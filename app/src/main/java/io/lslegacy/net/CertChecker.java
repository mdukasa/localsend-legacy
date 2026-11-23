package io.lslegacy.net;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA384Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.RSADigestSigner;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.util.encoders.Base64;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks a TLS server's certificate chain against the bundled {@link Roots}: each certificate must be
 * signed by the next (or by a trusted root), intermediates must be CAs, all must be within their validity
 * dates, and the server certificate must name the host. Uses only BouncyCastle's lightweight API, since
 * Android 2.x can't verify the ECDSA P-384 signatures these chains use.
 */
final class CertChecker {
    private static final int MAX_DEPTH = 6;
    private static List<Certificate> roots;

    private CertChecker() {}

    private static synchronized List<Certificate> roots() {
        if (roots == null) {
            List<Certificate> list = new ArrayList<Certificate>();
            for (String b64 : Roots.BASE64) list.add(Certificate.getInstance(Base64.decode(b64)));
            roots = list;
        }
        return roots;
    }

    /**
     * @param chain DER certificates as the server sent them, its own first
     * @param now   the time to check validity at
     * @throws IOException with a reason when the chain isn't trusted for {@code host}
     */
    static void check(List<byte[]> chain, String host, long now) throws IOException {
        if (chain.isEmpty()) throw new IOException("No certificate");
        List<Certificate> certs = new ArrayList<Certificate>();
        try {
            for (byte[] der : chain) certs.add(Certificate.getInstance(der));
        } catch (RuntimeException e) {
            throw new IOException("Unreadable certificate");
        }
        Certificate leaf = certs.get(0);
        if (!matchesHost(leaf, host)) throw new IOException("Certificate isn't for " + host);

        Certificate cur = leaf;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            checkDates(cur, now);
            if (depth > 0 && !isCa(cur)) throw new IOException("Intermediate isn't a CA");
            for (Certificate root : roots()) {
                // The server may send a trusted root itself (or a cross-signed copy of one): trust it as is.
                if (root.getSubject().equals(cur.getSubject())
                        && root.getSubjectPublicKeyInfo().equals(cur.getSubjectPublicKeyInfo())) {
                    return;
                }
                if (root.getSubject().equals(cur.getIssuer()) && verifies(cur, root)) {
                    checkDates(root, now);
                    return;
                }
            }
            Certificate next = null;
            for (Certificate c : certs) {
                if (c != cur && c.getSubject().equals(cur.getIssuer()) && verifies(cur, c)) {
                    next = c;
                    break;
                }
            }
            if (next == null) throw new IOException("Certificate isn't from a trusted authority");
            cur = next;
        }
        throw new IOException("Certificate chain too long");
    }

    private static void checkDates(Certificate c, long now) throws IOException {
        if (now < c.getStartDate().getDate().getTime()) throw new IOException("Certificate not valid yet; check the phone's clock");
        if (now > c.getEndDate().getDate().getTime()) throw new IOException("Certificate expired; check the phone's clock");
    }

    private static boolean isCa(Certificate c) {
        Extensions ext = c.getTBSCertificate().getExtensions();
        BasicConstraints bc = ext != null ? BasicConstraints.fromExtensions(ext) : null;
        return bc != null && bc.isCA();
    }

    /** Matches DNS names in the subject alternative names, with a single leftmost wildcard label. */
    static boolean matchesHost(Certificate c, String host) {
        Extensions ext = c.getTBSCertificate().getExtensions();
        GeneralNames names = ext != null ? GeneralNames.fromExtensions(ext, Extension.subjectAlternativeName) : null;
        if (names == null) return false;
        host = host.toLowerCase(Locale.US);
        for (GeneralName n : names.getNames()) {
            if (n.getTagNo() != GeneralName.dNSName) continue;
            String pattern = n.getName().toString().toLowerCase(Locale.US);
            if (pattern.equals(host)) return true;
            if (pattern.startsWith("*.")) {
                int dot = host.indexOf('.');
                if (dot > 0 && host.substring(dot).equals(pattern.substring(1))) return true;
            }
        }
        return false;
    }

    /** Whether {@code issuer}'s key made {@code c}'s signature. */
    private static boolean verifies(Certificate c, Certificate issuer) {
        try {
            byte[] tbs = c.getTBSCertificate().getEncoded(ASN1Encoding.DER);
            byte[] sig = c.getSignature().getOctets();
            ASN1ObjectIdentifier alg = c.getSignatureAlgorithm().getAlgorithm();
            AsymmetricKeyParameter key = PublicKeyFactory.createKey(issuer.getSubjectPublicKeyInfo());
            if (key instanceof ECPublicKeyParameters) {
                Digest d;
                if (alg.equals(X9ObjectIdentifiers.ecdsa_with_SHA256)) d = new SHA256Digest();
                else if (alg.equals(X9ObjectIdentifiers.ecdsa_with_SHA384)) d = new SHA384Digest();
                else if (alg.equals(X9ObjectIdentifiers.ecdsa_with_SHA512)) d = new SHA512Digest();
                else return false;
                byte[] hash = new byte[d.getDigestSize()];
                d.update(tbs, 0, tbs.length);
                d.doFinal(hash, 0);
                ASN1Sequence rs = ASN1Sequence.getInstance(sig);
                ECDSASigner signer = new ECDSASigner();
                signer.init(false, key);
                return signer.verifySignature(hash, ASN1Integer.getInstance(rs.getObjectAt(0)).getValue(),
                        ASN1Integer.getInstance(rs.getObjectAt(1)).getValue());
            }
            if (key instanceof RSAKeyParameters) {
                Digest d;
                if (alg.equals(PKCSObjectIdentifiers.sha256WithRSAEncryption)) d = new SHA256Digest();
                else if (alg.equals(PKCSObjectIdentifiers.sha384WithRSAEncryption)) d = new SHA384Digest();
                else if (alg.equals(PKCSObjectIdentifiers.sha512WithRSAEncryption)) d = new SHA512Digest();
                else return false;
                RSADigestSigner signer = new RSADigestSigner(d);
                signer.init(false, key);
                signer.update(tbs, 0, tbs.length);
                return signer.verifySignature(sig);
            }
            return false;
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
