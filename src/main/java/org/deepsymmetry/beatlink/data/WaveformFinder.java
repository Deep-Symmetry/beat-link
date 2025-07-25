package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.dbserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p>Watches for new metadata to become available for tracks loaded on players, and queries the
 * appropriate player for track waveforms when that happens. Can be configured to load only the small waveform
 * previews, or both those and the full scrollable waveform details.</p>
 *
 * <p>Maintains a hot cache of waveforms for any track currently loaded in a player, either on the main playback
 * deck, or as a hot cue, since those tracks could start playing instantly.</p>
 *
 * <p>Implicitly honors the active/passive setting of the {@link MetadataFinder}
 * (see {@link MetadataFinder#setPassive(boolean)}), because waveforms are loaded in response to metadata updates.</p>
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public class WaveformFinder extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(WaveformFinder.class);

    /**
     * Identifies the various waveform formats that are available.
     */
    public enum WaveformStyle {
        /**
         * The blue-and-white format used prior to the nxs2 hardware.
         */
        BLUE,

        /**
         * The full-color waveforms introduced with the nxs2 players.
         */
        RGB,

        /**
         * The three-band waveforms introduced with the CDJ-3000 players.
         */
        THREE_BAND
    }

    /**
     * The frequency bands that are drawn for three-band waveforms, along with the colors used to draw them.
     */
    public enum ThreeBandLayer {

        /**
         * Low frequencies are drawn in dark blue.
         */
        LOW(new Color(32, 83,  217)),

        /**
         * The overlap of low and mid-range frequencies are drawn in brown.
         */
        LOW_AND_MIO(new Color(169, 107, 39)),

        /**
         * Mid-range frequencies are drawn in amber.
         */
        MID(new Color(242, 170, 60)),

        /**
         * High frequencies are drawn in white.
         */
        HIGH(new Color(255, 255, 255));

        /**
         * The color with which this band should be drawn.
         */
        public final Color color;

        /**
         * Constructor simply records the band color.
         *
         * @param color the color with which this band should be drawn.
         */
        ThreeBandLayer(Color color) {
            this.color = color;
        }
    }

    /**
     * Keeps track of the current waveform previews cached for each player. We hot cache data for any track which is
     * currently on-deck in the player, as well as any that were loaded into a player's hot-cue slot.
     */
    private final Map<DeckReference, WaveformPreview> previewHotCache = new ConcurrentHashMap<>();

    /**
     * Keeps track of the current waveform details cached for each player. We hot cache data for any track which is
     * currently on-deck in the player, as well as any that were loaded into a player's hot-cue slot.
     */
    private final Map<DeckReference, WaveformDetail> detailHotCache = new ConcurrentHashMap<>();

    /**
     * Should we ask for details as well as the previews?
     */
    private final AtomicBoolean findDetails = new AtomicBoolean(true);

    /**
     * Set whether we should retrieve the waveform details in addition to the waveform previews.
     *
     * @param findDetails if {@code true}, both types of waveform will be retrieved, if {@code false} only previews
     *                    will be retrieved
     */
    @API(status = API.Status.STABLE)
    public final void setFindDetails(boolean findDetails) {
        this.findDetails.set(findDetails);
        if (findDetails) {
            primeCache();  // Get details for any tracks that were already loaded on players.
        } else {
            // Inform our listeners, on the proper thread, that the detailed waveforms are no longer available
            final Set<DeckReference> dyingCache = new HashSet<>(detailHotCache.keySet());
            detailHotCache.clear();
            SwingUtilities.invokeLater(() -> {
                for (DeckReference deck : dyingCache) {
                    deliverWaveformDetailUpdate(deck.player, null);
                }
            });
        }
    }

    /**
     * Check whether we are retrieving the waveform details in addition to the waveform previews.
     *
     * @return {@code true} if both types of waveform are being retrieved, {@code false} if only previews
     *         are being retrieved
     */
    @API(status = API.Status.STABLE)
    public final boolean isFindingDetails() {
        return findDetails.get();
    }

    /**
     * Keeps track of the waveform style that the user has specified they prefer to see. We may need to retrieve
     * other styles as well, either because the preferred style is not available, or because a listener has requested
     * another specific style, such as the {@link SignatureFinder}, which always needs RGB waveforms for track matching.
     */
    private final AtomicReference<WaveformStyle> preferredStyle = new AtomicReference<>(WaveformStyle.RGB);

    /**
     * Set the waveform style that the user prefers to see. We may need to retrieve other styles as well, either
     * because the preferred style is not available, or because a listener has requested another specific style,
     * such as the {@link SignatureFinder}, which always needs RGB waveforms for track matching.
     *
     * @param style the style of waveforms that will be retrieved by default if no listeners for other specific formats
     *              have been registered, and that will be delivered preferentially to listeners that do not specify a
     *              format
     */
    @API(status = API.Status.EXPERIMENTAL)
    public synchronized void setPreferredStyle(WaveformStyle style) {
        final WaveformStyle oldStyle = preferredStyle.getAndSet(style);
        if (oldStyle != style) {
            clearAllWaveforms();
            primeCache();
        }
    }

    /**
     * Check the waveform style that the user prefers to see. We may need to retrieve other styles as well, either
     * because the preferred style is not available, or because a listener has requested another specific style,
     * such as the {@link SignatureFinder}, which always needs RGB waveforms for track matching.
     *
     * @return the style of waveforms that will be retrieved by default if no listeners for other specific formats
     * have been registered, and that will be delivered preferentially to listeners that do not specify a
     * format
     */
    @API(status = API.Status.EXPERIMENTAL)
    public WaveformStyle getPreferredStyle() {
        return preferredStyle.get();
    }

    /**
     * <p>≈Set whether we should obtain color versions of waveforms and previews when they are available. Calling
     * with a {@code true} value is now the same as calling {@link #setPreferredStyle(WaveformStyle)} with the
     * value {@link WaveformStyle#RGB}, and calling this with {@code false} now translates to calling
     * {@link #setPreferredStyle(WaveformStyle)} with {@link WaveformStyle#BLUE}.</p>
     *
     * @param preferColor if {@code true}, the full-color versions of waveforms will be requested, if {@code false}
     *                   only the older blue versions will be retrieved
     *
     * @deprecated since 8.0.0
     */
    @API(status = API.Status.DEPRECATED)
    @Deprecated
    public final void setColorPreferred(boolean preferColor) {
        setPreferredStyle(preferColor? WaveformStyle.RGB : WaveformStyle.BLUE);
    }

    /**
     * Check whether we are retrieving color versions of waveforms and previews when they are available.
     * This is no longer entirely a meaningful question now that there are three supported waveform styles.
     *
     * @return {@code false} {@link WaveformStyle#BLUE} waveforms are being retrieved, {@code true} any other
     * style is preferred.
     *
     * @deprecated since 8.0.0
     */
    @API(status = API.Status.DEPRECATED)
    @Deprecated
    public final boolean isColorPreferred() {
        return preferredStyle.get() != WaveformStyle.BLUE;
    }

    /**
     * A queue used to hold metadata updates we receive from the {@link MetadataFinder} so we can process them on a
     * lower priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private final LinkedBlockingDeque<TrackMetadataUpdate> pendingUpdates = new LinkedBlockingDeque<>(100);

    /**
     * Our metadata listener just puts metadata updates on our queue, so we can process them on a lower
     * priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private final TrackMetadataListener metadataListener = update -> {
        logger.debug("Received metadata update {}", update);
        if (!pendingUpdates.offerLast(update)) {
            logger.warn("Discarding metadata update because our queue is backed up.");
        }
    };

    /**
     * Our mount listener evicts any cached waveforms that belong to media databases which have been unmounted, since
     * they are no longer valid.
     */
    private final MountListener mountListener = new MountListener() {
        @Override
        public void mediaMounted(SlotReference slot) {
            logger.debug("WaveformFinder doesn't yet need to do anything in response to a media mount.");
        }

        @Override
        public void mediaUnmounted(SlotReference slot) {
            // Iterate over a copy to avoid concurrent modification issues
            for (Map.Entry<DeckReference, WaveformPreview> entry : new HashMap<>(previewHotCache).entrySet()) {
                if (slot == SlotReference.getSlotReference(entry.getValue().dataReference)) {
                    logger.debug("Evicting cached waveform preview in response to unmount report {}", entry.getValue());
                    previewHotCache.remove(entry.getKey());
                }
            }

            // Again iterate over a copy to avoid concurrent modification issues
            for (Map.Entry<DeckReference, WaveformDetail> entry : new HashMap<>(detailHotCache).entrySet()) {
                if (slot == SlotReference.getSlotReference(entry.getValue().dataReference)) {
                    logger.debug("Evicting cached waveform detail in response to unmount report {}", entry.getValue());
                    detailHotCache.remove(entry.getKey());
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
            logger.debug("Currently nothing for WaveformFinder to do when devices appear.");
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            if (announcement.getDeviceNumber() == 25 && announcement.getDeviceName().equals("NXS-GW")) {
                logger.debug("Ignoring departure of Kuvo gateway, which fight each other and come and go constantly, especially in CDJ-3000s.");
                return;
            }
            logger.info("Clearing waveforms in response to the loss of a device, {}", announcement);
            clearWaveforms(announcement);
        }
    };

    /**
     * Keep track of whether we are running
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Check whether we are currently running. Unless the {@link MetadataFinder} is in passive mode, we will
     * automatically request waveforms from the appropriate player when a new track is loaded that is not found
     * in the hot cache.
     *
     * @return true if waveforms are being kept track of for all active players
     *
     * @see MetadataFinder#isPassive()
     */
    @API(status = API.Status.STABLE)
    public boolean isRunning() {
        return running.get();
    }

    /**
     * We process our player status updates on a separate thread so as not to slow down the high-priority update
     * delivery thread; we perform potentially slow I/O.
     */
    private Thread queueHandler;

    /**
     * We have received an update that invalidates the waveform preview for a player, so clear it and alert
     * any listeners if this represents a change. This does not affect the hot cues; they will stick around until the
     * player loads a new track that overwrites one or more of them.
     *
     * @param update the update which means we have no waveform preview for the associated player
     */
    private void clearDeckPreview(TrackMetadataUpdate update) {
        if (previewHotCache.remove(DeckReference.getDeckReference(update.player, 0)) != null) {
            deliverWaveformPreviewUpdate(update.player, null);
        }
    }

    /**
     * We have received an update that invalidates the waveform detail for a player, so clear it and alert
     * any listeners if this represents a change. This does not affect the hot cues; they will stick around until the
     * player loads a new track that overwrites one or more of them.
     *
     * @param update the update which means we have no waveform preview for the associated player
     */
    private void clearDeckDetail(TrackMetadataUpdate update) {
        if (detailHotCache.remove(DeckReference.getDeckReference(update.player, 0)) != null) {
            deliverWaveformDetailUpdate(update.player, null);
        }
    }

    /**
     * We have received an update that invalidates any previous metadata for a player, so clear its waveforms, and alert
     * any listeners if this represents a change. This does not affect the hot cues; they will stick around until the
     * player loads a new track that overwrites one or more of them.
     *
     * @param update the update which means we have no metadata for the associated player
     */
    private void clearDeck(TrackMetadataUpdate update) {
        clearDeckPreview(update);
        clearDeckDetail(update);
    }

    /**
     * We have received notification that a device is no longer on the network, so clear out all its waveforms.
     *
     * @param announcement the packet which reported the device’s disappearance
     */
    private void clearWaveforms(DeviceAnnouncement announcement) {
        final int player = announcement.getDeviceNumber();
        // Iterate over a copy to avoid concurrent modification issues
        for (DeckReference deck : new HashSet<>(previewHotCache.keySet())) {
            if (deck.player == player) {
                previewHotCache.remove(deck);
                if (deck.hotCue == 0) {
                    deliverWaveformPreviewUpdate(player, null);  // Inform listeners that preview is gone.
                }
            }
        }
        // Again iterate over a copy to avoid concurrent modification issues
        for (DeckReference deck : new HashSet<>(detailHotCache.keySet())) {
            if (deck.player == player) {
                detailHotCache.remove(deck);
                if (deck.hotCue == 0) {
                    deliverWaveformDetailUpdate(player, null);  // Inform listeners that detail is gone.
                }
            }
        }
    }

    /**
     * We have obtained a waveform preview for a device, so store it and alert any listeners.
     *
     * @param update the update which caused us to retrieve this waveform preview
     * @param preview the waveform preview which we retrieved
     */
    private void updatePreview(TrackMetadataUpdate update, WaveformPreview preview) {
        previewHotCache.put(DeckReference.getDeckReference(update.player, 0), preview);  // Main deck
        if (update.metadata.getCueList() != null) {  // Update the cache with any hot cues in this track as well
            for (CueList.Entry entry : update.metadata.getCueList().entries) {
                if (entry.hotCueNumber != 0) {
                    previewHotCache.put(DeckReference.getDeckReference(update.player, entry.hotCueNumber), preview);
                }
            }
        }
        deliverWaveformPreviewUpdate(update.player, preview);
    }

    /**
     * We have obtained waveform detail for a device, so store it and alert any listeners.
     *
     * @param update the update which caused us to retrieve this waveform detail
     * @param detail the waveform detail which we retrieved
     */
    private void updateDetail(TrackMetadataUpdate update, WaveformDetail detail) {
        detailHotCache.put(DeckReference.getDeckReference(update.player, 0), detail);  // Main deck
        if (update.metadata.getCueList() != null) {  // Update the cache with any hot cues in this track as well
            for (CueList.Entry entry : update.metadata.getCueList().entries) {
                if (entry.hotCueNumber != 0) {
                    detailHotCache.put(DeckReference.getDeckReference(update.player, entry.hotCueNumber), detail);
                }
            }
        }
        deliverWaveformDetailUpdate(update.player, detail);
    }

    /**
     * Get the waveform previews available for all tracks currently loaded in any player, either on the play deck, or
     * in a hot cue.
     *
     * @return the previews associated with all current players, including for any tracks loaded in their hot cue slots
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    @API(status = API.Status.STABLE)
    public Map<DeckReference, WaveformPreview> getLoadedPreviews() {
        ensureRunning();
        // Make a copy so callers get an immutable snapshot of the current state.
        return Map.copyOf(previewHotCache);
    }

    /**
     * Get the waveform details available for all tracks currently loaded in any player, either on the play deck, or
     * in a hot cue.
     *
     * @return the details associated with all current players, including for any tracks loaded in their hot cue slots
     *
     * @throws IllegalStateException if the WaveformFinder is not running or requesting waveform details
     */
    @API(status = API.Status.STABLE)
    public Map<DeckReference, WaveformDetail> getLoadedDetails() {
        ensureRunning();
        if (!isFindingDetails()) {
            throw new IllegalStateException("WaveformFinder is not configured to find waveform details.");
        }
        // Make a copy so callers get an immutable snapshot of the current state.
        return Map.copyOf(detailHotCache);
    }

    /**
     * Look up the waveform preview we have for the track loaded in the main deck of a given player number.
     *
     * @param player the device number whose waveform preview for the playing track is desired
     *
     * @return the waveform preview for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    @API(status = API.Status.STABLE)
    public WaveformPreview getLatestPreviewFor(int player) {
        ensureRunning();
        return previewHotCache.get(DeckReference.getDeckReference(player, 0));
    }

    /**
     * Look up the waveform preview we have for a given player, identified by a status update received from that player.
     *
     * @param update a status update from the player for which a waveform preview is desired
     *
     * @return the waveform preview for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    @API(status = API.Status.STABLE)
    public WaveformPreview getLatestPreviewFor(DeviceUpdate update) {
        return getLatestPreviewFor(update.getDeviceNumber());
    }

    /**
     * Look up the waveform detail we have for the track loaded in the main deck of a given player number.
     *
     * @param player the device number whose waveform detail for the playing track is desired
     *
     * @return the waveform detail for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    @API(status = API.Status.STABLE)
    public WaveformDetail getLatestDetailFor(int player) {
        ensureRunning();
        return detailHotCache.get(DeckReference.getDeckReference(player, 0));
    }

    /**
     * Look up the waveform detail we have for a given player, identified by a status update received from that player.
     *
     * @param update a status update from the player for which waveform detail is desired
     *
     * @return the waveform detail for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    @API(status = API.Status.STABLE)
    public WaveformDetail getLatestDetailFor(DeviceUpdate update) {
        return getLatestDetailFor(update.getDeviceNumber());
    }

    /**
     * Ask the specified player for the waveform preview in the specified slot with the specified rekordbox ID,
     * using downloaded media instead if it is available, and possibly giving up if we are in passive mode.
     *
     * @param trackReference uniquely identifies the desired waveform preview
     * @param fromUpdate if not {@code null} this is an automatic update in response to a track change, so we
     *                   should only try a dbserver query if we are not in passive mode.
     *
     * @return the waveform preview found, if any
     */
    private WaveformPreview requestPreviewInternal(final DataReference trackReference, final TrackMetadataUpdate fromUpdate) {

        // First see if any registered metadata providers can offer it for us.
        final MediaDetails sourceDetails = MetadataFinder.getInstance().getMediaDetailsFor(trackReference.getSlotReference());
        if (sourceDetails !=  null) {
            final WaveformPreview provided = MetadataFinder.getInstance().allMetadataProviders.getWaveformPreview(sourceDetails, trackReference);
            if (provided != null) {
                return provided;
            }
        }

        // At this point, unless we are allowed to actively request the data, we are done. We can always actively
        // request tracks from rekordbox.
        if (MetadataFinder.getInstance().isPassive() && fromUpdate != null && trackReference.slot != CdjStatus.TrackSourceSlot.COLLECTION) {
            return null;
        }

        // We have to actually request the preview using the dbserver protocol.
        ConnectionManager.ClientTask<WaveformPreview> task =
                client -> getWaveformPreview(trackReference.rekordboxId, SlotReference.getSlotReference(trackReference), trackReference.trackType, fromUpdate, client);

        try {
            return ConnectionManager.getInstance().invokeWithClientSession(trackReference.player, task, "requesting waveform preview");
        } catch (Exception e) {
            logger.error("Problem requesting waveform preview, returning null", e);
        }
        return null;
    }

    /**
     * Ask the specified player for the specified waveform preview from the specified media slot, first checking if we
     * have a cached copy.
     *
     * @param dataReference uniquely identifies the desired waveform preview
     *
     * @return the preview, if it was found, or {@code null}
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    @API(status = API.Status.STABLE)
    public WaveformPreview requestWaveformPreviewFrom(final DataReference dataReference) {
        ensureRunning();
        for (WaveformPreview cached : previewHotCache.values()) {
            if (cached.dataReference.equals(dataReference)) {  // Found a hot cue hit, use it.
                return cached;
            }
        }
        return requestPreviewInternal(dataReference, null);
    }

    /**
     * How long we are willing to wait, in nanoseconds, for a CDJ-3000 to analyze a non-rekordbox track before we give up on retrying.
     */
    static final long MAXIMUM_ANALYSIS_WAIT = TimeUnit.SECONDS.toNanos(90);

    /**
     * How long we should wait, in milliseconds, after a failed/partial response during CDJ-3000 analysis to try again.
     */
    static final long ANALYSIS_UPDATE_INTERVAL = TimeUnit.SECONDS.toMillis(10);

    /**
     * Keeps track of whether we have a retry thread already in progress, so we never have more than one.
     */
    private final AtomicBoolean retrying = new AtomicBoolean(false);

    /**
     * Helper method to check if we should retry a request because it seems the player may still be in the process
     * of analyzing an unanalyzed track, and to produce an informative log message about the decision.
     *
     * @param fromUpdate    the metadata update that led to this attempt, or {@code null} if it was an explicit request via our API
     * @param retryFlag     the flag that is being used to record that the finder class has already scheduled a retry attempt
     * @param retryListener the listener to which the metadata update should be re-delivered when it is time to retry
     * @param description   the text to use in log messages describing the data we are retrying to obtain
     *
     * @return {@code true} if we have kicked off the process of waiting and trying again
     */
    static boolean retryUnanalyzedTrack(TrackMetadataUpdate fromUpdate, AtomicBoolean retryFlag, TrackMetadataListener retryListener, String description) {
        if (fromUpdate == null || fromUpdate.metadata.trackType != CdjStatus.TrackType.UNANALYZED) {
            logger.info("retryUnanalyzedTrack bailing for {}, fromUpdate: {}", description, fromUpdate);
            return false;
        }

        final TrackMetadata currentMetadata = MetadataFinder.getInstance().getLatestMetadataFor(fromUpdate.player);

        if (currentMetadata == null || !currentMetadata.equals(fromUpdate.metadata)) {
            logger.info("Track changed while waiting for player {} to analyze {} for track {} in slot {}, current metadata: {}, giving up.", fromUpdate.player,
                    description, fromUpdate.metadata.trackReference.rekordboxId, fromUpdate.metadata.trackReference.slot, currentMetadata);
            return false;
        }

        if (System.nanoTime() - fromUpdate.metadata.timestamp > MAXIMUM_ANALYSIS_WAIT) {
            logger.warn("Waited too long for player {} to analyze {} for track {} in slot {}, giving up.", fromUpdate.player,
                    description, fromUpdate.metadata.trackReference.rekordboxId, fromUpdate.metadata.trackReference.slot);
            return false;
        }

        logger.info("Did not find full {} data yet, still waiting for player {} to analyze track {} in slot {}.", description, fromUpdate.player,
                fromUpdate.metadata.trackReference.rekordboxId, fromUpdate.metadata.trackReference.slot);
        if (retryFlag.compareAndSet(false, true)) {
            new Thread(() -> {
                try {
                    Thread.sleep(ANALYSIS_UPDATE_INTERVAL);
                } catch (InterruptedException e) {
                    logger.info("interrupted while waiting to retry loading {} data for unanalyzed track {}", description, currentMetadata);
                }
                retryFlag.set(false);
                if (currentMetadata.equals(MetadataFinder.getInstance().getLatestMetadataFor(fromUpdate.player))) {
                    try {
                        logger.info("Retrying {} requests for unanalyzed track in player {}.", description, fromUpdate.player);
                        retryListener.metadataChanged(fromUpdate);
                    } catch (Throwable t) {
                        logger.error("Problem processing retry for {} data of unanalyzed track {}", description, fromUpdate, t);
                    }
                }
            }, "Unanalyzed " + description + " data request retry").start();
        }
        return true;
    }

    /**
     * Requests the waveform preview for a specific track ID, given a connection to a player that has already been
     * set up.
     *
     * @param rekordboxId the track whose waveform preview is desired
     * @param slot identifies the media slot we are querying
     * @param trackType the type of track involved (since we can request metadata for unanalyzed tracks from CDJ-3000s)
     * @param fromUpdate if not {@code null} this is an automatic update in response to a track change, so we
     *                   should only try a dbserver query if we are not in passive mode.
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved waveform preview, or {@code null} if none was available
     * @throws IOException if there is a communication problem
     */
    WaveformPreview getWaveformPreview(int rekordboxId, SlotReference slot, CdjStatus.TrackType trackType, TrackMetadataUpdate fromUpdate, Client client)
            throws IOException {

        final NumberField idField = new NumberField(rekordboxId);

        // First try to get the NXS2-style color waveform if we are supposed to.
        if (getPreferredStyle() == WaveformStyle.RGB) {
            try {
                Message response = client.simpleRequest(Message.KnownType.ANLZ_TAG_REQ, Message.KnownType.ANLZ_TAG,
                        client.buildRMST(Message.MenuIdentifier.MAIN_MENU, slot.slot, trackType), idField,
                        new NumberField(Message.ANLZ_FILE_TAG_COLOR_WAVEFORM_PREVIEW), new NumberField(Message.ALNZ_FILE_TYPE_EXT));
                if (response.knownType != Message.KnownType.UNAVAILABLE && response.arguments.get(3).getSize() > 0) {
                    return new WaveformPreview(new DataReference(slot, rekordboxId, trackType), response, WaveformStyle.RGB);
                } else {
                    if (retryUnanalyzedTrack(fromUpdate, retrying, metadataListener, "waveform")) {
                        return null;  // We will hopefully get the data from the retry that has been queued.
                    }
                    logger.info("No color waveform preview available for slot {}, id {}; requesting blue version.", slot, rekordboxId);
                }
            } catch (Exception e) {
                if (retryUnanalyzedTrack(fromUpdate, retrying, metadataListener, "waveform")) {
                    return null;  // We will hopefully get the data from the retry that has been queued.
                }
                logger.info("No color waveform preview available for slot {}, id {}; requesting blue version.", slot, rekordboxId, e);
            }
        } else if (getPreferredStyle() == WaveformStyle.THREE_BAND) {
            // Or try to get the CDJ-3000-style 3-band waveform if we are supposed to.
            try {
                Message response = client.simpleRequest(Message.KnownType.ANLZ_TAG_REQ, Message.KnownType.ANLZ_TAG,
                        client.buildRMST(Message.MenuIdentifier.MAIN_MENU, slot.slot, trackType), idField,
                        new NumberField(Message.ANLZ_FILE_TAG_3BAND_WAVEFORM_PREVIEW), new NumberField(Message.ALNZ_FILE_TYPE_2EX));
                if (response.knownType != Message.KnownType.UNAVAILABLE && response.arguments.get(3).getSize() > 0) {
                    return new WaveformPreview(new DataReference(slot, rekordboxId, trackType), response, WaveformStyle.THREE_BAND);
                } else {
                    if (retryUnanalyzedTrack(fromUpdate, retrying, metadataListener, "waveform")) {
                        return null;  // We will hopefully get the data from the retry that has been queued.
                    }
                    logger.info("No 3-band waveform preview available for slot {}, id {}; requesting blue version.", slot, rekordboxId);
                }
            } catch (Exception e) {
                if (retryUnanalyzedTrack(fromUpdate, retrying, metadataListener, "waveform")) {
                    return null;  // We will hopefully get the data from the retry that has been queued.
                }
                logger.info("No 3-band waveform preview available for slot {}, id {}; requesting blue version.", slot, rekordboxId, e);
            }
        }

        Message response = client.simpleRequest(Message.KnownType.WAVE_PREVIEW_REQ, Message.KnownType.WAVE_PREVIEW,
                client.buildRMST(Message.MenuIdentifier.DATA, slot.slot, trackType), NumberField.WORD_1,
                idField, NumberField.WORD_0);
        if (response.knownType != Message.KnownType.UNAVAILABLE && response.arguments.get(3).getSize() > 0) {
            return new WaveformPreview(new DataReference(slot, rekordboxId, trackType), response, WaveformStyle.BLUE);
        }
        retryUnanalyzedTrack(fromUpdate, retrying, metadataListener, "waveform");
        return null;
    }

    /**
     * Ask the specified player for the waveform detail in the specified slot with the specified rekordbox ID,
     * using downloaded media instead if it is available, and possibly giving up if we are in passive mode.
     *
     * @param trackReference uniquely identifies the desired waveform detail
     * @param fromUpdate if not {@code null} this is an automatic update in response to a track change, so we
     *                   should only try a dbserver query if we are not in passive mode.
     *
     * @return the waveform preview found, if any
     */
    private WaveformDetail requestDetailInternal(final DataReference trackReference, final TrackMetadataUpdate fromUpdate) {

        // First see if any registered metadata providers can offer it to us.
        final MediaDetails sourceDetails = MetadataFinder.getInstance().getMediaDetailsFor(trackReference.getSlotReference());
        if (sourceDetails !=  null) {
            final WaveformDetail provided = MetadataFinder.getInstance().allMetadataProviders.getWaveformDetail(sourceDetails, trackReference);
            if (provided != null) {
                return provided;
            }
        }

        // At this point, unless we are allowed to actively request the data, we are done. We can always actively
        // request tracks from rekordbox.
        if (MetadataFinder.getInstance().isPassive() && fromUpdate != null && trackReference.slot != CdjStatus.TrackSourceSlot.COLLECTION) {
            return null;
        }

        // We have to actually request the detail using the dbserver protocol.
        ConnectionManager.ClientTask<WaveformDetail> task =
                client -> getWaveformDetail(trackReference.rekordboxId, SlotReference.getSlotReference(trackReference), trackReference.trackType, fromUpdate, client);

        try {
            return ConnectionManager.getInstance().invokeWithClientSession(trackReference.player, task, "requesting waveform detail");
        } catch (Exception e) {
            logger.error("Problem requesting waveform preview, returning null", e);
        }
        return null;
    }

    /**
     * Ask the specified player for the specified waveform detail from the specified media slot, first checking if we
     * have a cached copy.
     *
     * @param dataReference uniquely identifies the desired waveform detail
     *
     * @return the waveform detail, if it was found, or {@code null}
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    @API(status = API.Status.STABLE)
    public WaveformDetail requestWaveformDetailFrom(final DataReference dataReference) {
        ensureRunning();
        for (WaveformDetail cached : detailHotCache.values()) {
            if (cached.dataReference.equals(dataReference)) {  // Found a hot cue hit, use it.
                return cached;
            }
        }
        return requestDetailInternal(dataReference, null);
    }

    /**
     * Requests the waveform detail for a specific track ID, given a connection to a player that has already been
     * set up.
     *
     * @param rekordboxId the track whose waveform detail is desired
     * @param slot identifies the media slot we are querying
     * @param trackType the type of track involved (since we can request metadata for unanalyzed tracks from CDJ-3000s)
     * @param fromUpdate if not {@code null} this is an automatic update in response to a track change, so we
     *                   should only try a dbserver query if we are not in passive mode.
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved waveform detail, or {@code null} if none was available
     * @throws IOException if there is a communication problem
     */
    WaveformDetail getWaveformDetail(int rekordboxId, SlotReference slot, CdjStatus.TrackType trackType, TrackMetadataUpdate fromUpdate, Client client)
            throws IOException {
        final NumberField idField = new NumberField(rekordboxId);

        // First try to get the NXS2-style color waveform if we are supposed to.
        if (preferredStyle.get() == WaveformStyle.RGB) {
            try {
                Message response = client.simpleRequest(Message.KnownType.ANLZ_TAG_REQ, Message.KnownType.ANLZ_TAG,
                        client.buildRMST(Message.MenuIdentifier.MAIN_MENU, slot.slot, trackType), idField,
                        new NumberField(Message.ANLZ_FILE_TAG_COLOR_WAVEFORM_DETAIL), new NumberField(Message.ALNZ_FILE_TYPE_EXT));
                if (response.knownType != Message.KnownType.UNAVAILABLE && response.arguments.get(3).getSize() > 0) {
                    return new WaveformDetail(new DataReference(slot, rekordboxId, trackType), response, WaveformStyle.RGB);
                } else {
                    if (retryUnanalyzedTrack(fromUpdate, retrying, metadataListener, "waveform")) {
                        return null;  // We will hopefully get the data from the retry that has been queued.
                    }
                    logger.info("No color waveform detail available for slot {}, id {}; requesting blue version.", slot, rekordboxId);
                }
            } catch (Exception e) {
                if (retryUnanalyzedTrack(fromUpdate, retrying, metadataListener, "waveform")) {
                    return null;  // We will hopefully get the data from the retry that has been queued.
                }
                logger.info("Problem requesting color waveform detail for slot {}, id {}; requesting blue version.", slot, rekordboxId, e);
            }
        } else if (preferredStyle.get() == WaveformStyle.THREE_BAND) {
            // Or try to get the CDJ-3000-style 3-band waveform if we are supposed to.
            try {
                Message response = client.simpleRequest(Message.KnownType.ANLZ_TAG_REQ, Message.KnownType.ANLZ_TAG,
                        client.buildRMST(Message.MenuIdentifier.MAIN_MENU, slot.slot, trackType), idField,
                        new NumberField(Message.ANLZ_FILE_TAG_3BAND_WAVEFORM_DETAIL), new NumberField(Message.ALNZ_FILE_TYPE_2EX));
                if (response.knownType != Message.KnownType.UNAVAILABLE && response.arguments.get(3).getSize() > 0) {
                    return new WaveformDetail(new DataReference(slot, rekordboxId, trackType), response, WaveformStyle.THREE_BAND);
                } else {
                    if (retryUnanalyzedTrack(fromUpdate, retrying, metadataListener, "waveform")) {
                        return null;  // We will hopefully get the data from the retry that has been queued.
                    }
                    logger.info("No 3-band waveform detail available for slot {}, id {}; requesting blue version.", slot, rekordboxId);
                }
            } catch (Exception e) {
                if (retryUnanalyzedTrack(fromUpdate, retrying, metadataListener, "waveform")) {
                    return null;  // We will hopefully get the data from the retry that has been queued.
                }
                logger.info("Problem requesting 3-band waveform detail for slot {}, id {}; requesting blue version.", slot, rekordboxId, e);
            }
        }

        Message response = client.simpleRequest(Message.KnownType.WAVE_DETAIL_REQ, Message.KnownType.WAVE_DETAIL,
                client.buildRMST(Message.MenuIdentifier.MAIN_MENU, slot.slot, trackType), idField, NumberField.WORD_0);
        if (response.knownType != Message.KnownType.UNAVAILABLE && response.arguments.get(3).getSize() > 0) {
            return new WaveformDetail(new DataReference(slot, rekordboxId, trackType), response, WaveformStyle.BLUE);
        }
        retryUnanalyzedTrack(fromUpdate, retrying, metadataListener, "waveform");
        return null;
    }

    /**
     * Keep track of the devices we are currently trying to get previews from in response to metadata updates.
     */
    private final Set<Integer> activePreviewRequests = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Keep track of the devices we are currently trying to get details from in response to metadata updates.
     */
    private final Set<Integer> activeDetailRequests = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Keeps track of the registered waveform listeners.
     */
    private final Set<WaveformListener> waveformListeners = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * <p>Adds the specified waveform listener to receive updates when the waveform information for a player changes.
     * Waveforms are delivered in the format specified by {@link #getPreferredStyle()}, and if that is changed,
     * updated versions in the new preferred style are sent to the listeners.
     * If {@code listener} is {@code null} or already present in the set of registered listeners, no exception is
     * thrown and no action is performed.</p>
     *
     * <p>Updates are delivered to listeners on the Swing Event Dispatch thread, so it is safe to interact with
     * user interface elements within the event handler.</p>
     *
     * <p>Even so, any code in the listener method <em>must</em> finish quickly, or it will freeze the user interface,
     * add latency for other listeners, and updates will back up. If you want to perform lengthy processing of any sort,
     * do so on another thread.</p>
     *
     * @param listener the waveform update listener to add
     */
    @API(status = API.Status.STABLE)
    public void addWaveformListener(WaveformListener listener) {
        if (listener != null) {
            waveformListeners.add(listener);
        }
    }

    /**
     * Removes the specified waveform listener so that it no longer receives updates when the
     * waveform information for a player changes. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the waveform update listener to remove
     */
    @API(status = API.Status.STABLE)
    public void removeWaveformListener(WaveformListener listener) {
        if (listener != null) {
            waveformListeners.remove(listener);
        }
    }

    /**
     * Get the set of currently-registered waveform listeners.
     *
     * @return the listeners that are currently registered for waveform updates
     */
    @API(status = API.Status.STABLE)
    public Set<WaveformListener> getWaveformListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Set.copyOf(waveformListeners);
    }

    /**
     * Send a waveform preview update announcement to all registered listeners.
     *
     * @param player the player whose waveform preview has changed
     * @param preview the new waveform preview, if any
     */
    private void deliverWaveformPreviewUpdate(final int player, final WaveformPreview preview) {
        final Set<WaveformListener> listeners = getWaveformListeners();
        if (!listeners.isEmpty()) {
            SwingUtilities.invokeLater(() -> {
                final WaveformPreviewUpdate update = new WaveformPreviewUpdate(player, preview);
                for (final WaveformListener listener : listeners) {
                    try {
                        listener.previewChanged(update);

                    } catch (Throwable t) {
                        logger.warn("Problem delivering waveform preview update to listener", t);
                    }
                }
            });
        }
    }

    /**
     * Send a waveform detail update announcement to all registered listeners.
     *
     * @param player the player whose waveform detail has changed
     * @param detail the new waveform detail, if any
     */
    private void deliverWaveformDetailUpdate(final int player, final WaveformDetail detail) {
        if (!getWaveformListeners().isEmpty()) {
            SwingUtilities.invokeLater(() -> {
                final WaveformDetailUpdate update = new WaveformDetailUpdate(player, detail);
                for (final WaveformListener listener : getWaveformListeners()) {
                    try {
                        listener.detailChanged(update);

                    } catch (Throwable t) {
                        logger.warn("Problem delivering waveform detail update to listener", t);
                    }
                }
            });
        }
    }

    /**
     * Process a metadata update from the {@link MetadataFinder}, and see if it means the waveform information
     * associated with any player has changed.
     *
     * @param update describes the new metadata we have for a player, if any
     */
    private void handleUpdate(final TrackMetadataUpdate update) {
        boolean foundInCache = false;
        if (update.metadata == null) {
            clearDeck(update);
        } else {
            // We can offer waveform information for this device; check if we've already looked it up. First, preview:
            final WaveformPreview lastPreview = previewHotCache.get(DeckReference.getDeckReference(update.player, 0));
            if (lastPreview == null || !lastPreview.dataReference.equals(update.metadata.trackReference) || update.metadata.trackType == CdjStatus.TrackType.UNANALYZED) {  // We may have something new!

                // First see if we can find the new preview in the hot cache
                for (Map.Entry<DeckReference, WaveformPreview> cached : previewHotCache.entrySet()) {
                    if (cached.getKey().hotCue != 0 &&  cached.getValue().dataReference.equals(update.metadata.trackReference)) {  // Found a hot cue hit, use it.
                        updatePreview(update, cached.getValue());
                        foundInCache = true;
                        break;
                    }
                }

                // If not found in the cache, try actually retrieving it unless that is already in progress.
                if (!foundInCache && activePreviewRequests.add(update.player)) {
                    clearDeckPreview(update);  // We won't know what it is until our request completes.
                    new Thread(() -> {
                        try {
                            WaveformPreview preview = requestPreviewInternal(update.metadata.trackReference, update);
                            if (preview != null) {
                                updatePreview(update, preview);
                                if (!preview.equals(lastPreview)) {
                                    retryUnanalyzedTrack(update, retrying, metadataListener, "waveform");  // The preview is still changing, so retry for more.
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("Problem requesting waveform preview from update {}", update, e);
                        } finally {
                            activePreviewRequests.remove(update.player);
                        }
                    }).start();
                }
            }

            // Secondly, the detail.
            foundInCache = false;
            final WaveformDetail lastDetail = detailHotCache.get(DeckReference.getDeckReference(update.player, 0));
            if (isFindingDetails() && (lastDetail == null || !lastDetail.dataReference.equals(update.metadata.trackReference) ||
                    update.metadata.trackType == CdjStatus.TrackType.UNANALYZED)) {  // We may have something new!

                // First see if we can find the new detailed waveform in the hot cache
                for (Map.Entry<DeckReference,WaveformDetail> cached : detailHotCache.entrySet()) {
                    if (cached.getKey().hotCue != 0 && cached.getValue().dataReference.equals(update.metadata.trackReference)) {  // Found a hot cue hit, use it.
                        updateDetail(update, cached.getValue());
                        foundInCache = true;
                        break;
                    }
                }

                // If not found in the cache try actually retrieving it, unless that is already in progress.
                if (!foundInCache && activeDetailRequests.add(update.player)) {
                    clearDeckDetail(update);  // We won't know what it is until our request completes.
                    new Thread(() -> {
                        try {
                            WaveformDetail detail = requestDetailInternal(update.metadata.trackReference, update);
                            if (detail != null) {
                                updateDetail(update, detail);
                                if (!detail.equals(lastDetail)) {
                                    retryUnanalyzedTrack(update, retrying, metadataListener, "waveform");  // The detail is still changing, so retry for more.
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("Problem requesting waveform detail from update {}", update, e);
                        } finally {
                            activeDetailRequests.remove(update.player);
                        }
                    }).start();
                }
            }
        }
    }

    /**
     * Set up to automatically stop if anything we depend on stops.
     */
    private final LifecycleListener lifecycleListener = new LifecycleListener() {
        @Override
        public void started(LifecycleParticipant sender) {
            logger.debug("The WaveformFinder does not auto-start when {} does.", sender);
        }

        @Override
        public void stopped(LifecycleParticipant sender) {
            if (isRunning()) {
                logger.info("WaveformFinder stopping because {} has.", sender);
                stop();
            }
        }
    };

    /**
     * Send ourselves "updates" about any tracks that were loaded before we started, or before we were requesting
     * details, since we missed them.
     */
    private void primeCache() {
        SwingUtilities.invokeLater(() -> {
            if (isRunning()) {
                for (Map.Entry<DeckReference, TrackMetadata> entry : MetadataFinder.getInstance().getLoadedTracks().entrySet()) {
                    if (entry.getKey().hotCue == 0) {  // The track is currently loaded in a main player deck
                        handleUpdate(new TrackMetadataUpdate(entry.getKey().player, entry.getValue()));
                    }
                }
            }
        });
    }

    /**
     * <p>Start finding waveforms for all active players. Starts the {@link MetadataFinder} if it is not already
     * running, because we need it to send us metadata updates to notice when new tracks are loaded. This in turn
     * starts the {@link DeviceFinder}, so we can keep track of the comings and goings of players themselves.
     * We also start the {@link ConnectionManager} in order to make queries to obtain waveforms.</p>
     *
     * @throws Exception if there is a problem starting the required components
     */
    @API(status = API.Status.STABLE)
    public synchronized void start() throws Exception {
        if (!isRunning()) {
            ConnectionManager.getInstance().addLifecycleListener(lifecycleListener);
            ConnectionManager.getInstance().start();
            DeviceFinder.getInstance().addDeviceAnnouncementListener(announcementListener);
            MetadataFinder.getInstance().addLifecycleListener(lifecycleListener);
            MetadataFinder.getInstance().start();
            MetadataFinder.getInstance().addTrackMetadataListener(metadataListener);
            MetadataFinder.getInstance().addMountListener(mountListener);
            queueHandler = new Thread(() -> {
                while (isRunning()) {
                    try {
                        handleUpdate(pendingUpdates.take());
                    } catch (InterruptedException e) {
                        // Interrupted due to MetadataFinder shutdown, presumably
                    } catch (Throwable t) {
                        logger.error("Problem processing metadata update", t);
                    }
                }
            });
            running.set(true);
            queueHandler.start();
            deliverLifecycleAnnouncement(logger, true);
            primeCache();
        }
    }

    /**
     * Stop finding waveforms for all active players.
     */
    @API(status = API.Status.STABLE)
    public synchronized void stop() {
        if (isRunning()) {
            MetadataFinder.getInstance().removeTrackMetadataListener(metadataListener);
            running.set(false);
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;

            clearAllWaveforms();
            deliverLifecycleAnnouncement(logger, false);
        }
    }

    /**
     * Report the loss of all waveforms, on the proper thread, outside any lock.
     */
    private void clearAllWaveforms() {
        final Set<DeckReference> dyingPreviewCache = new HashSet<>(previewHotCache.keySet());
        previewHotCache.clear();
        final Set<DeckReference> dyingDetailCache = new HashSet<>(detailHotCache.keySet());
        detailHotCache.clear();
        SwingUtilities.invokeLater(() -> {
            for (DeckReference deck : dyingPreviewCache) {  // Report the loss of our previews.
                if (deck.hotCue == 0) {
                    deliverWaveformPreviewUpdate(deck.player, null);
                }
            }
            for (DeckReference deck : dyingDetailCache) {  // Report the loss of our details.
                if (deck.hotCue == 0) {
                    deliverWaveformDetailUpdate(deck.player, null);
                }
            }
        });
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final WaveformFinder ourInstance = new WaveformFinder();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    @API(status = API.Status.STABLE)
    public static WaveformFinder getInstance() {
        return ourInstance;
    }

    /**
     * Prevent direct instantiation.
     */
    private WaveformFinder() {
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WaveformFinder[running:").append(isRunning()).append(", passive:");
        sb.append(MetadataFinder.getInstance().isPassive()).append(", findingDetails:").append(isFindingDetails());
        if (isRunning()) {
            sb.append(", loadedPreviews:").append(getLoadedPreviews());
            if (isFindingDetails()) {
                sb.append(", loadedDetails:").append(getLoadedDetails());
            }
        }
        return sb.append("]").toString();
    }
}
