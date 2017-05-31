package org.deepsymmetry.beatlink;

import java.util.Map;
import java.util.zip.ZipFile;

/**
 * The listener interface for receiving updates when the set of available metadata caches changes.
 *
 * Classes that are interested displaying up-to-date information about attached metadata caches can implement this
 * interface, and then pass the implementing instance to {@link MetadataFinder#addCacheUpdateListener(MetadataCacheUpdateListener)}.
 * Then, whenever a metadata cache is attached or detached, {@link #cachesChanged(Map, Map)} will be called, with the
 * current mapping of player numbers to available metadata cache files.
 *
 * @author James Elliott
 */
public interface MetadataCacheUpdateListener {
    void cachesChanged(Map<Integer, ZipFile> sdCaches, Map<Integer, ZipFile> usbCaches);
}
