package am.ik.artifact;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * How a download reaches its URL beyond a plain {@code GET}: the proxy, the server's
 * credentials, extra headers and timeouts -- what a Maven {@code settings.xml} configures
 * for a repository.
 *
 * @param proxy the HTTP proxy, or {@code null} for a direct connection
 * @param credentials Basic credentials for the requested URL's host and port, sent once
 * that server answers a {@code 401} with a Basic challenge (and from then on without
 * waiting for one), never to another host a redirect leads to; or {@code null}
 * @param headers headers sent with every request, a redirect's included
 * @param connectTimeout the connect timeout, or {@code null} for the downloader's
 * @param idleTimeout the longest the exchange may go without a byte, or {@code null} for
 * the downloader's
 */
public record HttpAccess(@Nullable Proxy proxy, @Nullable Credentials credentials, Map<String, String> headers,
		@Nullable Duration connectTimeout, @Nullable Duration idleTimeout) {

	/**
	 * A direct connection: no proxy, credentials or headers, the downloader's timeouts.
	 */
	public static final HttpAccess DIRECT = new HttpAccess(null, null, Map.of(), null, null);

	/**
	 * Copies the headers, keeping their order.
	 * @param proxy the proxy, or {@code null}
	 * @param credentials the server's credentials, or {@code null}
	 * @param headers the extra headers
	 * @param connectTimeout the connect timeout, or {@code null}
	 * @param idleTimeout the idle timeout, or {@code null}
	 */
	public HttpAccess {
		headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
	}

	/**
	 * Answers whether this is {@link #DIRECT}: nothing a plain {@code GET} does not do.
	 * @return {@code true} when there is no proxy, credentials, header or timeout
	 */
	public boolean isDirect() {
		return this.proxy == null && this.credentials == null && this.headers.isEmpty() && this.connectTimeout == null
				&& this.idleTimeout == null;
	}

	/**
	 * An HTTP proxy: a {@code https} URL is tunnelled through it with {@code CONNECT}, an
	 * {@code http} one sent to it.
	 *
	 * @param host the proxy host
	 * @param port the proxy port
	 * @param credentials Basic credentials the proxy is sent with every request, or
	 * {@code null}
	 */
	public record Proxy(String host, int port, @Nullable Credentials credentials) {

		/**
		 * Validates the address.
		 * @param host the proxy host
		 * @param port the proxy port
		 * @param credentials the credentials, or {@code null}
		 */
		public Proxy {
			Objects.requireNonNull(host, "host");
		}

		/**
		 * {@code host:port}.
		 * @return the proxy's address
		 */
		@Override
		public String toString() {
			return this.host + ":" + this.port;
		}

	}

	/**
	 * A user name and password for HTTP Basic authentication, encoded as Maven's HTTP
	 * transport encodes them: ISO-8859-1, and a missing password spelled {@code null}.
	 *
	 * @param username the user name
	 * @param password the password, or {@code null}
	 */
	public record Credentials(String username, @Nullable String password) {

		/**
		 * Requires the user name.
		 * @param username the user name
		 * @param password the password, or {@code null}
		 */
		public Credentials {
			Objects.requireNonNull(username, "username");
		}

		/**
		 * Names the user, never the password.
		 * @return the description
		 */
		@Override
		public String toString() {
			return "Credentials[username=" + this.username + ", password=****]";
		}

	}

}
