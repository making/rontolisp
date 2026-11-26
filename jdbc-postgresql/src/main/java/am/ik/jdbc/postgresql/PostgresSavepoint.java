package am.ik.jdbc.postgresql;

import java.sql.SQLException;
import java.sql.Savepoint;

import org.jspecify.annotations.Nullable;

/**
 * A savepoint: one the program named, or one the connection numbered.
 */
final class PostgresSavepoint implements Savepoint {

	private final int id;

	private final @Nullable String name;

	PostgresSavepoint(int id, @Nullable String name) {
		this.id = id;
		this.name = name;
	}

	@Override
	public int getSavepointId() throws SQLException {
		if (this.name != null) {
			throw new SQLException("The savepoint has a name and no number", "3B000");
		}
		return this.id;
	}

	@Override
	public String getSavepointName() throws SQLException {
		if (this.name == null) {
			throw new SQLException("The savepoint has a number and no name", "3B000");
		}
		return this.name;
	}

	/** The name the server knows it by, quoted. */
	String identifier() {
		return Sql.identifier(this.name != null ? this.name : "jdbc_savepoint_" + this.id);
	}

}
