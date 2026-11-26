// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.sql;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.EventObject;

public class StatementEvent extends EventObject {

    private PreparedStatement statement;

    private SQLException exception;

    public StatementEvent(PooledConnection con, PreparedStatement statement) {
        super(con);
        this.statement = statement;
    }

    public StatementEvent(PooledConnection con, PreparedStatement statement, SQLException exception) {
        super(con);
        this.statement = statement;
        this.exception = exception;
    }

    public PreparedStatement getStatement() {
        return statement;
    }

    public SQLException getSQLException() {
        return exception;
    }

}
