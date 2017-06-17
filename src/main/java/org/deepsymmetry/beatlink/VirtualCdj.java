package org.deepsymmetry.beatlink;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.deepsymmetry.beatlink.data.MetadataFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides the ability to create a virtual CDJ device that can lurk on a DJ Link network and receive packets sent to
 * players, monitoring the detailed state of the other devices. This detailed information is helpful for augmenting
 * what {@link BeatFinder} reports, allowing you to keep track of which player is the tempo master, how many beats of
 * a track have been played, how close a player is getting to its next cue point, and more. It is also the foundation
 * for finding out the rekordbox ID of the loaded track, which supports all the features associated with the
 * {@link MetadataFinder}.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class VirtualCdj extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(VirtualCdj.class);

    /**
     * The port to which other devices will send status update messages.
     */
    @SuppressWarnings("WeakerAccess")
    public static final int UPDATE_PORT = 50002;

    /**
     * The socket used to receive device status packets while we are active.
     */
    private DatagramSocket socket;

    /**
     * Check whether we are presently posing as a virtual CDJ and receiving device status updates.
     *
     * @return true if our socket is open, sending presence announcements, and receiving status packets
     */
    public synchronized boolean isRunning() {
        return socket != null;
    }

    /**
     * Return the address being used by the virtual CDJ to send its own presence announcement broadcasts.
     *
     * @return the local address we present to the DJ Link network
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public synchronized InetAddress getLocalAddress() {
        ensureRunning();
        return socket.getLocalAddress();
    }

    /**
     * The broadcast address on which we can reach the DJ Link devices. Determined when we start
     * up by finding the network interface address on which we are receiving the other devices'
     * announcement broadcasts.
     */
    private InetAddress broadcastAddress;

    /**
     * Return the broadcast address used to reach the DJ Link network.
     *
     * @return the address on which packets can be broadcast to the other DJ Link devices
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public synchronized InetAddress getBroadcastAddress() {
        ensureRunning();
        return broadcastAddress;
    }

    /**
     * Keep track of the most recent updates we have seen, indexed by the address they came from.
     */
    private final Map<InetAddress, DeviceUpdate> updates = new ConcurrentHashMap<InetAddress, DeviceUpdate>();

    /**
     * Should we try to use a device number in the range 1 to 4 if we find one is available?
     */
    private boolean useStandardPlayerNumber = false;

    /**
     * When self-assigning a player number, should we try to use a value that is legal for a standard CDJ, in
     * the range 1 to 4? By default, we do not, to avoid any potential conflict with real players. However, if
     * the user is intending to use the {@link MetadataFinder}, and will always have fewer than four real players
     * on the network, this can be set to {@code true}, and a device number in this range will be chosen if it
     * is not in use on the network during startup.
     *
     * @param attempt true if self-assignment should try to use device numbers below 5 when available
     */
    public synchronized void setUseStandardPlayerNumber(boolean attempt) {
        useStandardPlayerNumber = attempt;
    }

    /**
     * When self-assigning a player number, should we try to use a value that is legal for a standard CDJ, in
     * the range 1 to 4? By default, we do not, to avoid any potential conflict with real players. However, if
     * the user is intending to use the {@link MetadataFinder}, and will always have fewer than four real players
     * on the network, this can be set to {@code true}, and a device number in this range will be chosen if it
     * is not in use on the network during startup.
     *
     * @return true if self-assignment should try to use device numbers below 5 when available
     */
    public synchronized boolean getUseStandardPlayerNumber() {
        return useStandardPlayerNumber;
    }

    /**
     * Get the device number that is used when sending presence announcements on the network to pose as a virtual CDJ.
     * This starts out being zero unless you explicitly assign another value, which means that the <code>VirtualCdj</code>
     * should assign itself an unused device number by watching the network when you call
     * {@link #start()}. If {@link #getUseStandardPlayerNumber()} returns {@code true}, self-assignment will try to
     * find a value in the range 1 to 4. Otherwise (or if those values are all used by other players), it will try to
     * find a value in the range 5 to 15.
     *
     * @return the virtual player number
     */
    public synchronized byte getDeviceNumber() {
        return announcementBytes[36];
    }

    /**
     * <p>Set the device number to be used when sending presence announcements on the network to pose as a virtual CDJ.
     * If this is set to zero before {@link #start()} is called, the {@code VirtualCdj} will watch the network to
     * look for an unused device number, and assign itself that number during startup. If you
     * explicitly assign a non-zero value, it will use that device number instead. Setting the value to zero while
     * already up and running reassigns it to an unused value immediately. If {@link #getUseStandardPlayerNumber()}
     * returns {@code true}, self-assignment will try to find a value in the range 1 to 4. Otherwise (or if those
     * values are all used by other players), it will try to find a value in the range 5 to 15.</p>
     *
     * <p>The device number defaults to 0, enabling self-assignment, and will be reset to that each time the
     * {@code VirtualCdj} is stopped.</p>
     *
     * @param number the virtual player number
     */
    @SuppressWarnings("WeakerAccess")
    public synchronized void setDeviceNumber(byte number) {
        if (number == 0 && isRunning()) {
            selfAssignDeviceNumber();
        } else {
            announcementBytes[36] = number;
        }
    }

    /**
     * The interval, in milliseconds, at which we post presence announcements on the network.
     */
    private int announceInterval = 1500;

    /**
     * Get the interval, in milliseconds, at which we broadcast presence announcements on the network to pose as
     * a virtual CDJ.
     *
     * @return the announcement interval
     */
    public synchronized int getAnnounceInterval() {
        return announceInterval;
    }

    /**
     * Set the interval, in milliseconds, at which we broadcast presence announcements on the network to pose as
     * a virtual CDJ.
     *
     * @param interval the announcement interval
     * @throws IllegalArgumentException if interval is not between 200 and 2000
     */
    public synchronized void setAnnounceInterval(int interval) {
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
    private static final byte[] announcementBytes = {
            0x51, 0x73, 0x70, 0x74,  0x31, 0x57, 0x6d, 0x4a,   0x4f, 0x4c, 0x06, 0x00,  0x62, 0x65, 0x61, 0x74,
            0x2d, 0x6c, 0x69, 0x6e,  0x6b, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x01, 0x02, 0x00, 0x36,  0x00, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,  0x01, 0x00
    };

    /**
     * Get the name to be used in announcing our presence on the network.
     *
     * @return the device name reported in our presence announcement packets
     */
    public static String getDeviceName() {
        return new String(announcementBytes, 12, 20).trim();
    }

    /**
     * Set the name to be used in announcing our presence on the network. The name can be no longer than twenty
     * bytes, and should be normal ASCII, no Unicode.
     *
     * @param name the device name to report in our presence announcement packets.
     */
    public synchronized void setDeviceName(String name) {
        if (name.getBytes().length > 20) {
            throw new IllegalArgumentException("name cannot be more than 20 bytes long");
        }
        Arrays.fill(announcementBytes, 12, 32, (byte)0);
        System.arraycopy(name.getBytes(), 0, announcementBytes, 12, name.getBytes().length);
    }

    /**
     * Keep track of which device has reported itself as the current tempo master.
     */
    private DeviceUpdate tempoMaster;

    /**
     * Check which device is the current tempo master, returning the {@link DeviceUpdate} packet in which it
     * reported itself to be master. If there is no current tempo master returns {@code null}.
     *
     * @return the most recent update from a device which reported itself as the master
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public synchronized DeviceUpdate getTempoMaster() {
        ensureRunning();
        return tempoMaster;
    }

    /**
     * Establish a new tempo master, and if it is a change from the existing one, report it to the listeners.
     *
     * @param newMaster the packet which caused the change of masters, or {@code null} if there is now no master.
     */
    private synchronized void setTempoMaster(DeviceUpdate newMaster) {
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
    private double tempoEpsilon = 0.0001;

    /**
     * Find out how large a tempo change is required before we consider it to be a real difference.
     *
     * @return the BPM fraction that will trigger a tempo change update
     */
    public synchronized double getTempoEpsilon() {
        return tempoEpsilon;
    }

    /**
     * Set how large a tempo change is required before we consider it to be a real difference.
     *
     * @param epsilon the BPM fraction that will trigger a tempo change update
     */
    public synchronized void setTempoEpsilon(double epsilon) {
        tempoEpsilon = epsilon;
    }

    /**
     * Track the most recently reported master tempo.
     */
    private double masterTempo;

    /**
     * Get the current master tempo.
     *
     * @return the most recently reported master tempo
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public synchronized double getMasterTempo() {
        ensureRunning();
        return masterTempo;
    }

    /**
     * Establish a new master tempo, and if it is a change from the existing one, report it to the listeners.
     *
     * @param newTempo the newly reported master tempo.
     */
    private synchronized void setMasterTempo(double newTempo) {
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
    private DeviceUpdate buildUpdate(DatagramPacket packet) {
        int length = packet.getLength();
        int kind = packet.getData()[10];
        if (length == 56 && kind == 0x29 && Util.validateHeader(packet, 0x29, "Mixer Status")) {
            return new MixerStatus(packet);
        } else if ((length == 212 || length == 208 || length == 284 || length == 292) &&
                kind == 0x0a && Util.validateHeader(packet, 0x0a, "CDJ Status")) {
            return new CdjStatus(packet);
        }
        logger.warn("Unrecognized device update packet with length " + length + " and kind " + kind);
        return null;
    }

    /**
     * Process a device update once it has been received. Track it as the most recent update from its address,
     * and notify any registered listeners, including master listeners if it results in changes to tracked state,
     * such as the current master player and tempo.
     */
    private synchronized void processUpdate(DeviceUpdate update) {
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
    synchronized void processBeat(Beat beat) {
        if (isRunning() && beat.isTempoMaster()) {
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
    private InterfaceAddress findMatchingAddress(DeviceAnnouncement aDevice, NetworkInterface networkInterface) {
        for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
            if ((address.getBroadcast() != null) &&
                    Util.sameNetwork(address.getNetworkPrefixLength(), aDevice.getAddress(), address.getAddress())) {
                return address;
            }
        }
        return null;
    }

    /**
     * The number of milliseconds for which the {@link DeviceFinder} needs to have been watching the network in order
     * for us to be confident we can choose a device number that will not conflict.
     */
    private static final long SELF_ASSIGNMENT_WATCH_PERIOD = 4000;

    /**
     * Try to choose a device number, which we have not seen on the network. Start by making sure
     * we have been watching long enough to have seen the other devices. Then, if {@link #useStandardPlayerNumber} is
     * {@code true}, try to use a standard player number in the range 1-4 if possible. Otherwise (or if all those
     * numbers are already in use), pick a number from 5 to 15.
     */
    private boolean selfAssignDeviceNumber() {
        final long now = System.currentTimeMillis();
        final long started = DeviceFinder.getInstance().getFirstDeviceTime();
        if (now - started < SELF_ASSIGNMENT_WATCH_PERIOD) {
            try {
                Thread.sleep(SELF_ASSIGNMENT_WATCH_PERIOD - (now - started));  // Sleep until we hit the right time
            } catch (InterruptedException e) {
                logger.warn("Interrupted waiting to self-assign device number, giving up.");
                return false;
            }
        }
        Set<Integer> numbersUsed = new HashSet<Integer>();
        for (DeviceAnnouncement device : DeviceFinder.getInstance().getCurrentDevices()) {
            numbersUsed.add(device.getNumber());
        }

        // Try all player numbers less than mixers use, only including the real player range if we are configured to.
        final int startingNumber = (useStandardPlayerNumber ? 1 : 5);
        for (int result = startingNumber; result < 16; result++) {
            if (!numbersUsed.contains(result)) {  // We found one that is not used, so we can use it
                setDeviceNumber((byte) result);
                if (useStandardPlayerNumber && (result > 4)) {
                    logger.warn("Unable to self-assign a standard player number, all are in use. Using number " +
                            result + ".");
                }
                return true;
            }
        }
        logger.warn("Found no unused device numbers between " + startingNumber + " and 15, giving up.");
        return false;
    }

    /**
     * Once we have seen some DJ Link devices on the network, we can proceed to create a virtual player on that
     * same network.
     *
     * @return true if we found DJ Link devices and were able to create the {@code VirtualCdj}.
     * @throws SocketException if there is a problem opening a socket on the right network
     */
    private boolean createVirtualCdj() throws SocketException {
        // Find the network interface and address to use to communicate with the first device we found.
        NetworkInterface matchedInterface = null;
        InterfaceAddress matchedAddress = null;
        DeviceAnnouncement aDevice = DeviceFinder.getInstance().getCurrentDevices().iterator().next();
        for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            matchedAddress = findMatchingAddress(aDevice, networkInterface);
            if (matchedAddress != null) {
                matchedInterface = networkInterface;
                break;
            }
        }

        if (matchedAddress == null) {
            logger.warn("Unable to find network interface to communicate with " + aDevice +
                    ", giving up.");
            return false;
        }

        if (getDeviceNumber() == 0) {
            if (!selfAssignDeviceNumber()) {
                return false;
            }
        }

        // Copy the chosen interface's hardware and IP addresses into the announcement packet template
        System.arraycopy(matchedInterface.getHardwareAddress(), 0, announcementBytes, 38, 6);
        System.arraycopy(matchedAddress.getAddress().getAddress(), 0, announcementBytes, 44, 4);
        broadcastAddress = matchedAddress.getBroadcast();

        // Looking good. Open our communication socket and set up our threads.
        socket = new DatagramSocket(UPDATE_PORT, matchedAddress.getAddress());

        // Inform the DeviceFinder to ignore our own device announcement packets.
        DeviceFinder.getInstance().addIgnoredAddress(socket.getLocalAddress());

        final byte[] buffer = new byte[512];
        final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        // Create the update reception thread
        Thread receiver = new Thread(null, new Runnable() {
            @Override
            public void run() {
                boolean received;
                while (isRunning()) {
                    try {
                        socket.receive(packet);
                        received = true;
                    } catch (IOException e) {
                        // Don't log a warning if the exception was due to the socket closing at shutdown.
                        if (isRunning()) {
                            // We did not expect to have a problem; log a warning and shut down.
                            logger.warn("Problem reading from DeviceStatus socket, stopping", e);
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
                        logger.warn("Problem processing device update packet", e);
                    }
                }
            }
        }, "beat-link VirtualCdj status receiver");
        receiver.setDaemon(true);
        receiver.setPriority(Thread.MAX_PRIORITY);
        receiver.start();

        // Create the thread which announces our participation in the DJ Link network, to request update packets
        Thread announcer = new Thread(null, new Runnable() {
            @Override
            public void run() {
                while (isRunning()) {
                    sendAnnouncement(broadcastAddress);
                }
            }
        }, "beat-link VirtualCdj announcement sender");
        announcer.setDaemon(true);
        announcer.start();
        deliverLifecycleAnnouncement(logger, true);
        return true;
    }

    private final LifecycleListener deviceFinderLifecycleListener = new LifecycleListener() {
        @Override
        public void started(LifecycleParticipant sender) {
            logger.debug("VirtualCDJ doesn't have anything to do when the DeviceFinder starts");
        }

        @Override
        public void stopped(LifecycleParticipant sender) {
            if (isRunning()) {
                logger.info("VirtualCDJ stopping because DeviceFinder has stopped.");
                stop();
            }
        }
    };

    /**
     * Start announcing ourselves and listening for status packets. If already active, has no effect. Requires the
     * {@link DeviceFinder} to be active in order to find out how to communicate with other devices, so will start
     * that if it is not already.
     *
     * @return true if we found DJ Link devices and were able to create the {@code VirtualCdj}, or it was already running.
     * @throws SocketException if the socket to listen on port 50002 cannot be created
     */
    @SuppressWarnings("UnusedReturnValue")
    public synchronized boolean start() throws SocketException {
        if (!isRunning()) {
            // Set up so we know we have to shut down if the DeviceFinder shuts down.
            DeviceFinder.getInstance().addLifecycleListener(deviceFinderLifecycleListener);

            // Find some DJ Link devices so we can figure out the interface and address to use to talk to them
            DeviceFinder.getInstance().start();
            for (int i = 0; DeviceFinder.getInstance().getCurrentDevices().isEmpty() && i < 20; i++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    logger.warn("Interrupted waiting for devices, giving up", e);
                    return false;
                }
            }

            if (DeviceFinder.getInstance().getCurrentDevices().isEmpty()) {
                logger.warn("No DJ Link devices found, giving up");
                return false;
            }

            return createVirtualCdj();
        }
        return true;  // We were already active
    }

    /**
     * Stop announcing ourselves and listening for status updates.
     */
    @SuppressWarnings("WeakerAccess")
    public synchronized void stop() {
        if (isRunning()) {
            DeviceFinder.getInstance().removeIgnoredAddress(socket.getLocalAddress());
            socket.close();
            socket = null;
            broadcastAddress = null;
            updates.clear();
            setMasterTempo(0.0);
            setTempoMaster(null);
            setDeviceNumber((byte)0);  // Set up for self-assignment if restarted.
            deliverLifecycleAnnouncement(logger, false);
        }
    }

    /**
     * Send an announcement packet so the other devices see us as being part of the DJ Link network and send us
     * updates.
     */
    private void sendAnnouncement(InetAddress broadcastAddress) {
        try {
            DatagramPacket announcement = new DatagramPacket(announcementBytes, announcementBytes.length,
                    broadcastAddress, DeviceFinder.ANNOUNCEMENT_PORT);
            socket.send(announcement);
            Thread.sleep(announceInterval);
        } catch (Exception e) {
            logger.warn("Unable to send announcement packet, shutting down", e);
            stop();
        }
    }

    /**
     * Get the most recent status we have seen from all devices that are recent enough to be considered still
     * active on the network.

     * @return the most recent detailed status update received for all active devices
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public Set<DeviceUpdate> getLatestStatus() {
        ensureRunning();
        Set<DeviceUpdate> result = new HashSet<DeviceUpdate>();
        long now = System.currentTimeMillis();
        for (DeviceUpdate update : updates.values()) {
            if (now - update.getTimestamp() <= DeviceFinder.MAXIMUM_AGE) {
                result.add(update);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Look up the most recent status we have seen for a device, given another update from it, which might be a
     * beat packet containing far less information.
     *
     * <em>Note:</em> If you are trying to determine the current tempo or beat being played by the device, you should
     * use {@link org.deepsymmetry.beatlink.data.TimeFinder#getLatestUpdateFor(DeviceUpdate)} instead, because that
     * combines both status updates and beat messages, and so is more likely to be current and definitive.
     *
     * @param device the update identifying the device for which current status information is desired
     *
     * @return the most recent detailed status update received for that device
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public DeviceUpdate getLatestStatusFor(DeviceUpdate device) {
        ensureRunning();
        return updates.get(device.getAddress());
    }

    /**
     * Look up the most recent status we have seen for a device, given its device announcement packet as returned
     * by {@link DeviceFinder#getCurrentDevices()}.
     *
     * <em>Note:</em> If you are trying to determine the current tempo or beat being played by the device, you should
     * use {@link org.deepsymmetry.beatlink.data.TimeFinder#getLatestUpdateFor(int)} instead, because that
     * combines both status updates and beat messages, and so is more likely to be current and definitive.
     *
     * @param device the announcement identifying the device for which current status information is desired
     *
     * @return the most recent detailed status update received for that device
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public DeviceUpdate getLatestStatusFor(DeviceAnnouncement device) {
        ensureRunning();
        return updates.get(device.getAddress());
    }

    /**
     * Look up the most recent status we have seen for a device from a device identifying itself
     * with the specified device number, if any.
     *
     * <em>Note:</em> If you are trying to determine the current tempo or beat being played by the device, you should
     * use {@link org.deepsymmetry.beatlink.data.TimeFinder#getLatestUpdateFor(int)} instead, because that
     * combines both status updates and beat messages, and so is more likely to be current and definitive.
     *
     * @param deviceNumber the device number of interest
     *
     * @return the matching detailed status update or null if none have been received
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public DeviceUpdate getLatestStatusFor(int deviceNumber) {
        ensureRunning();
        for (DeviceUpdate update : updates.values()) {
            if (update.getDeviceNumber() == deviceNumber) {
                return update;
            }
        }
        return null;
    }

    /**
     * Keeps track of the registered master listeners.
     */
    private final Set<MasterListener> masterListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<MasterListener, Boolean>());

    /**
     * <p>Adds the specified master listener to receive device updates when there are changes related
     * to the tempo master. If {@code listener} is {@code null} or already present in the set
     * of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * <p>To reduce latency, tempo master updates are delivered to listeners directly on the thread that is receiving them
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and master updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the master listener to add
     */
    public void addMasterListener(MasterListener listener) {
        if (listener != null) {
            masterListeners.add(listener);
        }
    }

    /**
     * Removes the specified master listener so that it no longer receives device updates when
     * there are changes related to the tempo master. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the master listener to remove
     */
    public void removeMasterListener(MasterListener listener) {
        if (listener != null) {
            masterListeners.remove(listener);
        }
    }

    /**
     * Get the set of master listeners that are currently registered.
     *
     * @return the currently registered tempo master listeners
     */
    @SuppressWarnings("WeakerAccess")
    public Set<MasterListener> getMasterListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<MasterListener>(masterListeners));
    }

    /**
     * Send a master changed announcement to all registered master listeners.
     *
     * @param update the message announcing the new tempo master
     */
    private void deliverMasterChangedAnnouncement(final DeviceUpdate update) {
        for (final MasterListener listener : getMasterListeners()) {
            try {
                listener.masterChanged(update);
            } catch (Exception e) {
                logger.warn("Problem delivering master changed announcement to listener", e);
            }
        }
    }

    /**
     * Send a tempo changed announcement to all registered master listeners.
     *
     * @param tempo the new master tempo
     */
    private void deliverTempoChangedAnnouncement(final double tempo) {
        for (final MasterListener listener : getMasterListeners()) {
            try {
                listener.tempoChanged(tempo);
            } catch (Exception e) {
                logger.warn("Problem delivering tempo changed announcement to listener", e);
            }
        }
    }

    /**
     * Send a beat announcement to all registered master listeners.
     *
     * @param beat the beat sent by the tempo master
     */
    private void deliverBeatAnnouncement(final Beat beat) {
        for (final MasterListener listener : getMasterListeners()) {
            try {
                listener.newBeat(beat);
            } catch (Exception e) {
                logger.warn("Problem delivering master beat announcement to listener", e);
            }
        }
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
    @SuppressWarnings("SameParameterValue")
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
    @SuppressWarnings("WeakerAccess")
    public Set<DeviceUpdateListener> getUpdateListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<DeviceUpdateListener>(updateListeners));
    }

    /**
     * Send a device update to all registered update listeners.
     *
     * @param update the device update that has just arrived
     */
    private void deliverDeviceUpdate(final DeviceUpdate update) {
        for (final DeviceUpdateListener listener : getUpdateListeners()) {
            try {
                listener.received(update);
            } catch (Exception e) {
                logger.warn("Problem delivering device update to listener", e);
            }
        }
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final VirtualCdj ourInstance = new VirtualCdj();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    public static VirtualCdj getInstance() {
        return ourInstance;
    }

    /**
     * Prevent instantiation.
     */
    private VirtualCdj() {
        // Nothing to do.
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("VirtualCdj[number:").append(getDeviceNumber()).append(", name:").append(getDeviceName());
        sb.append(", announceInterval:").append(getAnnounceInterval());
        sb.append(", useStandardPlayerNumber:").append(getUseStandardPlayerNumber());
        sb.append(", tempoEpsilon:").append(getTempoEpsilon()).append(", active:").append(isRunning());
        if (isRunning()) {
            sb.append(", localAddress:").append(getLocalAddress().getHostAddress());
            sb.append(", broadcastAddress:").append(getBroadcastAddress().getHostAddress());
            sb.append(", latestStatus:").append(getLatestStatus()).append(", masterTempo:").append(getMasterTempo());
            sb.append(", tempoMaster:").append(getTempoMaster());
        }
        return sb.append("]").toString();
    }
}
