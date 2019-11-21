package org.deepsymmetry.beatlink;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Uniquely identifies a device on the network. We used to use just the IP address for this, but the introduction of
 * the XDJ-XZ completely broke that, because it reports two CDJs and a mixer all on the same IP address. This immutable
 * class combines the device number and IP address as a joint key. The factory method ensures that for a given device
 * number and address pair, the same instance will always be returned, so object reference equality can be used to
 * distinguish devices in sets and hash maps.
 *
 * @author James Elliott
 * @since 0.6.0
 */
public class DeviceReference {

    /**
     * The device number reported by the device.
     */
    @SuppressWarnings("WeakerAccess")
    public final int deviceNumber;

    /**
     * The IP address at which the device can be found.
     */
    @SuppressWarnings("WeakerAccess")
    public final InetAddress address;

    /**
     * Create a unique device identifier.
     *
     * @param number the device number reported by the device
     * @param addr the IP address at which the device can be found
     */
    private DeviceReference(int number, InetAddress addr) {
        deviceNumber = number;
        address = addr;
    }

    /**
     * Holds all the instances of this class as they get created by the static factory method.
     */
    private static final Map<InetAddress, Map<Integer, DeviceReference>> instances =
            new HashMap<InetAddress, Map<Integer, DeviceReference>>();

    /**
     * Get a unique device identifier by device number and address.
     *
     * @param number the device number reported by the device
     * @param address the IP address at which the device can be found
     * @return the reference uniquely identifying the device with that number and address
     */
    @SuppressWarnings("WeakerAccess")
    public static synchronized DeviceReference getDeviceReference(int number, InetAddress address) {
        Map<Integer, DeviceReference> playerMap = instances.get(address);
        if (playerMap == null) {
            playerMap = new HashMap<Integer, DeviceReference>();
            instances.put(address, playerMap);
        }
        DeviceReference result = playerMap.get(number);
        if (result == null) {
            result = new DeviceReference(number, address);
            playerMap.put(number, result);
        }
        return result;
    }

    /**
     * Get a unique device identifier corresponding to a device we have received an announcement packet from.
     *
     * @param announcement the device announcement received
     * @return the reference uniquely identifying the device which sent the announcement
     */
    @SuppressWarnings("WeakerAccess")
    public static DeviceReference getDeviceReference(DeviceAnnouncement announcement) {
        return getDeviceReference(announcement.getNumber(), announcement.getAddress());
    }

    /**
     * Get a unique device identifier corresponding to a device we have received an update packet from.
     *
     * @param update the device update received
     * @return the reference uniquely identifying the device which sent the update
     */
    @SuppressWarnings("WeakerAccess")
    public static DeviceReference getDeviceReference(DeviceUpdate update) {
        return getDeviceReference(update.deviceNumber, update.address);
    }

    @Override
    public String toString() {
        return "DeckReference[deviceNumber:" + deviceNumber + ", address:" + address + "]";
    }
}
