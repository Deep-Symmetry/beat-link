package org.deepsymmetry.beatlink;

/**
 * <p>An abstract adapter class for receiving device announcements. The methods in this class are empty; it exists as a
 * convenience for creating listener objects.</p>
 *
 * <p>Extend this class to create a {@link DeviceAnnouncementListener} and override only the methods for events that you
 * care about. If you plan to implement all the methods in the interface, you might as well implement
 * {@link DeviceAnnouncementListener} directly.</p>
 *
 * <p>Create a listener object using your extended class and then register it using
 * {@link DeviceFinder#addDeviceAnnouncementListener(DeviceAnnouncementListener)}. Whenever a new device is found,
 * or a device disappears from the network, the relevant method in the listener object is invoked, and the
 * {@link DeviceAnnouncement} is passed to it.</p>
 *
 * @author  James Elliott
 */
public abstract class DeviceAnnouncementAdapter implements DeviceAnnouncementListener {

    @Override
    public void deviceFound(DeviceAnnouncement announcement) {

    }

    @Override
    public void deviceLost(DeviceAnnouncement announcement) {

    }
}
