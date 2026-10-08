package am.ik.rontolisp.clojure;

import java.util.Map;

import am.ik.rontolisp.clojure.ClojureDepsEdn.Lib;
import org.jspecify.annotations.Nullable;

/**
 * The {@code deps.edn} coordinates this front end answers itself, with nothing to fetch:
 * {@code org.clojure/clojure} and the two libraries its jar depends on
 * ({@code spec.alpha}, {@code core.specs.alpha}) are the front end -- a subset of
 * {@code clj} 1.12.6 whatever version a project names, every surface it lacks refused
 * where it is used -- and {@code ring/ring-core} / {@code ring/ring-codec} are the
 * shipped Ring namespaces ({@link ClojureBuiltinNamespaces}). A Maven coordinate of one
 * of these is built in; any other coordinate type of the same library (a local checkout,
 * a git commit) is that library's own source.
 */
final class ClojureBuiltinLibs {

	private static final Lib CLOJURE = new Lib("org.clojure", "clojure");

	private static final Lib RING_CORE = new Lib("ring", "ring-core");

	private static final Lib RING_CODEC = new Lib("ring", "ring-codec");

	/**
	 * Each built-in library with the version shipped, or the empty string for the front
	 * end itself, which stands in for any version.
	 */
	private static final Map<Lib, String> SHIPPED = Map.of(CLOJURE, "", new Lib("org.clojure", "spec.alpha"), "",
			new Lib("org.clojure", "core.specs.alpha"), "", RING_CORE, "1.15.5", RING_CODEC, "1.3.0");

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
