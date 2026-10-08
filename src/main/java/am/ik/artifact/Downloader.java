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
	 * @throws IOException if the fetch fails
	 */
	byte[] get(String url) throws IOException;

}
