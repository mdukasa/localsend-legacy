package io.lslegacy.net;

import org.bouncycastle.tls.AlertDescription;
import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.CertificateEntry;
import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.CipherSuite;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.HashAlgorithm;
import org.bouncycastle.tls.SignatureAlgorithm;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsFatalAlert;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.TlsUtils;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

import java.io.IOException;

import io.lslegacy.Identity;
import io.lslegacy.Util;

/**
 * TLS client for LocalSend peers: presents our self-signed ECDSA identity (LocalSend requires a
 * client certificate) and, when the peer's fingerprint is known, pins the server certificate to it.
 */
final class LsTlsClient extends DefaultTlsClient {
    // ChaCha20 is several times faster than AES-GCM in pure Java on CPUs without AES instructions.
    private static final int[] CHACHA_SUITES = {
            CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
    };

    private final Identity identity;
    private final String expectedFingerprint;
    private final boolean chachaOnly;

    LsTlsClient(BcTlsCrypto crypto, Identity identity, String expectedFingerprint, boolean chachaOnly) {
        super(crypto);
        this.identity = identity;
        this.expectedFingerprint = expectedFingerprint;
        this.chachaOnly = chachaOnly;
    }

    @Override protected int[] getSupportedCipherSuites() {
        if (chachaOnly) return TlsUtils.getSupportedCipherSuites(getCrypto(), CHACHA_SUITES);
        return super.getSupportedCipherSuites();
    }

    @Override public TlsAuthentication getAuthentication() {
        return new TlsAuthentication() {
            @Override public void notifyServerCertificate(TlsServerCertificate serverCertificate) throws IOException {
                if (expectedFingerprint == null || expectedFingerprint.length() == 0) return;
                Certificate chain = serverCertificate.getCertificate();
                if (chain == null || chain.isEmpty()) throw new TlsFatalAlert(AlertDescription.bad_certificate);
                String actual = Util.sha256Hex(chain.getCertificateAt(0).getEncoded());
                if (!actual.equalsIgnoreCase(expectedFingerprint)) {
                    throw new TlsFatalAlert(AlertDescription.bad_certificate);
                }
            }

            @Override public TlsCredentials getClientCredentials(CertificateRequest request) throws IOException {
                if (identity == null) return null;
                TlsCertificate cert = getCrypto().createCertificate(identity.certDer);
                byte[] ctx = request.getCertificateRequestContext();
                Certificate chain = ctx != null
                        ? new Certificate(ctx, new CertificateEntry[]{new CertificateEntry(cert, null)})
                        : new Certificate(new TlsCertificate[]{cert});
                SignatureAndHashAlgorithm alg = SignatureAndHashAlgorithm.getInstance(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa);
                return new BcDefaultTlsCredentialedSigner(new TlsCryptoParameters(context), (BcTlsCrypto) getCrypto(),
                        identity.privateKey, chain, alg);
            }
        };
    }
}
