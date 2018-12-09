package org.deepsymmetry.beatlink.data;

/**
 * Supports delegation of Swing repaint calls to a host component. This is currently used by the
 * {@link WaveformPreviewComponent} to operate in a soft-loaded manner in user interfaces which display
 * lists of large numbers of waveforms, so that the ones not on the screen at the moment can be garbage
 * collected and reloaded later. It still needs some way to communicate which areas of the screen need
 * to be redrawn when responding to changes in playback position marker locations, so this interface is
 * used along with {@link WaveformPreviewComponent#setRepaintDelegate(RepaintDelegate)} to enable those
 * changes to be communicated to the lighter component which is hosting it.
 */
public interface RepaintDelegate {

    /**
     * Request that a region of this component be scheduled for repaint even though it is not actually
     * in any on-screen container. The host component delegates to its own
     * {@link java.awt.Component#repaint(int, int, int, int)} implementation.
     *
     * @param x the left edge of the region that we want to have redrawn
     * @param y the top edge of the region that we want to have redrawn
     * @param width the width of the region that we want to have redrawn
     * @param height the height of the region that we want to have redrawn
     */
    void repaint(int x, int y, int width, int height);

}
