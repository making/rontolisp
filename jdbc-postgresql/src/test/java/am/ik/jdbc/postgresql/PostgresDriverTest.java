package am.ik.jdbc.postgresql;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLClientInfoException;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
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
import java.util.Calendar;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The driver against a PostgreSQL 17 in Docker, with a role for each authentication
 * method the server has without TLS. Where the JDBC specification leaves an answer open,
 * the same statements are asked through pgjdbc beside it, and each difference is a line
 * of {@link #whatAValueOfEachTypeReadsAsBesidePgjdbc()}.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresDriverTest {

	// One rule a role, so a login succeeds only through the method under test.
	private static final String HBA_CONF = """
			local   all all       trust
			host    all trustuser all trust
			host    all passuser  all password
			host    all md5user   all md5
			host    all all       all scram-sha-256
			""";

	private static final String INIT_SQL = """
			SET password_encryption = 'md5';
			CREATE USER md5user PASSWORD 'md5pw';
			SET password_encryption = 'scram-sha-256';
			CREATE USER trustuser PASSWORD 'trustpw';
			CREATE USER passuser  PASSWORD 'passpw';
			CREATE USER scramuser PASSWORD 'scrampw';
			GRANT CREATE ON SCHEMA public TO PUBLIC;
			""";

	@Container
	private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
			DockerImageName.parse("postgres:17-alpine"))
		.withExposedPorts(5432)
		.withEnv("POSTGRES_PASSWORD", "postgres")
		.withCopyToContainer(Transferable.of(HBA_CONF), "/etc/postgresql/pg_hba.conf")
		.withCopyToContainer(Transferable.of(INIT_SQL), "/docker-entrypoint-initdb.d/10-roles.sql")
		.withCommand("postgres", "-c", "hba_file=/etc/postgresql/pg_hba.conf")
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
	}

	@AfterAll
	static void theWallAsItWas() {
		TimeZone.setDefault(zone);
	}

	private static String url() {
		return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/postgres";
	}

	private static Connection connect(Driver driver, String user, String password) throws SQLException {
		Properties info = new Properties();
		info.setProperty("user", user);
		if (password != null) {
			info.setProperty("password", password);
		}
		return driver.connect(url(), info);
	}

	private static Connection connect(String user, String password) throws SQLException {
		return connect(new PostgresDriver(), user, password);
	}

	private static Connection connect() throws SQLException {
		return connect("scramuser", "scrampw");
	}

	private static String one(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
			assertThat(rows.next()).isTrue();
			return rows.getString(1);
		}
	}

	// Logins.

	@Test
	void aLoginByEachMethod() throws Exception {
		String[][] logins = { { "trustuser", null }, { "passuser", "passpw" }, { "md5user", "md5pw" },
				{ "scramuser", "scrampw" } };
		for (String[] login : logins) {
			try (Connection connection = connect(login[0], login[1])) {
				assertThat(one(connection, "select current_user")).isEqualTo(login[0]);
				assertThat(connection.isValid(5)).isTrue();
			}
		}
		for (String user : new String[] { "passuser", "md5user", "scramuser" }) {
			assertThatThrownBy(() -> connect(user, "wrong")).as(user)
				.isInstanceOf(SQLInvalidAuthorizationSpecException.class)
				.hasFieldOrPropertyWithValue("SQLState", "28P01");
			assertThatThrownBy(() -> connect(user, null)).as(user)
				.isInstanceOf(SQLInvalidAuthorizationSpecException.class)
				.hasMessageContaining("password");
		}
		// A data source makes a connection with what is set on it, or with the user
		// given.
		PostgresDataSource source = new PostgresDataSource();
		source.setUrl(url());
		source.setUser("scramuser");
		source.setPassword("scrampw");
		source.setLoginTimeout(5);
		try (Connection made = source.getConnection(); Connection other = source.getConnection("md5user", "md5pw")) {
			assertThat(one(made, "select current_user")).isEqualTo("scramuser");
			assertThat(one(other, "select current_user")).isEqualTo("md5user");
		}
		source.setUrl("jdbc:other://localhost/db");
		assertThatThrownBy(source::getConnection).hasFieldOrPropertyWithValue("SQLState", "08001");
		// The driver is found through its services file, and takes its URLs alone.
		assertThat(DriverManager.drivers()).anyMatch(driver -> driver instanceof PostgresDriver);
		assertThat(new PostgresDriver().connect("jdbc:other://localhost/db", new Properties())).isNull();
		assertThatThrownBy(() -> new PostgresDriver().connect(url() + "?sslmode=require&user=trustuser", null))
			.isInstanceOf(SQLFeatureNotSupportedException.class);
		try (Connection named = new PostgresDriver()
			.connect(url() + "?user=trustuser&ApplicationName=the%20test&sslmode=prefer", null)) {
			assertThat(one(named, "show application_name")).isEqualTo("the test");
			assertThat(named.getClientInfo("ApplicationName")).isEqualTo("the test");
			named.setClientInfo("ApplicationName", "it's \\ renamed");
			assertThat(one(named, "show application_name")).isEqualTo("it's \\ renamed");
			assertThat(named.getClientInfo().getProperty("ApplicationName")).isEqualTo("it's \\ renamed");
			assertThatThrownBy(() -> named.setClientInfo("Other", "x")).isInstanceOf(SQLClientInfoException.class);
		}
	}

	@Test
	void aPasswordOutsideAsciiIsPreparedAsTheServerPreparesIt() throws Exception {
		// What the server derived its verifier from is its own preparation of each: a
		// login succeeds only where the driver's is the same.
		String[] passwords = {
				// Normalized: a full-width letter, a Roman numeral, a soft hyphen, an
				// ideographic space.
				"pａssⅨ­　word",
				// A zero-width space is in the table of spaces and in the one mapped to
				// nothing.
				"zero​width",
				// Japanese, which normalization leaves as it is.
				"パスワード",
				// Prohibited (a code point Unicode 3.2 leaves unassigned): used as given.
				"tea🍵ａ", "bell\u0007ａ",
				// A tone mark the normalization replaces before the prohibition is looked
				// at.
				"tonè" };
		try (Connection admin = connect("postgres", "postgres"); Statement statement = admin.createStatement()) {
			for (int i = 0; i < passwords.length; i++) {
				statement.execute("create user prepared" + i + " password '" + passwords[i] + "'");
			}
		}
		for (int i = 0; i < passwords.length; i++) {
			try (Connection connection = connect("prepared" + i, passwords[i])) {
				assertThat(one(connection, "select current_user")).as(passwords[i]).isEqualTo("prepared" + i);
			}
		}
		// The first one's normal form logs in too, since both sides prepare.
		try (Connection connection = connect("prepared0", "passIX word")) {
			assertThat(connection.isValid(5)).isTrue();
		}
	}

	// Values.

	@Test
	void aValueOfEachTypeIsWrittenAndRead() throws Exception {
		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			statement.execute("""
					create temporary table kinds (id int primary key, flag boolean, tiny smallint, whole integer,
						large bigint, single real, twice double precision, exact numeric(12, 3), name varchar(40),
						body text, raw bytea, day date, clock time, wall timestamp, moment timestamptz, key uuid,
						document jsonb)""");
			String body = "お茶🍵 ".repeat(4000);
			byte[] raw = new byte[20000];
			for (int i = 0; i < raw.length; i++) {
				raw[i] = (byte) i;
			}
			UUID key = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
			Timestamp wall = Timestamp.valueOf("2026-11-22 08:06:00.123456");
			try (PreparedStatement insert = connection
				.prepareStatement("insert into kinds values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
				insert.setInt(1, 1);
				insert.setBoolean(2, true);
				insert.setShort(3, (short) -7);
				insert.setInt(4, Integer.MIN_VALUE);
				insert.setLong(5, Long.MAX_VALUE);
				insert.setFloat(6, 1.5f);
				insert.setDouble(7, -2.5e-300);
				insert.setBigDecimal(8, new BigDecimal("123456789.125"));
				insert.setString(9, "it's");
				insert.setCharacterStream(10, new StringReader(body));
				insert.setBinaryStream(11, new ByteArrayInputStream(raw));
				insert.setDate(12, Date.valueOf("2026-11-22"));
				insert.setTime(13, Time.valueOf("08:06:59"));
				insert.setTimestamp(14, wall);
				insert.setTimestamp(15, wall);
				insert.setObject(16, key);
				insert.setObject(17, "{\"a\": [1, 2]}", Types.OTHER);
				assertThat(insert.executeUpdate()).isEqualTo(1);
				// The same row through setObject, and a row of NULLs.
				insert.setObject(1, 2);
				insert.setObject(2, Boolean.FALSE);
				insert.setObject(3, (short) 7);
				insert.setObject(4, 42);
				insert.setObject(5, 42L);
				insert.setObject(6, Float.NaN);
				insert.setObject(7, Double.POSITIVE_INFINITY);
				insert.setObject(8, new BigDecimal("-0.5"));
				insert.setObject(9, "");
				insert.setObject(10, body, Types.LONGVARCHAR);
				insert.setObject(11, new byte[0]);
				insert.setObject(12, LocalDate.of(-43, 3, 15));
				insert.setObject(13, LocalTime.of(23, 59, 59, 999_999_000));
				insert.setObject(14, LocalDateTime.of(1999, 12, 31, 23, 59, 59));
				insert.setObject(15, OffsetDateTime.of(1999, 12, 31, 23, 59, 59, 0, ZoneOffset.ofHours(-8)));
				insert.setObject(16, key.toString(), Types.OTHER);
				insert.setNull(17, Types.OTHER);
				assertThat(insert.executeUpdate()).isEqualTo(1);
				// A timestamp written on a calendar's wall is the same instant, eight
				// hours earlier there.
				insert.setInt(1, 4);
				Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				insert.setTimestamp(14, wall, utc);
				insert.setTimestamp(15, wall, utc);
				insert.setDate(12, Date.valueOf("2026-11-22"), utc);
				insert.setTime(13, Time.valueOf("08:06:59"), utc);
				assertThat(insert.executeUpdate()).isEqualTo(1);
				insert.clearParameters();
				insert.setInt(1, 3);
				assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class)
					.hasMessageContaining("parameter 2");
				for (int parameter = 2; parameter <= 17; parameter++) {
					insert.setObject(parameter, null);
				}
				assertThat(insert.executeUpdate()).isEqualTo(1);
			}
			try (ResultSet rows = statement.executeQuery("select * from kinds order by id")) {
				assertThat(rows.next()).isTrue();
				assertThat(rows.getBoolean("flag")).isTrue();
				assertThat(rows.getShort("tiny")).isEqualTo((short) -7);
				assertThat(rows.getInt("WHOLE")).isEqualTo(Integer.MIN_VALUE);
				assertThat(rows.getLong("large")).isEqualTo(Long.MAX_VALUE);
				assertThat(rows.getFloat("single")).isEqualTo(1.5f);
				assertThat(rows.getDouble("twice")).isEqualTo(-2.5e-300);
				assertThat(rows.getBigDecimal("exact")).isEqualTo(new BigDecimal("123456789.125"));
				assertThat(rows.getString("name")).isEqualTo("it's");
				assertThat(rows.getString("body")).isEqualTo(body);
				assertThat(rows.getBytes("raw")).isEqualTo(raw);
				assertThat(rows.getDate("day")).isEqualTo(Date.valueOf("2026-11-22"));
				assertThat(rows.getTime("clock")).isEqualTo(Time.valueOf("08:06:59"));
				// The timestamp is the same instant in both columns, whatever wall the
				// server keeps.
				assertThat(rows.getTimestamp("wall")).isEqualTo(wall);
				assertThat(rows.getTimestamp("moment")).isEqualTo(wall);
				assertThat(rows.getString("wall")).isEqualTo("2026-11-22 08:06:00.123456");
				assertThat(rows.getString("moment")).isEqualTo("2026-11-21 23:06:00.123456+00");
				assertThat(rows.getObject("moment", OffsetDateTime.class).toInstant()).isEqualTo(wall.toInstant());
				assertThat(rows.getObject("moment", Instant.class)).isEqualTo(wall.toInstant());
				assertThat(rows.getObject("wall", LocalDateTime.class)).isEqualTo(wall.toLocalDateTime());
				// On a calendar's wall: the column with no time zone is read there, the
				// instant is not.
				Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				assertThat(rows.getTimestamp("wall", utc))
					.isEqualTo(Timestamp.from(Instant.parse("2026-11-22T08:06:00.123456Z")));
				assertThat(rows.getTimestamp("moment", utc)).isEqualTo(wall);
				assertThat(rows.getDate("day", utc).getTime())
					.isEqualTo(Instant.parse("2026-11-22T00:00:00Z").toEpochMilli());
				assertThat(rows.getTime("clock", utc).getTime()).isEqualTo(Time.valueOf("17:06:59").getTime());
				assertThat(rows.getObject("key")).isEqualTo(key);
				assertThat(rows.getString("document")).isEqualTo("{\"a\": [1, 2]}");
				// A number read as another kind of number: a fraction is cut off, a value
				// out of range refused.
				assertThat(rows.getInt("exact")).isEqualTo(123456789);
				assertThat(rows.getLong("flag")).isEqualTo(1);
				assertThat(rows.getBoolean("id")).isTrue();
				assertThat(rows.getDouble("whole")).isEqualTo((double) Integer.MIN_VALUE);
				assertThatThrownBy(() -> rows.getInt("large")).isInstanceOf(SQLException.class)
					.hasFieldOrPropertyWithValue("SQLState", "22003");
				assertThatThrownBy(() -> rows.getInt("name")).isInstanceOf(SQLDataException.class);
				assertThatThrownBy(() -> rows.getString("nowhere")).hasFieldOrPropertyWithValue("SQLState", "42703");
				assertThatThrownBy(() -> rows.getString(18)).hasFieldOrPropertyWithValue("SQLState", "42703");

				assertThat(rows.next()).isTrue();
				assertThat(rows.getBoolean(2)).isFalse();
				assertThat(rows.getObject(3)).isEqualTo(7);
				assertThat(rows.getObject(5)).isEqualTo(42L);
				assertThat(rows.getFloat(6)).isNaN();
				assertThat(rows.getObject(7)).isEqualTo(Double.POSITIVE_INFINITY);
				assertThat(rows.getObject(8)).isEqualTo(new BigDecimal("-0.500"));
				assertThat(rows.getString(9)).isEmpty();
				assertThat(rows.getBytes(11)).isEmpty();
				assertThat(rows.getObject(12, LocalDate.class)).isEqualTo(LocalDate.of(-43, 3, 15));
				assertThat(rows.getString(12)).isEqualTo("0044-03-15 BC");
				assertThat(rows.getObject(13, LocalTime.class)).isEqualTo(LocalTime.of(23, 59, 59, 999_999_000));
				assertThat(rows.getObject(14)).isEqualTo(Timestamp.valueOf("1999-12-31 23:59:59"));
				assertThat(rows.getObject(15, OffsetDateTime.class).toInstant())
					.isEqualTo(Instant.parse("2000-01-01T07:59:59Z"));
				assertThat(rows.getObject(16, UUID.class)).isEqualTo(key);

				assertThat(rows.next()).isTrue();
				for (int column = 2; column <= 17; column++) {
					assertThat(rows.getObject(column)).as("column %d", column).isNull();
					assertThat(rows.wasNull()).isTrue();
				}
				assertThat(rows.getInt(4)).isZero();
				assertThat(rows.getBoolean(2)).isFalse();
				assertThat(rows.getInt(1)).isEqualTo(3);
				assertThat(rows.wasNull()).isFalse();
				assertThat(rows.next()).isTrue();
				assertThat(rows.getString("wall")).isEqualTo("2026-11-21 23:06:00.123456");
				assertThat(rows.getTimestamp("moment")).isEqualTo(wall);
				assertThat(rows.getString("day")).isEqualTo("2026-11-21");
				assertThat(rows.getString("clock")).isEqualTo("23:06:59");
				assertThat(rows.next()).isFalse();
				assertThatThrownBy(() -> rows.getInt(1)).hasFieldOrPropertyWithValue("SQLState", "24000");
			}
		}
	}

	/**
	 * Literals of the server's types read through this driver and through pgjdbc: the
	 * text, the class of {@code getObject} and the constant of {@link Types} are the same
	 * but where a line of {@code differences} says what each answers.
	 */
	@Test
	void whatAValueOfEachTypeReadsAsBesidePgjdbc() throws Exception {
		String[] literals = { "true", "'7'::smallint", "42", "9000000000", "1.5::real", "2.5::float8",
				"12.50::numeric(6,2)", "'a'::varchar(3)", "'b'::text", "'c'::char(2)", "'\\x0102'::bytea",
				"date '2026-11-22'", "time '08:06:59.5'", "timestamp '2026-11-22 08:06:00.25'",
				"timestamptz '2026-11-22 08:06:00+09'", "timetz '08:06:59+09'",
				"'0f8fad5b-d9cb-469f-a165-70867728950e'::uuid", "'{\"a\": 1}'::jsonb", "'{1,2}'::int[]",
				"interval '1 day'", "null", "'x'::name", "'1'::oid", "''::xml" };
		List<String> differences = new ArrayList<>();
		String sql = "select " + String.join(", ", literals);
		try (Connection here = connect();
				Connection there = connect(new org.postgresql.Driver(), "scramuser", "scrampw");
				Statement mine = here.createStatement();
				Statement theirs = there.createStatement()) {
			// The two sessions show a time with a time zone on the same wall.
			theirs.execute("set timezone = 'UTC'");
			try (ResultSet a = mine.executeQuery(sql); ResultSet b = theirs.executeQuery(sql)) {
				assertThat(a.next() && b.next()).isTrue();
				ResultSetMetaData described = a.getMetaData();
				ResultSetMetaData theirDescription = b.getMetaData();
				for (int column = 1; column <= literals.length; column++) {
					String what = literals[column - 1];
					compare(differences, what + " text", a.getString(column), b.getString(column));
					compare(differences, what + " class", className(a.getObject(column)),
							className(b.getObject(column)));
					compare(differences, what + " type", described.getColumnType(column),
							theirDescription.getColumnType(column));
					compare(differences, what + " type name", described.getColumnTypeName(column),
							theirDescription.getColumnTypeName(column));
					if (a.getObject(column) instanceof java.util.Date || a.getObject(column) instanceof Number
							|| a.getObject(column) instanceof Boolean || a.getObject(column) instanceof UUID) {
						compare(differences, what + " value", a.getObject(column), b.getObject(column));
					}
				}
			}
		}
		assertThat(differences).containsExactly(
				// The JDBC specification's constant for a boolean; pgjdbc's is BIT.
				"true type: 16 / -7",
				// A type of the server's own is read as its text, where pgjdbc has a
				// class of its own.
				"'{\"a\": 1}'::jsonb class: java.lang.String / org.postgresql.util.PGobject",
				"'{1,2}'::int[] class: java.lang.String / org.postgresql.jdbc.PgArray",
				"'{1,2}'::int[] type: 1111 / 2003",
				"interval '1 day' class: java.lang.String / org.postgresql.util.PGInterval",
				"''::xml class: java.lang.String / org.postgresql.jdbc.PgSQLXML");
	}

	private static String className(Object value) {
		return value == null ? "null" : value.getClass().getName();
	}

	private static void compare(List<String> differences, String what, Object here, Object there) {
		if (here == null ? there != null : !here.equals(there)) {
			differences.add(what + ": " + here + " / " + there);
		}
	}

	// Statements.

	@Test
	void whatAStatementAnswers() throws Exception {
		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			// Several commands in one text: each a result, in their order.
			assertThat(statement.execute("create temporary table items (id serial primary key, name text unique);"
					+ " insert into items (name) values ('alpha'), ('beta'); select name from items order by id;"
					+ " update items set name = upper(name)"))
				.isFalse();
			assertThat(statement.getUpdateCount()).isZero();
			assertThat(statement.getMoreResults()).isFalse();
			assertThat(statement.getUpdateCount()).isEqualTo(2);
			assertThat(statement.getMoreResults()).isTrue();
			ResultSet names = statement.getResultSet();
			assertThat(names.next()).isTrue();
			assertThat(names.getString(1)).isEqualTo("alpha");
			assertThat(statement.getMoreResults()).isFalse();
			assertThat(names.isClosed()).isTrue();
			assertThat(statement.getLargeUpdateCount()).isEqualTo(2);
			assertThat(statement.getMoreResults()).isFalse();
			assertThat(statement.getUpdateCount()).isEqualTo(-1);
			assertThat(statement.execute("")).isFalse();
			assertThat(statement.getUpdateCount()).isEqualTo(-1);
			// A count where rows are asked for, and rows where a count is.
			assertThatThrownBy(() -> statement.executeQuery("delete from items where id < 0"))
				.hasFieldOrPropertyWithValue("SQLState", "02000");
			assertThatThrownBy(() -> statement.executeUpdate("select 1")).hasFieldOrPropertyWithValue("SQLState",
					"0100E");

			// The rows written, asked for: every column, the ones named, the ones of a
			// batch.
			assertThat(statement.executeUpdate("insert into items (name) values ('gamma'), ('delta')",
					Statement.RETURN_GENERATED_KEYS))
				.isEqualTo(2);
			try (ResultSet keys = statement.getGeneratedKeys()) {
				assertThat(keys.next()).isTrue();
				assertThat(keys.getInt("id")).isEqualTo(3);
				assertThat(keys.getString("name")).isEqualTo("gamma");
				assertThat(keys.next()).isTrue();
				assertThat(keys.next()).isFalse();
			}
			try (PreparedStatement insert = connection.prepareStatement("insert into items (name) values (?)",
					new String[] { "id" })) {
				insert.setString(1, "epsilon");
				insert.addBatch();
				insert.setString(1, "zeta");
				insert.addBatch();
				assertThat(insert.executeBatch()).containsExactly(1, 1);
				try (ResultSet keys = insert.getGeneratedKeys()) {
					assertThat(keys.getMetaData().getColumnCount()).isEqualTo(1);
					assertThat(keys.next()).isTrue();
					assertThat(keys.getInt(1)).isEqualTo(5);
					assertThat(keys.next()).isTrue();
					assertThat(keys.getInt(1)).isEqualTo(6);
				}
				// A batch the server refuses is undone whole.
				insert.setString(1, "eta");
				insert.addBatch();
				insert.setString(1, "ALPHA");
				insert.addBatch();
				assertThatThrownBy(insert::executeBatch).isInstanceOfSatisfying(BatchUpdateException.class, refused -> {
					assertThat(refused.getUpdateCounts()).containsExactly(Statement.EXECUTE_FAILED,
							Statement.EXECUTE_FAILED);
					assertThat(refused.getSQLState()).isEqualTo("23505");
					assertThat(refused.getCause()).isInstanceOf(SQLIntegrityConstraintViolationException.class);
				});
				assertThat(insert.executeBatch()).isEmpty();
			}
			assertThat(one(connection, "select count(*) from items")).isEqualTo("6");
			statement.addBatch("update items set name = 'one' where id = 1");
			statement.addBatch("update items set name = 'one' where id = 2");
			statement.addBatch("delete from items where id > 4");
			assertThatThrownBy(statement::executeBatch).isInstanceOfSatisfying(BatchUpdateException.class,
					refused -> assertThat(refused.getUpdateCounts()).containsExactly(1, Statement.EXECUTE_FAILED, 2));

			// A prepared statement run twice, described without being run, limited in its
			// rows.
			try (PreparedStatement select = connection
				.prepareStatement("select id, name from items where id > ? order by id")) {
				assertThat(select.getMetaData().getColumnLabel(2)).isEqualTo("name");
				assertThat(select.getParameterMetaData().getParameterCount()).isEqualTo(1);
				assertThat(select.getParameterMetaData().getParameterType(1)).isEqualTo(Types.INTEGER);
				for (int after = 2; after <= 3; after++) {
					select.setInt(1, after);
					try (ResultSet rows = select.executeQuery()) {
						assertThat(rows.next()).isTrue();
						assertThat(rows.getInt(1)).isEqualTo(after + 1);
					}
				}
				select.setMaxRows(1);
				select.setInt(1, 0);
				try (ResultSet rows = select.executeQuery()) {
					assertThat(rows.next()).isTrue();
					assertThat(rows.next()).isFalse();
				}
				assertThatThrownBy(() -> select.executeQuery("select 1")).hasFieldOrPropertyWithValue("SQLState",
						"HY010");
			}
			statement.setMaxRows(2);
			try (ResultSet rows = statement.executeQuery("select id from items order by id")) {
				assertThat(rows.next() && rows.next()).isTrue();
				assertThat(rows.next()).isFalse();
			}
			try (PreparedStatement insert = connection.prepareStatement("delete from items where id = 1")) {
				assertThat(insert.getMetaData()).isNull();
			}
		}
	}

	@Test
	void aResultSetIsWalkedBothWays() throws Exception {
		try (Connection connection = connect();
				Statement statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
						ResultSet.CONCUR_READ_ONLY);
				ResultSet rows = statement.executeQuery("select generate_series(1, 5)")) {
			assertThat(rows.isBeforeFirst()).isTrue();
			assertThat(rows.last()).isTrue();
			assertThat(rows.getInt(1)).isEqualTo(5);
			assertThat(rows.isLast()).isTrue();
			assertThat(rows.getRow()).isEqualTo(5);
			assertThat(rows.absolute(2)).isTrue();
			assertThat(rows.getInt(1)).isEqualTo(2);
			assertThat(rows.relative(2)).isTrue();
			assertThat(rows.getInt(1)).isEqualTo(4);
			assertThat(rows.absolute(-1)).isTrue();
			assertThat(rows.getInt(1)).isEqualTo(5);
			assertThat(rows.previous()).isTrue();
			assertThat(rows.first()).isTrue();
			assertThat(rows.isFirst()).isTrue();
			assertThat(rows.previous()).isFalse();
			assertThat(rows.isBeforeFirst()).isTrue();
			rows.afterLast();
			assertThat(rows.isAfterLast()).isTrue();
			assertThat(rows.getRow()).isZero();
			try (Statement forward = connection.createStatement(); ResultSet one = forward.executeQuery("select 1")) {
				assertThatThrownBy(one::last).hasFieldOrPropertyWithValue("SQLState", "24000");
				assertThatThrownBy(() -> one.updateInt(1, 2)).isInstanceOf(SQLFeatureNotSupportedException.class);
			}
			assertThatThrownBy(
					() -> connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE))
				.isInstanceOf(SQLFeatureNotSupportedException.class);
		}
	}

	// Transactions.

	@Test
	void aTransactionIsTheProgramsToEnd() throws Exception {
		try (Connection connection = connect();
				Connection other = connect();
				Statement statement = connection.createStatement()) {
			statement.execute("create table ledger (id int primary key, amount int)");
			assertThatThrownBy(connection::commit).hasFieldOrPropertyWithValue("SQLState", "25000");
			connection.setAutoCommit(false);
			statement.execute("insert into ledger values (1, 10)");
			assertThat(one(other, "select count(*) from ledger")).isEqualTo("0");
			connection.commit();
			assertThat(one(other, "select count(*) from ledger")).isEqualTo("1");
			statement.execute("insert into ledger values (2, 20)");
			connection.rollback();
			assertThat(one(connection, "select count(*) from ledger")).isEqualTo("1");

			// A savepoint: what came after it is undone, what came before is kept.
			statement.execute("insert into ledger values (3, 30)");
			Savepoint numbered = connection.setSavepoint();
			statement.execute("insert into ledger values (4, 40)");
			Savepoint named = connection.setSavepoint("the \"second\"");
			statement.execute("insert into ledger values (5, 50)");
			connection.rollback(named);
			connection.releaseSavepoint(named);
			assertThat(one(connection, "select count(*) from ledger")).isEqualTo("3");
			connection.rollback(numbered);
			connection.commit();
			assertThat(one(other, "select count(*) from ledger")).isEqualTo("2");
			assertThat(named.getSavepointName()).isEqualTo("the \"second\"");
			assertThat(numbered.getSavepointId()).isEqualTo(1);

			// A transaction that failed refuses every statement, and its commit is a
			// rollback.
			statement.execute("insert into ledger values (6, 60)");
			assertThatThrownBy(() -> statement.execute("insert into ledger values (1, 0)"))
				.isInstanceOf(SQLIntegrityConstraintViolationException.class);
			assertThatThrownBy(() -> statement.execute("select 1")).hasFieldOrPropertyWithValue("SQLState", "25P02");
			assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class)
				.hasFieldOrPropertyWithValue("SQLState", "40000");
			assertThat(one(other, "select count(*) from ledger")).isEqualTo("2");

			// Turning the mode back commits what is open.
			statement.execute("insert into ledger values (7, 70)");
			connection.setAutoCommit(true);
			assertThat(one(other, "select count(*) from ledger")).isEqualTo("3");

			connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
			assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
			connection.setReadOnly(true);
			assertThatThrownBy(() -> statement.execute("delete from ledger")).hasFieldOrPropertyWithValue("SQLState",
					"25006");
			connection.setReadOnly(false);
			connection.setSchema("pg_catalog");
			assertThat(connection.getSchema()).isEqualTo("pg_catalog");
			connection.setSchema(null);
			statement.execute("drop table ledger");
		}
	}

	// Refusals.

	@Test
	void whatTheServerRefusesIsAnExceptionOfItsState() throws Exception {
		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			assertThatThrownBy(() -> statement.execute("selec 1")).isInstanceOf(SQLSyntaxErrorException.class)
				.hasFieldOrPropertyWithValue("SQLState", "42601")
				.hasMessageContaining("ERROR: syntax error")
				.hasMessageContaining("Position: 1");
			assertThatThrownBy(() -> statement.execute("select 1 / 0")).isInstanceOf(SQLDataException.class)
				.hasFieldOrPropertyWithValue("SQLState", "22012");
			assertThatThrownBy(() -> statement.execute("select * from nowhere"))
				.isInstanceOf(SQLSyntaxErrorException.class)
				.hasFieldOrPropertyWithValue("SQLState", "42P01");
			assertThatThrownBy(() -> statement.execute("do $$ begin raise serialization_failure; end $$"))
				.isInstanceOf(SQLTransactionRollbackException.class);
			assertThatThrownBy(() -> statement.execute("copy (select 1) to stdout"))
				.isInstanceOf(SQLFeatureNotSupportedException.class);
			assertThatThrownBy(() -> statement.execute("copy pg_class from stdin")).isInstanceOf(SQLException.class);
			// The connection is as good as before after each.
			assertThat(one(connection, "select 1")).isEqualTo("1");
			// A notice is a warning of the statement that raised it.
			statement.execute("do $$ begin raise notice 'look'; raise warning 'out'; end $$");
			assertThat(statement.getWarnings().getMessage()).contains("look");
			assertThat(statement.getWarnings().getNextWarning().getMessage()).contains("out");
			try (PreparedStatement prepared = connection.prepareStatement("select ?::int")) {
				prepared.setString(1, "x");
				assertThatThrownBy(prepared::executeQuery).isInstanceOf(SQLDataException.class)
					.hasFieldOrPropertyWithValue("SQLState", "22P02");
				prepared.setString(1, "7");
				try (ResultSet rows = prepared.executeQuery()) {
					assertThat(rows.next()).isTrue();
					assertThat(rows.getInt(1)).isEqualTo(7);
				}
			}
			assertThatThrownBy(() -> connection.prepareCall("{call f()}"))
				.isInstanceOf(SQLFeatureNotSupportedException.class)
				.hasMessageContaining("Connection.prepareCall");
			connection.close();
			assertThat(connection.isClosed()).isTrue();
			assertThat(connection.isValid(1)).isFalse();
			assertThatThrownBy(() -> statement.execute("select 1"))
				.isInstanceOf(SQLNonTransientConnectionException.class)
				.hasFieldOrPropertyWithValue("SQLState", "08003");
			assertThatThrownBy(connection::createStatement).hasFieldOrPropertyWithValue("SQLState", "08003");
		}
		// No server where the URL says one is.
		assertThatThrownBy(() -> new PostgresDriver().connect("jdbc:postgresql://127.0.0.1:1/postgres?user=u", null))
			.isInstanceOf(SQLException.class)
			.hasFieldOrPropertyWithValue("SQLState", "08001");
	}

	@Test
	void aStatementOutOfTimeAndOneCancelledEndWithTheServersRefusal() throws Exception {
		try (Connection connection = connect();
				Connection watcher = connect();
				Statement statement = connection.createStatement()) {
			statement.setQueryTimeout(1);
			assertThatThrownBy(() -> statement.execute("select pg_sleep(30)")).isInstanceOf(SQLTimeoutException.class)
				.hasFieldOrPropertyWithValue("SQLState", "57014");
			// The limit is the statement's: another of the connection has none.
			try (Statement unlimited = connection.createStatement()) {
				unlimited.execute("select 1");
				assertThat(one(connection, "show statement_timeout")).isEqualTo("0");
			}
			statement.setQueryTimeout(0);
			// A cancel from another thread, once the server shows the statement running.
			String pid = one(connection, "select pg_backend_pid()");
			CompletableFuture<Throwable> running = CompletableFuture.supplyAsync(() -> {
				try {
					statement.execute("select pg_sleep(30)");
					return null;
				}
				catch (SQLException ex) {
					return ex;
				}
			});
			while (!"active".equals(one(watcher, "select state from pg_stat_activity where pid = " + pid))) {
				assertThat(running).isNotDone();
			}
			statement.cancel();
			assertThat(running.get(30, TimeUnit.SECONDS)).isInstanceOf(SQLException.class)
				.hasFieldOrPropertyWithValue("SQLState", "57014")
				.isNotInstanceOf(SQLTimeoutException.class);
			assertThat(one(connection, "select 1")).isEqualTo("1");
		}
	}

	// What the driver tells of the server and of itself.

	@Test
	void theMetadataBesidePgjdbc() throws Exception {
		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			DatabaseMetaData database = connection.getMetaData();
			assertThat(database.getDatabaseProductName()).isEqualTo("PostgreSQL");
			assertThat(database.getDatabaseMajorVersion()).isEqualTo(17);
			assertThat(database.getDatabaseProductVersion()).startsWith("17." + database.getDatabaseMinorVersion());
			assertThat(database.getURL()).isEqualTo(url());
			assertThat(database.getUserName()).isEqualTo("scramuser");
			assertThat(database.getDriverName()).isEqualTo("jdbc-postgresql");
			assertThat(database.getJDBCMajorVersion()).isEqualTo(4);
			assertThat(database.supportsTransactions() && database.supportsBatchUpdates()
					&& database.supportsGetGeneratedKeys())
				.isTrue();
			assertThat(database.getConnection()).isSameAs(connection);
			assertThat(connection.getCatalog()).isEqualTo("postgres");
			assertThat(connection.unwrap(Connection.class)).isSameAs(connection);
			assertThat(connection.isWrapperFor(String.class)).isFalse();
			try (ResultSet rows = statement.executeQuery(
					"select 1 as \"One\", 'x'::varchar(7) as two, 1.5::numeric(8,3) as three, now() as four")) {
				ResultSetMetaData described = rows.getMetaData();
				assertThat(described.getColumnCount()).isEqualTo(4);
				assertThat(described.getColumnLabel(1)).isEqualTo("One");
				assertThat(rows.findColumn("one")).isEqualTo(1);
				assertThat(described.getColumnType(2)).isEqualTo(Types.VARCHAR);
				assertThat(described.getPrecision(2)).isEqualTo(7);
				assertThat(described.getPrecision(3)).isEqualTo(8);
				assertThat(described.getScale(3)).isEqualTo(3);
				assertThat(described.getColumnTypeName(4)).isEqualTo("timestamptz");
				assertThat(described.getColumnClassName(4)).isEqualTo("java.sql.Timestamp");
				assertThat(described.isSigned(1)).isTrue();
			}
		}
		// The facts and the catalog's answers beside pgjdbc's, over the same tables: the
		// same but where a line of differences says what each answers.
		try (Connection admin = connect("postgres", "postgres"); Statement statement = admin.createStatement()) {
			statement.execute("create schema meta authorization scramuser");
		}
		try (Connection owner = connect(); Statement statement = owner.createStatement()) {
			statement.execute(
					"""
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
				Connection there = connect(new org.postgresql.Driver(), "scramuser", "scrampw")) {
			DatabaseMetaData mine = here.getMetaData();
			DatabaseMetaData theirs = there.getMetaData();
			// Every fact asked without an argument, and with each constant it takes.
			for (java.lang.reflect.Method method : DatabaseMetaData.class.getMethods()) {
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
					String what = method.getName() + (given.length == 0 ? "" : "(" + given[0] + ")");
					Object ours = ask(method, mine, given);
					Object other = ask(method, theirs, given);
					if (what.equals("getSQLKeywords")) {
						List<String> missing = new ArrayList<>(List.of(other.toString().split(",")));
						missing.removeAll(List.of(ours.toString().split(",")));
						differences.add("getSQLKeywords lacks " + missing);
					}
					else {
						compare(differences, what, ours, other);
					}
				}
			}
			compareRows(differences, "getCatalogs", metadata -> metadata.getCatalogs(), mine, theirs);
			compareRows(differences, "getSchemas", metadata -> metadata.getSchemas(), mine, theirs);
			compareRows(differences, "getSchemas meta", metadata -> metadata.getSchemas(null, "m_t%"), mine, theirs);
			compareRows(differences, "getTableTypes", metadata -> metadata.getTableTypes(), mine, theirs);
			compareRows(differences, "getTables", metadata -> metadata.getTables(null, "meta", "%", null), mine,
					theirs);
			compareRows(differences, "getTables TABLE",
					metadata -> metadata.getTables(null, "met_", "b%", new String[] { "TABLE" }), mine, theirs);
			compareRows(differences, "getColumns", metadata -> metadata.getColumns(null, "meta", "%", "%"), mine,
					theirs);
			// A column's position counts the columns its pattern leaves out.
			compareRows(differences, "getColumns t%", metadata -> metadata.getColumns(null, "meta", "books", "t%"),
					mine, theirs);
			// A catalog is the session's database.
			try (ResultSet none = mine.getTables("template1", "meta", "%", null)) {
				assertThat(none.next()).isFalse();
			}
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
			// What a result's columns are of.
			String sql = "select b.*, a.name, 1 as one from meta.books b join meta.authors a on a.id = b.author";
			try (Statement a = here.createStatement();
					Statement b = there.createStatement();
					ResultSet ours = a.executeQuery(sql);
					ResultSet other = b.executeQuery(sql)) {
				ResultSetMetaData described = ours.getMetaData();
				ResultSetMetaData theirDescription = other.getMetaData();
				for (int column = 1; column <= described.getColumnCount(); column++) {
					String what = described.getColumnLabel(column);
					compare(differences, what + " table", described.getTableName(column),
							theirDescription.getTableName(column));
					compare(differences, what + " schema", described.getSchemaName(column),
							theirDescription.getSchemaName(column));
					compare(differences, what + " catalog", described.getCatalogName(column),
							theirDescription.getCatalogName(column));
					compare(differences, what + " nullable", described.isNullable(column),
							theirDescription.isNullable(column));
					compare(differences, what + " auto-increment", described.isAutoIncrement(column),
							theirDescription.isAutoIncrement(column));
					compare(differences, what + " type name", described.getColumnTypeName(column),
							theirDescription.getColumnTypeName(column));
				}
			}
		}
		// (The order of getMethods is not fixed.)
		assertThat(differences).containsExactlyInAnyOrder(
				// The driver's own name and version, and the version of JDBC it is
				// written
				// against.
				"getDriverName: jdbc-postgresql / PostgreSQL JDBC Driver", "getDriverVersion: 0.1 / 42.7.14",
				"getDriverMajorVersion: 0 / 42", "getDriverMinorVersion: 1 / 7", "getJDBCMinorVersion: 3 / 2",
				// The catalog lists tables and functions out of the user's reach.
				"allProceduresAreCallable: false / true", "allTablesAreSelectable: false / true",
				// Key words of SQL/XML and some others are SQL:2003's in the
				// documentation's
				// table.
				"getSQLKeywords lacks [connection, content, document, generated, mapping, restrict, server, standalone,"
						+ " strip, version, whitespace, wrapper, xml, xmlattributes, xmlconcat, xmlelement, xmlforest,"
						+ " xmlnamespaces, xmlparse, xmlpi, xmlroot, xmlserialize]",
				// No escape is read, so no function of {fn ..} and no {call ..}.
				"getNumericFunctions:  / abs,acos,asin,atan,atan2,ceiling,cos,cot,degrees,exp,floor,log,log10,mod,pi,"
						+ "power,radians,round,sign,sin,sqrt,tan,truncate",
				"getStringFunctions:  / ascii,char,concat,lcase,left,length,ltrim,repeat,rtrim,space,substring,ucase,"
						+ "replace",
				"getSystemFunctions:  / database,ifnull,user",
				"getTimeDateFunctions:  / curdate,curtime,dayname,dayofmonth,dayofweek,dayofyear,hour,minute,month,"
						+ "monthname,now,quarter,second,week,year,timestampadd",
				"supportsStoredProcedures: false / true", "supportsStoredFunctionsUsingCallSyntax: false / true",
				// A name not quoted may hold a dollar sign; the server has procedures.
				"getExtraNameCharacters: $ / ", "getProcedureTerm: procedure / function",
				// A result set holds its rows, so it stays open across a commit; it
				// updates
				// none, and holds no ref cursor or large object.
				"supportsOpenCursorsAcrossCommit: true / false", "supportsOpenCursorsAcrossRollback: true / false",
				"ownUpdatesAreVisible(1003): false / true", "ownUpdatesAreVisible(1004): false / true",
				"ownDeletesAreVisible(1003): false / true", "ownDeletesAreVisible(1004): false / true",
				"ownInsertsAreVisible(1003): false / true", "ownInsertsAreVisible(1004): false / true",
				"supportsMultipleOpenResults: true / false", "supportsResultSetHoldability(2): false / true",
				"locatorsUpdateCopy: false / true", "supportsRefCursors: false / true",
				// The documentation's limits, and the server's max_connections.
				"getMaxColumnsInSelect: 1664 / 0", "getMaxConnections: 100 / 8192", "getMaxRowSize: 0 / 1073741824",
				// pg_toast is a schema.
				"getSchemas rows: 5 / 4", "getSchemas TABLE_SCHEM: pg_toast / public",
				// What JDBC says may be null is.
				"getTables TYPE_CAT: null / ", "getTables TYPE_SCHEM: null / ", "getTables TYPE_NAME: null / ",
				"getTables SELF_REFERENCING_COL_NAME: null / ", "getTables REF_GENERATION: null / ",
				"getTables TABLE TYPE_CAT: null / ", "getTables TABLE TYPE_SCHEM: null / ",
				"getTables TABLE TYPE_NAME: null / ", "getTables TABLE SELF_REFERENCING_COL_NAME: null / ",
				"getTables TABLE REF_GENERATION: null / ",
				// A column's type is what a result set of it tells (an array OTHER, a
				// boolean
				// BOOLEAN, serial int4, a date of ten characters); no digits where none
				// apply.
				"getColumns DATA_TYPE: 1111 / 2003, 16 / -7", "getColumns TYPE_NAME: int4 / serial",
				"getColumns COLUMN_SIZE: 10 / 13", "getColumns DECIMAL_DIGITS: null / 0",
				"getColumns t% DATA_TYPE: 1111 / 2003", "getColumns t% DECIMAL_DIGITS: null / 0",
				// Ordered by the column's name, as JDBC says.
				"getPrimaryKeys COLUMN_NAME: author / book, book / author", "getPrimaryKeys KEY_SEQ: 2 / 1, 1 / 2",
				// JDBC's thirteen columns.
				"getIndexInfo labels: [TABLE_CAT, TABLE_SCHEM, TABLE_NAME, NON_UNIQUE, INDEX_QUALIFIER, INDEX_NAME, TYPE,"
						+ " ORDINAL_POSITION, COLUMN_NAME, ASC_OR_DESC, CARDINALITY, PAGES, FILTER_CONDITION] / [TABLE_CAT,"
						+ " TABLE_SCHEM, TABLE_NAME, NON_UNIQUE, INDEX_QUALIFIER, INDEX_NAME, TYPE, ORDINAL_POSITION,"
						+ " COLUMN_NAME, ASC_OR_DESC, CARDINALITY, PAGES, FILTER_CONDITION, REMARKS]",
				"getIndexInfo REMARKS: (none) / null",
				"getIndexInfo unique labels: [TABLE_CAT, TABLE_SCHEM, TABLE_NAME, NON_UNIQUE, INDEX_QUALIFIER, INDEX_NAME,"
						+ " TYPE, ORDINAL_POSITION, COLUMN_NAME, ASC_OR_DESC, CARDINALITY, PAGES, FILTER_CONDITION] /"
						+ " [TABLE_CAT, TABLE_SCHEM, TABLE_NAME, NON_UNIQUE, INDEX_QUALIFIER, INDEX_NAME, TYPE,"
						+ " ORDINAL_POSITION, COLUMN_NAME, ASC_OR_DESC, CARDINALITY, PAGES, FILTER_CONDITION, REMARKS]",
				"getIndexInfo unique REMARKS: (none) / null",
				"getClientInfoProperties DESCRIPTION: The name the server shows for the session / The name of the"
						+ " application currently utilizing the connection.",
				// A result's column of a table tells its schema; its type is int8, not
				// serial.
				"id schema: meta / ", "id type name: int8 / bigserial", "author schema: meta / ",
				"isbn schema: meta / ", "price schema: meta / ", "title schema: meta / ", "tags schema: meta / ",
				"stamp schema: meta / ", "clock schema: meta / ", "flag schema: meta / ", "raw schema: meta / ",
				"twice schema: meta / ", "name schema: meta / ");
	}

	private static Object ask(java.lang.reflect.Method method, DatabaseMetaData metadata, Object[] arguments) {
		try {
			return method.invoke(metadata, arguments);
		}
		catch (ReflectiveOperationException ex) {
			return "refused: " + ex.getCause();
		}
	}

	// The rows of two answers of the catalog, a difference a line: the labels, then each
	// row's values by label.
	private interface Catalog {

		ResultSet ask(DatabaseMetaData metadata) throws SQLException;

	}

	private static void compareRows(List<String> differences, String what, Catalog question, DatabaseMetaData here,
			DatabaseMetaData there) throws SQLException {
		List<List<String>> mine;
		try {
			mine = rows(question.ask(here));
		}
		catch (SQLException ex) {
			differences.add(what + ": refused " + ex);
			for (List<String> line : rows(question.ask(there))) {
				System.out.println("DUMP " + what + " " + line);
			} // TEMP
			return;
		}
		List<List<String>> theirs = rows(question.ask(there));
		compare(differences, what + " labels", mine.get(0), theirs.get(0));
		compare(differences, what + " rows", mine.size() - 1, theirs.size() - 1);
		// A column's differences in one line: each pair of answers that differ, once.
		for (int column = 0; column < theirs.get(0).size(); column++) {
			int at = mine.get(0).indexOf(theirs.get(0).get(column));
			java.util.Set<String> pairs = new java.util.LinkedHashSet<>();
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

}
