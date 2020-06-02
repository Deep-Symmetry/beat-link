package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
     * The color for brighter sections of the already-played section of the playback progress bar. Note that
     * if the indicator color has been changed, only the transparency (alpha) channel of this is used.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Color BRIGHT_PLAYED = new Color(255, 255, 255, 75);

    /**
     * The color for darker sections of the already-played section of the playback progress bar. Note that
     * if the indicator color has been changed, only the transparency (alpha) channel of this is used.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Color DIM_PLAYED = new Color(255, 255, 255, 35);

    /**
     * The color for the darker sections of the not-yet-played sections of the playback progress bar. Note that
     * if the indicator color has been changed, only the transparency (alpha) channel of this is used.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Color DIM_UNPLAYED = new Color(255, 255, 255, 170);

    /**
     * If not zero, automatically update the waveform, position, and metadata in response to the activity of the
     * specified player number.
     */
    private final AtomicInteger monitoredPlayer = new AtomicInteger(0);

    /**
     * The color to which the background is cleared before drawing the waveform. The default is black,
     * but can be changed (including to a transparent color) for use in other contexts, like the OBS overlay.
     */
    private final AtomicReference<Color> backgroundColor = new AtomicReference<Color>(Color.BLACK);

    /**
     * The color with which the playback position and tick markers are drawn. The default is white,
     * but can be changed (including to a transparent color) for use in other contexts, like the OBS overlay.
     */
    private final AtomicReference<Color> indicatorColor = new AtomicReference<Color>(Color.WHITE);

    /**
     * The color with which the playback position is drawn while playback is active. The default is red,
     * but can be changed (including to a transparent color) for use in other contexts, like the OBS overlay.
     */
    private final AtomicReference<Color> emphasisColor = new AtomicReference<Color>(Color.RED);

    /**
     * The waveform preview that we are drawing.
     */
    private final AtomicReference<WaveformPreview> preview = new AtomicReference<WaveformPreview>();

    /**
     * The rendered image of the waveform itself at its natural size.
     */
    private final AtomicReference<Image> waveformImage = new AtomicReference<Image>();

    /**
     * Track the playback state for the players that have the track loaded.
     */
    private final Map<Integer, PlaybackState> playbackStateMap = new ConcurrentHashMap<Integer, PlaybackState>(4);

    /**
     * Information about the playback duration of the track whose waveform we are drawing, so we can translate times
     * into positions.
     */
    private final AtomicInteger duration = new AtomicInteger(0);

    /**
     * Information about where all the beats in the track fall, so we can figure out our current position from
     * player updates.
     */
    private final AtomicReference<BeatGrid> beatGrid = new AtomicReference<BeatGrid>();

    /**
     * Information about where all the cues are so we can draw them.
     */
    private final AtomicReference<CueList> cueList = new AtomicReference<CueList>();

    /**
     * Get the cue list associated with this track.
     *
     * @return the cues defined for the track.
     */
    public CueList getCueList() {
        return cueList.get();
    }

    /**
     * The overlay painter that has been registered, if any.
     */
    private final AtomicReference<OverlayPainter> overlayPainter = new AtomicReference<OverlayPainter>();

    /**
     * Arrange for an overlay to be painted on top of the component.
     *
     * @param painter if not {@code null}, its {@link OverlayPainter#paintOverlay(Component, Graphics)} method will
     *                be called once this component has done its own painting
     */
    public void setOverlayPainter(OverlayPainter painter) {
        overlayPainter.set(painter);
    }

    /**
     * Examine the color to which the background is cleared before drawing the waveform. The default is black,
     * but can be changed (including to a transparent color) for use in other contexts, like the OBS overlay.
     *
     * @return the color used to draw the component background
     */
    public Color getBackgroundColor() {
        return backgroundColor.get();
    }

    /**
     * Change the color to which the background is cleared before drawing the waveform. The default is black,
     * but can be changed (including to a transparent color) for use in other contexts, like the OBS overlay.
     *
     * @param color the color used to draw the component background
     */
    public void setBackgroundColor(Color color) {
        backgroundColor.set(color);
        updateWaveform(preview.get());
    }

    /**
     * Examine the color with which the playback position and tick markers are drawn. The default is white,
     * but can be changed (including to a transparent color) for use in other contexts, like the OBS overlay.
     *
     * @return the color used to draw the playback and tick markers
     */
    public Color getIndicatorColor() {
        return indicatorColor.get();
    }

    /**
     * Change the color with which the playback position and tick markers are drawn. The default is white,
     * but can be changed (including to a transparent color) for use in other contexts, like the OBS overlay.

     * @param color the color used to draw the playback marker when actively playing
     */
    public void setIndicatorColor(Color color) {
        indicatorColor.set(color);
    }

    /**
     * Examine the color with which the playback position is drawn when playback is active. The default is red,
     * but can be changed (including to a transparent color) for use in other contexts, like the OBS overlay.
     *
     * @return the color used to draw the active playback marker
     */
    public Color getEmphasisColor() {
        return emphasisColor.get();
    }

    /**
     * Change the color with which the playback position is drawn when playback is active. The default is red,
     * but can be changed (including to a transparent color) for use in other contexts, like the OBS overlay.

     * @param color the color used to draw the playback marker when actively playing
     */
    public void setEmphasisColor(Color color) {
        emphasisColor.set(color);
    }

    /**
     * Look up the playback state that has reached furthest in the track. This is used to render the “played until”
     * graphic below the preview.
     *
     * @return the playback state, if any, with the highest {@link PlaybackState#position} value
     */
    public PlaybackState getFurthestPlaybackState() {
        PlaybackState result = null;
        for (PlaybackState state : playbackStateMap.values()) {
            if (result == null || result.position < state.position) {
                result = state;
            }
        }
        return result;
    }

    /**
     * Keep track of whether we are supposed to be delegating our repaint calls to a host component.
     */
    private final AtomicReference<RepaintDelegate> repaintDelegate = new AtomicReference<RepaintDelegate>();

    /**
     * Establish a host component to which all {@link #repaint(int, int, int, int)} calls should be delegated,
     * presumably because we are being soft-loaded in a large user interface to save on memory.
     *
     * @param delegate the permanent component that can actually accumulate repaint regions, or {@code null} if
     *                 we are being hosted normally in a container, so we should use the normal repaint process.
     */
    public void setRepaintDelegate(RepaintDelegate delegate) {
        repaintDelegate.set(delegate);
    }

    /**
     * Determine whether we should use the normal repaint process, or delegate that to another component that is
     * hosting us in a soft-loaded manner to save memory.
     *
     * @param x the left edge of the region that we want to have redrawn
     * @param y the top edge of the region that we want to have redrawn
     * @param width the width of the region that we want to have redrawn
     * @param height the height of the region that we want to have redrawn
     */
    @SuppressWarnings("SameParameterValue")
    private void delegatingRepaint(int x, int y, int width, int height) {
        final RepaintDelegate delegate = repaintDelegate.get();
        if (delegate != null) {
            //logger.info("Delegating repaint: " + x + ", " + y + ", " + width + ", " + height);
            delegate.repaint(x, y, width, height);
        } else {
            //logger.info("Normal repaint: " + x + ", " + y + ", " + width + ", " + height);
            repaint(x, y, width, height);
        }
    }

    /**
     * Helper method to mark the parts of the component that need repainting due to a change to the
     * tracked playback positions.
     *
     * @param oldMaxPosition The furthest playback position in our previous state
     * @param newMaxPosition The furthest playback position in our new state
     * @param oldState the old position of a marker being moved, or {@code null} if we are adding a marker
     * @param newState the new position of a marker being moved, or {@code null} if we are removing a marker
     */
    private void repaintDueToPlaybackStateChange(long oldMaxPosition, long newMaxPosition,
                                                 PlaybackState oldState, PlaybackState newState) {
        if (duration.get() > 0) {  // We are only drawing markers if we know the track duration
            final int width = waveformWidth() + 8;

            // See if we need to redraw a stretch of the “played until” stripe.
            if (oldMaxPosition > newMaxPosition) {
                final int left = Math.max(0, Math.min(width, millisecondsToX(newMaxPosition) - 6));
                final int right = Math.max(0, Math.min(width, millisecondsToX(oldMaxPosition) + 6));
                delegatingRepaint(left, 0, right - left, getHeight());
            } else if (newMaxPosition > oldMaxPosition) {
                final int left = Math.max(0, Math.min(width, millisecondsToX(oldMaxPosition) - 6));
                final int right = Math.max(0, Math.min(width, millisecondsToX(newMaxPosition) + 6));
                delegatingRepaint(left, 0, right - left, getHeight());
            }

            // Also refresh where the specific marker was moved from and/or to.
            if (oldState != null && (newState == null || newState.position != oldState.position)) {
                final int left = Math.max(0, Math.min(width, millisecondsToX(oldState.position) - 6));
                final int right = Math.max(0, Math.min(width, millisecondsToX(oldState.position) + 6));
                delegatingRepaint(left, 0, right - left, getHeight());
            }
            if (newState != null && (oldState == null || newState.position != oldState.position)) {
                final int left = Math.max(0, Math.min(width, millisecondsToX(newState.position) - 6));
                final int right = Math.max(0, Math.min(width, millisecondsToX(newState.position) + 6));
                delegatingRepaint(left, 0, right - left, getHeight());
            }
        }
    }

    /**
     * Set the current playback state for a player.
     *
     * Will cause part of the component to be redrawn if the player state has
     * changed (and we have the {@link TrackMetadata} we need to translate the time into a position in the
     * component). This will be quickly overruled if a player is being monitored, but
     * can be used in other contexts.
     *
     * @param player the player number whose playback state is being recorded
     * @param position the current playback position of that player in milliseconds
     * @param playing whether the player is actively playing the track
     *
     * @throws IllegalStateException if the component is configured to monitor a player, and this is called
     *         with state for a different player
     * @throws IllegalArgumentException if player is less than one
     *
     * @since 0.5.0
     */
    public synchronized void setPlaybackState(int player, long position, boolean playing) {
        if (getMonitoredPlayer() != 0 && player != getMonitoredPlayer()) {
            throw new IllegalStateException("Cannot setPlaybackState for another player when monitoring player " + getMonitoredPlayer());
        }
        if (player < 1) {
            throw new IllegalArgumentException("player must be positive");
        }
        long oldMaxPosition = 0;
        PlaybackState furthestState = getFurthestPlaybackState();
        if (furthestState != null) {
            oldMaxPosition = furthestState.position;
        }
        PlaybackState newState = new PlaybackState(player, position, playing);
        PlaybackState oldState = playbackStateMap.put(player, newState);
        long newMaxPosition = 0;
        furthestState = getFurthestPlaybackState();
        if (furthestState != null) {
            newMaxPosition = furthestState.position;
        }
        repaintDueToPlaybackStateChange(oldMaxPosition, newMaxPosition, oldState, newState);
    }

    /**
     * Clear the playback state stored for a player, such as when it has unloaded the track.
     *
     * @param player the player number whose playback state is no longer valid
     * @since 0.5.0
     */
    public synchronized void clearPlaybackState(int player) {
        long oldMaxPosition = 0;
        PlaybackState furthestState = getFurthestPlaybackState();
        if (furthestState != null) {
            oldMaxPosition = furthestState.position;
        }
        PlaybackState oldState = playbackStateMap.remove(player);
        long newMaxPosition = 0;
        furthestState = getFurthestPlaybackState();
        if (furthestState != null) {
            newMaxPosition = furthestState.position;
        }
        repaintDueToPlaybackStateChange(oldMaxPosition, newMaxPosition, oldState, null);
    }

    /**
     * Removes all stored playback state.
     * @since 0.5.0
     */
    public synchronized void clearPlaybackState() {
        for (PlaybackState state : playbackStateMap.values()) {
            clearPlaybackState(state.player);
        }
    }

    /**
     * Look up the playback state recorded for a particular player.
     *
     * @param player the player number whose playback state information is desired
     * @return the corresponding playback state, if any has been stored
     * @since 0.5.0
     */
    public PlaybackState getPlaybackState(int player) {
        return playbackStateMap.get(player);
    }

    /**
     * Look up all recorded playback state information.
     *
     * @return the playback state recorded for any player
     * @since 0.5.0
     */
    public Set<PlaybackState> getPlaybackState() {
        Set<PlaybackState> result = new HashSet<PlaybackState>(playbackStateMap.values());
        return Collections.unmodifiableSet(result);
    }

    /**
     * Helper method to find the single current playback state when used in single-player mode.
     *
     * @return either the single stored playback state
     */
    private PlaybackState currentSimpleState() {
        if (!playbackStateMap.isEmpty()) {  // Avoid exceptions during animation loop shutdown.
            return playbackStateMap.values().iterator().next();
        }
        return null;
    }

    /**
     * Set the current playback position. This method can only be used in situations where the component is
     * tied to a single player, and therefore always has a single playback position.
     *
     * Will cause part of the component to be redrawn if the position has
     * changed (and we have the {@link TrackMetadata} we need to translate the time into a position in the
     * component). This will be quickly overruled if a player is being monitored, but
     * can be used in other contexts.
     *
     * @param milliseconds how far into the track has been played
     *
     * @see #setPlaybackState
     */
    private void setPlaybackPosition(long milliseconds) {
        PlaybackState oldState = currentSimpleState();
        if (oldState != null && oldState.position != milliseconds) {
            setPlaybackState(oldState.player, milliseconds, oldState.playing);
        }
    }

    /**
     * Set whether the player holding the waveform is playing, which changes the indicator color to white from red
     * (assuming the indicator and emphasis colors have not changed).
     *
     * This method can only be used in situations where the component is tied to a single player, and therefore has
     * a single playback position.
     *
     * @param playing if {@code true}, draw the position marker in the indicator color, otherwise the emphasis color
     *
     * @see #setPlaybackState
     */
    private void setPlaying(boolean playing) {
        PlaybackState oldState = currentSimpleState();
        if (oldState != null && oldState.playing != playing) {
            setPlaybackState(oldState.player, oldState.position, playing);
        }
    }

    private static final Color TRANSPARENT = new Color(0,0,0,0);

    /**
     * Create an image of the proper size to hold a new waveform preview image and draw it.
     */
    private void updateWaveform(WaveformPreview preview) {
        this.preview.set(preview);
        if (preview == null) {
            waveformImage.set(null);
        } else {
            BufferedImage image = new BufferedImage(preview.segmentCount, preview.maxHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics g = image.getGraphics();
            g.setColor(TRANSPARENT);
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
     *                 positions and display hot cues and memory points
     */
    public void setWaveformPreview(WaveformPreview preview, TrackMetadata metadata) {
        updateWaveform(preview);
        this.duration.set(metadata.getDuration());
        this.cueList.set(metadata.getCueList());
        clearPlaybackState();
        repaint();
    }

    /**
     * Change the waveform preview being drawn. This will be quickly overruled if a player is being monitored, but
     * can be used in other contexts.
     *
     * @param preview the waveform preview to display
     * @param duration the playback duration, in seconds, of the track whose waveform we are drawing, so we can
     *                 translate times into positions
     * @param cueList the hot cues and memory points stored for the track, if any, so we can draw them
     */
    public void setWaveformPreview(WaveformPreview preview, int duration, CueList cueList) {
        updateWaveform(preview);
        this.duration.set(duration);
        this.cueList.set(cueList);
        clearPlaybackState();
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
        clearPlaybackState();
        monitoredPlayer.set(player);
        if (player > 0) {  // Start monitoring the specified player
            setPlaybackState(player, 0, false);  // Start with default values for required simple state.
            MetadataFinder.getInstance().addTrackMetadataListener(metadataListener);
            TrackMetadata knownMetadata = null;
            if (MetadataFinder.getInstance().isRunning()) {
                knownMetadata = MetadataFinder.getInstance().getLatestMetadataFor(player);
            }
            if (knownMetadata == null) {
                duration.set(0);  // We don't know the duration, so we can’t draw markers.
                cueList.set(null);
            } else {
                duration.set(knownMetadata.getDuration());
                cueList.set(knownMetadata.getCueList());
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
                                    //noinspection BusyWait
                                    Thread.sleep(33);  // Animate at 30 fps
                                } catch (InterruptedException e) {
                                    logger.warn("Waveform animation thread interrupted; ending");
                                    animating.set(false);
                                }
                                setPlaybackPosition(TimeFinder.getInstance().getTimeFor(getMonitoredPlayer()));
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
            duration.set(0);
            updateWaveform(null);
            beatGrid.set(null);
        }
        repaint();
    }

    /**
     * See which player is having its state tracked automatically by the component, if any.
     *
     * @return the player number being monitored, or zero if none
     */
    public int getMonitoredPlayer() {
        return monitoredPlayer.get();
    }

    /**
     * Reacts to changes in the track metadata associated with the player we are monitoring.
     */
    private final TrackMetadataListener metadataListener = new TrackMetadataListener() {
        @Override
        public void metadataChanged(TrackMetadataUpdate update) {
            if (update.player == getMonitoredPlayer()) {
                if (update.metadata != null) {
                    duration.set(update.metadata.getDuration());
                    cueList.set(update.metadata.getCueList());
                } else {
                    duration.set(0);
                    cueList.set(null);
                }
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
            if (update.player == getMonitoredPlayer()) {
                updateWaveform(update.preview);
                repaint();
            }
        }

        @SuppressWarnings("EmptyMethod")
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
            if (update.player == getMonitoredPlayer()) {
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
            if ((update instanceof CdjStatus) && (update.getDeviceNumber() == getMonitoredPlayer()) &&
                    (duration.get() > 0) && (beatGrid.get() != null)) {
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
        this.duration.set(metadata.getDuration());
    }

    /**
     * Create a view which draws a specific waveform, even if it is not currently loaded in a player.
     *
     * @param preview the waveform preview to display
     * @param duration the playback duration, in seconds, of the track whose waveform we are drawing, so we can
     *                 translate times into positions
     * @param cueList the hot cues and memory points stored for the track, if any, so we can draw them
     */
    public WaveformPreviewComponent(WaveformPreview preview, int duration, CueList cueList) {
        updateWaveform(preview);
        this.duration.set(duration);
        this.cueList.set(cueList);
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
    public int millisecondsToX(long milliseconds) {
        if (duration.get() < 1) {  // Don't crash if we are missing duration information.
            return 0;
        }
        long result = milliseconds * waveformWidth() / (duration.get() * 1000);
        return WAVEFORM_MARGIN + Math.max(0, Math.min(waveformWidth(), (int) result));
    }

    /**
     * Determine the playback time that corresponds to a particular X coordinate in the component given the current
     * scale.
     * @param x the horizontal position within the component coordinate space
     * @return the number of milliseconds into the track this would correspond to (may fall outside the actual track)
     */
    public long getTimeForX(int x) {
        if (duration.get() < 1) {  // Don't crash if we are missing duration information.
            return 0;
        }
        return (x - WAVEFORM_MARGIN) * duration.get() * 1000 / waveformWidth();
    }

    @Override
    protected synchronized void paintComponent(Graphics g) {

        Rectangle clipRect = g.getClipBounds();  // We only need to draw the part that is visible or dirty
        g.setColor(backgroundColor.get());  // Clear the background
        g.fillRect(clipRect.x, clipRect.y, clipRect.width, clipRect.height);

        // Draw our precomputed waveform image scaled and positioned to fit the component
        Image image = waveformImage.get();
        if (image != null) {
            g.drawImage(image, WAVEFORM_MARGIN, WAVEFORM_TOP, waveformWidth(), waveformHeight(), null);
        }

        // Draw the other preview elements that are visible or dirty
        final Color brightPlayed = Util.buildColor(indicatorColor.get(), BRIGHT_PLAYED);
        final Color dimPlayed = Util.buildColor(indicatorColor.get(), DIM_PLAYED);
        final Color dimUnplayed = Util.buildColor(indicatorColor.get(), DIM_UNPLAYED);
        for (int x = clipRect.x; x <= clipRect.x + clipRect.width; x++) {
            final int segment = x - WAVEFORM_MARGIN;
            if ((segment >= 0) && (segment < waveformWidth())) {
                if (duration.get() > 0) { // Draw the playback progress bar
                    long maxPosition = 0;
                    final PlaybackState furthestState = getFurthestPlaybackState();
                    if (furthestState != null) {
                        maxPosition = furthestState.position;
                    }
                    if (x < millisecondsToX(maxPosition) - 1) {  // The played section
                        g.setColor((x % 2 == 0)? brightPlayed : dimPlayed);
                        if (x == WAVEFORM_MARGIN) {
                            g.drawLine(x, playbackBarTop(), x, playbackBarTop() + PLAYBACK_BAR_HEIGHT);
                        } else {
                            g.drawLine(x, playbackBarTop(), x, playbackBarTop());
                            g.drawLine(x, playbackBarTop() + PLAYBACK_BAR_HEIGHT, x, playbackBarTop() + PLAYBACK_BAR_HEIGHT);
                        }
                    } else if (x > millisecondsToX(maxPosition) + 1) {  // The unplayed section
                        g.setColor((x % 2 == 0)? indicatorColor.get() : dimUnplayed);
                        g.drawLine(x, playbackBarTop(), x, playbackBarTop() + PLAYBACK_BAR_HEIGHT);
                    }
                }
            }
        }

        if (duration.get() > 0) {  // Draw the minute marks and playback position
            g.setColor(indicatorColor.get());
            for (int time = 60; time < duration.get(); time += 60) {
                final int x = millisecondsToX(time * 1000);
                g.drawLine(x, minuteMarkerTop(), x, minuteMarkerTop() + MINUTE_MARKER_HEIGHT);
            }

            // Draw the non-playing markers first, so the playing ones will be seen if they are in the same spot.
            g.setColor(Util.buildColor(indicatorColor.get(), WaveformDetailComponent.PLAYBACK_MARKER_STOPPED));
            for (PlaybackState state : playbackStateMap.values()) {
                if (!state.playing) {
                    g.fillRect(millisecondsToX(state.position) - 1, POSITION_MARKER_TOP, 2, positionMarkerHeight());
                }
            }

            // Then draw the playing markers on top of the non-playing ones.
            g.setColor(Util.buildColor(emphasisColor.get(), WaveformDetailComponent.PLAYBACK_MARKER_PLAYING));
            for (PlaybackState state : playbackStateMap.values()) {
                if (state.playing) {
                    g.fillRect(millisecondsToX(state.position) - 1, POSITION_MARKER_TOP, 2, positionMarkerHeight());
                }
            }
        }

        // Draw the cue points.
        if (cueList.get() != null) {
            drawCueList(g, clipRect);
        }

        // Finally, if an overlay painter has been attached, let it paint its overlay.
        OverlayPainter painter = overlayPainter.get();
        if (painter != null) {
            painter.paintOverlay(this, g);
        }
    }

    /**
     * Draw the visible memory cue points or hot cues.
     *
     * @param g the graphics object in which we are being rendered
     * @param clipRect the region that is being currently rendered
     */
    private void drawCueList(Graphics g, Rectangle clipRect) {
        for (CueList.Entry entry : cueList.get().entries) {
            final int x = millisecondsToX(entry.cueTime);
            if ((x > clipRect.x - 4) && (x < clipRect.x + clipRect.width + 4)) {
                g.setColor(entry.getColor());
                for (int i = 0; i < 4; i++) {
                    g.drawLine(x - 3 + i, CUE_MARKER_TOP + i, x + 3 - i, CUE_MARKER_TOP + i);
                }
            }
        }
    }

    @Override
    public String toString() {
        return"WaveformPreviewComponent[duration=" + duration.get() + ", waveformPreview=" + preview.get() +
                ", beatGrid=" + beatGrid.get() + ", cueList=" + cueList.get() + ", playbackStateMap=" +
                playbackStateMap + ", monitoredPlayer=" + getMonitoredPlayer() + "]";
    }
}
