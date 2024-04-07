package org.deepsymmetry.beatlink;

/**
 * <p>The listener interface for receiving updates about changes to tempo master state. Classes that are interested in
 * knowing when a different device becomes tempo master, the tempo master starts a new beat, or the master tempo itself
 * changes can either implement this interface (and all the methods it contains) or extend the abstract
 * {@link MasterAdapter} class (overriding only the methods of interest).
 * The listener object created from that class is then registered using
 * {@link VirtualCdj#addMasterListener(MasterListener)}. Whenever a relevant change occurs, the appropriate method
 * in the listener object is invoked.</p>
 *
 * <p>Note that in order for beats to be reported, the {@link BeatFinder} must be active as well.</p>
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public interface MasterListener extends BeatListener {

    /**
     * <p>Invoked when there is a change in which device is the current tempo master.</p>
     *
     * <p>To reduce latency, tempo master updates are delivered to listeners directly on the thread that is receiving them
     * from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and master updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param update the message identifying the new master, or {@code null} if there is now none
     */
    @SuppressWarnings("EmptyMethod")
    void masterChanged(DeviceUpdate update);

    /**
     * <p>Invoked when the master tempo has changed.</p>
     *
     * <p>To reduce latency, tempo master updates are delivered to listeners directly on the thread that is receiving them
     * from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and master updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param tempo the new master tempo
     */
    @SuppressWarnings("EmptyMethod")
    void tempoChanged(double tempo);

}
