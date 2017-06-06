package org.deepsymmetry.beatlink.data;

/**
 * Provides notification when the album art associated with a player changes.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class AlbumArtUpdate {

    /**
     * The player number for which an album art change has occurred.
     */
    public final int player;

    /**
     * The album art which is now associated with the track loaded in the player's main deck. Will be {@code null}
     * if we don't have any art available (including for a brief period after a new track has been loaded
     * while we are requesting the art).
     */
    public final AlbumArt art;

    AlbumArtUpdate(int player, AlbumArt art) {
        this.player = player;
        this.art = art;
    }
}
