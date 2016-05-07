package org.deepsymmetry.beatlink;

import java.net.DatagramPacket;
import java.net.InetAddress;

/**
 * Represents a beat announcement seen on a DJ Link network.
 *
 * @author James Elliott
 */
public class Beat {

    /**
     * The address from which this beat was received.
     */
    private final InetAddress address;

    /**
     * When this beat was received.
     */
    private final long timestamp;

    /**
     * The name of the device reporting the beat.
     */
    private final String deviceName;

    /**
     * The player/device number reporting the beat.
     */
    private final int deviceNumber;

    /**
     * The device playback pitch found in the packet.
     */
    private final int pitch;

    /**
     * The track BPM found in the packet.
     */
    private final int bpm;

    /**
     * The packet data containing the beat announcement.
     */
    private final byte[] packetBytes;

    /**
     * Constructor sets all the immutable interpreted fields based on the packet content.
     *
     * @param packet the beat announcement packet that was received
     */
    public Beat(DatagramPacket packet) {
        if (packet.getLength() != 96) {
            throw new IllegalArgumentException("Beat announcement packet must be 96 bytes long");
        }
        address = packet.getAddress();
        packetBytes = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), 0, packetBytes, 0, packet.getLength());
        timestamp = System.currentTimeMillis();
        deviceName = new String(packetBytes, 11, 20).trim();
        deviceNumber = Util.unsign(packetBytes[33]);
        pitch = (int)Util.bytesToNumber(packetBytes, 85, 3);
        bpm = (int)Util.bytesToNumber(packetBytes, 90, 2);
    }

    /**
     * Get the address of the device from which this beat was seen.
     *
     * @return the network address from which the beat was sent
     */
    public InetAddress getAddress() {
        return address;
    }

    /**
     * Get the timestamp recording when the beat announcement was received.
     *
     * @return the millisecond timestamp at which we received this beat
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Get the name reported by the device sending the beat.
     *
     * @return the device name
     */
    public String getDeviceName() {
        return deviceName;
    }

    /**
     * Get the player/device number reporting the beat.
     *
     * @return the player number found in the beat packet
     */
    public int getDeviceNumber() {
        return deviceNumber;
    }

    /**
     * Get the device pitch at the time of the beat. This is an integer ranging from 0 to 2097152, which corresponds
     * to a range between completely stopping playback to playing at twice normal tempo. The equivalent percentage
     * value can be obtained by passing the pitch to {@link Util#pitchToPercentage(long)}, and the corresponding
     * fractional scaling value by passing it to {@link Util#pitchToMultiplier(long)}.
     *
     * @return the raw device pitch
     */
    public long getPitch() {
        return pitch;
    }

    /**
     * Get the track BPM at the time of the beat. This is an integer representing the BPM times 100, so a track running
     * at 120.5 BPM would be represented by the value 12050.
     *
     * @return the track BPM to two decimal places multiplied by 100
     */
    public int getBpm() {
        return bpm;
    }

    /**
     * Get the position within a measure of music at which this beat falls (a value from 1 to 4, where 1 represents
     * the down beat). This value will be accurate for players when the track was properly configured within rekordbox
     * (and if the music follows a standard House 4/4 time signature). The mixer makes no effort to synchronize
     * down beats with players, however, so this value is meaningless when coming from the mixer.
     *
     * @return the beat number within the current measure of music
     */
    public int getBeatWithinBar() {
        return packetBytes[92];
    }

    /**
     * Get the raw data bytes of the beat packet.
     *
     * @return the data sent by the device to announce the beat
     */
    public byte[] getPacketBytes() {
        byte[] result = new byte[packetBytes.length];
        System.arraycopy(packetBytes, 0, result, 0, packetBytes.length);
        return result;
    }

    @Override
    public String toString() {
        return "Beat: Device " + deviceNumber + ", name: " + deviceName +
                ", pitch: " + String.format("%+.2f%%", Util.pitchToPercentage(pitch)) +
                ", track BPM: " + String.format("%.1f", bpm / 100.0) +
                ", effective BPM: " + String.format("%.1f", bpm * Util.pitchToMultiplier(pitch) / 100.0);
    }
}
