package am.ik.servlet.httpserver;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

/**
 * The body of a response and its buffer. While everything written fits the buffer the
 * response is not committed, and one that ends so is sent with its length; the write that
 * overflows the buffer, or a flush, commits it, and the rest goes out in chunks unless
 * the servlet declared a length.
 */
final class ResponseBody extends ServletOutputStream {

	private static final byte[] NONE = new byte[0];

	private final Response response;

	private byte[] buffer = NONE;

	private int capacity = 8192;

	private int count;

	private long written;

	private OutputStream sink;

	private boolean closed;

	ResponseBody(Response response) {
		this.response = response;
	}

	int capacity() {
		return this.capacity;
	}

	void capacity(int capacity) {
		this.capacity = capacity;
		this.buffer = NONE;
	}

	long written() {
		return this.written;
	}

	/** Forgets what is buffered. */
	void clear() {
		this.count = 0;
		this.written = 0;
	}

	@Override
	public void write(int b) throws IOException {
		write(new byte[] { (byte) b }, 0, 1);
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		if (off < 0 || len < 0 || len > b.length - off) {
			throw new IndexOutOfBoundsException();
		}
		if (this.closed) {
			return;
		}
		long declared = this.response.declaredLength();
		if (declared >= 0 && this.written + len > declared) {
			len = (int) (declared - this.written);
		}
		if (len <= 0) {
			return;
		}
		if (this.count + len > this.capacity) {
			send();
			if (len >= this.capacity) {
				if (this.sink != null) {
					this.sink.write(b, off, len);
				}
			}
			else {
				keep(b, off, len);
			}
		}
		else {
			keep(b, off, len);
		}
		this.written += len;
		if (declared >= 0 && this.written == declared) {
			// The declared length is reached: the response is whole.
			finish();
		}
	}

	private void keep(byte[] b, int off, int len) {
		int needed = this.count + len;
		if (needed > this.buffer.length) {
			this.buffer = Arrays.copyOf(this.buffer, Math.min(this.capacity, Math.max(needed, this.buffer.length * 2)));
		}
		System.arraycopy(b, off, this.buffer, this.count, len);
		this.count = needed;
	}

	/** Commits the response where it is not, and sends what is buffered. */
	private void send() throws IOException {
		if (!this.response.isCommitted()) {
			this.sink = this.response.commit(-1);
		}
		if (this.count > 0 && this.sink != null) {
			this.sink.write(this.buffer, 0, this.count);
		}
		this.count = 0;
	}

	@Override
	public void flush() throws IOException {
		if (this.closed) {
			return;
		}
		send();
		if (this.sink != null) {
			this.sink.flush();
		}
	}

	@Override
	public void close() throws IOException {
		if (this.response.including > 0) {
			return;
		}
		finish();
	}

	/**
	 * Ends the response: one not committed is sent with the length of what is buffered.
	 * What is written afterwards is dropped.
	 */
	void finish() throws IOException {
		if (this.closed) {
			return;
		}
		this.closed = true;
		if (!this.response.isCommitted()) {
			this.sink = this.response.commit(this.count);
		}
		if (this.sink != null) {
			if (this.count > 0) {
				this.sink.write(this.buffer, 0, this.count);
			}
			this.count = 0;
			this.sink.close();
		}
	}

	@Override
	public boolean isReady() {
		return true;
	}

	@Override
	public void setWriteListener(WriteListener writeListener) {
		throw new IllegalStateException("asynchronous requests are not supported");
	}

}
