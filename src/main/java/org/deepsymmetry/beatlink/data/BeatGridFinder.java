package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.dbserver.Client;
import org.deepsymmetry.beatlink.dbserver.ConnectionManager;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.beatlink.dbserver.NumberField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>Watches for new metadata to become available for tracks loaded on players, and queries the
 * appropriate player for the track beat grid when that happens.</p>
 *
 * <p>Maintains a hot cache of beat grids for any track currently loaded in a player, either on the main playback
 * deck, or as a hot cue, since those tracks could start playing instantly.</p>
 *
 * <p>Implicitly honors the active/passive setting of the {@link MetadataFinder}
 * (see {@link MetadataFinder#setPassive(boolean)}), because beat grids are loaded in response to metadata updates.</p>
 *
 */
@API(status = API.Status.STABLE)
public class BeatGridFinder extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(BeatGridFinder.class);

    /**
     * Keeps track of the current beat grids cached for each player. We hot cache beat grids for any track which is
     * currently on-deck in the player, as well as any that were loaded into a player's hot-cue slot.
     */
    private final Map<DeckReference, BeatGrid> hotCache = new ConcurrentHashMap<>();

    /**
     * A queue used to hold metadata updates we receive from the {@link MetadataFinder} so we can process them on a
     * lower priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private final LinkedBlockingDeque<TrackMetadataUpdate> pendingUpdates = new LinkedBlockingDeque<>(100);

    /**
     * Our metadata listener just puts metadata updates on our queue, so we can process them on a lower
     * priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private final TrackMetadataListener metadataListener = update -> {
        logger.debug("Received metadata update {}", update);
        if (!pendingUpdates.offerLast(update)) {
            logger.warn("Discarding metadata update because our queue is backed up.");
        }
    };

    /**
     * Our mount listener evicts any cached beat grids that belong to media databases which have been unmounted, since
     * they are no longer valid.
     */
    private final MountListener mountListener = new MountListener() {
        @Override
        public void mediaMounted(SlotReference slot) {
            logger.debug("BeatGridFinder doesn't yet need to do anything in response to a media mount.");
        }

        @Override
        public void mediaUnmounted(SlotReference slot) {
            // Iterate over a copy to avoid concurrent modification issues
            for (Map.Entry<DeckReference, BeatGrid> entry : new HashMap<>(hotCache).entrySet()) {
                if (slot == SlotReference.getSlotReference(entry.getValue().dataReference)) {
                    logger.debug("Evicting cached beat grid in response to unmount report {}", entry.getValue());
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
            logger.debug("Currently nothing for BeatGridFinder to do when devices appear.");
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            if (announcement.getDeviceNumber() == 25 && announcement.getDeviceName().equals("NXS-GW")) {
                logger.debug("Ignoring noise from Kuvo gateways, especially in CDJ-3000s, which fight each other and come and go constantly.");
                return;
            }
            logger.info("Clearing beat grids in response to the loss of a device, {}", announcement);
            clearBeatGrids(announcement);
        }
    };

    /**
     * Keep track of whether we are running
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Check whether we are currently running. Unless the {@link MetadataFinder} is in passive mode, we will
     * automatically request beat grids from the appropriate player when a new track is loaded that is not found
     * in the hot cache or a downloaded metadata export file.
     *
     * @return true if beat grids are being kept track of for all active players
     *
     * @see MetadataFinder#isPassive()
     */
    @API(status = API.Status.STABLE)
    public boolean isRunning() {
        return running.get();
    }

    /**
     * We process our player status updates on a separate thread so as not to slow down the high-priority update
     * delivery thread; we perform potentially slow I/O.
     */
    private Thread queueHandler;

    /**
     * We have received an update that invalidates the beat grid for a player, so clear it and alert
     * any listeners if this represents a change. This does not affect the hot cues; they will stick around until the
     * player loads a new track that overwrites one or more of them.
     *
     * @param update the update which means we have no beat grid for the associated player
     */
    private void clearDeck(TrackMetadataUpdate update) {
        if (hotCache.remove(DeckReference.getDeckReference(update.player, 0)) != null) {
            deliverBeatGridUpdate(update.player, null);
        }
    }

    /**
     * We have received notification that a device is no longer on the network, so clear out all its beat grids.
     *
     * @param announcement the packet which reported the device’s disappearance
     */
    private void clearBeatGrids(DeviceAnnouncement announcement) {
        final int player = announcement.getDeviceNumber();
        // Iterate over a copy to avoid concurrent modification issues
        for (DeckReference deck : new HashSet<>(hotCache.keySet())) {
            if (deck.player == player) {
                hotCache.remove(deck);
                if (deck.hotCue == 0) {
                    deliverBeatGridUpdate(player, null);  // Inform listeners the beat grid is gone.
                }
            }
        }
    }

    /**
     * We have obtained a beat grid for a device, so store it and alert any listeners.
     *
     * @param update the update which caused us to retrieve this beat grid
     * @param beatGrid the beat grid which we retrieved
     */
    private void updateBeatGrid(TrackMetadataUpdate update, BeatGrid beatGrid) {
        hotCache.put(DeckReference.getDeckReference(update.player, 0), beatGrid);  // Main deck
        if (update.metadata.getCueList() != null) {  // Update the cache with any hot cues in this track as well
            for (CueList.Entry entry : update.metadata.getCueList().entries) {
                if (entry.hotCueNumber != 0) {
                    hotCache.put(DeckReference.getDeckReference(update.player, entry.hotCueNumber), beatGrid);
                }
            }
        }
        deliverBeatGridUpdate(update.player, beatGrid);
    }

    /**
     * Get the beat grids available for all tracks currently loaded in any player, either on the play deck, or
     * in a hot cue.
     *
     * @return the beat grids associated with all current players, including for any tracks loaded in their hot cue slots
     *
     * @throws IllegalStateException if the BeatGridFinder is not running
     */
    @API(status = API.Status.STABLE)
    public Map<DeckReference, BeatGrid> getLoadedBeatGrids() {
        ensureRunning();
        // Make a copy so callers get an immutable snapshot of the current state.
        return Map.copyOf(hotCache);
    }

    /**
     * Look up the beat grid we have for the track loaded in the main deck of a given player number.
     *
     * @param player the device number whose beat grid for the playing track is desired
     *
     * @return the beat grid for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the BeatGridFinder is not running
     */
    @API(status = API.Status.STABLE)
    public BeatGrid getLatestBeatGridFor(int player) {
        ensureRunning();
        return hotCache.get(DeckReference.getDeckReference(player, 0));
    }

    /**
     * Look up the beat grid we have for a given player, identified by a status update received from that player.
     *
     * @param update a status update from the player for which a beat grid is desired
     *
     * @return the beat grid for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the BeatGridFinder is not running
     */
    @API(status = API.Status.STABLE)
    public BeatGrid getLatestBeatGridFor(DeviceUpdate update) {
        BeatGrid result = getLatestBeatGridFor(update.getDeviceNumber());
        if (result != null && (update instanceof CdjStatus) &&
                result.dataReference.rekordboxId != ((CdjStatus) update).getRekordboxId()) {
            return null;
        }
        return result;
    }

    /**
     * Ask the specified player for the beat grid in the specified slot with the specified rekordbox ID,
     * using downloaded media instead if it is available, and possibly giving up if we are in passive mode.
     *
     * @param trackReference uniquely identifies the desired beat grid
     * @param failIfPassive will prevent the request from taking place if we are in passive mode, so that automatic
     *                      beat grid updates will use available caches and downloaded metadata exports only
     *
     * @return the beat grid found, if any
     */
    private BeatGrid requestBeatGridInternal(final DataReference trackReference, final boolean failIfPassive) {

        // First see if any registered metadata providers can offer it to us.
        final MediaDetails sourceDetails = MetadataFinder.getInstance().getMediaDetailsFor(trackReference.getSlotReference());
        if (sourceDetails !=  null) {
            final BeatGrid provided = MetadataFinder.getInstance().allMetadataProviders.getBeatGrid(sourceDetails, trackReference);
            if (provided != null) {
                return provided;
            }
        }

        // At this point, unless we are allowed to actively request the data, we are done. We can always actively
        // request tracks from rekordbox.
        if (MetadataFinder.getInstance().isPassive() && failIfPassive && trackReference.slot != CdjStatus.TrackSourceSlot.COLLECTION) {
            return null;
        }

        // We have to actually request the beat grid using the dbserver protocol.
        ConnectionManager.ClientTask<BeatGrid> task =
                client -> getBeatGrid(trackReference.rekordboxId, SlotReference.getSlotReference(trackReference), client);

        try {
            return ConnectionManager.getInstance().invokeWithClientSession(trackReference.player, task, "requesting beat grid");
        } catch (Exception e) {
            logger.error("Problem requesting beat grid, returning null", e);
        }
        return null;
    }

    /**
     * Ask the specified player for the beat grid of the track in the specified slot with the specified rekordbox ID,
     * first checking if we have a cache we can use instead.
     *
     * @param track uniquely identifies the track whose beat grid is desired
     *
     * @return the beat grid, if any
     */
    @API(status = API.Status.STABLE)
    public BeatGrid requestBeatGridFrom(final DataReference track) {
        for (BeatGrid cached : hotCache.values()) {
            if (cached.dataReference.equals(track)) {  // Found a hot cue hit, use it.
                return cached;
            }
        }
        return requestBeatGridInternal(track, false);
    }

    /**
     * Requests the beat grid for a specific track ID, given a connection to a player that has already been set up.

     * @param rekordboxId the track of interest
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved beat grid, or {@code null} if there was none available
     *
     * @throws IOException if there is a communication problem
     */
    BeatGrid getBeatGrid(int rekordboxId, SlotReference slot, Client client)
            throws IOException {
        Message response = client.simpleRequest(Message.KnownType.BEAT_GRID_REQ, null,
                client.buildRMST(Message.MenuIdentifier.DATA, slot.slot), new NumberField(rekordboxId));
        if (response.knownType == Message.KnownType.BEAT_GRID) {
            return new BeatGrid(new DataReference(slot, rekordboxId), response);
        }
        logger.error("Unexpected response type when requesting beat grid: {}", response);
        return null;
    }

    /**
     * Keep track of the devices we are currently trying to get beat grids from in response to metadata updates.
     */
    private final Set<Integer> activeRequests = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Keeps track of the registered beat grid listeners.
     */
    private final Set<BeatGridListener> beatGridListeners = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * <p>Adds the specified beat grid listener to receive updates when the beat grid information for a player changes.
     * If {@code listener} is {@code null} or already present in the set of registered listeners, no exception is
     * thrown and no action is performed.</p>
     *
     * <p>To reduce latency, updates are delivered to listeners directly on the thread that is receiving packets
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the album art update listener to add
     */
    @API(status = API.Status.STABLE)
    public void addBeatGridListener(BeatGridListener listener) {
        if (listener != null) {
            beatGridListeners.add(listener);
        }
    }

    /**
     * Removes the specified beat grid listener so that it no longer receives updates when the
     * beat grid information for a player changes. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the waveform listener to remove
     */
    @API(status = API.Status.STABLE)
    public void removeBeatGridListener(BeatGridListener listener) {
        if (listener != null) {
            beatGridListeners.remove(listener);
        }
    }

    /**
     * Get the set of currently-registered beat grid listeners.
     *
     * @return the listeners that are currently registered for beat grid updates
     */
    @API(status = API.Status.STABLE)
    public Set<BeatGridListener> getBeatGridListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Set.copyOf(beatGridListeners);
    }

    /**
     * Send a beat grid update announcement to all registered listeners.
     *
     * @param player the player whose beat grid information has changed
     * @param beatGrid the new beat grid associated with that player, if any
     */
    private void deliverBeatGridUpdate(int player, BeatGrid beatGrid) {
        if (!getBeatGridListeners().isEmpty()) {
            final BeatGridUpdate update = new BeatGridUpdate(player, beatGrid);
            for (final BeatGridListener listener : getBeatGridListeners()) {
                try {
                    listener.beatGridChanged(update);

                } catch (Throwable t) {
                    logger.warn("Problem delivering beat grid update to listener", t);
                }
            }
        }
    }

    /**
     * Process a metadata update from the {@link MetadataFinder}, and see if it means the beat grid information
     * associated with any player has changed.
     *
     * @param update describes the new metadata we have for a player, if any
     */
    private void handleUpdate(final TrackMetadataUpdate update) {
        if (update.metadata == null || update.metadata.trackType != CdjStatus.TrackType.REKORDBOX) {
            clearDeck(update);
        } else {
            // We can offer beat grid information for this device; check if we've already looked it up.
            final BeatGrid lastBeatGrid = hotCache.get(DeckReference.getDeckReference(update.player, 0));
            if (lastBeatGrid == null || !lastBeatGrid.dataReference.equals(update.metadata.trackReference)) {  // We have something new!

                // First see if we can find the new preview in the hot cache
                for (BeatGrid cached : hotCache.values()) {
                    if (cached.dataReference.equals(update.metadata.trackReference)) {  // Found a hot cue hit, use it.
                        updateBeatGrid(update, cached);
                        return;
                    }
                }

                // Not in the cache so try actually retrieving it.
                if (activeRequests.add(update.player)) {  // We had to make sure we were not already asking for this track.
                    clearDeck(update);  // We won't know what it is until our request completes.

                    new Thread(() -> {
                        try {
                            BeatGrid grid = requestBeatGridInternal(update.metadata.trackReference, true);
                            if (grid != null && grid.beatCount > 0) {
                                updateBeatGrid(update, grid);
                            }
                        } catch (Exception e) {
                            logger.warn("Problem requesting beat grid from update {}", update, e);
                        } finally {
                            activeRequests.remove(update.player);
                        }
                    }, "Beat Grid request").start();
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
            logger.debug("The BeatGridFinder does not auto-start when {} does.", sender);
        }

        @Override
        public void stopped(LifecycleParticipant sender) {
            if (isRunning()) {
                logger.info("BeatGridFinder stopping because {} has.", sender);
                stop();
            }
        }
    };

    /**
     * <p>Start finding beat grids for all active players. Starts the {@link MetadataFinder} if it is not already
     * running, because we need it to send us metadata updates to notice when new tracks are loaded. This in turn
     * starts the {@link DeviceFinder}, so we can keep track of the comings and goings of players themselves.
     * We also start the {@link ConnectionManager} in order to make queries to obtain beat grids.</p>
     *
     * @throws Exception if there is a problem starting the required components
     */
    @API(status = API.Status.STABLE)
    public synchronized void start() throws Exception {
        if (!isRunning()) {
            ConnectionManager.getInstance().addLifecycleListener(lifecycleListener);
            ConnectionManager.getInstance().start();
            DeviceFinder.getInstance().addDeviceAnnouncementListener(announcementListener);
            MetadataFinder.getInstance().addLifecycleListener(lifecycleListener);
            MetadataFinder.getInstance().start();
            MetadataFinder.getInstance().addTrackMetadataListener(metadataListener);
            MetadataFinder.getInstance().addMountListener(mountListener);
            queueHandler = new Thread(() -> {
                while (isRunning()) {
                    try {
                        handleUpdate(pendingUpdates.take());
                    } catch (InterruptedException e) {
                        // Interrupted due to MetadataFinder shutdown, presumably
                    } catch (Throwable t) {
                        logger.error("Problem processing metadata update", t);
                    }
                }
            });
            running.set(true);
            queueHandler.start();
            deliverLifecycleAnnouncement(logger, true);

            // Send ourselves "updates" about any tracks that were loaded before we started, since we missed those.
            SwingUtilities.invokeLater(() -> {
                for (Map.Entry<DeckReference, TrackMetadata> entry : MetadataFinder.getInstance().getLoadedTracks().entrySet()) {
                    if (entry.getKey().hotCue == 0) {  // The track is currently loaded in a main player deck
                        handleUpdate(new TrackMetadataUpdate(entry.getKey().player, entry.getValue()));
                    }
                }
            });
        }
    }

    /**
     * Stop finding beat grids for all active players.
     */
    @API(status = API.Status.STABLE)
    public synchronized void stop() {
        if (isRunning()) {
            MetadataFinder.getInstance().removeTrackMetadataListener(metadataListener);
            running.set(false);
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;

            // Report the loss of our previews, on the proper thread, and outside our lock.
            final Set<DeckReference> dyingCache = new HashSet<>(hotCache.keySet());
            SwingUtilities.invokeLater(() -> {
                for (DeckReference deck : dyingCache) {
                    if (deck.hotCue == 0) {
                        deliverBeatGridUpdate(deck.player, null);
                    }
                }
            });
            hotCache.clear();
            deliverLifecycleAnnouncement(logger, false);
        }
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final BeatGridFinder ourInstance = new BeatGridFinder();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    @API(status = API.Status.STABLE)
    public static BeatGridFinder getInstance() {
        return ourInstance;
    }

    /**
     * Prevent instantiation.
     */
    private BeatGridFinder() {
        // Nothing to do
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("BeatGridFinder[running:").append(isRunning()).append(", passive:");
        sb.append(MetadataFinder.getInstance().isPassive());
        if (isRunning()) {
            sb.append(", loadedBeatGrids:").append(getLoadedBeatGrids());
        }
        return sb.append("]").toString();
    }
}
