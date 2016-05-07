package org.deepsymmetry.beatlink;

import java.net.DatagramPacket;

/**
 * Represents a status update sent by a mixer on a DJ Link network.
 *
 * @author James Elliott
 */
public class MixerStatus extends DeviceUpdate {

    /**
     * The BPM found in the packet.
     */
    private final int bpm;

    /**
     * Constructor sets all the immutable interpreted fields based on the packet content.
     *
     * @param packet the beat announcement packet that was received
     */
    public MixerStatus(DatagramPacket packet) {
        super(packet, "Mixer update", 56);
        bpm = (int)Util.bytesToNumber(packetBytes, 46, 2);
    }

    /**
     * Get the BPM at the time of the beat. This is an integer representing the BPM times 100,
     * so 120.5 BPM would be represented by the value 12050.
     *
     * @return the current BPM to two decimal places multiplied by 100
     */
    public int getBpm() {
        return bpm;
    }

    /**
     * Get the position within a measure of music at which the most recent beat falls (a value from 1 to 4, where 1
     * represents the down beat). The mixer makes no effort to synchronize down beats with players, however, so this
     * value is of little use.
     *
     * @return the beat number within the current measure of music, as far as the mixer knows
     */
    public int getBeatWithinBar() {
        return packetBytes[55];
    }

    @Override
    public String toString() {
        return "Mixer status: Device " + deviceNumber + ", name: " + deviceName +
                ", BPM: " + String.format("%.1f", bpm / 100.0) +
                ", beat within bar: " + getBeatWithinBar();
    }

}
