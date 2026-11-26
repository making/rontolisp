// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.security.Provider;

// The providers a JDK installs, under its names, versions and descriptions, each one
// object made where it is first asked for. SUN holds the subset's digests and
// SecureRandoms and SunJCE its Macs and PBKDF2 factories, each under the names and with
// the attributes a JDK's provider gives it and the subset's own class; the others hold
// none of the subset's services. What getInstance makes is the subset's tables, not these
// entries, whatever a program puts in them.
public final class InstalledProvider extends Provider {

    private static final InstalledProvider[] MADE = new InstalledProvider[Hashing.PROVIDERS.length];

    private InstalledProvider(String name) {
        super(name, "25", info(name));
    }

    // The entries of the subset's services this provider holds: what Provider puts in
    // its table before a member first reads or writes it.
    public void held() {
        String name = getName();
        if (name.equals("SUN")) {
            String[][] digests = Hashing.names(0);
            for (int digest = 0; digest < digests.length; digest++) {
                holds("MessageDigest", digests[digest], "rontolisp.internal." + digestClass(digest), "ImplementedIn",
                        "Software");
            }
            for (String random : new String[] { "NativePRNG", "NativePRNGBlocking", "NativePRNGNonBlocking" }) {
                holds("SecureRandom", new String[] { random }, "java.security.SecureRandom", "ThreadSafe", "true");
            }
            for (String random : new String[] { "SHA1PRNG", "DRBG" }) {
                holds("SecureRandom", new String[] { random }, "java.security.SecureRandom", "ThreadSafe", "true");
                put("SecureRandom." + random + " ImplementedIn", "Software");
            }
        } else if (name.equals("SunJCE")) {
            for (String[] names : Hashing.names(1)) {
                holds("Mac", names, "javax.crypto.Mac", "SupportedKeyFormats", "RAW");
            }
            for (String[] names : Hashing.names(2)) {
                holds("SecretKeyFactory", names, "javax.crypto.SecretKeyFactory", null, null);
            }
        }
    }

    // The provider of a name, null for none.
    public static synchronized InstalledProvider named(String name) {
        for (int i = 0; i < MADE.length; i++) {
            if (Hashing.PROVIDERS[i].equals(name)) {
                if (MADE[i] == null) {
                    MADE[i] = new InstalledProvider(name);
                }
                return MADE[i];
            }
        }
        return null;
    }

    // Every provider, in the order a JDK lists them.
    public static InstalledProvider[] all() {
        InstalledProvider[] all = new InstalledProvider[Hashing.PROVIDERS.length];
        for (int i = 0; i < all.length; i++) {
            all[i] = named(Hashing.PROVIDERS[i]);
        }
        return all;
    }

    // The entries of a service: its algorithm, the first of its names, and the class that
    // answers it; the other names as aliases; one attribute, or none.
    private void holds(String type, String[] names, String className, String attribute, String value) {
        if (names.length == 0) {
            return;
        }
        put(type + "." + names[0], className);
        for (int i = 1; i < names.length; i++) {
            put("Alg.Alias." + type + "." + names[i], names[0]);
        }
        if (attribute != null) {
            put(type + "." + names[0] + " " + attribute, value);
        }
    }

    private static String digestClass(int digest) {
        switch (digest) {
            case Hashing.MD5:
                return "Md5";
            case Hashing.SHA1:
                return "Sha1";
            case Hashing.SHA224, Hashing.SHA256:
                return "Sha256";
            case Hashing.MD2:
                return "Md2";
            case Hashing.SHA3_224, Hashing.SHA3_256, Hashing.SHA3_384, Hashing.SHA3_512, Hashing.SHAKE128,
                    Hashing.SHAKE256:
                return "Sha3";
            default:
                return "Sha512";
        }
    }

    // What a JDK 25's provider of a name says of itself.
    private static String info(String name) {
        switch (name) {
            case "SUN":
                return "SUN (DSA key/parameter generation; DSA signing; SHA-1, MD5 digests; SecureRandom; X.509 certificates;"
                        + " PKCS12, JKS & DKS keystores; PKIX CertPathValidator; PKIX CertPathBuilder; LDAP, Collection"
                        + " CertStores, JavaPolicy Policy; JavaLoginConfig Configuration)";
            case "SunRsaSign":
                return "Sun RSA signature provider";
            case "SunEC":
                return "Sun Elliptic Curve provider";
            case "SunJSSE":
                return "Sun JSSE provider(PKCS12, SunX509/PKIX key/trust factories,"
                        + " SSLv3/TLSv1/TLSv1.1/TLSv1.2/TLSv1.3/DTLSv1.0/DTLSv1.2)";
            case "SunJCE":
                return "SunJCE Provider (implements RSA, DES, Triple DES, AES, Blowfish, ARCFOUR, RC2, PBE, Diffie-Hellman,"
                        + " HMAC, ChaCha20, DHKEM, ML-KEM, and HKDF)";
            case "SunJGSS":
                return "Sun (Kerberos v5, SPNEGO)";
            case "SunSASL":
                return "Sun SASL provider(implements client mechanisms for: DIGEST-MD5, EXTERNAL, PLAIN, CRAM-MD5, NTLM;"
                        + " server mechanisms for: DIGEST-MD5, CRAM-MD5, NTLM)";
            case "XMLDSig":
                return "XMLDSig (DOM XMLSignatureFactory; DOM KeyInfoFactory; C14N 1.0, C14N 1.1, Exclusive C14N, Base64,"
                        + " Enveloped, XPath, XPath2, XSLT TransformServices)";
            case "SunPCSC":
                return "Sun PC/SC provider";
            case "JdkLDAP":
                return "JdkLDAP Provider (implements LDAP CertStore)";
            case "JdkSASL":
                return "JDK SASL provider(implements client and server mechanisms for GSSAPI)";
            default:
                return "Unconfigured and unusable PKCS11 provider";
        }
    }
}
