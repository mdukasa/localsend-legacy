package io.lslegacy;

import android.content.SharedPreferences;
import android.util.Base64;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECNamedDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Date;

/**
 * This device's self-signed ECDSA P-256 certificate. LocalSend uses the certificate's SHA-256 as the
 * device fingerprint and requires it as a TLS client certificate when we connect to it.
 * P-256 rather than RSA because key generation in pure Java on a 600 MHz ARMv6 is instant this way.
 */
public final class Identity {
    private static final String KEY_D = "identity_ec_d";
    private static final String KEY_CERT = "identity_cert";
    /** 1970-01-01 to 2049-12-31 23:59:59 UTC, the widest range UTCTime can encode. */
    private static final long NOT_BEFORE = 0L;
    private static final long NOT_AFTER = 2524607999000L;

    public final ECPrivateKeyParameters privateKey;
    public final byte[] certDer;
    public final String fingerprint;

    private Identity(ECPrivateKeyParameters privateKey, byte[] certDer) {
        this.privateKey = privateKey;
        this.certDer = certDer;
        this.fingerprint = Util.sha256Hex(certDer);
    }

    private static ECNamedDomainParameters domain() {
        X9ECParameters x9 = ECNamedCurveTable.getByOID(SECObjectIdentifiers.secp256r1);
        return new ECNamedDomainParameters(SECObjectIdentifiers.secp256r1, x9);
    }

    public static Identity loadOrCreate(SharedPreferences prefs) throws IOException {
        String d = prefs.getString(KEY_D, null);
        String cert = prefs.getString(KEY_CERT, null);
        Identity id;
        if (d != null && cert != null) {
            ECPrivateKeyParameters priv = new ECPrivateKeyParameters(new BigInteger(d, 16), domain());
            byte[] der = Base64.decode(cert, Base64.NO_WRAP);
            if (Certificate.getInstance(der).getEndDate().getDate().getTime() == NOT_AFTER) {
                return new Identity(priv, der);
            }
            // Versions up to 0.2.2 dated the certificate from the phone's clock, and phones that were
            // never set to the right time (1980 on a stock Wildfire S) got one that expired long ago.
            // LocalSend rejects an expired client certificate, so re-issue it for the same key.
            id = new Identity(priv, selfSign(priv, Rand.get()));
        } else {
            id = generate();
        }
        prefs.edit()
                .putString(KEY_D, id.privateKey.getD().toString(16))
                .putString(KEY_CERT, Base64.encodeToString(id.certDer, Base64.NO_WRAP))
                .commit();
        return id;
    }

    private static Identity generate() throws IOException {
        SecureRandom rnd = Rand.get();
        ECKeyPairGenerator gen = new ECKeyPairGenerator();
        gen.init(new ECKeyGenerationParameters(domain(), rnd));
        AsymmetricCipherKeyPair kp = gen.generateKeyPair();
        ECPrivateKeyParameters priv = (ECPrivateKeyParameters) kp.getPrivate();
        return new Identity(priv, selfSign(priv, rnd));
    }

    private static byte[] selfSign(ECPrivateKeyParameters priv, SecureRandom rnd) throws IOException {
        ECNamedDomainParameters dom = domain();
        ECPublicKeyParameters pub = new ECPublicKeyParameters(dom.getG().multiply(priv.getD()).normalize(), dom);

        X500Name name = new X500Name("CN=LocalSend Legacy");
        AlgorithmIdentifier sigAlg = new AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256);
        V3TBSCertificateGenerator tbsGen = new V3TBSCertificateGenerator();
        tbsGen.setSerialNumber(new ASN1Integer(new BigInteger(63, rnd)));
        tbsGen.setIssuer(name);
        tbsGen.setSubject(name);
        // Fixed dates, so a phone whose clock is wrong still makes a certificate that is valid now.
        tbsGen.setStartDate(new Time(new Date(NOT_BEFORE)));
        tbsGen.setEndDate(new Time(new Date(NOT_AFTER)));
        tbsGen.setSignature(sigAlg);
        tbsGen.setSubjectPublicKeyInfo(SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(pub));
        TBSCertificate tbs = tbsGen.generateTBSCertificate();

        byte[] tbsDer = tbs.getEncoded(ASN1Encoding.DER);
        SHA256Digest digest = new SHA256Digest();
        digest.update(tbsDer, 0, tbsDer.length);
        byte[] hash = new byte[digest.getDigestSize()];
        digest.doFinal(hash, 0);
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, priv);
        BigInteger[] rs = signer.generateSignature(hash);
        ASN1EncodableVector sig = new ASN1EncodableVector();
        sig.add(new ASN1Integer(rs[0]));
        sig.add(new ASN1Integer(rs[1]));

        ASN1EncodableVector cert = new ASN1EncodableVector();
        cert.add(tbs);
        cert.add(sigAlg);
        cert.add(new DERBitString(new DERSequence(sig).getEncoded(ASN1Encoding.DER)));
        return new DERSequence(cert).getEncoded(ASN1Encoding.DER);
    }
}
