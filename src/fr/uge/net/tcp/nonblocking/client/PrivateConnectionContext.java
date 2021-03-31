package fr.uge.net.tcp.nonblocking.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Objects;

import static fr.uge.net.tcp.nonblocking.Config.BUFFER_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.onConnectFail;
import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.onConnectSuccess;
import static java.util.Objects.requireNonNull;

public class PrivateConnectionContext implements Context {
    private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
    private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
    // Queue of messages
    // bool for connection
    private final SocketChannel sc;
    private final SelectionKey key;
    private final String other;
    private int token;

    public PrivateConnectionContext(String other, SelectionKey key) {
        this.other = requireNonNull(other);
        sc = (SocketChannel) key.channel();
        this.key = requireNonNull(key);
    }

    @Override
    public void queueMessage(String message) {
    }

    @Override
    public void doWrite() throws IOException { // Write in HTTP

    }
    @Override
    public void doRead() throws IOException { // Read in HTTP

    }
    @Override
    public void doConnect() throws IOException {
        try {
            if (!sc.finishConnect()) return;
            onConnectSuccess();
            key.interestOps(SelectionKey.OP_WRITE);
        } catch (IOException ioe) {
            onConnectFail();
            throw ioe;
        }
    }
}
