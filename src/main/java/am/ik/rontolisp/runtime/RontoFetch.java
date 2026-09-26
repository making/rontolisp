package am.ik.rontolisp.runtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The JVM backend's {@code rontolisp:fetch} transport, called from the emitted
 * {@code _fetch}: the request is built, sent and its reply converted as real Java here
 * instead of hand-assembled bytecode. The emitted method keeps only what must be bytecode
 * -- reading the options plist, refusing an unsupported method AT THE CALL, and rendering
 * each argument to its text through the program's own {@code _strv}.
 *
 * <p>
 * {@link #start} answers a future that settles ONCE to the response plist, so every await
 * of one fetch answers the same ({@code eq}) plist and the same body stream -- the
 * interpreter's contract. A request that cannot be built (a URL {@link URI} refuses, a
 * header the client restricts) FAILS that future rather than throwing, so it signals at
 * the await like a transport failure.
 *
 * <p>
 * Everything here speaks the JVM backend's RUNTIME VALUE REPRESENTATION (nil is
 * {@code null}, a cons an {@code Object[2]}, an integer a {@code Long}, a string its
 * quote-wrapped text, an octet vector the packed {@code byte[]{8, e0, ...}}). It is in
 * {@code runtime}, and imports nothing of the project's, so that it TRAVELS with the
 * compiled class ({@code .kb/jvm-export.md}) and the program fetches on a bare
 * {@code java -cp .}.
 */
public final class RontoFetch {

	/**
	 * The marker heading a stream's {@code Object[3]}, and its end-of-stream pill: the
	 * async runtime's {@code SMARKER}, declared here so the body stream built below and
	 * the stream operations the emitter writes compare the same interned string.
	 */
	public static final String STREAM_MARKER = "%stream\n";

	/**
	 * The response plist's keys in cons order. The compiler checks them against the shape
	 * every backend derives from the http-plist WIT record, so a field added there fails
	 * the JVM compile until {@link #plist} fills it.
	 */
	public static final List<String> RESPONSE_KEYWORDS = List.of(":STATUS", ":HEADERS", ":BODY");

	/**
	 * The request field a caller-silent fetch fills with the default user-agent,
	 * re-exported as {@code FetchResponseShape.USER_AGENT_HEADER} for every backend.
	 */
	public static final String USER_AGENT_HEADER = "User-Agent";

	private RontoFetch() {
	}

	/**
	 * Starts one request: it is in flight when this returns.
	 * @param url the request URL
	 * @param method the canonical (upper-case) method, already validated
	 * @param headers the caller's request fields as a flat name, value, ... list
	 * @param body the request body text, or {@code null} for none
	 * @param defaultUserAgent the user-agent a request whose fields name none carries
	 * @return a {@link CompletableFuture} settling to the response plist
	 */
	public static Object start(String url, String method, List<String> headers, Object body, String defaultUserAgent) {
		HttpRequest request;
		try {
			HttpRequest.BodyPublisher publisher = (body instanceof String text)
					? HttpRequest.BodyPublishers.ofString(text) : HttpRequest.BodyPublishers.noBody();
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).method(method, publisher);
			boolean userAgentSet = false;
			for (int i = 0; i + 1 < headers.size(); i += 2) {
				builder.header(headers.get(i), headers.get(i + 1));
				userAgentSet = userAgentSet || USER_AGENT_HEADER.equalsIgnoreCase(headers.get(i));
			}
			if (!userAgentSet) {
				builder.header(USER_AGENT_HEADER, defaultUserAgent);
			}
			request = builder.build();
		}
		catch (RuntimeException ex) {
			return CompletableFuture
				.failedFuture(new IllegalStateException("HTTP request failed: " + ex.getMessage(), ex));
		}
		// The per-request client is not closed: close() would wait for the request, and
		// the interpreter leaves its client to the collector the same way.
		return HttpClient.newHttpClient()
			.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
			.thenApply(RontoFetch::plist);
	}

	/**
	 * The reply's fields as {@code :headers} lists them on every JDK-backed transport:
	 * one entry per VALUE (a repeated field keeps each), names lowercased and in
	 * ascending name order, values of one field in the order they arrived, and HTTP/2's
	 * pseudo-fields ({@code :status}) left out -- they are not fields, and the status is
	 * the plist's own {@code :status}.
	 * @param headers the reply's headers
	 * @return (name, value) entries in {@code :headers} order
	 */
	public static List<Map.Entry<String, String>> responseFields(HttpHeaders headers) {
		List<Map.Entry<String, String>> fields = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : headers.map().entrySet()) {
			String name = entry.getKey().toLowerCase(Locale.ROOT);
			if (name.startsWith(":")) {
				continue;
			}
			for (String value : entry.getValue()) {
				fields.add(Map.entry(name, value));
			}
		}
		// HttpHeaders.map() is ordered case-insensitively; after lowercasing that is the
		// plain order, and the sort is stable, so each field's values stay in wire order.
		fields.sort(Map.Entry.comparingByKey());
		return fields;
	}

	private static Object plist(HttpResponse<byte[]> response) {
		// The header alist, built back-to-front; nil (null) when the reply has none.
		List<Map.Entry<String, String>> fields = responseFields(response.headers());
		Object alist = null;
		for (int i = fields.size() - 1; i >= 0; i--) {
			Map.Entry<String, String> field = fields.get(i);
			alist = new Object[] { new Object[] { quote(field.getKey()), quote(field.getValue()) }, alist };
		}
		Object plist = null;
		for (int i = RESPONSE_KEYWORDS.size() - 1; i >= 0; i--) {
			String keyword = RESPONSE_KEYWORDS.get(i);
			Object value = switch (keyword) {
				case ":STATUS" -> Long.valueOf(response.statusCode());
				case ":HEADERS" -> alist;
				case ":BODY" -> bodyStream(response.body());
				default -> throw new IllegalStateException("no value for response field " + keyword);
			};
			plist = new Object[] { keyword, new Object[] { value, plist } };
		}
		// Three fields always cons, so the accumulator's nil start is gone by here.
		return Objects.requireNonNull(plist);
	}

	/**
	 * The whole reply as ONE octet chunk of a closed stream -- the shape
	 * {@code _make_stream} builds, with the chunk and the end-of-stream pill already
	 * queued and the state closed (1), so {@code stream-read} answers the chunk and then
	 * nil. The chunk is the packed {@code (unsigned-byte 8)} vector, {@code byte[]{8, e0,
	 * ...}}: the width 8 in slot 0, as the compiled {@code _iv*} helpers lay it out, so a
	 * body costs one byte an octet.
	 */
	private static Object bodyStream(byte[] body) {
		byte[] octets = new byte[body.length + 1];
		octets[0] = 8;
		System.arraycopy(body, 0, octets, 1, body.length);
		LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
		queue.offer(octets);
		queue.offer(STREAM_MARKER);
		return new Object[] { STREAM_MARKER, queue, new AtomicInteger(1) };
	}

	private static String quote(String s) {
		return "\"" + s + "\"";
	}

}
