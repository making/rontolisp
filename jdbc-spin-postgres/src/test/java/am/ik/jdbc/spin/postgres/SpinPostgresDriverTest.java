package am.ik.jdbc.spin.postgres;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import am.ik.jdbc.postgresql.PostgresDriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The driver under a JDK, its host a stand-in ({@link SocketPostgres}) that answers
 * {@code spin:postgres/postgres} over a socket to a PostgreSQL 17 in Docker: what the
 * driver makes of JDBC reaches a real server, through the interface and the host's rules
 * as they were measured on Spin. Where the JDBC specification leaves an answer open the
 * same statements are asked through {@code jdbc-postgresql} beside it, and each
 * difference is a line of {@link #whatAValueOfEachTypeReadsAsBesideTheSocketDriver()}.
 */
@Testcontainers(disabledWithoutDocker = true)
class SpinPostgresDriverTest {

	@Container
	private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
			DockerImageName.parse("postgres:17-alpine"))
		.withExposedPorts(5432)
		.withEnv("POSTGRES_PASSWORD", "p@ss:w/rd")
		// Twice: once for the server that runs the init script, once for the one that
		// stays.
		.waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));

	private static TimeZone zone;

	// The wall of the program is not the server's (UTC), so a value that crosses it
	// shows.
	@BeforeAll
	static void aWallOfItsOwn() {
		zone = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
		// The drivers registered in the order of the class path, as a program's first
		// call of the driver manager registers them, whichever driver a test makes
		// first.
		DriverManager.drivers().count();
	}

	@AfterAll
	static void theWallAsItWas() {
		TimeZone.setDefault(zone);
	}

	private static String url() {
		return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/postgres";
	}

	private static Properties login() {
		Properties info = new Properties();
		info.setProperty("user", "postgres");
		info.setProperty("password", "p@ss:w/rd");
		return info;
	}

	private static Connection connect() throws SQLException {
		return new SpinPostgresDriver().connect(url() + "?sslmode=disable", login());
	}

	private static String one(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
			assertThat(rows.next()).isTrue();
			return rows.getString(1);
		}
	}

	@Test
	void aProgramReadsAndWritesThroughTheHost() throws Exception {
		try (Connection connection = DriverManager.getConnection(url() + "?sslmode=disable&ApplicationName=notes",
				login()); Statement statement = connection.createStatement()) {
			// The driver manager's first driver that connects is this one: the host is
			// there.
			assertThat(connection).isInstanceOf(SpinPostgresConnection.class);
			assertThat(SocketPostgres.lastAddress).startsWith("postgres://postgres:p%40ss%3Aw%2Frd@")
				.endsWith("/postgres?sslmode=disable&application_name=notes");
			assertThat(connection.getClientInfo("ApplicationName")).isEqualTo("notes");
			// A statement that answers no rows is asked for its count, by its words.
			assertThat(statement.execute("create table note (id bigint generated always as identity primary key,"
					+ " title text not null check (title <> ''), added date not null default current_date)"))
				.isFalse();
			assertThat(statement.getUpdateCount()).isZero();
			assertThat(statement.executeUpdate("insert into note (title) values ('one'), ('two')")).isEqualTo(2);
			try (PreparedStatement insert = connection.prepareStatement("insert into note (title, added) values (?, ?)",
					Statement.RETURN_GENERATED_KEYS)) {
				// A statement that answers no rows has no columns to describe.
				assertThat(insert.getMetaData()).isNull();
				assertThat(insert.getParameterMetaData().getParameterTypeName(2)).isEqualTo("date");
				insert.setString(1, "お茶を淹れる🍵");
				insert.setObject(2, LocalDate.of(2026, 11, 25));
				assertThat(insert.executeUpdate()).isEqualTo(1);
				try (ResultSet keys = insert.getGeneratedKeys()) {
					assertThat(keys.next()).isTrue();
					assertThat(keys.getLong("id")).isEqualTo(3);
					assertThat(keys.getString("title")).isEqualTo("お茶を淹れる🍵");
				}
			}
			// A bigint compared with setInt: the parameter is an int4 by its cast, as it
			// is by its declared type through a socket.
			try (PreparedStatement select = connection
				.prepareStatement("select id, title, added from note where id >= ? and title <> ? order by id;")) {
				// Described before it runs: the columns, and the parameters as the server
				// reads them -- by where they stand, then by the type each setter casts
				// to.
				ResultSetMetaData before = select.getMetaData();
				assertThat(before.getColumnCount()).isEqualTo(3);
				assertThat(before.getColumnLabel(2)).isEqualTo("title");
				assertThat(before.getColumnType(1)).isEqualTo(Types.BIGINT);
				ParameterMetaData parameters = select.getParameterMetaData();
				assertThat(parameters.getParameterCount()).isEqualTo(2);
				assertThat(parameters.getParameterTypeName(1)).isEqualTo("int8");
				assertThat(parameters.getParameterType(2)).isEqualTo(Types.VARCHAR);
				assertThat(parameters.getParameterClassName(1)).isEqualTo("java.lang.Long");
				select.setInt(1, 2);
				select.setString(2, "none");
				assertThat(select.getParameterMetaData().getParameterTypeName(1)).isEqualTo("int4");
				assertThat(one(connection, "select count(*) from pg_prepared_statements where from_sql"))
					.isEqualTo("0");
				assertThat(select.execute()).isTrue();
				try (ResultSet rows = select.getResultSet()) {
					ResultSetMetaData described = rows.getMetaData();
					assertThat(described.getColumnCount()).isEqualTo(3);
					assertThat(described.getColumnLabel(3)).isEqualTo("added");
					assertThat(described.getColumnType(1)).isEqualTo(Types.BIGINT);
					assertThat(described.getColumnTypeName(3)).isEqualTo("date");
					assertThat(rows.next()).isTrue();
					assertThat(rows.getInt(1)).isEqualTo(2);
					assertThat(rows.getString("TITLE")).isEqualTo("two");
					assertThat(rows.next()).isTrue();
					assertThat(rows.getObject("added", LocalDate.class)).isEqualTo(LocalDate.of(2026, 11, 25));
					assertThat(rows.getDate(3)).isEqualTo(Date.valueOf("2026-11-25"));
					assertThat(rows.next()).isFalse();
				}
				assertThat(select.getMoreResults()).isFalse();
				assertThat(select.getUpdateCount()).isEqualTo(-1);
			}
			// No row: the columns are still told. The most rows are cut here.
			try (ResultSet none = statement.executeQuery("select id, title from note where false")) {
				assertThat(none.next()).isFalse();
				assertThat(none.getMetaData().getColumnCount()).isEqualTo(2);
			}
			statement.setMaxRows(2);
			try (ResultSet two = statement.executeQuery("select id from note order by id")) {
				assertThat(two.next() && two.next()).isTrue();
				assertThat(two.next()).isFalse();
			}
			statement.setMaxRows(0);
			// A result set that scrolls, of a statement made for one.
			try (Statement scrolling = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
					ResultSet.CONCUR_READ_ONLY);
					ResultSet rows = scrolling.executeQuery("select id from note order by id")) {
				assertThat(rows.last()).isTrue();
				assertThat(rows.getRow()).isEqualTo(3);
				assertThat(rows.absolute(1)).isTrue();
				assertThat(rows.getLong(1)).isEqualTo(1);
				assertThat(rows.previous()).isFalse();
			}
			assertThatThrownBy(() -> statement.executeQuery("select id from note").previous())
				.hasMessageContaining("forward only");
			// The rows a statement returns are answered by execute, and counted by
			// executeUpdate.
			assertThat(statement.execute("update note set title = 'ONE' where id = 1 returning title")).isTrue();
			assertThat(statement.getResultSet().next()).isTrue();
			assertThat(statement.executeUpdate("delete from note where id = 1 returning id")).isEqualTo(1);
			assertThat(statement.executeUpdate("delete from note", Statement.RETURN_GENERATED_KEYS)).isEqualTo(2);
			try (ResultSet deleted = statement.getGeneratedKeys()) {
				assertThat(deleted.next() && deleted.next()).isTrue();
				assertThat(deleted.getLong("id")).isEqualTo(3);
			}
			assertThatThrownBy(() -> statement.executeQuery("delete from note")).hasMessageContaining("no rows");
			// What the driver tells of the database and of itself.
			DatabaseMetaData facts = connection.getMetaData();
			assertThat(facts.getDatabaseProductName()).isEqualTo("PostgreSQL");
			assertThat(facts.getDatabaseMajorVersion()).isEqualTo(17);
			assertThat(facts.getDriverName()).isEqualTo("jdbc-spin-postgres");
			assertThat(facts.getUserName()).isEqualTo("postgres");
			assertThat(facts.getURL()).startsWith("jdbc:postgresql://");
			assertThat(connection.getCatalog()).isEqualTo("postgres");
			assertThat(connection.getSchema()).isEqualTo("public");
			assertThat(connection.isValid(1)).isTrue();
			statement.execute("drop table note");
		}
	}

	@Test
	void everyTypeIsWrittenByItsSetterAndReadBack() throws Exception {
		try (Connection connection = connect();
				PreparedStatement select = connection
					.prepareStatement("select ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?")) {
			select.setBoolean(1, true);
			select.setShort(2, (short) -7);
			select.setInt(3, 42);
			select.setLong(4, 9_000_000_000L);
			select.setFloat(5, 1.5f);
			select.setDouble(6, 2.5e300);
			select.setBigDecimal(7, new BigDecimal("12.50"));
			select.setString(8, "a'b\"c\\d");
			select.setBytes(9, new byte[] { 0, 1, -1 });
			select.setDate(10, Date.valueOf("2026-11-25"));
			select.setTime(11, new Time(Time.valueOf("08:06:59").getTime() + 500));
			select.setTimestamp(12, Timestamp.valueOf("2026-11-25 08:06:00.25"));
			select.setObject(13, UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"));
			select.setObject(14, LocalDateTime.of(2026, 11, 25, 8, 6, 0, 250_000_000));
			select.setObject(15, LocalTime.of(8, 6, 59));
			select.setNull(16, Types.INTEGER);
			select.setObject(17, null);
			try (ResultSet row = select.executeQuery()) {
				ResultSetMetaData described = row.getMetaData();
				String[] names = { "bool", "int2", "int4", "int8", "float4", "float8", "numeric", "text", "bytea",
						"date", "time", "timestamp", "uuid", "timestamp", "time", "int4", "text" };
				for (int i = 0; i < names.length; i++) {
					assertThat(described.getColumnTypeName(i + 1)).as("parameter " + (i + 1)).isEqualTo(names[i]);
				}
				assertThat(row.next()).isTrue();
				assertThat(row.getBoolean(1)).isTrue();
				assertThat(row.getShort(2)).isEqualTo((short) -7);
				assertThat(row.getInt(3)).isEqualTo(42);
				assertThat(row.getLong(4)).isEqualTo(9_000_000_000L);
				assertThat(row.getFloat(5)).isEqualTo(1.5f);
				assertThat(row.getDouble(6)).isEqualTo(2.5e300);
				assertThat(row.getBigDecimal(7)).isEqualTo(new BigDecimal("12.50"));
				assertThat(row.getString(8)).isEqualTo("a'b\"c\\d");
				assertThat(row.getBytes(9)).containsExactly(0, 1, -1);
				assertThat(row.getDate(10)).isEqualTo(Date.valueOf("2026-11-25"));
				assertThat(row.getTime(11).getTime()).isEqualTo(Time.valueOf("08:06:59").getTime() + 500);
				assertThat(row.getTimestamp(12)).isEqualTo(Timestamp.valueOf("2026-11-25 08:06:00.25"));
				assertThat(row.getObject(13)).isEqualTo(UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"));
				assertThat(row.getObject(14, LocalDateTime.class))
					.isEqualTo(LocalDateTime.of(2026, 11, 25, 8, 6, 0, 250_000_000));
				assertThat(row.getObject(15, LocalTime.class)).isEqualTo(LocalTime.of(8, 6, 59));
				assertThat(row.getInt(16)).isZero();
				assertThat(row.wasNull()).isTrue();
				assertThat(row.getObject(17)).isNull();
				// A value read as another class goes through the server's text of it.
				assertThat(row.getString(1)).isEqualTo("t");
				assertThat(row.getInt(1)).isEqualTo(1);
				assertThat(row.getLong(7)).isEqualTo(12);
				assertThat(row.getBigDecimal(3)).isEqualTo(new BigDecimal("42"));
				assertThat(row.getString(6)).isEqualTo("2.5e+300");
				assertThat(row.getString(9)).isEqualTo("\\x0001ff");
				assertThat(row.getString(12)).isEqualTo("2026-11-25 08:06:00.25");
				assertThatThrownBy(() -> row.getInt(4)).hasMessageContaining("out of range");
				assertThatThrownBy(() -> row.getInt(8)).isInstanceOf(SQLDataException.class);
			}
			// A value the host has no case for travels as text and is cast by the server:
			// an
			// instant, whatever time zone the session has. A timestamp is the wall's
			// fields.
			try (PreparedStatement instants = connection.prepareStatement(
					"select (?)::text, (?)::text, pg_typeof(?)::text, (? at time zone 'UTC'), ?::jsonb ->> 'a', ?")) {
				instants.setObject(1, OffsetDateTime.of(2026, 11, 25, 8, 6, 0, 0, ZoneOffset.ofHours(9)));
				instants.setObject(2, Instant.parse("2026-11-24T23:06:00.5Z"));
				instants.setTimestamp(3, Timestamp.valueOf("2026-11-25 08:06:00"));
				instants.setObject(4, Instant.parse("2026-11-24T23:06:00Z"), Types.TIMESTAMP_WITH_TIMEZONE);
				instants.setObject(5, "{\"a\": \"b\"}", Types.OTHER);
				instants.setObject(6, "42", Types.INTEGER);
				try (ResultSet row = instants.executeQuery()) {
					assertThat(row.next()).isTrue();
					assertThat(row.getString(1)).isEqualTo("2026-11-24 23:06:00+00");
					assertThat(row.getString(2)).isEqualTo("2026-11-24 23:06:00.5+00");
					assertThat(row.getString(3)).isEqualTo("timestamp without time zone");
					assertThat(row.getObject(4, LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026, 11, 24, 23, 6));
					assertThat(row.getString(5)).isEqualTo("b");
					assertThat(row.getObject(6)).isEqualTo(42);
					// A timestamptz the statement casts to text reads as the instant.
					assertThat(row.getObject(1, OffsetDateTime.class).toInstant())
						.isEqualTo(Instant.parse("2026-11-24T23:06:00Z"));
					assertThat(row.getTimestamp(2)).isEqualTo(Timestamp.from(Instant.parse("2026-11-24T23:06:00.5Z")));
				}
			}
			// An array of an element type the host has a case for travels as that case;
			// of any other as the texts of its elements, which the server casts.
			try (PreparedStatement arrays = connection
				.prepareStatement("select ?, ?, ?, ?, (?)::text, '{9000000000}'::int8[], '{1.5}'::float8[], 'x'")) {
				arrays.setArray(1, connection.createArrayOf("int4", new Integer[] { 1, null, 3 }));
				arrays.setArray(2, connection.createArrayOf("varchar", new String[] { "a", "b c", null, "" }));
				arrays.setObject(3, connection.createArrayOf("bigint", new Long[] { 1L }));
				arrays.setArray(4, connection.createArrayOf("numeric", new Object[] { new BigDecimal("1.50"), 2 }));
				arrays.setArray(5, connection.createArrayOf("float8", new Double[] { 1.5, null }));
				try (ResultSet row = arrays.executeQuery()) {
					assertThat(row.next()).isTrue();
					Array ints = row.getArray(1);
					assertThat(ints.getBaseTypeName()).isEqualTo("int4");
					assertThat(ints.getBaseType()).isEqualTo(Types.INTEGER);
					assertThat((Object[]) ints.getArray()).isInstanceOf(Integer[].class).containsExactly(1, null, 3);
					assertThat((Object[]) ints.getArray(2, 5)).containsExactly(null, 3);
					try (ResultSet elements = ints.getResultSet(3, 1)) {
						assertThat(elements.next()).isTrue();
						assertThat(elements.getInt("INDEX")).isEqualTo(3);
						assertThat(elements.getObject("VALUE")).isEqualTo(3);
						assertThat(elements.next()).isFalse();
					}
					ints.free();
					assertThatThrownBy(ints::getArray).isInstanceOf(SQLException.class);
					assertThat((Object[]) row.getObject(2, Array.class).getArray()).containsExactly("a", "b c", null,
							"");
					assertThat(row.getString(2)).isEqualTo("{a,\"b c\",NULL,\"\"}");
					assertThat((Object[]) row.getArray(3).getArray()).containsExactly(1L);
					assertThat((Object[]) row.getArray(4).getArray()).containsExactly(new BigDecimal("1.50"),
							new BigDecimal("2"));
					assertThat(row.getString(5)).isEqualTo("{1.5,NULL}");
					assertThat(row.getArray(6).getBaseTypeName()).isEqualTo("int8");
					// The host reads no other array, nor an array's text.
					assertThatThrownBy(() -> row.getArray(7)).isInstanceOf(SQLFeatureNotSupportedException.class)
						.hasMessageContaining("::text[]");
					assertThatThrownBy(() -> row.getArray(8)).isInstanceOf(SQLDataException.class);
				}
			}
			assertThatThrownBy(() -> select.setObject(1, new URL("http://example.com")))
				.isInstanceOf(SQLFeatureNotSupportedException.class);
			select.clearParameters();
			assertThatThrownBy(select::executeQuery).hasMessageContaining("parameter 1 has no value");
		}
	}

	@Test
	void whatAValueOfEachTypeReadsAsBesideTheSocketDriver() throws Exception {
		String[] literals = { "true", "'7'::smallint", "42", "9000000000", "1.5::real", "2.5::float8", "1e-5::float8",
				"1e15::float8", "123456.7::real", "1e7::real", "12.50::numeric(6,2)", "'a'::varchar(3)", "'b'::text",
				"'c'::char(2)", "'\\x0102'::bytea", "date '2026-11-22'", "date '0044-03-15 BC'", "time '08:06:59.5'",
				"timestamp '2026-11-22 08:06:00.25'", "'0f8fad5b-d9cb-469f-a165-70867728950e'::uuid",
				"'{\"a\": 1}'::jsonb", "'{\"a\": 1}'::json", "'{1,2,NULL}'::int[]", "'{9000000000}'::bigint[]",
				"'{1.5,NULL}'::numeric[]", "'{a,NULL,\"b c\",\"\",\"NULL\",\"q\\\"q\",\"b\\\\s\",\"{}\"}'::text[]",
				"'[1,5)'::int4range", "'[1,)'::int8range", "'(1.5,2.5]'::numrange", "interval '1 day'",
				"interval '1 year 2 mons 3 days 04:05:06.789'", "interval '-1 year -2 mons +3 days -04:05:06'",
				"interval '0'", "interval '-1 mons +1 day 00:00:01'", "interval '1 mon -1 day 1 sec'",
				"interval '-13 mons'", "interval '100:00:00.000001'", "null", "'x'::name", "''::xml" };
		List<String> differences = new ArrayList<>();
		String sql = "select " + String.join(", ", literals);
		try (Connection here = connect();
				Connection there = new PostgresDriver().connect(url(), login());
				Statement mine = here.createStatement();
				Statement theirs = there.createStatement();
				ResultSet a = mine.executeQuery(sql);
				ResultSet b = theirs.executeQuery(sql)) {
			assertThat(a.next() && b.next()).isTrue();
			ResultSetMetaData described = a.getMetaData();
			ResultSetMetaData theirDescription = b.getMetaData();
			for (int column = 1; column <= literals.length; column++) {
				String what = literals[column - 1];
				compare(differences, what + " text", a.getString(column), b.getString(column));
				compare(differences, what + " class", className(a.getObject(column)), className(b.getObject(column)));
				compare(differences, what + " type", described.getColumnType(column),
						theirDescription.getColumnType(column));
				compare(differences, what + " type name", described.getColumnTypeName(column),
						theirDescription.getColumnTypeName(column));
				compare(differences, what + " label", described.getColumnLabel(column),
						theirDescription.getColumnLabel(column));
			}
		}
		// The host tells a varchar, a char and a text apart no more than their text.
		// (Spin also hands a jsonb over as it writes one itself, {"a":1}: the stand-in
		// keeps the server's text.)
		assertThat(differences).containsExactly("'a'::varchar(3) type name: text / varchar",
				"'c'::char(2) type: 12 / 1", "'c'::char(2) type name: text / bpchar");
	}

	/**
	 * Every fact of {@link DatabaseMetaData} and the catalog's answers over one schema,
	 * beside the socket driver's: the queries are written in both drivers, and this is
	 * what holds them together.
	 */
	@Test
	void theCatalogBesideTheSocketDriver() throws Exception {
		try (Connection socket = new PostgresDriver().connect(url(), login());
				Statement statement = socket.createStatement()) {
			statement.execute(
					"""
							create schema meta;
							create domain meta.positive as numeric(6, 2) check (value > 0);
							create table meta.authors (id serial primary key, name varchar(40) not null unique, born date,
								note text default 'none');
							comment on table meta.authors is 'who wrote';
							comment on column meta.authors.name is 'as printed';
							create table meta.books (id bigint generated always as identity primary key,
								author int references meta.authors on delete cascade, isbn char(13) constraint books_isbn unique,
								price meta.positive, title text not null, tags text[], stamp timestamptz(3), clock time,
								flag boolean, raw bytea, twice int generated always as (author * 2) stored);
							create index books_price on meta.books (price desc nulls last) where price > 1;
							create index books_title on meta.books (lower(title));
							create table meta.shelves (author int, book bigint, primary key (book, author),
								foreign key (book) references meta.books on update restrict,
								foreign key (author) references meta.authors deferrable initially deferred);
							create view meta.cheap as select title, price from meta.books where price < 10;
							create sequence meta.counter;
							""");
		}
		List<String> differences = new ArrayList<>();
		try (Connection here = connect();
				Connection there = new PostgresDriver().connect(url() + "?sslmode=disable", login())) {
			DatabaseMetaData mine = here.getMetaData();
			DatabaseMetaData theirs = there.getMetaData();
			assertThat(mine.getConnection()).isSameAs(here);
			// Every fact asked without an argument, and with each constant it takes.
			for (Method method : DatabaseMetaData.class.getMethods()) {
				Class<?> answer = method.getReturnType();
				if (answer != boolean.class && answer != int.class && answer != String.class) {
					continue;
				}
				List<Object[]> arguments = new ArrayList<>();
				if (method.getParameterCount() == 0) {
					arguments.add(new Object[0]);
				}
				else if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == int.class) {
					int[] constants = method.getName().contains("Holdability")
							? new int[] { ResultSet.HOLD_CURSORS_OVER_COMMIT, ResultSet.CLOSE_CURSORS_AT_COMMIT }
							: method.getName().contains("Isolation")
									? new int[] { Connection.TRANSACTION_READ_UNCOMMITTED,
											Connection.TRANSACTION_SERIALIZABLE }
									: new int[] { ResultSet.TYPE_FORWARD_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE };
					for (int constant : constants) {
						arguments.add(new Object[] { constant });
					}
				}
				for (Object[] given : arguments) {
					compare(differences, method.getName() + (given.length == 0 ? "" : "(" + given[0] + ")"),
							ask(method, mine, given), ask(method, theirs, given));
				}
			}
			compareRows(differences, "getCatalogs", metadata -> metadata.getCatalogs(), mine, theirs);
			compareRows(differences, "getSchemas", metadata -> metadata.getSchemas(), mine, theirs);
			compareRows(differences, "getSchemas m_t%", metadata -> metadata.getSchemas(null, "m_t%"), mine, theirs);
			compareRows(differences, "getTableTypes", metadata -> metadata.getTableTypes(), mine, theirs);
			compareRows(differences, "getTables", metadata -> metadata.getTables(null, "meta", "%", null), mine,
					theirs);
			compareRows(differences, "getTables TABLE",
					metadata -> metadata.getTables(null, "met_", "b%", new String[] { "TABLE" }), mine, theirs);
			compareRows(differences, "getColumns", metadata -> metadata.getColumns(null, "meta", "%", "%"), mine,
					theirs);
			compareRows(differences, "getColumns t%", metadata -> metadata.getColumns(null, "meta", "books", "t%"),
					mine, theirs);
			compareRows(differences, "getPrimaryKeys", metadata -> metadata.getPrimaryKeys(null, "meta", "shelves"),
					mine, theirs);
			compareRows(differences, "getImportedKeys", metadata -> metadata.getImportedKeys(null, "meta", "shelves"),
					mine, theirs);
			compareRows(differences, "getExportedKeys", metadata -> metadata.getExportedKeys(null, "meta", "authors"),
					mine, theirs);
			compareRows(differences, "getCrossReference",
					metadata -> metadata.getCrossReference(null, "meta", "books", null, "meta", "shelves"), mine,
					theirs);
			compareRows(differences, "getIndexInfo",
					metadata -> metadata.getIndexInfo(null, "meta", "books", false, false), mine, theirs);
			compareRows(differences, "getIndexInfo unique",
					metadata -> metadata.getIndexInfo(null, "meta", "authors", true, true), mine, theirs);
			compareRows(differences, "getClientInfoProperties", metadata -> metadata.getClientInfoProperties(), mine,
					theirs);
			// A catalog is the session's database; a question of the catalog is a
			// statement of the program's transaction.
			try (ResultSet none = mine.getTables("template1", "meta", "%", null)) {
				assertThat(none.next()).isFalse();
			}
			here.setAutoCommit(false);
			try (Statement statement = here.createStatement()) {
				statement.execute("create table meta.uncommitted (id int)");
				try (ResultSet tables = mine.getTables(null, "meta", "uncommitted", null)) {
					assertThat(tables.next()).isTrue();
				}
				here.rollback();
			}
		}
		// The driver's own name, and what a statement of the host answers: one result.
		assertThat(differences).containsExactlyInAnyOrder("getDriverName: jdbc-spin-postgres / jdbc-postgresql",
				"supportsMultipleResultSets: false / true", "supportsMultipleOpenResults: false / true");
	}

	private static Object ask(Method method, DatabaseMetaData metadata, Object[] arguments) {
		try {
			return method.invoke(metadata, arguments);
		}
		catch (ReflectiveOperationException ex) {
			return "refused: " + ex.getCause();
		}
	}

	private interface Catalog {

		ResultSet ask(DatabaseMetaData metadata) throws SQLException;

	}

	// The rows of two answers of the catalog, a difference a line: the labels, the
	// count, then each column's pairs of values that differ.
	private static void compareRows(List<String> differences, String what, Catalog question, DatabaseMetaData here,
			DatabaseMetaData there) throws SQLException {
		List<List<String>> mine = rows(question.ask(here));
		List<List<String>> theirs = rows(question.ask(there));
		compare(differences, what + " labels", mine.get(0), theirs.get(0));
		compare(differences, what + " rows", mine.size() - 1, theirs.size() - 1);
		for (int column = 0; column < theirs.get(0).size(); column++) {
			int at = mine.get(0).indexOf(theirs.get(0).get(column));
			Set<String> pairs = new LinkedHashSet<>();
			for (int row = 1; row < Math.min(mine.size(), theirs.size()); row++) {
				String ours = at < 0 ? "(none)" : mine.get(row).get(at);
				String other = theirs.get(row).get(column);
				if (ours == null ? other != null : !ours.equals(other)) {
					pairs.add(ours + " / " + other);
				}
			}
			if (!pairs.isEmpty()) {
				differences.add(what + " " + theirs.get(0).get(column) + ": " + String.join(", ", pairs));
			}
		}
	}

	private static List<List<String>> rows(ResultSet rows) throws SQLException {
		try (rows) {
			List<List<String>> lines = new ArrayList<>();
			ResultSetMetaData described = rows.getMetaData();
			List<String> labels = new ArrayList<>();
			for (int column = 1; column <= described.getColumnCount(); column++) {
				labels.add(described.getColumnLabel(column));
			}
			lines.add(labels);
			while (rows.next()) {
				List<String> line = new ArrayList<>();
				for (int column = 1; column <= labels.size(); column++) {
					line.add(rows.getString(column));
				}
				lines.add(line);
			}
			return lines;
		}
	}

	private static void compare(List<String> differences, String what, Object mine, Object theirs) {
		if (mine == null ? theirs != null : !mine.equals(theirs)) {
			differences.add(what + ": " + mine + " / " + theirs);
		}
	}

	private static String className(Object value) {
		return value == null ? null : value.getClass().getSimpleName();
	}

	@Test
	void aTransactionIsTheStatementsTheConnectionSends() throws Exception {
		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			statement.execute("create table tx (id int primary key)");
			assertThatThrownBy(connection::commit).hasMessageContaining("commits each statement");
			connection.setAutoCommit(false);
			statement.executeUpdate("insert into tx values (1)");
			try (Connection other = connect()) {
				assertThat(one(other, "select count(*) from tx")).isEqualTo("0");
				connection.commit();
				assertThat(one(other, "select count(*) from tx")).isEqualTo("1");
			}
			statement.executeUpdate("insert into tx values (2)");
			connection.rollback();
			assertThat(one(connection, "select count(*) from tx")).isEqualTo("1");
			// A savepoint undoes what came after it, a refusal included.
			statement.executeUpdate("insert into tx values (3)");
			Savepoint savepoint = connection.setSavepoint();
			assertThatThrownBy(() -> statement.executeUpdate("insert into tx values (3)"))
				.isInstanceOf(SQLIntegrityConstraintViolationException.class);
			assertThatThrownBy(() -> statement.executeQuery("select 1")).hasMessageContaining("aborted");
			connection.rollback(savepoint);
			statement.executeUpdate("insert into tx values (4)");
			connection.commit();
			assertThat(one(connection, "select count(*) from tx")).isEqualTo("3");
			connection.commit();
			// A transaction the server aborted is rolled back by its commit, which
			// throws; a
			// refusal of the host's own leaves the transaction as it was.
			statement.executeUpdate("insert into tx values (5)");
			assertThatThrownBy(() -> statement.executeUpdate("insert into tx values (5)"))
				.isInstanceOf(SQLIntegrityConstraintViolationException.class);
			assertThatThrownBy(connection::commit).isInstanceOf(SQLTransactionRollbackException.class);
			statement.executeUpdate("insert into tx values (6)");
			assertThatThrownBy(() -> statement.executeQuery("select now()"))
				.isInstanceOf(SQLFeatureNotSupportedException.class);
			connection.commit();
			assertThat(one(connection, "select string_agg(id::text, ',' order by id) from tx")).isEqualTo("1,3,4,6");
			connection.commit();
			assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
			connection.rollback();
			connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
			assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
			assertThat(one(connection, "select count(*) from tx")).isEqualTo("4");
			assertThatThrownBy(() -> connection.setReadOnly(true)).hasMessageContaining("inside a transaction");
			connection.rollback();
			connection.setReadOnly(true);
			assertThatThrownBy(() -> statement.executeUpdate("insert into tx values (7)"))
				.hasMessageContaining("read-only");
			connection.rollback();
			connection.setReadOnly(false);
			// Left open: closing rolls it back, since the host hands the session out
			// again
			// as it is.
			statement.executeUpdate("insert into tx values (8)");
		}
		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			assertThat(one(connection, "select count(*) from tx where id = 8")).isEqualTo("0");
			assertThat(one(connection, "select txid_current_if_assigned() is null")).isEqualTo("t");
			// One the program began itself is ended the same way.
			statement.execute("begin");
			statement.executeUpdate("insert into tx values (9)");
		}
		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			assertThat(one(connection, "select count(*) from tx where id = 9")).isEqualTo("0");
			// A batch of a prepared statement is one transaction: a refusal undoes its
			// rows.
			try (PreparedStatement insert = connection.prepareStatement("insert into tx values (?)",
					Statement.RETURN_GENERATED_KEYS)) {
				for (int id : new int[] { 10, 11 }) {
					insert.setInt(1, id);
					insert.addBatch();
				}
				assertThat(insert.executeBatch()).containsExactly(1, 1);
				try (ResultSet keys = insert.getGeneratedKeys()) {
					assertThat(keys.next() && keys.next()).isTrue();
					assertThat(keys.getInt(1)).isEqualTo(11);
				}
				for (int id : new int[] { 12, 10, 13 }) {
					insert.setInt(1, id);
					insert.addBatch();
				}
				assertThatThrownBy(insert::executeBatch).isInstanceOfSatisfying(BatchUpdateException.class, refused -> {
					assertThat(refused.getSQLState()).isEqualTo("23505");
					assertThat(refused.getUpdateCounts()).containsOnly(Statement.EXECUTE_FAILED).hasSize(3);
				});
			}
			assertThat(one(connection, "select count(*) from tx where id >= 10")).isEqualTo("2");
			// A statement's batch is a command at a time.
			statement.addBatch("insert into tx values (14)");
			statement.addBatch("insert into tx values (14)");
			statement.addBatch("delete from tx where id >= 10");
			assertThatThrownBy(statement::executeBatch).isInstanceOfSatisfying(BatchUpdateException.class,
					refused -> assertThat(refused.getUpdateCounts()).containsExactly(1, Statement.EXECUTE_FAILED, 3));
			statement.execute("drop table tx");
		}
	}

	@Test
	void aRefusalHasTheServersStateAndItsClass() throws Exception {
		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			statement.execute("create table refusal (id int primary key, word text)");
			statement.executeUpdate("insert into refusal values (1, 'a')");
			// Asked for a count, the host hands the server's refusal over with its
			// fields.
			assertThatThrownBy(() -> statement.executeUpdate("insert into refusal values (1, 'b')"))
				.isInstanceOfSatisfying(SQLIntegrityConstraintViolationException.class, refused -> {
					assertThat(refused.getSQLState()).isEqualTo("23505");
					assertThat(refused.getMessage()).isEqualTo(
							"ERROR: duplicate key value violates unique constraint \"refusal_pkey\"\n  Detail: Key (id)=(1) already exists.");
				});
			// Asked for rows, as text: the same exception is read out of it.
			assertThatThrownBy(() -> statement.executeUpdate("insert into refusal values (1, 'b')",
					Statement.RETURN_GENERATED_KEYS))
				.isInstanceOfSatisfying(SQLIntegrityConstraintViolationException.class, refused -> {
					assertThat(refused.getSQLState()).isEqualTo("23505");
					assertThat(refused.getMessage()).isEqualTo(
							"ERROR: duplicate key value violates unique constraint \"refusal_pkey\"\n  Detail: Key (id)=(1) already exists.");
				});
			assertThatThrownBy(() -> statement.executeQuery("selec 1")).isInstanceOf(SQLSyntaxErrorException.class);
			assertThatThrownBy(() -> statement.executeQuery("select 1/(id - 1) from refusal")).isInstanceOfSatisfying(
					SQLDataException.class, refused -> assertThat(refused.getSQLState()).isEqualTo("22012"));
			// One command a statement: the server refuses a second.
			assertThatThrownBy(() -> statement.execute("select 1; select 2"))
				.isInstanceOf(SQLSyntaxErrorException.class)
				.hasMessageContaining("multiple commands");
			// A statement out of time is ended by the server, and the limit is the
			// statement's: the next one runs under its own.
			statement.setQueryTimeout(1);
			assertThatThrownBy(() -> statement.executeQuery("select pg_sleep(5)"))
				.isInstanceOf(SQLTimeoutException.class);
			statement.setQueryTimeout(0);
			assertThat(one(connection, "select current_setting('statement_timeout')")).isEqualTo("0");
			// What the host cannot hand over is refused by name, with the way around it.
			assertThatThrownBy(() -> statement.executeQuery("select now()"))
				.isInstanceOf(SQLFeatureNotSupportedException.class)
				.hasMessageContaining("timestamptz")
				.hasMessageContaining("AT TIME ZONE 'UTC'");
			assertThatThrownBy(() -> statement.executeQuery("select 'NaN'::numeric"))
				.isInstanceOf(SQLFeatureNotSupportedException.class);
			try (ResultSet row = statement.executeQuery("select point '(1,2)', 'happy'::text::name")) {
				assertThat(row.next()).isTrue();
				assertThatThrownBy(() -> row.getString(1)).isInstanceOf(SQLFeatureNotSupportedException.class)
					.hasMessageContaining("point")
					.hasMessageContaining("::text");
				assertThat(row.getBytes(1)).isNotEmpty();
				assertThat(row.getString(2)).isEqualTo("happy");
			}
			// What the interface has no call for.
			assertThatThrownBy(statement::cancel).isInstanceOf(SQLFeatureNotSupportedException.class);
			assertThatThrownBy(() -> connection.prepareCall("call p()"))
				.isInstanceOf(SQLFeatureNotSupportedException.class);
			try (PreparedStatement prepared = connection.prepareStatement("select ? -- the value")) {
				// A parameter with no type where the server tells none is a text.
				assertThat(prepared.getParameterMetaData().getParameterTypeName(1)).isEqualTo("text");
				assertThat(prepared.getMetaData().getColumnTypeName(1)).isEqualTo("text");
				prepared.setString(1, "x");
				assertThat(prepared.executeQuery().getMetaData().getColumnTypeName(1)).isEqualTo("text");
			}
			// The columns of rows a command writes are told by no statement the host
			// runs without running it.
			try (PreparedStatement returning = connection
				.prepareStatement("update refusal set word = ? returning id, word")) {
				assertThatThrownBy(returning::getMetaData).isInstanceOf(SQLFeatureNotSupportedException.class)
					.hasMessageContaining("RETURNING");
				returning.setString(1, "c");
				assertThat(returning.executeQuery().next()).isTrue();
				assertThat(returning.getMetaData().getColumnLabel(2)).isEqualTo("word");
			}
			statement.execute("drop table refusal");
		}
		// The host opens no connection: its words, no state of the server's.
		Properties wrong = login();
		wrong.setProperty("password", "wrong");
		assertThatThrownBy(() -> new SpinPostgresDriver().connect(url() + "?sslmode=disable", wrong))
			.isInstanceOfSatisfying(SQLNonTransientConnectionException.class, refused -> {
				assertThat(refused.getSQLState()).isEqualTo("08001");
				assertThat(refused.getMessage()).contains("password authentication failed");
			});
		// A certificate goes to the host through its connection builder.
		Properties verified = login();
		verified.setProperty("sslrootcert", "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n");
		assertThatThrownBy(() -> new SpinPostgresDriver().connect(url() + "?sslmode=verify-full", verified))
			.isInstanceOf(SQLNonTransientConnectionException.class)
			.hasMessageContaining("TLS");
		assertThat(SocketPostgres.lastCertificate).startsWith("-----BEGIN CERTIFICATE-----");
		assertThat(SocketPostgres.lastAddress).endsWith("?sslmode=require");
	}

	/**
	 * With no host of the interface -- a JDK with no provider on its path -- the driver
	 * takes no URL, and the driver manager goes on to the socket driver beside it.
	 */
	@Test
	void whereNoHostAnswersTheNextDriverConnects() throws Exception {
		Thread thread = Thread.currentThread();
		ClassLoader loader = thread.getContextClassLoader();
		thread.setContextClassLoader(new ClassLoader(loader) {
			@Override
			public Enumeration<URL> getResources(String name) throws java.io.IOException {
				return name.equals("META-INF/services/" + Postgres.class.getName()) ? Collections.emptyEnumeration()
						: super.getResources(name);
			}
		});
		try {
			assertThat(new SpinPostgresDriver().connect(url(), login())).isNull();
			assertThat(new SpinPostgresDriver().acceptsURL(url())).isTrue();
			try (Connection connection = DriverManager.getConnection(url(), login())) {
				assertThat(connection.getClass().getName()).isEqualTo("am.ik.jdbc.postgresql.PostgresConnection");
				assertThat(one(connection, "select 1")).isEqualTo("1");
			}
			// The data source names this driver: it goes on to no other.
			SpinPostgresDataSource dataSource = new SpinPostgresDataSource();
			dataSource.setUrl(url());
			dataSource.setUser("postgres");
			dataSource.setPassword("p@ss:w/rd");
			assertThatThrownBy(dataSource::getConnection).isInstanceOf(SQLException.class)
				.hasMessageContaining("No host implements spin:postgres/postgres");
		}
		finally {
			thread.setContextClassLoader(loader);
		}
		SpinPostgresDataSource dataSource = new SpinPostgresDataSource();
		dataSource.setUrl(url() + "?sslmode=disable");
		dataSource.setUser("postgres");
		dataSource.setPassword("p@ss:w/rd");
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection).isInstanceOf(SpinPostgresConnection.class);
			assertThat(one(connection, "select current_user")).isEqualTo("postgres");
		}
	}

}
