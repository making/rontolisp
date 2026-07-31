package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the REAL uax-15 v0.1.3 sources
 * (vendored unmodified under {@code src/test/resources/uax-15}, MIT license) load via
 * {@code asdf:load-system} and run the four Unicode normalization forms on all four
 * backends via {@link AsdfLibraryE2eSupport}. The library transitively pulls
 * split-sequence and cl-ppcre, then reads the 34k-line UnicodeData.txt,
 * CompositionExclusions.txt and DerivedNormalizationProps.txt into precomputed hash
 * tables via {@code with-open-file :external-format :UTF-8}. This exercises the
 * {@code asdf:find-system} / {@code asdf:system-source-directory} pair (the runtime
 * system registry, folded at compile time by
 * {@link am.ik.rontolisp.cli.CompileTimePathnameFolder}), {@code uiop:merge-pathnames*} +
 * {@code make-pathname} (namestring composition, also folded), per-line {@code read-line}
 * over 34k lines (2/3-arg CL form lowered by
 * {@link am.ik.rontolisp.macro.LispMacroExpander#expandReadLineCompat}), and
 * {@code (subseq unicode-string ...)}/{@code (stable-sort unicode-string ...)} on
 * mutable-character vectors (subseq's vector arm added in this integration).
 *
 * <p>
 * It is also the pin for {@link am.ik.rontolisp.eval.Uax15Tables}: those load-time table
 * builds are the ones rontolisp DERIVES from the same bundled data files while loading or
 * compiling, emits as data and materializes on FIRST READ, so the combining-class map,
 * the illegal-character list (length and both endpoints, i.e. the whole ordered list) and
 * {@code unicode-letter-p} are asserted here alongside the normalizations -- and the
 * exercise leads with the two lines that pin the deferral itself (see the comment on
 * {@code EXERCISE}). {@code unicode-letter-p} answering T for {@code #\A} is the derived
 * table's doing: upstream's own letter loop keys every data-derived entry on {@code nil},
 * because a file's {@code pushnew} onto {@code *features*} never reaches the reader that
 * would enable {@code #+utf-32} in {@code char-from-hexstring}.
 *
 * <p>
 * All four backends produce the same code-point sequences: the WASM GC string byte data
 * is UTF-8 encoded on serialization ({@code _charvec_to_str} emits each character's 1-4
 * byte UTF-8 sequence) and read back through
 * {@link am.ik.rontolisp.codegen.wasm.WasmStringRuntimeBuilder#buildStrCharAtBody
 * _str_char_at} /
 * {@link am.ik.rontolisp.codegen.wasm.WasmStringRuntimeBuilder#buildStrCharCountBody
 * _str_char_count}, so a non-BMP scratch value stored by uax-15 in a
 * {@code unicode-string} char vector round-trips through the compiled program unchanged.
 */
class Uax15E2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "uax-15").toAbsolutePath().toString();

	// The first two lines pin the LAZINESS, and both are load-bearing. The `null` row
	// is the whole claim of Uax15Tables' deferral: after loading the system every one of
	// the five derived tables is still NIL, so nothing in the load built one. The second
	// line then forces the COMPOSITION map first, through the one route that reaches it
	// without going through the canonical decomposition map (uax-15::compose, which the
	// normalize entry points never reach until nfd has run) -- the builder relocated into
	// precomputed-tables.lisp maphashes that map, and without its explicit force every
	// backend dies on NIL. Putting them after a normalize would hide both.
	//
	// The uax-15 API is stringly-typed and the returned strings contain combining
	// marks / decomposed sequences that are hard to eyeball, so the exercise prints
	// codepoint LISTS rather than the raw normalized strings. NFC composes: A + U+030A
	// -> U+00C5. NFD decomposes: U+00C5 -> A + U+030A. NFKC compat-composes: U+2460
	// (circled 1) -> 49 (ASCII 1), U+00BD (1/2) -> 49 U+2044 50. NFKD of U+FB00
	// (LATIN SMALL LIGATURE FF) -> two 'f' characters. NFC of U+212B (Angstrom sign)
	// canonically decomposes to U+00C5.
	private static final String EXERCISE = """
			(asdf:load-system :uax-15)
			(defun codes (s) (map 'list #'char-code s))
			(print (list (null uax-15::*canonical-combining-class*)
			             (null uax-15::*canonical-decomp-map*)
			             (null uax-15::*compatible-decomp-map*)
			             (null uax-15::*canonical-comp-map*)
			             (null uax-15::*unicode-letters*)))
			(print (codes (uax-15::from-unicode-string
			               (uax-15::compose
			                (uax-15::to-unicode-string (format nil "A~C" (code-char #x030A)))))))
			(print (codes (uax-15:normalize (format nil "A~C" (code-char #x030A)) :nfc)))
			(print (codes (uax-15:normalize (string (code-char #x00C5)) :nfd)))
			(print (codes (uax-15:normalize (format nil "~C~C" (code-char #x2460) (code-char #x00BD)) :nfkc)))
			(print (codes (uax-15:normalize (string (code-char #xFB00)) :nfkd)))
			(print (codes (uax-15:normalize (string (code-char #x212B)) :nfc)))
			(print (gethash #x0301 (uax-15:get-canonical-combining-class-map) 0))
			(let ((illegal (uax-15:get-illegal-char-list :nfc)))
			  (print (list (length illegal) (first illegal) (car (last illegal)))))
			(print (mapcar (lambda (code) (uax-15:unicode-letter-p (code-char code)))
			               (list #x41 #x3042 #x30 #x4E00)))
			""";

	private static final List<String> EXPECTED = List.of("(T T T T T)", "(197)", "(197)", "(65 778)", "(49 49 8260 50)",
			"(102 102)", "(197)", "230", "(1231 (832 NIL) (71984 T))", "(T T NIL T)");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected List<String> extraSystemPath() {
		return List.of(Path.of("src", "test", "resources", "split-sequence").toAbsolutePath().toString(),
				Path.of("src", "test", "resources", "cl-ppcre").toAbsolutePath().toString());
	}

	@Override
	protected String exercise() {
		return EXERCISE;
	}

	@Override
	protected List<String> expected() {
		return EXPECTED;
	}

	@Override
	protected String artifactName() {
		return "Uax15E2e";
	}

}
