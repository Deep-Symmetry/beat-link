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
    public static final int STATUS_FLAGS = 137;

    /**
     * The bit within the status flag that indicates the player is on the air, as illustrated in Figure 12 of the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     * A player is considered to be on the air when it is connected to a mixer channel that is not faded out.
     * Only Nexus mixers seem to support this capability.
     */
    public static final int ON_AIR_FLAG = 0x08;

    /**
     * The bit within the status flag that indicates the player is synced, as illustrated in Figure 12 of the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public static final int SYNCED_FLAG = 0x10;

    /**
     * The bit within the status flag that indicates the player is the tempo master, as illustrated in Figure 12 of
     * the <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public static final int MASTER_FLAG = 0x20;

    /**
     * The bit within the status flag that indicates the player is playing, as illustrated in Figure 12 of the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public static final int PLAYING_FLAG = 0x40;

    /**
     * The device number of the player from which the track was loaded, if any; labeled <i>P<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    private final int trackSourcePlayer;

    /**
     * Get the device number of the player from which the track was loaded, if any; labeled <i>P<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     *
     * @return the device number from which the current track was loaded
     */
    public int getTrackSourcePlayer() { return trackSourcePlayer; }

    /**
     * The possible values describing from where the track was loaded, labeled <i>S<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public static enum TrackSourceSlot {
        NO_TRACK, CD_SLOT, SD_SLOT, USB_SLOT, UNKNOWN
    }

    /**
     * The slot from which the track was loaded, if any; labeled <i>S<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    private final TrackSourceSlot trackSourceSlot;

    /**
     * Get the slot from which the track was loaded, if any; labeled <i>S<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     *
     * @return the slot from which the current track was loaded
     */
    public TrackSourceSlot getTrackSourceSlot() { return trackSourceSlot; }

    /**
     * The possible values describing the track type, labeled <i>t<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public static enum TrackType {
        NO_TRACK, REKORDBOX, CD_DIGITAL_AUDIO, UNKNOWN
    }

    /**
     * The type of the track that was loaded, if any; labeled <i>t<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    private final TrackType trackType;

    /**
     * Get the type of the track was loaded, if any; labeled <i>t<sub>r</sub></i> in Figure 11 of
     * the <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     *
     * @return the type of track that is currently loaded
     */
    public TrackType getTrackType() { return trackType; }

    /**
     * The rekordbox ID of the track that was loaded, if any; labeled<i>rekordbox</i> in Figure 11 of
     * the <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    private final int rekordboxId;

    /**
     * Get the rekordbox ID of the track that was loaded, if any; labeled<i>rekordbox</i> in Figure 11 of
     * the <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     * Will be zero if no track is loaded, and is simply the track number when an ordinary audio CD track has been
     * loaded.
     *
     * @return the rekordbok database ID of the current track
     */
    public int getRekordboxId() { return rekordboxId; }

    /**
     * The possible values of the first play state found in the packet, labeled <i>P<sub>1</sub></i> in Figure 11 of
     * the <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public static enum PlayState1 {
        NO_TRACK, PLAYING, LOOPING, PAUSED, CUED, SEARCHING, ENDED, UNKNOWN
    }

    /**
     * The first play state found in the packet, labeled <i>P<sub>1</sub></i> in Figure 11 of the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    private final PlayState1 playState1;

    /**
     * Get the first play state found in the packet, labeled <i>P<sub>1</sub></i> in Figure 11 of the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     *
     * @return the first play state element
     */
    public PlayState1 getPlayState1() {
        return playState1;
    }

    /**
     * The possible values of the second play state found in the packet, labeled <i>P<sub>2</sub></i> in Figure 11 of
     * the <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public static enum PlayState2 {
        MOVING, STOPPED, UNKNOWN
    }

    /**
     * The second play state found in the packet, labeled <i>P<sub>2</sub></i> in Figure 11 of the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    private final PlayState2 playState2;

    /**
     * Get the second play state found in the packet, labeled <i>P<sub>2</sub></i> in Figure 11 of the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     *
     * @return the second play state element
     */
    public PlayState2 getPlayState2() {
        return playState2;
    }

    /**
     * The possible values of the third play state found in the packet, labeled <i>P<sub>3</sub></i> in Figure 11 of
     * the <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    public static enum PlayState3 {
        NO_TRACK, PAUSED_OR_REVERSE, FORWARD_VINYL, FORWARD_CDJ, UNKNOWN
    }

    /**
     * The third play state found in the packet, labeled <i>P<sub>3</sub></i> in Figure 11 of the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
     */
    private final PlayState3 playState3;

    /**
     * Get the third play state found in the packet, labeled <i>P<sub>3</sub></i> in Figure 11 of the
     * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis document</a>.
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
     * Determine the enum value corresponding to the track source slot found in the packet.
     *
     * @return the proper value
     */
    private TrackSourceSlot findTrackSourceSlot() {
        switch (packetBytes[41]) {
            case 0: return TrackSourceSlot.NO_TRACK;
            case 1: return TrackSourceSlot.CD_SLOT;
            case 2: return TrackSourceSlot.SD_SLOT;
            case 3: return TrackSourceSlot.USB_SLOT;
            default: return TrackSourceSlot.UNKNOWN;
        }
    }

    /**
     * Determine the enum value corresponding to the track type found in the packet.
     *
     * @return the proper value
     */
    private TrackType findTrackType() {
        switch (packetBytes[42]) {
            case 0: return TrackType.NO_TRACK;
            case 1: return TrackType.REKORDBOX;
            case 5: return TrackType.CD_DIGITAL_AUDIO;
            default: return TrackType.UNKNOWN;
        }
    }

    /**
     * Determine the enum value corresponding to the first play state found in the packet.
     *
     * @return the proper value
     */
    private PlayState1 findPlayState1() {
        switch (packetBytes[123]) {
            case 0: return PlayState1.NO_TRACK;
            case 3: return PlayState1.PLAYING;
            case 4: return PlayState1.LOOPING;
            case 5: return PlayState1.PAUSED;
            case 6: return PlayState1.CUED;
            case 9: return PlayState1.SEARCHING;
            case 17: return PlayState1.ENDED;
            default: return PlayState1.UNKNOWN;
        }
    }

    /**
     * Determine the enum value corresponding to the second play state found in the packet.
     *
     * @return the proper value
     */
    private PlayState2 findPlayState2() {
        switch (packetBytes[139]) {
            case 106:
            case 122:
                return PlayState2.MOVING;

            case 110:
            case 126:
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
        switch (packetBytes[157]) {
            case 0: return PlayState3.NO_TRACK;
            case 1: return PlayState3.PAUSED_OR_REVERSE;
            case 9: return PlayState3.FORWARD_VINYL;
            case 13: return PlayState3.FORWARD_CDJ;
            default: return PlayState3.UNKNOWN;
        }
    }

    /**
     * Constructor sets all the immutable interpreted fields based on the packet content.
     *
     * @param packet the beat announcement packet that was received
     */
    public CdjStatus(DatagramPacket packet) {
        super(packet, "CDJ status", packet.getLength());
        if (packetBytes.length != 208 && packetBytes.length != 212) {
            throw new IllegalArgumentException("CDJ status packet must be 208 or 212 bytes long");
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
    public double getEffectiveTempo() {
        return bpm * Util.pitchToMultiplier(pitch) / 100.0;
    }

    /**
     * Was the CDJ playing a track when this update was sent?
     *
     * @return true if the play flag was set, or, if this seems to be a non-nexus player, if <em>P<sub>1</sub></em>
     *         has a value corresponding to a playing state.
     */
    public boolean isPlaying() {
        if (packetBytes.length == 212) {
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
     * Is USB media available somewhere on the network?
     *
     * @return true if some player has USB media that can be linked to
     */
    public boolean isLinkedUsbAvailable() {
        return (packetBytes[117] != 0);
    }

    /**
     * Check if the player is doing anything.
     *
     * @return true if the player is playing, searching, or loading a track
     */
    public boolean isBusy() {
        return packetBytes[39] != 0;
    }

    /**
     * Get the track number of the loaded track. Identifies the track within a playlist or other scrolling list of
     * tracks in the CDJ's browse interface.
     *
     * @return the index of the current track
     */
    public int getTrackNumber() {
        return (int)Util.bytesToNumber(packetBytes, 50, 2);
    }

    /**
     * Get the counter identifying how many times the tempo master has changed.
     *
     * @return a number that seems to increment whenever a new player becomes tempo master.
     */
    public int getSyncNumber() {
        return (int)Util.bytesToNumber(packetBytes, 134, 2);
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
    public int getCueCountdown() {
        return (int)Util.bytesToNumber(packetBytes, 164, 2);
    }

    /**
     * Format a cue countdown indicator in the same way as the CDJ would at this point in the track.
     *
     * @return the value that the CDJ would display to indicate the distance to the next cue
     * @see #getCueCountdown()
     */
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
        return "CDJ status: Device " + deviceNumber + ", name: " + deviceName + ", busy? " + isBusy() +
                ", pitch: " + String.format("%+.2f%%", Util.pitchToPercentage(pitch)) +
                ", rekordbox ID:" + getRekordboxId() + ", from player: " + getTrackSourcePlayer() +
                ", in slot: " + getTrackSourceSlot() + ", track type: " + getTrackType() +
                ", track: " + getTrackNumber() + ", track BPM: " + String.format("%.1f", bpm / 100.0) +
                ", effective BPM: " + String.format("%.1f", getEffectiveTempo()) +
                ", beat: " + getBeatNumber() + ", beat within bar: " + getBeatWithinBar() +
                ", cue: " + formatCueCountdown() +
                ", Playing? " + isPlaying() + ", Master? " + isTempoMaster() +
                ", Synced? " + isSynced() + ", On-Air? " + isOnAir();
    }

}
