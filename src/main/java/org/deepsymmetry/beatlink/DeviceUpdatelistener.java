package org.deepsymmetry.beatlink;

/**
 * The listener interface for receiving all device updates. Classes that are interested in knowing when DJ Link
 * devices report detailed status can implement this interface. The listener object created from that class is
 * then registered using
 * {@link }.  Whenever a new device is found,
 * or a device disappears from the network, the relevant method in the listener object is invoked, and the
 * {@link DeviceUpdate} is passed to it.
 *
 * @author James Elliott
 */
public interface DeviceUpdateListener {

    /**
     * Invoked whenever a device status update is received by {@link VirtualCdj}. Currently the update will either be
     * a {@link MixerStatus} or a {@link CdjStatus}, but more varieties may be added as the protocol analysis deepens.
     *
     * @param update the status update which has just arrived
     */
    void received(DeviceUpdate update);

}
