package fr.uge.net.tcp.nonblocking.client;

import fr.uge.net.tcp.nonblocking.Packet;
import fr.uge.net.tcp.nonblocking.reader.ClientPacketReader;
import fr.uge.net.tcp.nonblocking.reader.Reader;

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

public class ClientChatOS {
    private class Context {
        private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
        private final SelectionKey key;
        private final SocketChannel sc;
        private boolean closed = false;

        private Context(SelectionKey key){
            this.key = key;
            this.sc = (SocketChannel) key.channel();
        }

        private void processIn() { // TODO
        }
        private void processOut() { // TODO
        }

        private void updateInterestOps() {
            var op=0;
            if (!closed && bbIn.hasRemaining()) op |= SelectionKey.OP_READ;
            if (bbOut.position()!=0)            op |= SelectionKey.OP_WRITE;
            if (op==0)                          silentlyClose(sc);
            key.interestOps(op);
        }

        private void doRead() throws IOException {
            if (sc.read(bbIn)==-1) {
                closed=true;
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

        public void doConnect() throws IOException {
            if (!sc.finishConnect()) return;
            key.interestOps(SelectionKey.OP_WRITE);
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
            while (scan.hasNextLine()) {
                sendCommand(scan.nextLine());
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
        // TODO
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
    private void silentlyClose(Channel channel) {
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
