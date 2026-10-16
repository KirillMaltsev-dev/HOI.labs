package ru.nsu.fit.maltsev.hoi.client;

import org.bouncycastle.asn1.ASN1String;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import ru.nsu.fit.maltsev.hoi.crypto.RsaKeys;
import ru.nsu.fit.maltsev.hoi.protocol.ClientName;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;

final class CredentialValidator {
    private CredentialValidator() {
    }

    static void validate(ClientName name, ProtocolResponse.Success response)
            throws IOException, GeneralSecurityException {
        byte[] privateKeyDer = decodePem(response.privateKeyPem(), "PRIVATE KEY");
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(privateKeyDer));
        byte[] certificateDer = decodePem(response.certificatePem(), "CERTIFICATE");
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificateDer));
        if (!(privateKey instanceof RSAPrivateKey rsaPrivate)
                || !(certificate.getPublicKey() instanceof RSAPublicKey rsaPublic)
                || rsaPrivate.getModulus().bitLength() != RsaKeys.KEY_SIZE_BITS
                || rsaPublic.getModulus().bitLength() != RsaKeys.KEY_SIZE_BITS) {
            throw new InvalidKeyException("Expected RSA-8192 private and public keys");
        }
        if (!rsaPrivate.getModulus().equals(rsaPublic.getModulus())) {
            throw new InvalidKeyException("Private key does not match certificate public key");
        }
        RDN[] subject = X500Name.getInstance(certificate.getSubjectX500Principal().getEncoded()).getRDNs();
        if (subject.length != 1 || subject[0].size() != 1
                || !subject[0].getFirst().getType().equals(BCStyle.CN)
                || !(subject[0].getFirst().getValue() instanceof ASN1String commonName)
                || !name.value().equals(commonName.getString())) {
            throw new CertificateException("Certificate subject does not match requested client name");
        }
        certificate.checkValidity();
        byte[] challenge = "J1 credential validation".getBytes(StandardCharsets.US_ASCII);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(challenge);
        byte[] signed = signature.sign();
        signature.initVerify(certificate.getPublicKey());
        signature.update(challenge);
        if (!signature.verify(signed)) {
            throw new InvalidKeyException("Private key does not match certificate public key");
        }
    }

    private static byte[] decodePem(byte[] encoded, String expectedType) throws IOException {
        var ascii = StandardCharsets.US_ASCII.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (PemReader reader = new PemReader(new InputStreamReader(new ByteArrayInputStream(encoded), ascii))) {
            PemObject object = reader.readPemObject();
            if (object == null || !expectedType.equals(object.getType()) || reader.readPemObject() != null) {
                throw new IOException("Expected exactly one " + expectedType + " PEM object");
            }
            return object.getContent();
        }
    }
}
