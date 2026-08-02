package am.ik.rontolisp.eval;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import am.ik.rontolisp.LispNames;
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
	private static final Map<String, Function<Features, List<LispVal>>> SYSTEMS = Map.ofEntries(
			Map.entry("usocket", (Function<Features, List<LispVal>>) features -> UsocketLibrary.forms()),
			Map.entry("closer-mop", features -> ShimLibraries.forms("closer-mop", features)),
			Map.entry("flexi-streams", features -> ShimLibraries.forms("flexi-streams", features)),
			Map.entry("float-features", features -> ShimLibraries.forms("float-features", features)),
			Map.entry("trivial-gray-streams", features -> ShimLibraries.forms("trivial-gray-streams", features)),
			Map.entry("bordeaux-threads", features -> ShimLibraries.forms("bordeaux-threads", features)),
			Map.entry("babel", features -> ShimLibraries.forms("babel", features)),
			Map.entry("swank", features -> ShimLibraries.forms("swank", features)),
			// The Clack handler backend: both the hyphenated ecosystem spelling and the
			// dotted spelling lack's find-package-or-load derives from the package name
			// resolve to the one shim (see ShimLibraries.RESOURCES).
			Map.entry(LispNames.CLACK_HANDLER_RONTOLISP_SYSTEM,
					features -> ShimLibraries.forms(LispNames.CLACK_HANDLER_RONTOLISP_SYSTEM, features)),
			Map.entry(LispNames.CLACK_HANDLER_RONTOLISP_DOTTED_SYSTEM,
					features -> ShimLibraries.forms(LispNames.CLACK_HANDLER_RONTOLISP_DOTTED_SYSTEM, features)),
			// The uiop package stub is seeded in PackageRegistry; the system contributes
			// no forms (real libraries only name it so its symbols resolve).
			Map.entry("uiop", features -> List.of()));

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
