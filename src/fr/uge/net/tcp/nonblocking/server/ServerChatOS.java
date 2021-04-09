package fr.uge.net.tcp.nonblocking.server;

import fr.uge.net.tcp.nonblocking.display.ServerMessageDisplay;
import fr.uge.net.tcp.nonblocking.reader.RejectReader;
import fr.uge.net.tcp.nonblocking.utils.ChatOSUtils;
import fr.uge.net.tcp.nonblocking.packet.Packet;
import fr.uge.net.tcp.nonblocking.context.AbstractContext;
import fr.uge.net.tcp.nonblocking.context.Context;
import fr.uge.net.tcp.nonblocking.packet.PacketReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.logging.Logger;

import static fr.uge.net.tcp.nonblocking.display.ServerMessageDisplay.onPacketReceived;
import static fr.uge.net.tcp.nonblocking.utils.ChatOSUtils.copyBuffer;
import static fr.uge.net.tcp.nonblocking.packet.Packet.ErrorCode.*;
import static fr.uge.net.tcp.nonblocking.packet.Packet.PacketBuilder.*;
import static fr.uge.net.tcp.nonblocking.packet.Packet.PacketType.AUTH;
import static fr.uge.net.tcp.nonblocking.packet.Packet.PacketType.TOKEN;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.*;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.util.Objects.requireNonNull;

public class ServerChatOS {
    /**
     * Class for the initial connections.
     * After an authentication packet or a token packet arrived, this context will be deleted
     * and replaced by respectively {@link ConnectionContext} and {@link PrivateConnection.PrivateConnectionContext}.
     */
    private class ConnectionContext extends AbstractContext implements Context {
        private final RejectReader rejectReader = new RejectReader();
        private final PacketReader reader = new PacketReader();
        private boolean deprecated = false;
        private final SelectionKey key;

        /**
         * Creates a connection context. Used by client until authentication.
         * @param key the connection key. Cannot be null.
         */
        private ConnectionContext(SelectionKey key){
            super(key);
            this.key = key;
        }
        /**
         * Tries to read a packet by processing {@link #bbIn}.
         */
        @Override
        public void processIn() {
            if (rejectReader.process(bbIn, "???")) return;
            var status = reader.process(bbIn);
            if (status == REFILL) return;
            if (status == ERROR) rejectReader.reject(reader.getFailure(), this, "???");
            if (status == DONE) treatPacket(reader.get());
            reader.reset();
        }
        /**
         * Analyses the processed packet.<br>
         * If the packet is an authentication : Register as client or send error.<br>
         * If the packet is a token : Register as private connection.<br>
         * Otherwise : ignore.
         *
         * @param packet the processed packet. Cannot be null.
         */
        private void treatPacket(Packet packet) {
            requireNonNull(packet);
            if (packet.type() == AUTH) {
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

            ServerMessageDisplay.onAuthPacket((SocketChannel) key.channel(), pseudo);

            if (!clients.containsKey(pseudo)) {
                clients.put(pseudo, new ClientContext(key, pseudo));
                deprecated = true;
            } else {
                queueMessage(makeErrorPacket(AUTH_ERROR).toBuffer());
            }
        }

        @Override
        public int updateInterestOps() {
            if (!deprecated) {
                return super.updateInterestOps();
            }
            return 0;
        }

        /**
         * Replace this context with a {@link PrivateConnection.PrivateConnectionContext}.
         * Also link it to the correct private connection.
         * @param token the private connection's identifier.
         */
        private void onToken(int token) {
            ServerMessageDisplay.onTokenPacket((SocketChannel) key.channel(), token);
            if (privateConnections.containsKey(token)) {
                privateConnections.get(token).addSelectionKey(key, bbIn);
                deprecated = true;
            } else {
                logger.warning("Invalid token received : " + token);
            }
        }
    }
    /**
     * Class for all the "normal" clients (i.e. not the private connections)
     * Handle the receiving and sending of GMSG, DMSG, PC and ERROR.
     */
    private class ClientContext extends AbstractContext implements Context {
        private final RejectReader rejectReader = new RejectReader();
        private final PacketReader reader = new PacketReader();
        private final String pseudo;

        private ClientContext(SelectionKey key, String pseudo){
            super(key);
            setConnected();
            this.pseudo = pseudo;
            changing.put(key, this);
            queueMessage(makeAuthenticationPacket(pseudo));
        }
        /**
         * Tries to read a packet by processing bbIn.
         * And do an action on packet.
         */
        @Override
        public void processIn() {
            if (rejectReader.process(bbIn, "pseudo")) return;
            var status = reader.process(bbIn);
            if (status == REFILL) return;
            if (status == ERROR) rejectReader.reject(reader.getFailure(), this, pseudo);
            if (status == DONE) treatPacket(reader.get());
            reader.reset();
            processIn();
        }
        public void queueMessage(Packet packet) {
            queueMessage(packet.toBuffer());
        }

        /**
         * Do an action depending on the {@code packet}'s type.
         * @param packet the processed packet.
         */
        private void treatPacket(Packet packet) {
            onPacketReceived(packet, pseudo);
            switch (packet.type()) {
                case ERR -> onError(packet);
                case GMSG -> broadcast(makeGeneralMessagePacket(packet.message(), packet.pseudo()), this);
                case DMSG -> onDirectMessage(packet);
                case PC -> onPrivateConnection(packet);
            }
        }

        /**
         * If the error code contained {@code packet} is
         * {@link Packet.ErrorCode#REJECTED}, tries to remove
         * the pending connection between the two clients and forwards the rejection to
         * the other client.
         *
         * @param packet the packet containing the error.
         */
        private void onError(Packet packet) {
            if (packet.code() != REJECTED) return;
            var token = computeToken(pseudo, packet.pseudo());
            if (!pendingPC.contains(token)) return;
            pendingPC.remove(token);
            if (!clients.containsKey(packet.pseudo())) return;
            clients.get(packet.pseudo()).queueMessage(makeRejectedPacket(pseudo));
        }

        /**
         * Sends a direct message to {@code packet.pseudo()} if the client is connected;
         * otherwise sends an error packet to this client.
         *
         * @param packet the packet containing the direct message.
         */
        private void onDirectMessage(Packet packet) {
            if (pseudo.equals(packet.pseudo()) || !clients.containsKey(packet.pseudo())) {
                queueMessage(makeErrorPacket(DEST_ERROR));
            } else {
                clients.get(packet.pseudo()).queueMessage(makeDirectMessagePacket(packet.message(), pseudo));
            }
        }

        /**
         * The reception of this packet can be:<br/>
         *  - when a client ask for a private connection with another client.<br/>
         *  - when a client respond to a request.<br/>
         * In any case, this method will first check if the other client is connected and not
         * himself. (otherwise send an error packet)
         * <br/>
         * After this check, if the two clients are already connected, do nothing.
         * If this is a response, validate the pending connection and send to the
         * two clients the token used to represent the connection.
         * If this is a request, add the token in the pending connection and wait until
         * the other client accept or reject the connection.
         *
         * @param packet the packet containing the private connection.
         */
        private void onPrivateConnection(Packet packet) {
            if (pseudo.equals(packet.pseudo()) || !clients.containsKey(packet.pseudo())) {
                queueMessage(makeErrorPacket(DEST_ERROR));
                return;
            }
            var token = computeToken(pseudo, packet.pseudo());
            if (pendingPC.contains(token)) {
                onPrivateConnectionAccept(token, packet.pseudo());
            } else if (!privateConnections.containsKey(token)) {
                onPrivateConnectionRequest(token, packet.pseudo());
            } else {
                System.out.println("Already connected!");
            }
        }

        /**
         * If the packet represents a private connection request,
         * store the request in the server and forwards the request to the
         * {@code other} client.
         *
         * @param token the identifier of the connection.
         * @param other the pseudo of the other client.
         */
        private void onPrivateConnectionRequest(int token, String other) {
            pendingPC.add(token);
            clients.get(other).queueMessage(makePrivateConnectionPacket(pseudo));
        }
        /**
         * If the packet represents a private connection positive response,
         * remove the connection from the pending connection and add it into
         * the actual established connections.
         * Also send a {@link Packet.PacketType#TOKEN} packet
         * to the two client with the private connection identifier.
         *
         * @param token the identifier of the connection.
         * @param other the pseudo of the other client.
         */
        private void onPrivateConnectionAccept(int token, String other) {
            pendingPC.remove(token);
            privateConnections.put(token, new PrivateConnection(token));
            queueMessage(makeTokenPacket(token, other));
            clients.get(other).queueMessage(makeTokenPacket(token, pseudo));
        }

        @Override
        public int updateInterestOps() {
            var op = super.updateInterestOps();
            if (op == 0) clients.remove(pseudo);
            return op;
        }
    }
    /**
     * Class for the private connections.
     * To establish a private connection you must register two {@link SelectionKey} with
     * {@link #addSelectionKey(SelectionKey, ByteBuffer)}. After that all bytes read from one connection
     * will be sent to the other and vice-versa.
     */
    private class PrivateConnection {
        private class PrivateConnectionContext extends AbstractContext implements Context {
            private PrivateConnectionContext linked;

            /**
             * Copy everything from remaining into {@link #bbIn}.
             * This context don't read nor write until linked with another.
             *
             * @param key the connection key.
             * @param remaining what remains in the previous context. Should be in write-mode.
             */
            private PrivateConnectionContext(SelectionKey key, ByteBuffer remaining) {
                super(key);
                setConnected();
                requireNonNull(remaining);
                changing.put(key, this);
                key.interestOps(0);
                if (remaining.flip().hasRemaining())
                    bbIn.put(remaining);
                remaining.compact();
            }

            /**
             * Send all data in {@link #bbIn} to the linked context.
             */
            @Override
            public void processIn() {
                linked.forwardMessage(bbIn.flip());
                bbIn.clear();
            }
            /**
             * Copies all data from {@code other} into the queue.
             * @param other the source buffer.
             */
            private void forwardMessage(ByteBuffer other) {
                queueMessage(copyBuffer(other));
            }
            /**
             * Adds link to the other private connection.
             * @param other the other private connection. Cannot be null.
             */
            private void link(PrivateConnectionContext other) {
                this.linked = requireNonNull(other);
            }
            /**
             * Close this private connection and the linked one.
             */
            public void closeBoth() {
                PrivateConnection.this.closeBoth();
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
            contexts.get(0).close();
            contexts.get(1).close();
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
            if (contexts.size() == 2)
                throw new IllegalStateException("Too much client with the same token.");
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
     * If a key (other than {@link #serverSocketChannel}) is closed unregisters
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
                pcCtx.closeBoth();
            else if (ctx instanceof ConnectionContext conCtx)
                conCtx.close();
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
