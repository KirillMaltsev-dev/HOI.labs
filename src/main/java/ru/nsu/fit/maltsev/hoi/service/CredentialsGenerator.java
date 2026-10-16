package ru.nsu.fit.maltsev.hoi.service;

import ru.nsu.fit.maltsev.hoi.crypto.CertificateGenerator;
import ru.nsu.fit.maltsev.hoi.crypto.GeneratedCredentials;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolResponse;

import java.util.Objects;

@FunctionalInterface
public interface CredentialsGenerator {
    ProtocolResponse.Success generate(String clientName) throws Exception;

    static CredentialsGenerator from(CertificateGenerator generator) {
        Objects.requireNonNull(generator, "generator");
        return name -> {
            GeneratedCredentials credentials = generator.generate(name);
            return new ProtocolResponse.Success(credentials.privateKeyPem(), credentials.certificatePem());
        };
    }
}
