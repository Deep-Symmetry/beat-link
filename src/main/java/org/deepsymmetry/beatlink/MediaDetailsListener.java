package org.deepsymmetry.beatlink;

import org.deepsymmetry.beatlink.data.MetadataFinder;
import org.deepsymmetry.beatlink.data.SlotReference;

/**
 * The listener interface for receiving media detail responses when the {@link VirtualCdj} has been told to ask for
 * information about what is in a player’s media slot. The {@link org.deepsymmetry.beatlink.data.MetadataFinder} uses
 * this to keep its information current; most people will probably only need to rely on that, through the
 * {@link org.deepsymmetry.beatlink.data.MetadataFinder#getMediaDetailsFor(SlotReference)} and
 * {@link MetadataFinder#getMountedMediaDetails()} methods. But if you want to obtain that information without
 * starting the MetadataFinder, you can implement this interface, register it using
 * {@link UpdateSocketConnection#addMediaDetailsListener(MediaDetailsListener)}, and then call
 * {@link VirtualCdj#sendMediaQuery(SlotReference)} yourself.
 *
 * @author James Elliott
 */
public interface MediaDetailsListener {

    /**
     * <p>Invoked when a media details response message is received by the {@link VirtualCdj} from a player.</p>
     *
     * <p>To reduce latency, detail announcements are delivered to listeners directly on the thread that is receiving them
     * them from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and detail announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param details the information describing the media mounted in a player slot
     */
    void detailsAvailable(MediaDetails details);

}
