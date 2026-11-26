package am.ik.servlet.httpserver;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The percent encoding of a request: a path's segments and the pairs of a query or a
 * form.
 */
final class UrlCodec {

	private UrlCodec() {
	}

	/**
	 * The path a request is mapped by (Servlet 6.1, 3.5.2): each segment without its path
	 * parameters, decoded as UTF-8, the empty segments but the last removed, then
	 * {@code .} and {@code ..} resolved.
	 * @param raw the path as the request line has it
	 * @return the canonical path, or null for a path the specification has rejected: one
	 * not starting with a slash, a broken escape or octet sequence, a control character,
	 * an encoded slash, a backslash, a {@code ..} above the root, a dot segment that is
	 * encoded or has parameters, and an empty segment with parameters that is not the
	 * last
	 */
	static @Nullable String canonicalPath(String raw) {
		if (raw == null || raw.isEmpty() || raw.charAt(0) != '/') {
			return null;
		}
		List<String> segments = new ArrayList<>();
		int length = raw.length();
		int start = 1;
		boolean directory = false;
		while (start <= length) {
			int end = raw.indexOf('/', start);
			if (end < 0) {
				end = length;
			}
			String segment = raw.substring(start, end);
			boolean last = end == length;
			boolean encoded = segment.indexOf('%') >= 0;
			String decoded = encoded ? decode(segment, StandardCharsets.UTF_8, false) : segment;
			if (decoded == null || refused(decoded)) {
				return null;
			}
			int parameters = segment.indexOf(';');
			if (parameters >= 0) {
				segment = segment.substring(0, parameters);
				encoded = segment.indexOf('%') >= 0;
				decoded = encoded ? decode(segment, StandardCharsets.UTF_8, false) : segment;
				if (decoded == null) {
					return null;
				}
			}
			if (decoded.isEmpty()) {
				if (last) {
					directory = true;
				}
				else if (parameters >= 0) {
					return null;
				}
			}
			else if (decoded.equals(".") || decoded.equals("..")) {
				if (parameters >= 0 || encoded || (decoded.length() == 2 && segments.isEmpty())) {
					return null;
				}
				if (decoded.length() == 2) {
					segments.removeLast();
				}
			}
			else {
				segments.add(decoded);
			}
			start = end + 1;
		}
		if (segments.isEmpty()) {
			return "/";
		}
		StringBuilder path = new StringBuilder(raw.length());
		for (int i = 0; i < segments.size(); i++) {
			path.append('/').append(segments.get(i));
		}
		if (directory) {
			path.append('/');
		}
		return path.toString();
	}

	private static boolean refused(String segment) {
		for (int i = 0; i < segment.length(); i++) {
			char c = segment.charAt(i);
			if (c < 0x20 || c == 0x7F || c == '/' || c == '\\') {
				return true;
			}
		}
		return false;
	}

	/**
	 * Reads the pairs of a query string or of a form's body into a map of names to
	 * values, in their order.
	 * @param text the pairs, separated by {@code &}
	 * @param charset the charset of the escaped octets
	 * @param into the map the values are added to
	 * @throws IllegalStateException for a broken escape or octet sequence
	 */
	static void parameters(String text, Charset charset, Map<String, List<String>> into) {
		int length = text.length();
		int start = 0;
		while (start <= length) {
			int end = text.indexOf('&', start);
			if (end < 0) {
				end = length;
			}
			if (end > start) {
				int equals = text.indexOf('=', start);
				String name;
				String value;
				if (equals < 0 || equals > end) {
					name = text.substring(start, end);
					value = "";
				}
				else {
					name = text.substring(start, equals);
					value = text.substring(equals + 1, end);
				}
				name = decode(name, charset, true);
				value = decode(value, charset, true);
				if (name == null || value == null) {
					throw new IllegalStateException("a parameter is not percent-encoded " + charset.name());
				}
				List<String> values = into.get(name);
				if (values == null) {
					values = new ArrayList<>(1);
					into.put(name, values);
				}
				values.add(value);
			}
			start = end + 1;
		}
	}

	/**
	 * Decodes the escapes of a text.
	 * @param text the text with {@code %XX} escapes
	 * @param charset the charset of the octets
	 * @param form whether a plus sign is a space
	 * @return the decoded text, or null for a broken escape or, in UTF-8, a broken octet
	 * sequence
	 */
	static @Nullable String decode(String text, Charset charset, boolean form) {
		int length = text.length();
		boolean plain = true;
		for (int i = 0; i < length && plain; i++) {
			char c = text.charAt(i);
			plain = c != '%' && (c != '+' || !form) && c < 0x80;
		}
		if (plain) {
			return text;
		}
		byte[] octets = new byte[length * 3];
		int count = 0;
		for (int i = 0; i < length; i++) {
			char c = text.charAt(i);
			if (c == '%') {
				if (i + 2 >= length) {
					return null;
				}
				int high = hex(text.charAt(i + 1));
				int low = hex(text.charAt(i + 2));
				if (high < 0 || low < 0) {
					return null;
				}
				octets[count++] = (byte) (high << 4 | low);
				i += 2;
			}
			else if (c == '+' && form) {
				octets[count++] = ' ';
			}
			else if (c < 0x80) {
				octets[count++] = (byte) c;
			}
			else {
				// A character sent unescaped is the octets it has in the charset.
				int end = i + 1;
				if (Character.isHighSurrogate(c) && end < length && Character.isLowSurrogate(text.charAt(end))) {
					end++;
				}
				byte[] encoded = text.substring(i, end).getBytes(charset);
				System.arraycopy(encoded, 0, octets, count, encoded.length);
				count += encoded.length;
				i = end - 1;
			}
		}
		if ("UTF-8".equals(charset.name()) && !utf8(octets, count)) {
			return null;
		}
		return new String(octets, 0, count, charset);
	}

	private static int hex(char c) {
		if (c >= '0' && c <= '9') {
			return c - '0';
		}
		if (c >= 'a' && c <= 'f') {
			return c - 'a' + 10;
		}
		if (c >= 'A' && c <= 'F') {
			return c - 'A' + 10;
		}
		return -1;
	}

	/** Whether octets are well-formed UTF-8 (RFC 3629: shortest forms, no surrogates). */
	static boolean utf8(byte[] octets, int count) {
		int i = 0;
		while (i < count) {
			int lead = octets[i] & 0xFF;
			int more;
			int least;
			if (lead < 0x80) {
				i++;
				continue;
			}
			else if (lead >= 0xC2 && lead <= 0xDF) {
				more = 1;
				least = 0x80;
			}
			else if (lead >= 0xE0 && lead <= 0xEF) {
				more = 2;
				least = 0x800;
			}
			else if (lead >= 0xF0 && lead <= 0xF4) {
				more = 3;
				least = 0x10000;
			}
			else {
				return false;
			}
			if (i + more >= count) {
				return false;
			}
			int point = lead & (0x3F >> more);
			for (int k = 1; k <= more; k++) {
				int next = octets[i + k] & 0xFF;
				if ((next & 0xC0) != 0x80) {
					return false;
				}
				point = point << 6 | (next & 0x3F);
			}
			if (point < least || point > 0x10FFFF || (point >= 0xD800 && point <= 0xDFFF)) {
				return false;
			}
			i += more + 1;
		}
		return true;
	}

}
