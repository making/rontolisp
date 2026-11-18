package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.clojure.ClojureDepsEdn.Coord;
import am.ik.rontolisp.clojure.ClojureDepsEdn.DepsMap;
import am.ik.rontolisp.clojure.ClojureDepsEdn.Lib;
import am.ik.rontolisp.clojure.ClojureDepsEdn.PathRef;
import am.ik.rontolisp.clojure.ClojureDepsGraph.Contribution;
import am.ik.rontolisp.clojure.ClojureDepsGraph.Dep;
import am.ik.rontolisp.clojure.ClojureDepsGraph.Root;
import am.ik.rontolisp.clojure.ClojureRepositories.FetchFailure;
import am.ik.rontolisp.clojure.ClojureRepositories.MavenArtifact;
import am.ik.rontolisp.clojure.ClojureRepositories.MavenDependency;
import am.ik.rontolisp.clojure.ClojureRepositories.MavenSource;
import am.ik.rontolisp.clojure.ClojureRepositories.PomConfiguration;
import am.ik.rontolisp.clojure.ClojureRepositories.PomExecution;
import am.ik.rontolisp.clojure.ClojureRepositories.PomPlugin;
import am.ik.rontolisp.clojure.ClojureRepositories.PomProject;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * What each coordinate type brings, read through the {@link ClojureFiles} seam, as the
 * deps.edn reference states it and, where it is silent, as {@code clj} 1.12.6 answers
 * (measured 2026-10-08 and 2026-11-18), with the oracle's refusals:
 * <ul>
 * <li>{@code :local/root} to a directory: its own {@code deps.edn} merged over the root
 * map, so its {@code :paths} (relative to it, {@code ["src"]} by default) and its
 * {@code :deps} -- a directory with neither a {@code deps.edn} nor a {@code pom.xml} is
 * the oracle's {@code Manifest file not found}; to a jar: the jar itself, read in place
 * as a source root, and the dependencies its own {@code pom.xml} declares.</li>
 * <li>{@code :mvn/version}: the dependencies its POM declares (compile and runtime ones,
 * not optional) and its jar, through the repositories; a built-in library
 * ({@link ClojureBuiltinLibs}) is answered by this front end and fetches nothing, and the
 * built-in clojure's own dependencies are read only when a lookup first asks for one of
 * their namespaces ({@link ClojureDepsGraph.Deferred}).</li>
 * <li>{@code :git/url}: the commit checked out, its tag and abbreviated sha checked and
 * resolved against the repository, its {@code deps.edn} read like a local root's; of two
 * commits the descendant is the newer.</li>
 * <li>A {@code pom.xml} project (a directory or a commit with a {@code pom.xml} and no
 * {@code deps.edn}): its effective model's compile and runtime dependencies, and its
 * build's source directory, {@code src/main/clojure}, resource directories and
 * {@code build-helper-maven-plugin} directories as roots.</li>
 * </ul>
 * Where the host fetches nothing ({@link ClojureFiles#repositories()} is null: an
 * embedder, a test, the browser playground), a Maven or git coordinate contributes no
 * root and no dependency, and neither a jar's {@code pom.xml} nor a {@code pom.xml}
 * project is read: each is named when a lookup misses.
 */
final class ClojureDepsProcurer implements ClojureDepsGraph.Procurer {

	/**
	 * The forges a git library's name points at when its coordinate gives no
	 * {@code :git/url}: the deps.edn reference's table.
	 */
	private static final List<Forge> FORGES = List.of(
			new Forge("github", "https://github.com/%s/%s.git", List.of("io", "com")),
			new Forge("gitlab", "https://gitlab.com/%s/%s.git", List.of("io", "com")),
			new Forge("bitbucket", "https://bitbucket.org/%s/%s.git", List.of("io", "org")),
			new Forge("beanstalkapp", "https://%s.git.beanstalkapp.com/%s.git", List.of("io", "com")),
			new Forge("sr", "https://git.sr.ht/~%s/%s", List.of("ht")),
			new Forge("codeberg", "https://codeberg.org/%s/%s.git", List.of("org", "page")));

	/** A Maven version naming exactly one version, {@code [1.0]}. */
	private static final Pattern SPECIFIC_VERSION = Pattern.compile("^\\[([^,]*)]$");

	/** The scopes a Maven dependency brings onto the classpath. */
	private static final Set<String> CLASSPATH_SCOPES = Set.of("compile", "runtime");

	private final ClojureFiles files;

	private final @Nullable ClojureRepositories repositories;

	private final Supplier<MavenSource> mavenSourceOf;

	private @Nullable MavenSource mavenSource;

	/** A {@code :local/root} directory's {@code deps.edn} merged over the root map. */
	private final Map<String, DepsMap> manifests = new HashMap<>();

	/** Each git revision's commit asked once per resolution: {@code url revision}. */
	private final Map<String, Optional<String>> commits = new HashMap<>();

	/** Each git tag's existence asked once per resolution: {@code url tag}. */
	private final Map<String, Boolean> tags = new HashMap<>();

	/** Each {@code pom.xml} project's model, by its file. */
	private final Map<String, PomProject> pomProjects = new HashMap<>();

	/**
	 * A procurer reading the files, fetching from the root map's Maven repositories.
	 * @param files where coordinates are read and fetched through
	 */
	ClojureDepsProcurer(ClojureFiles files) {
		this(files, () -> ClojureDepsEdn.mavenSource(ClojureDepsEdn.ROOT, files, null));
	}

	/**
	 * A procurer reading the files, fetching Maven coordinates from the repositories the
	 * project's merged map names.
	 * @param files where coordinates are read and fetched through
	 * @param mavenSource the project's Maven repositories, asked for on the first fetch
	 */
	ClojureDepsProcurer(ClojureFiles files, Supplier<MavenSource> mavenSource) {
		this.files = files;
		this.repositories = files.repositories();
		this.mavenSourceOf = mavenSource;
	}

	@Override
	public Coord canonicalize(Lib lib, Coord coord, @Nullable String dir) {
		return switch (coord.type()) {
			case "local" -> {
				String root = this.files
					.canonical(this.files.resolve(dir, String.valueOf(coord.string(":local/root"))));
				if (!this.files.exists(root)) {
					throw new LispReadException("Local lib " + lib + " not found: " + root);
				}
				yield coord.with(":local/root", LispString.literal(root));
			}
			case "git" -> canonicalGit(lib, coord);
			default -> canonicalMaven(lib, coord);
		};
	}

	/**
	 * A Maven coordinate made canonical: {@code [1.0]} is {@code 1.0}; a range,
	 * {@code RELEASE} and {@code LATEST} resolve against the repositories (where nothing
	 * is fetched, they stay as written, like the coordinate); any other version is taken
	 * as written.
	 */
	private Coord canonicalMaven(Lib lib, Coord coord) {
		String version = String.valueOf(coord.string(":mvn/version"));
		Matcher specific = SPECIFIC_VERSION.matcher(version);
		boolean resolved = version.equals("RELEASE") || version.equals("LATEST");
		if (!resolved && specific.matches()) {
			return coord.with(":mvn/version", LispString.literal(specific.group(1)));
		}
		ClojureRepositories fetcher = this.repositories;
		if (fetcher == null || !(resolved || version.contains("[") || version.contains("("))) {
			return coord;
		}
		String concrete = fetch(() -> fetcher.mavenVersion(mavenSource(), mavenArtifact(lib, coord)));
		return coord.with(":mvn/version", LispString.literal(concrete));
	}

	/**
	 * A git coordinate made canonical. The deps.edn reference states the rules: a
	 * {@code :git/url} given or inferred from the library's name ({@link #FORGES}), a
	 * {@code :git/sha} required, a short one only beside a {@code :git/tag}. The rest is
	 * what {@code clj} 1.12.6 answered over a {@code file:} repository (2026-11-18): the
	 * older spellings {@code :sha} and {@code :tag} are taken but not beside the
	 * {@code :git/} ones, and a coordinate is refused at the first of a sha spelled
	 * twice, a tag spelled twice (named by its {@code :git/tag}), no URL, a tag the
	 * repository lacks, no sha, a short sha without a tag, and a sha and a tag that do
	 * not name one commit (also when the short sha names none). A short sha with its tag
	 * becomes the full one. The answer keeps the coordinate's other keys where they were,
	 * a {@code :git/tag} written {@code :tag} moving last. Where nothing is fetched, the
	 * checks that ask the repository are left out.
	 */
	private Coord canonicalGit(Lib lib, Coord coord) {
		String sha = spelledOnce(coord, ":git/sha", ":sha", "git coord has both :sha and :git/sha for " + lib);
		String tag = spelledOnce(coord, ":git/tag", ":tag",
				"git coord has both :tag and :git/tag for " + coord.string(":git/tag"));
		String url = coord.string(":git/url");
		if (url == null) {
			url = inferredGitUrl(lib);
		}
		if (url == null) {
			throw new LispReadException("Failed to infer git url for: " + lib);
		}
		ClojureRepositories fetcher = this.repositories;
		if (tag != null && fetcher != null && !hasTag(fetcher, url, tag)) {
			throw new LispReadException("Library " + lib + " has invalid tag: " + tag);
		}
		if (sha == null) {
			throw new LispReadException("Library " + lib + " has coord with missing sha");
		}
		boolean full = sha.length() == 40;
		if (tag == null && !full) {
			throw new LispReadException("Library " + lib + " has prefix sha, use full sha or add tag");
		}
		if (tag != null && fetcher != null) {
			String named = commit(fetcher, url, sha);
			if (named == null || !named.equals(commit(fetcher, url, tag))) {
				throw new LispReadException("Library " + lib + " has sha and tag that point to different commits");
			}
			sha = full ? sha : named;
		}
		SequencedMap<String, LispVal> entries = new LinkedHashMap<>(coord.entries());
		entries.put(":git/url", LispString.literal(url));
		entries.put(":git/sha", LispString.literal(sha));
		if (tag != null) {
			entries.put(":git/tag", LispString.literal(tag));
		}
		entries.keySet().removeAll(List.of(":sha", ":tag"));
		return new Coord(entries);
	}

	/**
	 * A key's string under its current spelling or its older one, refused when both are
	 * written.
	 */
	private static @Nullable String spelledOnce(Coord coord, String key, String older, String refusal) {
		String current = coord.string(key);
		String old = coord.string(older);
		if (current != null && old != null) {
			throw new LispReadException(refusal);
		}
		return current != null ? current : old;
	}

	private boolean hasTag(ClojureRepositories fetcher, String url, String tag) {
		return this.tags.computeIfAbsent(url + " " + tag, key -> fetch(() -> fetcher.gitTag(url, tag)));
	}

	private @Nullable String commit(ClojureRepositories fetcher, String url, String revision) {
		return this.commits
			.computeIfAbsent(url + " " + revision,
					key -> Optional.ofNullable(fetchNullable(() -> fetcher.gitCommit(url, revision))))
			.orElse(null);
	}

	/**
	 * The repository a library's name points at: its group is a forge's two words and the
	 * owner, its name the repository. {@code clj} 1.12.6 matched any character between
	 * the three parts ({@code ioXgithubYfoo/bar} is {@code github.com/foo/bar}), the
	 * words case for case, an owner of any characters but a dot (2026-11-18).
	 */
	private static @Nullable String inferredGitUrl(Lib lib) {
		String group = lib.ns();
		for (Forge forge : FORGES) {
			for (String top : forge.tops()) {
				int owner = top.length() + 1 + forge.word().length() + 1;
				if (group.length() > owner && group.startsWith(top) && group.startsWith(forge.word(), top.length() + 1)
						&& group.indexOf('.', owner) < 0) {
					return String.format(forge.url(), group.substring(owner), lib.name());
				}
			}
		}
		return null;
	}

	/**
	 * One row of the reference's table of forges.
	 *
	 * @param word the forge's word, the second of the group
	 * @param url the repository's URL, formatted with the owner and the library's name
	 * @param tops the words the group may start with
	 */
	private record Forge(String word, String url, List<String> tops) {
	}

	@Override
	public Coord manifest(Lib lib, Coord coord) {
		return switch (coord.type()) {
			case "mvn" -> coord.with(":deps/manifest", new LispSymbol(":mvn"));
			case "local" -> localManifest(lib, coord);
			default -> gitManifest(lib, coord);
		};
	}

	/**
	 * A local root's manifest: the one the coordinate names, a jar for a file, else the
	 * directory's {@code deps.edn}, else its {@code pom.xml}, else none.
	 */
	private Coord localManifest(Lib lib, Coord coord) {
		String root = String.valueOf(coord.string(":local/root"));
		LispVal named = coord.get(":deps/manifest");
		if (named != null) {
			return coord.with(":deps/root", LispString.literal(root));
		}
		if (!this.files.isDirectory(root)) {
			if (!this.files.exists(root)) {
				throw new LispReadException("Local lib " + lib + " not found: " + root);
			}
			return coord.with(":deps/manifest", new LispSymbol(":jar")).with(":deps/root", LispString.literal(root));
		}
		return detectedManifest(coord, root);
	}

	/**
	 * A git coordinate's manifest: the commit checked out, its {@code :deps/root} below
	 * the checkout (an absolute one as it stands), then the manifest the coordinate names
	 * or the one found there. Where nothing is fetched, the coordinate as it stands.
	 */
	private Coord gitManifest(Lib lib, Coord coord) {
		ClojureRepositories fetcher = this.repositories;
		if (fetcher == null) {
			return coord;
		}
		String url = String.valueOf(coord.string(":git/url"));
		String sha = String.valueOf(coord.string(":git/sha"));
		String checkout = fetchNullable(() -> fetcher.gitCheckout(url, sha));
		if (checkout == null) {
			throw new LispReadException("Commit not found for " + lib + " in repo " + url + " at " + sha);
		}
		String sub = coord.string(":deps/root");
		String root = sub == null ? checkout : this.files.canonical(this.files.resolve(checkout, sub));
		if (coord.get(":deps/manifest") != null) {
			return coord.with(":deps/root", LispString.literal(root));
		}
		return detectedManifest(coord, root);
	}

	/**
	 * The manifest a directory holds: its {@code deps.edn}, else its {@code pom.xml} (the
	 * reference: {@code deps.edn} is preferred), else the coordinate as it stands (none).
	 */
	private Coord detectedManifest(Coord coord, String root) {
		LispVal manifest;
		if (isFile(this.files.resolve(root, "deps.edn"))) {
			manifest = new LispSymbol(":deps");
		}
		else if (isFile(this.files.resolve(root, "pom.xml"))) {
			manifest = new LispSymbol(":pom");
		}
		else {
			return coord;
		}
		return coord.with(":deps/manifest", manifest).with(":deps/root", LispString.literal(root));
	}

	private boolean isFile(String path) {
		return this.files.exists(path) && !this.files.isDirectory(path);
	}

	@Override
	public List<Dep> children(Lib lib, Coord useCoord) {
		String type = useCoord.type();
		if (type.equals("mvn")) {
			if (ClojureBuiltinLibs.isBuiltin(lib)) {
				// known without a fetch: the built-in clojure's own dependencies
				return ClojureBuiltinLibs.children(lib);
			}
			ClojureRepositories fetcher = this.repositories;
			return fetcher == null || ClojureBuiltinLibs.deferredNamespaces(lib, useCoord) != null ? List.of()
					: mavenChildren(fetcher, lib, useCoord);
		}
		if (type.equals("git") && this.repositories == null) {
			return List.of(); // not fetched
		}
		return switch (manifestOf(lib, useCoord)) {
			case ":deps" -> {
				List<Dep> out = new ArrayList<>();
				SequencedMap<Lib, @Nullable Coord> deps = depsMapOf(useCoord).deps();
				if (deps != null) {
					for (Map.Entry<Lib, @Nullable Coord> dep : deps.entrySet()) {
						out.add(new Dep(dep.getKey(), dep.getValue()));
					}
				}
				yield out;
			}
			case ":jar" -> jarChildren(lib, useCoord);
			default -> {
				PomProject project = pomProject(useCoord);
				yield project == null ? List.of() : modelChildren(project.dependencies());
			}
		};
	}

	/**
	 * A Maven coordinate's dependencies: the descriptor's compile and runtime
	 * dependencies that are not optional, each lib {@code group/artifact$classifier}, its
	 * coordinate the version, the extension when it is no jar, and the exclusions.
	 */
	private List<Dep> mavenChildren(ClojureRepositories fetcher, Lib lib, Coord useCoord) {
		MavenArtifact artifact = mavenArtifact(lib, useCoord);
		List<MavenDependency> dependencies = fetch(() -> fetcher.mavenDependencies(mavenSource(), artifact));
		List<Dep> out = new ArrayList<>();
		for (MavenDependency dependency : dependencies) {
			if (!CLASSPATH_SCOPES.contains(dependency.scope()) || dependency.optional()) {
				continue;
			}
			SequencedMap<String, LispVal> entries = new LinkedHashMap<>();
			entries.put(":mvn/version", LispString.literal(dependency.artifact().version()));
			if (!dependency.artifact().extension().equals("jar")) {
				entries.put(":extension", LispString.literal(dependency.artifact().extension()));
			}
			putExclusions(entries, dependency);
			out.add(new Dep(libOf(dependency.artifact()), new Coord(entries)));
		}
		return out;
	}

	/**
	 * A local jar's dependencies: the ones the jar's own {@code pom.xml} declares (its
	 * first {@code META-INF/.../pom.xml}), read like a project model -- compile and
	 * runtime ones, optional ones too, each classifier the one written and no extension
	 * -- or none without a {@code pom.xml}, or where nothing is fetched.
	 */
	private List<Dep> jarChildren(Lib lib, Coord useCoord) {
		ClojureRepositories fetcher = this.repositories;
		String jar = String.valueOf(useCoord.string(":local/root"));
		String pom = fetcher == null ? null : jarPom(jar);
		if (fetcher == null || pom == null) {
			return List.of();
		}
		String text = this.files.readArchiveEntry(jar, pom);
		if (text == null) {
			throw new LispReadException("Local lib " + lib + " is not a readable jar: " + jar);
		}
		return modelChildren(
				fetch(() -> fetcher.pomDependencies(mavenSource(), text), "the " + pom + " in " + jar + ": "));
	}

	/**
	 * A POM's model read as a project's dependencies: the compile and runtime
	 * dependencies, optional ones too, each coordinate its version, scope,
	 * {@code :optional} and exclusions, each classifier the one written and no extension.
	 */
	private static List<Dep> modelChildren(List<MavenDependency> dependencies) {
		List<Dep> out = new ArrayList<>();
		for (MavenDependency dependency : dependencies) {
			if (!CLASSPATH_SCOPES.contains(dependency.scope())) {
				continue;
			}
			SequencedMap<String, LispVal> entries = new LinkedHashMap<>();
			entries.put(":mvn/version", LispString.literal(dependency.artifact().version()));
			entries.put(":scope", LispString.literal(dependency.scope()));
			if (dependency.optional()) {
				entries.put(":optional", new LispSymbol("true"));
			}
			putExclusions(entries, dependency);
			out.add(new Dep(libOf(dependency.artifact()), new Coord(entries)));
		}
		return out;
	}

	/**
	 * The first entry of a jar's central directory that is a {@code pom.xml} below
	 * {@code META-INF/}; {@code null} without one.
	 */
	private @Nullable String jarPom(String jar) {
		List<String> entries = this.files.archiveEntries(jar);
		if (entries == null) {
			return null;
		}
		for (String entry : entries) {
			if (entry.startsWith("META-INF/") && entry.endsWith("pom.xml")) {
				return entry;
			}
		}
		return null;
	}

	/** {@code :exclusions} as the oracle's set of {@code group/artifact} symbols. */
	private static void putExclusions(SequencedMap<String, LispVal> entries, MavenDependency dependency) {
		if (dependency.exclusions().isEmpty()) {
			return;
		}
		List<LispVal> items = new ArrayList<>();
		items.add(new LispSymbol("%hash-set"));
		for (String exclusion : dependency.exclusions()) {
			LispSymbol symbol = new LispSymbol(exclusion);
			if (!items.contains(symbol)) {
				items.add(symbol);
			}
		}
		entries.put(":exclusions", ClojureLowerUtil.list(items));
	}

	/** The lib a Maven artifact is: {@code group/artifact}, {@code $classifier} added. */
	private static Lib libOf(MavenArtifact artifact) {
		return new Lib(artifact.groupId(), artifact.classifier().isEmpty() ? artifact.artifactId()
				: artifact.artifactId() + "$" + artifact.classifier());
	}

	/**
	 * The Maven artifact a coordinate names: the lib's group and artifact, its
	 * {@code $classifier}, the {@code :extension} (a jar by default); a
	 * {@code :classifier} key is the oracle's refusal.
	 */
	private static MavenArtifact mavenArtifact(Lib lib, Coord coord) {
		if (coord.get(":classifier") != null) {
			SequencedMap<String, LispVal> shown = new LinkedHashMap<>(coord.entries());
			shown.remove(":deps/manifest");
			throw new LispReadException("Invalid library spec:\n  " + lib + " " + new Coord(shown).print() + "\n"
					+ ":classifier in Maven coordinates is no longer supported.\n"
					+ "Use groupId/artifactId$classifier in lib names instead.");
		}
		String name = lib.name();
		int dollar = name.indexOf('$');
		String extension = coord.string(":extension");
		return new MavenArtifact(lib.ns(), dollar < 0 ? name : name.substring(0, dollar),
				dollar < 0 ? "" : name.substring(dollar + 1), extension == null ? "jar" : extension,
				String.valueOf(coord.string(":mvn/version")));
	}

	private MavenSource mavenSource() {
		MavenSource known = this.mavenSource;
		if (known == null) {
			known = this.mavenSourceOf.get();
			this.mavenSource = known;
		}
		return known;
	}

	@Override
	public Contribution contribution(Lib lib, Coord useCoord) {
		return switch (useCoord.type()) {
			case "mvn" -> mavenContribution(lib, useCoord);
			case "git" -> this.repositories == null
					? new Contribution(List.of(), false, lib + " " + useCoord.string(":git/url") + " at "
							+ gitVersion(useCoord) + " (a git coordinate, not fetched)")
					: manifestContribution(lib, useCoord);
			default -> manifestContribution(lib, useCoord);
		};
	}

	/**
	 * A Maven coordinate's jar (nothing for another extension), built in or unfetched as
	 * the host decides; a dependency of the built-in clojure read when a lookup first
	 * asks for one of its namespaces.
	 */
	private Contribution mavenContribution(Lib lib, Coord useCoord) {
		if (ClojureBuiltinLibs.isBuiltin(lib)) {
			return new Contribution(List.of(), true, null);
		}
		Set<String> deferred = ClojureBuiltinLibs.deferredNamespaces(lib, useCoord);
		if (deferred != null) {
			return new Contribution(List.of(), false, null, null,
					new ClojureDepsGraph.Deferred(deferred, () -> fetchedMavenContribution(lib, useCoord)));
		}
		return fetchedMavenContribution(lib, useCoord);
	}

	/** A Maven coordinate's jar, fetched where the host fetches, else named. */
	private Contribution fetchedMavenContribution(Lib lib, Coord useCoord) {
		ClojureRepositories fetcher = this.repositories;
		if (fetcher == null) {
			return new Contribution(List.of(), false,
					lib + " " + useCoord.string(":mvn/version") + " (a Maven coordinate, not fetched)");
		}
		MavenArtifact artifact = mavenArtifact(lib, useCoord);
		if (!artifact.extension().equals("jar")) {
			return new Contribution(List.of(), false, null);
		}
		String jar = fetch(() -> fetcher.mavenArtifact(mavenSource(), artifact));
		String coordinate = artifact.groupId() + ":" + artifact.artifactId()
				+ (artifact.classifier().isEmpty() ? "" : ":jar:" + artifact.classifier()) + ":" + artifact.version();
		return new Contribution(List.of(new Root(jar, true)), false, null, coordinate);
	}

	private static String gitVersion(Coord coord) {
		String tag = coord.string(":git/tag");
		String sha = String.valueOf(coord.string(":git/sha"));
		return tag != null ? tag : sha.substring(0, Math.min(7, sha.length()));
	}

	/**
	 * What a local root or a checked-out commit contributes by its manifest: a
	 * {@code deps.edn}'s {@code :paths} below its root, a jar itself, a {@code pom.xml}
	 * project's source directories (nothing, named, where nothing is fetched).
	 */
	private Contribution manifestContribution(Lib lib, Coord useCoord) {
		String root = String.valueOf(useCoord.string(":deps/root"));
		return switch (manifestOf(lib, useCoord)) {
			case ":deps" -> {
				List<Root> roots = new ArrayList<>();
				List<PathRef> paths = depsMapOf(useCoord).paths();
				for (PathRef path : paths == null ? List.<PathRef>of() : paths) {
					if (path.alias()) {
						throw new LispReadException("the :paths of " + lib + " (" + root + ") name the alias "
								+ path.value() + ": only a project's own :paths resolve an alias");
					}
					roots.add(new Root(this.files.canonical(this.files.resolve(root, path.value())), false));
				}
				yield new Contribution(roots, false, null);
			}
			case ":jar" -> {
				String jar = String.valueOf(useCoord.string(":local/root"));
				if (this.files.archiveEntries(jar) == null) {
					throw new LispReadException("Local lib " + lib + " is not a readable jar: " + jar);
				}
				yield new Contribution(List.of(new Root(jar, true)), false,
						this.repositories == null && jarPom(jar) != null
								? "the dependencies the pom.xml in " + jar + " declares (a jar's pom.xml is not read)"
								: null);
			}
			default -> {
				PomProject project = pomProject(useCoord);
				yield project == null
						? new Contribution(List.of(), false, lib + " " + root + " (a pom.xml project, not read)")
						: new Contribution(pomRoots(root, project), false, null);
			}
		};
	}

	/**
	 * A {@code pom.xml} project's roots: the source directory, {@code src/main/clojure},
	 * the resources' directories, then the {@code build-helper-maven-plugin} directories,
	 * each made canonical against the root (absent ones too), each once.
	 */
	private List<Root> pomRoots(String root, PomProject project) {
		List<@Nullable String> sources = new ArrayList<>();
		sources.add(project.sourceDirectory());
		sources.add("src/main/clojure");
		sources.addAll(project.resourceDirectories());
		sources.addAll(buildHelperPaths(project.plugins()));
		Set<String> seen = new LinkedHashSet<>();
		for (String source : sources) {
			if (source != null) {
				seen.add(this.files.canonical(this.files.resolve(root, source)));
			}
		}
		List<Root> roots = new ArrayList<>();
		for (String path : seen) {
			roots.add(new Root(path, false));
		}
		return roots;
	}

	/**
	 * The {@code build-helper-maven-plugin} directories: when any plugin is that one, the
	 * {@code add-source} executions' sources then the {@code add-resource} executions'
	 * resources -- read off the FIRST plugin, whichever it is, as {@code clj} 1.12.6
	 * answers ({@code .kb/clojure-frontend.md}, "deps.edn": a child plugin listed ahead
	 * of the helper it shares with its parent leaves no helper directory).
	 */
	private static List<@Nullable String> buildHelperPaths(List<PomPlugin> plugins) {
		boolean helper = false;
		for (PomPlugin plugin : plugins) {
			helper |= "org.codehaus.mojo".equals(plugin.groupId())
					&& "build-helper-maven-plugin".equals(plugin.artifactId());
		}
		if (!helper) {
			return List.of();
		}
		List<@Nullable String> paths = new ArrayList<>();
		addConfigured(paths, plugins.get(0), "add-source", "sources");
		addConfigured(paths, plugins.get(0), "add-resource", "resources");
		return paths;
	}

	/** Each value below the named element of every execution with that goal. */
	private static void addConfigured(List<@Nullable String> paths, PomPlugin plugin, String goal, String element) {
		for (PomExecution execution : plugin.executions()) {
			PomConfiguration configuration = execution.configuration();
			PomConfiguration list = configuration == null ? null : configuration.child(element);
			if (execution.goals().contains(goal) && list != null) {
				for (PomConfiguration child : list.children()) {
					paths.add(child.value());
				}
			}
		}
	}

	/**
	 * A {@code pom.xml} project's model, read once per resolution; {@code null} where
	 * nothing is fetched.
	 */
	private @Nullable PomProject pomProject(Coord useCoord) {
		ClojureRepositories fetcher = this.repositories;
		if (fetcher == null) {
			return null;
		}
		String pom = this.files.resolve(String.valueOf(useCoord.string(":deps/root")), "pom.xml");
		return this.pomProjects.computeIfAbsent(pom,
				key -> fetch(() -> fetcher.pomProject(mavenSource(), key), key + ": "));
	}

	/**
	 * A coordinate's manifest type, refused in the oracle's words when there is none or
	 * it names one the oracle has no reader for.
	 */
	private static String manifestOf(Lib lib, Coord useCoord) {
		LispVal manifest = useCoord.get(":deps/manifest");
		if (manifest == null) {
			throw new LispReadException("Manifest file not found for " + lib + " in coordinate " + useCoord.print());
		}
		String type = manifest instanceof LispSymbol keyword ? keyword.name() : ClojureEdn.print(manifest);
		if (!type.equals(":deps") && !type.equals(":jar") && !type.equals(":pom")) {
			throw new LispReadException(
					"Manifest type " + type + " not loaded for " + lib + " in coordinate " + useCoord.print());
		}
		return type;
	}

	/**
	 * A {@code :deps} library's map: its {@code deps.edn} (none is an empty one) merged
	 * over the root map -- the user-level map is not merged into a dependency's, as
	 * {@code clj} answers.
	 */
	private DepsMap depsMapOf(Coord useCoord) {
		String root = String.valueOf(useCoord.string(":deps/root"));
		DepsMap known = this.manifests.get(root);
		if (known == null) {
			String file = this.files.resolve(root, "deps.edn");
			String text = this.files.read(file);
			DepsMap own = text == null ? ClojureDepsEdn.EMPTY : ClojureDepsEdn.read(text, file);
			known = ClojureDepsEdn.merge(List.of(ClojureDepsEdn.ROOT, own));
			this.manifests.put(root, known);
		}
		return known;
	}

	@Override
	public boolean unprepped(Lib lib, Coord useCoord) {
		if (useCoord.type().equals("mvn") || (useCoord.type().equals("git") && this.repositories == null)
				|| !":deps".equals(manifestOf(lib, useCoord))) {
			return false;
		}
		ClojureDepsEdn.PrepLib prep = depsMapOf(useCoord).prepLib();
		return prep != null
				&& !this.files.exists(this.files.resolve(String.valueOf(useCoord.string(":deps/root")), prep.ensure()));
	}

	/**
	 * Of two commits of a git library the descendant is the newer -- asked of both
	 * repositories when the URLs differ -- and two commits neither of which descends from
	 * the other are refused. Where nothing is fetched, the version selected first stays.
	 */
	@Override
	public int compareGit(Lib lib, Coord x, Coord y) {
		ClojureRepositories fetcher = this.repositories;
		if (fetcher == null) {
			return 0;
		}
		String urlX = String.valueOf(x.string(":git/url"));
		String shaX = String.valueOf(x.string(":git/sha"));
		String urlY = String.valueOf(y.string(":git/url"));
		String shaY = String.valueOf(y.string(":git/sha"));
		String descendant = fetchNullable(() -> fetcher.gitDescendant(urlX, shaX, shaY));
		if (!urlX.equals(urlY) && descendant != null) {
			descendant = fetchNullable(() -> fetcher.gitDescendant(urlY, shaX, shaY));
		}
		if (descendant == null) {
			throw new LispReadException("No known ancestor relationship between git versions for " + lib + "\n  " + urlX
					+ " at " + shaX + "\n  " + urlY + " at " + shaY);
		}
		return descendant.equals(shaX) ? 1 : -1;
	}

	/** A fetch, its failure a refusal naming what could not be fetched. */
	private static <T> T fetch(Supplier<T> fetch) {
		return fetch(fetch, "");
	}

	private static <T> T fetch(Supplier<T> fetch, String what) {
		try {
			return fetch.get();
		}
		catch (FetchFailure ex) {
			throw new LispReadException(what + ex.getMessage());
		}
	}

	private static <T> @Nullable T fetchNullable(Supplier<@Nullable T> fetch) {
		try {
			return fetch.get();
		}
		catch (FetchFailure ex) {
			throw new LispReadException(String.valueOf(ex.getMessage()));
		}
	}

}
