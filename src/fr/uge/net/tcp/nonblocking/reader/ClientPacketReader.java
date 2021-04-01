package fr.uge.net.tcp.nonblocking.reader;

import fr.uge.net.tcp.nonblocking.Packet;
import fr.uge.net.tcp.nonblocking.Packet.PacketType;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.Packet.ErrorCode.REJECTED;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.*;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.*;
import static fr.uge.net.tcp.nonblocking.reader.Reader.moveData;
import static java.util.Objects.requireNonNull;

public class ClientPacketReader implements Reader<Packet> {
    private final ByteBuffer buff = ByteBuffer.allocate(Integer.BYTES); // Used to read a TOKEN
    private final StringReader reader = new StringReader();
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
     *       <li> If the type is not recognized.</li> <!--LATEST replace with the correct range-->
     *       <li> If the type is {@link fr.uge.net.tcp.nonblocking.Packet.PacketType#ERR} and the code is not recognized.</li> <!--LATEST replace with the correct range-->
     *       <li> If any length read is not between 1 and {@link fr.uge.net.tcp.nonblocking.Config#TEXT_SIZE}.</li>
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
        if (!bb.flip().hasRemaining()) {
            bb.compact();
            return REFILL;
        }
        status = subProcess(bb.compact());
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
                return processError(bb);
            }
            case AUTH -> {
                var status = reader.process(bb);                                        // Tries to read a string
                if (status == DONE) packet = makeAuthenticationPacket(reader.get());    // If success create the packet
                return status;                                                          // Returns the status
            }
            case GMSG -> {
                var status = processTwoString(bb);                                              // Tries to read two strings
                if (status == DONE) packet = makeGeneralMessagePacket(element, reader.get());   // If success create the packet
                return status;                                                                  // Returns the status
            }
            case DMSG -> {
                var status = processTwoString(bb);                                              // Tries to read two strings
                if (status == DONE) packet = makeDirectMessagePacket(element, reader.get());    // If success create the packet
                return status;                                                                  // Returns the status
            }
            case CP -> {
                var status = reader.process(bb);                                            // Tries to read two strings
                if (status == DONE) packet = makePrivateConnectionPacket(reader.get());     // If success create the packet
                return status;                                                              // Returns the status
            }
            case TOKEN -> {
                if (token == -1) {
                    if (moveData(bb.flip(), buff)) {    // Tries to read an int
                        bb.compact();                   // Back to write-mode
                        return REFILL;                  // Need REFILL
                    }
                    bb.compact();                       // Back to write-mode
                    token = buff.flip().getInt();       // Get int
                }
                var status = reader.process(bb);        // Tries read String
                if (status == DONE)                     // Success -> create packet
                    packet = makeTokenPacket(token, reader.get());
                return status;                          // All case -> return status
            }
        }
        return ERROR; // Shouldn't happen
    }

    private ProcessStatus processTwoString(ByteBuffer bb) {
        if (element == null) {                  // If this is the first string to be read
            var status = reader.process(bb);    // Tries to read it
            if (status != DONE) return status;  // If not successful return the status
            element = reader.get();             // Otherwise take the element
            reader.reset();                     // And reset the reader
        }
        return reader.process(bb);              // If this is the second string only return the process status.
    }

    /**
     * @param bb buffer in write-mode.
     * @return the current status of the reader.
     */
    private ProcessStatus processError(ByteBuffer bb) {
        if (errorCode == -1) {
            if (!bb.flip().hasRemaining()) {
                bb.compact();
                return REFILL;
            }
            errorCode = bb.get();
            bb.compact();
        }
        if (errorCode != REJECTED.ordinal()) {
            return (packet = makeErrorPacket(errorCode)) == null ? ERROR : DONE;
        }

        // If the code is REJECTED
        var status = reader.process(bb);        // Tries to read a string
        if (status != DONE) return status;      // If not successful return the status
        element = reader.get();                 // Otherwise take the element
        packet = makeRejectedPacket(element);   // Creates a REJECTED packet
        return DONE;
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
        reader.reset();
        element = null;
        errorCode = -1;
        packet = null;
        buff.clear();
        type = null;
        token = -1;
    }
}
