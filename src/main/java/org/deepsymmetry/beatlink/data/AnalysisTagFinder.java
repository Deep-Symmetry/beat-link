package org.deepsymmetry.beatlink.data;

import io.kaitai.struct.ByteBufferKaitaiStream;
import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.dbserver.*;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>Watches for new metadata to become available for tracks loaded on players, and queries the
 * appropriate player for any desired track analysis sections (identified by the analysis file extension
 * and four-character tag type code) when that happens. Clients can express interest in any number of
 * different tag types, and each unique combination will be retrieved once, and sent to any listeners
 * who registered interest in that type. This allows new tags to be supported, as they are discovered
 * and parsed by <a href="https://github.com/Deep-Symmetry/crate-digger">Crate Digger</a> without any
 * code changes to Beat Link.</p>
 *
 * <p>Maintains a hot cache of analysis tag information for any track currently loaded in a player, either on the main
 * playback deck, or as a hot cue, since those tracks could start playing instantly.</p>
 *
 * <p>Implicitly honors the active/passive setting of the {@link MetadataFinder}
 * (see {@link MetadataFinder#setPassive(boolean)}), because tags are loaded in response to metadata updates.</p>
 *
 * @author James Elliott
 */
public class AnalysisTagFinder extends LifecycleParticipant  {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisTagFinder.class);

    /**
     * Wraps values we store in our hot cache so we can keep track of the player, slot, track, file extension,
     * and type tag the analysis section was associated with.
     */
    public static class CacheEntry {

        /**
         * Identifies the track to which the analysis section belongs.
         */
        public final DataReference dataReference;

        /**
         * Identifies the specific analysis file from which the cached section was loaded.
         */
        public final String fileExtension;

        /**
         * The four-character type code identifying the specific section of the analysis file that was cached.
         */
        public final String typeTag;

        /**
         * The parsed analysis file section itself.
         */
        public final RekordboxAnlz.TaggedSection taggedSection;

        /**
         * Constructor simply sets the immutable fields.
         *
         * @param dataReference the track to which the song structure information belongs
         * @param fileExtension identifies the specific analysis file from which the cached section was loaded
         * @param typeTag the four-character type code identifying the specific section of the analysis file that was cached
         * @param taggedSection the parsed analysis file section itself
         */
        CacheEntry(DataReference dataReference, String fileExtension, String typeTag, RekordboxAnlz.TaggedSection taggedSection) {
            this.dataReference = dataReference;
            this.fileExtension = fileExtension;
            this.typeTag = typeTag;
            this.taggedSection = taggedSection;
        }
    }

    /**
     * Keeps track of the current analysis tags cached for each player, sub-indexed by type (fileExtension + typeTag).
     * We hot cache data for any track which is currently on-deck in the player, as well as any that were
     * loaded into a player's hot-cue slot.
     */
    private final ConcurrentHashMap<DeckReference, ConcurrentHashMap<String, CacheEntry>> hotCache =
            new ConcurrentHashMap<DeckReference, ConcurrentHashMap<String, CacheEntry>>();

    /**
     * A queue used to hold metadata updates we receive from the {@link MetadataFinder} so we can process them on a
     * lower priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private final LinkedBlockingDeque<TrackMetadataUpdate> pendingUpdates =
            new LinkedBlockingDeque<TrackMetadataUpdate>(100);

    /**
     * Our metadata listener just puts metadata updates on our queue, so we can process them on a lower
     * priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private final TrackMetadataListener metadataListener = new TrackMetadataListener() {
        @Override
        public void metadataChanged(TrackMetadataUpdate update) {
            logger.debug("Received metadata update {}", update);
            if (!pendingUpdates.offerLast(update)) {
                logger.warn("Discarding metadata update because our queue is backed up.");
            }
        }
    };

    /**
     * Our mount listener evicts any cached tags that belong to media databases which have been unmounted,
     * since they are no longer valid.
     */
    private final MountListener mountListener = new MountListener() {
        @Override
        public void mediaMounted(SlotReference slot) {
            logger.debug("SongStructureFinder doesn't yet need to do anything in response to a media mount.");
        }

        @Override
        public void mediaUnmounted(SlotReference slot) {
            // Iterate over a copy to avoid concurrent modification issues
            for (Map.Entry<DeckReference, Map<String, CacheEntry>> deckEntry : new HashMap<DeckReference, Map<String, CacheEntry>>(hotCache).entrySet()) {
                for (Map.Entry<String, CacheEntry> typeEntry : new HashMap<String, CacheEntry>(deckEntry.getValue()).entrySet()) {
                    if (slot == SlotReference.getSlotReference(typeEntry.getValue().dataReference)) {
                        logger.debug("Evicting cached track analysis sections in response to unmount report {}", typeEntry.getValue());
                        final Map<String, CacheEntry> deckCache = hotCache.get(deckEntry.getKey());
                        CacheEntry removed = null;
                        if (deckCache != null) removed = deckCache.remove(typeEntry.getKey());
                        if (deckEntry.getKey().hotCue == 0 && removed != null) {
                            // We removed something that listeners cared about.
                            deliverAnalysisTagUpdate(deckEntry.getKey().player, removed.fileExtension, removed.typeTag, null);
                        }
                    }
                }
            }
        }
    };

    /**
     * Our announcement listener watches for devices to disappear from the network so we can discard all information
     * about them.
     */
    private final DeviceAnnouncementListener announcementListener = new DeviceAnnouncementListener() {
        @Override
        public void deviceFound(final DeviceAnnouncement announcement) {
            logger.debug("Currently nothing for WaveformFinder to do when devices appear.");
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            logger.info("Clearing song structures in response to the loss of a device, {}", announcement);
            clearTags(announcement);
        }
    };

    /**
     * Keep track of whether we are running
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Check whether we are currently running. Unless the {@link MetadataFinder} is in passive mode, we will
     * automatically request waveforms from the appropriate player when a new track is loaded that is not found
     * in the hot cache or a downloaded analysis file.
     *
     * @return true if waveforms are being kept track of for all active players
     *
     * @see MetadataFinder#isPassive()
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * We process our player status updates on a separate thread so as not to slow down the high-priority update
     * delivery thread; we perform potentially slow I/O.
     */
    private Thread queueHandler;

    /**
     * We have received an update that invalidates the tags for a player, so clear them and alert any
     * listeners if this represents a change. This does not affect the hot cues; they will stick around until the
     * player loads a new track that overwrites one or more of them.
     *
     * @param update the update which means we have no tags for the associated player
     */
    private void clearDeckTags(TrackMetadataUpdate update) {
        final Map<String, CacheEntry> oldTags = hotCache.remove(DeckReference.getDeckReference(update.player, 0));
        if (oldTags != null) {
            deliverTagLossUpdate(update.player, oldTags);
        }
    }

    /**
     * We have received notification that a device is no longer on the network, so clear out all its tags.
     *
     * @param announcement the packet which reported the device’s disappearance
     */
    private void clearTags(DeviceAnnouncement announcement) {
        final int player = announcement.getDeviceNumber();
        // Iterate over a copy to avoid concurrent modification issues
        for (DeckReference deck : new HashSet<DeckReference>(hotCache.keySet())) {
            if (deck.player == player) {
                final Map<String, CacheEntry> oldTags = hotCache.remove(deck);
                if (deck.hotCue == 0) {
                    deliverTagLossUpdate(player, oldTags);  // Inform listeners that preview is gone.
                }
            }
        }
    }

    /**
     * We have obtained a track analysis section for a device, so store it and alert any listeners.
     *
     * @param update the update which caused us to retrieve this track analysis tag
     * @param fileExtension identifies the specific analysis file for which an update is available
     * @param typeTag the four-character type code identifying the specific section of the analysis file that has changed
     * @param analysisTag the parsed track analysis section which we retrieved
     */
    private void updateAnalysisTag(final TrackMetadataUpdate update, final String fileExtension, final String typeTag, RekordboxAnlz.TaggedSection analysisTag) {
        final CacheEntry cacheEntry = new CacheEntry(update.metadata.trackReference, fileExtension, typeTag, analysisTag);
        final String tagKey = typeTag + fileExtension;
        ConcurrentHashMap<String, CacheEntry> newDeckMap = new ConcurrentHashMap<String, CacheEntry>();
        ConcurrentHashMap<String, CacheEntry> deckMap = hotCache.putIfAbsent(DeckReference.getDeckReference(update.player, 0), newDeckMap);
        if (deckMap == null) deckMap = newDeckMap;  // We added a new deck reference to the hot cache.
        deckMap.put(tagKey, cacheEntry);
        if (update.metadata.getCueList() != null) {  // Update the cache with any hot cues in this track as well
            for (CueList.Entry entry : update.metadata.getCueList().entries) {
                if (entry.hotCueNumber != 0) {
                    newDeckMap = new ConcurrentHashMap<String, CacheEntry>();
                    deckMap = hotCache.putIfAbsent(DeckReference.getDeckReference(update.player, entry.hotCueNumber), newDeckMap);
                    if (deckMap == null) deckMap = newDeckMap;  // We added a new deck reference to the hot cache.
                    deckMap.put(tagKey, cacheEntry);
                }
            }
        }
        deliverAnalysisTagUpdate(update.player, fileExtension, typeTag, analysisTag);
    }

    /**
     * Get the analysis tags available for all tracks currently loaded in any player, either on the play deck, or
     * in a hot cue. Returns a map of deck references to maps of typeTag + fileExtension to the actual cache entries.
     *
     * @return the cache entries associated with all current players, including for any tracks loaded in their hot cue slots
     *
     * @throws IllegalStateException if the AnalysisTagFinder is not running
     */
    @SuppressWarnings("WeakerAccess")
    public Map<DeckReference, Map<String, CacheEntry>> getLoadedAnalysisTags() {
        ensureRunning();
        // Make a copy so callers get an immutable snapshot of the current state.
        final Map<DeckReference, Map<String, CacheEntry>> result = new HashMap<DeckReference, Map<String, CacheEntry>>();
        for (Map.Entry<DeckReference, Map<String, CacheEntry>> entry: new HashMap<DeckReference, Map<String, CacheEntry>>(hotCache).entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableMap(new HashMap<String, CacheEntry>(entry.getValue())));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Look up the analysis tag of a specific type we have for the track loaded in the main deck of a given player number.
     *
     * @param player the device number whose song structure information for the playing track is desired
     * @param fileExtension identifies the specific analysis file we are interested in a tag from
     * @param typeTag the four-character type code identifying the specific section of the analysis file desired
     *
     * @return the specified parsed track analysis tag for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the AnalysisTagFinder is not running
     */
    public RekordboxAnlz.TaggedSection getLatestTrackAnalysisFor(final int player, final String fileExtension, final String typeTag) {
        ensureRunning();
        final Map<String, CacheEntry> deckTags = hotCache.get(DeckReference.getDeckReference(player, 0));
        if (deckTags == null) {
            return null;
        }
        final CacheEntry entry = deckTags.get(typeTag + fileExtension);
        if (entry == null) {
            return null;
        }
        return entry.taggedSection;
    }

    /**
     * Look up the track analysis tag of a specified type we have for a given player, identified by a status update received from that player.
     *
     * @param update a status update from the player for which track analysis information is desired
     * @param fileExtension identifies the specific analysis file we are interested in a tag from
     * @param typeTag the four-character type code identifying the specific section of the analysis file desired
     *
     * @return the desired parsed track analysis section for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the AnalysisTagFinder is not running
     */
    public RekordboxAnlz.TaggedSection getLatestTrackAnalysisFor(final DeviceUpdate update, final String fileExtension, final String typeTag) {
        return getLatestTrackAnalysisFor(update.getDeviceNumber(), fileExtension, typeTag);
    }

    /**
     * Ask the specified player for the analysis tag in the specified slot with the specified rekordbox ID, file extension,
     * and tag type, using downloaded media instead if it is available, and possibly giving up if we are in passive mode.
     *
     * @param trackReference uniquely identifies the track whose analysis tag is desired
     * @param fileExtension identifies the specific analysis file we are interested in a tag from
     * @param typeTag the four-character type code identifying the specific section of the analysis file desired
     * @param failIfPassive will prevent the request from taking place if we are in passive mode, so that automatic
     *                      song structure updates will use available downloaded media files only
     *
     * @return the song structure information found, if any
     */
    private RekordboxAnlz.TaggedSection requestAnalysisTagInternal(final DataReference trackReference, final String fileExtension,
                                                                      final String typeTag, final boolean failIfPassive) {

        // First see if any registered metadata providers can offer it for us (i.e. Crate Digger, probably).
        final MediaDetails sourceDetails = MetadataFinder.getInstance().getMediaDetailsFor(trackReference.getSlotReference());
        if (sourceDetails !=  null) {
            final RekordboxAnlz.TaggedSection provided = MetadataFinder.getInstance().allMetadataProviders.getAnalysisSection(
                    sourceDetails, trackReference, fileExtension, typeTag);
            if (provided != null) {
                return provided;
            }
        }

        // At this point, unless we are allowed to actively request the data, we are done. We can always actively
        // request tracks from rekordbox.
        if (MetadataFinder.getInstance().isPassive() && failIfPassive && trackReference.slot != CdjStatus.TrackSourceSlot.COLLECTION) {
            return null;
        }

        // We have to actually request the analysis using the dbserver protocol.
        ConnectionManager.ClientTask<RekordboxAnlz.TaggedSection> task = new ConnectionManager.ClientTask<RekordboxAnlz.TaggedSection>() {
            @Override
            public RekordboxAnlz.TaggedSection useClient(Client client) {
                return getTagViaDbServer(trackReference.rekordboxId, SlotReference.getSlotReference(trackReference), fileExtension, typeTag, client);
            }
        };

        try {
            return ConnectionManager.getInstance().invokeWithClientSession(trackReference.player, task,
                    "requesting analysis tag of type " + typeTag + " from file with extension " + fileExtension);
        } catch (Exception e) {
            logger.error("Problem requesting analysis tag, returning null", e);
        }
        return null;
    }

    /**
     * Ask the specified player for the specified track analysis information from the specified media slot, first
     * checking if we have a cached copy.
     *
     * @param dataReference uniquely identifies the track from which we want analysis information
     * @param fileExtension identifies the specific analysis file we are interested in a tag from
     * @param typeTag the four-character type code identifying the specific section of the analysis file desired
     *
     * @return the parsed track analysis information, if it was found, or {@code null}
     *
     * @throws IllegalStateException if the AnalysisTagFinder is not running
     */
    public RekordboxAnlz.TaggedSection requestSongStructureFrom(final DataReference dataReference,
                                                                final String fileExtension, final String typeTag) {
        ensureRunning();
        final String tagKey = typeTag + fileExtension;
        for (Map<String, CacheEntry> deckCache : hotCache.values()) {
            CacheEntry cached = deckCache.get(tagKey);
                if (cached != null && cached.dataReference.equals(dataReference)) {
                    // Found a hot cue hit, use it.
                    return cached.taggedSection;
            }
        }
        return requestAnalysisTagInternal(dataReference, fileExtension, typeTag, false);
    }

    /**
     * Converts a short (up to four character) string to the byte-reversed integer value used to represent that
     * string in dbserver protocol requests for track analysis tags.
     *
     * @param s the string to be converted
     * @return the numeric field the protocol uses to represent that string.
     */
    public NumberField stringToProtocolNumber(final String s) {
        int fourcc = 0;  // Convert the type tag to a byte-reversed integer as used in the protocol.
        for (int i = 3; i >= 0; i--) {
            fourcc = fourcc * 256;
            if (i < (s.length())) {
                fourcc = fourcc + s.charAt(i);
            }
        }
        return  new NumberField(fourcc);
    }

    /**
     * Requests a specific analysis file section for a specific track ID, given a connection to a player
     * that has already been set up.
     *
     * @param rekordboxId the track whose song structure is desired
     * @param slot identifies the media slot we are querying
     * @param fileExtension identifies the specific analysis file we are interested in a tag from
     * @param typeTag the four-character type code identifying the specific section of the analysis file desired
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved track analysis section, or {@code null} if none was available
     */
    RekordboxAnlz.TaggedSection getTagViaDbServer(final int rekordboxId, final SlotReference slot,
                                                  final String fileExtension, final String typeTag, final Client client) {
        final NumberField idField = new NumberField(rekordboxId);
        final NumberField fileField = stringToProtocolNumber(fileExtension);
        final NumberField tagField = stringToProtocolNumber(typeTag);

        try {
            Message response = client.simpleRequest(Message.KnownType.ANLZ_TAG_REQ, Message.KnownType.ANLZ_TAG,
                    client.buildRMST(Message.MenuIdentifier.MAIN_MENU, slot.slot), idField, tagField, fileField);
            if (response.knownType != Message.KnownType.UNAVAILABLE && response.arguments.get(3).getSize() > 0) {
                ByteBuffer data = ((BinaryField) response.arguments.get(3)).getValue();
                data.position(4);  // Skip the length so we are at the tag four-character identifier.
                data = data.slice();
                return new RekordboxAnlz.TaggedSection(new ByteBufferKaitaiStream(data));
            }
        } catch (Exception e) {
                logger.info("Problem requesting song structure information for slot " + slot + ", id " + rekordboxId, e);
        }
        return null;
    }

    /**
     * Keep track of the devices and specific tags we are currently trying to get analysis sections from in response
     * to metadata updates. Keys are the device number concatenated with a colon, typeTag, and fileExtension.
     */
    private final Set<String> activeRequests = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * Keeps track of the registered tag listeners, indexed by the type of tag they are listening for.
     */
    private final Map<String, Set<AnalysisTagListener>> analysisTagListeners = new ConcurrentHashMap<String, Set<AnalysisTagListener>>();

    /**
     * <p>Adds the specified listener to receive updates when track analysis information of a specific type for a player changes.
     * If {@code listener} is {@code null} or already present in the set of registered listeners for the specified file
     * extension and tag type, no exception is thrown and no action is performed.</p>
     *
     * <p>Updates are delivered to listeners on the Swing Event Dispatch thread, so it is safe to interact with
     * user interface elements within the event handler.
     *
     * Even so, any code in the listener method <em>must</em> finish quickly, or it will freeze the user interface,
     * add latency for other listeners, and updates will back up. If you want to perform lengthy processing of any sort,
     * do so on another thread.</p>
     *
     * @param listener the track analysis update listener to add
     * @param fileExtension identifies the specific analysis file for which the listener wants tag updates (such as ".DAT" and ".EXT")
     * @param typeTag the four-character type code identifying the specific section of the analysis file desired
     */
    public synchronized void addAnalysisTagListener(final AnalysisTagListener listener, final String fileExtension, final String typeTag) {
        if (listener != null) {
            final String tagKey = typeTag + fileExtension;
            boolean trackingNewTag = false;
            Set<AnalysisTagListener> specificTagListeners = analysisTagListeners.get(tagKey);
            if (specificTagListeners == null) {
                trackingNewTag = true;
                specificTagListeners = Collections.newSetFromMap(new ConcurrentHashMap<AnalysisTagListener, Boolean>());
                analysisTagListeners.put(tagKey, specificTagListeners);
            }
            specificTagListeners.add(listener);
            if (trackingNewTag) primeCache();  // Someone is interested in something new, so go get it.
        }
    }

    /**
     * Removes the specified listener so that it no longer receives updates when track analysis information of a
     * specific type for a player changes. If {@code listener} is {@code null} or not present
     * in the set of registered listeners for that tag type, no exception is thrown and no action is performed.
     *
     * @param listener the track analysis update listener to remove
     * @param fileExtension identifies the specific analysis file for which the listener no longer wants tag updates
     * @param typeTag the four-character type code identifying the specific section of the analysis file no longer desired
     */
    public synchronized void removeAnalysisTagListener(final AnalysisTagListener listener, final String fileExtension, final String typeTag) {
        if (listener != null) {
            final String tagKey = typeTag + fileExtension;
            Set<AnalysisTagListener> specificTagListeners = analysisTagListeners.get(tagKey);
            if (specificTagListeners != null) {
                specificTagListeners.remove(listener);
                if (specificTagListeners.isEmpty()) {  // No listeners left of this type, remove the parent entry.
                    analysisTagListeners.remove(tagKey);
                }
            }
        }
    }

    /**
     * Get the sets of currently-registered analysis tag listeners. Returns a map whose keys are strings formed by
     * concatenating each pair of typeTag and fileExtension strings that a registered listener has requested, and
     * whose values are the sets of listeners interested in that type of analysis tag.
     *
     * @return the listeners that are currently registered for track analysis updates, indexed by typeTag + fileExtension
     */
    @SuppressWarnings("WeakerAccess")
    public Map<String, Set<AnalysisTagListener>> getTagListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        final Map<String, Set<AnalysisTagListener>> result = new HashMap<String, Set<AnalysisTagListener>>();
        for (Map.Entry<String, Set<AnalysisTagListener>> entry : new HashMap<String, Set<AnalysisTagListener>>(analysisTagListeners).entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableSet(new HashSet<AnalysisTagListener>(entry.getValue())));
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Send a track analysis tag update announcement to all registered listeners interested in the tag type.
     *
     * @param player the player whose waveform preview has changed
     * @param fileExtension identifies the specific analysis file for which an update is available
     * @param typeTag the four-character type code identifying the specific section of the analysis file that has changed
     * @param taggedSection the new parsed track analysis information, if any
     */
    private void deliverAnalysisTagUpdate(final int player, final String fileExtension, final String typeTag, final RekordboxAnlz.TaggedSection taggedSection) {
        final Set<AnalysisTagListener> currentListeners = analysisTagListeners.get(typeTag + fileExtension);
        if (currentListeners != null) {
            // Iterate over a copy to avoid concurrent modification issues.
            final Set<AnalysisTagListener> listeners = new HashSet<AnalysisTagListener>(currentListeners);
            if (!listeners.isEmpty()) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        final AnalysisTagUpdate update = new AnalysisTagUpdate(player, fileExtension, typeTag, taggedSection);
                        for (final AnalysisTagListener listener : listeners) {
                            try {
                                listener.analysisChanged(update);

                            } catch (Throwable t) {
                                logger.warn("Problem delivering track analysis tag update to listener", t);
                            }
                        }
                    }
                });
            }
        }
    }

    /**
     * Report that a set of analysis tags is no longer available.
     *
     * @param player the player for which we have lost information
     * @param tags the tags which are no longer relevant
     */
    private void deliverTagLossUpdate(final int player, final Map<String, CacheEntry> tags) {
        // We don’t need to copy the map because it has already been removed from the hot cache, so there is no
        // risk of concurrent modification.
        for (CacheEntry entry : tags.values()) {
            deliverAnalysisTagUpdate(player, entry.fileExtension, entry.typeTag, null);
        }
    }

    /**
     * Process a metadata update from the {@link MetadataFinder}, and see if it means the track analysis information
     * associated with any player has changed.
     *
     * @param update describes the new metadata we have for a player, if any
     */
    private void handleUpdate(final TrackMetadataUpdate update) {
        if (update.metadata == null || update.metadata.trackType != CdjStatus.TrackType.REKORDBOX) {
            clearDeckTags(update);
        } else {
            // We can offer song structure information for this device; check if we've already got it in our cache.
            for (String trackedTag : analysisTagListeners.keySet()) {
                final String fileExtension = trackedTag.substring(trackedTag.indexOf("."));
                final String typeTag = trackedTag.substring(0, trackedTag.indexOf("."));
                final Map<String, CacheEntry> deckCache = hotCache.get(DeckReference.getDeckReference(update.player, 0));
                final CacheEntry lastStructure = (deckCache != null) ? deckCache.get(trackedTag) : null;
                if (lastStructure == null || !lastStructure.dataReference.equals(update.metadata.trackReference)) {  // We have something new!
                    // First see if we can find the new information in the hot cache.
                    boolean foundInCache = false;
                    for (Map<String, CacheEntry> tagCache : hotCache.values()) {
                        final CacheEntry cached = tagCache.get(trackedTag);
                        if (cached != null && cached.dataReference.equals(update.metadata.trackReference)) {
                            // Found a hot cue hit, use it.
                            updateAnalysisTag(update, fileExtension, typeTag, cached.taggedSection);
                            foundInCache = true;
                            break;
                        }
                    }

                    // If not found in the cache try actually retrieving it.
                    final String activeKey = update.player + ":" + trackedTag;
                    if (!foundInCache && activeRequests.add(activeKey))
                        clearDeckTags(update);  // We won't know what it is until our request completes.
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                RekordboxAnlz.TaggedSection structure = requestAnalysisTagInternal(
                                        update.metadata.trackReference, fileExtension, typeTag, true);
                                if (structure != null) {
                                    updateAnalysisTag(update, fileExtension, typeTag, structure);
                                }
                            } catch (Exception e) {
                                logger.warn("Problem requesting analysis tag of type " + typeTag + " in file with extension " +
                                        fileExtension + " from update" + update, e);
                            } finally {
                                activeRequests.remove(activeKey);
                            }
                        }
                    }).start();
                }
            }
        }
    }


    /**
     * Set up to automatically stop if anything we depend on stops.
     */
    private final LifecycleListener lifecycleListener = new LifecycleListener() {
        @Override
        public void started(LifecycleParticipant sender) {
            logger.debug("The AnalysisTagFinder does not auto-start when {} does.", sender);
        }

        @Override
        public void stopped(LifecycleParticipant sender) {
            if (isRunning()) {
                logger.info("AnalysisTagFinder stopping because {} has.", sender);
                stop();
            }
        }
    };

    /**
     * Send ourselves "updates" about any tracks that were loaded before we started, or before we were requesting
     * song structures, since we missed them.
     */
    private void primeCache() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<DeckReference, TrackMetadata> entry : MetadataFinder.getInstance().getLoadedTracks().entrySet()) {
                    if (entry.getKey().hotCue == 0) {  // The track is currently loaded in a main player deck
                        handleUpdate(new TrackMetadataUpdate(entry.getKey().player, entry.getValue()));
                    }
                }
            }
        });
    }

    /**
     * <p>Start finding analysis tags for all active players. Starts the {@link MetadataFinder} if it is not already
     * running, because we need it to send us metadata updates to notice when new tracks are loaded. This in turn
     * starts the {@link DeviceFinder}, so we can keep track of the comings and goings of players themselves.
     * We also start the {@link ConnectionManager} in order to make queries to obtain analysis information.</p>
     *
     * @throws Exception if there is a problem starting the required components
     */
    public synchronized void start() throws Exception {
        if (!isRunning()) {
            ConnectionManager.getInstance().addLifecycleListener(lifecycleListener);
            ConnectionManager.getInstance().start();
            DeviceFinder.getInstance().addDeviceAnnouncementListener(announcementListener);
            MetadataFinder.getInstance().addLifecycleListener(lifecycleListener);
            MetadataFinder.getInstance().start();
            MetadataFinder.getInstance().addTrackMetadataListener(metadataListener);
            MetadataFinder.getInstance().addMountListener(mountListener);
            queueHandler = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (isRunning()) {
                        try {
                            handleUpdate(pendingUpdates.take());
                        } catch (InterruptedException e) {
                            // Interrupted due to MetadataFinder shutdown, presumably
                        } catch (Throwable t) {
                            logger.error("Problem processing metadata update", t);
                        }
                    }
                }
            });
            running.set(true);
            queueHandler.start();
            deliverLifecycleAnnouncement(logger, true);
            primeCache();
        }
    }

    /**
     * Stop finding analysis tag information for all active players.
     */
    @SuppressWarnings("WeakerAccess")
    public synchronized void stop() {
        if (isRunning()) {
            MetadataFinder.getInstance().removeTrackMetadataListener(metadataListener);
            running.set(false);
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;

            // Report the loss of our song structure information, on the proper thread, outside our lock.
            final Map<DeckReference, Map<String, CacheEntry>> dyingCache = new HashMap<DeckReference, Map<String, CacheEntry>>(hotCache);
            hotCache.clear();
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    for (Map.Entry<DeckReference, Map<String, CacheEntry>> entry : dyingCache.entrySet()) {  // Report the loss of our tags.
                        if (entry.getKey().hotCue == 0) {
                            deliverTagLossUpdate(entry.getKey().player, entry.getValue());
                        }
                    }
                }
            });
            deliverLifecycleAnnouncement(logger, false);
        }
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final AnalysisTagFinder ourInstance = new AnalysisTagFinder();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    public static AnalysisTagFinder getInstance() {
        return ourInstance;
    }

    /**
     * Prevent direct instantiation.
     */
    private AnalysisTagFinder() {
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("AnalysisTagFinder[running:").append(isRunning()).append(", passive:");
        sb.append(MetadataFinder.getInstance().isPassive());
        if (isRunning()) {
            sb.append(", loadedAnalysisTags:").append(getLoadedAnalysisTags());
        }
        return sb.append("]").toString();
    }
}
