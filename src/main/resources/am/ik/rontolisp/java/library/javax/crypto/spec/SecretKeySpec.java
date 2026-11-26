// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.crypto.spec;

import java.security.spec.KeySpec;
import javax.crypto.SecretKey;
import rontolisp.internal.DerivedKey;

public class SecretKeySpec implements KeySpec, SecretKey {

    private final byte[] key;

    private final String algorithm;

    public SecretKeySpec(byte[] key, String algorithm) {
        if (key == null || algorithm == null) {
            throw new IllegalArgumentException("Missing argument");
        }
        if (key.length == 0) {
            throw new IllegalArgumentException("Empty key");
        }
        this.key = key.clone();
        this.algorithm = algorithm;
    }

    public SecretKeySpec(byte[] key, int offset, int len, String algorithm) {
        if (key == null || algorithm == null) {
            throw new IllegalArgumentException("Missing argument");
        }
        if (key.length == 0) {
            throw new IllegalArgumentException("Empty key");
        }
        if (offset < 0) {
            throw new ArrayIndexOutOfBoundsException("offset is negative");
        }
        if (len < 0) {
            throw new ArrayIndexOutOfBoundsException("len is negative");
        }
        if (key.length - offset < len) {
            throw new IllegalArgumentException("Invalid offset/length combination");
        }
        this.key = new byte[len];
        System.arraycopy(key, offset, this.key, 0, len);
        this.algorithm = algorithm;
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

    // A number of our own, the same for keys that are equal.
    public int hashCode() {
        return DerivedKey.hash(key, algorithm);
    }

    public boolean equals(Object obj) {
        return DerivedKey.same(this, obj);
    }
}
