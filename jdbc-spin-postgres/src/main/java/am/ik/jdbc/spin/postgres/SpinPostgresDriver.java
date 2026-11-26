package am.ik.jdbc.spin.postgres;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.logging.Logger;

import am.ik.rontolisp.wit.WitException;
import org.jspecify.annotations.Nullable;

/**
 * A JDBC driver for PostgreSQL over the connection of the host that runs the program: it
 * takes the URLs that start with {@code jdbc:postgresql:} and runs each statement through
 * Spin's {@code spin:postgres/postgres}. The host opens the connection, pools it, speaks
 * PostgreSQL's protocol and, when the URL asks for it, TLS; the driver opens no socket.
 *
 * <p>
 * Where no host implements the interface -- under a JDK, where another driver takes the
 * same URLs over a socket -- {@link #connect} answers {@code null}, as for a URL of
 * another driver, so {@link java.sql.DriverManager} goes on to the next one: a program
 * with both on its path runs through the host where there is one, and over a socket
 * elsewhere.
 *
 * <p>
 * {@link java.sql.DriverManager} finds it through
 * {@code META-INF/services/java.sql.Driver}; nothing has to name the class.
 */
public final class SpinPostgresDriver implements Driver {

	static final String NAME = "jdbc-spin-postgres";

	static final int MAJOR_VERSION = 0;

	static final int MINOR_VERSION = 1;

	static {
		try {
			DriverManager.registerDriver(new SpinPostgresDriver());
		}
		catch (SQLException ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

	@Override
	public @Nullable Connection connect(String url, Properties info) throws SQLException {
		Optional<Postgres> host = ServiceLoader.load(Postgres.class).findFirst();
		if (host.isEmpty()) {
			return null;
		}
		Address address = Address.parse(url, info);
		return address == null ? null : open(host.get(), address);
	}

	/** A connection of the host's, or a refusal that names what has no host. */
	static Connection connect(Address address) throws SQLException {
		Optional<Postgres> host = ServiceLoader.load(Postgres.class).findFirst();
		if (host.isEmpty()) {
			throw new SQLException(
					"No host implements spin:postgres/postgres here: the driver runs in a WebAssembly component"
							+ " that Spin serves",
					"08001");
		}
		return open(host.get(), address);
	}

	// With a certificate of the program's own, through the connection builder; without
	// one the host verifies the server's against the roots it has.
	private static Connection open(Postgres host, Address address) throws SQLException {
		try {
			String certificate = address.rootCertificate;
			if (certificate == null) {
				return new SpinPostgresConnection(address, host.connectionOpen(address.host));
			}
			try (Postgres.ConnectionBuilder builder = host.connectionBuilderNew(address.host)) {
				builder.setCaRoot(certificate);
				return new SpinPostgresConnection(address, builder.build());
			}
		}
		catch (WitException ex) {
			throw Failures.connecting(ex);
		}
	}

	@Override
	public boolean acceptsURL(String url) throws SQLException {
		if (url == null) {
			throw new SQLException("The url is null", "08001");
		}
		return url.startsWith(Address.PREFIX);
	}

	@Override
	public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
		DriverPropertyInfo user = new DriverPropertyInfo("user", info != null ? info.getProperty("user") : null);
		user.description = "The role to log in as";
		user.required = true;
		DriverPropertyInfo password = new DriverPropertyInfo("password", null);
		password.description = "The role's password";
		DriverPropertyInfo mode = new DriverPropertyInfo("sslmode", null);
		mode.description = "Whether the host speaks TLS to the server";
		mode.choices = new String[] { "disable", "prefer", "require" };
		DriverPropertyInfo certificate = new DriverPropertyInfo("sslrootcert", null);
		certificate.description = "A PEM certificate the server's is verified against";
		return new DriverPropertyInfo[] { user, password, mode, certificate };
	}

	@Override
	public int getMajorVersion() {
		return MAJOR_VERSION;
	}

	@Override
	public int getMinorVersion() {
		return MINOR_VERSION;
	}

	@Override
	public boolean jdbcCompliant() {
		return false;
	}

	@Override
	public Logger getParentLogger() throws SQLFeatureNotSupportedException {
		throw Failures.unsupported("Driver.getParentLogger");
	}

}
