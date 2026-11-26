// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

// Nothing throws it: a thread is never stopped from outside.
@Deprecated(since = "20", forRemoval = true)
public class ThreadDeath extends Error {

    public ThreadDeath() {
    }
}
