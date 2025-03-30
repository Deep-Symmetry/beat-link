package org.deepsymmetry.beatlink.data;

import io.kaitai.struct.RandomAccessFileKaitaiStream;
import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.cratedigger.Database;
import org.deepsymmetry.cratedigger.FileFetcher;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.deepsymmetry.cratedigger.pdb.RekordboxPdb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>Uses the <a href="https://github.com/Deep-Symmetry/crate-digger#crate-digger">Crate Digger</a> library to
 * provide an even more reliable source of track metadata, even when there are four players in use on the network
 * and the {@link org.deepsymmetry.beatlink.VirtualCdj} is forced to use a non-standard player number.</p>
 *
 * <p>To take advantage of these capabilities, simply call the {@link #start()} method and then use the
 * {@link MetadataFinder} as you would have without this class.</p>
 *
 * @author James Elliott
 * @since 0.5.0
 */
@API(status = API.Status.STABLE)
public class CrateDigger {

    private final Logger logger = LoggerFactory.getLogger(CrateDigger.class);

    /**
     * How many times we will try to download a file from a player before giving up.
     */
    private final AtomicInteger retryLimit = new AtomicInteger(3);

    /**
     * Check how many times we will try to download a file from a player before giving up.
     *
     * @return the maximum number of attempts we will make when a file download fails
     */
    @API(status = API.Status.STABLE)
    public int getRetryLimit() {
        return retryLimit.get();
    }

    /**
     * Set how many times we will try to download a file from a player before giving up.
     *
     * @param limit the maximum number of attempts we will make when a file download fails
     */
    @API(status = API.Status.STABLE)
    public void setRetryLimit(int limit) {
        if (limit < 1 || limit > 10) {
            throw new IllegalArgumentException("limit must be between 1 and 10");
        }
        retryLimit.set(limit);
    }

    /**
     * Keep track of whether we are running.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Check whether we are currently running.
     *
     * @return true if track metadata is being fetched using NFS for all active players
     */
    @API(status = API.Status.STABLE)
    public boolean isRunning() {
        return running.get();
    }


    /**
     * Keeps tracks of the media that we have discovered name their database folder ".PIONEER" rather than "PIONEER".
     */
    private final Set<SlotReference> mediaWithHiddenPioneerFolder = new HashSet<>();

    /**
     * Keeps track of the DeviceAnnouncement packets associated with media we hear about, so that if the device
     * has already disappeared from the network when we learn about the unmount (caused by the loss of the device),
     * we can still know what address it used to have, in order to properly clean up the {@link FileFetcher} cache.
     */
    private final Map<Integer, DeviceAnnouncement> addressBackup = new ConcurrentHashMap<>();

    /**
     * Keep track of the slots we are currently trying to fetch local databases for, so we only do it once even if
     * multiple media details responses arrive while we are working on the first.
     */
    private final Set<SlotReference> activeRequests = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Holds the local databases we have fetched for mounted media, so we can use them to respond to metadata requests.
     */
    private final Map<SlotReference, Database> databases = new ConcurrentHashMap<>();

    /**
     * Return the filesystem path needed to mount the NFS filesystem associated with a particular media slot.
     *
     * @param slot the slot whose filesystem is desired
     *
     * @return the path to use in the NFS mount request to access the files mounted in that slot
     *
     * @throws IllegalArgumentException if it is a slot that we don't know how to handle
     */
    private String mountPath(CdjStatus.TrackSourceSlot slot) {
        switch (slot) {
            case SD_SLOT: return "/B/";
            case USB_SLOT: return "/C/";
        }
        throw new IllegalArgumentException("Don't know how to NFS mount filesystem for slot " + slot);
    }

    /**
     * How long we should back off, in milliseconds, before retrying after each failure to get a file.
     */
    private static final long RETRY_BACKOFF = 2000;

    /**
     * The maximum amount of time we should wait between retry attempts, in milliseconds.
     */
    private static final long MAX_RETRY_INTERVAL = 6000;

    /**
     * Helper method to call the {@link FileFetcher} with the right arguments to get a file for a particular slot. Also
     * arranges for the file to be deleted when we are shutting down in case we fail to clean it up ourselves. Will
     * retry up to the number of tries configured in {@link #retryLimit}.
     *
     * @param slot the slot from which a file is desired
     * @param path the path to the file within the slot's mounted filesystem
     * @param destination where to write the file contents
     *
     * @throws IOException if there is a problem fetching the file
     */
    private void fetchFile(SlotReference slot, String path, File destination) throws IOException {
        fetchFile(slot, path, destination, getRetryLimit());
    }

    /**
     * Helper method to call the {@link FileFetcher} with the right arguments to get a file for a particular slot. Also
     * arranges for the file to be deleted when we are shutting down in case we fail to clean it up ourselves. Will
     * retry up to the specific number of times passed in the {@code retryLimit} argument.
     *
     * @param slot the slot from which a file is desired
     * @param path the path to the file within the slot's mounted filesystem
     * @param destination where to write the file contents
     * @param retryLimit the maximum number of tries that will be made to read the file
     *
     * @throws IOException if there is a problem fetching the file
     */
    private void fetchFile(SlotReference slot, String path, File destination, int retryLimit) throws IOException {
        final DeviceAnnouncement player = DeviceFinder.getInstance().getLatestAnnouncementFrom(slot.player);
        if (player == null) {
            throw new IOException("Cannot fetch file from player that is not found on the network; slot: " + slot);
        }
        int triesMade = 0;
        if (path.startsWith("PIONEER/") && mediaWithHiddenPioneerFolder.contains(slot)) {
            path = "." + path;  // We are dealing with HFS+ media, so skip the first, failed attempt to read it.
        }
        while (triesMade < retryLimit) {
            try {
                FileFetcher.getInstance().fetch(player.getAddress(), mountPath(slot.slot), path, destination);
                destination.deleteOnExit();
                return;
            } catch (IOException e) {
                if (path.startsWith("PIONEER/") &&
                        e.getMessage().contains("lookup of element \"PIONEER\" returned status")) {
                    // Workaround for the fact that HFS+ formatted devices hide their PIONEER directory as a dot-file.
                    mediaWithHiddenPioneerFolder.add(slot);  // Skip the initial failed attempt next time we access it.
                    fetchFile(slot, "." + path, destination);
                    return;
                }
                triesMade++;
                if (triesMade < retryLimit) {
                    logger.warn("Attempt to fetch file {} from {} to {} failed, tries left: {}", path, slot, destination, retryLimit - triesMade, e);
                    try {
                        //noinspection BusyWait
                        Thread.sleep(Math.min(MAX_RETRY_INTERVAL, triesMade * RETRY_BACKOFF));
                    } catch (InterruptedException ie) {
                        logger.warn("Interrupted while sleeping between file fetch attempts. Retrying immediately.");
                    }
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Format the filename prefix that will be used to store files downloaded from a particular player slot.
     * This allows them all to be cleaned up when that slot is unmounted or the player goes away.
     *
     * @param slotReference the slot from which files are being downloaded
     *
     * @return the prefix with which the names of all files downloaded from that slot will start
     */
    private String slotPrefix(SlotReference slotReference) {
        return "player-" + slotReference.player + "-slot-" + slotReference.slot.protocolValue + "-";
    }

    /**
     * Format a number of bytes in a human-centric format.
     * From <a href="https://stackoverflow.com/a/3758880/802383">Stack Overflow</a>
     *
     * @param bytes the number of bytes
     * @param si {code @true} if we should use SI interpretation where k=1000
     * @return the nicely readable summary string
     */
    @API(status = API.Status.STABLE)
    public static String humanReadableByteCount(long bytes, boolean si) {
        int unit = si ? 1000 : 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        @SuppressWarnings("SpellCheckingInspection") String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp-1) + (si ? "" : "i");
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }

    /**
     * Whenever we learn media details about a newly-mounted media slot, if it is rekordbox media, start the process
     * of fetching and parsing the database, so we can offer metadata for that slot.
     */
    private final MediaDetailsListener mediaDetailsListener = new MediaDetailsListener() {
        @Override
        public void detailsAvailable(final MediaDetails details) {
            if (isRunning() && details.mediaType == CdjStatus.TrackType.REKORDBOX &&
                    !VirtualRekordbox.getInstance().isRunning() &&  // If we are dealing with an Opus Quad, we can’t download files.
                    details.slotReference.slot != CdjStatus.TrackSourceSlot.COLLECTION &&  // We always use dbserver to talk to rekordbox.
                    !databases.containsKey(details.slotReference) &&
                    activeRequests.add(details.slotReference)) {
                new Thread(() -> {
                    File file = null;
                    try {
                        file = new File(downloadDirectory, slotPrefix(details.slotReference) + "export.pdb");
                        logger.info("Fetching rekordbox export.pdb from player {}, slot {}",
                                details.slotReference.player, details.slotReference.slot);
                        long started = System.nanoTime();
                        fetchFile(details.slotReference, "PIONEER/rekordbox/export.pdb", file);
                        long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                        logger.info("Finished fetching export.pdb from player {}, slot {}; received {} in {}ms, {}/s.",
                                details.slotReference.player, details.slotReference.slot,
                                humanReadableByteCount(file.length(), true), duration,
                                humanReadableByteCount(file.length() * 1000 / duration, true));
                        started = System.nanoTime();
                        final Database database = new Database(file);
                        duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                        logger.info("Parsing database took {}ms, {} tracks/s", duration, database.trackIndex.size() * 1000L / duration);
                        databases.put(details.slotReference, database);
                        deliverDatabaseUpdate(details.slotReference, database, true);
                    } catch (Throwable t) {
                        logger.error("Problem fetching rekordbox database for media {}, will not offer metadata for it.", details, t);
                        if (file != null) {
                            //noinspection ResultOfMethodCallIgnored
                            file.delete();
                        }
                    } finally {
                        activeRequests.remove(details.slotReference);
                    }
                }).start();
            }
        }
    };

    /**
     * Find the database we have downloaded and parsed that can provide information about the supplied data
     * reference, if any.
     *
     * @param reference identifies the location from which data is desired
     *
     * @return the appropriate rekordbox extract to start from in finding that data, if we have one
     */
    @API(status = API.Status.STABLE)
    public Database findDatabase(DataReference reference) {
        return databases.get(reference.getSlotReference());
    }

    /**
     * Find the database we have downloaded and parsed that can provide information about the supplied slot
     * reference, if any.
     *
     * @param slot identifies the slot from which data is desired
     *
     * @return the appropriate rekordbox extract to start from in finding that data, if we have one
     */
    @API(status = API.Status.STABLE)
    public Database findDatabase(SlotReference slot) {
        return databases.get(slot);
    }

    /**
     * Find the analysis file for the specified track, downloading it from the player if we have not already done so.
     * Be sure to call {@code _io().close()} when you are done using the returned struct.
     *
     * @param track the track whose analysis file is desired
     * @param database the parsed database export from which the analysis path can be determined
     *
     * @return the parsed file containing the track analysis
     */
    private RekordboxAnlz findTrackAnalysis(DataReference track, Database database) {
        return findTrackAnalysis(track, database, ".DAT");
    }

    /**
     * Find the extended analysis file for the specified track, downloading it from the player if we have not already
     * done so. Be sure to call {@code _io().close()} when you are done using the returned struct.
     *
     * @param track the track whose extended analysis file is desired
     * @param database the parsed database export from which the analysis path can be determined
     *
     * @return the parsed file containing the track analysis
     */
    private RekordboxAnlz findExtendedAnalysis(DataReference track, Database database) {
        return findTrackAnalysis(track, database, ".EXT");
    }

    /**
     * Find an analysis file for the specified track, with the specified file extension, downloading it from the player
     * if we have not already done so. Be sure to call {@code _io().close()} when you are done using the returned struct.
     *
     * @param track the track whose extended analysis file is desired
     * @param database the parsed database export from which the analysis path can be determined
     * @param extension the file extension (such as ".DAT" or ".EXT") which identifies the type file to be retrieved
     *
     * @return the parsed file containing the track analysis
     */
    private RekordboxAnlz findTrackAnalysis(DataReference track, Database database, String extension) {
        File file = null;
        try {
            final RekordboxPdb.TrackRow trackRow = database.trackIndex.get((long) track.rekordboxId);
            if (trackRow != null) {
                file = new File(downloadDirectory, slotPrefix(track.getSlotReference()) +
                        "track-" + track.rekordboxId + "-anlz" + extension.toLowerCase());
                final String filePath = file.getCanonicalPath();
                final String analyzePath = Database.getText(trackRow.analyzePath());
                final String requestedPath = analyzePath.replaceAll("\\.DAT$", extension.toUpperCase());
                try {
                    synchronized (Util.allocateNamedLock(filePath)) {
                        if (file.canRead()) {  // We have already downloaded it.
                            return new RekordboxAnlz(new RandomAccessFileKaitaiStream(filePath));
                        }
                        // Prepare to download it.
                        file.deleteOnExit();
                        fetchFile(track.getSlotReference(), requestedPath, file);
                        return new RekordboxAnlz(new RandomAccessFileKaitaiStream(filePath));
                    }
                } catch (Exception e) {  // We can give a more specific error including the file path.
                    logger.error("Problem parsing requested analysis file {} for track {} from database {}", requestedPath, track, database, e);
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                } finally {
                    Util.freeNamedLock(filePath);
                }
            } else {
                logger.warn("Unable to find track {} in database {}", track, database);
            }
        } catch (Exception e) {
            logger.error("Problem fetching analysis file with extension {} for track {} from database {}", extension.toUpperCase(), track, database, e);
            if (file != null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        return null;
    }

    /**
     * This is the mechanism by which we offer metadata to the {@link MetadataProvider} while we are running.
     */
    private final MetadataProvider metadataProvider = new MetadataProvider() {
        @Override
        public List<MediaDetails> supportedMedia() {
            return null;  // We can potentially answer any query.
        }

        @Override
        public TrackMetadata getTrackMetadata(MediaDetails sourceMedia, DataReference track) {
            final Database database = findDatabase(track);
            if (database != null) {
                try {
                    return new TrackMetadata(track, database, getCueList(sourceMedia, track));
                } catch (Exception e) {
                    logger.error("Problem fetching metadata for track {} from database {}", track, database, e);
                }
            }
            return null;
        }

        @Override
        public AlbumArt getAlbumArt(MediaDetails sourceMedia, DataReference art) {
            File file = null;
            final Database database = findDatabase(art);
            if (database != null) {
                try {
                    RekordboxPdb.ArtworkRow artworkRow = database.artworkIndex.get((long) art.rekordboxId);
                    if (artworkRow != null) {
                        file = new File(downloadDirectory, slotPrefix(art.getSlotReference()) +
                                "art-" + art.rekordboxId + ".jpg");
                        if (file.canRead()) {
                            return new AlbumArt(art, file);
                        }
                        file.deleteOnExit();  // Prepare to download it.
                        if (ArtFinder.getInstance().getRequestHighResolutionArt()) {
                            try {
                                fetchFile(art.getSlotReference(), Util.highResolutionPath(Database.getText(artworkRow.path())), file, 1);
                            } catch (IOException e) {
                                if (!(e instanceof FileNotFoundException)) {
                                    logger.error("Unexpected exception type trying to load high resolution album art", e);
                                }
                                // Fall back to looking for the normal resolution art.
                                fetchFile(art.getSlotReference(), Database.getText(artworkRow.path()), file);
                            }
                        } else {
                            fetchFile(art.getSlotReference(), Database.getText(artworkRow.path()), file);
                        }
                        return new AlbumArt(art, file);
                    } else {
                        logger.warn("Unable to find artwork {} in database {}", art, database);
                    }
                } catch (Exception e) {
                    logger.warn("Problem fetching artwork {} from database {}", art, database, e);
                    if (file != null) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                }
            }
            return null;
        }

        @Override
        public BeatGrid getBeatGrid(MediaDetails sourceMedia, DataReference track) {
            final Database database = findDatabase(track);
            if (database != null) {
                try {
                    final RekordboxAnlz file = findTrackAnalysis(track, database);
                    if (file != null) {
                        try {
                            return new BeatGrid(track, file);
                        } finally {
                            file._io().close();
                        }
                    }
                } catch (Exception e) {
                    logger.error("Problem fetching beat grid for track {} from database {}", track, database, e);
                }
            }
            return null;
        }

        @Override
        public CueList getCueList(MediaDetails sourceMedia, DataReference track) {
            final Database database = findDatabase(track);
            if (database != null) {
                try {
                    // Try the extended file first, because it can contain both nxs2-style commented cues and basic cues
                    RekordboxAnlz file = findExtendedAnalysis(track, database);
                    if (file ==  null) {  // No extended analysis found, fall back to the basic one
                        file = findTrackAnalysis(track, database);
                    }
                    if (file != null) {
                        try {
                            return new CueList(file);
                        } finally {
                            file._io().close();
                        }
                    }
                } catch (Exception e) {
                    logger.error("Problem fetching cue list for track {} from database {}", track, database, e);
                }
            }
            return null;
        }

        @Override
        public WaveformPreview getWaveformPreview(MediaDetails sourceMedia, DataReference track) {
            final Database database = findDatabase(track);
            if (database != null) {

                // Attempt 3-band preview if so configured first
                if (WaveformFinder.getInstance().getPreferredStyle() == WaveformFinder.WaveformStyle.THREE_BAND) {
                    try {
                        final RekordboxAnlz file = findTrackAnalysis(track, database, ".2EX");
                        if (file != null) {
                            try {
                                return new WaveformPreview(track, file);
                            } finally {
                                file._io().close();
                            }
                        }
                    } catch (IllegalStateException e) {
                        logger.info("No 3-band preview waveform found, falling back to color or blue version.");
                    } catch (Exception e) {
                        logger.error("Problem fetching 3-band waveform preview for track {} from database {}", track, database, e);
                    }
                }

                // Attempt color preview if so configured next
                if (WaveformFinder.getInstance().getPreferredStyle() == WaveformFinder.WaveformStyle.RGB) {
                    try {
                        final RekordboxAnlz file = findExtendedAnalysis(track, database);
                        if (file != null) {
                            try {
                                return new WaveformPreview(track, file);
                            } finally {
                                file._io().close();
                            }
                        }
                    } catch (IllegalStateException e) {
                        logger.info("No color preview waveform found, checking for blue version.");
                    } catch (Exception e) {
                        logger.error("Problem fetching color waveform preview for track {} from database {}", track, database, e);
                    }
                }

                // Final fallback option, the old blue-and white waveforms.
                try {
                    final RekordboxAnlz file = findTrackAnalysis(track, database);
                    if (file != null) {
                        try {
                            return new WaveformPreview(track, file);
                        } finally {
                            file._io().close();
                        }
                    }
                } catch (Exception e) {
                    logger.error("Problem fetching waveform preview for track {} from database {}", track, database, e);
                }
            }
            return null;
        }

        @Override
        public WaveformDetail getWaveformDetail(MediaDetails sourceMedia, DataReference track) {
            final Database database = findDatabase(track);
            if (database != null) {

                // Attempt 3-band waveform detail if so configured first
                if (WaveformFinder.getInstance().getPreferredStyle() == WaveformFinder.WaveformStyle.THREE_BAND) {
                    try {
                        final RekordboxAnlz file = findTrackAnalysis(track, database, ".2EX");
                        if (file != null) {
                            try {
                                return new WaveformDetail(track, file);
                            } finally {
                                file._io().close();
                            }
                        }
                    } catch (IllegalStateException e) {
                        logger.info("No 3-band detail waveform found, falling back to color or blue version.");
                    } catch (Exception e) {
                        logger.error("Problem fetching waveform detail for track {} from database {}", track, database, e);
                    }
                }

                // Fall back to color or blue/white waveform detail
                try {
                    final RekordboxAnlz file = findExtendedAnalysis(track, database);
                    if (file != null) {
                        try {
                            return new WaveformDetail(track, file);
                        } finally {
                            file._io().close();
                        }
                    }
                } catch (Exception e) {
                    logger.error("Problem fetching waveform preview for track {} from database {}", track, database, e);
                }
            }
            return null;
        }

        @Override
        public RekordboxAnlz.TaggedSection getAnalysisSection(MediaDetails sourceMedia, DataReference track, String fileExtension, String typeTag) {
            final Database database = findDatabase(track);
            if (database != null) {
                try {
                    if ((typeTag.length()) > 4) {
                        throw new IllegalArgumentException("typeTag cannot be longer than four characters");
                    }

                    int fourcc = 0;  // Convert the type tag to an integer as found in the analysis file.
                    for (int i = 0; i < 4; i++) {
                        fourcc = fourcc * 256;
                        if (i < (typeTag.length())) {
                            fourcc = fourcc + typeTag.charAt(i);
                        }
                    }

                    final RekordboxAnlz file = findTrackAnalysis(track, database, fileExtension);  // Open the desired file to scan.
                    if (file != null) {
                        try {  // Scan for the requested tag type.
                            for (RekordboxAnlz.TaggedSection section : file.sections()) {
                                if (section.fourcc() == RekordboxAnlz.SectionTags.byId(fourcc)) {
                                    return section;
                                }
                            }
                        } finally {
                            file._io().close();
                        }
                    }
                } catch (Exception e) {
                    logger.error("Problem fetching analysis file {} section {} for track {} from database {}", fileExtension, typeTag, track, database, e);
                }
            }
            return null;
        }
    };

    /**
     * Start finding track metadata for all active players using the NFS server on the players to pull the exported
     * database and track analysis files. Starts the {@link MetadataFinder} if it is not already
     * running, because we build on its features. This will transitively start many of the other Beat Link subsystems,
     * and stopping any of them will stop us as well.
     *
     * @throws Exception if there is a problem starting the required components
     */
    @API(status = API.Status.STABLE)
    public synchronized void start() throws Exception {
        if (!isRunning()) {
            MetadataFinder.getInstance().start();
            running.set(true);
            // Try fetching the databases of anything that was already mounted before we started.
            for (MediaDetails details : MetadataFinder.getInstance().getMountedMediaDetails()) {
                mediaDetailsListener.detailsAvailable(details);
            }
            MetadataFinder.getInstance().addMetadataProvider(metadataProvider);
        }
    }

    /**
     * Stop finding track metadata for all active players.
     */
    @API(status = API.Status.STABLE)
    public synchronized void stop() {
        if (isRunning()) {
            running.set(false);
            MetadataFinder.getInstance().removeMetadataProvider(metadataProvider);
            for (Database database : databases.values()) {
                //noinspection ResultOfMethodCallIgnored
                database.sourceFile.delete();
            }
            databases.clear();
        }
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final CrateDigger instance = new CrateDigger();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    @API(status = API.Status.STABLE)
    public static CrateDigger getInstance() {
        return instance;
    }

    /**
     * The folder into which database exports and track analysis files will be downloaded.
     */
    @API(status = API.Status.STABLE)
    public final File downloadDirectory;

    /**
     * The number of variations on the file name we will attempt when creating our temporary directory.
     */
    private static final int TEMP_DIR_ATTEMPTS = 1000;

    /**
     * Create the directory into which we can download (and reuse) database exports and track analysis files.
     *
     * @return the created temporary directory.
     */
    static File createDownloadDirectory() {
        File baseDir = new File(System.getProperty("java.io.tmpdir"));
        String baseName = "bl-" + System.currentTimeMillis() + "-";

        for (int counter = 0; counter < TEMP_DIR_ATTEMPTS; counter++) {
            File tempDir = new File(baseDir, baseName + counter);
            if (tempDir.mkdir()) {
                return tempDir;
            }
        }
        throw new IllegalStateException("Failed to create download directory within " + TEMP_DIR_ATTEMPTS + " attempts.");
    }

    /**
     * Keeps track of the registered database listeners.
     */
    private final Set<DatabaseListener> dbListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Adds the specified database listener to receive updates when a rekordbox database has been obtained for a
     * media slot, or when the underlying media for a database has been unmounted, so it is no longer relevant.
     * If {@code listener} is {@code null} or already present in the set of registered listeners, no exception is
     * thrown and no action is performed.
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
     * @param listener the database update listener to add
     */
    @API(status = API.Status.STABLE)
    public void addDatabaseListener(DatabaseListener listener) {
        if (listener != null) {
            dbListeners.add(listener);
        }
    }

    /**
     * Removes the specified database listener so that it no longer receives updates when there
     * are changes to the available set of rekordbox databases. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the database update listener to remove
     */
    @API(status = API.Status.STABLE)
    public void removeDatabaseListener(DatabaseListener listener) {
        if (listener != null) {
            dbListeners.remove(listener);
        }
    }

    @API(status = API.Status.STABLE)
    public Set<DatabaseListener> getDatabaseListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Set.copyOf(dbListeners);
    }

    /**
     * Send a database announcement to all registered listeners.
     *
     * @param slot the media slot whose database availability has changed
     * @param database the database whose relevance has changed
     * @param available if {@code} true, the database is newly available, otherwise it is no longer relevant
     */
    private void deliverDatabaseUpdate(SlotReference slot, Database database, boolean available) {
        for (final DatabaseListener listener : getDatabaseListeners()) {
            try {
                if (available) {
                    listener.databaseMounted(slot, database);
                } else {
                    listener.databaseUnmounted(slot, database);
                }
            } catch (Throwable t) {
                logger.warn("Problem delivering rekordbox database availability update to listener", t);
            }
        }
    }


    /**
     * Prevent direct instantiation, create a temporary directory for our file downloads,
     * and register the listeners that hook us into the streams of information we need.
     */
    private CrateDigger() {
        final LifecycleListener lifecycleListener = new LifecycleListener() {
            // Allows us to automatically shut down when the MetadataFinder, which we depend on, does, and start back up once it resumes.
            @Override
            public void started(final LifecycleParticipant sender) {
                new Thread(() -> {
                    try {
                        logger.info("CrateDigger starting because {} has.", sender);
                        start();
                    } catch (Throwable t) {
                        logger.error("Problem starting the CrateDigger in response to a lifecycle event.", t);
                    }
                }).start();
            }

            @Override
            public void stopped(final LifecycleParticipant sender) {
                if (isRunning()) {
                    logger.info("CrateDigger stopping because {} has.", sender);
                    stop();
                }
            }
        };
        MetadataFinder.getInstance().addLifecycleListener(lifecycleListener);
        final MountListener mountListener = new MountListener() {
            @Override
            public void mediaMounted(SlotReference slot) {
                // Record information about the device owning the slot, in case it disappears before we learn we need to
                // unmount its media.
                final DeviceAnnouncement player = DeviceFinder.getInstance().getLatestAnnouncementFrom(slot.player);
                addressBackup.put(slot.player, player);

                // Nothing else to do here yet, we need to wait until the media details are available.
                logger.debug("Media mounted, waiting for details.");
            }

            @Override
            public void mediaUnmounted(SlotReference slot) {
                // Clear the FileFetcher cache when media is unmounted, so it does not try to use
                // stale filesystem handles. Also clear up our own caches for the vanished media,
                // and close the files associated with the parsed database structures.
                mediaWithHiddenPioneerFolder.remove(slot);
                DeviceAnnouncement player = DeviceFinder.getInstance().getLatestAnnouncementFrom(slot.player);
                if (player == null) {
                    logger.info("Received an unmount for a player we can't find, it must have left network.");
                    player = addressBackup.get(slot.player);
                }
                if (player != null) {
                    FileFetcher.getInstance().removePlayer(player.getAddress());
                } else {
                    logger.warn("Unable to clear FileFetcher connections for player that we can't find backup address for!");
                }

                final Database database = databases.remove(slot);
                if (database != null) {
                    deliverDatabaseUpdate(slot, database, false);
                    try {
                        database.close();
                    } catch (IOException e) {
                        logger.error("Problem closing parsed rekordbox database export.", e);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    database.sourceFile.delete();
                }
                final String prefix = slotPrefix(slot);
                File[] files = downloadDirectory.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.getName().startsWith(prefix)) {
                            //noinspection ResultOfMethodCallIgnored
                            file.delete();
                        }
                    }
                }
            }
        };
        MetadataFinder.getInstance().addMountListener(mountListener);
        final DeviceAnnouncementListener deviceListener = new DeviceAnnouncementAdapter() {
            @Override
            public void deviceLost(DeviceAnnouncement announcement) {
                // Clear the FileFetcher cache when players disappear, so it does not try
                // to use stale filesystem handles. Our own caches will be cleaned up by mountListener.
                FileFetcher.getInstance().removePlayer(announcement.getAddress());
            }
        };
        DeviceFinder.getInstance().addDeviceAnnouncementListener(deviceListener);
        VirtualCdj.getInstance().addMediaDetailsListener(mediaDetailsListener);
        downloadDirectory = createDownloadDirectory();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("CrateDigger[").append("running: ").append(isRunning());
        if (isRunning()) {
            sb.append(", databases mounted: ").append(databases.size());
            sb.append(", download directory: ").append(downloadDirectory.getAbsolutePath());
            sb.append(", media using hidden PIONEER folder: ").append(mediaWithHiddenPioneerFolder);
        }
        return sb.append("]").toString();
    }
}
