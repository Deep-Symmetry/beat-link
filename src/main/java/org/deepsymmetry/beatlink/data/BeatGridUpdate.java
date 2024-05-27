package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;

/**
 * Provides notification when the beat grid associated with a player changes.
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public class BeatGridUpdate {
    /**
     * The player number for which a beat grid change has occurred.
     */
    @API(status = API.Status.STABLE)
    public final int player;

    /**
     * The beat grid which is now associated with the track loaded in the player's main deck. Will be {@code null}
     * if we don't have any beat grid available (including for a brief period after a new track has been loaded
     * while we are requesting the beat grid).
     */
    @API(status = API.Status.STABLE)
    public final BeatGrid beatGrid;

    BeatGridUpdate(int player, BeatGrid beatGrid) {
        this.player = player;
        this.beatGrid = beatGrid;
    }

    @Override
    public String toString() {
        return "BeatGridUpdate[player:" + player + ", beatGrid:" + beatGrid + "]";
    }
}
