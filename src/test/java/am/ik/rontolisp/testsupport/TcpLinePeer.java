package am.ik.rontolisp.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;

import am.ik.rontolisp.TlsTestSupport;

/**
 * A TCP peer on the loopback for a program whose sockets only connect -- a
 * {@code --no-wasi} module's, whose host has no listener to be its own peer with. It
 * answers a connection's lines: {@code echo TEXT} with the text, {@code later MILLIS}
 * with {@code late} after that long, {@code octets} with the 256 octets,
 * {@code big COUNT} with that many octets and its end, {@code bye} with its end,
 * {@code starttls} with {@code go} and then the server's side of a TLS handshake
 * ({@link TlsTestSupport#serverContext()}), after which the lines go on over TLS, any
 * other line with nothing; and ends a connection when its client does.
 */
public final class TcpLinePeer implements AutoCloseable {

	/**
	 * {@code connect()} of {@code cloudflare:sockets} over {@code node:net} and
	 * {@code node:tls}, as the JavaScript a {@code node} host of a test hands the
	 * generated glue: a hostname, a port and the options in; the two streams, the promise
	 * of the connection, a close and, for a socket made {@code "starttls"},
	 * {@code startTls} out. A TLS peer is trusted by the certificates of the PEM file the
	 * environment variable {@code RONTOLISP_TEST_CA} names, else by node's own.
	 */
	public static final String NODE_CONNECT = """
			import { connect as dial, isIP } from "node:net";
			import { connect as seal } from "node:tls";
			import { readFileSync as readTrusted } from "node:fs";

			const ca = process.env.RONTOLISP_TEST_CA ? readTrusted(process.env.RONTOLISP_TEST_CA) : undefined;

			// A node socket as the platform's, open once `ready` has fired. Its readable
			// takes a chunk per read; letting go of it (a cancel, or startTls) leaves the
			// connection, which the platform's startTls() takes over.
			const wrap = (socket, ready, upgrade) => {
			  const opened = new Promise((resolve, reject) => {
			    socket.once(ready, () =>
			      resolve({
			        remoteAddress: socket.remoteAddress + ":" + socket.remotePort,
			        localAddress: socket.localAddress + ":" + socket.localPort,
			      }),
			    );
			    socket.once("error", reject);
			  });
			  opened.catch(() => {});
			  socket.on("error", () => {});
			  let detach = () => {};
			  const readable = new ReadableStream(
			    {
			      start(controller) {
			        socket.pause();
			        const data = (chunk) => {
			          socket.pause();
			          controller.enqueue(new Uint8Array(chunk));
			        };
			        const end = () => controller.close();
			        const error = (e) => controller.error(e);
			        socket.on("data", data).on("end", end).on("error", error);
			        detach = () => socket.off("data", data).off("end", end).off("error", error).pause();
			      },
			      pull() {
			        socket.resume();
			      },
			      cancel() {
			        detach();
			      },
			    },
			    { highWaterMark: 0 },
			  );
			  const writable = new WritableStream({
			    write: (chunk) =>
			      new Promise((resolve, reject) => socket.write(chunk, (e) => (e ? reject(e) : resolve()))),
			    close: () => new Promise((resolve) => socket.end(resolve)),
			    abort: () => void socket.destroy(),
			  });
			  const startTls = (options = {}) => {
			    if (upgrade === undefined) {
			      throw new TypeError("The `secureTransport` socket option must be set to 'starttls' for startTls to be used.");
			    }
			    detach();
			    return upgrade(options);
			  };
			  return { readable, writable, opened, close: async () => socket.destroy(), startTls };
			};

			const connect = ({ hostname, port }, options = {}) => {
			  const socket = dial({ host: hostname, port, allowHalfOpen: true });
			  const upgrade = ({ expectedServerHostname: name = hostname }) =>
			    wrap(seal({ socket, host: name, servername: isIP(name) ? undefined : name, ca }), "secureConnect");
			  return wrap(socket, "connect", options.secureTransport === "starttls" ? upgrade : undefined);
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
				else if (said.equals("starttls")) {
					out.write("go\n".getBytes(StandardCharsets.UTF_8));
					out.flush();
					SSLSocket sealed = (SSLSocket) TlsTestSupport.serverContext()
						.getSocketFactory()
						.createSocket(client, null, client.getPort(), true);
					sealed.setUseClientMode(false);
					in = sealed.getInputStream();
					out = sealed.getOutputStream();
				}
				out.flush();
			}
		}
		catch (IOException | InterruptedException ended) {
			// the client is gone
		}
	}

}
