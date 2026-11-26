package am.ik.jdbc.postgresql;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import org.jspecify.annotations.Nullable;

/**
 * Every abstract member of {@link Connection} as a refusal by name, so that the class
 * under it holds what the driver does and nothing else. Written by a script from the
 * interface's signatures; a member is changed in the subclass, not here.
 */
abstract class UnsupportedConnection implements Connection {

	@Override
	public void abort(Executor a) throws SQLException {
		throw Failures.unsupported("Connection.abort");
	}

	@Override
	public void clearWarnings() throws SQLException {
		throw Failures.unsupported("Connection.clearWarnings");
	}

	@Override
	public void close() throws SQLException {
		throw Failures.unsupported("Connection.close");
	}

	@Override
	public void commit() throws SQLException {
		throw Failures.unsupported("Connection.commit");
	}

	@Override
	public Array createArrayOf(String a, Object[] b) throws SQLException {
		throw Failures.unsupported("Connection.createArrayOf");
	}

	@Override
	public Blob createBlob() throws SQLException {
		throw Failures.unsupported("Connection.createBlob");
	}

	@Override
	public Clob createClob() throws SQLException {
		throw Failures.unsupported("Connection.createClob");
	}

	@Override
	public NClob createNClob() throws SQLException {
		throw Failures.unsupported("Connection.createNClob");
	}

	@Override
	public SQLXML createSQLXML() throws SQLException {
		throw Failures.unsupported("Connection.createSQLXML");
	}

	@Override
	public Statement createStatement() throws SQLException {
		throw Failures.unsupported("Connection.createStatement");
	}

	@Override
	public Statement createStatement(int a, int b, int c) throws SQLException {
		throw Failures.unsupported("Connection.createStatement");
	}

	@Override
	public Statement createStatement(int a, int b) throws SQLException {
		throw Failures.unsupported("Connection.createStatement");
	}

	@Override
	public Struct createStruct(String a, Object[] b) throws SQLException {
		throw Failures.unsupported("Connection.createStruct");
	}

	@Override
	public boolean getAutoCommit() throws SQLException {
		throw Failures.unsupported("Connection.getAutoCommit");
	}

	@Override
	public String getCatalog() throws SQLException {
		throw Failures.unsupported("Connection.getCatalog");
	}

	@Override
	public Properties getClientInfo() throws SQLException {
		throw Failures.unsupported("Connection.getClientInfo");
	}

	@Override
	public @Nullable String getClientInfo(String a) throws SQLException {
		throw Failures.unsupported("Connection.getClientInfo");
	}

	@Override
	public int getHoldability() throws SQLException {
		throw Failures.unsupported("Connection.getHoldability");
	}

	@Override
	public DatabaseMetaData getMetaData() throws SQLException {
		throw Failures.unsupported("Connection.getMetaData");
	}

	@Override
	public int getNetworkTimeout() throws SQLException {
		throw Failures.unsupported("Connection.getNetworkTimeout");
	}

	@Override
	public @Nullable String getSchema() throws SQLException {
		throw Failures.unsupported("Connection.getSchema");
	}

	@Override
	public int getTransactionIsolation() throws SQLException {
		throw Failures.unsupported("Connection.getTransactionIsolation");
	}

	@Override
	public Map<String, Class<?>> getTypeMap() throws SQLException {
		throw Failures.unsupported("Connection.getTypeMap");
	}

	@Override
	public @Nullable SQLWarning getWarnings() throws SQLException {
		throw Failures.unsupported("Connection.getWarnings");
	}

	@Override
	public boolean isClosed() throws SQLException {
		throw Failures.unsupported("Connection.isClosed");
	}

	@Override
	public boolean isReadOnly() throws SQLException {
		throw Failures.unsupported("Connection.isReadOnly");
	}

	@Override
	public boolean isValid(int a) throws SQLException {
		throw Failures.unsupported("Connection.isValid");
	}

	@Override
	public boolean isWrapperFor(Class<?> a) throws SQLException {
		return a.isInstance(this);
	}

	@Override
	public String nativeSQL(String a) throws SQLException {
		throw Failures.unsupported("Connection.nativeSQL");
	}

	@Override
	public CallableStatement prepareCall(String a, int b, int c, int d) throws SQLException {
		throw Failures.unsupported("Connection.prepareCall");
	}

	@Override
	public CallableStatement prepareCall(String a, int b, int c) throws SQLException {
		throw Failures.unsupported("Connection.prepareCall");
	}

	@Override
	public CallableStatement prepareCall(String a) throws SQLException {
		throw Failures.unsupported("Connection.prepareCall");
	}

	@Override
	public PreparedStatement prepareStatement(String a, int[] b) throws SQLException {
		throw Failures.unsupported("Connection.prepareStatement");
	}

	@Override
	public PreparedStatement prepareStatement(String a, String[] b) throws SQLException {
		throw Failures.unsupported("Connection.prepareStatement");
	}

	@Override
	public PreparedStatement prepareStatement(String a, int b, int c, int d) throws SQLException {
		throw Failures.unsupported("Connection.prepareStatement");
	}

	@Override
	public PreparedStatement prepareStatement(String a, int b, int c) throws SQLException {
		throw Failures.unsupported("Connection.prepareStatement");
	}

	@Override
	public PreparedStatement prepareStatement(String a, int b) throws SQLException {
		throw Failures.unsupported("Connection.prepareStatement");
	}

	@Override
	public PreparedStatement prepareStatement(String a) throws SQLException {
		throw Failures.unsupported("Connection.prepareStatement");
	}

	@Override
	public void releaseSavepoint(Savepoint a) throws SQLException {
		throw Failures.unsupported("Connection.releaseSavepoint");
	}

	@Override
	public void rollback() throws SQLException {
		throw Failures.unsupported("Connection.rollback");
	}

	@Override
	public void rollback(Savepoint a) throws SQLException {
		throw Failures.unsupported("Connection.rollback");
	}

	@Override
	public void setAutoCommit(boolean a) throws SQLException {
		throw Failures.unsupported("Connection.setAutoCommit");
	}

	@Override
	public void setCatalog(String a) throws SQLException {
		throw Failures.unsupported("Connection.setCatalog");
	}

	@Override
	public void setHoldability(int a) throws SQLException {
		throw Failures.unsupported("Connection.setHoldability");
	}

	@Override
	public void setNetworkTimeout(Executor a, int b) throws SQLException {
		throw Failures.unsupported("Connection.setNetworkTimeout");
	}

	@Override
	public void setReadOnly(boolean a) throws SQLException {
		throw Failures.unsupported("Connection.setReadOnly");
	}

	@Override
	public Savepoint setSavepoint() throws SQLException {
		throw Failures.unsupported("Connection.setSavepoint");
	}

	@Override
	public Savepoint setSavepoint(String a) throws SQLException {
		throw Failures.unsupported("Connection.setSavepoint");
	}

	@Override
	public void setSchema(String a) throws SQLException {
		throw Failures.unsupported("Connection.setSchema");
	}

	@Override
	public void setTransactionIsolation(int a) throws SQLException {
		throw Failures.unsupported("Connection.setTransactionIsolation");
	}

	@Override
	public void setTypeMap(Map<String, Class<?>> a) throws SQLException {
		throw Failures.unsupported("Connection.setTypeMap");
	}

	@Override
	public <T> T unwrap(Class<T> a) throws SQLException {
		if (a.isInstance(this)) {
			return a.cast(this);
		}
		throw Failures.unsupported("An unwrap to " + a.getName());
	}

}
