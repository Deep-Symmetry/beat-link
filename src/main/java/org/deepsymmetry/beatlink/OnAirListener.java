package org.deepsymmetry.beatlink;

import java.util.Set;

/**
 * The listener interface for receiving on-air status messages. Classes that are interested in knowing when the
 * mixer reports which channels are on and off the air (audible in its audio output) can implement this interface.
 * The listener object created is then registered using {@link BeatFinder#addOnAirListener(OnAirListener)}.
 * Whenever a relevant message is received, the {@link #channelsOnAir(Set)} method in the listener object is invoked.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public interface OnAirListener {

    /**
     * Invoked when we have received a message telling us which channels are currently on the air. A channel may be off
     * the air because of the cross fader the channel fader, or the mixer being configured to use a different kind of
     * input (such as USB) for that channel, rather than a CDJ.
     *
     * <p>To reduce latency, on-air updates are delivered to listeners directly on the thread that is receiving them
     * them from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param audibleChannels holds the device numbers of all channels that can currently be heard in the mixer output
     */
    void channelsOnAir(Set<Integer> audibleChannels);

}
