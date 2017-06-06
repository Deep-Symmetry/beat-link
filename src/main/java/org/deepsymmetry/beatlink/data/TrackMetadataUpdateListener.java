package org.deepsymmetry.beatlink.data;

/**
 * The listener interface for receiving updates when the metadata available about a track loaded in any player changes.
 *
 * Classes that are interested having up-to-date information about metadata for loaded tracks can implement this
 * interface, and then pass the implementing instance to
 * {@link MetadataFinder#addTrackMetadataUpdateListener(TrackMetadataUpdateListener)}.
 * Then, whenever a player loads a new track (or the set of available metadata changes, so we know more or less about
 * tracks in any loaded player), {@link #metadataChanged(TrackMetadataUpdate)} will be called, with the currently
 * available metadata about the track (if any) loaded in the player.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public interface TrackMetadataUpdateListener {
    /**
     * Called when the metadata available for a player has changed.
     *
     * @param update provides information about what has changed
     */
    void metadataChanged(TrackMetadataUpdate update);
}
