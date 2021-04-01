package fr.uge.net.tcp.nonblocking.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;

import static fr.uge.net.tcp.nonblocking.Config.BUFFER_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.makeTokenPacket;
import static fr.uge.net.tcp.nonblocking.client.ClientChatOS.silentlyClose;
import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.onConnectFail;
import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.onConnectSuccess;
import static java.util.Objects.requireNonNull;

public class PrivateConnectionContext implements Context {
    private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
    private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
    private final LinkedList<ByteBuffer> queue = new LinkedList<>();        // Buffer are in read-mode
    private final SocketChannel sc;
    private final SelectionKey key;
    private final String other;
    private boolean closed;

    public PrivateConnectionContext(String other, SelectionKey key) {
        this.other = requireNonNull(other);
        sc = (SocketChannel) key.channel();
        this.key = requireNonNull(key);
        closed = false;
    }
    /**
     * Initiate the connection to the server.
     */
    public void launch(int token) {
        queue.add(makeTokenPacket(token, other).toBuffer().flip());
    }
    private void processIn() {
        // Todo : Read HTTP content
        System.out.println(StandardCharsets.UTF_8.decode(bbIn).toString());
    }
    public void queueMessage(String msg) {
        // Todo : Convert msg to HTTP
        System.out.println(msg);
//        queue.add(StandardCharsets.UTF_8.encode(msg).compact());
//        processOut();
//        updateInterestOps();
    }
    private void processOut() {
        while (!queue.isEmpty()){                                       // While there is a message
            if (queue.peek().remaining() > bbOut.remaining()) break;    // Gets it and break if not enough room in buffOut
            bbOut.put(queue.remove());                                  // Otherwise -> add into buffOut
        }
    }
    private void updateInterestOps() {
        var op=0;
        if (!closed && bbIn.hasRemaining()) op  = SelectionKey.OP_READ;     // If there's something to read
        if (bbOut.position()!=0)            op |= SelectionKey.OP_WRITE;    // If there's something to write
        if (op != 0)                        key.interestOps(op);
        else                                silentlyClose(sc);              // If there's nothing to read nor write
    }
    @Override
    public void doRead() throws IOException {
        if (sc.read(bbIn)==-1) closed = true;
        processIn();
        updateInterestOps();
    }
    @Override
    public void doWrite() throws IOException {
        System.out.println("----------------SENDING----------------");
        System.out.println(bbOut);
        sc.write(bbOut.flip());
        bbOut.compact();
        processOut();
        updateInterestOps();
    }
    @Override
    public void doConnect() throws IOException {
        try {
            if (!sc.finishConnect()) return;
            processOut();
            System.out.println("coucou");
            updateInterestOps();
        } catch (IOException ioe) {
            onConnectFail();
            throw ioe;
        }
    }
}
