package org.deepsymmetry.beatlink;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.deepsymmetry.beatlink.data.MetadataFinder;
import org.deepsymmetry.beatlink.data.SlotReference;
import org.deepsymmetry.electro.Metronome;
import org.deepsymmetry.electro.Snapshot;
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
    public static final int MAC_ADDRESS_OFFSET = 38;

    /**
     * The socket used to receive device status packets while we are active.
     */
    private final AtomicReference<DatagramSocket> socket = new AtomicReference<DatagramSocket>();

    /**
     * Indicates we were started with VirtualRekordbox running so we are just acting as a proxy for it, to
     * work with the Opus Quad.
     */
    private final AtomicBoolean proxyingForVirtualRekordbox = new AtomicBoolean(false);

    /**
     * Check whether we are simply proxying information from VirtualRekordbox so that we can work with the Opus
     * Quad rather than real Pro DJ Link hardware.
     *
     * @return an indication that we are in a limited mode to support the Opus Quad.
     */
    public boolean inOpusQuadCompatibilityMode() {
        return proxyingForVirtualRekordbox.get();
    }

    /**
     * Check whether we are presently posing as a virtual CDJ and receiving device status updates.
     *
     * @return true if our socket is open, sending presence announcements, and receiving status packets,
     *         or if we were started in a mode where we delegate most of our responsibility to VirtualRekordbox
     */
    public boolean isRunning() {
        return inOpusQuadCompatibilityMode() || (socket.get() != null && claimingNumber.get() == 0);
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
     * The broadcast address on which we can reach the DJ Link devices. Determined when we start
     * up by finding the network interface address on which we are receiving the other devices'
     * announcement broadcasts.
     */
    private final AtomicReference<InetAddress> broadcastAddress = new AtomicReference<InetAddress>();

    /**
     * Return the broadcast address used to reach the DJ Link network.
     *
     * @return the address on which packets can be broadcast to the other DJ Link devices
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public InetAddress getBroadcastAddress() {
        ensureRunning();
        return broadcastAddress.get();
    }

    /**
     * Keep track of the most recent updates we have seen, indexed by the address they came from.
     */
    private final Map<DeviceReference, DeviceUpdate> updates = new ConcurrentHashMap<DeviceReference, DeviceUpdate>();

    /**
     * Should we try to use a device number in the range 1 to 4 if we find one is available?
     */
    private final AtomicBoolean useStandardPlayerNumber = new AtomicBoolean(false);

    /**
     * When self-assigning a player number, should we try to use a value that is legal for a standard CDJ, in
     * the range 1 to 4? By default, we do not, to avoid any potential conflict with real players. However, if
     * the user is intending to use features (like becoming tempo master) which require Beat Link to operate with
     * a device number in the standard range, and will always have fewer than four real players
     * on the network, this can be set to {@code true}, and a device number in this range will be chosen if it
     * is not in use on the network during startup.
     *
     * @param attempt true if self-assignment should try to use device numbers below 5 when available
     */
    public void setUseStandardPlayerNumber(boolean attempt) {
        useStandardPlayerNumber.set(attempt);
    }

    /**
     * When self-assigning a player number, should we try to use a value that is legal for a standard CDJ, in
     * the range 1 to 4? By default, we do not, to avoid any potential conflict with real players. However, if
     * the user is intending to use features (like becoming tempo master) which require Beat Link to operate with
     * a device number in the standard range, and will always have fewer than four real players
     * on the network, this can be set to {@code true}, and a device number in this range will be chosen if it
     * is not in use on the network during startup.
     *
     * @return true if self-assignment should try to use device numbers below 5 when available
     */
    public boolean getUseStandardPlayerNumber() {
        return useStandardPlayerNumber.get();
    }

    /**
     * Get the device number that is used when sending presence announcements on the network to pose as a virtual CDJ.
     * This starts out being zero unless you explicitly assign another value, which means that the <code>VirtualCdj</code>
     * should assign itself an unused device number by watching the network when you call
     * {@link #start()}. If {@link #getUseStandardPlayerNumber()} returns {@code true}, self-assignment will try to
     * find a value in the range 1 to 4. Otherwise it will try to find a value in the range 7 to 15 (so that it does
     * not even conflict with newer CDJ-3000 networks, which can use channels 5 and 6). Even when told
     * to use a standard player number, if all device numbers in that range are already in use, we will be forced to
     * use a larger number.
     *
     * @return the virtual player number
     */
    public synchronized byte getDeviceNumber() {
        return keepAliveBytes[DEVICE_NUMBER_OFFSET];
    }

    /**
     * <p>Set the device number to be used when sending presence announcements on the network to pose as a virtual CDJ.
     * Used during the startup process; cannot be set while running. If set to zero, will attempt to claim any free
     * device number, otherwise will try to claim the number specified. If the mixer tells us that we are plugged
     * into a channel-specific Ethernet port, we will honor that and use the device number specified by the mixer.</p>
     *
     * <p>If {@link #getUseStandardPlayerNumber()}
     * returns {@code true}, self-assignment will try to find a value in the range 1 to 4. Otherwise (or if those
     * values are all used by other players), it will try to find a value in the range 7 to 15 (so that it does
     * not even conflict with newer CDJ-3000 networks, which can use channels 5 and 6).</p>
     *
     * <p>The device number defaults to 0, enabling self-assignment, and will be reset to that each time the
     * {@code VirtualCdj} is stopped.</p>
     *
     * @param number the virtual player number
     * @throws IllegalStateException if we are currently running
     */
    @SuppressWarnings("WeakerAccess")
    public synchronized void setDeviceNumber(byte number) {
        if (isRunning()) {
            throw new IllegalStateException("Can't change device number once started.");
        }
        keepAliveBytes[DEVICE_NUMBER_OFFSET] = number;
    }

    /**
     * The interval, in milliseconds, at which we post presence announcements on the network.
     */
    private final AtomicInteger announceInterval = new AtomicInteger(1500);

    /**
     * Get the interval, in milliseconds, at which we broadcast presence announcements on the network to pose as
     * a virtual CDJ.
     *
     * @return the announcement interval
     */
    public int getAnnounceInterval() {
        return announceInterval.get();
    }

    /**
     * Set the interval, in milliseconds, at which we broadcast presence announcements on the network to pose as
     * a virtual CDJ.
     *
     * @param interval the announcement interval
     * @throws IllegalArgumentException if interval is not between 200 and 2000
     */
    public void setAnnounceInterval(int interval) {
        if (interval < 200 || interval > 2000) {
            throw new IllegalArgumentException("Interval must be between 200 and 2000");
        }
        announceInterval.set(interval);
    }

    /**
     * Used to construct the keep-alive packet we broadcast in order to participate in the DJ Link network.
     * Some of these bytes are fixed, some get replaced by things like our device name and number, MAC address,
     * and IP address, as described in the
     * <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/startup.html#cdj-keep-alive">Packet Analysis document</a>.
     */
    private static final byte[] keepAliveBytes = {
            0x51, 0x73, 0x70, 0x74,  0x31, 0x57, 0x6d, 0x4a,   0x4f, 0x4c, 0x06, 0x00,  0x62, 0x65, 0x61, 0x74,
            0x2d, 0x6c, 0x69, 0x6e,  0x6b, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x01, 0x02, 0x00, 0x36,  0x00, 0x01, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x02, 0x00, 0x00, 0x00,  0x01, 0x64
    };

    /**
     * The location of the device name in the announcement packet.
     */
    public static final int DEVICE_NAME_OFFSET = 0x0c;

    /**
     * The length of the device name in the announcement packet.
     */
    public static final int DEVICE_NAME_LENGTH = 0x14;

    /**
     * The location of the device number in the announcement packet.
     */
    public static final int DEVICE_NUMBER_OFFSET = 0x24;

    /**
     * Get the name to be used in announcing our presence on the network.
     *
     * @return the device name reported in our presence announcement packets
     */
    public static String getDeviceName() {
        return new String(keepAliveBytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH).trim();
    }

    /**
     * Set the name to be used in announcing our presence on the network. The name can be no longer than twenty
     * bytes, and should be normal ASCII, no Unicode.
     *
     * @param name the device name to report in our presence announcement packets.
     */
    public synchronized void setDeviceName(String name) {
        if (name.getBytes().length > DEVICE_NAME_LENGTH) {
            throw new IllegalArgumentException("name cannot be more than " + DEVICE_NAME_LENGTH + " bytes long");
        }
        Arrays.fill(keepAliveBytes, DEVICE_NAME_OFFSET, DEVICE_NAME_OFFSET + DEVICE_NAME_LENGTH, (byte)0);
        System.arraycopy(name.getBytes(), 0, keepAliveBytes, DEVICE_NAME_OFFSET, name.getBytes().length);
    }

    /**
     * Reacts to player status updates to reflect the current playback state.
     */
    private final DeviceUpdateListener updateListener = new DeviceUpdateListener() {
        @Override
        public void received(DeviceUpdate update) {
            processUpdate(update);
        }
    };

    public DeviceUpdateListener getUpdateListener() {
        return updateListener;
    }


    /**
     * Reacts to player status updates to reflect the current playback state.
     */
    private final MasterListener masterListener = new MasterListener() {
        @Override
        public void masterChanged(DeviceUpdate update) {
            deliverMasterChangedAnnouncement(update);
        }

        @Override
        public void tempoChanged(double tempo) {
            deliverTempoChangedAnnouncement(tempo);
        }

        @Override
        public void newBeat(Beat beat) {
            deliverBeatAnnouncement(beat);
        }
    };

    public MasterListener getMasterListener() {
        return masterListener;
    }

    /**
     * The initial packet sent three times when coming online.
     */
    private static final byte[] helloBytes = {
            0x51, 0x73, 0x70, 0x74,  0x31, 0x57, 0x6d, 0x4a,   0x4f, 0x4c, 0x0a, 0x00,  0x62, 0x65, 0x61, 0x74,
            0x2d, 0x6c, 0x69, 0x6e,  0x6b, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x01, 0x04, 0x00, 0x26,  0x01, 0x40
    };

    /**
     * The first-stage device number claim packet series.
     */
    private static final byte[] claimStage1bytes = {
            0x51, 0x73, 0x70, 0x74,  0x31, 0x57, 0x6d, 0x4a,   0x4f, 0x4c, 0x00, 0x00,  0x62, 0x65, 0x61, 0x74,
            0x2d, 0x6c, 0x69, 0x6e,  0x6b, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x01, 0x03, 0x00, 0x2c,  0x0d, 0x01, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00
    };

    /**
     * The second-stage device number claim packet series.
     */
    private static final byte[] claimStage2bytes = {
            0x51, 0x73, 0x70, 0x74,  0x31, 0x57, 0x6d, 0x4a,   0x4f, 0x4c, 0x02, 0x00,  0x62, 0x65, 0x61, 0x74,
            0x2d, 0x6c, 0x69, 0x6e,  0x6b, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x01, 0x03, 0x00, 0x32,  0x00, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x0d, 0x00,
            0x01, 0x00
    };

    /**
     * The third-stage (final) device number claim packet series.
     */
    private static final byte[] claimStage3bytes = {
            0x51, 0x73, 0x70, 0x74,  0x31, 0x57, 0x6d, 0x4a,   0x4f, 0x4c, 0x04, 0x00,  0x62, 0x65, 0x61, 0x74,
            0x2d, 0x6c, 0x69, 0x6e,  0x6b, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x01, 0x03, 0x00, 0x26,  0x0d, 0x00
    };

    /**
     * Packet used to acknowledge a mixer's intention to assign us a device number.
     */
    private static final byte[] assignmentRequestBytes = {
            0x51, 0x73, 0x70, 0x74,  0x31, 0x57, 0x6d, 0x4a,   0x4f, 0x4c, 0x02, 0x01,  0x62, 0x65, 0x61, 0x74,
            0x2d, 0x6c, 0x69, 0x6e,  0x6b, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x01, 0x02, 0x00, 0x32,  0x00, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x01, 0x00
    };

    /**
     * Packet used to tell another device we are already using a device number.
     */
    private static final byte[] deviceNumberDefenseBytes = {
            0x51, 0x73, 0x70, 0x74,  0x31, 0x57, 0x6d, 0x4a,   0x4f, 0x4c, 0x08, 0x00,  0x62, 0x65, 0x61, 0x74,
            0x2d, 0x6c, 0x69, 0x6e,  0x6b, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x01, 0x02, 0x00, 0x29,  0x00, 0x00, 0x00, 0x00,   0x00
    };

    /**
     * Keep track of which device has reported itself as the current tempo master.
     */
    private final AtomicReference<DeviceUpdate> tempoMaster = new AtomicReference<DeviceUpdate>();

    /**
     * Check which device is the current tempo master, returning the {@link DeviceUpdate} packet in which it
     * reported itself to be master. If there is no current tempo master returns {@code null}. Note that when
     * we are acting as tempo master ourselves in order to control player tempo and beat alignment, this will
     * also have a {@code null} value, as there is no real player that is acting as master; we will instead
     * send tempo and beat updates ourselves.
     *
     * @return the most recent update from a device which reported itself as the master
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public DeviceUpdate getTempoMaster() {
        ensureRunning();
        return tempoMaster.get();
    }

    /**
     * Establish a new tempo master, and if it is a change from the existing one, report it to the listeners.
     *
     * @param newMaster the packet which caused the change of masters, or {@code null} if there is now no master.
     */
    private void setTempoMaster(DeviceUpdate newMaster) {
        DeviceUpdate oldMaster = tempoMaster.getAndSet(newMaster);
        if ((newMaster == null && oldMaster != null) ||
                (newMaster != null && ((oldMaster == null) || !newMaster.getAddress().equals(oldMaster.getAddress()) ||
                        newMaster.getDeviceNumber() != oldMaster.getDeviceNumber()))) {
            // This is a change in master, so report it to any registered listeners
            deliverMasterChangedAnnouncement(newMaster);
        }
    }

    /**
     * How large a tempo change is required before we consider it to be a real difference.
     */
    private final AtomicLong tempoEpsilon = new AtomicLong(Double.doubleToLongBits(0.0001));

    /**
     * Find out how large a tempo change is required before we consider it to be a real difference.
     *
     * @return the BPM fraction that will trigger a tempo change update
     */
    public double getTempoEpsilon() {
        return Double.longBitsToDouble(tempoEpsilon.get());
    }

    /**
     * Set how large a tempo change is required before we consider it to be a real difference.
     *
     * @param epsilon the BPM fraction that will trigger a tempo change update
     */
    public void setTempoEpsilon(double epsilon) {
        tempoEpsilon.set(Double.doubleToLongBits(epsilon));
    }

    /**
     * Track the most recently reported master tempo.
     */
    private final AtomicLong masterTempo = new AtomicLong();

    /**
     * Get the current master tempo.
     *
     * @return the most recently reported master tempo
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public double getMasterTempo() {
        ensureRunning();
        return Double.longBitsToDouble(masterTempo.get());
    }

    /**
     * Establish a new master tempo, and if it is a change from the existing one, report it to the listeners.
     *
     * @param newTempo the newly reported master tempo.
     */
    private void setMasterTempo(double newTempo) {
        double oldTempo = Double.longBitsToDouble(masterTempo.getAndSet(Double.doubleToLongBits(newTempo)));
        if ((getTempoMaster() != null) && (Math.abs(newTempo - oldTempo) > getTempoEpsilon())) {
            // This is a change in tempo, so report it to any registered listeners, and update our metronome if we are synced.
            if (isSynced()) {
                metronome.setTempo(newTempo);
                notifyBeatSenderOfChange();
            }
            deliverTempoChangedAnnouncement(newTempo);
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
                deliverMediaDetailsUpdate(new MediaDetails(packet));
                return null;

            default:
                logger.warn("Ignoring " + kind.name + " packet sent to update port.");
                return null;
        }
    }



    /**
     * <p>Process a device update once it has been received. Track it as the most recent update from its address,
     * and notify any registered listeners, including master listeners if it results in changes to tracked state,
     * such as the current master player and tempo. Also handles the Baroque dance of handing off the tempo master
     * role from or to another device.</p>
     *
     * <p>This used to be a private method, but it was made package accessible as part of the effort to support
     * Opus Quad hardware, so the VirtualRekordbox could proxy the status packets it receives through us.</p>
     */
    void processUpdate(DeviceUpdate update) {
        updates.put(DeviceReference.getDeviceReference(update), update);

        // Keep track of the largest sync number we see.
        if (update instanceof CdjStatus) {
            int syncNumber = ((CdjStatus)update).getSyncNumber();
            if (syncNumber > this.largestSyncCounter.get()) {
                this.largestSyncCounter.set(syncNumber);
            }
        }

        // Deal with the tempo master complexities, including handoff to/from us.
        if (update.isTempoMaster()) {
            final Integer packetYieldingTo = update.getDeviceMasterIsBeingYieldedTo();
            if (packetYieldingTo == null) {
                // This is a normal, non-yielding master packet. Update our notion of the current master, and,
                // if we were yielding, finish that process, updating our sync number appropriately.
                if (master.get()) {
                    if (nextMaster.get() == update.deviceNumber) {
                        syncCounter.set(largestSyncCounter.get() + 1);
                    } else {
                        if (nextMaster.get() == 0xff) {
                            logger.warn("Saw master asserted by player " + update.deviceNumber +
                                    " when we were not yielding it.");
                        } else {
                            logger.warn("Expected to yield master role to player " + nextMaster.get() +
                                    " but saw master asserted by player " + update.deviceNumber);
                        }
                    }
                }
                master.set(false);
                nextMaster.set(0xff);
                setTempoMaster(update);
                if (update.getBpm() != 0xffff) {  // Ignore invalid tempo, i.e. when master has no track loaded.
                    setMasterTempo(update.getEffectiveTempo());
                }
            } else {
                // This is a yielding master packet. If it is us that is being yielded to, take over master.
                // Log a message if it was unsolicited, and a warning if it's coming from a different player than
                // we asked.
                if (packetYieldingTo == getDeviceNumber()) {
                    if (update.deviceNumber != masterYieldedFrom.get()) {
                        if (masterYieldedFrom.get() == 0) {
                            logger.info("Accepting unsolicited Master yield; we must be the only synced device playing.");
                        } else {
                            logger.warn("Expected player " + masterYieldedFrom.get() + " to yield master to us, but player " +
                                    update.deviceNumber + " did.");
                        }
                    }
                    master.set(true);
                    masterYieldedFrom.set(0);
                    setTempoMaster(null);
                    setMasterTempo(getTempo());
                }
            }
        } else {
            // This update was not acting as a tempo master; if we thought it should be, update our records.
            DeviceUpdate oldMaster = getTempoMaster();
            if (oldMaster != null && oldMaster.getAddress().equals(update.getAddress()) &&
                    oldMaster.getDeviceNumber() == update.getDeviceNumber()) {
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
    void processBeat(Beat beat) {
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
     * The number of milliseconds for which the {@link DeviceFinder} needs to have been watching the network in order
     * for us to be confident we can choose a device number that will not conflict.
     */
    private static final long SELF_ASSIGNMENT_WATCH_PERIOD = 4000;

    /**
     * <p>Try to choose a device number, which we have not seen on the network. If we have already tried one, it must
     * have been defended, so increment to the next one we can try (we stop at 15). If we have not yet tried one,
     * pick the first appropriate one to try, honoring the value of {@link #useStandardPlayerNumber} to determine
     * if we start at 1 or 7. Set the number we are going to try next in {@link #claimingNumber}.</p>
     *
     * <p>Even though in theory we should be able to rely on the protocol to tell us if we are claiming a
     * number that belongs to another player, it turns out the XDJ-XZ is buggy and tells us to go ahead and
     * use whatever we were claiming, and further fails to defend the device numbers that it is using. So
     * we still need to make sure the {@link DeviceFinder} has been running long enough to see all devices
     * so we can avoid trying to claim a number that some other device is already using.</p>
     *
     * @return true if there was a number available for us to try claiming
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

        if (claimingNumber.get() == 0) {
            // We have not yet tried a number. If we are not supposed to use standard player numbers, make sure
            // the first one we try is 7 (to accommodate the CDJ-3000, which can use channels 5 and 6.
            if (!getUseStandardPlayerNumber()) {
                claimingNumber.set(6);
            }
        }

        // Record what numbers we have already seen, since there is no point trying one of them.
        Set<Integer> numbersUsed = new HashSet<Integer>();
        for (DeviceAnnouncement device : DeviceFinder.getInstance().getCurrentDevices()) {
            numbersUsed.add(device.getDeviceNumber());
        }

        // Try next available player number less than mixers use.
        final int startingNumber = claimingNumber.get() + 1;
        for (int result = startingNumber; result < 16; result++) {
            if (!numbersUsed.contains(result)) {  // We found one that is not used, so we can use it
                claimingNumber.set(result);
                if (getUseStandardPlayerNumber() && (result > 4)) {
                    logger.warn("Unable to self-assign a standard player number, all are in use. Trying number " +
                            result + ".");
                }
                return true;
            }
        }
        logger.warn("Found no unused device numbers between " + startingNumber + " and 15, giving up.");
        return false;
    }

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
     * If we are in the process of trying to establish a device number, this will hold the number we are
     * currently trying to claim. Otherwise it will hold the value 0.
     */
    private final AtomicInteger claimingNumber = new AtomicInteger(0);

    /**
     * If another player defends the number we tried to claim, this value will get set to true, and we will either
     * have to try another number, or fail to start up, as appropriate.
     */
    private final AtomicBoolean claimRejected = new AtomicBoolean(false);

    /**
     * If a mixer has assigned us a device number, this will be that non-zero value.
     */
    private final AtomicInteger mixerAssigned = new AtomicInteger(0);

    /**
     * Implement the process of requesting a device number from a mixer that has told us it is responsible for
     * assigning it to us as described in the
     * <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/startup.html#cdj-startup">protocol analysis</a>.
     *
     * @param mixerAddress the address from which we received a mixer device number assignment offer
     */
    private void requestNumberFromMixer(InetAddress mixerAddress) {
        final DatagramSocket currentSocket = socket.get();
        if (currentSocket == null) {
            logger.warn("Gave up before sending device number request to mixer.");
            return;  // We've already given up.
        }

        // Send a packet directly to the mixer telling it we are ready for its device assignment.
        Arrays.fill(assignmentRequestBytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH, (byte)0);
        System.arraycopy(getDeviceName().getBytes(), 0, assignmentRequestBytes, DEVICE_NAME_OFFSET, getDeviceName().getBytes().length);
        System.arraycopy(matchedAddress.getAddress().getAddress(), 0, assignmentRequestBytes, 0x24, 4);
        System.arraycopy(keepAliveBytes, MAC_ADDRESS_OFFSET, assignmentRequestBytes, 0x28, 6);
        // Can't call getDeviceNumber() on next line because that's synchronized!
        assignmentRequestBytes[0x31] = (keepAliveBytes[DEVICE_NUMBER_OFFSET] == 0)? (byte)1 : (byte)2;  // The auto-assign flag.
        assignmentRequestBytes[0x2f] = 1;  // The packet counter.
        try {
            DatagramPacket announcement = new DatagramPacket(assignmentRequestBytes, assignmentRequestBytes.length,
                    mixerAddress, DeviceFinder.ANNOUNCEMENT_PORT);
            logger.debug("Sending device number request to mixer at address " + announcement.getAddress().getHostAddress() +
                    ", port " + announcement.getPort());
            currentSocket.send(announcement);
        } catch (Exception e) {
            logger.warn("Unable to send device number request to mixer.", e);
        }
    }

    /**
     * Tell a device that is trying to use the same number that we have ownership of it.
     *
     * @param invaderAddress the address from which a rogue device claim or announcement was received for the same
     *                       device number we are using
     */
    void defendDeviceNumber(InetAddress invaderAddress) {
        final DatagramSocket currentSocket = socket.get();
        if (currentSocket == null) {
            logger.warn("Went offline before we could defend our device number.");
            return;
        }

        // Send a packet to the interloper telling it that we are using that device number.
        Arrays.fill(deviceNumberDefenseBytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH, (byte)0);
        System.arraycopy(getDeviceName().getBytes(), 0, deviceNumberDefenseBytes, DEVICE_NAME_OFFSET, getDeviceName().getBytes().length);
        deviceNumberDefenseBytes[0x24] = keepAliveBytes[DEVICE_NUMBER_OFFSET];
        System.arraycopy(matchedAddress.getAddress().getAddress(), 0, deviceNumberDefenseBytes, 0x25, 4);
        try {
            DatagramPacket defense = new DatagramPacket(deviceNumberDefenseBytes, deviceNumberDefenseBytes.length,
                    invaderAddress, DeviceFinder.ANNOUNCEMENT_PORT);
            logger.info("Sending device number defense packet to invader at address " + defense.getAddress().getHostAddress() +
                    ", port " + defense.getPort());
            currentSocket.send(defense);
        } catch (Exception e) {
            logger.error("Unable to send device defense packet.", e);
        }
    }

    /**
     * Implement the device-number claim protocol described in the
     * <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/startup.html#cdj-startup">protocol analysis</a>.
     *
     * @return true iff a device number was successfully established and startup can proceed
     */
    private boolean claimDeviceNumber() {
        // Set up our state trackers for device assignment negotiation.
        claimRejected.set(false);
        mixerAssigned.set(0);

        // Send the initial series of three "coming online" packets.
        Arrays.fill(helloBytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH, (byte)0);
        System.arraycopy(getDeviceName().getBytes(), 0, helloBytes, DEVICE_NAME_OFFSET, getDeviceName().getBytes().length);
        for (int i = 1; i <= 3; i++) {
            try {
                logger.debug("Sending hello packet " + i);
                DatagramPacket announcement = new DatagramPacket(helloBytes, helloBytes.length,
                        broadcastAddress.get(), DeviceFinder.ANNOUNCEMENT_PORT);
                socket.get().send(announcement);
                Thread.sleep(300);
            } catch (Exception e) {
                logger.warn("Unable to send hello packet to network, failing to go online.", e);
                return false;
            }
        }

        // Establish the device number we want to claim; if zero that means we will try to self-assign.
        claimingNumber.set(getDeviceNumber());
        boolean claimed = false;  // Indicates we have successfully claimed a number and can be done.

        selfAssignLoop:
        while (!claimed) {
            // If we are supposed to self-assign a number, find the next one we can try.
            if (getDeviceNumber() == 0 && !selfAssignDeviceNumber()) {
                // There are no addresses left for us to try, give up and report failure.
                claimingNumber.set(0);
                return false;
            }

            // Send the series of three initial device number claim packets, unless we are interrupted by a defense
            // or a mixer assigning us a specific number.
            Arrays.fill(claimStage1bytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH, (byte)0);
            System.arraycopy(getDeviceName().getBytes(), 0, claimStage1bytes, DEVICE_NAME_OFFSET, getDeviceName().getBytes().length);
            System.arraycopy(keepAliveBytes, MAC_ADDRESS_OFFSET, claimStage1bytes, 0x26, 6);
            for (int i = 1; i <= 3 && mixerAssigned.get() == 0; i++) {
                claimStage1bytes[0x24] = (byte)i;  // The packet counter.
                try {
                    logger.debug("Sending claim stage 1 packet " + i);
                    DatagramPacket announcement = new DatagramPacket(claimStage1bytes, claimStage1bytes.length,
                            broadcastAddress.get(), DeviceFinder.ANNOUNCEMENT_PORT);
                    socket.get().send(announcement);
                    //noinspection BusyWait
                    Thread.sleep(300);
                } catch (Exception e) {
                    logger.warn("Unable to send device number claim stage 1 packet to network, failing to go online.", e);
                    claimingNumber.set(0);
                    return false;
                }
                if (claimRejected.get()) {  // Some other player is defending the number we tried to claim.
                    if (getDeviceNumber() == 0) {  // We are trying to pick a number.
                        continue selfAssignLoop;  // Try the next available number, if any.
                    }
                    logger.warn("Unable to use device number " + getDeviceNumber() + ", another device has it. Failing to go online.");
                    claimingNumber.set(0);
                    return false;
                }
            }

            // Send the middle series of device claim packets, unless we are interrupted by a defense
            // or a mixer assigning us a specific number.
            Arrays.fill(claimStage2bytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH, (byte)0);
            System.arraycopy(getDeviceName().getBytes(), 0, claimStage2bytes, DEVICE_NAME_OFFSET, getDeviceName().getBytes().length);
            System.arraycopy(matchedAddress.getAddress().getAddress(), 0, claimStage2bytes, 0x24, 4);
            System.arraycopy(keepAliveBytes, MAC_ADDRESS_OFFSET, claimStage2bytes, 0x28, 6);
            claimStage2bytes[0x2e] = (byte)claimingNumber.get();  // The number we are claiming.
            claimStage2bytes[0x31] = (getDeviceNumber() == 0)? (byte)1 : (byte)2;  // The auto-assign flag.
            for (int i = 1; i <= 3 && mixerAssigned.get() == 0; i++) {
                claimStage2bytes[0x2f] = (byte)i;  // The packet counter.
                try {
                    logger.debug("Sending claim stage 2 packet " + i + " for device " + claimStage2bytes[0x2e]);
                    DatagramPacket announcement = new DatagramPacket(claimStage2bytes, claimStage2bytes.length,
                            broadcastAddress.get(), DeviceFinder.ANNOUNCEMENT_PORT);
                    socket.get().send(announcement);
                    //noinspection BusyWait
                    Thread.sleep(300);
                } catch (Exception e) {
                    logger.warn("Unable to send device number claim stage 2 packet to network, failing to go online.", e);
                    claimingNumber.set(0);
                    return false;
                }
                if (claimRejected.get()) {  // Some other player is defending the number we tried to claim.
                    if (getDeviceNumber() == 0) {  // We are trying to pick a number.
                        continue selfAssignLoop;  // Try the next available number, if any.
                    }
                    logger.warn("Unable to use device number " + getDeviceNumber() + ", another device has it. Failing to go online.");
                    claimingNumber.set(0);
                    return false;
                }
            }

            // If the mixer assigned us a number, use it.
            final int assigned = mixerAssigned.getAndSet(0);
            if (assigned > 0) {
                claimingNumber.set(assigned);
            }

            // Send the final series of device claim packets, unless we are interrupted by a defense, or the mixer
            // acknowledges our acceptance of its assignment.
            Arrays.fill(claimStage3bytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH, (byte)0);
            System.arraycopy(getDeviceName().getBytes(), 0, claimStage3bytes, DEVICE_NAME_OFFSET, getDeviceName().getBytes().length);
            claimStage3bytes[0x24] = (byte)claimingNumber.get();  // The number we are claiming.
            for (int i = 1; i <= 3 && mixerAssigned.get() == 0; i++) {
                claimStage3bytes[0x25] = (byte)i;  // The packet counter.
                try {
                    logger.debug("Sending claim stage 3 packet " + i + " for device " + claimStage3bytes[0x24]);
                    DatagramPacket announcement = new DatagramPacket(claimStage3bytes, claimStage3bytes.length,
                            broadcastAddress.get(), DeviceFinder.ANNOUNCEMENT_PORT);
                    socket.get().send(announcement);
                    //noinspection BusyWait
                    Thread.sleep(300);
                } catch (Exception e) {
                    logger.warn("Unable to send device number claim stage 3 packet to network, failing to go online.", e);
                    claimingNumber.set(0);
                    return false;
                }
                if (claimRejected.get()) {  // Some other player is defending the number we tried to claim.
                    if (getDeviceNumber() == 0) {  // We are trying to pick a number.
                        continue selfAssignLoop;  // Try the next available number, if any.
                    }
                    logger.warn("Unable to use device number " + getDeviceNumber() + ", another device has it. Failing to go online.");
                    claimingNumber.set(0);
                    return false;
                }
            }

            claimed = true;  // If we finished all our loops, the number we wanted is ours.
        }
        // Set the device number we claimed.
        keepAliveBytes[DEVICE_NUMBER_OFFSET] = (byte)claimingNumber.getAndSet(0);
        mixerAssigned.set(0);
        return true;  // Huzzah, we found the right device number to use!
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
        matchingInterfaces = new ArrayList<NetworkInterface>();
        matchedAddress = null;
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

        // Copy the chosen interface's hardware and IP addresses into the announcement packet template
        System.arraycopy(matchingInterfaces.get(0).getHardwareAddress(), 0, keepAliveBytes, MAC_ADDRESS_OFFSET, 6);
        System.arraycopy(matchedAddress.getAddress().getAddress(), 0, keepAliveBytes, 44, 4);
        broadcastAddress.set(matchedAddress.getBroadcast());

        // Open our communication socket.
        socket.set(new DatagramSocket(UPDATE_PORT, matchedAddress.getAddress()));

        // Inform the DeviceFinder to ignore our own device announcement packets.
        DeviceFinder.getInstance().addIgnoredAddress(socket.get().getLocalAddress());

        // Determine the device number we are supposed to use, and make sure it can be claimed by us.
        if (!claimDeviceNumber()) {
            // We couldn't get a device number, so clean up and report failure.
            logger.warn("Unable to allocate a device number for the Virtual CDJ, giving up.");
            DeviceFinder.getInstance().removeIgnoredAddress(socket.get().getLocalAddress());
            socket.get().close();
            socket.set(null);
            return false;
        }

        // Set up our buffer and packet to receive incoming messages.
        final Thread receiver = createStatusReceiver();
        receiver.start();

        // Create the thread which announces our participation in the DJ Link network, to request update packets
        Thread announcer = new Thread(null, new Runnable() {
            @Override
            public void run() {
                while (isRunning()) {
                    sendAnnouncement(broadcastAddress.get());
                }
            }
        }, "beat-link VirtualCdj announcement sender");
        announcer.setDaemon(true);
        announcer.start();
        deliverLifecycleAnnouncement(logger, true);
        return true;
    }

    /**
     * Create a thread that will wait for and process status update packets sent to our socket.
     *
     * @return the thread
     */
    private Thread createStatusReceiver() {
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
                                processUpdate(update);
                            }
                        }
                    } catch (Throwable t) {
                        logger.warn("Problem processing device update packet", t);
                    }
                }
            }
        }, "beat-link VirtualCdj status receiver");
        receiver.setDaemon(true);
        receiver.setPriority(Thread.MAX_PRIORITY);
        return receiver;
    }

    /**
     * Checks if we can see any players that are on a different network than the one we chose for the Virtual CDJ.
     * If so, we are not going to be able to communicate with them, and they should all be moved onto a single
     * network.
     *
     * @return the device announcements of any players which are on unreachable networks, or hopefully an empty list
     * @throws IllegalStateException if we are not running
     */
    public Set<DeviceAnnouncement> findUnreachablePlayers() {
        ensureRunning();
        Set<DeviceAnnouncement> result = new HashSet<DeviceAnnouncement>();
        for (DeviceAnnouncement candidate: DeviceFinder.getInstance().getCurrentDevices()) {
            if (!Util.sameNetwork(matchedAddress.getNetworkPrefixLength(), matchedAddress.getAddress(), candidate.getAddress())) {
                result.add(candidate);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Makes sure we get shut down if the {@link DeviceFinder} does, because we rely on it.
     */
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
     * Makes sure we get shut down if the VirtualRekordbox does when in proxy mode.
     */
    private final LifecycleListener virtualRekordboxLifecycleListener = new LifecycleListener() {
        @Override
        public void started(LifecycleParticipant sender) {
            logger.debug("Nothing to do when VirtualRekordbox starts.");
        }

        @Override
        public void stopped(LifecycleParticipant sender) {
            if (inOpusQuadCompatibilityMode()) {
                logger.info("Shutting down because VirtualRekordbox is and we were proxying for it.");
                stop();
            }
        }
    };

    /**
     * <p>In normal operation (with Pro DJ Link devices), start announcing ourselves and listening for status packets.
     * If VirtualRekordbox is running, then we are actually in Opus Quad compatibility mode, and will do far less,
     * acting as a proxy for packets that it is responsible for receiving. Normal operation Requires the
     * {@link DeviceFinder} to be active in order to find out how to communicate with other devices, so will start
     * that if it is not already.</p>
     *
     * <p>If already active, has no effect.</p>
     *
     * @return true if we found DJ Link devices and were able to create the {@code VirtualCdj}, or it was already running.
     * @throws SocketException if the socket to listen on port 50002 cannot be created
     */
    @SuppressWarnings("UnusedReturnValue")
    public synchronized boolean start() throws SocketException {
        if (!isRunning()) {
            // See if we are just going to proxy information for VirtualRekordbox.
            VirtualRekordbox.getInstance().addLifecycleListener(virtualRekordboxLifecycleListener);
            if (VirtualRekordbox.getInstance().isRunning()) {
                proxyingForVirtualRekordbox.set(true);
                return true;
            }

            // Set up so we know we have to shut down if the DeviceFinder shuts down.
            DeviceFinder.getInstance().addLifecycleListener(deviceFinderLifecycleListener);

            // Find some DJ Link devices so we can figure out the interface and address to use to talk to them
            DeviceFinder.getInstance().start();
            for (int i = 0; DeviceFinder.getInstance().getCurrentDevices().isEmpty() && i < 20; i++) {
                try {
                    //noinspection BusyWait
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
     * <p>Start announcing ourselves as a specific device number (if we can claim it) and listening for status packets.
     * If already active, has no effect. Requires the {@link DeviceFinder} to be active in order to find out how to
     * communicate with other devices, so will start that if it is not already. </p>
     *
     * <p>This version is shorthand for calling {@link #setDeviceNumber(byte)} with the specified device number and
     * then immediately calling {@link #start()}, but avoids the race condition which can occur if startup is already
     * in progress, which would lead to an {@link IllegalStateException}. This is not uncommon when startup is being
     * driven by receipt of device announcement packets.</p>
     *
     * @param deviceNumber the device number to try to claim
     *
     * @return true if we found DJ Link devices and were able to create the {@code VirtualCdj}, or it was already running.
     * @throws SocketException if the socket to listen on port 50002 cannot be created
     */
    public synchronized boolean start(byte deviceNumber) throws SocketException {
        if (!isRunning()) {
            setDeviceNumber(deviceNumber);
            return start();
        }
        return true;  // We are already running.
    }

    /**
     * Stop announcing ourselves and listening for status updates.
     */
    public synchronized void stop() {
        if (isRunning()) {
            if (inOpusQuadCompatibilityMode()) {
                // We were just running in proxy mode, so nothing really needs shutting down.
                proxyingForVirtualRekordbox.set(false);
                return;
            }
            try {
                setSendingStatus(false);
            } catch (Throwable t) {
                logger.error("Problem stopping sending status during shutdown", t);
            }
            DeviceFinder.getInstance().removeIgnoredAddress(socket.get().getLocalAddress());
            socket.get().close();
            socket.set(null);
            broadcastAddress.set(null);
            updates.clear();
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
            DatagramPacket announcement = new DatagramPacket(keepAliveBytes, keepAliveBytes.length,
                    broadcastAddress, DeviceFinder.ANNOUNCEMENT_PORT);
            socket.get().send(announcement);
            Thread.sleep(getAnnounceInterval());
        } catch (Throwable t) {
            logger.warn("Unable to send announcement packet, flushing DeviceFinder due to likely network change and shutting down.", t);
            DeviceFinder.getInstance().flush();
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
     * <p><em>Note:</em> If you are trying to determine the current tempo or beat being played by the device, you should
     * either use the status you just received, or
     * {@link org.deepsymmetry.beatlink.data.TimeFinder#getLatestUpdateFor(int)} instead, because that
     * combines both status updates and beat messages, and so is more likely to be current and definitive.</p>
     *
     * @param device the update identifying the device for which current status information is desired
     *
     * @return the most recent detailed status update received for that device
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public DeviceUpdate getLatestStatusFor(DeviceUpdate device) {
        ensureRunning();
        return updates.get(DeviceReference.getDeviceReference(device));
    }

    /**
     * Look up the most recent status we have seen for a device, given its device announcement packet as returned
     * by {@link DeviceFinder#getCurrentDevices()}.
     *
     * <p><em>Note:</em> If you are trying to determine the current tempo or beat being played by the device, you should
     * use {@link org.deepsymmetry.beatlink.data.TimeFinder#getLatestUpdateFor(int)} instead, because that
     * combines both status updates and beat messages, and so is more likely to be current and definitive.</p>
     *
     * @param device the announcement identifying the device for which current status information is desired
     *
     * @return the most recent detailed status update received for that device
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public DeviceUpdate getLatestStatusFor(DeviceAnnouncement device) {
        ensureRunning();
        return updates.get(DeviceReference.getDeviceReference(device));
    }

    /**
     * Look up the most recent status we have seen for a device from a device identifying itself
     * with the specified device number, if any.
     *
     * <p><em>Note:</em> If you are trying to determine the current tempo or beat being played by the device, you should
     * use {@link org.deepsymmetry.beatlink.data.TimeFinder#getLatestUpdateFor(int)} instead, because that
     * combines both status updates and beat messages, and so is more likely to be current and definitive.</p>
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
            } catch (Throwable t) {
                logger.warn("Problem delivering master changed announcement to listener", t);
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
            } catch (Throwable t) {
                logger.warn("Problem delivering tempo changed announcement to listener", t);
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
            } catch (Throwable t) {
                logger.warn("Problem delivering master beat announcement to listener", t);
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
    private void deliverDeviceUpdate(final DeviceUpdate update) {
        for (DeviceUpdateListener listener : getUpdateListeners()) {
            try {
                listener.received(update);
            } catch (Throwable t) {
                logger.warn("Problem delivering device update to listener", t);
            }
        }
    }

    // This is a way to get mediaDetails from Rekordbox and pass to all MediaDetailsListeners
    private final MediaDetailsListener mediaDetailsListener = new MediaDetailsListener(){
        @Override
        public void detailsAvailable(MediaDetails details) {
            deliverMediaDetailsUpdate(details);
        }
    };

    public MediaDetailsListener getMediaDetailsListener() {
        return mediaDetailsListener;
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
    private void deliverMediaDetailsUpdate(final MediaDetails details) {
        for (MediaDetailsListener listener : getMediaDetailsListeners()) {
            try {
                listener.detailsAvailable(details);
            } catch (Throwable t) {
                logger.warn("Problem delivering media details response to listener", t);
            }
        }
    }

    /**
     * Finish the work of building and sending a protocol packet.
     *
     * @param kind the type of packet to create and send
     * @param payload the content which will follow our device name in the packet
     * @param destination where the packet should be sent
     * @param port the port to which the packet should be sent
     *
     * @throws IOException if there is a problem sending the packet
     */
    @SuppressWarnings("SameParameterValue")
    private void assembleAndSendPacket(Util.PacketType kind, byte[] payload, InetAddress destination, int port) throws IOException {
        DatagramPacket packet = Util.buildPacket(kind,
                ByteBuffer.wrap(keepAliveBytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH).asReadOnlyBuffer(),
                ByteBuffer.wrap(payload));
        packet.setAddress(destination);
        packet.setPort(port);
        socket.get().send(packet);
    }

    /**
     * The bytes after the device name in a media query packet.
     */
    private final static byte[] MEDIA_QUERY_PAYLOAD = { 0x01,
    0x00, 0x0d, 0x00, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

    /**
     * Ask a device for information about the media mounted in a particular slot. Will update the
     * {@link MetadataFinder} when a response is received.
     *
     * @param slot the slot holding media we want to know about.
     *
     * @throws IOException if there is a problem sending the request.
     */
    public void sendMediaQuery(SlotReference slot) throws IOException {
        final DeviceAnnouncement announcement = DeviceFinder.getInstance().getLatestAnnouncementFrom(slot.player);
        if (announcement == null) {
            throw new IllegalArgumentException("Device for " + slot + " not found on network.");
        }
        ensureRunning();
        byte[] payload = new byte[MEDIA_QUERY_PAYLOAD.length];
        System.arraycopy(MEDIA_QUERY_PAYLOAD, 0, payload, 0, MEDIA_QUERY_PAYLOAD.length);
        payload[2] = getDeviceNumber();
        System.arraycopy(keepAliveBytes, 44, payload, 5, 4);  // Copy in our IP address.
        payload[12] = (byte)slot.player;
        payload[16] = slot.slot.protocolValue;
        assembleAndSendPacket(Util.PacketType.MEDIA_QUERY, payload, announcement.getAddress(), UPDATE_PORT);
    }

    /**
     * The bytes after the device name in a sync control command packet.
     */
    private final static byte[] SYNC_CONTROL_PAYLOAD = { 0x01,
            0x00, 0x0d, 0x00, 0x08, 0x00, 0x00, 0x00, 0x0d, 0x00, 0x00, 0x00, 0x0f };

    /**
     * Assemble and send a packet that performs sync control, turning a device's sync mode on or off, or telling it
     * to become the tempo master.
     *
     * @param target an update from the device whose sync state is to be set
     * @param command the byte identifying the specific sync command to be sent
     *
     * @throws IOException if there is a problem sending the command to the device
     */
    private void sendSyncControlCommand(DeviceUpdate target, byte command) throws IOException {
        ensureRunning();
        byte[] payload = new byte[SYNC_CONTROL_PAYLOAD.length];
        System.arraycopy(SYNC_CONTROL_PAYLOAD, 0, payload, 0, SYNC_CONTROL_PAYLOAD.length);
        payload[2] = getDeviceNumber();
        payload[8] = getDeviceNumber();
        payload[12] = command;
        assembleAndSendPacket(Util.PacketType.SYNC_CONTROL, payload, target.getAddress(), BeatFinder.BEAT_PORT);
    }

    /**
     * Tell a device to turn sync on or off.
     *
     * @param deviceNumber the device whose sync state is to be set
     * @param synced {@code} true if sync should be turned on, else it will be turned off
     *
     * @throws IOException if there is a problem sending the command to the device
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     * @throws IllegalArgumentException if {@code deviceNumber} is not found on the network
     */
    public void sendSyncModeCommand(int deviceNumber, boolean synced) throws IOException {
        final DeviceUpdate update = getLatestStatusFor(deviceNumber);
        if (update == null) {
            throw new IllegalArgumentException("Device " + deviceNumber + " not found on network.");
        }
        sendSyncModeCommand(update, synced);
    }

    /**
     * Tell a device to turn sync on or off.
     *
     * @param target an update from the device whose sync state is to be set
     * @param synced {@code} true if sync should be turned on, else it will be turned off
     *
     * @throws IOException if there is a problem sending the command to the device
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     * @throws NullPointerException if {@code update} is {@code null}
     */
    public void sendSyncModeCommand(DeviceUpdate target, boolean synced) throws IOException {
        sendSyncControlCommand(target, synced? (byte)0x10 : (byte)0x20);
    }

    /**
     * Tell a device to become tempo master.
     *
     * @param deviceNumber the device we want to take over the role of tempo master
     *
     * @throws IOException if there is a problem sending the command to the device
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     * @throws IllegalArgumentException if {@code deviceNumber} is not found on the network
     */
    public void appointTempoMaster(int deviceNumber) throws IOException {
        final DeviceUpdate update = getLatestStatusFor(deviceNumber);
        if (update == null) {
            throw new IllegalArgumentException("Device " + deviceNumber + " not found on network.");
        }
        appointTempoMaster(update);
    }

    /**
     * Tell a device to become tempo master.
     *
     * @param target an update from the device that we want to take over the role of tempo master
     *
     * @throws IOException if there is a problem sending the command to the device
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     * @throws NullPointerException if {@code update} is {@code null}
     */
    public void appointTempoMaster(DeviceUpdate target) throws IOException {
        sendSyncControlCommand(target, (byte)0x01);
    }

    /**
     * The bytes after the device name in a fader start command packet.
     */
    private final static byte[] FADER_START_PAYLOAD = { 0x01,
            0x00, 0x0d, 0x00, 0x04, 0x02, 0x02, 0x02, 0x02 };

    /**
     * Broadcast a packet that tells some players to start playing and others to stop. If a player number is in
     * both sets, it will be told to stop. Numbers outside the range 1 to 4 are ignored.
     *
     * @param deviceNumbersToStart the players that should start playing if they aren't already
     * @param deviceNumbersToStop the players that should stop playing
     *
     * @throws IOException if there is a problem broadcasting the command to the players
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public void sendFaderStartCommand(Set<Integer> deviceNumbersToStart, Set<Integer> deviceNumbersToStop) throws IOException {
        ensureRunning();
        byte[] payload = new byte[FADER_START_PAYLOAD.length];
        System.arraycopy(FADER_START_PAYLOAD, 0, payload, 0, FADER_START_PAYLOAD.length);
        payload[2] = getDeviceNumber();

        for (int i = 1; i <= 4; i++) {
            if (deviceNumbersToStart.contains(i)) {
                payload[i + 4] = 0;
            }
            if (deviceNumbersToStop.contains(i)) {
                payload[i + 4] = 1;
            }
        }

        assembleAndSendPacket(Util.PacketType.FADER_START_COMMAND, payload, getBroadcastAddress(), BeatFinder.BEAT_PORT);
    }

    /**
     * The bytes after the device name in a channels on-air report packet.
     */
    private final static byte[] CHANNELS_ON_AIR_PAYLOAD = { 0x01,
            0x00, 0x0d, 0x00, 0x09, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };

    /**
     * Broadcast a packet that tells the players which channels are on the air (audible in the mixer output).
     * This version sends the packet which was used by mixers older than the DJM-V10, and supports devices 1-4.
     * Numbers outside the range 1 to 4 are ignored. If there is an actual DJM mixer on the network, it will
     * be sending these packets several times per second, so the results of calling this method will be quickly
     * overridden.
     *
     * @param deviceNumbersOnAir the players whose channels are currently on the air
     *
     * @throws IOException if there is a problem broadcasting the command to the players
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public void sendOnAirCommand(Set<Integer> deviceNumbersOnAir) throws IOException {
        ensureRunning();
        byte[] payload = new byte[CHANNELS_ON_AIR_PAYLOAD.length];
        System.arraycopy(CHANNELS_ON_AIR_PAYLOAD, 0, payload, 0, CHANNELS_ON_AIR_PAYLOAD.length);
        payload[2] = getDeviceNumber();

        for (int i = 1; i <= 4; i++) {
            if (deviceNumbersOnAir.contains(i)) {
                payload[i + 4] = 1;
            }
        }

        assembleAndSendPacket(Util.PacketType.CHANNELS_ON_AIR, payload, getBroadcastAddress(), BeatFinder.BEAT_PORT);
    }

    /**
     * The bytes after the device name in an extended channels on-air report packet.
     */
    private final static byte[] CHANNELS_ON_AIR_EXTENDED_PAYLOAD = { 0x01,
            0x03, 0x0d, 0x00, 0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00 };

    /**
     * Broadcast a packet that tells the players which channels are on the air (audible in the mixer output).
     * This version sends the packet used by the DJM-V10 mixer, and supports devices 1-6.
     * Numbers outside the range 1 to 6 are ignored. If there is an actual DJM mixer on the network, it will
     * be sending these packets several times per second, so the results of calling this method will be quickly
     * overridden.
     *
     * @param deviceNumbersOnAir the players whose channels are currently on the air
     *
     * @throws IOException if there is a problem broadcasting the command to the players
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public void sendOnAirExtendedCommand(Set<Integer> deviceNumbersOnAir) throws IOException {
        ensureRunning();
        byte[] payload = new byte[CHANNELS_ON_AIR_EXTENDED_PAYLOAD.length];
        System.arraycopy(CHANNELS_ON_AIR_EXTENDED_PAYLOAD, 0, payload, 0, CHANNELS_ON_AIR_EXTENDED_PAYLOAD.length);
        payload[2] = getDeviceNumber();

        for (int i = 1; i <= 4; i++) {
            if (deviceNumbersOnAir.contains(i)) {
                payload[i + 4] = 1;
            }
        }

        for (int i = 5; i <= 6; i++) {
            if (deviceNumbersOnAir.contains(i)) {
                payload[i + 9] = 1;
            }
        }

        assembleAndSendPacket(Util.PacketType.CHANNELS_ON_AIR, payload, getBroadcastAddress(), BeatFinder.BEAT_PORT);
    }

    /**
     * The bytes after the device name in a load-track command packet.
     */
    private final static byte[] LOAD_TRACK_PAYLOAD = { 0x01,
            0x00, 0x0d, 0x00, 0x34,  0x0d, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x32,  0x00, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00
    };

    /**
     * Send a packet to the target player telling it to load the specified track from the specified source player.
     *
     * @param targetPlayer the device number of the player that you want to have load a track
     * @param rekordboxId the identifier of a track within the source player's rekordbox database
     * @param sourcePlayer the device number of the player from which the track should be loaded
     * @param sourceSlot the media slot from which the track should be loaded
     * @param sourceType the type of track to be loaded
     *
     * @throws IOException if there is a problem sending the command
     * @throws IllegalStateException if the {@code VirtualCdj} is not active or the target device cannot be found
     */
    public void sendLoadTrackCommand(int targetPlayer, int rekordboxId,
                                     int sourcePlayer, CdjStatus.TrackSourceSlot sourceSlot, CdjStatus.TrackType sourceType)
            throws IOException {
        final DeviceUpdate update = getLatestStatusFor(targetPlayer);
        if (update == null) {
            throw new IllegalArgumentException("Device " + targetPlayer + " not found on network.");
        }
        sendLoadTrackCommand(update, rekordboxId, sourcePlayer, sourceSlot, sourceType);
    }

    /**
     * Send a packet to the target device telling it to load the specified track from the specified source player.
     *
     * @param target an update from the player that you want to have load a track
     * @param rekordboxId the identifier of a track within the source player's rekordbox database
     * @param sourcePlayer the device number of the player from which the track should be loaded
     * @param sourceSlot the media slot from which the track should be loaded
     * @param sourceType the type of track to be loaded
     *
     * @throws IOException if there is a problem sending the command
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public void sendLoadTrackCommand(DeviceUpdate target, int rekordboxId,
                                     int sourcePlayer, CdjStatus.TrackSourceSlot sourceSlot, CdjStatus.TrackType sourceType)
            throws IOException {
        ensureRunning();
        byte[] payload = new byte[LOAD_TRACK_PAYLOAD.length];
        System.arraycopy(LOAD_TRACK_PAYLOAD, 0, payload, 0, LOAD_TRACK_PAYLOAD.length);
        payload[0x02] = getDeviceNumber();
        payload[0x05] = getDeviceNumber();
        payload[0x09] = (byte)sourcePlayer;
        payload[0x0a] = sourceSlot.protocolValue;
        payload[0x0b] = sourceType.protocolValue;
        payload[0x21] = (byte)(target.getDeviceNumber() - 1);
        if (target.deviceName.startsWith("XDJ-XZ")) {
            // Use the packet variant that seems to be necessary for the XDJ-XZ to accept it.
            payload[0x01] = 1;
            payload[0x2c] = 0x32;
            // See if we can find a rekordbox instance to pose as, which is also necessary to make this work.
            for (DeviceAnnouncement device : DeviceFinder.getInstance().getCurrentDevices()) {
                final byte number = (byte)device.getDeviceNumber();
                if (number >= 0x11 && number < 0x20) {  // Aha! We can see a regular rekordbox instance, this will work.
                    payload[0x02] = number;
                    payload[0x05] = number;
                    break;
                }
            }
            if (payload[0x02] == getDeviceNumber()) {  // We did not find a laptop rekordbox, see if there's a mobile one.
                for (DeviceAnnouncement device : DeviceFinder.getInstance().getCurrentDevices()) {
                    final byte number = (byte)device.getDeviceNumber();
                    if (number >= 0x29 && number < 0x30) { // We can see rekordbox mobile, this will work too.
                        payload[0x02] = number;
                        payload[0x05] = number;
                        break;
                    }
                }
            }
        }
        Util.numberToBytes(rekordboxId, payload, 0x0d, 4);
        assembleAndSendPacket(Util.PacketType.LOAD_TRACK_COMMAND, payload, target.getAddress(), UPDATE_PORT);
    }

    /**
     * The bytes after the device name in a load-track acknowledgment packet.
     */
    private final static byte[] YIELD_ACK_PAYLOAD = { 0x01,
            0x00, 0x0d, 0x00, 0x08,  0x00, 0x00, 0x00, 0x0d,   0x00, 0x00, 0x00, 0x01
    };

    /**
     * The bytes after the device name in a load-settings command packet.
     */
    private final static byte[] LOAD_SETTINGS_PAYLOAD = { 0x02,
            0x0d, 0x0d, 0x00, 0x50,  0x12, 0x34, 0x56, 0x78,   0x00, 0x00, 0x00, 0x03,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x01, 0x00, 0x00,  0x00, 0x01, 0x01, 0x01,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
    };

    /**
     * Send a packet to the target player telling it to apply the supplied settings.
     *
     * @param targetPlayer the device number of the player that you want to configure
     * @param settings the device settings that should be loaded
     *
     * @throws IOException if there is a problem sending the command
     * @throws IllegalStateException if the {@code VirtualCdj} is not active or the target device cannot be found
     */
    public void sendLoadSettingsCommand(int targetPlayer, PlayerSettings settings)
            throws IOException {
        final DeviceUpdate update = getLatestStatusFor(targetPlayer);
        if (update == null) {
            throw new IllegalArgumentException("Device " + targetPlayer + " not found on network.");
        }
        sendLoadSettingsCommand(update, settings);
    }

    /**
     * Send a packet to the target device telling it to apply the supplied settings.
     *
     * @param target an update from the player that you want to configure
     * @param settings the device settings that should be loaded
     *
     * @throws IOException if there is a problem sending the command
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    public void sendLoadSettingsCommand(DeviceUpdate target, PlayerSettings settings) throws IOException {
        ensureRunning();
        byte[] payload = new byte[LOAD_SETTINGS_PAYLOAD.length];
        System.arraycopy(LOAD_SETTINGS_PAYLOAD, 0, payload, 0, LOAD_SETTINGS_PAYLOAD.length);
        Util.setPayloadByte(payload, 0x20, getDeviceNumber());
        Util.setPayloadByte(payload, 0x21, (byte)target.getDeviceNumber());
        Util.setPayloadByte(payload, 0x2c, settings.onAirDisplay.protocolValue);
        Util.setPayloadByte(payload, 0x2d, settings.lcdBrightness.protocolValue);
        Util.setPayloadByte(payload, 0x2e, settings.quantize.protocolValue);
        Util.setPayloadByte(payload, 0x2f, settings.autoCueLevel.protocolValue);
        Util.setPayloadByte(payload, 0x30, settings.language.protocolValue);
        Util.setPayloadByte(payload, 0x32, settings.jogRingIllumination.protocolValue);
        Util.setPayloadByte(payload, 0x33, settings.jogRingIndicator.protocolValue);
        Util.setPayloadByte(payload, 0x34, settings.slipFlashing.protocolValue);
        Util.setPayloadByte(payload, 0x38, settings.discSlotIllumination.protocolValue);
        Util.setPayloadByte(payload, 0x39, settings.ejectLoadLock.protocolValue);
        Util.setPayloadByte(payload, 0x3a, settings.sync.protocolValue);
        Util.setPayloadByte(payload, 0x3b, settings.autoPlayMode.protocolValue);
        Util.setPayloadByte(payload, 0x3c, settings.quantizeBeatValue.protocolValue);
        Util.setPayloadByte(payload, 0x3d, settings.autoLoadMode.protocolValue);
        Util.setPayloadByte(payload, 0x3e, settings.hotCueColor.protocolValue);
        Util.setPayloadByte(payload, 0x41, settings.needleLock.protocolValue);
        Util.setPayloadByte(payload, 0x44, settings.timeDisplayMode.protocolValue);
        Util.setPayloadByte(payload, 0x45, settings.jogMode.protocolValue);
        Util.setPayloadByte(payload, 0x46, settings.autoCue.protocolValue);
        Util.setPayloadByte(payload, 0x47, settings.masterTempo.protocolValue);
        Util.setPayloadByte(payload, 0x48, settings.tempoRange.protocolValue);
        Util.setPayloadByte(payload, 0x49, settings.phaseMeterType.protocolValue);
        Util.setPayloadByte(payload, 0x4c, settings.vinylSpeedAdjust.protocolValue);
        Util.setPayloadByte(payload, 0x4d, settings.jogWheelDisplay.protocolValue);
        Util.setPayloadByte(payload, 0x4e, settings.padButtonBrightness.protocolValue);
        Util.setPayloadByte(payload, 0x4f, settings.jogWheelLcdBrightness.protocolValue);
        assembleAndSendPacket(Util.PacketType.LOAD_SETTINGS_COMMAND, payload, target.getAddress(), UPDATE_PORT);
    }

    /**
     * The number of milliseconds that we will wait between sending status packets, when we are sending them.
     */
    private int statusInterval = 200;

    /**
     * Check how often we will send status packets, if we are configured to send them.
     *
     * @return the millisecond interval that will pass between status packets we send
     */
    public synchronized int getStatusInterval() {
        return statusInterval;
    }

    /**
     * Change the interval at which we will send status packets, if we are configured to send them. You probably won't
     * need to change this unless you are experimenting. If you find an environment where the default of 200ms doesn't
     * work, please open an issue.
     *
     * @param interval the millisecond interval that will pass between each status packet we send
     *
     * @throws IllegalArgumentException if {@code interval} is less than 20 or more than 2000
     */
    public synchronized void setStatusInterval(int interval) {
        if (interval < 20 || interval > 2000) {
            throw new IllegalArgumentException("interval must be between 20 and 2000");
        }
        this.statusInterval = interval;
    }

    /**
     * Makes sure we stop sending status if the {@link BeatFinder} shuts down, because we rely on it.
     */
    private final LifecycleListener beatFinderLifecycleListener = new LifecycleListener() {
        @Override
        public void started(LifecycleParticipant sender) {
            logger.debug("VirtualCDJ doesn't have anything to do when the BeatFinder starts");
        }

        @Override
        public void stopped(LifecycleParticipant sender) {
            if (isSendingStatus()) {
                logger.info("VirtualCDJ no longer sending status updates because BeatFinder has stopped.");
                try {
                    setSendingStatus(false);
                } catch (Exception e) {
                    logger.error("Problem stopping sending status packets when the BeatFinder stopped", e);
                }
            }
        }
    };

    /**
     * Will hold an instance when we are actively sending beats, so we can let it know when the metronome changes,
     * and when it is time to shut down.
     */
    private final AtomicReference<BeatSender> beatSender = new AtomicReference<BeatSender>();

    /**
     * Check whether we are currently running a {@link BeatSender}; if we are, notify it that there has been a change
     * to the metronome timeline, so it needs to wake up and reassess its situation.
     */
    private void notifyBeatSenderOfChange() {
        final BeatSender activeSender = beatSender.get();
        if (activeSender != null) {
            activeSender.timelineChanged();
        }
    }

    /**
     * The bytes following the device name in a beat packet.
     */
    private static final byte[] BEAT_PAYLOAD = { 0x01,
            0x00, 0x0d, 0x00, 0x3c,  0x01, 0x01, 0x01, 0x01,   0x02, 0x02, 0x02, 0x02,  0x10, 0x10, 0x10, 0x10,
            0x04, 0x04, 0x04, 0x04,  0x20, 0x20, 0x20, 0x20,   0x08, 0x08, 0x08, 0x08,    -1,   -1,   -1,   -1,
            -1,   -1,   -1,   -1,    -1,   -1,   -1,   -1,     -1,   -1,   -1,   -1,    -1,   -1,   -1,   -1,
            -1,   -1,   -1,   -1,  0x00, 0x10, 0x00, 0x00,   0x00, 0x00, 0x00, 0x00,  0x0b, 0x00, 0x00, 0x0d};

    /**
     * Sends a beat packet. Generally this should only be invoked when our {@link BeatSender} has determined that it is
     * time to do so, but it is public to allow experimentation.
     *
     * @return the beat number that was sent, computed from the current (or stopped) playback position
     */
    public long sendBeat() {
        return sendBeat(getPlaybackPosition());
    }

    /**
     * Sends a beat packet. Generally this should only be invoked when our {@link BeatSender} has determined that it is
     * time to do so, but it is public to allow experimentation.
     *
     * @param snapshot the time at which the beat to be sent occurred, for computation of its beat number
     *
     * @return the beat number that was sent
     */
    public long sendBeat(Snapshot snapshot) {
        byte[] payload = new byte[BEAT_PAYLOAD.length];
        System.arraycopy(BEAT_PAYLOAD, 0, payload, 0, BEAT_PAYLOAD.length);
        payload[0x02] = getDeviceNumber();

        Util.numberToBytes((int)snapshot.getBeatInterval(), payload, 0x05, 4);
        Util.numberToBytes((int)(snapshot.getBeatInterval() * 2), payload, 0x09, 4);
        Util.numberToBytes((int)(snapshot.getBeatInterval() * 4), payload, 0x11, 4);
        Util.numberToBytes((int)(snapshot.getBeatInterval() * 8), payload, 0x19, 4);

        final int beatsLeft = 5 - snapshot.getBeatWithinBar();
        final int nextBar = (int)(snapshot.getBeatInterval() * beatsLeft);
        Util.numberToBytes(nextBar, payload, 0x0d, 4);
        Util.numberToBytes(nextBar + (int)snapshot.getBarInterval(), payload, 0x15, 4);

        Util.numberToBytes((int)Math.round(snapshot.getTempo() * 100), payload, 0x3b, 2);
        payload[0x3d] = (byte)snapshot.getBeatWithinBar();
        payload[0x40] = getDeviceNumber();

        try {
            assembleAndSendPacket(Util.PacketType.BEAT, payload, broadcastAddress.get(), BeatFinder.BEAT_PORT);
        } catch (IOException e) {
            logger.error("VirtualCdj Failed to send beat packet.", e);
        }

        return snapshot.getBeat();
    }

    /**
     * Will hold a non-null value when we are sending our own status packets, which can be used to stop the thread
     * doing so. Most uses of Beat Link will not require this level of activity. However, if you want to be able to
     * take over the tempo master role, and control the tempo and beat alignment of other players, you will need to
     * turn on this feature, which also requires that you are using one of the standard player numbers, 1-4.
     */
    private AtomicBoolean sendingStatus = null;

    /**
     * Control whether the Virtual CDJ sends status packets to the other players. Most uses of Beat Link will not
     * require this level of activity. However, if you want to be able to take over the tempo master role, and control
     * the tempo and beat alignment of other players, you will need to turn on this feature, which also requires that
     * you are using one of the standard player numbers, 1-4.
     *
     * @param send if {@code true} we will send status packets, and can participate in (and control) tempo and beat sync
     *
     * @throws IllegalStateException if the virtual CDJ is not running, or if it is not using a device number in the
     *                               range 1 through 4
     * @throws IOException if there is a problem starting the {@link BeatFinder}
     */
    public synchronized void setSendingStatus(boolean send) throws IOException {
        if (isSendingStatus() == send) {
            return;
        }

        if (send) {  // Start sending status packets.
            ensureRunning();
            if (proxyingForVirtualRekordbox.get()) {
                throw new IllegalStateException("Cannot send status when in Opus Quad compatibility mode.");
            }
            if ((getDeviceNumber() < 1) || (getDeviceNumber() > 4)) {
                throw new IllegalStateException("Can only send status when using a standard player number, 1 through 4.");
            }

            BeatFinder.getInstance().start();
            BeatFinder.getInstance().addLifecycleListener(beatFinderLifecycleListener);

            final AtomicBoolean stillRunning = new AtomicBoolean(true);
            sendingStatus =  stillRunning;  // Allow other threads to stop us when necessary.

            Thread sender = new Thread(null, new Runnable() {
                @Override
                public void run() {
                    while (stillRunning.get()) {
                        sendStatus();
                        try {
                            //noinspection BusyWait
                            Thread.sleep(getStatusInterval());
                        } catch (InterruptedException e) {
                            logger.warn("beat-link VirtualCDJ status sender thread was interrupted; continuing");
                        }
                    }
                }
            }, "beat-link VirtualCdj status sender");
            sender.setDaemon(true);
            sender.start();

            if (isSynced()) {  // If we are supposed to be synced, we need to respond to master beats and tempo changes.
                addMasterListener(ourSyncMasterListener);
            }

            if (isPlaying()) {  // Start the beat sender too, if we are supposed to be playing.
                beatSender.set(new BeatSender(metronome));
            }
        } else {  // Stop sending status packets, and responding to master beats and tempo changes if we were synced.
            BeatFinder.getInstance().removeLifecycleListener(beatFinderLifecycleListener);
            removeMasterListener(ourSyncMasterListener);

            sendingStatus.set(false);                          // Stop the status sending thread.
            sendingStatus = null;                              // Indicate that we are no longer sending status.
            final BeatSender activeSender = beatSender.get();  // And stop the beat sender if we have one.
            if (activeSender != null) {
                activeSender.shutDown();
                beatSender.set(null);
            }
        }
    }

    /**
     * Check whether we are currently sending status packets.
     *
     * @return {@code true} if we are sending status packets, and can participate in (and control) tempo and beat sync
     */
    public synchronized boolean isSendingStatus() {
        return (sendingStatus != null);
    }

    /**
     * Used to keep time when we are pretending to play a track, and to allow us to sync with other players when we
     * are told to do so.
     */
    private final Metronome metronome = new Metronome();

    /**
     * Keeps track of our position when we are not playing; this beat gets loaded into the metronome when we start
     * playing, and it will keep time from there. When we stop again, we save the metronome's current beat here.
     */
    private final AtomicReference<Snapshot> whereStopped =
            new AtomicReference<Snapshot>(metronome.getSnapshot(metronome.getStartTime()));

    /**
     * Indicates whether we should currently pretend to be playing. This will only have an impact when we are sending
     * status and beat packets.
     */
    private final AtomicBoolean playing = new AtomicBoolean(false);

    /**
     * Controls whether we report that we are playing. This will only have an impact when we are sending status and
     * beat packets.
     *
     * @param playing {@code true} if we should seem to be playing
     */
    public void setPlaying(boolean playing) {

        if (this.playing.get() == playing) {
            return;
        }

        this.playing.set(playing);

        if (playing) {
            metronome.jumpToBeat(whereStopped.get().getBeat());
            if (isSendingStatus()) {  // Need to also start the beat sender.
                beatSender.set(new BeatSender(metronome));
            }
        } else {
            final BeatSender activeSender = beatSender.get();
            if (activeSender != null) {  // We have a beat sender we need to stop.
                activeSender.shutDown();
                beatSender.set(null);
            }
            whereStopped.set(metronome.getSnapshot());
        }
    }

    /**
     * Check whether we are pretending to be playing. This will only have an impact when we are sending status and
     * beat packets.
     *
     * @return {@code true} if we are reporting active playback
     */
    public boolean isPlaying() {
        return playing.get();
    }

    /**
     * Find details about the current simulated playback position.
     *
     * @return the current (or last, if we are stopped) playback state
     */
    public Snapshot getPlaybackPosition() {
        if (playing.get()) {
            return metronome.getSnapshot();
        } else {
            return whereStopped.get();
        }
    }

    /**
     * <p>Nudge the playback position by the specified number of milliseconds, to support synchronization with an
     * external clock. Positive values move playback forward in time, while negative values jump back. If we are
     * sending beat packets, notify the beat sender that the timeline has changed.</p>
     *
     * <p>If the shift would put us back before beat one, we will jump forward a bar to correct that. It is thus not
     * safe to jump backwards more than a bar's worth of time.</p>
     *
     * @param ms the number of millisecond to shift the simulated playback position
     */
    public void adjustPlaybackPosition(int ms) {
        if (ms != 0) {
            metronome.adjustStart(-ms);
            if (metronome.getBeat() < 1) {
                metronome.adjustStart(Math.round(Metronome.beatsToMilliseconds(metronome.getBeatsPerBar(), metronome.getTempo())));
            }
            notifyBeatSenderOfChange();
        }
    }

    /**
     * Indicates whether we are currently the tempo master. Will only be meaningful (and get set) if we are sending
     * status packets.
     */
    private final AtomicBoolean master = new AtomicBoolean(false);

    private static final byte[] MASTER_HANDOFF_REQUEST_PAYLOAD = { 0x01,
            0x00, 0x0d, 0x00, 0x04, 0x00, 0x00, 0x00, 0x0d };

    /**
     * When have started the process of requesting the tempo master role from another player, this gets set to its
     * device number. Otherwise it has the value {@code 0}.
     */
    private final AtomicInteger requestingMasterRoleFromPlayer = new AtomicInteger(0);

    /**
     * Arrange to become the tempo master. Starts a sequence of interactions with the other players that should end
     * up with us in charge of the group tempo and beat alignment.
     *
     * @throws IllegalStateException if we are not sending status updates
     * @throws IOException if there is a problem sending the master yield request
     */
    public synchronized void becomeTempoMaster() throws IOException {
        logger.debug("Trying to become master.");
        if (!isSendingStatus()) {
            throw new IllegalStateException("Must be sending status updates to become the tempo master.");
        }

        // Is there someone we need to ask to yield to us?
        final DeviceUpdate currentMaster = getTempoMaster();
        if (currentMaster != null) {
            // Send the yield request; we will become master when we get a successful response.
            byte[] payload = new byte[MASTER_HANDOFF_REQUEST_PAYLOAD.length];
            System.arraycopy(MASTER_HANDOFF_REQUEST_PAYLOAD, 0, payload, 0, MASTER_HANDOFF_REQUEST_PAYLOAD.length);
            payload[2] = getDeviceNumber();
            payload[8] = getDeviceNumber();
            if (logger.isDebugEnabled()) {
                logger.debug("Sending master yield request to player " + currentMaster);
            }
            requestingMasterRoleFromPlayer.set(currentMaster.deviceNumber);
            assembleAndSendPacket(Util.PacketType.MASTER_HANDOFF_REQUEST, payload, currentMaster.address, BeatFinder.BEAT_PORT);
        } else if (!master.get()) {
            // There is no other master, we can just become it immediately.
            requestingMasterRoleFromPlayer.set(0);
            setMasterTempo(getTempo());
            master.set(true);
        }
    }

    /**
     * Check whether we are currently in charge of the tempo and beat alignment.
     *
     * @return {@code true} if we hold the tempo master role
     */
    public boolean isTempoMaster() {
        return master.get();
    }

    /**
     * Used to respond to master tempo changes and beats when we are synced, aligning our own metronome.
     */
    private final MasterListener ourSyncMasterListener = new MasterListener() {
        @SuppressWarnings("EmptyMethod")
        @Override
        public void masterChanged(DeviceUpdate update) {
            // We don’t care about this.
        }

        @Override
        public void tempoChanged(double tempo) {
            if (!isTempoMaster()) {
                metronome.setTempo(tempo);
            }
        }

        @Override
        public void newBeat(Beat beat) {
            if (!isTempoMaster()) {
                metronome.setBeatPhase(0.0);
            }
        }
    };

    /**
     * Indicates whether we are currently staying in sync with the tempo master. Will only be meaningful if we are
     * sending status packets.
     */
    private final AtomicBoolean synced = new AtomicBoolean(false);

    /**
     * Controls whether we are currently staying in sync with the tempo master. Will only be meaningful if we are
     * sending status packets.
     *
     * @param sync if {@code true}, our status packets will be tempo and beat aligned with the tempo master
     */
    public synchronized void setSynced(boolean sync) {
        if (synced.get() != sync) {
            // We are changing sync state, so add or remove our master listener as appropriate.
            if (sync && isSendingStatus()) {
                addMasterListener(ourSyncMasterListener);
            } else {
                removeMasterListener(ourSyncMasterListener);
            }

            // Also, if there is a tempo master, and we just got synced, adopt its tempo.
            if (!isTempoMaster() && getTempoMaster() != null) {
                setTempo(getMasterTempo());
            }
        }
        synced.set(sync);
    }

    /**
     * Check whether we are currently staying in sync with the tempo master. Will only be meaningful if we are
     * sending status packets.
     *
     * @return {@code true} if our status packets will be tempo and beat aligned with the tempo master
     */
    public boolean isSynced() {
        return synced.get();
    }

    /**
     * Indicates whether we believe our channel is currently on the air (audible in the mixer output). Will only
     * be meaningful if we are sending status packets.
     */
    private final AtomicBoolean onAir = new AtomicBoolean(false);

    /**
     * Change whether we believe our channel is currently on the air (audible in the mixer output). Only meaningful
     * if we are sending status packets. If there is a real DJM mixer on the network, it will rapidly override any
     * value established by this method with its actual report about the channel state.
     *
     * @param audible {@code true} if we should report ourselves as being on the air in our status packets
     */
    public void setOnAir(boolean audible) {
        onAir.set(audible);
    }

    /**
     * Checks whether we believe our channel is currently on the air (audible in the mixer output). Only meaningful
     * if we are sending status packets. If there is a real DJM mixer on the network, it will be controlling the state
     * of this property.
     *
     * @return audible {@code true} if we should report ourselves as being on the air in our status packets
     */
    public boolean isOnAir() {
        return onAir.get();
    }

    /**
     * Controls the tempo at which we report ourselves to be playing. Only meaningful if we are sending status packets.
     * If {@link #isSynced()} is {@code true} and we are not the tempo master, any value set by this method will
     * overridden by the the next tempo master change.
     *
     * @param bpm the tempo, in beats per minute, that we should report in our status and beat packets
     */
    public void setTempo(double bpm) {
        if (bpm == 0.0) {
            throw new IllegalArgumentException("Tempo cannot be zero.");
        }

        final double oldTempo = metronome.getTempo();
        metronome.setTempo(bpm);
        notifyBeatSenderOfChange();

        if (isTempoMaster() && (Math.abs(bpm - oldTempo) > getTempoEpsilon())) {
            deliverTempoChangedAnnouncement(bpm);
        }
    }

    /**
     * Check the tempo at which we report ourselves to be playing. Only meaningful if we are sending status packets.
     *
     * @return the tempo, in beats per minute, that we are reporting in our status and beat packets
     */
    public double getTempo() {
        return metronome.getTempo();
    }

    /**
     * The longest beat we will report playing; if we are still playing and reach this beat, we will loop back to beat
     * one. If we are told to jump to a larger beat than this, we map it back into the range we will play. This would
     * be a little over nine hours at 120 bpm, which seems long enough for any track.
     */
    public final int MAX_BEAT = 65536;

    /**
     * Used to keep our beat number from growing indefinitely; we wrap it after a little over nine hours of playback;
     * maybe we are playing a giant loop?
     */
    private int wrapBeat(int beat) {
        if (beat <= MAX_BEAT) {
            return beat;
        }
        // This math is a little funky because beats are one-based rather than zero-based.
        return ((beat - 1) % MAX_BEAT) + 1;
    }

    /**
     * Moves our current playback position to the specified beat; this will be reflected in any status and beat packets
     * that we are sending. An incoming value less than one will jump us to the first beat.
     *
     * @param beat the beat that we should pretend to be playing
     */
    public synchronized void jumpToBeat(int beat) {

        if (beat < 1) {
            beat = 1;
        } else {
            beat = wrapBeat(beat);
        }

        if (playing.get()) {
            metronome.jumpToBeat(beat);
        } else {
            whereStopped.set(metronome.getSnapshot(metronome.getTimeOfBeat(beat)));
        }
    }

    /**
     * Used in the process of handing off the tempo master role to another player.
     */
    private final AtomicInteger syncCounter = new AtomicInteger(1);

    /**
     * Tracks the largest sync counter we have seen on the network, used in the process of handing off the tempo master
     * role to another player.
     */
    private final AtomicInteger largestSyncCounter = new AtomicInteger(1);

    /**
     * Used in the process of handing off the tempo master role to another player. Usually has the value 0xff, meaning
     * no handoff is taking place. But when we are in the process of handing off the role, will hold the device number
     * of the player that is taking over as tempo master.
     */
    private final AtomicInteger nextMaster = new AtomicInteger(0xff);

    /**
     * Used in the process of being handed the tempo master role from another player. Usually has the value 0, meaning
     * no handoff is taking place. But when we have received a successful yield response, will hold the device number
     * of the player that is yielding to us, so we can watch for the next stage in its status updates.
     */
    private final AtomicInteger masterYieldedFrom = new AtomicInteger(0);

    /**
     * Keeps track of the number of status packets we send.
     */
    private final AtomicInteger packetCounter = new AtomicInteger(0);

    /**
     * The template used to assemble a status packet when we are sending them.
     */
    private final static byte[] STATUS_PAYLOAD = { 0x01,
            0x04, 0x00, 0x00, (byte)0xf8, 0x00, 0x00, 0x01, 0x00, 0x00,  0x03,  0x01,  0x00, 0x00, 0x00, 0x00, 0x01,  // 0x020
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte)0xa0, 0x00, 0x00, 0x00, 0x00, 0x00,  // 0x030
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 0x040
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 0x050
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x04, 0x04, 0x00, 0x00, 0x00, 0x04,  // 0x060
            0x00, 0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x31, 0x2e, 0x34, 0x33,  // 0x070
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte)0xff, 0x00, 0x00, 0x10, 0x00, 0x00,  // 0x080
            (byte)0x80, 0x00, 0x00, 0x00, 0x7f, (byte)0xff, (byte)0xff, (byte)0xff, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 0x090
            0x00, 0x00, 0x00, 0x00, 0x01, (byte)0xff, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 0x0a0
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 0x0b0
            0x00, 0x10, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0f, 0x01, 0x00, 0x00,  // 0x0c0
            0x12, 0x34, 0x56, 0x78, 0x00, 0x00, 0x00, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00,  // 0x0d0
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 0x0e0
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 0x0f0
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 0x100
            0x00, 0x00, 0x00, 0x15, 0x00, 0x00, 0x07, 0x61, 0x00, 0x00, 0x06, 0x2f  // 0x110
    };

    /**
     * Gets the current playback position, then checks if we are within {@link BeatSender#SLEEP_THRESHOLD} ms before
     * an upcoming beat, or {@link BeatSender#BEAT_THRESHOLD} ms after one, sleeping until that is no longer the case.
     *
     * @return the current playback position, potentially after having delayed a bit so that is not too near a beat
     */
    private Snapshot avoidBeatPacket() {
        Snapshot playState = getPlaybackPosition();
        double distance = playState.distanceFromBeat();
        while (playing.get() &&
                (((distance < 0.0) && (Math.abs(distance) <= BeatSender.SLEEP_THRESHOLD)) ||
                ((distance >= 0.0) && (distance <= (BeatSender.BEAT_THRESHOLD + 1))))) {
            try {
                //noinspection BusyWait
                Thread.sleep(2);
            } catch (InterruptedException e) {
                logger.warn("Interrupted while sleeping to avoid beat packet; ignoring.", e);
            }
            playState = getPlaybackPosition();
            distance = playState.distanceFromBeat();
        }
        return playState;
    }

    /**
     * Send a status packet to all devices on the network. Used when we are actively sending status, presumably so we
     * we can be the tempo master. Avoids sending one within twice {@link BeatSender#BEAT_THRESHOLD} milliseconds of
     * a beat, to make sure the beat packet announces the new beat before an early status packet confuses matters.
     */
    private void sendStatus() {
        final Snapshot playState = avoidBeatPacket();
        final boolean playing = this.playing.get();
        byte[] payload = new byte[STATUS_PAYLOAD.length];
        System.arraycopy(STATUS_PAYLOAD, 0, payload, 0, STATUS_PAYLOAD.length);
        payload[0x02] = getDeviceNumber();
        payload[0x05] = payload[0x02];
        payload[0x08] = (byte)(playing ? 1 : 0);        // a, playing flag
        payload[0x09] = payload[0x02];                  // Dr, the player from which the track was loaded
        payload[0x5c] = (byte)(playing ? 3 : 5);        // P1, playing flag
        Util.numberToBytes(syncCounter.get(), payload, 0x65, 4);
        payload[0x6a] = (byte)(0x84 +                   // F, main status bit vector
                (playing ? 0x40 : 0) + (master.get() ? 0x20 : 0) + (synced.get() ? 0x10 : 0) + (onAir.get() ? 0x08 : 0));
        payload[0x6c] = (byte)(playing ? 0x7a : 0x7e);  // P2, playing flag
        Util.numberToBytes((int)Math.round(getTempo() * 100), payload, 0x73, 2);
        payload[0x7e] = (byte)(playing ? 9 : 1);        // P3, playing flag
        payload[0x7f] = (byte)(master.get() ? 1 : 0);   // Mm, tempo master flag
        payload[0x80] = (byte)nextMaster.get();         // Mh, tempo master handoff indicator
        Util.numberToBytes((int)playState.getBeat(), payload, 0x81, 4);
        payload[0x87] = (byte)(playState.getBeatWithinBar());
        Util.numberToBytes(packetCounter.incrementAndGet(), payload, 0xa9, 4);

        DatagramPacket packet = Util.buildPacket(Util.PacketType.CDJ_STATUS,
                ByteBuffer.wrap(keepAliveBytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH).asReadOnlyBuffer(),
                ByteBuffer.wrap(payload));
        packet.setPort(UPDATE_PORT);
        for (DeviceAnnouncement device : DeviceFinder.getInstance().getCurrentDevices()) {
            packet.setAddress(device.getAddress());
            try {
                socket.get().send(packet);
            } catch (IOException e) {
                logger.warn("Unable to send status packet to " + device, e);
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
     * @return the only instance of this class which exists
     */
    public static VirtualCdj getInstance() {
        return ourInstance;
    }

    /**
     * Register any relevant listeners; private to prevent instantiation.
     */
    private VirtualCdj() {
        masterTempo.set(Double.doubleToLongBits(0.0));  // Note that we have no master tempo yet.

        // Arrange to have our status accurately reflect any relevant updates and commands from the mixer.
        BeatFinder.getInstance().addOnAirListener(new OnAirListener() {
            @Override
            public void channelsOnAir(Set<Integer> audibleChannels) {
                setOnAir(audibleChannels.contains((int)getDeviceNumber()));
            }
        });

        BeatFinder.getInstance().addFaderStartListener(new FaderStartListener() {
            @Override
            public void fadersChanged(Set<Integer> playersToStart, Set<Integer> playersToStop) {
                if (playersToStart.contains((int)getDeviceNumber())) {
                    setPlaying(true);
                } else if (playersToStop.contains((int)getDeviceNumber())) {
                    setPlaying(false);
                }
            }
        });

        BeatFinder.getInstance().addSyncListener(new SyncListener() {
            @Override
            public void setSyncMode(boolean synced) {
                setSynced(synced);
            }

            @Override
            public void becomeMaster() {
                logger.debug("Received packet telling us to become master.");
                if (isSendingStatus()) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                becomeTempoMaster();
                            } catch (Throwable t) {
                                logger.error("Problem becoming tempo master in response to sync command packet", t);
                            }
                        }
                    }).start();
                } else {
                    logger.warn("Ignoring sync command to become tempo master, since we are not sending status packets.");
                }
            }
        });

        BeatFinder.getInstance().addMasterHandoffListener(new MasterHandoffListener() {
            @Override
            public void yieldMasterTo(int deviceNumber) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Received instruction to yield master to device " + deviceNumber);
                }
                if (isTempoMaster()) {
                    if (isSendingStatus() && getDeviceNumber() != deviceNumber) {
                        nextMaster.set(deviceNumber);
                        final DeviceUpdate lastStatusFromNewMaster = getLatestStatusFor(deviceNumber);
                        if (lastStatusFromNewMaster == null) {
                            logger.warn("Unable to send master yield response to device " + deviceNumber + ": no status updates have been received from it!");
                        } else {
                            byte[] payload = new byte[YIELD_ACK_PAYLOAD.length];
                            System.arraycopy(YIELD_ACK_PAYLOAD, 0, payload, 0, YIELD_ACK_PAYLOAD.length);
                            payload[0x02] = getDeviceNumber();
                            payload[0x08] = getDeviceNumber();
                            try {
                                assembleAndSendPacket(Util.PacketType.MASTER_HANDOFF_RESPONSE, payload, lastStatusFromNewMaster.getAddress(), UPDATE_PORT);
                            } catch (Throwable t) {
                                logger.error("Problem sending master yield acknowledgment to player " + deviceNumber, t);
                            }
                        }
                    }
                } else {
                    logger.warn("Ignoring instruction to yield master to device " + deviceNumber + ": we were not tempo master.");

                }
            }

            @Override
            public void yieldResponse(int deviceNumber, boolean yielded) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Received yield response of " + yielded + " from device " + deviceNumber);
                }
                if (yielded) {
                    if (isSendingStatus()) {
                        if (deviceNumber == requestingMasterRoleFromPlayer.get()) {
                            requestingMasterRoleFromPlayer.set(0);
                            masterYieldedFrom.set(deviceNumber);
                        } else {
                            if (requestingMasterRoleFromPlayer.get() == 0) {
                                logger.warn("Ignoring master yield response from player " + deviceNumber +
                                        " because we are not trying to become tempo master.");
                            } else {
                                logger.warn("Ignoring master yield response from player " + deviceNumber +
                                        " because we asked player " + requestingMasterRoleFromPlayer.get());
                            }
                        }
                    } else {
                        logger.warn("Ignoring master yield response because we are not sending status.");
                    }
                } else {
                    logger.warn("Ignoring master yield response with unexpected non-yielding value.");
                }
            }
        });
    }

    /**
     * We have received a packet from a device trying to claim a device number, see if we should defend it.
     *
     * @param packet the packet received
     * @param deviceOffset the index of the byte within the packet holding the device number being claimed
     */
    private void handleDeviceClaimPacket(DatagramPacket packet, int deviceOffset) {
        if (packet.getData().length < deviceOffset + 1) {
            logger.warn("Ignoring too-short device claim packet.");
            return;
        }
        if (isRunning() && getDeviceNumber() == packet.getData()[deviceOffset]) {
            defendDeviceNumber(packet.getAddress());
        }
    }

    /**
     * The {@link DeviceFinder} delegates packets it doesn't know how to deal with to us using this method, because
     * they relate to claiming or defending device numbers, which is our responsibility.
     *
     * @param kind the kind of packet that was received
     * @param packet the actual bytes of the packet
     */
    void handleSpecialAnnouncementPacket(Util.PacketType kind, DatagramPacket packet) {
        if (kind == Util.PacketType.DEVICE_NUMBER_STAGE_1) {
            logger.debug("Received device number claim stage 1 packet.");
        } else if (kind == Util.PacketType.DEVICE_NUMBER_STAGE_2) {
            handleDeviceClaimPacket(packet, 0x2e);
        } else if (kind == Util.PacketType.DEVICE_NUMBER_STAGE_3) {
            handleDeviceClaimPacket(packet, 0x24);
        } else if (kind == Util.PacketType.DEVICE_NUMBER_WILL_ASSIGN) {
            logger.debug("The mixer at address " + packet.getAddress().getHostAddress() + " wants to assign us a specific device number.");
            if (claimingNumber.get() != 0) {
                requestNumberFromMixer(packet.getAddress());
            } else {
                logger.warn("Ignoring mixer device number assignment offer; we are not claiming a device number!");
            }
        } else if (kind == Util.PacketType.DEVICE_NUMBER_ASSIGN) {
            mixerAssigned.set(packet.getData()[0x24]);
            if (mixerAssigned.get() == 0) {
                logger.debug("Mixer at address " + packet.getAddress().getHostAddress() + " told us to use any device.");
            } else {
                logger.info("Mixer at address " + packet.getAddress().getHostAddress() + " told us to use device number " + mixerAssigned.get());
            }
        } else if (kind == Util.PacketType.DEVICE_NUMBER_ASSIGNMENT_FINISHED) {
            mixerAssigned.set(claimingNumber.get());
            logger.info("Mixer confirmed device assignment.");
        } else if (kind == Util.PacketType.DEVICE_NUMBER_IN_USE) {
            final int defendedDevice = packet.getData()[0x24];
            if (defendedDevice == 0) {
                logger.warn("Ignoring unexplained attempt to defend device 0.");
            } else if (defendedDevice == claimingNumber.get()) {
                logger.warn("Another device is defending device number " + defendedDevice + ", so we can't use it.");
                claimRejected.set(true);
            } else if (isRunning()) {
                if (defendedDevice == getDeviceNumber()) {
                    logger.warn("Another device has claimed it owns our device number, shutting down.");
                    stop();
                } else {
                    logger.warn("Another device is defending a number we are not using, ignoring: " + defendedDevice);
                }
            } else {
                logger.warn("Received device number defense message for device number " + defendedDevice +  " when we are not even running!");
            }
        } else {
            logger.warn("Received unrecognized special announcement packet type: " + kind);
        }
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
            sb.append(", isSendingStatus:").append(isSendingStatus());
            if (isSendingStatus()) {
                sb.append(", isSynced:").append(isSynced());
                sb.append(", isTempoMaster:").append(isTempoMaster());
                sb.append(", isPlaying:").append((isPlaying()));
                sb.append(", isOnAir:").append(isOnAir());
                sb.append(", metronome:").append(metronome);
            }
        }
        return sb.append("]").toString();
    }
}
