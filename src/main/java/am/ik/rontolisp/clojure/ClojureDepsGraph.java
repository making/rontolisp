package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import am.ik.rontolisp.clojure.ClojureDepsEdn.Coord;
import am.ik.rontolisp.clojure.ClojureDepsEdn.Lib;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Which libraries a {@code deps.edn} project selects, and in which order they go on the
 * source path: the answer {@code clj} gives for the same dependencies.
 * <p>
 * Built from two things, and from nothing else. The rules are the ones the published
 * reference states (clojure.org, "Dependency Expansion" and the {@code deps.edn}
 * reference): the tree is walked breadth first; a top-level dependency always wins; among
 * the others the newest version of a library wins; an exclusion holds below the
 * coordinate that names it, and a library excluded on one path and brought in on another
 * is in; what only a replaced version brought in is dropped. Everything the reference
 * leaves open was learned by running {@code clj} 1.12.6.1673 as a black box
 * ({@code -Srepro -Spath} and {@code -Stree} over a {@code file:} Maven repository and
 * over {@code :local/root} trees, 2026-11-18: forty constructed Maven graphs, some thirty
 * local trees, and the classpaths of 1,500 generated graphs of up to eight libraries in
 * three versions with exclusions and cycles) and writing down what it answered. The
 * source of tools.deps was not read for it, and the walk that stood here before, which
 * had been translated from that source, was deleted, not edited. What the answers showed,
 * each pinned by {@code ClojureDepsGraphTest} (the read of a replaced library's
 * dependencies by {@code ClojureDepsFetchCliTest}):
 * <ul>
 * <li>The walk goes in waves, one per depth. Every dependency of a wave is decided, in
 * order, before the dependencies of any library taken in it are read; so a refusal met
 * while deciding comes before one met while reading, and a library replaced within its
 * own wave still has its dependencies read (and each of them then turned away).</li>
 * <li>A library keeps, per version, the routes it was taken on. A dependency is
 * considered only while its parent's selected version was taken on the route the
 * dependency hangs from, and while that parent itself hangs from a library that still
 * holds it.</li>
 * <li>When a newer version replaces the selected one, every other library whose selected
 * version was taken only on routes through the replaced one loses its selection, and with
 * it its claim against an older version arriving later. A library taken again after that
 * has its dependencies offered again.</li>
 * <li>A version met again where its coordinate excludes less offers what every earlier
 * coordinate of it excluded and this one does not. Only a coordinate's own
 * {@code :exclusions} count for that; one inherited from further up the route does
 * not.</li>
 * <li>Two versions that compare equal but are spelled differently are the first one.</li>
 * <li>A selected library is placed by the shortest route its selected version was ever
 * taken on, routes of one length in the order of their libraries' names, whether or not
 * that route still stands.</li>
 * </ul>
 * What a coordinate brings -- its dependencies, its roots -- is the {@link Procurer}'s;
 * this class is the selection, deterministic for a given graph
 * ({@code .kb/emitted-output-determinism.md}).
 */
final class ClojureDepsGraph {

	/**
	 * What the selection asks about a coordinate. Each question is one the oracle answers
	 * per kind of coordinate (Maven, local root, git), and each may refuse in the
	 * oracle's words.
	 */
	interface Procurer {

		/**
		 * The coordinate in the one spelling the rest of the selection compares: a local
		 * root made canonical against the directory and checked to exist, a git
		 * coordinate checked against its repository, a Maven version range resolved.
		 * @param lib the library
		 * @param coord its coordinate
		 * @param dir the directory a relative path is relative to, or null for the
		 * working directory
		 * @return the canonical coordinate
		 */
		Coord canonicalize(Lib lib, Coord coord, @Nullable String dir);

		/**
		 * The coordinate with what kind of project it names ({@code :deps/manifest}) and
		 * where that project's root is ({@code :deps/root}), where the coordinate has
		 * them.
		 * @param lib the library
		 * @param coord its canonical coordinate
		 * @return the coordinate the rest of the selection uses
		 */
		Coord manifest(Lib lib, Coord coord);

		/**
		 * The libraries this one depends on, in the order and with the coordinates it
		 * declares them.
		 * @param lib the library
		 * @param useCoord its coordinate, as {@link #manifest} answered it
		 * @return the dependencies
		 */
		List<Dep> children(Lib lib, Coord useCoord);

		/**
		 * What a selected library puts on the source path.
		 * @param lib the library
		 * @param useCoord its coordinate, as {@link #manifest} answered it
		 * @return its contribution
		 */
		Contribution contribution(Lib lib, Coord useCoord);

		/**
		 * Whether a selected library declares a preparation step ({@code :deps/prep-lib})
		 * that has not been run.
		 * @param lib the library
		 * @param useCoord its coordinate, as {@link #manifest} answered it
		 * @return whether the directory the step would make is missing
		 */
		boolean unprepped(Lib lib, Coord useCoord);

		/**
		 * Compares two git coordinates of one library that name different commits: of two
		 * commits the descendant is the newer.
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
	 * @param mavenCoordinate the Maven coordinates
	 * ({@code groupId:artifactId[:extension:classifier]:version}) of the jar a Maven
	 * coordinate fetched, its one root -- or null for any other root
	 * @param deferred what it contributes once a lookup first asks for one of the
	 * namespaces it holds, for a library every program selects and few read (the built-in
	 * clojure's own dependencies, {@link ClojureBuiltinLibs#deferredNamespaces}) -- or
	 * null when the roots above are all of it
	 */
	record Contribution(List<Root> roots, boolean builtin, @Nullable String unread, @Nullable String mavenCoordinate,
			@Nullable Deferred deferred) {

		/**
		 * A contribution read in full where it is selected.
		 * @param roots its source roots, in order
		 * @param builtin whether its namespaces are this front end's own
		 * @param unread what of it this build does not read, or null
		 * @param mavenCoordinate the Maven coordinates of the jar a Maven coordinate
		 * fetched, or null
		 */
		Contribution(List<Root> roots, boolean builtin, @Nullable String unread, @Nullable String mavenCoordinate) {
			this(roots, builtin, unread, mavenCoordinate, null);
		}

		/**
		 * A contribution no Maven coordinate fetched.
		 * @param roots its source roots, in order
		 * @param builtin whether its namespaces are this front end's own
		 * @param unread what of it this build does not read, or null
		 */
		Contribution(List<Root> roots, boolean builtin, @Nullable String unread) {
			this(roots, builtin, unread, null, null);
		}

	}

	/**
	 * A contribution read only when a lookup first asks for a namespace it holds.
	 *
	 * @param namespaces the namespaces it holds
	 * @param read what it contributes then: its jar fetched, or the note naming it where
	 * nothing fetches
	 */
	record Deferred(Set<String> namespaces, Supplier<Contribution> read) {
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

	/**
	 * One version of a library that the walk took at least once.
	 */
	private static final class Standing {

		final Lib lib;

		/**
		 * The coordinate it was first taken with, as {@link Procurer#manifest} gave it.
		 */
		final Coord coord;

		/** Every route it was taken on, top down to its parent; none is ever removed. */
		final Set<List<Lib>> routes = new LinkedHashSet<>();

		/**
		 * The libraries every coordinate it was taken with, since it last became the
		 * selection, excluded itself.
		 */
		Set<Lib> withheld = Set.of();

		/** What it depends on, canonical; read the first time the walk needs it. */
		@Nullable List<Dep> dependencies;

		Standing(Lib lib, Coord coord) {
			this.lib = lib;
			this.coord = coord;
		}

	}

	/** What the walk knows of one library. */
	private static final class Entry {

		/** Its versions taken so far, by {@link #version}. */
		final Map<String, Standing> taken = new HashMap<>();

		/** The selected version, or null while none is. */
		@Nullable Standing selected;

		/** Whether the project names it itself. */
		boolean top;

	}

	/**
	 * A dependency still to decide.
	 *
	 * @param route the libraries from the top down to the one declaring it
	 * @param lib the library
	 * @param coord its canonical coordinate, or null for {@code nil}
	 * @param barred the libraries excluded by the coordinates along the route
	 */
	private record Offer(List<Lib> route, Lib lib, @Nullable Coord coord, Set<Lib> barred) {
	}

	/**
	 * A dependency just taken, whose own dependencies make part of the next wave.
	 *
	 * @param standing the version taken
	 * @param route the route it was taken on
	 * @param own what its coordinate excludes itself
	 * @param barred the libraries excluded for everything below it on this route
	 * @param only the dependencies to offer, or null for every one {@code own} lets
	 * through
	 */
	private record Opening(Standing standing, List<Lib> route, Set<Lib> own, Set<Lib> barred, @Nullable Set<Lib> only) {
	}

	/** Shorter routes first; routes of one length by their libraries, top down. */
	private static final Comparator<List<Lib>> ROUTE_ORDER = (a, b) -> {
		if (a.size() != b.size()) {
			return Integer.compare(a.size(), b.size());
		}
		for (int i = 0; i < a.size(); i++) {
			int byLib = a.get(i).compareTo(b.get(i));
			if (byLib != 0) {
				return byLib;
			}
		}
		return 0;
	};

	private final Procurer procurer;

	private final @Nullable String dir;

	/** {@code :override-deps}: the coordinate used for a library wherever it appears. */
	private final Map<Lib, @Nullable Coord> overrides;

	/** {@code :default-deps}: the coordinate used for a library that names none. */
	private final Map<Lib, @Nullable Coord> defaults;

	/** The overrides already made canonical, each when first used. */
	private final Map<Lib, Coord> canonicalOverrides = new HashMap<>();

	/** The defaults already made canonical, each when first used. */
	private final Map<Lib, Coord> canonicalDefaults = new HashMap<>();

	/** Every library offered so far. */
	private final Map<Lib, Entry> entries = new HashMap<>();

	/** The libraries in the order each was first taken. */
	private final Set<Lib> firstTaken = new LinkedHashSet<>();

	private ClojureDepsGraph(Procurer procurer, @Nullable String dir, Map<Lib, @Nullable Coord> overrides,
			Map<Lib, @Nullable Coord> defaults) {
		this.procurer = procurer;
		this.dir = dir;
		this.overrides = overrides;
		this.defaults = defaults;
	}

	/**
	 * Selects the libraries of a project.
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
	 * {@link #resolve(SequencedMap, String, Procurer)} under the selected aliases'
	 * {@code :override-deps} and {@code :default-deps}. Wherever a library appears, top
	 * level included, its coordinate is its override if it has one, else the one written
	 * there, else its default. Asked of {@code clj} 1.12.6: an override or default is
	 * read relative to the project's directory and only when used (one whose root is
	 * missing and that nothing uses is no refusal); the coordinate an override replaces
	 * is still checked where it is written; the override's own {@code :exclusions} hold
	 * and the replaced coordinate's do not; two parents' different coordinates of an
	 * overridden library are one version.
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
		List<Offer> top = new ArrayList<>();
		for (Map.Entry<Lib, @Nullable Coord> dep : deps.entrySet()) {
			Coord coord = dep.getValue();
			top.add(new Offer(List.of(), dep.getKey(),
					coord == null ? null : procurer.canonicalize(dep.getKey(), coord, dir), Set.of()));
		}
		graph.walk(top);
		return graph.sourcePathOrder();
	}

	/**
	 * Walks the tree a wave at a time: the top-level deps, then what the libraries taken
	 * from them depend on, and so on until a wave takes nothing.
	 */
	private void walk(List<Offer> top) {
		List<Offer> wave = top;
		while (!wave.isEmpty()) {
			List<Opening> openings = new ArrayList<>();
			for (Offer offer : wave) {
				Opening opening = decide(offer);
				if (opening != null) {
					openings.add(opening);
				}
			}
			List<Offer> next = new ArrayList<>();
			for (Opening opening : openings) {
				offersBelow(opening, next);
			}
			wave = next;
		}
	}

	/**
	 * Decides one dependency.
	 * @return what to read for the next wave, or null when the dependency is turned away
	 * or brings nothing new
	 */
	private @Nullable Opening decide(Offer offer) {
		Lib lib = offer.lib();
		Coord coord = coordinateFor(lib, offer.coord());
		if (coord == null) {
			throw new LispReadException("Bad coordinate for library " + lib + ", expected map: nil");
		}
		Entry entry = this.entries.computeIfAbsent(lib, key -> new Entry());
		List<Lib> route = offer.route();
		if (route.isEmpty()) {
			entry.top = true;
		}
		else if (entry.top || offer.barred().contains(lib.base()) || !stillOffered(route)) {
			return null;
		}
		Coord useCoord = this.procurer.manifest(lib, coord);
		String version = version(useCoord);
		Set<Lib> excluded = useCoord.exclusions();
		Set<Lib> own = excluded == null ? Set.of() : excluded;
		Set<Lib> barred = union(offer.barred(), own);
		Standing selected = entry.selected;
		if (selected != null && selected == entry.taken.get(version)) {
			// the selected version again: one more route, and what it may now bring
			selected.routes.add(route);
			Set<Lib> released = new LinkedHashSet<>(selected.withheld);
			released.removeAll(own);
			selected.withheld = intersection(selected.withheld, own);
			return released.isEmpty() ? null : new Opening(selected, route, own, barred, released);
		}
		if (selected != null && compare(lib, useCoord, selected.coord) <= 0) {
			return null;
		}
		Standing standing = entry.taken.computeIfAbsent(version, key -> new Standing(lib, useCoord));
		standing.routes.add(route);
		standing.withheld = own;
		entry.selected = standing;
		this.firstTaken.add(lib);
		if (selected != null) {
			unselectWhatHungFrom(selected, entry);
		}
		return new Opening(standing, route, own, barred, null);
	}

	/**
	 * The coordinate a library is decided with: its override, else the one written, else
	 * its default.
	 */
	private @Nullable Coord coordinateFor(Lib lib, @Nullable Coord written) {
		Coord override = aliasCoordinate(lib, this.overrides, this.canonicalOverrides);
		if (override != null) {
			return override;
		}
		return written != null ? written : aliasCoordinate(lib, this.defaults, this.canonicalDefaults);
	}

	private @Nullable Coord aliasCoordinate(Lib lib, Map<Lib, @Nullable Coord> given, Map<Lib, Coord> canonical) {
		Coord known = canonical.get(lib);
		if (known == null) {
			Coord raw = given.get(lib);
			if (raw == null) {
				return null;
			}
			known = this.procurer.canonicalize(lib, raw, this.dir);
			canonical.put(lib, known);
		}
		return known;
	}

	/**
	 * Whether a dependency hanging from a route is still on offer: its parent's selected
	 * version was taken on that route, and the parent is itself held by some library.
	 */
	private boolean stillOffered(List<Lib> route) {
		Standing parent = selectedAt(route);
		if (parent == null) {
			return false;
		}
		for (List<Lib> parentRoute : parent.routes) {
			if (parentRoute.isEmpty() || selectedAt(parentRoute) != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The selected version of a route's last library, when it was taken on the rest of
	 * the route.
	 */
	private @Nullable Standing selectedAt(List<Lib> route) {
		Entry entry = this.entries.get(route.getLast());
		Standing selected = entry == null ? null : entry.selected;
		return selected != null && selected.routes.contains(route.subList(0, route.size() - 1)) ? selected : null;
	}

	/**
	 * A version was replaced: every other library whose selected version was taken only
	 * on routes through it is no longer selected. The library that replaced it keeps its
	 * selection even when its own route came through the replaced version (a cycle).
	 */
	private void unselectWhatHungFrom(Standing replaced, Entry replacing) {
		List<List<Lib>> through = new ArrayList<>();
		for (List<Lib> route : replaced.routes) {
			through.add(below(route, replaced.lib));
		}
		for (Entry entry : this.entries.values()) {
			Standing selected = entry.selected;
			if (selected == null || entry == replacing) {
				continue;
			}
			boolean elsewhere = false;
			for (List<Lib> route : selected.routes) {
				if (!startsWithAny(route, through)) {
					elsewhere = true;
					break;
				}
			}
			if (!elsewhere) {
				entry.selected = null;
			}
		}
	}

	private static boolean startsWithAny(List<Lib> route, List<List<Lib>> starts) {
		for (List<Lib> start : starts) {
			if (route.size() >= start.size() && route.subList(0, start.size()).equals(start)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Reads what a taken version depends on and adds its offers to the next wave. Every
	 * dependency written with a coordinate is made canonical first, against the library's
	 * own root, also one that is then left out: only after that does the coordinate's own
	 * exclusion drop it.
	 */
	private void offersBelow(Opening opening, List<Offer> next) {
		Standing standing = opening.standing();
		List<Dep> dependencies = standing.dependencies;
		if (dependencies == null) {
			String root = standing.coord.string(":deps/root");
			dependencies = new ArrayList<>();
			for (Dep declared : this.procurer.children(standing.lib, standing.coord)) {
				Coord coord = declared.coord();
				dependencies.add(new Dep(declared.lib(),
						coord == null ? null : this.procurer.canonicalize(declared.lib(), coord, root)));
			}
			standing.dependencies = dependencies;
		}
		List<Lib> route = below(opening.route(), standing.lib);
		Set<Lib> only = opening.only();
		for (Dep dependency : dependencies) {
			Lib named = dependency.lib().base();
			if (only != null ? only.contains(named) : !opening.own().contains(named)) {
				next.add(new Offer(route, dependency.lib(), dependency.coord(), opening.barred()));
			}
		}
	}

	/**
	 * The selected libraries where the source path has them: each at the shortest route
	 * its selected version was taken on, the routes of one length in the order of their
	 * libraries, a library's siblings by name. Their contributions are asked in that
	 * order; then the libraries still to prepare are refused together, named in the order
	 * the walk first took them.
	 */
	private List<Selected> sourcePathOrder() {
		Map<List<Lib>, Standing> places = new TreeMap<>(ROUTE_ORDER);
		for (Entry entry : this.entries.values()) {
			Standing selected = entry.selected;
			if (selected != null) {
				for (List<Lib> route : selected.routes) {
					places.put(below(route, selected.lib), selected);
				}
			}
		}
		Map<Lib, Selected> placed = new LinkedHashMap<>();
		for (Standing standing : places.values()) {
			if (!placed.containsKey(standing.lib)) {
				placed.put(standing.lib, new Selected(standing.lib, standing.coord,
						this.procurer.contribution(standing.lib, standing.coord)));
			}
		}
		List<String> unprepped = new ArrayList<>();
		for (Lib lib : this.firstTaken) {
			Selected selected = placed.get(lib);
			if (selected != null && this.procurer.unprepped(lib, selected.coord())) {
				unprepped.add(lib.toString());
			}
		}
		if (!unprepped.isEmpty()) {
			throw new LispReadException(
					"The following libs must be prepared before use: [" + String.join(" ", unprepped) + "]");
		}
		return new ArrayList<>(placed.values());
	}

	/**
	 * What makes two coordinates of one library the same version: a Maven version as
	 * spelled, a local root, a git repository and commit.
	 */
	private static String version(Coord coord) {
		String kind = coord.type();
		return switch (kind) {
			case "mvn" -> kind + " " + coord.string(":mvn/version");
			case "local" -> kind + " " + coord.string(":local/root");
			default -> kind + " " + coord.string(":git/url") + " " + coord.string(":git/sha");
		};
	}

	/**
	 * Compares a coordinate offered for a library with the selected one, refusing in the
	 * oracle's words when they cannot be compared: Maven versions by
	 * {@link ClojureMavenVersions}; two local roots are equal or no versions of each
	 * other; of two git commits the descendant is newer; two kinds never compare.
	 * @return positive when the offered one is newer
	 */
	private int compare(Lib lib, Coord offered, Coord selected) {
		String kind = offered.type();
		if (!kind.equals(selected.type())) {
			throw new LispReadException(
					"Unable to compare versions for " + lib + ": " + offered.print() + " and " + selected.print());
		}
		if (kind.equals("mvn")) {
			return ClojureMavenVersions.compare(String.valueOf(offered.string(":mvn/version")),
					String.valueOf(selected.string(":mvn/version")));
		}
		if (kind.equals("local")) {
			String offeredRoot = offered.string(":local/root");
			String selectedRoot = selected.string(":local/root");
			if (offeredRoot != null && offeredRoot.equals(selectedRoot)) {
				return 0;
			}
			throw new LispReadException("No known ancestor relationship between local versions for " + lib + ": "
					+ offeredRoot + " and " + selectedRoot);
		}
		return String.valueOf(offered.string(":git/sha")).equals(selected.string(":git/sha")) ? 0
				: this.procurer.compareGit(lib, offered, selected);
	}

	private static List<Lib> below(List<Lib> route, Lib lib) {
		List<Lib> out = new ArrayList<>(route.size() + 1);
		out.addAll(route);
		out.add(lib);
		return List.copyOf(out);
	}

	private static Set<Lib> union(Set<Lib> a, Set<Lib> b) {
		if (b.isEmpty()) {
			return a;
		}
		Set<Lib> out = new LinkedHashSet<>(a);
		out.addAll(b);
		return out;
	}

	private static Set<Lib> intersection(Set<Lib> a, Set<Lib> b) {
		Set<Lib> out = new LinkedHashSet<>(a);
		out.retainAll(b);
		return out;
	}

}
