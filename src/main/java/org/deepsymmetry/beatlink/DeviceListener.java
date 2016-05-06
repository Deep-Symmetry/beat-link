package org.deepsymmetry.beatlink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches for devices to report their presence by broadcasting keep-alive packets on port 50000,
 * and keeps a list of the devices that have been seen, as well as the network interface and
 * interface address on which they were seen.
 */
public class DeviceListener {

    private static final Logger logger = Logger.getLogger(DeviceListener.class.getName());

    /**
     * The port on which devices send keep-alive messages to report their presence on the network.
     */
    public static final int ANNOUNCEMENT_PORT = 50000;

    /**
     * The number of milliseconds after which we will consider a device to have disappeared if
     * we have not received an announcement from it.
     */
    public static final int MAXIMUM_AGE = 5000;

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

    /**
     * Keep track of the announcements we have seen.
     */
    private static final Map<InetAddress, DeviceAnnouncement> devices = new HashMap<InetAddress, DeviceAnnouncement>();

    /**
     * Remove any device announcements that are so old that the device seems to have gone away.
     */
    private static void expireDevices() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<InetAddress, DeviceAnnouncement>> it = devices.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<InetAddress, DeviceAnnouncement> entry = it.next();
            if (now - entry.getValue().getTimestamp() > MAXIMUM_AGE) {
                it.remove();
            }
        }
    }

    /**
     * Start listening for device announcements and keeping track of the DJ Link devices visible on the network.
     * If already listening, has no effect.
     *
     * @throws SocketException if the socket to listen on port 50000 cannot be created.
     */
    public static synchronized void start() throws SocketException {

        if (!isActive()) {
            socket = new DatagramSocket(ANNOUNCEMENT_PORT);

            final byte[] buffer = new byte[512];
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            Thread receiver = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (isActive()) {
                        try {
                            socket.receive(packet);
                        } catch (IOException e) {
                            // Don't log a warning if the exception was due to the socket closing at shutdown.
                            if (isActive()) {
                                // We did not expect to have a problem; log a warning and shut down.
                                logger.log(Level.WARNING, "Problem reading from DeviceAnnouncement socket, stopping", e);
                                stop();
                            }
                        }
                        try {
                            if (packet.getLength() == 54) {  // Looks like the kind of packet we are watching for
                                DeviceAnnouncement announcement = new DeviceAnnouncement(packet);
                                devices.put(announcement.getAddress(), announcement);
                            }
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Problem parsing DeviceAnnouncement packet", e);
                        }
                    }
                }
            });
            receiver.setDaemon(true);
            receiver.start();
        }
    }

    /**
     * Stop listening for device announcements. Also discards any announcements which had been received.
     */
    public static synchronized void stop() {
        if (isActive()) {
            socket.close();
            socket = null;
            devices.clear();
        }
    }

    /**
     * Get the list of devices which currently can be seen on the network.
     *
     * @return the DJ Link devices which have been heard from recently enough to be considered active.
     */
    public static List<DeviceAnnouncement> currentDevices() {
        expireDevices();
        return Collections.unmodifiableList(new ArrayList<DeviceAnnouncement>(devices.values()));
    }
}
