// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

// No target installs one, as a JDK since 24 does not (System.getSecurityManager is null,
// setting one is refused). A program may still make one; the frames of its stack are none
// here.
@Deprecated(since = "17", forRemoval = true)
public class SecurityManager {

    public SecurityManager() {
    }

    // the classes of the methods on the stack, the newest first: no frame is kept
    protected Class<?>[] getClassContext() {
        return new Class<?>[0];
    }
}
