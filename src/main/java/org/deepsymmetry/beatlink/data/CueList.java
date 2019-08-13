package org.deepsymmetry.beatlink.data;

import io.kaitai.struct.ByteBufferKaitaiStream;
import org.deepsymmetry.beatlink.Util;
import org.deepsymmetry.beatlink.dbserver.BinaryField;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.beatlink.dbserver.NumberField;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.List;

/**
 * Provides information about each memory point, hot cue, and loop stored for a track.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class CueList {

    private static final Logger logger = LoggerFactory.getLogger(CueList.class);

    /**
     * The message holding the cue list information as it was read over the network. This can be used to analyze fields
     * that have not yet been reliably understood, and is also used for storing the cue list in a cache file. This will
     * be {@code null} if the cue list was not obtained from a dbserver query.
     */
    public final Message rawMessage;

    /**
     * The bytes from which the Kaitai Struct tags holding cue list information were parsed from an ANLZ file.
     * Will be {@code null} if the cue list was obtained from a dbserver query.
     */
    public final List<ByteBuffer> rawTags;

    /**
     * The bytes from which the Kaitai Struct tags holding the nxs2 cue list information (which can include DJ-assigned
     * comment text for each cue) were parsed from an extended ANLZ file. Will be {@code null} if the cue list was
     * obtained from a dbserver query, and empty if there were no nxs2 cue tags available in the analysis data.
     */
    public final List<ByteBuffer> rawExtendedTags;

    /**
     * Return the number of entries in the cue list that represent hot cues.
     *
     * @return the number of cue list entries that are hot cues
     */
    public int getHotCueCount() {
        if (rawMessage != null) {
            return (int) ((NumberField) rawMessage.arguments.get(5)).getValue();
        }
        int total = 0;
        for (Entry entry : entries) {
            if (entry.hotCueNumber > 0) {
                ++total;
            }
        }
        return total;
    }

    /**
     * Return the number of entries in the cue list that represent ordinary memory points, rather than hot cues.
     * The memory points can also be loops.
     *
     * @return the number of cue list entries other than hot cues
     */
    public int getMemoryPointCount() {
        if (rawMessage != null) {
            return (int) ((NumberField) rawMessage.arguments.get(6)).getValue();
        }
        int total = 0;
        for (Entry entry : entries) {
            if (entry.hotCueNumber == 0) {
                ++total;
            }
        }
        return total;
    }

    /**
     * Returns the entry whose track position comes most closely before the specified number of milliseconds, if any.
     * If there is a cue which falls exactly at the specified time, it will be returned (and it will also be returned
     * by {@link #findEntryAfter(long)}). If there is more than one entry at the exact time as the one that is returned,
     * the one chosen will be unpredictable.
     *
     * All times are rounded to half frame units, because that is the resolution at which cues are stored.
     *
     * @param milliseconds the time of interest within the track
     * @return the cue whose start time is closest to the specified time but not after it
     */
    public Entry findEntryBefore(long milliseconds) {
        final Entry target = new Entry(0, Util.timeToHalfFrame(milliseconds), "", null, null);
        int index = Collections.binarySearch(entries, target, TIME_ONLY_COMPARATOR);
        if (index >= 0) {  // An exact match
            return entries.get(index);
        }

        // The exact time was not found, so convert the result to the index where the time would be inserted.
        index = -(index + 1);
        if (index > 0) {  // If there is a value before where we should insert this time, that's what we should return.
            return entries.get((index - 1));
        }

        // There was no cue at or before the desired time.
        return null;
    }

    /**
     * Returns the entry whose track position comes most closely after the specified number of milliseconds, if any.
     * If there is a cue which falls exactly at the specified time, it will be returned (and it will also be returned
     * by {@link #findEntryBefore(long)}). If there is more than one entry at the exact time as the one that is returned,
     * the one chosen will be unpredictable.
     *
     * All times are rounded to half frame units, because that is the resolution at which cues are stored.
     *
     * @param milliseconds the time of interest within the track
     * @return the cue whose start time is closest to the specified time but not before it
     */
    public Entry findEntryAfter(long milliseconds) {
        final Entry target = new Entry(0, Util.timeToHalfFrame(milliseconds), "", null, null);
        int index = Collections.binarySearch(entries, target, TIME_ONLY_COMPARATOR);
        if (index >= 0) {
            return entries.get(index);
        }

        // The exact time was not found, so convert the result to the index where the time would be inserted.
        index = -(index + 1);
        if (index < entries.size()) {  // If there is a value where we should insert this time, that's what we should return.
            return entries.get(index);
        }

        // There was no cue at or after the desired time.
        return null;
    }

    /**
     * Breaks out information about each entry in the cue list.
     */
    public static class Entry {

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Entry entry = (Entry) o;

            if (hotCueNumber != entry.hotCueNumber) return false;
            if (isLoop != entry.isLoop) return false;
            if (cuePosition != entry.cuePosition) return false;
            if (cueTime != entry.cueTime) return false;
            if (loopPosition != entry.loopPosition) return false;
            return loopTime == entry.loopTime;
        }

        @Override
        public int hashCode() {
            int result = hotCueNumber;
            result = 31 * result + (isLoop ? 1 : 0);
            result = 31 * result + (int) (cuePosition ^ (cuePosition >>> 32));
            result = 31 * result + (int) (cueTime ^ (cueTime >>> 32));
            result = 31 * result + (int) (loopPosition ^ (loopPosition >>> 32));
            result = 31 * result + (int) (loopTime ^ (loopTime >>> 32));
            return result;
        }

        /**
         * If this has a non-zero value, this entry is a hot cue with that identifier.
         */

        public final int hotCueNumber;

        /**
         * Indicates whether this entry represents a loop, as opposed to a simple cue point.
         */
        public final boolean isLoop;

        /**
         * Indicates the location of the cue in half-frame units, which are 1/150 of a second. If the cue is a loop,
         * this is the start of the loop.
         */
        public final long cuePosition;

        /**
         * Indicates the location of the cue in milliseconds. If the cue is a loop, this is the start of the loop.
         */
        public final long cueTime;

        /**
         * If the entry represents a loop, indicates the loop point in half-frame units, which are 1/150 of a second.
         * The loop point is the end of the loop, at which point playback jumps back to {@link #cuePosition}.
         */
        public final long loopPosition;

        /**
         * If the entry represents a loop, indicates the loop point in milliseconds. The loop point is the end of the
         * loop, at which point playback jumps back to {@link #cueTime}.
         */
        public final long loopTime;

        /**
         * If the entry was constructed from an extended nxs2-style commented cue tag, and the DJ assigned a comment
         * to the cue, this will contain the comment text. Otherwise it will be an empty string.
         */
        public final String comment;

        /**
         * The explicit color embedded into the cue, or {@code null} if there was none.
         */
        public final Color embeddedColor;

        /**
         * The color with which this cue will be displayed in rekordbox, if it is a hot cue with a recognized
         * color code, or {@code null} if that does not apply.
         */
        public final Color rekordboxColor;

        /**
         * Constructor for non-loop entries.
         *
         * @param number if non-zero, this is a hot cue, with the specified identifier
         * @param position the position of this cue/memory point in half-frame units, which are 1/150 of a second
         * @param comment the DJ-assigned comment, or an empty string if none was assigned
         * @param embeddedColor the explicit color embedded in the cue, if any
         * @param rekordboxColor the color that rekordbox will display for this cue, if available
         */
        public Entry(int number, long position, String comment, Color embeddedColor, Color rekordboxColor) {
            if (comment == null) throw new NullPointerException("comment must not be null");
            hotCueNumber = number;
            cuePosition = position;
            cueTime = Util.halfFrameToTime(position);
            isLoop = false;
            loopPosition = 0;
            loopTime = 0;
            this.comment = comment.trim();
            this.embeddedColor = embeddedColor;
            this.rekordboxColor = rekordboxColor;
        }

        /**
         * Constructor for loop entries.
         *
         * @param number if non-zero, this is a hot cue, with the specified identifier
         * @param startPosition the position of the start of this loop in half-frame units, which are 1/150 of a second
         * @param endPosition the position of the end of this loop in half-frame units
         * @param comment the DJ-assigned comment, or an empty string if none was assigned
         * @param embeddedColor the explicit color embedded in the cue, if any
         * @param rekordboxColor the color that rekordbox will display for this cue, if available
         */
        public Entry(int number, long startPosition, long endPosition, String comment, Color embeddedColor, Color rekordboxColor) {
            if (comment == null) throw new NullPointerException("comment must not be null");
            hotCueNumber = number;
            cuePosition = startPosition;
            cueTime = Util.halfFrameToTime(startPosition);
            isLoop = true;
            loopPosition = endPosition;
            loopTime = Util.halfFrameToTime(endPosition);
            this.comment = comment.trim();
            this.embeddedColor = embeddedColor;
            this.rekordboxColor = rekordboxColor;
        }

        /**
         * Determine the color that an original Nexus series player would use to display this cue. Hot cues are
         * green, loops are orange, and ordinary memory points are red.
         *
         * @return the color that represents this cue on players that don't support nxs2 colored cues.
         */
        public Color getNexusColor() {
            if (hotCueNumber > 0) {
                return Color.GREEN;
            }
            if (isLoop) {
                return Color.ORANGE;
            }
            return Color.RED;
        }

        /**
         * Determine the best color to be used to display this cue. If there is an indexed rekordbox color in the cue,
         * use that; otherwise, if there is an explicit color embedded, use that, and if neither of those is available,
         * delegate to {@link #getNexusColor()}.
         *
         * @return the most suitable available display color for the cue
         */
        public Color getColor() {
            if (rekordboxColor != null) {
                return rekordboxColor;
            }
            if (embeddedColor != null) {
                return embeddedColor;
            }
            return getNexusColor();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (hotCueNumber == 0) {
                if (isLoop) {
                    sb.append("Loop[");
                } else {
                    sb.append("Memory Point[");
                }
            } else {
                sb.append("Hot Cue ").append((char)(hotCueNumber + '@')).append('[');
            }
            sb.append("time ").append(cueTime).append("ms");
            if (isLoop) {
                sb.append(", loop time ").append(loopTime).append("ms");
            }
            if (!comment.isEmpty()) {
                sb.append(", comment ").append(comment);
            }
            sb.append(']');
            return sb.toString();
        }
    }

    /**
     * The entries present in the cue list, sorted into order by increasing position, with hot cues coming after
     * ordinary memory points if both are at the same position (as often seems to happen).
     */
    public final List<Entry> entries;

    /**
     * A comparator for sorting or searching entries that considers only their position within the track, and
     * not whether they are a hot cue.
     */
    public static final Comparator<Entry> TIME_ONLY_COMPARATOR = new Comparator<Entry>() {
        @Override
        public int compare(Entry entry1, Entry entry2) {
            return (int) (entry1.cuePosition - entry2.cuePosition);
        }
    };

    /**
     * The comparator used for sorting the cue list entries during construction, which orders them by position
     * within the track, with hot cues coming after ordinary memory points if both exist at the same position.
     * This often happens, and moving hot cues to the end ensures the waveform display components identify that
     * position as a hot cue, which is important information.
     */
    public static final Comparator<Entry> SORT_COMPARATOR = new Comparator<Entry>() {
        @Override
        public int compare(Entry entry1, Entry entry2) {
            int result = (int) (entry1.cuePosition - entry2.cuePosition);
            if (result == 0) {
                int h1 = (entry1.hotCueNumber != 0) ? 1 : 0;
                int h2 = (entry2.hotCueNumber != 0) ? 1 : 0;
                result = h1 - h2;
            }
            return result;
        }
    };

    /**
     * Sorts the entries into the order we want to present them in, which is by position, with hot cues coming after
     * ordinary memory points if both exist at the same position, which often happens.
     *
     * @param loadedEntries the unsorted entries we have loaded from a dbserver message, metadata cache, or rekordbox
     *                database export
     * @return an immutable list of the collections in the proper order
     */
    private List<Entry> sortEntries(List<Entry> loadedEntries) {
        Collections.sort(loadedEntries, SORT_COMPARATOR);
        return Collections.unmodifiableList(loadedEntries);
    }

    /**
     * Helper method to add cue list entries from a parsed ANLZ cue tag
     *
     * @param entries the list of entries being accumulated
     * @param tag the tag whose entries are to be added
     */
    private void addEntriesFromTag(List<Entry> entries, RekordboxAnlz.CueTag tag) {
        for (RekordboxAnlz.CueEntry cueEntry : tag.cues()) {  // TODO: Need to figure out how to identify deleted entries to ignore.
            if (cueEntry.type() == RekordboxAnlz.CueEntryType.LOOP) {
                entries.add(new Entry((int)cueEntry.hotCue(), Util.timeToHalfFrame(cueEntry.time()),
                        Util.timeToHalfFrame(cueEntry.loopTime()), "", null, null));
            } else {
                entries.add(new Entry((int)cueEntry.hotCue(), Util.timeToHalfFrame(cueEntry.time()), "", null, null));
            }
        }
    }

    /**
     * Look up the embedded color that we expect to be paired with a given rekordbox color code, so we can warn if
     * something else is found instead, which implies our understanding of cue colors is incorrect.
     *
     * @param colorCode the first color byte
     * @return the color corresponding to the three bytes that are expected to follow it
     */
    private Color expectedEmbeddedColor(int colorCode) {
        switch (colorCode) {
            case 0x31:  // magenta
                return new Color(0xff, 0x00, 0xa1);
            case 0x38:  // violet
                return new Color(0x83, 0x00, 0xff);
            case 0x3c:  // fuchsia
                return new Color(0x80, 0x00, 0xff);
            case 0x3e:  // light slate blue
                return new Color(0x33, 0x00, 0xff);

            case 0x01:  // blue
                return new Color(0x00, 0x00, 0xff);
            case 0x05:  // steel blue
                return new Color(0x50, 0x07, 0xff);
            case 0x09:  // aqua
                return new Color(0x00, 0xe0, 0xff);
            case 0x0e:  // sea green
                return new Color(0x1f, 0xff, 0xa3);

            case 0x12:  // teal
                return new Color(0x00, 0xff, 0x47);
            case 0x16:  // green
                return new Color(0x1a, 0xff, 0x00);
            case 0x1a:  // lime
                return new Color(0x80,0xff, 0x00);
            case 0x1e:  // olive
                return new Color(0xe6, 0xff, 0x00);

            case 0x20:  // yellow
                return new Color(0xff, 0xe8, 0x00);
            case 0x26:  // orange
                return new Color(0xff, 0x5e, 0x00);
            case 0x2a:  // red
                return new Color(0xff, 0x00, 0x00);
            case 0x2d:  // pink
                return new Color(0xff, 0x00, 0x73);

            default:
                return null;
        }
    }

    /**
     * Decode the embedded color present in the cue entry, if there is one.
     *
     * @param entry the parsed cue entry
     * @return the embedded color value, or {@code null} if there is none present
     */
    private Color findEmbeddedColor(RekordboxAnlz.CueExtendedEntry entry) {
        if (entry.colorRed() == 0 && entry.colorGreen() == 0 && entry.colorBlue() == 0) {
            return null;
        }
        return new Color(entry.colorRed(), entry.colorGreen(), entry.colorBlue());
    }

    /**
     * Look up the color that rekordbox would use to display this cue. The colors in this table correspond
     * to the 4x4 grid that is available inside the hot cue configuration interface.
     *
     * @param colorCode the color index found in the cue
     * @return the corresponding color or {@code null} if the index is not recognized
     */
    private Color findRekordboxColor(int colorCode) {
        switch (colorCode) {
            case 0x31:  // magenta
                return new Color(0xde, 0x44, 0xcf);
            case 0x38:  // violet
                return new Color(0x84, 0x32, 0xff);
            case 0x3c:  // fuchsia
                return new Color(0xaa, 0x72, 0xff);
            case 0x3e:  // light slate blue
                return new Color(0x64, 0x73, 0xff);

            case 0x01:  // blue
                return new Color(0x30, 0x5a, 0xff);
            case 0x05:  // steel blue
                return new Color(0x50, 0xb4, 0xff);
            case 0x09:  // aqua
                return new Color(0x00, 0xe0, 0xff);
            case 0x0e:  // sea green
                return new Color(0x1f, 0xa3, 0x92);

            case 0x12:  // teal
                return new Color(0x10, 0xb1, 0x76);
            case 0x16:  // green
                return new Color(0x28, 0xe2, 0x14);
            case 0x1a:  // lime
                return new Color(0xa2,0xdd, 0x16);
            case 0x1e:  // olive
                return new Color(0xb4, 0xbe, 0x04);

            case 0x20:  // yellow
                return new Color(0xc3, 0xaf, 0x04);
            case 0x26:  // orange
                return new Color(0xe0, 0x64, 0x1b);
            case 0x2a:  // red
                return new Color(0xe5, 0x28, 0x28);
            case 0x2d:  // pink
                return new Color(0xfe, 0x12, 0x7a);

            case 0x00:  // none
                return null;

            default:
                logger.warn("Unrecognized rekordbox color code, " + colorCode + ", returning null.");
                return null;
        }
    }

    /**
     * Helper method to add cue list entries from a parsed extended ANLZ nxs2 comment cue tag
     *
     * @param entries the list of entries being accumulated
     * @param tag the tag whose entries are to be added
     */
    private void addEntriesFromTag(List<Entry> entries, RekordboxAnlz.CueExtendedTag tag) {
        for (RekordboxAnlz.CueExtendedEntry cueEntry : tag.cues()) {
            final Color embeddedColor = findEmbeddedColor(cueEntry);
            final Color expectedColor = expectedEmbeddedColor(cueEntry.colorCode());
            final Color rekordboxColor = findRekordboxColor(cueEntry.colorCode());
            if ((embeddedColor == null && expectedColor != null) ||
                    (embeddedColor != null && !embeddedColor.equals(expectedColor))) {
                logger.warn("Was expecting embedded color " + expectedEmbeddedColor(cueEntry.colorCode()) +
                        " for rekordbox color code " + cueEntry.colorCode() + ", but found color " + embeddedColor);
            }
            if (cueEntry.type() == RekordboxAnlz.CueEntryType.LOOP) {
                entries.add(new Entry((int)cueEntry.hotCue(), Util.timeToHalfFrame(cueEntry.time()),
                        Util.timeToHalfFrame(cueEntry.loopTime()), cueEntry.comment(), embeddedColor, rekordboxColor));
            } else {
                entries.add(new Entry((int)cueEntry.hotCue(), Util.timeToHalfFrame(cueEntry.time()), cueEntry.comment(), embeddedColor, rekordboxColor));
            }
        }
    }

    /**
     * Constructor for when reading from a rekordbox track analysis file. Finds the cues sections and
     * translates them into the objects Beat Link uses to represent them.
     *
     * @param anlzFile the recordbox analysis file corresponding to that track
     */
    public CueList(RekordboxAnlz anlzFile) {
        rawMessage = null;  // We did not create this from a dbserver response.
        List<ByteBuffer> tagBuffers = new ArrayList<ByteBuffer>(2);
        List<ByteBuffer> extendedTagBuffers = new ArrayList<ByteBuffer>(2);
        List<Entry> mutableEntries = new ArrayList<Entry>();

        // First see if there are any nxs2-style cue comment tags available.
        for (RekordboxAnlz.TaggedSection section : anlzFile.sections()) {
            if (section.body() instanceof RekordboxAnlz.CueExtendedTag) {
                RekordboxAnlz.CueExtendedTag tag = (RekordboxAnlz.CueExtendedTag) section.body();
                extendedTagBuffers.add(ByteBuffer.wrap(section._raw_body()).asReadOnlyBuffer());
                addEntriesFromTag(mutableEntries, tag);
            }
        }

        // Then, collect any old style cue tags, but ignore their entries if we found nxs2-style ones.
        for (RekordboxAnlz.TaggedSection section : anlzFile.sections()) {
            if (section.body() instanceof RekordboxAnlz.CueTag) {
                RekordboxAnlz.CueTag tag = (RekordboxAnlz.CueTag) section.body();
                tagBuffers.add(ByteBuffer.wrap(section._raw_body()).asReadOnlyBuffer());
                if (extendedTagBuffers.isEmpty()) {
                    addEntriesFromTag(mutableEntries, tag);
                }
            }
        }
        entries = sortEntries(mutableEntries);
        rawTags = Collections.unmodifiableList(tagBuffers);
        rawExtendedTags = Collections.unmodifiableList(extendedTagBuffers);
    }

    /**
     * Constructor for when recreating from cache files containing the raw tag bytes if there were no nxs2-style
     * extended cue tags. Included for backwards compatibility.

     * @param rawTags the un-parsed ANLZ file tags holding the cue list entries
     */
    public CueList(List<ByteBuffer> rawTags) {
        this (rawTags, Collections.<ByteBuffer>emptyList());
    }

    /**
     * Constructor for when recreating from cache files containing the raw tag bytes and raw nxs2-style
     * cue comment tag bytes.

     * @param rawTags the un-parsed ANLZ file tags holding the cue list entries
     * @param rawExtendedTags the un-parsed extended ANLZ file tags holding the nxs2-style commented cue list entries
     */
    public CueList(List<ByteBuffer> rawTags, List<ByteBuffer> rawExtendedTags) {
        rawMessage = null;
        this.rawTags = Collections.unmodifiableList(rawTags);
        this.rawExtendedTags = Collections.unmodifiableList(rawExtendedTags);
        List<Entry> mutableEntries = new ArrayList<Entry>();
        if (rawExtendedTags.isEmpty()) {
            for (ByteBuffer buffer : rawTags) {
                RekordboxAnlz.CueTag tag = new RekordboxAnlz.CueTag(new ByteBufferKaitaiStream(buffer));
                addEntriesFromTag(mutableEntries, tag);
            }
        } else {
            for (ByteBuffer buffer : rawExtendedTags) {
                RekordboxAnlz.CueExtendedTag tag = new RekordboxAnlz.CueExtendedTag(new ByteBufferKaitaiStream(buffer));
                addEntriesFromTag(mutableEntries, tag);
            }
        }
        entries = sortEntries(mutableEntries);
    }

    /**
     * Constructor when reading from the network or a cache file.
     *
     * @param message the response that contains the cue list information
     */
    public CueList(Message message) {
        rawMessage = message;
        rawTags = null;
        rawExtendedTags = null;
        byte[] entryBytes = ((BinaryField) message.arguments.get(3)).getValueAsArray();
        final int entryCount = entryBytes.length / 36;
        ArrayList<Entry> mutableEntries = new ArrayList<Entry>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            final int offset = i * 36;
            final int cueFlag = entryBytes[offset + 1];
            final int hotCueNumber = entryBytes[offset + 2];
            if ((cueFlag != 0) || (hotCueNumber != 0)) {
                // This entry is not empty, so represent it.
                final long position = Util.bytesToNumberLittleEndian(entryBytes, offset + 12, 4);
                if (entryBytes[offset] != 0) {  // This is a loop
                    final long endPosition = Util.bytesToNumberLittleEndian(entryBytes, offset + 16, 4);
                    mutableEntries.add(new Entry(hotCueNumber, position, endPosition, "", null, null));
                } else {
                    mutableEntries.add(new Entry(hotCueNumber, position, "", null, null));
                }
            }
        }
        entries = sortEntries(mutableEntries);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CueList cueList = (CueList) o;

        return entries != null ? entries.equals(cueList.entries) : cueList.entries == null;
    }

    @Override
    public int hashCode() {
        return entries != null ? entries.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "Cue List[entries: " + entries + "]";
    }
}
