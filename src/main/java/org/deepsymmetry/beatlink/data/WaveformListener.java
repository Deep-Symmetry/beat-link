package org.deepsymmetry.beatlink.data;

/**
 * <p>The listener interface for receiving updates when the waveforms available for a track loaded in any player
 * change.</p>
 *
 * <p>Classes that are interested having up-to-date information about waveforms for loaded tracks can implement this
 * interface, and then pass the implementing instance to
 * {@link WaveformFinder#addWaveformListener(WaveformListener)}.
 * Then, whenever a player loads a new track (or the set of waveforms changes, so we know more or less about
 * tracks in any loaded player), {@link #previewChanged(WaveformPreviewUpdate)} and/or
 * {@link #detailChanged(WaveformDetailUpdate)} will be called, with the currently available waveform preview or detail
 * (if any) for the track loaded in the player.</p>
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public interface WaveformListener {
    /**
     * Called when the waveform preview available for a player has changed.
     *
     * @param update provides information about what has changed
     */
    void previewChanged(WaveformPreviewUpdate update);

    /**
     * Called when the waveform detail available for a player has changed.
     *
     * @param update provides information about what has changed
     */
    @SuppressWarnings("EmptyMethod")
    void detailChanged(WaveformDetailUpdate update);
}
