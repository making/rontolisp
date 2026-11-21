package am.ik.servlet.httpserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A client that sends the octets it is given and reads the reply to the end of the
 * connection, so that a test sees the framing a container chose.
 */
final class Http {

	private Http() {
	}

	/** A reply: the status, the fields by their names in lower case, the decoded body. */
	static final class Reply {

		final int status;

		final Map<String, List<String>> fields = new LinkedHashMap<>();

		final byte[] body;

		Reply(int status, byte[] body) {
			this.status = status;
			this.body = body;
		}

		String field(String name) {
			List<String> values = this.fields.get(name.toLowerCase(Locale.ROOT));
			return values == null ? null : String.join("|", values);
		}

		String text() {
			return new String(this.body, StandardCharsets.UTF_8);
		}

		@Override
		public String toString() {
			return this.status + " " + this.fields + " " + text();
		}

	}

	/** Sends a request with no body. */
	static Reply get(int port, String target, String... fields) throws IOException {
		return send(port, "GET", target, null, fields);
	}

	/**
	 * Sends a request and reads the reply.
	 * @param body the body, sent with its length, or null
	 * @param fields the fields, each as {@code Name: value}
	 */
	static Reply send(int port, String method, String target, byte[] body, String... fields) throws IOException {
		StringBuilder head = new StringBuilder();
		head.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
		boolean host = false;
		for (String field : fields) {
			head.append(field).append("\r\n");
			host |= field.regionMatches(true, 0, "Host:", 0, 5);
		}
		if (!host) {
			head.append("Host: localhost:").append(port).append("\r\n");
		}
		if (body != null) {
			head.append("Content-Length: ").append(body.length).append("\r\n");
		}
		head.append("Connection: close\r\n\r\n");
		ByteArrayOutputStream request = new ByteArrayOutputStream();
		request.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
		if (body != null) {
			request.write(body);
		}
		return raw(port, request.toByteArray());
	}

	/** Sends octets and reads the reply to the end of the connection. */
	static Reply raw(int port, byte[] request) throws IOException {
		byte[] all;
		try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
			socket.setSoTimeout(10_000);
			socket.getOutputStream().write(request);
			socket.getOutputStream().flush();
			InputStream in = socket.getInputStream();
			ByteArrayOutputStream reply = new ByteArrayOutputStream();
			in.transferTo(reply);
			all = reply.toByteArray();
		}
		int end = indexOf(all, new byte[] { '\r', '\n', '\r', '\n' }, 0);
		if (end < 0) {
			throw new IOException("no reply head in " + all.length + " octets");
		}
		String[] lines = new String(all, 0, end, StandardCharsets.ISO_8859_1).split("\r\n");
		Map<String, List<String>> fields = new LinkedHashMap<>();
		for (int i = 1; i < lines.length; i++) {
			int colon = lines[i].indexOf(':');
			fields.computeIfAbsent(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), name -> new ArrayList<>())
				.add(lines[i].substring(colon + 1).trim());
		}
		byte[] body = new byte[all.length - end - 4];
		System.arraycopy(all, end + 4, body, 0, body.length);
		if (fields.containsKey("transfer-encoding")) {
			body = unchunk(body);
		}
		Reply reply = new Reply(Integer.parseInt(lines[0].split(" ")[1]), body);
		reply.fields.putAll(fields);
		return reply;
	}

	private static byte[] unchunk(byte[] chunked) throws IOException {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		int at = 0;
		while (true) {
			int line = indexOf(chunked, new byte[] { '\r', '\n' }, at);
			if (line < 0) {
				throw new IOException("the chunks end without the last one");
			}
			String size = new String(chunked, at, line - at, StandardCharsets.ISO_8859_1);
			int semicolon = size.indexOf(';');
			int length = Integer.parseInt((semicolon < 0 ? size : size.substring(0, semicolon)).trim(), 16);
			if (length == 0) {
				return body.toByteArray();
			}
			body.write(chunked, line + 2, length);
			at = line + 2 + length + 2;
		}
	}

	private static int indexOf(byte[] in, byte[] what, int from) {
		outer: for (int i = from; i <= in.length - what.length; i++) {
			for (int k = 0; k < what.length; k++) {
				if (in[i + k] != what[k]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

}
