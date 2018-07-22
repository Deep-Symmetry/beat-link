package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.dbserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * <p>Watches for new metadata to become available for tracks loaded on players, and queries the
 * appropriate player for track waveforms when that happens. Can be configured to load only the small waveform
 *  previews, or both those and the full scrollable waveform details.</p>
 *
 * <p>Maintains a hot cache of waveforms for any track currently loaded in a player, either on the main playback
 * deck, or as a hot cue, since those tracks could start playing instantly.</p>
 *
 * <p>Implicitly honors the active/passive setting of the {@link MetadataFinder}
 * (see {@link MetadataFinder#setPassive(boolean)}), because art is loaded in response to metadata updates.</p>
 *
 * @author James Elliott
 */
public class WaveformFinder extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(WaveformFinder.class);

    /**
     * Keeps track of the current waveform previews cached for each player. We hot cache art for any track which is
     * currently on-deck in the player, as well as any that were loaded into a player's hot-cue slot.
     */
    private final Map<DeckReference, WaveformPreview> previewHotCache =
            new ConcurrentHashMap<DeckReference, WaveformPreview>();

    /**
     * Keeps track of the current waveform preview cached for each player. We hot cache art for any track which is
     * currently on-deck in the player, as well as any that were loaded into a player's hot-cue slot.
     */
    private final Map<DeckReference, WaveformDetail> detailHotCache =
            new ConcurrentHashMap<DeckReference, WaveformDetail>();

    /**
     * Should we ask for details as well as the previews?
     */
    final private AtomicBoolean findDetails = new AtomicBoolean(false);

    /**
     * Set whether we should retrieve the waveform details in addition to the waveform previews.
     *
     * @param findDetails if {@code true}, both types of waveform will be retrieved, if {@code false} only previews
     *                    will be retrieved
     */
    public final void setFindDetails(boolean findDetails) {
        this.findDetails.set(findDetails);
        if (findDetails) {
            primeCache();  // Get details for any tracks that were already loaded on players.
        } else {
            // Inform our listeners, on the proper thread, that the detailed waveforms are no longer available
            final Set<DeckReference> dyingCache = new HashSet<DeckReference>(detailHotCache.keySet());
            detailHotCache.clear();
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    for (DeckReference deck : dyingCache) {
                        deliverWaveformDetailUpdate(deck.player, null);
                    }
                }
            });
        }
    }

    /**
     * Check whether we are retrieving the waveform details in addition to the waveform previews.
     *
     * @return {@code true} if both types of waveform are being retrieved, {@code false} if only previews
     *         are being retrieved
     */
    @SuppressWarnings("WeakerAccess")
    public final boolean isFindingDetails() {
        return findDetails.get();
    }

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
     * Our mount listener evicts any cached waveforms that belong to media databases which have been unmounted, since
     * they are no longer valid.
     */
    private final MountListener mountListener = new MountListener() {
        @Override
        public void mediaMounted(SlotReference slot) {
            logger.debug("WaveformFinder doesn't yet need to do anything in response to a media mount.");
        }

        @Override
        public void mediaUnmounted(SlotReference slot) {
            // Iterate over a copy to avoid concurrent modification issues
            for (Map.Entry<DeckReference, WaveformPreview> entry : new HashMap<DeckReference, WaveformPreview>(previewHotCache).entrySet()) {
                if (slot == SlotReference.getSlotReference(entry.getValue().dataReference)) {
                    logger.debug("Evicting cached waveform preview in response to unmount report {}", entry.getValue());
                    previewHotCache.remove(entry.getKey());
                }
            }
            // Again iterate over a copy to avoid concurrent modification issues
            for (Map.Entry<DeckReference, WaveformDetail> entry : new HashMap<DeckReference, WaveformDetail>(detailHotCache).entrySet()) {
                if (slot == SlotReference.getSlotReference(entry.getValue().dataReference)) {
                    logger.debug("Evicting cached waveform detail in response to unmount report {}", entry.getValue());
                    detailHotCache.remove(entry.getKey());
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
            logger.info("Clearing waveforms in response to the loss of a device, {}", announcement);
            clearWaveforms(announcement);
        }
    };

    /**
     * Keep track of whether we are running
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Check whether we are currently running. Unless the {@link MetadataFinder} is in passive mode, we will
     * automatically request waveforms from the appropriate player when a new track is loaded that is not found
     * in the hot cache or an attached metadata cache file.
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
     * We have received an update that invalidates the waveform preview for a player, so clear it and alert
     * any listeners if this represents a change. This does not affect the hot cues; they will stick around until the
     * player loads a new track that overwrites one or more of them.
     *
     * @param update the update which means we have no waveform preview for the associated player
     */
    private void clearDeckPreview(TrackMetadataUpdate update) {
        if (previewHotCache.remove(DeckReference.getDeckReference(update.player, 0)) != null) {
            deliverWaveformPreviewUpdate(update.player, null);
        }
    }

    /**
     * We have received an update that invalidates the waveform detail for a player, so clear it and alert
     * any listeners if this represents a change. This does not affect the hot cues; they will stick around until the
     * player loads a new track that overwrites one or more of them.
     *
     * @param update the update which means we have no waveform preview for the associated player
     */
    private void clearDeckDetail(TrackMetadataUpdate update) {
        if (detailHotCache.remove(DeckReference.getDeckReference(update.player, 0)) != null) {
            deliverWaveformDetailUpdate(update.player, null);
        }
    }

    /**
     * We have received an update that invalidates any previous metadata for a player, so clear its waveforms, and alert
     * any listeners if this represents a change. This does not affect the hot cues; they will stick around until the
     * player loads a new track that overwrites one or more of them.
     *
     * @param update the update which means we have no metadata for the associated player
     */
    private void clearDeck(TrackMetadataUpdate update) {
        clearDeckPreview(update);
        clearDeckDetail(update);
    }

    /**
     * We have received notification that a device is no longer on the network, so clear out all its waveforms.
     *
     * @param announcement the packet which reported the device’s disappearance
     */
    private void clearWaveforms(DeviceAnnouncement announcement) {
        final int player = announcement.getNumber();
        // Iterate over a copy to avoid concurrent modification issues
        for (DeckReference deck : new HashSet<DeckReference>(previewHotCache.keySet())) {
            if (deck.player == player) {
                previewHotCache.remove(deck);
            }
        }
        // Again iterate over a copy to avoid concurrent modification issues
        for (DeckReference deck : new HashSet<DeckReference>(detailHotCache.keySet())) {
            if (deck.player == player) {
                detailHotCache.remove(deck);
            }
        }
    }

    /**
     * We have obtained a waveform preview for a device, so store it and alert any listeners.
     *
     * @param update the update which caused us to retrieve this waveform preview
     * @param preview the waveform preview which we retrieved
     */
    private void updatePreview(TrackMetadataUpdate update, WaveformPreview preview) {
        previewHotCache.put(DeckReference.getDeckReference(update.player, 0), preview);  // Main deck
        if (update.metadata.getCueList() != null) {  // Update the cache with any hot cues in this track as well
            for (CueList.Entry entry : update.metadata.getCueList().entries) {
                if (entry.hotCueNumber != 0) {
                    previewHotCache.put(DeckReference.getDeckReference(update.player, entry.hotCueNumber), preview);
                }
            }
        }
        deliverWaveformPreviewUpdate(update.player, preview);
    }

    /**
     * We have obtained waveform detail for a device, so store it and alert any listeners.
     *
     * @param update the update which caused us to retrieve this waveform detail
     * @param detail the waveform detail which we retrieved
     */
    private void updateDetail(TrackMetadataUpdate update, WaveformDetail detail) {
        detailHotCache.put(DeckReference.getDeckReference(update.player, 0), detail);  // Main deck
        if (update.metadata.getCueList() != null) {  // Update the cache with any hot cues in this track as well
            for (CueList.Entry entry : update.metadata.getCueList().entries) {
                if (entry.hotCueNumber != 0) {
                    detailHotCache.put(DeckReference.getDeckReference(update.player, entry.hotCueNumber), detail);
                }
            }
        }
        deliverWaveformDetailUpdate(update.player, detail);
    }

    /**
     * Get the waveform previews available for all tracks currently loaded in any player, either on the play deck, or
     * in a hot cue.
     *
     * @return the previews associated with all current players, including for any tracks loaded in their hot cue slots
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    @SuppressWarnings("WeakerAccess")
    public Map<DeckReference, WaveformPreview> getLoadedPreviews() {
        ensureRunning();
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableMap(new HashMap<DeckReference, WaveformPreview>(previewHotCache));
    }

    /**
     * Get the waveform details available for all tracks currently loaded in any player, either on the play deck, or
     * in a hot cue.
     *
     * @return the details associated with all current players, including for any tracks loaded in their hot cue slots
     *
     * @throws IllegalStateException if the WaveformFinder is not running or requesting waveform details
     */
    @SuppressWarnings("WeakerAccess")
    public Map<DeckReference, WaveformDetail> getLoadedDetails() {
        ensureRunning();
        if (!isFindingDetails()) {
            throw new IllegalStateException("WaveformFinder is not configured to find waveform details.");
        }
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableMap(new HashMap<DeckReference, WaveformDetail>(detailHotCache));
    }

    /**
     * Look up the waveform preview we have for the track loaded in the main deck of a given player number.
     *
     * @param player the device number whose waveform preview for the playing track is desired
     *
     * @return the waveform preview for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    @SuppressWarnings("WeakerAccess")
    public WaveformPreview getLatestPreviewFor(int player) {
        ensureRunning();
        return previewHotCache.get(DeckReference.getDeckReference(player, 0));
    }

    /**
     * Look up the waveform preview we have for a given player, identified by a status update received from that player.
     *
     * @param update a status update from the player for which a waveform preview is desired
     *
     * @return the waveform preview for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    public WaveformPreview getLatestPreviewFor(DeviceUpdate update) {
        return getLatestPreviewFor(update.getDeviceNumber());
    }

    /**
     * Look up the waveform detail we have for the track loaded in the main deck of a given player number.
     *
     * @param player the device number whose waveform detail for the playing track is desired
     *
     * @return the waveform detail for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    @SuppressWarnings("WeakerAccess")
    public WaveformDetail getLatestDetailFor(int player) {
        ensureRunning();
        return detailHotCache.get(DeckReference.getDeckReference(player, 0));
    }

    /**
     * Look up the waveform detail we have for a given player, identified by a status update received from that player.
     *
     * @param update a status update from the player for which waveform detail is desired
     *
     * @return the waveform detail for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    public WaveformDetail getLatestDetailFor(DeviceUpdate update) {
        return getLatestDetailFor(update.getDeviceNumber());
    }

    /**
     * Ask the specified player for the waveform preview in the specified slot with the specified rekordbox ID,
     * using cached media instead if it is available, and possibly giving up if we are in passive mode.
     *
     * @param trackReference uniquely identifies the desired waveform preview
     * @param failIfPassive will prevent the request from taking place if we are in passive mode, so that automatic
     *                      waveform updates will use available caches only
     *
     * @return the waveform preview found, if any
     */
    private WaveformPreview requestPreviewInternal(final DataReference trackReference, final boolean failIfPassive) {

        // First check if we are using cached data for this slot
        ZipFile cache = MetadataFinder.getInstance().getMetadataCache(SlotReference.getSlotReference(trackReference));
        if (cache != null) {
            return getCachedWaveformPreview(cache, trackReference);
        }

        if (MetadataFinder.getInstance().isPassive() && failIfPassive) {
            // We are not allowed to perform actual requests in passive mode.
            return null;
        }

        // We have to actually request the preview.
        ConnectionManager.ClientTask<WaveformPreview> task = new ConnectionManager.ClientTask<WaveformPreview>() {
            @Override
            public WaveformPreview useClient(Client client) throws Exception {
                return getWaveformPreview(trackReference.rekordboxId, SlotReference.getSlotReference(trackReference), client);
            }
        };

        try {
            return ConnectionManager.getInstance().invokeWithClientSession(trackReference.player, task, "requesting waveform preview");
        } catch (Exception e) {
            logger.error("Problem requesting waveform preview, returning null", e);
        }
        return null;
    }

    /**
     * Ask the specified player for the specified waveform preview from the specified media slot, first checking if we
     * have a cached copy.
     *
     * @param dataReference uniquely identifies the desired waveform preview
     *
     * @return the preview, if it was found, or {@code null}
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    public WaveformPreview requestWaveformPreviewFrom(final DataReference dataReference) {
        ensureRunning();
        for (WaveformPreview cached : previewHotCache.values()) {
            if (cached.dataReference.equals(dataReference)) {  // Found a hot cue hit, use it.
                return cached;
            }
        }
        return requestPreviewInternal(dataReference, false);
    }

    /**
     * Look up a waveform preview in a metadata cache.
     *
     * @param cache the appropriate metadata cache file
     * @param dataReference the unique database specification of the desired waveform preview
     *
     * @return the cached waveform preview (if available), or {@code null}
     */
    @SuppressWarnings("WeakerAccess")
    public WaveformPreview getCachedWaveformPreview(ZipFile cache, DataReference dataReference) {
        ZipEntry entry = cache.getEntry(MetadataFinder.getInstance().getWaveformPreviewEntryName(dataReference.rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(cache.getInputStream(entry));
                Message message = Message.read(is);
                return new WaveformPreview(dataReference, message);
            } catch (IOException e) {
                logger.error("Problem reading waveform preview from cache file, returning null", e);
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (Exception e) {
                        logger.error("Problem closing ZipFile input stream for waveform preview", e);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Requests the waveform preview for a specific track ID, given a connection to a player that has already been
     * set up.
     *
     * @param rekordboxId the track whose waveform preview is desired
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved waveform preview, or {@code null} if none was available
     * @throws IOException if there is a communication problem
     */
    WaveformPreview getWaveformPreview(int rekordboxId, SlotReference slot, Client client)
            throws IOException {
        Message response = client.simpleRequest(Message.KnownType.WAVE_PREVIEW_REQ, null,
                client.buildRMST(Message.MenuIdentifier.DATA, slot.slot), NumberField.WORD_1,
                new NumberField(rekordboxId), NumberField.WORD_0);
        if (response.knownType == Message.KnownType.WAVE_PREVIEW) {
            return new WaveformPreview(new DataReference(slot, rekordboxId), response);
        }
        logger.error("Unexpected response type when requesting waveform preview: {}", response);
        return null;
    }

    /**
     * Ask the specified player for the waveform detail in the specified slot with the specified rekordbox ID,
     * using cached media instead if it is available, and possibly giving up if we are in passive mode.
     *
     * @param trackReference uniquely identifies the desired waveform detail
     * @param failIfPassive will prevent the request from taking place if we are in passive mode, so that automatic
     *                      artwork updates will use available caches only
     *
     * @return the waveform preview found, if any
     */
    private WaveformDetail requestDetailInternal(final DataReference trackReference, final boolean failIfPassive) {

        // First check if we are using cached data for this slot
        ZipFile cache = MetadataFinder.getInstance().getMetadataCache(SlotReference.getSlotReference(trackReference));
        if (cache != null) {
            return getCachedWaveformDetail(cache, trackReference);
        }

        if (MetadataFinder.getInstance().isPassive() && failIfPassive) {
            // We are not allowed to perform actual requests in passive mode.
            return null;
        }

        // We have to actually request the preview.
        ConnectionManager.ClientTask<WaveformDetail> task = new ConnectionManager.ClientTask<WaveformDetail>() {
            @Override
            public WaveformDetail useClient(Client client) throws Exception {
                return getWaveformDetail(trackReference.rekordboxId, SlotReference.getSlotReference(trackReference), client);
            }
        };

        try {
            return ConnectionManager.getInstance().invokeWithClientSession(trackReference.player, task, "requesting waveform detail");
        } catch (Exception e) {
            logger.error("Problem requesting waveform preview, returning null", e);
        }
        return null;
    }

    /**
     * Ask the specified player for the specified waveform detail from the specified media slot, first checking if we
     * have a cached copy.
     *
     * @param dataReference uniquely identifies the desired waveform detail
     *
     * @return the waveform detail, if it was found, or {@code null}
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    public WaveformDetail requestWaveformDetailFrom(final DataReference dataReference) {
        ensureRunning();
        for (WaveformDetail cached : detailHotCache.values()) {
            if (cached.dataReference.equals(dataReference)) {  // Found a hot cue hit, use it.
                return cached;
            }
        }
        return requestDetailInternal(dataReference, false);
    }

    /**
     * Look up waveform detail in a metadata cache.
     *
     * @param cache the appropriate metadata cache file
     * @param dataReference the unique database specification of the desired waveform detail
     *
     * @return the cached waveform detail (if available), or {@code null}
     */
    @SuppressWarnings("WeakerAccess")
    public WaveformDetail getCachedWaveformDetail(ZipFile cache, DataReference dataReference) {
        ZipEntry entry = cache.getEntry(MetadataFinder.getInstance().getWaveformDetailEntryName(dataReference.rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(cache.getInputStream(entry));
                Message message = Message.read(is);
                return new WaveformDetail(dataReference, message);
            } catch (IOException e) {
                logger.error("Problem reading waveform detail from cache file, returning null", e);
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (Exception e) {
                        logger.error("Problem closing ZipFile input stream for waveform detail", e);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Requests the waveform detail for a specific track ID, given a connection to a player that has already been
     * set up.
     *
     * @param rekordboxId the track whose waveform detail is desired
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved waveform detail, or {@code null} if none was available
     * @throws IOException if there is a communication problem
     */
    WaveformDetail getWaveformDetail(int rekordboxId, SlotReference slot, Client client)
            throws IOException {
        Message response = client.simpleRequest(Message.KnownType.WAVE_DETAIL_REQ, null,
                client.buildRMST(Message.MenuIdentifier.MAIN_MENU, slot.slot),
                new NumberField(rekordboxId), NumberField.WORD_0);
        if (response.knownType == Message.KnownType.WAVE_DETAIL) {
            return new WaveformDetail(new DataReference(slot, rekordboxId), response);
        }
        logger.error("Unexpected response type when requesting waveform detail: {}", response);
        return null;
    }

    /**
     * Keep track of the devices we are currently trying to get previews from in response to metadata updates.
     */
    private final Set<Integer> activePreviewRequests = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    /**
     * Keep track of the devices we are currently trying to get details from in response to metadata updates.
     */
    private final Set<Integer> activeDetailRequests = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    /**
     * Keeps track of the registered waveform listeners.
     */
    private final Set<WaveformListener> waveformListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<WaveformListener, Boolean>());

    /**
     * <p>Adds the specified waveform listener to receive updates when the waveform information for a player changes.
     * If {@code listener} is {@code null} or already present in the set of registered listeners, no exception is
     * thrown and no action is performed.</p>
     *
     * <p>To reduce latency, updates are delivered to listeners directly on the thread that is receiving packets
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the album art update listener to add
     */
    public void addWaveformListener(WaveformListener listener) {
        if (listener != null) {
            waveformListeners.add(listener);
        }
    }

    /**
     * Removes the specified waveform listener so that it no longer receives updates when the
     * waveform information for a player changes. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the waveform listener to remove
     */
    public void removeWaveformListener(WaveformListener listener) {
        if (listener != null) {
            waveformListeners.remove(listener);
        }
    }

    /**
     * Get the set of currently-registered waveform listeners.
     *
     * @return the listeners that are currently registered for waveform updates
     */
    @SuppressWarnings("WeakerAccess")
    public Set<WaveformListener> getWaveformListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<WaveformListener>(waveformListeners));
    }

    /**
     * Send a waveform preview update announcement to all registered listeners.
     *
     * @param player the player whose waveform preview has changed
     * @param preview the new waveform preview, if any
     */
    private void deliverWaveformPreviewUpdate(final int player, final WaveformPreview preview) {
        if (!getWaveformListeners().isEmpty()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    final WaveformPreviewUpdate update = new WaveformPreviewUpdate(player, preview);
                    for (final WaveformListener listener : getWaveformListeners()) {
                        try {
                            listener.previewChanged(update);

                        } catch (Exception e) {
                            logger.warn("Problem delivering waveform preview update to listener", e);
                        }
                    }
                }
            });
        }
    }

    /**
     * Send a waveform detail update announcement to all registered listeners.

     * @param player the player whose waveform detail has changed
     * @param detail the new waveform detail, if any
     */
    private void deliverWaveformDetailUpdate(final int player, final WaveformDetail detail) {
        if (!getWaveformListeners().isEmpty()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    final WaveformDetailUpdate update = new WaveformDetailUpdate(player, detail);
                    for (final WaveformListener listener : getWaveformListeners()) {
                        try {
                            listener.detailChanged(update);

                        } catch (Exception e) {
                            logger.warn("Problem delivering waveform detail update to listener", e);
                        }
                    }
                }
            });
        }
    }

    /**
     * Process a metadata update from the {@link MetadataFinder}, and see if it means the waveform information
     * associated with any player has changed.
     *
     * @param update describes the new metadata we have for a player, if any
     */
    private void handleUpdate(final TrackMetadataUpdate update) {
        if (update.metadata == null || update.metadata.trackType != CdjStatus.TrackType.REKORDBOX) {
            clearDeck(update);
        } else {
            // We can offer waveform information for this device; check if we've already looked it up. First, preview:
            final WaveformPreview lastPreview = previewHotCache.get(DeckReference.getDeckReference(update.player, 0));
            if (lastPreview == null || !lastPreview.dataReference.equals(update.metadata.trackReference)) {  // We have something new!

                // First see if we can find the new preview in the hot cache
                for (WaveformPreview cached : previewHotCache.values()) {
                    if (cached.dataReference.equals(update.metadata.trackReference)) {  // Found a hot cue hit, use it.
                        updatePreview(update, cached);
                        return;
                    }
                }

                // Not in the cache so try actually retrieving it.
                if (activePreviewRequests.add(update.player)) {
                    clearDeckPreview(update);  // We won't know what it is until our request completes.
                    // We had to make sure we were not already asking for this track.
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                WaveformPreview preview = requestPreviewInternal(update.metadata.trackReference, true);
                                if (preview != null) {
                                    updatePreview(update, preview);
                                }
                            } catch (Exception e) {
                                logger.warn("Problem requesting waveform preview from update" + update, e);
                            } finally {
                                activePreviewRequests.remove(update.player);
                            }
                        }
                    }).start();
                }
            }
            // Secondly, the detail.
            final WaveformDetail lastDetail = detailHotCache.get(DeckReference.getDeckReference(update.player, 0));
            if (isFindingDetails() && (lastDetail == null || !lastDetail.dataReference.equals(update.metadata.trackReference))) {  // We have something new!

                // First see if we can find the new preview in the hot cache
                for (WaveformDetail cached : detailHotCache.values()) {
                    if (cached.dataReference.equals(update.metadata.trackReference)) {  // Found a hot cue hit, use it.
                        updateDetail(update, cached);
                        return;
                    }
                }

                // Not in the cache so try actually retrieving it.
                if (activeDetailRequests.add(update.player)) {
                    clearDeckDetail(update);  // We won't know what it is until our request completes.
                    // We had to make sure we were not already asking for this track.
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                WaveformDetail detail = requestDetailInternal(update.metadata.trackReference, true);
                                if (detail != null) {
                                    updateDetail(update, detail);
                                }
                            } catch (Exception e) {
                                logger.warn("Problem requesting waveform detail from update" + update, e);
                            } finally {
                                activeDetailRequests.remove(update.player);
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
            logger.debug("The WaveformFinder does not auto-start when {} does.", sender);
        }

        @Override
        public void stopped(LifecycleParticipant sender) {
            if (isRunning()) {
                logger.info("WaveformFinder stopping because {} has.", sender);
                stop();
            }
        }
    };

    /**
     * Send ourselves "updates" about any tracks that were loaded before we started, or before we were requesting
     * details, since we missed them.
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
     * <p>Start finding waveforms for all active players. Starts the {@link MetadataFinder} if it is not already
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
     * Stop finding waveforms for all active players.
     */
    @SuppressWarnings("WeakerAccess")
    public synchronized void stop() {
        if (isRunning()) {
            MetadataFinder.getInstance().removeTrackMetadataListener(metadataListener);
            running.set(false);
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;

            // Report the loss of our waveforms, on the proper thread, outside our lock
            final Set<DeckReference> dyingPreviewCache = new HashSet<DeckReference>(previewHotCache.keySet());
            previewHotCache.clear();
            final Set<DeckReference> dyingDetailCache = new HashSet<DeckReference>(detailHotCache.keySet());
            detailHotCache.clear();
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    for (DeckReference deck : dyingPreviewCache) {  // Report the loss of our previews.
                        if (deck.hotCue == 0) {
                            deliverWaveformPreviewUpdate(deck.player, null);
                        }
                    }
                    for (DeckReference deck : dyingDetailCache) {  // Report the loss of our details.
                        if (deck.hotCue == 0) {
                            deliverWaveformDetailUpdate(deck.player, null);
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
    private static final WaveformFinder ourInstance = new WaveformFinder();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    @SuppressWarnings("WeakerAccess")
    public static WaveformFinder getInstance() {
        return ourInstance;
    }

    /**
     * Prevent direct instantiation.
     */
    private WaveformFinder() {
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WaveformFinder[running:").append(isRunning()).append(", passive:");
        sb.append(MetadataFinder.getInstance().isPassive()).append(", findingDetails:").append(isFindingDetails());
        if (isRunning()) {
            sb.append(", loadedPreviews:").append(getLoadedPreviews());
            if (isFindingDetails()) {
                sb.append(", loadedDetails:").append(getLoadedDetails());
            }
        }
        return sb.append("]").toString();
    }
}
