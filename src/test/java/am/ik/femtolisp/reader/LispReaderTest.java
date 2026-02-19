package am.ik.femtolisp.reader;

import java.util.List;

import am.ik.femtolisp.LispCons;
import am.ik.femtolisp.LispInteger;
import am.ik.femtolisp.LispNil;
import am.ik.femtolisp.LispString;
import am.ik.femtolisp.LispSymbol;
import am.ik.femtolisp.LispTrue;
import am.ik.femtolisp.LispVal;
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

}
