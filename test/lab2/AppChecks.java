package lab2;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class AppChecks {
    private AppChecks() { }

    public static void main(String[] args) throws Exception {
        options();
        splitting();
        require(Main.input(100, 42).equals(Main.input(100, 42)), "Одинаковые исходные данные");
        for (Supplier<SortableStrings> factory : List.<Supplier<SortableStrings>>of(
                LinkedStrings::new, ArrayStrings::new)) {
            measurement(factory.get());
            noPairs(factory.get());
            cancellation(factory.get());
        }
        System.out.println("Проверки запуска и измерений пройдены");
    }

    private static void options() {
        Options defaults = Options.parse(new String[0]);
        require(defaults.threads() == 4 && defaults.implementation().equals("linked"), "Параметры по умолчанию");
        Options custom = Options.parse(new String[]{"--threads", "2", "--inside-ms", "0", "--seed", "-7"});
        require(custom.threads() == 2 && custom.insideMillis() == 0 && custom.seed() == -7, "Чтение параметров");
        for (String[] args : List.of(
                new String[]{"--threads", "0"},
                new String[]{"--inside-ms", "-1"},
                new String[]{"--duration-ms", "0"},
                new String[]{"--threads", "1", "--threads", "2"},
                new String[]{"--mode", "unknown"},
                new String[]{"--size", "abc"},
                new String[]{"--missing", "1"},
                new String[]{"--threads"})) {
            try {
                Options.parse(args);
                throw new AssertionError("Некорректные параметры приняты");
            } catch (IllegalArgumentException expected) {
                require(expected.getMessage() != null, "Сообщение об ошибке параметра");
            }
        }
    }

    private static void splitting() throws InterruptedException {
        for (SortableStrings list : List.of(new LinkedStrings(), new ArrayStrings())) {
            String line = "я".repeat(79) + "🙂".repeat(82);
            Main.addLine(list, line);
            List<String> chunks = new ArrayList<>(list.snapshot().reversed());
            require(chunks.size() == 3, "Разбиение длинной строки");
            require(String.join("", chunks).equals(line), "Сохранены все символы");
            require(chunks.getFirst().codePointCount(0, chunks.getFirst().length()) == 80,
                    "Суррогатная пара не разрезана");
            for (String chunk : chunks) {
                require(chunk.codePointCount(0, chunk.length()) <= 80, "Не больше 80 символов");
            }
        }
    }

    private static void measurement(SortableStrings list) throws InterruptedException {
        List<String> input = Main.input(20, 42);
        for (String value : input) {
            list.addFirst(value);
        }
        Sorters sorters = new Sorters(list, 4, 1, 1);
        long started = System.nanoTime();
        try (sorters) {
            sorters.start(200);
            sorters.awaitDeadline();
        }
        Sorters.Statistics stats = sorters.statistics();
        require(stats.steps() > 0, "Шаги измерены");
        require(stats.steps() == stats.perThread().stream().mapToLong(Long::longValue).sum(),
                "Сумма шагов всех потоков");
        require(stats.swaps() <= stats.steps(), "Перестановки не превышают число шагов");
        require(System.nanoTime() - started >= 200_000_000L, "Выдержано время измерения");
        require(list.snapshot().size() == input.size(), "Число записей после остановки");
        require(sorters.statistics().equals(stats), "Счётчики не изменяются после остановки");
        require(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.isAlive() && t.getName().startsWith("sorter-")), "Все сортировщики остановлены");
    }

    private static void noPairs(SortableStrings list) throws InterruptedException {
        for (int size = 0; size <= 1; size++) {
            if (size == 1) {
                list.addFirst("единственная запись");
            }
            Sorters sorters = new Sorters(list, 2, 0, 0);
            try (sorters) {
                sorters.start(20);
                sorters.awaitDeadline();
            }
            require(sorters.statistics().steps() == 0, "Нет шагов без пары");
        }
    }

    private static void cancellation(SortableStrings list) throws InterruptedException {
        list.addFirst("а");
        list.addFirst("б");
        Sorters sorters = new Sorters(list, 4, 60_000, 0);
        long started = System.nanoTime();
        try (sorters) {
            sorters.start(30);
            sorters.awaitDeadline();
        }
        require(sorters.statistics().steps() == 0, "Незавершённые шаги не учитываются");
        require(System.nanoTime() - started < 5_000_000_000L, "Задержка прерывается при остановке");
        require(list.snapshot().equals(List.of("б", "а")), "Прерванный шаг не изменил ссылки");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
