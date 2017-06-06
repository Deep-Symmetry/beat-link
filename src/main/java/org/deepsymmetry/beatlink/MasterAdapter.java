package org.deepsymmetry.beatlink;

/**
 * <p>An abstract adapter class for receiving updates related to the tempo master.
 * The methods in this class are empty; it exists as a convenience for creating listener objects.</p>
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

    /**
     * <p>Invoked when a beat is reported by the tempo master, as long as the {@link BeatFinder} is active.
     * Even though beats contain far less detailed information than status updates, they can be passed to
     * {@link VirtualCdj#getLatestStatusFor(DeviceUpdate)} to find the current detailed status for that device,
     * as long as the Virtual CDJ is active.</p>
     *
     * <p>To reduce latency, tempo master updates are delivered to listeners directly on the thread that is receiving them
     * from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and master updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param beat the message which announced the start of the new beat
     */
    public void newBeat(Beat beat) {

    }

    @Override
    public void tempoChanged(double tempo) {

    }
}
