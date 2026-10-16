package ru.nsu.fit.maltsev.hoi.server;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

import static org.junit.jupiter.api.Assertions.*;

class PendingWriteTest {
    @Test
    void keepsPositionAcrossPartialAndZeroWrites() throws IOException {
        ByteBuffer message = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5});
        PendingWrite pending = new PendingWrite(message);
        var channel = new LimitedChannel();
        assertFalse(pending.writeTo(channel));
        assertEquals(0, channel.output.size());
        channel.allowance = 2;
        assertFalse(pending.writeTo(channel));
        assertArrayEquals(new byte[]{1, 2}, channel.output.toByteArray());
        channel.allowance = 0;
        assertFalse(pending.writeTo(channel));
        assertEquals(2, channel.output.size());
        channel.allowance = 2;
        assertFalse(pending.writeTo(channel));
        assertTrue(pending.writeTo(channel));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, channel.output.toByteArray());
        assertEquals(0, message.position());
    }

    @Test
    void connectionsHaveIndependentPositionsOverSharedMessage() throws IOException {
        ByteBuffer shared = ByteBuffer.wrap(new byte[]{1, 2, 3, 4}).asReadOnlyBuffer();
        PendingWrite slow = new PendingWrite(shared);
        PendingWrite fast = new PendingWrite(shared);
        var slowChannel = new LimitedChannel();
        var fastChannel = new LimitedChannel();
        slowChannel.allowance = 1;
        fastChannel.allowance = 10;
        assertFalse(slow.writeTo(slowChannel));
        assertTrue(fast.writeTo(fastChannel));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, fastChannel.output.toByteArray());
        slowChannel.allowance = 10;
        assertTrue(slow.writeTo(slowChannel));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, slowChannel.output.toByteArray());
        assertEquals(0, shared.position());
    }

    @Test
    void propagatesWriteFailure() {
        var pending = new PendingWrite(ByteBuffer.wrap(new byte[]{1}));
        WritableByteChannel broken = new WritableByteChannel() {
            @Override
            public int write(ByteBuffer source) throws IOException {
                throw new IOException("connection lost");
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {
            }
        };
        assertThrows(IOException.class, () -> pending.writeTo(broken));
    }

    private static final class LimitedChannel implements WritableByteChannel {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int allowance;

        @Override
        public int write(ByteBuffer source) {
            int count = Math.min(allowance, source.remaining());
            for (int i = 0; i < count; i++) {
                output.write(source.get());
            }
            return count;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }
    }
}
