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
     * <p>Beat announcements are delivered to listeners on the
     * <a href="https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html">Event Dispatch thread</a>,
     * so it is fine to interact with user interface objects in listener methods. Any code in the listener method
     * must finish quickly, or unhandled events will back up and the user interface will be come unresponsive.</p>
     *
     * @param beat the message which announced the start of the new beat
     */
    void newBeat(Beat beat);

}
