package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.format.LispFormatter;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generates the tables of Unicode normalization as the front ends share it
 * ({@code unicode-normalization-tables.lisp}, read by {@code unicode-normalization.lisp})
 * from the running JDK's public {@code java.text.Normalizer}, pins the shipped file to
 * them, and asks the run time what the JDK answers. A JDK whose Unicode moved fails here;
 * the message names the regenerated file to copy over the shipped one.
 *
 * <p>
 * Everything comes from running the JDK, nothing from reading it
 * ({@code .kb/unicode-normalization.md}, "The tables"): a code point's canonical mapping
 * is its NFD, its compatibility mapping its NFKD where that differs; a composite is a
 * code point that NFC keeps although NFD takes it apart, and its pair the one code point
 * of its NFD and the composition of the others that NFC composes to it; a mark is a code
 * point NFD moves across one of two marks of different classes, and the order of the
 * classes is the order NFD puts two marks in. The numbers of the classes are not asked
 * for: no form reads more of them than their order.
 */
class UnicodeNormalizationTablesTest {

	private static final int SYLLABLES = 0xAC00;

	private static final int LAST_SYLLABLE = 0xD7A3;

	/** Two marks of different classes: an overlay and the iota below. */
	private static final int[] MARKS = { 0x334, 0x345 };

	private static String text(int... codePoints) {
		return new String(codePoints, 0, codePoints.length);
	}

	private static int[] codePoints(String text) {
		return text.codePoints().toArray();
	}

	private static String normalize(String text, Normalizer.Form form) {
		return Normalizer.normalize(text, form);
	}

	/** Whether NFD writes the two code points the other way round. */
	private static boolean swaps(int first, int second) {
		return normalize(text(first, second), Normalizer.Form.NFD).equals(text(second, first));
	}

	/**
	 * What the tables hold.
	 *
	 * @param canonical a code point's canonical mapping: a composite's pair, any other's
	 * whole decomposition
	 * @param composites the code points the composition answers
	 * @param loud the composites whose decomposition starts with a code point of the
	 * classes
	 * @param compatibility a code point's compatibility decomposition, where it is not
	 * the canonical one
	 * @param classes a mark's class as its place among the classes, and 0 for a starter
	 * that composes with a starter before it
	 */
	record Tables(Map<Integer, int[]> canonical, Set<Integer> composites, Set<Integer> loud,
			Map<Integer, int[]> compatibility, Map<Integer, Integer> classes) {

		static final Tables OF_THE_JDK = read();

		/** The two code points a composite is composed of. */
		int[] pair(int composite) {
			return Objects.requireNonNull(this.canonical.get(composite));
		}

		/** The class of a code point of the classes. */
		int classOf(int codePoint) {
			return Objects.requireNonNull(this.classes.get(codePoint));
		}

		// The pair of a composite: one code point of its decomposition, the last that
		// does, and the composition of the others.
		private static int[] pair(int composite, int[] decomposition) {
			for (int at = decomposition.length - 1; at >= 1; at--) {
				int[] others = new int[decomposition.length - 1];
				for (int i = 0, k = 0; i < decomposition.length; i++) {
					if (i != at) {
						others[k++] = decomposition[i];
					}
				}
				int[] first = codePoints(normalize(text(others), Normalizer.Form.NFC));
				if (first.length == 1
						&& normalize(text(first[0], decomposition[at]), Normalizer.Form.NFC).equals(text(composite))) {
					return new int[] { first[0], decomposition[at] };
				}
			}
			throw new IllegalStateException("no pair composes U+" + Integer.toHexString(composite));
		}

		private static Tables read() {
			Tables tables = new Tables(new TreeMap<>(), new TreeSet<>(), new TreeSet<>(), new TreeMap<>(),
					new TreeMap<>());
			assertThat(swaps(MARKS[1], MARKS[0])).as("two marks of different classes").isTrue();
			List<Integer> marks = new ArrayList<>();
			for (int c = 0; c <= Character.MAX_CODE_POINT; c++) {
				String text = text(c);
				String nfd = normalize(text, Normalizer.Form.NFD);
				String nfkd = normalize(text, Normalizer.Form.NFKD);
				if (c >= SYLLABLES && c <= LAST_SYLLABLE) {
					// a Hangul syllable is arithmetic in the run time, which this holds
					// the JDK to
					int at = c - SYLLABLES;
					int[] jamo = at % 28 == 0 ? new int[] { 0x1100 + at / 588, 0x1161 + at % 588 / 28 }
							: new int[] { 0x1100 + at / 588, 0x1161 + at % 588 / 28, 0x11A7 + at % 28 };
					assertThat(List.of(nfd, nfkd, normalize(nfd, Normalizer.Form.NFC)))
						.as("the syllable U+" + Integer.toHexString(c))
						.containsExactly(text(jamo), text(jamo), text);
					continue;
				}
				if (!nfd.equals(text)) {
					int[] decomposition = codePoints(nfd);
					if (normalize(text, Normalizer.Form.NFC).equals(text)) {
						tables.composites.add(c);
						tables.canonical.put(c, pair(c, decomposition));
					}
					else {
						tables.canonical.put(c, decomposition);
					}
				}
				else if (swaps(c, MARKS[0]) || swaps(MARKS[0], c) || swaps(c, MARKS[1]) || swaps(MARKS[1], c)) {
					marks.add(c);
				}
				if (!nfkd.equals(nfd)) {
					tables.compatibility.put(c, codePoints(nfkd));
				}
			}
			// the classes in their order: a mark is searched among the classes so far
			List<List<Integer>> order = new ArrayList<>();
			for (int mark : marks) {
				int low = 0;
				int high = order.size();
				boolean placed = false;
				while (low < high && !placed) {
					int middle = (low + high) >>> 1;
					int other = order.get(middle).get(0);
					if (swaps(other, mark)) {
						high = middle;
					}
					else if (swaps(mark, other)) {
						low = middle + 1;
					}
					else {
						order.get(middle).add(mark);
						placed = true;
					}
				}
				if (!placed) {
					order.add(low, new ArrayList<>(List.of(mark)));
				}
			}
			for (int k = 0; k < order.size(); k++) {
				for (int mark : order.get(k)) {
					tables.classes.put(mark, k + 1);
				}
			}
			// a starter that composes with a starter before it: the second of a pair
			// that is no mark, and the vowels and trailing consonants of Hangul
			for (int composite : tables.composites) {
				tables.classes.putIfAbsent(tables.pair(composite)[1], 0);
			}
			for (int c = 0x1161; c <= 0x1175; c++) {
				tables.classes.put(c, 0);
			}
			for (int c = 0x11A8; c <= 0x11C2; c++) {
				tables.classes.put(c, 0);
			}
			// what the run time relies on: a code point of the classes has no mapping
			// (an entry is one or the other), and the classes fit beside a code point
			assertThat(tables.classes.keySet()).doesNotContainAnyElementsOf(tables.canonical.keySet())
				.doesNotContainAnyElementsOf(tables.compatibility.keySet());
			assertThat(order.size()).isLessThan(256);
			for (int composite : tables.composites) {
				if (tables.classes.containsKey(codePoints(normalize(text(composite), Normalizer.Form.NFD))[0])) {
					tables.loud.add(composite);
				}
			}
			return tables;
		}

	}

	// A number in base-32 digits, the most significant first, each but the last offset
	// by 32; the digit d is the character 48 + d, skipping the backslash (92).
	private static void encode(StringBuilder text, int n) {
		int shift = 0;
		while (n >>> (shift + 5) != 0) {
			shift += 5;
		}
		for (; shift > 0; shift -= 5) {
			text.append(digit(32 + ((n >>> shift) & 31)));
		}
		text.append(digit(n & 31));
	}

	private static char digit(int d) {
		return (char) (d < 44 ? 48 + d : 49 + d);
	}

	// A distance up as twice itself, a distance down as one less than twice it.
	private static int signed(int n) {
		return n >= 0 ? 2 * n : -2 * n - 1;
	}

	/**
	 * The classes as runs: the distance from the end of the run before, the length, the
	 * class.
	 */
	static String classes(Tables tables) {
		StringBuilder out = new StringBuilder();
		List<Integer> codes = new ArrayList<>(tables.classes.keySet());
		int end = 0;
		for (int i = 0; i < codes.size();) {
			int last = i;
			while (last + 1 < codes.size() && codes.get(last + 1) == codes.get(last) + 1
					&& tables.classOf(codes.get(last + 1)) == tables.classOf(codes.get(i))) {
				last++;
			}
			encode(out, codes.get(i) - end);
			encode(out, last - i + 1);
			encode(out, tables.classOf(codes.get(i)));
			end = codes.get(last) + 1;
			i = last + 1;
		}
		return out.toString();
	}

	/**
	 * The mappings by code point, as {@code %unorm-read} of
	 * {@code unicode-normalization.lisp} states them: a head of eight times the distance
	 * from the code point before plus a kind, then the mapping.
	 */
	static String mappings(Tables tables) {
		StringBuilder out = new StringBuilder();
		TreeSet<Integer> keys = new TreeSet<>(tables.canonical.keySet());
		keys.addAll(tables.compatibility.keySet());
		List<Integer> codes = new ArrayList<>(keys);
		int code = 0;
		int canonical = 0;
		int compatibility = 0;
		// the code point of the compatibility mapping before, when it was to one
		int single = -2;
		for (int i = 0; i < codes.size(); i++) {
			int c = codes.get(i);
			int[] to = tables.canonical.get(c);
			if (to != null) {
				int kind = tables.loud.contains(c) ? 7 : tables.composites.contains(c) ? 1 : to.length == 1 ? 0 : 2;
				encode(out, (c - code) * 8 + kind);
				if (kind == 2) {
					encode(out, to.length);
				}
				encode(out, signed(to[0] - canonical));
				for (int k = 1; k < to.length; k++) {
					encode(out, to[k]);
				}
				code = c;
				canonical = to[0];
			}
			to = tables.compatibility.get(c);
			if (to == null) {
				continue;
			}
			if (to.length == 1 && to[0] == compatibility + 1 && single == c - 1) {
				int length = 1;
				while (i + length < codes.size() && codes.get(i + length) == c + length
						&& !tables.canonical.containsKey(c + length) && java.util.Arrays
							.equals(tables.compatibility.get(c + length), new int[] { to[0] + length })) {
					length++;
				}
				encode(out, (c - code) * 8 + 6);
				encode(out, length);
				i += length - 1;
				code = c + length - 1;
				compatibility = to[0] + length - 1;
				single = code;
				continue;
			}
			int kind = to.length == 1 ? 3 : to.length == 2 ? 4 : 5;
			encode(out, (c - code) * 8 + kind);
			if (kind == 5) {
				encode(out, to.length);
			}
			encode(out, signed(to[0] - compatibility));
			for (int k = 1; k < to.length; k++) {
				encode(out, to[k]);
			}
			code = c;
			compatibility = to[0];
			single = to.length == 1 ? c : -2;
		}
		return out.toString();
	}

	/** The generated file. */
	static String text() {
		Tables tables = Tables.OF_THE_JDK;
		return ";;;; The tables of Unicode normalization (unicode-normalization.lisp), generated from\n"
				+ ";;;; a running JDK's public java.text.Normalizer by UnicodeNormalizationTablesTest,\n"
				+ ";;;; which fails when they are not the running JDK's: regenerate them there, never\n"
				+ ";;;; by hand.\n"
				+ ";;;; Third-party: Unicode Character Database data (Unicode-3.0), Copyright (c) 1991-2026\n"
				+ ";;;; Unicode, Inc. Notice: THIRD-PARTY-NOTICES.txt, \"Unicode\".\n\n"
				+ "(defun rontolisp::%unorm-classes ()\n"
				+ "  \"The marks' combining classes in their order and the starters that compose\n"
				+ "   with a starter before them, as runs (%unorm-read).\"\n  \"" + classes(tables) + "\")\n\n"
				+ "(defun rontolisp::%unorm-mappings ()\n"
				+ "  \"The canonical and the compatibility mappings by code point (%unorm-read).\"\n  \""
				+ mappings(tables) + "\")\n";
	}

	private static String shipped() throws IOException {
		try (InputStream in = LispEvaluator.class.getResourceAsStream("unicode-normalization-tables.lisp")) {
			assertThat(in).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void theShippedTablesAreTheJdks() throws IOException {
		// in the layout `rontolisp format` gives it, which the shipped file keeps
		String generated = LispFormatter.format(text());
		String shipped = shipped();
		if (!generated.equals(shipped)) {
			Path out = Path.of("target", "unicode-normalization-tables.lisp");
			Files.createDirectories(out.getParent());
			Files.writeString(out, generated);
			assertThat(shipped)
				.as("copy " + out.toAbsolutePath()
						+ " over src/main/resources/am/ik/rontolisp/eval/unicode-normalization-tables.lisp")
				.isEqualTo(generated);
		}
	}

	private static String hex(String text) {
		return text.codePoints().mapToObj(Integer::toHexString).collect(Collectors.joining(" "));
	}

	@Test
	void theRuntimeNormalizesAsTheJdk() {
		LispEvaluator evaluator = new LispEvaluator(
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
		// a text below the first code point its form changes reads no table
		assertThat(evaluator.eval(LispReader.readAllFromString("""
				(let ((below (lambda (limit)
				               (coerce (list (code-char 97) (code-char (- limit 1))) 'string))))
				  (rontolisp::%unorm-normalize (funcall below 192) nil nil)
				  (rontolisp::%unorm-normalize (funcall below 768) nil t)
				  (rontolisp::%unorm-normalize (funcall below 160) t nil)
				  (rontolisp::%unorm-normalize (funcall below 160) t t)
				  rontolisp::%unorm-tables-cache)""", Features.INTERPRETER).get(0)).print()).isEqualTo("NIL");
		// every code point of the tables and the one on either side of it alone, every
		// pair ahead of its composite, and seeded texts of one to six code points, two
		// in three from the ones that meet most: marks of several classes, jamo and
		// syllables, composites, a kana and its voiced marks, a surrogate
		Tables tables = Tables.OF_THE_JDK;
		TreeSet<Integer> points = new TreeSet<>(
				List.of(0, 0x41, 0x9F, 0xA0, 0xBF, 0xC0, 0x2FF, 0x300, 0x4E00, 0x10FF, 0x1100, 0x1112, 0x1113, 0x1160,
						0x1176, 0x11A7, 0x11C3, 0xABFF, SYLLABLES, SYLLABLES + 1, SYLLABLES + 28, LAST_SYLLABLE,
						LAST_SYLLABLE + 1, 0xD800, 0xDFFF, 0xE000, 0xFFFF, 0x10000, Character.MAX_CODE_POINT));
		for (Set<Integer> codes : List.of(tables.canonical.keySet(), tables.compatibility.keySet(),
				tables.classes.keySet())) {
			for (int c : codes) {
				points.addAll(List.of(c - 1, c, c + 1));
			}
		}
		List<Integer> alphabet = new ArrayList<>(points);
		List<String> texts = new ArrayList<>();
		alphabet.forEach(c -> texts.add(text(c)));
		tables.composites.forEach(c -> texts.add(text(tables.pair(c))));
		int[] common = { 'a', 'e', 0xE9, 0x301, 0x300, 0x323, 0x327, 0x328, 0x315, 0x5AE, 0x334, 0x345, 0x3042, 0x304B,
				0x3099, 0x309A, SYLLABLES, SYLLABLES + 1, 0x1100, 0x1161, 0x11A8, 0x1112, 0x1175, 0x11C2, 0xFF76,
				0xFF9E, 0xFB01, 0x1E9B, 0x212B, 0x958, 0x93C, 0x9C7, 0x9BE, 0x1D15E, 0x2F800, 0xD800 };
		Random random = new Random(29);
		for (int i = 0; i < 30000; i++) {
			int[] codes = new int[1 + random.nextInt(6)];
			for (int k = 0; k < codes.length; k++) {
				codes[k] = random.nextInt(3) == 0 ? alphabet.get(random.nextInt(alphabet.size()))
						: common[random.nextInt(common.length)];
			}
			texts.add(text(codes));
		}
		List<String> expected = new ArrayList<>();
		List<String> actual = new ArrayList<>();
		for (int from = 0; from < texts.size(); from += 2000) {
			List<String> chunk = texts.subList(from, Math.min(from + 2000, texts.size()));
			String program = "(mapcar (lambda (codes) (let ((text (coerce (mapcar (function code-char) codes) 'string)))"
					+ " (mapcar (lambda (form) (map 'list (function char-code)"
					+ " (rontolisp::%unorm-normalize text (car form) (cdr form))))"
					+ " '((nil . nil) (nil . t) (t . nil) (t . t))))) '("
					+ chunk.stream()
						.map(text -> text.codePoints()
							.mapToObj(String::valueOf)
							.collect(Collectors.joining(" ", "(", ")")))
						.collect(Collectors.joining(" "))
					+ "))";
			LispVal answers = evaluator.eval(LispReader.readAllFromString(program, Features.INTERPRETER).get(0));
			int at = 0;
			for (LispVal rest = answers; rest instanceof LispCons cons; rest = cons.cdr()) {
				StringBuilder row = new StringBuilder(hex(chunk.get(at++))).append(" ->");
				for (LispVal forms = cons.car(); forms instanceof LispCons form; forms = form.cdr()) {
					row.append(" |");
					for (LispVal codes = form.car(); codes instanceof LispCons code; codes = code.cdr()) {
						row.append(' ').append(Long.toHexString(((LispInteger) code.car()).value()));
					}
				}
				actual.add(row.toString());
			}
			for (String text : chunk) {
				StringBuilder row = new StringBuilder(hex(text)).append(" ->");
				for (Normalizer.Form form : Normalizer.Form.values()) {
					String normalized = normalize(text, form);
					row.append(normalized.isEmpty() ? " |" : " | ").append(hex(normalized));
				}
				expected.add(row.toString());
			}
		}
		assertThat(actual).isEqualTo(expected);
	}

}
