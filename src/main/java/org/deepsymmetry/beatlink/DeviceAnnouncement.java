package org.deepsymmetry.beatlink;

import java.net.DatagramPacket;
import java.net.InetAddress;

/**
 * Represents a device announcement seen on a DJ Link network.
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
    private final byte[] packetBytes;

    /**
     * Constructor sets all the immutable interpreted fields based on the packet content.
     *
     * @param packet the device announcement packet that was received
     */
    public DeviceAnnouncement(DatagramPacket packet) {
        if (packet.getLength() != 54) {
            throw new IllegalArgumentException("Device announcement packet must be 54 bytes long");
        }
        address = packet.getAddress();
        packetBytes = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), 0, packetBytes, 0, packet.getLength());
        timestamp = System.currentTimeMillis();
        name = new String(packetBytes, 12, 20).trim();
        number = Util.unsign(packetBytes[36]);
    }

    /**
     * Get the address on which this device was seen.
     *
     * @return the network address from which the device is communicating
     */
    public InetAddress getAddress() {
        return address;
    }

    /**
     * Get the last time the device was heard from.
     *
     * @return the millisecond timestamp at which we last received an announcement from this device
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Get the name reported by the device.
     *
     * @return the device name
     */
    public String getName() {
        return name;
    }

    /**
     * Get the player/device number reported by the device.
     *
     * @return the player number found in the device announcement packet
     */
    public int getNumber() {
        return number;
    }

    /**
     * Get the MAC address reported by the device.
     *
     * @return the device's Ethernet address
     */
    public byte[] getHardwareAddress() {
        byte[] result = new byte[6];
        System.arraycopy(packetBytes, 38, result, 0, 6);
        return result;
    }

    /**
     * Get the raw data bytes of the device announcement packet.
     *
     * @return the data sent by the device to announce its presence on the network
     */
    public byte[] getPacketBytes() {
        byte[] result = new byte[packetBytes.length];
        System.arraycopy(packetBytes, 0, result, 0, packetBytes.length);
        return result;
    }

    @Override
    public String toString() {
        return "DJ Link Device Announcement: Device " + number + ", name: " + name + ", address: " + address;
    }
}
