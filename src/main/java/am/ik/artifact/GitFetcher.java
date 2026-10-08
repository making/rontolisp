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
 * command line (any host, any transport, the user's own credentials and git config), and
 * answers what a dependency resolver asks of a repository: the commit a tag or an
 * abbreviated sha names, whether a tag exists, which of two commits descends from the
 * other.
 *
 * <p>
 * Layout under the area ({@code <root>/gitlibs/}):
 * <ul>
 * <li>{@code repos/<key>/} -- a bare clone of the repository, fetched into when a commit
 * or tag is missing, and {@code repos/<key>.lock}, the lock every use of it holds;</li>
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
 * Every question fetches the repository only when the clone cannot answer it.
 */
public final class GitFetcher {

	/** The cache area this fetcher owns. */
	public static final String AREA = "gitlibs";

	/** The git command the default fetcher runs. */
	public static final String DEFAULT_EXECUTABLE = "git";

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
	 * Answers the checkout of {@code coordinate}'s commit -- or its {@code root}
	 * sub-directory -- installing it first if the cache has none. Clones or fetches the
	 * repository only when the commit (or the tag) is not already in the local clone.
	 * @param coordinate the repository, commit, tag and root
	 * @return the directory
	 * @throws IOException if git cannot be run, the commit or tag cannot be found, the
	 * tag names another commit, or the root is not a directory of the checkout
	 */
	public Path fetch(GitCoordinate coordinate) throws IOException {
		Path lib = libOf(coordinate.url(), coordinate.sha());
		if (coordinate.tag() != null || !Files.isDirectory(lib)) {
			withClone(coordinate.url(), mirror -> {
				if (!ensureCommit(coordinate.url(), coordinate.sha(), mirror)) {
					throw new IOException("commit " + coordinate.sha() + " not found in " + coordinate.url());
				}
				String tag = coordinate.tag();
				if (tag != null) {
					checkTag(coordinate, tag, mirror);
				}
				return AtomicInstall.installDirectory(lib, staging -> writeTree(mirror, coordinate.sha(), staging));
			});
		}
		return resolveRoot(lib, coordinate);
	}

	/**
	 * Answers the checkout of a commit, installing it first if the cache has none, like
	 * {@link #fetch} without a tag or a root -- or {@code null} when the repository has
	 * no such commit.
	 * @param url the repository URL
	 * @param sha the full sha
	 * @return the directory, or {@code null}
	 * @throws IOException if git cannot be run or the repository cannot be cloned
	 * @throws IllegalArgumentException if the URL would reach git as an option, or the
	 * sha is not a full one
	 */
	public @Nullable Path checkout(String url, String sha) throws IOException {
		GitCoordinate.checkUrl(url);
		String full = GitCoordinate.fullSha(sha, url);
		Path lib = libOf(url, full);
		if (Files.isDirectory(lib)) {
			return lib;
		}
		return withClone(url, mirror -> ensureCommit(url, full, mirror)
				? AtomicInstall.installDirectory(lib, staging -> writeTree(mirror, full, staging)) : null);
	}

	private Path libOf(String url, String sha) {
		return this.area.resolve("libs").resolve(cacheKey(url)).resolve(sha);
	}

	/**
	 * Answers the full sha of the commit a revision names in a repository -- a tag, a
	 * branch, a full or abbreviated sha -- cloning the repository when the cache has no
	 * clone of it, and fetching its branches and tags once when the clone cannot resolve
	 * the revision.
	 * @param url the repository URL
	 * @param revision the revision
	 * @return the full sha, lower case, or {@code null} when the repository has no commit
	 * of that name (an abbreviation two commits share names none)
	 * @throws IOException if git cannot be run or the repository cannot be cloned
	 * @throws IllegalArgumentException if the URL or revision would reach git as an
	 * option or is no name
	 */
	public @Nullable String resolve(String url, String revision) throws IOException {
		GitCoordinate.checkUrl(url);
		GitCoordinate.checkRevision(revision, "revision", url);
		return withClone(url, mirror -> {
			String sha = commitOf(mirror, revision);
			if (sha == null) {
				fetchRefs(url, mirror);
				sha = commitOf(mirror, revision);
			}
			return sha;
		});
	}

	/**
	 * Answers whether a repository has a tag of a name, fetching its branches and tags
	 * once when the clone has none of that name.
	 * @param url the repository URL
	 * @param tag the tag name
	 * @return whether the tag exists
	 * @throws IOException if git cannot be run or the repository cannot be cloned
	 * @throws IllegalArgumentException if the URL or tag would reach git as an option or
	 * is no name
	 */
	public boolean hasTag(String url, String tag) throws IOException {
		GitCoordinate.checkUrl(url);
		GitCoordinate.checkRevision(tag, "tag", url);
		Boolean found = withClone(url, mirror -> {
			if (tagExists(mirror, tag)) {
				return true;
			}
			fetchRefs(url, mirror);
			return tagExists(mirror, tag);
		});
		return Boolean.TRUE.equals(found);
	}

	/**
	 * Answers which of two commits of a repository descends from the other -- the newer
	 * of two versions of one history -- fetching once when the clone lacks either.
	 * @param url the repository URL
	 * @param x one full sha
	 * @param y the other full sha
	 * @return {@code x} or {@code y}, whichever descends from the other (either when they
	 * are one commit), or {@code null} when neither does or the repository lacks one
	 * @throws IOException if git cannot be run, the repository cannot be cloned, or git
	 * cannot compare the commits
	 * @throws IllegalArgumentException if the URL would reach git as an option, or a sha
	 * is not a full one
	 */
	public @Nullable String descendant(String url, String x, String y) throws IOException {
		GitCoordinate.checkUrl(url);
		String shaX = GitCoordinate.fullSha(x, url);
		String shaY = GitCoordinate.fullSha(y, url);
		if (shaX.equals(shaY)) {
			return x;
		}
		return withClone(url, mirror -> {
			if (!hasCommit(mirror, shaX) || !hasCommit(mirror, shaY)) {
				fetchRefs(url, mirror);
				if (!hasCommit(mirror, shaX) || !hasCommit(mirror, shaY)) {
					return null;
				}
			}
			if (isAncestor(mirror, shaX, shaY)) {
				return y;
			}
			return isAncestor(mirror, shaY, shaX) ? x : null;
		});
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

	/** What runs against a repository's clone, holding its lock. */
	@FunctionalInterface
	private interface CloneWork<T> {

		@Nullable T run(Path mirror) throws IOException;

	}

	/**
	 * Runs work against the repository's clone -- cloned first when the cache has none --
	 * holding the repository's lock: a {@link FileLock} across processes and one monitor
	 * per lock file within this JVM.
	 */
	private <T> @Nullable T withClone(String url, CloneWork<T> work) throws IOException {
		String key = cacheKey(url);
		Path repos = this.area.resolve("repos");
		Files.createDirectories(repos);
		Path lockFile = repos.resolve(key + ".lock").toAbsolutePath().normalize();
		synchronized (JVM_LOCKS.computeIfAbsent(lockFile, path -> new Object())) {
			try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
					FileLock fileLock = channel.lock()) {
				return work.run(ensureMirror(url, repos.resolve(key)));
			}
		}
	}

	/** Clones the repository bare unless the clone exists; answers the clone. */
	private Path ensureMirror(String url, Path mirror) throws IOException {
		return AtomicInstall.installDirectory(mirror, staging -> {
			Path clone = staging.resolve("repo.git");
			this.git.run(Map.of(), List.of("clone", "--bare", "--quiet", "--", url, clone.toString()));
			return clone;
		});
	}

	/**
	 * Makes the commit present in the clone: fetches every branch and tag when it is
	 * missing, then the commit itself (a commit no ref names, where the server allows
	 * it). Answers whether the commit is there now.
	 */
	private boolean ensureCommit(String url, String sha, Path mirror) throws IOException {
		if (hasCommit(mirror, sha)) {
			return true;
		}
		fetchRefs(url, mirror);
		if (hasCommit(mirror, sha)) {
			return true;
		}
		GitCommand.Result bySha = this.git.exec(Map.of(),
				List.of("--git-dir=" + mirror, "fetch", "--quiet", "--", url, sha + ":refs/rontolisp/" + sha));
		return bySha.ok() && hasCommit(mirror, sha);
	}

	private void fetchRefs(String url, Path mirror) throws IOException {
		this.git.run(Map.of(), List.of("--git-dir=" + mirror, "fetch", "--quiet", "--", url,
				"+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*"));
	}

	private boolean hasCommit(Path mirror, String sha) throws IOException {
		return this.git.exec(Map.of(), List.of("--git-dir=" + mirror, "cat-file", "-e", sha + "^{commit}")).ok();
	}

	/** The full sha a revision names in the clone, or {@code null} for none. */
	private @Nullable String commitOf(Path mirror, String revision) throws IOException {
		GitCommand.Result result = this.git.exec(Map.of(),
				List.of("--git-dir=" + mirror, "rev-parse", "--verify", "--quiet", revision + "^{commit}"));
		return result.ok() ? result.stdout().strip() : null;
	}

	private boolean tagExists(Path mirror, String tag) throws IOException {
		return this.git
			.exec(Map.of(), List.of("--git-dir=" + mirror, "rev-parse", "--verify", "--quiet", "refs/tags/" + tag))
			.ok();
	}

	/**
	 * {@code merge-base --is-ancestor}: exit 0 is yes, 1 is no, anything else a failure.
	 */
	private boolean isAncestor(Path mirror, String ancestor, String descendant) throws IOException {
		GitCommand.Result result = this.git.exec(Map.of(),
				List.of("--git-dir=" + mirror, "merge-base", "--is-ancestor", ancestor, descendant));
		if (result.exitCode() > 1) {
			throw new IOException(
					"git cannot compare commits " + ancestor + " and " + descendant + ": " + result.stderr().strip());
		}
		return result.ok();
	}

	/**
	 * Checks that the tag names the commit, fetching the tags first when the clone's tag
	 * is missing or names another commit (a tag may have been created or moved upstream).
	 */
	private void checkTag(GitCoordinate coordinate, String tag, Path mirror) throws IOException {
		String tagged = tagCommit(mirror, tag);
		if (!coordinate.sha().equals(tagged)) {
			fetchRefs(coordinate.url(), mirror);
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
		return commitOf(mirror, "refs/tags/" + tag);
	}

	/**
	 * Writes the commit's tree into {@code staging/tree} through a private index, so the
	 * clone's own state is never touched, and answers the tree. Line endings are the
	 * committed ones whatever the user's {@code core.autocrlf}.
	 */
	private Path writeTree(Path mirror, String sha, Path staging) throws IOException {
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
