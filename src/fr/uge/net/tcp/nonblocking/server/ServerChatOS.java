package fr.uge.net.tcp.nonblocking.server;

import fr.uge.net.tcp.nonblocking.ChatOSUtils;
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
    /**
     * Class for the initial connections.
     * After an authentication packet or a token packet arrived, this context will be deleted
     * and replaced by respectively {@link ConnectionContext} and {@link PrivateConnection.PrivateConnectionContext}.
     */
    private class ConnectionContext implements Context {
        /**
         * The two buffer will always stay in write-mode after each methods.
         */
        private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final LinkedList<ByteBuffer> queue = new LinkedList<>();
        private final PacketReader reader = new PacketReader();
        /**
         * Set to true after an error. Imply that all read bytes are not treated.
         */
        private boolean rejecting = false;
        private final SelectionKey key;
        private final SocketChannel sc;
        private boolean closed = false;

        /**
         * Creates a connection context. Used by client until authentication.
         * @param key the connection key. Cannot be null.
         */
        private ConnectionContext(SelectionKey key){
            this.key = requireNonNull(key);
            sc = (SocketChannel) key.channel();
        }
        /**
         * Tries to read a packet by processing bbIn.
         */
        private void processIn() {
            // Todo : read only one byte if rejecting
            //  until read an error code
            var status = reader.process(bbIn);
            if (status == REFILL) return;
            if (status == ERROR && !rejecting) sendError();
            if (status == DONE) treatPacket(reader.get());
            reader.reset();
        }
        /**
         * Send an error packet if an error occurs while processing bbIn
         */
        private void sendError() {
            switch (reader.getFailure()) {
                case CODE -> queueMessage(makeErrorPacket(WRONG_CODE));
                case LENGTH -> queueMessage(makeErrorPacket(INVALID_LENGTH));
            }
            rejecting = true;
        }
        /**
         * Analyses the processed packet.
         * If the packet is a recover : stop rejecting.
         * If the packet is an authentication : Register as client or send error.
         * If the packet is a token : Register as private connection.
         * Otherwise : ignore.
         *
         * @param packet the processed packet. Cannot be null.
         */
        private void treatPacket(Packet packet) {
            requireNonNull(packet);
            if (rejecting) {
                if (packet.code() == ERROR_RECOVER) rejecting = false;
            } else if (packet.type() == AUTH) {
                onAuthentication(packet.pseudo());
            } else if (packet.type() == TOKEN) {
                onToken(Integer.parseInt(packet.message()));
            }
        }
        /**
         * Checks if the pseudo is already taken.
         * If so send an error to the client. Otherwise replace this context by a {@link ClientContext}.
         * @param pseudo the requested pseudo of the client.
         */
        private void onAuthentication(String pseudo) {
            requireNonNull(pseudo);
            new ServerMessageDisplay("").onAuthPacket(sc, pseudo);
            if (!clients.containsKey(pseudo)) {
                clients.put(pseudo, new ClientContext(key, pseudo));
            } else {
                queueMessage(makeErrorPacket(AUTH_ERROR));
            }
        }
        /**
         * Replace this context with a {@link PrivateConnection.PrivateConnectionContext}.
         * Also link it to the correct private connection.
         * @param token the private connection's identifier.
         */
        private void onToken(int token) {
            new ServerMessageDisplay("").onTokenPacket(sc, token);
            if (privateConnections.containsKey(token)) {
                privateConnections.get(token).addSelectionKey(key, bbIn);
            } // Todo : add error if token invalid
        }
        /**
         * Adds this message to the queue and tries to fill {@link #bbOut}.
         * @param msg the message to send.
         */
        private void queueMessage(Packet msg) { // Todo : Remove on refactor
            queue.add(msg.toBuffer());
            processOut();
            updateInterestOps();
        }

        /**
         * Tries to fill {@link #bbOut} if there's an element in the {@link #queue}
         * and {@link #bbOut} doesn't have anything in it.
         */
        private void processOut() { // Todo : Remove on refactor
            if (queue.isEmpty()) return;
            if (bbOut.position() != 0) return;
            bbOut.clear()
                 .put(queue.remove());
        }
        // Todo  : Elle va dégager
        private void updateInterestOps() { // Todo : Remove on refactor
            var op = OP_WRITE;
            if (!closed && bbIn.hasRemaining()) op |= OP_READ;
            key.interestOps(op);
        }
        @Override
        public void doRead() throws IOException { // Todo : Keep after refactor and redo
            if (sc.read(bbIn) == -1) closed = true;
            processIn();
        }
        @Override
        public void doWrite() throws IOException { // Todo : Remove on refactor
            sc.write(bbOut.flip());
            bbOut.compact();
            processOut();
            updateInterestOps();
        }
        @Override public void doConnect() {} // Todo : Replace by empty method after refactor
    }
    /**
     * Class for all the "normal" clients (i.e. not the private connections)
     * Handle the receiving and sending of GMSG, DMSG, PC and ERROR.
     */
    private class ClientContext implements Context {
        private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final LinkedList<ByteBuffer> queue = new LinkedList<>();
        private final PacketReader reader = new PacketReader();
        private boolean rejecting = false;
        private boolean closed = false;
        private final SelectionKey key;
        private final SocketChannel sc;
        private final String pseudo;
        // Todo : redo display

        private ClientContext(SelectionKey key, String pseudo){
            this.key = key;
            this.pseudo = pseudo;
            sc = (SocketChannel) key.channel();
            changing.put(key, this);
            queueMessage(makeAuthenticationPacket(pseudo));
        }
        /**
         * Tries to read a packet by processing bbIn.
         * And do an action on packet.
         */
        private void processIn() {
            var status = reader.process(bbIn);      // Process Input
            if (status == REFILL) return;           // If not full -> stop
            if (status == ERROR) sendError();
            if (status == DONE) treatPacket(reader.get());
            reader.reset();
            // Todo : maybe recursive call
        }
        private void sendError() {      // Todo : refactor with the one from ConnectionContext
            if (rejecting) return;
            switch (reader.getFailure()) {
            case CODE -> queue.add(makeErrorPacket(WRONG_CODE).toBuffer());     // Todo : use queueMessage
                case LENGTH -> queue.add(makeErrorPacket(INVALID_LENGTH).toBuffer()); // Todo : use queueMessage
            }
            rejecting = true;
            processOut();
            updateInterestOps();
        }

        private void treatPacket(Packet packet) { // Todo : split into many functions
            if (rejecting) {                            // If rejecting
                if (packet.code() == ERROR_RECOVER) {   // But the errorCode is ERROR_RECOVER
                    rejecting = false;                  // Stops rejecting
                }
                return;
            }
            switch (packet.type()) {
                case ERR -> {
                    if (packet.code() == REJECTED) {
                        var token = computeToken(pseudo, packet.pseudo());
                        if (pendingPC.contains(token)) {  // If pending request -> accept
                            pendingPC.remove(token);
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
                case PC -> {
                    if (pseudo.equals(packet.pseudo()) || !clients.containsKey(packet.pseudo())) {
                        queueMessage(makeErrorPacket(DEST_ERROR));
                        return;
                    }
                    var token = computeToken(pseudo, packet.pseudo());
                    if (privateConnections.containsKey(token)) {         // Already connected
                        System.out.println("Already connected!");
                    } else if (pendingPC.contains(token)) {     // Client B accept the connection
                        pendingPC.remove(token);
                        privateConnections.put(token, new PrivateConnection(token));
                        queueMessage(makeTokenPacket(token, packet.pseudo()));
                        clients.get(packet.pseudo()).queueMessage(makeTokenPacket(token, pseudo));
                    } else {
                        pendingPC.add(token);
                        clients.get(packet.pseudo()).queueMessage(makePrivateConnectionPacket(pseudo));
                    }
                }
            }
        }
        private void queueMessage(Packet msg) { // Todo : Remove on refactor
            queue.add(msg.toBuffer());
            processOut();
            updateInterestOps();
        }
        private void processOut() { // Todo : Remove on refactor
            if (queue.isEmpty()) return;
            if (bbOut.position() != 0) return;
            bbOut.clear();
            bbOut.put(queue.remove());
        }
        private void updateInterestOps() { // Todo : Remove on refactor
            var op = 0;
            if (!closed && bbIn.hasRemaining()) op |= OP_READ;
            if (bbOut.position() != 0)          op |= OP_WRITE;
            if (op == 0)                        silentlyClose(sc, pseudo);
            else                                key.interestOps(op);
        }
        @Override
        public void doRead() throws IOException { // Todo : Remove on refactor
            if (sc.read(bbIn) == -1) {
                closed = true;
                logger.info("Connection closed");
            }
            processIn();
            updateInterestOps();
        }
        @Override
        public void doWrite() throws IOException { // Todo : Remove on refactor
            sc.write(bbOut.flip());
            bbOut.compact();
            processOut();
            updateInterestOps();
        }
        @Override public void doConnect() {} // Todo : Replace by empty method after refactor
    }
    /**
     * Class for the private connections.
     * To establish a private connection you must register two {@link SelectionKey} with
     * {@link #addSelectionKey(SelectionKey, ByteBuffer)}. After that all bytes read from one connection
     * will be sent to the other and vice-versa.
     */
    private class PrivateConnection {
        private class PrivateConnectionContext implements Context {
            private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
            private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
            private final LinkedList<ByteBuffer> queue = new LinkedList<>(); // read-mode
            private PrivateConnectionContext linked;
            private final SocketChannel sc;
            private final SelectionKey key;
            private boolean closed = false;

            /**
             * Copy everything from remaining into {@link #bbIn}.
             * This context don't read nor write until linked with another.
             *
             * @param key the connection key.
             * @param remaining what remains in the previous context. Should be in write-mode.
             */
            private PrivateConnectionContext(SelectionKey key, ByteBuffer remaining) {
                requireNonNull(remaining);
                this.key = requireNonNull(key);
                sc = (SocketChannel) key.channel();
                changing.put(key, this);
                key.interestOps(0);
                if (remaining.flip().hasRemaining())
                    bbIn.put(remaining);
                remaining.compact();
            }

            /**
             * Send all data in {@link #bbIn} to the linked context.
             */
            private void processIn() {
                linked.queueMessage(bbIn.flip());
                bbIn.clear();
            }

            /**
             * Copies all data from {@code other} into the {@link #queue}.
             * @param other the source buffer.
             */
            private void queueMessage(ByteBuffer other) {
                queue.add(copyBuffer(other));
                processOut();
                updateInterestOps();
            }
            private void processOut() {     // Todo :Remove on refactor
                if (queue.isEmpty()) return;
                if (bbOut.position() != 0) return;
                bbOut.clear();
                bbOut.put(queue.remove());
            }
            private void updateInterestOps() {   // Todo :Remove on refactor
                var op = 0;
                if (!closed && bbIn.hasRemaining()) op |= OP_READ;
                if (bbOut.position() != 0)          op |= OP_WRITE;
                if (op == 0)                        close();
                else                                key.interestOps(op);
            }
            @Override
            public void doRead() throws IOException {  // Todo :Remove on refactor
                if (sc.read(bbIn) == -1) {
                    closed = true;
                    logger.info("Connection closed");
                }
                processIn();
                updateInterestOps();
            }
            @Override
            public void doWrite() throws IOException {  // Todo :Remove on refactor
                sc.write(bbOut.flip());
                bbOut.compact();
                processOut();
                updateInterestOps();
            }

            /**
             * Adds link to the other private connection.
             * @param other the other private connection. Cannot be null.
             */
            private void link(PrivateConnectionContext other) {
                this.linked = requireNonNull(other);
            }

            @Override public void doConnect() {} // Todo : Keep

            /**
             * Close this private connection and the linked one.
             */
            public void close() {
                closeBoth();
            }
        }

        private final ArrayList<PrivateConnectionContext> contexts = new ArrayList<>(2);
        private final int token;
        public PrivateConnection(int token) {
            this.token = token;
        }

        /**
         * Closes the two ends of the private connection and removes it from
         * the private connections.
         */
        private void closeBoth() {
            logger.info("Private connection closed");
            ChatOSUtils.silentlyClose(contexts.get(0).sc);
            ChatOSUtils.silentlyClose(contexts.get(1).sc);
            privateConnections.remove(token);
        }

        /**
         * Creates a context with the given key and registers it.
         * When there's 2 context registered. Link them and start listening/writing.
         *
         * @param key the key of the connection.
         * @param remaining what remains in the buffer from the previous context.
         * @throws IllegalStateException if there's already 2 registered connections and
         */
        public void addSelectionKey(SelectionKey key, ByteBuffer remaining) {
            if (contexts.size() == 2) return;               // TODO : add error here
            contexts.add(new PrivateConnectionContext(key, remaining));
            if (contexts.size() != 2) return;
            contexts.get(0).link(contexts.get(1));
            contexts.get(1).link(contexts.get(0));
            contexts.get(0).processIn();
            contexts.get(1).processIn();
        }
    }

    private static final Logger logger = Logger.getLogger(ServerChatOS.class.getName());

    private final HashMap<Integer, PrivateConnection> privateConnections = new HashMap<>();
    private final HashMap<SelectionKey, Context> changing = new HashMap<>();
    private final HashMap<String, ClientContext> clients = new HashMap<>();
    private final HashSet<Integer> pendingPC = new HashSet<>();
    private final ServerSocketChannel serverSocketChannel;
    private final Selector selector;

    /**
     * Initialize the server with the given port on localhost.
     * @param port the port the server is bound to.
     * @throws AlreadyBoundException if the port is already taken.
     * @throws IOException if an I/O error occurs.
     */
    public ServerChatOS(int port) throws IOException {
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(port));
        serverSocketChannel.configureBlocking(false);
        selector = Selector.open();
    }

    /**
     * Starts the server.
     * Treats all keys and after tries to update the context of the key that need a change.
     * @throws IOException if the server cannot accept a connection. (i.e. if the {@link #serverSocketChannel} is closed).
     */
    public void launch() throws IOException {
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        while(!Thread.interrupted()) {
            try {
                selector.select(this::treatKey);
                changing.forEach(SelectionKey::attach);
                changing.clear();
            } catch (UncheckedIOException tunneled) {
                throw tunneled.getCause();
            }
        }
    }

    /**
     * Do the available action (whether Accept, Write or Read) on the key.
     * If a key (other than {@link #serverSocketChannel}) is closed unregister
     * it properly and remove it from the {@link #clients} if it's a client.
     *
     * @param key the current key to treat.
     * @throws UncheckedIOException if the {@link #serverSocketChannel} is closed.
     */
    private void treatKey(SelectionKey key) {
        try {
            if (key.isValid() && key.isAcceptable()) doAccept();
        } catch(IOException ioe) {  // Tunneling
            throw new UncheckedIOException(ioe);
        }
        var ctx = (Context) key.attachment();
        try {
            if (key.isValid() && key.isWritable()) ctx.doWrite();
            if (key.isValid() && key.isReadable()) ctx.doRead();
        } catch (IOException e) {
            logger.info("Connection closed with client due to IOException");
            if (ctx instanceof ClientContext cliCtx)
                silentlyClose(key.channel(), cliCtx.pseudo);
            else if (ctx instanceof PrivateConnection.PrivateConnectionContext pcCtx)
                pcCtx.close();
        }
    }

    /**
     * Accepts connections from the {@link #serverSocketChannel} if possible.
     * The client is configured blocking and has a {@link ConnectionContext} attched to it.
     *
     * @throws IOException if an I/O error occurs.
     */
    private void doAccept() throws IOException {
        var client = serverSocketChannel.accept();
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
     * Removes the given pseudo from the {@link #clients}.
     * If the pseudo is unknown use {@link ChatOSUtils#silentlyClose(Channel)} instead.
     * @param channel the channel to close. Cannot be null.
     * @param pseudo the pseudo to remove. Cannot be null.
     * @throws NullPointerException if {@code pseudo} or the {@code channel} is null.
     */
    private void silentlyClose(Channel channel, String pseudo) {
        clients.remove(requireNonNull(pseudo));
        ChatOSUtils.silentlyClose(channel);
    }

    /**
     * Send the same {@link Packet} to every clients connected other that
     * {@code sender}.
     * @param msg the message to broadcast. Cannot be null.
     * @param sender the sender of this message. Can be null if everyone need to receive this message.
     */
    public void broadcast(Packet msg, ClientContext sender) {
        requireNonNull(msg);
        clients.values().stream().filter(client -> client != sender).forEach(client -> client.queueMessage(msg));
    }

    /**
     * Computes an integer that represent these 2 clients pseudo.
     * This method always give the same value for the same input even if the
     * two pseudo are swapped.
     *
     * @param c1 the first pseudo. Cannot be null.
     * @param c2 the second pseudo. Cannot be null.
     * @return the token computed with the two pseudos.
     */
    public static int computeToken(String c1, String c2) {
        return c1.hashCode() + c2.hashCode();
    }
    public static void main(String[] args) throws NumberFormatException, IOException {
        if (args.length != 1) {
            usage();
        } else {
            new ServerChatOS(Integer.parseInt(args[0])).launch();
        }
    }
    public static void usage() {
        System.out.println("Usage : ServerChatOS port");
    }
}
