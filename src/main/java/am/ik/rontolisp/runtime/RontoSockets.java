package am.ik.rontolisp.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

/**
 * The socket a connect starts from, as the interpreter and the JVM backend make and
 * connect it ({@code rontolisp::%tcp-socket}, {@code rontolisp::%tcp-connect-socket}): a
 * socket made before its connect -- bound first or not -- which a close from another
 * thread ends the connect of, and a connect given a time limit.
 *
 * <p>
 * The class imports nothing of rontolisp, so it travels beside a compiled program that
 * uses sockets ({@code .kb/jvm-export.md}, "What travels").
 */
public final class RontoSockets {

	private RontoSockets() {
	}

	/**
	 * A socket not connected yet: bound to {@code host:port} when {@code port} is not
	 * negative -- every interface for an empty host, a port of the host's choosing for 0
	 * -- else not bound. A socket whose bind failed is closed.
	 * @param host an address or a name the host looks up, or empty
	 * @param port the local port, or -1 for none
	 * @return the socket
	 * @throws IOException if the bind fails
	 */
	public static Socket open(String host, int port) throws IOException {
		Socket socket = new Socket();
		if (port < 0) {
			return socket;
		}
		try {
			socket.bind(new InetSocketAddress(host.isEmpty() ? null : InetAddress.getByName(host), port));
			return socket;
		}
		catch (IOException ex) {
			socket.close();
			throw ex;
		}
	}

	/**
	 * Connects a socket {@link #open} made to {@code host:port} within {@code millis}.
	 * @param socket the socket
	 * @param host an address or a name the host looks up
	 * @param port the remote port
	 * @param millis the time limit in milliseconds, 0 for as long as the host's connect
	 * waits
	 * @throws IOException if the connect fails, passes its limit or the socket is closed
	 * meanwhile
	 */
	public static void connect(Socket socket, String host, int port, int millis) throws IOException {
		socket.connect(new InetSocketAddress(host, port), millis);
	}

	/**
	 * Ends half of a connected socket: its output, after what its output buffer holds has
	 * gone out, or its input.
	 * @param socket the connected socket
	 * @param output whether the output half ends, else the input half
	 * @param outputs the program's socket output buffers
	 * @throws IOException if the host refuses
	 */
	public static void shutdown(Socket socket, boolean output, Map<?, ? extends OutputStream> outputs)
			throws IOException {
		if (output) {
			OutputStream buffer = outputs.get(socket);
			if (buffer != null) {
				buffer.flush();
			}
			socket.shutdownOutput();
		}
		else {
			socket.shutdownInput();
		}
	}

	/**
	 * Gives an option of a connected socket to the host: 0 {@code TCP_NODELAY} and 1
	 * {@code SO_KEEPALIVE} (1 on, 0 off), 2 {@code SO_LINGER} (seconds, -1 off), 3
	 * {@code SO_OOBINLINE} (1 on, 0 off), 4 {@code IP_TOS}; or, for 5, sends the byte
	 * {@code value} as urgent data after what the socket's output buffer holds.
	 * @param socket the connected socket
	 * @param option the option
	 * @param value its value
	 * @param outputs the program's socket output buffers
	 * @throws IOException if the host refuses
	 */
	public static void option(Socket socket, int option, int value, Map<?, ? extends OutputStream> outputs)
			throws IOException {
		int v = value;
		switch (option) {
			case 0 -> socket.setTcpNoDelay(v != 0);
			case 1 -> socket.setKeepAlive(v != 0);
			case 2 -> socket.setSoLinger(v >= 0, Math.max(v, 0));
			case 3 -> socket.setOOBInline(v != 0);
			case 4 -> socket.setTrafficClass(v);
			case 5 -> {
				OutputStream buffer = outputs.get(socket);
				if (buffer != null) {
					buffer.flush();
				}
				socket.sendUrgentData(v);
			}
			default -> throw new IOException("no option " + option);
		}
	}

}
