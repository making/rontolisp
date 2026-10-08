package am.ik.rontolisp.clojure;

import java.util.List;

import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClojureReaderTest {

	private static List<LispVal> read(String source) {
		return new ClojureReader(source, null).readAll();
	}

	private static String printed(String source) {
		return read(source).stream().map(LispVal::print).toList().toString();
	}

	@Test
	void identifiersKeepTheirCaseAndKeywordsStayKeywords() {
		List<LispVal> datums = read("foo Foo FOO :a :A");
		assertThat(datums.stream().map(v -> ((am.ik.rontolisp.LispSymbol) v).name()).toList()).containsExactly("foo",
				"Foo", "FOO", ":a", ":A");
	}

	@Test
	void nilTrueFalseStaySymbolsForTheLowering() {
		assertThat(printed("nil true false")).isEqualTo("[|nil|, |true|, |false|]");
	}

	@Test
	void numbers() {
		assertThat(printed("42 -17 +5 1/2 1.5 1e3 1M 2N")).isEqualTo("[42, -17, 5, 1/2, 1.5, 1000.0, 1, 2]");
	}

	@Test
	void radixIntegers() {
		assertThat(printed("0xFF 2r101 8r17 16rff -0xFF 017 10r17 36rzz 2r101N"))
			.isEqualTo("[255, 5, 15, 255, -255, 15, 17, 1295, 5]");
		assertThat(printed("99999999999999999999999N")).isEqualTo("[99999999999999999999999]");
	}

	@Test
	void bigDecimalsAreExactRatios() {
		assertThat(printed("1M 0.1M 1e3M")).isEqualTo("[1, 1/10, 1000]");
	}

	@Test
	void invalidNumbersAreErrors() {
		assertThatThrownBy(() -> read("09")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid number: 09");
		assertThatThrownBy(() -> read("1e")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid number: 1e");
		assertThatThrownBy(() -> read("2r")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid number: 2r");
		assertThatThrownBy(() -> read("0x")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid number: 0x");
		assertThatThrownBy(() -> read("1.5N")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid number: 1.5N");
		assertThatThrownBy(() -> read("2r101/10")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid number: 2r101/10");
		assertThatThrownBy(() -> read("3/2N")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid number: 3/2N");
	}

	@Test
	void characters() {
		assertThat(printed("\\a \\A \\newline \\space \\tab \\return \\backspace \\formfeed"))
			.isEqualTo("[#\\a, #\\A, #\\Newline, #\\Space, #\\Tab, #\\Return, #\\Backspace, #\\Page]");
		assertThat(printed("\\u0041 \\o141")).isEqualTo("[#\\A, #\\a]");
		assertThatThrownBy(() -> read("\\ab")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unsupported character");
		assertThatThrownBy(() -> read("\\Tab")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unsupported character");
		assertThatThrownBy(() -> read("\\rubout")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unsupported character");
	}

	@Test
	void regexLiteralsReadAsMarkedSourceStrings() {
		assertThat(printed("#\"a+\"")).isEqualTo("[(|%regex| \"a+\")]");
		// backslashes stay verbatim for the pattern parser (oracle `clj`
		// 1.12.6.1673): `#"\\d"` reads two characters, not the digit class
		assertThat(printed("#\"\\\\d\"")).isEqualTo("[(|%regex| \"\\\\\\\\d\")]");
		assertThat(printed("#\"\\d\"")).isEqualTo("[(|%regex| \"\\\\d\")]");
		assertThat(printed("#\"a\\Qb\\Ec\"")).isEqualTo("[(|%regex| \"a\\\\Qb\\\\Ec\")]");
		assertThatThrownBy(() -> read("#\"\\q\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Illegal/unsupported escape sequence");
		assertThatThrownBy(() -> read("#%x")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unsupported reader form #%");
	}

	@Test
	void symbolicValuesReadAsDoubles() {
		assertThat(printed("##NaN ##Inf ##-Inf")).isEqualTo("[NaN, Infinity, -Infinity]");
		assertThat(read("##NaN ##Inf ##-Inf")).allSatisfy(v -> assertThat(v).isInstanceOf(LispDouble.class));
		assertThat(((LispDouble) read("##NaN").get(0)).value()).isNaN();
		assertThat(((LispDouble) read("##Inf").get(0)).value()).isEqualTo(Double.POSITIVE_INFINITY);
		assertThat(((LispDouble) read("##-Inf").get(0)).value()).isEqualTo(Double.NEGATIVE_INFINITY);
		// the oracle reads the next form: whitespace, comments and discards in between,
		// a delimiter ends the symbol
		assertThat(printed("## Inf ##\n;c\n -Inf [##Inf]")).isEqualTo("[Infinity, -Infinity, (|%vector| Infinity)]");
	}

	@Test
	void unknownSymbolicValuesAreRefusedByName() {
		assertThatThrownBy(() -> read("##Foo")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unknown symbolic value: ##Foo");
		assertThatThrownBy(() -> read("##-NaN")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unknown symbolic value: ##-NaN");
		assertThatThrownBy(() -> read("##inf")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unknown symbolic value: ##inf");
		assertThatThrownBy(() -> read("##1")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid token: ##1");
		assertThatThrownBy(() -> read("##\"a\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid token: ##a");
		assertThatThrownBy(() -> read("##nil")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid token: ##null");
		assertThatThrownBy(() -> read("##")).isInstanceOf(LispReadException.class);
	}

	@Test
	void stringsCommentsAndCommas() {
		List<LispVal> datums = read("\"a\" ; a comment\n[a, b]");
		assertThat(datums).hasSize(2);
		assertThat(datums.get(1).print()).isEqualTo("(|%vector| |a| |b|)");
	}

	@Test
	void unknownStringEscapesSignalInsteadOfDuplicatingTheNextChar() {
		assertThatThrownBy(() -> read("\"a\\q\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unsupported escape character: \\q");
		// with a char behind the escape: the old default arm read the next char
		// twice, so `"a\qb"` came out as `"abb"` instead of signalling
		assertThatThrownBy(() -> read("\"a\\qb\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unsupported escape character: \\q");
		assertThat(read("\"a\\n\\t\\r\\f\\b\\\\\\\"\\u0041\"")).isEqualTo(List.of(new LispString("a\n\t\r\f\b\\\"A")));
	}

	@Test
	void octalStringEscapesMatchTheOracle() {
		// measured on `clj` 1.12.6.1673: `"\0"` is NUL, `"\77"` is `?`
		assertThat(read("\"\\0\"")).isEqualTo(List.of(new LispString("\0")));
		assertThat(read("\"\\77\"")).isEqualTo(List.of(new LispString("?")));
		assertThat(read("\"\\377\"")).isEqualTo(List.of(new LispString("ÿ")));
		assertThat(read("\"\\07\\00\"")).isEqualTo(List.of(new LispString("\7\0")));
		// the escape stops at the closing quote, whitespace, `,` or a macro
		// char, leaving it for the string body
		assertThat(read("\"a\\12 b\"")).isEqualTo(List.of(new LispString("a\n b")));
		assertThat(read("\"a\\0,b\"")).isEqualTo(List.of(new LispString("a\0,b")));
		assertThat(read("\"\\0123\"")).isEqualTo(List.of(new LispString("\n3")));
		// past `\377` is the oracle's range refusal
		assertThatThrownBy(() -> read("\"\\400\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Octal escape sequence must be in range [0, 377].");
		assertThatThrownBy(() -> read("\"\\777\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Octal escape sequence must be in range [0, 377].");
		// a non-octal digit -- `8`/`9` or a trailing letter -- is the oracle's
		// `Invalid digit` refusal
		assertThatThrownBy(() -> read("\"a\\8b\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid digit: 8");
		assertThatThrownBy(() -> read("\"a\\9b\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid digit: 9");
		assertThatThrownBy(() -> read("\"a\\0b\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid digit: b");
	}

	@Test
	void unicodeStringEscapesMatchTheOracle() {
		// measured on `clj` 1.12.6.1673: exactly four hex digits read, the
		// rest stays string body
		assertThat(read("\"\\u0041\"")).isEqualTo(List.of(new LispString("A")));
		assertThat(read("\"\\u00419\"")).isEqualTo(List.of(new LispString("A9")));
		// a non-hex FIRST digit is the oracle's `Invalid unicode escape` (never
		// the old `NumberFormatException` leak, and even the closing quote is
		// named, like the closing quote in `"\"\\u\""`)
		assertThatThrownBy(() -> read("\"\\uzzzz\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid unicode escape: \\uz");
		assertThatThrownBy(() -> read("\"\\u\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid unicode escape: \\u\"");
		// a non-hex LATER digit is the oracle's `Invalid digit`
		assertThatThrownBy(() -> read("\"\\u12xg\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid digit: x");
		assertThatThrownBy(() -> read("\"\\u123x\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid digit: x");
		assertThatThrownBy(() -> read("\"\\u12:b\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid digit: :");
		// fewer than four digits before a stop (the closing quote, whitespace,
		// `,` or a macro char) is the oracle's length refusal, not the old
		// `truncated \\u escape`
		assertThatThrownBy(() -> read("\"\\u12\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid character length: 2, should be: 4");
		assertThatThrownBy(() -> read("\"\\u1\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid character length: 1, should be: 4");
		assertThatThrownBy(() -> read("\"\\u12 b\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid character length: 2, should be: 4");
		// a buffer ending mid-escape stays incomplete (the session waits for the
		// rest), while the closed-but-wrong shape is complete, so it is reported
		assertThat(ClojureSession.isComplete("\"\\u")).isFalse();
		assertThat(ClojureSession.isComplete("\"\\u12")).isFalse();
		assertThat(ClojureSession.isComplete("\"\\u12\"")).isTrue();
	}

	@Test
	void singleQuoteEscapeSignalsLikeTheOracle() {
		// `\'` read as `'` here but the oracle (clj 1.12.6.1673) signals
		// `Unsupported escape character: \'`: refused to match it instead
		// of keeping the lenient read (a `'` needs no escaping in `"..."`).
		assertThatThrownBy(() -> read("\"a\\'b\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unsupported escape character: \\'");
	}

	@Test
	void vectorsMapsSetsAndQuotes() {
		assertThat(printed("[1 :a]")).isEqualTo("[(|%vector| 1 :|a|)]");
		assertThat(printed("{:a 1}")).isEqualTo("[(|%hash-map| :|a| 1)]");
		assertThat(printed("#{1 2}")).isEqualTo("[(|%hash-set| 1 2)]");
		assertThat(printed("'{}")).isEqualTo("[(|quote| (|%hash-map|))]");
		assertThat(printed("'#{1}")).isEqualTo("[(|quote| (|%hash-set| 1))]");
		assertThat(printed("'(1 2)")).isEqualTo("[(|quote| (1 2))]");
	}

	@Test
	void anonFnReadsAsTheOraclesFnStarOverGeneratedParameters() {
		assertThat(printed("#(* % %1)")).isEqualTo("[(|fn*| (|%vector| |p1__1#|) (* |p1__1#| |p1__1#|))]");
		// an unused lower parameter is generated after the body; %& is the rest
		assertThat(printed("#(f %2 %&)"))
			.isEqualTo("[(|fn*| (|%vector| |p1__3#| |p2__1#| & |rest__2#|) (|f| |p2__1#| |rest__2#|))]");
		// the numbers restart per top-level form, and an argument inside a quote is
		// replaced too
		assertThat(printed("#(f '%) #(g %)")).isEqualTo(
				"[(|fn*| (|%vector| |p1__1#|) (|f| (|quote| |p1__1#|))), (|fn*| (|%vector| |p1__1#|) (|g| |p1__1#|))]");
		assertThat(printed("%")).isEqualTo("[%]");
		assertThatThrownBy(() -> read("#(f #(g %))")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Nested #()s are not allowed");
		assertThatThrownBy(() -> read("#(f %x)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("arg literal must be %, %& or %integer");
	}

	@Test
	void derefMetaVarAndDiscard() {
		assertThat(printed("@x")).isEqualTo("[(|deref| |x|)]");
		assertThat(printed("^:k v")).isEqualTo("[(|%with-meta| |v| :|k|)]");
		assertThat(printed("#'x")).isEqualTo("[(|var| |x|)]");
		assertThat(printed("#_skip-me y")).isEqualTo("[|y|]");
		assertThat(printed("@x ^:k v #'x #_skip-me y"))
			.isEqualTo("[(|deref| |x|), (|%with-meta| |v| :|k|), (|var| |x|), |y|]");
	}

	@Test
	void aDiscardBeforeAClosingBracketOrTheEndDiscardsLikeTheOracle() {
		// oracle (clj 1.12.6.1673): '[1 #_ 2] is [1], '(a #_ b) is (a), a map entry
		// commented out at its end drops, and a file may end in a discard
		assertThat(printed("[1 #_ 2]")).isEqualTo("[(|%vector| 1)]");
		assertThat(printed("(a #_ b)")).isEqualTo("[(|a|)]");
		assertThat(printed("{:a 1 #_ :b #_ 2}")).isEqualTo("[(|%hash-map| :|a| 1)]");
		assertThat(printed("x #_ y")).isEqualTo("[|x|]");
		assertThat(printed("#_ #_ a b c")).isEqualTo("[|c|]");
		assertThat(printed("'#_ a b")).isEqualTo("[(|quote| |b|)]");
		assertThatThrownBy(() -> read("#_")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unexpected end of input");
	}

	@Test
	void aCharacterLiteralTakesItsFirstCharacterWhateverItIs() {
		// oracle: the character behind the backslash belongs to the literal
		// unconditionally, so what pr spells for a delimiter reads back; a
		// backslash ends the literal
		assertThat(printed("\\( \\) \\[ \\] \\{ \\} \\\" \\; \\, \\\\ \\# \\' \\@ \\^ \\` \\~"))
			.isEqualTo(printed("\\u0028 \\u0029 \\u005B \\u005D \\u007B \\u007D \\u0022 \\u003B \\u002C \\u005C "
					+ "\\u0023 \\u0027 \\u0040 \\u005E \\u0060 \\u007E"));
		assertThat(printed("[\\(\\a\\b]")).isEqualTo("[(|%vector| #\\( #\\a #\\b)]");
		assertThat(printed("\\a,")).isEqualTo("[#\\a]");
	}

	@Test
	void legacyHashCaretMetadataReadsLikeTheCaret() {
		assertThat(printed("#^:k v")).isEqualTo(printed("^:k v"));
		assertThat(printed("#^{:doc \"x\"} v")).isEqualTo(printed("^{:doc \"x\"} v"));
		assertThat(printed("(defn #^String f [#^long x] x)")).isEqualTo(printed("(defn ^String f [^long x] x)"));
	}

	@Test
	void recordLiteralsReadAsMarkedClassAndBody() {
		assertThat(printed("#user.P{:a 1}")).isEqualTo("[(|%record| |user.P| (|%hash-map| :|a| 1))]");
		assertThat(printed("#my_app.core.P[1 x]")).isEqualTo("[(|%record| |my_app.core.P| (|%vector| 1 |x|))]");
		assertThat(printed("#user.P {}")).isEqualTo("[(|%record| |user.P| (|%hash-map|))]");
	}

	@Test
	void recordLiteralRefusalsMatchTheOracle() {
		// an undotted tag is a tagged literal, and none has a reader function
		assertThatThrownBy(() -> read("#P{:a 1}")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("No reader function for tag P");
		assertThatThrownBy(() -> read("#user.P(1)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unreadable constructor form starting with \"#user.P\"");
		assertThatThrownBy(() -> read("#user.P{\"a\" 1}")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("key must be of type clojure.lang.Keyword, got \"a\"");
		assertThatThrownBy(() -> read("#user.P{:a 1 :a 2}")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Duplicate key: :a");
	}

	@Test
	void instAndUuidReadThroughTheOraclesDefaultDataReaders() {
		// the instant's milliseconds and the UUID's halves, which the lowering builds
		assertThat(printed("#inst \"2020-01-01T01:00:00+01:00\" #inst\"1970\" #uuid \"1-1-1-1-1\""))
			.isEqualTo("[(|%inst| 1577836800000), (|%inst| 0), (|%uuid| 4295032833 281474976710657)]");
		assertThat(printed("#inst \"1582-10-10\" [#uuid \"ffffffff-ffff-ffff-ffff-ffffffffffff\"]"))
			.isEqualTo("[(|%inst| -12218860800000), (|%vector| (|%uuid| -1 -1))]");
		// a branch not taken needs no reader, like any tag
		assertThat(new ClojureReader("#?(:cljs #inst \"x\" :clj 1)", "a.cljc").readAll().toString()).contains("1");
	}

	@Test
	void instAndUuidRefusalsAreTheOraclesAfterTheirForm() {
		assertThatThrownBy(() -> new ClojureReader("(def x #inst \"2021-02-29\")", "a.clj").readAll())
			.isInstanceOf(LispReadException.class)
			.hasMessage("a.clj:1:26: failed: (<= 1 days (days-in-month months (leap-year? years)))");
		assertThatThrownBy(() -> new ClojureReader("[1 #uuid \"bad\"]", "a.clj").readAll())
			.isInstanceOf(LispReadException.class)
			.hasMessage("a.clj:1:15: Invalid UUID string: bad");
		assertThatThrownBy(() -> read("#inst \"2020-1-1\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unrecognized date/time syntax: 2020-1-1");
		assertThatThrownBy(() -> read("#inst 2020")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a timestamp needs a string");
		assertThatThrownBy(() -> read("#uuid :a")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("#uuid data reader expected string");
		assertThatThrownBy(() -> read("#uuid \"1-1-1-1-8000000000000000\"")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Error at index 15 in: \"8000000000000000\"");
		// a repeated key names the oracle's toString of the earlier one
		assertThatThrownBy(() -> read("{#inst \"2020\" 1 #inst \"2020-01-01T00:00:00Z\" 2}"))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Duplicate key: Wed Jan 01 00:00:00 UTC 2020");
		assertThatThrownBy(() -> read("#{#uuid \"1-1-1-1-1\" #uuid \"00000001-0001-0001-0001-000000000001\"}"))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Duplicate key: 00000001-0001-0001-0001-000000000001");
		// a splice of one is no list
		assertThatThrownBy(() -> new ClojureReader("[#?@(:clj #inst \"2020\")]", "a.cljc").readAll())
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Spliced form list in read-cond-splicing must implement java.util.List");
	}

	@Test
	void aNamespaceMapQualifiesItsKeys() {
		assertThat(printed("#:ns{:a 1 :_/b 2 :c/d 3 e 4 \"s\" 5 nil 6}"))
			.isEqualTo("[(|%hash-map| :|ns/a| 1 :|b| 2 :|c/d| 3 |ns/e| 4 \"s\" 5 |nil| 6)]");
		assertThat(printed("#:ns {:a 1}")).isEqualTo("[(|%hash-map| :|ns/a| 1)]");
		// #:: keys are spelled auto-resolved, which the lowering resolves
		assertThat(printed("#::{:a 1 ::b 2} #::s{:a 1} #:: {:c 3}"))
			.isEqualTo("[(|%hash-map| :|:a| 1 :|:b| 2), (|%hash-map| :|:s/a| 1), (|%hash-map| :|:c| 3)]");
		assertThatThrownBy(() -> read("#: {:a 1}")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Namespaced map must specify a namespace");
		assertThatThrownBy(() -> read("#:{:a 1}")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Namespaced map must specify a valid namespace: null");
		assertThatThrownBy(() -> read("#:a/b{:a 1}")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Namespaced map must specify a valid namespace: a/b");
		assertThatThrownBy(() -> read("#:ns [1]")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Namespaced map must specify a map");
		assertThatThrownBy(() -> read("#:ns{:a}")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Namespaced map literal must contain an even number of forms");
		assertThatThrownBy(() -> read("#::{a 1}")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a symbol key of an auto-resolved namespace map is not supported: a");
	}

	@Test
	void aHashBangLineIsACommentAnywhere() {
		assertThat(printed("1 #!skipped (\n2")).isEqualTo("[1, 2]");
	}

	@Test
	void gensymSuffixReadsAsOneIdentifier() {
		assertThat(printed("x#")).isEqualTo("[|x#|]");
		assertThat(printed("`(~x ~@y s#)"))
			.isEqualTo("[(|syntax-quote| ((|unquote| |x|) (|unquote-splicing| |y|) |s#|))]");
		// a dispatch form after an identifier still dispatches
		assertThat(printed("a#'x")).isEqualTo("[|a|, (|var| |x|)]");
		assertThat(printed("a#_skip b")).isEqualTo("[|a|, |b|]");
	}

	@Test
	void aQuoteInsideASymbolIsAConstituentAndEndsANumber() {
		// oracle (clj 1.12.6, 2026-10-08): ' is a non-terminating macro character, so
		// coll' and a'b are symbols (medley, stuartsierra/dependency spell them), while
		// a number stops at it
		assertThat(printed("[a' b'' a'b 'c (f'x) :k']"))
			.isEqualTo("[(|%vector| |a'| |b''| |a'b| (|quote| |c|) (|f'x|) :|k'|)]");
		assertThat(printed("1'x")).isEqualTo("[1, (|quote| |x|)]");
	}

	@Test
	void aReaderConditionalTakesTheFirstBranchOfAFeatureItHas() {
		// oracle (clj 1.12.6, 2026-10-08, read-string {:read-cond :allow}), :rontolisp
		// answered first
		assertThat(conditional("[#?(:cljs 1 :clj 2) #?(:default 9 :clj 1) #?(:rontolisp 3 :clj 4) #?(:cljs 5)]"))
			.isEqualTo("[(|%vector| 2 9 3)]");
		assertThat(conditional("#?(:cljs 1) 7")).isEqualTo("[7]");
		assertThat(conditional("[#?(:clj 1 :cljs)] [#?(:cljs)] [#?()] [#? (:clj 2)] [#?(:clj 1 \"clj\" 1)]"))
			.isEqualTo("[(|%vector| 1), (|%vector|), (|%vector|), (|%vector| 2), (|%vector| 1)]");
		// the branches not taken read suppressed: no reader for a tag, no #= evaluation
		assertThat(conditional("[#?(:cljs #js {:a 1} :clj 2) #?(:cljs #inst \"x\" :clj 3) #?(:cljs #=(+ 1 2) :clj 4)"
				+ " #?(:cljs #my.Rec{:a 1} :clj 5)]"))
			.isEqualTo("[(|%vector| 2 3 4 5)]");
		// a #_ inside, a nested conditional, a map entry
		assertThat(conditional("[#?(:cljs 3 :clj #_ 4 5) #?(:clj #?(:cljs 3 :clj 4)) #?(:clj 1 :cljs #?(:clj 3))]"))
			.isEqualTo("[(|%vector| 5 4 1)]");
		assertThat(conditional("{:a #?(:clj 1)}")).isEqualTo("[(|%hash-map| :|a| 1)]");
	}

	@Test
	void aSplicingReaderConditionalSplicesIntoTheEnclosingList() {
		// oracle: the members go ahead of the rest of the list; under a quote, a deref,
		// a var or a discard the first is taken and the rest stays in the list; a
		// splice inside a taken branch keeps its first member only
		assertThat(conditional("[1 #?@(:clj [2 3] :cljs [4]) 5]")).isEqualTo("[(|%vector| 1 2 3 5)]");
		assertThat(conditional("{#?@(:clj [:a 1])} (a #?@(:clj []) b) [#?@(:clj (1 2))] [#?@(:clj ^:m [1 2])]"))
			.isEqualTo("[(|%hash-map| :|a| 1), (|a| |b|), (|%vector| 1 2), (|%vector| 1 2)]");
		assertThat(conditional("(a '#?@(:clj [x y]) b) (a @#?@(:clj [x y]) b) (a #_#?@(:clj [x y]) b)"))
			.isEqualTo("[(|a| (|quote| |x|) |y| |b|), (|a| (|deref| |x|) |y| |b|), (|a| |y| |b|)]");
		assertThat(conditional("'#?@(:clj [x y])")).isEqualTo("[(|quote| |x|)]");
		assertThat(conditional("[#?(:clj #?@(:clj [3 4]) :cljs 5)] [#?(#?@(:clj [:clj 1]))]"))
			.isEqualTo("[(|%vector| 3), (|%vector| 1)]");
		assertThat(conditional("#?@(:cljs [1]) 5")).isEqualTo("[5]");
		assertThatThrownBy(() -> conditional("#?@(:clj [1 2])")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Reader conditional splicing not allowed at the top level.");
		assertThatThrownBy(() -> conditional("#?@(:clj 1) 5")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Spliced form list in read-cond-splicing must implement java.util.List");
		assertThatThrownBy(() -> conditional("[#?@(:clj {:a 1})]")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Spliced form list in read-cond-splicing must implement java.util.List");
	}

	@Test
	void aReaderConditionalIsRefusedInTheOraclesWords() {
		assertThatThrownBy(() -> read("#?(:clj 1)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Conditional read not allowed");
		assertThatThrownBy(() -> new ClojureReader("#?(:clj 1)", "a.clj").readAll())
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("a.clj:1:3: Conditional read not allowed");
		assertThat(new ClojureReader("[#?(:clj 1)]", "a.cljc").readAll().get(0).print()).isEqualTo("(|%vector| 1)");
		assertThatThrownBy(() -> conditional("[#?(:clj)]")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("read-cond requires an even number of forms.");
		assertThatThrownBy(() -> conditional("[#?(:else 2)]")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Feature name :else is reserved.");
		assertThatThrownBy(() -> conditional("[#?(:none 2)]")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Feature name :none is reserved.");
		assertThatThrownBy(() -> conditional("[#?[:clj 2]]")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("read-cond body must be a list");
		assertThatThrownBy(() -> conditional("[#?(:cljs 3 #_ :clj 4)]")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Feature should be a keyword: 4");
		assertThatThrownBy(() -> conditional("[#?(\"clj\" 1)]")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Feature should be a keyword: clj");
		assertThatThrownBy(() -> conditional("[#?(:cljs 1 nil 2)]")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Feature should be a keyword: null");
		assertThatThrownBy(() -> conditional("[#?([:clj] 1)]")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Feature should be a keyword: [:clj]");
		assertThatThrownBy(() -> conditional("[#?(:cljs 1 (x) 2)]")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Feature should be a keyword: (x)");
		// a branch not taken still reads: its syntax errors are the oracle's too
		assertThatThrownBy(() -> conditional("[#?(:cljs #{1 1} :clj 2)]")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Duplicate key: 1");
		assertThatThrownBy(() -> conditional("[#?(:clj 3")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unclosed form");
	}

	private static String conditional(String source) {
		return new ClojureReader(source, null, true).readAll().stream().map(LispVal::print).toList().toString();
	}

	@Test
	void anOddMapAndAnUnclosedFormAreErrors() {
		assertThatThrownBy(() -> read("{:a 1 :b}")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("even number");
		assertThatThrownBy(() -> read("(a b")).isInstanceOf(LispReadException.class);
		assertThatThrownBy(() -> read("\"abc")).isInstanceOf(LispReadException.class);
	}

	@Test
	void aRepeatedSetElementIsAnErrorNamingTheKey() {
		assertThatThrownBy(() -> read("#{1 1}")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Duplicate key: 1");
		assertThat(read("#{1 2}")).hasSize(1);
		assertThat(read("#{}")).hasSize(1);
		// the later member is named, and a vector equals a list of the same members
		assertThatThrownBy(() -> read("#{[1] (1)}")).hasMessage("Duplicate key: (1)");
		assertThatThrownBy(() -> read("#{\"a\" \"a\"}")).hasMessage("Duplicate key: a");
	}

	@Test
	void aRepeatedMapKeyIsAnErrorNamingTheEarlierKeyAfterTheBrace() {
		assertThatThrownBy(() -> new ClojureReader("{:a 1 :a 2}", "dup.clj").readAll())
			.isInstanceOf(LispReadException.class)
			.hasMessage("dup.clj:1:12: Duplicate key: :a");
		assertThatThrownBy(() -> read("{:a 1 :b 2 :a 3}")).hasMessage("Duplicate key: :a");
		assertThat(read("{:a 1 :b 2}")).hasSize(1);
		assertThat(read("{}")).hasSize(1);
		assertThat(read("{:a 1 ::a 2}")).hasSize(1);
	}

	@Test
	void aMapKeyRepeatsWhenTheReadFormsAreEqual() {
		// the oracle's toString spells the key; = decides, not the spelling
		assertThatThrownBy(() -> read("{1 :a 1N :b}")).hasMessage("Duplicate key: 1");
		assertThatThrownBy(() -> read("{[1] :a (1) :b}")).hasMessage("Duplicate key: [1]");
		assertThatThrownBy(() -> read("{(1) :a [1] :b}")).hasMessage("Duplicate key: (1)");
		assertThatThrownBy(() -> read("{() :a [] :b}")).hasMessage("Duplicate key: ()");
		assertThatThrownBy(() -> read("{\"a\" 1 \"a\" 2}")).hasMessage("Duplicate key: a");
		assertThatThrownBy(() -> read("{nil 1 nil 2}")).hasMessage("Duplicate key: null");
		assertThatThrownBy(() -> read("{\\a 1 \\a 2}")).hasMessage("Duplicate key: a");
		assertThatThrownBy(() -> read("{(f) 1 (f) 2}")).hasMessage("Duplicate key: (f)");
		assertThatThrownBy(() -> read("{'a 1 'a 2}")).hasMessage("Duplicate key: (quote a)");
		assertThatThrownBy(() -> read("{#{1 2} 1 #{2 1} 2}")).hasMessage("Duplicate key: #{1 2}");
		assertThatThrownBy(() -> read("{{:a 1 :b 2} 1 {:b 2 :a 1} 2}")).hasMessage("Duplicate key: {:a 1, :b 2}");
		assertThatThrownBy(() -> read("{[1 [2]] 1 (1 (2)) 2}")).hasMessage("Duplicate key: [1 [2]]");
		assertThatThrownBy(() -> read("{-0.0 1 0.0 2}")).hasMessageContaining("Duplicate key:");
		assertThatThrownBy(() -> read("{##NaN 1 ##NaN 2}")).hasMessageContaining("Duplicate key:");
		assertThatThrownBy(() -> read("{^:m a 1 a 2}")).hasMessage("Duplicate key: a");
		assertThatThrownBy(() -> read("{#_x :a 1 :a 2}")).hasMessage("Duplicate key: :a");
		// distinct under =: another type, another category, a regex, a different member
		assertThat(read("{1 :a 1.0 :b}")).hasSize(1);
		assertThat(read("{\\a 1 \"a\" 2}")).hasSize(1);
		assertThat(read("{#\"a\" 1 #\"a\" 2}")).hasSize(1);
		assertThat(read("{[1] :a [2] :b {} :c #{} :d}")).hasSize(1);
		assertThat(read("{:a/b 1 :a 2 a/b 3}")).hasSize(1);
	}

}
