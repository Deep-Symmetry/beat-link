package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.dbserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * <p>Watches for new tracks to be loaded on players, and queries the
 * appropriate player for the album art when that happens.</p>
 *
 * <p>Maintains a hot cache of art for any track currently loaded in a player, either on the main playback
 * deck, or as a hot cue, since those tracks could start playing instantly.</p>
 *
 * <p>In busy performance situations where all four usable player numbers are in use by actual players,
 * you may want to use a metadata cache with the {@link MetadataFinder} to avoid conflicting queries yet still have
 * art available. In such situations, you may want to go into passive mode, using {@link #setPassive(boolean)}, to
 * prevent art queries about tracks that are not available from the attached metadata cache files.</p>
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class ArtFinder {

    private static final Logger logger = LoggerFactory.getLogger(ArtFinder.class);

    /**
     * Keeps track of the current metadata cached for each player. We cache metadata for any track which is currently
     * on-deck in the player, as well as any that were loaded into a player's hot-cue slot.
     */
    private static final Map<DeckReference, AlbumArt> hotCache = new ConcurrentHashMap<DeckReference, AlbumArt>();

    /**
     * A queue used to hold metadata updates we receive from the {@link MetadataFinder} so we can process them on a
     * lower priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private static final LinkedBlockingDeque<TrackMetadataUpdate> pendingUpdates =
            new LinkedBlockingDeque<TrackMetadataUpdate>(100);

    /**
     * Our metadata listener just puts metadata updates on our queue, so we can process them on a lower
     * priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private static final TrackMetadataUpdateListener metadataListener = new TrackMetadataUpdateListener() {
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
    private static final MountUpdateListener mountListener = new MountUpdateListener() {
        @Override
        public void mediaMounted(SlotReference slot) {
            logger.debug("ArtFinder doesn't yet need to do anything in response to a media mount.");
        }

        @Override
        public void mediaUnmounted(SlotReference slot) {
            for (DataReference artReference : artCache.keySet()) {
                if (SlotReference.getSlotReference(artReference) == slot) {
                    logger.debug("Evicting cached artwork in response to unmount report {}", artReference);
                    artCache.remove(artReference);
                }
            }
        }
    };

    /**
     * Our announcement listener watches for devices to disappear from the network so we can discard all information
     * about them.
     */
    private static final DeviceAnnouncementListener announcementListener = new DeviceAnnouncementListener() {
        @Override
        public void deviceFound(final DeviceAnnouncement announcement) {
            logger.debug("Currently nothing for MetaDataListener to do when devices appear.");
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            logger.debug("Clearing artwork in response to the loss of a device, {}", announcement);
            clearArt(announcement);
        }
    };

    /**
     * Keep track of whether we are running
     */
    private static boolean running = false;

    /**
     * Check whether we are currently running. Unless we are in passive mode, we will also automatically request
     * album art from the appropriate player when a new track is loaded that is not found in the hot cache or an
     * attached metadata cache file.
     *
     * @return true if album art is being kept track of for all active players
     *
     * @see #isPassive()
     */
    @SuppressWarnings("WeakerAccess")
    public static synchronized boolean isRunning() {
        return running;
    }

    /**
     * Indicates whether we should use metadata only from caches, never actively requesting it from a player.
     */
    private static boolean passive = false;

    /**
     * Check whether we are configured to use art only from caches, never actively requesting it from a player.
     *
     * @return {@code true} if only cached art will be used, or {@code false} if art will be requested from the
     *         appropriate player when a track is loaded from a media slot to which no cache has been assigned
     */
    public static synchronized boolean isPassive() {
        return passive;
    }

    /**
     * Set whether we are configured to use art only from caches, never actively requesting it from a player.
     *
     * @param passive {@code true} if only cached art will be used, or {@code false} if art will be requested
     *                from the appropriate player if a track is loaded from a media slot to which no cache has
     *                been assigned
     */
    public static synchronized void setPassive(boolean passive) {
        ArtFinder.passive = passive;
    }

    /**
     * We process our player status updates on a separate thread so as not to slow down the high-priority update
     * delivery thread; we perform potentially slow I/O.
     */
    private static Thread queueHandler;

    /**
     * We have received an update that invalidates any previous metadata for a player, so clear it out, and alert
     * any listeners if this represents a change. This does not affect the hot cues; they will stick around until the
     * player loads a new track that overwrites one or more of them.
     *
     * @param update the update which means we have no metadata for the associated player
     */
    private static synchronized void clearDeck(TrackMetadataUpdate update) {
        if (hotCache.remove(DeckReference.getDeckReference(update.player, 0)) != null) {
            deliverAlbumArtUpdate(update.player, null);
        }
    }

    /**
     * We have received notification that a device is no longer on the network, so clear out its artwork.
     *
     * @param announcement the packet which reported the device’s disappearance
     */
    private static synchronized void clearArt(DeviceAnnouncement announcement) {
        final int player = announcement.getNumber();
        for (DeckReference deck : hotCache.keySet()) {
            if (deck.player == player) {
                hotCache.remove(deck);
            }
        }
        for (DataReference artReference : artCache.keySet()) {
            if (artReference.player == player) {
                artCache.remove(artReference);
            }
        }
    }

    /**
     * We have obtained album art for a device, so store it and alert any listeners.
     *
     * @param update the update which caused us to retrieve this art
     * @param art the album art which we retrieved
     */
    private static synchronized void updateArt(TrackMetadataUpdate update, AlbumArt art) {
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
     */
    public static synchronized Map<DeckReference, AlbumArt> getLoadedArt() {
        return Collections.unmodifiableMap(new HashMap<DeckReference, AlbumArt>(hotCache));
    }

    /**
     * Look up the album art we have for the track loaded in the main deck of a given player number.
     *
     * @param player the device number whose album art for the playing track is desired
     * @return the album art for the track loaded on that player, if available
     */
    @SuppressWarnings("WeakerAccess")
    public static AlbumArt getLatestArtFor(int player) {
        return hotCache.get(DeckReference.getDeckReference(player, 0));
    }

    /**
     * Look up the album art we have for a given player, identified by a status update received from that player.
     *
     * @param update a status update from the player for which album art is desired
     * @return the album art for the track loaded on that player, if available
     */
    public static AlbumArt getLatestArtworkFor(DeviceUpdate update) {
        return getLatestArtFor(update.getDeviceNumber());
    }

    /**
     * Provide a least-recently used cache mechanism so we can keep artwork around for reuse, since it is often shared
     * between tracks, and does not take up much space.
     *
     * @param <A> the type of the keys that will be used in the cache
     * @param <B> the type of the values that will be used in the cache
     */
    private static class LruCache<A, B> extends LinkedHashMap<A, B> {

        /**
         * How many entries are we to retain.
         */
        private final int maxEntries;

        /**
         * Set the cache size cap and then delegate to the superclass constructor.
         *
         * @param maxEntries the largest number of entries we will retain
         */
        @SuppressWarnings("SameParameterValue")
        LruCache(final int maxEntries) {
            super(maxEntries + 1, 1.0f, true);
            this.maxEntries = maxEntries;
        }

        /**
         * Returns <tt>true</tt> if this <code>LruCache</code> has more entries than the maximum specified when it was
         * created.
         *
         * This method <em>does not</em> modify the underlying <code>Map</code>; it relies on the implementation of
         * {@link LinkedHashMap} to do that, but that behavior is documented in the JavaDoc for
         * <code>LinkedHashMap</code>
         *
         * @param eldest the {@link java.util.Map.Entry} in question; this implementation doesn't care what it is,
         *               since the implementation is only dependent on the size of the cache
         *
         * @return <tt>true</tt> if the map has overflowed, so the oldest entry should be removed
         *
         * @see LinkedHashMap#removeEldestEntry(Map.Entry)
         */
        @Override
        protected boolean removeEldestEntry(final Map.Entry<A, B> eldest) {
            return (size() > maxEntries);
        }
    }

    /**
     * The maximum number of artwork images we will retain in our cache.
     */
    private static final int ART_CACHE_SIZE = 100;  // TODO: Make this configurable

    /**
     * Establish the artwork cache. Even though we are not caching tracks, the {@link DataReference} tuple has
     * exactly the information we need to identify cached artwork.
     */
    private static final Map<DataReference, AlbumArt> artCache =
            Collections.synchronizedMap(new LruCache<DataReference, AlbumArt>(ART_CACHE_SIZE));

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
    private static AlbumArt requestArtworkInternal(final DataReference artReference, final boolean failIfPassive) {

        // First check if we are using cached data for this slot
        ZipFile cache = MetadataFinder.getMetadataCache(SlotReference.getSlotReference(artReference));
        if (cache != null) {
            return getCachedArtwork(cache, artReference);
        }

        if (passive && failIfPassive) {  // We are not allowed to perform actual requests in passive mode.
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
            AlbumArt artwork = ConnectionManager.invokeWithClientSession(artReference.player, task, "requesting artwork");
            if (artwork != null) {  // Our cache file load or network request succeeded, so add to the in-memory cache.
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
     */
    public static AlbumArt requestArtworkFrom(final DataReference artReference) {

        // First check the in-memory artwork caches
        AlbumArt artwork = findArtInMemoryCaches(artReference);
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
     */
    private static AlbumArt getCachedArtwork(ZipFile cache, DataReference artReference) {
        ZipEntry entry = cache.getEntry(MetadataFinder.getArtworkEntryName(artReference.rekordboxId));
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
    static AlbumArt getArtwork(int artworkId, SlotReference slot, Client client)
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
    private static final Set<Integer> activeRequests = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    /**
     * Look for the specified album art in both the hot cache of loaded tracks and the longer-lived LRU cache.
     *
     * @param artReference uniquely identifies the desired album art
     *
     * @return the art, if it was found in one of our caches, or {@code null}
     */
    private static AlbumArt findArtInMemoryCaches(DataReference artReference) {
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
    private static final Set<AlbumArtUpdateListener> artListeners = new HashSet<AlbumArtUpdateListener>();

    /**
     * Adds the specified album art listener to receive updates when the album art for a player changes.
     * If {@code listener} is {@code null} or already present in the set of registered listeners, no exception is
     * thrown and no action is performed.
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
    public static synchronized void addAlbumArtUpdateListener(AlbumArtUpdateListener listener) {
        if (listener != null) {
            artListeners.add(listener);
        }
    }

    /**
     * Get the set of currently-registered album art update listeners.
     *
     * @return the listeners that are currently registered for album art updates
     */
    @SuppressWarnings("WeakerAccess")
    public static synchronized Set<AlbumArtUpdateListener> getAlbumArtUpdateListeners() {
        return Collections.unmodifiableSet(new HashSet<AlbumArtUpdateListener>(artListeners));
    }

    /**
     * Removes the specified album art update listener so that it no longer receives updates when the
     * album art for a player changes. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the album art update listener to remove
     */
    public static synchronized void removeAlbumArtUpdateListener(AlbumArtUpdateListener listener) {
        if (listener != null) {
            artListeners.remove(listener);
        }
    }

    /**
     * Send an album art update announcement to all registered listeners.
     */
    private static void deliverAlbumArtUpdate(int player, AlbumArt art) {
        if (!getAlbumArtUpdateListeners().isEmpty()) {
            final AlbumArtUpdate update = new AlbumArtUpdate(player, art);
            for (final AlbumArtUpdateListener listener : getAlbumArtUpdateListeners()) {
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
    private static void handleUpdate(final TrackMetadataUpdate update) {
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
                                logger.warn("Problem requesting track metadata from update" + update, e);
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
     * Start finding album art for all active players. Starts the {@link MetadataFinder} if it is not already
     * running, because we need it to send us metadata updates to notice when new tracks are loaded. Also
     * starts the {@link DeviceFinder} (which is also needed by the {@code MetadataFinder}) so we can keep track of
     * the comings and goings of players themselves, and the {@link ConnectionManager} in order to make queries
     * to obtain art.
     *
     * @throws Exception if there is a problem starting the required components
     */
    public static synchronized void start() throws Exception {
        if (!running) {
            ConnectionManager.start();
            DeviceFinder.start();
            DeviceFinder.addDeviceAnnouncementListener(announcementListener);
            MetadataFinder.start();
            MetadataFinder.addTrackMetadataUpdateListener(metadataListener);
            MetadataFinder.addMountUpdateListener(mountListener);
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
            running = true;
            queueHandler.start();
        }
    }

    /**
     * Stop finding album art for all active players.
     */
    public static synchronized void stop() {
        if (running) {
            MetadataFinder.removeTrackMetadataUpdateListener(metadataListener);
            running = false;
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;
            hotCache.clear();
        }
    }
}
