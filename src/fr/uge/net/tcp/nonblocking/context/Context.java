package fr.uge.net.tcp.nonblocking.context;

import java.io.IOException;

/**
 * Mandatory methods to be an attachement of a SelectionKey.
 */
public interface Context {
    void doWrite() throws IOException;
    void doRead() throws IOException;
    void doConnect() throws IOException;
}
