package fr.uge.net.tcp.nonblocking.server;

import fr.uge.net.tcp.nonblocking.Packet;
import fr.uge.net.tcp.nonblocking.client.AbstractContext;
import fr.uge.net.tcp.nonblocking.reader.PacketReader;
import fr.uge.net.tcp.nonblocking.reader.PacketReader.ProcessFailure;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.Packet.ErrorCode.*;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.makeErrorPacket;
import static fr.uge.net.tcp.nonblocking.Packet.PacketType.ERR;

public class RejectReader {
    private static final int ERR_CODE = ERR.ordinal();
    private static final int RECOVER_CODE = ERROR_RECOVER.ordinal();

    private boolean rejecting = false;
    private byte last = 1;

    /**
     * If the {@link RejectReader} is not in reject mode, returns true.
     * Otherwise tries to read until the sequence of byte : {@link #ERR_CODE} | {@link #RECOVER_CODE}
     * is found. If the sequence is found in the {@code buff}, returns true; false otherwise.
     *
     * @param buff the input data. Must be in write-mode.
     * @return false if the reader has not found the sequence; true if the reader is not
     * rejecting or if the sequence is found.
     */
    public boolean process(ByteBuffer buff) {
        if (!rejecting) return true;
        var completed = subProcess(buff.flip());
        buff.compact();
        if (!completed) return false;
        last = 1;
        rejecting = false;
        return true;
    }

    /**
     * Reads byte by byte until two consecutive bytes are equals to {@link #ERR_CODE} and {@link #RECOVER_CODE}.
     * @param buff the input data. Must be in read-mode.
     * @return true if the processing found the correct sequence; false otherwise.
     */
    private boolean subProcess(ByteBuffer buff) {
        while (buff.hasRemaining()) {
            var c = buff.get();
            if (last == ERR_CODE && c == RECOVER_CODE) return true;
            last = c;
        }
        return false;
    }

    /**
     * Sets the Reader in reject mode.
     * Also send an error packet to the {@code context} depending on the failure.
     *
     * @param failure the failure from the other reader. Cannot be null.
     * @param context the context in which we send the error packet. Cannot be null.
     */
    public void reject(ProcessFailure failure, AbstractContext context) {
        this.rejecting = true;
        switch (failure) {
            case CODE -> context.queueMessage(makeErrorPacket(WRONG_CODE).toBuffer());
            case LENGTH -> context.queueMessage(makeErrorPacket(INVALID_LENGTH).toBuffer());
        }
    }
}
