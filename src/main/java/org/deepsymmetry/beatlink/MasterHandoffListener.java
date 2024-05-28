package org.deepsymmetry.beatlink;

import org.apiguardian.api.API;

/**
 * <p>The listener interface for receiving tempo master handoff messages. Classes that are interested in knowing when they
 * are being instructed to yield the tempo master role to another device, or when a device they have asked to yield it
 * to them has responded, can implement this interface.</p>
 *
 * <p>The listener object created is then registered using {@link BeatFinder#addMasterHandoffListener(MasterHandoffListener)}.
 * Whenever a relevant message is received, the {@link #yieldMasterTo(int)} or {@link #yieldResponse(int, boolean)}
 * method in the listener object is invoked.</p>
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public interface MasterHandoffListener {

    /**
     * Invoked when we have received a message asking us to yield the tempo master role to another device.
     *
     * <p>To reduce latency, handoff messages are delivered to listeners directly on the thread that is receiving them
     * them from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param deviceNumber identifies the device that we are supposed to hand the tempo master role to
     */
    @API(status = API.Status.STABLE)
    void yieldMasterTo(int deviceNumber);

    /**
     * Invoked when we have received a response from a device we have asked to yield the tempo master role to us.
     *
     * <p>To reduce latency, sync commands are delivered to listeners directly on the thread that is receiving them
     * them from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param deviceNumber identifies the device that is agreeing to hand the tempo master role to us
     * @param yielded will be {@code true} to indicate it is time for us to be the tempo master
     */
    @API(status = API.Status.STABLE)
    void yieldResponse(int deviceNumber, boolean yielded);

}
