// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

public class SQLDataException extends SQLNonTransientException {

    public SQLDataException() {
    }

    public SQLDataException(String reason) {
        super(reason);
    }

    public SQLDataException(String reason, String SQLState) {
        super(reason, SQLState);
    }

    public SQLDataException(String reason, String SQLState, int vendorCode) {
        super(reason, SQLState, vendorCode);
    }

    public SQLDataException(Throwable cause) {
        super(cause);
    }

    public SQLDataException(String reason, Throwable cause) {
        super(reason, cause);
    }

    public SQLDataException(String reason, String SQLState, Throwable cause) {
        super(reason, SQLState, cause);
    }

    public SQLDataException(String reason, String SQLState, int vendorCode, Throwable cause) {
        super(reason, SQLState, vendorCode, cause);
    }

}
