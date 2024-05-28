package org.deepsymmetry.beatlink.dbserver;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages a connection to the dbserver port on a particular player, allowing queries to be sent, and their
 * responses to be interpreted. Direct instantiation is not available outside the package, instead
 * {@link ConnectionManager#invokeWithClientSession(int, ConnectionManager.ClientTask, String)} must be used.
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public class Client {

    private static final Logger logger = LoggerFactory.getLogger(Client.class);

    /**
     * The socket on which we are communicating with the player's dbserver.
     */
    private final Socket socket;

    /**
     * The stream used to read input from the dbserver.
     */
    private final DataInputStream is;

    /**
     * The stream used to send messages to the dbserver.
     */
    private final OutputStream os;

    /**
     * The channel used to write byte buffers to the dbserver.
     */
    private final WritableByteChannel channel;

    /**
     * The player number we are communicating with.
     */
    @API(status = API.Status.STABLE)
    public final int targetPlayer;

    /**
     * The player we are pretending to be.
     */
    @API(status = API.Status.STABLE)
    public final int posingAsPlayer;

    /**
     * The greeting message exchanged over a new connection consists of a 4-byte number field containing the value 1.
     */
    @API(status = API.Status.STABLE)
    public static final NumberField GREETING_FIELD = new NumberField(1, 4);

    /**
     * Used to assign unique numbers to each transaction.
     */
    private long transactionCounter = 0;

    /**
     * The dbserver client must be constructed with a freshly-opened socket to the dbserver port on the specified
     * player number. It must be in charge of all communication with that socket. The {@link ConnectionManager} is
     * expected to be the only class that instantiates clients.
     *
     * @param socket the newly opened network socket to the dbserver on a player
     * @param targetPlayer the player number to which the socket was opened
     * @param posingAsPlayer the player number that we are pretending to be
     *
     * @throws IOException if there is a problem configuring the socket for use
     */
    Client(Socket socket, int targetPlayer, int posingAsPlayer) throws IOException {
        this.socket = socket;
        is = new DataInputStream(socket.getInputStream());
        os = socket.getOutputStream();
        channel = Channels.newChannel(os);
        this.targetPlayer = targetPlayer;
        this.posingAsPlayer = posingAsPlayer;

        try {
            // Exchange the greeting message, which is a 4-byte number field containing the value 1.
            sendField(GREETING_FIELD);
            final Field response = Field.read(is);
            if ((response instanceof NumberField) && (response.getSize() == 4) &&
                    (((NumberField) response).getValue() == 1)) {
                performSetupExchange();
            } else {
                throw new IOException("Did not receive expected greeting response from dbserver, instead got: " + response);
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /**
     * Exchanges the initial fully-formed messages which establishes the transaction context for queries to
     * the dbserver.
     *
     * @throws IOException if there is a problem during the exchange
     */
    private void performSetupExchange() throws IOException {
        Message setupRequest = new Message(0xfffffffeL, Message.KnownType.SETUP_REQ, new NumberField(posingAsPlayer, 4));
        sendMessage(setupRequest);
        Message response = Message.read(is);
        if (response.knownType != Message.KnownType.MENU_AVAILABLE) {
            throw new IOException("Did not receive message type 0x4000 in response to setup message, got: " + response);
        }
        if (response.arguments.size() != 2) {
            throw new IOException("Did not receive two arguments in response to setup message, got: " + response);
        }
        final Field player = response.arguments.get(1);
        if (!(player instanceof NumberField)) {
            throw new IOException("Second argument in response to setup message was not a number: " + response);
        }
        if (((NumberField)player).getValue() != targetPlayer) {
            throw new IOException("Expected to connect to player " + targetPlayer +
                    ", but welcome response identified itself as player " + ((NumberField)player).getValue());
        }
    }

    /**
     * Exchanges the final messages which politely report our intention to disconnect from the dbserver.
     */
    private void performTeardownExchange() throws IOException {
        Message teardownRequest = new Message(0xfffffffeL, Message.KnownType.TEARDOWN_REQ);
        sendMessage(teardownRequest);
        // At this point, the server closes the connection from its end, so we can’t read anymore.
    }

    /**
     * Check whether our connection is still available for use. We will close it if there is ever a problem
     * communicating with the dbserver.
     *
     * @return {@code true} if this instance can still be used to query the connected dbserver
     */
    @API(status = API.Status.STABLE)
    public boolean isConnected() {
        return socket.isConnected();
    }

    /**
     * Closes the connection to the dbserver. This instance can no longer be used after this action.
     */
    void close() {
        try {
            performTeardownExchange();
        } catch (IOException e) {
            logger.warn("Problem reporting our intention to close the dbserver connection", e);
        }
        try {
            channel.close();
        } catch (IOException e) {
            logger.warn("Problem closing dbserver client output channel", e);
        }
        try {
            os.close();
        } catch (IOException e) {
            logger.warn("Problem closing dbserver client output stream", e);
        }
        try {
            is.close();
        } catch (IOException e) {
            logger.warn("Problem closing dbserver client input stream", e);
        }
        try {
            socket.close();
        } catch (IOException e) {
            logger.warn("Problem closing dbserver client socket", e);
        }
    }

    /**
     * Attempt to send the specified field to the dbserver.
     * This low-level function is available only to the package itself for use in setting up the connection. It was
     * previously also used for sending parts of larger-scale messages, but because that sometimes led to them being
     * fragmented into multiple network packets, and Windows rekordbox cannot handle that, full message sending no
     * longer uses this method.
     *
     * @param field the field to be sent
     *
     * @throws IOException if the field cannot be sent
     */
    @API(status = API.Status.STABLE)
    private void sendField(Field field) throws IOException {
        if (isConnected()) {
            try {
                field.write(channel);
            } catch (IOException e) {
                logger.warn("Problem trying to write field to dbserver, closing connection", e);
                close();
                throw e;
            }
            return;
        }
        throw new IOException("sendField() called after dbserver connection was closed");
    }

    /**
     * Allocate a new transaction number for a message that is to be sent.
     *
     * @return the appropriate number field to use for the new message’s transaction argument
     */
    private NumberField assignTransactionNumber() {
        return new NumberField(++transactionCounter, 4);
    }

    /**
     * Sends a message to the dbserver, first assembling it into a single byte buffer so that it can be sent as
     * a single packet.
     *
     * @param message the message to be sent
     *
     * @throws IOException if there is a problem sending it
     */
    private void sendMessage(Message message) throws IOException {
        logger.debug("Sending> {}", message);
        int totalSize = 0;
        for (Field field : message.fields) {
            totalSize += field.getBytes().remaining();
        }
        ByteBuffer combined = ByteBuffer.allocate(totalSize);
        for (Field field : message.fields) {
            logger.debug("..sending> {}", field);
            combined.put(field.getBytes());
        }
        combined.flip();
        Util.writeFully(combined, channel);
    }

    /**
     * <p>Build the <em>R:M:S:T</em> parameter that begins many queries, when T=1.</p>
     *
     * <p>Many request messages take, as their first argument, a 4-byte value where each byte has a special meaning.
     * The first byte is the player number of the requesting player. The second identifies the menu into which
     * the response is being loaded, as described by {@link Message.MenuIdentifier}. The third specifies the media
     * slot from which information is desired, as described by {@link CdjStatus.TrackSourceSlot}, and the fourth
     * byte identifies the type of track about which information is being requested; in most requests this has the
     * value 1, which corresponds to rekordbox tracks, and this version of the function assumes that.</p>
     *
     * @param requestingPlayer the player number that is asking the question
     * @param targetMenu the destination for the response to this query
     * @param slot the media library of interest for this query
     *
     * @return the first argument to send with the query in order to obtain the desired information
     */
    @API(status = API.Status.STABLE)
    public static NumberField buildRMST(int requestingPlayer, Message.MenuIdentifier targetMenu,
                                        CdjStatus.TrackSourceSlot slot) {
        return buildRMST(requestingPlayer, targetMenu, slot, CdjStatus.TrackType.REKORDBOX);
    }

    /**
     * <p>Build the <em>R:M:S:T</em> parameter that begins many queries.</p>
     *
     * <p>Many request messages take, as their first argument, a 4-byte value where each byte has a special meaning.
     * The first byte is the player number of the requesting player. The second identifies the menu into which
     * the response is being loaded, as described by {@link Message.MenuIdentifier}. The third specifies the media
     * slot from which information is desired, as described by {@link CdjStatus.TrackSourceSlot}, and the fourth
     * byte identifies the type of track about which information is being requested; in most requests this has the
     * value 1, which corresponds to rekordbox tracks.</p>
     *
     * @param requestingPlayer the player number that is asking the question
     * @param targetMenu the destination for the response to this query
     * @param slot the media library of interest for this query
     * @param trackType the type of track about which information is being requested
     *
     * @return the first argument to send with the query in order to obtain the desired information
     */
    @API(status = API.Status.STABLE)
    public static NumberField buildRMST(int requestingPlayer, Message.MenuIdentifier targetMenu,
                                        CdjStatus.TrackSourceSlot slot, CdjStatus.TrackType trackType) {
        return new NumberField(((long) (requestingPlayer & 0x0ff) << 24) |
                ((targetMenu.protocolValue & 0xff) << 16) |
                ((slot.protocolValue & 0xff) << 8) |
                (trackType.protocolValue & 0xff));
    }

    /**
     * <p>Build the <em>R:M:S:T</em> parameter that begins many queries.</p>
     *
     * <p>Many request messages take, as their first argument, a 4-byte value where each byte has a special meaning.
     * The first byte is the player number of the requesting player. The second identifies the menu into which
     * the response is being loaded, as described by {@link Message.MenuIdentifier}. The third specifies the media
     * slot from which information is desired, as described by {@link CdjStatus.TrackSourceSlot}, and the fourth
     * byte identifies the type of track about which information is being requested; in most requests this has the
     * value 1, which corresponds to rekordbox tracks, and this version of the function assumes that.</p>
     *
     * @param targetMenu the destination for the response to this query
     * @param slot the media library of interest for this query
     *
     * @return the first argument to send with the query in order to obtain the desired information
     */
    @API(status = API.Status.STABLE)
    public NumberField buildRMST(Message.MenuIdentifier targetMenu, CdjStatus.TrackSourceSlot slot) {
        return buildRMST(posingAsPlayer, targetMenu, slot, CdjStatus.TrackType.REKORDBOX);
    }

    /**
     * <p>Build the <em>R:M:S:T</em> parameter that begins many queries.</p>
     *
     * <p>Many request messages take, as their first argument, a 4-byte value where each byte has a special meaning.
     * The first byte is the player number of the requesting player. The second identifies the menu into which
     * the response is being loaded, as described by {@link Message.MenuIdentifier}. The third specifies the media
     * slot from which information is desired, as described by {@link CdjStatus.TrackSourceSlot}, and the fourth
     * byte identifies the type of track about which information is being requested; in most requests this has the
     * value 1, which corresponds to rekordbox tracks.</p>
     *
     * @param targetMenu the destination for the response to this query
     * @param slot the media library of interest for this query
     * @param trackType the type of track about which information is being requested
     *
     * @return the first argument to send with the query in order to obtain the desired information
     */
    @API(status = API.Status.STABLE)
    public NumberField buildRMST(Message.MenuIdentifier targetMenu, CdjStatus.TrackSourceSlot slot,
                                 CdjStatus.TrackType trackType) {
        return buildRMST(posingAsPlayer, targetMenu, slot, trackType);
    }
    /**
     * Send a request that expects a single message as its response, then read and return that response.
     *
     * @param requestType identifies what kind of request to send
     * @param responseType identifies the type of response we expect, or {@code null} if we’ll accept anything
     * @param arguments The argument fields to send in the request
     *
     * @return the response from the player
     *
     * @throws IOException if there is a communication problem, or if the response does not have the same transaction
     *                     ID as the request.
     */
    @API(status = API.Status.STABLE)
    public synchronized Message simpleRequest(Message.KnownType requestType, Message.KnownType responseType,
                                              Field... arguments)
            throws IOException {
        final NumberField transaction = assignTransactionNumber();
        final Message request = new Message(transaction, new NumberField(requestType.protocolValue, 2), arguments);
        sendMessage(request);
        final Message response = Message.read(is);
        if (response.transaction.getValue() != transaction.getValue()) {
            throw new IOException("Received response with wrong transaction ID. Expected: " + transaction.getValue() +
            ", got: " + response);
        }
        if (responseType != null && response.knownType != responseType) {
            throw new IOException("Received response with wrong type. Expected: " + responseType +
            ", got: " + response);
        }
        return response;
    }

    /**
     * Send a request for a menu that we will retrieve items from in subsequent requests. This variant works for
     * nearly all menus, but when you are trying to request metadata for an unanalyzed (non-rekordbox) track, you
     * need to use {@link #menuRequestTyped(Message.KnownType, Message.MenuIdentifier, CdjStatus.TrackSourceSlot, CdjStatus.TrackType, Field...)}
     * and specify the actual type of the track whose metadata you want to retrieve.
     *
     * @param requestType identifies what kind of menu request to send
     * @param targetMenu the destination for the response to this query
     * @param slot the media library of interest for this query
     * @param arguments the additional arguments needed, if any, to complete the request
     *
     * @return the {@link Message.KnownType#MENU_AVAILABLE} response reporting how many items are available in the menu
     *
     * @throws IOException if there is a problem communicating, or if the requested menu is not available
     * @throws IllegalStateException if {@link #tryLockingForMenuOperations(long, TimeUnit)} was not called successfully
     *         before attempting this call
     */
    @API(status = API.Status.STABLE)
    public Message menuRequest(Message.KnownType requestType, Message.MenuIdentifier targetMenu,
                                            CdjStatus.TrackSourceSlot slot, Field... arguments)
        throws IOException {
        return menuRequestTyped(requestType, targetMenu, slot, CdjStatus.TrackType.REKORDBOX, arguments);
    }

    /**
     * Send a request for a menu that we will retrieve items from in subsequent requests, when the request must reflect
     * the actual type of track being asked about.
     *
     * @param requestType identifies what kind of menu request to send
     * @param targetMenu the destination for the response to this query
     * @param slot the media library of interest for this query
     * @param trackType the type of track for which metadata is being requested, since this affects the request format
     * @param arguments the additional arguments needed, if any, to complete the request
     *
     * @return the {@link Message.KnownType#MENU_AVAILABLE} response reporting how many items are available in the menu
     *
     * @throws IOException if there is a problem communicating, or if the requested menu is not available
     * @throws IllegalStateException if {@link #tryLockingForMenuOperations(long, TimeUnit)} was not called successfully
     *         before attempting this call
     */
    @API(status = API.Status.STABLE)
    public synchronized Message menuRequestTyped(Message.KnownType requestType, Message.MenuIdentifier targetMenu,
                                                 CdjStatus.TrackSourceSlot slot, CdjStatus.TrackType trackType, Field... arguments)
            throws IOException {

        if (!menuLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("renderMenuItems() cannot be called without first successfully calling tryLockingForMenuOperation()");
        }

        Field[] combinedArguments = new Field[arguments.length + 1];
        combinedArguments[0] = buildRMST(targetMenu, slot, trackType);
        System.arraycopy(arguments, 0, combinedArguments, 1, arguments.length);
        final Message response = simpleRequest(requestType, Message.KnownType.MENU_AVAILABLE, combinedArguments);
        final NumberField reportedRequestType = (NumberField)response.arguments.get(0);
        if (reportedRequestType.getValue() != requestType.protocolValue) {
            throw new IOException("Menu request did not return result for same type as request; sent type: " +
                    requestType.protocolValue + ", received type: " + reportedRequestType.getValue() +
                    ", response: " + response);
        }
        return response;
    }

    /**
     * The default maximum number of menu items we will request at a single time. We are not sure what the largest safe
     * value to use is, but 64 seems to work well for CDJ-2000 nexus players.
     */
    @API(status = API.Status.STABLE)
    public static final int DEFAULT_MENU_BATCH_SIZE = 64;

    /**
     * Ghe maximum number of menu items we will request at a single time. We are not sure what the largest safe
     * value to use is, but 64 seems to work well for CDJ-2000 nexus players. Changing this will affect future calls
     * to {@link #renderMenuItems(Message.MenuIdentifier, CdjStatus.TrackSourceSlot, CdjStatus.TrackType trackType, Message)}.
     *
     * @return the maximum number of items {@link #renderMenuItems(Message.MenuIdentifier, CdjStatus.TrackSourceSlot, CdjStatus.TrackType trackType, Message)}
     *         will request at once
     */
    @API(status = API.Status.STABLE)
    public static long getMenuBatchSize() {
        return menuBatchSize.get();
    }

    /**
     * Set the maximum number of menu items we will request at a single time. We are not sure what the largest safe
     * value to use is, but 64 seems to work well for CDJ-2000 nexus players. Changing this will affect future calls
     * to {@link #renderMenuItems(Message.MenuIdentifier, CdjStatus.TrackSourceSlot, CdjStatus.TrackType trackType, Message)}.
     *
     * @param batchSize the maximum number of items {@link #renderMenuItems(Message.MenuIdentifier, CdjStatus.TrackSourceSlot, CdjStatus.TrackType trackType, Message)}
     *                      will request at once
     */
    @API(status = API.Status.STABLE)
    public static void setMenuBatchSize(int batchSize) {
        menuBatchSize.set(batchSize);
    }

    /**
     * The maximum number of menu items we will request at a single time. We are not sure what the largest safe
     * value to use is, but 64 seems to work well for CDJ-2000 nexus players. Changing this will affect future calls
     * to {@link #renderMenuItems(Message.MenuIdentifier, CdjStatus.TrackSourceSlot, CdjStatus.TrackType trackType, Message)}.
     */
    private static final AtomicInteger menuBatchSize = new AtomicInteger(DEFAULT_MENU_BATCH_SIZE);

    /**
     * Used to ensure that only one thread at a time is attempting to perform menu operations, which require more than
     * one request/response cycle.
     */
    private final ReentrantLock menuLock = new ReentrantLock();

    /**
     * Attempt to secure exclusive access to this player for performing a menu operation, which requires multiple
     * request/response cycles. The caller <em>must</em> call {@link #unlockForMenuOperations()} as soon as it is
     * done (even if it is failing because of an exception), or no future menu operations will be possible by any
     * other thread, unless {@code false} was returned, meaning the attempt failed.
     *
     * @param timeout the time to wait for the lock
     * @param unit the time unit of the timeout argument
     *
     * @return {@code true} if the lock was acquired within the specified time
     *
     * @throws InterruptedException if the thread is interrupted while waiting for the lock
     */
    @API(status = API.Status.STABLE)
    public boolean tryLockingForMenuOperations(long timeout, TimeUnit unit) throws InterruptedException {
        return menuLock.tryLock(timeout, unit);
    }

    /**
     * Allow other threads to perform menu operations. This <em>must</em> be called promptly, once for each time that
     * {@link #tryLockingForMenuOperations(long, TimeUnit)} was called with a {@code true} return value.
     */
    @API(status = API.Status.STABLE)
    public void unlockForMenuOperations() {
        menuLock.unlock();
    }

    /**
     * Gather all the responses that are available for a menu request. Will involve multiple requests if
     * the number of responses available is larger than our maximum batch size (see {@link #getMenuBatchSize()}).
     *
     * @param targetMenu the destination for the response to this query
     * @param slot the media library of interest for this query
     * @param trackType the type of track about which information is being requested
     * @param availableResponse the response to the initial menu setup request, reporting how many responses are
     *                          available, as well as the target menu we are working with
     *
     * @return all the response items, using as many queries as necessary to gather them safely, and omitting all
     *         the header and footer items
     *
     * @throws IOException if there is a problem reading the menu items
     * @throws IllegalStateException if {@link #tryLockingForMenuOperations(long, TimeUnit)} was not called successfully
     *         before attempting this call
     */
    @API(status = API.Status.STABLE)
    public synchronized List<Message> renderMenuItems(Message.MenuIdentifier targetMenu, CdjStatus.TrackSourceSlot slot,
                                                      CdjStatus.TrackType trackType, Message availableResponse)
            throws IOException {
        final long count = availableResponse.getMenuResultsCount();
        if (count == Message.NO_MENU_RESULTS_AVAILABLE || count == 0) {
            return Collections.emptyList();
        }
        return renderMenuItems(targetMenu, slot, trackType, 0, (int) count);
    }

    /**
     * Gather the specified range of responses for a menu request. Will involve multiple requests if
     * the number of responses requested is larger than our maximum batch size (see {@link #getMenuBatchSize()}).
     * It is the caller's responsibility to make sure that {@code offset} and {@code count} remain within the
     * legal, available menu items based on the initial menu setup request. Most use cases will be best served
     * by the simpler {@link #renderMenuItems(Message.MenuIdentifier, CdjStatus.TrackSourceSlot, CdjStatus.TrackType, Message)}.
     *
     * @param targetMenu the destination for the response to this query
     * @param slot the media library of interest for this query
     * @param trackType the type of track about which information is being requested
     * @param offset the first response desired (the first one available has offset 0)
     * @param count the number of responses desired
     *
     * @return the response items, using as many queries as necessary to gather them safely, and omitting all
     *         the header and footer items
     *
     * @throws IOException if there is a problem reading the menu items
     * @throws IllegalStateException if {@link #tryLockingForMenuOperations(long, TimeUnit)} was not called successfully
     *         before attempting this call
     */
    @API(status = API.Status.STABLE)
    public synchronized List<Message> renderMenuItems(Message.MenuIdentifier targetMenu, CdjStatus.TrackSourceSlot slot,
                                                      CdjStatus.TrackType trackType, int offset, int count)
            throws IOException {

        if (!menuLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("renderMenuItems() cannot be called without first successfully calling tryLockingForMenuOperation()");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be nonnegative");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }

        final ArrayList<Message> results = new ArrayList<>(count);
        int gathered = 0;
        while (gathered < count) {
            final int batchSize = (Math.min(count - gathered, menuBatchSize.get()));
            final NumberField transaction = assignTransactionNumber();
            final NumberField limit = new NumberField(batchSize);
            final NumberField total = new NumberField(count);
            final Message request = new Message(transaction,
                    new NumberField(Message.KnownType.RENDER_MENU_REQ.protocolValue, 2),
                    buildRMST(targetMenu, slot, trackType), new NumberField(offset), limit, NumberField.WORD_0, total, NumberField.WORD_0);

            sendMessage(request);
            Message response = Message.read(is);

            if (response.transaction.getValue() != transaction.getValue()) {
                throw new IOException("Received response with wrong transaction ID. Expected: " + transaction.getValue() +
                        ", got: " + response);
            }
            if (response.knownType != Message.KnownType.MENU_HEADER) {
                throw new IOException("Expecting MENU_HEADER, instead got: " + response);
            }
            response = Message.read(is);

            while (response.knownType == Message.KnownType.MENU_ITEM) {
                results.add(response);
                response = Message.read(is);
            }

            if (response.knownType != Message.KnownType.MENU_FOOTER) {
                throw new IOException("Expecting MENU_ITEM or MENU_FOOTER, instead got: " + response);
            }

            offset += batchSize;
            gathered += batchSize;
        }
        return Collections.unmodifiableList(results);
    }

    @Override
    public String toString() {
        return "DBServer Client[targetPlayer: " + targetPlayer + ", posingAsPlayer: " + posingAsPlayer +
                ", transactionCounter: " + transactionCounter + ", menuBatchSize: " + getMenuBatchSize() + "]";
    }
}
