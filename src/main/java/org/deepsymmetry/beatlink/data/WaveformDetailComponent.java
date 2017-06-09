package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.*;

import javax.swing.*;
import java.awt.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides a convenient way to draw waveform detail in a user interface, including annotations like the
 * location at the current time, and cue point markers (if you supply {@link TrackMetadata} so their location
 * can be determined), and beat markers (if you also supply a {@link BeatGrid}). Can also
 * be configured to automatically update itself to reflect the state of a specified player, showing the current
 * track, playback state, and position, as long as it is able to load appropriate metadata.
 */
@SuppressWarnings("WeakerAccess")
public class WaveformDetailComponent extends JComponent {

    // private static final Logger logger = LoggerFactory.getLogger(WaveformDetailComponent.class);

    /**
     * How many pixels high are the beat markers.
     */
    private static final int BEAT_MARKER_HEIGHT = 4;

    /**
     * How many pixels beyond the waveform the playback indicator extends.
     */
    private static final int VERTICAL_MARGIN = 15;

    /**
     * How many pixels wide is the current time indicator.
     */
    private static final int PLAYBACK_MARKER_WIDTH = 2;

    /**
     * The color to draw the playback position when playing; a slightly transparent white.
     */
    private static final Color PLAYBACK_MARKER_PLAYING = new Color(255, 255, 255, 235);

    /**
     * The color to draw the playback position when playing; a slightly transparent red.
     */
    private static final Color PLAYBACK_MARKER_STOPPED = new Color(255, 0, 0, 235);

    /**
     * The different colors the waveform can be based on its intensity.
     */
    private static final Color[] COLOR_MAP = {
            new Color(0, 0, 163),
            new Color(0, 52, 208),
            new Color(0, 119, 233),
            new Color(46, 215, 255),
            new Color(76, 225, 250),
            new Color(164, 231, 227),
            new Color(185, 224, 217),
            new Color(223, 245, 255)
    };

    /**
     * If not zero, automatically update the waveform, position, and metadata in response to the activity of the
     * specified player number.
     */
    private final AtomicInteger monitoredPlayer = new AtomicInteger(0);

    /**
     * The waveform preview that we are drawing.
     */
    private final AtomicReference<WaveformDetail> waveform = new AtomicReference<WaveformDetail>();

    /**
     * Track the current playback position in milliseconds.
     */
    private final AtomicLong playbackPosition = new AtomicLong(0);

    /**
     * Track how many segments we average into a column of pixels; larger values zoom out, 1 is full scale.
     */
    private final AtomicInteger scale = new AtomicInteger(1);

    /**
     * Track whether the player holding the waveform is currently playing.
     */
    private final AtomicBoolean playing = new AtomicBoolean(false);

    /**
     * Information about the track whose waveform we are drawing, so we can draw cues and memory points.
     */
    private final AtomicReference<TrackMetadata> metadata = new AtomicReference<TrackMetadata>();

    /**
     * Information about where all the beats in the track fall, so we can draw them.
     */
    private final AtomicReference<BeatGrid> beatGrid = new AtomicReference<BeatGrid>();

    /**
     * Set the current playback position. Will cause the component to be redrawn if the position has
     * changed. This will be quickly overruled if a player is being monitored, but
     * can be used in other contexts.
     *
     * @param milliseconds how far into the track has been played
     */
    public void setPlaybackPosition(long milliseconds) {
        long oldPosition = playbackPosition.getAndSet(milliseconds);
        if (oldPosition != milliseconds) {
            repaint();
        }
    }

    /**
     * Set the zoom scale of the view. a value of (the smallest allowed) draws the waveform at full scale.
     * Larger values combine more and more segments into a single column of pixels, zooming out to see more at once.
     *
     * @param scale the number of waveform segments that should be averaged into a single column of pixels
     *
     * @throws IllegalArgumentException if scale is less than 1 or greater than 256
     */
    public void setScale(int scale) {
        if ((scale < 1) || (scale > 256)) {
            throw new IllegalArgumentException("Scale must be between 1 and 256");
        }
        int oldScale = this.scale.getAndSet(scale);
        if (oldScale != scale) {
            repaint();
        }
    }

    /**
     * Set whether the player holding the waveform is playing, which changes the indicator color to white from red.
     *
     * @param playing if {@code true}, draw the position marker in white, otherwise red
     */
    @SuppressWarnings("WeakerAccess")
    public void setPlaying(boolean playing) {
        final boolean oldValue = this.playing.getAndSet(playing);
        if ((metadata != null) && oldValue != playing) {
            repaint((getWidth() / 2) - 2, 0, 4, getHeight());
        }
    }

    /**
     * Change the waveform preview being drawn. This will be quickly overruled if a player is being monitored, but
     * can be used in other contexts.
     *
     * @param waveform the waveform detail to display
     * @param metadata information about the track whose waveform we are drawing, so we can draw cue and memory points
     * @param beatGrid the locations of the beats, so they can be drawn
     */
    public void setWaveform(WaveformDetail waveform, TrackMetadata metadata, BeatGrid beatGrid) {
        this.waveform.set(waveform);
        this.metadata.set(metadata);
        this.beatGrid.set(beatGrid);
        playbackPosition.set(0);
        repaint();
    }

    /**
     * Configures the player whose current track waveforms and status will automatically be reflected. Whenever a new
     * track is loaded on that player, the waveform and metadata will be updated, and the current playback position and
     * state of the player will be reflected by the component.
     *
     * @param player the player number to monitor, or zero if monitoring should stop
     */
    public synchronized void setMonitoredPlayer(final int player) {
        if (player < 0) {
            throw new IllegalArgumentException("player cannot be negative");
        }
        monitoredPlayer.set(player);
        if (player > 0) {  // Start monitoring the specified player
            MetadataFinder.getInstance().addTrackMetadataListener(metadataListener);
            if (MetadataFinder.getInstance().isRunning()) {
                metadata.set(MetadataFinder.getInstance().getLatestMetadataFor(player));
            } else {
                metadata.set(null);
            }
            WaveformFinder.getInstance().addWaveformListener(waveformListener);
            if (WaveformFinder.getInstance().isRunning() && WaveformFinder.getInstance().isFindingDetails()) {
                waveform.set(WaveformFinder.getInstance().getLatestDetailFor(player));
            } else {
                waveform.set(null);
            }
            BeatGridFinder.getInstance().addBeatGridListener(beatGridListener);
            if (BeatGridFinder.getInstance().isRunning()) {
                beatGrid.set(BeatGridFinder.getInstance().getLatestBeatGridFor(player));
            } else {
                beatGrid.set(null);
            }
            VirtualCdj.getInstance().addUpdateListener(updateListener);
        } else {  // Stop monitoring any player
            VirtualCdj.getInstance().removeUpdateListener(updateListener);
            MetadataFinder.getInstance().removeTrackMetadataListener(metadataListener);
            WaveformFinder.getInstance().removeWaveformListener(waveformListener);
            metadata.set(null);
            waveform.set(null);
            beatGrid.set(null);
        }
        repaint();
    }

    /**
     * Reacts to changes in the track metadata associated with the player we are monitoring.
     */
    private final TrackMetadataListener metadataListener = new TrackMetadataListener() {
        @Override
        public void metadataChanged(TrackMetadataUpdate update) {
            if (update.player == monitoredPlayer.get()) {
                metadata.set(update.metadata);
                repaint();
            }
        }
    };

    /**
     * Reacts to changes in the waveform associated with the player we are monitoring.
     */
    private final WaveformListener waveformListener = new WaveformListener() {
        @Override
        public void previewChanged(WaveformPreviewUpdate update) {
            // Nothing to do.
        }

        @Override
        public void detailChanged(WaveformDetailUpdate update) {
            if (update.player == monitoredPlayer.get()) {
                waveform.set(update.detail);
                repaint();
            }
        }
    };

    /**
     * Reacts to changes in the beat grid associated with the player we are monitoring.
     */
    private final BeatGridListener beatGridListener = new BeatGridListener() {
        @Override
        public void beatGridChanged(BeatGridUpdate update) {
            if (update.player == monitoredPlayer.get()) {
                beatGrid.set(update.beatGrid);
                repaint();
            }
        }
    };

    // TODO: Add beat listener too? Better, switch to using TransportListener once I implement that.

    /**
     * Reacts to player status updates to reflect the current playback position and state.
     */
    private final DeviceUpdateListener updateListener = new DeviceUpdateListener() {
        @Override
        public void received(DeviceUpdate update) {
            if ((update instanceof CdjStatus) && (update.getDeviceNumber() == monitoredPlayer.get()) &&
                    (metadata.get() != null) && (beatGrid.get() != null)) {
                CdjStatus status = (CdjStatus) update;
                final int beat = status.getBeatNumber();
                setPlaybackPosition((beat < 1)? 0 : beatGrid.get().getTimeWithinTrack(beat));
                setPlaying(status.isPlaying());
            }
        }
    };

    /**
     * Create a view which updates itself to reflect the track loaded on a particular player, and that player's
     * playback progress.
     *
     * @param player the player number to monitor, or zero if it should start out monitoring no player
     */
    @SuppressWarnings("WeakerAccess")
    public WaveformDetailComponent(int player) {
        setMonitoredPlayer(player);
    }

    /**
     * Create a view which draws a specific waveform, even if it is not currently loaded in a player.
     *
     * @param waveform the waveform detail to display
     * @param metadata information about the track whose waveform we are drawing, so we can draw cues and memory points
     * @param beatGrid the locations of the beats, so they can be drawn
     */
    public WaveformDetailComponent(WaveformDetail waveform, TrackMetadata metadata, BeatGrid beatGrid) {
        this.waveform.set(waveform);
        this.metadata.set(metadata);
        this.beatGrid.set(beatGrid);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(300, 150);
    }

    /**
     * Figure out the starting waveform segment that corresponds to the specified coordinate in the window.

     * @param x the column being drawn
     *
     * @return the offset into the waveform at the current scale and playback time that should be drawn there
     */
    private int getSegmentForX(int x) {
        int playHead = (x - (getWidth() / 2));
        int offset = Util.timeToHalfFrame(playbackPosition.get()) / scale.get();
        return  (playHead + offset) * scale.get();
    }

    /**
     * The number of bytes at the start of the waveform data which do not seem to be valid or used.
     */
    public static final int LEADING_JUNK_BYTES = 19;

    /**
     * Determine the total number of valid segments in a waveform.
     *
     * @param waveBytes the bytes encoding the waveform heights and colors
     */
    private int totalSegments(ByteBuffer waveBytes) {
        return waveBytes.remaining() - LEADING_JUNK_BYTES;
    }

    /**
     * Determine the height of the waveform given an index into it. If we are not at full scale, we determine an
     * average starting with that segment.
     *
     * @param segment the index of the first waveform byte to examine
     * @param waveBytes the bytes encoding the waveform heights and colors
     *
     * @return a value from 0 to 31 representing the height of the waveform at that segment, which may be an average
     *         of a number of values starting there, determined by the scale
     */
    private int segmentHeight(int segment, ByteBuffer waveBytes) {
        final int scale = this.scale.get();
        int sum = 0;
        for (int i = segment; (i < segment + scale) && (i < totalSegments(waveBytes)); i++) {
            sum += waveBytes.get(i + LEADING_JUNK_BYTES) & 0x1f;
        }
        return sum / scale;
    }

    /**
     * Determine the color of the waveform given an index into it. If we are not at full scale, we determine an
     * average starting with that segment. Skips over the junk bytes at the start of the waveform.
     *
     * @param segment the index of the first waveform byte to examine
     * @param waveBytes the bytes encoding the waveform heights and colors
     *
     * @return the color of the waveform at that segment, which may be based on an average
     *         of a number of values starting there, determined by the scale
     */
    private Color segmentColor(int segment, ByteBuffer waveBytes) {
        final int scale = this.scale.get();
        int sum = 0;
        for (int i = segment; (i < segment + scale) && (i < totalSegments(waveBytes)); i++) {
            sum += (waveBytes.get(i + LEADING_JUNK_BYTES) & 0xe0) >> 5;
        }
        return COLOR_MAP[sum / scale];
    }

    @Override
    protected void paintComponent(Graphics g) {
        Rectangle clipRect = g.getClipBounds();  // We only need to draw the part that is visible or dirty
        g.setColor(Color.BLACK);  // Black out the background
        g.fillRect(clipRect.x, clipRect.y, clipRect.width, clipRect.height);

        final ByteBuffer waveBytes = (waveform.get() == null) ? null : waveform.get().getData();
        int lastBeat = 0;
        if (beatGrid.get() != null) {  // Find what beat was represented by the column just before the first we draw.
            lastBeat = beatGrid.get().findBeatAtTime(Util.halfFrameToTime(getSegmentForX(clipRect.x - 1)));
        }
        for (int x = clipRect.x; x <= clipRect.x + clipRect.width; x++) {
            final int axis = getHeight() / 2;
            final int maxHeight = axis - VERTICAL_MARGIN;
            final int segment = getSegmentForX(x);
            if (waveBytes != null) { // Drawing the waveform itself
                if ((segment >= 0) && (segment < totalSegments(waveBytes))) {
                    g.setColor(segmentColor(segment, waveBytes));
                    final int height = (segmentHeight(segment, waveBytes) * maxHeight) / 31;
                    g.drawLine(x, axis - height, x, axis + height);
                }
            }
            if (beatGrid.get() != null) {  // Draw the beat markers
                int inBeat = beatGrid.get().findBeatAtTime(Util.halfFrameToTime(segment));
                if ((inBeat > 0) && (inBeat != lastBeat)) {  // Start of a new beat, so draw it
                    g.setColor((beatGrid.get().getBeatWithinBar(inBeat) == 1)? Color.RED : Color.WHITE);
                    g.drawLine(x, axis - maxHeight - 2 - BEAT_MARKER_HEIGHT, x, axis - maxHeight - 2);
                    g.drawLine(x, axis + maxHeight + 2, x, axis + maxHeight + BEAT_MARKER_HEIGHT + 2);
                    lastBeat = inBeat;
                }
            }
        }
        g.setColor(playing.get()? PLAYBACK_MARKER_PLAYING : PLAYBACK_MARKER_STOPPED);  // Draw the playback position
        g.fillRect((getWidth() / 2) - 1, 0, PLAYBACK_MARKER_WIDTH, getHeight());
    }
}
