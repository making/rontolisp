package am.ik.rontolisp.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * The JVM backend's per-request Clack glue, called from the emitted
 * {@code handle(Request)} method: the environment construction and the response
 * marshalling run as real Java here instead of hand-assembled bytecode -- the same speed,
 * none of the hand-written {@code maxStack} risk. It is in {@code runtime}, and imports
 * nothing of the project's, so that it TRAVELS with the compiled class
 * ({@code .kb/jvm-export.md}) and the program runs on a bare {@code java -cp .}. The
 * emitted method keeps only what must be bytecode: the {@code :raw-body} construction
 * (the buffered stream is the compiled {@code %http-body-stream} Gray instance), the
 * handler dispatch through {@code _invoke_1}/{@code _await}, and the compiled
 * {@code %http-normalize-response} call, whose delayed-response arm must {@code funcall}
 * back into compiled code.
 *
 * <p>
 * Everything here speaks the JVM backend's RUNTIME VALUE REPRESENTATION: nil is
 * {@code null}, a cons is an {@code Object[2]}, an integer is a {@code Long}, a string is
 * its quote-wrapped text, a symbol its bare name, and a hash table is a bucket index
 * built through {@link RontoHashTable} -- the ONE declaration of that shape, shared with
 * the emitter of the {@code _hash*} helpers, so the table this builds and the table
 * compiled code builds cannot drift apart. The table class is exact, not merely
 * map-shaped: the emitted helpers cast to it and both {@code hash-table-p} and the
 * printer key off it, so a plain {@code HashMap} here would fail the cast at the first
 * {@code gethash} (pinned by
 * {@code JvmHashRuntimeBuilderTest#theHandwrittenRuntimeBuildsTheSameTableClass}).
 *
 * <p>
 * The environment's KEY SET and order are {@link RontoClackEnv#FIELDS}; only the
 * per-field value extraction is this backend's, and an unmapped field throws -- the same
 * drift guard every backend applies to the same declaration.
 */
public final class RontoHttpClack {

	private RontoHttpClack() {
	}

	/**
	 * Builds the Clack environment plist for one served request, in the JVM runtime value
	 * representation.
	 * @param request the transport facts
	 * @param rawBody the ready {@code :raw-body} value (the emitted code constructs it:
	 * nil -- which is a real {@code null} in this representation -- the asynchronous
	 * stream, or the compiled Gray instance)
	 * @return the environment plist ({@code Object[2]} cons chain)
	 */
	public static Object buildEnv(RontoHttpServer.Request request, Object rawBody) {
		String target = request.target();
		int q = target.indexOf('?');
		String path = q < 0 ? target : target.substring(0, q);
		Object query = q < 0 ? null : quote(target.substring(q + 1));
		// The mounted split (the Servlet war under a context path): the RAW mount
		// prefix comes off the target path BEFORE percent-decoding -- that is what
		// makes it strippable at all -- and :script-name is its decode, so both halves
		// come out decoded, the same shape lack's mount middleware produces when it
		// moves a matched prefix across. A scriptName that is not a prefix of the
		// target degrades to the root-mounted split rather than signalling.
		String script = request.scriptName();
		String scriptName = "";
		String pathInfo = path;
		if (!script.isEmpty() && path.startsWith(script)) {
			scriptName = percentDecode(script);
			pathInfo = path.substring(script.length());
		}
		// The header table: lowercased names, repeated headers joined with ", " in wire
		// order (the Clack handler-backend rule), never nil.
		LinkedHashMap<Object, Object> headers = RontoHashTable.newTable();
		// "" is "the header was not sent" throughout: this package carries no @Nullable
		// (see RontoHashTable), and no HTTP header value that matters here is empty.
		String host = "";
		String contentType = "";
		String contentLength = "";
		for (RontoHttpServer.Header header : request.headers()) {
			String name = header.name().toLowerCase(Locale.ROOT);
			String key = quote(name);
			Object seen = RontoHashTable.get(headers, key, "");
			String value = ((String) seen).isEmpty() ? header.value() : unquote((String) seen) + ", " + header.value();
			RontoHashTable.put(headers, key, quote(value));
			switch (name) {
				case "host" -> host = value;
				case "content-type" -> contentType = value;
				case "content-length" -> contentLength = value;
				default -> {
				}
			}
		}
		String serverName = request.localName();
		long serverPort = request.localPort();
		if (!host.isEmpty()) {
			int colon = host.lastIndexOf(':');
			String tail = colon < 0 ? "" : host.substring(colon + 1);
			if (colon >= 0 && !tail.isEmpty() && tail.chars().allMatch(Character::isDigit)) {
				serverName = host.substring(0, colon);
				serverPort = Long.parseLong(tail);
			}
			else {
				serverName = host;
			}
		}
		// :content-length is an integer, or nil (null in this representation) when the
		// header was absent or carried no leading digits.
		Object contentLengthValue = null;
		int digits = 0;
		while (digits < contentLength.length() && Character.isDigit(contentLength.charAt(digits))) {
			digits++;
		}
		if (digits > 0) {
			contentLengthValue = Long.valueOf(Long.parseLong(contentLength.substring(0, digits)));
		}
		// The plist, built back-to-front so it comes out in RontoClackEnv.FIELDS order --
		// freshly consed and proper on every request (lack-request rplacds its last
		// cons, the mount / session middleware setf getf into it).
		Object plist = null;
		List<String> fields = RontoClackEnv.FIELDS;
		for (int i = fields.size() - 1; i >= 0; i--) {
			String field = fields.get(i);
			Object value = switch (field) {
				case RontoClackEnv.REQUEST_METHOD -> ":" + request.method().toUpperCase(Locale.ROOT);
				case RontoClackEnv.SCRIPT_NAME -> quote(scriptName);
				case RontoClackEnv.PATH_INFO -> quote(percentDecode(pathInfo));
				case RontoClackEnv.QUERY_STRING -> query;
				case RontoClackEnv.SERVER_NAME -> quote(serverName.isEmpty() ? "localhost" : serverName);
				case RontoClackEnv.SERVER_PORT -> serverPort == 0 ? Long.valueOf(80) : serverPort;
				case RontoClackEnv.SERVER_PROTOCOL -> ":" + request.protocol().toUpperCase(Locale.ROOT);
				case RontoClackEnv.REQUEST_URI -> quote(target);
				case RontoClackEnv.URL_SCHEME -> quote(request.scheme());
				case RontoClackEnv.REMOTE_ADDR -> request.remoteAddr().isEmpty() ? null : quote(request.remoteAddr());
				case RontoClackEnv.REMOTE_PORT -> request.remotePort() == 0 ? null : Long.valueOf(request.remotePort());
				case RontoClackEnv.HEADERS -> headers;
				case RontoClackEnv.CONTENT_TYPE -> contentType.isEmpty() ? null : quote(contentType);
				case RontoClackEnv.CONTENT_LENGTH -> contentLengthValue;
				case RontoClackEnv.RAW_BODY -> rawBody;
				default -> throw new IllegalStateException(
						"http-handler has no JVM extraction for environment field " + field);
			};
			plist = new Object[] { field, new Object[] { value, plist } };
		}
		// Fifteen fields always cons at least one cell, so the accumulator's nil start is
		// gone by here.
		return java.util.Objects.requireNonNull(plist);
	}

	/**
	 * Marshals the canonical response triple the compiled
	 * {@code %http-normalize-response} returned -- {@code (status header-alist body)} in
	 * the JVM runtime representation -- into the {@link RontoHttpServer.Response} the
	 * server writes.
	 * @param triple the normalized response triple
	 * @param drainedBody the triple's body after the emitted {@code _drain_body} pass (a
	 * stream body arrives as its quoted concatenation, an {@code (unsigned-byte 8)} body
	 * as the packed vector the normalizer deliberately did not flatten)
	 * @return the response to write back
	 */
	public static RontoHttpServer.Response toResponse(Object triple, Object drainedBody) {
		Object[] cons = (Object[]) triple;
		int status = (int) ((Long) cons[0]).longValue();
		Object[] rest = (Object[]) cons[1];
		List<RontoHttpServer.Header> headers = new ArrayList<>();
		Object cursor = rest[0];
		while (cursor instanceof Object[] cell) {
			// Each entry is a (name . value) pair of quoted strings; the normalizer
			// already lowercased the names and dropped the framing headers.
			if (cell[0] instanceof Object[] pair && pair[0] instanceof String name && pair[1] instanceof String value) {
				headers.add(new RontoHttpServer.Header(unquote(name), unquote(value)));
			}
			cursor = cell[1];
		}
		byte[] body = switch (drainedBody) {
			case String text -> unquote(text).getBytes(StandardCharsets.UTF_8);
			// An (unsigned-byte 8) body: long[]{width, e0, ...} here. The octets go out
			// as they are -- which is why the shared normalizer deliberately did not
			// flatten them into characters this transport would then UTF-8 encode.
			case long[] octets -> octetsBytes(octets);
			case null, default -> EMPTY_BODY;
		};
		return new RontoHttpServer.Response(status, headers, body);
	}

	private static final byte[] EMPTY_BODY = new byte[0];

	/**
	 * The request body as the packed {@code (unsigned-byte 8)} vector of the JVM runtime
	 * ({@code long[]{8, e0, ...}}) -- the octets as they came, for both {@code :raw-body}
	 * modes: the emitted {@code handle} hands them to the compiled
	 * {@code %http-body-stream} (a byte stream, which must never see a re-encoded body)
	 * or writes them as the default asynchronous stream's one chunk (an octet stream on
	 * every backend). A bodiless request answers the bare width header.
	 * @param request the served request
	 * @return the body octets, {@code long[]{8, e0, ...}}
	 */
	public static long[] bodyOctets(RontoHttpServer.Request request) {
		byte[] bytes = request.body();
		long[] out = new long[bytes.length + 1];
		out[0] = 8;
		for (int i = 0; i < bytes.length; i++) {
			out[i + 1] = bytes[i] & 0xFF;
		}
		return out;
	}

	// long[]{width, e0, ...} -> the raw octets. The elements are already masked to the
	// element width, so the narrowing cannot lose anything.
	private static byte[] octetsBytes(long[] octets) {
		byte[] out = new byte[Math.max(0, octets.length - 1)];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) octets[i + 1];
		}
		return out;
	}

	/**
	 * Percent-decodes a request path. Lenient (a {@code %} not followed by two hex digits
	 * stays literal -- a request target is attacker input) and never decodes {@code +},
	 * which is a query-string rule; {@code http-server.lisp}'s
	 * {@code %http-percent-decode} is the same function for the component backend, and
	 * the interpreter calls this one.
	 * @param s the raw path
	 * @return the decoded path
	 */
	public static String percentDecode(String s) {
		if (s.indexOf('%') < 0) {
			return s;
		}
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
		int i = 0;
		while (i < s.length()) {
			char c = s.charAt(i);
			int hi = i + 2 < s.length() && c == '%' ? Character.digit(s.charAt(i + 1), 16) : -1;
			int lo = hi < 0 ? -1 : Character.digit(s.charAt(i + 2), 16);
			if (lo >= 0) {
				out.write((hi << 4) + lo);
				i += 3;
			}
			else {
				out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
				i++;
			}
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String quote(String s) {
		return "\"" + s + "\"";
	}

	private static String unquote(String s) {
		return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
	}

}
