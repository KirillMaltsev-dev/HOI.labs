package lab2;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class ListChecks {
    private ListChecks() { }

    public static void main(String[] args) throws Exception {
        for (Supplier<SortableStrings> factory : List.<Supplier<SortableStrings>>of(
                LinkedStrings::new, ArrayStrings::new)) {
            basics(factory.get());
            concurrentAccess(factory.get());
            betweenSteps(factory.get());
        }
        interruptedSnapshot();
        System.out.println("Проверки списков пройдены");
    }

    private static void basics(SortableStrings list) throws Exception {
        require(list.snapshot().isEmpty(), "Пустой список");
        require(list.step(0, 0) == SortableStrings.Step.NO_PAIR, "Нет пары в пустом списке");
        list.addFirst("я");
        require(list.step(0, 0) == SortableStrings.Step.NO_PAIR, "Нет пары из одного узла");
        list.addFirst("а");
        list.addFirst("а");
        list.addFirst("б");
        require(list.snapshot().equals(List.of("б", "а", "а", "я")), "Добавление в начало");
        var iterator = list.iterator();
        list.addFirst("в");
        List<String> previous = new ArrayList<>();
        iterator.forEachRemaining(previous::add);
        require(previous.equals(List.of("б", "а", "а", "я")), "Итератор хранит снимок");
        int comparisons = 0;
        for (int pass = 0; pass < 4; pass++) {
            for (int position = 0; position < 4; position++) {
                require(list.step(position, 0) != SortableStrings.Step.NO_PAIR, "Пара найдена");
                comparisons++;
            }
            require(list.step(4, 0) == SortableStrings.Step.NO_PAIR, "Конец прохода");
        }
        require(comparisons == 16, "R(n-1) сравнений");
        List<String> printed = new ArrayList<>();
        for (String value : list) {
            printed.add(value);
        }
        require(printed.equals(List.of("а", "а", "б", "в", "я")), "Порядок и дубликаты");
    }

    private static void concurrentAccess(SortableStrings list) throws Exception {
        for (int i = 0; i < 40; i++) {
            list.addFirst("base-" + i);
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> sorters = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread thread = daemon(() -> {
                try {
                    int position = 0;
                    while (!Thread.currentThread().isInterrupted()) {
                        position = list.step(position, 0) == SortableStrings.Step.NO_PAIR
                                ? 0 : position + 1;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            sorters.add(thread);
            thread.start();
        }
        try {
            within(() -> {
                int lastSize = 40;
                for (int i = 0; i < 100; i++) {
                    list.addFirst("new-" + i);
                    List<String> snapshot = list.snapshot();
                    require(snapshot.size() == ++lastSize, "Сохранено число записей");
                    require(new HashSet<>(snapshot).size() == snapshot.size(), "Нет повторного обхода узла");
                    for (int j = 0; j < 40; j++) {
                        require(snapshot.contains("base-" + j), "Исходная запись сохранена");
                    }
                    for (int j = 0; j <= i; j++) {
                        require(snapshot.contains("new-" + j), "Введённая запись сохранена");
                    }
                }
                return null;
            });
        } finally {
            for (Thread sorter : sorters) {
                sorter.interrupt();
            }
            for (Thread sorter : sorters) {
                join(sorter);
            }
        }
        require(failure.get() == null, "Сортировщики завершились без ошибок: " + failure.get());
        List<String> expected = new ArrayList<>(list.snapshot());
        Collections.sort(expected);
        for (int pass = 0; pass < expected.size() - 1; pass++) {
            for (int position = 0; position < expected.size() - 1; position++) {
                list.step(position, 0);
            }
        }
        require(list.snapshot().equals(expected), "Сортировка после конкурентной работы");
    }

    private static void betweenSteps(SortableStrings list) throws Exception {
        list.addFirst("а");
        list.addFirst("б");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sorter = daemon(() -> {
            try {
                list.step(0, 0);
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        sorter.start();
        try {
            awaitState(sorter, Thread.State.TIMED_WAITING);
            require(within(list::snapshot).equals(List.of("а", "б")), "Снимок между шагами");
        } finally {
            sorter.interrupt();
            join(sorter);
        }
        require(failure.get() == null, "Ошибка шага: " + failure.get());
    }

    private static void interruptedSnapshot() throws Exception {
        LinkedStrings list = new LinkedStrings();
        for (String value : List.of("а", "б", "в")) {
            list.addFirst(value);
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sorter = daemon(() -> {
            try {
                list.step(1, 10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        Thread reader = daemon(() -> {
            try {
                list.snapshot();
                failure.set(new AssertionError("Снимок должен ожидать занятую запись"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        sorter.start();
        try {
            awaitState(sorter, Thread.State.TIMED_WAITING);
            reader.start();
            awaitState(reader, Thread.State.WAITING);
            reader.interrupt();
            join(reader);
            within(() -> {
                list.addFirst("г");
                return null;
            });
        } finally {
            reader.interrupt();
            sorter.interrupt();
            join(sorter);
            if (reader.getState() != Thread.State.NEW) {
                join(reader);
            }
        }
        require(within(list::snapshot).equals(List.of("г", "в", "б", "а")),
                "Освобождены все частично захваченные блокировки");
        require(failure.get() == null, "Ошибка прерывания: " + failure.get());
    }

    private static <T> T within(Callable<T> action) throws Exception {
        var executor = Executors.newSingleThreadExecutor(task -> daemon(task));
        try {
            return executor.submit(action).get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("Ошибка конкурентной операции", e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitState(Thread thread, Thread.State state) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (thread.getState() != state && System.nanoTime() < deadline) {
            require(thread.isAlive(), "Поток завершился до ожидаемого состояния");
            Thread.sleep(1);
        }
        require(thread.getState() == state, "Ожидалось состояние " + state);
    }

    private static Thread daemon(Runnable task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        return thread;
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(5_000);
        require(!thread.isAlive(), "Поток не завершился");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
