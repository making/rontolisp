package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Disabled;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the REAL uax-15 v0.1.3 sources
 * (vendored unmodified under {@code src/test/resources/uax-15}, MIT license) load via
 * {@code asdf:load-system} and run the four Unicode normalization forms on the
 * INTERPRETER via {@link AsdfLibraryE2eSupport}. The library transitively pulls
 * split-sequence and cl-ppcre, then loads the 34k-line UnicodeData.txt,
 * CompositionExclusions.txt and DerivedNormalizationProps.txt into precomputed hash
 * tables at load time via {@code with-open-file :external-format :UTF-8}. This exercises
 * the {@code asdf:find-system} / {@code asdf:system-source-directory} pair (the runtime
 * system registry), {@code uiop:merge-pathnames*} + {@code make-pathname} (namestring
 * composition), and per-line {@code read-line} over 34k lines.
 *
 * <p>
 * The compile paths (JVM + both WASM backends) are excluded: the pathname / ASDF
 * primitives ({@code make-pathname}, {@code uiop:merge-pathnames*},
 * {@code asdf:find-system}, {@code asdf:system-source-directory}) are registered as
 * runtime functions in the interpreter only and need per-backend compile-time
 * substitution (or bytecode) support to lower on the JVM and WASM. Additionally, the
 * compiled {@code .wasm} would need runtime filesystem access to the
 * {@code unicode-15-data/*.txt} data files, which the wasmtime sandbox does not provide
 * by default.
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
	@Disabled("uax-15 uses make-pathname / uiop:merge-pathnames* / asdf:find-system / asdf:system-source-directory at load time; interpreter-only until the JVM compile path grows lowerings for them")
	void compilesAndRunsOnJvm() {
	}

	@Override
	@Disabled("uax-15 reads unicode-15-data/*.txt at load time; the wasmtime sandbox has no --dir mount")
	void compilesAndRunsOnWasmPreview1() {
	}

	@Override
	@Disabled("uax-15 reads unicode-15-data/*.txt at load time; the wasmtime sandbox has no --dir mount")
	void compilesAndRunsOnWasmComponent() {
	}

}
