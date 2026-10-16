package ru.nsu.fit.maltsev.hoi.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolResponse;
import ru.nsu.fit.maltsev.hoi.protocol.ResponseStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class GenerationServiceTest {
    private static final ProtocolResponse.Success SUCCESS = new ProtocolResponse.Success(
            new byte[]{65}, new byte[]{66});

    @Test
    void concurrentRequestsShareOneAttemptAndCacheSuccess() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Semaphore published = new Semaphore(0);
        CredentialsGenerator generator = name -> {
            calls.incrementAndGet();
            started.countDown();
            release.await();
            return SUCCESS;
        };
        try (var service = new GenerationService(3, generator, published::release);
             ExecutorService callers = Executors.newFixedThreadPool(12)) {
            try {
                List<Future<CompletionStage<ProtocolResponse>>> submissions = new ArrayList<>();
                for (int i = 0; i < 24; i++) {
                    submissions.add(callers.submit(() -> {
                        start.await();
                        return service.request("Ann");
                    }));
                }
                start.countDown();
                CompletionStage<ProtocolResponse> shared = submissions.getFirst().get(5, TimeUnit.SECONDS);
                for (Future<CompletionStage<ProtocolResponse>> submission : submissions) {
                    assertSame(shared, submission.get(5, TimeUnit.SECONDS));
                }
                await(started);
                assertEquals(1, calls.get());
                release.countDown();
                await(published, 1);
                assertFalse(shared.toCompletableFuture().isDone());
                assertSame(shared, service.request("Ann"));
                assertEquals(1, service.drainCompletions());
                assertSame(SUCCESS, result(shared));
                assertSame(shared, service.request("Ann"));
                assertSame(SUCCESS, result(service.request("Ann")));
                assertEquals(1, calls.get());
                assertEquals(0, published.availablePermits());
                assertEquals(0, service.drainCompletions());
            } finally {
                start.countDown();
                release.countDown();
            }
        }
    }

    @Test
    void namesRemainCaseAndSpaceSensitive() throws Exception {
        Set<String> generated = ConcurrentHashMap.newKeySet();
        Semaphore published = new Semaphore(0);
        try (var service = new GenerationService(2, name -> {
            generated.add(name);
            return new ProtocolResponse.Success(name.getBytes(StandardCharsets.US_ASCII), new byte[]{1});
        }, published::release)) {
            List<String> names = List.of("Ann", "ann", " Ann ");
            List<CompletionStage<ProtocolResponse>> requests = names.stream().map(service::request).toList();
            await(published, 3);
            assertEquals(3, service.drainCompletions());
            assertEquals(Set.copyOf(names), generated);
            for (int i = 0; i < names.size(); i++) {
                var response = assertInstanceOf(ProtocolResponse.Success.class, result(requests.get(i)));
                assertArrayEquals(names.get(i).getBytes(StandardCharsets.US_ASCII), response.privateKeyPem());
            }
        }
    }

    @Test
    void fixedPoolLimitsParallelGenerationAndRunsQueuedTasks() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Semaphore published = new Semaphore(0);
        try (var service = new GenerationService(2, name -> {
            calls.incrementAndGet();
            threads.add(Thread.currentThread());
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            started.countDown();
            try {
                release.await();
                return SUCCESS;
            } finally {
                active.decrementAndGet();
            }
        }, published::release)) {
            try {
                for (int i = 0; i < 6; i++) {
                    service.request("Name" + i);
                }
                await(started);
                assertEquals(2, calls.get());
                assertEquals(2, maximum.get());
                release.countDown();
                await(published, 6);
                assertEquals(6, service.drainCompletions());
                assertEquals(6, calls.get());
                assertEquals(2, threads.size());
                assertFalse(threads.contains(Thread.currentThread()));
                assertEquals(2, maximum.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void allowsWorkerCountAboveAvailableProcessors() throws Exception {
        int count = Runtime.getRuntime().availableProcessors() + 1;
        Semaphore published = new Semaphore(0);
        try (var service = new GenerationService(count, name -> SUCCESS, published::release)) {
            var request = service.request("Ann");
            await(published, 1);
            assertEquals(1, service.drainCompletions());
            assertSame(SUCCESS, result(request));
        }
    }

    @Test
    void failureIsSharedAndRemovedBeforeCompletionCallbackRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<CompletionStage<ProtocolResponse>> retry = new AtomicReference<>();
        Semaphore published = new Semaphore(0);
        try (var service = new GenerationService(1, name -> {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("internal generation detail");
            }
            return SUCCESS;
        }, published::release)) {
            var first = service.request("Ann");
            var secondWaiter = service.request("Ann");
            assertSame(first, secondWaiter);
            first.thenAccept(response -> retry.set(service.request("Ann")));
            await(published, 1);
            assertSame(first, service.request("Ann"));
            service.drainCompletions();
            var error = assertInstanceOf(ProtocolResponse.Error.class, result(first));
            assertEquals(ResponseStatus.GENERATION_FAILED, error.status());
            assertFalse(error.message().contains("internal generation detail"));
            assertSame(error, result(secondWaiter));
            assertNotNull(retry.get());
            assertNotSame(first, retry.get());
            await(published, 1);
            service.drainCompletions();
            assertSame(SUCCESS, result(retry.get()));
            assertSame(retry.get(), service.request("Ann"));
            assertEquals(2, calls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void runtimeFailureAndNullResultDoNotLeaveNamePending(boolean returnNull) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Semaphore published = new Semaphore(0);
        try (var service = new GenerationService(1, name -> {
            if (calls.incrementAndGet() == 1) {
                if (returnNull) {
                    return null;
                }
                throw new IllegalStateException("generator failure");
            }
            return SUCCESS;
        }, published::release)) {
            var failed = service.request("Ann");
            await(published, 1);
            service.drainCompletions();
            assertEquals(ResponseStatus.GENERATION_FAILED, result(failed).status());
            var retry = service.request("Ann");
            await(published, 1);
            service.drainCompletions();
            assertSame(SUCCESS, result(retry));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void cancellationOrCompletionOfClientCopyCannotChangeSharedAttempt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Semaphore published = new Semaphore(0);
        try (var service = new GenerationService(1, name -> {
            release.await();
            return SUCCESS;
        }, published::release)) {
            try {
                var shared = service.request("Ann");
                var cancelled = shared.toCompletableFuture();
                assertTrue(cancelled.cancel(true));
                var modified = shared.toCompletableFuture();
                modified.complete(new ProtocolResponse.Error(ResponseStatus.INVALID_REQUEST, "fake"));
                assertFalse(shared.toCompletableFuture().isDone());
                assertSame(shared, service.request("Ann"));
                release.countDown();
                await(published, 1);
                service.drainCompletions();
                assertTrue(cancelled.isCancelled());
                assertSame(SUCCESS, result(shared));
                assertSame(SUCCESS, result(service.request("Ann")));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void workerPublishesOneHundredResultsWithoutConsumer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Semaphore published = new Semaphore(0);
        try (var service = new GenerationService(1, name -> {
            calls.incrementAndGet();
            return SUCCESS;
        }, published::release)) {
            List<CompletionStage<ProtocolResponse>> requests = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                requests.add(service.request("Name" + i));
            }
            await(published, 100);
            assertEquals(100, calls.get());
            for (var request : requests) {
                assertFalse(request.toCompletableFuture().isDone());
            }
            assertEquals(100, service.drainCompletions());
            for (var request : requests) {
                assertSame(SUCCESS, result(request));
            }
        }
    }

    @Test
    void slowCompletionCallbackRunsOnDispatcherAndDoesNotBlockWorker() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        Semaphore published = new Semaphore(0);
        try (var service = new GenerationService(1, name -> {
            calls.incrementAndGet();
            return SUCCESS;
        }, published::release);
             ExecutorService dispatcher = Executors.newSingleThreadExecutor()) {
            try {
                var first = service.request("Ann");
                var callback = first.thenRun(() -> {
                    callbackThread.set(Thread.currentThread());
                    callbackStarted.countDown();
                    try {
                        releaseCallback.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                });
                await(published, 1);
                Future<Thread> dispatch = dispatcher.submit(() -> {
                    service.drainCompletions();
                    return Thread.currentThread();
                });
                await(callbackStarted);
                var second = service.request("Bob");
                await(published, 1);
                assertEquals(2, calls.get());
                assertFalse(second.toCompletableFuture().isDone());
                releaseCallback.countDown();
                assertSame(dispatch.get(5, TimeUnit.SECONDS), callbackThread.get());
                callback.toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertSame(SUCCESS, result(second));
            } finally {
                releaseCallback.countDown();
            }
        }
    }

    @Test
    void closeCancelsRunningAndQueuedRequestsAndStopsWorkers() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        var service = new GenerationService(1, name -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return SUCCESS;
            } finally {
                interrupted.countDown();
            }
        }, () -> { });
        try {
            var running = service.request("Ann");
            await(started);
            var queued = service.request("Bob");
            service.close();
            assertCancelled(running);
            assertCancelled(queued);
            await(interrupted);
            assertTrue(service.awaitTermination(Duration.ofSeconds(5)));
            assertEquals(1, calls.get());
            assertEquals(0, service.drainCompletions());
            assertThrows(IllegalStateException.class, () -> service.request("Ann"));
            assertDoesNotThrow(service::close);
        } finally {
            service.close();
        }
    }

    @Test
    void closeDiscardsPublishedButUndeliveredResult() throws Exception {
        Semaphore published = new Semaphore(0);
        var service = new GenerationService(1, name -> SUCCESS, published::release);
        try {
            var request = service.request("Ann");
            await(published, 1);
            service.close();
            assertCancelled(request);
            assertEquals(0, service.drainCompletions());
            assertTrue(service.awaitTermination(Duration.ofSeconds(5)));
        } finally {
            service.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveWorkerCount(int count) {
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationService(count, name -> SUCCESS, () -> { }));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a\nb", "я"})
    void invalidNameNeverReachesGenerator(String name) {
        AtomicInteger calls = new AtomicInteger();
        try (var service = new GenerationService(1, ignored -> {
            calls.incrementAndGet();
            return SUCCESS;
        }, () -> { })) {
            assertThrows(IllegalArgumentException.class, () -> service.request(name));
            assertThrows(IllegalArgumentException.class, () -> service.request("a".repeat(256)));
            assertEquals(0, calls.get());
        }
    }

    private static ProtocolResponse result(CompletionStage<ProtocolResponse> request) throws Exception {
        return request.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void assertCancelled(CompletionStage<ProtocolResponse> request) throws Exception {
        Throwable failure = request.handle((response, exception) -> exception)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        while (failure instanceof CompletionException) {
            failure = failure.getCause();
        }
        assertInstanceOf(CancellationException.class, failure);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for latch");
    }

    private static void await(Semaphore semaphore, int count) throws InterruptedException {
        assertTrue(semaphore.tryAcquire(count, 5, TimeUnit.SECONDS), "Timed out waiting for publication");
    }
}
