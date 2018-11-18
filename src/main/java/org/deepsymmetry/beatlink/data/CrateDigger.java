package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.cratedigger.Database;
import org.deepsymmetry.cratedigger.FileFetcher;
import org.deepsymmetry.cratedigger.pdb.AnlzFile;
import org.deepsymmetry.cratedigger.pdb.PdbFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
public class CrateDigger {

    private final Logger logger = LoggerFactory.getLogger(CrateDigger.class);

    /**
     * Keep track of whether we are running.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Check whether we are currently running.
     *
     * @return true if track metadata is being fetched using NFS for all active players
     */
    @SuppressWarnings("WeakerAccess")
    public boolean isRunning() {
        return running.get();
    }


    /**
     * Allows us to automatically shut down when the {@link MetadataFinder}, which we depend on, does, and start
     * back up once it resumes.
     */
    @SuppressWarnings("FieldCanBeLocal")
    private final LifecycleListener lifecycleListener = new LifecycleListener() {
        @Override
        public void started(final LifecycleParticipant sender) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        logger.info("CrateDigger starting because {} has.", sender);
                        start();
                    } catch (Throwable t) {
                        logger.error("Problem starting the CrateDigger in response to a lifecycle event.", t);
                    }
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

    /**
     * Clear the {@link org.deepsymmetry.cratedigger.FileFetcher} cache when media is unmounted, so it does not try
     * to use stale filesystem handles. Also clear up our own caches for the vanished media.
     */
    private final MountListener mountListener = new MountListener() {
        @Override
        public void mediaMounted(SlotReference slot) {
            // Nothing for us to do here yet, we need to wait until the media details are available.
        }

        @Override
        public void mediaUnmounted(SlotReference slot) {
            DeviceAnnouncement player = DeviceFinder.getInstance().getLatestAnnouncementFrom(slot.player);
            if (player != null) {
                FileFetcher.getInstance().removePlayer(player.getAddress());
                final Database database = databases.remove(slot);
                if (database != null) {
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
            } else {
                logger.info("Ignoring unmount from player that we can't find, must have left network.");
            }
        }
    };

    /**
     * Clear the {@link org.deepsymmetry.cratedigger.FileFetcher} cache when players disappear, so it does not try
     * to use stale filesystem handles. Our own caches will be cleaned up by {@link #mountListener}.
     */
    @SuppressWarnings("FieldCanBeLocal")
    private final DeviceAnnouncementListener deviceListener = new DeviceAnnouncementAdapter() {
        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            FileFetcher.getInstance().removePlayer(announcement.getAddress());
        }
    };

    /**
     * Keep track of the slots we are currently trying to fetch local databases for, so we only do it once even if
     * multiple media details responses arrive while we are working on the first.
     */
    private final Set<SlotReference> activeRequests = Collections.newSetFromMap(new ConcurrentHashMap<SlotReference, Boolean>());

    /**
     * Holds the local databases we have fetched for mounted media, so we can use them to respond to metadata requests.
     */
    private final Map<SlotReference, Database> databases = new ConcurrentHashMap<SlotReference, Database>();

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
     * Helper method to call the {@link FileFetcher} with the right arguments to get a file for a particular slot. Also
     * arranges for the file to be deleted when we are shutting down in case we fail to clean it up ourselves.
     *
     * @param slot the slot from which a file is desired
     * @param path the path to the file within the slot's mounted filesystem
     * @param destination where to write the file contents
     *
     * @throws IOException if there is a problem fetching the file
     */
    private void fetchFile(SlotReference slot, String path, File destination) throws IOException {
        destination.deleteOnExit();
        final DeviceAnnouncement player = DeviceFinder.getInstance().getLatestAnnouncementFrom(slot.player);
        if (player == null) {
            throw new IOException("Cannot fetch file from player that is not found on the network; slot: " + slot);
        }
        FileFetcher.getInstance().fetch(player.getAddress(), mountPath(slot.slot), path, destination);
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
     * Whenever we learn media details about a newly-mounted media slot, if it is rekordbox media, start the process
     * of fetching and parsing the database so we can offer metadata for that slot.
     */
    private final MediaDetailsListener mediaDetailsListener = new MediaDetailsListener() {
        @Override
        public void detailsAvailable(final MediaDetails details) {
            if (isRunning() && details.mediaType == CdjStatus.TrackType.REKORDBOX &&
                    !databases.containsKey(details.slotReference) &&
                    activeRequests.add(details.slotReference)) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        File file = null;
                        try {
                            file = new File(downloadDirectory, slotPrefix(details.slotReference) + "export.pdb");
                            fetchFile(details.slotReference, "PIONEER/rekordbox/export.pdb", file);
                            databases.put(details.slotReference, new Database(file));
                        } catch (Throwable t) {
                            logger.error("Problem fetching rekordbox database for media " + details +
                                    ", will not offer metadata for it.", t);
                            if (file != null) {
                                //noinspection ResultOfMethodCallIgnored
                                file.delete();
                            }
                        } finally {
                            activeRequests.remove(details.slotReference);
                        }
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
    private Database findDatabase(DataReference reference) {
        return databases.get(reference.getSlotReference());
    }

    /**
     * Find the analysis file for the specified track, downloading it from the player if we have not already done so.
     *
     * @param track the track whose analysis file is desired
     * @param database the parsed database export from which the analysis path can be determined
     *
     * @return the file containing the track analysis
     */
    private AnlzFile findTrackAnalysis(DataReference track, Database database) {
        File file = null;
        try {
            PdbFile.TrackRow trackRow = database.trackIndex.get((long) track.rekordboxId);
            if (trackRow != null) {
                file = new File(downloadDirectory, slotPrefix(track.getSlotReference()) +
                        "track-" + track.rekordboxId + "-anlz.dat");
                if (file.canRead()) {
                    return AnlzFile.fromFile(file.getAbsolutePath());  // We have already downloaded it.
                }
                file.deleteOnExit();  // Prepare to download it.
                fetchFile(track.getSlotReference(), Database.getText(trackRow.analyzePath()), file);
                return AnlzFile.fromFile((file.getAbsolutePath()));
            } else {
                logger.warn("Unable to find track " + track + " in database " + database);
            }
        } catch (Exception e) {
            logger.error("Problem fetching analysis file for track " + track + " from database " + database, e);
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
            Database database = findDatabase(track);
            if (database != null) {
                try {
                    return new TrackMetadata(track, database, getCueList(sourceMedia, track));
                } catch (Exception e) {
                    logger.error("Problem fetching metadata for track " + track + " from database " + database, e);
                }
            }
            return null;
        }

        @Override
        public AlbumArt getAlbumArt(MediaDetails sourceMedia, DataReference art) {
            File file = null;
            Database database = findDatabase(art);
            if (database != null) {
                try {
                    PdbFile.ArtworkRow artworkRow = database.artworkIndex.get((long) art.rekordboxId);
                    if (artworkRow != null) {
                        file = new File(downloadDirectory, slotPrefix(art.getSlotReference()) +
                                "art-" + art.rekordboxId);
                        if (file.canRead()) {
                            return new AlbumArt(art, file);
                        }
                        file.deleteOnExit();  // Prepare to download it.
                        fetchFile(art.getSlotReference(), Database.getText(artworkRow.path()), file);
                        return new AlbumArt(art, file);
                    } else {
                        logger.warn("Unable to find artwork " + art + " in database " + database);
                    }
                } catch (Exception e) {
                    logger.warn("Problem fetching artwork " + art + " from database " + database, e);
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
            Database database = findDatabase(track);
            if (database != null) {
                try {
                    AnlzFile file = findTrackAnalysis(track, database);
                    if (file != null) {
                        return new BeatGrid(track, file);
                    }
                } catch (Exception e) {
                    logger.error("Problem fetching beat grid for track " + track + " from database " + database, e);
                }
            }
            return null;
        }

        @Override
        public CueList getCueList(MediaDetails sourceMedia, DataReference track) {
            Database database = findDatabase(track);
            if (database != null) {
                try {
                    AnlzFile file = findTrackAnalysis(track, database);
                    if (file != null) {
                        return new CueList(file);
                    }
                } catch (Exception e) {
                    logger.error("Problem fetching cue list for track " + track + " from database " + database, e);
                }
            }
            return null;
        }

        @Override
        public WaveformPreview getWaveformPreview(MediaDetails sourceMedia, DataReference track) {
            Database database = findDatabase(track);
            if (database != null) {
                try {
                    AnlzFile file = findTrackAnalysis(track, database);
                    if (file != null) {
                        return new WaveformPreview(track, file);
                    }
                } catch (Exception e) {
                    logger.error("Problem fetching waveform preview for track " + track + " from database " + database, e);
                }
            }
            return null;
        }

        @Override
        public WaveformDetail getWaveformDetail(MediaDetails sourceMedia, DataReference track) {
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
    @SuppressWarnings("WeakerAccess")
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
    @SuppressWarnings("WeakerAccess")
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
    public static CrateDigger getInstance() {
        return instance;
    }

    /**
     * The folder into which database exports and track analysis files will be downloaded.
     */
    @SuppressWarnings("WeakerAccess")
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
    private File createDownloadDirectory() {
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
     * Prevent direct instantiation, create a temporary directory for our file downloads,
     * and register the listeners that hook us into the streams of information we need.
     */
    private CrateDigger() {
        MetadataFinder.getInstance().addLifecycleListener(lifecycleListener);
        MetadataFinder.getInstance().addMountListener(mountListener);
        DeviceFinder.getInstance().addDeviceAnnouncementListener(deviceListener);
        VirtualCdj.getInstance().addMediaDetailsListener(mediaDetailsListener);
        downloadDirectory = createDownloadDirectory();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("MetadataFinder[").append("running: ").append(isRunning());
        if (isRunning()) {
            sb.append(", databases mounted: ").append(databases.size());
            sb.append(", download directory: ").append(downloadDirectory.getAbsolutePath());
        }
        return sb.append("]").toString();
    }
}
