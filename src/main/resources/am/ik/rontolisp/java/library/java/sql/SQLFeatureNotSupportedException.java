// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

public class SQLFeatureNotSupportedException extends SQLNonTransientException {

    public SQLFeatureNotSupportedException() {
    }

    public SQLFeatureNotSupportedException(String reason) {
        super(reason);
    }

    public SQLFeatureNotSupportedException(String reason, String SQLState) {
        super(reason, SQLState);
    }

    public SQLFeatureNotSupportedException(String reason, String SQLState, int vendorCode) {
        super(reason, SQLState, vendorCode);
    }

    public SQLFeatureNotSupportedException(Throwable cause) {
        super(cause);
    }

    public SQLFeatureNotSupportedException(String reason, Throwable cause) {
        super(reason, cause);
    }

    public SQLFeatureNotSupportedException(String reason, String SQLState, Throwable cause) {
        super(reason, SQLState, cause);
    }

    public SQLFeatureNotSupportedException(String reason, String SQLState, int vendorCode, Throwable cause) {
        super(reason, SQLState, vendorCode, cause);
    }

}
