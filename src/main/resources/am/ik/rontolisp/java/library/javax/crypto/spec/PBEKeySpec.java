// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.crypto.spec;

import java.security.spec.KeySpec;

public class PBEKeySpec implements KeySpec {

    private char[] password;

    private byte[] salt;

    private int iterationCount;

    private int keyLength;

    public PBEKeySpec(char[] password) {
        this.password = password == null ? new char[0] : password.clone();
    }

    public PBEKeySpec(char[] password, byte[] salt, int iterationCount, int keyLength) {
        this(password, salt, iterationCount);
        if (keyLength <= 0) {
            throw new IllegalArgumentException("invalid keyLength value");
        }
        this.keyLength = keyLength;
    }

    public PBEKeySpec(char[] password, byte[] salt, int iterationCount) {
        this.password = password == null ? new char[0] : password.clone();
        if (salt == null) {
            throw new NullPointerException("the salt parameter must be non-null");
        }
        if (salt.length == 0) {
            throw new IllegalArgumentException("the salt parameter must not be empty");
        }
        if (iterationCount <= 0) {
            throw new IllegalArgumentException("invalid iterationCount value");
        }
        this.salt = salt.clone();
        this.iterationCount = iterationCount;
    }

    public final void clearPassword() {
        if (password != null) {
            for (int i = 0; i < password.length; i++) {
                password[i] = ' ';
            }
            password = null;
        }
    }

    public final char[] getPassword() {
        if (password == null) {
            throw new IllegalStateException("password has been cleared");
        }
        return password.clone();
    }

    public final byte[] getSalt() {
        return salt == null ? null : salt.clone();
    }

    public final int getIterationCount() {
        return iterationCount;
    }

    public final int getKeyLength() {
        return keyLength;
    }
}
