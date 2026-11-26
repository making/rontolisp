package am.ik.jdbc.postgresql;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLWarning;

/**
 * The exceptions of the driver: each carries the SQLSTATE of what failed and is of the
 * class the JDBC specification gives that state's class ("SQLException", the categories
 * of non-transient and transient exceptions).
 */
final class Failures {

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

	/** A message of the server that the protocol does not allow where it came. */
	static SQLException protocol(String message) {
		return new SQLNonTransientConnectionException(message, "08P01");
	}

	static SQLException protocol(String message, Throwable cause) {
		return new SQLNonTransientConnectionException(message, "08P01", cause);
	}

	/** A login the client ends itself. */
	static SQLException login(String message) {
		return new SQLInvalidAuthorizationSpecException(message, "28000");
	}

	/** The connection failed under an exchange: it is of no use afterwards. */
	static SQLException broken(IOException cause) {
		if (cause instanceof SocketTimeoutException) {
			return new SQLTimeoutException("The server did not answer in time: " + cause.getMessage(), "08006", cause);
		}
		return new SQLNonTransientConnectionException("The connection to the server failed: " + cause.getMessage(),
				"08006", cause);
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

	/**
	 * Reads the fields of an ErrorResponse or a NoticeResponse: each an octet naming it
	 * and a text, to a zero octet.
	 * @return the fields by their octets: S severity, V the same unlocalized, C the
	 * state, M the message, D the detail, H the hint, P the position
	 */
	static String[] fields(Wire wire) throws IOException {
		String[] fields = new String[128];
		for (int kind = wire.octet(); kind != 0; kind = wire.octet()) {
			String value = wire.text();
			if (kind < fields.length) {
				fields[kind] = value;
			}
		}
		return fields;
	}

	/** The exception of an ErrorResponse. */
	static SQLException error(String[] fields, boolean timed) {
		String state = fields['C'] != null ? fields['C'] : "XX000";
		String message = text(fields);
		if (timed && state.equals("57014")) {
			return new SQLTimeoutException(message, state);
		}
		return of(message, state);
	}

	/** The warning of a NoticeResponse. */
	static SQLWarning warning(String[] fields) {
		return new SQLWarning(text(fields), fields['C'] != null ? fields['C'] : "01000");
	}

	private static String text(String[] fields) {
		String severity = fields['V'] != null ? fields['V'] : fields['S'];
		StringBuilder text = new StringBuilder();
		if (severity != null) {
			text.append(severity).append(": ");
		}
		text.append(fields['M']);
		if (fields['D'] != null) {
			text.append("\n  Detail: ").append(fields['D']);
		}
		if (fields['H'] != null) {
			text.append("\n  Hint: ").append(fields['H']);
		}
		if (fields['P'] != null) {
			text.append("\n  Position: ").append(fields['P']);
		}
		return text.toString();
	}

}
