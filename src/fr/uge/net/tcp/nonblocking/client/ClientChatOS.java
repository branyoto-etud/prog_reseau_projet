package fr.uge.net.tcp.nonblocking.client;

import fr.uge.net.tcp.nonblocking.Packet;
import fr.uge.net.tcp.nonblocking.reader.ClientPacketReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Logger;

import static fr.uge.net.tcp.nonblocking.Config.BUFFER_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.*;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.makeGeneralMessagePacket;
import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.*;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.DONE;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.REFILL;
import static java.lang.Integer.parseInt;
import static java.util.Objects.requireNonNull;

public class ClientChatOS {
    private class MainContext implements Context {
        private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final ClientPacketReader reader = new ClientPacketReader();
        private final LinkedList<ByteBuffer> queue = new LinkedList<>();        // Stored buffer are in write-mode
        private final SelectionKey key;
        private final SocketChannel sc;
        private boolean connected;
        private boolean closed;

        public MainContext(SelectionKey key_){
            sc = (SocketChannel) key_.channel();
            connected = false;
            closed = false;
            key = key_;
        }
        private void processIn() throws IOException {
            // TODO : Try to reconnect on error to avoid having multiple problems
            var status = reader.process(bbIn);              // Processes the buffer
            if (status == REFILL) return;                   // If needs refill -> stop
            if (status == DONE) treatPacket(reader.get());  // If done -> treats the packet
            reader.reset();                                 // Resets the buffer
        }
        private void treatPacket(Packet packet) throws IOException {
            onMessageReceived(packet);
            switch (packet.type()) {
                case ERR -> treatError(packet.code());  // Do an action on error
                case AUTH -> connected = true;          // Validate connection
                case GMSG, DMSG -> {}                   // Only need to be displayed (already done)
                case CP -> {
                    getPrivateConnection(packet.pseudo());
                    waiting = packet.pseudo();
                }
                case TOKEN -> {                         // Todo : pass it to the correct private connection
                    var ctx = getPrivateConnection(packet.pseudo());
                    ctx.launch(parseInt(packet.message()));
                }
            }
        }
        private void treatError(Packet.ErrorCode code) {
            switch (code) {
                case AUTH_ERROR, DEST_ERROR -> {}   // Nothing
                case REJECTED -> {                  // Todo: pass it to the correct private connection
                }
                case ERROR_RECOVER -> {}            // Should not happen
                case WRONG_CODE, INVALID_LENGTH, OTHER -> // When receiving one of these error need to send a ERROR_RECOVERY
                        queue.addFirst(makeErrorPacket(Packet.ErrorCode.ERROR_RECOVER).toBuffer());
                default -> {}  // Nothing
            }
        }
        public void queueMessage(String msg) throws IOException {
            if (waiting != null) {
                whileWaiting(msg);
                return;
            }
            queue.add(parseInput(msg).toBuffer());
            processOut();
            updateInterestOps();
        }
        private void whileWaiting(String msg) {
            if (msg.toLowerCase().startsWith("y")) {
                queue.add(makePrivateConnectionPacket(waiting).toBuffer());
                waiting = null;
            } else if (msg.toLowerCase().startsWith("n")) {
                queue.add(makeRejectedPacket(waiting).toBuffer());
                waiting = null;
            }
        }
        private Packet parseInput(String msg) throws IOException {
            if (!connected) return makeAuthenticationPacket(msg);

            if (msg.startsWith("@")) {
                var tokens = msg.substring(1).split(" ", 2);
                return makeDirectMessagePacket(tokens[0], tokens[1]);
            }
            if (msg.startsWith("/")) {
                var tokens = msg.substring(1).split(" ", 2);
                getPrivateConnection(tokens[0]).queueMessage(tokens[1]);
                return makePrivateConnectionPacket(tokens[0]);
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
                key.interestOps(SelectionKey.OP_WRITE);
            } catch (IOException ioe) {
                onConnectFail();
                throw ioe;
            }
        }
    }

    private static final Logger logger = Logger.getLogger(ClientChatOS.class.getName());

    private final ArrayBlockingQueue<String> commandQueue = new ArrayBlockingQueue<>(10);
    private final HashMap<String, PrivateConnectionContext> privateConnections = new HashMap<>();
    private final InetSocketAddress serverAddress;
    private final Selector selector;
    private MainContext mainContext;
    private final SocketChannel sc;
    private final String repertory;
    private String waiting = null;
    private final Thread console;

    public ClientChatOS(InetSocketAddress serverAddress, String repertory) throws IOException {
        this.serverAddress = requireNonNull(serverAddress);
        this.repertory = requireNonNull(repertory);
        console = new Thread(this::consoleRun);
        selector = Selector.open();
        sc = SocketChannel.open();
        sc.configureBlocking(false);
    }

    private void consoleRun() {
        try (var scan = new Scanner(System.in)){
            while (scan.hasNextLine()) {
                sendCommand(scan.nextLine());
            }
        } catch (IOException e) {
            logger.severe("Cannot create a socket for a private connection!");
        } finally {
            logger.info("Console thread stopping!");
        }
    }
    private PrivateConnectionContext getPrivateConnection(String pseudo) throws IOException {
        if (privateConnections.containsKey(pseudo))
            return privateConnections.get(pseudo);
        var pc = SocketChannel.open();
        pc.configureBlocking(false);
        var key = pc.register(selector, SelectionKey.OP_CONNECT);
        var context = (PrivateConnectionContext) key.attach(new PrivateConnectionContext(pseudo, key));
        pc.connect(serverAddress);
        privateConnections.put(pseudo, context);
        return context;
    }
    private void sendCommand(String msg) throws IOException {
        if (msg.isBlank()) return;
        synchronized (commandQueue) {
            commandQueue.add(msg);
            selector.wakeup();
        }
    }
    private void processCommands() throws IOException {
        synchronized (commandQueue) {
            if (commandQueue.isEmpty()) return;
            mainContext.queueMessage(commandQueue.remove());
        }
    }
    public void launch() throws IOException {
        var key = sc.register(selector, SelectionKey.OP_CONNECT);
        mainContext = (MainContext) key.attach(new MainContext(key));
        sc.connect(serverAddress);
        console.start();

        while(!Thread.interrupted()) {
            try {
                selector.select(this::treatKey);
                processCommands();
            } catch (UncheckedIOException tunneled) {
                console.interrupt();
                silentlyClose(key.channel());
                throw tunneled.getCause();
            }
        }
    }
    private void treatKey(SelectionKey key) {
        try {
            if (key.isValid() && key.isConnectable()) {
                mainContext.doConnect();
            }
            if (key.isValid() && key.isWritable()) {
                mainContext.doWrite();
            }
            if (key.isValid() && key.isReadable()) {
                mainContext.doRead();
            }
        } catch(IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    // --------------------------------------------------
    // Static Methods
    // --------------------------------------------------

    public static void silentlyClose(Channel channel) {
        try {channel.close();} catch (IOException ignore) {}
    }

    public static void main(String[] args) throws NumberFormatException, IOException {
        if (args.length!=3){
            usage();
            return;
        }
        new ClientChatOS(new InetSocketAddress(args[0], parseInt(args[1])), args[2]).launch();
    }

    private static void usage(){
        System.out.println("Usage : ClientChatOS hostname port repertory");
    }
}
