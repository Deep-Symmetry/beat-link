package org.deepsymmetry.beatlink;

import java.net.InetAddress;

/**
 * Provides utility functions.
 *
 * @author James Elliott
 */
public class Util {

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
     * Converts the bytes that make up an internet address into the corresponding integer value to make
     * it easier to perform bit-masking operations on them.
     *
     * @param address an address whose integer equivalent is desired
     *
     * @return the integer corresponding to that address
     */
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
     * Prevent instantiation.
     */
    private Util() {
        // Nothing to do.
    }
}
