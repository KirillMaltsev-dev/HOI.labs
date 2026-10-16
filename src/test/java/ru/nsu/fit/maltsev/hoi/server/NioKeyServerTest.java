package ru.nsu.fit.maltsev.hoi.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolCodec;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolResponse;
import ru.nsu.fit.maltsev.hoi.protocol.ResponseDecoder;
import ru.nsu.fit.maltsev.hoi.protocol.ResponseStatus;
import ru.nsu.fit.maltsev.hoi.service.CredentialsGenerator;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class NioKeyServerTest {
    private static final ProtocolResponse.Success SUCCESS = new ProtocolResponse.Success(
            new byte[]{65, 66}, new byte[]{67});

    @Test
    void acceptsFragmentedRequestAndClientReadsOneByteAtATime() throws Exception {
        AtomicReference<String> receivedName = new AtomicReference<>();
        try (TestServer server = new TestServer(name -> {
            receivedName.set(name);
            return SUCCESS;
        }); Socket socket = server.connect()) {
            byte[] request = ProtocolCodec.encodeRequest(" Ann ").array();
            for (byte value : request) {
                socket.getOutputStream().write(value);
                socket.getOutputStream().flush();
            }
            assertEquals(SUCCESS, readReply(socket, 1));
            assertEquals(" Ann ", receivedName.get());
        }
    }

    static Stream<byte[]> invalidRequests() {
        byte[] oversized = new byte[256];
        Arrays.fill(oversized, (byte) 65);
        return Stream.of(new byte[]{0}, new byte[]{1}, new byte[]{127}, new byte[]{(byte) 128}, oversized);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void sendsInvalidRequestWithoutGeneratingKeys(byte[] request) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (TestServer server = new TestServer(name -> {
            calls.incrementAndGet();
            return SUCCESS;
        }); Socket socket = server.connect()) {
            socket.getOutputStream().write(request);
            assertEquals(ResponseStatus.INVALID_REQUEST, readReply(socket, 17).status());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void reportsEofBeforeTerminator() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (TestServer server = new TestServer(name -> {
            calls.incrementAndGet();
            return SUCCESS;
        }); Socket socket = server.connect()) {
            socket.getOutputStream().write("Ann".getBytes(StandardCharsets.US_ASCII));
            socket.shutdownOutput();
            assertEquals(ResponseStatus.INVALID_REQUEST, readReply(socket, 17).status());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void halfClosedRequestCanStillReceiveResponse() throws Exception {
        try (TestServer server = new TestServer(name -> SUCCESS); Socket socket = server.connect()) {
            send(socket, "Ann");
            socket.shutdownOutput();
            assertEquals(SUCCESS, readReply(socket, 17));
        }
    }

    @Test
    void sharesPendingGenerationAndServesCachedResponse() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TestServer server = new TestServer(name -> {
            calls.incrementAndGet();
            started.countDown();
            release.await();
            return SUCCESS;
        }); Socket first = server.connect(); Socket second = server.connect()) {
            try {
                send(first, "Ann");
                await(started);
                send(second, "Ann");
                release.countDown();
                assertEquals(SUCCESS, readReply(first, 2));
                assertEquals(SUCCESS, readReply(second, 3));
                try (Socket cached = server.connect()) {
                    send(cached, "Ann");
                    assertEquals(SUCCESS, readReply(cached, 17));
                }
                assertEquals(1, calls.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void sendsGenerationErrorAndAllowsNewAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (TestServer server = new TestServer(name -> {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("test generation failure");
            }
            return SUCCESS;
        })) {
            try (Socket failed = server.connect()) {
                send(failed, "Ann");
                assertEquals(ResponseStatus.GENERATION_FAILED, readReply(failed, 17).status());
            }
            try (Socket retry = server.connect()) {
                send(retry, "Ann");
                assertEquals(SUCCESS, readReply(retry, 17));
            }
            assertEquals(2, calls.get());
        }
    }

    @Test
    void resetDuringGenerationDoesNotCancelSharedAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TestServer server = new TestServer(name -> {
            calls.incrementAndGet();
            started.countDown();
            release.await();
            return SUCCESS;
        }); Socket disconnected = server.connect()) {
            try {
                disconnected.setSoLinger(true, 0);
                send(disconnected, "Ann");
                await(started);
                disconnected.close();
                try (Socket remaining = server.connect()) {
                    send(remaining, "Ann");
                    release.countDown();
                    assertEquals(SUCCESS, readReply(remaining, 17));
                }
                assertEquals(1, calls.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void resetBeforeCompleteRequestDoesNotStopServer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (TestServer server = new TestServer(name -> {
            calls.incrementAndGet();
            return SUCCESS;
        })) {
            try (Socket reset = server.connect()) {
                reset.setSoLinger(true, 0);
                reset.getOutputStream().write(65);
            }
            try (Socket healthy = server.connect()) {
                send(healthy, "Ann");
                assertEquals(SUCCESS, readReply(healthy, 17));
            }
            assertEquals(1, calls.get());
        }
    }

    @Test
    void servesOneHundredSimultaneousConnectionsWithOneGeneration() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TestServer server = new TestServer(name -> {
            calls.incrementAndGet();
            started.countDown();
            release.await();
            return SUCCESS;
        })) {
            List<Socket> sockets = new ArrayList<>();
            try {
                for (int i = 0; i < 100; i++) {
                    Socket socket = server.connect();
                    sockets.add(socket);
                    send(socket, "Ann");
                }
                await(started);
                release.countDown();
                for (Socket socket : sockets) {
                    assertEquals(SUCCESS, readReply(socket, 17));
                }
                assertEquals(1, calls.get());
            } finally {
                release.countDown();
                for (Socket socket : sockets) {
                    socket.close();
                }
            }
        }
    }

    @Test
    void nonReadingClientsDoNotDelayAnotherClient() throws Exception {
        ProtocolResponse.Success large = largeResponse();
        CountDownLatch generated = new CountDownLatch(1);
        try (TestServer server = new TestServer(name -> {
            if (name.equals("Slow")) {
                generated.countDown();
                return large;
            }
            return SUCCESS;
        })) {
            List<Socket> slow = new ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) {
                    Socket socket = new Socket();
                    slow.add(socket);
                    socket.setReceiveBufferSize(1024);
                    socket.connect(server.server.localAddress(), 5000);
                    send(socket, "Slow");
                }
                await(generated);
                try (Socket fast = server.connect()) {
                    send(fast, "Fast");
                    assertEquals(SUCCESS, readReply(fast, 17));
                }
            } finally {
                for (Socket socket : slow) {
                    socket.close();
                }
            }
        }
    }

    @Test
    void transmitsMaximumSizedBlocksCompletely() throws Exception {
        ProtocolResponse.Success expected = largeResponse();
        try (TestServer server = new TestServer(name -> expected); Socket socket = server.connect()) {
            send(socket, "Ann");
            assertEquals(expected, readReply(socket, 257));
        }
    }

    @Test
    void stoppingServerClosesPendingConnectionAndInterruptsWorker() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        try (TestServer server = new TestServer(name -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return SUCCESS;
            } finally {
                stopped.countDown();
            }
        }); Socket socket = server.connect()) {
            send(socket, "Ann");
            await(started);
            server.server.close();
            server.loop.get(5, TimeUnit.SECONDS);
            assertEquals(-1, socket.getInputStream().read());
            await(stopped);
            assertDoesNotThrow(server.server::close);
        }
    }

    @Test
    void closingBeforeRunReleasesPortAndPreventsRun() throws Exception {
        NioKeyServer server = new NioKeyServer(new InetSocketAddress("127.0.0.1", 0), 1, name -> SUCCESS);
        InetSocketAddress address = server.localAddress();
        server.close();
        assertDoesNotThrow(server::close);
        assertThrows(IllegalStateException.class, server::run);
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.bind(address);
        }
    }

    @Test
    void bindFailureDoesNotAffectExistingServer() throws Exception {
        try (TestServer server = new TestServer(name -> SUCCESS); Socket socket = server.connect()) {
            assertThrows(IOException.class,
                    () -> new NioKeyServer(server.server.localAddress(), 1, name -> SUCCESS));
            send(socket, "Ann");
            assertEquals(SUCCESS, readReply(socket, 17));
        }
    }

    private static ProtocolResponse.Success largeResponse() {
        byte[] key = new byte[65_536];
        byte[] certificate = new byte[65_536];
        Arrays.fill(key, (byte) 65);
        Arrays.fill(certificate, (byte) 66);
        return new ProtocolResponse.Success(key, certificate);
    }

    private static void send(Socket socket, String name) throws IOException {
        socket.getOutputStream().write(ProtocolCodec.encodeRequest(name).array());
        socket.getOutputStream().flush();
    }

    private static ProtocolResponse readReply(Socket socket, int chunkSize) throws IOException {
        ResponseDecoder decoder = new ResponseDecoder();
        ProtocolResponse response = null;
        byte[] chunk = new byte[chunkSize];
        int count;
        while ((count = socket.getInputStream().read(chunk)) != -1) {
            assertNull(response, "Unexpected bytes after response");
            ByteBuffer input = ByteBuffer.wrap(chunk, 0, count);
            response = decoder.feed(input).orElse(null);
            assertFalse(input.hasRemaining());
        }
        decoder.endOfInput();
        assertNotNull(response);
        return response;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for generation");
    }

    private static final class TestServer implements AutoCloseable {
        private final NioKeyServer server;
        private final ExecutorService eventLoop = Executors.newSingleThreadExecutor();
        private final Future<?> loop;

        private TestServer(CredentialsGenerator generator) throws IOException {
            server = new NioKeyServer(new InetSocketAddress("127.0.0.1", 0), 2, generator);
            loop = eventLoop.submit(() -> {
                server.run();
                return null;
            });
        }

        private Socket connect() throws IOException {
            Socket socket = new Socket();
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);
            socket.connect(server.localAddress(), 5000);
            return socket;
        }

        @Override
        public void close() throws Exception {
            server.close();
            try {
                loop.get(5, TimeUnit.SECONDS);
            } finally {
                eventLoop.shutdownNow();
                assertTrue(eventLoop.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }
}
