// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;

public interface SQLOutput {

    void writeString(String x) throws SQLException;

    void writeBoolean(boolean x) throws SQLException;

    void writeByte(byte x) throws SQLException;

    void writeShort(short x) throws SQLException;

    void writeInt(int x) throws SQLException;

    void writeLong(long x) throws SQLException;

    void writeFloat(float x) throws SQLException;

    void writeDouble(double x) throws SQLException;

    void writeBigDecimal(BigDecimal x) throws SQLException;

    void writeBytes(byte[] x) throws SQLException;

    void writeDate(Date x) throws SQLException;

    void writeTime(Time x) throws SQLException;

    void writeTimestamp(Timestamp x) throws SQLException;

    void writeCharacterStream(Reader x) throws SQLException;

    void writeAsciiStream(InputStream x) throws SQLException;

    void writeBinaryStream(InputStream x) throws SQLException;

    void writeObject(SQLData x) throws SQLException;

    void writeRef(Ref x) throws SQLException;

    void writeBlob(Blob x) throws SQLException;

    void writeClob(Clob x) throws SQLException;

    void writeStruct(Struct x) throws SQLException;

    void writeArray(Array x) throws SQLException;

    void writeURL(URL x) throws SQLException;

    void writeNString(String x) throws SQLException;

    void writeNClob(NClob x) throws SQLException;

    void writeRowId(RowId x) throws SQLException;

    void writeSQLXML(SQLXML x) throws SQLException;

    default void writeObject(Object x, SQLType targetSqlType) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

}
