package org.deepsymmetry.beatlink.data;

/**
 * Keeps track of the most recent information we have received from a player from which we have been able to compute
 * a track position.
 */
@SuppressWarnings("WeakerAccess")
public class TrackPositionUpdate {

    /**
     * When this update was received.
     */
    public final long timestamp;

    /**
     * How far into the track has the player reached.
     */
    public final long milliseconds;

    /**
     * The beat number that was reported (or incremented) by this update.
     */
    public final int beatNumber;

    /**
     * If {@code true}, this was created in response to a beat packet, so we know exactly where the player was at that
     * point. Otherwise, we infer position based on how long has elapsed since the previous beat packet, and the
     * intervening playback pitch and direction.
     */
    public final boolean definitive;

    /**
     * If {@code true}, the player reported that it was playing when the update was received.
     */
    public final boolean playing;

    /**
     * The playback pitch when this update was created.
     */
    public final double pitch;

    /**
     * If {@code true}, the player was playing backwards when this update was created.
     */
    public final boolean reverse;

    /**
     * The track metadata against which this update was calculated, so that if it has changed, we know to discard
     * the update.
     */
    public final BeatGrid beatGrid;

    /**
     * Constructor simply sets the fields of this immutable value class.
     *
     * @param timestamp when this update was received
     * @param milliseconds how far into the track has the player reached
     * @param beatNumber the beat number that was reported (or incremented) by this update
     * @param definitive indicates if this was based on a direct report of track position from the player (i.e. a beat)
     * @param playing indicates whether the player was actively playing a track when this update was received
     * @param pitch the playback pitch (where 1.0 is normal speed) when this update was received
     * @param reverse indicates if the player was playing backwards when this update was received
     * @param beatGrid the track beat grid that was used to calculate the update
     */
    public TrackPositionUpdate(long timestamp, long milliseconds, int beatNumber, boolean definitive,
                               boolean playing, double pitch, boolean reverse, BeatGrid beatGrid) {
        this.timestamp = timestamp;
        this.milliseconds = milliseconds;
        this.beatNumber = beatNumber;
        this.definitive = definitive;
        this.playing = playing;
        this.pitch = pitch;
        this.reverse = reverse;
        this.beatGrid = beatGrid;
    }

    @Override
    public String toString() {
        return "TrackPositionUpdate[timestamp:" + timestamp + ", milliseconds:" + milliseconds +
                ", beatNumber:" + beatNumber + ", definitive:" + definitive + ", playing:" + playing +
                ", pitch:" + String.format("%.2f", pitch) + ", reverse:" + reverse +
                ", beatGrid:" + beatGrid + "]";
    }
}
