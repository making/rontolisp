// Shiori: the connections to the database, kept across requests as a pool keeps them.
package com.example.shiori.jdbc;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedDeque;

// Each statement runs on a connection an earlier one gave back, or on a new one; a
// transaction holds one connection for all its statements.
public final class Database implements JdbcClient, Connector {

    // A connection that has waited longer than this is asked whether it is still there
    // before a statement is given it: the server may have ended it meanwhile.
    private static final long CHECKED_WITHIN = 2_000_000_000L;

    // The connections kept for the next statements; more are closed when given back.
    private static final int KEPT = 4;

    private final String url;

    private final Properties properties;

    // The connections no statement holds, the latest given back first. A login is a few
    // round trips, and a SCRAM one costs tens of milliseconds on wasm (README.md).
    private final ConcurrentLinkedDeque<Idle> idle = new ConcurrentLinkedDeque<>();

    private record Idle(Connection connection, long since) {
    }

    private Database(String url, Properties properties) {
        this.url = url;
        this.properties = properties;
    }

    // A database URL in either form: a JDBC URL, or postgresql://user:password@host:port/db,
    // whose user and password become the connection's properties.
    public static Database of(String url) {
        if (url.startsWith("jdbc:")) {
            return new Database(url, new Properties());
        }
        URI uri = URI.create(url);
        if (!"postgresql".equals(uri.getScheme()) && !"postgres".equals(uri.getScheme())) {
            throw new IllegalArgumentException("DATABASE_URL is neither jdbc: nor postgresql:// : " + url);
        }
        Properties properties = new Properties();
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            properties.setProperty("user", colon < 0 ? userInfo : userInfo.substring(0, colon));
            if (colon >= 0) {
                properties.setProperty("password", userInfo.substring(colon + 1));
            }
        }
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        return new Database(jdbcUrl, properties);
    }

    @Override
    public StatementSpec sql(String sql) {
        return new StatementSpec(this, sql);
    }

    // The work's statements in one transaction: committed when it returns, rolled back
    // when it throws.
    public <T> T inTransaction(Transaction<T> work) throws SQLException {
        return run(connection -> {
            connection.setAutoCommit(false);
            try {
                T result = work.run(new Held(connection));
                connection.commit();
                return result;
            }
            catch (SQLException | RuntimeException ex) {
                try {
                    connection.rollback();
                }
                catch (SQLException rollback) {
                    ex.addSuppressed(rollback);
                }
                throw ex;
            }
            finally {
                connection.setAutoCommit(true);
            }
        });
    }

    // The work with a connection: one an earlier statement gave back, or a new one. It is
    // given back afterwards unless it ended or was left in a transaction.
    @Override
    public <T> T run(Work<T> work) throws SQLException {
        Connection connection = take();
        boolean reusable = false;
        try {
            T result = work.run(connection);
            reusable = true;
            return result;
        }
        catch (SQLException ex) {
            // a statement the server refused leaves the connection as it was; a failure
            // of the connection itself (SQLSTATE class 08) does not
            String state = ex.getSQLState();
            reusable = state == null || !state.startsWith("08");
            throw ex;
        }
        finally {
            give(connection, reusable);
        }
    }

    // A connection that asks the server nothing it does not need: one given back within
    // CHECKED_WITHIN is taken as it is.
    private Connection take() throws SQLException {
        Idle kept;
        while ((kept = this.idle.pollFirst()) != null) {
            if (System.nanoTime() - kept.since() < CHECKED_WITHIN || kept.connection().isValid(5)) {
                return kept.connection();
            }
            closeQuietly(kept.connection());
        }
        return DriverManager.getConnection(this.url, this.properties);
    }

    private void give(Connection connection, boolean reusable) {
        try {
            if (reusable && !connection.isClosed() && connection.getAutoCommit() && this.idle.size() < KEPT) {
                this.idle.offerFirst(new Idle(connection, System.nanoTime()));
                return;
            }
        }
        catch (SQLException ex) {
            // closed below
        }
        closeQuietly(connection);
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        }
        catch (SQLException ex) {
            // it is gone either way
        }
    }

    // The connection a transaction holds: every statement of the work runs on it.
    private record Held(Connection connection) implements JdbcClient, Connector {

        @Override
        public StatementSpec sql(String sql) {
            return new StatementSpec(this, sql);
        }

        @Override
        public <T> T run(Work<T> work) throws SQLException {
            return work.run(this.connection);
        }

    }

}
