// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

public class InterruptedIOException extends IOException {

    public int bytesTransferred = 0;

    public InterruptedIOException() {
    }

    public InterruptedIOException(String message) {
        super(message);
    }
}
