package org.deepsymmetry.beatlink;

import java.net.DatagramPacket;

/**
 * A device update that reports the exact playback position of a CDJ-3000 (or newer?) player, even if it is not
 * currently playing. These are sent very frequently (roughly every 30 milliseconds) as long as the player has a
 * track loaded.
 *
 * @author James Elliott
 */public class PrecisePosition extends DeviceUpdate {

    /**
     * The track length information found in the packet.
     */
    private final int trackLength;

    /**
     * The playback position information found in the packet.
     */
    private final int playbackPosition;

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
    public PrecisePosition(DatagramPacket packet) {
        super(packet, "Precise position", 0x3c);
        trackLength = (int)Util.bytesToNumber(packetBytes, 0x24, 4);
        playbackPosition = (int)Util.bytesToNumber(packetBytes, 0x28, 4);
        pitch = (int)Util.percentageToPitch(Util.bytesToNumber(packetBytes, 0x2c, 4) / 100.0);
        bpm = (int)Util.bytesToNumber(packetBytes, 0x38, 4);
    }

    /**
     * Get the length of the track that is loaded in the player, in seconds, rounded down to the nearest second.
     *
     * @return the track length
     */
    public int getTrackLength() {
        return trackLength;
    }

    /**
     * Get the current position of the player's playback head within the track, in milliseconds.
     *
     * @return the playback position
     */
    public int getPlaybackPosition() {
        return playbackPosition;
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

    @Override
    public String toString() {
        return "Precise position: Device " + deviceNumber + ", name: " + deviceName +
                ", pitch: " + String.format("%+.2f%%", Util.pitchToPercentage(pitch)) +
                ", track BPM: " + String.format("%.1f", bpm / 100.0) +
                ", effective BPM: " + String.format("%.1f", getEffectiveTempo());
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

    @SuppressWarnings("SameReturnValue")
    @Override
    public Integer getDeviceMasterIsBeingYieldedTo() {
        return null;  // Beats never yield the master role
    }

    @Override
    public double getEffectiveTempo() {
        return bpm * Util.pitchToMultiplier(pitch) / 100.0;
    }

    /**
     * Get the position within a measure of music at which the most recent beat fell (a value from 1 to 4, where 1 represents
     * the downbeat). This value will be accurate for players when the track was properly configured within rekordbox
     * (and if the music follows a standard House 4/4 time signature). The mixer makes no effort to synchronize
     * downbeats with players, however, so this value is meaningless when coming from the mixer. The usefulness of
     * this value can be checked with {@link #isBeatWithinBarMeaningful()}.
     *
     * @return the beat number within the current measure of music
     * @throws IllegalStateException if the {@link VirtualCdj} is not running
     */
    @Override
    public int getBeatWithinBar() {
        return VirtualCdj.getInstance().getLatestStatusFor(this).getBeatWithinBar();
    }

    /**
     * Returns {@code true} if this update is coming from a device where {@link #getBeatWithinBar()} can reasonably
     * be expected to have musical significance, because it respects the way a track was configured within rekordbox.
     *
     * @return true for status packets from players, false for status packets from mixers
     * @throws IllegalStateException if the {@link VirtualCdj} is not running
     */
    @Override
    public boolean isBeatWithinBarMeaningful() {
        return VirtualCdj.getInstance().getLatestStatusFor(this).isBeatWithinBarMeaningful();
    }

}
