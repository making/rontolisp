// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

public interface ParameterMetaData extends Wrapper {

    int parameterNoNulls = 0;

    int parameterNullable = 1;

    int parameterNullableUnknown = 2;

    int parameterModeUnknown = 0;

    int parameterModeIn = 1;

    int parameterModeInOut = 2;

    int parameterModeOut = 4;

    int getParameterCount() throws SQLException;

    int isNullable(int param) throws SQLException;

    boolean isSigned(int param) throws SQLException;

    int getPrecision(int param) throws SQLException;

    int getScale(int param) throws SQLException;

    int getParameterType(int param) throws SQLException;

    String getParameterTypeName(int param) throws SQLException;

    String getParameterClassName(int param) throws SQLException;

    int getParameterMode(int param) throws SQLException;

}
