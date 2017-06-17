package org.deepsymmetry.beatlink;

import java.net.DatagramPacket;

/**
 * Represents a status update sent by a mixer on a DJ Link network.
 *
 * @author James Elliott
 */
public class MixerStatus extends DeviceUpdate {

    /**
     * The byte within the packet which contains useful status information, labeled <i>F</i> in Figure 10 of the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    @SuppressWarnings("WeakerAccess")
    public static final int STATUS_FLAGS = 39;

    /**
     * The device playback pitch found in the packet.
     */
    private final int pitch;

    /**
     * The BPM found in the packet.
     */
    private final int bpm;

    /**
     * Constructor sets all the immutable interpreted fields based on the packet content.
     *
     * @param packet the beat announcement packet that was received
     */
    @SuppressWarnings("WeakerAccess")
    public MixerStatus(DatagramPacket packet) {
        super(packet, "Mixer update", 56);
        pitch = (int)Util.bytesToNumber(packetBytes, 40, 4);
        bpm = (int)Util.bytesToNumber(packetBytes, 46, 2);
    }

    /**
     * Get the BPM at the time of the update. This is an integer representing the BPM times 100,
     * so 120.5 BPM would be represented by the value 12050.
     *
     * @return the current BPM to two decimal places multiplied by 100
     */
    public int getBpm() {
        return bpm;
    }

    /**
     * Get the position within a measure of music at which the most recent beat occurred (a value from 1 to 4, where 1
     * represents the down beat). The mixer makes no effort to synchronize down beats with players, however, so this
     * value is of little use.
     *
     * @return the beat number within the current measure of music, as far as the mixer knows
     */
    public int getBeatWithinBar() {
        return packetBytes[55];
    }

    /**
     * Returns {@code true} if this beat is coming from a device where {@link #getBeatWithinBar()} can reasonably
     * be expected to have musical significance, because it respects the way a track was configured within rekordbox.
     *
     * @return false because mixers make no effort to line up their beats with rekordbox-identified measures
     */
    @Override
    public boolean isBeatWithinBarMeaningful() {
        return false;
    }

    @Override
    public int getPitch() {
        return pitch;
    }

    /**
     * Is this mixer reporting itself to be the current tempo master?
     *
     * @return {@code true} if the mixer that sent this update is the master
     */
    @Override
    public boolean isTempoMaster() {
        return (packetBytes[STATUS_FLAGS] & CdjStatus.MASTER_FLAG) > 0;
    }

    @Override
    public double getEffectiveTempo() {
        return bpm * Util.pitchToMultiplier(pitch) / 100.0;
    }

    @Override
    public String toString() {
        return "MixerStatus[device:" + deviceNumber + ", name:" + deviceName + ", address:" + address.getHostAddress() +
                ", timestamp:" + timestamp + ", BPM:" + String.format("%.1f", bpm / 100.0) +
                ", beat within bar:" + getBeatWithinBar() + ", isBeatWithBarMeaningful? " +
                isBeatWithinBarMeaningful() + "]";
    }
}
