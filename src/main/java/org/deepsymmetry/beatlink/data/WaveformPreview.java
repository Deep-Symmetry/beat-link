package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.Util;
import org.deepsymmetry.beatlink.dbserver.BinaryField;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;

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
     * The number of bytes at the start of the color waveform data to be skipped when that was loaded using the
     * nxs2 ANLZ tag request. We actually know what these mean, now that we know how to parse EXT files, but we
     * can simply skip them anyway.
     */
    @SuppressWarnings("WeakerAccess")
    public static final int LEADING_DBSERVER_COLOR_JUNK_BYTES = 28;

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
     * by Crate Digger rather than from the dbserver. If it is a color waveform, the data is always in expanded
     * form.
     */
    private final ByteBuffer expandedData;

    /**
     * Indicates whether this is an NXS2-style color waveform, or a monochrome (blue) waveform.
     */
    @SuppressWarnings("WeakerAccess")
    public final boolean isColor;

    /**
     * Get the raw bytes of the waveform preview data
     *
     * @return the bytes from which the preview can be drawn, as described in Section 5.8 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public ByteBuffer getData() {
        if (expandedData != null) {
            expandedData.rewind();
            return expandedData.slice();
        }
        return ((BinaryField) rawMessage.arguments.get(3)).getValue();
    }

    /**
     * Get the pixel width (number of waveform preview columns) available.
     *
     * @return the width required to draw the entire preview.
     */
    @SuppressWarnings("WeakerAccess")
    public int getSegmentCount() {
        return getData().remaining() / (isColor? 6 : 2);
    }

    /**
     * Holds the maximum height of any point along the waveform, so that it can drawn in a normalized manner to fit
     * its display area.
     */
    @SuppressWarnings("WeakerAccess")
    public final int maxHeight;

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
     * Scan the segments to find the largest height value present.
     *
     * @return the largest waveform height anywhere in the preview.
     */
    private int findMaxHeight() {
        int result = 0;
        for (int i = 0; i < getSegmentCount(); i++) {
            result = Math.max(result, segmentHeight(i, false));
        }
        return result;
    }

    /**
     * Constructor when reading from the network or a cache file.
     *
     * @param reference the unique database reference that was used to request this waveform preview
     * @param message the response that contains the preview
     */
    WaveformPreview(DataReference reference, Message message) {
        isColor = message.knownType == Message.KnownType.ANLZ_TAG;  // If we got one of these, its an NXS2 color wave.
        dataReference = reference;
        rawMessage = message;
        if (isColor) {
            ByteBuffer data = ((BinaryField) rawMessage.arguments.get(3)).getValue();
            data.position(LEADING_DBSERVER_COLOR_JUNK_BYTES);
            expandedData = data.slice();
        } else {
            expandedData = null;
        }
        maxHeight = findMaxHeight();
    }

    /**
     * Constructor when received from Crate Digger.
     *
     * @param reference the unique database reference that was used to request this waveform preview
     * @param anlzFile the parsed rekordbox track analysis file containing the waveform preview
     */
    @SuppressWarnings("WeakerAccess")
    public WaveformPreview(DataReference reference, RekordboxAnlz anlzFile) {
        dataReference = reference;
        rawMessage = null;
        ByteBuffer found = null;
        boolean colorFound = false;

        for (RekordboxAnlz.TaggedSection section : anlzFile.sections()) {
            if (WaveformFinder.getInstance().isColorPreferred() && section.body() instanceof  RekordboxAnlz.WaveColorPreviewTag) {
                RekordboxAnlz.WaveColorPreviewTag tag = (RekordboxAnlz.WaveColorPreviewTag) section.body();
                found = ByteBuffer.wrap(tag.entries()).asReadOnlyBuffer();
                colorFound = true;
                break;
            }
            if (section.body() instanceof RekordboxAnlz.WavePreviewTag) {
                RekordboxAnlz.WavePreviewTag tag = (RekordboxAnlz.WavePreviewTag) section.body();
                if (tag.lenPreview() < 400) {
                    continue;  // We want to ignore the tiny previews
                }
                byte[] tagBytes = tag.data();
                byte[] bytes = new byte[tagBytes.length * 2];
                for (int i = 0; i < tagBytes.length; i++) {
                    bytes[i * 2] = (byte)(tagBytes[i] & 0x1f);
                    bytes[(i * 2) + 1] = (byte)((tagBytes[i] >> 5) & 7);
                }
                found = ByteBuffer.wrap(bytes).asReadOnlyBuffer();
                // Keep on going in case we also find the color version, which is better.
            }
        }
        expandedData = found;
        isColor = colorFound;
        if (expandedData == null) {
            throw new IllegalStateException("Could not construct WaveformPreview, missing from ANLZ file " + anlzFile);
        }
        maxHeight = findMaxHeight();
    }

    /**
     * The color at which segments of the blue waveform marked most intense are drawn.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Color INTENSE_COLOR = new Color(116, 246, 244);

    /**
     * The color at which non-intense blue waveform segments are drawn.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Color NORMAL_COLOR = new Color(43, 89, 255);

    /**
     * Determine the height of the preview given an index into it.
     *
     * @param segment the index of the waveform preview segment to examine
     * @param front if {@code true} the height of the front (brighter) segment of a color waveform preview is returned,
     *              otherwise the height of the back (dimmer) segment is returned. Has no effect for blue previews.
     *
     * @return a value from 0 to 31 representing the height of the waveform at that segment, which may be an average
     *         of a number of values starting there, determined by the scale
     */
    @SuppressWarnings("WeakerAccess")
    public int segmentHeight(final int segment, final boolean front) {
        final ByteBuffer bytes = getData();
        if (isColor) {
            final int base = segment * 6;
            final int frontHeight = Util.unsign(bytes.get(base + 5));
            if (front) {
                return frontHeight;
            } else {
                return Math.max(frontHeight, Math.max(Util.unsign(bytes.get(base + 3)), Util.unsign(bytes.get(base + 4))));
            }
        } else {
            return getData().get(segment * 2) & 0x1f;
        }
    }

    /**
     * Determine the color of the waveform given an index into it.
     *
     * @param segment the index of the first waveform byte to examine
     * @param front if {@code true} the front (brighter) segment of a color waveform preview is returned,
     *              otherwise the back (dimmer) segment is returned. Has no effect for blue previews.
     *
     * @return the color of the waveform at that segment, which may be based on an average
     *         of a number of values starting there, determined by the scale
     */
    @SuppressWarnings("WeakerAccess")
    public Color segmentColor(final int segment, final boolean front) {
        final ByteBuffer bytes = getData();
        if (isColor) {
            final int base = segment * 6;
            final int backHeight = segmentHeight(segment, false);
            if (backHeight == 0) {
                return Color.BLACK;
            }
            final int maxLevel = front? 255 : 191;
            final int red = Util.unsign(bytes.get(base + 3)) * maxLevel / backHeight;
            final int green = Util.unsign(bytes.get(base + 4)) * maxLevel / backHeight;
            final int blue = Util.unsign(bytes.get(base + 5)) * maxLevel / backHeight;
            return new Color(red, green, blue);
        } else {
            final int intensity = getData().get(segment * 2 + 1) & 0x07;
            return (intensity >= 5) ? INTENSE_COLOR : NORMAL_COLOR;
        }
    }

    @Override
    public String toString() {
        return "WaveformPreview[dataReference=" + dataReference + ", isColor? " + isColor + ", size:" + getData().remaining() +
                ", segments:" + getSegmentCount() + "]";
    }
}
