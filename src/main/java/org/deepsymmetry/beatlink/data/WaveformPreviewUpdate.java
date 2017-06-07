package org.deepsymmetry.beatlink.data;

/**
 * Provides notification when the waveform preview associated with a player changes.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class WaveformPreviewUpdate {
    /**
     * The player number for which a waveform preview change has occurred.
     */
    public final int player;

    /**
     * The waveform preview which is now associated with the track loaded in the player's main deck. Will be
     * {@code null} if we don't have any preview available (including for a brief period after a new track has been
     * loaded while we are requesting the waveform preview).
     */
    public final WaveformPreview preview;

    WaveformPreviewUpdate(int player, WaveformPreview preview) {
        this.player = player;
        this.preview = preview;
    }

    @Override
    public String toString() {
        return "WaveformPreviewUpdate[player:" + player + ", preview:" + preview + "]";
    }
}
