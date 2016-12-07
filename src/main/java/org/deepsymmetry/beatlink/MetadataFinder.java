package org.deepsymmetry.beatlink;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.Socket;
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
     * The payload of the initial message which seems to be necessary to enable metadata queries.
     */
    private static byte[] setupPacket = {
            (byte)0x10, (byte)0x00, (byte)0x00, (byte)0x0f, (byte)0x01, (byte)0x14, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00,
            (byte)0x00, (byte)0x00, 0  // This final byte must be replaced with a valid player number.
            // The player number chosen must be active and different than the player being sent the query.
    };

    /**
     * The payload of the first message needed to request metadata about a particular track.
     */
    private static byte[] specifyTrackForMetadataPacket = {
            (byte)0x10, (byte)0x20, (byte)0x02, (byte)0x0f, (byte)0x02, (byte)0x14, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x11, 0 /* player */,
            (byte)0x01, 0 /* slot */, (byte)0x01, (byte)0x11, 0, 0, 0, 0  // Track ID (4 bytes) go here
    };

    /**
     * The payload of the second message which completes a request for metadata about a particular track.
     */
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
     * A message which always appears at the end of a track metadata response, which we can use to be sure we
     * have received the entire response.
     */
    private static byte[] finalMetadataField = {
            (byte)0x10, (byte)0x42, (byte)0x01, (byte)0x0f, (byte)0x00, (byte)0x14, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x0c, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00
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
     * @param is the input stream associated with the player metadata socket.
     * @return the bytes read.
     *
     * @throws IOException if there is a problem reading the response
     */
    private static byte[] receiveBytes(InputStream is) throws IOException {
        byte[] buffer = new byte[8192];
        int len = (is.read(buffer));
        if (len < 1) {
            throw new IOException("receiveBytes read " + len + " bytes.");
        }
        return Arrays.copyOf(buffer, len);
    }

    /**
     * Receive an expected number of bytes from the player, logging a warning if we get a different number of them.
     *
     * @param is the input stream associated with the player metadata socket.
     * @param size the number of bytes we expect to receive.
     * @param description the type of response being processed, for use in the warning message.
     * @return the bytes read.
     *
     * @throws IOException if there is a problem reading the response.
     */
    private static byte[] readResponseWithExpectedSize(InputStream is, int size, String description) throws IOException {
        byte[] result = receiveBytes(is);
        if (result.length != size) {
            logger.warn("Expected " + size + " bytes while reading " + description + " response, received " + result.length);
        }
        return result;
    }

    /**
     * Finds a player number that is currently visible but which is different from the one specified, so it can
     * be used as the source player for a query being sent to the specified one.
     *
     * @param player the player to which a metadata query is being sent.
     * @return some other currently active player number.
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
     * Checks whether the last bytes of the supplied buffer are identical to the bytes of the expected ending.
     *
     * @param buffer the buffer to be checked.
     * @param ending the bytes the buffer is expected to end with once all packets have been received.
     * @return true if the ending byte pattern is found at the end of the buffer.
     */
    private static boolean endsWith(byte[] buffer, byte[] ending) {
        if (buffer.length >= ending.length) {
            for (int i = 0; i < ending.length; i++) {
                if (ending[i] != buffer[i + buffer.length - ending.length]) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Reads as many packets as needed in order to assemble a complete track metadata response, as identified by
     * the presence of the expected final field.
     *
     * @param is the stream connected to the database server.
     * @param messageId the sequence number of the metadata request.
     * @return the assembled bytes of the complete response.
     *
     * @throws IOException if the complete response could not be read.
     */
    private static byte[] readFullMetadataResponse(InputStream is, int messageId) throws IOException {
        byte[] endMarker = buildPacket(messageId, finalMetadataField);
        byte[] result = {};
        do {
            byte[] part = new byte[8192];
            int read = is.read(part);
            if (read < 1) {
                throw new IOException("Unable to read complete metadata response");
            }
            int existingSize = result.length;
            result = Arrays.copyOf(result, existingSize + read);
            System.arraycopy(part, 0, result, existingSize, read);
        } while (!endsWith(result, endMarker));

        return result;
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
        final int dbServerPort = getPlayerDBServerPort(player);
        if (deviceAnnouncement == null || dbServerPort < 0) {
            return null;  // If the device isn't known, or did not provide a database server, we can't get metadata.
        }

        final byte posingAsPlayerNumber = (byte)anotherPlayerNumber(player);
        final byte slotByte = byteRepresentingSlot(slot);

        Socket socket = null;
        try {
            socket = new Socket(deviceAnnouncement.getAddress(), dbServerPort);
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();
            socket.setSoTimeout(3000);

            // Send the first two packets
            os.write(initialPacket);
            readResponseWithExpectedSize(is, 5, "initial packet");

            os.write(buildSetupPacket(posingAsPlayerNumber));
            readResponseWithExpectedSize(is, 42, "connection setup");

            // Send the packet identifying the track we want metadata for
            byte[] payload = new byte[specifyTrackForMetadataPacket.length];
            System.arraycopy(specifyTrackForMetadataPacket, 0, payload, 0, specifyTrackForMetadataPacket.length);
            payload[23] = posingAsPlayerNumber;
            payload[25] = slotByte;
            setIdBytes(payload, payload.length - 4, rekordboxId);
            os.write(buildPacket(1, payload));
            readResponseWithExpectedSize(is, 42, "track metadata id message");

            // Send the final packet to kick off the metadata request
            payload = new byte[finishMetadataQueryPacket.length];
            System.arraycopy(finishMetadataQueryPacket, 0, payload, 0, finishMetadataQueryPacket.length);
            payload[23] = posingAsPlayerNumber;
            payload[25] = slotByte;
            setIdBytes(payload, payload.length - 4, rekordboxId);
            os.write(buildPacket(2, payload));
            return new TrackMetadata(player, splitMetadataFields(readFullMetadataResponse(is, 2)));
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
     * Our update listener just puts appropriate device updates on our queue, so we can process them on a lower
     * priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private static DeviceUpdateListener updateListener = new DeviceUpdateListener() {
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
     * Keeps track of the database server ports of all the players we have seen on the network.
     */
    private static final Map<Integer, Integer> dbServerPorts = new HashMap<Integer, Integer>();

    /**
     * Look up the database server port reported by a given player.
     *
     * @param player the player number of interest.
     *
     * @return the port number on which its database server is running, or -1 if unknown.
     */
    public static synchronized int getPlayerDBServerPort(int player) {
        Integer result = dbServerPorts.get(player);
        if (result == null) {
            return -1;
        }
        return result;
    }

    /**
     * Record the database server port reported by a player.
     *
     * @param player the player number whose server port has been determined.
     * @param port the port number on which the player's database server is running.
     */
    private static synchronized void setPlayerDBServerPort(int player, int port) {
        dbServerPorts.put(player, port);
    }

    /**
     * The port on which we can request information about a player, including the port on which its database server
     * is running.
     */
    private static final int DB_SERVER_QUERY_PORT = 12523;

    private static final byte[] DB_SERVER_QUERY_PACKET = {
            0x00, 0x00, 0x00, 0x0f,
            0x52, 0x65, 0x6d, 0x6f, 0x74, 0x65, 0x44, 0x42, 0x53, 0x65, 0x72, 0x76, 0x65, 0x72,  // RemoteDBServer
            0x00
    };

    /**
     * Query a player to determine the port on which its database server is running.
     *
     * @param announcement the device announcement with which we detected a new player on the network.
     */
    private static void requestPlayerDBServerPort(DeviceAnnouncement announcement) {
        if (announcement.getNumber() > 32) {
            return;  // Don't try to query mixers; they don't listen on the query port.
        }

        Socket socket = null;
        try {
            socket = new Socket(announcement.getAddress(), DB_SERVER_QUERY_PORT);
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();
            socket.setSoTimeout(3000);
            os.write(DB_SERVER_QUERY_PACKET);
            byte[] response = readResponseWithExpectedSize(is, 2, "database server port query packet");
            if (response.length == 2) {
                setPlayerDBServerPort(announcement.getNumber(), (int)Util.bytesToNumber(response, 0, 2));
            }
        } catch (Exception e) {
            logger.warn("Problem requesting database server port number", e);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    logger.warn("Problem closing database server port request socket", e);
                }
            }
        }
    }

    /**
     * Our announcement listener watches for devices to appear on the network so we can ask them for their database
     * server port, and when they disappear discards all information about them.
     */
    private static DeviceAnnouncementListener announcementListener = new DeviceAnnouncementListener() {
        @Override
        public void deviceFound(final DeviceAnnouncement announcement) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    requestPlayerDBServerPort(announcement);
                }
            }).start();
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            setPlayerDBServerPort(announcement.getNumber(), -1);
            clearMetadata(announcement);
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
     * We have received notification that a device is no longer on the network, so clear out its metadata.
     * @param announcement
     */
    private static synchronized void clearMetadata(DeviceAnnouncement announcement) {
        metadata.remove(announcement.getNumber());
        lastUpdates.remove(announcement.getAddress());
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
            DeviceFinder.start();
            DeviceFinder.addDeviceAnnouncementListener(announcementListener);
            for (DeviceAnnouncement device: DeviceFinder.currentDevices()) {
                requestPlayerDBServerPort(device);
            }
            VirtualCdj.start();
            VirtualCdj.addUpdateListener(updateListener);
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
            VirtualCdj.removeUpdateListener(updateListener);
            running = false;
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;
            lastUpdates.clear();
            metadata.clear();
        }
    }
}
