package am.ik.jdbc.spin.postgres;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.jspecify.annotations.Nullable;

/**
 * What a connection URL and its properties name, and the address the host is asked to
 * open for it.
 *
 * <p>
 * The URL's forms are the ones of a PostgreSQL driver: {@code jdbc:postgresql:database},
 * {@code jdbc:postgresql:/}, {@code jdbc:postgresql://host[:port]/[database]}, each with
 * {@code ?name=value&name=value} after it; a name and a value are percent-encoded UTF-8.
 * A setting of the URL wins over the same one of the properties. The host's address is
 * {@code postgres://user:password@host:port/database?sslmode=...}, the settings it knows
 * under its own names.
 */
final class Address {

	static final String PREFIX = "jdbc:postgresql:";

	final String url;

	final String database;

	final String user;

	/** The address the host opens. */
	final String host;

	/** A PEM certificate the server's is verified against, or {@code null}. */
	final @Nullable String rootCertificate;

	private Address(String url, String database, String user, String host, @Nullable String rootCertificate) {
		this.url = url;
		this.database = database;
		this.user = user;
		this.host = host;
		this.rootCertificate = rootCertificate;
	}

	/**
	 * Reads a URL.
	 * @return what it names, or {@code null} for a URL of another driver
	 */
	static @Nullable Address parse(@Nullable String url, @Nullable Properties info) throws SQLException {
		if (url == null) {
			throw new SQLException("The url is null", "08001");
		}
		if (!url.startsWith(PREFIX)) {
			return null;
		}
		Map<String, String> settings = new LinkedHashMap<>();
		if (info != null) {
			for (String name : info.stringPropertyNames()) {
				settings.put(name, info.getProperty(name));
			}
		}
		String rest = url.substring(PREFIX.length());
		int query = rest.indexOf('?');
		if (query >= 0) {
			for (String pair : rest.substring(query + 1).split("&")) {
				if (pair.isEmpty()) {
					continue;
				}
				int equals = pair.indexOf('=');
				String name = decode(equals < 0 ? pair : pair.substring(0, equals), true);
				settings.put(name, equals < 0 ? "" : decode(pair.substring(equals + 1), true));
			}
			rest = rest.substring(0, query);
		}
		String server = "localhost";
		int port = 5432;
		String database = rest;
		if (rest.startsWith("//")) {
			int slash = rest.indexOf('/', 2);
			String authority = slash < 0 ? rest.substring(2) : rest.substring(2, slash);
			database = slash < 0 ? "" : rest.substring(slash + 1);
			if (authority.indexOf(',') >= 0) {
				throw Failures.unsupported("A connection URL naming more than one server");
			}
			// An IPv6 address is in brackets, so its colons are not the port's.
			int colon = authority.lastIndexOf(':');
			if (colon >= 0 && colon > authority.lastIndexOf(']')) {
				port = port(authority.substring(colon + 1), url);
				authority = authority.substring(0, colon);
			}
			if (!authority.isEmpty()) {
				server = authority;
			}
		}
		else if (rest.equals("/")) {
			database = "";
		}
		String user = settings.get("user");
		if (user == null || user.isEmpty()) {
			user = System.getProperty("user.name");
		}
		if (user == null || user.isEmpty()) {
			throw new SQLInvalidAuthorizationSpecException("The connection names no user", "28000");
		}
		database = decode(database, false);
		if (database.isEmpty()) {
			database = user;
		}
		StringBuilder host = new StringBuilder("postgres://").append(encode(user));
		String password = settings.get("password");
		if (password != null && !password.isEmpty()) {
			host.append(':').append(encode(password));
		}
		host.append('@').append(server).append(':').append(port).append('/').append(encode(database));
		char separator = '?';
		String mode = mode(settings);
		if (mode != null) {
			host.append(separator).append("sslmode=").append(mode);
			separator = '&';
		}
		separator = setting(host, separator, "application_name", settings.get("ApplicationName"));
		separator = setting(host, separator, "options", settings.get("options"));
		String wait = settings.get("connectTimeout");
		setting(host, separator, "connect_timeout", wait != null ? wait : settings.get("loginTimeout"));
		String certificate = settings.get("sslrootcert");
		if (certificate != null && !certificate.contains("-----BEGIN ")) {
			throw Failures
				.unsupported("A file's path as sslrootcert (the host verifies: it takes the certificate's PEM text)");
		}
		return new Address(url, database, user, host.toString(), certificate);
	}

	// The host knows three modes and verifies the server's certificate and name
	// whenever it speaks TLS, so the two verifying modes of other drivers are its
	// require.
	private static @Nullable String mode(Map<String, String> settings) throws SQLException {
		String mode = settings.get("sslmode");
		if (mode == null) {
			String ssl = settings.get("ssl");
			return ssl == null ? null : ssl.equals("false") ? "disable" : "require";
		}
		return switch (mode) {
			case "disable", "prefer", "require" -> mode;
			case "verify-ca", "verify-full" -> "require";
			default -> throw Failures.unsupported("sslmode=" + mode + " (the host takes disable, prefer and require)");
		};
	}

	private static char setting(StringBuilder host, char separator, String name, @Nullable String value) {
		if (value == null || value.isEmpty()) {
			return separator;
		}
		host.append(separator).append(name).append('=').append(encode(value));
		return '&';
	}

	private static int port(String text, String url) throws SQLException {
		try {
			int port = Integer.parseInt(text);
			if (port > 0 && port < 65536) {
				return port;
			}
		}
		catch (NumberFormatException ex) {
			// Refused below.
		}
		throw new SQLException("The url names no port a server can listen on: " + url, "08001");
	}

	// Percent-encoding of UTF-8: a letter, a digit and - . _ ~ stay as they are.
	private static String encode(String text) {
		StringBuilder encoded = new StringBuilder(text.length() + 8);
		for (byte octet : text.getBytes(StandardCharsets.UTF_8)) {
			int c = octet & 0xff;
			if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_'
					|| c == '~') {
				encoded.append((char) c);
			}
			else {
				encoded.append('%')
					.append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
					.append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
			}
		}
		return encoded.toString();
	}

	// Percent-decoding of UTF-8; in a query a plus sign is a space, as in a form.
	private static String decode(String text, boolean form) throws SQLException {
		if (text.indexOf('%') < 0 && (!form || text.indexOf('+') < 0)) {
			return text;
		}
		byte[] source = text.getBytes(StandardCharsets.UTF_8);
		byte[] octets = new byte[source.length];
		int length = 0;
		boolean malformed = false;
		for (int i = 0; i < source.length && !malformed; i++) {
			int octet = source[i];
			if (octet == '%') {
				int high = i + 2 < source.length ? Character.digit(source[i + 1], 16) : -1;
				int low = i + 2 < source.length ? Character.digit(source[i + 2], 16) : -1;
				malformed = high < 0 || low < 0;
				octet = high * 16 + low;
				i += 2;
			}
			else if (form && octet == '+') {
				octet = ' ';
			}
			octets[length++] = (byte) octet;
		}
		if (malformed) {
			throw new SQLException("A percent sign of the url is followed by no two hexadecimal digits: " + text,
					"08001");
		}
		return new String(octets, 0, length, StandardCharsets.UTF_8);
	}

}
