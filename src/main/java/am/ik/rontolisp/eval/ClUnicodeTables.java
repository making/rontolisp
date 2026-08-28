package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The build step of cl-unicode, performed here instead of by the library.
 *
 * <p>
 * cl-unicode ships as SOURCES PLUS UCD DATA: three of the eight components it names --
 * {@code lists.lisp}, {@code hash-tables.lisp} and {@code methods.lisp} -- do not exist
 * in the release at all. Real ASDF materializes them by loading the separate
 * {@code cl-unicode/build} system (a UCD parser over {@code build/data/*.txt}) and
 * running its {@code :perform (load-op ...)} hook, which WRITES the three files next to
 * the sources; a {@code :output-files} declaration plus a {@code component-depends-on}
 * method on {@code prepare-op} is what wires that in. None of those three ASDF facilities
 * exists in the defsystem-as-data subset ({@code .kb/asdf.md}), and the generator itself
 * is a CLOS pass over a 1.1M-element array of instances -- so the library cannot build
 * itself here at any acceptable cost.
 *
 * <p>
 * What it CAN do is have the same three files handed to it. This class parses the same
 * bundled data files and emits the same definitions, in Java, at load time: the range
 * trees the {@code methods.lisp} methods look up, the hash tables
 * {@code hash-tables.lisp} fills, and the property-symbol lists {@code lists.lisp} sets.
 * Everything else in the library -- the API, the derived-property algebra, the
 * conditions, the aliases -- is the real upstream source, loaded verbatim. This is the
 * {@link Uax15Tables} pattern (derive from the bundled data at build time rather than at
 * run time) taken one step further: there the file exists and its table-building forms
 * are rewritten, here the file is the output.
 *
 * <p>
 * Faithfulness is the point, so the emitted DATA matches what {@code build/dump.lisp}
 * writes, quirks included: {@code build-range-list} stops at
 * {@code +code-point-limit+ - 1}, so the last range ends at {@code #x10FFFE} and
 * {@code #x10FFFF} is covered by no range; {@code dump-hash-table} drops a nil value; and
 * the {@code pushnew} lists come out in reverse order of first appearance. Reproducing
 * them keeps the generated data identical to what a real {@code cl-unicode/build} run
 * would have written, which is what makes the upstream test suite meaningful against it.
 * The one quirk that is NOT reproduced is {@code split-range-list}'s round-half-to-EVEN
 * middle, because the balanced tree it shaped is gone (below) and its shape was never
 * visible in an answer.
 *
 * <p>
 * The deliberate deviations are all about SIZE, because {@code build/dump.lisp}'s own
 * shape does not fit a compiled program: written out literally the three components are
 * ~5 MB holding ~140,000 integer literals and ~68,000 distinct name strings, and a
 * {@code .class} may name 65534 constants in total ({@code .kb/jvm-method-size-limits.md}
 * -- an integer costs two entries, a string two more). So the data travels as its own
 * PRINTED TEXT inside ~230 string literals and is read back with
 * {@code read-from-string}, through the generated helpers in {@code lists.lisp}
 * ({@link #HELPERS}). Reading is what makes that affordable: the reader is native on
 * every backend, where a Lisp-level scan of the same characters is 40x slower.
 *
 * <p>
 * A range tree additionally becomes the flat ascending range table {@link #HELPERS}'
 * {@code %lookup} binary-searches, built on the FIRST lookup of that property and never
 * again -- so a program that never asks for a decomposition never reads that table's
 * 17,272 ranges, and one that asks for no property at all reads none of the fourteen.
 *
 * <p>
 * What the deviations do NOT change is any answer: every table is filled with the same
 * entries in the same order, the range list is the same partition
 * {@code build-range-list} computes, and {@code %lookup} returns what {@code tree-lookup}
 * returns for every code point -- the balanced tree was only ever an index over that
 * list.
 */
final class ClUnicodeTables {

	/** The ASDF system whose missing components this class generates (canonical name). */
	static final String SYSTEM = "cl-unicode";

	/** The bundled UCD directory, relative to the system's base directory. */
	private static final String DATA_DIR = "build/data";

	/** The components that do not exist in the release and are generated here. */
	private static final List<String> GENERATED = List.of("lists.lisp", "hash-tables.lisp", "methods.lisp");

	/** {@code +code-point-limit+}: the smallest integer that is not a code point. */
	private static final int LIMIT = 0x110000;

	/**
	 * How many characters the printed text of one emitted chunk carries. The text is cut
	 * into chunks rather than left as one literal because a JVM string constant may not
	 * exceed 65535 UTF-8 bytes and these tables are megabytes; the chunks are as large as
	 * that ceiling comfortably allows, so there are ~230 of them in all against the
	 * ~208,000 constants the literal dump wanted. It is NOT about scan cost -- nothing
	 * scans a chunk character by character, the reader reads it whole.
	 */
	private static final int RUN_CHUNK = 20_000;

	/**
	 * How many elements one chunk carries, whatever its length. The reader recurses per
	 * element, so an unbounded list is a stack overflow waiting for the biggest table --
	 * a 30,000-element one overflows. Only the short elements (a range start) ever reach
	 * this bound before {@link #RUN_CHUNK}.
	 */
	private static final int RUN_ELEMENTS = 1_000;

	/**
	 * The Hangul syllable block, whose decomposition is derived rather than tabulated.
	 */
	private static final int FIRST_HANGUL = 0xAC00;

	private static final int LAST_HANGUL = 0xD7A3;

	private static final int S_BASE = 0xAC00;

	private static final int L_BASE = 0x1100;

	private static final int V_BASE = 0x1161;

	private static final int T_BASE = 0x11A7;

	private static final int T_COUNT = 28;

	private static final int N_COUNT = 21 * 28;

	/**
	 * {@code canonicalize-name}: drop whitespace, hyphens and underscores, but keep a
	 * hyphen that a space or underscore introduces in the two ambiguous Unicode name
	 * endings (see UTR #18).
	 */
	private static final Pattern CANONICALIZE = Pattern.compile("[ _](-A|O-E)$|[-_\\s]");

	/**
	 * The decoders the emitted runs are read back with, emitted at the top of
	 * {@code lists.lisp} -- the first of the three generated components, so both of the
	 * others can call them. Every name is prefixed, so nothing here depends on which
	 * package a canonical-shape component is read in.
	 */
	private static final String HELPERS = """

			;;; The tables below travel as the PRINTED TEXT of their data inside a handful of
			;;; string literals, read back with READ-FROM-STRING, rather than as literals of
			;;; their own: an integer literal costs two JVM constant pool entries and a
			;;; distinct string two more, and the written-out dump wants ~208,000 of them
			;;; against the 65534 a class may name (.kb/jvm-method-size-limits.md).  Reading
			;;; is what keeps that affordable -- the reader is native on every backend, at
			;;; ~1.5us an element, where a Lisp-level scan of the same characters costs 8us
			;;; EACH.  A run is a LIST of chunks for two reasons: a string constant may not
			;;; exceed 65535 UTF-8 bytes, and the reader recurses per element.

			(defun cl-unicode::%read (chunks)
			  "The elements of CHUNKS -- each the printed representation of one list -- in
			order.  Appended right to left, so each chunk is copied exactly once."
			  (let ((all '()))
			    (dolist (chunk (reverse chunks))
			      (setq all (append (read-from-string chunk) all)))
			    all))

			(defun cl-unicode::%table (start-chunks value-chunks)
			  "The range table a generated method looks up: the ascending vector of the
			inclusive starts of BUILD-RANGE-LIST's ranges, and the parallel vector of their
			values.  The ranges partition the code space, so a start is all a range needs."
			  (cons (coerce (cl-unicode::%read start-chunks) 'vector)
			        (coerce (cl-unicode::%read value-chunks) 'vector)))

			(defun cl-unicode::%lookup (code-point table)
			  "What TREE-LOOKUP answers, over the flat range table TABLE: the value of the
			range holding CODE-POINT, or nil when no range does.  BUILD-RANGE-LIST returns at
			(1- +code-point-limit+), so the ranges cover 0..#x10FFFE and #x10FFFF is in none."
			  (if (or (< code-point 0) (> code-point 1114110))
			      nil
			      (let* ((starts (car table))
			             (low 0)
			             (high (- (length starts) 1)))
			        (loop while (< low high) do
			          (let ((middle (floor (+ low high 1) 2)))
			            (if (<= (aref starts middle) code-point)
			                (setq low middle)
			                (setq high (- middle 1)))))
			        (aref (cdr table) low))))
			""";

	/** Parsed forms per system base directory: the parse is the expensive half. */
	private static final Map<String, Map<String, List<LispVal>>> CACHE = new ConcurrentHashMap<>();

	private ClUnicodeTables() {
	}

	/**
	 * {@return whether the named component of cl-unicode is one this class generates}
	 * @param componentFile the component path, relative to the system's base directory
	 */
	static boolean generates(String componentFile) {
		return GENERATED.contains(componentFile);
	}

	/**
	 * Returns the forms of a generated component, deriving all three from the bundled UCD
	 * data on the first call for a given system directory.
	 * @param componentFile the component path, relative to the system's base directory
	 * @param baseDir the system's base directory
	 * @param loader the loader the data files are read through
	 * @return the component's forms
	 */
	static List<LispVal> forms(String componentFile, @Nullable String baseDir, SourceLoader loader) {
		Map<String, List<LispVal>> generated = CACHE.computeIfAbsent(baseDir == null ? "" : baseDir,
				dir -> generate(dir, loader));
		List<LispVal> forms = generated.get(componentFile);
		if (forms == null) {
			throw new IllegalArgumentException("Not a generated " + SYSTEM + " component: " + componentFile);
		}
		return forms;
	}

	/**
	 * Derives the three generated components as SOURCE, keyed by component name. This is
	 * what {@link #forms} reads; it is separate so the derivation can be asserted as text
	 * (a range tree and a fill form are far easier to read that way than as parsed
	 * values).
	 * @param baseDir the system's base directory
	 * @param loader the loader the bundled UCD files are read through
	 * @return the generated sources
	 */
	static Map<String, String> sources(String baseDir, SourceLoader loader) {
		Ucd ucd = new Ucd(baseDir, loader);
		ucd.fillDatabase();
		Map<String, String> out = new HashMap<>();
		out.put("lists.lisp", ucd.dumpLists());
		out.put("hash-tables.lisp", ucd.dumpHashTables());
		out.put("methods.lisp", ucd.dumpMethods());
		return Map.copyOf(out);
	}

	private static Map<String, List<LispVal>> generate(String baseDir, SourceLoader loader) {
		Map<String, List<LispVal>> out = new HashMap<>();
		for (Map.Entry<String, String> entry : sources(baseDir, loader).entrySet()) {
			out.put(entry.getKey(), read(entry.getValue()));
		}
		return Map.copyOf(out);
	}

	private static List<LispVal> read(String source) {
		// Generated data carries no reader conditional, so the feature set is immaterial.
		return LispReader.readAllFromString(source, Features.INTERPRETER);
	}

	/** A property symbol: a symbol in the {@code cl-unicode-names} package. */
	private record Sym(int id) {
	}

	/** A Lisp rational, the type {@code numeric-value} answers. */
	private record Rat(BigInteger numerator, BigInteger denominator) {
	}

	/** A dotted pair: {@code *composition-mappings*} holds alists of them. */
	private record Pair(Object car, Object cdr) {
	}

	/**
	 * The parsed UCD, in the shape {@code build/read.lisp} builds it: one attribute array
	 * per {@code char-info} slot that every code point has, and a sparse map per slot
	 * only some do.
	 */
	private static final class Ucd {

		private final String baseDir;

		private final SourceLoader loader;

		// -- the property-symbol registry (property-symbol / *canonical-names*) --
		private final Map<String, Integer> symbolIds = new HashMap<>();

		private final List<String> symbolKeys = new ArrayList<>();

		private final List<String> canonicalNames = new ArrayList<>();

		// -- the char-info slots that exist for every code point --
		private final boolean[] present = new boolean[LIMIT];

		private final short[] generalCategory = new short[LIMIT];

		private final short[] script = new short[LIMIT];

		private final short[] codeBlock = new short[LIMIT];

		private final short[] wordBreak = new short[LIMIT];

		private final short[] bidiClass = new short[LIMIT];

		private final short[] numericType = new short[LIMIT];

		private final short[] age = new short[LIMIT];

		private final short[] combiningClass = new short[LIMIT];

		private final int[] mirroringGlyph = new int[LIMIT];

		// -- the sparse slots --
		private final Map<Integer, String> names = new HashMap<>();

		private final Map<Integer, String> unicode1Names = new HashMap<>();

		private final Map<Integer, Rat> numericValue = new HashMap<>();

		private final Map<Integer, int[]> caseMapping = new HashMap<>();

		private final Map<Integer, List<Object>> binaryProps = new HashMap<>();

		private final Map<Integer, List<Object>> idnaMapping = new HashMap<>();

		private final Map<Integer, List<Object>> decomposition = new HashMap<>();

		private final Map<Integer, List<Object>> caseFolding = new HashMap<>();

		private final Map<Integer, List<Object>> specialCaseMappings = new LinkedHashMap<>();

		private final Map<Integer, String> jamoShortNames = new LinkedHashMap<>();

		private final Map<String, Integer> propertyAliases = new LinkedHashMap<>();

		private final Map<Integer, List<Object>> compositionMappings = new LinkedHashMap<>();

		/** The distinct {@code (major minor)} age values, indexed by the age array. */
		private final List<List<Object>> ages = new ArrayList<>();

		private final Map<String, Integer> ageIds = new HashMap<>();

		// -- the pushnew lists lists.lisp publishes (kept in pushnew order: newest first)
		private final List<Object> generalCategories = new ArrayList<>();

		private final List<Object> compatibilityFormattingTags = new ArrayList<>();

		private final List<Object> scripts = new ArrayList<>();

		private final List<Object> codeBlocks = new ArrayList<>();

		private final List<Object> binaryProperties = new ArrayList<>();

		private final List<Object> bidiClasses = new ArrayList<>();

		private int cnSymbol;

		private int otherSymbol;

		Ucd(String baseDir, SourceLoader loader) {
			this.baseDir = baseDir;
			this.loader = loader;
			Arrays.fill(this.generalCategory, (short) -1);
			Arrays.fill(this.script, (short) -1);
			Arrays.fill(this.codeBlock, (short) -1);
			Arrays.fill(this.wordBreak, (short) -1);
			Arrays.fill(this.bidiClass, (short) -1);
			Arrays.fill(this.numericType, (short) -1);
			Arrays.fill(this.age, (short) -1);
			Arrays.fill(this.mirroringGlyph, -1);
		}

		// ------------------------------------------------------------------
		// property symbols
		// ------------------------------------------------------------------

		private int propertySymbol(String name) {
			String key = canonicalize(name).toUpperCase(Locale.ROOT);
			Integer id = this.symbolIds.get(key);
			if (id != null) {
				return id;
			}
			int fresh = this.symbolKeys.size();
			this.symbolIds.put(key, fresh);
			this.symbolKeys.add(key);
			this.canonicalNames.add(null);
			return fresh;
		}

		private int registerPropertySymbol(String name) {
			int id = propertySymbol(name);
			this.canonicalNames.set(id, name);
			return id;
		}

		private Sym sym(String name) {
			return new Sym(registerPropertySymbol(name));
		}

		// ------------------------------------------------------------------
		// data files
		// ------------------------------------------------------------------

		private List<String[]> lines(String file) {
			String source;
			try {
				source = this.loader.load(SourceLoader.resolve(this.baseDir, DATA_DIR + "/" + file));
			}
			catch (IOException ex) {
				throw new UncheckedIOException(
						"the cl-unicode release in the cache is missing its bundled UCD file " + file, ex);
			}
			List<String[]> out = new ArrayList<>();
			for (String raw : source.split("\n", -1)) {
				int comment = raw.indexOf('#');
				String line = (comment < 0 ? raw : raw.substring(0, comment)).trim();
				if (line.isEmpty()) {
					continue;
				}
				String[] fields = line.split(";", -1);
				for (int i = 0; i < fields.length; i++) {
					fields[i] = fields[i].trim();
				}
				out.add(fields);
			}
			return out;
		}

		private static String field(String[] fields, int index) {
			return index < fields.length ? fields[index] : "";
		}

		private static int parseHex(String value) {
			return Integer.parseInt(value, 16);
		}

		/** {@code parse-code-point}: {@code {start, end}}, both inclusive. */
		private static int[] codePointRange(String value) {
			int dots = value.indexOf("..");
			if (dots < 0) {
				int one = parseHex(value);
				return new int[] { one, one };
			}
			return new int[] { parseHex(value.substring(0, dots)), parseHex(value.substring(dots + 2)) };
		}

		private static @Nullable List<Object> hexList(String value) {
			if (value.isEmpty()) {
				return null;
			}
			List<Object> out = new ArrayList<>();
			for (String part : value.split("\\s+")) {
				out.add(parseHex(part));
			}
			return out;
		}

		// ------------------------------------------------------------------
		// fill-database
		// ------------------------------------------------------------------

		void fillDatabase() {
			initializePropertySymbols();
			readCharacterData();
			readScripts();
			readCodeBlocks();
			readWordBreaks();
			readBinaryProperties();
			readDerivedAge();
			readMirroringGlyphs();
			readJamo();
			readPropertyAliases();
			readIdnaMapping();
			readSpecialCasing();
			readCaseFoldingMapping();
			setDefaultBidiClasses();
			addHangulDecomposition();
			buildCompositionMappings();
		}

		private void initializePropertySymbols() {
			for (String name : new String[] { "Cn", "AL", "R", "L", "Decimal", "Digit", "Numeric", "BidiMirrored",
					"NoncharacterCodePoint" }) {
				registerPropertySymbol(name);
			}
			this.cnSymbol = propertySymbol("Cn");
			this.otherSymbol = registerPropertySymbol("Other");
		}

		/** Creates a {@code char-info} at {@code cp} with the class's own initforms. */
		private void ensurePresent(int cp) {
			if (this.present[cp]) {
				return;
			}
			this.present[cp] = true;
			this.generalCategory[cp] = (short) this.cnSymbol;
			this.wordBreak[cp] = (short) this.otherSymbol;
			this.combiningClass[cp] = 0;
		}

		private void readCharacterData() {
			this.generalCategories.add(new Sym(this.cnSymbol));
			List<String[]> lines = lines("UnicodeData.txt");
			for (int index = 0; index < lines.size(); index++) {
				String[] fields = lines.get(index);
				int[] range = codePointRange(fields[0]);
				String name = field(fields, 1);
				if (name.matches("^<.*, First>$") && index + 1 < lines.size()) {
					// A <..., First> line names a RANGE whose end is the first field of
					// the following <..., Last> line, which upstream's reader consumes
					// with it -- so that line is not a record of its own.
					range = new int[] { range[0], codePointRange(lines.get(++index)[0])[1] };
				}
				int category = registerPropertySymbol(field(fields, 2));
				int combining = Integer.parseInt(field(fields, 3));
				int bidi = registerPropertySymbol(field(fields, 4));
				List<Object> decompositionMapping = taggedHexList(field(fields, 5));
				boolean decimalDigit = !field(fields, 6).isEmpty();
				boolean digit = !field(fields, 7).isEmpty();
				Rat numeric = rational(field(fields, 8));
				boolean mirrored = "Y".equals(field(fields, 9));
				String unicode1Name = field(fields, 10);
				Integer upper = optionalHex(field(fields, 12));
				Integer lower = optionalHex(field(fields, 13));
				Integer title = optionalHex(field(fields, 14));

				if (decompositionMapping != null && !decompositionMapping.isEmpty()
						&& decompositionMapping.get(0) instanceof Sym tag) {
					pushNew(this.compatibilityFormattingTags, tag);
				}
				pushNew(this.generalCategories, new Sym(category));
				pushNew(this.bidiClasses, new Sym(bidi));
				if (name.startsWith("<")) {
					name = "";
				}
				Integer numericTypeId = decimalDigit ? Integer.valueOf(propertySymbol("Decimal"))
						: digit ? Integer.valueOf(propertySymbol("Digit"))
								: numeric != null ? Integer.valueOf(propertySymbol("Numeric")) : null;
				for (int cp = range[0]; cp <= range[1]; cp++) {
					ensurePresent(cp);
					this.generalCategory[cp] = (short) category;
					this.combiningClass[cp] = (short) combining;
					this.bidiClass[cp] = (short) bidi;
					if (!name.isEmpty()) {
						this.names.put(cp, name);
					}
					if (!unicode1Name.isEmpty()) {
						this.unicode1Names.put(cp, unicode1Name);
					}
					if (decompositionMapping != null) {
						this.decomposition.put(cp, decompositionMapping);
					}
					if (numericTypeId != null) {
						this.numericType[cp] = numericTypeId.shortValue();
					}
					if (numeric != null) {
						this.numericValue.put(cp, numeric);
					}
					if (mirrored) {
						this.binaryProps.put(cp, new ArrayList<>(List.of(new Sym(propertySymbol("BidiMirrored")))));
					}
					if (upper != null || lower != null || title != null) {
						this.caseMapping.put(cp, new int[] { lower == null ? -1 : lower, upper == null ? -1 : upper,
								title == null ? -1 : title });
					}
				}
			}
		}

		private @Nullable Integer optionalHex(String value) {
			return value.isEmpty() ? null : parseHex(value);
		}

		private @Nullable List<Object> taggedHexList(String value) {
			if (value.isEmpty()) {
				return null;
			}
			List<Object> out = new ArrayList<>();
			for (String part : value.split("\\s+")) {
				out.add(part.startsWith("<") ? new Sym(registerPropertySymbol(part)) : (Object) parseHex(part));
			}
			return out;
		}

		private static @Nullable Rat rational(String value) {
			if (value.isEmpty()) {
				return null;
			}
			int slash = value.indexOf('/');
			BigInteger numerator = new BigInteger(slash < 0 ? value : value.substring(0, slash));
			BigInteger denominator = slash < 0 ? BigInteger.ONE : new BigInteger(value.substring(slash + 1));
			BigInteger divisor = numerator.gcd(denominator);
			if (divisor.signum() != 0) {
				numerator = numerator.divide(divisor);
				denominator = denominator.divide(divisor);
			}
			if (denominator.signum() < 0) {
				numerator = numerator.negate();
				denominator = denominator.negate();
			}
			return new Rat(numerator, denominator);
		}

		private void readScripts() {
			for (String[] fields : lines("Scripts.txt")) {
				int[] range = codePointRange(fields[0]);
				int symbol = registerPropertySymbol(field(fields, 1));
				pushNew(this.scripts, new Sym(symbol));
				for (int cp = range[0]; cp <= range[1]; cp++) {
					if (this.present[cp]) {
						this.script[cp] = (short) symbol;
					}
				}
			}
		}

		private void readCodeBlocks() {
			for (String[] fields : lines("Blocks.txt")) {
				int[] range = codePointRange(fields[0]);
				int symbol = registerPropertySymbol(field(fields, 1));
				pushNew(this.codeBlocks, new Sym(symbol));
				for (int cp = range[0]; cp <= range[1]; cp++) {
					if (this.present[cp]) {
						this.codeBlock[cp] = (short) symbol;
					}
				}
			}
		}

		private void readWordBreaks() {
			for (String[] fields : lines("auxiliary/WordBreakProperty.txt")) {
				int[] range = codePointRange(fields[0]);
				int symbol = registerPropertySymbol(field(fields, 1));
				for (int cp = range[0]; cp <= range[1]; cp++) {
					if (this.present[cp]) {
						this.wordBreak[cp] = (short) symbol;
					}
				}
			}
		}

		private void readBinaryProperties() {
			this.binaryProperties.add(new Sym(propertySymbol("BidiMirrored")));
			int noncharacter = propertySymbol("NoncharacterCodePoint");
			for (String[] fields : lines("PropList.txt")) {
				int[] range = codePointRange(fields[0]);
				int symbol = registerPropertySymbol(field(fields, 1));
				if (symbol == noncharacter) {
					continue;
				}
				pushNew(this.binaryProperties, new Sym(symbol));
				for (int cp = range[0]; cp <= range[1]; cp++) {
					ensurePresent(cp);
					this.binaryProps.computeIfAbsent(cp, key -> new ArrayList<>()).add(0, new Sym(symbol));
				}
			}
		}

		private void readDerivedAge() {
			for (String[] fields : lines("DerivedAge.txt")) {
				int[] range = codePointRange(fields[0]);
				String value = field(fields, 1);
				int ageId = this.ageIds.computeIfAbsent(value, key -> {
					String[] parts = key.split("\\.");
					this.ages.add(List.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
					return this.ages.size() - 1;
				});
				for (int cp = range[0]; cp <= range[1]; cp++) {
					if (this.present[cp]) {
						this.age[cp] = (short) ageId;
					}
				}
			}
		}

		private void readMirroringGlyphs() {
			for (String[] fields : lines("BidiMirroring.txt")) {
				int[] range = codePointRange(fields[0]);
				int glyph = parseHex(field(fields, 1));
				for (int cp = range[0]; cp <= range[1]; cp++) {
					if (this.present[cp]) {
						this.mirroringGlyph[cp] = glyph;
					}
				}
			}
		}

		private void readJamo() {
			for (String[] fields : lines("Jamo.txt")) {
				int[] range = codePointRange(fields[0]);
				String shortName = field(fields, 1);
				for (int cp = range[0]; cp <= range[1]; cp++) {
					this.jamoShortNames.put(cp, shortName);
				}
			}
		}

		private void readPropertyAliases() {
			for (String[] fields : lines("PropertyAliases.txt")) {
				if (fields.length < 2) {
					continue;
				}
				int symbol = propertySymbol(fields[1]);
				this.propertyAliases.put(canonicalize(fields[1]).toUpperCase(Locale.ROOT), symbol);
				this.propertyAliases.put(canonicalize(fields[0]).toUpperCase(Locale.ROOT), symbol);
				for (int i = 2; i < fields.length; i++) {
					if (!fields[i].isEmpty()) {
						this.propertyAliases.put(canonicalize(fields[i]).toUpperCase(Locale.ROOT), symbol);
					}
				}
			}
		}

		private void readIdnaMapping() {
			int disallowed = propertySymbol("disallowed");
			for (String[] fields : lines("idna/IdnaMappingTable.txt")) {
				int[] range = codePointRange(fields[0]);
				int type = registerPropertySymbol(field(fields, 1));
				if (type == disallowed) {
					continue;
				}
				List<Object> mappedTo = hexList(field(fields, 2));
				String scopeName = field(fields, 3);
				Object scope = scopeName.isEmpty() ? null : new Sym(registerPropertySymbol(scopeName));
				List<Object> mapping = new ArrayList<>();
				mapping.add(new Sym(type));
				mapping.add(mappedTo);
				mapping.add(scope);
				for (int cp = range[0]; cp <= range[1]; cp++) {
					ensurePresent(cp);
					this.idnaMapping.put(cp, mapping);
				}
			}
		}

		private void readSpecialCasing() {
			for (String[] fields : lines("SpecialCasing.txt")) {
				int cp = parseHex(fields[0]);
				List<Object> conditions = new ArrayList<>();
				for (int i = 4; i < fields.length; i++) {
					if (!fields[i].isEmpty()) {
						conditions.add(fields[i]);
					}
				}
				List<Object> rule = new ArrayList<>();
				rule.add(conditions.isEmpty() ? null : conditions);
				rule.add(hexList(field(fields, 1)));
				rule.add(hexList(field(fields, 3)));
				rule.add(hexList(field(fields, 2)));
				// pushnew's default test is eql, and every rule here is freshly consed,
				// so upstream always pushes -- matching that keeps the order identical.
				this.specialCaseMappings.computeIfAbsent(cp, key -> new ArrayList<>()).add(0, rule);
			}
		}

		private void readCaseFoldingMapping() {
			for (String[] fields : lines("CaseFolding.txt")) {
				int[] range = codePointRange(fields[0]);
				Sym status = new Sym(registerPropertySymbol(field(fields, 1)));
				List<Object> mappedTo = hexList(field(fields, 2));
				List<Object> mapping = new ArrayList<>();
				mapping.add(status);
				mapping.add(mappedTo);
				for (int cp = range[0]; cp <= range[1]; cp++) {
					ensurePresent(cp);
					this.caseFolding.computeIfAbsent(cp, key -> new ArrayList<>()).add(0, mapping);
				}
			}
		}

		/** {@code default-bidi-class} for every assigned character that named none. */
		private void setDefaultBidiClasses() {
			int noncharacter = propertySymbol("NoncharacterCodePoint");
			int al = propertySymbol("AL");
			int r = propertySymbol("R");
			int l = propertySymbol("L");
			for (int cp = 0; cp < LIMIT; cp++) {
				if (!this.present[cp] || this.bidiClass[cp] >= 0) {
					continue;
				}
				int value;
				List<Object> props = this.binaryProps.get(cp);
				boolean isNoncharacter = props != null && props.contains(new Sym(noncharacter));
				if ((cp >= 0x0600 && cp <= 0x07BF || cp >= 0xFB50 && cp <= 0xFDFF || cp >= 0xFE70 && cp <= 0xFEFF)
						&& !isNoncharacter) {
					value = al;
				}
				else if (cp >= 0x0590 && cp <= 0x05FF || cp >= 0x07C0 && cp <= 0x08FF || cp >= 0xFB1D && cp <= 0xFB4F
						|| cp >= 0x10800 && cp <= 0x10FFF) {
					value = r;
				}
				else {
					value = l;
				}
				pushNew(this.bidiClasses, new Sym(value));
				this.bidiClass[cp] = (short) value;
			}
		}

		private void addHangulDecomposition() {
			for (int cp = FIRST_HANGUL; cp <= LAST_HANGUL; cp++) {
				if (!this.present[cp]) {
					continue;
				}
				int sIndex = cp - S_BASE;
				int lValue = L_BASE + sIndex / N_COUNT;
				int vValue = V_BASE + sIndex % N_COUNT / T_COUNT;
				int tValue = T_BASE + sIndex % T_COUNT;
				int lvValue = S_BASE + T_COUNT * (sIndex / T_COUNT);
				this.decomposition.put(cp, tValue != T_BASE ? List.of(lvValue, tValue) : List.of(lValue, vValue));
			}
		}

		private void buildCompositionMappings() {
			boolean[] excluded = new boolean[LIMIT];
			for (String[] fields : lines("CompositionExclusions.txt")) {
				int[] range = codePointRange(fields[0]);
				for (int cp = range[0]; cp <= range[1]; cp++) {
					excluded[cp] = true;
				}
			}
			for (int cp = 0; cp < LIMIT; cp++) {
				List<Object> mapping = this.present[cp] ? this.decomposition.get(cp) : null;
				if (mapping == null || mapping.isEmpty() || mapping.get(0) instanceof Sym || mapping.size() != 2
						|| excluded[cp] || nonStarter(cp, mapping)) {
					continue;
				}
				int first = (Integer) mapping.get(0);
				int second = (Integer) mapping.get(1);
				this.compositionMappings.computeIfAbsent(first, key -> new ArrayList<>()).add(0, new Pair(second, cp));
			}
		}

		private boolean nonStarter(int cp, List<Object> mapping) {
			if (this.combiningClass[cp] != 0) {
				return true;
			}
			if (!(mapping.get(0) instanceof Integer first)) {
				return false;
			}
			return !this.present[first] || this.combiningClass[first] != 0;
		}

		private static void pushNew(List<Object> list, Object value) {
			if (!list.contains(value)) {
				list.add(0, value);
			}
		}

		// ------------------------------------------------------------------
		// dump-lists
		// ------------------------------------------------------------------

		String dumpLists() {
			StringBuilder out = new StringBuilder();
			header(out, "lists.lisp");
			// lists.lisp loads before the two components that scan the runs, so the
			// decoders ride along with it rather than in a component of their own.
			out.append(HELPERS);
			dumpList(out, "*general-categories*", this.generalCategories);
			dumpList(out, "*compatibility-formatting-tags*", this.compatibilityFormattingTags);
			dumpList(out, "*scripts*", this.scripts);
			dumpList(out, "*code-blocks*", this.codeBlocks);
			dumpList(out, "*binary-properties*", this.binaryProperties);
			dumpList(out, "*bidi-classes*", this.bidiClasses);
			return out.toString();
		}

		private void dumpList(StringBuilder out, String name, List<Object> values) {
			out.append("(setq cl-unicode::").append(name).append(" '");
			print(out, values);
			out.append(")\n");
		}

		// ------------------------------------------------------------------
		// dump-hash-tables
		// ------------------------------------------------------------------

		String dumpHashTables() {
			StringBuilder out = new StringBuilder();
			header(out, "hash-tables.lisp");
			List<Map.Entry<Object, Object>> canonical = new ArrayList<>();
			for (int id = 0; id < this.symbolKeys.size(); id++) {
				String name = this.canonicalNames.get(id);
				if (name != null) {
					canonical.add(Map.entry(new Sym(id), name));
				}
			}
			dumpTable(out, "*canonical-names*", canonical);
			dumpTable(out, "*names-to-code-points*", mapEntries(this.names, true));
			dumpTable(out, "*code-points-to-names*", mapEntries(this.names, false));
			dumpTable(out, "*unicode1-names-to-code-points*", mapEntries(this.unicode1Names, true));
			dumpTable(out, "*code-points-to-unicode1-names*", mapEntries(this.unicode1Names, false));
			dumpTable(out, "*case-mappings*", caseMappingEntries());
			dumpTable(out, "*special-case-mappings*", entries(this.specialCaseMappings));
			dumpTable(out, "*jamo-short-names*", entries(this.jamoShortNames));
			dumpTable(out, "*property-aliases*", aliasEntries());
			dumpTable(out, "*composition-mappings*", entries(this.compositionMappings));
			out.append("(cl-unicode::add-hangul-names)\n");
			return out.toString();
		}

		private List<Map.Entry<Object, Object>> mapEntries(Map<Integer, String> source, boolean nameToCodePoint) {
			List<Integer> codePoints = new ArrayList<>(source.keySet());
			codePoints.sort(null);
			List<Map.Entry<Object, Object>> out = new ArrayList<>(codePoints.size());
			for (Integer cp : codePoints) {
				String name = Objects.requireNonNull(source.get(cp));
				out.add(nameToCodePoint ? Map.entry(canonicalize(name), cp) : Map.entry(cp, name));
			}
			return out;
		}

		private List<Map.Entry<Object, Object>> caseMappingEntries() {
			List<Integer> codePoints = new ArrayList<>(this.caseMapping.keySet());
			codePoints.sort(null);
			List<Map.Entry<Object, Object>> out = new ArrayList<>(codePoints.size());
			for (Integer cp : codePoints) {
				int[] mappings = Objects.requireNonNull(this.caseMapping.get(cp));
				List<Object> value = new ArrayList<>(3);
				for (int mapping : mappings) {
					value.add(mapping < 0 ? null : mapping);
				}
				out.add(Map.entry(cp, value));
			}
			return out;
		}

		private List<Map.Entry<Object, Object>> aliasEntries() {
			List<Map.Entry<Object, Object>> out = new ArrayList<>(this.propertyAliases.size());
			for (Map.Entry<String, Integer> entry : this.propertyAliases.entrySet()) {
				out.add(Map.entry(entry.getKey(), new Sym(entry.getValue())));
			}
			return out;
		}

		private static <V> List<Map.Entry<Object, Object>> entries(Map<Integer, V> source) {
			List<Map.Entry<Object, Object>> out = new ArrayList<>(source.size());
			for (Map.Entry<Integer, V> entry : source.entrySet()) {
				out.add(Map.entry(entry.getKey(), entry.getValue()));
			}
			return out;
		}

		/**
		 * Emits one {@code dump-hash-table}, filled in the shape upstream's own dump uses
		 * -- except that the entries arrive from {@code %read} rather than as a quoted
		 * literal, which is what keeps 68,000 names and 45,000 numbers off the constant
		 * pool.
		 */
		private void dumpTable(StringBuilder out, String name, List<Map.Entry<Object, Object>> entries) {
			out.append("(clrhash cl-unicode::").append(name).append(")\n");
			Chunks chunks = new Chunks();
			for (Map.Entry<Object, Object> entry : entries) {
				// dump-hash-table drops a nil value: the table answers nil for a missing
				// key anyway, so an entry for one is pure size.
				if (entry.getValue() == null) {
					continue;
				}
				StringBuilder pair = new StringBuilder("(");
				print(pair, entry.getKey());
				pair.append(" . ");
				print(pair, entry.getValue());
				chunks.add(pair.append(')').toString());
			}
			out.append("(loop for (key . value) in (cl-unicode::%read");
			chunks.appendTo(out);
			out.append(") do (setf (gethash key cl-unicode::").append(name).append(") value))\n");
		}

		// ------------------------------------------------------------------
		// dump-methods
		// ------------------------------------------------------------------

		String dumpMethods() {
			StringBuilder out = new StringBuilder();
			header(out, "methods.lisp");
			dumpMethod(out, "script", cp -> symbolOrNull(this.script, cp), true);
			dumpMethod(out, "code-block", cp -> symbolOrNull(this.codeBlock, cp), true);
			dumpMethod(out, "word-break", cp -> symbolOrNull(this.wordBreak, cp), true);
			dumpMethod(out, "age", cp -> this.age[cp] < 0 ? null : this.ages.get(this.age[cp]), false);
			dumpMethod(out, "general-category", cp -> symbolOrNull(this.generalCategory, cp), true);
			dumpMethod(out, "bidi-class", cp -> symbolOrNull(this.bidiClass, cp), true);
			dumpMethod(out, "numeric-type", cp -> symbolOrNull(this.numericType, cp), true);
			dumpMethod(out, "numeric-value", cp -> this.numericValue.get(cp), false);
			dumpMethod(out, "combining-class", cp -> (int) this.combiningClass[cp], false);
			dumpMethod(out, "bidi-mirroring-glyph%", cp -> this.mirroringGlyph[cp] < 0 ? null : this.mirroringGlyph[cp],
					false);
			dumpMethod(out, "binary-props", cp -> this.binaryProps.get(cp), false);
			dumpMethod(out, "idna-mapping", cp -> this.idnaMapping.get(cp), false);
			dumpMethod(out, "decomposition-mapping", cp -> this.decomposition.get(cp), false);
			dumpMethod(out, "case-folding-mapping", cp -> this.caseFolding.get(cp), false);
			return out.toString();
		}

		private @Nullable Object symbolOrNull(short[] slot, int cp) {
			return slot[cp] < 0 ? null : new Sym(slot[cp]);
		}

		/** The reader of one {@code char-info} slot, {@code null} when there is none. */
		private interface Slot {

			@Nullable Object read(int codePoint);

		}

		/**
		 * Emits one {@code char-info} slot as a lazily built range table plus the method
		 * that looks it up.
		 * <p>
		 * {@code dump-method} writes the whole balanced tree as one literal; here the
		 * ranges travel as the printed text of two parallel lists -- the ascending
		 * inclusive starts and their values -- and the table is built on the first lookup
		 * of that property, never at load. A program that asks only for a general
		 * category therefore never reads the 17,272-range decomposition table, and one
		 * that asks for nothing reads none of the fourteen.
		 */
		private void dumpMethod(StringBuilder out, String name, Slot slot, boolean propertyPair) {
			List<Object[]> ranges = buildRangeList(slot);
			Chunks starts = new Chunks();
			Chunks values = new Chunks();
			for (Object[] range : ranges) {
				starts.add(range[0].toString());
				StringBuilder value = new StringBuilder();
				print(value, range[2]);
				values.add(value.toString());
			}
			out.append("(defvar cl-unicode::*%").append(name).append("-table* nil)\n");
			out.append("(defun cl-unicode::%").append(name).append("-table ()\n");
			out.append("  (or cl-unicode::*%").append(name).append("-table*\n");
			out.append("      (setq cl-unicode::*%").append(name).append("-table*\n");
			out.append("            (cl-unicode::%table");
			starts.appendTo(out);
			values.appendTo(out);
			out.append("))))\n");
			out.append("(defmethod cl-unicode::").append(name).append(" ((code-point integer))\n");
			if (propertyPair) {
				out.append("  (let ((symbol (cl-unicode::%lookup code-point (cl-unicode::%")
					.append(name)
					.append("-table))))\n    (values (cl-unicode::property-name symbol) symbol)))\n");
			}
			else {
				out.append("  (cl-unicode::%lookup code-point (cl-unicode::%").append(name).append("-table)))\n");
			}
		}

		/**
		 * {@code build-range-list}: the coarsest ascending partition of the code space on
		 * which the slot's value is constant. Upstream's loop returns at
		 * {@code +code-point-limit+ - 1}, so the final range ends one below it.
		 */
		private List<Object[]> buildRangeList(Slot slot) {
			List<Object[]> ranges = new ArrayList<>();
			Object last = this.present[0] ? slot.read(0) : null;
			int lastCodePoint = 0;
			for (int cp = 1; cp < LIMIT - 1; cp++) {
				Object value = this.present[cp] ? slot.read(cp) : null;
				if (!Objects.equals(value, last)) {
					ranges.add(new Object[] { lastCodePoint, cp - 1, last });
					last = value;
					lastCodePoint = cp;
				}
			}
			ranges.add(new Object[] { lastCodePoint, LIMIT - 2, last });
			return ranges;
		}

		// ------------------------------------------------------------------
		// printing
		// ------------------------------------------------------------------

		private void print(StringBuilder out, @Nullable Object value) {
			switch (value) {
				case null -> out.append("nil");
				case Sym symbol -> out.append("cl-unicode-names::").append(this.symbolKeys.get(symbol.id()));
				case Integer number -> out.append(number.intValue());
				case Rat rational -> {
					out.append(rational.numerator());
					if (!BigInteger.ONE.equals(rational.denominator())) {
						out.append('/').append(rational.denominator());
					}
				}
				case String text -> printString(out, text);
				case Pair pair -> {
					out.append('(');
					print(out, pair.car());
					out.append(" . ");
					print(out, pair.cdr());
					out.append(')');
				}
				case List<?> list -> {
					if (list.isEmpty()) {
						out.append("nil");
						return;
					}
					out.append('(');
					for (int i = 0; i < list.size(); i++) {
						if (i > 0) {
							out.append(' ');
						}
						print(out, list.get(i));
					}
					out.append(')');
				}
				default -> throw new IllegalStateException("cannot print " + value.getClass());
			}
		}

		private static void printString(StringBuilder out, String text) {
			out.append('"');
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				if (c == '"' || c == '\\') {
					out.append('\\');
				}
				out.append(c);
			}
			out.append('"');
		}

		private static void header(StringBuilder out, String file) {
			out.append(";;; ").append(file).append(" -- generated from the bundled UCD data by ClUnicodeTables,\n");
			out.append(";;; standing in for a cl-unicode/build run. Canonical shape (no in-package).\n");
		}

	}

	/**
	 * {@code canonicalize-name}: strips whitespace, hyphens and underscores so a name can
	 * be looked up unambiguously.
	 * @param name the name as it appears in the data
	 * @return the canonicalized name
	 */
	static String canonicalize(String name) {
		Matcher matcher = CANONICALIZE.matcher(name);
		StringBuilder out = new StringBuilder(name.length());
		int last = 0;
		while (matcher.find()) {
			out.append(name, last, matcher.start());
			if (matcher.group(1) != null) {
				out.append(' ').append(matcher.group(1));
			}
			last = matcher.end();
		}
		return out.append(name, last, name.length()).toString();
	}

	/**
	 * The printed text of one emitted list, cut into string literals between elements.
	 * Everything bulky cl-unicode's tables hold travels this way rather than as a
	 * literal; the reason and the two ceilings the cut answers are on {@link #RUN_CHUNK}
	 * and {@link #RUN_ELEMENTS}.
	 */
	private static final class Chunks {

		private final List<String> chunks = new ArrayList<>();

		private final StringBuilder chunk = new StringBuilder();

		private int count;

		void add(String printed) {
			if (!this.chunk.isEmpty()
					&& (this.chunk.length() + 1 + printed.length() > RUN_CHUNK || this.count >= RUN_ELEMENTS)) {
				this.chunks.add(this.chunk.toString());
				this.chunk.setLength(0);
				this.count = 0;
			}
			if (!this.chunk.isEmpty()) {
				this.chunk.append(' ');
			}
			this.chunk.append(printed);
			this.count++;
		}

		/**
		 * Appends the chunks as a quoted list of string literals, each holding one
		 * printed LIST, on its own line.
		 */
		void appendTo(StringBuilder out) {
			List<String> all = new ArrayList<>(this.chunks);
			// Always at least one chunk, so an empty table reads back as an empty list
			// rather than as a missing argument.
			all.add(this.chunk.toString());
			out.append("\n  '(");
			for (int i = 0; i < all.size(); i++) {
				out.append(i == 0 ? "" : "\n    ").append("\"(");
				for (int j = 0; j < all.get(i).length(); j++) {
					char c = all.get(i).charAt(j);
					if (c == '"' || c == '\\') {
						out.append('\\');
					}
					out.append(c);
				}
				out.append(")\"");
			}
			out.append(')');
		}

	}

}
