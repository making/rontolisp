// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.sql;

import java.sql.Connection;
import java.sql.SQLException;

public interface PooledConnection {

    Connection getConnection() throws SQLException;

    void close() throws SQLException;

    void addConnectionEventListener(ConnectionEventListener listener);

    void removeConnectionEventListener(ConnectionEventListener listener);

    void addStatementEventListener(StatementEventListener listener);

    void removeStatementEventListener(StatementEventListener listener);

}
