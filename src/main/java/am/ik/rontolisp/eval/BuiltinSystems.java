package am.ik.rontolisp.eval;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;

/**
 * The ASDF systems provided by rontolisp itself: a system name in this registry is
 * satisfied by an embedded Lisp-source library instead of a {@code NAME.asd} lookup, so
 * {@code (asdf:load-system "usocket")}, {@code (ql:quickload :usocket)} and a
 * {@code :depends-on ("usocket")} clause in a third-party {@code .asd} resolve without
 * touching the file system or the network. Consumers: the compile-time
 * {@code LoadInliner} splices {@link #forms} in place of the system's component files;
 * the interpreter's {@code loadSystem}/{@code quickload} evaluate the same library
 * through its lazy-load hook and mark the system loaded.
 */
public final class BuiltinSystems {

	// Keyed by the ASDF system name -- always the canonical lower-case coerce-name form
	// (system names are a separate namespace from the upcase-canonical package names).
	private static final Map<String, Function<Features, List<LispVal>>> SYSTEMS = Map.of("usocket",
			features -> UsocketLibrary.forms(), "closer-mop", features -> ShimLibraries.forms("closer-mop", features),
			"flexi-streams", features -> ShimLibraries.forms("flexi-streams", features), "float-features",
			features -> ShimLibraries.forms("float-features", features), "trivial-gray-streams",
			features -> ShimLibraries.forms("trivial-gray-streams", features), "bordeaux-threads",
			features -> ShimLibraries.forms("bordeaux-threads", features), "babel",
			features -> ShimLibraries.forms("babel", features), "swank",
			features -> ShimLibraries.forms("swank", features),
			// The uiop package stub is seeded in PackageRegistry; the system contributes
			// no forms (real libraries only name it so its symbols resolve).
			"uiop", features -> List.of());

	private BuiltinSystems() {
	}

	/**
	 * Returns whether the named system is provided by rontolisp itself.
	 * @param name the system name as requested (canonical lower-case)
	 * @return {@code true} when the system is built in
	 */
	public static boolean isBuiltin(String name) {
		return SYSTEMS.containsKey(name);
	}

	/**
	 * Returns the library forms satisfying the named built-in system, read with the
	 * target backend's reader features (the interpreter's when loading, the compile
	 * target's when splicing -- see {@link ShimLibraries#forms}).
	 * @param name a name for which {@link #isBuiltin} is true
	 * @param features the reader features of the target backend
	 * @return the library forms
	 */
	public static List<LispVal> forms(String name, Features features) {
		Function<Features, List<LispVal>> supplier = SYSTEMS.get(name);
		if (supplier == null) {
			throw new IllegalArgumentException("Not a built-in system: " + name);
		}
		return supplier.apply(features);
	}

}
