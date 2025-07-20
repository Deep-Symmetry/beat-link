package org.deepsymmetry.beatlink;

import org.apiguardian.api.API;

import java.net.DatagramPacket;

/**
 * <p>A device update that announces the start of a new beat on a DJ Link network. Even though beats contain
 * far less detailed information than status updates, they can be passed to
 * {@link VirtualCdj#getLatestStatusFor(DeviceUpdate)} to find the current detailed status for that device,
 * as long as the Virtual CDJ is active.</p>
 *
 * <p>They also provide information about the timing of a variety upcoming beats and bars, which may be helpful
 * for implementing Sync in a player, but the full {@link org.deepsymmetry.beatlink.data.BeatGrid} can be
 * obtained as well.</p>
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
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
    @API(status = API.Status.STABLE)
    public Beat(DatagramPacket packet) {
        super(packet, "Beat announcement", 0x60);
        pitch = (int)Util.bytesToNumber(packetBytes, 0x55, 3);
        bpm = (int)Util.bytesToNumber(packetBytes, 0x5a, 2);
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
    @API(status = API.Status.STABLE)
    public int getBpm() {
        return bpm;
    }

    /**
     * Get the position within a measure of music at which this beat falls (a value from 1 to 4, where 1 represents
     * the downbeat). This value will be accurate for players when the track was properly configured within rekordbox
     * (and if the music follows a standard House 4/4 time signature). The mixer makes no effort to synchronize
     * downbeats with players, however, so this value is meaningless when coming from the mixer.
     *
     * @return the beat number within the current measure of music
     */
    @API(status = API.Status.STABLE)
    public int getBeatWithinBar() {
        return packetBytes[0x5c];
    }

    /**
     * Get the time at which the next beat would arrive, in milliseconds, if the track were being played at
     * normal speed (a pitch of +0%). If the track ends before that beat, returns {@code 0xffffffff}.
     *
     * @return the number of milliseconds after which the next beat occurs
     */
    @API(status = API.Status.STABLE)
    public long getNextBeat() {
        return Util.bytesToNumber(packetBytes, 0x24, 4);
    }

    /**
     * Get the time at which the second upcoming beat would arrive, in milliseconds, if the track were being played at
     * normal speed (a pitch of +0%). If the track ends before that beat, returns {@code 0xffffffff}.
     *
     * @return the number of milliseconds after which the beat after the next occurs
     */
    @API(status = API.Status.STABLE)
    public long getSecondBeat() {
        return Util.bytesToNumber(packetBytes, 0x28, 4);
    }

    /**
     * Get the time at which the next bar would begin (the next downbeat would arrive), in milliseconds,
     * if the track were being played at normal speed (a pitch of +0%). If the track ends before that bar,
     * returns {@code 0xffffffff}.
     *
     * @return the number of milliseconds after which the next bar occurs
     */
    @API(status = API.Status.STABLE)
    public long getNextBar() {
        return Util.bytesToNumber(packetBytes, 0x2c, 4);
    }

    /**
     * Get the time at which the fourth beat would arrive, in milliseconds, if the track were being played at
     * normal speed (a pitch of +0%). If the track ends before that beat, returns {@code 0xffffffff}.
     *
     * @return the number of milliseconds at which the fourth upcoming beat occurs
     */
    @API(status = API.Status.STABLE)
    public long getFourthBeat() {
        return Util.bytesToNumber(packetBytes, 0x30, 4);
    }

    /**
     * Get the time at which the second upcoming bar would begin (the second downbeat would arrive), in milliseconds,
     * if the track were being played at normal speed (a pitch of +0%). If the track ends before that bar,
     * returns {@code 0xffffffff}.
     *
     * @return the number of milliseconds after which the second upcoming bar occurs
     */
    @API(status = API.Status.STABLE)
    public long getSecondBar() {
        return Util.bytesToNumber(packetBytes, 0x34, 4);
    }

    /**
     * Get the time at which the eighth beat would arrive, in milliseconds, if the track were being played at
     * normal speed (a pitch of +0%). If the track ends before that beat, returns {@code 0xffffffff}.
     *
     * @return the number of milliseconds at which the eighth upcoming beat occurs
     */
    @API(status = API.Status.STABLE)
    public long getEighthBeat() {
        return Util.bytesToNumber(packetBytes, 0x38, 4);
    }

    /**
     * <p>Returns {@code true} if this beat is coming from a device where {@link #getBeatWithinBar()} can reasonably
     * be expected to have musical significance, because it respects the way a track was configured within rekordbox.</p>
     *
     * <p>If the {@link VirtualCdj} is running, we can check the latest status update received from this device to
     * get a definitive answer. Otherwise, we guess based on the device number; mixers seem to fall in the range
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
     * @throws  IllegalStateException if the {@link VirtualCdj} is not running
     */
    @Override
    public boolean isTempoMaster() {
        DeviceUpdate master = VirtualCdj.getInstance().getTempoMaster();
        return (master != null) && master.getAddress().equals(address) && master.getDeviceNumber() == deviceNumber;
    }

    /**
     * Was this beat sent by a device that is synced to the tempo master?
     *
     * @return {@code true} if the device that sent this beat is synced
     * @throws  IllegalStateException if the {@link VirtualCdj} is not running
     */
    @Override
    public boolean isSynced() {
        return VirtualCdj.getInstance().getLatestStatusFor(this).isSynced();
    }

    @Override
    public Integer getDeviceMasterIsBeingYieldedTo() {
        return null;  // Beats never yield the master role
    }

    @Override
    public double getEffectiveTempo() {
        return bpm * Util.pitchToMultiplier(pitch) / 100.0;
    }
}
