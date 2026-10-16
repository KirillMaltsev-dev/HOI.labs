package ru.nsu.fit.maltsev.hoi.client;

import ru.nsu.fit.maltsev.hoi.protocol.ProtocolResponse;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Optional;

public final class ClientMain {
    private ClientMain() {
    }

    public static void main(String[] args) throws IOException, GeneralSecurityException, InterruptedException {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println(ClientConfig.USAGE);
            return;
        }
        ClientConfig config = ClientConfig.parse(args);
        Optional<ProtocolResponse.Success> response = new KeyClient().fetch(config);
        if (response.isEmpty()) {
            System.out.println("Request sent; disconnected without reading response");
            return;
        }
        CredentialsFiles.SavedPair saved = CredentialsFiles.save(
                config.outputDirectory(), config.name(), response.orElseThrow());
        System.out.println("Private key saved to " + saved.privateKey());
        System.out.println("Certificate saved to " + saved.certificate());
    }
}
