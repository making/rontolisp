// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

public class SQLTransientConnectionException extends SQLTransientException {

    public SQLTransientConnectionException() {
    }

    public SQLTransientConnectionException(String reason) {
        super(reason);
    }

    public SQLTransientConnectionException(String reason, String SQLState) {
        super(reason, SQLState);
    }

    public SQLTransientConnectionException(String reason, String SQLState, int vendorCode) {
        super(reason, SQLState, vendorCode);
    }

    public SQLTransientConnectionException(Throwable cause) {
        super(cause);
    }

    public SQLTransientConnectionException(String reason, Throwable cause) {
        super(reason, cause);
    }

    public SQLTransientConnectionException(String reason, String SQLState, Throwable cause) {
        super(reason, SQLState, cause);
    }

    public SQLTransientConnectionException(String reason, String SQLState, int vendorCode, Throwable cause) {
        super(reason, SQLState, vendorCode, cause);
    }

}
