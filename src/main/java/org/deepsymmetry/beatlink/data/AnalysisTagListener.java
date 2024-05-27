package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;

/**
 * <p>The listener interface for receiving updates when requested analysis information available for a track loaded in
 * any player changes.</p>
 *
 * <p>Classes that are interested having up-to-date information track analysis for loaded tracks can implement
 * this interface, and then pass the implementing instance to
 * {@link AnalysisTagFinder#addAnalysisTagListener(AnalysisTagListener, String, String)}.
 * Then, whenever a player loads a new track (or the set of analysis information changes, so we know more
 * or less about tracks in any loaded player),
 * {@link #analysisChanged(AnalysisTagUpdate)} will be called, with the currently available analysis
 * information of a specific type (if any) for the track loaded in the player.</p>
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public interface AnalysisTagListener {
    /**
     * Called when requested track analysis information available for a player has changed.
     *
     * @param update provides information about what has changed
     */
    @API(status = API.Status.STABLE)
    void analysisChanged(AnalysisTagUpdate update);
}
