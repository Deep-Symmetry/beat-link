package org.deepsymmetry.beatlink;

import java.net.DatagramPacket;

/**
 * A device update that announces the start of a new beat on a DJ Link network. Even though beats contain
 * far less detailed information than status updates, they can be passed to
 * {@link VirtualCdj#getLatestStatusFor(DeviceUpdate)} to find the current detailed status for that device,
 * as long as the Virtual CDJ is active.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
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
    @Override
    public int getPitch() {
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

    /**
     * <p>Returns {@code true} if this beat is coming from a device where {@link #getBeatWithinBar()} can reasonably
     * be expected to have musical significance, because it respects the way a track was configured within rekordbox.</p>
     *
     * <p>If the {@link VirtualCdj} is running, we can check the latest status update received from this device to
     * get a definitive answer. Otherwise we guess based on the device number; mixers seem to fall in the range
     * 33 and up.</p>
     *
     * @return true for status packets from players, false for status packets from mixers
     */
    @Override
    public boolean isBeatWithinBarMeaningful() {
        if (VirtualCdj.getInstance().isRunning()) {
            return VirtualCdj.getInstance().getLatestStatusFor(this).isBeatWithinBarMeaningful();
        }

        return deviceNumber < 33;
    }

    @Override
    public String toString() {
        return "Beat: Device " + deviceNumber + ", name: " + deviceName +
                ", pitch: " + String.format("%+.2f%%", Util.pitchToPercentage(pitch)) +
                ", track BPM: " + String.format("%.1f", bpm / 100.0) +
                ", effective BPM: " + String.format("%.1f", getEffectiveTempo()) +
                ", beat within bar: " + getBeatWithinBar();
    }

    /**
     * Was this beat sent by the current tempo master?
     *
     * @return {@code true} if the device that sent this beat is the master
     * @throws  IllegalStateException if the {@link VirtualCdj} is not running.
     */
    @Override
    public boolean isTempoMaster() {
        DeviceUpdate master = VirtualCdj.getInstance().getTempoMaster();
        return (master != null) && master.getAddress().equals(address);
    }

    @Override
    public Integer getDeviceMasterIsBeingYieldedTo() {
        return null;  // Beats never yield the master role
    }

    @SuppressWarnings("SameReturnValue")
    @Override
    public DeviceUpdate getDeviceBecomingTempoMaster() {
        return null;
    }

    @Override
    public double getEffectiveTempo() {
        return bpm * Util.pitchToMultiplier(pitch) / 100.0;
    }
}
