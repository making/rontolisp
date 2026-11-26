// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

public interface ConnectionBuilder {

    ConnectionBuilder user(String username);

    ConnectionBuilder password(String password);

    ConnectionBuilder shardingKey(ShardingKey shardingKey);

    ConnectionBuilder superShardingKey(ShardingKey superShardingKey);

    Connection build() throws SQLException;

}
