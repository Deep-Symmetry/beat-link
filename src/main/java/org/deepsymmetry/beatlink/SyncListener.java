package org.deepsymmetry.beatlink;

import org.apiguardian.api.API;

/**
 * The listener interface for receiving sync control messages. Classes that are interested in knowing when they
 * are being instructed to turn sync mode on or off, or to become the tempo master, can implement this interface.
 * The listener object created is then registered using {@link BeatFinder#addSyncListener(SyncListener)}.
 * Whenever a relevant message is received, the {@link #setSyncMode(boolean)} or {@link #becomeMaster()} method
 * in the listener object is invoked.
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public interface SyncListener {

    /**
     * Invoked when we have received a message telling us to turn sync mode on or off.
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
     * @param synced will be {@code true} when we should turn sync mode on
     */
    @API(status = API.Status.STABLE)
    void setSyncMode(boolean synced);

    /**
     * Invoked when we have received a message telling us to take over the role of tempo master.
     *
     * <p>To reduce latency, sync commands are delivered to listeners directly on the thread that is receiving them
     * them from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     */
    @API(status = API.Status.STABLE)
    void becomeMaster();

}
