package am.ik.rontolisp.eval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispStream;
import am.ik.rontolisp.compiler.FetchResponseShape;
import org.jspecify.annotations.Nullable;

/**
 * Performs outgoing HTTP requests for the interpreter backend, using the JDK
 * {@link HttpClient}. The response is delivered in two stages, matching the
 * {@code rontolisp:fetch} contract: the returned future settles with the status and
 * headers as soon as they arrive, and the body streams afterwards into the
 * {@link LispStream} chunk by chunk.
 */
final class HttpSupport {

	private HttpSupport() {
	}

	/** A single HTTP header (name and value). */
	record Header(String name, String value) {
	}

	/**
	 * A fully buffered HTTP outcome: status code, whole response body and headers. The
	 * browser playground's fetch broker delivers this shape (see
	 * {@code src/web/java/.../eval/Target_HttpSupport.java} and
	 * {@code BrowserHttpResponses}); it is converted into a {@link Start} with an
	 * already-settled one-chunk body stream (the body's UTF-8 octets).
	 */
	record HttpResult(int status, String body, List<Header> headers) {
	}

	/**
	 * The response head: status code and headers, plus the body as a stream of OCTET
	 * chunks ({@code (unsigned-byte 8)} vectors) that fills in asynchronously.
	 */
	record Start(int status, List<Header> headers, LispStream body) {
	}

	/**
	 * Starts an HTTP request asynchronously (JavaScript {@code fetch}-style) via
	 * {@link HttpClient#sendAsync}: the request is in flight when this returns, the
	 * future settles when the response HEAD (status + headers) has arrived, and the body
	 * streams into the result's {@link LispStream} afterwards (as octet chunks, exactly
	 * the bytes received -- {@code rontolisp:read-all} decodes; a transport failure
	 * mid-body fails the stream). Request-building failures (e.g. a malformed URL) fail
	 * the returned future rather than throwing, so every failure surfaces at await time.
	 * This is the seam the browser playground substitutes (see
	 * {@code src/web/java/.../eval/Target_HttpSupport.java}), where the request is
	 * buffered and an already-completed future with a one-chunk body stream is returned
	 * -- and where the default user-agent below is deliberately absent, the browser
	 * owning that field and forbidding a page from setting it. The per-request client is
	 * intentionally not closed: {@code close()} would block until the in-flight request
	 * completes, and the compiled JVM backend leaves its client to be garbage-collected
	 * the same way.
	 * @param method the HTTP method (e.g. {@code "GET"}, {@code "POST"})
	 * @param url the request URL
	 * @param requestHeaders the request headers to set; when they name no user-agent,
	 * {@link FetchResponseShape#defaultUserAgent()} is added
	 * @param body the request body, or {@code null} for no body
	 * @return a future settling to the response status, headers and body stream
	 */
	static CompletableFuture<Start> requestAsync(String method, String url, List<Header> requestHeaders,
			@Nullable String body) {
		HttpClient client;
		HttpRequest request;
		try {
			HttpRequest.BodyPublisher publisher = (body == null) ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(body);
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).method(method, publisher);
			boolean userAgentSet = false;
			for (Header header : requestHeaders) {
				builder.header(header.name(), header.value());
				userAgentSet = userAgentSet || FetchResponseShape.isUserAgentHeader(header.name());
			}
			if (!userAgentSet) {
				// Set it EXPLICITLY rather than letting the JDK write its own
				// Java-http-client/<jdk>: fetch sends the same request on every backend
				// (FetchResponseShape.USER_AGENT_HEADER).
				builder.header(FetchResponseShape.USER_AGENT_HEADER, FetchResponseShape.defaultUserAgent());
			}
			request = builder.build();
			client = HttpClient.newHttpClient();
		}
		catch (RuntimeException ex) {
			return CompletableFuture
				.failedFuture(new IllegalStateException("HTTP request failed: " + ex.getMessage(), ex));
		}
		return client.sendAsync(request, HttpResponse.BodyHandlers.ofPublisher()).thenApply(response -> {
			List<Header> responseHeaders = new ArrayList<>();
			for (Map.Entry<String, List<String>> entry : response.headers().map().entrySet()) {
				for (String value : entry.getValue()) {
					responseHeaders.add(new Header(entry.getKey(), value));
				}
			}
			LispStream stream = LispStream.open();
			response.body().subscribe(new BodyPump(stream));
			return new Start(response.statusCode(), responseHeaders, stream);
		});
	}

	/**
	 * Pumps the response body publisher into a {@link LispStream}: each batch of byte
	 * buffers becomes ONE OCTET CHUNK -- an {@code (unsigned-byte 8)} vector holding the
	 * bytes exactly as they arrived, no decode -- and one batch is requested at a time.
	 * The body stream is a BYTE stream on every backend: a handler that relays it as its
	 * own response body forwards the upstream's octets untouched, and
	 * {@code rontolisp:read-all} decodes the whole body once, at the drain, which is also
	 * what keeps a multi-byte sequence a batch boundary split from ever being decoded in
	 * two halves.
	 */
	private static final class BodyPump implements Flow.Subscriber<List<ByteBuffer>> {

		private final LispStream stream;

		private Flow.@Nullable Subscription subscription;

		BodyPump(LispStream stream) {
			this.stream = stream;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(1);
		}

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			try {
				LispIntVector chunk = octets(buffers);
				if (chunk.length() > 0) {
					this.stream.write(chunk);
				}
				Flow.Subscription active = this.subscription;
				if (active != null) {
					active.request(1);
				}
			}
			catch (RuntimeException ex) {
				this.stream.fail(new IllegalStateException("HTTP body failed: " + ex.getMessage(), ex));
				Flow.Subscription active = this.subscription;
				if (active != null) {
					active.cancel();
				}
			}
		}

		@Override
		public void onError(Throwable throwable) {
			this.stream.fail(new IllegalStateException("HTTP body failed: " + throwable.getMessage(), throwable));
		}

		@Override
		public void onComplete() {
			this.stream.close();
		}

		private static LispIntVector octets(List<ByteBuffer> buffers) {
			int total = 0;
			for (ByteBuffer buffer : buffers) {
				total += buffer.remaining();
			}
			long[] data = new long[total];
			int k = 0;
			for (ByteBuffer buffer : buffers) {
				while (buffer.hasRemaining()) {
					data[k++] = buffer.get() & 0xFF;
				}
			}
			return new LispIntVector(8, data);
		}

	}

}
