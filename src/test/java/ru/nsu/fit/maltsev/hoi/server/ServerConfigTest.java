package ru.nsu.fit.maltsev.hoi.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {
    @Test
    void parsesRequiredOptionsAndDefaultBindAddress() {
        ServerConfig config = ServerConfig.parse(arguments());
        assertEquals("0.0.0.0", config.bindAddress());
        assertEquals(8080, config.port());
        assertEquals(4, config.workerCount());
        assertEquals(Path.of("keys/signing.key"), config.signingKey());
        assertEquals("CN=J1 Issuer,O=NSU", config.issuer());
    }

    @Test
    void acceptsExplicitBindAndWorkerCountAboveProcessors() {
        List<String> args = new ArrayList<>(List.of(arguments()));
        args.addAll(List.of("--bind", "127.0.0.1"));
        int workers = Runtime.getRuntime().availableProcessors() + 1;
        args.set(args.indexOf("--workers") + 1, Integer.toString(workers));
        ServerConfig config = ServerConfig.parse(args.toArray(String[]::new));
        assertEquals("127.0.0.1", config.bindAddress());
        assertEquals(workers, config.workerCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "65536", "2147483648", "abc", "1.5"})
    void rejectsInvalidPort(String port) {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse(replace("--port", port)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "2147483648", "abc", "1.5"})
    void rejectsInvalidWorkerCount(String count) {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse(replace("--workers", count)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--port", "--workers", "--signing-key", "--issuer"})
    void rejectsMissingRequiredOption(String option) {
        List<String> args = new ArrayList<>(List.of(arguments()));
        int index = args.indexOf(option);
        args.remove(index + 1);
        args.remove(index);
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse(args.toArray(String[]::new)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not a distinguished name"})
    void rejectsInvalidIssuer(String issuer) {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse(replace("--issuer", issuer)));
    }

    @Test
    void rejectsUnknownDuplicateAndValuelessOptions() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse(new String[]{"--unknown", "1"}));
        List<String> duplicate = new ArrayList<>(List.of(arguments()));
        duplicate.addAll(List.of("--port", "9000"));
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.parse(duplicate.toArray(String[]::new)));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse(new String[]{"--port"}));
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.parse(new String[]{"--port", "--workers"}));
        List<String> blankBind = new ArrayList<>(List.of(arguments()));
        blankBind.addAll(List.of("--bind", " "));
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.parse(blankBind.toArray(String[]::new)));
    }

    @Test
    void missingSigningKeyFailsBeforeServerStarts(@TempDir Path directory) {
        String[] args = replace("--signing-key", directory.resolve("missing.key").toString());
        assertThrows(NoSuchFileException.class, () -> ServerMain.main(args));
    }

    private static String[] arguments() {
        return new String[]{"--port", "8080", "--workers", "4", "--signing-key", "keys/signing.key",
                "--issuer", "CN=J1 Issuer,O=NSU"};
    }

    private static String[] replace(String option, String value) {
        String[] args = arguments();
        args[Arrays.asList(args).indexOf(option) + 1] = value;
        return args;
    }
}
