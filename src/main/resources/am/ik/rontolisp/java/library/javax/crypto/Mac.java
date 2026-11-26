// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.crypto;

import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.ProviderException;
import java.security.spec.AlgorithmParameterSpec;
import rontolisp.internal.BlockDigest;
import rontolisp.internal.Hashing;
import rontolisp.internal.InstalledProvider;

// An HMAC (RFC 2104) over one of the digests: the digest at its start taken as the inner
// state once the key's padded block is in it, a second as the outer, a third the one the
// message goes into. A Mac of a name is made with the digest of that name, so that a
// program naming HmacSHA256 holds no other digest.
//
// A Mac of getInstance(String) has the provider a JDK chooses late: the first member other
// than getAlgorithm chooses it, an init with a key whose format is RAW among them. An init
// failing before any choice leaves a Mac of no provider, which every member then refuses.
public class Mac implements Cloneable {

    private static final int UNCHOSEN = 0;

    private static final int CHOSEN = 1;

    private static final int NONE = 2;

    private final String algorithm;

    private final BlockDigest inner;

    private final BlockDigest outer;

    private final BlockDigest work;

    private boolean initialized;

    private int provider;

    private Mac(String algorithm, BlockDigest inner, BlockDigest outer, BlockDigest work) {
        this.algorithm = algorithm;
        this.inner = inner;
        this.outer = outer;
        this.work = work;
    }

    // The Mac of a name over a digest at its start: what getInstance makes.
    public static Mac of(String algorithm, BlockDigest digest) {
        return new Mac(algorithm, digest, digest.fresh(), digest.fresh());
    }

    // Lowered where it is called: a name written as a constant is its digest's class
    // there; any other name is looked up.
    public static final native Mac getInstance(String algorithm) throws NoSuchAlgorithmException;

    public static final Mac lookup(String algorithm) throws NoSuchAlgorithmException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        int digest = Hashing.mac(algorithm);
        if (digest < 0) {
            throw new NoSuchAlgorithmException("Algorithm " + algorithm + " not available");
        }
        return of(algorithm, Hashing.engine(digest, Hashing.name(digest)));
    }

    public static final Mac getInstance(String algorithm, String provider)
            throws NoSuchAlgorithmException, NoSuchProviderException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        Hashing.provided(provider);
        int digest = Hashing.mac(algorithm);
        if (digest < 0 || !provider.equals("SunJCE")) {
            throw new NoSuchAlgorithmException("no such algorithm: " + algorithm + " for provider " + provider);
        }
        Mac named = of(algorithm, Hashing.engine(digest, Hashing.name(digest)));
        named.provider = CHOSEN;
        return named;
    }

    public static final Mac getInstance(String algorithm, Provider provider) throws NoSuchAlgorithmException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        Hashing.provided(provider, "Mac", algorithm);
        int digest = Hashing.mac(algorithm);
        if (digest < 0 || !provider.getName().equals("SunJCE")) {
            throw new NoSuchAlgorithmException(
                    "no such algorithm: " + algorithm + " for provider " + provider.getName());
        }
        Mac given = of(algorithm, Hashing.engine(digest, Hashing.name(digest)));
        given.provider = CHOSEN;
        return given;
    }

    // What a name a JDK has no Mac of answers, where the name was a constant.
    public static Mac absent(String algorithm) throws NoSuchAlgorithmException {
        throw new NoSuchAlgorithmException("Algorithm " + algorithm + " not available");
    }

    public final String getAlgorithm() {
        return algorithm;
    }

    // SunJCE, chosen here where it was not.
    public final Provider getProvider() {
        chosen();
        return InstalledProvider.named("SunJCE");
    }

    public final int getMacLength() {
        chosen();
        return inner.getDigestLength();
    }

    public final void init(Key key) throws InvalidKeyException {
        try {
            init(key, null);
        } catch (InvalidAlgorithmParameterException e) {
            throw new InvalidKeyException(e);
        }
    }

    // HMAC takes no parameters.
    public final void init(Key key, AlgorithmParameterSpec params)
            throws InvalidKeyException, InvalidAlgorithmParameterException {
        if (provider == CHOSEN) {
            keyed(key, params);
            return;
        }
        if (provider == UNCHOSEN && key != null && "RAW".equals(key.getFormat())) {
            provider = NONE;
            keyed(key, params);
            provider = CHOSEN;
            return;
        }
        provider = NONE;
        throw new InvalidKeyException(
                "No installed provider supports this key: " + (key == null ? "(null)" : key.getClass().getName()));
    }

    private void keyed(Key key, AlgorithmParameterSpec params)
            throws InvalidKeyException, InvalidAlgorithmParameterException {
        if (params != null) {
            throw new InvalidAlgorithmParameterException("HMAC does not use parameters");
        }
        if (!(key instanceof SecretKey)) {
            throw new InvalidKeyException("Secret key expected");
        }
        byte[] encoded = key.getEncoded();
        if (encoded == null) {
            throw new InvalidKeyException("Missing key data");
        }
        inner.reset();
        outer.reset();
        inner.keyed(encoded, outer);
        work.restore(inner);
        initialized = true;
    }

    // The provider chosen where it was not, and a Mac of none refused.
    private void chosen() {
        if (provider == NONE) {
            throw new ProviderException("Could not construct MacSpi instance");
        }
        provider = CHOSEN;
    }

    private void initialized() {
        chosen();
        if (!initialized) {
            throw new IllegalStateException("MAC not initialized");
        }
    }

    public final void update(byte input) throws IllegalStateException {
        initialized();
        work.update(input);
    }

    public final void update(byte[] input) throws IllegalStateException {
        initialized();
        if (input != null) {
            work.update(input, 0, input.length);
        }
    }

    public final void update(byte[] input, int offset, int len) throws IllegalStateException {
        initialized();
        if (input != null) {
            if (offset < 0 || len < 0 || len > input.length - offset) {
                throw new IllegalArgumentException("Bad arguments");
            }
            work.update(input, offset, len);
        }
    }

    // the bytes from the position to the limit, which it moves to the limit
    public final void update(ByteBuffer input) {
        initialized();
        if (input == null) {
            throw new IllegalArgumentException("Buffer must not be null");
        }
        byte[] taken = new byte[input.remaining()];
        input.get(taken);
        work.update(taken, 0, taken.length);
    }

    public final byte[] doFinal() throws IllegalStateException {
        initialized();
        byte[] mac = new byte[inner.getDigestLength()];
        work.hmacFinish(outer, work, mac, 0);
        work.restore(inner);
        return mac;
    }

    public final void doFinal(byte[] output, int outOffset) throws ShortBufferException, IllegalStateException {
        initialized();
        int length = inner.getDigestLength();
        if (output == null || output.length - outOffset < length) {
            throw new ShortBufferException("Cannot store MAC in output buffer");
        }
        System.arraycopy(doFinal(), 0, output, outOffset, length);
    }

    public final byte[] doFinal(byte[] input) throws IllegalStateException {
        update(input);
        return doFinal();
    }

    public final void reset() {
        chosen();
        if (initialized) {
            work.restore(inner);
        }
    }

    public final Object clone() throws CloneNotSupportedException {
        chosen();
        Mac copy = new Mac(algorithm, inner.fresh(), inner.fresh(), inner.fresh());
        copy.provider = CHOSEN;
        copy.inner.restore(inner);
        copy.outer.restore(outer);
        copy.work.restore(work);
        copy.initialized = initialized;
        return copy;
    }
}
