package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.format.LispFormatter;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generates the Unicode tables of the Clojure runtime's regex engine
 * ({@code clojure-unicode.lisp}, read by {@code clojure.lisp}'s "Unicode properties")
 * from the running JDK, the oracle's own {@code Character}, and pins the shipped file to
 * them: every code point's general category and the {@code Character} predicates
 * {@code java.util.regex}'s {@code CharPredicates} asks, its {@code UnicodeScript} and
 * {@code UnicodeBlock}, and the names {@code UnicodeScript.forName} and
 * {@code UnicodeBlock.forName} take. A JDK whose Unicode moved fails here; the message
 * names the regenerated file to copy over the shipped one.
 *
 * <p>
 * The names come from public API alone: a script's ISO 15924 alias is found by asking
 * {@code forName} every four-letter name, and a block's names are the variants of its
 * constant's name with each underscore a space, a hyphen or kept (and without the
 * spaces), plus the three canonical names that are no such variant. A later JDK adding
 * another such name would go unseen here.
 */
class ClojureUnicodeTablesTest {

	/**
	 * The {@code Character} predicates of a combo, from bit 5 up in this order
	 * ({@code clojure.lisp}, "Unicode properties").
	 */
	private static final List<IntPredicate> PREDICATES = List.of(Character::isAlphabetic, Character::isLowerCase,
			Character::isUpperCase, Character::isTitleCase, Character::isIdeographic, Character::isDigit,
			Character::isLetter, Character::isLetterOrDigit, Character::isDefined, Character::isMirrored,
			Character::isJavaIdentifierStart, Character::isJavaIdentifierPart, Character::isUnicodeIdentifierStart,
			Character::isUnicodeIdentifierPart, Character::isIdentifierIgnorable, Character::isSpaceChar,
			Character::isWhitespace, Character::isISOControl, Character::isEmoji, Character::isEmojiPresentation,
			Character::isEmojiModifier, Character::isEmojiModifierBase, Character::isEmojiComponent,
			Character::isExtendedPictographic);

	/** The block names that are no variant of the block constant's name. */
	private static final List<String> CANONICAL_BLOCK_NAMES = List.of("GREEK AND COPTIC",
			"COMBINING DIACRITICAL MARKS FOR SYMBOLS", "CYRILLIC SUPPLEMENT");

	static int combo(int codePoint) {
		int combo = Character.getType(codePoint);
		for (int bit = 0; bit < PREDICATES.size(); bit++) {
			if (PREDICATES.get(bit).test(codePoint)) {
				combo |= 1 << (5 + bit);
			}
		}
		return combo;
	}

	/**
	 * The blocks: in the order their first code point comes, then the ones no code point
	 * is in (the deprecated {@code SURROGATES_AREA}) by name. A code point's block value
	 * is its block's place here plus one, zero for none.
	 */
	static List<Character.UnicodeBlock> blocks() {
		Set<Character.UnicodeBlock> seen = new java.util.LinkedHashSet<>();
		for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
			Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
			if (block != null) {
				seen.add(block);
			}
		}
		TreeSet<String> rest = new TreeSet<>();
		Map<String, Character.UnicodeBlock> byName = new LinkedHashMap<>();
		for (Field field : Character.UnicodeBlock.class.getFields()) {
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == Character.UnicodeBlock.class) {
				try {
					Character.UnicodeBlock block = (Character.UnicodeBlock) field.get(null);
					if (!seen.contains(block)) {
						rest.add(block.toString());
						byName.put(block.toString(), block);
					}
				}
				catch (IllegalAccessException ex) {
					throw new IllegalStateException(ex);
				}
			}
		}
		List<Character.UnicodeBlock> out = new ArrayList<>(seen);
		for (String name : rest) {
			out.add(byName.get(name));
		}
		return out;
	}

	/** The generated file. */
	static String text() {
		StringBuilder out = new StringBuilder();
		out.append(";;;; The Unicode tables of the Clojure runtime's regex engine (clojure.lisp,\n")
			.append(";;;; \"Unicode properties\"), generated from the JDK by\n")
			.append(";;;; ClojureUnicodeTablesTest, which fails when they are not the running JDK's:\n")
			.append(";;;; regenerate them there, never by hand.\n\n");
		// the properties: runs of combo indexes into the combos
		Map<Integer, Integer> combos = new LinkedHashMap<>();
		Runs props = runs(codePoint -> combos.computeIfAbsent(combo(codePoint), ignored -> combos.size()));
		StringBuilder comboText = new StringBuilder();
		combos.keySet().forEach(combo -> encode(comboText, combo));
		cache(out, "props",
				"The general category and the Character predicates of every code point, as runs\n" + "   of combos.",
				"(rontolisp::%clojure-re-uni-decode \"" + props.text + "\" " + props.count
						+ "\n                  (rontolisp::%clojure-re-uni-numbers \"" + comboText + "\" "
						+ combos.size() + "))");
		Runs scripts = runs(codePoint -> Character.UnicodeScript.of(codePoint).ordinal());
		cache(out, "scripts", "The Character.UnicodeScript ordinal of every code point, as runs.",
				"(rontolisp::%clojure-re-uni-decode \"" + scripts.text + "\" " + scripts.count + " nil)");
		List<Character.UnicodeBlock> blocks = blocks();
		Runs blockRuns = runs(codePoint -> {
			Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
			return block == null ? 0 : blocks.indexOf(block) + 1;
		});
		cache(out, "blocks",
				"The Character.UnicodeBlock of every code point (its place among the block\n"
						+ "   names plus one, 0 for none), as runs.",
				"(rontolisp::%clojure-re-uni-decode \"" + blockRuns.text + "\" " + blockRuns.count + " nil)");
		names(out, "script-names", "The scripts by ordinal: the constant's name, then its ISO 15924 alias.",
				scriptNames());
		names(out, "block-names", "The blocks: the constant's name, then its canonical names (* the constant's\n"
				+ "   name with spaces), each taken without its spaces too.", blockNames(blocks));
		return out.toString();
	}

	private static void cache(StringBuilder out, String name, String doc, String decode) {
		out.append("(defvar rontolisp::%clojure-re-uni-")
			.append(name)
			.append("-cache nil)\n\n(defun rontolisp::%clojure-re-uni-")
			.append(name)
			.append(" ()\n  \"")
			.append(doc)
			.append("\"\n  (or rontolisp::%clojure-re-uni-")
			.append(name)
			.append("-cache\n      (setq rontolisp::%clojure-re-uni-")
			.append(name)
			.append("-cache\n            ")
			.append(decode)
			.append(")))\n\n");
	}

	private static void names(StringBuilder out, String name, String doc, List<List<String>> entries) {
		String table = entries.stream().map(entry -> String.join("=", entry)).collect(Collectors.joining(","));
		out.append("(defun rontolisp::%clojure-re-uni-")
			.append(name)
			.append(" ()\n  \"")
			.append(doc)
			.append("\"\n  \"")
			.append(table)
			.append("\")\n\n");
	}

	/** Every script's constant name and, when it differs, its four-letter alias. */
	static List<List<String>> scriptNames() {
		Character.UnicodeScript[] scripts = Character.UnicodeScript.values();
		String[] aliases = new String[scripts.length];
		char[] name = new char[4];
		for (int i = 0; i < 26 * 26 * 26 * 26; i++) {
			int rest = i;
			for (int k = 3; k >= 0; k--) {
				name[k] = (char) ('A' + rest % 26);
				rest /= 26;
			}
			String candidate = new String(name);
			try {
				Character.UnicodeScript script = Character.UnicodeScript.forName(candidate);
				if (!script.name().equals(candidate)) {
					assertThat(aliases[script.ordinal()]).as("one alias of " + script).isNull();
					aliases[script.ordinal()] = candidate;
				}
			}
			catch (IllegalArgumentException ex) {
				// no script of the name
			}
		}
		List<List<String>> out = new ArrayList<>();
		for (Character.UnicodeScript script : scripts) {
			String alias = aliases[script.ordinal()];
			out.add(alias == null ? List.of(script.name()) : List.of(script.name(), alias));
		}
		return out;
	}

	/**
	 * Every block's constant name and its canonical names: the names {@code forName}
	 * takes are the constant's and each canonical one with and without its spaces.
	 */
	static List<List<String>> blockNames(List<Character.UnicodeBlock> blocks) {
		List<List<String>> out = new ArrayList<>();
		for (Character.UnicodeBlock block : blocks) {
			String id = block.toString();
			Set<String> taken = new TreeSet<>();
			List<String> candidates = new ArrayList<>(variants(id));
			candidates.addAll(CANONICAL_BLOCK_NAMES);
			for (String candidate : candidates) {
				for (String spelling : List.of(candidate, candidate.replace(" ", ""))) {
					try {
						if (Character.UnicodeBlock.forName(spelling) == block) {
							taken.add(spelling);
						}
					}
					catch (IllegalArgumentException ex) {
						// no block of the name
					}
				}
			}
			List<String> canonical = new ArrayList<>();
			for (String name : taken) {
				boolean unspaced = name.indexOf(' ') < 0 && taken.stream()
					.anyMatch(other -> other.indexOf(' ') >= 0 && other.replace(" ", "").equals(name));
				if (!name.equals(id) && !unspaced) {
					canonical.add(name);
				}
			}
			Set<String> rebuilt = new TreeSet<>();
			rebuilt.add(id);
			for (String name : canonical) {
				rebuilt.add(name);
				rebuilt.add(name.replace(" ", ""));
			}
			assertThat(rebuilt).as("the names of " + id).isEqualTo(taken);
			List<String> entry = new ArrayList<>();
			entry.add(id);
			for (String name : canonical) {
				entry.add(name.equals(id.replace('_', ' ')) ? "*" : name);
			}
			out.add(entry);
		}
		return out;
	}

	// The constant's name with each underscore kept, a space or a hyphen.
	private static List<String> variants(String id) {
		List<String> out = new ArrayList<>(List.of(""));
		for (char c : id.toCharArray()) {
			List<String> next = new ArrayList<>();
			for (String prefix : out) {
				if (c == '_') {
					next.add(prefix + "_");
					next.add(prefix + " ");
					next.add(prefix + "-");
				}
				else {
					next.add(prefix + c);
				}
			}
			out = next;
		}
		return out;
	}

	record Runs(String text, int count) {
	}

	private static Runs runs(IntUnaryOperator value) {
		StringBuilder text = new StringBuilder();
		int count = 0;
		int start = 0;
		int previous = -1;
		for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
			int v = value.applyAsInt(codePoint);
			if (v != previous) {
				encode(text, codePoint - start);
				encode(text, v);
				count++;
				start = codePoint;
				previous = v;
			}
		}
		return new Runs(text.toString(), count);
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

	private static String shipped() throws IOException {
		try (InputStream in = LispEvaluator.class.getResourceAsStream("clojure-unicode.lisp")) {
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
			Path out = Path.of("target", "clojure-unicode.lisp");
			Files.createDirectories(out.getParent());
			Files.writeString(out, generated);
			assertThat(shipped)
				.as("copy " + out.toAbsolutePath()
						+ " over src/main/resources/am/ik/rontolisp/eval/clojure-unicode.lisp")
				.isEqualTo(generated);
		}
	}

	@Test
	void theRuntimeReadsEveryRunAsTheJdkAnswers() {
		// the decoded tables at both sides of every run boundary: the general category
		// and predicates, the script and the block of each, and every name the two
		// forName methods take
		LispEvaluator evaluator = new LispEvaluator(
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
		List<Character.UnicodeBlock> blocks = blocks();
		TreeSet<Integer> points = new TreeSet<>(List.of(0, Character.MAX_CODE_POINT));
		for (int codePoint = 1; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
			if (combo(codePoint) != combo(codePoint - 1)
					|| Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.of(codePoint - 1)
					|| Character.UnicodeBlock.of(codePoint) != Character.UnicodeBlock.of(codePoint - 1)) {
				points.add(codePoint - 1);
				points.add(codePoint);
			}
		}
		List<Integer> list = new ArrayList<>(points);
		List<String> expected = new ArrayList<>();
		List<String> actual = new ArrayList<>();
		for (int from = 0; from < list.size(); from += 2000) {
			List<Integer> chunk = list.subList(from, Math.min(from + 2000, list.size()));
			String program = "(let ((out nil)) (dolist (c '("
					+ chunk.stream().map(String::valueOf).collect(Collectors.joining(" "))
					+ ") (reverse out)) (setq out (cons (list (rontolisp::%clojure-re-uni-combo c)"
					+ " (rontolisp::%clojure-re-uni-run-value (rontolisp::%clojure-re-uni-scripts) c)"
					+ " (rontolisp::%clojure-re-uni-run-value (rontolisp::%clojure-re-uni-blocks) c)) out))))";
			LispVal answers = evaluator.eval(LispReader.readAllFromString(program, Features.INTERPRETER).get(0));
			for (LispVal rest = answers; rest instanceof LispCons cons; rest = cons.cdr()) {
				List<Long> row = new ArrayList<>();
				for (LispVal item = cons.car(); item instanceof LispCons cell; item = cell.cdr()) {
					row.add(((LispInteger) cell.car()).value());
				}
				actual.add(row.toString());
			}
			for (int codePoint : chunk) {
				Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
				expected.add(List.of((long) combo(codePoint), (long) Character.UnicodeScript.of(codePoint).ordinal(),
						(long) (block == null ? 0 : blocks.indexOf(block) + 1))
					.toString());
			}
		}
		assertThat(actual).isEqualTo(expected);
	}

	@Test
	void theRuntimeFindsAScriptAndABlockByEveryNameForNameTakes() {
		// every name of each, as spelled and in lower case, and spellings that are none
		LispEvaluator evaluator = new LispEvaluator(
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
		List<Character.UnicodeBlock> blocks = blocks();
		Set<String> names = new TreeSet<>(List.of("Latin", "Old_Italic", "OLD ITALIC", "OldItalic", "Basic latin",
				"BASIC-LATIN", "LATIN 1 SUPPLEMENT", "Latin-1Supplement", "Surrogates_Area", "Greek and Coptic", "foo",
				"", "_", "*"));
		scriptNames().forEach(names::addAll);
		for (List<String> entry : blockNames(blocks)) {
			for (String name : entry) {
				String spelled = name.equals("*") ? entry.get(0).replace('_', ' ') : name;
				names.add(spelled);
				names.add(spelled.replace(" ", ""));
			}
		}
		List<String> all = new ArrayList<>();
		for (String name : names) {
			all.add(name);
			all.add(name.toLowerCase(java.util.Locale.ROOT));
		}
		String program = "(mapcar (lambda (s) (list"
				+ " (let ((i (rontolisp::%clojure-re-uni-script-item s))) (if i (car (cdr i)) -1))"
				+ " (let ((i (rontolisp::%clojure-re-uni-block-item s))) (if i (car (cdr i)) -1)))) '("
				+ all.stream()
					.map(name -> new am.ik.rontolisp.LispString(name).print())
					.collect(Collectors.joining(" "))
				+ "))";
		LispVal answers = evaluator.eval(LispReader.readAllFromString(program, Features.INTERPRETER).get(0));
		List<String> actual = new ArrayList<>();
		for (LispVal rest = answers; rest instanceof LispCons cons; rest = cons.cdr()) {
			LispCons row = (LispCons) cons.car();
			actual.add(((LispInteger) row.car()).value() + " " + ((LispInteger) ((LispCons) row.cdr()).car()).value());
		}
		List<String> expected = new ArrayList<>();
		for (String name : all) {
			long script;
			try {
				script = Character.UnicodeScript.forName(name).ordinal();
			}
			catch (IllegalArgumentException ex) {
				script = -1;
			}
			long block;
			try {
				block = blocks.indexOf(Character.UnicodeBlock.forName(name)) + 1;
			}
			catch (IllegalArgumentException ex) {
				block = -1;
			}
			expected.add(script + " " + block);
		}
		assertThat(actual).isEqualTo(expected);
	}

}
