package org.deepsymmetry.beatlink;

import java.net.DatagramPacket;
import java.net.InetAddress;

/**
 * Represents a device status update seen on a DJ Link network.
 *
 * @author James Elliott
 */
public abstract class DeviceUpdate {

    /**
     * The address from which this device update was received.
     */
    protected final InetAddress address;

    /**
     * When this update was received.
     */
    protected final long timestamp;

    /**
     * The name of the device sending the update.
     */
    protected final String deviceName;

    /**
     * The player/device number sending the update.
     */
    protected final int deviceNumber;

    /**
     * The packet data containing the device update.
     */
    protected final byte[] packetBytes;

    /**
     * Constructor sets all the immutable interpreted fields based on the packet content.
     *
     * @param packet the device update packet that was received
     * @param name the type of packet that is being processed, in case a problem needs to be reported
     * @param length the expected length of the packet
     */
    public DeviceUpdate(DatagramPacket packet, String name, int length) {
        if (packet.getLength() != length) {
            throw new IllegalArgumentException(name + " packet must be " + length + " bytes long");
        }
        address = packet.getAddress();
        packetBytes = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), 0, packetBytes, 0, packet.getLength());
        timestamp = System.currentTimeMillis();
        deviceName = new String(packetBytes, 11, 20).trim();
        deviceNumber = Util.unsign(packetBytes[36]);
    }

    /**
     * Get the address of the device from which this update was seen.
     *
     * @return the network address from which the update was sent
     */
    public InetAddress getAddress() {
        return address;
    }

    /**
     * Get the timestamp recording when the device update was received.
     *
     * @return the millisecond timestamp at which we received this update
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Get the name reported by the device sending the update.
     *
     * @return the device name
     */
    public String getDeviceName() {
        return deviceName;
    }

    /**
     * Get the player/device number reporting the update.
     *
     * @return the player number found in the update packet
     */
    public int getDeviceNumber() {
        return deviceNumber;
    }

    /**
     * Get the raw data bytes of the device update packet.
     *
     * @return the data sent by the device to update its status
     */
    public byte[] getPacketBytes() {
        byte[] result = new byte[packetBytes.length];
        System.arraycopy(packetBytes, 0, result, 0, packetBytes.length);
        return result;
    }

    /**
     * Is this device reporting itself to be the current tempo master?
     *
     * @return {@code true} if the device that sent this update is the master
     * @throws  IllegalStateException if the {@link VirtualCdj} is not running.
     */
    public abstract boolean isTempoMaster();

    /**
     * Get the effective tempo reflected by this update, which reflects both its track BPM and pitch as needed.
     *
     * @return the beats per minute this device is reporting
     */
    public abstract double getEffectiveTempo();

    /**
     * Get the position within a measure of music at which the most recent beat fell (a value from 1 to 4, where 1 represents
     * the down beat). This value will be accurate for players when the track was properly configured within rekordbox
     * (and if the music follows a standard House 4/4 time signature). The mixer makes no effort to synchronize
     * down beats with players, however, so this value is meaningless when coming from the mixer. The usefulness of
     * this value can be checked with {@link #isBeatWithinBarMeaningful()}.
     *
     * @return the beat number within the current measure of music
     */
    public abstract int getBeatWithinBar();

    /**
     * Returns {@code true} if this update is coming from a device where {@link #getBeatWithinBar()} can reasonably
     * be expected to have musical significance, because it respects the way a track was configured within rekordbox.
     *
     * @return true for status packets from players, false for status packets from mixers
     */
    public abstract boolean isBeatWithinBarMeaningful();
}
