package ru.nsu.fit.maltsev.hoi.crypto;

import org.bouncycastle.asn1.ASN1String;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolCodec;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolLimits;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolResponse;
import ru.nsu.fit.maltsev.hoi.protocol.ResponseDecoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class CryptoTest {
    private static final String ISSUER = "CN=J1 Test CA,O=NSU";
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final String NAME_PREFIX = "#Ann, CN=Injected+\\\"<>;= ";
    private static final String NAME = NAME_PREFIX + "x".repeat(254 - NAME_PREFIX.length()) + " ";

    @TempDir
    static Path directory;

    private static Path signingKeyPath;
    private static PrivateKey signingKey;
    private static PublicKey signingPublicKey;
    private static CertificateGenerator generator;
    private static GeneratedCredentials credentials;

    @BeforeAll
    static void prepareCredentials() throws Exception {
        signingKeyPath = directory.resolve("keys/signing.key");
        SigningKeyTool.main(new String[]{"--output", signingKeyPath.toString()});
        signingKey = RsaKeys.loadPrivateKey(signingKeyPath);
        RSAPrivateCrtKey rsaKey = assertInstanceOf(RSAPrivateCrtKey.class, signingKey);
        signingPublicKey = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(rsaKey.getModulus(), rsaKey.getPublicExponent()));
        generator = new CertificateGenerator(signingKey, ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));
        credentials = generator.generate(NAME);
    }

    @Test
    void signingAndClientKeysAre8192BitsAndDifferent() {
        RSAPrivateKey signing = assertInstanceOf(RSAPrivateKey.class, signingKey);
        RSAPrivateKey clientPrivate = assertInstanceOf(RSAPrivateKey.class, credentials.keyPair().getPrivate());
        RSAPublicKey clientPublic = assertInstanceOf(RSAPublicKey.class, credentials.keyPair().getPublic());
        assertEquals(8192, signing.getModulus().bitLength());
        assertEquals(8192, clientPrivate.getModulus().bitLength());
        assertEquals(8192, clientPublic.getModulus().bitLength());
        assertEquals(clientPublic.getModulus(), clientPrivate.getModulus());
        assertNotEquals(signing.getModulus(), clientPrivate.getModulus());
    }

    @Test
    void signingKeyFileIsPkcs8AndCanBeReloaded() throws Exception {
        String pem = Files.readString(signingKeyPath, StandardCharsets.US_ASCII);
        assertTrue(pem.startsWith("-----BEGIN PRIVATE KEY-----"));
        assertTrue(pem.stripTrailing().endsWith("-----END PRIVATE KEY-----"));
        assertArrayEquals(signingKey.getEncoded(), RsaKeys.loadPrivateKey(signingKeyPath).getEncoded());
    }

    @Test
    void certificateHasConfiguredIssuerAndSingleLiteralCommonName() {
        X509Certificate certificate = credentials.certificate();
        assertEquals(3, certificate.getVersion());
        assertEquals(new X500Name(ISSUER), X500Name.getInstance(certificate.getIssuerX500Principal().getEncoded()));
        X500Name subject = X500Name.getInstance(certificate.getSubjectX500Principal().getEncoded());
        assertEquals(1, subject.getRDNs().length);
        assertEquals(1, subject.getRDNs(BCStyle.CN).length);
        assertEquals(NAME, ((ASN1String) subject.getRDNs(BCStyle.CN)[0].getFirst().getValue()).getString());
        assertEquals(255, NAME.length());
        assertArrayEquals(credentials.keyPair().getPublic().getEncoded(), certificate.getPublicKey().getEncoded());
        assertTrue(certificate.getSerialNumber().signum() > 0);
        assertTrue(certificate.getSerialNumber().bitLength() <= 159);
        assertEquals(-1, certificate.getBasicConstraints());
        assertTrue(certificate.getKeyUsage()[0]);
        assertTrue(certificate.getKeyUsage()[2]);
    }

    @Test
    void certificateSignatureVerifiesWithSigningKeyOnly() throws Exception {
        X509Certificate certificate = credentials.certificate();
        assertEquals("1.2.840.113549.1.1.11", certificate.getSigAlgOID());
        assertDoesNotThrow(() -> certificate.verify(signingPublicKey));
        assertThrows(SignatureException.class, () -> certificate.verify(credentials.keyPair().getPublic()));
    }

    @Test
    void validityIsExactly365DaysFromClock() throws Exception {
        X509Certificate certificate = credentials.certificate();
        assertEquals(Date.from(NOW), certificate.getNotBefore());
        assertEquals(Date.from(NOW.plus(365, ChronoUnit.DAYS)), certificate.getNotAfter());
        certificate.checkValidity(Date.from(NOW.plus(1, ChronoUnit.DAYS)));
        assertThrows(CertificateNotYetValidException.class,
                () -> certificate.checkValidity(Date.from(NOW.minusSeconds(1))));
        assertThrows(CertificateExpiredException.class,
                () -> certificate.checkValidity(Date.from(NOW.plus(365, ChronoUnit.DAYS).plusSeconds(1))));
    }

    @Test
    void exportedPemIsReadableByJdkAndKeysMatch() throws Exception {
        String pem = new String(credentials.privateKeyPem(), StandardCharsets.US_ASCII);
        String header = "-----BEGIN PRIVATE KEY-----";
        String footer = "-----END PRIVATE KEY-----";
        assertTrue(pem.startsWith(header));
        assertTrue(pem.stripTrailing().endsWith(footer));
        byte[] encodedKey = Base64.getMimeDecoder().decode(pem.substring(header.length(), pem.indexOf(footer)));
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encodedKey));
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(credentials.certificatePem()));
        assertArrayEquals(credentials.certificate().getEncoded(), certificate.getEncoded());
        assertArrayEquals(credentials.keyPair().getPrivate().getEncoded(), privateKey.getEncoded());
        byte[] data = "J1 private and public key match".getBytes(StandardCharsets.US_ASCII);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(data);
        byte[] signed = signature.sign();
        signature.initVerify(certificate.getPublicKey());
        signature.update(data);
        assertTrue(signature.verify(signed));
    }

    @Test
    void realCredentialsFitProtocolAndRoundTrip() throws Exception {
        byte[] key = credentials.privateKeyPem();
        byte[] certificate = credentials.certificatePem();
        assertTrue(key.length <= ProtocolLimits.MAX_PEM_BLOCK_BYTES);
        assertTrue(certificate.length <= ProtocolLimits.MAX_PEM_BLOCK_BYTES);
        var response = new ProtocolResponse.Success(key, certificate);
        assertEquals(response, new ResponseDecoder().feed(ProtocolCodec.encodeResponse(response)).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a\u0000b", "a\nb", "\u007F", "я", "😀"})
    void rejectsInvalidNameBeforeGeneration(String name) {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(name));
    }

    @Test
    void rejectsNameAboveProtocolLimit() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate("a".repeat(256)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not a distinguished name"})
    void rejectsInvalidIssuer(String issuer) {
        assertThrows(IllegalArgumentException.class, () -> new CertificateGenerator(signingKey, issuer));
    }

    @Test
    void refusesToOverwriteExistingKey() throws Exception {
        byte[] original = Files.readAllBytes(signingKeyPath);
        assertThrows(FileAlreadyExistsException.class, () -> RsaKeys.writePrivateKey(signingKeyPath, signingKey));
        assertThrows(FileAlreadyExistsException.class,
                () -> SigningKeyTool.main(new String[]{"--output", signingKeyPath.toString()}));
        assertArrayEquals(original, Files.readAllBytes(signingKeyPath));
    }

    @Test
    void reportsMissingSigningKey() {
        assertThrows(NoSuchFileException.class, () -> RsaKeys.loadPrivateKey(directory.resolve("missing.key")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not a key", "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n",
            "-----BEGIN PRIVATE KEY-----\nAAAA\n"})
    void rejectsMalformedSigningKeyFile(String content) throws Exception {
        Path path = Files.createTempFile(directory, "malformed-", ".key");
        Files.writeString(path, content, StandardCharsets.US_ASCII);
        assertThrows(IOException.class, () -> RsaKeys.loadPrivateKey(path));
    }

    @Test
    void rejectsCertificateAndMultipleObjectsInKeyFile() throws Exception {
        Path certificate = directory.resolve("certificate-instead-of-key.pem");
        Files.write(certificate, credentials.certificatePem());
        assertThrows(IOException.class, () -> RsaKeys.loadPrivateKey(certificate));
        String key = Files.readString(signingKeyPath, StandardCharsets.US_ASCII);
        Path multiple = directory.resolve("multiple-keys.pem");
        Files.writeString(multiple, key + key, StandardCharsets.US_ASCII);
        assertThrows(IOException.class, () -> RsaKeys.loadPrivateKey(multiple));
    }

    @Test
    void rejectsRsaKeyWithWrongSize() throws Exception {
        KeyPairGenerator smallGenerator = KeyPairGenerator.getInstance("RSA");
        smallGenerator.initialize(1024);
        PrivateKey smallKey = smallGenerator.generateKeyPair().getPrivate();
        Path path = directory.resolve("wrong-size.key");
        Files.write(path, PemEncoding.encode("PRIVATE KEY", smallKey.getEncoded()));
        assertThrows(InvalidKeyException.class, () -> RsaKeys.loadPrivateKey(path));
        assertThrows(InvalidKeyException.class, () -> new CertificateGenerator(smallKey, ISSUER));
        assertThrows(InvalidKeyException.class,
                () -> RsaKeys.writePrivateKey(directory.resolve("must-not-write.key"), smallKey));
        assertFalse(Files.exists(directory.resolve("must-not-write.key")));
    }

    @Test
    void rejectsNonRsaPrivateKey() throws Exception {
        KeyPairGenerator ecGenerator = KeyPairGenerator.getInstance("EC");
        ecGenerator.initialize(256);
        KeyPair pair = ecGenerator.generateKeyPair();
        Path path = directory.resolve("ec.key");
        Files.write(path, PemEncoding.encode("PRIVATE KEY", pair.getPrivate().getEncoded()));
        assertThrows(GeneralSecurityException.class, () -> RsaKeys.loadPrivateKey(path));
        assertThrows(InvalidKeyException.class, () -> new CertificateGenerator(pair.getPrivate(), ISSUER));
    }

    @Test
    void signingKeyToolRequiresOutputOption() {
        assertThrows(IllegalArgumentException.class, () -> SigningKeyTool.main(new String[]{}));
        assertThrows(IllegalArgumentException.class, () -> SigningKeyTool.main(new String[]{"--output"}));
        assertThrows(IllegalArgumentException.class, () -> SigningKeyTool.main(new String[]{"--other", "key"}));
        assertThrows(IllegalArgumentException.class, () -> SigningKeyTool.main(new String[]{"--output", " "}));
    }
}
