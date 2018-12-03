package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides a convenient way to draw a waveform preview in a user interface, including annotations like the
 * current time and minute markers (if you supply {@link TrackMetadata} so the total length can be determined),
 * and cue markers (if you also supply a {@link CueList}). Can also be configured to automatically update
 * itself to reflect the state of a specified player, showing the current track, playback state, and position,
 * as long as it is able to load appropriate metadata, which includes beat grids for translating beat numbers
 * into track time.
 */
@SuppressWarnings("WeakerAccess")
public class WaveformPreviewComponent extends JComponent {

    private static final Logger logger = LoggerFactory.getLogger(WaveformPreviewComponent.class);

    /**
     * The Y coordinate at which the top of cue markers is drawn.
     */
    private static final int CUE_MARKER_TOP = 4;

    /**
     * How many pixels high are the cue markers.
     */
    private static final int CUE_MARKER_HEIGHT = 4;

    /**
     * The number of pixels high the cue marker is.
     */
    private static final int POSITION_MARKER_TOP = CUE_MARKER_TOP + CUE_MARKER_HEIGHT;

    /**
     * The Y coordinate at which the top of the waveform is drawn.
     */
    private static final int WAVEFORM_TOP = POSITION_MARKER_TOP + 2;

    /**
     * Calculate the height of the waveform based on the component height.
     *
     * @return the height of the waveform.
     */
    private int waveformHeight() {
        return getHeight() - POSITION_MARKER_TOP - 9 - PLAYBACK_BAR_HEIGHT - MINUTE_MARKER_HEIGHT;
    }

    /**
     * Calculate the width of the waveform based on the component width.
     *
     * @return the width of the waveform
     */
    private int waveformWidth() {
        return getWidth() - WAVEFORM_MARGIN * 2;
    }

    /**
     * The minimum acceptable height for the waveform.
     */
    private static final int MIN_WAVEFORM_HEIGHT = 31;

    /**
     * The minimum acceptable width for the waveform.
     */
    public static final int MIN_WAVEFORM_WIDTH = 200;

    /**
     * The Y coordinate at which the top of the playback progress bar is drawn.
     */
    private int playbackBarTop() {
        return WAVEFORM_TOP + waveformHeight() + 3;
    }

    /**
     * The height of the playback progress bar.
     */
    private static final int PLAYBACK_BAR_HEIGHT = 4;

    /**
     * The Y coordinate at which the top of the minute markers are drawn.
     */
    private int minuteMarkerTop() {
        return playbackBarTop() + PLAYBACK_BAR_HEIGHT + 3;
    }

    /**
     * The height of the minute markers.
     */
    private static final int MINUTE_MARKER_HEIGHT = 4;

    /**
     * The height of the large bar showing the current playback position.
     */
    private int positionMarkerHeight() {
        return minuteMarkerTop() - POSITION_MARKER_TOP - 1;
    }

    /**
     * The X coordinate of the waveform, to give enough space for a cue marker at the start of the track.
     */
    private static final int WAVEFORM_MARGIN = 4;

    /**
     * The color for brighter sections of the already-played section of the playback progress bar.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Color BRIGHT_PLAYED = new Color(75, 75, 75);

    /**
     * The color for darker sections of the already-played section of the playback progress bar.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Color DIM_PLAYED = new Color(35, 35, 35);

    /**
     * The color for the darker sections of hte not-yet-played sections of the playback progress bar.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Color DIM_UNPLAYED = new Color(170, 170, 170);

    /**
     * If not zero, automatically update the waveform, position, and metadata in response to the activity of the
     * specified player number.
     */
    private final AtomicInteger monitoredPlayer = new AtomicInteger(0);

    /**
     * The waveform preview that we are drawing.
     */
    private final AtomicReference<WaveformPreview> preview = new AtomicReference<WaveformPreview>();

    /**
     * The rendered image of the waveform itself at its natural size.
     */
    private final AtomicReference<Image> waveformImage = new AtomicReference<Image>();

    /**
     * Track the current playback position in milliseconds.
     */
    private final AtomicLong playbackPosition = new AtomicLong(0);

    /**
     * Track whether the player holding the waveform is currently playing.
     */
    private final AtomicBoolean playing = new AtomicBoolean(false);

    /**
     * Information about the track whose waveform we are drawing, so we can translate times into positions.
     */
    private final AtomicReference<TrackMetadata> metadata = new AtomicReference<TrackMetadata>();

    /**
     * Information about where all the beats in the track fall, so we can figure out our current position from
     * player updates.
     */
    private final AtomicReference<BeatGrid> beatGrid = new AtomicReference<BeatGrid>();

    /**
     * Set the current playback position. Will cause part of the component to be redrawn if the position has
     * changed (and we have the {@link TrackMetadata} we need to translate the time into a position in the
     * component). This will be quickly overruled if a player is being monitored, but
     * can be used in other contexts.
     *
     * @param milliseconds how far into the track has been played
     */
    public void setPlaybackPosition(long milliseconds) {
        if ((metadata.get() !=  null) && (playbackPosition.get() != milliseconds)) {
            int left;
            int right;
            final int width = waveformWidth() + 8;
            if (milliseconds > playbackPosition.get()) {
                left = Math.max(0, Math.min(width, millisecondsToX(playbackPosition.get()) - 6));
                right = Math.max(0, Math.min(width, millisecondsToX(milliseconds) + 6));
            } else {
                left = Math.max(0, Math.min(width, millisecondsToX(milliseconds) - 6));
                right = Math.max(0, Math.min(width, millisecondsToX(playbackPosition.get()) + 6));
            }
            playbackPosition.set(milliseconds);
            repaint(left, 0, right - left, getHeight());
        } else {
            playbackPosition.set(milliseconds);  // Just set, don't attempt to draw anything
        }
    }

    /**
     * Set whether the player holding the waveform is playing, which changes the indicator color to white from red.
     *
     * @param playing if {@code true}, draw the position marker in white, otherwise red
     */
    public void setPlaying(boolean playing) {
        final boolean oldValue = this.playing.getAndSet(playing);
        if ((metadata.get() != null) && oldValue != playing) {
            int left = Math.max(0, Math.min(waveformWidth() + 8, millisecondsToX(playbackPosition.get()) - 2));
            repaint(left, 0, 4, getHeight());
        }
    }

    /**
     * Create an image of the proper size to hold a new waveform preview image and draw it.
     */
    private void updateWaveform(WaveformPreview preview) {
        this.preview.set(preview);
        if (preview == null) {
            waveformImage.set(null);
        } else {
            BufferedImage image = new BufferedImage(preview.segmentCount, preview.maxHeight, BufferedImage.TYPE_INT_RGB);
            Graphics g = image.getGraphics();
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, preview.segmentCount, preview.maxHeight);
            for (int segment = 0; segment < preview.segmentCount; segment++) {
                g.setColor(preview.segmentColor(segment, false));
                g.drawLine(segment, preview.maxHeight, segment, preview.maxHeight - preview.segmentHeight(segment, false));
                if (preview.isColor) {  // We have a front color segment to draw on top.
                    g.setColor(preview.segmentColor(segment, true));
                    g.drawLine(segment, preview.maxHeight, segment, preview.maxHeight - preview.segmentHeight(segment, true));
                }
            }
            waveformImage.set(image);
        }
    }

    /**
     * Change the waveform preview being drawn. This will be quickly overruled if a player is being monitored, but
     * can be used in other contexts.
     *
     * @param preview the waveform preview to display
     * @param metadata information about the track whose waveform we are drawing, so we can translate times into
     *                 positions
     */
    public void setWaveformPreview(WaveformPreview preview, TrackMetadata metadata) {
        updateWaveform(preview);
        this.metadata.set(metadata);
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
            MetadataFinder.getInstance().addTrackMetadataListener(metadataListener);
            if (MetadataFinder.getInstance().isRunning()) {
                metadata.set(MetadataFinder.getInstance().getLatestMetadataFor(player));
            } else {
                metadata.set(null);
            }
            WaveformFinder.getInstance().addWaveformListener(waveformListener);
            if (WaveformFinder.getInstance().isRunning()) {
                updateWaveform(WaveformFinder.getInstance().getLatestPreviewFor(player));
            } else {
                updateWaveform(null);
            }
            BeatGridFinder.getInstance().addBeatGridListener(beatGridListener);
            if (BeatGridFinder.getInstance().isRunning()) {
                beatGrid.set(BeatGridFinder.getInstance().getLatestBeatGridFor(player));
            } else {
                beatGrid.set(null);
            }
            VirtualCdj.getInstance().addUpdateListener(updateListener);
            try {
                TimeFinder.getInstance().start();
                if (!animating.getAndSet(true)) {
                    // Create the thread to update our position smoothly as the track plays
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            while (animating.get()) {
                                try {
                                    Thread.sleep(33);  // Animate at 30 fps
                                } catch (InterruptedException e) {
                                    logger.warn("Waveform animation thread interrupted; ending");
                                    animating.set(false);
                                }
                                setPlaybackPosition(TimeFinder.getInstance().getTimeFor(monitoredPlayer.get()));
                            }
                        }
                    }).start();
                }
            } catch (Exception e) {
                logger.error("Unable to start the TimeFinder to animate the waveform preview");
                animating.set(false);
            }
        } else {  // Stop monitoring any player
            animating.set(false);
            VirtualCdj.getInstance().removeUpdateListener(updateListener);
            MetadataFinder.getInstance().removeTrackMetadataListener(metadataListener);
            WaveformFinder.getInstance().removeWaveformListener(waveformListener);
            metadata.set(null);
            updateWaveform(null);
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
            if (update.player == monitoredPlayer.get()) {
                updateWaveform(update.preview);
                repaint();
            }
        }

        @Override
        public void detailChanged(WaveformDetailUpdate update) {
            // Nothing to do.
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
    public WaveformPreviewComponent(int player) {
        setMonitoredPlayer(player);
    }

    /**
     * Create a view which draws a specific waveform, even if it is not currently loaded in a player.
     *
     * @param preview the waveform preview to display
     * @param metadata information about the track whose waveform we are drawing, so we can translate times into
     *                 positions
     */
    public WaveformPreviewComponent(WaveformPreview preview, TrackMetadata metadata) {
        updateWaveform(preview);
        this.metadata.set(metadata);
    }

    /**
     * Calculates the total height needed to draw the component for a given waveform height.
     *
     * @param waveformHeight the height of the waveform being previewed
     *
     * @return the total height needed to render the component
     */
    private int heightGivenWaveformHeight(int waveformHeight) {
        return waveformHeight + POSITION_MARKER_TOP + 9 + PLAYBACK_BAR_HEIGHT + MINUTE_MARKER_HEIGHT;
    }

    /**
     * Calculates the total height needed to draw the component for a given waveform width.
     *
     * @param waveformWidth the width of the waveform being previewed
     *
     * @return the total width needed to render the component
     */
    private int widthGivenWaveformWidth(int waveformWidth) {
        return waveformWidth + WAVEFORM_MARGIN * 2;
    }

    @Override
    public Dimension getPreferredSize() {
        int wavePreferredHeight = preview.get() == null? MIN_WAVEFORM_HEIGHT : preview.get().maxHeight;
        int wavePreferredWidth = preview.get() == null? 400 : preview.get().segmentCount;

        return new Dimension(widthGivenWaveformWidth(wavePreferredWidth), heightGivenWaveformHeight(wavePreferredHeight));
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(widthGivenWaveformWidth(MIN_WAVEFORM_WIDTH), heightGivenWaveformHeight(MIN_WAVEFORM_HEIGHT));
    }

    /**
     * Converts a time in milliseconds to the appropriate x coordinate for drawing something at that time.
     * Can only be called when we have {@link TrackMetadata}.
     *
     * @param milliseconds the time at which something should be drawn
     *
     * @return the component x coordinate at which it should be drawn
     */
    private int millisecondsToX(long milliseconds) {
        long duration = metadata.get().getDuration();
        if (duration < 1) {  // Don't crash on goofy metadata
            return 0;
        }
        long result = milliseconds * waveformWidth() / (metadata.get().getDuration() * 1000);
        return WAVEFORM_MARGIN + Math.max(0, Math.min(waveformWidth(), (int) result));
    }

    @Override
    protected synchronized void paintComponent(Graphics g) {

        Rectangle clipRect = g.getClipBounds();  // We only need to draw the part that is visible or dirty
        g.setColor(Color.BLACK);  // Black out the background
        g.fillRect(clipRect.x, clipRect.y, clipRect.width, clipRect.height);

        // Draw our precomputed waveform image scaled and positioned to fit the component
        Image image = waveformImage.get();
        if (image != null) {
            g.drawImage(image, WAVEFORM_MARGIN, WAVEFORM_TOP, waveformWidth(), waveformHeight(), null);
        }

        // Draw the other preview elements that are visible or dirty
        for (int x = clipRect.x; x <= clipRect.x + clipRect.width; x++) {
            final int segment = x - WAVEFORM_MARGIN;
            if ((segment >= 0) && (segment < waveformWidth())) {
                if (metadata.get() != null) { // Draw the playback progress bar
                    if (x < millisecondsToX(playbackPosition.get()) - 1) {  // The played section
                        g.setColor((x % 2 == 0)? BRIGHT_PLAYED : DIM_PLAYED);
                        if (x == WAVEFORM_MARGIN) {
                            g.drawLine(x, playbackBarTop(), x, playbackBarTop() + PLAYBACK_BAR_HEIGHT);
                        } else {
                            g.drawLine(x, playbackBarTop(), x, playbackBarTop());
                            g.drawLine(x, playbackBarTop() + PLAYBACK_BAR_HEIGHT, x, playbackBarTop() + PLAYBACK_BAR_HEIGHT);
                        }
                    } else if (x > millisecondsToX(playbackPosition.get()) + 1) {  // The unplayed section
                        g.setColor((x % 2 == 0)? Color.WHITE : DIM_UNPLAYED);
                        g.drawLine(x, playbackBarTop(), x, playbackBarTop() + PLAYBACK_BAR_HEIGHT);
                    }
                }
            }
        }

        if (metadata.get() != null) {  // Draw the minute marks and playback position
            g.setColor(Color.WHITE);
            for (int time = 60; time < metadata.get().getDuration(); time += 60) {
                final int x = millisecondsToX(time * 1000);
                g.drawLine(x, minuteMarkerTop(), x, minuteMarkerTop() + MINUTE_MARKER_HEIGHT);
            }
            final int x = millisecondsToX(playbackPosition.get());
            if (!playing.get()) {
                g.setColor(Color.RED);
            }
            g.fillRect(x - 1, POSITION_MARKER_TOP, 2, positionMarkerHeight());
        }

        // Finally, draw the cue points, first the ordinary memory points and then the hot cues, since sometimes
        // they are in the same place and we want the hot cues to stand out.
        if (metadata.get() != null && metadata.get().getCueList() != null) {
            drawCueList(g, clipRect, false);
            drawCueList(g, clipRect, true);
        }
    }

    /**
     * Draw the visible memory cue points or hot cues.
     *
     * @param g the graphics object in which we are being rendered
     * @param clipRect the region that is being currently rendered
     * @param hot true if we should draw hot cues, otherwise we draw memory points
     */
    private void drawCueList(Graphics g, Rectangle clipRect, boolean hot) {
        for (CueList.Entry entry : metadata.get().getCueList().entries) {
            if ((hot && entry.hotCueNumber > 0) || (entry.hotCueNumber == 0 && !hot)) {
                final int x = millisecondsToX(entry.cueTime);
                if ((x > clipRect.x - 4) && (x < clipRect.x + clipRect.width + 4)) {
                    g.setColor(WaveformDetailComponent.cueColor(entry));
                    for (int i = 0; i < 4; i++) {
                        g.drawLine(x - 3 + i, CUE_MARKER_TOP + i, x + 3 - i, CUE_MARKER_TOP + i);
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return"WaveformPreviewComponent[metadata=" + metadata.get() + ", waveformPreview=" + preview.get() + ", beatGrid=" +
                beatGrid.get() + ", playbackPosition=" + playbackPosition.get() + ", playing=" + playing.get() + ", monitoredPlayer=" +
                monitoredPlayer.get() + "]";
    }
}
