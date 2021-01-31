package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;

/**
 * Provides notification when the song structure information associated with a player changes.
 *
 * @author James Elliott
 */
public class SongStructureUpdate {
    /**
     * The player number for which a song structure change has occurred.
     */
    public final int player;

    /**
     * The song structure information which is now associated with the track loaded in the player's main deck. Will be
     * {@code null} if we don't have any information available (including for a brief period after a new track has been
     * loaded while we are requesting the waveform detail). Not all tracks have song structure information, only tracks
     * which have had phrase analysis performed and which have been exported by rekordbox 6 or later will include it.
     */
    public final RekordboxAnlz.SongStructureTag structure;

    SongStructureUpdate(int player, RekordboxAnlz.SongStructureTag structure) {
        this.player = player;
        this.structure = structure;
    }

    @Override
    public String toString() {
        return "SongStructureUpdate[player:" + player + ", song structure:" + structure + "]";
    }
}
