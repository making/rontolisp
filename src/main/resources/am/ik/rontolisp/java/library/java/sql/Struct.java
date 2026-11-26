// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

import java.util.Map;

public interface Struct {

    String getSQLTypeName() throws SQLException;

    Object[] getAttributes() throws SQLException;

    Object[] getAttributes(Map<String, Class<?>> map) throws SQLException;

}
