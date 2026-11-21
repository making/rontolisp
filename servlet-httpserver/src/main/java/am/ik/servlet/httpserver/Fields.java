package am.ik.servlet.httpserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.Cookie;

/**
 * The header fields with a structure of their own: cookies, the languages a client
 * accepts, the parameters of a media type.
 */
final class Fields {

	private Fields() {
	}

	/**
	 * Reads the pairs of a {@code Cookie} field (RFC 6265, 4.2.1). A value keeps its
	 * quotes; a pair without a name, and one whose name a cookie cannot have, is left
	 * out.
	 * @param field the field's value
	 * @param into the list the cookies are added to
	 */
	static void cookies(String field, List<Cookie> into) {
		int length = field.length();
		int start = 0;
		while (start < length) {
			int end = field.indexOf(';', start);
			if (end < 0) {
				end = length;
			}
			int equals = field.indexOf('=', start);
			if (equals > start && equals < end) {
				String name = field.substring(start, equals).trim();
				String value = field.substring(equals + 1, end).trim();
				if (!name.isEmpty()) {
					try {
						into.add(new Cookie(name, value));
					}
					catch (IllegalArgumentException ex) {
						// Not a name of a cookie: the pair is not one.
					}
				}
			}
			start = end + 1;
		}
	}

	/**
	 * Writes a cookie as the value of a {@code Set-Cookie} field (RFC 6265, 4.1.1): the
	 * pair, then each attribute the cookie holds.
	 * @param cookie the cookie
	 * @return the field's value
	 */
	static String setCookie(Cookie cookie) {
		StringBuilder field = new StringBuilder();
		field.append(cookie.getName()).append('=');
		if (cookie.getValue() != null) {
			field.append(cookie.getValue());
		}
		for (Map.Entry<String, String> attribute : cookie.getAttributes().entrySet()) {
			field.append("; ").append(attribute.getKey());
			if (!attribute.getValue().isEmpty()) {
				field.append('=').append(attribute.getValue());
			}
		}
		return field.toString();
	}

	/**
	 * The languages of {@code Accept-Language} fields, the preferred first (RFC 9110,
	 * 12.5.4).
	 * @param fields the fields' values, or null
	 * @return the locales in the order of their weights; the default locale alone where
	 * the client names none
	 */
	static List<Locale> locales(List<String> fields) {
		List<Locale> locales = new ArrayList<>();
		List<Double> weights = new ArrayList<>();
		for (int f = 0; fields != null && f < fields.size(); f++) {
			String field = fields.get(f);
			int length = field.length();
			int start = 0;
			while (start < length) {
				int end = field.indexOf(',', start);
				if (end < 0) {
					end = length;
				}
				String range = field.substring(start, end).trim();
				double weight = 1.0;
				int semicolon = range.indexOf(';');
				if (semicolon >= 0) {
					String q = parameter(range, "q");
					range = range.substring(0, semicolon).trim();
					try {
						weight = q == null ? 1.0 : Double.parseDouble(q);
					}
					catch (NumberFormatException ex) {
						weight = 0.0;
					}
				}
				if (!range.isEmpty() && !range.equals("*") && weight > 0.0) {
					int at = locales.size();
					while (at > 0 && weights.get(at - 1) < weight) {
						at--;
					}
					locales.add(at, Locale.forLanguageTag(range));
					weights.add(at, weight);
				}
				start = end + 1;
			}
		}
		if (locales.isEmpty()) {
			locales.add(Locale.getDefault());
		}
		return locales;
	}

	/**
	 * A parameter of a field's value, as {@code charset} of
	 * {@code text/html; charset="UTF-8"}.
	 * @param value the field's value, or null
	 * @param name the parameter's name, in any case
	 * @return the parameter's value without its quotes, or null
	 */
	static String parameter(String value, String name) {
		if (value == null) {
			return null;
		}
		int[] range = parameterRange(value, name);
		if (range == null) {
			return null;
		}
		String found = value.substring(range[2], range[1]).trim();
		if (found.length() >= 2 && found.charAt(0) == '"' && found.charAt(found.length() - 1) == '"') {
			found = found.substring(1, found.length() - 1);
		}
		return found.isEmpty() ? null : found;
	}

	/**
	 * A field's value without one parameter.
	 * @param value the field's value
	 * @param name the parameter's name, in any case
	 * @return the value without that parameter and its semicolon
	 */
	static String withoutParameter(String value, String name) {
		int[] range = parameterRange(value, name);
		if (range == null) {
			return value;
		}
		String before = value.substring(0, range[0]);
		String after = range[1] < value.length() ? value.substring(range[1]) : "";
		return (before + after).trim();
	}

	/** Where a parameter is: its semicolon, its end, the start of its value. */
	private static int[] parameterRange(String value, String name) {
		int semicolon = value.indexOf(';');
		while (semicolon >= 0) {
			int end = value.indexOf(';', semicolon + 1);
			if (end < 0) {
				end = value.length();
			}
			int equals = value.indexOf('=', semicolon + 1);
			if (equals > 0 && equals < end && value.substring(semicolon + 1, equals).trim().equalsIgnoreCase(name)) {
				return new int[] { semicolon, end, equals + 1 };
			}
			semicolon = end < value.length() ? end : -1;
		}
		return null;
	}

}
