package org.deepsymmetry.beatlink;

/**
 * The listener interface for receiving updates about changes to tempo master state. Classes that are interested in
 * knowing when a different device becomes tempo master, the tempo master starts a new beat, or the master tempo itself
 * changes can either implement this interface (and all the methods it contains) or extend the abstract
 * {@link MasterAdapter} class (overriding only the methods of interest).
 * The listener object created from that class is then registered using
 * {@link VirtualCdj#addMasterListener(MasterListener)}. Whenever a relevant change occurs, the appropriate method
 * in the listener object is invoked.
 *
 * <p>Note that in order for beats to be reported, the {@link BeatFinder} must be active as well.</p>
 *
 * @author James Elliott
 */
public interface MasterListener extends BeatListener {

    /**
     * Invoked when there is a change in which device is the current tempo master.
     *
     * <p>Tempo master updates are delivered to listeners on the
     * <a href="https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html">Event Dispatch thread</a>,
     * so it is fine to interact with user interface objects in listener methods. Any code in the listener method
     * must finish quickly, or unhandled events will back up and the user interface will be come unresponsive.</p>
     *
     * @param update the message identifying the new master, or {@code null} if there is now none
     */
    void masterChanged(DeviceUpdate update);

    /**
     * Invoked when the master tempo has changed.
     *
     * <p>Tempo master updates are delivered to listeners on the
     * <a href="https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html">Event Dispatch thread</a>,
     * so it is fine to interact with user interface objects in listener methods. Any code in the listener method
     * must finish quickly, or unhandled events will back up and the user interface will be come unresponsive.</p>
     *
     * @param tempo the new master tempo
     */
    void tempoChanged(double tempo);

}
