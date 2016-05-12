package org.deepsymmetry.beatlink;

import java.awt.EventQueue;
import java.io.IOException;
import java.net.*;
import java.util.*;
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
     * Check whether we are presently posing as a virtual CDJ and receiving device status updates.
     *
     * @return true if our socket is open, sending presence announcements, and receiving status packets
     */
    public static synchronized boolean isActive() {
        return socket != null;
    }

    /**
     * Return the address being used by the virtual CDJ to send its own presence announcement broadcasts,
     * so they can be filtered out by the {@link DeviceFinder}.
     *
     * @return the local address we present to the DJ Link network
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public static synchronized InetAddress getLocalAddress() {
        ensureActive();
        return  socket.getLocalAddress();
    }

    /**
     * The broadcast address on which we can reach the DJ Link devices. Determined when we start
     * up by finding the network interface address on which we are receiving the other devices'
     * announcement broadcasts.
     */
    private static InetAddress broadcastAddress;

    /**
     * Return the broadcast address used to reach the DJ Link network.
     *
     * @return the address on which packets can be broadcast to the other DJ Link devices
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public static synchronized InetAddress getBroadcastAddress() {
        ensureActive();
        return broadcastAddress;
    }

    /**
     * Keep track of the most recent updates we have seen, indexed by the address they came from.
     */
    private static final Map<InetAddress, DeviceUpdate> updates = new HashMap<InetAddress, DeviceUpdate>();

    /**
     * Get the device number that is used when sending presence announcements on the network to pose as a virtual CDJ.
     *
     * @return the virtual player number
     */
    public static synchronized byte getDeviceNumber() {
        return announcementBytes[36];
    }

    /**
     * Set the device number to be used when sending presence announcements on the network to pose as a virtual CDJ.
     *
     * @param number the virtual player number
     */
    public static synchronized void setDeviceNumber(byte number) {
        announcementBytes[36] = number;
    }

    /**
     * The interval, in milliseconds, at which we post presence announcements on the network.
     */
    private static int announceInterval = 1500;

    /**
     * Get the interval, in milliseconds, at which we broadcast presence announcements on the network to pose as
     * a virtual CDJ.
     *
     * @return the announcement interval
     */
    public static synchronized int getAnnounceInterval() {
        return announceInterval;
    }

    /**
     * Set the interval, in milliseconds, at which we broadcast presence announcements on the network to pose as
     * a virtual CDJ.
     *
     * @param interval the announcement interval
     * @throws IllegalArgumentException if interval is not between 200 and 2000
     */
    public static synchronized void setAnnounceInterval(int interval) {
        if (interval < 200 || interval > 2000) {
            throw new IllegalArgumentException("Interval must be between 200 and 2000");
        }
        announceInterval = interval;
    }

    /**
     * Used to construct the announcement packet we broadcast in order to participate in the DJ Link network.
     * Some of these bytes are fixed, some get replaced by things like our device name and number, MAC address,
     * and IP address, as described in Figure 8 in the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    private static byte[] announcementBytes = {
            0x51, 0x73, 0x70, 0x74,  0x31, 0x57, 0x6d, 0x4a,   0x4f, 0x4c, 0x06, 0x00,  0x62, 0x65, 0x61, 0x74,
            0x2d, 0x6c, 0x69, 0x6e,  0x6b, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x01, 0x02, 0x00, 0x36,  0x05, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,  0x01, 0x00
    };

    /**
     * Get the name to be used in announcing our presence on the network.
     *
     * @return the device name reported in our presence announcement packets
     */
    public static synchronized String getDeviceName() {
        return new String(announcementBytes, 12, 20).trim();
    }

    /**
     * Set the name to be used in announcing our presence on the network. The name can be no longer than twenty
     * bytes, and should be normal ASCII, no Unicode.
     *
     * @param name the device name to report in our presence announcement packets.
     */
    public static synchronized void setDeviceName(String name) {
        if (name.getBytes().length > 20) {
            throw new IllegalArgumentException("name cannot be more than 20 bytes long");
        }
        Arrays.fill(announcementBytes, 12, 32, (byte)0);
        System.arraycopy(name.getBytes(), 0, announcementBytes, 12, name.getBytes().length);
    }

    /**
     * Helper method to throw an {@link IllegalStateException} if we are not currently active.
     *
     * @throws IllegalStateException if the {@link VirtualCdj} is not active
     */
    private static void ensureActive() {
        if (!isActive()) {
            throw new IllegalStateException("VirtualCdj is not active");
        }
    }

    /**
     * Keep track of which device has reported itself as the current tempo master.
     */
    private static DeviceUpdate tempoMaster;

    /**
     * Check which device is the current tempo master, returning the {@link DeviceUpdate} packet in which it
     * reported itself to be master. If there is no current tempo master returns {@code null}.
     *
     * @return the most recent update from a device which reported itself as the master
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public static synchronized DeviceUpdate getTempoMaster() {
        ensureActive();
        return tempoMaster;
    }

    /**
     * Establish a new tempo master, and if it is a change from the existing one, report it to the listeners.
     *
     * @param newMaster the packet which caused the change of masters, or {@code null} if there is now no master.
     */
    private static synchronized void setTempoMaster(DeviceUpdate newMaster) {
        if ((newMaster == null && tempoMaster != null) ||
                (newMaster != null && ((tempoMaster == null) || !newMaster.getAddress().equals(tempoMaster.getAddress())))) {
            // This is a change in master, so report it to any registered listeners
            deliverMasterChangedAnnouncement(newMaster);
        }
        tempoMaster = newMaster;
    }

    /**
     * How large a tempo change is required before we consider it to be a real difference.
     */
    private static double tempoEpsilon = 0.0001;

    /**
     * Find out how large a tempo change is required before we consider it to be a real difference.
     *
     * @return the BPM fraction that will trigger a tempo change update
     */
    public static synchronized double getTempoEpsilon() {
        return tempoEpsilon;
    }

    /**
     * Set how large a tempo change is required before we consider it to be a real difference.
     *
     * @param epsilon the BPM fraction that will trigger a tempo change update
     */
    public static synchronized void setTempoEpsilon(double epsilon) {
        tempoEpsilon = epsilon;
    }

    /**
     * Track the most recently reported master tempo.
     */
    private static double masterTempo;

    /**
     * Get the current master tempo.
     *
     * @return the most recently reported master tempo
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public static synchronized double getMasterTempo() {
        ensureActive();
        return masterTempo;
    }

    /**
     * Establish a new master tempo, and if it is a change from the existing one, report it to the listeners.
     *
     * @param newTempo the newly reported master tempo.
     */
    private static synchronized void setMasterTempo(double newTempo) {
        if ((tempoMaster != null) && (Math.abs(newTempo - masterTempo) > tempoEpsilon)) {
            // This is a change in tempo, so report it to any registered listeners
            deliverTempoChangedAnnouncement(newTempo);
            masterTempo = newTempo;
        }
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
            return new CdjStatus(packet);
        }
        logger.log(Level.WARNING, "Unrecognized device update packet with length " + length + " and kind " + kind);
        return null;
    }

    /**
     * Process a device update once it has been received. Track it as the most recent update from its address,
     * and notify any registered listeners, including master listeners if it results in changes to tracked state,
     * such as the current master player and tempo.
     */
    private static synchronized void processUpdate(DeviceUpdate update) {
        updates.put(update.getAddress(), update);
        if (update.isTempoMaster()) {
            setTempoMaster(update);
            setMasterTempo(update.getEffectiveTempo());
        } else {
            if (tempoMaster != null && tempoMaster.getAddress().equals(update.getAddress())) {
                // This device has resigned master status, and nobody else has claimed it so far
                setTempoMaster(null);
            }
        }
        deliverDeviceUpdate(update);
    }

    /**
     * Process a beat packet, potentially updating the master tempo and sending our listeners a master
     * beat notification. Does nothing if we are not active.
     */
    static synchronized void processBeat(Beat beat) {
        if (isActive() && beat.isTempoMaster()) {
            setMasterTempo(beat.getEffectiveTempo());
            deliverBeatAnnouncement(beat);
        }
    }

    /**
     * Scan a network interface to find if it has an address space which matches the device we are trying to reach.
     * If so, return the address specification.
     *
     * @param aDevice the DJ Link device we are trying to communicate with
     * @param networkInterface the network interface we are testing
     * @return the address which can be used to communicate with the device on the interface, or null
     */
    private static InterfaceAddress findMatchingAddress(DeviceAnnouncement aDevice, NetworkInterface networkInterface) {
        for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
            if ((address.getBroadcast() != null) &&
                    Util.sameNetwork(address.getNetworkPrefixLength(), aDevice.getAddress(), address.getAddress())) {
                return address;
            }
        }
        return null;
    }

    /**
     * Once we have seen some DJ Link devices on the network, we can proceed to create a virtual player on that
     * same network.
     *
     * @throws SocketException if there is a problem opening a socket on the right network
     */
    private static synchronized void createVirtualCdj() throws SocketException {
        // Find the network interface and address to use to communicate with the first device we found.
        NetworkInterface matchedInterface = null;
        InterfaceAddress matchedAddress = null;
        DeviceAnnouncement aDevice = DeviceFinder.currentDevices().iterator().next();
        for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            matchedAddress = findMatchingAddress(aDevice, networkInterface);
            if (matchedAddress != null) {
                matchedInterface = networkInterface;
                break;
            }
        }

        if (matchedAddress == null) {
            logger.log(Level.WARNING, "Unable to find network interface to communicate with " + aDevice +
                    ", giving up.");
            return;
        }

        // Copy the chosen interface's hardware and IP addresses into the announcement packet template
        System.arraycopy(matchedInterface.getHardwareAddress(), 0, announcementBytes, 38, 6);
        System.arraycopy(matchedAddress.getAddress().getAddress(), 0, announcementBytes, 44, 4);
        broadcastAddress = matchedAddress.getBroadcast();

        // Looking good. Open our communication socket and set up our threads.
        socket = new DatagramSocket(UPDATE_PORT, matchedAddress.getAddress());

        final byte[] buffer = new byte[512];
        final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        // Create the update reception thread
        Thread receiver = new Thread(null, new Runnable() {
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
                        if (received && (packet.getAddress() != socket.getLocalAddress())) {
                            DeviceUpdate update = buildUpdate(packet);
                            if (update != null) {
                                processUpdate(update);
                            }
                        }
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Problem processing device update packet", e);
                    }
                }
            }
        }, "beat-link VirtualCdj status receiver");
        receiver.setDaemon(true);
        receiver.start();

        // Create the thread which announces our participation in the DJ Link network, to request update packets
        Thread announcer = new Thread(null, new Runnable() {
            @Override
            public void run() {
                while (isActive()) {
                    sendAnnouncement(broadcastAddress);
                }
            }
        }, "beat-link VirtualCdj announcement sender");
        announcer.setDaemon(true);
        announcer.start();

    }

    /**
     * Start announcing ourselves and listening for status packets. If already active, has no effect. Requires the
     * {@link DeviceFinder} to be active in order to find out how to communicate with other devices, so will start
     * that if it is not already.
     *
     * @throws SocketException if the socket to listen on port 50002 cannot be created
     */
    public static void start() throws SocketException {
        if (!isActive()) {

            // Find some DJ Link devices so we can figure out the interface and address to use to talk to them
            DeviceFinder.start();
            for (int i = 0; DeviceFinder.currentDevices().isEmpty() && i < 20; i++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    logger.log(Level.WARNING, "Interrupted waiting for devices, giving up", e);
                    return;
                }
            }

            if (DeviceFinder.currentDevices().isEmpty()) {
                logger.log(Level.WARNING, "No DJ Link devices found, giving up");
                return;
            }

            createVirtualCdj();
        }
    }

    /**
     * Stop announcing ourselves and listening for status updates.
     */
    public static synchronized void stop() {
        if (isActive()) {
            socket.close();
            socket = null;
            broadcastAddress = null;
            updates.clear();
            setMasterTempo(0.0);
            setTempoMaster(null);
        }
    }

    /**
     * Send an announcement packet so the other devices see us as being part of the DJ Link network and send us
     * updates.
     */
    private static void sendAnnouncement(InetAddress broadcastAddress) {
        try {
            DatagramPacket announcement = new DatagramPacket(announcementBytes, announcementBytes.length,
                    broadcastAddress, DeviceFinder.ANNOUNCEMENT_PORT);
            socket.send(announcement);
            Thread.sleep(announceInterval);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Unable to send announcement packet, shutting down", e);
            stop();
        }
    }

    /**
     * Look up the most recent status we have seen for a device, given another update from it, which might be a
     * beat packet containing far less information.
     *
     * @param device the update identifying the device for which current status information is desired
     *
     * @return the most recent detailed status update received for that device
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public static DeviceUpdate getLatestStatusFor(DeviceUpdate device) {
        ensureActive();
        return updates.get(device.getAddress());
    }

    /**
     * Look up the most recent status we have seen for a device, given its device announcement packet as returned
     * by {@link DeviceFinder#currentDevices()}.
     *
     * @param device the announcement identifying the device for which current status information is desired
     *
     * @return the most recent detailed status update received for that device
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public static DeviceUpdate getLatestStatusFor(DeviceAnnouncement device) {
        ensureActive();
        return updates.get(device.getAddress());
    }

    /**
     * Keeps track of the registered master listeners.
     */
    private static final Set<MasterListener> masterListeners = new HashSet<MasterListener>();

    /**
     * Adds the specified master listener to receive device updates when there are changes related
     * to the tempo master. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the master listener to add
     */
    public static synchronized void addMasterListener(MasterListener listener) {
        if (listener != null) {
            masterListeners.add(listener);
        }
    }

    /**
     * Removes the specified master listener so that it no longer receives device updates when
     * there are changes related to the tempo master. If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the master listener to remove
     */
    public static synchronized void removeMasterListener(MasterListener listener) {
        if (listener != null) {
            masterListeners.remove(listener);
        }
    }

    /**
     * Get the set of master listeners that are currently registered.
     *
     * @return the currently registered tempo master listeners
     */
    public static synchronized Set<MasterListener> getMasterListeners() {
        return Collections.unmodifiableSet(new HashSet<MasterListener>(masterListeners));
    }

    /**
     * Send a master changed announcement to all registered master listeners.
     *
     * @param update the message announcing the new tempo master
     */
    private static void deliverMasterChangedAnnouncement(final DeviceUpdate update) {
        for (final MasterListener listener : getMasterListeners()) {
            EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        listener.masterChanged(update);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Problem delivering master changed announcement to listener", e);
                    }
                }
            });
        }
    }

    /**
     * Send a tempo changed announcement to all registered master listeners.
     *
     * @param tempo the new master tempo
     */
    private static void deliverTempoChangedAnnouncement(final double tempo) {
        for (final MasterListener listener : getMasterListeners()) {
            EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        listener.tempoChanged(tempo);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Problem delivering tempo changed announcement to listener", e);
                    }
                }
            });
        }
    }

    /**
     * Send a beat announcement to all registered master listeners.
     *
     * @param beat the beat sent by the tempo master
     */
    private static void deliverBeatAnnouncement(final Beat beat) {
        for (final MasterListener listener : getMasterListeners()) {
            EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        listener.newBeat(beat);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Problem delivering master beat announcement to listener", e);
                    }
                }
            });
        }
    }

    /**
     * Keeps track of the regitered device update listeners.
     */
    private static final Set<DeviceUpdateListener> updateListeners = new HashSet<DeviceUpdateListener>();

    /**
     * Adds the specified device update listener to receive device updates whenever they come in.
     * If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the device update listener to add
     */
    public static synchronized void addUpdateListener(DeviceUpdateListener listener) {
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
    public static synchronized void removeUpdateListener(DeviceUpdateListener listener) {
        if (listener != null) {
            updateListeners.remove(listener);
        }
    }

    /**
     * Get the set of device update listeners that are currently registered.
     *
     * @return the currently registered update listeners
     */
    public static synchronized Set<DeviceUpdateListener> getUpdateListeners() {
        return Collections.unmodifiableSet(new HashSet<DeviceUpdateListener>(updateListeners));
    }

    /**
     * Send a device update to all registered update listeners.
     *
     * @param update the device update that has just arrived
     */
    private static void deliverDeviceUpdate(final DeviceUpdate update) {
        for (final DeviceUpdateListener listener : getUpdateListeners()) {
            EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        listener.received(update);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Problem delivering device update to listener", e);
                    }
                }
            });
        }
    }

    /**
     * Prevent instantiation.
     */
    private VirtualCdj() {
        // Nothing to do.
    }
}
