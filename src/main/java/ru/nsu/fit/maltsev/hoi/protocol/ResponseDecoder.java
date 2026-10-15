package ru.nsu.fit.maltsev.hoi.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

public final class ResponseDecoder {
    private enum State { STATUS, LENGTH, BODY, COMPLETE, FAILED }

    private State state = State.STATUS;
    private ResponseStatus status;
    private long length;
    private int lengthBytes;
    private byte[] body;
    private int bodyBytes;
    private byte[] privateKey;
    private ProtocolResponse result;

    public Optional<ProtocolResponse> feed(ByteBuffer input) throws ProtocolException {
        Objects.requireNonNull(input, "input");
        ensureReadable();
        try {
            while (input.hasRemaining() && state != State.COMPLETE) {
                switch (state) {
                    case STATUS -> {
                        status = ResponseStatus.fromWireCode(Byte.toUnsignedInt(input.get()));
                        state = State.LENGTH;
                    }
                    case LENGTH -> readLength(input);
                    case BODY -> readBody(input);
                    default -> throw new IllegalStateException("Unexpected decoder state");
                }
            }
            return Optional.ofNullable(result);
        } catch (ProtocolException exception) {
            state = State.FAILED;
            throw exception;
        }
    }

    private void readLength(ByteBuffer input) throws ProtocolException {
        length = (length << 8) | Byte.toUnsignedInt(input.get());
        lengthBytes++;
        if (lengthBytes != Integer.BYTES) {
            return;
        }
        int maximum = status == ResponseStatus.SUCCESS
                ? ProtocolLimits.MAX_PEM_BLOCK_BYTES : ProtocolLimits.MAX_ERROR_MESSAGE_BYTES;
        // unsigned uint32 сначала проверяется как long, затем преобразуется в int.
        if (length < 1 || length > maximum) {
            throw new ProtocolException("Invalid block length: " + length + "; maximum: " + maximum);
        }
        body = new byte[(int) length];
        bodyBytes = 0;
        state = State.BODY;
    }

    private void readBody(ByteBuffer input) throws ProtocolException {
        int count = Math.min(input.remaining(), body.length - bodyBytes);
        input.get(body, bodyBytes, count);
        bodyBytes += count;
        if (bodyBytes != body.length) {
            return;
        }
        if (status == ResponseStatus.SUCCESS && privateKey == null) {
            privateKey = body;
            body = null;
            length = 0;
            lengthBytes = 0;
            state = State.LENGTH;
            return;
        }
        if (status == ResponseStatus.SUCCESS) {
            result = new ProtocolResponse.Success(privateKey, body);
        } else {
            try {
                String message = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(body)).toString();
                result = new ProtocolResponse.Error(status, message);
            } catch (CharacterCodingException exception) {
                throw new ProtocolException("Error message is not valid UTF-8", exception);
            }
        }
        privateKey = null;
        body = null;
        state = State.COMPLETE;
    }

    public void endOfInput() throws ProtocolException {
        if (state == State.COMPLETE) {
            return;
        }
        ensureReadable();
        state = State.FAILED;
        throw new ProtocolException("Response ended before the complete message was received");
    }

    private void ensureReadable() {
        if (state == State.COMPLETE || state == State.FAILED) {
            throw new IllegalStateException("Decoder has already completed or failed");
        }
    }
}
