package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * Hand-authored replacements for {@code .asd} files that cannot be parsed as plain data.
 * A real library's {@code .asd} is sometimes an executable program (ironclad's defines
 * component classes and generates its defsystems with a macro); {@link AsdfSystems}
 * deliberately never evaluates system definitions, so such a file cannot be read at all.
 * For those libraries this registry substitutes a bundled replacement source -- written
 * in the supported defsystem subset, declaring the subsystems rontolisp can actually load
 * -- at {@link AsdfSystems#locate} time. The located path (and therefore the base
 * directory the component files resolve against) is kept, so the REAL library sources are
 * loaded; only the system metadata is redeclared. Both system loaders (the interpreter's
 * {@code loadSystem} and the compile-time {@code LoadInliner}) go through {@code locate},
 * so one entry serves every backend.
 */
public final class AsdOverrides {

	/**
	 * The {@code .asd} file name (as computed from the primary system name by
	 * {@code locate}) to the classpath resource holding the replacement source.
	 */
	private static final Map<String, String> RESOURCES = Map.ofEntries(Map.entry("ironclad.asd", "ironclad-slice.asd"),
			// Not unparseable but UNDER-DECLARED: sql-string.lisp needs alexandria and
			// data-types.lisp needs cl-ppcre, neither in the upstream :depends-on (a
			// full CL image nearly always has them loaded already). The replacement
			// declares the true set so one (ql:quickload "cl-postgres") resolves on
			// the eagerly-resolving compile paths too.
			Map.entry("cl-postgres.asd", "cl-postgres-deps.asd"),
			// Unparseable AND under-declared: a top-level eval-when pushes the
			// :postmodern-thread-safe / :postmodern-use-mop features per
			// implementation. The replacement takes both decisions statically (a
			// *features* push would be invisible to the reader anyway) and declares
			// cl-ppcre + uax-15, which the sources call but the .asd never names.
			Map.entry("postmodern.asd", "postmodern-deps.asd"),
			// Not unparseable but re-routed: upstream depends on trivia.balland2006
			// (the match-clause OPTIMIZER), which needs iterate + type-i -- a large
			// substrate investment buying zero semantics. The replacement maps the
			// system to trivia.trivial, upstream's own sanctioned base system.
			// Re-evaluation trigger in the replacement source and .kb/asdf.md.
			Map.entry("trivia.asd", "trivia-trivial.asd"),
			// Not unparseable but MIS-DECIDED here: upstream selects its cache
			// (per-thread vs single) from a thread-capability feature expression that
			// can never match rontolisp's feature set, so the verbatim parse picks the
			// single-threaded cache on backends that really run concurrent handlers.
			// The replacement takes the decision per backend; reasons in the file.
			Map.entry("dbi.asd", "dbi-deps.asd"),
			// Not unparseable at all -- replaced only to ADD the opt-in
			// "tiny-routes/lite" secondary system (same components, no :cl-ppcre,
			// path-template.lisp substituted with the ppcre-free matcher via
			// ShimLibraries.leafModuleForms). The primary system is declared
			// verbatim, so plain (ql:quickload "tiny-routes") is unchanged.
			Map.entry("tiny-routes.asd", "tiny-routes-lite.asd"),
			// Not unparseable but UNREACHABLE as written: cl-unicode/build declares
			// :output-files and performs load-op by writing the three components the
			// primary system names and the release does not ship, wired in through a
			// component-depends-on method on prepare-op. None of that exists in the
			// defsystem-as-data subset; rontolisp generates those three components
			// from the same bundled UCD data instead (ClUnicodeTables), so the
			// replacement drops the build system and the dependency on it.
			Map.entry("cl-unicode.asd", "cl-unicode-built.asd"),
			// Unparseable for two independent reasons: it opens with (error
			// "Sorry, this Lisp is not yet supported") for an implementation
			// upstream's own list does not name, and it ends in a defmethod
			// version-satisfies. The replacement declares the same components with
			// rontolisp's cffi-sys backend as the implementation one, so upstream's
			// portable source loads unmodified (.kb/cffi.md).
			Map.entry("cffi.asd", "cffi-rontolisp.asd"));

	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

	private AsdOverrides() {
	}

	/**
	 * Returns the replacement {@code .asd} source for the given file name, or
	 * {@code null} when the real file should be parsed.
	 * @param asdFileName the located file's name (e.g. {@code "ironclad.asd"})
	 * @return the replacement source, or {@code null}
	 */
	@Nullable public static String replacementSource(String asdFileName) {
		String resource = RESOURCES.get(asdFileName);
		if (resource == null) {
			return null;
		}
		return CACHE.computeIfAbsent(resource, AsdOverrides::readSource);
	}

	private static String readSource(String resource) {
		try (InputStream in = AsdOverrides.class.getResourceAsStream(resource)) {
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
