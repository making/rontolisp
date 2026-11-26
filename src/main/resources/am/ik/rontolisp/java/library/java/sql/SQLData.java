// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

public interface SQLData {

    String getSQLTypeName() throws SQLException;

    void readSQL(SQLInput stream, String typeName) throws SQLException;

    void writeSQL(SQLOutput stream) throws SQLException;

}
