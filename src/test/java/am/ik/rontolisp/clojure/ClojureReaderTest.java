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
		assertThat(printed("42 -17 +5 1/2 1.5 1e3 1M 2N")).isEqualTo("[42, -17, 5, 1/2, 1.5, 1000.0, 1.0, 2]");
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
