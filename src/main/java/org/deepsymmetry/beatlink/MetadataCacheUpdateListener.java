package org.deepsymmetry.beatlink;

import java.io.File;

/**
 * The listener interface for receiving updates on the progress of creating a metadata cache file.
 *
 * Classes that are interested in displaying a progress bar during the (potentially lengthy) process, and allowing
 * the user to cancel it, can implement this interface.
 *
 * The listener object created from that class is then passed to
 * {@link MetadataFinder#createMetadataCache(int, CdjStatus.TrackSourceSlot, File, MetadataCacheUpdateListener)}
 * in order to be able to display progress during the process of creating the cache file. As each track is added
 * to the cache, {@link #cacheUpdateContinuing(TrackMetadata, int, int)} is called, with the most recent track
 * added, the number of tracks that have been added so far, and the total that need to  be added. If it returns
 * {@code false}, the creation of the cache file will be canceled.
 *
 * @author James Elliott
 */
public interface MetadataCacheUpdateListener {
    /**
     * Called to inform the listener that another track has been added to the metadata cache file being created.
     * Allows the progress to be displayed, and for the process to be canceled if so desired.
     *
     * @param lastTrackAdded the most recent track that has been added to the metadata cache file
     * @param tracksAdded the number of tracks that have been added to the cache so far
     * @param totalTracksToAdd the total number of tracks that need to be added to the cache
     *
     * @return {@code true} if creation of the cache should continue, {@code false} if it should be aborted
     */
    boolean cacheUpdateContinuing(TrackMetadata lastTrackAdded, int tracksAdded, int totalTracksToAdd);
}
