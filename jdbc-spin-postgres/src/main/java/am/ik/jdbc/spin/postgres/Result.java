package am.ik.jdbc.spin.postgres;

import org.jspecify.annotations.Nullable;

/**
 * What one statement answered: rows under their columns, all of them or a stream of them,
 * or a count. The host has a call for each, so which one it is was asked for, not told by
 * the server.
 */
final class Result {

	/**
	 * The rows, or {@code null} for a statement asked for its count or read in pieces.
	 */
	final Postgres.@Nullable RowSet rows;

	/** The rows as the host reads them from the server, or {@code null}. */
	final Postgres.@Nullable QueryAsync streamed;

	/** The rows a command changed or took. */
	final long count;

	Result(Postgres.RowSet rows) {
		this.rows = rows;
		this.streamed = null;
		this.count = 0;
	}

	Result(Postgres.QueryAsync streamed) {
		this.rows = null;
		this.streamed = streamed;
		this.count = 0;
	}

	Result(long count) {
		this.rows = null;
		this.streamed = null;
		this.count = count;
	}

	/** Whether the statement answered rows, whole or in pieces. */
	boolean isRows() {
		return this.rows != null || this.streamed != null;
	}

}
