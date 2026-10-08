package am.ik.artifact;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What {@link HttpDownloader} and {@link ProxyTunnel} share: a response, the
 * {@code Basic} credentials header, a URL's port.
 */
final class HttpMessages {

	private HttpMessages() {
	}

	/**
	 * One request's whole answer, whatever the status.
	 *
	 * @param status the status
	 * @param headers the header fields
	 * @param body the body
	 */
	record Response(int status, HttpHeaders headers, byte[] body) {
	}

	/** The header fields of {@code name, value} pairs, in arrival order. */
	static HttpHeaders headers(List<String[]> fields) {
		Map<String, List<String>> map = new HashMap<>();
		for (String[] field : fields) {
			map.computeIfAbsent(field[0].toLowerCase(Locale.ROOT), name -> new ArrayList<>()).add(field[1]);
		}
		return HttpHeaders.of(map, (name, value) -> true);
	}

	/**
	 * The {@code Basic} credentials header value, encoded as Maven's HTTP transport
	 * encodes it: ISO-8859-1, a missing password spelled {@code null}.
	 */
	static String basic(HttpAccess.Credentials credentials) {
		String pair = credentials.username() + ":" + credentials.password();
		return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.ISO_8859_1));
	}

	static boolean isHttps(URI uri) {
		return "https".equalsIgnoreCase(uri.getScheme());
	}

	/** The URL's port, else its scheme's. */
	static int port(URI uri) {
		return uri.getPort() != -1 ? uri.getPort() : isHttps(uri) ? 443 : 80;
	}

}
