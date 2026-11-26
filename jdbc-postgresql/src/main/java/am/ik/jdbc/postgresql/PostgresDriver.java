package am.ik.jdbc.postgresql;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

/**
 * A JDBC driver for PostgreSQL: it takes the URLs that start with
 * {@code jdbc:postgresql:} and speaks the frontend/backend protocol, version 3.0, over a
 * plain socket.
 *
 * <p>
 * {@link java.sql.DriverManager} finds it through
 * {@code META-INF/services/java.sql.Driver}; nothing has to name the class.
 */
public final class PostgresDriver implements Driver {

	static final String NAME = "jdbc-postgresql";

	static final int MAJOR_VERSION = 0;

	static final int MINOR_VERSION = 1;

	static {
		try {
			DriverManager.registerDriver(new PostgresDriver());
		}
		catch (SQLException ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

	@Override
	public @Nullable Connection connect(String url, Properties info) throws SQLException {
		Target target = Target.parse(url, info);
		if (target == null) {
			return null;
		}
		String mode = target.setting("sslmode");
		boolean encrypted = mode != null ? mode.equals("require") || mode.startsWith("verify-")
				: target.setting("ssl") != null && !target.setting("ssl").equals("false");
		if (encrypted) {
			throw Failures.unsupported("A connection over TLS (sslmode=" + (mode != null ? mode : "require") + ")");
		}
		return new PostgresConnection(new Session(target));
	}

	@Override
	public boolean acceptsURL(String url) throws SQLException {
		if (url == null) {
			throw new SQLException("The url is null", "08001");
		}
		return url.startsWith(Target.PREFIX);
	}

	@Override
	public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
		Target target = Target.parse(url, info);
		DriverPropertyInfo user = new DriverPropertyInfo("user", target != null ? target.user : null);
		user.description = "The role to log in as";
		user.required = true;
		DriverPropertyInfo password = new DriverPropertyInfo("password", null);
		password.description = "The role's password";
		return new DriverPropertyInfo[] { user, password };
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
