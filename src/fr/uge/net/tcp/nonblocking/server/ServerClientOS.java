package fr.uge.net.tcp.nonblocking.server;

import fr.uge.net.tcp.nonblocking.Packet;
import fr.uge.net.tcp.nonblocking.reader.ClientPacketReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.LinkedList;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.*;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.makePrivateConnectionPacket;
import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.onMessageReceived;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.DONE;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.REFILL;

public class ServerClientOS {

    private class Context {
        final private LinkedList<ByteBuffer> queue = new LinkedList<>();
        final private ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_SIZE);
        final private ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_SIZE);
        private final ClientPacketReader reader = new ClientPacketReader();
        final private SelectionKey key;
        final private SocketChannel sc;
        private boolean closed = false;

        private Context(SelectionKey key){
            this.sc = (SocketChannel) key.channel();
            this.key = key;
        }
        private void processIn() {
            var status = reader.process(bbIn);
            if (status == REFILL) return;
            if (status == DONE) {
                onMessageReceived(reader.get(), "Server");
                if (reader.get().type() == Packet.PacketType.AUTH) {
                    queueMessage(makeAuthenticationPacket(reader.get().pseudo()));
                }
            }
            reader.reset();
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
            if (!closed && bbIn.hasRemaining()) op |= SelectionKey.OP_READ;
            if (bbOut.position() != 0)          op |= SelectionKey.OP_WRITE;
            if (op == 0) {
                counter.decrementAndGet();
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

    static private final Logger logger = Logger.getLogger(ServerClientOS.class.getName());
    static private final int BUFFER_SIZE = 1_024;

    private final ArrayBlockingQueue<String> commandQueue = new ArrayBlockingQueue<>(10);
    private final AtomicInteger counter = new AtomicInteger(1);
    private final ServerSocketChannel serverSocketChannel;
    private final Selector selector;
    private final Thread console;

    public ServerClientOS(int port) throws IOException {
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
            case "INFO" -> System.out.println(counter.get()-1 + " clients connected!");
            case "GMSG" -> broadcast(makeGeneralMessagePacket("Hello", "Server"));
            case "DMSG" -> broadcast(makeDirectMessagePacket("Hello", "Server"));
            case "CP" -> broadcast(makePrivateConnectionPacket("other"));
            case "TOKEN" -> broadcast(makeTokenPacket(10, "other"));
            case "AERR" -> broadcast(makeErrorPacket(Packet.ErrorCode.AUTH_ERROR));
            case "DERR" -> broadcast(makeErrorPacket(Packet.ErrorCode.DEST_ERROR));
            case "EREC" -> broadcast(makeErrorPacket(Packet.ErrorCode.ERROR_RECOVER));
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

        while(!Thread.interrupted() && counter.get() > 0) {
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
        try {
            if (key.isValid() && key.isWritable()) {
                ((Context) key.attachment()).doWrite();
            }
            if (key.isValid() && key.isReadable()) {
                ((Context) key.attachment()).doRead();
            }
        } catch (IOException e) {
            logger.info("Connection closed with client due to IOException");
            counter.decrementAndGet();
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
        counter.incrementAndGet();
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
        new ServerClientOS(Integer.parseInt(args[0])).launch();
    }

    private static void usage(){
        System.out.println("Usage : ServerClientOS port");
    }
}
