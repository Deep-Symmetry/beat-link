package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.*;

import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.geom.Rectangle2D;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
     * The color to draw the playback position when playing; a slightly transparent red. Note that if the indicator
     * color has been changed, only the transparency from this is used.
     *
     * @see #getIndicatorColor()
     */
    static final Color PLAYBACK_MARKER_PLAYING = new Color(255, 0, 0, 235);

    /**
     * The color to draw the playback position when stopped; a slightly transparent white. Note that if the indicator
     * color has been changed, only the transparency from this is used.
     *
     * @see #getIndicatorColor()
     */
    static final Color PLAYBACK_MARKER_STOPPED = new Color(255, 255, 255, 235);

    /**
     * The color drawn behind sections of the waveform which represent loops.
     */
    private static final Color LOOP_BACKGROUND = new Color(204, 121, 29);

    /**
     * The transparency with which phrase bars are drawn.
     */
    private static final Color PHRASE_TRANSPARENCY = new Color(255, 255, 255, 220);

   /**
     * If not zero, automatically update the waveform, position, and metadata in response to the activity of the
     * specified player number.
     */
    private final AtomicInteger monitoredPlayer = new AtomicInteger(0);

    /**
     * Determines how we decide what to draw. The default behavior is to draw as much of the waveform as fits
     * within our current size at the current scale around the current playback position (or, if we are tracking
     * multiple players, the furthest playback position, prioritizing active players even if they are not as far as
     * an inactive player). If this is changed to {@code false} then changing the scale actually changes the size
     * of the component, and we always draw the full waveform at the chosen scale, allowing an outer scroll pane to
     * control what is visible.
     */
    private final AtomicBoolean autoScroll = new AtomicBoolean(true);

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
     * Determines the font to use when drawing hot cue, memory point, and loop labels. If {@code null}, they are
     * not drawn at all.
     */
    private final AtomicReference<Font> labelFont =
            new AtomicReference<Font>(javax.swing.UIManager.getDefaults().getFont("Label.font"));

    /**
     * The waveform preview that we are drawing.
     */
    private final AtomicReference<WaveformDetail> waveform = new AtomicReference<WaveformDetail>();

    /**
     * Track the playback state for the players that have the track loaded.
     */
    private final Map<Integer, PlaybackState> playbackStateMap = new ConcurrentHashMap<Integer, PlaybackState>(4);

    /**
     * Track how many segments we average into a column of pixels; larger values zoom out, 1 is full scale.
     */
    private final AtomicInteger scale = new AtomicInteger(1);

    /**
     * Information about the cues, memory points, and loops in the track.
     */
    private final AtomicReference<CueList> cueList = new AtomicReference<CueList>();

    /**
     * Information about where all the beats in the track fall, so we can draw them.
     */
    private final AtomicReference<BeatGrid> beatGrid = new AtomicReference<BeatGrid>();

    /**
     * Controls whether we should obtain and display song structure information (phrases) at the bottom of the
     * waveform.
     */
    private final AtomicBoolean fetchSongStructures = new AtomicBoolean(true);

    /**
     * Information about the musical phrases that make up the current track, if we have it, so we can draw them.
     */
    private final AtomicReference<RekordboxAnlz.SongStructureTag> songStructure = new AtomicReference<RekordboxAnlz.SongStructureTag>();

    /**
     * The overlay painter that has been registered, if any.
     */
    private final AtomicReference<OverlayPainter> overlayPainter = new AtomicReference<OverlayPainter>();

    /**
     * Control whether the component should automatically center itself on the playback position of the player
     * that is furthest into the track. This is the default behavior of the component, and will allow it to be
     * useful at any size, showing a currently-relevant portion of the waveform. If set to {@code false} the
     * component must be inside a scroll pane so the user can control what portion of the waveform is visible.
     *
     * @param auto should the waveform be centered on the playback position
     */
    public void setAutoScroll(boolean auto) {
        if (autoScroll.getAndSet(auto) != auto) {
            setSize(getPreferredSize());
            repaint();
        }
    }

    /**
     * Check whether the component should automatically center itself on the playback position of the player
     * that is furthest into the track. This is the default behavior of the component, and will allow it to be
     * useful at any size, showing a currently-relevant portion of the waveform. If set to {@code false} the
     * component must be inside a scroll pane so the user can control what portion of the waveform is visible.
     *
     * @return {@code true} if the waveform will be centered on the playback position
     */
    public boolean getAutoScroll() {
        return autoScroll.get();
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
     * @return the color used to draw the playback and tick markers
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
     * Specify the font to be used when drawing hot cue, memory point, and loop labels. If {@code null}, do not draw
     * them at all. The default is the standard label font defined by the current Swing look and feel.
     *
     * @param font if not {@code null}, draw labels for hot cues and named memory points and loops, and use this font
     */
    public void setLabelFont(Font font) {
        labelFont.set(font);
        repaint();
    }

    /**
     * Check the font being used to draw hot cue, memory point, and loop labels. If {@code null}, they are not being
     * drawn at all.
     *
     * @return if not {@code null}, labels are being drawn for hot cues and named memory points and loops, in this font
     */
    public Font getLabelFont() {
        return labelFont.get();
    }

    /**
     * Establish a song structure (phrase analysis) to be displayed on the waveform. If we are configured to monitor
     * a player, then this will be overwritten the next time a track loads.
     *
     * @param songStructure the phrase information to be painted at the bottom of the waveform, or {@code null} to display none
     */
    public void setSongStructure(RekordboxAnlz.SongStructureTag songStructure) {
        this.songStructure.set(songStructure);
        repaint();
    }

    /**
     * Unwrap the tagged section to find the song structure inside it if it is not null, otherwise set our song
     * structure to null.
     *
     * @param taggedSection a possible tagged section holding song structure information.
     */
    private void setSongStructureWrapper(RekordboxAnlz.TaggedSection taggedSection) {
        if (taggedSection == null) {
            setSongStructure(null);
        } else if (taggedSection.fourcc() == RekordboxAnlz.SectionTags.SONG_STRUCTURE) {
            setSongStructure((RekordboxAnlz.SongStructureTag) taggedSection.body());
        } else {
            logger.warn("Received unexpected analysis tag type:" + taggedSection);
        }
    }

    /**
     * Determine whether we should try to obtain the song structure for tracks that we are displaying, and paint
     * the phrase information at the bottom of the waveform. Only has effect if we are monitoring a player.
     *
     * @param fetchSongStructures {@code true} if we should try to obtain and display phrase analysis information
     */
    public synchronized void setFetchSongStructures(boolean fetchSongStructures) {
        this.fetchSongStructures.set(fetchSongStructures);
        if (fetchSongStructures && monitoredPlayer.get() > 0) {
            AnalysisTagFinder.getInstance().addAnalysisTagListener(analysisTagListener, ".EXT", "PSSI");
            if (AnalysisTagFinder.getInstance().isRunning()) {
                setSongStructureWrapper(AnalysisTagFinder.getInstance().getLatestTrackAnalysisFor(monitoredPlayer.get(), ".EXT", "PSSI"));
            }
        } else {
            AnalysisTagFinder.getInstance().removeAnalysisTagListener(analysisTagListener, ".EXT", "PSSI");
        }
    }

    /**
     * Check whether we are supposed to obtain the song structure for tracks we are displaying when we are monitoring
     * a player.
     *
     * @return {@code true} if we should try to obtain and display phrase analysis information
     */
    public boolean getFetchSongStructures() {
        return fetchSongStructures.get();
    }

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
     * Helper method to mark the parts of the component that need repainting due to a change to the
     * tracked playback positions.
     *
     * @param oldState the old position of a marker being moved, or {@code null} if we are adding a marker
     * @param newState the new position of a marker being moved, or {@code null} if we are removing a marker
     * @param oldFurthestState the position at which the waveform was centered before this update, if we are auto-scrolling
     */
    private void repaintDueToPlaybackStateChange(PlaybackState oldState, PlaybackState newState, PlaybackState oldFurthestState) {
        if (autoScroll.get()) {
            // See if we need to repaint the whole component because our center point has shifted
            long oldFurthest = 0;
            if (oldFurthestState != null) {
                oldFurthest = oldFurthestState.position;
            }
            long newFurthest = 0;
            PlaybackState newFurthestState = getFurthestPlaybackState();
            if (newFurthestState != null) {
                newFurthest = newFurthestState.position;
            }
            if (oldFurthest != newFurthest) {
                repaint();
                return;
            }
        }
        // Refresh where the specific marker was moved from and/or to.
        if (oldState != null) {
            final int left = millisecondsToX(oldState.position) - 6;
            final int right = millisecondsToX(oldState.position) + 6;
            repaint(left, 0, right - left, getHeight());
        }
        if (newState != null) {
            final int left = millisecondsToX(newState.position) - 6;
            final int right = millisecondsToX(newState.position) + 6;
            repaint(left, 0, right - left, getHeight());
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
        PlaybackState oldFurthestState = getFurthestPlaybackState();
        PlaybackState newState = new PlaybackState(player, position, playing);
        PlaybackState oldState = playbackStateMap.put(player, newState);
        if (oldState == null || oldState.position != newState.position) {
            repaintDueToPlaybackStateChange(oldState, newState, oldFurthestState);
        }
    }

    /**
     * Clear the playback state stored for a player, such as when it has unloaded the track.
     *
     * @param player the player number whose playback state is no longer valid
     * @since 0.5.0
     */
    public synchronized void clearPlaybackState(int player) {
        PlaybackState oldFurthestState = getFurthestPlaybackState();
        PlaybackState oldState = playbackStateMap.remove(player);
        repaintDueToPlaybackStateChange(oldState, null, oldFurthestState);
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
     * changed. This will be quickly overruled if a player is being monitored, but
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
     * Set the zoom scale of the view. a value of 1 (the smallest allowed) draws the waveform at full scale.
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
            if (!autoScroll.get()) {
                setSize(getPreferredSize());
            }
        }
    }

    /**
     * Check the zoom scale of the view. a value of 1 (the smallest allowed) draws the waveform at full scale.
     * Larger values combine more and more segments into a single column of pixels, zooming out to see more at once.
     *
     * @return the current zoom scale.
     */
    public int getScale() {
        return scale.get();
    }

    /**
     * Set whether the player holding the waveform is playing, which changes the indicator color to white from red.
     * This method can only be used in situations where the component is tied to a single player, and therefore has
     * a single playback position.
     *
     * @param playing if {@code true}, draw the position marker in white, otherwise red
     *
     * @see #setPlaybackState
     */
    private void setPlaying(boolean playing) {
        PlaybackState oldState = currentSimpleState();
        if (oldState != null && oldState.playing != playing) {
            setPlaybackState(oldState.player, oldState.position, playing);
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
        if (metadata != null) {
            cueList.set(metadata.getCueList());
        } else {
            cueList.set(null);
        }
        this.beatGrid.set(beatGrid);
        clearPlaybackState();
        repaint();
        if (!autoScroll.get()) {
            invalidate();
        }
    }

    /**
     * Change the waveform preview being drawn. This will be quickly overruled if a player is being monitored, but
     * can be used in other contexts.
     *
     * @param waveform the waveform detail to display
     * @param cueList used to draw cue and memory points
     * @param beatGrid the locations of the beats, so they can be drawn
     */
    public void setWaveform(WaveformDetail waveform, CueList cueList, BeatGrid beatGrid) {
        this.waveform.set(waveform);
        this.cueList.set(cueList);
        this.beatGrid.set(beatGrid);
        clearPlaybackState();
        repaint();
        if (!autoScroll.get()) {
            invalidate();
        }
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
            VirtualCdj.getInstance().addUpdateListener(updateListener);
            MetadataFinder.getInstance().addTrackMetadataListener(metadataListener);
            cueList.set(null);  // Assume the worst, but see if we have one available next.
            if (MetadataFinder.getInstance().isRunning()) {
                TrackMetadata metadata = MetadataFinder.getInstance().getLatestMetadataFor(player);
                if (metadata != null) {
                    cueList.set(metadata.getCueList());
                }
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
            if (fetchSongStructures.get()) {
                AnalysisTagFinder.getInstance().addAnalysisTagListener(analysisTagListener, ".EXT", "PSSI");
                if (AnalysisTagFinder.getInstance().isRunning()) {
                    setSongStructureWrapper(AnalysisTagFinder.getInstance().getLatestTrackAnalysisFor(player, ".EXT", "PSSI"));
                }
            }
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
                logger.error("Unable to start the TimeFinder to animate the waveform detail view");
                animating.set(false);
            }
        } else {  // Stop monitoring any player
            animating.set(false);
            VirtualCdj.getInstance().removeUpdateListener(updateListener);
            MetadataFinder.getInstance().removeTrackMetadataListener(metadataListener);
            WaveformFinder.getInstance().removeWaveformListener(waveformListener);
            AnalysisTagFinder.getInstance().removeAnalysisTagListener(analysisTagListener, ".EXT", "PSSI");
            cueList.set(null);
            waveform.set(null);
            beatGrid.set(null);
            songStructure.set(null);
        }
        if (!autoScroll.get()) {
            invalidate();
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
                    cueList.set(update.metadata.getCueList());
                } else {
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
            // Nothing to do.
        }

        @Override
        public void detailChanged(WaveformDetailUpdate update) {
            logger.debug("Got waveform detail update: {}", update);
            if (update.player == getMonitoredPlayer()) {
                waveform.set(update.detail);
                if (!autoScroll.get()) {
                    invalidate();
                }
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
                    (cueList.get() != null) && (beatGrid.get() != null)) {
                CdjStatus status = (CdjStatus) update;
                setPlaying(status.isPlaying());
            }
        }
    };

    private final AnalysisTagListener analysisTagListener = new AnalysisTagListener() {
        @Override
        public void analysisChanged(AnalysisTagUpdate update) {
            if (update.player == getMonitoredPlayer()) {
                setSongStructureWrapper(update.taggedSection);
            }
        }
    };

    /**
     * Create a view which updates itself to reflect the track loaded on a particular player, and that player's
     * playback progress.
     *
     * @param player the player number to monitor, or zero if it should start out monitoring no player
     */
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
        if (metadata != null) {
            cueList.set(metadata.getCueList());
        }
        this.beatGrid.set(beatGrid);
    }

    /**
     * Create a view which draws a specific waveform, even if it is not currently loaded in a player.
     *
     * @param waveform the waveform detail to display
     * @param cueList used to draw cues and memory points
     * @param beatGrid the locations of the beats, so they can be drawn
     */
    public WaveformDetailComponent(WaveformDetail waveform, CueList cueList, BeatGrid beatGrid) {
        this.waveform.set(waveform);
        this.cueList.set(cueList);
        this.beatGrid.set(beatGrid);
    }

    @Override
    public Dimension getMinimumSize() {
        final WaveformDetail detail = waveform.get();
        if (autoScroll.get() || detail == null) {
            return new Dimension(300, 92);
        }
        return new Dimension(detail.getFrameCount() / scale.get(), 92);
    }

    @Override
    public Dimension getPreferredSize() {
        return getMinimumSize();
    }

    /**
     * Look up the playback state that has reached furthest in the track, but give playing players priority over stopped players.
     * This is used to choose the scroll center when auto-scrolling is active.
     *
     * @return the playback state, if any, with the highest playing {@link PlaybackState#position} value
     */
    public PlaybackState getFurthestPlaybackState() {
        PlaybackState result = null;
        for (PlaybackState state : playbackStateMap.values()) {
            if (result == null || (!result.playing && state.playing) ||
                    (result.position < state.position) && (state.playing || !result.playing)) {
                result = state;
            }
        }
        return result;
    }

    /**
     * Look up the furthest position, in milliseconds, that has been reached, giving playing players priority over stopped players.
     * If there are no playback positions, returns 0.
     *
     * @return The position in milliseconds of the furthest playback state reached, or 0 if there are no playback states
     */
    public long getFurthestPlaybackPosition() {
        PlaybackState state = getFurthestPlaybackState();
        if (state != null) {
            return state.position;
        }
        return 0;
    }

    /**
     * Figure out the starting waveform segment that corresponds to the specified coordinate in the window.

     * @param x the column being drawn
     *
     * @return the offset into the waveform at the current scale and playback time that should be drawn there
     */
    private int getSegmentForX(int x) {
        if (autoScroll.get()) {
            int playHead = (x - (getWidth() / 2));
            int offset = Util.timeToHalfFrame(getFurthestPlaybackPosition()) / scale.get();
            return  (playHead + offset) * scale.get();
        }
        return x * scale.get();
    }

    /**
     * Determine the playback time that corresponds to a particular X coordinate in the component given the current
     * scale.
     * @param x the horizontal position within the component coordinate space
     * @return the number of milliseconds into the track this would correspond to (may fall outside the actual track)
     */
    public long getTimeForX(int x) {
        return Util.halfFrameToTime(getSegmentForX(x));
    }

    /**
     * Determine the beat that corresponds to a particular X coordinate in the component, given the current scale.
     * Clicks past the end of the track will return the final beat, clicks before the first beat (or if there is no
     * beat grid) will return -1.
     *
     * @param x the horizontal position within the component coordinate space
     * @return the beat number being played at that point, or -1 if the point is before the first beat
     */
    public int getBeatForX(int x) {
        BeatGrid grid = beatGrid.get();
        if (grid != null) {
            return grid.findBeatAtTime(getTimeForX(x));
        }
        return -1;
    }

    /**
     * Determine the X coordinate within the component at which the specified beat begins.
     *
     * @param beat the beat number whose position is desired
     * @return the horizontal position within the component coordinate space where that beat begins
     * @throws IllegalArgumentException if the beat number exceeds the number of beats in the track.
     */
    public int getXForBeat(int beat) {
        BeatGrid grid = beatGrid.get();
        if (grid != null) {
            return millisecondsToX(grid.getTimeWithinTrack(beat));
        }
        return 0;
    }

    /**
     * Converts a time in milliseconds to the appropriate x coordinate for drawing something at that time.
     *
     * @param milliseconds the time at which something should be drawn
     *
     * @return the component x coordinate at which it should be drawn
     */
    public int millisecondsToX(long milliseconds) {
        if (autoScroll.get()) {
            int playHead = (getWidth() / 2) + 2;
            long offset = milliseconds - getFurthestPlaybackPosition();
            return playHead + (Util.timeToHalfFrame(offset) / scale.get());
        }
        return Util.timeToHalfFrame(milliseconds) / scale.get();
    }

    /**
     * The largest scale at which we will draw individual beat markers; above this we show only bars.
     */
    private static final int MAX_BEAT_SCALE = 9;

    /**
     * Determine the color to use to draw a cue list entry. Hot cues are green, ordinary memory points are red,
     * and loops are orange.
     *
     * @param entry the entry being drawn
     *
     * @return the color with which it should be represented
     *
     * @deprecated use {@link CueList.Entry#getColor()} instead
     */
    @Deprecated
    public static Color cueColor(CueList.Entry entry) {
       return entry.getColor();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Rectangle clipRect = g.getClipBounds();  // We only need to draw the part that is visible or dirty
        g.setColor(backgroundColor.get());  // Clear the background
        g.fillRect(clipRect.x, clipRect.y, clipRect.width, clipRect.height);

        CueList currentCueList = cueList.get();  // Avoid crashes if the value changes mid-render.
        RekordboxAnlz.SongStructureTag currentSongStructure = songStructure.get();  // Same.

        // Draw the loop regions of any visible loops
        final int axis = getHeight() / 2;
        final int maxHeight = axis - VERTICAL_MARGIN;
        if (currentCueList != null) {
            g.setColor(LOOP_BACKGROUND);
            for (CueList.Entry entry : currentCueList.entries) {
                if (entry.isLoop) {
                    final int start = millisecondsToX(entry.cueTime);
                    final int end = millisecondsToX(entry.loopTime);
                    g.fillRect(start, axis - maxHeight, end - start, maxHeight * 2);
                }
            }
        }

        int lastBeat = 0;
        if (beatGrid.get() != null) {  // Find what beat was represented by the column just before the first we draw.
            lastBeat = beatGrid.get().findBeatAtTime(Util.halfFrameToTime(getSegmentForX(clipRect.x - 1)));
        }
        for (int x = clipRect.x; x <= clipRect.x + clipRect.width; x++) {
            final int segment = getSegmentForX(x);
            if (waveform.get() != null) { // Drawing the waveform itself
                if ((segment >= 0) && (segment < waveform.get().getFrameCount())) {
                    g.setColor(waveform.get().segmentColor(segment, scale.get()));
                    final int height = (waveform.get().segmentHeight(segment, scale.get()) * maxHeight) / 31;
                    g.drawLine(x, axis - height, x, axis + height);
                }
            }
            if (beatGrid.get() != null) {  // Draw the beat markers
                int inBeat = beatGrid.get().findBeatAtTime(Util.halfFrameToTime(segment));
                if ((inBeat > 0) && (inBeat != lastBeat)) {  // Start of a new beat, so prepare to draw it
                    final int beatWithinBar = beatGrid.get().getBeatWithinBar(inBeat);
                    if (scale.get() <= MAX_BEAT_SCALE || beatWithinBar == 1) {
                        // Once scale gets large enough, we only draw the down beats, like CDJs.
                        g.setColor((beatWithinBar == 1) ? emphasisColor.get() : indicatorColor.get());
                        g.drawLine(x, axis - maxHeight - 2 - BEAT_MARKER_HEIGHT, x, axis - maxHeight - 2);
                        g.drawLine(x, axis + maxHeight + 2, x, axis + maxHeight + BEAT_MARKER_HEIGHT + 2);
                    }
                    lastBeat = inBeat;
                }
            }
        }

        // Draw the cue and memory point markers, first the memory cues and then the hot cues, since some are in
        // the same place and we want the hot cues to stand out.
        if (currentCueList != null) {
            paintCueList(g, clipRect, currentCueList, axis, maxHeight);
        }

        // Draw the song structure if we have one for the track.
        if (currentSongStructure != null) {
            paintPhrases(g, clipRect, currentSongStructure, axis, maxHeight);
        }

        // Draw the non-playing markers first, so the playing ones will be seen if they are in the same spot.
        g.setColor(Util.buildColor(indicatorColor.get(), WaveformDetailComponent.PLAYBACK_MARKER_STOPPED));
        for (PlaybackState state : playbackStateMap.values()) {
            if (!state.playing) {
                g.fillRect(millisecondsToX(state.position) - (PLAYBACK_MARKER_WIDTH / 2), 0,
                        PLAYBACK_MARKER_WIDTH, getHeight());
            }
        }

        // Then draw the playing markers on top of the non-playing ones.
        g.setColor(Util.buildColor(emphasisColor.get(), WaveformDetailComponent.PLAYBACK_MARKER_PLAYING));
        for (PlaybackState state : playbackStateMap.values()) {
            if (state.playing) {
                g.fillRect(millisecondsToX(state.position) - (PLAYBACK_MARKER_WIDTH / 2), 0,
                        PLAYBACK_MARKER_WIDTH, getHeight());
            }
        }

        // Finally, if an overlay painter has been attached, let it paint its overlay.
        OverlayPainter painter = overlayPainter.get();
        if (painter != null) {
            painter.paintOverlay(this, g);
        }
    }

    /**
     * Determine the label to display below a cue marker.
     *
     * @param entry the cue list entry which might need labeling
     * @return the text to display, or an empty string if none is needed
     */
    private String buildCueLabel(CueList.Entry entry) {
        if (entry.hotCueNumber > 0) {
            String label = String.valueOf((char)(64 + entry.hotCueNumber));
            if (entry.comment.isEmpty()) {
                return label;
            }
            return label + ": " + entry.comment;
        }
        return entry.comment;
    }

    /**
     * Draw the visible memory cue points or hot cues.
     *
     * @param g the graphics object in which we are being rendered
     * @param clipRect the region that is being currently rendered
     * @param cueList the cues to  be drawn
     * @param axis the base on which the waveform is being drawn
     * @param maxHeight the highest waveform segment
     */
    private void paintCueList(Graphics g, Rectangle clipRect, CueList cueList, int axis, int maxHeight) {
        for (CueList.Entry entry : cueList.entries) {
            final int x = millisecondsToX(entry.cueTime);
            if ((x > clipRect.x - 4) && (x < clipRect.x + clipRect.width + 4)) {
                g.setColor(entry.getColor());
                for (int i = 0; i < 4; i++) {
                    g.drawLine(x - 3 + i, axis - maxHeight - BEAT_MARKER_HEIGHT - CUE_MARKER_HEIGHT + i,
                            x + 3 - i, axis - maxHeight - BEAT_MARKER_HEIGHT - CUE_MARKER_HEIGHT + i);
                }

                String label = buildCueLabel(entry);
                Font font = labelFont.get();
                if (font != null && !label.isEmpty()) {
                    Graphics2D g2 = (Graphics2D)g;
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setFont(font);
                    FontRenderContext renderContext = g2.getFontRenderContext();
                    LineMetrics metrics = g2.getFont().getLineMetrics(label, renderContext);
                    Rectangle2D bounds = g2.getFont().getStringBounds(label, renderContext);
                    int textWidth = (int)Math.ceil(bounds.getWidth());
                    int textHeight = (int)Math.ceil(metrics.getAscent() + metrics.getDescent());
                    g2.fillRect(x, axis - maxHeight - 2, textWidth + 4, textHeight + 2);
                    g2.setColor(Color.black);
                    g2.drawString(label, x + 2, axis - maxHeight - 1 + metrics.getAscent());
                }
            }
        }
    }

    /**
     * Draw the visible phrases if the track has a structure analysis.
     *
     * @param g the graphics object in which we are being rendered
     * @param clipRect the region that is being currently rendered
     * @param songStructure contains the phrases to be drawn
     * @param axis the base on which the waveform is being drawn
     * @param maxHeight the highest waveform segment
     */
    private void paintPhrases(Graphics g, Rectangle clipRect, RekordboxAnlz.SongStructureTag songStructure, int axis, int maxHeight) {
        if (songStructure == null) {
            return;
        }

        // Have the phrase labels stick to the left edge of the viewable area as they scroll by.
        // Start by finding our parent scroll pane, if there is one, so we can figure out its horizontal scroll position.
        int scrolledX = 0;
        Container parent = getParent();
        while (parent != null) {
            if (parent instanceof JScrollPane) {
                scrolledX = ((JScrollPane) parent).getViewport().getViewPosition().x;
                parent = null;  // We are done searching for our scroll pane.
            } else {
                parent = parent.getParent();
            }
        }

        for (int i = 0; i < songStructure.lenEntries(); i++) {
            final RekordboxAnlz.SongStructureEntry entry = songStructure.body().entries().get(i);
            final int endBeat = (i == songStructure.lenEntries() - 1) ? songStructure.body().endBeat() : songStructure.body().entries().get(i + 1).beat();
            final int x1 = getXForBeat(entry.beat());
            final int x2 = getXForBeat(endBeat) - 1;
            if ((x1 >= clipRect.x && x1 <= clipRect.x + clipRect.width) || (x2 >= clipRect.x && x2 <= clipRect.x + clipRect.width) ||
                    (x1 < clipRect.x && x2 > clipRect.x + clipRect.width)) {  // Is any of this phrase visible?
                g.setColor(Util.buildColor(Util.phraseColor(entry), PHRASE_TRANSPARENCY));  // Render slightly transparently.
                final String label = Util.phraseLabel(entry);
                final Font font = labelFont.get();
                if (font != null && !label.isEmpty()) {
                    Graphics2D g2 = (Graphics2D)g;
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setFont(font);
                    FontRenderContext renderContext = g2.getFontRenderContext();
                    LineMetrics metrics = g2.getFont().getLineMetrics(label, renderContext);
                    int textHeight = (int)Math.ceil(metrics.getAscent() + metrics.getDescent());
                    Rectangle2D phraseRect = new Rectangle2D.Double(x1, axis + maxHeight + 2 - textHeight, x2 - x1, textHeight + 2);
                    g2.fill(phraseRect);
                    Shape oldClip = g2.getClip();
                    g2.setClip(phraseRect);
                    g2.setColor(Util.buildColor(Util.phraseTextColor(entry), PHRASE_TRANSPARENCY));
                    // See if the label for this phrase needs to be adjusted to stay visible as we scroll.
                    int labelX = x1;
                    if (scrolledX > labelX) {  // We have scrolled past the start of the phrase.
                        labelX += (scrolledX - labelX);  // Nudge the label back into view.
                    }
                    g2.drawString(label, labelX + 2, axis + maxHeight);
                    g2.setClip(oldClip);
                }
            }
        }
    }


    @Override
    public String toString() {
        return"WaveformDetailComponent[cueList=" + cueList.get() + ", waveform=" + waveform.get() + ", beatGrid=" +
                beatGrid.get() + ", playbackStateMap=" + playbackStateMap + ", monitoredPlayer=" +
                getMonitoredPlayer() + "fetchSongStructures=" + fetchSongStructures.get() + "]";
    }
}
