// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.sql;

import java.sql.SQLException;
import java.sql.ShardingKey;

public interface PooledConnectionBuilder {

    PooledConnectionBuilder user(String username);

    PooledConnectionBuilder password(String password);

    PooledConnectionBuilder shardingKey(ShardingKey shardingKey);

    PooledConnectionBuilder superShardingKey(ShardingKey superShardingKey);

    PooledConnection build() throws SQLException;

}
