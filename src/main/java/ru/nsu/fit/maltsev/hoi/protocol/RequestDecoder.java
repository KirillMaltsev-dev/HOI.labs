package ru.nsu.fit.maltsev.hoi.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

public final class RequestDecoder {
    private final ByteArrayOutputStream name = new ByteArrayOutputStream();
    private boolean complete;
    private boolean failed;

    public Optional<String> feed(ByteBuffer input) throws ProtocolException {
        Objects.requireNonNull(input, "input");
        ensureReadable();
        try {
            while (input.hasRemaining()) {
                int value = Byte.toUnsignedInt(input.get());
                if (value == 0) {
                    if (name.size() == 0) {
                        throw new ProtocolException("Empty client name");
                    }
                    complete = true;
                    return Optional.of(name.toString(StandardCharsets.US_ASCII));
                }
                if (value < 0x20 || value > 0x7E) {
                    throw new ProtocolException("Name must contain printable ASCII only");
                }
                if (name.size() == ProtocolLimits.MAX_NAME_BYTES) {
                    throw new ProtocolException("Client name exceeds byte limit");
                }
                name.write(value);
            }
            return Optional.empty();
        } catch (ProtocolException exception) {
            failed = true;
            throw exception;
        }
    }

    public void endOfInput() throws ProtocolException {
        if (complete) {
            return;
        }
        ensureReadable();
        failed = true;
        throw new ProtocolException("Request ended before the zero terminator");
    }

    private void ensureReadable() {
        if (complete || failed) {
            throw new IllegalStateException("Decoder has already completed or failed");
        }
    }
}
