package ru.nsu.maltsev.j3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class CrawlerTest {
    private static final URI ROOT = URI.create("http://localhost:8080/");

    @Test
    void visitsEachPathOnceAndPreservesRootEmptyAndRepeatedMessages() throws Exception {
        var graph = Map.of(
                "/", new Page("z", List.of("a", "b", "a", "/a", "./a")),
                "/a", new Page("same", List.of("/", "leaf")),
                "/b", new Page("same", List.of("leaf")),
                "/leaf", new Page("", List.of()));
        var calls = new ConcurrentHashMap<String, Integer>();
        var crawler = new Crawler(uri -> {
            calls.merge(uri.getPath(), 1, Integer::sum);
            return graph.get(uri.getPath());
        });

        assertEquals(List.of("", "same", "same", "z"), crawler.crawl(ROOT));
        assertEquals(Map.of("/", 1, "/a", 1, "/b", 1, "/leaf", 1), calls);
    }

    @Test
    void schedulesNewlyDiscoveredPathsWhileOtherRequestsAreStillRunning() throws Exception {
        var deepReached = new CountDownLatch(1);
        var threads = ConcurrentHashMap.<Thread>newKeySet();
        var crawler = new Crawler(uri -> {
            threads.add(Thread.currentThread());
            return switch (uri.getPath()) {
                case "/" -> new Page("root", List.of("slow", "fast"));
                case "/slow" -> {
                    if (!deepReached.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Deep request did not start while slow request was pending");
                    }
                    yield new Page("slow", List.of());
                }
                case "/fast" -> new Page("fast", List.of("deep"));
                case "/deep" -> {
                    deepReached.countDown();
                    yield new Page("deep", List.of());
                }
                default -> throw new IOException("Unexpected path " + uri);
            };
        });

        assertEquals(List.of("deep", "fast", "root", "slow"), crawler.crawl(ROOT));
        assertEquals(4, threads.size());
        assertTrue(threads.stream().allMatch(Thread::isVirtual));
    }

    @Test
    void failureCancelsOutstandingRequestAndIncludesItsUrl() {
        var slowStarted = new CountDownLatch(1);
        var slowInterrupted = new CountDownLatch(1);
        var crawler = new Crawler(uri -> switch (uri.getPath()) {
            case "/" -> new Page("root", List.of("slow", "broken"));
            case "/slow" -> {
                slowStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                    throw new AssertionError("Unreachable");
                } catch (InterruptedException e) {
                    slowInterrupted.countDown();
                    throw e;
                }
            }
            case "/broken" -> {
                if (!slowStarted.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Slow request did not start");
                }
                throw new IOException("HTTP 500");
            }
            default -> throw new IOException("Unexpected path");
        });

        IOException error = assertThrows(IOException.class, () -> crawler.crawl(ROOT));
        assertTrue(error.getMessage().contains("http://localhost:8080/broken"));
        assertTrue(error.getMessage().contains("HTTP 500"));
        assertEquals(0, slowInterrupted.getCount());
    }

    @Test
    void coordinatorInterruptionCancelsWorkers() throws Exception {
        var started = new CountDownLatch(1);
        var workerInterrupted = new CountDownLatch(1);
        var outcome = new AtomicReference<Throwable>();
        var crawler = new Crawler(uri -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("Unreachable");
            } catch (InterruptedException e) {
                workerInterrupted.countDown();
                throw e;
            }
        });
        Thread coordinator = Thread.ofPlatform().start(() -> {
            try {
                crawler.crawl(ROOT);
            } catch (Throwable e) {
                outcome.set(e);
            }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            coordinator.interrupt();
            coordinator.join(5000);
            assertFalse(coordinator.isAlive());
            assertInstanceOf(InterruptedException.class, outcome.get());
            assertEquals(0, workerInterrupted.getCount());
        } finally {
            coordinator.interrupt();
            coordinator.join(1000);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://other.example/a", "//other.example/a", "a?x=1", "a#fragment", "bad path"})
    void rejectsSuccessorsThatAreNotServerPaths(String successor) {
        var crawler = new Crawler(uri -> new Page("root", List.of(successor)));
        IOException error = assertThrows(IOException.class, () -> crawler.crawl(ROOT));
        assertTrue(error.getMessage().contains("Invalid successor"));
        assertTrue(error.getMessage().contains(ROOT.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://localhost/", "/relative", "http://localhost/a", "http://localhost/?q=1",
            "http://localhost/#fragment", "http://user@localhost/", "http://localhost:65536/", "http://localhost:0/"})
    void rejectsInvalidRoot(String root) {
        assertThrows(IllegalArgumentException.class, () -> Crawler.validateRoot(URI.create(root)));
    }

    @Test
    void normalizesRootUrl() {
        assertEquals(ROOT, Crawler.validateRoot(URI.create("HTTP://LOCALHOST:8080")));
    }
}
