// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

public class SQLInvalidAuthorizationSpecException extends SQLNonTransientException {

    public SQLInvalidAuthorizationSpecException() {
    }

    public SQLInvalidAuthorizationSpecException(String reason) {
        super(reason);
    }

    public SQLInvalidAuthorizationSpecException(String reason, String SQLState) {
        super(reason, SQLState);
    }

    public SQLInvalidAuthorizationSpecException(String reason, String SQLState, int vendorCode) {
        super(reason, SQLState, vendorCode);
    }

    public SQLInvalidAuthorizationSpecException(Throwable cause) {
        super(cause);
    }

    public SQLInvalidAuthorizationSpecException(String reason, Throwable cause) {
        super(reason, cause);
    }

    public SQLInvalidAuthorizationSpecException(String reason, String SQLState, Throwable cause) {
        super(reason, SQLState, cause);
    }

    public SQLInvalidAuthorizationSpecException(String reason, String SQLState, int vendorCode, Throwable cause) {
        super(reason, SQLState, vendorCode, cause);
    }

}
