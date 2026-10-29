package am.ik.rontolisp.clojure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.clojure.ClojureDepsEdn.Coord;
import am.ik.rontolisp.clojure.ClojureDepsEdn.Lib;
import am.ik.rontolisp.clojure.ClojureDepsGraph.Dep;
import org.jspecify.annotations.Nullable;

/**
 * The {@code deps.edn} coordinates this front end answers itself, with nothing to fetch:
 * {@code org.clojure/clojure} is the front end -- a subset of {@code clj} 1.12.6 whatever
 * version a project names, every surface it lacks refused where it is used -- and
 * {@code ring/ring-core} / {@code ring/ring-codec} are the shipped Ring namespaces
 * ({@link ClojureBuiltinNamespaces}). A Maven coordinate of one of these is built in; any
 * other coordinate type of the same library (a local checkout, a git commit) is that
 * library's own source.
 *
 * <p>
 * The libraries clojure.jar itself depends on, {@code spec.alpha} and
 * {@code core.specs.alpha}, are no part of the front end: they are the built-in
 * {@code org.clojure/clojure}'s dependencies, as its 1.12.6 POM declares them, selected
 * like any other library's and read from their own jars. Every program's tree holds them,
 * so their jars are fetched only when a lookup first asks for one of their namespaces
 * ({@link #deferredNamespaces}), not before every program.
 */
final class ClojureBuiltinLibs {

	private static final Lib CLOJURE = new Lib("org.clojure", "clojure");

	private static final Lib RING_CORE = new Lib("ring", "ring-core");

	private static final Lib RING_CODEC = new Lib("ring", "ring-codec");

	private static final Lib SPEC = new Lib("org.clojure", "spec.alpha");

	private static final Lib CORE_SPECS = new Lib("org.clojure", "core.specs.alpha");

	/**
	 * Each built-in library with the version shipped, or the empty string for the front
	 * end itself, which stands in for any version.
	 */
	private static final Map<Lib, String> SHIPPED = Map.of(CLOJURE, "", RING_CORE, "1.15.5", RING_CODEC, "1.3.0");

	/**
	 * The dependencies of {@code org.clojure/clojure} 1.12.6 (its POM's compile scope, in
	 * its order), each with the version it names and the namespaces its jar holds. Their
	 * own POMs declare no compile or runtime dependency (clojure is {@code provided},
	 * test.check {@code test}).
	 */
	private static final SequencedMap<Lib, Deferred> CLOJURE_DEPENDENCIES = clojureDependencies();

	/**
	 * A dependency of the built-in clojure: the version clojure's POM names and the
	 * namespaces that version's jar holds.
	 */
	private record Deferred(String version, Set<String> namespaces) {
	}

	private static SequencedMap<Lib, Deferred> clojureDependencies() {
		SequencedMap<Lib, Deferred> deps = new LinkedHashMap<>();
		deps.put(SPEC, new Deferred("0.5.238",
				Set.of("clojure.spec.alpha", "clojure.spec.gen.alpha", "clojure.spec.test.alpha")));
		deps.put(CORE_SPECS, new Deferred("0.4.74", Set.of("clojure.core.specs.alpha")));
		return deps;
	}

	private ClojureBuiltinLibs() {
	}

	/**
	 * Whether a library is built in.
	 * @param lib the library
	 * @return whether its namespaces are this front end's own
	 */
	static boolean isBuiltin(Lib lib) {
		return SHIPPED.containsKey(lib);
	}

	/**
	 * The dependencies of a built-in library, known without fetching anything: the
	 * built-in clojure's own two, none for the shipped Ring files.
	 * @param lib a built-in library
	 * @return its dependencies, in its POM's order
	 */
	static List<Dep> children(Lib lib) {
		if (!lib.equals(CLOJURE)) {
			return List.of();
		}
		return CLOJURE_DEPENDENCIES.entrySet().stream().map(dep -> {
			SequencedMap<String, LispVal> entries = new LinkedHashMap<>();
			entries.put(":mvn/version", LispString.literal(dep.getValue().version()));
			return new Dep(dep.getKey(), new Coord(entries));
		}).toList();
	}

	/**
	 * The namespaces of a dependency of the built-in clojure at the version clojure's POM
	 * names, whose jar is fetched when a lookup first asks for one of them; such a
	 * coordinate's own dependencies are known to be none.
	 * @param lib the library
	 * @param coord its coordinate
	 * @return the namespaces its jar holds, or null for any other coordinate
	 */
	static @Nullable Set<String> deferredNamespaces(Lib lib, Coord coord) {
		Deferred deferred = CLOJURE_DEPENDENCIES.get(lib);
		return deferred != null && coord.type().equals("mvn") && deferred.version().equals(coord.string(":mvn/version"))
				? deferred.namespaces() : null;
	}

	/**
	 * The version of a built-in library this front end ships.
	 * @param lib the library
	 * @return the version, or null when any version is the front end itself (or the
	 * library is not built in)
	 */
	static @Nullable String shippedVersion(Lib lib) {
		String version = SHIPPED.get(lib);
		return version == null || version.isEmpty() ? null : version;
	}

	/**
	 * The library a built-in namespace belongs to: {@code ring.util.codec} is
	 * ring-codec's, every other Ring namespace ring-core's, anything else the Clojure
	 * jar's.
	 * @param ns a namespace a built-in file defines
	 * @return its library
	 */
	static Lib libOf(String ns) {
		if (ns.equals("ring.util.codec")) {
			return RING_CODEC;
		}
		return ns.startsWith("ring.") ? RING_CORE : CLOJURE;
	}

}
