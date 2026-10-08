package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
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
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * What each coordinate type brings, read through the {@link ClojureFiles} seam, with the
 * oracle's refusals:
 * <ul>
 * <li>{@code :local/root} to a directory: its own {@code deps.edn} merged over the root
 * map, so its {@code :paths} (relative to it, {@code ["src"]} by default) and its
 * {@code :deps} -- a directory with neither a {@code deps.edn} nor a {@code pom.xml} is
 * the oracle's {@code Manifest file not found}; to a jar: the jar itself, read in place
 * as a source root.</li>
 * <li>A Maven coordinate of a built-in library ({@link ClojureBuiltinLibs}) is answered
 * by this front end; any other Maven coordinate, and every git coordinate, is not
 * fetched: it contributes no root, and a namespace no root holds is refused naming
 * it.</li>
 * <li>A jar's own {@code pom.xml} and a {@code :local/root} project with a
 * {@code pom.xml} but no {@code deps.edn} are not read, named the same way.</li>
 * </ul>
 */
final class ClojureDepsProcurer implements ClojureDepsGraph.Procurer {

	/**
	 * The oracle's inferred git URLs ({@code clojure.tools.deps.extensions.git}): a lib
	 * whose group names a forge, {@code io.github.user/repo}, is that forge's repository.
	 */
	private static final Map<Pattern, String> GIT_SERVICES = gitServices();

	private final ClojureFiles files;

	/** A {@code :local/root} directory's {@code deps.edn} merged over the root map. */
	private final Map<String, DepsMap> manifests = new HashMap<>();

	ClojureDepsProcurer(ClojureFiles files) {
		this.files = files;
	}

	private static Map<Pattern, String> gitServices() {
		Map<Pattern, String> services = new LinkedHashMap<>();
		services.put(Pattern.compile("^(?:com|io).github.([^.]+)$"), "https://github.com/%s/%s.git");
		services.put(Pattern.compile("^(?:com|io).gitlab.([^.]+)$"), "https://gitlab.com/%s/%s.git");
		services.put(Pattern.compile("^(?:org|page).codeberg.([^.]+)$"), "https://codeberg.org/%s/%s.git");
		services.put(Pattern.compile("^(?:org|io).bitbucket.([^.]+)$"), "https://bitbucket.org/%s/%s.git");
		services.put(Pattern.compile("^(?:com|io).beanstalkapp.([^.]+)$"), "https://%s.git.beanstalkapp.com/%s.git");
		services.put(Pattern.compile("^ht.sr.([^.]+)$"), "https://git.sr.ht/~%s/%s");
		return services;
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
			default -> coord; // a Maven version is taken as written
		};
	}

	/**
	 * The oracle's checks of a git coordinate that need no repository: one spelling of
	 * the commit and of the tag, a URL given or inferred, a full commit (a short one
	 * needs a tag to resolve it); answered in the oracle's standard keys.
	 */
	private static Coord canonicalGit(Lib lib, Coord coord) {
		String unsha = coord.string(":sha");
		String sha = coord.string(":git/sha");
		String untag = coord.string(":tag");
		String tag = coord.string(":git/tag");
		if (unsha != null && sha != null) {
			throw new LispReadException("git coord has both :sha and :git/sha for " + lib);
		}
		if (untag != null && tag != null) {
			throw new LispReadException("git coord has both :tag and :git/tag for " + tag);
		}
		String canonSha = sha != null ? sha : unsha;
		String canonTag = tag != null ? tag : untag;
		String url = coord.string(":git/url");
		if (url == null) {
			url = inferredGitUrl(lib);
		}
		if (url == null) {
			throw new LispReadException("Failed to infer git url for: " + lib);
		}
		if (canonSha == null) {
			throw new LispReadException("Library " + lib + " has coord with missing sha");
		}
		if (canonTag == null && canonSha.length() != 40) {
			throw new LispReadException("Library " + lib + " has prefix sha, use full sha or add tag");
		}
		SequencedMap<String, LispVal> entries = new LinkedHashMap<>(coord.entries());
		entries.put(":git/url", LispString.literal(url));
		entries.put(":git/sha", LispString.literal(canonSha));
		if (canonTag != null) {
			entries.put(":git/tag", LispString.literal(canonTag));
		}
		entries.remove(":sha");
		entries.remove(":tag");
		return new Coord(entries);
	}

	private static @Nullable String inferredGitUrl(Lib lib) {
		for (Map.Entry<Pattern, String> service : GIT_SERVICES.entrySet()) {
			Matcher match = service.getKey().matcher(lib.ns());
			if (match.matches()) {
				return String.format(service.getValue(), match.group(1), lib.name());
			}
		}
		return null;
	}

	@Override
	public Coord manifest(Lib lib, Coord coord) {
		return switch (coord.type()) {
			case "mvn" -> coord.with(":deps/manifest", new LispSymbol(":mvn"));
			case "local" -> localManifest(lib, coord);
			default -> coord; // git: the manifest is in the commit, which is not fetched
		};
	}

	/**
	 * A local root's manifest: the one the coordinate names, a jar for a file, else the
	 * directory's {@code deps.edn}, else its {@code pom.xml}, else none.
	 */
	private Coord localManifest(Lib lib, Coord coord) {
		String root = String.valueOf(coord.string(":local/root"));
		LispVal named = coord.get(":deps/manifest");
		LispVal manifest;
		if (named != null) {
			manifest = named;
		}
		else if (!this.files.isDirectory(root)) {
			if (!this.files.exists(root)) {
				throw new LispReadException("Local lib " + lib + " not found: " + root);
			}
			manifest = new LispSymbol(":jar");
		}
		else if (isFile(this.files.resolve(root, "deps.edn"))) {
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
		if (!useCoord.type().equals("local")) {
			return List.of(); // built in, or not fetched
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
			default -> List.of(); // a jar's or a pom project's pom.xml is not read
		};
	}

	@Override
	public Contribution contribution(Lib lib, Coord useCoord) {
		return switch (useCoord.type()) {
			case "mvn" -> ClojureBuiltinLibs.isBuiltin(lib) ? new Contribution(List.of(), true, null)
					: new Contribution(List.of(), false,
							lib + " " + useCoord.string(":mvn/version") + " (a Maven coordinate, not fetched)");
			case "git" -> new Contribution(List.of(), false, lib + " " + useCoord.string(":git/url") + " at "
					+ gitVersion(useCoord) + " (a git coordinate, not fetched)");
			default -> localContribution(lib, useCoord);
		};
	}

	private static String gitVersion(Coord coord) {
		String tag = coord.string(":git/tag");
		String sha = String.valueOf(coord.string(":git/sha"));
		return tag != null ? tag : sha.substring(0, Math.min(7, sha.length()));
	}

	private Contribution localContribution(Lib lib, Coord useCoord) {
		String root = String.valueOf(useCoord.string(":local/root"));
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
				List<String> entries = this.files.archiveEntries(root);
				if (entries == null) {
					throw new LispReadException("Local lib " + lib + " is not a readable jar: " + root);
				}
				boolean pom = entries.stream()
					.anyMatch(name -> name.startsWith("META-INF/") && name.endsWith("pom.xml"));
				yield new Contribution(List.of(new Root(root, true)), false, pom
						? "the dependencies the pom.xml in " + root + " declares (a jar's pom.xml is not read)" : null);
			}
			default -> new Contribution(List.of(), false, lib + " " + root + " (a pom.xml project, not read)");
		};
	}

	/**
	 * A local coordinate's manifest type, refused in the oracle's words when there is
	 * none or it names one the oracle has no reader for.
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
	 * over the root map, like the oracle's {@code deps-map} -- the user-level map is not
	 * merged into a dependency's.
	 */
	private DepsMap depsMapOf(Coord useCoord) {
		String root = String.valueOf(useCoord.string(":local/root"));
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
		if (!useCoord.type().equals("local") || !":deps".equals(manifestOf(lib, useCoord))) {
			return false;
		}
		ClojureDepsEdn.PrepLib prep = depsMapOf(useCoord).prepLib();
		return prep != null && !this.files
			.exists(this.files.resolve(String.valueOf(useCoord.string(":local/root")), prep.ensure()));
	}

	@Override
	public int compareGit(Lib lib, Coord x, Coord y) {
		// telling which commit descends from which needs the repository, and git
		// coordinates are not fetched: the version selected first stays
		return 0;
	}

}
