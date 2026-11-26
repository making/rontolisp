package am.ik.jdbc.spin.postgres;

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
 * {@link SpinPostgresDriver#connect}. It keeps no connection: the host keeps the pool.
 *
 * <p>
 * It names this driver, so with another PostgreSQL driver on the path the connection is
 * still the host's; where no host implements the interface it throws, and goes on to no
 * other driver.
 */
public final class SpinPostgresDataSource implements DataSource {

	private @Nullable String url;

	private @Nullable String user;

	private @Nullable String password;

	private @Nullable String sslRootCert;

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

	/**
	 * A PEM certificate the server's is verified against, beside the roots the host has.
	 */
	public @Nullable String getSslRootCert() {
		return this.sslRootCert;
	}

	public void setSslRootCert(@Nullable String certificate) {
		this.sslRootCert = certificate;
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
		if (this.sslRootCert != null) {
			info.setProperty("sslrootcert", this.sslRootCert);
		}
		Address address = Address.parse(target, info);
		if (address == null) {
			throw new SQLException("Not a URL of this driver: " + target, "08001");
		}
		return SpinPostgresDriver.connect(address);
	}

	/** The seconds the host waits for the server at a login; 0 for its default. */
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
