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
public interface DeviceUpdateListener {

    /**
     * Invoked whenever a device status update is received by {@link VirtualCdj}. Currently the update will either be
     * a {@link MixerStatus} or a {@link CdjStatus}, but more varieties may be added as the protocol analysis deepens.
     *
     * <p>Device updates are delivered to listeners on the
     * <a href="https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html">Event Dispatch thread</a>,
     * so it is fine to interact with user interface objects in listener methods. Any code in the listener method
     * must finish quickly, or unhandled events will back up and the user interface will be come unresponsive.</p>
     *
     * @param update the status update which has just arrived
     */
    void received(DeviceUpdate update);

}
