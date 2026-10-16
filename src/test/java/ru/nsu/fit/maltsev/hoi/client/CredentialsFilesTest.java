package ru.nsu.fit.maltsev.hoi.client;

import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.nsu.fit.maltsev.hoi.crypto.GeneratedCredentials;
import ru.nsu.fit.maltsev.hoi.crypto.RsaKeys;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolResponse;
import ru.nsu.fit.maltsev.hoi.server.NioKeyServer;

import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CredentialsFilesTest {
    private static KeyPair keys;
    private static ProtocolResponse.Success valid;

    @TempDir
    Path directory;

    @BeforeAll
    static void generateFixture() throws Exception {
        keys = RsaKeys.generate();
        valid = response("Ann");
    }

    @Test
    void savesUnchangedPemWithPortableMatchingFilenames() throws Exception {
        Path output = directory.resolve("results");
        CredentialsFiles.SavedPair saved = CredentialsFiles.save(output, "Ann", valid);
        assertEquals(output.toAbsolutePath(), saved.privateKey().getParent());
        assertEquals(output.toAbsolutePath(), saved.certificate().getParent());
        assertTrue(saved.privateKey().getFileName().toString().matches("client-[0-9a-f]{64}\\.key"));
        assertEquals(saved.privateKey().getFileName().toString().replace(".key", ".crt"),
                saved.certificate().getFileName().toString());
        assertArrayEquals(valid.privateKeyPem(), Files.readAllBytes(saved.privateKey()));
        assertArrayEquals(valid.certificatePem(), Files.readAllBytes(saved.certificate()));
        assertArrayEquals(keys.getPrivate().getEncoded(), RsaKeys.loadPrivateKey(saved.privateKey()).getEncoded());
        try (InputStream input = Files.newInputStream(saved.certificate())) {
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(input);
            assertArrayEquals(keys.getPublic().getEncoded(), certificate.getPublicKey().getEncoded());
        }
    }

    @Test
    void acceptsMaximumLengthNameWithPathAndWindowsSpecialCharacters() throws Exception {
        String prefix = "../CON:a\\b\"<>|?+#, ";
        String name = prefix + "x".repeat(254 - prefix.length()) + " ";
        Path output = directory.resolve("results");
        var saved = CredentialsFiles.save(output, name, response(name));
        assertEquals(255, name.length());
        assertEquals(output.toAbsolutePath(), saved.privateKey().getParent());
        assertTrue(saved.privateKey().getFileName().toString().matches("client-[0-9a-f]{64}\\.key"));
        assertTrue(Files.isRegularFile(saved.certificate()));
    }

    @Test
    void filenamesPreserveCaseAndSpaceDifferencesThroughHash() throws Exception {
        var upper = CredentialsFiles.save(directory, "Ann", valid);
        var lower = CredentialsFiles.save(directory, "ann", response("ann"));
        var spaced = CredentialsFiles.save(directory, " Ann ", response(" Ann "));
        assertNotEquals(upper.privateKey(), lower.privateKey());
        assertNotEquals(upper.privateKey(), spaced.privateKey());
        assertNotEquals(lower.privateKey(), spaced.privateKey());
        try (var files = Files.list(directory)) {
            assertEquals(6, files.count());
        }
    }

    @Test
    void doesNotOverwriteExistingPair() throws Exception {
        var saved = CredentialsFiles.save(directory, "Ann", valid);
        byte[] key = Files.readAllBytes(saved.privateKey());
        byte[] certificate = Files.readAllBytes(saved.certificate());
        assertThrows(FileAlreadyExistsException.class, () -> CredentialsFiles.save(directory, "Ann", valid));
        assertArrayEquals(key, Files.readAllBytes(saved.privateKey()));
        assertArrayEquals(certificate, Files.readAllBytes(saved.certificate()));
    }

    @Test
    void rollsBackNewKeyWhenCertificatePathAlreadyExists() throws Exception {
        var saved = CredentialsFiles.save(directory, "Ann", valid);
        Files.delete(saved.privateKey());
        Files.writeString(saved.certificate(), "keep existing certificate", StandardCharsets.US_ASCII);
        assertThrows(FileAlreadyExistsException.class, () -> CredentialsFiles.save(directory, "Ann", valid));
        assertFalse(Files.exists(saved.privateKey()));
        assertEquals("keep existing certificate", Files.readString(saved.certificate(), StandardCharsets.US_ASCII));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void reportsOutputDirectoryThatIsAFileWithoutChangingIt() throws Exception {
        Path output = directory.resolve("file");
        Files.writeString(output, "keep");
        assertThrows(java.io.IOException.class, () -> CredentialsFiles.save(output, "Ann", valid));
        assertEquals("keep", Files.readString(output));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsWrongPemLabelBeforeCreatingDirectory(boolean wrongCertificate) throws Exception {
        var response = wrongCertificate
                ? new ProtocolResponse.Success(valid.privateKeyPem(), valid.privateKeyPem())
                : new ProtocolResponse.Success(valid.certificatePem(), valid.certificatePem());
        Path output = directory.resolve("must-not-exist");
        assertThrows(java.io.IOException.class, () -> CredentialsFiles.save(output, "Ann", response));
        assertFalse(Files.exists(output));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMultiplePemObjectsInEitherBlock(boolean duplicateCertificate) throws Exception {
        byte[] key = valid.privateKeyPem();
        byte[] certificate = valid.certificatePem();
        var response = duplicateCertificate
                ? new ProtocolResponse.Success(key, concatenate(certificate, certificate))
                : new ProtocolResponse.Success(concatenate(key, key), certificate);
        Path output = directory.resolve("must-not-exist");
        assertThrows(java.io.IOException.class, () -> CredentialsFiles.save(output, "Ann", response));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsDamagedDerInsidePem() {
        Path output = directory.resolve("must-not-exist");
        var badKey = new ProtocolResponse.Success(pem("PRIVATE KEY", new byte[]{1, 2, 3}), valid.certificatePem());
        var badCertificate = new ProtocolResponse.Success(valid.privateKeyPem(), pem("CERTIFICATE", new byte[]{1, 2, 3}));
        assertThrows(GeneralSecurityException.class, () -> CredentialsFiles.save(output, "Ann", badKey));
        assertThrows(GeneralSecurityException.class, () -> CredentialsFiles.save(output, "Ann", badCertificate));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsCertificateForDifferentName() throws Exception {
        Path output = directory.resolve("must-not-exist");
        var response = response("Other");
        assertThrows(CertificateException.class, () -> CredentialsFiles.save(output, "Ann", response));
        assertFalse(Files.exists(output));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsExpiredAndNotYetValidCertificate(boolean future) throws Exception {
        Instant now = Instant.now();
        Instant begin = future ? now.plus(1, ChronoUnit.DAYS) : now.minus(2, ChronoUnit.DAYS);
        Instant end = future ? now.plus(2, ChronoUnit.DAYS) : now.minus(1, ChronoUnit.DAYS);
        var response = response("Ann", keys.getPublic(), begin, end);
        Path output = directory.resolve("must-not-exist");
        assertThrows(CertificateException.class, () -> CredentialsFiles.save(output, "Ann", response));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsCertificateWithAnother8192BitModulus() throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) keys.getPublic();
        PublicKey other = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(publicKey.getModulus().flipBit(4), publicKey.getPublicExponent()));
        Instant now = Instant.now();
        var response = response("Ann", other, now.minusSeconds(60), now.plus(1, ChronoUnit.DAYS));
        Path output = directory.resolve("must-not-exist");
        assertThrows(InvalidKeyException.class, () -> CredentialsFiles.save(output, "Ann", response));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsWrongPrivateExponentEvenWhenModulusMatches() throws Exception {
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) keys.getPrivate();
        PrivateKey wrong = KeyFactory.getInstance("RSA").generatePrivate(new RSAPrivateKeySpec(
                privateKey.getModulus(), privateKey.getPrivateExponent().add(BigInteger.TWO)));
        var response = new ProtocolResponse.Success(pem("PRIVATE KEY", wrong.getEncoded()), valid.certificatePem());
        Path output = directory.resolve("must-not-exist");
        assertThrows(GeneralSecurityException.class, () -> CredentialsFiles.save(output, "Ann", response));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsSmallerRsaKey() throws Exception {
        KeyPairGenerator smallGenerator = KeyPairGenerator.getInstance("RSA");
        smallGenerator.initialize(1024);
        var response = new ProtocolResponse.Success(pem("PRIVATE KEY", smallGenerator.generateKeyPair()
                .getPrivate().getEncoded()), valid.certificatePem());
        Path output = directory.resolve("must-not-exist");
        assertThrows(InvalidKeyException.class, () -> CredentialsFiles.save(output, "Ann", response));
        assertFalse(Files.exists(output));
    }

    @Test
    void mainDownloadsFromNioServerAndSavesCachedMaterialToSeparateDirectories() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (NioKeyServer server = new NioKeyServer(new InetSocketAddress("127.0.0.1", 0), 1, name -> {
            calls.incrementAndGet();
            return valid;
        }); ExecutorService eventLoop = Executors.newSingleThreadExecutor()) {
            Future<?> loop = eventLoop.submit(() -> {
                server.run();
                return null;
            });
            try {
                for (String folder : List.of("first", "second")) {
                    Path output = directory.resolve(folder);
                    ClientMain.main(new String[]{"--name", "Ann", "--host", "127.0.0.1", "--port",
                            Integer.toString(server.localAddress().getPort()), "--output-dir", output.toString()});
                    List<Path> files;
                    try (var saved = Files.list(output)) {
                        files = saved.toList();
                    }
                    assertEquals(2, files.size());
                    Path key = files.stream().filter(path -> path.toString().endsWith(".key")).findFirst().orElseThrow();
                    Path certificate = files.stream().filter(path -> path.toString().endsWith(".crt")).findFirst().orElseThrow();
                    assertArrayEquals(valid.privateKeyPem(), Files.readAllBytes(key));
                    assertArrayEquals(valid.certificatePem(), Files.readAllBytes(certificate));
                }
                assertEquals(1, calls.get());
            } finally {
                server.close();
                loop.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static ProtocolResponse.Success response(String name) throws Exception {
        Instant now = Instant.now();
        return response(name, keys.getPublic(), now.minusSeconds(60), now.plus(365, ChronoUnit.DAYS));
    }

    private static ProtocolResponse.Success response(String name, PublicKey publicKey, Instant begin, Instant end)
            throws Exception {
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.CN, new DERUTF8String(name)).build();
        var builder = new JcaX509v3CertificateBuilder(new X500Name("CN=Client Test Issuer"), BigInteger.ONE,
                Date.from(begin), Date.from(end), subject, publicKey);
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        var credentials = new GeneratedCredentials(keys, certificate);
        return new ProtocolResponse.Success(credentials.privateKeyPem(), credentials.certificatePem());
    }

    private static byte[] pem(String type, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(der);
        return ("-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
