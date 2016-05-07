package org.deepsymmetry.beatlink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides the ability to create a virtual CDJ device that can lurk on a DJ Link network and receive packets sent to
 * players, monitoring the detailed state of the other devices. This detailed information is helpful for augmenting
 * what {@link BeatFinder} reports, allowing you to keep track of which player is the tempo master, how many beats of
 * a track have been played, how close a player is getting to its next cue point, and more.
 *
 * @author James Elliott
 */
public class VirtualCdj {

    private static final Logger logger = Logger.getLogger(VirtualCdj.class.getName());

    /**
     * The port to which other devices will send status update messages.
     */
    public static final int UPDATE_PORT = 50002;

    /**
     * The socket used to receive device status packets while we are active.
     */
    private static DatagramSocket socket;

    /**
     * Check whether we are presently listening for device announcements.
     *
     * @return true if our socket is open and monitoring for status packets sent to it.
     */
    public static synchronized boolean isActive() {
        return socket != null;
    }

    /**
     * Given an update packet sent to us, create the appropriate object to describe it.
     *
     * @param packet the packet received on our update port
     * @return the corresponding {@link DeviceUpdate} subclass, or {@code nil} if the packet was not recognizable
     */
    private static DeviceUpdate buildUpdate(DatagramPacket packet) {
        int length = packet.getLength();
        int kind = packet.getData()[10];
        if (length == 56 && kind == 0x29 && Util.validateHeader(packet, 0x29, "Mixer Status")) {
            return new MixerStatus(packet);
        } else if (length == 212 && kind == 0x0a && Util.validateHeader(packet, 0x0a, "CDJ Status")) {
            return null;  // TODO new CdjStatus(packet);
        }
        logger.log(Level.WARNING, "Unrecognized device update packet with length " + length + " and kind " + kind);
        return null;
    }

    /**
     * Start announcing ourselves and listening for status packets. If already active, has no effect.
     *
     * @throws SocketException if the socket to listen on port 50002 cannot be created
     */
    public static synchronized void start() throws SocketException {
        if (!isActive()) {
            socket = new DatagramSocket(UPDATE_PORT);

            final byte[] buffer = new byte[512];
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            Thread receiver = new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean received;
                    while (isActive()) {
                        try {
                            socket.receive(packet);
                            received = true;
                        } catch (IOException e) {
                            // Don't log a warning if the exception was due to the socket closing at shutdown.
                            if (isActive()) {
                                // We did not expect to have a problem; log a warning and shut down.
                                logger.log(Level.WARNING, "Problem reading from DeviceStatus socket, stopping", e);
                                stop();
                            }
                            received = false;
                        }
                        try {
                            if (received) {
                                DeviceUpdate update = buildUpdate(packet);
                                if (update != null) {
                                    // TODO process it
                                }
                            }
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Problem processing device update packet", e);
                        }
                    }
                }
            });
            receiver.setDaemon(true);
            receiver.start();
        }

        // TODO create self-announcement thread
    }

    /**
     * Stop announcing ourselves and listening for status updates.
     */
    public static synchronized void stop() {
        if (isActive()) {
            socket.close();
            socket = null;
        }
    }

}
