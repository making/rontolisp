package am.ik.jdbc.spin.postgres;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.BatchUpdateException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A statement with parameters. The host takes a parameter as one of the cases of its
 * {@code parameter-value} and converts it by the type the server reads the parameter as,
 * refusing a case that is not that type's. So a parameter travels as the case its setter
 * names, and its type is written into the text as a cast around it: the server reads the
 * parameter as that type wherever it stands, as it reads one declared with the statement.
 * A value the host has no case for travels as text, cast to its type by the server.
 */
final class SpinPostgresPreparedStatement extends SpinPostgresStatement implements PreparedStatement {

	private static final Postgres.ParameterValue NULL = new Postgres.ParameterValue.DbNull();

	private final Sql sql;

	// The columns of the rows the statement writes to answer, or null.
	private final String @Nullable [] keyColumns;

	private Postgres.@Nullable ParameterValue[] values;

	// Each parameter's type as the text casts it, null for one left to the server.
	private @Nullable String[] types;

	private final List<Postgres.ParameterValue[]> batchValues = new ArrayList<>();

	private final List<@Nullable String[]> batchTypes = new ArrayList<>();

	SpinPostgresPreparedStatement(SpinPostgresConnection connection, int resultSetType, String sql,
			String @Nullable [] keyColumns) {
		super(connection, resultSetType);
		this.sql = Sql.parse(sql, true);
		this.keyColumns = keyColumns;
		this.values = new Postgres.ParameterValue[this.sql.parameters()];
		this.types = new String[this.sql.parameters()];
	}

	// Running.

	@Override
	public boolean execute() throws SQLException {
		return run(EITHER);
	}

	@Override
	public ResultSet executeQuery() throws SQLException {
		run(ROWS);
		return rows();
	}

	@Override
	public int executeUpdate() throws SQLException {
		return (int) executeLargeUpdate();
	}

	@Override
	public long executeLargeUpdate() throws SQLException {
		run(COUNT);
		return count();
	}

	private boolean run(int asked) throws SQLException {
		begin();
		return run(this.sql, this.keyColumns, this.types, complete(), asked);
	}

	@Override
	void textual() throws SQLException {
		throw new SQLException("A prepared statement runs its own text and takes no other", "HY010");
	}

	private List<Postgres.ParameterValue> complete() throws SQLException {
		Postgres.ParameterValue[] given = new Postgres.ParameterValue[this.values.length];
		for (int i = 0; i < given.length; i++) {
			Postgres.ParameterValue value = this.values[i];
			if (value == null) {
				throw new SQLException("The parameter " + (i + 1) + " has no value", "07001");
			}
			given[i] = value;
		}
		return Arrays.asList(given);
	}

	// A batch.

	@Override
	public void addBatch() throws SQLException {
		open();
		this.batchValues.add(complete().toArray(new Postgres.ParameterValue[0]));
		this.batchTypes.add(this.types.clone());
	}

	@Override
	public void clearBatch() throws SQLException {
		open();
		this.batchValues.clear();
		this.batchTypes.clear();
	}

	/**
	 * Runs the batch a row at a time, as one transaction where the connection commits
	 * each statement: a refusal undoes the rows before it, and every count of the
	 * exception is {@link Statement#EXECUTE_FAILED}.
	 */
	@Override
	public long[] executeLargeBatch() throws SQLException {
		begin();
		long[] counts = new long[this.batchValues.size()];
		List<Postgres.RowSet> written = new ArrayList<>();
		try {
			this.connection.atomically(() -> {
				for (int i = 0; i < counts.length; i++) {
					run(this.sql, this.keyColumns, this.batchTypes.get(i), Arrays.asList(this.batchValues.get(i)),
							COUNT);
					counts[i] = count();
					Postgres.RowSet keys = keys();
					if (keys != null) {
						written.add(keys);
					}
				}
			});
		}
		catch (SQLException refused) {
			Arrays.fill(counts, Statement.EXECUTE_FAILED);
			throw new BatchUpdateException(refused.getMessage(), refused.getSQLState(), 0, counts, refused);
		}
		finally {
			this.batchValues.clear();
			this.batchTypes.clear();
			forget();
		}
		keys(written);
		return counts;
	}

	// What the statement takes and answers, asked of the server before it runs: the
	// host describes no statement, so the server is asked by statements of the
	// driver's (SpinPostgresConnection.columns, parameterTypes).

	/**
	 * The columns of the rows a query answers, its parameters NULL; {@code null} for a
	 * statement that answers no rows. Those a command writes and returns
	 * ({@code RETURNING}) are not told before it has run: the server describes a command
	 * only by running it, or by preparing it, which tells no column's name.
	 */
	@Override
	public @Nullable ResultSetMetaData getMetaData() throws SQLException {
		open();
		if (this.sql.query()) {
			return new SpinPostgresResultSetMetaData(
					this.connection.columns(this.sql.describing(this.types), this.values.length));
		}
		if (!this.sql.rows()) {
			return null;
		}
		ResultSet rows = getResultSet();
		if (rows == null) {
			throw Failures.unsupported("PreparedStatement.getMetaData of a command's rows (RETURNING, SHOW, EXPLAIN,"
					+ " FETCH) before the command has run");
		}
		return rows.getMetaData();
	}

	/**
	 * The parameters as the server reads them: one with a value as the type its setter
	 * casts it to, one without as the server takes it from where it stands.
	 */
	@Override
	public ParameterMetaData getParameterMetaData() throws SQLException {
		open();
		return new SpinPostgresParameterMetaData(
				this.values.length == 0 ? List.of() : this.connection.parameterTypes(this.sql.text(this.types)));
	}

	// The parameters.

	private void set(int parameter, Postgres.@Nullable ParameterValue value, @Nullable String type)
			throws SQLException {
		open();
		if (parameter < 1 || parameter > this.values.length) {
			throw new SQLException("The statement has no parameter " + parameter, "22023");
		}
		this.values[parameter - 1] = value != null ? value : NULL;
		this.types[parameter - 1] = type;
	}

	@Override
	public void clearParameters() throws SQLException {
		open();
		Arrays.fill(this.values, null);
		Arrays.fill(this.types, null);
	}

	/** The type a parameter of a constant of {@link Types} is cast to. */
	private static @Nullable String type(int jdbc) {
		return switch (jdbc) {
			case Types.BOOLEAN, Types.BIT -> "bool";
			case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> "bytea";
			case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR ->
				"varchar";
			case Types.BIGINT -> "int8";
			case Types.TINYINT, Types.SMALLINT -> "int2";
			case Types.INTEGER -> "int4";
			case Types.REAL -> "float4";
			case Types.FLOAT, Types.DOUBLE -> "float8";
			case Types.NUMERIC, Types.DECIMAL -> "numeric";
			case Types.DATE -> "date";
			case Types.TIME -> "time";
			case Types.TIME_WITH_TIMEZONE -> "timetz";
			case Types.TIMESTAMP -> "timestamp";
			case Types.TIMESTAMP_WITH_TIMEZONE -> "timestamptz";
			default -> null;
		};
	}

	@Override
	public void setNull(int parameter, int sqlType) throws SQLException {
		set(parameter, null, type(sqlType));
	}

	@Override
	public void setNull(int parameter, int sqlType, String typeName) throws SQLException {
		set(parameter, null, type(sqlType));
	}

	@Override
	public void setBoolean(int parameter, boolean x) throws SQLException {
		set(parameter, new Postgres.ParameterValue.Boolean(x), "bool");
	}

	@Override
	public void setByte(int parameter, byte x) throws SQLException {
		set(parameter, new Postgres.ParameterValue.Int16(x), "int2");
	}

	@Override
	public void setShort(int parameter, short x) throws SQLException {
		set(parameter, new Postgres.ParameterValue.Int16(x), "int2");
	}

	@Override
	public void setInt(int parameter, int x) throws SQLException {
		set(parameter, new Postgres.ParameterValue.Int32(x), "int4");
	}

	@Override
	public void setLong(int parameter, long x) throws SQLException {
		set(parameter, new Postgres.ParameterValue.Int64(x), "int8");
	}

	@Override
	public void setFloat(int parameter, float x) throws SQLException {
		set(parameter, new Postgres.ParameterValue.Floating32(x), "float4");
	}

	@Override
	public void setDouble(int parameter, double x) throws SQLException {
		set(parameter, new Postgres.ParameterValue.Floating64(x), "float8");
	}

	@Override
	public void setBigDecimal(int parameter, @Nullable BigDecimal x) throws SQLException {
		set(parameter, x == null ? null : new Postgres.ParameterValue.Decimal(x.toPlainString()), "numeric");
	}

	@Override
	public void setString(int parameter, @Nullable String x) throws SQLException {
		set(parameter, x == null ? null : new Postgres.ParameterValue.Str(x), "varchar");
	}

	@Override
	public void setNString(int parameter, @Nullable String x) throws SQLException {
		setString(parameter, x);
	}

	@Override
	public void setBytes(int parameter, byte @Nullable [] x) throws SQLException {
		set(parameter, x == null ? null : new Postgres.ParameterValue.Binary(x), "bytea");
	}

	@Override
	public void setDate(int parameter, @Nullable Date x) throws SQLException {
		set(parameter, x == null ? null : date(x.toLocalDate()), "date");
	}

	@Override
	public void setTime(int parameter, @Nullable Time x) throws SQLException {
		set(parameter,
				x == null ? null : time(x.toLocalTime().plusNanos(Math.floorMod(x.getTime(), 1000L) * 1_000_000L)),
				"time");
	}

	/**
	 * A timestamp is sent as the fields of the wall where the program runs, a timestamp
	 * without a time zone: the host has no case the server reads as either kind. Where
	 * the parameter stands for a timestamp with a time zone the server reads the fields
	 * on the wall of the session's time zone; an {@code OffsetDateTime} or an
	 * {@code Instant} given to {@code setObject} is the instant whatever that zone is.
	 */
	@Override
	public void setTimestamp(int parameter, @Nullable Timestamp x) throws SQLException {
		set(parameter, x == null ? null : dateTime(x.toLocalDateTime()), "timestamp");
	}

	// With a calendar: its time zone is the wall the value's instant is written on.

	@Override
	public void setDate(int parameter, @Nullable Date x, @Nullable Calendar calendar) throws SQLException {
		if (x == null || calendar == null) {
			setDate(parameter, x);
		}
		else {
			set(parameter,
					date(Instant.ofEpochMilli(x.getTime()).atZone(calendar.getTimeZone().toZoneId()).toLocalDate()),
					"date");
		}
	}

	@Override
	public void setTime(int parameter, @Nullable Time x, @Nullable Calendar calendar) throws SQLException {
		if (x == null || calendar == null) {
			setTime(parameter, x);
		}
		else {
			set(parameter,
					time(Instant.ofEpochMilli(x.getTime()).atZone(calendar.getTimeZone().toZoneId()).toLocalTime()),
					"time");
		}
	}

	@Override
	public void setTimestamp(int parameter, @Nullable Timestamp x, @Nullable Calendar calendar) throws SQLException {
		if (x == null || calendar == null) {
			setTimestamp(parameter, x);
		}
		else {
			set(parameter, dateTime(x.toInstant().atZone(calendar.getTimeZone().toZoneId()).toLocalDateTime()),
					"timestamp");
		}
	}

	private static Postgres.ParameterValue date(LocalDate date) {
		return new Postgres.ParameterValue.Date(
				new Postgres.Ymd(date.getYear(), (byte) date.getMonthValue(), (byte) date.getDayOfMonth()));
	}

	private static Postgres.ParameterValue time(LocalTime time) {
		return new Postgres.ParameterValue.Time(new Postgres.Hms((byte) time.getHour(), (byte) time.getMinute(),
				(byte) time.getSecond(), time.getNano()));
	}

	private static Postgres.ParameterValue dateTime(LocalDateTime dateTime) {
		return new Postgres.ParameterValue.Datetime(new Postgres.YmdHms(dateTime.getYear(),
				(byte) dateTime.getMonthValue(), (byte) dateTime.getDayOfMonth(), (byte) dateTime.getHour(),
				(byte) dateTime.getMinute(), (byte) dateTime.getSecond(), dateTime.getNano()));
	}

	// A value the host has no case for: its text, which the server casts to the type.
	private void text(int parameter, String text, String type) throws SQLException {
		set(parameter, new Postgres.ParameterValue.Str(text), "text::" + type);
	}

	@Override
	public void setObject(int parameter, @Nullable Object x) throws SQLException {
		if (x == null) {
			set(parameter, null, null);
		}
		else if (x instanceof String text) {
			setString(parameter, text);
		}
		else if (x instanceof Character character) {
			setString(parameter, character.toString());
		}
		else if (x instanceof Integer number) {
			setInt(parameter, number);
		}
		else if (x instanceof Long number) {
			setLong(parameter, number);
		}
		else if (x instanceof Short number) {
			setShort(parameter, number);
		}
		else if (x instanceof Byte number) {
			setByte(parameter, number);
		}
		else if (x instanceof Boolean flag) {
			setBoolean(parameter, flag);
		}
		else if (x instanceof Double number) {
			setDouble(parameter, number);
		}
		else if (x instanceof Float number) {
			setFloat(parameter, number);
		}
		else if (x instanceof BigDecimal number) {
			setBigDecimal(parameter, number);
		}
		else if (x instanceof BigInteger number) {
			set(parameter, new Postgres.ParameterValue.Decimal(number.toString()), "numeric");
		}
		else if (x instanceof byte[] octets) {
			setBytes(parameter, octets);
		}
		else if (x instanceof UUID uuid) {
			set(parameter, new Postgres.ParameterValue.Uuid(uuid.toString()), "uuid");
		}
		else if (x instanceof Date date) {
			setDate(parameter, date);
		}
		else if (x instanceof Time time) {
			setTime(parameter, time);
		}
		else if (x instanceof Timestamp timestamp) {
			setTimestamp(parameter, timestamp);
		}
		else if (x instanceof java.util.Date date) {
			setTimestamp(parameter, new Timestamp(date.getTime()));
		}
		else if (x instanceof LocalDate date) {
			set(parameter, date(date), "date");
		}
		else if (x instanceof LocalTime time) {
			set(parameter, time(time), "time");
		}
		else if (x instanceof LocalDateTime dateTime) {
			set(parameter, dateTime(dateTime), "timestamp");
		}
		else if (x instanceof OffsetDateTime dateTime) {
			text(parameter, Moment.text(dateTime), "timestamptz");
		}
		else if (x instanceof ZonedDateTime dateTime) {
			text(parameter, Moment.text(dateTime.toOffsetDateTime()), "timestamptz");
		}
		else if (x instanceof Instant instant) {
			text(parameter, Moment.text(instant.atOffset(ZoneOffset.UTC)), "timestamptz");
		}
		else if (x instanceof OffsetTime time) {
			text(parameter, Moment.text(time), "timetz");
		}
		else if (x instanceof Array array) {
			setArray(parameter, array);
		}
		else {
			throw Failures.unsupported("A parameter of the class " + x.getClass().getName());
		}
	}

	/**
	 * A value sent as the type a constant of {@link Types} names. A parameter always has
	 * a type here, since the host converts by it: {@link Types#OTHER} and the constants
	 * the driver knows no type for send the value's text as {@code text}, so a value of a
	 * type of the server's own (json, an enum) is cast in the statement
	 * ({@code ?::jsonb}).
	 */
	@Override
	public void setObject(int parameter, @Nullable Object x, int targetSqlType) throws SQLException {
		String type = type(targetSqlType);
		if (x == null) {
			set(parameter, null, type);
			return;
		}
		if (x instanceof Array array) {
			setArray(parameter, array);
			return;
		}
		// The value as its own class is sent, then given the type asked for: of the
		// same type nothing changes, of another the server casts it.
		setObject(parameter, x);
		String own = this.types[parameter - 1];
		if (type == null) {
			if (!(x instanceof UUID)) {
				set(parameter, new Postgres.ParameterValue.Str(text(x)), "text");
			}
		}
		else if (own != null && !own.equals(type) && !own.endsWith("::" + type)) {
			this.types[parameter - 1] = own + "::" + type;
		}
	}

	// A value's text, as the server reads it.
	static String text(Object x) {
		if (x instanceof byte[] octets) {
			return Values.hex(octets);
		}
		if (x instanceof BigDecimal decimal) {
			return decimal.toPlainString();
		}
		if (x instanceof Date date) {
			return Moment.text(date.toLocalDate());
		}
		if (x instanceof Time time) {
			return Moment.text(time.toLocalTime().plusNanos(Math.floorMod(time.getTime(), 1000L) * 1_000_000L));
		}
		if (x instanceof java.util.Date date) {
			return Moment.text(new Timestamp(date.getTime()).toLocalDateTime());
		}
		if (x instanceof LocalDate date) {
			return Moment.text(date);
		}
		if (x instanceof LocalTime time) {
			return Moment.text(time);
		}
		if (x instanceof LocalDateTime dateTime) {
			return Moment.text(dateTime);
		}
		if (x instanceof OffsetDateTime dateTime) {
			return Moment.text(dateTime);
		}
		if (x instanceof ZonedDateTime dateTime) {
			return Moment.text(dateTime.toOffsetDateTime());
		}
		if (x instanceof Instant instant) {
			return Moment.text(instant.atOffset(ZoneOffset.UTC));
		}
		if (x instanceof OffsetTime time) {
			return Moment.text(time);
		}
		return x.toString();
	}

	@Override
	public void setObject(int parameter, @Nullable Object x, int targetSqlType, int scaleOrLength) throws SQLException {
		setObject(parameter, x, targetSqlType);
	}

	@Override
	public void setURL(int parameter, @Nullable URL x) throws SQLException {
		setString(parameter, x == null ? null : x.toString());
	}

	// A stream is read to its end, or to the length stated, when the parameter is set.

	@Override
	public void setBinaryStream(int parameter, @Nullable InputStream x) throws SQLException {
		setBytes(parameter, x == null ? null : octets(x, Long.MAX_VALUE));
	}

	@Override
	public void setBinaryStream(int parameter, @Nullable InputStream x, int length) throws SQLException {
		setBytes(parameter, x == null ? null : octets(x, length));
	}

	@Override
	public void setBinaryStream(int parameter, @Nullable InputStream x, long length) throws SQLException {
		setBytes(parameter, x == null ? null : octets(x, length));
	}

	@Override
	public void setAsciiStream(int parameter, @Nullable InputStream x) throws SQLException {
		setString(parameter, x == null ? null : new String(octets(x, Long.MAX_VALUE), StandardCharsets.US_ASCII));
	}

	@Override
	public void setAsciiStream(int parameter, @Nullable InputStream x, int length) throws SQLException {
		setString(parameter, x == null ? null : new String(octets(x, length), StandardCharsets.US_ASCII));
	}

	@Override
	public void setAsciiStream(int parameter, @Nullable InputStream x, long length) throws SQLException {
		setString(parameter, x == null ? null : new String(octets(x, length), StandardCharsets.US_ASCII));
	}

	@Override
	public void setCharacterStream(int parameter, @Nullable Reader x) throws SQLException {
		setString(parameter, x == null ? null : characters(x, Long.MAX_VALUE));
	}

	@Override
	public void setCharacterStream(int parameter, @Nullable Reader x, int length) throws SQLException {
		setString(parameter, x == null ? null : characters(x, length));
	}

	@Override
	public void setCharacterStream(int parameter, @Nullable Reader x, long length) throws SQLException {
		setString(parameter, x == null ? null : characters(x, length));
	}

	@Override
	public void setNCharacterStream(int parameter, @Nullable Reader x) throws SQLException {
		setCharacterStream(parameter, x);
	}

	@Override
	public void setNCharacterStream(int parameter, @Nullable Reader x, long length) throws SQLException {
		setCharacterStream(parameter, x, length);
	}

	private static byte[] octets(InputStream stream, long length) throws SQLException {
		try {
			byte[] octets = new byte[8192];
			int count = 0;
			while (count < length) {
				if (count == octets.length) {
					octets = Arrays.copyOf(octets, count * 2);
				}
				int read = stream.read(octets, count, (int) Math.min(octets.length - count, length - count));
				if (read < 0) {
					break;
				}
				count += read;
			}
			return Arrays.copyOf(octets, count);
		}
		catch (IOException ex) {
			throw new SQLException("The stream of a parameter cannot be read: " + ex.getMessage(), "22000", ex);
		}
	}

	private static String characters(Reader reader, long length) throws SQLException {
		try {
			StringBuilder text = new StringBuilder();
			char[] characters = new char[4096];
			while (text.length() < length) {
				int read = reader.read(characters, 0, (int) Math.min(characters.length, length - text.length()));
				if (read < 0) {
					break;
				}
				text.append(characters, 0, read);
			}
			return text.toString();
		}
		catch (IOException ex) {
			throw new SQLException("The reader of a parameter cannot be read: " + ex.getMessage(), "22000", ex);
		}
	}

	/**
	 * An array of one dimension: as the host's case of its element type where it has one,
	 * else as the texts of its elements, which the server casts.
	 */
	@Override
	public void setArray(int parameter, @Nullable Array x) throws SQLException {
		if (x == null) {
			set(parameter, null, null);
			return;
		}
		SpinPostgresArray array = x instanceof SpinPostgresArray own ? own
				: SpinPostgresArray.of(x.getBaseTypeName(), (Object[]) x.getArray());
		Postgres.ParameterValue value = array.parameter();
		set(parameter, value, array.cast(value));
	}

	// What the driver does not write.

	@Override
	@Deprecated
	public void setUnicodeStream(int parameter, InputStream x, int length) throws SQLException {
		throw Failures.unsupported("PreparedStatement.setUnicodeStream");
	}

	@Override
	public void setRef(int parameter, Ref x) throws SQLException {
		throw Failures.unsupported("PreparedStatement.setRef");
	}

	@Override
	public void setBlob(int parameter, Blob x) throws SQLException {
		throw Failures.unsupported("PreparedStatement.setBlob");
	}

	@Override
	public void setBlob(int parameter, InputStream x) throws SQLException {
		throw Failures.unsupported("PreparedStatement.setBlob");
	}

	@Override
	public void setBlob(int parameter, InputStream x, long length) throws SQLException {
		throw Failures.unsupported("PreparedStatement.setBlob");
	}

	@Override
	public void setClob(int parameter, Clob x) throws SQLException {
		throw Failures.unsupported("PreparedStatement.setClob");
	}

	@Override
	public void setClob(int parameter, Reader x) throws SQLException {
		throw Failures.unsupported("PreparedStatement.setClob");
	}

	@Override
	public void setClob(int parameter, Reader x, long length) throws SQLException {
		throw Failures.unsupported("PreparedStatement.setClob");
	}

	@Override
	public void setNClob(int parameter, NClob x) throws SQLException {
		throw Failures.unsupported("PreparedStatement.setNClob");
	}

	@Override
	public void setNClob(int parameter, Reader x) throws SQLException {
		throw Failures.unsupported("PreparedStatement.setNClob");
	}

	@Override
	public void setNClob(int parameter, Reader x, long length) throws SQLException {
		throw Failures.unsupported("PreparedStatement.setNClob");
	}

	@Override
	public void setRowId(int parameter, RowId x) throws SQLException {
		throw Failures.unsupported("PreparedStatement.setRowId");
	}

	@Override
	public void setSQLXML(int parameter, SQLXML x) throws SQLException {
		throw Failures.unsupported("PreparedStatement.setSQLXML");
	}

}
