package lab2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--help")) {
            help();
            return;
        }
        try {
            Options options = Options.parse(args);
            if (options.mode().equals("benchmark")) {
                benchmark(options);
            } else {
                interactive(options);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.err.println("Список параметров: --help");
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Работа прервана");
            System.exit(130);
        } catch (IOException | IllegalStateException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void interactive(Options options) throws IOException, InterruptedException {
        SortableStrings list = create(options.implementation());
        Sorters sorters = new Sorters(list, options.threads(), options.insideMillis(), options.betweenMillis());
        System.out.printf("Список: %s, потоков: %d%n", options.implementation(), options.threads());
        System.out.println("Введите строки, пустая строка выводит список, EOF завершает работу");
        try (sorters) {
            sorters.start(0);
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    System.out.println("--- список ---");
                    int count = 0;
                    for (String value : list) {
                        System.out.println(value);
                        count++;
                    }
                    System.out.println("--- записей: " + count + " ---");
                } else {
                    addLine(list, line);
                }
            }
        }
        printStatistics(sorters.statistics());
    }

    static void addLine(SortableStrings list, String line) throws InterruptedException {
        int start = 0;
        int remaining = line.codePointCount(0, line.length());
        while (remaining > 0) {
            int length = Math.min(80, remaining);
            int end = line.offsetByCodePoints(start, length);
            list.addFirst(line.substring(start, end));
            start = end;
            remaining -= length;
        }
    }

    private static void benchmark(Options options) throws InterruptedException {
        List<String> input = input(options.size(), options.seed());
        Map<String, Integer> expected = frequencies(input);
        System.out.printf("n=%d p=%d T=%d мс a=%d мс b=%d мс seed=%d%n",
                options.size(), options.threads(), options.durationMillis(), options.insideMillis(),
                options.betweenMillis(), options.seed());
        printBounds(options);
        Map<String, List<Long>> results = new HashMap<>();
        results.put("linked", new ArrayList<>());
        results.put("array", new ArrayList<>());
        for (int repeat = 1; repeat <= options.repeats(); repeat++) {
            List<String> order = repeat % 2 == 1 ? List.of("linked", "array") : List.of("array", "linked");
            for (String implementation : order) {
                SortableStrings list = create(implementation);
                for (String value : input) {
                    list.addFirst(value);
                }
                Sorters sorters = new Sorters(list, options.threads(), options.insideMillis(), options.betweenMillis());
                try (sorters) {
                    sorters.start(options.durationMillis());
                    sorters.awaitDeadline();
                }
                Sorters.Statistics statistics = sorters.statistics();
                if (!frequencies(list.snapshot()).equals(expected)) {
                    throw new IllegalStateException("Нарушена сохранность записей: " + implementation);
                }
                results.get(implementation).add(statistics.steps());
                System.out.printf("Повтор %d, %s: ", repeat, implementation);
                printStatistics(statistics);
            }
        }
        for (String implementation : List.of("linked", "array")) {
            List<Long> counts = results.get(implementation);
            var summary = counts.stream().mapToLong(Long::longValue).summaryStatistics();
            System.out.printf(Locale.ROOT, "%s: среднее %.1f, минимум %d, максимум %d%n",
                    implementation, summary.getAverage(), summary.getMin(), summary.getMax());
        }
    }

    private static void printBounds(Options options) {
        if (options.size() < 2) {
            System.out.println("Теоретическое число шагов: 0 для обоих списков");
            return;
        }
        double totalDelay = options.insideMillis() + options.betweenMillis();
        double localBound = totalDelay == 0 ? Double.POSITIVE_INFINITY
                : options.threads() * (double) options.durationMillis() / totalDelay;
        double globalBound = options.insideMillis() == 0 ? localBound
                : Math.min(localBound, (double) options.durationMillis() / options.insideMillis());
        System.out.printf(Locale.ROOT, "Идеальные оценки без накладных расходов: linked ≲ %s, array ≲ %s%n",
                bound(localBound), bound(globalBound));
        System.out.println("Оценки асимптотические, старт и границы интервала дают погрешность порядка числа потоков");
    }

    private static String bound(double value) {
        return Double.isInfinite(value) ? "нет ограничения по задержкам" : String.format(Locale.ROOT, "%.1f", value);
    }

    private static void printStatistics(Sorters.Statistics statistics) {
        System.out.printf("шагов=%d, перестановок=%d, по потокам=%s%n",
                statistics.steps(), statistics.swaps(), statistics.perThread());
    }

    static List<String> input(int size, long seed) {
        Random random = new Random(seed);
        List<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(String.format(Locale.ROOT, "%08x", random.nextInt()));
        }
        return List.copyOf(values);
    }

    private static Map<String, Integer> frequencies(List<String> values) {
        Map<String, Integer> counts = new HashMap<>();
        for (String value : values) {
            counts.merge(value, 1, Integer::sum);
        }
        return counts;
    }

    private static SortableStrings create(String implementation) {
        return implementation.equals("linked") ? new LinkedStrings() : new ArrayStrings();
    }

    private static void help() {
        System.out.println("Java 21 | lab2.Main [параметр значение]");
        System.out.println("--mode interactive|benchmark  по умолчанию interactive");
        System.out.println("--implementation linked|array  список в interactive, по умолчанию linked");
        System.out.println("--threads 4  --inside-ms 1000  --between-ms 1000");
        System.out.println("--duration-ms 5000  --size 100  --repeats 3  --seed 42  параметры benchmark");
    }
}
