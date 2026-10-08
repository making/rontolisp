package am.ik.maven;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * A Maven repository by id and base URL: {@code https:} and {@code http:} through the
 * downloader, {@code file:} read from disk. The id is what a {@code settings.xml} mirror
 * or server names.
 *
 * @param id the repository id
 * @param url the base URL
 */
public record RemoteRepository(String id, String url) {

	/** Maven Central, under the id and URL {@code clj} uses. */
	public static final RemoteRepository CENTRAL = new RemoteRepository("central", "https://repo1.maven.org/maven2/");

	/** Clojars. */
	public static final RemoteRepository CLOJARS = new RemoteRepository("clojars", "https://repo.clojars.org/");

	/**
	 * Validates the URL: absolute, with a scheme this resolver reads.
	 * @param id the repository id
	 * @param url the base URL
	 */
	public RemoteRepository {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(url, "url");
		URI uri;
		try {
			uri = URI.create(url);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("repository '" + id + "' has a malformed URL: " + url, ex);
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !scheme.equals("http") && !scheme.equals("file")) {
			throw new IllegalArgumentException(
					"repository '" + id + "' has a URL this resolver cannot read (https, http or file): " + url);
		}
	}

	/**
	 * Returns the URL scheme, lowercased.
	 * @return {@code https}, {@code http} or {@code file}
	 */
	String protocol() {
		return URI.create(this.url).getScheme().toLowerCase(Locale.ROOT);
	}

	/**
	 * Returns the host, or the empty string for a {@code file:} repository.
	 * @return the host
	 */
	String host() {
		String host = URI.create(this.url).getHost();
		return host == null ? "" : host;
	}

	/**
	 * Returns the URL of a repository-relative path: the base URL, a {@code /}, and the
	 * path percent-encoded the way Maven Resolver's layout encodes it.
	 * @param path the relative path
	 * @return the absolute URL
	 */
	URI resolve(String path) {
		String base = this.url.endsWith("/") ? this.url : this.url + "/";
		try {
			return URI.create(base).resolve(new URI(null, null, path, null));
		}
		catch (URISyntaxException ex) {
			throw new IllegalArgumentException("not a repository path: " + path, ex);
		}
	}

	/**
	 * {@code id (url)}.
	 * @return the repository
	 */
	@Override
	public String toString() {
		return this.id + " (" + this.url + ")";
	}

}
