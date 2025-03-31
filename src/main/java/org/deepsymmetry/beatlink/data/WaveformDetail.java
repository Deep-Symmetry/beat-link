package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.Util;
import org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle;
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
@API(status = API.Status.STABLE)
public class WaveformDetail {

    @SuppressWarnings({"unused"})
    private final Logger logger = LoggerFactory.getLogger(WaveformDetail.class);

    /**
     * The number of bytes at the start of the waveform data which do not seem to be valid or used when it is served
     * by the dbserver protocol. They are not present when the ANLZ.EXT file is loaded directly by Crate Digger.
     */
    @API(status = API.Status.STABLE)
    public static final int LEADING_DBSERVER_JUNK_BYTES = 19;

    /**
     * The number of bytes at the start of the color waveform data to be skipped when that was loaded using the
     * nxs2 ANLZ tag request. We actually know what these mean, now that we know how to parse EXT files, but we
     * can simply skip them anyway.
     */
    @API(status = API.Status.STABLE)
    public static final int LEADING_DBSERVER_COLOR_JUNK_BYTES = 28;

    /**
     * The unique identifier that was used to request this waveform detail.
     */
    @API(status = API.Status.STABLE)
    public final DataReference dataReference;

    /**
     * The message holding the detail as it was read over the network. This can be used to analyze fields
     * that have not yet been reliably understood, and is also used for storing the cue list in a file.
     * This will be {@code null} if the data was obtained from Crate Digger.
     */
    @API(status = API.Status.STABLE)
    public final Message rawMessage;

    /**
     * The parsed structure holding the detail as it was extracted from the extended analysis file, regardless of
     * how it was obtained.
     */
    private final ByteBuffer detailBuffer;

    /**
     * Indicates whether this is an NXS2-style color waveform, or a monochrome (blue) waveform (use predates
     * the existence of {@link #style}). Has no meaning if {@link #style} is {@link WaveformFinder.WaveformStyle#THREE_BAND}.
     *
     * @deprecated since 8.0.0
     */
    @API(status = API.Status.DEPRECATED)
    public final boolean isColor;

    /**
     * Indicates the format of this waveform preview.
     */
    @API(status = API.Status.EXPERIMENTAL)
    public final WaveformStyle style;

    /**
     * Get the raw bytes of the waveform detail data
     *
     * @return the bytes from which the detail can be drawn, as described in the
     * <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#_detailed_waveforms">Packet Analysis document</a>.
     */
    @API(status = API.Status.STABLE)
    public ByteBuffer getData() {
        detailBuffer.rewind();
        return detailBuffer.slice();
    }

    /**
     * Count the half-frames of waveform available.
     *
     * @return the number of half-frames (pixel columns) that make up the track
     */
    @API(status = API.Status.STABLE)
    public int getFrameCount() {
        final int bytes = getData().remaining();

        switch (style) {
            case THREE_BAND: return bytes / 3;
            case RGB: return bytes / 2;
            case BLUE: return bytes;
        }

        throw new IllegalStateException("Unrecognized waveform style: " + style);
    }

    /**
     * Determine how long the track plays, in milliseconds. This provides a more accurate value than the track
     * metadata, which is accurate only to the second, because we know how many half-frames (1/150 of a second)
     * the track is composed of.
     *
     * @return the number of milliseconds it will take to play all half-frames that make up the track
     */
    @API(status = API.Status.STABLE)
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
    @API(status = API.Status.STABLE)
    public JComponent createViewComponent(TrackMetadata metadata, BeatGrid beatGrid) {
        return new WaveformDetailComponent(this, metadata, beatGrid);
    }

    /**
     * Constructor when reading from the network or a file.
     *
     * @param reference the unique database reference that was used to request this waveform detail
     * @param message the response that contains the preview
     */
    @API(status = API.Status.STABLE)
    public WaveformDetail(DataReference reference, Message message) {
        isColor = message.knownType == Message.KnownType.ANLZ_TAG;  // If we got one of these, it's an NXS2 color wave.
        style = isColor? WaveformStyle.RGB : WaveformStyle.BLUE;  // We don't yet know how to request 3-band waveforms using the dbserver protocol.
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
    @API(status = API.Status.STABLE)
    public WaveformDetail(DataReference reference, RekordboxAnlz anlzFile) {
        dataReference = reference;
        rawMessage = null;
        ByteBuffer found = null;
        boolean threeBandFound = false;
        boolean colorFound = false;

        for (RekordboxAnlz.TaggedSection section : anlzFile.sections()) {

            // Check for requested 3-band waveform first
            if (WaveformFinder.getInstance().getPreferredStyle() == WaveformStyle.THREE_BAND &&
                    section.body() instanceof RekordboxAnlz.Wave3bandScrollTag) {
                RekordboxAnlz.Wave3bandScrollTag tag = (RekordboxAnlz.Wave3bandScrollTag) section.body();
                found = ByteBuffer.wrap(tag.entries()).asReadOnlyBuffer();
                threeBandFound = true;
                break;
            }

            // Next see if requested color waveform present
            if (WaveformFinder.getInstance().getPreferredStyle() == WaveformStyle.RGB &&
                    section.body() instanceof RekordboxAnlz.WaveColorScrollTag) {
                RekordboxAnlz.WaveColorScrollTag tag = (RekordboxAnlz.WaveColorScrollTag) section.body();
                found = ByteBuffer.wrap(tag.entries()).asReadOnlyBuffer();
                colorFound = true;
                break;
            }

            // Finally fall back to blue/white waveform
            if (section.body() instanceof RekordboxAnlz.WaveScrollTag) {
                RekordboxAnlz.WaveScrollTag tag = (RekordboxAnlz.WaveScrollTag) section.body();
                found = ByteBuffer.wrap(tag.entries()).asReadOnlyBuffer();
                // Keep going in case we also find a requested color version, which is better.
            }
        }

        detailBuffer = found;
        style = threeBandFound? WaveformStyle.THREE_BAND : (colorFound? WaveformStyle.RGB : WaveformStyle.BLUE);
        isColor = colorFound;
        if (detailBuffer == null) {
            throw new IllegalStateException("Could not construct WaveformDetail, missing from ANLZ file " + anlzFile);
        }
    }

    /**
     * Constructor for use with external caching mechanisms, for code that predates 3-band support.
     *
     * @param reference the unique database reference that was used to request this waveform preview
     * @param data the waveform data as will be returned by {@link #getData()}
     * @param isColor indicates whether the data represents a color waveform
     *
     * @deprecated since 8.0.0
     */
    @API(status = API.Status.DEPRECATED)
    public WaveformDetail(DataReference reference, ByteBuffer data, boolean isColor) {
        this(reference, data, isColor? WaveformStyle.RGB : WaveformStyle.BLUE);
    }

    /**
     * Constructor for use with external caching mechanisms.
     *
     * @param reference the unique database reference that was used to request this waveform preview
     * @param data the waveform data as will be returned by {@link #getData()}
     * @param style indicates the style of the waveform
     */
    @API(status = API.Status.EXPERIMENTAL)
    public WaveformDetail(DataReference reference, ByteBuffer data, WaveformStyle style) {
        dataReference = reference;
        rawMessage = null;
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        detailBuffer = ByteBuffer.wrap(bytes).asReadOnlyBuffer();
        isColor = style == WaveformStyle.RGB;
        this.style = style;
    }

    /**
     * The different colors the monochrome (blue) waveform can be based on its intensity.
     */
    @API(status = API.Status.STABLE)
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
     * @throws UnsupportedOperationException for three-band waveforms since each band has a different height
     */
    @API(status = API.Status.STABLE)
    public int segmentHeight(final int segment, final int scale) {
        final ByteBuffer waveBytes = getData();
        final int limit = getFrameCount();
        int sum = 0;
        for (int i = segment; (i < segment + scale) && (i < limit); i++) {
            switch (style) {
                case THREE_BAND:
                    throw new UnsupportedOperationException();

                case RGB:
                    sum += (getColorWaveformBits(waveBytes, segment) >> 2) & 0x1f;
                    break;

                case BLUE:
                    sum += waveBytes.get(i) & 0x1f;
                    break;

                default:
                    throw new IllegalStateException("Unrecognized waveform style: " + style);
            }
        }
        return sum / scale;
    }

    /**
     * Determine the height of a three-band preview given an index into it.
     *
     * @param segment the index of the waveform preview segment to examine
     * @param scale the number of wave segments being drawn as a single pixel column
     * @param band the band whose height is wanted
     *
     * @return a value from 0 to 31 representing the height of the waveform at that segment, which may be an average
     *         of a number of values starting there, determined by the scale
     * @throws UnsupportedOperationException if called on a non three-band waveform
     */
    public int segmentHeight(final int segment, final int scale, final WaveformFinder.ThreeBandLayer band) {
        if (style != WaveformStyle.THREE_BAND) throw new UnsupportedOperationException();

        final ByteBuffer bytes = getData();
        final int limit = getFrameCount();
        int sum = 0;

        for (int i = segment; (i < segment + scale) && (i < limit); i++) {
            final int base = i * 3;

            switch (band) {
                case LOW:
                    sum += Math.round(Util.unsign(bytes.get(base + 2)) * 0.4f);
                    break;

                case MID:
                    sum += Math.round(Util.unsign(bytes.get(base)) * 0.3f);
                    break;

                case HIGH:
                    sum += Math.round(Util.unsign(bytes.get(base + 1)) * 0.06f);
                    break;

                default:
                    throw new IllegalStateException("Unrecognized three-band waveform band: " + band);
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
     * @throws UnsupportedOperationException if called on a three-band waveform, colors are fixed for each band
     */
    @API(status = API.Status.STABLE)
    public Color segmentColor(final int segment, final int scale) {
        final ByteBuffer waveBytes = getData();
        final int limit = getFrameCount();

        switch (style) {
            case THREE_BAND:
                throw new UnsupportedOperationException();

            case RGB:
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

            case BLUE:
                int sum = 0;
                for (int i = segment; (i < segment + scale) && (i < limit); i++) {
                    sum += (waveBytes.get(i) & 0xe0) >> 5;
                }
                return COLOR_MAP[sum / scale];
        }

        throw new IllegalStateException("Unrecognized waveform style: " + style);
    }



    @Override
    public String toString() {
        return "WaveformDetail[dataReference=" + dataReference + ", size:" + getData().remaining() + "]";
    }
}
