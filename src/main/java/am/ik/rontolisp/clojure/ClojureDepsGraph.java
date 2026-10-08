package am.ik.rontolisp.clojure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.Predicate;

import am.ik.rontolisp.clojure.ClojureDepsEdn.Coord;
import am.ik.rontolisp.clojure.ClojureDepsEdn.Lib;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The dependency graph of a {@code deps.edn} project and the libraries it selects, by the
 * oracle's own rules ({@code clj} 1.12.6's {@code clojure.tools.deps/resolve-deps} and
 * {@code make-classpath-map}, ported and measured against it 2026-10-08):
 * <ul>
 * <li>the top-level deps are expanded breadth first, each library's dependencies after
 * the libraries already queued;</li>
 * <li>a top-level dep always wins; otherwise the newest version of a library across the
 * tree is selected ({@link ClojureMavenVersions} for Maven versions; two local roots of
 * one library are no versions of each other and are refused, as are coordinates of two
 * types), and the dependencies only the replaced version brought in are dropped;</li>
 * <li>{@code :exclusions} drop a library below the coordinate naming them, unless another
 * path brings it in; a cycle stops at the library already selected;</li>
 * <li>the selected libraries go on the source path top of the tree first, alphabetically
 * by their path from the top at each depth, each library's roots in its own order.</li>
 * </ul>
 * What a coordinate brings -- its dependencies, its roots -- is the {@link Procurer}'s;
 * this class is the selection, deterministic for a given graph
 * ({@code .kb/emitted-output-determinism.md}).
 */
final class ClojureDepsGraph {

	/**
	 * What the expansion asks of a coordinate: the oracle's extension points
	 * ({@code clojure.tools.deps.extensions}), each answering for one library.
	 */
	interface Procurer {

		/**
		 * The coordinate in canonical form ({@code ext/canonicalize}): a local root made
		 * canonical against the directory and checked to exist, a git coordinate checked.
		 * @param lib the library
		 * @param coord its coordinate
		 * @param dir the directory a relative path is relative to, or null for the
		 * working directory
		 * @return the canonical coordinate
		 */
		Coord canonicalize(Lib lib, Coord coord, @Nullable String dir);

		/**
		 * The coordinate with its manifest merged in ({@code ext/manifest-type}):
		 * {@code :deps/manifest} and {@code :deps/root} where the coordinate has them.
		 * @param lib the library
		 * @param coord its coordinate
		 * @return the coordinate the rest of the expansion uses
		 */
		Coord manifest(Lib lib, Coord coord);

		/**
		 * The libraries this one depends on, in its manifest's order
		 * ({@code ext/coord-deps}), not yet canonicalized.
		 * @param lib the library
		 * @param useCoord its coordinate, manifest merged
		 * @return the dependencies
		 */
		List<Dep> children(Lib lib, Coord useCoord);

		/**
		 * What a selected library puts on the source path ({@code ext/coord-paths}).
		 * @param lib the library
		 * @param useCoord its coordinate, manifest merged
		 * @return its contribution
		 */
		Contribution contribution(Lib lib, Coord useCoord);

		/**
		 * Whether a selected library still needs the preparation its
		 * {@code :deps/prep-lib} names ({@code prep-libs!}).
		 * @param lib the library
		 * @param useCoord its coordinate, manifest merged
		 * @return whether its {@code :ensure} directory is missing
		 */
		boolean unprepped(Lib lib, Coord useCoord);

		/**
		 * Compares two git coordinates of one library with different commits: the newer
		 * is the descendant ({@code compare-versions [:git :git]}).
		 * @param lib the library
		 * @param x one coordinate
		 * @param y the other
		 * @return positive when {@code x} is newer
		 */
		int compareGit(Lib lib, Coord x, Coord y);

	}

	/**
	 * One dependency as a manifest declares it.
	 *
	 * @param lib the library
	 * @param coord its coordinate, or null for {@code nil}
	 */
	record Dep(Lib lib, @Nullable Coord coord) {
	}

	/**
	 * One source root: a directory, or a jar read in place.
	 *
	 * @param path its path
	 * @param archive whether it is a jar
	 */
	record Root(String path, boolean archive) {
	}

	/**
	 * What a selected library puts on the source path.
	 *
	 * @param roots its source roots, in order
	 * @param builtin whether its namespaces are this front end's own (the Clojure core,
	 * the shipped Ring namespaces): a built-in coordinate
	 * @param unread what of it this build does not read, for a refusal to name: a Maven
	 * or git coordinate is not fetched, a jar's {@code pom.xml} is not read -- or null
	 * when everything it holds is on the path
	 */
	record Contribution(List<Root> roots, boolean builtin, @Nullable String unread) {
	}

	/**
	 * A selected library.
	 *
	 * @param lib the library
	 * @param coord its selected coordinate, manifest merged
	 * @param contribution what it puts on the source path
	 */
	record Selected(Lib lib, Coord coord, Contribution contribution) {
	}

	/** Every version of one library seen, the paths reaching each, and the selection. */
	private static final class Versions {

		final Map<String, Coord> coords = new LinkedHashMap<>();

		final Map<String, Set<List<Lib>>> paths = new LinkedHashMap<>();

		@Nullable String select;

		boolean top;

		@Nullable Set<List<Lib>> selectedPaths() {
			return this.select == null ? null : this.paths.get(this.select);
		}

	}

	/** What the breadth-first queue holds: a node, or a node's dependencies to read. */
	private sealed interface Queued permits Node, Children {

	}

	/**
	 * A queued node: the path of libraries above it, and it.
	 *
	 * @param parents the libraries from the top down to its parent
	 * @param lib the library
	 * @param coord its canonical coordinate, or null for {@code nil}
	 */
	private record Node(List<Lib> parents, Lib lib, @Nullable Coord coord) implements Queued {
	}

	/**
	 * A queued expansion of one included library's dependencies, filtered by what its
	 * exclusions allow.
	 *
	 * @param lib the library
	 * @param coordId its version's identity
	 * @param useCoord its coordinate, manifest merged
	 * @param usePath the libraries from the top down to it
	 * @param pred which of its dependencies get queued
	 */
	private record Children(Lib lib, String coordId, Coord useCoord, List<Lib> usePath,
			Predicate<Lib> pred) implements Queued {
	}

	private final Procurer procurer;

	private final @Nullable String dir;

	/** {@code :override-deps}: the coordinate used for a library wherever it appears. */
	private final Map<Lib, @Nullable Coord> overrides;

	/** {@code :default-deps}: the coordinate used for a library that names none. */
	private final Map<Lib, @Nullable Coord> defaults;

	/** An override or default canonicalized against the project, once per library. */
	private final Map<String, Coord> canonicalArgs = new HashMap<>();

	private final Map<Lib, Versions> versions = new LinkedHashMap<>();

	private final Map<List<Lib>, Set<Lib>> exclusions = new HashMap<>();

	private final Map<String, @Nullable Set<Lib>> cut = new HashMap<>();

	private final Map<String, List<Node>> childrenMemo = new HashMap<>();

	private ClojureDepsGraph(Procurer procurer, @Nullable String dir, Map<Lib, @Nullable Coord> overrides,
			Map<Lib, @Nullable Coord> defaults) {
		this.procurer = procurer;
		this.dir = dir;
		this.overrides = overrides;
		this.defaults = defaults;
	}

	/**
	 * Expands the top-level deps and selects the libraries, like the oracle's
	 * {@code resolve-deps} followed by {@code make-classpath-map}'s library order.
	 * @param deps the top-level deps, in the merged map's order
	 * @param dir the project's directory, which a top-level relative path is relative to
	 * (null for the working directory)
	 * @param procurer what a coordinate brings
	 * @return the selected libraries in source path order
	 */
	static List<Selected> resolve(SequencedMap<Lib, @Nullable Coord> deps, @Nullable String dir, Procurer procurer) {
		return resolve(deps, dir, procurer, Map.of(), Map.of());
	}

	/**
	 * {@link #resolve(SequencedMap, String, Procurer)} under the aliases'
	 * {@code :override-deps} and {@code :default-deps}, the oracle's
	 * {@code choose-coord}: wherever a library appears, its override if it has one, else
	 * the coordinate that names it, else its default. Both are read relative to the
	 * project's directory wherever the library appears, and only when used (measured on
	 * {@code clj} 1.12.6, 2026-10-08: a relative {@code :local/root} override of a
	 * transitive library resolves against the project).
	 * @param deps the top-level deps, in the merged map's order
	 * @param dir the project's directory (null for the working directory)
	 * @param procurer what a coordinate brings
	 * @param overrides {@code :override-deps}
	 * @param defaults {@code :default-deps}
	 * @return the selected libraries in source path order
	 */
	static List<Selected> resolve(SequencedMap<Lib, @Nullable Coord> deps, @Nullable String dir, Procurer procurer,
			Map<Lib, @Nullable Coord> overrides, Map<Lib, @Nullable Coord> defaults) {
		ClojureDepsGraph graph = new ClojureDepsGraph(procurer, dir, overrides, defaults);
		List<Dep> top = new ArrayList<>();
		for (Map.Entry<Lib, @Nullable Coord> entry : deps.entrySet()) {
			top.add(new Dep(entry.getKey(), entry.getValue()));
		}
		graph.expand(graph.canonicalized(top, List.of(), dir));
		return graph.classpathOrder();
	}

	/** The coordinates canonicalized against a directory, as queued nodes. */
	private List<Node> canonicalized(List<Dep> deps, List<Lib> parents, @Nullable String against) {
		List<Node> out = new ArrayList<>();
		for (Dep dep : deps) {
			Coord coord = dep.coord();
			out.add(new Node(parents, dep.lib(),
					coord == null ? null : this.procurer.canonicalize(dep.lib(), coord, against)));
		}
		return out;
	}

	/** The breadth-first expansion: {@code expand-deps}. */
	private void expand(List<Node> top) {
		Deque<Queued> queue = new ArrayDeque<>(top);
		Deque<Node> pending = new ArrayDeque<>();
		while (true) {
			Node node = nextNode(pending, queue);
			if (node == null) {
				return;
			}
			Lib lib = node.lib();
			List<Lib> parents = node.parents();
			Coord coord = chooseCoord(lib, node.coord());
			if (coord == null) {
				throw new LispReadException("Bad coordinate for library " + lib + ", expected map: nil");
			}
			Coord useCoord = this.procurer.manifest(lib, coord);
			String coordId = depId(useCoord);
			List<Lib> usePath = append(parents, lib);
			Decision decision = include(lib, useCoord, coordId, parents);
			Predicate<Lib> pred = updateExclusions(lib, useCoord, coordId, usePath, decision);
			if (pred != null) {
				queue.addLast(new Children(lib, coordId, useCoord, usePath, pred));
			}
		}
	}

	/** {@code choose-coord}: the override, else the node's own, else the default. */
	private @Nullable Coord chooseCoord(Lib lib, @Nullable Coord coord) {
		Coord override = argCoord("override", lib, this.overrides);
		if (override != null) {
			return override;
		}
		return coord != null ? coord : argCoord("default", lib, this.defaults);
	}

	private @Nullable Coord argCoord(String kind, Lib lib, Map<Lib, @Nullable Coord> args) {
		Coord raw = args.get(lib);
		if (raw == null) {
			return null;
		}
		String key = kind + " " + lib;
		Coord known = this.canonicalArgs.get(key);
		if (known == null) {
			known = this.procurer.canonicalize(lib, raw, this.dir);
			this.canonicalArgs.put(key, known);
		}
		return known;
	}

	/**
	 * The next node: a pending sibling first, else the next queued node, else the next
	 * queued library's dependencies -- read now, in queue order, so a manifest's refusal
	 * surfaces where the oracle's does.
	 */
	private @Nullable Node nextNode(Deque<Node> pending, Deque<Queued> queue) {
		while (true) {
			Node sibling = pending.pollFirst();
			if (sibling != null) {
				return sibling;
			}
			Queued next = queue.pollFirst();
			switch (next) {
				case null -> {
					return null;
				}
				case Node node -> {
					return node;
				}
				case Children children -> {
					for (Node child : childrenOf(children)) {
						if (children.pred().test(child.lib())) {
							pending.addLast(new Node(children.usePath(), child.lib(), child.coord()));
						}
					}
				}
			}
		}
	}

	/** A library's canonical dependencies, read once per library version. */
	private List<Node> childrenOf(Children children) {
		String key = children.lib() + " " + children.coordId();
		List<Node> known = this.childrenMemo.get(key);
		if (known == null) {
			String root = children.useCoord().string(":deps/root");
			known = canonicalized(this.procurer.children(children.lib(), children.useCoord()), List.of(),
					root != null ? root : this.dir);
			this.childrenMemo.put(key, known);
		}
		return known;
	}

	/** Why a node was included or omitted ({@code include-coord?}'s reason). */
	private enum Decision {

		NEW_TOP(true), NEW_DEP(true), NEWER_VERSION(true), EXCLUDED(false), USE_TOP(false), PARENT_OMITTED(false),
		SAME_VERSION(false), OLDER_VERSION(false);

		final boolean include;

		Decision(boolean include) {
			this.include = include;
		}

	}

	/**
	 * The include decision for one node, updating the versions: {@code include-coord?}.
	 */
	private Decision include(Lib lib, Coord coord, String coordId, List<Lib> parents) {
		if (parents.isEmpty()) {
			addVersion(lib, coord, parents, coordId);
			select(lib, coordId, true);
			return Decision.NEW_TOP;
		}
		if (excluded(parents, lib)) {
			return Decision.EXCLUDED;
		}
		Versions known = this.versions.get(lib);
		if (known != null && known.top) {
			return Decision.USE_TOP;
		}
		if (parentMissing(parents)) {
			return Decision.PARENT_OMITTED;
		}
		if (known == null || known.select == null) {
			addVersion(lib, coord, parents, coordId);
			select(lib, coordId, false);
			return Decision.NEW_DEP;
		}
		if (coordId.equals(known.select)) {
			addVersion(lib, coord, parents, coordId);
			return Decision.SAME_VERSION;
		}
		Coord selected = known.coords.get(known.select);
		if (selected != null && compareVersions(lib, coord, selected) > 0) {
			Set<List<Lib>> omitted = new LinkedHashSet<>();
			Set<List<Lib>> oldPaths = known.selectedPaths();
			if (oldPaths != null) {
				for (List<Lib> path : oldPaths) {
					omitted.add(append(path, lib));
				}
			}
			addVersion(lib, coord, parents, coordId);
			deselectOrphans(omitted);
			select(lib, coordId, false);
			return Decision.NEWER_VERSION;
		}
		return Decision.OLDER_VERSION;
	}

	private void addVersion(Lib lib, Coord coord, List<Lib> parents, String coordId) {
		Versions known = this.versions.computeIfAbsent(lib, k -> new Versions());
		known.coords.put(coordId, coord);
		known.paths.computeIfAbsent(coordId, k -> new LinkedHashSet<>()).add(parents);
	}

	private void select(Lib lib, String coordId, boolean top) {
		Versions known = this.versions.computeIfAbsent(lib, k -> new Versions());
		known.select = coordId;
		if (top) {
			known.top = true;
		}
	}

	/** Whether an exclusion above the node names its library: {@code excluded?}. */
	private boolean excluded(List<Lib> parents, Lib lib) {
		Lib base = lib.base();
		for (int length = parents.size(); length > 0; length--) {
			Set<Lib> excluded = this.exclusions.get(parents.subList(0, length));
			if (excluded != null && excluded.contains(base)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a library on the path to the node is no longer selected along it -- a newer
	 * version elsewhere orphaned it: {@code parent-missing?}, following the other paths
	 * to a selected library before giving up.
	 */
	private boolean parentMissing(List<Lib> parentPath) {
		List<Lib> path = parentPath;
		Deque<List<Lib>> more = new ArrayDeque<>();
		while (true) {
			if (path.isEmpty()) {
				return false;
			}
			Lib lib = path.get(path.size() - 1);
			List<Lib> check = path.subList(0, path.size() - 1);
			Versions known = this.versions.get(lib);
			Set<List<Lib>> toSelected = known == null ? null : known.selectedPaths();
			if (toSelected != null && toSelected.contains(check)) {
				for (List<Lib> other : toSelected) {
					if (!other.equals(check)) {
						more.addLast(other);
					}
				}
				path = check;
			}
			else if (!more.isEmpty()) {
				path = more.removeFirst();
			}
			else {
				return true;
			}
		}
	}

	/**
	 * Deselects every library all of whose paths to its selected version run through the
	 * omitted paths: {@code deselect-orphans}.
	 */
	private void deselectOrphans(Set<List<Lib>> omitted) {
		for (Versions known : this.versions.values()) {
			Set<List<Lib>> paths = known.selectedPaths();
			boolean orphaned = true;
			if (paths != null) {
				for (List<Lib> path : paths) {
					boolean under = false;
					for (List<Lib> prefix : omitted) {
						if (path.size() >= prefix.size() && path.subList(0, prefix.size()).equals(prefix)) {
							under = true;
							break;
						}
					}
					if (!under) {
						orphaned = false;
						break;
					}
				}
			}
			if (orphaned) {
				known.select = null;
			}
		}
	}

	/**
	 * The exclusions an included node adds, and which of its dependencies get queued
	 * ({@code update-excl}): all of them but its own exclusions when it is new; when it
	 * is the selected version seen again, only those an earlier visit's exclusions cut
	 * that this visit no longer excludes; none otherwise (null).
	 */
	private @Nullable Predicate<Lib> updateExclusions(Lib lib, Coord useCoord, String coordId, List<Lib> usePath,
			Decision decision) {
		Set<Lib> coordExcl = useCoord.exclusions();
		String cutKey = lib + " " + coordId;
		if (decision.include) {
			if (coordExcl == null) {
				return child -> true;
			}
			this.exclusions.put(usePath, coordExcl);
			this.cut.put(cutKey, coordExcl);
			return child -> !coordExcl.contains(child);
		}
		if (decision == Decision.SAME_VERSION) {
			if (coordExcl != null && !coordExcl.isEmpty()) {
				this.exclusions.put(usePath, coordExcl);
			}
			Set<Lib> cutCoord = this.cut.get(cutKey);
			Set<Lib> newCut = intersection(coordExcl, cutCoord);
			Set<Lib> enqueueOnly = difference(cutCoord, newCut);
			this.cut.put(cutKey, newCut);
			Set<Lib> only = enqueueOnly == null ? Set.of() : enqueueOnly;
			return only::contains;
		}
		return null;
	}

	/** {@code clojure.set/intersection}, where a missing set is nil. */
	private static @Nullable Set<Lib> intersection(@Nullable Set<Lib> a, @Nullable Set<Lib> b) {
		int countA = a == null ? 0 : a.size();
		int countB = b == null ? 0 : b.size();
		if (countB < countA) {
			return intersection(b, a);
		}
		if (a == null) {
			return null;
		}
		Set<Lib> out = new LinkedHashSet<>(a);
		out.removeIf(member -> b == null || !b.contains(member));
		return out;
	}

	/** {@code clojure.set/difference}, where a missing set is nil. */
	private static @Nullable Set<Lib> difference(@Nullable Set<Lib> a, @Nullable Set<Lib> b) {
		if (a == null) {
			return null;
		}
		Set<Lib> out = new LinkedHashSet<>(a);
		if (b != null) {
			out.removeAll(b);
		}
		return out;
	}

	/**
	 * The selected libraries in source path order: every path from the top to a selected
	 * library, sorted by length and then library by library, each library at its first
	 * path ({@code flatten-libs}); a library's contribution read in that order.
	 */
	private List<Selected> classpathOrder() {
		List<List<Lib>> treePaths = new ArrayList<>();
		Map<Lib, Coord> selected = new LinkedHashMap<>();
		for (Map.Entry<Lib, Versions> entry : this.versions.entrySet()) {
			Versions known = entry.getValue();
			Coord coord = known.select == null ? null : known.coords.get(known.select);
			if (coord == null) {
				continue; // cut-orphans
			}
			selected.put(entry.getKey(), coord);
			Set<List<Lib>> parents = known.selectedPaths();
			if (parents != null) {
				for (List<Lib> parent : parents) {
					treePaths.add(append(parent, entry.getKey()));
				}
			}
		}
		// download-libs: every selected library's contribution, in the expansion's order
		Map<Lib, Selected> chosen = new LinkedHashMap<>();
		for (Map.Entry<Lib, Coord> entry : selected.entrySet()) {
			chosen.put(entry.getKey(), new Selected(entry.getKey(), entry.getValue(),
					this.procurer.contribution(entry.getKey(), entry.getValue())));
		}
		List<String> unprepped = new ArrayList<>();
		for (Map.Entry<Lib, Coord> entry : selected.entrySet()) {
			if (this.procurer.unprepped(entry.getKey(), entry.getValue())) {
				unprepped.add(entry.getKey().toString());
			}
		}
		if (!unprepped.isEmpty()) {
			throw new LispReadException(
					"The following libs must be prepared before use: [" + String.join(" ", unprepped) + "]");
		}
		treePaths.sort(ClojureDepsGraph::comparePaths);
		Set<Lib> order = new LinkedHashSet<>();
		for (List<Lib> path : treePaths) {
			order.add(path.get(path.size() - 1));
		}
		List<Selected> out = new ArrayList<>();
		for (Lib lib : order) {
			Selected one = chosen.get(lib);
			if (one != null) {
				out.add(one);
			}
		}
		return out;
	}

	/** The oracle's vector order: shorter first, then member by member. */
	private static int comparePaths(List<Lib> a, List<Lib> b) {
		if (a.size() != b.size()) {
			return Integer.compare(a.size(), b.size());
		}
		for (int i = 0; i < a.size(); i++) {
			int c = a.get(i).compareTo(b.get(i));
			if (c != 0) {
				return c;
			}
		}
		return 0;
	}

	/**
	 * A coordinate's identity among the versions of its library ({@code ext/dep-id}): a
	 * Maven version, a local root, a git repository at a commit.
	 * @param coord the canonical coordinate
	 * @return its identity
	 */
	static String depId(Coord coord) {
		return switch (coord.type()) {
			case "mvn" -> "mvn " + coord.string(":mvn/version");
			case "local" -> "local " + coord.string(":local/root");
			default -> "git " + coord.string(":git/url") + " " + coord.string(":git/sha");
		};
	}

	/**
	 * Which of two coordinates of one library is newer ({@code ext/compare-versions}):
	 * Maven versions by their order, one local root is no other's version, git commits by
	 * descent; coordinates of two types are the oracle's refusal.
	 */
	private int compareVersions(Lib lib, Coord x, Coord y) {
		String typeX = x.type();
		String typeY = y.type();
		if (typeX.equals("mvn") && typeY.equals("mvn")) {
			return ClojureMavenVersions.compare(String.valueOf(x.string(":mvn/version")),
					String.valueOf(y.string(":mvn/version")));
		}
		if (typeX.equals("local") && typeY.equals("local")) {
			String rootX = x.string(":local/root");
			String rootY = y.string(":local/root");
			if (String.valueOf(rootX).equals(rootY)) {
				return 0;
			}
			throw new LispReadException("No known ancestor relationship between local versions for " + lib + ": "
					+ rootX + " and " + rootY);
		}
		if (typeX.equals("git") && typeY.equals("git")) {
			return String.valueOf(x.string(":git/sha")).equals(y.string(":git/sha")) ? 0
					: this.procurer.compareGit(lib, x, y);
		}
		throw new LispReadException("Unable to compare versions for " + lib + ": " + x.print() + " and " + y.print());
	}

	private static List<Lib> append(List<Lib> path, Lib lib) {
		List<Lib> out = new ArrayList<>(path.size() + 1);
		out.addAll(path);
		out.add(lib);
		return List.copyOf(out);
	}

}
