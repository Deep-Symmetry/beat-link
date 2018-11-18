package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.cratedigger.Database;
import org.deepsymmetry.cratedigger.FileFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collections;
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
                            final DeviceAnnouncement player = DeviceFinder.getInstance().getLatestAnnouncementFrom(details.slotReference.player);
                            if (player == null) {
                                throw new IllegalStateException("Cannot fetch rekordbox database from player that is not found on the network; details: " +
                                        details);
                            }
                            file = File.createTempFile("beat-link-", ".pdb");
                            file.deleteOnExit();
                            FileFetcher.getInstance().fetch(player.getAddress(), mountPath(details.slotReference.slot),
                                    "PIONEER/rekordbox/export.pdb", file);
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
        }
    }

    /**
     * Stop finding track metadata for all active players.
     */
    @SuppressWarnings("WeakerAccess")
    public synchronized void stop() {
        if (isRunning()) {
            running.set(false);
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
     * Prevent direct instantiation, and register the listeners that hook us into the streams of information we need.
     */
    private CrateDigger() {
        MetadataFinder.getInstance().addLifecycleListener(lifecycleListener);
        MetadataFinder.getInstance().addMountListener(mountListener);
        DeviceFinder.getInstance().addDeviceAnnouncementListener(deviceListener);
        VirtualCdj.getInstance().addMediaDetailsListener(mediaDetailsListener);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("MetadataFinder[").append("running: ").append(isRunning());
        if (isRunning()) {
            sb.append(", databases mounted: ").append(databases.size());
        }
        return sb.append("]").toString();
    }
}
