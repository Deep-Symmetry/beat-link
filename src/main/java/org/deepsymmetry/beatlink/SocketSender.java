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
     * @param packet sends packet to socket connection to be queued by the OS.
     * @throws IOException Throws this if the socket can't connect or has failed in another way
     */
    void send(DatagramPacket packet) throws IOException;

    InetAddress getLocalAddress();
}
