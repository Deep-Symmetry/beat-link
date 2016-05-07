package org.deepsymmetry.beatlink;

import java.net.DatagramPacket;
import java.net.InetAddress;

/**
 * A device update that announces the start of a new beat on a DJ Link network.
 *
 * @author James Elliott
 */
public class Beat extends DeviceUpdate {

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
    public Beat(DatagramPacket packet) {
        super(packet, "Beat announcement", 96);
        pitch = (int)Util.bytesToNumber(packetBytes, 85, 3);
        bpm = (int)Util.bytesToNumber(packetBytes, 90, 2);
    }

    /**
     * Get the device pitch at the time of the beat. This is an integer ranging from 0 to 2097152, which corresponds
     * to a range between completely stopping playback to playing at twice normal tempo. The equivalent percentage
     * value can be obtained by passing the pitch to {@link Util#pitchToPercentage(long)}, and the corresponding
     * fractional scaling value by passing it to {@link Util#pitchToMultiplier(long)}.
     *
     * @return the raw device pitch
     */
    public long getPitch() {
        return pitch;
    }

    /**
     * Get the track BPM at the time of the beat. This is an integer representing the BPM times 100, so a track running
     * at 120.5 BPM would be represented by the value 12050.
     *
     * @return the track BPM to two decimal places multiplied by 100
     */
    public int getBpm() {
        return bpm;
    }

    /**
     * Get the position within a measure of music at which this beat falls (a value from 1 to 4, where 1 represents
     * the down beat). This value will be accurate for players when the track was properly configured within rekordbox
     * (and if the music follows a standard House 4/4 time signature). The mixer makes no effort to synchronize
     * down beats with players, however, so this value is meaningless when coming from the mixer.
     *
     * @return the beat number within the current measure of music
     */
    public int getBeatWithinBar() {
        return packetBytes[92];
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
