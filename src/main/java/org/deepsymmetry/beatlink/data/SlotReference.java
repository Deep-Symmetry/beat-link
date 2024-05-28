package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;
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
@API(status = API.Status.STABLE)
public class SlotReference {

    /**
     * The player in which this slot is found.
     */
    @API(status = API.Status.STABLE)
    public final int player;

    /**
     * The specific type of the slot.
     */
    @API(status = API.Status.STABLE)
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
    private static final Map<Integer, Map<CdjStatus.TrackSourceSlot, SlotReference>> instances = new HashMap<>();

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
    @API(status = API.Status.STABLE)
    public static synchronized SlotReference getSlotReference(int player, CdjStatus.TrackSourceSlot slot) {
        Map<CdjStatus.TrackSourceSlot, SlotReference> playerMap = instances.computeIfAbsent(player, k -> new HashMap<>());
        return playerMap.computeIfAbsent(slot, s -> new SlotReference(player, s));
    }

    /**
     * Get a unique reference to the media slot on the network from which the specified data was loaded.
     *
     * @param dataReference the data whose media slot is of interest
     *
     * @return the instance that will always represent the slot associated with the specified data
     */
    @API(status = API.Status.STABLE)
    public static SlotReference getSlotReference(DataReference dataReference) {
        return getSlotReference(dataReference.player, dataReference.slot);
    }

    @Override
    public String toString() {
        return "SlotReference[player:" + player + ", slot:" + slot + "]";
    }
}
