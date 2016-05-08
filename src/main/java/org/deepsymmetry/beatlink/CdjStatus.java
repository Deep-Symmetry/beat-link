package org.deepsymmetry.beatlink;

import java.net.DatagramPacket;

/**
 * Represents a status update sent by a CDJ (or perhaps other player) on a DJ Link network.
 *
 * @author James Elliott
 */
public class CdjStatus extends DeviceUpdate {

    /**
     * The byte within the packet which contains useful status information, labeled <i>F</i> in Figure 11 of the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public final int STATUS_FLAGS = 137;

    /**
     * The bit within the status flag that indicates the player is on the air, as illustrated in Figure 12 of the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     * A player is considered to be on the air when it is connected to a mixer channel that is not faded out.
     * Only Nexus mixers seem to support this capability.
     */
    public final int ON_AIR_FLAG = 0x08;

    /**
     * The bit within the status flag that indicates the player is synced, as illustrated in Figure 12 of the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public final int SYNCED_FLAG = 0x10;

    /**
     * The bit within the status flag that indicates the player is the tempo master, as illustrated in Figure 12 of
     * the <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public final int MASTER_FLAG = 0x20;

    /**
     * The bit within the status flag that indicates the player is playing, as illustrated in Figure 12 of the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public final int PLAYING_FLAG = 0x40;

    /**
     * The device playback pitch found in the packet.
     */
    private final int pitch;

    /**
     * The track BPM found in the packet.
     */
    private final int bpm;

    /**
     * Constructor sets all the immutable interpreted fields based on the packet content.
     *
     * @param packet the beat announcement packet that was received
     */
    public CdjStatus(DatagramPacket packet) {
        super(packet, "CDJ status", 212);
        pitch = (int)Util.bytesToNumber(packetBytes, 141, 3);
        bpm = (int)Util.bytesToNumber(packetBytes, 146, 2);
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
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>),
     * reflecting the effective BPM currently being played when it is combined with the track BPM. To probe the other
     * pitch values that were reported, you can use {@link #getPitch(int)}.</p>
     *
     * @return the raw effective device pitch at the time of the update
     */
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
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
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
     * @return the beat number within the current measure of music
     */
    public int getBeatWithinBar() {
        return packetBytes[92];
    }

    @Override
    public boolean isTempoMaster() {
        return (packetBytes[STATUS_FLAGS] & MASTER_FLAG) > 0 ;
    }

    @Override
    public double getEffectiveTempo() {
        return bpm * Util.pitchToMultiplier(pitch) / 100.0;
    }

    @Override
    public String toString() {
        return "Beat: Device " + deviceNumber + ", name: " + deviceName +
                ", pitch: " + String.format("%+.2f%%", Util.pitchToPercentage(pitch)) +
                ", track BPM: " + String.format("%.1f", bpm / 100.0) +
                ", effective BPM: " + String.format("%.1f", bpm * Util.pitchToMultiplier(pitch) / 100.0) +
                ", beat within bar: " + getBeatWithinBar();
    }

}
