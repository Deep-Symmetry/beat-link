package org.deepsymmetry.beatlink;

import java.awt.EventQueue;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches for devices to report new beats by broadcasting beat packets on port 50001,
 * and passes them on to registered listeners. When players are actively playing music,
 * they send beat packets at the start of each beat which, in addition to announcing the
 * start of the beat, provide information about where the beat falls within a measure of
 * music (assuming that the track was properly configured in rekordbox, and is in 4/4 time),
 * the current BPM of the track being played, and the current player pitch adjustment, from
 * which the actual effective BPM can be calculated.
 *
 * <p>When players are stopped, they do not send beat packets, but the mixer continues sending them
 * at the last BPM reported by the master player, so it acts as the most reliable synchronization
 * source. The mixer does not make any effort to keep its notion of measures (down beats) consistent
 * with any player, however. So systems which want to stay in sync with measures as well as beats
 * will want to use the {@link VirtualCdj} to maintain awareness of which player is the master player.</p>
 *
 * @author James Elliott
 */
public class BeatFinder {

    private static final Logger logger = Logger.getLogger(BeatFinder.class.getName());

    /**
     * The port to which devices broadcast beat messages.
     */
    public static final int BEAT_PORT = 50001;

    /**
     * The socket used to listen for beat packets while we are active.
     */
    private static DatagramSocket socket;

    /**
     * Check whether we are presently listening for beat packets.
     *
     * @return {@code true} if our socket is open and monitoring for DJ Link beat packets on the network
     */
    public static synchronized boolean isActive() {
        return socket != null;
    }

    /**
     * Start listening for beat announcements. If already listening, has no effect.
     *
     * @throws SocketException if the socket to listen on port 50001 cannot be created
     */
    public static synchronized void start() throws SocketException {
        if (!isActive()) {
            socket = new DatagramSocket(BEAT_PORT);

            final byte[] buffer = new byte[512];
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            Thread receiver = new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean received;
                    while (isActive()) {
                        try {
                            socket.receive(packet);
                            received = true;
                        } catch (IOException e) {
                            // Don't log a warning if the exception was due to the socket closing at shutdown.
                            if (isActive()) {
                                // We did not expect to have a problem; log a warning and shut down.
                                logger.log(Level.WARNING, "Problem reading from DeviceAnnouncement socket, stopping", e);
                                stop();
                            }
                            received = false;
                        }
                        try {
                            if (received && packet.getLength() == 96 && Util.validateHeader(packet, 0x28, "beat")) {
                                // Looks like a beat packet
                                deliverBeat(new Beat(packet));
                            }
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Problem processing beat packet", e);
                        }
                    }
                }
            });
            receiver.setDaemon(true);
            receiver.start();
        }
    }

    /**
     * Stop listening for beats.
     */
    public static synchronized void stop() {
        if (isActive()) {
            socket.close();
            socket = null;
        }
    }

    /**
     * Keeps track of the registered beat listeners.
     */
    private static final Set<BeatListener> listeners = new HashSet<BeatListener>();

    /**
     * Adds the specified beat listener to receive beat announcements when DJ Link devices broadcast
     * them on the network. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the beat listener to add
     */
    public static synchronized void addBeatListener(BeatListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Removes the specified beat listener so that it no longer receives beat announcements when
     * DJ Link devices broadcast them to the network. If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the beat listener to remove
     */
    public static synchronized void removeBeatListener(BeatListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Get the set of beat listeners that are currently registered.
     *
     * @return the currently registered beat listeners
     */
    public static synchronized Set<BeatListener> getBeatListeners() {
        return Collections.unmodifiableSet(new HashSet<BeatListener>(listeners));
    }

    /**
     * Send a beat announcement to all registered listeners, and let the {@link VirtualCdj} know about it in case it
     * needs to notify the master beat listeners.
     *
     * @param beat the message announcing the new beat.
     */
    private static void deliverBeat(final Beat beat) {
        VirtualCdj.processBeat(beat);
        for (final BeatListener listener : getBeatListeners()) {
            EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        listener.newBeat(beat);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Problem delivering beat announcement to listener", e);
                    }
                }
            });
        }
    }
}
