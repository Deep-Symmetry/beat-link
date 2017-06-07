package org.deepsymmetry.beatlink;

/**
 * The listener interface for receiving beat announcements. Classes that are interested in knowing when DJ Link
 * devices report beats can implement this interface. The listener object created from that class is
 * then registered using {@link BeatFinder#addBeatListener(BeatListener)}.
 * Whenever a new beat starts, the {@link #newBeat(Beat)} method in the listener object is invoked with it.
 *
 * @author James Elliott
 */
public interface BeatListener {

    /**
     * <p>Invoked when a beat is reported on the network. Even though beats contain
     * far less detailed information than status updates, they can be passed to
     * {@link VirtualCdj#getLatestStatusFor(DeviceUpdate)} to find the current detailed status for that device,
     * as long as the Virtual CDJ is active.</p>
     *
     * <p>To reduce latency, beat announcements are delivered to listeners directly on the thread that is receiving them
     * them from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param beat the message which announced the start of the new beat
     */
    @SuppressWarnings("EmptyMethod")
    void newBeat(Beat beat);

}
