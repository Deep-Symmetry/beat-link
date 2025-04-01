package org.deepsymmetry.beatlink;

import org.apiguardian.api.API;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
@API(status = API.Status.STABLE)
public class DeviceReference {

    /**
     * The device number reported by the device.
     */
    @API(status = API.Status.STABLE)
    public final int deviceNumber;

    /**
     * The IP address at which the device can be found.
     */
    @API(status = API.Status.STABLE)
    public final InetAddress address;

    /**
     * We are immutable so we can precompute our hash code.
     */
    private final int hashcode;

    /**
     * Create a unique device identifier.
     *
     * @param number the device number reported by the device
     * @param address the IP address at which the device can be found
     */
    private DeviceReference(int number, InetAddress address) {
        deviceNumber = number;
        this.address = address;
        hashcode = Objects.hash(deviceNumber, address);
    }

    /**
     * Holds all the instances of this class as they get created by the static factory method.
     */
    private static final Map<InetAddress, Map<Integer, DeviceReference>> instances = new HashMap<>();

    /**
     * Get a unique device identifier by device number and address.
     *
     * @param number the device number reported by the device
     * @param address the IP address at which the device can be found
     * @return the reference uniquely identifying the device with that number and address
     */
    @API(status = API.Status.STABLE)
    public static synchronized DeviceReference getDeviceReference(int number, InetAddress address) {
        final Map<Integer, DeviceReference> playerMap = instances.computeIfAbsent(address, k -> new HashMap<>());
        return playerMap.computeIfAbsent(number, n -> new DeviceReference(n, address));
    }

    /**
     * Get a unique device identifier corresponding to a device we have received an announcement packet from.
     *
     * @param announcement the device announcement received
     * @return the reference uniquely identifying the device which sent the announcement
     */
    @API(status = API.Status.STABLE)
    public static DeviceReference getDeviceReference(DeviceAnnouncement announcement) {
        return getDeviceReference(announcement.getDeviceNumber(), announcement.getAddress());
    }

    /**
     * Get a unique device identifier corresponding to a device we have received an update packet from.
     *
     * @param update the device update received
     * @return the reference uniquely identifying the device which sent the update
     */
    @API(status = API.Status.STABLE)
    public static DeviceReference getDeviceReference(DeviceUpdate update) {
        return getDeviceReference(update.deviceNumber, update.address);
    }

    @Override
    public int hashCode() {
        return hashcode;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DeviceReference && ((DeviceReference) obj).deviceNumber == deviceNumber && ((DeviceReference) obj).address.equals(address);
    }

    @Override
    public String toString() {
        return "DeckReference[deviceNumber:" + deviceNumber + ", address:" + address + "]";
    }
}
