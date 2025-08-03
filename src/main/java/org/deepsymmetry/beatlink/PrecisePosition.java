package org.deepsymmetry.beatlink;

import org.apiguardian.api.API;

import java.net.DatagramPacket;

/**
 * A device update that reports the exact playback position of a CDJ-3000 (or newer?) player, even if it is not
 * currently playing. These are sent very frequently (roughly every 30 milliseconds) as long as the player has a
 * track loaded.
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public class PrecisePosition extends DeviceUpdate {

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
     * The effective tempo found in the packet.
     */
    private final int bpm;

    /**
     * Constructor sets all the immutable interpreted fields based on the packet content.
     *
     * @param packet the beat announcement packet that was received
     */
    @API(status = API.Status.STABLE)
    public PrecisePosition(DatagramPacket packet) {
        // Use special parent constructor because the normal offset does not contain our device number.
        super(packet, "Precise position", 0x3c, 0x21);
        trackLength = (int)Util.bytesToNumber(packetBytes, 0x24, 4);
        playbackPosition = (int)Util.bytesToNumber(packetBytes, 0x28, 4);
        long rawPitch = Util.bytesToNumber(packetBytes, 0x2c, 4);
        if (rawPitch > 0x80000000L) {  // This is a negative effective tempo
            rawPitch -= 0x100000000L;
        }
        pitch = (int)Util.percentageToPitch(rawPitch / 100.0);
        bpm = (int)Util.bytesToNumber(packetBytes, 0x38, 4) * 10;
    }

    /**
     * Get the length of the track that is loaded in the player, in seconds, rounded down to the nearest second.
     *
     * @return the track length
     */
    @API(status = API.Status.STABLE)
    public int getTrackLength() {
        return trackLength;
    }

    /**
     * Get the current position of the player's playback head within the track, in milliseconds.
     *
     * @return the playback position
     */
    @API(status = API.Status.STABLE)
    public int getPlaybackPosition() {
        return playbackPosition;
    }

    @Override
    public int getPitch() {
        return pitch;
    }

    @Override
    public int getBpm() {
        return (int)Math.round(bpm / Util.pitchToMultiplier(pitch));
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

    @Override
    public Integer getDeviceMasterIsBeingYieldedTo() {
        return null;  // Beats never yield the master role
    }

    @Override
    public double getEffectiveTempo() {
        return bpm / 100.0;
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
