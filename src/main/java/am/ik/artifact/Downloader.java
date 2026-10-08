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

}
