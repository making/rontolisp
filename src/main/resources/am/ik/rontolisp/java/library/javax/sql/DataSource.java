// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.sql;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ConnectionBuilder;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Wrapper;

public interface DataSource extends CommonDataSource, Wrapper {

    Connection getConnection() throws SQLException;

    Connection getConnection(String username, String password) throws SQLException;

    PrintWriter getLogWriter() throws SQLException;

    void setLogWriter(PrintWriter out) throws SQLException;

    void setLoginTimeout(int seconds) throws SQLException;

    int getLoginTimeout() throws SQLException;

    default ConnectionBuilder createConnectionBuilder() throws SQLException {
        throw new SQLFeatureNotSupportedException("createConnectionBuilder not implemented");
    }

}
