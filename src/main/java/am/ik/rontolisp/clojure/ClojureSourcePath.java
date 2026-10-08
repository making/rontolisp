package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.clojure.ClojureDepsEdn.Lib;
import am.ik.rontolisp.clojure.ClojureDepsGraph.Root;
import am.ik.rontolisp.clojure.ClojureDepsGraph.Selected;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Where a project namespace's file is found: the source roots of the program a lowering
 * reads, and the file a namespace name maps to below them. The roots are, in order, the
 * directory the entry file's own namespace names (its own directory for a file without
 * one, the working directory for a read without a file), then the selected aliases'
 * {@code :extra-paths} and the project's {@code :paths}, then the roots of every library
 * its {@code :deps} select, in the oracle's source path order ({@link ClojureDepsGraph}),
 * then the built-in namespaces. The project map is the oracle's root map, the user-level
 * {@code deps.edn} and the nearest {@code deps.edn} at or above the entry file's
 * directory, merged in that order with the aliases the files name applied
 * ({@link ClojureBasis}); with no {@code deps.edn} anywhere, the working directory is the
 * project's ({@code src} below it). Computed once, when a lowering starts -- a program's
 * dependencies resolve before it lowers, as the oracle's classpath is built before its
 * program starts -- and then each selected jar that holds classes joins the program's
 * Java class path ({@link ClojureFiles#addJavaClassPath}), so the lowering and the
 * program see them. The {@code data_readers} files the roots hold are read when the
 * project resolves too ({@link #dataReaders}).
 */
final class ClojureSourcePath {

	/**
	 * One namespace file found.
	 *
	 * @param path the path it was read from, for positions ({@code lib.jar!/a/b.clj} in a
	 * jar)
	 * @param resource its path below its root ({@code my_app/core.cljc}), the oracle's
	 * {@code *file*} while it loads
	 * @param text its contents
	 * @param builtin whether it is a built-in namespace's file
	 * ({@link ClojureBuiltinNamespaces}) rather than the project's
	 */
	record Found(String path, String resource, String text, boolean builtin) {
	}

	private final ClojureFiles files;

	private final @Nullable String entryFile;

	private @Nullable String entryNs;

	private @Nullable List<Root> roots;

	/** The selected libraries, known with the roots. */
	private List<Selected> libs = List.of();

	/** Each jar root's entry names. */
	private final Map<String, Set<String>> archives = new HashMap<>();

	/** The namespaces found so far: one read per file per lowering. */
	private final Map<String, Found> found = new HashMap<>();

	/** The data readers the roots hold, read with them. */
	private @Nullable ClojureDataReaders dataReaders;

	ClojureSourcePath(ClojureFiles files, @Nullable String entryFile) {
		this.files = files;
		this.entryFile = entryFile;
	}

	/**
	 * The namespace the entry file declares first, which names the root it sits under.
	 * @param ns the namespace, or {@code null} for a file without one
	 */
	void entryNamespace(@Nullable String ns) {
		this.entryNs = ns;
	}

	/**
	 * The entry file's path as the oracle's {@code *file*} holds it: absolute where the
	 * host has a working directory, {@code NO_SOURCE_PATH} for a read without a file.
	 * @return the path
	 */
	String entryPath() {
		if (this.entryFile == null) {
			return ClojureCoreSpecials.NO_SOURCE_PATH;
		}
		String dir = this.files.parent(this.entryFile);
		return dir == null ? this.entryFile : this.files.resolve(dir, lastSegmentOf(this.entryFile));
	}

	/**
	 * The entry file's name, the oracle's {@code *source-path*}, or
	 * {@code NO_SOURCE_FILE} for a read without a file.
	 * @return the name
	 */
	String entryName() {
		return this.entryFile == null ? ClojureCoreSpecials.NO_SOURCE_FILE : lastSegmentOf(this.entryFile);
	}

	/**
	 * The {@code .clj} file a namespace maps to below a root, like the oracle's root
	 * resource: the dots are directories and a dash is an underscore ({@code my-app.core}
	 * is {@code my_app/core.clj}).
	 * @param ns the namespace
	 * @return the relative path
	 */
	static String resourceOf(String ns) {
		return scriptBaseOf(ns) + ".clj";
	}

	/**
	 * The path a namespace maps to below a root without an extension, the oracle's
	 * {@code RT.load} script base ({@code my_app/core}).
	 * @param ns the namespace
	 * @return the relative path
	 */
	static String scriptBaseOf(String ns) {
		return ns.replace('-', '_').replace('.', '/');
	}

	/**
	 * The file of a namespace like the oracle's {@code RT.load}: its {@code .clj} file
	 * from the first root holding one, else its {@code .cljc} file from the first root
	 * holding one -- so a {@code .clj} under any root wins over a {@code .cljc} under an
	 * earlier one, measured on {@code clj} 1.12.6 (2026-10-08) -- else the built-in one
	 * ({@link ClojureBuiltinNamespaces}): a project file shadows a built-in namespace, as
	 * a source directory precedes a dependency jar on the oracle's classpath -- except
	 * one the oracle loads before the program, which a {@code require} never reads again.
	 * A namespace a jar holds only compiled ahead of time is refused: only source lowers.
	 * @param ns the namespace
	 * @return the file, or {@code null} when neither a root nor the built-ins hold one
	 */
	@Nullable Found find(String ns) {
		Found known = this.found.get(ns);
		if (known != null) {
			return known;
		}
		String base = scriptBaseOf(ns);
		boolean startup = ClojureBuiltinNamespaces.isStartup(ns);
		for (String extension : startup ? List.<String>of() : List.of(".clj", ".cljc")) {
			Found project = findFile(base + extension);
			if (project != null) {
				this.found.put(ns, project);
				return project;
			}
		}
		for (Root root : startup ? List.<Root>of() : roots()) {
			if (root.archive() && entriesOf(root).contains(base + "__init.class")) {
				throw new LispReadException(ns + " is compiled ahead of time in " + root.path()
						+ " without its source, and only a namespace's source is read");
			}
		}
		String builtin = ClojureBuiltinNamespaces.source(ns);
		if (builtin == null) {
			return null;
		}
		refuseBuiltinStandIn(ns);
		String relative = resourceOf(ns);
		Found shipped = new Found(relative, relative, builtin, true);
		this.found.put(ns, shipped);
		return shipped;
	}

	/**
	 * Refuses a built-in namespace where the project selects its library in a form the
	 * shipped file cannot stand in for: a Maven version newer than the one shipped (the
	 * shipped one stands in for an older one, as the oracle's newest-wins selection
	 * assumes), or the library's own source that does not hold the namespace.
	 */
	private void refuseBuiltinStandIn(String ns) {
		Lib lib = ClojureBuiltinLibs.libOf(ns);
		for (Selected selected : this.libs) {
			if (!selected.lib().equals(lib)) {
				continue;
			}
			if (!selected.contribution().builtin()) {
				throw new LispReadException(ns + ": the deps.edn selects " + lib + " " + selected.coord().print()
						+ ", which holds no " + resourceOf(ns) + " this build reads");
			}
			String shipped = ClojureBuiltinLibs.shippedVersion(lib);
			String wanted = selected.coord().string(":mvn/version");
			if (shipped != null && wanted != null && ClojureMavenVersions.compare(wanted, shipped) > 0) {
				throw new LispReadException(ns + ": the deps.edn selects " + lib + " " + wanted
						+ ", newer than the built-in " + lib.name() + " " + shipped);
			}
		}
	}

	/**
	 * A file below the first root holding it, for {@code load}: a root-relative path, not
	 * a namespace.
	 * @param relative the path, extension included
	 * @return the file, or {@code null} when no root holds it
	 */
	@Nullable Found findFile(String relative) {
		for (Root root : roots()) {
			if (root.archive()) {
				if (entriesOf(root).contains(relative)) {
					String text = this.files.readArchiveEntry(root.path(), relative);
					if (text != null) {
						return new Found(root.path() + "!/" + relative, relative, text, false);
					}
				}
				continue;
			}
			String path = this.files.resolve(root.path(), relative);
			String text = this.files.read(path);
			if (text != null) {
				return new Found(path, relative, text, false);
			}
		}
		return null;
	}

	private Set<String> entriesOf(Root root) {
		Set<String> known = this.archives.get(root.path());
		if (known == null) {
			List<String> entries = this.files.archiveEntries(root.path());
			known = entries == null ? Set.of() : new HashSet<>(entries);
			this.archives.put(root.path(), known);
		}
		return known;
	}

	/**
	 * The roots as a refusal names them: {@code ": src, test"} (the working directory as
	 * {@code .}), or why there are none to search.
	 * @return the text, starting with its separator
	 */
	String describeRoots() {
		if (this.files == ClojureFiles.NONE) {
			return " (the program was read without files)";
		}
		List<String> shown = new ArrayList<>();
		for (Root root : roots()) {
			shown.add(root.path().isEmpty() ? "." : root.path());
		}
		return ": " + String.join(", ", shown) + notSearched();
	}

	/**
	 * What the source path leaves out because this build does not read it -- a Maven or
	 * git coordinate, a jar's {@code pom.xml} -- for a refusal of a namespace no root
	 * holds: {@code "; not searched: org.clojure/data.json 2.5.1 (a Maven coordinate, not
	 * fetched)"}, or nothing.
	 * @return the text, starting with its separator, or the empty string
	 */
	String notSearched() {
		if (this.files == ClojureFiles.NONE) {
			return "";
		}
		roots();
		List<String> unread = new ArrayList<>();
		for (Selected selected : this.libs) {
			String note = selected.contribution().unread();
			if (note != null) {
				unread.add(note);
			}
		}
		return unread.isEmpty() ? "" : "; not searched: " + String.join(", ", unread);
	}

	/**
	 * The source roots, in search order; an empty string is the working directory.
	 * @return the roots
	 */
	List<Root> roots() {
		List<Root> known = this.roots;
		if (known == null) {
			known = computeRoots();
			this.roots = known;
		}
		return known;
	}

	/**
	 * The program's data readers ({@link ClojureDataReaders}): every
	 * {@code data_readers.clj} at a root, in the roots' order, then every
	 * {@code data_readers.cljc}, like the oracle's {@code getResources} over its
	 * classpath. Read once, when the program's project resolves.
	 * @return the data readers
	 */
	ClojureDataReaders dataReaders() {
		ClojureDataReaders known = this.dataReaders;
		if (known == null) {
			List<ClojureDataReaders.Source> sources = new ArrayList<>();
			for (String name : ClojureDataReaders.FILES) {
				for (Root root : roots()) {
					ClojureDataReaders.Source source = rootFile(root, name);
					if (source != null) {
						sources.add(source);
					}
				}
			}
			known = ClojureDataReaders.of(sources);
			this.dataReaders = known;
		}
		return known;
	}

	/** A file directly below a root, or null when the root holds none of the name. */
	private ClojureDataReaders.@Nullable Source rootFile(Root root, String name) {
		if (root.archive()) {
			if (!entriesOf(root).contains(name)) {
				return null;
			}
			String text = this.files.readArchiveEntry(root.path(), name);
			return text == null ? null : new ClojureDataReaders.Source(root.path() + "!/" + name, text);
		}
		String path = this.files.resolve(root.path(), name);
		String text = this.files.read(path);
		return text == null ? null : new ClojureDataReaders.Source(path, text);
	}

	private List<Root> computeRoots() {
		Set<Root> out = new LinkedHashSet<>();
		String dir = this.entryFile == null ? "" : this.files.parent(this.entryFile);
		if (dir == null) {
			dir = "";
		}
		out.add(new Root(inferredRoot(dir), false));
		if (this.files == ClojureFiles.NONE) {
			out.add(new Root("src", false));
			return List.copyOf(out);
		}
		ClojureBasis basis = ClojureBasis.create(this.files, dir, this.entryFile != null);
		for (String path : basis.paths()) {
			out.add(new Root(path, false));
		}
		this.libs = basis.libraries();
		for (Selected selected : this.libs) {
			out.addAll(selected.contribution().roots());
		}
		for (Selected selected : this.libs) {
			for (Root root : selected.contribution().roots()) {
				if (root.archive() && entriesOf(root).stream().anyMatch(entry -> entry.endsWith(".class"))) {
					this.files.addJavaClassPath(root.path(), selected.contribution().mavenCoordinate());
				}
			}
		}
		return List.copyOf(out);
	}

	/**
	 * The root the entry file's namespace names: {@code src} for
	 * {@code src/demo/main.clj} declaring {@code demo.main}. When the file's name and the
	 * directories above it do not spell the namespace (or there is none), the file's own
	 * directory.
	 */
	private String inferredRoot(String dir) {
		String file = this.entryFile;
		String ns = this.entryNs;
		if (file == null || ns == null) {
			return dir;
		}
		String[] segments = ns.split("\\.", -1);
		String fileName = lastSegmentOf(file);
		String stem = munge(segments[segments.length - 1]);
		if (segments.length < 2 || !(fileName.equals(stem + ".clj") || fileName.equals(stem + ".cljc"))) {
			return dir;
		}
		String root = dir;
		for (int i = segments.length - 2; i >= 0; i--) {
			if (root == null || !lastSegmentOf(root).equals(munge(segments[i]))) {
				return dir;
			}
			root = this.files.parent(root);
		}
		return root == null ? dir : root;
	}

	private static String munge(String segment) {
		return segment.replace('-', '_');
	}

	static String lastSegmentOf(String path) {
		int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		return path.substring(cut + 1);
	}

}
