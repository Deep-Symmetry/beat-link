package org.deepsymmetry.beatlink.data;

import io.kaitai.struct.ByteBufferKaitaiStream;
import org.deepsymmetry.beatlink.Util;
import org.deepsymmetry.beatlink.dbserver.BinaryField;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.beatlink.dbserver.NumberField;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Provides information about each memory point, hot cue, and loop stored for a track.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class CueList {

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
         * Constructor for non-loop entries.
         *
         * @param number if non-zero, this is a hot cue, with the specified identifier
         * @param position the position of this cue/memory point in half-frame units, which are 1/150 of a second
         */
        public Entry(int number, long position) {
            hotCueNumber = number;
            cuePosition = position;
            cueTime = Util.halfFrameToTime(position);
            isLoop = false;
            loopPosition = 0;
            loopTime = 0;
        }

        /**
         * Constructor for loop entries.
         *
         * @param number if non-zero, this is a hot cue, with the specified identifier
         * @param startPosition the position of the start of this loop in half-frame units, which are 1/150 of a second
         * @param endPosition the position of the end of this loop in half-frame units
         */
        public Entry(int number, long startPosition, long endPosition) {
            hotCueNumber = number;
            cuePosition = startPosition;
            cueTime = Util.halfFrameToTime(startPosition);
            isLoop = true;
            loopPosition = endPosition;
            loopTime = Util.halfFrameToTime(endPosition);
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
     * Sorts the entries into the order we want to present them in, which is by position, with hot cues coming after
     * ordinary memory points if both exist at the same position, which often happens.
     *
     * @param loadedEntries the unsorted entries we have loaded from a dbserver message, metadata cache, or rekordbox
     *                database export
     * @return an immutable list of the collections in the proper order
     */
    private List<Entry> sortEntries(List<Entry> loadedEntries) {
        Collections.sort(loadedEntries, new Comparator<Entry>() {
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
        });
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
                        Util.timeToHalfFrame(cueEntry.loopTime())));
            } else {
                entries.add(new Entry((int)cueEntry.hotCue(), Util.timeToHalfFrame(cueEntry.time())));
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
        List<Entry> mutableEntries = new ArrayList<Entry>();
        for (RekordboxAnlz.TaggedSection section : anlzFile.sections()) {
            if (section.body() instanceof RekordboxAnlz.CueTag) {
                RekordboxAnlz.CueTag tag = (RekordboxAnlz.CueTag) section.body();
                tagBuffers.add(ByteBuffer.wrap(section._raw_body()).asReadOnlyBuffer());
                addEntriesFromTag(mutableEntries, tag);
            }
        }
        entries = sortEntries(mutableEntries);
        rawTags = Collections.unmodifiableList(tagBuffers);
    }

    /**
     * Constructor for when recreating from cache files containing the raw tag bytes.

     * @param rawTags the un-parsed ANLZ file tags holding the cue list entries
     */
    public CueList(List<ByteBuffer> rawTags) {
        rawMessage = null;
        this.rawTags = Collections.unmodifiableList(rawTags);
        List<Entry> mutableEntries = new ArrayList<Entry>();
        for (ByteBuffer buffer : rawTags) {
            RekordboxAnlz.CueTag tag = new RekordboxAnlz.CueTag(new ByteBufferKaitaiStream(buffer));
            addEntriesFromTag(mutableEntries, tag);
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
                    mutableEntries.add(new Entry(hotCueNumber, position, endPosition));
                } else {
                    mutableEntries.add(new Entry(hotCueNumber, position));
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
