package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.function.Predicate;

import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The EXPERIMENTAL Scheme front end: a subset of R7RS-small {@code (scheme base)} and
 * {@code (scheme write)}, read case-sensitively and LOWERED to the Common Lisp core forms
 * every pipeline already consumes. Partial conformance by design and no compatibility
 * promise; the subset, the lowering table and the stated deviations are in
 * {@code .kb/scheme-frontend.md} and on the user-facing page
 * {@code doc/en/guides/scheme.md}.
 *
 * <p>
 * This package depends on the AST types and {@code reader} only. It is reached through
 * the source-language seam ({@code eval/SourceLanguage.SCHEME}), never directly, and its
 * run-time helpers are Common Lisp source shipped like every other library
 * ({@code eval/SchemeLibrary}).
 */
public final class Scheme {

	private Scheme() {
	}

	/**
	 * Reads a Scheme program and lowers it to Common Lisp core forms.
	 * @param source the program text
	 * @param file the origin file for diagnostics, or {@code null} when unknown
	 * @return the top-level forms
	 */
	public static List<LispVal> read(String source, @Nullable String file) {
		return SchemeLowering.ofFile(new SchemeReader(source, file)).lower();
	}

	/**
	 * Starts an interactive session -- a REPL, a playground -- that reads one buffer at a
	 * time.
	 * @return the session
	 */
	public static SchemeSession session() {
		return new SchemeSession();
	}

	/**
	 * The definitions {@code scheme.lisp} needs from this package's tables, generated so
	 * each table is spelled once: the run-time procedure table behind {@code eval}
	 * ({@code rontolisp::%scheme-builtin}) and the library-name predicate behind
	 * {@code (environment ...)} ({@code rontolisp::%scheme-library-p}). Appended to the
	 * library's forms by {@code eval/SchemeLibrary}, in the same canonical shape.
	 * @param spelled whether the program the forms are for spells a (mangled) procedure
	 * name -- the table holds only those entries; the interpreter, which loads the
	 * library once for every program, passes a predicate that accepts everything
	 * @return the forms
	 */
	public static List<LispVal> runtimeForms(Predicate<String> spelled) {
		List<LispVal> forms = new ArrayList<>(SchemeBuiltins.runtimeForms(SchemeNames::mangle, spelled));
		forms.add(SchemeLowering.libraryPredicateForm());
		return List.copyOf(forms);
	}

	/**
	 * Every name a file with no import resolves without defining: the procedure entries
	 * and constants of the libraries the no-import default merges, plus the syntactic
	 * keywords the lowering implements. A file that uses any other free name is a
	 * fragment, not a program -- the rule the SICP corpus harness
	 * ({@code SicpCorpusE2eTest}) ports to classify each sample.
	 * @return the provided names, in canonical order
	 */
	public static SequencedSet<String> providedNames() {
		SequencedSet<String> names = new LinkedHashSet<>();
		names.addAll(SchemeBuiltins.entries().keySet());
		names.addAll(SchemeBuiltins.constants().keySet());
		names.addAll(SchemeLowering.syntaxNames());
		return names;
	}

}
