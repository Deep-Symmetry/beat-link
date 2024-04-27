package org.deepsymmetry.beatlink.data;

import io.kaitai.struct.RandomAccessFileKaitaiStream;
import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.MediaDetails;
import org.deepsymmetry.beatlink.Util;
import org.deepsymmetry.cratedigger.Database;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.deepsymmetry.cratedigger.pdb.RekordboxPdb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
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
@API(status = API.Status.STABLE)  // TODO get rid of all IntelliJ code pattern entry points and @SuppressWarnings annotations, use this instead.
public class OpusProvider {

    private final Logger logger = LoggerFactory.getLogger(OpusProvider.class);

    /**
     * Keep track of whether we are running.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

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
     * Holds the ZIP filesystem of the media export mounted for USB slot 1 of the Opus Quad.
     */
    private final AtomicReference<FileSystem> usb1filesystem = new AtomicReference<>();

    /**
     * Holds the parsed database for the media we are proxying for the USB 1 slot of the Opus Quad.
     */
    private final AtomicReference<Database> usb1database = new AtomicReference<>();

    /**
     * Holds the ZIP filesystem of the media export mounted for USB slot 2 of the Opus Quad.
     */
    private final AtomicReference<FileSystem> usb2filesystem = new AtomicReference<>();

    /**
     * Holds the parsed database for the media we are proxying for the USB 2 slot of the Opus Quad.
     */
    private final AtomicReference<Database> usb2database = new AtomicReference<>();

    /**
     * Attach a metadata archive to supply information for the media mounted a USB slot of the Opus Quad.
     * This must be a file created using {@link org.deepsymmetry.cratedigger.Archivist#createArchive(Database, File)}
     * from that media.
     *
     * @param archive the metadata archive that can provide metadata for tracks playing from the specified USB slot, or
     *                {@code null} to stop providing metadata for that slot
     * @param slot which USB slot we are to provide metadata for (we follow the XDJ-XZ convention of using
     *            {@link CdjStatus.TrackSourceSlot#SD_SLOT} to represent USB 1, and
     *            {@link CdjStatus.TrackSourceSlot#USB_SLOT} to represent USB 2)
     *
     * @throws java.io.IOException if there is a problem attaching the archive
     * @throws IllegalArgumentException if a slot other than the SD or USB slot is specified
     */
    @API(status = API.Status.STABLE)
    public synchronized void attachMetadataArchive(File archive, CdjStatus.TrackSourceSlot slot) throws IOException {

        // Determine which slot we are adjusting the archive for.
        if (slot != CdjStatus.TrackSourceSlot.SD_SLOT && slot != CdjStatus.TrackSourceSlot.USB_SLOT) {
            throw new IllegalArgumentException("Unsupported slot, use SD_SLOT for USB 1 or USB_SLOT for USB 2: " + slot);
        }

        final AtomicReference<Database> databaseReference = (slot == CdjStatus.TrackSourceSlot.SD_SLOT) ? usb1database : usb2database;
        final AtomicReference<FileSystem> filesystemReference = (slot == CdjStatus.TrackSourceSlot.SD_SLOT) ? usb1filesystem : usb2filesystem;

        // First close and remove any archive we had previously attached for this slot.
        final Database formerDatabase = databaseReference.getAndSet(null);
        if (formerDatabase != null) {
            try {
                formerDatabase.close();
                //noinspection ResultOfMethodCallIgnored
                formerDatabase.sourceFile.delete();
            } catch (IOException e) {
                logger.error("Problem closing database for {}", slot, e);
            }
        }
        final FileSystem formerArchive = filesystemReference.getAndSet(null);
        if (formerArchive != null) {
            try {
                logger.info("Detached metadata archive {} from slot {}", formerArchive, slot);
                formerArchive.close();
            } catch (IOException e) {
                logger.error("Problem closing archive filesystem for USB 1", e);
            }

            // Clean up any extracted files associated with this archive.
            final String prefix = slotPrefix(slot);
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

        if (archive == null) return;  // Detaching is all we were asked to do, so we are done.

        // Open the new archive filesystem.
        FileSystem filesystem = FileSystems.newFileSystem(archive.toPath(), Thread.currentThread().getContextClassLoader());
        try {
            final File databaseFile = new File(extractDirectory, slotPrefix(slot) + "export.pdb");
            Files.copy(filesystem.getPath("/export.pdb"), databaseFile.toPath());
            final Database database = new Database(databaseFile);
            // If we got here, this looks like a valid metadata archive because we found a valid database export inside it.
            databaseReference.set(database);
            filesystemReference.set(filesystem);
            logger.info("Attached metadata archive {} for slot {}.", filesystem, slot);
        } catch (Exception e) {
            filesystem.close();
            throw new IOException("Problem reading export.pdb from metadata archive " + archive, e);
        }
    }

    /**
     * Find the database we have been provided and parsed that can provide information about the supplied slot
     * reference, if any (we follow the XDJ-XZ convention of using {@link CdjStatus.TrackSourceSlot#SD_SLOT}
     * to represent USB 1, and {@link CdjStatus.TrackSourceSlot#USB_SLOT} to represent USB 2).
     *
     * @param reference identifies the location for which data is desired
     *
     * @return the appropriate rekordbox extract to start from in finding that data, if we have one
     *
     * @throws IllegalArgumentException if a slot reference other than the SD or USB slot is specified
     */
    @API(status = API.Status.STABLE)
    public Database findDatabase(DataReference reference) {
        if (reference.player >= 9 && reference.player <= 12) {  // This is an Opus Quad deck.
            return findDatabase(reference.getSlotReference());
        }
        return null;
    }

    /**
     * Find the database we have been provided and parsed that can provide information about the supplied slot
     * reference, if any (we follow the XDJ-XZ convention of using {@link CdjStatus.TrackSourceSlot#SD_SLOT}
     * to represent USB 1, and {@link CdjStatus.TrackSourceSlot#USB_SLOT} to represent USB 2).
     *
     * @param slot identifies the slot for which data is desired
     *
     * @return the appropriate rekordbox extract to start from in finding that data, if we have one
     *
     * @throws IllegalArgumentException if a slot reference other than the SD or USB slot is specified
     */
    @API(status = API.Status.STABLE)
    public Database findDatabase(SlotReference slot) {
        switch (slot.slot) {
            case SD_SLOT:
                return usb1database.get();
            case USB_SLOT:
                return usb2database.get();
            default:
                throw new IllegalArgumentException("Unsupported slot, use SD_SLOT for USB 1 or USB_SLOT for USB 2: " + slot.slot);
        }
    }

    /**
     * Find the ZIP filesystem we have been provided that can provide metadata for the supplied slot
     * reference, if any (we follow the XDJ-XZ convention of using {@link CdjStatus.TrackSourceSlot#SD_SLOT}
     * to represent USB 1, and {@link CdjStatus.TrackSourceSlot#USB_SLOT} to represent USB 2).
     *
     * @param reference identifies the location for which metadata is desired
     *
     * @return the appropriate archive ZIP filesystem holding that metadata, if we have one
     *
     * @throws IllegalArgumentException if a slot reference other than the SD or USB slot is specified
     */
    @API(status = API.Status.STABLE)
    public FileSystem findFilesystem(DataReference reference) {
        return findFilesystem(reference.getSlotReference());
    }

    /**
     * Find the ZIP filesystem we have been provided that can provide metadata for the supplied slot
     * reference, if any (we follow the XDJ-XZ convention of using {@link CdjStatus.TrackSourceSlot#SD_SLOT}
     * to represent USB 1, and {@link CdjStatus.TrackSourceSlot#USB_SLOT} to represent USB 2).
     *
     * @param slot identifies the slot for which metadata is desired
     *
     * @return the appropriate archive ZIP filesystem holding that metadata, if we have one
     *
     * @throws IllegalArgumentException if a slot reference other than the SD or USB slot is specified
     */
    @API(status = API.Status.STABLE)
    public FileSystem findFilesystem(SlotReference slot) {
        switch (slot.slot) {
            case SD_SLOT:
                return usb1filesystem.get();
            case USB_SLOT:
                return usb2filesystem.get();
            default:
                throw new IllegalArgumentException("Unsupported slot, use SD_SLOT for USB 1 or USB_SLOT for USB 2: " + slot.slot);
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
    private String slotPrefix(CdjStatus.TrackSourceSlot slot) {
        return "slot-" + slot.protocolValue + "-";
    }

    /**
     * Find the analysis file for the specified track.
     * Be sure to call {@code _io().close()} when you are done using the returned struct.
     *
     * @param track the track whose analysis file is desired
     * @param database the parsed database export from which the analysis path can be determined
     * @param filesystem the open ZIP filesystem in which metadata can be found
     *
     * @return the parsed file containing the track analysis
     */
    private RekordboxAnlz findTrackAnalysis(DataReference track, Database database, FileSystem filesystem) {
        return findTrackAnalysis(track, database, filesystem, ".DAT");
    }

    /**
     * Find the extended analysis file for the specified track.
     * Be sure to call {@code _io().close()} when you are done using the returned struct.
     *
     * @param track the track whose extended analysis file is desired
     * @param database the parsed database export from which the analysis path can be determined
     * @param filesystem the open ZIP filesystem in which metadata can be found
     *
     * @return the parsed file containing the track analysis
     */
    private RekordboxAnlz findExtendedAnalysis(DataReference track, Database database, FileSystem filesystem) {
        return findTrackAnalysis(track, database, filesystem, ".EXT");
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
     * @param filesystem the open ZIP filesystem in which metadata can be found
     * @param extension the file extension (such as ".DAT" or ".EXT") which identifies the type file to be retrieved
     *
     * @return the parsed file containing the track analysis
     */
    private RekordboxAnlz findTrackAnalysis(DataReference track, Database database, FileSystem filesystem, String extension) {
        File file = null;
        try {
            RekordboxPdb.TrackRow trackRow = database.trackIndex.get((long) track.rekordboxId);
            if (trackRow != null) {
                file = new File(extractDirectory, slotPrefix(track.getSlotReference().slot) +
                        "track-" + track.rekordboxId + "-anlz" + extension.toLowerCase());
                final String filePath = file.getCanonicalPath();
                final String analyzePath = Database.getText(trackRow.analyzePath());
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
    @API(status = API.Status.STABLE)
    public final MetadataProvider metadataProvider = new MetadataProvider() {
        @Override
        public List<MediaDetails> supportedMedia() {
            return Collections.emptyList();  // We can answer queries about any media.
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
            final FileSystem archive = findFilesystem(art);
            if (database != null) {
                try {
                    RekordboxPdb.ArtworkRow artworkRow = database.artworkIndex.get((long) art.rekordboxId);
                    if (artworkRow != null) {
                        file = new File(extractDirectory, slotPrefix(art.getSlotReference().slot) +
                                "art-" + art.rekordboxId + ".jpg");
                        if (file.canRead()) {
                            return new AlbumArt(art, file);
                        }
                        if (ArtFinder.getInstance().getRequestHighResolutionArt()) {
                            try {
                                extractFile(archive, Util.highResolutionPath(Database.getText(artworkRow.path())), file);
                            } catch (IOException e) {
                                if (!(e instanceof java.nio.file.NoSuchFileException)) {
                                    logger.error("Unexpected exception type trying to load high resolution album art", e);
                                }
                                // Fall back to looking for the normal resolution art.
                                extractFile(archive, Database.getText(artworkRow.path()), file);
                            }
                        } else {
                            extractFile(archive, Database.getText(artworkRow.path()), file);
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
            final Database database = findDatabase(track);
            if (database != null) {
                try {
                    final RekordboxAnlz file = findTrackAnalysis(track, database, findFilesystem(track));
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
            final FileSystem archive = findFilesystem(track);
            if (database != null) {
                try {
                    // Try the extended file first, because it can contain both nxs2-style commented cues and basic cues
                    RekordboxAnlz file = findExtendedAnalysis(track, database, archive);
                    if (file ==  null) {  // No extended analysis found, fall back to the basic one
                        file = findTrackAnalysis(track, database, archive);
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
            return null;        }

        @Override
        public WaveformPreview getWaveformPreview(MediaDetails sourceMedia, DataReference track) {
            final Database database = findDatabase(track);
            final FileSystem archive = findFilesystem(track);
            if (database != null) {
                try {
                    final RekordboxAnlz file = findExtendedAnalysis(track, database, archive);  // Look for color preview first
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
                try {
                    final RekordboxAnlz file = findTrackAnalysis(track, database, archive);
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
                try {
                    RekordboxAnlz file = findExtendedAnalysis(track, database, findFilesystem(track));
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

                    final RekordboxAnlz file = findTrackAnalysis(track, database, findFilesystem(track), fileExtension);  // Open the desired file to scan.
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
    @API(status = API.Status.STABLE)
    public final File extractDirectory;

    /**
     * Prevent direct instantiation.
     */
    private OpusProvider() {
        extractDirectory = CrateDigger.createDownloadDirectory();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("OpusProvider[").append("running: ").append(isRunning());
        if (isRunning()) {
            sb.append(", USB 1 media: ").append(usb1filesystem.get());
            sb.append(", USB 2 media: ").append(usb2filesystem.get());
        }
        return sb.append("]").toString();
    }
}
