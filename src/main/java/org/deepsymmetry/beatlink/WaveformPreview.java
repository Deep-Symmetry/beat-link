package org.deepsymmetry.beatlink;

import org.deepsymmetry.beatlink.dbserver.BinaryField;
import org.deepsymmetry.beatlink.dbserver.Message;

import java.nio.ByteBuffer;

/**
 * Gives a birds-eye view of the audio content of a track.
 *
 * @author James Elliott
 */
public class WaveformPreview {
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
    public ByteBuffer getData() {
        return ((BinaryField) rawMessage.arguments.get(3)).getValue();
    }

    /**
     * Constructor when reading from the network or a cache file.
     *
     * @param message the response that contains the preview
     */
    public WaveformPreview(Message message) {
        rawMessage = message;
    }

}
