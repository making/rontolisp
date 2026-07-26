package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The lite dependency-shim libraries behind the built-in ASDF systems that real
 * third-party libraries name in {@code :depends-on} clauses: {@code closer-mop} (no slot
 * metaobjects -- class introspection yields no slots), {@code flexi-streams} (a flexi
 * stream is the underlying stream), {@code float-features} (wrappers over the IEEE 754
 * bit primitives) and {@code trivial-gray-streams} (the Gray base classes + generics the
 * {@code write-string} built-in dispatches to for CLOS-instance streams). Each is a small
 * canonical-shape {@code .lisp} resource next to this class (the {@code usocket.lisp}
 * pattern); the packages are seeded in {@code PackageRegistry}.
 *
 * <p>
 * Besides whole shim systems, this class also substitutes LEAF MODULES -- individual
 * component files INSIDE a real third-party system whose contract with the rest of that
 * system is a few package-qualified functions. The jzon numeric leaves
 * ({@code eisel-lemire.lisp}/{@code ratio-to-double.lisp}/{@code schubfach.lisp}) are
 * replaced with shims over rontolisp's native float arithmetic and printer: the
 * originals' {@code #.}-generated power-of-ten tables reference a load-time special the
 * macro-time evaluator cannot see, and their u64/u128 bit algorithms are beyond the WASM
 * numeric model, so the real files are interpreter-only while the shims run on every
 * backend. Each shim carries the replaced file's {@code defpackage} (so the package
 * registers exactly as the original would) followed by canonical-shape qualified defuns;
 * both system loaders ({@code LispEvaluator.loadSystem} and
 * {@code cli.LoadInliner.spliceSystem}) consult {@link #leafModuleForms} before reading a
 * component file. Tradeoff: float text is rontolisp's cross-backend-identical shape, not
 * schubfach's shortest-round-trip string, and parsing extreme exponents is a few ulps off
 * eisel-lemire's exact rounding.
 *
 * <p>
 * ironclad's core carries two more leaves, for a different reason: the file is not
 * unportable, it is merely far too big to drag in for the handful of names the loadable
 * slice needs. {@code src/public-key/public-key.lisp} is 3,065 lines of RSA/DSA/ECC of
 * which the slice wants exactly the two integer/octet-vector converters cl-postgres'
 * SCRAM client proof calls, so the shim reproduces those two VERBATIM and nothing else;
 * {@code src/prng/prng.lisp} would pull in the Fortuna CSPRNG, and the slice needs only
 * {@code random-data} to EXIST (it is the never-taken default of
 * {@code pbkdf2-hash-password}'s {@code :salt}), so that shim signals rather than hand
 * out non-cryptographic bytes under a name that promises unpredictability. See
 * {@code .kb/asdf.md}.
 *
 * <p>
 * A third, lighter form of substitution rewrites INDIVIDUAL FORMS of a real component and
 * leaves the rest of the file verbatim: {@link #rewriteComponentSource} hands uax-15's
 * table-building forms to {@link Uax15Tables}, which derives the same tables from the
 * same bundled Unicode data at compile time and emits them as data.
 */
public final class ShimLibraries {

	/**
	 * Built-in system name (canonical lower-case coerce-name form -- a separate namespace
	 * from the upcase-canonical package names) to its classpath resource.
	 */
	private static final Map<String, String> RESOURCES = Map.of("closer-mop", "closer-mop.lisp", "flexi-streams",
			"flexi-streams.lisp", "float-features", "float-features.lisp", "trivial-gray-streams",
			"trivial-gray-streams.lisp");

	/**
	 * Leaf-module substitutions: system name to (component file relative to the system's
	 * base directory, shim classpath resource).
	 */
	private static final Map<String, Map<String, String>> LEAF_MODULES = Map.of("com.inuoe.jzon",
			Map.of("eisel-lemire.lisp", "jzon-eisel-lemire.lisp", "ratio-to-double.lisp", "jzon-ratio-to-double.lisp",
					"schubfach.lisp", "jzon-schubfach.lisp"),
			"ironclad/core", Map.of("src/prng/prng.lisp", "ironclad-prng.lisp", "src/public-key/public-key.lisp",
					"ironclad-public-key.lisp"));

	private static final Map<String, List<LispVal>> CACHE = new ConcurrentHashMap<>();

	private ShimLibraries() {
	}

	/**
	 * Returns whether the named system is one of the dependency shims.
	 * @param name the system name (canonical lower-case)
	 * @return {@code true} when a shim satisfies it
	 */
	public static boolean isShim(String name) {
		return RESOURCES.containsKey(name);
	}

	/**
	 * Returns the parsed library definitions of the named shim system. The source is in
	 * canonical shape (qualified public names), so it needs no package resolution. Parsed
	 * once and cached.
	 * @param name a name for which {@link #isShim} is true
	 * @return the library forms
	 */
	public static List<LispVal> forms(String name) {
		String resource = RESOURCES.get(name);
		if (resource == null) {
			throw new IllegalArgumentException("Not a shim system: " + name);
		}
		return CACHE.computeIfAbsent(name, key -> {
			List<LispVal> parsed = LispReader.readAllFromString(readSource(resource), Features.INTERPRETER);
			if ("trivial-gray-streams".equals(key)) {
				// The adapter's superclasses and delegation targets are rontolisp's
				// own Gray protocol: its definitions must precede the adapter's.
				List<LispVal> combined = new java.util.ArrayList<>(GrayStreamsLibrary.forms());
				combined.addAll(parsed);
				return List.copyOf(combined);
			}
			return parsed;
		});
	}

	/**
	 * Returns the parsed shim forms replacing the given component file of the named
	 * system, or {@code null} when the component is not substituted. The forms start with
	 * the replaced file's {@code defpackage} and must be resolved through the package
	 * resolver in order (like any loaded source), so the package registers before the
	 * dependent components resolve against it.
	 * @param systemName the ASDF system name (canonical lower-case)
	 * @param componentFile the component source file, relative to the system's base
	 * directory
	 * @return the shim forms, or {@code null} when the real file should be loaded
	 */
	@Nullable public static List<LispVal> leafModuleForms(String systemName, String componentFile) {
		Map<String, String> modules = LEAF_MODULES.get(systemName);
		String resource = modules == null ? null : modules.get(componentFile);
		if (resource == null) {
			return null;
		}
		return CACHE.computeIfAbsent(resource,
				key -> LispReader.readAllFromString(readSource(key), Features.INTERPRETER));
	}

	/**
	 * Returns the source of the given component of the named system, REWRITTEN when the
	 * component is one whose load-time table building rontolisp derives at compile time
	 * instead ({@code uax-15}, see {@link Uax15Tables}). Unlike {@link #leafModuleForms},
	 * which substitutes a whole component with canonical-shape forms, this rewrites the
	 * real source in place and hands it back to the caller's normal read: everything the
	 * rewrite does not touch stays verbatim upstream, and it keeps the package resolution
	 * a real component file gets.
	 * @param systemName the ASDF system name (canonical lower-case)
	 * @param componentFile the component source file, relative to the system's base
	 * directory
	 * @param source the real component source
	 * @param baseDir the system's base directory (bundled data files resolve against it)
	 * @param loader the loader those data files are read through
	 * @return the source to read, the given one when nothing is rewritten
	 */
	public static String rewriteComponentSource(String systemName, String componentFile, String source,
			@Nullable String baseDir, SourceLoader loader) {
		if (!Uax15Tables.SYSTEM.equals(systemName)) {
			return source;
		}
		String rewritten = Uax15Tables.rewrite(componentFile, source, baseDir, loader);
		return rewritten == null ? source : rewritten;
	}

	private static String readSource(String resource) {
		try (InputStream in = ShimLibraries.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException(resource + " is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
