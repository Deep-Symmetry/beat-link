package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final ConcurrentHashMap<Integer, TrackPositionUpdate> positions =
            new ConcurrentHashMap<Integer, TrackPositionUpdate>();

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
    private boolean running = false;

    /**
     * Check whether we are currently running.
     *
     * @return true if track playback positions are being kept track of for all active players
     */
    @SuppressWarnings("WeakerAccess")
    public synchronized boolean isRunning() {
        return running;
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
    @SuppressWarnings("WeakerAccess")
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
     *
     * @return the playback position we believe that player has reached now
     */
    private long interpolateTimeSinceUpdate(TrackPositionUpdate update) {
        if (!update.playing) {
            return update.milliseconds;
        }
        long elapsedMillis = (System.nanoTime() - update.timestamp) / 1000000;
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
        }
        return interpolated;  // TODO This isn't right but maybe we should give up on supporting reverse; no beats!
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
    @SuppressWarnings("WeakerAccess")
    public long getTimeFor(int player) {
        TrackPositionUpdate update = positions.get(player);
        if (update != null) {
            return interpolateTimeSinceUpdate(update);
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
     * Reacts to player status updates to update the predicted playback position and state.
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
                            // This is a new track, and we have not yet received a beat packet for it, or a big jump
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
                    }
                }
            } else {
                positions.remove(update.getDeviceNumber());  // We can't say where that player is.
            }
        }
    };

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
                    positions.put(beat.getDeviceNumber(), new TrackPositionUpdate(beat.getTimestamp(),
                            beatGrid.getTimeWithinTrack(beatNumber), beatNumber, definitive, true,
                            Util.pitchToMultiplier(beat.getPitch()), false, beatGrid));
                } else {
                    positions.remove(beat.getDeviceNumber());  // We can't determine where the player is.
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
        if (!running) {
            DeviceFinder.getInstance().addDeviceAnnouncementListener(announcementListener);
            BeatGridFinder.getInstance().addLifecycleListener(lifecycleListener);
            BeatGridFinder.getInstance().start();
            VirtualCdj.getInstance().addUpdateListener(updateListener);
            VirtualCdj.getInstance().addLifecycleListener(lifecycleListener);
            VirtualCdj.getInstance().start();
            BeatFinder.getInstance().addLifecycleListener(lifecycleListener);
            BeatFinder.getInstance().addBeatListener(beatListener);
            BeatFinder.getInstance().start();
            running = true;
            deliverLifecycleAnnouncement(logger, true);
        }
    }

    /**
     * Stop interpolating playback position for all active players.
     */
    @SuppressWarnings("WeakerAccess")
    public synchronized void stop() {
        if (running) {
            BeatFinder.getInstance().removeBeatListener(beatListener);
            VirtualCdj.getInstance().removeUpdateListener(updateListener);
            running = false;
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
