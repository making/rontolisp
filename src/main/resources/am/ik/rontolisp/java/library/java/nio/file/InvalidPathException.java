// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file;

import java.util.Objects;

public class InvalidPathException extends IllegalArgumentException {

    private final String input;

    private final int index;

    public InvalidPathException(String input, String reason, int index) {
        super(Objects.requireNonNull(reason));
        if (index < -1) {
            throw new IllegalArgumentException();
        }
        this.input = Objects.requireNonNull(input);
        this.index = index;
    }

    public InvalidPathException(String input, String reason) {
        this(input, reason, -1);
    }

    public String getInput() {
        return input;
    }

    public String getReason() {
        return super.getMessage();
    }

    public int getIndex() {
        return index;
    }

    public String getMessage() {
        return getReason() + (index > -1 ? " at index " + index : "") + ": " + input;
    }
}
