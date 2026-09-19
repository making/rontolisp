package am.ik.rontolisp.scheme;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the run-time half of {@code (scheme char)} ({@code scheme.lisp} over the tables
 * {@link SchemeCharacters} generates) to the JDK, and the case folding spelled twice --
 * {@link SchemeCharacters#foldcase(String)} for {@code #!fold-case} at compile time,
 * {@code %scheme-string-foldcase} at run time -- to each other. The interpreter sweeps
 * every code point in about 200 seconds per property, so it is asked at the code points
 * where an answer can go wrong instead: the first 1,024, both sides of every range bound
 * of every property, and both sides of every character whose mapping departs from the
 * simple one. The cross-backend agreement is {@code scheme-spec.yaml}'s.
 */
class SchemeCharactersTest {

	private static final String COMBINING_DOT_ABOVE = Character.toString(0x307);

	private static final String COMBINING_ACUTE = Character.toString(0x301);

	private static final IntPredicate DECIMAL = codePoint -> Character
		.getType(codePoint) == Character.DECIMAL_DIGIT_NUMBER;

	private static final List<IntPredicate> PROPERTIES = List.of(Character::isAlphabetic, DECIMAL,
			SchemeCharacters::isWhiteSpace, Character::isUpperCase, Character::isLowerCase,
			SchemeCharacters::isCaseIgnorable);

	private static LispEvaluator evaluator;

	private static List<Integer> sample;

	// The sample in lists of 500: a quoted list of thousands overflows the interpreter's
	// stack.
	private static List<String> sampleLists;

	@BeforeAll
	static void loadTheLibrary() {
		evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
		for (LispVal form : Scheme.read("", null)) {
			evaluator.eval(form);
		}
		TreeSet<Integer> points = new TreeSet<>();
		for (int codePoint = 0; codePoint < 1024; codePoint++) {
			points.add(codePoint);
		}
		for (int codePoint = 1; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
			boolean edge = false;
			for (IntPredicate property : PROPERTIES) {
				edge |= property.test(codePoint) != property.test(codePoint - 1);
			}
			String single = Character.toString(codePoint);
			edge |= !single.toUpperCase(Locale.ROOT).equals(upper(codePoint))
					|| !single.toLowerCase(Locale.ROOT).equals(lower(codePoint))
					|| !SchemeCharacters.foldcase(single).equals(lower(codePoint));
			if (edge) {
				points.add(codePoint - 1);
				points.add(codePoint);
				points.add(Math.min(codePoint + 1, Character.MAX_CODE_POINT));
			}
		}
		points.add(Character.MAX_CODE_POINT);
		sample = List.copyOf(points);
		List<String> lists = new ArrayList<>();
		for (int from = 0; from < sample.size(); from += 500) {
			lists.add(sample.subList(from, Math.min(from + 500, sample.size()))
				.stream()
				.map(String::valueOf)
				.collect(Collectors.joining(" ", "'(", ")")));
		}
		sampleLists = List.copyOf(lists);
	}

	private static String eval(String lisp) {
		return evaluator.eval(LispReader.readAllFromString(lisp, Features.INTERPRETER).get(0)).print();
	}

	// A list-answering expression over each sampled list in turn, the answers joined.
	private static String overTheSample(Function<String, String> expression) {
		List<String> parts = new ArrayList<>();
		for (String list : sampleLists) {
			String answer = eval(expression.apply(list));
			if (!answer.equals("NIL")) {
				parts.add(answer.substring(1, answer.length() - 1));
			}
		}
		return parts.isEmpty() ? "NIL" : "(" + String.join(" ", parts) + ")";
	}

	/** The sampled code points a run-time predicate holds for. */
	private static String runtimeHolding(String predicate) {
		return overTheSample(list -> "(let ((out nil)) (dolist (i " + list + ") (if (rontolisp::" + predicate
				+ " (code-char i)) (setq out (cons i out)))) (nreverse out))");
	}

	private static String javaHolding(IntPredicate property) {
		List<String> holding = sample.stream().filter(property::test).map(String::valueOf).toList();
		return holding.isEmpty() ? "NIL" : "(" + String.join(" ", holding) + ")";
	}

	@Test
	void theSampleCoversEveryRangeBound() {
		assertThat(sample).hasSizeGreaterThan(7_000)
			.contains(0x1D7CE, 0x1D7FF, 0x13F8, 0x1E9E, Character.MAX_CODE_POINT);
	}

	@Test
	void everyPropertyIsTheJdks() {
		assertThat(runtimeHolding("%scheme-char-alphabetic?")).isEqualTo(javaHolding(Character::isAlphabetic));
		assertThat(runtimeHolding("%scheme-char-numeric?")).isEqualTo(javaHolding(DECIMAL));
		assertThat(runtimeHolding("%scheme-char-whitespace?")).isEqualTo(javaHolding(SchemeCharacters::isWhiteSpace));
		assertThat(runtimeHolding("%scheme-char-upper-case?")).isEqualTo(javaHolding(Character::isUpperCase));
		assertThat(runtimeHolding("%scheme-char-lower-case?")).isEqualTo(javaHolding(Character::isLowerCase));
		assertThat(runtimeHolding("%scheme-case-ignorable-p"))
			.isEqualTo(javaHolding(SchemeCharacters::isCaseIgnorable));
	}

	@Test
	void digitValueIsTheJdksNumericValueOfEveryDecimalDigit() {
		// Every decimal digit, not a sample: 760 of them.
		List<String> digits = new ArrayList<>();
		List<String> values = new ArrayList<>();
		for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
			if (DECIMAL.test(codePoint)) {
				digits.add(String.valueOf(codePoint));
				values.add(String.valueOf(Character.getNumericValue(codePoint)));
			}
		}
		assertThat(values).hasSize(760);
		assertThat(eval("(mapcar (lambda (i) (rontolisp::%scheme-digit-value (code-char i))) '("
				+ String.join(" ", digits) + "))"))
			.isEqualTo("(" + String.join(" ", values) + ")");
	}

	/**
	 * The sampled code points whose run-time mapping differs from a baseline, each with
	 * the mapping: both Lisp expressions over the character {@code c}, answering a
	 * string.
	 */
	private static String runtimeDepartures(String mapping, String baseline) {
		return overTheSample(list -> "(let ((out nil)) (dolist (i " + list + ") (let* ((c (code-char i)) (m " + mapping
				+ ")) (if (string/= m " + baseline + ") (setq out (cons (cons i m) out))))) (nreverse out))");
	}

	private static String javaDepartures(IntFunction<String> mapping, IntFunction<String> baseline) {
		List<String> departures = new ArrayList<>();
		for (int codePoint : sample) {
			String mapped = mapping.apply(codePoint);
			if (!mapped.equals(baseline.apply(codePoint))) {
				departures.add("(" + codePoint + " . " + new LispString(mapped).print() + ")");
			}
		}
		return departures.isEmpty() ? "NIL" : "(" + String.join(" ", departures) + ")";
	}

	private static String lower(int codePoint) {
		return Character.toString(Character.toLowerCase(codePoint));
	}

	private static String upper(int codePoint) {
		return Character.toString(Character.toUpperCase(codePoint));
	}

	@Test
	void theRunTimeFoldIsTheCompileTimeFold() {
		assertThat(runtimeDepartures("(rontolisp::%scheme-string-foldcase (string c))", "(string (char-downcase c))"))
			.isEqualTo(javaDepartures(codePoint -> SchemeCharacters.foldcase(Character.toString(codePoint)),
					SchemeCharactersTest::lower));
		assertThat(runtimeDepartures("(string (rontolisp::%scheme-char-foldcase c))", "(string (char-downcase c))"))
			.isEqualTo(javaDepartures(codePoint -> Character.toString(SchemeCharacters.foldcase(codePoint)),
					SchemeCharactersTest::lower));
	}

	@Test
	void theFullMappingsOfOneCharacterAreTheJdks() {
		assertThat(runtimeDepartures("(rontolisp::%scheme-string-upcase (string c))", "(string (char-upcase c))"))
			.isEqualTo(javaDepartures(codePoint -> Character.toString(codePoint).toUpperCase(Locale.ROOT),
					SchemeCharactersTest::upper));
		assertThat(runtimeDepartures("(rontolisp::%scheme-string-downcase (string c))", "(string (char-downcase c))"))
			.isEqualTo(javaDepartures(codePoint -> Character.toString(codePoint).toLowerCase(Locale.ROOT),
					SchemeCharactersTest::lower));
	}

	@Test
	void finalSigmaLooksThroughCaseIgnorableCharacters() {
		// Unicode 3.13 Final_Sigma, as the JDK's String.toLowerCase applies it.
		for (String string : List.of("ΧΑΟΣ", "ΧΑΟΣ Σ", "ΣΑ", "AΣ.", "AΣ" + COMBINING_ACUTE, "AΣ'S", "1Σ", "A'Σ", "AΣΣ",
				"ᾼΣ", "A" + COMBINING_ACUTE + "Σ")) {
			assertThat(eval("(rontolisp::%scheme-string-downcase " + new LispString(string).print() + ")")).as(string)
				.isEqualTo(new LispString(string.toLowerCase(Locale.ROOT)).print());
		}
	}

	@Test
	void foldingKnowsTheCharactersFullFoldingTreatsApart() {
		assertThat(SchemeCharacters.foldcase("Straße ΧΑΟΣ İı ẞ ﬁ ǅ Ꭰꭰ"))
			.isEqualTo("strasse χαοσ i" + COMBINING_DOT_ABOVE + "ı ss fi ǆ ᎠᎠ");
	}

}
