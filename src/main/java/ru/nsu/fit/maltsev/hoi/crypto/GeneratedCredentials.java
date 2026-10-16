package ru.nsu.fit.maltsev.hoi.crypto;

import java.io.IOException;
import java.security.KeyPair;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Objects;

public record GeneratedCredentials(KeyPair keyPair, X509Certificate certificate) {
    public GeneratedCredentials {
        Objects.requireNonNull(keyPair, "keyPair");
        Objects.requireNonNull(certificate, "certificate");
    }

    public byte[] privateKeyPem() throws IOException {
        return PemEncoding.encode("PRIVATE KEY", keyPair.getPrivate().getEncoded());
    }

    public byte[] certificatePem() throws IOException, CertificateEncodingException {
        return PemEncoding.encode("CERTIFICATE", certificate.getEncoded());
    }

    @Override
    public String toString() {
        return "GeneratedCredentials[serial=" + certificate.getSerialNumber() + "]";
    }
}
