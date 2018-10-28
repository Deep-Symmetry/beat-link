package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.dbserver.BinaryField;
import org.deepsymmetry.beatlink.dbserver.Message;

import javax.swing.*;
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
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    @SuppressWarnings("WeakerAccess")
    public ByteBuffer getData() {
        return ((BinaryField) rawMessage.arguments.get(3)).getValue();
    }



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
        return new WaveformPreviewComponent(this, metadata);
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
