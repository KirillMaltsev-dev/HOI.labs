package ru.nsu.fit.maltsev.hoi.crypto;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

public final class SigningKeyTool {
    private SigningKeyTool() {
    }

    public static void main(String[] args) throws IOException, GeneralSecurityException {
        if (args.length != 2 || !"--output".equals(args[0]) || args[1].isBlank()) {
            throw new IllegalArgumentException("Usage: SigningKeyTool --output <private-key-path>");
        }
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(output.toString());
        }
        Files.createDirectories(output.getParent());
        RsaKeys.writePrivateKey(output, RsaKeys.generate().getPrivate());
        System.out.println("Signing key written to " + output);
    }
}
