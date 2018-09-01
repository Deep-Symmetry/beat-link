package org.deepsymmetry.beatlink;

import org.deepsymmetry.electro.Metronome;
import org.deepsymmetry.electro.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the thread that sends beat packets when the {@link VirtualCdj} is sending status and playing.
 */
class BeatSender {

    private static final Logger logger = LoggerFactory.getLogger(BeatSender.class);

    /**
     * Becomes false when we are told to shut down, so that the thread knows to stop.
     */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * Holds the thread that runs the beat-sending loop, so we can interrupt it when the metronome configuration
     * changes, or when it is time to shut down.
     */
    private final Thread thread;

    /**
     * Holds the metronome that allows us to determine when beat packets are due.
     */
    private final Metronome metronome;

    /**
     * Holds the beat, if any, that we just sent, so we know not to send it again if for some reason the thread
     * gets awakened near the start of the same beat.
     */
    private final AtomicReference<Long> lastBeatSent = new AtomicReference<Long>();

    /**
     * How far into a beat can we find ourselves, in milliseconds, and still consider it timely to send the beat packet
     * (assuming we haven't already sent one for this beat).
     */
    public static final long BEAT_THRESHOLD = 10;

    /**
     * If we are this close to the next beat, we will busy-wait rather than sleeping so we can send it at a more
     * accurate time. We will also aim our sleep to land this far before the next beat, to try and absorb threading
     * jitter.
     */
    private static final int SLEEP_THRESHOLD = 5;

    /**
     * The loop that is run by the beat sender thread, sending beats at appropriate intervals.
     */
    @SuppressWarnings("FieldCanBeLocal")
    private final Runnable beatLoop = new Runnable() {
        @Override
        public void run() {

            while (running.get()) {
                Snapshot snapshot = metronome.getSnapshot();

                if (lastBeatSent.get() != null &&
                        ((snapshot.getBeatPhase() > 0.5) || (snapshot.getBeat() != lastBeatSent.get()))) {
                    lastBeatSent.set(null);  // We are no longer at the start of the same beat, so are armed to send.
                }

                // Determine when the current and next beats belong
                final long currentBeatDue = snapshot.getTimeOfBeat(snapshot.getBeat());
                final long nextBeatDue = snapshot.getTimeOfBeat(snapshot.getBeat() + 1);

                // Is it time to send a beat?
                final long distanceIntoCurrentBeat = snapshot.getInstant() - currentBeatDue;
                if (distanceIntoCurrentBeat < BEAT_THRESHOLD &&
                        (lastBeatSent.get() == null || lastBeatSent.get() != snapshot.getBeat())) {
                    //logger.info("Sending beat " + snapshot.getBeat() + ", " + distanceIntoCurrentBeat + " ms into beat.");
                    lastBeatSent.set(VirtualCdj.getInstance().sendBeat(snapshot));
                }

                final long sleepMilliseconds = nextBeatDue - System.currentTimeMillis();
                if (sleepMilliseconds > SLEEP_THRESHOLD) {  // Long enough to try actually sleeping until we are closer to due
                    try {
                        Thread.sleep(sleepMilliseconds - SLEEP_THRESHOLD);
                    } catch (InterruptedException e) {
                        logger.info("BeatSender thread interrupted, re-evaluating time until next beat.");
                    }
                } else {  // Close enough to busy-wait until it is time to send the beat.
                    final long targetTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(sleepMilliseconds);
                    //noinspection StatementWithEmptyBody
                    while (!Thread.interrupted() && (System.nanoTime() < targetTime)) {
                        // Busy-waiting, woo hoo, hope you didn't need this core!
                    }
                }
            }
        }
    };

    /**
     * Must be called whenever the tempo changes or our position on the timeline is nudged so the loop can recompute
     * when the next beat is due and react appropriately.
     */
    void timelineChanged() {
        if (!running.get()) {
            logger.warn("BeatSender ignoring timelineChanged() call because it has been shut down.");
            return;
        }
        thread.interrupt();
    }

    /**
     * Tells the beat sending thread to shut down. The BeatSender can no longer be used once this is called.
     */
    void shutDown() {
        running.set(false);
        thread.interrupt();
    }

    /**
     * Create and start the beat sending thread.
     *
     * @param metronome determines when beats need to be sent
     *
     */
    BeatSender(Metronome metronome) {
        this.metronome = metronome;
        thread = new Thread(beatLoop, "beat-link VirtualCdj beat sender");
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.setDaemon(true);
        thread.start();
    }
}
