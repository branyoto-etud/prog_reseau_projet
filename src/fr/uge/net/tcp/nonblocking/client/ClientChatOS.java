package fr.uge.net.tcp.nonblocking.client;

import fr.uge.net.tcp.nonblocking.context.AbstractContext;
import fr.uge.net.tcp.nonblocking.context.Context;
import fr.uge.net.tcp.nonblocking.packet.Packet;
import fr.uge.net.tcp.nonblocking.packet.PacketReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Logger;

import static fr.uge.net.tcp.nonblocking.display.ClientMessageDisplay.onConnectSuccess;
import static fr.uge.net.tcp.nonblocking.display.ClientMessageDisplay.onMessageReceived;
import static fr.uge.net.tcp.nonblocking.packet.Packet.PacketFactory.*;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.DONE;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.REFILL;
import static fr.uge.net.tcp.nonblocking.utils.ChatOSUtils.silentlyClose;
import static java.lang.Integer.parseInt;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static java.util.Objects.requireNonNull;

/**
 * Main class for the client.
 */
public final class ClientChatOS {
    private final class MainContext extends AbstractContext implements Context {
        private final PacketReader reader = new PacketReader();
        private boolean connected = false;
        private String requester = null;
        private final SelectionKey key;
        private String pseudo;

        public MainContext(SelectionKey key){
            super(key);
            this.key = key;
        }

        /**
         * Processes {@link #bbIn} into the {@link #reader}.
         * If the {@link #reader} has finished, analyses it.
         */
        @Override
        public void processIn() {
            var status = reader.process(bbIn);
            if (status == REFILL) return;
            if (status == DONE) treatPacket(reader.get());
            reader.reset();
        }

        /**
         * Analyses the {@code packet} and chooses what to do:<br/>
         *     - Recover from an error<br/>
         *     - Validate connection<br/>
         *     - Accept private connection<br/>
         *     - Reject private connection<br/>
         *     - Start a new socket for a private connection<br/>
         * @param packet the packet to analyse.
         */
        private void treatPacket(Packet packet) {
            onMessageReceived(packet, pseudo);
            switch (packet.type()) {
                case ERR -> treatError(packet);
                case AUTH -> connected = true;
                case GMSG, DMSG -> {}
                case PC -> onPrivateConnection(packet);
                case TOKEN -> onToken(packet);
            }
        }

        /**
         * If the client receives a {@link Packet.PacketType#PC} packet,
         * 2 options are available:<br>
         *  - The request was sent by the client and the packet is a positive response.
         *  In this case, we wait for the {@link  Packet.PacketType#TOKEN}
         *  packet from the server to start the private connection.<br>
         *  - The request wasn't sent by the client, so we pause the client until
         *  he submits 'y' or 'n' to accept/refuse the connection.
         * @param packet the received packet.
         */
        private void onPrivateConnection(Packet packet) {
            if (!pendingConnection.containsKey(packet.pseudo())) {
                requester = packet.pseudo();
            }
        }

        /**
         * When the client receives a {@link Packet.PacketType#TOKEN} packet,
         * starts a new socket and connects it to the server.
         * @param packet the received packet.
         */
        private void onToken(Packet packet) {
            try {
                var ctx = createPrivateConnection(packet);
                if (pendingConnection.containsKey(packet.pseudo())) {
                    ctx.queueMessage(pendingConnection.remove(packet.pseudo()));
                }
            } catch (IOException ioe) {
                System.out.println("Cannot open a new socket for a private connection!");
            }
        }

        /**
         * On error reception, does an action.
         * <ul>
         *     <li> If the error is {@link Packet.ErrorCode#REJECTED}, removes the request from
         *     the {@link #pendingConnection}.</li>
         *     <li> If the error is {@link Packet.ErrorCode#WRONG_CODE}
         *     or {@link Packet.ErrorCode#INVALID_LENGTH}, sends an amend packet to let the server
         *     know that he's not ignoring packets anymore.</li>
         *     <li> The other errors are only displayed.</li>
         * </ul>
         *
         * @param packet the packet that contains the error.
         */
        private void treatError(Packet packet) {
            switch (packet.code()) {
                case AUTH_ERROR, DEST_ERROR -> {}
                case REJECTED -> pendingConnection.remove(packet.pseudo());
                case ERROR_RECOVER -> logger.warning("Received ERROR_RECOVER unlikely!");
                case WRONG_CODE, INVALID_LENGTH -> insertFirstMessage(makeErrorPacket(Packet.ErrorCode.ERROR_RECOVER).toBuffer());
            }
        }
        /**
         * Add {@code line} to the end of the queue after a treatment.<br>
         * If the client is currently locked by a private connection request, accept only {@code line}
         * that starts with 'n' or 'y'.<br>
         * Otherwise, sends the line inside a packet as a general message, a direct message
         * or a private connection request depending on the {@code line} content.
         *
         * @param line the line to queue. Cannot be null.
         */
        public void queueMessage(String line) {
            requireNonNull(line);
            if (requester != null) {
                whileWaiting(line);
            } else {
                parseInput(line);
            }
        }

        /**
         * Add {@code packet} to the end of the queue after a conversion to a buffer.
         * @param packet the packet to queue. Cannot be null.
         */
        public void queueMessage(Packet packet) {
            requireNonNull(packet);
            queueMessage(packet.toBuffer());
        }
        /**
         * While the input is locked by a private connection request, only accepts a line
         * that starts with 'y' for an acceptation or 'n' for a reject. (the case doesn't matter)<br>
         * Any other {@code line} content will be ignored.
         * Note : The rest of te {@code line} is ignored.
         *
         * @param line the line to parse.
         */
        private void whileWaiting(String line) {
            requireNonNull(line);
            if (line.toLowerCase().startsWith("y")) {
                queueMessage(makePrivateConnectionPacket(requester).toBuffer());
                requester = null;
            } else if (line.toLowerCase().startsWith("n")) {
                queueMessage(makeRejectedPacket(requester).toBuffer());
                requester = null;
            }
        }
        /**
         * Sends a message depending on the first chars of the {@code line}:
         *  - if the {@code line} starts with '@', sends a direct message if possible.<br>
         *  - if the {@code line} starts with '/', sends a private connection request if possible
         *  or only a request if the two clients are already connected.<br>
         *  - if the {@code line} starts with '\@' or '\/', sends a general message
         *  without the '\'.<br>
         *  - in any fail case, sends a general message containing the full {@code line}.
         * @param line the line to send.
         */
        private void parseInput(String line) {
            requireNonNull(line);
            if (!connected) {
                queueMessage(makeAuthenticationPacket(pseudo = line).toBuffer());
                return;
            }
            if (line.startsWith("@") && sendDirectMessage(line)) return;
            if (line.startsWith("/") && sendPrivateConnection(line)) return;
            queueMessage(makeGeneralMessagePacket(
                    line.startsWith("\\@") || line.startsWith("\\/") ? line.substring(1) : line, pseudo
                    ));
        }

        /**
         * Sends a direct message to the pseudo (i.e. the first word of the {@code line})
         * with the content of what's left on the {@code line}.
         * @param line the {@code line} to interpret. Cannot be null.
         * @return true if the message can be sent; false if the {@code line} contains less than two words.
         */
        private boolean sendDirectMessage(String line) {
            requireNonNull(line);
            var tokens = line.substring(1).split(" ", 2);
            if (tokens.length != 2) return false;
            queueMessage(makeDirectMessagePacket(tokens[1], tokens[0]));
            return true;
        }

        /**
         * Sends a private connection request to the pseudo (i.e. the first word of the {@code line})
         * with the request of what's left on the {@code line}.
         * If this client is already connected to the pseudo, only sends the request to the other client
         * (not a connection request).
         *
         * @param line the {@code line} to interpret. Cannot be null.
         * @return true if the message can be sent; false if the {@code line} contains less than two words.
         */
        private boolean sendPrivateConnection(String line) {
            requireNonNull(line);
            var tokens = line.substring(1).split(" ", 2);
            if (tokens.length != 2) return false;
            sendPrivateConnectionAux(tokens[0], tokens[1]);
            return true;
        }
        private void sendPrivateConnectionAux(String pseudo, String resource) {
            if (privateConnections.containsKey(pseudo)) {
                privateConnections.get(pseudo).queueMessage(resource);
            } else if (!pendingConnection.containsKey(pseudo)) {
                pendingConnection.put(pseudo, resource);
                queueMessage(makePrivateConnectionPacket(pseudo));
            } else {
                System.out.println("wait for " + pseudo + "'s response before sending another request!");
            }
        }

        /**
         * Updates the interest op of the key.
         * If the client need to accept or reject a private connection, only set it in write mode.
         *
         * @return the value of the operator assigned to the key.
         */
        @Override
        public int updateInterestOps() {
            if (requester == null) return super.updateInterestOps();
            key.interestOps(OP_WRITE);
            return OP_WRITE;
        }

        /**
         * If the connection is successful, updates interest operator.
         *
         * @throws IOException if an I/O error occurs.
         */
        @Override
        public void doConnect() throws IOException {
            super.doConnect();
            if (isConnected()) onConnectSuccess();
        }
    }
    private static final Logger logger = Logger.getLogger(ClientChatOS.class.getName());

    private final ArrayBlockingQueue<String> commandQueue = new ArrayBlockingQueue<>(10);
    private final HashMap<String, PrivateConnectionContext> privateConnections = new HashMap<>();
    private final HashMap<String, String> pendingConnection = new HashMap<>();
    private final Thread console = new Thread(this::consoleRun);
    private final InetSocketAddress serverAddress;
    private final Selector selector;
    private MainContext mainContext;
    private final SocketChannel sc;
    private final String directory;

    /**
     * Creates a client with a socket and a selector.
     * @param serverAddress the address of the server. Cannot be null.
     * @param directory the working directory. Cannot be null.
     * @throws IOException if an I/O error occurs.
     */
    public ClientChatOS(InetSocketAddress serverAddress, String directory) throws IOException {
        this.serverAddress = requireNonNull(serverAddress);
        this.directory = requireNonNull(directory);
        selector = Selector.open();
        sc = SocketChannel.open();
        sc.configureBlocking(false);
    }

    /**
     * Console tread.
     * Reads a line and send it to {@link #mainContext}.
     */
    private void consoleRun() {
        try (var scan = new Scanner(System.in)) {
            while (scan.hasNextLine()) {
                sendLine(scan.nextLine());
            }
        } catch (IOException e) {
            logger.severe("An I/O error occurs!");
        } finally {
            logger.info("Console thread stopping!");
        }
    }

    /**
     * Sends the line to the {@link #mainContext} if not empty.
     * And wakes up the main thread.
     *
     * @param line the line to send.
     * @throws IOException if the {@link #selector} is closed.
     */
    private void sendLine(String line) throws IOException {
        if (line.isBlank()) return;
        synchronized (commandQueue) {
            commandQueue.add(line);
            selector.wakeup();
        }
    }

    /**
     * Actually sends the line to the {@link #mainContext}.
     */
    private void processLine() {
        synchronized (commandQueue) {
            if (commandQueue.isEmpty()) return;
            mainContext.queueMessage(commandQueue.remove());
        }
    }

    /**
     * Creates a new socket to register to the server as a private connection.
     *
     * @param packet the packet containing the token.
     * @return the new context.
     * @throws IOException if the socket cannot be created nor registered to the {@link #selector}.
     */
    private PrivateConnectionContext createPrivateConnection(Packet packet) throws IOException {
        var pc = SocketChannel.open();
        pc.configureBlocking(false);
        var key = pc.register(selector, SelectionKey.OP_CONNECT);
        var context = new PrivateConnectionContext(packet, directory, key);
        key.attach(context);
        pc.connect(serverAddress);
        privateConnections.put(packet.pseudo(), context);
        return context;
    }

    /**
     * Actually starts the client by registering the socket into
     * the selector and connects it to the server. And starts the console thread.
     *
     * @throws IOException if an I/O error occurs.
     */
    public void launch() throws IOException {
        var key = sc.register(selector, SelectionKey.OP_CONNECT);
        key.attach(mainContext = new MainContext(key));

        sc.connect(serverAddress);
        console.start();

        while(!Thread.interrupted()) {
            try {
                selector.select(this::treatKey);
                processLine();
            } catch (UncheckedIOException tunneled) {
                privateConnections.forEach((k, v) -> v.close());
                silentlyClose(key.channel());
                console.interrupt();
                System.exit(-1);
            }
        }
    }

    /**
     * Does the available action (whether Connect, Write or Read) on the key.
     * If a key is closed (other than the main one) displays a message and closes it properly.
     *
     * @param key the current key to treat.
     * @throws UncheckedIOException if the main channel is closed.
     */
    private void treatKey(SelectionKey key) {
        var ctx = (Context) key.attachment();
        try {
            if (key.isValid() && key.isConnectable()) ctx.doConnect();
            if (key.isValid() && key.isWritable()) ctx.doWrite();
            if (key.isValid() && key.isReadable()) ctx.doRead();
        } catch(IOException ioe) {
            if (!(ctx instanceof PrivateConnectionContext privateCtx)) {
                throw new UncheckedIOException(ioe);
            }
            logger.info("Private connection stopped!");
            privateCtx.close(privateConnections);
        }
    }

    // --------------------------------------------------
    // Static Methods
    // --------------------------------------------------

    /**
     * Main method.
     * Only starts the client if there's 3 arguments.
     * And if the last argument is a correct directory (i.e. exists and is a directory not a file).
     *
     * @param args the programs arguments.
     * @throws NumberFormatException if the second argument isn't an integer.
     * @throws IOException if an I/O error occurs.
     */
    public static void main(String[] args) throws NumberFormatException, IOException {
        if (args.length!=3) usage();
        else if (!Files.isDirectory(Paths.get(args[2]))) invalidDirectory(args[2]);
        else new ClientChatOS(new InetSocketAddress(args[0], parseInt(args[1])), args[2]).launch();
    }

    private static void invalidDirectory(String dir) {
        System.out.println(dir + " is not a directory or is not accessible.");
    }
    private static void usage(){
        System.out.println("Usage : ClientChatOS hostname port repertory");
    }
}
