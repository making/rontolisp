package am.ik.rontolisp.eval;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import am.ik.rontolisp.LispVal;

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
	private static final Map<String, Supplier<List<LispVal>>> SYSTEMS = Map.of("usocket", UsocketLibrary::forms,
			"closer-mop", () -> ShimLibraries.forms("closer-mop"), "flexi-streams",
			() -> ShimLibraries.forms("flexi-streams"), "float-features", () -> ShimLibraries.forms("float-features"),
			"trivial-gray-streams", () -> ShimLibraries.forms("trivial-gray-streams"),
			// The uiop package stub is seeded in PackageRegistry; the system contributes
			// no forms (real libraries only name it so its symbols resolve).
			"uiop", List::of);

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
	 * Returns the library forms satisfying the named built-in system.
	 * @param name a name for which {@link #isBuiltin} is true
	 * @return the library forms
	 */
	public static List<LispVal> forms(String name) {
		Supplier<List<LispVal>> supplier = SYSTEMS.get(name);
		if (supplier == null) {
			throw new IllegalArgumentException("Not a built-in system: " + name);
		}
		return supplier.get();
	}

}
