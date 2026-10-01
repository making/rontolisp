package am.ik.rontolisp.clojure;

import java.util.List;

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
		assertThatThrownBy(() -> read("#:x")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unsupported reader form #:");
	}

	@Test
	void stringsCommentsAndCommas() {
		List<LispVal> datums = read("\"a\" ; a comment\n[a, b]");
		assertThat(datums).hasSize(2);
		assertThat(datums.get(1).print()).isEqualTo("(|%vector| |a| |b|)");
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
	void anonFnReadsAsFnOverTheAnonMarker() {
		assertThat(printed("#(* % %)")).isEqualTo("[(|fn| |%anon| * % %)]");
	}

	@Test
	void derefMetaVarAndDiscard() {
		assertThat(printed("@x")).isEqualTo("[(|deref| |x|)]");
		assertThat(printed("^:k v")).isEqualTo("[(|with-meta| |v| :|k|)]");
		assertThat(printed("#'x")).isEqualTo("[(|var| |x|)]");
		assertThat(printed("#_skip-me y")).isEqualTo("[|y|]");
		assertThat(printed("@x ^:k v #'x #_skip-me y"))
			.isEqualTo("[(|deref| |x|), (|with-meta| |v| :|k|), (|var| |x|), |y|]");
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
	}

}
