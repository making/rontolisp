package am.ik.jdbc.postgresql;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;

/**
 * The driver as a {@link DataSource}: a connection is made with the URL, the user and the
 * password set on it, as a pool or a container sets a bean's properties. A setting of the
 * URL wins over the user and the password, as it does over a property given to
 * {@link PostgresDriver#connect}. It keeps no connection.
 */
public final class PostgresDataSource implements DataSource {

	private @Nullable String url;

	private @Nullable String user;

	private @Nullable String password;

	private int loginTimeout;

	private @Nullable PrintWriter logWriter;

	/** The URL connections are made to, as {@code jdbc:postgresql://host:5432/db}. */
	public @Nullable String getUrl() {
		return this.url;
	}

	public void setUrl(@Nullable String url) {
		this.url = url;
	}

	public @Nullable String getUser() {
		return this.user;
	}

	public void setUser(@Nullable String user) {
		this.user = user;
	}

	public void setPassword(@Nullable String password) {
		this.password = password;
	}

	@Override
	public Connection getConnection() throws SQLException {
		return getConnection(this.user, this.password);
	}

	@Override
	public Connection getConnection(@Nullable String user, @Nullable String password) throws SQLException {
		String target = this.url;
		if (target == null) {
			throw new SQLException("The data source has no URL", "08001");
		}
		Properties info = new Properties();
		if (user != null) {
			info.setProperty("user", user);
		}
		if (password != null) {
			info.setProperty("password", password);
		}
		if (this.loginTimeout > 0) {
			info.setProperty("loginTimeout", Integer.toString(this.loginTimeout));
		}
		Connection connection = new PostgresDriver().connect(target, info);
		if (connection == null) {
			throw new SQLException("Not a URL of this driver: " + target, "08001");
		}
		return connection;
	}

	/** The seconds a login waits for the server; 0 for the driver's default. */
	@Override
	public int getLoginTimeout() throws SQLException {
		return this.loginTimeout;
	}

	@Override
	public void setLoginTimeout(int seconds) throws SQLException {
		if (seconds < 0) {
			throw new SQLException("The seconds to wait cannot be negative: " + seconds, "22023");
		}
		this.loginTimeout = seconds;
	}

	/** Kept as JDBC asks, and written to by nothing: the driver logs nothing. */
	@Override
	public @Nullable PrintWriter getLogWriter() throws SQLException {
		return this.logWriter;
	}

	@Override
	public void setLogWriter(@Nullable PrintWriter out) throws SQLException {
		this.logWriter = out;
	}

	@Override
	public Logger getParentLogger() throws SQLFeatureNotSupportedException {
		throw Failures.unsupported("DataSource.getParentLogger");
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) throws SQLException {
		return iface.isInstance(this);
	}

	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		if (iface.isInstance(this)) {
			return iface.cast(this);
		}
		throw Failures.unsupported("An unwrap to " + iface.getName());
	}

}
