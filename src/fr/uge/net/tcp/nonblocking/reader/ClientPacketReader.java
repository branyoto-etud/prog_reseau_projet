package fr.uge.net.tcp.nonblocking.reader;

import fr.uge.net.tcp.nonblocking.Packet;
import fr.uge.net.tcp.nonblocking.Packet.PacketType;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.*;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.*;
import static fr.uge.net.tcp.nonblocking.reader.Reader.moveData;
import static java.util.Objects.requireNonNull;

public class ClientPacketReader implements Reader<Packet> {
    private final ByteBuffer buff = ByteBuffer.allocate(Integer.BYTES); // Used to read a PacketType.TOKEN
    private final StringReader reader = new StringReader();
    private ProcessStatus status = REFILL;
    private PacketType type = null;
    private Packet packet = null;
    private String element = null;

    @Override
    public ProcessStatus process(ByteBuffer bb) {
        requireNonNull(bb);
        if (status != REFILL) throw new IllegalStateException();
        status = subProcess(bb);
        return status;
    }

    private ProcessStatus subProcess(ByteBuffer bb) {
        if (type == null) {
            var t = bb.flip().get();
            bb.compact();
            if (t < 0 || t >= PacketType.values().length) return ERROR;
            type = PacketType.values()[t];
        }

        switch (type) {
            case ERR -> {
                var ret = !bb.flip().hasRemaining() ? REFILL :
                        (packet = makeErrorPacket(bb.get())) == null ? ERROR
                                : DONE;
                bb.compact();
                return ret;
            }
            case AUTH -> {
                var status = reader.process(bb);
                if (status == DONE) packet = makeAuthenticationPacket(reader.get());
                return status;
            }
            case GMSG -> {
                var status = processTwoString(bb);
                if (status == DONE) packet = makeGeneralMessagePacket(element, reader.get());
                return status;
            }
            case DMSG -> {
                var status = processTwoString(bb);
                if (status == DONE) packet = makeDirectMessagePacket(element, reader.get());
                return status;
            }
            case CP -> {
                var status = reader.process(bb);
                if (status == DONE) packet = makePrivateConnectionPacket(reader.get());
                return status;
            }
            case TOKEN -> {
                if (moveData(bb.flip(), buff)) return REFILL;
                bb.compact();

            }
        }
        return ERROR; // Shouldn't happen
    }

    private ProcessStatus processTwoString(ByteBuffer bb) {
        if (element == null) {
            var status = reader.process(bb);
            if (status != DONE) return status;
            element = reader.get();
            reader.reset();
        }
        return reader.process(bb);
    }
    @Override
    public Packet get() {
        if (status == DONE) return packet;
        throw new IllegalStateException("Cannot get a message if the process method haven't return ProcessStatus.DONE.");
    }
    @Override
    public void reset() {
        buff.clear();
        reader.reset();
        status = REFILL;
        type = null;
        packet = null;
        element = null;
    }
}
