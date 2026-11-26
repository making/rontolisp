// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.charset;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;

// What a step of a coder ends in. A result of an error is one object a length, as a JDK
// answers (malformedForLength(n) == malformedForLength(n) for every n asked): a chain of
// the results made so far, one a kind.
public class CoderResult {

    private static final int UNDER = 0;

    private static final int OVER = 1;

    private static final int MALFORMED = 2;

    private static final int UNMAPPABLE = 3;

    public static final CoderResult UNDERFLOW = new CoderResult(UNDER, 0, null);

    public static final CoderResult OVERFLOW = new CoderResult(OVER, 0, null);

    private static CoderResult malformed;

    private static CoderResult unmappable;

    private final int type;

    private final int length;

    private final CoderResult next;

    private CoderResult(int type, int length, CoderResult next) {
        this.type = type;
        this.length = length;
        this.next = next;
    }

    private static CoderResult find(CoderResult chain, int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Non-positive length");
        }
        for (CoderResult r = chain; r != null; r = r.next) {
            if (r.length == length) {
                return r;
            }
        }
        return null;
    }

    public static CoderResult malformedForLength(int length) {
        CoderResult r = find(malformed, length);
        if (r == null) {
            r = new CoderResult(MALFORMED, length, malformed);
            malformed = r;
        }
        return r;
    }

    public static CoderResult unmappableForLength(int length) {
        CoderResult r = find(unmappable, length);
        if (r == null) {
            r = new CoderResult(UNMAPPABLE, length, unmappable);
            unmappable = r;
        }
        return r;
    }

    public boolean isUnderflow() {
        return type == UNDER;
    }

    public boolean isOverflow() {
        return type == OVER;
    }

    public boolean isError() {
        return type >= MALFORMED;
    }

    public boolean isMalformed() {
        return type == MALFORMED;
    }

    public boolean isUnmappable() {
        return type == UNMAPPABLE;
    }

    public int length() {
        if (type < MALFORMED) {
            throw new UnsupportedOperationException();
        }
        return length;
    }

    public void throwException() throws CharacterCodingException {
        if (type == UNDER) {
            throw new BufferUnderflowException();
        }
        if (type == OVER) {
            throw new BufferOverflowException();
        }
        if (type == MALFORMED) {
            throw new MalformedInputException(length);
        }
        throw new UnmappableCharacterException(length);
    }

    public String toString() {
        return type == UNDER ? "UNDERFLOW"
                : type == OVER ? "OVERFLOW" : (type == MALFORMED ? "MALFORMED[" : "UNMAPPABLE[") + length + "]";
    }
}
