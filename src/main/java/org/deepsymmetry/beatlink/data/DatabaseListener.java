package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;
import org.deepsymmetry.cratedigger.Database;

/**
 * <p>The listener interface for receiving updates when {@link CrateDigger} has obtained the rekordbox DeviceSQL
 * (original Device Library) database that was just mounted in a player slot, or when that slot has unmounted so the
 * database is no longer relevant.</p>
 *
 * <p>Note that this interface is not used to report when SQLite (Device Library Plus) connections are made.</p>
 *
 * <p>Classes that are interested displaying up-to-date information about databases for mounted media can implement this
 * interface, and then pass the implementing instance to {@link CrateDigger#addDatabaseListener(DatabaseListener)}.
 * Then, when a new database is available, {@link #databaseMounted(SlotReference, Database)} will be called,
 * identifying the slot for which a database is now available, and the database itself. When the underlying media
 * is unmounted, {@link #databaseUnmounted(SlotReference, Database)} will be called to report that the database
 * is no longer relevant for that slot.
 * </p>
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public interface DatabaseListener {
    /**
     * <p>Invoked whenever a rekordbox DeviceSQL (original Device Library) export database has been successfully
     * retrieved and parsed from a slot, so it can be used locally to obtain metadata about the tracks in that slot.</p>
     *
     * <p>To reduce latency, updates are delivered to listeners directly on the thread that is receiving packets
     * from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and device updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param slot uniquely identifies a media slot on the network which might host a rekordbox database
     * @param database the database that has been retrieved and parsed from that slot
     */
    @API(status = API.Status.STABLE)
    void databaseMounted(SlotReference slot, Database database);

    /**
     * <p>Invoked whenever the media in for which a database had been obtained is unmounted, to report that the
     * database is no longer relevant for that slot.</p>
     *
     * <p>To reduce latency, updates are delivered to listeners directly on the thread that is receiving packets
     * from the network, so if you want to interact with user interface objects in this method, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.</p>
     *
     * <p>Even if you are not interacting with user interface objects, any code in this method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and device updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param slot uniquely identifies a media slot on the network which might host a rekordbox database
     * @param database the database that had previously provided information about tracks in that slot
     */
    @API(status = API.Status.STABLE)
    void databaseUnmounted(SlotReference slot, Database database);
}
