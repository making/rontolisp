package am.ik.jdbc.spin.postgres;

import java.sql.Array;
import java.sql.ClientInfoStatus;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import am.ik.rontolisp.wit.WitException;
import org.jspecify.annotations.Nullable;

/**
 * A connection: one of the host's, a session with the server until it is closed, and what
 * JDBC keeps of it -- whether each statement is a transaction of its own, and the
 * transaction that is open otherwise, which starts with the first statement after the
 * last one ended.
 *
 * <p>
 * The host tells nothing of the session between two statements, so the transaction is
 * kept by the statements the connection sends: {@code BEGIN} ahead of the first one,
 * {@code COMMIT} or {@code ROLLBACK} to end it. A closed connection goes back to the
 * host's pool as it is, a transaction left open included, so closing ends one.
 */
final class SpinPostgresConnection extends UnsupportedConnection {

	private static final List<Postgres.ParameterValue> NONE = List.of();

	private final Address address;

	// The host's connection, or null once closed.
	private Postgres.@Nullable Connection host;

	private boolean autoCommit = true;

	private boolean readOnly;

	// Whether the connection sent a BEGIN that nothing has ended yet.
	private boolean transaction;

	// Whether the server refused a statement inside that transaction: it may have
	// aborted it.
	private boolean refused;

	// Whether a statement of the program's began a transaction itself.
	private boolean begun;

	private int savepoints;

	// The milliseconds a statement may run, as the connection last set them for the
	// session; -1 once a rollback may have taken a setting back.
	private int limit;

	private boolean limited;

	SpinPostgresConnection(Address address, Postgres.Connection host) {
		this.address = address;
		this.host = host;
	}

	Address address() {
		return this.address;
	}

	private Postgres.Connection host() throws SQLException {
		Postgres.Connection open = this.host;
		if (open == null) {
			throw Failures.closed("connection");
		}
		return open;
	}

	/**
	 * Runs a statement of the program's: under the limit on its time, and inside the
	 * transaction JDBC keeps when the connection does not commit each statement.
	 * @param rows whether its rows are asked for, or its count
	 * @param begins whether it starts a transaction itself
	 * @param streamed whether its rows are read in pieces, as the host reads them from
	 * the server ({@code query-async}), rather than handed over together
	 */
	Result run(String text, List<Postgres.ParameterValue> parameters, boolean rows, int timeoutSeconds, boolean begins,
			boolean streamed) throws SQLException {
		synchronized (this) {
			Postgres.Connection open = host();
			try {
				if (timeoutSeconds * 1000 != this.limit) {
					open.execute("SET statement_timeout = " + timeoutSeconds * 1000, NONE);
					this.limit = timeoutSeconds * 1000;
					this.limited = true;
				}
				if (!this.autoCommit && !this.transaction) {
					open.execute("BEGIN", NONE);
					this.transaction = true;
					this.refused = false;
				}
				this.begun |= begins;
				return streamed ? new Result(open.queryAsync(text, parameters))
						: rows ? new Result(open.query(text, parameters)) : new Result(open.execute(text, parameters));
			}
			catch (WitException ex) {
				throw refused(ex, timeoutSeconds > 0);
			}
		}
	}

	/**
	 * A refusal of a statement of the program's, the run's or the end of rows read in
	 * pieces: a refusal of the server's inside a transaction aborts it.
	 * @param timed whether the statement ran under a limit on its time
	 */
	SQLException refused(WitException ex, boolean timed) {
		synchronized (this) {
			this.refused |= this.transaction && Failures.server(ex);
			return Failures.of(ex, timed);
		}
	}

	// Runs a statement of the connection's own, outside the transaction JDBC keeps when
	// none is open.
	private void call(String sql) throws SQLException {
		synchronized (this) {
			try {
				host().execute(sql, NONE);
			}
			catch (WitException ex) {
				throw Failures.of(ex, false);
			}
		}
	}

	// The one value a statement of the connection's own answers.
	private @Nullable String value(String sql, List<Postgres.ParameterValue> parameters) throws SQLException {
		synchronized (this) {
			try {
				Postgres.RowSet rows = host().query(sql, parameters);
				if (rows.rows().isEmpty() || rows.rows().get(0).get(0) instanceof Postgres.DbValue.DbNull) {
					return null;
				}
				return Values.text(rows.rows().get(0).get(0), Values.name(rows.columns().get(0).dataType()));
			}
			catch (WitException ex) {
				throw Failures.of(ex, false);
			}
		}
	}

	// What a statement is, before it runs: the host describes none, so the server is
	// asked by statements of its own, inside the transaction that is open and starting
	// none.

	/**
	 * The columns of the rows a query answers, its parameters NULL: the query inside one
	 * that answers no row ({@link Sql#describing}).
	 */
	List<Postgres.Column> columns(String describing, int parameters) throws SQLException {
		List<Postgres.ParameterValue> nulls = new ArrayList<>();
		for (int i = 0; i < parameters; i++) {
			nulls.add(new Postgres.ParameterValue.DbNull());
		}
		synchronized (this) {
			try {
				return host().query(describing, nulls).columns();
			}
			catch (WitException ex) {
				this.refused |= this.transaction && Failures.server(ex);
				throw Failures.of(ex, false);
			}
		}
	}

	// How many statements the connection prepared to read their parameters' types.
	private int described;

	/**
	 * The type of each parameter of a text as the server reads it, by its name: the text
	 * prepared under a name ({@code PREPARE}), its {@code parameter_types} read from
	 * {@code pg_prepared_statements}, then let go ({@code DEALLOCATE}). The name is the
	 * call's own: one a failed call left behind stays in the session, which the host
	 * hands out again as it is, and a name taken is an error that would abort the
	 * transaction.
	 */
	List<String> parameterTypes(String text) throws SQLException {
		synchronized (this) {
			Postgres.Connection open = host();
			String name = "jdbc_spin_postgres_" + Long.toUnsignedString(System.nanoTime(), 36) + "_" + ++this.described;
			try {
				open.execute("PREPARE " + name + " AS " + text, NONE);
				Postgres.RowSet rows;
				try {
					rows = open.query("SELECT t.typname::text FROM pg_catalog.pg_prepared_statements p,"
							+ " unnest(p.parameter_types) WITH ORDINALITY AS u(type, n)"
							+ " JOIN pg_catalog.pg_type t ON t.oid = u.type WHERE p.name = '" + name + "' ORDER BY u.n",
							NONE);
				}
				finally {
					open.execute("DEALLOCATE " + name, NONE);
				}
				List<String> types = new ArrayList<>();
				for (List<Postgres.DbValue> row : rows.rows()) {
					types.add(Values.text(row.get(0), "text"));
				}
				return types;
			}
			catch (WitException ex) {
				this.refused |= this.transaction && Failures.server(ex);
				throw Failures.of(ex, false);
			}
		}
	}

	/**
	 * An array of one dimension for a parameter. The type is the elements' as the server
	 * names it ({@code int4}, {@code integer}, {@code text}, ...).
	 */
	@Override
	public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
		host();
		if (typeName == null || elements == null) {
			throw new SQLException("An array needs a type and elements", "22023");
		}
		return SpinPostgresArray.of(typeName, elements);
	}

	// Statements.

	@Override
	public Statement createStatement() throws SQLException {
		return createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
	}

	@Override
	public Statement createStatement(int type, int concurrency) throws SQLException {
		host();
		return new SpinPostgresStatement(this, kind(type, concurrency));
	}

	@Override
	public Statement createStatement(int type, int concurrency, int holdability) throws SQLException {
		return createStatement(type, concurrency);
	}

	@Override
	public PreparedStatement prepareStatement(String sql) throws SQLException {
		return prepare(sql, ResultSet.TYPE_FORWARD_ONLY, null);
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int type, int concurrency) throws SQLException {
		return prepare(sql, kind(type, concurrency), null);
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int type, int concurrency, int holdability)
			throws SQLException {
		return prepare(sql, kind(type, concurrency), null);
	}

	@Override
	public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
		return prepare(sql, ResultSet.TYPE_FORWARD_ONLY,
				autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS ? new String[0] : null);
	}

	@Override
	public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
		return prepare(sql, ResultSet.TYPE_FORWARD_ONLY, columnNames);
	}

	private PreparedStatement prepare(String sql, int type, String @Nullable [] keyColumns) throws SQLException {
		host();
		if (sql == null) {
			throw new SQLException("The statement is null", "42601");
		}
		return new SpinPostgresPreparedStatement(this, type, sql, keyColumns);
	}

	// A result set holds its rows: it can be walked both ways, sees no change and makes
	// none.
	private static int kind(int type, int concurrency) throws SQLException {
		if (type == ResultSet.TYPE_SCROLL_SENSITIVE) {
			throw Failures.unsupported("A result set that sees the changes of others");
		}
		if (concurrency != ResultSet.CONCUR_READ_ONLY) {
			throw Failures.unsupported("A result set that updates its rows");
		}
		return type;
	}

	/**
	 * The JDBC escapes in braces are not read: a statement goes to the server as it is.
	 */
	@Override
	public String nativeSQL(String sql) throws SQLException {
		host();
		return sql;
	}

	// Transactions.

	@Override
	public boolean getAutoCommit() throws SQLException {
		host();
		return this.autoCommit;
	}

	@Override
	public void setAutoCommit(boolean autoCommit) throws SQLException {
		synchronized (this) {
			host();
			if (autoCommit != this.autoCommit && this.transaction) {
				end("COMMIT");
			}
			this.autoCommit = autoCommit;
		}
	}

	/**
	 * Commits the transaction. One the server aborted for a statement it refused is
	 * rolled back, as the server does with its COMMIT, and the commit throws.
	 */
	@Override
	public void commit() throws SQLException {
		synchronized (this) {
			manual("commit");
			if (this.transaction) {
				boolean aborted = this.refused && aborted();
				end("COMMIT");
				if (aborted) {
					throw Failures.of("The transaction had failed and was rolled back, not committed", "40000");
				}
			}
		}
	}

	// The host tells nothing of a transaction's state: a statement that changes nothing
	// is refused in an aborted one.
	private boolean aborted() throws SQLException {
		try {
			host().execute("SELECT 1", NONE);
			return false;
		}
		catch (WitException ex) {
			return true;
		}
	}

	@Override
	public void rollback() throws SQLException {
		synchronized (this) {
			manual("rollback");
			if (this.transaction) {
				end("ROLLBACK");
			}
		}
	}

	private void manual(String what) throws SQLException {
		host();
		if (this.autoCommit) {
			throw new SQLException("A " + what + " of a connection that commits each statement", "25000");
		}
	}

	private void end(String command) throws SQLException {
		this.savepoints = 0;
		this.transaction = false;
		this.refused = false;
		this.begun = false;
		forgetLimit();
		call(command);
	}

	// A setting made inside a transaction is taken back by its rollback: the limit the
	// session has is not known afterwards.
	private void forgetLimit() {
		if (this.limited) {
			this.limit = -1;
		}
	}

	/**
	 * Runs statements as one transaction where the connection commits each: a batch is
	 * undone as a whole by a refusal.
	 */
	void atomically(Batch batch) throws SQLException {
		synchronized (this) {
			boolean own = this.autoCommit && !this.begun;
			if (own) {
				call("BEGIN");
			}
			try {
				batch.run();
			}
			catch (SQLException ex) {
				if (own) {
					forgetLimit();
					call("ROLLBACK");
				}
				throw ex;
			}
			if (own) {
				call("COMMIT");
			}
		}
	}

	/** The statements of a batch. */
	interface Batch {

		void run() throws SQLException;

	}

	@Override
	public Savepoint setSavepoint() throws SQLException {
		synchronized (this) {
			manual("savepoint");
			return savepoint(new SpinPostgresSavepoint(++this.savepoints, null));
		}
	}

	@Override
	public Savepoint setSavepoint(String name) throws SQLException {
		synchronized (this) {
			manual("savepoint");
			if (name == null) {
				throw new SQLException("The savepoint's name is null", "3B001");
			}
			return savepoint(new SpinPostgresSavepoint(0, name));
		}
	}

	private Savepoint savepoint(SpinPostgresSavepoint savepoint) throws SQLException {
		if (!this.transaction) {
			call("BEGIN");
			this.transaction = true;
			this.refused = false;
		}
		call("SAVEPOINT " + savepoint.identifier());
		return savepoint;
	}

	@Override
	public void rollback(Savepoint savepoint) throws SQLException {
		synchronized (this) {
			manual("rollback");
			call("ROLLBACK TO SAVEPOINT " + own(savepoint).identifier());
			this.refused = false;
			forgetLimit();
		}
	}

	@Override
	public void releaseSavepoint(Savepoint savepoint) throws SQLException {
		call("RELEASE SAVEPOINT " + own(savepoint).identifier());
	}

	private static SpinPostgresSavepoint own(Savepoint savepoint) throws SQLException {
		if (savepoint instanceof SpinPostgresSavepoint own) {
			return own;
		}
		throw new SQLException("The savepoint is not one of this driver's", "3B001");
	}

	@Override
	public int getTransactionIsolation() throws SQLException {
		String level = value("SHOW transaction_isolation", NONE);
		return switch (level != null ? level : "") {
			case "read uncommitted" -> Connection.TRANSACTION_READ_UNCOMMITTED;
			case "repeatable read" -> Connection.TRANSACTION_REPEATABLE_READ;
			case "serializable" -> Connection.TRANSACTION_SERIALIZABLE;
			default -> Connection.TRANSACTION_READ_COMMITTED;
		};
	}

	@Override
	public void setTransactionIsolation(int level) throws SQLException {
		String name = switch (level) {
			case Connection.TRANSACTION_READ_UNCOMMITTED -> "READ UNCOMMITTED";
			case Connection.TRANSACTION_READ_COMMITTED -> "READ COMMITTED";
			case Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE READ";
			case Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
			default -> throw new SQLException("No transaction isolation level is " + level, "22023");
		};
		synchronized (this) {
			between("isolation level");
			call("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL " + name);
		}
	}

	@Override
	public boolean isReadOnly() throws SQLException {
		host();
		return this.readOnly;
	}

	@Override
	public void setReadOnly(boolean readOnly) throws SQLException {
		synchronized (this) {
			between("read-only mode");
			if (readOnly != this.readOnly) {
				call("SET SESSION CHARACTERISTICS AS TRANSACTION " + (readOnly ? "READ ONLY" : "READ WRITE"));
				this.readOnly = readOnly;
			}
		}
	}

	private void between(String what) throws SQLException {
		host();
		if (this.transaction) {
			throw new SQLException("The " + what + " cannot change inside a transaction", "25001");
		}
	}

	@Override
	public int getHoldability() throws SQLException {
		host();
		return ResultSet.HOLD_CURSORS_OVER_COMMIT;
	}

	@Override
	public void setHoldability(int holdability) throws SQLException {
		host();
		if (holdability != ResultSet.HOLD_CURSORS_OVER_COMMIT) {
			throw Failures.unsupported("A result set that closes at a commit");
		}
	}

	// The connection itself.

	/**
	 * Gives the connection back to the host, a transaction left open rolled back first:
	 * the host hands the session out again as it is.
	 */
	@Override
	public void close() throws SQLException {
		synchronized (this) {
			Postgres.Connection open = this.host;
			if (open == null) {
				return;
			}
			this.host = null;
			try {
				if (this.transaction || this.begun) {
					open.execute("ROLLBACK", NONE);
				}
			}
			catch (WitException ex) {
				// The session is of no use to the host either.
			}
			finally {
				open.close();
			}
		}
	}

	@Override
	public boolean isClosed() throws SQLException {
		return this.host == null;
	}

	@Override
	public void abort(Executor executor) throws SQLException {
		close();
	}

	/**
	 * Asks the server an empty query.
	 * @param timeout ignored: the host's call takes no limit on its time
	 */
	@Override
	public boolean isValid(int timeout) throws SQLException {
		if (timeout < 0) {
			throw new SQLException("The seconds to wait cannot be negative: " + timeout, "22023");
		}
		synchronized (this) {
			Postgres.Connection open = this.host;
			if (open == null) {
				return false;
			}
			try {
				open.execute("", NONE);
				return true;
			}
			catch (WitException ex) {
				return false;
			}
		}
	}

	@Override
	public DatabaseMetaData getMetaData() throws SQLException {
		host();
		return new SpinPostgresDatabaseMetaData(this);
	}

	/** A setting of the session. */
	@Nullable String setting(String name) throws SQLException {
		return value("SELECT pg_catalog.current_setting(($1::text))", List.of(new Postgres.ParameterValue.Str(name)));
	}

	@Override
	public String getCatalog() throws SQLException {
		host();
		return this.address.database;
	}

	/**
	 * A session is of one database: another cannot be chosen, and the request is ignored.
	 */
	@Override
	public void setCatalog(String catalog) throws SQLException {
		host();
	}

	@Override
	public @Nullable String getSchema() throws SQLException {
		return value("SELECT pg_catalog.current_schema()::text", NONE);
	}

	@Override
	public void setSchema(String schema) throws SQLException {
		call(schema == null ? "RESET search_path" : "SET search_path TO " + Sql.identifier(schema));
	}

	/** The host hands over none of the server's notices. */
	@Override
	public @Nullable SQLWarning getWarnings() throws SQLException {
		host();
		return null;
	}

	@Override
	public void clearWarnings() throws SQLException {
		host();
	}

	@Override
	public Map<String, Class<?>> getTypeMap() throws SQLException {
		host();
		return new HashMap<>();
	}

	@Override
	public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
		host();
		if (map != null && !map.isEmpty()) {
			throw Failures.unsupported("A type map");
		}
	}

	// The one client info property is the name the server shows for the session.

	@Override
	public @Nullable String getClientInfo(String name) throws SQLException {
		host();
		return "ApplicationName".equals(name) ? setting("application_name") : null;
	}

	@Override
	public Properties getClientInfo() throws SQLException {
		Properties info = new Properties();
		String application = getClientInfo("ApplicationName");
		if (application != null) {
			info.setProperty("ApplicationName", application);
		}
		return info;
	}

	@Override
	public void setClientInfo(String name, String value) throws SQLClientInfoException {
		try {
			if (!"ApplicationName".equals(name)) {
				throw new SQLException("No client info property is named " + name, "22023");
			}
			// The value goes apart from the text, so nothing in it is read as SQL.
			value("SELECT pg_catalog.set_config('application_name', ($1::text), false)",
					List.of(new Postgres.ParameterValue.Str(value != null ? value : "")));
		}
		catch (SQLException ex) {
			Map<String, ClientInfoStatus> failed = new HashMap<>();
			failed.put(name, "ApplicationName".equals(name) ? ClientInfoStatus.REASON_UNKNOWN
					: ClientInfoStatus.REASON_UNKNOWN_PROPERTY);
			throw new SQLClientInfoException(ex.getMessage(), ex.getSQLState(), failed, ex);
		}
	}

	@Override
	public void setClientInfo(Properties properties) throws SQLClientInfoException {
		if (properties != null) {
			for (String name : properties.stringPropertyNames()) {
				setClientInfo(name, properties.getProperty(name));
			}
		}
	}

}
