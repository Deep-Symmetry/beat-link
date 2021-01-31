package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;

/**
 * Provides notification when requested track analysis information associated with a player changes.
 *
 * @author James Elliott
 */
public class AnalysisTagUpdate {
    /**
     * The player number for which a track analysis information change has occurred.
     */
    public final int player;

    /**
     * The file extension identifying which specific track analysis file this section came from.
     */
    public final String fileExtension;

    /**
     * The four-character tag type code identifying the specific analysis section which has changed.
     */
    public final String typeTag;

    /**
     * The parsed track analysis section which is now associated with the track loaded in the player's main deck. Will be
     * {@code null} if we don't have any information available (including for a brief period after a new track has been
     * loaded while we are requesting the analysis file). Not all tracks have all tags; for example, only tracks
     * which have had phrase analysis performed and which have been exported by rekordbox 6 or later will include a
     * "PSSI" section in their ".EXT" file.
     */
    public final RekordboxAnlz.TaggedSection taggedSection;

    AnalysisTagUpdate(final int player, final String fileExtension, final String typeTag, final RekordboxAnlz.TaggedSection taggedSection) {
        this.player = player;
        this.fileExtension = fileExtension;
        this.typeTag = typeTag;
        this.taggedSection = taggedSection;
    }

    @Override
    public String toString() {
        return "AnalysisTagUpdate[player:" + player + ", file extension:" + fileExtension + ", tag type:" + typeTag +
                ", tagged section:" + taggedSection + "]";
    }
}
