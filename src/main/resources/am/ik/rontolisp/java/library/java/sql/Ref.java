// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

import java.util.Map;

public interface Ref {

    String getBaseTypeName() throws SQLException;

    Object getObject(Map<String, Class<?>> map) throws SQLException;

    Object getObject() throws SQLException;

    void setObject(Object value) throws SQLException;

}
