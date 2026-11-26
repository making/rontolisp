// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

// A warning writes one line to DriverManager's log writer, in the words a JDK 25 writes,
// and no trace.
public class SQLWarning extends SQLException {

    public SQLWarning(String reason, String SQLState, int vendorCode) {
        super(reason, SQLState, vendorCode, true);
        DriverManager.println("SQLWarning: reason(" + reason + ") SQLState(" + SQLState + ") vendor code(" + vendorCode + ")");
    }

    public SQLWarning(String reason, String SQLState) {
        super(reason, SQLState, 0, true);
        DriverManager.println("SQLWarning: reason(" + reason + ") SQLState(" + SQLState + ")");
    }

    public SQLWarning(String reason) {
        super(reason, null, 0, true);
        DriverManager.println("SQLWarning: reason(" + reason + ")");
    }

    public SQLWarning() {
        super(null, null, 0, true);
        DriverManager.println("SQLWarning: ");
    }

    public SQLWarning(Throwable cause) {
        super(cause == null ? null : cause.toString(), null, 0, cause, true);
        DriverManager.println("SQLWarning");
    }

    public SQLWarning(String reason, Throwable cause) {
        super(reason, null, 0, cause, true);
        DriverManager.println("SQLWarning : reason(" + reason + ")");
    }

    public SQLWarning(String reason, String SQLState, Throwable cause) {
        super(reason, SQLState, 0, cause, true);
        DriverManager.println("SQLWarning: reason(" + reason + ") SQLState(" + SQLState + ")");
    }

    public SQLWarning(String reason, String SQLState, int vendorCode, Throwable cause) {
        super(reason, SQLState, vendorCode, cause, true);
        DriverManager.println("SQLWarning: reason(" + reason + ") SQLState(" + SQLState + ") vendor code(" + vendorCode + ")");
    }

    public SQLWarning getNextWarning() {
        SQLException next = getNextException();
        if (next == null || next instanceof SQLWarning) {
            return (SQLWarning) next;
        }
        throw new Error("SQLWarning chain holds value that is not a SQLWarning");
    }

    public void setNextWarning(SQLWarning w) {
        setNextException(w);
    }

}
