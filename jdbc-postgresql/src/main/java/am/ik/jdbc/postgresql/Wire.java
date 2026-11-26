package am.ik.jdbc.postgresql;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The connection's socket and the framing of the frontend/backend protocol, version 3.0
 * (PostgreSQL documentation, "Message Formats"): a message is one octet naming its kind,
 * a length of four octets counting itself, and a body.
 *
 * <p>
 * Messages are written into one array and sent by {@link #flush()} in one piece, so a
 * round trip is one segment each way. A message read is whole in the input array before
 * any of its fields is asked for; the fields are read where they lie.
 */
final class Wire {

	private final Socket socket;

	private final InputStream in;

	private final OutputStream out;

	private byte[] output = new byte[1024];

	private int written;

	// Where the length of the message being written goes.
	private int lengthAt;

	private byte[] input = new byte[8192];

	// The next octet to read and the end of what has come.
	private int position;

	private int limit;

	// The end of the message being read.
	private int end;

	Wire(String host, int port, int connectTimeoutMillis) throws IOException {
		this.socket = new Socket();
		try {
			this.socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
			this.socket.setTcpNoDelay(true);
			this.in = this.socket.getInputStream();
			this.out = this.socket.getOutputStream();
		}
		catch (IOException | RuntimeException ex) {
			try {
				this.socket.close();
			}
			catch (IOException ignored) {
				// The failure to connect is the one to report.
			}
			throw ex;
		}
	}

	void timeout(int millis) throws IOException {
		this.socket.setSoTimeout(millis);
	}

	int timeout() throws IOException {
		return this.socket.getSoTimeout();
	}

	boolean isClosed() {
		return this.socket.isClosed();
	}

	void close() throws IOException {
		this.socket.close();
	}

	// Writing.

	/**
	 * Starts a message.
	 * @param kind the octet naming it, or 0 for the two that have none (the start-up
	 * message and a cancel request)
	 */
	Wire begin(int kind) {
		if (kind != 0) {
			room(5);
			this.output[this.written++] = (byte) kind;
		}
		else {
			room(4);
		}
		this.lengthAt = this.written;
		this.written += 4;
		return this;
	}

	Wire octet(int value) {
		room(1);
		this.output[this.written++] = (byte) value;
		return this;
	}

	Wire int16(int value) {
		room(2);
		this.output[this.written++] = (byte) (value >> 8);
		this.output[this.written++] = (byte) value;
		return this;
	}

	Wire int32(int value) {
		room(4);
		put32(this.written, value);
		this.written += 4;
		return this;
	}

	Wire octets(byte[] value) {
		room(value.length);
		System.arraycopy(value, 0, this.output, this.written, value.length);
		this.written += value.length;
		return this;
	}

	/** Writes a text in UTF-8 and the zero octet that ends it. */
	Wire text(String value) {
		octets(value.getBytes(StandardCharsets.UTF_8));
		return octet(0);
	}

	/** Ends the message started last: its length is known now. */
	Wire end() {
		put32(this.lengthAt, this.written - this.lengthAt);
		return this;
	}

	/** Sends every message written since the last call. */
	void flush() throws IOException {
		int length = this.written;
		if (length == 0) {
			return;
		}
		this.written = 0;
		this.out.write(this.output, 0, length);
		this.out.flush();
	}

	private void put32(int at, int value) {
		this.output[at] = (byte) (value >> 24);
		this.output[at + 1] = (byte) (value >> 16);
		this.output[at + 2] = (byte) (value >> 8);
		this.output[at + 3] = (byte) value;
	}

	private void room(int count) {
		if (this.written + count > this.output.length) {
			byte[] larger = new byte[Math.max(this.output.length * 2, this.written + count)];
			System.arraycopy(this.output, 0, larger, 0, this.written);
			this.output = larger;
		}
	}

	// Reading.

	/**
	 * Reads the next message whole, leaving what was not read of the one before.
	 * @return the octet naming its kind
	 */
	int next() throws IOException {
		this.position = this.end;
		fill(5);
		int kind = this.input[this.position] & 0xff;
		int length = get32(this.position + 1);
		if (length < 4) {
			throw new IOException("A message of the kind " + (char) kind + " states the length " + length);
		}
		this.position += 5;
		fill(length - 4);
		this.end = this.position + length - 4;
		return kind;
	}

	/** The octets of the message not read yet. */
	int remaining() {
		return this.end - this.position;
	}

	int octet() throws IOException {
		need(1);
		return this.input[this.position++] & 0xff;
	}

	int int16() throws IOException {
		need(2);
		int value = ((this.input[this.position] & 0xff) << 8) | (this.input[this.position + 1] & 0xff);
		this.position += 2;
		return value;
	}

	int int32() throws IOException {
		need(4);
		int value = get32(this.position);
		this.position += 4;
		return value;
	}

	byte[] octets(int count) throws IOException {
		need(count);
		byte[] value = new byte[count];
		System.arraycopy(this.input, this.position, value, 0, count);
		this.position += count;
		return value;
	}

	/** Reads a text up to the zero octet that ends it. */
	String text() throws IOException {
		int start = this.position;
		int at = start;
		while (at < this.end && this.input[at] != 0) {
			at++;
		}
		if (at == this.end) {
			throw new IOException("A text of a message does not end");
		}
		this.position = at + 1;
		return new String(this.input, start, at - start, StandardCharsets.UTF_8);
	}

	private void need(int count) throws IOException {
		if (count < 0 || count > this.end - this.position) {
			throw new IOException("A message is shorter than its fields");
		}
	}

	private int get32(int at) {
		return ((this.input[at] & 0xff) << 24) | ((this.input[at + 1] & 0xff) << 16)
				| ((this.input[at + 2] & 0xff) << 8) | (this.input[at + 3] & 0xff);
	}

	// Has count octets from the position on in the array, reading what is missing.
	private void fill(int count) throws IOException {
		int held = this.limit - this.position;
		if (held >= count) {
			return;
		}
		if (this.position + count > this.input.length) {
			byte[] target = count > this.input.length ? new byte[Math.max(count, this.input.length * 2)] : this.input;
			System.arraycopy(this.input, this.position, target, 0, held);
			this.input = target;
			this.position = 0;
			this.limit = held;
		}
		while (this.limit - this.position < count) {
			int read = this.in.read(this.input, this.limit, this.input.length - this.limit);
			if (read < 0) {
				throw new EOFException("The server closed the connection");
			}
			this.limit += read;
		}
	}

}
