package am.ik.artifact;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;

import org.jspecify.annotations.Nullable;

/**
 * The network {@link Downloader}: an HTTP(S) GET answering the body of a {@code 200} and
 * failing on any other status with an {@link HttpStatusException}, reaching the URL as an
 * {@link HttpAccess} says -- the way Maven's HTTP transport does.
 *
 * <ul>
 * <li>Redirects are followed, up to five, never from {@code https} to {@code http}.
 * Headers go with every request; credentials only to the requested URL's host and port,
 * once that server answers {@code 401} with a Basic challenge, and from then on without
 * waiting for one (Maven's authentication cache). A challenge offering no Basic scheme
 * fails by name.</li>
 * <li>A proxy is sent its credentials with every request. An {@code https} URL through a
 * proxy with credentials is tunnelled here ({@link ProxyTunnel}): the JDK client refuses
 * Basic credentials on a {@code CONNECT} unless a process-wide property says otherwise,
 * and Maven sends them.</li>
 * </ul>
 *
 * <p>
 * Two timeouts bound a stalled server, so a download can never hang the compile: the
 * connect timeout, and an IDLE timeout -- the longest the exchange may go without
 * receiving a byte, whether it is still waiting for the response headers or in the middle
 * of the body. An idle bound rather than a total one, because a large artifact over a
 * slow link is legitimate and only a silent one is not.
 *
 * <p>
 * The browser build substitutes {@link #get(String, HttpAccess)} (no network there),
 * which also keeps the {@link HttpClient} and the tunnel's sockets out of that image:
 * nothing else references them.
 */
public final class HttpDownloader implements Downloader {

	/** The default connect timeout. */
	public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

	/** The default idle timeout: the longest an exchange may go without a byte. */
	public static final Duration IDLE_TIMEOUT = Duration.ofSeconds(60);

	/** Maven Resolver's limit, and the JDK client's. */
	static final int MAX_REDIRECTS = 5;

	/** The headers the JDK client refuses from a caller. */
	private static final Set<String> RESTRICTED_HEADERS = Set.of("connection", "content-length", "expect", "host",
			"upgrade");

	private static final Pattern BASIC_CHALLENGE = Pattern.compile("(?i)(^|,)\\s*basic(\\s|,|$)");

	private final Duration connectTimeout;

	private final Duration idleTimeout;

	private final @Nullable SSLContext sslContext;

	/** One client per proxy and connect timeout, made on first use, then reused. */
	private final Map<ClientKey, HttpClient> clients = new HashMap<>();

	/** The credentials a server took: sent to it from then on without a challenge. */
	private final Set<String> acceptedCredentials = ConcurrentHashMap.newKeySet();

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
		this(connectTimeout, idleTimeout, null);
	}

	/**
	 * Creates a downloader trusting what {@code sslContext} trusts (a test's own
	 * certificate).
	 */
	HttpDownloader(Duration connectTimeout, Duration idleTimeout, @Nullable SSLContext sslContext) {
		this.connectTimeout = connectTimeout;
		this.idleTimeout = idleTimeout;
		this.sslContext = sslContext;
	}

	@Override
	public byte[] get(String url) throws IOException {
		return get(url, HttpAccess.DIRECT);
	}

	@Override
	public byte[] get(String url, HttpAccess access) throws IOException {
		URI uri = parse(url);
		for (String name : access.headers().keySet()) {
			if (RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
				throw new IOException("the header '" + name + "' cannot be set (fetching " + url + ")");
			}
		}
		HttpAccess.Credentials credentials = access.credentials();
		String origin = origin(uri);
		String accepted = credentials == null ? ""
				: origin + '\n' + credentials.username() + '\n' + credentials.password();
		boolean authorize = credentials != null && this.acceptedCredentials.contains(accepted);
		for (int redirects = 0;;) {
			boolean sendCredentials = authorize && origin(uri).equals(origin);
			HttpMessages.Response response = exchange(uri, access, sendCredentials ? credentials : null);
			int status = response.status();
			String location = response.headers().firstValue("location").orElse(null);
			if (isRedirect(status) && location != null) {
				URI next;
				try {
					next = uri.resolve(location);
				}
				catch (IllegalArgumentException ex) {
					throw new IOException("a malformed redirect from " + uri + ": " + location, ex);
				}
				if (!HttpMessages.isHttps(uri) || HttpMessages.isHttps(next)) {
					if (++redirects > MAX_REDIRECTS) {
						throw new IOException("too many redirects (" + MAX_REDIRECTS + ") fetching " + url);
					}
					uri = next;
					continue;
				}
			}
			if (status == 401 && credentials != null && !sendCredentials && origin(uri).equals(origin)) {
				List<String> challenges = response.headers().allValues("www-authenticate");
				if (challenges.stream().noneMatch(challenge -> BASIC_CHALLENGE.matcher(challenge).find())) {
					throw new HttpStatusException(status, url, challenges.isEmpty() ? "no authentication challenge"
							: "asks for " + String.join(", ", challenges) + "; only Basic credentials are sent");
				}
				authorize = true;
				continue;
			}
			if (status != 200) {
				throw new HttpStatusException(status, url);
			}
			if (sendCredentials) {
				this.acceptedCredentials.add(accepted);
			}
			return response.body();
		}
	}

	private static URI parse(String url) throws IOException {
		try {
			return URI.create(url);
		}
		catch (IllegalArgumentException ex) {
			throw new IOException("not a URL: " + url, ex);
		}
	}

	private static boolean isRedirect(int status) {
		return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
	}

	/**
	 * The scope of a server's credentials: host and port, as Maven's transport scopes
	 * them.
	 */
	private static String origin(URI uri) {
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		return host + ":" + HttpMessages.port(uri);
	}

	private HttpMessages.Response exchange(URI uri, HttpAccess access, HttpAccess.@Nullable Credentials credentials)
			throws IOException {
		Duration connect = access.connectTimeout() != null ? access.connectTimeout() : this.connectTimeout;
		Duration idle = access.idleTimeout() != null ? access.idleTimeout() : this.idleTimeout;
		HttpAccess.Proxy proxy = access.proxy();
		Map<String, String> headers = new LinkedHashMap<>(access.headers());
		if (credentials != null) {
			headers.put("Authorization", HttpMessages.basic(credentials));
		}
		if (proxy != null && proxy.credentials() != null && HttpMessages.isHttps(uri)) {
			return ProxyTunnel.get(uri, proxy, headers, connect, idle, sslContext());
		}
		if (proxy != null && proxy.credentials() != null) {
			headers.put("Proxy-Authorization", HttpMessages.basic(proxy.credentials()));
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET();
		try {
			for (Map.Entry<String, String> header : headers.entrySet()) {
				builder.setHeader(header.getKey(), header.getValue());
			}
		}
		catch (IllegalArgumentException ex) {
			throw new IOException("cannot send the headers " + headers.keySet() + " to " + uri + ": " + ex.getMessage(),
					ex);
		}
		AtomicLong received = new AtomicLong();
		CompletableFuture<HttpResponse<byte[]>> pending = client(proxy, connect).sendAsync(builder.build(), info -> {
			// The status line and headers count as progress too.
			received.incrementAndGet();
			return new CountingSubscriber(HttpResponse.BodySubscribers.ofByteArray(), received);
		});
		HttpResponse<byte[]> response = await(pending, received, uri.toString(), idle);
		return new HttpMessages.Response(response.statusCode(), response.headers(), response.body());
	}

	private SSLContext sslContext() throws IOException {
		if (this.sslContext != null) {
			return this.sslContext;
		}
		try {
			return SSLContext.getDefault();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IOException("no TLS: " + ex.getMessage(), ex);
		}
	}

	/** What a client is built for. */
	private record ClientKey(@Nullable String proxyHost, int proxyPort, Duration connectTimeout) {
	}

	private synchronized HttpClient client(HttpAccess.@Nullable Proxy proxy, Duration connect) {
		ClientKey key = new ClientKey(proxy == null ? null : proxy.host(), proxy == null ? 0 : proxy.port(), connect);
		HttpClient current = this.clients.get(key);
		if (current == null) {
			HttpClient.Builder builder = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(connect);
			if (proxy != null) {
				builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(proxy.host(), proxy.port())));
			}
			if (this.sslContext != null) {
				builder.sslContext(this.sslContext);
			}
			current = builder.build();
			this.clients.put(key, current);
		}
		return current;
	}

	/**
	 * Waits for the exchange, in idle-timeout slices: a slice in which no byte arrived
	 * cancels it.
	 */
	private static HttpResponse<byte[]> await(CompletableFuture<HttpResponse<byte[]>> pending, AtomicLong received,
			String url, Duration idleTimeout) throws IOException {
		long seen = received.get();
		while (true) {
			try {
				return pending.get(idleTimeout.toNanos(), TimeUnit.NANOSECONDS);
			}
			catch (TimeoutException ex) {
				long now = received.get();
				if (now == seen) {
					pending.cancel(true);
					throw new IOException(
							"no data from " + url + " for " + idleTimeout.toMillis() + " ms (server stalled)", ex);
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
