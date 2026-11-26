package am.ik.servlet.httpserver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * A body of {@code multipart/form-data} read as a stream (RFC 7578 over the multipart
 * syntax of RFC 2046, 5.1.1): the preamble dropped, then each part's fields and its
 * octets up to the next delimiter, until the delimiter that closes the body. What follows
 * that is the epilogue, which is not read.
 */
final class MultipartBody {

	/** The longest block of fields a part may have, its line ends included. */
	static final int FIELDS_LIMIT = 8192;

	private final InputStream in;

	/** A line end, two hyphens, the boundary. */
	private final byte[] delimiter;

	/** The most octets of the body read, or -1 for any number. */
	private final long max;

	private final byte[] buffer = new byte[FIELDS_LIMIT];

	private int position;

	private int limit;

	private long read;

	private boolean ended;

	/**
	 * A body.
	 * @param in the request's body
	 * @param boundary the boundary of the request's {@code Content-Type}
	 * @param max the most octets the body may have, or -1
	 */
	MultipartBody(InputStream in, String boundary, long max) {
		this.in = in;
		this.delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
		this.max = max;
		// The first delimiter may open the body with no line end before it: the body is
		// read as if one came first.
		this.buffer[0] = '\r';
		this.buffer[1] = '\n';
		this.limit = 2;
	}

	/**
	 * The boundary a {@code Content-Type} names (RFC 2046, 5.1.1: one to seventy
	 * characters, the last no space).
	 * @param contentType the request's {@code Content-Type}
	 * @return the boundary, or null where there is none fit to be one
	 */
	static @Nullable String boundary(String contentType) {
		String boundary = parameters(contentType).get("boundary");
		if (boundary == null || boundary.isEmpty() || boundary.length() > 70 || boundary.endsWith(" ")) {
			return null;
		}
		for (int i = 0; i < boundary.length(); i++) {
			char c = boundary.charAt(i);
			if (c < 0x20 || c >= 0x7f) {
				return null;
			}
		}
		return boundary;
	}

	/**
	 * Makes at least n octets available from the position, unless the body ends first.
	 */
	private boolean fill(int n) throws IOException {
		if (this.limit - this.position >= n) {
			return true;
		}
		if (this.position > 0) {
			System.arraycopy(this.buffer, this.position, this.buffer, 0, this.limit - this.position);
			this.limit -= this.position;
			this.position = 0;
		}
		while (this.limit < n && !this.ended) {
			int count = this.in.read(this.buffer, this.limit, this.buffer.length - this.limit);
			if (count < 0) {
				this.ended = true;
			}
			else {
				this.read += count;
				if (this.max >= 0 && this.read > this.max) {
					throw new IllegalStateException("the multipart body is longer than " + this.max + " octets");
				}
				this.limit += count;
			}
		}
		return this.limit - this.position >= n;
	}

	/**
	 * Hands the octets up to the next delimiter to a part, or drops them (the preamble),
	 * then reads the delimiter and what ends its line.
	 * @param part the part the octets are of, or null
	 * @return whether the delimiter closes the body
	 * @throws IOException if the body ends first, or the delimiter is followed by neither
	 * two hyphens nor a line end
	 */
	boolean next(@Nullable FormPart part) throws IOException {
		int length = this.delimiter.length;
		while (true) {
			int found = find();
			int end = found >= 0 ? found : Math.max(this.position, this.limit - (length - 1));
			if (part != null && end > this.position) {
				part.append(this.buffer, this.position, end - this.position);
			}
			this.position = end;
			if (found >= 0) {
				break;
			}
			if (!fill(this.limit - this.position + 1)) {
				throw new IOException("the multipart body ends before its closing delimiter");
			}
		}
		if (!fill(length + 2)) {
			throw new IOException("the multipart body ends before its closing delimiter");
		}
		if (this.buffer[this.position + length] == '-' && this.buffer[this.position + length + 1] == '-') {
			this.position += length + 2;
			return true;
		}
		// transport padding, then the line end
		for (int at = length; at + 2 <= this.buffer.length && fill(at + 2); at++) {
			byte octet = this.buffer[this.position + at];
			if (octet == '\r' && this.buffer[this.position + at + 1] == '\n') {
				this.position += at + 2;
				return false;
			}
			if (octet != ' ' && octet != '\t') {
				break;
			}
		}
		throw new IOException("a delimiter of the multipart body is followed by neither -- nor a line end");
	}

	/** Where the delimiter is between the position and the limit, or -1. */
	private int find() {
		byte[] delimiter = this.delimiter;
		int last = this.limit - delimiter.length;
		outer: for (int i = this.position; i <= last; i++) {
			for (int k = 0; k < delimiter.length; k++) {
				if (this.buffer[i + k] != delimiter[k]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	/**
	 * The fields of a part, up to the empty line that ends them; a line that starts with
	 * a space or a tab goes on with the field before (RFC 5322, 2.2.3).
	 * @param charset what the octets of the fields are read as
	 * @return each field's name and value in turn
	 */
	List<String> fields(Charset charset) throws IOException {
		List<String> fields = new ArrayList<>();
		int size = 0;
		while (true) {
			int at = 0;
			while (true) {
				if (size + at + 2 > FIELDS_LIMIT) {
					throw new IllegalStateException("the fields of a part are longer than " + FIELDS_LIMIT + " octets");
				}
				if (!fill(at + 2)) {
					throw new IOException("the multipart body ends inside the fields of a part");
				}
				if (this.buffer[this.position + at] == '\r' && this.buffer[this.position + at + 1] == '\n') {
					break;
				}
				at++;
			}
			String line = new String(this.buffer, this.position, at, charset);
			this.position += at + 2;
			size += at + 2;
			if (line.isEmpty()) {
				return fields;
			}
			char first = line.charAt(0);
			int colon = line.indexOf(':');
			if (first == ' ' || first == '\t') {
				if (fields.isEmpty()) {
					throw new IOException("the fields of a part start with a folded line");
				}
				int value = fields.size() - 1;
				fields.set(value, fields.get(value) + " " + line.trim());
			}
			else if (colon > 0) {
				fields.add(line.substring(0, colon).trim());
				fields.add(line.substring(colon + 1).trim());
			}
			else {
				throw new IOException("a field of a part has no name: " + line);
			}
		}
	}

	/**
	 * The parameters of a field's value ({@code form-data; name="a"; filename="b"}), each
	 * name in lower case, the first of a name kept. A quoted value is taken to its
	 * closing quote; a backslash in it escapes a quote or a backslash and is otherwise
	 * itself, so a path a client sends as it is stays whole (RFC 7578, 4.2, and the forms
	 * of HTML, which escape no backslash).
	 * @param value the field's value, or null
	 * @return the parameters, by name
	 */
	static Map<String, String> parameters(@Nullable String value) {
		Map<String, String> parameters = new LinkedHashMap<>();
		if (value == null) {
			return parameters;
		}
		int length = value.length();
		int at = value.indexOf(';');
		while (at >= 0 && at < length) {
			int equals = at + 1;
			while (equals < length && value.charAt(equals) != '=' && value.charAt(equals) != ';') {
				equals++;
			}
			String name = value.substring(at + 1, equals).trim().toLowerCase(Locale.ROOT);
			if (equals >= length || value.charAt(equals) == ';') {
				at = equals;
				continue;
			}
			int start = equals + 1;
			while (start < length && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) {
				start++;
			}
			StringBuilder text = new StringBuilder();
			int end;
			if (start < length && value.charAt(start) == '"') {
				end = start + 1;
				while (end < length && value.charAt(end) != '"') {
					char c = value.charAt(end);
					if (c == '\\' && end + 1 < length
							&& (value.charAt(end + 1) == '"' || value.charAt(end + 1) == '\\')) {
						c = value.charAt(++end);
					}
					text.append(c);
					end++;
				}
				end = value.indexOf(';', end);
			}
			else {
				end = value.indexOf(';', start);
				text.append(value, start, end < 0 ? length : end);
			}
			if (!parameters.containsKey(name)) {
				parameters.put(name,
						start < length && value.charAt(start) == '"' ? text.toString() : text.toString().trim());
			}
			at = end;
		}
		return parameters;
	}

	/**
	 * The type of a field's value without its parameters, in lower case.
	 * @param value the field's value, or null
	 * @return the type, or null
	 */
	static @Nullable String type(@Nullable String value) {
		if (value == null) {
			return null;
		}
		int semicolon = value.indexOf(';');
		return (semicolon < 0 ? value : value.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * A value of the extended form of RFC 8187 ({@code UTF-8''%E2%82%AC.txt}).
	 * @param value the parameter's value
	 * @return the text, or null where the charset is not known or an escape is broken
	 */
	static @Nullable String extended(String value) {
		int first = value.indexOf('\'');
		int second = first < 0 ? -1 : value.indexOf('\'', first + 1);
		if (second < 0) {
			return null;
		}
		Charset charset;
		try {
			charset = Charset.forName(value.substring(0, first));
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
		String text = value.substring(second + 1);
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) >= 0x80) {
				return null;
			}
		}
		return UrlCodec.decode(text, charset, false);
	}

}
