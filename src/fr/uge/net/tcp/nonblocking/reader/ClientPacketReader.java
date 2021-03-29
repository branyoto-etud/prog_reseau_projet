package fr.uge.net.tcp.nonblocking.reader;

import fr.uge.net.tcp.nonblocking.Packet;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.*;

public class ClientPacketReader implements Reader<Packet> {
    @Override
    public ProcessStatus process(ByteBuffer bb) {
        return REFILL;
    }

    @Override
    public Packet get() {
        return null;
    }
    @Override
    public void reset() {
    }
}
