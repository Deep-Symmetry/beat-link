package org.deepsymmetry.beatlink;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.data.OpusProvider;
import org.deepsymmetry.beatlink.data.SlotReference;
import org.deepsymmetry.beatlink.data.OpusProvider.DeviceSqlRekordboxIdAndSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
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
        return socket.get() != null;
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
     * Get the device number that is used when sending presence announcements on the network to pose as a virtual rekordbox.
     * This value is not meaningful until the <code>VirtualRekordbox</code> is running and has found an available number
     * to use.
     *
     * @return the virtual player number
     */
    @API(status = API.Status.EXPERIMENTAL)
    public synchronized byte getDeviceNumber() {
        return rekordboxKeepAliveBytes[DEVICE_NUMBER_OFFSET];
    }

    /**
     * <p>Set the device number to be used when sending presence announcements on the network to pose as a virtual Rekordbox.
     * Used during the startup process; cannot be set while running. If we find this device number is in use, we will pick
     * another number in the range 0x13 to 0x27.</p>
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
     * The value that comes in update packet[0x25] for PSSI data.
     */
    private static final int METADATA_TYPE_IDENTIFIER_PSSI = 10;

    /**
     * The value that comes in update packet[0x25] once per song change
     */
    private static final int METADATA_TYPE_IDENTIFIER_SONG_CHANGE = 1;

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

    /**
     * Used to construct the packet we send to request lighting information from the Opus Quad, which is how we
     * can figure out what tracks it is playing.
     */
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
     * Packet used to tell Opus device we want PSSI data once for all players with loaded songs.
     */
    private static final byte[] requestPSSIBytes = {
            0x51, 0x73, 0x70, 0x74, 0x31, 0x57, 0x6d, 0x4a, 0x4f, 0x4c, 0x55, 0x72, 0x65, 0x6b, 0x6f, 0x72,
            0x64, 0x62, 0x6f, 0x78, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
            0x00, 0x17, 0x00, 0x08, 0x36, 0x00, 0x00, 0x00, 0x0a, 0x02, 0x03, 0x01
    };

    /**
     * This imitates the request RekordboxLighting sends to get PSSI data from Opus Quad device
     * (and maybe more devices in the future).
     *
     * @throws IOException if there is a problem sending the request
     */
    @API(status = API.Status.EXPERIMENTAL)
    public void requestPSSI() throws IOException{
        if (DeviceFinder.getInstance().isRunning() && !DeviceFinder.getInstance().getCurrentDevices().isEmpty()) {
            InetAddress address = DeviceFinder.getInstance().getCurrentDevices().iterator().next().getAddress();
            DatagramPacket packet = new DatagramPacket(requestPSSIBytes, requestPSSIBytes.length, address, UPDATE_PORT);

            socket.get().send(packet);
        }
    }

    /**
     * Keeps track of the source slots we've matched to metadata archives for each player.
     */
    private final Map<Integer, SlotReference> playerTrackSourceSlots = new ConcurrentHashMap<>();

    /**
     * Keeps track of the current DeviceSQL rekordbox ID for each player.
     * See {@link #findDeviceSqlRekordboxIdForPlayer(int)} for more details.
     */
    private final Map<Integer, Integer> playerToDeviceSqlRekordboxId = new ConcurrentHashMap<>();

    /**
     * Clear both player caches so that we can reload the data. This usually happens when we load an archive
     * in OpusProvider.
     *
     * @param usbSlotNumber the slot we have the archive loaded in
     */
    @API(status = API.Status.EXPERIMENTAL)
    public void clearPlayerCaches(int usbSlotNumber){
        playerTrackSourceSlots.remove(usbSlotNumber);
    }

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
     * Given a player number (normalized to the range 1-4), returns the DeviceSQL rekordbox ID for the
     * track that is currently loaded on that player. Returns 0 if we are searching for a PSSI match still on a new track load,
     * or if a PSSI match wasn't found, or if the player does not have a track loaded.
     *
     * @param player the player whose DeviceSQL rekordbox ID we are interested in
     * @return the DeviceSQL rekordbox ID for the track that is currently loaded on that player
     */
    int findDeviceSqlRekordboxIdForPlayer(int player) {
        return playerToDeviceSqlRekordboxId.getOrDefault(player, 0);
    }

    /**
     * Keeps track of the most recent valid (non-zero) status flag byte we have received from each device number,
     * so we can reuse it in cases where a corrupt (zero) value has been sent to us.
     */
    private final Map<Integer, Byte> lastValidStatusFlagBytes = new ConcurrentHashMap<>();

    /**
     * Tracks packets received from devices to reconstruct complete messages that span multiple packets.
     * Used primarily for reconstructing binary data (specifically PSSI) from the Opus Quad, which 
     * arrives fragmented across multiple packets. Maintains state between packet
     * arrivals until a complete message is assembled, at which point the data can be processed.
     */
    private static class PacketTracker {
        private final List<Byte> data;

        PacketTracker() {
            this.data = new ArrayList<>();
        }

        /**
         * Receives and processes a new packet in the sequence.
         *
         * @param packetData the data from the new packet
         * @param packetNumber which packet this is in the sequence
         * @param totalPackets how many packets make up the complete message
         * @return true if this was the final packet in the sequence
         */
        boolean receivePacket(byte[] packetData, int packetNumber, int totalPackets) {
            for (byte b : packetData) {
                data.add(b);
            }

            if (packetNumber == totalPackets) {
                return true;
            }
            return false;
        }

        /**
         * Reset the tracker state
         */
        void resetForNewPacketStream() {
            data.clear();
        }

        /**
         * Convert the accumulated data to a byte array, trimming trailing zeros.
         * We are not sure if the OPUS_METADATA packets send the length of the entire PSSI back,
         * so for now we just trim the trailing zeros.
         *
         * @return the complete message data with trailing zeros removed
         */
        byte[] getDataAsBytesAndTrimTrailingZeros() {
            // First convert to regular byte array
            byte[] result = new byte[data.size()];
            for (int i = 0; i < data.size(); i++) {
                result[i] = data.get(i);
            }

            // Find last non-zero byte
            int lastNonZero = result.length - 1;
            while (lastNonZero >= 0 && result[lastNonZero] == 0) {
                lastNonZero--;
            }

            // If we found trailing zeros, create a new trimmed array
            if (lastNonZero < result.length - 1) {
                return Arrays.copyOf(result, lastNonZero + 1);
            }

            return result;
        }
    }

    /**
     * Packet trackers for each player.
     */
    private final Map<Integer, PacketTracker> playerPacketTrackers = new ConcurrentHashMap<>();

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
            logger.debug("Ignoring unrecognized packet sent to update port.");  // validateHeader will already warn once
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
                    // Try to recover from a malformed Opus Quad packet with a zero value in its status flags by reusing the last valid one we saw from the same device.
                    final byte reportedStatusFlags = packet.getData()[CdjStatus.STATUS_FLAGS];
                    final boolean hadToRecoverStatusFlags = (reportedStatusFlags == 0);
                    final int rawDeviceNumber = packet.getData()[CdjStatus.DEVICE_NUMBER_OFFSET];
                    if (hadToRecoverStatusFlags) {
                        final Byte recoveredStatusFlags = lastValidStatusFlagBytes.get(rawDeviceNumber);
                        if (recoveredStatusFlags != null) {
                            packet.getData()[CdjStatus.STATUS_FLAGS] = recoveredStatusFlags;
                        } else {
                            logger.warn("Unable to recover from malformed Opus Quad status packet because we have not yet received a valid packet from device {}.",
                                    rawDeviceNumber);
                            return null;  // Discard this packet.
                        }
                    } else {
                        lastValidStatusFlagBytes.put(rawDeviceNumber, reportedStatusFlags);  // Record in case next packet for this device is malformed.
                    }

                    CdjStatus status = new CdjStatus(packet, hadToRecoverStatusFlags);

                    // If source player number is zero the deck does not have a song loaded, clear the PSSI and source slot we had for that player.
                    // This also represents a new track load.
                    // We also need to set the DeviceSQL rekordbox ID to 0 for the player, so we don't
                    // reflect the old track right when the new track is loaded (it takes a few seconds to match the PSSI).
                    if (status.getTrackSourcePlayer() == 0) {
                        playerTrackSourceSlots.remove(status.getDeviceNumber());
                        playerToDeviceSqlRekordboxId.remove(status.getDeviceNumber());
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
                    logger.debug("Opus Hello bytes packet.");
                    return null;
                }

            case OPUS_METADATA:
                byte[] data = packet.getData();
                final int packetLength = packet.getLength();
                final byte[] binaryData = Arrays.copyOfRange(data, 0x34, packetLength);
                final int playerNumber = Util.translateOpusPlayerNumbers(data[0x21]);
                final int rekordboxIdFromOpus = (int) Util.bytesToNumber(data, 0x28, 4);

                // Get or create tracker for this player
                PacketTracker tracker = playerPacketTrackers.computeIfAbsent(playerNumber, k -> new PacketTracker());

                // PSSI Data
                if (data[0x25] == METADATA_TYPE_IDENTIFIER_PSSI) {
                    final int totalPackets = data[0x33] - 1;
                    final int packetNumber = data[0x31];
                    
                    boolean dataComplete = tracker.receivePacket(binaryData, packetNumber, totalPackets);

                    // Process the packet
                    if (dataComplete) {
                        // We have a complete PSSI message
                        final byte[] pssiFromOpus = tracker.getDataAsBytesAndTrimTrailingZeros();

                        // Reset the packet tracker since we have the required data now
                        tracker.resetForNewPacketStream();

                        // Get the actual rekordbox DeviceSQL ID and slot number
                        final DeviceSqlRekordboxIdAndSlot match = OpusProvider.getInstance().getDeviceSqlRekordboxIdAndSlotNumberFromPssi(pssiFromOpus, rekordboxIdFromOpus);

                        // Record this song structure for matching tracks in CdjStatus packets
                        if (match != null) {
                            playerToDeviceSqlRekordboxId.put(playerNumber, match.getRekordboxId());
                            playerTrackSourceSlots.put(playerNumber, SlotReference.getSlotReference(match.getUsbSlot(), USB_SLOT));
                        }
                    }
                } else if (data[0x25] == METADATA_TYPE_IDENTIFIER_SONG_CHANGE) {
                    try {
                        requestPSSI();
                    } catch (IOException e) {
                        logger.warn("Cannot send PSSI request");
                    }
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

    /**
     * <p>Choose a device number, which we have not seen on the network.
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

        // Record what numbers we have already seen, since there is no point trying one of them.
        Set<Integer> numbersUsed = new HashSet<>();
        for (DeviceAnnouncement device : DeviceFinder.getInstance().getCurrentDevices()) {
            numbersUsed.add(device.getDeviceNumber());
        }

        // If we are able to use the number we were configures to use before startup, great!
        if (!numbersUsed.contains((int) getDeviceNumber())) {
            return true;
        }

        // Try finding an unused player number higher than two rekordbox laptops would use, and less than rekordbox mobile uses.
        for (int result = 0x13; result < 0x28; result++) {
            if (!numbersUsed.contains(result)) {  // We found one that is not used, so we can use it
                setDeviceNumber((byte) result);
                return true;
            }
        }
        logger.warn("Found no unused device numbers between 0x13 and 0x27, giving up.");
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
     * This will send the announcement that makes players think that they are talking to rekordbox.
     * After we send these announcement packets other players will start to send out status packets.
     * We need to send these every second or two otherwise we will be disconnected from the Pro DJ Link
     * network.
     */
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
     * This method will start up all the required pieces to emulate Rekordbox Lighting to pioneer devices on the
     * network. This is not as powerful as emulating a CDJ, as that will get most Pro DJ Link devices to become
     * very chatty, but rather this is for devices that don't support Pro DJ Link properly but can be coaxed to
     * send status packets when they talk to RekordboxLighting (the Opus Quad being the only device at the time
     * of coding this).
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

        // Determine a device number we can use.
        if (!selfAssignDeviceNumber()) {
            // We couldn't get a device number, so clean up and report failure.
            logger.warn("Unable to find an unused a device number for the Virtual recordbox, giving up.");
            DeviceFinder.getInstance().removeIgnoredAddress(socket.get().getLocalAddress());
            socket.get().close();
            socket.set(null);
            return false;
        }

        // Set up our buffer and packet to receive incoming messages.
        createStatusReceiver().start();

        // Create the thread which announces our participation in the DJ Link network, to request update packets
        final Thread announcer = new Thread(null, () -> {
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
     * Create a thread that will wait for and process status update packets sent to our socket.
     *
     * @return the thread
     */
    private Thread createStatusReceiver() {
        final byte[] buffer = new byte[1420];
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
        return receiver;
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
     * Stop announcing ourselves and listening for status updates.
     */
    @API(status = API.Status.EXPERIMENTAL)
    synchronized void stop() {
        if (isRunning()) {
            DeviceFinder.getInstance().removeIgnoredAddress(socket.get().getLocalAddress());
            socket.get().close();
            socket.set(null);
            broadcastAddress.set(null);
            updates.clear();
            lastValidStatusFlagBytes.clear();
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

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("VirtualRekordbox[number:").append(getDeviceNumber()).append(", name:").append(getDeviceName());
        sb.append(", announceInterval:").append(getAnnounceInterval());
        sb.append(", active:").append(isRunning());
        if (isRunning()) {
            sb.append(", localAddress:").append(getLocalAddress().getHostAddress());
            sb.append(", broadcastAddress:").append(getBroadcastAddress().getHostAddress());
        }
        return sb.append("]").toString();
    }
}
