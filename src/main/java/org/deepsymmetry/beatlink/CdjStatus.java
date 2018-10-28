package org.deepsymmetry.beatlink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.util.*;

/**
 * Represents a status update sent by a CDJ (or perhaps other player) on a DJ Link network.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class CdjStatus extends DeviceUpdate {

    private static final Logger logger = LoggerFactory.getLogger(CdjStatus.class);

    /**
     * The byte within the status packet which contains useful status information, labeled <i>F</i> in Figure 11 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    @SuppressWarnings("WeakerAccess")
    public static final int STATUS_FLAGS = 0x89;

    /**
     * The byte within a status packet which indicates that the device is in the process of handing off the tempo
     * master role to anther device, labeled <i>M<sub>h</sub></i> in Figure 11 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     *
     * Normally it holds the value 0xff, but during a tempo master hand-off, it holds
     * the device number of the incoming tempo master, until that device asserts the master state, after which this
     * device will stop doing so.
     */
    public static final int MASTER_HAND_OFF = 0x9f;

    /**
     * The bit within the status flag that indicates the player is on the air, as illustrated in Figure 12 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     * A player is considered to be on the air when it is connected to a mixer channel that is not faded out.
     * Only Nexus mixers seem to support this capability.
     */
    @SuppressWarnings("WeakerAccess")
    public static final int ON_AIR_FLAG = 0x08;

    /**
     * The bit within the status flag that indicates the player is synced, as illustrated in Figure 12 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    @SuppressWarnings("WeakerAccess")
    public static final int SYNCED_FLAG = 0x10;

    /**
     * The bit within the status flag that indicates the player is the tempo master, as illustrated in Figure 12 of
     * the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public static final int MASTER_FLAG = 0x20;

    /**
     * The bit within the status flag that indicates the player is playing, as illustrated in Figure 12 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    @SuppressWarnings("WeakerAccess")
    public static final int PLAYING_FLAG = 0x40;

    /**
     * The device number of the player from which the track was loaded, if any; labeled <i>P<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    private final int trackSourcePlayer;

    /**
     * Get the device number of the player from which the track was loaded, if any; labeled <i>P<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     *
     * @return the device number from which the current track was loaded
     */
    public int getTrackSourcePlayer() { return trackSourcePlayer; }

    /**
     * The possible values describing from where the track was loaded, labeled <i>S<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public enum TrackSourceSlot {
        /**
         * Nothing has been loaded.
         */
        NO_TRACK   (0),
        /**
         * The track was loaded from a CD.
         */
        CD_SLOT    (1),
        /**
         * The track was loaded from the Secure Digital media slot.
         */
        SD_SLOT    (2),
        /**
         * The track was loaded from the USB socket.
         */
        USB_SLOT   (3),
        /**
         * The track was loaded from a computer’s rekordbox collection over the network.
         */
        COLLECTION (4),
        /**
         * We saw a value that we did not recognize, so we don’t know where the track came from.
         */
        UNKNOWN    (-1);

        /**
         * The value that represents this media source in a status update or dbserver request.
         */
        public final byte protocolValue;

        TrackSourceSlot(int value) {
            protocolValue = (byte)value;
        }
    }

    /**
     * Allows a known track source slot value to be looked up based on the byte that was seen in a status update.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Map<Byte,TrackSourceSlot> TRACK_SOURCE_SLOT_MAP;

    static {
        Map<Byte,TrackSourceSlot> scratch = new HashMap<Byte, TrackSourceSlot>();
        for (TrackSourceSlot slot : TrackSourceSlot.values()) {
            scratch.put(slot.protocolValue, slot);
        }
        TRACK_SOURCE_SLOT_MAP = Collections.unmodifiableMap(scratch);
    }

    /**
     * The slot from which the track was loaded, if any; labeled <i>S<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    private final TrackSourceSlot trackSourceSlot;

    /**
     * Get the slot from which the track was loaded, if any; labeled <i>S<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     *
     * @return the slot from which the current track was loaded
     */
    public TrackSourceSlot getTrackSourceSlot() { return trackSourceSlot; }

    /**
     * The possible values describing the track type, labeled <i>t<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public enum TrackType {
        /**
         * No track has been loaded.
         */
        NO_TRACK         (0),
        /**
         * The track was loaded from a rekordbox database, and has been analyzed for beat grid and waveforms.
         */
        REKORDBOX        (1),
        /**
         * The track was loaded from digital media (including a data disc), but was not from rekordbox,
         * and so has not been analyzed for beat grid and waveforms.
         */
        UNANALYZED       (2),
        /**
         * The track was loaded from an audio CD, and so has not been analyzed for beat grid and waveforms.
         */
        CD_DIGITAL_AUDIO (5),
        /**
         * We received a value that we did not recognize, so we don’t know what it means.
         */
        UNKNOWN          (-1);

        /**
         * The value that represents this track type in a status update.
         */
        @SuppressWarnings("WeakerAccess")
        public final byte protocolValue;

        TrackType(int value) {
            protocolValue = (byte)value;
        }
    }

    /**
     * Allows a known track source type value to be looked up based on the byte that was seen in a status update.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Map<Byte,TrackType> TRACK_TYPE_MAP;

    static {
        Map<Byte,TrackType> scratch = new HashMap<Byte, TrackType>();
        for (TrackType type : TrackType.values()) {
            scratch.put(type.protocolValue, type);
        }
        TRACK_TYPE_MAP = Collections.unmodifiableMap(scratch);
    }

    /**
     * The type of the track that was loaded, if any; labeled <i>t<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    private final TrackType trackType;

    /**
     * Get the type of the track was loaded, if any; labeled <i>t<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     *
     * @return the type of track that is currently loaded
     */
    public TrackType getTrackType() { return trackType; }

    /**
     * The rekordbox ID of the track that was loaded, if any; labeled<i>rekordbox</i> in Figure 11 of
     * the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    private final int rekordboxId;

    /**
     * Get the rekordbox ID of the track that was loaded, if any; labeled <i>rekordbox</i> in Figure 11 of
     * the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     * Will be zero if no track is loaded, and is simply the track number when an ordinary audio CD track has been
     * loaded.
     *
     * @return the rekordbox database ID of the current track
     */
    public int getRekordboxId() { return rekordboxId; }

    /**
     * The possible values of the first play state found in the packet, labeled <i>P<sub>1</sub></i> in Figure 11 of
     * the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    @SuppressWarnings("WeakerAccess")
    public enum PlayState1 {
        /**
         * No track has been loaded.
         */
        NO_TRACK (0),
        /**
         * A track is in the process of being loaded.
         */
        LOADING (2),
        /**
         * The player is playing normally.
         */
        PLAYING (3),
        /**
         * The player is playing a loop.
         */
        LOOPING (4),
        /**
         * The player is paused anywhere other than the cue point.
         */
        PAUSED (5),
        /**
         * The player is paused at the cue point.
         */
        CUED (6),
        /**
         * Cue play is in progress (playback while the cue button is held down).
         */
        CUE_PLAYING (7),
        /**
         * Cue scratch is in progress; the player will return to the cue point when the jog wheel is released.
         */
        CUE_SCRATCHING (8),
        /**
         * The player is searching forwards or backwards.
         */
        SEARCHING(9),
        /**
         * The player reached the end of the track and stopped.
         */
        ENDED(17),
        /**
         * We received a value we don’t recognize, so we don’t know what it means.
         */
        UNKNOWN(-1);

        /**
         * The value that represents this play state in a status update.
         */
        public final byte protocolValue;

        /**
         * Constructor simply sets the protocol value.
         *
         * @param value the value that represents this play state in a status update.
         */
        PlayState1(int value) {
            protocolValue = (byte) value;
        }
    }

    /**
     * Allows a known <i>P<sub>1</sub></i> value to be looked up based on the byte that was seen in a status update.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Map<Byte,PlayState1> PLAY_STATE_1_MAP;

    static {
        Map<Byte,PlayState1> scratch = new HashMap<Byte, PlayState1>();
        for (PlayState1 state : PlayState1.values()) {
            scratch.put(state.protocolValue, state);
        }
        PLAY_STATE_1_MAP = Collections.unmodifiableMap(scratch);
    }

    /**
     * The first play state found in the packet, labeled <i>P<sub>1</sub></i> in Figure 11 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    private final PlayState1 playState1;

    /**
     * Get the first play state found in the packet, labeled <i>P<sub>1</sub></i> in Figure 11 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     *
     * @return the first play state element
     */
    @SuppressWarnings("WeakerAccess")
    public PlayState1 getPlayState1() {
        return playState1;
    }

    /**
     * The possible values of the second play state found in the packet, labeled <i>P<sub>2</sub></i> in Figure 11 of
     * the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public enum PlayState2 {
        /**
         * The player is moving through a track.
         */
        MOVING  (0x7a),
        /**
         * The player is stopped.
         */
        STOPPED (0x7e),
        /**
         * We saw an unknown value, so we don’t know what it means.
         */
        UNKNOWN (-1);

        /**
         * The value that represents this play state in a status update.
         */
        @SuppressWarnings("WeakerAccess")
        public final byte protocolValue;

        /**
         * Constructor simply sets the protocol value.
         *
         * @param value the value that represents this play state in a status update.
         */
        PlayState2(int value) {
            protocolValue = (byte) value;
        }
    }

    /**
     * Allows a known <i>P<sub>2</sub></i> value to be looked up based on the byte that was seen in a status update.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Map<Byte,PlayState2> PLAY_STATE_2_MAP;

    static {
        Map<Byte,PlayState2> scratch = new HashMap<Byte, PlayState2>();
        for (PlayState2 state : PlayState2.values()) {
            scratch.put(state.protocolValue, state);
        }
        PLAY_STATE_2_MAP = Collections.unmodifiableMap(scratch);
    }

    /**
     * The second play state found in the packet, labeled <i>P<sub>2</sub></i> in Figure 11 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    private final PlayState2 playState2;

    /**
     * Get the second play state found in the packet, labeled <i>P<sub>2</sub></i> in Figure 11 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     *
     * @return the second play state element
     */
    @SuppressWarnings("WeakerAccess")
    public PlayState2 getPlayState2() {
        return playState2;
    }

    /**
     * The possible values of the third play state found in the packet, labeled <i>P<sub>3</sub></i> in Figure 11 of
     * the <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public enum PlayState3 {
        /**
         * No track has been loaded.
         */
        NO_TRACK (0),
        /**
         * The player is paused or playing in Reverse mode.
         */
        PAUSED_OR_REVERSE (1),
        /**
         * The player is playing in Forward mode with jog mode set to Vinyl.
         */
        FORWARD_VINYL (9),
        /**
         * The player is playing in Forward mode with jog mode set to CDJ.
         */
        FORWARD_CDJ (13),
        /**
         * We received a value that we did not recognize, so we don’t know what it means.
         */
        UNKNOWN (-1);

        /**
         * The value that represents this play state in a status update.
         */
        public final byte protocolValue;

        /**
         * Constructor simply sets the protocol value.
         *
         * @param value the value that represents this play state in a status update.
         */
        PlayState3(int value) {
            protocolValue = (byte) value;
        }
    }

    /**
     * Allows a known <i>P<sub>3</sub></i> value to be looked up based on the byte that was seen in a status update.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Map<Byte,PlayState3> PLAY_STATE_3_MAP;

    static {
        Map<Byte,PlayState3> scratch = new HashMap<Byte, PlayState3>();
        for (PlayState3 state : PlayState3.values()) {
            scratch.put(state.protocolValue, state);
        }
        PLAY_STATE_3_MAP = Collections.unmodifiableMap(scratch);
    }

    /**
     * The third play state found in the packet, labeled <i>P<sub>3</sub></i> in Figure 11 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    private final PlayState3 playState3;

    /**
     * Get the third play state found in the packet, labeled <i>P<sub>3</sub></i> in Figure 11 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     *
     * @return the third play state element
     */
    public PlayState3 getPlayState3() {
        return playState3;
    }

    /**
     * The device playback pitch found in the packet.
     */
    private final int pitch;

    /**
     * The track BPM found in the packet.
     */
    private final int bpm;

    /**
     * The firmware version found in the packet.
     */
    private final String firmwareVersion;

    /**
     * If we are in the process of handing the tempo master role to another device, this will be the device number
     * of that device, otherwise it will have the value 255.
     */
    private final int handingMasterToDevice;

    /**
     * Determine the enum value corresponding to the track source slot found in the packet.
     *
     * @return the proper value
     */
    private TrackSourceSlot findTrackSourceSlot() {
        TrackSourceSlot result = TRACK_SOURCE_SLOT_MAP.get(packetBytes[41]);
        if (result == null) {
            return TrackSourceSlot.UNKNOWN;
        }
        return result;
    }

    /**
     * Determine the enum value corresponding to the track type found in the packet.
     *
     * @return the proper value
     */
    private TrackType findTrackType() {
        TrackType result = TRACK_TYPE_MAP.get(packetBytes[42]);
        if (result == null) {
            return TrackType.UNKNOWN;
        }
        return result;
    }

    /**
     * Determine the enum value corresponding to the first play state found in the packet.
     *
     * @return the proper value
     */
    private PlayState1 findPlayState1() {
        PlayState1 result = PLAY_STATE_1_MAP.get(packetBytes[123]);
        if (result == null) {
            return PlayState1.UNKNOWN;
        }
        return result;
    }

    /**
     * Determine the enum value corresponding to the second play state found in the packet.
     *
     * @return the proper value
     */
    private PlayState2 findPlayState2() {
        switch (packetBytes[139]) {
            case 0x6a:
            case 0x7a:
            case (byte)0xfa:
                return PlayState2.MOVING;

            case 0x6e:
            case 0x7e:
            case (byte)0xfe:
                return PlayState2.STOPPED;

            default:
                return PlayState2.UNKNOWN;
        }
    }

    /**
     * Determine the enum value corresponding to the third play state found in the packet.
     *
     * @return the proper value
     */
    private PlayState3 findPlayState3() {
        PlayState3 result = PLAY_STATE_3_MAP.get(packetBytes[157]);
        if (result == null) {
            return PlayState3.UNKNOWN;
        }
        return result;
    }

    /**
     * Contains the sizes we expect CDJ status packets to have so we can log a warning if we get an unusual
     * one. We will then add the new size to the list so it only gets logged once per run.
     */
    private static final Set<Integer> expectedStatusPacketSizes = new HashSet<Integer>(Arrays.asList(0xd0, 0xd4, 0x11c, 0x124));

    /**
     * The smallest packet size from which we can be constructed. Anything less than this and we are missing
     * crucial information.
     */
    public static final int MINIMUM_PACKET_SIZE = 0xcc;

    /**
     * Constructor sets all the immutable interpreted fields based on the packet content.
     *
     * @param packet the CDJ status packet that was received
     */
    public CdjStatus(DatagramPacket packet) {
        super(packet, "CDJ status", packet.getLength());

        if (packetBytes.length < MINIMUM_PACKET_SIZE) {
            throw new IllegalArgumentException("Unable to create a CdjStatus object, packet too short: we need " + MINIMUM_PACKET_SIZE +
                    " bytes and were given only " + packetBytes.length);
        }

        final int payloadLength = (int)Util.bytesToNumber(packetBytes, 0x22, 2);
        if (packetBytes.length != payloadLength + 0x24) {
            logger.warn("Received CDJ status packet with reported payload length of " + payloadLength + " and actual payload length of " +
                    (packetBytes.length - 0x24));
        }

        if (!expectedStatusPacketSizes.contains(packetBytes.length)) {
            logger.warn("Processing CDJ Status packets with unexpected lengths " + packetBytes.length + ".");
            expectedStatusPacketSizes.add(packetBytes.length);
        }
        trackSourcePlayer = packetBytes[40];
        trackSourceSlot = findTrackSourceSlot();
        trackType = findTrackType();
        rekordboxId = (int)Util.bytesToNumber(packetBytes, 44, 4);
        pitch = (int)Util.bytesToNumber(packetBytes, 141, 3);
        bpm = (int)Util.bytesToNumber(packetBytes, 146, 2);
        playState1 = findPlayState1();
        playState2 = findPlayState2();
        playState3 = findPlayState3();
        firmwareVersion = new String(packetBytes, 124, 4).trim();
        handingMasterToDevice = Util.unsign(packetBytes[MASTER_HAND_OFF]);
    }

    /**
     * Get the device pitch at the time of the update. This is an integer ranging from 0 to 2097152, which corresponds
     * to a range between completely stopping playback to playing at twice normal tempo. The equivalent percentage
     * value can be obtained by passing the pitch to {@link Util#pitchToPercentage(long)}, and the corresponding
     * fractional scaling value by passing it to {@link Util#pitchToMultiplier(long)}.
     *
     * <p>CDJ update packets actually have four copies of the pitch value which behave slightly differently under
     * different circumstances. This method returns one which seems to provide the most useful information (labeled
     * <i>Pitch<sub>1</sub></i> in Figure 11 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>),
     * reflecting the effective BPM currently being played when it is combined with the track BPM. To probe the other
     * pitch values that were reported, you can use {@link #getPitch(int)}.</p>
     *
     * @return the raw effective device pitch at the time of the update
     */
    @Override
    public int getPitch() {
        return pitch;
    }

    /**
     * Get a specific copy of the device pitch information at the time of the update. This is an integer ranging from 0
     * to 2097152, which corresponds to a range between completely stopping playback to playing at twice normal tempo.
     * The equivalent percentage value can be obtained by passing the pitch to {@link Util#pitchToPercentage(long)},
     * and the corresponding fractional scaling value by passing it to {@link Util#pitchToMultiplier(long)}.
     *
     * <p>CDJ update packets contain four copies of the pitch value which behave slightly differently under
     * different circumstances, labeled <i>Pitch<sub>1</sub></i> through <i>Pitch<sub>4</sub></i> in Figure 11 of the
     * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     * This method returns the one you choose by specifying {@code number}. If all you want is the current effective
     * pitch, you can use {@link #getPitch()}.</p>
     *
     * @param number the subscript identifying the copy of the pitch information you are interested in
     * @return the specified raw device pitch information copy found in the update
     */
    public int getPitch(int number) {
        switch (number) {
            case 1: return pitch;
            case 2: return (int)Util.bytesToNumber(packetBytes, 153, 3);
            case 3: return (int)Util.bytesToNumber(packetBytes, 193, 3);
            case 4: return (int)Util.bytesToNumber(packetBytes, 197, 3);
            default: throw new IllegalArgumentException("Pitch number must be between 1 and 4");
        }
    }

    /**
     * Get the track BPM at the time of the update. This is an integer representing the BPM times 100, so a track
     * running at 120.5 BPM would be represented by the value 12050.
     *
     * <p>When the CDJ has just started up and no track has been loaded, it will report a BPM of 65535.</p>
     *
     * @return the track BPM to two decimal places multiplied by 100
     */
    public int getBpm() {
        return bpm;
    }

    /**
     * Get the position within a measure of music at which the most recent beat occurred (a value from 1 to 4, where 1
     * represents the down beat). This value will be accurate when the track was properly configured within rekordbox
     * (and if the music follows a standard House 4/4 time signature).
     *
     * <p>When the track being played has not been analyzed by rekordbox, or is playing on a non-nexus player, this
     * value will always be zero.</p>
     *
     * @return the beat number within the current measure of music
     */
    public int getBeatWithinBar() {
        return packetBytes[166];
    }

    /**
     * Returns {@code true} if this beat is coming from a device where {@link #getBeatWithinBar()} can reasonably
     * be expected to have musical significance, because it respects the way a track was configured within rekordbox.
     * For CDJs this is true whenever the value is not zero (i.e. this is a nexus player playing a track that
     * was analyzed by rekordbox), because players report their beats according to rekordbox-identified measures.
     *
     * @return true whenever we are reporting a non-zero value
     */
    @Override
    public boolean isBeatWithinBarMeaningful() {
        return getBeatWithinBar() > 0;
    }

    /**
     * Is this CDJ reporting itself to be the current tempo master?
     *
     * @return {@code true} if the player that sent this update is the master
     */
    @Override
    public boolean isTempoMaster() {
        return (packetBytes[STATUS_FLAGS] & MASTER_FLAG) > 0;
    }

    @Override
    public Integer getDeviceMasterIsBeingYieldedTo() {
        if (handingMasterToDevice == 0xff) {
            return null;
        }
        return handingMasterToDevice;
    }

    @Override
    public double getEffectiveTempo() {
        return bpm * Util.pitchToMultiplier(pitch) / 100.0;
    }

    /**
     * Was the CDJ playing a track when this update was sent?
     *
     * @return true if the play flag was set, or, if this seems to be a non-nexus player, if <em>P<sub>1</sub></em>
     *         has a value corresponding to a playing state.
     */
    @SuppressWarnings("WeakerAccess")
    public boolean isPlaying() {
        if (packetBytes.length >= 212) {
            return (packetBytes[STATUS_FLAGS] & PLAYING_FLAG) > 0;
        } else {
            final PlayState1 state = getPlayState1();
            return  state == PlayState1.PLAYING || state == PlayState1.LOOPING ||
                    (state == PlayState1.SEARCHING && getPlayState2() == PlayState2.MOVING);
        }
    }

    /**
     * Was the CDJ in Sync mode when this update was sent?
     *
     * @return true if the sync flag was set
     */
    @SuppressWarnings("WeakerAccess")
    @Override
    public boolean isSynced() {
        return (packetBytes[STATUS_FLAGS] & SYNCED_FLAG) > 0;
    }

    /**
     * Was the CDJ on the air when this update was sent?
     * A player is considered to be on the air when it is connected to a mixer channel that is not faded out.
     * Only Nexus mixers seem to support this capability.
     *
     * @return true if the on-air flag was set
     */
    @SuppressWarnings("WeakerAccess")
    public boolean isOnAir() {
        return (packetBytes[STATUS_FLAGS] & ON_AIR_FLAG) > 0;
    }

    /**
     * Is USB media loaded in this particular CDJ?
     *
     * @return true if there is a USB drive mounted locally
     */
    public boolean isLocalUsbLoaded() {
        return (packetBytes[111] == 0);
    }

    /**
     * Is USB media being unloaded from this particular CDJ?
     *
     * @return true if there is a local USB drive currently being unmounted
     */
    public boolean isLocalUsbUnloading() {
        return (packetBytes[111] == 2);
    }

    /**
     * Is USB media absent from this particular CDJ?
     *
     * @return true if there is no local USB drive mounted
     */
    public boolean isLocalUsbEmpty() {
        return (packetBytes[111] == 4);
    }

    /**
     * Is SD media loaded in this particular CDJ?
     *
     * @return true if there is a Secure Digital card mounted locally
     */
    public boolean isLocalSdLoaded() {
        return (packetBytes[115] == 0);
    }

    /**
     * Is SD media being unloaded from this particular CDJ?
     *
     * @return true if there is a local Secure Digital card currently being unmounted
     */
    public boolean isLocalSdUnloading() {
        return (packetBytes[115] == 2);
    }

    /**
     * Is SD media absent from this particular CDJ?
     *
     * @return true if there is no local Secure Digital card mounted
     */
    public boolean isLocalSdEmpty() {
        return (packetBytes[115] == 4);
    }

    /**
     * Is disc media absent from this particular CDJ? Also returns {@code true} if the CD drive has powered off
     * from being unused for too long, in which case {@link #isDiscSlotAsleep()} will also return {@code true}.
     *
     * @return true if there is no disc mounted or the disc drive has powered off
     */
    public boolean isDiscSlotEmpty() {
        return (packetBytes[0x37] == 0) || isDiscSlotAsleep();
    }

    /**
     * Has this player's CD drive powered down due to prolonged disuse? When this returns {@code true}, the other
     * methods asking about the disc slot do not provide reliable values.
     *
     * @return true if the disc drive has powered off
     */
    public boolean isDiscSlotAsleep() {
        return (packetBytes[0x37] == 1);
    }

    /**
     * How many tracks are on the mounted disc? Audio CDs will reflect the audio track count, while data discs
     * will generally have one track regardless of how many usable audio files they contain when mounted. Also,
     * if the CD drive has powered off because of an extended period of not being used, this seems to return 1
     * (you can check for that condition by calling {@link #isDiscSlotAsleep()}.
     *
     * @return the number of tracks found on the mounted disc, or zero if no disc is mounted.
     */
    public int getDiscTrackCount() {
        return Util.unsign(packetBytes[0x47]);
    }

    /**
     * Is a track loaded?
     *
     * @return true if a track has been loaded
     */
    public boolean isTrackLoaded() {
        return  playState1 != PlayState1.NO_TRACK;
    }

    /**
     * Is the player currently playing a loop?
     *
     * @return true if a loop is being played
     */
    public boolean isLooping() {
        return playState1 == PlayState1.LOOPING;
    }

    /**
     * Is the player currently paused?
     *
     * @return true if the player is paused, whether or not at the cue point
     */
    public boolean isPaused() {
        return (playState1 == PlayState1.PAUSED) || (playState1 == PlayState1.CUED);
    }

    /**
     * Is the player currently cued?
     *
     * @return true if the player is paused at the cue point
     */
    public boolean isCued() {
        return playState1 == PlayState1.CUED;
    }

    /**
     * Is the player currently searching?
     *
     * @return true if the player is searching forwards or backwards
     */
    public boolean isSearching() {
        return playState1 == PlayState1.SEARCHING;
    }

    /**
     * Is the player currently stopped at the end of a track?
     *
     * @return true if playback stopped because a track ended
     */
    public boolean isAtEnd() {
        return playState1 == PlayState1.ENDED;
    }

    /**
     * Is the player currently playing forwards?
     *
     * @return true if forward playback is underway
     */
    public boolean isPlayingForwards() {
        return (playState1 == PlayState1.PLAYING) && (playState3 != PlayState3.PAUSED_OR_REVERSE);
    }

    /**
     * Is the player currently playing backwards?
     *
     * @return true if reverse playback is underway
     */
    public boolean isPlayingBackwards() {
        return (playState1 == PlayState1.PLAYING) && (playState3 == PlayState3.PAUSED_OR_REVERSE);
    }

    /**
     * Is the player currently playing with the jog wheel in Vinyl mode?
     *
     * @return true if forward playback in vinyl mode is underway
     */
    public boolean isPlayingVinylMode() {
        return playState3 == PlayState3.FORWARD_VINYL;
    }

    /**
     * Is the player currently playing with the jog wheel in CDJ mode?
     *
     * @return true if forward playback in CDJ mode is underway
     */
    public boolean isPlayingCdjMode() {
        return playState3 == PlayState3.FORWARD_CDJ;
    }

    /**
     * Is link media available somewhere on the network?
     *
     * @return true if some player has USB, SD, or other media that can be linked to
     */
    public boolean isLinkMediaAvailable() {
        return (packetBytes[117] != 0);
    }

    /**
     * Check if the player is doing anything.
     *
     * @return true if the player is playing, searching, or loading a track
     */
    @SuppressWarnings("WeakerAccess")
    public boolean isBusy() {
        return packetBytes[39] != 0;
    }

    /**
     * Get the track number of the loaded track. Identifies the track within a playlist or other scrolling list of
     * tracks in the CDJ's browse interface.
     *
     * @return the index of the current track
     */
    @SuppressWarnings("WeakerAccess")
    public int getTrackNumber() {
        return (int)Util.bytesToNumber(packetBytes, 50, 2);
    }

    /**
     * Get the sync counter used in the tempo master handoff.
     *
     * @return a number that becomes one greater than the value reported by any other player when a player gives up
     * its role as the tempo master.
     */
    public int getSyncNumber() {
        return (int)Util.bytesToNumber(packetBytes, 0x84, 4);
    }

    /**
     * Identify the beat of the track that being played. This counter starts at beat 1 as the track is played, and
     * increments on each beat. When the player is paused at the start of the track before playback begins, the
     * value reported is 0.
     *
     * <p>When the track being played has not been analyzed by rekordbox, or is being played on a non-nexus player,
     * this information is not available, and the value -1 is returned.</p>
     *
     * @return the number of the beat within the track that is currently being played, or -1 if unknown
     */
    @SuppressWarnings("WeakerAccess")
    public int getBeatNumber() {
        long result = Util.bytesToNumber(packetBytes, 160, 4);
        if (result != 0xffffffffL) {
            return (int) result;
        } return -1;
    }

    /**
     * How many beats away is the next cue point in the track? If there is no saved cue point after the current play
     * location, or if it is further than 64 bars ahead, the value 511 is returned (and the CDJ will display
     * "--.- bars"). As soon as there are just 64 bars (256 beats) to go before the next cue point, this value becomes
     * 256. This is the point at which the CDJ starts to display a countdown, which it displays as "63.4 Bars". As
     * each beat goes by, this value decrements by 1, until the cue point is about to be reached, at which point the
     * value is 1 and the CDJ displays "00.1 Bars". On the beat on which the cue point was saved the value is 0 and the
     * CDJ displays "00.0 Bars". On the next beat, the value becomes determined by the next cue point (if any) in the
     * track.
     *
     * @return the cue beat countdown, or 511 if no countdown is in effect
     * @see #formatCueCountdown()
     */
    @SuppressWarnings("WeakerAccess")
    public int getCueCountdown() {
        return (int)Util.bytesToNumber(packetBytes, 164, 2);
    }

    /**
     * Format a cue countdown indicator in the same way as the CDJ would at this point in the track.
     *
     * @return the value that the CDJ would display to indicate the distance to the next cue
     * @see #getCueCountdown()
     */
    @SuppressWarnings("WeakerAccess")
    public String formatCueCountdown() {
        int count = getCueCountdown();

        if (count == 511) {
            return "--.-";
        }

        if ((count >= 1) && (count <= 256)) {
            int bars = (count - 1) / 4;
            int beats = ((count - 1) % 4) + 1;
            return String.format("%02d.%d", bars, beats);
        }

        if (count == 0) {
            return "00.0";
        }

        return "??.?";
    }

    /**
     * Return the firmware version string reported in the packet.
     *
     * @return the version of the firmware in the player
     */
    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    /**
     * Return the sequence number of this update packet, a value that increments with each packet sent.
     *
     * @return the number of this packet
     */
    public long getPacketNumber() {
        return Util.bytesToNumber(packetBytes, 200, 4);
    }

    @Override
    public String toString() {
        return "CdjStatus[device:" + deviceNumber + ", name:" + deviceName +
                ", address:" + address.getHostAddress() + ", timestamp:" + timestamp + ", busy? " + isBusy() +
                ", pitch:" + String.format("%+.2f%%", Util.pitchToPercentage(pitch)) +
                ", rekordboxId:" + getRekordboxId() + ", from player:" + getTrackSourcePlayer() +
                ", in slot:" + getTrackSourceSlot() + ", track type:" + getTrackType() +
                ", track:" + getTrackNumber() + ", track BPM:" + String.format("%.1f", bpm / 100.0) +
                ", effective BPM:" + String.format("%.1f", getEffectiveTempo()) +
                ", beat:" + getBeatNumber() + ", beatWithinBar:" + getBeatWithinBar() +
                ", isBeatWithinBarMeaningful? " + isBeatWithinBarMeaningful() + ", cue: " + formatCueCountdown() +
                ", Playing? " + isPlaying() + ", Master? " + isTempoMaster() +
                ", Synced? " + isSynced() + ", On-Air? " + isOnAir() +
                ", handingMasterToDevice:" + handingMasterToDevice + "]";
    }

}
