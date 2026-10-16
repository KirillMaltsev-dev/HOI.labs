package ru.nsu.fit.maltsev.hoi.crypto;

import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import ru.nsu.fit.maltsev.hoi.protocol.ClientName;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Objects;

public final class CertificateGenerator {
    public static final int VALIDITY_DAYS = 365;
    public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private static final Provider PROVIDER = new BouncyCastleProvider();

    private final PrivateKey signingKey;
    private final X500Name issuer;
    private final Clock clock;

    public CertificateGenerator(PrivateKey signingKey, String issuer) throws InvalidKeyException {
        this(signingKey, issuer, Clock.systemUTC());
    }

    CertificateGenerator(PrivateKey signingKey, String issuer, Clock clock) throws InvalidKeyException {
        RsaKeys.require8192BitKey(signingKey);
        Objects.requireNonNull(issuer, "issuer");
        this.issuer = new X500Name(issuer);
        if (this.issuer.getRDNs().length == 0) {
            throw new IllegalArgumentException("Issuer must not be empty");
        }
        this.signingKey = signingKey;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public GeneratedCredentials generate(String clientName) throws GeneralSecurityException, IOException {
        ClientName name = new ClientName(clientName);
        KeyPair keyPair = RsaKeys.generate();
        Instant notBefore = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant notAfter = notBefore.plus(VALIDITY_DAYS, ChronoUnit.DAYS);
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.CN, new DERUTF8String(name.value())).build();
        SecureRandom random = new SecureRandom();
        BigInteger serial;
        do {
            serial = new BigInteger(159, random);
        } while (serial.signum() == 0);

        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                issuer, serial, Date.from(notBefore), Date.from(notAfter), subject,
                SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()));
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        try {
            ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                    .setProvider(PROVIDER).build(signingKey);
            X509Certificate certificate = new JcaX509CertificateConverter().setProvider(PROVIDER)
                    .getCertificate(builder.build(signer));
            return new GeneratedCredentials(keyPair, certificate);
        } catch (OperatorCreationException exception) {
            throw new GeneralSecurityException("Cannot create certificate signer", exception);
        }
    }
}
