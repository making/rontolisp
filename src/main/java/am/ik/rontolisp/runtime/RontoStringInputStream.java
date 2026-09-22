package am.ik.rontolisp.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

/**
 * A string INPUT stream that knows its position: the reader
 * {@code make-string-input-stream} / {@code with-input-from-string} put in the stream
 * table wherever {@code file-position} can be asked of one.
 *
 * <p>
 * It extends {@link BufferedReader} so that every read dispatch a stream table already
 * has -- {@code read-line}, {@code read-char}, the {@code mark}/{@code reset} peek,
 * {@code read-sequence}'s block read, {@code listen}, {@code close} -- takes it with no
 * arm of its own; only {@code file-position} asks for {@link #position()} and
 * {@link #seek(long)}. Every method works on the string directly (the superclass is
 * handed an empty reader it never reads), so the cursor IS the logical position: nothing
 * reads ahead of what was consumed.
 *
 * <p>
 * It behaves exactly as the {@code BufferedReader} over a {@code StringReader} it stands
 * in for, down to {@link #ready()} answering {@code true} until it is closed. It lives in
 * {@code runtime} because it TRAVELS beside a compiled JVM program that asks for a string
 * stream's position ({@code .kb/jvm-export.md}, "What travels"), so, like every class
 * here, it imports nothing but the JDK.
 */
public final class RontoStringInputStream extends BufferedReader {

	private final String text;

	private int cursor;

	private int mark = -1;

	private boolean closed;

	/**
	 * A stream over the whole of {@code text}.
	 * @param text the characters to read
	 */
	public RontoStringInputStream(String text) {
		super(new StringReader(""), 1);
		this.text = text;
	}

	/**
	 * The number of CHARACTERS (code points) read so far.
	 * @return the position
	 */
	public long position() {
		return this.text.codePointCount(0, this.cursor);
	}

	/**
	 * Moves the cursor to a character index. {@code -1} means the end -- what
	 * {@code (file-position s :end)} is rewritten to on the compile paths, where a string
	 * stream has no {@code file-length} to seek to.
	 * @param position the character index, or {@code -1}
	 * @return whether the cursor moved: false for an index past the end or below
	 * {@code -1}
	 */
	public boolean seek(long position) {
		if (position == -1) {
			this.cursor = this.text.length();
			return true;
		}
		if (position < 0 || position > this.text.codePointCount(0, this.text.length())) {
			return false;
		}
		this.cursor = this.text.offsetByCodePoints(0, (int) position);
		return true;
	}

	private void ensureOpen() throws IOException {
		if (this.closed) {
			throw new IOException("Stream closed");
		}
	}

	@Override
	public int read() throws IOException {
		ensureOpen();
		return this.cursor < this.text.length() ? this.text.charAt(this.cursor++) : -1;
	}

	@Override
	public int read(char[] buffer, int offset, int length) throws IOException {
		ensureOpen();
		if (length == 0) {
			return 0;
		}
		int available = this.text.length() - this.cursor;
		if (available <= 0) {
			return -1;
		}
		int n = Math.min(length, available);
		this.text.getChars(this.cursor, this.cursor + n, buffer, offset);
		this.cursor += n;
		return n;
	}

	// null at the end is BufferedReader's contract, which every caller tests for; the
	// class cannot spell @Nullable (it imports nothing but the JDK).
	@Override
	@SuppressWarnings("NullAway")
	public String readLine() throws IOException {
		ensureOpen();
		if (this.cursor >= this.text.length()) {
			return null;
		}
		int start = this.cursor;
		int end = start;
		while (end < this.text.length() && this.text.charAt(end) != '\n' && this.text.charAt(end) != '\r') {
			end++;
		}
		this.cursor = end;
		if (end < this.text.length()) {
			this.cursor++;
			if (this.text.charAt(end) == '\r' && this.cursor < this.text.length()
					&& this.text.charAt(this.cursor) == '\n') {
				this.cursor++;
			}
		}
		return this.text.substring(start, end);
	}

	@Override
	public long skip(long n) throws IOException {
		if (n < 0L) {
			throw new IllegalArgumentException("skip value is negative");
		}
		ensureOpen();
		int skipped = (int) Math.min(n, this.text.length() - this.cursor);
		this.cursor += skipped;
		return skipped;
	}

	@Override
	public boolean ready() throws IOException {
		ensureOpen();
		return true;
	}

	@Override
	public boolean markSupported() {
		return true;
	}

	@Override
	public void mark(int readAheadLimit) throws IOException {
		if (readAheadLimit < 0) {
			throw new IllegalArgumentException("Read-ahead limit < 0");
		}
		ensureOpen();
		this.mark = this.cursor;
	}

	@Override
	public void reset() throws IOException {
		ensureOpen();
		if (this.mark < 0) {
			throw new IOException("Stream not marked");
		}
		this.cursor = this.mark;
	}

	@Override
	public void close() {
		this.closed = true;
	}

}
