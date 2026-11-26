// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import javax.crypto.SecretKey;
import javax.crypto.interfaces.PBEKey;

// The key a SecretKeyFactory of PBKDF2 derives: its bytes, which may be none, under the
// factory's algorithm, with the password, the salt and the count it was derived from.
// Equal, as a SecretKeySpec is, to a secret key of the same algorithm, whatever its case,
// and of the same bytes.
public final class DerivedKey implements PBEKey {

    private final byte[] key;

    private final String algorithm;

    private final char[] password;

    private final byte[] salt;

    private final int iterationCount;

    public DerivedKey(byte[] key, String algorithm, char[] password, byte[] salt, int iterationCount) {
        this.key = key;
        this.algorithm = algorithm;
        this.password = password;
        this.salt = salt;
        this.iterationCount = iterationCount;
    }

    public char[] getPassword() {
        return password.clone();
    }

    public byte[] getSalt() {
        return salt.clone();
    }

    public int getIterationCount() {
        return iterationCount;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getFormat() {
        return "RAW";
    }

    public byte[] getEncoded() {
        return key.clone();
    }

    public int hashCode() {
        return hash(key, algorithm);
    }

    public boolean equals(Object obj) {
        return same(this, obj);
    }

    // A number of our own over a key's bytes and its algorithm's name in one case.
    public static int hash(byte[] key, String algorithm) {
        int h = algorithm.toUpperCase().hashCode();
        for (int i = 0; i < key.length; i++) {
            h = 31 * h + key[i];
        }
        return h;
    }

    // Whether a key and an object are secret keys of one algorithm and the same bytes.
    public static boolean same(SecretKey key, Object obj) {
        if (key == obj) {
            return true;
        }
        if (!(obj instanceof SecretKey)) {
            return false;
        }
        SecretKey other = (SecretKey) obj;
        String name = other.getAlgorithm();
        return name != null && name.equalsIgnoreCase(key.getAlgorithm())
                && java.security.MessageDigest.isEqual(key.getEncoded(), other.getEncoded());
    }
}
