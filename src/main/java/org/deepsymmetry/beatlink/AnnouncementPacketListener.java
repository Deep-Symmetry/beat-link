package org.deepsymmetry.beatlink;

/**
 * The listener interface for receiving device announcements. This interface is for lower level DeviceAnnouncements
 * from the AnnouncementSocketConnection class and does not have access to the device list to know if we should send
 * device found or lost messages. To listen for device found and lost messages, register a DeviceAnnouncementStatusListener
 * in DeviceFinder.
 *
 */
@SuppressWarnings("WeakerAccess")
public interface AnnouncementPacketListener {

    /**
     * Invoked when a new DJ Link packet is received on the announcement port.
     *
     * <p>Device announcements are delivered to listeners on the
     * <a href="https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html">Event Dispatch thread</a>,
     * so it is fine to interact with user interface objects in this method. Any code in this method
     * must finish quickly, or unhandled events will back up and the user interface will be come unresponsive.</p>
     *
     * @param announcement the message which announced the device's presence
     */
    void handleAnnouncementPacket(DeviceAnnouncement announcement);

}
