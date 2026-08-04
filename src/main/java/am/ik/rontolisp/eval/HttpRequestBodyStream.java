package am.ik.rontolisp.eval;

import java.io.InputStream;

import org.jspecify.annotations.Nullable;

/**
 * The buffered ({@code :raw-body :buffered}) request body of one served HTTP request: an
 * in-memory BIVALENT input stream over the body octets. One byte cursor serves both read
 * families -- {@code read-char}/{@code read-line} decode UTF-8 at the cursor,
 * {@code read-byte}/{@code read-sequence} take the octets raw -- so a character read and
 * a byte read can never disagree about where the body is, and {@code file-position} is a
 * real byte index, which is what lets circular-streams rewind a body lack-request has
 * already parsed.
 *
 * <p>
 * This is the interpreter's and the JVM backend's counterpart of
 * {@code http-server.lisp}'s {@code http-request-body-stream} Gray class (which the WASI
 * component keeps): a Clack application reads the body through interpreted
 * {@code lack-request} / {@code http-body} code, and serving each {@code read-line} as a
 * per-character generic-function dispatch cost a measured 36% of the POST throughput. The
 * decode rules are the Gray class's exactly -- lenient UTF-8, a byte that starts no valid
 * sequence is its own character -- so the two constructions stay observably identical.
 *
 * <p>
 * It extends {@link InputStream} so the stream-table dispatch of {@code read-byte} /
 * {@code listen} takes it with no new arm; {@code read-line} / {@code read-char} /
 * {@code peek-char} / {@code file-position} dispatch on the concrete type. The entry is
 * removed from the stream table when the request ends (the transport closes it), never by
 * the handler.
 */
public final class HttpRequestBodyStream extends InputStream {

	private final byte[] octets;

	private int index;

	/**
	 * Creates the stream over the request body octets.
	 * @param octets the body bytes (not copied; the request owns them)
	 */
	public HttpRequestBodyStream(byte[] octets) {
		this.octets = octets;
	}

	@Override
	public int read() {
		if (this.index >= this.octets.length) {
			return -1;
		}
		return this.octets[this.index++] & 0xFF;
	}

	@Override
	public int available() {
		return this.octets.length - this.index;
	}

	/**
	 * Reads one character as a Unicode code point, advancing the byte cursor past its
	 * UTF-8 encoding. Lenient like the Gray class: a byte that starts no valid sequence
	 * (or whose continuation bytes run past the end) answers its own value and advances
	 * by one, so a binary body read as characters degrades instead of signalling.
	 * @return the code point, or {@code -1} at end of stream
	 */
	public int readCodePoint() {
		int cp = decodeAt(this.index);
		if (cp >= 0) {
			this.index += decodedLength(this.index);
		}
		return cp;
	}

	/**
	 * Decodes the character at the cursor without advancing.
	 * @return the code point, or {@code -1} at end of stream
	 */
	public int peekCodePoint() {
		return decodeAt(this.index);
	}

	/**
	 * Reads one line, decoding UTF-8 at the cursor. Line terminators are {@code \n},
	 * {@code \r} and {@code \r\n}, none of which is part of the answer -- the
	 * {@code BufferedReader.readLine} contract the pre-cutover string body had.
	 * @return the line (possibly empty), or {@code null} at end of stream
	 */
	public @Nullable String readLine() {
		if (this.index >= this.octets.length) {
			return null;
		}
		StringBuilder line = new StringBuilder();
		int cp = readCodePoint();
		while (cp >= 0 && cp != '\n' && cp != '\r') {
			line.appendCodePoint(cp);
			cp = readCodePoint();
		}
		if (cp == '\r' && this.index < this.octets.length && this.octets[this.index] == '\n') {
			this.index++;
		}
		return line.toString();
	}

	/**
	 * Returns the byte position of the cursor.
	 * @return the position
	 */
	public int position() {
		return this.index;
	}

	/**
	 * Moves the cursor to the given byte position (clamped to the body).
	 * @param position the new position
	 */
	public void position(int position) {
		this.index = Math.max(0, Math.min(position, this.octets.length));
	}

	private int decodeAt(int i) {
		byte[] v = this.octets;
		int e = v.length;
		if (i >= e) {
			return -1;
		}
		int b = v[i] & 0xFF;
		if (b < 0x80) {
			return b;
		}
		if (b >= 0xC0 && b < 0xE0 && i + 1 < e) {
			return ((b & 0x1F) << 6) | (v[i + 1] & 0x3F);
		}
		if (b >= 0xE0 && b < 0xF0 && i + 2 < e) {
			return ((b & 0x0F) << 12) | ((v[i + 1] & 0x3F) << 6) | (v[i + 2] & 0x3F);
		}
		if (b >= 0xF0 && i + 3 < e) {
			return ((b & 0x07) << 18) | ((v[i + 1] & 0x3F) << 12) | ((v[i + 2] & 0x3F) << 6) | (v[i + 3] & 0x3F);
		}
		return b;
	}

	private int decodedLength(int i) {
		int b = this.octets[i] & 0xFF;
		int e = this.octets.length;
		if (b < 0x80 || b < 0xC0) {
			return 1;
		}
		if (b < 0xE0) {
			return i + 1 < e ? 2 : 1;
		}
		if (b < 0xF0) {
			return i + 2 < e ? 3 : 1;
		}
		return i + 3 < e ? 4 : 1;
	}

}
