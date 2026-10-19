package ru.nsu.maltsev.j3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProvidedServerIT {
    @Test
    @Timeout(210)
    void packagedClientCrawlsProvidedServer() throws Exception {
        Path target = Path.of("target").toAbsolutePath();
        Path serverJar = Path.of("docs", "Task_J3-server.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(serverJar), "The provided server JAR is required");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String studentId = System.getProperty("server.studentId", "IntegrationTest");
        assertTrue(studentId.matches("[A-Za-z]+"), "server.studentId must contain Latin letters only");
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        URI root = URI.create("http://127.0.0.1:" + port + "/");
        Path serverLog = target.resolve("provided-server.log");
        Path messagesFile = target.resolve("provided-server-messages.txt");
        Path clientLog = target.resolve("provided-client.log");
        String expression = "(require (quote server)) (server/-main (name (quote " + studentId + ")) " + port + ")";
        Process server = new ProcessBuilder(javaExecutable, "-cp", serverJar.toString(), "clojure.main", "-e", expression)
                .redirectErrorStream(true)
                .redirectOutput(serverLog.toFile())
                .start();
        Process client = null;
        try {
            awaitReady(server, root, serverLog);
            long start = System.nanoTime();
            client = new ProcessBuilder(javaExecutable, "-jar", target.resolve("lab_j3.jar").toString(), root.toString())
                    .redirectOutput(messagesFile.toFile())
                    .redirectError(clientLog.toFile())
                    .start();
            assertTrue(client.waitFor(180, TimeUnit.SECONDS), "Client did not finish within 180 seconds");
            double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
            String diagnostics = Files.readString(clientLog, StandardCharsets.UTF_8);
            assertEquals(0, client.exitValue(), diagnostics);
            List<String> messages = Files.readAllLines(messagesFile, StandardCharsets.UTF_8);
            assertEquals(1001, messages.size(), "Every resource, including the root, must contribute a message");
            var sorted = new ArrayList<>(messages);
            sorted.sort(String::compareTo);
            assertEquals(sorted, messages);
            assertTrue(diagnostics.contains("Collected 1001 messages"), diagnostics);
            Files.writeString(target.resolve("provided-server-result.txt"),
                    "studentId=" + studentId + System.lineSeparator()
                            + "messages=" + messages.size() + System.lineSeparator()
                            + "elapsedSeconds=" + elapsed + System.lineSeparator()
                            + diagnostics, StandardCharsets.UTF_8);
            System.out.printf(java.util.Locale.ROOT,
                    "Provided server: %d messages, sorted, %.3f seconds (studentId=%s).%n",
                    messages.size(), elapsed, studentId);
        } finally {
            stop(client);
            stop(server);
        }
    }

    private static void awaitReady(Process server, URI root, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        try (var probe = new HttpPageClient(Duration.ofSeconds(1), Duration.ofSeconds(1))) {
            while (System.nanoTime() < deadline) {
                if (!server.isAlive()) {
                    fail("Server exited: " + Files.readString(log, StandardCharsets.UTF_8));
                }
                try {
                    assertEquals(100, probe.fetch(root).successors().size());
                    return;
                } catch (IOException e) {
                    Thread.sleep(100);
                }
            }
        }
        fail("Server did not start: " + Files.readString(log, StandardCharsets.UTF_8));
    }

    private static void stop(Process process) throws InterruptedException {
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Could not stop test process");
            }
        }
    }
}
