package am.ik.jdbc.spin.postgres;

import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;

import am.ik.rontolisp.wit.WitException;
import org.jspecify.annotations.Nullable;

/**
 * The exceptions of the driver: each carries the SQLSTATE of what failed and is of the
 * class the JDBC specification gives that state's class ("SQLException", the categories
 * of non-transient and transient exceptions). What the host answers in its error arm is
 * read here.
 */
final class Failures {

	// How the host begins the text of a server's error it hands over as text: the
	// fields
	// follow, written as Rust writes a structure for debugging.
	private static final String WRAPPED = "DbError { as-text: ";

	// What the host says of a value it has no type for.
	private static final String NO_TYPE = "cannot convert between the Rust type";

	private Failures() {
	}

	/**
	 * The exception of a state.
	 * @param message what failed
	 * @param state the five characters of its SQLSTATE
	 */
	static SQLException of(String message, String state) {
		if (state.startsWith("0A")) {
			return new SQLFeatureNotSupportedException(message, state);
		}
		if (state.startsWith("08")) {
			return new SQLNonTransientConnectionException(message, state);
		}
		if (state.startsWith("22")) {
			return new SQLDataException(message, state);
		}
		if (state.startsWith("23")) {
			return new SQLIntegrityConstraintViolationException(message, state);
		}
		if (state.startsWith("28")) {
			return new SQLInvalidAuthorizationSpecException(message, state);
		}
		if (state.startsWith("42")) {
			return new SQLSyntaxErrorException(message, state);
		}
		if (state.startsWith("40")) {
			return new SQLTransactionRollbackException(message, state);
		}
		return new SQLException(message, state);
	}

	/** What the driver does not do, named. */
	static SQLFeatureNotSupportedException unsupported(String what) {
		return new SQLFeatureNotSupportedException(what + " is not supported", "0A000");
	}

	static SQLException closed(String what) {
		return new SQLNonTransientConnectionException("This " + what + " is closed", "08003");
	}

	/** A value that is not what a call asks for. */
	static SQLException data(String message) {
		return new SQLDataException(message, "22000");
	}

	static SQLException data(String message, Throwable cause) {
		return new SQLDataException(message, "22000", cause);
	}

	/** The host opened no connection: its words are all there is, no SQLSTATE. */
	static SQLException connecting(WitException refused) {
		String text = refused.error() instanceof Postgres.Error.ConnectionFailed(String words) ? words : words(refused);
		SQLException failure = new SQLNonTransientConnectionException("The host opened no connection: " + text,
				"08001");
		failure.initCause(refused);
		return failure;
	}

	/**
	 * The exception of a statement the host answered in its error arm.
	 * @param timed whether the statement ran under a limit on its time
	 */
	static SQLException of(WitException refused, boolean timed) {
		SQLException failure = read(refused.error(), timed);
		if (failure == null) {
			failure = new SQLException(words(refused), "58000");
		}
		failure.initCause(refused);
		return failure;
	}

	/**
	 * Whether a refusal is the server's own, which aborts the transaction it is in; the
	 * host's refusal of a value it cannot convert leaves the transaction as it was.
	 */
	static boolean server(WitException refused) {
		Object error = refused.error();
		return error instanceof Postgres.Error.QueryFailed(Postgres.QueryError.DbError(Postgres.DbError server))
				|| error instanceof Postgres.Error.QueryFailed(Postgres.QueryError.Text(String text))
						&& text.contains(WRAPPED);
	}

	private static @Nullable SQLException read(@Nullable Object error, boolean timed) {
		if (error instanceof Postgres.Error.QueryFailed(Postgres.QueryError.DbError(Postgres.DbError server))) {
			String hint = null;
			for (Postgres.Extra extra : server.extras()) {
				if (extra.name().equals("hint")) {
					hint = extra.value();
				}
			}
			return server(server.severity(), server.code(), server.message(), server.detail().orElse(null), hint,
					timed);
		}
		if (error instanceof Postgres.Error.QueryFailed(Postgres.QueryError.Text(String text))) {
			return text(text, timed);
		}
		if (error instanceof Postgres.Error.ConnectionFailed(String text)) {
			return new SQLNonTransientConnectionException("The host's connection failed: " + text, "08006");
		}
		if (error instanceof Postgres.Error.BadParameter(String text)) {
			return new SQLDataException("The host took a parameter for no value: " + text, "22023");
		}
		if (error instanceof Postgres.Error.ValueConversionFailed(String text)) {
			return data("The host converted no value: " + text);
		}
		if (error instanceof Postgres.Error.Other(String text)) {
			return new SQLException(text, "58000");
		}
		return null;
	}

	// The server's refusal: its SQLSTATE decides the class, the text is the server's.
	private static SQLException server(String severity, String state, String message, @Nullable String detail,
			@Nullable String hint, boolean timed) {
		StringBuilder text = new StringBuilder();
		if (!severity.isEmpty()) {
			text.append(severity).append(": ");
		}
		text.append(message);
		if (detail != null) {
			text.append("\n  Detail: ").append(detail);
		}
		if (hint != null) {
			text.append("\n  Hint: ").append(hint);
		}
		String code = state.isEmpty() ? "XX000" : state;
		if (timed && code.equals("57014")) {
			return new SQLTimeoutException(text.toString(), code);
		}
		return of(text.toString(), code);
	}

	// A refusal the host words itself. Three are told apart: a server's error it hands
	// over as text (one raised while the rows are read), a value of a type it has no
	// case for, and a value of a type it has a case for and cannot read.
	private static SQLException text(String text, boolean timed) {
		int wrapped = text.indexOf(WRAPPED);
		if (wrapped >= 0) {
			SQLException server = wrapped(text, wrapped + WRAPPED.length(), timed);
			if (server != null) {
				return server;
			}
		}
		int noType = text.indexOf(NO_TYPE);
		if (noType >= 0) {
			String type = between(text, "the Postgres type `", "`", noType);
			if (text.contains("deserializing")) {
				return new SQLFeatureNotSupportedException("The host reads no value of the type "
						+ (type != null ? type : "of a column")
						+ ": cast the column in the statement (a timestamptz as x AT TIME ZONE 'UTC', any type as x::text)",
						"0A000");
			}
			return new SQLException("The host has no value for a parameter the server reads as "
					+ (type != null ? type : "another type") + ": " + text, "07006");
		}
		if (text.contains("deserializing")) {
			return new SQLFeatureNotSupportedException(
					"The host cannot read a value of the result (cast the column to text in the statement): " + text,
					"0A000");
		}
		if (text.contains("WrongType")) {
			return new SQLException("The host has no value for a parameter of the type the server reads it as: " + text,
					"07006");
		}
		return new SQLException(text, "58000");
	}

	// The fields of a server's error in the host's text: as-text, severity, code,
	// message, each a string in quotes, then detail, None or Some(a string).
	private static @Nullable SQLException wrapped(String text, int start, boolean timed) {
		int[] at = { start };
		if (quoted(text, at) == null) {
			return null;
		}
		String severity = field(text, at, ", severity: ");
		String state = field(text, at, ", code: ");
		String message = field(text, at, ", message: ");
		if (severity == null || state == null || message == null) {
			return null;
		}
		String detail = text.startsWith(", detail: Some(", at[0]) ? field(text, at, ", detail: Some(") : null;
		int hinted = text.indexOf("(\"hint\", ", at[0]);
		String hint = null;
		if (hinted >= 0) {
			at[0] = hinted;
			hint = field(text, at, "(\"hint\", ");
		}
		return server(severity, state, message, detail, hint, timed);
	}

	private static @Nullable String field(String text, int[] at, String name) {
		if (!text.startsWith(name, at[0])) {
			return null;
		}
		at[0] += name.length();
		return quoted(text, at);
	}

	// A string as Rust writes one for debugging, from its opening quote at at[0]: a
	// quote, a backslash and the control characters are escaped, a code point as
	// backslash, u and its hexadecimal digits in braces.
	private static @Nullable String quoted(String text, int[] at) {
		int i = at[0];
		if (i >= text.length() || text.charAt(i) != '"') {
			return null;
		}
		StringBuilder value = new StringBuilder();
		for (i++; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"') {
				at[0] = i + 1;
				return value.toString();
			}
			if (c != '\\' || i + 1 >= text.length()) {
				value.append(c);
				continue;
			}
			char escaped = text.charAt(++i);
			switch (escaped) {
				case 'n' -> value.append('\n');
				case 'r' -> value.append('\r');
				case 't' -> value.append('\t');
				case '0' -> value.append('\0');
				case 'u' -> {
					int close = text.indexOf('}', i);
					if (close < 0 || i + 1 >= text.length() || text.charAt(i + 1) != '{') {
						return null;
					}
					try {
						value.appendCodePoint(Integer.parseInt(text.substring(i + 2, close), 16));
					}
					catch (IllegalArgumentException ex) {
						return null;
					}
					i = close;
				}
				default -> value.append(escaped);
			}
		}
		return null;
	}

	private static @Nullable String between(String text, String before, String after, int from) {
		int start = text.indexOf(before, from);
		if (start < 0) {
			return null;
		}
		int end = text.indexOf(after, start + before.length());
		return end < 0 ? null : text.substring(start + before.length(), end);
	}

	// What an error arm the driver has no reading of says.
	private static String words(WitException refused) {
		Object error = refused.error();
		return error != null ? error.toString() : String.valueOf(refused.getMessage());
	}

}
