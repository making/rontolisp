package am.ik.rontolisp.reader;

import java.util.List;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LispReaderTest {

	@Test
	void readInteger() {
		LispVal result = LispReader.readFromString("42");
		assertThat(result).isEqualTo(new LispInteger(42));
	}

	@Test
	void readNegativeInteger() {
		LispVal result = LispReader.readFromString("-5");
		assertThat(result).isEqualTo(new LispInteger(-5));
	}

	@Test
	void readSymbol() {
		LispVal result = LispReader.readFromString("foo");
		assertThat(result).isEqualTo(new LispSymbol("foo"));
	}

	@Test
	void readNil() {
		LispVal result = LispReader.readFromString("nil");
		assertThat(result).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void readT() {
		LispVal result = LispReader.readFromString("t");
		assertThat(result).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void readSimpleList() {
		LispVal result = LispReader.readFromString("(+ 1 2)");
		assertThat(result).isInstanceOf(LispCons.class);
		LispCons cons = (LispCons) result;
		assertThat(cons.car()).isEqualTo(new LispSymbol("+"));
		List<LispVal> list = cons.toList();
		assertThat(list).hasSize(3);
		assertThat(list.get(1)).isEqualTo(new LispInteger(1));
		assertThat(list.get(2)).isEqualTo(new LispInteger(2));
	}

	@Test
	void readNestedList() {
		LispVal result = LispReader.readFromString("(+ (* 2 3) 4)");
		assertThat(result).isInstanceOf(LispCons.class);
		LispCons outer = (LispCons) result;
		assertThat(outer.car()).isEqualTo(new LispSymbol("+"));
		LispCons innerCdr = (LispCons) outer.cdr();
		LispVal inner = innerCdr.car();
		assertThat(inner).isInstanceOf(LispCons.class);
		LispCons innerCons = (LispCons) inner;
		assertThat(innerCons.car()).isEqualTo(new LispSymbol("*"));
	}

	@Test
	void readQuoteSugar() {
		LispVal result = LispReader.readFromString("'foo");
		assertThat(result).isInstanceOf(LispCons.class);
		LispCons cons = (LispCons) result;
		assertThat(cons.car()).isEqualTo(new LispSymbol("quote"));
		LispCons inner = (LispCons) cons.cdr();
		assertThat(inner.car()).isEqualTo(new LispSymbol("foo"));
	}

	@Test
	void readString() {
		LispVal result = LispReader.readFromString("\"hello\"");
		assertThat(result).isEqualTo(new LispString("hello"));
	}

	@Test
	void readMultipleExpressions() {
		List<LispVal> results = LispReader.readAllFromString("(+ 1 2) (* 3 4)");
		assertThat(results).hasSize(2);
	}

	@Test
	void readEmptyList() {
		LispVal result = LispReader.readFromString("()");
		assertThat(result).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void readThrowsOnUnmatchedParen() {
		assertThatThrownBy(() -> LispReader.readFromString("(+ 1")).isInstanceOf(LispReadException.class);
	}

	@Test
	void readVectorLiteral() {
		LispVal result = LispReader.readFromString("#(1 2 3)");
		assertThat(result).isInstanceOf(LispArray.class);
		LispArray array = (LispArray) result;
		assertThat(array.dimensions()).containsExactly(3);
		assertThat(array.print()).isEqualTo("#(1 2 3)");
	}

	@Test
	void readEmptyVectorLiteral() {
		LispVal result = LispReader.readFromString("#()");
		assertThat(result).isInstanceOf(LispArray.class);
		assertThat(((LispArray) result).dimensions()).containsExactly(0);
	}

	@Test
	void readVectorLiteralWithMixedElements() {
		LispVal result = LispReader.readFromString("#(a \"b\" 3)");
		assertThat(result).isInstanceOf(LispArray.class);
		LispArray array = (LispArray) result;
		assertThat(array.aref(0)).isEqualTo(new LispSymbol("a"));
		assertThat(array.aref(1)).isEqualTo(new LispString("b"));
		assertThat(array.aref(2)).isEqualTo(new LispInteger(3));
	}

	@Test
	void readDouble() {
		LispVal result = LispReader.readFromString("3.14");
		assertThat(result).isEqualTo(new LispDouble(3.14));
	}

	@Test
	void readNegativeDouble() {
		LispVal result = LispReader.readFromString("-0.5");
		assertThat(result).isEqualTo(new LispDouble(-0.5));
	}

	@Test
	void readGroupedInteger() {
		LispVal result = LispReader.readFromString("1,000");
		assertThat(result).isEqualTo(new LispInteger(1000));
	}

	@Test
	void readMultiGroupedInteger() {
		LispVal result = LispReader.readFromString("1,234,567");
		assertThat(result).isEqualTo(new LispInteger(1234567));
	}

	@Test
	void readGroupedDouble() {
		LispVal result = LispReader.readFromString("3,000.50");
		assertThat(result).isEqualTo(new LispDouble(3000.5));
	}

	// --- Backquote (quasiquote): expanded at read time into list/append/quote ---

	@Test
	void readBackquoteSymbol() {
		assertThat(LispReader.readFromString("`x").print()).isEqualTo("(quote x)");
	}

	@Test
	void readBackquoteSelfEvaluatingAtom() {
		assertThat(LispReader.readFromString("`42").print()).isEqualTo("42");
		assertThat(LispReader.readFromString("`\"s\"").print()).isEqualTo("\"s\"");
	}

	@Test
	void readBackquoteUnquoteAtTop() {
		assertThat(LispReader.readFromString("`,(+ 1 2)").print()).isEqualTo("(+ 1 2)");
	}

	@Test
	void readBackquoteList() {
		assertThat(LispReader.readFromString("`(a ,b 3)").print()).isEqualTo("(list (quote a) b 3)");
	}

	@Test
	void readBackquoteSplicing() {
		assertThat(LispReader.readFromString("`(a ,@bs c)").print())
			.isEqualTo("(append (list (quote a)) bs (list (quote c)))");
	}

	@Test
	void readBackquoteLoneSplicing() {
		assertThat(LispReader.readFromString("`(,@xs)").print()).isEqualTo("(append xs)");
	}

	@Test
	void readBackquoteNestedList() {
		assertThat(LispReader.readFromString("`(a (b ,c))").print()).isEqualTo("(list (quote a) (list (quote b) c))");
	}

	@Test
	void readBackquoteEmptyList() {
		assertThat(LispReader.readFromString("`()").print()).isEqualTo("nil");
	}

	@Test
	void readBackquoteQuoteInTemplate() {
		assertThat(LispReader.readFromString("`('a ,b)").print()).isEqualTo("(list (list (quote quote) (quote a)) b)");
	}

	@Test
	void readBackquoteWithoutWhitespaceAroundUnquote() {
		// ',' terminates a symbol, so `(a ,b) parses the same without the space.
		assertThat(LispReader.readFromString("`(a,b)").print()).isEqualTo("(list (quote a) b)");
	}

	@Test
	void readCommaOutsideBackquoteFails() {
		assertThatThrownBy(() -> LispReader.readFromString(",x")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("outside of backquote");
	}

	@Test
	void readSplicingAtTemplateTopFails() {
		assertThatThrownBy(() -> LispReader.readFromString("`,@xs")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("inside a list");
	}

	@Test
	void readNestedBackquoteFails() {
		assertThatThrownBy(() -> LispReader.readFromString("``x")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Nested backquote");
	}

}
