package am.ik.rontolisp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 * which its end sends. The peer's answer is followed by a line of a million octets, a
 * multi-byte first character, an inner {@code \r} and a {@code \r\n} end, read in one
 * call: a read-line taking it a byte at a time with a copy each is quadratic. Then lines
 * read with their missing-newline-p at top level (an await on the WASM targets): an
 * invalid octet before a sequence the peer splits across two writes and before ASCII,
 * each one U+FFFD as the JDK's decoder and SBCL's {@code :replacement} answer; then
 * characters read one at a time, where a malformed sequence is one U+FFFD and the octet
 * that cut it short is left to start the next character -- the last cut short by an octet
 * the peer sends only after the program's {@code !}, which the program's read flushes
 * once the octets before it are spent; then a last line the peer's half-close ends, and
 * the end of input.
 */
public final class TcpOutputBufferFixture implements AutoCloseable {

	/** The program, formatted with the peer's port. */
	private static final String PROGRAM = """
			(let ((s (rontolisp:tcp-connect "127.0.0.1" %d)))
			  (dotimes (i 100) (write-byte 97 s))
			  (terpri s)
			  (flet ((codes (values)
			           (cons (if (stringp (car values)) (map 'list #'char-code (car values)) (car values))
			                 (cdr values))))
			    (print (list (read-line s)
			                 (let ((line (read-line s)))
			                   (list (length line) (char-code (char line 0))
			                         (char-code (char line (- (length line) 2)))
			                         (char-code (char line (- (length line) 1)))))
			                 (codes (multiple-value-list (read-line s nil :eof)))
			                 (codes (multiple-value-list (read-line s nil :eof)))
			                 (progn
			                   (write-char #\\! s)
			                   (let ((chars nil))
			                     (dotimes (i 12)
			                       (let ((c (read-char s nil :eof)))
			                         (push (if (characterp c) (char-code c) c) chars)))
			                     (nreverse chars)))
			                 (codes (multiple-value-list (read-line s nil :eof)))
			                 (codes (multiple-value-list (read-line s nil :eof))))))
			  (write-string "x" s)
			  (write-string (make-string 20000 :initial-element #\\b) s)
			  (write-line "bye" s))
			""";

	/** The octets of the long line's body, between its first and last characters. */
	private static final int LONG_LINE = 1_000_000;

	/**
	 * What the program prints: the peer's count of reads for the first line, the long
	 * line's length and its first and last two characters, then the codes and
	 * missing-newline-p of each later line, with the characters read one at a time before
	 * the last.
	 */
	public static final String EXPECTED_OUTPUT = "(\"1\" (" + (LONG_LINE + 3)
			+ " 233 13 99) ((65533 233 122) NIL) ((65533 122) NIL) (65533 122 10 65533 65 10 65 65533 66 10 65533 66) ((120 121 122) T) (:EOF T))";

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
			OutputStream out = s.getOutputStream();
			out.write((reads + "\n").getBytes(StandardCharsets.US_ASCII));
			out.write(("\u00e9" + "b".repeat(LONG_LINE) + "\rc\r\n").getBytes(StandardCharsets.UTF_8));
			out.write(new byte[] { (byte) 0xff, (byte) 0xc3 });
			Thread.sleep(200);
			out.write(new byte[] { (byte) 0xa9, 'z', '\n', (byte) 0xff, 'z', '\n', (byte) 0xff, 'z', '\n', (byte) 0xc3,
					'A', '\n', 'A', (byte) 0xe9, 'B', '\n', (byte) 0xe9 });
			if (in.read() != '!') {
				throw new IllegalStateException("expected the program's ! before the rest");
			}
			out.write(new byte[] { 'B', 'x', 'y', 'z' });
			s.shutdownOutput();
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
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	@Override
	public void close() throws IOException {
		this.listener.close();
	}

}
