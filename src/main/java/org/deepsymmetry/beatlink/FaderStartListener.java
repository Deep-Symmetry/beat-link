package org.deepsymmetry.beatlink;

import org.apiguardian.api.API;

import java.util.Set;

/**
 * The listener interface for receiving fader start commands. Classes that are interested in knowing when the
 * mixer broadcasts commands telling players to start or stop can implement this interface.
 * The listener object created is then registered using {@link BeatFinder#addFaderStartListener(FaderStartListener)}.
 * Whenever a relevant message is received, the {@link #fadersChanged(Set, Set)} method in the listener object
 * is invoked.
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public interface FaderStartListener {

    /**
     * Invoked when we have received a message telling us which players should start and stop playing.
     *
     * <p>To reduce latency, on-air updates are delivered to listeners directly on the thread that is receiving them
     * them from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param playersToStart contains the device numbers of all players that should start playing
     * @param playersToStop contains the device numbers of all players that should stop playing
     */
    @API(status = API.Status.STABLE)
    void fadersChanged(Set<Integer>playersToStart, Set<Integer> playersToStop);

}
