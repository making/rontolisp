// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security;

// With no security manager, as a JDK since 24 has none, a privileged action is the
// action run: what it throws unchecked is thrown as it is, a checked exception wrapped.
@Deprecated(since = "17", forRemoval = true)
public final class AccessController {

    private AccessController() {
    }

    public static <T> T doPrivileged(PrivilegedAction<T> action) {
        return action.run();
    }

    public static <T> T doPrivileged(PrivilegedExceptionAction<T> action) throws PrivilegedActionException {
        try {
            return action.run();
        }
        catch (RuntimeException e) {
            throw e;
        }
        catch (Exception e) {
            throw new PrivilegedActionException(e);
        }
    }
}
