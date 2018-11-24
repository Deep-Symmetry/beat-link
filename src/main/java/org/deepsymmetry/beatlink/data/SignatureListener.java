package org.deepsymmetry.beatlink.data;

/**
 * <p>The listener interface for receiving updates when the signatures available for a track loaded in any player
 * change.</p>
 *
 * <p>Classes that are interested having up-to-date information for reliably recognizing loaded tracks can implement
 * this interface, and then pass the implementing instance to
 * {@link SignatureFinder#addSignatureListener(SignatureListener)}.
 * Then, whenever a player loads a new track and enough metadata has been obtained to compute the track signature,
 * {@link #signatureChanged(SignatureUpdate)} will be called, with the currently available signature (if any)
 * for the track loaded in the player.</p>
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public interface SignatureListener {
    /**
     * Called when the track signature available for a player has changed.
     *
     * @param update provides information about what has changed
     */
    void signatureChanged(SignatureUpdate update);
}
