package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.Nullable;

/**
 * Performs blocking TCP socket operations for the interpreter backend, using the JDK
 * {@link Socket} / {@link ServerSocket}. A socket is stored directly in the interpreter's
 * stream table (both types implement {@link Closeable}, so {@code close} needs no special
 * case) and the stream built-ins ({@code read-line}, {@code write-line},
 * {@code read-byte}, {@code write-byte}) dispatch to the helpers here for socket entries.
 * Reads are byte-at-a-time (no readahead buffer is held between calls) and writes go out
 * immediately, matching the compiled backends. This is the seam the browser playground
 * substitutes (see {@code src/web/java/.../eval/Target_SocketSupport.java}), where every
 * operation signals an error: the browser sandbox provides no host TCP sockets.
 */
final class SocketSupport {

	private SocketSupport() {
	}

	/**
	 * Opens a blocking TCP connection to the given host and port.
	 * @param host a hostname or IP literal
	 * @param port the remote TCP port
	 * @return the connected socket
	 * @throws LispEvalException if the connection cannot be established
	 */
	static Socket connect(String host, int port) {
		try {
			return new Socket(host, port);
		}
		catch (IOException ex) {
			throw new LispEvalException("tcp-connect: cannot connect to " + host + ":" + port + ": " + ex.getMessage());
		}
	}

	/**
	 * Binds a listening TCP socket.
	 * @param port the local port (0 picks an ephemeral port)
	 * @param host the local address to bind, or {@code null} for all interfaces
	 * @return the listening server socket
	 * @throws LispEvalException if the address cannot be bound
	 */
	static ServerSocket listen(int port, @Nullable String host) {
		try {
			ServerSocket listener = new ServerSocket();
			listener.setReuseAddress(true);
			InetAddress address = (host == null) ? null : InetAddress.getByName(host);
			listener.bind(new InetSocketAddress(address, port));
			return listener;
		}
		catch (IOException ex) {
			throw new LispEvalException("tcp-listen: cannot listen on " + ((host == null) ? "*" : host) + ":" + port
					+ ": " + ex.getMessage());
		}
	}

	/**
	 * Blocks until a client connects to the given listener.
	 * @param listener a server socket returned by {@link #listen}
	 * @return the accepted connection socket
	 * @throws LispEvalException if accepting fails (e.g. the listener was closed)
	 */
	static Socket accept(ServerSocket listener) {
		try {
			return listener.accept();
		}
		catch (IOException ex) {
			throw new LispEvalException("tcp-accept: " + ex.getMessage());
		}
	}

	/**
	 * Returns the local port bound to a socket or listener handle, or {@code -1} for any
	 * other stream entry.
	 * @param entry a stream-table entry
	 * @return the local port number, or -1 if the entry is not a TCP handle
	 */
	static long localPort(Closeable entry) {
		if (entry instanceof ServerSocket listener) {
			return listener.getLocalPort();
		}
		if (entry instanceof Socket socket) {
			return socket.getLocalPort();
		}
		return -1;
	}

	/**
	 * Reads one line from a socket: bytes up to a {@code \n} (exclusive, with one
	 * trailing {@code \r} stripped for CRLF parity with {@code BufferedReader.readLine}),
	 * decoded as UTF-8. Reads byte-at-a-time so no readahead is lost between calls.
	 * @param socket the connected socket
	 * @return the line, or {@code null} when the peer closed before any byte arrived
	 */
	static @Nullable String readLine(Socket socket) {
		try {
			InputStream in = socket.getInputStream();
			ByteArrayOutputStream line = new ByteArrayOutputStream();
			int b = in.read();
			if (b < 0) {
				return null;
			}
			while (b >= 0 && b != '\n') {
				line.write(b);
				b = in.read();
			}
			byte[] bytes = line.toByteArray();
			int length = (bytes.length > 0 && bytes[bytes.length - 1] == '\r') ? bytes.length - 1 : bytes.length;
			return new String(bytes, 0, length, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new LispEvalException("read-line: " + ex.getMessage());
		}
	}

	/**
	 * Writes a line (the string plus {@code \n}, UTF-8) to a socket. Socket output
	 * streams are unbuffered, so the bytes go out immediately (unlike buffered file
	 * writers).
	 * @param socket the connected socket
	 * @param line the line to send (without the newline)
	 */
	static void writeLine(Socket socket, String line) {
		try {
			socket.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new LispEvalException("write-line: " + ex.getMessage());
		}
	}

	/**
	 * Reads a single byte from a socket.
	 * @param socket the connected socket
	 * @return the byte value (0-255), or -1 at end of stream
	 */
	static int readByte(Socket socket) {
		try {
			return socket.getInputStream().read();
		}
		catch (IOException ex) {
			throw new LispEvalException("read-byte: " + ex.getMessage());
		}
	}

	/**
	 * Writes a single byte to a socket (sent immediately).
	 * @param socket the connected socket
	 * @param value the byte value (0-255)
	 */
	static void writeByte(Socket socket, int value) {
		try {
			socket.getOutputStream().write(value);
		}
		catch (IOException ex) {
			throw new LispEvalException("write-byte: " + ex.getMessage());
		}
	}

}
