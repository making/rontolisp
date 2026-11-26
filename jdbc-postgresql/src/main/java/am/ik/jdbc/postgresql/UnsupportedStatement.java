package am.ik.jdbc.postgresql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;

import org.jspecify.annotations.Nullable;

/**
 * Every abstract member of {@link Statement} as a refusal by name, so that the class
 * under it holds what the driver does and nothing else. Written by a script from the
 * interface's signatures; a member is changed in the subclass, not here.
 */
abstract class UnsupportedStatement implements Statement {

	@Override
	public void addBatch(String a) throws SQLException {
		throw Failures.unsupported("Statement.addBatch");
	}

	@Override
	public void cancel() throws SQLException {
		throw Failures.unsupported("Statement.cancel");
	}

	@Override
	public void clearBatch() throws SQLException {
		throw Failures.unsupported("Statement.clearBatch");
	}

	@Override
	public void clearWarnings() throws SQLException {
		throw Failures.unsupported("Statement.clearWarnings");
	}

	@Override
	public void close() throws SQLException {
		throw Failures.unsupported("Statement.close");
	}

	@Override
	public void closeOnCompletion() throws SQLException {
		throw Failures.unsupported("Statement.closeOnCompletion");
	}

	@Override
	public boolean execute(String a, int[] b) throws SQLException {
		throw Failures.unsupported("Statement.execute");
	}

	@Override
	public boolean execute(String a, String[] b) throws SQLException {
		throw Failures.unsupported("Statement.execute");
	}

	@Override
	public boolean execute(String a, int b) throws SQLException {
		throw Failures.unsupported("Statement.execute");
	}

	@Override
	public boolean execute(String a) throws SQLException {
		throw Failures.unsupported("Statement.execute");
	}

	@Override
	public int[] executeBatch() throws SQLException {
		throw Failures.unsupported("Statement.executeBatch");
	}

	@Override
	public ResultSet executeQuery(String a) throws SQLException {
		throw Failures.unsupported("Statement.executeQuery");
	}

	@Override
	public int executeUpdate(String a, int[] b) throws SQLException {
		throw Failures.unsupported("Statement.executeUpdate");
	}

	@Override
	public int executeUpdate(String a, String[] b) throws SQLException {
		throw Failures.unsupported("Statement.executeUpdate");
	}

	@Override
	public int executeUpdate(String a, int b) throws SQLException {
		throw Failures.unsupported("Statement.executeUpdate");
	}

	@Override
	public int executeUpdate(String a) throws SQLException {
		throw Failures.unsupported("Statement.executeUpdate");
	}

	@Override
	public Connection getConnection() throws SQLException {
		throw Failures.unsupported("Statement.getConnection");
	}

	@Override
	public int getFetchDirection() throws SQLException {
		throw Failures.unsupported("Statement.getFetchDirection");
	}

	@Override
	public int getFetchSize() throws SQLException {
		throw Failures.unsupported("Statement.getFetchSize");
	}

	@Override
	public ResultSet getGeneratedKeys() throws SQLException {
		throw Failures.unsupported("Statement.getGeneratedKeys");
	}

	@Override
	public int getMaxFieldSize() throws SQLException {
		throw Failures.unsupported("Statement.getMaxFieldSize");
	}

	@Override
	public int getMaxRows() throws SQLException {
		throw Failures.unsupported("Statement.getMaxRows");
	}

	@Override
	public boolean getMoreResults() throws SQLException {
		throw Failures.unsupported("Statement.getMoreResults");
	}

	@Override
	public boolean getMoreResults(int a) throws SQLException {
		throw Failures.unsupported("Statement.getMoreResults");
	}

	@Override
	public int getQueryTimeout() throws SQLException {
		throw Failures.unsupported("Statement.getQueryTimeout");
	}

	@Override
	public @Nullable ResultSet getResultSet() throws SQLException {
		throw Failures.unsupported("Statement.getResultSet");
	}

	@Override
	public int getResultSetConcurrency() throws SQLException {
		throw Failures.unsupported("Statement.getResultSetConcurrency");
	}

	@Override
	public int getResultSetHoldability() throws SQLException {
		throw Failures.unsupported("Statement.getResultSetHoldability");
	}

	@Override
	public int getResultSetType() throws SQLException {
		throw Failures.unsupported("Statement.getResultSetType");
	}

	@Override
	public int getUpdateCount() throws SQLException {
		throw Failures.unsupported("Statement.getUpdateCount");
	}

	@Override
	public @Nullable SQLWarning getWarnings() throws SQLException {
		throw Failures.unsupported("Statement.getWarnings");
	}

	@Override
	public boolean isCloseOnCompletion() throws SQLException {
		throw Failures.unsupported("Statement.isCloseOnCompletion");
	}

	@Override
	public boolean isClosed() throws SQLException {
		throw Failures.unsupported("Statement.isClosed");
	}

	@Override
	public boolean isPoolable() throws SQLException {
		throw Failures.unsupported("Statement.isPoolable");
	}

	@Override
	public boolean isWrapperFor(Class<?> a) throws SQLException {
		return a.isInstance(this);
	}

	@Override
	public void setCursorName(String a) throws SQLException {
		throw Failures.unsupported("Statement.setCursorName");
	}

	@Override
	public void setEscapeProcessing(boolean a) throws SQLException {
		throw Failures.unsupported("Statement.setEscapeProcessing");
	}

	@Override
	public void setFetchDirection(int a) throws SQLException {
		throw Failures.unsupported("Statement.setFetchDirection");
	}

	@Override
	public void setFetchSize(int a) throws SQLException {
		throw Failures.unsupported("Statement.setFetchSize");
	}

	@Override
	public void setMaxFieldSize(int a) throws SQLException {
		throw Failures.unsupported("Statement.setMaxFieldSize");
	}

	@Override
	public void setMaxRows(int a) throws SQLException {
		throw Failures.unsupported("Statement.setMaxRows");
	}

	@Override
	public void setPoolable(boolean a) throws SQLException {
		throw Failures.unsupported("Statement.setPoolable");
	}

	@Override
	public void setQueryTimeout(int a) throws SQLException {
		throw Failures.unsupported("Statement.setQueryTimeout");
	}

	@Override
	public <T> T unwrap(Class<T> a) throws SQLException {
		if (a.isInstance(this)) {
			return a.cast(this);
		}
		throw Failures.unsupported("An unwrap to " + a.getName());
	}

}
