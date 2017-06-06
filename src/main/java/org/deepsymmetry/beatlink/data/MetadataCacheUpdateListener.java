package org.deepsymmetry.beatlink.data;

import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * The listener interface for receiving updates when the set of attached metadata caches changes.
 *
 * Classes that are interested displaying up-to-date information about attached metadata caches can implement this
 * interface, and then pass the implementing instance to {@link MetadataFinder#addCacheUpdateListener(MetadataCacheUpdateListener)}.
 * Then, whenever a metadata cache is attached or detached, {@link #cacheStateChanged(Map)} will be called, with the
 * current mapping of player numbers to available metadata cache files.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public interface MetadataCacheUpdateListener {
    /**
     * Invoked whenever there is a change in the attached metadata caches.
     *
     * <p>To reduce latency, updates are delivered to listeners directly on the thread that is receiving packets
     * from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and device updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param caches every player that has a cache attached for one of its media slot will be represented by an entry
     *               in this map, with the key identifying the player and slot, and the value being the cache file
     *               itself
     */
    void cacheStateChanged(Map<SlotReference, ZipFile> caches);
}
