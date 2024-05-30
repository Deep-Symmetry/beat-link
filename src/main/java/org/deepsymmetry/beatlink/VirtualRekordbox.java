package org.deepsymmetry.beatlink;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.data.OpusProvider;
import org.deepsymmetry.beatlink.data.SlotReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;

import static org.deepsymmetry.beatlink.CdjStatus.TrackSourceSlot.USB_SLOT;

/**
 * Provides the ability to emulate the Rekordbox lighting application which causes devices to share their player state
 * and PSSI (track phrase data). This limited information therefore we enrich it by downloading the Rekordbox USB data
 * using CrateDigger. We can then augment the limited updates from the players to provide more Beat-Link functionality.
 *
 * @author Kris Prep
 */
@API(status = API.Status.EXPERIMENTAL)
public class VirtualRekordbox extends LifecycleParticipant {
    private static final Logger logger = LoggerFactory.getLogger(VirtualRekordbox.class);

    /**
     * The port to which other devices will send status update messages.
     */
    @API(status = API.Status.EXPERIMENTAL)
    public static final int UPDATE_PORT = 50002;

    /**
     * The position within a keep-alive packet at which the MAC address is stored.
     */
    @API(status = API.Status.EXPERIMENTAL)
    public static final int MAC_ADDRESS_OFFSET = 38;

    /**
     * The socket used to receive device status packets while we are active.
     */
    private final AtomicReference<DatagramSocket> socket = new AtomicReference<>();

    /**
     * Check whether we are presently posing as a virtual Rekordbox and receiving device status updates.
     *
     * @return true if our socket is open, sending presence announcements, and receiving status packets
     */
    @API(status = API.Status.EXPERIMENTAL)
    public boolean isRunning() {
        return socket.get() != null && claimingNumber.get() == 0;
    }

    /**
     * Return the address being used by the virtual Rekordbox to send its own presence announcement broadcasts.
     *
     * @return the local address we present to the DJ Link network
     * @throws IllegalStateException if the {@code VirtualCdj} is not active
     */
    @API(status = API.Status.EXPERIMENTAL)
    public InetAddress getLocalAddress() {
        ensureRunning();
        return socket.get().getLocalAddress();
    }

    /**
     * The broadcast address on which we can reach the DJ Link devices. Determined when we start
     * up by finding the network interface address on which we are receiving the other devices'
     * announcement broadcasts.
     */
    private final AtomicReference<InetAddress> broadcastAddress = new AtomicReference<>();

    /**
     * Return the broadcast address used to reach the DJ Link network.
     *
     * @return the address on which packets can be broadcast to the other DJ Link devices
     * @throws IllegalStateException if the {@code VirtualRekordbox} is not active
     */
    @API(status = API.Status.EXPERIMENTAL)
    public InetAddress getBroadcastAddress() {
        ensureRunning();
        return broadcastAddress.get();
    }

    /**
     * Keep track of the most recent updates we have seen, indexed by the address they came from.
     */
    private final Map<DeviceReference, DeviceUpdate> updates = new ConcurrentHashMap<>();

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
    @API(status = API.Status.EXPERIMENTAL)
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
    @API(status = API.Status.EXPERIMENTAL)
    public boolean getUseStandardPlayerNumber() {
        return useStandardPlayerNumber.get();
    }

    /**
     * Get the device number that is used when sending presence announcements on the network to pose as a virtual CDJ.
     * This starts out being zero unless you explicitly assign another value, which means that the <code>VirtualRekordbox</code>
     * should assign itself an unused device number by watching the network when you call
     * {@link #start()}.
     *
     * @return the virtual player number
     */
    @API(status = API.Status.EXPERIMENTAL)
    public synchronized byte getDeviceNumber() {
        return rekordboxKeepAliveBytes[DEVICE_NUMBER_OFFSET];
    }

    /**
     * <p>Set the device number to be used when sending presence announcements on the network to pose as a virtual Rekordbox.
     * Used during the startup process; cannot be set while running. If set to zero, will attempt to claim any free
     * device number, otherwise will try to claim the number specified. If the mixer tells us that we are plugged
     * into a channel-specific Ethernet port, we will honor that and use the device number specified by the mixer.</p>
     *
     * @param number the virtual player number
     * @throws IllegalStateException if we are currently running
     */
    @API(status = API.Status.EXPERIMENTAL)
    public synchronized void setDeviceNumber(byte number) {
        if (isRunning()) {
            throw new IllegalStateException("Can't change device number once started.");
        }
        rekordboxKeepAliveBytes[DEVICE_NUMBER_OFFSET] = number;
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
    @API(status = API.Status.EXPERIMENTAL)
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
    @API(status = API.Status.EXPERIMENTAL)
    public void setAnnounceInterval(int interval) {
        if (interval < 200 || interval > 2000) {
            throw new IllegalArgumentException("Interval must be between 200 and 2000");
        }
        announceInterval.set(interval);
    }

    /**
     * The value that comes in update packet 0x25 for PSSI data.
     */
    private static int METADATA_TYPE_IDENTIFIER_PSSI = 10;

    /**
     * The value that comes in update packet 0x25 once per song change
     */
    private static int METADATA_TYPE_IDENTIFIER_SONG_CHANGE = 1;

    /**
     * Used to construct the keep-alive packet we broadcast in order to participate in the DJ Link network.
     * Some of these bytes are fixed, some get replaced by things like our device name and number, MAC address,
     * and IP address, as described in the
     * <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/startup.html#cdj-keep-alive">Packet Analysis document</a>.
     */
    private static final byte[] rekordboxKeepAliveBytes = {
            0x51, 0x73, 0x70, 0x74,  0x31, 0x57, 0x6d, 0x4a,  0x4f, 0x4c, 0x06, 0x00, 0x72, 0x65, 0x6b, 0x6f, 0x72,
            0x64, 0x62, 0x6f, 0x78,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x03,
            0x00, 0x36, 0x17, 0x01,  0x18, 0x3e, (byte) 0xef, (byte) 0xda, 0x5b, (byte) 0xca, (byte) 0xc0, (byte) 0xa8,
            0x02, 0x0b, 0x04, 0x01,  0x00, 0x00, 0x04, 0x08
    };

    private static final byte[] rekordboxLightingRequestStatusBytes = {
            0x51, 0x73, 0x70, 0x74,  0x31, 0x57, 0x6d, 0x4a,  0x4f, 0x4c, 0x11, 0x72,  0x65, 0x6b, 0x6f, 0x72,
            0x64, 0x62, 0x6f, 0x78,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x01,
            0x01, 0x17, 0x01, 0x04,  0x17, 0x01, 0x00, 0x00,  0x00, 0x6d, 0x00, 0x61,  0x00, 0x63, 0x00, 0x62,
            0x00, 0x6f, 0x00, 0x6f,  0x00, 0x6b, 0x00, 0x20,  0x00, 0x70, 0x00, 0x72,  0x00, 0x6f, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00
    };

    /**
     * The location of the device name in the announcement packet.
     */
    @API(status = API.Status.EXPERIMENTAL)
    public static final int DEVICE_NAME_OFFSET = 0x0c;

    /**
     * The length of the device name in the announcement packet.
     */
    @API(status = API.Status.EXPERIMENTAL)
    public static final int DEVICE_NAME_LENGTH = 0x14;

    /**
     * The location of the device number in the announcement packet.
     */
    @API(status = API.Status.EXPERIMENTAL)
    public static final int DEVICE_NUMBER_OFFSET = 0x24;

    /**
     * Get the name to be used in announcing our presence on the network.
     *
     * @return the device name reported in our presence announcement packets
     */
    @API(status = API.Status.EXPERIMENTAL)
    public static String getDeviceName() {
        return new String(rekordboxKeepAliveBytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH).trim();
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

    private static final byte[] requestPSSIBytes = {
            0x51, 0x73, 0x70, 0x74, 0x31, 0x57, 0x6d, 0x4a, 0x4f, 0x4c, 0x55, 0x72, 0x65, 0x6b, 0x6f, 0x72,
            0x64, 0x62, 0x6f, 0x78, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
            0x00, 0x17, 0x00, 0x08, 0x36, 0x00, 0x00, 0x00, 0x0a, 0x02, 0x03, 0x01
    };

    private static final byte[] deviceName = "rekordbox".getBytes();

    // TODO this (and the arrays above) need JavaDoc.
    @API(status = API.Status.EXPERIMENTAL)
    public void requestPSSI() throws IOException{
        if (DeviceFinder.getInstance().isRunning() && !DeviceFinder.getInstance().getCurrentDevices().isEmpty()) {
            InetAddress address = DeviceFinder.getInstance().getCurrentDevices().iterator().next().getAddress();
            DatagramPacket packet = new DatagramPacket(requestPSSIBytes, requestPSSIBytes.length, address, UPDATE_PORT);

            socket.get().send(packet);
        }
    }

    /**
     * Keeps track of the PSSI bytes we have received for each player.
     */
    private final Map<Integer, ByteBuffer> playerSongStructures = new ConcurrentHashMap<>();

    /**
     * Keeps track of the source slots we've matched to metadata archives for each player.
     */
    private final Map<Integer, SlotReference> playerTrackSourceSlots = new ConcurrentHashMap<>();

    /**
     * Given a player number (normalized to the range 1-4), returns the track source slot associated with the
     * metadata archive that we have matched that player's track to, if any, so we can report it in a meaningful
     * way in {@link CdjStatus} packets.
     *
     * @param player the player whose track we are interested in
     * @return the Opus Quad USB slot that has a metadata archive mounted that matched that player's current track
     */
    SlotReference findMatchedTrackSourceSlotForPlayer(int player) {
        return playerTrackSourceSlots.get(player);
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
                    logger.warn("Processing a Mixer Status packet with unexpected length {}, expected 56 bytes.", length);
                }
                if (length >= 56) {
                    return new MixerStatus(packet);
                } else {
                    logger.warn("Ignoring too-short Mixer Status packet.");
                    return null;
                }

            case CDJ_STATUS:
                if (length >= CdjStatus.MINIMUM_PACKET_SIZE) {
                    CdjStatus status = new CdjStatus(packet);

                    // If source player number is zero the deck does not have a song loaded, clear the PSSI and source slot we had for that player.
                    if (status.getTrackSourcePlayer() == 0) {
                        playerSongStructures.remove(status.getDeviceNumber());
                        playerTrackSourceSlots.remove(status.getDeviceNumber());
                    }
                    return status;
                } else {
                    logger.warn("Ignoring too-short CDJ Status packet with length {} (we need " + CdjStatus.MINIMUM_PACKET_SIZE + " bytes).", length);
                    return null;
                }

            case DEVICE_REKORDBOX_LIGHTING_HELLO_BYTES:
                if (length >= CdjStatus.MINIMUM_PACKET_SIZE) {
                    return new CdjStatus(packet);
                } else {
                    logger.warn("Opus Hello bytes packet.");
                    return null;
                }

            case OPUS_METADATA:
                byte[] data = packet.getData();
                // PSSI Data
                if (data[0x25] == METADATA_TYPE_IDENTIFIER_PSSI) {

                    final int rekordboxId = (int) Util.bytesToNumber(data, 0x28, 4);
                    // Record this song structure so that we can use it for matching tracks in CdjStatus packets.
                    if (rekordboxId != 0) {
                        final ByteBuffer pssiFromOpus = ByteBuffer.wrap(Arrays.copyOfRange(data, 0x35, data.length));
                        final int player = Util.translateOpusPlayerNumbers(data[0x21]);
                        playerSongStructures.put(player, pssiFromOpus);
                        // Also record the conceptual source slot that represents the USB slot from which this track seems to have been loaded
                        // TODO we need to check that the track was loaded from a player, and not rekordbox, as well, before trying to do this!
                        final int sourceSlot = OpusProvider.getInstance().findMatchingUsbSlotForTrack(rekordboxId, player, pssiFromOpus);
                        if (sourceSlot != 0) {  // We found match, record it.
                            playerTrackSourceSlots.put(player, SlotReference.getSlotReference(sourceSlot, USB_SLOT));
                        }
                    }
                } else if (data[0x25] == METADATA_TYPE_IDENTIFIER_SONG_CHANGE) {
                    try {
                        requestPSSI();
                    } catch (IOException e) {
                        logger.warn("Cannot send PSSI request",data[0x25]);
                        return null;
                    }
                    logger.info("data {}",data[0x25]);
                }
                return null;

            default:
                logger.warn("Ignoring {} packet sent to update port.", kind.name);
                return null;
        }
    }

    /**
     * This will send the bytes Rekordbox Lighting sends to a player to acknowledge its existence on the network and
     * trigger it to begin sending CDJStatus packets.
     */
    @API(status = API.Status.EXPERIMENTAL)
    public void sendRekordboxLightingPacket() {
        DatagramPacket updatesAnnouncement = new DatagramPacket(rekordboxLightingRequestStatusBytes, rekordboxLightingRequestStatusBytes.length,
                broadcastAddress.get(), UPDATE_PORT);
        try {
            socket.get().send(updatesAnnouncement);
        } catch (IOException e) {
            logger.warn("Unable to send Rekordbox lighting hello packet. Will try again when next device announces itself.");
        }
    }


    /**
     * Process a device update once it has been received. Track it as the most recent update from its address,
     * and notify any registered listeners, including master listeners if it results in changes to tracked state,
     * such as the current master player and tempo. Also handles the Baroque dance of handing off the tempo master
     * role from or to another device.
     */
    private void processUpdate(DeviceUpdate update) {
        updates.put(DeviceReference.getDeviceReference(update), update);

        deliverDeviceUpdate(update);
    }

    /**
     * The number of milliseconds for which the {@link DeviceFinder} needs to have been watching the network in order
     * for us to be confident we can choose a device number that will not conflict.
     */
    @API(status = API.Status.EXPERIMENTAL)
    private static final long SELF_ASSIGNMENT_WATCH_PERIOD = 4000;

    // TODO: Is any of this stuff needed? If so it needs to be modified to work in the device number range used by rekordbox
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
            // the first one we try is 7 (to accommodate the CDJ-3000, which can use channels 5 and 6).
            if (!getUseStandardPlayerNumber()) {
                claimingNumber.set(6);
            }
        }

        // Record what numbers we have already seen, since there is no point trying one of them.
        Set<Integer> numbersUsed = new HashSet<>();
        for (DeviceAnnouncement device : DeviceFinder.getInstance().getCurrentDevices()) {
            numbersUsed.add(device.getDeviceNumber());
        }

        // Try next available player number less than mixers use.
        final int startingNumber = claimingNumber.get() + 1;
        for (int result = startingNumber; result < 16; result++) {
            if (!numbersUsed.contains(result)) {  // We found one that is not used, so we can use it
                claimingNumber.set(result);
                if (getUseStandardPlayerNumber() && (result > 4)) {
                    logger.warn("Unable to self-assign a standard player number, all are in use. Trying number {}.", result);
                }
                return true;
            }
        }
        logger.warn("Found no unused device numbers between {} and 15, giving up.", startingNumber);
        return false;
    }

    /**
     * Hold the network interfaces which match the address on which we found player traffic. Should only be one,
     * or we will likely receive duplicate packets, which will cause problematic behavior.
     */
    private List<NetworkInterface> matchingInterfaces = null;

    /*
     * Holds the interface address we chose to communicate with the DJ Link device we found during startup,
     * so we can check if there are any unreachable ones.
     */
    private InterfaceAddress matchedAddress = null;


    /**
     * Returns the first device IP address that we found when starting up this class.
     *
     * @return InterfaceAddress of the first device we find.
     */
    @API(status = API.Status.EXPERIMENTAL)
    public InterfaceAddress getMatchedAddress() {
        return matchedAddress;
    }

    /**
     * Check the interfaces that match the address from which we are receiving DJ Link traffic. If there is more
     * than one value in this list, that is a problem because we will likely receive duplicate packets that will
     * play havoc with our understanding of player states.
     *
     * @return the list of network interfaces on which we might receive player packets
     * @throws IllegalStateException if we are not running
     */
    @API(status = API.Status.EXPERIMENTAL)
    public List<NetworkInterface> getMatchingInterfaces() {
        ensureRunning();
        return Collections.unmodifiableList(matchingInterfaces);
    }

    /**
     * If we are in the process of trying to establish a device number, this will hold the number we are
     * currently trying to claim. Otherwise, it will hold the value 0.
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
        Arrays.fill(assignmentRequestBytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH, (byte) 0);
        System.arraycopy(getDeviceName().getBytes(), 0, assignmentRequestBytes, DEVICE_NAME_OFFSET, getDeviceName().getBytes().length);
        System.arraycopy(matchedAddress.getAddress().getAddress(), 0, assignmentRequestBytes, 0x24, 4);
        System.arraycopy(rekordboxKeepAliveBytes, MAC_ADDRESS_OFFSET, assignmentRequestBytes, 0x28, 6);
        // Can't call getDeviceNumber() on next line because that's synchronized!
        assignmentRequestBytes[0x31] = (rekordboxKeepAliveBytes[DEVICE_NUMBER_OFFSET] == 0) ? (byte) 1 : (byte) 2;  // The auto-assign flag.
        assignmentRequestBytes[0x2f] = 1;  // The packet counter.
        try {
            DatagramPacket announcement = new DatagramPacket(assignmentRequestBytes, assignmentRequestBytes.length,
                    mixerAddress, DeviceFinder.ANNOUNCEMENT_PORT);
            logger.debug("Sending device number request to mixer at address {}, port {}", announcement.getAddress().getHostAddress(), announcement.getPort());
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
        Arrays.fill(deviceNumberDefenseBytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH, (byte) 0);
        System.arraycopy(getDeviceName().getBytes(), 0, deviceNumberDefenseBytes, DEVICE_NAME_OFFSET, getDeviceName().getBytes().length);
        deviceNumberDefenseBytes[0x24] = rekordboxKeepAliveBytes[DEVICE_NUMBER_OFFSET];
        System.arraycopy(matchedAddress.getAddress().getAddress(), 0, deviceNumberDefenseBytes, 0x25, 4);
        try {
            DatagramPacket defense = new DatagramPacket(deviceNumberDefenseBytes, deviceNumberDefenseBytes.length,
                    invaderAddress, DeviceFinder.ANNOUNCEMENT_PORT);
            logger.info("Sending device number defense packet to invader at address {}, port {}",
                    defense.getAddress().getHostAddress(), defense.getPort());
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
        Arrays.fill(helloBytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH, (byte) 0);
        System.arraycopy(getDeviceName().getBytes(), 0, helloBytes, DEVICE_NAME_OFFSET, getDeviceName().getBytes().length);
        for (int i = 1; i <= 3; i++) {
            try {
                logger.debug("Sending hello packet {}", i);
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
            Arrays.fill(claimStage1bytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH, (byte) 0);
            System.arraycopy(getDeviceName().getBytes(), 0, claimStage1bytes, DEVICE_NAME_OFFSET, getDeviceName().getBytes().length);
            System.arraycopy(rekordboxKeepAliveBytes, MAC_ADDRESS_OFFSET, claimStage1bytes, 0x26, 6);
            for (int i = 1; i <= 3 && mixerAssigned.get() == 0; i++) {
                claimStage1bytes[0x24] = (byte) i;  // The packet counter.
                try {
                    logger.debug("Sending claim stage 1 packet {}", i);
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
                    logger.warn("Unable to use device number {}, another device has it. Failing to go online.", getDeviceNumber());
                    claimingNumber.set(0);
                    return false;
                }
            }

            // Send the middle series of device claim packets, unless we are interrupted by a defense
            // or a mixer assigning us a specific number.
            Arrays.fill(claimStage2bytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH, (byte) 0);
            System.arraycopy(getDeviceName().getBytes(), 0, claimStage2bytes, DEVICE_NAME_OFFSET, getDeviceName().getBytes().length);
            System.arraycopy(matchedAddress.getAddress().getAddress(), 0, claimStage2bytes, 0x24, 4);
            System.arraycopy(rekordboxKeepAliveBytes, MAC_ADDRESS_OFFSET, claimStage2bytes, 0x28, 6);
            claimStage2bytes[0x2e] = (byte) claimingNumber.get();  // The number we are claiming.
            claimStage2bytes[0x31] = (getDeviceNumber() == 0) ? (byte) 1 : (byte) 2;  // The auto-assign flag.
            for (int i = 1; i <= 3 && mixerAssigned.get() == 0; i++) {
                claimStage2bytes[0x2f] = (byte) i;  // The packet counter.
                try {
                    logger.debug("Sending claim stage 2 packet {} for device {}", i, claimStage2bytes[0x2e]);
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
                    logger.warn("Unable to use device number {}, another device has it. Failing to go online.", getDeviceNumber());
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
            Arrays.fill(claimStage3bytes, DEVICE_NAME_OFFSET, DEVICE_NAME_LENGTH, (byte) 0);
            System.arraycopy(getDeviceName().getBytes(), 0, claimStage3bytes, DEVICE_NAME_OFFSET, getDeviceName().getBytes().length);
            claimStage3bytes[0x24] = (byte) claimingNumber.get();  // The number we are claiming.
            for (int i = 1; i <= 3 && mixerAssigned.get() == 0; i++) {
                claimStage3bytes[0x25] = (byte) i;  // The packet counter.
                try {
                    logger.debug("Sending claim stage 3 packet {} for device {}", i, claimStage3bytes[0x24]);
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
                    logger.warn("Unable to use device number {}, another device has it. Failing to go online.", getDeviceNumber());
                    claimingNumber.set(0);
                    return false;
                }
            }

            claimed = true;  // If we finished all our loops, the number we wanted is ours.
        }
        // Set the device number we claimed.
        rekordboxKeepAliveBytes[DEVICE_NUMBER_OFFSET] = (byte) claimingNumber.getAndSet(0);
        mixerAssigned.set(0);
        return true;  // Huzzah, we found the right device number to use!
    }

    // TODO JavaDoc needed
    @API(status = API.Status.EXPERIMENTAL)
    public void sendRekordboxAnnouncement() {
        if (isRunning()) {
            DatagramPacket announcement = new DatagramPacket(rekordboxKeepAliveBytes, rekordboxKeepAliveBytes.length,
                    broadcastAddress.get(), DeviceFinder.ANNOUNCEMENT_PORT);
            try {
                this.socket.get().send(announcement);
            } catch (IOException e) {
                logger.error("Exception sending announce, trying again.", e);
            }
        }
    }

    /**
     * TODO top-level description needed.
     *
     * @return true if we found DJ Link devices and were able to create the {@code VirtualRekordbox}.
     * @throws Exception if there is a problem opening a socket on the right network
     */
    private boolean createVirtualRekordbox() throws Exception {
        OpusProvider.getInstance().start();

        // Forward Updates to VirtualCdj. That's where all clients are used to getting them.
        addUpdateListener(VirtualCdj.getInstance().getUpdateListener());

        // Find the network interface and address to use to communicate with the first device we found.
        matchingInterfaces = new ArrayList<>();
        matchedAddress = null;
        DeviceAnnouncement announcement = DeviceFinder.getInstance().getCurrentDevices().iterator().next();
        for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            InterfaceAddress candidate = Util.findMatchingAddress(announcement, networkInterface);
            if (candidate != null) {
                if (matchedAddress == null) {
                    matchedAddress = candidate;
                }
                matchingInterfaces.add(networkInterface);
            }
        }

        if (matchedAddress == null) {
            logger.warn("Unable to find network interface to communicate with {}, giving up.", announcement);
            return false;
        }

        logger.info("Found matching network interface {} ({}), will use address {}",
                matchingInterfaces.get(0).getDisplayName(), matchingInterfaces.get(0).getName(), matchedAddress);
        if (matchingInterfaces.size() > 1) {
            for (ListIterator<NetworkInterface> it = matchingInterfaces.listIterator(1); it.hasNext(); ) {
                NetworkInterface extra = it.next();
                logger.warn("Network interface {} ({}) sees same network: we will likely get duplicate DJ Link packets, causing severe problems.",
                        extra.getDisplayName(), extra.getName());
            }
        }

        // Open our communication socket.
        socket.set(new DatagramSocket(UPDATE_PORT, matchedAddress.getAddress()));

        System.arraycopy(getMatchingInterfaces().get(0).getHardwareAddress(),
                0, rekordboxKeepAliveBytes, MAC_ADDRESS_OFFSET, 6);
        System.arraycopy(matchedAddress.getAddress().getAddress(),
                0, rekordboxKeepAliveBytes, 44, 4);
        System.arraycopy(getMatchingInterfaces().get(0).getHardwareAddress(),
                0, rekordboxLightingRequestStatusBytes, MAC_ADDRESS_OFFSET, 6);
        System.arraycopy(matchedAddress.getAddress().getAddress(),
                0, rekordboxLightingRequestStatusBytes, 44, 4);

        // Copy the chosen interface's hardware and IP addresses into the announcement packet template
        broadcastAddress.set(matchedAddress.getBroadcast());

        // Inform the DeviceFinder to ignore our own Rekordbox Lighting announcement broadcast packets.
        DeviceFinder.getInstance().addIgnoredAddress(matchedAddress.getBroadcast());
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
        final byte[] buffer = new byte[512];
        final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        // Create the update reception thread
        Thread receiver = new Thread(null, () -> {
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
        }, "beat-link VirtualRekordbox status receiver");
        receiver.setDaemon(true);
        receiver.setPriority(Thread.MAX_PRIORITY);
        receiver.start();


        // Create the thread which announces our participation in the DJ Link network, to request update packets
        Thread announcer = new Thread(null, () -> {
            while (isRunning()) {
                sendAnnouncements();
            }
        }, "beat-link VirtualRekordbox announcement/updates sender");
        announcer.setDaemon(true);
        announcer.start();

        // Inform the DeviceFinder to ignore our own broadcast Rekordbox announcement packets.
        DeviceFinder.getInstance().addIgnoredAddress(matchedAddress.getBroadcast());

        deliverLifecycleAnnouncement(logger, true);

        return true;
    }

    /**
     * Send an announcement packets so that devices see us as Rekordbox Lighting and send us updates.
     */
    private void sendAnnouncements() {
        try {
            sendRekordboxAnnouncement();
            sendRekordboxLightingPacket();

            Thread.sleep(getAnnounceInterval());
        } catch (Throwable t) {
            logger.warn("Unable to send announcement packets, flushing DeviceFinder due to likely network change and shutting down.", t);
            DeviceFinder.getInstance().flush();
            stop();
        }
    }

    /**
     * Makes sure we get shut down if the {@link DeviceFinder} does, because we rely on it.
     */
    private final LifecycleListener deviceFinderLifecycleListener = new LifecycleListener() {
        @Override
        public void started(LifecycleParticipant sender) {
            logger.debug("VirtualRekordbox doesn't have anything to do when the DeviceFinder starts");
        }

        @Override
        public void stopped(LifecycleParticipant sender) {
            if (isRunning()) {
                logger.info("VirtualRekordbox stopping because DeviceFinder has stopped.");
                stop();
            }
        }
    };

    /**
     * Start announcing ourselves and listening for status packets. If already active, has no effect. Requires the
     * {@link DeviceFinder} to be active in order to find out how to communicate with other devices, so will start
     * that if it is not already. Only accessible within the package because {@link VirtualCdj} is responsible for
     * starting and stopping this service when it detects an Opus Quad.
     *
     * @return true if we found DJ Link devices and were able to create the {@code VirtualRekordbox}, or it was already running.
     * @throws Exception if the socket to listen on port 50002 cannot be created
     */
    @API(status = API.Status.EXPERIMENTAL)
    synchronized boolean start() throws Exception {
        if (!isRunning()) {

            // Set up so we know we have to shut down if the DeviceFinder shuts down.
            DeviceFinder.getInstance().addLifecycleListener(deviceFinderLifecycleListener);

            // Find some DJ Link devices, so we can figure out the interface and address to use to talk to them
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

            return createVirtualRekordbox();
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
     * @return true if we found DJ Link devices and were able to create the {@code VirtualRekordbox}, or it was already running.
     * @throws SocketException if the socket to listen on port 50002 cannot be created
     */
    synchronized boolean start(byte deviceNumber) throws Exception {
        // TODO I am not sure we actually need this method. If we do want to control the device number that is used,
        //      we will need to add a mechanism do that from VirtualCdj.
        if (!isRunning()) {
            setDeviceNumber(deviceNumber);
            return start();
        }
        return true;  // We are already running.
    }

    /**
     * Stop announcing ourselves and listening for status updates.
     */
    synchronized void stop() {
        if (isRunning()) {
            DeviceFinder.getInstance().removeIgnoredAddress(socket.get().getLocalAddress());
            socket.get().close();
            socket.set(null);
            broadcastAddress.set(null);
            updates.clear();
            setDeviceNumber((byte) 0);  // Set up for self-assignment if restarted.
            deliverLifecycleAnnouncement(logger, false);
        }
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
     * @return the matching detailed status update or null if none have been received
     * @throws IllegalStateException if the {@code VirtualRekordbox} is not active
     */
    @API(status = API.Status.EXPERIMENTAL)
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
     * Keeps track of the registered device update listeners.
     */
    private final Set<DeviceUpdateListener> updateListeners = Collections.newSetFromMap(new ConcurrentHashMap<>());

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
    @API(status = API.Status.EXPERIMENTAL)
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
    @API(status = API.Status.EXPERIMENTAL)
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
    @API(status = API.Status.EXPERIMENTAL)
    public Set<DeviceUpdateListener> getUpdateListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Set.copyOf(updateListeners);
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

    /**
     * Holds the singleton instance of this class.
     */
    private static final VirtualRekordbox ourInstance = new VirtualRekordbox();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists
     */
    @API(status = API.Status.EXPERIMENTAL)
    public static VirtualRekordbox getInstance() {
        return ourInstance;
    }

    /**
     * Register any relevant listeners; private to prevent instantiation.
     */
    private VirtualRekordbox() {
    }

    /**
     * We have received a packet from a device trying to claim a device number, see if we should defend it.
     *
     * @param packet       the packet received
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
     * @param kind   the kind of packet that was received
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
            logger.debug("The mixer at address {} wants to assign us a specific device number.", packet.getAddress().getHostAddress());
            if (claimingNumber.get() != 0) {
                requestNumberFromMixer(packet.getAddress());
            } else {
                logger.warn("Ignoring mixer device number assignment offer; we are not claiming a device number!");
            }
        } else if (kind == Util.PacketType.DEVICE_NUMBER_ASSIGN) {
            mixerAssigned.set(packet.getData()[0x24]);
            if (mixerAssigned.get() == 0) {
                logger.debug("Mixer at address {} told us to use any device.", packet.getAddress().getHostAddress());
            } else {
                logger.info("Mixer at address {} told us to use device number {}", packet.getAddress().getHostAddress(), mixerAssigned.get());
            }
        } else if (kind == Util.PacketType.DEVICE_NUMBER_ASSIGNMENT_FINISHED) {
            mixerAssigned.set(claimingNumber.get());
            logger.info("Mixer confirmed device assignment.");
        } else if (kind == Util.PacketType.DEVICE_NUMBER_IN_USE) {
            final int defendedDevice = packet.getData()[0x24];
            if (defendedDevice == 0) {
                logger.warn("Ignoring unexplained attempt to defend device 0.");
            } else if (defendedDevice == claimingNumber.get()) {
                logger.warn("Another device is defending device number {}, so we can't use it.", defendedDevice);
                claimRejected.set(true);
            } else if (isRunning()) {
                if (defendedDevice == getDeviceNumber()) {
                    logger.warn("Another device has claimed it owns our device number, shutting down.");
                    stop();
                } else {
                    logger.warn("Another device is defending a number we are not using, ignoring: {}", defendedDevice);
                }
            } else {
                logger.warn("Received device number defense message for device number {} when we are not even running!", defendedDevice);
            }
        } else {
            logger.warn("Received unrecognized special announcement packet type: {}", kind);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("VirtualRekordbox[number:").append(getDeviceNumber()).append(", name:").append(getDeviceName());
        sb.append(", announceInterval:").append(getAnnounceInterval());
        sb.append(", useStandardPlayerNumber:").append(getUseStandardPlayerNumber());
        sb.append(", active:").append(isRunning());
        if (isRunning()) {
            sb.append(", localAddress:").append(getLocalAddress().getHostAddress());
            sb.append(", broadcastAddress:").append(getBroadcastAddress().getHostAddress());
        }
        return sb.append("]").toString();
    }
}
