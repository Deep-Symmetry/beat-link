package org.deepsymmetry.beatlink.dbserver;

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
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Manages a connection to the dbserver port on a particular player, allowing queries to be sent, and their
 * responses to be interpreted.
 *
 * @author James Elliott
 */
public class Client {

    private static final Logger logger = LoggerFactory.getLogger(Client.class.getName());

    /**
     * The socket on which we are communicating with the player's dbserver.
     */
    final Socket socket;

    /**
     * The stream used to read input from the dbserver.
     */
    final DataInputStream is;

    /**
     * The stream used to send messages to the dbserver.
     */
    final OutputStream os;

    /**
     * The channel used to write byte buffers to the dbserver.
     */
    final WritableByteChannel channel;

    /**
     * The player number we are communicating with.
     */
    final int targetPlayer;

    /**
     * The player we are pretending to be.
     */
    final int posingAsPlayer;

    /**
     * The greeting message exchanged over a new connection consists of a 4-byte number field containing the value 1.
     */
    public static final NumberField GREETING_FIELD = new NumberField(1, 4);

    /**
     * Used to assign unique numbers to each transaction.
     */
    long transactionCounter = 0;

    /**
     * The dbserver client must be constructed with a freshly-opened socket to the dbserver port on the specified
     * player number. It must be in charge of all communication with that socket.
     *
     * @param socket the newly opened network socket to the dbserver on a player
     * @param targetPlayer the player number to which the socket was opened
     * @param posingAsPlayer the player number that we are pretending to be
     *
     * @throws IOException if there is a problem configuring the socket for use
     */
    public Client(Socket socket, int targetPlayer, int posingAsPlayer) throws IOException {
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
     * Check whether our connection is still available for use. We will close it if there is ever a problem
     * communicating with the dbserver.
     *
     * @return {@code true} if this instance can still be used to query the connected dbserver
     */
    public boolean isConnected() {
        return socket.isConnected();
    }

    /**
     * Closes the connection to the dbserver. This instance can no longer be used after this action.
     */
    public void close() {
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
     * Attempt to write the specified field to the specified channel.
     *
     * @param field the field to be written
     * @param channel the channel to which it should be written
     *
     * @throws IOException if there is a problem writing to the channel
     */
    void writeField(Field field, WritableByteChannel channel) throws IOException {
        logger.debug("..writing> {}", field);
        Util.writeFully(field.getBytes(), channel);
    }

    /**
     * Attempt to send the specified field to the dbserver.
     * This low-level function is available only to the package itself for use in setting up the connection and
     * sending parts of larger-scale messages.
     *
     * @param field the field to be sent
     *
     * @throws IOException if the field cannot be sent
     */
    void sendField(Field field) throws IOException {
        if (isConnected()) {
            try {
                writeField(field, channel);
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
    public synchronized NumberField assignTransactionNumber() {
        return new NumberField(++transactionCounter, 4);
    }

    /**
     * Sends a message to the dbserver.
     *
     * @param message the message to be sent
     *
     * @throws IOException if there is a problem sending it
     */
    public synchronized void sendMessage(Message message) throws IOException {
        logger.debug("Sending> {}", message);
        for (Field field : message.fields) {
            sendField(field);
        }
    }

    /**
     * Writes a message to the specified channel, for example when creating metadata cache files.
     *
     * @param message the message to be written
     * @param channel the channel to which it should be written
     *
     * @throws IOException if there is a problem writing to the channel
     */
    public void writeMessage(Message message, WritableByteChannel channel) throws IOException {
        logger.debug("Writing> {}", message);
        for (Field field : message.fields) {
            writeField(field, channel);
        }

    }

    /**
     * Build the <em>R:M:S:1</em> parameter that begins many queries.
     *
     * Many request messages take, as their first argument, a 4-byte value where each byte has a special meaning.
     * The first byte is the player number of the requesting player. The second identifies the menu into which
     * the response is being loaded, as described by {@link Message.MenuIdentifier}. The third specifies the media
     * slot from which information is desired, as described by {@link CdjStatus.TrackSourceSlot}, and the fourth
     * byte always seems to be <em>1</em> (Austin's libpdjl called it <em>sourceAnalyzed</em>).
     *
     * @param requestingPlayer the player number that is asking the question
     * @param targetMenu the destination for the response to this query
     * @param slot the media library of interest for this query
     *
     * @return the first argument to send with the query in order to obtain the desired information
     */
    public static NumberField buildRMS1(int requestingPlayer, Message.MenuIdentifier targetMenu,
                                        CdjStatus.TrackSourceSlot slot) {
        return new NumberField(((requestingPlayer & 0x0ff) << 24) |
                ((targetMenu.protocolValue & 0xff) << 16) |
                ((slot.protocolValue & 0xff) << 8) | 0x01);
    }

    /**
     * Build the <em>R:M:S:1</em> parameter that begins many queries.
     *
     * Many request messages take, as their first argument, a 4-byte value where each byte has a special meaning.
     * The first byte is the player number of the requesting player. The second identifies the menu into which
     * the response is being loaded, as described by {@link Message.MenuIdentifier}. The third specifies the media
     * slot from which information is desired, as described by {@link CdjStatus.TrackSourceSlot}, and the fourth
     * byte always seems to be <em>1</em> (Austin's libpdjl called it <em>sourceAnalyzed</em>).
     *
     * @param targetMenu the destination for the response to this query
     * @param slot the media library of interest for this query
     *
     * @return the first argument to send with the query in order to obtain the desired information
     */
    public NumberField buildRMS1(Message.MenuIdentifier targetMenu, CdjStatus.TrackSourceSlot slot) {
        return buildRMS1(posingAsPlayer, targetMenu, slot);
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
     * Send a request for a menu that we will retrieve items from in subsequent requests.
     *
     * @param requestType identifies what kind of menu request to send
     * @param targetMenu the destination for the response to this query
     * @param slot the media library of interest for this query
     * @param arguments the additional arguments needed, if any, to complete the request
     *
     * @return the {@link Message.KnownType#MENU_AVAILABLE} response reporting how many items are available in the menu
     *
     * @throws IOException if there is a problem communicating, or if the requested menu is not available
     */
    public synchronized Message menuRequest(Message.KnownType requestType, Message.MenuIdentifier targetMenu,
                               CdjStatus.TrackSourceSlot slot, Field... arguments)
        throws IOException {
        Field[] combinedArguments = new Field[arguments.length + 1];
        combinedArguments[0] = buildRMS1(targetMenu, slot);
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
    public static final long DEFAULT_MENU_BATCH_SIZE = 64;

    /**
     * Ghe maximum number of menu items we will request at a single time. We are not sure what the largest safe
     * value to use is, but 64 seems to work well for CDJ-2000 nexus players. Changing this will affect future calls
     * to {@link #renderMenuItems(Message.MenuIdentifier, CdjStatus.TrackSourceSlot, Message)}.
     *
     * @return the maximum number of items {@link #renderMenuItems(Message.MenuIdentifier, CdjStatus.TrackSourceSlot, Message)}
     *         will request at once
     */
    public synchronized long getMenuBatchSize() {
        return menuBatchSize;
    }

    /**
     * Set the maximum number of menu items we will request at a single time. We are not sure what the largest safe
     * value to use is, but 64 seems to work well for CDJ-2000 nexus players. Changing this will affect future calls
     * to {@link #renderMenuItems(Message.MenuIdentifier, CdjStatus.TrackSourceSlot, Message)}.
     *
     * @param menuBatchSize the maximum number of items {@link #renderMenuItems(Message.MenuIdentifier, CdjStatus.TrackSourceSlot, Message)}
     *                      will request at once
     */
    public synchronized void setMenuBatchSize(long menuBatchSize) {
        this.menuBatchSize = menuBatchSize;
    }

    /**
     * The maximum number of menu items we will request at a single time. We are not sure what the largest safe
     * value to use is, but 64 seems to work well for CDJ-2000 nexus players. Changing this will affect future calls
     * to {@link #renderMenuItems(Message.MenuIdentifier, CdjStatus.TrackSourceSlot, Message)}.
     */
    private long menuBatchSize = DEFAULT_MENU_BATCH_SIZE;


    /**
     * Gather up all the responses that are available for a menu request. Will involve multiple requests if
     * the number of responses available is larger than our maximum batch size (see {@link #getMenuBatchSize()}.
     *
     * @param targetMenu the destination for the response to this query
     * @param slot the media library of interest for this query
     * @param availableResponse the response to the initial menu setup request, reporting how many responses are
     *                          available, as well as the target menu we are working with
     *
     * @return all the response items, using as many queries as necessary to gather them safely, and omitting all
     *         the header and footer items
     *
     * @throws IOException if there is a problem reading the menu items
     */
    public synchronized List<Message> renderMenuItems(Message.MenuIdentifier targetMenu, CdjStatus.TrackSourceSlot slot,
                                                      Message availableResponse)
            throws IOException {
        final long count = availableResponse.getMenuResultsCount();
        if (count == Message.NO_MENU_RESULTS_AVAILABLE) {
            return Collections.emptyList();
        }
        final LinkedList<Message> results = new LinkedList<Message>();
        final Field zeroField = new NumberField(0);
        long offset = 0;
        while (offset < count) {
            final long batchSize = (Math.min(count - offset, menuBatchSize));
            final NumberField transaction = assignTransactionNumber();
            final NumberField limit = new NumberField(batchSize);
            final Message request = new Message(transaction,
                    new NumberField(Message.KnownType.RENDER_MENU_REQ.protocolValue, 2),
                    buildRMS1(targetMenu, slot), new NumberField(offset), limit, zeroField, limit, zeroField);
            // TODO: Based on LinkInfo.tracklist.txt it looks like the last limit should be count instead?

            sendMessage(request);
            Message response = Message.read(is);

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
        }
        return Collections.unmodifiableList(results);
    }
}
