package ru.nsu.fit.maltsev.hoi.protocol;

import java.util.Objects;

public record ClientName(String value) {
    public ClientName {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || value.length() > ProtocolLimits.MAX_NAME_BYTES) {
            throw new IllegalArgumentException("Name length must be 1.." + ProtocolLimits.MAX_NAME_BYTES);
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 0x20 || value.charAt(i) > 0x7E) {
                throw new IllegalArgumentException("Name must contain printable ASCII only");
            }
        }
    }
}
