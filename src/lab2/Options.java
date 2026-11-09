package lab2;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

record Options(String mode, String implementation, int threads, long insideMillis,
               long betweenMillis, long durationMillis, int size, int repeats, long seed) {
    static Options parse(String[] args) {
        Set<String> names = Set.of("mode", "implementation", "threads", "inside-ms", "between-ms",
                "duration-ms", "size", "repeats", "seed");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || !names.contains(args[i].substring(2))) {
                throw new IllegalArgumentException("Неизвестный параметр: " + args[i]);
            }
            if (i + 1 == args.length) {
                throw new IllegalArgumentException("Нет значения для " + args[i]);
            }
            if (values.put(args[i].substring(2), args[i + 1]) != null) {
                throw new IllegalArgumentException("Параметр повторяется: " + args[i]);
            }
        }
        String mode = values.getOrDefault("mode", "interactive");
        String implementation = values.getOrDefault("implementation", "linked");
        if (!Set.of("interactive", "benchmark").contains(mode)) {
            throw new IllegalArgumentException("Режим: interactive или benchmark");
        }
        if (!Set.of("linked", "array").contains(implementation)) {
            throw new IllegalArgumentException("Реализация: linked или array");
        }
        return new Options(mode, implementation,
                (int) number(values, "threads", 4, 1, 256),
                number(values, "inside-ms", 1000, 0, 60_000),
                number(values, "between-ms", 1000, 0, 60_000),
                number(values, "duration-ms", 5000, 1, 3_600_000),
                (int) number(values, "size", 100, 0, 100_000),
                (int) number(values, "repeats", 3, 1, 100),
                number(values, "seed", 42, Long.MIN_VALUE, Long.MAX_VALUE));
    }

    private static long number(Map<String, String> values, String name, long fallback,
                               long minimum, long maximum) {
        String text = values.get(name);
        if (text == null) {
            return fallback;
        }
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Ожидалось целое число для --" + name);
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("--" + name + ": допустимо от " + minimum + " до " + maximum);
        }
        return value;
    }
}
