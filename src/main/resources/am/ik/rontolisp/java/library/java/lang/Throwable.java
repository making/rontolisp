// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

public class Throwable {

    private String message;

    public Throwable() {
    }

    public Throwable(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public String toString() {
        String name = getClass().getName();
        return message == null ? name : name + ": " + message;
    }
}
