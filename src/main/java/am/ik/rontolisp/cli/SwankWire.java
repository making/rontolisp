package am.ik.rontolisp.cli;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * The SWANK wire format: each message is six hexadecimal digits giving the length in
 * octets of a UTF-8 payload, the payload one S-expression -- what SLIME's
 * {@code slime-net-send} writes and SWANK's {@code swank/rpc} reads and writes.
 *
 * <p>
 * A message is read into plain values: a list a {@link List}, a string a {@link String},
 * an integer a {@link Long} or {@link BigInteger}, a float a {@link Double}, a keyword a
 * {@link Keyword}, any other symbol a {@link Symbol}, {@code nil} the empty list,
 * {@code t} {@link Boolean#TRUE}, {@code 'x} the list {@code (quote x)}. Writing takes
 * the same values ({@code null} and {@link Boolean#FALSE} are {@code nil}) and prints
 * them as SWANK does: symbols downcased, strings with {@code \} and {@code "} escaped.
 */
final class SwankWire {

	/** The longest payload the six-digit header can count. */
	static final int MAX_LENGTH = 0xFFFFFF;

	private SwankWire() {
	}

	/**
	 * A keyword, by its name without the colon.
	 *
	 * @param name the name
	 */
	record Keyword(String name) {

		@Override
		public boolean equals(@Nullable Object other) {
			return other instanceof Keyword keyword && keyword.name.equalsIgnoreCase(this.name);
		}

		@Override
		public int hashCode() {
			return this.name.toLowerCase(Locale.ROOT).hashCode();
		}

	}

	/**
	 * A symbol, by its name as written, package prefix included.
	 *
	 * @param name the name
	 */
	record Symbol(String name) {

		/**
		 * The name without its package prefix, lowercased.
		 * @return the name
		 */
		String member() {
			int colon = this.name.lastIndexOf(':');
			return (colon < 0 ? this.name : this.name.substring(colon + 1)).toLowerCase(Locale.ROOT);
		}

		/**
		 * The package prefix, lowercased, or the empty string without one.
		 * @return the prefix
		 */
		String pkg() {
			int colon = this.name.indexOf(':');
			return colon < 0 ? "" : this.name.substring(0, colon).toLowerCase(Locale.ROOT);
		}

	}

	/**
	 * Reads one message.
	 * @param in the stream
	 * @return the message, or null at the end of the stream before a header
	 * @throws IOException when the stream fails or holds no message
	 */
	static @Nullable Object read(InputStream in) throws IOException {
		byte[] header = in.readNBytes(6);
		if (header.length == 0) {
			return null;
		}
		if (header.length < 6) {
			throw new EOFException("short SWANK header");
		}
		int length;
		try {
			length = Integer.parseInt(new String(header, StandardCharsets.US_ASCII), 16);
		}
		catch (NumberFormatException ex) {
			throw new IOException("not a SWANK header: " + new String(header, StandardCharsets.US_ASCII), ex);
		}
		byte[] payload = in.readNBytes(length);
		if (payload.length < length) {
			throw new EOFException("short SWANK message");
		}
		return parse(new String(payload, StandardCharsets.UTF_8));
	}

	/**
	 * Writes one message.
	 * @param out the stream, flushed after the message
	 * @param message the message
	 * @throws IOException when the stream fails
	 */
	static void write(OutputStream out, Object message) throws IOException {
		byte[] payload = print(message).getBytes(StandardCharsets.UTF_8);
		if (payload.length > MAX_LENGTH) {
			throw new IOException("SWANK message of " + payload.length + " octets is too long");
		}
		ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 6);
		frame.writeBytes(String.format("%06x", payload.length).getBytes(StandardCharsets.US_ASCII));
		frame.writeBytes(payload);
		frame.writeTo(out);
		out.flush();
	}

	/**
	 * Reads one S-expression.
	 * @param text the text
	 * @return the value
	 * @throws IOException when the text is no S-expression
	 */
	static Object parse(String text) throws IOException {
		Parser parser = new Parser(text);
		Object value = parser.next();
		if (value == Parser.CLOSE || value == Parser.END) {
			throw new IOException("not a SWANK message: " + text);
		}
		return value;
	}

	/**
	 * Prints a value as SWANK does.
	 * @param value the value
	 * @return the text
	 */
	static String print(@Nullable Object value) {
		StringBuilder out = new StringBuilder();
		print(value, out);
		return out.toString();
	}

	private static void print(@Nullable Object value, StringBuilder out) {
		switch (value) {
			case null -> out.append("nil");
			case Boolean b -> out.append(b ? "t" : "nil");
			case String s -> {
				out.append('"');
				for (int i = 0; i < s.length(); i++) {
					char c = s.charAt(i);
					if (c == '"' || c == '\\') {
						out.append('\\');
					}
					out.append(c);
				}
				out.append('"');
			}
			case Keyword k -> out.append(':').append(k.name().toLowerCase(Locale.ROOT));
			case Symbol s -> out.append(s.name().toLowerCase(Locale.ROOT));
			case Double d -> out.append(Double.toString(d).toLowerCase(Locale.ROOT));
			case Number n -> out.append(n);
			case List<?> list -> {
				if (list.isEmpty()) {
					out.append("nil");
					return;
				}
				out.append('(');
				for (int i = 0; i < list.size(); i++) {
					if (i > 0) {
						out.append(' ');
					}
					print(list.get(i), out);
				}
				out.append(')');
			}
			default -> print(String.valueOf(value), out);
		}
	}

	/** A reader of Emacs's {@code prin1} output. */
	private static final class Parser {

		static final Object CLOSE = new Object();

		static final Object END = new Object();

		private final String text;

		private int pos;

		Parser(String text) {
			this.text = text;
		}

		Object next() throws IOException {
			skipBlank();
			if (this.pos >= this.text.length()) {
				return END;
			}
			char c = this.text.charAt(this.pos);
			switch (c) {
				case '(' -> {
					this.pos++;
					List<Object> list = new ArrayList<>();
					while (true) {
						Object element = next();
						if (element == CLOSE) {
							return list;
						}
						if (element == END) {
							throw new IOException("unbalanced SWANK message: " + this.text);
						}
						if (element instanceof Symbol dot && dot.name().equals(".")) {
							Object tail = next();
							if (next() != CLOSE) {
								throw new IOException("bad dotted list in SWANK message: " + this.text);
							}
							if (tail instanceof List<?> rest) {
								list.addAll(rest);
							}
							return list;
						}
						list.add(element);
					}
				}
				case ')' -> {
					this.pos++;
					return CLOSE;
				}
				case '\'' -> {
					this.pos++;
					Object quoted = next();
					if (quoted == CLOSE || quoted == END) {
						throw new IOException("bad quote in SWANK message: " + this.text);
					}
					List<Object> quote = new ArrayList<>(2);
					quote.add(new Symbol("quote"));
					quote.add(quoted);
					return quote;
				}
				case '"' -> {
					return string();
				}
				default -> {
					return atom();
				}
			}
		}

		private void skipBlank() {
			while (this.pos < this.text.length()) {
				char c = this.text.charAt(this.pos);
				if (c == ';') {
					while (this.pos < this.text.length() && this.text.charAt(this.pos) != '\n') {
						this.pos++;
					}
				}
				else if (Character.isWhitespace(c)) {
					this.pos++;
				}
				else {
					return;
				}
			}
		}

		private String string() throws IOException {
			StringBuilder out = new StringBuilder();
			this.pos++;
			while (this.pos < this.text.length()) {
				char c = this.text.charAt(this.pos++);
				if (c == '"') {
					return out.toString();
				}
				if (c != '\\') {
					out.append(c);
					continue;
				}
				if (this.pos >= this.text.length()) {
					break;
				}
				char e = this.text.charAt(this.pos++);
				switch (e) {
					case 'n' -> out.append('\n');
					case 't' -> out.append('\t');
					case 'r' -> out.append('\r');
					case 'f' -> out.append('\f');
					case 'e' -> out.append((char) 27);
					case 'a' -> out.append((char) 7);
					case '\n' -> {
						// an escaped newline continues the line
					}
					case 'x' -> {
						// \xHH..., ended by a non-digit or by a backslash and a space
						int start = this.pos;
						while (this.pos < this.text.length() && Character.digit(this.text.charAt(this.pos), 16) >= 0) {
							this.pos++;
						}
						if (this.pos > start) {
							out.appendCodePoint(Integer.parseInt(this.text.substring(start, this.pos), 16));
						}
						if (this.text.startsWith("\\ ", this.pos)) {
							this.pos += 2;
						}
					}
					default -> out.append(e);
				}
			}
			throw new IOException("unterminated string in SWANK message: " + this.text);
		}

		private Object atom() {
			StringBuilder token = new StringBuilder();
			boolean escaped = false;
			while (this.pos < this.text.length()) {
				char c = this.text.charAt(this.pos);
				if (c == '\\' && this.pos + 1 < this.text.length()) {
					token.append(this.text.charAt(this.pos + 1));
					this.pos += 2;
					escaped = true;
					continue;
				}
				if (Character.isWhitespace(c) || c == '(' || c == ')' || c == '"' || c == '\'' || c == ';') {
					break;
				}
				token.append(c);
				this.pos++;
			}
			String name = token.toString();
			if (!escaped) {
				Object number = number(name);
				if (number != null) {
					return number;
				}
				if (name.equalsIgnoreCase("nil")) {
					return List.of();
				}
				if (name.equalsIgnoreCase("t")) {
					return Boolean.TRUE;
				}
			}
			if (name.startsWith(":") && name.length() > 1) {
				return new Keyword(name.substring(1));
			}
			return new Symbol(name);
		}

		private static @Nullable Object number(String token) {
			if (token.matches("[+-]?\\d+\\.?")) {
				BigInteger value = new BigInteger(token.endsWith(".") ? token.substring(0, token.length() - 1) : token);
				return value.bitLength() < 64 ? (Object) value.longValue() : value;
			}
			if (token.matches("[+-]?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][+-]?\\d+)?") && !token.matches("[+-]?\\d+")) {
				return Double.valueOf(token);
			}
			return null;
		}

	}

}
