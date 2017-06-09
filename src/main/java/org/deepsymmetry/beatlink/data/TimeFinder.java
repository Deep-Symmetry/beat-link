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
     * Keeps track of the most recent information we have received from a player from which we have been able to compute
     * a track position.
     */
    public static class TrackPositionUpdate {

        /**
         * When this update was received.
         */
        public final long timestamp;

        /**
         * How far into the track has the player reached.
         */
        public final long milliseconds;

        /**
         * The beat number that was reported (or incremented) by this update
         * The beat number that was reported (or incremented) by this update
         */
        public final int beatNumber;

        /**
         * If {@code true}, this was created in response to a beat packet, so we know exactly where the player was at that
         * point. Otherwise, we infer position based on how long has elapsed since the previous beat packet, and the
         * intervening playback pitch and direction.
         */
        public final boolean definitive;

        /**
         * If {@code true}, the player reported that it was playing when the update was received.
         */
        public final boolean playing;

        /**
         * The playback pitch when this update was created.
         */
        public final double pitch;

        /**
         * If {@code true}, the player was playing backwards when this update was created.
         */
        public final boolean reverse;

        /**
         * The track metadata against which this update was calculated, so that if it has changed, we know to discard
         * the update.
         */
        public final BeatGrid beatGrid;

        /**
         * Constructor simply sets the fields of this immutable value class.
         *
         * @param timestamp when this update was received
         * @param milliseconds how far into the track has the player reached
         * @param beatNumber the beat number that was reported (or incremented) by this update
         * @param definitive indicates if this was based on a direct report of track position from the player (i.e. a beat)
         * @param playing indicates whether the player was actively playing a track when this update was received
         * @param pitch the playback pitch (where 1.0 is normal speed) when this update was received
         * @param reverse indicates if the player was playing backwards when this update was received
         * @param beatGrid the track beat grid that was used to calculate the update
         */
        public TrackPositionUpdate(long timestamp, long milliseconds, int beatNumber, boolean definitive,
                                   boolean playing, double pitch, boolean reverse, BeatGrid beatGrid) {
            this.timestamp = timestamp;
            this.milliseconds = milliseconds;
            this.beatNumber = beatNumber;
            this.definitive = definitive;
            this.playing = playing;
            this.pitch = pitch;
            this.reverse = reverse;
            this.beatGrid = beatGrid;
        }

        @Override
        public String toString() {
            return "TrackPositionUpdate[timestamp:" + timestamp + ", milliseconds:" + milliseconds +
                    ", beatNumber:" + beatNumber + ", definitive:" + definitive + ", playing:" + playing +
                    ", pitch:" + String.format("%.2f", pitch) + ", reverse:" + reverse +
                    ", beatGrid:" + beatGrid + "]";
        }
    }

    /**
     * Keeps track of the latest position information we have from each player, indexed by player number.
     */
    private final ConcurrentHashMap<Integer, TrackPositionUpdate> positions =
            new ConcurrentHashMap<Integer, TrackPositionUpdate>();

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
     * @return the details associated with all current players, including for any tracks loaded in their hot cue slots
     *
     * @throws IllegalStateException if the TimeFinder is not running
     */
    @SuppressWarnings("WeakerAccess")
    public Map<Integer, TrackPositionUpdate> getLatestUpdates() {
        ensureRunning();
        return Collections.unmodifiableMap(new HashMap<Integer, TrackPositionUpdate>(positions));
    }

    /**
     * Figure out, based on how much time has elapsed since we received an update, and the playback position,
     * speed, and direction at the time of that update, where the player will be at the specified time.
     *
     * @param update the most recent update received from a player
     * @param timestamp the time at which we'd like to predict the player location (a nano-time)
     *
     * @return the playback position we believe that player has reached at that point in time
     */
    private long interpolateTimeFromUpdate(TrackPositionUpdate update, long timestamp) {
        if (!update.playing) {
            return update.milliseconds;
        }
        long elapsedMillis = (timestamp - update.timestamp) / 1000000;
        long moved = Math.round(update.pitch * elapsedMillis);
        if (update.reverse) {
            return update.milliseconds - moved;
        }
        return update.milliseconds + moved;
    }


    /**
     * Figure out, based on how much time has elapsed since we received an update, and the playback position,
     * speed, and direction at the time of that update, where the player will be now.
     *
     * @param update the most recent update received from a player
     *
     * @return the playback position we believe that player has reached now
     */
    private long interpolateTimeFromUpdate(TrackPositionUpdate update) {
        return interpolateTimeFromUpdate(update, System.nanoTime());
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
            return interpolateTimeFromUpdate(update);
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
                final BeatGrid beatGrid = BeatGridFinder.getInstance().getLatestBeatGridFor(update);
                final int beatNumber = ((CdjStatus) update).getBeatNumber();
                // logger.debug("Update: beat " + update.getBeatWithinBar() + " -- " + beatNumber);
                if (beatGrid != null && (beatNumber > 0)) {
                    boolean done = false;
                    TrackPositionUpdate lastPosition = positions.get(update.getDeviceNumber());
                    while (!done && ((lastPosition == null) || lastPosition.timestamp < update.getTimestamp())) {
                        TrackPositionUpdate newPosition;
                        if (lastPosition == null || lastPosition.beatGrid != beatGrid) {
                            // This is a new track, and we have not yet received a beat packet for it
                            newPosition = new TrackPositionUpdate(update.getTimestamp(),
                                    beatGrid.getTimeWithinTrack(beatNumber), beatNumber, false,
                                    ((CdjStatus) update).isPlaying(),
                                    Util.pitchToMultiplier(((CdjStatus) update).getPitch()),
                                    ((CdjStatus) update).isPlayingBackwards(), beatGrid);
                        } else {
                            // We have moved on in a track we already have a position for
                            newPosition = new TrackPositionUpdate(update.getTimestamp(),
                                    interpolateTimeFromUpdate(lastPosition, update.getTimestamp()),
                                    beatNumber, false, ((CdjStatus) update).isPlaying(),
                                    Util.pitchToMultiplier(((CdjStatus) update).getPitch()),
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
            sb.append(", latestUpdates:").append(getLatestUpdates());
        }
        return sb.append("]").toString();
    }
}
