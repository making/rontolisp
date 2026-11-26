// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The digests of java.security and javax.crypto: their numbers, the lookup of the names
// a JDK knows them by (the front end's table, JavaIntrinsics.DIGEST_NAMES and its two
// siblings) and the kernels a target may have of its own. The algorithms are the
// standards': MD5 is RFC 1321, MD2 RFC 1319, SHA-1 and the SHA-2 family FIPS 180-4, SHA-3
// and SHAKE FIPS 202, HMAC RFC 2104 and PBKDF2 RFC 8018, section 5.2. Each digest is a
// class of this package -- Md5, Md2, Sha1, Sha256 (SHA-224 too), Sha512 (SHA-384,
// SHA-512/224 and SHA-512/256 too) and Sha3 (the six of FIPS 202) -- made only by a
// program that names its algorithm or computes the name it asks for.
package rontolisp.internal;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;

public final class Hashing {

    public static final int MD5 = 0;

    public static final int SHA1 = 1;

    public static final int SHA224 = 2;

    public static final int SHA256 = 3;

    public static final int SHA384 = 4;

    public static final int SHA512 = 5;

    public static final int SHA512_224 = 6;

    public static final int SHA512_256 = 7;

    public static final int MD2 = 8;

    public static final int SHA3_224 = 9;

    public static final int SHA3_256 = 10;

    public static final int SHA3_384 = 11;

    public static final int SHA3_512 = 12;

    // A JDK's SHAKE128-256 and SHAKE256-512: the first 32 and 64 bytes of the two.
    public static final int SHAKE128 = 13;

    public static final int SHAKE256 = 14;

    // The providers a JDK has, by the names getInstance of a provider takes, in the order
    // Security lists them.
    static final String[] PROVIDERS = { "SUN", "SunRsaSign", "SunEC", "SunJSSE", "SunJCE", "SunJGSS",
            "SunSASL", "XMLDSig", "SunPCSC", "JdkLDAP", "JdkSASL", "SunPKCS11" };

    private Hashing() {
    }

    // BLOCKS blocks of DATA from OFFSET compressed into the STATE of a digest by a kernel
    // of the target's own: whether it has one. The interpreter has; on every other target
    // the Java of the digest's class does the work.
    public static native boolean compressed(int digest, int[] state, byte[] data, int offset, int blocks);

    public static native boolean compressed(int digest, long[] state, byte[] data, int offset, int blocks);

    // PBKDF2 over HMAC of the digest by a kernel of the target's own, or null where it has
    // none.
    public static native byte[] derived(int digest, byte[] password, byte[] salt, int iterations, int length);

    // The number of a digest of a name as MessageDigest.getInstance takes it, whatever
    // its case; -1 for none here.
    public static int digest(String name) {
        return digestNumber(name.toUpperCase());
    }

    // The number of the digest under an HMAC of a name as Mac.getInstance takes it.
    public static int mac(String name) {
        return macNumber(name.toUpperCase());
    }

    // The number of the digest under the HMAC of a name as SecretKeyFactory.getInstance
    // takes it.
    public static int factory(String name) {
        return factoryNumber(name.toUpperCase());
    }

    // Each a lookup of a name in upper case in the front end's table of the names a JDK
    // knows each by, which also decides what a constant name makes (JavaIntrinsics).
    private static native int digestNumber(String upper);

    private static native int macNumber(String upper);

    private static native int factoryNumber(String upper);

    // The names a JDK gives the algorithms of a kind -- 0 the digests, 1 the Macs, 2 the
    // factories, a constant where it is called -- by number: the standard name first,
    // then the aliases; none for a number of no algorithm. The front end's table.
    static native String[][] names(int kind);

    // The standard name of a digest: what a refusal and a derived key say.
    public static String name(int digest) {
        switch (digest) {
            case MD5:
                return "MD5";
            case SHA1:
                return "SHA-1";
            case SHA224:
                return "SHA-224";
            case SHA256:
                return "SHA-256";
            case SHA384:
                return "SHA-384";
            case SHA512:
                return "SHA-512";
            case SHA512_224:
                return "SHA-512/224";
            case SHA512_256:
                return "SHA-512/256";
            case MD2:
                return "MD2";
            case SHA3_224:
                return "SHA3-224";
            case SHA3_256:
                return "SHA3-256";
            case SHA3_384:
                return "SHA3-384";
            case SHA3_512:
                return "SHA3-512";
            case SHAKE128:
                return "SHAKE128-256";
            default:
                return "SHAKE256-512";
        }
    }

    // The name of the HMAC of a digest of SHA-1 or the SHA-2 family, as a derived key's
    // algorithm writes it.
    public static String hmacName(int digest) {
        return digest == MD5 ? "HmacMD5" : "HmacSHA" + name(digest).substring(4);
    }

    // A digest of a number, under the name a program gave: every class of a digest is
    // made where this is called.
    public static BlockDigest engine(int digest, String algorithm) {
        switch (digest) {
            case MD5:
                return new Md5(algorithm);
            case SHA1:
                return new Sha1(algorithm);
            case SHA224, SHA256:
                return new Sha256(algorithm, digest);
            case MD2:
                return new Md2(algorithm);
            case SHA3_224, SHA3_256, SHA3_384, SHA3_512, SHAKE128, SHAKE256:
                return new Sha3(algorithm, digest);
            default:
                return new Sha512(algorithm, digest);
        }
    }

    // Refuses no provider, and answers for one of the program's own what a JDK answers
    // where the class its service names cannot be found: the subset makes no class by its
    // name. A provider of the subset's own passes; the caller asks it for the algorithm.
    public static void provided(Provider provider, String type, String algorithm) throws NoSuchAlgorithmException {
        if (provider == null) {
            throw new IllegalArgumentException("missing provider");
        }
        if (provider instanceof InstalledProvider) {
            return;
        }
        Provider.Service service = provider.getService(type, algorithm);
        if (service == null) {
            throw new NoSuchAlgorithmException("no such algorithm: " + algorithm + " for provider " + provider.getName());
        }
        throw new NoSuchAlgorithmException(
                "class configured for " + type + " (provider: " + provider.getName() + ") cannot be found.",
                new ClassNotFoundException(service.getClassName()));
    }

    // Refuses a provider of no name and one a JDK does not have.
    public static void provided(String provider) throws NoSuchProviderException {
        if (provider == null || provider.isEmpty()) {
            throw new IllegalArgumentException("missing provider");
        }
        for (String known : PROVIDERS) {
            if (known.equals(provider)) {
                return;
            }
        }
        throw new NoSuchProviderException("no such provider: " + provider);
    }
}
