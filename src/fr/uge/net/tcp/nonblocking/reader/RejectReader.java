package fr.uge.net.tcp.nonblocking.reader;

import fr.uge.net.tcp.nonblocking.context.AbstractContext;
import fr.uge.net.tcp.nonblocking.packet.PacketReader.ProcessFailure;

import java.nio.ByteBuffer;
import java.util.Objects;

import static fr.uge.net.tcp.nonblocking.display.ServerMessageDisplay.onErrorProcessed;
import static fr.uge.net.tcp.nonblocking.display.ServerMessageDisplay.onRecover;
import static fr.uge.net.tcp.nonblocking.packet.Packet.ErrorCode.*;
import static fr.uge.net.tcp.nonblocking.packet.Packet.PacketFactory.makeErrorPacket;
import static fr.uge.net.tcp.nonblocking.packet.Packet.PacketType.ERR;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.DONE;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.REFILL;

/**
 * Reader used to ignore all bytes until the reading of the byte {@link #ERR_CODE} followed by {@link #RECOVER_CODE}.
 */
public class RejectReader implements Reader<Boolean>{
    private static final int ERR_CODE = ERR.ordinal();
    private static final int RECOVER_CODE = ERROR_RECOVER.ordinal();
    private boolean rejecting = false;
    private final String pseudo;
    private byte last = 1;

    /**
     * Creates an object with the given pseudo.
     * @param pseudo the pseudo of the client who created this reader. Cannot be null.
     */
    public RejectReader(String pseudo) {
        this.pseudo = Objects.requireNonNull(pseudo);
    }


    /**
     * If the {@link RejectReader} is not in reject mode, returns false.
     * Otherwise tries to read until the sequence of byte : {@link #ERR_CODE} | {@link #RECOVER_CODE}
     * is found. If the sequence is found in the {@code buff}, returns false; true otherwise.
     * The returned value can be :
     * <ul>
     *   <li>   {@link ProcessStatus#REFILL} : if the reader is still rejecting.</li>
     *   <li>   {@link ProcessStatus#ERROR} : never!</li>
     *   <li>   {@link ProcessStatus#DONE} : if the reader has finished rejecting.</li>
     * </ul>
     *
     * @param buff the input data. Must be in write-mode.
     * @return true if the reader is still rejecting; false if the reader is not
     * rejecting or if the sequence is found.
     */
    public ProcessStatus process(ByteBuffer buff) {
        if (!rejecting) return DONE;
        var completed = subProcess(buff.flip());
        buff.compact();
        if (!completed) return REFILL;
        onRecover(pseudo);
        reset();
        return DONE;
    }

    /**
     * Reads byte by byte until two consecutive bytes are equal to {@link #ERR_CODE} and {@link #RECOVER_CODE}.
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
     * Also sends an error packet to the {@code context} depending on the failure.
     *
     * @param failure the failure from the other reader. Cannot be null.
     * @param context the context in which we send the error packet. Cannot be null.
     * @param pseudo the pseudo of the client. Cannot be null.
     */
    public void reject(ProcessFailure failure, AbstractContext context, String pseudo) {
        this.rejecting = true;
        switch (failure) {
            case CODE -> context.queueMessage(makeErrorPacket(WRONG_CODE).toBuffer());
            case LENGTH -> context.queueMessage(makeErrorPacket(INVALID_LENGTH).toBuffer());
        }
        onErrorProcessed(pseudo);
    }

    /**
     * @return whether this reader is rejecting data or not.
     */
    public Boolean get() {
        return rejecting;
    }

    /**
     * Resets the reader in it's initial state.
     */
    public void reset() {
        rejecting = false;
        last = 1;
    }

}
