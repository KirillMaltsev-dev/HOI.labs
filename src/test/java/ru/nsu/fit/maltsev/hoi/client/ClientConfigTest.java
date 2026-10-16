package ru.nsu.fit.maltsev.hoi.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientConfigTest {
    @Test
    void parsesRequiredOptionsAndDefaults() {
        ClientConfig config = ClientConfig.parse(arguments());
        assertEquals("Ann", config.name());
        assertEquals("localhost", config.host());
        assertEquals(8080, config.port());
        assertEquals(Path.of("client-output"), config.outputDirectory());
        assertEquals(0, config.readDelaySeconds());
        assertFalse(config.disconnectWithoutRead());
    }

    @Test
    void parsesOptionalValuesAndStandaloneFlagInAnyPosition() {
        List<String> args = new ArrayList<>(List.of("--disconnect-without-read"));
        args.addAll(List.of(arguments()));
        args.addAll(List.of("--read-delay-seconds", "7", "--output-dir", "results"));
        ClientConfig config = ClientConfig.parse(args.toArray(String[]::new));
        assertEquals(7, config.readDelaySeconds());
        assertEquals(Path.of("results"), config.outputDirectory());
        assertTrue(config.disconnectWithoutRead());
    }

    @ParameterizedTest
    @ValueSource(strings = {" Ann ", " ", "--port", "--disconnect-without-read", "../CON:a\\b"})
    void preservesAnyValidNameIncludingValuesThatLookLikeOptions(String name) {
        assertEquals(name, ClientConfig.parse(replace("--name", name)).name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a\nb", "я", "\u007F"})
    void rejectsInvalidName(String name) {
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.parse(replace("--name", name)));
    }

    @Test
    void acceptsMaximumNameAndRejectsOversizedName() {
        assertEquals(255, ClientConfig.parse(replace("--name", "a".repeat(255))).name().length());
        assertThrows(IllegalArgumentException.class,
                () -> ClientConfig.parse(replace("--name", "a".repeat(256))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "65536", "2147483648", "abc", "1.5"})
    void rejectsInvalidPort(String value) {
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.parse(replace("--port", value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "2147483648", "abc", "1.5", ""})
    void rejectsInvalidDelay(String value) {
        List<String> args = new ArrayList<>(List.of(arguments()));
        args.addAll(List.of("--read-delay-seconds", value));
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.parse(args.toArray(String[]::new)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--name", "--host", "--port"})
    void rejectsMissingRequiredOption(String option) {
        List<String> args = new ArrayList<>(List.of(arguments()));
        int index = args.indexOf(option);
        args.remove(index + 1);
        args.remove(index);
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.parse(args.toArray(String[]::new)));
    }

    @Test
    void rejectsUnknownDuplicateAndValuelessOptions() {
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.parse(new String[]{"--unknown", "1"}));
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.parse(new String[]{"--name"}));
        List<String> duplicate = new ArrayList<>(List.of(arguments()));
        duplicate.addAll(List.of("--port", "9000"));
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.parse(duplicate.toArray(String[]::new)));
        List<String> flags = new ArrayList<>(List.of(arguments()));
        flags.addAll(List.of("--disconnect-without-read", "--disconnect-without-read"));
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.parse(flags.toArray(String[]::new)));
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.parse(replace("--host", " ")));
        List<String> output = new ArrayList<>(List.of(arguments()));
        output.addAll(List.of("--output-dir", " "));
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.parse(output.toArray(String[]::new)));
    }

    private static String[] arguments() {
        return new String[]{"--name", "Ann", "--host", "localhost", "--port", "8080"};
    }

    private static String[] replace(String option, String value) {
        String[] args = arguments();
        args[Arrays.asList(args).indexOf(option) + 1] = value;
        return args;
    }
}
