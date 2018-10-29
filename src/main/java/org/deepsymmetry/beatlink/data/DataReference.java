package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.CdjStatus;

/**
 * Uniquely identifies a track, album art, beat grid, or waveform currently available on the network, by the player
 * and media slot in which it is mounted, and its rekordbox ID. A simple immutable value class.
 *
 * @author James Elliott
 */
public class DataReference {
    /**
     * The player in which this track, album art, beat grid, or waveform is mounted.
     */
    public final int player;

    /**
     * The slot in which the track, album art, beat grid, or waveform is mounted.
     */
    public final CdjStatus.TrackSourceSlot slot;

    /**
     * The unique ID of the track, album art, beat grid, or waveform within that media database.
     */
    public final int rekordboxId;

    /**
     * Caches the hash code for performance.
     */
    private final int hash;

    /**
     * Create a unique reference to a track, album art, beat grid, or waveform that is currently available on
     * the network.
     *
     * @param player the player in which the item is mounted
     * @param slot the slot in which the item is mounted
     * @param rekordboxId the unique ID of the item within that media database
     *
     * @throws NullPointerException if {@code slot} is {@code null}
     */
    public DataReference(int player, CdjStatus.TrackSourceSlot slot, int rekordboxId) {
        this.player = player;
        this.slot = slot;
        this.rekordboxId = rekordboxId;

        // Calculate the hash code
        int scratch = 7;
        scratch = scratch * 31 + player;
        scratch = scratch * 31 + slot.hashCode();
        hash = scratch * 31 + rekordboxId;

    }

    /**
     * Create a unique reference to a track, album art, beat grid, or waveform that is currently available on
     * the network.
     *
     * @param slot the slot in which the item is mounted
     * @param rekordboxId the unique ID of the item within that media database
     */
    public DataReference(SlotReference slot, int rekordboxId) {
        this(slot.player, slot.slot, rekordboxId);
    }

    /**
     * Extract the slot reference portion of this data reference (discarding the rekordbox ID).
     *
     * @return the player and slot from which this data can to be loaded
     */
    public SlotReference getSlotReference() {
        return SlotReference.getSlotReference(this);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof DataReference) {
            DataReference other = (DataReference) obj;
            return (other.player == player) && (other.slot == slot) && (other.rekordboxId == rekordboxId);
        }
        return false;
    }

    @Override
    public String toString() {
        return "DataReference[player:" + player + ", slot:" + slot + ", rekordboxId:" + rekordboxId + "]";
    }
}
