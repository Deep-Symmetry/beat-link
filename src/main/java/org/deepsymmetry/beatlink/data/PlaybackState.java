package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;

/**
 * Captures the playback state of a single player that has the track loaded, as an immutable value class.
 */
@API(status = API.Status.STABLE)
public class PlaybackState {

    /**
     * The player number whose playback state this represents.
     */
    @API(status = API.Status.STABLE)
    public final int player;

    /**
     * The current playback position of the player in milliseconds.
     */
    @API(status = API.Status.STABLE)
    public final long position;

    /**
     * Whether the player is actively playing the track.
     */
    @API(status = API.Status.STABLE)
    public final boolean playing;

    /**
     * Create an instance to represent a particular playback state.
     *
     * @param player the player number whose playback state this represents
     * @param position the current playback position in milliseconds
     * @param playing whether the player is actively playing the track
     */
    @API(status = API.Status.STABLE)
    public PlaybackState(int player, long position, boolean playing) {
        this.player = player;
        this.position = position;
        this.playing = playing;
    }

    @Override
    public String toString() {
        return "PlaybackState[player=" + player + ", position=" + position + ", playing=" + playing + "]";
    }
}
