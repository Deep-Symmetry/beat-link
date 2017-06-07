package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.dbserver.BinaryField;
import org.deepsymmetry.beatlink.dbserver.Message;

import javax.swing.*;
import java.awt.*;
import java.nio.ByteBuffer;

/**
 * Gives a birds-eye view of the audio content of a track, and offers a Swing component for rendering that view
 * as part of a user interface, along with annotations showing the current playback position and cue points, if the
 * appropriate metadata is available.
 *
 * @author James Elliott
 */
public class WaveformPreview {

    /**
     * The unique identifier that was used to request this waveform preview.
     */
    @SuppressWarnings("WeakerAccess")
    public final DataReference dataReference;

    /**
     * The message holding the preview as it was read over the network. This can be used to analyze fields
     * that have not yet been reliably understood, and is also used for storing the cue list in a cache file.
     */
    @SuppressWarnings("WeakerAccess")
    public final Message rawMessage;

    /**
     * Get the raw bytes of the waveform preview data
     *
     * @return the bytes from which the preview can be drawn, as described in Section 5.8 of the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    @SuppressWarnings("WeakerAccess")
    public ByteBuffer getData() {
        return ((BinaryField) rawMessage.arguments.get(3)).getValue();
    }

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
     * The height of the waveform.
     */
    private static final int WAVEFORM_HEIGHT = 31;

    /**
     * The Y coordinate at which the top of the playback progress bar is drawn.
     */
    private static final int PLAYBACK_BAR_TOP = WAVEFORM_TOP + WAVEFORM_HEIGHT + 3;

    /**
     * The height of the playback progress bar.
     */
    private static final int PLAYBACK_BAR_HEIGHT = 4;

    /**
     * The Y coordinate at which the top of the minute markers are drawn.
     */
    private static final int MINUTE_MARKER_TOP = PLAYBACK_BAR_TOP + PLAYBACK_BAR_HEIGHT + 3;

    /**
     * The height of the minute markers.
     */
    private static final int MINUTE_MARKER_HEIGHT = 4;

    /**
     * The height of the large bar showing the current playback position.
     */
    private static final int POSITION_MARKER_HEIGHT = MINUTE_MARKER_TOP - POSITION_MARKER_TOP - 1;

    /**
     * The total height of the component.
     */
    private static final int VIEW_HEIGHT = MINUTE_MARKER_TOP + MINUTE_MARKER_HEIGHT + 1;

    /**
     * The X coordinate of the waveform, to give enough space for a cue marker at the start of the track.
     */
    private static final int WAVEFORM_MARGIN = 4;

    /**
     * The color at which segments of the waveform marked most intense are drawn.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Color INTENSE_COLOR = new Color(116, 246, 244);

    /**
     * The color at which non-intense waveform segments are drawn.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Color NORMAL_COLOR = new Color(43, 89, 255);

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
     * Provides a convenient way to draw the waveform preview in a user interface, including annotations like the
     * current time and minute markers (if you supply {@link TrackMetadata} so the total length can be determined),
     * and cue markers (if you also supply a {@link CueList}).
     */
    public class ViewComponent extends JComponent {

        /**
         * Track the current playback position in milliseconds.
         */
        private long playbackPosition = 0;

        /**
         * Information about the track whose waveform we are drawing, so we can translate times into positions.
         */
        private final TrackMetadata metadata;

        /**
         * Set the current playback position. Will cause  part the component to be redrawn if the position has
         * changed (and we have the {@link TrackMetadata} we need to translate the time into a position in the
         * component).
         *
         * @param milliseconds how far into the track has been played
         */
        public void setPlaybackPosition(long milliseconds) {
            if ((metadata !=  null) && (playbackPosition != milliseconds)) {
                int left;
                int right;
                if (milliseconds > playbackPosition) {
                    left = Math.max(0, Math.min(408, millisecondsToX(playbackPosition) - 6));
                    right = Math.max(0, Math.min(408, millisecondsToX(milliseconds) + 6));
                } else {
                    left = Math.max(0, Math.min(408, millisecondsToX(milliseconds) - 6));
                    right = Math.max(0, Math.min(408, millisecondsToX(playbackPosition) + 6));
                }
                playbackPosition = milliseconds;
                repaint(left, 0, right - left, VIEW_HEIGHT);
            }
        }

        /**
         * Record the information we can use to draw annotations.
         *
         * @param metadata Information about the track whose waveform we are drawing, so we can translate times into
         *                 positions
         */
        ViewComponent(final TrackMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(400 + WAVEFORM_MARGIN * 2, VIEW_HEIGHT);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
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
            long result = milliseconds * 4 / (metadata.getDuration() * 10);
            return WAVEFORM_MARGIN + Math.max(0, Math.min(400, (int) result));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Rectangle clipRect = g.getClipBounds();  // We only need to draw the part that is visible or dirty
            g.setColor(Color.BLACK);  // Black out the background
            g.fillRect(clipRect.x, clipRect.y, clipRect.width, clipRect.height);

            for (int x = clipRect.x; x <= clipRect.x + clipRect.width; x++) {  // Draw the waveform
                final ByteBuffer waveBytes = getData();
                final int segment = x - WAVEFORM_MARGIN;
                if ((segment >= 0) && (segment < 400)) {
                    // Waveform
                    final int height = waveBytes.get(segment * 2) & 0x1f;
                    final int intensity = waveBytes.get(segment * 2 + 1) & 0x5;
                    g.setColor((intensity == 5) ? INTENSE_COLOR : NORMAL_COLOR);
                    g.drawLine(x, WAVEFORM_TOP + WAVEFORM_HEIGHT, x, WAVEFORM_TOP + WAVEFORM_HEIGHT - height);

                    if (metadata != null) { // Draw the playback progress bar
                        if (x < millisecondsToX(playbackPosition) - 1) {  // The played section
                            g.setColor((x % 2 == 0)? BRIGHT_PLAYED : DIM_PLAYED);
                            if (x == WAVEFORM_MARGIN) {
                                g.drawLine(x, PLAYBACK_BAR_TOP, x, PLAYBACK_BAR_TOP + PLAYBACK_BAR_HEIGHT);
                            } else {
                                g.drawLine(x, PLAYBACK_BAR_TOP, x, PLAYBACK_BAR_TOP);
                                g.drawLine(x, PLAYBACK_BAR_TOP + PLAYBACK_BAR_HEIGHT, x, PLAYBACK_BAR_TOP + PLAYBACK_BAR_HEIGHT);
                            }
                        } else if (x > millisecondsToX(playbackPosition) + 1) {  // The unplayed section
                            g.setColor((x % 2 == 0)? Color.WHITE : DIM_UNPLAYED);
                            g.drawLine(x, PLAYBACK_BAR_TOP, x, PLAYBACK_BAR_TOP + PLAYBACK_BAR_HEIGHT);
                        }
                    }
                }
            }

            if (metadata != null) {  // Draw the minute marks and playback position
                g.setColor(Color.WHITE);
                for (int time = 60; time < metadata.getDuration(); time += 60) {
                    final int x = millisecondsToX(time * 1000);
                    g.drawLine(x, MINUTE_MARKER_TOP, x, MINUTE_MARKER_TOP + MINUTE_MARKER_HEIGHT);
                }
                final int x = millisecondsToX(playbackPosition);
                g.fillRect(x - 1, POSITION_MARKER_TOP, 2, POSITION_MARKER_HEIGHT);
            }

            // Finally, draw the cue points
            if (metadata != null && metadata.getCueList() != null) {
                for (CueList.Entry entry : metadata.getCueList().entries) {
                    final int x = millisecondsToX(entry.cueTime);
                    if ((x > clipRect.x - 4) && (x < clipRect.x + clipRect.width + 4)) {
                        g.setColor((entry.hotCueNumber > 0)? Color.GREEN : Color.RED);
                        for (int i = 0; i < 4; i++) {
                            g.drawLine(x - 3 + i, CUE_MARKER_TOP + i, x + 3 - i, CUE_MARKER_TOP + i);
                        }
                    }
                }
            }
        }
    }

    // TODO: Have the view able to automatically update itself in response to messages from the VirtualCDJ!
    // TODO: Move the view into its own class, either construct with the waveform and metadata directly for
    //       a static view, or with a player number in which case it registers with the finders and obtains
    //       all the pieces it needs to stay up to date with whatever is loaded in that player!

    /**
     * Create a standard Swing component which can be added to a user interface that will draw this waveform preview,
     * optionally including annotations like the current playback position and minute markers (if you supply
     * {@link TrackMetadata} so the total length can be determined), and cue markers (if you also supply a
     * {@link CueList}). The playback position can be
     *
     * @param metadata Information about the track whose waveform we are drawing, so we can translate times into
     *                 positions
     *
     * @return the component which will draw the annotated waveform preview
     */
    public JComponent createViewComponent(TrackMetadata metadata) {
        return new ViewComponent(metadata);
    }

    /**
     * Constructor when reading from the network or a cache file.
     *
     * @param reference the unique database reference that was used to request this waveform preview
     * @param message the response that contains the preview
     */
    @SuppressWarnings("WeakerAccess")
    public WaveformPreview(DataReference reference, Message message) {
        dataReference = reference;
        rawMessage = message;
    }

    @Override
    public String toString() {
        return "WaveformPreview[dataReference=" + dataReference + ", size:" + getData().remaining() + "]";
    }
}
