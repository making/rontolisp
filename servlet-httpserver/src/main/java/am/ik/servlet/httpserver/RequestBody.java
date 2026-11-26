package am.ik.servlet.httpserver;

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

/**
 * The body of a request: the exchange's stream, read by the thread of the request. It is
 * finished once the length the request declared is read, or the end of its chunks.
 *
 * <p>
 * The exchange's stream blocks, so {@link #isReady()} is always true: with a read
 * listener, a read may still wait for the client.
 */
final class RequestBody extends ServletInputStream {

	private final Request request;

	private final InputStream in;

	/** The octets the request declares, or -1 for a body of chunks. */
	private final long length;

	private long read;

	private boolean finished;

	/** Whether a read listener was set: the stream is in non-blocking mode. */
	private boolean listened;

	RequestBody(Request request, InputStream in, long length) {
		this.request = request;
		this.in = in;
		this.length = length;
		this.finished = length == 0;
	}

	@Override
	public int read() throws IOException {
		if (this.finished) {
			return -1;
		}
		int octet = this.in.read();
		counted(octet < 0 ? -1 : 1);
		return octet;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		if (this.finished) {
			return len == 0 ? 0 : -1;
		}
		int read = this.in.read(b, off, len);
		counted(read);
		return read;
	}

	private void counted(int read) {
		if (read < 0) {
			this.finished = true;
		}
		else {
			this.read += read;
			if (this.length >= 0 && this.read >= this.length) {
				this.finished = true;
			}
		}
	}

	@Override
	public int available() throws IOException {
		return this.finished ? 0 : this.in.available();
	}

	@Override
	public byte[] readAllBytes() throws IOException {
		blocking();
		return super.readAllBytes();
	}

	@Override
	public byte[] readNBytes(int len) throws IOException {
		blocking();
		return super.readNBytes(len);
	}

	@Override
	public int readNBytes(byte[] b, int off, int len) throws IOException {
		blocking();
		return super.readNBytes(b, off, len);
	}

	/** The reads that wait for every octet asked are for a stream in blocking mode. */
	private void blocking() {
		if (this.listened) {
			throw new IllegalStateException("a read listener has been set: the stream is in non-blocking mode");
		}
	}

	@Override
	public boolean isFinished() {
		return this.finished;
	}

	@Override
	public boolean isReady() {
		return true;
	}

	@Override
	public void setReadListener(ReadListener readListener) {
		if (readListener == null) {
			throw new NullPointerException("readListener");
		}
		Async async = this.request.async();
		if (async == null || !async.started()) {
			throw new IllegalStateException("a read listener is set for an asynchronous request");
		}
		async.reader(readListener);
		this.listened = true;
	}

}
