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
    final InetAddress address;

    /**
     * When this update was received.
     */
    @SuppressWarnings("WeakerAccess")
    final long timestamp;

    /**
     * The name of the device sending the update.
     */
    final String deviceName;

    /**
     * The player/device number sending the update.
     */
    final int deviceNumber;

    /**
     * The packet data containing the device update.
     */
    final byte[] packetBytes;

    /**
     * Does this appear to come from a pre-nexus CDJ?
     */
    final boolean preNexusCdj;

    /**
     * Constructor sets all the immutable interpreted fields based on the packet content.
     *
     * @param packet the device update packet that was received
     * @param name the type of packet that is being processed, in case a problem needs to be reported
     * @param length the expected length of the packet
     */
    @SuppressWarnings("WeakerAccess")
    public DeviceUpdate(DatagramPacket packet, String name, int length) {
        timestamp = System.nanoTime();
        if (packet.getLength() != length) {
            throw new IllegalArgumentException(name + " packet must be " + length + " bytes long");
        }
        address = packet.getAddress();
        packetBytes = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), 0, packetBytes, 0, packet.getLength());
        deviceName = new String(packetBytes, 11, 20).trim();
        preNexusCdj = deviceName.startsWith("CDJ") && (deviceName.endsWith("900") || deviceName.endsWith("2000"));

        if (Util.isOpusQuad(deviceName)){
            deviceNumber = translateOpusPlayerNumbers(packetBytes[40]);
        } else {
            deviceNumber = Util.unsign(packetBytes[33]);
        }
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
     * @return the nanosecond timestamp at which we received this update
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
     * Check whether this packet seems to have come from a CDJ older
     * than the original Nexus series (which means, for example, that
     * beat numbers will not be available, so the {@link org.deepsymmetry.beatlink.data.TimeFinder}
     * can't work with it.
     *
     * @return {@code true} if the device name starts with "CDJ" and ends with "0".
     */
    public boolean isPreNexusCdj() {
        return preNexusCdj;
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
     * Get the device pitch at the time of the update. This is an integer ranging from 0 to 2097152, which corresponds
     * to a range between completely stopping playback to playing at twice normal tempo. The equivalent percentage
     * value can be obtained by passing the pitch to {@link Util#pitchToPercentage(long)}, and the corresponding
     * fractional scaling value by passing it to {@link Util#pitchToMultiplier(long)}. Mixers always report a pitch
     * of +0%, so tempo changes are purely reflected in the BPM value.
     *
     * @return the raw effective device pitch at the time of the update
     */
    public abstract int getPitch();

    /**
     * Get the playback BPM at the time of the update. This is an integer representing the BPM times 100, so a track
     * running at 120.5 BPM would be represented by the value 12050. Mixers always report a pitch of +0%, so tempo
     * changes are purely reflected in the BPM value.
     *
     * <p>When the CDJ has just started up and no track has been loaded, it will report a BPM of 65535.</p>
     *
     * @return the track BPM to two decimal places multiplied by 100
     */
    public abstract int getBpm();

    /**
     * Is this device reporting itself to be the current tempo master?
     *
     * @return {@code true} if the device that sent this update is the master
     * @throws  IllegalStateException if called with a {@link Beat} and the {@link VirtualCdj} is not running, since
     *          that is needed to find the latest status update from the device which sent the beat packet.
     */
    public abstract boolean isTempoMaster();

    /**
     * Is this device reporting itself synced to the current tempo master?
     *
     * @return {@code true} if the device that sent this update is synced
     * @throws  IllegalStateException if called with a {@link Beat} and the {@link VirtualCdj} is not running, since
     *          that is needed to find the latest status update from the device which sent the beat packet.
     */
    public abstract boolean isSynced();

    /**
     * If this packet indicates the device in the process of yielding the tempo master role to another player,
     * this will hold the device number of that player, otherwise it will be {@code null}.
     *
     * @return the device number, if any, this update is yielding the tempo master role to
     */
    public abstract Integer getDeviceMasterIsBeingYieldedTo();

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
    @SuppressWarnings("WeakerAccess")
    public abstract boolean isBeatWithinBarMeaningful();

    /**
     * Adjust the player numbers from the Opus-Quad so that they are 1-4 as expected.
     *
     * @return the proper value
     */
    int translateOpusPlayerNumbers(int reportedPlayerNumber) {
        switch (reportedPlayerNumber){
            case 9: return 1;
            case 10: return 2;
            case 11: return 3;
            case 12: return 4;
        }

        return reportedPlayerNumber;
    }

    @Override
    public String toString() {
        return "DeviceUpdate[deviceNumber:" + deviceNumber +
                ", deviceName:" + deviceName + ", address:" + address.getHostAddress() +
                ", timestamp:" + timestamp + ", beatWithinBar:" + getBeatWithinBar() +
                ", isBeatWithinBarMeaningful: " + isBeatWithinBarMeaningful() + ", effectiveTempo:" +
                getEffectiveTempo() + ", isTempoMaster:" + isTempoMaster();
    }
}
