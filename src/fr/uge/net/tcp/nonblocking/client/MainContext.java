package fr.uge.net.tcp.nonblocking.client;

import fr.uge.net.tcp.nonblocking.Packet;
import fr.uge.net.tcp.nonblocking.reader.ClientPacketReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;

import static fr.uge.net.tcp.nonblocking.Config.BUFFER_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.*;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.makeGeneralMessagePacket;
import static fr.uge.net.tcp.nonblocking.client.ClientChatOS.silentlyClose;
import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.*;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.DONE;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.REFILL;

public class MainContext implements Context {
    private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
    private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
    private final ClientPacketReader reader = new ClientPacketReader();
    private final LinkedList<ByteBuffer> queue = new LinkedList<>();        // Stored buffer are in write-mode
    private final SelectionKey key;
    private final SocketChannel sc;
    private boolean connected;
    private boolean closed;
    private String pseudo;          // Maybe unused

    public MainContext(SelectionKey key_){
        sc = (SocketChannel) key_.channel();
        connected = false;
        closed = false;
        key = key_;
    }
    private void processIn() {
        // TODO : Try to reconnect on error to avoid having multiple problems
        var status = reader.process(bbIn);  // Processes the buffer
        if (status == REFILL) return;                           // If needs refill -> stop
        if (status == DONE) treatPacket(reader.get());          // If done -> treats the packet
        reader.reset();                                         // Resets the buffer
    }
    private void treatPacket(Packet packet) {
        onMessageReceived(packet);
        switch (packet.type()) {
            case ERR -> treatError(packet.code());
            case AUTH -> {
                connected = true;
                pseudo = packet.pseudo();
            }
            case GMSG, DMSG -> {}   // Only need to be displayed (already done)
            case TOKEN -> { // Todo : store token and start a new socket
            }
        }
    }
    private void treatError(Packet.ErrorCode code) {
        switch (code) {
            case REJECTED -> {} // Delete the pending private connection
            case WRONG_CODE, INVALID_LENGTH, OTHER -> // When receiving one of these error need to send a ERROR_RECOVERY
                    queue.addFirst(makeErrorPacket(Packet.ErrorCode.ERROR_RECOVER).toBuffer());
            default -> {}                                   // nothing
        }
    }
    @Override
    public void queueMessage(String msg) {
        queue.add(parseInput(msg).toBuffer());
        processOut();
        updateInterestOps();
    }
    private Packet parseInput(String msg) {
        if (!connected) return makeAuthenticationPacket(msg);
        if (msg.startsWith("@")) {
            var tokens = msg.split(" ", 2);
            return makeDirectMessagePacket(tokens[0].substring(1), tokens[1]);
        }
        if (msg.startsWith("/")) {
            // Todo : store tokens[1] into a var to request via GET
            var tokens = msg.split(" ", 2);
            return makePrivateConnectionPacket(tokens[0].substring(1));
        }
        return msg.startsWith("\\@") || msg.startsWith("\\/") ?
                makeGeneralMessagePacket(msg.substring(1)) : makeGeneralMessagePacket(msg);
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
        if (op==0)                          silentlyClose(sc);              // If there's nothing to read nor write
        key.interestOps(op);
    }
    public void doRead() throws IOException {
        if (sc.read(bbIn)==-1) closed = true;
        processIn();
        updateInterestOps();
    }
    public void doWrite() throws IOException {
        sc.write(bbOut.flip());
        bbOut.compact();
        processOut();
        updateInterestOps();
    }
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
