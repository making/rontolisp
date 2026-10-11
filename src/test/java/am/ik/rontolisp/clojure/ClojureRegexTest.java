package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.CliStackExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Clojure runtime's regex engine ({@code clojure.lisp}, "Regular expressions":
 * {@code java.util.regex.Pattern}'s parser and match nodes ported) to
 * {@code java.util.regex} itself, the oracle's engine: every find of a pattern over an
 * input with its groups, and the {@code PatternSyntaxException} message of a pattern the
 * oracle refuses, over curated cases and a seeded random corpus of lookarounds, inline
 * flags, classes, quantifiers and groups. The interpreter answers here; the agreement of
 * the four backends is {@code clojure-spec.yaml}'s.
 */
@ExtendWith(CliStackExtension.class)
class ClojureRegexTest {

	// Every find of a pattern over an input: (0 (start end group0 group1 ...) ...), at
	// most twenty; (1 message) for the oracle's PatternSyntaxException, (2 message) for
	// any other signal.
	private static final String PROBE = """
			(defun regex-probe (re in)
			  (handler-case
			      (let* ((p (rontolisp::%clojure-re-compile re))
			             (m (rontolisp::%clojure-re-fresh-matcher p in))
			             (n (rontolisp::%clojure-re-pat-ngroups p))
			             (acc nil))
			        (do ((found (rontolisp::%clojure-re-next m) (rontolisp::%clojure-re-next m))
			             (k 1 (+ k 1)))
			            ((or (null found) (> k 20)) (cons 0 (reverse acc)))
			          (setq acc (cons (cons (car found)
			                                (cons (car (cdr found))
			                                      (rontolisp::%clojure-re-group-strings in found n 0)))
			                          acc))))
			    (error (c) (list (if (rontolisp::%clojure-refusal-p c) 1 2) (princ-to-string c)))))
			""";

	// The same finds through a matcher over a CharSequence type (a deftype whose length
	// and charAt read IN) that has read SEED characters: each search runs over the
	// prefix read so far and reads on while it reads the prefix's end, so a seed of one
	// puts a prefix boundary at 1, 2, 4, 8 ... and one of three at 3, 6, 12 ... The
	// groups are read off the prefix the matcher holds.
	private static final String LAZY_PROBE = """
			(defun regex-lazy-matcher (re in seed)
			  (list :c%matcher (gensym "re") (rontolisp::%clojure-re-compile re) (subseq in 0 (min seed (length in)))
			        (cons 0 nil) (cons (list :c%type 'regex-probe-cs in) (length in))))
			(defun regex-lazy-probe (re in seed)
			  (handler-case
			      (let* ((m (regex-lazy-matcher re in seed))
			             (n (rontolisp::%clojure-re-pat-ngroups (rontolisp::%clojure-re-match-pat m)))
			             (acc nil))
			        (do ((found (rontolisp::%clojure-re-next m) (rontolisp::%clojure-re-next m))
			             (k 1 (+ k 1)))
			            ((or (null found) (> k 20)) (cons 0 (reverse acc)))
			          (setq acc (cons (cons (car found)
			                                (cons (car (cdr found))
			                                      (rontolisp::%clojure-re-group-strings
			                                       (rontolisp::%clojure-re-match-input m) found n 0)))
			                          acc))))
			    (error (c) (list (if (rontolisp::%clojure-refusal-p c) 1 2) (princ-to-string c)))))
			(defun regex-lazy-anchored (re in seed whole)
			  (let* ((m (regex-lazy-matcher re in seed))
			         (n (rontolisp::%clojure-re-pat-ngroups (rontolisp::%clojure-re-match-pat m)))
			         (found (rontolisp::%clojure-re-lazy-search m 0 whole)))
			    (if found
			        (cons (car found)
			              (cons (car (cdr found))
			                    (rontolisp::%clojure-re-group-strings
			                     (rontolisp::%clojure-re-match-input m) found n 0)))
			        nil)))
			(defun regex-lazy-anchored-probe (re in seed)
			  (handler-case
			      (list 0 (regex-lazy-anchored re in seed nil) (regex-lazy-anchored re in seed t))
			    (error (c) (list (if (rontolisp::%clojure-refusal-p c) 1 2) (princ-to-string c)))))
			(defvar rontolisp::%clojure-false '|false|)
			(defun regex-ops (re in ops)
			  (let ((m (rontolisp::%clojure-re-fresh-matcher (rontolisp::%clojure-re-compile re) in))
			        (acc nil))
			    (dolist (op ops (reverse acc))
			      (setq acc
			            (cons (handler-case
			                      (if (eq t (cond ((= op -3) (rontolisp::%clojure-matcher-find m))
			                                      ((= op -2) (rontolisp::%clojure-matcher-looking-at m))
			                                      ((= op -1) (rontolisp::%clojure-matcher-matches m))
			                                      (t (rontolisp::%clojure-matcher-find-from m op))))
			                          (list (rontolisp::%clojure-matcher-start m 0) (rontolisp::%clojure-matcher-end m 0))
			                          nil)
			                    (error (c) (declare (ignore c)) "oob"))
			                  acc)))))
			(rontolisp::%clojure-char-sequence-row 'regex-probe-cs '("java.lang.CharSequence")
			  (list "length" (lambda (x) (length (car (cdr (cdr x)))))
			        "charAt" (lambda (x i) (char (car (cdr (cdr x))) i))))
			""";

	private static LispEvaluator evaluator;

	record Case(String regex, String input) {
	}

	@BeforeAll
	static void defineTheProbe() {
		evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
		evaluator.eval(LispReader.readAllFromString(PROBE, Features.INTERPRETER).get(0));
		for (LispVal form : LispReader.readAllFromString(LAZY_PROBE, Features.INTERPRETER)) {
			evaluator.eval(form);
		}
	}

	private static Case c(String regex, String input) {
		return new Case(regex, input);
	}

	@Test
	void theCuratedPatternsMatchAsJavaUtilRegexDoes() {
		List<Case> cases = List.of(
				// lookahead, lookbehind (bounded, a one-character loop, the overflowing
				// sum that never matches), atomic groups
				c("a(?!c)", "acab"), c("(?<=x)y", "ay xy"), c("(?=(a))a", "a"), c("(?<=(a|bc))d", "bcd"),
				c("(?<=a+)b", "aab"), c("(?<=a+b+)c", "abc"), c("(?<=a*?)b", "ab"), c("(?<=ab*?)c", "abc"),
				c("(?<=(?:ab)+)c", "ababc"), c("(?<=\\R{2})x", "\r\nx"), c("(?<=a{0,2147483647})x", "ax"),
				c("(?<=ba{0,2147483647})x", "bax"), c("(?<=(a|ab)(c|bc))d", "abcd"), c("(?<!^)a", "aa"),
				c("(?<=\\1(a))x", "aax"), c("(?>a|ab)c", "abc"), c("(?>ab|a)c", "abc"), c("(?<=(?>ab|a))c", "abc"),
				// captures the oracle keeps on paths it leaves
				c("(?=(a))ab|(?=(a))ac", "ac"), c("(?!(a)b)a(c)?", "abac"), c("((\\w)\\w)*\\w\\w", "abcde"),
				c("((\\w)\\w)*\\w\\w", "ab"), c("()*", "x"), c("()+", "x"), c("(a?)*", "x"), c("(a*)+", "aab"),
				c("(?:xy|x)*(yz|xyz)", "xyz"), c("(\\0141){2}+(?>\\W{0,2}).|", "A-1ka-"),
				// inline flags: global, scoped, off, across |, and the comments lexer
				c("(?x) a b # comment", "xab"), c("(?s)a.b", "a\nb"), c("(?i)abc", "xAbC"), c("a(?i)b|c", "aBC"),
				c("(a(?i)b)c|C", "abC C"), c("(?i:a)b|c", "AbC"), c("(?i-i)a", "A"), c("(?x-x) a", " a"),
				c("(?m)^a$", "a\nb\na"), c("(?m)$", "a\r\nb"), c("(?md)^.", "a\rb\nc"), c("(?d)$", "a\r"),
				c("(?x)[a b]+", "ab ba"), c("(?x)( ?:a)", "a"), c("(?x)a+ ?", "aaa"), c("(?x)a b{2, 3}", "abbb"),
				c("(?x)a#b\rc", "ac"), c("(?xd)a#b\rc", "ac"), c("(?x)\\Q a\\E", " a"), c("(?i)[a-c]+", "xAbC"),
				c("(?i)\\u00e9", "É"), c("(?iu)\\u00e9", "É"), c("(?iu)\\u00df", "ẞ"), c("(?iu)a\\u00df", "aẞ"),
				c("(?iu)k", "K"), c("(?iu)[k]", "K"), c("(?iu)[a-z]+", "K"), c("(?i)(a)\\1", "aA"),
				c("(?iu)(\\u00e9)\\1", "éÉ"),
				// the parser: \Q..\E, escapes, back references, classes, closures
				c("\\Qab\\E+", "abab"), c("(a)\\11", "aa1"), c("\\11", "a1"), c("[a[bc]]+", "abcd"),
				c("[a&&[^b]]", "ab"), c("[[:alpha:]]+", ":alpha"), c("[\\w&&[^\\d]]+", "ab12cd"), c("[&&a]", "a"),
				c("[a&&]", "a"), c("[a&]+", "a&b"), c("\\x{41}\\cJ\\ca", "A\n!"), c("\\0101\\0400", "A 0"),
				c("{2}", "x"), c("a{1}{2}", "a"), c(".", "a\u0085b c"), c("$", "a\r\n"), c("a$", "a\n\n"),
				c("\\R\\n", "\r\n"), c("\\R*\\n", "\r\n"), c("(?:\\R|x)*\\n", "\r\n"), c("\\h+\\v", "a \t　\n"),
				c("\\b{3}", "ab"), c("^+", "abc"), c("(?<n>a)\\k<n>", "aa"),
				// the oracle's refusals
				c("(a", ""), c("a)", ""), c("a|*", ""), c("a{2,1}", ""), c("a{2", ""), c("a{,2}", ""),
				c("a{99999999999}", ""), c("[]", ""), c("[z-a]", ""), c("[a-\\d]", ""), c("\\", ""), c("\\y", ""),
				c("\\E", ""), c("(?<n>a)(?<n>b)", ""), c("(?<1n>a)", ""), c("\\k<n>(?<n>a)", ""), c("(?$a)", ""),
				c("\\x{110000}", ""), c("\\x{41", ""), c("\\u12", ""), c("\\c", ""), c("\\08", ""), c("[\\1]", ""),
				c("[&&]", ""), c("[[]", ""), c("(?i", ""), c("(?q)", ""), c("x{2}?+", ""), c("(?x)[ ]", ""),
				c("\t(a", ""), c("\\Q(\\E(", ""), c("(?<=a*b)c", "bc"));
		assertThat(runtime(cases)).isEqualTo(java(cases));
	}

	@Test
	void randomPatternsMatchAsJavaUtilRegexDoes() throws Exception {
		List<Case> cases = new RandomPatterns(new Random(20261011)).cases(2000);
		assertThat(cases).hasSizeGreaterThan(1900);
		assertThat(runtime(cases)).isEqualTo(java(cases));
	}

	@Test
	void aMatcherOverACharSequenceTypeReadingAPrefixAnswersAsOverTheWholeInput() throws Exception {
		// A matcher over a CharSequence type reads a prefix and reads on only while a
		// search reads where the prefix ends (the engine's end mark): every find,
		// lookingAt and matches over the random corpus and the curated cases with the
		// boundaries of every power of two (seed 1) and of three times one (seed 3) is
		// java.util.regex's answer over the whole input. An engine step that read the
		// end without marking it would stop at a prefix boundary.
		List<Case> cases = new ArrayList<>(new RandomPatterns(new Random(20261011)).cases(2000));
		cases.addAll(List.of(c("a+b", "aaaaaaaaaaaaaaaaab"), c("a*?b", "aaaab"), c("a{2,5}+b", "aaaaab"),
				c("\\w+$", "abcdefghij"), c("(?m)^b", "aaaa\nbb"), c("a\\b", "aaaa"), c("\\Bb", "aaab"),
				c("(a)\\1{3}", "aaaaa"), c("a\\R", "aaaa\r\n"), c("x\\z", "aaaax"), c("x\\Z", "aax\n"),
				c("(?=aaaaab)a", "aaaaab"), c("(?!aaaaab)a", "aaaaac"), c("(?<=aaaa)b", "aaaab"),
				c("abc|abcdef", "abcdef"), c("(?:ab)*c", "ababababc"), c("(a|ab)*c", "abababc"), c("$", "aaaaaa"),
				c("(?d)$", "aaaa\n"), c("", "")));
		for (int seed : List.of(1, 3)) {
			assertThat(mismatches(cases, lazyRuntime("regex-lazy-probe", cases, seed), java(cases)))
				.as("finds, seed " + seed)
				.isEmpty();
			assertThat(mismatches(cases, lazyRuntime("regex-lazy-anchored-probe", cases, seed), anchored(cases)))
				.as("lookingAt and matches, seed " + seed)
				.isEmpty();
		}
	}

	// The cases whose two answers differ, each with both answers: the whole list of
	// answers says too little when one of two thousand cases differs.
	private static List<String> mismatches(List<Case> cases, List<String> actual, List<String> expected) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < cases.size(); i++) {
			if (!actual.get(i).equals(expected.get(i))) {
				out.add(quote(cases.get(i).regex()) + " over " + quote(cases.get(i).input()) + ": " + actual.get(i)
						+ " where java.util.regex answers " + expected.get(i));
			}
		}
		return out;
	}

	@Test
	void aTopLevelGreedyLoopRemembersWhereItFailedAsTheOraclesDoes() throws Exception {
		// the oracle's Loop memo (localsPos): without it each pattern backtracks
		// exponentially over its input; with a back reference the oracle keeps none
		String as = "a".repeat(120);
		List<Case> cases = List.of(c("(a|aa)*c", as), c("(?:\\w+\\s?)*$", "ab ".repeat(40) + "!"),
				c("(x+x+)+y", "x".repeat(40)), c("(?=(a|aa)*c)", as), c("(a|aa)*\\1c", "aaaa"));
		assertThat(CliStack.callWithin("regex-loop-memo", () -> runtime(cases), Duration.ofSeconds(60)))
			.contains(java(cases));
	}

	@Test
	void whatNoBackendMatchesIsRefusedAsAnUnsupportedRegex() {
		// a name the oracle looks up in Unicode's name list, the grapheme clusters, a
		// class
		// or a property matched against the input's NFC normalization
		List<Case> cases = List.of(c("\\N{LATIN SMALL LETTER A}", "a"), c("\\X", "a"), c("\\b{g}", "a"),
				c("(?c)[a]", "a"), c("(?c)\\p{L}", "a"));
		assertThat(runtime(cases)).isEqualTo(List.of("(2 \"unsupported regex: \\\\N{...} is not supported\")",
				"(2 \"unsupported regex: \\\\X is not supported\")",
				"(2 \"unsupported regex: \\\\b{g} is not supported\")",
				"(2 \"unsupported regex: a character class under (?c) is not supported\")",
				"(2 \"unsupported regex: a character class under (?c) is not supported\")"));
		// the oracle takes each, and (?U) or (?c) where neither changes a match; a class
		// the oracle refuses is refused as it refuses it, (?c) or not
		assertThat(java(cases)).noneMatch(answer -> answer.startsWith("(1 "));
		List<Case> taken = List.of(c("(?U)a", "A"), c("(?iU)\\u00e9", "É"), c("(?c)a", "a"), c("(?c)[a", "a"),
				c("(?c)\\p{Foo}", "a"));
		assertThat(runtime(taken)).isEqualTo(java(taken));
	}

	@Test
	void unicodePropertiesMatchAsJavaUtilRegexDoes() {
		String text = "héllo Wörld 42 ٤٢ Ǆǅǆ ΣΑΣ ﬁ _x‿y ｱ日本語 \u0301\u20dd e\u0301 1\u0301 \t\u00a0\u2028 $€ («»)"
				+ " 😀🏻🇯🇵 \ufdd0\uffff Ⅻⓐǅ ";
		List<Case> cases = new ArrayList<>();
		for (String regex : List.of("\\p{L}+", "\\pL+", "\\PL+", "\\p{Lu}", "\\p{Ll}+", "\\p{Lt}", "\\p{Lm}",
				"\\p{Lo}+", "\\p{Mn}", "\\p{Me}", "\\p{Nd}+", "\\p{N}+", "\\p{No}", "\\p{Nl}", "\\p{Zs}", "\\p{Z}",
				"\\p{Zl}", "\\p{Cc}", "\\p{Cn}", "\\p{C}", "\\p{Cs}", "\\p{Co}", "\\p{Cf}", "\\p{P}+", "\\p{Pc}",
				"\\p{Pd}", "\\p{Ps}", "\\p{Pe}", "\\p{Pi}", "\\p{Pf}", "\\p{Po}", "\\p{S}", "\\p{Sc}", "\\p{Sm}",
				"\\p{Sk}", "\\p{So}", "\\p{LC}+", "\\p{LD}+", "\\p{L1}+", "\\p{all}", "\\p{ASCII}+", "\\p{Alnum}+",
				"\\p{Alpha}+", "\\p{Blank}", "\\p{Cntrl}", "\\p{Digit}", "\\p{Graph}+", "\\p{Lower}+", "\\p{Print}+",
				"\\p{Punct}", "\\p{Space}", "\\p{Upper}", "\\p{XDigit}+", "\\p{javaLowerCase}+", "\\p{javaUpperCase}",
				"\\p{javaAlphabetic}+", "\\p{javaIdeographic}+", "\\p{javaTitleCase}", "\\p{javaDigit}+",
				"\\p{javaDefined}+", "\\p{javaLetter}+", "\\p{javaLetterOrDigit}+", "\\p{javaJavaIdentifierStart}+",
				"\\p{javaJavaIdentifierPart}+", "\\p{javaUnicodeIdentifierStart}+", "\\p{javaUnicodeIdentifierPart}+",
				"\\p{javaIdentifierIgnorable}", "\\p{javaSpaceChar}", "\\p{javaWhitespace}", "\\p{javaISOControl}",
				"\\p{javaMirrored}", "\\p{IsAlphabetic}+", "\\p{IsLetter}+", "\\p{IsIdeographic}+", "\\p{IsLowercase}+",
				"\\p{IsUppercase}+", "\\p{IsTitlecase}", "\\p{IsPunctuation}", "\\p{IsWhite_Space}",
				"\\p{IsWhiteSpace}", "\\p{IsDigit}+", "\\p{IsHex_Digit}+", "\\p{IsHexDigit}", "\\p{IsJoin_Control}",
				"\\p{IsNoncharacter_Code_Point}", "\\p{IsAssigned}+", "\\p{IsControl}", "\\p{IsEmoji}",
				"\\p{IsEmoji_Presentation}", "\\p{IsEmoji_Modifier}", "\\p{IsEmoji_Modifier_Base}",
				"\\p{IsEmoji_Component}", "\\p{IsExtended_Pictographic}", "\\p{IsWord}+", "\\p{IsAlnum}+",
				"\\p{IsBlank}", "\\p{IsGraph}+", "\\p{IsPrint}+", "\\p{IsPunct}", "\\p{IsSpace}", "\\p{IsCntrl}",
				"\\p{IsXDigit}", "\\p{IsAlpha}+", "\\p{IsLower}+", "\\p{IsUpper}", "\\p{Isalphabetic}+", "\\p{IsLu}",
				"\\p{IsL}+", "\\p{IsLatin}+", "\\p{IsGreek}+", "\\p{Isgreek}+", "\\p{IsGrek}+", "\\p{IsHan}+",
				"\\p{IsKatakana}", "\\p{IsCommon}+", "\\p{IsInherited}", "\\p{IsZyyy}+", "\\p{InGreek}+",
				"\\p{InBasic_Latin}+", "\\p{InBasicLatin}+", "\\p{InBasic Latin}+", "\\p{InLatin-1 Supplement}+",
				"\\p{InLATIN_1_SUPPLEMENT}+", "\\p{InGreek and Coptic}+", "\\p{InCJKUnifiedIdeographs}+",
				"\\p{InSurrogates_Area}", "\\p{InEmoticons}", "\\p{sc=Latin}+", "\\p{script=Grek}+", "\\p{SC=latn}+",
				"\\p{blk=Greek}+", "\\p{block=Halfwidth and Fullwidth Forms}", "\\p{gc=Lu}",
				"\\p{general_category=Nd}+", "\\p{GC=L}+", "\\P{L}+", "\\P{IsLatin}+", "\\P{InGreek}",
				"[\\p{L}&&[^a-z]]+", "[^\\p{L}\\p{N}]+", "[\\p{Lu}\\d]+", "[\\P{L}x]+", "[a-z\\p{InGreek}]+",
				"(?i)\\p{Lu}+", "(?i)\\p{Ll}+", "(?i)\\p{Lt}+", "(?i)\\p{Lower}+", "(?i)\\p{Upper}+",
				"(?i)\\p{javaLowerCase}+", "(?i)\\p{IsLowercase}+", "(?i)\\p{IsUppercase}+", "(?i)\\p{IsTitlecase}+",
				"(?U)\\p{Lower}+", "(?U)\\p{Alpha}+", "(?U)\\p{Punct}", "(?U)\\p{Digit}+", "(?U)\\p{Space}",
				"(?U)\\p{XDigit}+", "(?U)\\p{Alnum}+", "(?U)\\p{Blank}", "(?U)\\p{Graph}+", "(?U)\\p{Print}+",
				"(?U)\\p{Cntrl}", "(?U)\\p{Upper}+", "(?iU)\\p{Upper}+", "(?U)\\p{L}+", "(?U)\\p{javaDigit}+",
				"(?U)\\w+", "(?U)\\W+", "(?U)\\d+", "(?U)\\D+", "(?U)\\s+", "(?U)\\S+", "(?U)[\\w&&[^\\d]]+",
				"(?U)\\b\\w+\\b", "(?U)\\B.", "(?U:\\w+)\\w", "(?U)(?-U:\\w+)", "\\b\\w+\\b", "\\b.", "\\B.",
				"(?x)\\p { L } +", "(?x)\\p{ L }", "(?x)\\pL+", "\\p{L}{2}", "\\p{L}*?\\d", "(?<=\\p{L})\\d",
				"\\Q\\p{L}\\E")) {
			cases.add(c(regex, text));
		}
		cases.addAll(List.of(c("\\b\\w+\\b", "été"), c("\\b\\w+\\b", "e\u0301te\u0301"), c("\\b", "\u0301a"),
				c("\\b", "a\u0301\u0302 b"), c("\\b", "1\u0301"), c("\\b", "_\u0301"), c("\\B", "a\u0301"),
				c("(?U)\\b", "日本 語"), c("(?U)\\b", "a\u0301 \u0301"), c("(?U)\\w+", "a\u200cb\u200d"),
				// the oracle's refusals of a family it cannot resolve
				c("\\p{}", ""), c("\\p{L", ""), c("\\p{Foo}", ""), c("\\pX", ""), c("\\p", ""), c("\\P", ""),
				c("\\p{IsFoo}", ""), c("\\p{InFoo}", ""), c("\\p{sc=Foo}", ""), c("\\p{x=y}", ""), c("\\p{=L}", ""),
				c("\\p{gc=}", ""), c("\\p{lu}", ""), c("\\p{alpha}", ""), c("(?U)\\p{alpha}", "a"),
				c("\\p{InBasic-Latin}", ""), c("\\p{IsLatn}", "a"), c("[\\p{Foo}]", ""), c("[\\p{L}-z]", ""),
				c("[a-\\p{L}]", ""), c("(?x)\\p{L # c\n}", ""), c("(?x)\\p{ # }\n}", ""), c("a\\p{L", ""),
				c("\\p{L}\\p{Foo", ""), c("\\p{Is}", ""), c("\\p{In}", ""), c("\\p{L1 }", "")));
		assertThat(mismatches(cases, runtime(cases), java(cases))).isEmpty();
	}

	@Test
	void randomUnicodePatternsMatchAsJavaUtilRegexDoes() throws Exception {
		List<Case> cases = new RandomPatterns(new Random(20261012), true).cases(1500);
		assertThat(cases).hasSizeGreaterThan(1400);
		assertThat(mismatches(cases, runtime(cases), java(cases))).isEmpty();
	}

	@Test
	void backslashGHoldsWhereTheMatchersLastMatchEnded() {
		List<Case> cases = List.of(c("\\G\\d", "12a3"), c("\\G", "ab"), c("\\Ga|b", "aabab"), c("a|\\Gb", "bab"),
				c("\\G\\w*", "ab cd"), c("(?<=\\G.)x", "axbx"), c("\\G(?:ab)*", "ababxab"), c("[\\G]", ""),
				c("\\G+", "a"), c("x*\\G", "xxa"));
		assertThat(mismatches(cases, runtime(cases), java(cases))).isEmpty();
		// the matcher methods: find from the last match's end (one past an empty one),
		// find(int) from its start, lookingAt and matches from the start, each failure
		// keeping where the last match ended, as the oracle's
		Random random = new Random(20261012);
		List<String> patterns = List.of("\\G\\w", "\\Ga*", "\\G\\w+|x", "a\\G", "\\Gab|b", "^\\G", "\\G$", "(?:\\Ga)+",
				"\\G\\b\\w", "^");
		List<String> inputs = List.of("aab", "ab ab", "xaxa", "", "b");
		StringBuilder program = new StringBuilder("(list");
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			String regex = patterns.get(random.nextInt(patterns.size()));
			String input = inputs.get(random.nextInt(inputs.size()));
			List<Integer> ops = new ArrayList<>();
			for (int k = 0; k < 7; k++) {
				ops.add(random.nextInt(4) == 0 ? random.nextInt(4) - 3 : random.nextInt(input.length() + 2));
			}
			program.append(" (regex-ops ")
				.append(new LispString(regex).print())
				.append(' ')
				.append(new LispString(input).print())
				.append(" '(")
				.append(ops.stream().map(String::valueOf).collect(Collectors.joining(" ")))
				.append("))");
			expected.add(javaOps(regex, input, ops));
		}
		LispVal answers = evaluator
			.eval(LispReader.readAllFromString(program.append(')').toString(), Features.INTERPRETER).get(0));
		List<String> actual = new ArrayList<>();
		for (LispVal rest = answers; rest instanceof LispCons cons; rest = cons.cdr()) {
			actual.add(render(cons.car()));
		}
		assertThat(actual).isEqualTo(expected);
	}

	// An op script over one matcher: -3 find(), -2 lookingAt(), -1 matches(), n >= 0
	// find(n); each answers (start end), nil, or the refusal of find(n) past the input.
	private static String javaOps(String regex, String input, List<Integer> ops) {
		Matcher m = Pattern.compile(regex).matcher(input);
		List<String> out = new ArrayList<>();
		for (int op : ops) {
			boolean hit;
			try {
				hit = op == -3 ? m.find() : op == -2 ? m.lookingAt() : op == -1 ? m.matches() : m.find(op);
			}
			catch (IndexOutOfBoundsException ex) {
				out.add("\"oob\"");
				continue;
			}
			out.add(hit ? "(" + m.start() + " " + m.end() + ")" : "nil");
		}
		return "(" + String.join(" ", out) + ")";
	}

	// The runtime's answer to each case, rendered like java(...)'s.
	private static List<String> runtime(List<Case> cases) {
		List<String> answers = new ArrayList<>();
		for (int from = 0; from < cases.size(); from += 200) {
			String calls = cases.subList(from, Math.min(from + 200, cases.size()))
				.stream()
				.map(c -> "(regex-probe " + new LispString(c.regex()).print() + " " + new LispString(c.input()).print()
						+ ")")
				.collect(Collectors.joining(" "));
			LispVal list = evaluator
				.eval(LispReader.readAllFromString("(list " + calls + ")", Features.INTERPRETER).get(0));
			for (LispVal rest = list; rest instanceof LispCons cons; rest = cons.cdr()) {
				answers.add(render(cons.car()));
			}
		}
		return answers;
	}

	// The lazy PROBE's answer to each case, the matcher seeded with SEED characters.
	private static List<String> lazyRuntime(String probe, List<Case> cases, int seed) {
		List<String> answers = new ArrayList<>();
		for (int from = 0; from < cases.size(); from += 200) {
			String calls = cases.subList(from, Math.min(from + 200, cases.size()))
				.stream()
				.map(c -> "(" + probe + " " + new LispString(c.regex()).print() + " "
						+ new LispString(c.input()).print() + " " + seed + ")")
				.collect(Collectors.joining(" "));
			LispVal list = evaluator
				.eval(LispReader.readAllFromString("(list " + calls + ")", Features.INTERPRETER).get(0));
			for (LispVal rest = list; rest instanceof LispCons cons; rest = cons.cdr()) {
				answers.add(render(cons.car()));
			}
		}
		return answers;
	}

	// java.util.regex's lookingAt and matches of each case, rendered like the lazy
	// anchored probe: (0 looking matches), each (start end group0 ...) or nil, or the
	// PatternSyntaxException message.
	private static List<String> anchored(List<Case> cases) {
		return cases.stream().map(c -> {
			try {
				Pattern p = Pattern.compile(c.regex());
				return "(0 " + anchoredMatch(p.matcher(c.input()), c.input(), false) + " "
						+ anchoredMatch(p.matcher(c.input()), c.input(), true) + ")";
			}
			catch (PatternSyntaxException ex) {
				return "(1 " + quote(String.valueOf(ex.getMessage())) + ")";
			}
		}).toList();
	}

	private static String anchoredMatch(Matcher m, String input, boolean whole) {
		if (!(whole ? m.matches() : m.lookingAt())) {
			return "nil";
		}
		StringBuilder out = new StringBuilder("(").append(input.codePointCount(0, m.start()))
			.append(' ')
			.append(input.codePointCount(0, m.end()));
		for (int g = 0; g <= m.groupCount(); g++) {
			String group = m.group(g);
			out.append(' ').append(group == null ? "nil" : quote(group));
		}
		return out.append(')').toString();
	}

	private static String render(LispVal value) {
		if (value instanceof LispNil) {
			return "nil";
		}
		if (value instanceof LispInteger integer) {
			return Long.toString(integer.value());
		}
		if (value instanceof LispString string) {
			return quote(string.value());
		}
		if (value instanceof LispCons) {
			List<String> items = new ArrayList<>();
			for (LispVal rest = value; rest instanceof LispCons cons; rest = cons.cdr()) {
				items.add(render(cons.car()));
			}
			return "(" + String.join(" ", items) + ")";
		}
		return value.print();
	}

	// java.util.regex's answer to each case: every find (at most twenty) with its
	// groups, or the PatternSyntaxException message.
	private static List<String> java(List<Case> cases) {
		return cases.stream().map(ClojureRegexTest::java).toList();
	}

	private static String java(Case c) {
		try {
			Matcher m = Pattern.compile(c.regex()).matcher(c.input());
			StringBuilder out = new StringBuilder("(0");
			for (int n = 0; n < 20 && m.find(); n++) {
				// the runtime counts code points where the oracle counts UTF-16 units
				out.append(" (")
					.append(c.input().codePointCount(0, m.start()))
					.append(' ')
					.append(c.input().codePointCount(0, m.end()));
				for (int g = 0; g <= m.groupCount(); g++) {
					String group = m.group(g);
					out.append(' ').append(group == null ? "nil" : quote(group));
				}
				out.append(')');
			}
			return out.append(')').toString();
		}
		catch (PatternSyntaxException ex) {
			return "(1 " + quote(String.valueOf(ex.getMessage())) + ")";
		}
	}

	private static String quote(String s) {
		StringBuilder out = new StringBuilder("\"");
		s.codePoints().forEach(cp -> {
			if (cp == '"' || cp == '\\') {
				out.append('\\').appendCodePoint(cp);
			}
			else if (cp < 0x20 || cp > 0x7e) {
				out.append(String.format("\\x{%x}", cp));
			}
			else {
				out.appendCodePoint(cp);
			}
		});
		return out.append('"').toString();
	}

	/**
	 * Random patterns over the constructs the engine ports -- lookarounds, atomic and
	 * named groups, inline flags (comments mode with its spaces and # comments among
	 * them), classes with ranges, escapes, unions and intersections, back references,
	 * every quantifier -- and short inputs over the characters they test, each kept only
	 * when the oracle answers it within a second.
	 */
	static final class RandomPatterns {

		private final Random random;

		private int groups;

		private boolean named;

		private final boolean unicode;

		RandomPatterns(Random random) {
			this(random, false);
		}

		/**
		 * Random patterns, with the Unicode constructs among them when UNICODE: the
		 * properties, the U flag and \\G, over inputs of letters, digits, marks and
		 * spaces past ASCII.
		 */
		RandomPatterns(Random random, boolean unicode) {
			this.random = random;
			this.unicode = unicode;
		}

		private String property() {
			return pick("\\p{L}", "\\P{L}", "\\p{Lu}", "\\p{Ll}", "\\pN", "\\p{Nd}", "\\p{Mn}", "\\p{M}", "\\p{Zs}",
					"\\p{P}", "\\p{IsLatin}", "\\p{IsGreek}", "\\p{InGreek}", "\\p{sc=Han}", "\\p{IsAlphabetic}",
					"\\p{IsWhite_Space}", "\\p{Alpha}", "\\p{Punct}", "\\p{Lower}", "\\p{javaLowerCase}",
					"\\p{javaUpperCase}", "\\p{IsLu}", "\\P{IsLatin}", "\\p{IsDigit}", "\\p{Upper}", "\\p{XDigit}");
		}

		List<Case> cases(int count) throws Exception {
			List<Case> cases = new ArrayList<>();
			ExecutorService oracle = Executors.newSingleThreadExecutor();
			try {
				for (int i = 0; i < count; i++) {
					this.groups = 0;
					this.named = false;
					String regex = (this.random.nextInt(5) == 0 ? "(?" + flags() + ")" : "") + expr(0);
					String input = input();
					Case c = new Case(regex, input);
					Future<String> answer = oracle.submit(() -> java(c));
					try {
						answer.get(1, TimeUnit.SECONDS);
						cases.add(c);
					}
					catch (java.util.concurrent.TimeoutException ex) {
						answer.cancel(true);
						oracle.shutdownNow();
						oracle = Executors.newSingleThreadExecutor();
					}
				}
			}
			finally {
				oracle.shutdownNow();
			}
			return cases;
		}

		private String pick(String... choices) {
			return choices[this.random.nextInt(choices.length)];
		}

		private String literal() {
			return pick("a", "b", "A", "B", "x", "1", "_", " ", "-", "\\n", "\\r", "\\.", "\\-", "\\t", "\\x41",
					"\\u0062", "\\0141", "\\cJ", "#", "k", "K", "s", "S", "\\u212a", "\\u00e9", "\\u00c9", "\\u017f");
		}

		private String classItem() {
			if (this.unicode && this.random.nextInt(4) == 0) {
				return property();
			}
			return switch (this.random.nextInt(9)) {
				case 0 -> "a-c";
				case 1 -> "A-C";
				case 2 -> pick("\\w", "\\d", "\\s", "\\W", "\\S", "\\D", "\\h", "\\v");
				case 3 -> "[" + classBody() + "]";
				case 4 -> pick("x", "-", "^", "&", " ", "\\]", "\\[", "k", "S", "\\u212a", "\\u00e9");
				case 5 -> pick("i-k", "r-t", "0-9", "\\x00-\\x20");
				default -> literal().replace("\\.", ".");
			};
		}

		private String classBody() {
			StringBuilder body = new StringBuilder();
			if (this.random.nextInt(4) == 0) {
				body.append('^');
			}
			int n = 1 + this.random.nextInt(3);
			for (int i = 0; i < n; i++) {
				body.append(classItem());
			}
			if (this.random.nextInt(6) == 0) {
				body.append("&&").append(this.random.nextBoolean() ? "[" + classBody() + "]" : classItem());
			}
			return body.toString();
		}

		private String quantifier() {
			String q = pick("*", "+", "?", "{2}", "{0,2}", "{1,}", "{0,1}", "{2,3}");
			int mode = this.random.nextInt(5);
			return mode == 0 ? q + "?" : mode == 1 ? q + "+" : q;
		}

		private String atom(int depth) {
			if (this.unicode && this.random.nextInt(5) == 0) {
				return this.random.nextInt(6) == 0 ? "\\G" : property();
			}
			return switch (this.random.nextInt(depth > 2 ? 10 : 22)) {
				case 0, 1, 2, 3 -> literal();
				case 4 -> ".";
				case 5 ->
					pick("\\w", "\\d", "\\s", "\\W", "\\b", "\\B", "^", "$", "\\A", "\\z", "\\Z", "\\R", "\\h", "\\v");
				case 6, 7 -> "[" + classBody() + "]";
				case 8 -> this.groups > 0 ? "\\" + (1 + this.random.nextInt(this.groups)) : literal();
				case 9 -> this.named ? "\\k<g>" : literal();
				case 10, 11 -> {
					this.groups++;
					yield "(" + expr(depth + 1) + ")";
				}
				case 12 -> "(?:" + expr(depth + 1) + ")";
				case 13 -> "(?=" + expr(depth + 1) + ")";
				case 14 -> "(?!" + expr(depth + 1) + ")";
				case 15 -> "(?<=" + expr(depth + 1) + ")";
				case 16 -> "(?<!" + expr(depth + 1) + ")";
				case 17 -> "(?>" + expr(depth + 1) + ")";
				case 18 -> "(?" + flags() + ":" + expr(depth + 1) + ")";
				case 19 -> "(?" + flags() + ")";
				case 20 -> {
					if (this.named) {
						yield literal();
					}
					this.named = true;
					this.groups++;
					yield "(?<g>" + expr(depth + 1) + ")";
				}
				default -> literal();
			};
		}

		private String flags() {
			StringBuilder flags = new StringBuilder();
			int n = this.random.nextInt(3);
			for (int i = 0; i < n; i++) {
				flags.append(this.unicode ? pick("i", "m", "s", "x", "d", "u", "U", "U")
						: pick("i", "m", "s", "x", "d", "u"));
			}
			if (this.random.nextInt(4) == 0) {
				flags.append('-').append(this.unicode ? pick("i", "U") : pick("i", "m", "s", "x", "d", "u"));
			}
			return flags.isEmpty() ? "i" : flags.toString();
		}

		private String sequence(int depth) {
			StringBuilder sequence = new StringBuilder();
			int n = this.random.nextInt(4);
			for (int i = 0; i < n; i++) {
				sequence.append(atom(depth));
				if (this.random.nextInt(3) == 0) {
					sequence.append(quantifier());
				}
				if (this.random.nextInt(12) == 0) {
					sequence.append(pick(" ", "  #c\n", "\t"));
				}
			}
			return sequence.toString();
		}

		private String expr(int depth) {
			String expr = sequence(depth);
			return this.random.nextInt(4) == 0 ? expr + "|" + sequence(depth) : expr;
		}

		private String input() {
			String alphabet = this.unicode ? "abAx1_ -éÉΣσ٤日\u0301\u0302\u00a0Ⅻ\u200cǅ" : "abABx1_ -\n\rkKéÉſS#";
			StringBuilder input = new StringBuilder();
			int n = this.random.nextInt(9);
			for (int i = 0; i < n; i++) {
				input.append(alphabet.charAt(this.random.nextInt(alphabet.length())));
			}
			return input.toString();
		}

	}

}
