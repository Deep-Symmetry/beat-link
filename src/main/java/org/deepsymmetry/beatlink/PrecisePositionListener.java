package org.deepsymmetry.beatlink;

/**
 * The listener interface for receiving precise position announcements. Classes that are interested in knowing
 * when DJ Link devices (at the time of writing, only CDJ-3000s) report their precise position can implement this
 * interface. The listener object created from that class is then registered using
 * {@link BeatFinder#addPrecisePositionListener(PrecisePositionListener)}. Whenever a position report beat arrives,
 * the {@link #positionReported(PrecisePosition)} method in the listener object is invoked with it.
 *
 * @author James Elliott
 */
public interface PrecisePositionListener {

    /**
     * <p>Invoked when a precise player position is reported on the network.</p>
     *
     * <p>To reduce latency, precise position announcements are delivered to listeners directly on the thread
     * that is receiving them them from the network, so if you want to interact with user interface objects in
     * this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     * <p>
     * Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and precise position announcements
     * (which arrive very frequently, at roughly 30 millisecond intervals from players that send them) will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param position the message containing precise position information for a player
     */
    void positionReported(PrecisePosition position);
}
