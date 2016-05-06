package org.deepsymmetry.beatlink;

/**
 * Provides utility functions.
 *
 * @author James Elliott
 */
public class Util {
    /**
     * Converts a signed byte to its unsigned int equivalent in the range 0-255.
     */
    public static int unsign(byte b) {
        return b & 0xff;
    }
}
