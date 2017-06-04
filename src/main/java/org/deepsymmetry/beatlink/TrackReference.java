package org.deepsymmetry.beatlink;

/**
 * Uniquely identifies a track currently available on the network, by the player and media slot in which it is mounted,
 * and its rekordbox ID. A simple immutable value class.
 *
 * @author James Elliott
 */
public class TrackReference {
    /**
     * The player in which this track is mounted.
     */
    public final int player;

    /**
     * The slot in which the track is mounted.
     */
    public final CdjStatus.TrackSourceSlot slot;

    /**
     * The unique ID of the track within that media database.
     */
    public final int rekordboxId;

    /**
     * Caches the hash code for performance.
     */
    private final int hash;

    /**
     * Create a unique reference to a track that is currently available on the network.
     *
     * @param player the player in which the track is mounted
     * @param slot the slot in which the track is mounted
     * @param rekordboxId the unique ID of the track within that media database
     *
     * @throws NullPointerException if {@code slot} is {@code null}
     */
    public TrackReference(int player, CdjStatus.TrackSourceSlot slot, int rekordboxId) {
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
     * Create a unique reference to a track that is currently available on the network.
     *
     * @param slot the slot in which the track is mounted
     * @param rekordboxId the unique ID of the track within that media database
     */
    public TrackReference(SlotReference slot, int rekordboxId) {
        this(slot.player, slot.slot, rekordboxId);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TrackReference) {
            TrackReference other = (TrackReference) obj;
            return (other.player == player) && (other.slot == slot) && (other.rekordboxId == rekordboxId);
        }
        return false;
    }

    @Override
    public String toString() {
        return "TrackReference[player:" + player + ", slot:" + slot + ", rekordboxId:" + rekordboxId + "]";
    }
}
