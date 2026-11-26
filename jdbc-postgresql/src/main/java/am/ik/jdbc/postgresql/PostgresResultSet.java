package am.ik.jdbc.postgresql;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * The rows a statement answered, all of them read before the first is asked for: a result
 * set that can be walked in both directions and changes nothing.
 */
final class PostgresResultSet extends UnsupportedResultSet {

	private final @Nullable PostgresStatement statement;

	private final PostgresConnection connection;

	private final Column[] columns;

	private final List<byte[]> rows;

	private final int type;

	// The row under the cursor, counted from 0: -1 before the first, the count after the
	// last.
	private int at = -1;

	// Where each value of the row under the cursor starts and how long it is, -1 for
	// NULL.
	private final int[] starts;

	private final int[] lengths;

	private boolean indexed;

	private boolean wasNull;

	private boolean closed;

	private @Nullable Map<String, Integer> labels;

	PostgresResultSet(@Nullable PostgresStatement statement, PostgresConnection connection, Result result, int type) {
		this.statement = statement;
		this.connection = connection;
		// Made of a result with columns alone.
		this.columns = Objects.requireNonNull(result.columns);
		this.rows = result.rows;
		this.type = type;
		this.starts = new int[this.columns.length];
		this.lengths = new int[this.columns.length];
	}

	// The cursor.

	@Override
	public boolean next() throws SQLException {
		open();
		return move(Math.min(this.at + 1, this.rows.size()));
	}

	@Override
	public boolean previous() throws SQLException {
		scrollable();
		return move(Math.max(this.at - 1, -1));
	}

	@Override
	public boolean first() throws SQLException {
		scrollable();
		return move(this.rows.isEmpty() ? -1 : 0);
	}

	@Override
	public boolean last() throws SQLException {
		scrollable();
		return move(this.rows.size() - 1);
	}

	@Override
	public void beforeFirst() throws SQLException {
		scrollable();
		move(-1);
	}

	@Override
	public void afterLast() throws SQLException {
		scrollable();
		move(this.rows.size());
	}

	@Override
	public boolean absolute(int row) throws SQLException {
		scrollable();
		int target = row > 0 ? row - 1 : row < 0 ? this.rows.size() + row : -1;
		return move(Math.max(-1, Math.min(target, this.rows.size())));
	}

	@Override
	public boolean relative(int count) throws SQLException {
		scrollable();
		return move((int) Math.max(-1L, Math.min((long) this.at + count, this.rows.size())));
	}

	private boolean move(int to) {
		this.at = to;
		this.indexed = false;
		return to >= 0 && to < this.rows.size();
	}

	@Override
	public boolean isBeforeFirst() throws SQLException {
		open();
		return this.at < 0 && !this.rows.isEmpty();
	}

	@Override
	public boolean isAfterLast() throws SQLException {
		open();
		return this.at >= this.rows.size() && !this.rows.isEmpty();
	}

	@Override
	public boolean isFirst() throws SQLException {
		open();
		return this.at == 0 && !this.rows.isEmpty();
	}

	@Override
	public boolean isLast() throws SQLException {
		open();
		return this.at == this.rows.size() - 1 && !this.rows.isEmpty();
	}

	@Override
	public int getRow() throws SQLException {
		open();
		return this.at >= 0 && this.at < this.rows.size() ? this.at + 1 : 0;
	}

	@Override
	public void close() throws SQLException {
		if (!this.closed) {
			this.closed = true;
			if (this.statement != null) {
				this.statement.closed(this);
			}
		}
	}

	@Override
	public boolean isClosed() throws SQLException {
		return this.closed;
	}

	private void open() throws SQLException {
		if (this.closed) {
			throw Failures.closed("result set");
		}
	}

	private void scrollable() throws SQLException {
		open();
		if (this.type == ResultSet.TYPE_FORWARD_ONLY) {
			throw new SQLException("This result set moves forward only", "24000");
		}
	}

	// What the result set is.

	@Override
	public ResultSetMetaData getMetaData() throws SQLException {
		open();
		return new PostgresResultSetMetaData(this.connection, this.columns);
	}

	@Override
	public @Nullable Statement getStatement() throws SQLException {
		open();
		return this.statement;
	}

	@Override
	public int getType() throws SQLException {
		open();
		return this.type;
	}

	@Override
	public int getConcurrency() throws SQLException {
		open();
		return ResultSet.CONCUR_READ_ONLY;
	}

	@Override
	public int getHoldability() throws SQLException {
		open();
		return ResultSet.HOLD_CURSORS_OVER_COMMIT;
	}

	@Override
	public int getFetchDirection() throws SQLException {
		open();
		return ResultSet.FETCH_FORWARD;
	}

	@Override
	public void setFetchDirection(int direction) throws SQLException {
		open();
	}

	@Override
	public int getFetchSize() throws SQLException {
		open();
		return 0;
	}

	@Override
	public void setFetchSize(int rows) throws SQLException {
		open();
	}

	@Override
	public @Nullable SQLWarning getWarnings() throws SQLException {
		open();
		return null;
	}

	@Override
	public void clearWarnings() throws SQLException {
		open();
	}

	@Override
	public boolean rowUpdated() throws SQLException {
		open();
		return false;
	}

	@Override
	public boolean rowInserted() throws SQLException {
		open();
		return false;
	}

	@Override
	public boolean rowDeleted() throws SQLException {
		open();
		return false;
	}

	@Override
	public int findColumn(String label) throws SQLException {
		open();
		if (this.labels == null) {
			this.labels = new HashMap<>();
			for (int i = this.columns.length - 1; i >= 0; i--) {
				this.labels.put(this.columns[i].name.toLowerCase(Locale.ROOT), i + 1);
			}
		}
		Integer column = label != null ? this.labels.get(label.toLowerCase(Locale.ROOT)) : null;
		if (column == null) {
			throw new SQLException("The result set has no column named " + label, "42703");
		}
		return column;
	}

	@Override
	public boolean wasNull() throws SQLException {
		open();
		return this.wasNull;
	}

	// A value of the row under the cursor.

	// Finds the column's value: its index from 0, or -1 for NULL.
	private int value(int column) throws SQLException {
		open();
		if (this.at < 0 || this.at >= this.rows.size()) {
			throw new SQLException("The cursor is on no row", "24000");
		}
		if (column < 1 || column > this.columns.length) {
			throw new SQLException("The result set has no column " + column, "42703");
		}
		if (!this.indexed) {
			byte[] row = this.rows.get(this.at);
			int position = 2;
			for (int i = 0; i < this.columns.length; i++) {
				int length = ((row[position] & 0xff) << 24) | ((row[position + 1] & 0xff) << 16)
						| ((row[position + 2] & 0xff) << 8) | (row[position + 3] & 0xff);
				position += 4;
				this.starts[i] = position;
				this.lengths[i] = length;
				position += Math.max(length, 0);
			}
			this.indexed = true;
		}
		this.wasNull = this.lengths[column - 1] < 0;
		return this.wasNull ? -1 : column - 1;
	}

	@Override
	public @Nullable String getString(int column) throws SQLException {
		int index = value(column);
		return index < 0 ? null : text(index);
	}

	// The text of a value that is not NULL.
	private String text(int index) {
		return new String(this.rows.get(this.at), this.starts[index], this.lengths[index], StandardCharsets.UTF_8);
	}

	@Override
	public boolean getBoolean(int column) throws SQLException {
		String text = getString(column);
		return text != null && Values.bool(text);
	}

	@Override
	public byte getByte(int column) throws SQLException {
		String text = getString(column);
		return text == null ? 0 : (byte) Values.integer(text, Byte.MIN_VALUE, Byte.MAX_VALUE);
	}

	@Override
	public short getShort(int column) throws SQLException {
		String text = getString(column);
		return text == null ? 0 : (short) Values.integer(text, Short.MIN_VALUE, Short.MAX_VALUE);
	}

	@Override
	public int getInt(int column) throws SQLException {
		String text = getString(column);
		return text == null ? 0 : (int) Values.integer(text, Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

	@Override
	public long getLong(int column) throws SQLException {
		String text = getString(column);
		return text == null ? 0 : Values.integer(text, Long.MIN_VALUE, Long.MAX_VALUE);
	}

	@Override
	public float getFloat(int column) throws SQLException {
		String text = getString(column);
		return text == null ? 0 : (float) Values.real(text);
	}

	@Override
	public double getDouble(int column) throws SQLException {
		String text = getString(column);
		return text == null ? 0 : Values.real(text);
	}

	@Override
	public @Nullable BigDecimal getBigDecimal(int column) throws SQLException {
		String text = getString(column);
		return text == null ? null : Values.decimal(text);
	}

	@Override
	@Deprecated
	public @Nullable BigDecimal getBigDecimal(int column, int scale) throws SQLException {
		BigDecimal value = getBigDecimal(column);
		return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
	}

	@Override
	public byte @Nullable [] getBytes(int column) throws SQLException {
		int index = value(column);
		if (index < 0) {
			return null;
		}
		byte[] row = this.rows.get(this.at);
		if (this.columns[index].type == TypeId.BYTEA) {
			return Values.octets(row, this.starts[index], this.lengths[index]);
		}
		byte[] octets = new byte[this.lengths[index]];
		System.arraycopy(row, this.starts[index], octets, 0, octets.length);
		return octets;
	}

	@Override
	public @Nullable Date getDate(int column) throws SQLException {
		String text = getString(column);
		return text == null ? null : Values.date(text);
	}

	@Override
	public @Nullable Time getTime(int column) throws SQLException {
		String text = getString(column);
		return text == null ? null : Values.time(text);
	}

	@Override
	public @Nullable Timestamp getTimestamp(int column) throws SQLException {
		String text = getString(column);
		return text == null ? null : Values.timestamp(text);
	}

	// With a calendar: its time zone is the wall a value with no offset is read on.

	@Override
	public @Nullable Date getDate(int column, Calendar calendar) throws SQLException {
		String text = getString(column);
		return text == null ? null
				: calendar == null ? Values.date(text) : Values.date(text, calendar.getTimeZone().toZoneId());
	}

	@Override
	public @Nullable Time getTime(int column, Calendar calendar) throws SQLException {
		String text = getString(column);
		return text == null ? null
				: calendar == null ? Values.time(text) : Values.time(text, calendar.getTimeZone().toZoneId());
	}

	@Override
	public @Nullable Timestamp getTimestamp(int column, Calendar calendar) throws SQLException {
		String text = getString(column);
		return text == null ? null
				: calendar == null ? Values.timestamp(text) : Values.timestamp(text, calendar.getTimeZone().toZoneId());
	}

	@Override
	public @Nullable Object getObject(int column) throws SQLException {
		int index = value(column);
		if (index < 0) {
			return null;
		}
		int type = this.columns[index].type;
		return type == TypeId.BYTEA ? getBytes(column) : Values.as(Values.natural(type), text(index), type);
	}

	@Override
	public @Nullable Object getObject(int column, Map<String, Class<?>> map) throws SQLException {
		if (map != null && !map.isEmpty()) {
			throw Failures.unsupported("A value read through a type map");
		}
		return getObject(column);
	}

	@Override
	public <T> @Nullable T getObject(int column, Class<T> asked) throws SQLException {
		if (asked == null) {
			throw new SQLException("The class asked for is null", "HY004");
		}
		int index = value(column);
		if (index < 0) {
			return null;
		}
		if (asked == Object.class) {
			return asked.cast(getObject(column));
		}
		if (asked == byte[].class) {
			return asked.cast(getBytes(column));
		}
		return asked.cast(Values.as(asked, text(index), this.columns[index].type));
	}

	@Override
	public @Nullable String getNString(int column) throws SQLException {
		return getString(column);
	}

	@Override
	public @Nullable Reader getCharacterStream(int column) throws SQLException {
		String text = getString(column);
		return text == null ? null : new StringReader(text);
	}

	@Override
	public @Nullable Reader getNCharacterStream(int column) throws SQLException {
		return getCharacterStream(column);
	}

	@Override
	public @Nullable InputStream getBinaryStream(int column) throws SQLException {
		byte[] octets = getBytes(column);
		return octets == null ? null : new ByteArrayInputStream(octets);
	}

	@Override
	public @Nullable InputStream getAsciiStream(int column) throws SQLException {
		String text = getString(column);
		return text == null ? null : new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));
	}

	// The same by a column's label.

	@Override
	public @Nullable String getString(String label) throws SQLException {
		return getString(findColumn(label));
	}

	@Override
	public boolean getBoolean(String label) throws SQLException {
		return getBoolean(findColumn(label));
	}

	@Override
	public byte getByte(String label) throws SQLException {
		return getByte(findColumn(label));
	}

	@Override
	public short getShort(String label) throws SQLException {
		return getShort(findColumn(label));
	}

	@Override
	public int getInt(String label) throws SQLException {
		return getInt(findColumn(label));
	}

	@Override
	public long getLong(String label) throws SQLException {
		return getLong(findColumn(label));
	}

	@Override
	public float getFloat(String label) throws SQLException {
		return getFloat(findColumn(label));
	}

	@Override
	public double getDouble(String label) throws SQLException {
		return getDouble(findColumn(label));
	}

	@Override
	public @Nullable BigDecimal getBigDecimal(String label) throws SQLException {
		return getBigDecimal(findColumn(label));
	}

	@Override
	@Deprecated
	public @Nullable BigDecimal getBigDecimal(String label, int scale) throws SQLException {
		return getBigDecimal(findColumn(label), scale);
	}

	@Override
	public byte @Nullable [] getBytes(String label) throws SQLException {
		return getBytes(findColumn(label));
	}

	@Override
	public @Nullable Date getDate(String label) throws SQLException {
		return getDate(findColumn(label));
	}

	@Override
	public @Nullable Time getTime(String label) throws SQLException {
		return getTime(findColumn(label));
	}

	@Override
	public @Nullable Timestamp getTimestamp(String label) throws SQLException {
		return getTimestamp(findColumn(label));
	}

	@Override
	public @Nullable Date getDate(String label, Calendar calendar) throws SQLException {
		return getDate(findColumn(label), calendar);
	}

	@Override
	public @Nullable Time getTime(String label, Calendar calendar) throws SQLException {
		return getTime(findColumn(label), calendar);
	}

	@Override
	public @Nullable Timestamp getTimestamp(String label, Calendar calendar) throws SQLException {
		return getTimestamp(findColumn(label), calendar);
	}

	@Override
	public @Nullable Object getObject(String label) throws SQLException {
		return getObject(findColumn(label));
	}

	@Override
	public @Nullable Object getObject(String label, Map<String, Class<?>> map) throws SQLException {
		return getObject(findColumn(label), map);
	}

	@Override
	public <T> @Nullable T getObject(String label, Class<T> asked) throws SQLException {
		return getObject(findColumn(label), asked);
	}

	@Override
	public @Nullable String getNString(String label) throws SQLException {
		return getString(findColumn(label));
	}

	@Override
	public @Nullable Reader getCharacterStream(String label) throws SQLException {
		return getCharacterStream(findColumn(label));
	}

	@Override
	public @Nullable Reader getNCharacterStream(String label) throws SQLException {
		return getCharacterStream(findColumn(label));
	}

	@Override
	public @Nullable InputStream getBinaryStream(String label) throws SQLException {
		return getBinaryStream(findColumn(label));
	}

	@Override
	public @Nullable InputStream getAsciiStream(String label) throws SQLException {
		return getAsciiStream(findColumn(label));
	}

}
