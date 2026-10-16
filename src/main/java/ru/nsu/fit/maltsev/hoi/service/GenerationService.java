package ru.nsu.fit.maltsev.hoi.service;

import ru.nsu.fit.maltsev.hoi.protocol.ClientName;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolResponse;
import ru.nsu.fit.maltsev.hoi.protocol.ResponseStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class GenerationService implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(GenerationService.class.getName());
    private static final ProtocolResponse.Error GENERATION_FAILED = new ProtocolResponse.Error(
            ResponseStatus.GENERATION_FAILED, "Credential generation failed");

    private final Object lock = new Object();
    private final Map<ClientName, Attempt> attempts = new HashMap<>();
    private final ConcurrentLinkedQueue<Completion> completions = new ConcurrentLinkedQueue<>();
    private final ExecutorService workers;
    private final CredentialsGenerator generator;
    private final Runnable wakeup;
    private volatile boolean closed;

    public GenerationService(int workerCount, CredentialsGenerator generator, Runnable wakeup) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("Worker count must be positive");
        }
        this.generator = Objects.requireNonNull(generator, "generator");
        this.wakeup = Objects.requireNonNull(wakeup, "wakeup");
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory defaultFactory = Executors.defaultThreadFactory();
        workers = Executors.newFixedThreadPool(workerCount, task -> {
            Thread thread = defaultFactory.newThread(task);
            thread.setName("key-generator-" + threadNumber.incrementAndGet());
            return thread;
        });
    }

    public CompletionStage<ProtocolResponse> request(String clientName) {
        ClientName name = new ClientName(clientName);
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Generation service is closed");
            }
            Attempt existing = attempts.get(name);
            if (existing != null) {
                return existing.result;
            }
            Attempt attempt = new Attempt(name);
            attempts.put(name, attempt);
            try {
                workers.execute(() -> generate(attempt));
            } catch (RuntimeException | Error failure) {
                attempts.remove(name, attempt);
                throw failure;
            }
            return attempt.result;
        }
    }

    public int drainCompletions() {
        int count = 0;
        Completion completion;
        while ((completion = completions.poll()) != null) {
            Attempt attempt = completion.attempt;
            synchronized (lock) {
                if (closed || attempts.get(attempt.name) != attempt) {
                    continue;
                }
                if (completion.failure != null) {
                    attempts.remove(attempt.name, attempt);
                }
            }
            if (completion.failure != null) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "Credential generation failed for " + attempt.name.value(), completion.failure);
            }
            if (attempt.future.complete(completion.response)) {
                count++;
            }
        }
        return count;
    }

    private void generate(Attempt attempt) {
        ProtocolResponse response;
        Throwable failure = null;
        try {
            response = Objects.requireNonNull(generator.generate(attempt.name.value()), "Generated response");
        } catch (Throwable exception) {
            response = GENERATION_FAILED;
            failure = exception;
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        publish(new Completion(attempt, response, failure));
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private void publish(Completion completion) {
        if (closed) {
            return;
        }
        completions.add(completion);
        if (closed) {
            completions.remove(completion);
            return;
        }
        wakeup.run();
    }

    @Override
    public void close() {
        List<Attempt> pending;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            pending = new ArrayList<>(attempts.values());
            attempts.clear();
            workers.shutdownNow();
            completions.clear();
        }
        for (Attempt attempt : pending) {
            attempt.future.completeExceptionally(new CancellationException("Generation service is closed"));
        }
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must not be negative");
        }
        return workers.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private static final class Attempt {
        private final ClientName name;
        private final CompletableFuture<ProtocolResponse> future = new CompletableFuture<>();
        private final CompletionStage<ProtocolResponse> result = future.minimalCompletionStage();

        private Attempt(ClientName name) {
            this.name = name;
        }
    }

    private record Completion(Attempt attempt, ProtocolResponse response, Throwable failure) {
    }
}
