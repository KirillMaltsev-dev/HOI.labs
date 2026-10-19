package ru.nsu.maltsev.j3;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;

final class Crawler {
    private final PageClient client;

    Crawler(PageClient client) {
        this.client = client;
    }

    List<String> crawl(URI root) throws IOException, InterruptedException {
        root = validateRoot(root);
        Set<URI> visited = new HashSet<>();
        List<String> messages = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var completed = new ExecutorCompletionService<FetchedPage>(executor);
                visited.add(root);
                submit(completed, root);
                int pending = 1;

                while (pending > 0) {
                    FetchedPage fetched = completed.take().get();
                    pending--;
                    messages.add(fetched.page().message());
                    for (String successor : fetched.page().successors()) {
                        URI next = resolveSuccessor(root, successor, fetched.uri());
                        if (visited.add(next)) {
                            submit(completed, next);
                            pending++;
                        }
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("Request task failed", e.getCause());
        }
        messages.sort(String::compareTo);
        return List.copyOf(messages);
    }

    static URI validateRoot(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getPort() == 0 || uri.getPort() > 65535
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("Expected a server root URL, for example http://localhost:8080/");
        }
        try {
            return new URI(scheme.toLowerCase(Locale.ROOT), null,
                    uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), "/", null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid server URL", e);
        }
    }

    private static URI resolveSuccessor(URI root, String successor, URI source) throws IOException {
        try {
            URI path = new URI(successor);
            if (path.isAbsolute() || path.getRawAuthority() != null
                    || path.getRawQuery() != null || path.getRawFragment() != null) {
                throw new URISyntaxException(successor, "Expected a path on the same server");
            }
            return root.resolve(path).normalize();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid successor '" + successor + "' from " + source, e);
        }
    }

    private void submit(ExecutorCompletionService<FetchedPage> completed, URI uri) {
        completed.submit(() -> {
            try {
                return new FetchedPage(uri, client.fetch(uri));
            } catch (IOException e) {
                throw new IOException("Request failed for " + uri + ": " + e.getMessage(), e);
            }
        });
    }

    private record FetchedPage(URI uri, Page page) { }
}
