package fr.uge.net.tcp.nonblocking.client;

import fr.uge.net.tcp.nonblocking.context.AbstractContext;
import fr.uge.net.tcp.nonblocking.context.Context;
import fr.uge.net.tcp.nonblocking.packet.Packet;
import fr.uge.net.tcp.nonblocking.http.HTTPPacket;
import fr.uge.net.tcp.nonblocking.http.HTTPReader;

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

import static fr.uge.net.tcp.nonblocking.utils.ChatOSUtils.CONTENT_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.packet.Packet.PacketBuilder.makeTokenPacket;
import static fr.uge.net.tcp.nonblocking.http.HTTPPacket.*;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.DONE;
import static java.lang.Integer.parseInt;
import static java.util.Objects.requireNonNull;

class PrivateConnectionContext extends AbstractContext implements Context {
    private final HTTPReader reader = new HTTPReader();
    private final String directory;
    private final String pseudo;

    /**
     * @param packet the packet containing the token. Cannot be null.
     * @param directory the working directory. Cannot be null.
     * @param key the connection key. Cannot be null.
     */
    public PrivateConnectionContext(Packet packet, String directory, SelectionKey key) {
        super(requireNonNull(key));
        this.pseudo = packet.pseudo();
        this.directory = requireNonNull(directory);
        queue.add(makeTokenPacket(parseInt(packet.message()), packet.pseudo()).toBuffer());
    }

    /**
     * Tries to process data from {@link #bbIn} and uses the data
     * if ready.
     * <br>
     * This method is recursive and all processIn should be. But not quit sure yet.
     */
    public void processIn() {
        var status = reader.process(bbIn);
        if (status != DONE) return;
        treatPacket(reader.get());
        reader.reset();
        processIn();
    }

    /**
     * Analyses the type of the {@code packet} and chooses an action.
     * @param packet the meaning full packet.
     */
    private void treatPacket(HTTPPacket packet) {
        switch (packet.type()) {
            case REQUEST -> onRequest(packet.resource());
            case GOOD_RESPONSE -> onGoodResponse(packet);
            case BAD_RESPONSE -> System.out.println("Bad request : " + packet.resource());
        }
    }

    /**
     * Sends the requested resource if it exists else sends a bad HTTP Response.
     * @param resource the requested resource.
     */
    private void onRequest(String resource) {
        if (!fileExists(resource)) {
            queueMessage(createBadResponse(resource).toBuffer());
        } else {
            var packets = resourceToPackets(resource, resource.endsWith(".txt") ? TEXT_CONTENT : OTHER_CONTENT);
            packets.forEach(p -> queueMessage(p.toBuffer()));
        }
    }

    /**
     * If the packet contains a good HTTP response.
     * Displays the content if it contains text.
     * Else, saves it inside the file {@code resource} in {@link #directory} otherwise.
     *
     * @param packet the response packet.
     */
    private void onGoodResponse(HTTPPacket packet) {
        if (TEXT_CONTENT.equals(packet.contentType())) writeAsText(packet.content());
        else writeAsData(packet.content(), packet.resource());
    }
    /**
     * Adds the request for the given {@code resource} to the message queue.
     * @param resource the requested resource. Cannot be null.
     */
    public void queueMessage(String resource) {
        requireNonNull(resource);
        queueMessage(HTTPPacket.createRequest(resource).toBuffer());
    }

    /**
     * Reads data in {@link #bbIn}.
     * If there's no data to read, {@link #closed} is set to true.
     *
     * @throws IOException if the connection is closed.
     */
    @Override
    public void doRead() throws IOException {
        super.doRead();
        if (!isConnected()){ // Todo: find better solution
            throw new IOException("Connection closed");
        }
    }

    /**
     * Tests if the file {@code resource} exists inside {@link #directory}.
     * @param resource the file to check. Cannot be null.
     * @return true if the file exists; false if not.
     */
    private boolean fileExists(String resource) {
        return Files.exists(resourceToPath(resource));
    }

    /**
     * Reads the file {@code resource} and creates multiple {@link HTTPPacket} if the file
     * is bigger than {@link fr.uge.net.tcp.nonblocking.utils.ChatOSUtils#CONTENT_MAX_SIZE}.
     *
     * @param resource the requested resource. Cannot be null.
     * @param contentType the type of the content. Can be either {@link HTTPPacket#TEXT_CONTENT}
     *                    or {@link HTTPPacket#OTHER_CONTENT}.
     * @return the list of created packets, or a list with only a Bad HTTP Response
     * if the file produces an {@link IOException}.
     */
    private List<HTTPPacket> resourceToPackets(String resource, String contentType) {
        try (var fc = FileChannel.open(resourceToPath(resource), StandardOpenOption.READ)) {
            var packets = new ArrayList<HTTPPacket>();
            fillWithFile(packets, fc, resource, contentType);
            return packets;
        } catch (IOException e) {
            return List.of(createBadResponse(resource));
        }
    }

    /**
     * Fills the input list with the packets read by the method.
     *
     * @param packets the list to fill. Cannot be null.
     * @param fc the file where to read. Cannot be null.
     * @param resource the requested resource. Cannot be null.
     * @param contentType the type of the content. Can be either {@link HTTPPacket#TEXT_CONTENT}
     *                    or {@link HTTPPacket#OTHER_CONTENT}.
     * @throws IOException if the read fails.
     */
    private void fillWithFile(ArrayList<HTTPPacket> packets, FileChannel fc,
                              String resource, String contentType) throws IOException {
        var buff = ByteBuffer.allocate(CONTENT_MAX_SIZE);
        while (fc.read(buff) != -1) {
            if (!buff.hasRemaining()) {
                packets.add(createGoodResponse(contentType, buff.flip(), resource));
                buff = ByteBuffer.allocate(CONTENT_MAX_SIZE);
            }
        }
        if (buff.position() != 0)
            packets.add(createGoodResponse(contentType, buff.flip(), resource));
    }

    /**
     * Displays in the standard output the {@code content} decoded in {@link StandardCharsets#UTF_8}.
     * @param content the content to display. Cannot be null.
     */
    private static void writeAsText(ByteBuffer content) {
        requireNonNull(content);
        System.out.println(StandardCharsets.US_ASCII.decode(content).toString());
    }

    /**
     * Writes the {@code content} into the given file.
     * @param content the content to write. Cannot be null.
     * @param resource the file where to write the content.
     */
    private void writeAsData(ByteBuffer content, String resource) {
        try (var fc = FileChannel.open(resourceToPath(resource), StandardOpenOption.APPEND, StandardOpenOption.CREATE)) {
            while (content.hasRemaining()) {
                fc.write(content);
            }
        } catch (IOException ioe) {
            System.err.println("Cannot open the file " + resource + " in write mode.");
        }
    }

    /**
     * Converts the {@code resource} into a {@link Path}.
     * @param resource the file within the working {@link #directory}.
     * @return the corresponding path.
     */
    private Path resourceToPath(String resource) {
        requireNonNull(resource);
        return Paths.get(resource.startsWith("/") ? directory + resource : directory + "/" + resource);
    }

    /**
     * Closes the channel and removes it from the {@code privateConnections}.
     * @param privateConnections the map from which we want to remove the context.
     */
    public void close(Map<String, PrivateConnectionContext> privateConnections) {
        close();
        privateConnections.remove(pseudo);
    }
}
