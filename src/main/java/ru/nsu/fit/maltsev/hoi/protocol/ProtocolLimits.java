package ru.nsu.fit.maltsev.hoi.protocol;

public final class ProtocolLimits {
    public static final int MAX_NAME_BYTES = 255;
    public static final int MAX_PEM_BLOCK_BYTES = 65_536;
    public static final int MAX_ERROR_MESSAGE_BYTES = 1_024;

    private ProtocolLimits() {
    }
}
