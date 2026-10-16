package ru.nsu.fit.maltsev.hoi.client;

import ru.nsu.fit.maltsev.hoi.protocol.ProtocolResponse;
import ru.nsu.fit.maltsev.hoi.protocol.ResponseStatus;

import java.io.IOException;

public final class ServerResponseException extends IOException {
    private final ResponseStatus status;

    public ServerResponseException(ProtocolResponse.Error response) {
        super("Server " + response.status() + ": " + response.message());
        status = response.status();
    }

    public ResponseStatus status() {
        return status;
    }
}
