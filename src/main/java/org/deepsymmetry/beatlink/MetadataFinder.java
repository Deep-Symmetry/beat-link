package org.deepsymmetry.beatlink;

import org.deepsymmetry.beatlink.dbserver.BinaryField;
import org.deepsymmetry.beatlink.dbserver.Client;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.beatlink.dbserver.NumberField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

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
     * Finds a valid  player number that is currently visible but which is different from the one specified, so it can
     * be used as the source player for a query being sent to the specified one. If the virtual CDJ is running on an
     * acceptable player number (which must be 1-4 to request metadata from an actual CDJ, but can be anything if we
     * are talking to rekordbox), uses that, since it will always be safe. Otherwise, picks an existing player number,
     * but this will cause the query to fail if that player has mounted media from the player we are querying.
     *
     * @param player the player to which a metadata query is being sent
     * @param slot the media slot from which the track of interest was loaded
     *
     * @return some other currently active player number
     *
     * @throws IllegalStateException if there is no other player number available to use.
     */
    private static int chooseAskingPlayerNumber(int player, CdjStatus.TrackSourceSlot slot) {
        final int fakeDevice = VirtualCdj.getDeviceNumber();
        if (slot == CdjStatus.TrackSourceSlot.COLLECTION || (fakeDevice >= 1 && fakeDevice <= 4)) {
            return fakeDevice;
        }

        for (DeviceAnnouncement candidate : DeviceFinder.currentDevices()) {
            final int realDevice = candidate.getNumber();
            if (realDevice != player && realDevice >= 1 && realDevice <= 4) {
                final DeviceUpdate lastUpdate =  VirtualCdj.getLatestStatusFor(realDevice);
                if (lastUpdate != null && lastUpdate instanceof CdjStatus &&
                        ((CdjStatus)lastUpdate).getTrackSourcePlayer() != player) {
                    return candidate.getNumber();
                }
            }
        }
        throw new IllegalStateException("No player number available to query player " + player +
                ". If they are on the network, they must be using Link to play a track from that player, " +
                "so we can't use their ID.");
    }

    /**
     * Request metadata for a specific track ID, given a connection to a player that has already been set up.
     * Separated into its own method so it could be used multiple times on the same connection when gathering
     * all track metadata.
     *
     * @param rekordboxId the track of interest
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved metadata, or {@code null} if there is no such track
     *
     * @throws IOException if there is a communication problem
     */
    private static TrackMetadata getTrackMetadata(int rekordboxId, CdjStatus.TrackSourceSlot slot, Client client)
            throws IOException {

        // Send the metadata menu request
        Message response = client.menuRequest(Message.KnownType.METADATA_REQ, Message.MenuIdentifier.MAIN_MENU, slot,
                new NumberField(rekordboxId));
        final long count = response.getMenuResultsCount();
        if (count == Message.NO_MENU_RESULTS_AVAILABLE) {
            return null;
        }

        // Gather all the metadata menu items
        final List<Message> items = client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slot, response);
        TrackMetadata result = new TrackMetadata(items);
        if (result.getArtworkId() != 0) {
            result = result.withArtwork(requestArtwork(result.getArtworkId(), slot, client));
        }
        return result;
    }

    /**
     * Requests the beat grid for a specific track ID, given a connection to a player that has already been set up.

     * @param rekordboxId the track of interest
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved beat grid, or {@code null} if there is no such track
     *
     * @throws IOException if there is a communication problem
     */
    private static BeatGrid getBeatGrid(int rekordboxId, CdjStatus.TrackSourceSlot slot, Client client)
            throws IOException {
        Message response = client.simpleRequest(Message.KnownType.BEAT_GRID_REQ, null,
                client.buildRMS1(Message.MenuIdentifier.DATA, slot), new NumberField(rekordboxId));
        if (response.knownType == Message.KnownType.BEAT_GRID) {
            return new BeatGrid(response);
        }
        logger.error("Unexpected response type when requesting beat grid: {}", response);
        return null;
    }

    /**
     * Request the list of all tracks in the specified slot, given a connection to a player that has already been
     * set up.
     *
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved track list entry items
     *
     * @throws IOException if there is a communication problem
     */
    private static List<Message> getFullTrackList(CdjStatus.TrackSourceSlot slot, Client client)
        throws IOException {
        // Send the metadata menu request
        Message response = client.menuRequest(Message.KnownType.TRACK_LIST_REQ, Message.MenuIdentifier.MAIN_MENU, slot,
                new NumberField(0));
        final long count = response.getMenuResultsCount();
        if (count == Message.NO_MENU_RESULTS_AVAILABLE) {
            return Collections.emptyList();
        }

        // Gather all the metadata menu items
        return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slot, response);
    }


    /**
     * Look up track metadata from a cache.
     *
     * @param cache the appropriate metadata cache file
     * @param rekordboxId the track whose metadata is desired
     *
     * @return the cached metadata, including album art (if available), or {@code null}
     */
    private static TrackMetadata getCachedMetadata(ZipFile cache, int rekordboxId) {
        ZipEntry entry = cache.getEntry(getMetadataEntryName(rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(cache.getInputStream(entry));
                List<Message> items = new LinkedList<Message>();
                Message current = Message.read(is);
                while (current.messageType.getValue() == Message.KnownType.MENU_ITEM.protocolValue) {
                    items.add(current);
                    current = Message.read(is);
                }
                TrackMetadata result = new TrackMetadata(items);
                try {
                    is.close();
                } catch (Exception e) {
                    is = null;
                    logger.error("Problem closing Zip File input stream after reading metadata entry", e);
                }
                if (result.getArtworkId() != 0) {
                    entry = cache.getEntry(getArtworkEntryName(result));
                    if (entry != null) {
                        is = new DataInputStream(cache.getInputStream(entry));
                        try {
                            byte[] imageBytes = new byte[(int)entry.getSize()];
                            is.readFully(imageBytes);
                            result = result.withArtwork(ByteBuffer.wrap(imageBytes).asReadOnlyBuffer());
                        } catch (Exception e) {
                            logger.error("Problem reading artwork from metadata cache, leaving as null", e);
                        }
                    }
                }
                return result;
            } catch (IOException e) {
                if (is != null) {
                    try {
                        is.close();
                    } catch (Exception e2) {
                        logger.error("Problem closing ZipFile input stream after exception", e2);
                    }
                }
                logger.error("Problem reading metadata from cache file, returning null", e);
            }
        }
        return null;
    }

    /**
     * Look up a beat grid in a metadata cache.
     *
     * @param cache the appropriate metadata cache file
     * @param rekordboxId the track whose beat grid is desired
     *
     * @return the cached beat grid (if available), or {@code null}
     */
    private static BeatGrid getCachedBeatGrid(ZipFile cache, int rekordboxId) {
        ZipEntry entry = cache.getEntry(getBeatGridEntryName(rekordboxId));
        if (entry != null) {
            DataInputStream is = null;
            try {
                is = new DataInputStream(cache.getInputStream(entry));
                final Message result = Message.read(is);
                try {
                    is.close();
                } catch (Exception e) {
                    is = null;
                    logger.error("Problem closing Zip File input stream after reading beat grid entry", e);
                }
                return new BeatGrid(result);
            } catch (IOException e) {
                if (is != null) {
                    try {
                        is.close();
                    } catch (Exception e2) {
                        logger.error("Problem closing ZipFile input stream after exception", e2);
                    }
                }
                logger.error("Problem reading beat grid from cache file, returning null", e);
            }
        }
        return null;
    }

    /**
     * Ask the specified player for metadata about the track in the specified slot with the specified rekordbox ID.
     *
     * @param player the player number whose track is of interest
     * @param slot the slot in which the track can be found
     * @param rekordboxId the track of interest
     *
     * @return the metadata, if any
     */
    public static synchronized TrackMetadata requestMetadataFrom(int player, CdjStatus.TrackSourceSlot slot, int rekordboxId) {

        // First check if we are using cached data for this request
        ZipFile cache = getMetadataCache(player, slot);
        if (cache != null) {
            return getCachedMetadata(cache, rekordboxId);
        }

        if (passive) {
            return null;  // We are not allowed to actively query for metadata
        }

        final DeviceAnnouncement deviceAnnouncement = DeviceFinder.getLatestAnnouncementFrom(player);
        final int dbServerPort = getPlayerDBServerPort(player);
        if (deviceAnnouncement == null || dbServerPort < 0) {
            return null;  // If the device isn't known, or did not provide a database server, we can't get metadata.
        }

        final byte posingAsPlayerNumber = (byte) chooseAskingPlayerNumber(player, slot);

        Socket socket = null;
        try {
            InetSocketAddress address = new InetSocketAddress(deviceAnnouncement.getAddress(), dbServerPort);
            socket = new Socket();
            socket.connect(address, 5000);
            Client client = new Client(socket, player, posingAsPlayerNumber);

            return getTrackMetadata(rekordboxId, slot, client);
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

    // TODO: Add method that enumerates a playlist or folder, then one that caches metadata from a playlist.

    public static synchronized BeatGrid requestBeatGridFrom(int player, CdjStatus.TrackSourceSlot slot, int rekordboxId) {
        // First check if we are using cached data for this request
        ZipFile cache = getMetadataCache(player, slot);
        if (cache != null) {
            return getCachedBeatGrid(cache, rekordboxId);
        }

        if (passive) {
            return null;  // We are not allowed to actively query for metadata
        }

        final DeviceAnnouncement deviceAnnouncement = DeviceFinder.getLatestAnnouncementFrom(player);
        final int dbServerPort = getPlayerDBServerPort(player);
        if (deviceAnnouncement == null || dbServerPort < 0) {
            return null;  // If the device isn't known, or did not provide a database server, we can't get metadata.
        }

        final byte posingAsPlayerNumber = (byte) chooseAskingPlayerNumber(player, slot);

        Socket socket = null;
        try {
            InetSocketAddress address = new InetSocketAddress(deviceAnnouncement.getAddress(), dbServerPort);
            socket = new Socket();
            socket.connect(address, 5000);
            Client client = new Client(socket, player, posingAsPlayerNumber);

            return getBeatGrid(rekordboxId, slot, client);
        } catch (Exception e) {
            logger.warn("Problem requesting beat grid", e);
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
     * Creates a metadata cache archive file of all tracks in the specified slot on the specified player. Any
     * previous contents of the specified file will be replaced.
     *
     * @param player the player number whose media library is to have its metdata cached
     * @param slot the slot in which the media to be cached can be found
     * @param cache the file into which the metadata cache should be written
     *
     * @throws IOException if there is a problem communicating with the player or writing the cache file.
     */
    public static void createMetadataCache(int player, CdjStatus.TrackSourceSlot slot, File cache)
            throws IOException {
        createMetadataCache(player, slot, cache, null);
    }

    /**
     * The root under which all zip file entries will be created in our cache metadata files.
     */
    private static final String CACHE_PREFIX = "BLTMetaCache/";

    /**
     * The file entry whose content will be the cache format identifier.
     */
    private static final String CACHE_FORMAT_ENTRY = CACHE_PREFIX + "version";

    /**
     * The prefix for cache file entries that will store track metadata.
     */
    private static final String CACHE_METADATA_ENTRY_PREFIX = CACHE_PREFIX + "metadata/";

    /**
     * The prefix for cache file entries that will store album art.
     */
    private static final String CACHE_ART_ENTRY_PREFIX = CACHE_PREFIX + "artwork/";

    /**
     * The prefix for cache file entries that will store beat grids.
     */
    private static final String CACHE_BEAT_GRID_ENTRY_PREFIX = CACHE_PREFIX + "beatgrid/";

    /**
     * The comment string used to identify a ZIP file as one of our metadata caches.
     */
    public static final String CACHE_FORMAT_IDENTIFIER = "BeatLink Metadata Cache version 1";

    /**
     * Used to mark the end of the metadata items in each cache entry, just like when reading from the server.
     */
    private static final Message MENU_FOOTER_MESSAGE = new Message(0, Message.KnownType.MENU_FOOTER);

    /**
     * Finish the process of copying a list of tracks to a metadata cache, once they have been listed. This code
     * is shared between the implementations that work with the full track list and with playlists.
     *
     * @param trackListEntries the list of menu items identifying which tracks need to be copied to the metadata
     *                         cache
     * @param client the connection to the dbserver on the player whose metadata is being cached
     * @param slot the slot in which the media to be cached can be found
     * @param cache the file into which the metadata cache should be written
     * @param listener will be informed after each track is added to the cache file being created and offered
     *                 the opportunity to cancel the process
     *
     * @throws IOException if there is a problem communicating with the player or writing the cache file.
     */
    private static void copyTracksToCache(List<Message> trackListEntries, Client client, CdjStatus.TrackSourceSlot slot,
                                   File cache, MetadataCreationUpdateListener listener)
        throws IOException {
        FileOutputStream fos = null;
        BufferedOutputStream bos = null;
        ZipOutputStream zos = null;
        WritableByteChannel channel = null;
        final Set<Integer> artworkAdded = new HashSet<Integer>();
        try {
            fos = new FileOutputStream(cache);
            bos = new BufferedOutputStream(fos);
            zos = new ZipOutputStream(bos);
            zos.setMethod(ZipOutputStream.DEFLATED);

            // Add a marker so we can recognize this as a metadata archive. I would use the ZipFile comment, but
            // that is not available until Java 7, and Beat Link is supposed to be backwards compatible with Java 6.
            ZipEntry zipEntry = new ZipEntry(CACHE_FORMAT_ENTRY);
            zos.putNextEntry(zipEntry);
            zos.write(CACHE_FORMAT_IDENTIFIER.getBytes("UTF-8"));

            // Write the actual metadata entries
            channel = Channels.newChannel(zos);
            final int totalToCopy = trackListEntries.size();
            int tracksCopied = 0;

            for (Message entry : trackListEntries) {
                if (entry.getMenuItemType() != Message.MenuItemType.TRACK_LIST_ENTRY) {
                    throw new IOException("Received unexpected item type. Needed TRACK_LIST_ENTRY, got: " + entry);
                }
                int rekordBoxId = (int)((NumberField)entry.arguments.get(1)).getValue();
                TrackMetadata track = getTrackMetadata(rekordBoxId, slot, client);
                logger.debug("Adding metadata with ID {}", rekordBoxId);
                zipEntry = new ZipEntry(getMetadataEntryName(rekordBoxId));
                zos.putNextEntry(zipEntry);
                for (Message metadataItem : track.rawItems) {
                    client.writeMessage(metadataItem, channel);
                }
                client.writeMessage(MENU_FOOTER_MESSAGE, channel);  // So we know to stop reading

                // Now the album art, if any
                if (track.getRawArtwork() != null && !artworkAdded.contains(track.getArtworkId())) {
                    logger.debug("Adding artwork with ID {}", track.getArtworkId());
                    zipEntry = new ZipEntry(getArtworkEntryName(track));
                    zos.putNextEntry(zipEntry);
                    channel.write(track.getRawArtwork());
                }

                BeatGrid beatGrid = getBeatGrid(rekordBoxId, slot, client);
                if (beatGrid != null) {
                    logger.debug("Adding beat grid with ID {}", rekordBoxId);
                    zipEntry = new ZipEntry(getBeatGridEntryName(rekordBoxId));
                    zos.putNextEntry(zipEntry);
                    client.writeMessage(beatGrid.rawMessage, channel);
                }

                // TODO: Include waveforms (once supported), etc.
                if (listener != null) {
                    if (!listener.cacheUpdateContinuing(track, ++tracksCopied, totalToCopy)) {
                        logger.info("Track metadata cache creation canceled by listener");
                        cache.delete();
                        return;
                    }
                }
            }
        } finally {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (Exception e) {
                logger.error("Problem closing byte channel for writing to metadata cache", e);
            }
            try {
                if (zos != null) {
                    zos.close();
                }
            } catch (Exception e) {
                logger.error("Problem closing Zip Output Stream of metadata cache", e);
            }
            try {
                if (bos != null) {
                    bos.close();
                }
            } catch (Exception e) {
                logger.error("Problem closing Buffered Output Stream of metadata cahce", e);
            }
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (Exception e) {
                logger.error("Problem closing File Output Stream of metadata cache", e);
            }
        }
    }

    /**
     * Names the appropriate zip file entry for caching a track's metadata.
     *
     * @param rekordBoxId the id of the track being cached or looked up
     *
     * @return the name of the entry where that track's metadata should be stored
     */
    private static String getMetadataEntryName(int rekordBoxId) {
        return CACHE_METADATA_ENTRY_PREFIX + rekordBoxId;
    }

    /**
     * Names the appropriate zip file entry for caching a track's album art.
     *
     * @param track the track being cached or looked up
     *
     * @return the name of entry where that track's artwork should be stored
     */
    private static String getArtworkEntryName(TrackMetadata track) {
        return CACHE_ART_ENTRY_PREFIX + track.getArtworkId() + ".jpg";
    }

    /**
     * Names the appropriate zip file entry for caching a track's beat grid.
     *
     * @param rekordBoxId the id of the track being cached or looked up
     *
     * @return the name of the entry where that track's beat grid should be stored
     */
    private static String getBeatGridEntryName(int rekordBoxId) {
        return CACHE_BEAT_GRID_ENTRY_PREFIX + rekordBoxId;
    }

    /**
     * Creates a metadata cache archive file of all tracks in the specified slot on the specified player. Any
     * previous contents of the specified file will be replaced. If a non-{@code null} {@code listener} is
     * supplied, its {@link MetadataCreationUpdateListener#cacheUpdateContinuing(TrackMetadata, int, int)} method
     * will be called after each track is added to the cache, allowing it to display progress updates to the user,
     * and to continue or cancel the process by returning {@code true} or {@code false}.
     *
     * @param player the player number whose media library is to have its metdata cached
     * @param slot the slot in which the media to be cached can be found
     * @param cache the file into which the metadata cache should be written
     * @param listener will be informed after each track is added to the cache file being created and offered
     *                 the opportunity to cancel the process
     *
     * @throws IOException if there is a problem communicating with the player or writing the cache file.
     */
    public static void createMetadataCache(int player, CdjStatus.TrackSourceSlot slot, File cache,
                                           MetadataCreationUpdateListener listener)
            throws IOException {
        final DeviceAnnouncement deviceAnnouncement = DeviceFinder.getLatestAnnouncementFrom(player);
        final int dbServerPort = getPlayerDBServerPort(player);
        if (deviceAnnouncement == null || dbServerPort < 0) {
            throw new IOException("Unable to find dbserver on player " + player);
        }

        final byte posingAsPlayerNumber = (byte) chooseAskingPlayerNumber(player, slot);
        cache.delete();
        Socket socket = null;
        Client client = null;
        try {
            InetSocketAddress address = new InetSocketAddress(deviceAnnouncement.getAddress(), dbServerPort);
            socket = new Socket();
            socket.connect(address, 5000);
            client = new Client(socket, player, posingAsPlayerNumber);
            copyTracksToCache(getFullTrackList(slot, client), client, slot, cache, listener);
        } finally {
            try {
                if (client != null) {
                    client.close();
                }
            } catch (Exception e) {
                logger.error("Problem closing dbserver client when building metadata cache", e);
            }
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (Exception e) {
                logger.error("Problem closing socket when building metadata cache", e);
            }
        }
    }

    /**
     * Request the artwork associated with a track whose metadata is being retrieved.
     *
     * @param artworkId identifies the album art to retrieve
     * @param slot the slot identifier from which the track was loaded
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the track's artwork, or null if none is available
     *
     * @throws IOException if there is a problem communicating with the player
     */
    private static ByteBuffer requestArtwork(int artworkId, CdjStatus.TrackSourceSlot slot, Client client)
            throws IOException {

        // Send the artwork request
        Message response = client.simpleRequest(Message.KnownType.ALBUM_ART_REQ, Message.KnownType.ALBUM_ART,
                client.buildRMS1(Message.MenuIdentifier.DATA, slot), new NumberField((long)artworkId));

        // Create an image from the response bytes
        return ((BinaryField)response.arguments.get(3)).getValue();
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
    private static final DeviceUpdateListener updateListener = new DeviceUpdateListener() {
        @Override
        public void received(DeviceUpdate update) {
            logger.debug("Received device update {}", update);
            if (update instanceof CdjStatus) {
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
        } catch (java.net.ConnectException ce) {
            logger.info("Player " + announcement.getNumber() +
                    " doesn't answer rekordbox port queries, connection refused. Won't attempt to request metadata.");
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
    private static final DeviceAnnouncementListener announcementListener = new DeviceAnnouncementListener() {
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
            detachMetadataCache(announcement.getNumber(), CdjStatus.TrackSourceSlot.SD_SLOT);
            detachMetadataCache(announcement.getNumber(), CdjStatus.TrackSourceSlot.USB_SLOT);
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
     * Indicates whether we should use metdata only from caches, never actively requesting it from a player.
     */
    private static boolean passive = false;

    /**
     * Check whether we are configured to use metadata only from caches, never actively requesting it from a player.
     *
     * @return {@code true} if only cached metadata will be used, or {@code false} if metadata will be requested from
     *         a player if a track is loaded from a media slot to which no cache has been assigned
     */
    public static synchronized boolean isPassive() {
        return passive;
    }

    /**
     * Set whether we are configured to use metadata only from caches, never actively requesting it from a player.
     *
     * @param passive {@code true} if only cached metadata will be used, or {@code false} if metadata will be requested
     *                from a player if a track is loaded from a media slot to which no cache has been assigned
     */
    public static synchronized void setPassive(boolean passive) {
        MetadataFinder.passive = passive;
    }

    /**
     * We process our updates on a separate thread so as not to slow down the high-priority update delivery thread;
     * we perform potentially slow I/O.
     */
    private static Thread queueHandler;

    /**
     * We have received an update that invalidates any previous metadata for that player, so clear it out, and alert
     * any listeners.
     *
     * @param update the update which means we can have no metadata for the associated player
     */
    private static synchronized void clearMetadata(CdjStatus update) {
        metadata.remove(update.deviceNumber);
        lastUpdates.remove(update.address);
        deliverTrackMetadataUpdate(update.deviceNumber, null);
    }

    /**
     * We have received notification that a device is no longer on the network, so clear out its metadata.
     *
     * @param announcement the packet which reported the device’s disappearance
     */
    private static synchronized void clearMetadata(DeviceAnnouncement announcement) {
        metadata.remove(announcement.getNumber());
        lastUpdates.remove(announcement.getAddress());
    }

    /**
     * We have obtained metadata for a device, so store it and alert any listeners.
     *
     * @param update the update which caused us to retrieve this metadata
     * @param data the metadata which we received
     */
    private static synchronized void updateMetadata(CdjStatus update, TrackMetadata data) {
        metadata.put(update.deviceNumber, data);
        lastUpdates.put(update.address, update);
        deliverTrackMetadataUpdate(update.deviceNumber, data);
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
     * Keep track of the devices we are currently trying to get metadata from in response to status updates.
     */
    private final static Set<Integer> activeRequests = new HashSet<Integer>();

    /**
     * Keeps track of any metadata caches that have been attached for the SD slots of players on the network,
     * keyed by player number.
     */
    private final static Map<Integer, ZipFile> sdMetadataCaches = new ConcurrentHashMap<Integer, ZipFile>();

    /**
     * Keeps track of any metadata caches that have been attached for the USB slots of players on the network,
     * keyed by player number.
     */
    private final static Map<Integer, ZipFile> usbMetadataCaches = new ConcurrentHashMap<Integer, ZipFile>();

    /**
     * Attach a metadata cache file to a particular player media slot, so the cache will be used instead of querying
     * the player for metadata. This supports operation with metadata during shows where DJs are using all four player
     * numbers and heavily cross-linking between them.
     *
     * If the media is ejected from that player slot, the cache will be detached.
     *
     * @param player the player number for which a metadata cache is to be attached
     * @param slot the media slot to which a meta data cache is to be attached
     * @param cache the metadata cache to be attached
     *
     * @throws IOException if there is a problem reading the cache file
     * @throws IllegalArgumentException if an invalid player number or slot is supplied
     * @throws IllegalStateException if the metadatafinder is not running
     */
    public static void attachMetadataCache(int player, CdjStatus.TrackSourceSlot slot, File cache)
            throws IOException {
        if (!isRunning()) {
            throw new IllegalStateException("attachMetadataCache() can't be used if MetadataFinder is not running");
        }
        if (player < 1 || player > 4 || DeviceFinder.getLatestAnnouncementFrom(player) == null) {
            throw new IllegalArgumentException("unable to attach metadata cache for player " + player);
        }
        ZipFile oldCache = null;

        // Open and validate the cache
        ZipFile newCache = new ZipFile(cache, ZipFile.OPEN_READ);
        ZipEntry zipEntry = newCache.getEntry(CACHE_FORMAT_ENTRY);
        InputStream is = newCache.getInputStream(zipEntry);
        Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A");
        String tag = null;
        if (s.hasNext()) tag = s.next();
        if (!CACHE_FORMAT_IDENTIFIER.equals(tag)) {
            try {
                newCache.close();
            } catch (Exception e) {
                logger.error("Problem re-closing newly opened candidate metadata cache", e);
            }
            throw new IOException("File does not contain a Beat Link metadata cache: " + cache +
            " (looking for format identifier \"" + CACHE_FORMAT_IDENTIFIER + "\", found: " + tag);
        }

        switch (slot) {
            case USB_SLOT:
                oldCache = usbMetadataCaches.put(player, newCache);
                break;

            case SD_SLOT:
                oldCache = sdMetadataCaches.put(player, newCache);
                break;

            default:
                try {
                    newCache.close();
                } catch (Exception e) {
                    logger.error("Problem re-closing newly opened candidate metadata cache", e);
                }
                throw new IllegalArgumentException("Cannot cache media for slot " + slot);
        }

        if (oldCache != null) {
            try {
                oldCache.close();
            } catch (IOException e) {
                logger.error("Problem closing previous metadata cache", e);
            }
        }

        deliverCacheUpdate();
    }

    /**
     * Removes any metadata cache file that might have been assigned to a particular player media slot, so metadata
     * will be looked up from the player itself.
     *
     * @param player the player number for which a metadata cache is to be attached
     * @param slot the media slot to which a meta data cache is to be attached
     */
    public static void detachMetadataCache(int player, CdjStatus.TrackSourceSlot slot) {
        ZipFile oldCache = null;
        switch (slot) {
            case USB_SLOT:
                oldCache = usbMetadataCaches.remove(player);
                break;

            case SD_SLOT:
                oldCache = sdMetadataCaches.remove(player);
                break;

            default:
                logger.warn("Ignoring request to remove metadata cache for slot {}", slot);
        }

        if (oldCache != null) {
            try {
                oldCache.close();
            } catch (IOException e) {
                logger.error("Problem closing metadata cache", e);
            }
            deliverCacheUpdate();
        }
    }

    /**
     * Finds the metadata cache file assigned to a particular player media slot, if any.
     *
     * @param player the player number for which a metadata cache is to be attached
     * @param slot the media slot to which a meta data cache is to be attached
     *
     * @return the zip file being used as a metadata cache for that player and slot, or {@code null} if no cache
     *         has been attached
     */
    public static ZipFile getMetadataCache(int player, CdjStatus.TrackSourceSlot slot) {
        switch (slot) {
            case USB_SLOT:
                return usbMetadataCaches.get(player);
            case SD_SLOT:
                return  sdMetadataCaches.get(player);
            default:
                return null;
        }
    }

    /**
     * Keeps track of any players with mounted SD media.
     */
    private static Set<Integer> sdMounts = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    /**
     * Keeps track of any players with mounted USB media.
     */
    private static Set<Integer> usbMounts = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    /**
     * Records that there is media mounted in a particular media player slot, updating listeners if this is a change.
     *
     * @param player the number of the player that has media in the specified slot
     * @param slot the slot in which media is mounted
     */
    private static void recordMount(int player, CdjStatus.TrackSourceSlot slot) {
        switch (slot) {
            case USB_SLOT:
                if (usbMounts.add(player)) {
                    deliverCacheUpdate();
                }
                break;
            case SD_SLOT:
                if (sdMounts.add(player)) {
                    deliverCacheUpdate();
                }
                break;
            default:
                throw new IllegalArgumentException("Cannot record mounted media in slot " + slot);
        }
    }

    /**
     * Records that there is no media mounted in a particular media player slot, updating listeners if this is a change.
     *
     * @param player the number of the player that has no media in the specified slot
     * @param slot the slot in which no media is mounted
     */
    private static void removeMount(int player, CdjStatus.TrackSourceSlot slot) {
        switch (slot) {
            case USB_SLOT:
                if (usbMounts.remove(player)) {
                    deliverCacheUpdate();
                }
                break;
            case SD_SLOT:
                if (sdMounts.remove(player)) {
                    deliverCacheUpdate();
                }
                break;
            default:
                logger.warn("Ignoring request to record unmounted media in slot {}", slot);
        }
    }

    /**
     * Returns the set of player numbers that currently have media mounted in the specified slot.
     *
     * @param slot the slot of interest, currently must be either {@code SD_SLOT} or {@code USB_SLOT}
     *
     * @return the player numbers with media currently mounted in the specified slot
     */
    public static Set<Integer> getPlayersWithMediaIn(CdjStatus.TrackSourceSlot slot) {
        switch (slot) {
            case USB_SLOT:
                return Collections.unmodifiableSet(usbMounts);
            case SD_SLOT:
                return Collections.unmodifiableSet(sdMounts);
            default:
                throw new IllegalArgumentException("Cannot report mounted media in slot " + slot);
        }
    }

    /**
     * Keeps track of the registered cache update listeners.
     */
    private static final Set<MetadataCacheUpdateListener> cacheListeners = new HashSet<MetadataCacheUpdateListener>();

    /**
     * Adds the specified cache update listener to receive updates when a metadata cache is attached or detached.
     * If {@code listener} is {@code null} or already present in the set of registered listeners, no exception is
     * thrown and no action is performed.
     *
     * <p>To reduce latency, updates are delivered to listeners directly on the thread that is receiving packets
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the cache update listener to add
     */
    public static synchronized void addCacheUpdateListener(MetadataCacheUpdateListener listener) {
        if (listener != null) {
            cacheListeners.add(listener);
        }
    }

    /**
     * Removes the specified cache update listener so that it no longer receives updates when there
     * are changes to the available set of metadata caches. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the master listener to remove
     */
    public static synchronized void removeCacheUpdateListener(MetadataCacheUpdateListener listener) {
        if (listener != null) {
            cacheListeners.remove(listener);
        }
    }

    /**
     * Get the set of currently-registered metadata cache update listeners.
     *
     * @return the listeners that are currently registered for metadata cache updates
     */
    public static synchronized Set<MetadataCacheUpdateListener> getCacheUpdateListeners() {
        return Collections.unmodifiableSet(new HashSet<MetadataCacheUpdateListener>(cacheListeners));
    }

    /**
     * Send a metadata cache update announcement to all registered listeners.
     */
    private static void deliverCacheUpdate() {
        final Map<Integer, ZipFile> sdCaches = Collections.unmodifiableMap(sdMetadataCaches);
        final Map<Integer, ZipFile> usbCaches = Collections.unmodifiableMap(usbMetadataCaches);
        final Set<Integer> sdSet = Collections.unmodifiableSet(sdMounts);
        final Set<Integer> usbSet = Collections.unmodifiableSet(usbMounts);
        for (final MetadataCacheUpdateListener listener : getCacheUpdateListeners()) {
            try {
                listener.cacheStateChanged(sdCaches, usbCaches, sdSet, usbSet);

            } catch (Exception e) {
                logger.warn("Problem delivering metadata cache update to listener", e);
            }
        }
    }

    /**
     * Keeps track of the registered track metadata update listeners.
     */
    private static final Set<TrackMetadataUpdateListener> trackListeners = new HashSet<TrackMetadataUpdateListener>();

    /**
     * Adds the specified track metadata listener to receive updates when the track metadata for a player changes.
     * If {@code listener} is {@code null} or already present in the set of registered listeners, no exception is
     * thrown and no action is performed.
     *
     * <p>To reduce latency, updates are delivered to listeners directly on the thread that is receiving packets
     * from the network, so if you want to interact with user interface objects in listener methods, you need to use
     * <code><a href="http://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-">javax.swing.SwingUtilities.invokeLater(Runnable)</a></code>
     * to do so on the Event Dispatch Thread.
     *
     * Even if you are not interacting with user interface objects, any code in the listener method
     * <em>must</em> finish quickly, or it will add latency for other listeners, and updates will back up.
     * If you want to perform lengthy processing of any sort, do so on another thread.</p>
     *
     * @param listener the track metadata update listener to add
     */
    public static synchronized void addTrackMetadataUpdateListener(TrackMetadataUpdateListener listener) {
        if (listener != null) {
            trackListeners.add(listener);
        }
    }

    /**
     * Get the set of currently-registered metadata cache update listeners.
     *
     * @return the listeners that are currently registered for metadata cache updates
     */
    public static synchronized Set<TrackMetadataUpdateListener> getTrackMetadataUpdateListeners() {
        return Collections.unmodifiableSet(new HashSet<TrackMetadataUpdateListener>(trackListeners));
    }

    /**
     * Removes the specified track metadata update listener so that it no longer receives updates when track
     * metadata for a player changes. If {@code listener} is {@code null} or not present
     * in the set of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the track metadata update listener to remove
     */
    public static synchronized void removeTrackMetadataUpdateListener(TrackMetadataUpdateListener listener) {
        if (listener != null) {
            trackListeners.remove(listener);
        }
    }

    /**
     * Send a metadata cache update announcement to all registered listeners.
     */
    private static void deliverTrackMetadataUpdate(int player, TrackMetadata metadata) {
        for (final TrackMetadataUpdateListener listener : getTrackMetadataUpdateListeners()) {
            try {
                listener.metadataChanged(player, metadata);

            } catch (Exception e) {
                logger.warn("Problem delivering track metadata update to listener", e);
            }
        }
    }

    /**
     * Process an update packet from one of the CDJs. See if it has a valid track loaded; if not, clear any
     * metadata we had stored for that player. If so, see if it is the same track we already know about; if not,
     * request the metadata associated with that track.
     *
     * Also clears out any metadata caches that were attached for slots that no longer have media mounted in them,
     * and updates the sets of which players have media mounted in which slots.
     *
     * If any of these reflect a change in state, any registered listeners will be informed.
     *
     * @param update an update packet we received from a CDJ
     */
    private static void handleUpdate(final CdjStatus update) {
        // First see if any metadata caches need evicting or mount sets need updating.
        if (update.isLocalUsbEmpty()) {
            detachMetadataCache(update.deviceNumber, CdjStatus.TrackSourceSlot.USB_SLOT);
            removeMount(update.deviceNumber, CdjStatus.TrackSourceSlot.USB_SLOT);
        } else if (update.isLocalUsbLoaded()) {
            recordMount(update.deviceNumber, CdjStatus.TrackSourceSlot.USB_SLOT);
        }
        if (update.isLocalSdEmpty()) {
            detachMetadataCache(update.deviceNumber, CdjStatus.TrackSourceSlot.SD_SLOT);
            removeMount(update.deviceNumber, CdjStatus.TrackSourceSlot.SD_SLOT);
        } else if (update.isLocalSdLoaded()){
            recordMount(update.deviceNumber, CdjStatus.TrackSourceSlot.SD_SLOT);
        }

        // Now see if a track has changed that needs new metadata.
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
                synchronized (activeRequests) {
                    // Make sure we are not already talking to the device before we try hitting it again.
                    if (!activeRequests.contains(update.getTrackSourcePlayer())) {
                        activeRequests.add(update.getTrackSourcePlayer());
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    TrackMetadata data = requestMetadataFrom(update);
                                    if (data != null) {
                                        updateMetadata(update, data);
                                    }
                                } catch (Exception e) {
                                    logger.warn("Problem requesting track metadata from update" + update, e);
                                } finally {
                                    synchronized (activeRequests) {
                                        activeRequests.remove(update.getTrackSourcePlayer());
                                    }
                                }
                            }
                        }).start();
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
