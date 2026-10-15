package ru.nsu.fit.maltsev.hoi.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class ProtocolCodec {
    private ProtocolCodec() {
    }

    public static ByteBuffer encodeRequest(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty() || name.length() > ProtocolLimits.MAX_NAME_BYTES) {
            throw new IllegalArgumentException("Name length must be 1.." + ProtocolLimits.MAX_NAME_BYTES);
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < 0x20 || name.charAt(i) > 0x7E) {
                throw new IllegalArgumentException("Name must contain printable ASCII only");
            }
        }
        ByteBuffer result = ByteBuffer.allocate(name.length() + 1);
        result.put(name.getBytes(StandardCharsets.US_ASCII)).put((byte) 0);
        return result.flip();
    }

    public static ByteBuffer encodeResponse(ProtocolResponse response) {
        Objects.requireNonNull(response, "response");
        return switch (response) {
            case ProtocolResponse.Success success -> {
                byte[] key = success.privateKeyPem();
                byte[] certificate = success.certificatePem();
                ByteBuffer result = ByteBuffer.allocate(1 + 2 * Integer.BYTES + key.length + certificate.length)
                        .order(ByteOrder.BIG_ENDIAN);
                result.put((byte) success.status().wireCode());
                result.putInt(key.length).put(key);
                result.putInt(certificate.length).put(certificate);
                yield result.flip();
            }
            case ProtocolResponse.Error error -> {
                byte[] message = error.messageBytes();
                ByteBuffer result = ByteBuffer.allocate(1 + Integer.BYTES + message.length)
                        .order(ByteOrder.BIG_ENDIAN);
                result.put((byte) error.status().wireCode());
                result.putInt(message.length).put(message);
                yield result.flip();
            }
        };
    }
}
