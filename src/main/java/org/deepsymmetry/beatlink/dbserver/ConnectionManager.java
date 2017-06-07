package org.deepsymmetry.beatlink.dbserver;

import org.deepsymmetry.beatlink.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manges connections to dbserver ports on the players, offering sessions that can be used to perform transactions,
 * and allowing the connections to close when there are no active sessions.
 *
 * @author James Elliott
 */
public class ConnectionManager extends LifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

    /**
     * An interface for all the kinds of activities that need a connection to the dbserver for, so we can keep track
     * of how many sessions are in effect, clean up after them, and know when the client is idle and can be closed.
     *
     * @param <T> the type returned by the activity
     */
    public interface ClientTask<T> {
        T useClient(Client client) throws Exception;
    }

    /**
     * Keeps track of the clients that are currently active, indexed by player number
     */
    private final Map<Integer,Client> openClients = new ConcurrentHashMap<Integer, Client>();

    /**
     * Keeps track of how many tasks are currently using each client.
     */
    private final Map<Client,Integer> useCounts = new ConcurrentHashMap<Client, Integer>();

    /**
     * Finds or opens a client to talk to the dbserver on the specified player, incrementing its use count.
     *
     * @param targetPlayer the player number whose database needs to be interacted with
     * @param description a short description of the task being performed for error reporting if it fails,
     *                    should be a verb phrase like "requesting track metadata"
     *
     * @return the communication client for talking to that player, or {@code null} if the player could not be found
     *
     * @throws IllegalStateException if we can't find the target player or there is no suitable player number for us
     *                               to pretend to be
     * @throws IOException if there is a problem communicating
     */
    private synchronized Client allocateClient(int targetPlayer, String description) throws IOException {
        Client result = openClients.get(targetPlayer);
        if (result == null) {
            // We need to open a new connection.
            final DeviceAnnouncement deviceAnnouncement = DeviceFinder.getInstance().getLatestAnnouncementFrom(targetPlayer);
            final int dbServerPort = getPlayerDBServerPort(targetPlayer);
            if (deviceAnnouncement == null || dbServerPort < 0) {
                throw new IllegalStateException("Player " + targetPlayer + " could not be found " + description);
            }

            final byte posingAsPlayerNumber = (byte) chooseAskingPlayerNumber(targetPlayer);

            Socket socket = null;
            try {
                InetSocketAddress address = new InetSocketAddress(deviceAnnouncement.getAddress(), dbServerPort);
                socket = new Socket();
                socket.connect(address, socketTimeout);
                socket.setSoTimeout(socketTimeout);
                result = new Client(socket, targetPlayer, posingAsPlayerNumber);
            } catch (IOException e) {
                try {
                    socket.close();
                } catch (IOException e2) {
                    logger.error("Problem closing socket for failed client creation attempt " + description);
                }
                throw e;
            }
            openClients.put(targetPlayer, result);
            useCounts.put(result, 0);
        }
        useCounts.put(result, useCounts.get(result) + 1);
        return result;
    }

    /**
     * Decrements the client's use count, and makes it eligible for closing if it is no longer in use.
     *
     * @param client the dbserver connection client which is no longer being used for a task
     */
    private synchronized void freeClient(Client client) {
        int current = useCounts.get(client);
        if (current > 0) {
            useCounts.put(client, current - 1);
            if (current == 1) {
                // This was the last use, so close it.
                // TODO: To be fancier, we could keep it around for a while and close it after an idle period.
                client.close();
                openClients.remove(client.targetPlayer);
            }
        } else {
            logger.error("Ignoring attempt to free a client that is not allocated: {}", client);
        }
    }

    /**
     * Obtain a dbserver client session that can be used to perform some task, call that task with the client,
     * then release the client.
     *
     * @param targetPlayer the player number whose dbserver we wish to communicate with
     * @param task the activity that will be performed with exclusive access to a dbserver connection
     * @param description a short description of the task being performed for error reporting if it fails,
     *                    should be a verb phrase like "requesting track metadata"
     * @param <T> the type that will be returned by the task to be performed
     *
     * @return the value returned by the completed task
     *
     * @throws IOException if there is a problem communicating
     * @throws Exception from the underlying {@code task}, if any
     */
    public <T> T invokeWithClientSession(int targetPlayer, ClientTask<T> task, String description)
            throws Exception {
        if (!isRunning()) {
            throw new IllegalStateException("ConnectionManager is not running, aborting " + description);
        }

        final Client client = allocateClient(targetPlayer, description);
        try {
            return task.useClient(client);
        } finally {
            freeClient(client);
        }
    }

    /**
     * Keeps track of the database server ports of all the players we have seen on the network.
     */
    private final Map<Integer, Integer> dbServerPorts = new ConcurrentHashMap<Integer, Integer>();

    /**
     * Look up the database server port reported by a given player. You should not use this port directly; instead
     * ask this class for a session to use while you communicate with the database.
     *
     * @param player the player number of interest
     *
     * @return the port number on which its database server is running, or -1 if unknown
     *
     * @throws IllegalStateException if not running
     */
    @SuppressWarnings("WeakerAccess")
    public int getPlayerDBServerPort(int player) {
        ensureRunning();
        Integer result = dbServerPorts.get(player);
        if (result == null) {
            return -1;
        }
        return result;
    }

    /**
     * Our announcement listener watches for devices to appear on the network so we can ask them for their database
     * server port, and when they disappear discards all information about them.
     */
    private final DeviceAnnouncementListener announcementListener = new DeviceAnnouncementListener() {
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
        }
    };


    /**
     * Record the database server port reported by a player.
     *
     * @param player the player number whose server port has been determined.
     * @param port the port number on which the player's database server is running.
     */
    private void setPlayerDBServerPort(int player, int port) {
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
    private void requestPlayerDBServerPort(DeviceAnnouncement announcement) {
        Socket socket = null;
        try {
            InetSocketAddress address = new InetSocketAddress(announcement.getAddress(), DB_SERVER_QUERY_PORT);
            socket = new Socket();
            socket.connect(address, socketTimeout);
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();
            socket.setSoTimeout(socketTimeout);
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
     * The default value we will use for timeouts on opening and reading from sockets.
     */
    @SuppressWarnings("WeakerAccess")
    public static final int DEFAULT_SOCKET_TIMEOUT = 10000;

    /**
     * The number of milliseconds after which an attempt to open or read from a socket will fail.
     */
    private int socketTimeout = DEFAULT_SOCKET_TIMEOUT;

    /**
     * Set how long we will wait for a socket to connect or for a read operation to complete.
     * Adjust this if your players or network require it.
     *
     * @param timeout after how many milliseconds will an attempt to open or read from a socket fail
     */
    public synchronized void setSocketTimeout(int timeout) {
        socketTimeout = timeout;
    }

    /**
     * Check how long we will wait for a socket to connect or for a read operation to complete.
     * Adjust this if your players or network require it.
     *
     * @return the number of milliseconds after which an attempt to open or read from a socket will fail
     */
    public synchronized int getSocketTimeout() {
        return socketTimeout;
    }

    /**
     * Receive some bytes from the player we are requesting metadata from.
     *
     * @param is the input stream associated with the player metadata socket.
     * @return the bytes read.
     *
     * @throws IOException if there is a problem reading the response
     */
    private byte[] receiveBytes(InputStream is) throws IOException {
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
    @SuppressWarnings("SameParameterValue")
    private byte[] readResponseWithExpectedSize(InputStream is, int size, String description) throws IOException {
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
     * are talking to rekordbox), uses that, since it will always be safe. Otherwise, tries to borrow the player number
     * of another actual CDJ on the network, but we can't do that if the player we want to impersonate has mounted
     * a track from the player that we want to talk to.
     *
     * @param targetPlayer the player to which a metadata query is being sent
     *
     * @return some other currently active player number, ideally not a real player, but sometimes we have to
     *
     * @throws IllegalStateException if there is no other player number available to use
     */
    private int chooseAskingPlayerNumber(int targetPlayer) {
        final int fakeDevice = VirtualCdj.getInstance().getDeviceNumber();
        if ((targetPlayer > 15) || (fakeDevice >= 1 && fakeDevice <= 4)) {
            return fakeDevice;
        }

        for (DeviceAnnouncement candidate : DeviceFinder.getInstance().getCurrentDevices()) {
            final int realDevice = candidate.getNumber();
            if (realDevice != targetPlayer && realDevice >= 1 && realDevice <= 4) {
                final DeviceUpdate lastUpdate =  VirtualCdj.getInstance().getLatestStatusFor(realDevice);
                if (lastUpdate != null && lastUpdate instanceof CdjStatus &&
                        ((CdjStatus)lastUpdate).getTrackSourcePlayer() != targetPlayer) {
                    return candidate.getNumber();
                }
            }
        }
        throw new IllegalStateException("No player number available to query player " + targetPlayer +
                ". If such a player is present on the network, it must be using Link to play a track from " +
                "our target player, so we can't steal its channel number.");
    }

    /**
     * Keep track of whether we are running
     */
    private boolean running = false;

    /**
     * Check whether we are currently running.
     *
     * @return true if we are offering shared dbserver sessions
     */
    @SuppressWarnings("WeakerAccess")
    public synchronized boolean isRunning() {
        return running;
    }

    private final LifecycleListener lifecycleListener = new LifecycleListener() {
        @Override
        public void started(LifecycleParticipant sender) {
            logger.debug("ConnectionManager does not auto-start when {} starts.", sender);
        }

        @Override
        public void stopped(LifecycleParticipant sender) {
            if (isRunning()) {
                logger.info("ConnectionManager stopping because DeviceFinder did.");
                stop();
            }
        }
    };

    /**
     * Start offering shared dbserver sessions.
     *
     * @throws SocketException if there is a problem opening connections
     */
    public synchronized void start() throws SocketException {
        if (!running) {
            DeviceFinder.getInstance().addLifecycleListener(lifecycleListener);
            DeviceFinder.getInstance().addDeviceAnnouncementListener(announcementListener);
            DeviceFinder.getInstance().start();
            for (DeviceAnnouncement device: DeviceFinder.getInstance().getCurrentDevices()) {
                requestPlayerDBServerPort(device);
            }

            running = true;
            deliverLifecycleAnnouncement(logger, true);
        }
    }

    /**
     * Stop offering shared dbserver sessions.
     */
    public synchronized void stop() {
        if (running) {
            running = false;
            DeviceFinder.getInstance().removeDeviceAnnouncementListener(announcementListener);
            dbServerPorts.clear();
            for (Client client : openClients.values()) {
                try {
                    client.close();
                } catch (Exception e) {
                    logger.warn("Problem closing " + client + " when stopping", e);
                }
            }
            openClients.clear();
            useCounts.clear();
            deliverLifecycleAnnouncement(logger, false);
        }
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final ConnectionManager ourInstance = new ConnectionManager();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    public static ConnectionManager getInstance() {
        return ourInstance;
    }

    /**
     * Prevent direct instantiation.
     */
    private ConnectionManager() {
        // Nothing to do.
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ConnectionManager[running:").append(isRunning());
        sb.append(", dbServerPorts:").append(dbServerPorts).append(", openClients:").append(openClients);
        return sb.append(", useCounts:").append(useCounts).append("]").toString();
    }
}
