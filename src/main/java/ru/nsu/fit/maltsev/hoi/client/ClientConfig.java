package ru.nsu.fit.maltsev.hoi.client;

import ru.nsu.fit.maltsev.hoi.protocol.ClientName;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ClientConfig(String name, String host, int port, Path outputDirectory,
                           int readDelaySeconds, boolean disconnectWithoutRead) {
    private static final String DISCONNECT = "--disconnect-without-read";
    private static final Set<String> OPTIONS = Set.of(
            "--name", "--host", "--port", "--output-dir", "--read-delay-seconds", DISCONNECT);
    public static final String USAGE = "Usage: ClientMain --name <ASCII-name> --host <address-or-DNS> "
            + "--port <1..65535> [--output-dir <path>] [--read-delay-seconds <nonnegative-integer>] "
            + "[--disconnect-without-read]";

    public ClientConfig {
        new ClientName(name);
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        if (host.isBlank()) {
            throw new IllegalArgumentException("Host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be 1..65535");
        }
        if (readDelaySeconds < 0) {
            throw new IllegalArgumentException("Read delay must not be negative");
        }
    }

    public static ClientConfig parse(String[] args) {
        Objects.requireNonNull(args, "args");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (!OPTIONS.contains(option)) {
                throw new IllegalArgumentException("Unknown option: " + option + "\n" + USAGE);
            }
            if (values.containsKey(option)) {
                throw new IllegalArgumentException("Duplicate option: " + option);
            }
            if (DISCONNECT.equals(option)) {
                values.put(option, "true");
                continue;
            }
            if (i + 1 == args.length) {
                throw new IllegalArgumentException("Missing value for " + option + "\n" + USAGE);
            }
            values.put(option, args[++i]);
        }
        String name = values.get("--name");
        if (name == null) {
            throw new IllegalArgumentException("Missing value for --name\n" + USAGE);
        }
        return new ClientConfig(name, required(values, "--host"),
                integer(required(values, "--port"), "port"),
                Path.of(values.containsKey("--output-dir")
                        ? required(values, "--output-dir") : "client-output"),
                integer(values.getOrDefault("--read-delay-seconds", "0"), "read-delay-seconds"),
                values.containsKey(DISCONNECT));
    }

    private static String required(Map<String, String> values, String option) {
        String value = values.get(option);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing value for " + option + "\n" + USAGE);
        }
        return value;
    }

    private static int integer(String value, String option) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid integer for " + option + ": " + value, failure);
        }
    }
}
