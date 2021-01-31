package org.deepsymmetry.beatlink.data;

import io.kaitai.struct.ByteBufferKaitaiStream;
import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.dbserver.*;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>Watches for new metadata to become available for tracks loaded on players, and queries the
 * appropriate player for song structure (phrase analysis) information when that happens.</p>
 *
 * <p>Maintains a hot cache of structure information for any track currently loaded in a player, either on the main
 * playback deck, or as a hot cue, since those tracks could start playing instantly.</p>
 *
 * <p>Implicitly honors the active/passive setting of the {@link MetadataFinder}
 * (see {@link MetadataFinder#setPassive(boolean)}), because song structure is loaded in response to metadata updates.</p>
 *
 * @author James Elliott
 */
public class SongStructureFinder extends LifecycleParticipant  {

    private static final Logger logger = LoggerFactory.getLogger(SongStructureFinder.class);

    /**
     * Wraps values we store in our hot cache so we can keep track of the player, slot, and track it was associated
     * with.
     */
    public static class CacheEntry {

        /**
         * Identifies the track to which the song structure information belongs.
         */
        final DataReference dataReference;

        /**
         * The song structure information itself.
         */
        final RekordboxAnlz.SongStructureTag songStructure;

        /**
         * Constructor simply sets the immutable fields.
         *
         * @param dataReference the track to which the song structure information belongs
         * @param songStructure the song structure information itself
         */
        CacheEntry(DataReference dataReference, RekordboxAnlz.SongStructureTag songStructure) {
            this.dataReference = dataReference;
            this.songStructure = songStructure;
        }
    }

    /**
     * Keeps track of the current analysis tags cached for each player. We hot cache data for any track which is
     * currently on-deck in the player, as well as any that were loaded into a player's hot-cue slot.
     */
    private final Map<DeckReference, CacheEntry> hotCache =
            new ConcurrentHashMap<DeckReference, CacheEntry>();

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
     * Our mount listener evicts any cached song structures that belong to media databases which have been unmounted,
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
            for (Map.Entry<DeckReference, CacheEntry> entry : new HashMap<DeckReference, CacheEntry>(hotCache).entrySet()) {
                if (slot == SlotReference.getSlotReference(entry.getValue().dataReference)) {
                    logger.debug("Evicting cached song structure in response to unmount report {}", entry.getValue());
                    hotCache.remove(entry.getKey());
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
            clearSongStructures(announcement);
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
     * We have received an update that invalidates the song structure for a player, so clear it and alert
     * any listeners if this represents a change. This does not affect the hot cues; they will stick around until the
     * player loads a new track that overwrites one or more of them.
     *
     * @param update the update which means we have no song structure for the associated player
     */
    private void clearDeckSongStructure(TrackMetadataUpdate update) {
        if (hotCache.remove(DeckReference.getDeckReference(update.player, 0)) != null) {
            deliverSongStructureUpdate(update.player, null);
        }
    }

    /**
     * We have received notification that a device is no longer on the network, so clear out all its song structures.
     *
     * @param announcement the packet which reported the device’s disappearance
     */
    private void clearSongStructures(DeviceAnnouncement announcement) {
        final int player = announcement.getDeviceNumber();
        // Iterate over a copy to avoid concurrent modification issues
        for (DeckReference deck : new HashSet<DeckReference>(hotCache.keySet())) {
            if (deck.player == player) {
                hotCache.remove(deck);
                if (deck.hotCue == 0) {
                    deliverSongStructureUpdate(player, null);  // Inform listeners that preview is gone.
                }
            }
        }
    }

    /**
     * We have obtained a song structure for a device, so store it and alert any listeners.
     *
     * @param update the update which caused us to retrieve this song structure
     * @param structure the song structure which we retrieved
     */
    private void updateSongStructure(TrackMetadataUpdate update, RekordboxAnlz.SongStructureTag structure) {
        CacheEntry cacheEntry = new CacheEntry(update.metadata.trackReference, structure);
        hotCache.put(DeckReference.getDeckReference(update.player, 0), cacheEntry);  // Main deck
        if (update.metadata.getCueList() != null) {  // Update the cache with any hot cues in this track as well
            for (CueList.Entry entry : update.metadata.getCueList().entries) {
                if (entry.hotCueNumber != 0) {
                    hotCache.put(DeckReference.getDeckReference(update.player, entry.hotCueNumber), cacheEntry);
                }
            }
        }
        deliverSongStructureUpdate(update.player, structure);
    }

    /**
     * Get the song structures available for all tracks currently loaded in any player, either on the play deck, or
     * in a hot cue.
     *
     * @return the cache entries associated with all current players, including for any tracks loaded in their hot cue slots
     *
     * @throws IllegalStateException if the SongStructureFinder is not running
     */
    @SuppressWarnings("WeakerAccess")
    public Map<DeckReference, CacheEntry> getLoadedSongStructures() {
        ensureRunning();
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableMap(new HashMap<DeckReference, CacheEntry>(hotCache));
    }

    /**
     * Look up the song structure we have for the track loaded in the main deck of a given player number.
     *
     * @param player the device number whose song structure information for the playing track is desired
     *
     * @return the song structure for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the SongStructureFinder is not running
     */
    public RekordboxAnlz.SongStructureTag getLatestSongStructureFor(int player) {
        ensureRunning();
        final CacheEntry entry = hotCache.get(DeckReference.getDeckReference(player, 0));
        if (entry == null) {
            return null;
        }
        return entry.songStructure;
    }

    /**
     * Look up the song structure we have for a given player, identified by a status update received from that player.
     *
     * @param update a status update from the player for which song structure information is desired
     *
     * @return the song structure for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the SongStructureFinder is not running
     */
    public RekordboxAnlz.SongStructureTag getLatestSongStructureFor(DeviceUpdate update) {
        return getLatestSongStructureFor(update.getDeviceNumber());
    }

    /**
     * Ask the specified player for the song structure in the specified slot with the specified rekordbox ID,
     * using downloaded media instead if it is available, and possibly giving up if we are in passive mode.
     *
     * @param trackReference uniquely identifies the track whose song structure is desired
     * @param failIfPassive will prevent the request from taking place if we are in passive mode, so that automatic
     *                      song structure updates will use available downloaded media files only
     *
     * @return the song structure information found, if any
     */
    private RekordboxAnlz.SongStructureTag requestSongStructureInternal(final DataReference trackReference, final boolean failIfPassive) {

        // TODO: This class seems likely to be pretty easy to generalize to spawn instances that retrieve any category of ANLZ data!
        // First see if any registered metadata providers can offer it for us (i.e. Crate Digger, probably).
        final MediaDetails sourceDetails = MetadataFinder.getInstance().getMediaDetailsFor(trackReference.getSlotReference());
        if (sourceDetails !=  null) {
            final RekordboxAnlz.TaggedSection provided = MetadataFinder.getInstance().allMetadataProviders.getAnalysisSection(
                    sourceDetails, trackReference, "EXT", "PSSI");
            if (provided != null) {
                return (RekordboxAnlz.SongStructureTag) provided.body();
            }
        }

        // At this point, unless we are allowed to actively request the data, we are done. We can always actively
        // request tracks from rekordbox.
        if (MetadataFinder.getInstance().isPassive() && failIfPassive && trackReference.slot != CdjStatus.TrackSourceSlot.COLLECTION) {
            return null;
        }

        // We have to actually request the preview using the dbserver protocol.
        ConnectionManager.ClientTask<RekordboxAnlz.SongStructureTag> task = new ConnectionManager.ClientTask<RekordboxAnlz.SongStructureTag>() {
            @Override
            public RekordboxAnlz.SongStructureTag useClient(Client client) {
                return getSongStructureViaDbServer(trackReference.rekordboxId, SlotReference.getSlotReference(trackReference), client);
            }
        };

        try {
            return ConnectionManager.getInstance().invokeWithClientSession(trackReference.player, task, "requesting song structure");
        } catch (Exception e) {
            logger.error("Problem requesting song structure, returning null", e);
        }
        return null;
    }

    /**
     * Ask the specified player for the specified song structure information from the specified media slot, first checking if we
     * have a cached copy.
     *
     * @param dataReference uniquely identifies the desired song structure information
     *
     * @return the song structure, if it was found, or {@code null}
     *
     * @throws IllegalStateException if the SongStructureFinder is not running
     */
    public RekordboxAnlz.SongStructureTag requestSongStructureFrom(final DataReference dataReference) {
        ensureRunning();
        for (CacheEntry cached : hotCache.values()) {
            if (cached.dataReference.equals(dataReference)) {  // Found a hot cue hit, use it.
                return cached.songStructure;
            }
        }
        return requestSongStructureInternal(dataReference, false);
    }

    /**
     * Requests the song structure for a specific track ID, given a connection to a player that has already been
     * set up.
     *
     * @param rekordboxId the track whose song structure is desired
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved song structure information, or {@code null} if none was available
     */
    RekordboxAnlz.SongStructureTag getSongStructureViaDbServer(int rekordboxId, SlotReference slot, Client client) {

        final NumberField idField = new NumberField(rekordboxId);

        try {
            Message response = client.simpleRequest(Message.KnownType.ANLZ_TAG_REQ, Message.KnownType.ANLZ_TAG,
                    client.buildRMST(Message.MenuIdentifier.MAIN_MENU, slot.slot), idField,
                    new NumberField(Message.ANLZ_FILE_TAG_SONG_STRUCTURE), new NumberField(Message.ALNZ_FILE_TYPE_EXT));
            if (response.knownType != Message.KnownType.UNAVAILABLE && response.arguments.get(3).getSize() > 0) {
                ByteBuffer data = ((BinaryField) response.arguments.get(3)).getValue();
                data.position(16);  // Skip the length and general tag header bytes, so we are at the PSSI tag itself.
                data = data.slice();
                // DEBUGGING SECTION TO BE REMOVED:
                File file = new File("/Users/jim/Desktop/PSSI.dat");
                FileChannel channel = new FileOutputStream(file, false).getChannel();
                channel.write(data);
                channel.close();
                data.rewind();
                // END SECTION TO BE REMOVED.
                return new RekordboxAnlz.SongStructureTag(new ByteBufferKaitaiStream(data));
            }
        } catch (Exception e) {
                logger.info("Problem requesting song structure information for slot " + slot + ", id " + rekordboxId, e);
        }
        return null;
    }

    /**
     * Keep track of the devices we are currently trying to get song structures from in response to metadata updates.
     */
    private final Set<Integer> activeRequests = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    /**
     * Keeps track of the registered song structure listeners.
     */
    private final Set<SongStructureListener> songStructureListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<SongStructureListener, Boolean>());

    /**
     * <p>Adds the specified structure listener to receive updates when the song structure information for a player changes.
     * If {@code listener} is {@code null} or already present in the set of registered listeners, no exception is
     * thrown and no action is performed.</p>
     *
     * <p>Updates are delivered to listeners on the Swing Event Dispatch thread, so it is safe to interact with
     * user interface elements within the event handler.
     *
     * Even so, any code in the listener method <em>must</em> finish quickly, or it will freeze the user interface,
     * add latency for other listeners, and updates will back up. If you want to perform lengthy processing of any sort,
     * do so on another thread.</p>
     *
     * @param listener the song structure update listener to add
     */
    public void addSongStructureListener(SongStructureListener listener) {
        if (listener != null) {
            songStructureListeners.add(listener);
        }
    }

    /**
     * Removes the specified song structure listener so that it no longer receives updates when the
     * song structure information for a player changes. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the song structure update listener to remove
     */
    public void removeSongStructureListener(SongStructureListener listener) {
        if (listener != null) {
            songStructureListeners.remove(listener);
        }
    }

    /**
     * Get the set of currently-registered song structure listeners.
     *
     * @return the listeners that are currently registered for song structure updates
     */
    @SuppressWarnings("WeakerAccess")
    public Set<SongStructureListener> getSongStructureListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<SongStructureListener>(songStructureListeners));
    }

    /**
     * Send a song structure update announcement to all registered listeners.
     *
     * @param player the player whose waveform preview has changed
     * @param structure the new song structure information, if any
     */
    private void deliverSongStructureUpdate(final int player, final RekordboxAnlz.SongStructureTag structure) {
        final Set<SongStructureListener> listeners = getSongStructureListeners();
        if (!listeners.isEmpty()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    final SongStructureUpdate update = new SongStructureUpdate(player, structure);
                    for (final SongStructureListener listener : listeners) {
                        try {
                            listener.structureChanged(update);

                        } catch (Throwable t) {
                            logger.warn("Problem delivering song structure update to listener", t);
                        }
                    }
                }
            });
        }
    }

    /**
     * Process a metadata update from the {@link MetadataFinder}, and see if it means the song structure information
     * associated with any player has changed.
     *
     * @param update describes the new metadata we have for a player, if any
     */
    private void handleUpdate(final TrackMetadataUpdate update) {
        boolean foundInCache = false;

        if (update.metadata == null || update.metadata.trackType != CdjStatus.TrackType.REKORDBOX) {
            clearDeckSongStructure(update);
        } else {
            // We can offer song structure information for this device; check if we've already looked it up.
            final CacheEntry lastStructure = hotCache.get(DeckReference.getDeckReference(update.player, 0));
            if (lastStructure == null || !lastStructure.dataReference.equals(update.metadata.trackReference)) {  // We have something new!

                // First see if we can find the new preview in the hot cache
                for (CacheEntry cached : hotCache.values()) {
                    if (cached.dataReference.equals(update.metadata.trackReference)) {  // Found a hot cue hit, use it.
                        updateSongStructure(update, cached.songStructure);
                        foundInCache = true;
                        break;
                    }
                }

                // If not found in the cache try actually retrieving it.
                if (!foundInCache && activeRequests.add(update.player)) {
                    clearDeckSongStructure(update);  // We won't know what it is until our request completes.
                    // We had to make sure we were not already asking for this track.
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                RekordboxAnlz.SongStructureTag structure = requestSongStructureInternal(update.metadata.trackReference, true);
                                if (structure != null) {
                                    updateSongStructure(update, structure);
                                }
                            } catch (Exception e) {
                                logger.warn("Problem requesting song structure information from update" + update, e);
                            } finally {
                                activeRequests.remove(update.player);
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
            logger.debug("The SongStructureFinder does not auto-start when {} does.", sender);
        }

        @Override
        public void stopped(LifecycleParticipant sender) {
            if (isRunning()) {
                logger.info("SongStructureFinder stopping because {} has.", sender);
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
     * <p>Start finding song structures for all active players. Starts the {@link MetadataFinder} if it is not already
     * running, because we need it to send us metadata updates to notice when new tracks are loaded. This in turn
     * starts the {@link DeviceFinder}, so we can keep track of the comings and goings of players themselves.
     * We also start the {@link ConnectionManager} in order to make queries to obtain waveforms.</p>
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
     * Stop finding song structure information for all active players.
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
            final Set<DeckReference> dyingCache = new HashSet<DeckReference>(hotCache.keySet());
            hotCache.clear();
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    for (DeckReference deck : dyingCache) {  // Report the loss of our previews.
                        if (deck.hotCue == 0) {
                            deliverSongStructureUpdate(deck.player, null);
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
    private static final SongStructureFinder ourInstance = new SongStructureFinder();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    public static SongStructureFinder getInstance() {
        return ourInstance;
    }

    /**
     * Prevent direct instantiation.
     */
    private SongStructureFinder() {
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SongStructureFinder[running:").append(isRunning()).append(", passive:");
        sb.append(MetadataFinder.getInstance().isPassive());
        if (isRunning()) {
            sb.append(", loadedSongStructures:").append(getLoadedSongStructures());
        }
        return sb.append("]").toString();
    }
}
