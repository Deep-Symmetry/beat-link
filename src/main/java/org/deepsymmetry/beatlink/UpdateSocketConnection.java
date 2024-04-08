package org.deepsymmetry.beatlink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class UpdateSocketConnection extends LifecycleParticipant implements SocketSender {
    private static final Logger logger = LoggerFactory.getLogger(UpdateSocketConnection.class);

    private static final UpdateSocketConnection connectionsManager = new UpdateSocketConnection();

    /**
     * The socket used to receive device status packets while we are active.
     */
    private final AtomicReference<DatagramSocket> socket = new AtomicReference<DatagramSocket>();

    /**
     * Maintain a set of addresses from which device announcements should be ignored. The {@link VirtualCdj} will add
     * its socket to this set when it is active so that it does not show up in the set of devices found on the network.
     */
    private final Set<InetAddress> ignoredAddresses =
            Collections.newSetFromMap(new ConcurrentHashMap<InetAddress, Boolean>());

    /**
     * Hold the network interfaces which match the address on which we found player traffic. Should only be one,
     * or we will likely receive duplicate packets, which will cause problematic behavior.
     */
    private List<NetworkInterface> matchingInterfaces = null;

    /**
     * Check the interfaces that match the address from which we are receiving DJ Link traffic. If there is more
     * than one value in this list, that is a problem because we will likely receive duplicate packets that will
     * play havoc with our understanding of player states.
     *
     * @return the list of network interfaces on which we might receive player packets
     * @throws IllegalStateException if we are not running
     */
    public List<NetworkInterface> getMatchingInterfaces() {
        ensureRunning();
        return Collections.unmodifiableList(matchingInterfaces);
    }

    /**
     * Holds the interface address we chose to communicate with the DJ Link device we found during startup,
     * so we can check if there are any unreachable ones.
     */
    private InterfaceAddress matchedAddress = null;


    /**
     * The port to which devices broadcast updates messages to report their status on the network.
     */
    public static final int UPDATE_PORT = 50002;


    /**
     * Check whether we are presently posing as a virtual CDJ and receiving device status updates.
     *
     * @return true if our socket is open, sending presence announcements, and receiving status packets
     */
    public boolean isRunning() {
        return socket.get() != null;
    }
    public static UpdateSocketConnection getInstance() {
        return connectionsManager;
    }


    public InterfaceAddress getMatchedAddress() {
        return matchedAddress;
    }

    /**
     * Start announcing ourselves and listening for status packets. If already active, has no effect. Requires the
     * {@link DeviceFinder} to be active in order to find out how to communicate with other devices, so will start
     * that if it is not already.
     *
     * This should only ever be called by classes that use this class. No need t
     *
     * @return true if we found DJ Link devices and were able to create the {@code VirtualCdj}, or it was already running.
     * @throws SocketException if the socket to listen on port 50002 cannot be created
     */
    @SuppressWarnings("UnusedReturnValue")
    public synchronized boolean start() throws SocketException {
        if (!isRunning()) {
            deliverLifecycleAnnouncement(logger, true);
            // Find the network interface and address to use to communicate with the first device we found.
            matchingInterfaces = new ArrayList<NetworkInterface>();
            matchedAddress = null;

            DeviceFinder.getInstance().start();

            DeviceAnnouncement aDevice = DeviceFinder.getInstance().getCurrentDevices().iterator().next();
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                InterfaceAddress candidate = findMatchingAddress(aDevice, networkInterface);
                if (candidate != null) {
                    if (matchedAddress == null) {
                        matchedAddress = candidate;
                    }
                    matchingInterfaces.add(networkInterface);
                }
            }

            if (matchedAddress == null) {
                logger.warn("Unable to find network interface to communicate with " + aDevice +
                        ", giving up.");
                return false;
            }

            logger.info("Found matching network interface " + matchingInterfaces.get(0).getDisplayName() + " (" +
                    matchingInterfaces.get(0).getName() + "), will use address " + matchedAddress);
            if (matchingInterfaces.size() > 1) {
                for (ListIterator<NetworkInterface> it = matchingInterfaces.listIterator(1); it.hasNext(); ) {
                    NetworkInterface extra = it.next();
                    logger.warn("Network interface " + extra.getDisplayName() + " (" + extra.getName() +
                            ") sees same network: we will likely get duplicate DJ Link packets, causing severe problems.");
                }
            }

            // Open our communication socket.
            socket.set(new DatagramSocket(UPDATE_PORT, matchedAddress.getAddress()));

            // Inform the DeviceFinder to ignore our own device announcement packets.
            AnnouncementSocketConnection.getInstance().addIgnoredAddress(socket.get().getLocalAddress());

            // Set up our buffer and packet to receive incoming messages.
            final byte[] buffer = new byte[512];
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            // Create the update reception thread
            Thread receiver = new Thread(null, new Runnable() {
                @Override
                public void run() {
                    boolean received;
                    while (isRunning()) {
                        try {
                            socket.get().receive(packet);
                            received = true;
                        } catch (IOException e) {
                            // Don't log a warning if the exception was due to the socket closing at shutdown.
                            if (isRunning()) {
                                // We did not expect to have a problem; log a warning and shut down.
                                logger.warn("Problem reading from DeviceStatus socket, flushing DeviceFinder due to likely network change and shutting down.", e);
                                DeviceFinder.getInstance().flush();
                                stop();
                            }
                            received = false;
                        }
                        try {
                            if (received && (packet.getAddress() != socket.get().getLocalAddress())) {
                                DeviceUpdate update = buildUpdate(packet);
                                if (update != null) {
                                    deliverDeviceUpdate(update);
                                }
                            }
                        } catch (Throwable t) {
                            logger.warn("Problem processing device update packet", t);
                        }
                    }
                }
            }, "beat-link UpdateSocketConnection status receiver");
            receiver.setDaemon(true);
            receiver.setPriority(Thread.MAX_PRIORITY);
            receiver.start();


            return true;
        }
        return true;  // We were already active
    }

    /**
     * Scan a network interface to find if it has an address space which matches the device we are trying to reach.
     * If so, return the address specification.
     *
     * @param aDevice          the DJ Link device we are trying to communicate with
     * @param networkInterface the network interface we are testing
     * @return the address which can be used to communicate with the device on the interface, or null
     */
    private InterfaceAddress findMatchingAddress(DeviceAnnouncement aDevice, NetworkInterface networkInterface) {
        for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
            if (address == null) {
                // This should never happen, but we are protecting against a Windows Java bug, see
                // https://bugs.java.com/bugdatabase/view_bug?bug_id=8023649
                logger.warn("Received a null InterfaceAddress from networkInterface.getInterfaceAddresses(), is this Windows? " +
                        "Do you have a VPN installed? Trying to recover by ignoring it.");
            } else if ((address.getBroadcast() != null) &&
                    Util.sameNetwork(address.getNetworkPrefixLength(), aDevice.getAddress(), address.getAddress())) {
                return address;
            }
        }
        return null;
    }

    /**
     * Stop listening for status updates.
     */
    @SuppressWarnings("WeakerAccess")
    public synchronized void stop() {
        if (isRunning()) {
            removeIgnoredAddress(socket.get().getLocalAddress());
            socket.get().close();
            socket.set(null);// Set up for self-assignment if restarted.
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

    @Override
    public void send(DatagramPacket packet) throws IOException {
        if (isRunning()) {
            socket.get().send(packet);
        } else {
            logger.warn("Socket is null, cannot send packet.");
        }
    }

    /**
     * Return the address being used by the virtual CDJ to send its own presence announcement broadcasts.
     *
     * @return the local address we present to the DJ Link network
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public InetAddress getLocalAddress() {
        ensureRunning();
        return socket.get().getLocalAddress();
    }


    /**
     * Keeps track of the registered device update listeners.
     */
    private final Set<DeviceUpdateListener> updateListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<DeviceUpdateListener, Boolean>());

    /**
     * <p>Adds the specified device update listener to receive device updates whenever they come in.
     * If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * <p>To reduce latency, device updates are delivered to listeners directly on the thread that is receiving them
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and device updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the device update listener to add
     */
    @SuppressWarnings("SameParameterValue")
    public void addUpdateListener(DeviceUpdateListener listener) {
        if (listener != null) {
            updateListeners.add(listener);
        }
    }

    /**
     * Removes the specified device update listener so it no longer receives device updates when they come in.
     * If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the device update listener to remove
     */
    public void removeUpdateListener(DeviceUpdateListener listener) {
        if (listener != null) {
            updateListeners.remove(listener);
        }
    }

    /**
     * Get the set of device update listeners that are currently registered.
     *
     * @return the currently registered update listeners
     */
    public Set<DeviceUpdateListener> getUpdateListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<DeviceUpdateListener>(updateListeners));
    }

    /**
     * Send a device update to all registered update listeners.
     *
     * @param update the device update that has just arrived
     */
    public void deliverDeviceUpdate(final DeviceUpdate update) {
        for (DeviceUpdateListener listener : getUpdateListeners()) {
            try {
                listener.received(update);
            } catch (Throwable t) {
                logger.warn("Problem delivering device update to listener", t);
            }
        }
    }


    /**
     * Keeps track of the registered media details listeners.
     */
    private final Set<MediaDetailsListener> detailsListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<MediaDetailsListener, Boolean>());

    /**
     * <p>Adds the specified media details listener to receive detail responses whenever they come in.
     * If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * <p>To reduce latency, device updates are delivered to listeners directly on the thread that is receiving them
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and detail updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the media details listener to add
     */
    public void addMediaDetailsListener(MediaDetailsListener listener) {
        if (listener != null) {
            detailsListeners.add(listener);
        }
    }

    /**
     * Removes the specified media details listener so it no longer receives detail responses when they come in.
     * If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the media details listener to remove
     */
    public void removeMediaDetailsListener(MediaDetailsListener listener) {
        if (listener != null) {
            detailsListeners.remove(listener);
        }
    }

    /**
     * Get the set of media details listeners that are currently registered.
     *
     * @return the currently registered details listeners
     */
    public Set<MediaDetailsListener> getMediaDetailsListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<MediaDetailsListener>(detailsListeners));
    }

    /**
     * Send a media details response to all registered listeners.
     *
     * @param details the response that has just arrived
     */
    public void deliverMediaDetailsUpdate(final MediaDetails details) {
        for (MediaDetailsListener listener : getMediaDetailsListeners()) {
            try {
                listener.detailsAvailable(details);
            } catch (Throwable t) {
                logger.warn("Problem delivering media details response to listener", t);
            }
        }
    }



    /**
     * Given an update packet sent to us, create the appropriate object to describe it.
     *
     * @param packet the packet received on our update port
     * @return the corresponding {@link DeviceUpdate} subclass, or {@code nil} if the packet was not recognizable
     */
    private DeviceUpdate buildUpdate(DatagramPacket packet) {
        final int length = packet.getLength();
        final Util.PacketType kind = Util.validateHeader(packet, UPDATE_PORT);

        if (kind == null) {
            logger.warn("Ignoring unrecognized packet sent to update port.");
            return null;
        }

        switch (kind) {
            case MIXER_STATUS:
                if (length != 56) {
                    logger.warn("Processing a Mixer Status packet with unexpected length " + length + ", expected 56 bytes.");
                }
                if (length >= 56) {
                    return new MixerStatus(packet);
                } else {
                    logger.warn("Ignoring too-short Mixer Status packet.");
                    return null;
                }

            case CDJ_STATUS:
                if (length >= CdjStatus.MINIMUM_PACKET_SIZE) {
                    return new CdjStatus(packet);

                } else {
                    logger.warn("Ignoring too-short CDJ Status packet with length " + length + " (we need " + CdjStatus.MINIMUM_PACKET_SIZE +
                            " bytes).");
                    return null;
                }

            case LOAD_TRACK_ACK:
                logger.info("Received track load acknowledgment from player " + packet.getData()[0x21]);
                return null;

            case MEDIA_QUERY:
                logger.warn("Received a media query packet, we don’t yet support responding to this.");
                return null;

            case MEDIA_RESPONSE:
                if (packet.getLength() > MediaDetails.MINIMUM_PACKET_SIZE) {
                    logger.error("MEDIA RESPONSE");
                    deliverMediaDetailsUpdate(new MediaDetails(packet));
                }
                return null;

            case DEVICE_HELLO:
                if (packet.getLength() > MediaDetails.MINIMUM_PACKET_SIZE) {
                    logger.error("DEVICE HELLO");
                    deliverMediaDetailsUpdate(new MediaDetails(packet));
                }
                return null;

            default:
                logger.warn("Ignoring " + kind.name + " packet sent to update port.");
                return null;
        }
    }
}
