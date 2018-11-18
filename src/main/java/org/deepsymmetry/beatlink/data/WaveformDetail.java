package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.Util;
import org.deepsymmetry.beatlink.dbserver.BinaryField;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.cratedigger.pdb.AnlzFile;

import javax.swing.*;
import java.nio.ByteBuffer;

/**
 * Gives a detail view of the audio content of a track, and offers a Swing component for rendering that view
 * as part of a user interface, along with annotations showing the current playback position, beats, and cue points,
 * if the appropriate metadata is available.
 *
 * @author James Elliott
 */
public class WaveformDetail {
    /**
     * The number of bytes at the start of the waveform data which do not seem to be valid or used when it is served
     * by the dbserver protocol. They are not present when the ANLZ.EXT file is loaded directly by Crate Digger.
     */
    @SuppressWarnings("WeakerAccess")
    public static final int LEADING_DBSERVER_JUNK_BYTES = 19;

    /**
     * The unique identifier that was used to request this waveform detail.
     */
    @SuppressWarnings("WeakerAccess")
    public final DataReference dataReference;

    /**
     * The message holding the detail as it was read over the network. This can be used to analyze fields
     * that have not yet been reliably understood, and is also used for storing the cue list in a cache file.
     * This will be null if the data was obtained from Crate Digger.
     */
    @SuppressWarnings("WeakerAccess")
    public final Message rawMessage;

    /**
     * The parsed structure holding the detail as it was extracted from the extended analysis file, regardless of
     * how it was obtained.
     */
    private final ByteBuffer detailBuffer;

    /**
     * How many leading junk bytes are present in the waveform data. This value will depend on how the data was
     * obtained.
     */
    @SuppressWarnings("WeakerAccess")
    public final int leadingJunkBytes;

    /**
     * Get the raw bytes of the waveform detail data
     *
     * @return the bytes from which the detail can be drawn, as described in Section 5.8 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    @SuppressWarnings("WeakerAccess")
    public ByteBuffer getData() {
        detailBuffer.rewind();
        return detailBuffer.slice();
    }

    /**
     * Count the half-frames of waveform available.
     *
     * @return the number of half-frames that make up the track, ignoring the leading junk bytes
     */
    @SuppressWarnings("WeakerAccess")
    public int getFrameCount() {
        return getData().remaining() - leadingJunkBytes;
    }

    /**
     * Determine how long the track plays, in milliseconds. This provides a more accurate value than the track
     * metadata, which is accurate only to the second, because we know how many half-frames (1/150 of a second)
     * the track is composed of.
     *
     * @return the number of milliseconds it will take to play all half-frames that make up the track
     */
    public long getTotalTime() {
        return Util.halfFrameToTime(getFrameCount());
    }

    /**
     * Create a standard Swing component which can be added to a user interface that will draw this waveform detail,
     * optionally including annotations like the current playback position and minute markers (if you supply
     * {@link TrackMetadata} so the total length can be determined), and cue markers (if you also supply a
     * {@link CueList}). The playback position can be
     *
     * @param metadata Information about the track whose waveform we are drawing, so we can translate times into
     *                 positions
     * @param beatGrid The locations of all the beats in the track, so they can be drawn
     *
     * @return the component which will draw the annotated waveform preview
     */
    public JComponent createViewComponent(TrackMetadata metadata, BeatGrid beatGrid) {
        return new WaveformDetailComponent(this, metadata, beatGrid);
    }

    /**
     * Constructor when reading from the network or a cache file.
     *
     * @param reference the unique database reference that was used to request this waveform detail
     * @param message the response that contains the preview
     */
    @SuppressWarnings("WeakerAccess")
    public WaveformDetail(DataReference reference, Message message) {
        dataReference = reference;
        rawMessage = message;
        detailBuffer = ((BinaryField) rawMessage.arguments.get(3)).getValue();
        leadingJunkBytes = LEADING_DBSERVER_JUNK_BYTES;
    }

    /**
     * Constructor when received from Crate Digger.
     *
     * @param reference the unique database reference that was used to request this waveform preview
     * @param anlzFile the parsed rekordbox track analysis file containing the waveform preview
     */
    WaveformDetail(DataReference reference, AnlzFile anlzFile) {
        dataReference = reference;
        rawMessage = null;
        ByteBuffer found = null;
        for (AnlzFile.TaggedSection section : anlzFile.sections()) {
            if (section.body() instanceof AnlzFile.WaveScrollTag) {
                AnlzFile.WaveScrollTag tag = (AnlzFile.WaveScrollTag) section.body();
                found = ByteBuffer.wrap(tag.entries()).asReadOnlyBuffer();
                break;
            }
        }
        detailBuffer = found;
        if (detailBuffer == null) {
            throw new IllegalStateException("Could not construct WaveformDetail, missing from ANLZ file " + anlzFile);
        }
        leadingJunkBytes = 0;
    }

    @Override
    public String toString() {
        return "WaveformDetail[dataReference=" + dataReference + ", size:" + getData().remaining() + "]";
    }
}
