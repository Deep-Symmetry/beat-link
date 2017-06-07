package org.deepsymmetry.beatlink.data;

/**
 * Provides notification when the waveform detail associated with a player changes.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class WaveformDetailUpdate {
    /**
     * The player number for which a waveform detail change has occurred.
     */
    public final int player;

    /**
     * The waveform detail which is now associated with the track loaded in the player's main deck. Will be
     * {@code null} if we don't have any detail available (including for a brief period after a new track has been
     * loaded while we are requesting the waveform detail).
     */
    public final WaveformDetail detail;

    WaveformDetailUpdate(int player, WaveformDetail detail) {
        this.player = player;
        this.detail = detail;
    }

    @Override
    public String toString() {
        return "WaveformDetailUpdate[player:" + player + ", detail:" + detail + "]";
    }
}
