package am.ik.rontolisp.reader;

import java.util.List;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
	void readPathnameLiteralYieldsAPathnameValue() {
		// #P"..." denotes a pathname VALUE -- an instance carrying its namestring.
		// The reader builds it directly: the layout is fixed
		// (LispLayout.PATHNAME), so no registry is needed, and like a folded #S(...)
		// literal it rides quote and backquote as a self-evaluating value.
		assertThat(LispReader.readFromString("#P\"data/x.dat\""))
			.isEqualTo(new LispInstance(LispLayout.PATHNAME, new LispVal[] { new LispString("data/x.dat") }));
		assertThat(LispReader.readFromString("#p\"/abs/path\"").print()).isEqualTo("#P\"/abs/path\"");
		assertThat(LispReader.readFromString("'#P\"q\"").print()).isEqualTo("(QUOTE #P\"q\")");
	}

	@Test
	void readPathnameDispatchNotFollowedByAStringStaysASymbol() {
		// The pre-#P tokenization: only #P" is the dispatch.
		assertThat(LispReader.readFromString("#PFOO")).isEqualTo(new LispSymbol("#PFOO"));
	}

	@Test
	void readPathnameLiteralUnderAFailingFeatureConditionalIsSkippedWhole() {
		// The #+ skip must consume the dispatch AND its string, or the namestring
		// would surface as a datum of its own.
		assertThat(LispReader.readFromString("(list #+rontolisp-no-such-feature #P\"skipped\" 1)").print())
			.isEqualTo("(LIST 1)");
	}

	@Test
	void readSymbol() {
		LispVal result = LispReader.readFromString("foo");
		assertThat(result).isEqualTo(new LispSymbol("FOO"));
	}

	@Test
	void readPipeEscapedSymbol() {
		// CL multiple escape: the pipes are dropped, the case is preserved verbatim.
		assertThat(LispReader.readFromString("|noChange|")).isEqualTo(new LispSymbol("noChange"));
	}

	@Test
	void readPipeEscapedSymbolWithWhitespaceAndTerminatingChars() {
		assertThat(LispReader.readFromString("|when used|")).isEqualTo(new LispSymbol("when used"));
		assertThat(LispReader.readFromString("|a(b)'c;d|")).isEqualTo(new LispSymbol("a(b)'c;d"));
	}

	@Test
	void readPipeEscapeTogglesMidToken() {
		assertThat(LispReader.readFromString("foo|bar baz|qux")).isEqualTo(new LispSymbol("FOObar bazQUX"));
	}

	@Test
	void readPipeEscapedSymbolInsideAList() {
		LispVal result = LispReader.readFromString("(quote |when used|)");
		List<LispVal> list = ((LispCons) result).toList();
		assertThat(list).hasSize(2);
		assertThat(list.get(1)).isEqualTo(new LispSymbol("when used"));
	}

	@Test
	void readPipeEscapedSymbolWithBackslashEscapeInside() {
		assertThat(LispReader.readFromString("|a\\|b|")).isEqualTo(new LispSymbol("a|b"));
	}

	@Test
	void readUnterminatedPipeEscapeFails() {
		assertThatThrownBy(() -> LispReader.readFromString("|never closed"))
			.hasMessageContaining("Unterminated |...| symbol escape");
	}

	@Test
	void lexerErrorNamesFileAndLineColumn() {
		// An unterminated string is a lexer-level error; with a known origin file the
		// message is prefixed file:line:column and the position is exposed structurally.
		assertThatThrownBy(
				() -> LispReader.readAllFromString("(print \"never closed)", Features.INTERPRETER, "lib.lisp"))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("lib.lisp:1:")
			.hasMessageContaining("Unterminated string literal");
		LispReadException ex = catchThrowableOfType(
				() -> LispReader.readAllFromString("(print \"never closed)", Features.INTERPRETER, "lib.lisp"),
				LispReadException.class);
		assertThat(ex.location()).isNotNull();
		assertThat(ex.location().line()).isEqualTo(1);
		assertThat(ex.location().column()).isGreaterThan(1);
		assertThat(ex.location().file()).isEqualTo("lib.lisp");
	}

	@Test
	void readsTheSemiStandardAndTheUnicodeCharacterNames() {
		// The short names first: #\Vt is the one every implementation spells for
		// U+000B, and it is what a portable whitespace list is written with.
		assertThat(LispReader.readFromString("#\\Vt")).isEqualTo(new LispChar(11));
		assertThat(LispReader.readFromString("#\\vertical-tab")).isEqualTo(new LispChar(11));
		assertThat(LispReader.readFromString("#\\Bell")).isEqualTo(new LispChar(7));
		// Then the long one: a character with no short name is spelled by its UNICODE
		// NAME, with the spaces written as underscores. The table is the host JDK's and
		// source is read here on every backend, so a name that reads reads on all four.
		assertThat(LispReader.readFromString("#\\No-break_space")).isEqualTo(new LispChar(0x00A0));
		assertThat(LispReader.readFromString("#\\Ideographic_space")).isEqualTo(new LispChar(0x3000));
		assertThat(LispReader.readFromString("#\\GREEK_SMALL_LETTER_ALPHA")).isEqualTo(new LispChar(0x03B1));
		// A name that is neither still names itself in the error.
		assertThatThrownBy(() -> LispReader.readFromString("#\\Nope")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unknown character name: #\\Nope");
	}

	@Test
	void readerErrorOnALaterLineReportsTheRightLine() {
		// Line/column are counted by newline, so an error on the second line names
		// line 2, not a flattened single line.
		assertThatThrownBy(() -> LispReader.readAllFromString("(foo\n  #\\Nope)", Features.INTERPRETER, "lib.lisp"))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("lib.lisp:2:3: ")
			.hasMessageContaining("Unknown character name: #\\Nope");
	}

	@Test
	void unterminatedConstructsPointAtTheirOpeningDelimiter() {
		// A construct that only fails once the input runs out (string, block comment,
		// |...| escape) must report where it OPENED: end-of-file names the last line of a
		// spliced library, which says nothing about which quote or comment is unbalanced.
		assertThatThrownBy(() -> LispReader.readAllFromString("(a)\n(print \"never closed)\n(b)\n(c)\n",
				Features.INTERPRETER, "lib.lisp"))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("lib.lisp:2:8: ")
			.hasMessageContaining("Unterminated string literal");
		assertThatThrownBy(
				() -> LispReader.readAllFromString("(a)\n#| open\n(b)\n(c)\n", Features.INTERPRETER, "lib.lisp"))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("lib.lisp:2:1: ")
			.hasMessageContaining("Unterminated block comment");
		assertThatThrownBy(
				() -> LispReader.readAllFromString("(a)\n(foo |never closed\n(b)\n", Features.INTERPRETER, "lib.lisp"))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("lib.lisp:2:6: ")
			.hasMessageContaining("Unterminated |...| symbol escape");
	}

	@Test
	void anUnclosedListPointsAtItsOpeningParen() {
		// Same for a list: the reader only notices at end of input, so it reports the '('
		// that was never closed rather than the far-away last line.
		assertThatThrownBy(
				() -> LispReader.readAllFromString("(a)\n(defun f (x)\n  (+ x 1)\n", Features.INTERPRETER, "lib.lisp"))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("lib.lisp:2:1: ")
			.hasMessageContaining("Unexpected end of input, expected ')'");
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
		assertThat(cons.car()).isEqualTo(new LispSymbol("QUOTE"));
		LispCons inner = (LispCons) cons.cdr();
		assertThat(inner.car()).isEqualTo(new LispSymbol("FOO"));
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
		assertThat(array.aref(0)).isEqualTo(new LispSymbol("A"));
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
		assertThat(array.aref(0, 0)).isEqualTo(new LispSymbol("A"));
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
	void readRank0ArrayLiteralHoldsOneDatumWithoutParens() {
		// #0A<datum>: the datum follows whole, so #0A(1) is a rank-0 array holding the
		// LIST (1) -- not a rank-0 array of the element 1.
		LispVal held = LispReader.readFromString("#0A(1)");
		assertThat(held).isInstanceOf(LispArray.class);
		assertThat(((LispArray) held).dimensions()).isEmpty();
		assertThat(((LispArray) held).aref().print()).isEqualTo("(1)");
		assertThat(held.print()).isEqualTo("#0A(1)");
		assertThat(LispReader.readFromString("#0A5").print()).isEqualTo("#0A5");
		assertThat(LispReader.readFromString("#0aNIL").print()).isEqualTo("#0ANIL");
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
		assertThat(LispReader.readFromString("`x").print()).isEqualTo("(QUOTE X)");
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
		assertThat(LispReader.readFromString("`(a ,b 3)").print()).isEqualTo("(LIST (QUOTE A) B 3)");
	}

	@Test
	void readBackquoteSplicing() {
		assertThat(LispReader.readFromString("`(a ,@bs c)").print())
			.isEqualTo("(APPEND (LIST (QUOTE A)) BS (LIST (QUOTE C)))");
	}

	@Test
	void readBackquoteLoneSplicing() {
		assertThat(LispReader.readFromString("`(,@xs)").print()).isEqualTo("(APPEND XS)");
	}

	@Test
	void readBackquoteNestedList() {
		assertThat(LispReader.readFromString("`(a (b ,c))").print()).isEqualTo("(LIST (QUOTE A) (LIST (QUOTE B) C))");
	}

	@Test
	void readBackquoteEmptyList() {
		assertThat(LispReader.readFromString("`()").print()).isEqualTo("NIL");
	}

	@Test
	void readBackquoteQuoteInTemplate() {
		assertThat(LispReader.readFromString("`('a ,b)").print()).isEqualTo("(LIST (LIST (QUOTE QUOTE) (QUOTE A)) B)");
	}

	@Test
	void readBackquoteSplicingIntoQuote() {
		// ',@xs is the template (quote ,@xs) = (cons 'quote xs); the single-element
		// splice reads back as 'x (trivia level0's `(equal ,*what* ',@args)).
		assertThat(LispReader.readFromString("`(equal ,w ',@args)").print())
			.isEqualTo("(LIST (QUOTE EQUAL) W (CONS (QUOTE QUOTE) ARGS))");
		assertThat(LispReader.readFromString("`(f #',@fns)").print())
			.isEqualTo("(LIST (QUOTE F) (CONS (QUOTE FUNCTION) FNS))");
		// The trivia site verbatim: a plain quote wrapping the backquote template.
		assertThat(LispReader.readFromString("(quote `(equal ,*what* ',@args))").print())
			.isEqualTo("(QUOTE (LIST (QUOTE EQUAL) *WHAT* (CONS (QUOTE QUOTE) ARGS)))");
	}

	@Test
	void readBackquoteWithoutWhitespaceAroundUnquote() {
		// ',' terminates a symbol, so `(a ,b) parses the same without the space.
		assertThat(LispReader.readFromString("`(a,b)").print()).isEqualTo("(LIST (QUOTE A) B)");
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
		assertThat(LispReader.readFromString("``x").print()).isEqualTo("(QUOTE (QUOTE X))");
	}

	@Test
	void readNestedBackquoteDoubleUnquote() {
		// ``(,,a): the inner comma survives, the outer one evaluates a. Expanding
		// once builds (list 'list a) -- code that, evaluated with a, rebuilds `(,a).
		assertThat(LispReader.readFromString("``(,,a)").print()).isEqualTo("(LIST (QUOTE LIST) A)");
	}

	@Test
	void readNestedBackquoteDoubleUnquoteSplicing() {
		// ``(,,@lst): the ,@ splices lst at the outer level, each element re-quoted.
		assertThat(LispReader.readFromString("``(,,@lst)").print()).isEqualTo("(CONS (QUOTE LIST) LST)");
	}

	@Test
	void readNestedBackquoteKeepsDottedPairs() {
		// A CONSTANT dotted pair in a template that also carries a nested backquote (so
		// the CLtL2 path runs instead of the optimized single-level one). The dotted
		// tail used to vanish: bq-attach-append's constant fold appended the tail as if
		// it were a proper list, and a symbol tail walked to nothing. esrap's
		// *expression-kinds* is exactly this shape -- (terminal . terminal) entries in a
		// template whose ,@ splices build inner backquotes -- so every alist lookup
		// answered nil and no grammar expression was recognized.
		assertThat(LispReader.readFromString("`((p . q) `(a))").print()).isEqualTo("(QUOTE ((P . Q) (QUOTE (A))))");
		assertThat(LispReader.readFromString("`(o (p . q) ,@(mapcar (lambda (s) `(,s)) xs))").print())
			.isEqualTo("(LIST* (QUOTE O) (QUOTE (P . Q)) (MAPCAR (LAMBDA (S) (LIST S)) XS))");
		// The same template WITHOUT a nested backquote takes the optimized path and
		// always got this right -- the two paths must agree.
		assertThat(LispReader.readFromString("`(o (p . q) ,@(mapcar #'list xs))").print())
			.isEqualTo("(APPEND (LIST (QUOTE O) (CONS (QUOTE P) (QUOTE Q))) (MAPCAR (FUNCTION LIST) XS))");
	}

	@Test
	void readNestedBackquoteInDataPosition() {
		// The inner backquote sits in a data position, so only its ,(+ 1 2) that
		// reaches level 0 would evaluate here; ,(+ 1 2) stays at level 1 and is kept.
		assertThat(LispReader.readFromString("`(a `(b ,(+ 1 2)))").print())
			.isEqualTo("(QUOTE (A (LIST (QUOTE B) (+ 1 2))))");
	}

	@Test
	void readTripleNestedBackquote() {
		// A third level: only the innermost triple-comma reaches level 0. Peeling
		// all three levels reproduces `a`, verified against SBCL.
		assertThat(LispReader.readFromString("```(,,,a)").print())
			.isEqualTo("(LIST (QUOTE LIST) (QUOTE (QUOTE LIST)) A)");
	}

	// --- Dotted pairs: (a . b) reads as a cons with a non-list cdr ---

	@Test
	void readDottedPair() {
		LispVal result = LispReader.readFromString("(a . 1)");
		assertThat(result).isInstanceOf(LispCons.class);
		LispCons cons = (LispCons) result;
		assertThat(cons.car()).isEqualTo(new LispSymbol("A"));
		assertThat(cons.cdr()).isEqualTo(new LispInteger(1));
		assertThat(result.print()).isEqualTo("(A . 1)");
	}

	@Test
	void readDottedListWithMultipleElements() {
		LispVal result = LispReader.readFromString("(a b . c)");
		assertThat(result.print()).isEqualTo("(A B . C)");
	}

	@Test
	void readDottedNilTailIsProperList() {
		LispVal result = LispReader.readFromString("(a . nil)");
		assertThat(result.print()).isEqualTo("(A)");
	}

	@Test
	void readQuotedAlist() {
		LispVal result = LispReader.readFromString("'((a . 1) (b . 2))");
		assertThat(result.print()).isEqualTo("(QUOTE ((A . 1) (B . 2)))");
	}

	@Test
	void readNestedDottedPair() {
		LispVal result = LispReader.readFromString("(a . (b . 1))");
		assertThat(result.print()).isEqualTo("(A B . 1)");
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
		assertThat(LispReader.readFromString("`(a . ,b)").print()).isEqualTo("(CONS (QUOTE A) B)");
	}

	@Test
	void readBackquoteDottedSymbolTail() {
		assertThat(LispReader.readFromString("`(a . b)").print()).isEqualTo("(CONS (QUOTE A) (QUOTE B))");
	}

	@Test
	void readBackquoteDottedTailAfterMultipleElements() {
		assertThat(LispReader.readFromString("`(a ,b . ,c)").print()).isEqualTo("(CONS (QUOTE A) (CONS B C))");
	}

	@Test
	void readBackquoteDottedPairInsideList() {
		assertThat(LispReader.readFromString("`((a . ,x) (b . 2))").print())
			.isEqualTo("(LIST (CONS (QUOTE A) X) (CONS (QUOTE B) 2))");
	}

	@Test
	void readBackquoteSplicingWithDottedUnquoteTail() {
		// `(x1 ... xn . tail) with splices is (append [x1] ... [xn] tail): the
		// dotted tail becomes the last append argument (CLHS 2.4.6.1).
		assertThat(LispReader.readFromString("`(,@xs . ,b)").print()).isEqualTo("(APPEND XS B)");
		assertThat(LispReader.readFromString("`(a ,@xs . ,b)").print()).isEqualTo("(APPEND (LIST (QUOTE A)) XS B)");
	}

	@Test
	void readBackquoteSplicingWithDottedConstantTail() {
		assertThat(LispReader.readFromString("`(,@xs . b)").print()).isEqualTo("(APPEND XS (QUOTE B))");
	}

	@Test
	void readBackquoteSplicingWithDottedTailTriviaShape() {
		// trivia level2 impl.lisp:224 verbatim shape:
		// `((,head ,@(mappend #'car pairs) . ,(cdr (last args))))
		assertThat(LispReader.readFromString("`((,head ,@(mappend #'car pairs) . ,(cdr (last args))))").print())
			.isEqualTo("(LIST (APPEND (LIST HEAD) (MAPPEND (FUNCTION CAR) PAIRS) (CDR (LAST ARGS))))");
	}

	@Test
	void readBackquoteCommaDotSplicing() {
		// ",." is ",@" with permission to destroy the spliced list (CLHS 2.4.6), so it
		// reads to the same append. iterate's expand-iterate is written entirely in it:
		// `(tagbody (progn ,.init-code) ,.(if used (list step)) ...).
		assertThat(LispReader.readFromString("`(a ,.bs c)").print())
			.isEqualTo("(APPEND (LIST (QUOTE A)) BS (LIST (QUOTE C)))");
		assertThat(LispReader.readFromString("`(progn ,.body)").print())
			.isEqualTo("(APPEND (LIST (QUOTE PROGN)) BODY)");
		assertThat(LispReader.readFromString("`(x ,.(if flag (list s)))").print())
			.isEqualTo("(APPEND (LIST (QUOTE X)) (IF FLAG (LIST S)))");
	}

	@Test
	void readBackquoteCommaDotWithDottedUnquoteTail() {
		// iterate expand-iterate:610 verbatim: `(let* ,binds ,.decls ,(if p
		// `(unwind-protect
		// ,body .,prot) body)) -- ",." beside a dotted ".," tail in one template.
		assertThat(LispReader.readFromString("`(unwind-protect ,body .,prot)").print())
			.isEqualTo("(CONS (QUOTE UNWIND-PROTECT) (CONS BODY PROT))");
		assertThat(LispReader.readFromString("`(f ,.xs .,tail)").print())
			.isEqualTo("(APPEND (LIST (QUOTE F)) XS TAIL)");
	}

	@Test
	void readBackquoteSplicingTailAfterDotFails() {
		assertThatThrownBy(() -> LispReader.readFromString("`(a . ,@xs)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining(",@");
	}

	@Test
	void readSharpL() {
		// #L is iterate's numbered-argument lambda: the arity is the highest !n the
		// body mentions, and the body is one form unless its first element is itself a
		// cons (iterate's list-of-forms?).
		assertThat(LispReader.readFromString("#L(* !1 !1)").print()).isEqualTo("(FUNCTION (LAMBDA (!1) (* !1 !1)))");
		assertThat(LispReader.readFromString("#L(list !2 !3)").print())
			.isEqualTo("(FUNCTION (LAMBDA (!1 !2 !3) (LIST !2 !3)))");
		assertThat(LispReader.readFromString("#L(f)").print()).isEqualTo("(FUNCTION (LAMBDA NIL (F)))");
		assertThat(LispReader.readFromString("#L((g !1) (h !1))").print())
			.isEqualTo("(FUNCTION (LAMBDA (!1) (G !1) (H !1)))");
		// A lambda call in head position is one form, not a list of forms.
		assertThat(LispReader.readFromString("#L((lambda (x) x) !1)").print())
			.isEqualTo("(FUNCTION (LAMBDA (!1) ((LAMBDA (X) X) !1)))");
	}

	@Test
	void readSharpLWithExplicitArity() {
		assertThat(LispReader.readFromString("#3L(list !1)").print())
			.isEqualTo("(FUNCTION (LAMBDA (!1 !2 !3) (LIST !1)))");
		assertThatThrownBy(() -> LispReader.readFromString("#1L(list !2)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("too few arguments");
		assertThatThrownBy(() -> LispReader.readFromString("#5000L(list !1)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("more than");
		// !n past the cap is a NAME, not an argument: the lambda list stays empty.
		assertThat(LispReader.readFromString("#L(list !999999999)").print())
			.isEqualTo("(FUNCTION (LAMBDA NIL (LIST !999999999)))");
	}

	@Test
	void readSharpLInsideBackquoteTemplate() {
		// iterate's unioning clause: `(... (delete-if #L(member !1 var :test ,test)
		// ...)).
		// The body carries an unquote, so the lambda is assembled by construction code
		// rather than read as a datum -- the arity still comes from the raw body.
		assertThat(LispReader.readFromString("`(delete-if #L(member !1 xs :test ,test))").print())
			.isEqualTo("(LIST (QUOTE DELETE-IF) " + "(LIST (QUOTE FUNCTION) (LIST (QUOTE LAMBDA) (QUOTE (!1)) "
					+ "(LIST (QUOTE MEMBER) (QUOTE !1) (QUOTE XS) (QUOTE :TEST) TEST))))");
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
		assertThat(result.get(0).print()).isEqualTo("(PRINT 1)");
	}

	@Test
	void readFeatureConditionalPositiveMiss() {
		List<LispVal> result = LispReader.readAllFromString("#+sbcl (print 1) (print 2)");
		assertThat(result).hasSize(1);
		assertThat(result.get(0).print()).isEqualTo("(PRINT 2)");
	}

	@Test
	void readFeatureConditionalNegative() {
		List<LispVal> result = LispReader.readAllFromString("#-sbcl (print 1) #-rontolisp (print 2)");
		assertThat(result).hasSize(1);
		assertThat(result.get(0).print()).isEqualTo("(PRINT 1)");
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
		// *features* stays a SYMBOL on every target: it is a variable, which a program
		// binds and pushes onto, and every backend seeds it as an ordinary special. The
		// compile backends used to have the reader substitute the quoted list here,
		// which put a cons where a binding needs a name.
		assertThat(LispReader.readFromString("*features*").print()).isEqualTo("*FEATURES*");
		assertThat(LispReader.readAllFromString("*features*", Features.JVM).get(0).print()).isEqualTo("*FEATURES*");
		assertThat(LispReader.readAllFromString("(let ((*features* nil)) *features*)", Features.WASM).get(0).print())
			.isEqualTo("(LET ((*FEATURES* NIL)) *FEATURES*)");
	}

	@Test
	void readOwnFeaturePushIsVisibleToTheSameSourcesConditionals() {
		// The announcement idiom: push in the header, #+ on it below. Real CL sees it
		// because load/compile-file go form at a time; here the READER makes the
		// announcement, so every backend agrees without evaluating anything.
		String source = """
				(eval-when (:compile-toplevel :load-toplevel :execute)
				  (pushnew :announced *features*))
				#+announced (yes)
				#-announced (no)
				""";
		for (Features features : List.of(Features.INTERPRETER, Features.JVM, Features.WASM)) {
			List<LispVal> forms = LispReader.readAllFromString(source, features);
			assertThat(forms).hasSize(2);
			assertThat(forms.get(1).print()).isEqualTo("(YES)");
		}
	}

	@Test
	void readOwnFeaturePushOnlyCountsALiteralPush() {
		// A push the program COMPUTES is invisible: deciding it means running the
		// program to decide how the program is read, which a compile backend cannot do
		// before the program exists (FeaturePushes).
		String computed = """
				(eval-when (:compile-toplevel :load-toplevel :execute)
				  (pushnew (intern "COMPUTED" :keyword) *features*))
				#+computed (yes)
				#-computed (no)
				""";
		List<LispVal> computedForms = LispReader.readAllFromString(computed);
		assertThat(computedForms.get(computedForms.size() - 1).print()).isEqualTo("(NO)");
		// ... and a push onto something that is not *features* is not an announcement.
		String elsewhere = "(pushnew :announced *my-list*) #+announced (yes) #-announced (no)";
		List<LispVal> elsewhereForms = LispReader.readAllFromString(elsewhere);
		assertThat(elsewhereForms.get(elsewhereForms.size() - 1).print()).isEqualTo("(NO)");
	}

	@Test
	void readOwnFeaturePushIsSeenThroughAnEarlierPushesConditional() {
		// The scan runs to a fixpoint, so a push that only becomes readable once an
		// earlier push has widened the set lands too.
		String source = """
				(pushnew :first *features*)
				#+first (pushnew :second *features*)
				#+second (both)
				#-second (only-first)
				""";
		List<LispVal> forms = LispReader.readAllFromString(source);
		assertThat(forms.get(forms.size() - 1).print()).isEqualTo("(BOTH)");
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
	void readReadEvalSingleDatumWithMarkers() {
		// The runtime read built-ins' entry point: one datum, marker mode.
		assertThat(LispReader.readFromStringWithReadEvalMarkers("#.(+ 1 2)", Features.INTERPRETER))
			.isEqualTo(LispReader.readFromString("(%read-eval (+ 1 2))"));
	}

	@Test
	void readReadEvalUnparsableDatumCarriesItsRawTextInTolerantMode() {
		// A #. datum that does not parse (here an unquote outside a backquote) becomes a
		// (%read-eval-unreadable "RAW TEXT") marker: it occupies exactly one datum, so it
		// cannot shift the surrounding structure (e.g. plist/alist pairing inside an .asd
		// option), and it carries the source text the CONSUMER needs to decide -- silent
		// in metadata, a hard error where the value would decide what gets loaded.
		List<LispVal> result = LispReader.readAllSkippingReadEval("#.,foo 42", Features.INTERPRETER);
		assertThat(result).containsExactly(LispReader.readFromString("(%read-eval-unreadable \",foo\")"),
				new LispInteger(42));
	}

	@Test
	void readUninternedSymbol() {
		// #:foo reads as a plain symbol whose printed name keeps the #: prefix;
		// package designators strip it (see PackageResolver/AsdfSystems).
		LispVal result = LispReader.readFromString("#:foo");
		assertThat(result).isEqualTo(new LispSymbol("#:FOO"));
	}

	// --- the reader's upcase premise ---

	private static Features upcase() {
		return Features.INTERPRETER;
	}

	@Test
	void readUpcaseModeUpcasesAllSymbols() {
		// Unescaped symbols upcase like CL's :upcase readtable case, with no fold: the
		// standard names, lambda-list markers and the user's own symbols all read
		// uppercase, so a lexed name is already its canonical spelling.
		List<LispVal> result = LispReader.readAllFromString("(DEFUN Foo (X &OPTIONAL y) (LIST X y))", upcase());
		assertThat(result.get(0).print()).isEqualTo("(DEFUN FOO (X &OPTIONAL Y) (LIST X Y))");
	}

	@Test
	void readUpcaseModeReadsTAndNil() {
		List<LispVal> result = LispReader.readAllFromString("(T NIL)", upcase());
		assertThat(result.get(0))
			.isEqualTo(new LispCons(LispTrue.INSTANCE, new LispCons(LispNil.INSTANCE, LispNil.INSTANCE)));
	}

	@Test
	void readUpcaseModeKeepsEscapedCharactersVerbatim() {
		// |...| protects case like CL: |mixedCase| stays verbatim, while |CAR| is the
		// standard CAR (the canonical spelling of the standard symbols is uppercase, so
		// an escaped uppercase name IS the standard symbol; |car| would be a distinct
		// lowercase symbol).
		assertThat(LispReader.readAllFromString("|mixedCase|", upcase()).get(0)).isEqualTo(new LispSymbol("mixedCase"));
		assertThat(LispReader.readAllFromString("|CAR|", upcase()).get(0)).isEqualTo(new LispSymbol("CAR"));
	}

	@Test
	void readUpcaseModeUpcasesBuiltinPackagePrefixesAndDesignators() {
		// Everything upcases with no fold: a built-in package prefix and its member, a
		// keyword designator, a data keyword and a user package prefix all keep their
		// upcased spelling self-consistently (the resolver maps the upcased nickname).
		assertThat(LispReader.readAllFromString("RL:FETCH", upcase()).get(0)).isEqualTo(new LispSymbol("RL:FETCH"));
		assertThat(LispReader.readAllFromString(":CL-USER", upcase()).get(0)).isEqualTo(new LispSymbol(":CL-USER"));
		assertThat(LispReader.readAllFromString(":ELEMENTS", upcase()).get(0)).isEqualTo(new LispSymbol(":ELEMENTS"));
		assertThat(LispReader.readAllFromString("my-pkg:frob", upcase()).get(0))
			.isEqualTo(new LispSymbol("MY-PKG:FROB"));
	}

	// -- source position literals -------------------------

	@Test
	void currentFileAndCurrentLineReadAsTheirOwnPosition() {
		List<LispVal> forms = LispReader.readAllFromString("""
				(print rontolisp:current-file)
				(print rontolisp:current-line)
				""", Features.INTERPRETER, "app.lisp");
		assertThat(forms.get(0)).isEqualTo(list(new LispSymbol("PRINT"), new LispString("app.lisp")));
		// The line of the SYMBOL, not of the form containing it.
		assertThat(forms.get(1)).isEqualTo(list(new LispSymbol("PRINT"), new LispInteger(2)));
	}

	@Test
	void currentFileIsNilWhenTheReadHasNoOriginFile() {
		// A REPL line or a runtime read-from-string: there is no file to name, and the
		// line is still meaningful within the string that was read.
		List<LispVal> forms = LispReader.readAllFromString("\n(list rontolisp:current-file rontolisp:current-line)",
				Features.INTERPRETER);
		assertThat(forms.get(0)).isEqualTo(list(new LispSymbol("LIST"), LispNil.INSTANCE, new LispInteger(2)));
	}

	@Test
	void everyQualifiedSpellingOfTheSourceLiteralsIsRecognized() {
		// rontolisp: / rontolisp:: / the rl: nickname all name the same two literals.
		assertThat(LispReader
			.readAllFromString("(rontolisp::current-line rl:current-line)", Features.INTERPRETER, "app.lisp")
			.get(0)).isEqualTo(list(new LispInteger(1), new LispInteger(1)));
	}

	@Test
	void anUnqualifiedCurrentFileStaysAnOrdinarySymbol() {
		// Reading happens before any in-package directive is interpreted, so a bare name
		// cannot be known to mean the rontolisp one -- and must not be stolen from a user
		// who defined their own.
		assertThat(LispReader.readAllFromString("current-file", Features.INTERPRETER, "app.lisp").get(0))
			.isEqualTo(new LispSymbol("CURRENT-FILE"));
		assertThat(LispReader.readAllFromString("other:current-file", Features.INTERPRETER, "app.lisp").get(0))
			.isEqualTo(new LispSymbol("OTHER:CURRENT-FILE"));
	}

	private static LispVal list(LispVal... items) {
		LispVal result = LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			result = new LispCons(items[i], result);
		}
		return result;
	}

}
