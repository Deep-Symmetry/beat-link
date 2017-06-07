package org.deepsymmetry.beatlink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides utility functions.
 *
 * @author James Elliott
 */
public class Util {

    private static final Logger logger = LoggerFactory.getLogger(Util.class);

    /**
     * The bytes that should always be present at the start of a DJ Link packet.
     */
    private static final byte[] EXPECTED_HEADER = { 0x51, 0x73, 0x70, 0x74, 0x31, 0x57, 0x6d, 0x4a, 0x4f, 0x4c };

    /**
     * Check to see whether a packet starts with the standard header bytes, followed by a byte identifying it as the
     * kind of packet that is expected.
     *
     * @param packet a packet that has just been received
     * @param kind the expected value of the eleventh byte, which seems to identify the packet type
     * @param name the name of the kind of packet expected, for use in logging warnings if a mismatch is found
     *
     * @return {@code true} if the packet has the right header
     */
    public static boolean validateHeader(DatagramPacket packet, int kind, String name) {
        boolean valid = true;
        byte[] data = packet.getData();

        for (int i = 0; i < EXPECTED_HEADER.length; i++) {
            if (EXPECTED_HEADER[i] != data[i]) {
                logger.warn("Header mismatch at byte " + i + " of " + name + " packet: expecting " +
                EXPECTED_HEADER[i] + ", found " + data[i]);
                valid = false;
            }
        }

        if (data[10] != (byte)kind) {
            logger.warn("Expecting " + name + " packet to have kind value " + kind + ", but found " + data[10]);
            valid = false;
        }

        return valid;
    }

    /**
     * Converts a signed byte to its unsigned int equivalent in the range 0-255.
     *
     * @param b a byte value to be considered an unsigned integer
     *
     * @return the unsigned version of the byte
     */
    public static int unsign(byte b) {
        return b & 0xff;
    }

    /**
     * Reconstructs a number that is represented by more than one byte in a network packet in big-endian order.
     *
     * @param buffer the byte array containing the packet data
     * @param start the index of the first byte containing a numeric value
     * @param length the number of bytes making up the value
     * @return the reconstructed number
     */
    public static long bytesToNumber(byte[] buffer, int start, int length) {
        long result = 0;
        for (int index = start; index < start + length; index++) {
            result = (result << 8) + unsign(buffer[index]);
        }
        return result;
    }

    /**
     * Reconstructs a number that is represented by more than one byte in a network packet in little-endian order, for
     * the very few protocol values that are sent in this quirky way.
     *
     * @param buffer the byte array containing the packet data
     * @param start the index of the first byte containing a numeric value
     * @param length the number of bytes making up the value
     * @return the reconstructed number
     */
    @SuppressWarnings("SameParameterValue")
    public static long bytesToNumberLittleEndian(byte[] buffer, int start, int length) {
        long result = 0;
        for (int index = start + length - 1; index >= start; index--) {
            result = (result << 8) + unsign(buffer[index]);
        }
        return result;
    }

    /**
     * Converts the bytes that make up an internet address into the corresponding integer value to make
     * it easier to perform bit-masking operations on them.
     *
     * @param address an address whose integer equivalent is desired
     *
     * @return the integer corresponding to that address
     */
    @SuppressWarnings("WeakerAccess")
    public static long addressToLong(InetAddress address) {
        long result = 0;
        for (byte element : address.getAddress()) {
            result = (result << 8) + unsign(element);
        }
        return result;
    }

    /**
     * Checks whether two internet addresses are on the same subnet.
     *
     * @param prefixLength the number of bits within an address that identify the network
     * @param address1 the first address to be compared
     * @param address2 the second address to be compared
     *
     * @return true if both addresses share the same network bits
     */
    public static boolean sameNetwork(int prefixLength, InetAddress address1, InetAddress address2) {
        long prefixMask = 0xffffffffL & (-1 << (32 - prefixLength));
        return (addressToLong(address1) & prefixMask) == (addressToLong(address2) & prefixMask);
    }

    /**
     * Convert a pitch value reported by a device to the corresponding percentage (-100% to +100%, where normal,
     * unadjusted pitch has the value 0%).
     *
     * @param pitch the reported device pitch
     * @return the pitch as a percentage
     */
    public static double pitchToPercentage(long pitch) {
        return (pitch - 1048567) / 10485.76;
    }

    /**
     * Convert a pitch value reported by a device to the corresponding multiplier (0.0 to 2.0, where normal, unadjusted
     * pitch has the multiplier 1.0).
     *
     * @param pitch the reported device pitch
     * @return the implied pitch multiplier
     */
    public static double pitchToMultiplier(long pitch) {
        return pitch / 1048576.0;
    }

    /**
     * Writes the entire remaining contents of the buffer to the channel. May complete in one operation, but the
     * documentation is vague, so this keeps going until we are sure.
     *
     * @param buffer the data to be written
     * @param channel the channel to which we want to write data
     *
     * @throws IOException if there is a problem writing to the channel
     */
    public static void writeFully(ByteBuffer buffer, WritableByteChannel channel) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * Prevent instantiation.
     */
    private Util() {
        // Nothing to do.
    }
}
