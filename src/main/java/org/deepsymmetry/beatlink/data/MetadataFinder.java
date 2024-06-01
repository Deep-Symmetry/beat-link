package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.dbserver.Client;
import org.deepsymmetry.beatlink.dbserver.ConnectionManager;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.beatlink.dbserver.NumberField;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>Watches for new tracks to be loaded on players, and queries the
 * appropriate player for the metadata information when that happens.</p>
 *
 * <p>Maintains a hot cache of metadata about any track currently loaded in a player, either on the main playback
 * deck, or as a hot cue, since those tracks could start playing instantly.</p>
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public class MetadataFinder extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(MetadataFinder.class);

    /**
     * Given a status update from a CDJ, find the metadata for the track that it has loaded, if any. If there is
     * an appropriate metadata file downloaded, will use that, otherwise makes a query to the player's dbserver.
     *
     * @param status the CDJ status update that will be used to determine the loaded track and ask the appropriate
     *               player for metadata about it
     *
     * @return the metadata that was obtained, if any
     */
    @API(status = API.Status.STABLE)
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
     * unless we have a metadata download available for the specified media slot, in which case that will be used
     * instead.
     *
     * @param track uniquely identifies the track whose metadata is desired
     * @param trackType identifies the type of track being requested, which affects the type of metadata request
     *                  message that must be used
     *
     * @return the metadata, if any
     */
    @API(status = API.Status.STABLE)
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
     *                      metadata updates will use available caches and metadata export downloads only
     *
     * @return the metadata found, if any
     */
    private TrackMetadata requestMetadataInternal(final DataReference track, final CdjStatus.TrackType trackType,
                                                  final boolean failIfPassive) {
        // First see if any registered metadata providers can offer it for us.
        final MediaDetails sourceDetails = getMediaDetailsFor(track.getSlotReference());
        if (sourceDetails != null) {
            final TrackMetadata provided = allMetadataProviders.getTrackMetadata(sourceDetails, track);
            if (provided != null) {
                return provided;
            }
        }

        // At this point, unless we are allowed to actively request the data, we are done. We can always actively
        // request tracks from rekordbox.
        if (passive.get() && failIfPassive && track.slot != CdjStatus.TrackSourceSlot.COLLECTION) {
            return null;
        }

        // Use the dbserver protocol implementation to request the metadata.
        ConnectionManager.ClientTask<TrackMetadata> task = client -> queryMetadata(track, trackType, client);

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
    @API(status = API.Status.STABLE)
    public static final int MENU_TIMEOUT = 20;

    /**
     * Request metadata for a specific track ID, given a dbserver connection to a player that has already been set up.
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
     * Requests the cue list for a specific track ID, given a dbserver connection to a player that has already
     * been set up.
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
        // First try for an extended cue list, with colors and names, and hot cues above C.
        Message response = client.simpleRequest(Message.KnownType.CUE_LIST_EXT_REQ, null,
                client.buildRMST(Message.MenuIdentifier.DATA, slot), new NumberField(rekordboxId), NumberField.WORD_0);
        if (response.knownType == Message.KnownType.CUE_LIST_EXT) {
            return new CueList(response);
        }
        // Fall back to an original Nexus cue list.
        response = client.simpleRequest(Message.KnownType.CUE_LIST_REQ, null,
                client.buildRMST(Message.MenuIdentifier.DATA, slot), new NumberField(rekordboxId));
        if (response.knownType == Message.KnownType.CUE_LIST) {
            return new CueList(response);
        }
        logger.error("Unexpected response type when requesting cue list: {}", response);
        return null;
    }

    /**
     * Request the list of all tracks in the specified slot, given a dbserver connection to a player that has already
     * been set up.
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
     * Ask the connected dbserver for the playlist entries of the specified playlist (if {@code folder} is {@code false}),
     * or the list of playlists and folders inside the specified playlist folder (if {@code folder} is {@code true}).
     * Pulled into a separate method so it can be used from multiple different client transactions.
     *
     * @param slot the slot in which the playlist can be found
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
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
    List<Message> getPlaylistItems(CdjStatus.TrackSourceSlot slot, int sortOrder, int playlistOrFolderId,
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
     * Ask the specified player's dbserver for the playlist entries of the specified playlist (if {@code folder} is {@code false}),
     * or the list of playlists and folders inside the specified playlist folder (if {@code folder} is {@code true}).
     *
     * @param player the player number whose playlist entries are of interest
     * @param slot the slot in which the playlist can be found
     * @param sortOrder the order in which responses should be sorted, 0 for default, see the
     *                  <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html#alternate-track-sort-orders">Packet Analysis
     *                  document</a> for details
     * @param playlistOrFolderId the database ID of the desired playlist or folder
     * @param folder indicates whether we are asking for the contents of a folder or playlist
     *
     * @return the items that are found in the specified playlist or folder; they will be tracks if we are asking
     *         for a playlist, or playlists and folders if we are asking for a folder
     *
     * @throws Exception if there is a problem obtaining the playlist information
     */
    @API(status = API.Status.STABLE)
    public List<Message> requestPlaylistItemsFrom(final int player, final CdjStatus.TrackSourceSlot slot,
                                                  final int sortOrder, final int playlistOrFolderId,
                                                  final boolean folder)
            throws Exception {
        ConnectionManager.ClientTask<List<Message>> task = client -> getPlaylistItems(slot, sortOrder, playlistOrFolderId, folder, client);

        return ConnectionManager.getInstance().invokeWithClientSession(player, task, "requesting playlist information");
    }

   /**
     * Keeps track of the current metadata cached for each player. We cache metadata for any track which is currently
     * on-deck in the player, as well as any that were loaded into a player's hot-cue slot.
     */
    private final Map<DeckReference, TrackMetadata> hotCache = new ConcurrentHashMap<>();

    /**
     * A queue used to hold CDJ status updates we receive from the {@link VirtualCdj} so we can process them on a
     * lower priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private final LinkedBlockingDeque<CdjStatus> pendingUpdates = new LinkedBlockingDeque<>(100);

    /**
     * Our update listener just puts appropriate device updates on our queue, so we can process them on a lower
     * priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private final DeviceUpdateListener updateListener = update -> {
        logger.debug("Received device update {}", update);
        if (update instanceof CdjStatus) {
            if (!pendingUpdates.offerLast((CdjStatus)update)) {
                logger.warn("Discarding CDJ update because our queue is backed up.");
            }
        }
    };


    /**
     * Our announcement listener watches for devices to disappear from the network so we can discard all information
     * about them. It also records the arrival and departure of rekordbox collections as rekordbox comes and goes.
     */
    private final DeviceAnnouncementListener announcementListener = new DeviceAnnouncementListener() {
        @Override
        public void deviceFound(final DeviceAnnouncement announcement) {
            logger.info("Processing device found, number:{}, name:\"{}\".", announcement.getDeviceNumber(), announcement.getDeviceName());
            if ((((announcement.getDeviceNumber() > 0x0f) && announcement.getDeviceNumber() < 0x20) || announcement.getDeviceNumber() > 40) &&
                    (announcement.getDeviceName().startsWith("rekordbox"))) {  // Looks like rekordbox.
                logger.info("Recording rekordbox collection mount.");
                recordMount(SlotReference.getSlotReference(announcement.getDeviceNumber(),
                        CdjStatus.TrackSourceSlot.COLLECTION));  // Report the rekordbox collection as mounted media.
            }
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            clearMetadata(announcement);
            if (announcement.getDeviceNumber() < 0x10) {  // Looks like a player, clear the whole panoply of caches.
                removeMount(SlotReference.getSlotReference(announcement.getDeviceNumber(), CdjStatus.TrackSourceSlot.CD_SLOT));
                removeMount(SlotReference.getSlotReference(announcement.getDeviceNumber(), CdjStatus.TrackSourceSlot.USB_SLOT));
                removeMount(SlotReference.getSlotReference(announcement.getDeviceNumber(), CdjStatus.TrackSourceSlot.SD_SLOT));
            } else if ((((announcement.getDeviceNumber() > 0x0f) && announcement.getDeviceNumber() < 0x20) || announcement.getDeviceNumber() > 40) &&
                    (announcement.getDeviceName().startsWith("rekordbox"))) {  // Looks like rekordbox, clear "mounted" database.
                removeMount(SlotReference.getSlotReference(announcement.getDeviceNumber(), CdjStatus.TrackSourceSlot.COLLECTION));
            }
        }
    };

    /**
     * Keep track of whether we are running
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Check whether we are currently running. Unless we are in passive mode, we will also automatically request
     * metadata from the appropriate player when a new track is loaded that is not found in the hot cache.
     *
     * @return true if track metadata is being kept track of for all active players
     *
     * @see #isPassive()
     */
    @API(status = API.Status.STABLE)
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Indicates whether we should use metadata only from caches or downloaded metadata exports,
     * never actively requesting it from a player.
     */
    private final AtomicBoolean passive = new AtomicBoolean(false);

    /**
     * Check whether we are configured to use metadata only from caches and downloaded metadata exports,
     * never actively requesting it from a player. Note that this will implicitly mean all of the metadata-related
     * finders ({@link ArtFinder}, {@link BeatGridFinder}, and {@link WaveformFinder}) are in passive mode as well,
     * because their activity is triggered by the availability of new track metadata.
     *
     * @return {@code true} if only cached metadata will be used, or {@code false} if metadata will be requested from
     *         a player if a track is loaded from a media slot for which no metadata has been downloaded
     */
    @API(status = API.Status.STABLE)
    public boolean isPassive() {
        return passive.get();
    }

    /**
     * Set whether we are configured to use metadata only from caches or downloaded metadata exports,
     * never actively requesting it from a player.
     * Note that this will implicitly put all of the metadata-related finders ({@link ArtFinder}, {@link BeatGridFinder},
     * and {@link WaveformFinder}) into a passive mode as well, because their activity is triggered by the availability
     * of new track metadata.
     *
     * @param passive {@code true} if only cached metadata will be used, or {@code false} if metadata will be requested
     *                from a player if a track is loaded from a media slot for which no metadata has been downloaded
     */
    @API(status = API.Status.STABLE)
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
        final int player = announcement.getDeviceNumber();
        // Iterate over a copy to avoid concurrent modification issues
        for (DeckReference deck : new HashSet<>(hotCache.keySet())) {
            if (deck.player == player) {
                hotCache.remove(deck);
                if (deck.hotCue == 0) {
                    deliverTrackMetadataUpdate(player, null);  // Inform listeners the metadata is gone.
                }
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
    @API(status = API.Status.STABLE)
    public Map<DeckReference, TrackMetadata> getLoadedTracks() {
        ensureRunning();
        // Make a copy so callers get an immutable snapshot of the current state.
        return Map.copyOf(hotCache);
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
    @API(status = API.Status.STABLE)
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
    @API(status = API.Status.STABLE)
    public TrackMetadata getLatestMetadataFor(DeviceUpdate update) {
        return getLatestMetadataFor(update.getDeviceNumber());
    }

    /**
     * Keep track of the devices we are currently trying to get metadata from in response to status updates.
     */
    private final Set<Integer> activeRequests = Collections.newSetFromMap(new ConcurrentHashMap<>());


    /**
     * Discards any tracks from the hot cache that were loaded from a now-unmounted media slot, because they are no
     * longer valid.
     */
    private void flushHotCacheSlot(SlotReference slot) {
        // Iterate over a copy to avoid concurrent modification issues
        for (Map.Entry<DeckReference, TrackMetadata> entry : new HashMap<>(hotCache).entrySet()) {
            if (slot == SlotReference.getSlotReference(entry.getValue().trackReference)) {
                logger.debug("Evicting cached metadata in response to unmount report {}", entry.getValue());
                hotCache.remove(entry.getKey());
            }
        }
    }

    /**
     * Keeps track of any players with mounted media.
     */
    private final Set<SlotReference> mediaMounts = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Records that there is media mounted in a particular media player slot, updating listeners if this is a change.
     * Also send a query to the player requesting details about the media mounted in that slot, if we don't already
     * have that information.
     *
     * @param slot the slot in which media is mounted
     */
    private void recordMount(SlotReference slot) {

        if (mediaMounts.add(slot)) {
            deliverMountUpdate(slot, true);
        }
        if (!mediaDetails.containsKey(slot) && !VirtualCdj.getInstance().inOpusQuadCompatibilityMode()) {
            try {
                VirtualCdj.getInstance().sendMediaQuery(slot);
            } catch (Exception e) {
                logger.warn("Problem trying to request media details for {}", slot, e);
            }
        }
        if (OpusProvider.getInstance().isRunning()) {
            OpusProvider.getInstance().pollAndSendMediaDetails(slot.player);
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
    @API(status = API.Status.STABLE)
    public Set<SlotReference> getMountedMediaSlots() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Set.copyOf(mediaMounts);
    }

    /**
     * Keeps track of the media details the Virtual CDJ has been able to find for us about the mounted slots.
     */
    private final Map<SlotReference,MediaDetails> mediaDetails = new ConcurrentHashMap<>();

    /**
     * Get the details we know about all mounted media.
     *
     * @return the media details the Virtual CDJ has been able to find for us about the mounted slots.
     */
    @API(status = API.Status.STABLE)
    public Collection<MediaDetails> getMountedMediaDetails() {
        return Collections.unmodifiableCollection(mediaDetails.values());
    }

    /**
     * Look up the details we know about the media mounted in a particular slot
     *
     * @param slot the slot whose media is of interest
     * @return the details, or {@code null} if we don't have any
     */
    @API(status = API.Status.STABLE)
    public MediaDetails getMediaDetailsFor(SlotReference slot) {
        return mediaDetails.get(slot);
    }

    /**
     * Keeps track of the registered mount update listeners.
     */
    private final Set<MountListener> mountListeners = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Adds the specified mount update listener to receive updates when media is mounted or unmounted by any player.
     * If {@code listener} is {@code null} or already present in the set of registered listeners, no exception is
     * thrown and no action is performed.
     *
     * <p>Note that at the time a mount is detected, we will not yet know any details about the mounted media.
     * If {@code listener} also implements {@link MediaDetailsListener}, then as soon as the media details have
     * been reported by the mounting player, {@link MediaDetailsListener#detailsAvailable(MediaDetails)} will be
     * called with them.</p>
     *
     * <p>To reduce latency, updates are delivered to listeners directly on the thread that is receiving packets
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the mount update listener to add
     */
    @API(status = API.Status.STABLE)
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
    @API(status = API.Status.STABLE)
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
    @API(status = API.Status.STABLE)
    public Set<MountListener> getMountListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Set.copyOf(mountListeners);
    }

    /**
     * Send a mount update announcement to all registered listeners.
     *
     * @param slot the slot in which media has been mounted or unmounted
     * @param mounted will be {@code true} if there is now media mounted in the specified slot
     */
    private void deliverMountUpdate(SlotReference slot, boolean mounted) {
        if (mounted) {
            logger.info("Reporting media mounted in {}", slot);

        } else {
            logger.info("Reporting media removed from {}", slot);
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
    }


    /**
     * Keeps track of the registered track metadata update listeners.
     */
    private final Set<TrackMetadataListener> trackListeners = Collections.newSetFromMap(new ConcurrentHashMap<>());

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
     * <p>Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the track metadata update listener to add
     */
    @API(status = API.Status.STABLE)
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
   @API(status = API.Status.STABLE)
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
    @API(status = API.Status.STABLE)
    public Set<TrackMetadataListener> getTrackMetadataListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Set.copyOf(trackListeners);
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
     * Holds the providers of metadata that client applications register. The keys are the {@link MediaDetails#hashKey()}
     * values of the media that they reported providing metadata for. Providers that offer metadata for all media are
     * stored with a key of an empty string.
     */
    private final Map<String, Set<MetadataProvider>> metadataProviders = new ConcurrentHashMap<>();



    /**
     * Adds a metadata provider that will be consulted to see if it can provide metadata for newly-loaded tracks before
     * we try to retrieve it from the players. This function will immediately call
     * {@link MetadataProvider#supportedMedia()} and will only consult the provider for tracks loaded from the media
     * mentioned in the response. The function is only called once, when initially adding the provider, so if the set
     * of supported media changes, you will need to remove the provider and re-add it. Providers that can provide
     * metadata for all media can simply return an empty list of supported media, and they will always be consulted.
     * Only do this if they truly can always provide metadata, or you will slow down the lookup process by having them
     * frequently called in vain.
     *
     * @param provider the object that can supply metadata about tracks
     */
    @API(status = API.Status.STABLE)
    public void addMetadataProvider(MetadataProvider provider) {
        List<MediaDetails> supportedMedia = provider.supportedMedia();
        if (supportedMedia == null || supportedMedia.isEmpty()) {
            addMetadataProviderForMedia("", provider);
        } else {
            for (MediaDetails details : supportedMedia) {
                addMetadataProviderForMedia(details.hashKey(), provider);
            }
        }
    }

    /**
     * Internal method that adds a metadata provider to the set associated with a particular hash key, creating the
     * set if needed.
     *
     * @param key the hashKey identifying the media for which this provider can offer metadata (or the empty string if
     *            it can offer metadata for all media)
     * @param provider the metadata provider to be added to the active set
     */
    private void addMetadataProviderForMedia(String key, MetadataProvider provider) {
        if (!metadataProviders.containsKey(key)) {
            metadataProviders.put(key, Collections.newSetFromMap(new ConcurrentHashMap<>()));
        }
        Set<MetadataProvider> providers = metadataProviders.get(key);
        providers.add(provider);
    }

    /**
     * Removes a metadata provider, so it will no longer be consulted to provide metadata for tracks loaded from any
     * media.
     *
     * @param provider the metadata provider to remove.
     */
    @API(status = API.Status.STABLE)
    public void removeMetadataProvider(MetadataProvider provider) {
        for (Set<MetadataProvider> providers : metadataProviders.values()) {
            providers.remove(provider);
        }
    }

    /**
     * Get the set of metadata providers that can offer metadata for tracks loaded from the specified media.
     *
     * @param sourceMedia the media whose metadata providers are desired, or {@code null} to get the set of
     *                    metadata providers that can offer metadata for all media.
     *
     * @return any registered metadata providers that reported themselves as supporting tracks from that media
     */
    @API(status = API.Status.STABLE)
    public Set<MetadataProvider> getMetadataProviders(MediaDetails sourceMedia) {
        String key = (sourceMedia == null)? "" : sourceMedia.hashKey();
        Set<MetadataProvider> result = metadataProviders.get(key);
        if (result == null) {
            return Collections.emptySet();
        }
        return Set.copyOf(result);
    }

    /**
     * A composite metadata provider that runs all registered metadata providers that are appropriate for a
     * particular source media, returning the first non-{@code null} result from any of them. This can be used
     * wherever metadata is needed before moving on to other methods.
     */
    final MetadataProvider allMetadataProviders = new MetadataProvider() {
        @Override
        public List<MediaDetails> supportedMedia() {
            return Collections.emptyList();  // We support all media, not that anyone will call this.
        }

        @Override
        public TrackMetadata getTrackMetadata(MediaDetails sourceMedia, DataReference track) {
            for (MetadataProvider provider : getMetadataProviders(sourceMedia)) {
                TrackMetadata result = provider.getTrackMetadata(sourceMedia, track);
                if (result != null) {
                    return result;
                }
            }
            for (MetadataProvider provider : getMetadataProviders(null)) {
                TrackMetadata result = provider.getTrackMetadata(sourceMedia, track);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }

        @Override
        public AlbumArt getAlbumArt(MediaDetails sourceMedia, DataReference art) {
            for (MetadataProvider provider : getMetadataProviders(sourceMedia)) {
                AlbumArt result = provider.getAlbumArt(sourceMedia, art);
                if (result != null) {
                    return result;
                }
            }
            for (MetadataProvider provider : getMetadataProviders(null)) {
                AlbumArt result = provider.getAlbumArt(sourceMedia, art);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }

        @Override
        public BeatGrid getBeatGrid(MediaDetails sourceMedia, DataReference track) {
            for (MetadataProvider provider : getMetadataProviders(sourceMedia)) {
                BeatGrid result = provider.getBeatGrid(sourceMedia, track);
                if (result != null) {
                    return result;
                }
            }
            for (MetadataProvider provider : getMetadataProviders(null)) {
                BeatGrid result = provider.getBeatGrid(sourceMedia, track);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }

        @Override
        public CueList getCueList(MediaDetails sourceMedia, DataReference track) {
            for (MetadataProvider provider : getMetadataProviders(sourceMedia)) {
                CueList result = provider.getCueList(sourceMedia, track);
                if (result != null) {
                    return result;
                }
            }
            for (MetadataProvider provider : getMetadataProviders(null)) {
                CueList result = provider.getCueList(sourceMedia, track);
                if (result != null) {
                    return result;
                }
            }
            return null;

        }

        @Override
        public WaveformPreview getWaveformPreview(MediaDetails sourceMedia, DataReference track) {
            for (MetadataProvider provider : getMetadataProviders(sourceMedia)) {
                WaveformPreview result = provider.getWaveformPreview(sourceMedia, track);
                if (result != null) {
                    return result;
                }
            }
            for (MetadataProvider provider : getMetadataProviders(null)) {
                WaveformPreview result = provider.getWaveformPreview(sourceMedia, track);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }

        @Override
        public WaveformDetail getWaveformDetail(MediaDetails sourceMedia, DataReference track) {
            for (MetadataProvider provider : getMetadataProviders(sourceMedia)) {
                WaveformDetail result = provider.getWaveformDetail(sourceMedia, track);
                if (result != null) {
                    return result;
                }
            }
            for (MetadataProvider provider : getMetadataProviders(null)) {
                WaveformDetail result = provider.getWaveformDetail(sourceMedia, track);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }

        @Override
        public RekordboxAnlz.TaggedSection getAnalysisSection(MediaDetails sourceMedia, DataReference track, String fileExtension, String typeTag) {
            for (MetadataProvider provider : getMetadataProviders(sourceMedia)) {
                RekordboxAnlz.TaggedSection result = provider.getAnalysisSection(sourceMedia, track, fileExtension, typeTag);
                if (result != null) {
                    return result;
                }
            }
            for (MetadataProvider provider : getMetadataProviders(null)) {
                RekordboxAnlz.TaggedSection result = provider.getAnalysisSection(sourceMedia, track, fileExtension, typeTag);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }
    };

    /**
     * <p>Process an update packet from one of the CDJs. See if it has a valid track loaded; if not, clear any
     * metadata we had stored for that player. If so, see if it is the same track we already know about; if not,
     * request the metadata associated with that track.</p>
     *
     * <p>Also updates the sets of which players have media mounted in which slots.</p>
     *
     * <p>If any of these reflect a change in state, any registered listeners will be informed.</p>
     *
     * @param update an update packet we received from a CDJ
     */
    private void handleUpdate(final CdjStatus update) {
        // First see if any mount sets need updating.
        if (update.isLocalUsbEmpty()) {
            final SlotReference slot = SlotReference.getSlotReference(update.getDeviceNumber(), CdjStatus.TrackSourceSlot.USB_SLOT);
            flushHotCacheSlot(slot);
            removeMount(slot);
        } else if (update.isLocalUsbLoaded()) {
            recordMount(SlotReference.getSlotReference(update.getDeviceNumber(), CdjStatus.TrackSourceSlot.USB_SLOT));
        }

        if (update.isLocalSdEmpty()) {
            final SlotReference slot = SlotReference.getSlotReference(update.getDeviceNumber(), CdjStatus.TrackSourceSlot.SD_SLOT);
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

                // Not in the hot cache so try actually retrieving it, if possible.
                if (ConnectionManager.getInstance().getPlayerDBServerPort(update.getTrackSourcePlayer()) > 0 || VirtualRekordbox.getInstance().isRunning()) {
                    if (activeRequests.add(update.getTrackSourcePlayer())) {
                        // We had to make sure we were not already asking for this track.
                        clearDeck(update);  // We won't know what it is until our request completes.
                        new Thread(() -> {
                            try {
                                TrackMetadata data = requestMetadataInternal(trackReference, update.getTrackType(), true);
                                if (data != null) {
                                    updateMetadata(update, data);
                                }
                            } catch (Exception e) {
                                logger.warn("Problem requesting track metadata from update {}", update, e);
                            } finally {
                                activeRequests.remove(update.getTrackSourcePlayer());
                            }
                        }, "MetadataFinder metadata request").start();
                    }
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
    @API(status = API.Status.STABLE)
    public synchronized void start() throws Exception {
        if (!isRunning()) {
            ConnectionManager.getInstance().addLifecycleListener(lifecycleListener);
            ConnectionManager.getInstance().start();
            DeviceFinder.getInstance().start();
            DeviceFinder.getInstance().addDeviceAnnouncementListener(announcementListener);
            VirtualCdj.getInstance().addLifecycleListener(lifecycleListener);
            VirtualCdj.getInstance().start();
            VirtualCdj.getInstance().addUpdateListener(updateListener);
            queueHandler = new Thread(() -> {
                while (isRunning()) {
                    try {
                        handleUpdate(pendingUpdates.take());
                    } catch (InterruptedException e) {
                        logger.debug("Interrupted, presumably due to MetadataFinder shutdown.", e);
                    } catch (Exception e) {
                        logger.error("Problem handling CDJ status update.", e);
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
    @API(status = API.Status.STABLE)
    public synchronized void stop() {
        if (isRunning()) {
            VirtualCdj.getInstance().removeUpdateListener(updateListener);
            running.set(false);
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;

            // Report the loss of our hot cached metadata on the proper thread, outside our lock
            final Set<DeckReference> dyingCache = new HashSet<>(hotCache.keySet());
            SwingUtilities.invokeLater(() -> {
                for (DeckReference deck : dyingCache) {
                    if (deck.hotCue == 0) {
                        deliverTrackMetadataUpdate(deck.player, null);
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
    @API(status = API.Status.STABLE)
    public static MetadataFinder getInstance() {
        return ourInstance;
    }

    /**
     * Prevent direct instantiation, and arrange for us to hear about the responses to any media details requests we
     * ask the Virtual CDJ to make for us. and pass them on to any {@link MountListener} instances that also implement
     * {@link MediaDetailsListener}.
     */
    private MetadataFinder() {
        VirtualCdj.getInstance().addMediaDetailsListener(details -> {
            mediaDetails.put(details.slotReference, details);
            if (!mediaMounts.contains(details.slotReference)) {
                // We do this after the fact in case the slot was being unmounted at the same time as we were
                // responding to the event, so we end up in the correct final state.
                logger.warn("Discarding media details reported for an unmounted media slot: {}", details);
                mediaDetails.remove(details.slotReference);
            } else {
                for (final MountListener listener : getMountListeners()) {
                    try {
                        if (listener instanceof MediaDetailsListener) {
                            ((MediaDetailsListener) listener).detailsAvailable(details);
                        }
                    } catch (Throwable t) {
                        logger.warn("Problem delivering media details update to mount listener", t);
                    }
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
        }
        return sb.append("]").toString();
    }
}
