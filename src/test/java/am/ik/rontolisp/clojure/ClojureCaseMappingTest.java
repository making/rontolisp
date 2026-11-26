package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Clojure runtime's case mapping ({@code clojure.lisp} over the tables
 * {@link ClojureCaseMapping} generates) to the JDK's {@code String.toUpperCase} and
 * {@code String.toLowerCase}, the oracle's own calls. The interpreter is asked at the
 * code points where an answer can go wrong -- the first 1,024, both sides of every
 * word-class run and every character whose mapping departs from the one-character one --
 * and the capital sigma's final form over strings drawn from one character of every word
 * class. The cross-backend agreement is {@code clojure-spec.yaml}'s.
 */
class ClojureCaseMappingTest {

	private static final int SIGMA = 0x3A3;

	private static LispEvaluator evaluator;

	private static List<Integer> sample;

	@BeforeAll
	static void sampleTheEdges() {
		evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
		TreeSet<Integer> points = new TreeSet<>();
		for (int codePoint = 0; codePoint < 1024; codePoint++) {
			points.add(codePoint);
		}
		for (int codePoint = 1; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
			if (ClojureCaseMapping.wordClass(codePoint) != ClojureCaseMapping.wordClass(codePoint - 1)
					|| ClojureCaseMapping.specialUpcase(codePoint) != null
					|| ClojureCaseMapping.specialDowncase(codePoint) != null) {
				points.add(codePoint - 1);
				points.add(codePoint);
				points.add(Math.min(codePoint + 1, Character.MAX_CODE_POINT));
			}
		}
		points.add(Character.MAX_CODE_POINT);
		points.removeIf(codePoint -> codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE);
		sample = List.copyOf(points);
	}

	// The answers of a one-argument library function over each string, in order.
	private static List<String> runtime(String function, List<String> inputs) {
		List<String> answers = new ArrayList<>();
		for (int from = 0; from < inputs.size(); from += 300) {
			String strings = inputs.subList(from, Math.min(from + 300, inputs.size()))
				.stream()
				.map(input -> new LispString(input).print())
				.collect(Collectors.joining(" "));
			LispVal list = evaluator
				.eval(LispReader
					.readAllFromString("(mapcar (lambda (s) (rontolisp::" + function + " s)) (list " + strings + "))",
							Features.INTERPRETER)
					.get(0));
			for (LispVal rest = list; rest instanceof LispCons cons; rest = cons.cdr()) {
				answers.add(((LispString) cons.car()).value());
			}
		}
		return answers;
	}

	private static List<String> java(UnaryOperator<String> mapping, List<String> inputs) {
		return inputs.stream().map(mapping).toList();
	}

	private static List<String> eachSampled(String template) {
		return sample.stream().map(codePoint -> template.replace("%s", Character.toString(codePoint))).toList();
	}

	@Test
	void theSampleCoversEveryRunAndEveryDeparture() {
		assertThat(sample).hasSizeGreaterThan(5_000).contains(0xDF, 0x130, 0x149, 0xFB00, 0x1F80, 0x3A3);
	}

	@Test
	void theDecodedTableIsTheGeneratorsAtEverySampledCodePoint() {
		List<Integer> runtime = new ArrayList<>();
		for (int from = 0; from < sample.size(); from += 300) {
			String codes = sample.subList(from, Math.min(from + 300, sample.size()))
				.stream()
				.map(String::valueOf)
				.collect(Collectors.joining(" "));
			LispVal list = evaluator
				.eval(LispReader
					.readAllFromString("(mapcar (lambda (i) (rontolisp::%clojure-word-class i)) (list " + codes + "))",
							Features.INTERPRETER)
					.get(0));
			for (LispVal rest = list; rest instanceof LispCons cons; rest = cons.cdr()) {
				runtime.add((int) ((LispInteger) cons.car()).value());
			}
		}
		assertThat(runtime).isEqualTo(sample.stream().map(ClojureCaseMapping::wordClass).toList());
	}

	@Test
	void everyOneCharacterMappingIsTheJdks() {
		List<String> singles = eachSampled("%s");
		assertThat(runtime("%clojure-upper-case", singles))
			.isEqualTo(java(single -> single.toUpperCase(Locale.ROOT), singles));
		assertThat(runtime("%clojure-lower-case", singles))
			.isEqualTo(java(single -> single.toLowerCase(Locale.ROOT), singles));
	}

	@Test
	void theFinalSigmaReadsTheWordAroundItAsTheJdkDoes() {
		// the sampled character before the sigma, between it and a cased letter before,
		// and after it, before a cased letter after; the JDK misreads a word with a
		// character past the BMP in it (.kb/clojure-frontend.md, "Case mapping")
		List<String> words = new ArrayList<>();
		for (String template : List.of("Α%sΣ", "%sΣ", "ה%sΣ", "1%sΣ", "Α.%sΣ", "ΑΣ%sΑ", "ΑΣ%s", "ΑΣ1%sΑ", "ΑΣ%s1")) {
			eachSampled(template).stream()
				.filter(word -> word.codePoints().allMatch(Character::isBmpCodePoint))
				.forEach(words::add);
		}
		assertThat(runtime("%clojure-lower-case", words)).isEqualTo(java(word -> word.toLowerCase(Locale.ROOT), words));
	}

	@Test
	void theFinalSigmaOverWordsOfEveryClass() {
		// one character of every word class, cased and not, and the punctuation each rule
		// names by value
		int[] alphabet = { 'A', 'a', 0x5D4, SIGMA, '1', 0x2160, 0x2B0, '.', '\'', '"', ',', 0x66B, '-', '_', 0xAD,
				0x2027, 0x964, '$', '%', '#', ' ', '\n', 0x200B, 0x301, 0x345, 0x20DD, 0x903, 0x3099, 0x4E00, 0x30A2,
				0x3042, '+', 0x24B6 };
		Random random = new Random(20261011);
		List<String> words = new ArrayList<>();
		for (int n = 0; n < 4_000; n++) {
			StringBuilder word = new StringBuilder();
			int length = 1 + random.nextInt(8);
			for (int i = 0; i < length; i++) {
				word.appendCodePoint(random.nextInt(4) == 0 ? SIGMA : alphabet[random.nextInt(alphabet.length)]);
			}
			words.add(word.toString());
		}
		assertThat(runtime("%clojure-lower-case", words)).isEqualTo(java(word -> word.toLowerCase(Locale.ROOT), words));
		assertThat(runtime("%clojure-upper-case", words)).isEqualTo(java(word -> word.toUpperCase(Locale.ROOT), words));
	}

	@Test
	void capitalizeIsTheOraclesOverUtf16Units() {
		// clojure.string/capitalize: (.toUpperCase s) under two UTF-16 units, else the
		// first unit upcased and the rest downcased as a string of its own
		List<String> words = List.of("", "ß", "ßa", "ΑΣ", "aΑΣ", "hELLO", "ŉx", "İİ", Character.toString(0x10428) + "Σ",
				Character.toString(0x10428), "ǆA", "ﬀΣΑ");
		assertThat(runtime("%clojure-capitalize", words)).isEqualTo(java(
				word -> word.length() < 2 ? word.toUpperCase(Locale.ROOT)
						: word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT),
				words));
	}

}
