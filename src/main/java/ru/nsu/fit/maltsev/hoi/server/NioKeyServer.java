package ru.nsu.fit.maltsev.hoi.server;

import ru.nsu.fit.maltsev.hoi.protocol.ProtocolCodec;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolException;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolLimits;
import ru.nsu.fit.maltsev.hoi.protocol.ProtocolResponse;
import ru.nsu.fit.maltsev.hoi.protocol.RequestDecoder;
import ru.nsu.fit.maltsev.hoi.protocol.ResponseStatus;
import ru.nsu.fit.maltsev.hoi.service.CredentialsGenerator;
import ru.nsu.fit.maltsev.hoi.service.GenerationService;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

public final class NioKeyServer implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(NioKeyServer.class.getName());
    private static final int ACCEPT_BATCH_SIZE = 64;

    private final Object lifecycle = new Object();
    private final Selector selector;
    private final ServerSocketChannel listener;
    private final GenerationService generation;
    private final InetSocketAddress localAddress;
    private final Set<Connection> connections = new HashSet<>();
    private final Map<CompletionStage<ProtocolResponse>, ResponseGroup> responses = new IdentityHashMap<>();
    private final ByteBuffer input = ByteBuffer.allocate(ProtocolLimits.MAX_NAME_BYTES + 1);
    private volatile boolean stopRequested;
    private boolean started;

    public NioKeyServer(InetSocketAddress bindAddress, int workerCount, CredentialsGenerator generator)
            throws IOException {
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(generator, "generator");
        if (workerCount < 1) {
            throw new IllegalArgumentException("Worker count must be positive");
        }
        selector = Selector.open();
        ServerSocketChannel opened = null;
        try {
            opened = ServerSocketChannel.open();
            opened.configureBlocking(false);
            opened.bind(bindAddress, 256);
            opened.register(selector, SelectionKey.OP_ACCEPT);
            localAddress = (InetSocketAddress) opened.getLocalAddress();
            generation = new GenerationService(workerCount, generator, selector::wakeup);
            listener = opened;
        } catch (IOException | RuntimeException | Error failure) {
            if (opened != null) {
                closeResource(opened);
            }
            closeResource(selector);
            throw failure;
        }
    }

    public InetSocketAddress localAddress() {
        return localAddress;
    }

    public void run() throws IOException {
        synchronized (lifecycle) {
            if (started || stopRequested) {
                throw new IllegalStateException("Server can only run once and must not be closed");
            }
            started = true;
        }
        try {
            while (!stopRequested && !Thread.currentThread().isInterrupted()) {
                generation.drainCompletions();
                if (stopRequested) {
                    break;
                }
                selector.select();
                if (stopRequested) {
                    break;
                }
                processSelectedKeys();
            }
        } finally {
            stopRequested = true;
            cleanup();
        }
    }

    private void processSelectedKeys() throws IOException {
        Iterator<SelectionKey> selected = selector.selectedKeys().iterator();
        while (selected.hasNext()) {
            SelectionKey key = selected.next();
            selected.remove();
            if (!key.isValid()) {
                continue;
            }
            if (key.isAcceptable()) {
                acceptConnections();
                continue;
            }
            Connection connection = (Connection) key.attachment();
            try {
                if (key.isReadable()) {
                    read(connection);
                }
                if (key.isValid() && key.isWritable()) {
                    write(connection);
                }
            } catch (IOException | CancelledKeyException failure) {
                closeConnection(connection);
            }
        }
    }

    private void acceptConnections() throws IOException {
        for (int accepted = 0; accepted < ACCEPT_BATCH_SIZE; accepted++) {
            SocketChannel channel = listener.accept();
            if (channel == null) {
                return;
            }
            try {
                channel.configureBlocking(false);
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                SelectionKey key = channel.register(selector, SelectionKey.OP_READ);
                Connection connection = new Connection(channel, key);
                key.attach(connection);
                connections.add(connection);
            } catch (IOException failure) {
                closeResource(channel);
            }
        }
    }

    private void read(Connection connection) throws IOException {
        input.clear();
        int count = connection.channel.read(input);
        if (count == -1) {
            connection.inputClosed = true;
            if (connection.state == State.READING) {
                try {
                    connection.decoder.endOfInput();
                } catch (ProtocolException failure) {
                    invalidRequest(connection, failure);
                }
            }
            updateInterests(connection);
            return;
        }
        if (count == 0 || connection.state != State.READING) {
            return;
        }
        input.flip();
        try {
            Optional<String> name = connection.decoder.feed(input);
            if (name.isPresent()) {
                requestGeneration(connection, name.orElseThrow());
            }
        } catch (ProtocolException failure) {
            invalidRequest(connection, failure);
        }
    }

    private void requestGeneration(Connection connection, String name) {
        connection.state = State.WAITING;
        CompletionStage<ProtocolResponse> result = generation.request(name);
        ResponseGroup group = responses.get(result);
        boolean subscribe = group == null;
        if (subscribe) {
            group = new ResponseGroup(result);
            responses.put(result, group);
        }
        if (group.encoded != null) {
            beginWrite(connection, group.encoded);
            return;
        }
        connection.group = group;
        group.waiters.add(connection);
        if (subscribe) {
            ResponseGroup subscribed = group;
            result.whenComplete((response, failure) -> completeGroup(subscribed, response, failure));
        }
    }

    private void completeGroup(ResponseGroup group, ProtocolResponse response, Throwable failure) {
        if (stopRequested || failure != null) {
            responses.remove(group.result);
            for (Connection connection : List.copyOf(group.waiters)) {
                closeConnection(connection);
            }
            return;
        }
        group.encoded = ProtocolCodec.encodeResponse(response).asReadOnlyBuffer();
        if (response.status() != ResponseStatus.SUCCESS) {
            responses.remove(group.result);
        }
        for (Connection connection : List.copyOf(group.waiters)) {
            beginWrite(connection, group.encoded);
        }
    }

    private void invalidRequest(Connection connection, ProtocolException failure) {
        ProtocolResponse.Error response = new ProtocolResponse.Error(
                ResponseStatus.INVALID_REQUEST, failure.getMessage());
        beginWrite(connection, ProtocolCodec.encodeResponse(response));
    }

    private void beginWrite(Connection connection, ByteBuffer message) {
        if (connection.state == State.CLOSED) {
            return;
        }
        if (connection.group != null) {
            connection.group.waiters.remove(connection);
            connection.group = null;
        }
        connection.output = new PendingWrite(message);
        connection.state = State.WRITING;
        updateInterests(connection);
    }

    private void updateInterests(Connection connection) {
        int operations = connection.inputClosed ? 0 : SelectionKey.OP_READ;
        if (connection.state == State.WRITING) {
            operations |= SelectionKey.OP_WRITE;
        }
        connection.key.interestOps(operations);
    }

    private void write(Connection connection) throws IOException {
        if (connection.output.writeTo(connection.channel)) {
            closeConnection(connection);
        }
    }

    private void closeConnection(Connection connection) {
        if (connection.state == State.CLOSED) {
            return;
        }
        connection.state = State.CLOSED;
        if (connection.group != null) {
            connection.group.waiters.remove(connection);
            connection.group = null;
        }
        connection.output = null;
        connection.key.cancel();
        connections.remove(connection);
        closeResource(connection.channel);
    }

    @Override
    public void close() {
        boolean cleanupHere;
        synchronized (lifecycle) {
            if (stopRequested) {
                return;
            }
            stopRequested = true;
            cleanupHere = !started;
            started = true;
            selector.wakeup();
        }
        if (cleanupHere) {
            cleanup();
        }
    }

    private void cleanup() {
        for (Connection connection : List.copyOf(connections)) {
            closeConnection(connection);
        }
        responses.clear();
        generation.close();
        closeResource(listener);
        closeResource(selector);
    }

    private static void closeResource(Closeable resource) {
        try {
            resource.close();
        } catch (IOException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Cannot close server resource", failure);
        }
    }

    private enum State { READING, WAITING, WRITING, CLOSED }

    private static final class Connection {
        private final SocketChannel channel;
        private final SelectionKey key;
        private final RequestDecoder decoder = new RequestDecoder();
        private State state = State.READING;
        private boolean inputClosed;
        private ResponseGroup group;
        private PendingWrite output;

        private Connection(SocketChannel channel, SelectionKey key) {
            this.channel = channel;
            this.key = key;
        }
    }

    private static final class ResponseGroup {
        private final CompletionStage<ProtocolResponse> result;
        private final Set<Connection> waiters = new HashSet<>();
        private ByteBuffer encoded;

        private ResponseGroup(CompletionStage<ProtocolResponse> result) {
            this.result = result;
        }
    }
}
