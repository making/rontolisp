package am.ik.rontolisp.runtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
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
 * quote-wrapped text, an octet vector the bare {@code byte[]} of its octets). It is in
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
	 * @param body the request body -- its text, sent as UTF-8, or an octet vector (a
	 * {@code byte[]}), sent as it is -- or {@code null} for none
	 * @param defaultUserAgent the user-agent a request whose fields name none carries
	 * @return a {@link CompletableFuture} settling to the response plist
	 */
	public static Object start(String url, String method, List<String> headers, Object body, String defaultUserAgent) {
		HttpRequest request;
		try {
			HttpRequest.BodyPublisher publisher = switch (body) {
				case String text -> HttpRequest.BodyPublishers.ofString(text);
				case byte[] octets -> HttpRequest.BodyPublishers.ofByteArray(octets);
				case null, default -> HttpRequest.BodyPublishers.noBody();
			};
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
		// the interpreter leaves its client to the collector the same way. The future
		// settles at the head; the body fills in as the reply arrives (bodyStream).
		return HttpClient.newHttpClient()
			.sendAsync(request, HttpResponse.BodyHandlers.ofPublisher())
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

	private static Object plist(HttpResponse<Flow.Publisher<List<ByteBuffer>>> response) {
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
	 * The reply's body as a stream of octet chunks filling in as the reply arrives -- the
	 * shape {@code _make_stream} builds, {@code {SMARKER, queue, state}}, whose
	 * {@code stream-read} takes the next chunk at its await, blocking only while none has
	 * arrived ({@link BodyPump}).
	 */
	private static Object bodyStream(Flow.Publisher<List<ByteBuffer>> body) {
		LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
		AtomicInteger state = new AtomicInteger(0);
		body.subscribe(new BodyPump(queue, state));
		return new Object[] { STREAM_MARKER, queue, state };
	}

	/**
	 * Pumps the client's body publisher into a body stream, one batch at a time: each
	 * batch is queued as ONE chunk, the {@code (unsigned-byte 8)} vector -- the bare
	 * {@code byte[]} the compiled {@code _iv*} helpers take, so a body costs one byte an
	 * octet -- holding the octets as they arrived; the end is the end-of-stream pill, and
	 * the state turns closed (1), so a write is refused as the interpreter's closed
	 * stream refuses one. A transfer that fails mid-body queues a failed future ahead of
	 * the pill: the read that takes it awaits it and signals, as the interpreter's failed
	 * stream does, so the body never reads as a shorter one. A stream the program closes
	 * (its state turned closed by {@code stream-close}) cancels the subscription at the
	 * next batch, which releases the connection; a batch queued while it closed is taken
	 * back, so no read past the end answers it.
	 */
	private static final class BodyPump implements Flow.Subscriber<List<ByteBuffer>> {

		private final LinkedBlockingQueue<Object> queue;

		private final AtomicInteger state;

		@SuppressWarnings("NullAway.Init")
		private Flow.Subscription subscription;

		BodyPump(LinkedBlockingQueue<Object> queue, AtomicInteger state) {
			this.queue = queue;
			this.state = state;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(1);
		}

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			if (this.state.get() != 0) {
				this.subscription.cancel();
				return;
			}
			int total = 0;
			for (ByteBuffer buffer : buffers) {
				total += buffer.remaining();
			}
			if (total > 0) {
				byte[] chunk = new byte[total];
				int k = 0;
				for (ByteBuffer buffer : buffers) {
					int n = buffer.remaining();
					buffer.get(chunk, k, n);
					k += n;
				}
				this.queue.offer(chunk);
				if (this.state.get() != 0) {
					this.queue.remove(chunk);
					this.subscription.cancel();
					return;
				}
			}
			this.subscription.request(1);
		}

		@Override
		public void onError(Throwable throwable) {
			this.queue.offer(CompletableFuture
				.failedFuture(new IllegalStateException("HTTP body failed: " + throwable.getMessage(), throwable)));
			end();
		}

		@Override
		public void onComplete() {
			end();
		}

		private void end() {
			this.queue.offer(STREAM_MARKER);
			this.state.set(1);
		}

	}

	private static String quote(String s) {
		return "\"" + s + "\"";
	}

}
