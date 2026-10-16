package ru.nsu.fit.maltsev.hoi.server;

import ru.nsu.fit.maltsev.hoi.crypto.CertificateGenerator;
import ru.nsu.fit.maltsev.hoi.crypto.RsaKeys;
import ru.nsu.fit.maltsev.hoi.service.CredentialsGenerator;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;

public final class ServerMain {
    private ServerMain() {
    }

    public static void main(String[] args) throws IOException, GeneralSecurityException {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println(ServerConfig.USAGE);
            return;
        }
        ServerConfig config = ServerConfig.parse(args);
        PrivateKey signingKey = RsaKeys.loadPrivateKey(config.signingKey());
        CertificateGenerator certificates = new CertificateGenerator(signingKey, config.issuer());
        try (NioKeyServer server = new NioKeyServer(
                new InetSocketAddress(config.bindAddress(), config.port()), config.workerCount(),
                CredentialsGenerator.from(certificates))) {
            Thread eventThread = Thread.currentThread();
            Thread shutdown = new Thread(() -> {
                server.close();
                try {
                    eventThread.join(5000);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }, "server-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                System.out.println("Listening on " + server.localAddress());
                server.run();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException shuttingDown) {
                }
            }
        }
    }
}
