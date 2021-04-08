package fr.uge.net.tcp.nonblocking.context;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Objects;

import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.onConnectFail;
import static fr.uge.net.tcp.nonblocking.utils.ChatOSUtils.BUFFER_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.utils.ChatOSUtils.silentlyClose;
import static java.nio.channels.SelectionKey.*;

/**
 * Abstract context are used to group all common methods among all implementations of {@link Context}.
 */
public abstract class AbstractContext implements Context {
    /**
     * Output buffer is in write-mode.
     */
    private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
    /**
     * The stored buffers are in read-mode.
     */
    protected final LinkedList<ByteBuffer> queue = new LinkedList<>();
    /**
     * Input buffer is in write-mode.
     */
    protected final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
    private final SelectionKey key;
    private final SocketChannel sc;
    private boolean closed = false;
    private boolean connected = false;

    /**
     * @param key the connection key.
     */
    public AbstractContext(SelectionKey key) {
        this.key = Objects.requireNonNull(key);
        sc = (SocketChannel) key.channel();
    }
    /**
     * Reads what's inside bbIn and do something with it.
     */
    public abstract void processIn();
    /**
     * Adds this buffer to the queue and tries to fill {@link #bbOut}.
     * @param buff the buffer in write mode to add to the {@link #queue} to send.
     */
    public void queueMessage(ByteBuffer buff) {
        queue.add(buff);
        processOut();
        updateInterestOps();
    }
    /**
     * Takes an element from the {@link #queue} and
     * stores it into {@link #bbOut} if empty.
     */
    public void processOut() {
        if (queue.isEmpty() || bbOut.position() != 0) return;
        bbOut.clear()
             .put(queue.remove());
    }
    /**
     * Updates the interest operators of {@link #key} based on the values inside of
     * {@link #bbIn} and {@link #bbOut}.
     * The operators will be set to:
     * <ul>
     *     <li> {@link SelectionKey#OP_READ} if the channel isn't closed and there's space left in {@link #bbIn}.
     *     <li> {@link SelectionKey#OP_WRITE} if {@link #bbOut} has something to write.
     * </ul>
     * The operators can be cumulated. If none of the above conditions are met, the channel is closed.
     *
     * @return the value of the operator assigned to the key.
     */
    public int updateInterestOps() {
        var op = 0;
        if (!closed && bbIn.hasRemaining()) op |= OP_READ;
        if (bbOut.position() != 0)          op |= OP_WRITE;
        if (!connected)                     op  = OP_CONNECT;
        if (op == 0)                        close();
        else                                key.interestOps(op);
        return op;
    }
    /**
     * Reads data in {@link #bbIn}.
     * If there is no data to read, {@link #closed} is set to true.
     *
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public void doRead() throws IOException {
        if (sc.read(bbIn) == -1) closed = true;
        processIn();
        updateInterestOps();
    }
    /**
     * Writes data from {@link #bbOut}.
     *
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public void doWrite() throws IOException {
        sc.write(bbOut.flip());
        bbOut.compact();
        processOut();
        updateInterestOps();
    }
    /**
     * If the connection is successful, updates interest operator.
     *
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public void doConnect() throws IOException {
        try {
            if (!sc.finishConnect()) return;
            connected = true;
            processOut();
            updateInterestOps();
        } catch (IOException ioe) {
            onConnectFail();
            throw ioe;
        }
    }

    /**
     * @return if the current socket is closed or not.
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * Closes properly the socket.
     */
    public void close() {
        silentlyClose(sc);
        connected = false;
    }

    public void setConnected() {
        connected = true;
    }
}
