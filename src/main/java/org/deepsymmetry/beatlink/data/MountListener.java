package org.deepsymmetry.beatlink.data;

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
@SuppressWarnings("WeakerAccess")
public interface MountListener {

    /**
     * Report that media is newly available in the specified player slot.
     *
     * @param slot uniquely identifies a media slot on the network.
     */
    void mediaMounted(SlotReference slot);

    /**
     * Report that media is no longer available in the specified player slot.
     *
     * @param slot uniquely identifies a media slot on the network.
     */
    void mediaUnmounted(SlotReference slot);
}
