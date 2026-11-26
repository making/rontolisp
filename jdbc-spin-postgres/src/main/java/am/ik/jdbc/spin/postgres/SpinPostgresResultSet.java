package am.ik.jdbc.spin.postgres;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
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

import am.ik.rontolisp.wit.WitException;
import org.jspecify.annotations.Nullable;

/**
 * The rows a statement answered: all of them handed over by the host before the first is
 * asked for -- a result set that can be walked in both directions -- or, under a fetch
 * size, read from the host's stream so many at a time as the cursor moves on, forward
 * only, the statement's refusal thrown where the rows run out. It changes nothing.
 */
final class SpinPostgresResultSet extends UnsupportedResultSet {

	private final @Nullable SpinPostgresStatement statement;

	private final List<Postgres.Column> columns;

	// The rows; of a result read in pieces, the piece the cursor is in.
	private List<List<Postgres.DbValue>> rows;

	private final int type;

	// The row under the cursor, counted from 0: -1 before the first, the count after the
	// last.
	private int at = -1;

	private boolean wasNull;

	private boolean closed;

	private @Nullable Map<String, Integer> labels;

	// A result read in pieces: the host's stream and its end, null for a whole one.
	private final Postgres.@Nullable QueryAsync source;

	private int fetchSize;

	// The most rows read, 0 for no limit.
	private final int limit;

	private final boolean timed;

	// The rows read before the first of this.rows.
	private long before;

	// The piece after this.rows, read to tell whether a row is the last.
	private @Nullable List<List<Postgres.DbValue>> ahead;

	// Whether the stream and its end were given back.
	private boolean ended;

	SpinPostgresResultSet(@Nullable SpinPostgresStatement statement, Postgres.RowSet rows, int type) {
		this.statement = statement;
		this.columns = rows.columns();
		this.rows = rows.rows();
		this.type = type;
		this.source = null;
		this.limit = 0;
		this.timed = false;
	}

	/**
	 * Rows read in pieces.
	 * @param fetchSize the most rows a read asks the host for
	 * @param limit the most rows read, 0 for no limit
	 * @param timed whether the statement ran under a limit on its time
	 */
	SpinPostgresResultSet(SpinPostgresStatement statement, Postgres.QueryAsync source, int fetchSize, int limit,
			boolean timed) {
		this.statement = statement;
		this.columns = source.columns();
		this.rows = List.of();
		this.type = ResultSet.TYPE_FORWARD_ONLY;
		this.source = source;
		this.fetchSize = fetchSize;
		this.limit = limit;
		this.timed = timed;
	}

	// The cursor.

	@Override
	public boolean next() throws SQLException {
		open();
		if (this.source == null || this.at + 1 < this.rows.size() || this.at >= this.rows.size()) {
			return move(Math.min(this.at + 1, this.rows.size()));
		}
		List<List<Postgres.DbValue>> next = more();
		if (next.isEmpty()) {
			return move(this.rows.size());
		}
		this.before += this.rows.size();
		this.rows = next;
		return move(0);
	}

	// The piece after this.rows: the one read ahead, or the host's next, none at the end
	// -- where the statement's refusal is thrown -- or at the most rows.
	private List<List<Postgres.DbValue>> more() throws SQLException {
		List<List<Postgres.DbValue>> next = this.ahead;
		if (next != null) {
			this.ahead = null;
			return next;
		}
		if (this.ended) {
			return List.of();
		}
		Postgres.QueryAsync read = Objects.requireNonNull(this.source);
		long most = this.fetchSize;
		if (this.limit > 0) {
			most = Math.min(most, this.limit - this.before - this.rows.size());
			if (most <= 0) {
				// the rest is the host's to drop: its end is not read
				end(false);
				return List.of();
			}
		}
		next = read.rows().read((int) most);
		if (next.isEmpty()) {
			end(true);
		}
		return next;
	}

	// Gives the stream and its end back, the end read first when the rows ran out: its
	// error arm is the statement's refusal. An end not read is the host's to drop.
	private void end(boolean read) throws SQLException {
		this.ended = true;
		Postgres.QueryAsync streamed = Objects.requireNonNull(this.source);
		try {
			if (read) {
				streamed.done().get();
			}
		}
		catch (WitException ex) {
			throw Objects.requireNonNull(this.statement).connection.refused(ex, this.timed);
		}
		finally {
			streamed.rows().close();
			streamed.done().close();
		}
	}

	// Whether no row follows this.rows, a piece read ahead to tell.
	private boolean noMore() throws SQLException {
		if (this.source == null) {
			return true;
		}
		if (this.ahead == null && !this.ended) {
			List<List<Postgres.DbValue>> next = more();
			this.ahead = next.isEmpty() ? null : next;
		}
		return this.ahead == null;
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
		return to >= 0 && to < this.rows.size();
	}

	@Override
	public boolean isBeforeFirst() throws SQLException {
		open();
		return this.at < 0 && (!this.rows.isEmpty() || !noMore());
	}

	@Override
	public boolean isAfterLast() throws SQLException {
		open();
		return this.at >= this.rows.size() && !this.rows.isEmpty();
	}

	@Override
	public boolean isFirst() throws SQLException {
		open();
		return this.before == 0 && this.at == 0 && !this.rows.isEmpty();
	}

	@Override
	public boolean isLast() throws SQLException {
		open();
		return this.at == this.rows.size() - 1 && !this.rows.isEmpty() && noMore();
	}

	@Override
	public int getRow() throws SQLException {
		open();
		return this.at >= 0 && this.at < this.rows.size() ? (int) (this.before + this.at + 1) : 0;
	}

	@Override
	public void close() throws SQLException {
		if (!this.closed) {
			this.closed = true;
			if (this.source != null && !this.ended) {
				// the rest is the host's to drop
				end(false);
			}
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
		return new SpinPostgresResultSetMetaData(this.columns);
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
		return this.fetchSize;
	}

	/** Of a result read in pieces, the most rows each read from now on asks for. */
	@Override
	public void setFetchSize(int rows) throws SQLException {
		open();
		if (rows < 0) {
			throw new SQLException("The fetch size cannot be negative: " + rows, "22023");
		}
		if (rows > 0 || this.source == null) {
			this.fetchSize = rows;
		}
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
			for (int i = this.columns.size() - 1; i >= 0; i--) {
				this.labels.put(this.columns.get(i).name().toLowerCase(Locale.ROOT), i + 1);
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

	// The column's value, or null for NULL.
	private Postgres.@Nullable DbValue value(int column) throws SQLException {
		open();
		if (this.at < 0 || this.at >= this.rows.size()) {
			throw new SQLException("The cursor is on no row", "24000");
		}
		if (column < 1 || column > this.columns.size()) {
			throw new SQLException("The result set has no column " + column, "42703");
		}
		Postgres.DbValue value = this.rows.get(this.at).get(column - 1);
		this.wasNull = value instanceof Postgres.DbValue.DbNull;
		return this.wasNull ? null : value;
	}

	// The name of the column's type, for a refusal to name.
	private String type(int column) {
		return Values.name(this.columns.get(column - 1).dataType());
	}

	@Override
	public @Nullable String getString(int column) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? null : Values.text(value, type(column));
	}

	@Override
	public boolean getBoolean(int column) throws SQLException {
		Postgres.DbValue value = value(column);
		return value != null && Values.bool(value, type(column));
	}

	@Override
	public byte getByte(int column) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? 0 : (byte) Values.integer(value, type(column), Byte.MIN_VALUE, Byte.MAX_VALUE);
	}

	@Override
	public short getShort(int column) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? 0 : (short) Values.integer(value, type(column), Short.MIN_VALUE, Short.MAX_VALUE);
	}

	@Override
	public int getInt(int column) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? 0 : (int) Values.integer(value, type(column), Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

	@Override
	public long getLong(int column) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? 0 : Values.integer(value, type(column), Long.MIN_VALUE, Long.MAX_VALUE);
	}

	@Override
	public float getFloat(int column) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? 0 : Values.single(value, type(column));
	}

	@Override
	public double getDouble(int column) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? 0 : Values.real(value, type(column));
	}

	@Override
	public @Nullable BigDecimal getBigDecimal(int column) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? null : Values.decimal(value, type(column));
	}

	@Override
	@Deprecated
	public @Nullable BigDecimal getBigDecimal(int column, int scale) throws SQLException {
		BigDecimal value = getBigDecimal(column);
		return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
	}

	@Override
	public byte @Nullable [] getBytes(int column) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? null : Values.octets(value, type(column));
	}

	@Override
	public @Nullable Date getDate(int column) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? null : Values.date(value, type(column));
	}

	@Override
	public @Nullable Time getTime(int column) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? null : Values.time(value, type(column));
	}

	@Override
	public @Nullable Timestamp getTimestamp(int column) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? null : Values.timestamp(value, type(column));
	}

	// With a calendar: its time zone is the wall a value with no offset is read on.

	@Override
	public @Nullable Date getDate(int column, Calendar calendar) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? null : calendar == null ? Values.date(value, type(column))
				: Values.date(value, type(column), calendar.getTimeZone().toZoneId());
	}

	@Override
	public @Nullable Time getTime(int column, Calendar calendar) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? null : calendar == null ? Values.time(value, type(column))
				: Values.time(value, type(column), calendar.getTimeZone().toZoneId());
	}

	@Override
	public @Nullable Timestamp getTimestamp(int column, Calendar calendar) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? null : calendar == null ? Values.timestamp(value, type(column))
				: Values.timestamp(value, type(column), calendar.getTimeZone().toZoneId());
	}

	@Override
	public @Nullable Object getObject(int column) throws SQLException {
		Postgres.DbValue value = value(column);
		return value == null ? null : Values.natural(value, type(column));
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
		Postgres.DbValue value = value(column);
		if (value == null) {
			return null;
		}
		if (asked == Object.class) {
			return asked.cast(Values.natural(value, type(column)));
		}
		if (asked == byte[].class) {
			return asked.cast(Values.octets(value, type(column)));
		}
		if (asked == Array.class) {
			return asked.cast(getArray(column));
		}
		return asked.cast(Values.as(asked, value, type(column)));
	}

	/**
	 * An array the host read: of {@code int4}, {@code int8}, {@code numeric} or
	 * {@code text} (a {@code varchar[]} is one of text). The host reads no other array.
	 */
	@Override
	public @Nullable Array getArray(int column) throws SQLException {
		Postgres.DbValue value = value(column);
		if (value == null) {
			return null;
		}
		Array array = SpinPostgresArray.read(value);
		if (array != null) {
			return array;
		}
		if (value instanceof Postgres.DbValue.Unsupported && type(column).startsWith("_")) {
			throw Failures.unsupported("An array of the type " + type(column) + " (the host reads none: cast it in"
					+ " the statement, x::text[], for the texts of its elements)");
		}
		throw Failures.data("The value of the type " + type(column) + " is no array");
	}

	@Override
	public @Nullable Array getArray(String label) throws SQLException {
		return getArray(findColumn(label));
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
