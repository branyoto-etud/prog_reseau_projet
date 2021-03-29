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
import java.util.LinkedList;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Logger;

import static fr.uge.net.tcp.nonblocking.Config.BUFFER_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.makeAuthenticationPacket;
import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.*;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.*;

public class ClientChatOS {
    private static class Context {
        private final LinkedList<ByteBuffer> queue = new LinkedList<>(); // Stored buffer are in write-mode
        private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final ClientPacketReader reader = new ClientPacketReader();
        private final SelectionKey key;
        private final SocketChannel sc;
        private boolean connected;
        private boolean closed;

        private Context(SelectionKey key_){
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
                case AUTH -> connected = true;
                case GMSG, DMSG -> {}   // Only need to be displayed (already done)
                case TOKEN -> { // Todo : store token and start a new socket
                }
            }
        }
        private void treatError(Packet.ErrorCode code) {
            switch (code) {
                case WRONG_CODE, INVALID_LENGTH, OTHER -> {     // When receiving one of these error need to send a ERROR_RECOVERY
                    // Todo : send ERROR_RECOVER
                }
                default -> {}                                   // nothing
            }
        }
        // Todo : Change String to Packet
        private void queueMessage(String msg) {
            if (!connected) {
                queue.add(makeAuthenticationPacket(msg).toBuffer());
            }
            // TODO: add other type of messages
            processOut();
            updateInterestOps();
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
        private void doRead() throws IOException {
            if (sc.read(bbIn)==-1) closed = true;
            processIn();
            updateInterestOps();
        }
        private void doWrite() throws IOException {
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
    static private final Logger logger = Logger.getLogger(ClientChatOS.class.getName());

    private final ArrayBlockingQueue<String> commandQueue = new ArrayBlockingQueue<>(10);
    private final InetSocketAddress serverAddress;
    private final Selector selector;
    private final SocketChannel sc;
    private Context uniqueContext;
    private final Thread console;

    public ClientChatOS(InetSocketAddress serverAddress) throws IOException {
        this.serverAddress = serverAddress;
        this.sc = SocketChannel.open();
        sc.configureBlocking(false);
        this.selector = Selector.open();
        this.console = new Thread(this::consoleRun);
    }

    private void consoleRun() {
        try (var scan = new Scanner(System.in)){
            String type = null;
            while (scan.hasNextLine()) {
                var line = scan.nextLine();
                if (!uniqueContext.connected) sendCommand(line);
                if (type == null) type = line;
                else {
                    sendCommand(line);
                    type = null;
                }
            }
        } finally {
            logger.info("Console thread stopping");
        }
    }
    private void sendCommand(String msg) {
        if (msg.isBlank()) return;
        commandQueue.add(msg);
        selector.wakeup();
    }
    private void processCommands(){
        if (commandQueue.isEmpty()) return;
        uniqueContext.queueMessage(commandQueue.remove());
        uniqueContext.key.interestOps(uniqueContext.key.interestOps() | SelectionKey.OP_WRITE);
    }
    public void launch() throws IOException {
        var key = sc.register(selector, SelectionKey.OP_CONNECT);
        uniqueContext = (Context) key.attach(new Context(key));
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
                uniqueContext.doConnect();
            }
            if (key.isValid() && key.isWritable()) {
                uniqueContext.doWrite();
            }
            if (key.isValid() && key.isReadable()) {
                uniqueContext.doRead();
            }
        } catch(IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    // --------------------------------------------------
    // Static Methods
    // --------------------------------------------------

    private static void silentlyClose(Channel channel) {
        try {channel.close();} catch (IOException ignore) {}
    }
    public static void main(String[] args) throws NumberFormatException, IOException {
        if (args.length!=2){
            usage();
            return;
        }
        new ClientChatOS(new InetSocketAddress(args[0],Integer.parseInt(args[1]))).launch();
    }
    private static void usage(){
        System.out.println("Usage : ClientChatOS hostname port");
    }
}
