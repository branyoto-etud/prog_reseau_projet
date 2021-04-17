package fr.uge.net.tcp.nonblocking.context;

import java.io.IOException;

/**
 * Mandatory methods to be an attachement of a SelectionKey.
 */
public interface Context {
    /**
     * Writes datas.
     * @throws IOException if an I/O error occurs.
     */
    void doWrite() throws IOException;

    /**
     * Reads datas.
     * @throws IOException if an I/O error occurs.
     */
    void doRead() throws IOException;
    /**
     * Connects to a server.
     * @throws IOException if an I/O error occurs.
     */
    void doConnect() throws IOException;
}
