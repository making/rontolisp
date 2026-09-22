package am.ik.rontolisp.runtime;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.channels.FileChannel;

/**
 * A CHARACTER output file stream that knows its byte offset: what
 * {@code (open p :direction :output)} answers on the interpreter, and on the JVM backend
 * in a program that names {@code file-position}. {@code file-position} is the offset the
 * next character lands at -- sbcl's answer -- so a character advances it by its UTF-8
 * length, and an appending stream starts at the end of the file.
 *
 * <p>
 * It extends {@link BufferedWriter} so that every OUTPUT dispatch a stream table already
 * has -- the print family, {@code write-string}, {@code write-line}, {@code fresh-line},
 * {@code force-output}, {@code close}, the end-of-program flush -- takes it with no arm
 * of its own. The superclass's own buffer is never used: this class encodes UTF-8 into
 * its own byte buffer, so the offset is the channel's position plus what is still
 * buffered, with no per-character counter anywhere.
 *
 * <p>
 * An unpaired surrogate encodes as {@code ?} -- the replacement a {@code FileWriter}
 * makes. It lives in {@code runtime} because it TRAVELS ({@code .kb/jvm-export.md}, "What
 * travels"), so it imports nothing but the JDK.
 */
public final class RontoCharFileWriter extends BufferedWriter {

	private final FileOutputStream out;

	private final FileChannel channel;

	private final byte[] bytes = new byte[8192];

	private int len;

	/** A high surrogate waiting for its low half, or -1. */
	private int pendingHigh = -1;

	/**
	 * Opens the file for writing: truncating it, or keeping its content and writing at
	 * its end.
	 * @param path the file to open
	 * @param append whether to append ({@code :if-exists :append}) rather than truncate
	 * @throws IOException when the file cannot be opened
	 */
	public RontoCharFileWriter(String path, boolean append) throws IOException {
		super(Writer.nullWriter(), 1);
		this.out = new FileOutputStream(path, append);
		this.channel = this.out.getChannel();
	}

	/**
	 * Returns the byte offset the next character lands at -- what {@code file-position}
	 * answers. An appending channel reports the file's end.
	 * @return the offset
	 * @throws IOException when the offset cannot be read
	 */
	public long position() throws IOException {
		return this.channel.position() + this.len;
	}

	/**
	 * Writes what is buffered, then moves to the given byte offset. The file keeps
	 * everything before and after it; the next character overwrites from there.
	 * @param offset the new offset
	 * @throws IOException when the seek fails
	 */
	public void position(long offset) throws IOException {
		flushPendingHigh();
		drain();
		this.channel.position(offset);
	}

	@Override
	public void write(int c) throws IOException {
		char ch = (char) c;
		if (this.pendingHigh >= 0) {
			int high = this.pendingHigh;
			this.pendingHigh = -1;
			if (Character.isLowSurrogate(ch)) {
				encode(Character.toCodePoint((char) high, ch));
				return;
			}
			encode('?');
		}
		if (Character.isHighSurrogate(ch)) {
			this.pendingHigh = ch;
		}
		else if (Character.isLowSurrogate(ch)) {
			encode('?');
		}
		else {
			encode(ch);
		}
	}

	@Override
	public void write(char[] buffer, int off, int count) throws IOException {
		for (int i = off; i < off + count; i++) {
			write(buffer[i]);
		}
	}

	@Override
	public void write(String s, int off, int count) throws IOException {
		for (int i = off; i < off + count; i++) {
			write(s.charAt(i));
		}
	}

	@Override
	public void newLine() throws IOException {
		write(System.lineSeparator());
	}

	private void encode(int cp) throws IOException {
		if (this.len + 4 > this.bytes.length) {
			drain();
		}
		byte[] b = this.bytes;
		if (cp < 0x80) {
			b[this.len++] = (byte) cp;
		}
		else if (cp < 0x800) {
			b[this.len++] = (byte) (0xC0 | (cp >> 6));
			b[this.len++] = (byte) (0x80 | (cp & 0x3F));
		}
		else if (cp < 0x10000) {
			b[this.len++] = (byte) (0xE0 | (cp >> 12));
			b[this.len++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
			b[this.len++] = (byte) (0x80 | (cp & 0x3F));
		}
		else {
			b[this.len++] = (byte) (0xF0 | (cp >> 18));
			b[this.len++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
			b[this.len++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
			b[this.len++] = (byte) (0x80 | (cp & 0x3F));
		}
	}

	private void flushPendingHigh() throws IOException {
		if (this.pendingHigh >= 0) {
			this.pendingHigh = -1;
			encode('?');
		}
	}

	private void drain() throws IOException {
		if (this.len > 0) {
			this.out.write(this.bytes, 0, this.len);
			this.len = 0;
		}
	}

	@Override
	public void flush() throws IOException {
		// A high surrogate stays pending across a flush: its low half may be the next
		// write.
		drain();
	}

	@Override
	public void close() throws IOException {
		flushPendingHigh();
		drain();
		this.out.close();
	}

}
