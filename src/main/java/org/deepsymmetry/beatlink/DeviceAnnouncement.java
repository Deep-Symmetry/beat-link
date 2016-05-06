package org.deepsymmetry.beatlink;

import java.net.InetAddress;

/**
 * Represents a device announcement packet seen on a DJ Link network.
 *
 * @author James Elliott
 */
public class DeviceAnnouncement {

    /**
     * The address on which this device was seen.
     */
    private final InetAddress address;

    /**
     * The last time the device was heard from.
     */
    private final long timestamp;

    /**
     * The name reported by the device.
     */
    private final String name;

    /**
     * The player/device number reported by the device.
     */
    private final int number;

    /**
     * The packet data containing the device announcement.
     */
    private final byte[] packet;

    /**
     * Constructor simply sets all the immutable fields.
     *
     * @param address the address on which this device announcement was seen.
     * @param packet the device announcement packet data.
     */
    public DeviceAnnouncement(InetAddress address, byte[] packet) {
        if (packet.length != 54) {
            throw new IllegalArgumentException("Device announcement packet must be 54 bytes long");
        }
        this.address = address;
        this.packet = new byte[54];
        System.arraycopy(packet, 0, this.packet, 0, 54);
        this.timestamp = System.currentTimeMillis();
        this.name = new String(packet, 12, 20).trim();
        this.number = Util.unsign(packet[36]);
    }

    /**
     * The address on which this device was seen.
     */
    public InetAddress getAddress() {
        return address;
    }

    /**
     * The last time the device was heard from.
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * The name reported by the device.
     */
    public String getName() {
        return name;
    }

    /**
     * The player/device number reported by the device.
     */
    public int getNumber() {
        return number;
    }

    /**
     * The MAC address reported by the device.
     */
    public byte[] getHardwareAddress() {
        byte[] result = new byte[6];
        System.arraycopy(packet, 38, result, 0, 6);
        return result;
    }

    /**
     * The raw data bytes of the device announcement packet.
     */
    public byte[] getPacket() {
        byte[] result = new byte[54];
        System.arraycopy(packet, 0, result, 0, 54);
        return result;
    }
}
