package org.deepsymmetry.beatlink;

/**
 An abstract adapter class for receiving announcements related to the tempo master.
 * The methods in this class are empty; it exists as a convenience for creating listener objects.
 *
 * <p>Extend this class to create a {@link MasterListener} and override only the methods for events that you
 * care about. If you plan to implement all the methods in the interface, you might as well implement
 * {@link MasterListener} directly.</p>
 *
 * <p>Create a listener object using your extended class and then register it using
 * {@link VirtualCdj#addMasterListener(MasterListener)}.
 * Whenever a relevant change occurs, the appropriate method
 * in the listener object is invoked, and the {@link DeviceUpdate} which reported the change is passed to it.</p>
 *
 * @author  James Elliott */
public abstract class MasterAdapter implements MasterListener{
    @Override
    public void masterChanged(DeviceUpdate update) {

    }

    @Override
    public void newBeat(Beat beat) {

    }

    @Override
    public void tempoChanged(double tempo) {

    }
}
