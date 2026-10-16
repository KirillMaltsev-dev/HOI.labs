package ru.nsu.fit.maltsev.hoi.client;

import ru.nsu.fit.maltsev.hoi.protocol.ProtocolCodec;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolException;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolResponse;
import ru.nsu.fit.maltsev.hoi.protocol.ResponseDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class KeyClient {
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private final ReadDelay delay;

    public KeyClient() {
        this(seconds -> TimeUnit.SECONDS.sleep(seconds));
    }

    KeyClient(ReadDelay delay) {
        this.delay = Objects.requireNonNull(delay, "delay");
    }

    public Optional<ProtocolResponse.Success> fetch(ClientConfig config) throws IOException, InterruptedException {
        Objects.requireNonNull(config, "config");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(config.host(), config.port()), CONNECT_TIMEOUT_MILLIS);
            socket.getOutputStream().write(ProtocolCodec.encodeRequest(config.name()).array());
            socket.getOutputStream().flush();
            if (config.disconnectWithoutRead()) {
                return Optional.empty();
            }
            delay.pause(config.readDelaySeconds());
            ProtocolResponse response = readResponse(socket.getInputStream());
            if (response instanceof ProtocolResponse.Error error) {
                throw new ServerResponseException(error);
            }
            return Optional.of((ProtocolResponse.Success) response);
        }
    }

    private static ProtocolResponse readResponse(InputStream input) throws IOException {
        ResponseDecoder decoder = new ResponseDecoder();
        ProtocolResponse response = null;
        byte[] chunk = new byte[4096];
        int count;
        while ((count = input.read(chunk)) != -1) {
            if (response != null) {
                throw new ProtocolException("Unexpected bytes after response");
            }
            ByteBuffer buffer = ByteBuffer.wrap(chunk, 0, count);
            response = decoder.feed(buffer).orElse(null);
            if (buffer.hasRemaining()) {
                throw new ProtocolException("Unexpected bytes after response");
            }
        }
        decoder.endOfInput();
        return response;
    }

    @FunctionalInterface
    interface ReadDelay {
        void pause(int seconds) throws InterruptedException;
    }
}
