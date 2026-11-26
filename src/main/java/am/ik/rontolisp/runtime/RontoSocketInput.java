package am.ik.rontolisp.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A connected socket's input as the stream built-ins read it on the interpreter and the
 * JVM backend: one host read fills a buffer of what has come, and the byte, character,
 * line and sequence reads take from it -- the chunk the WASM component and a
 * {@code --native} executable hold per socket. Every read the buffer cannot answer asks
 * the host, and every such ask first sends what the program's socket output buffers hold:
 * a single-threaded program writes on one socket and then waits on its peer's end, which
 * may be another socket of the same program.
 *
 * <p>
 * The reads of one socket share this one buffer, so no read takes bytes another owes the
 * caller. A socket has one, made on its first read and dropped by {@link #drop} when the
 * socket closes or is upgraded to TLS. It takes no lock: one thread reads a socket at a
 * time.
 *
 * <p>
 * The class imports nothing of rontolisp, so it travels beside a compiled program that
 * uses sockets ({@code .kb/jvm-export.md}, "What travels").
 */
public final class RontoSocketInput extends InputStream {

	/** The buffer's size: the component's and the {@code --native} runner's chunk. */
	static final int SIZE = 8192;

	private static final Map<Socket, RontoSocketInput> INPUTS = new ConcurrentHashMap<>();

	private final InputStream in;

	private final Map<?, ? extends OutputStream> outputs;

	private final byte[] buffer = new byte[SIZE];

	private int position;

	private int limit;

	private RontoSocketInput(InputStream in, Map<?, ? extends OutputStream> outputs) {
		this.in = in;
		this.outputs = outputs;
	}

	/**
	 * The input of a connected socket, made on its first read. A {@code Socket} keeps
	 * {@code Object}'s identity equality, so a socket is its own key.
	 * @param socket the connected socket
	 * @param outputs the program's socket output buffers, sent before every host read
	 * @return the socket's input
	 * @throws IOException if the socket is closed
	 */
	public static RontoSocketInput of(Socket socket, Map<?, ? extends OutputStream> outputs) throws IOException {
		RontoSocketInput input = INPUTS.get(socket);
		if (input == null) {
			RontoSocketInput fresh = new RontoSocketInput(socket.getInputStream(), outputs);
			input = INPUTS.putIfAbsent(socket, fresh);
			if (input == null) {
				input = fresh;
			}
		}
		return input;
	}

	/**
	 * Forgets a socket's input, with what its buffer still holds: at {@code close} and at
	 * a TLS upgrade, whose socket reads the connection through an input of its own.
	 * @param socket the socket
	 */
	public static void drop(Socket socket) {
		INPUTS.remove(socket);
	}

	/**
	 * Sends what every socket output buffer of the program holds. A flush that fails is
	 * skipped: its bytes stay buffered, and the socket's own next write or flush reports
	 * the failure.
	 */
	private void flushOutputs() {
		for (OutputStream output : this.outputs.values()) {
			try {
				output.flush();
			}
			catch (IOException ex) {
				// reported by the socket's own next write or flush
			}
		}
	}

	/** Waits for what comes next; false at the peer's close. */
	private boolean fill() throws IOException {
		flushOutputs();
		int n = this.in.read(this.buffer, 0, SIZE);
		if (n <= 0) {
			return false;
		}
		this.position = 0;
		this.limit = n;
		return true;
	}

	@Override
	public int read() throws IOException {
		if (this.position == this.limit && !fill()) {
			return -1;
		}
		return this.buffer[this.position++] & 0xff;
	}

	/**
	 * What has come, into {@code octets[offset, offset + length)}: what the buffer holds,
	 * waiting for the host only when it holds nothing, then what else the host has at
	 * once. A request of a buffer's size or more is read into the caller's octets.
	 */
	@Override
	public int read(byte[] octets, int offset, int length) throws IOException {
		if (length == 0) {
			return 0;
		}
		int n;
		if (this.position < this.limit) {
			n = take(octets, offset, length);
		}
		else if (length >= SIZE) {
			flushOutputs();
			return this.in.read(octets, offset, length);
		}
		else if (fill()) {
			n = take(octets, offset, length);
		}
		else {
			return -1;
		}
		while (n < length && this.in.available() > 0) {
			int more;
			if (length - n >= SIZE) {
				more = this.in.read(octets, offset + n, length - n);
			}
			else {
				more = fill() ? take(octets, offset + n, length - n) : -1;
			}
			if (more <= 0) {
				break;
			}
			n += more;
		}
		return n;
	}

	/** Copies what the buffer holds, up to {@code length} octets. */
	private int take(byte[] octets, int offset, int length) {
		int n = Math.min(length, this.limit - this.position);
		System.arraycopy(this.buffer, this.position, octets, offset, n);
		this.position += n;
		return n;
	}

	/** What a read hands over without waiting: the buffer's, else what the host has. */
	@Override
	public int available() throws IOException {
		if (this.position < this.limit) {
			return this.limit - this.position;
		}
		flushOutputs();
		return this.in.available();
	}

	/**
	 * Whether a read would answer at once ({@code listen}): the program's socket output
	 * goes first, since the input asked about may wait on it.
	 * @return true when the buffer or the host holds an octet
	 * @throws IOException if the socket is closed
	 */
	public boolean ready() throws IOException {
		flushOutputs();
		return available() > 0;
	}

}
