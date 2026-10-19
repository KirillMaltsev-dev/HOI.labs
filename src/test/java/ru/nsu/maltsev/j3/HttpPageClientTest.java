package ru.nsu.maltsev.j3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class HttpPageClientTest {
    @Test
    void sendsGetAndReadsUtf8Json() throws Exception {
        var method = new AtomicReference<String>();
        var accept = new AtomicReference<String>();
        try (var server = new TestHttpServer(); var client = new HttpPageClient()) {
            server.handle("/", exchange -> {
                method.set(exchange.getRequestMethod());
                accept.set(exchange.getRequestHeaders().getFirst("Accept"));
                try (exchange) {
                    byte[] body = "{\"message\":\"Привет \\\"мир\\\"\",\"successors\":[\"42\"]}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });

            assertEquals(new Page("Привет \"мир\"", List.of("42")), client.fetch(server.root()));
            assertEquals("GET", method.get());
            assertEquals("application/json", accept.get());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 404, 500})
    void rejectsNon200Responses(int status) throws Exception {
        try (var server = new TestHttpServer(); var client = new HttpPageClient()) {
            server.respond("/", status, "Error");
            IOException error = assertThrows(IOException.class, () -> client.fetch(server.root()));
            assertEquals("HTTP " + status, error.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "", "null", "[]", "{}",
            "{\"message\":null,\"successors\":[]}", "{\"message\":1,\"successors\":[]}",
            "{\"message\":\"text\",\"successors\":null}",
            "{\"message\":\"text\",\"successors\":[1]}",
            "{\"message\":\"text\",\"successors\":[null]}",
            "{\"message\":\"text\",\"successors\":[]} {}"})
    void rejectsMalformedJsonAndInvalidFieldTypes(String body) throws Exception {
        try (var server = new TestHttpServer(); var client = new HttpPageClient()) {
            server.respond("/", 200, body);
            assertThrows(IOException.class, () -> client.fetch(server.root()));
        }
    }

    @Test
    void timesOutSlowResponse() throws Exception {
        var reached = new CountDownLatch(1);
        try (var server = new TestHttpServer();
             var client = new HttpPageClient(Duration.ofSeconds(1), Duration.ofMillis(300))) {
            server.handle("/", exchange -> {
                reached.countDown();
                try (exchange) {
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertThrows(HttpTimeoutException.class, () -> client.fetch(server.root()));
            assertTrue(reached.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void crawlerCancelsAnActualHttpSendWhenAnotherRequestFails() throws Exception {
        var slowStarted = new CountDownLatch(1);
        try (var server = new TestHttpServer(); var client = new HttpPageClient()) {
            server.respond("/", 200, "{\"message\":\"root\",\"successors\":[\"slow\",\"broken\"]}");
            server.handle("/slow", exchange -> {
                slowStarted.countDown();
                try (exchange) {
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            server.handle("/broken", exchange -> {
                try (exchange) {
                    try {
                        if (!slowStarted.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("Slow request did not start");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    exchange.sendResponseHeaders(500, -1);
                }
            });

            IOException error = assertThrows(IOException.class, () -> new Crawler(client).crawl(server.root()));
            assertTrue(error.getMessage().contains("/broken"));
            assertTrue(error.getMessage().contains("HTTP 500"));
        }
    }
}
