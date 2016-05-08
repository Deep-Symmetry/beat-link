package org.deepsymmetry.beatlink;

import java.awt.EventQueue;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
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
     * Keep track of the most recent updates we have seen, indexed by the address they came from.
     */
    private static final Map<InetAddress, DeviceUpdate> updates = new HashMap<InetAddress, DeviceUpdate>();

    /**
     * The device number we use in posting presence announcements on the network.
     */
    private static int deviceNumber = 5;

    /**
     * Get the device number that is used when sending presence announcements on the network to pose as a virtual CDJ.
     *
     * @return the virtual player number
     */
    public static synchronized int getDeviceNumber() {
        return deviceNumber;
    }

    /**
     * Set the device number to be used when sending presence announcements on the network to pose as a virtual CDJ.
     *
     * @param number the virtual player number
     */
    public static synchronized void setDeviceNumber(int number) {
        deviceNumber = number;
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
     * Keep track of which device has reported itself as the current tempo master.
     */
    private static DeviceUpdate tempoMaster;

    /**
     * Check which device is the current tempo master, returning the {@link DeviceUpdate} packet in which it
     * reported itself to be master. If there is no current tempo master or the Virtual CDJ is not active
     * returns {@code null}.
     *
     * @return the most recent update from a device which reported itself as the master
     */
    public static synchronized DeviceUpdate getTempoMaster() {
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
     * Get the current master tempo. If the Virtual CDJ is not active returns zero.
     *
     * @return the most recently reported master tempo
     */
    public static synchronized double getMasterTempo() {
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
     * and notify any registered listeners if it results in changes to tracked state, such as the current master
     * player and tempo.
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
    }

    /**
     * Process a beat packet, potentially updating the master tempo and sending our listeners a master
     * beat notification.
     */
    static synchronized void processBeat(Beat beat) {
        if (beat.isTempoMaster()) {
            setMasterTempo(beat.getEffectiveTempo());
            deliverBeatAnnouncement(beat);
        }
    }

    /**
     * Start announcing ourselves and listening for status packets. If already active, has no effect.
     *
     * @throws SocketException if the socket to listen on port 50002 cannot be created
     */
    public static synchronized void start() throws SocketException {
        if (!isActive()) {
            // TODO look for other devices and determine the network, address, and MAC address we should use, or fail

            socket = new DatagramSocket(UPDATE_PORT);

            final byte[] buffer = new byte[512];
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            // Create the update reception thread
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
                                    processUpdate(update);
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

        // Create the thread which announces our participation in the DJ Link network, to request update packets
        Thread announcer = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isActive()) {
                    sendAnnouncement();
                }
            }
        });
        announcer.setDaemon(true);
        announcer.start();
    }

    /**
     * Stop announcing ourselves and listening for status updates.
     */
    public static synchronized void stop() {
        if (isActive()) {
            socket.close();
            socket = null;
            updates.clear();
            setMasterTempo(0.0);
            setTempoMaster(null);
        }
    }

    /**
     * Send an announcement packet so the other devices see us as being part of the DJ Link network and send us
     * updates.
     */
    private static void sendAnnouncement() {
        try {
            // TODO implement!
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
     */
    public static DeviceUpdate getLatestStatusFor(DeviceUpdate device) {
        return updates.get(device.getAddress());
    }

    /**
     * Look up the most recent status we have seen for a device, given its device announcement packet as returned
     * by {@link DeviceFinder#currentDevices()}.
     *
     * @param device the announcement identifying the device for which current status information is desired
     *
     * @return the most recent detailed status update received for that device
     */
    public static DeviceUpdate getLatestStatusFor(DeviceAnnouncement device) {
        return updates.get(device.getAddress());
    }

    /**
     * Keeps track of the registered device announcement listeners.
     */
    private static final Set<MasterListener> masterListeners = new HashSet<MasterListener>();

    /**
     * Adds the specified master listener to receive device updates when there are changes related
     * to the tempo master. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the device announcement listener to add
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
     * @param listener the device announcement listener to remove
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

    // TODO add support for listeners for all device updates

    /**
     * Prevent instantiation.
     */
    private VirtualCdj() {
        // Nothing to do.
    }
}
