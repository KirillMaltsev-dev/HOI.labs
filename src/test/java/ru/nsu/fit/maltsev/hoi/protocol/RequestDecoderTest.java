package ru.nsu.fit.maltsev.hoi.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RequestDecoderTest {
    @Test
    void decodesRequestOneByteAtATimeWithoutTrimmingSpaces() throws ProtocolException {
        var decoder = new RequestDecoder();
        for (byte value : new byte[]{32, 65, 110, 110, 32}) {
            assertTrue(decoder.feed(ByteBuffer.wrap(new byte[]{value})).isEmpty());
        }
        assertEquals(" Ann ", decoder.feed(ByteBuffer.wrap(new byte[]{0})).orElseThrow());
        assertDoesNotThrow(decoder::endOfInput);
        assertDoesNotThrow(decoder::endOfInput);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 255})
    void acceptsNameAtBothLimits(int length) throws ProtocolException {
        String name = "a".repeat(length);
        assertEquals(name, new RequestDecoder().feed(ProtocolCodec.encodeRequest(name)).orElseThrow());
    }

    @Test
    void acceptsEntirePrintableAsciiRange() throws ProtocolException {
        StringBuilder name = new StringBuilder();
        for (char value = 0x20; value <= 0x7E; value++) {
            name.append(value);
        }
        assertEquals(name.toString(), new RequestDecoder()
                .feed(ProtocolCodec.encodeRequest(name.toString())).orElseThrow());
    }

    @Test
    void rejectsEmptyName() {
        assertThrows(ProtocolException.class,
                () -> new RequestDecoder().feed(ByteBuffer.wrap(new byte[]{0})));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 9, 10, 31, 127, 128, 255})
    void rejectsNonPrintableOrNonAsciiBytes(int value) {
        assertThrows(ProtocolException.class,
                () -> new RequestDecoder().feed(ByteBuffer.wrap(new byte[]{(byte) value, 0})));
    }

    @Test
    void rejectsOversizedNameBeforeTerminatorArrives() throws ProtocolException {
        var decoder = new RequestDecoder();
        byte[] maximum = new byte[255];
        Arrays.fill(maximum, (byte) 65);
        assertTrue(decoder.feed(ByteBuffer.wrap(maximum)).isEmpty());
        assertThrows(ProtocolException.class, () -> decoder.feed(ByteBuffer.wrap(new byte[]{65})));
        assertThrows(IllegalStateException.class, () -> decoder.feed(ByteBuffer.wrap(new byte[]{0})));
    }

    @Test
    void rejectsEveryTruncatedPrefix() throws ProtocolException {
        byte[] message = {65, 110, 110, 0};
        for (int length = 0; length < message.length; length++) {
            var decoder = new RequestDecoder();
            assertTrue(decoder.feed(ByteBuffer.wrap(Arrays.copyOf(message, length))).isEmpty());
            assertThrows(ProtocolException.class, decoder::endOfInput, "prefix length: " + length);
        }
    }

    @Test
    void leavesTrailingBytesAndHonorsBufferPositionAndLimit() throws ProtocolException {
        ByteBuffer input = ByteBuffer.wrap(new byte[]{99, 65, 0, 66, 77}).asReadOnlyBuffer();
        input.position(1);
        input.limit(4);
        var decoder = new RequestDecoder();
        assertEquals("A", decoder.feed(input).orElseThrow());
        assertEquals(3, input.position());
        assertEquals(66, input.get());
        assertThrows(IllegalStateException.class, () -> decoder.feed(ByteBuffer.allocate(0)));
    }
}
