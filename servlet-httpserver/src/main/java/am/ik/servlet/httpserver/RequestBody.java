package am.ik.servlet.httpserver;

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

/**
 * The body of a request: the exchange's stream, read by the thread of the request.
 */
final class RequestBody extends ServletInputStream {

	private final InputStream in;

	private boolean finished;

	RequestBody(InputStream in) {
		this.in = in;
	}

	@Override
	public int read() throws IOException {
		int octet = this.in.read();
		if (octet < 0) {
			this.finished = true;
		}
		return octet;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		int read = this.in.read(b, off, len);
		if (read < 0) {
			this.finished = true;
		}
		return read;
	}

	@Override
	public int available() throws IOException {
		return this.in.available();
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
		throw new IllegalStateException("asynchronous requests are not supported");
	}

}
