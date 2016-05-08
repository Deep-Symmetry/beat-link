package org.deepsymmetry.beatlink;

/**
 * The listener interface for receiving beat announcements. Classes that are interested in knowing when DJ Link
 * devices report beats can implement this interface. The listener object created from that class is
 * then registered using {@link BeatFinder#addBeatListener(BeatListener)}.
 * Whenever a new beat starts, the {@link #newBeat(Beat)} method in the listener object is invoked with it.
 *
 * @author James Elliott
 */
public interface BeatListener {

    /**
     * Invoked when a beat is reported on the network. Even though beats contain
     * far less detailed information than status updates, they can be passed to
     * {@link VirtualCdj#getLatestStatusFor(DeviceUpdate)} to find the current detailed status for that device,
     * as long as the Virtual CDJ is active.
     *
     * @param beat the message which announced the start of the new beat
     */
    void newBeat(Beat beat);

}
