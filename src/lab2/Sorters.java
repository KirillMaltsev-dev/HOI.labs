package lab2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class Sorters implements AutoCloseable {
    record Statistics(long steps, long swaps, List<Long> perThread) { }

    private final class Worker implements Runnable {
        private long steps;
        private long swaps;

        @Override
        public void run() {
            ready.countDown();
            try {
                start.await();
                int position = 0;
                while (!Thread.currentThread().isInterrupted() && beforeDeadline()) {
                    SortableStrings.Step result = list.step(position, insideMillis);
                    if (result == SortableStrings.Step.NO_PAIR) {
                        position = 0;
                        Thread.sleep(1);
                        continue;
                    }
                    if (!beforeDeadline()) {
                        break;
                    }
                    steps++;
                    if (result == SortableStrings.Step.SWAPPED) {
                        swaps++;
                    }
                    position++;
                    Thread.sleep(betweenMillis);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        }
    }

    private final SortableStrings list;
    private final long insideMillis;
    private final long betweenMillis;
    private final CountDownLatch ready;
    private final CountDownLatch start = new CountDownLatch(1);
    private final List<Worker> workers = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private long startedAt;
    private long durationNanos;

    Sorters(SortableStrings list, int count, long insideMillis, long betweenMillis) {
        this.list = list;
        this.insideMillis = insideMillis;
        this.betweenMillis = betweenMillis;
        ready = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            Worker worker = new Worker();
            workers.add(worker);
            threads.add(new Thread(worker, "sorter-" + (i + 1)));
        }
    }

    void start(long durationMillis) throws InterruptedException {
        for (Thread thread : threads) {
            thread.start();
        }
        ready.await();
        durationNanos = TimeUnit.MILLISECONDS.toNanos(durationMillis);
        startedAt = System.nanoTime();
        start.countDown();
    }

    void awaitDeadline() throws InterruptedException {
        long remaining;
        while ((remaining = durationNanos - (System.nanoTime() - startedAt)) > 0) {
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }

    private boolean beforeDeadline() {
        return durationNanos == 0 || System.nanoTime() - startedAt < durationNanos;
    }

    Statistics statistics() {
        for (Thread thread : threads) {
            if (thread.isAlive()) {
                throw new IllegalStateException("Статистика доступна после остановки потоков");
            }
        }
        Throwable error = failure.get();
        if (error != null) {
            throw new IllegalStateException("Ошибка сортировщика", error);
        }
        long steps = 0;
        long swaps = 0;
        List<Long> perThread = new ArrayList<>();
        for (Worker worker : workers) {
            steps += worker.steps;
            swaps += worker.swaps;
            perThread.add(worker.steps);
        }
        return new Statistics(steps, swaps, List.copyOf(perThread));
    }

    @Override
    public void close() {
        for (Thread thread : threads) {
            thread.interrupt();
        }
        boolean interrupted = false;
        for (Thread thread : threads) {
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
