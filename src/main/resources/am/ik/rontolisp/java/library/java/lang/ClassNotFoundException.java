// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

public class ClassNotFoundException extends ReflectiveOperationException {

    private final Throwable ex;

    public ClassNotFoundException() {
        super((Throwable) null);
        this.ex = null;
    }

    public ClassNotFoundException(String message) {
        super(message, null);
        this.ex = null;
    }

    public ClassNotFoundException(String message, Throwable ex) {
        super(message, ex);
        this.ex = ex;
    }

    public Throwable getException() {
        return ex;
    }
}
