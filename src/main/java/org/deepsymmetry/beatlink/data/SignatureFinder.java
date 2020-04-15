package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>Watches for new metadata to become available for tracks loaded on players, and when enough elements have been
 * loaded, calculates a signature string that uniquely identifies the track, based on its title, artist, duration,
 * waveform, and beat grid. Reports the signatures to any registered listeners once they have been identified. This
 * supports the efficient triggering of cues based on the recognition of specific tracks, regardless of the media
 * from which they have been loaded.</p>
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class SignatureFinder extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(SignatureFinder.class);

    /**
     * A queue used to hold players whose tracks now have enough information to calculate a signature, so we can
     * process them on a lower priority thready, and not hold up delivery to more time-sensitive listeners.
     */
    private final LinkedBlockingDeque<Integer> pendingUpdates =
            new LinkedBlockingDeque<Integer>(20);

    /**
     * Holds the currently-recognized track signatures for each track loaded in a player.
     */
    private final Map<Integer, String> signatures = new ConcurrentHashMap<Integer, String>();

    /**
     * Called whenever we have lost some piece of information that determines a loaded track signature. If we were
     * previously offering a signature for that player, clear it and notify our listeners.
     *
     * @param player the player number that now lacks required signature information
     */
    private void clearSignature(int player) {
        if (signatures.remove(player) != null) {
            deliverSignatureUpdate(player, null);
        }
    }

    /**
     * Called whenever we have a new piece of information that might complete our ability to calculate a track
     * signature. Enqueues a message to the signature builder thread that will cause it to see if it has everything
     * it needs for that player, and if so, build and report the new signature.
     */
    private void checkIfSignatureReady(int player) {
        if (!pendingUpdates.offerLast(player)) {
            logger.warn("Discarding signature check for player {} because our queue is backed up.", player);
        }
    }

    /**
     * Our metadata listener updates our signature state as track metadata comes and goes.
     */
    private final TrackMetadataListener metadataListener = new TrackMetadataListener() {
        @Override
        public void metadataChanged(TrackMetadataUpdate update) {
            if (update.metadata == null) {
                clearSignature(update.player);
            } else {
                checkIfSignatureReady(update.player);
            }
        }
    };

    /**
     * Our waveform listener updates our signature state as track waveforms come and go.
     */
    private final WaveformListener waveformListener = new WaveformListener() {
        @Override
        public void previewChanged(WaveformPreviewUpdate update) {
            // We don’t do anything with previews
        }

        @Override
        public void detailChanged(WaveformDetailUpdate update) {
            if (update.detail == null) {
                clearSignature(update.player);
            } else {
                checkIfSignatureReady(update.player);
            }
        }
    };

    /**
     * Our beat grid listener updates our signature state as track beat grids come and go.
     */
    private final BeatGridListener beatGridListener = new BeatGridListener() {
        @Override
        public void beatGridChanged(BeatGridUpdate update) {
            if (update.beatGrid == null) {
                clearSignature(update.player);
            } else {
                checkIfSignatureReady(update.player);
            }
        }
    };

    /**
     * Keep track of whether we are running.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Check whether we are currently running.
     *
     * @return true if track signatures are being computed for all active players
     *
     * @see MetadataFinder#isPassive()
     */
    @SuppressWarnings("WeakerAccess")
    public boolean isRunning() {
        return running.get();
    }

    /**
     * We compute signatures on a separate thread so as not to slow down the high-priority update
     * delivery thread; we perform potentially slow cryptographic computation.
     */
    private Thread queueHandler;

    /**
     * Get the signatures that have been computed for all tracks currently loaded in any player for which we have
     * been able to obtain all necessary metadata.
     *
     * @return the signatures that uniquely identify the tracks loaded in each player
     *
     * @throws IllegalStateException if the SignatureFinder is not running
     */
    public Map<Integer, String> getSignatures() {
        ensureRunning();
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableMap(new HashMap<Integer, String>(signatures));
    }

    /**
     * Look up the signature we have calculated for the track loaded in the specified player.
     *
     * @param player the player whose track signature is desired
     *
     * @return the track signature if we have been able to compute one
     *
     * @throws IllegalStateException if the SignatureFinder is not running
     */
    public String getLatestSignatureFor(int player) {
        ensureRunning();
        return signatures.get(player);
    }

    /**
     * Look up the signature we have calculated for the track loaded in a player, identified by a status update
     * received from that player.
     *
     * @param update a status update from the player for which a track signature is desired
     *
     * @return the track signature if we have been able to compute one
     *
     * @throws IllegalStateException if the SignatureFinder is not running
     */
    public String getLatestSignatureFor(DeviceUpdate update) {
        return getLatestSignatureFor(update.getDeviceNumber());
    }

    /**
     * Keeps track of the registered signature listeners.
     */
    private final Set<SignatureListener> signatureListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<SignatureListener, Boolean>());

    /**
     * <p>Adds the specified signature listener to receive updates when the track signature for a player changes.
     * If {@code listener} is {@code null} or already present in the set of registered listeners, no exception is
     * thrown and no action is performed.</p>
     *
     * <p>Updates are delivered to listeners on the Swing Event Dispatch thread, so it is safe to interact with
     * user interface elements within the event handler.
     *
     * Even so, any code in the listener method <em>must</em> finish quickly, or it will freeze the user interface,
     * add latency for other listeners, and updates will back up. If you want to perform lengthy processing of any sort,
     * do so on another thread.</p>
     *
     * @param listener the track signature update listener to add
     */
    public void addSignatureListener(SignatureListener listener) {
        if (listener != null) {
            signatureListeners.add(listener);
        }
    }

    /**
     * Removes the specified signature listener so that it no longer receives updates when the
     * track signature for a player changes. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the track signature update listener to remove
     */
    public void removeSignatureListener(SignatureListener listener) {
        if (listener != null) {
            signatureListeners.remove(listener);
        }
    }

    /**
     * Get the set of currently-registered signature listeners.
     *
     * @return the listeners that are currently registered for track signature updates
     */
    @SuppressWarnings("WeakerAccess")
    public Set<SignatureListener> getSignatureListeners() {
        // Make a copy so callers get an immutable snapshot of the current state.
        return Collections.unmodifiableSet(new HashSet<SignatureListener>(signatureListeners));
    }

    private void deliverSignatureUpdate(final int player, final String signature) {
        final Set<SignatureListener> listeners = getSignatureListeners();
        if (!listeners.isEmpty()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    final SignatureUpdate update = new SignatureUpdate(player, signature);
                    for (final SignatureListener listener : listeners) {
                        try {
                            listener.signatureChanged(update);
                        } catch (Throwable t) {
                            logger.warn("Problem delivering track signature update to listener", t);
                        }
                    }
                }
            });
        }
    }

    /**
     * Set up to automatically stop if anything we depend on stops.
     */
    private final LifecycleListener lifecycleListener = new LifecycleListener() {
        @Override
        public void started(LifecycleParticipant sender) {
            logger.debug("The SignatureFinder does not auto-start when {} does.", sender);
        }

        @Override
        public void stopped(LifecycleParticipant sender) {
            if (isRunning()) {
                logger.info("SignatureFinder stopping because {} has.", sender);
                stop();
            }
        }
    };

    /**
     * Send ourselves "updates" about any tracks that were loaded before we started, since we missed them.
     */
    private void checkExistingTracks() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<DeckReference, TrackMetadata> entry : MetadataFinder.getInstance().getLoadedTracks().entrySet()) {
                    if (entry.getKey().hotCue == 0) {  // The track is currently loaded in a main player deck
                        checkIfSignatureReady(entry.getKey().player);
                    }
                }
            }
        });
    }

    /**
     * Helper method to add a Java integer value to a message digest.
     *
     * @param digest the message digest being built
     * @param value the integer whose bytes should be included in the digest
     */
    private void digestInteger(MessageDigest digest, int value) {
        byte[] valueBytes = new byte[4];
        Util.numberToBytes(value, valueBytes, 0, 4);
        digest.update(valueBytes);
    }

    /**
     * Calculate the signature by which we can reliably recognize a loaded track.
     *
     * @param title the track title
     * @param artist the track artist, or {@code null} if there is no artist
     * @param duration the duration of the track in seconds
     * @param waveformDetail the monochrome waveform detail of the track
     * @param beatGrid the beat grid of the track
     *
     * @return the SHA-1 hash of all the arguments supplied, or {@code null} if any either {@code waveFormDetail} or {@code beatGrid} were {@code null}
     */
    public String computeTrackSignature(final String title, final SearchableItem artist, final int duration,
                                        final WaveformDetail waveformDetail, final BeatGrid beatGrid) {
        final String safeTitle = (title == null)? "" : title;
        final String artistName = (artist == null)? "[no artist]" : artist.label;
        try {
            // Compute the SHA-1 hash of our fields
            MessageDigest digest = MessageDigest.getInstance("SHA1");
            digest.update(safeTitle.getBytes("UTF-8"));
            digest.update((byte) 0);
            digest.update(artistName.getBytes("UTF-8"));
            digest.update((byte) 0);
            digestInteger(digest, duration);
            digest.update(waveformDetail.getData());
            for (int i = 1; i <= beatGrid.beatCount; i++) {
                digestInteger(digest, beatGrid.getBeatWithinBar(i));
                digestInteger(digest, (int)beatGrid.getTimeWithinTrack(i));
                // For historical reasons, the tempo at each beat is not considered in calculating the signature.
            }
            byte[] result = digest.digest();

            // Create a hex string representation of the hash
            StringBuilder hex = new StringBuilder(result.length * 2);
            for (byte aResult : result) {
                hex.append(Integer.toString((aResult & 0xff) + 0x100, 16).substring(1));
            }

            return hex.toString();

        } catch (NullPointerException e) {
            logger.info("Returning null track signature because an input element was null.", e);
        } catch (NoSuchAlgorithmException e) {
            logger.error("Unable to obtain SHA-1 MessageDigest instance for computing track signatures.", e);
        } catch (UnsupportedEncodingException e) {
            logger.error("Unable to work with UTF-8 string encoding for computing track signatures.", e);
        }
        return null;  // We were unable to compute a signature
    }

    /**
     * We have reason to believe we might have enough information to calculate a signature for the track loaded on a
     * player. Verify that, and if so, perform the computation and record and report the new signature.
     */
    private void handleUpdate(final int player) {
        final TrackMetadata metadata = MetadataFinder.getInstance().getLatestMetadataFor(player);
        final WaveformDetail waveformDetail = WaveformFinder.getInstance().getLatestDetailFor(player);
        final BeatGrid beatGrid = BeatGridFinder.getInstance().getLatestBeatGridFor(player);
        if (metadata != null && waveformDetail != null && beatGrid != null) {
            final String signature = computeTrackSignature(metadata.getTitle(), metadata.getArtist(),
                    metadata.getDuration(), waveformDetail, beatGrid);
            if (signature != null) {
                signatures.put(player, signature);
                deliverSignatureUpdate(player, signature);
            }
        }
    }

    /**
     * <p>Start finding waveforms for all active players. Starts the {@link MetadataFinder} if it is not already
     * running, because we need it to send us metadata updates to notice when new tracks are loaded. This in turn
     * starts the {@link DeviceFinder}, so we can keep track of the comings and goings of players themselves.
     * We also start the {@link WaveformFinder} and {@link BeatGridFinder} in order get the other pieces of information
     * we need for computing track signatures.</p>
     *
     * @throws Exception if there is a problem starting the required components
     */
    public synchronized void start() throws Exception {
        if (!isRunning()) {
            MetadataFinder.getInstance().addLifecycleListener(lifecycleListener);
            MetadataFinder.getInstance().start();
            MetadataFinder.getInstance().addTrackMetadataListener(metadataListener);

            WaveformFinder.getInstance().addLifecycleListener(lifecycleListener);
            WaveformFinder.getInstance().setFindDetails(true);
            WaveformFinder.getInstance().start();
            WaveformFinder.getInstance().addWaveformListener(waveformListener);

            BeatGridFinder.getInstance().addLifecycleListener(lifecycleListener);
            BeatGridFinder.getInstance().start();
            BeatGridFinder.getInstance().addBeatGridListener(beatGridListener);

            queueHandler = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (isRunning()) {
                        try {
                            handleUpdate(pendingUpdates.take());
                        } catch (InterruptedException e) {
                            // Interrupted due to one of our finders shutting down, presumably.
                        } catch (Throwable t) {
                            logger.error("Problem processing track signature update", t);
                        }
                    }
                }
            });
            running.set(true);
            queueHandler.start();
            deliverLifecycleAnnouncement(logger, true);
            checkExistingTracks();
        }
    }

    /**
     * Stop finding signatures for all active players.
     */
    public synchronized void stop () {
        if (isRunning()) {
            MetadataFinder.getInstance().removeTrackMetadataListener(metadataListener);
            WaveformFinder.getInstance().removeWaveformListener(waveformListener);
            BeatGridFinder.getInstance().removeBeatGridListener(beatGridListener);
            running.set(false);
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;

            // Report the loss of our signatures, on the proper thread, outside our lock
            final Set<Integer> dyingSignatures = new HashSet<Integer>(signatures.keySet());
            signatures.clear();
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    for (Integer player : dyingSignatures) {
                        deliverSignatureUpdate(player, null);
                    }
                }
            });
        }
        deliverLifecycleAnnouncement(logger, false);
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final SignatureFinder ourInstance = new SignatureFinder();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    public static SignatureFinder getInstance() {
        return ourInstance;
    }

    /**
     * Prevent instantiation.
     */
    private SignatureFinder() {
        // Nothing to do.
    }

    @Override
    public String toString() {
        return "SignatureFinder[running:" + isRunning() + ", signatures:" + signatures + "]";
    }
}
