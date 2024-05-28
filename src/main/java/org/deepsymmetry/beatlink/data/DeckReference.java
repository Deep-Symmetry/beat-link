package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;

import java.util.HashMap;
import java.util.Map;

/**
 * Uniquely identifies a place where a track can be currently loaded on the network, either the visible deck of one
 * of the players, or one of the hot cues on a player. Used to keep track of which tracks may suddenly start playing.
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public class DeckReference {

    /**
     * The player in which this track is loaded.
     */
    @API(status = API.Status.STABLE)
    public final int player;

    /**
     * The hot cue number in which the track is loaded, or 0 if it is actively loaded on the playback deck.
     */
    @API(status = API.Status.STABLE)
    public final int hotCue;

    /**
     * Create a unique reference to a place where a track is currently loaded.
     *
     * @param player the player in which the track is loaded
     * @param hotCue hot cue number in which the track is loaded, or 0 if it is actively loaded on the playback deck
     */
    private DeckReference(int player, int hotCue) {
        this.player = player;
        this.hotCue = hotCue;
    }

    /**
     * Holds all the instances of this class as they get created by the static factory method.
     */
    private static final Map<Integer, Map<Integer, DeckReference>> instances = new HashMap<>();

    /**
     * Get a unique reference to a place where a track is currently loaded in a player.
     *
     * @param player the player in which the track is loaded
     * @param hotCue hot cue number in which the track is loaded, or 0 if it is actively loaded on the playback deck
     *
     * @return the instance that will always represent a reference to the specified player and hot cue
     */
    @API(status = API.Status.STABLE)
    public static synchronized DeckReference getDeckReference(int player, int hotCue) {
        final Map<Integer, DeckReference> playerMap = instances.computeIfAbsent(player, k -> new HashMap<>());
        return playerMap.computeIfAbsent(hotCue, c -> new DeckReference(player, c));
    }

    @Override
    public String toString() {
        return "DeckReference[player:" + player + ", hotCue:" + hotCue + "]";
    }
}
