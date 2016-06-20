package org.deepsymmetry.beatlink;

import java.io.UnsupportedEncodingException;

/**
 * Represents rekordbox metadata (title, artist, etc.) about tracks loaded into players on a DJ Link network.
 *
 * @author James Elliott
 */
public class TrackMetadata {

    /**
     * When this metadata was received.
     */
    private final long timestamp;

    /**
     * The player/device number which loaded the track described by the metadata.
     */
    private final int deviceNumber;

    /**
     * The raw packet data containing the metadata.
     */
    private final byte[] packetBytes;

    /**
     * Get the raw packet data, to analyze fields that have not yet been reliably found.
     *
     * @return the raw bytes we received from the CDJ when asked for metadata
     */
    public byte[] getRawMetadata() {
        byte[] result = new byte[packetBytes.length];
        System.arraycopy(packetBytes, 0, result, 0, packetBytes.length);
        return result;
    }

    /**
     * The title of the track.
     */
    private final String title;

    /**
     * The artist that created the track.
     */
    private final String artist;

    /**
     * Constructor sets all the immutable interpreted fields based on the received content.
     *
     * @param device the device number from which the metadata was received
     * @param buffer the metadata that was received
     *
     * @throws UnsupportedEncodingException if there is a problem interpreting the metadata
     */
    public TrackMetadata(int device, byte[] buffer) throws UnsupportedEncodingException {
        timestamp = System.currentTimeMillis();
        deviceNumber = device;
        packetBytes = buffer;

        int titleLength = (int)Util.bytesToNumber(packetBytes, 90, 4);
        title = new String(packetBytes, 94, (2 * (titleLength - 1)), "UTF-16");
        int artistLength = (int)Util.bytesToNumber(packetBytes, 184 + (2 * titleLength), 4);
        artist = new String(packetBytes, 188 + (2 * titleLength), (2 * (artistLength - 1)), "UTF-16");
    }

    @Override
    public String toString() {
        return "Track Metadata: Title: " + title + ", Artist: " + artist;
    }
}
