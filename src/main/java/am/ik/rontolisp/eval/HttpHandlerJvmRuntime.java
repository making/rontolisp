package am.ik.rontolisp.eval;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import am.ik.rontolisp.compiler.ClackEnv;
import am.ik.rontolisp.compiler.JvmHashTableShape;
import org.jspecify.annotations.Nullable;

/**
 * The JVM backend's per-request Clack glue, called from the emitted
 * {@code handle(Request)} method. A compiled {@code rontolisp:http-handler} class is not
 * standalone (it needs the rontolisp jar on the runtime classpath -- documented since the
 * directive shipped), so the environment construction and the response marshalling run as
 * real Java here instead of hand-assembled bytecode: the same speed, none of the
 * hand-written {@code maxStack} risk. The emitted method keeps only what must be
 * bytecode: the {@code :raw-body} construction (the buffered stream is the compiled
 * {@code %http-body-stream} Gray instance), the handler dispatch through
 * {@code _invoke_1}/{@code _await}, and the compiled {@code %http-normalize-response}
 * call, whose delayed-response arm must {@code funcall} back into compiled code.
 *
 * <p>
 * Everything here speaks the JVM backend's RUNTIME VALUE REPRESENTATION: nil is
 * {@code null}, a cons is an {@code Object[2]}, an integer is a {@code Long}, a string is
 * its quote-wrapped text, a symbol its bare name, and a hash table is a bucket index
 * built through {@link JvmHashTableShape} -- the ONE declaration of that shape, shared
 * with the emitter of the {@code _hash*} helpers, so the table this builds and the table
 * compiled code builds cannot drift apart. The table class is exact, not merely
 * map-shaped: the emitted helpers cast to it and both {@code hash-table-p} and the
 * printer key off it, so a plain {@code HashMap} here would fail the cast at the first
 * {@code gethash} (pinned by
 * {@code JvmHashRuntimeBuilderTest#theHandwrittenRuntimeBuildsTheSameTableClass}).
 *
 * <p>
 * The environment's KEY SET and order are {@link ClackEnv#FIELDS}; only the per-field
 * value extraction is this backend's, and an unmapped field throws -- the same drift
 * guard every backend applies to the same declaration.
 */
public final class HttpHandlerJvmRuntime {

	private HttpHandlerJvmRuntime() {
	}

	/**
	 * Builds the Clack environment plist for one served request, in the JVM runtime value
	 * representation.
	 * @param request the transport facts
	 * @param rawBody the ready {@code :raw-body} value (the emitted code constructs it:
	 * nil, the asynchronous stream, or the compiled Gray instance)
	 * @return the environment plist ({@code Object[2]} cons chain; declared nullable only
	 * for the accumulator's sake -- fifteen fields always cons at least one cell)
	 */
	public static @Nullable Object buildEnv(HttpHandlerSupport.Request request, @Nullable Object rawBody) {
		String target = request.target();
		int q = target.indexOf('?');
		String path = q < 0 ? target : target.substring(0, q);
		Object query = q < 0 ? null : quote(target.substring(q + 1));
		// The header table: lowercased names, repeated headers joined with ", " in wire
		// order (the Clack handler-backend rule), never nil.
		LinkedHashMap<Object, Object> headers = JvmHashTableShape.newTable();
		String host = null;
		String contentType = null;
		String contentLength = null;
		for (HttpHandlerSupport.Header header : request.headers()) {
			String name = header.name().toLowerCase(Locale.ROOT);
			String key = quote(name);
			Object seen = JvmHashTableShape.get(headers, key);
			String value = seen == null ? header.value() : unquote((String) seen) + ", " + header.value();
			JvmHashTableShape.put(headers, key, quote(value));
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
		if (host != null) {
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
		// The plist, built back-to-front so it comes out in ClackEnv.FIELDS order --
		// freshly consed and proper on every request (lack-request rplacds its last
		// cons, the mount / session middleware setf getf into it).
		Object plist = null;
		List<String> fields = ClackEnv.FIELDS;
		for (int i = fields.size() - 1; i >= 0; i--) {
			String field = fields.get(i);
			Object value = switch (field) {
				case ClackEnv.REQUEST_METHOD -> ":" + request.method().toUpperCase(Locale.ROOT);
				case ClackEnv.SCRIPT_NAME -> quote("");
				case ClackEnv.PATH_INFO -> quote(LispEvaluator.percentDecode(path));
				case ClackEnv.QUERY_STRING -> query;
				case ClackEnv.SERVER_NAME -> quote(serverName == null ? "localhost" : serverName);
				case ClackEnv.SERVER_PORT -> serverPort == 0 ? Long.valueOf(80) : serverPort;
				case ClackEnv.SERVER_PROTOCOL -> ":" + request.protocol().toUpperCase(Locale.ROOT);
				case ClackEnv.REQUEST_URI -> quote(target);
				case ClackEnv.URL_SCHEME -> quote(request.scheme());
				case ClackEnv.REMOTE_ADDR -> request.remoteAddr() == null ? null : quote(request.remoteAddr());
				case ClackEnv.REMOTE_PORT -> request.remotePort() == 0 ? null : Long.valueOf(request.remotePort());
				case ClackEnv.HEADERS -> headers;
				case ClackEnv.CONTENT_TYPE -> contentType == null ? null : quote(contentType);
				case ClackEnv.CONTENT_LENGTH -> parseContentLength(contentLength);
				case ClackEnv.RAW_BODY -> rawBody;
				default -> throw new IllegalStateException(
						"http-handler has no JVM extraction for environment field " + field);
			};
			plist = new Object[] { field, new Object[] { value, plist } };
		}
		return plist;
	}

	/**
	 * Marshals the canonical response triple the compiled
	 * {@code %http-normalize-response} returned -- {@code (status header-alist body)} in
	 * the JVM runtime representation -- into the {@link HttpHandlerSupport.Response} the
	 * server writes.
	 * @param triple the normalized response triple
	 * @param drainedBody the triple's body after the emitted {@code _drain_body} pass (a
	 * stream body arrives as its quoted concatenation, an {@code (unsigned-byte 8)} body
	 * as the packed vector the normalizer deliberately did not flatten)
	 * @return the response to write back
	 */
	public static HttpHandlerSupport.Response toResponse(Object triple, @Nullable Object drainedBody) {
		Object[] cons = (Object[]) triple;
		int status = (int) ((Long) cons[0]).longValue();
		Object[] rest = (Object[]) cons[1];
		List<HttpHandlerSupport.Header> headers = new ArrayList<>();
		Object cursor = rest[0];
		while (cursor instanceof Object[] cell) {
			// Each entry is a (name . value) pair of quoted strings; the normalizer
			// already lowercased the names and dropped the framing headers.
			if (cell[0] instanceof Object[] pair && pair[0] instanceof String name && pair[1] instanceof String value) {
				headers.add(new HttpHandlerSupport.Header(unquote(name), unquote(value)));
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
		return new HttpHandlerSupport.Response(status, headers, body);
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
	public static long[] bodyOctets(HttpHandlerSupport.Request request) {
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

	private static @Nullable Object parseContentLength(@Nullable String value) {
		if (value == null) {
			return null;
		}
		int end = 0;
		while (end < value.length() && Character.isDigit(value.charAt(end))) {
			end++;
		}
		return end == 0 ? null : Long.parseLong(value.substring(0, end));
	}

	private static String quote(String s) {
		return "\"" + s + "\"";
	}

	private static String unquote(String s) {
		return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
	}

}
