package am.ik.jdbc.postgresql;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A statement with parameters, run through the extended query: parsed into the unnamed
 * prepared statement, bound and run in one round trip each time, so the server holds
 * nothing of it between two runs. A parameter travels as text with the type its setter
 * names.
 */
final class PostgresPreparedStatement extends PostgresStatement implements PreparedStatement {

	private final Sql sql;

	// Whether the rows the statement writes are asked for.
	private final boolean answering;

	private @Nullable String[] values;

	private int[] types;

	private boolean[] given;

	private final List<String[]> batchValues = new ArrayList<>();

	private final List<int[]> batchTypes = new ArrayList<>();

	PostgresPreparedStatement(PostgresConnection connection, int resultSetType, String sql,
			String @Nullable [] keyColumns) {
		super(connection, resultSetType);
		Sql written = Sql.parse(sql, true);
		this.sql = keyColumns != null ? written.answering(keyColumns) : written;
		this.answering = this.sql != written;
		this.values = new String[written.parameters];
		this.types = new int[written.parameters];
		this.given = new boolean[written.parameters];
	}

	// Running.

	@Override
	public boolean execute() throws SQLException {
		Session session = begin();
		complete();
		synchronized (session) {
			ahead(session);
			session.parse(this.sql.text, this.types);
			session.bind(this.values, true, maxRows());
			session.sync();
			try {
				session.run(this.results, 0, timeoutSeconds() > 0);
			}
			finally {
				warnings(session.takeWarnings());
			}
		}
		return settle(this.answering);
	}

	@Override
	public ResultSet executeQuery() throws SQLException {
		execute();
		return rows();
	}

	@Override
	public int executeUpdate() throws SQLException {
		execute();
		return (int) count();
	}

	@Override
	public long executeLargeUpdate() throws SQLException {
		execute();
		return count();
	}

	@Override
	void textual() throws SQLException {
		throw new SQLException("A prepared statement runs its own text and takes no other", "HY010");
	}

	private void complete() throws SQLException {
		for (int i = 0; i < this.given.length; i++) {
			if (!this.given[i]) {
				throw new SQLException("The parameter " + (i + 1) + " has no value", "07001");
			}
		}
	}

	// A batch.

	@Override
	public void addBatch() throws SQLException {
		open();
		complete();
		this.batchValues.add(this.values.clone());
		this.batchTypes.add(this.types.clone());
	}

	@Override
	public void clearBatch() throws SQLException {
		open();
		this.batchValues.clear();
		this.batchTypes.clear();
	}

	/**
	 * Runs the batch in one round trip and one group: the statement is parsed once for
	 * each run of rows of the same types and bound once a row. The group is one
	 * transaction of the server's, so a refusal undoes the rows before it, and every
	 * count of the exception is {@link Statement#EXECUTE_FAILED}.
	 */
	@Override
	public long[] executeLargeBatch() throws SQLException {
		Session session = begin();
		long[] counts = new long[this.batchValues.size()];
		if (counts.length == 0) {
			return counts;
		}
		SQLException refused;
		synchronized (session) {
			ahead(session);
			int[] parsed = null;
			for (int i = 0; i < counts.length; i++) {
				if (parsed == null || !Arrays.equals(parsed, this.batchTypes.get(i))) {
					parsed = this.batchTypes.get(i);
					session.parse(this.sql.text, parsed);
				}
				session.bind(this.batchValues.get(i), this.answering, 0);
			}
			session.sync();
			this.batchValues.clear();
			this.batchTypes.clear();
			try {
				refused = session.group(this.results, 0, timeoutSeconds() > 0);
			}
			finally {
				warnings(session.takeWarnings());
			}
		}
		if (refused != null) {
			Arrays.fill(counts, Statement.EXECUTE_FAILED);
			this.results.clear();
			throw new BatchUpdateException(refused.getMessage(), refused.getSQLState(), 0, counts, refused);
		}
		for (int i = 0; i < counts.length && i < this.results.size(); i++) {
			counts[i] = this.results.get(i).count();
		}
		settle(this.answering);
		this.results.clear();
		return counts;
	}

	// What the statement takes and answers, asked of the server without running it.

	@Override
	public @Nullable ResultSetMetaData getMetaData() throws SQLException {
		List<Result> described = describe();
		Column[] columns = described.isEmpty() ? null : described.get(0).columns;
		return columns == null ? null : new PostgresResultSetMetaData(this.connection, columns);
	}

	@Override
	public ParameterMetaData getParameterMetaData() throws SQLException {
		describe();
		return new PostgresParameterMetaData(this.connection, this.connection.session().described());
	}

	private List<Result> describe() throws SQLException {
		open();
		Session session = this.connection.session();
		List<Result> described = new ArrayList<>();
		synchronized (session) {
			session.parse(this.sql.text, this.types);
			session.describe();
			session.sync();
			session.run(described, 0, false);
		}
		return described;
	}

	// The parameters.

	private void set(int parameter, @Nullable String value, int type) throws SQLException {
		open();
		if (parameter < 1 || parameter > this.values.length) {
			throw new SQLException("The statement has no parameter " + parameter, "22023");
		}
		this.values[parameter - 1] = value;
		this.types[parameter - 1] = type;
		this.given[parameter - 1] = true;
	}

	@Override
	public void clearParameters() throws SQLException {
		open();
		Arrays.fill(this.values, null);
		Arrays.fill(this.types, 0);
		Arrays.fill(this.given, false);
	}

	@Override
	public void setNull(int parameter, int sqlType) throws SQLException {
		set(parameter, null, TypeId.of(sqlType));
	}

	@Override
	public void setNull(int parameter, int sqlType, String typeName) throws SQLException {
		set(parameter, null, TypeId.of(sqlType));
	}

	@Override
	public void setBoolean(int parameter, boolean x) throws SQLException {
		set(parameter, x ? "true" : "false", TypeId.BOOL);
	}

	@Override
	public void setByte(int parameter, byte x) throws SQLException {
		set(parameter, Integer.toString(x), TypeId.INT2);
	}

	@Override
	public void setShort(int parameter, short x) throws SQLException {
		set(parameter, Integer.toString(x), TypeId.INT2);
	}

	@Override
	public void setInt(int parameter, int x) throws SQLException {
		set(parameter, Integer.toString(x), TypeId.INT4);
	}

	@Override
	public void setLong(int parameter, long x) throws SQLException {
		set(parameter, Long.toString(x), TypeId.INT8);
	}

	@Override
	public void setFloat(int parameter, float x) throws SQLException {
		set(parameter, Float.toString(x), TypeId.FLOAT4);
	}

	@Override
	public void setDouble(int parameter, double x) throws SQLException {
		set(parameter, Double.toString(x), TypeId.FLOAT8);
	}

	@Override
	public void setBigDecimal(int parameter, @Nullable BigDecimal x) throws SQLException {
		set(parameter, x == null ? null : x.toPlainString(), TypeId.NUMERIC);
	}

	@Override
	public void setString(int parameter, @Nullable String x) throws SQLException {
		set(parameter, x, TypeId.VARCHAR);
	}

	@Override
	public void setNString(int parameter, @Nullable String x) throws SQLException {
		set(parameter, x, TypeId.VARCHAR);
	}

	@Override
	public void setBytes(int parameter, byte @Nullable [] x) throws SQLException {
		set(parameter, x == null ? null : Values.hex(x), TypeId.BYTEA);
	}

	@Override
	public void setDate(int parameter, @Nullable Date x) throws SQLException {
		set(parameter, x == null ? null : Values.text(x), TypeId.DATE);
	}

	@Override
	public void setTime(int parameter, @Nullable Time x) throws SQLException {
		set(parameter, x == null ? null : Values.text(x), TypeId.TIME);
	}

	/**
	 * A timestamp is sent with the offset of where the program runs and no type, so the
	 * server reads it as the type of where the parameter stands: the instant for a column
	 * with a time zone, the wall's fields for one without.
	 */
	@Override
	public void setTimestamp(int parameter, @Nullable Timestamp x) throws SQLException {
		set(parameter, x == null ? null : Values.text(x), TypeId.UNSPECIFIED);
	}

	// With a calendar: its time zone is the wall the value's instant is written on.

	@Override
	public void setDate(int parameter, @Nullable Date x, @Nullable Calendar calendar) throws SQLException {
		if (x == null || calendar == null) {
			setDate(parameter, x);
		}
		else {
			set(parameter,
					Moment.text(
							Instant.ofEpochMilli(x.getTime()).atZone(calendar.getTimeZone().toZoneId()).toLocalDate()),
					TypeId.DATE);
		}
	}

	@Override
	public void setTime(int parameter, @Nullable Time x, @Nullable Calendar calendar) throws SQLException {
		if (x == null || calendar == null) {
			setTime(parameter, x);
		}
		else {
			set(parameter,
					Moment.text(
							Instant.ofEpochMilli(x.getTime()).atZone(calendar.getTimeZone().toZoneId()).toLocalTime()),
					TypeId.TIME);
		}
	}

	@Override
	public void setTimestamp(int parameter, @Nullable Timestamp x, @Nullable Calendar calendar) throws SQLException {
		if (x == null || calendar == null) {
			setTimestamp(parameter, x);
		}
		else {
			set(parameter, Values.text(x, calendar.getTimeZone().toZoneId()), TypeId.UNSPECIFIED);
		}
	}

	@Override
	public void setObject(int parameter, @Nullable Object x) throws SQLException {
		if (x == null) {
			set(parameter, null, TypeId.UNSPECIFIED);
			return;
		}
		int type = Values.type(x);
		if (type < 0) {
			throw Failures.unsupported("A parameter of the class " + x.getClass().getName());
		}
		set(parameter, Values.text(x), type);
	}

	/**
	 * A value sent as the type a constant of {@link Types} names; {@link Types#OTHER} and
	 * the constants the driver knows no type for leave the type to the server, which is
	 * how a value of a type of the server's own (json, an enum) is written.
	 */
	@Override
	public void setObject(int parameter, @Nullable Object x, int targetSqlType) throws SQLException {
		int type = TypeId.of(targetSqlType);
		if (x == null) {
			set(parameter, null, type);
		}
		else if (x instanceof Boolean flag && type != TypeId.BOOL && type != TypeId.VARCHAR
				&& type != TypeId.UNSPECIFIED) {
			set(parameter, flag ? "1" : "0", type);
		}
		else {
			set(parameter, Values.text(x),
					type == TypeId.UNSPECIFIED && Values.type(x) == TypeId.UUID ? TypeId.UUID : type);
		}
	}

	@Override
	public void setObject(int parameter, @Nullable Object x, int targetSqlType, int scaleOrLength) throws SQLException {
		setObject(parameter, x, targetSqlType);
	}

	@Override
	public void setURL(int parameter, @Nullable URL x) throws SQLException {
		set(parameter, x == null ? null : x.toString(), TypeId.VARCHAR);
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
	public void setArray(int parameter, Array x) throws SQLException {
		throw Failures.unsupported("PreparedStatement.setArray");
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
