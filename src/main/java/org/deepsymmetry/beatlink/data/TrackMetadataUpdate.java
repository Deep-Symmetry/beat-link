package org.deepsymmetry.beatlink.data;

/**
 * Provides notification when the track metadata associated with a player changes.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class TrackMetadataUpdate {

    /**
     * The player number for which a metadata change has occurred.
     */
    public final int player;

    /**
     * The metadata which is now associated with the track loaded in the player's main deck. Will be {@code null}
     * if we don't have any information available (including for a brief period after a new track has been loaded
     * while we are requesting the metadata).
     */
    public final TrackMetadata metadata;

    TrackMetadataUpdate(int player, TrackMetadata metadata) {
        this.player = player;
        this.metadata = metadata;
    }

    @Override
    public String toString() {
        return "TrackMetadataUpdate[player:" + player + ", metadata:" + metadata + "]";
    }
}
