package ru.nsu.fit.maltsev.hoi.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolCodecTest {
    @Test
    void encodesRequestWithTerminatorAndPreservesSpaces() {
        ByteBuffer encoded = ProtocolCodec.encodeRequest(" Ann ");
        assertEquals(0, encoded.position());
        assertArrayEquals(new byte[]{32, 65, 110, 110, 32, 0}, bytes(encoded));
    }

    @Test
    void encodesSuccessInSpecifiedOrder() {
        ByteBuffer encoded = ProtocolCodec.encodeResponse(new ProtocolResponse.Success(
                new byte[]{65, 66}, new byte[]{67}));
        assertEquals(0, encoded.position());
        assertEquals(ByteOrder.BIG_ENDIAN, encoded.order());
        assertArrayEquals(new byte[]{0, 0, 0, 0, 2, 65, 66, 0, 0, 0, 1, 67}, bytes(encoded));
    }

    @ParameterizedTest
    @EnumSource(value = ResponseStatus.class, names = {"INVALID_REQUEST", "GENERATION_FAILED"})
    void encodesErrorWithUtf8ByteLength(ResponseStatus status) {
        ByteBuffer encoded = ProtocolCodec.encodeResponse(new ProtocolResponse.Error(status, "ой"));
        assertArrayEquals(new byte[]{(byte) status.wireCode(), 0, 0, 0, 4,
                (byte) 0xD0, (byte) 0xBE, (byte) 0xD0, (byte) 0xB9}, bytes(encoded));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a\u0000b", "a\nb", "\u001F", "\u007F", "я", "😀"})
    void rejectsInvalidOutgoingName(String name) {
        assertThrows(IllegalArgumentException.class, () -> ProtocolCodec.encodeRequest(name));
    }

    @Test
    void rejectsNameAboveLimit() {
        assertThrows(IllegalArgumentException.class, () -> ProtocolCodec.encodeRequest("a".repeat(256)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 65_537})
    void rejectsInvalidOutgoingPemLength(int length) {
        assertThrows(IllegalArgumentException.class,
                () -> new ProtocolResponse.Success(new byte[length], new byte[]{1}));
        assertThrows(IllegalArgumentException.class,
                () -> new ProtocolResponse.Success(new byte[]{1}, new byte[length]));
    }

    @Test
    void rejectsSuccessStatusInErrorResponse() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProtocolResponse.Error(ResponseStatus.SUCCESS, "error"));
    }

    @Test
    void rejectsEmptyOversizedAndMalformedOutgoingErrorText() {
        for (String message : new String[]{"", "a".repeat(1025), "я".repeat(513), "\uD800", "\uDC00"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ProtocolResponse.Error(ResponseStatus.INVALID_REQUEST, message));
        }
    }

    @Test
    void acceptsErrorAtUtf8ByteLimit() throws ProtocolException {
        for (String message : new String[]{"a".repeat(1024), "я".repeat(512), "😀".repeat(256)}) {
            var response = new ProtocolResponse.Error(ResponseStatus.GENERATION_FAILED, message);
            ByteBuffer encoded = ProtocolCodec.encodeResponse(response);
            assertEquals(1024, encoded.getInt(1));
            assertEquals(response, new ResponseDecoder().feed(encoded).orElseThrow());
        }
    }

    @Test
    void successOwnsItsArraysAndHasValueEquality() {
        byte[] key = {65, 66};
        byte[] certificate = {67};
        var response = new ProtocolResponse.Success(key, certificate);
        key[0] = 0;
        certificate[0] = 0;
        response.privateKeyPem()[0] = 0;
        response.certificatePem()[0] = 0;
        var expected = new ProtocolResponse.Success(new byte[]{65, 66}, new byte[]{67});
        assertEquals(expected, response);
        assertEquals(expected.hashCode(), response.hashCode());
        assertArrayEquals(new byte[]{65, 66}, response.privateKeyPem());
        assertArrayEquals(new byte[]{67}, response.certificatePem());
        assertEquals("Success[privateKeyBytes=2, certificateBytes=1]", response.toString());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65_536})
    void roundTripsSuccessAtBothBlockLimits(int length) throws ProtocolException {
        byte[] key = new byte[length];
        byte[] certificate = new byte[length];
        Arrays.fill(key, (byte) 65);
        Arrays.fill(certificate, (byte) 66);
        var expected = new ProtocolResponse.Success(key, certificate);
        var decoder = new ResponseDecoder();
        assertEquals(expected, decoder.feed(ProtocolCodec.encodeResponse(expected)).orElseThrow());
        assertDoesNotThrow(decoder::endOfInput);
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
}
