package org.deepsymmetry.beatlink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Watches for devices to report new beats by broadcasting beat packets on port 50001,
 * and passes them on to registered listeners. When players are actively playing music,
 * they send beat packets at the start of each beat which, in addition to announcing the
 * start of the beat, provide information about where the beat falls within a measure of
 * music (assuming that the track was properly configured in rekordbox, and is in 4/4 time),
 * the current BPM of the track being played, and the current player pitch adjustment, from
 * which the actual effective BPM can be calculated.</p>
 *
 * <p>When players are stopped, they do not send beat packets, but the mixer continues sending them
 * at the last BPM reported by the master player, so it acts as the most reliable synchronization
 * source. The mixer does not make any effort to keep its notion of measures (down beats) consistent
 * with any player, however. So systems which want to stay in sync with measures as well as beats
 * will want to use the {@link VirtualCdj} to maintain awareness of which player is the master player.</p>
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class BeatFinder extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(BeatFinder.class);

    /**
     * The port to which devices broadcast beat messages.
     */
    @SuppressWarnings("WeakerAccess")
    public static final int BEAT_PORT = 50001;

    /**
     * The socket used to listen for beat packets while we are active.
     */
    private final AtomicReference<DatagramSocket> socket = new AtomicReference<DatagramSocket>(null);

    /**
     * Check whether we are presently listening for beat packets.
     *
     * @return {@code true} if our socket is open and monitoring for DJ Link beat packets on the network
     */
    public boolean isRunning() {
        return socket.get() != null;
    }

    /**
     * Start listening for beat announcements. If already listening, has no effect.
     *
     * @throws SocketException if the socket to listen on port 50001 cannot be created
     */
    public synchronized void start() throws SocketException {
        if (!isRunning()) {
            socket.set(new DatagramSocket(BEAT_PORT));
            deliverLifecycleAnnouncement(logger, true);
            final byte[] buffer = new byte[512];
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            Thread receiver = new Thread(null, new Runnable() {
                @Override
                public void run() {
                    boolean received;
                    while (isRunning()) {
                        try {
                            socket.get().receive(packet);
                            received = true;
                        } catch (IOException e) {
                            // Don't log a warning if the exception was due to the socket closing at shutdown.
                            if (isRunning()) {
                                // We did not expect to have a problem; log a warning and shut down.
                                logger.warn("Problem reading from DeviceAnnouncement socket, stopping", e);
                                stop();
                            }
                            received = false;
                        }
                        try {
                            if (received) {
                                final Util.PacketType kind = Util.validateHeader(packet,  BEAT_PORT);
                                if (kind == Util.PacketType.BEAT) {
                                    final int length = packet.getLength();
                                    if (length < 96) {
                                        logger.warn("Ignoring too-short beat packet; expecting 96 bytes and got " + length + ".");
                                    } else {
                                        if (length > 96) {
                                            logger.warn("Processing too-long beat packet; expecting 96 bytes and got " + length + ".");
                                        }
                                        deliverBeat(new Beat(packet));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("Problem processing beat packet", e);
                        }
                    }
                }
            }, "beat-link BeatFinder receiver");
            receiver.setDaemon(true);
            receiver.setPriority(Thread.MAX_PRIORITY);
            receiver.start();
        }
    }

    /**
     * Stop listening for beats.
     */
    @SuppressWarnings("WeakerAccess")
    public synchronized void stop() {
        if (isRunning()) {
            socket.get().close();
            socket.set(null);
            deliverLifecycleAnnouncement(logger, false);
        }
    }

    /**
     * Keeps track of the registered beat listeners.
     */
    private final Set<BeatListener> listeners =
            Collections.newSetFromMap(new ConcurrentHashMap<BeatListener, Boolean>());

    /**
     * <p>Adds the specified beat listener to receive beat announcements when DJ Link devices broadcast
     * them on the network. If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed.</p>
     *
     * <p>To reduce latency, beat announcements are delivered to listeners directly on the thread that is receiving them
     * them from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and beat announcements will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the beat listener to add
     */
    public void addBeatListener(BeatListener listener) {
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
    public void removeBeatListener(BeatListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Get the set of beat listeners that are currently registered.
     *
     * @return the currently registered beat listeners
     */
    @SuppressWarnings("WeakerAccess")
    public Set<BeatListener> getBeatListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<BeatListener>(listeners));
    }

    /**
     * Send a beat announcement to all registered listeners, and let the {@link VirtualCdj} know about it in case it
     * needs to notify the master beat listeners.
     *
     * @param beat the message announcing the new beat.
     */
    private void deliverBeat(final Beat beat) {
        VirtualCdj.getInstance().processBeat(beat);
        for (final BeatListener listener : getBeatListeners()) {
            try {
                listener.newBeat(beat);
            } catch (Exception e) {
                logger.warn("Problem delivering beat announcement to listener", e);
            }
        }
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final BeatFinder ourInstance = new BeatFinder();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    public static BeatFinder getInstance() {
        return ourInstance;
    }

    /**
     * Prevent direct instantiation.
     */
    private BeatFinder() {
        // Nothing to do.
    }

    @Override
    public String toString() {
        return "BeatFinder[active:" + isRunning() + "]";
    }
}
