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
	void readRank2ArrayLiteral() {
		LispVal result = LispReader.readFromString("#2A((1 2 3) (4 5 6))");
		assertThat(result).isInstanceOf(LispArray.class);
		LispArray array = (LispArray) result;
		assertThat(array.dimensions()).containsExactly(2, 3);
		assertThat(array.aref(0, 0)).isEqualTo(new LispInteger(1));
		assertThat(array.aref(1, 2)).isEqualTo(new LispInteger(6));
		assertThat(array.print()).isEqualTo("#2A((1 2 3) (4 5 6))");
	}

	@Test
	void readRank2ArrayLiteralLowercase() {
		LispVal result = LispReader.readFromString("#2a((1 2) (3 4))");
		assertThat(((LispArray) result).print()).isEqualTo("#2A((1 2) (3 4))");
	}

	@Test
	void readRank1ArrayLiteral() {
		LispVal result = LispReader.readFromString("#1A(1 2 3)");
		assertThat(result).isInstanceOf(LispArray.class);
		LispArray array = (LispArray) result;
		assertThat(array.dimensions()).containsExactly(3);
		assertThat(array.print()).isEqualTo("#(1 2 3)");
	}

	@Test
	void readRank3ArrayLiteral() {
		LispVal result = LispReader.readFromString("#3A(((1 2) (3 4)) ((5 6) (7 8)))");
		assertThat(result).isInstanceOf(LispArray.class);
		LispArray array = (LispArray) result;
		assertThat(array.dimensions()).containsExactly(2, 2, 2);
		assertThat(array.aref(1, 0, 1)).isEqualTo(new LispInteger(6));
		assertThat(array.print()).isEqualTo("#3A(((1 2) (3 4)) ((5 6) (7 8)))");
	}

	@Test
	void readEmptyRank2ArrayLiteral() {
		LispVal result = LispReader.readFromString("#2A()");
		assertThat(result).isInstanceOf(LispArray.class);
		assertThat(((LispArray) result).dimensions()).containsExactly(0, 0);
	}

	@Test
	void readRank2ArrayLiteralWithEmptyRows() {
		LispVal result = LispReader.readFromString("#2A(() ())");
		assertThat(((LispArray) result).dimensions()).containsExactly(2, 0);
	}

	@Test
	void readRank2ArrayLiteralWithMixedElements() {
		LispVal result = LispReader.readFromString("#2A((a \"b\") (1 2.5))");
		LispArray array = (LispArray) result;
		assertThat(array.aref(0, 0)).isEqualTo(new LispSymbol("a"));
		assertThat(array.aref(0, 1)).isEqualTo(new LispString("b"));
		assertThat(array.aref(1, 0)).isEqualTo(new LispInteger(1));
		assertThat(array.aref(1, 1)).isEqualTo(new LispDouble(2.5));
	}

	@Test
	void readRaggedArrayLiteralThrows() {
		assertThatThrownBy(() -> LispReader.readFromString("#2A((1 2) (3))")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("ragged");
	}

	@Test
	void readArrayLiteralWithNonListRowThrows() {
		assertThatThrownBy(() -> LispReader.readFromString("#2A(1 2)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("nested list");
	}

	@Test
	void readRank0ArrayLiteralThrows() {
		assertThatThrownBy(() -> LispReader.readFromString("#0A(1)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("rank");
	}

	@Test
	void readUnterminatedArrayLiteralThrows() {
		assertThatThrownBy(() -> LispReader.readFromString("#2A((1 2)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unexpected end of input");
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

	// --- Nested backquote: the CLtL2/Steele algorithm fully expands every level ---

	@Test
	void readNestedBackquoteSymbol() {
		// ``x -> (list 'quote 'x), folded to (quote (quote x)); evaluating once
		// yields (quote x) = `x, matching an inner backquote whose comma survives.
		assertThat(LispReader.readFromString("``x").print()).isEqualTo("(quote (quote x))");
	}

	@Test
	void readNestedBackquoteDoubleUnquote() {
		// ``(,,a): the inner comma survives, the outer one evaluates a. Expanding
		// once builds (list 'list a) -- code that, evaluated with a, rebuilds `(,a).
		assertThat(LispReader.readFromString("``(,,a)").print()).isEqualTo("(list (quote list) a)");
	}

	@Test
	void readNestedBackquoteDoubleUnquoteSplicing() {
		// ``(,,@lst): the ,@ splices lst at the outer level, each element re-quoted.
		assertThat(LispReader.readFromString("``(,,@lst)").print()).isEqualTo("(cons (quote list) lst)");
	}

	@Test
	void readNestedBackquoteInDataPosition() {
		// The inner backquote sits in a data position, so only its ,(+ 1 2) that
		// reaches level 0 would evaluate here; ,(+ 1 2) stays at level 1 and is kept.
		assertThat(LispReader.readFromString("`(a `(b ,(+ 1 2)))").print())
			.isEqualTo("(quote (a (list (quote b) (+ 1 2))))");
	}

	@Test
	void readTripleNestedBackquote() {
		// A third level: only the innermost triple-comma reaches level 0. Peeling
		// all three levels reproduces `a`, verified against SBCL.
		assertThat(LispReader.readFromString("```(,,,a)").print())
			.isEqualTo("(list (quote list) (quote (quote list)) a)");
	}

	// --- Dotted pairs: (a . b) reads as a cons with a non-list cdr ---

	@Test
	void readDottedPair() {
		LispVal result = LispReader.readFromString("(a . 1)");
		assertThat(result).isInstanceOf(LispCons.class);
		LispCons cons = (LispCons) result;
		assertThat(cons.car()).isEqualTo(new LispSymbol("a"));
		assertThat(cons.cdr()).isEqualTo(new LispInteger(1));
		assertThat(result.print()).isEqualTo("(a . 1)");
	}

	@Test
	void readDottedListWithMultipleElements() {
		LispVal result = LispReader.readFromString("(a b . c)");
		assertThat(result.print()).isEqualTo("(a b . c)");
	}

	@Test
	void readDottedNilTailIsProperList() {
		LispVal result = LispReader.readFromString("(a . nil)");
		assertThat(result.print()).isEqualTo("(a)");
	}

	@Test
	void readQuotedAlist() {
		LispVal result = LispReader.readFromString("'((a . 1) (b . 2))");
		assertThat(result.print()).isEqualTo("(quote ((a . 1) (b . 2)))");
	}

	@Test
	void readNestedDottedPair() {
		LispVal result = LispReader.readFromString("(a . (b . 1))");
		assertThat(result.print()).isEqualTo("(a b . 1)");
	}

	@Test
	void readDotWithoutCarFails() {
		assertThatThrownBy(() -> LispReader.readFromString("(. 1)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("before '.'");
	}

	@Test
	void readDotWithMultipleTailsFails() {
		assertThatThrownBy(() -> LispReader.readFromString("(a . 1 2)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("follows '.'");
	}

	@Test
	void readDotWithoutTailFails() {
		assertThatThrownBy(() -> LispReader.readFromString("(a .)")).isInstanceOf(LispReadException.class);
	}

	@Test
	void readLoneDotFails() {
		assertThatThrownBy(() -> LispReader.readFromString(".")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unexpected '.'");
	}

	// --- Dotted tails in backquote templates: `(a . ,b) lowers to cons chains ---

	@Test
	void readBackquoteDottedUnquoteTail() {
		assertThat(LispReader.readFromString("`(a . ,b)").print()).isEqualTo("(cons (quote a) b)");
	}

	@Test
	void readBackquoteDottedSymbolTail() {
		assertThat(LispReader.readFromString("`(a . b)").print()).isEqualTo("(cons (quote a) (quote b))");
	}

	@Test
	void readBackquoteDottedTailAfterMultipleElements() {
		assertThat(LispReader.readFromString("`(a ,b . ,c)").print()).isEqualTo("(cons (quote a) (cons b c))");
	}

	@Test
	void readBackquoteDottedPairInsideList() {
		assertThat(LispReader.readFromString("`((a . ,x) (b . 2))").print())
			.isEqualTo("(list (cons (quote a) x) (cons (quote b) 2))");
	}

	@Test
	void readBackquoteSplicingWithDottedTailFails() {
		assertThatThrownBy(() -> LispReader.readFromString("`(,@xs . ,b)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining(",@");
	}

	@Test
	void readBackquoteSplicingTailAfterDotFails() {
		assertThatThrownBy(() -> LispReader.readFromString("`(a . ,@xs)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining(",@");
	}

	@Test
	void readBlockComment() {
		List<LispVal> result = LispReader.readAllFromString("(+ 1 #| skipped |# 2)");
		assertThat(result).hasSize(1);
		assertThat(result.get(0).print()).isEqualTo("(+ 1 2)");
	}

	@Test
	void readNestedBlockComment() {
		List<LispVal> result = LispReader.readAllFromString("#| outer #| inner |# still outer |# 42");
		assertThat(result).containsExactly(new LispInteger(42));
	}

	@Test
	void readUnterminatedBlockCommentFails() {
		assertThatThrownBy(() -> LispReader.readAllFromString("#| never closed")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("block comment");
	}

	@Test
	void readFeatureConditionalPositiveMatch() {
		List<LispVal> result = LispReader.readAllFromString("#+rontolisp (print 1) (print 2)");
		assertThat(result).hasSize(2);
		assertThat(result.get(0).print()).isEqualTo("(print 1)");
	}

	@Test
	void readFeatureConditionalPositiveMiss() {
		List<LispVal> result = LispReader.readAllFromString("#+sbcl (print 1) (print 2)");
		assertThat(result).hasSize(1);
		assertThat(result.get(0).print()).isEqualTo("(print 2)");
	}

	@Test
	void readFeatureConditionalNegative() {
		List<LispVal> result = LispReader.readAllFromString("#-sbcl (print 1) #-rontolisp (print 2)");
		assertThat(result).hasSize(1);
		assertThat(result.get(0).print()).isEqualTo("(print 1)");
	}

	@Test
	void readFeatureConditionalKeywordSpelling() {
		List<LispVal> result = LispReader.readAllFromString("#+:rontolisp 1 #+:sbcl 2");
		assertThat(result).containsExactly(new LispInteger(1));
	}

	@Test
	void readFeatureConditionalPerBackend() {
		assertThat(LispReader.readAllFromString("#+rontolisp-jvm 1", Features.JVM)).containsExactly(new LispInteger(1));
		assertThat(LispReader.readAllFromString("#+rontolisp-jvm 1", Features.WASM)).isEmpty();
		assertThat(LispReader.readAllFromString("#+rontolisp-interpreter 1", Features.INTERPRETER))
			.containsExactly(new LispInteger(1));
	}

	@Test
	void readFeatureConditionalCompoundExpression() {
		assertThat(LispReader.readAllFromString("#+(or sbcl rontolisp) 1")).containsExactly(new LispInteger(1));
		assertThat(LispReader.readAllFromString("#+(and rontolisp sbcl) 1")).isEmpty();
		assertThat(LispReader.readAllFromString("#+(not sbcl) 1")).containsExactly(new LispInteger(1));
		assertThat(LispReader.readAllFromString("#+(:or :sbcl (:and :rontolisp (:not :abcl))) 1"))
			.containsExactly(new LispInteger(1));
	}

	@Test
	void readFeatureConditionalNilIdiom() {
		// #+nil is the classic "comment out one form" idiom: nil is never a feature.
		assertThat(LispReader.readAllFromString("#+nil (broken :form) 42")).containsExactly(new LispInteger(42));
	}

	@Test
	void readFeatureConditionalSkipsUnsupportedSyntax() {
		// The skipped form is not tokenized, so syntax rontolisp does not support
		// (here read-time eval and a nested conditional) must not break the read.
		List<LispVal> result = LispReader
			.readAllFromString("#+sbcl (foo #.(bar) #\\) \"str \\\" )\" #+ccl (baz) `(a ,@b)) 42");
		assertThat(result).containsExactly(new LispInteger(42));
	}

	@Test
	void readFeatureConditionalStacked() {
		assertThat(LispReader.readAllFromString("#+rontolisp #+sbcl 1 2")).containsExactly(new LispInteger(2));
		assertThat(LispReader.readAllFromString("#+rontolisp #-sbcl 1 2")).containsExactly(new LispInteger(1),
				new LispInteger(2));
	}

	@Test
	void readFeatureConditionalAtEndOfInputFails() {
		assertThatThrownBy(() -> LispReader.readAllFromString("#+sbcl")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("expected a form to skip");
		assertThatThrownBy(() -> LispReader.readAllFromString("#+")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("feature expression");
	}

	@Test
	void readFeaturesVariable() {
		// The interpreter keeps *features* a symbol (a real global variable binds it;
		// the substitution would corrupt binding positions); the compile backends
		// substitute the quoted list so a compiled program's feature set is fixed.
		LispVal result = LispReader.readFromString("*features*");
		assertThat(result.print()).isEqualTo("*features*");
		List<LispVal> jvm = LispReader.readAllFromString("*features*", Features.JVM);
		assertThat(jvm.get(0).print()).isEqualTo("(quote (:rontolisp :rontolisp-jvm))");
	}

	@Test
	void readReadEvalFails() {
		assertThatThrownBy(() -> LispReader.readAllFromString("#.(+ 1 2)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("#.");
	}

	@Test
	void readReadEvalWrappedInMarkerInTolerantMode() {
		// A readable #. datum is wrapped in a (%read-eval datum) marker for the .asd
		// consumer (AsdfSystems) to resolve against the file's defparameter bindings.
		List<LispVal> result = LispReader.readAllSkippingReadEval("#.*string-file* 42", Features.INTERPRETER);
		assertThat(result).containsExactly(LispReader.readFromString("(%read-eval *string-file*)"),
				new LispInteger(42));
	}

	@Test
	void readReadEvalUnparsableDatumSkippedInTolerantMode() {
		// A #. datum that does not parse (here an unquote outside a backquote) falls
		// back to a nil placeholder so it cannot shift the surrounding structure (e.g.
		// plist/alist pairing inside an .asd option); consumers that treat top-level
		// forms (AsdfSystems) ignore a bare nil.
		List<LispVal> result = LispReader.readAllSkippingReadEval("#.,foo 42", Features.INTERPRETER);
		assertThat(result).containsExactly(LispNil.INSTANCE, new LispInteger(42));
	}

	@Test
	void readUninternedSymbol() {
		// #:foo reads as a plain symbol whose printed name keeps the #: prefix;
		// package designators strip it (see PackageResolver/AsdfSystems).
		LispVal result = LispReader.readFromString("#:foo");
		assertThat(result).isEqualTo(new LispSymbol("#:foo"));
	}

}
