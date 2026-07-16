package am.ik.rontolisp.eval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispStream;
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
	 * already-settled one-chunk body stream.
	 */
	record HttpResult(int status, String body, List<Header> headers) {
	}

	/**
	 * The response head: status code and headers, plus the body as a stream of string
	 * chunks that fills in asynchronously.
	 */
	record Start(int status, List<Header> headers, LispStream body) {
	}

	/**
	 * Starts an HTTP request asynchronously (JavaScript {@code fetch}-style) via
	 * {@link HttpClient#sendAsync}: the request is in flight when this returns, the
	 * future settles when the response HEAD (status + headers) has arrived, and the body
	 * streams into the result's {@link LispStream} afterwards (UTF-8 decoded, multi-byte
	 * sequences preserved across chunk boundaries; a transport failure mid-body fails the
	 * stream). Request-building failures (e.g. a malformed URL) fail the returned future
	 * rather than throwing, so every failure surfaces at await time. This is the seam the
	 * browser playground substitutes (see
	 * {@code src/web/java/.../eval/Target_HttpSupport.java}), where the request is
	 * buffered and an already-completed future with a one-chunk body stream is returned.
	 * The per-request client is intentionally not closed: {@code close()} would block
	 * until the in-flight request completes, and the compiled JVM backend leaves its
	 * client to be garbage-collected the same way.
	 * @param method the HTTP method (e.g. {@code "GET"}, {@code "POST"})
	 * @param url the request URL
	 * @param requestHeaders the request headers to set
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
			for (Header header : requestHeaders) {
				builder.header(header.name(), header.value());
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
	 * buffers is UTF-8 decoded into one string chunk (carrying a trailing partial
	 * multi-byte sequence over to the next batch) and one batch is requested at a time.
	 */
	private static final class BodyPump implements Flow.Subscriber<List<ByteBuffer>> {

		private final LispStream stream;

		private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPLACE)
			.onUnmappableCharacter(CodingErrorAction.REPLACE);

		private byte[] carry = new byte[0];

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
				String chunk = decode(buffers, false);
				if (!chunk.isEmpty()) {
					this.stream.write(new LispString(chunk));
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
			String tail = decode(List.of(), true);
			if (!tail.isEmpty()) {
				this.stream.write(new LispString(tail));
			}
			this.stream.close();
		}

		private String decode(List<ByteBuffer> buffers, boolean endOfInput) {
			int total = this.carry.length;
			for (ByteBuffer buffer : buffers) {
				total += buffer.remaining();
			}
			ByteBuffer input = ByteBuffer.allocate(total);
			input.put(this.carry);
			for (ByteBuffer buffer : buffers) {
				input.put(buffer);
			}
			input.flip();
			CharBuffer output = CharBuffer.allocate(total + 2);
			this.decoder.decode(input, output, endOfInput);
			if (endOfInput) {
				this.decoder.flush(output);
				this.carry = new byte[0];
			}
			else {
				this.carry = new byte[input.remaining()];
				input.get(this.carry);
			}
			output.flip();
			return output.toString();
		}

	}

}
