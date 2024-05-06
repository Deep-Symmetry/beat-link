package org.deepsymmetry.beatlink;

import java.awt.*;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz.SongStructureEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides utility functions.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class Util {

    private static final Logger logger = LoggerFactory.getLogger(Util.class);

    /**
     * The sequence of ten bytes which begins all UDP packets sent in the protocol.
     */
    private static final byte[] MAGIC_HEADER = {0x51, 0x73, 0x70, 0x74, 0x31, 0x57, 0x6d, 0x4a, 0x4f, 0x4c};

    /**
     * Get the sequence of nine bytes which begins all UDP packets sent in the protocol as a {@link ByteBuffer}.
     * Each call returns a new instance, so you don't need to worry about messing with buffer positions.
     *
     * @return a read-only {@link ByteBuffer} containing the header with which all protocol packets begin.
     */
    public static ByteBuffer getMagicHeader() {
        return ByteBuffer.wrap(MAGIC_HEADER).asReadOnlyBuffer();
    }

    /**
     * The offset into protocol packets which identify the content of the packet.
     */
    public static final int PACKET_TYPE_OFFSET = 0x0a;

    /**
     * The known packet types used in the protocol, along with the byte values
     * which identify them, and the names by which we describe them, and the port
     * on which they are received.
     */
    public enum PacketType {

        /**
         * Used by the mixer to tell a set of players to start and/or stop playing.
         */
        FADER_START_COMMAND(0x02, "Fader Start", BeatFinder.BEAT_PORT),

        /**
         * Used by the mixer to tell the players which channels are on and off the air.
         */
        CHANNELS_ON_AIR(0x03, "Channels On Air", BeatFinder.BEAT_PORT),

        /**
         * Used to ask a player for information about the media mounted in a slot.
         */
        MEDIA_QUERY(0x05, "Media Query", VirtualCdj.UPDATE_PORT),

        /**
         * The response sent when a Media Query is received.
         */
        MEDIA_RESPONSE(0x06, "Media Response", VirtualCdj.UPDATE_PORT),

        /**
         * An initial series of three of these packets is sent when a device first joins the network, at 300ms
         * intervals.
         */
        DEVICE_HELLO(0x0a, "Device Hello", DeviceFinder.ANNOUNCEMENT_PORT),

        /**
         * These are the bytes that all in one players like the Opus-Quad send to announce to Rekordbox Lighting that
         * it is available on the network. Once VirtualRekordbox sends its hello message to the player, it stops sending
         * these and starts sending CdjStatus updates.
         */
        DEVICE_REKORDBOX_LIGHTING_HELLO_BYTES(0x10, "Rekordbox Lighting Hello Bytes", VirtualCdj.UPDATE_PORT),


        /**
         * A series of three of these is sent at 300ms intervals when a device is starting to establish its
         * device number.
         */
        DEVICE_NUMBER_STAGE_1(0x00, "Device Number Claim Stage 1", DeviceFinder.ANNOUNCEMENT_PORT),

        /**
         * This packet is sent by a mixer directly to a device which has just sent a device number self-assignment
         * packet when that device is plugged into a channel-specific Ethernet jack on the mixer (or XDJ-XZ) to let
         * the device know the sender of this packet is responsible for assigning its number.
         */
        DEVICE_NUMBER_WILL_ASSIGN(0x01, "Device Number Will Be Assigned", DeviceFinder.ANNOUNCEMENT_PORT),

        /**
         * A second series of three packets sent at 300ms intervals when the device is claiming its device number.
         */
        DEVICE_NUMBER_STAGE_2(0x02, "Device Number Claim Stage 2", DeviceFinder.ANNOUNCEMENT_PORT),

        /**
         * This packet is sent by a mixer (or XDJ-XZ) when a player has acknowledged that it is ready to be assigned
         * the device number that belongs to the jack to which it is connected.
         */
        DEVICE_NUMBER_ASSIGN(0x03, "Device Number Assignment", DeviceFinder.ANNOUNCEMENT_PORT),

        /**
         * Third and final series of three packets sent at 300ms intervals when a device is claiming its device number.
         * If the device is configured to use a specific number, only one is sent.
         */
        DEVICE_NUMBER_STAGE_3(0x04, "Device Number Claim Stage 3", DeviceFinder.ANNOUNCEMENT_PORT),

        /**
         * This packet is sent by a mixer (or XDJ-XZ) once it sees that device number assignment has concluded
         * successfully, to the player plugged into a channel-specific jack.
         */
        DEVICE_NUMBER_ASSIGNMENT_FINISHED(0x05, "Device Number Assignment Finished", DeviceFinder.ANNOUNCEMENT_PORT),

        /**
         * Used to report that a device is still present on the DJ Link network.
         */
        DEVICE_KEEP_ALIVE(0x06, "Device Keep-Alive", DeviceFinder.ANNOUNCEMENT_PORT),

        /**
         * Used to defend a device number that is already in use.
         */
        DEVICE_NUMBER_IN_USE(0x08, "Device Number In Use", DeviceFinder.ANNOUNCEMENT_PORT),

        /**
         * A status update from a player, with a great many status flags, pitch, tempo, and beat-within-bar details.
         * Sadly, the same number is used (on port 50000) as part of the CDJ startup process.
         */
        CDJ_STATUS(0x0a, "CDJ Status", VirtualCdj.UPDATE_PORT),

        /**
         * Metadata that includes track album art, possibly waveforms and more. We do not use this information at the moment
         * because it is not complete enough to support all of the Beat Link Trigger functionality. Instead, we download
         * the track data from a Rekordbox USB.
         */
        OPUS_METADATA(0x56, "OPUS Metadata", VirtualCdj.UPDATE_PORT),

        /**
         * A command to load a particular track; usually sent by rekordbox.
         */
        LOAD_TRACK_COMMAND(0x19, "Load Track Command", VirtualCdj.UPDATE_PORT),

        /**
         * A response indicating that the specified track is being loaded.
         */
        LOAD_TRACK_ACK(0x1a, "Load Track Acknowledgment", VirtualCdj.UPDATE_PORT),

        /**
         * Used by an incoming tempo master to ask the current tempo master to relinquish that role.
         */
        MASTER_HANDOFF_REQUEST(0x26, "Master Handoff Request", BeatFinder.BEAT_PORT),

        /**
         * Used by the active tempo master to respond to a request to relinquish that role.
         */
        MASTER_HANDOFF_RESPONSE(0x27, "Master Handoff Response", BeatFinder.BEAT_PORT),

        /**
         * Announces a beat has been played in a rekordbox-analyzed track, with lots of useful synchronization
         * information.
         */
        BEAT(0x28, "Beat", BeatFinder.BEAT_PORT),

        /**
         * Reports the exact playback position of a CDJ-3000 or newer player, on a very frequent basis,
         * even if it is not currently playing.
         */
        PRECISE_POSITION(0x0b, "Precise Position", BeatFinder.BEAT_PORT),

        /**
         * A status update from the mixer, with status flags, pitch, and tempo, and beat-within-bar information.
         */
        MIXER_STATUS(0x29, "Mixer Status", VirtualCdj.UPDATE_PORT),

        /**
         * Used to tell a player to turn sync on or off, or that it should become the tempo master.
         */
        SYNC_CONTROL(0x2a, "Sync Control", BeatFinder.BEAT_PORT),

        /**
         * A command to apply settings to a player; usually sent by rekordbox
         */
        LOAD_SETTINGS_COMMAND(0x34, "Load Settings Command", VirtualCdj.UPDATE_PORT);

        /**
         * The value that appears in the type byte which identifies this type of packet.
         */
        public final byte protocolValue;

        /**
         * The name by which we describe this kind of packet.
         */
        public final String name;

        /**
         * The port on which this kind of packet is received.
         */
        public final int port;

        /**
         * Constructor simply sets the protocol value and name.
         *
         * @param value the value that appears in the type byte which identifies this type of packet
         * @param name how we describe this kind of packet
         * @param port the port number on which this kind of packet is received
         */
        PacketType(int value, String name, int port) {
            protocolValue = (byte)value;
            this.name = name;
            this.port = port;
        }
    }

    /**
     * Allows a known packet type to be looked up given the port number it was received on and the packet type byte.
     */
    public static final Map<Integer, Map<Byte, PacketType>> PACKET_TYPE_MAP;
    static {
        Map<Integer, Map<Byte, PacketType>> scratch = new HashMap<Integer, Map<Byte, PacketType>>();
        for (PacketType packetType : PacketType.values()) {
            Map<Byte, PacketType> portMap = scratch.get(packetType.port);
            if (portMap == null) {
                portMap = new HashMap<Byte, PacketType>();
                scratch.put(packetType.port, portMap);
            }
            portMap.put(packetType.protocolValue, packetType);
        }
        for (Map.Entry<Integer, Map<Byte, PacketType>> entry : scratch.entrySet()) {
            scratch.put(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
        }
        PACKET_TYPE_MAP = Collections.unmodifiableMap(scratch);
    }

    /**
     * Build a standard-format UDP packet for sending to port 50001 or 50002 in the protocol.
     *
     * @param type the type of packet to create.
     * @param deviceName the 0x14 (twenty) bytes of the device name to send in the packet.
     * @param payload the remaining bytes which come after the device name.
     * @return the packet to send.
     */
    public static DatagramPacket buildPacket(PacketType type, ByteBuffer deviceName, ByteBuffer payload) {
        ByteBuffer content = ByteBuffer.allocate(0x1f + payload.remaining());
        content.put(getMagicHeader());
        content.put(type.protocolValue);
        content.put(deviceName);
        content.put(payload);
        return new DatagramPacket(content.array(), content.capacity());
    }

    /**
     * Helper function to create the payload for a packet being sent to port 50001 or 50002,
     * while working with the byte addresses shown in the protocol analysis document (adjusts
     * the address to account for the fact that the standard packet header up through the device
     * name has not yet been prepended).
     *
     * @param payload the array in which the packet payload is being built
     * @param address the overall packet address (taking account the header) of the byte to be set
     * @param value the byte value to store at that packet address
     */
    public static void setPayloadByte(byte[] payload, int address, byte value) {
        payload[address - 0x1f] = value;
    }

    /**
     * Used to keep track of when we report seeing a packet to an unexpected port, so we only do that once.
     */
    private static final Set<Integer> unknownPortsReported = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    /**
     * Used to keep track of when we report seeing a packet of an unknown type reported to a port, so we do that only once.
     */
    private static final Map<Integer, Set<Byte>> unknownPortTypesReported = new ConcurrentHashMap<Integer, Set<Byte>>();

    /**
     * Check to see whether a packet starts with the standard header bytes, followed by a known byte identifying it.
     * If so, return the kind of packet that has been recognized.
     *
     * @param packet a packet that has just been received
     * @param port the port on which the packet has been received
     *
     * @return the type of packet that was recognized, or {@code null} if the packet was not recognized
     */
    public static PacketType validateHeader(DatagramPacket packet, int port) {
        byte[] data = packet.getData();

        if (data.length < PACKET_TYPE_OFFSET) {
            logger.warn("Packet is too short to be a Pro DJ Link packet; must be at least " + PACKET_TYPE_OFFSET +
                    " bytes long, was only " + data.length + ".");
            return null;
        }

        if (!getMagicHeader().equals(ByteBuffer.wrap(data, 0, MAGIC_HEADER.length))) {
            logger.warn("Packet did not have correct ten-byte header for the Pro DJ Link protocol.");
            return null;
        }

        final Map<Byte, PacketType> portMap = PACKET_TYPE_MAP.get(port);
        if (portMap == null) {  // Warn about unrecognized port, once, and return null for packet type.
            if (!unknownPortsReported.contains(port)) {
                logger.warn("Do not know any Pro DJ Link packets that are received on port " + port +
                        " (this will be reported only once).");
                unknownPortsReported.add(port);
            }
            return null;
        }

        final PacketType result = portMap.get(data[PACKET_TYPE_OFFSET]);
        if (result == null) {  // Warn about unrecognized type, once.
            Set<Byte> typesReportedForPort = unknownPortTypesReported.get(port);
            if (typesReportedForPort == null) {  // First problem we have seen for this port, set up set for it.
                typesReportedForPort = Collections.newSetFromMap(new ConcurrentHashMap<Byte, Boolean>());
                unknownPortTypesReported.put(port, typesReportedForPort);
            }
           if (!typesReportedForPort.contains(data[PACKET_TYPE_OFFSET])) {
               logger.warn("Do not know any Pro DJ Link packets received on port " + port + " with type " +
                       String.format("0x%02x", data[PACKET_TYPE_OFFSET]) + " (this will be reported only once).");
               typesReportedForPort.add(data[PACKET_TYPE_OFFSET]);
           }
        }

        return result;
    }

    /**
     * Converts a signed byte to its unsigned int equivalent in the range 0-255.
     *
     * @param b a byte value to be considered an unsigned integer
     *
     * @return the unsigned version of the byte
     */
    public static int unsign(byte b) {
        return b & 0xff;
    }

    /**
     * Reconstructs a number that is represented by more than one byte in a network packet in big-endian order.
     *
     * @param buffer the byte array containing the packet data
     * @param start the index of the first byte containing a numeric value
     * @param length the number of bytes making up the value
     * @return the reconstructed number
     */
    public static long bytesToNumber(byte[] buffer, int start, int length) {
        long result = 0;
        for (int index = start; index < start + length; index++) {
            result = (result << 8) + unsign(buffer[index]);
        }
        return result;
    }

    /**
     * Reconstructs a number that is represented by more than one byte in a network packet in little-endian order, for
     * the very few protocol values that are sent in this quirky way.
     *
     * @param buffer the byte array containing the packet data
     * @param start the index of the first byte containing a numeric value
     * @param length the number of bytes making up the value
     * @return the reconstructed number
     */
    @SuppressWarnings("SameParameterValue")
    public static long bytesToNumberLittleEndian(byte[] buffer, int start, int length) {
        long result = 0;
        for (int index = start + length - 1; index >= start; index--) {
            result = (result << 8) + unsign(buffer[index]);
        }
        return result;
    }

    /**
     * Writes a number to the specified byte array field, breaking it into its component bytes in big-endian order.
     * If the number is too large to fit in the specified number of bytes, only the low-order bytes are written.
     *
     * @param number the number to be written to the array
     * @param buffer the buffer to which the number should be written
     * @param start where the high-order byte should be written
     * @param length how many bytes of the number should be written
     */
    public static void numberToBytes(int number, byte[] buffer, int start, int length) {
        for (int index = start + length - 1; index >= start; index--) {
            buffer[index] = (byte)(number & 0xff);
            number = number >> 8;
        }
    }

    /**\
     * Convert int to big array, little endian.
     * @param i
     * @return little endian byte array of int
     */
    public static byte[] intToLeByteArray(int i) {
        final ByteBuffer bb = ByteBuffer.allocate(Integer.SIZE / Byte.SIZE);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(i);
        return bb.array();
    }

    /**
     * Converts the bytes that make up an internet address into the corresponding integer value to make
     * it easier to perform bit-masking operations on them.
     *
     * @param address an address whose integer equivalent is desired
     *
     * @return the integer corresponding to that address
     */
    public static long addressToLong(InetAddress address) {
        long result = 0;
        for (byte element : address.getAddress()) {
            result = (result << 8) + unsign(element);
        }
        return result;
    }

    /**
     * Checks whether two internet addresses are on the same subnet.
     *
     * @param prefixLength the number of bits within an address that identify the network
     * @param address1 the first address to be compared
     * @param address2 the second address to be compared
     *
     * @return true if both addresses share the same network bits
     */
    public static boolean sameNetwork(int prefixLength, InetAddress address1, InetAddress address2) {
        if (logger.isDebugEnabled()) {
            logger.debug("Comparing address " + address1.getHostAddress() + " with " + address2.getHostAddress() + ", prefixLength=" + prefixLength);
        }
        long prefixMask = 0xffffffffL & (-1L << (32 - prefixLength));
        return (addressToLong(address1) & prefixMask) == (addressToLong(address2) & prefixMask);
    }

    /**
     * The value sent in beat and status packets to represent playback at normal speed.
     */
    public static final long NEUTRAL_PITCH = 1048576;

    /**
     * Convert a pitch value reported by a device to the corresponding percentage (-100% to +100%, where normal,
     * unadjusted pitch has the value 0%).
     *
     * @param pitch the reported device pitch
     * @return the pitch as a percentage
     */
    public static double pitchToPercentage(long pitch) {
        return (pitch - NEUTRAL_PITCH) / (NEUTRAL_PITCH / 100.0);
    }

    /**
     * Convert a human-oriented pitch percentage (-100% to +100%, where normal, unadjusted playback speed has the
     * value 0%) to the raw numeric value used to represent it in beat and CDJ status packets.
     *
     * @param percentage the pitch as a percentage
     * @return the value that would represent that pitch in a beat or CDJ status packet
     */
    public static long percentageToPitch(double percentage) {
        return (Math.round(percentage * NEUTRAL_PITCH / 100.0) + NEUTRAL_PITCH);
    }

    /**
     * Convert a pitch value reported by a device to the corresponding multiplier (0.0 to 2.0, where normal, unadjusted
     * pitch has the multiplier 1.0).
     *
     * @param pitch the reported device pitch
     * @return the implied pitch multiplier
     */
    public static double pitchToMultiplier(long pitch) {
        return pitch / (double) NEUTRAL_PITCH;
    }

    /**
     * Writes the entire remaining contents of the buffer to the channel. May complete in one operation, but the
     * documentation is vague, so this keeps going until we are sure.
     *
     * @param buffer the data to be written
     * @param channel the channel to which we want to write data
     *
     * @throws IOException if there is a problem writing to the channel
     */
    public static void writeFully(ByteBuffer buffer, WritableByteChannel channel) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * Figure out the track time that corresponds to a half-frame number (75 frames per second, so 150 half-frames).
     *
     * @param halfFrame the half-frame that we are interested in knowing the time for
     *
     * @return the number of milliseconds into a track that the specified half-frame begins
     */
    public static long halfFrameToTime(long halfFrame) {
        return halfFrame * 100 / 15;
    }

    /**
     * Convert a track position (time) into the corresponding half-frame value (75 frames per second, so 150 half-frames).
     *
     * @param milliseconds how long a track has been playing for
     *
     * @return the half-frame that contains that part of the track
     */
    public static int timeToHalfFrame(long milliseconds) {
        return (int) (milliseconds * 15 / 100);
    }

    /**
     * Convert a track position (time) into the corresponding rounded half-frame value (75 frames per second, so 150 half-frames).
     *
     * @param milliseconds how long a track has been playing for
     *
     * @return the nearest half-frame that contains that part of the track
     */
    public static int timeToHalfFrameRounded(long milliseconds) {
        return Math.round(milliseconds * 0.15f);
    }

    /**
     * Used to allow locking operations against named resources, such as files being fetched by
     * {@link org.deepsymmetry.beatlink.data.CrateDigger}, to protect against race conditions where
     * one thread creates the file and another thinks it has already been downloaded and tries to
     * parse the partial file.
     */
    private static final Map<String, Object> namedLocks = new HashMap<String, Object>();

    /**
     * Counts the threads that are currently using a named lock, so we can know when it can be
     * removed from the maps.
     */
    private static final Map<String, Integer> namedLockUseCounts = new HashMap<String, Integer>();

    /**
     * Obtain an object that can be synchronized against to provide exclusive access to a named resource,
     * given its unique name. Used with file canonical path names by {@link org.deepsymmetry.beatlink.data.CrateDigger}
     * to protect against race conditions where one thread creates the file and another thinks it has already been
     * downloaded and tries to parse the partial file.
     * <p>
     * Once the exclusive lock is no longer needed, {@link #freeNamedLock(String)} should be called with the same
     * name so the lock can be garbage collected if no other threads are now using it.
     *
     * @param name uniquely identifies some resource to which exclusive access is needed
     * @return an object that can be used with a {@code synchronized} block to guarantee exclusive access to the resource
     */
    public synchronized static Object allocateNamedLock(String name) {
        Object result = namedLocks.get(name);
        if (result != null) {
            namedLockUseCounts.put(name, namedLockUseCounts.get(name) + 1);
            return result;
        }
        namedLockUseCounts.put(name, 1);
        result = new Object();
        namedLocks.put(name, result);
        return result;
    }

    /**
     * Indicate that an object obtained from {@link #allocateNamedLock(String)} is no longer needed by the caller, so
     * it is eligible for garbage collection if no other threads have it allocated.
     *
     * @param name uniquely identifies some resource to which exclusive access was previously needed
     */
    public synchronized static void freeNamedLock(String name) {
        int count = namedLockUseCounts.get(name);
        if (count > 1) {
            namedLockUseCounts.put(name, count - 1);
        } else {
            namedLocks.remove(name);
            namedLockUseCounts.remove(name);
        }
    }

    /**
     * Helper method that combines the hue value of one color with the transparency of another.
     *
     * @param hueColor the color that specifies the actual hue to be drawn
     * @param alphaColor the color whose transparency will determine the transparency of the result
     *
     * @return a color with hue components from hueColor and alpha from alphaColor
     */
    public static Color buildColor(Color hueColor, Color alphaColor) {
        return new Color(hueColor.getRed(), hueColor.getGreen(), hueColor.getBlue(), alphaColor.getAlpha());
    }

    /**
     * The color of an intro phrase in a track with a low mood.
     */
    public static final Color LOW_INTRO_COLOR = new Color(255, 170, 180);

    /**
     * The color of a verse 1 phrase in a track with a low mood.
     */
    public static final Color LOW_VERSE_1_COLOR = new Color(165, 160, 255);

    /**
     * The color of a verse 2 phrase in a track with a low mood.
     */
    public static final Color LOW_VERSE_2_COLOR = new Color(190, 160, 255);

    /**
     * The color of a bridge phrase in a track with a low mood.
     */
    public static final Color LOW_BRIDGE_COLOR = new Color(255, 250, 165);

    /**
     * The color of a chorus phrase in a track with a low mood.
     */
    public static final Color LOW_CHORUS_COLOR = new Color(185, 225, 185);

    /**
     * The color of an outro phrase in a track with a low mood.
     */
    public static final Color LOW_OUTRO_COLOR = new Color(145, 160, 180);


    /**
     * The color of an intro phrase in a track with a mid mood.
     */
    public static final Color MID_INTRO_COLOR = new Color(225, 70, 70);

    /**
     * The color of a verse 1 phrase in a track with a mid mood.
     */
    public static final Color MID_VERSE_1_COLOR = new Color(80, 110, 255);

    /**
     * The color of a verse 2 phrase in a track with a mid mood.
     */
    public static final Color MID_VERSE_2_COLOR = new Color(80, 85, 255);

    /**
     * The color of a verse 3 phrase in a track with a mid mood.
     */
    public static final Color MID_VERSE_3_COLOR = new Color(100, 80, 255);

    /**
     * The color of a verse 4 phrase in a track with a mid mood.
     */
    public static final Color MID_VERSE_4_COLOR = new Color(120, 80, 255);

    /**
     * The color of a verse 5 phrase in a track with a mid mood.
     */
    public static final Color MID_VERSE_5_COLOR = new Color(140, 80, 255);

    /**
     * The color of a verse 6 phrase in a track with a mid mood.
     */
    public static final Color MID_VERSE_6_COLOR = new Color(160, 80, 255);

    /**
     * The color of a bridge phrase in a track with a mid mood.
     */
    public static final Color MID_BRIDGE_COLOR = new Color(225, 215, 65);

    /**
     * The color of a chorus phrase in a track with a mid mood.
     */
    public static final Color MID_CHORUS_COLOR = new Color(120, 195, 125);

    /**
     * The color of an outro phrase in a track with a mid mood.
     */
    public static final Color MID_OUTRO_COLOR = new Color(115, 130, 150);


    /**
     * The color of an intro 1 phrase in a track with a high mood.
     */
    public static final Color HIGH_INTRO_1_COLOR = new Color(200, 0, 0);

    /**
     * The color of an intro 2 phrase in a track with a high mood.
     */
    public static final Color HIGH_INTRO_2_COLOR = new Color(200, 50, 0);

    /**
     * The color of an up 1 phrase in a track with a high mood.
     */
    public static final Color HIGH_UP_1_COLOR = new Color(140, 50, 255);

    /**
     * The color of an up 2 phrase in a track with a high mood.
     */
    public static final Color HIGH_UP_2_COLOR = new Color(105, 50, 255);

    /**
     * The color of an up 3 phrase in a track with a high mood.
     */
    public static final Color HIGH_UP_3_COLOR = new Color(90, 50, 255);

    /**
     * The color of a down phrase in a track with a high mood.
     */
    public static final Color HIGH_DOWN_COLOR = new Color(155, 115, 45);

    /**
     * The color of a chorus 1 phrase in a track with a high mood.
     */
    public static final Color HIGH_CHORUS_1_COLOR = new Color(15, 170, 0);

    /**
     * The color of a chorus 2 phrase in a track with a high mood.
     */
    public static final Color HIGH_CHORUS_2_COLOR = new Color(15, 170, 0);

    /**
     * The color of an outro 1 phrase in a track with a high mood.
     */
    public static final Color HIGH_OUTRO_1_COLOR = new Color(80, 135, 195);

    /**
     * The color of an outro 2 phrase in a track with a high mood.
     */
    public static final Color HIGH_OUTRO_2_COLOR = new Color(95, 135, 175);


    /**
     * Get the color that should be used for the bar representing a phrase being painted on a waveform.
     *
     * @param phrase the song structure entry representing a phrase being painted
     *
     * @return the color with which to paint the box that identifies the phrase
     */
    public static Color phraseColor(final SongStructureEntry phrase) {

        switch (phrase._parent().mood()) {

            case LOW:
                final RekordboxAnlz.PhraseLow phraseLow = (RekordboxAnlz.PhraseLow)phrase.kind();
                if (phraseLow == null) return Color.white;  // We don't recognize this phrase.

                switch (phraseLow.id()) {
                    case INTRO:
                        return LOW_INTRO_COLOR;

                    case VERSE_1:
                    case VERSE_1B:
                    case VERSE_1C:
                        return LOW_VERSE_1_COLOR;

                    case VERSE_2:
                    case VERSE_2B:
                    case VERSE_2C:
                        return LOW_VERSE_2_COLOR;

                    case BRIDGE:
                        return LOW_BRIDGE_COLOR;

                    case CHORUS:
                        return LOW_CHORUS_COLOR;

                    case OUTRO:
                        return LOW_OUTRO_COLOR;
                }

            case MID:
                final RekordboxAnlz.PhraseMid phraseMid = (RekordboxAnlz.PhraseMid)phrase.kind();
                if (phraseMid == null) return Color.white;  // We don't recognize this phrase.

                switch (phraseMid.id()) {
                    case INTRO:
                        return MID_INTRO_COLOR;

                    case VERSE_1:
                        return MID_VERSE_1_COLOR;

                    case VERSE_2:
                        return MID_VERSE_2_COLOR;

                    case VERSE_3:
                        return MID_VERSE_3_COLOR;

                    case VERSE_4:
                        return MID_VERSE_4_COLOR;

                    case VERSE_5:
                        return MID_VERSE_5_COLOR;

                    case VERSE_6:
                        return MID_VERSE_6_COLOR;

                    case BRIDGE:
                        return MID_BRIDGE_COLOR;

                    case CHORUS:
                        return MID_CHORUS_COLOR;

                    case OUTRO:
                        return MID_OUTRO_COLOR;
                }

            case HIGH:
                final RekordboxAnlz.PhraseHigh phraseHigh = (RekordboxAnlz.PhraseHigh)phrase.kind();
                if (phraseHigh == null) return Color.white;  // We don't recognize this phrase.

                switch (phraseHigh.id()) {
                    case INTRO:
                        if (phrase.k1() == 1) {
                            return HIGH_INTRO_1_COLOR;
                        }
                        return HIGH_INTRO_2_COLOR;

                    case UP:
                        if (phrase.k2() == 0) {
                            if (phrase.k3() == 0) {
                                return HIGH_UP_1_COLOR;
                            }
                            return  HIGH_UP_2_COLOR;
                        }
                        return HIGH_UP_3_COLOR;

                    case DOWN:
                        return HIGH_DOWN_COLOR;

                    case CHORUS:
                        if (phrase.k1() == 1) {
                            return HIGH_CHORUS_1_COLOR;
                        }
                        return HIGH_CHORUS_2_COLOR;

                    case OUTRO:
                        if (phrase.k1() == 1) {
                            return HIGH_OUTRO_1_COLOR;
                        }
                        return HIGH_OUTRO_2_COLOR;
                }

            default:
                return Color.WHITE;  // We don't recognize this mood.
        }
    }

    /**
     * Returns the color that should be used for the text of a phrase label, for legibility on the phrase background.
     *
     * @param phrase the song structure entry representing a phrase being painted
     *
     * @return the color that should be used for painting the label
     */
    public static Color phraseTextColor(final SongStructureEntry phrase) {
        if (phrase._parent().mood() == RekordboxAnlz.TrackMood.HIGH) {
            return Color.white;
        }
        return Color.black;
    }

    /**
     * Get the text that should be used for the label representing a phrase being painted on a waveform.
     *
     * @param phrase the song structure entry representing a phrase being painted
     *
     * @return the color with which to paint the box that identifies the phrase
     */
    public static String phraseLabel(final SongStructureEntry phrase) {

        switch (phrase._parent().mood()) {

            case LOW:
                final RekordboxAnlz.PhraseLow phraseLow = (RekordboxAnlz.PhraseLow)phrase.kind();
                if (phraseLow == null) return "Unknown Low";  // We don't recognize this phrase.

                switch (phraseLow.id()) {
                    case INTRO:
                        return "Intro";

                    case VERSE_1:
                    case VERSE_1B:
                    case VERSE_1C:
                        return "Verse 1";

                    case VERSE_2:
                    case VERSE_2B:
                    case VERSE_2C:
                        return "Verse 2";

                    case BRIDGE:
                        return "Bridge";

                    case CHORUS:
                        return "Chorus";

                    case OUTRO:
                        return "Outro";
                }

            case MID:
                final RekordboxAnlz.PhraseMid phraseMid = (RekordboxAnlz.PhraseMid)phrase.kind();
                if (phraseMid == null) return "Unknown Mid";  // We don't recognize this phrase.

                switch (phraseMid.id()) {
                    case INTRO:
                        return "Intro";

                    case VERSE_1:
                        return "Verse 1";

                    case VERSE_2:
                        return "Verse 2";

                    case VERSE_3:
                        return "Verse 3";

                    case VERSE_4:
                        return "Verse 4";

                    case VERSE_5:
                        return "Verse 5";

                    case VERSE_6:
                        return "Verse 6";

                    case BRIDGE:
                        return "Bridge";

                    case CHORUS:
                        return "Chorus";

                    case OUTRO:
                        return "Outro";
                }

            case HIGH:
                final RekordboxAnlz.PhraseHigh phraseHigh = (RekordboxAnlz.PhraseHigh)phrase.kind();
                if (phraseHigh == null) return "Unknown High";  // We don't recognize this phrase.

                switch (phraseHigh.id()) {
                    case INTRO:
                        if (phrase.k1() == 1) {
                            return "Intro 1";
                        }
                        return "Intro 2";

                    case UP:
                        if (phrase.k2() == 0) {
                            if (phrase.k3() == 0) {
                                return "Up 1";
                            }
                            return  "Up 2";
                        }
                        return "Up 3";

                    case DOWN:
                        return "Down";

                    case CHORUS:
                        if (phrase.k1() == 1) {
                            return "Chorus 1";
                        }
                        return "Chorus 2";

                    case OUTRO:
                        if (phrase.k1() == 1) {
                            return "Outro 1";
                        }
                        return "Outro 2";
                }

            default:
                return "Unknown Mood";  // We don't recognize this mood.
        }
    }

    /**
     * Scan a network interface to find if it has an address space which matches the device we are trying to reach.
     * If so, return the address specification.
     *
     * @param announcement the DJ Link device we are trying to communicate with
     * @param networkInterface the network interface we are testing
     * @return the address which can be used to communicate with the device on the interface, or null
     */
    public static InterfaceAddress findMatchingAddress(DeviceAnnouncement announcement, NetworkInterface networkInterface) {
        for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
            if (address == null) {
                // This should never happen, but we are protecting against a Windows Java bug, see
                // https://bugs.java.com/bugdatabase/view_bug?bug_id=8023649
                logger.warn("Received a null InterfaceAddress from networkInterface.getInterfaceAddresses(), is this Windows? " +
                        "Do you have a VPN installed? Trying to recover by ignoring it.");
            } else if ((address.getBroadcast() != null) &&
                    Util.sameNetwork(address.getNetworkPrefixLength(), announcement.getAddress(), address.getAddress())) {
                return address;
            }
        }
        return null;
    }

    /**
     * Transforms an album art path to the version that will contain high resolution art, if that is available.
     *
     * @param artPath standard resolution album art path
     * @return path at which high resolution art might be found
     */
    public static String highResolutionPath(String artPath) {
        return artPath.replaceFirst("(\\.\\w+$)", "_m$1");
    }

    public static boolean isOpusQuad(String deviceName){
        return deviceName.equals("OPUS-QUAD");
    }

    /**
     * Prevent instantiation.
     */
    private Util() {
        // Nothing to do.
    }
}
