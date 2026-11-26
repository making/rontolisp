// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

public class SQLTransientException extends SQLException {

    public SQLTransientException() {
    }

    public SQLTransientException(String reason) {
        super(reason);
    }

    public SQLTransientException(String reason, String SQLState) {
        super(reason, SQLState);
    }

    public SQLTransientException(String reason, String SQLState, int vendorCode) {
        super(reason, SQLState, vendorCode);
    }

    public SQLTransientException(Throwable cause) {
        super(cause);
    }

    public SQLTransientException(String reason, Throwable cause) {
        super(reason, cause);
    }

    public SQLTransientException(String reason, String SQLState, Throwable cause) {
        super(reason, SQLState, cause);
    }

    public SQLTransientException(String reason, String SQLState, int vendorCode, Throwable cause) {
        super(reason, SQLState, vendorCode, cause);
    }

}
