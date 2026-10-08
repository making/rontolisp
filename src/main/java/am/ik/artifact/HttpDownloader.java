package am.ik.artifact;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;

/**
 * The network {@link Downloader}: an HTTP(S) GET through the JDK {@link HttpClient},
 * following redirects, answering the body of a {@code 200} and failing on any other
 * status with an {@link HttpStatusException}.
 *
 * <p>
 * Two timeouts bound a stalled server, so a download can never hang the compile: the
 * connect timeout, and an IDLE timeout -- the longest the exchange may go without
 * receiving a byte, whether it is still waiting for the response headers or in the middle
 * of the body. An idle bound rather than a total one, because a large artifact over a
 * slow link is legitimate and only a silent one is not.
 *
 * <p>
 * The browser build substitutes {@link #get} (no network there), which also keeps the
 * {@link HttpClient} out of that image: nothing else references it.
 */
public final class HttpDownloader implements Downloader {

	/** The default connect timeout. */
	public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

	/** The default idle timeout: the longest an exchange may go without a byte. */
	public static final Duration IDLE_TIMEOUT = Duration.ofSeconds(60);

	private final Duration connectTimeout;

	private final Duration idleTimeout;

	/** Created on the first {@link #get}, then reused (one connection pool). */
	@Nullable private HttpClient client;

	/**
	 * Creates a downloader with the default timeouts.
	 */
	public HttpDownloader() {
		this(CONNECT_TIMEOUT, IDLE_TIMEOUT);
	}

	/**
	 * Creates a downloader with explicit timeouts.
	 * @param connectTimeout the connect timeout
	 * @param idleTimeout the longest an exchange may go without receiving a byte
	 */
	public HttpDownloader(Duration connectTimeout, Duration idleTimeout) {
		this.connectTimeout = connectTimeout;
		this.idleTimeout = idleTimeout;
	}

	@Override
	public byte[] get(String url) throws IOException {
		URI uri;
		try {
			uri = URI.create(url);
		}
		catch (IllegalArgumentException ex) {
			throw new IOException("not a URL: " + url, ex);
		}
		HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
		AtomicLong received = new AtomicLong();
		CompletableFuture<HttpResponse<byte[]>> pending = client().sendAsync(request, info -> {
			// The status line and headers count as progress too.
			received.incrementAndGet();
			return new CountingSubscriber(HttpResponse.BodySubscribers.ofByteArray(), received);
		});
		HttpResponse<byte[]> response = await(pending, received, url);
		if (response.statusCode() != 200) {
			throw new HttpStatusException(response.statusCode(), url);
		}
		return response.body();
	}

	private synchronized HttpClient client() {
		HttpClient current = this.client;
		if (current == null) {
			current = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(this.connectTimeout)
				.build();
			this.client = current;
		}
		return current;
	}

	/**
	 * Waits for the exchange, in idle-timeout slices: a slice in which no byte arrived
	 * cancels it.
	 */
	private HttpResponse<byte[]> await(CompletableFuture<HttpResponse<byte[]>> pending, AtomicLong received, String url)
			throws IOException {
		long seen = received.get();
		while (true) {
			try {
				return pending.get(this.idleTimeout.toNanos(), TimeUnit.NANOSECONDS);
			}
			catch (TimeoutException ex) {
				long now = received.get();
				if (now == seen) {
					pending.cancel(true);
					throw new IOException(
							"no data from " + url + " for " + this.idleTimeout.toMillis() + " ms (server stalled)", ex);
				}
				seen = now;
			}
			catch (InterruptedException ex) {
				pending.cancel(true);
				Thread.currentThread().interrupt();
				throw new IOException("download interrupted for " + url, ex);
			}
			catch (CancellationException ex) {
				throw new IOException("download cancelled for " + url, ex);
			}
			catch (ExecutionException ex) {
				Throwable cause = ex.getCause();
				String message = cause != null && cause.getMessage() != null ? cause.getMessage()
						: String.valueOf(cause);
				throw new IOException("download failed for " + url + ": " + message, cause);
			}
		}
	}

	/**
	 * A body subscriber that counts every buffer it is handed before passing it on, so
	 * {@link #await} can tell a slow body from a stalled one.
	 */
	private record CountingSubscriber(HttpResponse.BodySubscriber<byte[]> delegate,
			AtomicLong received) implements HttpResponse.BodySubscriber<byte[]> {

		@Override
		public CompletionStage<byte[]> getBody() {
			return this.delegate.getBody();
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.delegate.onSubscribe(subscription);
		}

		@Override
		public void onNext(List<ByteBuffer> item) {
			long bytes = 0;
			for (ByteBuffer buffer : item) {
				bytes += buffer.remaining();
			}
			this.received.addAndGet(bytes + 1);
			this.delegate.onNext(item);
		}

		@Override
		public void onError(Throwable throwable) {
			this.delegate.onError(throwable);
		}

		@Override
		public void onComplete() {
			this.delegate.onComplete();
		}

	}

}
