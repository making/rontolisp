// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.sql;

import java.sql.SQLException;
import java.util.EventObject;

public class ConnectionEvent extends EventObject {

    private SQLException ex;

    public ConnectionEvent(PooledConnection con) {
        super(con);
    }

    public ConnectionEvent(PooledConnection con, SQLException ex) {
        super(con);
        this.ex = ex;
    }

    public SQLException getSQLException() {
        return ex;
    }

}
