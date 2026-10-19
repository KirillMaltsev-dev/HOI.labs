package ru.nsu.maltsev.j3;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class TestHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    TestHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.start();
    }

    URI root() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    void handle(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    void respond(String path, int status, String body) {
        handle(path, exchange -> {
            try (exchange) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        executor.close();
    }
}
