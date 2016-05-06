package org.deepsymmetry.beatlink;

/**
 * Provides utility functions.
 *
 * @author James Elliott
 */
public class Util {
    /**
     * Converts a signed byte to its unsigned int equivalent in the range 0-255.
     *
     * @param b a byte value to be considered an unsigned integer.
     *
     * @return the unsigned version of the byte.
     */
    public static int unsign(byte b) {
        return b & 0xff;
    }

    /**
     * Prevent instantiation.
     */
    private Util() {
        // Nothing to do.
    }
}
