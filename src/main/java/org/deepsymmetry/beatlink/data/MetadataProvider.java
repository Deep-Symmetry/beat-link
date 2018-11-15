package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.MediaDetails;

import java.util.List;

/**
 * A class that is able to provide metadata for a loaded track without the need to query the source player can
 * implement this interface and register itself with the {@link MetadataFinder#addMetadataProvider(MetadataProvider)}.
 * Examples include cue lists that store their own copies of the metadata. Metadata caches created by Beat Link
 * as ZIP files also implement this interface, but they are treated specially because they existed before it, and
 * for backwards compatibility need to support files that do not know what media they were created from.
 *
 * TODO: Reassess whether this is needed any longer given the more-reliable NFS-based metadata approach.
 *
 * @since 0.5.0
 */
@SuppressWarnings("WeakerAccess")
public interface MetadataProvider {
    /**
     * Get the list of media devices for which metadata can be offered by this provider. If the provider can
     * offer metadata for all media, return an empty list, and it will always be consulted. Otherwise, it will
     * only be consulted for media that were listed in response to this call when it was registered using
     * {@link MetadataFinder#addMetadataProvider(MetadataProvider)}.
     *
     * @return the media device descriptors for which we have at least one available metadata item.
     */
    List<MediaDetails> supportedMedia();

    /**
     * Get metadata for a particular track, if it is available.
     *
     * @param sourceMedia the media to which the track belongs, for use by providers which store metadata from multiple
     *                    sources
     * @param track identifies the track whose metadata is desired; since track metadata always has a player and
     *              slot associated with it, those are needed as well as the rekordbox ID in order to create it
     *
     * @return the metadata corresponding to that track, or {@code null} if it is not a track for which we have metadata
     */
    TrackMetadata getTrackMetadata(MediaDetails sourceMedia, DataReference track);

    /**
     * Get a particular album art image, if it is available.
     *
     * @param sourceMedia the media to which the art belongs, for use by providers which store metadata from multiple
     *                    sources
     * @param art identifies the album art whose metadata is desired; since art always has a player and slot
     *            associated with it, those are needed as well as the rekordbox ID in order to create it
     *
     * @return the art with the specified id, or {@code null} if we don't have it to offer
     */
    AlbumArt getAlbumArt(MediaDetails sourceMedia, DataReference art);

    /**
     * Get the beat grid for a particular track, if it is available.
     *
     * @param sourceMedia the media to which the track belongs, for use by providers which store metadata from multiple
     *                    sources
     * @param track identifies the track whose beat grid is desired; since beat grids always have a player and
     *              slot associated with them, those are needed as well as the rekordbox ID in order to create one
     *
     * @return the beat grid corresponding to that track, or {@code null} if we don't have one to offer
     */
    BeatGrid getBeatGrid(MediaDetails sourceMedia, DataReference track);


    /**
     * Get the cue list for a particular track, if it is available.
     *
     * @param sourceMedia the media to which the track belongs, for use by providers which store metadata from multiple
     *                    sources
     * @param rekordboxId identifies the track whose cue list is desired
     *
     * @return the cue list corresponding to that track, or {@code null} if we don't have one to offer
     */
    CueList getCueList(MediaDetails sourceMedia, int rekordboxId);

    /**
     * Get the waveform preview for a particular track, if it is available.
     *
     * @param sourceMedia the media to which the track belongs, for use by providers which store metadata from multiple
     *                    sources
     * @param track identifies the track whose waveform preview is desired; since beat grids always have a player and
     *              slot associated with them, those are needed as well as the rekordbox ID in order to create one
     *
     * @return the waveform preview corresponding to that track, or {@code null} if we don't have one to offer
     */
    WaveformPreview getWaveformPreview(MediaDetails sourceMedia, DataReference track);

    /**
     * Get the waveform detail for a particular track, if it is available.
     *
     * @param sourceMedia the media to which the track belongs, for use by providers which store metadata from multiple
     *                    sources
     * @param track identifies the track whose waveform detail is desired; since beat grids always have a player and
     *              slot associated with them, those are needed as well as the rekordbox ID in order to create one
     *
     * @return the waveform detail corresponding to that track, or {@code null} if we don't have one to offer
     */
    WaveformDetail getWaveformDetail(MediaDetails sourceMedia, DataReference track);
}
