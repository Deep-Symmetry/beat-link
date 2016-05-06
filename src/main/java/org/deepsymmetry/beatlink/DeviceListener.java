package org.deepsymmetry.beatlink;

import java.net.DatagramSocket;

/**
 * Watches for devices to report their presence by broadcasting keep-alive packets on port 50000,
 * and keeps a list of the devices that have been seen, as well as the network interface and
 * interface address on which they were seen.
 */
public class DeviceListener {

    /**
     * The port on which devices send keep-alive messages to report their presence on the network.
     */
    public static final int KEEPALIVE_PORT = 50000;

    /**
     * The socket used to listen for keep-alive packets while we are active.
     */
    private static DatagramSocket socket;

    /**
     * Start listening for
     */


}