package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;

/**
 * <p>The listener interface for receiving updates when the set of mounted media slots on the network changes.</p>
 *
 * <p>Classes that are interested having up-to-date information about which players have media loaded into their media
 * slots can implement this interface, and then pass the implementing instance to
 * {@link MetadataFinder#addMountListener(MountListener)}.
 * Then, whenever a player mounts media in one of its slots, {@link #mediaMounted(SlotReference)} will be called, with
 * the appropriate slot reference, and whenever a player unmounts media, {@link #mediaUnmounted(SlotReference)} will
 * be called.</p>
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public interface MountListener {

    /**
     * Report that media is newly available in the specified player slot.
     *
     * @param slot uniquely identifies a media slot on the network.
     */
    @API(status = API.Status.STABLE)
    void mediaMounted(SlotReference slot);

    /**
     * Report that media is no longer available in the specified player slot.
     *
     * @param slot uniquely identifies a media slot on the network.
     */
    @API(status = API.Status.STABLE)
    void mediaUnmounted(SlotReference slot);
}
