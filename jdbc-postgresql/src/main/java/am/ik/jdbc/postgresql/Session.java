package am.ik.jdbc.postgresql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * One session with a server over the frontend/backend protocol, version 3.0 (PostgreSQL
 * documentation, "Frontend/Backend Protocol"): the start-up, the authentication, and the
 * exchanges of the simple and the extended query. It knows nothing of JDBC but its
 * exceptions.
 *
 * <p>
 * An exchange is written whole, sent in one piece and then read one group at a time: a
 * group is what the server answers up to a ReadyForQuery, so a simple query is a group
 * and so is everything up to a Sync. Every value travels as text, in UTF-8.
 */
final class Session {

	private static final int PROTOCOL = 3 << 16;

	private static final int CANCEL = (1234 << 16) | 5678;

	private final Target target;

	private final Wire wire;

	private final Map<String, String> parameters = new HashMap<>();

	private int processId;

	private int secretKey;

	// 'I' idle, 'T' in a transaction, 'E' in a failed one.
	private int status = 'I';

	private boolean closed;

	private @Nullable SQLWarning warnings;

	// The statement_timeout this session set last, in milliseconds: 0 none, -1 when a
	// rollback may have taken it back.
	private int timeout;

	// Whether a statement_timeout was set inside the transaction that is open.
	private boolean timeoutInTransaction;

	// The groups written ahead of the caller's own, whose answers are read and dropped.
	private int ahead;

	private int[] described = new int[0];

	Session(Target target) throws SQLException {
		this.target = target;
		// The limit DriverManager keeps is the one of a connection that names none.
		int login = target.setting("loginTimeout") != null ? target.millis("loginTimeout")
				: DriverManager.getLoginTimeout() * 1000;
		try {
			this.wire = new Wire(target.host, target.port,
					target.setting("connectTimeout") != null ? target.millis("connectTimeout") : login);
		}
		catch (IOException ex) {
			throw new SQLException(
					"The connection to " + target.host + ":" + target.port + " failed: " + ex.getMessage(), "08001",
					ex);
		}
		try {
			this.wire.timeout(login);
			login();
			this.wire.timeout(target.millis("socketTimeout"));
		}
		catch (IOException ex) {
			abort();
			throw Failures.broken(ex);
		}
		catch (SQLException | RuntimeException ex) {
			abort();
			throw ex;
		}
	}

	// The start-up.

	private void login() throws IOException, SQLException {
		this.wire.begin(0).int32(PROTOCOL);
		this.wire.text("user").text(this.target.user);
		this.wire.text("database").text(this.target.database);
		this.wire.text("client_encoding").text("UTF8");
		this.wire.text("DateStyle").text("ISO");
		String application = this.target.setting("ApplicationName");
		if (application != null && !application.isEmpty()) {
			this.wire.text("application_name").text(application);
		}
		String options = this.target.setting("options");
		if (options != null && !options.isEmpty()) {
			this.wire.text("options").text(options);
		}
		this.wire.octet(0).end().flush();
		Scram scram = null;
		while (true) {
			switch (this.wire.next()) {
				case 'R' -> {
					int request = this.wire.int32();
					switch (request) {
						case 0 -> {
						}
						case 3 -> this.wire.begin('p').text(password("a password")).end().flush();
						case 5 -> {
							byte[] salt = this.wire.octets(4);
							this.wire.begin('p')
								.text(md5(this.target.user, password("an MD5 password"), salt))
								.end()
								.flush();
						}
						case 10 -> {
							boolean offered = false;
							for (String mechanism = this.wire.text(); !mechanism.isEmpty(); mechanism = this.wire
								.text()) {
								offered |= mechanism.equals("SCRAM-SHA-256");
							}
							if (!offered) {
								throw Failures.unsupported("A SASL mechanism other than SCRAM-SHA-256");
							}
							scram = new Scram("", password("a SCRAM-SHA-256 password"), Scram.nonce());
							byte[] first = scram.clientFirst().getBytes(StandardCharsets.UTF_8);
							this.wire.begin('p').text("SCRAM-SHA-256").int32(first.length).octets(first).end().flush();
						}
						case 11 -> {
							if (scram == null) {
								throw Failures.protocol("The server continues a SASL exchange it did not start");
							}
							String serverFirst = new String(this.wire.octets(this.wire.remaining()),
									StandardCharsets.UTF_8);
							this.wire.begin('p')
								.octets(scram.clientFinal(serverFirst).getBytes(StandardCharsets.UTF_8))
								.end()
								.flush();
						}
						case 12 -> {
							if (scram == null) {
								throw Failures.protocol("The server ends a SASL exchange it did not start");
							}
							scram.verify(new String(this.wire.octets(this.wire.remaining()), StandardCharsets.UTF_8));
						}
						default -> throw Failures
							.unsupported("The authentication the server asks for (request " + request + ")");
					}
				}
				case 'S' -> {
					String name = this.wire.text();
					this.parameters.put(name, this.wire.text());
				}
				case 'K' -> {
					this.processId = this.wire.int32();
					this.secretKey = this.wire.int32();
				}
				case 'N' -> warn(Failures.warning(Failures.fields(this.wire)));
				case 'E' -> throw Failures.error(Failures.fields(this.wire), false);
				case 'Z' -> {
					this.status = this.wire.octet();
					return;
				}
				default -> {
					// NegotiateProtocolVersion and what a later server adds: nothing to
					// answer.
				}
			}
		}
	}

	private String password(String asked) throws SQLException {
		if (this.target.password == null) {
			throw Failures.login("The server asks for " + asked + " and the connection has none");
		}
		return this.target.password;
	}

	// "md5" and the MD5, in hexadecimal, of the salt after the MD5 in hexadecimal of the
	// password and the user (PostgreSQL documentation, "Password Authentication").
	static String md5(String user, String password, byte[] salt) throws SQLException {
		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			digest.update(password.getBytes(StandardCharsets.UTF_8));
			digest.update(user.getBytes(StandardCharsets.UTF_8));
			digest.update(hex(digest.digest()).getBytes(StandardCharsets.US_ASCII));
			digest.update(salt);
			return "md5" + hex(digest.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw Failures.protocol("The MD5 digest of the password cannot be computed", ex);
		}
	}

	private static String hex(byte[] octets) {
		StringBuilder text = new StringBuilder(octets.length * 2);
		for (byte octet : octets) {
			text.append(Character.forDigit((octet >> 4) & 15, 16)).append(Character.forDigit(octet & 15, 16));
		}
		return text.toString();
	}

	// What the session is.

	Target target() {
		return this.target;
	}

	/** A run-time parameter the server reported, as {@code server_version}. */
	@Nullable String parameter(String name) {
		return this.parameters.get(name);
	}

	boolean inTransaction() {
		return this.status != 'I';
	}

	boolean isClosed() {
		return this.closed;
	}

	@Nullable SQLWarning takeWarnings() {
		SQLWarning taken = this.warnings;
		this.warnings = null;
		return taken;
	}

	private void warn(SQLWarning warning) {
		if (this.warnings == null) {
			this.warnings = warning;
		}
		else {
			this.warnings.setNextWarning(warning);
		}
	}

	int timeout() throws SQLException {
		try {
			return this.wire.timeout();
		}
		catch (IOException ex) {
			throw Failures.broken(ex);
		}
	}

	void timeout(int millis) throws SQLException {
		try {
			this.wire.timeout(millis);
		}
		catch (IOException ex) {
			throw Failures.broken(ex);
		}
	}

	// Writing an exchange.

	/**
	 * Writes ahead of an exchange the start of a transaction, unless one is open.
	 */
	void transaction() {
		if (this.status == 'I') {
			simple("BEGIN");
			this.ahead++;
		}
	}

	/**
	 * Writes ahead of an exchange the server's limit on a statement's time, unless it
	 * holds already.
	 * @param millis the limit, 0 for none
	 */
	void limit(int millis) {
		if (millis != this.timeout) {
			simple(millis == 0 ? "RESET statement_timeout" : "SET statement_timeout = " + millis);
			this.timeout = millis;
			this.timeoutInTransaction |= this.status != 'I';
			this.ahead++;
		}
	}

	/** Writes a simple query: its answer is a group. */
	void simple(String sql) {
		this.wire.begin('Q').text(sql).end();
	}

	/** Writes the parse of a statement into the unnamed prepared statement. */
	void parse(String sql, int[] types) {
		this.wire.begin('P').octet(0).text(sql).int16(types.length);
		for (int type : types) {
			this.wire.int32(type);
		}
		this.wire.end();
	}

	/**
	 * Writes the binding of the unnamed statement's parameters, as text, and the run of
	 * what it makes.
	 * @param values the parameters, {@code null} for NULL
	 * @param described whether the columns are asked for
	 * @param maxRows the rows to answer at most, 0 for all
	 */
	void bind(@Nullable String[] values, boolean described, int maxRows) {
		this.wire.begin('B').octet(0).octet(0).int16(0).int16(values.length);
		for (String value : values) {
			if (value == null) {
				this.wire.int32(-1);
			}
			else {
				byte[] octets = value.getBytes(StandardCharsets.UTF_8);
				this.wire.int32(octets.length).octets(octets);
			}
		}
		this.wire.int16(0).end();
		if (described) {
			this.wire.begin('D').octet('P').octet(0).end();
		}
		this.wire.begin('E').octet(0).int32(maxRows).end();
	}

	/** Writes the question what the unnamed statement takes and answers. */
	void describe() {
		this.wire.begin('D').octet('S').octet(0).end();
	}

	/** The types of the parameters the server described last. */
	int[] described() {
		return this.described;
	}

	/** Writes the Sync that ends a group of the extended query. */
	void sync() {
		this.wire.begin('S').end();
	}

	// Reading an exchange.

	/**
	 * Sends what was written and reads one group.
	 * @param into where the group's results go
	 * @param maxRows the rows to keep of each result at most, 0 for all
	 * @param timed whether a statement the server cancels is one out of time
	 * @return what the server refused in the group, {@code null} when nothing
	 */
	@Nullable SQLException group(@Nullable List<Result> into, int maxRows, boolean timed) throws SQLException {
		try {
			this.wire.flush();
			SQLException refused = null;
			while (this.ahead > 0) {
				this.ahead--;
				SQLException before = read(null, 0, false);
				refused = refused != null ? refused : before;
			}
			SQLException own = read(into, maxRows, timed);
			return refused != null ? refused : own;
		}
		catch (IOException ex) {
			abort();
			throw Failures.broken(ex);
		}
	}

	/** Sends what was written and reads one group, throwing what the server refused. */
	void run(@Nullable List<Result> into, int maxRows, boolean timed) throws SQLException {
		SQLException refused = group(into, maxRows, timed);
		if (refused != null) {
			throw refused;
		}
	}

	private @Nullable SQLException read(@Nullable List<Result> into, int maxRows, boolean timed) throws IOException {
		SQLException refused = null;
		Result current = null;
		while (true) {
			switch (this.wire.next()) {
				case 'T' -> {
					Column[] columns = new Column[this.wire.int16()];
					for (int i = 0; i < columns.length; i++) {
						String name = this.wire.text();
						int table = this.wire.int32();
						int attribute = this.wire.int16();
						int type = this.wire.int32();
						int size = (short) this.wire.int16();
						int modifier = this.wire.int32();
						this.wire.int16();
						columns[i] = new Column(name, table, attribute, type, size, modifier);
					}
					current = new Result(columns);
				}
				case 'D' -> {
					if (current != null && (maxRows == 0 || current.rows.size() < maxRows)) {
						current.rows.add(this.wire.octets(this.wire.remaining()));
					}
				}
				case 'C' -> {
					if (current == null) {
						current = new Result(null);
					}
					current.tag = this.wire.text();
					if (into != null) {
						into.add(current);
					}
					current = null;
				}
				case 't' -> {
					this.described = new int[this.wire.int16()];
					for (int i = 0; i < this.described.length; i++) {
						this.described[i] = this.wire.int32();
					}
				}
				case 's' -> {
					// The portal holds more rows than were asked for: the result ends
					// here.
					if (current != null && into != null) {
						current.tag = "SELECT " + current.rows.size();
						into.add(current);
					}
					current = null;
				}
				case 'n' -> current = null;
				case 'E' -> {
					SQLException error = Failures.error(Failures.fields(this.wire), timed);
					if (refused == null) {
						refused = error;
					}
					else {
						refused.setNextException(error);
					}
					current = null;
				}
				case 'N' -> warn(Failures.warning(Failures.fields(this.wire)));
				case 'S' -> {
					String name = this.wire.text();
					this.parameters.put(name, this.wire.text());
				}
				case 'G', 'W' -> {
					// The server waits for the rows of a COPY: tell it there are none.
					this.wire.begin('f').text("COPY FROM STDIN is not supported by this driver").end().flush();
				}
				case 'H' -> {
					SQLException error = Failures.unsupported("COPY TO STDOUT");
					if (refused == null) {
						refused = error;
					}
				}
				case 'Z' -> {
					this.status = this.wire.octet();
					if (current != null && into != null) {
						// Columns described and not run: the answer to a Describe.
						into.add(current);
					}
					if (this.status == 'I' && this.timeoutInTransaction) {
						// A rollback takes a SET back: the limit is set again when next
						// needed.
						this.timeoutInTransaction = false;
						this.timeout = -1;
					}
					return refused;
				}
				default -> {
					// ParseComplete, BindComplete, CloseComplete, an empty query's
					// answer, the
					// rows of a COPY, a notification: nothing to keep.
				}
			}
		}
	}

	// The end.

	/** Tells the server the session ends, and closes the socket. */
	void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		try {
			this.wire.begin('X').end().flush();
		}
		catch (IOException ex) {
			// The session ends either way.
		}
		abort();
	}

	/** Closes the socket with no word to the server. */
	void abort() {
		this.closed = true;
		try {
			this.wire.close();
		}
		catch (IOException ex) {
			// Nothing to do about a socket that does not close.
		}
	}

	/**
	 * Asks the server to cancel what this session is running, over a connection of its
	 * own (PostgreSQL documentation, "Canceling Requests in Progress").
	 */
	void cancel() throws SQLException {
		try {
			Wire request = new Wire(this.target.host, this.target.port, this.target.millis("connectTimeout"));
			try {
				request.begin(0).int32(CANCEL).int32(this.processId).int32(this.secretKey).end().flush();
			}
			finally {
				request.close();
			}
		}
		catch (IOException ex) {
			throw new SQLException("The request to cancel did not reach the server: " + ex.getMessage(), "08001", ex);
		}
	}

}
