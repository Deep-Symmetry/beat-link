package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.MediaDetails;
import org.deepsymmetry.beatlink.dbserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides support for navigating the menu hierarchy offered by the dbserver on a player for a particular media slot.
 * Note that for historical reasons, loading track metadata, playlists, and the full track list are performed by the
 * {@link MetadataFinder}, even though those are technically menu operations.
 *
 * @since 0.4.0
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public class MenuLoader {

    private static final Logger logger = LoggerFactory.getLogger(MenuLoader.class);

    /**
     * Ask the specified player for its top-level menu of menus. The {@link MetadataFinder} must be running for us to
     * know the right kind of message to send, because it depends on whether the slot holds a rekordbox database or not.
     * If we can't tell (because it's not running), we will just guess that there is one, and perhaps get back nothing.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details, although it does not seem to have an effect on the root menu
     *
     * @return the entries in the top level menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestRootMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    final MediaDetails details = MetadataFinder.getInstance().getMediaDetailsFor(slotReference);
                    final CdjStatus.TrackType mediaType = details == null? CdjStatus.TrackType.REKORDBOX : details.mediaType;

                    final Message response = client.menuRequestTyped(Message.KnownType.ROOT_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            mediaType, new NumberField(sortOrder), new NumberField(0xffffff));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, mediaType, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting root menu");
    }

    /**
     * Ask the specified player for a Playlist menu. This boils down to a call to
     * {@link MetadataFinder#requestPlaylistItemsFrom(int, CdjStatus.TrackSourceSlot, int, int, boolean)} asking for
     * the playlist folder with ID 0, but it is also made available here since this is likely where people will be
     * looking for the capability. To get the contents of individual playlists or sub-folders, pass the playlist or
     * folder ID obtained by calling this to that function.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the playlists and folders in the playlist menu
     *
     * @see MetadataFinder#requestPlaylistItemsFrom(int, CdjStatus.TrackSourceSlot, int, int, boolean)
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestPlaylistMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        return MetadataFinder.getInstance().requestPlaylistItemsFrom(slotReference.player, slotReference.slot, sortOrder,
                0, true);
    }

    /**
     * Ask the specified player for a History menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details, although it does not seem to have an effect on the history menu
     *
     * @return the entries in the history menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestHistoryMenuFrom(final SlotReference slotReference, final int sortOrder)
        throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting History menu.");
                    Message response = client.menuRequest(Message.KnownType.HISTORY_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting history menu");
    }

    /**
     * Ask the specified player a History playlist.
     *
     * @param slotReference the player and slot for which the playlist is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param historyId identifies which history session's playlist is desired
     *
     * @return the entries in the history playlist
     *
     * @throws Exception if there is a problem obtaining the playlist
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestHistoryPlaylistFrom(final SlotReference slotReference, final int sortOrder, final int historyId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting History playlist.");
                    Message response = client.menuRequest(Message.KnownType.TRACK_MENU_FOR_HISTORY_REQ, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(historyId));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting history playlist");
    }

    /**
     * Ask the specified player for a Track menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the track menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestTrackMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> MetadataFinder.getInstance().getFullTrackList(slotReference.slot, client, sortOrder);

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting track menu");
    }

    /**
     * Ask the specified player for an Artist menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the artist menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestArtistMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Artist menu.");
                    Message response = client.menuRequest(Message.KnownType.ARTIST_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting artist menu");
    }

    /**
     * Ask the specified player for an Artist Album menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param artistId  the artist whose album menu is desired
     *
     * @return the entries in the artist album menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestArtistAlbumMenuFrom(final SlotReference slotReference, final int sortOrder, final int artistId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Artist Album menu.");
                    Message response = client.menuRequest(Message.KnownType.ALBUM_MENU_FOR_ARTIST_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder), new NumberField(artistId));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting artist album menu");
    }

    /**
     * Ask the specified player for an Artist Album Tracks menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param artistId the artist whose album track menu is desired
     * @param albumId the album whose track menu is desired, or -1 for all albums
     *
     * @return the entries in the artist album tracks menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestArtistAlbumTrackMenuFrom(final SlotReference slotReference, final int sortOrder, final int artistId, final int albumId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Artist Album Track menu.");
                    Message response = client.menuRequest(Message.KnownType.TRACK_MENU_FOR_ARTIST_AND_ALBUM, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(artistId), new NumberField(albumId));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting artist album tracks menu");
    }

    /**
     * Ask the specified player for an Original Artist menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the original artist menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestOriginalArtistMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Original Artist menu.");
                    Message response = client.menuRequest(Message.KnownType.ORIGINAL_ARTIST_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting artist menu");
    }

    /**
     * Ask the specified player for an Original Artist Album menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param artistId  the original artist whose album menu is desired
     *
     * @return the entries in the original artist album menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestOriginalArtistAlbumMenuFrom(final SlotReference slotReference, final int sortOrder, final int artistId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Original Artist Album menu.");
                    Message response = client.menuRequest(Message.KnownType.ALBUM_MENU_FOR_ORIGINAL_ARTIST_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder), new NumberField(artistId));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting artist album menu");
    }

    /**
     * Ask the specified player for an Original Artist Album Tracks menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param artistId the original artist whose album track menu is desired
     * @param albumId the album whose track menu is desired, or -1 for all albums
     *
     * @return the entries in the original artist album tracks menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestOriginalArtistAlbumTrackMenuFrom(final SlotReference slotReference, final int sortOrder, final int artistId, final int albumId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Original Artist Album Track menu.");
                    Message response = client.menuRequest(Message.KnownType.TRACK_MENU_FOR_ORIGINAL_ARTIST_AND_ALBUM, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(artistId), new NumberField(albumId));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting artist album tracks menu");
    }

    /**
     * Ask the specified player for a Remixer menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the remixer menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestRemixerMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Remixer menu.");
                    Message response = client.menuRequest(Message.KnownType.REMIXER_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting artist menu");
    }

    /**
     * Ask the specified player for a Remixer Album menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param artistId the remixer whose album menu is desired
     *
     * @return the entries in the remixer album menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestRemixerAlbumMenuFrom(final SlotReference slotReference, final int sortOrder, final int artistId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Remixer Album menu.");
                    Message response = client.menuRequest(Message.KnownType.ALBUM_MENU_FOR_REMIXER_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder), new NumberField(artistId));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting artist album menu");
    }

    /**
     * Ask the specified player for a Remixer Album Tracks menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see of the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param artistId the remixer whose album track menu is desired
     * @param albumId the album whose track menu is desired, or -1 for all albums
     *
     * @return the entries in the remixer album tracks menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestRemixerAlbumTrackMenuFrom(final SlotReference slotReference, final int sortOrder, final int artistId, final int albumId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Remixer Album Track menu.");
                    Message response = client.menuRequest(Message.KnownType.TRACK_MENU_FOR_REMIXER_AND_ALBUM, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(artistId), new NumberField(albumId));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting artist album tracks menu");
    }

    /**
     * Ask the specified player for an Album Track menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param albumId the album whose track menu is desired
     *
     * @return the entries in the album track menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestAlbumTrackMenuFrom(final SlotReference slotReference, final int sortOrder, final int albumId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Album Track menu.");
                    Message response = client.menuRequest(Message.KnownType.TRACK_MENU_FOR_ALBUM_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder), new NumberField(albumId));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting album tracks menu");
    }

    /**
     * Ask the specified player for a Genre menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the genre menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestGenreMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Genre menu.");
                    Message response = client.menuRequest(Message.KnownType.GENRE_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting genre menu");
    }

    /**
     * Ask the specified player for a Genre Artists menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param genreId the genre whose artist menu is desired
     *
     * @return the entries in the genre artists menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestGenreArtistMenuFrom(final SlotReference slotReference, final int sortOrder, final int genreId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Genre Artist menu.");
                    Message response = client.menuRequest(Message.KnownType.ARTIST_MENU_FOR_GENRE_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder), new NumberField(genreId));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting genre artists menu");
    }

    /**
     * Ask the specified player for a Genre Artist Albums menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param genreId the genre whose artist album menu is desired
     * @param artistId the artist whose album menu is desired, or -1 for all artists
     *
     * @return the entries in the genre artist albums menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestGenreArtistAlbumMenuFrom(final SlotReference slotReference, final int sortOrder, final int genreId, final int artistId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Genre Artist Album menu.");
                    Message response = client.menuRequest(Message.KnownType.ALBUM_MENU_FOR_GENRE_AND_ARTIST, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(genreId), new NumberField(artistId));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting genre artist albums menu");
    }

    /**
     * Ask the specified player for a Genre Artist Album Tracks menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param genreId the genre whose artist album track menu is desired
     * @param artistId the artist whose album track menu is desired, or -1 for all artists
     * @param albumId the album whose track menu is desired, or -1 for all albums
     *
     * @return the entries in the genre artist album tracks menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestGenreArtistAlbumTrackMenuFrom(final SlotReference slotReference, final int sortOrder, final int genreId,
                                                              final int artistId, final int albumId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Genre Artist Album Track menu.");
                    Message response = client.menuRequest(Message.KnownType.TRACK_MENU_FOR_GENRE_ARTIST_AND_ALBUM, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(genreId), new NumberField(artistId), new NumberField(albumId));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting genre artist album tracks menu");
    }

    /**
     * Ask the specified player for a Label menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the label menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestLabelMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Label menu.");
                    Message response = client.menuRequest(Message.KnownType.LABEL_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting genre menu");
    }

    /**
     * Ask the specified player for a Label Artists menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param labelId the label whose artist menu is desired
     *
     * @return the entries in the label artists menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestLabelArtistMenuFrom(final SlotReference slotReference, final int sortOrder, final int labelId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Label Artist menu.");
                    Message response = client.menuRequest(Message.KnownType.ARTIST_MENU_FOR_LABEL_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder), new NumberField(labelId));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting genre artists menu");
    }

    /**
     * Ask the specified player for a Label Artist Albums menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param labelId the label whose artist album menu is desired
     * @param artistId the artist whose album menu is desired, or -1 for all artists
     *
     * @return the entries in the label artist albums menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestLabelArtistAlbumMenuFrom(final SlotReference slotReference, final int sortOrder, final int labelId, final int artistId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Label Artist Album menu.");
                    Message response = client.menuRequest(Message.KnownType.ALBUM_MENU_FOR_LABEL_AND_ARTIST, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(labelId), new NumberField(artistId));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting genre artist albums menu");
    }

    /**
     * Ask the specified player for a Label Artist Album Tracks menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param labelId the label whose artist album track menu is desired
     * @param artistId the artist whose album track menu is desired, or -1 for all artists
     * @param albumId the album whose track menu is desired, or -1 for all albums
     *
     * @return the entries in the label artist album tracks menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestLabelArtistAlbumTrackMenuFrom(final SlotReference slotReference, final int sortOrder, final int labelId,
                                                              final int artistId, final int albumId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Label Artist Album Track menu.");
                    Message response = client.menuRequest(Message.KnownType.TRACK_MENU_FOR_LABEL_ARTIST_AND_ALBUM, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(labelId), new NumberField(artistId), new NumberField(albumId));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting genre artist album tracks menu");
    }

    /**
     * Ask the specified player for an Album menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the album menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestAlbumMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Album menu.");
                    Message response = client.menuRequest(Message.KnownType.ALBUM_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting album menu");
    }

    /**
     * Ask the specified player for a Key menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the key menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestKeyMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Key menu.");
                    Message response = client.menuRequest(Message.KnownType.KEY_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting key menu");
    }

    /**
     * Ask the specified player for a key neighbor menu for a given key.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param keyId the key whose available compatible keys are desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the key neighbor menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestKeyNeighborMenuFrom(final SlotReference slotReference, final int sortOrder, final int keyId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting key neighbor menu.");
                    Message response = client.menuRequest(Message.KnownType.NEIGHBOR_MENU_FOR_KEY, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(keyId));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting key neighbor menu");
    }

    /**
     * Ask the specified player for a track menu for an allowed distance from a given key.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param keyId the key whose compatible tracks are desired
     * @param distance how far along the circle of fifths the tracks are allowed to be from the specified key
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the matching tracks
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestTracksByKeyAndDistanceFrom(final SlotReference slotReference, final int sortOrder, final int keyId, final int distance)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting tracks by key and distance menu.");
                    Message response = client.menuRequest(Message.KnownType.TRACK_MENU_FOR_KEY_AND_DISTANCE, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(keyId), new NumberField(distance));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting tracks by key and distance menu");
    }

    /**
     * Ask the specified player for a BPM menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the BPM menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestBpmMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting BPM menu.");
                    Message response = client.menuRequest(Message.KnownType.BPM_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting BPM menu");
    }

    /**
     * Ask the specified player for a tempo range menu for a given BPM.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param bpm the tempo whose nearby ranges are desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the tempo range menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestBpmRangeMenuFrom(final SlotReference slotReference, final int sortOrder, final int bpm)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting tempo neighbor menu.");
                    Message response = client.menuRequest(Message.KnownType.BPM_RANGE_REQ, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(bpm));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting tempo range menu");
    }

    /**
     * Ask the specified player for tracks whose tempo falls within a specific percentage of a given BPM.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param bpm the tempo that tracks must be close to
     * @param range the percentage by which the actual tempo may differ for a track to still be returned
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the tracks whose tempo falls within the specified range
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestTracksByBpmRangeFrom(final SlotReference slotReference, final int sortOrder, final int bpm, final int range)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting tracks by bpm range menu.");
                    Message response = client.menuRequest(Message.KnownType.TRACK_MENU_FOR_BPM_AND_DISTANCE, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(bpm), new NumberField(range));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting tracks within tempo range menu");
    }

    /**
     * Ask the specified player for a Rating menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the rating menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestRatingMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Rating menu.");
                    Message response = client.menuRequest(Message.KnownType.RATING_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting rating menu");
    }

    /**
     * Ask the specified player for a track menu for a given rating.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param rating the desired rating for tracks to be returned
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the matching tracks
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestTracksByRatingFrom(final SlotReference slotReference, final int sortOrder, final int rating)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting tracks by rating menu.");
                    Message response = client.menuRequest(Message.KnownType.TRACK_MENU_FOR_RATING_REQ, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(rating));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting tracks by rating menu");
    }

    /**
     * Ask the specified player for a Color menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the color menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestColorMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Color menu.");
                    Message response = client.menuRequest(Message.KnownType.COLOR_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting color menu");
    }

    /**
     * Ask the specified player for a track menu for a given color.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param color the desired color for tracks to be returned
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the matching tracks
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestTracksByColorFrom(final SlotReference slotReference, final int sortOrder, final int color)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting tracks by color menu.");
                    Message response = client.menuRequest(Message.KnownType.TRACK_MENU_FOR_COLOR_REQ, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(color));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting tracks by color menu");
    }

    /**
     * Ask the specified player for a Time menu, grouping tracks by their length in minutes.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the time menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestTimeMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Time menu.");
                    Message response = client.menuRequest(Message.KnownType.TIME_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting time menu");
    }

    /**
     * Ask the specified player for a track menu for a given time (track length in minutes).
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param time the length in minutes of tracks to be returned
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the matching tracks
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestTracksByTimeFrom(final SlotReference slotReference, final int sortOrder, final int time)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting tracks by time menu.");
                    Message response = client.menuRequest(Message.KnownType.TRACK_MENU_FOR_TIME_REQ, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(time));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting tracks by time menu");
    }

    /**
     * Ask the specified player for a Bit Rate menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the bit rate menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestBitRateMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Bit Rate menu.");
                    Message response = client.menuRequest(Message.KnownType.BIT_RATE_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting genre menu");
    }

    /**
     * Ask the specified player for a track menu for a given track bit rate (in Kbps).
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param bitRate the bit rate, in kilobits per second, of tracks to be returned
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the matching tracks
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestTracksByBitRateFrom(final SlotReference slotReference, final int sortOrder, final int bitRate)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting tracks by bit rate menu.");
                    Message response = client.menuRequest(Message.KnownType.TRACK_MENU_FOR_BIT_RATE_REQ, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(bitRate));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting tracks by time menu");
    }

    /**
     * Ask the specified player for a Year menu, grouping years by decade.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the year menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestYearMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Year menu.");
                    Message response = client.menuRequest(Message.KnownType.YEAR_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting year menu");
    }

    /**
     * Ask the specified player for a year menu for a given decade.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param decade narrows the years of tracks to be returned
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the matching tracks
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestYearsByDecadeFrom(final SlotReference slotReference, final int sortOrder, final int decade)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting years by decade menu.");
                    Message response = client.menuRequest(Message.KnownType.YEAR_MENU_FOR_DECADE_REQ, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(decade));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting years by decade menu");
    }

    /**
     * Ask the specified player for a track menu for a decade and year.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param decade the decade for which tracks are desired
     * @param year the specific year for which tracks are desired, or -1 for all years within the specified decade
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the matching tracks
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestTracksByDecadeAndYear(final SlotReference slotReference, final int sortOrder, final int decade, final int year)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting tracks by decade and year menu.");
                    Message response = client.menuRequest(Message.KnownType.TRACK_MENU_FOR_DECADE_YEAR_REQ, Message.MenuIdentifier.MAIN_MENU,
                            slotReference.slot, new NumberField(sortOrder), new NumberField(decade), new NumberField(year));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting tracks by decade and year menu");
    }

    /**
     * Ask the specified player for a Filename menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     *
     * @return the entries in the filename menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestFilenameMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Filename menu.");
                    Message response = client.menuRequest(Message.KnownType.FILENAME_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            new NumberField(sortOrder));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting filename menu");
    }

    /**
     * Ask the specified player for a Folder menu for exploring its raw filesystem.
     * This is a request for unanalyzed items, so we do a typed menu request.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param folderId identifies the folder whose contents should be listed, use -1 to get the root folder
     *
     * @return the entries in the folder menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestFolderMenuFrom(final SlotReference slotReference, final int sortOrder, final int folderId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = client -> {
            if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                try {
                    logger.debug("Requesting Folder menu.");
                    Message response = client.menuRequestTyped(Message.KnownType.FOLDER_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                            CdjStatus.TrackType.UNANALYZED, new NumberField(sortOrder), new NumberField(folderId), new NumberField(0xffffff));
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.UNANALYZED, response);
                } finally {
                    client.unlockForMenuOperations();
                }
            } else {
                throw new TimeoutException("Unable to lock player for menu operations.");
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting folder menu");
    }

    /**
     * Ask the connected dbserver about database records whose names contain {@code text}. If {@code count} is not
     * {@code null}, no more than that many results will be returned, and the value will be set to the total number
     * of results that were available. Otherwise, all results will be returned.
     *
     * @param slot the slot in which the database can be found
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details, although it does not seem to have an effect on searches.
     * @param text the search text used to filter the results
     * @param count if present, sets an upper limit on the number of results to return, and will get set
     *              to the actual number that were available
     *
     * @return the items that match the specified search string; they may be a variety of different types
     *
     * @throws IOException if there is a problem communicating
     * @throws InterruptedException if the thread is interrupted while trying to lock the client for menu operations
     * @throws TimeoutException if we are unable to lock the client for menu operations
     */
    @API(status = API.Status.STABLE)
    private List<Message> getSearchItems(CdjStatus.TrackSourceSlot slot, int sortOrder, String text,
                                         AtomicInteger count, Client client)
            throws IOException, InterruptedException, TimeoutException {
        if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
            try {
                final StringField textField = new StringField(text);
                Message response = client.menuRequest(Message.KnownType.SEARCH_MENU, Message.MenuIdentifier.MAIN_MENU, slot,
                        new NumberField(sortOrder), new NumberField(textField.getSize()), textField, NumberField.WORD_0);
                final int actualCount = (int)response.getMenuResultsCount();
                if (actualCount == 0) {
                    if (count != null) {
                        count.set(0);
                    }
                    return Collections.emptyList();
                }

                // Gather the requested number of search menu items
                if (count == null) {
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slot, CdjStatus.TrackType.REKORDBOX, response);
                } else {
                    final int desiredCount = Math.min(count.get(), actualCount);
                    count.set(actualCount);
                    if (desiredCount < 1) {
                        return Collections.emptyList();
                    }
                    return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slot, CdjStatus.TrackType.REKORDBOX,
                            0, desiredCount);
                }
            } finally {
                client.unlockForMenuOperations();
            }
        } else {
            throw new TimeoutException("Unable to lock player for menu operations.");
        }
    }

    /**
     * Ask the specified player for database records whose names contain {@code text}. If {@code count} is not
     * {@code null}, no more than that many results will be returned, and the value will be set to the total number
     * of results that were available. Otherwise, all results will be returned.
     *
     * @param player the player number whose database is to be searched
     * @param slot the slot in which the database can be found
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details, although it does not seem to have an effect on searches.
     * @param text the search text used to filter the results
     * @param count if present, sets an upper limit on the number of results to return, and will get set
     *              to the actual number that were available
     *
     * @return the items that the specified search string; they may be a variety of different types
     *
     * @throws Exception if there is a problem performing the search
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestSearchResultsFrom(final int player, final CdjStatus.TrackSourceSlot slot,
                                                  final int sortOrder, final String text,
                                                  final AtomicInteger count)
            throws Exception {
        ConnectionManager.ClientTask<List<Message>> task = client -> getSearchItems(slot, sortOrder, text.toUpperCase(), count, client);

        return ConnectionManager.getInstance().invokeWithClientSession(player, task, "performing search");
    }


    /**
     * Ask the connected dbserver about database records whose names contain {@code text}. If {@code count} is not
     * {@code null}, no more than that many results will be returned, and the value will be set to the total number
     * of results that were available. Otherwise, all results will be returned.
     *
     * @param slot the slot in which the database can be found
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details, although it does not seem to have an effect on searches.
     * @param text the search text used to filter the results
     * @param offset the first result desired (the first available result has offset 0)
     * @param count the number of results to return (if more than the number available, fewer will simply be returned)
     *
     * @return the items that match the specified search string; they may be a variety of different types
     *
     * @throws IOException if there is a problem communicating
     * @throws InterruptedException if the thread is interrupted while trying to lock the client for menu operations
     * @throws TimeoutException if we are unable to lock the client for menu operations
     */
    private List<Message> getMoreSearchItems(final CdjStatus.TrackSourceSlot slot, final int sortOrder, final String text,
                                             final int offset, final int count, final Client client)
            throws IOException, InterruptedException, TimeoutException {
        if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
            try {
                final StringField textField = new StringField(text);
                Message response = client.menuRequest(Message.KnownType.SEARCH_MENU, Message.MenuIdentifier.MAIN_MENU, slot,
                        new NumberField(sortOrder), new NumberField(textField.getSize()), textField, NumberField.WORD_0);
                final int actualCount = (int)response.getMenuResultsCount();
                if (offset + count > actualCount) {
                    throw new IllegalArgumentException("Cannot request items past the end of the menu.");
                }

                // Gather the requested search menu items
                return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slot, CdjStatus.TrackType.REKORDBOX,
                        offset, count);
            } finally {
                client.unlockForMenuOperations();
            }
        } else {
            throw new TimeoutException("Unable to lock player for menu operations.");
        }
    }

    /**
     * Ask the specified player for more database records whose names contain {@code text}. This can be used after
     * calling {@link #requestSearchResultsFrom(int, CdjStatus.TrackSourceSlot, int, String, AtomicInteger)} to obtain
     * a partial result and the total count available, to gradually expand the search under direction from the user.
     *
     * @param player the player number whose database is to be searched
     * @param slot the slot in which the database can be found
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details, although it does not seem to have an effect on searches.
     * @param text the search text used to filter the results
     * @param offset the first result desired (the first available result has offset 0)
     * @param count the number of results to return (if more than the number available, fewer will simply be returned)
     *
     * @return the items that the specified search string; they may be a variety of different types
     *
     * @throws Exception if there is a problem performing the search
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestMoreSearchResultsFrom(final int player, final CdjStatus.TrackSourceSlot slot,
                                                      final int sortOrder, final String text,
                                                      final int offset, final int count)
            throws Exception {
        ConnectionManager.ClientTask<List<Message>> task = client -> getMoreSearchItems(slot, sortOrder, text.toUpperCase(), offset, count, client);

        return ConnectionManager.getInstance().invokeWithClientSession(player, task, "performing search");
    }


    /**
     * Holds the singleton instance of this class.
     */
    private static final MenuLoader ourInstance = new MenuLoader();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    @API(status = API.Status.STABLE)
    public static MenuLoader getInstance() {
        return ourInstance;
    }

    /**
     * Prevent direct instantiation.
     */
    private MenuLoader() {
        // Nothing to do.
    }
}
