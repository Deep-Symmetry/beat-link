package org.deepsymmetry.beatlink;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.data.OpusProvider;
import org.deepsymmetry.beatlink.data.TimeFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
 * source. The mixer does not make any effort to keep its notion of measures (downbeats) consistent
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
@API(status = API.Status.STABLE)
public class BeatFinder extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(BeatFinder.class);

    /**
     * The port to which devices broadcast beat messages.
     */
    @API(status = API.Status.STABLE)
    public static final int BEAT_PORT = 50001;

    /**
     * The socket used to listen for beat packets while we are active.
     */
    private final AtomicReference<DatagramSocket> socket = new AtomicReference<>(null);

    /**
     * Check whether we are presently listening for beat packets.
     *
     * @return {@code true} if our socket is open and monitoring for DJ Link beat packets on the network
     */
    @API(status = API.Status.STABLE)
    public boolean isRunning() {
        return socket.get() != null;
    }

    /**
     * Keeps track of the warnings we have already issued for packets of a given type with a given unexpected size,
     * so we only issue them once.
     */
    private static final Map<String, Set<Integer>> sizeWarningsIssued = new HashMap<>();

    /**
     * Checks whether this is the first time we have seen a particular bad size for a given packet type, and returns
     * {@code true} in that circumstance, making note that we have now seen this combination, so we will return
     * {@code false} for it in the future.
     *
     * @param packetName the type of packet to be warned about
     * @param unexpectedLength the number of bytes in this packet, which we did not expect to see
     *
     * @return true if this is the first time we have seen a packet like this
     */
    private static synchronized boolean shouldWarn(String packetName, int unexpectedLength) {
        Set<Integer> sizeSet = sizeWarningsIssued.get(packetName);
        if (sizeSet == null) { // This is the first time we have seen this packet at all.
            sizeSet = new HashSet<>();
            sizeSet.add(unexpectedLength);
            sizeWarningsIssued.put(packetName, sizeSet);
            return true;
        } else if (sizeSet.contains(unexpectedLength)) {
            return false;  // We have seen this before.
        }
        // We have seen this packet type before, but not this particular length.
        sizeSet.add(unexpectedLength);
        return true;
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
        if (length < expectedLength && shouldWarn(name, length)) {
            logger.warn("Ignoring too-short {} packet; expecting {} bytes and got {}.", name, expectedLength, length);
            return false;
        }

        if (length > expectedLength && shouldWarn(name, length)) {
            logger.warn("Processing too-long {} packet; expecting {} bytes and got {}.", name, expectedLength, length);
        }

        return true;
    }

    /**
     * Used to keep track of the last time we saw a channels-on-air packet from an XDJ-AZ, which would tell us
     * that it is operating in Pro DJ Link mode.
     */
    private final AtomicReference<Long> lastSeenXdjAzChannelsOnAir = new AtomicReference<>();

    /**
     * Check if there seems to be an XDJ-AZ on the network that is operating in Pro DJ Link mode,
     * by seeing if we have received a Channels On-Air packet from one within the past second.
     *
     * @return true if one seems to be present
     * @throws IllegalStateException if we are not running
     */
    @API(status = API.Status.EXPERIMENTAL)
    public boolean canSeeXdjAzInProDJLinkMode() {
        ensureRunning();
        final Long saw = lastSeenXdjAzChannelsOnAir.get();
        return saw != null && System.nanoTime() - saw < TimeUnit.SECONDS.toNanos(1);
    }

    /**
     * Checks if a device update packet was sent by an XDJ-AZ by looking at the device name field.
     *
     * @param packet the packet that was received
     * @return {@code true} if the device name matches XDJ-AZ
     */
    private boolean isFromXdjAz(DatagramPacket packet) {
        final byte[] data = packet.getData();
        final byte[] name = OpusProvider.XDJ_AZ_NAME.getBytes();
        final int len = name.length;
        return Arrays.equals(data, 0x0b, 0x0b + len, name, 0, len) &&
                data[0x0b + len + 1] == 0;
    }

    /**
     * Start listening for beat announcements and sync commands. If already listening, has no effect.
     *
     * @throws SocketException if the socket to listen on port 50001 cannot be created
     */
    @API(status = API.Status.STABLE)
    public synchronized void start() throws SocketException {
        if (!isRunning()) {
            socket.set(new DatagramSocket(BEAT_PORT));
            deliverLifecycleAnnouncement(logger, true);
            final byte[] buffer = new byte[512];
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            Thread receiver = new Thread(null, () -> {
                boolean received;
                while (isRunning()) {
                    try {
                        socket.get().receive(packet);
                        received = !DeviceFinder.getInstance().isAddressIgnored(packet.getAddress());
                    } catch (IOException e) {
                        // Don't log a warning if the exception was due to the socket closing at shutdown.
                        if (isRunning()) {
                            // We did not expect to have a problem; log a warning and shut down.
                            logger.warn("Problem reading from beat/sync socket, stopping", e);
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

                                    case PRECISE_POSITION:
                                        if (isPacketLongEnough(packet, 60, "precise position")) {
                                            deliverPrecisePosition(new PrecisePosition(packet));
                                        }
                                        break;

                                    case CHANNELS_ON_AIR:
                                        if (packet.getLength() == 0x35 ||  // New DJM-V10 packet with six channels
                                                isPacketLongEnough(packet, 0x2d, "channels on-air")) {
                                            final Set<Integer> audibleChannels = getAudibleChannels(packet);
                                            deliverOnAirUpdate(audibleChannels);
                                            if (isFromXdjAz(packet)) {  // Record that we saw an XDJ-AZ channels-on-air packet
                                                lastSeenXdjAzChannelsOnAir.set(System.nanoTime());
                                            }
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

                                    case FADER_START_COMMAND:
                                        if (isPacketLongEnough(packet, 0x28, "fader start command")) {
                                            byte[] data = packet.getData();
                                            Set<Integer> playersToStart = new TreeSet<>();
                                            Set<Integer> playersToStop = new TreeSet<>();
                                            for (int channel = 1; channel <= 4; channel++) {
                                                switch (data[0x23 + channel]) {

                                                    case 0:
                                                        playersToStart.add(channel);
                                                        break;

                                                    case 1:
                                                        playersToStop.add(channel);
                                                        break;

                                                    case 2:
                                                        // Leave this player alone
                                                        break;

                                                    default:
                                                        logger.warn("Ignoring unrecognized fader start command, {}, for channel {}", data[0x23 + channel], channel);
                                                }
                                            }
                                            playersToStart = Collections.unmodifiableSet(playersToStart);
                                            playersToStop = Collections.unmodifiableSet(playersToStop);
                                            deliverFaderStartCommand(playersToStart, playersToStop);
                                        }
                                        break;

                                    default:
                                        logger.warn("Ignoring packet received on beat port with unexpected type: {}", kind);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        logger.warn("Problem processing beat packet", t);
                    }
                }
            }, "beat-link BeatFinder receiver");
            receiver.setDaemon(true);
            receiver.setPriority(Thread.MAX_PRIORITY);
            receiver.start();
        }
    }

    /**
     * Finds the channel numbers that are marked as being on-air in a Channels On-Air packet.
     *
     * @param packet the packet we received
     * @return the channels it reports as being on-air
     */
    private static Set<Integer> getAudibleChannels(DatagramPacket packet) {
        byte[] data = packet.getData();
        Set<Integer> audibleChannels = new TreeSet<>();
        for (int channel = 1; channel <= 4; channel++) {
            if (data[0x23 + channel] != 0) {
                audibleChannels.add(channel);
            }
        }
        if (packet.getLength() >= 0x35) {
            for (int channel = 5; channel <= 6; channel++) {
                if (data[0x28 + channel] != 0) {
                    audibleChannels.add(channel);
                }
            }
        }
        audibleChannels = Collections.unmodifiableSet(audibleChannels);
        return audibleChannels;
    }

    /**
     * Stop listening for beats.
     */
    @API(status = API.Status.STABLE)
    public synchronized void stop() {
        if (isRunning()) {
            socket.get().close();
            socket.set(null);
            deliverLifecycleAnnouncement(logger, false);
        }
    }

    /**
     * Keeps track of the TimeFinder's beat listener when it is registered, so we can call it first.
     */
    private final AtomicReference<BeatListener> timeFinderBeatListener = new AtomicReference<>();


    /**
     * Keeps track of the registered beat listeners, except the TimeFinder's.
     */
    private final Set<BeatListener> beatListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * <p>Adds the specified beat listener to receive beat announcements when DJ Link devices broadcast
     * them on the network. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * <p>To reduce latency, beat announcements are delivered to listeners directly on the thread that is receiving them
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     * <p>
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the beat listener to add
     */
    @API(status = API.Status.STABLE)
    public void addBeatListener(BeatListener listener) {
        if (TimeFinder.getInstance().isOwnBeatListener(listener)) {
            timeFinderBeatListener.set(listener);
        } else if (listener != null) {
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
    @API(status = API.Status.STABLE)
    public void removeBeatListener(BeatListener listener) {
        if (TimeFinder.getInstance().isOwnBeatListener(listener)) {
            timeFinderBeatListener.set(null);
        } else if (listener != null) {
            beatListeners.remove(listener);
        }
    }

    /**
     * Get the set of beat listeners that are currently registered.
     *
     * @return the currently registered beat listeners
     */
    @API(status = API.Status.STABLE)
    public Set<BeatListener> getBeatListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        final Set<BeatListener> result = new HashSet<>(beatListeners);
        final BeatListener timeFinderListener = timeFinderBeatListener.get();
        if (timeFinderListener != null) {
            result.add(timeFinderListener);
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Send a beat announcement to all registered listeners, and let the {@link VirtualCdj} know about it in case it
     * needs to notify the master beat listeners.
     *
     * @param beat the message announcing the new beat
     */
    private void deliverBeat(final Beat beat) {
        VirtualCdj.getInstance().processBeat(beat);
        final BeatListener timeFinderListener = timeFinderBeatListener.get();
        if (timeFinderListener != null) {
            try {
                timeFinderListener.newBeat(beat);
            } catch (Throwable t) {
                logger.warn("Problem delivering beat announcement to TimeFinder listener", t);
            }
        }
        for (final BeatListener listener : new LinkedList<>(beatListeners)) {
            try {
                listener.newBeat(beat);
            } catch (Throwable t) {
                logger.warn("Problem delivering beat announcement to listener", t);
            }
        }
    }

    /**
     * Keeps track of the registered precise position listeners.
     */
    private final Set<PrecisePositionListener> precisePositionListeners = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * <p>Adds the specified precise position listener to receive precise position updates when DJ Link devices send
     * them to Beat Link. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * <p>To reduce latency, precise position updates are delivered to listeners directly on the thread that is receiving
     * them from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     * <p>
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and precise position updates
     * (which are sent frequently, at intervals of roughly 30 milliseconds) will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the precise position listener to add
     */
    @API(status = API.Status.STABLE)
    public void addPrecisePositionListener(PrecisePositionListener listener) {
        if (listener != null) {
            precisePositionListeners.add(listener);
        }
    }

    /**
     * Removes the specified precise position listener so that it no longer receives precise position updates when
     * DJ Link devices send them to Beat Link. If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the precise position listener to remove
     */
    @API(status = API.Status.STABLE)
    public void removePrecisePositionListener(PrecisePositionListener listener) {
        if (listener != null) {
            precisePositionListeners.remove(listener);
        }
    }

    /**
     * Get the set precise position listeners that are currently registered.
     *
     * @return the currently registered precise position listeners
     */
    @API(status = API.Status.STABLE)
    public Set<PrecisePositionListener> getPrecisePositionListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Set.copyOf(precisePositionListeners);
    }

    /**
     * Send a precise position update to all registered listeners.
     *
     * @param position the precise position update we received from a player
     */
    private void deliverPrecisePosition(PrecisePosition position) {
        for (final PrecisePositionListener listener : getPrecisePositionListeners()) {
            try {
                listener.positionReported(position);
            } catch (Throwable t) {
                logger.warn("Problem delivering precise position update to listener", t);
            }
        }
    }

    /**
     * Keeps track of the registered sync command listeners.
     */
    private final Set<SyncListener> syncListeners = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * <p>Adds the specified sync command listener to receive sync commands when DJ Link devices send
     * them to Beat Link. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * <p>To reduce latency, sync commands are delivered to listeners directly on the thread that is receiving them
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     * <p>
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the sync listener to add
     */
    @API(status = API.Status.STABLE)
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
    @API(status = API.Status.STABLE)
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
    @API(status = API.Status.STABLE)
    public Set<SyncListener> getSyncListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Set.copyOf(syncListeners);
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
            } catch (Throwable t) {
                logger.warn("Problem delivering sync command to listener", t);
            }
        }
    }

    /**
     * Keeps track of the registered master  handoff command listeners.
     */
    private final Set<MasterHandoffListener> masterHandoffListeners = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * <p>Adds the specified master handoff listener to receive tempo master handoff commands when DJ Link devices send
     * them to Beat Link. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * <p>To reduce latency, handoff commands are delivered to listeners directly on the thread that is receiving them
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     * <p>
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the tempo master handoff listener to add
     */
    @API(status = API.Status.STABLE)
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
    @API(status = API.Status.STABLE)
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
    @API(status = API.Status.STABLE)
    public Set<MasterHandoffListener> getMasterHandoffListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Set.copyOf(masterHandoffListeners);
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
            } catch (Throwable t) {
                logger.warn("Problem delivering master yield command to listener", t);
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
            } catch (Throwable t) {
                logger.warn("Problem delivering master yield response to listener", t);
            }
        }
    }

    /**
     * Keeps track of the registered on-air listeners.
     */
    private final Set<OnAirListener> onAirListeners = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * <p>Adds the specified on-air listener to receive channel on-air updates when the mixer broadcasts
     * them on the network. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * <p>To reduce latency, on-air updates are delivered to listeners directly on the thread that is receiving them
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     * <p>
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the on-air listener to add
     */
    @API(status = API.Status.STABLE)
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
    @API(status = API.Status.STABLE)
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
    @API(status = API.Status.STABLE)
    public Set<OnAirListener> getOnAirListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Set.copyOf(onAirListeners);
    }

    /**
     * Send a channels on-air update to all registered listeners.
     *
     * @param audibleChannels holds the device numbers of all channels that can currently be heard in the mixer output
     */
    private void deliverOnAirUpdate(Set<Integer> audibleChannels) {
        for (final OnAirListener listener : getOnAirListeners()) {
            try {
                listener.channelsOnAir(audibleChannels);
            } catch (Throwable t) {
                logger.warn("Problem delivering channels on-air update to listener", t);
            }
        }
    }

    /**
     * Keeps track of the registered fader start listeners.
     */
    private final Set<FaderStartListener> faderStartListeners = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * <p>Adds the specified fader start listener to receive fader start commands when the mixer broadcasts
     * them on the network. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * <p>To reduce latency, fader start commands are delivered to listeners directly on the thread that is receiving
     * them from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     * <p>
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the fader start listener to add
     */
    @API(status = API.Status.STABLE)
    public void addFaderStartListener(FaderStartListener listener) {
        if (listener != null) {
            faderStartListeners.add(listener);
        }
    }

    /**
     * Removes the specified fader start listener so that it no longer receives fader start commands when
     * the mixer broadcasts them to the network. If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the fader start listener to remove
     */
    @API(status = API.Status.STABLE)
    public void removeFaderStartListener(FaderStartListener listener) {
        if (listener != null) {
            faderStartListeners.remove(listener);
        }
    }

    /**
     * Get the set of fader start listeners that are currently registered.
     *
     * @return the currently registered fader start listeners
     */
    @API(status = API.Status.STABLE)
    public Set<FaderStartListener> getFaderStartListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Set.copyOf(faderStartListeners);
    }

    /**
     * Send a fader start command to all registered listeners.
     *
     * @param playersToStart contains the device numbers of all players that should start playing
     * @param playersToStop contains the device numbers of all players that should stop playing
     */
    private void deliverFaderStartCommand(Set<Integer> playersToStart, Set<Integer> playersToStop) {
        for (final FaderStartListener listener : getFaderStartListeners()) {
            try {
                listener.fadersChanged(playersToStart, playersToStop);
            } catch (Throwable t) {
                logger.warn("Problem delivering fader start command to listener", t);
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
    @API(status = API.Status.STABLE)
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
