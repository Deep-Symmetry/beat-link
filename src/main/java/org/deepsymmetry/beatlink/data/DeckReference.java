package org.deepsymmetry.beatlink.data;

import java.util.HashMap;
import java.util.Map;

/**
 * Uniquely identifies a place where a track can be currently loaded on the network, either the visible deck of one
 * of the players, or one of the hot cues on a player. Used to keep track of which tracks may suddenly start playing.
 *
 * @author James Elliott
 */
public class DeckReference {

    /**
     * The player in which this track is loaded.
     */
    public final int player;

    /**
     * The hot cue number in which the track is loaded, or 0 if it is actively loaded on the playback deck.
     */
    @SuppressWarnings("WeakerAccess")
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
    private static final Map<Integer, Map<Integer, DeckReference>> instances =
            new HashMap<Integer, Map<Integer, DeckReference>>();

    /**
     * Get a unique reference to a place where a track is currently loaded in a player.
     *
     * @param player the player in which the track is loaded
     * @param hotCue hot cue number in which the track is loaded, or 0 if it is actively loaded on the playback deck
     *
     * @return the instance that will always represent a reference to the specified player and hot cue
     */
    @SuppressWarnings("WeakerAccess")
    public static synchronized DeckReference getDeckReference(int player, int hotCue) {
        Map<Integer, DeckReference> playerMap = instances.get(player);
        if (playerMap == null) {
            playerMap = new HashMap<Integer, DeckReference>();
            instances.put(player, playerMap);
        }
        DeckReference result = playerMap.get(hotCue);
        if (result == null) {
            result = new DeckReference(player, hotCue);
            playerMap.put(hotCue, result);
        }
        return result;
    }

    @Override
    public String toString() {
        return "DeckReference[player:" + player + ", hotCue:" + hotCue + "]";
    }
}
