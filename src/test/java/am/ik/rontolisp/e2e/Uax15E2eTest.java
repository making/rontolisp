package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Disabled;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the REAL uax-15 v0.1.3 sources
 * (vendored unmodified under {@code src/test/resources/uax-15}, MIT license) load via
 * {@code asdf:load-system} and run the four Unicode normalization forms on the
 * INTERPRETER + JVM via {@link AsdfLibraryE2eSupport}. The library transitively pulls
 * split-sequence and cl-ppcre, then loads the 34k-line UnicodeData.txt,
 * CompositionExclusions.txt and DerivedNormalizationProps.txt into precomputed hash
 * tables at load time via {@code with-open-file :external-format :UTF-8}. This exercises
 * the {@code asdf:find-system} / {@code asdf:system-source-directory} pair (the runtime
 * system registry, folded at compile time by
 * {@link am.ik.rontolisp.cli.CompileTimePathnameFolder}), {@code uiop:merge-pathnames*} +
 * {@code make-pathname} (namestring composition, also folded), per-line {@code read-line}
 * over 34k lines (2/3-arg CL form lowered by
 * {@link am.ik.rontolisp.LispMacroExpander#expandReadLineCompat}), and
 * {@code (subseq unicode-string ...)}/{@code (stable-sort unicode-string ...)} on
 * mutable-character vectors (subseq's vector arm added in this integration).
 *
 * <p>
 * Both WASM backends remain excluded: the WASM string model is byte/ASCII
 * ({@code _charvec_to_str} truncates each element to one byte), and uax-15 stores non-BMP
 * scratch values in its {@code unicode-string} arrays, so downstream string reads on the
 * compiled program silently drop the high bytes. Fixing this needs a wider WASM string
 * model (UTF-8 encoding on serialization at minimum) that a follow-up scope should tackle
 * -- see {@code .todo/159}.
 */
class Uax15E2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "uax-15").toAbsolutePath().toString();

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
			(print (codes (uax-15:normalize (format nil "A~C" (code-char #x030A)) :nfc)))
			(print (codes (uax-15:normalize (string (code-char #x00C5)) :nfd)))
			(print (codes (uax-15:normalize (format nil "~C~C" (code-char #x2460) (code-char #x00BD)) :nfkc)))
			(print (codes (uax-15:normalize (string (code-char #xFB00)) :nfkd)))
			(print (codes (uax-15:normalize (string (code-char #x212B)) :nfc)))
			""";

	private static final List<String> EXPECTED = List.of("(197)", "(65 778)", "(49 49 8260 50)", "(102 102)", "(197)");

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

	@Override
	@Disabled("WASM string model is byte/ASCII (_charvec_to_str truncates each element to one byte);"
			+ " uax-15 stores non-BMP scratch values in unicode-string vectors so downstream"
			+ " string reads silently drop the high bytes. Follow-up: .todo/159 wide-char WASM" + " string model.")
	void compilesAndRunsOnWasmPreview1() {
	}

	@Override
	@Disabled("WASM string model is byte/ASCII (_charvec_to_str truncates each element to one byte);"
			+ " uax-15 stores non-BMP scratch values in unicode-string vectors so downstream"
			+ " string reads silently drop the high bytes. Follow-up: .todo/159 wide-char WASM" + " string model.")
	void compilesAndRunsOnWasmComponent() {
	}

}
