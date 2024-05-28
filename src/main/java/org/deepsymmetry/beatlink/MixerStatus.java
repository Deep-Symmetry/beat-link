package org.deepsymmetry.beatlink;

import org.apiguardian.api.API;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents a status update sent by a mixer on a DJ Link network.
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public class MixerStatus extends DeviceUpdate {

    private static final Logger logger = LoggerFactory.getLogger(MixerStatus.class);

    /**
     * The byte within the status packet which contains useful status information, labeled <i>F</i> in the
     * <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/vcdj.html#mixer-status-packets">Packet Analysis document</a>.
     */
    @API(status = API.Status.STABLE)
    public static final int STATUS_FLAGS = 0x27;

    /**
     * <p>The byte within a status packet which indicates that the device is in the process of handing off the tempo
     * master role to anther device, labeled <i>M<sub>h</sub></i> in the
     * <a href="https://djl-analysis.deepsymmetry.org/djl-analysis/vcdj.html#mixer-status-packets">Packet Analysis document</a>.</p>
     *
     * <p>Normally it holds the value 0xff, but during a tempo master hand-off, it holds
     * the device number of the incoming tempo master, until that device asserts the master state, after which this
     * device will stop doing so.</p>
     */
    @API(status = API.Status.STABLE)
    public static final int MASTER_HAND_OFF = 0x36;

    /**
     * The device playback pitch found in the packet.
     */
    private final int pitch;

    /**
     * The BPM found in the packet.
     */
    private final int bpm;

    /**
     * If we are in the process of handing the tempo master role to another device, this will be the device number
     * of that device, otherwise it will have the value 255.
     */
    private final int handingMasterToDevice;

    /**
     * The smallest packet size from which we can be constructed. Anything less than this and we are missing
     * crucial information.
     */
    @API(status = API.Status.STABLE)
    public static final int MINIMUM_PACKET_SIZE = 0x38;

    /**
     * Contains the sizes we expect mixer status packets to have, so we can log a warning if we get an unusual
     * one. We will then add the new size to the list, so it only gets logged once per run.
     */
    private static final Set<Integer> expectedStatusPacketSizes = new HashSet<>(Collections.singletonList(MINIMUM_PACKET_SIZE));

    /**
     * Constructor sets all the immutable interpreted fields based on the packet content.
     *
     * @param packet the beat announcement packet that was received
     */
    @API(status = API.Status.STABLE)
    public MixerStatus(DatagramPacket packet) {
        super(packet, "Mixer update", packet.getLength());

        if (packetBytes.length < MINIMUM_PACKET_SIZE) {
            throw new IllegalArgumentException("Unable to create a MixerStatus object, packet too short: we need " + MINIMUM_PACKET_SIZE +
                    " bytes and were given only " + packetBytes.length);
        }

        final int payloadLength = (int)Util.bytesToNumber(packetBytes, 0x22, 2);
        final byte subtype = packetBytes[0x20];
        switch (subtype) {
            case 0:  // Actual mixer status packets use this subtype
                if (packetBytes.length != payloadLength + 0x24) {
                    logger.warn("Received Mixer status packet with reported payload length of {} and actual payload length of {}: {}",
                            payloadLength, packetBytes.length - 0x24, this);
                }
                break;

            case 1:  // rekordbox seems to sometimes send packets with this subtype
                if (packetBytes.length != payloadLength) {
                    logger.warn("Received Mixer status packet with reported length of {} and actual length of {}: {}", payloadLength, packetBytes.length, this);
                }
                break;

            default:
                logger.warn("Received Mixer status packet with unexpected subtype {}: {}", subtype, this);
        }

        if (!expectedStatusPacketSizes.contains(packetBytes.length)) {
            logger.warn("Processing Mixer Status packets with unexpected lengths {}.", packetBytes.length);
            expectedStatusPacketSizes.add(packetBytes.length);
        }

        pitch = (int)Util.bytesToNumber(packetBytes, 0x28, 4);
        bpm = (int)Util.bytesToNumber(packetBytes, 0x2e, 2);
        handingMasterToDevice = Util.unsign(packetBytes[MASTER_HAND_OFF]);
    }

    /**
     * Get the BPM at the time of the update. This is an integer representing the BPM times 100,
     * so 120.5 BPM would be represented by the value 12050.
     *
     * @return the current BPM to two decimal places multiplied by 100
     */
    @API(status = API.Status.STABLE)
    public int getBpm() {
        return bpm;
    }

    /**
     * Get the position within a measure of music at which the most recent beat occurred (a value from 1 to 4, where 1
     * represents the downbeat). The mixer makes no effort to synchronize downbeats with players, however, so this
     * value is of little use.
     *
     * @return the beat number within the current measure of music, as far as the mixer knows
     */
    @API(status = API.Status.STABLE)
    public int getBeatWithinBar() {
        return packetBytes[55];
    }

    /**
     * Returns {@code true} if this beat is coming from a device where {@link #getBeatWithinBar()} can reasonably
     * be expected to have musical significance, because it respects the way a track was configured within rekordbox.
     *
     * @return false because mixers make no effort to line up their beats with rekordbox-identified measures
     */
    @Override
    public boolean isBeatWithinBarMeaningful() {
        return false;
    }

    @Override
    public int getPitch() {
        return pitch;
    }

    /**
     * Is this mixer reporting itself to be the current tempo master?
     *
     * @return {@code true} if the mixer that sent this update is the master
     */
    @Override
    public boolean isTempoMaster() {
        return (packetBytes[STATUS_FLAGS] & CdjStatus.MASTER_FLAG) > 0;
    }

    /**
     * Was the mixer in Sync mode when this update was sent? Unless someone has been sending sync control packets
     * using a package like Beat Link, this will generally return {@code true}.
     *
     * @return true if the sync flag was set
     */
    @Override
    public boolean isSynced() {
        return (packetBytes[STATUS_FLAGS] & CdjStatus.SYNCED_FLAG) > 0;
    }

    @Override
    public Integer getDeviceMasterIsBeingYieldedTo() {
        if (handingMasterToDevice == 0xff) {
            return null;
        }
        return handingMasterToDevice;
    }

    @Override
    public double getEffectiveTempo() {
        return bpm * Util.pitchToMultiplier(pitch) / 100.0;
    }

    @Override
    public String toString() {
        return "MixerStatus[device:" + deviceNumber + ", name:" + deviceName + ", address:" + address.getHostAddress() +
                ", timestamp:" + timestamp + ", BPM:" + String.format("%.1f", bpm / 100.0) +
                ", beat within bar:" + getBeatWithinBar() + ", isBeatWithBarMeaningful? " +
                isBeatWithinBarMeaningful() + ", handingMasterToDevice:" + handingMasterToDevice + "]";
    }
}
