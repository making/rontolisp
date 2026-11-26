package am.ik.artifact;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The root every downloaded artifact is cached under, and the {@link Downloader} that
 * fills it. Each consumer owns one area beneath the root ({@code <root>/quicklisp/},
 * {@code <root>/ultralisp/}, ...) and installs into it through {@link AtomicInstall}.
 *
 * <p>
 * The root is {@code RONTOLISP_DIST_HOME} or {@code ~/.rontolisp}, read ONLY by
 * {@link #createDefault()}: a cache built with an explicit root (every test) must never
 * pick up the developer's cache.
 */
public final class ArtifactCache {

	/** The environment variable overriding the default root. */
	public static final String HOME_ENV = "RONTOLISP_DIST_HOME";

	private final Path root;

	private final Downloader downloader;

	/**
	 * Creates a cache at {@code root} filled through {@code downloader}.
	 * @param root the cache root
	 * @param downloader the byte fetcher
	 */
	public ArtifactCache(Path root, Downloader downloader) {
		this.root = root;
		this.downloader = downloader;
	}

	/**
	 * Creates the default cache: {@link #defaultRoot()} over an {@link HttpDownloader}.
	 * @return the default cache
	 */
	public static ArtifactCache createDefault() {
		return new ArtifactCache(defaultRoot(), new HttpDownloader());
	}

	/**
	 * Returns the default root: {@code RONTOLISP_DIST_HOME} if set, otherwise
	 * {@code ~/.rontolisp}.
	 * @return the default root
	 */
	public static Path defaultRoot() {
		String override = System.getenv(HOME_ENV);
		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}
		return Path.of(System.getProperty("user.home", "."), ".rontolisp");
	}

	/**
	 * Returns the cache root.
	 * @return the root
	 */
	public Path root() {
		return this.root;
	}

	/**
	 * Returns a consumer's area under the root.
	 * @param name the area name, a single path segment
	 * @return {@code <root>/<name>}
	 */
	public Path area(String name) {
		if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
			throw new IllegalArgumentException("not a cache area name: '" + name + "'");
		}
		return this.root.resolve(name);
	}

	/**
	 * Downloads {@code url} and verifies the bytes against every given checksum before
	 * answering them.
	 * @param url the URL to fetch
	 * @param expected the digests the bytes must match
	 * @return the verified bytes
	 * @throws IOException if the fetch fails or a digest differs
	 */
	public byte[] download(String url, Checksum... expected) throws IOException {
		return download(url, -1, expected);
	}

	/**
	 * Downloads {@code url} and verifies the bytes' length and every given checksum
	 * before answering them. The length is checked first: a download cut short is the
	 * common failure, and saying so beats a digest mismatch.
	 * @param url the URL to fetch
	 * @param expectedSize the length the bytes must have, or a negative value for any
	 * @param expected the digests the bytes must match
	 * @return the verified bytes
	 * @throws IOException if the fetch fails, or the length or a digest differs
	 */
	public byte[] download(String url, long expectedSize, Checksum... expected) throws IOException {
		byte[] bytes = this.downloader.get(url);
		if (expectedSize >= 0 && bytes.length != expectedSize) {
			throw new IOException(
					"size mismatch for " + url + ": expected " + expectedSize + " bytes, got " + bytes.length);
		}
		for (Checksum checksum : expected) {
			checksum.verify(bytes, url);
		}
		return bytes;
	}

}
