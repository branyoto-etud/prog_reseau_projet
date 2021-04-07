package fr.uge.net.tcp.nonblocking.reader;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.Config.BUFFER_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.DONE;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.REFILL;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;

public class HTTPLineReader implements Reader<String> {
    private static final byte CR = '\015';     // ASCII code for \r
    private static final byte LF = '\012';     // ASCII code for \n

    private final ByteBuffer buff = ByteBuffer.allocate(BUFFER_MAX_SIZE); // Always in write-mode
    private final StringBuilder lineBuilder = new StringBuilder();
    private ProcessStatus status = REFILL;
    private int pos = -1;

    @Override
    public ProcessStatus process(ByteBuffer bb) {
        requireNonNull(bb);
        if (status != REFILL)
            throw new IllegalStateException("Reader not ready to process data.");
        if ((status = subProcess(bb.flip())) == DONE)
            lineBuilder.append(US_ASCII.decode(buff.flip().limit(pos)));
        bb.compact();
        return status;
    }
    private ProcessStatus subProcess(ByteBuffer bb) {
        while (bb.hasRemaining()) {
            var c = bb.get();
            if (c == LF && pos != -1 && buff.get(pos) == CR) return DONE;
            buff.put(c);
            pos++;
            if (!buff.hasRemaining()) {
                lineBuilder.append(US_ASCII.decode(buff.flip().limit(pos)));
                buff.clear().put(buff.get(pos));
                pos = 0;
            }
        }
        return REFILL;
    }

    @Override
    public String get() {
        if (status == DONE) return lineBuilder.toString();
        throw new IllegalStateException("Reader not done!");
    }

    @Override
    public void reset() {
        lineBuilder.setLength(0);
        status = REFILL;
        buff.clear();
        pos = -1;
    }
}
