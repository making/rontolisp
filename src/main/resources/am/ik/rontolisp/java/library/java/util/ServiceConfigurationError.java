// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public class ServiceConfigurationError extends Error {

    public ServiceConfigurationError(String msg) {
        super(msg);
    }

    public ServiceConfigurationError(String msg, Throwable cause) {
        super(msg, cause);
    }
}
