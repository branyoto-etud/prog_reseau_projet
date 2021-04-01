package fr.uge.net.tcp.nonblocking.server;

import fr.uge.net.tcp.nonblocking.Packet;
import fr.uge.net.tcp.nonblocking.reader.PacketReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Logger;

import static fr.uge.net.tcp.nonblocking.Packet.ErrorCode.*;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.*;
import static fr.uge.net.tcp.nonblocking.Packet.PacketType.AUTH;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.*;
import static java.util.Objects.requireNonNull;

public class ServerChatOS {
    private class Context {
        private final LinkedList<ByteBuffer> queue = new LinkedList<>();
        private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_SIZE);
        private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_SIZE);
        private final PacketReader reader = new PacketReader();
        private final ServerMessageDisplay display;
        private final SelectionKey key;
        private final SocketChannel sc;
        private boolean closed = false;
        private boolean rejecting = false;      // Set to true after an error. Imply that all read packet are not treated
        private boolean authenticated = false;
        private String pseudo = null;

        private Context(SelectionKey key){
            this.key = key;
            sc = (SocketChannel) key.channel();
            display = new ServerMessageDisplay(sc);
        }
        private void processIn() {
            // Todo : improve reject of message after error reception
            var status = reader.process(bbIn);      // Process Input
            if (status == REFILL) return;           // If not full -> stop
            if (status == ERROR) {                  // On error
                if (!rejecting) {                   // If first error -> send error packet
                    display.onErrorProcessed();
                    sendError();
                    processOut();
                    updateInterestOps();
                }
                rejecting = true;                   // reject all other packet (correct or incorrect)
            }
            if (status == DONE) {
                treatPacket(reader.get());
                processOut();
                updateInterestOps();
            }
            reader.reset();
        }
        private void sendError() {
            switch (reader.getFailure()) {
                case CODE -> queue.add(makeErrorPacket(WRONG_CODE).toBuffer().flip());
                case LENGTH -> queue.add(makeErrorPacket(INVALID_LENGTH).toBuffer().flip());
                default -> throw new IllegalStateException("Unexpected value: " + reader.getFailure());
            }
        }
        private void treatPacket(Packet packet) {
            display.onPacketReceived(packet, rejecting, authenticated);
            if (rejecting) {                            // If rejecting
                if (packet.code() == ERROR_RECOVER) {   // But the errorCode is ERROR_RECOVER
                    display.onRecover();
                    rejecting = false;                  // Stops rejecting
                }
                return;
            }
            if (!authenticated && packet.type() == AUTH) {
                pseudo = packet.pseudo();               // Save pseudo (not used until authenticated)
                if (!clients.containsKey(pseudo)) {     // If no client with that pseudo
                    clients.put(pseudo, this);          // Add this one
                    display.setPseudo(pseudo);
                    authenticated = true;               // Set authenticated
                    queue.add(makeAuthenticationPacket(pseudo).toBuffer().flip());
                } else {    // Cannot use queueMessage because not authenticated
                    queue.add(makeErrorPacket(AUTH_ERROR).toBuffer().flip());
                    processOut();
                    updateInterestOps();
                }
                return;
            }
            switch (packet.type()) {
                case ERR -> {
                    switch (packet.code()) {
                        case AUTH_ERROR, DEST_ERROR, OTHER -> {} // Impossible
                        case WRONG_CODE, INVALID_LENGTH -> {} // Server internal Error
                        case ERROR_RECOVER -> {} // Received when not rejecting -> Impossible/Ignore
                        case REJECTED -> {
                            var key = new PCKey(pseudo, packet.pseudo());
                            if (pendingCP.contains(key)) {  // If pending request -> accept
                                pendingCP.remove(key);
                                if (clients.containsKey(packet.pseudo()))
                                    clients.get(packet.pseudo()).queueMessage(makeRejectedPacket(pseudo));
                            }
                            // If no pending request -> Impossible/Ignore
                        }
                    }
                }
                case AUTH -> {} // Received when already authenticated -> Impossible/Ignore
                case GMSG -> broadcast(makeGeneralMessagePacket(packet.message(), packet.pseudo()));
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
                    var key = new PCKey(pseudo, packet.pseudo());
                    if (pendingCP.contains(key)) {  // Client B accept the connection
                        pendingCP.remove(key);
                        int token = new Random().nextInt();
                        privateCP.put(key, "tmp");
                        queueMessage(makeTokenPacket(token, packet.pseudo()));
                        clients.get(packet.pseudo()).queueMessage(makeTokenPacket(token, pseudo));
                    } else {
                        pendingCP.add(key);
                        clients.get(packet.pseudo()).queueMessage(makePrivateConnectionPacket(pseudo));
                    }
                }
                case TOKEN -> {} // Should not happen.
            }
        }
        private void queueMessage(Packet msg) {
            if (!authenticated) return;         // If not authenticated cannot receive message
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
            if (!closed && bbIn.hasRemaining()) op |= SelectionKey.OP_READ;
            if (bbOut.position() != 0)          op |= SelectionKey.OP_WRITE;
            if (op == 0) {
                clients.remove(pseudo);
                silentlyClose();
                return;
            }
            key.interestOps(op);
        }
        private void silentlyClose() {
            try {
                sc.close();
            } catch (IOException ignore) {}
        }
        private void doRead() throws IOException {
            if (sc.read(bbIn) == -1) {
                closed = true;
                logger.info("Connection closed");
            }
            processIn();
            updateInterestOps();
        }
        private void doWrite() throws IOException {
            sc.write(bbOut.flip());
            bbOut.compact();
            processOut();
            updateInterestOps();
        }
    }

    /**
     * Record that represents a key for a private connection between
     * {@link #client1} and {@link #client2}. The two clients can be in any order.
     * In other words, this code should print "Correct":
     * <blockquote><pre>
     * var key1 = new PCKey("foo", "bar");
     * var key2 = new PCKey("bar", "foo");
     * if (key1.equals(key2)) {             // Should be true
     *     System.out.println("Correct");
     * }</pre></blockquote>
     */
    private static record PCKey(String client1, String client2) {
        PCKey {
            requireNonNull(client1);
            requireNonNull(client2);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PCKey pcKey = (PCKey) o;
            return client1.equals(pcKey.client1) ? client2.equals(pcKey.client2) :
                    client1.equals(pcKey.client2) && client2.equals(pcKey.client1);
        }

        @Override
        public int hashCode() {
            return client1.hashCode() + client2.hashCode();
        }
    }


    static private final Logger logger = Logger.getLogger(ServerChatOS.class.getName());
    static private final int BUFFER_SIZE = 1_024;

    private final ArrayBlockingQueue<String> commandQueue = new ArrayBlockingQueue<>(10);
    private final HashMap<PCKey, String> privateCP = new HashMap<>();   // Todo : Change String to a context
    private final HashMap<String, Context> clients = new HashMap<>();
    private final HashSet<PCKey> pendingCP = new HashSet<>();
    private final ServerSocketChannel serverSocketChannel;
    private final Selector selector;
    private final Thread console;

    public ServerChatOS(int port) throws IOException {
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(port));
        selector = Selector.open();
        console = new Thread(this::consoleRun);
    }

    private void consoleRun() {
        try (var scan = new Scanner(System.in)) {
            while (!Thread.interrupted() && scan.hasNextLine()) {
                sendCommand(scan.nextLine());
            }
        } finally {
            logger.info("Console thread stopping");
        }
    }
    private void sendCommand(String msg) {
        commandQueue.add(msg.toUpperCase());
        selector.wakeup();
    }

    private void treatCommands() {
        if (commandQueue.isEmpty()) return;

        var msg = commandQueue.remove();
        switch (msg.toUpperCase()) {
            case "INFO" -> System.out.println(clients.size() + " clients connected!");
            case "GMSG" -> broadcast(makeGeneralMessagePacket("Hello", "Server"));
            case "DMSG" -> broadcast(makeDirectMessagePacket("Hello", "Server"));
            case "CP" -> broadcast(makePrivateConnectionPacket("other"));
            case "TOKEN" -> broadcast(makeTokenPacket(10, "other"));
            case "AERR" -> broadcast(makeErrorPacket(Packet.ErrorCode.AUTH_ERROR));
            case "DERR" -> broadcast(makeErrorPacket(Packet.ErrorCode.DEST_ERROR));
            case "EREC" -> broadcast(makeErrorPacket(ERROR_RECOVER));
            case "WCOD" -> broadcast(makeErrorPacket(Packet.ErrorCode.WRONG_CODE));
            case "REJ" -> broadcast(makeRejectedPacket("Server"));
            case "INV" -> broadcast(makeErrorPacket(Packet.ErrorCode.INVALID_LENGTH));
            case "OTHER" -> broadcast(makeErrorPacket(Packet.ErrorCode.OTHER));
            default -> broadcast(parseInput(msg));
        }
    }
    private Packet parseInput(String msg) {
        if (msg.startsWith("@")) {
            var tokens = msg.substring(1).split(" ", 2);
            return makeDirectMessagePacket(tokens[1], tokens[0]);
        }
        if (msg.startsWith("/")) {
            var tokens = msg.substring(1).split(" ", 2);
            return makePrivateConnectionPacket(tokens[0]);
        }
        return msg.startsWith("\\@") || msg.startsWith("\\/") ?
                makeGeneralMessagePacket(msg.substring(1), "Server") : makeGeneralMessagePacket(msg, "Server");
    }

    public void launch() throws IOException {
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        console.start();

        while(!Thread.interrupted()) {
            try {
                selector.select(this::treatKey);
                treatCommands();
            } catch (UncheckedIOException tunneled) {
                console.interrupt();
                throw tunneled.getCause();
            }
        }
        System.out.println("fini");
        console.interrupt();
    }

    private void treatKey(SelectionKey key) {
        try {
            if (key.isValid() && key.isAcceptable()) {
                doAccept(key);
            }
        } catch(IOException ioe) {
            // lambda call in select requires to tunnel IOException
            throw new UncheckedIOException(ioe);
        }
        var ctx = (Context) key.attachment();
        try {
            if (key.isValid() && key.isWritable()) {
                ctx.doWrite();
            }
            if (key.isValid() && key.isReadable()) {
                ctx.doRead();
            }
        } catch (IOException e) {
            logger.info("Connection closed with client due to IOException");
            clients.remove(ctx.pseudo);
            silentlyClose(key);
        }
    }

    private void doAccept(SelectionKey key) throws IOException {
        var sc = (ServerSocketChannel) key.channel();
        var client = sc.accept();
        if (client == null) {
            logger.info("Wrong hint from selector");
            return;
        }
        client.configureBlocking(false);
        var clientKey = client.register(selector, SelectionKey.OP_READ);
        clientKey.attach(new Context(clientKey));
    }

    private void silentlyClose(SelectionKey key) {
        silentlyClose(key.channel());
    }
    private void silentlyClose(Channel sc) {
        try {sc.close();} catch (IOException ignore) {}
    }

    private void broadcast(Packet msg) {
        for (var client : selector.keys()) {
            if (client.channel() == serverSocketChannel) continue;
            ((Context) client.attachment()).queueMessage(msg);
        }
    }

    public static void main(String[] args) throws NumberFormatException, IOException {
        if (args.length!=1){
            usage();
            return;
        }
        new ServerChatOS(Integer.parseInt(args[0])).launch();
    }

    private static void usage(){
        System.out.println("Usage : ServerChatOS port");
    }
}
