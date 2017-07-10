package org.deepsymmetry.beatlink.data;

/**
 * <p>The listener interface for receiving updates when there are significant changes to the movement through
 * a track on a player (for example, to send time code that represents the progress of playing the track).</p>
 *
 * <p>Classes that are interested in being informed when unexpected movement or changes in playback state occur on
 * a player can implement this
 * interface, and then pass the implementing instance to
 * {@link TimeFinder#addTrackPositionListener(int, TrackPositionListener)}.
 * Then, whenever a player loads a new track (or the set of available metadata changes, so we know more or less about
 * tracks in any loaded player), {@link #movementChanged(TrackPositionUpdate)} will be called, with the currently
 * available position and movement information about the track being played by the player.</p>
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public interface TrackPositionListener {
    /**
     * Called when there has been a significant change in movement since the last reported change. This method should
     * not perform significant work that would block the calling thread; any such operations must be delegated to a
     * different thread.
     *
     * @param update the latest information about the current track position and playback state and speed, or
     *               {@code null} if we can no longer determine that information
     */
    void movementChanged(TrackPositionUpdate update);
}
