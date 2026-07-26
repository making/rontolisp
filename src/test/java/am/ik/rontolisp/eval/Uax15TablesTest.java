package am.ik.rontolisp.eval;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class Uax15TablesTest {

	// Fields: codepoint;name;category;combining class;bidi;decomposition;...
	private static final String UNICODE_DATA = """
			0041;LATIN CAPITAL LETTER A;Lu;0;L;;;;;N;;;;0061;
			0042;LATIN CAPITAL LETTER B;Lu;0;L;;;;;N;;;;0062;
			0061;LATIN SMALL LETTER A;Ll;0;L;;;;;N;;;;;0041;
			00C0;LATIN CAPITAL LETTER A WITH GRAVE;Lu;0;L;0041 0300;;;;N;;;;00E0;
			0300;COMBINING GRAVE ACCENT;Mn;230;NSM;;;;;N;NON-SPACING GRAVE;;;;
			00A0;NO-BREAK SPACE;Zs;0;CS;<noBreak> 0020;;;;N;;;;;
			""";

	private static final String DERIVED_PROPS = """
			# a comment line that matches nothing
			00A0          ; NFKC_QC; N #
			0340..0341    ; NFC_QC; M #
			00C0          ; NFD_QC; N #
			1E9B          ; NFKD_QC; N #
			""";

	private static final String TABLES_SOURCE = """
			(in-package :uax-15)

			(defvar *unicode-data*
			  (with-open-file (in (uiop:merge-pathnames* *data-directory* "UnicodeData.txt"))
			    (loop for line = (read-line in nil nil)
			       while line
			       collect (cl-ppcre:split ";" line))))

			(defvar *canonical-decomp-map* nil)

			(let ((canonical-decomp-map (make-hash-table)))
			  (loop for (1st) in *unicode-data* do (print 1st))
			  (setf *canonical-decomp-map* canonical-decomp-map))

			(defparameter *unicode-letters* (make-hash-table))
			""";

	private static final String LIBRARY_SOURCE = """
			(in-package :uax-15)

			(let ((nfd-illegal-list '())
			      (nfkc-illegal-list '()))
			  (with-open-file (in *derived-normalization-props-data-file*)
			    (loop for line = (read-line in nil nil) while line do (print line)))

			  (defun get-illegal-char-list (normalization-form)
			    "Takes a normalization form, e.g. :nfkc and returns a list."
			    (ecase normalization-form
			      (:nfd  nfd-illegal-list)
			      (:nfkc nfkc-illegal-list))))
			""";

	private static final SourceLoader LOADER = path -> {
		String file = path.substring(path.lastIndexOf('/') + 1);
		return switch (file) {
			case "UnicodeData.txt" -> UNICODE_DATA;
			case "DerivedNormalizationProps.txt" -> DERIVED_PROPS;
			default -> throw new IOException("no such data file: " + path);
		};
	};

	@Test
	void derivesTheTablesFromTheBundledDataAndLeavesTheRestVerbatim() {
		String rewritten = Uax15Tables.rewrite("src/precomputed-tables.lisp", TABLES_SOURCE, "uax-15", LOADER);
		assertThat(rewritten).isNotNull();
		// The row parse is gone, and with it the only reader of the raw rows.
		assertThat(rewritten).doesNotContain("UnicodeData.txt").contains("(defvar *unicode-data* nil)");
		// U+0300's combining class 230, and U+00C0 -> U+0041 U+0300 as a
		// length/codepoint/mapped record. The <noBreak> mapping of U+00A0 is a
		// COMPATIBILITY one, so it goes to the other table with the tag dropped.
		assertThat(dataOf(rewritten, "%lite-combining-class-data")).isEqualTo("768 230");
		assertThat(dataOf(rewritten, "%lite-canonical-decomp-data")).isEqualTo("2 192 65 768");
		assertThat(dataOf(rewritten, "%lite-compatible-decomp-data")).isEqualTo("1 160 32");
		// Letters keep their category, as inclusive codepoint ranges: A and B are
		// adjacent uppercase, a is the only lowercase, and no other category appears.
		assertThat(dataOf(rewritten, "%lite-letters-lu-data")).isEqualTo("65 66 192 192");
		assertThat(dataOf(rewritten, "%lite-letters-ll-data")).isEqualTo("97 97");
		assertThat(dataOf(rewritten, "%lite-letters-lo-data")).isEmpty();
		// Untouched forms stay exactly as upstream wrote them.
		assertThat(rewritten).contains("(defvar *canonical-decomp-map* nil)")
			.contains("(defparameter *unicode-letters* (make-hash-table))");
	}

	@Test
	void derivesTheIllegalCharacterListsAsRangeRows() {
		String rewritten = Uax15Tables.rewrite("src/uax-15.lisp", LIBRARY_SOURCE, "uax-15", LOADER);
		assertThat(rewritten).isNotNull();
		assertThat(rewritten).doesNotContain("*derived-normalization-props-data-file*");
		// low/high/maybe triples: a single codepoint repeats as its own range, and only
		// a "; M" line carries the maybe flag.
		assertThat(dataOf(rewritten, "%lite-illegal-nfkc-data")).isEqualTo("160 160 0");
		assertThat(dataOf(rewritten, "%lite-illegal-nfc-data")).isEqualTo("832 833 1");
		assertThat(dataOf(rewritten, "%lite-illegal-nfd-data")).isEqualTo("192 192 0");
		assertThat(dataOf(rewritten, "%lite-illegal-nfkd-data")).isEqualTo("7835 7835 0");
		// The upstream docstring survives on the replacement definition.
		assertThat(rewritten).contains("\"Takes a normalization form, e.g. :nfkc and returns a list.\"");
	}

	// The rewrite is derived from ONE known shape of these two files. An upstream release
	// that moves a form must fail loudly: silently loading the real file instead would
	// reintroduce the 30 seconds this exists to remove, with nothing pointing at why.
	@Test
	void failsLoudlyWhenAnUpstreamReleaseMovesAForm() {
		String moved = TABLES_SOURCE.replace("(let ((canonical-decomp-map", "(let ((decomp-map");
		assertThatIllegalStateException()
			.isThrownBy(() -> Uax15Tables.rewrite("src/precomputed-tables.lisp", moved, "uax-15", LOADER))
			.withMessageContaining("no longer has exactly one form starting with '(let ((canonical-decomp-map'")
			.withMessageContaining("Uax15Tables");
	}

	@Test
	void keepsTheRealSourceWhenTheBundledDataCannotBeRead() {
		SourceLoader missing = path -> {
			throw new IOException("no filesystem");
		};
		assertThat(Uax15Tables.rewrite("src/precomputed-tables.lisp", TABLES_SOURCE, "uax-15", missing)).isNull();
		assertThat(Uax15Tables.rewrite("src/normalize-backend.lisp", "(in-package :uax-15)", "uax-15", LOADER))
			.isNull();
	}

	// A form's span ends at ITS closing paren: a string, a ;-comment, a #|...|# block and
	// a #\( character literal inside it are not delimiters.
	@Test
	void findsTheEndOfAFormPastStringsCommentsAndCharacterLiterals() {
		String source = """
				(in-package :uax-15)

				(defvar *unicode-data* (list ")" #\\( #\\) ; ) not a delimiter
				                             #|)|# 1))

				(let ((canonical-decomp-map 1)) canonical-decomp-map)
				(defparameter *tail* t)
				""";
		String rewritten = Uax15Tables.rewrite("src/precomputed-tables.lisp", source, "uax-15", LOADER);
		assertThat(rewritten).isNotNull();
		assertThat(rewritten).doesNotContain("#|)|#").contains("(defparameter *tail* t)");
	}

	/**
	 * The decimal run inside the emitted zero-argument data function of the given name.
	 */
	private static String dataOf(String source, String name) {
		int start = source.indexOf("(defun " + name + " ()");
		assertThat(start).as("emitted data function " + name).isNotNegative();
		int open = source.indexOf('"', start);
		int end = source.indexOf('"', open + 1);
		String text = source.substring(open + 1, end);
		// A chunked run is joined with (concatenate 'string "..." "..."): the test data
		// is far below one chunk, so a single literal is expected.
		assertThat(source.substring(start, open)).doesNotContain("concatenate");
		return text;
	}

	/** Keeps the loaders' entry point honest: the map is consulted by system name. */
	@Test
	void onlyRewritesTheUax15System() {
		assertThat(ShimLibraries.rewriteComponentSource("cl-ppcre", "api.lisp", "(in-package :cl-ppcre)", "cl-ppcre",
				LOADER))
			.isEqualTo("(in-package :cl-ppcre)");
	}

}
