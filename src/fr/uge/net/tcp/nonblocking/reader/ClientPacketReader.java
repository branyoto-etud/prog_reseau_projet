package fr.uge.net.tcp.nonblocking.reader;

import fr.uge.net.tcp.nonblocking.Packet;
import fr.uge.net.tcp.nonblocking.Packet.PacketType;

import java.nio.ByteBuffer;

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
                var ret = !bb.flip().hasRemaining() ? REFILL :                      // If the input has not at least 1 byte -> REFILL
                        (packet = makeErrorPacket(bb.get())) == null ? ERROR        // If the read byte are not valid -> ERROR
                                : DONE;                                             // Otherwise -> DONE
                bb.compact();                                                       // Go back in write mode
                return ret;
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
                if (moveData(bb.flip(), buff)) return REFILL;   // Tries to read an int
                bb.compact();                                   // Go back in write-mode

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
        buff.clear();
        reader.reset();
        status = REFILL;
        type = null;
        packet = null;
        element = null;
    }
}
