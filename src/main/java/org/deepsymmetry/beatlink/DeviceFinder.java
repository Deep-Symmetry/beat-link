package org.deepsymmetry.beatlink;

import org.apiguardian.api.API;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Watches for devices to report their presence by broadcasting announcement packets on port 50000,
 * and keeps a list of the devices that have been seen, and the network address on which they were seen.
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public class DeviceFinder extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(DeviceFinder.class);

    /**
     * The port to which devices broadcast announcement messages to report their presence on the network.
     */
    @API(status = API.Status.STABLE)
    public static final int ANNOUNCEMENT_PORT = 50000;

    /**
     * The number of milliseconds after which we will consider a device to have disappeared if
     * we have not received an announcement from it.
     */
    @API(status = API.Status.STABLE)
    public static final int MAXIMUM_AGE = 10000;

    /**
     * The socket used to listen for announcement packets while we are active.
     */
    private final AtomicReference<DatagramSocket> socket = new AtomicReference<>(null);

    /**
     * Track when we started listening for announcement packets.
     */
    private static final AtomicLong startTime = new AtomicLong();

    /**
     * Track when we saw the first announcement packet, to help the {@link VirtualCdj} determine how long it needs
     * to watch for devices in order to avoid conflicts when self-assigning a device number. Will be zero when none
     * have yet been seen.
     */
    private static final AtomicLong firstDeviceTime = new AtomicLong(0);

    /**
     * Check whether we are presently listening for device announcements.
     *
     * @return {@code true} if our socket is open and monitoring for DJ Link device announcements on the network,
     *         or if we were started in a mode where we delegate most of our responsibility to VirtualRekordbox
     */
    @API(status = API.Status.STABLE)
    public boolean isRunning() {
        return socket.get() != null;
    }

    /**
     * Get the timestamp of when we started listening for device announcements.
     *
     * @return the system millisecond timestamp when {@link #start()} was called.
     * @throws IllegalStateException if we are not listening for announcements.
     */
    @API(status = API.Status.STABLE)
    public long getStartTime() {
        ensureRunning();
        return startTime.get();
    }

    /**
     * Get the timestamp of when we saw the first announcement packet, to help the {@link VirtualCdj} determine how
     * long it needs to watch for devices in order to avoid conflicts when self-assigning a device number.
     *
     * @return the system millisecond timestamp when the first device announcement was received.
     * @throws IllegalStateException if we are not listening for announcements, or if none have been seen.
     */
    @API(status = API.Status.STABLE)
    public long getFirstDeviceTime() {
        ensureRunning();
        final long result = firstDeviceTime.get();
        if (result == 0) {
            throw new IllegalStateException("No device announcements have yet been seen");
        }
        return result;
    }

    /**
     * Keep track of the announcements we have seen.
     */
    private final Map<DeviceReference, DeviceAnnouncement> devices = new ConcurrentHashMap<>();

    /**
     * Remove any device announcements that are so old that the device seems to have gone away.
     */
     private void expireDevices() {
        long now = System.currentTimeMillis();
        // Make a copy, so we don't have to worry about concurrent modification.
        Map<DeviceReference, DeviceAnnouncement> copy = new HashMap<>(devices);
        for (Map.Entry<DeviceReference, DeviceAnnouncement> entry : copy.entrySet()) {
            if (now - entry.getValue().getTimestamp() > MAXIMUM_AGE) {
                devices.remove(entry.getKey());
                deliverLostAnnouncement(entry.getValue());
            }
        }
        if (devices.isEmpty()) {
            firstDeviceTime.set(0);  // We have lost contact with the Pro DJ Link network, so start over with next device.
        }
    }

    /**
     * Record a device announcement in the devices map, so we know we saw it.
     *
     * @param announcement the announcement to be recorded
     */
    private void updateDevices(DeviceAnnouncement announcement) {
        firstDeviceTime.compareAndSet(0, System.currentTimeMillis());
        devices.put(DeviceReference.getDeviceReference(announcement), announcement);
    }

    /**
     * Check whether a device is already known, or if it is newly found.
     *
     * @param announcement the message from the device to be considered
     *
     * @return true if this is the first message from this device
     */
    private boolean isDeviceNew(DeviceAnnouncement announcement) {
        return !devices.containsKey(DeviceReference.getDeviceReference(announcement));
    }

    /**
     * Maintain a set of addresses from which device announcements should be ignored. The {@link VirtualCdj} will add
     * its socket to this set when it is active so that it does not show up in the set of devices found on the network.
     */
    private final Set<InetAddress> ignoredAddresses =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Start ignoring any device updates which are received from the specified address. Intended for use by the
     * {@link VirtualCdj}, so that its updates do not cause it to appear as a device.
     *
     * @param address the address from which any device updates should be ignored.
     */
    @API(status = API.Status.STABLE)
    public void addIgnoredAddress(InetAddress address) {
        ignoredAddresses.add(address);
    }

    /**
     * Stop ignoring device updates which are received from the specified address. Intended for use by the
     * {@link VirtualCdj}, so that when it shuts down, its socket stops being treated specially.
     *
     * @param address the address from which any device updates should be ignored.
     */
    @API(status = API.Status.STABLE)
    public void removeIgnoredAddress(InetAddress address) {
        ignoredAddresses.remove(address);
    }

    /**
     * Check whether an address is being ignored. (The {@link BeatFinder} will call this, so it can filter out the
     * {@link VirtualCdj}'s beat messages when it is broadcasting them, for example).
     *
     * @param address the address to be checked as a candidate to be ignored
     *
     * @return {@code true} if packets from the address should be ignored
     */
    @API(status = API.Status.STABLE)
    public boolean isAddressIgnored(InetAddress address) {
        return ignoredAddresses.contains(address);
    }

    /**
     * Handle a device announcement packet we have received.
     *
     * @param announcement the device announcement that has been received.
     */
    private void processAnnouncement(DeviceAnnouncement announcement) {
        final boolean foundNewDevice = isDeviceNew(announcement);
        updateDevices(announcement);
        if (foundNewDevice) {
            deliverFoundAnnouncement(announcement);
        }
    }

    /**
     * Handle a device announcement packet we have received from the Opus Quad.
     *
     * @param packet the packet from Opus Quad to infer the 4 players
     */
    private void createAndProcessOpusAnnouncements(DatagramPacket packet) {
        for (int i = 1; i <= 4; i++) {
            DeviceAnnouncement opusAnnouncement = new DeviceAnnouncement(packet, i);
            updateDevices(opusAnnouncement);

            if (isDeviceNew(opusAnnouncement)) {
                deliverFoundAnnouncement(opusAnnouncement);
            }
        }
    }

    /**
     * <p>In normal operation (with Pro DJ Link devices), start listening for device announcements and keeping
     * track of the DJ Link devices visible on the network.  If VirtualRekordbox is running, then we are actually
     * in Opus Quad compatibility mode, and will do far less, acting as a proxy for packets that it is responsible
     * for receiving.</p>
     *
     * <p>If already active, has no effect.</p>
     *
     * @throws SocketException if the socket to listen on port 50000 cannot be created
     */
    @API(status = API.Status.STABLE)
    public synchronized void start() throws SocketException {

        if (!isRunning()) {
            startTime.set(System.currentTimeMillis());
            deliverLifecycleAnnouncement(logger, true);

            socket.set(new DatagramSocket(ANNOUNCEMENT_PORT));

            final byte[] buffer = new byte[512];
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            Thread receiver = new Thread(null, () -> {
                boolean received;
                while (isRunning()) {
                    try {
                        if (getCurrentDevices().isEmpty()) {
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
                                    logger.warn("Ignoring too-short {} packet; expected 54 bytes, but only got {}.", kind.name, packet.getLength());
                                } else {
                                    if (packet.getLength() > 54) {
                                        logger.warn("Processing too-long {} packet; expected 54 bytes, but got {}.", kind.name, packet.getLength());
                                    }

                                    DeviceAnnouncement announcement = new DeviceAnnouncement(packet);

                                    if (announcement.isOpusQuad) {
                                        createAndProcessOpusAnnouncements(packet);
                                    } else {
                                        processAnnouncement(announcement);

                                        if (VirtualCdj.getInstance().isRunning() &&
                                                announcement.getDeviceNumber() == VirtualCdj.getInstance().getDeviceNumber()) {
                                            // Someone is using the same device number as we are! Try to defend it.
                                            VirtualCdj.getInstance().defendDeviceNumber(announcement.getAddress());
                                        }
                                    }
                                }
                            } else if (kind == Util.PacketType.DEVICE_HELLO) {
                                logger.debug("Received device hello packet.");
                            } else if (kind != null) {
                                VirtualCdj.getInstance().handleSpecialAnnouncementPacket(kind, packet);
                            }
                        }
                        expireDevices();
                    } catch (Throwable t) {
                        logger.warn("Problem processing DeviceAnnouncement packet", t);
                    }
                }
            }, "beat-link DeviceFinder receiver");
            receiver.setDaemon(true);
            receiver.start();
        }
    }

    /**
     * Discard any knowledge we have about current devices. Called when shutting down, and also by the
     * {@link VirtualCdj} when it believes the network has changed in a way that makes them no longer
     * reachable.
     */
    synchronized void flush() {
        final Set<DeviceAnnouncement> lastDevices = new HashSet<>(devices.values());
        devices.clear();
        firstDeviceTime.set(0);

        // Report the loss of all our devices, on the proper thread, also outside our lock.
        SwingUtilities.invokeLater(() -> {
            for (DeviceAnnouncement announcement : lastDevices) {
                deliverLostAnnouncement(announcement);
            }
        });
    }

    /**
     * Stop listening for device announcements. Also discard any announcements which had been received, and
     * notify any registered listeners that those devices have been lost.
     */
    @API(status = API.Status.STABLE)
    public synchronized void stop() {
        if (isRunning()) {
            socket.get().close();
            socket.set(null);
            flush();
            deliverLifecycleAnnouncement(logger, false);
        }
    }

    /**
     * Get the set of DJ Link devices which currently can be seen on the network. These can be passed to
     * {@link VirtualCdj#getLatestStatusFor(DeviceUpdate)} to find the current detailed status for that device,
     * as long as the Virtual CDJ is active.
     *
     * @return the devices which have been heard from recently enough to be considered present on the network
     *
     * @throws IllegalStateException if the {@code DeviceFinder} is not active
     */
    @API(status = API.Status.STABLE)
    public Set<DeviceAnnouncement> getCurrentDevices() {
        if (!isRunning()) {
            throw new IllegalStateException("DeviceFinder is not active");
        }
        expireDevices();  // Get rid of anything past its sell-by date.
        // Make a copy so callers get an immutable snapshot of the current state.
        return Set.copyOf(devices.values());
    }

    /**
     * Find and return the device announcement that was most recently received from a device identifying itself
     * with the specified device number, if any.
     *
     * @param deviceNumber the device number of interest
     *
     * @return the matching announcement or null if no such device has been heard from
     *
     * @throws IllegalStateException if the {@code DeviceFinder} is not active
     */
    @API(status = API.Status.STABLE)
    public DeviceAnnouncement getLatestAnnouncementFrom(int deviceNumber) {
        ensureRunning();
        for (DeviceAnnouncement announcement : getCurrentDevices()) {
            if (announcement.getDeviceNumber() == deviceNumber) {
                return announcement;
            }
        }
        return null;
    }

    /**
     * Keeps track of the registered device announcement listeners.
     */
    private final Set<DeviceAnnouncementListener> deviceListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
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
    @API(status = API.Status.STABLE)
    public void addDeviceAnnouncementListener(DeviceAnnouncementListener listener) {
        if (listener != null) {
            deviceListeners.add(listener);
        }
    }

    /**
     * Removes the specified device announcement listener so that it no longer receives device announcements when
     * DJ Link devices are found on or leave the network. If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the device announcement listener to remove
     */
    public void removeDeviceAnnouncementListener(DeviceAnnouncementListener listener) {
        if (listener != null) {
            deviceListeners.remove(listener);
        }
    }

    /**
     * Get the set of device announcement listeners that are currently registered.
     *
     * @return the currently registered device announcement listeners
     */
    @API(status = API.Status.STABLE)
    public Set<DeviceAnnouncementListener> getDeviceAnnouncementListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Set.copyOf(deviceListeners);
    }

    /**
     * Send a device found announcement to all registered listeners.
     *
     * @param announcement the message announcing the new device
     */
    private void deliverFoundAnnouncement(final DeviceAnnouncement announcement) {
        for (final DeviceAnnouncementListener listener : getDeviceAnnouncementListeners()) {
            SwingUtilities.invokeLater(() -> {
                try {
                    listener.deviceFound(announcement);
                } catch (Throwable t) {
                    logger.warn("Problem delivering device found announcement to listener", t);
                }
            });
        }
    }

    /**
     * Send a device lost announcement to all registered listeners.
     *
     * @param announcement the last message received from the vanished device
     */
    private void deliverLostAnnouncement(final DeviceAnnouncement announcement) {
        for (final DeviceAnnouncementListener listener : getDeviceAnnouncementListeners()) {
            SwingUtilities.invokeLater(() -> {
                try {
                    listener.deviceLost(announcement);
                } catch (Throwable t) {
                    logger.warn("Problem delivering device lost announcement to listener", t);
                }
            });
        }
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final DeviceFinder ourInstance = new DeviceFinder();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    @API(status = API.Status.STABLE)
    public static DeviceFinder getInstance() {
        return ourInstance;
    }

    /**
     * Prevent direct instantiation.
     */
    private DeviceFinder() {
        // Nothing to do.
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DeviceFinder[active:").append(isRunning());
        if (isRunning()) {
            sb.append(", startTime:").append(getStartTime()).append(", firstDeviceTime:").append(getFirstDeviceTime());
            sb.append(", currentDevices:").append(getCurrentDevices());
        }
        return sb.append("]").toString();
    }
}
