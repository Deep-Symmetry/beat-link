package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.MediaDetails;
import org.deepsymmetry.cratedigger.Database;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileSystem;
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
    @SuppressWarnings("WeakerAccess")
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
    private final AtomicReference<Database> usb1database = new AtomicReference<Database>();

    /**
     * Holds the ZIP filesystem of the media export mounted for USB slot 2 of the Opus Quad.
     */
    private final AtomicReference<FileSystem> usb2filesystem = new AtomicReference<>();

    /**
     * Holds the parsed database for the media we are proxying for the USB 2 slot of the Opus Quad.
     */
    private final AtomicReference<Database> usb2database = new AtomicReference<Database>();

    /**
     * This is the mechanism by which we offer metadata to the {@link MetadataProvider} while we are running.
     */
    private final MetadataProvider metadataProvider = new MetadataProvider() {
        @Override
        public List<MediaDetails> supportedMedia() {
            return Collections.emptyList();
        }

        @Override
        public TrackMetadata getTrackMetadata(MediaDetails sourceMedia, DataReference track) {
            // TODO implement!
            return null;
        }

        @Override
        public AlbumArt getAlbumArt(MediaDetails sourceMedia, DataReference art) {
            // TODO implement!
            return null;
        }

        @Override
        public BeatGrid getBeatGrid(MediaDetails sourceMedia, DataReference track) {
            // TODO implement!
            return null;
        }

        @Override
        public CueList getCueList(MediaDetails sourceMedia, DataReference track) {
            // TODO implement!
            return null;
        }

        @Override
        public WaveformPreview getWaveformPreview(MediaDetails sourceMedia, DataReference track) {
            // TODO implement!
            return null;
        }

        @Override
        public WaveformDetail getWaveformDetail(MediaDetails sourceMedia, DataReference track) {
            // TODO implement!
            return null;
        }

        @Override
        public RekordboxAnlz.TaggedSection getAnalysisSection(MediaDetails sourceMedia, DataReference track, String fileExtension, String typeTag) {
            // TODO implement!
            return null;
        }
    };

    /**
     * Start proxying track metadata from mounted archives for the Opus Quad decks.
     */
    @SuppressWarnings("WeakerAccess")
    public synchronized void start()  {
        if (!isRunning()) {
            running.set(true);
             MetadataFinder.getInstance().addMetadataProvider(metadataProvider);
        }
    }

    /**
     * Stop proxying track metadata for Opus Quad decks.
     */
    @SuppressWarnings("WeakerAccess")
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
    public static OpusProvider getInstance() {
        return instance;
    }

    /**
     * Prevent direct instantiation.
     */
    private OpusProvider() {
        // Nothing to do.
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
