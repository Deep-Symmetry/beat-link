package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.CdjStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * Uniquely identifies a media slot on the network from which tracks can be loaded, by the player and slot type.
 * A simple immutable value class, with the property that all instances are interned, such that any instances with
 * the same value will actually be the same object, for fast comparison.
 *
 * @author James Elliott
 */
public class SlotReference {

    /**
     * The player in which this slot is found.
     */
    public final int player;

    /**
     * The specific type of the slot.
     */
    @SuppressWarnings("WeakerAccess")
    public final CdjStatus.TrackSourceSlot slot;

    /**
     * Create a unique reference to a media slot on the network from which tracks can be loaded.
     *
     * @param player the player in which the slot is found
     * @param slot the specific type of the slot
     *
     * @throws NullPointerException if {@code slot} is {@code null}
     */
    private SlotReference(int player, CdjStatus.TrackSourceSlot slot) {
        this.player = player;
        this.slot = slot;
    }

    /**
     * Holds all the instances of this class as they get created by the static factory methods.
     */
    private static final Map<Integer, Map<CdjStatus.TrackSourceSlot, SlotReference>> instances =
            new HashMap<Integer, Map<CdjStatus.TrackSourceSlot, SlotReference>>();

    /**
     * Get a unique reference to a media slot on the network from which tracks can be loaded.
     *
     * @param player the player in which the slot is found
     * @param slot the specific type of the slot
     *
     * @return the instance that will always represent the specified slot
     *
     * @throws NullPointerException if {@code slot} is {@code null}
     */
    @SuppressWarnings("WeakerAccess")
    public static synchronized SlotReference getSlotReference(int player, CdjStatus.TrackSourceSlot slot) {
        Map<CdjStatus.TrackSourceSlot, SlotReference> playerMap = instances.get(player);
        if (playerMap == null) {
            playerMap = new HashMap<CdjStatus.TrackSourceSlot, SlotReference>();
            instances.put(player, playerMap);
        }
        SlotReference result = playerMap.get(slot);
        if (result == null) {
            result = new SlotReference(player, slot);
            playerMap.put(slot, result);
        }
        return result;
    }

    /**
     * Get a unique reference to the media slot on the network from which the specified data was loaded.
     *
     * @param dataReference the data whose media slot is of interest
     *
     * @return the instance that will always represent the slot associated with the specified data
     */
    @SuppressWarnings("WeakerAccess")
    public static SlotReference getSlotReference(DataReference dataReference) {
        return getSlotReference(dataReference.player, dataReference.slot);
    }

    @Override
    public String toString() {
        return "SlotReference[player:" + player + ", slot:" + slot + "]";
    }
}
