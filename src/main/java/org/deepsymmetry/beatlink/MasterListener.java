package org.deepsymmetry.beatlink;

/**
 * The listener interface for receiving updates about changes to tempo master state. Classes that are interested in
 * knowing when a different device becomes tempo master, the tempo master starts a new beat, or the master tempo itself
 * changes can either implement this interface (and all the methods it contains)or extend the abstract
 * {@link MasterAdapter} class (overriding only the methods of interest).
 * The listener object created from that class is then registered using
 * {@link VirtualCdj#addMasterListener(MasterListener)}. Whenever a relevant change occurs, the appropriate method
 * in the listener object is invoked, and the {@link DeviceUpdate} which reported the change is passed to it.
 *
 * @author James Elliott
 */
public interface MasterListener {

    /**
     * Invoked when there is a change in which device is the current tempo master.
     *
     * @param update the message identifying the new master, or {@code null} if there is now none
     */
    void masterChanged(DeviceUpdate update);

    /**
     * Invoked when the tempo master reports a new beat.
     *
     * @param update the message announcing the beat
     */
    void newBeat(DeviceUpdate update);

    /**
     * Invoked when the master tempo has changed.
     *
     * @param tempo the new master tempo
     */
    void tempoChanged(double tempo);

}
