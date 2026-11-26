// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.crypto;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import javax.crypto.interfaces.PBEKey;
import javax.crypto.spec.PBEKeySpec;
import rontolisp.internal.BlockDigest;
import rontolisp.internal.DerivedKey;
import rontolisp.internal.Hashing;
import rontolisp.internal.InstalledProvider;

// PBKDF2 (RFC 8018, section 5.2) over HMAC of one of the digests: a password, which is
// the UTF-8 of its characters, a salt, a count of iterations and a length in bits make a
// key of that many whole bytes. A factory of a name is made with the digest of that name,
// so that a program naming PBKDF2WithHmacSHA256 holds no other digest.
public class SecretKeyFactory {

    private final String algorithm;

    private final BlockDigest digest;

    // Whether a key or a spec was refused yet, by any of generateSecret, getKeySpec and
    // translateKey. The first refusal that is no GeneralSecurityException is answered as
    // that member's, as a JDK's factory does where no provider was named; every later
    // refusal as it comes.
    private boolean refused;

    private SecretKeyFactory(String algorithm, BlockDigest digest) {
        this.algorithm = algorithm;
        this.digest = digest;
    }

    // The factory of a name over a digest at its start: what getInstance makes.
    public static SecretKeyFactory of(String algorithm, BlockDigest digest) {
        return new SecretKeyFactory(algorithm, digest);
    }

    // Lowered where it is called: a name written as a constant is its digest's class
    // there; any other name is looked up.
    public static final native SecretKeyFactory getInstance(String algorithm) throws NoSuchAlgorithmException;

    public static final SecretKeyFactory lookup(String algorithm) throws NoSuchAlgorithmException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        int digest = Hashing.factory(algorithm);
        if (digest < 0) {
            throw new NoSuchAlgorithmException(algorithm + " SecretKeyFactory not available");
        }
        return of(algorithm, Hashing.engine(digest, Hashing.name(digest)));
    }

    public static final SecretKeyFactory getInstance(String algorithm, String provider)
            throws NoSuchAlgorithmException, NoSuchProviderException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        Hashing.provided(provider);
        int digest = Hashing.factory(algorithm);
        if (digest < 0 || !provider.equals("SunJCE")) {
            throw new NoSuchAlgorithmException("no such algorithm: " + algorithm + " for provider " + provider);
        }
        SecretKeyFactory named = of(algorithm, Hashing.engine(digest, Hashing.name(digest)));
        named.refused = true;
        return named;
    }

    public static final SecretKeyFactory getInstance(String algorithm, Provider provider)
            throws NoSuchAlgorithmException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        Hashing.provided(provider, "SecretKeyFactory", algorithm);
        int digest = Hashing.factory(algorithm);
        if (digest < 0 || !provider.getName().equals("SunJCE")) {
            throw new NoSuchAlgorithmException(
                    "no such algorithm: " + algorithm + " for provider " + provider.getName());
        }
        SecretKeyFactory given = of(algorithm, Hashing.engine(digest, Hashing.name(digest)));
        given.refused = true;
        return given;
    }

    // What a name a JDK has no factory of answers, where the name was a constant.
    public static SecretKeyFactory absent(String algorithm) throws NoSuchAlgorithmException {
        throw new NoSuchAlgorithmException(algorithm + " SecretKeyFactory not available");
    }

    public final String getAlgorithm() {
        return algorithm;
    }

    public final Provider getProvider() {
        return InstalledProvider.named("SunJCE");
    }

    public final SecretKey generateSecret(KeySpec keySpec) throws InvalidKeySpecException {
        try {
            return derived(keySpec);
        } catch (InvalidKeySpecException e) {
            refused = true;
            throw e;
        } catch (RuntimeException e) {
            if (first()) {
                throw new InvalidKeySpecException("Could not generate secret key", e);
            }
            throw e;
        }
    }

    // A PBEKey's password, salt and count, and its length in bits, as a PBEKeySpec.
    public final KeySpec getKeySpec(SecretKey key, Class<?> keySpec) throws InvalidKeySpecException {
        try {
            if (!(key instanceof PBEKey)) {
                throw new InvalidKeySpecException("Only PBEKey is accepted");
            }
            if (keySpec == null || !keySpec.isAssignableFrom(PBEKeySpec.class)) {
                throw new InvalidKeySpecException("Only PBEKeySpec is accepted");
            }
            return spec((PBEKey) key);
        } catch (InvalidKeySpecException e) {
            refused = true;
            throw e;
        } catch (RuntimeException e) {
            if (first()) {
                throw new InvalidKeySpecException("Could not get key spec", e);
            }
            throw e;
        }
    }

    // A key of this factory as it is, and a PBEKey of its algorithm derived again from its
    // password, salt and count, as long as its bytes.
    public final SecretKey translateKey(SecretKey key) throws InvalidKeyException {
        try {
            String name = "PBKDF2With" + Hashing.hmacName(digest.digestNumber());
            if (key == null || !key.getAlgorithm().equalsIgnoreCase(name) || !key.getFormat().equalsIgnoreCase("RAW")) {
                throw new InvalidKeyException("Only " + name + " key with RAW format is accepted");
            }
            if (key instanceof DerivedKey) {
                return key;
            }
            if (!(key instanceof PBEKey)) {
                throw new InvalidKeyException("Only PBEKey is accepted");
            }
            PBEKeySpec spec = spec((PBEKey) key);
            return key(spec.getPassword(), spec.getSalt(), spec.getIterationCount(), spec.getKeyLength());
        } catch (InvalidKeyException e) {
            refused = true;
            throw e;
        } catch (RuntimeException e) {
            if (first()) {
                throw new InvalidKeyException("Could not translate key", e);
            }
            throw e;
        }
    }

    // Whether this refusal is the factory's first.
    private boolean first() {
        boolean first = !refused;
        refused = true;
        return first;
    }

    // What a PBEKey answers as a spec; the password array it answered is cleared after,
    // as a JDK clears it.
    private static PBEKeySpec spec(PBEKey key) {
        char[] password = key.getPassword();
        try {
            return new PBEKeySpec(password, key.getSalt(), key.getIterationCount(), key.getEncoded().length * 8);
        } finally {
            if (password != null) {
                for (int i = 0; i < password.length; i++) {
                    password[i] = 0;
                }
            }
        }
    }

    private SecretKey derived(KeySpec keySpec) throws InvalidKeySpecException {
        if (!(keySpec instanceof PBEKeySpec)) {
            throw new InvalidKeySpecException("Only PBEKeySpec is accepted");
        }
        PBEKeySpec spec = (PBEKeySpec) keySpec;
        char[] chars = spec.getPassword();
        byte[] salt = spec.getSalt();
        if (salt == null) {
            throw new InvalidKeySpecException("Salt not found");
        }
        int bits = spec.getKeyLength();
        if (bits <= 0) {
            throw new InvalidKeySpecException("Key length not found");
        }
        return key(chars, salt, spec.getIterationCount(), bits);
    }

    // The key of BITS / 8 bytes a password, a salt and a count derive.
    private SecretKey key(char[] chars, byte[] salt, int iterations, int bits) {
        byte[] key = digest.fresh().derive(utf8(chars), salt, iterations, bits / 8);
        return new DerivedKey(key, "PBKDF2With" + Hashing.hmacName(digest.digestNumber()), chars, salt, iterations);
    }

    // The UTF-8 of a password's characters, which are UTF-16 units: two surrogates in
    // turn are the character they spell, one with no partner is '?', as a JDK has it.
    private static byte[] utf8(char[] chars) {
        byte[] out = new byte[3 * chars.length];
        int n = 0;
        for (int i = 0; i < chars.length; i++) {
            int c = chars[i];
            if (c >= 0xd800 && c <= 0xdbff && i + 1 < chars.length && chars[i + 1] >= 0xdc00
                    && chars[i + 1] <= 0xdfff) {
                int code = 0x10000 + ((c - 0xd800) << 10) + (chars[i + 1] - 0xdc00);
                i++;
                out[n] = (byte) (0xf0 | (code >> 18));
                out[n + 1] = (byte) (0x80 | ((code >> 12) & 0x3f));
                out[n + 2] = (byte) (0x80 | ((code >> 6) & 0x3f));
                out[n + 3] = (byte) (0x80 | (code & 0x3f));
                n += 4;
            } else if (c >= 0xd800 && c <= 0xdfff) {
                out[n] = '?';
                n++;
            } else if (c < 0x80) {
                out[n] = (byte) c;
                n++;
            } else if (c < 0x800) {
                out[n] = (byte) (0xc0 | (c >> 6));
                out[n + 1] = (byte) (0x80 | (c & 0x3f));
                n += 2;
            } else {
                out[n] = (byte) (0xe0 | (c >> 12));
                out[n + 1] = (byte) (0x80 | ((c >> 6) & 0x3f));
                out[n + 2] = (byte) (0x80 | (c & 0x3f));
                n += 3;
            }
        }
        byte[] encoded = new byte[n];
        System.arraycopy(out, 0, encoded, 0, n);
        return encoded;
    }
}
