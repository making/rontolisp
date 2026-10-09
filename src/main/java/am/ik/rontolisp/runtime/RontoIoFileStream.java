package am.ik.rontolisp.runtime;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Writer;

/**
 * One BIDIRECTIONAL file stream: the value {@code (open p :direction :io)} answers, and
 * also what an {@code :if-exists :overwrite} output open uses. Reads, writes and
 * {@code file-position} all move ONE cursor -- a {@link RandomAccessFile}'s -- which is
 * the whole point: {@code (write-string "wxyz" s)}, {@code (file-position s 0)},
 * {@code (read-line s)} has to answer what was just written, and a
 * {@code Reader}/{@code Writer} pair over the same path cannot.
 *
 * <p>
 * It extends {@link Writer} so that every OUTPUT dispatch a stream table already has --
 * the print family, {@code write-string}, {@code write-line}, {@code fresh-line},
 * {@code force-output}, {@code close}, the end-of-program flush -- takes it with no arm
 * of its own; the read side and the byte side are the explicit methods below, which the
 * interpreter and the JVM backend dispatch to on the concrete type (the
 * {@code HttpRequestBodyStream} shape).
 *
 * <p>
 * It lives in {@code runtime} because it TRAVELS: the JVM backend emits calls to it
 * rather than a bytecode transcription of the UTF-8 walk, so the interpreter and a
 * compiled program run literally the same code and cannot drift
 * ({@code .kb/jvm-export.md}, "What travels"). Like every class here it imports nothing
 * but the JDK -- which is why the mode bits below are mirrored from
 * {@code compiler.OpenModes} rather than referenced.
 *
 * <p>
 * Characters are UTF-8, decoded and encoded here rather than through a
 * {@code CharsetDecoder}, because a decoder buffers and the byte cursor
 * {@code file-position} answers must never be ahead of what has been consumed. A
 * malformed sequence decodes as {@link RontoCharFileReader} decodes it -- Java's decoder:
 * one U+FFFD for its valid prefix, and the byte that interrupted it is left for the next
 * read ({@code .kb/character-sequence-io.md}, "Malformed input").
 */
public final class RontoIoFileStream extends Writer {

	/** Mirror of {@code compiler.OpenModes.APPEND_BIT}. */
	private static final int APPEND_BIT = 4;

	/** Mirror of {@code compiler.OpenModes.OVERWRITE_BIT}. */
	private static final int OVERWRITE_BIT = 8;

	private final RandomAccessFile file;

	/** Whether end of file ended the line {@link #readLine()} last answered. */
	private boolean lineMissingNewline;

	/**
	 * Opens the file for reading and writing and positions it as the mode asks.
	 *
	 * <p>
	 * The disposition is the {@code :if-exists} value the mode carries: neither bit means
	 * the truncating open ({@code :supersede} and its version-less synonyms), the
	 * overwrite bit means "keep the content, write from 0", and the append bit means
	 * "keep the content, write from the end". CL's {@code :append} makes EVERY write go
	 * to the end; here the cursor merely STARTS there, because one cursor also serves the
	 * reads and {@code file-position} moves it -- the documented divergence.
	 * @param path the file to open
	 * @param mode the {@code compiler.OpenModes} mode
	 * @throws IOException when the file cannot be opened
	 */
	public RontoIoFileStream(String path, int mode) throws IOException {
		this.file = new RandomAccessFile(path, "rw");
		if ((mode & (APPEND_BIT | OVERWRITE_BIT)) == 0) {
			this.file.setLength(0);
		}
		else if ((mode & APPEND_BIT) != 0) {
			this.file.seek(this.file.length());
		}
	}

	/**
	 * Reads one octet.
	 * @return the octet, or {@code -1} at end of file
	 * @throws IOException when the read fails
	 */
	public int readByte() throws IOException {
		return this.file.read();
	}

	/**
	 * Writes one octet at the cursor.
	 * @param octet the octet
	 * @throws IOException when the write fails
	 */
	public void writeByte(int octet) throws IOException {
		this.file.write(octet & 0xFF);
	}

	/**
	 * Reads one character as a Unicode code point, advancing the cursor past its UTF-8
	 * encoding.
	 * @return the code point, or {@code -1} at end of file
	 * @throws IOException when the read fails
	 */
	public int readCodePoint() throws IOException {
		int b = this.file.read();
		if (b < 0x80) {
			return b;
		}
		int need = (b >= 0xC2 && b <= 0xDF) ? 1 : (b >= 0xE0 && b <= 0xEF) ? 2 : (b >= 0xF0 && b <= 0xF4) ? 3 : 0;
		if (need == 0) {
			return 0xFFFD;
		}
		int cp = b & ((need == 1) ? 0x1F : (need == 2) ? 0x0F : 0x07);
		for (int i = 1; i <= need; i++) {
			long here = this.file.getFilePointer();
			int next = this.file.read();
			if (next < 0) {
				// End of file inside the sequence: what came of it is one U+FFFD.
				return 0xFFFD;
			}
			int lo = (i == 1 && b == 0xE0) ? 0xA0 : (i == 1 && b == 0xF0) ? 0x90 : 0x80;
			int hi = (i == 1 && b == 0xF4) ? 0x8F : 0xBF;
			if (next < lo || next > hi) {
				// The byte that interrupted it starts the next character.
				this.file.seek(here);
				return 0xFFFD;
			}
			cp = (cp << 6) | (next & 0x3F);
		}
		return (cp >= 0xD800 && cp <= 0xDFFF) ? 0xFFFD : cp;
	}

	/**
	 * Decodes the character at the cursor WITHOUT advancing it.
	 * @return the code point, or {@code -1} at end of file
	 * @throws IOException when the read fails
	 */
	public int peekCodePoint() throws IOException {
		long here = this.file.getFilePointer();
		int cp = readCodePoint();
		this.file.seek(here);
		return cp;
	}

	/**
	 * Reads one line. The terminators are {@code \n}, {@code \r} and {@code \r\n}, none
	 * of which is part of the answer -- the {@code BufferedReader.readLine} contract
	 * every other stream kind here answers.
	 *
	 * <p>
	 * End of file is the CALLER's test, through {@link #ready()}: a class in this package
	 * cannot say {@code @Nullable} (the annotation is {@code RuntimeVisible} and would
	 * follow the class into a compiled program's output), and "" is a real line.
	 * @return the line, empty when the cursor is already at end of file
	 * @throws IOException when the read fails
	 */
	public String readLine() throws IOException {
		int cp = readCodePoint();
		if (cp < 0) {
			this.lineMissingNewline = true;
			return "";
		}
		StringBuilder line = new StringBuilder();
		while (cp >= 0 && cp != '\n' && cp != '\r') {
			line.appendCodePoint(cp);
			cp = readCodePoint();
		}
		boolean missing = cp < 0;
		if (cp == '\r') {
			long here = this.file.getFilePointer();
			int next = this.file.read();
			if (next < 0) {
				missing = true;
			}
			else if (next != '\n') {
				this.file.seek(here);
			}
		}
		this.lineMissingNewline = missing;
		return line.toString();
	}

	/**
	 * Whether end of file -- not a terminator -- ended the line {@link #readLine()} last
	 * answered: {@code read-line}'s second value, missing-newline-p. A {@code \r} the
	 * file ends on ends its line as end of file does, the way the WASM backends answer.
	 * @return true when end of file ended the last line
	 */
	public boolean lastLineMissingNewline() {
		return this.lineMissingNewline;
	}

	/**
	 * Returns the byte offset of the cursor -- what {@code file-position} answers.
	 * @return the offset
	 * @throws IOException when the offset cannot be read
	 */
	public long position() throws IOException {
		return this.file.getFilePointer();
	}

	/**
	 * Moves the cursor to the given byte offset.
	 * @param offset the new offset
	 * @throws IOException when the seek fails
	 */
	public void position(long offset) throws IOException {
		this.file.seek(offset);
	}

	/**
	 * Returns the file's length in octets -- what {@code file-length} answers.
	 * @return the length
	 * @throws IOException when the length cannot be read
	 */
	public long length() throws IOException {
		return this.file.length();
	}

	/**
	 * Whether a read would answer without blocking -- what {@code listen} answers. A
	 * regular file never blocks, so this is "not at end of file".
	 * @return true when the cursor is before the end of the file
	 * @throws IOException when the file cannot be measured
	 */
	public boolean ready() throws IOException {
		return this.file.getFilePointer() < this.file.length();
	}

	@Override
	public void write(char[] buffer, int off, int len) throws IOException {
		byte[] octets = new byte[len * 4];
		int n = 0;
		int i = off;
		int end = off + len;
		while (i < end) {
			int cp = buffer[i];
			if (Character.isHighSurrogate(buffer[i]) && i + 1 < end && Character.isLowSurrogate(buffer[i + 1])) {
				cp = Character.toCodePoint(buffer[i], buffer[i + 1]);
				i += 2;
			}
			else {
				i++;
			}
			if (cp < 0x80) {
				octets[n++] = (byte) cp;
			}
			else if (cp < 0x800) {
				octets[n++] = (byte) (0xC0 | (cp >> 6));
				octets[n++] = (byte) (0x80 | (cp & 0x3F));
			}
			else if (cp < 0x10000) {
				octets[n++] = (byte) (0xE0 | (cp >> 12));
				octets[n++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
				octets[n++] = (byte) (0x80 | (cp & 0x3F));
			}
			else {
				octets[n++] = (byte) (0xF0 | (cp >> 18));
				octets[n++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
				octets[n++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
				octets[n++] = (byte) (0x80 | (cp & 0x3F));
			}
		}
		this.file.write(octets, 0, n);
	}

	@Override
	public void flush() {
		// Every write is a write through to the descriptor: there is nothing buffered.
	}

	@Override
	public void close() throws IOException {
		this.file.close();
	}

}
