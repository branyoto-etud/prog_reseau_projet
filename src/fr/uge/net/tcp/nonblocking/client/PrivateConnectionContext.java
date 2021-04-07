package fr.uge.net.tcp.nonblocking.client;

import fr.uge.net.tcp.nonblocking.Packet;
import fr.uge.net.tcp.nonblocking.reader.HTTPPacket;
import fr.uge.net.tcp.nonblocking.reader.HTTPReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static fr.uge.net.tcp.nonblocking.ChatOSUtils.silentlyClose;
import static fr.uge.net.tcp.nonblocking.Config.BUFFER_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.Config.CONTENT_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.makeTokenPacket;
import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.onConnectFail;
import static fr.uge.net.tcp.nonblocking.reader.HTTPPacket.*;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.DONE;
import static java.lang.Integer.parseInt;
import static java.util.Objects.requireNonNull;

class PrivateConnectionContext implements Context {
    private final ByteBuffer bbOut = ByteBuffer.allocate(BUFFER_MAX_SIZE);
    private final ByteBuffer bbIn = ByteBuffer.allocate(BUFFER_MAX_SIZE);
    private final LinkedList<ByteBuffer> queue = new LinkedList<>();        // Buffer are in read-mode
    private final HTTPReader reader = new HTTPReader();
    private final String directory;
    private final SocketChannel sc;
    private final SelectionKey key;
    private boolean connected;
    private boolean closed;

    public PrivateConnectionContext(Packet packet, String directory, SelectionKey key) {
        sc = (SocketChannel) key.channel();
        this.key = requireNonNull(key);
        this.directory = directory;
        queue.add(makeTokenPacket(parseInt(packet.message()), packet.pseudo()).toBuffer());
    }
    private void processIn() {
        var status = reader.process(bbIn);
        if (status != DONE) return;
        var packet = reader.get();
        reader.reset();
        switch (packet.type()) {
            case REQUEST -> {
                var resource = packet.resource();
                if (!fileExists(resource)) {
                    queueMessage(createBadResponse().toBuffer());
                    return;
                }
                var contentType = resource.endsWith(".txt") ? TEXT_CONTENT : OTHER_CONTENT;
                var packets = resourceToPackets(resource, contentType);
                packets.forEach(System.out::println);
                packets.forEach(p -> queueMessage(p.toBuffer()));
            }
            case GOOD_RESPONSE -> {
                if (TEXT_CONTENT.equals(packet.contentType())) {
                    writeAsText(packet.content());
                } else {
                    writeAsData(packet.content(), packet.resource());
                }
            }
            case BAD_RESPONSE -> System.out.println("Bad request : " + packet.resource());
        }
    }
    public void queueMessage(String msg) {
        queueMessage(HTTPPacket.createRequest(msg).toBuffer());
    }
    public void queueMessage(ByteBuffer buff) {
        queue.add(buff);
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
        var op=0;
        if (!closed && bbIn.hasRemaining()) op  = SelectionKey.OP_READ;     // If there's something to read
        if (bbOut.position()!=0)            op |= SelectionKey.OP_WRITE;    // If there's something to write
        if (!connected)                     op  = SelectionKey.OP_CONNECT;  // If not connected only connect
        if (op == 0)                        silentlyClose(sc);              // If there's nothing to read nor write
        else                                key.interestOps(op);
    }
    @Override
    public void doRead() throws IOException {
        if (sc.read(bbIn)==-1) {
            closed = true;
        }
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
            connected = true;
            processOut();
            updateInterestOps();
        } catch (IOException ioe) {
            onConnectFail();
            throw ioe;
        }
    }

    private boolean fileExists(String resource) {
        var path = resource.startsWith("/") ? directory + resource : directory + "/" + resource;
        return Files.exists(Paths.get(path));
    }
    private List<HTTPPacket> resourceToPackets(String resource, String contentType) {
        var buffers = new ArrayList<HTTPPacket>();
        var path = resource.startsWith("/") ? directory + resource : directory + "/" + resource;
        try (var fc = FileChannel.open(Paths.get(path), StandardOpenOption.READ)) {
            var buff = ByteBuffer.allocate(CONTENT_MAX_SIZE);
            while (fc.read(buff) != -1) {
                if (!buff.hasRemaining()) {
                    buffers.add(createGoodResponse(contentType, buff.flip(), resource));
                    buff = ByteBuffer.allocate(CONTENT_MAX_SIZE);
                }
            }
            if (buff.position() != 0)
                buffers.add(createGoodResponse(contentType, buff.flip(), resource));
        } catch (IOException e) {
            return List.of(createBadResponse());
        }
        return buffers;
    }
    private static void writeAsText(ByteBuffer content) {
        System.out.println(StandardCharsets.US_ASCII.decode(content).toString());
    }
    private void writeAsData(ByteBuffer content, String resource) {
        var path = resource.startsWith("/") ? directory + resource : directory + "/" + resource;
        try (var fc = FileChannel.open(Paths.get(path), StandardOpenOption.APPEND, StandardOpenOption.CREATE)) {
            while (content.hasRemaining()) {
                fc.write(content);
            }
        } catch (IOException ioe) {
            System.err.println("Cannot open the file " + resource + " in write mode.");
        }
    }
}
