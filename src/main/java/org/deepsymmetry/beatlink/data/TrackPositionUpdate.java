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
     * If {@code true}, this was created in response to a beat packet or precise position packet, so we know exactly
     * where the player was at that point. Otherwise, we infer position based on how long has elapsed since
     * the previous beat packet, and the intervening playback pitch and direction.
     */
    public final boolean definitive;

    /**
     * If {@code true}, we are receiving precise position packets from this player, so we know exactly where the
     * player is even when it is not playing, or jumping or looping within beats.
     */
    public final boolean precise;

    /**
     * If {@code true}, this position update resulted from the receipt of a beat packet.
     */
    public final boolean fromBeat;

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
     * Constructor simply sets the fields of this immutable value class. Backwards-compatible version which
     * infers a {@code false} value for {@code precise}.
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
        this(timestamp, milliseconds, beatNumber, definitive, playing, pitch, reverse, beatGrid,
                false, false);
    }

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
     * @param precise this position was derived from a high-precision position packet, directly or through a beat
     * @param fromBeat this position was created in response to a beat packet
     */
    public TrackPositionUpdate(long timestamp, long milliseconds, int beatNumber, boolean definitive,
                               boolean playing, double pitch, boolean reverse, BeatGrid beatGrid,
                               boolean precise, boolean fromBeat) {
        this.timestamp = timestamp;
        this.milliseconds = milliseconds;
        this.beatNumber = beatNumber;
        this.definitive = definitive;
        this.playing = playing;
        this.pitch = pitch;
        this.reverse = reverse;
        this.beatGrid = beatGrid;
        this.precise = precise;
        this.fromBeat = fromBeat;
    }

    @Override
    public String toString() {
        return "TrackPositionUpdate[timestamp:" + timestamp + ", milliseconds:" + milliseconds +
                ", beatNumber:" + beatNumber + ", definitive:" + definitive + ", playing:" + playing +
                ", pitch:" + String.format("%.2f", pitch) + ", reverse:" + reverse +
                ", beatGrid:" + beatGrid + ", precise:" + precise + "]";
    }
}
