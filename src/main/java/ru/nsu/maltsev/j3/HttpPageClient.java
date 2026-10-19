package ru.nsu.maltsev.j3;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;

final class HttpPageClient implements PageClient, AutoCloseable {
    private final HttpClient client;
    private final Duration requestTimeout;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    HttpPageClient() {
        this(Duration.ofSeconds(5), Duration.ofSeconds(30));
    }

    HttpPageClient(Duration connectTimeout, Duration requestTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.requestTimeout = requestTimeout;
    }

    @Override
    public Page fetch(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        JsonNode json = mapper.readTree(response.body());
        if (json == null || !json.isObject()
                || !json.path("message").isTextual()
                || !json.path("successors").isArray()) {
            throw new IOException("Expected JSON with a string message and an array of successors");
        }
        var successors = new ArrayList<String>();
        for (JsonNode successor : json.get("successors")) {
            if (!successor.isTextual()) {
                throw new IOException("Each successor must be a string");
            }
            successors.add(successor.textValue());
        }
        return new Page(json.get("message").textValue(), successors);
    }

    @Override
    public void close() {
        client.close();
    }
}
