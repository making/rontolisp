package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * A {@link ByteArrayOutputStream} whose writes take no lock. The JDK's own synchronizes
 * every {@code write}, and an emitter or a binary rewriter writes a module one opcode or
 * LEB byte at a time into a buffer only its own thread ever sees -- so the lock is pure
 * cost, paid per byte. Every other method (including {@code toByteArray} and
 * {@code reset}) is inherited, so an instance goes wherever a
 * {@code ByteArrayOutputStream} does; it just must not be shared between threads.
 */
public final class UnsynchronizedByteArrayOutputStream extends ByteArrayOutputStream {

	/** Creates a buffer with the default initial capacity. */
	public UnsynchronizedByteArrayOutputStream() {
		super();
	}

	/**
	 * Creates a buffer with the given initial capacity.
	 * @param size the initial capacity
	 */
	public UnsynchronizedByteArrayOutputStream(int size) {
		super(size);
	}

	@Override
	public void write(int b) {
		if (this.count == this.buf.length) {
			grow(this.count + 1);
		}
		this.buf[this.count++] = (byte) b;
	}

	@Override
	public void write(byte[] b, int off, int len) {
		java.util.Objects.checkFromIndexSize(off, len, b.length);
		if (this.count + len > this.buf.length) {
			grow(this.count + len);
		}
		System.arraycopy(b, off, this.buf, this.count, len);
		this.count += len;
	}

	@Override
	public void writeBytes(byte[] b) {
		write(b, 0, b.length);
	}

	/**
	 * Replaces one byte already written -- a patch in place, where copying the buffer out
	 * and back would cost the whole of it.
	 * @param index the position of the byte, below {@link #size()}
	 * @param b the new value
	 */
	public void overwrite(int index, int b) {
		java.util.Objects.checkIndex(index, this.count);
		this.buf[index] = (byte) b;
	}

	private void grow(int minCapacity) {
		this.buf = Arrays.copyOf(this.buf, Math.max(minCapacity, Math.max(16, this.buf.length * 2)));
	}

}
