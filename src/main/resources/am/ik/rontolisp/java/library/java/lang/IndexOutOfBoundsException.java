// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

public class IndexOutOfBoundsException extends RuntimeException {

    public IndexOutOfBoundsException() {
    }

    public IndexOutOfBoundsException(String message) {
        super(message);
    }

    public IndexOutOfBoundsException(int index) {
        super("Index out of range: " + index);
    }

    public IndexOutOfBoundsException(long index) {
        super("Index out of range: " + index);
    }
}
