package org.deepsymmetry.beatlink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Watches for devices to report new beats by broadcasting beat packets on port 50001,
 * and passes them on to registered listeners. When players are actively playing music,
 * they send beat packets at the start of each beat which, in addition to announcing the
 * start of the beat, provide information about where the beat falls within a measure of
 * music (assuming that the track was properly configured in rekordbox, and is in 4/4 time),
 * the current BPM of the track being played, and the current player pitch adjustment, from
 * which the actual effective BPM can be calculated.</p>
 *
 * <p>When players are stopped, they do not send beat packets, but the mixer continues sending them
 * at the last BPM reported by the master player, so it acts as the most reliable synchronization
 * source. The mixer does not make any effort to keep its notion of measures (down beats) consistent
 * with any player, however. So systems which want to stay in sync with measures as well as beats
 * will want to use the {@link VirtualCdj} to maintain awareness of which player is the master player.</p>
 *
 * This class also receives special sync and on-air control messages which are sent to the same port as
 * beat packets. If the {@link VirtualCdj} is sending status packets, it needs to be notified about these
 * so it can properly update its sync and on-air state if the mixer tells it to, so it will ensure the
 * {@code BeatFinder} is running whenever it is sending status updates.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class BeatFinder extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(BeatFinder.class);

    /**
     * The port to which devices broadcast beat messages.
     */
    public static final int BEAT_PORT = 50001;

    /**
     * The socket used to listen for beat packets while we are active.
     */
    private final AtomicReference<DatagramSocket> socket = new AtomicReference<DatagramSocket>(null);

    /**
     * Check whether we are presently listening for beat packets.
     *
     * @return {@code true} if our socket is open and monitoring for DJ Link beat packets on the network
     */
    public boolean isRunning() {
        return socket.get() != null;
    }

    /**
     * Helper method to check that we got the right size packet.
     *
     * @param packet a packet that has been received
     * @param expectedLength the number of bytes we expect it to contain
     * @param name the description of the packet in case we need to report issues with the length
     *
     * @return {@code true} if enough bytes were received to process the packet
     */
    private boolean isPacketLongEnough(DatagramPacket packet, int expectedLength, String name) {
        final int length = packet.getLength();
        if (length < expectedLength) {
            logger.warn("Ignoring too-short " + name + " packet; expecting " + expectedLength + " bytes and got " +
                    length + ".");
            return false;
        }

        if (length > expectedLength) {
            logger.warn("Processing too-long " + name + " packet; expecting " + expectedLength +
                    " bytes and got " + length + ".");
        }

        return true;
    }

    /**
     * Start listening for beat announcements. If already listening, has no effect.
     *
     * @throws SocketException if the socket to listen on port 50001 cannot be created
     */
    public synchronized void start() throws SocketException {
        if (!isRunning()) {
            socket.set(new DatagramSocket(BEAT_PORT));
            deliverLifecycleAnnouncement(logger, true);
            final byte[] buffer = new byte[512];
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
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
                                logger.warn("Problem reading from DeviceAnnouncement socket, stopping", e);
                                stop();
                            }
                            received = false;
                        }
                        try {
                            if (received) {
                                final Util.PacketType kind = Util.validateHeader(packet,  BEAT_PORT);
                                if (kind != null) {
                                    switch (kind) {

                                        case BEAT:
                                            if (isPacketLongEnough(packet, 96, "beat")) {
                                                deliverBeat(new Beat(packet));
                                            }
                                            break;

                                        case CHANNELS_ON_AIR:
                                            if (isPacketLongEnough(packet, 0x2d, "channels on-air")) {
                                                byte[] data = packet.getData();
                                                deliverOnAirUpdate(data[0x24] != 0, data[0x25] != 0, data[0x26] != 0, data[0x27] != 0);
                                            }
                                            break;

                                        case SYNC_CONTROL:
                                            if (isPacketLongEnough(packet, 0x2c, "sync control command")) {
                                                deliverSyncCommand(packet.getData()[0x2b]);
                                            }
                                            break;

                                        case MASTER_HANDOFF_REQUEST:
                                            if (isPacketLongEnough(packet, 0x28, "tempo master handoff request")) {
                                                deliverMasterYieldCommand(packet.getData()[0x21]);
                                            }
                                            break;

                                        case MASTER_HANDOFF_RESPONSE:
                                            if (isPacketLongEnough(packet, 0x2c, "tempo master handoff response")) {
                                                byte[] data = packet.getData();
                                                deliverMasterYieldResponse(data[0x21], data[0x2b] == 1);
                                            }
                                            break;

                                            // TODO: Handle fader start commands

                                        default:
                                            logger.warn("Ignoring packet received on beat port with unexpected type: " + kind);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("Problem processing beat packet", e);
                        }
                    }
                }
            }, "beat-link BeatFinder receiver");
            receiver.setDaemon(true);
            receiver.setPriority(Thread.MAX_PRIORITY);
            receiver.start();
        }
    }

    /**
     * Stop listening for beats.
     */
    public synchronized void stop() {
        if (isRunning()) {
            socket.get().close();
            socket.set(null);
            deliverLifecycleAnnouncement(logger, false);
        }
    }

    /**
     * Keeps track of the registered beat listeners.
     */
    private final Set<BeatListener> beatListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<BeatListener, Boolean>());

    /**
     * <p>Adds the specified beat listener to receive beat announcements when DJ Link devices broadcast
     * them on the network. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * <p>To reduce latency, beat announcements are delivered to listeners directly on the thread that is receiving them
     * them from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the beat listener to add
     */
    public void addBeatListener(BeatListener listener) {
        if (listener != null) {
            beatListeners.add(listener);
        }
    }

    /**
     * Removes the specified beat listener so that it no longer receives beat announcements when
     * DJ Link devices broadcast them to the network. If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the beat listener to remove
     */
    public void removeBeatListener(BeatListener listener) {
        if (listener != null) {
            beatListeners.remove(listener);
        }
    }

    /**
     * Get the set of beat listeners that are currently registered.
     *
     * @return the currently registered beat listeners
     */
    public Set<BeatListener> getBeatListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<BeatListener>(beatListeners));
    }

    /**
     * Send a beat announcement to all registered listeners, and let the {@link VirtualCdj} know about it in case it
     * needs to notify the master beat listeners.
     *
     * @param beat the message announcing the new beat
     */
    private void deliverBeat(final Beat beat) {
        VirtualCdj.getInstance().processBeat(beat);
        for (final BeatListener listener : getBeatListeners()) {
            try {
                listener.newBeat(beat);
            } catch (Exception e) {
                logger.warn("Problem delivering beat announcement to listener", e);
            }
        }
    }

    /**
     * Keeps track of the registered sync command listeners.
     */
    private final Set<SyncListener> syncListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<SyncListener, Boolean>());

    /**
     * <p>Adds the specified sync command listener to receive sync commands when DJ Link devices send
     * them to Beat Link. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * <p>To reduce latency, sync commands are delivered to listeners directly on the thread that is receiving them
     * them from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the sync listener to add
     */
    public void addSyncListener(SyncListener listener) {
        if (listener != null) {
            syncListeners.add(listener);
        }
    }

    /**
     * Removes the specified sync listener so that it no longer receives sync commands when
     * DJ Link devices send them to Beat Link. If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the sync listener to remove
     */
    public void removeSyncListener(SyncListener listener) {
        if (listener != null) {
            syncListeners.remove(listener);
        }
    }

    /**
     * Get the set of sync command listeners that are currently registered.
     *
     * @return the currently registered sync listeners
     */
    public Set<SyncListener> getSyncListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<SyncListener>(syncListeners));
    }

    /**
     * Send a sync command to all registered listeners.
     *
     * @param command the byte which identifies the type of sync command we received
     */
    private void deliverSyncCommand(byte command) {
        for (final SyncListener listener : getSyncListeners()) {
            try {
                switch (command) {

                    case 0x01:
                        listener.becomeMaster();

                    case 0x10:
                       listener.setSyncMode(true);
                       break;

                    case 0x20:
                        listener.setSyncMode(false);
                        break;

                }
            } catch (Exception e) {
                logger.warn("Problem delivering sync command to listener", e);
            }
        }
    }

    /**
     * Keeps track of the registered master  handoff command listeners.
     */
    private final Set<MasterHandoffListener> masterHandoffListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<MasterHandoffListener, Boolean>());

    /**
     * <p>Adds the specified master handoff listener to receive tempo master handoff commands when DJ Link devices send
     * them to Beat Link. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * <p>To reduce latency, handoff commands are delivered to listeners directly on the thread that is receiving them
     * them from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the tempo master handoff listener to add
     */
    public void addMasterHandoffListener(MasterHandoffListener listener) {
        if (listener != null) {
            masterHandoffListeners.add(listener);
        }
    }

    /**
     * Removes the specified master handoff listener so that it no longer receives tempo master handoff commands when
     * DJ Link devices send them to Beat Link. If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the tempo master handoff listener to remove
     */
    public void removeMasterHandoffListener(MasterHandoffListener listener) {
        if (listener != null) {
            masterHandoffListeners.remove(listener);
        }
    }

    /**
     * Get the set of master handoff command listeners that are currently registered.
     *
     * @return the currently registered tempo master handoff command listeners
     */
    public Set<MasterHandoffListener> getMasterHandoffListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<MasterHandoffListener>(masterHandoffListeners));
    }

    /**
     * Send a master handoff yield command to all registered listeners.
     *
     * @param toPlayer the device number to which we are being instructed to yield the tempo master role
     */
    private void deliverMasterYieldCommand(int toPlayer) {
        for (final MasterHandoffListener listener : getMasterHandoffListeners()) {
            try {
                listener.yieldMasterTo(toPlayer);
            } catch (Exception e) {
                logger.warn("Problem delivering master yield command to listener", e);
            }
        }
    }

    /**
     * Send a master handoff yield response to all registered listeners.
     *
     * @param fromPlayer the device number that is responding to our request that it yield the tempo master role to us
     * @param yielded will be {@code true} if we should now be the tempo master
     */
    private void deliverMasterYieldResponse(int fromPlayer, boolean yielded) {
        for (final MasterHandoffListener listener : getMasterHandoffListeners()) {
            try {
                listener.yieldResponse(fromPlayer, yielded);
            } catch (Exception e) {
                logger.warn("Problem delivering master yield response to listener", e);
            }
        }
    }

    /**
     * Keeps track of the registered on-air listeners.
     */
    private final Set<OnAirListener> onAirListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<OnAirListener, Boolean>());

    /**
     * <p>Adds the specified on-air listener to receive channel on-air updates when the mixer broadcasts
     * them on the network. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * <p>To reduce latency, on-air updates are delivered to listeners directly on the thread that is receiving them
     * them from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the on-air listener to add
     */
    public void addOnAirListener(OnAirListener listener) {
        if (listener != null) {
            onAirListeners.add(listener);
        }
    }

    /**
     * Removes the specified on-air listener so that it no longer receives channel on-air updates when
     * the mixer broadcasts them to the network. If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the on-air listener to remove
     */
    public void removeOnAirListener(OnAirListener listener) {
        if (listener != null) {
            onAirListeners.remove(listener);
        }
    }

    /**
     * Get the set of on-air listeners that are currently registered.
     *
     * @return the currently registered on-air listeners
     */
    public Set<OnAirListener> getOnAirListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<OnAirListener>(onAirListeners));
    }

    /**
     * Send a channels on-air update to all registered listeners.
     *
     * @param channel1 will be true when channel 1 is on the air
     * @param channel2 will be true when channel 2 is on the air
     * @param channel3 will be true when channel 3 is on the air
     * @param channel4 will be true when channel 4 is on the air
     */
    private void deliverOnAirUpdate(boolean channel1, boolean channel2, boolean channel3, boolean channel4) {
        for (final OnAirListener listener : getOnAirListeners()) {
            try {
                listener.channelsOnAir(channel1, channel2, channel3, channel4);
            } catch (Exception e) {
                logger.warn("Problem delivering channels on-air update to listener", e);
            }
        }
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final BeatFinder ourInstance = new BeatFinder();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    public static BeatFinder getInstance() {
        return ourInstance;
    }

    /**
     * Prevent direct instantiation.
     */
    private BeatFinder() {
        // Nothing to do.
    }

    @Override
    public String toString() {
        return "BeatFinder[active:" + isRunning() + "]";
    }
}
