package org.deepsymmetry.beatlink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicReference;

public interface SocketSender {

    /**
     * Convenience method to wrap the socket sending so that other classes can use it.
     * This does not need to be synchronized because the whole packet is sent to the OS
     * to be queued to send, safe to send from many threads.
     *
     * @param packet
     * @throws IOException
     */
    void send(DatagramPacket packet) throws IOException;

    /**
     * A method for socket senders to provide access to the underlying socket, but only
     * for sending reasons. If a process wants to receive from a socket, they must register
     * a listener.
     *
     * @return SocketSender
     */
    SocketSender getSocketSender();

    InetAddress getLocalAddress();

    void close();
}
