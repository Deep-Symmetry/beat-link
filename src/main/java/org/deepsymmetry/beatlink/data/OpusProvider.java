package org.deepsymmetry.beatlink.data;

import io.kaitai.struct.RandomAccessFileKaitaiStream;
import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.cratedigger.Database;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.deepsymmetry.cratedigger.pdb.RekordboxPdb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.mc.SQLiteMCSqlCipherConfig;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p>Allows users to attach metadata archives created by the
 * <a href="https://github.com/Deep-Symmetry/crate-digger#crate-digger">Crate Digger</a> library to
 * provide metadata when the corresponding USB is mounted in an Opus Quad, which is not capable of
 * providing most metadata on its own.</p>
 *
 * <p>To take advantage of these capabilities, simply call the {@link #start()} method and then use the
 * {@link MetadataFinder} as you would have without this class.</p>
 *
 * @author James Elliott
 * @since 8.0.0 */
@API(status = API.Status.EXPERIMENTAL)
public class OpusProvider {

    private static final Logger logger = LoggerFactory.getLogger(OpusProvider.class);

    /**
     * The device name reported by Opus Quad, so we can recognize when we are dealing with one of these devices.
     */
    public static final String OPUS_NAME = "OPUS-QUAD";

    /**
     * Keep track of whether we are running.
     */
    private static final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Check whether we are currently running.
     *
     * @return true if track metadata is being proxied from mounted media archives for Opus Quad players
     */
    @API(status = API.Status.STABLE)
    public boolean isRunning() {
        return running.get();
    }

    /**
     * If the user knows the key needed to access the SQLite database, it will be stored here.
     */
    private final AtomicReference<String> databaseKey = new AtomicReference<>();

    /**
     * Set the key needed to access SQLite databases found in metadata archives. If this is known and supplied,
     * more reliable access to metadata can be obtained by using these newer, Device Library Plus databases.
     *
     * @param key the key needed to open the {@code exportLibrary.db} databases.
     */
    @API(status = API.Status.EXPERIMENTAL)
    public void setDatabaseKey(String key) {
        databaseKey.set(key);
    }

    /**
     * Container for the USB slot number, database or JDBC connection, and filesystem of any particular Rekordbox USB archive.
     */
    @API(status = API.Status.EXPERIMENTAL)
    public static class RekordboxUsbArchive {
        private final int usbSlot;
        private final Database database;
        private final Connection connection;
        private final FileSystem fileSystem;

        /**
         * Return information about the metadata archive, if any, attached for one of the Opus Quad USB slots.
         *
         * @param usbSlot the slot number that the user has attached the archive to, should correspond to the USB slot in the Opus Quad, so they can keep track of what they are doing
         * @param database the parsed DeviceSQL database which contains information about tracks, artwork, etc.
         * @param connection the JDBC connection to the SQLite database that can be used instead of database; only one of these will be non-null.
         * @param fileSystem the filesystem which contains the database and other metadata
         */
        private RekordboxUsbArchive(int usbSlot, Database database, Connection connection, FileSystem fileSystem) {
            this.usbSlot = usbSlot;
            this.database = database;
            this.connection = connection;
            this.fileSystem = fileSystem;
        }

        /**
         * Find the USB slot number to which this archive has been attached.
         *
         * @return the USB slot on the Opus Quad to which the user has reported this metadata belongs
         */
        @API(status = API.Status.EXPERIMENTAL)
        public int getUsbSlot() {
            return usbSlot;
        }

        /**
         * Get the DeviceSQL database found in this archive. Will be {@code null} if {@link #getConnection()} is not {@code null}.
         *
         * @return the parsed database which contains information about tracks, artwork, etc.
         */
        @API(status = API.Status.EXPERIMENTAL)
        public Database getDatabase() {
            return database;
        }

        /**
         * Get the JDBC connection used to communicate with the SQLite database found in this archive.
         * Will be {@code null} if the archive lacked an {@code exportLibrary.db} file or if {@link #databaseKey} is {@code null} or incorrect.
         *
         * @return the connection that can be used to query information about tracks, artwork, etc.
         */
        @API(status = API.Status.EXPERIMENTAL)
        public Connection getConnection() {
            return connection;
        }

        /**
         * Get the filesystem associated with this archive.
         *
         * @return the filesystem which contains the database and other metadata
         */
        @API(status = API.Status.EXPERIMENTAL)
        public FileSystem getFileSystem() {
            return fileSystem;
        }
    }

    /**
     * Holds the archive information (USB slot number, parsed Database and Zipped Filesystem)
     * of media exports mounted for the Opus USB slots.
     */
    private final Map<Integer, RekordboxUsbArchive> usbArchiveMap = new ConcurrentHashMap<>();

    /**
     * <p>Contains a queue per slot number which allows us to slow down sending VirtualCdj.deliverMediaDetailsUpdate
     * when we attach metadata archives until the application is actually ready to do so.</p>
     *
     * <p>Queues are initialized in the constructor and will never be {@code null}.</p>
     */
    private final Map<Integer, LinkedBlockingQueue<MediaDetails>> archiveAttachQueueMap = new ConcurrentHashMap<>();

    /**
     * TODO: Doc
     */
    public static class DeviceSqlRekordboxIdAndSlot {
        private final int rekordboxId;
        private final int usbSlot;
    
        private DeviceSqlRekordboxIdAndSlot(int rekordboxId, int usbSlot) {
            this.rekordboxId = rekordboxId;
            this.usbSlot = usbSlot;
        }
    
        public int getRekordboxId() {
            return rekordboxId;
        }
    
        public int getUsbSlot() {
            return usbSlot;
        }
    }

    /**
     * TODO: Doc
     */
    private final Map<String, DeviceSqlRekordboxIdAndSlot> pssiToDeviceSqlRekordboxId = new ConcurrentHashMap<>();

    /**
     * TODO: Doc
     */
    private String computeSha1(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA1");
            digest.update(data);
            byte[] result = digest.digest();
            
            StringBuilder hex = new StringBuilder(result.length * 2);
            for (byte b : result) {
                hex.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
            }
            return hex.toString();
            
        } catch (NoSuchAlgorithmException e) {
            logger.error("Unable to obtain SHA-1 MessageDigest instance", e);
            return null;
        }
    }

    /**
     * Attach a metadata archive to supply information for the media mounted a USB slot of the Opus Quad.
     * This must be a file created using {@link org.deepsymmetry.cratedigger.Archivist#createArchive(Database, File)}
     * from that media.
     *
     * @param archiveFile the metadata archive that can provide metadata for tracks playing from the specified USB slot, or
     *                {@code null} to stop providing metadata for that slot
     * @param usbSlotNumber which USB slot we are to provide metadata for. Acceptable values are 1, 2 and 3.
     *
     * @throws java.io.IOException if there is a problem attaching the archive
     * @throws IllegalArgumentException if a slot other than the SD or USB slot is specified
     */
    @API(status = API.Status.EXPERIMENTAL)
    public synchronized void attachMetadataArchive(File archiveFile, int usbSlotNumber) throws IOException {
        if (usbSlotNumber < 1 || usbSlotNumber > 3) {
            throw new IllegalArgumentException("Unsupported usbSlotNumber, can only use 1, 2 or 3.");
        }

        // First close and remove any archive we had previously attached for this slot.
        final RekordboxUsbArchive formerArchive = usbArchiveMap.remove(usbSlotNumber);

        // Report archive closed.
        final SlotReference emptySlotReference = SlotReference.getSlotReference(usbSlotNumber, null);
        final MediaDetails emptyDetails = new MediaDetails(emptySlotReference, CdjStatus.TrackType.REKORDBOX, "",
                0, 0, 0);

        archiveAttachQueueMap.get(usbSlotNumber).add(emptyDetails);

        if (formerArchive != null) {
            try {
                logger.info("Detached metadata archive {} from slot {}", formerArchive, formerArchive.usbSlot);
                if (formerArchive.getDatabase() != null) {
                    formerArchive.getDatabase().close();
                }
                if (formerArchive.getConnection() != null) {
                    try {
                        formerArchive.getConnection().close();
                    } catch (Exception e) {
                        logger.error("Problem closing metadata archive JDBC connection for slot {}", usbSlotNumber, e);
                    }
                }
                formerArchive.getFileSystem().close();

                // Clear player caches as matching data is not applicable anymore.
                VirtualRekordbox.getInstance().clearPlayerCaches(usbSlotNumber);
            } catch (IOException e) {
                logger.error("Problem closing database or FileSystem for slot {}", usbSlotNumber, e);
            }

            // Clean up any extracted files associated with this archive.
            final String prefix = slotPrefix(usbSlotNumber);
            File[] files = extractDirectory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().startsWith(prefix)) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                }
            }
        }

        if (archiveFile == null) return;  // Detaching is all we were asked to do, so we are done.

        // Open the new archive filesystem.
        FileSystem filesystem = FileSystems.newFileSystem(archiveFile.toPath(), Thread.currentThread().getContextClassLoader());

        // First see if we can decrypt and use a SQLite Device Library Plus database from the archive.
        final SlotReference slotReference = SlotReference.getSlotReference(usbSlotNumber, CdjStatus.TrackSourceSlot.USB_SLOT);
        RekordboxUsbArchive openedArchive = null;
        MediaDetails newDetails = null;
        if (databaseKey.get() != null) {
            try {
                final File databaseFile = new File(extractDirectory, slotPrefix(usbSlotNumber) + "exportLibrary.db");
                Files.copy(filesystem.getPath("/exportLibrary.db"), databaseFile.toPath());
                final Connection connection = SQLiteMCSqlCipherConfig.getV4Defaults().withKey(databaseKey.get()).build()
                        .createConnection("jdbc:sqlite:file:" + databaseFile.getAbsolutePath());

                // If we got here, this is a valid metadata archive, we found a valid SQLite Device Library Plus export inside it,
                // and we have the correct key to decrypt and use the database.
                openedArchive = new RekordboxUsbArchive(usbSlotNumber, null, connection, filesystem);
                newDetails = new MediaDetails(slotReference, CdjStatus.TrackType.REKORDBOX, filesystem.toString(),
                        getRowCount(connection,"content"), getRowCount(connection,"playlist"), databaseFile.lastModified());
                logger.info("Attached SQLite metadata archive {} for slot {}.", filesystem, usbSlotNumber);

            } catch (Exception e) {
                filesystem.close();
                logger.error("Problem reading exportLibrary.db from metadata archive {}, is database key correct?", archiveFile, e);
            }
        }

        // Failing that, fall back to parsing and using the DeviceSQL export database.
        if (openedArchive == null) {
            try {
                final File databaseFile = new File(extractDirectory, slotPrefix(usbSlotNumber) + "export.pdb");
                Files.copy(filesystem.getPath("/export.pdb"), databaseFile.toPath());
                final Database database = new Database(databaseFile);

                // If we got here, this looks like a valid metadata archive because we found a valid DeviceSQL database export inside it.
                openedArchive = new RekordboxUsbArchive(usbSlotNumber, database, null, filesystem);

                // Populate pssiToDeviceSqlRekordboxId
                SlotReference slotRef = SlotReference.getSlotReference(1, CdjStatus.TrackSourceSlot.USB_SLOT);

                // Get max ID first
                // This can be found by finding the max key in database.trackIndex
                int maxId = 0;
                for (Long key : database.trackIndex.keySet()) {
                    if (key > maxId) {
                        maxId = key.intValue();
                    }
                }

                for (int i = 1; i <= maxId; i++) {
                    DataReference dataRef = new DataReference(slotRef, i);
                    RekordboxAnlz anlz = findExtendedAnalysis(usbSlotNumber, dataRef, database, openedArchive.getConnection(), filesystem);
                    if (anlz != null) {
                        // Get the SONG_STRUCTURE raw body
                        byte[] songStructure = getSongStructureRawBody(anlz);
                        if (songStructure != null) {
                            // SHA1 the songStructure
                            String sha1 = computeSha1(songStructure);
                            if (sha1 == null) {
                                logger.warn("Could not calculate SHA-1 for track {}", i);
                                continue;
                            }
                            pssiToDeviceSqlRekordboxId.put(sha1, new DeviceSqlRekordboxIdAndSlot(i, usbSlotNumber));
                        } else {
                            logger.warn("No SONG_STRUCTURE found for track {}", i);
                        }
                    } else {
                        logger.warn("No extended analysis found for track {}", i);
                    }
                }

                logger.info("pssiToDeviceSqlRekordboxId is now filled with {} entries", pssiToDeviceSqlRekordboxId.size());
                    newDetails = new MediaDetails(slotReference, CdjStatus.TrackType.REKORDBOX, filesystem.toString(),
                            database.trackIndex.size(), database.playlistIndex.size(), database.sourceFile.lastModified());
                    logger.info("Attached DeviceSQL metadata archive {} for slot {}.", filesystem, usbSlotNumber);
                } catch (Exception e) {
                    filesystem.close();
                    throw new IOException("Problem reading export.pdb from metadata archive " + archiveFile, e);
                }
            }

        // We successfully opened the archive with one of the two database formats.
        usbArchiveMap.put(usbSlotNumber, openedArchive);

        // Request initial PSSIs for track matching. After this we will request PSSI data on song change.
        VirtualRekordbox.getInstance().requestPSSI();

        // Send a media update so clients know this media is mounted.
        try {
            archiveAttachQueueMap.get(usbSlotNumber).put(newDetails);
        } catch (InterruptedException e) {
            logger.error("Problem enqueuing media update for mounted metadata archive", e);
        }
    }

    /**
     * Helper method to count the rows in a table.
     * @param connection provides access to the database
     * @param table the name of the table whose row count is desired
     *
     * @return the number of rows found in the table, or 0 if something goes wrong
     */
    private static int getRowCount(Connection connection, String table) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select count (*) ")) {
            if (resultSet.next()) {
                return resultSet.getInt(1);
            }
        } catch (Exception e) {
            logger.error("Problem counting rows in SQLite database from table {}", table, e);
        }
        return 0;
    }

    /**
     * Find the ZIP filesystem we have been provided that can provide metadata for the supplied slot
     * reference, if any (we follow the XDJ-XZ convention of using {@link CdjStatus.TrackSourceSlot#SD_SLOT}
     * to represent USB 1, and {@link CdjStatus.TrackSourceSlot#USB_SLOT} to represent USB 2).
     *
     * @param usbSlotNumber identifies the Opus Quad USB slot for which metadata is desired
     *
     * @return the appropriate archive ZIP filesystem holding that metadata, if we have one
     *
     * @throws IllegalArgumentException if a slot reference other than the SD or USB slot is specified
     */
    @API(status = API.Status.EXPERIMENTAL)
    public RekordboxUsbArchive findArchive(int usbSlotNumber) {
        return usbArchiveMap.get(usbSlotNumber);
    }

    /**
     * Grab {@link MediaDetails} for a slot from {@link #archiveAttachQueueMap} and deliver it to VirtualCdj listeners.
     * Message is {@code null} if that media archive does not exist.
     *
     * @param usbSlotNumber the USB slot whose queue should be checked
     */
    void pollAndSendMediaDetails(int usbSlotNumber){
        if (usbSlotNumber > 0 && usbSlotNumber < 4) {
            // Only send media details if there is something in the queue.
            MediaDetails mediaDetails = archiveAttachQueueMap.get(usbSlotNumber).poll();

            if (mediaDetails != null) {
                VirtualCdj.getInstance().deliverMediaDetailsUpdate(mediaDetails);
            }
        }
    }

    /**
     * Format the filename prefix that will be used to store files downloaded from a particular USB slot.
     * This allows them all to be cleaned up when that slot media is detached.
     *
     * @param slot the slot from which files are being downloaded
     *
     * @return the prefix with which the names of all files downloaded from that slot will start
     */
    private String slotPrefix(int slot) {
        return "slot-" + slot + "-";
    }

    /**
     * Find the analysis file for the specified track.
     * Be sure to call {@code _io().close()} when you are done using the returned struct.
     *
     * @param track the track whose analysis file is desired
     * @param database the parsed database export from which the analysis path can be determined
     * @param connection the JDBC connection to the more-useful SQLite database, if one is available
     * @param filesystem the open ZIP filesystem in which metadata can be found
     *
     * @return the parsed file containing the track analysis
     */
    private RekordboxAnlz findTrackAnalysis(int usbSlotNumber, DataReference track, Database database, Connection connection, FileSystem filesystem) {
        return findTrackAnalysis(usbSlotNumber, track, database, connection, filesystem, ".DAT");
    }

    /**
     * Find the extended analysis file for the specified track.
     * Be sure to call {@code _io().close()} when you are done using the returned struct.
     *
     * @param track the track whose extended analysis file is desired
     * @param database the parsed database export from which the analysis path can be determined
     * @param connection the JDBC connection to the more-useful SQLite database, if one is available
     * @param filesystem the open ZIP filesystem in which metadata can be found
     *
     * @return the parsed file containing the track analysis
     */
    private RekordboxAnlz findExtendedAnalysis(int usbSlotNumber, DataReference track, Database database, Connection connection, FileSystem filesystem) {
        return findTrackAnalysis(usbSlotNumber, track, database, connection, filesystem, ".EXT");
    }

    private byte[] getSongStructureRawBody(RekordboxAnlz anlz) {
        for (RekordboxAnlz.TaggedSection section : anlz.sections()) {
            if (section.fourcc() == RekordboxAnlz.SectionTags.SONG_STRUCTURE) {
                return section._raw_body();
            }
        }
        return null;
    }

    /**
     * Helper method to extract a file from the metadata archive. Handles the fact that archives created from HFS+
     * media hide the PIONEER folder with a leading period.
     *
     * @param archive the filesystem mounted from the metadata ZIP file
     * @param sourcePath identifies the desired file within the archive
     * @param destination the file on the host filesystem to which the file should be extracted
     *
     * @throws IOException if there is a problem extracting the file
     */
    private void extractFile(FileSystem archive, String sourcePath, File destination) throws IOException {
        if (sourcePath.startsWith("PIONEER/") && !Files.isReadable(archive.getPath(sourcePath))) {
            extractFile(archive, "." + sourcePath, destination);  // We must be dealing with HFS+ media.
            return;
        }
        Files.copy(archive.getPath(sourcePath), destination.toPath());
        destination.deleteOnExit();
    }

    /**
     * Find an analysis file for the specified track, with the specified file extension.
     * Be sure to call {@code _io().close()} when you are done using the returned struct.
     *
     * @param track the track whose extended analysis file is desired
     * @param database the parsed database export from which the analysis path can be determined
     * @param connection the JDBC connection to the more-useful SQLite database, if one is available
     * @param filesystem the open ZIP filesystem in which metadata can be found
     * @param extension the file extension (such as ".DAT" or ".EXT") which identifies the type file to be retrieved
     *
     * @return the parsed file containing the track analysis
     */
    private RekordboxAnlz findTrackAnalysis(int usbSlotNumber, DataReference track, Database database, Connection connection, FileSystem filesystem, String extension) {
        File file = null;
        String analyzePath = null;
        if (connection != null) {
            // We can use the nice SQLite Device Library Plus database
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("select analysisDataFilePath from content where content_id = " + track.rekordboxId)) {
                if (resultSet.next()) {
                    analyzePath = resultSet.getString(1);
                }
            } catch (SQLException e) {
                logger.error("Problem reading track analysis file path from SQLite database", e);
            }
        } else {
            // We have to fall back to the legacy DeviceSQL database
            RekordboxPdb.TrackRow trackRow = database.trackIndex.get((long) track.rekordboxId);
            if (trackRow != null) {
                analyzePath = Database.getText(trackRow.analyzePath());
            }
        }
        try {
            if (analyzePath != null) {
                file = new File(extractDirectory, slotPrefix(usbSlotNumber) +
                        "track-" + track.rekordboxId + "-anlz" + extension.toLowerCase());
                final String filePath = file.getCanonicalPath();
                final String requestedPath = analyzePath.replaceAll("\\.DAT$", extension.toUpperCase());
                try {
                    synchronized (Util.allocateNamedLock(filePath)) {
                        if (file.canRead()) {  // We have already downloaded it.
                            return new RekordboxAnlz(new RandomAccessFileKaitaiStream(filePath));
                        }
                        extractFile(filesystem, requestedPath, file);
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
            logger.error("Problem extracting analysis file with extension {} for track {} from database {}", extension.toUpperCase(), track, database, e);
            if (file != null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        return null;
    }

    /**
     * This is the mechanism by which we offer metadata to the {@link MetadataProvider} while we are running.
     * Exposed as public so that it can be used for testing and as a utility if desired.
     */
    @API(status = API.Status.EXPERIMENTAL)
    public final MetadataProvider metadataProvider = new MetadataProvider() {
        @Override
        public List<MediaDetails> supportedMedia() {
            return Collections.emptyList();  // We can answer queries about any media.
        }

        @Override
        public TrackMetadata getTrackMetadata(MediaDetails sourceMedia, DataReference track) {
            final RekordboxUsbArchive archive = findArchive(track.player);
            if (archive != null) {
                Connection connection = archive.getConnection();
                if (connection != null) {
                    // We have a usable SQLite Device Library Plus database connection we can use
                    try {
                        return new TrackMetadata(track, connection, getCueList(sourceMedia, track));
                    } catch (Exception e) {
                        logger.error("Problem fetching metadata for track {} from JDBC SQLite connection", track, e);
                    }
                } else {
                    // We have to fall back to the legacy DeviceSQL database and hope the IDs match
                    Database database = archive.getDatabase();
                    try {
                        return new TrackMetadata(track, database, getCueList(sourceMedia, track));
                    } catch (Exception e) {
                        logger.error("Problem fetching metadata for track {} from DeviceSQL database {}", track, database, e);
                    }
                }
            }
            return null;
        }

        @Override
        public AlbumArt getAlbumArt(MediaDetails sourceMedia, DataReference art) {
            File file = null;
            final RekordboxUsbArchive archive = findArchive(art.player);

            if (archive != null) {
                final FileSystem fileSystem = archive.getFileSystem();
                final Connection connection = archive.getConnection();
                final Database database = archive.getDatabase();

                try {
                    String artPath = null;
                    if (connection != null) {
                        // We have a JDBC connection to the SQLite Device Library Plus export database
                        try (Statement statement = connection.createStatement();
                             ResultSet resultSet = statement.executeQuery("select * from image where image_id = " + art.rekordboxId)) {
                            if (resultSet.next()) {
                                artPath = resultSet.getString("path");
                            }
                        } catch (SQLException e) {
                            logger.error("Problem retrieving artwork path from SQLite database", e);
                        }
                    } else {
                        // We have to use the legacy DeviceSQL database
                        RekordboxPdb.ArtworkRow artworkRow = database.artworkIndex.get((long) art.rekordboxId);
                        if (artworkRow != null) {
                            artPath = Database.getText(artworkRow.path());
                        }
                    }

                    if (artPath != null) {
                        file = new File(extractDirectory, slotPrefix(archive.getUsbSlot()) +
                                "art-" + art.rekordboxId + ".jpg");
                        if (file.canRead()) {
                            return new AlbumArt(art, file);
                        }
                        if (ArtFinder.getInstance().getRequestHighResolutionArt()) {
                            try {
                                extractFile(fileSystem, Util.highResolutionPath(artPath), file);
                            } catch (IOException e) {
                                if (!(e instanceof java.nio.file.NoSuchFileException)) {
                                    logger.error("Unexpected exception type trying to load high resolution album art", e);
                                }
                                // Fall back to looking for the normal resolution art.
                                extractFile(fileSystem, artPath, file);
                            }
                        } else {
                            extractFile(fileSystem, artPath, file);
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
            return null;        }

        @Override
        public BeatGrid getBeatGrid(MediaDetails sourceMedia, DataReference track) {
            final RekordboxUsbArchive archive = findArchive(track.player);

            if (archive != null) {
                try {
                    final RekordboxAnlz file = findTrackAnalysis(archive.getUsbSlot(), track, archive.getDatabase(), archive.getConnection(), archive.getFileSystem());
                    if (file != null) {
                        try {
                            return new BeatGrid(track, file);
                        } finally {
                            file._io().close();
                        }
                    }
                } catch (Exception e) {
                    logger.error("Problem fetching beat grid for track {} from archive {}", track, archive, e);
                }
            }
            return null;
        }

        @Override
        public CueList getCueList(MediaDetails sourceMedia, DataReference track) {
            final RekordboxUsbArchive archive = findArchive(track.player);
            if (archive != null) {
                try {
                    // Try the extended file first, because it can contain both nxs2-style commented cues and basic cues
                    RekordboxAnlz file = findExtendedAnalysis(archive.getUsbSlot(), track, archive.getDatabase(), archive.getConnection(), archive.getFileSystem());
                    if (file ==  null) {  // No extended analysis found, fall back to the basic one
                        file = findTrackAnalysis(archive.getUsbSlot(), track, archive.getDatabase(), archive.getConnection(), archive.getFileSystem());
                    }
                    if (file != null) {
                        try {
                            return new CueList(file);
                        } finally {
                            file._io().close();
                        }
                    }
                } catch (Exception e) {
                    logger.error("Problem fetching cue list for track {} from archive {}", track, archive, e);
                }
            }
            return null;        }

        @Override
        public WaveformPreview getWaveformPreview(MediaDetails sourceMedia, DataReference track) {
            RekordboxUsbArchive archive = findArchive(track.player);
            if (archive != null) {
                try {
                    final RekordboxAnlz file = findExtendedAnalysis(archive.getUsbSlot(), track, archive.getDatabase(), archive.getConnection(), archive.getFileSystem());  // Look for color preview first
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
                    logger.error("Problem fetching color waveform preview for track {} from archive {}", track, archive, e);
                }
                try {
                    final RekordboxAnlz file = findTrackAnalysis(archive.getUsbSlot(), track, archive.getDatabase(), archive.getConnection(), archive.getFileSystem());
                    if (file != null) {
                        try {
                            return new WaveformPreview(track, file);
                        } finally {
                            file._io().close();
                        }
                    }
                } catch (Exception e) {
                    logger.error("Problem fetching waveform preview for track {} from archive {}", track, archive, e);
                }
            }
            return null;
        }

        @Override
        public WaveformDetail getWaveformDetail(MediaDetails sourceMedia, DataReference track) {
            final RekordboxUsbArchive archive = findArchive(track.player);

            if (archive != null) {
                try {
                    RekordboxAnlz file = findExtendedAnalysis(archive.getUsbSlot(), track, archive.getDatabase(), archive.getConnection(), archive.getFileSystem());
                    if (file != null) {
                        try {
                            return new WaveformDetail(track, file);
                        } finally {
                            file._io().close();
                        }
                    }
                } catch (Exception e) {
                    logger.error("Problem fetching waveform preview for track {} from archive {}", track, archive, e);
                }
            }
            return null;
        }

        @Override
        public RekordboxAnlz.TaggedSection getAnalysisSection(MediaDetails sourceMedia, DataReference track, String fileExtension, String typeTag) {
            final RekordboxUsbArchive archive = findArchive(track.player);

            if (archive != null) {
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

                    final RekordboxAnlz file = findTrackAnalysis(archive.getUsbSlot(), track, archive.getDatabase(), archive.getConnection(),
                            archive.getFileSystem(), fileExtension);  // Open the desired file to scan.
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
                    logger.error("Problem fetching analysis file {} section {} for track {} from archive {}", fileExtension, typeTag, track, archive, e);
                }
            }
            return null;
        }
    };

    public DeviceSqlRekordboxIdAndSlot getDeviceSqlRekordboxIdAndSlotNumberFromPssi(byte[] pssi, int idSentFromOpus) {
        // From observation, the actual part we need to check from the
        // body sent from the Opus is starting at index 12
        // (that's where the SONG_STRUCTURE starts)
        byte[] pssiBody = Arrays.copyOfRange(pssi, 12, pssi.length);

        // Compute the SHA-1 of the final slice
        String pssiBodySha1 = computeSha1(pssiBody);

        if (pssiBodySha1 == null) {
            logger.warn("Could not calculate SHA-1 for PSSI");
            return null;
        }

        List<DeviceSqlRekordboxIdAndSlot> matches = new ArrayList<>();

        // Go through pssiToDeviceSqlRekordboxId completely and find all matches
        for (Map.Entry<String, DeviceSqlRekordboxIdAndSlot> entry : pssiToDeviceSqlRekordboxId.entrySet()) {
            String sha1 = entry.getKey();
            if (pssiBodySha1.equals(sha1)) {
                matches.add(entry.getValue());
            }
        }

        if (matches.isEmpty()) {
            logger.warn("No PSSI matches found");
            return null;
        } else if (matches.size() == 1) {
            return matches.get(0);
        } else {
            // Multiple matches found - prefer the match with the same ID sent from the Opus
            for (DeviceSqlRekordboxIdAndSlot match : matches) {
                if (match.getRekordboxId() == idSentFromOpus) {
                    logger.info("Multiple PSSI matches found, preferring ID sent from Opus: {}", idSentFromOpus);
                    return match;
                }
            }
            // If the Opus sent back an ID that we don't have a match for,
            // we'll just return the first match we found.
            DeviceSqlRekordboxIdAndSlot firstMatch = matches.get(0);
            logger.info("Multiple PSSI matches found, but none match ID sent from Opus: {}. Returning the first match: {}", idSentFromOpus, firstMatch);
            return firstMatch;
        }
    }

    /**
     * Method that will use PSSI + rekordboxId to confirm that the database filesystem match the song. Will
     * look up PSSI in the chosen archive for the specific RekordboxId and then see if the PSSI matches what the
     * Opus is sending us.
     *
     * @param dataRef This is the track/slot data
     * @param pssiFromOpus PSSI sent from the opus
     * @param archive the database and filesystem we are comparing the PSSI+rekordboxId against
     * @return true if matched
     */
    private boolean trackMatchesArchive(DataReference dataRef, ByteBuffer pssiFromOpus, RekordboxUsbArchive archive) {
        RekordboxAnlz anlz = findExtendedAnalysis(archive.getUsbSlot(), dataRef, archive.getDatabase(), archive.getConnection(), archive.getFileSystem());
        if (anlz != null) {
            for (RekordboxAnlz.TaggedSection taggedSection : anlz.sections()) {
                if (taggedSection.fourcc() == RekordboxAnlz.SectionTags.SONG_STRUCTURE) {
                    return Util.indexOfByteBuffer(pssiFromOpus, taggedSection._raw_body()) > -1;
                }
            }
        }
        return false;
    }

    /**
     * Find which USB slot contains a track that matches the specified ID, given the latest song structure report we have from that player
     *
     * @param rekordboxId the reported track ID
     * @param player the device playing the specified track
     * @param songStructureBytes the PSSI data we have received for the player
     * @return the USB slot number in which a match was found, or zero if none was found
     */
    @API(status = API.Status.EXPERIMENTAL)
    public int findMatchingUsbSlotForTrack(int rekordboxId, int player, ByteBuffer songStructureBytes){
        SlotReference slotRef = SlotReference.getSlotReference(player, CdjStatus.TrackSourceSlot.USB_SLOT);
        DataReference dataRef = new DataReference(slotRef, rekordboxId);

        for (RekordboxUsbArchive archive: usbArchiveMap.values()) {
            if (trackMatchesArchive(dataRef, songStructureBytes, archive)) {
                return archive.getUsbSlot();
            }
        }
        return 0;
    }

    /**
     * Start proxying track metadata from mounted archives for the Opus Quad decks.
     */
    @API(status = API.Status.STABLE)
    public synchronized void start()  {
        if (!isRunning()) {
            running.set(true);
             MetadataFinder.getInstance().addMetadataProvider(metadataProvider);
        }
    }

    /**
     * Stop proxying track metadata for Opus Quad decks.
     */
    @API(status = API.Status.STABLE)
    public synchronized void stop() {
        if (isRunning()) {
            running.set(false);
            MetadataFinder.getInstance().removeMetadataProvider(metadataProvider);
        }
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final OpusProvider instance = new OpusProvider();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    @API(status = API.Status.STABLE)
    public static OpusProvider getInstance() {
        return instance;
    }

    /**
     * The folder into which database exports and track analysis files will be extracted.
     */
    @API(status = API.Status.EXPERIMENTAL)
    public final File extractDirectory;

    /**
     * Prevent direct instantiation.
     */
    private OpusProvider() {
        extractDirectory = CrateDigger.createDownloadDirectory();
        // Create MediaDetails Queues, one per USB slot 1-3.
        for (int i = 1; i <= 3; i++) {
            archiveAttachQueueMap.put(i, new LinkedBlockingQueue<>());
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("OpusProvider[").append("running: ").append(isRunning());
        if (isRunning()) {
            for (RekordboxUsbArchive archive : usbArchiveMap.values()) {
                sb.append(", USB ").append(archive.getUsbSlot()).append(" media: ").append(archive.getFileSystem());
            }
        }
        return sb.append("]").toString();
    }
}
