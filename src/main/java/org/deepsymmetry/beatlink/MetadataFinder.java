package org.deepsymmetry.beatlink;

import org.deepsymmetry.beatlink.dbserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Watches for new tracks to be loaded on players, and queries the
 * appropriate player for the metadata information when that happens.<p>
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class MetadataFinder {

    private static final Logger logger = LoggerFactory.getLogger(MetadataFinder.class.getName());

    /**
     * Given a status update from a CDJ, find the metadata for the track that it has loaded, if any. If there is
     * an appropriate metadata cache, will use that, otherwise makes a query to the players dbserver.
     *
     * @param status the CDJ status update that will be used to determine the loaded track and ask the appropriate
     *               player for metadata about it
     *
     * @return the metadata that was obtained, if any
     */
    @SuppressWarnings("WeakerAccess")
    public static TrackMetadata requestMetadataFrom(CdjStatus status) {
        if (status.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.NO_TRACK || status.getRekordboxId() == 0) {
            return null;
        }
        return requestMetadataFrom(new TrackReference(status.getTrackSourcePlayer(),
                status.getTrackSourceSlot(), status.getRekordboxId()));
    }


    /**
     * Ask the specified player for metadata about the track in the specified slot with the specified rekordbox ID,
     * unless we have a metadata cache available for the specified media slot, in which case that will be used instead.
     *
     * @param track uniquely identifies the track whose metadata is desired
     *
     * @return the metadata, if any
     *
     * @throws IllegalStateException if a metadata cache is being created amd we need to talk to the CDJs
     */
    @SuppressWarnings("WeakerAccess")
    public static TrackMetadata requestMetadataFrom(TrackReference track) {
        return requestMetadataInternal(track, false);
    }

    /**
     * Ask the specified player for metadata about the track in the specified slot with the specified rekordbox ID,
     * using cached media instead if it is available, and possibly giving up if we are in passive mode.
     *
     * @param track uniquely identifies the track whose metadata is desired
     * @param failIfPassive will prevent the request from taking place if we are in passive mode, so that automatic
     *                      metadata updates will use available caches only
     *
     * @return the metadata found, if any
     *
     * @throws IllegalStateException if a metadata cache is being created amd we need to talk to the CDJs
     */
    private static TrackMetadata requestMetadataInternal(final TrackReference track, boolean failIfPassive) {
        // First check if we are using cached data for this request
        ZipFile cache = getMetadataCache(SlotReference.getSlotReference(track));
        if (cache != null) {
            return getCachedMetadata(cache, track);
        }

        if (passive && failIfPassive) {
            return null;
        }

        ConnectionManager.ClientTask<TrackMetadata> task = new ConnectionManager.ClientTask<TrackMetadata>() {
            @Override
            public TrackMetadata useClient(Client client) throws Exception {
                return queryMetadata(track, client);
            }
        };

        try {
            return ConnectionManager.invokeWithClientSession(track.player, task, "requesting metadata");
        } catch (Exception e) {
            logger.error("Problem requesting metadata, returning null", e);
        }
        return null;
    }

    /**
     * Request metadata for a specific track ID, given a connection to a player that has already been set up.
     * Separated into its own method so it could be used multiple times with the same connection when gathering
     * all track metadata.
     *
     * @param track uniquely identifies the track whose metadata is desired
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved metadata, or {@code null} if there is no such track
     *
     * @throws IOException if there is a communication problem
     */
    private static TrackMetadata queryMetadata(TrackReference track, Client client)
            throws IOException {

        // Send the metadata menu request
        Message response = client.menuRequest(Message.KnownType.METADATA_REQ, Message.MenuIdentifier.MAIN_MENU,
                track.slot, new NumberField(track.rekordboxId));
        final long count = response.getMenuResultsCount();
        if (count == Message.NO_MENU_RESULTS_AVAILABLE) {
            return null;
        }

        // Gather the cue list and all the metadata menu items
        final CueList cueList = getCueList(track.rekordboxId, track.slot, client);
        final List<Message> items = client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, track.slot, response);
        return new TrackMetadata(track, items, cueList);
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
    private static ByteBuffer getArtwork(int artworkId, CdjStatus.TrackSourceSlot slot, Client client)
            throws IOException {

        // Send the artwork request
        Message response = client.simpleRequest(Message.KnownType.ALBUM_ART_REQ, Message.KnownType.ALBUM_ART,
                client.buildRMS1(Message.MenuIdentifier.DATA, slot), new NumberField((long)artworkId));

        // Create an image from the response bytes
        return ((BinaryField)response.arguments.get(3)).getValue();
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
    private static BeatGrid getBeatGrid(int rekordboxId, CdjStatus.TrackSourceSlot slot, Client client)
            throws IOException {
        Message response = client.simpleRequest(Message.KnownType.BEAT_GRID_REQ, null,
                client.buildRMS1(Message.MenuIdentifier.DATA, slot), new NumberField(rekordboxId));
        if (response.knownType == Message.KnownType.BEAT_GRID) {
            return new BeatGrid(response);
        }
        logger.error("Unexpected response type when requesting beat grid: {}", response);
        return null;
    }

    /**
     * Requests the cue list for a specific track ID, given a connection to a player that has already been set up.
     *
     * @param rekordboxId the track of interest
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved cue list, or {@code null} if none was available
     * @throws IOException if there is a communication problem
     */
    private static CueList getCueList(int rekordboxId, CdjStatus.TrackSourceSlot slot, Client client)
            throws IOException {
        Message response = client.simpleRequest(Message.KnownType.CUE_LIST_REQ, null,
                client.buildRMS1(Message.MenuIdentifier.DATA, slot), new NumberField(rekordboxId));
        if (response.knownType == Message.KnownType.CUE_LIST) {
            return new CueList(response);
        }
        logger.error("Unexpected response type when requesting cue list: {}", response);
        return null;
    }

    /**
     * Requests the waveform preview for a specific track ID, given a connection to a player that has already been
     * set up.
     *
     * @param rekordboxId the track of interest
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved waveform preview, or {@code null} if none was available
     * @throws IOException if there is a communication problem
     */
    private static WaveformPreview getWaveformPreview(int rekordboxId, CdjStatus.TrackSourceSlot slot, Client client)
        throws IOException {
        Message response = client.simpleRequest(Message.KnownType.WAVE_PREVIEW_REQ, null,
                client.buildRMS1(Message.MenuIdentifier.DATA, slot), new NumberField(1),
                new NumberField(rekordboxId), new NumberField(0));
        if (response.knownType == Message.KnownType.WAVE_PREVIEW) {
            return new WaveformPreview(response);
        }
        logger.error("Unexpected response type when requesting waveform preview: {}", response);
        return null;
    }

    /**
     * Request the list of all tracks in the specified slot, given a connection to a player that has already been
     * set up.
     *
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved track list entry items
     *
     * @throws IOException if there is a communication problem
     */
    private static List<Message> getFullTrackList(CdjStatus.TrackSourceSlot slot, Client client)
        throws IOException {
        // Send the metadata menu request
        Message response = client.menuRequest(Message.KnownType.TRACK_LIST_REQ, Message.MenuIdentifier.MAIN_MENU, slot,
                new NumberField(0));
        final long count = response.getMenuResultsCount();
        if (count == Message.NO_MENU_RESULTS_AVAILABLE) {
            return Collections.emptyList();
        }

        // Gather all the metadata menu items
        return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slot, response);
    }


    /**
     * Look up track metadata from a cache.
     *
     * @param cache the appropriate metadata cache file
     * @param track identifies the track whose metadata is desired
     *
     * @return the cached metadata, including album art (if available), or {@code null}
     */
    private static TrackMetadata getCachedMetadata(ZipFile cache, TrackReference track) {
        ZipEntry entry = cache.getEntry(getMetadataEntryName(track.rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(cache.getInputStream(entry));
                List<Message> items = new LinkedList<Message>();
                Message current = Message.read(is);
                while (current.messageType.getValue() == Message.KnownType.MENU_ITEM.protocolValue) {
                    items.add(current);
                    current = Message.read(is);
                }
                return new TrackMetadata(track, items, getCachedCueList(cache, track.rekordboxId));
            } catch (IOException e) {
                logger.error("Problem reading metadata from cache file, returning null", e);
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (Exception e) {
                        logger.error("Problem closing ZipFile input stream for reading metadata entry", e);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Look up artwork from a cache.
     *
     * @param cache the appropriate metadata cache file
     * @param artworkId the database ID of the desired artwork
     *
     * @return the cached artwork bytes (if available), or {@code null}
     */
    private static ByteBuffer getCachedArtwork(ZipFile cache, int artworkId) {
        ZipEntry entry = cache.getEntry(getArtworkEntryName(artworkId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(cache.getInputStream(entry));
                byte[] imageBytes = new byte[(int)entry.getSize()];
                is.readFully(imageBytes);
                return(ByteBuffer.wrap(imageBytes).asReadOnlyBuffer());
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
     * Look up a beat grid in a metadata cache.
     *
     * @param cache the appropriate metadata cache file
     * @param rekordboxId the track whose beat grid is desired
     *
     * @return the cached beat grid (if available), or {@code null}
     */
    private static BeatGrid getCachedBeatGrid(ZipFile cache, int rekordboxId) {
        ZipEntry entry = cache.getEntry(getBeatGridEntryName(rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(cache.getInputStream(entry));
                byte[] gridBytes = new byte[(int)entry.getSize()];
                is.readFully(gridBytes);
                return new BeatGrid(ByteBuffer.wrap(gridBytes).asReadOnlyBuffer());
            } catch (IOException e) {
                logger.error("Problem reading beat grid from cache file, returning null", e);
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (Exception e) {
                        logger.error("Problem closing ZipFile input stream for reading beat grid entry", e);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Look up a cue list in a metadata cache.
     *
     * @param cache the appropriate metadata cache file
     * @param rekordboxId the track whose cue list is desired
     *
     * @return the cached cue list (if available), or {@code null}
     */
    private static CueList getCachedCueList(ZipFile cache, int rekordboxId) {
        ZipEntry entry = cache.getEntry(getCueListEntryName(rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(cache.getInputStream(entry));
                Message message = Message.read(is);
                return new CueList(message);
            } catch (IOException e) {
                logger.error("Problem reading cue list from cache file, returning null", e);
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (Exception e) {
                        logger.error("Problem closing ZipFile input stream for reading cue list", e);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Look up a waveform preview in a metadata cache.
     *
     * @param cache the appropriate metadata cache file
     * @param rekordboxId the track whose preview is desired
     *
     * @return the cached waveform preview (if available), or {@code null}
     */
    private static WaveformPreview getCachedWaveformPreview(ZipFile cache, int rekordboxId) {
        ZipEntry entry = cache.getEntry(getCueListEntryName(rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(cache.getInputStream(entry));
                Message message = Message.read(is);
                return new WaveformPreview(message);
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
     * Ask the connected dbserver for the playlist entries of the specified playlist (if {@code folder} is {@code false},
     * or the list of playlists and folders inside the specified playlist folder (if {@code folder} is {@code true}.
     * Pulled into a separate method so it can be used from multiple different client transactions.
     *
     * @param slot the slot in which the track can be found
     * @param sortOrder the order in which responses should be sorted, 0 for default, see Section 5.10.1 of the
     *                  <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis
     *                  document</a> for details
     * @param playlistOrFolderId the database ID of the desired playlist or folder
     * @param folder indicates whether we are asking for the contents of a folder or playlist
     * @param client the dbserver client that is communicating with the appropriate player

     * @return the items that are found in the specified playlist or folder; they will be tracks if we are asking
     *         for a playlist, or playlists and folders if we are asking for a folder

     * @throws IOException if there is a problem communicating
     */
    private static List<Message> getPlaylistItems(CdjStatus.TrackSourceSlot slot, int sortOrder, int playlistOrFolderId,
                                                  boolean folder, Client client) throws IOException {
        Message response = client.menuRequest(Message.KnownType.PLAYLIST_REQ, Message.MenuIdentifier.MAIN_MENU, slot,
                new NumberField(sortOrder), new NumberField(playlistOrFolderId), new NumberField(folder? 1 : 0));
        final long count = response.getMenuResultsCount();
        if (count == Message.NO_MENU_RESULTS_AVAILABLE) {
            return Collections.emptyList();
        }

        // Gather all the metadata menu items
        return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slot, response);
    }

    /**
     * Ask the specified player for the playlist entries of the specified playlist (if {@code folder} is {@code false},
     * or the list of playlists and folders inside the specified playlist folder (if {@code folder} is {@code true}.
     *
     * @param player the player number whose playlist entries are of interest
     * @param slot the slot in which the playlist can be found
     * @param sortOrder the order in which responses should be sorted, 0 for default, see Section 5.10.1 of the
     *                  <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis
     *                  document</a> for details
     * @param playlistOrFolderId the database ID of the desired playlist or folder
     * @param folder indicates whether we are asking for the contents of a folder or playlist
     *
     * @return the items that are found in the specified playlist or folder; they will be tracks if we are asking
     *         for a playlist, or playlists and folders if we are asking for a folder
     *
     * @throws Exception if there is a problem obtaining the playlist information
     */
    public static List<Message> requestPlaylistItemsFrom(final int player, final CdjStatus.TrackSourceSlot slot,
                                                         final int sortOrder, final int playlistOrFolderId,
                                                         final boolean folder)
            throws Exception {
        ConnectionManager.ClientTask<List<Message>> task = new ConnectionManager.ClientTask<List<Message>>() {
            @Override
            public List<Message> useClient(Client client) throws Exception {
               return getPlaylistItems(slot, sortOrder, playlistOrFolderId, folder, client);
            }
        };

        return ConnectionManager.invokeWithClientSession(player, task, "requesting playlist information");
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
        public LruCache(final int maxEntries) {
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
     * The maximum number of artwork images we will retain in our cache
     */
    private static final int ART_CACHE_SIZE = 100;

    /**
     * Establish the artwork cache. Even though we are not caching tracks, the {@link TrackReference} tuple has
     * exactly the information we need to identify cached artwork.
     */
    private static final Map<TrackReference, ByteBuffer> artCache =
            Collections.synchronizedMap(new LruCache<TrackReference, ByteBuffer>(ART_CACHE_SIZE));

    /**
     * Ask the specified player for the specified artwork from the specified media slot, first checking if we have a
     * cached copy.
     *
     * @param slot the slot in which the artwork can be found
     * @param artworkId the unique database ID of the desired artwork
     *
     * @return a newly allocated image containing the artwork, if it was found, or {@code null}
     *
     * @throws Exception if there is a problem obtaining the artwork
     */
    public static BufferedImage requestArtworkFrom(final SlotReference slot, final int artworkId)
            throws Exception {

        // First check the in-memory artwork cache
        final TrackReference ref = new TrackReference(slot.player, slot.slot, artworkId);
        ByteBuffer artwork = artCache.get(ref);
        if (artwork == null) {
            // Then check if we are using cached data for this slot
            ZipFile cache = getMetadataCache(slot);
            if (cache != null) {
                artwork = getCachedArtwork(cache, artworkId);
            } else {
                // Have to actually request it.
                ConnectionManager.ClientTask<ByteBuffer> task = new ConnectionManager.ClientTask<ByteBuffer>() {
                    @Override
                    public ByteBuffer useClient(Client client) throws Exception {
                        return getArtwork(artworkId, slot.slot, client);
                    }
                };
                artwork = ConnectionManager.invokeWithClientSession(slot.player, task, "requesting artwork");
            }
            if (artwork != null) {  // Our cache file load or network request succeeded, so add to the in-memory cache.
                artCache.put(ref, artwork);
            }
        }
        if (artwork != null) {  // We found artwork somewhere, so create an image from it
            artwork.rewind();
            byte[] imageBytes = new byte[artwork.remaining()];
            artwork.get(imageBytes);
            try {
                return ImageIO.read(new ByteArrayInputStream(imageBytes));
            } catch (IOException e) {
                logger.error("Weird! Caught exception creating image from artwork bytes", e);
                return null;
            }
        }
        return null;  // We were not able to find the artwork.
    }

    /**
     * Ask the specified player for the beat grid of the track in the specified slot with the specified rekordbox ID,
     * first checking if we have a cache we can use instead.
     *
     * @param track uniquely identifies the track whose beat grid is desired
     *
     * @return the beat grid, if any
     *
     * @throws Exception if there is a problem getting the beat grid information
     */
    public static BeatGrid requestBeatGridFrom(final TrackReference track)
            throws Exception {

        // First check if we are using cached data for this slot
        ZipFile cache = getMetadataCache(SlotReference.getSlotReference(track));
        if (cache != null) {
            return getCachedBeatGrid(cache, track.rekordboxId);
        }

        ConnectionManager.ClientTask<BeatGrid> task = new ConnectionManager.ClientTask<BeatGrid>() {
            @Override
            public BeatGrid useClient(Client client) throws Exception {
                return getBeatGrid(track.rekordboxId, track.slot, client);
            }
        };

        return ConnectionManager.invokeWithClientSession(track.player, task, "requesting beat grid");
    }

    /**
     * Ask the specified player for the waveform preview of the track in the specified slot with the specified
     * rekordbox ID, first checking if we have a cache we can use instead.
     *
     * @param track uniquely identifies the track whose waveform preview is desired
     *
     * @return the preview, if any
     *
     * @throws Exception if there is a problem getting the waveform preview
     */
    public static WaveformPreview requestWaveformPreviewFrom(final TrackReference track)
            throws Exception {

        // First check if we are using cached data for this slot
        ZipFile cache = getMetadataCache(SlotReference.getSlotReference(track));
        if (cache != null) {
            return getCachedWaveformPreview(cache, track.rekordboxId);
        }

        ConnectionManager.ClientTask<WaveformPreview> task = new ConnectionManager.ClientTask<WaveformPreview>() {
            @Override
            public WaveformPreview useClient(Client client) throws Exception {
                return getWaveformPreview(track.rekordboxId, track.slot, client);
            }
        };

        return ConnectionManager.invokeWithClientSession(track.player, task, "requesting cue list");
    }

    /**
     * Creates a metadata cache archive file of all tracks in the specified slot on the specified player. Any
     * previous contents of the specified file will be replaced.
     *
     * @param slot the slot in which the media to be cached can be found
     * @param playlistId the id of playlist to be cached, or 0 of all tracks should be cached
     * @param cache the file into which the metadata cache should be written
     *
     * @throws Exception if there is a problem communicating with the player or writing the cache file.
     */
    public static void createMetadataCache(SlotReference slot, int playlistId, File cache)
            throws Exception {
        createMetadataCache(slot, playlistId, cache, null);
    }

    /**
     * The root under which all zip file entries will be created in our cache metadata files.
     */
    private static final String CACHE_PREFIX = "BLTMetaCache/";

    /**
     * The file entry whose content will be the cache format identifier.
     */
    private static final String CACHE_FORMAT_ENTRY = CACHE_PREFIX + "version";

    /**
     * The prefix for cache file entries that will store track metadata.
     */
    private static final String CACHE_METADATA_ENTRY_PREFIX = CACHE_PREFIX + "metadata/";

    /**
     * The prefix for cache file entries that will store album art.
     */
    private static final String CACHE_ART_ENTRY_PREFIX = CACHE_PREFIX + "artwork/";

    /**
     * The prefix for cache file entries that will store beat grids.
     */
    private static final String CACHE_BEAT_GRID_ENTRY_PREFIX = CACHE_PREFIX + "beatGrid/";

    /**
     * The prefix for cache file entries that will store beat grids.
     */
    private static final String CACHE_CUE_LIST_ENTRY_PREFIX = CACHE_PREFIX + "cueList/";

    /**
     * The prefix for cache file entries that will store waveform previews.
     */
    private static final String CACHE_WAVEFORM_PREVIEW_ENTRY_PREFIX = CACHE_PREFIX + "wavePrev/";

    /**
     * The comment string used to identify a ZIP file as one of our metadata caches.
     */
    @SuppressWarnings("WeakerAccess")
    public static final String CACHE_FORMAT_IDENTIFIER = "BeatLink Metadata Cache version 1";

    /**
     * Used to mark the end of the metadata items in each cache entry, just like when reading from the server.
     */
    private static final Message MENU_FOOTER_MESSAGE = new Message(0, Message.KnownType.MENU_FOOTER);

    /**
     * Finish the process of copying a list of tracks to a metadata cache, once they have been listed. This code
     * is shared between the implementations that work with the full track list and with playlists.
     *
     * @param trackListEntries the list of menu items identifying which tracks need to be copied to the metadata
     *                         cache
     * @param client the connection to the dbserver on the player whose metadata is being cached
     * @param slot the slot in which the media to be cached can be found
     * @param cache the file into which the metadata cache should be written
     * @param listener will be informed after each track is added to the cache file being created and offered
     *                 the opportunity to cancel the process
     *
     * @throws IOException if there is a problem communicating with the player or writing the cache file.
     */
    private static void copyTracksToCache(List<Message> trackListEntries, Client client, SlotReference slot,
                                          File cache, MetadataCreationUpdateListener listener)
        throws IOException {
        FileOutputStream fos = null;
        BufferedOutputStream bos = null;
        ZipOutputStream zos = null;
        WritableByteChannel channel = null;
        final Set<Integer> artworkAdded = new HashSet<Integer>();
        try {
            fos = new FileOutputStream(cache);
            bos = new BufferedOutputStream(fos);
            zos = new ZipOutputStream(bos);
            zos.setMethod(ZipOutputStream.DEFLATED);

            // Add a marker so we can recognize this as a metadata archive. I would use the ZipFile comment, but
            // that is not available until Java 7, and Beat Link is supposed to be backwards compatible with Java 6.
            ZipEntry zipEntry = new ZipEntry(CACHE_FORMAT_ENTRY);
            zos.putNextEntry(zipEntry);
            zos.write(CACHE_FORMAT_IDENTIFIER.getBytes("UTF-8"));

            // Write the actual metadata entries
            channel = Channels.newChannel(zos);
            final int totalToCopy = trackListEntries.size();
            int tracksCopied = 0;

            for (Message entry : trackListEntries) {
                if (entry.getMenuItemType() != Message.MenuItemType.TRACK_LIST_ENTRY) {
                    throw new IOException("Received unexpected item type. Needed TRACK_LIST_ENTRY, got: " + entry);
                }
                int rekordboxId = (int)((NumberField)entry.arguments.get(1)).getValue();
                TrackMetadata track = queryMetadata(new TrackReference(slot, rekordboxId), client);

                if (track != null) {
                    logger.debug("Adding metadata with ID {}", rekordboxId);
                    zipEntry = new ZipEntry(getMetadataEntryName(rekordboxId));
                    zos.putNextEntry(zipEntry);
                    for (Message metadataItem : track.rawItems) {
                        metadataItem.write(channel);
                    }
                    MENU_FOOTER_MESSAGE.write(channel);  // So we know to stop reading
                } else {
                    logger.warn("Unable to retrieve metadata with ID {}", rekordboxId);
                }

                // Now the album art, if any
                if (track != null && track.getArtworkId() != 0 && !artworkAdded.contains(track.getArtworkId())) {
                    logger.debug("Adding artwork with ID {}", track.getArtworkId());
                    zipEntry = new ZipEntry(getArtworkEntryName(track.getArtworkId()));
                    zos.putNextEntry(zipEntry);
                    Util.writeFully(getArtwork(track.getArtworkId(), slot.slot, client), channel);
                    artworkAdded.add(track.getArtworkId());
                }

                BeatGrid beatGrid = getBeatGrid(rekordboxId, slot.slot, client);
                if (beatGrid != null) {
                    logger.debug("Adding beat grid with ID {}", rekordboxId);
                    zipEntry = new ZipEntry(getBeatGridEntryName(rekordboxId));
                    zos.putNextEntry(zipEntry);
                    Util.writeFully(beatGrid.getRawData(), channel);
                }

                CueList cueList = getCueList(rekordboxId, slot.slot, client);
                if (cueList != null) {
                    logger.debug("Adding cue list entry with ID {}", rekordboxId);
                    zipEntry = new ZipEntry((getCueListEntryName(rekordboxId)));
                    zos.putNextEntry(zipEntry);
                    cueList.rawMessage.write(channel);
                }

                WaveformPreview preview = getWaveformPreview(rekordboxId, slot.slot, client);
                if (preview != null) {
                    logger.debug("Adding waveform preview entry with ID {}", rekordboxId);
                    zipEntry = new ZipEntry((getWaveformPreviewEntryName(rekordboxId)));
                    zos.putNextEntry(zipEntry);
                    preview.rawMessage.write(channel);
                }

                // TODO: Include waveforms (once supported), etc.
                if (listener != null) {
                    if (!listener.cacheUpdateContinuing(track, ++tracksCopied, totalToCopy)) {
                        logger.info("Track metadata cache creation canceled by listener");
                        if (!cache.delete()) {
                            logger.warn("Unable to delete cache metadata file, {}", cache);
                        }
                        return;
                    }
                }
            }
        } finally {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (Exception e) {
                logger.error("Problem closing byte channel for writing to metadata cache", e);
            }
            try {
                if (zos != null) {
                    zos.close();
                }
            } catch (Exception e) {
                logger.error("Problem closing Zip Output Stream of metadata cache", e);
            }
            try {
                if (bos != null) {
                    bos.close();
                }
            } catch (Exception e) {
                logger.error("Problem closing Buffered Output Stream of metadata cache", e);
            }
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (Exception e) {
                logger.error("Problem closing File Output Stream of metadata cache", e);
            }
        }
    }

    /**
     * Names the appropriate zip file entry for caching a track's metadata.
     *
     * @param rekordboxId the id of the track being cached or looked up
     *
     * @return the name of the entry where that track's metadata should be stored
     */
    private static String getMetadataEntryName(int rekordboxId) {
        return CACHE_METADATA_ENTRY_PREFIX + rekordboxId;
    }

    /**
     * Names the appropriate zip file entry for caching album art.
     *
     * @param artworkId the database ID of the artwork being cached or looked up
     *
     * @return the name of entry where that artwork should be stored
     */
    private static String getArtworkEntryName(int artworkId) {
        return CACHE_ART_ENTRY_PREFIX + artworkId + ".jpg";
    }

    /**
     * Names the appropriate zip file entry for caching a track's beat grid.
     *
     * @param rekordboxId the id of the track being cached or looked up
     *
     * @return the name of the entry where that track's beat grid should be stored
     */
    private static String getBeatGridEntryName(int rekordboxId) {
        return CACHE_BEAT_GRID_ENTRY_PREFIX + rekordboxId;
    }

    /**
     * Names the appropriate zip file entry for caching a track's cue list.
     *
     * @param rekordboxId the id of the track being cached or looked up
     *
     * @return the name of the entry where that track's cue list should be stored
     */
    private static String getCueListEntryName(int rekordboxId) {
        return CACHE_CUE_LIST_ENTRY_PREFIX + rekordboxId;
    }

    /**
     * Names the appropriate zip file entry for caching a track's waveform preview.
     *
     * @param rekordboxId the id of the track being cached or looked up
     *
     * @return the name of the entry where that track's cue list should be stored
     */
    private static String getWaveformPreviewEntryName(int rekordboxId) {
        return CACHE_WAVEFORM_PREVIEW_ENTRY_PREFIX + rekordboxId;
    }

    /**
     * Creates a metadata cache archive file of all tracks in the specified slot on the specified player. Any
     * previous contents of the specified file will be replaced. If a non-{@code null} {@code listener} is
     * supplied, its {@link MetadataCreationUpdateListener#cacheUpdateContinuing(TrackMetadata, int, int)} method
     * will be called after each track is added to the cache, allowing it to display progress updates to the user,
     * and to continue or cancel the process by returning {@code true} or {@code false}.
     *
     * Because this takes a huge amount of time relative to CDJ status updates, it can only be performed while
     * the MetadataFinder is in passive mode.
     *
     * @param slot the slot in which the media to be cached can be found
     * @param playlistId the id of playlist to be cached, or 0 of all tracks should be cached
     * @param cache the file into which the metadata cache should be written
     * @param listener will be informed after each track is added to the cache file being created and offered
     *                 the opportunity to cancel the process
     *
     * @throws Exception if there is a problem communicating with the player or writing the cache file
     */
    @SuppressWarnings({"SameParameterValue", "WeakerAccess"})
    public static void createMetadataCache(final SlotReference slot, final int playlistId,
                                           final File cache, final MetadataCreationUpdateListener listener)
            throws Exception {
        ConnectionManager.ClientTask<Object> task = new ConnectionManager.ClientTask<Object>() {
            @Override
            public Object useClient(Client client) throws Exception {
                final List<Message> trackList;
                if (playlistId == 0) {
                    trackList = getFullTrackList(slot.slot, client);
                } else {
                    trackList = getPlaylistItems(slot.slot, 0, playlistId, false, client);
                }
                copyTracksToCache(trackList, client, slot, cache, listener);
                return null;
            }
        };

        if (!cache.delete()) {
            logger.warn("Unable to delete cache file, {}", cache);
        }
        ConnectionManager.invokeWithClientSession(slot.player, task, "building metadata cache");
    }

    /**
     * Keeps track of the current metadata cached for each player. We cache metadata for any track which is currently
     * on-deck in the player, as well as any that were loaded into a player's hot-cue slot.
     */
    private static final Map<DeckReference, TrackMetadata> hotCache = new ConcurrentHashMap<DeckReference, TrackMetadata>();

    /**
     * A queue used to hold CDJ status updates we receive from the {@link VirtualCdj} so we can process them on a
     * lower priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private static final LinkedBlockingDeque<CdjStatus> pendingUpdates = new LinkedBlockingDeque<CdjStatus>(100);

    /**
     * Our update listener just puts appropriate device updates on our queue, so we can process them on a lower
     * priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private static final DeviceUpdateListener updateListener = new DeviceUpdateListener() {
        @Override
        public void received(DeviceUpdate update) {
            logger.debug("Received device update {}", update);
            if (update instanceof CdjStatus) {
                if (!pendingUpdates.offerLast((CdjStatus)update)) {
                    logger.warn("Discarding CDJ update because our queue is backed up.");
                }
            }
        }
    };


    /**
     * Our announcement listener watches for devices to appear on the network so we can ask them for their database
     * server port, and when they disappear discards all information about them.
     */
    private static final DeviceAnnouncementListener announcementListener = new DeviceAnnouncementListener() {
        @Override
        public void deviceFound(final DeviceAnnouncement announcement) {
            logger.debug("Currently nothing for MetaDataListener to do when devices appear.");
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            clearMetadata(announcement);
            detachMetadataCache(SlotReference.getSlotReference(announcement.getNumber(), CdjStatus.TrackSourceSlot.SD_SLOT));
            detachMetadataCache(SlotReference.getSlotReference(announcement.getNumber(), CdjStatus.TrackSourceSlot.USB_SLOT));
        }
    };

    /**
     * Keep track of whether we are running
     */
    private static boolean running = false;

    /**
     * Check whether we are currently running.
     *
     * @return true if track metadata is being sought for all active players
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
     * Check whether we are configured to use metadata only from caches, never actively requesting it from a player.
     *
     * @return {@code true} if only cached metadata will be used, or {@code false} if metadata will be requested from
     *         a player if a track is loaded from a media slot to which no cache has been assigned
     */
    public static synchronized boolean isPassive() {
        return passive;
    }

    /**
     * Set whether we are configured to use metadata only from caches, never actively requesting it from a player.
     *
     * @param passive {@code true} if only cached metadata will be used, or {@code false} if metadata will be requested
     *                from a player if a track is loaded from a media slot to which no cache has been assigned
     */
    public static synchronized void setPassive(boolean passive) {
        MetadataFinder.passive = passive;
    }

    /**
     * We process our player status updates on a separate thread so as not to slow down the high-priority update
     * delivery thread; we perform potentially slow I/O.
     */
    private static Thread queueHandler;

    /**
     * We have received an update that invalidates any previous metadata for that player, so clear it out, and alert
     * any listeners. This does not affect the hot cues; they will stick around until the player loads a new track
     * that overwrites one or more of them.
     *
     * @param update the update which means we can have no metadata for the associated player
     */
    private static synchronized void clearDeck(CdjStatus update) {
        hotCache.remove(DeckReference.getDeckReference(update.deviceNumber, 0));
        deliverTrackMetadataUpdate(update.deviceNumber, null);
    }

    /**
     * We have received notification that a device is no longer on the network, so clear out its metadata.
     *
     * @param announcement the packet which reported the device’s disappearance
     */
    private static synchronized void clearMetadata(DeviceAnnouncement announcement) {
        final int player = announcement.getNumber();
        for (DeckReference deck : hotCache.keySet()) {
            if (deck.player == player) {
                hotCache.remove(deck);
            }
        }
        for (TrackReference artReference : artCache.keySet()) {
            if (artReference.player == player) {
                artCache.remove(artReference);
            }
        }
    }

    /**
     * We have obtained metadata for a device, so store it and alert any listeners.
     *
     * @param update the update which caused us to retrieve this metadata
     * @param data the metadata which we received
     */
    private static synchronized void updateMetadata(CdjStatus update, TrackMetadata data) {
        hotCache.put(DeckReference.getDeckReference(update.deviceNumber, 0), data);  // Main deck
        if (data.getCueList() != null) {  // Update the cache with any hot cues in this track as well
            for (CueList.Entry entry : data.getCueList().entries) {
                if (entry.hotCueNumber != 0) {
                    hotCache.put(DeckReference.getDeckReference(update.deviceNumber, entry.hotCueNumber), data);
                }
            }
        }
        deliverTrackMetadataUpdate(update.deviceNumber, data);
    }

    /**
     * Get the metadata of all tracks currently loaded in any player, either on the play deck, or in a hot cue.
     *
     * @return the track information reported by all current players, including any tracks loaded in their hot cue slots
     */
    public static synchronized Map<DeckReference, TrackMetadata> getLoadedTracks() {
        return Collections.unmodifiableMap(new HashMap<DeckReference, TrackMetadata>(hotCache));
    }

    /**
     * Look up the track metadata we have for the track loaded in the main deck of a given player number.
     *
     * @param player the device number whose track metadata for the playing track is desired
     * @return information about the track loaded on that player, if available
     */
    @SuppressWarnings("WeakerAccess")
    public static synchronized TrackMetadata getLatestMetadataFor(int player) {
        return hotCache.get(DeckReference.getDeckReference(player, 0));
    }

    /**
     * Look up the track metadata we have for a given player, identified by a status update received from that player.
     *
     * @param update a status update from the player for which track metadata is desired
     * @return information about the track loaded on that player, if available
     */
    public static TrackMetadata getLatestMetadataFor(DeviceUpdate update) {
        return getLatestMetadataFor(update.deviceNumber);
    }

    /**
     * Keep track of the devices we are currently trying to get metadata from in response to status updates.
     */
    private static final Set<Integer> activeRequests = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    /**
     * Keeps track of any metadata caches that have been attached for the slots of players on the network,
     * keyed by slot reference.
     */
    private final static Map<SlotReference, ZipFile> metadataCacheFiles = new ConcurrentHashMap<SlotReference, ZipFile>();

    /**
     * Attach a metadata cache file to a particular player media slot, so the cache will be used instead of querying
     * the player for metadata. This supports operation with metadata during shows where DJs are using all four player
     * numbers and heavily cross-linking between them.
     *
     * If the media is ejected from that player slot, the cache will be detached.
     *
     * @param slot the media slot to which a meta data cache is to be attached
     * @param cache the metadata cache to be attached
     *
     * @throws IOException if there is a problem reading the cache file
     * @throws IllegalArgumentException if an invalid player number or slot is supplied
     * @throws IllegalStateException if the metadata finder is not running
     */
    public static void attachMetadataCache(SlotReference slot, File cache)
            throws IOException {
        if (!isRunning()) {
            throw new IllegalStateException("attachMetadataCache() can't be used if MetadataFinder is not running");
        }
        if (slot.player < 1 || slot.player > 4 || DeviceFinder.getLatestAnnouncementFrom(slot.player) == null) {
            throw new IllegalArgumentException("unable to attach metadata cache for player " + slot.player);
        }
        ZipFile oldCache;

        // Open and validate the cache
        ZipFile newCache = new ZipFile(cache, ZipFile.OPEN_READ);
        ZipEntry zipEntry = newCache.getEntry(CACHE_FORMAT_ENTRY);
        InputStream is = newCache.getInputStream(zipEntry);
        Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A");
        String tag = null;
        if (s.hasNext()) tag = s.next();
        if (!CACHE_FORMAT_IDENTIFIER.equals(tag)) {
            try {
                newCache.close();
            } catch (Exception e) {
                logger.error("Problem re-closing newly opened candidate metadata cache", e);
            }
            throw new IOException("File does not contain a Beat Link metadata cache: " + cache +
            " (looking for format identifier \"" + CACHE_FORMAT_IDENTIFIER + "\", found: " + tag);
        }

        oldCache = metadataCacheFiles.put(slot, newCache);
        if (oldCache != null) {
            try {
                oldCache.close();
            } catch (IOException e) {
                logger.error("Problem closing previous metadata cache", e);
            }
        }

        deliverCacheUpdate();
    }

    /**
     * Removes any metadata cache file that might have been assigned to a particular player media slot, so metadata
     * will be looked up from the player itself.
     *
     * @param slot the media slot to which a meta data cache is to be attached
     */
    @SuppressWarnings("WeakerAccess")
    public static void detachMetadataCache(SlotReference slot) {
        ZipFile oldCache = metadataCacheFiles.remove(slot);
        if (oldCache != null) {
            try {
                oldCache.close();
            } catch (IOException e) {
                logger.error("Problem closing metadata cache", e);
            }
            deliverCacheUpdate();
        }
    }

    /**
     * Finds the metadata cache file assigned to a particular player media slot, if any.
     *
     * @param slot the media slot to which a meta data cache is to be attached
     *
     * @return the zip file being used as a metadata cache for that player and slot, or {@code null} if no cache
     *         has been attached
     */
    @SuppressWarnings("WeakerAccess")
    public static ZipFile getMetadataCache(SlotReference slot) {
        return metadataCacheFiles.get(slot);
    }

    /**
     * Keeps track of any players with mounted media.
     */
    private static final Set<SlotReference> mediaMounts = Collections.newSetFromMap(new ConcurrentHashMap<SlotReference, Boolean>());

    /**
     * Records that there is media mounted in a particular media player slot, updating listeners if this is a change.
     *
     * @param slot the slot in which media is mounted
     */
    private static void recordMount(SlotReference slot) {
        if (mediaMounts.add(slot)) {
            deliverCacheUpdate();
        }
    }

    /**
     * Records that there is no media mounted in a particular media player slot, updating listeners if this is a change,
     * and clearing any affected items from our in-memory caches.
     *
     * @param slot the slot in which no media is mounted
     */
    private static void removeMount(SlotReference slot) {
        if (mediaMounts.remove(slot)) {
            deliverCacheUpdate();
            for (TrackReference artReference : artCache.keySet()) {
                if (SlotReference.getSlotReference(artReference) == slot) {
                    artCache.remove(artReference);
                }
            }
        }
    }

    /**
     * Returns the set of media slots on the network that currently have media mounted in them.
     *
     * @return the slots with media currently available on the network
     */
    public static Set<SlotReference> getMountedMediaSlots() {
        return Collections.unmodifiableSet(mediaMounts);
    }

    /**
     * Keeps track of the registered cache update listeners.
     */
    private static final Set<MetadataCacheUpdateListener> cacheListeners = new HashSet<MetadataCacheUpdateListener>();

    /**
     * Adds the specified cache update listener to receive updates when a metadata cache is attached or detached.
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
     * @param listener the cache update listener to add
     */
    public static synchronized void addCacheUpdateListener(MetadataCacheUpdateListener listener) {
        if (listener != null) {
            cacheListeners.add(listener);
        }
    }

    /**
     * Removes the specified cache update listener so that it no longer receives updates when there
     * are changes to the available set of metadata caches. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the master listener to remove
     */
    public static synchronized void removeCacheUpdateListener(MetadataCacheUpdateListener listener) {
        if (listener != null) {
            cacheListeners.remove(listener);
        }
    }

    /**
     * Get the set of currently-registered metadata cache update listeners.
     *
     * @return the listeners that are currently registered for metadata cache updates
     */
    @SuppressWarnings("WeakerAccess")
    public static synchronized Set<MetadataCacheUpdateListener> getCacheUpdateListeners() {
        return Collections.unmodifiableSet(new HashSet<MetadataCacheUpdateListener>(cacheListeners));
    }

    /**
     * Send a metadata cache update announcement to all registered listeners.
     */
    private static void deliverCacheUpdate() {
        final Map<SlotReference, ZipFile> caches = Collections.unmodifiableMap(metadataCacheFiles);
        final Set<SlotReference> mounts = Collections.unmodifiableSet(mediaMounts);
        for (final MetadataCacheUpdateListener listener : getCacheUpdateListeners()) {
            try {
                listener.cacheStateChanged(caches, mounts);

            } catch (Exception e) {
                logger.warn("Problem delivering metadata cache update to listener", e);
            }
        }
    }

    /**
     * Keeps track of the registered track metadata update listeners.
     */
    private static final Set<TrackMetadataUpdateListener> trackListeners = new HashSet<TrackMetadataUpdateListener>();

    /**
     * Adds the specified track metadata listener to receive updates when the track metadata for a player changes.
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
     * @param listener the track metadata update listener to add
     */
    public static synchronized void addTrackMetadataUpdateListener(TrackMetadataUpdateListener listener) {
        if (listener != null) {
            trackListeners.add(listener);
        }
    }

    /**
     * Get the set of currently-registered metadata cache update listeners.
     *
     * @return the listeners that are currently registered for metadata cache updates
     */
    @SuppressWarnings("WeakerAccess")
    public static synchronized Set<TrackMetadataUpdateListener> getTrackMetadataUpdateListeners() {
        return Collections.unmodifiableSet(new HashSet<TrackMetadataUpdateListener>(trackListeners));
    }

    /**
     * Removes the specified track metadata update listener so that it no longer receives updates when track
     * metadata for a player changes. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the track metadata update listener to remove
     */
    public static synchronized void removeTrackMetadataUpdateListener(TrackMetadataUpdateListener listener) {
        if (listener != null) {
            trackListeners.remove(listener);
        }
    }

    /**
     * Send a metadata cache update announcement to all registered listeners.
     */
    private static void deliverTrackMetadataUpdate(int player, TrackMetadata metadata) {
        for (final TrackMetadataUpdateListener listener : getTrackMetadataUpdateListeners()) {
            try {
                listener.metadataChanged(player, metadata);

            } catch (Exception e) {
                logger.warn("Problem delivering track metadata update to listener", e);
            }
        }
    }

    /**
     * Process an update packet from one of the CDJs. See if it has a valid track loaded; if not, clear any
     * metadata we had stored for that player. If so, see if it is the same track we already know about; if not,
     * request the metadata associated with that track.
     *
     * Also clears out any metadata caches that were attached for slots that no longer have media mounted in them,
     * and updates the sets of which players have media mounted in which slots.
     *
     * If any of these reflect a change in state, any registered listeners will be informed.
     *
     * @param update an update packet we received from a CDJ
     */
    private static void handleUpdate(final CdjStatus update) {
        // First see if any metadata caches need evicting or mount sets need updating.
        if (update.isLocalUsbEmpty()) {
            final SlotReference slot = SlotReference.getSlotReference(update.deviceNumber, CdjStatus.TrackSourceSlot.USB_SLOT);
            detachMetadataCache(slot);
            removeMount(slot);
        } else if (update.isLocalUsbLoaded()) {
            recordMount(SlotReference.getSlotReference(update.deviceNumber, CdjStatus.TrackSourceSlot.USB_SLOT));
        }
        if (update.isLocalSdEmpty()) {
            final SlotReference slot = SlotReference.getSlotReference(update.deviceNumber, CdjStatus.TrackSourceSlot.SD_SLOT);
            detachMetadataCache(slot);
            removeMount(slot);
        } else if (update.isLocalSdLoaded()){
            recordMount(SlotReference.getSlotReference(update.deviceNumber, CdjStatus.TrackSourceSlot.SD_SLOT));
        }

        // Now see if a track has changed that needs new metadata.
        if (update.getTrackType() != CdjStatus.TrackType.REKORDBOX ||
                update.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.NO_TRACK ||
                update.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.UNKNOWN ||
                update.getRekordboxId() == 0) {  // We no longer have metadata for this device.
            clearDeck(update);
        } else {  // We can offer metadata for this device; check if we already looked up this track.
            final TrackMetadata lastMetadata = hotCache.get(DeckReference.getDeckReference(update.deviceNumber, 0));
            final TrackReference trackReference = new TrackReference(update.getTrackSourcePlayer(),
                    update.getTrackSourceSlot(), update.getRekordboxId());
            if (lastMetadata == null || !lastMetadata.trackReference.equals(trackReference)) {  // We have something new!
                // First see if we can find the new track in the hot cache as a hot cue
                for (TrackMetadata cached : hotCache.values()) {
                    if (cached.trackReference.equals(trackReference)) {  // Found a hot cue hit, use it.
                        updateMetadata(update, cached);
                        return;
                    }
                }

                // Not in the hot cache so try actually retrieving it.
                if (activeRequests.add(update.getTrackSourcePlayer())) {
                    clearDeck(update);  // We won't know what it is until our request completes.
                    // We had to make sure we were not already asking for this track.
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                TrackMetadata data = requestMetadataInternal(trackReference, true);
                                if (data != null) {
                                    updateMetadata(update, data);
                                }
                            } catch (Exception e) {
                                logger.warn("Problem requesting track metadata from update" + update, e);
                            } finally {
                                activeRequests.remove(update.getTrackSourcePlayer());
                            }
                        }
                    }).start();
                }
            }
        }
    }

    /**
     * Start finding track metadata for all active players. Starts the {@link VirtualCdj} if it is not already
     * running, because we need it to send us device status updates to notice when new tracks are loaded.
     *
     * @throws Exception if there is a problem starting the required components
     */
    public static synchronized void start() throws Exception {
        if (!running) {
            ConnectionManager.start();
            DeviceFinder.start();
            DeviceFinder.addDeviceAnnouncementListener(announcementListener);
            VirtualCdj.start();
            VirtualCdj.addUpdateListener(updateListener);
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
     * Stop finding track metadata for all active players.
     */
    public static synchronized void stop() {
        if (running) {
            VirtualCdj.removeUpdateListener(updateListener);
            running = false;
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;
            hotCache.clear();
        }
    }
}
