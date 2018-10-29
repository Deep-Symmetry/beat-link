package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.dbserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p>Watches for new tracks to be loaded on players, and queries the
 * appropriate player for the metadata information when that happens.</p>
 *
 * <p>Maintains a hot cache of metadata about any track currently loaded in a player, either on the main playback
 * deck, or as a hot cue, since those tracks could start playing instantly.</p>
 *
 * <p>Can also create cache files containing metadata about either all tracks in a media library, or tracks from a
 * specific play list, and attach those cache files to be used instead of actually querying the player about tracks
 * loaded from that library. This can be used in busy performance situations where all four usable player numbers
 * are in use by actual players, to avoid conflicting queries yet still have useful metadata available. In such
 * situations, you may want to go into passive mode, using {@link #setPassive(boolean)}, to prevent metadata queries
 * about tracks that are not available from the attached metadata cache files.</p>
 *
 * @author James Elliott
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class MetadataFinder extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(MetadataFinder.class);

    /**
     * Given a status update from a CDJ, find the metadata for the track that it has loaded, if any. If there is
     * an appropriate metadata cache, will use that, otherwise makes a query to the players dbserver.
     *
     * @param status the CDJ status update that will be used to determine the loaded track and ask the appropriate
     *               player for metadata about it
     *
     * @return the metadata that was obtained, if any
     */
    @SuppressWarnings("WeakerAccess")
    public TrackMetadata requestMetadataFrom(final CdjStatus status) {
        if (status.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.NO_TRACK || status.getRekordboxId() == 0) {
            return null;
        }
        final DataReference track = new DataReference(status.getTrackSourcePlayer(), status.getTrackSourceSlot(),
                status.getRekordboxId());
        return requestMetadataFrom(track, status.getTrackType());
    }


    /**
     * Ask the specified player for metadata about the track in the specified slot with the specified rekordbox ID,
     * unless we have a metadata cache available for the specified media slot, in which case that will be used instead.
     *
     * @param track uniquely identifies the track whose metadata is desired
     * @param trackType identifies the type of track being requested, which affects the type of metadata request
     *                  message that must be used
     *
     * @return the metadata, if any
     */
    @SuppressWarnings("WeakerAccess")
    public TrackMetadata requestMetadataFrom(final DataReference track, final CdjStatus.TrackType trackType) {
        return requestMetadataInternal(track, trackType, false);
    }

    /**
     * Ask the specified player for metadata about the track in the specified slot with the specified rekordbox ID,
     * using cached media instead if it is available, and possibly giving up if we are in passive mode.
     *
     * @param track uniquely identifies the track whose metadata is desired
     * @param trackType identifies the type of track being requested, which affects the type of metadata request
     *                  message that must be used
     * @param failIfPassive will prevent the request from taking place if we are in passive mode, so that automatic
     *                      metadata updates will use available caches only
     *
     * @return the metadata found, if any
     */
    private TrackMetadata requestMetadataInternal(final DataReference track, final CdjStatus.TrackType trackType,
                                                  final boolean failIfPassive) {
        // First check if we are using cached data for this request
        MetadataCache cache = getMetadataCache(SlotReference.getSlotReference(track));
        if (cache != null && trackType == CdjStatus.TrackType.REKORDBOX) {
            return cache.getTrackMetadata(null, track);
        }

        if (passive.get() && failIfPassive) {
            return null;
        }

        ConnectionManager.ClientTask<TrackMetadata> task = new ConnectionManager.ClientTask<TrackMetadata>() {
            @Override
            public TrackMetadata useClient(Client client) throws Exception {
                return queryMetadata(track, trackType, client);
            }
        };

        try {
            return ConnectionManager.getInstance().invokeWithClientSession(track.player, task, "requesting metadata");
        } catch (Exception e) {
            logger.error("Problem requesting metadata, returning null", e);
        }
        return null;
    }

    /**
     * How many seconds are we willing to wait to lock the database client for menu operations.
     */
    public static final int MENU_TIMEOUT = 20;

    /**
     * Request metadata for a specific track ID, given a connection to a player that has already been set up.
     * Separated into its own method so it could be used multiple times with the same connection when gathering
     * all track metadata.
     *
     * @param track uniquely identifies the track whose metadata is desired
     * @param trackType identifies the type of track being requested, which affects the type of metadata request
     *                  message that must be used
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved metadata, or {@code null} if there is no such track
     *
     * @throws IOException if there is a communication problem
     * @throws InterruptedException if the thread is interrupted while trying to lock the client for menu operations
     * @throws TimeoutException if we are unable to lock the client for menu operations
     */
    TrackMetadata queryMetadata(final DataReference track, final CdjStatus.TrackType trackType, final Client client)
            throws IOException, InterruptedException, TimeoutException {

        // Send the metadata menu request
        if (client.tryLockingForMenuOperations(20, TimeUnit.SECONDS)) {
            try {
                final Message.KnownType requestType = (trackType == CdjStatus.TrackType.REKORDBOX) ?
                        Message.KnownType.REKORDBOX_METADATA_REQ : Message.KnownType.UNANALYZED_METADATA_REQ;
                final Message response = client.menuRequestTyped(requestType, Message.MenuIdentifier.MAIN_MENU,
                        track.slot, trackType, new NumberField(track.rekordboxId));
                final long count = response.getMenuResultsCount();
                if (count == Message.NO_MENU_RESULTS_AVAILABLE) {
                    return null;
                }

                // Gather the cue list and all the metadata menu items
                final List<Message> items = client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, track.slot, trackType, response);
                final CueList cueList = getCueList(track.rekordboxId, track.slot, client);
                return new TrackMetadata(track, trackType, items, cueList);
            } finally {
                client.unlockForMenuOperations();
            }
        } else {
            throw new TimeoutException("Unable to lock the player for menu operations");
        }
    }

    /**
     * Requests the cue list for a specific track ID, given a connection to a player that has already been set up.
     *
     * @param rekordboxId the track of interest
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved cue list, or {@code null} if none was available
     * @throws IOException if there is a communication problem
     */
    CueList getCueList(int rekordboxId, CdjStatus.TrackSourceSlot slot, Client client)
            throws IOException {
        Message response = client.simpleRequest(Message.KnownType.CUE_LIST_REQ, null,
                client.buildRMST(Message.MenuIdentifier.DATA, slot), new NumberField(rekordboxId));
        if (response.knownType == Message.KnownType.CUE_LIST) {
            return new CueList(response);
        }
        logger.error("Unexpected response type when requesting cue list: {}", response);
        return null;
    }

    /**
     * Request the list of all tracks in the specified slot, given a connection to a player that has already been
     * set up.
     *
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved track list entry items
     *
     * @throws IOException if there is a communication problem
     * @throws InterruptedException if the thread is interrupted while trying to lock the client for menu operations
     * @throws TimeoutException if we are unable to lock the client for menu operations
     */
    List<Message> getFullTrackList(final CdjStatus.TrackSourceSlot slot, final Client client, final int sortOrder)
            throws IOException, InterruptedException, TimeoutException {
        // Send the metadata menu request
        if (client.tryLockingForMenuOperations(MENU_TIMEOUT, TimeUnit.SECONDS)) {
            try {
                Message response = client.menuRequest(Message.KnownType.TRACK_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slot,
                        new NumberField(sortOrder));
                final long count = response.getMenuResultsCount();
                if (count == Message.NO_MENU_RESULTS_AVAILABLE || count == 0) {
                    return Collections.emptyList();
                }

                // Gather all the metadata menu items
                return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slot, CdjStatus.TrackType.REKORDBOX, response);
            }
            finally {
                client.unlockForMenuOperations();
            }
        } else {
            throw new TimeoutException("Unable to lock the player for menu operations");
        }
    }

    /**
     * Ask the connected dbserver for the playlist entries of the specified playlist (if {@code folder} is {@code false},
     * or the list of playlists and folders inside the specified playlist folder (if {@code folder} is {@code true}.
     * Pulled into a separate method so it can be used from multiple different client transactions.
     *
     * @param slot the slot in which the playlist can be found
     * @param sortOrder the order in which responses should be sorted, 0 for default, see Section 6.11.1 of the
     *                  <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis
     *                  document</a> for details
     * @param playlistOrFolderId the database ID of the desired playlist or folder
     * @param folder indicates whether we are asking for the contents of a folder or playlist
     * @param client the dbserver client that is communicating with the appropriate player

     * @return the items that are found in the specified playlist or folder; they will be tracks if we are asking
     *         for a playlist, or playlists and folders if we are asking for a folder

     * @throws IOException if there is a problem communicating
     * @throws InterruptedException if the thread is interrupted while trying to lock the client for menu operations
     * @throws TimeoutException if we are unable to lock the client for menu operations
     */
    private List<Message> getPlaylistItems(CdjStatus.TrackSourceSlot slot, int sortOrder, int playlistOrFolderId,
                                           boolean folder, Client client)
            throws IOException, InterruptedException, TimeoutException {
        if (client.tryLockingForMenuOperations(MENU_TIMEOUT, TimeUnit.SECONDS)) {
            try {
                Message response = client.menuRequest(Message.KnownType.PLAYLIST_REQ, Message.MenuIdentifier.MAIN_MENU, slot,
                        new NumberField(sortOrder), new NumberField(playlistOrFolderId), new NumberField(folder? 1 : 0));
                final long count = response.getMenuResultsCount();
                if (count == Message.NO_MENU_RESULTS_AVAILABLE || count == 0) {
                    return Collections.emptyList();
                }

                // Gather all the metadata menu items
                return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slot, CdjStatus.TrackType.REKORDBOX, response);
            } finally {
                client.unlockForMenuOperations();
            }
        } else {
            throw new TimeoutException("Unable to lock player for menu operations.");
        }
    }

    /**
     * Ask the specified player for the playlist entries of the specified playlist (if {@code folder} is {@code false},
     * or the list of playlists and folders inside the specified playlist folder (if {@code folder} is {@code true}.
     *
     * @param player the player number whose playlist entries are of interest
     * @param slot the slot in which the playlist can be found
     * @param sortOrder the order in which responses should be sorted, 0 for default, see Section 6.11.1 of the
     *                  <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis
     *                  document</a> for details
     * @param playlistOrFolderId the database ID of the desired playlist or folder
     * @param folder indicates whether we are asking for the contents of a folder or playlist
     *
     * @return the items that are found in the specified playlist or folder; they will be tracks if we are asking
     *         for a playlist, or playlists and folders if we are asking for a folder
     *
     * @throws Exception if there is a problem obtaining the playlist information
     */
    public List<Message> requestPlaylistItemsFrom(final int player, final CdjStatus.TrackSourceSlot slot,
                                                  final int sortOrder, final int playlistOrFolderId,
                                                  final boolean folder)
            throws Exception {
        ConnectionManager.ClientTask<List<Message>> task = new ConnectionManager.ClientTask<List<Message>>() {
            @Override
            public List<Message> useClient(Client client) throws Exception {
               return getPlaylistItems(slot, sortOrder, playlistOrFolderId, folder, client);
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(player, task, "requesting playlist information");
    }

    /**
     * Creates a metadata cache archive file of all tracks in the specified slot on the specified player. Any
     * previous contents of the specified file will be replaced.
     *
     * @param slot the slot in which the media to be cached can be found
     * @param playlistId the id of playlist to be cached, or 0 of all tracks should be cached
     * @param cache the file into which the metadata cache should be written
     *
     * @throws Exception if there is a problem communicating with the player or writing the cache file.
     */
    public void createMetadataCache(SlotReference slot, int playlistId, File cache) throws Exception {
        createMetadataCache(slot, playlistId, cache, null);
    }

    /**
     * How long should we pause between requesting metadata entries while building a cache to give the player
     * a chance to perform its other tasks.
     */
    private final AtomicLong cachePauseInterval = new AtomicLong(50);

    /**
     * Set how long to pause between requesting metadata entries while building a cache to give the player
     * a chance to perform its other tasks.
     *
     * @param milliseconds the delay to add between each track that gets added to the metadata cache
     */
    public void setCachePauseInterval(long milliseconds) {
        cachePauseInterval.set(milliseconds);
    }

    /**
     * Check how long we pause between requesting metadata entries while building a cache to give the player
     * a chance to perform its other tasks.
     *
     * @return the delay to add between each track that gets added to the metadata cache
     */
    public long getCachePauseInterval() {
        return cachePauseInterval.get();
    }

    /**
     * Creates a metadata cache archive file of all tracks in the specified slot on the specified player. Any
     * previous contents of the specified file will be replaced. If a non-{@code null} {@code listener} is
     * supplied, its {@link MetadataCacheCreationListener#cacheCreationContinuing(TrackMetadata, int, int)} method
     * will be called after each track is added to the cache, allowing it to display progress updates to the user,
     * and to continue or cancel the process by returning {@code true} or {@code false}.
     *
     * Because this takes a huge amount of time relative to CDJ status updates, it can only be performed while
     * the MetadataFinder is in passive mode.
     *
     * @param slot the slot in which the media to be cached can be found
     * @param playlistId the id of playlist to be cached, or 0 of all tracks should be cached
     * @param cache the file into which the metadata cache should be written
     * @param listener will be informed after each track is added to the cache file being created and offered
     *                 the opportunity to cancel the process
     *
     * @throws Exception if there is a problem communicating with the player or writing the cache file
     */
    @SuppressWarnings({"SameParameterValue", "WeakerAccess"})
    public void createMetadataCache(final SlotReference slot, final int playlistId,
                                    final File cache, final MetadataCacheCreationListener listener)
            throws Exception {
        ConnectionManager.ClientTask<Object> task = new ConnectionManager.ClientTask<Object>() {
            @Override
            public Object useClient(Client client) throws Exception {
                final List<Message> trackList;
                if (playlistId == 0) {
                    trackList = getFullTrackList(slot.slot, client, 0);
                } else {
                    trackList = getPlaylistItems(slot.slot, 0, playlistId, false, client);
                }
                MetadataCache.copyTracksToCache(trackList, playlistId, client, slot, cache, listener);
                return null;
            }
        };

        if (cache.exists() && !cache.delete()) {
            logger.warn("Unable to delete cache file, {}", cache);
        }
        ConnectionManager.getInstance().invokeWithClientSession(slot.player, task, "building metadata cache");
    }

   /**
     * Keeps track of the current metadata cached for each player. We cache metadata for any track which is currently
     * on-deck in the player, as well as any that were loaded into a player's hot-cue slot.
     */
    private final Map<DeckReference, TrackMetadata> hotCache = new ConcurrentHashMap<DeckReference, TrackMetadata>();

    /**
     * A queue used to hold CDJ status updates we receive from the {@link VirtualCdj} so we can process them on a
     * lower priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private final LinkedBlockingDeque<CdjStatus> pendingUpdates = new LinkedBlockingDeque<CdjStatus>(100);

    /**
     * Our update listener just puts appropriate device updates on our queue, so we can process them on a lower
     * priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private final DeviceUpdateListener updateListener = new DeviceUpdateListener() {
        @Override
        public void received(DeviceUpdate update) {
            logger.debug("Received device update {}", update);
            if (update instanceof CdjStatus) {
                if (!pendingUpdates.offerLast((CdjStatus)update)) {
                    logger.warn("Discarding CDJ update because our queue is backed up.");
                }
            }
        }
    };


    /**
     * Our announcement listener watches for devices to disappear from the network so we can discard all information
     * about them.
     */
    private final DeviceAnnouncementListener announcementListener = new DeviceAnnouncementListener() {
        @Override
        public void deviceFound(final DeviceAnnouncement announcement) {
            if ((announcement.getNumber() > 0x10) && (announcement.getNumber() < 0x20)) {  // Looks like rekordbox.
                recordMount(SlotReference.getSlotReference(announcement.getNumber(),
                        CdjStatus.TrackSourceSlot.COLLECTION));  // Report the rekordbox collection as mounted media.
            }
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            clearMetadata(announcement);
            if (announcement.getNumber() < 0x10) {  // Looks like a player, clear the whole panoply of caches.
                removeMount(SlotReference.getSlotReference(announcement.getNumber(), CdjStatus.TrackSourceSlot.CD_SLOT));
                removeMount(SlotReference.getSlotReference(announcement.getNumber(), CdjStatus.TrackSourceSlot.USB_SLOT));
                removeMount(SlotReference.getSlotReference(announcement.getNumber(), CdjStatus.TrackSourceSlot.SD_SLOT));
                detachMetadataCache(SlotReference.getSlotReference(announcement.getNumber(), CdjStatus.TrackSourceSlot.USB_SLOT));
                detachMetadataCache(SlotReference.getSlotReference(announcement.getNumber(), CdjStatus.TrackSourceSlot.SD_SLOT));
            } else if (announcement.getNumber() < 0x20) {  // Looks like rekordbox, clear "mounted" database.
                removeMount(SlotReference.getSlotReference(announcement.getNumber(), CdjStatus.TrackSourceSlot.COLLECTION));
            }
        }
    };

    /**
     * Keep track of whether we are running
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Check whether we are currently running. Unless we are in passive mode, we will also automatically request
     * metadata from the appropriate player when a new track is loaded that is not found in the hot cache or an
     * attached metadata cache file.
     *
     * @return true if track metadata is being kept track of for all active players
     *
     * @see #isPassive()
     */
    @SuppressWarnings("WeakerAccess")
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Indicates whether we should use metadata only from caches, never actively requesting it from a player.
     */
    private final AtomicBoolean passive = new AtomicBoolean(false);

    /**
     * Check whether we are configured to use metadata only from caches, never actively requesting it from a player.
     * Note that this will implicitly mean all of the metadata-related finders ({@link ArtFinder}, {@link BeatGridFinder},
     * and {@link WaveformFinder}) are in passive mode as well, because their activity is triggered by the availability
     * of new track metadata.
     *
     * @return {@code true} if only cached metadata will be used, or {@code false} if metadata will be requested from
     *         a player if a track is loaded from a media slot to which no cache has been assigned
     */
    public boolean isPassive() {
        return passive.get();
    }

    /**
     * Set whether we are configured to use metadata only from caches, never actively requesting it from a player.
     * Note that this will implicitly put all of the metadata-related finders ({@link ArtFinder}, {@link BeatGridFinder},
     * and {@link WaveformFinder}) into a passive mode as well, because their activity is triggered by the availability
     * of new track metadata.
     *
     * @param passive {@code true} if only cached metadata will be used, or {@code false} if metadata will be requested
     *                from a player if a track is loaded from a media slot to which no cache has been assigned
     */
    public void setPassive(boolean passive) {
        this.passive.set(passive);
    }

    /**
     * We process our player status updates on a separate thread so as not to slow down the high-priority update
     * delivery thread; we perform potentially slow I/O.
     */
    private Thread queueHandler;

    /**
     * We have received an update that invalidates any previous metadata for that player, so clear it out, and alert
     * any listeners if this represents a change. This does not affect the hot cues; they will stick around until the
     * player loads a new track that overwrites one or more of them.
     *
     * @param update the update which means we can have no metadata for the associated player
     */
    private void clearDeck(CdjStatus update) {
        if (hotCache.remove(DeckReference.getDeckReference(update.getDeviceNumber(), 0)) != null) {
            deliverTrackMetadataUpdate(update.getDeviceNumber(), null);
        }
    }

    /**
     * We have received notification that a device is no longer on the network, so clear out its metadata.
     *
     * @param announcement the packet which reported the device’s disappearance
     */
    private void clearMetadata(DeviceAnnouncement announcement) {
        final int player = announcement.getNumber();
        // Iterate over a copy to avoid concurrent modification issues
        for (DeckReference deck : new HashSet<DeckReference>(hotCache.keySet())) {
            if (deck.player == player) {
                hotCache.remove(deck);
            }
        }
    }

    /**
     * We have obtained metadata for a device, so store it and alert any listeners.
     *
     * @param update the update which caused us to retrieve this metadata
     * @param data the metadata which we received
     */
    private void updateMetadata(CdjStatus update, TrackMetadata data) {
        hotCache.put(DeckReference.getDeckReference(update.getDeviceNumber(), 0), data);  // Main deck
        if (data.getCueList() != null) {  // Update the cache with any hot cues in this track as well
            for (CueList.Entry entry : data.getCueList().entries) {
                if (entry.hotCueNumber != 0) {
                    hotCache.put(DeckReference.getDeckReference(update.getDeviceNumber(), entry.hotCueNumber), data);
                }
            }
        }
        deliverTrackMetadataUpdate(update.getDeviceNumber(), data);
    }

    /**
     * Get the metadata of all tracks currently loaded in any player, either on the play deck, or in a hot cue.
     *
     * @return the track information reported by all current players, including any tracks loaded in their hot cue slots
     *
     * @throws IllegalStateException if the MetadataFinder is not running
     */
    public Map<DeckReference, TrackMetadata> getLoadedTracks() {
        ensureRunning();
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableMap(new HashMap<DeckReference, TrackMetadata>(hotCache));
    }

    /**
     * Look up the track metadata we have for the track loaded in the main deck of a given player number.
     *
     * @param player the device number whose track metadata for the playing track is desired
     *
     * @return information about the track loaded on that player, if available
     *
     * @throws IllegalStateException if the MetadataFinder is not running
     */
    @SuppressWarnings("WeakerAccess")
    public TrackMetadata getLatestMetadataFor(int player) {
        ensureRunning();
        return hotCache.get(DeckReference.getDeckReference(player, 0));
    }

    /**
     * Look up the track metadata we have for a given player, identified by a status update received from that player.
     *
     * @param update a status update from the player for which track metadata is desired
     *
     * @return information about the track loaded on that player, if available
     *
     * @throws IllegalStateException if the MetadataFinder is not running
     */
    public TrackMetadata getLatestMetadataFor(DeviceUpdate update) {
        return getLatestMetadataFor(update.getDeviceNumber());
    }

    /**
     * Keep track of the devices we are currently trying to get metadata from in response to status updates.
     */
    private final Set<Integer> activeRequests = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    /**
     * Keeps track of any metadata caches that have been attached for the slots of players on the network,
     * keyed by slot reference.
     */
    private final Map<SlotReference, MetadataCache> metadataCacheFiles = new ConcurrentHashMap<SlotReference, MetadataCache>();

    /**
     * Attach a metadata cache file to a particular player media slot, so the cache will be used instead of querying
     * the player for metadata. This supports operation with metadata during shows where DJs are using all four player
     * numbers and heavily cross-linking between them.
     *
     * If the media is ejected from that player slot, the cache will be detached.
     *
     * @param slot the media slot to which a meta data cache is to be attached
     * @param file the metadata cache to be attached
     *
     * @throws IOException if there is a problem reading the cache file
     * @throws IllegalArgumentException if an invalid player number or slot is supplied
     * @throws IllegalStateException if the metadata finder is not running
     */
    public void attachMetadataCache(SlotReference slot, File file)
            throws IOException {
        ensureRunning();
        if (slot.player < 1 || slot.player > 4 || DeviceFinder.getInstance().getLatestAnnouncementFrom(slot.player) == null) {
            throw new IllegalArgumentException("unable to attach metadata cache for player " + slot.player);
        }
        if ((slot.slot != CdjStatus.TrackSourceSlot.USB_SLOT) && (slot.slot != CdjStatus.TrackSourceSlot.SD_SLOT)) {
            throw new IllegalArgumentException("unable to attach metadata cache for slot " + slot.slot);
        }

        MetadataCache cache = new MetadataCache(file);
        final MediaDetails slotDetails = getMediaDetailsFor(slot);
        if (cache.sourceMedia !=  null && slotDetails != null) {
            if (!slotDetails.hashKey().equals(cache.sourceMedia.hashKey())) {
                throw new IllegalArgumentException("Cache was created for different media (" + cache.sourceMedia.hashKey() +
                        ") than is in the slot (" + slotDetails.hashKey() + ").");
            }
            if (slotDetails.hasChanged(cache.sourceMedia)) {
                logger.warn("Media has changed (" + slotDetails + ") since cache was created (" + cache.sourceMedia +
                        "). Attaching anyway as instructed.");
            }
        }
        attachMetadataCacheInternal(slot, cache);
    }

    /**
     * Finishes the process of attaching a metadata cache file once it has been opened and validated.
     *
     * @param slot the slot to which the cache should be attached
     * @param cache the opened, validated metadata cache file
     */
    private void attachMetadataCacheInternal(SlotReference slot, MetadataCache cache) {
        MetadataCache oldCache = metadataCacheFiles.put(slot, cache);
        if (oldCache != null) {
            try {
                oldCache.close();
            } catch (IOException e) {
                logger.error("Problem closing previous metadata cache", e);
            }
        }

        deliverCacheUpdate(slot, cache);
    }

    /**
     * Removes any metadata cache file that might have been assigned to a particular player media slot, so metadata
     * will be looked up from the player itself.
     *
     * @param slot the media slot to which a meta data cache is to be attached
     */
    public void detachMetadataCache(SlotReference slot) {
        MetadataCache oldCache = metadataCacheFiles.remove(slot);
        if (oldCache != null) {
            try {
                oldCache.close();
            } catch (IOException e) {
                logger.error("Problem closing metadata cache", e);
            }
            deliverCacheUpdate(slot, null);
        }
    }

    /**
     * Keep track of the cache files that we are supposed to automatically attach when we find media in a slot that
     * seems to match them.
     */
    private final Set<File> autoAttachCacheFiles = Collections.newSetFromMap(new ConcurrentHashMap<File, Boolean>());

    /**
     * Add a metadata cache file to the set being automatically attached when matching media is inserted. Will try
     * to auto-attach the new file to any already-mounted media. Adding a file that is already present in the set
     * will have no effect, and if the file being added was created from the same media as any existing file in the
     * set, the new file will replace the existing one, since only one file can match the media when it mounts.
     * (Tracking of source media was only added in version 0.4.1.)
     *
     * @param metadataCacheFile the file to be auto-attached when matching media is seen on the network
     *
     * @throws IOException if the specified file cannot be read or is not a valid metadata cache
     */
    public void addAutoAttachCacheFile(File metadataCacheFile) throws IOException {
        MetadataCache opened = new MetadataCache(metadataCacheFile);  // Make sure it is readable and valid.
        try {
            if (opened.sourceMedia != null) {  // Remove any auto-attach files created from the same media as the one being added.
                Iterator<File> iterator = autoAttachCacheFiles.iterator();
                while (iterator.hasNext()) {
                    File file = iterator.next();
                    if (!file.equals(metadataCacheFile)) {
                        MetadataCache existing = new MetadataCache(file);
                        try {
                            if (existing.sourceMedia != null && existing.sourceMedia.hashKey().equals(opened.sourceMedia.hashKey())) {
                                iterator.remove();
                            }
                        } finally {
                            existing.close();
                        }
                    }
                }
            }
        } finally {
            opened.close();
        }
        if (autoAttachCacheFiles.add(metadataCacheFile)) {
            for (SlotReference slot : getMountedMediaSlots()) {
                tryAutoAttaching(slot);
            }
        }
    }

    /**
     * Remove a metadata cache file from the set being automatically attached when matching media is inserted.
     * This will not detach it from a slot if it has already been attached; for that you need to use
     * {@link #detachMetadataCache(SlotReference)}.
     *
     * @param metadataCacheFile the file that should no longer be auto-attached when matching media is seen
     */
    public void removeAutoAttacheCacheFile(File metadataCacheFile) {
        autoAttachCacheFiles.remove(metadataCacheFile);
    }

    /**
     * Get the metadata cache files that are currently configured to be automatically attached when matching media is
     * mounted in a player on the network.
     *
     * @return the current auto-attache cache files, sorted by name
     */
    public List<File> getAutoAttachCacheFiles() {
        ArrayList<File> currentFiles = new ArrayList<File>(autoAttachCacheFiles);
        Collections.sort(currentFiles, new Comparator<File>() {
            @Override
            public int compare(File o1, File o2) {
                return o1.getName().compareTo(o2.getName());
            }
        });
        return Collections.unmodifiableList(currentFiles);
    }

    /**
     * The number of tracks that will be examined when considering a metadata cache file for auto-attachment.
     */
    private final AtomicInteger autoAttachProbeCount = new AtomicInteger(5);

    /**
     * Set the number of tracks examined when considering auto-attaching a metadata cache file to a newly-mounted
     * media database. The more examined, the more confident we can be in the cache matching the media, but the longer
     * it will take and the more metadata queries will be required. The smallest legal value is 1. This value is only
     * used for metadata caches created by versions older than 0.4.1, because they lacked the ability to match by the
     * details of the media database itself.
     *
     * @param numTracks the number of tracks that will be compared between the cache files and the media in the player
     */
    public void setAutoAttachProbeCount(int numTracks) {
        if (numTracks < 1) {
            throw new IllegalArgumentException("numTracks must be positive");
        }
        autoAttachProbeCount.set(numTracks);
    }

    /**
     * Get the number of tracks examined when considering auto-attaching a metadata cache file to a newly-mounted
     * media database. The more examined, the more confident we can be in the cache matching the media, but the longer
     * it will take and the more metadata queries will be required. This value is only used for metadata caches created
     * by versions older than 0.4.1, because they lacked the ability to match by the details of the media database
     * itself.
     *
     * @return  the number of tracks that will be compared between the cache files and the media in the player
     */
    public int getAutoAttachProbeCount() {
        return autoAttachProbeCount.get();
    }

    /**
     * See if there is an auto-attach cache file that seems to match the media in the specified slot, and if so,
     * attach it.
     *
     * @param slot the player slot that is under consideration for automatic cache attachment
     */
    private void tryAutoAttaching(final SlotReference slot) {
        if (!getMountedMediaSlots().contains(slot)) {
            logger.error("Unable to auto-attach cache to empty slot {}", slot);
            return;
        }
        if (getMetadataCache(slot) != null) {
            logger.info("Not auto-attaching to slot {}; already has a cache attached.", slot);
            return;
        }
        if (autoAttachCacheFiles.isEmpty()) {
            logger.debug("No auto-attach files configured.");
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(5);  // Give us a chance to find out what type of media is in the new mount.
                    final MediaDetails details = getMediaDetailsFor(slot);
                    if (details != null && details.mediaType == CdjStatus.TrackType.REKORDBOX) {
                        // First stage attempt: See if we can match based on stored media details, which is both more reliable and
                        // less disruptive than trying to sample the player database to compare entries.
                        boolean attached = false;
                        for (File file : autoAttachCacheFiles) {
                            final MetadataCache cache = new MetadataCache(file);
                            try {
                                if (cache.sourceMedia != null && cache.sourceMedia.hashKey().equals(details.hashKey())) {
                                    // We found a solid match, no need to probe tracks.
                                    final boolean changed = cache.sourceMedia.hasChanged(details);
                                    logger.info("Auto-attaching metadata cache " + cache.getName() + " to slot " + slot +
                                            " based on media details " + (changed? "(changed since created)!" : "(unchanged)."));
                                    attachMetadataCacheInternal(slot, cache);
                                    attached = true;
                                    return;
                                }
                            } finally {
                                if (!attached) {
                                    cache.close();
                                }
                            }
                        }

                        // Could not match based on media details; fall back to older method based on probing track metadata.
                        ConnectionManager.ClientTask<Object> task = new ConnectionManager.ClientTask<Object>() {
                            @Override
                            public Object useClient(Client client) throws Exception {
                                tryAutoAttachingWithConnection(slot, client);
                                return null;
                            }
                        };
                        ConnectionManager.getInstance().invokeWithClientSession(slot.player, task, "trying to auto-attach metadata cache");
                    }
                } catch (Exception e) {
                    logger.error("Problem trying to auto-attach metadata cache for slot " + slot, e);
                }

            }
        }, "Metadata cache file auto-attachment attempt").start();
    }

    /**
     * Second stage of the auto-attach process, once we have obtained a connection to the database server for the
     * media slot we are checking our automatic metadata cache files against. Probes the metadata offered by that
     * sever to see if it matches any of the available caches, and if so, attaches that cache file.
     *
     * @param slot identifies the media slot we are checking for automatic cache matches
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @throws IOException if there is a communication problem
     * @throws InterruptedException if the thread is interrupted while trying to lock the client for menu operations
     * @throws TimeoutException if we are unable to lock the client for menu operations
     */
    private void tryAutoAttachingWithConnection(SlotReference slot, Client client) throws IOException, InterruptedException, TimeoutException {
        // Keeps track of the files we might be able to auto-attach, grouped and sorted by the playlist they
        // were created from, where playlist 0 means all tracks.
        final Map<Integer, LinkedList<MetadataCache>> candidateGroups = gatherCandidateAttachmentGroups();
        MetadataCache match = null;  // We will close any non-matched files from the candidateGroups in our finally clause,
                               // but we will leave this one open because we are returning it.
        try {
            // Set up a menu request to process each group.
            for (Map.Entry<Integer,LinkedList<MetadataCache>> entry : candidateGroups.entrySet()) {
                final LinkedList<MetadataCache> candidates;
                ArrayList<Integer> tracksToSample;
                if (client.tryLockingForMenuOperations(MENU_TIMEOUT, TimeUnit.SECONDS)) {
                    try {
                        final int playlistId = entry.getKey();
                        candidates = entry.getValue();
                        final long count = getTrackCount(slot.slot, client, playlistId);
                        if (count == Message.NO_MENU_RESULTS_AVAILABLE || count == 0) {
                            // No tracks available to match this set of candidates.
                            for (final MetadataCache candidate : candidates) {
                                candidate.close();
                            }
                            candidates.clear();
                        }

                        // Filter out any candidates with the wrong number of tracks.
                        final Iterator<MetadataCache> candidateIterator = candidates.iterator();
                        while (candidateIterator.hasNext()) {
                            final MetadataCache candidate = candidateIterator.next();
                            if (candidate.trackCount != count) {
                                candidate.close();
                                candidateIterator.remove();
                            }
                        }

                        // Bail before querying any metadata if we can already rule out all the candidates.
                        if (candidates.isEmpty()) {
                            continue;
                        }

                        // Gather as many track IDs as we are configured to sample, up to the number available
                        tracksToSample = chooseTrackSample(slot, client, (int) count);
                    } finally {
                        client.unlockForMenuOperations();
                    }
                } else {
                    throw new TimeoutException("Unable to lock player for menu operations.");
                }

                // Winnow out any auto-attachment candidates that don't match any sampled track
                for (final int trackId : tracksToSample) {
                    logger.info("Comparing track " + trackId + " with " + candidates.size() + " metadata cache file(s).");

                    final DataReference reference = new DataReference(slot, trackId);
                    final TrackMetadata track = queryMetadata(reference, CdjStatus.TrackType.REKORDBOX, client);
                    if (track == null) {
                        logger.warn("Unable to retrieve metadata when attempting cache auto-attach for slot {}, giving up", slot);
                        return;
                    }

                    for (int i = candidates.size() - 1; i >= 0; --i) {
                        final MetadataCache candidate = candidates.get(i);
                        if (!track.equals(candidate.getTrackMetadata(null, reference))) {
                            candidate.close();
                            candidates.remove(i);
                        }
                    }

                    if (candidates.isEmpty()) {
                        break;  // No point sampling more tracks, we have ruled out all candidates in this group.
                    }
                }

                if (candidates.isEmpty()) {
                    continue;  // This group has failed; move on to the next candidate group, if any.
                }

                match = candidates.get(0);  // We have found at least one matching cache, use the first.
                logger.info("Auto-attaching metadata cache " + match.getName() + " to slot " + slot);
                attachMetadataCacheInternal(slot, match);
                return;
            }
        } finally {  // No matter how we leave this function, close any of the remaining zip files we are not attaching.
            for (Map.Entry<Integer, LinkedList<MetadataCache>> entry : candidateGroups.entrySet()) {
                for (MetadataCache candidate : entry.getValue()) {
                    if (candidate != match) {
                        candidate.close();
                    }
                }
            }
        }
    }

    /**
     * Groups all of the metadata cache files that are candidates for auto-attachment to player slots into lists
     * that are keyed by the playlist ID used to create the cache file. Files that cache all tracks have a playlist
     * ID of 0.
     *
     * @return a map from playlist ID to the caches holding tracks from that playlist
     */
    private Map<Integer, LinkedList<MetadataCache>> gatherCandidateAttachmentGroups() {
        Map<Integer,LinkedList<MetadataCache>> candidateGroups = new TreeMap<Integer, LinkedList<MetadataCache>>();
        final Iterator<File> iterator = autoAttachCacheFiles.iterator();
        while (iterator.hasNext()) {
            final File file = iterator.next();
            try {
                final MetadataCache candidate = new MetadataCache(file);
                if (candidateGroups.get(candidate.sourcePlaylist) == null) {
                    candidateGroups.put(candidate.sourcePlaylist, new LinkedList<MetadataCache>());
                }
                candidateGroups.get(candidate.sourcePlaylist).add(candidate);
            } catch (Exception e) {
                logger.error("Unable to open metadata cache file " + file + ", discarding", e);
                iterator.remove();
            }
        }
        return candidateGroups;
    }

    /**
     * Pick a random sample of tracks to compare against the cache, to see if it matches the attached database. We
     * will choose whichever is the smaller of the number configured in {@link #getAutoAttachProbeCount()} or the
     * total number available in the database or playlist being compared with the cache file.
     *
     * @param slot the player slot in which the database we are comparing to our cache is found
     * @param client the connection to the player for performing database queries to find track IDs
     * @param count the number of tracks available to sample
     *
     * @return the IDs of the tracks we have chosen to compare
     *
     * @throws IOException if there is a problem communicating with the player
     */
    private ArrayList<Integer> chooseTrackSample(SlotReference slot, Client client, int count) throws IOException {
        int tracksLeft = count;
        int samplesNeeded = Math.min(tracksLeft, autoAttachProbeCount.get());
        ArrayList<Integer> tracksToSample = new ArrayList<Integer>(samplesNeeded);
        int offset = 0;
        Random random = new Random();
        while (samplesNeeded > 0) {
            int rand = random.nextInt(tracksLeft);
            if (rand < samplesNeeded) {
                --samplesNeeded;
                tracksToSample.add(findTrackIdAtOffset(slot, client, offset));
            }
            --tracksLeft;
            ++offset;
        }
        return tracksToSample;
    }

    /**
     * Find out how many tracks are present in a playlist (or in all tracks, if {@code playlistId} is 0) without
     * actually retrieving all the entries. This is used in checking whether a metadata cache matches what is found
     * in a player slot, and to set up the context for sampling a random set of individual tracks for deeper
     * comparison.
     *
     * @param slot the player slot in which the media is located that we would like to compare
     * @param client the player database connection we can use to perform queries
     * @param playlistId identifies the playlist we want to know about, or 0 of we are interested in all tracks
     * @return the number of tracks found in the player database, which is now ready to enumerate them if a positive
     *         value is returned
     *
     * @throws IOException if there is a problem communicating with the database server
     */
    private long getTrackCount(CdjStatus.TrackSourceSlot slot, Client client, int playlistId) throws IOException {
        Message response;
        if (playlistId == 0) {  // Form the proper request to render either all tracks or a playlist
            response = client.menuRequest(Message.KnownType.TRACK_MENU_REQ, Message.MenuIdentifier.MAIN_MENU,
                    slot, NumberField.WORD_0);
        }
        else {
            response = client.menuRequest(Message.KnownType.PLAYLIST_REQ, Message.MenuIdentifier.MAIN_MENU, slot,
                    NumberField.WORD_0, new NumberField(playlistId), NumberField.WORD_0);
            }

        return response.getMenuResultsCount();
    }


    /**
     * As part of checking whether a metadata cache can be auto-mounted for a particular media slot, this method
     * looks up the track at the specified offset within the player's track list, and returns its rekordbox ID.
     *
     * @param slot the slot being considered for auto-attaching a metadata cache
     * @param client the connection to the database server on the player holding that slot
     * @param offset an index into the list of all tracks present in the slot
     *
     * @throws IOException if there is a problem communicating with the player
     */
    private int findTrackIdAtOffset(SlotReference slot, Client client, int offset) throws IOException {
        Message entry = client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slot.slot, CdjStatus.TrackType.REKORDBOX, offset, 1).get(0);
        if (entry.getMenuItemType() == Message.MenuItemType.UNKNOWN) {
            logger.warn("Encountered unrecognized track list entry item type: {}", entry);
        }
        return (int)((NumberField)entry.arguments.get(1)).getValue();
    }

    /**
     * Discards any tracks from the hot cache that were loaded from a now-unmounted media slot, because they are no
     * longer valid.
     */
    private void flushHotCacheSlot(SlotReference slot) {
        // Iterate over a copy to avoid concurrent modification issues
        for (Map.Entry<DeckReference, TrackMetadata> entry : new HashMap<DeckReference,TrackMetadata>(hotCache).entrySet()) {
            if (slot == SlotReference.getSlotReference(entry.getValue().trackReference)) {
                logger.debug("Evicting cached metadata in response to unmount report {}", entry.getValue());
                hotCache.remove(entry.getKey());
            }
        }
    }

    /**
     * Finds the metadata cache file assigned to a particular player media slot, if any.
     *
     * @param slot the media slot to which a meta data cache is to be attached
     *
     * @return the metadata cache for that player and slot, or {@code null} if no cache has been attached
     */
    public MetadataCache getMetadataCache(SlotReference slot) {
        return metadataCacheFiles.get(slot);
    }

    /**
     * Keeps track of any players with mounted media.
     */
    private final Set<SlotReference> mediaMounts = Collections.newSetFromMap(new ConcurrentHashMap<SlotReference, Boolean>());

    /**
     * Records that there is media mounted in a particular media player slot, updating listeners if this is a change.
     *
     * @param slot the slot in which media is mounted
     */
    private void recordMount(SlotReference slot) {
        if (mediaMounts.add(slot)) {
            deliverMountUpdate(slot, true);
            try {
                VirtualCdj.getInstance().sendMediaQuery(slot);
            } catch (Exception e) {
                logger.warn("Problem trying to request media details for " + slot, e);
            }
        }
    }

    /**
     * Records that there is no media mounted in a particular media player slot, updating listeners if this is a change,
     * and clearing any affected items from our in-memory caches.
     *
     * @param slot the slot in which no media is mounted
     */
    private void removeMount(SlotReference slot) {
        mediaDetails.remove(slot);
        if (mediaMounts.remove(slot)) {
            deliverMountUpdate(slot, false);
        }
    }

    /**
     * Returns the set of media slots on the network that currently have media mounted in them.
     * Note that computers running rekordbox are included; their collections are valid media databases to
     * explore with the {@link MenuLoader}, and they are valid as {@link SlotReference} arguments to tell
     * players to load tracks from.
     *
     * @return the slots with media currently available on the network, including rekordbox instances
     */
    public Set<SlotReference> getMountedMediaSlots() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<SlotReference>(mediaMounts));
    }

    /**
     * Keeps track of the media details the Virtual CDJ has been able to find for us about the mounted slots.
     */
    private final Map<SlotReference,MediaDetails> mediaDetails = new ConcurrentHashMap<SlotReference, MediaDetails>();

    /**
     * Get the details we know about all mounted media.
     *
     * @return the media details the Virtual CDJ has been able to find for us about the mounted slots.
     */
    public Collection<MediaDetails> getMountedMediaDetails() {
        return Collections.unmodifiableCollection(mediaDetails.values());
    }

    /**
     * Look up the details we know about the media mounted in a particular slot
     *
     * @param slot the slot whose media is of interest
     * @return the details, or {@code null} if we don't have any
     */
    public MediaDetails getMediaDetailsFor(SlotReference slot) {
        return mediaDetails.get(slot);
    }

    /**
     * Keeps track of the registered mount update listeners.
     */
    private final Set<MountListener> mountListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<MountListener, Boolean>());

    /**
     * Adds the specified mount update listener to receive updates when media is mounted or unmounted by any player.
     * If {@code listener} is {@code null} or already present in the set of registered listeners, no exception is
     * thrown and no action is performed.
     *
     * <p>To reduce latency, updates are delivered to listeners directly on the thread that is receiving packets
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the mount update listener to add
     */
    @SuppressWarnings("SameParameterValue")
    public void addMountListener(MountListener listener) {
        if (listener != null) {
            mountListeners.add(listener);
        }
    }

    /**
     * Removes the specified mount update listener so that it no longer receives updates when a player mounts or
     * unmounts media in one of its media slots. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the mount update listener to remove
     */
    public void removeMountListener(MountListener listener) {
        if (listener != null) {
            mountListeners.remove(listener);
        }
    }

    /**
     * Get the set of currently-registered mount update listeners.
     *
     * @return the listeners that are currently registered for mount updates
     */
    @SuppressWarnings("WeakerAccess")
    public Set<MountListener> getMountListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<MountListener>(mountListeners));
    }

    /**
     * Send a mount update announcement to all registered listeners, and see if we can auto-attach a media cache file.
     *
     * @param slot the slot in which media has been mounted or unmounted
     * @param mounted will be {@code true} if there is now media mounted in the specified slot
     */
    private void deliverMountUpdate(SlotReference slot, boolean mounted) {
        if (mounted) {
            logger.info("Reporting media mounted in " + slot);

        } else {
            logger.info("Reporting media removed from " + slot);
        }
        for (final MountListener listener : getMountListeners()) {
            try {
                if (mounted) {
                    listener.mediaMounted(slot);
                } else {
                    listener.mediaUnmounted(slot);
                }

            } catch (Throwable t) {
                logger.warn("Problem delivering mount update to listener", t);
            }
        }
        if (mounted) {
            tryAutoAttaching(slot);
        }
    }

    /**
     * Keeps track of the registered cache update listeners.
     */
    private final Set<MetadataCacheListener> cacheListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<MetadataCacheListener, Boolean>());

    /**
     * Adds the specified cache update listener to receive updates when a metadata cache is attached or detached.
     * If {@code listener} is {@code null} or already present in the set of registered listeners, no exception is
     * thrown and no action is performed.
     *
     * <p>To reduce latency, updates are delivered to listeners directly on the thread that is receiving packets
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the cache update listener to add
     */
    public void addCacheListener(MetadataCacheListener listener) {
        if (listener != null) {
            cacheListeners.add(listener);
        }
    }

    /**
     * Removes the specified cache update listener so that it no longer receives updates when there
     * are changes to the available set of metadata caches. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the cache update listener to remove
     */
    public void removeCacheListener(MetadataCacheListener listener) {
        if (listener != null) {
            cacheListeners.remove(listener);
        }
    }

    /**
     * Get the set of currently-registered metadata cache update listeners.
     *
     * @return the listeners that are currently registered for metadata cache updates
     */
    @SuppressWarnings("WeakerAccess")
    public Set<MetadataCacheListener> getCacheListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<MetadataCacheListener>(cacheListeners));
    }

    /**
     * Send a metadata cache update announcement to all registered listeners.
     *
     * @param slot the media slot whose cache status has changed
     * @param cache the cache which has been attached, or, if {@code null}, the previous cache has been detached
     */
    private void deliverCacheUpdate(SlotReference slot, MetadataCache cache) {
        for (final MetadataCacheListener listener : getCacheListeners()) {
            try {
                if (cache == null) {
                    listener.cacheDetached(slot);
                } else {
                    listener.cacheAttached(slot, cache);
                }
            } catch (Throwable t) {
                logger.warn("Problem delivering metadata cache update to listener", t);
            }
        }
    }

    /**
     * Keeps track of the registered track metadata update listeners.
     */
    private final Set<TrackMetadataListener> trackListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<TrackMetadataListener, Boolean>());

    /**
     * Adds the specified track metadata listener to receive updates when the track metadata for a player changes.
     * If {@code listener} is {@code null} or already present in the set of registered listeners, no exception is
     * thrown and no action is performed.
     *
     * <p>To reduce latency, updates are delivered to listeners directly on the thread that is receiving packets
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the track metadata update listener to add
     */
    @SuppressWarnings("SameParameterValue")
    public void addTrackMetadataListener(TrackMetadataListener listener) {
        if (listener != null) {
            trackListeners.add(listener);
        }
    }

   /**
     * Removes the specified track metadata update listener so that it no longer receives updates when track
     * metadata for a player changes. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the track metadata update listener to remove
     */
    @SuppressWarnings("SameParameterValue")
    public void removeTrackMetadataListener(TrackMetadataListener listener) {
        if (listener != null) {
            trackListeners.remove(listener);
        }
    }

    /**
     * Get the set of currently-registered track metadata update listeners.
     *
     * @return the listeners that are currently registered for track metadata updates
     */
    @SuppressWarnings("WeakerAccess")
    public Set<TrackMetadataListener> getTrackMetadataListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<TrackMetadataListener>(trackListeners));
    }

    /**
     * Send a track metadata update announcement to all registered listeners.
     */
    private void deliverTrackMetadataUpdate(int player, TrackMetadata metadata) {
        if (!getTrackMetadataListeners().isEmpty()) {
            final TrackMetadataUpdate update = new TrackMetadataUpdate(player, metadata);
            for (final TrackMetadataListener listener : getTrackMetadataListeners()) {
                try {
                    listener.metadataChanged(update);

                } catch (Throwable t) {
                    logger.warn("Problem delivering track metadata update to listener", t);
                }
            }
        }
    }

    /**
     * Process an update packet from one of the CDJs. See if it has a valid track loaded; if not, clear any
     * metadata we had stored for that player. If so, see if it is the same track we already know about; if not,
     * request the metadata associated with that track.
     *
     * Also clears out any metadata caches that were attached for slots that no longer have media mounted in them,
     * and updates the sets of which players have media mounted in which slots.
     *
     * If any of these reflect a change in state, any registered listeners will be informed.
     *
     * @param update an update packet we received from a CDJ
     */
    private void handleUpdate(final CdjStatus update) {
        // First see if any metadata caches need evicting or mount sets need updating.
        if (update.isLocalUsbEmpty()) {
            final SlotReference slot = SlotReference.getSlotReference(update.getDeviceNumber(), CdjStatus.TrackSourceSlot.USB_SLOT);
            detachMetadataCache(slot);
            flushHotCacheSlot(slot);
            removeMount(slot);
        } else if (update.isLocalUsbLoaded()) {
            recordMount(SlotReference.getSlotReference(update.getDeviceNumber(), CdjStatus.TrackSourceSlot.USB_SLOT));
        }

        if (update.isLocalSdEmpty()) {
            final SlotReference slot = SlotReference.getSlotReference(update.getDeviceNumber(), CdjStatus.TrackSourceSlot.SD_SLOT);
            detachMetadataCache(slot);
            flushHotCacheSlot(slot);
            removeMount(slot);
        } else if (update.isLocalSdLoaded()){
            recordMount(SlotReference.getSlotReference(update.getDeviceNumber(), CdjStatus.TrackSourceSlot.SD_SLOT));
        }

        if (update.isDiscSlotEmpty()) {
            removeMount(SlotReference.getSlotReference(update.getDeviceNumber(), CdjStatus.TrackSourceSlot.CD_SLOT));
        } else {
            recordMount(SlotReference.getSlotReference(update.getDeviceNumber(), CdjStatus.TrackSourceSlot.CD_SLOT));
        }

        // Now see if a track has changed that needs new metadata.
        if (update.getTrackType() == CdjStatus.TrackType.UNKNOWN ||
                update.getTrackType() == CdjStatus.TrackType.NO_TRACK ||
                update.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.NO_TRACK ||
                update.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.UNKNOWN ||
                update.getRekordboxId() == 0) {  // We no longer have metadata for this device.
            clearDeck(update);
        } else {  // We can offer metadata for this device; check if we already looked up this track.
            final TrackMetadata lastMetadata = hotCache.get(DeckReference.getDeckReference(update.getDeviceNumber(), 0));
            final DataReference trackReference = new DataReference(update.getTrackSourcePlayer(),
                    update.getTrackSourceSlot(), update.getRekordboxId());
            if (lastMetadata == null || !lastMetadata.trackReference.equals(trackReference)) {  // We have something new!
                // First see if we can find the new track in the hot cache as a hot cue
                for (TrackMetadata cached : hotCache.values()) {
                    if (cached.trackReference.equals(trackReference)) {  // Found a hot cue hit, use it.
                        updateMetadata(update, cached);
                        return;
                    }
                }

                // Not in the hot cache so try actually retrieving it.
                if (activeRequests.add(update.getTrackSourcePlayer())) {
                    // We had to make sure we were not already asking for this track.
                    clearDeck(update);  // We won't know what it is until our request completes.
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                TrackMetadata data = requestMetadataInternal(trackReference, update.getTrackType(), true);
                                if (data != null) {
                                    updateMetadata(update, data);
                                }
                            } catch (Exception e) {
                                logger.warn("Problem requesting track metadata from update" + update, e);
                            } finally {
                                activeRequests.remove(update.getTrackSourcePlayer());
                            }
                        }
                    }, "MetadataFinder metadata request").start();
                }
            }
        }
    }

    /**
     * Allows us to automatically shut down when the VirtualCdj, which we depend on, does.
     */
    private final LifecycleListener lifecycleListener = new LifecycleListener() {
        @Override
        public void started(LifecycleParticipant sender) {
            logger.debug("MetadataFinder won't automatically start just because {} has.", sender);
        }

        @Override
        public void stopped(LifecycleParticipant sender) {
            if (isRunning()) {
                logger.info("MetadataFinder stopping because {} has.", sender);
                stop();
            }
        }
    };

    /**
     * Start finding track metadata for all active players. Starts the {@link VirtualCdj} if it is not already
     * running, because we need it to send us device status updates to notice when new tracks are loaded; this
     * starts the {@link DeviceFinder} (which is also needed by the {@code VirtualCdj}) so we can keep track of
     * the comings and goings of players themselves. We start the {@link ConnectionManager} in order to make queries
     * to obtain metadata.
     *
     * @throws Exception if there is a problem starting the required components
     */
    public synchronized void start() throws Exception {
        if (!isRunning()) {
            ConnectionManager.getInstance().addLifecycleListener(lifecycleListener);
            ConnectionManager.getInstance().start();
            DeviceFinder.getInstance().start();
            DeviceFinder.getInstance().addDeviceAnnouncementListener(announcementListener);
            VirtualCdj.getInstance().addLifecycleListener(lifecycleListener);
            VirtualCdj.getInstance().start();
            VirtualCdj.getInstance().addUpdateListener(updateListener);
            queueHandler = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (isRunning()) {
                        try {
                            handleUpdate(pendingUpdates.take());
                        } catch (InterruptedException e) {
                            logger.debug("Interrupted, presumably due to MetadataFinder shutdown.", e);
                        } catch (Exception e) {
                            logger.error("Problem handling CDJ status update.", e);
                        }
                    }
                }
            });
            running.set(true);
            queueHandler.start();
            deliverLifecycleAnnouncement(logger, true);

            // If there are already any rekordbox instances on the network, "mount" their collections.
            for (DeviceAnnouncement existingDevice : DeviceFinder.getInstance().getCurrentDevices()) {
                announcementListener.deviceFound(existingDevice);
            }
        }
    }

    /**
     * Stop finding track metadata for all active players.
     */
    public synchronized void stop() {
        if (isRunning()) {
            VirtualCdj.getInstance().removeUpdateListener(updateListener);
            running.set(false);
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;

            // Report the loss of our hot cached metadata on the proper thread, outside our lock
            final Set<DeckReference> dyingCache = new HashSet<DeckReference>(hotCache.keySet());
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    for (DeckReference deck : dyingCache) {
                        if (deck.hotCue == 0) {
                            deliverTrackMetadataUpdate(deck.player, null);
                        }
                    }

                }
            });
            hotCache.clear();
            deliverLifecycleAnnouncement(logger, false);
        }
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final MetadataFinder ourInstance = new MetadataFinder();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    public static MetadataFinder getInstance() {
        return ourInstance;
    }

    /**
     * Prevent direct instantiation, and arrange for us to hear about the responses to any media details requests we
     * ask the Virtual CDJ to make for us.
     */
    private MetadataFinder() {
        VirtualCdj.getInstance().addMediaDetailsListener(new MediaDetailsListener() {
            @Override
            public void detailsAvailable(MediaDetails details) {
                mediaDetails.put(details.slotReference, details);
                if (!mediaMounts.contains(details.slotReference)) {
                    // We do this after the fact in case the slot was being unmounted at the same time as we were
                    // responding to the event, so we end up in the correct final state.
                    logger.warn("Discarding media details reported for an unmounted media slot:" + details);
                    mediaDetails.remove(details.slotReference);
                }
            }
        });
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("MetadataFinder[").append("running:").append(isRunning()).append(", passive:").append(isPassive());
        if (isRunning()) {
            sb.append(", loadedTracks:").append(getLoadedTracks()).append(", mountedMediaSlots:").append(getMountedMediaSlots());
            sb.append(", mountedMediaDetails:").append(getMountedMediaDetails());
            sb.append(", metadataCacheFiles:").append(metadataCacheFiles);
        }
        return sb.append("]").toString();
    }
}
