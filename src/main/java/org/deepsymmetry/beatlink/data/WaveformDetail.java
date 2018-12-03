package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.Util;
import org.deepsymmetry.beatlink.dbserver.BinaryField;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.nio.ByteBuffer;

/**
 * Gives a detail view of the audio content of a track, and offers a Swing component for rendering that view
 * as part of a user interface, along with annotations showing the current playback position, beats, and cue points,
 * if the appropriate metadata is available.
 *
 * @author James Elliott
 */
public class WaveformDetail {

    @SuppressWarnings("FieldCanBeLocal")
    private final Logger logger = LoggerFactory.getLogger(WaveformDetail.class);

    /**
     * The number of bytes at the start of the waveform data which do not seem to be valid or used when it is served
     * by the dbserver protocol. They are not present when the ANLZ.EXT file is loaded directly by Crate Digger.
     */
    @SuppressWarnings("WeakerAccess")
    public static final int LEADING_DBSERVER_JUNK_BYTES = 19;

    /**
     * The number of bytes at the start of the color waveform data to be skipped when that was loaded using the
     * nxs2 ANLZ tag request. We actually know what these mean, now that we know how to parse EXT files, but we
     * can simply skip them anyway.
     */
    @SuppressWarnings("WeakerAccess")
    public static final int LEADING_DBSERVER_COLOR_JUNK_BYTES = 28;

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
     * Indicates whether this is an NXS2-style color waveform, or a monochrome (blue) waveform.
     */
    @SuppressWarnings("WeakerAccess")
    public final boolean isColor;

    /**
     * Get the raw bytes of the waveform detail data
     *
     * @return the bytes from which the detail can be drawn, as described in Section 5.8 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public ByteBuffer getData() {
        detailBuffer.rewind();
        return detailBuffer.slice();
    }

    /**
     * Count the half-frames of waveform available.
     *
     * @return the number of half-frames (pixel columns) that make up the track
     */
    public int getFrameCount() {
        final int bytes = getData().remaining();
        if (isColor) {
            return bytes / 2;
        }
        return bytes;
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
        isColor = message.knownType == Message.KnownType.ANLZ_TAG;  // If we got one of these, its an NXS2 color wave.
        dataReference = reference;
        rawMessage = message;
        // Load the bytes we were sent, and skip over the proper number of leading junk bytes
        ByteBuffer rawBuffer = ((BinaryField) rawMessage.arguments.get(3)).getValue();
        rawBuffer.position(isColor? LEADING_DBSERVER_COLOR_JUNK_BYTES : LEADING_DBSERVER_JUNK_BYTES);
        detailBuffer = rawBuffer.slice();
    }

    /**
     * Constructor when received from Crate Digger.
     *
     * @param reference the unique database reference that was used to request this waveform preview
     * @param anlzFile the parsed rekordbox track analysis file containing the waveform preview
     */
    @SuppressWarnings("WeakerAccess")
    public WaveformDetail(DataReference reference, RekordboxAnlz anlzFile) {
        dataReference = reference;
        rawMessage = null;
        ByteBuffer found = null;
        boolean colorFound = false;

        for (RekordboxAnlz.TaggedSection section : anlzFile.sections()) {
            if (WaveformFinder.getInstance().isColorPreferred() && section.body() instanceof RekordboxAnlz.WaveColorScrollTag) {
                RekordboxAnlz.WaveColorScrollTag tag = (RekordboxAnlz.WaveColorScrollTag) section.body();
                found = ByteBuffer.wrap(tag.entries()).asReadOnlyBuffer();
                colorFound = true;
                break;
            }
            if (section.body() instanceof RekordboxAnlz.WaveScrollTag) {
                RekordboxAnlz.WaveScrollTag tag = (RekordboxAnlz.WaveScrollTag) section.body();
                found = ByteBuffer.wrap(tag.entries()).asReadOnlyBuffer();
                // Keep going in case we also find the color version, which is better.
            }
        }
        detailBuffer = found;
        isColor = colorFound;
        if (detailBuffer == null) {
            throw new IllegalStateException("Could not construct WaveformDetail, missing from ANLZ file " + anlzFile);
        }
        logger.debug("Created waveform, isColor? " + isColor + ", frameCount: " + getFrameCount() + ", data size: " + getData().remaining());
    }

    /**
     * The different colors the monochrome (blue) waveform can be based on its intensity.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Color[] COLOR_MAP = {
            new Color(0, 104, 144),
            new Color(0, 136, 176),
            new Color(0, 168, 232),
            new Color(0, 184, 216),
            new Color(120, 184, 216),
            new Color(136, 192, 232),
            new Color(136, 192, 232),
            new Color(200, 224, 232)
    };

    /**
     * Color waveforms are represented by a series of sixteen bit integers into which color and height information are
     * packed. This function returns the integer corresponding to a particular half-frame in the waveform.
     *
     * @param waveBytes the raw data making up the waveform
     * @param segment the index of hte half-frame of interest
     *
     * @return the sixteen-bit number encoding the height and RGB values of that segment
     */
    private int getColorWaveformBits(final ByteBuffer waveBytes, final int segment) {
        final int base = (segment * 2);
        final int big = Util.unsign(waveBytes.get(base));
        final int small = Util.unsign(waveBytes.get(base + 1));
        return big * 256 + small;
    }

    /**
     * Determine the height of the waveform given an index into it. If {@code scale} is larger than 1 we are zoomed out,
     * so we determine an average height of {@code scale} segments starting with the specified one.
     *
     * @param segment the index of the first waveform byte to examine
     * @param scale the number of wave segments being drawn as a single pixel column
     *
     * @return a value from 0 to 31 representing the height of the waveform at that segment, which may be an average
     *         of a number of values starting there, determined by the scale
     */
    @SuppressWarnings("WeakerAccess")
    public int segmentHeight(final int segment, final int scale) {
        final ByteBuffer waveBytes = getData();
        final int limit = getFrameCount();
        int sum = 0;
        for (int i = segment; (i < segment + scale) && (i < limit); i++) {
            if (isColor) {
                sum += (getColorWaveformBits(waveBytes, segment) >> 2) & 0x1f;
            } else {
                sum += waveBytes.get(i) & 0x1f;
            }
        }
        return sum / scale;
    }

    /**
     * Determine the color of the waveform given an index into it. If {@code scale} is larger than 1 we are zoomed out,
     * so we determine an average color of {@code scale} segments starting with the specified one.
     *
     * @param segment the index of the first waveform byte to examine
     * @param scale the number of wave segments being drawn as a single pixel column
     *
     * @return the color of the waveform at that segment, which may be based on an average
     *         of a number of values starting there, determined by the scale
     */
    @SuppressWarnings("WeakerAccess")
    public Color segmentColor(final int segment, final int scale) {
        final ByteBuffer waveBytes = getData();
        final int limit = getFrameCount();
        if (isColor) {
            int red = 0;
            int green = 0;
            int blue = 0;
            for (int i = segment; (i < segment + scale) && (i < limit); i++) {
                int bits = getColorWaveformBits(waveBytes, segment);
                red += (bits >> 13) & 7;
                green += (bits >> 10) & 7;
                blue += (bits >> 7) & 7;
            }
            return new Color(red * 255 / (scale * 7), blue * 255 / (scale * 7), green * 255 / (scale * 7));
        }
        int sum = 0;
        for (int i = segment; (i < segment + scale) && (i < limit); i++) {
            sum += (waveBytes.get(i) & 0xe0) >> 5;
        }
        return COLOR_MAP[sum / scale];
    }



    @Override
    public String toString() {
        return "WaveformDetail[dataReference=" + dataReference + ", size:" + getData().remaining() + "]";
    }
}
