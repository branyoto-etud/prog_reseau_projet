package fr.uge.net.tcp.nonblocking.reader;

import java.nio.ByteBuffer;

public class Main {
    public static void main(String[] args) {
        StringReader reader = new StringReader();
        ByteBuffer buff = ByteBuffer.allocate(24).put((byte) 0).put((byte) 0).put((byte) 0);

        var extracted = reader.process(buff);

        buff
          .put((byte) 2)
          .put((byte) 97)
          .put((byte) 97)
        ;

        extracted = reader.process(buff);

        System.out.println(extracted);
        System.out.println(reader.get());
    }
}
