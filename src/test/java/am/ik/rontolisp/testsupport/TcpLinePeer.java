package am.ik.rontolisp.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A TCP peer on the loopback for a program whose sockets only connect -- a
 * {@code --no-wasi} module's, whose host has no listener to be its own peer with. It
 * answers a connection's lines: {@code echo TEXT} with the text, {@code later MILLIS}
 * with {@code late} after that long, {@code octets} with the 256 octets,
 * {@code big COUNT} with that many octets and its end, {@code bye} with its end, any
 * other line with nothing; and ends a connection when its client does.
 */
public final class TcpLinePeer implements AutoCloseable {

	/**
	 * {@code connect()} of {@code cloudflare:sockets} over {@code node:net}, as the
	 * JavaScript a {@code node} host of a test hands the generated glue: a hostname and a
	 * port in; the two streams, the promise of the connection and a close out.
	 */
	public static final String NODE_CONNECT = """
			import { connect as dial } from "node:net";
			import { Duplex } from "node:stream";

			const connect = ({ hostname, port }) => {
			  const socket = dial({ host: hostname, port, allowHalfOpen: true });
			  const opened = new Promise((resolve, reject) => {
			    socket.once("connect", () =>
			      resolve({
			        remoteAddress: socket.remoteAddress + ":" + socket.remotePort,
			        localAddress: socket.localAddress + ":" + socket.localPort,
			      }),
			    );
			    socket.once("error", reject);
			  });
			  opened.catch(() => {});
			  const { readable, writable } = Duplex.toWeb(socket);
			  return { readable, writable, opened, close: async () => socket.destroy() };
			};
			""";

	private final ServerSocket listener;

	/**
	 * Starts the peer on a port of the host's choosing.
	 * @throws IOException if no listener can be made
	 */
	public TcpLinePeer() throws IOException {
		this.listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
		Thread accepting = new Thread(() -> {
			while (true) {
				Socket client;
				try {
					client = this.listener.accept();
				}
				catch (IOException closed) {
					return;
				}
				Thread answering = new Thread(() -> answer(client), "tcp-line-peer");
				answering.setDaemon(true);
				answering.start();
			}
		}, "tcp-line-peer-accept");
		accepting.setDaemon(true);
		accepting.start();
	}

	/**
	 * The port the peer listens on.
	 * @return the port
	 */
	public int port() {
		return this.listener.getLocalPort();
	}

	@Override
	public void close() throws IOException {
		this.listener.close();
	}

	private static void answer(Socket client) {
		try (client) {
			InputStream in = client.getInputStream();
			OutputStream out = client.getOutputStream();
			ByteArrayOutputStream line = new ByteArrayOutputStream();
			int octet;
			while ((octet = in.read()) >= 0) {
				if (octet != '\n') {
					line.write(octet);
					continue;
				}
				String said = line.toString(StandardCharsets.UTF_8);
				line.reset();
				if (said.startsWith("echo ")) {
					out.write((said.substring(5) + "\n").getBytes(StandardCharsets.UTF_8));
				}
				else if (said.startsWith("later ")) {
					Thread.sleep(Long.parseLong(said.substring(6)));
					out.write("late\n".getBytes(StandardCharsets.UTF_8));
				}
				else if (said.equals("octets")) {
					byte[] all = new byte[256];
					for (int i = 0; i < all.length; i++) {
						all[i] = (byte) i;
					}
					out.write(all);
				}
				else if (said.startsWith("big ")) {
					byte[] all = new byte[Integer.parseInt(said.substring(4))];
					for (int i = 0; i < all.length; i++) {
						all[i] = (byte) (i % 251);
					}
					out.write(all);
					return;
				}
				else if (said.equals("bye")) {
					return;
				}
				out.flush();
			}
		}
		catch (IOException | InterruptedException ended) {
			// the client is gone
		}
	}

}
