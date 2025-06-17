package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p>Watches the beat packets and transport information contained in player status update to infer the current
 * track playback position based on the most recent information available, the time at which that was
 * received, and the playback pitch and direction that was in effect at that time.</p>
 *
 * <p>Takes advantage of
 * <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/beats.html#absolute-position-packets">precise position
 * packets</a> from the CDJ-3000 to support a much more precise and robust tracking of playback (and idle) position for
 * those players.</p>
 *
 * <p>Can only operate properly when track metadata and beat grids are available, as these are necessary to
 * convert beat numbers into track positions.</p>
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public class TimeFinder extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(TimeFinder.class);

    /**
     * Keeps track of the latest position information we have from each player, indexed by player number. When nothing
     * is known for a player, the entry will be missing.
     */
    private final ConcurrentHashMap<Integer, TrackPositionUpdate> positions = new ConcurrentHashMap<>();

    /**
     * Keeps track of the latest device update reported by each player, indexed by player number.
     */
    private final ConcurrentHashMap<Integer, DeviceUpdate> updates = new ConcurrentHashMap<>();

    /**
     * Our announcement listener watches for devices to disappear from the network, so we can discard all information
     * about them.
     */
    private final DeviceAnnouncementListener announcementListener = new DeviceAnnouncementListener() {
        @Override
        public void deviceFound(final DeviceAnnouncement announcement) {
            logger.debug("Currently nothing for TimeFinder to do when devices appear.");
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            if (announcement.getDeviceNumber() == 25 && announcement.getDeviceName().equals("NXS-GW")) {
                logger.debug("Ignoring departure of Kuvo gateway, which fight each other and come and go constantly, especially in CDJ-3000s.");
                return;
            }
            logger.info("Clearing position information in response to the loss of a device, {}", announcement);
            positions.remove(announcement.getDeviceNumber());
            updates.remove(announcement.getDeviceNumber());
        }
    };

    /**
     * Keep track of whether we are running
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Check whether we are currently running.
     *
     * @return true if track playback positions are being kept track of for all active players
     */
    @API(status = API.Status.STABLE)
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get the latest track position reports available for all visible players.
     *
     * @return the track position information we have been able to calculate for all current players
     *
     * @throws IllegalStateException if the TimeFinder is not running
     */
    @API(status = API.Status.STABLE)
    public Map<Integer, TrackPositionUpdate> getLatestPositions() {
        ensureRunning();
        return Collections.unmodifiableMap(new HashMap<>(positions));
    }

    /**
     * Get the latest device updates (either beats or status updates) available for all visible players.
     *
     * @return the latest beat or status update, whichever was more recent, for each current player
     */
    @API(status = API.Status.STABLE)
    public Map<Integer, DeviceUpdate> getLatestUpdates() {
        ensureRunning();
        return Collections.unmodifiableMap(new HashMap<>(updates));
    }

    /**
     * Get the latest information we have for the specified player. This incorporates both status updates and
     * beat packets we have received from the player, and so is the most definitive source of time and tempo
     * information from the player.
     *
     * @param player the player number whose position information is desired
     *
     * @return the consolidated track position information we have for the specified player, or {@code null} if we
     *         do not have enough information to calculate it
     *
     * @throws IllegalStateException if the TimeFinder is not running
     */
    @API(status = API.Status.STABLE)
    public TrackPositionUpdate getLatestPositionFor(int player) {
        ensureRunning();
        return positions.get(player);
    }

    /**
     * Get the latest information we have for the player that sent the supplied status update.
     * The result incorporates both status updates and beat packets we have received from the player,
     * and so is the most definitive source of time and tempo information from the player.
     *
     * @param update the device update from a player whose position information is desired
     *
     * @return the consolidated track position information we have for the specified player, or {@code null} if we
     *         do not have enough information to calculate it
     *
     * @throws IllegalStateException if the TimeFinder is not running
     */
    @API(status = API.Status.STABLE)
    public TrackPositionUpdate getLatestPositionFor(DeviceUpdate update) {
        return getLatestPositionFor(update.getDeviceNumber());
    }

    /**
     * Get the beat or status update reported by the specified player, whichever is most recent. This is available
     * even when we do not have a beat grid available in order to calculate detailed track position information.
     *
     * @param player the player number whose most recent status is desired
     *
     * @return the latest beat or status reported by the specified player, or {@code null} if we have not heard any
     *
     * @throws IllegalStateException if the TimeFinder is not running
     */
    @API(status = API.Status.STABLE)
    public DeviceUpdate getLatestUpdateFor(int player) {
        ensureRunning();
        return updates.get(player);
    }

    /**
     * Figure out, based on how much time has elapsed since we received an update, and the playback position,
     * speed, and direction at the time of that update, where the player will be now.
     *
     * @param update the most recent update received from a player
     * @param currentTimestamp the nanosecond timestamp representing when we want to interpolate the track's position
     *
     * @return the playback position we believe that player has reached now
     */
    private long interpolateTimeSinceUpdate(TrackPositionUpdate update, long currentTimestamp) {
        if (!update.playing) {
            return update.milliseconds;
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(currentTimestamp - update.timestamp);
        long moved = Math.round(update.pitch * elapsedMillis);
        if (update.reverse) {
            return update.milliseconds - moved;
        }
        return update.milliseconds + moved;
    }

    /**
     * Checks whether a CDJ status update seems to be close enough to a cue that if we just jumped there (or just
     * loaded the track) it would be a reasonable assumption that we jumped to the cue.
     *
     * @param update the status update to check for proximity to hot cues and memory points
     * @param beatGrid the beat grid of the track  being played
     * @return a matching memory point if we had a cue list available and were within a beat of one, or {@code null}
     */
    private CueList.Entry findAdjacentCue(CdjStatus update, BeatGrid beatGrid) {
        if (!MetadataFinder.getInstance().isRunning()) return null;
        final TrackMetadata metadata = MetadataFinder.getInstance().getLatestMetadataFor(update);
        final int newBeat = update.getBeatNumber();
        if (metadata != null && metadata.getCueList() != null) {
            for (CueList.Entry entry : metadata.getCueList().entries) {
                final int entryBeat = beatGrid.findBeatAtTime(entry.cueTime);
                if (Math.abs(newBeat - entryBeat) < 2) {
                    return entry;  // We have found a cue we likely jumped to
                }
                if (entryBeat > newBeat) {
                    break;  // We have moved past our location, no point scanning further.
                }
            }
        }
        return null;
    }

    /**
     * Sanity-check a new non-beat update, make sure we are still interpolating a sensible position, and correct
     * as needed.
     *
     * @param lastTrackUpdate the most recent digested update received from a player
     * @param newDeviceUpdate a new status update from the player
     * @param beatGrid the beat grid for the track that is playing, in case we have jumped
     *
     * @return the playback position we believe that player has reached at that point in time
     */
    private long interpolateTimeFromUpdate(TrackPositionUpdate lastTrackUpdate, CdjStatus newDeviceUpdate,
                                           BeatGrid beatGrid) {
        final int beatNumber = newDeviceUpdate.getBeatNumber();
        final boolean noLongerPlaying = !newDeviceUpdate.isPlaying();

        // If we have just stopped, see if we are near a cue (assuming that information is available), and if so,
        // the best assumption is that the DJ jumped to that cue.
        if (lastTrackUpdate.playing && noLongerPlaying) {
            final CueList.Entry jumpedTo = findAdjacentCue(newDeviceUpdate, beatGrid);
            if (jumpedTo != null) return jumpedTo.cueTime;
        }

        // Handle the special case where we were not playing either in the previous or current update, but the DJ
        // might have jumped to a different place in the track.
        if (!lastTrackUpdate.playing) {
            if (lastTrackUpdate.beatNumber == beatNumber && noLongerPlaying) {  // Haven't moved
                return lastTrackUpdate.milliseconds;
            } else {
                if (noLongerPlaying) {  // Have jumped without playing.
                    if (beatNumber < 0) {
                        return -1; // We don't know the position anymore; weird to get into this state and still have a grid?
                    }
                    // As a heuristic, assume we are right before the beat?
                    return timeOfBeat(beatGrid, beatNumber, newDeviceUpdate);
                }
            }
        }

        // One way or another, we are now playing.
        long elapsedMillis = (newDeviceUpdate.getTimestamp() - lastTrackUpdate.timestamp) / 1000000;
        long moved = Math.round(lastTrackUpdate.pitch * elapsedMillis);
        long interpolated = (lastTrackUpdate.reverse)?
                Math.max(lastTrackUpdate.milliseconds - moved, 0) : lastTrackUpdate.milliseconds + moved;
        if (Math.abs(beatGrid.findBeatAtTime(interpolated) - beatNumber) < 2) {
            return interpolated;  // Our calculations still look plausible
        }
        // The player has jumped or drifted somewhere unexpected, correct.
        if (newDeviceUpdate.isPlayingForwards()) {
            return timeOfBeat(beatGrid, beatNumber, newDeviceUpdate);
        } else {
            return beatGrid.getTimeWithinTrack(Math.min(beatNumber + 1, beatGrid.beatCount));
        }
    }

    /**
     * Get the best guess we have for the current track position on the specified player.
     *
     * @param player the player number whose position is desired
     *
     * @return the milliseconds into the track that we believe playback has reached, or -1 if we don't know
     *
     * @throws IllegalStateException if the TimeFinder is not running
     */
    @API(status = API.Status.STABLE)
    public long getTimeFor(int player) {
        TrackPositionUpdate update = positions.get(player);
        if (update != null) {
            return interpolateTimeSinceUpdate(update, System.nanoTime());
        }
        return  -1;  // We don't know.
    }

    /**
     * Get the best guess we have for the current track position on the player that sent the specified update.
     *
     * @param update the device update from a player whose position is desired
     *
     * @return the milliseconds into the track that we believe playback has reached, or -1 if we don't know
     *
     * @throws IllegalStateException if the TimeFinder is not running
     */
    @API(status = API.Status.STABLE)
    public long getTimeFor(DeviceUpdate update) {
        return getTimeFor(update.getDeviceNumber());
    }

    /**
     * Keeps track of the listeners that have registered interest in closely following track playback for a particular
     * player. The keys are the listener interface, and the values are the last update that was sent to that
     * listener. If no information was known during the last update, the special value {@link #NO_INFORMATION} is
     * used to represent it, rather than trying to store a {@code null} value in the hash map.
     */
    private final ConcurrentHashMap<TrackPositionListener, TrackPositionUpdate> trackPositionListeners = new ConcurrentHashMap<>();

    /**
     * Keeps track of the player numbers that registered track position listeners are interested in.
     */
    private final ConcurrentHashMap<TrackPositionListener, Integer> listenerPlayerNumbers = new ConcurrentHashMap<>();

    /**
     * This is used to represent the fact that we have told a listener that there is no information for it, since
     * we can't actually store a {@code null} value in a {@link ConcurrentHashMap}.
     */
    private final TrackPositionUpdate NO_INFORMATION = new TrackPositionUpdate(0, 0, 0,
            false, false, 0, false, null, false, false);

    /**
     * Add a listener that wants to closely follow track playback for a particular player. The listener will be called
     * as soon as there is an initial {@link TrackPositionUpdate} for the specified player, and whenever there is an
     * unexpected change in playback position, speed, or state on that player.
     * <p>
     * To help the listener orient itself, it is sent a {@link TrackPositionListener#movementChanged(TrackPositionUpdate)}
     * message immediately upon registration to report the current playback position, even if none is known (in which
     * case it will be called with the value {@code null}).
     * <p>
     * If the same listener was previously registered (for example, to listen to a different player), this call
     * replaces the former registration with the new one.
     *
     * @param player the player number that the listener is interested in
     * @param listener the interface that will be called when there are changes in track playback on the player
     */
    @API(status = API.Status.STABLE)
    public void addTrackPositionListener(int player, TrackPositionListener listener) {
        listenerPlayerNumbers.put(listener, player);
        TrackPositionUpdate currentPosition = positions.get(player);
        trackPositionListeners.put(listener, currentPosition == null? NO_INFORMATION : currentPosition);
        listener.movementChanged(currentPosition);  // If this throws an exception, the caller will catch it.
    }

    /**
     * Remove a listener that was following track playback movement.
     *
     * @param listener the interface that will no longer be called for changes in track playback
     */
    @API(status = API.Status.STABLE)
    public void removeTrackPositionListener(TrackPositionListener listener) {
        trackPositionListeners.remove(listener);
        listenerPlayerNumbers.remove(listener);
    }

    /**
     * How many milliseconds is our interpolated time allowed to drift before we consider it a significant enough
     * change to update listeners that are trying to track a player's playback position.
     */
    private final AtomicLong slack = new AtomicLong(50);

    /**
     * Check how many milliseconds our interpolated time is allowed to drift from what is being reported by a player
     * before we consider it a significant enough change to report to listeners that are trying to closely track a
     * player's playback position.
     *
     * @return the maximum number of milliseconds we will allow our listeners to diverge from the reported playback
     * position before reporting a jump
     */
    @API(status = API.Status.STABLE)
    public long getSlack() {
        return slack.get();
    }

    /**
     * Set how many milliseconds our interpolated time is allowed to drift from what is being reported by a player
     * before we consider it a significant enough change to report to listeners that are trying to closely track a
     * player's playback position.
     *
     * @param slack the maximum number of milliseconds we will allow our listeners to diverge from the reported playback
     * position before reporting a jump
     */
    @API(status = API.Status.STABLE)
    public void setSlack(long slack) {
        this.slack.set(slack);
    }

    /**
     * Check whether we have diverged from what we would predict from the last update that was sent to a particular
     * track position listener.
     *
     * @param lastUpdate the last update that was sent to the listener
     * @param currentUpdate the latest update available for the same player
     *
     * @return {@code true} if the listener will have diverged by more than our permitted amount of slack, and so
     * should be updated
     */
    private boolean interpolationsDisagree(TrackPositionUpdate lastUpdate, TrackPositionUpdate currentUpdate) {
        final long now = System.nanoTime();
        final long skew = Math.abs(interpolateTimeSinceUpdate(lastUpdate, now) - interpolateTimeSinceUpdate(currentUpdate, now));
        final long tolerance = lastUpdate.playing? slack.get() : 0;  // If we are not playing, any difference is real, from a precise update.
        if (tolerance > 0 && skew > tolerance && logger.isDebugEnabled()) {
            logger.debug("interpolationsDisagree: updates arrived {} ms apart, last {}interpolates to {}, current {}interpolates to {}, skew {}",
                    TimeUnit.NANOSECONDS.toMillis(currentUpdate.timestamp - lastUpdate.timestamp),
                    (lastUpdate.fromBeat? "(beat) " : ""), interpolateTimeSinceUpdate(lastUpdate, now),
                    (currentUpdate.fromBeat? "(beat) " : ""), interpolateTimeSinceUpdate(currentUpdate, now), skew);
        }
        return skew > tolerance;
    }

    private boolean pitchesDiffer(TrackPositionUpdate lastUpdate, TrackPositionUpdate currentUpdate) {
        final double delta = Math.abs(lastUpdate.pitch - currentUpdate.pitch);
        if (lastUpdate.precise && (lastUpdate.fromBeat != currentUpdate.fromBeat)) {
            // We're in a precise position packet situation, so beats send pitch differently
            return delta > 0.001;
        } else {
            // Pitches are comparable, we can use a tight tolerance to detect changes
            return delta > 0.000001;
        }
    }

    /**
     * Check if the current position tracking information for a player represents a significant change compared to
     * what a listener was last informed to expect, and if so, send another update. If this is a definitive update
     * (i.e. a new beat), and the listener wants all beats, always send it.
     *
     * @param player the device number for which an update has occurred
     * @param update the latest track position tracking information for the specified player, or {@code null} if we
     *               no longer have any
     * @param beat if this update was triggered by a beat packet, contains the packet to pass on to interested listeners
     */
    private void updateListenersIfNeeded(int player, TrackPositionUpdate update, Beat beat) {
        // Iterate over a copy to avoid issues with concurrent modification
        for (Map.Entry<TrackPositionListener, TrackPositionUpdate> entry : new HashMap<>(trackPositionListeners).entrySet()) {
            if (player == listenerPlayerNumbers.get(entry.getKey())) {  // This listener is interested in this player
                if (update == null) {  // We are reporting a loss of information
                    if (entry.getValue() != NO_INFORMATION) {
                        if (trackPositionListeners.replace(entry.getKey(), entry.getValue(), NO_INFORMATION)) {
                            try {
                                entry.getKey().movementChanged(null);
                            } catch (Throwable t) {
                                logger.warn("Problem delivering null movementChanged update", t);
                            }
                        }
                    }
                } else {  // We have some information, see if it is a significant change from what was last reported
                    final TrackPositionUpdate lastUpdate = entry.getValue();
                    if (lastUpdate == NO_INFORMATION ||
                            lastUpdate.playing != update.playing ||
                            pitchesDiffer(lastUpdate, update) ||
                            interpolationsDisagree(lastUpdate, update)) {
                        if (trackPositionListeners.replace(entry.getKey(), entry.getValue(), update)) {
                            try {
                                entry.getKey().movementChanged(update);
                            } catch (Throwable t) {
                                logger.warn("Problem delivering movementChanged update", t);
                            }
                        }
                    }

                    // And regardless of whether this was a significant change, if this was a new beat and the listener
                    // implements the interface that requests all beats, send that information.
                    if (update.fromBeat && entry.getKey() instanceof TrackPositionBeatListener) {
                        try {
                            ((TrackPositionBeatListener) entry.getKey()).newBeat(beat, update);
                        } catch (Throwable t) {
                            logger.warn("Problem delivering newBeat update", t);
                        }
                    }
                }
            }
        }
    }

    /**
     * Reacts to player status updates to update the predicted playback position and state and potentially inform
     * registered track position listeners of significant changes. If we are receiving precise position packets
     * from the same player, we ignore these less-useful updates.
     */
    private final DeviceUpdateListener updateListener = update -> {
        final int device = update.getDeviceNumber();
        TrackPositionUpdate lastPosition = positions.get(device);
        if (update instanceof CdjStatus && (lastPosition == null || !lastPosition.precise)) {
            updates.put(device, update);
            final BeatGrid beatGrid = BeatGridFinder.getInstance().getLatestBeatGridFor(update);
            final int beatNumber = ((CdjStatus) update).getBeatNumber();
            // logger.debug("Update: beat " + update.getBeatWithinBar() + " -- " + beatNumber);
            if (beatGrid != null && (beatNumber >= 0)) {
                boolean done = false;
                while (!done && ((lastPosition == null) || lastPosition.timestamp < update.getTimestamp())) {
                    TrackPositionUpdate newPosition;
                    if (lastPosition == null || lastPosition.beatGrid != beatGrid) {
                        // This is a new track, and we have not yet received a beat packet for it
                        long timeGuess = timeOfBeat(beatGrid, beatNumber, update);
                        final CueList.Entry likelyCue = findAdjacentCue((CdjStatus) update, beatGrid);
                        if (likelyCue != null) {
                            timeGuess = likelyCue.cueTime;
                        }
                        newPosition = new TrackPositionUpdate(update.getTimestamp(),
                                timeGuess, beatNumber, false,
                                ((CdjStatus) update).isPlaying(),
                                Util.pitchToMultiplier(update.getPitch()),
                                ((CdjStatus) update).isPlayingBackwards(), beatGrid, false, false);
                    } else {
                        final long newTime = interpolateTimeFromUpdate(lastPosition, (CdjStatus) update, beatGrid);
                        final boolean newReverse = ((CdjStatus) update).isPlayingBackwards();
                        // Although the players report themselves stopped when they hit the end of the track playing forward,
                        // they don't do that when they hit the beginning playing backward! That makes for weird timecode and
                        // track waveform positioning, so we make up for it by synthesizing a stopped state here.
                        final boolean newPlaying = ((CdjStatus) update).isPlaying() && (!newReverse || newTime > 0);
                        newPosition = new TrackPositionUpdate(update.getTimestamp(), newTime, beatNumber,
                                false, newPlaying, Util.pitchToMultiplier(update.getPitch()),
                                newReverse, beatGrid, false, false);
                    }
                    if (lastPosition == null) {
                        done = (positions.putIfAbsent(device, newPosition) == null);
                    } else {
                        done = positions.replace(device, lastPosition, newPosition);
                    }
                    if (done) {
                        updateListenersIfNeeded(device, newPosition, null);
                    } else {  // Some other thread updated the position while we were working, re-evaluate.
                        lastPosition = positions.get(device);
                    }
                }
            } else {
                positions.remove(device);  // We can't say where that player is.
                updateListenersIfNeeded(device, null, null);
            }
        }
    };

    /**
     * Translates a beat number to a track time, given a beat grid. In almost all cases, simply delegates that task
     * to the beat grid. However, since players sometimes report beats that are outside the beat grid (especially when
     * looping tracks that extend for more than a beat's worth of time past the last beat in the beat grid), to avoid
     * weird exceptions and infinitely-growing track time reports in such situations, we extrapolate an extension to
     * the beat grid by repeating the interval between the last two beats in the track.
     * <p>
     * In the completely degenerate case of a track with a single beat (which probably will never occur), we simply
     * return that beat's time even if you ask for a later one.
     *
     * @param beatGrid the times at which known beats fall in the track whose playback time we are reporting.
     *
     * @param beatNumber the number of the beat that a player has just reported reaching, which may be slightly
     *                   greater than the actual number of beats in the track.
     *
     * @param update the device update which caused this calculation to take place, in case we need to log a warning
     *               that the reported beat number falls outside the beat grid.
     *
     * @return the number of milliseconds into the track at which that beat falls, perhaps extrapolated past the final
     *         recorded beat.
     */
    private long timeOfBeat(BeatGrid beatGrid, int beatNumber, DeviceUpdate update) {
        if (beatNumber <= beatGrid.beatCount) {
            return beatGrid.getTimeWithinTrack(beatNumber);
        }
        logger.warn("Received beat number {} from {} {}, but beat grid only goes up to beat {}. Packet: {}",
                beatNumber, update.getDeviceName(), update.getDeviceNumber(), beatGrid.beatCount, update);
        if (beatGrid.beatCount < 2) {
            return beatGrid.getTimeWithinTrack(1);
        }
        long lastTime = beatGrid.getTimeWithinTrack(beatGrid.beatCount);
        long lastInterval = lastTime - beatGrid.getTimeWithinTrack(beatGrid.beatCount - 1);
        return lastTime + (lastInterval * (beatNumber - beatGrid.beatCount));
    }

    /**
     * Reacts to beat messages to update the definitive playback position for that player. Because we may sometimes get
     * a beat packet before a corresponding device update containing the actual beat number, don't increment past the
     * end of the beat grid, because we must be looping if we get an extra beat there.
     */
    private final BeatListener beatListener = beat -> {
        final int device = beat.getDeviceNumber();
        if (device < 16) {  // We only care about CDJs.
            updates.put(device, beat);
            // logger.info("Beat: " + beat.getBeatWithinBar());
            final BeatGrid beatGrid = BeatGridFinder.getInstance().getLatestBeatGridFor(beat);
            if (beatGrid != null) {
                TrackPositionUpdate lastPosition = positions.get(device);
                int beatNumber;
                if (lastPosition == null || lastPosition.beatGrid != beatGrid) {
                    // We don’t handle beat packets received before any status packets for the player. This will
                    // probably never happen except in cases where we can't use the status packets because the
                    // player is a pre-nexus model that does not send beat numbers, so we don't want to be tricked
                    // into guessing a position for that player based on no valid information.
                    return;
                } else {
                    // See if we have moved past 1/5 of the way into the current beat.
                    final long distanceIntoBeat = lastPosition.milliseconds - beatGrid.getTimeWithinTrack(lastPosition.beatNumber);
                    final long farEnough = 6000000 / beat.getBpm() / 5;
                    if (distanceIntoBeat >= farEnough) {  // We can consider this the start of a new beat
                        beatNumber = Math.min(lastPosition.beatNumber + 1, beatGrid.beatCount);  // Handle loop at end
                    } else {  // We must have received the beat packet out of order with respect to the first status in the beat.
                        beatNumber = lastPosition.beatNumber;
                    }
                }

                // We know the player is playing forward because otherwise we don't get beats.
                final TrackPositionUpdate newPosition = new TrackPositionUpdate(beat.getTimestamp(),
                        timeOfBeat(beatGrid, beatNumber, beat), beatNumber, true, true,
                        Util.pitchToMultiplier(beat.getPitch()), false, beatGrid,
                        lastPosition.precise, true);
                positions.put(device, newPosition);
                updateListenersIfNeeded(device, newPosition, beat);
            } else {
                positions.remove(device);  // We can't determine where the player is.
                updateListenersIfNeeded(device, null, beat);
            }
        }
    };

    /**
     * Checks whether the specified beat listener belongs to the TimeFinder. The {@link BeatFinder} uses this
     * to notify the TimeFinder before any other listeners when a beat is received so that other listeners can
     * rely on the TimeFinder having an up-to-date beat number when they want it. There's no reason for any
     * other class to call this method (and the only reason it is public is that the BeatFinder is in a
     * different package).
     *
     * @param listener the beat listener implementation to be checked
     * @return {@code true} if the listener is ours
     */
    @API(status = API.Status.STABLE)
    public boolean isOwnBeatListener(BeatListener listener) {
        return listener == beatListener;
    }

    /**
     * Tracks whether we have been told to disregard precise position packets from devices that send them,
     * to help with jitter while we are working on a better smoothing approach.
     */
    private final AtomicBoolean usePrecisePositonPackets = new AtomicBoolean(true);

    /**
     * Control whether we should pay attention to precise position packets from devices (like the CDJ-3000) that
     * send them. The default is to use them to tightly track playback, but that is currently resulting in a
     * problematic amount of jitter when synchronizing with other audio sources via Ableton Link. Ignoring these
     * packets will treat CDJ-3000s like other player hardware, where only beat packets are trusted as position
     * anchors.
     *
     * @param use whether precise position packets should be used to help track the playback position
     */
    @API(status = API.Status.EXPERIMENTAL)
    public void setUsePrecisePositionPackets(boolean use) {
        usePrecisePositonPackets.set(use);
    }

    /**
     * Check whether we are paying attention to precise position packets from devices (like the CDJ-3000) that
     * send them. The default is to use them to tightly track playback, but that is currently resulting in a
     * problematic amount of jitter when synchronizing with other audio sources via Ableton Link. Ignoring these
     * packets will treat CDJ-3000s like other player hardware, where only beat packets are trusted as position
     * anchors.
     *
     * @return an indication of whether precise position packets are being taken into consideration for position tracking
     */
    @API(status = API.Status.EXPERIMENTAL)
    public boolean isUsingPrecisePositionPackets() {
        return usePrecisePositonPackets.get();
    }

    /**
     * Reacts to precise position updates to update the definitive, precise position of the reporting player.
     */
    private final PrecisePositionListener positionListener = position -> {
        if (usePrecisePositonPackets.get()) {
            final int device = position.getDeviceNumber();
            if (device < 16) {  // We only care about CDJs, if other devices ever send these.
                updates.put(device, position);
                final DeviceUpdate lastStatus = VirtualCdj.getInstance().getLatestStatusFor(device);
                final boolean playing = lastStatus instanceof CdjStatus && ((CdjStatus) lastStatus).isPlaying();
                final boolean reverse = lastStatus instanceof CdjStatus && ((CdjStatus) lastStatus).isPlayingBackwards();
                final BeatGrid beatGrid = BeatGridFinder.getInstance().getLatestBeatGridFor(position);
                final int beatNumber = (beatGrid == null) ? 0 : beatGrid.findBeatAtTime(position.getPlaybackPosition());
                final TrackPositionUpdate newPosition = new TrackPositionUpdate(position.getTimestamp(),
                        position.getPlaybackPosition(), beatNumber, true, playing,
                        Util.pitchToMultiplier(position.getPitch()), reverse, beatGrid, true, false);
                positions.put(device, newPosition);
                updateListenersIfNeeded(device, newPosition, null);
            }
        }
    };

    /**
     * Set up to automatically stop if anything we depend on stops.
     */
    private final LifecycleListener lifecycleListener = new LifecycleListener() {
        @Override
        public void started(LifecycleParticipant sender) {
            logger.debug("The TimeFinder does not auto-start when {} does.", sender);
        }

        @Override
        public void stopped(LifecycleParticipant sender) {
            if (isRunning()) {
                logger.info("TimeFinder stopping because {} has.", sender);
                stop();
            }
        }
    };
    /**
     * <p>Start interpolating playback position for all active players. Starts the {@link BeatGridFinder},
     * {@link BeatFinder}, and {@link VirtualCdj} if they are not already running, because we need them to
     * perform our calculations. This in turn starts the {@link DeviceFinder}, so we can keep track of the
     * comings and goings of players themselves.</p>
     *
     * @throws Exception if there is a problem starting the required components
     */
    @API(status = API.Status.STABLE)
    public synchronized void start() throws Exception {
        if (!isRunning()) {
            DeviceFinder.getInstance().addDeviceAnnouncementListener(announcementListener);
            BeatGridFinder.getInstance().addLifecycleListener(lifecycleListener);
            BeatGridFinder.getInstance().start();
            VirtualCdj.getInstance().addUpdateListener(updateListener);
            VirtualCdj.getInstance().addLifecycleListener(lifecycleListener);
            VirtualCdj.getInstance().start();
            BeatFinder.getInstance().addLifecycleListener(lifecycleListener);
            BeatFinder.getInstance().addBeatListener(beatListener);
            BeatFinder.getInstance().addPrecisePositionListener(positionListener);
            BeatFinder.getInstance().start();
            running.set(true);
            deliverLifecycleAnnouncement(logger, true);
        }
    }

    /**
     * Stop interpolating playback position for all active players.
     */
    @API(status = API.Status.STABLE)
    public synchronized void stop() {
        if (isRunning()) {
            BeatFinder.getInstance().removePrecisePositionListener(positionListener);
            BeatFinder.getInstance().removeBeatListener(beatListener);
            VirtualCdj.getInstance().removeUpdateListener(updateListener);
            running.set(false);
            positions.clear();
            updates.clear();
            deliverLifecycleAnnouncement(logger, false);
        }
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final TimeFinder ourInstance = new TimeFinder();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    @API(status = API.Status.STABLE)
    public static TimeFinder getInstance() {
        return ourInstance;
    }

    /**
     * Prevent direct instantiation.
     */
    private TimeFinder() {
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TimeFinder[running:").append(isRunning());
        sb.append(", listener count:").append(trackPositionListeners.size());
        if (isRunning()) {
            sb.append(", latestPositions:").append(getLatestPositions());
            sb.append(", latestUpdates:").append(getLatestUpdates());
        }
        return sb.append("]").toString();
    }
}
