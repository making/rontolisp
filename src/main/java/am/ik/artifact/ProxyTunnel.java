package am.ik.artifact;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

import org.jspecify.annotations.Nullable;

/**
 * One {@code https} GET through an HTTP proxy that takes Basic credentials: a
 * {@code CONNECT} carrying them, TLS over the tunnel, and an HTTP/1.1 exchange over that.
 * The JDK client sends no Basic credentials on a {@code CONNECT} unless the process-wide
 * {@code jdk.http.auth.tunneling.disabledSchemes} property, read once, is emptied;
 * Maven's transport sends them. Every read is bound by the idle timeout, the TLS
 * handshake's included.
 */
final class ProxyTunnel {

	/** The longest status or header line read. */
	private static final int MAX_LINE = 64 * 1024;

	private ProxyTunnel() {
	}

	static HttpMessages.Response get(URI uri, HttpAccess.Proxy proxy, Map<String, String> headers,
			Duration connectTimeout, Duration idleTimeout, SSLContext sslContext) throws IOException {
		String host = uri.getHost();
		if (host == null) {
			throw new IOException("no host in " + uri);
		}
		int port = HttpMessages.port(uri);
		String authority = host + ":" + port;
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(proxy.host(), proxy.port()), (int) connectTimeout.toMillis());
			socket.setSoTimeout((int) Math.max(1, idleTimeout.toMillis()));
			StringBuilder connect = new StringBuilder();
			connect.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n");
			connect.append("Host: ").append(authority).append("\r\n");
			connect.append("Proxy-Authorization: ")
				.append(HttpMessages.basic(Objects.requireNonNull(proxy.credentials())))
				.append("\r\n\r\n");
			OutputStream raw = socket.getOutputStream();
			raw.write(connect.toString().getBytes(StandardCharsets.ISO_8859_1));
			raw.flush();
			// Unbuffered: nothing past the proxy's reply may be consumed before TLS
			// starts.
			InputStream rawIn = socket.getInputStream();
			Head reply = readHead(rawIn);
			if (reply.status() / 100 != 2) {
				throw new HttpStatusException(reply.status(), uri.toString(),
						"the proxy " + proxy + " refused the tunnel to " + authority + " (" + reply.statusLine() + ")");
			}
			SSLSocket tls = (SSLSocket) sslContext.getSocketFactory().createSocket(socket, host, port, true);
			SSLParameters parameters = tls.getSSLParameters();
			parameters.setEndpointIdentificationAlgorithm("HTTPS");
			tls.setSSLParameters(parameters);
			tls.startHandshake();
			StringBuilder request = new StringBuilder();
			String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
			request.append("GET ").append(path).append(uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
			request.append(" HTTP/1.1\r\n");
			request.append("Host: ").append(uri.getPort() == -1 ? host : authority).append("\r\n");
			for (Map.Entry<String, String> header : headers.entrySet()) {
				request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
			}
			request.append("Connection: close\r\n\r\n");
			OutputStream out = tls.getOutputStream();
			out.write(request.toString().getBytes(StandardCharsets.UTF_8));
			out.flush();
			InputStream in = new BufferedInputStream(tls.getInputStream());
			Head head = readHead(in);
			while (head.status() / 100 == 1) {
				head = readHead(in);
			}
			byte[] body = readBody(head, in);
			return new HttpMessages.Response(head.status(), HttpMessages.headers(head.fields()), body);
		}
		catch (SocketTimeoutException ex) {
			throw new IOException("no data from " + uri + " for " + idleTimeout.toMillis() + " ms (server stalled)",
					ex);
		}
	}

	/** A response's status line and header fields. */
	private record Head(String statusLine, int status, List<String[]> fields) {

		@Nullable String field(String name) {
			String value = null;
			for (String[] field : this.fields) {
				if (field[0].equalsIgnoreCase(name)) {
					value = field[1];
				}
			}
			return value;
		}

	}

	private static Head readHead(InputStream in) throws IOException {
		String statusLine = readLine(in);
		String[] parts = statusLine.split(" ", 3);
		if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
			throw new IOException("not an HTTP response: " + statusLine);
		}
		int status;
		try {
			status = Integer.parseInt(parts[1]);
		}
		catch (NumberFormatException ex) {
			throw new IOException("not an HTTP response: " + statusLine, ex);
		}
		List<String[]> fields = new ArrayList<>();
		for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
			int colon = line.indexOf(':');
			if (colon > 0) {
				fields.add(new String[] { line.substring(0, colon).trim(), line.substring(colon + 1).trim() });
			}
		}
		return new Head(statusLine, status, fields);
	}

	private static String readLine(InputStream in) throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		while (true) {
			int b = in.read();
			if (b == -1) {
				throw new EOFException("the connection closed in the middle of a response");
			}
			if (b == '\n') {
				break;
			}
			if (line.size() == MAX_LINE) {
				throw new IOException("a response line longer than " + MAX_LINE + " bytes");
			}
			line.write(b);
		}
		String text = line.toString(StandardCharsets.ISO_8859_1);
		return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
	}

	private static byte[] readBody(Head head, InputStream in) throws IOException {
		if (head.status() == 204 || head.status() == 304) {
			return new byte[0];
		}
		String encoding = head.field("Transfer-Encoding");
		if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked")) {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			while (true) {
				String sizeLine = readLine(in);
				int extension = sizeLine.indexOf(';');
				String size = (extension < 0 ? sizeLine : sizeLine.substring(0, extension)).trim();
				long length;
				try {
					length = Long.parseLong(size, 16);
				}
				catch (NumberFormatException ex) {
					throw new IOException("a malformed chunk size: " + sizeLine, ex);
				}
				if (length == 0) {
					while (!readLine(in).isEmpty()) {
						// trailer fields
					}
					return body.toByteArray();
				}
				body.write(readExactly(in, length));
				readLine(in);
			}
		}
		String contentLength = head.field("Content-Length");
		if (contentLength != null) {
			long length;
			try {
				length = Long.parseLong(contentLength);
			}
			catch (NumberFormatException ex) {
				throw new IOException("a malformed Content-Length: " + contentLength, ex);
			}
			return readExactly(in, length);
		}
		return in.readAllBytes();
	}

	private static byte[] readExactly(InputStream in, long length) throws IOException {
		if (length < 0 || length > Integer.MAX_VALUE - 8) {
			throw new IOException("a body of " + length + " bytes");
		}
		byte[] bytes = in.readNBytes((int) length);
		if (bytes.length != length) {
			throw new EOFException("the connection closed after " + bytes.length + " of " + length + " bytes");
		}
		return bytes;
	}

}
