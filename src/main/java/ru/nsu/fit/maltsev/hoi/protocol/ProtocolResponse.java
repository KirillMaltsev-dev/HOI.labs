package ru.nsu.fit.maltsev.hoi.protocol;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Неизменяемые ответы. Содержимое PEM на этом этапе не проверяется. */
public sealed interface ProtocolResponse permits ProtocolResponse.Success, ProtocolResponse.Error {
    ResponseStatus status();

    record Success(byte[] privateKeyPem, byte[] certificatePem) implements ProtocolResponse {
        public Success {
            privateKeyPem = checkedCopy(privateKeyPem);
            certificatePem = checkedCopy(certificatePem);
        }

        private static byte[] checkedCopy(byte[] bytes) {
            Objects.requireNonNull(bytes, "PEM block");
            if (bytes.length < 1 || bytes.length > ProtocolLimits.MAX_PEM_BLOCK_BYTES) {
                throw new IllegalArgumentException("PEM block length must be 1.."
                        + ProtocolLimits.MAX_PEM_BLOCK_BYTES);
            }
            return bytes.clone();
        }

        @Override
        public ResponseStatus status() {
            return ResponseStatus.SUCCESS;
        }

        @Override
        public byte[] privateKeyPem() {
            return privateKeyPem.clone();
        }

        @Override
        public byte[] certificatePem() {
            return certificatePem.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Success success
                    && Arrays.equals(privateKeyPem, success.privateKeyPem)
                    && Arrays.equals(certificatePem, success.certificatePem);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(privateKeyPem) + Arrays.hashCode(certificatePem);
        }

        @Override
        public String toString() {
            return "Success[privateKeyBytes=" + privateKeyPem.length
                    + ", certificateBytes=" + certificatePem.length + "]";
        }
    }

    record Error(ResponseStatus status, String message) implements ProtocolResponse {
        public Error {
            Objects.requireNonNull(status, "status");
            if (status == ResponseStatus.SUCCESS) {
                throw new IllegalArgumentException("An error response must have an error status");
            }
            checkedMessageBytes(message);
        }

        byte[] messageBytes() {
            return checkedMessageBytes(message);
        }

        private static byte[] checkedMessageBytes(String message) {
            Objects.requireNonNull(message, "message");
            // Даже до кодирования исключаем заведомо слишком длинную строку.
            if (message.isEmpty() || message.length() > ProtocolLimits.MAX_ERROR_MESSAGE_BYTES) {
                throw new IllegalArgumentException("Invalid error message length");
            }
            try {
                ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(CharBuffer.wrap(message));
                if (encoded.remaining() > ProtocolLimits.MAX_ERROR_MESSAGE_BYTES) {
                    throw new IllegalArgumentException("Error message exceeds UTF-8 byte limit");
                }
                byte[] bytes = new byte[encoded.remaining()];
                encoded.get(bytes);
                return bytes;
            } catch (CharacterCodingException exception) {
                throw new IllegalArgumentException("Error message is not valid Unicode", exception);
            }
        }
    }
}
