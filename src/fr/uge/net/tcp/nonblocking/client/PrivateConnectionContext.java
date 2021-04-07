package fr.uge.net.tcp.nonblocking.client;

import fr.uge.net.tcp.nonblocking.Packet;
import fr.uge.net.tcp.nonblocking.reader.HTTPPacket;
import fr.uge.net.tcp.nonblocking.reader.HTTPReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static fr.uge.net.tcp.nonblocking.Config.CONTENT_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.makeTokenPacket;
import static fr.uge.net.tcp.nonblocking.reader.HTTPPacket.*;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.DONE;
import static java.lang.Integer.parseInt;
import static java.nio.channels.SelectionKey.OP_CONNECT;

class PrivateConnectionContext extends AbstractContext implements Context {
    private final HTTPReader reader = new HTTPReader();
    private final String directory;
    private final String pseudo;

    public PrivateConnectionContext(Packet packet, String directory, SelectionKey key, String pseudo) {
        super(key);
        this.pseudo = pseudo;
        this.directory = directory;
        queue.add(makeTokenPacket(parseInt(packet.message()), packet.pseudo()).toBuffer());
    }
    public void processIn() {
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
    public int updateInterestOps() {
        if (isConnected()) return super.updateInterestOps();
        key.interestOps(OP_CONNECT);
        return OP_CONNECT;
    }
    @Override
    public void doRead() throws IOException {
        super.doRead();
        if (!isConnected()){ // Todo: find better solution
            throw new IOException("Connection closed");
        }
    }
    private boolean fileExists(String resource) {
        return Files.exists(resourceToPath(resource));
    }
    private List<HTTPPacket> resourceToPackets(String resource, String contentType) {
        var buffers = new ArrayList<HTTPPacket>();
        try (var fc = FileChannel.open(resourceToPath(resource), StandardOpenOption.READ)) {
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
        try (var fc = FileChannel.open(resourceToPath(resource), StandardOpenOption.APPEND, StandardOpenOption.CREATE)) {
            while (content.hasRemaining()) {
                fc.write(content);
            }
        } catch (IOException ioe) {
            System.err.println("Cannot open the file " + resource + " in write mode.");
        }
    }
    private Path resourceToPath(String resource) {
        return Paths.get(resource.startsWith("/") ? directory + resource : directory + "/" + resource);
    }
    public void close(Map<String, PrivateConnectionContext> privateConnections) {
        close();
        privateConnections.remove(pseudo);
    }
}
