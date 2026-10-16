package ru.nsu.fit.maltsev.hoi.client;

import ru.nsu.fit.maltsev.hoi.protocol.ClientName;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

public final class CredentialsFiles {
    private CredentialsFiles() {
    }

    public static SavedPair save(Path directory, String clientName, ProtocolResponse.Success response)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(response, "response");
        ClientName name = new ClientName(clientName);
        CredentialValidator.validate(name, response);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(name.value().getBytes(StandardCharsets.US_ASCII));
        String base = "client-" + HexFormat.of().formatHex(digest);
        Path output = directory.toAbsolutePath().normalize();
        Files.createDirectories(output);
        Path key = output.resolve(base + ".key");
        Path certificate = output.resolve(base + ".crt");
        boolean keyCreated = false;
        boolean certificateCreated = false;
        try {
            try (OutputStream stream = Files.newOutputStream(key,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                keyCreated = true;
                stream.write(response.privateKeyPem());
            }
            try (OutputStream stream = Files.newOutputStream(certificate,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                certificateCreated = true;
                stream.write(response.certificatePem());
            }
            return new SavedPair(key, certificate);
        } catch (IOException | RuntimeException failure) {
            if (certificateCreated) {
                deleteCreated(certificate, failure);
            }
            if (keyCreated) {
                deleteCreated(key, failure);
            }
            throw failure;
        }
    }

    private static void deleteCreated(Path path, Exception failure) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    public record SavedPair(Path privateKey, Path certificate) {
    }
}
