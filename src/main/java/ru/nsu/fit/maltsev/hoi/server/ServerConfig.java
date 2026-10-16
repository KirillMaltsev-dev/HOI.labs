package ru.nsu.fit.maltsev.hoi.server;

import org.bouncycastle.asn1.x500.X500Name;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ServerConfig(String bindAddress, int port, int workerCount, Path signingKey, String issuer) {
    private static final Set<String> OPTIONS = Set.of(
            "--bind", "--port", "--workers", "--signing-key", "--issuer");
    public static final String USAGE = "Usage: ServerMain [--bind <address>] --port <1..65535> "
            + "--workers <positive-count> --signing-key <path> --issuer <X.500-name>";

    public ServerConfig {
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(signingKey, "signingKey");
        Objects.requireNonNull(issuer, "issuer");
        if (bindAddress.isBlank()) {
            throw new IllegalArgumentException("Bind address must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be 1..65535");
        }
        if (workerCount < 1) {
            throw new IllegalArgumentException("Worker count must be positive");
        }
        if (issuer.isBlank() || new X500Name(issuer).getRDNs().length == 0) {
            throw new IllegalArgumentException("Issuer must be a nonempty X.500 name");
        }
    }

    public static ServerConfig parse(String[] args) {
        Objects.requireNonNull(args, "args");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String option = args[i];
            if (!OPTIONS.contains(option)) {
                throw new IllegalArgumentException("Unknown option: " + option + "\n" + USAGE);
            }
            if (i + 1 == args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + option + "\n" + USAGE);
            }
            if (values.putIfAbsent(option, args[i + 1]) != null) {
                throw new IllegalArgumentException("Duplicate option: " + option);
            }
        }
        return new ServerConfig(values.getOrDefault("--bind", "0.0.0.0"),
                integer(required(values, "--port"), "port"),
                integer(required(values, "--workers"), "workers"),
                Path.of(required(values, "--signing-key")), required(values, "--issuer"));
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
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer for " + option + ": " + value, exception);
        }
    }
}
