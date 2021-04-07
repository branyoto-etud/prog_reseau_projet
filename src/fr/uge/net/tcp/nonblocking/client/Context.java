package fr.uge.net.tcp.nonblocking.client;

import java.io.IOException;

/**
 * Mandatory methods to be an attachement of a SelectionKey.
 */
public interface Context { // Todo : Add default methods
    void doWrite() throws IOException;
    void doRead() throws IOException;
    void doConnect() throws IOException;
}
