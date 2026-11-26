package am.ik.jdbc.spin.postgres;

import org.jspecify.annotations.Nullable;

/**
 * What one statement answered: rows under their columns, or a count. The host has a call
 * for each, so which one it is was asked for, not told by the server.
 */
final class Result {

	/** The rows, or {@code null} for a statement asked for its count. */
	final Postgres.@Nullable RowSet rows;

	/** The rows a command changed or took. */
	final long count;

	Result(Postgres.RowSet rows) {
		this.rows = rows;
		this.count = 0;
	}

	Result(long count) {
		this.rows = null;
		this.count = count;
	}

}
