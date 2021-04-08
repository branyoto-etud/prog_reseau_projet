package fr.uge.net.tcp.nonblocking.packet;

import fr.uge.net.tcp.nonblocking.packet.Packet.PacketType;
import fr.uge.net.tcp.nonblocking.packet.Packet.ErrorCode;
import fr.uge.net.tcp.nonblocking.reader.Reader;
import fr.uge.net.tcp.nonblocking.reader.StringReader;

import java.nio.ByteBuffer;
import java.util.function.Function;

import static fr.uge.net.tcp.nonblocking.utils.ChatOSUtils.moveData;
import static fr.uge.net.tcp.nonblocking.packet.Packet.ErrorCode.REJECTED;
import static fr.uge.net.tcp.nonblocking.packet.Packet.PacketBuilder.*;
import static fr.uge.net.tcp.nonblocking.packet.PacketReader.ProcessFailure.CODE;
import static fr.uge.net.tcp.nonblocking.packet.PacketReader.ProcessFailure.LENGTH;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.*;
import static java.util.Objects.requireNonNull;

public class PacketReader implements Reader<Packet> {
    /**
     * Type of failure that can appear.
     */
    public enum ProcessFailure {CODE, LENGTH}
    // Always in write-mode.
    private final ByteBuffer buff = ByteBuffer.allocate(Integer.BYTES);
    private final StringReader reader = new StringReader();
    private ProcessFailure failure = null;
    private ProcessStatus status = REFILL;
    private PacketType type = null;
    private Packet packet = null;
    private String element = null;
    private byte errorCode = -1;
    private int token = -1;

    /**
     * Processes the buffer and extracts a byte (the type of the packet).
     * After that the process method will read different type and length of data
     * depending in the type of the packet.
     * This method remembers what has been processed beforehand.
     *
     * The returned value can be :
     * <ul>
     *   <li>   {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#REFILL} :
     *     if the reader has not finished.</li>
     *   <li>   {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#DONE} :
     *     if the message is ready to be get.</li>
     *   <li>   {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#ERROR} :
     *     <ul>
     *       <li> If the type is not recognized. (i.e. not between 0 and {@link PacketType#TOKEN} (5))</li>
     *       <li> If the type is {@link Packet.PacketType#ERR} and the code is not recognized. (i.e. not between 0 and {@link ErrorCode#ERROR_RECOVER} (5))</li>
     *       <li> If any length read is not between 1 and {@link fr.uge.net.tcp.nonblocking.utils.ChatOSUtils#TEXT_SIZE}.</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @param bb the buffer where to read the data.
     *           Must be in write-mode before being called and will be kept in write-mode after.
     * @return the current state of this reader.
     * @throws NullPointerException if {@code bb} is null.
     * @throws IllegalStateException if the reader has already finished reading data.
     * (i.e. already returned {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#DONE})
     */
    @Override
    public ProcessStatus process(ByteBuffer bb) {
        requireNonNull(bb);
        if (status != REFILL) throw new IllegalStateException();
        if (!bb.flip().hasRemaining()) { // Fail-fast if nothing to read
            bb.compact();
            return REFILL;
        }
        status = subProcess(bb.compact());
        return status;
    }

    /**
     *
     * @param bb the buffer where to read data. Must be in write-mode.
     * @return the current state of this reader.
     */
    private ProcessStatus subProcess(ByteBuffer bb) {
        var status = readType(bb);
        if (status != DONE) return status;
        return switch (type) {
            case ERR -> processError(bb);
            case AUTH -> makePacketOnDone(bb, reader::process, Packet.PacketBuilder::makeAuthenticationPacket);
            case GMSG -> makePacketOnDone(bb, this::processTwoString, s -> makeGeneralMessagePacket(element, s));
            case DMSG -> makePacketOnDone(bb, this::processTwoString, s -> makeDirectMessagePacket(element, s));
            case PC -> makePacketOnDone(bb, reader::process, Packet.PacketBuilder::makePrivateConnectionPacket);
            case TOKEN -> processToken(bb);
        };
    }

    /**
     * This method reads something only if {@link #type} is null.<br>
     * Reads the first byte of the buffer and store it in {@link #type}.
     *
     * @param bb the buffer where to read data. Must be in write-mode.
     * @return DONE if successful or ERROR if the read type is incorrect
     * (not between 0 and {@code PacketType.values().length}).
     */
    private ProcessStatus readType(ByteBuffer bb) {
        if (type == null) {
            var t = bb.flip().get();
            bb.compact();
            if (t < 0 || t >= PacketType.values().length) {
                failure = CODE;
                return ERROR;
            }
            type = PacketType.values()[t];
        }
        return DONE;
    }

    /**
     * Reads one byte for the error code and if the code is {@link Packet.ErrorCode#REJECTED}
     * read an int and a string.
     * The returned value can be:
     * <ul>
     *     <li> {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#DONE} :
     *     if the reader has finished reading.
     *     <li> {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#REFILL} :
     *     if {@code bb} is empty or if the code is {@link Packet.ErrorCode#REJECTED} and
     *     the int or the string cannot be read.
     *     <li> {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#ERROR} :
     *     if the error code read is invalid (sets {@link #failure} to {@link ProcessFailure#CODE}).
     *     Or if the length of the string is beyond limit (sets {@link #failure} to {@link ProcessFailure#LENGTH}).
     * </ul>
     *
     * @param bb buffer in write-mode.
     * @return the current status of the reader.
     * @see Packet.PacketBuilder#makeErrorPacket(byte)
     */
    private ProcessStatus processError(ByteBuffer bb) {
        if (errorCode == -1) {
            try {
                if (!bb.flip().hasRemaining()) return REFILL;
                errorCode = bb.get();
            } finally {
                bb.compact();
            }
        }
        if (errorCode == REJECTED.ordinal())
            return makePacketOnDone(bb, reader::process, Packet.PacketBuilder::makeRejectedPacket);
        if ((packet = makeErrorPacket(errorCode)) != null) return DONE;
        failure = CODE;
        return ERROR;
    }

    /**
     * Read the token (i.e. an integer) and after a pseudo.
     * The returned value can be:
     * <ul>
     *     <li> {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#DONE} :
     *     if the reader has finished reading.
     *     <li> {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#REFILL} :
     *     if {@code bb} has less than an integer or if the int or the string cannot be read.
     *     <li> {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#ERROR} :
     *     if the length of the string is beyond limit (sets {@link #failure} to {@link ProcessFailure#LENGTH}).
     * </ul>
     * @param bb buffer in write-mode.
     * @return the current status of the reader.
     */
    private ProcessStatus processToken(ByteBuffer bb) {
        if (token == -1) {
            try {
                if (moveData(bb.flip(), buff)) return REFILL;
            } finally {
                bb.compact();
            }
            token = buff.flip().getInt();
        }
        return makePacketOnDone(bb, reader::process, s -> makeTokenPacket(token, s));
    }

    /**
     * Sub method used to factorize every creation of a packet.
     * Processes the data from {@code bb} inside {@code processor}.<br>
     * If an error is returned : sets {@link #failure} to {@link ProcessFailure#LENGTH}.<br>
     * If the processing is completed : create a packet using {@code creator} and what's inside of {@link #reader}.
     * Stores the result inside {@link #packet}.
     *
     * @param processor a function that can processes a {@link ByteBuffer} in write-mode. Cannot be null.
     * @param creator a function that takes a String and creates a packet with it. Cannot be null.
     * @param bb buffer in write-mode.
     * @return the current status of the reader.
     */
    private ProcessStatus makePacketOnDone(ByteBuffer bb, Function<ByteBuffer, ProcessStatus> processor,
                                           Function<String, Packet> creator) {
        requireNonNull(processor);
        requireNonNull(creator);
        var status = processor.apply(bb);
        if (status == ERROR) failure = LENGTH;
        if (status == DONE) packet = creator.apply(reader.get());
        return status;
    }

    /**
     * Processes two string that are following each other using a {@link StringReader}.
     *
     * @param bb buffer in write-mode.
     * @return the current status of the reader.
     * @see StringReader
     */
    private ProcessStatus processTwoString(ByteBuffer bb) {
        if (element == null) {
            var status = reader.process(bb);
            if (status != DONE) return status;
            element = reader.get();
            reader.reset();
        }
        return reader.process(bb);
    }

    /**
     * @return the failure code.
     * @throws IllegalStateException if the reader hasn't failed.
     */
    public ProcessFailure getFailure() {
        if (status != ERROR) throw new IllegalStateException("Cannot get failure if not failed");
        return failure;
    }

    /**
     * @return the packet if the process method has successfully read a packet.
     * @throws IllegalStateException if the process method hasn't finished to read a packet.
     */
    @Override
    public Packet get() {
        if (status == DONE) return packet;
        throw new IllegalStateException("Cannot get a message if the process method haven't return ProcessStatus.DONE.");
    }

    /**
     * Resets the reader to its initial state.<br>
     * Note that the packet will not be accessible with {@link #get()} until {@link #process(ByteBuffer)}
     * have returned {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#DONE} once again.
     */
    @Override
    public void reset() {
        status = REFILL;
        failure = null;
        reader.reset();
        element = null;
        errorCode = -1;
        packet = null;
        buff.clear();
        type = null;
        token = -1;
    }

}
