package am.ik.rontolisp.eval;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.jspecify.annotations.Nullable;

/**
 * A limited, API-compatible subset of Quicklisp behind {@code ql:quickload}. Not a port:
 * it downloads a system (and its transitive dependencies) from one or more <em>real</em>
 * Quicklisp-format distributions into a local cache, extracts the release tarballs, and
 * then hands the extracted {@code .asd} directories to the {@link AsdfSystems} loader --
 * so {@code ql:quickload} is exactly {@code asdf:load-system} with an auto-download step
 * in front of it.
 *
 * <p>
 * A <em>dist</em> is the Quicklisp dist metadata format, which more than one distribution
 * speaks: {@code quicklisp.txt} / {@code ultralisp.txt} (the distinfo) points at
 * {@code systems.txt} (each line {@code project system-file system-name dep...}, giving
 * dependency resolution) and {@code releases.txt} (each line
 * {@code project url size md5 sha1 prefix file...}, giving the tarball URL and its
 * top-level directory). Quicklisp is installed by default;
 * <a href= "https://ultralisp.org/">Ultralisp</a> and any other Quicklisp-format
 * distribution are opt-in, through {@code ql-dist:install-dist} (the CLI spells it
 * {@code --dist}). The dists are searched IN ORDER and the first one that lists a system
 * name provides it, per system -- so a dependency the earlier dist does not have is still
 * resolved by a later one. A dist's indexes are downloaded only when it is actually
 * consulted, so installing a second dist costs nothing until the first one comes up
 * short.
 *
 * <p>
 * Each dist caches its indexes and its extracted sources under its own directory
 * ({@code ~/.rontolisp/<dist>/}; the base is overridden with {@code RONTOLISP_DIST_HOME},
 * and the quicklisp dist additionally with the older {@code RONTOLISP_QUICKLISP_HOME}),
 * so a second {@code quickload} of the same system does no network I/O.
 * {@code ql:update-dist} drops a dist's cached indexes so the next resolution sees the
 * current release of every project -- which is what a fast-moving dist like Ultralisp
 * (rebuilt every few minutes) needs.
 *
 * <p>
 * The {@link Downloader} is injectable so tests can serve an in-memory dist without
 * touching the network; {@link #createDefault()} uses the JDK {@link HttpClient}. Because
 * the download happens at interpret time or compile time (Java-side), a compiled program
 * has the sources spliced in and never fetches at runtime -- the WASM {@code fetch}
 * limitation does not apply.
 */
public final class DistClient {

	/** The name of the Quicklisp dist, installed by default. */
	public static final String QUICKLISP = "quicklisp";

	/** The name of the Ultralisp dist ({@code https://ultralisp.org/}), opt-in. */
	public static final String ULTRALISP = "ultralisp";

	/**
	 * The dists that have a name of their own: the canonical distinfo URL per name. Any
	 * other Quicklisp-format distribution is installed by its distinfo URL.
	 */
	private static final Map<String, String> KNOWN_DISTS = Map.of(//
			QUICKLISP, "https://beta.quicklisp.org/dist/quicklisp.txt", //
			ULTRALISP, "https://dist.ultralisp.org/ultralisp.txt");

	/**
	 * The hosts of the known dists, so the URL a user copies from the distribution's own
	 * front page ({@code http://dist.ultralisp.org/}, which serves the distinfo directly)
	 * shares the cache directory -- and the identity -- of the named spelling.
	 */
	private static final Map<String, String> KNOWN_HOSTS = Map.of(//
			"beta.quicklisp.org", QUICKLISP, //
			"www.quicklisp.org", QUICKLISP, //
			"quicklisp.org", QUICKLISP, //
			"dist.ultralisp.org", ULTRALISP, //
			"ultralisp.org", ULTRALISP);

	/**
	 * Fetches the bytes at a URL. Injectable so tests can serve an in-memory distribution
	 * without network access.
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

	/**
	 * A {@code systems.txt} entry: the release project that provides the system and the
	 * names of the systems it depends on.
	 */
	private record SystemEntry(String project, List<String> deps) {
	}

	/**
	 * A {@code releases.txt} entry: the tarball URL and the archive's top-level directory
	 * name (the extraction prefix).
	 */
	private record ReleaseEntry(String url, String prefix) {
	}

	/**
	 * One installed distribution: its name, the distinfo URL it bootstraps from, its
	 * cache directory and its parsed indexes (loaded lazily, on the first lookup that
	 * reaches this dist).
	 */
	private static final class Dist {

		private final String name;

		private final String distinfoUrl;

		private final Path home;

		@Nullable private Map<String, SystemEntry> systems;

		@Nullable private Map<String, ReleaseEntry> releases;

		private Dist(String name, String distinfoUrl, Path home) {
			this.name = name;
			this.distinfoUrl = distinfoUrl;
			this.home = home;
		}

	}

	/** Where a system name was found: the dist and the release project providing it. */
	private record Located(Dist dist, SystemEntry entry) {
	}

	private final Path base;

	private final Downloader downloader;

	private final Map<String, Path> homeOverrides;

	/** The installed dists, in search order. */
	private final List<Dist> dists = new ArrayList<>();

	/**
	 * Creates a client rooted at {@code base} (one subdirectory per dist), downloading
	 * through {@code downloader}, with only the Quicklisp dist installed.
	 * @param base the cache base directory
	 * @param downloader the byte fetcher
	 */
	public DistClient(Path base, Downloader downloader) {
		this(base, downloader, List.of(), Map.of());
	}

	/**
	 * Creates a client with additional dists installed beside Quicklisp.
	 * @param base the cache base directory
	 * @param downloader the byte fetcher
	 * @param distSpecs dist names or distinfo URLs to install, in search order
	 */
	public DistClient(Path base, Downloader downloader, List<String> distSpecs) {
		this(base, downloader, distSpecs, Map.of());
	}

	private DistClient(Path base, Downloader downloader, List<String> distSpecs, Map<String, Path> homeOverrides) {
		this.base = base;
		this.downloader = downloader;
		this.homeOverrides = Map.copyOf(homeOverrides);
		// Quicklisp is installed first unless the caller named it itself -- naming it is
		// how the search order is changed, since the first dist listing a system wins.
		boolean namesQuicklisp = distSpecs.stream().anyMatch(spec -> QUICKLISP.equals(distNameOrNull(spec)));
		if (!namesQuicklisp) {
			installDist(QUICKLISP);
		}
		for (String spec : distSpecs) {
			installDist(spec);
		}
	}

	/**
	 * Creates the default client: cache under {@code ~/.rontolisp/} (or
	 * {@code RONTOLISP_DIST_HOME}) and the JDK {@link HttpClient} as the downloader, with
	 * only the Quicklisp dist installed.
	 * @return the default client
	 */
	public static DistClient createDefault() {
		return createDefault(List.of());
	}

	/**
	 * Creates the default client with additional dists installed (the CLI {@code --dist}
	 * option / the {@code RONTOLISP_DISTS} environment variable).
	 * @param distSpecs dist names or distinfo URLs to install, in search order
	 * @return the default client
	 */
	public static DistClient createDefault(List<String> distSpecs) {
		// The environment overrides are read HERE and not in homeFor: a client built
		// with an explicit base (every test) must not pick up the developer's cache.
		Map<String, Path> overrides = new HashMap<>();
		String quicklispHome = System.getenv("RONTOLISP_QUICKLISP_HOME");
		if (quicklispHome != null && !quicklispHome.isBlank()) {
			overrides.put(QUICKLISP, Path.of(quicklispHome));
		}
		return new DistClient(defaultBase(), DistClient::httpGet, distSpecs, overrides);
	}

	/**
	 * Returns the default cache base directory: {@code RONTOLISP_DIST_HOME} if set,
	 * otherwise {@code ~/.rontolisp}. Each dist caches under {@code <base>/<dist-name>}.
	 * @return the default cache base directory
	 */
	public static Path defaultBase() {
		String override = System.getenv("RONTOLISP_DIST_HOME");
		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}
		return Path.of(System.getProperty("user.home", "."), ".rontolisp");
	}

	/**
	 * Installs a distribution: a known dist name ({@code quicklisp}, {@code ultralisp})
	 * or the URL of a Quicklisp-format distinfo. An already-installed dist keeps its
	 * position in the search order (the call is idempotent); a new one is appended, so it
	 * is consulted only for a system the dists before it do not provide. Nothing is
	 * downloaded here -- the dist's indexes are fetched when a lookup first reaches it.
	 * @param spec a dist name or a distinfo URL
	 * @return the name of the installed dist
	 * @throws IllegalArgumentException if the spec is neither a known name nor a URL
	 */
	public synchronized String installDist(String spec) {
		String name = distName(spec);
		for (Dist installed : this.dists) {
			if (installed.name.equals(name)) {
				return installed.name;
			}
		}
		this.dists.add(new Dist(name, distinfoUrl(spec), homeFor(name)));
		return name;
	}

	/**
	 * Drops a dist's cached indexes, so the next {@code quickload} re-reads the
	 * distribution's current {@code systems.txt} / {@code releases.txt} and sees the
	 * releases published since the cache was written. Already-extracted sources are kept
	 * (a release directory is named after its version, so a newer release extracts beside
	 * the old one).
	 * @param name the dist name
	 * @throws IOException if the dist is not installed or its cache cannot be cleared
	 */
	public synchronized void updateDist(String name) throws IOException {
		Dist dist = findDist(name);
		if (dist == null) {
			throw new IOException("no dist named '" + name + "' is installed (installed: "
					+ String.join(", ", installedNames()) + ")");
		}
		Files.deleteIfExists(dist.home.resolve("systems.txt"));
		Files.deleteIfExists(dist.home.resolve("releases.txt"));
		dist.systems = null;
		dist.releases = null;
	}

	/**
	 * Returns the installed dist names, in search order.
	 * @return the installed dist names
	 */
	public synchronized List<String> installedNames() {
		return this.dists.stream().map(dist -> dist.name).toList();
	}

	/**
	 * Ensures the named system and its transitive dependencies are downloaded and
	 * extracted, and returns the directories that contain {@code .asd} files -- to be
	 * added to the {@code asdf:load-system} search path so the actual loading proceeds
	 * through the {@link AsdfSystems} subset.
	 * @param systemName the system to make available
	 * @return the {@code .asd} directories (absolute paths), in a stable order
	 * @throws IOException if the dist metadata or a tarball cannot be fetched/extracted
	 */
	public synchronized List<String> ensureAvailable(String systemName) throws IOException {
		String lookupName = systemName;
		if (locate(lookupName) == null) {
			// A secondary system (NAME/SUB) no dist index lists individually lives in
			// NAME.asd by ASDF's naming rule, so the primary's release is the one to
			// download; AsdfSystems.locate resolves the slash name against that same
			// file, and reports loudly if it does not define the secondary system after
			// all. This is how "tiny-routes/lite" -- a system the replacement .asd adds
			// -- downloads the tiny-routes release.
			int slash = lookupName.indexOf('/');
			String primary = slash > 0 ? lookupName.substring(0, slash) : null;
			if (primary == null || locate(primary) == null) {
				throw new IOException("ql:quickload: system '" + systemName + "' is not in the installed dists ("
						+ String.join(", ", installedNames()) + ")");
			}
			lookupName = primary;
		}
		LinkedHashMap<String, ProjectRef> projects = new LinkedHashMap<>();
		collectProjects(lookupName, projects, new HashSet<>(), true);
		List<String> searchDirs = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (ProjectRef ref : projects.values()) {
			Path root = ensureProject(ref);
			collectAsdDirs(root, searchDirs, seen);
		}
		return searchDirs;
	}

	/** A release project to extract, and the dist it comes from. */
	private record ProjectRef(Dist dist, String project) {
	}

	/**
	 * Returns where {@code systemName} is provided: the first installed dist whose
	 * {@code systems.txt} lists it, or {@code null} if no dist does. The dists ahead of
	 * the hit have their indexes loaded (once, cached); the ones behind it are never
	 * touched.
	 */
	@Nullable private Located locate(String systemName) throws IOException {
		for (Dist dist : this.dists) {
			SystemEntry entry = systems(dist).get(systemName);
			if (entry != null) {
				return new Located(dist, entry);
			}
		}
		return null;
	}

	/**
	 * Walks the dependency graph from {@code system} (via each dist's
	 * {@code systems.txt}), adding each reachable release project to {@code projects},
	 * keyed by dist and project so the same project name in two dists stays two
	 * downloads. Every name is resolved across ALL installed dists independently, so a
	 * dependency the requested system's own dist lacks is taken from a later one. A
	 * dependency that is in no dist is skipped (it is either built into Common Lisp or
	 * genuinely unavailable -- the {@code asdf} loader reports it later if it is really
	 * needed); the originally requested system must be present, so an unknown one there
	 * is an error.
	 */
	private void collectProjects(String system, LinkedHashMap<String, ProjectRef> projects, Set<String> visited,
			boolean required) throws IOException {
		if (!visited.add(system)) {
			return;
		}
		Located located = locate(system);
		if (located == null) {
			if (required) {
				throw new IOException("ql:quickload: system '" + system + "' is not in the installed dists ("
						+ String.join(", ", installedNames()) + ")");
			}
			return;
		}
		for (String dep : located.entry().deps()) {
			collectProjects(dep, projects, visited, false);
		}
		String project = located.entry().project();
		projects.putIfAbsent(located.dist().name + "/" + project, new ProjectRef(located.dist(), project));
	}

	/**
	 * Ensures a release project's tarball is downloaded and extracted under
	 * {@code <dist home>/software/<prefix>/}, and returns that directory. An
	 * already-extracted project is reused (no network I/O).
	 */
	private Path ensureProject(ProjectRef ref) throws IOException {
		ReleaseEntry release = releases(ref.dist()).get(ref.project());
		if (release == null) {
			throw new IOException("ql:quickload: no release found for project '" + ref.project() + "' in dist '"
					+ ref.dist().name + "'");
		}
		Path software = ref.dist().home.resolve("software");
		Path root = software.resolve(release.prefix());
		if (Files.isDirectory(root)) {
			return root;
		}
		byte[] tarGz = this.downloader.get(release.url());
		Files.createDirectories(software);
		extractTarGz(tarGz, software);
		if (!Files.isDirectory(root)) {
			throw new IOException("ql:quickload: archive for '" + ref.project() + "' did not contain the expected"
					+ " directory '" + release.prefix() + "'");
		}
		return root;
	}

	private Map<String, SystemEntry> systems(Dist dist) throws IOException {
		ensureIndex(dist);
		return java.util.Objects.requireNonNull(dist.systems);
	}

	private Map<String, ReleaseEntry> releases(Dist dist) throws IOException {
		ensureIndex(dist);
		return java.util.Objects.requireNonNull(dist.releases);
	}

	/**
	 * Ensures a dist's indexes ({@code systems.txt}, {@code releases.txt}) are cached and
	 * parsed. On a cache miss, the distinfo is fetched to discover the index URLs, which
	 * are then downloaded and written to the cache.
	 */
	private void ensureIndex(Dist dist) throws IOException {
		if (dist.systems != null) {
			return;
		}
		Files.createDirectories(dist.home);
		Path systemsFile = dist.home.resolve("systems.txt");
		Path releasesFile = dist.home.resolve("releases.txt");
		if (!Files.isRegularFile(systemsFile) || !Files.isRegularFile(releasesFile)) {
			String distinfo = new String(this.downloader.get(dist.distinfoUrl), StandardCharsets.UTF_8);
			String systemsUrl = distinfoValue(distinfo, "system-index-url");
			String releasesUrl = distinfoValue(distinfo, "release-index-url");
			Files.write(systemsFile, this.downloader.get(systemsUrl));
			Files.write(releasesFile, this.downloader.get(releasesUrl));
		}
		dist.systems = parseSystems(Files.readString(systemsFile, StandardCharsets.UTF_8));
		dist.releases = parseReleases(Files.readString(releasesFile, StandardCharsets.UTF_8));
	}

	@Nullable private Dist findDist(String name) {
		for (Dist dist : this.dists) {
			if (dist.name.equals(name)) {
				return dist;
			}
		}
		return null;
	}

	private Path homeFor(String name) {
		Path override = this.homeOverrides.get(name);
		return override != null ? override : this.base.resolve(name);
	}

	/**
	 * The name a dist spec installs under: a known name as written, a URL by its host
	 * when the host is a known distribution's, and otherwise a directory-safe slug of the
	 * URL (host plus path) -- so an unknown distribution still gets a stable cache
	 * directory without a distinfo round trip to learn the name it calls itself.
	 */
	private static String distName(String spec) {
		String name = distNameOrNull(spec);
		if (name == null) {
			throw new IllegalArgumentException("unknown dist '" + spec + "': give a known dist name ("
					+ String.join(", ", KNOWN_DISTS.keySet().stream().sorted().toList())
					+ ") or the URL of a Quicklisp-format distinfo");
		}
		return name;
	}

	@Nullable private static String distNameOrNull(String spec) {
		String trimmed = spec.trim();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		if (KNOWN_DISTS.containsKey(lower)) {
			return lower;
		}
		if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
			return null;
		}
		URI uri;
		try {
			uri = URI.create(trimmed);
		}
		catch (IllegalArgumentException _) {
			// Not a URL after all: reported as an unknown spec by distName, rather than
			// as a URI syntax error from inside the constructor's order check.
			return null;
		}
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		String known = KNOWN_HOSTS.get(host);
		if (known != null) {
			return known;
		}
		String path = uri.getPath() == null ? "" : uri.getPath();
		String slug = (host + path).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
		return slug.isEmpty() ? "dist" : slug;
	}

	/**
	 * The distinfo URL a spec bootstraps from: a known dist's canonical URL, whether it
	 * was named or given as one of its own URLs (the front-page {@code
	 * http://dist.ultralisp.org/} redirects to the same distinfo, and one dist must have
	 * one URL); any other spec is the URL as written.
	 */
	private static String distinfoUrl(String spec) {
		String trimmed = spec.trim();
		String known = KNOWN_DISTS.get(distNameOrNull(trimmed));
		return known != null ? known : trimmed;
	}

	private static String distinfoValue(String distinfo, String key) throws IOException {
		for (String line : distinfo.split("\n")) {
			int colon = line.indexOf(':');
			if (colon > 0 && line.substring(0, colon).trim().equals(key)) {
				return line.substring(colon + 1).trim();
			}
		}
		throw new IOException("ql:quickload: distinfo is missing '" + key + "'");
	}

	/**
	 * Parses {@code systems.txt}: each non-comment line is
	 * {@code project system-file system-name dep...}, keyed by system name.
	 */
	private static Map<String, SystemEntry> parseSystems(String text) {
		Map<String, SystemEntry> index = new HashMap<>();
		for (String line : text.split("\n")) {
			if (line.isBlank() || line.charAt(0) == '#') {
				continue;
			}
			String[] parts = line.trim().split("\\s+");
			if (parts.length < 3) {
				continue;
			}
			List<String> deps = parts.length > 3 ? List.of(parts).subList(3, parts.length) : List.of();
			index.put(parts[2], new SystemEntry(parts[0], List.copyOf(deps)));
		}
		return index;
	}

	/**
	 * Parses {@code releases.txt}: each non-comment line is
	 * {@code project url size md5 sha1 prefix file...}, keyed by project.
	 */
	private static Map<String, ReleaseEntry> parseReleases(String text) {
		Map<String, ReleaseEntry> index = new HashMap<>();
		for (String line : text.split("\n")) {
			if (line.isBlank() || line.charAt(0) == '#') {
				continue;
			}
			String[] parts = line.trim().split("\\s+");
			if (parts.length < 6) {
				continue;
			}
			index.put(parts[0], new ReleaseEntry(parts[1], parts[5]));
		}
		return index;
	}

	/**
	 * Adds every directory under {@code root} that contains a {@code .asd} file to
	 * {@code out} (deduplicated via {@code seen}). This makes each system's definition
	 * locatable by {@link AsdfSystems#locate} regardless of how deep the {@code .asd}
	 * sits in the release.
	 */
	private static void collectAsdDirs(Path root, List<String> out, Set<String> seen) throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path p : (Iterable<Path>) walk::iterator) {
				if (!Files.isRegularFile(p) || !p.getFileName().toString().endsWith(".asd")) {
					continue;
				}
				Path parent = p.getParent();
				if (parent == null) {
					continue;
				}
				String dir = parent.toAbsolutePath().normalize().toString();
				if (seen.add(dir)) {
					out.add(dir);
				}
			}
		}
	}

	// --- tar.gz extraction (USTAR / GNU tar, no external dependencies) ---

	/**
	 * Extracts a gzip-compressed tar archive into {@code destDir}. Handles the USTAR
	 * {@code name}/{@code prefix} split and GNU long-name ({@code L}) entries, creates
	 * directories and regular files, and skips other entry types. Entry paths are
	 * normalized and confined to {@code destDir} (a path-traversal guard).
	 */
	private static void extractTarGz(byte[] tarGz, Path destDir) throws IOException {
		Path base = destDir.toAbsolutePath().normalize();
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(tarGz))) {
			String longName = null;
			while (true) {
				byte[] header = in.readNBytes(512);
				if (header.length < 512 || isZeroBlock(header)) {
					break;
				}
				long size = parseOctal(header, 124, 12);
				int dataBlocks = (int) ((size + 511) / 512);
				char type = (char) (header[156] & 0xff);
				if (type == 'L') {
					// GNU long name: the entry data is the next entry's full name.
					byte[] data = in.readNBytes(dataBlocks * 512);
					longName = trimNul(new String(data, 0, (int) size, StandardCharsets.UTF_8));
					continue;
				}
				String name = longName != null ? longName : combineName(header);
				longName = null;
				if (type == '5') {
					Files.createDirectories(safeResolve(base, name));
				}
				else if (type == '0' || type == '\0') {
					byte[] data = in.readNBytes(dataBlocks * 512);
					Path target = safeResolve(base, name);
					Path parent = target.getParent();
					if (parent != null) {
						Files.createDirectories(parent);
					}
					Files.write(target, java.util.Arrays.copyOf(data, (int) size));
					continue;
				}
				// Consume the data of skipped entry types (symlinks, GNU long link, ...).
				if (type != '5') {
					in.skipNBytes((long) dataBlocks * 512);
				}
			}
		}
	}

	private static String combineName(byte[] header) {
		String name = parseString(header, 0, 100);
		String prefix = parseString(header, 345, 155);
		return prefix.isEmpty() ? name : prefix + "/" + name;
	}

	private static Path safeResolve(Path base, String name) throws IOException {
		Path resolved = base.resolve(name).normalize();
		if (!resolved.startsWith(base)) {
			throw new IOException("ql:quickload: unsafe path in archive: " + name);
		}
		return resolved;
	}

	private static boolean isZeroBlock(byte[] block) {
		for (byte b : block) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}

	private static String parseString(byte[] block, int offset, int length) {
		int end = offset;
		int limit = offset + length;
		while (end < limit && block[end] != 0) {
			end++;
		}
		return new String(block, offset, end - offset, StandardCharsets.UTF_8);
	}

	private static String trimNul(String s) {
		int end = s.indexOf('\0');
		return (end < 0 ? s : s.substring(0, end)).trim();
	}

	private static long parseOctal(byte[] block, int offset, int length) {
		long value = 0;
		int i = offset;
		int limit = offset + length;
		// Skip leading spaces and NULs.
		while (i < limit && (block[i] == ' ' || block[i] == 0)) {
			i++;
		}
		while (i < limit && block[i] >= '0' && block[i] <= '7') {
			value = (value << 3) + (block[i] - '0');
			i++;
		}
		return value;
	}

	private static byte[] httpGet(String url) throws IOException {
		HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
		try {
			HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200) {
				throw new IOException("ql:quickload: HTTP " + response.statusCode() + " for " + url);
			}
			return response.body();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("ql:quickload: download interrupted for " + url, ex);
		}
	}

}
