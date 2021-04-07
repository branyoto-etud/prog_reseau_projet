package fr.uge.net.tcp.nonblocking.client;

import fr.uge.net.tcp.nonblocking.Packet;
import fr.uge.net.tcp.nonblocking.reader.PacketReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Logger;

import static fr.uge.net.tcp.nonblocking.ChatOSUtils.silentlyClose;
import static fr.uge.net.tcp.nonblocking.Config.BUFFER_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.*;
import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.*;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.DONE;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.REFILL;
import static java.lang.Integer.parseInt;
import static java.util.Objects.requireNonNull;

public class ClientChatOS {
    private class MainContext implements Context {
        private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final PacketReader reader = new PacketReader();
        private final LinkedList<ByteBuffer> queue = new LinkedList<>();        // Stored buffer are in read-mode
        private final SelectionKey key;
        private final SocketChannel sc;
        private String requester = null;
        private boolean connected;
        private boolean closed;
        private String pseudo;

        public MainContext(SelectionKey key_){
            sc = (SocketChannel) key_.channel();
            connected = false;
            closed = false;
            key = key_;
        }
        private void processIn() throws IOException {
            var status = reader.process(bbIn);              // Processes the buffer
            if (status == REFILL) return;                   // If needs refill -> stop
            if (status == DONE) treatPacket(reader.get());  // If done -> treats the packet
            reader.reset();                                 // Resets the buffer
        }
        private void treatPacket(Packet packet) throws IOException {
            onMessageReceived(packet, pseudo);
            switch (packet.type()) {
                case ERR -> treatError(packet);     // Do an action on error
                case AUTH -> connected = true;      // Validate connection
                case GMSG, DMSG -> {}               // Only need to be displayed (already done)
                case PC -> {
                    // If there is no pending connection, this is the client B
                    if (!pendingConnection.containsKey(packet.pseudo())) requester = packet.pseudo();
                    // Otherwise wait for a TOKEN packet
                }
                case TOKEN -> {
                    var ctx = createPrivateConnection(packet);
                    if (pendingConnection.containsKey(packet.pseudo())) {
                        ctx.queueMessage(pendingConnection.remove(packet.pseudo()));
                    }
                }
            }
        }
        private void treatError(Packet packet) {
            switch (packet.code()) {
                case AUTH_ERROR, DEST_ERROR -> {}
                case REJECTED -> pendingConnection.remove(packet.pseudo());
                case ERROR_RECOVER -> logger.warning("Received ERROR_RECOVER unlikely!");
                case WRONG_CODE, INVALID_LENGTH -> {
                    queue.addFirst(makeErrorPacket(Packet.ErrorCode.ERROR_RECOVER).toBuffer());
                    processOut();
                    updateInterestOps();
                }
            }
        }
        public void queueMessage(String msg) {
            if (requester != null) {
                whileWaiting(msg);
            } else {
                queue.add(parseInput(msg).toBuffer());
            }
            processOut();
            updateInterestOps();
        }
        private void whileWaiting(String msg) {
            if (msg.toLowerCase().startsWith("y")) {
                queue.add(makePrivateConnectionPacket(requester).toBuffer());
                requester = null;
            } else if (msg.toLowerCase().startsWith("n")) {
                queue.add(makeRejectedPacket(requester).toBuffer());
                requester = null;
            }
        }
        private Packet parseInput(String msg) {
            if (!connected) {
                pseudo = msg;
                return makeAuthenticationPacket(msg);
            }
            if (msg.startsWith("@")) {
                var tokens = msg.substring(1).split(" ", 2);
                if (tokens.length == 2) return makeDirectMessagePacket(tokens[1], tokens[0]);
            }
            if (msg.startsWith("/")) {
                var tokens = msg.substring(1).split(" ", 2);
                if (tokens.length == 2) {
                    if (privateConnections.containsKey(tokens[0])) {        // Already connected to that client
                        privateConnections.get(tokens[0]).queueMessage(tokens[1]);
                    } else {
                        pendingConnection.put(tokens[0], tokens[1]);
                    }
                    return makePrivateConnectionPacket(tokens[0]);
                }
            }
            return msg.startsWith("\\@") || msg.startsWith("\\/") ?
                    makeGeneralMessagePacket(msg.substring(1), pseudo) : makeGeneralMessagePacket(msg, pseudo);
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
            if (op != 0) key.interestOps(op);
            else silentlyClose(sc);                                             // If there's nothing to read nor write
        }
        @Override
        public void doRead() throws IOException {
            if (sc.read(bbIn)==-1) closed = true;
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
        @Override
        public void doConnect() throws IOException {
            try {
                if (!sc.finishConnect()) return;
                onConnectSuccess();
                key.interestOps(SelectionKey.OP_READ);
            } catch (IOException ioe) {
                onConnectFail();
                throw ioe;
            }
        }
    }
    private static final Logger logger = Logger.getLogger(ClientChatOS.class.getName());

    private final ArrayBlockingQueue<String> commandQueue = new ArrayBlockingQueue<>(10);
    private final HashMap<String, PrivateConnectionContext> privateConnections = new HashMap<>();
    private final HashMap<String, String> pendingConnection = new HashMap<>();
    private final InetSocketAddress serverAddress;
    private final Selector selector;
    private MainContext mainContext;
    private final SocketChannel sc;
    private final String directory;
    private final Thread console;

    public ClientChatOS(InetSocketAddress serverAddress, String directory) throws IOException {
        this.serverAddress = requireNonNull(serverAddress);
        this.directory = requireNonNull(directory);
        console = new Thread(this::consoleRun);
        selector = Selector.open();
        sc = SocketChannel.open();
        sc.configureBlocking(false);
    }

    /**
     * Console tread.
     * Read a line and send it to {@link #mainContext}.
     */
    private void consoleRun() {
        try (var scan = new Scanner(System.in)){    // Todo : Possible use of BufferedReader to avoid System.exit();
            while (scan.hasNextLine()) {
                sendLine(scan.nextLine());
            }
        } catch (IOException e) {
            logger.severe("Cannot create a socket for a private connection!");
        } finally {
            logger.info("Console thread stopping!");
        }
    }

    /**
     * Send the line to the {@link #mainContext} if not empty.
     * And wakeup the main thread.
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
     * Actually send the line to the {@link #mainContext}.
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
    public void launch() throws IOException {
        var key = sc.register(selector, SelectionKey.OP_CONNECT);
        mainContext = new MainContext(key);
        key.attach(mainContext);

        sc.connect(serverAddress);
        console.start();

        while(!Thread.interrupted()) {
            try {
                selector.select(this::treatKey);
                processLine();
            } catch (UncheckedIOException tunneled) {
                console.interrupt();
                silentlyClose(key.channel());
                System.exit(-1);
            }
        }
    }
    private void treatKey(SelectionKey key) {
        try {
            if (key.isValid() && key.isConnectable()) {
                ((Context) key.attachment()).doConnect();
            }
            if (key.isValid() && key.isWritable()) {
                ((Context) key.attachment()).doWrite();
            }
            if (key.isValid() && key.isReadable()) {
                ((Context) key.attachment()).doRead();
            }
        } catch(IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    // --------------------------------------------------
    // Static Methods
    // --------------------------------------------------


    public static void main(String[] args) throws NumberFormatException, IOException {
        if (args.length!=3) usage();
        else new ClientChatOS(new InetSocketAddress(args[0], parseInt(args[1])), args[2]).launch();
    }

    private static void usage(){
        System.out.println("Usage : ClientChatOS hostname port repertory");
    }
}
