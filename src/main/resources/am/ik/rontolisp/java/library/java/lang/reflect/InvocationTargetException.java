// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

public class InvocationTargetException extends ReflectiveOperationException {

    private final Throwable target;

    protected InvocationTargetException() {
        super((Throwable) null);
        this.target = null;
    }

    public InvocationTargetException(Throwable target) {
        super((Throwable) null);
        this.target = target;
    }

    public InvocationTargetException(Throwable target, String message) {
        super(message, null);
        this.target = target;
    }

    public Throwable getTargetException() {
        return target;
    }

    public Throwable getCause() {
        return target;
    }
}
