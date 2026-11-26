package am.ik.jdbc.postgresql;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Every abstract member of {@link ResultSet} as a refusal by name, so that the class
 * under it holds what the driver does and nothing else. Written by a script from the
 * interface's signatures; a member is changed in the subclass, not here.
 */
abstract class UnsupportedResultSet implements ResultSet {

	@Override
	public boolean absolute(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.absolute");
	}

	@Override
	public void afterLast() throws SQLException {
		throw Failures.unsupported("ResultSet.afterLast");
	}

	@Override
	public void beforeFirst() throws SQLException {
		throw Failures.unsupported("ResultSet.beforeFirst");
	}

	@Override
	public void cancelRowUpdates() throws SQLException {
		throw Failures.unsupported("ResultSet.cancelRowUpdates");
	}

	@Override
	public void clearWarnings() throws SQLException {
		throw Failures.unsupported("ResultSet.clearWarnings");
	}

	@Override
	public void close() throws SQLException {
		throw Failures.unsupported("ResultSet.close");
	}

	@Override
	public void deleteRow() throws SQLException {
		throw Failures.unsupported("ResultSet.deleteRow");
	}

	@Override
	public int findColumn(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.findColumn");
	}

	@Override
	public boolean first() throws SQLException {
		throw Failures.unsupported("ResultSet.first");
	}

	@Override
	public Array getArray(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getArray");
	}

	@Override
	public Array getArray(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getArray");
	}

	@Override
	public @Nullable InputStream getAsciiStream(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getAsciiStream");
	}

	@Override
	public @Nullable InputStream getAsciiStream(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getAsciiStream");
	}

	@Override
	public @Nullable BigDecimal getBigDecimal(String a, int b) throws SQLException {
		throw Failures.unsupported("ResultSet.getBigDecimal");
	}

	@Override
	public @Nullable BigDecimal getBigDecimal(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getBigDecimal");
	}

	@Override
	public @Nullable BigDecimal getBigDecimal(int a, int b) throws SQLException {
		throw Failures.unsupported("ResultSet.getBigDecimal");
	}

	@Override
	public @Nullable BigDecimal getBigDecimal(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getBigDecimal");
	}

	@Override
	public @Nullable InputStream getBinaryStream(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getBinaryStream");
	}

	@Override
	public @Nullable InputStream getBinaryStream(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getBinaryStream");
	}

	@Override
	public Blob getBlob(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getBlob");
	}

	@Override
	public Blob getBlob(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getBlob");
	}

	@Override
	public boolean getBoolean(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getBoolean");
	}

	@Override
	public boolean getBoolean(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getBoolean");
	}

	@Override
	public byte getByte(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getByte");
	}

	@Override
	public byte getByte(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getByte");
	}

	@Override
	public byte @Nullable [] getBytes(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getBytes");
	}

	@Override
	public byte @Nullable [] getBytes(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getBytes");
	}

	@Override
	public @Nullable Reader getCharacterStream(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getCharacterStream");
	}

	@Override
	public @Nullable Reader getCharacterStream(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getCharacterStream");
	}

	@Override
	public Clob getClob(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getClob");
	}

	@Override
	public Clob getClob(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getClob");
	}

	@Override
	public int getConcurrency() throws SQLException {
		throw Failures.unsupported("ResultSet.getConcurrency");
	}

	@Override
	public String getCursorName() throws SQLException {
		throw Failures.unsupported("ResultSet.getCursorName");
	}

	@Override
	public @Nullable Date getDate(String a, Calendar b) throws SQLException {
		throw Failures.unsupported("ResultSet.getDate");
	}

	@Override
	public @Nullable Date getDate(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getDate");
	}

	@Override
	public @Nullable Date getDate(int a, Calendar b) throws SQLException {
		throw Failures.unsupported("ResultSet.getDate");
	}

	@Override
	public @Nullable Date getDate(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getDate");
	}

	@Override
	public double getDouble(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getDouble");
	}

	@Override
	public double getDouble(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getDouble");
	}

	@Override
	public int getFetchDirection() throws SQLException {
		throw Failures.unsupported("ResultSet.getFetchDirection");
	}

	@Override
	public int getFetchSize() throws SQLException {
		throw Failures.unsupported("ResultSet.getFetchSize");
	}

	@Override
	public float getFloat(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getFloat");
	}

	@Override
	public float getFloat(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getFloat");
	}

	@Override
	public int getHoldability() throws SQLException {
		throw Failures.unsupported("ResultSet.getHoldability");
	}

	@Override
	public int getInt(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getInt");
	}

	@Override
	public int getInt(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getInt");
	}

	@Override
	public long getLong(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getLong");
	}

	@Override
	public long getLong(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getLong");
	}

	@Override
	public ResultSetMetaData getMetaData() throws SQLException {
		throw Failures.unsupported("ResultSet.getMetaData");
	}

	@Override
	public @Nullable Reader getNCharacterStream(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getNCharacterStream");
	}

	@Override
	public @Nullable Reader getNCharacterStream(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getNCharacterStream");
	}

	@Override
	public NClob getNClob(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getNClob");
	}

	@Override
	public NClob getNClob(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getNClob");
	}

	@Override
	public @Nullable String getNString(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getNString");
	}

	@Override
	public @Nullable String getNString(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getNString");
	}

	@Override
	public <T> @Nullable T getObject(String a, Class<T> b) throws SQLException {
		throw Failures.unsupported("ResultSet.getObject");
	}

	@Override
	public @Nullable Object getObject(String a, Map<String, Class<?>> b) throws SQLException {
		throw Failures.unsupported("ResultSet.getObject");
	}

	@Override
	public @Nullable Object getObject(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getObject");
	}

	@Override
	public <T> @Nullable T getObject(int a, Class<T> b) throws SQLException {
		throw Failures.unsupported("ResultSet.getObject");
	}

	@Override
	public @Nullable Object getObject(int a, Map<String, Class<?>> b) throws SQLException {
		throw Failures.unsupported("ResultSet.getObject");
	}

	@Override
	public @Nullable Object getObject(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getObject");
	}

	@Override
	public Ref getRef(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getRef");
	}

	@Override
	public Ref getRef(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getRef");
	}

	@Override
	public int getRow() throws SQLException {
		throw Failures.unsupported("ResultSet.getRow");
	}

	@Override
	public RowId getRowId(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getRowId");
	}

	@Override
	public RowId getRowId(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getRowId");
	}

	@Override
	public SQLXML getSQLXML(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getSQLXML");
	}

	@Override
	public SQLXML getSQLXML(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getSQLXML");
	}

	@Override
	public short getShort(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getShort");
	}

	@Override
	public short getShort(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getShort");
	}

	@Override
	public @Nullable Statement getStatement() throws SQLException {
		throw Failures.unsupported("ResultSet.getStatement");
	}

	@Override
	public @Nullable String getString(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getString");
	}

	@Override
	public @Nullable String getString(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getString");
	}

	@Override
	public @Nullable Time getTime(String a, Calendar b) throws SQLException {
		throw Failures.unsupported("ResultSet.getTime");
	}

	@Override
	public @Nullable Time getTime(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getTime");
	}

	@Override
	public @Nullable Time getTime(int a, Calendar b) throws SQLException {
		throw Failures.unsupported("ResultSet.getTime");
	}

	@Override
	public @Nullable Time getTime(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getTime");
	}

	@Override
	public @Nullable Timestamp getTimestamp(String a, Calendar b) throws SQLException {
		throw Failures.unsupported("ResultSet.getTimestamp");
	}

	@Override
	public @Nullable Timestamp getTimestamp(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getTimestamp");
	}

	@Override
	public @Nullable Timestamp getTimestamp(int a, Calendar b) throws SQLException {
		throw Failures.unsupported("ResultSet.getTimestamp");
	}

	@Override
	public @Nullable Timestamp getTimestamp(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getTimestamp");
	}

	@Override
	public int getType() throws SQLException {
		throw Failures.unsupported("ResultSet.getType");
	}

	@Override
	public URL getURL(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getURL");
	}

	@Override
	public URL getURL(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getURL");
	}

	@Override
	public InputStream getUnicodeStream(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.getUnicodeStream");
	}

	@Override
	public InputStream getUnicodeStream(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.getUnicodeStream");
	}

	@Override
	public @Nullable SQLWarning getWarnings() throws SQLException {
		throw Failures.unsupported("ResultSet.getWarnings");
	}

	@Override
	public void insertRow() throws SQLException {
		throw Failures.unsupported("ResultSet.insertRow");
	}

	@Override
	public boolean isAfterLast() throws SQLException {
		throw Failures.unsupported("ResultSet.isAfterLast");
	}

	@Override
	public boolean isBeforeFirst() throws SQLException {
		throw Failures.unsupported("ResultSet.isBeforeFirst");
	}

	@Override
	public boolean isClosed() throws SQLException {
		throw Failures.unsupported("ResultSet.isClosed");
	}

	@Override
	public boolean isFirst() throws SQLException {
		throw Failures.unsupported("ResultSet.isFirst");
	}

	@Override
	public boolean isLast() throws SQLException {
		throw Failures.unsupported("ResultSet.isLast");
	}

	@Override
	public boolean isWrapperFor(Class<?> a) throws SQLException {
		return a.isInstance(this);
	}

	@Override
	public boolean last() throws SQLException {
		throw Failures.unsupported("ResultSet.last");
	}

	@Override
	public void moveToCurrentRow() throws SQLException {
		throw Failures.unsupported("ResultSet.moveToCurrentRow");
	}

	@Override
	public void moveToInsertRow() throws SQLException {
		throw Failures.unsupported("ResultSet.moveToInsertRow");
	}

	@Override
	public boolean next() throws SQLException {
		throw Failures.unsupported("ResultSet.next");
	}

	@Override
	public boolean previous() throws SQLException {
		throw Failures.unsupported("ResultSet.previous");
	}

	@Override
	public void refreshRow() throws SQLException {
		throw Failures.unsupported("ResultSet.refreshRow");
	}

	@Override
	public boolean relative(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.relative");
	}

	@Override
	public boolean rowDeleted() throws SQLException {
		throw Failures.unsupported("ResultSet.rowDeleted");
	}

	@Override
	public boolean rowInserted() throws SQLException {
		throw Failures.unsupported("ResultSet.rowInserted");
	}

	@Override
	public boolean rowUpdated() throws SQLException {
		throw Failures.unsupported("ResultSet.rowUpdated");
	}

	@Override
	public void setFetchDirection(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.setFetchDirection");
	}

	@Override
	public void setFetchSize(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.setFetchSize");
	}

	@Override
	public <T> T unwrap(Class<T> a) throws SQLException {
		if (a.isInstance(this)) {
			return a.cast(this);
		}
		throw Failures.unsupported("An unwrap to " + a.getName());
	}

	@Override
	public void updateArray(String a, Array b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateArray");
	}

	@Override
	public void updateArray(int a, Array b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateArray");
	}

	@Override
	public void updateAsciiStream(String a, InputStream b, int c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateAsciiStream");
	}

	@Override
	public void updateAsciiStream(String a, InputStream b, long c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateAsciiStream");
	}

	@Override
	public void updateAsciiStream(String a, InputStream b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateAsciiStream");
	}

	@Override
	public void updateAsciiStream(int a, InputStream b, int c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateAsciiStream");
	}

	@Override
	public void updateAsciiStream(int a, InputStream b, long c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateAsciiStream");
	}

	@Override
	public void updateAsciiStream(int a, InputStream b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateAsciiStream");
	}

	@Override
	public void updateBigDecimal(String a, BigDecimal b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBigDecimal");
	}

	@Override
	public void updateBigDecimal(int a, BigDecimal b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBigDecimal");
	}

	@Override
	public void updateBinaryStream(String a, InputStream b, int c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBinaryStream");
	}

	@Override
	public void updateBinaryStream(String a, InputStream b, long c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBinaryStream");
	}

	@Override
	public void updateBinaryStream(String a, InputStream b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBinaryStream");
	}

	@Override
	public void updateBinaryStream(int a, InputStream b, int c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBinaryStream");
	}

	@Override
	public void updateBinaryStream(int a, InputStream b, long c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBinaryStream");
	}

	@Override
	public void updateBinaryStream(int a, InputStream b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBinaryStream");
	}

	@Override
	public void updateBlob(String a, InputStream b, long c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBlob");
	}

	@Override
	public void updateBlob(String a, InputStream b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBlob");
	}

	@Override
	public void updateBlob(String a, Blob b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBlob");
	}

	@Override
	public void updateBlob(int a, InputStream b, long c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBlob");
	}

	@Override
	public void updateBlob(int a, InputStream b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBlob");
	}

	@Override
	public void updateBlob(int a, Blob b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBlob");
	}

	@Override
	public void updateBoolean(String a, boolean b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBoolean");
	}

	@Override
	public void updateBoolean(int a, boolean b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBoolean");
	}

	@Override
	public void updateByte(String a, byte b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateByte");
	}

	@Override
	public void updateByte(int a, byte b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateByte");
	}

	@Override
	public void updateBytes(String a, byte[] b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBytes");
	}

	@Override
	public void updateBytes(int a, byte[] b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateBytes");
	}

	@Override
	public void updateCharacterStream(String a, Reader b, int c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateCharacterStream");
	}

	@Override
	public void updateCharacterStream(String a, Reader b, long c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateCharacterStream");
	}

	@Override
	public void updateCharacterStream(String a, Reader b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateCharacterStream");
	}

	@Override
	public void updateCharacterStream(int a, Reader b, int c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateCharacterStream");
	}

	@Override
	public void updateCharacterStream(int a, Reader b, long c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateCharacterStream");
	}

	@Override
	public void updateCharacterStream(int a, Reader b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateCharacterStream");
	}

	@Override
	public void updateClob(String a, Reader b, long c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateClob");
	}

	@Override
	public void updateClob(String a, Reader b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateClob");
	}

	@Override
	public void updateClob(String a, Clob b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateClob");
	}

	@Override
	public void updateClob(int a, Reader b, long c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateClob");
	}

	@Override
	public void updateClob(int a, Reader b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateClob");
	}

	@Override
	public void updateClob(int a, Clob b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateClob");
	}

	@Override
	public void updateDate(String a, Date b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateDate");
	}

	@Override
	public void updateDate(int a, Date b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateDate");
	}

	@Override
	public void updateDouble(String a, double b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateDouble");
	}

	@Override
	public void updateDouble(int a, double b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateDouble");
	}

	@Override
	public void updateFloat(String a, float b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateFloat");
	}

	@Override
	public void updateFloat(int a, float b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateFloat");
	}

	@Override
	public void updateInt(String a, int b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateInt");
	}

	@Override
	public void updateInt(int a, int b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateInt");
	}

	@Override
	public void updateLong(String a, long b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateLong");
	}

	@Override
	public void updateLong(int a, long b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateLong");
	}

	@Override
	public void updateNCharacterStream(String a, Reader b, long c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateNCharacterStream");
	}

	@Override
	public void updateNCharacterStream(String a, Reader b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateNCharacterStream");
	}

	@Override
	public void updateNCharacterStream(int a, Reader b, long c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateNCharacterStream");
	}

	@Override
	public void updateNCharacterStream(int a, Reader b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateNCharacterStream");
	}

	@Override
	public void updateNClob(String a, Reader b, long c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateNClob");
	}

	@Override
	public void updateNClob(String a, Reader b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateNClob");
	}

	@Override
	public void updateNClob(String a, NClob b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateNClob");
	}

	@Override
	public void updateNClob(int a, Reader b, long c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateNClob");
	}

	@Override
	public void updateNClob(int a, Reader b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateNClob");
	}

	@Override
	public void updateNClob(int a, NClob b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateNClob");
	}

	@Override
	public void updateNString(String a, String b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateNString");
	}

	@Override
	public void updateNString(int a, String b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateNString");
	}

	@Override
	public void updateNull(String a) throws SQLException {
		throw Failures.unsupported("ResultSet.updateNull");
	}

	@Override
	public void updateNull(int a) throws SQLException {
		throw Failures.unsupported("ResultSet.updateNull");
	}

	@Override
	public void updateObject(String a, Object b, int c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateObject");
	}

	@Override
	public void updateObject(String a, Object b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateObject");
	}

	@Override
	public void updateObject(int a, Object b, int c) throws SQLException {
		throw Failures.unsupported("ResultSet.updateObject");
	}

	@Override
	public void updateObject(int a, Object b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateObject");
	}

	@Override
	public void updateRef(String a, Ref b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateRef");
	}

	@Override
	public void updateRef(int a, Ref b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateRef");
	}

	@Override
	public void updateRow() throws SQLException {
		throw Failures.unsupported("ResultSet.updateRow");
	}

	@Override
	public void updateRowId(String a, RowId b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateRowId");
	}

	@Override
	public void updateRowId(int a, RowId b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateRowId");
	}

	@Override
	public void updateSQLXML(String a, SQLXML b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateSQLXML");
	}

	@Override
	public void updateSQLXML(int a, SQLXML b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateSQLXML");
	}

	@Override
	public void updateShort(String a, short b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateShort");
	}

	@Override
	public void updateShort(int a, short b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateShort");
	}

	@Override
	public void updateString(String a, String b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateString");
	}

	@Override
	public void updateString(int a, String b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateString");
	}

	@Override
	public void updateTime(String a, Time b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateTime");
	}

	@Override
	public void updateTime(int a, Time b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateTime");
	}

	@Override
	public void updateTimestamp(String a, Timestamp b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateTimestamp");
	}

	@Override
	public void updateTimestamp(int a, Timestamp b) throws SQLException {
		throw Failures.unsupported("ResultSet.updateTimestamp");
	}

	@Override
	public boolean wasNull() throws SQLException {
		throw Failures.unsupported("ResultSet.wasNull");
	}

}
