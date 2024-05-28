package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.Beat;

/**
 * <p>The listener interface for receiving updates when a new beat has occurred during track playback, when you want
 * to know the actual new beat number (the beat packet itself does not carry this information, but the
 * {@link TimeFinder} integrates it for you).</p>
 *
 * <p>Registering this extension of the {@link TrackPositionListener} interface with
 * {@link TimeFinder#addTrackPositionListener(int, TrackPositionListener)} will arrange for
 * {@link #newBeat(Beat, TrackPositionUpdate)} to be called whenever a player sends a beat, even if this does
 * not represent an unexpected change in playback position
 * (so {@link TrackPositionListener#movementChanged(TrackPositionUpdate)} would not be called).</p>
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public interface TrackPositionBeatListener extends TrackPositionListener {

    /**
     * <p>Invoked when a beat is reported by a player for which we have a {@link BeatGrid}. The raw beat update
     * is available in {@code beat}, and calculated beat number of the beat which is just beginning (as well as the
     * playback position within the track that this represents) can be found in {@code position}.</p>
     *
     * <p>To reduce latency, beat announcements are delivered to listeners directly on the thread that is receiving them
     * them from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param beat the message which announced the start of the new beat
     * @param update the latest information about the current track position and playback state and speed
     */
    @API(status = API.Status.STABLE)
    void newBeat(Beat beat, TrackPositionUpdate update);
}
