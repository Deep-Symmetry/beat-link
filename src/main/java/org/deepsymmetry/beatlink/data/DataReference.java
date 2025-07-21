package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.CdjStatus;

/**
 * Uniquely identifies a track, album art, beat grid, waveform, song structure, or other metadata object currently available on
 * the network, by the player, media slot in which it is mounted, track type, and its rekordbox ID. A simple immutable value class.
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public class DataReference {
    /**
     * The player in which this track, album art, beat grid, or waveform is mounted.
     */
    @API(status = API.Status.STABLE)
    public final int player;

    /**
     * The slot in which the track, album art, beat grid, or waveform is mounted.
     */
    @API(status = API.Status.STABLE)
    public final CdjStatus.TrackSourceSlot slot;

    /**
     * The unique ID of the track, album art, beat grid, or waveform within that media database.
     */
    @API(status = API.Status.STABLE)
    public final int rekordboxId;

    /**
     * The type of the track. Prior to the CDJ-3000 this was always {@link org.deepsymmetry.beatlink.CdjStatus.TrackType#REKORDBOX}
     * and was assumed, but since the players can now perform analysis themselves, this can now also have the value
     * {@link org.deepsymmetry.beatlink.CdjStatus.TrackType#UNANALYZED} so it needs to be part of the reference.
     */
    public final CdjStatus.TrackType trackType;

    /**
     * Caches the hash code for performance.
     */
    @API(status = API.Status.STABLE)
    private final int hash;

    /**
     * Create a unique reference to a track, album art, beat grid, waveform, song structure, or other metadata object
     * that is currently available on the network.
     *
     * @param player the player in which the item is mounted
     * @param slot the slot in which the item is mounted
     * @param rekordboxId the unique ID of the item within that media database
     *
     * @throws NullPointerException if {@code slot} is {@code null}
     * @deprecated since version 8 in favor of {@link DataReference#DataReference(int, CdjStatus.TrackSourceSlot, int, CdjStatus.TrackType)}.
     */
    @API(status = API.Status.DEPRECATED)
    public DataReference(int player, CdjStatus.TrackSourceSlot slot, int rekordboxId) {
        this(player, slot, rekordboxId, CdjStatus.TrackType.REKORDBOX);
    }

    /**
     * Create a unique reference to a track, album art, beat grid, waveform, song structure, or other metadata object
     * that is currently available on the network.
     *
     * @param player the player in which the item is mounted
     * @param slot the slot in which the item is mounted
     * @param rekordboxId the unique ID of the item within that media database
     * @param trackType identifies the type of track (can now be either {@link org.deepsymmetry.beatlink.CdjStatus.TrackType#REKORDBOX}
     *                  or {@link org.deepsymmetry.beatlink.CdjStatus.TrackType#UNANALYZED} with CDJ-3000s)
     *
     * @throws NullPointerException if {@code slot} or {@code trackType} is {@code null}
     */
    @API(status = API.Status.STABLE)
    public DataReference(int player, CdjStatus.TrackSourceSlot slot, int rekordboxId, CdjStatus.TrackType trackType) {
        this.player = player;
        this.slot = slot;
        this.rekordboxId = rekordboxId;
        this.trackType = trackType;

        // Calculate the hash code
        int scratch = 7;
        scratch = scratch * 31 + player;
        scratch = scratch * 31 + slot.hashCode();
        scratch = scratch * 31 + rekordboxId;
        hash = scratch * 31 + trackType.hashCode();
    }

    /**
     * Create a unique reference to a track, album art, beat grid, or waveform that is currently available on
     * the network, assuming it is a rekordbox track.
     *
     * @param slot the slot in which the item is mounted
     * @param rekordboxId the unique ID of the item within that media database
     *
     * @deprecated since version 8 in favor of {@link DataReference#DataReference(SlotReference, int, CdjStatus.TrackType)}.
     */
    @API(status = API.Status.DEPRECATED)
    public DataReference(SlotReference slot, int rekordboxId) {
        this(slot.player, slot.slot, rekordboxId, CdjStatus.TrackType.REKORDBOX);
    }

    /**
     * Create a unique reference to a track, album art, beat grid, or waveform that is currently available on
     * the network, assuming it is a rekordbox track.
     *
     * @param slot the slot in which the item is mounted
     * @param rekordboxId the unique ID of the item within that media database
     * @param trackType identifies the type of track (can now be either {@link org.deepsymmetry.beatlink.CdjStatus.TrackType#REKORDBOX}
     *                  or {@link org.deepsymmetry.beatlink.CdjStatus.TrackType#UNANALYZED} with CDJ-3000s)
     */
    @API(status = API.Status.STABLE)
    public DataReference(SlotReference slot, int rekordboxId, CdjStatus.TrackType trackType)
    {
        this(slot.player, slot.slot, rekordboxId, trackType);
    }

    /**
     * Extract the slot reference portion of this data reference (discarding the rekordbox ID).
     *
     * @return the player and slot from which this data can to be loaded
     */
    @API(status = API.Status.STABLE)
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
            return (other.player == player) && (other.slot == slot) && (other.rekordboxId == rekordboxId) && other.trackType == trackType;
        }
        return false;
    }

    @Override
    public String toString() {
        return "DataReference[player:" + player + ", slot:" + slot + ", rekordboxId:" + rekordboxId + ", trackType:" + trackType + "]";
    }
}
