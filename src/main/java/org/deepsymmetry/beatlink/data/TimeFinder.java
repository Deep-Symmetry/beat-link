package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p>Watches the beat packets and transport information contained in player status update to infer the current
 * track playback position based on the most recent information available, the time at which that was
 * received, and the playback pitch and direction that was in effect at that time.</p>
 *
 * <p>Can only operate properly when track metadata and beat grids are available, as these are necessary to
 * convert beat numbers into track positions.</p>
 *
 * @author James Elliott
 */
public class TimeFinder extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(TimeFinder.class);

    /**
     * Keeps track of the latest position information we have from each player, indexed by player number.
     */
    private final ConcurrentHashMap<Integer, TrackPositionUpdate> positions = new ConcurrentHashMap<Integer, TrackPositionUpdate>();

    /**
     * Keeps track of the latest device update reported by each player, indexed by player number.
     */
    private final ConcurrentHashMap<Integer, DeviceUpdate> updates = new ConcurrentHashMap<Integer, DeviceUpdate>();

    /**
     * Our announcement listener watches for devices to disappear from the network so we can discard all information
     * about them.
     */
    private final DeviceAnnouncementListener announcementListener = new DeviceAnnouncementListener() {
        @Override
        public void deviceFound(final DeviceAnnouncement announcement) {
            logger.debug("Currently nothing for TimeFinder to do when devices appear.");
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            logger.info("Clearing position information in response to the loss of a device, {}", announcement);
            positions.remove(announcement.getNumber());
            updates.remove(announcement.getNumber());
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
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get the latest track position reports available for all visible players.
     *
     * @return the the track position information we have been able to calculate for all current players
     *
     * @throws IllegalStateException if the TimeFinder is not running
     */
    @SuppressWarnings("WeakerAccess")
    public Map<Integer, TrackPositionUpdate> getLatestPositions() {
        ensureRunning();
        return Collections.unmodifiableMap(new HashMap<Integer, TrackPositionUpdate>(positions));
    }

    /**
     * Get the latest device updates (either beats or status updates) available for all visible players.
     *
     * @return the latest beat or status update, whichever was more recent, for each current player
     */
    public Map<Integer, DeviceUpdate> getLatestUpdates() {
        ensureRunning();
        return Collections.unmodifiableMap(new HashMap<Integer, DeviceUpdate>(updates));
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
        long elapsedMillis = (currentTimestamp - update.timestamp) / 1000000;
        long moved = Math.round(update.pitch * elapsedMillis);
        if (update.reverse) {
            return update.milliseconds - moved;
        }
        return update.milliseconds + moved;
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
        if (!lastTrackUpdate.playing ) {  // Haven't moved
            if (lastTrackUpdate.beatNumber == beatNumber) {
                return lastTrackUpdate.milliseconds;
            } else {  // Have jumped without playing.
                if (beatNumber < 0) {
                    return -1; // We don't know the position any more; weird to get into this state and still have a grid?
                }
                // As a heuristic, assume we are right before the beat? Interfering with correction logic right now.
                // This all needs some serious pondering on more sleep.
                return beatGrid.getTimeWithinTrack(beatNumber);
            }
        }
        long elapsedMillis = (newDeviceUpdate.getTimestamp() - lastTrackUpdate.timestamp) / 1000000;
        long moved = Math.round(lastTrackUpdate.pitch * elapsedMillis);
        long interpolated = (lastTrackUpdate.reverse)?
                (lastTrackUpdate.milliseconds - moved) : lastTrackUpdate.milliseconds + moved;
        if (Math.abs(beatGrid.findBeatAtTime(interpolated) - beatNumber) < 2) {
            return interpolated;  // Our calculations still look plausible
        }
        // The player has jumped or drifted somewhere unexpected, correct.
        if (newDeviceUpdate.isPlayingForwards()) {
            return beatGrid.getTimeWithinTrack(beatNumber);
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
    public long getTimeFor(DeviceUpdate update) {
        return getTimeFor(update.getDeviceNumber());
    }

    /**
     * Keeps track of the listeners that have registered interest in closely following track playback for a particular
     * player. The keys are the listener interface, and the values are the last update that was sent to that
     * listener.
     */
    private final ConcurrentHashMap<TrackPositionListener, TrackPositionUpdate> trackPositionListeners =
            new ConcurrentHashMap<TrackPositionListener, TrackPositionUpdate>();

    /**
     * Keeps track of the player numbers that registered track position listeners are interested in.
     */
    private final ConcurrentHashMap<TrackPositionListener, Integer> listenerPlayerNumbers =
            new ConcurrentHashMap<TrackPositionListener, Integer>();

    /**
     * This is used to represent the fact that we have told a listener that there is no information for it, since
     * we can't actually store a {@code null} value in a {@link ConcurrentHashMap}.
     */
    private final TrackPositionUpdate NO_INFORMATION = new TrackPositionUpdate(0, 0, 0,
            false, false, 0, false, null);

    /**
     * Add a listener that wants to closely follow track playback for a particular player. The listener will be called
     * as soon as there is an initial {@link TrackPositionUpdate} for the specified player, and whenever there is an
     * unexpected change in playback position, speed, or state on that player.
     *
     * @param player the player number that the listener is interested in
     * @param listener the interface that will be called when there are changes in track playback on the player
     */
    public void addTrackPositionListener(int player, TrackPositionListener listener) {
        listenerPlayerNumbers.put(listener, player);
        TrackPositionUpdate currentPosition = positions.get(player);
        if (currentPosition !=  null) {
            listener.movementChanged(currentPosition);
            trackPositionListeners.put(listener, currentPosition);
        } else {
            trackPositionListeners.put(listener, NO_INFORMATION);
        }
    }

    /**
     * Remove a listener that was following track playback movement.
     *
     * @param listener the interface that will no longer be called for changes in track playback
     */
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
     * @return {@code true }if the listener will have diverged by more than our permitted amount of slack, and so
     * should be updated
     */
    private boolean interpolationsDisagree(TrackPositionUpdate lastUpdate, TrackPositionUpdate currentUpdate) {
        long now = System.nanoTime();
        return Math.abs(interpolateTimeSinceUpdate(lastUpdate, now) - interpolateTimeSinceUpdate(currentUpdate, now)) >
                slack.get();
    }

    /**
     * Check if the current position tracking information for a player represents a significant change compared to
     * what a listener was last informed to expect, and if so, send another update.
     *
     * @param player the device number for which an update has occurred
     * @param update the latest track position tracking information for the specified player, or {@code null} if we
     *               no longer have any
     */
    private void updateListenersIfNeeded(int player, TrackPositionUpdate update) {
        // Iterate over a copy to avoid issues with concurrent modification
        for (Map.Entry<TrackPositionListener, TrackPositionUpdate> entry :
                new HashMap<TrackPositionListener, TrackPositionUpdate>(trackPositionListeners).entrySet()) {
            if (player == listenerPlayerNumbers.get(entry.getKey())) {  // This listener is interested in this player
                if (update == null) {  // We are reporting a loss of information
                    if (entry.getValue() != NO_INFORMATION) {
                        if (trackPositionListeners.replace(entry.getKey(), entry.getValue(), NO_INFORMATION)) {
                            try {
                                entry.getKey().movementChanged(null);
                            } catch (Exception e) {
                                logger.warn("Problem delivering null movementChanged update", e);
                            }
                        }
                    }
                } else {  // We have some information, see if it is a significant change from what was last reported
                    final TrackPositionUpdate lastUpdate = entry.getValue();
                    if (lastUpdate == NO_INFORMATION ||
                            lastUpdate.playing != update.playing ||
                            Math.abs(lastUpdate.pitch - update.pitch) > 0.000001 ||
                            interpolationsDisagree(lastUpdate, update)) {
                        if (trackPositionListeners.replace(entry.getKey(), entry.getValue(), update)) {
                            try {
                                entry.getKey().movementChanged(update);
                            } catch (Exception e) {
                                logger.warn("Problem delivering movementChanged update", e);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Reacts to player status updates to update the predicted playback position and state and potentially inform
     * registered track position listeners of significant changes.
     */
    private final DeviceUpdateListener updateListener = new DeviceUpdateListener() {
        @Override
        public void received(DeviceUpdate update) {
            if (update instanceof CdjStatus) {
                updates.put(update.getDeviceNumber(), update);
                final BeatGrid beatGrid = BeatGridFinder.getInstance().getLatestBeatGridFor(update);
                final int beatNumber = ((CdjStatus) update).getBeatNumber();
                // logger.debug("Update: beat " + update.getBeatWithinBar() + " -- " + beatNumber);
                if (beatGrid != null && (beatNumber >= 0)) {
                    boolean done = false;
                    TrackPositionUpdate lastPosition = positions.get(update.getDeviceNumber());
                    while (!done && ((lastPosition == null) || lastPosition.timestamp < update.getTimestamp())) {
                        TrackPositionUpdate newPosition;
                        if (lastPosition == null || lastPosition.beatGrid != beatGrid) {
                            // This is a new track, and we have not yet received a beat packet for it
                            newPosition = new TrackPositionUpdate(update.getTimestamp(),
                                    beatGrid.getTimeWithinTrack(beatNumber), beatNumber, false,
                                    ((CdjStatus) update).isPlaying(),
                                    Util.pitchToMultiplier(update.getPitch()),
                                    ((CdjStatus) update).isPlayingBackwards(), beatGrid);
                        } else {
                            newPosition = new TrackPositionUpdate(update.getTimestamp(),
                                    interpolateTimeFromUpdate(lastPosition, (CdjStatus) update, beatGrid),
                                    beatNumber, false, ((CdjStatus) update).isPlaying(),
                                    Util.pitchToMultiplier(update.getPitch()),
                                    ((CdjStatus) update).isPlayingBackwards(), beatGrid);
                        }
                        if (lastPosition == null) {
                            done = (positions.putIfAbsent(update.getDeviceNumber(), newPosition) == null);
                        } else {
                            done = positions.replace(update.getDeviceNumber(), lastPosition, newPosition);
                        }
                        if (done) {
                            updateListenersIfNeeded(update.getDeviceNumber(), newPosition);
                        }
                    }
                }
            } else {
                positions.remove(update.getDeviceNumber());  // We can't say where that player is.
                updateListenersIfNeeded(update.getDeviceNumber(), null);
            }
        }
    };

    /**
     * Translates a beat number to a track time, given a beat grid. In almost all cases, simply delegates that task
     * to the beat grid. However, since players sometimes report beats that are outside the beat grid (especially when
     * looping tracks that extend for more than a beat's worth of time past the last beat in the beat grid), to avoid
     * weird exceptions and infinitely-growing track time reports in such situations, we extrapolate an extension to
     * the beat grid by repeating the interval between the last two beats in the track.
     *
     * In the completely degenerate case of a track with a single beat (which probably will never occur), we simply
     * return that beat's time even if you ask for a later one.
     *
     * @param beatGrid the times at which known beats fall in the track whose playback time we are reporting.
     *
     * @param beatNumber the number of the beat that a player has just reported reaching, which may be slightly
     *                   greater than the actual number of beats in the track.
     *
     * @return the number of milliseconds into the track at which that beat falls, perhaps extrapolated past the final
     *         recorded beat.
     */
    private long timeOfBeat(BeatGrid beatGrid, int beatNumber) {
        if (beatNumber <= beatGrid.beatCount) {
            return beatGrid.getTimeWithinTrack(beatNumber);
        }
        if (beatGrid.beatCount < 2) {
            return beatGrid.getTimeWithinTrack(1);
        }
        long lastTime = beatGrid.getTimeWithinTrack(beatGrid.beatCount);
        long lastInterval = lastTime - beatGrid.getTimeWithinTrack(beatGrid.beatCount - 1);
        return lastTime + (lastInterval * (beatNumber - beatGrid.beatCount));
    }

    /**
     * Reacts to beat messages to update the definitive playback position for that player
     */
    private final BeatListener beatListener = new BeatListener() {
        @Override
        public void newBeat(Beat beat) {
            if (beat.getDeviceNumber() < 16) {  // We only care about CDJs.
                updates.put(beat.getDeviceNumber(), beat);
                // logger.info("Beat: " + beat.getBeatWithinBar());
                final BeatGrid beatGrid = BeatGridFinder.getInstance().getLatestBeatGridFor(beat);
                if (beatGrid != null) {
                    TrackPositionUpdate lastPosition = positions.get(beat.getDeviceNumber());
                    int beatNumber;
                    boolean definitive;
                    if (lastPosition == null || lastPosition.beatGrid != beatGrid) {
                        // Crazy! We somehow got a beat before any other status update from the player. We have
                        // to assume it was the first beat of the track, we will recover soon.
                        beatNumber = 1;
                        definitive = false;
                    } else {
                        beatNumber = lastPosition.beatNumber + 1;
                        definitive = true;
                    }

                    // We know the player is playing forward because otherwise we don't get beats.
                    final TrackPositionUpdate newPosition = new TrackPositionUpdate(beat.getTimestamp(),
                            timeOfBeat(beatGrid, beatNumber), beatNumber, definitive, true,
                            Util.pitchToMultiplier(beat.getPitch()), false, beatGrid);
                    positions.put(beat.getDeviceNumber(), newPosition);
                    updateListenersIfNeeded(beat.getDeviceNumber(), newPosition);
                } else {
                    positions.remove(beat.getDeviceNumber());  // We can't determine where the player is.
                    updateListenersIfNeeded(beat.getDeviceNumber(), null);
                }
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
            BeatFinder.getInstance().start();
            running.set(true);
            deliverLifecycleAnnouncement(logger, true);
        }
    }

    /**
     * Stop interpolating playback position for all active players.
     */
    @SuppressWarnings("WeakerAccess")
    public synchronized void stop() {
        if (isRunning()) {
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
        if (isRunning()) {
            sb.append(", latestPositions:").append(getLatestPositions());
            sb.append(", latestUpdates:").append(getLatestUpdates());
        }
        return sb.append("]").toString();
    }
}
