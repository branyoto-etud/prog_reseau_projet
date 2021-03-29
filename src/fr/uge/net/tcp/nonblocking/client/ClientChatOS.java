package fr.uge.net.tcp.nonblocking.client;

import fr.uge.net.tcp.nonblocking.Packet;
import fr.uge.net.tcp.nonblocking.reader.ClientPacketReader;
import fr.uge.net.tcp.nonblocking.reader.Reader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Logger;

import static fr.uge.net.tcp.nonblocking.Config.BUFFER_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.Packet.PacketBuilder.makeAuthenticationPacket;
import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.*;

public class ClientChatOS {
}
