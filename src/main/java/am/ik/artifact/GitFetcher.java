package am.ik.artifact;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * Fetches a git repository at a pinned commit into the cache, through the {@code git}
 * command line (any host, any transport, the user's own credentials and git config).
 *
 * <p>
 * Layout under the area ({@code <root>/gitlibs/}):
 * <ul>
 * <li>{@code repos/<key>/} -- a bare clone of the repository, fetched into when a commit
 * or tag is missing, and {@code repos/<key>.lock}, the lock every update of it
 * holds;</li>
 * <li>{@code libs/<key>/<sha>/} -- the checkout of one commit: the committed tree, no
 * {@code .git}. Its existence is the installed mark ({@link AtomicInstall}), and nothing
 * writes into it afterwards.</li>
 * </ul>
 * The key is the repository's identity -- host and path, the scheme, user, and a trailing
 * {@code .git} dropped -- as one readable path segment plus a hash of that identity, so
 * {@code https://github.com/a/b.git} and {@code git@github.com:a/b} share a clone and no
 * two repositories ever do.
 *
 * <p>
 * An installed checkout needs no git: without a tag, a second fetch is a directory
 * lookup. A tag is checked against the clone every time (no network while it agrees).
 */
public final class GitFetcher {

	/** The cache area this fetcher owns. */
	public static final String AREA = "gitlibs";

	/** The git command the default fetcher runs. */
	public static final String DEFAULT_EXECUTABLE = "git";

	/**
	 * The {@code deps.edn} lib-name prefixes tools.deps infers a repository URL from, and
	 * the URL format each one fills with the rest of the group and the artifact name.
	 */
	private static final List<Map.Entry<String, String>> INFERRED_URLS = List.of(
			Map.entry("github.", "https://github.com/%s/%s.git"),
			Map.entry("com.github.", "https://github.com/%s/%s.git"),
			Map.entry("io.github.", "https://github.com/%s/%s.git"),
			Map.entry("gitlab.", "https://gitlab.com/%s/%s.git"),
			Map.entry("com.gitlab.", "https://gitlab.com/%s/%s.git"),
			Map.entry("io.gitlab.", "https://gitlab.com/%s/%s.git"),
			Map.entry("bitbucket.", "https://bitbucket.org/%s/%s.git"),
			Map.entry("org.bitbucket.", "https://bitbucket.org/%s/%s.git"),
			Map.entry("io.bitbucket.", "https://bitbucket.org/%s/%s.git"),
			Map.entry("beanstalkapp.", "https://%s.git.beanstalkapp.com/%s.git"),
			Map.entry("com.beanstalkapp.", "https://%s.git.beanstalkapp.com/%s.git"),
			Map.entry("ht.sr.", "https://git.sr.ht/~%s/%s"));

	/**
	 * One monitor per lock file: a JVM may not hold two {@link FileLock}s on one file.
	 */
	private static final Map<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

	/** The longest readable part of a key, before its hash. */
	private static final int KEY_READABLE_LENGTH = 80;

	private final Path area;

	private final GitCommand git;

	/**
	 * Creates a fetcher over an area, running {@code executable} as git.
	 * @param area the cache area (normally {@code cache.area(AREA)})
	 * @param executable the git command
	 */
	public GitFetcher(Path area, String executable) {
		this.area = area;
		this.git = new GitCommand(executable);
	}

	/**
	 * Creates the fetcher over {@code cache}'s {@link #AREA}, running {@code git} from
	 * {@code PATH}.
	 * @param cache the artifact cache
	 * @return the fetcher
	 */
	public static GitFetcher create(ArtifactCache cache) {
		return new GitFetcher(cache.area(AREA), DEFAULT_EXECUTABLE);
	}

	/**
	 * Answers the repository URL tools.deps infers from a lib name
	 * ({@code io.github.user/repo} -> {@code https://github.com/user/repo.git}), or
	 * {@code null} when the name names no known forge.
	 * @param lib the qualified lib name, {@code group/artifact}
	 * @return the URL, or {@code null}
	 */
	public static @Nullable String inferUrl(String lib) {
		int slash = lib.indexOf('/');
		if (slash <= 0 || slash == lib.length() - 1 || lib.indexOf('/', slash + 1) >= 0) {
			return null;
		}
		String group = lib.substring(0, slash);
		String artifact = lib.substring(slash + 1);
		for (Map.Entry<String, String> entry : INFERRED_URLS) {
			if (group.startsWith(entry.getKey()) && group.length() > entry.getKey().length()) {
				return entry.getValue().formatted(group.substring(entry.getKey().length()), artifact);
			}
		}
		return null;
	}

	/**
	 * Answers the checkout of {@code coordinate}'s commit -- or its {@code root}
	 * sub-directory -- installing it first if the cache has none. Clones or fetches the
	 * repository only when the commit (or the tag) is not already in the local clone.
	 * @param coordinate the repository, commit, tag and root
	 * @return the directory
	 * @throws IOException if git cannot be run, the commit or tag cannot be found, the
	 * tag names another commit, or the root is not a directory of the checkout
	 */
	public Path fetch(GitCoordinate coordinate) throws IOException {
		String key = cacheKey(coordinate.url());
		Path lib = this.area.resolve("libs").resolve(key).resolve(coordinate.sha());
		if (coordinate.tag() != null || !Files.isDirectory(lib)) {
			Path repos = this.area.resolve("repos");
			Files.createDirectories(repos);
			Path lockFile = repos.resolve(key + ".lock").toAbsolutePath().normalize();
			synchronized (JVM_LOCKS.computeIfAbsent(lockFile, path -> new Object())) {
				try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
						StandardOpenOption.WRITE); FileLock fileLock = channel.lock()) {
					Path mirror = ensureMirror(coordinate, repos.resolve(key));
					ensureCommit(coordinate, mirror);
					String tag = coordinate.tag();
					if (tag != null) {
						checkTag(coordinate, tag, mirror);
					}
					AtomicInstall.installDirectory(lib, staging -> checkout(mirror, coordinate.sha(), staging));
				}
			}
		}
		return resolveRoot(lib, coordinate);
	}

	/**
	 * Answers the cache key of a repository URL: its identity (lower-case host and port,
	 * then the path without a trailing {@code .git}) made one readable path segment, then
	 * {@code -} and 16 hex digits of the identity's 64-bit FNV-1a hash.
	 * @param url the repository URL
	 * @return the key
	 */
	static String cacheKey(String url) {
		String identity = identity(url);
		StringBuilder readable = new StringBuilder();
		for (int i = 0; i < identity.length() && readable.length() < KEY_READABLE_LENGTH; i++) {
			char c = identity.charAt(i);
			boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.'
					|| c == '-';
			readable.append(safe ? c : '_');
		}
		if (readable.isEmpty() || readable.charAt(0) == '.') {
			readable.insert(0, '_');
		}
		long hash = 0xcbf29ce484222325L;
		for (byte b : identity.getBytes(StandardCharsets.UTF_8)) {
			hash ^= b & 0xff;
			hash *= 0x100000001b3L;
		}
		return readable + "-" + String.format(Locale.ROOT, "%016x", hash);
	}

	/**
	 * The scheme-independent identity of a repository URL: {@code host[:port]/path}, or
	 * {@code /path} for a local repository.
	 */
	private static String identity(String url) {
		String host;
		String path;
		int scheme = url.indexOf("://");
		int colon = url.indexOf(':');
		int slash = url.indexOf('/');
		if (scheme > 0) {
			try {
				URI uri = new URI(url);
				host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
				if (uri.getPort() >= 0) {
					host += ":" + uri.getPort();
				}
				path = uri.getPath() == null ? "" : uri.getPath();
			}
			catch (URISyntaxException ex) {
				host = "";
				path = url.substring(scheme + 3);
			}
		}
		else if (colon > 0 && (slash < 0 || colon < slash)) {
			// scp-like: [user@]host:path
			String authority = url.substring(0, colon);
			host = authority.substring(authority.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
			path = url.substring(colon + 1);
		}
		else {
			host = "";
			path = url;
		}
		path = path.replace('\\', '/');
		while (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		if (path.endsWith(".git")) {
			path = path.substring(0, path.length() - 4);
		}
		while (path.startsWith("/")) {
			path = path.substring(1);
		}
		return host + "/" + path;
	}

	/** Clones the repository bare unless the clone exists; answers the clone. */
	private Path ensureMirror(GitCoordinate coordinate, Path mirror) throws IOException {
		return AtomicInstall.installDirectory(mirror, staging -> {
			Path clone = staging.resolve("repo.git");
			this.git.run(Map.of(), List.of("clone", "--bare", "--quiet", "--", coordinate.url(), clone.toString()));
			return clone;
		});
	}

	/**
	 * Makes the commit present in the clone: fetches every branch and tag when it is
	 * missing, then the commit itself (a commit no ref names, where the server allows
	 * it).
	 */
	private void ensureCommit(GitCoordinate coordinate, Path mirror) throws IOException {
		if (hasCommit(mirror, coordinate.sha())) {
			return;
		}
		fetchRefs(coordinate, mirror);
		if (hasCommit(mirror, coordinate.sha())) {
			return;
		}
		GitCommand.Result bySha = this.git.exec(Map.of(), List.of("--git-dir=" + mirror, "fetch", "--quiet", "--",
				coordinate.url(), coordinate.sha() + ":refs/rontolisp/" + coordinate.sha()));
		if (!bySha.ok() || !hasCommit(mirror, coordinate.sha())) {
			throw new IOException("commit " + coordinate.sha() + " not found in " + coordinate.url());
		}
	}

	private void fetchRefs(GitCoordinate coordinate, Path mirror) throws IOException {
		this.git.run(Map.of(), List.of("--git-dir=" + mirror, "fetch", "--quiet", "--", coordinate.url(),
				"+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*"));
	}

	private boolean hasCommit(Path mirror, String sha) throws IOException {
		return this.git.exec(Map.of(), List.of("--git-dir=" + mirror, "cat-file", "-e", sha + "^{commit}")).ok();
	}

	/**
	 * Checks that the tag names the commit, fetching the tags first when the clone's tag
	 * is missing or names another commit (a tag may have been created or moved upstream).
	 */
	private void checkTag(GitCoordinate coordinate, String tag, Path mirror) throws IOException {
		String tagged = tagCommit(mirror, tag);
		if (!coordinate.sha().equals(tagged)) {
			fetchRefs(coordinate, mirror);
			tagged = tagCommit(mirror, tag);
		}
		if (tagged == null) {
			throw new IOException("tag " + tag + " not found in " + coordinate.url());
		}
		if (!coordinate.sha().equals(tagged)) {
			throw new IOException("tag " + tag + " names commit " + tagged + ", not " + coordinate.sha() + ", in "
					+ coordinate.url());
		}
	}

	private @Nullable String tagCommit(Path mirror, String tag) throws IOException {
		GitCommand.Result result = this.git.exec(Map.of(),
				List.of("--git-dir=" + mirror, "rev-parse", "--verify", "--quiet", "refs/tags/" + tag + "^{commit}"));
		return result.ok() ? result.stdout().strip() : null;
	}

	/**
	 * Writes the commit's tree into {@code staging/tree} through a private index, so the
	 * clone's own state is never touched, and answers the tree. Line endings are the
	 * committed ones whatever the user's {@code core.autocrlf}.
	 */
	private Path checkout(Path mirror, String sha, Path staging) throws IOException {
		Path tree = Files.createDirectory(staging.resolve("tree"));
		Map<String, String> env = Map.of("GIT_INDEX_FILE", staging.resolve("index").toString());
		this.git.run(env, List.of("--git-dir=" + mirror, "read-tree", sha));
		this.git.run(env, List.of("-c", "core.autocrlf=false", "--git-dir=" + mirror, "--work-tree=" + tree,
				"checkout-index", "--all", "--force"));
		return tree;
	}

	private static Path resolveRoot(Path lib, GitCoordinate coordinate) throws IOException {
		String root = coordinate.root();
		if (root == null) {
			return lib;
		}
		Path dir = lib.resolve(root);
		if (!Files.isDirectory(dir) || !dir.toRealPath().startsWith(lib.toRealPath())) {
			throw new IOException("root directory " + root + " is not a directory of " + coordinate.url() + " at "
					+ coordinate.sha());
		}
		return dir;
	}

}
