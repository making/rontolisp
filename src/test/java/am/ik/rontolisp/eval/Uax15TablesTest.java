package am.ik.rontolisp.eval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class Uax15TablesTest {

	// Fields: codepoint;name;category;combining class;bidi;decomposition;...
	// The last two rows are the CJK range MARKERS: UnicodeData.txt gives a block's first
	// and last codepoint one row each and nothing in between, which is why the hardcoded
	// loops exist and why merging the two sources has to leave a hole between #x3401 and
	// the loop's own end.
	private static final String UNICODE_DATA = """
			0041;LATIN CAPITAL LETTER A;Lu;0;L;;;;;N;;;;0061;
			0042;LATIN CAPITAL LETTER B;Lu;0;L;;;;;N;;;;0062;
			0061;LATIN SMALL LETTER A;Ll;0;L;;;;;N;;;;;0041;
			00C0;LATIN CAPITAL LETTER A WITH GRAVE;Lu;0;L;0041 0300;;;;N;;;;00E0;
			0300;COMBINING GRAVE ACCENT;Mn;230;NSM;;;;;N;NON-SPACING GRAVE;;;;
			00A0;NO-BREAK SPACE;Zs;0;CS;<noBreak> 0020;;;;N;;;;;
			3400;<CJK Ideograph Extension A, First>;Lo;0;L;;;;;N;;;;;
			4DBF;<CJK Ideograph Extension A, Last>;Lo;0;L;;;;;N;;;;;
			""";

	private static final String DERIVED_PROPS = """
			# a comment line that matches nothing
			00A0          ; NFKC_QC; N #
			0340..0341    ; NFC_QC; M #
			00C0          ; NFD_QC; N #
			1E9B          ; NFKD_QC; N #
			""";

	// The shape of src/precomputed-tables.lisp that the rewrite is derived from: the
	// raw-row defvar, the table-building let, the composition map (a defparameter of a
	// NON-NIL hash table plus the maphash that fills it) and the letter table (likewise,
	// plus the hardcoded range loops, the last of which ends the relocated span).
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

			(defparameter *canonical-comp-map* (make-hash-table :test #'equal))
			(maphash
			   (lambda (src-char decomped-chars)
			     (setf (gethash decomped-chars *canonical-comp-map*) src-char))
			   *canonical-decomp-map*)

			(defparameter *unicode-letters* (make-hash-table :size 170000))
			(loop for x in uax-15::*unicode-data*
			      do (print x))
			(loop for code from #x3400 below #x4DB5 ; CJK Ideograph Extension A
			      do (setf (gethash (code-char code) *unicode-letters*) "Lo"))
			#-utf-16 (loop for code from #x2CEB0 below #x2EBE0 ; CJK Ideograph Extension F
			      do (setf (gethash (code-char code) *unicode-letters*) "Lo"))

			#|
			Letter characters in ranges
			|#
			""";

	// src/uax-15.lisp: the illegal-list let, plus the three functions that READ a derived
	// table (get-mapping reads two of them, one of those twice on a single line).
	private static final String LIBRARY_SOURCE = """
			(in-package :uax-15)

			(defun get-mapping (normalization-form &aux (mapping '()))
			  (dolist (map (ecase normalization-form
			                 (:nfd  (list *canonical-decomp-map*))
			                 (:nfkd (list *canonical-decomp-map* *compatible-decomp-map*))
			                 (:nfc  (list *canonical-comp-map*))))
			    (print map)))

			(defun get-canonical-combining-class-map ()
			  *canonical-combining-class*)

			(let ((nfd-illegal-list '())
			      (nfkc-illegal-list '()))
			  (with-open-file (in *derived-normalization-props-data-file*)
			    (loop for line = (read-line in nil nil) while line do (print line)))

			  (defun get-illegal-char-list (normalization-form)
			    "Takes a normalization form, e.g. :nfkc and returns a list."
			    (ecase normalization-form
			      (:nfd  nfd-illegal-list)
			      (:nfkc nfkc-illegal-list))))

			(defun unicode-letter-p (char)
			  "Returns T if the character is one of the unicode characters falling into a letter category."
			  (when (gethash char *unicode-letters*)
			    t))
			""";

	private static final String BACKEND_SOURCE = """
			(in-package :uax-15)

			(defun get-canonical-combining-class (ch)
			  (gethash ch *canonical-combining-class* 0))

			(defun decompose-char (char &optional (type :canonical))
			  (or (gethash char *canonical-decomp-map*)
			      (and (eq type :compatible)
			           (gethash char *compatible-decomp-map*))))

			(defun compose (s)
			  (gethash s *canonical-comp-map*))
			""";

	private static final SourceLoader LOADER = path -> {
		String file = path.substring(path.lastIndexOf('/') + 1);
		return switch (file) {
			case "UnicodeData.txt" -> UNICODE_DATA;
			case "DerivedNormalizationProps.txt" -> DERIVED_PROPS;
			default -> throw new IOException("no such data file: " + path);
		};
	};

	private static final SourceLoader MISSING = path -> {
		throw new IOException("no filesystem");
	};

	@Test
	void derivesTheTablesFromTheBundledDataAndLeavesTheRestVerbatim() {
		String rewritten = rewriteTables();
		// The row parse is gone, and with it the only reader of the raw rows.
		assertThat(rewritten).doesNotContain("UnicodeData.txt").contains("(defvar *unicode-data* nil)");
		// U+0300's combining class 230, and U+00C0 -> U+0041 U+0300 as a
		// length/codepoint/mapped record. The <noBreak> mapping of U+00A0 is a
		// COMPATIBILITY one, so it goes to the other table with the tag dropped.
		assertThat(dataOf(rewritten, "%lite-combining-class-data")).isEqualTo("768 230");
		assertThat(dataOf(rewritten, "%lite-canonical-decomp-data")).isEqualTo("2 192 65 768");
		assertThat(dataOf(rewritten, "%lite-compatible-decomp-data")).isEqualTo("1 160 32");
		// One run of inclusive ranges for all five letter categories, and it is the
		// UNION of the data rows and the hardcoded loops -- member for member the key
		// set of the table it replaces. A and B are adjacent uppercase, a is the only
		// lowercase. The CJK block shows both halves: the loop's #x3400..#x4DB4 swallows
		// the data row at #x3400, while the block's LAST marker at #x4DBF stays a range
		// of its own, the loop having stopped eleven codepoints short of it.
		assertThat(dataOf(rewritten, "%lite-letter-range-data"))
			.isEqualTo("65 66 97 97 192 192 13312 19892 19903 19903 183984 191455");
		// Untouched forms stay exactly as upstream wrote them.
		assertThat(rewritten).contains("(defvar *canonical-decomp-map* nil)")
			.contains("#|\nLetter characters in ranges\n|#");
	}

	// Nothing may run at load time any more: each table is published by its own builder,
	// and until that builder runs the global is NIL -- which is what makes the readers'
	// (or *T* (%lite-build-T)) protocol work at all.
	@Test
	void buildsEachTableInABuilderInsteadOfAtLoadTime() {
		String rewritten = rewriteTables();
		assertThat(rewritten).contains("""
				(defun %lite-build-canonical-combining-class ()
				  (setf *canonical-combining-class*
				        (%lite-fill-pairs (%lite-ints (%lite-combining-class-data)) (make-hash-table))))""");
		// The composition map's upstream defparameter initializes to a NON-NIL (empty)
		// hash table. Left alone, (or *T* ...) would short-circuit onto it and the
		// builder would never run, so it is demoted to nil and its initializer moves
		// into the builder. The letter table is demoted too and stays nil: nothing
		// builds it any more.
		assertThat(rewritten).contains("(defvar *canonical-comp-map* nil)")
			.contains("(defvar *unicode-letters* nil)")
			.doesNotContain("(defparameter *canonical-comp-map*")
			.doesNotContain("(defparameter *unicode-letters*")
			.contains("(setf *canonical-comp-map* (make-hash-table :test #'equal))")
			.doesNotContain("(setf *unicode-letters*");
		// Nothing outside a builder assigns a table: no top-level fill survives.
		assertThat(rewritten.indexOf("(setf *canonical-combining-class*"))
			.isGreaterThan(rewritten.indexOf("(defun %lite-build-canonical-combining-class ()"));
	}

	// The relocated maphash reads *canonical-decomp-map* verbatim, and reads inside THIS
	// file are deliberately never rewritten (it is full of (setf (gethash K *T*) V) write
	// places). Without the explicit force, every backend maphashes NIL.
	@Test
	void forcesTheDecompositionMapBeforeTheRelocatedCompositionMaphash() {
		String rewritten = rewriteTables();
		int builder = rewritten.indexOf("(defun %lite-build-canonical-comp-map ()");
		int force = rewritten.indexOf("(or *canonical-decomp-map* (%lite-build-canonical-decomp-map))", builder);
		int maphash = rewritten.indexOf("(maphash", builder);
		assertThat(builder).isNotNegative();
		assertThat(force).as("the composition-map builder forces its source table").isBetween(builder, maphash);
	}

	// The hardcoded range loops are READ, not relocated: their codepoints are in the
	// range run above, so neither they nor the dead data-derived loop survive, and the
	// letter table has no builder at all -- the predicate searches the ranges instead.
	@Test
	void foldsTheLetterRangeLoopsIntoTheRangesInsteadOfRunningThem() {
		String rewritten = rewriteTables();
		assertThat(rewritten).doesNotContain("(loop for code from #x")
			.doesNotContain("(loop for x in uax-15::*unicode-data*")
			.doesNotContain("%lite-build-unicode-letters")
			.doesNotContain("%lite-fill-letters");
		assertThat(rewritten.indexOf("(defun %lite-unicode-letter-p (char)"))
			.as("the predicate follows the ranges it searches")
			.isGreaterThan(rewritten.indexOf("(defun %lite-letter-range-data ()"));
	}

	// The rewrite has to READ a reader conditional the relocated loops used to leave to
	// the reader, so a loop it cannot read must not be silently skipped -- the ranges
	// would simply be missing those codepoints, and unicode-letter-p would answer NIL for
	// every CJK ideograph with nothing pointing at why.
	@Test
	void failsLoudlyWhenALetterRangeLoopIsSpelledSomeOtherWay() {
		String toForm = TABLES_SOURCE.replace("from #x3400 below #x4DB5", "from #x3400 to #x4DB4");
		assertThatIllegalStateException()
			.isThrownBy(() -> Uax15Tables.rewrite("src/precomputed-tables.lisp", toForm, "uax-15", LOADER))
			.withMessageContaining("hardcoded letter range loop(s)")
			.withMessageContaining("Uax15Tables");
		String otherFeature = TABLES_SOURCE.replace("#-utf-16 (loop for code from #x2CEB0",
				"#+sbcl (loop for code from #x2CEB0");
		assertThatIllegalStateException()
			.isThrownBy(() -> Uax15Tables.rewrite("src/precomputed-tables.lisp", otherFeature, "uax-15", LOADER))
			.withMessageContaining("reader conditional");
	}

	// After the span replacement the tables file names the letter table exactly once,
	// in the defvar. A mention anywhere else is a write into a table nothing builds, or
	// a read that answers "no character is a letter" -- both silent, so pin the count.
	@Test
	void failsLoudlyWhenTheTablesFileStillNamesTheLetterTableElsewhere() {
		String extraRead = TABLES_SOURCE + "\n(defun letter-count () (hash-table-count *unicode-letters*))\n";
		assertThatIllegalStateException()
			.isThrownBy(() -> Uax15Tables.rewrite("src/precomputed-tables.lisp", extraRead, "uax-15", LOADER))
			.withMessageContaining("outside the letter span")
			.withMessageContaining("Uax15Tables");
	}

	// Every read of a derived table in a component that has one becomes a forcing read.
	@Test
	void forcesEveryReadOfADerivedTableInTheComponentsThatReadThem() {
		String backend = Uax15Tables.rewrite("src/normalize-backend.lisp", BACKEND_SOURCE, "uax-15", LOADER);
		assertThat(backend).isNotNull();
		assertThat(backend)
			.contains("(gethash ch (or *canonical-combining-class* (%lite-build-canonical-combining-class)) 0)")
			.contains("(gethash char (or *canonical-decomp-map* (%lite-build-canonical-decomp-map)))")
			.contains("(gethash char (or *compatible-decomp-map* (%lite-build-compatible-decomp-map)))")
			.contains("(gethash s (or *canonical-comp-map* (%lite-build-canonical-comp-map)))");
		String library = Uax15Tables.rewrite("src/uax-15.lisp", LIBRARY_SOURCE, "uax-15", LOADER);
		assertThat(library).isNotNull();
		// Both reads on the :nfkd line and the whole-body read of the accessor.
		assertThat(library)
			.contains("(:nfkd (list (or *canonical-decomp-map* (%lite-build-canonical-decomp-map))"
					+ " (or *compatible-decomp-map* (%lite-build-compatible-decomp-map))))")
			.contains("  (or *canonical-combining-class* (%lite-build-canonical-combining-class)))");
		// A component that reads none is left alone entirely.
		assertThat(Uax15Tables.rewrite("src/utilities.lisp", "(in-package :uax-15)", "uax-15", LOADER)).isNull();
	}

	// The letter table is the one global with no builder, so its read is not forced but
	// REMOVED: the exported predicate keeps its docstring and calls the range search, and
	// nothing in the component names the table any more.
	@Test
	void replacesTheLetterLookupWithTheRangeSearchKeepingTheDocstring() {
		String library = Uax15Tables.rewrite("src/uax-15.lisp", LIBRARY_SOURCE, "uax-15", LOADER);
		assertThat(library).isNotNull();
		assertThat(library).contains("""
				(defun unicode-letter-p (char)
				  "Returns T if the character is one of the unicode characters falling into a letter category."
				  (%lite-unicode-letter-p char))""").doesNotContain("*unicode-letters*");
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

	// (char s i) costs O(i) on both WASM backends and O(length) on the JVM, so one long
	// literal makes the scan quadratic. The run is cut into chunks -- between integers,
	// never inside one, which is what lets the scanner treat each chunk independently.
	@Test
	void cutsALongRunIntoChunksWithoutEverSplittingANumber() {
		StringBuilder rows = new StringBuilder();
		List<String> expected = new ArrayList<>();
		for (int code = 0x1000; code < 0x1200; code++) {
			rows.append("%04X;COMBINING TEST;Mn;230;NSM;;;;;N;;;;;%n".formatted(code));
			expected.add(code + " 230");
		}
		String rewritten = Uax15Tables.rewrite("src/precomputed-tables.lisp", TABLES_SOURCE, "uax-15", path -> {
			String file = path.substring(path.lastIndexOf('/') + 1);
			return "UnicodeData.txt".equals(file) ? rows.toString() : DERIVED_PROPS;
		});
		assertThat(rewritten).isNotNull();
		List<String> chunks = chunksOf(rewritten, "%lite-combining-class-data");
		assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(chunk -> {
			assertThat(chunk.length()).isLessThanOrEqualTo(1000);
			assertThat(chunk).doesNotStartWith(" ").doesNotEndWith(" ");
		});
		assertThat(String.join(" ", chunks)).isEqualTo(String.join(" ", expected));
	}

	// The rewrite is derived from ONE known shape of these files. An upstream release
	// that moves a form must fail loudly: silently loading the real file instead would
	// reintroduce the 30 seconds this exists to remove, with nothing pointing at why.
	@Test
	void failsLoudlyWhenAnUpstreamReleaseMovesAForm() {
		String moved = TABLES_SOURCE.replace("(let ((canonical-decomp-map", "(let ((decomp-map");
		assertThatIllegalStateException()
			.isThrownBy(() -> Uax15Tables.rewrite("src/precomputed-tables.lisp", moved, "uax-15", LOADER))
			.withMessageContaining("no longer has exactly one form starting with '(let ((canonical-decomp-map'")
			.withMessageContaining("Uax15Tables");
		// A relocated SPAN carries the same guard on both of its ends.
		String noLastLoop = TABLES_SOURCE.replace("(loop for code from #x2CEB0", "(loop for code from #x2CEB1");
		assertThatIllegalStateException()
			.isThrownBy(() -> Uax15Tables.rewrite("src/precomputed-tables.lisp", noLastLoop, "uax-15", LOADER))
			.withMessageContaining("(loop for code from #x2CEB0");
	}

	// A read this rewrite fails to reach sees a table that is still NIL, and only at the
	// moment that one table is first needed -- so the count is pinned, not discovered.
	@Test
	void failsLoudlyWhenAConsumerNoLongerReadsATableTheExpectedNumberOfTimes() {
		String moved = BACKEND_SOURCE.replace("(gethash char *compatible-decomp-map*)", "(gethash char nil)");
		assertThatIllegalStateException()
			.isThrownBy(() -> Uax15Tables.rewrite("src/normalize-backend.lisp", moved, "uax-15", LOADER))
			.withMessageContaining("no longer reads *compatible-decomp-map* exactly 1 time(s)")
			.withMessageContaining("src/normalize-backend.lisp")
			.withMessageContaining("Uax15Tables");
	}

	// The inventory is keyed by component PATH, so the one thing it cannot see is a
	// reader that MOVED. Left unguarded that is the worst combination -- the tables file
	// still matches its own markers, so the tables go lazy, while the moved reader keeps
	// a
	// bare read that never forces one and dies later on a NIL hash table.
	@Test
	void failsLoudlyWhenAComponentOutsideTheInventoryReadsADerivedTable() {
		assertThatIllegalStateException()
			.isThrownBy(() -> Uax15Tables.rewrite("src/uax15-api.lisp", LIBRARY_SOURCE, "uax-15", LOADER))
			.withMessageContaining("reads *canonical-combining-class* from src/uax15-api.lisp")
			.withMessageContaining("not in the forced-read inventory")
			.withMessageContaining("Uax15Tables");
		// The letter table is scanned for on stronger grounds than the other four: it has
		// no builder at all, so a second reader would answer that nothing is a letter.
		assertThatIllegalStateException().isThrownBy(() -> Uax15Tables.rewrite("src/letters-api.lisp",
				"(in-package :uax-15)\n(defun letter-count () (hash-table-count *unicode-letters*))\n", "uax-15",
				LOADER))
			.withMessageContaining("reads *unicode-letters* from src/letters-api.lisp");
		// A component that mentions none of the five is still left alone.
		assertThat(Uax15Tables.rewrite("src/trivial-utf-16.lisp",
				"(in-package :uax-15)\n;; the first char from *unicode-data*\n", "uax-15", LOADER))
			.isNull();
	}

	// With no bundled data the real source loads and builds every table eagerly, so the
	// forcing reads the other components carry must still name a defined function. The
	// identity builders are generated from the SAME name list the reads are, which is the
	// only thing keeping the two sides in step -- no E2E ever walks this path.
	@Test
	void appendsIdentityBuildersWhenTheBundledDataCannotBeRead() {
		String rewritten = Uax15Tables.rewrite("src/precomputed-tables.lisp", TABLES_SOURCE, "uax-15", MISSING);
		assertThat(rewritten).isNotNull();
		assertThat(rewritten).startsWith(TABLES_SOURCE);
		String backend = Uax15Tables.rewrite("src/normalize-backend.lisp", BACKEND_SOURCE, "uax-15", MISSING);
		String library = Uax15Tables.rewrite("src/uax-15.lisp", LIBRARY_SOURCE, "uax-15", MISSING);
		assertThat(backend).isNotNull();
		assertThat(library).isNotNull();
		for (String called : buildersCalledIn(backend + library)) {
			assertThat(rewritten).as("fallback defines " + called).contains("(defun " + called + " () *");
		}
		// unicode-letter-p is replaced either way -- there are no ranges to search here,
		// so the fallback answers it from the table the real source built eagerly. That
		// is
		// what lets the replacement be unconditional and the two paths agree.
		assertThat(library).contains("(%lite-unicode-letter-p char))");
		assertThat(rewritten).contains("""
				(defun %lite-unicode-letter-p (char)
				  (when (gethash char *unicode-letters*) t))""");
		// The illegal-character lists have no fallback builder: that span simply keeps
		// the real (slow) source, as it always did.
		assertThat(library).contains("*derived-normalization-props-data-file*");
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
				(defparameter *canonical-comp-map* (make-hash-table :test #'equal))
				(maphash (lambda (k v) (print (list k v ")" #\\())) *canonical-decomp-map*)
				(defparameter *unicode-letters* (make-hash-table))
				(loop for code from #x2CEB0 below #x2EBE0 do (print code))
				(defparameter *tail* t)
				""";
		String rewritten = Uax15Tables.rewrite("src/precomputed-tables.lisp", source, "uax-15", LOADER);
		assertThat(rewritten).isNotNull();
		assertThat(rewritten).doesNotContain("#|)|#").contains("(defparameter *tail* t)");
	}

	/**
	 * {@return the decimal-run chunks of the emitted zero-argument data function}
	 */
	private static List<String> chunksOf(String source, String name) {
		int start = source.indexOf("(defun " + name + " ()");
		assertThat(start).as("emitted data function " + name).isNotNegative();
		int end = source.indexOf("))\n", start);
		List<String> chunks = new ArrayList<>();
		for (int open = source.indexOf('"', start); open >= 0 && open < end; open = source.indexOf('"', open + 1)) {
			int close = source.indexOf('"', open + 1);
			chunks.add(source.substring(open + 1, close));
			open = close;
		}
		return chunks;
	}

	/**
	 * {@return the whole decimal run of the emitted data function, chunks rejoined}
	 */
	private static String dataOf(String source, String name) {
		return String.join(" ", chunksOf(source, name)).trim();
	}

	/**
	 * {@return the builder names the forcing reads of {@code source} call}
	 */
	private static List<String> buildersCalledIn(String source) {
		List<String> names = new ArrayList<>();
		for (int at = source.indexOf("(%lite-build-"); at >= 0; at = source.indexOf("(%lite-build-", at + 1)) {
			String name = source.substring(at + 1, source.indexOf(')', at));
			if (!names.contains(name)) {
				names.add(name);
			}
		}
		assertThat(names).as("every table is forced somewhere").hasSize(4);
		return names;
	}

	private static String rewriteTables() {
		String rewritten = Uax15Tables.rewrite("src/precomputed-tables.lisp", TABLES_SOURCE, "uax-15", LOADER);
		assertThat(rewritten).isNotNull();
		return rewritten;
	}

	/** Keeps the loaders' entry point honest: the map is consulted by system name. */
	@Test
	void onlyRewritesTheUax15System() {
		assertThat(ShimLibraries.rewriteComponentSource("cl-ppcre", "api.lisp", "(in-package :cl-ppcre)", "cl-ppcre",
				LOADER))
			.isEqualTo("(in-package :cl-ppcre)");
	}

}
