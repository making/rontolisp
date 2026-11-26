// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.sql;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

public interface ConnectionPoolDataSource extends CommonDataSource {

    PooledConnection getPooledConnection() throws SQLException;

    PooledConnection getPooledConnection(String user, String password) throws SQLException;

    PrintWriter getLogWriter() throws SQLException;

    void setLogWriter(PrintWriter out) throws SQLException;

    void setLoginTimeout(int seconds) throws SQLException;

    int getLoginTimeout() throws SQLException;

    default PooledConnectionBuilder createPooledConnectionBuilder() throws SQLException {
        throw new SQLFeatureNotSupportedException("createPooledConnectionBuilder not implemented");
    }

}
