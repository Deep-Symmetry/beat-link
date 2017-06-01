package org.deepsymmetry.beatlink;

import org.deepsymmetry.beatlink.dbserver.BinaryField;
import org.deepsymmetry.beatlink.dbserver.Message;

import java.nio.ByteBuffer;

/**
 * Provides information about each beat in a track: the number of milliseconds after the start of the track that
 * the beat occurs, and where the beat falls within a measure.
 *
 * @author James Elliott
 */
public class BeatGrid {

    /**
     * The message field holding the raw bytes of the beat grid as it was read over the network.
     */
    private final ByteBuffer rawData;

    /**
     * Get the raw bytes of the beat grid as it was read over the network. This can be used to analyze fields
     * that have not yet been reliably understood, and is also used for storing the beat grid in a cache file.
     *
     * @return the bytes that make up the beat grid
     */
    public ByteBuffer getRawData() {
        rawData.rewind();
        return rawData.slice();
    }

    /**
     * The number of beats in the track.
     */
    public final int beatCount;

    /**
     * Holds the actual bytes of the grid, for calculating and reporting information about the beats in it.
     */
    private final byte[] gridBytes;

    /**
     * Constructor for when reading from the network.
     *
     * @param message the response that contained the beat grid data
     */
    public BeatGrid(Message message) {
        this(((BinaryField)message.arguments.get(3)).getValue());
    }

    /**
     * Constructor for reading from a cache file.
     *
     * @param buffer the raw bytes representing the beat grid
     */
    public BeatGrid(ByteBuffer buffer) {
        rawData = buffer;
        gridBytes = new byte[rawData.remaining()];
        rawData.get(gridBytes);
        beatCount = (gridBytes.length - 20) / 16;
    }

    /**
     * Calculate where within the beat grid array the information for the specified beat can be found.
     *
     * @param beatNumber the beat desired
     *
     * @return the offset of the start of that beat information within the bytes of the beat grid
     */
    private int beatOffset(int beatNumber) {
        if (beatNumber < 1 || beatNumber > beatCount) {
            throw new IllegalArgumentException("number must be between 1 and " + beatCount);
        }
        return 20 + (beatNumber - 1) * 16;
    }

    /**
     * Returns the time at which the specified beat falls within the track.
     *
     * @param beatNumber the beat number desired, must fall within the range 1..beatCount
     *
     * @return the number of milliseconds into the track at which the specified beat occurs
     *
     * @throws IllegalArgumentException if {@code number} is less than 1 or greater than {@code beatCount}
     */
    public long getTimeWithinTrack(int beatNumber) {
        final int offset = beatOffset(beatNumber);
        long result = 0;
        // For some reason, unlike every other number in the protocol, these are little-endian.
        for (int i = offset + 7; i >= offset + 4; i--) {
            result = (result << 8) + Util.unsign(gridBytes[i]);
        }
        return result;
    }

    /**
     * Returns the musical count of the specified beat, a number from 1 to 4, where 1 is the down beat, or the start
     * of a new measure.
     *
     * @param beatNumber the number of the beat of interest, must fall within the range 1..beatcount
     *
     * @return where that beat falls in a bar of music
     *
     * @throws IllegalArgumentException if {@code number} is less than 1 or greater than {@code beatCount}
     */
    public int getPositionWithinBar(int beatNumber) {
        return gridBytes[beatOffset(beatNumber)];
    }
}
