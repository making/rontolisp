// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security;

import java.nio.ByteBuffer;
import rontolisp.internal.BlockDigest;
import rontolisp.internal.Hashing;
import rontolisp.internal.InstalledProvider;

// A digest of a name is an object of the class of its algorithm (rontolisp.internal's
// Md5, Sha1, Sha256 and Sha512), made only by a program that names that algorithm or
// computes the name it asks for. What this class adds to the engine's members is the
// checks of the arguments, in the order and with the words a JDK has, and whether a
// digest is under way.
public abstract class MessageDigest extends MessageDigestSpi {

    private final String algorithm;

    private boolean progressing;

    protected MessageDigest(String algorithm) {
        this.algorithm = algorithm;
    }

    // Lowered where it is called: a name written as a constant is its algorithm's class
    // there, so that a program naming SHA-256 holds no other digest; any other name is
    // looked up.
    public static native MessageDigest getInstance(String algorithm) throws NoSuchAlgorithmException;

    public static MessageDigest lookup(String algorithm) throws NoSuchAlgorithmException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        int digest = Hashing.digest(algorithm);
        if (digest < 0) {
            throw new NoSuchAlgorithmException(algorithm + " MessageDigest not available");
        }
        return Hashing.engine(digest, algorithm);
    }

    // What a name of no digest answers, where the name was a constant.
    public static MessageDigest absent(String algorithm) throws NoSuchAlgorithmException {
        throw new NoSuchAlgorithmException(algorithm + " MessageDigest not available");
    }

    public static MessageDigest getInstance(String algorithm, String provider)
            throws NoSuchAlgorithmException, NoSuchProviderException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        Hashing.provided(provider);
        int digest = Hashing.digest(algorithm);
        if (digest < 0 || !provider.equals("SUN")) {
            throw new NoSuchAlgorithmException("no such algorithm: " + algorithm + " for provider " + provider);
        }
        return Hashing.engine(digest, algorithm);
    }

    public static MessageDigest getInstance(String algorithm, Provider provider) throws NoSuchAlgorithmException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        Hashing.provided(provider, "MessageDigest", algorithm);
        int digest = Hashing.digest(algorithm);
        if (digest < 0 || !provider.getName().equals("SUN")) {
            throw new NoSuchAlgorithmException(
                    "no such algorithm: " + algorithm + " for provider " + provider.getName());
        }
        return Hashing.engine(digest, algorithm);
    }

    // SUN for a digest of the subset, none for one of the program's own.
    public final Provider getProvider() {
        return this instanceof BlockDigest ? InstalledProvider.named("SUN") : null;
    }

    public void update(byte input) {
        engineUpdate(input);
        progressing = true;
    }

    public void update(byte[] input, int offset, int len) {
        if (input == null) {
            throw new IllegalArgumentException("No input buffer given");
        }
        if (input.length - offset < len) {
            throw new IllegalArgumentException("Input buffer too short");
        }
        if (offset < 0 || len < 0) {
            throw new ArrayIndexOutOfBoundsException(
                    "Range [" + offset + ", " + offset + " + " + len + ") out of bounds for length " + input.length);
        }
        engineUpdate(input, offset, len);
        progressing = true;
    }

    public void update(byte[] input) {
        engineUpdate(input, 0, input.length);
        progressing = true;
    }

    // the bytes from the position to the limit, which it moves to the limit
    public final void update(ByteBuffer input) {
        if (input == null) {
            throw new NullPointerException();
        }
        byte[] taken = new byte[input.remaining()];
        input.get(taken);
        engineUpdate(taken, 0, taken.length);
        progressing = true;
    }

    public byte[] digest() {
        byte[] result = engineDigest();
        progressing = false;
        return result;
    }

    public int digest(byte[] buf, int offset, int len) throws DigestException {
        if (buf == null) {
            throw new IllegalArgumentException("No output buffer given");
        }
        if (buf.length - offset < len) {
            throw new IllegalArgumentException("Output buffer too small for specified offset and length");
        }
        int written = engineDigest(buf, offset, len);
        progressing = false;
        return written;
    }

    public byte[] digest(byte[] input) {
        update(input);
        return digest();
    }

    public String toString() {
        String provider = this instanceof BlockDigest ? "SUN" : "(no provider)";
        return algorithm + " Message Digest from " + provider + ", "
                + (progressing ? "<in progress>" : "<initialized>") + "\n";
    }

    // Whether two digests are the same bytes, in a time that depends on the first's
    // length alone.
    public static boolean isEqual(byte[] digesta, byte[] digestb) {
        if (digesta == digestb) {
            return true;
        }
        if (digesta == null || digestb == null) {
            return false;
        }
        int differing = digesta.length ^ digestb.length;
        int other = digestb.length;
        for (int i = 0; i < digesta.length; i++) {
            differing |= digesta[i] ^ (i < other ? digestb[i] : 0);
        }
        return differing == 0;
    }

    public void reset() {
        engineReset();
        progressing = false;
    }

    public final String getAlgorithm() {
        return algorithm;
    }

    public final int getDigestLength() {
        return engineGetDigestLength();
    }

    public Object clone() throws CloneNotSupportedException {
        MessageDigest copy = copied();
        if (copy == null) {
            throw new CloneNotSupportedException();
        }
        copy.progressing = progressing;
        return copy;
    }

    // A copy of this digest's state, or null for a digest that has none to give: what
    // clone() answers with.
    protected MessageDigest copied() {
        return null;
    }
}
