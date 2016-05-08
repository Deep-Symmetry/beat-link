package org.deepsymmetry.beatlink;

import java.awt.EventQueue;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches for devices to report their presence by broadcasting announcement packets on port 50000,
 * and keeps a list of the devices that have been seen, and the network address on which they were seen.
 *
 * @author James Elliott
 */
public class DeviceFinder {

    private static final Logger logger = Logger.getLogger(DeviceFinder.class.getName());

    /**
     * The port to which devices broadcast announcement messages to report their presence on the network.
     */
    public static final int ANNOUNCEMENT_PORT = 50000;

    /**
     * The number of milliseconds after which we will consider a device to have disappeared if
     * we have not received an announcement from it.
     */
    public static final int MAXIMUM_AGE = 5000;

    /**
     * The socket used to listen for announcement packets while we are active.
     */
    private static DatagramSocket socket;

    /**
     * Check whether we are presently listening for device announcements.
     *
     * @return {@code true} if our socket is open and monitoring for DJ Link device announcements on the network
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
                deliverLostAnnouncement(entry.getValue());
                it.remove();
            }
        }
    }

    /**
     * Check whether a device is already known, or if it is newly found.
     *
     * @param announcement the message from the device to be considered
     *
     * @return true if we have already seen messages from this device
     */
    private static synchronized boolean isDeviceKnown(DeviceAnnouncement announcement) {
        return devices.containsKey(announcement.getAddress());
    }

    /**
     * Start listening for device announcements and keeping track of the DJ Link devices visible on the network.
     * If already listening, has no effect.
     *
     * @throws SocketException if the socket to listen on port 50000 cannot be created
     */
    public static synchronized void start() throws SocketException {

        if (!isActive()) {
            socket = new DatagramSocket(ANNOUNCEMENT_PORT);

            final byte[] buffer = new byte[512];
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            Thread receiver = new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean received;
                    while (isActive()) {
                        try {
                            if (currentDevices().isEmpty()) {
                                socket.setSoTimeout(0);  // We have no devices to check for timeout; block indefinitely
                            } else {
                                socket.setSoTimeout(1000);  // Check every second to see if a device has vanished
                            }
                            received = true;
                            socket.receive(packet);
                        } catch (SocketTimeoutException ste) {
                            received = false;
                        } catch (IOException e) {
                            // Don't log a warning if the exception was due to the socket closing at shutdown.
                            if (isActive()) {
                                // We did not expect to have a problem; log a warning and shut down.
                                logger.log(Level.WARNING, "Problem reading from DeviceAnnouncement socket, stopping", e);
                                stop();
                            }
                            received = false;
                        }
                        try {
                            if (received && (packet.getLength() == 54) && Util.validateHeader(packet, 6, "device announcement")) {
                                // Looks like the kind of packet we need
                                DeviceAnnouncement announcement = new DeviceAnnouncement(packet);
                                if (!isDeviceKnown(announcement)) {
                                    deliverFoundAnnouncement(announcement);
                                }
                                devices.put(announcement.getAddress(), announcement);
                            }
                            expireDevices();
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Problem processing DeviceAnnouncement packet", e);
                        }
                    }
                }
            });
            receiver.setDaemon(true);
            receiver.start();
        }
    }

    /**
     * Stop listening for device announcements. Also discard any announcements which had been received, and
     * notify any registered listeners that those devices have been lost.
     */
    public static synchronized void stop() {
        if (isActive()) {
            socket.close();
            socket = null;
            for (DeviceAnnouncement announcement : currentDevices()) {
                deliverLostAnnouncement(announcement);
            }
            devices.clear();
        }
    }

    /**
     * Get the set of DJ Link devices which currently can be seen on the network. These can be passed to
     * {@link VirtualCdj#getLatestStatusFor(DeviceUpdate)} to find the current detailed status for that device,
     * as long as the Virtual CDJ is active.
     *
     * @return the devices which have been heard from recently enough to be considered active
     */
    public static Set<DeviceAnnouncement> currentDevices() {
        expireDevices();
        return Collections.unmodifiableSet(new HashSet<DeviceAnnouncement>(devices.values()));
    }

    /**
     * Keeps track of the registered device announcement listeners.
     */
    private static final Set<DeviceAnnouncementListener> listeners = new HashSet<DeviceAnnouncementListener>();

    /**
     * Adds the specified device announcement listener to receive device announcements when DJ Link devices
     * are found on or leave the network. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the device announcement listener to add
     */
    public static synchronized void addDeviceAnnouncementListener(DeviceAnnouncementListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Removes the specified device announcement listener so that it no longer receives device announcements when
     * DJ Link devices are found on or leave the network. If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the device announcement listener to remove
     */
    public static synchronized void removeDeviceAnnouncementListener(DeviceAnnouncementListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Get the set of device announcement listeners that are currently registered.
     *
     * @return the currently registered device announcement listeners
     */
    public static synchronized Set<DeviceAnnouncementListener> getDeviceAnnouncementListeners() {
        return Collections.unmodifiableSet(new HashSet<DeviceAnnouncementListener>(listeners));
    }

    /**
     * Send a device found announcement to all registered listeners.
     *
     * @param announcement the message announcing the new device
     */
    private static void deliverFoundAnnouncement(final DeviceAnnouncement announcement) {
        for (final DeviceAnnouncementListener listener : getDeviceAnnouncementListeners()) {
            EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        listener.deviceFound(announcement);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Problem delivering device found announcement to listener", e);
                    }
                }
            });
        }
    }

    /**
     * Send a device lost announcement to all registered listeners.
     *
     * @param announcement the last message received from the vanished device
     */
    private static void deliverLostAnnouncement(final DeviceAnnouncement announcement) {
        for (final DeviceAnnouncementListener listener : getDeviceAnnouncementListeners()) {
            EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        listener.deviceLost(announcement);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Problem delivering device lost announcement to listener", e);
                    }
                }
            });
        }
    }

    /**
     * Prevent instantiation.
     */
    private DeviceFinder() {
        // Nothing to do.
    }
}
