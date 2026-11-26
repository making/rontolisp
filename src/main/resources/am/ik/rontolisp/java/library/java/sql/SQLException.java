// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

import java.io.PrintWriter;
import java.util.Iterator;
import java.util.NoSuchElementException;

// A database's refusal: a text, a SQLState, a vendor's code and the exceptions chained
// after it. While DriverManager has a log writer, each public constructor writes the
// exception's trace to it, with the state and the code before or after it as a JDK 25
// writes them; a warning and the client-information refusal write their own lines.
public class SQLException extends Exception implements Iterable<Throwable> {

    private String SQLState;

    private int vendorCode;

    private SQLException next;

    public SQLException(String reason, String SQLState, int vendorCode) {
        super(reason);
        this.SQLState = SQLState;
        this.vendorCode = vendorCode;
        logged("SQLState(" + SQLState + ") vendor code(" + vendorCode + ")", null);
    }

    public SQLException(String reason, String SQLState) {
        super(reason);
        this.SQLState = SQLState;
        logged(null, "SQLException: SQLState(" + SQLState + ")");
    }

    public SQLException(String reason) {
        super(reason);
        logged(null, null);
    }

    public SQLException() {
        logged(null, null);
    }

    public SQLException(Throwable cause) {
        super(cause);
        logged(null, null);
    }

    public SQLException(String reason, Throwable cause) {
        super(reason, cause);
        logged(null, null);
    }

    public SQLException(String reason, String SQLState, Throwable cause) {
        super(reason, cause);
        this.SQLState = SQLState;
        logged(null, "SQLState(" + SQLState + ")");
    }

    public SQLException(String reason, String SQLState, int vendorCode, Throwable cause) {
        super(reason, cause);
        this.SQLState = SQLState;
        this.vendorCode = vendorCode;
        logged("SQLState(" + SQLState + ") vendor code(" + vendorCode + ")", null);
    }

    // The two that write nothing, the cause unset and set, for the subclasses that write
    // lines of their own.
    SQLException(String reason, String SQLState, int vendorCode, boolean quiet) {
        super(reason);
        this.SQLState = SQLState;
        this.vendorCode = vendorCode;
    }

    SQLException(String reason, String SQLState, int vendorCode, Throwable cause, boolean quiet) {
        super(reason, cause);
        this.SQLState = SQLState;
        this.vendorCode = vendorCode;
    }

    private void logged(String before, String after) {
        PrintWriter writer = DriverManager.getLogWriter();
        if (writer != null) {
            if (before != null) {
                DriverManager.println(before);
            }
            printStackTrace(writer);
            if (after != null) {
                DriverManager.println(after);
            }
        }
    }

    public String getSQLState() {
        return SQLState;
    }

    public int getErrorCode() {
        return vendorCode;
    }

    public SQLException getNextException() {
        return next;
    }

    /** Puts the exception at the end of the chain. */
    public void setNextException(SQLException ex) {
        SQLException last = this;
        while (last.next != null) {
            last = last.next;
        }
        last.next = ex;
    }

    /** Each exception of the chain, and after each the causes under it. */
    public Iterator<Throwable> iterator() {
        return new Iterator<Throwable>() {

            private SQLException exception = SQLException.this;

            private Throwable at = SQLException.this;

            public boolean hasNext() {
                return at != null;
            }

            public Throwable next() {
                if (at == null) {
                    throw new NoSuchElementException();
                }
                Throwable answer = at;
                at = at.getCause();
                if (at == null) {
                    exception = exception.getNextException();
                    at = exception;
                }
                return answer;
            }

            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

}
