package org.deepsymmetry.beatlink;

/**
 * The listener interface for receiving detailed updates from all devices.
 * Classes that are interested in knowing when DJ Link devices report detailed status can implement this interface.
 * The listener object created from that class is then registered using
 * {@link VirtualCdj#addUpdateListener(DeviceUpdateListener)}.  Whenever a device update is received,
 * {@link #received(DeviceUpdate)} is invoked with it.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public interface DeviceUpdateListener {

    /**
     * <p>Invoked whenever a device status update is received by {@link VirtualCdj}. Currently the update will
     * either be a {@link MixerStatus} or a {@link CdjStatus}, but more varieties may be added as the protocol analysis
     * deepens.</p>
     *
     * <p>To reduce latency, device updates are delivered to listeners directly on the thread that is receiving them
     * from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and device updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param update the status update which has just arrived
     */
    void received(DeviceUpdate update);

}
