package am.ik.jdbc.spin.postgres;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import am.ik.jdbc.postgresql.PostgresDriver;
import am.ik.rontolisp.wit.WitException;

/**
 * A stand-in for the host under a JDK: {@code spin:postgres/postgres} answered over a
 * socket, through {@code jdbc-postgresql}, so the driver's statements reach a real
 * server. It is a model of Spin, each rule of it measured on Spin 4.3.0 against
 * PostgreSQL 17 (2026-11-25); it is not Spin, and what it models is listed here.
 *
 * <ul>
 * <li>The address is {@code postgres://user:password@host:port/database} with the
 * settings {@code sslmode}, {@code application_name}, {@code options} and
 * {@code connect_timeout}; another setting is an invalid connection string.</li>
 * <li>A session is kept by its address when its connection is closed and handed out to
 * the next one opened: its settings reset, a transaction left open still open.</li>
 * <li>A parameter is converted by the type the server reads it as, and a case that is not
 * that type's is refused; a statement is one prepared statement.</li>
 * <li>A result's columns are the cases the host converts, any other type a name and the
 * server's octets; a {@code timestamptz}, an infinite date and a numeric NaN fail the
 * whole query.</li>
 * <li>A statement the server refuses while its rows are read is handed over as text; any
 * other refusal with its fields.</li>
 * </ul>
 */
public final class SocketPostgres implements Postgres {

	private static final Set<String> SETTINGS = Set.of("sslmode", "application_name", "options", "connect_timeout");

	// The sessions given back, by address: the host's pool.
	private static final Map<String, Deque<java.sql.Connection>> POOL = new HashMap<>();

	/** The certificate the last connection builder was given, for a test to read. */
	static volatile String lastCertificate;

	/** The address the last connection was opened for. */
	static volatile String lastAddress;

	@Override
	public Connection connectionOpen(String address) {
		lastAddress = address;
		return new Session(address, open(address));
	}

	@Override
	public ConnectionBuilder connectionBuilderNew(String address) {
		return new ConnectionBuilder() {
			@Override
			public void setCaRoot(String certificate) {
				lastCertificate = certificate;
			}

			@Override
			public Connection build() {
				return connectionOpen(address);
			}

			@Override
			public void close() {
			}
		};
	}

	private static java.sql.Connection open(String address) {
		synchronized (POOL) {
			Deque<java.sql.Connection> kept = POOL.get(address);
			if (kept != null && !kept.isEmpty()) {
				return kept.pop();
			}
		}
		try {
			URI uri = URI.create(address);
			if (!"postgres".equals(uri.getScheme())) {
				throw new IllegalArgumentException(address);
			}
			Properties info = new Properties();
			String[] login = uri.getRawUserInfo().split(":", 2);
			info.setProperty("user", decode(login[0]));
			if (login.length > 1) {
				info.setProperty("password", decode(login[1]));
			}
			if (uri.getRawQuery() != null) {
				for (String pair : uri.getRawQuery().split("&")) {
					String[] setting = pair.split("=", 2);
					if (!SETTINGS.contains(setting[0])) {
						throw new IllegalArgumentException(pair);
					}
					String value = decode(setting[1]);
					switch (setting[0]) {
						case "sslmode" -> {
							if (!Set.of("disable", "prefer", "require").contains(value)) {
								throw new IllegalArgumentException(pair);
							}
							if (value.equals("require")) {
								throw new WitException("connection-open",
										new Error.ConnectionFailed("error performing TLS handshake: no TLS here"));
							}
						}
						case "application_name" -> info.setProperty("ApplicationName", value);
						case "options" -> info.setProperty("options", value);
						default -> info.setProperty("connectTimeout", value);
					}
				}
			}
			String url = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + "/"
					+ decode(uri.getRawPath().substring(1));
			return new PostgresDriver().connect(url, info);
		}
		catch (IllegalArgumentException ex) {
			throw new WitException("connection-open", new Error.ConnectionFailed("invalid connection string"));
		}
		catch (SQLException ex) {
			throw new WitException("connection-open",
					new Error.ConnectionFailed("Error occurred while creating a new object: db error\n\nCaused by:\n"
							+ "    0: db error\n    1: " + ex.getMessage()));
		}
	}

	private static String decode(String text) {
		return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
	}

	private static final class Session implements Connection {

		private final String address;

		private final java.sql.Connection connection;

		Session(String address, java.sql.Connection connection) {
			this.address = address;
			this.connection = connection;
		}

		@Override
		public RowSet query(String statement, List<ParameterValue> params) {
			try (PreparedStatement prepared = prepare(statement, params)) {
				boolean rows;
				try {
					rows = prepared.execute();
				}
				catch (SQLException ex) {
					// Refused while the statement ran: the host hands it over as text.
					throw new WitException("query", new Error.QueryFailed(new QueryError.Text(wrapped(ex))));
				}
				if (!rows) {
					return new RowSet(List.of(), List.of());
				}
				return rows(prepared.getResultSet());
			}
			catch (SQLException ex) {
				throw refused("query", ex);
			}
		}

		@Override
		public long execute(String statement, List<ParameterValue> params) {
			try (PreparedStatement prepared = prepare(statement, params)) {
				if (!prepared.execute()) {
					return prepared.getLargeUpdateCount();
				}
				long count = 0;
				try (ResultSet rows = prepared.getResultSet()) {
					while (rows.next()) {
						count++;
					}
				}
				return count;
			}
			catch (SQLException ex) {
				throw refused("execute", ex);
			}
		}

		// The session goes back to the pool with its settings reset, which is refused
		// in
		// a transaction that failed: the host then drops the session.
		@Override
		public void close() {
			try (java.sql.Statement reset = this.connection.createStatement()) {
				reset.execute("RESET ALL");
				synchronized (POOL) {
					POOL.computeIfAbsent(this.address, key -> new ArrayDeque<>()).push(this.connection);
				}
			}
			catch (SQLException ex) {
				try {
					this.connection.close();
				}
				catch (SQLException ignored) {
					// dropped either way
				}
			}
		}

		// Prepares a statement, checks each parameter's case against the type the
		// server reads the parameter as, and binds the values as text with no type.
		private PreparedStatement prepare(String statement, List<ParameterValue> params) throws SQLException {
			// The socket driver numbers the question marks itself: the driver under test
			// writes each parameter once and in order.
			PreparedStatement prepared = this.connection.prepareStatement(
					params.isEmpty() ? statement.replace("?", "??") : statement.replaceAll("\\$[0-9]+", "?"));
			try {
				ParameterMetaData described = prepared.getParameterMetaData();
				if (described.getParameterCount() != params.size()) {
					throw new WitException("query",
							new Error.QueryFailed(new QueryError.Text("Error { kind: Parameters(" + params.size() + ", "
									+ described.getParameterCount() + "), cause: None }")));
				}
				for (int i = 0; i < params.size(); i++) {
					ParameterValue param = params.get(i);
					String type = described.getParameterTypeName(i + 1);
					if (param instanceof ParameterValue.DbNull) {
						prepared.setNull(i + 1, Types.NULL);
						continue;
					}
					if (!accepted(param).contains(type)) {
						throw new WitException("query",
								new Error.QueryFailed(new QueryError.Text(
										"Error { kind: ToSql(" + i + "), cause: Some(WrongType { postgres: " + type
												+ ", rust: \"" + param.getClass().getSimpleName() + "\" }) }")));
					}
					prepared.setObject(i + 1, text(param), Types.OTHER);
				}
				return prepared;
			}
			catch (SQLException | RuntimeException ex) {
				prepared.close();
				throw ex;
			}
		}

	}

	// The server's types a case is converted for.
	private static Set<String> accepted(ParameterValue param) {
		return switch (param) {
			case ParameterValue.Boolean p -> Set.of("bool");
			case ParameterValue.Int8 p -> Set.of("char");
			case ParameterValue.Int16 p -> Set.of("int2");
			case ParameterValue.Int32 p -> Set.of("int4");
			case ParameterValue.Int64 p -> Set.of("int8");
			case ParameterValue.Floating32 p -> Set.of("float4");
			case ParameterValue.Floating64 p -> Set.of("float8");
			case ParameterValue.Str p -> Set.of("text", "varchar", "bpchar", "name", "unknown");
			case ParameterValue.Binary p -> Set.of("bytea");
			case ParameterValue.Date p -> Set.of("date");
			case ParameterValue.Time p -> Set.of("time");
			case ParameterValue.Datetime p -> Set.of("timestamp");
			case ParameterValue.Timestamp p -> Set.of("timestamptz");
			case ParameterValue.Uuid p -> Set.of("uuid");
			case ParameterValue.Jsonb p -> Set.of("jsonb");
			case ParameterValue.Decimal p -> Set.of("numeric");
			case ParameterValue.RangeInt32 p -> Set.of("int4range");
			case ParameterValue.RangeInt64 p -> Set.of("int8range");
			case ParameterValue.RangeDecimal p -> Set.of("numrange");
			case ParameterValue.ArrayInt32 p -> Set.of("_int4");
			case ParameterValue.ArrayInt64 p -> Set.of("_int8");
			case ParameterValue.ArrayDecimal p -> Set.of("_numeric");
			case ParameterValue.ArrayStr p -> Set.of("_text", "_varchar");
			case ParameterValue.Interval p -> Set.of("interval");
			case ParameterValue.DbNull p -> Set.of();
		};
	}

	// A case's value as the text the server reads for its type.
	private static String text(ParameterValue param) {
		return switch (param) {
			case ParameterValue.Boolean(boolean value) -> Boolean.toString(value);
			case ParameterValue.Int8(byte value) -> Integer.toString(value);
			case ParameterValue.Int16(short value) -> Integer.toString(value);
			case ParameterValue.Int32(int value) -> Integer.toString(value);
			case ParameterValue.Int64(long value) -> Long.toString(value);
			case ParameterValue.Floating32(float value) -> Float.toString(value);
			case ParameterValue.Floating64(double value) -> Double.toString(value);
			case ParameterValue.Str(String value) -> value;
			case ParameterValue.Binary(byte[] value) -> Values.hex(value);
			case ParameterValue.Date(Ymd value) -> Moment.text(Values.date(value));
			case ParameterValue.Time(Hms value) -> Moment.text(Values.time(value));
			case ParameterValue.Datetime(YmdHms value) -> Moment.text(Values.dateTime(value));
			case ParameterValue.Timestamp(long value) -> Instant.ofEpochSecond(value).toString();
			case ParameterValue.Uuid(String value) -> value;
			case ParameterValue.Jsonb(byte[] value) -> new String(value, StandardCharsets.UTF_8);
			case ParameterValue.Decimal(String value) -> value;
			case ParameterValue.Interval(Postgres.Interval value) ->
				value.months() + " mons " + value.days() + " days " + value.micros() + " microseconds";
			case ParameterValue.ArrayInt32(List<Optional<Integer>> value) -> array(value);
			case ParameterValue.ArrayInt64(List<Optional<Long>> value) -> array(value);
			case ParameterValue.ArrayDecimal(List<Optional<String>> value) -> array(value);
			case ParameterValue.ArrayStr(List<Optional<String>> value) -> array(value);
			default -> throw new WitException("query", new Error.BadParameter("a case the stand-in binds none of"));
		};
	}

	// An array of one dimension as the server reads it: each element quoted.
	private static String array(List<? extends Optional<?>> elements) {
		StringBuilder text = new StringBuilder("{");
		for (Optional<?> element : elements) {
			text.append(text.length() > 1 ? "," : "");
			if (element.isEmpty()) {
				text.append("NULL");
			}
			else {
				text.append('"')
					.append(element.get().toString().replace("\\", "\\\\").replace("\"", "\\\""))
					.append('"');
			}
		}
		return text.append('}').toString();
	}

	private static RowSet rows(ResultSet result) throws SQLException {
		try (result) {
			ResultSetMetaData described = result.getMetaData();
			int width = described.getColumnCount();
			List<Column> columns = new ArrayList<>();
			String[] types = new String[width];
			for (int i = 0; i < width; i++) {
				types[i] = described.getColumnTypeName(i + 1);
				columns.add(new Column(described.getColumnLabel(i + 1), type(types[i])));
			}
			List<List<DbValue>> rows = new ArrayList<>();
			while (result.next()) {
				List<DbValue> row = new ArrayList<>();
				for (int i = 0; i < width; i++) {
					row.add(value(types[i], result.getString(i + 1), i));
				}
				rows.add(row);
			}
			return new RowSet(columns, rows);
		}
	}

	private static DbDataType type(String name) {
		return switch (name) {
			case "bool" -> new DbDataType.Boolean();
			case "int2" -> new DbDataType.Int16();
			case "int4" -> new DbDataType.Int32();
			case "int8" -> new DbDataType.Int64();
			case "float4" -> new DbDataType.Floating32();
			case "float8" -> new DbDataType.Floating64();
			case "text", "varchar", "bpchar", "unknown" -> new DbDataType.Str();
			case "bytea" -> new DbDataType.Binary();
			case "date" -> new DbDataType.Date();
			case "time" -> new DbDataType.Time();
			case "timestamp", "timestamptz" -> new DbDataType.Datetime();
			case "uuid" -> new DbDataType.Uuid();
			case "jsonb" -> new DbDataType.Jsonb();
			case "numeric" -> new DbDataType.Decimal();
			case "int4range" -> new DbDataType.RangeInt32();
			case "int8range" -> new DbDataType.RangeInt64();
			case "numrange" -> new DbDataType.RangeDecimal();
			case "_int4" -> new DbDataType.ArrayInt32();
			case "_int8" -> new DbDataType.ArrayInt64();
			case "_numeric" -> new DbDataType.ArrayDecimal();
			case "_text", "_varchar" -> new DbDataType.ArrayStr();
			case "interval" -> new DbDataType.Interval();
			default -> new DbDataType.Other(name);
		};
	}

	// The server's text of a value as the case the host converts its type to.
	private static DbValue value(String type, String text, int column) {
		if (type.equals("timestamptz")) {
			throw deserializing(column,
					"cannot convert between the Rust type `core::option::Option<chrono::naive::datetime::NaiveDateTime>`"
							+ " and the Postgres type `timestamptz`");
		}
		if (text == null) {
			return new DbValue.DbNull();
		}
		switch (type) {
			case "bool":
				return new DbValue.Boolean(text.equals("t"));
			case "int2":
				return new DbValue.Int16(Short.parseShort(text));
			case "int4":
				return new DbValue.Int32(Integer.parseInt(text));
			case "int8":
				return new DbValue.Int64(Long.parseLong(text));
			case "float4":
				return new DbValue.Floating32(Float.parseFloat(text));
			case "float8":
				return new DbValue.Floating64(Double.parseDouble(text));
			case "text", "varchar", "bpchar", "unknown":
				return new DbValue.Str(text);
			case "bytea": {
				byte[] octets = new byte[(text.length() - 2) / 2];
				for (int i = 0; i < octets.length; i++) {
					octets[i] = (byte) Integer.parseInt(text.substring(2 + 2 * i, 4 + 2 * i), 16);
				}
				return new DbValue.Binary(octets);
			}
			case "date": {
				if (text.endsWith("infinity")) {
					throw deserializing(column, "value too large to decode");
				}
				LocalDate date = Moment.parse(text).local().toLocalDate();
				return new DbValue.Date(
						new Ymd(date.getYear(), (byte) date.getMonthValue(), (byte) date.getDayOfMonth()));
			}
			case "time": {
				LocalTime time = LocalTime.parse(text);
				return new DbValue.Time(new Hms((byte) time.getHour(), (byte) time.getMinute(), (byte) time.getSecond(),
						time.getNano()));
			}
			case "timestamp": {
				if (text.endsWith("infinity")) {
					throw deserializing(column, "value too large to decode");
				}
				LocalDateTime at = Moment.parse(text).local();
				return new DbValue.Datetime(
						new YmdHms(at.getYear(), (byte) at.getMonthValue(), (byte) at.getDayOfMonth(),
								(byte) at.getHour(), (byte) at.getMinute(), (byte) at.getSecond(), at.getNano()));
			}
			case "uuid":
				return new DbValue.Uuid(text);
			case "jsonb":
				return new DbValue.Jsonb(text.getBytes(StandardCharsets.UTF_8));
			case "numeric":
				if (text.equals("NaN")) {
					throw deserializing(column, "Error while converting to NaN");
				}
				return new DbValue.Decimal(text);
			case "int4range":
				return new DbValue.RangeInt32(
						new Range32(bound(text, true).map(b -> new Bound32(Integer.parseInt(b), kind(text, true))),
								bound(text, false).map(b -> new Bound32(Integer.parseInt(b), kind(text, false)))));
			case "int8range":
				return new DbValue.RangeInt64(
						new Range64(bound(text, true).map(b -> new Bound64(Long.parseLong(b), kind(text, true))),
								bound(text, false).map(b -> new Bound64(Long.parseLong(b), kind(text, false)))));
			case "numrange":
				return new DbValue.RangeDecimal(
						new RangeDecimals(bound(text, true).map(b -> new BoundDecimal(b, kind(text, true))),
								bound(text, false).map(b -> new BoundDecimal(b, kind(text, false)))));
			case "_int4":
				return new DbValue.ArrayInt32(elements(text).stream().map(e -> e.map(Integer::valueOf)).toList());
			case "_int8":
				return new DbValue.ArrayInt64(elements(text).stream().map(e -> e.map(Long::valueOf)).toList());
			case "_numeric":
				return new DbValue.ArrayDecimal(elements(text));
			case "_text", "_varchar":
				return new DbValue.ArrayStr(elements(text));
			case "interval":
				return interval(text);
			default:
				// The octets of the binary form: its text for the types whose binary form
				// is
				// the text, and for the others no reading of the stand-in's.
				return new DbValue.Unsupported(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static WitException deserializing(int column, String cause) {
		return new WitException("query",
				new Error.QueryFailed(
						new QueryError.Text("Error::QueryFailed(QueryError::Text(\"error deserializing column " + column
								+ "\\n\\nCaused by:\\n    " + cause + "\"))")));
	}

	// A bound of a range in the server's text; none for an infinite one or an empty
	// range.
	private static Optional<String> bound(String range, boolean lower) {
		if (range.equals("empty")) {
			return Optional.empty();
		}
		int comma = range.indexOf(',');
		String bound = lower ? range.substring(1, comma) : range.substring(comma + 1, range.length() - 1);
		return bound.isEmpty() ? Optional.empty() : Optional.of(bound);
	}

	private static RangeBoundKind kind(String range, boolean lower) {
		char edge = lower ? range.charAt(0) : range.charAt(range.length() - 1);
		return edge == '[' || edge == ']' ? RangeBoundKind.INCLUSIVE : RangeBoundKind.EXCLUSIVE;
	}

	// The elements of an array of one dimension in the server's text.
	private static List<Optional<String>> elements(String array) {
		List<Optional<String>> elements = new ArrayList<>();
		int i = 1;
		while (i < array.length() - 1) {
			StringBuilder element = new StringBuilder();
			boolean quoted = array.charAt(i) == '"';
			if (quoted) {
				for (i++; array.charAt(i) != '"'; i++) {
					if (array.charAt(i) == '\\') {
						i++;
					}
					element.append(array.charAt(i));
				}
				i++;
			}
			else {
				while (array.charAt(i) != ',' && array.charAt(i) != '}') {
					element.append(array.charAt(i++));
				}
			}
			elements
				.add(!quoted && element.toString().equals("NULL") ? Optional.empty() : Optional.of(element.toString()));
			i++;
		}
		return elements;
	}

	// An interval in the server's own style as months, days and microseconds.
	private static DbValue interval(String text) {
		int months = 0;
		int days = 0;
		long micros = 0;
		String[] words = text.split(" ");
		for (int i = 0; i < words.length; i++) {
			if (words[i].indexOf(':') < 0) {
				int count = Integer.parseInt(words[i].replace("+", ""));
				String unit = words[++i];
				if (unit.startsWith("year")) {
					months += 12 * count;
				}
				else if (unit.startsWith("mon")) {
					months += count;
				}
				else {
					days += count;
				}
				continue;
			}
			boolean negative = words[i].startsWith("-");
			String[] fields = words[i].replace("+", "").replace("-", "").split(":");
			String[] seconds = fields[2].split("\\.");
			micros = (Long.parseLong(fields[0]) * 3600 + Long.parseLong(fields[1]) * 60 + Long.parseLong(seconds[0]))
					* 1_000_000L;
			if (seconds.length > 1) {
				micros += Long.parseLong((seconds[1] + "000000").substring(0, 6));
			}
			micros = negative ? -micros : micros;
		}
		return new DbValue.Interval(new Postgres.Interval(micros, days, months));
	}

	// A refusal of the server's with its fields.
	private static WitException refused(String function, SQLException ex) {
		String message = message(ex);
		String detail = detail(ex);
		String debug = "Error { kind: Db, cause: Some(DbError { severity: \"ERROR\", code: SqlState(E"
				+ ex.getSQLState() + "), message: " + quote(message) + " }) }";
		return new WitException(function, new Error.QueryFailed(new QueryError.DbError(new DbError(debug, "ERROR",
				ex.getSQLState(), message, Optional.ofNullable(detail), List.of(new Extra("routine", "stand_in"))))));
	}

	// The same handed over as text, as Rust writes the structure for debugging.
	private static String wrapped(SQLException ex) {
		String detail = detail(ex);
		return "Error::QueryFailed(QueryError::DbError(DbError { as-text: "
				+ quote("Error { kind: Db, cause: Some(DbError { severity: \"ERROR\", message: " + quote(message(ex))
						+ " }) }")
				+ ", severity: \"ERROR\", code: " + quote(ex.getSQLState()) + ", message: " + quote(message(ex))
				+ ", detail: " + (detail == null ? "None" : "Some(" + quote(detail) + ")")
				+ ", extras: [(\"routine\", \"stand_in\")] }))";
	}

	// The socket driver's text is "ERROR: message", then lines of detail.
	private static String message(SQLException ex) {
		String text = ex.getMessage();
		int end = text.indexOf("\n  ");
		String first = end < 0 ? text : text.substring(0, end);
		return first.startsWith("ERROR: ") ? first.substring(7) : first;
	}

	private static String detail(SQLException ex) {
		String text = ex.getMessage();
		int start = text.indexOf("\n  Detail: ");
		if (start < 0) {
			return null;
		}
		int end = text.indexOf("\n  ", start + 1);
		return text.substring(start + 11, end < 0 ? text.length() : end);
	}

	private static String quote(String text) {
		return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
	}

}
