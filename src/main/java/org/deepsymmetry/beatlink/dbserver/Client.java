package org.deepsymmetry.beatlink.dbserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;

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
    public final NumberField greeting = new NumberField(1, 4);

    /**
     * Used to assign unique numbers to each transaction.
     */
    int transactionCounter = 0;

    /**
     * The dbserver client must be constructed with a freshly-opened socket to the dbserver port on the specified
     * player number. It must be in charge of all communication with that socket.
     *
     * @param socket the newly opened network socket to the dbserver on a player.
     * @param targetPlayer the player number to which the socket was opened.
     * @param posingAsPlayer the player number that we are pretending to be.
     *
     * @throws IOException if there is a problem configuring the socket for use.
     */
    public Client(Socket socket, int targetPlayer, int posingAsPlayer) throws IOException {
        socket.setSoTimeout(3000);
        this.socket = socket;
        is = new DataInputStream(socket.getInputStream());
        os = socket.getOutputStream();
        channel = Channels.newChannel(os);
        this.targetPlayer = targetPlayer;
        this.posingAsPlayer = posingAsPlayer;

        // Exchange the greeting message, which is a 4-byte number field containing the value 1.
        sendField(greeting);
        final Field response = Field.read(is);
        if ((response instanceof NumberField) && (response.getSize() == 4) &&
                (((NumberField) response).getValue() == 1)) {
            performSetupExchange();
        } else {
            throw new IOException("Did not receive expected greeting response from dbserver, instead got: " + response);
        }
    }

    /**
     * Exchanges the initial fully-formed messages which establishes the transaction context for queries to the dbserver.
     */
    private void performSetupExchange() {
        // TODO: Implement!
    }

    /**
     * Check whether our connection is still available for use. We will close it if there is ever a problem
     * communicating with the dbserver.
     *
     * @return {@code true} if this instance can still be used to query the connected dbserver.
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
     * Attempt to send the specified field to the dbserver, returning an indication of whether the attempt was made.
     * This low-level function is available only to the package itself for use in setting up the connection and
     * sending parts of larger-scale messages.
     *
     * @param field the field to be sent.
     *
     * @return {@code true} if the bytes were written, or {@code false} if something prevented that.
     */
    boolean sendField(Field field) {
        if (isConnected()) {
            try {
                final ByteBuffer buffer = field.getBytes();
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            } catch (IOException e) {
                logger.warn("Problem trying to write field to dbserver, closing connection", e);
                close();
                return false;
            }
            return true;
        }
        logger.warn("sendField() called after dbserver connection was closed, ignoring");
        return false;
    }
}
