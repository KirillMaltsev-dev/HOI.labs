package ru.nsu.fit.maltsev.hoi.server;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.ASN1String;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ru.nsu.fit.maltsev.hoi.client.ClientMain;
import ru.nsu.fit.maltsev.hoi.crypto.RsaKeys;
import ru.nsu.fit.maltsev.hoi.crypto.SigningKeyTool;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolCodec;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolResponse;
import ru.nsu.fit.maltsev.hoi.protocol.ResponseDecoder;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ServerEndToEndTest {
    private static final String ISSUER = "CN=J1 End-to-End Issuer,O=NSU";
    private static final String NAME = "Ann";

    @TempDir
    Path directory;

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void realServerHandlesOneHundredClientsAndCliModesWithRsa8192() throws Exception {
        Path signingKey = directory.resolve("keys/signing.key");
        SigningKeyTool.main(new String[]{"--output", signingKey.toString()});
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        Path serverLog = directory.resolve("server.log");
        Process server = start(ServerMain.class, serverLog,
                "--bind", "127.0.0.1", "--port", Integer.toString(port), "--workers", "2",
                "--signing-key", signingKey.toString(), "--issuer", ISSUER);
        List<Socket> clients = new ArrayList<>();
        Process slow = null;
        try {
            awaitListening(server, serverLog);
            for (int i = 0; i < 100; i++) {
                Socket socket = new Socket();
                clients.add(socket);
                socket.setSoTimeout(180_000);
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 10_000);
            }
            for (Socket socket : clients) {
                socket.getOutputStream().write(ProtocolCodec.encodeRequest(NAME).array());
            }
            clients.getFirst().shutdownOutput();
            clients.get(1).setSoLinger(true, 0);
            clients.get(1).close();

            runClient(port, NAME, "disconnected", "--disconnect-without-read");
            assertFalse(Files.exists(directory.resolve("disconnected")));
            slow = startClient(port, NAME, "slow", "--read-delay-seconds", "1");
            runClient(port, NAME, "ordinary");
            awaitSuccess(slow, directory.resolve("slow.log"));

            ProtocolResponse.Success expected = readReply(clients.get(2));
            for (int i = 3; i < clients.size(); i++) {
                assertEquals(expected, readReply(clients.get(i)), "Response for client " + i);
            }
            assertEquals(expected, readReply(clients.getFirst()));
            runClient(port, NAME, "cached");
            for (String folder : List.of("ordinary", "slow", "cached")) {
                assertArrayEquals(expected.privateKeyPem(), savedFile(folder, ".key"));
                assertArrayEquals(expected.certificatePem(), savedFile(folder, ".crt"));
            }
            verifyCertificate(expected.certificatePem(), NAME, signingKey);

            runClient(port, "Bob", "different-name");
            byte[] otherCertificate = savedFile("different-name", ".crt");
            verifyCertificate(otherCertificate, "Bob", signingKey);
            assertFalse(java.util.Arrays.equals(expected.privateKeyPem(), savedFile("different-name", ".key")));
            assertNotEquals(certificate(expected.certificatePem()).getPublicKey(),
                    certificate(otherCertificate).getPublicKey());
            assertTrue(server.isAlive(), () -> readLog(serverLog));
        } finally {
            if (slow != null) {
                stop(slow);
            }
            try {
                for (Socket socket : clients) {
                    socket.close();
                }
            } finally {
                stop(server);
            }
        }
    }

    private Process startClient(int port, String name, String folder, String... options) throws Exception {
        List<String> args = new ArrayList<>(List.of("--name", name, "--host", "localhost", "--port",
                Integer.toString(port), "--output-dir", directory.resolve(folder).toString()));
        args.addAll(List.of(options));
        return start(ClientMain.class, directory.resolve(folder + ".log"), args.toArray(String[]::new));
    }

    private void runClient(int port, String name, String folder, String... options) throws Exception {
        Process client = startClient(port, name, folder, options);
        try {
            awaitSuccess(client, directory.resolve(folder + ".log"));
        } finally {
            stop(client);
        }
    }

    private static Process start(Class<?> main, Path log, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                main.getName()));
        command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static void awaitListening(Process server, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            assertTrue(server.isAlive(), () -> readLog(log));
            if (readLog(log).contains("Listening on ")) {
                return;
            }
            Thread.sleep(25);
        }
        fail("Server did not start: " + readLog(log));
    }

    private static void awaitSuccess(Process process, Path log) throws Exception {
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), () -> "Process timed out: " + readLog(log));
        assertEquals(0, process.exitValue(), () -> readLog(log));
    }

    private static void stop(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Cannot stop child JVM");
            }
        }
    }

    private static String readLog(Path log) {
        try {
            return Files.readString(log, StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            return failure.toString();
        }
    }

    private static ProtocolResponse.Success readReply(Socket socket) throws Exception {
        ResponseDecoder decoder = new ResponseDecoder();
        ProtocolResponse response = null;
        byte[] chunk = new byte[257];
        int count;
        while ((count = socket.getInputStream().read(chunk)) != -1) {
            assertNull(response, "Unexpected data after response");
            ByteBuffer input = ByteBuffer.wrap(chunk, 0, count);
            response = decoder.feed(input).orElse(null);
            assertFalse(input.hasRemaining());
        }
        decoder.endOfInput();
        return assertInstanceOf(ProtocolResponse.Success.class, response);
    }

    private byte[] savedFile(String folder, String extension) throws Exception {
        try (var files = Files.list(directory.resolve(folder))) {
            List<Path> matches = files.filter(path -> path.toString().endsWith(extension)).toList();
            assertEquals(1, matches.size());
            return Files.readAllBytes(matches.getFirst());
        }
    }

    private static X509Certificate certificate(byte[] pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem));
    }

    private static void verifyCertificate(byte[] pem, String name, Path signingKey) throws Exception {
        X509Certificate certificate = certificate(pem);
        certificate.checkValidity();
        assertEquals(8192, ((RSAPublicKey) certificate.getPublicKey()).getModulus().bitLength());
        assertEquals(new X500Name(ISSUER), X500Name.getInstance(certificate.getIssuerX500Principal().getEncoded()));
        X500Name subject = X500Name.getInstance(certificate.getSubjectX500Principal().getEncoded());
        assertEquals(1, subject.getRDNs().length);
        assertEquals(name, ((ASN1String) subject.getRDNs(BCStyle.CN)[0].getFirst().getValue()).getString());
        RSAPrivateCrtKey issuerKey = (RSAPrivateCrtKey) RsaKeys.loadPrivateKey(signingKey);
        certificate.verify(KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(issuerKey.getModulus(), issuerKey.getPublicExponent())));
    }
}
