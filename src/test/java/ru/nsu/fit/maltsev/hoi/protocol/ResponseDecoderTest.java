package ru.nsu.fit.maltsev.hoi.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ResponseDecoderTest {
    private static final byte[] SUCCESS = {0, 0, 0, 0, 2, 65, 66, 0, 0, 0, 1, 67};
    private static final byte[] INVALID_REQUEST = {1, 0, 0, 0, 1, 69};
    private static final byte[] GENERATION_FAILED = {2, 0, 0, 0, 4,
            (byte) 0xD0, (byte) 0xBE, (byte) 0xD0, (byte) 0xB9};

    static Stream<Arguments> validResponses() {
        return Stream.of(
                Arguments.of(SUCCESS, new ProtocolResponse.Success(new byte[]{65, 66}, new byte[]{67})),
                Arguments.of(INVALID_REQUEST, new ProtocolResponse.Error(ResponseStatus.INVALID_REQUEST, "E")),
                Arguments.of(GENERATION_FAILED, new ProtocolResponse.Error(ResponseStatus.GENERATION_FAILED, "ой")));
    }

    @ParameterizedTest
    @MethodSource("validResponses")
    void decodesIndependentWireExamples(byte[] message, ProtocolResponse expected) throws ProtocolException {
        var decoder = new ResponseDecoder();
        // Порядок самого ByteBuffer не должен переопределять сетевой порядок.
        ByteBuffer input = ByteBuffer.wrap(message).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(expected, decoder.feed(input).orElseThrow());
        assertFalse(input.hasRemaining());
        assertDoesNotThrow(decoder::endOfInput);
        assertDoesNotThrow(decoder::endOfInput);
    }

    @ParameterizedTest
    @MethodSource("validResponses")
    void decodesEveryPossibleTwoFragmentSplit(byte[] message, ProtocolResponse expected)
            throws ProtocolException {
        for (int split = 0; split < message.length; split++) {
            var decoder = new ResponseDecoder();
            assertTrue(decoder.feed(ByteBuffer.wrap(message, 0, split)).isEmpty());
            assertEquals(expected, decoder.feed(ByteBuffer.wrap(message, split, message.length - split))
                    .orElseThrow(), "split: " + split);
        }
    }

    @ParameterizedTest
    @MethodSource("validResponses")
    void decodesOneByteAtATime(byte[] message, ProtocolResponse expected) throws ProtocolException {
        var decoder = new ResponseDecoder();
        for (int i = 0; i < message.length; i++) {
            Optional<ProtocolResponse> result = decoder.feed(ByteBuffer.wrap(new byte[]{message[i]}));
            if (i + 1 == message.length) {
                assertEquals(expected, result.orElseThrow());
            } else {
                assertTrue(result.isEmpty());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("validResponses")
    void rejectsEveryTruncatedPrefix(byte[] message, ProtocolResponse unused) throws ProtocolException {
        for (int length = 0; length < message.length; length++) {
            var decoder = new ResponseDecoder();
            assertTrue(decoder.feed(ByteBuffer.wrap(Arrays.copyOf(message, length))).isEmpty());
            assertThrows(ProtocolException.class, decoder::endOfInput, "prefix length: " + length);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 127, 128, 255})
    void rejectsUnknownStatusImmediately(int status) {
        assertThrows(ProtocolException.class,
                () -> new ResponseDecoder().feed(ByteBuffer.wrap(new byte[]{(byte) status})));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 65_537, Integer.MAX_VALUE, Integer.MIN_VALUE, -1})
    void rejectsInvalidKeyLengthBeforeReadingBody(int length) {
        assertThrows(ProtocolException.class, () -> new ResponseDecoder().feed(header(0, length)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 65_537, Integer.MAX_VALUE, Integer.MIN_VALUE, -1})
    void rejectsInvalidCertificateLengthBeforeReadingBody(int length) {
        ByteBuffer input = ByteBuffer.allocate(10).put((byte) 0).putInt(1).put((byte) 65).putInt(length).flip();
        assertThrows(ProtocolException.class, () -> new ResponseDecoder().feed(input));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1025, Integer.MAX_VALUE, Integer.MIN_VALUE, -1})
    void rejectsInvalidErrorLengthForBothErrorStatuses(int length) {
        for (int status : new int[]{1, 2}) {
            assertThrows(ProtocolException.class, () -> new ResponseDecoder().feed(header(status, length)));
        }
    }

    static Stream<byte[]> malformedUtf8() {
        return Stream.of(new byte[]{(byte) 0x80}, new byte[]{(byte) 0xD0},
                new byte[]{(byte) 0xC0, (byte) 0xAF},
                new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80});
    }

    @ParameterizedTest
    @MethodSource("malformedUtf8")
    void rejectsMalformedUtf8InsteadOfReplacingIt(byte[] body) {
        ByteBuffer input = ByteBuffer.allocate(5 + body.length)
                .put((byte) 1).putInt(body.length).put(body).flip();
        assertThrows(ProtocolException.class, () -> new ResponseDecoder().feed(input));
    }

    @Test
    void leavesTrailingBytesAndHonorsDirectReadOnlyBufferBounds() throws ProtocolException {
        ByteBuffer input = ByteBuffer.allocateDirect(SUCCESS.length + 3)
                .put((byte) 99).put(SUCCESS).put((byte) 42).put((byte) 77).flip().asReadOnlyBuffer();
        input.position(1);
        input.limit(SUCCESS.length + 2);
        var decoder = new ResponseDecoder();
        assertEquals(new ProtocolResponse.Success(new byte[]{65, 66}, new byte[]{67}),
                decoder.feed(input).orElseThrow());
        assertEquals(SUCCESS.length + 1, input.position());
        assertEquals(42, input.get());
        assertThrows(IllegalStateException.class, () -> decoder.feed(ByteBuffer.allocate(0)));
    }

    @Test
    void failedDecoderCannotBeReused() {
        var decoder = new ResponseDecoder();
        assertThrows(ProtocolException.class, () -> decoder.feed(ByteBuffer.wrap(new byte[]{3})));
        assertThrows(IllegalStateException.class, () -> decoder.feed(ByteBuffer.wrap(SUCCESS)));
        assertThrows(IllegalStateException.class, decoder::endOfInput);
    }

    private static ByteBuffer header(int status, int length) {
        return ByteBuffer.allocate(5).put((byte) status).putInt(length).flip();
    }
}
