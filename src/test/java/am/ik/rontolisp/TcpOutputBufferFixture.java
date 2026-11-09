package am.ik.rontolisp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A connected socket's output buffer ({@code .kb/tcp-sockets.md}, "Per-backend
 * mechanics"), against a JDK peer that counts its reads, on every backend with sockets:
 * the interpreter, the JVM, the WASM component and a {@code --native} executable. A line
 * written a byte at a time arrives in ONE read -- written through, each byte after the
 * first waited for the peer's delayed ACK under the host's Nagle. Then a byte, a write
 * past the buffer (sent as it is, after the byte), and a line the program never flushes,
 * which its end sends.
 */
public final class TcpOutputBufferFixture implements AutoCloseable {

	/** The program, formatted with the peer's port. */
	private static final String PROGRAM = """
			(let ((s (rontolisp:tcp-connect "127.0.0.1" %d)))
			  (dotimes (i 100) (write-byte 97 s))
			  (terpri s)
			  (print (read-line s))
			  (write-string "x" s)
			  (write-string (make-string 20000 :initial-element #\\b) s)
			  (write-line "bye" s))
			""";

	/** What the program prints: the peer's count of reads for the first line. */
	public static final String EXPECTED_OUTPUT = "\"1\"";

	/** What the peer received: the first line's length, then every later byte. */
	public static final String EXPECTED_RECEIVED = "101:x" + "b".repeat(20000) + "bye\n";

	private final ServerSocket listener;

	private final CompletableFuture<String> received;

	private TcpOutputBufferFixture() throws IOException {
		this.listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		this.received = CompletableFuture.supplyAsync(this::serve);
	}

	/**
	 * Starts the peer on an ephemeral loopback port.
	 * @return the running peer
	 * @throws IOException if the port cannot be bound
	 */
	public static TcpOutputBufferFixture start() throws IOException {
		return new TcpOutputBufferFixture();
	}

	/**
	 * The program connecting to this peer.
	 * @return the program text
	 */
	public String program() {
		return PROGRAM.formatted(this.listener.getLocalPort());
	}

	/**
	 * What the peer received, once the program's last line has arrived.
	 * @return {@link #EXPECTED_RECEIVED} when the program buffered as it should
	 * @throws Exception if the peer failed or timed out
	 */
	public String received() throws Exception {
		return this.received.get(60, TimeUnit.SECONDS);
	}

	// Reads up to "bye\n" rather than to the close: an in-process interpreter or JVM run
	// leaves its socket open.
	private String serve() {
		try (Socket s = this.listener.accept()) {
			s.setSoTimeout(30_000);
			InputStream in = s.getInputStream();
			byte[] buf = new byte[65536];
			ByteArrayOutputStream line = new ByteArrayOutputStream();
			int reads = 0;
			while (line.size() == 0 || buf[line.size() - 1] != '\n') {
				int n = in.read(buf, line.size(), buf.length - line.size());
				if (n < 0) {
					break;
				}
				line.write(buf, line.size(), n);
				reads++;
			}
			s.getOutputStream().write((reads + "\n").getBytes(StandardCharsets.US_ASCII));
			ByteArrayOutputStream rest = new ByteArrayOutputStream();
			byte[] chunk = new byte[65536];
			while (!rest.toString(StandardCharsets.US_ASCII).endsWith("bye\n")) {
				int n = in.read(chunk);
				if (n < 0) {
					break;
				}
				rest.write(chunk, 0, n);
			}
			return line.size() + ":" + rest.toString(StandardCharsets.US_ASCII);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Override
	public void close() throws IOException {
		this.listener.close();
	}

}
