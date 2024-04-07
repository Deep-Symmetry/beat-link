package org.deepsymmetry.beatlink;

import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;

/**
 * Watches for devices to report their presence by broadcasting announcement packets on port 50000,
 * and keeps a list of the devices that have been seen, and the network address on which they were seen.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class DeviceFinder extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(DeviceFinder.class);

    /**
     * The number of milliseconds after which we will consider a device to have disappeared if
     * we have not received an announcement from it.
     */
    public static final int MAXIMUM_AGE = 10000;

    /**
     * The socket used to listen for announcement packets while we are active.
     */
    private final AtomicReference<DatagramSocket> socket = new AtomicReference<DatagramSocket>(null);

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
     * @return {@code true} if our socket is open and monitoring for DJ Link device announcements on the network
     */
    public boolean isRunning() {
        return AnnouncementSocketConnection.getInstance().isRunning();
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
     * Get the timestamp of when we saw the first announcement packet, to help the {@link VirtualCdj} determine how
     * long it needs to watch for devices in order to avoid conflicts when self-assigning a device number.
     *
     * @return the system millisecond timestamp when the first device announcement was received.
     * @throws IllegalStateException if we are not listening for announcements, or if none have been seen.
     */
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
    private final Map<DeviceReference, DeviceAnnouncement> devices = new ConcurrentHashMap<DeviceReference, DeviceAnnouncement>();

    /**
     * Remove any device announcements that are so old that the device seems to have gone away.
     */
    private void expireDevices() {
        long now = System.currentTimeMillis();
        // Make a copy so we don't have to worry about concurrent modification.
        Map<DeviceReference, DeviceAnnouncement> copy = new HashMap<DeviceReference, DeviceAnnouncement>(devices);
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
     * Record a device announcement in the devices map, so we know whe saw it.
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
     * Start ignoring any device updates which are received from the specified address. Intended for use by the
     * {@link VirtualCdj}, so that its updates do not cause it to appear as a device.
     *
     * This is a wrapper for AnnouncementSocketConnection.addIgnoredAddress()
     *
     * @param address the address from which any device updates should be ignored.
     */
    public void addIgnoredAddress(InetAddress address) {
        AnnouncementSocketConnection.getInstance().addIgnoredAddress(address);
    }

    /**
     * Stop ignoring device updates which are received from the specified address. Intended for use by the
     * {@link VirtualCdj}, so that when it shuts down, its socket stops being treated specially.
     *
     * This is a wrapper for AnnouncementSocketConnection.removeIgnoredAddress()
     *
     * @param address the address from which any device updates should be ignored.
     */
    public void removeIgnoredAddress(InetAddress address) {
        AnnouncementSocketConnection.getInstance().removeIgnoredAddress(address);
    }

    /**
     * Check whether an address is being ignored.
     *
     * This is a wrapper for AnnouncementSocketConnection.isAddressIgnored()
     *
     * @param address the address to be checked as a candidate to be ignored
     *
     * @return {@code true} if packets from the address should be ignored
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isAddressIgnored(InetAddress address) {
        return AnnouncementSocketConnection.getInstance().isAddressIgnored(address);
    }

    /**
     * Start listening for device announcements and keeping track of the DJ Link devices visible on the network.
     * If already listening, has no effect.
     *
     * @throws SocketException if the socket to listen on port 50000 cannot be created
     */
    public synchronized void start() throws SocketException {

        if (!isRunning()) {
            AnnouncementSocketConnection.getInstance().start();

            deliverLifecycleAnnouncement(logger, true);
        }
    }

    /**
     * Start listening for device announcements and keeping track of the DJ Link devices visible on the network.
     * If already listening, has no effect.
     *
     * @throws SocketException if the socket to listen on port 50000 cannot be created
     */
    public synchronized void handleDeviceAnnouncement(DeviceAnnouncement announcement) {
        if (isRunning()) {

            final boolean foundNewDevice = isDeviceNew(announcement);
            updateDevices(announcement);
            if (foundNewDevice) {
                deliverFoundAnnouncement(announcement);
            }
            if (VirtualCdj.getInstance().isRunning() &&
                    announcement.getDeviceNumber() == VirtualCdj.getInstance().getDeviceNumber()) {
                // Someone is using the same device number as we are! Try to defend it.
                VirtualCdj.getInstance().defendDeviceNumber(announcement.getAddress());
            }
        }

        expireDevices();
    }

/**
 * Discard any knowledge we have about current devices. Called when shutting down, and also by the
 * {@link VirtualCdj} when it believes the network has changed in a way that makes them no longer
 * reachable.
 */
synchronized void flush() {
    final Set<DeviceAnnouncement> lastDevices = new HashSet<DeviceAnnouncement>(devices.values());
    devices.clear();
    firstDeviceTime.set(0);

    // Report the loss of all our devices, on the proper thread, also outside our lock.
    SwingUtilities.invokeLater(new Runnable() {
        @Override
        public void run() {
            for (DeviceAnnouncement announcement : lastDevices) {
                deliverLostAnnouncement(announcement);
            }
        }
    });
}

/**
 * Get the set of DJ Link devices which currently can be seen on the network. These can be passed to
 * {@link VirtualCdj#getLatestStatusFor(DeviceUpdate)} to find the current detailed status for that device,
 * as long as the Virtual CDJ is active.
 *
 * @return the devices which have been heard from recently enough to be considered present on the network
 * @throws IllegalStateException if the {@code DeviceFinder} is not active
 */
public Map<DeviceReference, DeviceAnnouncement> getSynchronizedDevices() {
    if (!isRunning()) {
        throw new IllegalStateException("DeviceFinder is not active");
    }
    //expireDevices();  // Get rid of anything past its sell-by date.
    // Make a copy so callers get an immutable snapshot of the current state.
    return devices;
}

/**
 * Get the set of DJ Link devices which currently can be seen on the network. These can be passed to
 * {@link VirtualCdj#getLatestStatusFor(DeviceUpdate)} to find the current detailed status for that device,
 * as long as the Virtual CDJ is active.
 *
 * @return the devices which have been heard from recently enough to be considered present on the network
 * @throws IllegalStateException if the {@code DeviceFinder} is not active
 */
public Set<DeviceAnnouncement> getCurrentDevices() {
    if (!isRunning()) {
        throw new IllegalStateException("DeviceFinder is not active");
    }
    expireDevices();  // Get rid of anything past its sell-by date.
    // Make a copy so callers get an immutable snapshot of the current state.
    return Collections.unmodifiableSet(new HashSet<DeviceAnnouncement>(devices.values()));
}

/**
 * Find and return the device announcement that was most recently received from a device identifying itself
 * with the specified device number, if any.
 *
 * @param deviceNumber the device number of interest
 * @return the matching announcement or null if no such device has been heard from
 * @throws IllegalStateException if the {@code DeviceFinder} is not active
 */
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
        Collections.newSetFromMap(new ConcurrentHashMap<DeviceAnnouncementListener, Boolean>());

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
@SuppressWarnings("WeakerAccess")
public Set<DeviceAnnouncementListener> getDeviceAnnouncementListeners() {
    // Make a copy so callers get an immutable snapshot of the current state.
    return Collections.unmodifiableSet(new HashSet<DeviceAnnouncementListener>(deviceListeners));
}

/**
 * Send a device found announcement to all registered listeners.
 *
 * @param announcement the message announcing the new device
 */
private void deliverFoundAnnouncement(final DeviceAnnouncement announcement) {
    for (final DeviceAnnouncementListener listener : getDeviceAnnouncementListeners()) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.deviceFound(announcement);
                } catch (Throwable t) {
                    logger.warn("Problem delivering device found announcement to listener", t);
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
private void deliverLostAnnouncement(final DeviceAnnouncement announcement) {
    for (final DeviceAnnouncementListener listener : getDeviceAnnouncementListeners()) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.deviceLost(announcement);
                } catch (Throwable t) {
                    logger.warn("Problem delivering device lost announcement to listener", t);
                }
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
        sb.append(", startTime:").append(AnnouncementSocketConnection.getInstance().getStartTime()).append(", firstDeviceTime:").append(getFirstDeviceTime());
        sb.append(", currentDevices:").append(getCurrentDevices());
    }
    return sb.append("]").toString();
}
}
