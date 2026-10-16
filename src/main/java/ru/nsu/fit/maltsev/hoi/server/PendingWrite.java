package ru.nsu.fit.maltsev.hoi.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

final class PendingWrite {
    private final ByteBuffer remaining;

    PendingWrite(ByteBuffer message) {
        remaining = message.asReadOnlyBuffer();
    }

    boolean writeTo(WritableByteChannel channel) throws IOException {
        channel.write(remaining);
        return !remaining.hasRemaining();
    }
}
