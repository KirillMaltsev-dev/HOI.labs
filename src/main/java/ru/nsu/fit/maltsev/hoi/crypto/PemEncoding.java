package ru.nsu.fit.maltsev.hoi.crypto;

import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

final class PemEncoding {
    private PemEncoding() {
    }

    static byte[] encode(String type, byte[] encoded) throws IOException {
        StringWriter output = new StringWriter();
        try (PemWriter writer = new PemWriter(output)) {
            writer.writeObject(new PemObject(type, encoded));
        }
        return output.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
