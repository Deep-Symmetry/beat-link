package org.deepsymmetry.beatlink;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.LinkedBlockingDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches for new tracks to be loaded on players, and queries the
 * appropriate player for the metadata information when that happens.<p>
 *
 * @author James Elliott
 */
public class MetadataFinder {

    private static final Logger logger = LoggerFactory.getLogger(MetadataFinder.class.getName());

    /**
     * The port on which we contact players to ask them for metadata information.
     */
    public static final int METADATA_PORT = 1051;

    /**
     * Given a status update from a CDJ, find the metadata for the track that it has loaded, if any.
     *
     * @param status the CDJ status update that will be used to determine the loaded track and ask the appropriate
     *               player for metadata about it
     * @return the metadata that was obtained, if any
     */
    public static TrackMetadata requestMetadataFrom(CdjStatus status) {
        if (status.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.NO_TRACK || status.getRekordboxId() == 0) {
            return null;
        }
        return requestMetadataFrom(status.getTrackSourcePlayer(), status.getTrackSourceSlot(), status.getRekordboxId());
    }

    /**
     * The first packet that gets sent to any player when setting up to request metadata.
     */
    private static byte[] initialPacket = {0x11, 0x00, 0x00, 0x00, 0x01};

    /**
     * The delimiter which separates individual messages in the TCP stream.
     */
    private static byte[] messageSeparator =  {(byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11};

    /**
     * Split the metadata into its individual fields
     *
     * @param metadata the raw metadata received from the player.
     * @return the fields comprising the metadata response, as delimited by the message separator.
     */
    private static List<byte[]> splitMetadataFields(byte[] metadata) {
        List<byte[]> fields = new LinkedList<byte[]>();
        int begin = 0;

        outer:
        for (int i = 0; i < metadata.length - messageSeparator.length + 1; i++) {
            for (int j = 0; j < messageSeparator.length; j++) {
                if (metadata[i + j] != messageSeparator[j]) {
                    continue outer;
                }
            }
            fields.add(Arrays.copyOfRange(metadata, begin, i));
            begin = i + messageSeparator.length;
        }
        fields.add(Arrays.copyOfRange(metadata, begin, metadata.length));
        return fields;
    }


    /**
     * The payload of the initial packet which seems to be necessary to enable metadata queries.
     */
    private static byte[] setupPacket = {
            (byte)0x10, (byte)0x00, (byte)0x00, (byte)0x0f, (byte)0x01, (byte)0x14, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00,
            (byte)0x00, (byte)0x00, 0  // This final byte must be replaced with a valid player number.
            // The player number chosen must be active and different than the player being sent the query.
    };

    /**
     * The payload of the first packet needed to request metadata about a particular track.
     */
    private static byte[] specifyTrackForMetadataPacket = {
            (byte)0x10, (byte)0x20, (byte)0x02, (byte)0x0f, (byte)0x02, (byte)0x14, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x11, 0 /* player */,
            (byte)0x01, 0 /* slot */, (byte)0x01, (byte)0x11, 0, 0, 0, 0  // Track ID (4 bytes) go here
    };

    private static byte[] finishMetadataQueryPacket = {
            (byte)0x10, (byte)0x30, (byte)0x00, (byte)0x0f, (byte)0x06, (byte)0x14, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x06, (byte)0x06, (byte)0x06, (byte)0x06,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x11, 0 /* player */,
            (byte)0x01, 0 /* slot */, (byte)0x01, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0b, (byte)0x11, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0b, (byte)0x11,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00
    };

    /**
     * Stores a 4-byte id value in the proper byte order into a byte buffer that is being used to build up
     * a metadata query.
     *
     * @param buffer the buffer in which the query is being created.
     * @param offset the index of the first byte where the id is to be stored.
     * @param id the 4-byte id that needs to be stored in the query buffer.
     */
    private static void setIdBytes(byte[] buffer, int offset, int id) {
        buffer[offset] = (byte)(id >> 24);
        buffer[offset + 1] = (byte)(id >> 16);
        buffer[offset + 2] = (byte)(id >>8);
        buffer[offset + 3] = (byte)id;
    }

    /**
     * Formats a query packet to be sent to the player.
     *
     * @param messageId the sequence number of the message; should start with 1 and be incremented with each message.
     * @param payload the bytes which should follow teh separator and sequence number.
     * @return the formatted query packet.
     */
    private static byte[] buildPacket(int messageId, byte[] payload) {
        byte[] result = new byte[payload.length + messageSeparator.length + 4];
        System.arraycopy(messageSeparator, 0, result, 0, messageSeparator.length);
        setIdBytes(result, messageSeparator.length, messageId);
        System.arraycopy(payload, 0, result, messageSeparator.length + 4, payload.length);
        return result;
    }

    /**
     * Creates the packet needed to set up the player connection to successfully process metadata queries.
     *
     * @param fromPlayer the player number we are posing as when making our queries.
     * @return the bytes of the setup packet that should be sent to the player.
     */
    private static byte[] buildSetupPacket(byte fromPlayer) {
        byte[] payload = new byte[setupPacket.length];
        System.arraycopy(setupPacket, 0, payload, 0, setupPacket.length);
        payload[payload.length - 1] = fromPlayer;
        return buildPacket(0xfffffffe, payload);
    }

    /**
     * Receive some bytes from the player we are requesting metadata from.
     *
     * @param is the input stream associated with the player metadata socket
     * @return the bytes read
     *
     * @throws IOException if there is a problem reading the response
     */
    private static byte[] receiveBytes(InputStream is) throws IOException {
        byte[] buffer = new byte[8192];
        int len = (is.read(buffer));
        if (len < 1) {
            throw new IOException("receiveBytes read " + len + " bytes.");
        }
        byte[] result = new byte[len];
        System.arraycopy(buffer, 0, result, 0, len);
        return result;
    }

    /**
     * Finds a player number that is currently visible but which is different from the one specified, so it can
     * be used as the source player for a query being sent to the specified one.
     *
     * @param player the player to which a metadata query is being sent.
     * @return some other currently active player number
     *
     * @throws IllegalStateException if there is no other player number available to use.
     */
    private static int anotherPlayerNumber(int player) {
        for (DeviceAnnouncement candidate : DeviceFinder.currentDevices()) {
            if (candidate.getNumber() != player && candidate.getNumber() < 17) {
                return candidate.getNumber();
            }
        }
        throw new IllegalStateException("No player number available to query player " + player);
    }

    /**
     * Determine the byte value that specifies a particular slot from which a track can be loaded.
     *
     * @param slot the slot of interest.
     * @return the corresponding byte value for metadata query packets.
     * @throws IllegalArgumentException if an unsupported slot value is supplied.
     */
    private static final byte byteRepresentingSlot(CdjStatus.TrackSourceSlot slot) {
        switch (slot) {
            case SD_SLOT:
                return 2;

            case USB_SLOT:
                return 3;

            case COLLECTION:
                return 4;
        }
        throw new IllegalArgumentException("Cannot query metadata for slot " + slot);
    }

    /**
     * Ask the specified player for metadata about the track in the specified slot with the specified rekordbox ID.
     *
     * @param player the player number whose track is of interest
     * @param slot the slot in which the track can be found
     * @param rekordboxId the track of interest
     * @return the metadata, if any
     */
    public static TrackMetadata requestMetadataFrom(int player, CdjStatus.TrackSourceSlot slot, int rekordboxId) {
        final DeviceAnnouncement deviceAnnouncement = DeviceFinder.getLatestAnnouncementFrom(player);
        if (deviceAnnouncement == null || player < 1 || player > 4) {
            return null;
        }
        final byte posingAsPlayerNumber = (byte)anotherPlayerNumber(player);
        final byte slotByte = byteRepresentingSlot(slot);

        Socket socket = null;
        try {
            // TODO: Add DBServer port query rather than hardcoding the port
            socket = new Socket(deviceAnnouncement.getAddress(), METADATA_PORT);
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();
            socket.setSoTimeout(3000);

            // Send the first two packets
            os.write(initialPacket);
            receiveBytes(is);  // Should get 5 bytes

            os.write(buildSetupPacket(posingAsPlayerNumber));
            receiveBytes(is);  // Should get 42 bytes

            // Send the packet identifying the track we want metadata for
            byte[] payload = new byte[specifyTrackForMetadataPacket.length];
            System.arraycopy(specifyTrackForMetadataPacket, 0, payload, 0, specifyTrackForMetadataPacket.length);
            payload[23] = posingAsPlayerNumber;
            payload[25] = slotByte;
            setIdBytes(payload, payload.length - 4, rekordboxId);
            os.write(buildPacket(1, payload));
            receiveBytes(is);  // Should get 42 bytes back

            // Send the final packet to kick off the metadata request
            payload = new byte[finishMetadataQueryPacket.length];
            System.arraycopy(finishMetadataQueryPacket, 0, payload, 0, finishMetadataQueryPacket.length);
            payload[23] = posingAsPlayerNumber;
            payload[25] = slotByte;
            setIdBytes(payload, payload.length - 4, rekordboxId);
            os.write(buildPacket(2, payload));
            byte[] result = receiveBytes(is);  // TODO: Keep reading until we have final segment

            return new TrackMetadata(player, splitMetadataFields(result));
        } catch (Exception e) {
            logger.warn("Problem requesting metadata", e);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    logger.warn("Problem closing metadata request socket", e);
                }
            }
        }
        return null;
    }

    /**
     * Keeps track of the current metadata known for each player.
     */
    private static final Map<Integer, TrackMetadata> metadata = new HashMap<Integer, TrackMetadata>();

    /**
     * Keeps track of the previous update from each player that we retrieved metadata about, to check whether a new
     * track has been loaded.
     */
    private static final Map<InetAddress, CdjStatus> lastUpdates = new HashMap<InetAddress, CdjStatus>();

    /**
     * A queue used to hold CDJ status updates we receive from the {@link VirtualCdj} so we can process them on a
     * lower priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private static LinkedBlockingDeque<CdjStatus> pendingUpdates = new LinkedBlockingDeque<CdjStatus>(100);

    /**
     * Our listener method just puts appropriate device updates on our queue, so we can process them on a lower
     * priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private static DeviceUpdateListener listener = new DeviceUpdateListener() {
        @Override
        public void received(DeviceUpdate update) {
            //logger.log(Level.INFO, "Received: " + update);
            if (update instanceof CdjStatus) {
                //logger.log(Level.INFO, "Queueing");
                if (!pendingUpdates.offerLast((CdjStatus)update)) {
                    logger.warn("Discarding CDJ update because our queue is backed up.");
                }
            }
        }
    };

    /**
     * Keep track of whether we are running
     */
    private static boolean running = false;

    /**
     * Check whether we are currently running.
     *
     * @return true if track metadata is being sought for all active players
     */
    public static synchronized boolean isRunning() {
        return running;
    }

    /**
     * We process our updates on a separate thread so as not to slow down the high-priority update delivery thread;
     * we perform potentially slow I/O.
     */
    private static Thread queueHandler;

    /**
     * We have received an update that invalidates any previous metadata for that player, so clear it out.
     *
     * @param update the update which means we can have no metadata for the associated player.
     */
    private static synchronized void clearMetadata(CdjStatus update) {
        metadata.remove(update.deviceNumber);
        lastUpdates.remove(update.address);
        // TODO: Add update listener
    }

    /**
     * We have obtained metadata for a device, so store it.
     *
     * @param update the update which caused us to retrieve this metadata
     * @param data the metadata which we received
     */
    private static synchronized void updateMetadata(CdjStatus update, TrackMetadata data) {
        metadata.put(update.deviceNumber, data);
        lastUpdates.put(update.address, update);
        // TODO: Add update listener
    }

    /**
     * Get all currently known metadata.
     *
     * @return the track information reported by all current players
     */
    public static synchronized Map<Integer, TrackMetadata> getLatestMetadata() {
        return Collections.unmodifiableMap(new TreeMap<Integer, TrackMetadata>(metadata));
    }

    /**
     * Look up the track metadata we have for a given player number.
     *
     * @param player the device number whose track metadata is desired
     * @return information about the track loaded on that player, if available
     */
    public static synchronized TrackMetadata getLatestMetadataFor(int player) {
        return metadata.get(player);
    }

    /**
     * Look up the track metadata we have for a given player, identified by a status update received from that player.
     *
     * @param update a status update from the player for which track metadata is desired
     * @return information about the track loaded on that player, if available
     */
    public static TrackMetadata getLatestMetadataFor(DeviceUpdate update) {
        return getLatestMetadataFor(update.deviceNumber);
    }

    /**
     * Process an update packet from one of the CDJs. See if it has a valid track loaded; if not, clear any
     * metadata we had stored for that player. If so, see if it is the same track we already know about; if not,
     * request the metadata associated with that track.
     *
     * @param update an update packet we received from a CDJ
     */
    private static void handleUpdate(CdjStatus update) {
        if (update.getTrackSourcePlayer() >= 1 && update.getTrackSourcePlayer() <= 4) {  // We only know how to talk to these devices
            if (update.getTrackType() != CdjStatus.TrackType.REKORDBOX ||
                    update.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.NO_TRACK ||
                    update.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.UNKNOWN ||
                    update.getRekordboxId() == 0) {  // We no longer have metadata for this device
                clearMetadata(update);
            } else {  // We can gather metadata for this device; check if we already looked up this track
                CdjStatus lastStatus = lastUpdates.get(update.address);
                if (lastStatus == null || lastStatus.getTrackSourceSlot() != update.getTrackSourceSlot() ||
                        lastStatus.getTrackSourcePlayer() != update.getTrackSourcePlayer() ||
                        lastStatus.getRekordboxId() != update.getRekordboxId()) {  // We have something new!
                    try {
                        TrackMetadata data = requestMetadataFrom(update);
                        if (data != null) {
                            updateMetadata(update, data);
                        }
                    } catch (Exception e) {
                        logger.warn("Problem requesting track metadata from update" + update, e);
                    }
                }
            }

        }
    }

    /**
     * Start finding track metadata for all active players. Starts the {@link VirtualCdj} if it is not already
     * running, because we need it to send us device status updates to notice when new tracks are loaded.
     *
     * @throws Exception if there is a problem starting the required components
     */
    public static synchronized void start() throws Exception {
        if (!running) {
            VirtualCdj.start();
            VirtualCdj.addUpdateListener(listener);
            queueHandler = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (isRunning()) {
                        try {
                            handleUpdate(pendingUpdates.take());
                        } catch (InterruptedException e) {
                            // Interrupted due to MetadataFinder shutdown, presumably
                        }
                    }
                }
            });
            running = true;
            queueHandler.start();
        }
    }

    /**
     * Stop finding track metadata for all active players.
     */
    public static synchronized void stop() {
        if (running) {
            VirtualCdj.removeUpdateListener(listener);
            running = false;
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;
            lastUpdates.clear();
            metadata.clear();
        }
    }
}
