package ru.nsu.fit.maltsev.hoi.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolCodec;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolException;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolResponse;
import ru.nsu.fit.maltsev.hoi.protocol.ResponseStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class KeyClientTest {
    private static final byte[] SUCCESS = {0, 0, 0, 0, 2, 65, 66, 0, 0, 0, 1, 67};

    @TempDir
    Path directory;

    @Test
    void sendsTerminatedNameAndReadsFragmentedResponseOverDnsHost() throws Exception {
        try (Peer peer = new Peer(socket -> {
            assertEquals(" Ann ", readName(socket));
            for (byte value : SUCCESS) {
                socket.getOutputStream().write(value);
                socket.getOutputStream().flush();
            }
        })) {
            var response = new KeyClient().fetch(peer.config(directory, 0, false)).orElseThrow();
            assertEquals(new ProtocolResponse.Success(new byte[]{65, 66}, new byte[]{67}), response);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ResponseStatus.class, names = {"INVALID_REQUEST", "GENERATION_FAILED"})
    void reportsServerErrorWithStatusAndMessage(ResponseStatus status) throws Exception {
        var response = new ProtocolResponse.Error(status, "Ошибка запроса");
        try (Peer peer = new Peer(socket -> {
            readName(socket);
            socket.getOutputStream().write(ProtocolCodec.encodeResponse(response).array());
        })) {
            var error = assertThrows(ServerResponseException.class,
                    () -> new KeyClient().fetch(peer.config(directory, 0, false)));
            assertEquals(status, error.status());
            assertTrue(error.getMessage().contains("Ошибка запроса"));
        }
    }

    static Stream<byte[]> malformedResponses() {
        return Stream.of(new byte[0], new byte[]{3}, new byte[]{0, 0, 0, 0, 0},
                new byte[]{0, 0, 1, 0, 1}, new byte[]{2, 0, 0, 4, 1},
                new byte[]{0, 0, 0, 0, 2, 65}, new byte[]{0, 0, 0, 0, 2, 65, 66, 0, 0},
                new byte[]{1, 0, 0, 0, 1, (byte) 0xFF},
                new byte[]{0, 0, 0, 0, 2, 65, 66, 0, 0, 0, 1, 67, 42});
    }

    @ParameterizedTest
    @MethodSource("malformedResponses")
    void rejectsMalformedTruncatedOrTrailingResponse(byte[] response) throws Exception {
        try (Peer peer = new Peer(socket -> {
            readName(socket);
            socket.getOutputStream().write(response);
        })) {
            assertThrows(ProtocolException.class,
                    () -> new KeyClient().fetch(peer.config(directory, 0, false)));
        }
    }

    @Test
    void pausesAfterSendingRequestAndBeforeReading() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicInteger observedDelay = new AtomicInteger(-1);
        try (Peer peer = new Peer(socket -> {
            assertEquals(" Ann ", readName(socket));
            received.countDown();
            socket.getOutputStream().write(SUCCESS);
        })) {
            KeyClient client = new KeyClient(seconds -> {
                assertTrue(received.await(5, TimeUnit.SECONDS));
                observedDelay.set(seconds);
            });
            assertTrue(client.fetch(peer.config(directory, 7, false)).isPresent());
            assertEquals(7, observedDelay.get());
        }
    }

    @Test
    void disconnectFlagSkipsDelayAndResponseReading() throws Exception {
        try (Peer peer = new Peer(socket -> {
            assertEquals(" Ann ", readName(socket));
            assertEquals(-1, socket.getInputStream().read());
        })) {
            KeyClient client = new KeyClient(seconds -> fail("Delay must be skipped"));
            assertTrue(client.fetch(peer.config(directory, 7, true)).isEmpty());
        }
    }

    @Test
    void interruptionDuringDelayClosesConnection() throws Exception {
        try (Peer peer = new Peer(socket -> {
            readName(socket);
            assertEquals(-1, socket.getInputStream().read());
        })) {
            KeyClient client = new KeyClient(seconds -> {
                throw new InterruptedException("test interruption");
            });
            assertThrows(InterruptedException.class, () -> client.fetch(peer.config(directory, 1, false)));
        }
    }

    @Test
    void mainDoesNotCreateOutputOnServerError() throws Exception {
        Path output = directory.resolve("must-not-exist");
        try (Peer peer = new Peer(socket -> {
            readName(socket);
            socket.getOutputStream().write(ProtocolCodec.encodeResponse(new ProtocolResponse.Error(
                    ResponseStatus.GENERATION_FAILED, "generation failed")).array());
        })) {
            assertThrows(ServerResponseException.class, () -> ClientMain.main(peer.arguments(output)));
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void mainDisconnectModeDoesNotCreateOutput() throws Exception {
        Path output = directory.resolve("must-not-exist");
        try (Peer peer = new Peer(socket -> {
            readName(socket);
            assertEquals(-1, socket.getInputStream().read());
        })) {
            String[] base = peer.arguments(output);
            String[] args = java.util.Arrays.copyOf(base, base.length + 3);
            args[base.length] = "--read-delay-seconds";
            args[base.length + 1] = "7";
            args[base.length + 2] = "--disconnect-without-read";
            ClientMain.main(args);
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void mainRejectsInvalidPemBeforeCreatingOutput() throws Exception {
        Path output = directory.resolve("must-not-exist");
        try (Peer peer = new Peer(socket -> {
            readName(socket);
            socket.getOutputStream().write(SUCCESS);
        })) {
            assertThrows(IOException.class, () -> ClientMain.main(peer.arguments(output)));
            assertFalse(Files.exists(output));
        }
    }

    private static String readName(Socket socket) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = socket.getInputStream().read()) != 0) {
            if (value == -1) {
                throw new IOException("Request ended before terminator");
            }
            bytes.write(value);
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    @FunctionalInterface
    private interface PeerAction {
        void run(Socket socket) throws Exception;
    }

    private static final class Peer implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<?> task;

        private Peer(PeerAction action) throws IOException {
            listener = new ServerSocket(0, 50, InetAddress.getByName("localhost"));
            task = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    action.run(socket);
                }
                return null;
            });
        }

        private ClientConfig config(Path output, int delay, boolean disconnect) {
            return new ClientConfig(" Ann ", "localhost", listener.getLocalPort(), output, delay, disconnect);
        }

        private String[] arguments(Path output) {
            return new String[]{"--name", " Ann ", "--host", "localhost", "--port",
                    Integer.toString(listener.getLocalPort()), "--output-dir", output.toString()};
        }

        @Override
        public void close() throws Exception {
            try {
                task.get(5, TimeUnit.SECONDS);
            } finally {
                listener.close();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }
}
