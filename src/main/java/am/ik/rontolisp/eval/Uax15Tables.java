package am.ik.rontolisp.eval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * Rewrites the table-BUILDING forms of the {@code uax-15} system so the tables are
 * derived from the bundled Unicode data files once, while the system is being loaded or
 * compiled, emitted as data, and then materialized ON FIRST READ instead of at load --
 * instead of being rebuilt by parsing 2.7 MB of text through {@code cl-ppcre} every time
 * the program runs.
 *
 * <p>
 * Everything that computes a normalization stays VERBATIM upstream: only the way the
 * tables are OBTAINED changes, and the contents are identical, so {@code normalize} (and
 * {@code get-illegal-char-list}) answer exactly what the real load would answer. Spans
 * are located by a marker that must occur exactly once -- an upstream release that moves
 * one fails loudly rather than silently going stale.
 *
 * <p>
 * <b>In {@code src/precomputed-tables.lisp}</b>, four spans:
 *
 * <ul>
 * <li>the {@code (defvar *unicode-data* ...)} that reads and splits all 34,924 rows of
 * {@code UnicodeData.txt} (a value NO consumer outside that file ever reads) becomes
 * {@code nil};</li>
 * <li>the {@code let} that folds those rows into the combining-class and the two
 * decomposition maps becomes the three tables as data plus one BUILDER {@code defun}
 * each;</li>
 * <li>{@code (defparameter *canonical-comp-map* ...)} + the {@code maphash} that fills it
 * becomes {@code (defvar *canonical-comp-map* nil)} plus a builder whose body is that
 * same {@code maphash}, RELOCATED verbatim;</li>
 * <li>{@code (defparameter *unicode-letters* ...)} + the nine hardcoded CJK / Hangul /
 * Tangut range loops becomes {@code (defvar *unicode-letters* nil)} plus a builder whose
 * body is those same nine loops, RELOCATED verbatim, followed by the data-derived letter
 * entries.</li>
 * </ul>
 *
 * <p>
 * <b>In {@code src/uax-15.lisp}</b>, the {@code let} that folds
 * {@code DerivedNormalizationProps.txt} into the four illegal-character lists becomes the
 * source RANGE rows as data, expanded on demand inside {@code get-illegal-char-list} and
 * cached.
 *
 * <p>
 * <b>In every component that READS one of the five tables</b> ({@code
 * src/normalize-backend.lisp} and {@code src/uax-15.lisp}), each bare read {@code *T*}
 * becomes {@code (or *T* (%lite-build-T))}, so the first read builds the table and every
 * later one hits it. The read counts are an explicit inventory ({@link #FORCED_READS}):
 * an upstream release that adds, moves or drops a read throws, exactly like a moved form
 * does. Forcing at the READ and not at the exported entry points is what keeps the
 * granularity -- an {@code :nfd} normalization structurally never touches the
 * compatibility map or the composition map -- and what makes the internal entry points
 * ({@code uax-15::nfc} and friends) impossible to bypass.
 *
 * <p>
 * Two facts make the {@code (or ...)} protocol correct, and both are load-bearing:
 *
 * <ul>
 * <li>All five globals must start out {@code nil}. Three already do; the two the last two
 * spans cover are upstream {@code defparameter}s of a FRESH, hence non-nil (TRUE),
 * {@code make-hash-table}, which would make {@code or} short-circuit onto that empty
 * table forever -- so those two initializers move INTO their builder.</li>
 * <li>The relocated {@code maphash} reads {@code *canonical-decomp-map*} and the reads
 * inside {@code precomputed-tables.lisp} are never rewritten (that file is full of
 * {@code (setf (gethash K *T*) V)} write places, which the substitution must not touch),
 * so the composition-map builder forces its dependency EXPLICITLY, as its first form.
 * Without it every backend fails on an empty composition map.</li>
 * </ul>
 *
 * <p>
 * Bulk numbers are emitted as decimal runs inside STRING literals scanned by a generated
 * helper, never as numeric literals: an integer literal costs two constant pool entries
 * on the JVM backend (it is a boxed {@code long}), and ~25,000 of them push a
 * library-scale class past the 65534-entry class-format ceiling -- see
 * {@code .kb/jvm-method-size-limits.md}, where that overflow is what made this
 * substitution look like an operand-stack bug. The runs are emitted as a QUOTED LIST of
 * short chunks rather than one long literal, which is what keeps any one of them clear of
 * the same 65535-byte {@code CONSTANT_Utf8} ceiling. Chunking used to buy far more than
 * that -- {@code (char s i)} cost O(i) on every COMPILE backend, so a single-literal scan
 * was quadratic and {@link #CHUNK} was worth ~15x on either WASM backend and ~10x on the
 * JVM -- but a character index is amortized O(1) everywhere now
 * ({@code .kb/string-index-cost.md}), so the chunk size is a constant-pool decision
 * alone.
 *
 * <p>
 * One BEHAVIOR difference, and it is a fix: the replaced letter loop passes each hex
 * codepoint through {@code char-from-hexstring}, whose {@code #+utf-32} branch is dead
 * because a file's own {@code pushnew} onto {@code *features*} never reaches the reader
 * -- so upstream collapses all 21,765 data-derived letters onto a single {@code nil} key
 * and {@code (unicode-letter-p #\A)} answers NIL. The derived table has the real keys.
 */
final class Uax15Tables {

	/** The ASDF system whose components this class rewrites (canonical lower-case). */
	static final String SYSTEM = "uax-15";

	/** The bundled data directory, relative to the system's base directory. */
	private static final String DATA_DIR = "unicode-15-data";

	/** The component holding the tables. Its own reads are never rewritten. */
	private static final String TABLES_FILE = "src/precomputed-tables.lisp";

	/** The component holding the illegal-character lists. */
	private static final String LIBRARY_FILE = "src/uax-15.lisp";

	/**
	 * How long an emitted string literal chunk may get. A {@code CONSTANT_Utf8} allows
	 * 65535 bytes and a run can be several times that, so a run has to be cut somewhere;
	 * this size is what it was cut to when the cut was also worth ~15x in scan time
	 * (measured over a 55,811-character run against one literal, ms: component 90 against
	 * 1439, WASM Preview 1 90 against 1328, JVM 27 against 280, interpreter 67 against
	 * 68). That reason is gone -- a character index is amortized O(1) on every backend
	 * now, {@code .kb/string-index-cost.md} -- and the ceiling is what is left, so this
	 * could be raised toward it if the chunk COUNT ever costs something. A number is
	 * never split: chunks are cut between integers.
	 */
	private static final int CHUNK = 1000;

	/** The letter categories the replaced loop selects, in the order it lists them. */
	private static final List<String> LETTER_CATEGORIES = List.of("Ll", "Lu", "Lm", "Lt", "Lo");

	/**
	 * The five derived tables, by the global each publishes, in the order
	 * {@code precomputed-tables.lisp} defines them. One list drives the builder names on
	 * BOTH sides -- the definitions emitted into that file and the forced reads emitted
	 * into the components that read them -- so the two can never drift apart, which is
	 * the whole safety of the no-data-files fallback ({@link #fallbackBuilders}).
	 */
	private static final List<String> LAZY_TABLES = List.of("*canonical-combining-class*", "*canonical-decomp-map*",
			"*compatible-decomp-map*", "*canonical-comp-map*", "*unicode-letters*");

	/**
	 * Component file to the EXACT number of bare reads of each table it is expected to
	 * have. Every one of them is rewritten to force its table; a count that no longer
	 * matches throws, because a read this rewrite failed to reach would see a table that
	 * is still {@code nil} -- which is a crash on every backend, but only at the moment
	 * that particular table is first needed.
	 */
	private static final Map<String, Map<String, Integer>> FORCED_READS = Map.of("src/normalize-backend.lisp",
			orderedCounts("*canonical-combining-class*", 1, "*canonical-decomp-map*", 1, "*compatible-decomp-map*", 1,
					"*canonical-comp-map*", 1),
			LIBRARY_FILE, orderedCounts("*canonical-combining-class*", 1, "*canonical-decomp-map*", 2,
					"*compatible-decomp-map*", 1, "*canonical-comp-map*", 1, "*unicode-letters*", 1));

	private Uax15Tables() {
	}

	/**
	 * {@return the given name/count pairs, in argument order}
	 */
	private static Map<String, Integer> orderedCounts(Object... pairs) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			counts.put((String) pairs[i], (Integer) pairs[i + 1]);
		}
		return counts;
	}

	/**
	 * Returns the rewritten source of the given component, or {@code null} when this
	 * component neither holds a rewritten form nor reads a derived table.
	 * @param componentFile the component path, relative to the system's base directory
	 * @param source the real component source
	 * @param baseDir the system's base directory
	 * @param loader the loader the data files are read through
	 * @return the rewritten source, or {@code null} to keep the real one
	 */
	static @Nullable String rewrite(String componentFile, String source, @Nullable String baseDir,
			SourceLoader loader) {
		if (TABLES_FILE.equals(componentFile)) {
			return rewriteTables(source, baseDir, loader);
		}
		String forced = forceReads(componentFile, source);
		if (LIBRARY_FILE.equals(componentFile)) {
			String lists = rewriteIllegalCharLists(forced == null ? source : forced, baseDir, loader);
			return lists != null ? lists : forced;
		}
		return forced;
	}

	/**
	 * Rewrites every bare read of a derived table in {@code source} into a form that
	 * builds the table on first use.
	 * @return the rewritten source, or {@code null} when this component reads none
	 * @throws IllegalStateException when a read count no longer matches the inventory
	 */
	private static @Nullable String forceReads(String componentFile, String source) {
		Map<String, Integer> expected = FORCED_READS.get(componentFile);
		if (expected == null) {
			// A component the inventory does not name must not read a derived table. The
			// inventory is keyed by PATH, so a release that RENAMES a reader (or adds a
			// new one) would otherwise land here silently and get the worst combination:
			// tables that are lazy because the tables file still matched its own markers,
			// and a bare read that never forces one. That fails as a NIL hash table at
			// whichever API call first needs that one table, naming neither uax-15 nor
			// this class -- so it has to be caught here instead.
			for (String table : LAZY_TABLES) {
				if (source.contains(table)) {
					throw new IllegalStateException("the " + SYSTEM + " release in the quicklisp cache reads " + table
							+ " from " + componentFile
							+ ", which is not in the forced-read inventory (a renamed or new component?);"
							+ " the derived-table rewrite in Uax15Tables must be updated for it");
				}
			}
			return null;
		}
		String rewritten = source;
		for (Map.Entry<String, Integer> entry : expected.entrySet()) {
			String table = entry.getKey();
			int found = count(rewritten, table);
			if (found != entry.getValue()) {
				throw new IllegalStateException("the " + SYSTEM + " release in the quicklisp cache no longer reads "
						+ table + " exactly " + entry.getValue() + " time(s) in " + componentFile + " (found " + found
						+ "); the derived-table rewrite in Uax15Tables must be updated for it");
			}
			rewritten = rewritten.replace(table, "(or " + table + " (" + builderName(table) + "))");
		}
		return rewritten;
	}

	/**
	 * {@return the number of occurrences of {@code needle} in {@code text}}
	 */
	private static int count(String text, String needle) {
		int total = 0;
		for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
			total++;
		}
		return total;
	}

	/**
	 * {@return the builder function name publishing the given global}
	 */
	private static String builderName(String table) {
		return "%lite-build-" + table.substring(1, table.length() - 1);
	}

	private static String rewriteTables(String source, @Nullable String baseDir, SourceLoader loader) {
		String data = read(baseDir, "UnicodeData.txt", loader);
		if (data == null) {
			return source + fallbackBuilders();
		}
		List<Integer> combiningClasses = new ArrayList<>();
		List<Integer> canonicalDecomp = new ArrayList<>();
		List<Integer> compatibleDecomp = new ArrayList<>();
		Map<String, TreeSet<Integer>> letters = new LinkedHashMap<>();
		for (String category : LETTER_CATEGORIES) {
			letters.put(category, new TreeSet<>());
		}
		for (String line : data.split("\n")) {
			String row = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
			if (row.isEmpty()) {
				continue;
			}
			String[] fields = row.split(";", -1);
			int code = Integer.parseInt(fields[0].trim(), 16);
			int combiningClass = Integer.parseInt(fields[3].trim());
			if (combiningClass > 0) {
				combiningClasses.add(code);
				combiningClasses.add(combiningClass);
			}
			// A decomposition mapping starting with a <tag> is a compatibility one; the
			// tag is dropped and the rest are the mapped-to codepoints.
			if (!fields[5].isEmpty()) {
				String[] parts = fields[5].split(" ");
				boolean compatible = parts[0].startsWith("<");
				List<Integer> target = compatible ? compatibleDecomp : canonicalDecomp;
				int first = compatible ? 1 : 0;
				target.add(parts.length - first);
				target.add(code);
				for (int i = first; i < parts.length; i++) {
					target.add(Integer.parseInt(parts[i].trim(), 16));
				}
			}
			TreeSet<Integer> category = letters.get(fields[2]);
			if (category != null) {
				category.add(code);
			}
		}
		// Both relocated spans are read off the ORIGINAL source, before any replacement
		// shifts their offsets.
		String compMapInit = valueFormOf(source, TABLES_FILE, "(defparameter *canonical-comp-map*");
		String compMapFill = formAt(source, TABLES_FILE, "(maphash");
		String lettersInit = valueFormOf(source, TABLES_FILE, "(defparameter *unicode-letters*");
		String letterLoops = spanBody(source, TABLES_FILE, "(defparameter *unicode-letters*",
				"(loop for code from #x2CEB0");

		StringBuilder tables = new StringBuilder();
		tables.append(HELPERS);
		tables.append(dataFunction("%lite-combining-class-data", combiningClasses));
		tables.append(dataFunction("%lite-canonical-decomp-data", canonicalDecomp));
		tables.append(dataFunction("%lite-compatible-decomp-data", compatibleDecomp));
		tables.append("""

				;; One builder per table, run on the FIRST READ of its global and never again
				;; (the readers go through (or *T* (%lite-build-T)), and setf returns the table).
				(defun %lite-build-canonical-combining-class ()
				  (setf *canonical-combining-class*
				        (%lite-fill-pairs (%lite-ints (%lite-combining-class-data)) (make-hash-table))))

				(defun %lite-build-canonical-decomp-map ()
				  (setf *canonical-decomp-map*
				        (%lite-fill-decomp (%lite-ints (%lite-canonical-decomp-data)) (make-hash-table))))

				(defun %lite-build-compatible-decomp-map ()
				  (setf *compatible-decomp-map*
				        (%lite-fill-decomp (%lite-ints (%lite-compatible-decomp-data)) (make-hash-table))))
				""");

		StringBuilder compMap = new StringBuilder();
		// defvar nil, never the upstream defparameter of a fresh hash table: an empty
		// table is TRUE, so (or *canonical-comp-map* ...) would never build anything.
		compMap.append("\n(defvar *canonical-comp-map* nil)\n\n(defun %lite-build-canonical-comp-map ()\n")
			.append("  ;; The relocated maphash below reads *canonical-decomp-map* verbatim, and\n")
			.append("  ;; the reads inside THIS file are deliberately not rewritten -- so force\n")
			.append("  ;; the dependency here, explicitly, before the maphash walks it.\n")
			.append("  (or *canonical-decomp-map* (%lite-build-canonical-decomp-map))\n")
			.append("  (setf *canonical-comp-map* ")
			.append(compMapInit)
			.append(")\n")
			.append(compMapFill)
			.append("\n  *canonical-comp-map*)\n");

		StringBuilder lettersForms = new StringBuilder("\n(defvar *unicode-letters* nil)\n");
		StringBuilder letterFills = new StringBuilder();
		for (Map.Entry<String, TreeSet<Integer>> entry : letters.entrySet()) {
			String name = "%lite-letters-" + entry.getKey().toLowerCase() + "-data";
			lettersForms.append(dataFunction(name, ranges(entry.getValue())));
			letterFills.append("  (%lite-fill-letters (%lite-ints (")
				.append(name)
				.append(")) *unicode-letters* \"")
				.append(entry.getKey())
				.append("\")\n");
		}
		// The nine range loops keep running BEFORE the data-derived entries, which is the
		// order the emitted file has always had (upstream runs its own letter loop first,
		// but that loop is dead here -- its keys collapse to nil -- and the derived fill
		// that replaces it has always been appended after the ranges). Both write the
		// same
		// keys for the CJK/Hangul/Tangut ranges and only the last writer's category
		// string
		// survives, so the order is preserved deliberately, not incidentally.
		lettersForms.append("\n(defun %lite-build-unicode-letters ()\n  (setf *unicode-letters* ")
			.append(lettersInit)
			.append(")\n")
			.append(letterLoops)
			.append('\n')
			.append(letterFills)
			.append("  *unicode-letters*)\n");

		String rewritten = replaceForm(source, TABLES_FILE, "(defvar *unicode-data*", """
				;; Nothing outside this file ever read the rows: the tables below are the
				;; only consumers, and they are data now.
				(defvar *unicode-data* nil)
				""");
		rewritten = replaceForm(rewritten, TABLES_FILE, "(let ((canonical-decomp-map", tables.toString());
		rewritten = replaceSpan(rewritten, TABLES_FILE, "(defparameter *canonical-comp-map*", "(maphash",
				compMap.toString());
		return replaceSpan(rewritten, TABLES_FILE, "(defparameter *unicode-letters*", "(loop for code from #x2CEB0",
				lettersForms.toString());
	}

	/**
	 * {@return identity builders for all five tables, appended to the REAL source when
	 * the bundled data files cannot be read}
	 *
	 * <p>
	 * That source builds every table eagerly, so each global is already non-nil and the
	 * {@code (or *T* (%lite-build-T))} the other components carry never reaches its
	 * builder. They exist only so the name resolves. Generating them from
	 * {@link #LAZY_TABLES} -- the same list the forced reads name -- is what makes it
	 * impossible for the fallback to define a different set than the readers call.
	 */
	private static String fallbackBuilders() {
		StringBuilder out = new StringBuilder("""

				;; The bundled Unicode data files could not be read, so everything above ran
				;; the real (eager) way and every table is already built. These identity
				;; builders exist only so the forced reads in the other components name a
				;; defined function; the (or ...) in front of each never reaches them.
				""");
		for (String table : LAZY_TABLES) {
			out.append("(defun ").append(builderName(table)).append(" () ").append(table).append(")\n");
		}
		return out.toString();
	}

	private static @Nullable String rewriteIllegalCharLists(String source, @Nullable String baseDir,
			SourceLoader loader) {
		String data = read(baseDir, "DerivedNormalizationProps.txt", loader);
		if (data == null) {
			return null;
		}
		// The quick-check properties, in the order the replaced cond tests them: the
		// first match wins, so a line counts for exactly one form. A "; M" line is the
		// one whose expanded entries carry T (may require renormalization); the two
		// decomposition forms have no maybe-key at all.
		Map<String, List<Integer>> rows = new LinkedHashMap<>();
		for (String form : List.of("nfd", "nfkd", "nfc", "nfkc")) {
			rows.put(form, new ArrayList<>());
		}
		for (String line : data.split("\n")) {
			String row = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
			String form;
			String maybeKey;
			if (row.contains("NFKC_QC; N") || row.contains("NFKC_QC; M")) {
				form = "nfkc";
				maybeKey = "NFKC_QC; M";
			}
			else if (row.contains("NFC_QC; N") || row.contains("NFC_QC; M")) {
				form = "nfc";
				maybeKey = "NFC_QC; M";
			}
			else if (row.contains("NFKD_QC; N")) {
				form = "nfkd";
				maybeKey = null;
			}
			else if (row.contains("NFD_QC; N")) {
				form = "nfd";
				maybeKey = null;
			}
			else {
				continue;
			}
			String codes = row.split(";", -1)[0].trim();
			int separator = codes.indexOf("..");
			int low = Integer.parseInt((separator < 0 ? codes : codes.substring(0, separator)).trim(), 16);
			int high = separator < 0 ? low : Integer.parseInt(codes.substring(separator + 2).trim(), 16);
			List<Integer> target = java.util.Objects.requireNonNull(rows.get(form));
			target.add(low);
			target.add(high);
			target.add(maybeKey != null && row.contains(maybeKey) ? 1 : 0);
		}
		StringBuilder lists = new StringBuilder();
		for (Map.Entry<String, List<Integer>> entry : rows.entrySet()) {
			lists.append(dataFunction("%lite-illegal-" + entry.getKey() + "-data", entry.getValue()));
		}
		lists.append("""
				(defvar *lite-illegal-char-lists* (make-hash-table))

				(defun %lite-expand-illegal (ints)
				  (let ((expanded '()))
				    (loop while ints
				          do (let* ((low (pop ints)) (high (pop ints)) (maybe (pop ints)))
				               (loop for code from low to high
				                     do (push (list code (if (= maybe 1) t nil)) expanded))))
				    (nreverse expanded)))

				(defun get-illegal-char-list (normalization-form)
				  """).append(documentationOf(source, "(defun get-illegal-char-list")).append("""

				  ;; Expanded on first use and cached: the four lists together are 36,532
				  ;; entries, and no caller exists in uax-15, cl-postgres or Postmodern.
				  (or (gethash normalization-form *lite-illegal-char-lists*)
				      (setf (gethash normalization-form *lite-illegal-char-lists*)
				            (%lite-expand-illegal
				             (%lite-ints
				              (ecase normalization-form
				                (:nfd  (%lite-illegal-nfd-data))
				                (:nfkd (%lite-illegal-nfkd-data))
				                (:nfc  (%lite-illegal-nfc-data))
				                (:nfkc (%lite-illegal-nfkc-data))))))))
				""");
		return replaceForm(source, LIBRARY_FILE, "(let ((nfd-illegal-list", lists.toString());
	}

	/** The scanners the emitted data forms are read back with. */
	private static final String HELPERS = """

			;; The tables below are emitted as decimal runs in string literals rather than
			;; as numeric literals: an integer literal costs two JVM constant pool entries
			;; and there are tens of thousands of them (.kb/jvm-method-size-limits.md).
			;; Each run is a LIST of short chunks, never one long literal, so no chunk can
			;; approach the JVM's 65535-byte constant limit. A number never straddles a
			;; chunk boundary.
			(defun %lite-ints (chunks)
			  "The decimal integers of the string chunks of CHUNKS, in order."
			  (let ((ints '()) (value 0) (digits nil))
			    (dolist (str chunks)
			      (dotimes (i (length str))
			        (let ((code (char-code (char str i))))
			          (if (and (>= code 48) (<= code 57))
			              (progn (setf value (+ (* value 10) (- code 48))) (setf digits t))
			              (progn (when digits (push value ints)) (setf value 0) (setf digits nil)))))
			      (when digits (push value ints) (setf value 0) (setf digits nil)))
			    (nreverse ints)))

			(defun %lite-fill-pairs (ints table)
			  "INTS is key/value pairs."
			  (loop while ints
			        do (let ((key (pop ints)))
			             (setf (gethash key table) (pop ints))))
			  table)

			;; let* throughout, never let: consecutive pops must run left to right, and
			;; a parallel let's init order is not something to lean on
			;; (.todo/014-compiler-argument-evaluation-order.md).
			(defun %lite-fill-decomp (ints table)
			  "INTS is length/codepoint/mapped-codepoints records."
			  (loop while ints
			        do (let* ((count (pop ints)) (code (pop ints)) (mapped '()))
			             (dotimes (i count) (push (pop ints) mapped))
			             (setf (gethash code table) (nreverse mapped))))
			  table)

			(defun %lite-fill-letters (ints table category)
			  "INTS is inclusive codepoint range pairs, all of CATEGORY."
			  (loop while ints
			        do (let* ((low (pop ints)) (high (pop ints)))
			             (loop for code from low to high
			                   do (setf (gethash (code-char code) table) category))))
			  table)
			""";

	/**
	 * {@return a {@code defun} of no arguments returning the integers as a quoted list of
	 * decimal-run chunks}
	 */
	private static String dataFunction(String name, List<Integer> ints) {
		List<String> chunks = new ArrayList<>();
		StringBuilder chunk = new StringBuilder();
		for (int value : ints) {
			String token = Integer.toString(value);
			if (!chunk.isEmpty() && chunk.length() + 1 + token.length() > CHUNK) {
				chunks.add(chunk.toString());
				chunk.setLength(0);
			}
			if (!chunk.isEmpty()) {
				chunk.append(' ');
			}
			chunk.append(token);
		}
		// Always at least one chunk, so the emitted shape is the same for an empty table.
		chunks.add(chunk.toString());
		StringBuilder out = new StringBuilder("\n(defun ").append(name).append(" ()\n  '(");
		for (int i = 0; i < chunks.size(); i++) {
			out.append(i == 0 ? "" : "\n    ").append('"').append(chunks.get(i)).append('"');
		}
		return out.append("))\n").toString();
	}

	/**
	 * {@return the codepoints as a flat run of inclusive range pairs}
	 */
	private static List<Integer> ranges(TreeSet<Integer> codes) {
		List<Integer> flat = new ArrayList<>();
		int low = -1;
		int high = -1;
		for (int code : codes) {
			if (low < 0) {
				low = high = code;
			}
			else if (code == high + 1) {
				high = code;
			}
			else {
				flat.add(low);
				flat.add(high);
				low = high = code;
			}
		}
		if (low >= 0) {
			flat.add(low);
			flat.add(high);
		}
		return flat;
	}

	/**
	 * Replaces the one top-level form of {@code source} that starts with {@code marker}.
	 * @throws IllegalStateException when the marker is absent or ambiguous -- the
	 * upstream release moved the form this rewrite is derived from, and loading the real
	 * file instead would silently reintroduce the cost this class exists to remove
	 */
	private static String replaceForm(String source, String componentFile, String marker, String replacement) {
		int start = markerAt(source, componentFile, marker);
		return source.substring(0, start) + replacement + source.substring(endOfForm(source, start, componentFile));
	}

	/**
	 * Replaces the run of top-level forms from the one starting with {@code startMarker}
	 * through the one starting with {@code endMarker}, inclusive. Both markers carry the
	 * same exactly-once guarantee {@link #replaceForm} does.
	 */
	private static String replaceSpan(String source, String componentFile, String startMarker, String endMarker,
			String replacement) {
		int start = markerAt(source, componentFile, startMarker);
		int last = markerAt(source, componentFile, endMarker);
		if (last < start) {
			throw new IllegalStateException("the " + SYSTEM + " release in the quicklisp cache has '" + endMarker
					+ "' BEFORE '" + startMarker + "' in " + componentFile
					+ "; the derived-table rewrite in Uax15Tables must be updated for it");
		}
		return source.substring(0, start) + replacement + source.substring(endOfForm(source, last, componentFile));
	}

	/**
	 * {@return the verbatim text of the one form starting with {@code marker}}
	 */
	private static String formAt(String source, String componentFile, String marker) {
		int start = markerAt(source, componentFile, marker);
		return source.substring(start, endOfForm(source, start, componentFile));
	}

	/**
	 * {@return the verbatim text of the VALUE form of the {@code defvar}/
	 * {@code defparameter} starting with {@code marker}} The marker is expected to end
	 * with the variable name, so the value is the next form after it.
	 */
	private static String valueFormOf(String source, String componentFile, String marker) {
		int at = markerAt(source, componentFile, marker) + marker.length();
		while (at < source.length() && Character.isWhitespace(source.charAt(at))) {
			at++;
		}
		return source.substring(at, endOfForm(source, at, componentFile));
	}

	/**
	 * {@return the verbatim text between the END of the form starting with
	 * {@code afterMarker} and the end of the form starting with {@code endMarker}} -- the
	 * run of forms a span replacement relocates.
	 */
	private static String spanBody(String source, String componentFile, String afterMarker, String endMarker) {
		int from = endOfForm(source, markerAt(source, componentFile, afterMarker), componentFile);
		int last = markerAt(source, componentFile, endMarker);
		return source.substring(from, endOfForm(source, last, componentFile));
	}

	/**
	 * {@return the index of the one occurrence of {@code marker} in {@code source}}
	 * @throws IllegalStateException when the marker is absent or ambiguous
	 */
	private static int markerAt(String source, String componentFile, String marker) {
		int start = source.indexOf(marker);
		if (start < 0 || source.indexOf(marker, start + 1) >= 0) {
			throw new IllegalStateException("the " + SYSTEM
					+ " release in the quicklisp cache no longer has exactly one" + " form starting with '" + marker
					+ "' in " + componentFile + "; the derived-table rewrite in Uax15Tables must be updated for it");
		}
		return start;
	}

	/**
	 * {@return the index just past the s-expression starting at {@code start}}
	 */
	private static int endOfForm(String source, int start, String componentFile) {
		int depth = 0;
		for (int i = start; i < source.length(); i++) {
			char c = source.charAt(i);
			switch (c) {
				case '(' -> depth++;
				case ')' -> {
					if (--depth == 0) {
						return i + 1;
					}
				}
				case '"' -> {
					i++;
					while (i < source.length() && source.charAt(i) != '"') {
						i += source.charAt(i) == '\\' ? 2 : 1;
					}
				}
				case ';' -> {
					while (i < source.length() && source.charAt(i) != '\n') {
						i++;
					}
				}
				case '#' -> {
					if (i + 1 < source.length() && source.charAt(i + 1) == '\\') {
						// A character literal: #\( and #\) are not delimiters.
						i += 2;
					}
					else if (i + 1 < source.length() && source.charAt(i + 1) == '|') {
						i = source.indexOf("|#", i + 2);
						if (i < 0) {
							throw new IllegalStateException("unterminated #| |# comment in " + componentFile);
						}
						i++;
					}
				}
				default -> {
				}
			}
		}
		throw new IllegalStateException("unterminated form at " + start + " in " + componentFile);
	}

	/**
	 * {@return the docstring literal of the {@code defun} starting with {@code marker},
	 * so the replacement keeps the upstream documentation verbatim}
	 */
	private static String documentationOf(String source, String marker) {
		int defun = source.indexOf(marker);
		int open = source.indexOf('"', defun);
		int i = open + 1;
		while (i < source.length() && source.charAt(i) != '"') {
			i += source.charAt(i) == '\\' ? 2 : 1;
		}
		return source.substring(open, i + 1);
	}

	private static @Nullable String read(@Nullable String baseDir, String file, SourceLoader loader) {
		try {
			return loader.load(SourceLoader.resolve(baseDir, DATA_DIR + "/" + file));
		}
		catch (IOException | RuntimeException ex) {
			// No data files (a host with no filesystem, or a stripped checkout): the real
			// source loads unchanged and builds the tables the slow way.
			return null;
		}
	}

}
