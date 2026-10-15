package ru.nsu.fit.maltsev.hoi.protocol;

public enum ResponseStatus {
    SUCCESS(0),
    INVALID_REQUEST(1),
    GENERATION_FAILED(2);

    private final int wireCode;

    ResponseStatus(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static ResponseStatus fromWireCode(int code) throws ProtocolException {
        return switch (code) {
            case 0 -> SUCCESS;
            case 1 -> INVALID_REQUEST;
            case 2 -> GENERATION_FAILED;
            default -> throw new ProtocolException("Unknown response status: " + code);
        };
    }
}
