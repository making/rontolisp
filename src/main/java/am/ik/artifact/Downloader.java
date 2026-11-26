package am.ik.artifact;

import java.io.IOException;

/**
 * Fetches the bytes at a URL. {@link HttpDownloader} is the network implementation; tests
 * inject an in-memory one so nothing reaches the network.
 */
@FunctionalInterface
public interface Downloader {

	/**
	 * Returns the bytes at {@code url}.
	 * @param url the URL to fetch
	 * @return the response body bytes
	 * @throws HttpStatusException if the server answers a status other than {@code 200}
	 * ({@code 404}: nothing there)
	 * @throws IOException if the fetch fails otherwise
	 */
	byte[] get(String url) throws IOException;

	/**
	 * Returns the bytes at {@code url}, reached as {@code access} says. A downloader that
	 * knows nothing of proxies, credentials or headers -- this default -- refuses any
	 * access but {@link HttpAccess#DIRECT} rather than ignoring it.
	 * @param url the URL to fetch
	 * @param access the proxy, credentials, headers and timeouts
	 * @return the response body bytes
	 * @throws HttpStatusException if the server answers a status other than {@code 200}
	 * @throws IOException if the fetch fails otherwise
	 */
	default byte[] get(String url, HttpAccess access) throws IOException {
		if (!access.isDirect()) {
			throw new IOException(getClass().getName() + " cannot fetch " + url + " through a proxy, with credentials, "
					+ "headers or timeouts (" + access + ")");
		}
		return get(url);
	}

}
