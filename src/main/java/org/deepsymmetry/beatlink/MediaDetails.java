package org.deepsymmetry.beatlink;

import org.deepsymmetry.beatlink.data.SlotReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents information about the media mounted in a player's slot; returned in response to a media query packet.
 */
@SuppressWarnings("WeakerAccess")
public class MediaDetails {

    private static final Logger logger = LoggerFactory.getLogger(MediaDetails.class);

    /**
     * The player and slot in which this media resides.
     */
    public final SlotReference slotReference;

    /**
     * The type of tracks stored in this media.
     */
    public final CdjStatus.TrackType mediaType;

    /**
     * The name assigned to the media within rekordbox.
     */
    public final String name;

    /**
     * The creation date of the media.
     */
    public final String creationDate;

    /**
     * The number of rekordbox tracks in the media database. Will be zero if {@link #mediaType} is not rekordbox.
     */
    public final int trackCount;

    /**
     * The number of rekordbox playlists in the media database. Will be zero if {@link #mediaType} is not rekordbox.
     */
    public final int playlistCount;

    /**
     * The size of the storage space, in bytes.
     */
    public final long totalSize;

    /**
     * The amount of storage remaining, in bytes.
     */
    public final long freeSpace;

    /**
     * The bytes from which we were constructed, to support saving for later recreation.
     */
    private final ByteBuffer rawBytes;

    /**
     * Get the raw bytes of the media details as returned by the player.
     *
     * @return the bytes that make up the media details
     */
    public ByteBuffer getRawBytes() {
        rawBytes.rewind();
        return rawBytes.slice();
    }

    /**
     * Contains the sizes we expect Media response packets to have so we can log a warning if we get an unusual
     * one. We will then add the new size to the list so it only gets logged once per run.
     */
    private static final Set<Integer> expectedMediaPacketSizes = new HashSet<Integer>(Collections.singletonList(0xc0));

    /**
     * The smallest packet size from which we can be constructed. Anything less than this and we are missing
     * crucial information.
     */
    public static final int MINIMUM_PACKET_SIZE = 0xc0;

    /**
     * Constructor sets all the immutable interpreted fields based on the packet content.
     *
     * @param packet the media response packet that was received
     */
    MediaDetails(DatagramPacket packet) {
        this(packet.getData(), packet.getLength());
    }

    /**
     * Constructor sets all the immutable interpreted fields based on the packet content.
     *
     * @param packet the media response packet that was received or cached
     * @param packetLength the number of bytes within the packet which were actually received
     */
    public MediaDetails(byte[] packet, int packetLength) {
        byte[] packetCopy = new byte[packetLength];  // Make a defensive copy
        System.arraycopy(packet, 0, packetCopy, 0, packetLength);
        rawBytes = ByteBuffer.wrap(packetCopy).asReadOnlyBuffer();  // Save for safe sharing.

        if (packetCopy.length < MINIMUM_PACKET_SIZE) {
            throw new IllegalArgumentException("Unable to create a MediaDetails object, packet too short: we need " + MINIMUM_PACKET_SIZE +
                    " bytes and were given only " + packetCopy.length);
        }

        final int payloadLength = (int)Util.bytesToNumber(packetCopy, 0x22, 2);
        if (packetCopy.length != payloadLength + 0x24) {
            logger.warn("Received Media response packet with reported payload length of " + payloadLength + " and actual payload length of " +
                    (packetCopy.length - 0x24));
        }

        if (!expectedMediaPacketSizes.contains(packetCopy.length)) {
            logger.warn("Processing Media response packets with unexpected lengths " + packetCopy.length + ".");
            expectedMediaPacketSizes.add(packetCopy.length);
        }

        final int hostPlayer = packetCopy[0x27];
        final CdjStatus.TrackSourceSlot hostSLot = CdjStatus.TRACK_SOURCE_SLOT_MAP.get(packetCopy[0x2b]);
        if (hostSLot == null) {
            throw new IllegalArgumentException("Unrecognized slot for media response:" + packetCopy[0x2b]);
        }
        slotReference = SlotReference.getSlotReference(hostPlayer, hostSLot);

        final CdjStatus.TrackType type = CdjStatus.TRACK_TYPE_MAP.get(packetCopy[0xaa]);
        if (type == null) {
            throw new IllegalArgumentException("Unrecognized media type for media response:" + packetCopy[0xaa]);
        }
        mediaType = type;

        try {
            name = new String(packetCopy, 0x2c, 0x40, "UTF-16BE").trim();
            creationDate = new String(packetCopy, 0x6c, 0x18, "UTF-16BE").trim();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("Java no longer supports UTF-16BE encoding?!", e);
        }

        trackCount = (int)Util.bytesToNumber(packetCopy, 0xa6, 2);
        playlistCount = (int)Util.bytesToNumber(packetCopy, 0xae, 2);
        totalSize = Util.bytesToNumber(packetCopy, 0xb0, 8);
        freeSpace = Util.bytesToNumber(packetCopy, 0xb8, 8);
    }

    @Override
    public String toString() {
        return "MediaDetails[slotReference:" + slotReference + ", name:" + name + ", creationDate:" + creationDate +
                ", mediaType:" + mediaType + ", trackCount:" + trackCount + ", playlistCount:" + playlistCount +
                ", totalSize:" + totalSize + ", freeSpace:" + freeSpace + "]";
    }

    /**
     * Build a string containing the things that we do not expect to change about the media, to help us recognize
     * it when it gets mounted in the future.
     *
     * @return a colon-delimited string made up of the creation date, media type, total size, and name
     */
    public String hashKey() {
        return creationDate + ":" + mediaType + ":" + totalSize + ":" + name;
    }

    /**
     * Check whether the media seems to have changed since a saved version of it was used.
     *
     * @param originalMedia the media details when information about it was saved
     * @return true if there have been detectable changes to the media since it was saved
     * @throws IllegalArgumentException if the {@link #hashKey()} values of the media detail objects differ
     */
    public boolean hasChanged(MediaDetails originalMedia) {
        if (!hashKey().equals(originalMedia.hashKey())) {
            throw new IllegalArgumentException("Can't compare media details with different hashKey values");
        }
        return freeSpace == originalMedia.freeSpace && playlistCount == originalMedia.playlistCount &&
                trackCount == originalMedia.trackCount;
    }
}
