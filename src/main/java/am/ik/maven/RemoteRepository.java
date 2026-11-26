package am.ik.maven;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * A Maven repository by id and base URL: {@code https:} and {@code http:} through the
 * downloader, {@code file:} read from disk. The id is what a {@code settings.xml} mirror
 * or server names. Its {@link RepositoryPolicy policies} say which kinds it is asked for
 * and how often cached answers are refreshed: both enabled and {@code daily} unless
 * given.
 *
 * @param id the repository id
 * @param url the base URL
 * @param releases what it serves of releases
 * @param snapshots what it serves of snapshots
 */
public record RemoteRepository(String id, String url, RepositoryPolicy releases, RepositoryPolicy snapshots) {

	/** Maven Central, under the id and URL {@code clj} uses. */
	public static final RemoteRepository CENTRAL = new RemoteRepository("central", "https://repo1.maven.org/maven2/");

	/** Clojars. */
	public static final RemoteRepository CLOJARS = new RemoteRepository("clojars", "https://repo.clojars.org/");

	/**
	 * A repository with Maven's default policies.
	 * @param id the repository id
	 * @param url the base URL
	 */
	public RemoteRepository(String id, String url) {
		this(id, url, RepositoryPolicy.DEFAULT, RepositoryPolicy.DEFAULT);
	}

	/**
	 * Validates the URL: absolute, with a scheme this resolver reads.
	 * @param id the repository id
	 * @param url the base URL
	 * @param releases what it serves of releases
	 * @param snapshots what it serves of snapshots
	 */
	public RemoteRepository {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(url, "url");
		Objects.requireNonNull(releases, "releases");
		Objects.requireNonNull(snapshots, "snapshots");
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
	 * Returns this repository with another release policy.
	 * @param policy what it serves of releases
	 * @return the repository
	 */
	public RemoteRepository withReleases(RepositoryPolicy policy) {
		return new RemoteRepository(this.id, this.url, policy, this.snapshots);
	}

	/**
	 * Returns this repository with another snapshot policy.
	 * @param policy what it serves of snapshots
	 * @return the repository
	 */
	public RemoteRepository withSnapshots(RepositoryPolicy policy) {
		return new RemoteRepository(this.id, this.url, this.releases, policy);
	}

	/**
	 * Returns the policy for releases or for snapshots.
	 * @param snapshot whether the snapshot policy is wanted
	 * @return the policy
	 */
	RepositoryPolicy policy(boolean snapshot) {
		return snapshot ? this.snapshots : this.releases;
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
