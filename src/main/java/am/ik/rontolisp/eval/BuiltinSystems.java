package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
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

	/** The built-in system name of the platform-feature announcer. */
	static final String TRIVIAL_FEATURES = "trivial-features";

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
			// The CLtL2 environment-API shim (trivia.level2's dependency): the real
			// library re-exports host-implementation members that do not exist here.
			Map.entry("trivial-cltl2", features -> ShimLibraries.forms("trivial-cltl2", features)),
			// The mgl-pax package stub (trivial-utf-8's hard dependency, on the uuid /
			// mito path): real mgl-pax-bootstrap's .asd declares :around-compile, a
			// compile hook the defsystem-as-data front-end cannot honor -- the swank
			// precedent.
			Map.entry("mgl-pax-bootstrap", features -> ShimLibraries.forms("mgl-pax-bootstrap", features)),
			// GC finalizers as no-ops (dbd-postgres's dependency): the real library's
			// .asd errors under rontolisp's features, and no backend has GC hooks.
			Map.entry("trivial-garbage", features -> ShimLibraries.forms("trivial-garbage", features)),
			// Client-side TLS over rontolisp:tls-upgrade (dexador/drakma's https
			// path): the real cl+ssl is a CFFI binding to OpenSSL, unloadable here.
			Map.entry("cl+ssl", features -> ShimLibraries.forms("cl+ssl", features)),
			// The platform-feature announcer (dexador's :defsystem-depends-on entry).
			// Its whole content is the announcement, so the forms are generated from
			// the same list the .asd parse reads (DECLARED_FEATURES).
			Map.entry(TRIVIAL_FEATURES, features -> trivialFeaturesForms()),
			// The Clack handler backend: both the hyphenated ecosystem spelling and the
			// dotted spelling lack's find-package-or-load derives from the package name
			// resolve to the one shim (see ShimLibraries.RESOURCES).
			Map.entry(LispNames.CLACK_HANDLER_RONTOLISP_SYSTEM,
					features -> ShimLibraries.forms(LispNames.CLACK_HANDLER_RONTOLISP_SYSTEM, features)),
			Map.entry(LispNames.CLACK_HANDLER_RONTOLISP_DOTTED_SYSTEM,
					features -> ShimLibraries.forms(LispNames.CLACK_HANDLER_RONTOLISP_DOTTED_SYSTEM, features)),
			Map.entry(LispNames.CLACK_HANDLER_REACTOR_SYSTEM,
					features -> ShimLibraries.forms(LispNames.CLACK_HANDLER_REACTOR_SYSTEM, features)),
			Map.entry(LispNames.CLACK_HANDLER_REACTOR_DOTTED_SYSTEM,
					features -> ShimLibraries.forms(LispNames.CLACK_HANDLER_REACTOR_DOTTED_SYSTEM, features)),
			// The uiop package stub is seeded in PackageRegistry; the system contributes
			// no forms (real libraries only name it so its symbols resolve).
			Map.entry("uiop", features -> List.of()));

	/**
	 * The {@code :depends-on} edges BETWEEN built-in systems. Only one exists: the
	 * flexi-streams shim's in-memory octet streams are real Gray streams, so rontolisp's
	 * Gray protocol has to be defined before its {@code defclass} runs -- and
	 * {@code trivial-gray-streams} is what splices the protocol (see
	 * {@link ShimLibraries#forms}). Real flexi-streams declares exactly this dependency
	 * in its own {@code .asd}, for the same reason.
	 */
	private static final Map<String, List<String>> DEPENDENCIES = Map.of("flexi-streams",
			List.of("trivial-gray-streams"));

	/**
	 * The features a built-in system ANNOUNCES to whatever names it in
	 * {@code :defsystem-depends-on} -- the read-time half of such a dependency, and the
	 * only half a parse can honor (a real third-party system announces its features by
	 * RUNNING, and a {@code .asd} is data here, never evaluated).
	 * <p>
	 * One entry: upstream trivial-features exists to make {@code *features*} agree across
	 * implementations, and its {@code .asd} ends in
	 * {@code (error "Sorry, your Lisp is not supported")} for one it does not recognize
	 * -- so the shim states directly what rontolisp's own model already implies, rather
	 * than probing anything:
	 * <ul>
	 * <li>{@code :unix} -- every backend's file, path and environment surface is
	 * POSIX-shaped ({@code /} separators, {@code uiop:getenv}, WASI's POSIX model), and
	 * no backend is Windows; {@code #-windows} / {@code #+unix} is the branch a consumer
	 * must take here.</li>
	 * <li>{@code :little-endian} -- the two places a program can see machine layout at
	 * all (WASM linear memory and the {@code :bytes} boundary of a reactor import) are
	 * little-endian by the wasm spec, and the JVM backend exposes no such view to
	 * disagree.</li>
	 * <li>{@code :64-bit} -- every rontolisp backend has 64-bit fixnums, and every
	 * pointer-shaped value (a JVM object reference, a WASM {@code i31}/{@code externref},
	 * an FFM {@code MemorySegment} address) is 8 bytes wherever pointers exist at all.
	 * {@code cffi}'s {@code types.lisp} reads exactly this feature to pick
	 * {@code :size}'s base type, and no rontolisp backend could ever answer the 32-bit
	 * half true, so stating it is not a claim a program could catch out.</li>
	 * </ul>
	 * The CPU name ({@code :x86-64}) stays absent: rontolisp has no instruction-set
	 * surface to describe, only the word-size fact {@code :64-bit} states.
	 * {@code :32-bit} stays absent too -- rontolisp is never that.
	 */
	private static final Map<String, List<String>> DECLARED_FEATURES = Map.of(TRIVIAL_FEATURES,
			List.of("unix", "little-endian", "64-bit"));

	private BuiltinSystems() {
	}

	/**
	 * The features the named built-in systems announce, merged in order. A name that is
	 * not built in (or announces nothing) contributes nothing.
	 * @param names the system names, canonical lower-case
	 * @return the announced feature names, without the leading colon
	 */
	public static List<String> declaredFeatures(List<String> names) {
		List<String> declared = new ArrayList<>();
		for (String name : names) {
			for (String feature : DECLARED_FEATURES.getOrDefault(name, List.of())) {
				if (!declared.contains(feature)) {
					declared.add(feature);
				}
			}
		}
		return List.copyOf(declared);
	}

	/**
	 * The trivial-features shim's forms: the run-time half of the announcement, one
	 * {@code (pushnew :F *features*)} per declared name, so a program that reads
	 * {@code *features*} at run time sees what its {@code #+} conditionals saw while it
	 * was read ({@code *features*} is an ordinary special variable on every backend,
	 * {@code .kb/reader-features.md}).
	 */
	private static List<LispVal> trivialFeaturesForms() {
		List<LispVal> forms = new ArrayList<>();
		for (String feature : DECLARED_FEATURES.getOrDefault(TRIVIAL_FEATURES, List.of())) {
			LispVal push = new LispCons(new LispSymbol(LispNames.PUSHNEW),
					new LispCons(new LispSymbol(":" + feature.toUpperCase(Locale.ROOT)),
							new LispCons(new LispSymbol(LispNames.FEATURES_VAR), LispNil.INSTANCE)));
			forms.add(push);
		}
		return List.copyOf(forms);
	}

	/**
	 * The built-in systems the named one depends on, to be loaded (or spliced) first.
	 * @param name a name for which {@link #isBuiltin} is true
	 * @return the dependency names, possibly empty
	 */
	public static List<String> dependencies(String name) {
		return DEPENDENCIES.getOrDefault(name, List.of());
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
