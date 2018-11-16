package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.MediaDetails;
import org.deepsymmetry.beatlink.Util;
import org.deepsymmetry.beatlink.dbserver.Client;
import org.deepsymmetry.beatlink.dbserver.ConnectionManager;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.beatlink.dbserver.NumberField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * A ZIP-file based cache of all the kinds of track metadata that we need, so we can operate with full functionality
 * even when metadata requests are difficult or impossible because four CDJs are all using the same media.
 *
 * Although this class implements {@link MetadataProvider}, it should <em>not</em> be passed to
 * {@link MetadataFinder#addMetadataProvider(MetadataProvider)} because there is all kinds of special handling
 * needed to deal in a backwards-compatible way with older cache files that do not store media details. Instead,
 * use the longstanding {@link MetadataFinder#attachMetadataCache(SlotReference, File)} and
 * {@link MetadataFinder#addAutoAttachCacheFile(File)} methods to work with metadata cache files.
 *
 * @since 0.5.0
 */
@SuppressWarnings("WeakerAccess")
public class MetadataCache implements MetadataProvider {

    private static final Logger logger = LoggerFactory.getLogger(MetadataCache.class);

    /**
     * The file that contains our cached information.
     */
    private final ZipFile zipFile;


    /**
     * Holds the ID of the playlist that was used to create the cache, or 0 of it is an all-tracks cache.
     */
    public final int sourcePlaylist;

    /**
     * Holds the number of tracks contained in the cache.
     */
    public final int trackCount;

    /**
     * Holds information about the media from which this cache was created. Will be present in caches created by
     * Beat Link version 0.4.1 or later; older caches will result in a {@code null} value.
     */
    public final MediaDetails sourceMedia;

    /**
     * Open the specified ZIP file and prepare to serve its contents as a cache. When you are finished with the cache,
     * be sure to call its {@link #close()} method to free up system resources.
     *
     * @param file the metadata cache file to be served.
     *
     * @throws IOException if there is a problem reading the file, or if it does not have the right content
     */
    public MetadataCache(File file) throws IOException {
        zipFile = new ZipFile(file, ZipFile.OPEN_READ);
        String tag = getCacheFormatEntry();
        if (tag == null || !tag.startsWith(CACHE_FORMAT_IDENTIFIER)) {
            try {
                zipFile.close();
            } catch (Exception e) {
                logger.error("Problem re-closing newly opened candidate metadata cache", e);
            }
            throw new IOException("File does not contain a Beat Link metadata cache: " + file +
                    " (looking for format identifier \"" + CACHE_FORMAT_IDENTIFIER + "\", found: " + tag);
        }
        String[] pieces = tag.split(":");
        sourcePlaylist = Integer.parseInt(pieces[1]);
        trackCount = Integer.parseInt(pieces[2]);
        sourceMedia = getCacheMediaDetails();
    }

    /**
     * Close the cache. This should be called when the cache will no longer be used, to free up file descriptors.
     * Once this is called, trying to access any cached information will fail with an {@link IOException}.
     *
     * @throws IOException if there is a problem closing the cache
     */
    public void close() throws IOException {
        zipFile.close();
    }

    /**
     * Get the path name of the file containing the cache.
     *
     * @return the path name of the metadata cache file.
     */
    public String getName() {
        return zipFile.getName();
    }

    /**
     * The comment string used to identify a ZIP file as one of our metadata caches.
     */
    @SuppressWarnings("WeakerAccess")
    public static final String CACHE_FORMAT_IDENTIFIER = "BeatLink Metadata Cache version 1";

    /**
     * The root under which all zip file entries will be created in our cache metadata files.
     */
    private static final String CACHE_PREFIX = "BLTMetaCache/";

    /**
     * The file entry whose content will be the cache format identifier.
     */
    private static final String CACHE_FORMAT_ENTRY = CACHE_PREFIX + "version";

    /**
     * The file entry whose content will be the details about the source media, if available.
     */
    private static final String CACHE_DETAILS_ENTRY = CACHE_PREFIX + "mediaDetails";

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
     * The prefix for cache file entries that will store waveform previews.
     */
    private static final String CACHE_WAVEFORM_DETAIL_ENTRY_PREFIX = CACHE_PREFIX + "waveform/";

    /**
     * Used to mark the end of the metadata items in each cache entry, just like when reading from the server.
     */
    private static final Message MENU_FOOTER_MESSAGE = new Message(0, Message.KnownType.MENU_FOOTER);

    /**
     * Names the appropriate zip file entry for caching a track's metadata.
     *
     * @param rekordboxId the id of the track being cached or looked up
     *
     * @return the name of the entry where that track's metadata should be stored
     */
    static String getMetadataEntryName(int rekordboxId) {
        return CACHE_METADATA_ENTRY_PREFIX + rekordboxId;
    }

    /**
     * Names the appropriate zip file entry for caching album art.
     *
     * @param artworkId the database ID of the artwork being cached or looked up
     *
     * @return the name of entry where that artwork should be stored
     */
    static String getArtworkEntryName(int artworkId) {
        return CACHE_ART_ENTRY_PREFIX + artworkId + ".jpg";
    }

    /**
     * Names the appropriate zip file entry for caching a track's beat grid.
     *
     * @param rekordboxId the id of the track being cached or looked up
     *
     * @return the name of the entry where that track's beat grid should be stored
     */
    static String getBeatGridEntryName(int rekordboxId) {
        return CACHE_BEAT_GRID_ENTRY_PREFIX + rekordboxId;
    }

    /**
     * Names the appropriate zip file entry for caching a track's cue list.
     *
     * @param rekordboxId the id of the track being cached or looked up
     *
     * @return the name of the entry where that track's cue list should be stored
     */
    static String getCueListEntryName(int rekordboxId) {
        return CACHE_CUE_LIST_ENTRY_PREFIX + rekordboxId;
    }

    /**
     * Names the appropriate zip file entry for caching a track's waveform preview.
     *
     * @param rekordboxId the id of the track being cached or looked up
     *
     * @return the name of the entry where that track's waveform preview should be stored
     */
    static String getWaveformPreviewEntryName(int rekordboxId) {
        return CACHE_WAVEFORM_PREVIEW_ENTRY_PREFIX + rekordboxId;
    }

    /**
     * Names the appropriate zip file entry for caching a track's waveform detail.
     *
     * @param rekordboxId the id of the track being cached or looked up
     *
     * @return the name of the entry where that track's waveform detail should be stored
     */
    static String getWaveformDetailEntryName(int rekordboxId) {
        return CACHE_WAVEFORM_DETAIL_ENTRY_PREFIX + rekordboxId;
    }

    /**
     * Finish the process of copying a list of tracks to a metadata cache, once they have been listed. This code
     * is shared between the implementations that work with the full track list and with playlists, and invoked
     * by the {@link MetadataFinder}.
     *
     * @param trackListEntries the list of menu items identifying which tracks need to be copied to the metadata
     *                         cache
     * @param playlistId the id of playlist being cached, or 0 of all tracks are being cached
     * @param client the connection to the dbserver on the player whose metadata is being cached
     * @param slot the slot in which the media to be cached can be found
     * @param cache the file into which the metadata cache should be written
     * @param listener will be informed after each track is added to the cache file being created and offered
     *                 the opportunity to cancel the process
     *
     * @throws IOException if there is a problem communicating with the player or writing the cache file.
     * @throws TimeoutException if we are unable to lock the client for menu operations
     */
    static void copyTracksToCache(List<Message> trackListEntries, int playlistId, Client client, SlotReference slot,
                                  File cache, MetadataCacheCreationListener listener)
            throws IOException, TimeoutException {
        FileOutputStream fos = null;
        BufferedOutputStream bos = null;
        ZipOutputStream zos = null;
        WritableByteChannel channel = null;
        final Set<Integer> tracksAdded = new HashSet<Integer>();
        final Set<Integer> artworkAdded = new HashSet<Integer>();
        try {
            fos = new FileOutputStream(cache);
            bos = new BufferedOutputStream(fos);
            zos = new ZipOutputStream(bos);
            zos.setMethod(ZipOutputStream.DEFLATED);

            addCacheFormatEntry(trackListEntries, playlistId, zos);
            channel = Channels.newChannel(zos);
            addCacheDetailsEntry(slot, zos, channel);

            // Write the actual metadata entries
            final int totalToCopy = trackListEntries.size();
            TrackMetadata lastTrackAdded = null;
            int tracksCopied = 0;

            for (Message entry : trackListEntries) {
                if (entry.getMenuItemType() == Message.MenuItemType.UNKNOWN) {
                    logger.warn("Encountered unrecognized track list entry item type: {}", entry);
                }

                int rekordboxId = (int)((NumberField)entry.arguments.get(1)).getValue();
                if (!tracksAdded.contains(rekordboxId)) {  // Ignore extra copies of a track present on a playlist.
                    lastTrackAdded = copyTrackToCache(client, slot, zos, channel, artworkAdded, rekordboxId);
                    tracksAdded.add(rekordboxId);
                }

                if (listener != null) {
                    if (!listener.cacheCreationContinuing(lastTrackAdded, ++tracksCopied, totalToCopy)) {
                        logger.info("Track metadata cache creation canceled by listener");
                        if (!cache.delete()) {
                            logger.warn("Unable to delete metadata cache file, {}", cache);
                        }
                        return;
                    }
                }

                Thread.sleep(getCachePauseInterval());
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while building metadata cache file, aborting", e);
            if (!cache.delete()) {
                logger.warn("Unable to delete metadata cache file, {}", cache);
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
     * Add a marker so we can recognize this as a metadata archive. I would use the ZipFile comment, but
     * that is not available until Java 7, and Beat Link is supposed to be backwards compatible with Java 6.
     * Since we are doing this anyway, we can also provide information about the nature of the cache, and
     * how many metadata entries it contains, which is useful for auto-attachment.
     *
     * @param trackListEntries the tracks contained in the cache, so we can record the number
     * @param playlistId the playlist contained in the cache, or 0 if it is all tracks from the media
     * @param zos the stream to which the ZipFile is being written
     *
     * @throws IOException if there is a problem creating the format entry
     */
    private static void addCacheFormatEntry(List<Message> trackListEntries, int playlistId, ZipOutputStream zos) throws IOException {
        // Add a marker so we can recognize this as a metadata archive. I would use the ZipFile comment, but
        // that is not available until Java 7, and Beat Link is supposed to be backwards compatible with Java 6.
        // Since we are doing this anyway, we can also provide information about the nature of the cache, and
        // how many metadata entries it contains, which is useful for auto-attachment.
        zos.putNextEntry(new ZipEntry(CACHE_FORMAT_ENTRY));
        String formatEntry = CACHE_FORMAT_IDENTIFIER + ":" + playlistId + ":" + trackListEntries.size();
        zos.write(formatEntry.getBytes("UTF-8"));
    }

    /**
     * Record the details of the media being cached, to make it easier to recognize, now that we have access to that
     * information.
     *
     * @param slot the slot from which a metadata cache is being created
     * @param zos the stream to which the ZipFile is being written
     * @param channel the low-level channel to which the cache is being written
     *
     * @throws IOException if there is a problem writing the media details entry
     */
    private static void addCacheDetailsEntry(SlotReference slot, ZipOutputStream zos, WritableByteChannel channel) throws IOException {
        // Record the details of the media being cached, to make it easier to recognize now that we can.
        MediaDetails details = MetadataFinder.getInstance().getMediaDetailsFor(slot);
        if (details != null) {
            zos.putNextEntry(new ZipEntry(CACHE_DETAILS_ENTRY));
            Util.writeFully(details.getRawBytes(), channel);
        }
    }

    /**
     * Copy a single track's metadata and related objects to a cache file being created.
     *
     * @param client the connection to the database server from which the art can be obtained
     * @param slot the player slot from which the art is being copied
     * @param zos the stream to which the cache is being written
     * @param channel the low-level channel to which the cache is being written
     * @param artworkAdded collects the artwork that has already been added to the cache, to avoid duplicates
     * @param rekordboxId the database ID of the track to be cached
     *
     * @return the track metadata object that was written to the cache, or {@code null} if it could not be found
     *
     * @throws IOException if there is a problem communicating with the player or writing to the cache file
     * @throws InterruptedException if the thread is interrupted while trying to lock the client for menu operations
     * @throws TimeoutException if we are unable to lock the client for menu operations
     */
    private static TrackMetadata copyTrackToCache(Client client, SlotReference slot, ZipOutputStream zos,
                                                  WritableByteChannel channel, Set<Integer> artworkAdded, int rekordboxId)
            throws IOException, TimeoutException, InterruptedException {

        final TrackMetadata track = MetadataFinder.getInstance().queryMetadata(new DataReference(slot, rekordboxId), CdjStatus.TrackType.REKORDBOX, client);
        if (track != null) {
            logger.debug("Adding metadata with ID {}", track.trackReference.rekordboxId);
            zos.putNextEntry(new ZipEntry(getMetadataEntryName(track.trackReference.rekordboxId)));
            for (Message metadataItem : track.rawItems) {
                metadataItem.write(channel);
            }
            MENU_FOOTER_MESSAGE.write(channel);  // So we know to stop reading
        } else {
            logger.warn("Unable to retrieve metadata with ID {}", rekordboxId);
            return null;
        }

        if (track.getArtworkId() != 0 && !artworkAdded.contains(track.getArtworkId())) {
            logger.debug("Adding artwork with ID {}", track.getArtworkId());
            zos.putNextEntry(new ZipEntry(getArtworkEntryName(track.getArtworkId())));
            final AlbumArt art = ArtFinder.getInstance().getArtwork(track.getArtworkId(), slot, CdjStatus.TrackType.REKORDBOX, client);
            if (art != null) {
                Util.writeFully(art.getRawBytes(), channel);
                artworkAdded.add(track.getArtworkId());
            }
        }

        final BeatGrid beatGrid = BeatGridFinder.getInstance().getBeatGrid(rekordboxId, slot, client);
        if (beatGrid != null) {
            logger.debug("Adding beat grid with ID {}", rekordboxId);
            zos.putNextEntry(new ZipEntry(getBeatGridEntryName(rekordboxId)));
            Util.writeFully(beatGrid.getRawData(), channel);
        }

        final CueList cueList = MetadataFinder.getInstance().getCueList(rekordboxId, slot.slot, client);
        if (cueList != null) {
            logger.debug("Adding cue list entry with ID {}", rekordboxId);
            zos.putNextEntry(new ZipEntry((getCueListEntryName(rekordboxId))));
            cueList.rawMessage.write(channel);
        }

        final WaveformPreview preview = WaveformFinder.getInstance().getWaveformPreview(rekordboxId, slot, client);
        if (preview != null) {
            logger.debug("Adding waveform preview entry with ID {}", rekordboxId);
            zos.putNextEntry(new ZipEntry((getWaveformPreviewEntryName(rekordboxId))));
            preview.rawMessage.write(channel);
        }

        final WaveformDetail detail = WaveformFinder.getInstance().getWaveformDetail(rekordboxId, slot, client);
        if (detail != null) {
            logger.debug("Adding waveform detail entry with ID {}", rekordboxId);
            zos.putNextEntry(new ZipEntry((getWaveformDetailEntryName(rekordboxId))));
            detail.rawMessage.write(channel);
        }

        return track;
    }

    /**
     * Find and read the cache format entry in a metadata cache file.
     *
     * @return the content of the format entry, or {@code null} if none was found
     *
     * @throws IOException if there is a problem reading the file
     */
    private String getCacheFormatEntry() throws IOException {
        ZipEntry zipEntry = zipFile.getEntry(CACHE_FORMAT_ENTRY);
        InputStream is = zipFile.getInputStream(zipEntry);
        try {
            Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A");
            String tag = null;
            if (s.hasNext()) tag = s.next();
            return tag;
        } finally {
            is.close();
        }
    }

    /**
     * Retrieves the details about the media from which a metadata cache was created, if available. This will only
     * return a non-{@code null} value for caches created by Beat Link 0.4.1 or later.
     *
     * @return the details about the media from which the cache was created or {@code null} if unknown
     *
     * @throws IOException if there is a problem reading the file
     *
     * @since 0.4.1
     */
    private MediaDetails getCacheMediaDetails() throws IOException {
        ZipEntry zipEntry = zipFile.getEntry(CACHE_DETAILS_ENTRY);
        if (zipEntry == null) {
            return null;  // No details available.
        }
        InputStream is = zipFile.getInputStream(zipEntry);
        try {
            DataInputStream dis = new DataInputStream(is);
            try {
                byte[] detailBytes = new byte[(int)zipEntry.getSize()];
                dis.readFully(detailBytes);
                return new MediaDetails(detailBytes, detailBytes.length);
            } finally {
                dis.close();
            }
        } finally {
            is.close();
        }
    }

    /**
     * Returns a list of the rekordbox IDs of the tracks contained in the cache.
     *
     * @return a list containing the rekordbox ID for each track present in the cache, in the order they appear
     */
    public List<Integer> getTrackIds() {
        ArrayList<Integer> results = new ArrayList<Integer>(trackCount);
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().startsWith(CACHE_METADATA_ENTRY_PREFIX)) {
                String idPart = entry.getName().substring(CACHE_METADATA_ENTRY_PREFIX.length());
                if (idPart.length() > 0) {
                    results.add(Integer.valueOf(idPart));
                }
            }
        }

        return Collections.unmodifiableList(results);
    }

    @Override
    public List<MediaDetails> supportedMedia() {
        if (sourceMedia != null) {
            return Collections.singletonList(sourceMedia);
        }
        return Collections.emptyList();
    }

    @Override
    public TrackMetadata getTrackMetadata(MediaDetails sourceMedia, DataReference track) {
        ZipEntry entry = zipFile.getEntry(getMetadataEntryName(track.rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(zipFile.getInputStream(entry));
                List<Message> items = new LinkedList<Message>();
                Message current = Message.read(is);
                while (current.messageType.getValue() == Message.KnownType.MENU_ITEM.protocolValue) {
                    items.add(current);
                    current = Message.read(is);
                }
                return new TrackMetadata(track, CdjStatus.TrackType.REKORDBOX, items, getCueList(sourceMedia, track.rekordboxId));
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
        return null;    }

    @Override
    public AlbumArt getAlbumArt(@SuppressWarnings("unused") MediaDetails sourceMedia, DataReference art) {
        ZipEntry entry = zipFile.getEntry(getArtworkEntryName(art.rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(zipFile.getInputStream(entry));
                byte[] imageBytes = new byte[(int)entry.getSize()];
                is.readFully(imageBytes);
                return new AlbumArt(art, ByteBuffer.wrap(imageBytes).asReadOnlyBuffer());
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
        return null;    }

    @SuppressWarnings("unused")
    @Override
    public BeatGrid getBeatGrid(MediaDetails sourceMedia, DataReference track) {
        ZipEntry entry = zipFile.getEntry(getBeatGridEntryName(track.rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(zipFile.getInputStream(entry));
                byte[] gridBytes = new byte[(int)entry.getSize()];
                is.readFully(gridBytes);
                return new BeatGrid(track, ByteBuffer.wrap(gridBytes).asReadOnlyBuffer());
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

    @Override
    public CueList getCueList(@SuppressWarnings("unused") MediaDetails sourceMedia, int rekordboxId) {
        ZipEntry entry = zipFile.getEntry(getCueListEntryName(rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(zipFile.getInputStream(entry));
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

    @SuppressWarnings("unused")
    @Override
    public WaveformPreview getWaveformPreview(MediaDetails sourceMedia, DataReference track) {
        ZipEntry entry = zipFile.getEntry(getWaveformPreviewEntryName(track.rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(zipFile.getInputStream(entry));
                Message message = Message.read(is);
                return new WaveformPreview(track, message);
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

    @Override
    public WaveformDetail getWaveformDetail(@SuppressWarnings("unused") MediaDetails sourceMedia, DataReference track) {
        ZipEntry entry = zipFile.getEntry(getWaveformDetailEntryName(track.rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(zipFile.getInputStream(entry));
                Message message = Message.read(is);
                return new WaveformDetail(track, message);
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


    /*
     * Methods for creating metadata caches.
     */

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
    public static void createMetadataCache(SlotReference slot, int playlistId, File cache) throws Exception {
        createMetadataCache(slot, playlistId, cache, null);
    }

    /**
     * How long should we pause between requesting metadata entries while building a cache to give the player
     * a chance to perform its other tasks.
     */
    private static final AtomicLong cachePauseInterval = new AtomicLong(50);

    /**
     * Set how long to pause between requesting metadata entries while building a cache to give the player
     * a chance to perform its other tasks.
     *
     * @param milliseconds the delay to add between each track that gets added to the metadata cache
     */
    public static void setCachePauseInterval(long milliseconds) {
        cachePauseInterval.set(milliseconds);
    }

    /**
     * Check how long we pause between requesting metadata entries while building a cache to give the player
     * a chance to perform its other tasks.
     *
     * @return the delay to add between each track that gets added to the metadata cache
     */
    public static long getCachePauseInterval() {
        return cachePauseInterval.get();
    }

    /**
     * Creates a metadata cache archive file of all tracks in the specified slot on the specified player. Any
     * previous contents of the specified file will be replaced. If a non-{@code null} {@code listener} is
     * supplied, its {@link MetadataCacheCreationListener#cacheCreationContinuing(TrackMetadata, int, int)} method
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
                                    final File cache, final MetadataCacheCreationListener listener)
            throws Exception {
        ConnectionManager.ClientTask<Object> task = new ConnectionManager.ClientTask<Object>() {
            @SuppressWarnings("SameReturnValue")
            @Override
            public Object useClient(Client client) throws Exception {
                final List<Message> trackList;
                if (playlistId == 0) {
                    trackList = MetadataFinder.getInstance().getFullTrackList(slot.slot, client, 0);
                } else {
                    trackList = MetadataFinder.getInstance().getPlaylistItems(slot.slot, 0, playlistId, false, client);
                }
                MetadataCache.copyTracksToCache(trackList, playlistId, client, slot, cache, listener);
                return null;
            }
        };

        if (cache.exists() && !cache.delete()) {
            logger.warn("Unable to delete cache file, {}", cache);
        }
        ConnectionManager.getInstance().invokeWithClientSession(slot.player, task, "building metadata cache");
    }


    /*
     * Methods for auto-attaching metadata caches.
     */

    /**
     * See if there is an auto-attach cache file that seems to match the media in the specified slot, and if so,
     * attach it.
     *
     * @param slot the player slot that is under consideration for automatic cache attachment
     */
    static void tryAutoAttaching(final SlotReference slot) {
        if (!MetadataFinder.getInstance().getMountedMediaSlots().contains(slot)) {
            logger.error("Unable to auto-attach cache to empty slot {}", slot);
            return;
        }
        if (MetadataFinder.getInstance().getMetadataCache(slot) != null) {
            logger.info("Not auto-attaching to slot {}; already has a cache attached.", slot);
            return;
        }
        if (MetadataFinder.getInstance().getAutoAttachCacheFiles().isEmpty()) {
            logger.debug("No auto-attach files configured.");
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(5);  // Give us a chance to find out what type of media is in the new mount.
                    final MediaDetails details = MetadataFinder.getInstance().getMediaDetailsFor(slot);
                    if (details != null && details.mediaType == CdjStatus.TrackType.REKORDBOX) {
                        // First stage attempt: See if we can match based on stored media details, which is both more reliable and
                        // less disruptive than trying to sample the player database to compare entries.
                        boolean attached = false;
                        for (File file : MetadataFinder.getInstance().getAutoAttachCacheFiles()) {
                            final MetadataCache cache = new MetadataCache(file);
                            try {
                                if (cache.sourceMedia != null && cache.sourceMedia.hashKey().equals(details.hashKey())) {
                                    // We found a solid match, no need to probe tracks.
                                    final boolean changed = cache.sourceMedia.hasChanged(details);
                                    logger.info("Auto-attaching metadata cache " + cache.getName() + " to slot " + slot +
                                            " based on media details " + (changed? "(changed since created)!" : "(unchanged)."));
                                    MetadataFinder.getInstance().attachMetadataCacheInternal(slot, cache);
                                    attached = true;
                                    return;
                                }
                            } finally {
                                if (!attached) {
                                    cache.close();
                                }
                            }
                        }

                        // Could not match based on media details; fall back to older method based on probing track metadata.
                        ConnectionManager.ClientTask<Object> task = new ConnectionManager.ClientTask<Object>() {
                            @Override
                            public Object useClient(Client client) throws Exception {
                                tryAutoAttachingWithConnection(slot, client);
                                return null;
                            }
                        };
                        ConnectionManager.getInstance().invokeWithClientSession(slot.player, task, "trying to auto-attach metadata cache");
                    }
                } catch (Exception e) {
                    logger.error("Problem trying to auto-attach metadata cache for slot " + slot, e);
                }

            }
        }, "Metadata cache file auto-attachment attempt").start();
    }

    /**
     * Second stage of the auto-attach process, once we have obtained a connection to the database server for the
     * media slot we are checking our automatic metadata cache files against. Probes the metadata offered by that
     * sever to see if it matches any of the available caches, and if so, attaches that cache file.
     *
     * @param slot identifies the media slot we are checking for automatic cache matches
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @throws IOException if there is a communication problem
     * @throws InterruptedException if the thread is interrupted while trying to lock the client for menu operations
     * @throws TimeoutException if we are unable to lock the client for menu operations
     */
    private static void tryAutoAttachingWithConnection(SlotReference slot, Client client) throws IOException, InterruptedException, TimeoutException {
        // Keeps track of the files we might be able to auto-attach, grouped and sorted by the playlist they
        // were created from, where playlist 0 means all tracks.
        final Map<Integer, LinkedList<MetadataCache>> candidateGroups = gatherCandidateAttachmentGroups();
        MetadataCache match = null;  // We will close any non-matched files from the candidateGroups in our finally clause,
        // but we will leave this one open because we are returning it.
        try {
            // Set up a menu request to process each group.
            for (Map.Entry<Integer,LinkedList<MetadataCache>> entry : candidateGroups.entrySet()) {
                final LinkedList<MetadataCache> candidates;
                ArrayList<Integer> tracksToSample;
                if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                    try {
                        final int playlistId = entry.getKey();
                        candidates = entry.getValue();
                        final long count = getTrackCount(slot.slot, client, playlistId);
                        if (count == Message.NO_MENU_RESULTS_AVAILABLE || count == 0) {
                            // No tracks available to match this set of candidates.
                            for (final MetadataCache candidate : candidates) {
                                candidate.close();
                            }
                            candidates.clear();
                        }

                        // Filter out any candidates with the wrong number of tracks.
                        final Iterator<MetadataCache> candidateIterator = candidates.iterator();
                        while (candidateIterator.hasNext()) {
                            final MetadataCache candidate = candidateIterator.next();
                            if (candidate.trackCount != count) {
                                candidate.close();
                                candidateIterator.remove();
                            }
                        }

                        // Bail before querying any metadata if we can already rule out all the candidates.
                        if (candidates.isEmpty()) {
                            continue;
                        }

                        // Gather as many track IDs as we are configured to sample, up to the number available
                        tracksToSample = chooseTrackSample(slot, client, (int) count);
                    } finally {
                        client.unlockForMenuOperations();
                    }
                } else {
                    throw new TimeoutException("Unable to lock player for menu operations.");
                }

                // Winnow out any auto-attachment candidates that don't match any sampled track
                for (final int trackId : tracksToSample) {
                    logger.info("Comparing track " + trackId + " with " + candidates.size() + " metadata cache file(s).");

                    final DataReference reference = new DataReference(slot, trackId);
                    final TrackMetadata track = MetadataFinder.getInstance().queryMetadata(reference, CdjStatus.TrackType.REKORDBOX, client);
                    if (track == null) {
                        logger.warn("Unable to retrieve metadata when attempting cache auto-attach for slot {}, giving up", slot);
                        return;
                    }

                    for (int i = candidates.size() - 1; i >= 0; --i) {
                        final MetadataCache candidate = candidates.get(i);
                        if (!track.equals(candidate.getTrackMetadata(null, reference))) {
                            candidate.close();
                            candidates.remove(i);
                        }
                    }

                    if (candidates.isEmpty()) {
                        break;  // No point sampling more tracks, we have ruled out all candidates in this group.
                    }
                }

                if (candidates.isEmpty()) {
                    continue;  // This group has failed; move on to the next candidate group, if any.
                }

                match = candidates.get(0);  // We have found at least one matching cache, use the first.
                logger.info("Auto-attaching metadata cache " + match.getName() + " to slot " + slot);
                MetadataFinder.getInstance().attachMetadataCacheInternal(slot, match);
                return;
            }
        } finally {  // No matter how we leave this function, close any of the remaining zip files we are not attaching.
            for (Map.Entry<Integer, LinkedList<MetadataCache>> entry : candidateGroups.entrySet()) {
                for (MetadataCache candidate : entry.getValue()) {
                    if (candidate != match) {
                        candidate.close();
                    }
                }
            }
        }
    }

    /**
     * Groups all of the metadata cache files that are candidates for auto-attachment to player slots into lists
     * that are keyed by the playlist ID used to create the cache file. Files that cache all tracks have a playlist
     * ID of 0.
     *
     * @return a map from playlist ID to the caches holding tracks from that playlist
     */
    private static Map<Integer, LinkedList<MetadataCache>> gatherCandidateAttachmentGroups() {
        Map<Integer,LinkedList<MetadataCache>> candidateGroups = new TreeMap<Integer, LinkedList<MetadataCache>>();
        final Iterator<File> iterator = MetadataFinder.getInstance().getAutoAttachCacheFiles().iterator();
        while (iterator.hasNext()) {
            final File file = iterator.next();
            try {
                final MetadataCache candidate = new MetadataCache(file);
                if (candidateGroups.get(candidate.sourcePlaylist) == null) {
                    candidateGroups.put(candidate.sourcePlaylist, new LinkedList<MetadataCache>());
                }
                candidateGroups.get(candidate.sourcePlaylist).add(candidate);
            } catch (Exception e) {
                logger.error("Unable to open metadata cache file " + file + ", discarding", e);
                iterator.remove();
            }
        }
        return candidateGroups;
    }

    /**
     * Pick a random sample of tracks to compare against the cache, to see if it matches the attached database. We
     * will choose whichever is the smaller of the number configured in {@link MetadataFinder#getAutoAttachProbeCount()} or the
     * total number available in the database or playlist being compared with the cache file.
     *
     * @param slot the player slot in which the database we are comparing to our cache is found
     * @param client the connection to the player for performing database queries to find track IDs
     * @param count the number of tracks available to sample
     *
     * @return the IDs of the tracks we have chosen to compare
     *
     * @throws IOException if there is a problem communicating with the player
     */
    private static ArrayList<Integer> chooseTrackSample(SlotReference slot, Client client, int count) throws IOException {
        int tracksLeft = count;
        int samplesNeeded = Math.min(tracksLeft, MetadataFinder.getInstance().getAutoAttachProbeCount());
        ArrayList<Integer> tracksToSample = new ArrayList<Integer>(samplesNeeded);
        int offset = 0;
        Random random = new Random();
        while (samplesNeeded > 0) {
            int rand = random.nextInt(tracksLeft);
            if (rand < samplesNeeded) {
                --samplesNeeded;
                tracksToSample.add(findTrackIdAtOffset(slot, client, offset));
            }
            --tracksLeft;
            ++offset;
        }
        return tracksToSample;
    }

    /**
     * Find out how many tracks are present in a playlist (or in all tracks, if {@code playlistId} is 0) without
     * actually retrieving all the entries. This is used in checking whether a metadata cache matches what is found
     * in a player slot, and to set up the context for sampling a random set of individual tracks for deeper
     * comparison.
     *
     * @param slot the player slot in which the media is located that we would like to compare
     * @param client the player database connection we can use to perform queries
     * @param playlistId identifies the playlist we want to know about, or 0 of we are interested in all tracks
     * @return the number of tracks found in the player database, which is now ready to enumerate them if a positive
     *         value is returned
     *
     * @throws IOException if there is a problem communicating with the database server
     */
    private static long getTrackCount(CdjStatus.TrackSourceSlot slot, Client client, int playlistId) throws IOException {
        Message response;
        if (playlistId == 0) {  // Form the proper request to render either all tracks or a playlist
            response = client.menuRequest(Message.KnownType.TRACK_MENU_REQ, Message.MenuIdentifier.MAIN_MENU,
                    slot, NumberField.WORD_0);
        }
        else {
            response = client.menuRequest(Message.KnownType.PLAYLIST_REQ, Message.MenuIdentifier.MAIN_MENU, slot,
                    NumberField.WORD_0, new NumberField(playlistId), NumberField.WORD_0);
        }

        return response.getMenuResultsCount();
    }

    /**
     * As part of checking whether a metadata cache can be auto-mounted for a particular media slot, this method
     * looks up the track at the specified offset within the player's track list, and returns its rekordbox ID.
     *
     * @param slot the slot being considered for auto-attaching a metadata cache
     * @param client the connection to the database server on the player holding that slot
     * @param offset an index into the list of all tracks present in the slot
     *
     * @throws IOException if there is a problem communicating with the player
     */
    private static int findTrackIdAtOffset(SlotReference slot, Client client, int offset) throws IOException {
        Message entry = client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slot.slot, CdjStatus.TrackType.REKORDBOX, offset, 1).get(0);
        if (entry.getMenuItemType() == Message.MenuItemType.UNKNOWN) {
            logger.warn("Encountered unrecognized track list entry item type: {}", entry);
        }
        return (int)((NumberField)entry.arguments.get(1)).getValue();
    }


}
