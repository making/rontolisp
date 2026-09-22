package am.ik.rontolisp.runtime;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/**
 * A CHARACTER input file stream that knows its byte offset: what {@code (open p)} answers
 * on the interpreter, and on the JVM backend in a program that names
 * {@code file-position}. {@code file-position} is the offset of the next byte a read
 * would consume -- sbcl's answer -- so a character advances it by its UTF-8 length and a
 * CRLF line ends after its LF.
 *
 * <p>
 * It extends {@link BufferedReader} so that every dispatch a stream table already has for
 * a character input stream -- {@code read-line}, {@code read-char}, {@code peek-char}'s
 * {@code mark}/{@code reset}, {@code listen}'s {@code ready}, {@code read-sequence}'s
 * block read, {@code close} -- takes it with no arm of its own. The superclass's own
 * buffer is never used: a {@code BufferedReader} over an {@code InputStreamReader} reads
 * ahead through a decoder, and neither layer can say how many BYTES the characters handed
 * out so far took. This class decodes UTF-8 itself over its own byte buffer, so the
 * offset is the channel's position less what is still buffered.
 *
 * <p>
 * A malformed sequence decodes to U+FFFD, consuming its valid prefix -- the replacement a
 * {@code FileReader} makes. It lives in {@code runtime} because it TRAVELS: the JVM
 * backend constructs it and calls its methods ({@code .kb/jvm-export.md}, "What
 * travels"), so it imports nothing but the JDK.
 */
public final class RontoCharFileReader extends BufferedReader {

	private final FileInputStream in;

	private final FileChannel channel;

	private byte[] bytes = new byte[8192];

	/** The next byte to decode. */
	private int pos;

	/** The end of the buffered bytes. */
	private int len;

	/** The low surrogate owed after a supplementary character's high one, or -1. */
	private int pendingLow = -1;

	/** Where {@link #mark} was set, or -1. */
	private int markPos = -1;

	private int markPendingLow = -1;

	private int markLimit;

	/**
	 * Opens the file for reading.
	 * @param path the file to open
	 * @throws IOException when the file cannot be opened
	 */
	public RontoCharFileReader(String path) throws IOException {
		super(Reader.nullReader(), 1);
		this.in = new FileInputStream(path);
		this.channel = this.in.getChannel();
	}

	/**
	 * Returns the byte offset of the next character -- what {@code file-position}
	 * answers.
	 * @return the offset
	 * @throws IOException when the offset cannot be read
	 */
	public long position() throws IOException {
		return this.channel.position() - (this.len - this.pos);
	}

	/**
	 * Moves to the given byte offset, dropping everything buffered.
	 * @param offset the new offset
	 * @throws IOException when the seek fails
	 */
	public void position(long offset) throws IOException {
		this.channel.position(offset);
		this.pos = 0;
		this.len = 0;
		this.pendingLow = -1;
		this.markPos = -1;
	}

	/**
	 * Makes at least {@code n} bytes available from {@link #pos}, short only at end of
	 * file.
	 */
	private boolean ensure(int n) throws IOException {
		while (this.len - this.pos < n) {
			if (this.markPos >= 0 && this.pos - this.markPos > this.markLimit * 4) {
				this.markPos = -1;
			}
			int keep = (this.markPos >= 0) ? this.markPos : this.pos;
			if (keep > 0) {
				System.arraycopy(this.bytes, keep, this.bytes, 0, this.len - keep);
				this.len -= keep;
				this.pos -= keep;
				if (this.markPos >= 0) {
					this.markPos -= keep;
				}
			}
			if (this.len == this.bytes.length) {
				this.bytes = Arrays.copyOf(this.bytes, this.bytes.length * 2);
			}
			int got = this.in.read(this.bytes, this.len, this.bytes.length - this.len);
			if (got < 0) {
				return false;
			}
			this.len += got;
		}
		return true;
	}

	/**
	 * Decodes one code point, or answers -1 at end of file.
	 */
	private int readCodePoint() throws IOException {
		if (!ensure(1)) {
			return -1;
		}
		int b = this.bytes[this.pos] & 0xFF;
		if (b < 0x80) {
			this.pos++;
			return b;
		}
		int need = (b >= 0xC2 && b <= 0xDF) ? 1 : (b >= 0xE0 && b <= 0xEF) ? 2 : (b >= 0xF0 && b <= 0xF4) ? 3 : 0;
		if (need == 0) {
			this.pos++;
			return 0xFFFD;
		}
		ensure(need + 1);
		int cp = b & ((need == 1) ? 0x1F : (need == 2) ? 0x0F : 0x07);
		int i = 1;
		for (; i <= need; i++) {
			if (this.pos + i >= this.len) {
				break;
			}
			int c = this.bytes[this.pos + i] & 0xFF;
			int lo = 0x80;
			int hi = 0xBF;
			if (i == 1) {
				// The second-byte ranges that rule out overlong forms, surrogates and
				// code points past U+10FFFF.
				if (b == 0xE0) {
					lo = 0xA0;
				}
				else if (b == 0xED) {
					hi = 0x9F;
				}
				else if (b == 0xF0) {
					lo = 0x90;
				}
				else if (b == 0xF4) {
					hi = 0x8F;
				}
			}
			if (c < lo || c > hi) {
				break;
			}
			cp = (cp << 6) | (c & 0x3F);
		}
		if (i <= need) {
			this.pos += i;
			return 0xFFFD;
		}
		this.pos += need + 1;
		return cp;
	}

	@Override
	public int read() throws IOException {
		if (this.pendingLow >= 0) {
			int low = this.pendingLow;
			this.pendingLow = -1;
			return low;
		}
		int cp = readCodePoint();
		if (cp >= 0x10000) {
			this.pendingLow = Character.lowSurrogate(cp);
			return Character.highSurrogate(cp);
		}
		return cp;
	}

	@Override
	public int read(char[] buffer, int off, int count) throws IOException {
		if (count == 0) {
			return 0;
		}
		int n = 0;
		while (n < count) {
			int c = read();
			if (c < 0) {
				break;
			}
			buffer[off + n] = (char) c;
			n++;
		}
		return (n == 0) ? -1 : n;
	}

	/**
	 * Reads one line. The terminators are {@code \n}, {@code \r} and {@code \r\n}, none
	 * of which is part of the answer -- {@link BufferedReader#readLine}'s contract -- and
	 * a {@code \r\n} is consumed WHOLE, so the offset after the line is after its LF.
	 * @return the line, or null at end of file
	 * @throws IOException when the read fails
	 */
	// The null is BufferedReader's end-of-file contract; this package cannot import the
	// annotation that would say so (it would travel with the class).
	@SuppressWarnings("NullAway")
	@Override
	public String readLine() throws IOException {
		int c = read();
		if (c < 0) {
			return null;
		}
		StringBuilder line = new StringBuilder();
		while (c >= 0 && c != '\n' && c != '\r') {
			line.append((char) c);
			c = read();
		}
		if (c == '\r' && ensure(1) && this.bytes[this.pos] == '\n') {
			this.pos++;
		}
		return line.toString();
	}

	@Override
	public long skip(long n) throws IOException {
		long skipped = 0;
		while (skipped < n && read() >= 0) {
			skipped++;
		}
		return skipped;
	}

	@Override
	public long transferTo(java.io.Writer out) throws IOException {
		long moved = 0;
		for (int c = read(); c >= 0; c = read()) {
			out.write(c);
			moved++;
		}
		return moved;
	}

	@Override
	public boolean ready() throws IOException {
		return this.pendingLow >= 0 || this.pos < this.len || this.channel.position() < this.channel.size();
	}

	@Override
	public boolean markSupported() {
		return true;
	}

	@Override
	public void mark(int readAheadLimit) {
		this.markPos = this.pos;
		this.markPendingLow = this.pendingLow;
		this.markLimit = Math.max(readAheadLimit, 1);
	}

	@Override
	public void reset() throws IOException {
		if (this.markPos < 0) {
			throw new IOException("Stream not marked");
		}
		this.pos = this.markPos;
		this.pendingLow = this.markPendingLow;
	}

	@Override
	public void close() throws IOException {
		this.in.close();
	}

}
