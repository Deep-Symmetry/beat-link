package org.deepsymmetry.beatlink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class AnnouncementSocketConnection extends LifecycleParticipant implements SocketSender {
    private static final Logger logger = LoggerFactory.getLogger(AnnouncementSocketConnection.class);

    public static final AnnouncementSocketConnection ourInstance = new AnnouncementSocketConnection();

    /**
     * The socket used to receive device status packets while we are active.
     */
    private final AtomicReference<DatagramSocket> socket = new AtomicReference<DatagramSocket>();

    /**
     * Track when we started listening for announcement packets.
     */
    private static final AtomicLong startTime = new AtomicLong();

    /**
     * Maintain a set of addresses from which device announcements should be ignored. The {@link VirtualCdj} will add
     * its socket to this set when it is active so that it does not show up in the set of devices found on the network.
     */
    private final Set<InetAddress> ignoredAddresses =
            Collections.newSetFromMap(new ConcurrentHashMap<InetAddress, Boolean>());

    /**
     * The port to which devices broadcast announcement messages to report their presence on the network.
     */
    public static final int ANNOUNCEMENT_PORT = 50000;

    /**
     * Check whether we are presently posing as a virtual CDJ and receiving device status updates.
     *
     * @return true if our socket is open, sending presence announcements, and receiving status packets
     */
    public boolean isRunning() {
        return socket.get() != null;
    }
    public static AnnouncementSocketConnection getInstance() {
        return ourInstance;
    }

    /**
     * Send a master changed announcement to all registered master listeners.
     *
     * @param announcement the message announcing the new announcements
     */
    private void deliverAnnouncementPacket(final DeviceAnnouncement announcement) {
        for (final AnnouncementPacketListener listener : getAnnouncementPacketListeners()) {
            try {
                listener.handleAnnouncementPacket(announcement);
            } catch (Throwable t) {
                logger.warn("Problem delivering announcement to listener", t);
            }
        }
    }

    /**
     * Start listening for device announcements and keeping track of the DJ Link devices visible on the network.
     * If already listening, has no effect.
     *
     * This should only be called by classes that use the announcement socket (eg: DeviceFinder/VirtualRekordbox).
     *
     * @throws SocketException if the socket to listen on port 50000 cannot be created
     */
    public synchronized void start() throws SocketException {
        if (!isRunning()) {
            // This needs to happen first as this will be how users of this
            socket.set(new DatagramSocket(ANNOUNCEMENT_PORT));
            startTime.set(System.currentTimeMillis());
            deliverLifecycleAnnouncement(logger, true);

            final byte[] buffer = new byte[512];
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            Thread receiver = new Thread(null, new Runnable() {
                @Override
                public void run() {
                    boolean received;
                    while (isRunning()) {
                        try {
                            if (DeviceFinder.getInstance().getCurrentDevices().isEmpty()) {
                                socket.get().setSoTimeout(60000);  // We have no devices to check for timeout; block for a whole minute to check for shutdown
                            } else {
                                socket.get().setSoTimeout(1000);  // Check every second to see if a device has vanished
                            }
                            socket.get().receive(packet);
                            received = !ignoredAddresses.contains(packet.getAddress());
                        } catch (SocketTimeoutException ste) {
                            received = false;
                        } catch (IOException e) {
                            // Don't log a warning if the exception was due to the socket closing at shutdown.
                            if (isRunning()) {
                                // We did not expect to have a problem; log a warning and shut down.
                                logger.warn("Problem reading from DeviceAnnouncement socket, stopping", e);
                                stop();
                            }
                            received = false;
                        }
                        try {
                            if (received) {
                                final Util.PacketType kind = Util.validateHeader(packet, ANNOUNCEMENT_PORT);
                                if (kind == Util.PacketType.DEVICE_KEEP_ALIVE) {
                                    // Looks like the kind of packet we need
                                    if (packet.getLength() < 54) {
                                        logger.warn("Ignoring too-short " + kind.name + " packet; expected 54 bytes, but only got " +
                                                packet.getLength() + ".");
                                    } else {
                                        if (packet.getLength() > 54) {
                                            logger.warn("Processing too-long " + kind.name + " packet; expected 54 bytes, but got " +
                                                    packet.getLength() + ".");
                                        }
                                        DeviceAnnouncement announcement = new DeviceAnnouncement(packet);

                                        deliverAnnouncementPacket(announcement);
                                    }
                                } else if (kind == Util.PacketType.DEVICE_HELLO) {
                                    logger.debug("Received device hello packet.");
                                } else if (kind != null) {
                                    // TODO, probably should be a set of listeners to not couple VirtualCdj to this class
                                    VirtualCdj.getInstance().handleSpecialAnnouncementPacket(kind, packet);
                                }
                            }
                        } catch (Throwable t) {
                            logger.warn("Problem processing AnnouncementPacket", t);
                        }
                    }
                }
            }, "beat-link AnnouncementSocketConnection receiver");
            receiver.setDaemon(true);
            receiver.start();
        }
    }

    public void send(DatagramPacket packet) throws IOException {
        if (isRunning()) {
            socket.get().send(packet);
        } else {
            logger.warn("Socket is null, cannot send packet.");
        }
    }

    /**
     * Keeps track of the registered device announcement listeners.
     */
    private final Set<AnnouncementPacketListener> announcementPacketListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<AnnouncementPacketListener, Boolean>());

    /**
     * Adds the specified device announcement listener to receive device announcements when DJ Link devices
     * are found on or leave the network. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.
     *
     * <p>Device announcements are delivered to listeners on the
     * <a href="https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html">Event Dispatch thread</a>,
     * so it is fine to interact with user interface objects in listener methods. Any code in the listener method
     * must finish quickly, or unhandled events will back up and the user interface will be come unresponsive.</p>
     *
     * @param listener the device announcement listener to add
     */
    public void addAnnouncementPacketListener(AnnouncementPacketListener listener) {
        if (listener != null) {
            announcementPacketListeners.add(listener);
        }
    }

    /**
     * Removes the specified device announcement listener so that it no longer receives device announcements when
     * DJ Link devices are found on or leave the network. If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the device announcement listener to remove
     */
    public void removeAnnouncementPacketListener(AnnouncementPacketListener listener) {
        if (listener != null) {
            announcementPacketListeners.remove(listener);
        }
    }

    /**
     * Get the set of device announcement listeners that are currently registered.
     *
     * @return the currently registered device announcement listeners
     */
    @SuppressWarnings("WeakerAccess")
    public Set<AnnouncementPacketListener> getAnnouncementPacketListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<AnnouncementPacketListener>(announcementPacketListeners));
    }

    /**
     * Stop listening for device announcements. Also discard any announcements which had been received, and
     * notify any registered listeners that those devices have been lost.
     */
    @SuppressWarnings("WeakerAccess")
    public synchronized void stop() {
        if (isRunning()) {
            socket.get().close();
            socket.set(null);
            deliverLifecycleAnnouncement(logger, false);
        }
    }

    /**
     * Start ignoring any device updates which are received from the specified address. Intended for use by the
     * {@link VirtualCdj}, so that its updates do not cause it to appear as a device.
     *
     * @param address the address from which any device updates should be ignored.
     */
    public void addIgnoredAddress(InetAddress address) {
        ignoredAddresses.add(address);
    }

    /**
     * Stop ignoring device updates which are received from the specified address. Intended for use by the
     * {@link VirtualCdj}, so that when it shuts down, its socket stops being treated specially.
     *
     * @param address the address from which any device updates should be ignored.
     */
    public void removeIgnoredAddress(InetAddress address) {
        ignoredAddresses.remove(address);
    }

    /**
     * Check whether an address is being ignored. (The {@link BeatFinder} will call this so it can filter out the
     * {@link VirtualCdj}'s beat messages when it is broadcasting them, for example.
     *
     * @param address the address to be checked as a candidate to be ignored
     * @return {@code true} if packets from the address should be ignored
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isAddressIgnored(InetAddress address) {
        return ignoredAddresses.contains(address);
    }

    /**
     * Get the timestamp of when we started listening for device announcements.
     *
     * @return the system millisecond timestamp when {@link #start()} was called.
     * @throws IllegalStateException if we are not listening for announcements.
     */
    public long getStartTime() {
        ensureRunning();
        return startTime.get();
    }

    /**
     * Return the address being used on the Update Socket port to send presence announcement broadcasts.
     *
     * @return the local address we present to the DJ Link network
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public InetAddress getLocalAddress() {
        ensureRunning();
        return socket.get().getLocalAddress();
    }
}
