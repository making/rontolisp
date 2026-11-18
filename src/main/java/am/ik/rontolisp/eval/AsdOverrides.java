package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

/**
 * Hand-authored replacements for {@code .asd} files whose systems this subset cannot take
 * as written. A real library's {@code .asd} is sometimes a program that reaches past the
 * subset (ironclad's defines a system class and generates its defsystems with a macro;
 * {@link AsdfSystems} runs the file, but parses each {@code defsystem} as data and models
 * no system class). For those libraries this registry substitutes a bundled replacement
 * source -- written in the supported defsystem subset, declaring the subsystems rontolisp
 * can actually load -- at {@link AsdfSystems#locate} time. The located path (and
 * therefore the base directory the component files resolve against) is kept, so the REAL
 * library sources are loaded; only the system metadata is redeclared. Both system loaders
 * (the interpreter's {@code loadSystem} and the compile-time {@code LoadInliner}) go
 * through {@code locate}, so one entry serves every backend.
 *
 * <p>
 * Where the difference is small and the upstream file is under a copyleft licence
 * (clsql's LLGPL, petalisp's AGPL-3.0), no near-copy is bundled: {@link #REWRITES} edits
 * the located file's own text at load time, as {@link PetalispForms} edits a component,
 * by markers that must occur exactly once, so an upstream release that moves one fails
 * loudly instead of loading the unedited file.
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
			Map.entry("cffi.asd", "cffi-rontolisp.asd"),
			// Guards its whole defsystem with an implementation list, so here the file
			// defines nothing. The replacement is cffi's own uffi-compat/uffi.asd: the
			// same system name over cffi-uffi-compat, whose package has the nickname
			// uffi.
			Map.entry("uffi.asd", "uffi-cffi.asd"),
			// Not unparseable but MIS-DECLARED for a .wasm: its cffi dependency copies
			// foreign libraries next to an executable, which a module has none of (its
			// mezzano arm), and a .wasm refuses ffi: by name. The replacement skips cffi
			// there; every other target reads upstream's dependencies.
			Map.entry("deploy.asd", "deploy-rontolisp.asd"));

	/**
	 * The {@code .asd} file name to the edit of its own text: the systems that load as
	 * upstream declares them but for a difference small enough to state as a few marker
	 * edits.
	 */
	private static final Map<String, UnaryOperator<String>> REWRITES = Map.of(
			// MIS-DECLARED here: an eval-when loads uffi for a perform method reading
			// ~/.clsql-init.lisp, which does not run here, and no MOP system is named
			// because package.lisp uses one MOP package per implementation (ClsqlForms).
			// Both are skipped on rontolisp and closer-mop is declared; uffi is left to
			// the database backends, so the core reaches wasm.
			"clsql.asd",
			source -> edit("clsql.asd",
					edit("clsql.asd",
							edit("clsql.asd", source, "(eval-when (:compile-toplevel :load-toplevel :execute)",
									"#-rontolisp\n(eval-when (:compile-toplevel :load-toplevel :execute)"),
							"(defmethod perform :after", "#-rontolisp\n(defmethod perform :after"),
					"(defsystem clsql\n", "(defsystem clsql\n    :depends-on (closer-mop)\n"),
			// MIS-DECLARED here: static-vectors serves the SBCL and CCL arms that read an
			// array's storage by its address, and its own .asd refuses rontolisp. The
			// rontolisp arm (PetalispForms) reads none, so the dependency is skipped;
			// every other dependency and component is upstream's.
			"petalisp.native-backend.asd", source -> edit("petalisp.native-backend.asd", source, "\"static-vectors\"",
					"#-rontolisp \"static-vectors\""));

	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

	private AsdOverrides() {
	}

	/**
	 * The text a located {@code .asd} is read as: its bundled replacement, its own text
	 * edited ({@link #REWRITES}), or its own text.
	 * @param asdFileName the located file's name (e.g. {@code "clsql.asd"})
	 * @param source the located file's text
	 * @return the text to read
	 * @throws IllegalStateException when an edit's marker is not in the text exactly once
	 */
	public static String effectiveSource(String asdFileName, String source) {
		String replacement = replacementSource(asdFileName);
		if (replacement != null) {
			return replacement;
		}
		UnaryOperator<String> rewrite = REWRITES.get(asdFileName);
		return rewrite == null ? source : rewrite.apply(source);
	}

	private static String edit(String asdFileName, String source, String marker, String replacement) {
		int first = source.indexOf(marker);
		if (first < 0 || source.indexOf(marker, first + 1) >= 0) {
			throw new IllegalStateException(asdFileName + ": expected exactly one occurrence of the marker <<<" + marker
					+ ">>> (found " + (first < 0 ? 0 : 2) + "). The upstream release moved it; update "
					+ AsdOverrides.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		return source.substring(0, first) + replacement + source.substring(first + marker.length());
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
