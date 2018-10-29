package org.deepsymmetry.beatlink.data;

/**
 * <p>The listener interface for receiving updates when the set of attached metadata caches changes.</p>
 *
 * <p>Classes that are interested displaying up-to-date information about attached metadata caches can implement this
 * interface, and then pass the implementing instance to {@link MetadataFinder#addCacheListener(MetadataCacheListener)}.
 * Then, when a new metadata cache is attached, {@link #cacheAttached(SlotReference, MetadataCache)} will be called,
 * identifying the slot for which a cache is now available, and the cache file itself. When a cache is detached,
 * {@link #cacheDetached(SlotReference)} will be called to report that the cache will no longer be used for that slot.
 * </p>
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public interface MetadataCacheListener {
    /**
     * <p>Invoked whenever a metadata cache is attached, so the player does not need to be queried when metadata
     * is desired for tracks in that slot.</p>
     *
     * <p>To reduce latency, updates are delivered to listeners directly on the thread that is receiving packets
     * from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and device updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param slot uniquely identifies a media slot on the network from which metadata can be requested
     * @param cache the cache file which has just been attached to provide metadata for the slot
     */
    void cacheAttached(SlotReference slot, MetadataCache cache);

    /**
     * <p>Invoked whenever a metadata cache is detached, so metadata must be obtained by querying the player.</p>
     *
     * <p>To reduce latency, updates are delivered to listeners directly on the thread that is receiving packets
     * from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and device updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param slot uniquely identifies a media slot on the network from which metadata can be requested
     */
    void cacheDetached(SlotReference slot);
}
