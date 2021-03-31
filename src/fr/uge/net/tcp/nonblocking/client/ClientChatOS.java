package fr.uge.net.tcp.nonblocking.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Logger;

import static java.util.Objects.requireNonNull;

public class ClientChatOS {
    static private final Logger logger = Logger.getLogger(ClientChatOS.class.getName());

    private final ArrayBlockingQueue<String> commandQueue = new ArrayBlockingQueue<>(10);
    private final HashMap<String, PrivateConnectionContext> privateConnections = new HashMap<>();
    private final InetSocketAddress serverAddress;
    private final Selector selector;
    private MainContext mainContext;
    private final SocketChannel sc;
    private final String repertory;
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
    private PrivateConnectionContext getOrCreatePrivateConnection(String pseudo) throws IOException {
        if (privateConnections.containsKey(pseudo)) return privateConnections.get(pseudo);
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
        if (msg.startsWith("/")) {
            var tokens = msg.split(" ", 2);
            var context = getOrCreatePrivateConnection(tokens[0]);
            context.queueMessage(tokens[1]);
        } else {
            synchronized (commandQueue) {
                commandQueue.add(msg);
                selector.wakeup();
            }
        }
    }
    private void processCommands(){
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
        new ClientChatOS(new InetSocketAddress(args[0],Integer.parseInt(args[1])), args[2]).launch();
    }

    private static void usage(){
        System.out.println("Usage : ClientChatOS hostname port repertory");
    }
}
