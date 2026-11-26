// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

// setAccessible(true) of a member of a class of the JDK that a program may not open.
public class InaccessibleObjectException extends RuntimeException {

    public InaccessibleObjectException() {
        super();
    }

    public InaccessibleObjectException(String msg) {
        super(msg);
    }
}
