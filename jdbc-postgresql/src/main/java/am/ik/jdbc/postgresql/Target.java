package am.ik.jdbc.postgresql;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.jspecify.annotations.Nullable;

/**
 * What a connection URL and its properties name: the server, the database, the user and
 * the settings of the connection.
 *
 * <p>
 * The forms are {@code jdbc:postgresql:database}, {@code jdbc:postgresql:/},
 * {@code jdbc:postgresql://host[:port]/[database]}, each with
 * {@code ?name=value&name=value} after it; a name and a value are percent-encoded UTF-8.
 * A setting of the URL wins over the same one of the properties.
 */
final class Target {

	static final String PREFIX = "jdbc:postgresql:";

	final String url;

	final String host;

	final int port;

	final String database;

	final String user;

	final @Nullable String password;

	private final Map<String, String> settings;

	private Target(String url, String host, int port, String database, Map<String, String> settings)
			throws SQLException {
		this.url = url;
		this.host = host;
		this.port = port;
		this.settings = settings;
		String named = settings.get("user");
		this.user = named != null ? named : System.getProperty("user.name");
		if (this.user == null || this.user.isEmpty()) {
			throw Failures.login("The connection names no user");
		}
		this.password = settings.get("password");
		this.database = database.isEmpty() ? this.user : database;
	}

	/**
	 * Reads a URL.
	 * @return what it names, or {@code null} for a URL of another driver
	 */
	static @Nullable Target parse(String url, @Nullable Properties info) throws SQLException {
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
		String host = "localhost";
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
			if (authority.startsWith("[") && authority.endsWith("]")) {
				authority = authority.substring(1, authority.length() - 1);
			}
			if (!authority.isEmpty()) {
				host = authority;
			}
		}
		else if (rest.equals("/")) {
			database = "";
		}
		return new Target(url, host, port, decode(database, false), settings);
	}

	/** A setting, or {@code null} when neither the URL nor the properties name it. */
	@Nullable String setting(String name) {
		return this.settings.get(name);
	}

	/** A setting that counts seconds, in milliseconds; 0 when it is not named. */
	int millis(String name) throws SQLException {
		String value = this.settings.get(name);
		if (value == null || value.isEmpty()) {
			return 0;
		}
		try {
			long millis = (long) (Double.parseDouble(value) * 1000.0);
			if (millis < 0 || millis > Integer.MAX_VALUE) {
				throw new NumberFormatException(value);
			}
			return (int) millis;
		}
		catch (NumberFormatException ex) {
			throw new SQLException("The setting " + name + " is no count of seconds: " + value, "08001");
		}
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

	// Percent-decoding of UTF-8; in a query a plus sign is a space, as in a form.
	private static String decode(String text, boolean form) throws SQLException {
		if (text.indexOf('%') < 0 && (!form || text.indexOf('+') < 0)) {
			return text;
		}
		byte[] source = text.getBytes(StandardCharsets.UTF_8);
		byte[] octets = new byte[source.length];
		int length = 0;
		for (int i = 0; i < source.length; i++) {
			int octet = source[i];
			if (octet == '%') {
				int high = i + 2 < source.length ? Character.digit(source[i + 1], 16) : -1;
				int low = i + 2 < source.length ? Character.digit(source[i + 2], 16) : -1;
				if (high < 0 || low < 0) {
					throw new SQLException(
							"A percent sign of the url is followed by no two hexadecimal digits: " + text, "08001");
				}
				octet = high * 16 + low;
				i += 2;
			}
			else if (form && octet == '+') {
				octet = ' ';
			}
			octets[length++] = (byte) octet;
		}
		return new String(octets, 0, length, StandardCharsets.UTF_8);
	}

}
