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
    private final Map<DeckReference, Map<WaveformStyle, WaveformPreview>> previewHotCache = new ConcurrentHashMap<>();

    /**
     * Keeps track of the current waveform details cached for each player. We hot cache data for any track which is
     * currently on-deck in the player, as well as any that were loaded into a player's hot-cue slot.
     */
    private final Map<DeckReference, Map<WaveformStyle,WaveformDetail>> detailHotCache = new ConcurrentHashMap<>();

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
            for (Map.Entry<DeckReference, Map<WaveformStyle, WaveformPreview>> entry : new HashMap<>(previewHotCache).entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    final WaveformPreview somePreview = entry.getValue().values().iterator().next();  // We don't care about the format.
                    if (slot == SlotReference.getSlotReference(somePreview.dataReference)) {
                        logger.debug("Evicting cached waveform preview in response to unmount report {}", entry.getValue());
                        previewHotCache.remove(entry.getKey());
                    }
                }
            }

            // Again iterate over a copy to avoid concurrent modification issues
            for (Map.Entry<DeckReference, Map<WaveformStyle, WaveformDetail>> entry : new HashMap<>(detailHotCache).entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    final WaveformDetail someDetail = entry.getValue().values().iterator().next();  // We don't care about the format.
                    if (slot == SlotReference.getSlotReference(someDetail.dataReference)) {
                        logger.debug("Evicting cached waveform detail in response to unmount report {}", entry.getValue());
                        detailHotCache.remove(entry.getKey());
                    }
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
        Map<WaveformStyle,WaveformPreview> mainDeckMap = previewHotCache.computeIfAbsent(DeckReference.getDeckReference(update.player, 0),
                k -> new ConcurrentHashMap<>());
        mainDeckMap.put(preview.style, preview);

        if (update.metadata.getCueList() != null) {  // Update the cache with any hot cues in this track as well
            for (CueList.Entry entry : update.metadata.getCueList().entries) {
                if (entry.hotCueNumber != 0) {
                    final Map<WaveformStyle,WaveformPreview> hotCueMap = previewHotCache.computeIfAbsent(DeckReference.getDeckReference(update.player, entry.hotCueNumber),
                            k -> new ConcurrentHashMap<>());
                    hotCueMap.put(preview.style, preview);
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
        Map<WaveformStyle,WaveformDetail> newMap = new ConcurrentHashMap<>();
        Map<WaveformStyle,WaveformDetail> mainDeckMap = detailHotCache.put(DeckReference.getDeckReference(update.player, 0), newMap);
        if (mainDeckMap == null) {
            mainDeckMap = newMap;  // We just created it.
        }
        mainDeckMap.put(detail.style, detail);

        if (update.metadata.getCueList() != null) {  // Update the cache with any hot cues in this track as well
            for (CueList.Entry entry : update.metadata.getCueList().entries) {
                if (entry.hotCueNumber != 0) {
                    final Map<WaveformStyle,WaveformDetail> hotCueMap = detailHotCache.computeIfAbsent(DeckReference.getDeckReference(update.player, entry.hotCueNumber),
                            k -> new ConcurrentHashMap<>());
                    hotCueMap.put(detail.style, detail);
                }
            }
        }

        deliverWaveformDetailUpdate(update.player, detail);
    }

    /**
     * Determine which available waveform style is the closest available to a given preferred style.
     *
     * @param preferred the style that is desired
     * @param available the styles that are available
     * @return the closest available acceptable match for the desired style (or {@code null} if there is none)
     */
    public WaveformStyle bestMatch(WaveformStyle preferred, Set<WaveformStyle> available) {
        if (available.contains(preferred)) {
            return preferred;  // Best case, we can give them what they are asking for.
        }
        switch (preferred) {
            case BLUE: return null;  // Nothing suitable is available.
            case RGB: return bestMatch(WaveformStyle.BLUE, available);  // Blue is the fallback for RGB.
            case THREE_BAND: return bestMatch(WaveformStyle.RGB, available);  // RGB and then blue can fall back for 3-band.
        }

        throw new IllegalArgumentException("Unrecognized preferred waveform style: " + preferred);
    }

    /**
     * Get the waveform previews available for all tracks currently loaded in any player, either on the playback deck, or
     * in a hot cue. If multiple styles of preview are available for any slot, the best match for {@link #getPreferredStyle()}
     * will be chosen.
     *
     * @return the previews associated with all current players, including for any tracks loaded in their hot cue slots
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    @API(status = API.Status.STABLE)
    public Map<DeckReference, WaveformPreview> getLoadedPreviews() {
        ensureRunning();

        Map<DeckReference, WaveformPreview> result = new HashMap<>();
        for (final DeckReference deck : previewHotCache.keySet()) {
            final Map<WaveformStyle, WaveformPreview> previews = previewHotCache.get(deck);
            final WaveformStyle bestStyle = bestMatch(getPreferredStyle(), previews.keySet());
            result.put(deck, previews.get(bestStyle));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Get the waveform details available for all tracks currently loaded in any player, either on the playback deck, or
     * in a hot cue. If multiple styles of preview are available for any slot, the best match for {@link #getPreferredStyle()}
     * will be chosen.
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

        Map<DeckReference, WaveformDetail> result = new HashMap<>();
        for (final DeckReference deck : detailHotCache.keySet()) {
            final Map<WaveformStyle, WaveformDetail> details = detailHotCache.get(deck);
            final WaveformStyle bestStyle = bestMatch(getPreferredStyle(), details.keySet());
            result.put(deck, details.get(bestStyle));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Look up the waveform preview we have for the track loaded in the main deck of a given player number.
     * If multiple styles of waveform are available, the best match for {@link #getPreferredStyle()} will be chosen.
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
        final Map<WaveformStyle, WaveformPreview> previews = previewHotCache.get(DeckReference.getDeckReference(player, 0));
        if (previews == null) {
            return null;
        }
        return previews.get(bestMatch(getPreferredStyle(), previews.keySet()));
    }

    /**
     * Look up the waveform preview in a particular style we have for the track loaded in the main deck of a given player number.
     *
     * @param player the device number whose waveform preview for the playing track is desired
     * @param style the waveform style desired
     *
     * @return the waveform preview in the specified style for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    @API(status = API.Status.EXPERIMENTAL)
    public WaveformPreview getLatestPreviewFor(int player, WaveformStyle style) {
        ensureRunning();
        final Map<WaveformStyle, WaveformPreview> previews = previewHotCache.get(DeckReference.getDeckReference(player, 0));
        if (previews == null) {
            return null;
        }
        return previews.get(style);
    }

    /**
     * Look up the waveform preview we have for a given player, identified by a status update received from that player.
     * If multiple styles of waveform are available, the best match for {@link #getPreferredStyle()} will be chosen.
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
     * Look up the waveform preview we have for a given player, identified by a status update received from that player,
     * in a particular style.
     *
     * @param update a status update from the player for which a waveform preview is desired
     * @param style the waveform style desired
     *
     * @return the waveform preview in the specified style for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    @API(status = API.Status.STABLE)
    public WaveformPreview getLatestPreviewFor(DeviceUpdate update, WaveformStyle style) {
        return getLatestPreviewFor(update.getDeviceNumber(), style);
    }

    /**
     * Look up the waveform detail we have for the track loaded in the main deck of a given player number.
     * If multiple styles of waveform are available, the best match for {@link #getPreferredStyle()} will be chosen.
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
        final Map<WaveformStyle, WaveformDetail> details = detailHotCache.get(DeckReference.getDeckReference(player, 0));
        if (details == null) {
            return null;
        }
        return details.get(bestMatch(getPreferredStyle(), details.keySet()));
    }

    /**
     * Look up the waveform detail in a particular style we have for the track loaded in the main deck of a given player number.
     *
     * @param player the device number whose waveform detail for the playing track is desired
     * @param style the waveform style desired
     *
     * @return the waveform detail in the specified style for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    @API(status = API.Status.EXPERIMENTAL)
    public WaveformDetail getLatestDetailFor(int player, WaveformStyle style) {
        ensureRunning();
        final Map<WaveformStyle, WaveformDetail> details = detailHotCache.get(DeckReference.getDeckReference(player, 0));
        if (details == null) {
            return null;
        }
        return details.get(style);
    }

    /**
     * Look up the waveform detail we have for a given player, identified by a status update received from that player.
     * If multiple styles of waveform are available, the best match for {@link #getPreferredStyle()} will be chosen.
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
     * Look up the waveform detail we have for a given player, identified by a status update received from that player,
     * in a particular style.
     *
     * @param update a status update from the player for which a waveform detail is desired
     * @param style the waveform style desired
     *
     * @return the waveform detail in the specified style for the track loaded on that player, if available
     *
     * @throws IllegalStateException if the WaveformFinder is not running
     */
    @API(status = API.Status.STABLE)
    public WaveformDetail getLatestDetailFor(DeviceUpdate update, WaveformStyle style) {
        return getLatestDetailFor(update.getDeviceNumber(), style);
    }

    /**
     * Ask the specified player for the waveform preview in the specified slot with the specified rekordbox ID,
     * using downloaded media instead if it is available, and possibly giving up if we are in passive mode.
     *
     * @param trackReference uniquely identifies the desired waveform preview
     * @param failIfPassive will prevent the request from taking place if we are in passive mode, so that automatic
     *                      waveform updates will use available caches and downloaded metadata exports only
     *
     * @return the waveform preview found, if any
     */
    private WaveformPreview requestPreviewInternal(final DataReference trackReference, final boolean failIfPassive) {

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
        if (MetadataFinder.getInstance().isPassive() && failIfPassive && trackReference.slot != CdjStatus.TrackSourceSlot.COLLECTION) {
            return null;
        }

        // We have to actually request the preview using the dbserver protocol.
        ConnectionManager.ClientTask<WaveformPreview> task =
                client -> getWaveformPreview(trackReference.rekordboxId, SlotReference.getSlotReference(trackReference), client);

        try {
            return ConnectionManager.getInstance().invokeWithClientSession(trackReference.player, task, "requesting waveform preview");
        } catch (Exception e) {
            logger.error("Problem requesting waveform preview, returning null", e);
        }
        return null;
    }

    /**
     * Ask the specified player for the specified waveform preview from the specified media slot, first checking if we
     * have a cached copy. Note that we do not yet know how to retrieve 3-band waveforms using the dbserver protocol.
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
        for (Map<WaveformStyle,WaveformPreview> cachedMap : previewHotCache.values()) {
            if (!cachedMap.isEmpty() && cachedMap.values().iterator().next().dataReference.equals(dataReference)) {
                return cachedMap.get(bestMatch(getPreferredStyle(), cachedMap.keySet()));
            }
        }
        return requestPreviewInternal(dataReference, false);
    }

    /**
     * Requests the waveform preview for a specific track ID, given a connection to a player that has already been
     * set up.
     *
     * @param rekordboxId the track whose waveform preview is desired
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved waveform preview, or {@code null} if none was available
     * @throws IOException if there is a communication problem
     */
    WaveformPreview getWaveformPreview(int rekordboxId, SlotReference slot, Client client)
            throws IOException {

        final NumberField idField = new NumberField(rekordboxId);

        // First try to get the NXS2-style color waveform if we are supposed to get color or 3-band waveforms (which we don’t yet know how to request).
        if (getPreferredStyle() != WaveformStyle.BLUE) {
            try {
                Message response = client.simpleRequest(Message.KnownType.ANLZ_TAG_REQ, Message.KnownType.ANLZ_TAG,
                        client.buildRMST(Message.MenuIdentifier.MAIN_MENU, slot.slot), idField,
                        new NumberField(Message.ANLZ_FILE_TAG_COLOR_WAVEFORM_PREVIEW), new NumberField(Message.ALNZ_FILE_TYPE_EXT));
                if (response.knownType != Message.KnownType.UNAVAILABLE && response.arguments.get(3).getSize() > 0) {
                    return new WaveformPreview(new DataReference(slot, rekordboxId), response);
                } else {
                    logger.info("No color waveform preview available for slot {}, id {}; requesting blue version.", slot, rekordboxId);
                }
            } catch (Exception e) {
                logger.info("No color waveform preview available for slot {}, id {}; requesting blue version.", slot, rekordboxId, e);
            }
        }

        Message response = client.simpleRequest(Message.KnownType.WAVE_PREVIEW_REQ, Message.KnownType.WAVE_PREVIEW,
                client.buildRMST(Message.MenuIdentifier.DATA, slot.slot), NumberField.WORD_1,
                idField, NumberField.WORD_0);
        return new WaveformPreview(new DataReference(slot, rekordboxId), response);
    }

    /**
     * Ask the specified player for the waveform detail in the specified slot with the specified rekordbox ID,
     * using downloaded media instead if it is available, and possibly giving up if we are in passive mode.
     *
     * @param trackReference uniquely identifies the desired waveform detail
     * @param failIfPassive will prevent the request from taking place if we are in passive mode, so that automatic
     *                      artwork updates will use available caches and downloaded metadata exports only
     *
     * @return the waveform preview found, if any
     */
    private WaveformDetail requestDetailInternal(final DataReference trackReference, final boolean failIfPassive) {

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
        if (MetadataFinder.getInstance().isPassive() && failIfPassive && trackReference.slot != CdjStatus.TrackSourceSlot.COLLECTION) {
            return null;
        }

        // We have to actually request the detail using the dbserver protocol.
        ConnectionManager.ClientTask<WaveformDetail> task =
                client -> getWaveformDetail(trackReference.rekordboxId, SlotReference.getSlotReference(trackReference), client);

        try {
            return ConnectionManager.getInstance().invokeWithClientSession(trackReference.player, task, "requesting waveform detail");
        } catch (Exception e) {
            logger.error("Problem requesting waveform preview, returning null", e);
        }
        return null;
    }

    /**
     * Ask the specified player for the specified waveform detail from the specified media slot, first checking if we
     * have a cached copy. Note that we do not yet know how to retrieve 3-band waveforms using the dbserver protocol.
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
        for (Map<WaveformStyle,WaveformDetail> cachedMap : detailHotCache.values()) {
            if (!cachedMap.isEmpty() && cachedMap.values().iterator().next().dataReference.equals(dataReference)) {  // Found a hot cue hit, use it.
                return cachedMap.get(bestMatch(getPreferredStyle(), cachedMap.keySet()));
            }
        }
        return requestDetailInternal(dataReference, false);
    }

    /**
     * Requests the waveform detail for a specific track ID, given a connection to a player that has already been
     * set up.
     *
     * @param rekordboxId the track whose waveform detail is desired
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved waveform detail, or {@code null} if none was available
     * @throws IOException if there is a communication problem
     */
    WaveformDetail getWaveformDetail(int rekordboxId, SlotReference slot, Client client)
            throws IOException {
        final NumberField idField = new NumberField(rekordboxId);

        // First try to get the NXS2-style color waveform if we are supposed to.
        if (preferredStyle.get() == WaveformStyle.RGB) {
            try {
                Message response = client.simpleRequest(Message.KnownType.ANLZ_TAG_REQ, Message.KnownType.ANLZ_TAG,
                        client.buildRMST(Message.MenuIdentifier.MAIN_MENU, slot.slot), idField,
                        new NumberField(Message.ANLZ_FILE_TAG_COLOR_WAVEFORM_DETAIL), new NumberField(Message.ALNZ_FILE_TYPE_EXT));
                if (response.knownType != Message.KnownType.UNAVAILABLE && response.arguments.get(3).getSize() > 0) {
                    return new WaveformDetail(new DataReference(slot, rekordboxId), response);
                } else {
                    logger.info("No color waveform available for slot {}, id {}; requesting blue version.", slot, rekordboxId);
                }
            } catch (Exception e) {
                logger.info("Problem requesting color waveform for slot {}, id {}; requesting blue version.", slot, rekordboxId, e);
            }
        }

        Message response = client.simpleRequest(Message.KnownType.WAVE_DETAIL_REQ, Message.KnownType.WAVE_DETAIL,
                client.buildRMST(Message.MenuIdentifier.MAIN_MENU, slot.slot), idField, NumberField.WORD_0);
        return new WaveformDetail(new DataReference(slot, rekordboxId), response);
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
                        // TODO: Suppress sending if our cache already holds a format that more-closely matches the preferred style
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

     * @param player the player whose waveform detail has changed
     * @param detail the new waveform detail, if any
     */
    private void deliverWaveformDetailUpdate(final int player, final WaveformDetail detail) {
        if (!getWaveformListeners().isEmpty()) {
            SwingUtilities.invokeLater(() -> {
                final WaveformDetailUpdate update = new WaveformDetailUpdate(player, detail);
                for (final WaveformListener listener : getWaveformListeners()) {
                    try {
                        if (detail == null) {  // If we lost the waveform entirely, we always report that.
                            listener.detailChanged(update);
                        } else {  // If the style we just received is now the best match for the preferred style, send an update reporting it is now available.
                            final Set<WaveformStyle> styles = new HashSet<>();
                            final Map<WaveformStyle, WaveformDetail> cachedMap =  detailHotCache.get(DeckReference.getDeckReference(player,0));
                            if (cachedMap != null) {
                                styles.addAll(cachedMap.keySet());
                            }
                            styles.add(detail.style);
                            if (bestMatch(getPreferredStyle(), styles) == detail.style) {
                                listener.detailChanged(update);
                            }
                        }
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

        if (update.metadata == null || update.metadata.trackType != CdjStatus.TrackType.REKORDBOX) {
            clearDeck(update);
        } else {
            // We can offer waveform information for this device; check if we've already looked it up. First, preview:
            final Map<WaveformStyle, WaveformPreview> lastPreviewMap = previewHotCache.get(DeckReference.getDeckReference(update.player, 0));
            final WaveformPreview lastPreview = (lastPreviewMap == null)? null : lastPreviewMap.get(getPreferredStyle());
            if (lastPreview == null || !lastPreview.dataReference.equals(update.metadata.trackReference)) {  // We have something new!
                clearDeckPreview(update);

                // First see if we can find the new preview in the hot cache
                for (Map<WaveformStyle, WaveformPreview> cachedMap : previewHotCache.values()) {
                    final WaveformPreview cachedPreview = cachedMap.get(getPreferredStyle());
                    if (cachedPreview != null && cachedPreview.dataReference.equals(update.metadata.trackReference)) {  // Found a hot cue hit, use it.
                        foundInCache = true;
                        for (WaveformPreview cached : cachedMap.values()) {  // We can potentially use any of the cached styles for other listeners, too.
                            updatePreview(update, cached);
                        }
                        break;
                    }
                }

                // If not found in the cache, try actually retrieving it unless that is already in progress.
                if (!foundInCache && activePreviewRequests.add(update.player)) {
                    new Thread(() -> {
                        try {
                            WaveformPreview preview = requestPreviewInternal(update.metadata.trackReference, true);
                            if (preview != null) {
                                updatePreview(update, preview);
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
            final Map<WaveformStyle, WaveformDetail> lastDetailMap = detailHotCache.get(DeckReference.getDeckReference(update.player, 0));
            final WaveformDetail lastDetail = (lastDetailMap == null)? null : lastDetailMap.get(getPreferredStyle());
            if (isFindingDetails() && (lastDetail == null || !lastDetail.dataReference.equals(update.metadata.trackReference))) {  // We have something new!
                clearDeckDetail(update);

                // First see if we can find the new detailed waveform in the hot cache
                for (Map<WaveformStyle, WaveformDetail> cachedMap : detailHotCache.values()) {
                    final WaveformDetail cachedDetail = cachedMap.get(getPreferredStyle());
                    if (cachedDetail != null && cachedDetail.dataReference.equals(update.metadata.trackReference)) {  // Found a hot cue hit, use it.
                        foundInCache = true;
                        for (WaveformDetail cached : cachedMap.values()) {  // We can potentially use any of the cached styles for other listeners, too.
                            updateDetail(update, cached);
                        }
                        break;
                    }
                }

                // If not found in the cache, try actually retrieving it unless that is already in progress.
                if (!foundInCache && activeDetailRequests.add(update.player)) {
                    new Thread(() -> {
                        try {
                            WaveformDetail detail = requestDetailInternal(update.metadata.trackReference, true);
                            if (detail != null) {
                                updateDetail(update, detail);
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
            for (Map.Entry<DeckReference, TrackMetadata> entry : MetadataFinder.getInstance().getLoadedTracks().entrySet()) {
                if (entry.getKey().hotCue == 0) {  // The track is currently loaded in a main player deck
                    handleUpdate(new TrackMetadataUpdate(entry.getKey().player, entry.getValue()));
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
     * Discard all waveforms that we have learned, either because we are stopping, or because the user has changed
     * the preferred waveform style.
     */
    private void clearAllWaveforms() {
        // Report the loss of our waveforms, on the proper thread, outside any lock.
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
