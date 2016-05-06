package org.deepsymmetry.beatlink;

import java.net.DatagramSocket;

/**
 * Provides the ability to create a virtual CDJ device that can lurk on a DJ Link network and receive packets sent to
 * players, monitoring the detailed state of the actual players.
 *
 * @author James Elliott
 */
public class VirtualCdj {

    /**
     * The port to which other devices will send status update messages.
     */
    public static final int UPDATE_PORT = 50002;

    /**
     * The socket used to listen for keep-alive packets while we are active.
     */
    private static DatagramSocket socket;

    /**
     * Check whether we are presently listening for device announcements.
     *
     * @return true if our socket is open and monitoring for DJ Link devices announcements on the network.
     */
    public static synchronized boolean isActive() {
        return socket != null;
    }
}
