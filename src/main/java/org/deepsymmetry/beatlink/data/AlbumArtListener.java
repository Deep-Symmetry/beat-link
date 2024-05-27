package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;

/**
 * <p>The listener interface for receiving updates when the album art available for a track loaded in any player
 * changes.</p>
 *
 * <p>Classes that are interested having up-to-date information about album art for loaded tracks can implement this
 * interface, and then pass the implementing instance to
 * {@link ArtFinder#addAlbumArtListener(AlbumArtListener)}.
 * Then, whenever a player loads a new track (or the set of available album art changes, so we know more or less about
 * tracks in any loaded player), {@link #albumArtChanged(AlbumArtUpdate)} will be called, with the currently
 * available album art for the track (if any) loaded in the player.</p>
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public interface AlbumArtListener {
    /**
     * Called when the album art available for a player has changed.
     *
     * @param update provides information about what has changed
     */
    @API(status = API.Status.STABLE)
    void albumArtChanged(AlbumArtUpdate update);
}
