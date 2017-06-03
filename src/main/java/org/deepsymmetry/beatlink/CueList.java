package org.deepsymmetry.beatlink;

import org.deepsymmetry.beatlink.dbserver.BinaryField;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.beatlink.dbserver.NumberField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Provides information about each memory point, hot cue, and loop stored for a track.
 *
 * @author James Elliott
 */
public class CueList {
    /**
     * The message holding the cue list information as it was read over the network. This can be used to analyze fields
     * that have not yet been reliably understood, and is also used for storing the cue list in a cache file.
     */
    @SuppressWarnings("WeakerAccess")
    public final Message rawMessage;

    /**
     * Return the number of entries in the cue list that represent hot cues.
     *
     * @return the number of cue list entries that are hot cues
     */
    public int getHotCueCount() {
        return (int) ((NumberField) rawMessage.arguments.get(5)).getValue();
    }

    /**
     * Return the number of entries in the cue list that represent ordinary memory points, rather than hot cues.
     * The memory points can also be loops.
     *
     * @return the number of cue list entries other than hot cues
     */
    public int getMemoryPointCount() {
        return (int) ((NumberField) rawMessage.arguments.get(6)).getValue();
    }

    /**
     * Calculates the time represented by a half-frame position.
     *
     * @param position the number of half-frame units, each of which takes 1/150 of a second
     *
     * @return the corresponding millisecond time
     */
    @SuppressWarnings("WeakerAccess")
    public long timeFromHalfFramePosition(long position) {
        return (position * 100) / 15;
    }

    /**
     * Breaks out information about each entry in the cue list.
     */
    public class Entry {
        /**
         * If this has a non-zero value, this entry is a hot cue with that identifier.
         */
        final int hotCueNumber;

        /**
         * Indicates whether this entry represents a loop, as opposed to a simple cue point.
         */
        final boolean isLoop;

        /**
         * Indicates the location of the cue in half-frame units, which are 1/150 of a second.
         */
        final long cuePosition;

        /**
         * Indicates the location of the cue in milliseconds.
         */
        final long cueTime;

        /**
         * If the entry represents a loop, indicates the loop point in half-frame units, which are 1/150 of a second.
         */
        final long loopPosition;

        /**
         * If the entry represents a loop, indicates the loop point in milliseconds.
         */
        final long loopTime;

        /**
         * Constructor for non-loop entries.
         *
         * @param number if non-zero, this is a hot cue, with the specified identifier
         * @param position the position of this cue/memory point in half-frame units, which are 1/150 of a second
         */
        public Entry(int number, long position) {
            hotCueNumber = number;
            cuePosition = position;
            cueTime = timeFromHalfFramePosition(position);
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
            cueTime = timeFromHalfFramePosition(startPosition);
            isLoop = true;
            loopPosition = endPosition;
            loopTime = timeFromHalfFramePosition(endPosition);
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
                sb.append("Hot Cue ").append((char)hotCueNumber + '@').append('[');
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
    @SuppressWarnings("WeakerAccess")
    final List<Entry> entries;

    /**
     * Constructor when reading from the network or a cache file.
     *
     * @param message the response that contains the cue list information
     */
    public CueList(Message message) {
        rawMessage = message;
        byte[] entryBytes = ((BinaryField) message.arguments.get(3)).getValueAsArray();
        final int entryCount = entryBytes.length / 36;
        ArrayList<Entry> scratch = new ArrayList<Entry>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            final int offset = i * 36;
            final int cueFlag = entryBytes[offset + 1];
            final int hotCueNumber = entryBytes[offset + 2];
            if ((cueFlag != 0) || (hotCueNumber != 0)) {
                // This entry is not empty, so represent it.
                final long position = Util.bytesToNumberLittleEndian(entryBytes, 12, 4);
                if (entryBytes[offset] != 0) {  // This is a loop
                    final long endPosition = Util.bytesToNumberLittleEndian(entryBytes, 16, 4);
                    scratch.add(new Entry(hotCueNumber, position, endPosition));
                } else {
                    scratch.add(new Entry(hotCueNumber, position));
                }
            }
        }
        Collections.sort(scratch, new Comparator<Entry>() {
            @Override
            public int compare(Entry entry1, Entry entry2) {
                int result = (int) (entry2.cuePosition - entry1.cuePosition);
                if (result == 0) {
                    int h1 = (entry1.hotCueNumber != 0) ? 1 : 0;
                    int h2 = (entry2.hotCueNumber != 0) ? 1 : 0;
                    result = h2 - h1;
                }
                return result;
            }
        });
        entries = Collections.unmodifiableList(scratch);
    }
}
