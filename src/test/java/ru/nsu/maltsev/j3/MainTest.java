package ru.nsu.maltsev.j3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class MainTest {
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8);

    @Test
    void outputsSortedMessagesAndReportsCountSeparately() throws Exception {
        try (var server = new TestHttpServer()) {
            server.respond("/", 200, "{\"message\":\"z\",\"successors\":[\"a\",\"b\"]}");
            server.respond("/a", 200, "{\"message\":\"a\",\"successors\":[]}");
            server.respond("/b", 200, "{\"message\":\"a\",\"successors\":[]}");

            assertEquals(0, Main.run(new String[]{server.root().toString()}, out, err));
            assertEquals(String.join(System.lineSeparator(), "a", "a", "z", ""), stdout.toString(StandardCharsets.UTF_8));
            assertTrue(stderr.toString(StandardCharsets.UTF_8).startsWith("Collected 3 messages in "));
        }
    }

    @Test
    void failureHasNoPartialOutputAndReturnsExitCodeOne() throws Exception {
        try (var server = new TestHttpServer()) {
            server.respond("/", 200, "{\"message\":\"root\",\"successors\":[\"missing\"]}");
            server.respond("/missing", 404, "Not found");

            assertEquals(1, Main.run(new String[]{server.root().toString()}, out, err));
            assertEquals("", stdout.toString(StandardCharsets.UTF_8));
            assertTrue(stderr.toString(StandardCharsets.UTF_8).contains(server.root() + "missing"));
            assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("HTTP 404"));
        }
    }

    @Test
    void interruptionReturnsFailureAndPreservesInterruptStatus() throws Exception {
        try (var server = new TestHttpServer()) {
            Thread.currentThread().interrupt();
            try {
                assertEquals(1, Main.run(new String[]{server.root().toString()}, out, err));
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals("", stdout.toString(StandardCharsets.UTF_8));
                assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("interrupted"));
            } finally {
                Thread.interrupted();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-url", "http://", "file:///tmp", "http://localhost/path"})
    void invalidArgumentsReturnExitCodeTwo(String argument) {
        assertEquals(2, Main.run(new String[]{argument}, out, err));
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("Usage:"));
    }

    @Test
    void rejectsExtraArguments() {
        assertEquals(2, Main.run(new String[]{"http://localhost/", "extra"}, out, err));
    }

    @Test
    void helpDoesNotStartCrawling() {
        assertEquals(0, Main.run(new String[]{"--help"}, out, err));
        assertTrue(stdout.toString(StandardCharsets.UTF_8).startsWith("Usage:"));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }
}
