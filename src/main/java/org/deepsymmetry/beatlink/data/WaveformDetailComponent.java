package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger logger = LoggerFactory.getLogger(WaveformDetailComponent.class);

    /**
     * How many pixels high are the beat markers.
     */
    private static final int BEAT_MARKER_HEIGHT = 4;

    /**
     * How many pixels high are the cue markers.
     */
    private static final int CUE_MARKER_HEIGHT = 4;

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
            new Color(0, 0, 175),
            new Color(0, 52, 208),
            new Color(0, 119, 233),
            new Color(46, 205, 255),
            new Color(76, 210, 255),
            new Color(124, 218, 255),
            new Color(185, 222, 255),
            new Color(205, 233, 255)
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
     * Used to signal our animation thread to stop when we are no longer monitoring a player.
     */
    private final AtomicBoolean animating = new AtomicBoolean(false);

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
            VirtualCdj.getInstance().addUpdateListener(updateListener);
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
            try {
                TimeFinder.getInstance().start();
                if (!animating.getAndSet(true)) {
                    // Create the thread to update our position smoothly as the track plays
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            int lastPosition = getSegmentForX(0);
                            while (animating.get()) {
                                try {
                                    Thread.sleep(33);  // Animate at 30 fps
                                } catch (InterruptedException e) {
                                    logger.warn("Waveform animation thread interrupted; ending");
                                    animating.set(false);
                                }
                                setPlaybackPosition(TimeFinder.getInstance().getTimeFor(monitoredPlayer.get()));
                                int newPosition = getSegmentForX(0);
                                if (lastPosition != newPosition) {
                                    lastPosition = newPosition;
                                    repaint();
                                }
                            }
                        }
                    }).start();
                }
            } catch (Exception e) {
                logger.error("Unable to start the TimeFinder to animate the waveform detail view");
                animating.set(false);
            }
        } else {  // Stop monitoring any player
            animating.set(false);
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
            logger.debug("Got waveform detail update: {}", update);
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

    /**
     * Reacts to player status updates to reflect the current playback state.
     */
    private final DeviceUpdateListener updateListener = new DeviceUpdateListener() {
        @Override
        public void received(DeviceUpdate update) {
            if ((update instanceof CdjStatus) && (update.getDeviceNumber() == monitoredPlayer.get()) &&
                    (metadata.get() != null) && (beatGrid.get() != null)) {
                CdjStatus status = (CdjStatus) update;
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
        return new Dimension(300, 92);
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
     * Converts a time in milliseconds to the appropriate x coordinate for drawing something at that time.
     *
     * @param milliseconds the time at which something should be drawn
     *
     * @return the component x coordinate at which it should be drawn
     */
    private int millisecondsToX(long milliseconds) {
        int playHead = (getWidth() / 2) + 2;
        long offset = milliseconds - playbackPosition.get();
        return playHead + (Util.timeToHalfFrame(offset) / scale.get());
    }

    /**
     * Determine the total number of valid segments in a waveform.
     *
     * @param waveBytes the bytes encoding the waveform heights and colors
     */
    private int totalSegments(ByteBuffer waveBytes) {
        return waveBytes.remaining() - WaveformDetail.LEADING_JUNK_BYTES;
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
            sum += waveBytes.get(i + WaveformDetail.LEADING_JUNK_BYTES) & 0x1f;
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
            sum += (waveBytes.get(i + WaveformDetail.LEADING_JUNK_BYTES) & 0xe0) >> 5;
        }
        return COLOR_MAP[sum / scale];
    }

    /**
     * The largest scale at which we will draw individual beat markers; above this we show only bars.
     */
    private static final int MAX_BEAT_SCALE = 9;

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
        final int axis = getHeight() / 2;
        final int maxHeight = axis - VERTICAL_MARGIN;
        for (int x = clipRect.x; x <= clipRect.x + clipRect.width; x++) {
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
                if ((inBeat > 0) && (inBeat != lastBeat)) {  // Start of a new beat, so prepare to draw it
                    final int beatWithinBar = beatGrid.get().getBeatWithinBar(inBeat);
                    if (scale.get() <= MAX_BEAT_SCALE || beatWithinBar == 1) {
                        // Once scale gets large enough, we only draw the down beats, like CDJs.
                        g.setColor((beatWithinBar == 1) ? Color.RED : Color.WHITE);
                        g.drawLine(x, axis - maxHeight - 2 - BEAT_MARKER_HEIGHT, x, axis - maxHeight - 2);
                        g.drawLine(x, axis + maxHeight + 2, x, axis + maxHeight + BEAT_MARKER_HEIGHT + 2);
                    }
                    lastBeat = inBeat;
                }
            }
        }

        // Draw the cue points
        if (metadata.get() != null && metadata.get().getCueList() != null) {
            for (CueList.Entry entry : metadata.get().getCueList().entries) {
                final int x = millisecondsToX(entry.cueTime);
                if ((x > clipRect.x - 4) && (x < clipRect.x + clipRect.width + 4)) {
                    g.setColor((entry.hotCueNumber > 0)? Color.GREEN : Color.RED);
                    for (int i = 0; i < 4; i++) {
                        g.drawLine(x - 3 + i, axis - maxHeight - BEAT_MARKER_HEIGHT - CUE_MARKER_HEIGHT + i,
                                x + 3 - i, axis - maxHeight - BEAT_MARKER_HEIGHT - CUE_MARKER_HEIGHT + i);
                    }
                }
            }
        }

        g.setColor(playing.get()? PLAYBACK_MARKER_PLAYING : PLAYBACK_MARKER_STOPPED);  // Draw the playback position
        g.fillRect((getWidth() / 2) - 1, 0, PLAYBACK_MARKER_WIDTH, getHeight());
    }

    @Override
    public String toString() {
        return"WaveformDetailComponent[metadata=" + metadata.get() + ", waveform=" + waveform.get() + ", beatGrid=" +
                beatGrid.get() + ", playbackPosition=" + playbackPosition.get() + ", playing=" + playing.get() + ", monitoredPlayer=" +
                monitoredPlayer.get() + "]";
    }
}
