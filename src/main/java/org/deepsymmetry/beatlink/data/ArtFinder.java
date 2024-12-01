package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.dbserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>Watches for new metadata to become available for tracks loaded on players, and queries the
 * appropriate player for the album art when that happens.</p>
 *
 * <p>Maintains a hot cache of art for any track currently loaded in a player, either on the main playback
 * deck, or as a hot cue, since those tracks could start playing instantly. Also maintains a second-level
 * in-memory cache of artwork, discarding the least-recently-used art when the cache fills, because tracks
 * can share artwork, so the DJ may load another track with the same album art.</p>
 *
 * <p>Implicitly honors the active/passive setting of the {@link MetadataFinder}
 * (see {@link MetadataFinder#setPassive(boolean)}), because art is loaded in response to metadata updates.</p>
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public class ArtFinder extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(ArtFinder.class);

    /**
     * Keeps track of the current album art cached for each player. We hot cache art for any track which is currently
     * on-deck in the player, as well as any that were loaded into a player's hot-cue slot.
     */
    private final Map<DeckReference, AlbumArt> hotCache = new ConcurrentHashMap<>();

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
     * Removes the specified art from our second-level cache if it was present there.
     *
     * @param artReference identifies the album art which should no longer be cached.
     */
    private synchronized void removeArtFromCache(DataReference artReference) {
        artCache.remove(artReference);
        artCacheRecentlyUsed.remove(artReference);
        artCacheEvictionQueue.remove(artReference);
    }

    /**
     * Our mount listener evicts any cached artwork that belong to media databases which have been unmounted, since
     * they are no longer valid.
     */
    private final MountListener mountListener = new MountListener() {
        @Override
        public void mediaMounted(SlotReference slot) {
            logger.debug("ArtFinder doesn't yet need to do anything in response to a media mount.");
        }

        @Override
        public void mediaUnmounted(SlotReference slot) {
            // Iterate over a copy to avoid concurrent modification issues.
            final Set<DataReference> keys = new HashSet<>(artCache.keySet());
            for (DataReference artReference : keys) {
                if (SlotReference.getSlotReference(artReference) == slot) {
                    logger.debug("Evicting cached artwork in response to unmount report {}", artReference);
                    removeArtFromCache(artReference);
                }
            }
            // Again iterate over a copy to avoid concurrent modification issues.
            final Set<Map.Entry<DeckReference,AlbumArt>> copy = new HashSet<>(hotCache.entrySet());
            for (Map.Entry<DeckReference, AlbumArt> entry : copy) {
                if (slot == SlotReference.getSlotReference(entry.getValue().artReference)) {
                    logger.debug("Evicting hot cached artwork in response to unmount report {}", entry.getValue());
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
            logger.debug("Currently nothing for ArtFinder to do when devices appear.");
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            logger.info("Clearing artwork in response to the loss of a device, {}", announcement);
            clearArt(announcement);
        }
    };

    /**
     * Keep track of whether we are running
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Check whether we are currently running. Unless the {@link MetadataFinder} is in passive mode, we will
     * automatically request album art from the appropriate player when a new track is loaded that is not found
     * in the hot cache, second-level memory cache, or an attached metadata cache file.
     *
     * @return true if album art is being kept track of for all active players
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
     * We have received an update that invalidates any previous metadata for a player, so clear its art, and alert
     * any listeners if this represents a change. This does not affect the hot cues; they will stick around until the
     * player loads a new track that overwrites one or more of them.
     *
     * @param update the update which means we have no metadata for the associated player
     */
    private void clearDeck(TrackMetadataUpdate update) {
        if (hotCache.remove(DeckReference.getDeckReference(update.player, 0)) != null) {
            deliverAlbumArtUpdate(update.player, null);
        }
    }

    /**
     * We have received notification that a device is no longer on the network, so clear out its artwork.
     *
     * @param announcement the packet which reported the device’s disappearance
     */
    private void clearArt(DeviceAnnouncement announcement) {
        final int player = announcement.getDeviceNumber();
        // Iterate over a copy to avoid concurrent modification issues
        for (DeckReference deck : new HashSet<>(hotCache.keySet())) {
            if (deck.player == player) {
                hotCache.remove(deck);
                if (deck.hotCue == 0) {
                    deliverAlbumArtUpdate(player, null);  // Inform listeners that the artwork is gone.
                }
            }
        }
        // Again iterate over a copy to avoid concurrent modification issues
        for (DataReference art : new HashSet<>(artCache.keySet())) {
            if (art.player == player) {
                removeArtFromCache(art);
            }
        }
    }

    /**
     * We have obtained album art for a device, so store it and alert any listeners.
     *
     * @param update the update which caused us to retrieve this art
     * @param art the album art which we retrieved
     */
    private void updateArt(TrackMetadataUpdate update, AlbumArt art) {
        hotCache.put(DeckReference.getDeckReference(update.player, 0), art);  // Main deck
        if (update.metadata.getCueList() != null) {  // Update the cache with any hot cues in this track as well
            for (CueList.Entry entry : update.metadata.getCueList().entries) {
                if (entry.hotCueNumber != 0) {
                    hotCache.put(DeckReference.getDeckReference(update.player, entry.hotCueNumber), art);
                }
            }
        }
        deliverAlbumArtUpdate(update.player, art);
    }

    /**
     * Get the art available for all tracks currently loaded in any player, either on the play deck, or in a hot cue.
     *
     * @return the album art associated with all current players, including for any tracks loaded in their hot cue slots
     *
     * @throws IllegalStateException if the ArtFinder is not running
     */
    @API(status = API.Status.STABLE)
    public Map<DeckReference, AlbumArt> getLoadedArt() {
        ensureRunning();
        // Make a copy so callers get an immutable snapshot of the current state.
        return Map.copyOf(hotCache);
    }

    /**
     * Look up the album art we have for the track loaded in the main deck of a given player number.
     *
     * @param player the device number whose album art for the playing track is desired
     *
     * @return the album art for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the ArtFinder is not running
     */
    @API(status = API.Status.STABLE)
    public AlbumArt getLatestArtFor(int player) {
        ensureRunning();
        return hotCache.get(DeckReference.getDeckReference(player, 0));
    }

    /**
     * Look up the album art we have for a given player, identified by a status update received from that player.
     *
     * @param update a status update from the player for which album art is desired
     *
     * @return the album art for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the ArtFinder is not running
     */
    @API(status = API.Status.STABLE)
    public AlbumArt getLatestArtFor(DeviceUpdate update) {
        return getLatestArtFor(update.getDeviceNumber());
    }

    /**
     * The maximum number of artwork images we will retain in our cache.
     */
    @API(status = API.Status.STABLE)
    public static final int DEFAULT_ART_CACHE_SIZE = 100;

    /**
     * Establish the second-level artwork cache. Since multiple tracks share the same art, it can be worthwhile to keep
     * art around even for tracks that are not currently loaded, to save on having to request it again when another
     * track from the same album is loaded. This along with {@link #artCacheEvictionQueue} and
     * {@link #artCacheRecentlyUsed} provide a simple implementation of the “clock” or “second-chance” variant on a
     * least-recently-used cache. Thanks to Ben Manes for suggesting this approach as a replacement for the deprecated
     * <a href="https://github.com/ben-manes/concurrentlinkedhashmap">ConcurrentLinkedHashMap</a> which was preventing
     * Beat Link from working in GraalVM
     * <a href="https://www.graalvm.org/latest/reference-manual/native-image/">native-image</a> environments, without
     * forcing us to abandon Java 6 compatibility which is still useful for afterglow-max.
     */
    private final ConcurrentHashMap<DataReference, AlbumArt> artCache = new ConcurrentHashMap<>();

    /**
     * Keeps track of the order in which art has been added to the cache, so older and unused art is prioritized for
     * eviction.
     */
    private final LinkedList<DataReference> artCacheEvictionQueue = new LinkedList<>();

    /**
     * Keeps track of whether artwork has been used since it was added to the cache or previously considered for
     * eviction, so older and unused art is prioritized for eviction.
     */
    private final HashSet<DataReference> artCacheRecentlyUsed = new HashSet<>();

    /**
     * Establishes how many album art images we retain in our second-level cache.
     */
    private final AtomicInteger artCacheSize = new AtomicInteger(DEFAULT_ART_CACHE_SIZE);

    /**
     * Check how many album art images can be kept in the in-memory second-level cache.
     *
     * @return the maximum number of distinct album art images that will automatically be kept for reuse in the
     *         in-memory art cache.
     */
    @API(status = API.Status.STABLE)
    public long getArtCacheSize() {
        return artCacheSize.get();
    }

    /**
     * Removes an element from the second-level artwork cache. Looks for the first item in the eviction queue that
     * has not been used since it was created or considered for eviction and removes that. Any items which are found
     * to have been used are instead moved to the end of the queue and marked unused. Must be called by one of the
     * synchronized methods that deal with manipulating the cache.
     */
    private void evictFromArtCache() {
        boolean evicted = false;
        while (!evicted && !artCacheEvictionQueue.isEmpty()) {
            DataReference candidate = artCacheEvictionQueue.removeFirst();
            if (artCacheRecentlyUsed.remove(candidate)) {
                // This artwork has been used, give it a second chance.
                artCacheEvictionQueue.addLast(candidate);
            } else {
                // This candidate is ready to be evicted.
                artCache.remove(candidate);
                evicted = true;
            }
        }
    }

    /**
     * Set how many album art images can be kept in the in-memory second-level cache.
     *
     * @param size the maximum number of distinct album art images that will automatically be kept for reuse in the
     *         in-memory art cache; if you set this to a smaller number than are currently present in the cache, some
     *         of the older images will be immediately discarded so that only the number you specified remain
     *
     * @throws IllegalArgumentException if {@code} size is less than 1
     */
    @API(status = API.Status.STABLE)
    public synchronized void setArtCacheSize(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1");

        }
        artCacheSize.set(size);
        while (artCache.size() > size) {
            evictFromArtCache();
        }
    }

    /**
     * Controls whether we attempt to obtain high-resolution art when it is available.
     */
    private final AtomicBoolean requestHighResolutionArt = new AtomicBoolean(true);

    /**
     * Check whether we are requesting high-resolution artwork when it is available.
     * @return {@code true} if we attempt to obtain high-resolution versions of artwork, falling back to normal resolution when unavailable.
     */
    @API(status = API.Status.STABLE)
    public boolean getRequestHighResolutionArt() {
        return requestHighResolutionArt.get();
    }

    /**
     * Set whether we should attempt to obtain high resolution art when it is available.
     *
     * @param shouldRequest if {@code true} we will try to obtain high-resolution art first, falling back to the
     *                      ordinary resolution if it is not available.
     */
    @API(status = API.Status.STABLE)
    public void setRequestHighResolutionArt(boolean shouldRequest) {
        requestHighResolutionArt.set(shouldRequest);
    }

    /**
     * Adds artwork to our second-level cache, evicting older unused art if necessary to enforce the size limit.
     */
    private synchronized void addArtToCache(DataReference artReference, AlbumArt art) {
        while (artCache.size() >= artCacheSize.get()) {
            evictFromArtCache();
        }
        artCache.put(artReference, art);
        artCacheEvictionQueue.addLast(artReference);
    }

    /**
     * Ask the specified player for the album art in the specified slot with the specified rekordbox ID,
     * using downloaded media instead if it is available, and possibly giving up if we are in passive mode.
     *
     * @param artReference uniquely identifies the desired album art
     * @param trackType the kind of track that owns the art
     * @param failIfPassive will prevent the request from taking place if we are in passive mode, so that automatic
     *                      artwork updates will use available caches and downloaded metadata exports only
     *
     * @return the album art found, if any
     */
    private AlbumArt requestArtworkInternal(final DataReference artReference, final CdjStatus.TrackType trackType,
                                            final boolean failIfPassive) {

        // First see if any registered metadata providers can offer it for us.
        final MediaDetails sourceDetails = MetadataFinder.getInstance().getMediaDetailsFor(artReference.getSlotReference());
        if (sourceDetails != null) {
            final AlbumArt provided = MetadataFinder.getInstance().allMetadataProviders.getAlbumArt(sourceDetails, artReference);
            if (provided != null) {
                addArtToCache(artReference, provided);
                return provided;
            }
        }

        // At this point, unless we are allowed to actively request the data, we are done. We can always actively
        // request tracks from rekordbox.
        if (MetadataFinder.getInstance().isPassive() && failIfPassive && artReference.slot != CdjStatus.TrackSourceSlot.COLLECTION) {
            return null;
        }

        // We have to actually request the art using the dbserver protocol.
        ConnectionManager.ClientTask<AlbumArt> task =
                client -> getArtwork(artReference.rekordboxId, SlotReference.getSlotReference(artReference), trackType, client);

        try {
            AlbumArt artwork = ConnectionManager.getInstance().invokeWithClientSession(artReference.player, task, "requesting artwork");
            if (artwork != null) {  // Our file load or network request succeeded, so add to the level 2 cache.
                addArtToCache(artReference, artwork);
            }
            return artwork;
        } catch (Exception e) {
            logger.error("Problem requesting album art, returning null", e);
        }
        return null;
    }

    /**
     * Ask the specified player for the specified artwork from the specified media slot, first checking if we have a
     * cached copy.
     *
     * @param artReference uniquely identifies the desired artwork
     * @param trackType the kind of track that owns the artwork
     *
     * @return the artwork, if it was found, or {@code null}
     *
     * @throws IllegalStateException if the ArtFinder is not running
     */
    @API(status = API.Status.STABLE)
    public AlbumArt requestArtworkFrom(final DataReference artReference, final CdjStatus.TrackType trackType) {
        ensureRunning();
        AlbumArt artwork = findArtInMemoryCaches(artReference);  // First check the in-memory artwork caches.
        if (artwork == null) {
            artwork = requestArtworkInternal(artReference, trackType, false);
        }
        return artwork;
    }

    /**
     * Request the artwork with a particular artwork ID, given a connection to a player that has already been set up.
     * Will request high resolution unless {@link #setRequestHighResolutionArt(boolean)} has been called with a
     * {@code false} argument.
     *
     * @param artworkId identifies the album art to retrieve
     * @param slot the slot identifier from which the associated track was loaded
     * @param trackType the kind of track that owns the artwork
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the track's artwork, or null if none is available
     *
     * @throws IOException if there is a problem communicating with the player
     */
    AlbumArt getArtwork(int artworkId, SlotReference slot, CdjStatus.TrackType trackType, Client client)
            throws IOException {

        Message response;

        // Send the artwork request
        if (getRequestHighResolutionArt()) {
            response = client.simpleRequest(Message.KnownType.ALBUM_ART_REQ, Message.KnownType.ALBUM_ART,
                    client.buildRMST(Message.MenuIdentifier.DATA, slot.slot, trackType), new NumberField(artworkId),
                    new NumberField(1));
        } else {
            response = client.simpleRequest(Message.KnownType.ALBUM_ART_REQ, Message.KnownType.ALBUM_ART,
                    client.buildRMST(Message.MenuIdentifier.DATA, slot.slot, trackType), new NumberField(artworkId));
        }

        // Create an image from the response bytes
        return new AlbumArt(new DataReference(slot, artworkId), ((BinaryField)response.arguments.get(3)).getValue());
    }

    /**
     * Keep track of the devices we are currently trying to get artwork from in response to metadata updates.
     */
    private final Set<Integer> activeRequests = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Look for the specified album art in both the hot cache of loaded tracks and the longer-lived LRU cache.
     *
     * @param artReference uniquely identifies the desired album art
     *
     * @return the art, if it was found in one of our caches, or {@code null}
     */
    private AlbumArt findArtInMemoryCaches(DataReference artReference) {
        // First see if we can find the new track in the hot cache as a hot cue
        for (AlbumArt cached : hotCache.values()) {
            if (cached.artReference.equals(artReference)) {  // Found a hot cue hit, use it.
                return cached;
            }
        }

        // Not in the hot cache, see if it is in our LRU cache
        synchronized (this) {
            AlbumArt found = artCache.get(artReference);
            if (found != null) {
                artCacheRecentlyUsed.add(artReference);
            }
            return found;
        }
    }

    /**
     * Keeps track of the registered track metadata update listeners.
     */
    private final Set<AlbumArtListener> artListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * <p>Adds the specified album art listener to receive updates when the album art for a player changes.
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
    public void addAlbumArtListener(AlbumArtListener listener) {
        if (listener != null) {
            artListeners.add(listener);
        }
    }

    /**
     * Removes the specified album art listener so that it no longer receives updates when the
     * album art for a player changes. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the album art update listener to remove
     */
    @API(status = API.Status.STABLE)
    public void removeAlbumArtListener(AlbumArtListener listener) {
        if (listener != null) {
            artListeners.remove(listener);
        }
    }

    /**
     * Get the set of currently-registered album art listeners.
     *
     * @return the listeners that are currently registered for album art updates
     */
    @API(status = API.Status.STABLE)
    public Set<AlbumArtListener> getAlbumArtListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Set.copyOf(artListeners);
    }

    /**
     * Send an album art update announcement to all registered listeners.
     */
    private void deliverAlbumArtUpdate(int player, AlbumArt art) {
        if (!getAlbumArtListeners().isEmpty()) {
            final AlbumArtUpdate update = new AlbumArtUpdate(player, art);
            for (final AlbumArtListener listener : getAlbumArtListeners()) {
                try {
                    listener.albumArtChanged(update);

                } catch (Throwable t) {
                    logger.warn("Problem delivering album art update to listener", t);
                }
            }
        }
    }

    /**
     * Process a metadata update from the {@link MetadataFinder}, and see if it means the album art associated with
     * any player has changed.
     *
     * @param update describes the new metadata we have for a player, if any
     */
    private void handleUpdate(final TrackMetadataUpdate update) {
        if ((update.metadata == null) || (update.metadata.getArtworkId() == 0)) {
            // Either we have no metadata, or the track has no album art
            clearDeck(update);
        } else {
            // We can offer artwork for this device; check if we have already looked up this art
            final AlbumArt lastArt = hotCache.get(DeckReference.getDeckReference(update.player, 0));
            final DataReference artReference = new DataReference(update.metadata.trackReference.player,
                    update.metadata.trackReference.slot, update.metadata.getArtworkId());
            if (lastArt == null || !lastArt.artReference.equals(artReference)) {  // We have something new!

                // First see if we can find the new track in one of our in-memory caches
                AlbumArt cached = findArtInMemoryCaches(artReference);
                if (cached != null) {  // Found a cue hit, use it.
                    updateArt(update, cached);
                    return;
                }

                // Not in either cache so try actually retrieving it.
                if (activeRequests.add(update.player)) {
                    clearDeck(update);  // We won't know what it is until our request completes.
                    // We had to make sure we were not already asking for this track.
                    new Thread(() -> {
                        try {
                            AlbumArt art = requestArtworkInternal(artReference, update.metadata.trackType, true);
                            if (art != null) {
                                updateArt(update, art);
                            }
                        } catch (Exception e) {
                            logger.warn("Problem requesting album art from update {}", update, e);
                        } finally {
                            activeRequests.remove(update.player);
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
            logger.debug("The ArtFinder does not auto-start when {} does.", sender);
        }

        @Override
        public void stopped(LifecycleParticipant sender) {
            if (isRunning()) {
                logger.info("ArtFinder stopping because {} has.", sender);
                stop();
            }
        }
    };

    /**
     * <p>Start finding album art for all active players. Starts the {@link MetadataFinder} if it is not already
     * running, because we need it to send us metadata updates to notice when new tracks are loaded. This in turn
     * starts the {@link DeviceFinder}, so we can keep track of the comings and goings of players themselves.
     * We also start the {@link ConnectionManager} in order to make queries to obtain art.</p>
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
                    }
                }
            });
            running.set(true);
            queueHandler.start();
            deliverLifecycleAnnouncement(logger, true);

            // Send ourselves "updates" about any tracks that were loaded before we started, since we missed those.
            for (Map.Entry<DeckReference, TrackMetadata> entry : MetadataFinder.getInstance().getLoadedTracks().entrySet()) {
                if (entry.getKey().hotCue == 0) {  // The track is currently loaded in a main player deck
                    handleUpdate(new TrackMetadataUpdate(entry.getKey().player, entry.getValue()));
                }
            }
        }
    }

    /**
     * Stop finding album art for all active players.
     */
    @API(status = API.Status.STABLE)
    public synchronized void stop() {
        if (isRunning()) {
            MetadataFinder.getInstance().removeTrackMetadataListener(metadataListener);
            running.set(false);
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;

            // Report the loss of our hot cached art and our shutdown, on the proper thread, and outside our lock
            final Set<DeckReference> dyingCache = new HashSet<>(hotCache.keySet());
            SwingUtilities.invokeLater(() -> {
                for (DeckReference deck : dyingCache) {
                    if (deck.hotCue == 0) {
                        deliverAlbumArtUpdate(deck.player, null);
                    }
                }
            });
            hotCache.clear();
            artCache.clear();
            artCacheRecentlyUsed.clear();
            artCacheEvictionQueue.clear();
            deliverLifecycleAnnouncement(logger, false);
        }
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final ArtFinder ourInstance = new ArtFinder();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    @API(status = API.Status.STABLE)
    public static ArtFinder getInstance() {
        return ourInstance;
    }

    /**
     * Prevent instantiation.
     */
    private ArtFinder() {
        // Nothing to do
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ArtFinder[running:").append(isRunning()).append(", passive:");
        sb.append(MetadataFinder.getInstance().isPassive()).append(", artCacheSize:").append(getArtCacheSize());
        if (isRunning()) {
            sb.append(", loadedArt:").append(getLoadedArt()).append(", cached art:").append(artCache.size());
        }
        return sb.append("]").toString();
    }
}
