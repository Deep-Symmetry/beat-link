package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;

import java.sql.Connection;

/**
 * <p>The listener interface for receiving updates when {@link CrateDigger} has obtained the rekordbox SQLite
 * (Device Library Plus) database that was just mounted in a player slot and opened a JDBC connection to it,
 * or when that slot has unmounted so the database is no longer relevant and the connection has been closed.</p>
 *
 * <p>Note that this interface is not used to report when DeviceSQL (legacy Device Library) databases are parsed,
 * see {@link DatabaseListener} for that.</p>
 *
 * <p>Classes that are interested displaying up-to-date information about database connections for mounted media can implement this
 * interface, and then pass the implementing instance to {@link CrateDigger#addDatabaseListener(DatabaseListener)}.
 * Then, when a new connection is available, {@link #databaseConnected(SlotReference, Connection)} will be called,
 * identifying the slot for which a connection is now available, and the database itself. When the underlying media
 * is unmounted, {@link #databaseDisconnected(SlotReference, Connection)} will be called to report that the connection
 * is no longer relevant for that slot, and has been closed.
 * </p>
 *
 * @author James Elliott
 */
@API(status = API.Status.EXPERIMENTAL)
public interface SQLiteConnectionListener {
    /**
     * <p>Invoked whenever a rekordbox SQLite (Device Library Plus) export database has been successfully
     * retrieved from a slot and a JDBC connection has been opened to it, so it can be used locally to obtain
     * metadata about the tracks in that slot.</p>
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
     * @param connection the JDBC connection that has been opened to the database retrieved from that slot
     */
    @API(status = API.Status.EXPERIMENTAL)
    void databaseConnected(SlotReference slot, Connection connection);

    /**
     * <p>Invoked whenever the media for which a database had been obtained is unmounted, to report that the
     * database connection is no longer relevant for that slot (and has been closed).</p>
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
     * @param connection the JDBC connection that has previously been open to the database retrieved from that slot
     */
    @API(status = API.Status.EXPERIMENTAL)
    void databaseDisconnected(SlotReference slot, Connection connection);
}
