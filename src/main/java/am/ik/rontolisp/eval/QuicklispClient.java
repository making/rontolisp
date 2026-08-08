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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.jspecify.annotations.Nullable;

/**
 * A limited, API-compatible subset of Quicklisp behind {@code ql:quickload}. Not a port:
 * it downloads a system (and its transitive dependencies) from the <em>real</em>
 * Quicklisp distribution into a local cache, extracts the release tarballs, and then
 * hands the extracted {@code .asd} directories to the {@link AsdfSystems} loader -- so
 * {@code ql:quickload} is exactly {@code asdf:load-system} with an auto-download step in
 * front of it.
 *
 * <p>
 * The download uses the Quicklisp dist metadata: {@code quicklisp.txt} (the distinfo)
 * points at {@code systems.txt} (each line {@code project system-file system-name
 * dep...}, giving dependency resolution) and {@code releases.txt} (each line
 * {@code project url size md5 sha1 prefix file...}, giving the tarball URL and its
 * top-level directory). Both indexes and the extracted sources are cached under
 * {@code ~/.rontolisp/quicklisp/} (override with {@code RONTOLISP_QUICKLISP_HOME}), so a
 * second {@code quickload} of the same system does no network I/O.
 *
 * <p>
 * The {@link Downloader} is injectable so tests can serve an in-memory dist without
 * touching the network; {@link #createDefault()} uses the JDK {@link HttpClient}. Because
 * the download happens at interpret time or compile time (Java-side), a compiled program
 * has the sources spliced in and never fetches at runtime -- the WASM {@code fetch}
 * limitation does not apply.
 */
public final class QuicklispClient {

	/** The canonical Quicklisp distinfo subscription URL (points at the current dist). */
	private static final String DISTINFO_URL = "https://beta.quicklisp.org/dist/quicklisp.txt";

	/**
	 * Fetches the bytes at a URL. Injectable so tests can serve an in-memory Quicklisp
	 * distribution without network access.
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

	private final Path home;

	private final Downloader downloader;

	@Nullable private Map<String, SystemEntry> systemsIndex;

	@Nullable private Map<String, ReleaseEntry> releasesIndex;

	/**
	 * Creates a client rooted at {@code home}, downloading through {@code downloader}.
	 * @param home the cache directory (indexes + extracted sources)
	 * @param downloader the byte fetcher
	 */
	public QuicklispClient(Path home, Downloader downloader) {
		this.home = home;
		this.downloader = downloader;
	}

	/**
	 * Creates the default client: cache under {@code ~/.rontolisp/quicklisp/} (or
	 * {@code RONTOLISP_QUICKLISP_HOME}) and the JDK {@link HttpClient} as the downloader.
	 * @return the default client
	 */
	public static QuicklispClient createDefault() {
		return new QuicklispClient(defaultHome(), QuicklispClient::httpGet);
	}

	/**
	 * Returns the default cache directory: {@code RONTOLISP_QUICKLISP_HOME} if set,
	 * otherwise {@code ~/.rontolisp/quicklisp}.
	 * @return the default cache directory
	 */
	public static Path defaultHome() {
		String override = System.getenv("RONTOLISP_QUICKLISP_HOME");
		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}
		return Path.of(System.getProperty("user.home", "."), ".rontolisp", "quicklisp");
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
		ensureIndex();
		Map<String, SystemEntry> systems = java.util.Objects.requireNonNull(this.systemsIndex);
		Map<String, ReleaseEntry> releases = java.util.Objects.requireNonNull(this.releasesIndex);
		String lookupName = systemName;
		if (!systems.containsKey(lookupName)) {
			// A secondary system (NAME/SUB) the dist index does not list individually
			// lives in NAME.asd by ASDF's naming rule, so the primary's release is the
			// one to download; AsdfSystems.locate resolves the slash name against that
			// same file, and reports loudly if it does not define the secondary system
			// after all. This is how "tiny-routes/lite" -- a system the replacement
			// .asd adds -- downloads the tiny-routes release.
			int slash = lookupName.indexOf('/');
			String primary = slash > 0 ? lookupName.substring(0, slash) : null;
			if (primary == null || !systems.containsKey(primary)) {
				throw new IOException(
						"ql:quickload: system '" + systemName + "' is not in the Quicklisp distribution (systems.txt)");
			}
			lookupName = primary;
		}
		LinkedHashSet<String> projects = new LinkedHashSet<>();
		collectProjects(lookupName, projects, new HashSet<>(), true, systems);
		List<String> searchDirs = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String project : projects) {
			Path root = ensureProject(project, releases);
			collectAsdDirs(root, searchDirs, seen);
		}
		return searchDirs;
	}

	/**
	 * Walks the dependency graph from {@code system} (via {@code systems.txt}), adding
	 * each reachable release project to {@code projects}. A dependency that is not in the
	 * distribution is skipped (it is either built into Common Lisp or genuinely
	 * unavailable -- the {@code asdf} loader reports it later if it is really needed);
	 * the originally requested system must be present, so an unknown one there is an
	 * error.
	 */
	private void collectProjects(String system, LinkedHashSet<String> projects, Set<String> visited, boolean required,
			Map<String, SystemEntry> systems) throws IOException {
		if (!visited.add(system)) {
			return;
		}
		SystemEntry entry = systems.get(system);
		if (entry == null) {
			if (required) {
				throw new IOException("ql:quickload: system '" + system + "' is not in the Quicklisp distribution");
			}
			return;
		}
		for (String dep : entry.deps()) {
			collectProjects(dep, projects, visited, false, systems);
		}
		projects.add(entry.project());
	}

	/**
	 * Ensures a release project's tarball is downloaded and extracted under
	 * {@code home/software/<prefix>/}, and returns that directory. An already-extracted
	 * project is reused (no network I/O).
	 */
	private Path ensureProject(String project, Map<String, ReleaseEntry> releases) throws IOException {
		ReleaseEntry release = releases.get(project);
		if (release == null) {
			throw new IOException("ql:quickload: no release found for project '" + project + "'");
		}
		Path software = this.home.resolve("software");
		Path root = software.resolve(release.prefix());
		if (Files.isDirectory(root)) {
			return root;
		}
		byte[] tarGz = this.downloader.get(release.url());
		Files.createDirectories(software);
		extractTarGz(tarGz, software);
		if (!Files.isDirectory(root)) {
			throw new IOException("ql:quickload: archive for '" + project + "' did not contain the expected directory '"
					+ release.prefix() + "'");
		}
		return root;
	}

	/**
	 * Ensures the dist indexes ({@code systems.txt}, {@code releases.txt}) are cached and
	 * parsed. On a cache miss, the distinfo is fetched to discover the index URLs, which
	 * are then downloaded and written to the cache.
	 */
	private void ensureIndex() throws IOException {
		if (this.systemsIndex != null) {
			return;
		}
		Files.createDirectories(this.home);
		Path systemsFile = this.home.resolve("systems.txt");
		Path releasesFile = this.home.resolve("releases.txt");
		if (!Files.isRegularFile(systemsFile) || !Files.isRegularFile(releasesFile)) {
			String distinfo = new String(this.downloader.get(DISTINFO_URL), StandardCharsets.UTF_8);
			String systemsUrl = distinfoValue(distinfo, "system-index-url");
			String releasesUrl = distinfoValue(distinfo, "release-index-url");
			Files.write(systemsFile, this.downloader.get(systemsUrl));
			Files.write(releasesFile, this.downloader.get(releasesUrl));
		}
		this.systemsIndex = parseSystems(Files.readString(systemsFile, StandardCharsets.UTF_8));
		this.releasesIndex = parseReleases(Files.readString(releasesFile, StandardCharsets.UTF_8));
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
