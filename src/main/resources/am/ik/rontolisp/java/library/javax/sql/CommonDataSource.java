// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.sql;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.ShardingKeyBuilder;
import java.util.logging.Logger;

public interface CommonDataSource {

    PrintWriter getLogWriter() throws SQLException;

    void setLogWriter(PrintWriter out) throws SQLException;

    void setLoginTimeout(int seconds) throws SQLException;

    int getLoginTimeout() throws SQLException;

    Logger getParentLogger() throws SQLFeatureNotSupportedException;

    default ShardingKeyBuilder createShardingKeyBuilder() throws SQLException {
        throw new SQLFeatureNotSupportedException("createShardingKeyBuilder not implemented");
    }

}
