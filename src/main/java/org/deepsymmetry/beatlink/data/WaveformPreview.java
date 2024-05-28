package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;
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
@API(status = API.Status.STABLE)
public class WaveformPreview {

    /**
     * The number of bytes at the start of the color waveform data to be skipped when that was loaded using the
     * nxs2 ANLZ tag request. We actually know what these mean, now that we know how to parse EXT files, but we
     * can simply skip them anyway.
     */
    @API(status = API.Status.STABLE)
    public static final int LEADING_DBSERVER_COLOR_JUNK_BYTES = 28;

    /**
     * The unique identifier that was used to request this waveform preview.
     */
    @API(status = API.Status.STABLE)
    public final DataReference dataReference;

    /**
     * The message holding the preview as it was read over the network, if it came from the dbserver.
     * This can be used to analyze fields that have not yet been reliably understood, and is also used for storing
     * the cue list in a file.
     */
    @API(status = API.Status.STABLE)
    public final Message rawMessage;

    /**
     * The waveform data in its most convenient form. The results of expanding the data we received from the player's
     * NFS server if this preview was retrieved by Crate Digger rather than from the dbserver.
     * If it is a color waveform, the leading junk bytes have been removed.
     */
    private final ByteBuffer expandedData;

    /**
     * Indicates whether this is an NXS2-style color waveform, or a monochrome (blue) waveform.
     */
    @API(status = API.Status.STABLE)
    public final boolean isColor;

    /**
     * Get the raw bytes of the waveform preview data
     *
     * @return the bytes from which the preview can be drawn, as described in the
     * <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#_waveform_previews">Packet Analysis document</a>.
     */
    @API(status = API.Status.STABLE)
    public ByteBuffer getData() {
        expandedData.rewind();
        return expandedData.slice();
    }

    /**
     * The pixel width (number of waveform preview columns) available.
     */
    @API(status = API.Status.STABLE)
    public final int segmentCount;

    /**
     * Holds the maximum height of any point along the waveform, so that it can be drawn in a normalized manner to fit
     * its display area.
     */
    @API(status = API.Status.STABLE)
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
    @API(status = API.Status.STABLE)
    public JComponent createViewComponent(TrackMetadata metadata) {
        return new WaveformPreviewComponent(this, metadata);
    }

    /**
     * Figures out how many segments (columns) are in the preview.
     *
     * @return the width, in pixels, at which the preview should ideally be drawn
     */
    private int getSegmentCount() {
        return getData().remaining() / (isColor? 6 : 2);
    }

    /**
     * Scan the segments to find the largest height value present.
     *
     * @return the largest waveform height anywhere in the preview.
     */
    private int getMaxHeight() {
        int result = 0;
        for (int i = 0; i < segmentCount; i++) {
            result = Math.max(result, segmentHeight(i, false));
        }
        return result;
    }

    /**
     * Constructor when reading from the network or a file.
     *
     * @param reference the unique database reference that was used to request this waveform preview
     * @param message the response that contains the preview
     */
    WaveformPreview(DataReference reference, Message message) {
        isColor = message.knownType == Message.KnownType.ANLZ_TAG;  // If we got one of these, it's an NXS2 color wave.
        dataReference = reference;
        rawMessage = message;
        ByteBuffer data = ((BinaryField) rawMessage.arguments.get(3)).getValue();
        data.position(isColor? LEADING_DBSERVER_COLOR_JUNK_BYTES : 0);
        expandedData = data.slice();
        segmentCount = getSegmentCount();
        maxHeight = getMaxHeight();
    }

    /**
     * Constructor when received from Crate Digger.
     *
     * @param reference the unique database reference that was used to request this waveform preview
     * @param anlzFile the parsed rekordbox track analysis file containing the waveform preview
     */
    @API(status = API.Status.STABLE)
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
                if (tag.lenData() < 400 || tag.data() == null) {
                    continue;  // We want to ignore the tiny previews, and not crash for vestigial tags without data.
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
        segmentCount = getSegmentCount();
        maxHeight = getMaxHeight();
    }

    /**
     * Constructor when creating from an external caching mechanism.
     *
     * @param reference the unique database reference that was used to request this waveform preview
     * @param data the expanded data as will be returned by {@link #getData()}
     * @param isColor indicates whether the data represents a color preview
     */
    @API(status = API.Status.STABLE)
    public WaveformPreview(DataReference reference, ByteBuffer data, boolean isColor) {
        dataReference = reference;
        rawMessage = null;
        this.isColor = isColor;
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        expandedData = ByteBuffer.wrap(bytes).asReadOnlyBuffer();
        segmentCount = getSegmentCount();
        maxHeight = getMaxHeight();
    }

    /**
     * The color at which segments of the blue waveform marked most intense are drawn.
     */
    @API(status = API.Status.STABLE)
    public static final Color INTENSE_COLOR = new Color(116, 246, 244);

    /**
     * The color at which non-intense blue waveform segments are drawn.
     */
    @API(status = API.Status.STABLE)
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
    @API(status = API.Status.STABLE)
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
    @API(status = API.Status.STABLE)
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
                ", segments:" + segmentCount + "]";
    }
}
