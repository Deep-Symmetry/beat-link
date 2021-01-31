package org.deepsymmetry.beatlink.data;

/**
 * <p>The listener interface for receiving updates when the song structure information available for a track loaded in
 * any player changes.</p>
 *
 * <p>Classes that are interested having up-to-date information about song structures for loaded tracks can implement
 * this interface, and then pass the implementing instance to
 * {@link SongStructureFinder#addSongStructureListener(SongStructureListener)}.
 * Then, whenever a player loads a new track (or the set of song structure information changes, so we know more
 * or less about tracks in any loaded player),
 * {@link #structureChanged(SongStructureUpdate)} will be called, with the currently available song structure
 * information (if any) for the track loaded in the player.</p>
 *
 * @author James Elliott
 */
public interface SongStructureListener {
    /**
     * Called when the song structure information available for a player has changed.
     *
     * @param update provides information about what has changed
     */
    void structureChanged(SongStructureUpdate update);
}
