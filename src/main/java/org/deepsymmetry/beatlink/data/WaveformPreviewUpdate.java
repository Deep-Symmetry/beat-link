package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;

/**
 * Provides notification when the waveform preview associated with a player changes.
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public class WaveformPreviewUpdate {
    /**
     * The player number for which a waveform preview change has occurred.
     */
    @API(status = API.Status.STABLE)
    public final int player;

    /**
     * The waveform preview which is now associated with the track loaded in the player's main deck. Will be
     * {@code null} if we don't have any preview available (including for a brief period after a new track has been
     * loaded while we are requesting the waveform preview).
     */
    @API(status = API.Status.STABLE)
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
