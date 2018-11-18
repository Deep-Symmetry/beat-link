package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.dbserver.BinaryField;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.cratedigger.pdb.AnlzFile;

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
     * The message holding the preview as it was read over the network, if it came from the dbserver.
     * This can be used to analyze fields that have not yet been reliably understood, and is also used for storing
     * the cue list in a cache file.
     */
    @SuppressWarnings("WeakerAccess")
    public final Message rawMessage;

    /**
     * The results of expanding the data we received from the player's NFS server if this preview was retrieved
     * by Crate Digger rather than from the dbserver.
     */
    private final ByteBuffer expandedData;

    /**
     * Get the raw bytes of the waveform preview data
     *
     * @return the bytes from which the preview can be drawn, as described in Section 5.8 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    @SuppressWarnings("WeakerAccess")
    public ByteBuffer getData() {
        if (rawMessage != null) {
            return ((BinaryField) rawMessage.arguments.get(3)).getValue();
        }
        expandedData.rewind();
        return expandedData.slice();
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
    WaveformPreview(DataReference reference, Message message) {
        dataReference = reference;
        rawMessage = message;
        expandedData = null;
    }

    /**
     * Constructor when received from Crate Digger.
     *
     * @param reference the unique database reference that was used to request this waveform preview
     * @param anlzFile the parsed rekordbox track analysis file containing the waveform preview
     */
    WaveformPreview(DataReference reference, AnlzFile anlzFile) {
        dataReference = reference;
        rawMessage = null;
        ByteBuffer found = null;
        for (AnlzFile.TaggedSection section : anlzFile.sections()) {
            if (section.body() instanceof AnlzFile.WavePreviewTag) {
                AnlzFile.WavePreviewTag tag = (AnlzFile.WavePreviewTag) section.body();
                byte[] tagBytes = tag.data();
                byte[] bytes = new byte[tagBytes.length * 2];
                for (int i = 0; i < tagBytes.length; i++) {
                    bytes[i * 2] = (byte)(tagBytes[i] & 0x1f);
                    bytes[(i * 2) + 1] = (byte)((tagBytes[i] >> 5) & 7);
                }
                found = ByteBuffer.wrap(bytes).asReadOnlyBuffer();
                break;
            }
        }
        expandedData = found;
        if (expandedData == null) {
            throw new IllegalStateException("Could not construct WaveformPreview, missing from ANLZ file " + anlzFile);
        }
    }

    @Override
    public String toString() {
        return "WaveformPreview[dataReference=" + dataReference + ", size:" + getData().remaining() + "]";
    }
}
