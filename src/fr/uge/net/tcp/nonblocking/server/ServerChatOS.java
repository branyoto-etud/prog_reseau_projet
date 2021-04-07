package fr.uge.net.tcp.nonblocking.server;

import fr.uge.net.tcp.nonblocking.Packet;
import fr.uge.net.tcp.nonblocking.client.Context;
import fr.uge.net.tcp.nonblocking.reader.PacketReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.logging.Logger;

import static fr.uge.net.tcp.nonblocking.ChatOSUtils.copyBuffer;
import static fr.uge.net.tcp.nonblocking.Config.BUFFER_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.Packet.ErrorCode.*;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.*;
import static fr.uge.net.tcp.nonblocking.Packet.PacketType.AUTH;
import static fr.uge.net.tcp.nonblocking.Packet.PacketType.TOKEN;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.*;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static java.util.Objects.requireNonNull;

public class ServerChatOS {
    private class ConnectionContext implements Context {
        private final LinkedList<ByteBuffer> queue = new LinkedList<>();
        private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final PacketReader reader = new PacketReader();
        private final SelectionKey key;
        private final SocketChannel sc;
        private boolean closed = false;
        private boolean rejecting = false;      // Set to true after an error. Imply that all read packet are not treated

        private ConnectionContext(SelectionKey key){
            this.key = key;
            sc = (SocketChannel) key.channel();
        }
        private void processIn() {
            var status = reader.process(bbIn);      // Process Input
            if (status == REFILL) return;           // If not full -> stop
            if (status == ERROR) {                  // On error
                if (!rejecting) sendError();        // If first error -> send error packet
                rejecting = true;                   // reject all other packet (correct or incorrect)
            }
            if (status == DONE) treatPacket(reader.get());
            reader.reset();
        }
        private void sendError() {
            switch (reader.getFailure()) {
                case CODE -> queueMessage(makeErrorPacket(WRONG_CODE));
                case LENGTH -> queueMessage(makeErrorPacket(INVALID_LENGTH));
            }
        }
        private void treatPacket(Packet packet) {
            if (rejecting) {
                if (packet.code() == ERROR_RECOVER) rejecting = false;
            } else if (packet.type() == AUTH) {
                var pseudo = packet.pseudo();
                new ServerMessageDisplay("").onAuthPacket(sc, pseudo);
                if (!clients.containsKey(pseudo)) {
                    clients.put(pseudo, new ClientContext(key, pseudo)); // Unlink this context and the key
                } else {
                    queueMessage(makeErrorPacket(AUTH_ERROR));
                }
            } else if (packet.type() == TOKEN) {
                var token = Integer.parseInt(packet.message());
                new ServerMessageDisplay("").onTokenPacket(sc, token);
                if (privateCP.containsKey(token)) {
                    privateCP.get(token).addSelectionKey(key, bbIn);
                }
            }
        }
        private void queueMessage(Packet msg) {
            queue.add(msg.toBuffer().flip());
            processOut();
            updateInterestOps();
        }
        private void processOut() {
            if (queue.isEmpty()) return;
            if (bbOut.position() != 0) return;
            bbOut.clear();
            bbOut.put(queue.remove());
        }
        private void updateInterestOps() {
            var op = OP_WRITE;
            if (!closed && bbIn.hasRemaining()) op |= OP_READ;
            key.interestOps(op);
        }
        @Override
        public void doRead() throws IOException {
            if (sc.read(bbIn) == -1) closed = true;
            processIn();
        }
        @Override
        public void doWrite() throws IOException {
            sc.write(bbOut.flip());
            bbOut.compact();
            processOut();
            updateInterestOps();
        }
        @Override public void doConnect() {} // Not used here
    }
    private class ClientContext implements Context {
        private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final LinkedList<ByteBuffer> queue = new LinkedList<>();
        private final PacketReader reader = new PacketReader();
        private final ServerMessageDisplay display;
        private boolean rejecting = false;
        private boolean closed = false;
        private final SelectionKey key;
        private final SocketChannel sc;
        private final String pseudo;

        private ClientContext(SelectionKey key, String pseudo){
            this.key = key;
            this.pseudo = pseudo;
            sc = (SocketChannel) key.channel();
            changing.put(key, this);
            display = new ServerMessageDisplay(pseudo);
            queueMessage(makeAuthenticationPacket(pseudo));
        }
        private void processIn() {
            var status = reader.process(bbIn);      // Process Input
            if (status == REFILL) return;           // If not full -> stop
            if (status == ERROR) sendError();
            if (status == DONE) treatPacket(reader.get());
            reader.reset();
        }
        private void sendError() {
            if (rejecting) {
                display.onErrorProcessed();
                return;
            }
            switch (reader.getFailure()) {
                case CODE -> queue.add(makeErrorPacket(WRONG_CODE).toBuffer().flip());
                case LENGTH -> queue.add(makeErrorPacket(INVALID_LENGTH).toBuffer().flip());
            }
            rejecting = true;
            processOut();
            updateInterestOps();
        }
        private void treatPacket(Packet packet) {
            display.onPacketReceived(packet, rejecting);
            if (rejecting) {                            // If rejecting
                if (packet.code() == ERROR_RECOVER) {   // But the errorCode is ERROR_RECOVER
                    display.onRecover();
                    rejecting = false;                  // Stops rejecting
                }
                return;
            }
            switch (packet.type()) {
                case ERR -> {
                    if (packet.code() == REJECTED) {
                        var token = generateToken(pseudo, packet.pseudo());
                        if (pendingCP.contains(token)) {  // If pending request -> accept
                            pendingCP.remove(token);
                            if (clients.containsKey(packet.pseudo()))
                                clients.get(packet.pseudo()).queueMessage(makeRejectedPacket(pseudo));
                        }
                    }
                }
                case GMSG -> broadcast(makeGeneralMessagePacket(packet.message(), packet.pseudo()), this);
                case DMSG -> {
                    if (pseudo.equals(packet.pseudo()) || !clients.containsKey(packet.pseudo())) {
                        queueMessage(makeErrorPacket(DEST_ERROR));
                    } else {
                        clients.get(packet.pseudo()).queueMessage(makeDirectMessagePacket(packet.message(), pseudo));
                    }
                }
                case CP -> {
                    if (pseudo.equals(packet.pseudo()) || !clients.containsKey(packet.pseudo())) {
                        queueMessage(makeErrorPacket(DEST_ERROR));
                        return;
                    }
                    var token = generateToken(pseudo, packet.pseudo());
                    if (privateCP.containsKey(token)) {         // Already connected
                        System.out.println("Already connected!");
                    } else if (pendingCP.contains(token)) {     // Client B accept the connection
                        pendingCP.remove(token);
                        privateCP.put(token, new PrivateConnection());
                        queueMessage(makeTokenPacket(token, packet.pseudo()));
                        clients.get(packet.pseudo()).queueMessage(makeTokenPacket(token, pseudo));
                    } else {
                        pendingCP.add(token);
                        clients.get(packet.pseudo()).queueMessage(makePrivateConnectionPacket(pseudo));
                    }
                }
            }
        }
        private void queueMessage(Packet msg) {
            queue.add(msg.toBuffer().flip());
            processOut();
            updateInterestOps();
        }
        private void processOut() {
            if (queue.isEmpty()) return;
            if (bbOut.position() != 0) return;
            bbOut.clear();
            bbOut.put(queue.remove());
        }
        private void updateInterestOps() {
            var op = 0;
            if (!closed && bbIn.hasRemaining()) op |= OP_READ;
            if (bbOut.position() != 0)          op |= OP_WRITE;
            if (op == 0)                        silentlyClose(sc, pseudo);
            else                                key.interestOps(op);
        }
        @Override
        public void doRead() throws IOException {
            if (sc.read(bbIn) == -1) {
                closed = true;
                logger.info("Connection closed");
            }
            processIn();
            updateInterestOps();
        }
        @Override
        public void doWrite() throws IOException {
            sc.write(bbOut.flip());
            bbOut.compact();
            processOut();
            updateInterestOps();
        }
        @Override public void doConnect() {} // Not used here
    }
    private class PrivateConnection {
        private class PrivateConnectionContext implements Context {
            private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
            private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
            private final LinkedList<ByteBuffer> queue = new LinkedList<>(); // read-mode
            private PrivateConnectionContext other;
            private final SocketChannel sc;
            private final SelectionKey key;
            private boolean closed = false;

            private PrivateConnectionContext(SelectionKey key, ByteBuffer remaining) {
                this.key = key;
                sc = (SocketChannel) key.channel();
                changing.put(key, this);
                key.interestOps(0); // Do nothing until the two clients are connected
                if (remaining.flip().hasRemaining())
                    bbIn.put(remaining);
            }
            private void processIn() {
                other.queueMessage(bbIn.flip());
                bbIn.clear();
            }
            private void queueMessage(ByteBuffer other) {
                queue.add(copyBuffer(other).flip());
                processOut();
                updateInterestOps();
            }
            private void processOut() {
                if (queue.isEmpty()) return;
                if (bbOut.position() != 0) return;
                bbOut.clear();
                bbOut.put(queue.remove());
            }
            private void updateInterestOps() {
                var op = 0;
                if (!closed && bbIn.hasRemaining()) op |= OP_READ;
                if (bbOut.position() != 0)          op |= OP_WRITE;
                if (op == 0)                        close();
                else                                key.interestOps(op);
            }
            @Override
            public void doRead() throws IOException {
                if (sc.read(bbIn) == -1) {
                    closed = true;
                    logger.info("Connection closed");
                }
                processIn();
                updateInterestOps();
            }
            @Override
            public void doWrite() throws IOException {
                sc.write(bbOut.flip());
                bbOut.compact();
                processOut();
                updateInterestOps();
            }
            private void setOther(PrivateConnectionContext other) {
                this.other = requireNonNull(other);
            }

            @Override public void doConnect() {}
            public void close() {
                closeBoth();
            }
        }

        private final ArrayList<PrivateConnectionContext> contexts = new ArrayList<>();

        private void closeBoth() {
            logger.info("Private connection closed");
            silentlyClose(contexts.get(0).sc, null);
            silentlyClose(contexts.get(1).sc, null);
        }
        public void addSelectionKey(SelectionKey key, ByteBuffer remaining) {
            if (contexts.size() == 2) return;               // TODO : add error here
            contexts.add(new PrivateConnectionContext(key, remaining));
            if (contexts.size() != 2) return;
            contexts.get(0).setOther(contexts.get(1));
            contexts.get(1).setOther(contexts.get(0));
            contexts.get(0).processIn();
            contexts.get(1).processIn();
        }
    }

    static private final Logger logger = Logger.getLogger(ServerChatOS.class.getName());

    private final HashMap<SelectionKey, Context> changing = new HashMap<>();
    private final HashMap<Integer, PrivateConnection> privateCP = new HashMap<>();
    private final HashMap<String, ClientContext> clients = new HashMap<>();
    private final HashSet<Integer> pendingCP = new HashSet<>();
    private final ServerSocketChannel serverSocketChannel;
    private final Selector selector;

    public ServerChatOS(int port) throws IOException {
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(port));
        serverSocketChannel.configureBlocking(false);
        selector = Selector.open();
    }
    public void launch() throws IOException {
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        while(!Thread.interrupted()) {
            try {
                selector.select(this::treatKey);
                for (var e : changing.entrySet()) {
                    e.getKey().attach(e.getValue());
                }
                changing.clear();
            } catch (UncheckedIOException tunneled) {
                throw tunneled.getCause();
            }
        }
    }
    private void treatKey(SelectionKey key) {
        try {
            if (key.isValid() && key.isAcceptable()) doAccept(key);
        } catch(IOException ioe) {  // Tunneling
            throw new UncheckedIOException(ioe);
        }
        var ctx = (Context) key.attachment();
        try {
            if (key.isValid() && key.isWritable()) ctx.doWrite();
            if (key.isValid() && key.isReadable()) ctx.doRead();
        } catch (IOException e) {
            logger.info("Connection closed with client due to IOException");
            if (ctx instanceof ClientContext cli)
                silentlyClose(key.channel(), cli.pseudo);
            else if (ctx instanceof PrivateConnection.PrivateConnectionContext cp)
                cp.close();
        }
    }

    private void doAccept(SelectionKey key) throws IOException {
        var client = ((ServerSocketChannel) key.channel()).accept();
        if (client == null) {
            logger.info("Wrong hint from selector");
        } else {
            client.configureBlocking(false);
            var clientKey = client.register(selector, OP_READ);
            clientKey.attach(new ConnectionContext(clientKey));
        }
    }
    /**
     * Closes the connection without crashing if the connection is already closed.
     * Tries to remove the given pseudo from the {@link #clients} if not null.
     * @param channel the channel to close. Cannot be null.
     * @param pseudo the pseudo to remove. Can be null.
     * @throws NullPointerException if {@code channel} is null.
     */
    private void silentlyClose(Channel channel, String pseudo) {
        requireNonNull(channel);
        if (pseudo != null) clients.remove(pseudo);
        try { channel.close(); } catch (IOException ignore) {}
    }
    private void broadcast(Packet msg, ClientContext sender) {
        for (var client : clients.values()) {
            if (client != sender) client.queueMessage(msg);
        }
    }
    private static int generateToken(String c1, String c2) {
        return c1.hashCode() + c2.hashCode();
    }
    public static void main(String[] args) throws NumberFormatException, IOException {
        if (args.length != 1) {
            usage();
        } else {
            new ServerChatOS(Integer.parseInt(args[0])).launch();
        }
    }
    private static void usage() {
        System.out.println("Usage : ServerChatOS port");
    }
}
