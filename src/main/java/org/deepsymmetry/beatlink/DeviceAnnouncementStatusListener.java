package org.deepsymmetry.beatlink;

/**
 * The listener interface for receiving device announcements. Classes that are interested in knowing when DJ Link
 * devices are discovered or drop off the network can either implement this interface (and all the methods it contains)
 * or extend the abstract {@link DeviceAnnouncementAdapter} class (overriding only the methods of interest). The
 * listener object created from that class is then registered using
 * {@link DeviceFinder#addDeviceAnnouncementListener(DeviceAnnouncementStatusListener)}.  Whenever a new device is found,
 * or a device disappears from the network, the relevant method in the listener object is invoked, and the
 * {@link DeviceAnnouncement} is passed to it.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public interface DeviceAnnouncementStatusListener {

    /**
     * Invoked when a new DJ Link device is heard from on the network.
     *
     * <p>Device announcements are delivered to listeners on the
     * <a href="https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html">Event Dispatch thread</a>,
     * so it is fine to interact with user interface objects in this method. Any code in this method
     * must finish quickly, or unhandled events will back up and the user interface will be come unresponsive.</p>
     *
     * @param announcement the message which announced the device's presence
     */
    @SuppressWarnings("UnusedReturnValue")
    void deviceFound(DeviceAnnouncement announcement);

    /**
     * Invoked when a DJ Link device is no longer seen on the network.
     *
     * <p>Device announcements are delivered to listeners on the
     * <a href="https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html">Event Dispatch thread</a>,
     * so it is fine to interact with user interface objects in this method. Any code in this method
     * must finish quickly, or unhandled events will back up and the user interface will be come unresponsive.</p>
     *
     * @param announcement the last message which was sent by the device before it disappeared
     */
    @SuppressWarnings("UnusedReturnValue")
    void deviceLost(DeviceAnnouncement announcement);
}
