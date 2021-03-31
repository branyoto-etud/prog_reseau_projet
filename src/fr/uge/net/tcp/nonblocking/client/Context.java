package fr.uge.net.tcp.nonblocking.client;

import java.io.IOException;

public interface Context {
    void queueMessage(String message);
    void doWrite() throws IOException;
    void doRead() throws IOException;
    void doConnect() throws IOException;
}
