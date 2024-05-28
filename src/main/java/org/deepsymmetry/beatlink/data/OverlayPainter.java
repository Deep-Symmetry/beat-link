package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;

import java.awt.*;

/**
 * In order to make it easy for applications like Beat Link Trigger to overlay selections or cues on the GUI
 * components offered by Beat Link, they allow an overlay painter to be registered with them, and it will then
 * be invoked after the component has done its own painting.
 */
@API(status = API.Status.STABLE)
public interface OverlayPainter {
    /**
     * Paint the overlay on top of the component, which has finished doing its own painting.
     *
     * @param c the component that has just painted itself
     * @param g the graphics content in which painting is taking place
     */
    @API(status = API.Status.STABLE)
    void paintOverlay(Component c, Graphics g);
}
