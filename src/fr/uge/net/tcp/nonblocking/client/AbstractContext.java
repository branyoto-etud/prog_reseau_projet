package fr.uge.net.tcp.nonblocking.client;

import fr.uge.net.tcp.nonblocking.Packet;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Objects;

import static fr.uge.net.tcp.nonblocking.ChatOSUtils.silentlyClose;
import static fr.uge.net.tcp.nonblocking.Config.BUFFER_MAX_SIZE;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

/**
 * Abstract context are used to group all methods common between all implementations of {@link Context}.
 */
public abstract class AbstractContext implements Context {
    /**
     * Output buffer is in write-mode.
     */
    private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
    /**
     * The stored buffers are in read-mode.
     */
    private final LinkedList<ByteBuffer> queue = new LinkedList<>();
    /**
     * Input buffer is in write-mode.
     */
    final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
    private final SocketChannel sc;
    private final SelectionKey key;
    private boolean closed = false;

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
    abstract void processIn();

    /**
     * Adds this message to the queue and tries to fill {@link #bbOut}.
     * @param msg the message to send.
     */
    public void queueMessage(Packet msg) {
        queue.add(msg.toBuffer());
        processOut();
        updateInterestOps();
    }

    /**
     * Takes an element from the {@link #queue} and
     * stores it into {@link #bbOut} if empty.
     */
    private void processOut() {
        if (queue.isEmpty() || bbOut.position() != 0) return;
        bbOut.clear()
             .put(queue.remove());
    }

    /**
     * Updates the interest operators of {@link #key} based on the values inside
     * {@link #bbIn} and {@link #bbOut}.
     * The operators will be sets to:
     * <ul>
     *     <li> {@link SelectionKey#OP_READ} if the channel isn't closed and there's space left in {@link #bbIn}.
     *     <li> {@link SelectionKey#OP_WRITE} if {@link #bbOut} has something to write.
     * </ul>
     * The operators can be cumulated. If the none of the above conditions are meet, the channel is closed.
     *
     * @return the value of the operator assigned to the key.
     */
    private int updateInterestOps() {
        var op = 0;
        if (!closed && bbIn.hasRemaining()) op |= OP_READ;
        if (bbOut.position() != 0)          op |= OP_WRITE;
        if (op == 0)                        silentlyClose(sc);
        else                                key.interestOps(op);
        return op;
    }

    /**
     * Reads data in {@link #bbOut}.
     * If there's no data to read, {@link #closed} is sets to true.
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
     * If the connection is successful, update interest operator.
     *
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public void doConnect() throws IOException {
        if (sc.finishConnect()) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }
}
