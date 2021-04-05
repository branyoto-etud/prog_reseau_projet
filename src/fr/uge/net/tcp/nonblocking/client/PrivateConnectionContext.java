package fr.uge.net.tcp.nonblocking.client;

import fr.uge.net.tcp.nonblocking.reader.HTTPPacket;
import fr.uge.net.tcp.nonblocking.reader.HTTPReader;
import fr.uge.net.tcp.nonblocking.reader.Reader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;

import static fr.uge.net.tcp.nonblocking.Config.*;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.makeTokenPacket;
import static fr.uge.net.tcp.nonblocking.client.ClientChatOS.silentlyClose;
import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.onConnectFail;
import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.onConnectSuccess;
import static fr.uge.net.tcp.nonblocking.reader.HTTPPacket.HTTPPacketType.REQUEST;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.DONE;
import static java.util.Objects.requireNonNull;
class PrivateConnectionContext implements Context {
    private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
    private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
    private final LinkedList<ByteBuffer> queue = new LinkedList<>();        // Buffer are in read-mode
    private final HTTPReader reader = new HTTPReader();
    private final SocketChannel sc;
    private final SelectionKey key;
    private final String other;
    private boolean connected;
    private boolean closed;

    public PrivateConnectionContext(String other, SelectionKey key) {
        this.other = requireNonNull(other);
        sc = (SocketChannel) key.channel();
        this.key = requireNonNull(key);
    }
    /**
     * Initiate the connection to the server.
     */
    public void launch(int token) {
        queue.add(makeTokenPacket(token, other).toBuffer().flip());
    }
    private void processIn() {
        // Todo : Read HTTP content
        System.out.println("Msg received : " + StandardCharsets.UTF_8.decode(bbIn.flip()).toString());
        bbIn.compact();
        if (DEBUG_BUFFER) System.out.println("bbIn = " + bbIn);
    }
    public void queueMessage(String msg) {
        System.out.println("Msg to send : " + msg);
        queue.add(StandardCharsets.UTF_8.encode(msg));
        processOut();
        updateInterestOps();
    }
    private void processOut() {
        if (DEBUG_METHOD) System.out.println("---processOut---");
        if (queue.isEmpty()) return;
        if (bbOut.position() != 0) return;
        bbOut.clear();
        bbOut.put(queue.remove());
        if (DEBUG_BUFFER) System.out.println("bbOut = " + bbOut);
    }
    private void updateInterestOps() {
        var op=0;
        if (!closed && bbIn.hasRemaining()) op  = SelectionKey.OP_READ;     // If there's something to read
        if (bbOut.position()!=0)            op |= SelectionKey.OP_WRITE;    // If there's something to write
        if (!connected)                     op  = SelectionKey.OP_CONNECT;  // If not connected only connect
        if (op == 0)                        silentlyClose(sc);              // If there's nothing to read nor write
        else                                key.interestOps(op);
    }
    @Override
    public void doRead() throws IOException {
        if (DEBUG_METHOD) System.out.println("---READ---");
        if (sc.read(bbIn)==-1) {
            if (DEBUG_CONNECTION) System.out.println("\u001B[91m" + "Connection closed!!!!!!!!" + "\u001B[0m");
            closed = true;
        }
        if (DEBUG_BUFFER) System.out.println("bbIn = " + bbIn);
        processIn();
        updateInterestOps();
    }
    @Override
    public void doWrite() throws IOException {
        if (DEBUG_METHOD) System.out.println("---WRITE---");
        if (DEBUG_BUFFER) System.out.println("bbOut = " + bbOut);
        sc.write(bbOut.flip());
        bbOut.compact();
        if (DEBUG_BUFFER) System.out.println("bbOut = " + bbOut);
        processOut();
        updateInterestOps();
    }
    @Override
    public void doConnect() throws IOException {
        try {
            if (!sc.finishConnect()) return;
            if (DEBUG_CONNECTION) System.out.println("New socket : " + sc.getLocalAddress());
            connected = true;
            processOut();
            updateInterestOps();
        } catch (IOException ioe) {
            onConnectFail();
            throw ioe;
        }
    }
}
