package org.deepsymmetry.beatlink.data;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.dbserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
@SuppressWarnings("WeakerAccess")
public class ArtFinder extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(ArtFinder.class);

    /**
     * Keeps track of the current album art cached for each player. We hot cache art for any track which is currently
     * on-deck in the player, as well as any that were loaded into a player's hot-cue slot.
     */
    private final Map<DeckReference, AlbumArt> hotCache = new ConcurrentHashMap<DeckReference, AlbumArt>();

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
            final Set<DataReference> keys = new HashSet<DataReference>(artCache.keySet());
            for (DataReference artReference : keys) {
                if (SlotReference.getSlotReference(artReference) == slot) {
                    logger.debug("Evicting cached artwork in response to unmount report {}", artReference);
                    artCache.remove(artReference);
                }
            }
            // Again iterate over a copy to avoid concurrent modification issues.
            final Set<Map.Entry<DeckReference,AlbumArt>> copy = new HashSet<Map.Entry<DeckReference, AlbumArt>>(hotCache.entrySet());
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
        final int player = announcement.getNumber();
        // Iterate over a copy to avoid concurrent modification issues
        for (DeckReference deck : new HashSet<DeckReference>(hotCache.keySet())) {
            if (deck.player == player) {
                hotCache.remove(deck);
            }
        }
        // Again iterate over a copy to avoid concurrent modification issues
        for (DataReference art : new HashSet<DataReference>(artCache.keySet())) {
            if (art.player == player) {
                artCache.remove(art);
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
    public Map<DeckReference, AlbumArt> getLoadedArt() {
        ensureRunning();
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableMap(new HashMap<DeckReference, AlbumArt>(hotCache));
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
    public AlbumArt getLatestArtFor(DeviceUpdate update) {
        return getLatestArtFor(update.getDeviceNumber());
    }

    /**
     * The maximum number of artwork images we will retain in our cache.
     */
    public static final int DEFAULT_ART_CACHE_SIZE = 100;

    /**
     * Establish the second-level artwork cache. Since multiple tracks share the same art, it can be worthwhile to keep
     * art around even for tracks that are not currently loaded, to save on having to request it again when another
     * track from the same album is loaded.
     */
    private final ConcurrentLinkedHashMap<DataReference, AlbumArt> artCache =
            new ConcurrentLinkedHashMap.Builder<DataReference, AlbumArt>().maximumWeightedCapacity(DEFAULT_ART_CACHE_SIZE).build();

    /**
     * Check how many album art images can be kept in the in-memory second-level cache.
     *
     * @return the maximum number of distinct album art images that will automatically be kept for reuse in the
     *         in-memory art cache.
     */
    public long getArtCacheSize() {
        return artCache.capacity();
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
    public void setArtCacheSize(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1");

        }
        artCache.setCapacity(size);
    }

    /**
     * Ask the specified player for the album art in the specified slot with the specified rekordbox ID,
     * using cached media instead if it is available, and possibly giving up if we are in passive mode.
     *
     * @param artReference uniquely identifies the desired album art
     * @param failIfPassive will prevent the request from taking place if we are in passive mode, so that automatic
     *                      artwork updates will use available caches only
     *
     * @return the album art found, if any
     */
    private AlbumArt requestArtworkInternal(final DataReference artReference, final boolean failIfPassive) {

        // First check if we are using cached data for this slot
        ZipFile cache = MetadataFinder.getInstance().getMetadataCache(SlotReference.getSlotReference(artReference));
        if (cache != null) {
            return getCachedArtwork(cache, artReference);
        }

        if (MetadataFinder.getInstance().isPassive() && failIfPassive) {
            // We are not allowed to perform actual requests in passive mode.
            return null;
        }

        // We have to actually request the art.
        ConnectionManager.ClientTask<AlbumArt> task = new ConnectionManager.ClientTask<AlbumArt>() {
            @Override
            public AlbumArt useClient(Client client) throws Exception {
                return getArtwork(artReference.rekordboxId, SlotReference.getSlotReference(artReference), client);
            }
        };

        try {
            AlbumArt artwork = ConnectionManager.getInstance().invokeWithClientSession(artReference.player, task, "requesting artwork");
            if (artwork != null) {  // Our cache file load or network request succeeded, so add to the level 2 cache.
                artCache.put(artReference, artwork);
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
     *
     * @return the artwork, if it was found, or {@code null}
     *
     * @throws IllegalStateException if the ArtFinder is not running
     */
    public AlbumArt requestArtworkFrom(final DataReference artReference) {
        ensureRunning();
        AlbumArt artwork = findArtInMemoryCaches(artReference);  // First check the in-memory artwork caches.
        if (artwork == null) {
            artwork = requestArtworkInternal(artReference, false);
        }
        return artwork;
    }

    /**
     * Look up artwork from a cache file.
     *
     * @param cache the appropriate metadata cache file
     * @param artReference the unique database specification of the desired artwork
     *
     * @return the cached album art (if available), or {@code null}
     *
     * @throws IllegalStateException if the ArtFinder is not running
     */
    private AlbumArt getCachedArtwork(ZipFile cache, DataReference artReference) {
        ensureRunning();
        ZipEntry entry = cache.getEntry(MetadataFinder.getInstance().getArtworkEntryName(artReference.rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(cache.getInputStream(entry));
                byte[] imageBytes = new byte[(int)entry.getSize()];
                is.readFully(imageBytes);
                AlbumArt result = new AlbumArt(artReference, ByteBuffer.wrap(imageBytes).asReadOnlyBuffer());
                artCache.put(artReference, result);
                return result;
            } catch (IOException e) {
                logger.error("Problem reading artwork from cache file, returning null", e);
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (Exception e) {
                        logger.error("Problem closing ZipFile input stream for reading artwork entry", e);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Request the artwork with a particular artwork ID, given a connection to a player that has already been set up.
     *
     * @param artworkId identifies the album art to retrieve
     * @param slot the slot identifier from which the associated track was loaded
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the track's artwork, or null if none is available
     *
     * @throws IOException if there is a problem communicating with the player
     */
    AlbumArt getArtwork(int artworkId, SlotReference slot, Client client)
            throws IOException {

        // Send the artwork request
        Message response = client.simpleRequest(Message.KnownType.ALBUM_ART_REQ, Message.KnownType.ALBUM_ART,
                client.buildRMS1(Message.MenuIdentifier.DATA, slot.slot), new NumberField((long)artworkId));

        // Create an image from the response bytes
        return new AlbumArt(new DataReference(slot, artworkId), ((BinaryField)response.arguments.get(3)).getValue());
    }

    /**
     * Keep track of the devices we are currently trying to get artwork from in response to metadata updates.
     */
    private final Set<Integer> activeRequests = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

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
        return artCache.get(artReference);
    }

    /**
     * Keeps track of the registered track metadata update listeners.
     */
    private final Set<AlbumArtListener> artListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<AlbumArtListener, Boolean>());

    /**
     * <p>Adds the specified album art listener to receive updates when the album art for a player changes.
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
    @SuppressWarnings("WeakerAccess")
    public Set<AlbumArtListener> getAlbumArtListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<AlbumArtListener>(artListeners));
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

                } catch (Exception e) {
                    logger.warn("Problem delivering album art update to listener", e);
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
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                AlbumArt art = requestArtworkInternal(artReference, true);
                                if (art != null) {
                                    updateArt(update, art);
                                }
                            } catch (Exception e) {
                                logger.warn("Problem requesting album art from update" + update, e);
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
    public synchronized void stop() {
        if (isRunning()) {
            MetadataFinder.getInstance().removeTrackMetadataListener(metadataListener);
            running.set(false);
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;
            // Report the loss of our hot cached art and our shutdown, on the proper thread, and outside our lock
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    for (DeckReference deck : hotCache.keySet()) {
                        if (deck.hotCue == 0) {
                            deliverAlbumArtUpdate(deck.player, null);
                        }
                    }
                }
            });
            hotCache.clear();
            artCache.clear();
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
