package am.ik.rontolisp.eval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * Rewrites the two table-BUILDING forms of the {@code uax-15} system so the tables are
 * derived from the bundled Unicode data files once, while the system is being loaded or
 * compiled, and emitted as data -- instead of being rebuilt by parsing 2.7 MB of text
 * through {@code cl-ppcre} every time the program runs.
 *
 * <p>
 * Everything that computes a normalization stays VERBATIM upstream: only the way the
 * tables are OBTAINED changes, and the contents are identical, so {@code normalize} (and
 * {@code get-illegal-char-list}) answer exactly what the real load would answer. Three
 * spans are replaced, each located by a marker that must occur exactly once -- an
 * upstream release that moves them fails loudly rather than silently going stale:
 *
 * <ul>
 * <li>{@code src/precomputed-tables.lisp}: the {@code (defvar *unicode-data* ...)} that
 * reads and splits all 34,924 rows of {@code UnicodeData.txt} (21.4 s of the load, and a
 * value NO consumer outside that file ever reads) becomes {@code nil}, and the
 * {@code let} that folds those rows into the combining-class and the two decomposition
 * maps becomes the three tables as data. The data-derived letter entries of
 * {@code *unicode-letters*} are appended at the end of the file, where the file's own
 * hardcoded CJK/Hangul/Tangut range loops have already run (those are ~100 ms and stay
 * verbatim).</li>
 * <li>{@code src/uax-15.lisp}: the {@code let} that folds {@code
 * DerivedNormalizationProps.txt} into the four illegal-character lists (9.5 s, with a
 * quadratic {@code nconcf}) becomes the source RANGE rows as data, expanded on demand
 * inside {@code get-illegal-char-list} and cached -- so a program that never calls it (no
 * caller exists in uax-15, cl-postgres or Postmodern) never pays for the lists at all,
 * and one that does gets the identical list.</li>
 * </ul>
 *
 * <p>
 * Bulk numbers are emitted as decimal runs inside STRING literals scanned by a generated
 * helper, never as numeric literals: an integer literal costs two constant pool entries
 * on the JVM backend (it is a boxed {@code long}), and ~25,000 of them push a
 * library-scale class past the 65534-entry class-format ceiling -- see
 * {@code .kb/jvm-method-size-limits.md}, where that overflow is what made this
 * substitution look like an operand-stack bug.
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

	/**
	 * How long an emitted string literal may get. A {@code CONSTANT_Utf8} allows 65535
	 * bytes and the WASM data segment more, but the compile paths already chunk baked
	 * file contents at 20,000 characters ({@code cli.CompileTimePathnameFolder}), so this
	 * stays under that.
	 */
	private static final int CHUNK = 18000;

	/** The letter categories the replaced loop selects, in the order it lists them. */
	private static final List<String> LETTER_CATEGORIES = List.of("Ll", "Lu", "Lm", "Lt", "Lo");

	private Uax15Tables() {
	}

	/**
	 * Returns the rewritten source of the given component, or {@code null} when this
	 * component is not one of the two rewritten ones (or the bundled data files cannot be
	 * read, in which case the real source loads unchanged).
	 * @param componentFile the component path, relative to the system's base directory
	 * @param source the real component source
	 * @param baseDir the system's base directory
	 * @param loader the loader the data files are read through
	 * @return the rewritten source, or {@code null} to keep the real one
	 */
	static @Nullable String rewrite(String componentFile, String source, @Nullable String baseDir,
			SourceLoader loader) {
		return switch (componentFile) {
			case "src/precomputed-tables.lisp" -> rewriteTables(source, baseDir, loader);
			case "src/uax-15.lisp" -> rewriteIllegalCharLists(source, baseDir, loader);
			default -> null;
		};
	}

	private static @Nullable String rewriteTables(String source, @Nullable String baseDir, SourceLoader loader) {
		String data = read(baseDir, "UnicodeData.txt", loader);
		if (data == null) {
			return null;
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
		StringBuilder tables = new StringBuilder();
		tables.append(HELPERS);
		tables.append(dataFunction("%lite-combining-class-data", combiningClasses));
		tables.append(dataFunction("%lite-canonical-decomp-data", canonicalDecomp));
		tables.append(dataFunction("%lite-compatible-decomp-data", compatibleDecomp));
		tables.append("""
				(setf *canonical-combining-class*
				      (%lite-fill-pairs (%lite-ints (%lite-combining-class-data)) (make-hash-table)))
				(setf *canonical-decomp-map*
				      (%lite-fill-decomp (%lite-ints (%lite-canonical-decomp-data)) (make-hash-table)))
				(setf *compatible-decomp-map*
				      (%lite-fill-decomp (%lite-ints (%lite-compatible-decomp-data)) (make-hash-table)))
				""");
		String rewritten = replaceForm(source, "src/precomputed-tables.lisp", "(defvar *unicode-data*", """
				;; Nothing outside this file ever read the rows: the tables below are the
				;; only consumers, and they are data now.
				(defvar *unicode-data* nil)
				""");
		rewritten = replaceForm(rewritten, "src/precomputed-tables.lisp", "(let ((canonical-decomp-map",
				tables.toString());
		StringBuilder letterFills = new StringBuilder("\n");
		for (Map.Entry<String, TreeSet<Integer>> entry : letters.entrySet()) {
			String name = "%lite-letters-" + entry.getKey().toLowerCase() + "-data";
			letterFills.append(dataFunction(name, ranges(entry.getValue())));
			letterFills.append("(%lite-fill-letters (%lite-ints (")
				.append(name)
				.append(")) *unicode-letters* \"")
				.append(entry.getKey())
				.append("\")\n");
		}
		return rewritten + letterFills;
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
		return replaceForm(source, "src/uax-15.lisp", "(let ((nfd-illegal-list", lists.toString());
	}

	/** The scanners the emitted data forms are read back with. */
	private static final String HELPERS = """

			;; The tables below are emitted as decimal runs in string literals rather than
			;; as numeric literals: an integer literal costs two JVM constant pool entries
			;; and there are tens of thousands of them (.kb/jvm-method-size-limits.md).
			(defun %lite-ints (str)
			  "The decimal integers of STR, in order."
			  (let ((ints '()) (value 0) (digits nil))
			    (dotimes (i (length str))
			      (let ((code (char-code (char str i))))
			        (if (and (>= code 48) (<= code 57))
			            (progn (setf value (+ (* value 10) (- code 48))) (setf digits t))
			            (progn (when digits (push value ints)) (setf value 0) (setf digits nil)))))
			    (when digits (push value ints))
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
	 * {@return a {@code defun} of no arguments returning the integers as a decimal run,
	 * chunked into string literals}
	 */
	private static String dataFunction(String name, List<Integer> ints) {
		StringBuilder text = new StringBuilder();
		for (int value : ints) {
			if (!text.isEmpty()) {
				text.append(' ');
			}
			text.append(value);
		}
		List<String> chunks = new ArrayList<>();
		for (int start = 0; start < text.length(); start += CHUNK) {
			chunks.add(text.substring(start, Math.min(text.length(), start + CHUNK)));
		}
		StringBuilder out = new StringBuilder("\n(defun ").append(name).append(" ()\n  ");
		if (chunks.size() <= 1) {
			out.append('"').append(chunks.isEmpty() ? "" : chunks.getFirst()).append('"');
		}
		else {
			out.append("(concatenate 'string");
			for (String chunk : chunks) {
				out.append("\n   \"").append(chunk).append('"');
			}
			out.append(')');
		}
		return out.append(")\n").toString();
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
		int start = source.indexOf(marker);
		if (start < 0 || source.indexOf(marker, start + 1) >= 0) {
			throw new IllegalStateException("the " + SYSTEM
					+ " release in the quicklisp cache no longer has exactly one" + " form starting with '" + marker
					+ "' in " + componentFile + "; the derived-table rewrite in Uax15Tables must be updated for it");
		}
		return source.substring(0, start) + replacement + source.substring(endOfForm(source, start, componentFile));
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
