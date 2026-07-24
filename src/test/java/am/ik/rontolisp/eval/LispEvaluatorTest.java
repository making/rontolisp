package am.ik.rontolisp.eval;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LispEvaluatorTest {

	private LispVal eval(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		return evaluator.eval(LispReader.readFromString(input));
	}

	private LispVal evalMulti(String input) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	@Test
	void evalInteger() {
		assertThat(eval("(+ 1 2)")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalSubtraction() {
		assertThat(eval("(- 10 3)")).isEqualTo(new LispInteger(7));
	}

	@Test
	void evalMultiplication() {
		assertThat(eval("(* 3 4)")).isEqualTo(new LispInteger(12));
	}

	@Test
	void evalDivision() {
		assertThat(eval("(/ 10 3)")).isEqualTo(new LispRatio(BigInteger.TEN, BigInteger.valueOf(3)));
	}

	@Test
	void evalExactDivision() {
		assertThat(eval("(/ 10 2)")).isEqualTo(new LispInteger(5));
	}

	@Test
	void evalDivisionReturnsRatio() {
		assertThat(eval("(/ 1 2)")).isEqualTo(new LispRatio(BigInteger.ONE, BigInteger.TWO));
	}

	@Test
	void evalDivisionByFloat() {
		assertThat(eval("(/ 1 2.0)")).isEqualTo(new LispDouble(0.5));
	}

	@Test
	void evalExponentFloatLiteral() {
		// Common Lisp exponent-marker float literals (e/s/f/d/l) all read as a double.
		assertThat(eval("1d0")).isEqualTo(new LispDouble(1.0));
		assertThat(eval("1.5d3")).isEqualTo(new LispDouble(1500.0));
		assertThat(eval("6.02e23")).isEqualTo(new LispDouble(6.02e23));
		assertThat(eval("(* 2 1d0)")).isEqualTo(new LispDouble(2.0));
	}

	@Test
	void evalPiConstant() {
		assertThat(eval("pi")).isEqualTo(new LispDouble(Math.PI));
	}

	@Test
	void evalPiInExpression() {
		assertThat(eval("(* 2 pi)")).isEqualTo(new LispDouble(2 * Math.PI));
	}

	@Test
	void evalUnaryDivisionIsReciprocal() {
		assertThat(eval("(/ 2)")).isEqualTo(new LispRatio(BigInteger.ONE, BigInteger.TWO));
		assertThat(eval("(/ 2.0)")).isEqualTo(new LispDouble(0.5));
	}

	@Test
	void evalDivisionByZero() {
		assertThatThrownBy(() -> eval("(/ 1 0)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("Division by zero");
	}

	@Test
	void ratioLiteral() {
		assertThat(eval("1/3")).isEqualTo(new LispRatio(BigInteger.ONE, BigInteger.valueOf(3)));
	}

	@Test
	void ratioLiteralNormalizes() {
		assertThat(eval("2/4")).isEqualTo(new LispRatio(BigInteger.ONE, BigInteger.TWO));
		assertThat(eval("4/2")).isEqualTo(new LispInteger(2));
	}

	@Test
	void negativeRatioLiteral() {
		assertThat(eval("-1/3")).isEqualTo(new LispRatio(BigInteger.valueOf(-1), BigInteger.valueOf(3)));
	}

	@Test
	void ratioPrint() {
		assertThat(eval("1/3").print()).isEqualTo("1/3");
		assertThat(eval("-2/4").print()).isEqualTo("-1/2");
	}

	@Test
	void ratioAddition() {
		assertThat(eval("(+ 1/2 1/3)")).isEqualTo(new LispRatio(BigInteger.valueOf(5), BigInteger.valueOf(6)));
	}

	@Test
	void ratioAdditionDemotesToInteger() {
		assertThat(eval("(+ 1/2 1/2)")).isEqualTo(new LispInteger(1));
	}

	@Test
	void ratioMixedWithInteger() {
		assertThat(eval("(+ 1 1/2)")).isEqualTo(new LispRatio(BigInteger.valueOf(3), BigInteger.TWO));
		assertThat(eval("(* 2/3 3)")).isEqualTo(new LispInteger(2));
	}

	@Test
	void ratioMixedWithFloat() {
		assertThat(eval("(+ 1/2 0.5)")).isEqualTo(new LispDouble(1.0));
	}

	@Test
	void ratioSubtraction() {
		assertThat(eval("(- 1/2 1/3)")).isEqualTo(new LispRatio(BigInteger.ONE, BigInteger.valueOf(6)));
		assertThat(eval("(- 1/2)")).isEqualTo(new LispRatio(BigInteger.valueOf(-1), BigInteger.TWO));
	}

	@Test
	void ratioDivision() {
		assertThat(eval("(/ 1/2 1/3)")).isEqualTo(new LispRatio(BigInteger.valueOf(3), BigInteger.TWO));
	}

	@Test
	void ratioComparison() {
		assertThat(eval("(< 1/3 1/2)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(= 2/4 1/2)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(= 1/2 0.5)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(> 1/3 1/2)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void floatOfRatio() {
		assertThat(eval("(float 1/2)")).isEqualTo(new LispDouble(0.5));
	}

	@Test
	void ratioTruncateFloorCeilingRound() {
		assertThat(eval("(truncate 7/2)")).isEqualTo(new LispInteger(3));
		assertThat(eval("(truncate -7/2)")).isEqualTo(new LispInteger(-3));
		assertThat(eval("(floor 7/2)")).isEqualTo(new LispInteger(3));
		assertThat(eval("(floor -7/2)")).isEqualTo(new LispInteger(-4));
		assertThat(eval("(ceiling 7/2)")).isEqualTo(new LispInteger(4));
		assertThat(eval("(ceiling -7/2)")).isEqualTo(new LispInteger(-3));
		assertThat(eval("(round 7/2)")).isEqualTo(new LispInteger(4));
		assertThat(eval("(round 5/2)")).isEqualTo(new LispInteger(2));
		assertThat(eval("(round 1/3)")).isEqualTo(new LispInteger(0));
	}

	@Test
	void numeratorAndDenominator() {
		assertThat(eval("(numerator 3/4)")).isEqualTo(new LispInteger(3));
		assertThat(eval("(denominator 3/4)")).isEqualTo(new LispInteger(4));
		assertThat(eval("(numerator 5)")).isEqualTo(new LispInteger(5));
		assertThat(eval("(denominator 5)")).isEqualTo(new LispInteger(1));
	}

	@Test
	void rationalpPredicate() {
		assertThat(eval("(rationalp 1/2)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(rationalp 5)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(rationalp 0.5)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void ratioPredicates() {
		assertThat(eval("(numberp 1/2)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(integerp 1/2)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(plusp 1/2)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(minusp -1/2)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(zerop 1/2)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void ratioAbsMinMax() {
		assertThat(eval("(abs -1/2)")).isEqualTo(new LispRatio(BigInteger.ONE, BigInteger.TWO));
		assertThat(eval("(min 1/2 1/3)")).isEqualTo(new LispRatio(BigInteger.ONE, BigInteger.valueOf(3)));
		assertThat(eval("(max 1/2 1/3)")).isEqualTo(new LispRatio(BigInteger.ONE, BigInteger.TWO));
	}

	@Test
	void ratioOnePlusOneMinus() {
		assertThat(eval("(1+ 1/2)")).isEqualTo(new LispRatio(BigInteger.valueOf(3), BigInteger.TWO));
		assertThat(eval("(1- 1/2)")).isEqualTo(new LispRatio(BigInteger.valueOf(-1), BigInteger.TWO));
	}

	@Test
	void ratioExpt() {
		assertThat(eval("(expt 1/2 2)")).isEqualTo(new LispRatio(BigInteger.ONE, BigInteger.valueOf(4)));
		assertThat(eval("(expt 1/2 -2)")).isEqualTo(new LispInteger(4));
	}

	@Test
	void ratioSymbolFallback() {
		// "1/2x" and "1/2/3" are not ratio literals; they read as symbols.
		assertThatThrownBy(() -> eval("1/2x")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("The variable 1/2X is unbound");
	}

	@Test
	void ratioLiteralDivisionByZero() {
		assertThatThrownBy(() -> eval("1/0")).hasMessageContaining("Division by zero");
	}

	@Test
	void evalMod() {
		assertThat(eval("(mod 10 3)")).isEqualTo(new LispInteger(1));
	}

	@Test
	void evalModTakesSignOfDivisor() {
		assertThat(eval("(mod -13 4)")).isEqualTo(new LispInteger(3));
		assertThat(eval("(mod 13 -4)")).isEqualTo(new LispInteger(-3));
		assertThat(eval("(mod -13 -4)")).isEqualTo(new LispInteger(-1));
		assertThat(eval("(mod -5.5 2.0)")).isEqualTo(new LispDouble(0.5));
	}

	@Test
	void evalRemTakesSignOfDividend() {
		assertThat(eval("(rem 13 4)")).isEqualTo(new LispInteger(1));
		assertThat(eval("(rem -13 4)")).isEqualTo(new LispInteger(-1));
		assertThat(eval("(rem 13 -4)")).isEqualTo(new LispInteger(1));
		assertThat(eval("(rem -13 -4)")).isEqualTo(new LispInteger(-1));
	}

	@Test
	void evalVariadicComparison() {
		assertThat(eval("(< 1 2 3 4)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(< 1 2 2 4)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(<= 1 2 2 4)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(= 3 3 3)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(> 5 4 3 2 1)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(< 5)")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void evalVariadicMinMaxGcdLcm() {
		assertThat(eval("(min 5 2 8 1 9)")).isEqualTo(new LispInteger(1));
		assertThat(eval("(max 5 2 8 1 9)")).isEqualTo(new LispInteger(9));
		assertThat(eval("(min 1 2.0)")).isEqualTo(new LispDouble(1.0));
		assertThat(eval("(gcd 24 36 60)")).isEqualTo(new LispInteger(12));
		assertThat(eval("(lcm 2 3 4)")).isEqualTo(new LispInteger(12));
		assertThat(eval("(gcd)")).isEqualTo(new LispInteger(0));
		assertThat(eval("(lcm)")).isEqualTo(new LispInteger(1));
		assertThat(eval("(gcd -8)")).isEqualTo(new LispInteger(8));
	}

	@Test
	void evalLengthOfString() {
		assertThat(eval("(length \"hello\")")).isEqualTo(new LispInteger(5));
		assertThat(eval("(length \"\")")).isEqualTo(new LispInteger(0));
	}

	@Test
	void evalNestedArithmetic() {
		assertThat(eval("(+ (* 3 4) (- 10 5))")).isEqualTo(new LispInteger(17));
	}

	@Test
	void evalMultiArgAdd() {
		assertThat(eval("(+ 1 2 3 4)")).isEqualTo(new LispInteger(10));
	}

	@Test
	void evalIfTrue() {
		assertThat(eval("(if t 1 2)")).isEqualTo(new LispInteger(1));
	}

	@Test
	void evalIfFalse() {
		assertThat(eval("(if nil 1 2)")).isEqualTo(new LispInteger(2));
	}

	@Test
	void evalIfNoElse() {
		assertThat(eval("(if nil 1)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalQuote() {
		LispVal result = eval("(quote (1 2 3))");
		assertThat(result.print()).isEqualTo("(1 2 3)");
	}

	@Test
	void evalLet() {
		assertThat(eval("(let ((x 10) (y 20)) (+ x y))")).isEqualTo(new LispInteger(30));
	}

	@Test
	void evalMapListOverLists() {
		assertThat(eval("(map 'list #'+ '(1 2 3) '(10 20 30))").print()).isEqualTo("(11 22 33)");
	}

	@Test
	void evalMapListStopsAtShortestSequence() {
		assertThat(eval("(map 'list #'+ '(1 2 3) '(10 20))").print()).isEqualTo("(11 22)");
	}

	@Test
	void evalMapListOverString() {
		assertThat(eval("(map 'list (lambda (c) (char-code c)) \"AB\")").print()).isEqualTo("(65 66)");
	}

	@Test
	void evalMapStringOverString() {
		assertThat(eval("(map 'string #'char-upcase \"abc\")").print()).isEqualTo("\"ABC\"");
	}

	@Test
	void evalMapNilCallsForEffect() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		LispVal result = evaluator.eval(LispReader.readFromString("(map nil #'print '(7 8 9))"));
		assertThat(result).isSameAs(LispNil.INSTANCE);
		assertThat(baos.toString()).isEqualTo("7\n8\n9\n");
	}

	@Test
	void evalMapAcceptsSymbolFunctionDesignator() {
		assertThat(eval("(map 'list '1+ '(1 2 3))").print()).isEqualTo("(2 3 4)");
	}

	@Test
	void evalMapRejectsUnsupportedResultType() {
		assertThat(eval("(map 'vector #'1+ '(1 2 3))").print()).isEqualTo("#(2 3 4)");
		assertThatThrownBy(() -> eval("(map 'hash-table #'1+ '(1 2 3))"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void evalDefunAndCall() {
		assertThat(evalMulti("(defun square (x) (* x x)) (square 5)")).isEqualTo(new LispInteger(25));
	}

	@Test
	void evalLambda() {
		assertThat(eval("((lambda (x) (* x x)) 5)")).isEqualTo(new LispInteger(25));
	}

	@Test
	void evalDefvarDefinesGlobal() {
		assertThat(evalMulti("(defvar *x* 42) *x*")).isEqualTo(new LispInteger(42));
	}

	@Test
	void evalDefvarReturnsName() {
		assertThat(eval("(defvar *x* 42)")).isEqualTo(new LispSymbol("*X*"));
	}

	@Test
	void evalDefvarIsIdempotent() {
		// The second defvar must not overwrite the already-bound variable.
		assertThat(evalMulti("(defvar *x* 1) (defvar *x* 2) *x*")).isEqualTo(new LispInteger(1));
	}

	@Test
	void evalProgn() {
		assertThat(eval("(progn 1 2 3)")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalSetq() {
		assertThat(eval("(progn (setq x 10) x)")).isEqualTo(new LispInteger(10));
	}

	@Test
	void evalRecursion() {
		assertThat(evalMulti("(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1))))) (fact 5)"))
			.isEqualTo(new LispInteger(120));
	}

	@Test
	void evalComparison() {
		assertThat(eval("(= 1 1)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(< 1 2)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(> 2 1)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(<= 1 1)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(>= 2 1)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalPrint() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(print 42)"));
		assertThat(baos.toString()).contains("42");
	}

	@Test
	void evalTimeReturnsValueAndReportsElapsed() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		LispVal result = evaluator.eval(LispReader.readFromString("(time (+ 1 2))"));
		// time returns the form's value...
		assertThat(result).isEqualTo(new LispInteger(3));
		// ...and prints the elapsed real time to standard output.
		assertThat(baos.toString()).contains("; Elapsed real time: ").contains(" ms");
	}

	@Test
	void evalPrin1() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(prin1 42)"));
		assertThat(baos.toString()).isEqualTo("42");
	}

	@Test
	void evalPrin1String() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(prin1 \"hello\")"));
		assertThat(baos.toString()).isEqualTo("\"hello\"");
	}

	@Test
	void evalPrinc() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(princ 42)"));
		assertThat(baos.toString()).isEqualTo("42");
	}

	@Test
	void evalPrincString() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(princ \"hello\")"));
		assertThat(baos.toString()).isEqualTo("hello");
	}

	@Test
	void evalPrincList() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(princ '(1 \"hello\" 3))"));
		assertThat(baos.toString()).isEqualTo("(1 hello 3)");
	}

	@Test
	void evalTerpri() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		LispVal result = evaluator.eval(LispReader.readFromString("(terpri)"));
		assertThat(baos.toString()).isEqualTo(System.lineSeparator());
		assertThat(result).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalWithOutputToStringCollectsPrintFamilyOutput() {
		LispVal result = eval("""
				(with-output-to-string (s)
				  (princ "a=" s)
				  (princ 42 s)
				  (terpri s)
				  (prin1 "q" s)
				  (write-line " end" s)
				  (write-string "tail" s))""");
		assertThat(result).isEqualTo(new LispString("a=42\n\"q\" end\ntail"));
	}

	@Test
	void evalWithOutputToStringEmptyBody() {
		assertThat(eval("(with-output-to-string (s))")).isEqualTo(new LispString(""));
	}

	@Test
	void evalWithArenaIsAPlainProgn() {
		// rontolisp:with-arena names a reclamation boundary for --no-gc; the interpreter
		// heap is garbage-collected, so it is observationally a progn.
		assertThat(eval("(rontolisp:with-arena () 1 2 (+ 1 2))")).isEqualTo(new LispInteger(3));
		assertThat(eval("(rontolisp:with-arena ())")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalWithArenaRejectsANonEmptyOptionList() {
		assertThatThrownBy(() -> eval("(rontolisp:with-arena (:size 10) 1)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("empty option list");
	}

	@Test
	void evalWithOutputToStringDoesNotTouchStandardOutput() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(with-output-to-string (s) (princ \"hidden\" s))"));
		assertThat(baos.toString()).isEmpty();
	}

	@Test
	void evalFormatStreamDestination() {
		LispVal result = eval("(with-output-to-string (s) (format s \"x=~a, y=~s~%\" 1 \"two\"))");
		assertThat(result).isEqualTo(new LispString("x=1, y=\"two\"\n"));
	}

	@Test
	void evalPrintToStringStream() {
		LispVal result = eval("(with-output-to-string (s) (print 42 s))");
		assertThat(result).isEqualTo(new LispString("42\n"));
	}

	@Test
	void evalWithInputFromStringReadsLinesAndData() {
		LispVal result = eval("""
				(with-input-from-string (s "first line
				(1 2 3)
				third")
				  (list (read-line s) (read s) (read-line s) (read-line s)))""");
		assertThat(result.print()).isEqualTo("(\"first line\" (1 2 3) \"third\" NIL)");
	}

	@Test
	void evalWriteStringToStandardOutput() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		LispVal result = evaluator.eval(LispReader.readFromString("(write-string \"no newline\")"));
		assertThat(baos.toString()).isEqualTo("no newline");
		assertThat(result).isEqualTo(new LispString("no newline"));
	}

	@Test
	void evalWriteToString() {
		assertThat(eval("(write-to-string '(a \"b\" 3))")).isEqualTo(new LispString("(A \"b\" 3)"));
	}

	@Test
	void evalPrincStreamDesignatorTAndNilGoToStandardOutput() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(princ \"a\" t)"));
		evaluator.eval(LispReader.readFromString("(princ \"b\" nil)"));
		assertThat(baos.toString()).isEqualTo("ab");
	}

	@Test
	void evalFormat() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		LispVal result = evaluator.eval(LispReader.readFromString("(format t \"Hello ~a, you are ~d!~%\" 'world 42)"));
		assertThat(baos.toString()).isEqualTo("Hello WORLD, you are 42!" + System.lineSeparator());
		assertThat(result).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalFormatPrin1Directive() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(format t \"~s and ~a\" \"str\" \"str\")"));
		assertThat(baos.toString()).isEqualTo("\"str\" and str");
	}

	@Test
	void evalFormatList() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(format t \"list=~a\" (list 1 2 3))"));
		assertThat(baos.toString()).isEqualTo("list=(1 2 3)");
	}

	@Test
	void evalFormatTildeEscape() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(format t \"100~~\")"));
		assertThat(baos.toString()).isEqualTo("100~");
	}

	@Test
	void evalFormatUppercaseDirectives() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(format t \"~A ~D ~S\" 'sym 42 \"str\")"));
		assertThat(baos.toString()).isEqualTo("SYM 42 \"str\"");
	}

	@Test
	void evalFormatNilReturnsString() {
		assertThat(eval("(format nil \"Hello ~a, you are ~d!~%\" 'world 42)"))
			.isEqualTo(new LispString("Hello WORLD, you are 42!\n"));
	}

	@Test
	void evalFormatNilPrin1Directive() {
		assertThat(eval("(format nil \"~s and ~a\" \"str\" \"str\")")).isEqualTo(new LispString("\"str\" and str"));
	}

	@Test
	void evalFormatNilList() {
		assertThat(eval("(format nil \"list=~a\" (list 1 2 3))")).isEqualTo(new LispString("list=(1 2 3)"));
	}

	@Test
	void evalFormatNilLiteralOnly() {
		assertThat(eval("(format nil \"plain ~~ text\")")).isEqualTo(new LispString("plain ~ text"));
	}

	@Test
	void evalFormatNilEmpty() {
		assertThat(eval("(format nil \"\")")).isEqualTo(new LispString(""));
	}

	@Test
	void evalFormatDollarDirective() {
		assertThat(eval("(format nil \"~$\" 3.14159)")).isEqualTo(new LispString("3.14"));
		assertThat(eval("(format nil \"~5$\" 3.14159)")).isEqualTo(new LispString("3.14159"));
		assertThat(eval("(format nil \"~v$\" 3 3.14159)")).isEqualTo(new LispString("3.142"));
		assertThat(eval("(format nil \"~#$\" 3.14159)")).isEqualTo(new LispString("3.1"));
	}

	@Test
	void evalFormatFixedDirective() {
		assertThat(eval("(format nil \"~,5f\" 3.14159)")).isEqualTo(new LispString("3.14159"));
		assertThat(eval("(format nil \"~,2f\" 3.14159)")).isEqualTo(new LispString("3.14"));
		assertThat(eval("(format nil \"~,1f\" 2.5)")).isEqualTo(new LispString("2.5"));
		assertThat(eval("(format nil \"~,0f\" 2.7)")).isEqualTo(new LispString("3"));
		assertThat(eval("(format nil \"~,2f\" -1.5)")).isEqualTo(new LispString("-1.50"));
	}

	@Test
	void evalFormatExponentialDirective() {
		assertThat(eval("(format nil \"~e\" pi)")).isEqualTo(new LispString("3.141593e+0"));
		assertThat(eval("(format nil \"~,4e\" pi)")).isEqualTo(new LispString("3.1416e+0"));
		assertThat(eval("(format nil \"~e\" 1234.5)")).isEqualTo(new LispString("1.2345e+3"));
		assertThat(eval("(format nil \"~e\" 100.0)")).isEqualTo(new LispString("1.0e+2"));
		assertThat(eval("(format nil \"~e\" 0.5)")).isEqualTo(new LispString("5.0e-1"));
		assertThat(eval("(format nil \"~e\" 0.0)")).isEqualTo(new LispString("0.0e+0"));
		assertThat(eval("(format nil \"~,2e\" 9.999)")).isEqualTo(new LispString("1.00e+1"));
		assertThat(eval("(format nil \"~e\" -1234.5)")).isEqualTo(new LispString("-1.2345e+3"));
		assertThat(eval("(format nil \"~,2e\" 0.00031415)")).isEqualTo(new LispString("3.14e-4"));
		assertThat(eval("(format nil \"~@e\" 42.0)")).isEqualTo(new LispString("+4.2e+1"));
	}

	@Test
	void evalFormatDecimalModifiers() {
		assertThat(eval("(format nil \"~d\" 1000000)")).isEqualTo(new LispString("1000000"));
		assertThat(eval("(format nil \"~:d\" 1000000)")).isEqualTo(new LispString("1,000,000"));
		assertThat(eval("(format nil \"~@d\" 1000000)")).isEqualTo(new LispString("+1000000"));
		assertThat(eval("(format nil \"~:@d\" 1000000)")).isEqualTo(new LispString("+1,000,000"));
		assertThat(eval("(format nil \"~:d\" -1234567)")).isEqualTo(new LispString("-1,234,567"));
	}

	@Test
	void evalFormatPadding() {
		assertThat(eval("(format nil \"~10a|\" \"foo\")")).isEqualTo(new LispString("foo       |"));
		assertThat(eval("(format nil \"~10@a|\" \"foo\")")).isEqualTo(new LispString("       foo|"));
		assertThat(eval("(format nil \"~5d|\" 42)")).isEqualTo(new LispString("   42|"));
		assertThat(eval("(format nil \"~5,'0d|\" 42)")).isEqualTo(new LispString("00042|"));
	}

	@Test
	void evalFormatDecimalEdges() {
		// Negative value with a minimum width pads the whole number on the left.
		assertThat(eval("(format nil \"[~6d]\" -42)")).isEqualTo(new LispString("[   -42]"));
		// A custom comma character and interval (4th param) for ~:d.
		assertThat(eval("(format nil \"~,,'.:d\" 1234567)")).isEqualTo(new LispString("1.234.567"));
		assertThat(eval("(format nil \"~,,,4:d\" 1234567)")).isEqualTo(new LispString("123,4567"));
		// Bignum grouping (interpreter/JVM; WASM is limited to i31 integers).
		assertThat(eval("(format nil \"~:d\" 100000000000000000000)"))
			.isEqualTo(new LispString("100,000,000,000,000,000,000"));
	}

	@Test
	void evalFormatRuntimeWidthAndDollarEdges() {
		// A v parameter supplies the field width at run time.
		assertThat(eval("(format nil \"[~va]\" 8 \"hi\")")).isEqualTo(new LispString("[hi      ]"));
		// ~$ width (3rd param) and pad character (4th param).
		assertThat(eval("(format nil \"[~,,8,'*$]\" 3.5)")).isEqualTo(new LispString("[****3.50]"));
		// ~$ minimum integer digits (2nd param).
		assertThat(eval("(format nil \"~,3$\" 3.14159)")).isEqualTo(new LispString("003.14"));
		// ~f width (1st param) and decimals (2nd param).
		assertThat(eval("(format nil \"[~6,2f]\" 3.1)")).isEqualTo(new LispString("[  3.10]"));
	}

	@Test
	void evalFormatColonAestheticNil() {
		assertThat(eval("(format nil \"~:a\" nil)")).isEqualTo(new LispString("()"));
		assertThat(eval("(format nil \"~a\" nil)")).isEqualTo(new LispString("NIL"));
	}

	@Test
	void evalFormatNewlineAndTildeCounts() {
		assertThat(eval("(format nil \"a~3%b\")")).isEqualTo(new LispString("a\n\n\nb"));
		assertThat(eval("(format nil \"~3~\")")).isEqualTo(new LispString("~~~"));
	}

	@Test
	void evalFormatFreshLine() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(progn (princ \"x\") (fresh-line) (fresh-line) (princ \"y\"))"));
		assertThat(baos.toString()).isEqualTo("x\ny");
	}

	@Test
	void evalFormatFreshLineDirective() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(progn (format t \"a\") (format t \"~&b~&c\"))"));
		assertThat(baos.toString()).isEqualTo("a\nb\nc");
	}

	@Test
	void evalFormatRadixDirectives() {
		assertThat(eval("(format nil \"~x\" 256)")).isEqualTo(new LispString("100"));
		assertThat(eval("(format nil \"~x\" 255)")).isEqualTo(new LispString("FF"));
		assertThat(eval("(format nil \"~o\" 256)")).isEqualTo(new LispString("400"));
		assertThat(eval("(format nil \"~b\" #x10000)")).isEqualTo(new LispString("10000000000000000"));
		assertThat(eval("(format nil \"~8r\" #x10000)")).isEqualTo(new LispString("200000"));
		assertThat(eval("(format nil \"~x\" -255)")).isEqualTo(new LispString("-FF"));
		assertThat(eval("(format nil \"~x\" 0)")).isEqualTo(new LispString("0"));
		// Prefix parameters and modifiers work like ~d: width, pad, sign, grouping.
		assertThat(eval("(format nil \"[~8x]\" 255)")).isEqualTo(new LispString("[      FF]"));
		assertThat(eval("(format nil \"[~8,'0x]\" 255)")).isEqualTo(new LispString("[000000FF]"));
		assertThat(eval("(format nil \"~@x\" 255)")).isEqualTo(new LispString("+FF"));
		assertThat(eval("(format nil \"~:b\" 1010)")).isEqualTo(new LispString("1,111,110,010"));
		assertThat(eval("(format nil \"~16r\" 255)")).isEqualTo(new LispString("FF"));
		assertThat(eval("(format nil \"~2,8,'0r\" 10)")).isEqualTo(new LispString("00001010"));
	}

	@Test
	void evalFormatCharacterDirective() {
		assertThat(eval("(format nil \"~c\" #\\a)")).isEqualTo(new LispString("a"));
		assertThat(eval("(format nil \"~@c\" #\\a)")).isEqualTo(new LispString("#\\a"));
		assertThat(eval("(format nil \"~@c\" #\\Newline)")).isEqualTo(new LispString("#\\Newline"));
		assertThat(eval("(format nil \"~:c\" #\\a)")).isEqualTo(new LispString("a"));
		assertThat(eval("(format nil \"~:c\" #\\Newline)")).isEqualTo(new LispString("Newline"));
		assertThat(eval("(format nil \"~:c\" #\\Space)")).isEqualTo(new LispString("Space"));
	}

	@Test
	void evalFormatCaseConversion() {
		assertThat(eval("(format nil \"~(~a~)\" \"FOO BAR\")")).isEqualTo(new LispString("foo bar"));
		assertThat(eval("(format nil \"~:@(~a~)\" \"foo bar\")")).isEqualTo(new LispString("FOO BAR"));
		assertThat(eval("(format nil \"~:(~a~)\" \"foo bar\")")).isEqualTo(new LispString("Foo Bar"));
		assertThat(eval("(format nil \"~@(~a~)\" \"foo BAR\")")).isEqualTo(new LispString("Foo bar"));
		// Literal text inside the group is converted too.
		assertThat(eval("(format nil \"~(X~aY~)\" 'sym)")).isEqualTo(new LispString("xsymy"));
	}

	@Test
	void evalFormatConditional() {
		assertThat(eval("(format nil \"~[foo~a~;bar~a~:;baz~a~]\" 0 100)")).isEqualTo(new LispString("foo100"));
		assertThat(eval("(format nil \"~[foo~a~;bar~a~:;baz~a~]\" 1 100)")).isEqualTo(new LispString("bar100"));
		assertThat(eval("(format nil \"~[foo~a~;bar~a~:;baz~a~]\" 10 100)")).isEqualTo(new LispString("baz100"));
		// Without a default clause, an out-of-range selector prints nothing.
		assertThat(eval("(format nil \"<~[a~;b~]>\" 5)")).isEqualTo(new LispString("<>"));
		assertThat(eval("(format nil \"~:[foo~a~;bar~a~]\" t 100)")).isEqualTo(new LispString("bar100"));
		assertThat(eval("(format nil \"~:[foo~a~;bar~a~]\" nil 100)")).isEqualTo(new LispString("foo100"));
		assertThat(eval("(format nil \"~@[foo~a~] ~a\" 100 200)")).isEqualTo(new LispString("foo100 200"));
		assertThat(eval("(format nil \"~@[foo~a~] ~a\" nil 200)")).isEqualTo(new LispString(" 200"));
	}

	@Test
	void evalFormatConditionalStaticSelectors() {
		// A literal selector picks the clause at expansion time.
		assertThat(eval("(format nil \"~1[foo~a~;bar~a~:;baz~a~]\" 100)")).isEqualTo(new LispString("bar100"));
		// ~#[ selects by the number of remaining arguments.
		assertThat(eval("(format nil \"~#[none~;bar~a~;bar~a_~a~:;bar_many~]\")")).isEqualTo(new LispString("none"));
		assertThat(eval("(format nil \"~#[none~;bar~a~;bar~a_~a~:;bar_many~]\" 10)"))
			.isEqualTo(new LispString("bar10"));
		assertThat(eval("(format nil \"~#[none~;bar~a~;bar~a_~a~:;bar_many~]\" 10 100)"))
			.isEqualTo(new LispString("bar10_100"));
		assertThat(eval("(format nil \"~#[none~;bar~a~;bar~a_~a~:;bar_many~]\" 10 100 1000)"))
			.isEqualTo(new LispString("bar_many"));
	}

	@Test
	void evalFormatIteration() {
		assertThat(eval("(format nil \"~{ ~a,~}\" '(a b c d))")).isEqualTo(new LispString(" A, B, C, D,"));
		assertThat(eval("(format nil \"~{ <~a, ~a> ~}\" '(a 1 b 2 c 3))"))
			.isEqualTo(new LispString(" <A, 1>  <B, 2>  <C, 3> "));
		assertThat(eval("(format nil \"~2{ <~a, ~a> ~}\" '(a 1 b 2 c 3))"))
			.isEqualTo(new LispString(" <A, 1>  <B, 2> "));
		assertThat(eval("(format nil \"~:{ <~a, ~a> ~}\" '((a 1) (b 2) (c 3)))"))
			.isEqualTo(new LispString(" <A, 1>  <B, 2>  <C, 3> "));
		assertThat(eval("(format nil \"~{~a~}\" nil)")).isEqualTo(new LispString(""));
	}

	@Test
	void evalFormatIterationOverRemainingArgs() {
		assertThat(eval("(format nil \"~@{ ~a,~}\" 1 2 3 4 5)")).isEqualTo(new LispString(" 1, 2, 3, 4, 5,"));
		assertThat(eval("(format nil \"~4@{ ~a,~} ~4d\" 1 2 3 4 5)")).isEqualTo(new LispString(" 1, 2, 3, 4,    5"));
		assertThat(eval("(format nil \"~:@{ <~a, ~a> ~}\" '(a 1) '(b 2) '(c 3))"))
			.isEqualTo(new LispString(" <A, 1>  <B, 2>  <C, 3> "));
	}

	@Test
	void evalFormatNestedIteration() {
		assertThat(eval("(format nil \"~{~{~a~}|~}\" '((1 2) (3)))")).isEqualTo(new LispString("12|3|"));
		// An iteration whose body holds a conditional.
		assertThat(eval("(format nil \"~{~:[n~;y~]~}\" '(t nil t))")).isEqualTo(new LispString("yny"));
	}

	@Test
	void evalFormatArgumentJump() {
		assertThat(eval("(format nil \"~a ~a ~:* ~a\" 1 2 3)")).isEqualTo(new LispString("1 2  2"));
		assertThat(eval("(format nil \"~a ~a ~2:* ~a\" 1 2 3)")).isEqualTo(new LispString("1 2  1"));
		assertThat(eval("(format nil \"~a ~* ~a\" 1 2 3)")).isEqualTo(new LispString("1  3"));
		assertThat(eval("(format nil \"~a ~2* ~a\" 1 2 3 4)")).isEqualTo(new LispString("1  4"));
	}

	@Test
	void evalFormatRuntimePadChar() {
		// A v parameter supplies the pad character at run time (a char or a string).
		assertThat(eval("(format nil \"~v,vd\" 6 (char \"abcd\" 1) 10)")).isEqualTo(new LispString("bbbb10"));
		assertThat(eval("(format nil \"~v,'0d\" 5 10)")).isEqualTo(new LispString("00010"));
	}

	@Test
	void evalFormatFixedOverflowAndScale() {
		assertThat(eval("(format nil \"[~10,4f]\" pi)")).isEqualTo(new LispString("[    3.1416]"));
		assertThat(eval("(format nil \"[~10,4,,,'0f]\" pi)")).isEqualTo(new LispString("[00003.1416]"));
		assertThat(eval("(format nil \"[~10,8,,'*,'0f]\" pi)")).isEqualTo(new LispString("[3.14159265]"));
		assertThat(eval("(format nil \"[~10,9,,'*,'0f]\" pi)")).isEqualTo(new LispString("[**********]"));
		// The scale factor (3rd param) multiplies by 10^k before printing.
		assertThat(eval("(format nil \"~,2,2f\" 3.14159)")).isEqualTo(new LispString("314.16"));
	}

	@Test
	void evalFormatExponentialParams() {
		assertThat(eval("(format nil \"[~15,5e]\" pi)")).isEqualTo(new LispString("[     3.14159e+0]"));
		assertThat(eval("(format nil \"[~15,5,3e]\" pi)")).isEqualTo(new LispString("[   3.14159e+000]"));
		assertThat(eval("(format nil \"[~15,5,3,,'*,'0e]\" pi)")).isEqualTo(new LispString("[0003.14159e+000]"));
		assertThat(eval("(format nil \"[~15,8,3,,'*,'0e]\" pi)")).isEqualTo(new LispString("[3.14159265e+000]"));
		assertThat(eval("(format nil \"[~15,9,3,,'*,'0e]\" pi)")).isEqualTo(new LispString("[***************]"));
		// The exponent-marker character (7th param) replaces the default e.
		assertThat(eval("(format nil \"~,2,,,,,'de\" 314.159)")).isEqualTo(new LispString("3.14d+2"));
	}

	@Test
	void evalFormatGeneralFloat() {
		// ~g falls back to the plain float representation in the fixed range and to
		// the ~e form outside it.
		assertThat(eval("(format nil \"~g\" 1234.5)")).isEqualTo(new LispString("1234.5"));
		assertThat(eval("(format nil \"~g\" 0.5)")).isEqualTo(new LispString("0.5"));
		assertThat(eval("(format nil \"~g\" 0.00012345)")).isEqualTo(new LispString("1.2345e-4"));
		assertThat(eval("(format nil \"~g\" 0.0)")).isEqualTo(new LispString("0.0"));
		assertThat(eval("(format nil \"~g\" -1234.5)")).isEqualTo(new LispString("-1234.5"));
	}

	@Test
	void evalFormatCompositeToStandardOutput() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(format t \"~{~a~}~[x~;y~]~(Z~)\" '(1 2) 1 )"));
		assertThat(baos.toString()).isEqualTo("12yz");
	}

	@Test
	void evalFormatConditionalUnequalConsumptionFallsBackToRuntimeRenderer() {
		// A ~[ whose clauses consume different argument counts cannot be lowered
		// statically; the call falls back to the runtime renderer (its directive
		// subset applies -- unknown directives are emitted verbatim) instead of
		// failing the compile, so a library carrying it on a cold branch still
		// compiles (cl-ppcre's print-symbol-info).
		assertThat(eval("(stringp (format nil \"~[~a~;~a ~a~]\" 0 1 2))")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void evalFormatStreamDestinationRejectsNonStream() {
		assertThatThrownBy(() -> eval("(format 'foo \"~a\" 1)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("not an output stream");
	}

	@Test
	void evalPrincToString() {
		assertThat(eval("(princ-to-string 42)")).isEqualTo(new LispString("42"));
		assertThat(eval("(princ-to-string \"abc\")")).isEqualTo(new LispString("abc"));
		assertThat(eval("(princ-to-string 'sym)")).isEqualTo(new LispString("SYM"));
		assertThat(eval("(princ-to-string (list 1 \"x\" 3))")).isEqualTo(new LispString("(1 x 3)"));
	}

	@Test
	void evalPrin1ToString() {
		assertThat(eval("(prin1-to-string 42)")).isEqualTo(new LispString("42"));
		assertThat(eval("(prin1-to-string \"abc\")")).isEqualTo(new LispString("\"abc\""));
	}

	@Test
	void evalConcatenateStrings() {
		assertThat(eval("(concatenate 'string \"foo\" \"bar\" \"baz\")")).isEqualTo(new LispString("foobarbaz"));
		assertThat(eval("(concatenate 'string)")).isEqualTo(new LispString(""));
		assertThat(eval("(concatenate 'string \"x\")")).isEqualTo(new LispString("x"));
	}

	@Test
	void evalConcatenateRejectsNonStringResultType() {
		assertThatThrownBy(() -> eval("(concatenate 'list \"a\" \"b\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("string result type");
	}

	@Test
	void evalPrincToStringAsFunctionValue() {
		assertThat(eval("(mapcar #'princ-to-string (list 1 2))"))
			.isEqualTo(new LispCons(new LispString("1"), new LispCons(new LispString("2"), LispNil.INSTANCE)));
	}

	@Test
	void evalStringUpcaseDowncase() {
		assertThat(eval("(string-upcase \"Hello, World\")")).isEqualTo(new LispString("HELLO, WORLD"));
		assertThat(eval("(string-downcase \"Hello, World\")")).isEqualTo(new LispString("hello, world"));
	}

	@Test
	void evalStringCapitalize() {
		assertThat(eval("(string-capitalize \"hello world  foo\")")).isEqualTo(new LispString("Hello World  Foo"));
	}

	@Test
	void evalSubseq() {
		assertThat(eval("(subseq \"hello world\" 6)")).isEqualTo(new LispString("world"));
		assertThat(eval("(subseq \"hello world\" 0 5)")).isEqualTo(new LispString("hello"));
	}

	@Test
	void evalSubseqList() {
		assertThat(eval("(subseq '(1 2 3 4 5) 1 3)").print()).isEqualTo("(2 3)");
		assertThat(eval("(subseq '(1 2 3 4 5) 2)").print()).isEqualTo("(3 4 5)");
		assertThat(eval("(subseq '(a b c) 0)").print()).isEqualTo("(A B C)");
		assertThat(eval("(subseq '(1 2 3) 3)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(subseq '() 0)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalMakeString() {
		assertThat(eval("(make-string 3 :initial-element #\\x)")).isEqualTo(new LispString("xxx"));
		assertThat(eval("(length (make-string 5))")).isEqualTo(new LispInteger(5));
		assertThat(eval("(make-string 0 :initial-element #\\a)")).isEqualTo(new LispString(""));
		assertThat(eval("(make-string 2 :initial-element #\\z :element-type 'character)"))
			.isEqualTo(new LispString("zz"));
		// First-class use via funcall.
		assertThat(eval("(funcall #'make-string 3)")).isEqualTo(new LispString("   "));
	}

	@Test
	void evalReplace() {
		assertThat(eval("(replace (make-string 5 :initial-element #\\a) \"XY\" :start1 1)"))
			.isEqualTo(new LispString("aXYaa"));
		assertThat(eval("(replace \"aaaaa\" \"XY\")")).isEqualTo(new LispString("XYaaa"));
		assertThat(eval("(replace \"aaaaa\" \"XYZ\" :start1 1 :end1 3)")).isEqualTo(new LispString("aXYaa"));
		assertThat(eval("(replace \"aaaaa\" \"pqXYr\" :start1 1 :start2 2 :end2 4)"))
			.isEqualTo(new LispString("aXYaa"));
		assertThat(eval("(funcall #'replace \"aaaaa\" \"XY\")")).isEqualTo(new LispString("XYaaa"));
	}

	@Test
	void evalWriteSequenceString() {
		assertThat(eval("(with-output-to-string (s) (write-sequence \"abcd\" s :start 1 :end 3))"))
			.isEqualTo(new LispString("bc"));
		assertThat(eval("(with-output-to-string (s) (write-sequence \"hello\" s))")).isEqualTo(new LispString("hello"));
	}

	@Test
	void evalCasePredicates() {
		assertThat(eval("(lower-case-p #\\a)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(lower-case-p #\\A)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(lower-case-p #\\5)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(upper-case-p #\\A)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(upper-case-p #\\a)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(mapcar #'upper-case-p '(#\\A #\\b))").print()).isEqualTo("(T NIL)");
	}

	@Test
	void evalConstantp() {
		assertThat(eval("(constantp 5)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(constantp \"str\")")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(constantp #\\a)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(constantp :key)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(constantp t)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(constantp nil)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(constantp '(quote x))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(constantp 'x)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(constantp '(+ 1 2))")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalStreamp() {
		assertThat(eval("(with-output-to-string (s) (princ (streamp s) s))")).isEqualTo(new LispString("T"));
		assertThat(eval("(streamp 5)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(streamp \"x\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(with-output-to-string (s) (check-type s stream) (write-string \"ok\" s))"))
			.isEqualTo(new LispString("ok"));
	}

	@Test
	void evalStreampAcceptsTheStandardOutputDesignator() {
		// *standard-output* is bound to the designator t, which counts as a stream so
		// it survives a library's (check-type stream stream) guard (jzon's schubfach
		// write-double handed :stream t).
		assertThat(eval("(streamp t)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(streamp *standard-output*)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(output-stream-p t)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(input-stream-p t)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(let ((s *standard-output*)) (check-type s stream) :ok)")).isEqualTo(new LispSymbol(":OK"));
	}

	@Test
	void equalpComparesArraysElementwise() {
		assertThat(eval("(equalp #(1 \"A\" (2 3)) #(1 \"a\" (2 3.0)))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(equalp #(1) #(1 2))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(equalp #(1) \"x\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(equalp (make-array '(2 2) :initial-contents '((1 2) (3 4)))"
				+ " (make-array '(2 2) :initial-contents '((1.0 2) (3 4))))"))
			.isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(equalp (make-array '(2 2) :initial-contents '((1 2) (3 4)))"
				+ " (make-array '(4) :initial-contents '(1 2 3 4)))"))
			.isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void formatIsAFirstClassFunction() {
		// jzon's condition reports run (apply #'format stream control args).
		assertThat(eval("(apply #'format nil \"x=~a y=~d\" '(5 7))")).isEqualTo(new LispString("x=5 y=7"));
		assertThat(eval("(funcall #'format nil \"~s\" \"q\")")).isEqualTo(new LispString("\"q\""));
		assertThat(eval("(with-output-to-string (s) (funcall #'format s \"v=~a\" 1))"))
			.isEqualTo(new LispString("v=1"));
	}

	@Test
	void evalStringEquality() {
		assertThat(eval("(string= \"abc\" \"abc\")")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(string= \"abc\" \"abd\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(string-equal \"ABC\" \"abc\")")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void evalStringOrderingPredicates() {
		// The mismatch index (a generalized boolean), not t/nil: the index into string1
		// of the first differing character, or end1 when the substrings are equal.
		assertThat(eval("(string< \"aaaa\" \"aaab\")").print()).isEqualTo("3");
		assertThat(eval("(string< \"abc\" \"abc\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(string> \"abcd\" \"abc\")").print()).isEqualTo("3");
		assertThat(eval("(string> \"abc\" \"abd\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(string<= \"abc\" \"abc\")").print()).isEqualTo("3");
		assertThat(eval("(string<= \"abd\" \"abc\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(string>= \"aaaaa\" \"aaaa\")").print()).isEqualTo("4");
		assertThat(eval("(string>= \"abc\" \"abd\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(string/= \"abc\" \"abd\")").print()).isEqualTo("2");
		assertThat(eval("(string/= \"abc\" \"abc\")")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalStringCaseInsensitiveOrderingPredicates() {
		assertThat(eval("(string-lessp \"ABC\" \"abd\")").print()).isEqualTo("2");
		assertThat(eval("(string-lessp \"abc\" \"ABC\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(string-greaterp \"ABD\" \"abc\")").print()).isEqualTo("2");
		assertThat(eval("(string-not-greaterp \"Abcde\" \"abcdE\")").print()).isEqualTo("5");
		assertThat(eval("(string-not-lessp \"Abcde\" \"abcdE\")").print()).isEqualTo("5");
		assertThat(eval("(string-not-equal \"AAAA\" \"aaaA\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(string-not-equal \"AAAB\" \"aaaa\")").print()).isEqualTo("3");
	}

	@Test
	void evalStringComparisonBoundingIndices() {
		// The returned index is absolute in string1, not relative to :start1.
		assertThat(eval("(string-lessp \"012AAAA789\" \"01aaab6\" :start1 3 :end1 7 :start2 2 :end2 6)").print())
			.isEqualTo("6");
		assertThat(eval("(string< \"xabc\" \"abd\" :start1 1)").print()).isEqualTo("3");
		assertThat(eval("(string= \"together\" \"frog\" :start1 1 :end1 3 :start2 2)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(string-equal \"TOGETHER\" \"frog\" :start1 1 :end1 3 :start2 2)"))
			.isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(funcall #'string= \"xabc\" \"abc\" :start1 1)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(apply #'string-equal \"XABC\" \"abc\" '(:start1 1))")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void evalStringComparisonAcceptsDesignators() {
		// 'abc is the symbol ABC (the reader upcases), so it sorts before "abd" at 0.
		assertThat(eval("(string< 'abc \"abd\")").print()).isEqualTo("0");
		assertThat(eval("(string> \"b\" #\\a)").print()).isEqualTo("0");
	}

	@Test
	void evalStringTrim() {
		assertThat(eval("(string-trim \" xy\" \"xyhelloyx \")")).isEqualTo(new LispString("hello"));
		assertThat(eval("(string-left-trim \"x\" \"xxhello\")")).isEqualTo(new LispString("hello"));
		assertThat(eval("(string-right-trim \"x\" \"helloxx\")")).isEqualTo(new LispString("hello"));
	}

	@Test
	void evalStringUpcaseAsFunctionValue() {
		assertThat(eval("(mapcar #'string-upcase (list \"ab\" \"cd\"))"))
			.isEqualTo(new LispCons(new LispString("AB"), new LispCons(new LispString("CD"), LispNil.INSTANCE)));
	}

	@Test
	void evalFormatNonLiteralControlString() {
		// A computed (non-literal) control string is rendered at runtime by the fallback
		// renderer (used by cl-who's escape-string) rather than being an error.
		assertThat(eval("(let ((c \"~a-~a\")) (format nil c 1 2))")).isEqualTo(new LispString("1-2"));
		assertThat(eval("(let ((c \"&#x~x;\")) (format nil c 233))")).isEqualTo(new LispString("&#xe9;"));
	}

	@Test
	void evalFormatNotEnoughArguments() {
		assertThatThrownBy(() -> eval("(format t \"~a ~a\" 1)")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not enough arguments");
	}

	@Test
	void evalFormatUnsupportedDirectiveFallsBackToRuntimeRenderer() {
		// Same fallback as the uneven-~[ case: the runtime renderer emits an unknown
		// directive verbatim rather than failing the whole compile.
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(format t \"~<~>\" 65)"));
		assertThat(baos.toString()).isEqualTo("~<~>");
	}

	@Test
	void evalSetqLambdaAndCall() {
		assertThat(evalMulti("(setq square (lambda (x) (* x x))) (funcall square 5)")).isEqualTo(new LispInteger(25));
	}

	@Test
	void evalNullPredicate() {
		assertThat(eval("(null nil)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(null 1)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalListCarCdr() {
		assertThat(eval("(car (list 1 2 3))")).isEqualTo(new LispInteger(1));
		assertThat(eval("(car (cdr (list 1 2 3)))")).isEqualTo(new LispInteger(2));
	}

	@Test
	void evalCarCdrOfNil() {
		assertThat(eval("(car nil)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(cdr nil)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(car '())")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(cdr '())")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalCons() {
		assertThat(eval("(car (cons 1 2))")).isEqualTo(new LispInteger(1));
		assertThat(eval("(cdr (cons 1 2))")).isEqualTo(new LispInteger(2));
	}

	@Test
	void evalHigherOrderFunction() {
		assertThat(evalMulti(
				"(defun apply-twice (f x) (funcall f (funcall f x))) (defun square (x) (* x x)) (apply-twice #'square 3)"))
			.isEqualTo(new LispInteger(81));
	}

	@Test
	void evalLambdaAsArgument() {
		assertThat(
				evalMulti("(defun apply-twice (f x) (funcall f (funcall f x))) (apply-twice (lambda (x) (+ x 1)) 5)"))
			.isEqualTo(new LispInteger(7));
	}

	@Test
	void evalClosure() {
		assertThat(
				evalMulti("(defun make-adder (n) (lambda (x) (+ x n))) (setq add5 (make-adder 5)) (funcall add5 10)"))
			.isEqualTo(new LispInteger(15));
	}

	@Test
	void evalClosureMutation() {
		assertThat(evalMulti(
				"(defun make-counter () (let ((n 0)) (lambda () (setq n (+ n 1)) n))) (setq c (make-counter)) (funcall c) (funcall c) (funcall c)"))
			.isEqualTo(new LispInteger(3));
	}

	@Test
	void evalDynamicFunctionSelection() {
		assertThat(evalMulti("(defun sq (x) (* x x)) (setq f (if t #'sq (lambda (x) x))) (funcall f 5)"))
			.isEqualTo(new LispInteger(25));
	}

	@Test
	void evalFuncall() {
		assertThat(eval("(funcall (lambda (x) (* x x)) 5)")).isEqualTo(new LispInteger(25));
	}

	@Test
	void evalFunctionInList() {
		assertThat(evalMulti("(defun sq (x) (* x x)) (funcall (car (list #'sq)) 5)")).isEqualTo(new LispInteger(25));
	}

	@Test
	void evalTypePredicates() {
		// atom
		assertThat(eval("(atom 1)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(atom '(1 2))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(atom nil)")).isSameAs(LispTrue.INSTANCE);
		// numberp
		assertThat(eval("(numberp 42)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(numberp 3.14)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(numberp \"hello\")")).isSameAs(LispNil.INSTANCE);
		// integerp
		assertThat(eval("(integerp 42)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(integerp 3.14)")).isSameAs(LispNil.INSTANCE);
		// floatp
		assertThat(eval("(floatp 3.14)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(floatp 42)")).isSameAs(LispNil.INSTANCE);
		// symbolp
		assertThat(eval("(symbolp 'foo)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(symbolp 42)")).isSameAs(LispNil.INSTANCE);
		// stringp
		assertThat(eval("(stringp \"hello\")")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(stringp 42)")).isSameAs(LispNil.INSTANCE);
		// listp
		assertThat(eval("(listp '(1 2))")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(listp nil)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(listp 42)")).isSameAs(LispNil.INSTANCE);
		// consp
		assertThat(eval("(consp '(1 2))")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(consp nil)")).isSameAs(LispNil.INSTANCE);
		// keywordp
		assertThat(eval("(keywordp :foo)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(keywordp 'foo)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(keywordp 42)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalAppend() {
		assertThat(eval("(append)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(append '(1 2))").print()).isEqualTo("(1 2)");
		assertThat(eval("(append '(1 2) '(3 4))").print()).isEqualTo("(1 2 3 4)");
		assertThat(eval("(append nil '(1 2))").print()).isEqualTo("(1 2)");
		assertThat(eval("(append '(1 2) nil)").print()).isEqualTo("(1 2)");
		assertThat(eval("(append '(1) '(2) '(3))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(append '(1 2) 3)").print()).isEqualTo("(1 2 . 3)");
	}

	@Test
	void evalDoubleLiteral() {
		assertThat(eval("3.14")).isEqualTo(new LispDouble(3.14));
	}

	@Test
	void evalDoubleAddition() {
		assertThat(eval("(+ 1.5 2.5)")).isEqualTo(new LispDouble(4.0));
	}

	@Test
	void evalDoubleMixedAddition() {
		assertThat(eval("(+ 1 1.5)")).isEqualTo(new LispDouble(2.5));
	}

	@Test
	void evalDoubleSubtraction() {
		assertThat(eval("(- 3.5 1.5)")).isEqualTo(new LispDouble(2.0));
	}

	@Test
	void evalDoubleMultiplication() {
		assertThat(eval("(* 2.0 3.0)")).isEqualTo(new LispDouble(6.0));
	}

	@Test
	void evalDoubleDivision() {
		assertThat(eval("(/ 7.0 2.0)")).isEqualTo(new LispDouble(3.5));
	}

	@Test
	void evalDoubleMod() {
		assertThat(eval("(mod 5.5 2.0)")).isEqualTo(new LispDouble(1.5));
	}

	@Test
	void evalDoubleComparison() {
		assertThat(eval("(= 1.0 1.0)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(< 1.0 2.0)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(> 2.0 1.0)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(<= 1.5 1.5)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(>= 2.0 3.0)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalCarCdrComposition() {
		// 2-level compositions
		assertThat(eval("(cadr '(1 2 3))")).isEqualTo(new LispInteger(2));
		assertThat(eval("(cdar '((1 2) 3))").print()).isEqualTo("(2)");
		assertThat(eval("(caar '((1 2) 3))")).isEqualTo(new LispInteger(1));
		assertThat(eval("(cddr '(1 2 3))").print()).isEqualTo("(3)");
		// 3-level compositions
		assertThat(eval("(caddr '(1 2 3))")).isEqualTo(new LispInteger(3));
		assertThat(eval("(caadr '(1 (2 3) 4))")).isEqualTo(new LispInteger(2));
		// 4-level compositions
		assertThat(eval("(cadddr '(1 2 3 4))")).isEqualTo(new LispInteger(4));
		assertThat(eval("(caddar '((1 2 3) 4))")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalOnePlus() {
		assertThat(eval("(1+ 5)")).isEqualTo(new LispInteger(6));
	}

	@Test
	void evalOneMinus() {
		assertThat(eval("(1- 5)")).isEqualTo(new LispInteger(4));
	}

	@Test
	void evalZerop() {
		assertThat(eval("(zerop 0)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(zerop 1)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalPlusp() {
		assertThat(eval("(plusp 1)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(plusp 0)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(plusp -1)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalMinusp() {
		assertThat(eval("(minusp -1)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(minusp 0)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(minusp 1)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalEvenp() {
		assertThat(eval("(evenp 4)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(evenp 3)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalOddp() {
		assertThat(eval("(oddp 3)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(oddp 4)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalAbs() {
		assertThat(eval("(abs 5)")).isEqualTo(new LispInteger(5));
		assertThat(eval("(abs -5)")).isEqualTo(new LispInteger(5));
		assertThat(eval("(abs 0)")).isEqualTo(new LispInteger(0));
	}

	@Test
	void evalMin() {
		assertThat(eval("(min 3 5)")).isEqualTo(new LispInteger(3));
		assertThat(eval("(min 5 3)")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalMax() {
		assertThat(eval("(max 3 5)")).isEqualTo(new LispInteger(5));
		assertThat(eval("(max 5 3)")).isEqualTo(new LispInteger(5));
	}

	@Test
	void evalSqrt() {
		assertThat(eval("(sqrt 16)")).isEqualTo(new LispDouble(4.0));
		assertThat(eval("(sqrt 2)")).isEqualTo(new LispDouble(Math.sqrt(2)));
		assertThat(eval("(sqrt 2.0)")).isEqualTo(new LispDouble(Math.sqrt(2)));
	}

	@Test
	void evalRandom() {
		// (random 1) is always 0; the result type follows the limit and stays in range.
		assertThat(eval("(random 1)")).isEqualTo(new LispInteger(0));
		for (int trial = 0; trial < 100; trial++) {
			LispVal intResult = eval("(random 10)");
			assertThat(intResult).isInstanceOf(LispInteger.class);
			long v = ((LispInteger) intResult).value();
			assertThat(v).isGreaterThanOrEqualTo(0).isLessThan(10);
			LispVal floatResult = eval("(random 2.0)");
			assertThat(floatResult).isInstanceOf(LispDouble.class);
			double d = ((LispDouble) floatResult).value();
			assertThat(d).isGreaterThanOrEqualTo(0.0).isLessThan(2.0);
		}
		// A non-positive limit is an error.
		assertThatThrownBy(() -> eval("(random 0)")).hasMessageContaining("positive");
		assertThatThrownBy(() -> eval("(random -3)")).hasMessageContaining("positive");
	}

	@Test
	void evalTimeFunctions() {
		// get-universal-time is seconds since 1900; well past 2020 (> 3.7e9).
		LispVal ut = eval("(get-universal-time)");
		assertThat(ut).isInstanceOf(LispInteger.class);
		assertThat(((LispInteger) ut).value()).isGreaterThan(3_786_825_600L);
		assertThat(eval("(integerp (get-internal-real-time))")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(integerp (get-internal-run-time))")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalGetenv() {
		// An unset variable returns nil; a set one returns its value as a string. PATH is
		// present in every CI/dev environment.
		assertThat(eval("(getenv \"RONTOLISP_DEFINITELY_UNSET_VAR\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(stringp (getenv \"PATH\"))")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalIsqrt() {
		assertThat(eval("(isqrt 16)")).isEqualTo(new LispInteger(4));
		assertThat(eval("(isqrt 17)")).isEqualTo(new LispInteger(4));
		assertThat(eval("(isqrt 0)")).isEqualTo(new LispInteger(0));
	}

	@Test
	void evalExpt() {
		assertThat(eval("(expt 2 10)")).isEqualTo(new LispInteger(1024));
		assertThat(eval("(expt 3 0)")).isEqualTo(new LispInteger(1));
		assertThat(eval("(expt 2.0 3)")).isEqualTo(new LispDouble(8.0));
		// Integer base with large exponent promotes to BigInteger.
		assertThat(eval("(expt 2 70)")).isEqualTo(new LispBigInteger(java.math.BigInteger.valueOf(2).pow(70)));
	}

	@Test
	void evalGcdLcm() {
		assertThat(eval("(gcd 12 18)")).isEqualTo(new LispInteger(6));
		assertThat(eval("(gcd 0 5)")).isEqualTo(new LispInteger(5));
		assertThat(eval("(lcm 4 6)")).isEqualTo(new LispInteger(12));
		assertThat(eval("(lcm 0 6)")).isEqualTo(new LispInteger(0));
	}

	@Test
	void evalSignum() {
		assertThat(eval("(signum -5)")).isEqualTo(new LispInteger(-1));
		assertThat(eval("(signum 0)")).isEqualTo(new LispInteger(0));
		assertThat(eval("(signum 7)")).isEqualTo(new LispInteger(1));
		assertThat(eval("(signum 3.5)")).isEqualTo(new LispDouble(1.0));
		assertThat(eval("(signum -2.0)")).isEqualTo(new LispDouble(-1.0));
	}

	@Test
	void evalTranscendental() {
		assertThat(eval("(sin 0)")).isEqualTo(new LispDouble(0.0));
		assertThat(eval("(cos 0)")).isEqualTo(new LispDouble(1.0));
		assertThat(eval("(tan 0)")).isEqualTo(new LispDouble(0.0));
		assertThat(eval("(exp 0)")).isEqualTo(new LispDouble(1.0));
		assertThat(eval("(log 1)")).isEqualTo(new LispDouble(0.0));
		assertThat(eval("(atan 0)")).isEqualTo(new LispDouble(0.0));
		assertThat(eval("(asin 0)")).isEqualTo(new LispDouble(0.0));
		assertThat(eval("(acos 1)")).isEqualTo(new LispDouble(0.0));
		assertThat(eval("(sinh 0)")).isEqualTo(new LispDouble(0.0));
		assertThat(eval("(cosh 0)")).isEqualTo(new LispDouble(1.0));
		assertThat(eval("(tanh 0)")).isEqualTo(new LispDouble(0.0));
	}

	@Test
	void evalUnless() {
		assertThat(eval("(unless nil 42)")).isEqualTo(new LispInteger(42));
		assertThat(eval("(unless t 42)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(unless nil 1 2 3)")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalWhile() {
		assertThat(eval("(let ((n 0) (s 0)) (while (< n 5) (setq s (+ s n)) (setq n (+ n 1))) s)"))
			.isEqualTo(new LispInteger(10));
		// while returns nil
		assertThat(eval("(let ((n 0)) (while (< n 3) (setq n (+ n 1))))")).isSameAs(LispNil.INSTANCE);
		// test false on entry: body never runs
		assertThat(eval("(let ((n 0)) (while nil (setq n 99)) n)")).isEqualTo(new LispInteger(0));
	}

	@Test
	void evalDotimes() {
		assertThat(eval("(let ((s 0)) (dotimes (i 5) (setq s (+ s i))) s)")).isEqualTo(new LispInteger(10));
		// dotimes without a result form returns nil
		assertThat(eval("(dotimes (i 3))")).isSameAs(LispNil.INSTANCE);
		// optional result form, evaluated after the loop
		assertThat(eval("(let ((acc 1)) (dotimes (i 4 acc) (setq acc (* acc 2))))")).isEqualTo(new LispInteger(16));
		// zero iterations
		assertThat(eval("(let ((s 7)) (dotimes (i 0) (setq s 0)) s)")).isEqualTo(new LispInteger(7));
		// the count form is evaluated once
		assertThat(eval("(let ((s 0)) (dotimes (i (+ 1 2)) (setq s (+ s 1))) s)")).isEqualTo(new LispInteger(3));
		// nested dotimes
		assertThat(eval("(let ((s 0)) (dotimes (i 3) (dotimes (j 2) (setq s (+ s 1)))) s)"))
			.isEqualTo(new LispInteger(6));
	}

	@Test
	void evalProg1() {
		// returns the value of the first form
		assertThat(eval("(prog1 1 2 3)")).isEqualTo(new LispInteger(1));
		// a single form behaves like the identity
		assertThat(eval("(prog1 99)")).isEqualTo(new LispInteger(99));
		// the first form is saved before the body runs
		assertThat(eval("(let ((x (list 1 2 3))) (prog1 (car x) (setq x (cdr x))))")).isEqualTo(new LispInteger(1));
	}

	@Test
	void evalFirst() {
		assertThat(eval("(first '(1 2 3))")).isEqualTo(new LispInteger(1));
	}

	@Test
	void evalNth() {
		assertThat(eval("(nth 0 '(1 2 3))")).isEqualTo(new LispInteger(1));
		assertThat(eval("(nth 1 '(1 2 3))")).isEqualTo(new LispInteger(2));
		assertThat(eval("(nth 2 '(1 2 3))")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalNthcdr() {
		assertThat(eval("(nthcdr 0 '(1 2 3))")).isEqualTo(eval("'(1 2 3)"));
		assertThat(eval("(nthcdr 1 '(1 2 3))")).isEqualTo(eval("'(2 3)"));
		assertThat(eval("(nthcdr 2 '(1 2 3))")).isEqualTo(eval("'(3)"));
		assertThat(eval("(nthcdr 3 '(1 2 3))")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalSecond() {
		assertThat(eval("(second '(1 2 3))")).isEqualTo(new LispInteger(2));
	}

	@Test
	void evalCaseSingleKey() {
		assertThat(eval("(case 2 (1 'one) (2 'two) (3 'three))")).isEqualTo(new LispSymbol("TWO"));
	}

	@Test
	void evalCaseKeyList() {
		assertThat(eval("(case 3 (1 'one) ((2 3 4) 'small) (otherwise 'big))")).isEqualTo(new LispSymbol("SMALL"));
	}

	@Test
	void evalCaseOtherwise() {
		assertThat(eval("(case 99 (1 'one) ((2 3 4) 'small) (otherwise 'big))")).isEqualTo(new LispSymbol("BIG"));
	}

	@Test
	void evalCaseTDefault() {
		assertThat(eval("(case 99 (1 'one) (t 'fallback))")).isEqualTo(new LispSymbol("FALLBACK"));
	}

	@Test
	void evalCaseNoMatchReturnsNil() {
		assertThat(eval("(case 5 (1 'a) (2 'b))")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalCaseSymbolKey() {
		assertThat(eval("(case 'x (x \"matched\") (y \"other\"))")).isEqualTo(new LispString("matched"));
	}

	@Test
	void evalCaseEvaluatesKeyformOnce() {
		assertThat(evalMulti("(setq n 0) (defun bump () (setq n (+ n 1)) 2)" + " (case (bump) (1 'one) (2 'two)) n"))
			.isEqualTo(new LispInteger(1));
	}

	@Test
	void evalCaseMultipleBodyForms() {
		assertThat(evalMulti("(setq acc 0) (case 1 (1 (setq acc 10) (setq acc (+ acc 5)))) acc"))
			.isEqualTo(new LispInteger(15));
	}

	@Test
	void evalThird() {
		assertThat(eval("(third '(1 2 3))")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalFourth() {
		assertThat(eval("(fourth '(1 2 3 4))")).isEqualTo(new LispInteger(4));
	}

	@Test
	void evalRplaca() {
		assertThat(evalMulti("(setq x (cons 1 2)) (rplaca x 10) (car x)")).isEqualTo(new LispInteger(10));
	}

	@Test
	void evalRplacd() {
		assertThat(evalMulti("(setq x (cons 1 2)) (rplacd x 20) (cdr x)")).isEqualTo(new LispInteger(20));
	}

	@Test
	void evalRplacaReturnsCons() {
		assertThat(evalMulti("(setq x (cons 1 2)) (car (rplaca x 10))")).isEqualTo(new LispInteger(10));
	}

	@Test
	void evalSetfSymbol() {
		assertThat(evalMulti("(setq x 1) (setf x 2) x")).isEqualTo(new LispInteger(2));
	}

	@Test
	void evalSetfCar() {
		assertThat(evalMulti("(setq x (list 1 2 3)) (setf (car x) 10) (car x)")).isEqualTo(new LispInteger(10));
	}

	@Test
	void evalSetfCdr() {
		assertThat(evalMulti("(setq x (cons 1 2)) (setf (cdr x) 20) (cdr x)")).isEqualTo(new LispInteger(20));
	}

	@Test
	void evalSetfNth() {
		assertThat(evalMulti("(setq x (list 1 2 3)) (setf (nth 1 x) 20) (nth 1 x)")).isEqualTo(new LispInteger(20));
	}

	@Test
	void evalSetfFirst() {
		assertThat(evalMulti("(setq x (list 1 2 3)) (setf (first x) 10) (first x)")).isEqualTo(new LispInteger(10));
	}

	@Test
	void evalSetfSecond() {
		assertThat(evalMulti("(setq x (list 1 2 3)) (setf (second x) 20) (second x)")).isEqualTo(new LispInteger(20));
	}

	@Test
	void evalSetfCadr() {
		assertThat(evalMulti("(setq x (list 1 2 3)) (setf (cadr x) 20) (cadr x)")).isEqualTo(new LispInteger(20));
	}

	@Test
	void evalSetfReturnsValue() {
		assertThat(evalMulti("(setq x (list 1 2 3)) (setf (car x) 42)")).isEqualTo(new LispInteger(42));
	}

	@Test
	void evalDoublePrint() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(print 3.14)"));
		assertThat(baos.toString().trim()).isEqualTo("3.14");
	}

	// eq tests

	@Test
	void evalEqSameInteger() {
		assertThat(eval("(eq 1 1)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalEqDifferentInteger() {
		assertThat(eval("(eq 1 2)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalEqSymbols() {
		assertThat(eval("(eq 'foo 'foo)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalEqSymbolsDifferent() {
		assertThat(eval("(eq 'foo 'bar)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalEqNilNil() {
		assertThat(eval("(eq nil nil)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalEqNilAndValue() {
		assertThat(eval("(eq nil 1)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalEqConsReferenceIdentity() {
		assertThat(evalMulti("(setq x (cons 1 2)) (eq x x)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalEqConsDifferentInstances() {
		assertThat(eval("(eq (cons 1 2) (cons 1 2))")).isSameAs(LispNil.INSTANCE);
	}

	// eql tests

	@Test
	void evalEqlSameInteger() {
		assertThat(eval("(eql 3 3)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalEqlDifferentTypeNumbers() {
		assertThat(eval("(eql 3 3.0)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalEqlSameFloat() {
		assertThat(eval("(eql 3.0 3.0)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalEqlSymbols() {
		assertThat(eval("(eql 'foo 'foo)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalEqlNilNil() {
		assertThat(eval("(eql nil nil)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalEqlConsDifferentInstances() {
		assertThat(eval("(eql (cons 1 2) (cons 1 2))")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalEqlAsFunctionValue() {
		assertThat(eval("(funcall #'eql 5 5)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalEqlFloatsByValue() {
		assertThat(eval("(eql 1.5 1.5)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalEqFloatsNotEq() {
		// eq differs from eql: floats are distinct boxed objects, never eq
		assertThat(eval("(eq 1.5 1.5)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalEqlRatiosByValue() {
		assertThat(eval("(eql 1/2 1/2)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalEqRatiosNotEq() {
		assertThat(eval("(eq 1/2 1/2)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalEqIntegersStillEq() {
		assertThat(eval("(eq 3 3)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalEqualNestedLists() {
		assertThat(eval("(equal '(1 2 (3)) '(1 2 (3)))")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalEqualDifferentLists() {
		assertThat(eval("(equal '(1 2) '(1 3))")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalEqualStrings() {
		assertThat(eval("(equal \"abc\" \"abc\")")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalEqualDifferentTypeNumbers() {
		assertThat(eval("(equal 3 3.0)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalEqualFreshConsesUnlikeEql() {
		// equal compares structure recursively, where eql only compares cons by identity
		assertThat(eval("(eql (list 1 2) (list 1 2))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(equal (list 1 2) (list 1 2))")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalEqualAsFunctionValue() {
		assertThat(eval("(funcall #'equal '(1) '(1))")).isSameAs(LispTrue.INSTANCE);
	}

	// push tests

	@Test
	void evalPush() {
		assertThat(evalMulti("(setq x (list 2 3)) (push 1 x) x").print()).isEqualTo("(1 2 3)");
	}

	@Test
	void evalPushReturnsNewList() {
		assertThat(evalMulti("(setq x (list 2 3)) (push 1 x)").print()).isEqualTo("(1 2 3)");
	}

	// pop tests

	@Test
	void evalPop() {
		assertThat(evalMulti("(setq x (list 1 2 3)) (pop x)")).isEqualTo(new LispInteger(1));
	}

	@Test
	void evalPopUpdatesPlace() {
		assertThat(evalMulti("(setq x (list 1 2 3)) (pop x) x").print()).isEqualTo("(2 3)");
	}

	// remf tests

	@Test
	void evalRemfHead() {
		assertThat(evalMulti("(setq plist (list 'a 1 'b 2 'c 3)) (remf plist 'a) plist").print())
			.isEqualTo("(B 2 C 3)");
	}

	@Test
	void evalRemfMiddle() {
		assertThat(evalMulti("(setq plist (list 'a 1 'b 2 'c 3)) (remf plist 'b) plist").print())
			.isEqualTo("(A 1 C 3)");
	}

	@Test
	void evalRemfTail() {
		assertThat(evalMulti("(setq plist (list 'a 1 'b 2 'c 3)) (remf plist 'c) plist").print())
			.isEqualTo("(A 1 B 2)");
	}

	@Test
	void evalRemfNotFound() {
		assertThat(evalMulti("(setq plist (list 'a 1 'b 2)) (remf plist 'z)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalRemfEmpty() {
		assertThat(evalMulti("(setq plist nil) (remf plist 'a)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalRemfReturnsT() {
		assertThat(evalMulti("(setq plist (list 'a 1 'b 2)) (remf plist 'a)")).isSameAs(LispTrue.INSTANCE);
	}

	// keyword tests

	@Test
	void evalKeywordSelfEvaluating() {
		assertThat(eval(":foo")).isEqualTo(new LispSymbol(":FOO"));
	}

	@Test
	void evalKeywordEq() {
		assertThat(eval("(eq :foo :foo)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(eq :foo :bar)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalKeywordSymbolp() {
		assertThat(eval("(symbolp :foo)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalKeywordInList() {
		assertThat(eval("(car (list :foo :bar))")).isEqualTo(new LispSymbol(":FOO"));
	}

	@Test
	void evalReduceWithBuiltinPlus() {
		assertThat(eval("(reduce #'+ '(1 2 3 4 5) :initial-value 0)")).isEqualTo(new LispInteger(15));
	}

	@Test
	void evalReduceWithBuiltinMul() {
		assertThat(eval("(reduce #'* '(1 2 3 4 5) :initial-value 1)")).isEqualTo(new LispInteger(120));
	}

	@Test
	void evalReduceLeftFoldSubtraction() {
		// ((((1-2)-3)-4)) = -8
		assertThat(eval("(reduce #'- '(1 2 3 4))")).isEqualTo(new LispInteger(-8));
	}

	@Test
	void evalReduceFromEndSubtraction() {
		// (1-(2-(3-4))) = -2
		assertThat(eval("(reduce #'- '(1 2 3 4) :from-end t)")).isEqualTo(new LispInteger(-2));
	}

	@Test
	void evalReduceFromEndConsWithInitialValue() {
		assertThat(eval("(reduce #'cons '(1 2 3) :from-end t :initial-value nil)").print()).isEqualTo("(1 2 3)");
	}

	@Test
	void evalReduceKey() {
		assertThat(eval("(reduce #'+ '((1) (2) (3)) :key #'car)")).isEqualTo(new LispInteger(6));
	}

	@Test
	void evalReduceKeyAndFromEndAnyOrder() {
		// :key selects car; :from-end folds right: (1 . (2 . (3 . nil)))
		assertThat(eval("(reduce #'cons '((1) (2) (3)) :initial-value nil :from-end t :key #'car)").print())
			.isEqualTo("(1 2 3)");
	}

	@Test
	void evalReduceFromEndNilIsLeftFold() {
		assertThat(eval("(reduce #'- '(1 2 3 4) :from-end nil)")).isEqualTo(new LispInteger(-8));
	}

	@Test
	void evalRest() {
		assertThat(eval("(rest '(1 2 3))").print()).isEqualTo("(2 3)");
		assertThat(eval("(rest '(1))")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalSetfRestPlace() {
		assertThat(evalMulti("(setq l (list 1 2 3)) (setf (rest l) '(9)) l").print()).isEqualTo("(1 9)");
	}

	@Test
	void evalLetStar() {
		assertThat(eval("(let* ((x 2) (y (* x 3))) (+ x y))")).isEqualTo(new LispInteger(8));
	}

	@Test
	void evalDolist() {
		assertThat(evalMulti("(setq s 0) (dolist (e '(1 2 3 4)) (setq s (+ s e))) s")).isEqualTo(new LispInteger(10));
	}

	@Test
	void evalDolistResultForm() {
		assertThat(eval("(dolist (e '(1 2) 99))")).isEqualTo(new LispInteger(99));
	}

	@Test
	void evalDo() {
		// Sum 0..4 using a stepped counter and an accumulator mutated in the body.
		assertThat(eval("(do ((i 0 (+ i 1)) (s 0)) ((= i 5) s) (setq s (+ s i)))")).isEqualTo(new LispInteger(10));
	}

	@Test
	void evalDoParallelStep() {
		// Parallel step: a <- b, b <- a+b computes Fibonacci (fib 10 = 55).
		assertThat(eval("(do ((i 0 (+ i 1)) (a 0 b) (b 1 (+ a b))) ((= i 10) a))")).isEqualTo(new LispInteger(55));
	}

	@Test
	void evalDoNoResultForm() {
		assertThat(eval("(do ((i 0 (+ i 1))) ((= i 3)))")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalReturnFromDolist() {
		// return exits the dolist immediately, skipping the t result form.
		assertThat(eval("(dolist (m '(2 3 5) t) (if (= m 3) (return)))")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalReturnWithValue() {
		assertThat(eval("(dotimes (i 5 -1) (if (evenp i) (return i)))")).isEqualTo(new LispInteger(0));
	}

	@Test
	void evalReturnFromDo() {
		assertThat(eval("(do ((i 0 (+ i 1))) ((> i 100) -1) (if (= i 4) (return i)))")).isEqualTo(new LispInteger(4));
	}

	@Test
	void evalLoopNumericCollect() {
		assertThat(eval("(loop for i from 1 to 5 collect i)").print()).isEqualTo("(1 2 3 4 5)");
		assertThat(eval("(loop for i below 5 collect i)").print()).isEqualTo("(0 1 2 3 4)");
		assertThat(eval("(loop for i from 1 to 10 by 2 collect i)").print()).isEqualTo("(1 3 5 7 9)");
		assertThat(eval("(loop for i from 10 downto 7 collect i)").print()).isEqualTo("(10 9 8 7)");
		assertThat(eval("(loop for i from 5 above 2 collect i)").print()).isEqualTo("(5 4 3)");
	}

	@Test
	void evalLoopListStepping() {
		assertThat(eval("(loop for x in '(a b c) collect x)").print()).isEqualTo("(A B C)");
		assertThat(eval("(loop for x on '(1 2 3) collect x)").print()).isEqualTo("((1 2 3) (2 3) (3))");
		// Parallel for clauses terminate when the shortest runs out (indexed map).
		assertThat(eval("(loop for x in '(a b c) for i from 0 collect (list i x))").print())
			.isEqualTo("((0 A) (1 B) (2 C))");
	}

	@Test
	void evalLoopAcross() {
		assertThat(eval("(loop for c across \"hello\" collect c)").print()).isEqualTo("(#\\h #\\e #\\l #\\l #\\o)");
		assertThat(eval("(loop for c across \"hello\" count (eql c #\\l))")).isEqualTo(new LispInteger(2));
		assertThat(eval("(loop for c across \"\" collect c)").print()).isEqualTo("NIL");
		// across also walks a vector's elements.
		assertThat(eval("(loop for x across #(1 2 3 4 5) collect (* x x))").print()).isEqualTo("(1 4 9 16 25)");
		assertThat(eval("(loop for x across #(3 1 4 1 5) maximize x)")).isEqualTo(new LispInteger(5));
	}

	@Test
	void evalLoopBeingSymbols() {
		// Lite `being` package iteration: parses and iterates the empty sequence (no
		// runtime intern table), so every variant yields nil / an empty accumulation.
		assertThat(eval("(loop for s being the external-symbols of :cl collect s)").print()).isEqualTo("NIL");
		assertThat(eval("(loop for s being the symbols of :cl collect s)").print()).isEqualTo("NIL");
		assertThat(eval("(loop for s being each present-symbols in :cl-user collect s)").print()).isEqualTo("NIL");
		assertThat(eval("(loop for s being the external-symbols of :cl count s)")).isEqualTo(new LispInteger(0));
		// Embedded in the cl-who hyperdoc shape: the let+defun loads without error.
		assertThat(evalMulti("""
				(let ((alist (loop for symbol being the external-symbols of :cl-who
				                   collect (cons symbol (string-downcase symbol)))))
				  (defun hyperdoc-lookup (symbol type) (cdr (assoc symbol alist))))
				(hyperdoc-lookup 'with-html-output 'function)""").print()).isEqualTo("NIL");
	}

	@Test
	void evalSetfMultiplePairs() {
		// Multiple place/value pairs assign sequentially, like consecutive setfs.
		assertThat(evalMulti("(setq l (list 1 2 3)) (setf (car l) 9 (second l) 8) l").print()).isEqualTo("(9 8 3)");
		assertThat(evalMulti("(setf a 1 b (+ a 1)) (list a b)").print()).isEqualTo("(1 2)");
		assertThat(evalMulti("""
				(setq h (make-hash-table))
				(setf (gethash "foo" h) 10 (gethash "bar" h) 20)
				(list (gethash "foo" h) (gethash "bar" h))""").print()).isEqualTo("(10 20)");
		assertThatThrownBy(() -> eval("(setf a 1 b)")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("odd number");
	}

	@Test
	void evalLoopAccumulators() {
		assertThat(eval("(loop for i from 1 to 5 sum i)")).isEqualTo(new LispInteger(15));
		assertThat(eval("(loop for i from 1 to 10 count (evenp i))")).isEqualTo(new LispInteger(5));
		assertThat(eval("(loop for i in '(3 1 4 1 5) maximize i)")).isEqualTo(new LispInteger(5));
		assertThat(eval("(loop for i in '(3 1 4 1 5) minimize i)")).isEqualTo(new LispInteger(1));
		assertThat(eval("(loop for i from 1 to 3 append (list i i))").print()).isEqualTo("(1 1 2 2 3 3)");
		assertThat(eval("(loop for i from 1 to 3 nconc (list i i))").print()).isEqualTo("(1 1 2 2 3 3)");
	}

	@Test
	void evalLoopControlClauses() {
		assertThat(eval("(loop repeat 3 collect 'x)").print()).isEqualTo("(X X X)");
		assertThat(eval("(loop for i from 0 while (< i 4) collect i)").print()).isEqualTo("(0 1 2 3)");
		assertThat(eval("(loop for i from 0 until (>= i 4) collect i)").print()).isEqualTo("(0 1 2 3)");
		// A simple loop body repeats until an explicit return.
		assertThat(eval("(let ((i 0)) (loop (setq i (+ i 1)) (when (= i 5) (return i))))"))
			.isEqualTo(new LispInteger(5));
	}

	@Test
	void evalLoopConditionalAndAuxClauses() {
		assertThat(eval("(loop for i from 1 to 10 when (evenp i) collect i)").print()).isEqualTo("(2 4 6 8 10)");
		assertThat(eval("(loop for i from 1 to 4 if (evenp i) collect i else collect (- i))").print())
			.isEqualTo("(-1 2 -3 4)");
		assertThat(eval("(loop with a = 10 for i from 1 to 3 collect (+ a i))").print()).isEqualTo("(11 12 13)");
		assertThat(eval("(loop for x = 1 then (* x 2) for i from 1 to 5 collect x)").print()).isEqualTo("(1 2 4 8 16)");
		// into + finally with an explicit return value.
		assertThat(eval("(loop for i from 1 to 3 collect i into xs finally (return (length xs)))"))
			.isEqualTo(new LispInteger(3));
	}

	@Test
	void evalLoopPositionalWhileUntil() {
		// After body clauses (or a for that assigns its variable at the top of the
		// body), while/until fire at their textual position.
		assertThat(eval("(loop for x in '(1 2 3 9 4) while (< x 4) collect x)").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(loop for x in '(1 2 3 9 4) until (> x 3) collect x)").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(let ((n 0)) (loop for i from 1 do (setq n (+ n 1)) while (< i 3)) n)"))
			.isEqualTo(new LispInteger(3));
		assertThat(eval("(loop for i from 1 do nil until (> i 2) collect i)").print()).isEqualTo("(1 2)");
		assertThat(eval("(loop for c across \"abXc\" while (not (eql c #\\X)) collect c)").print())
			.isEqualTo("(#\\a #\\b)");
	}

	@Test
	void evalLoopThereisAlwaysNever() {
		assertThat(eval("(loop for x in '(nil nil 7 9) thereis x)")).isEqualTo(new LispInteger(7));
		assertThat(eval("(loop for x in '(nil nil) thereis x)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(loop for x in '(1 2 3) always (< x 5))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(loop for x in '(1 2 9) always (< x 5))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(loop for x in '(1 2 3) never (> x 5))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(loop for x in '(1 2 9) never (> x 5))")).isEqualTo(LispNil.INSTANCE);
		// Early termination short-circuits like return (finally is skipped); normal
		// completion runs finally.
		assertThat(eval("(let ((r nil)) (loop for x in '(9) always (< x 5) finally (setq r t)) r)"))
			.isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(let ((r nil)) (loop for x in '(1) always (< x 5) finally (setq r t)) r)"))
			.isEqualTo(LispTrue.INSTANCE);
		assertThatThrownBy(() -> eval("(loop for x in '(1) thereis x collect x)"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("always/never/thereis");
	}

	@Test
	void evalLoopAnaphoricIt() {
		assertThat(eval("(loop for x in '(1 nil 3 nil 5) when x collect it)").print()).isEqualTo("(1 3 5)");
		assertThat(eval("(loop for x in '(nil 2 nil) when x return it)")).isEqualTo(new LispInteger(2));
		assertThat(eval("(loop for x in '(1 6 3 8) when (and (evenp x) x) sum it)")).isEqualTo(new LispInteger(14));
		// A nested loop's conditionals own their it.
		assertThat(eval("(loop for x in '(1 nil) when x collect (loop for y in '(5 nil 6) when y collect it))").print())
			.isEqualTo("((5 6))");
	}

	@Test
	void evalLoopFinish() {
		assertThat(eval("(loop for i from 1 do (when (> i 3) (loop-finish)) collect i)").print()).isEqualTo("(1 2 3)");
		// Unlike return, loop-finish runs finally and yields the loop result.
		assertThat(eval(
				"(loop for i from 1 collect i into xs do (when (>= i 3) (loop-finish)) finally (return (length xs)))"))
			.isEqualTo(new LispInteger(3));
	}

	@Test
	void evalLoopSequentialDriverStepping() {
		// A later sequential for sees the in-variable already at binding time (CL
		// sequencing), and stepping stops at the first exhausted driver, so the
		// fold-left idiom works.
		assertThat(eval("(loop for x in '(1 2 3 4 5) for a = x then (+ a x) finally (return a))"))
			.isEqualTo(new LispInteger(15));
		assertThat(eval("(loop for x in '((1 2) (3 4)) for y in x collect y)").print()).isEqualTo("(1 2)");
	}

	@Test
	void evalLoopParallelForAnd() {
		// and-joined for clauses step against the previous iteration's values.
		assertThat(eval("(loop for a = 0 then b and b = 1 then (+ a b) repeat 8 collect b)").print())
			.isEqualTo("(1 1 2 3 5 8 13 21)");
		assertThat(eval("(loop for x in '(1 2 3) and y = 'init then x collect (list x y))").print())
			.isEqualTo("((1 INIT) (2 1) (3 2))");
		// and-joined with bindings are parallel: a later init sees the outer binding.
		assertThat(eval("(let ((x 5)) (loop with a = x and x = 10 repeat 1 collect (list a x)))").print())
			.isEqualTo("((5 10))");
	}

	@Test
	void evalLoopDestructuring() {
		assertThat(eval("(loop for (a b) in '((1 2) (3 4) (5 6)) collect (+ a b))").print()).isEqualTo("(3 7 11)");
		assertThat(eval("(loop for (a (b c)) in '((1 (2 3)) (4 (5 6))) collect (list a b c))").print())
			.isEqualTo("((1 2 3) (4 5 6))");
		assertThat(eval("(loop for (a b) = '(1 2) then (list b (+ a b)) repeat 5 collect a)").print())
			.isEqualTo("(1 2 3 5 8)");
		assertThat(eval("(loop with (x y) = '(10 20) repeat 1 collect (+ x y))").print()).isEqualTo("(30)");
		assertThat(eval("(loop for (x) on '(1 2 3) collect x)").print()).isEqualTo("(1 2 3)");
	}

	@Test
	void evalReturnExitsInnermostLoopOnly() {
		// The inner return exits only the inner dolist; the outer loop keeps iterating.
		assertThat(eval("""
				(let ((total 0))
				  (dolist (a '(1 2 3))
				    (dolist (b '(10 20 30))
				      (if (= b 20) (return))
				      (setq total (+ total b))))
				  total)""")).isEqualTo(new LispInteger(30));
	}

	@Test
	void evalIncfDecf() {
		assertThat(evalMulti("(setq n 10) (incf n) (incf n 5) (decf n 6) n")).isEqualTo(new LispInteger(10));
	}

	@Test
	void evalIncfPlace() {
		assertThat(evalMulti("(setq l (list 1 2 3)) (incf (cadr l)) l").print()).isEqualTo("(1 3 3)");
	}

	@Test
	void evalLength() {
		assertThat(eval("(length '(1 2 3 4 5))")).isEqualTo(new LispInteger(5));
		assertThat(eval("(length nil)")).isEqualTo(new LispInteger(0));
	}

	@Test
	void evalReverse() {
		assertThat(eval("(reverse '(1 2 3))").print()).isEqualTo("(3 2 1)");
		assertThat(eval("(reverse nil)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalMember() {
		assertThat(eval("(member 3 '(1 2 3 4))").print()).isEqualTo("(3 4)");
		assertThat(eval("(member 9 '(1 2 3))")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalMemberWithTest() {
		assertThat(eval("(member '(a d) '((a b) (a c) (a d) (a e)) :test 'equal)").print()).isEqualTo("((A D) (A E))");
		assertThat(eval("(member '(a d) '((a b) (a c) (a d) (a e)))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(member 3 '(1 2 3 4) :test #'equal)").print()).isEqualTo("(3 4)");
		assertThat(eval("(member 9 '(1 2 3) :test 'equal)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalFind() {
		assertThat(eval("(find 3 '(1 2 3 4))").print()).isEqualTo("3");
		assertThat(eval("(find 'b '(a b c))").print()).isEqualTo("B");
		assertThat(eval("(find 9 '(1 2 3))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'find 2 '(1 2 3))").print()).isEqualTo("2");
	}

	@Test
	void evalFindIf() {
		assertThat(eval("(find-if #'evenp '(1 3 5 6 7))").print()).isEqualTo("6");
		assertThat(eval("(find-if #'oddp '(2 4 6))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(find-if #'plusp '(-1 -2 3 4))").print()).isEqualTo("3");
		assertThat(eval("(funcall #'find-if #'evenp '(1 2 3))").print()).isEqualTo("2");
	}

	@Test
	void evalFindIfNot() {
		assertThat(eval("(find-if-not #'evenp '(2 4 5 6))").print()).isEqualTo("5");
		assertThat(eval("(find-if-not #'oddp '(1 3 4 5))").print()).isEqualTo("4");
		assertThat(eval("(find-if-not #'plusp '(1 2 3))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'find-if-not #'evenp '(2 4 3))").print()).isEqualTo("3");
	}

	@Test
	void evalPosition() {
		assertThat(eval("(position 3 '(1 2 3 4))").print()).isEqualTo("2");
		assertThat(eval("(position 'a '(a b c))").print()).isEqualTo("0");
		assertThat(eval("(position 9 '(1 2 3))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'position 2 '(5 2 8))").print()).isEqualTo("1");
	}

	@Test
	void evalPositionOnString() {
		assertThat(eval("(position #\\space \"hello world\")").print()).isEqualTo("5");
		assertThat(eval("(position #\\h \"hello\")").print()).isEqualTo("0");
		assertThat(eval("(position #\\z \"abc\")")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(position #\\a \"\")")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'position #\\l \"hello\")").print()).isEqualTo("2");
	}

	@Test
	void evalScanFunctionsOnStrings() {
		assertThat(eval("(find #\\l \"hello\")").print()).isEqualTo("#\\l");
		assertThat(eval("(find #\\z \"hello\")")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(find-if #'digit-char-p \"ab3c\")").print()).isEqualTo("#\\3");
		assertThat(eval("(find-if-not #'digit-char-p \"12a3\")").print()).isEqualTo("#\\a");
		assertThat(eval("(position-if #'digit-char-p \"ab3c\")").print()).isEqualTo("2");
		assertThat(eval("(count #\\a \"banana\")").print()).isEqualTo("3");
		assertThat(eval("(count-if #'digit-char-p \"a1b2\")").print()).isEqualTo("2");
		assertThat(eval("(every #'digit-char-p \"123\")").print()).isEqualTo("T");
		assertThat(eval("(every #'digit-char-p \"12a\")")).isSameAs(LispNil.INSTANCE);
		// some yields the first non-nil predicate value: digit-char-p's weight of #\1
		assertThat(eval("(some #'digit-char-p \"abc1\")").print()).isEqualTo("1");
		assertThat(eval("(notany #'digit-char-p \"ab1\")")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(notevery #'digit-char-p \"12a\")").print()).isEqualTo("T");
		assertThat(
				eval("(reduce (lambda (acc c) (if (char= c #\\a) (+ acc 1) acc)) \"banana\" :initial-value 0)").print())
			.isEqualTo("3");
	}

	@Test
	void evalSequenceReturningFunctionsOnStrings() {
		assertThat(eval("(reverse \"abc\")").print()).isEqualTo("\"cba\"");
		assertThat(eval("(reverse \"\")").print()).isEqualTo("\"\"");
		assertThat(eval("(remove #\\l \"hello\")").print()).isEqualTo("\"heo\"");
		assertThat(eval("(remove-if #'digit-char-p \"a1b2\")").print()).isEqualTo("\"ab\"");
		assertThat(eval("(remove-if-not #'digit-char-p \"a1b2\")").print()).isEqualTo("\"12\"");
		assertThat(eval("(remove-duplicates \"banana\")").print()).isEqualTo("\"bna\"");
		assertThat(eval("(substitute #\\o #\\a \"banana\")").print()).isEqualTo("\"bonono\"");
		assertThat(eval("(sort \"cab\" #'char<)").print()).isEqualTo("\"abc\"");
		assertThat(eval("(funcall #'reverse \"abc\")").print()).isEqualTo("\"cba\"");
		assertThat(eval("(funcall #'remove #\\l \"hello\")").print()).isEqualTo("\"heo\"");
	}

	@Test
	void evalPositionIf() {
		assertThat(eval("(position-if #'evenp '(1 3 5 6 7))").print()).isEqualTo("3");
		assertThat(eval("(position-if #'oddp '(2 4 5))").print()).isEqualTo("2");
		assertThat(eval("(position-if #'plusp '(-1 -2 -3))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'position-if #'evenp '(1 2 3))").print()).isEqualTo("1");
	}

	@Test
	void evalCount() {
		assertThat(eval("(count 2 '(1 2 3 2 2))").print()).isEqualTo("3");
		assertThat(eval("(count 'a '(a b a c a))").print()).isEqualTo("3");
		assertThat(eval("(count 9 '(1 2 3))").print()).isEqualTo("0");
		assertThat(eval("(count 1 nil)").print()).isEqualTo("0");
		assertThat(eval("(funcall #'count 2 '(2 2 8))").print()).isEqualTo("2");
	}

	@Test
	void evalCountIf() {
		assertThat(eval("(count-if #'evenp '(1 2 3 4 5 6))").print()).isEqualTo("3");
		assertThat(eval("(count-if #'oddp '(2 4 6))").print()).isEqualTo("0");
		assertThat(eval("(count-if #'plusp nil)").print()).isEqualTo("0");
		assertThat(eval("(funcall #'count-if #'evenp '(2 2 8 1))").print()).isEqualTo("3");
	}

	@Test
	void evalAssoc() {
		assertThat(eval("(assoc 'b '((a 1) (b 2) (c 3)))").print()).isEqualTo("(B 2)");
		assertThat(eval("(assoc 'z '((a 1)))")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalAssocOnDottedAlistLiteral() {
		assertThat(eval("(assoc 'b '((a . 1) (b . 2) (c . 3)))").print()).isEqualTo("(B . 2)");
		assertThat(eval("(cdr (assoc 'b '((a . 1) (b . 2))))").print()).isEqualTo("2");
	}

	@Test
	void evalAssocWithTest() {
		assertThat(eval("(assoc \"b\" '((\"a\" . 1) (\"b\" . 2)) :test #'equal)").print()).isEqualTo("(\"b\" . 2)");
		assertThat(eval("(assoc \"z\" '((\"a\" . 1)) :test 'equal)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'assoc 'b '((a . 1) (b . 2)))").print()).isEqualTo("(B . 2)");
	}

	@Test
	void evalRassocWithTest() {
		assertThat(eval("(rassoc \"x\" '((a . \"w\") (b . \"x\")) :test #'equal)").print()).isEqualTo("(B . \"x\")");
		assertThat(eval("(rassoc \"z\" '((a . \"w\")) :test 'equal)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'rassoc 2 '((a . 1) (b . 2)))").print()).isEqualTo("(B . 2)");
	}

	@Test
	void evalAssocWithKey() {
		// :key applies a selector to each pair's car before the test; it used to be
		// silently ignored (this returned (2 . b) as if :key were absent).
		assertThat(eval("(assoc 2 '((1 . a) (2 . b) (3 . c)) :key (lambda (k) (+ k 1)))").print()).isEqualTo("(1 . A)");
		assertThat(eval("(assoc \"B\" '((\"a\" . 1) (\"b\" . 2)) :test #'string= :key #'string-upcase)").print())
			.isEqualTo("(\"b\" . 2)");
		assertThat(eval("(assoc 9 '((1 . a)) :key (lambda (k) (+ k 1)))")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalMemberWithKey() {
		assertThat(eval("(member 3 '((1 2) (3 4) (5 6)) :key #'car)").print()).isEqualTo("((3 4) (5 6))");
		assertThat(eval("(member \"b\" '((\"a\") (\"b\")) :test #'string= :key #'car)").print()).isEqualTo("((\"b\"))");
		assertThat(eval("(member 9 '((1 2)) :key #'car)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalRassocWithKey() {
		assertThat(eval("(rassoc 2 '((a . 1) (b . 3)) :key (lambda (v) (- v 1)))").print()).isEqualTo("(B . 3)");
		assertThat(eval("(rassoc 9 '((a . 1)) :key (lambda (v) (- v 1)))")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalSequenceFunctionsWithTest() {
		// :test on the sequence/set functions used to be rejected with an arity error.
		assertThat(eval("(find \"b\" '(\"a\" \"b\" \"c\") :test #'string=)").print()).isEqualTo("\"b\"");
		assertThat(eval("(position \"b\" '(\"a\" \"b\" \"c\") :test #'string=)").print()).isEqualTo("1");
		assertThat(eval("(count \"a\" '(\"a\" \"b\" \"a\") :test #'string=)").print()).isEqualTo("2");
		assertThat(eval("(remove \"b\" '(\"a\" \"b\" \"c\") :test #'string=)").print()).isEqualTo("(\"a\" \"c\")");
		assertThat(eval("(delete \"b\" (list \"a\" \"b\" \"c\") :test #'string=)").print()).isEqualTo("(\"a\" \"c\")");
		assertThat(eval("(remove-duplicates '(\"a\" \"b\" \"a\" \"c\") :test #'string=)").print())
			.isEqualTo("(\"b\" \"a\" \"c\")");
		assertThat(eval("(substitute \"X\" \"b\" '(\"a\" \"b\" \"c\") :test #'string=)").print())
			.isEqualTo("(\"a\" \"X\" \"c\")");
		assertThat(eval("(nsubstitute \"X\" \"b\" (list \"a\" \"b\") :test #'string=)").print())
			.isEqualTo("(\"a\" \"X\")");
		assertThat(eval("(union '(\"a\" \"b\") '(\"b\" \"c\") :test #'string=)").print())
			.isEqualTo("(\"c\" \"a\" \"b\")");
		assertThat(eval("(intersection '(\"a\" \"b\") '(\"b\" \"c\") :test #'string=)").print()).isEqualTo("(\"b\")");
		assertThat(eval("(set-difference '(\"a\" \"b\") '(\"b\" \"c\") :test #'string=)").print()).isEqualTo("(\"a\")");
		assertThat(eval("(adjoin \"a\" '(\"a\" \"b\") :test #'string=)").print()).isEqualTo("(\"a\" \"b\")");
		assertThat(eval("(adjoin \"z\" '(\"a\" \"b\") :test #'string=)").print()).isEqualTo("(\"z\" \"a\" \"b\")");
	}

	@Test
	void evalSequenceFunctionsWithKey() {
		assertThat(eval("(find 4 '((1 2) (3 4)) :key #'cadr)").print()).isEqualTo("(3 4)");
		assertThat(eval("(position 3 '(1 2 3 4) :key (lambda (x) (- x 1)))").print()).isEqualTo("3");
		assertThat(eval("(count 2 '((1) (2) (2) (3)) :key #'car)").print()).isEqualTo("2");
		assertThat(eval("(remove 1 '((1 a) (2 b) (1 c)) :key #'car)").print()).isEqualTo("((2 B))");
		assertThat(eval("(delete 1 (list '(1 a) '(2 b)) :key #'car)").print()).isEqualTo("((2 B))");
		assertThat(eval("(remove-duplicates '((1 a) (2 b) (1 c)) :key #'car)").print()).isEqualTo("((2 B) (1 C))");
		assertThat(eval("(substitute 'x 2 '((1) (2) (3)) :key #'car)").print()).isEqualTo("((1) X (3))");
		assertThat(eval("(nsubstitute 'x 2 (list '(1) '(2)) :key #'car)").print()).isEqualTo("((1) X)");
		assertThat(eval("(union '((1)) '((1) (2)) :test #'equal :key #'car)").print()).isEqualTo("((2) (1))");
		assertThat(eval("(intersection '((1) (2)) '((2) (3)) :key #'car)").print()).isEqualTo("((2))");
		assertThat(eval("(set-difference '((1) (2)) '((2) (3)) :key #'car)").print()).isEqualTo("((1))");
		assertThat(eval("(adjoin '(1 x) '((1 a) (2 b)) :key #'car)").print()).isEqualTo("((1 A) (2 B))");
	}

	@Test
	void evalSequenceFunctionsRejectUnknownKeywords() {
		// Unsupported keywords (:from-end, :start, ...) are rejected loudly rather than
		// silently ignored.
		assertThatThrownBy(() -> eval("(find 1 '(1 2) :from-end t)")).isInstanceOf(RuntimeException.class)
			.hasMessageContaining(":test/:key");
		assertThatThrownBy(() -> eval("(assoc 1 '((1 . a)) :from-end t)")).isInstanceOf(RuntimeException.class)
			.hasMessageContaining(":test/:key");
		assertThatThrownBy(() -> eval("(remove 1 '(1 2) :count 1)")).isInstanceOf(RuntimeException.class)
			.hasMessageContaining(":test/:key");
	}

	@Test
	void evalAconsAsFunctionValue() {
		assertThat(eval("(funcall #'acons 'a 1 '((b . 2)))").print()).isEqualTo("((A . 1) (B . 2))");
	}

	@Test
	void evalQuotedCharacterList() {
		assertThat(eval("'(#\\a #\\b)").print()).isEqualTo("(#\\a #\\b)");
		assertThat(eval("(car '(#\\a #\\b))").print()).isEqualTo("#\\a");
		assertThat(eval("'(#\\a . #\\b)").print()).isEqualTo("(#\\a . #\\b)");
	}

	@Test
	void evalImproperCallFormSignalsError() {
		assertThatThrownBy(() -> eval("(+ 1 . 2)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("Improper list in call position");
		assertThatThrownBy(() -> eval("(print (list 1 . 2))")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("Improper list in call position");
	}

	@Test
	void evalPairlis() {
		assertThat(eval("(pairlis '(a b c) '(1 2 3))").print()).isEqualTo("((A . 1) (B . 2) (C . 3))");
		assertThat(eval("(pairlis '(a b) '(1 2) '((c . 3)))").print()).isEqualTo("((A . 1) (B . 2) (C . 3))");
		assertThat(eval("(pairlis nil nil)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(pairlis '(a b) '(1))").print()).isEqualTo("((A . 1))");
		assertThat(eval("(funcall #'pairlis '(a) '(1))").print()).isEqualTo("((A . 1))");
	}

	@Test
	void evalCopyAlist() {
		assertThat(eval("(copy-alist '((a . 1) (b . 2)))").print()).isEqualTo("((A . 1) (B . 2))");
		assertThat(eval("(copy-alist nil)")).isSameAs(LispNil.INSTANCE);
		// The pair cells are copied: mutating a copied pair leaves the original alist
		// intact.
		assertThat(eval("""
				(let* ((orig (list (cons 'a 1) (cons 'b 2)))
				       (copy (copy-alist orig)))
				  (rplacd (assoc 'a copy) 99)
				  (cdr (assoc 'a orig)))""").print()).isEqualTo("1");
		assertThat(eval("(funcall #'copy-alist '((a . 1)))").print()).isEqualTo("((A . 1))");
	}

	@Test
	void evalLast() {
		assertThat(eval("(last '(1 2 3))").print()).isEqualTo("(3)");
		assertThat(eval("(last nil)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalMemberIf() {
		assertThat(eval("(member-if #'oddp '(2 4 5 6))").print()).isEqualTo("(5 6)");
		assertThat(eval("(member-if #'evenp '(1 3 5))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'member-if #'plusp '(-1 -2 3 4))").print()).isEqualTo("(3 4)");
	}

	@Test
	void evalAssocIf() {
		assertThat(eval("(assoc-if #'oddp '((2 a) (3 b) (5 c)))").print()).isEqualTo("(3 B)");
		assertThat(eval("(assoc-if #'evenp '((1 a) (3 b)))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'assoc-if #'plusp '((-1 a) (2 b)))").print()).isEqualTo("(2 B)");
	}

	@Test
	void evalGetf() {
		assertThat(eval("(getf '(:a 1 :b 2) :b)").print()).isEqualTo("2");
		assertThat(eval("(getf '(:a 1 :b 2) :a)").print()).isEqualTo("1");
		assertThat(eval("(getf '(:a 1) :x)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(getf nil :x)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'getf '(:x 10 :y 20) :y)").print()).isEqualTo("20");
	}

	@Test
	void evalRemoveDuplicates() {
		assertThat(eval("(remove-duplicates '(1 2 1 3))").print()).isEqualTo("(2 1 3)");
		assertThat(eval("(remove-duplicates '(1 2 3))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(remove-duplicates nil)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'remove-duplicates '(a b a a c))").print()).isEqualTo("(B A C)");
	}

	@Test
	void evalButlast() {
		assertThat(eval("(butlast '(1 2 3))").print()).isEqualTo("(1 2)");
		assertThat(eval("(butlast '(1))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(butlast nil)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'butlast '(a b c d))").print()).isEqualTo("(A B C)");
	}

	@Test
	void evalNconc() {
		assertThat(eval("(nconc (list 1 2) (list 3 4))").print()).isEqualTo("(1 2 3 4)");
		assertThat(eval("(nconc nil (list 1 2))").print()).isEqualTo("(1 2)");
		assertThat(eval("(nconc (list 1 2) nil)").print()).isEqualTo("(1 2)");
		assertThat(eval("(funcall #'nconc (list 'a) (list 'b 'c))").print()).isEqualTo("(A B C)");
	}

	@Test
	void evalIdentity() {
		assertThat(eval("(identity 42)").print()).isEqualTo("42");
		assertThat(eval("(identity '(1 2 3))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(identity nil)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'identity 'x)").print()).isEqualTo("X");
	}

	@Test
	void evalCopyList() {
		assertThat(eval("(copy-list '(1 2 3))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(copy-list nil)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'copy-list '(a b))").print()).isEqualTo("(A B)");
	}

	@Test
	void evalNreverse() {
		assertThat(eval("(nreverse '(1 2 3))").print()).isEqualTo("(3 2 1)");
		assertThat(eval("(nreverse nil)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'nreverse '(a b c))").print()).isEqualTo("(C B A)");
	}

	@Test
	void evalNreverseIsDestructive() {
		// The original head cell is reused and left as the tail, so an alias to it sees
		// the
		// mutation (Common Lisp semantics).
		assertThat(evalMulti("(setq a (list 1 2 3)) (setq b a) (nreverse a) b").print()).isEqualTo("(1)");
	}

	@Test
	void evalDeleteIsDestructive() {
		// Interior cells are spliced out in place, so an alias to the surviving head sees
		// the deletion of later elements.
		assertThat(evalMulti("(setq a (list 1 2 3 2 1)) (setq b a) (delete 2 a) b").print()).isEqualTo("(1 3 1)");
		assertThat(evalMulti("(setq a (list 1 2 3 4 5)) (setq b a) (delete-if #'evenp a) b").print())
			.isEqualTo("(1 3 5)");
	}

	@Test
	void evalNsubstituteIsDestructive() {
		// Matching cars are rewritten in place, so an alias sees the replacement.
		assertThat(evalMulti("(setq a (list 1 2 1 3)) (setq b a) (nsubstitute 9 1 a) b").print())
			.isEqualTo("(9 2 9 3)");
	}

	@Test
	void evalMakeList() {
		assertThat(eval("(make-list 3)").print()).isEqualTo("(NIL NIL NIL)");
		assertThat(eval("(make-list 0)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'make-list 2)").print()).isEqualTo("(NIL NIL)");
	}

	@Test
	void evalUnion() {
		assertThat(eval("(union '(1 2 3) '(2 3 4))").print()).isEqualTo("(4 1 2 3)");
		assertThat(eval("(union nil '(1 2))").print()).isEqualTo("(2 1)");
		assertThat(eval("(union '(1 2) nil)").print()).isEqualTo("(1 2)");
		assertThat(eval("(funcall #'union '(a) '(a b))").print()).isEqualTo("(B A)");
	}

	@Test
	void evalIntersection() {
		assertThat(eval("(intersection '(1 2 3) '(2 3 4))").print()).isEqualTo("(3 2)");
		assertThat(eval("(intersection '(1 2) '(3 4))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'intersection '(a b c) '(b c d))").print()).isEqualTo("(C B)");
	}

	@Test
	void evalSetDifference() {
		assertThat(eval("(set-difference '(1 2 3) '(2))").print()).isEqualTo("(3 1)");
		assertThat(eval("(set-difference '(1 2 3) '(1 2 3))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'set-difference '(a b c) '(b))").print()).isEqualTo("(C A)");
	}

	@Test
	void evalAdjoin() {
		assertThat(eval("(adjoin 1 '(2 3))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(adjoin 2 '(1 2 3))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(adjoin 'a nil)").print()).isEqualTo("(A)");
		assertThat(eval("(funcall #'adjoin 5 '(5 6))").print()).isEqualTo("(5 6)");
	}

	@Test
	void evalBitwiseOps() {
		assertThat(eval("(logand 12 10)").print()).isEqualTo("8");
		assertThat(eval("(logior 12 10)").print()).isEqualTo("14");
		assertThat(eval("(logxor 12 10)").print()).isEqualTo("6");
		assertThat(eval("(lognot 0)").print()).isEqualTo("-1");
		assertThat(eval("(lognot 5)").print()).isEqualTo("-6");
		assertThat(eval("(ash 1 4)").print()).isEqualTo("16");
		assertThat(eval("(ash 255 -4)").print()).isEqualTo("15");
		// Variadic forms (identities: logand -1, logior/logxor 0).
		assertThat(eval("(logand)").print()).isEqualTo("-1");
		assertThat(eval("(logior)").print()).isEqualTo("0");
		assertThat(eval("(logand 12 10 6)").print()).isEqualTo("0");
		assertThat(eval("(logior 1 2 4 8)").print()).isEqualTo("15");
		assertThat(eval("(funcall #'logand 6 3)").print()).isEqualTo("2");
		assertThat(eval("(funcall #'lognot 0)").print()).isEqualTo("-1");
	}

	@Test
	void evalIntegerLengthAndLogbitp() {
		assertThat(eval("(integer-length 0)").print()).isEqualTo("0");
		assertThat(eval("(integer-length 5)").print()).isEqualTo("3");
		assertThat(eval("(integer-length 255)").print()).isEqualTo("8");
		assertThat(eval("(integer-length -1)").print()).isEqualTo("0");
		assertThat(eval("(integer-length -5)").print()).isEqualTo("3");
		assertThat(eval("(logbitp 0 5)").print()).isEqualTo("T");
		assertThat(eval("(logbitp 1 5)").print()).isEqualTo("NIL");
		assertThat(eval("(logbitp 2 5)").print()).isEqualTo("T");
		assertThat(eval("(logbitp 3 -1)").print()).isEqualTo("T");
		assertThat(eval("(funcall #'integer-length 8)").print()).isEqualTo("4");
		assertThat(eval("(funcall #'logbitp 1 2)").print()).isEqualTo("T");
	}

	@Test
	void evalByteFieldOps() {
		assertThat(eval("(byte-size (byte 8 3))").print()).isEqualTo("8");
		assertThat(eval("(byte-position (byte 8 3))").print()).isEqualTo("3");
		assertThat(eval("(ldb (byte 8 0) 255)").print()).isEqualTo("255");
		assertThat(eval("(ldb (byte 4 4) 255)").print()).isEqualTo("15");
		assertThat(eval("(ldb (byte 4 0) 255)").print()).isEqualTo("15");
		assertThat(eval("(ldb (byte 8 8) 65535)").print()).isEqualTo("255");
		assertThat(eval("(dpb 0 (byte 4 0) 255)").print()).isEqualTo("240");
		assertThat(eval("(dpb 5 (byte 4 4) 0)").print()).isEqualTo("80");
		assertThat(eval("(funcall #'ldb (byte 4 4) 255)").print()).isEqualTo("15");
		assertThat(eval("(funcall #'dpb 0 (byte 4 0) 255)").print()).isEqualTo("240");
		assertThat(eval("(funcall #'byte-size (byte 6 2))").print()).isEqualTo("6");
	}

	@Test
	void evalListStarAndAcons() {
		assertThat(eval("(list* 1 2 '(3 4))").print()).isEqualTo("(1 2 3 4)");
		assertThat(eval("(list* 1 2 3)").print()).isEqualTo("(1 2 . 3)");
		assertThat(eval("(list* 'x)").print()).isEqualTo("X");
		assertThat(eval("(acons 'a 1 nil)").print()).isEqualTo("((A . 1))");
		assertThat(eval("(acons 'b 2 (list (cons 'a 1)))").print()).isEqualTo("((B . 2) (A . 1))");
	}

	@Test
	void evalEltEndpRassoc() {
		assertThat(eval("(elt '(a b c) 1)").print()).isEqualTo("B");
		assertThat(eval("(elt \"abcd\" 1)").print()).isEqualTo("#\\b");
		assertThat(eval("(endp nil)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(endp '(1))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(rassoc 2 (list (cons 'a 1) (cons 'b 2)))").print()).isEqualTo("(B . 2)");
		assertThat(eval("(rassoc 9 (list (cons 'a 1) (cons 'b 2)))")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalRevappendNreconcMaplistMapcon() {
		assertThat(eval("(revappend '(1 2 3) '(4 5))").print()).isEqualTo("(3 2 1 4 5)");
		assertThat(eval("(nreconc '(1 2 3) '(4 5))").print()).isEqualTo("(3 2 1 4 5)");
		assertThat(eval("(maplist #'identity '(1 2 3))").print()).isEqualTo("((1 2 3) (2 3) (3))");
		assertThat(eval("(mapcon #'(lambda (x) (list (car x))) '(1 2 3))").print()).isEqualTo("(1 2 3)");
	}

	@Test
	void mapFamilySignalsErrorOnNonList() {
		// The map* family operates on lists; passing a non-list (e.g. a string) signals
		// an
		// error rather than silently returning nil, which would hide a caller's mistake.
		// nil is a valid empty list and must stay accepted.
		assertThatThrownBy(() -> eval("(mapcar #'identity \"abc\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("MAPCAR: argument is not a list: \"abc\"");
		assertThatThrownBy(() -> eval("(mapc #'identity \"abc\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("MAPC: argument is not a list");
		assertThatThrownBy(() -> eval("(mapcan #'list \"abc\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("MAPCAN: argument is not a list");
		assertThatThrownBy(() -> eval("(maplist #'identity \"abc\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("MAPLIST: argument is not a list");
		assertThatThrownBy(() -> eval("(mapcon #'list \"abc\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("MAPCON: argument is not a list");
		assertThatThrownBy(() -> eval("(mapcar #'1+ 5)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("MAPCAR: argument is not a list: 5");
		// nil (the empty list) stays accepted across the family.
		assertThat(eval("(mapcar #'1+ nil)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(maplist #'identity nil)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(mapcon #'list nil)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalNotanyNotevery() {
		assertThat(eval("(notany #'evenp '(1 3 5))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(notany #'evenp '(1 2 3))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(notevery #'evenp '(2 4 5))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(notevery #'evenp '(2 4 6))")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalProg2Psetq() {
		assertThat(eval("(prog2 1 2 3)").print()).isEqualTo("2");
		assertThat(eval("(let ((a 1) (b 2)) (psetq a b b a) (list a b))").print()).isEqualTo("(2 1)");
	}

	@Test
	void evalNamedBlockAndReturnFrom() {
		// A named return-from crosses an intervening loop: the after-loop code (an
		// error in cl-ppcre's collect-char-class) must NOT run.
		assertThat(evalMulti("""
				(defun collect-demo (limit)
				  (let ((acc nil))
				    (loop for i from 0
				          do (progn
				               (when (>= i limit)
				                 (return-from collect-demo (nreverse acc)))
				               (push i acc))))
				  (error "unreachable after loop"))
				(collect-demo 3)
				""").print()).isEqualTo("(0 1 2)");
		// A non-function named block with a return-from inside a loop.
		assertThat(eval("""
				(block scan
				  (dotimes (i 10)
				    (when (= i 4) (return-from scan (* i 100))))
				  :fell-through)
				""").print()).isEqualTo("400");
		// (block nil ...) catches plain return; nested blocks match by name.
		assertThat(eval("(block nil (return 7) 9)").print()).isEqualTo("7");
		assertThat(eval("(block a (block b (return-from a 1) 2) 3)").print()).isEqualTo("1");
		// A return-from inside a lambda called within the function's dynamic extent
		// exits the FUNCTION, as in CL.
		assertThat(evalMulti("""
				(defun outer-exit ()
				  (mapcar (lambda (x) (when (= x 2) (return-from outer-exit :found))) '(1 2 3))
				  :not-found)
				(outer-exit)
				""").print()).isEqualTo(":FOUND");
		// A defmethod body is a block named after the generic.
		assertThat(evalMulti("""
				(defgeneric probe-rf (x))
				(defmethod probe-rf ((x integer))
				  (dotimes (i 10)
				    (when (= i x) (return-from probe-rf (* i 2))))
				  :none)
				(probe-rf 3)
				""").print()).isEqualTo("6");
	}

	@Test
	void evalCharComparisonExtensions() {
		assertThat(eval("(char> #\\c #\\b #\\a)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(char> #\\a #\\b)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(char>= #\\b #\\b #\\a)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(char/= #\\a #\\b #\\c)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(char/= #\\a #\\b #\\a)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(char-equal #\\A #\\a)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(char-equal #\\A #\\b)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalCopyTreeAndSearch() {
		assertThat(evalMulti("""
				(let* ((orig (list (list 1 2)))
				       (copy (copy-tree orig)))
				  (setf (car (car copy)) 99)
				  (list orig copy))
				""").print()).isEqualTo("(((1 2)) ((99 2)))");
		assertThat(eval("(search \"bc\" \"abcd\")").print()).isEqualTo("1");
		assertThat(eval("(search \"x\" \"abcd\")").print()).isEqualTo("NIL");
		assertThat(eval("(search \"ab\" \"ab-ab\" :from-end t)").print()).isEqualTo("3");
		assertThat(eval("(search \"BC\" \"abcd\" :test #'char-equal)").print()).isEqualTo("1");
	}

	@Test
	void evalSetfSubseqReplacesInPlace() {
		assertThat(evalMulti("""
				(defvar *ss* (make-array 5 :element-type 'character :fill-pointer t :adjustable t))
				(replace *ss* "abcde")
				(setf (subseq *ss* 1 3) "XY")
				*ss*
				""").print()).isEqualTo("\"aXYde\"");
	}

	@Test
	void evalPsetf() {
		assertThat(eval("(let ((a 1) (b 2)) (psetf a b b a) (list a b))").print()).isEqualTo("(2 1)");
		// Place subforms are evaluated BEFORE any assignment: (cdr last-cdr) reads the
		// OLD last-cdr even though the first pair reassigns it (cl-ppcre's parser merge).
		assertThat(eval("""
				(let* ((tail (list 2))
				       (last-cdr tail)
				       (fresh (list 3)))
				  (psetf last-cdr fresh
				         (cdr last-cdr) fresh)
				  (list tail last-cdr))
				""").print()).isEqualTo("((2 3) (3))");
	}

	@Test
	void evalSubst() {
		assertThat(eval("(subst 'x 'a '(a (b a) c))").print()).isEqualTo("(X (B X) C)");
		assertThat(eval("(subst 9 '(char-class-test) '(f (char-class-test) g) :test #'equal)").print())
			.isEqualTo("(F 9 G)");
	}

	@Test
	void evalSimpleStringP() {
		assertThat(eval("(simple-string-p \"abc\")")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(simple-string-p 42)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(funcall #'simple-string-p \"abc\")")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void evalGetSetfExpansionThroughMultipleValueBind() {
		// The incf-after idiom: destructure the five setf-expansion values via mvb and
		// build a writer form for a variable place.
		LispVal result = evalMulti("""
				(defmacro incr-place (place &environment env)
				  (multiple-value-bind (vars vals store-vars writer-form reader-form)
				      (get-setf-expansion place env)
				    `(let* (,@(mapcar #'list vars vals)
				            (,(car store-vars) (+ ,reader-form 1)))
				       ,writer-form)))
				(defvar *gsx* 5)
				(incr-place *gsx*)
				*gsx*
				""");
		assertThat(result.print()).isEqualTo("6");
	}

	@Test
	void evalConstantpAcceptsEnvironmentArgument() {
		assertThat(eval("(constantp \"abc\" nil)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(constantp 'x nil)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalLocalDeclareSpecialThreadsDynamically() {
		// A local (declare (special x)) makes the let binding dynamic, so a callee
		// redeclaring it reads the caller's binding (cl-ppcre's convert phase).
		LispVal result = evalMulti("""
				(defun read-shared () (declare (special shared)) (car shared))
				(defun with-shared ()
				  (let ((shared (list 42)))
				    (declare (special shared))
				    (read-shared)))
				(with-shared)
				""");
		assertThat(result.print()).isEqualTo("42");
	}

	@Test
	void evalClosReaderMethodsDispatchPerClass() {
		// The same reader name over DIFFERENT slot positions in unrelated classes, plus
		// a plain defmethod on a third class, all merge into one generic (cl-ppcre's
		// len).
		LispVal result = evalMulti("""
				(defclass w1 () ((pad :initarg :pad) (size :initarg :size :accessor size)))
				(defclass w2 () ((size :initarg :size :accessor size)))
				(defclass w3 () ())
				(defmethod size ((w w3)) 0)
				(defvar *w1* (make-instance 'w1 :pad 9 :size 11))
				(defvar *w2* (make-instance 'w2 :size 22))
				(setf (size *w2*) 23)
				(list (size *w1*) (size *w2*) (size (make-instance 'w3)))
				""");
		assertThat(result.print()).isEqualTo("(11 23 0)");
	}

	@Test
	void evalInitializeInstanceAfterMethodRunsOnMakeInstance() {
		LispVal result = evalMulti("""
				(defclass counted () ((n :initarg :n :accessor n)))
				(defmethod initialize-instance :after ((c counted) &rest init-args)
				  (setf (n c) (* 10 (n c))))
				(list (n (make-instance 'counted :n 4))
				      (n (make-instance 'counted :n 5)))
				""");
		assertThat(result.print()).isEqualTo("(40 50)");
	}

	@Test
	void evalTypecase() {
		assertThat(eval("(typecase 42 (string \"s\") (integer \"i\") (t \"?\"))").print()).isEqualTo("\"i\"");
		assertThat(eval("(typecase \"x\" (string \"s\") (integer \"i\") (t \"?\"))").print()).isEqualTo("\"s\"");
		assertThat(eval("(typecase 'sym (string \"s\") (integer \"i\") (t \"?\"))").print()).isEqualTo("\"?\"");
		assertThat(eval("(typecase '(1) (cons \"c\") (null \"n\"))").print()).isEqualTo("\"c\"");
	}

	@Test
	void evalError() {
		assertThatThrownBy(() -> eval("(error \"boom\")")).isInstanceOf(LispEvalException.class).hasMessage("boom");
		assertThatThrownBy(() -> eval("(error \"bad value: ~a\" (+ 1 2))")).isInstanceOf(LispEvalException.class)
			.hasMessage("bad value: 3");
		assertThatThrownBy(() -> eval("(error \"got ~s instead\" \"x\")")).isInstanceOf(LispEvalException.class)
			.hasMessage("got \"x\" instead");
	}

	@Test
	void evalEcase() {
		assertThat(eval("(ecase 2 (1 \"one\") (2 \"two\") (3 \"three\"))").print()).isEqualTo("\"two\"");
		assertThat(eval("(ecase 'b ((a) \"A\") ((b c) \"BC\"))").print()).isEqualTo("\"BC\"");
		assertThatThrownBy(() -> eval("(ecase 9 (1 \"one\") (2 \"two\"))")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("ECASE");
	}

	@Test
	void evalCcase() {
		assertThat(eval("(ccase 1 (1 \"one\") (2 \"two\"))").print()).isEqualTo("\"one\"");
		assertThatThrownBy(() -> eval("(ccase 9 (1 \"one\"))")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("ECASE");
	}

	@Test
	void evalEtypecase() {
		assertThat(eval("(etypecase 42 (string \"s\") (integer \"i\"))").print()).isEqualTo("\"i\"");
		assertThat(eval("(etypecase \"x\" (string \"s\") (integer \"i\"))").print()).isEqualTo("\"s\"");
		assertThatThrownBy(() -> eval("(etypecase 'sym (string \"s\") (integer \"i\"))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("ETYPECASE");
	}

	@Test
	void evalTypecaseCompoundSpecifiers() {
		assertThat(eval("(typecase 5 ((integer 0 9) \"digit\") (integer \"int\"))").print()).isEqualTo("\"digit\"");
		assertThat(eval("(typecase 42 ((integer 0 9) \"digit\") (integer \"int\"))").print()).isEqualTo("\"int\"");
		assertThat(eval("(typecase 'b ((member a b c) \"abc\") (t \"other\"))").print()).isEqualTo("\"abc\"");
		assertThat(eval("(typecase 3 ((or string symbol) \"sos\") (t \"other\"))").print()).isEqualTo("\"other\"");
	}

	@Test
	void evalDeclareIsANoOp() {
		assertThat(evalMulti("(defun decl-fn (x) (declare (ignore x)) 42) (decl-fn 1)")).isEqualTo(new LispInteger(42));
		assertThat(eval("(let ((x 1)) (declare (type integer x) (optimize (speed 3))) (+ x 1))"))
			.isEqualTo(new LispInteger(2));
	}

	@Test
	void evalDeclaimAndProclaimAreNoOps() {
		assertThat(eval("(declaim (inline foo) (optimize (safety 0)))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(proclaim '(special *x*))")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalTheReturnsTheValue() {
		assertThat(eval("(the integer (+ 1 2))")).isEqualTo(new LispInteger(3));
		assertThat(eval("(the (or null string) \"s\")").print()).isEqualTo("\"s\"");
	}

	@Test
	void evalEvalWhenActsAsProgn() {
		assertThat(eval("(eval-when (:compile-toplevel :load-toplevel :execute) (+ 1 2))"))
			.isEqualTo(new LispInteger(3));
		assertThat(eval("(eval-when (:execute))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(eval-when (:execute) (defun ew-fn (x) (* x 2)) (ew-fn 21))")).isEqualTo(new LispInteger(42));
	}

	@Test
	void evalEvalWhenWrappedDefmacro() {
		assertThat(evalMulti("(eval-when (:compile-toplevel :load-toplevel :execute)"
				+ " (defmacro ew-twice (x) (list '+ x x))) (ew-twice 21)"))
			.isEqualTo(new LispInteger(42));
	}

	@Test
	void evalCheckType() {
		assertThat(eval("(let ((n 5)) (check-type n integer))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(let ((n 5)) (check-type n (integer 0 9)))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(let ((s \"x\")) (check-type s (or null string)))")).isEqualTo(LispNil.INSTANCE);
		assertThatThrownBy(() -> eval("(let ((n \"5\")) (check-type n integer))")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("The value of N is \"5\", which is not of type INTEGER.");
		assertThatThrownBy(() -> eval("(let ((n 12)) (check-type n (integer 0 9) \"a single digit\"))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("The value of N is 12, which is not a single digit.");
	}

	@Test
	void evalCheckTypeSatisfiesAndMember() {
		assertThat(evalMulti("(defun small-p (n) (< n 10)) (let ((n 5)) (check-type n (satisfies small-p)))"))
			.isEqualTo(LispNil.INSTANCE);
		assertThatThrownBy(() -> eval("(let ((k 'd)) (check-type k (member a b c)))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("which is not of type (MEMBER A B C)");
	}

	@Test
	void evalAssert() {
		assertThat(eval("(assert (= 1 1))")).isEqualTo(LispNil.INSTANCE);
		assertThatThrownBy(() -> eval("(assert (= 1 2))")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("The assertion (= 1 2) failed.");
		assertThatThrownBy(() -> eval("(let ((x 0)) (assert (> x 0) (x) \"x must be positive, got ~a\" x))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("x must be positive, got 0");
	}

	@Test
	void evalFletBasic() {
		assertThat(eval("(flet ((add1 (x) (+ x 1))) (add1 41))")).isEqualTo(new LispInteger(42));
		assertThat(eval("(flet ((sq (x) (* x x)) (dbl (x) (* 2 x))) (sq (dbl 3)))")).isEqualTo(new LispInteger(36));
		assertThat(eval("(flet ((sq (x) (* x x))) (mapcar #'sq '(1 2 3)))").print()).isEqualTo("(1 4 9)");
		assertThat(eval("(flet ((dbl (x) (* 2 x))) (funcall #'dbl 21))")).isEqualTo(new LispInteger(42));
		assertThat(eval("(flet () 'ok)").print()).isEqualTo("OK");
	}

	@Test
	void evalFletIsNotRecursive() {
		// The definition body sees the OUTER function binding, not itself.
		assertThat(evalMulti("(defun shadow-fn (x) (* 100 x))"
				+ " (flet ((shadow-fn (x) (if (= x 0) 'zero (shadow-fn 0)))) (shadow-fn 5))"))
			.isEqualTo(new LispInteger(0));
	}

	@Test
	void evalFletLambdaListExtensionsAndClosure() {
		assertThat(eval("(flet ((opt (a &optional (b 10) &rest r) (list a b r))) (opt 1))").print())
			.isEqualTo("(1 10 NIL)");
		assertThat(eval("(flet ((opt (a &optional (b 10) &rest r) (list a b r))) (opt 1 2 3 4))").print())
			.isEqualTo("(1 2 (3 4))");
		assertThat(eval("(let ((base 100)) (flet ((offs (x) (+ base x))) (offs 5)))")).isEqualTo(new LispInteger(105));
	}

	@Test
	void evalFletNestedShadowing() {
		assertThat(eval("(flet ((g () 1)) (flet ((h () (g))) (flet ((g () 2)) (list (g) (h)))))").print())
			.isEqualTo("(2 1)");
	}

	@Test
	void evalFletDoesNotRewriteDataPositions() {
		// case keys, quoted data, and let binding names are not call positions.
		assertThat(eval("(flet ((k (x) (* x 10))) (case 2 ((1 2) (k 3)) (t 'other)))")).isEqualTo(new LispInteger(30));
		assertThat(eval("(flet ((k (x) x)) (car '(k 1)))").print()).isEqualTo("K");
		assertThat(eval("(flet ((k (x) x)) (let ((k 5)) (k k)))")).isEqualTo(new LispInteger(5));
	}

	@Test
	void evalLabelsRecursion() {
		assertThat(eval("(labels ((fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))) (fact 6))"))
			.isEqualTo(new LispInteger(720));
		assertThat(eval("(labels ((tri (n) (if (= n 0) 0 (+ n (tri (- n 1)))))) (apply #'tri '(4)))"))
			.isEqualTo(new LispInteger(10));
	}

	@Test
	void evalLabelsMutualRecursion() {
		assertThat(eval("(labels ((ev (n) (if (= n 0) t (od (- n 1)))) (od (n) (if (= n 0) nil (ev (- n 1)))))"
				+ " (list (ev 10) (od 10)))")
			.print()).isEqualTo("(T NIL)");
	}

	@Test
	void evalFletErrors() {
		assertThatThrownBy(() -> eval("(flet ((f () 1) (f () 2)) (f))")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("more than once");
		assertThatThrownBy(() -> eval("(flet ((if (x) x)) 1)")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("special operator");
		assertThatThrownBy(() -> eval("(labels (f () 1) 2)")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("definition must be");
	}

	@Test
	void evalMacroletBasic() {
		// Local macros expand within the body; the whole form is the body's value.
		assertThat(eval("(macrolet ((sq (x) `(* ,x ,x))) (sq 6))")).isEqualTo(new LispInteger(36));
		assertThat(eval("(macrolet ((sq (x) `(* ,x ,x)) (twice (x) `(+ ,x ,x))) (+ (sq 5) (twice 5)))"))
			.isEqualTo(new LispInteger(35));
		// The macro body runs at expansion time (splices the unevaluated argument form).
		assertThat(eval("(macrolet ((swap (a b) `(list ,b ,a))) (swap 1 2))").print()).isEqualTo("(2 1)");
		assertThat(eval("(macrolet () 'ok)").print()).isEqualTo("OK");
	}

	@Test
	void evalMacroletScopingAndRestore() {
		// A macrolet macro does not leak past the body: a sibling form of the same name
		// resolves in the function namespace again (here: undefined function).
		assertThat(eval("(list (macrolet ((m () 1)) (m)) 2)").print()).isEqualTo("(1 2)");
		// An extended lambda list (rest) is destructured like defmacro.
		assertThat(eval("(macrolet ((mklist (&rest xs) `(list ,@xs))) (mklist 1 2 3))").print()).isEqualTo("(1 2 3)");
	}

	@Test
	void evalDefineCompilerMacroIsNoOp() {
		// A compiler macro is only an optimization hint: the ordinary function wins.
		assertThat(evalMulti("(defun myinc (x) (+ x 1))" + " (define-compiler-macro myinc (x) `(+ ,x 100)) (myinc 10)"))
			.isEqualTo(new LispInteger(11));
		assertThat(eval("(define-compiler-macro foo (x) x)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalRestartCaseEvaluatesPrimaryForm() {
		// Lite: no restart system, so the restart clauses are dead and the primary form
		// is the value (a signaling primary form still signals).
		assertThat(eval("(restart-case (+ 1 2) (continue () 99))")).isEqualTo(new LispInteger(3));
		assertThat(eval("(restart-case 'done)").print()).isEqualTo("DONE");
		assertThatThrownBy(() -> eval("(restart-case (error \"boom\") (continue () 0))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("boom");
	}

	@Test
	void evalValuesInSingleValueContext() {
		// No runtime multiple-value representation: extra values are discarded, all
		// argument forms still evaluate (prog1 semantics); (values) reads as nil.
		assertThat(eval("(values 1 2 3)")).isEqualTo(new LispInteger(1));
		assertThat(eval("(values)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(values 42)")).isEqualTo(new LispInteger(42));
		assertThat(evalMulti("(setq mv-side 0) (list (values 1 (setq mv-side 9)) mv-side)").print()).isEqualTo("(1 9)");
		assertThat(eval("(funcall #'values 1 2)")).isEqualTo(new LispInteger(1));
	}

	@Test
	void evalMultipleValueBind() {
		assertThat(eval("(multiple-value-bind (a b) (values 1 2) (list a b))").print()).isEqualTo("(1 2)");
		// Missing values bind to nil; surplus values are evaluated and discarded.
		assertThat(eval("(multiple-value-bind (a b c) (values 1 2) (list a b c))").print()).isEqualTo("(1 2 NIL)");
		assertThat(evalMulti(
				"(setq mv-side 0)" + " (multiple-value-bind (a) (values 1 (setq mv-side 7)) (list a mv-side))")
			.print()).isEqualTo("(1 7)");
		// A single-value producer supplies its primary value only.
		assertThat(eval("(multiple-value-bind (a b) (+ 1 2) (list a b))").print()).isEqualTo("(3 NIL)");
		assertThat(eval("(multiple-value-bind () (values 1 2) 'ok)").print()).isEqualTo("OK");
	}

	@Test
	void evalMultipleValueBindFloorFamily() {
		assertThat(eval("(multiple-value-bind (q r) (floor 7 2) (list q r))").print()).isEqualTo("(3 1)");
		assertThat(eval("(multiple-value-bind (q r) (floor -7 2) (list q r))").print()).isEqualTo("(-4 1)");
		assertThat(eval("(multiple-value-bind (q r) (truncate -7 2) (list q r))").print()).isEqualTo("(-3 -1)");
		assertThat(eval("(multiple-value-bind (q r) (ceiling 7 2) (list q r))").print()).isEqualTo("(4 -1)");
		assertThat(eval("(multiple-value-bind (q r) (round 7 2) (list q r))").print()).isEqualTo("(4 -1)");
		assertThat(eval("(multiple-value-bind (q r) (floor 7.5) (list q r))").print()).isEqualTo("(7 0.5)");
	}

	@Test
	void evalMultipleValueSetq() {
		assertThat(eval("(let (a b) (multiple-value-setq (a b) (values 1 2)) (list a b))").print()).isEqualTo("(1 2)");
		// floor-family producer + returns the primary value.
		assertThat(eval("(let (a b) (list (multiple-value-setq (a b) (floor 17 5)) a b))").print())
			.isEqualTo("(3 3 2)");
		// Extra variables receive nil; a single-value producer supplies its primary only.
		assertThat(eval("(let (a b c) (multiple-value-setq (a b c) (values 1 2)) (list a b c))").print())
			.isEqualTo("(1 2 NIL)");
		assertThat(eval("(let (a b) (multiple-value-setq (a b) (+ 1 2)) (list a b))").print()).isEqualTo("(3 NIL)");
	}

	@Test
	void evalRotatef() {
		assertThat(eval("(let ((x 1) (y 2)) (rotatef x y) (list x y))").print()).isEqualTo("(2 1)");
		// rotatef returns nil.
		assertThat(eval("(let ((x 1) (y 2)) (rotatef x y))")).isEqualTo(LispNil.INSTANCE);
		// Three-place rotate: each place gets the next place's old value.
		assertThat(eval("(let ((a 1) (b 2) (c 3)) (rotatef a b c) (list a b c))").print()).isEqualTo("(2 3 1)");
		// Compound places (car/cdr).
		assertThat(eval("(let ((x (cons 1 2))) (rotatef (car x) (cdr x)) x)").print()).isEqualTo("(2 . 1)");
	}

	// --- destructuring-bind ---

	@Test
	void evalDestructuringBindPlainAndNested() {
		assertThat(eval("(destructuring-bind (a b) '(1 2) (list a b))").print()).isEqualTo("(1 2)");
		assertThat(eval("(destructuring-bind (a (b c) d) '(1 (2 3) 4) (+ a b c d))")).isEqualTo(new LispInteger(10));
		assertThat(eval("(destructuring-bind (a (b (c))) '(1 (2 (3))) (list a b c))").print()).isEqualTo("(1 2 3)");
		// Lite semantics: a missing position binds to nil, surplus elements are
		// ignored.
		assertThat(eval("(destructuring-bind (a b) '(1) (list a b))").print()).isEqualTo("(1 NIL)");
		assertThat(eval("(destructuring-bind (a) '(1 2 3) a)")).isEqualTo(new LispInteger(1));
		assertThat(eval("(destructuring-bind () '(1) 'ok)").print()).isEqualTo("OK");
	}

	@Test
	void evalDestructuringBindOptionalRestKeyAux() {
		assertThat(eval("(destructuring-bind (a &optional (b 10) c) '(1) (list a b c))").print())
			.isEqualTo("(1 10 NIL)");
		assertThat(eval("(destructuring-bind (a &optional (b 10 bp)) '(1 2) (list a b bp))").print())
			.isEqualTo("(1 2 T)");
		assertThat(eval("(destructuring-bind (a &rest r) '(1 2 3) (list a r))").print()).isEqualTo("(1 (2 3))");
		assertThat(eval("(destructuring-bind (a &body b) '(1 2 3) (list a b))").print()).isEqualTo("(1 (2 3))");
		assertThat(eval("(destructuring-bind (a &key k (j 5)) '(1 :k 2) (list a k j))").print()).isEqualTo("(1 2 5)");
		assertThat(eval("(destructuring-bind (a &aux (b (* a 2))) '(3) (list a b))").print()).isEqualTo("(3 6)");
		// An &optional default can reference an earlier parameter.
		assertThat(eval("(destructuring-bind (a &optional (b (+ a 1))) '(4) (list a b))").print()).isEqualTo("(4 5)");
	}

	@Test
	void evalDestructuringBindNestedPatternWithKeywords() {
		assertThat(eval("(destructuring-bind ((a &key k) b) '((1 :k 2) 3) (list a k b))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(destructuring-bind (x (y &optional (z 9))) '(1 (2)) (list x y z))").print())
			.isEqualTo("(1 2 9)");
	}

	@Test
	void evalDestructuringBindUnknownKeywordSignals() {
		assertThatThrownBy(() -> eval("(destructuring-bind (&key k) '(:other 1) k)"))
			.hasMessageContaining("Unknown keyword argument");
		assertThat(eval("(destructuring-bind (&key k &allow-other-keys) '(:other 1) k)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalDestructuringBindRejectsWholeAndEnvironment() {
		assertThatThrownBy(() -> eval("(destructuring-bind (&whole w a) '(1) a)"))
			.hasMessageContaining("Unsupported lambda-list keyword");
		assertThatThrownBy(() -> eval("(destructuring-bind (a &environment e) '(1) a)"))
			.hasMessageContaining("Unsupported lambda-list keyword");
	}

	@Test
	void evalFloorFamilyWithDivisorInSingleValueContext() {
		// (floor a b) outside a multiple-value consumer yields the quotient only.
		assertThat(eval("(floor 7 2)")).isEqualTo(new LispInteger(3));
		assertThat(eval("(ceiling 7 2)")).isEqualTo(new LispInteger(4));
		assertThat(eval("(round 7 2)")).isEqualTo(new LispInteger(4));
		assertThat(eval("(truncate -7 2)")).isEqualTo(new LispInteger(-3));
		assertThat(eval("(floor 7.5 2)")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalMultipleValueBindGethash() {
		String setup = "(progn (setq mv-h (make-hash-table)) (setf (gethash 'x mv-h) nil)"
				+ " (setf (gethash 'y mv-h) 42)) ";
		assertThat(evalMulti(setup + "(multiple-value-bind (v p) (gethash 'y mv-h) (list v p))").print())
			.isEqualTo("(42 T)");
		// A stored nil is present (present-p distinguishes it from a missing key).
		assertThat(evalMulti(setup + "(multiple-value-bind (v p) (gethash 'x mv-h) (list v p))").print())
			.isEqualTo("(NIL T)");
		assertThat(evalMulti(setup + "(multiple-value-bind (v p) (gethash 'z mv-h) (list v p))").print())
			.isEqualTo("(NIL NIL)");
		assertThat(evalMulti(setup + "(multiple-value-bind (v p) (gethash 'z mv-h 'dflt) (list v p))").print())
			.isEqualTo("(DFLT NIL)");
	}

	@Test
	void evalMultipleValueList() {
		assertThat(eval("(multiple-value-list (values 1 2 3))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(multiple-value-list (values))").print()).isEqualTo("NIL");
		assertThat(eval("(multiple-value-list (floor 17 5))").print()).isEqualTo("(3 2)");
		assertThat(eval("(multiple-value-list (+ 1 2))").print()).isEqualTo("(3)");
	}

	@Test
	void evalNthValue() {
		assertThat(eval("(nth-value 0 (values 'a 'b))").print()).isEqualTo("A");
		assertThat(eval("(nth-value 1 (floor 7 2))")).isEqualTo(new LispInteger(1));
		assertThat(eval("(nth-value 5 (values 1 2))")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalMultipleValueCall() {
		assertThat(eval("(multiple-value-call #'+ (values 1 2))")).isEqualTo(new LispInteger(3));
		assertThat(eval("(multiple-value-call #'+ 1 2 3)")).isEqualTo(new LispInteger(6));
		assertThat(
				eval("(multiple-value-call (lambda (a b c d) (list a b c d)) 1 (values 2 3) (nth-value 1 (floor 9 4)))")
					.print())
			.isEqualTo("(1 2 3 1)");
	}

	@Test
	void evalMultipleValueUserFunctionTailValuesCrossTheCallBoundary() {
		// The %mv-spill channel: a (values ...) tail in a user function publishes its
		// extra values, so the caller's multiple-value-bind reads them back.
		assertThat(evalMulti("(defun mv-two () (values 4 5))" + " (multiple-value-bind (a b) (mv-two) (list a b))")
			.print()).isEqualTo("(4 5)");
		// A producer that never calls values leaves the cleared spill: extras are nil.
		assertThat(evalMulti("(defun mv-one () 4)" + " (multiple-value-bind (a b) (mv-one) (list a b))").print())
			.isEqualTo("(4 NIL)");
		// A values tail with FEWER values than a previous call's resets the spill.
		assertThat(evalMulti("(defun mv-two2 () (values 4 5))" + " (defun mv-one2 () (values 6))"
				+ " (multiple-value-bind (a b) (mv-two2) nil)" + " (multiple-value-bind (a b) (mv-one2) (list a b))")
			.print()).isEqualTo("(6 NIL)");
	}

	@Test
	void evalMultipleValueBindErrors() {
		assertThatThrownBy(() -> eval("(multiple-value-bind (a) )")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> eval("(multiple-value-bind (1) (values 1) 'x)"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must be a symbol");
	}

	@Test
	void evalSequenceFunctionsAsFirstClass() {
		assertThat(eval("(funcall #'length '(7 8 9))")).isEqualTo(new LispInteger(3));
		assertThat(eval("(mapcar #'reverse '((1 2) (3 4)))").print()).isEqualTo("((2 1) (4 3))");
		assertThat(eval("(funcall #'member 2 '(1 2 3))").print()).isEqualTo("(2 3)");
	}

	@Test
	void evalSharpQuoteOfLetStarIsAnError() {
		assertThatThrownBy(() -> eval("#'let*")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("is a macro or special operator, not a function");
	}

	@Test
	void evalMapWithBuiltinCar() {
		assertThat(eval("(mapcar #'car '((1 2) (3 4) (5 6)))").print()).isEqualTo("(1 3 5)");
	}

	@Test
	void evalMapIntoList() {
		// Two source lists; the result list is filled destructively and returned. It
		// stops
		// at the shortest sequence, leaving the trailing result element untouched.
		assertThat(eval("(map-into (list 0 0 0 0) #'+ '(1 2 3) '(10 20 30 40))").print()).isEqualTo("(11 22 33 0)");
	}

	@Test
	void evalMapIntoVector() {
		assertThat(eval("(map-into (make-array 3) #'* #(2 3 4) #(5 6 7))").print()).isEqualTo("#(10 18 28)");
	}

	@Test
	void evalMapIntoSymbolDesignatorAndNoSources() {
		// A quoted-symbol function designator over one source list.
		assertThat(eval("(map-into (list nil nil nil) '1+ '(7 8 9))").print()).isEqualTo("(8 9 10)");
		// With no source sequences the function is called with no arguments per element.
		assertThat(eval("(map-into (list 0 0 0) (lambda () 42))").print()).isEqualTo("(42 42 42)");
	}

	@Test
	void evalMapIntoMixedListAndVectorOperands() {
		// The result and each source may independently be a list or a vector; dispatch is
		// at runtime via per-operand cursors.
		assertThat(eval("(map-into (list 0 0 0) #'+ #(1 2 3) '(10 20 30))").print()).isEqualTo("(11 22 33)");
		assertThat(eval("(map-into (make-array 3) #'+ '(1 2 3) #(10 20 30))").print()).isEqualTo("#(11 22 33)");
	}

	@Test
	void evalMapIntoLargeListStaysLinear() {
		// Regression guard: with all-list operands map-into must stay O(n), not re-walk
		// the list from the head each iteration (which would make 20000 elements an
		// O(n^2) hang instead of instant).
		assertThat(eval("(length (map-into (make-list 20000) (lambda (x) 1) (make-list 20000)))").print())
			.isEqualTo("20000");
	}

	@Test
	void evalMapWithBuiltinCdr() {
		assertThat(eval("(mapcar #'cdr '((1 2) (3 4) (5 6)))").print()).isEqualTo("((2) (4) (6))");
	}

	@Test
	void evalFuncallWithBuiltinPlus() {
		assertThat(eval("(funcall #'+ 3 4)")).isEqualTo(new LispInteger(7));
	}

	@Test
	void evalMapWithBuiltin1Plus() {
		assertThat(eval("(mapcar #'1+ '(1 2 3))").print()).isEqualTo("(2 3 4)");
	}

	@Test
	void evalMapcarWithMultipleLists() {
		assertThat(eval("(mapcar #'+ '(1 2 3 4) '(10 20 30 40))").print()).isEqualTo("(11 22 33 44)");
	}

	@Test
	void evalMapcarWithMultipleListsStopsAtShortest() {
		assertThat(eval("(mapcar #'cons '(1 2 3) '(a b))").print()).isEqualTo("((1 . A) (2 . B))");
	}

	@Test
	void evalMapcarWithThreeLists() {
		assertThat(eval("(mapcar #'+ '(1 2) '(10 20) '(100 200))").print()).isEqualTo("(111 222)");
	}

	@Test
	void evalMapcReturnsOriginalList() {
		// mapc applies the function for effect and returns the original list,
		// not the mapped results (unlike mapcar).
		assertThat(eval("(mapc #'1+ '(1 2 3))").print()).isEqualTo("(1 2 3)");
	}

	@Test
	void evalMapcRunsSideEffectsInOrder() {
		assertThat(eval("(progn (setq acc nil) (mapc (lambda (x) (setq acc (cons x acc))) '(1 2 3)) acc)").print())
			.isEqualTo("(3 2 1)");
	}

	@Test
	void evalBuiltinAsVariable() {
		assertThat(evalMulti("(setq my-op #'+) (funcall my-op 10 20)")).isEqualTo(new LispInteger(30));
	}

	@Test
	void evalEvery() {
		assertThat(eval("(every #'evenp '(2 4 6))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(every #'evenp '(2 3 6))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(every #'evenp '())")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(every (lambda (x) (> x 0)) '(1 2 3))")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void evalSome() {
		assertThat(eval("(some #'oddp '(2 4 5))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(some #'oddp '(2 4 6))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(some #'evenp '())")).isSameAs(LispNil.INSTANCE);
		// some returns the first non-nil predicate result, not just t.
		assertThat(eval("(some (lambda (x) (if (> x 3) (* x 10))) '(1 2 5))")).isEqualTo(new LispInteger(50));
	}

	@Test
	void evalRemove() {
		assertThat(eval("(remove 2 '(1 2 3 2 4))").print()).isEqualTo("(1 3 4)");
		assertThat(eval("(remove 9 '(1 2 3))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(remove 1 '())").print()).isEqualTo("NIL");
	}

	@Test
	void evalRemoveIf() {
		assertThat(eval("(remove-if #'evenp '(1 2 3 4 5))").print()).isEqualTo("(1 3 5)");
		assertThat(eval("(remove-if (lambda (x) (> x 3)) '(1 2 3 4 5))").print()).isEqualTo("(1 2 3)");
	}

	@Test
	void evalRemoveIfNot() {
		assertThat(eval("(remove-if-not #'evenp '(1 2 3 4 5))").print()).isEqualTo("(2 4)");
		assertThat(eval("(remove-if-not (lambda (x) (> x 3)) '(1 2 3 4 5))").print()).isEqualTo("(4 5)");
		assertThat(eval("(remove-if-not #'evenp '())").print()).isEqualTo("NIL");
		assertThat(evalMulti("(funcall #'remove-if-not #'oddp '(1 2 3 4))").print()).isEqualTo("(1 3)");
	}

	@Test
	void evalEveryAsFunctionValue() {
		assertThat(evalMulti("(funcall #'remove 2 '(1 2 3 2)) ").print()).isEqualTo("(1 3)");
	}

	@Test
	void evalDelete() {
		assertThat(eval("(delete 2 '(1 2 3 2 1))").print()).isEqualTo("(1 3 1)");
		assertThat(eval("(delete-if #'evenp '(1 2 3 4 5))").print()).isEqualTo("(1 3 5)");
		assertThat(eval("(delete-if-not #'oddp '(1 2 3 4 5))").print()).isEqualTo("(1 3 5)");
		assertThat(evalMulti("(funcall #'delete 2 '(1 2 3 2))").print()).isEqualTo("(1 3)");
	}

	@Test
	void evalSubstitute() {
		assertThat(eval("(substitute 0 2 '(1 2 3 2 1))").print()).isEqualTo("(1 0 3 0 1)");
		assertThat(eval("(substitute 9 1 '())").print()).isEqualTo("NIL");
		assertThat(eval("(nsubstitute 9 1 '(1 2 1 3))").print()).isEqualTo("(9 2 9 3)");
		assertThat(evalMulti("(funcall #'substitute 0 2 '(2 2 2))").print()).isEqualTo("(0 0 0)");
	}

	@Test
	void evalDefparameterAlwaysAssigns() {
		// Unlike defvar, defparameter re-assigns even when already bound.
		assertThat(evalMulti("(defparameter *x* 1) (defparameter *x* 2) *x*")).isEqualTo(new LispInteger(2));
		assertThat(eval("(defparameter *y* 7)")).isEqualTo(new LispSymbol("*Y*"));
	}

	@Test
	void evalDefconstant() {
		assertThat(evalMulti("(defconstant +pi3+ 3) +pi3+")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalDoStarSequentialBindings() {
		// do* binds sequentially: acc sees i's value when binding, then steps
		// sequentially.
		assertThat(eval("(do* ((i 1 (+ i 1)) (acc i (* acc i))) ((> i 5) acc))")).isEqualTo(new LispInteger(720));
		assertThat(eval("(do* ((a 1 (+ a 1)) (b a)) ((> a 3) b))")).isEqualTo(new LispInteger(1));
	}

	@Test
	void evalMapcan() {
		assertThat(eval("(mapcan (lambda (x) (list x x)) '(1 2 3))").print()).isEqualTo("(1 1 2 2 3 3)");
		assertThat(eval("(mapcan (lambda (x) (if (evenp x) (list x) nil)) '(1 2 3 4))").print()).isEqualTo("(2 4)");
		assertThat(eval("(mapcan #'list '())").print()).isEqualTo("NIL");
		assertThat(evalMulti("(funcall #'mapcan (lambda (x) (list x)) '(1 2 3))").print()).isEqualTo("(1 2 3)");
	}

	@Test
	void evalSort() {
		assertThat(eval("(sort '(3 1 4 1 5 9 2 6) #'<)").print()).isEqualTo("(1 1 2 3 4 5 6 9)");
		assertThat(eval("(sort '(3 1 4) #'>)").print()).isEqualTo("(4 3 1)");
		assertThat(eval("(sort '() #'<)").print()).isEqualTo("NIL");
		assertThat(eval("(sort '(5) #'<)").print()).isEqualTo("(5)");
		assertThat(evalMulti("(funcall #'sort '(2 3 1) #'<)").print()).isEqualTo("(1 2 3)");
	}

	@Test
	void evalApply() {
		assertThat(eval("(apply #'+ '(1 2 3))").print()).isEqualTo("6");
		assertThat(eval("(apply #'+ 1 2 '(3 4))").print()).isEqualTo("10");
		assertThat(eval("(apply #'max '(3 1 4 1 5))").print()).isEqualTo("5");
		assertThat(eval("(apply #'list 1 2 '(3 4))").print()).isEqualTo("(1 2 3 4)");
		assertThat(eval("(apply #'cons '(1 2))").print()).isEqualTo("(1 . 2)");
		assertThat(eval("(apply (lambda (a b) (+ a b)) '(10 20))").print()).isEqualTo("30");
	}

	// Lisp-2 (separate function/variable namespaces) tests

	@Test
	void evalBareFunctionNameAsVariableIsUnbound() {
		assertThatThrownBy(() -> eval("car")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("The variable CAR is unbound");
	}

	@Test
	void evalFuncallWithSharpQuotedBuiltin() {
		assertThat(eval("(funcall #'car '(1 2))")).isEqualTo(new LispInteger(1));
	}

	@Test
	void evalFunctionFormReturnsFunctionValue() {
		assertThat(eval("(function car)")).isInstanceOf(LispFunction.class);
	}

	@Test
	void evalSharpQuotedLambdaWithFuncall() {
		assertThat(eval("(funcall #'(lambda (x) (* x 2)) 21)")).isEqualTo(new LispInteger(42));
	}

	@Test
	void evalSymbolFunctionWithFuncall() {
		assertThat(eval("(funcall (symbol-function 'car) '(9 8))")).isEqualTo(new LispInteger(9));
	}

	@Test
	void evalFuncallWithSymbolDesignator() {
		assertThat(eval("(funcall 'car '(7 8))")).isEqualTo(new LispInteger(7));
	}

	@Test
	void evalFunctionOfSpecialOperatorThrows() {
		assertThatThrownBy(() -> eval("#'defun")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("is a macro or special operator, not a function");
	}

	@Test
	void evalCallOfUndefinedFunctionThrows() {
		assertThatThrownBy(() -> eval("(nosuchfn 1)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("The function NOSUCHFN is undefined");
	}

	@Test
	void evalVariableBindingDoesNotShadowFunction() {
		assertThat(eval("(let ((car 5)) (car (list car 2)))")).isEqualTo(new LispInteger(5));
	}

	@Test
	void evalDefunReturnsFunctionNameSymbol() {
		assertThat(eval("(defun f (x) x)")).isEqualTo(new LispSymbol("F"));
	}

	@Test
	void evalMapWithSharpQuotedCarCdrComposition() {
		assertThat(eval("(mapcar #'cadr '((1 2) (3 4)))").print()).isEqualTo("(2 4)");
	}

	// eval tests

	@Test
	void evalEvalSelfEvaluating() {
		assertThat(eval("(eval 42)")).isEqualTo(new LispInteger(42));
	}

	@Test
	void evalEvalQuotedExpression() {
		assertThat(eval("(eval '(+ 1 2))")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalEvalDynamicExpression() {
		assertThat(eval("(eval (list '+ 1 2))")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalEvalViaVariable() {
		assertThat(evalMulti("(let ((x '(+ 1 2))) (eval x))")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalEvalWhileAndDotimes() {
		assertThat(eval("(eval '(let ((s 0)) (dotimes (i 5) (setq s (+ s i))) s))")).isEqualTo(new LispInteger(10));
		assertThat(eval("(eval '(let ((n 0) (s 0)) (while (< n 5) (setq s (+ s n)) (setq n (+ n 1))) s))"))
			.isEqualTo(new LispInteger(10));
	}

	// read-line / read tests

	private LispVal evalWithStdin(String input, String stdin) {
		ByteArrayInputStream in = new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()), in);
		return evaluator.eval(LispReader.readFromString(input));
	}

	private LispVal evalMultiWithStdin(String input, String stdin) {
		ByteArrayInputStream in = new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()), in);
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	@Test
	void evalReadLine() {
		assertThat(evalWithStdin("(read-line)", "hello\n")).isEqualTo(new LispString("hello"));
	}

	@Test
	void evalReadLineTwoLines() {
		assertThat(evalMultiWithStdin("(read-line) (read-line)", "hello\nworld\n")).isEqualTo(new LispString("world"));
	}

	@Test
	void evalReadLineEof() {
		assertThat(evalWithStdin("(read-line)", "")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalReadLineIsString() {
		assertThat(evalWithStdin("(stringp (read-line))", "hello\n")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void evalReadInteger() {
		assertThat(evalWithStdin("(read)", "42\n")).isEqualTo(new LispInteger(42));
	}

	@Test
	void evalReadList() {
		LispVal result = evalWithStdin("(read)", "(+ 1 2)\n");
		assertThat(result).isInstanceOf(LispCons.class);
		assertThat(result.print()).isEqualTo("(+ 1 2)");
	}

	@Test
	void evalReadEof() {
		assertThat(evalWithStdin("(read)", "")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalReadSkipsBlankAndCommentLines() {
		assertThat(evalWithStdin("(read)", "\n   \n; comment only\n42\n")).isEqualTo(new LispInteger(42));
	}

	@Test
	void evalReadSharpQuote() {
		LispVal result = evalWithStdin("(read)", "#'car\n");
		assertThat(result.print()).isEqualTo("(FUNCTION CAR)");
	}

	@Test
	void bigIntegerFactorialPromotesOnOverflow() {
		LispVal result = evalMulti("""
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(fact 32)
				""");
		assertThat(result).isEqualTo(new LispBigInteger(new BigInteger("263130836933693530167218012160000000")));
	}

	@Test
	void bigIntegerAdditionOverflow() {
		assertThat(eval("(+ 9223372036854775807 1)"))
			.isEqualTo(new LispBigInteger(new BigInteger("9223372036854775808")));
	}

	@Test
	void bigIntegerMultiplicationOverflow() {
		assertThat(eval("(* 1000000000000 1000000000000)"))
			.isEqualTo(new LispBigInteger(new BigInteger("1000000000000000000000000")));
	}

	@Test
	void bigIntegerResultDemotedWhenFitsInLong() {
		// A BigInteger result that fits back in a long is normalized to LispInteger.
		assertThat(eval("(- (* 1000000000000 1000000000000) (* 1000000000000 1000000000000))"))
			.isEqualTo(new LispInteger(0));
	}

	@Test
	void bigIntegerLiteralIsParsed() {
		assertThat(eval("123456789012345678901234567890"))
			.isEqualTo(new LispBigInteger(new BigInteger("123456789012345678901234567890")));
	}

	@Test
	void bigIntegerComparison() {
		assertThat(eval("(> (* 5000000000 5000000000) 9223372036854775807)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(= (* 5000000000 5000000000) 25000000000000000000)")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void bigIntegerIntegerpAndNumberp() {
		assertThat(eval("(integerp (* 5000000000 5000000000))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(numberp (* 5000000000 5000000000))")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void bigIntegerEvenpOddp() {
		assertThat(eval("(evenp (* 5000000000 5000000000))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(oddp (+ 1 (* 5000000000 5000000000)))")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void bigIntegerMod() {
		LispVal result = evalMulti("""
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(mod (fact 25) 1000000007)
				""");
		assertThat(result).isEqualTo(new LispInteger(440732388));
	}

	@Test
	void bigIntegerDivisionDemotes() {
		LispVal result = evalMulti("""
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(/ (fact 32) (fact 31))
				""");
		assertThat(result).isEqualTo(new LispInteger(32));
	}

	@Test
	void bigIntegerAbs() {
		assertThat(eval("(abs (- (* 5000000000 5000000000)))"))
			.isEqualTo(new LispBigInteger(new BigInteger("25000000000000000000")));
	}

	@Test
	void bigIntegerMaxAndMin() {
		assertThat(eval("(max (* 5000000000 5000000000) 1)"))
			.isEqualTo(new LispBigInteger(new BigInteger("25000000000000000000")));
		assertThat(eval("(min (* 5000000000 5000000000) 1)")).isEqualTo(new LispInteger(1));
	}

	@Test
	void bigIntegerFloatConversion() {
		assertThat(eval("(float (* 1000000000000 1000000000000))")).isEqualTo(new LispDouble(1.0e24));
	}

	@Test
	void witExportChecksTheProgramAgainstTheWorldAndIsOtherwiseInert(@TempDir Path tempDir) throws Exception {
		// The interpreter cannot export anything, but it still holds the program to the
		// world's contract, so a plain `rontolisp prog.lisp` run catches the same drift a
		// --component build would reject. It is a special form: the check sees the
		// functions defined so far, hence the directive goes last.
		Path wit = tempDir.resolve("analyzer.wit");
		Files.writeString(wit, """
				package root:component;

				world analyzer {
				  export count-vowels: func(s: string) -> s32;
				}
				""");
		String directive = "(rontolisp:wit-export \"" + wit.toString().replace("\\", "\\\\") + "\")";
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(defun count-vowels (s) (length s))"));
		// A matching world is a no-op returning nil.
		assertThat(evaluator.eval(LispReader.readFromString(directive))).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void witExportSignalsWhenTheWorldDoesNotMatchTheDefuns(@TempDir Path tempDir) throws Exception {
		Path wit = tempDir.resolve("analyzer.wit");
		Files.writeString(wit, """
				package root:component;

				world analyzer {
				  export count-vowels: func(s: string) -> s32;
				}
				""");
		String directive = "(rontolisp:wit-export \"" + wit.toString().replace("\\", "\\\\") + "\")";
		// No such defun at all: the error names the WIT file and line.
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator missing = new LispEvaluator(new PrintStream(baos));
		assertThatThrownBy(() -> missing.eval(LispReader.readFromString(directive)))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("analyzer.wit:4")
			.hasMessageContaining("export 'count-vowels' has no matching (defun count-vowels ...) in the program");
		// Defined, but with the wrong arity.
		LispEvaluator wrongArity = new LispEvaluator(new PrintStream(baos));
		wrongArity.eval(LispReader.readFromString("(defun count-vowels (s from) (length s))"));
		assertThatThrownBy(() -> wrongArity.eval(LispReader.readFromString(directive)))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("analyzer.wit:4")
			.hasMessageContaining("declares 1 parameter(s), but (defun count-vowels ...) takes 2");
		// Defined, but variadic: an exported function takes required parameters only.
		LispEvaluator variadic = new LispEvaluator(new PrintStream(baos));
		variadic.eval(LispReader.readFromString("(defun count-vowels (&rest args) 0)"));
		assertThatThrownBy(() -> variadic.eval(LispReader.readFromString(directive)))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("an exported function takes required parameters only");
	}

	@Test
	void witExportAcceptsTheTutorialWorldVerbatim(@TempDir Path tempDir) throws Exception {
		// The canonical component-model tutorial world, unedited. Its u32 types made it a
		// hard error here -- on the plain interpreter, before any WASM was involved --
		// which is the wrong first impression for the first world anyone meets.
		Path wit = tempDir.resolve("adder.wit");
		Files.writeString(wit, """
				package docs:adder@0.1.0;

				interface add {
				  add: func(x: u32, y: u32) -> u32;
				}

				world adder {
				  export add;
				}
				""");
		String directive = "(rontolisp:wit-export \"" + wit.toString().replace("\\", "\\\\") + "\" :world adder)";
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(defun add (x y) (+ x y))"));
		assertThat(evaluator.eval(LispReader.readFromString(directive))).isEqualTo(LispNil.INSTANCE);
		assertThat(evaluator.eval(LispReader.readFromString("(add 2 3)"))).isEqualTo(new LispInteger(5));
	}

	@Test
	void witExportReportsAMissingWitFile() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		assertThatThrownBy(() -> evaluator.eval(LispReader.readFromString("(rontolisp:wit-export \"nope.wit\")")))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("cannot read file")
			.hasMessageContaining("nope.wit");
	}

	@Test
	void loadEvaluatesFileAndReusesDefinitions(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("bar.lisp");
		Files.writeString(file, "(defun square (x) (* x x))\n(setq base 10)\n");
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		LispVal loadResult = evaluator
			.eval(LispReader.readFromString("(load \"" + file.toString().replace("\\", "\\\\") + "\")"));
		assertThat(loadResult).isEqualTo(LispTrue.INSTANCE);
		// Definitions from the loaded file are reusable afterwards.
		assertThat(evaluator.eval(LispReader.readFromString("(square base)"))).isEqualTo(new LispInteger(100));
	}

	@Test
	void loadResolvesRelativePathsAgainstTheLoadingFile(@TempDir Path tempDir) throws Exception {
		// A driver in a subdirectory loads a sibling by bare name; with the entry
		// directory as the base, the relative load resolves there (not against the
		// process working directory), and a nested load chains relative to each file.
		Path dir = Files.createDirectories(tempDir.resolve("proj"));
		Files.writeString(dir.resolve("common.lisp"), "(defun sq (x) (* x x))\n");
		Files.writeString(dir.resolve("core.lisp"), "(load \"common.lisp\")\n(defun cube (x) (* x (sq x)))\n");
		Path entry = dir.resolve("main.lisp");
		Files.writeString(entry, "(load \"core.lisp\")\n(print (cube 3))\n");
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.setLoadBaseDir(dir.toString());
		for (LispVal form : LispReader.readAllFromString(Files.readString(entry))) {
			evaluator.eval(form);
		}
		assertThat(baos.toString(StandardCharsets.UTF_8).trim()).isEqualTo("27");
	}

	@Test
	void withOpenFileWriteThenRead(@TempDir Path tempDir) {
		String file = tempDir.resolve("out.txt").toString().replace("\\", "\\\\");
		LispVal result = evalMulti("""
				(with-open-file (out "%s" :direction :output)
				  (write-line "hello" out)
				  (write-line "world" out))
				(with-open-file (in "%s")
				  (list (read-line in) (read-line in) (read-line in)))
				""".formatted(file, file));
		assertThat(result.print()).isEqualTo("(\"hello\" \"world\" NIL)");
	}

	@Test
	void withOpenFileReturnsBodyValue(@TempDir Path tempDir) {
		String file = tempDir.resolve("ret.txt").toString().replace("\\", "\\\\");
		assertThat(eval("(with-open-file (out \"" + file + "\" :direction :output) (write-line \"x\" out) 42)"))
			.isEqualTo(new LispInteger(42));
	}

	@Test
	void openCloseExplicitStreams(@TempDir Path tempDir) {
		String file = tempDir.resolve("manual.txt").toString().replace("\\", "\\\\");
		LispVal result = evalMulti("""
				(setq out (open "%s" :output))
				(write-line "line1" out)
				(close out)
				(setq in (open "%s" :input))
				(setq first-line (read-line in))
				(close in)
				first-line
				""".formatted(file, file));
		assertThat(result).isEqualTo(new LispString("line1"));
	}

	@Test
	void writeLineWithoutStreamPrintsToStdout() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		LispVal result = evaluator.eval(LispReader.readFromString("(write-line \"to stdout\")"));
		assertThat(result).isEqualTo(new LispString("to stdout"));
		assertThat(baos.toString(StandardCharsets.UTF_8).trim()).isEqualTo("to stdout");
	}

	@Test
	void withOpenFileClosesStreamAfterBody(@TempDir Path tempDir) {
		String file = tempDir.resolve("closed.txt").toString().replace("\\", "\\\\");
		LispVal result = evalMulti("""
				(setq s nil)
				(with-open-file (out "%s" :direction :output) (setq s out))
				""".formatted(file));
		assertThat(result.print()).isNotEmpty();
		assertThatThrownBy(() -> evalMulti("""
				(setq s (open "%s" :output))
				(close s)
				(close s)
				""".formatted(file))).isInstanceOf(LispEvalException.class).hasMessageContaining("not an open stream");
	}

	@Test
	void openMissingFileThrows(@TempDir Path tempDir) {
		String missing = tempDir.resolve("nope.txt").toString().replace("\\", "\\\\");
		assertThatThrownBy(() -> eval("(open \"" + missing + "\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("cannot open file");
	}

	@Test
	void withOpenFileUnsupportedOptionThrows(@TempDir Path tempDir) {
		String file = tempDir.resolve("opt.txt").toString().replace("\\", "\\\\");
		// :if-exists is accepted only with the value the native behavior already
		// implements (:supersede); :append must not be silently reinterpreted.
		assertThatThrownBy(() -> eval("(with-open-file (s \"" + file + "\" :if-exists :append) s)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining(":IF-EXISTS supports only the native default value");
	}

	@Test
	void withOpenFileBinaryRoundTrip(@TempDir Path tempDir) {
		String file = tempDir.resolve("bin.dat").toString().replace("\\", "\\\\");
		LispVal result = evalMulti("""
				(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8))
				  (write-byte 0 out)
				  (write-byte 10 out)
				  (write-byte 34 out)
				  (write-byte 255 out))
				(with-open-file (in "%s" :element-type '(unsigned-byte 8))
				  (list (read-byte in) (read-byte in) (read-byte in) (read-byte in) (read-byte in nil nil)))
				""".formatted(file, file));
		assertThat(result.print()).isEqualTo("(0 10 34 255 NIL)");
	}

	@Test
	void withOpenFileCharacterElementTypeIsText(@TempDir Path tempDir) {
		String file = tempDir.resolve("chr.txt").toString().replace("\\", "\\\\");
		LispVal result = evalMulti("""
				(with-open-file (out "%s" :direction :output :element-type 'character)
				  (write-line "hello" out))
				(with-open-file (in "%s" :element-type 'character)
				  (read-line in))
				""".formatted(file, file));
		assertThat(result).isEqualTo(new LispString("hello"));
	}

	@Test
	void withOpenFileNonLiteralElementTypeThrows(@TempDir Path tempDir) {
		String file = tempDir.resolve("nl.dat").toString().replace("\\", "\\\\");
		assertThatThrownBy(() -> eval("(with-open-file (s \"" + file + "\" :element-type (list 'unsigned-byte 8)) s)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining(":element-type must be the literal");
	}

	@Test
	void readByteEofSignalsErrorByDefault(@TempDir Path tempDir) {
		String file = tempDir.resolve("eof.dat").toString().replace("\\", "\\\\");
		assertThatThrownBy(() -> evalMulti("""
				(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8)))
				(with-open-file (in "%s" :element-type '(unsigned-byte 8))
				  (read-byte in))
				""".formatted(file, file))).isInstanceOf(LispEvalException.class).hasMessageContaining("end of file");
		assertThatThrownBy(() -> evalMulti("""
				(with-open-file (in "%s" :element-type '(unsigned-byte 8))
				  (read-byte in t))
				""".formatted(file))).isInstanceOf(LispEvalException.class).hasMessageContaining("end of file");
	}

	@Test
	void readByteEofValueReturned(@TempDir Path tempDir) {
		String file = tempDir.resolve("eofv.dat").toString().replace("\\", "\\\\");
		LispVal result = evalMulti("""
				(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8)))
				(with-open-file (in "%s" :element-type '(unsigned-byte 8))
				  (read-byte in nil -1))
				""".formatted(file, file));
		assertThat(result).isEqualTo(new LispInteger(-1));
	}

	@Test
	void writeByteReturnsByteAndValidatesRange(@TempDir Path tempDir) {
		String file = tempDir.resolve("wb.dat").toString().replace("\\", "\\\\");
		LispVal result = evalMulti("""
				(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8))
				  (write-byte 65 out))
				""".formatted(file));
		assertThat(result).isEqualTo(new LispInteger(65));
		assertThatThrownBy(() -> evalMulti("""
				(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8))
				  (write-byte 256 out))
				""".formatted(file))).isInstanceOf(LispEvalException.class).hasMessageContaining("between 0 and 255");
	}

	@Test
	void readByteOnTextStreamThrows(@TempDir Path tempDir) {
		String file = tempDir.resolve("txt.txt").toString().replace("\\", "\\\\");
		assertThatThrownBy(() -> evalMulti("""
				(with-open-file (out "%s" :direction :output)
				  (write-line "x" out))
				(with-open-file (in "%s")
				  (read-byte in))
				""".formatted(file, file))).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("binary input stream");
	}

	@Test
	void readWriteSequenceRoundTrip(@TempDir Path tempDir) {
		String file = tempDir.resolve("seq.dat").toString().replace("\\", "\\\\");
		LispVal result = evalMulti("""
				(setq buf (make-array 4))
				(setf (aref buf 0) 65)
				(setf (aref buf 1) 0)
				(setf (aref buf 2) 10)
				(setf (aref buf 3) 34)
				(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8))
				  (write-sequence buf out))
				(setq buf2 (make-array 4 :initial-element nil))
				(with-open-file (in "%s" :element-type '(unsigned-byte 8))
				  (list (read-sequence buf2 in) (aref buf2 0) (aref buf2 1) (aref buf2 2) (aref buf2 3)))
				""".formatted(file, file));
		assertThat(result.print()).isEqualTo("(4 65 0 10 34)");
	}

	@Test
	void writeSequenceReturnsSequence(@TempDir Path tempDir) {
		String file = tempDir.resolve("wsr.dat").toString().replace("\\", "\\\\");
		LispVal result = evalMulti("""
				(setq buf (make-array 2 :initial-element 7))
				(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8))
				  (eq (write-sequence buf out) buf))
				""".formatted(file));
		assertThat(result).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void readSequenceShortReadReturnsFillPosition(@TempDir Path tempDir) {
		String file = tempDir.resolve("short.dat").toString().replace("\\", "\\\\");
		LispVal result = evalMulti("""
				(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8))
				  (write-byte 1 out)
				  (write-byte 2 out))
				(setq buf (make-array 8 :initial-element 99))
				(with-open-file (in "%s" :element-type '(unsigned-byte 8))
				  (list (read-sequence buf in) (aref buf 0) (aref buf 1) (aref buf 2)))
				""".formatted(file, file));
		assertThat(result.print()).isEqualTo("(2 1 2 99)");
	}

	@Test
	void readWriteSequenceStartEnd(@TempDir Path tempDir) {
		String file = tempDir.resolve("se.dat").toString().replace("\\", "\\\\");
		LispVal result = evalMulti("""
				(setq buf (make-array 4))
				(setf (aref buf 0) 1)
				(setf (aref buf 1) 2)
				(setf (aref buf 2) 3)
				(setf (aref buf 3) 4)
				(with-open-file (out "%s" :direction :output :element-type '(unsigned-byte 8))
				  (write-sequence buf out :start 1 :end 3))
				(setq buf2 (make-array 4 :initial-element 0))
				(with-open-file (in "%s" :element-type '(unsigned-byte 8))
				  (list (read-sequence buf2 in :start 2) (aref buf2 0) (aref buf2 1) (aref buf2 2) (aref buf2 3)))
				""".formatted(file, file));
		assertThat(result.print()).isEqualTo("(4 0 0 2 3)");
	}

	@Test
	void loadMissingFileThrows(@TempDir Path tempDir) {
		Path missing = tempDir.resolve("nope.lisp");
		assertThatThrownBy(() -> eval("(load \"" + missing.toString().replace("\\", "\\\\") + "\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("cannot read file");
	}

	@Test
	void requireLoadsTheModuleFileOnce(@TempDir Path tempDir) throws Exception {
		// The provide inside the required file marks the module, so the second require
		// (the diamond-dependency case) does not evaluate the file again.
		Files.writeString(tempDir.resolve("util.lisp"),
				"(provide :util)\n(setq util-count (+ util-count 1))\n(defun util-sq (x) (* x x))\n");
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.setLoadBaseDir(tempDir.toString());
		evaluator.eval(LispReader.readFromString("(setq util-count 0)"));
		assertThat(evaluator.eval(LispReader.readFromString("(require :util)"))).isEqualTo(new LispSymbol("UTIL"));
		assertThat(evaluator.eval(LispReader.readFromString("(require :util)"))).isEqualTo(new LispSymbol("UTIL"));
		assertThat(evaluator.eval(LispReader.readFromString("util-count"))).isEqualTo(new LispInteger(1));
		assertThat(evaluator.eval(LispReader.readFromString("(util-sq 6)"))).isEqualTo(new LispInteger(36));
	}

	@Test
	void requireAcceptsAnExplicitPathAndAllDesignatorSpellings(@TempDir Path tempDir) throws Exception {
		Files.writeString(tempDir.resolve("util-v2.lisp"), "(provide :util)\n(defun util-version () 2)\n");
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.setLoadBaseDir(tempDir.toString());
		assertThat(evaluator.eval(LispReader.readFromString("(require :util \"util-v2.lisp\")")))
			.isEqualTo(new LispSymbol("UTIL"));
		assertThat(evaluator.eval(LispReader.readFromString("(util-version)"))).isEqualTo(new LispInteger(2));
		// Symbol and string designators name the same (already provided) module.
		assertThat(evaluator.eval(LispReader.readFromString("(require 'util)"))).isEqualTo(new LispSymbol("UTIL"));
		assertThat(evaluator.eval(LispReader.readFromString("(require \"UTIL\")"))).isEqualTo(new LispSymbol("UTIL"));
	}

	@Test
	void provideMarksTheModuleAndDuplicateProvideIsANoOp() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		assertThat(evaluator.eval(LispReader.readFromString("(provide :mod)"))).isEqualTo(new LispSymbol("MOD"));
		assertThat(evaluator.eval(LispReader.readFromString("(provide :mod)"))).isEqualTo(new LispSymbol("MOD"));
		// A require of the provided module returns the name without touching any file.
		assertThat(evaluator.eval(LispReader.readFromString("(require :mod)"))).isEqualTo(new LispSymbol("MOD"));
	}

	@Test
	void requireMissingModuleFileThrows(@TempDir Path tempDir) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.setLoadBaseDir(tempDir.toString());
		assertThatThrownBy(() -> evaluator.eval(LispReader.readFromString("(require :nope)")))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("REQUIRE: cannot read file");
	}

	@Test
	void requireAndProvideRejectNonDesignatorArguments() {
		assertThatThrownBy(() -> eval("(require 42)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("module name");
		assertThatThrownBy(() -> eval("(provide 42)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("module name");
	}

	@Test
	void requireAndProvideAreFirstClassFunctions() {
		// Being cl functions (not special forms), #'require / #'provide are valid.
		assertThat(eval("(funcall #'provide :fc-mod)")).isEqualTo(new LispSymbol("FC-MOD"));
	}

	@Test
	void rontolispVersionReturnsPlist() {
		assertThat(eval("(car (rontolisp:version))")).isEqualTo(new LispSymbol(":VERSION"));
		assertThat(eval("(cadr (rontolisp:version))")).isEqualTo(new LispString(am.ik.rontolisp.Version.getVersion()));
	}

	@Test
	void versionIsNotVisibleUnqualifiedInClUser() {
		assertThatThrownBy(() -> eval("(version)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("The function VERSION is undefined");
	}

	@Test
	void wasmExportIsNoOpReturningTheNamedSymbol() {
		// The directive returns the named symbol and does not affect normal evaluation.
		assertThat(evalMulti("(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))"
				+ "(rontolisp:wasm-export 'fact :params '(:int) :returns :int)" + "(fact 5)"))
			.isEqualTo(new LispInteger(120));
		assertThat(eval("(rontolisp:wasm-export 'fact :params '(:int) :returns :int)"))
			.isEqualTo(new LispSymbol("FACT"));
	}

	@Test
	void wasmImportDefinesAnErrorSignallingStub() {
		// The directive returns the named symbol; the imported host function only
		// exists in compiled WASM output, so calling the stub signals an error.
		assertThat(eval("(rontolisp:wasm-import 'add :from \"host\" :params '(:int :int) :returns :int)"))
			.isEqualTo(new LispSymbol("ADD"));
		assertThatThrownBy(() -> evalMulti(
				"(rontolisp:wasm-import 'add :from \"host\" :params '(:int :int) :returns :int)" + "(add 1 2)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("RONTOLISP:WASM-IMPORT");
	}

	private static java.util.List<String> symbolNames(LispVal val) {
		java.util.List<String> names = new java.util.ArrayList<>();
		while (val instanceof LispCons cons) {
			names.add(((LispSymbol) cons.car()).name());
			val = cons.cdr();
		}
		return names;
	}

	@Test
	void listMacrosReturnsSortedClMacros() {
		assertThat(eval("(rontolisp:list-macros)").print()).isEqualTo(
				"(AND ASSERT BLOCK CASE CCASE CERROR CHECK-TYPE COMPLEMENT COMPLEX COND DECF DECLAIM DECLARE DEFINE-COMPILER-MACRO DEFINE-CONDITION DEFINE-MODIFY-MACRO DEFINE-SETF-EXPANDER DEFSETF DEFTYPE DESTRUCTURING-BIND DO DO* DOCUMENTATION DOLIST DOTIMES ECASE ERROR ETYPECASE EVAL-WHEN FLET FORMAT HANDLER-CASE IGNORE-ERRORS INCF LABELS LET* LOAD-TIME-VALUE LOCALLY LOOP MACROLET MAKE-CONDITION MAKE-INSTANCE MAKE-SEQUENCE MULTIPLE-VALUE-BIND MULTIPLE-VALUE-CALL MULTIPLE-VALUE-LIST MULTIPLE-VALUE-SETQ NTH-VALUE OR POP PRINT-UNREADABLE-OBJECT PROCLAIM PROG PROG* PROG1 PROG2 PSETF PSETQ PUSH PUSHNEW REMF RESTART-CASE RETURN-FROM ROTATEF SETF SHIFTF SIGNAL SLOT-BOUNDP SLOT-MAKUNBOUND SLOT-VALUE THE TIME TYPECASE TYPEP UNLESS WARN WHEN WITH-INPUT-FROM-STRING WITH-OPEN-FILE WITH-OUTPUT-TO-STRING WITH-PACKAGE-ITERATOR WITH-SLOTS WRITE-CHAR)");
	}

	@Test
	void listSpecialFormsReturnsSortedClSpecialForms() {
		assertThat(eval("(rontolisp:list-special-forms)").print()).isEqualTo(
				"(DEFCLASS DEFCONSTANT DEFGENERIC DEFMACRO DEFMETHOD DEFPACKAGE DEFPARAMETER DEFSTRUCT DEFUN DEFVAR FUNCTION GO IF IN-PACKAGE LAMBDA LET PROGN PROGV QUOTE RETURN SETQ TAGBODY UNWIND-PROTECT WHILE)");
	}

	@Test
	void listFunctionsReturnsSortedClFunctions() {
		java.util.List<String> names = symbolNames(eval("(rontolisp:list-functions)"));
		assertThat(names)
			.contains("FIRST", "REST", "NTH", "FUNCALL", "LENGTH", "1+", "CAR", "EVAL", "NOT", "EQUAL", "MAP",
					"MAP-INTO", "MAPC", "EVERY", "SOME", "REMOVE", "REMOVE-IF", "REMOVE-IF-NOT", "FIND", "FIND-IF",
					"FIND-IF-NOT", "POSITION", "POSITION-IF", "COUNT", "COUNT-IF", "MAPCAN", "APPLY", "SORT",
					"MEMBER-IF", "ASSOC-IF", "GETF", "BUTLAST", "REMOVE-DUPLICATES", "NCONC", "IDENTITY", "COPY-LIST",
					"NREVERSE", "MAKE-LIST", "UNION", "INTERSECTION", "SET-DIFFERENCE", "ADJOIN", "LOGAND", "LOGIOR",
					"LOGXOR", "LOGNOT", "ASH", "INTEGER-LENGTH", "LOGBITP", "LIST*", "ACONS", "ENDP", "ELT", "RASSOC",
					"PAIRLIS", "COPY-ALIST", "REVAPPEND", "NRECONC", "MAPLIST", "MAPCON", "MAPL", "NOTANY", "NOTEVERY",
					"DELETE", "DELETE-IF", "DELETE-IF-NOT", "SUBSTITUTE", "NSUBSTITUTE", "FRESH-LINE", "EQUALP",
					"STRING<", "STRING>", "STRING<=", "STRING>=", "STRING/=", "STRING-LESSP", "STRING-GREATERP",
					"STRING-NOT-LESSP", "STRING-NOT-GREATERP", "STRING-NOT-EQUAL")
			.doesNotContain("COND", "QUOTE", "DEFUN", "SETF", "%remf-tail", "CADR", "*package*", "ERROR", "%fmt-pad")
			.contains("RANDOM", "GET-UNIVERSAL-TIME", "GET-INTERNAL-REAL-TIME", "GET-INTERNAL-RUN-TIME", "GETENV")
			.contains("READ-FROM-STRING", "PARSE-INTEGER", "CHAR", "SCHAR", "CHAR-CODE", "CODE-CHAR", "CHAR=", "CHAR<",
					"CHAR<=", "CHAR-UPCASE", "CHAR-DOWNCASE", "CHARACTERP", "ALPHA-CHAR-P", "DIGIT-CHAR-P")
			.contains("MAKE-HASH-TABLE", "GETHASH", "REMHASH", "CLRHASH", "HASH-TABLE-COUNT", "HASH-TABLE-P", "MAPHASH")
			.contains("MAKE-ARRAY", "AREF", "ROW-MAJOR-AREF", "ARRAY-ROW-MAJOR-INDEX")
			.contains("FILL-POINTER", "ARRAY-HAS-FILL-POINTER-P", "ADJUSTABLE-ARRAY-P", "VECTOR-PUSH", "VECTOR-POP",
					"VECTOR-PUSH-EXTEND", "ARRAY-ELEMENT-TYPE")
			.contains("GENSYM", "MACROEXPAND", "MACROEXPAND-1")
			.contains("REQUIRE", "PROVIDE")
			.contains("READ-BYTE", "WRITE-BYTE", "READ-SEQUENCE", "WRITE-SEQUENCE")
			.contains("WRITE-STRING", "WRITE-TO-STRING")
			.contains("SYMBOL-NAME", "INTERN", "FIND-SYMBOL", "MAKE-SYMBOL", "BOUNDP", "FBOUNDP", "SYMBOL-VALUE")
			.contains("BYTE", "BYTE-SIZE", "BYTE-POSITION", "LDB", "DPB")
			.contains("STRING")
			.doesNotContain("%puthash", "%aset", "%row-major-aset", "%make-string-output-stream",
					"%make-string-input-stream", "%string-stream-contents", "%set-fill-pointer", "%string-compare")
			.isSorted()
			.hasSize(302);
	}

	@Test
	void listFunctionsAcceptsAllDesignatorSpellings() {
		LispVal byDefault = eval("(rontolisp:list-functions)");
		assertThat(eval("(rontolisp:list-functions :cl)")).isEqualTo(byDefault);
		assertThat(eval("(rontolisp:list-functions cl)")).isEqualTo(byDefault);
		assertThat(eval("(rontolisp:list-functions \"CL\")")).isEqualTo(byDefault);
		assertThat(eval("(rontolisp:list-functions 'cl)")).isEqualTo(byDefault);
	}

	@Test
	void listFunctionsForClUserReflectsUserDefuns() {
		assertThat(eval("(rontolisp:list-functions :cl-user)")).isEqualTo(LispNil.INSTANCE);
		assertThat(evalMulti("""
				(defun fib (n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
				(defun add2 (a) (+ a 2))
				(rontolisp:list-functions :cl-user)
				""").print()).isEqualTo("(ADD2 FIB)");
	}

	@Test
	void listFunctionsForClUserExcludesShadowingAndInternalNames() {
		// A defun shadowing a cl name is filtered so all backends agree.
		assertThat(evalMulti("(defun length (x) 42) (rontolisp:list-functions :cl-user)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void listFunctionsForRontolispReturnsOwnedFunctions() {
		assertThat(eval("(rontolisp:list-functions :rontolisp)").print()).isEqualTo(
				"(AWAIT CATCH FETCH FINALLY HTTP-HANDLER JSON-PARSE JSON-STRINGIFY LIST-FUNCTIONS LIST-MACROS LIST-SPECIAL-FORMS QUERY-PARAM QUERY-PARAMS TCP-ACCEPT TCP-CONNECT TCP-LISTEN TCP-LOCAL-ADDRESS TCP-LOCAL-PORT TCP-PEER-ADDRESS TCP-PEER-PORT THEN THEN* TLS-CONNECT TLS-LISTEN TLS-LISTEN-PEM URL-DECODE URL-ENCODE URL-PATH URL-QUERY VERSION WIT-ERROR-PAYLOAD WIT-PROVIDE)");
	}

	@Test
	void listFunctionsForJavaReturnsOwnedFunctions() {
		assertThat(eval("(rontolisp:list-functions :java)").print()).isEqualTo("(CALL FIELD NEW PROXY STATIC)");
	}

	@Test
	void listMacrosAndSpecialFormsAreNilForClUserAndRontolisp() {
		assertThat(eval("(rontolisp:list-macros :cl-user)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(rontolisp:list-macros :rontolisp)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(rontolisp:list-macros :java)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(rontolisp:list-special-forms :cl-user)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(rontolisp:list-special-forms :rontolisp)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(rontolisp:list-special-forms :java)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void listFunctionsUnknownPackageThrows() {
		assertThatThrownBy(() -> eval("(rontolisp:list-functions :foo)"))
			.isInstanceOf(am.ik.rontolisp.LispPackageException.class)
			.hasMessageContaining("No such package: FOO");
	}

	@Test
	void listFunctionsUnknownPackageViaFuncallReturnsNil() {
		// The registry of user-defined (defpackage) packages lives in the resolver, so
		// a designator that only becomes known at runtime (funcall) cannot be
		// validated: any non-built-in name is treated as a user-package prefix filter
		// and an unknown one simply yields nil.
		assertThat(eval("(funcall #'rontolisp:list-functions :foo)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void listMacrosWorksViaEvalAndFuncall() {
		LispVal expected = eval("(rontolisp:list-macros)");
		assertThat(eval("(eval '(rontolisp:list-macros))")).isEqualTo(expected);
		assertThat(eval("(funcall #'rontolisp:list-macros :cl)")).isEqualTo(expected);
	}

	@Test
	void unqualifiedIntrospectionWorksInRontolispPackage() {
		assertThat(evalMulti("(in-package :rontolisp) (list-functions :rontolisp)").print()).isEqualTo(
				"(AWAIT CATCH FETCH FINALLY HTTP-HANDLER JSON-PARSE JSON-STRINGIFY LIST-FUNCTIONS LIST-MACROS LIST-SPECIAL-FORMS QUERY-PARAM QUERY-PARAMS TCP-ACCEPT TCP-CONNECT TCP-LISTEN TCP-LOCAL-ADDRESS TCP-LOCAL-PORT TCP-PEER-ADDRESS TCP-PEER-PORT THEN THEN* TLS-CONNECT TLS-LISTEN TLS-LISTEN-PEM URL-DECODE URL-ENCODE URL-PATH URL-QUERY VERSION WIT-ERROR-PAYLOAD WIT-PROVIDE)");
	}

	@Test
	void firstRestNthAreFirstClassFunctions() {
		assertThat(eval("(funcall #'first '(1 2 3))")).isEqualTo(new LispInteger(1));
		assertThat(eval("(funcall #'rest '(1 2 3))").print()).isEqualTo("(2 3)");
		assertThat(eval("(funcall #'nth 1 '(1 2 3))")).isEqualTo(new LispInteger(2));
		assertThat(eval("(funcall #'second '(1 2 3))")).isEqualTo(new LispInteger(2));
		assertThat(eval("(funcall #'third '(1 2 3))")).isEqualTo(new LispInteger(3));
		assertThat(eval("(funcall #'fourth '(1 2 3 4))")).isEqualTo(new LispInteger(4));
		assertThat(eval("(mapcar #'first '((1 2) (3 4)))").print()).isEqualTo("(1 3)");
	}

	@Test
	void packageDefaultsToClUser() {
		assertThat(eval("*package*")).isEqualTo(new LispSymbol("CL-USER"));
	}

	@Test
	void inPackageMakesVersionVisibleUnqualified() {
		assertThat(evalMulti("(in-package :rontolisp) (cl:cadr (version))"))
			.isEqualTo(new LispString(am.ik.rontolisp.Version.getVersion()));
	}

	@Test
	void inPackageUpdatesPackageVar() {
		assertThat(evalMulti("(in-package :rontolisp) cl:*package*")).isEqualTo(new LispSymbol("RONTOLISP"));
	}

	@Test
	void clQualifiedStandardFunctionWorksInRontolisp() {
		assertThat(evalMulti("(in-package :rontolisp) (cl:car '(1 2))")).isEqualTo(new LispInteger(1));
	}

	@Test
	void unqualifiedStandardSymbolInRontolispIsRejected() {
		assertThatThrownBy(() -> evalMulti("(in-package :rontolisp) (car '(1 2))"))
			.isInstanceOf(am.ik.rontolisp.LispPackageException.class)
			.hasMessageContaining("use CL:CAR");
	}

	@Test
	void inPackageCanSwitchBackToClUser() {
		assertThat(evalMulti("(in-package :rontolisp) (in-package :cl-user) *package*"))
			.isEqualTo(new LispSymbol("CL-USER"));
	}

	@Test
	void inPackageUnknownPackageThrows() {
		assertThatThrownBy(() -> eval("(in-package foo)")).isInstanceOf(am.ik.rontolisp.LispPackageException.class)
			.hasMessageContaining("No such package: FOO");
	}

	@Test
	void defpackageDefunAndCallAcrossPackages() {
		assertThat(evalMulti("""
				(defpackage :mypkg (:use :cl) (:export :greet))
				(in-package :mypkg)
				(defun greet (name) (concatenate 'string "hello, " name))
				(in-package :cl-user)
				(mypkg:greet "world")
				""")).isEqualTo(new LispString("hello, world"));
	}

	@Test
	void defpackageFunctionValueWorksAcrossPackages() {
		assertThat(evalMulti("""
				(defpackage :mypkg (:use :cl) (:export :twice))
				(in-package :mypkg)
				(defun twice (x) (* x 2))
				(in-package :cl-user)
				(mapcar #'mypkg:twice '(1 2 3))
				""").print()).isEqualTo("(2 4 6)");
	}

	@Test
	void defpackageInternalFunctionRequiresDoubleColon() {
		String prologue = """
				(defpackage :mypkg (:use :cl))
				(in-package :mypkg)
				(defun helper () 42)
				(in-package :cl-user)
				""";
		assertThat(evalMulti(prologue + "(mypkg::helper)")).isEqualTo(new LispInteger(42));
		assertThatThrownBy(() -> evalMulti(prologue + "(mypkg:helper)"))
			.isInstanceOf(am.ik.rontolisp.LispPackageException.class)
			.hasMessageContaining("The symbol HELPER is not external in the MYPKG package");
	}

	@Test
	void defpackageUseInheritsOnlyExportedSymbols() {
		String prologue = """
				(defpackage :base (:use :cl) (:export :pub))
				(in-package :base)
				(defun pub () 1)
				(defun priv () 2)
				(defpackage :client (:use :cl :base))
				(in-package :client)
				""";
		assertThat(evalMulti(prologue + "(pub)")).isEqualTo(new LispInteger(1));
		// priv is internal to base, so an unqualified priv interns as client's own
		// (undefined) symbol instead of reaching base's function.
		assertThatThrownBy(() -> evalMulti(prologue + "(priv)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("The function CLIENT::PRIV is undefined");
	}

	@Test
	void listFunctionsForUserPackageListsItsDefunsQualified() {
		assertThat(evalMulti("""
				(defpackage :mypkg (:use :cl) (:export :pub))
				(in-package :mypkg)
				(defun pub () 1)
				(defun priv () 2)
				(in-package :cl-user)
				(rontolisp:list-functions :mypkg)
				""").print()).isEqualTo("(MYPKG::PRIV MYPKG:PUB)");
	}

	@Test
	void userPackageDefunsStayOutOfClUserListing() {
		assertThat(evalMulti("""
				(defpackage :mypkg (:use :cl) (:export :pub))
				(in-package :mypkg)
				(defun pub () 1)
				(in-package :cl-user)
				(rontolisp:list-functions :cl-user)
				""")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void fetchReturnsStatusBodyAndResponseHeaders() throws Exception {
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
			.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/hello", exchange -> {
			byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("X-Test", "ok");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			int port = server.getAddress().getPort();
			// fetch returns a future immediately; await resolves it into the plist
			// (:status 200 :headers (...) :body #<STREAM>)
			assertThat(
					eval("(getf (rontolisp:await (rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\")) :status)"))
				.isEqualTo(new LispInteger(200));
			assertThat(eval("(rontolisp:await (rontolisp:read-all"
					+ " (getf (rontolisp:await (rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\")) :body)))"))
				.isEqualTo(new LispString("hello world"));
			assertThat(eval("(rontolisp:streamp (getf (rontolisp:await (rontolisp:fetch \"http://127.0.0.1:" + port
					+ "/hello\")) :body))")
				.print()).isEqualTo("T");
			LispVal headers = eval(
					"(getf (rontolisp:await (rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\")) :headers)");
			// the JDK HttpClient normalizes response header names to lower case
			assertThat(headers.print()).contains("x-test").contains("ok");
			// fetch itself returns an opaque future, not the result; it prints as
			// #<FUTURE> and satisfies futurep
			assertThat(eval("(rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\")"))
				.isInstanceOf(am.ik.rontolisp.LispFuture.class);
			assertThat(eval("(rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\")").print())
				.isEqualTo("#<FUTURE>");
			assertThat(eval("(rontolisp:futurep (rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\"))").print())
				.isEqualTo("T");
			// a settled future can be awaited more than once
			assertThat(eval("(let ((p (rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\")))"
					+ " (rontolisp:await p) (getf (rontolisp:await p) :status))"))
				.isEqualTo(new LispInteger(200));
			// two in-flight futures resolve independently
			assertThat(eval("(let ((p1 (rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\"))"
					+ " (p2 (rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\")))"
					+ " (list (getf (rontolisp:await p2) :status) (getf (rontolisp:await p1) :status)))")
				.print()).isEqualTo("(200 200)");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void tcpEchoRoundTripOnLoopback() {
		// Single-threaded choreography: connect before accept (the connection sits in
		// the listen backlog) and write before the peer reads (small payloads sit in
		// the kernel socket buffers), so nothing deadlocks.
		String program = """
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port)))
				  (write-line "hello" client)
				  (let* ((server (rontolisp:tcp-accept listener))
				         (line (read-line server)))
				    (write-line line server)
				    (let ((reply (read-line client)))
				      (close server)
				      (close client)
				      (close listener)
				      reply)))
				""";
		assertThat(eval(program)).isEqualTo(new LispString("hello"));
	}

	@Test
	void tcpByteOpsOnSocket() {
		String program = """
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port)))
				  (write-byte 65 client)
				  (let* ((server (rontolisp:tcp-accept listener))
				         (b (read-byte server)))
				    (close server)
				    (close client)
				    (close listener)
				    b))
				""";
		assertThat(eval(program)).isEqualTo(new LispInteger(65));
	}

	@Test
	void tcpReadLineReturnsNilAfterPeerClose() {
		String program = """
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port))
				       (server (rontolisp:tcp-accept listener)))
				  (close client)
				  (let ((line (read-line server)))
				    (close server)
				    (close listener)
				    line))
				""";
		assertThat(eval(program)).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void tcpConnectRefusedSignalsError() {
		// Listen on an ephemeral port, close it, then connect to the now-free port.
		String program = """
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener)))
				  (close listener)
				  (rontolisp:tcp-connect "127.0.0.1" port))
				""";
		assertThatThrownBy(() -> eval(program)).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("tcp-connect");
	}

	@Test
	void tcpArgumentValidation() {
		assertThatThrownBy(() -> eval("(rontolisp:tcp-connect \"127.0.0.1\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("TCP-CONNECT");
		assertThatThrownBy(() -> eval("(rontolisp:tcp-connect 80 80)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a string host");
		assertThatThrownBy(() -> eval("(rontolisp:tcp-listen \"nope\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects an integer port");
		assertThatThrownBy(() -> eval("(rontolisp:tcp-accept 12345)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a listener handle");
		assertThatThrownBy(() -> eval("(rontolisp:tcp-local-port 12345)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a socket or listener handle");
		// tcp-local-port on a file stream handle is rejected too
		assertThatThrownBy(() -> evalMulti("""
				(setq h (open "/dev/null" :input))
				(rontolisp:tcp-local-port h)
				""")).isInstanceOf(LispEvalException.class).hasMessageContaining("expects a socket or listener handle");
	}

	@Test
	void tcpAddressAccessorsOnLoopback() {
		String program = """
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port))
				       (server (rontolisp:tcp-accept listener))
				       (result (list (rontolisp:tcp-local-address listener)
				                     (rontolisp:tcp-peer-address client)
				                     (rontolisp:tcp-peer-port client)
				                     (rontolisp:tcp-peer-address server))))
				  (close server)
				  (close client)
				  (close listener)
				  result)
				""";
		LispVal result = eval(program);
		assertThat(result.print()).isEqualTo("(\"127.0.0.1\" \"127.0.0.1\" " + portOf(result) + " \"127.0.0.1\")");
	}

	private static long portOf(LispVal accessorResult) {
		// The third element of the tcpAddressAccessorsOnLoopback result list: the
		// ephemeral server port the client is connected to.
		LispCons cons = (LispCons) accessorResult;
		LispCons rest = (LispCons) ((LispCons) cons.cdr()).cdr();
		return ((LispInteger) rest.car()).value();
	}

	@Test
	void tcpAddressAccessorArgumentValidation() {
		assertThatThrownBy(() -> eval("(rontolisp:tcp-peer-address 12345)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a connected socket handle");
		assertThatThrownBy(() -> eval("(rontolisp:tcp-peer-port 12345)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a connected socket handle");
		assertThatThrownBy(() -> eval("(rontolisp:tcp-local-address 12345)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a socket or listener handle");
		// A listener is not a connected socket, so the peer accessors reject it.
		assertThatThrownBy(() -> evalMulti("""
				(setq l (rontolisp:tcp-listen 0 "127.0.0.1"))
				(rontolisp:tcp-peer-address l)
				""")).isInstanceOf(LispEvalException.class).hasMessageContaining("expects a connected socket handle");
	}

	@Test
	void usocketEchoOverLoopback() {
		// The Postmodern (cl-postgres) shape: socket-connect with :element-type,
		// socket-stream, stream I/O, socket-close. Same single-threaded loopback
		// choreography as the tcp tests.
		String program = """
				(let* ((listener (usocket:socket-listen "127.0.0.1" usocket:*auto-port*))
				       (port (usocket:get-local-port listener))
				       (client (usocket:socket-connect "127.0.0.1" port :element-type '(unsigned-byte 8))))
				  (write-line "hello" (usocket:socket-stream client))
				  (let* ((server (usocket:socket-accept listener))
				         (line (read-line (usocket:socket-stream server))))
				    (usocket:socket-close server)
				    (usocket:socket-close client)
				    (usocket:socket-close listener)
				    line))
				""";
		assertThat(eval(program)).isEqualTo(new LispString("hello"));
	}

	@Test
	void usocketAddressAccessors() {
		String program = """
				(let* ((listener (usocket:socket-listen "127.0.0.1" usocket:*auto-port*))
				       (port (usocket:get-local-port listener))
				       (client (usocket:socket-connect "127.0.0.1" port))
				       (server (usocket:socket-accept listener))
				       (result (list (usocket:get-peer-address client)
				                     (= (usocket:get-peer-port client) port)
				                     (usocket:get-local-address listener)
				                     (usocket:get-peer-name server))))
				  (usocket:socket-close server)
				  (usocket:socket-close client)
				  (usocket:socket-close listener)
				  result)
				""";
		assertThat(eval(program).print()).isEqualTo("(\"127.0.0.1\" T \"127.0.0.1\" \"127.0.0.1\")");
	}

	@Test
	void usocketWildcardHostListens() {
		String program = """
				(let* ((listener (usocket:socket-listen usocket:*wildcard-host* 0))
				       (port (usocket:get-local-port listener)))
				  (usocket:socket-close listener)
				  (> port 0))
				""";
		assertThat(eval(program)).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void usocketVariableFirstReferenceLoadsLibrary() {
		// The program's FIRST usocket reference is a variable read, not a function
		// call: the evalSymbolRef lazy-load hook must fire.
		assertThat(eval("usocket:*wildcard-host*")).isEqualTo(new LispString("0.0.0.0"));
	}

	@Test
	void usocketSocketConnectRejectsDatagram() {
		assertThatThrownBy(() -> eval("(usocket:socket-connect \"127.0.0.1\" 9 :protocol :datagram)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("UDP");
	}

	@Test
	void usocketGetLocalNameSuppliesBothValuesToMultipleValueBind() {
		// The literal (values ...) tail flows through the multiple-values tier's
		// user-function channel, so both the address and the port come through.
		String program = """
				(let* ((listener (usocket:socket-listen "127.0.0.1" 0))
				       (port (usocket:get-local-port listener)))
				  (multiple-value-bind (addr p) (usocket:get-local-name listener)
				    (usocket:socket-close listener)
				    (list addr (= p port))))
				""";
		assertThat(eval(program).print()).isEqualTo("(\"127.0.0.1\" T)");
	}

	@Test
	void usocketWithMacrosEchoOverLoopback() {
		// with-socket-listener + with-client-socket + with-connected-socket compose;
		// each closes its handle on normal exit (lite: no unwind-protect, so a body
		// error would leak -- documented).
		String program = """
				(usocket:with-socket-listener (listener "127.0.0.1" 0)
				  (usocket:with-client-socket (client stream "127.0.0.1" (usocket:get-local-port listener)
				                               :element-type '(unsigned-byte 8))
				    (write-line "ping" stream)
				    (usocket:with-connected-socket (server (usocket:socket-accept listener))
				      (read-line server))))
				""";
		assertThat(eval(program)).isEqualTo(new LispString("ping"));
	}

	@Test
	void usocketWithConnectedSocketClosesOnNormalExit() {
		// After the body, the socket handle is closed: a subsequent read-line on it
		// signals instead of blocking.
		String program = """
				(let* ((listener (usocket:socket-listen "127.0.0.1" 0))
				       (client (usocket:socket-connect "127.0.0.1" (usocket:get-local-port listener)))
				       (kept nil))
				  (usocket:with-connected-socket (server (usocket:socket-accept listener))
				    (setq kept server))
				  (usocket:socket-close client)
				  (usocket:socket-close listener)
				  (read-line kept))
				""";
		assertThatThrownBy(() -> eval(program)).isInstanceOf(LispEvalException.class);
	}

	@Test
	void handlerCaseCatchesTypedErrorByClass() {
		assertThat(evalMulti("""
				(define-condition hc-err (error) ((v :initarg :v :reader hc-err-v)))
				(handler-case (error 'hc-err :v 7)
				  (hc-err (e) (list :caught (hc-err-v e))))
				""").print()).isEqualTo("(:CAUGHT 7)");
	}

	@Test
	void handlerCaseCatchesPlainErrorAsError() {
		assertThat(eval("""
				(handler-case (error "boom ~a" 1)
				  (error (e) (list :caught (nth 1 e))))
				""").print()).isEqualTo("(:CAUGHT \"boom 1\")");
	}

	@Test
	void handlerCaseDispatchesByHierarchyAndClauseOrder() {
		assertThat(evalMulti("""
				(define-condition hc-sub (parse-error) ())
				(handler-case (error 'hc-sub)
				  (warning (w) :warning)
				  (parse-error (e) :parse)
				  (error (e) :error))
				""").print()).isEqualTo(":PARSE");
	}

	@Test
	void handlerCaseRethrowsUnmatchedToOuterHandler() {
		assertThat(evalMulti("""
				(define-condition hc-warn2 (warning) ())
				(handler-case
				    (handler-case (error 'hc-warn2)
				      (error (e) :inner))
				  (warning (w) :outer))
				""").print()).isEqualTo(":OUTER");
	}

	@Test
	void handlerCaseUnmatchedErrorAborts() {
		assertThatThrownBy(() -> eval("(handler-case (error \"boom\") (warning (w) :w))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("boom");
	}

	@Test
	void handlerCaseNoErrorClauseReceivesValue() {
		assertThat(eval("(handler-case (+ 1 2) (error (e) :err) (:no-error (v) (list :ok v)))").print())
			.isEqualTo("(:OK 3)");
	}

	@Test
	void handlerCaseValueWithoutClauses() {
		assertThat(eval("(handler-case (+ 1 2) (error (e) :err))")).isEqualTo(new LispInteger(3));
	}

	@Test
	void handlerCaseCatchesSignalAndSignalFallsThroughOtherwise() {
		assertThat(eval("(handler-case (progn (signal \"quiet\") :not-raised) (condition (c) :raised))").print())
			.isEqualTo(":RAISED");
		assertThat(eval("(signal \"quiet\")")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void handlerCaseRunsUnwindProtectCleanupBeforeHandler() {
		assertThat(eval("""
				(let ((log nil))
				  (handler-case
				      (unwind-protect (error "boom") (setq log (cons :cleaned log)))
				    (error (e) (cons :caught log))))
				""").print()).isEqualTo("(:CAUGHT :CLEANED)");
	}

	@Test
	void ignoreErrorsYieldsNilOnErrorAndValueOtherwise() {
		assertThat(eval("(ignore-errors (error \"boom\"))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(ignore-errors (+ 1 2))")).isEqualTo(new LispInteger(3));
	}

	@Test
	void handlerCaseCatchesUsocketSocketErrorOnConnectToClosedPort() {
		// A connection failure is re-signaled as a typed usocket:socket-error, catchable
		// by type (.kb/error-handling.md).
		assertThat(eval("""
				(let* ((l (usocket:socket-listen "127.0.0.1" 0))
				       (p (usocket:get-local-port l)))
				  (usocket:socket-close l)
				  (handler-case (usocket:socket-connect "127.0.0.1" p)
				    (usocket:socket-error (e) :refused)))
				""").print()).isEqualTo(":REFUSED");
	}

	@Test
	void usocketConnectFailureMessageIsPreservedWhenUncaught() {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.eval(LispReader.readFromString(
				"(setq up-port (let ((l (usocket:socket-listen \"127.0.0.1\" 0))) (let ((p (usocket:get-local-port l))) (usocket:socket-close l) p)))"));
		assertThatThrownBy(
				() -> evaluator.eval(LispReader.readFromString("(usocket:socket-connect \"127.0.0.1\" up-port)")))
			.isInstanceOfSatisfying(LispEvalException.class, e -> {
				assertThat(e.getMessage()).contains("tcp-connect");
				assertThat(e.condition()).isNotNull();
			});
	}

	@Test
	void typedErrorCarriesConditionInstanceAndLegacyMessage() {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.eval(LispReader.readFromString("(define-condition my-cond-err (error) ((v :initarg :v)))"));
		assertThatThrownBy(() -> evaluator.eval(LispReader.readFromString("(error 'my-cond-err :v 42)")))
			.isInstanceOfSatisfying(LispEvalException.class, e -> {
				assertThat(e.getMessage()).isEqualTo("Condition (MY-COND-ERR :V 42) was signalled.");
				assertThat(e.condition()).isNotNull();
				assertThat(java.util.Objects.requireNonNull(e.condition()).print())
					.isEqualTo("(%class-MY-COND-ERR 42)");
			});
	}

	@Test
	void defineConditionReportStringBecomesMessage() {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.eval(LispReader.readFromString("(define-condition my-cond-rep (error) () (:report \"it broke\"))"));
		assertThatThrownBy(() -> evaluator.eval(LispReader.readFromString("(error 'my-cond-rep)")))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("it broke");
	}

	@Test
	void defineConditionReportLambdaRendersMessage() {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.eval(LispReader.readFromString("""
				(define-condition my-cond-lam (error) ((v :initarg :v :reader my-cond-lam-v))
				  (:report (lambda (c s) (format s "bad value ~a" (my-cond-lam-v c)))))
				"""));
		assertThatThrownBy(() -> evaluator.eval(LispReader.readFromString("(error 'my-cond-lam :v 42)")))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("bad value 42");
	}

	@Test
	void makeConditionBuildsTypedInstance() {
		assertThat(eval("(make-condition 'simple-error :format-control \"x\")").print())
			.isEqualTo("(%class-SIMPLE-ERROR \"x\" NIL)");
	}

	@Test
	void errorWithConditionObjectSignalsItsCarriedMessage() {
		assertThatThrownBy(() -> eval("(error (make-condition 'simple-error :format-control \"boom obj\"))"))
			.isInstanceOfSatisfying(LispEvalException.class, e -> {
				assertThat(e.getMessage()).isEqualTo("boom obj");
				assertThat(e.condition()).isNotNull();
			});
	}

	@Test
	void errorWithRuntimeStringSignalsItAsMessage() {
		assertThatThrownBy(() -> eval("(let ((m \"runtime msg\")) (error m))")).isInstanceOf(LispEvalException.class)
			.hasMessage("runtime msg");
	}

	@Test
	void signalReturnsNilWhenUnhandled() {
		assertThat(eval("(signal \"quiet\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(evalMulti("""
				(define-condition my-cond-sig (condition) ())
				(signal 'my-cond-sig)
				""")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void typecaseMatchesConditionClassesByHierarchy() {
		assertThat(evalMulti("""
				(define-condition my-cond-tc (error) ())
				(typecase (make-condition 'my-cond-tc) (warning 'w) (error 'e) (t 'o))
				""").print()).isEqualTo("E");
	}

	@Test
	void withSlotsReadsInstanceSlots() {
		assertThat(evalMulti("""
				(define-condition my-cond-ws (error) ((a :initarg :a) (b :initarg :b)))
				(with-slots (a b) (make-condition 'my-cond-ws :a 1 :b 2) (list a b))
				""").print()).isEqualTo("(1 2)");
	}

	@Test
	void unwindProtectReturnsProtectedValueAfterCleanup() {
		assertThat(eval("(let ((n 1)) (list (unwind-protect (+ n 1) (setq n 10)) n))").print()).isEqualTo("(2 10)");
	}

	@Test
	void unwindProtectRunsCleanupOnErrorUnwind() {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.eval(LispReader.readFromString("(setq up-cleaned nil)"));
		assertThatThrownBy(() -> evaluator
			.eval(LispReader.readFromString("(unwind-protect (error \"boom\") (setq up-cleaned t))")))
			.isInstanceOf(LispEvalException.class);
		assertThat(evaluator.eval(LispReader.readFromString("up-cleaned"))).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void unwindProtectRunsCleanupOnErrorFromCalledFunction() {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.eval(LispReader.readFromString("(setq up-cleaned nil)"));
		evaluator.eval(LispReader.readFromString("(defun up-thrower () (error \"deep\"))"));
		assertThatThrownBy(
				() -> evaluator.eval(LispReader.readFromString("(unwind-protect (up-thrower) (setq up-cleaned t))")))
			.isInstanceOf(LispEvalException.class);
		assertThat(evaluator.eval(LispReader.readFromString("up-cleaned"))).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void unwindProtectRunsCleanupOnReturnExit() {
		assertThat(eval("""
				(let ((log nil))
				  (dolist (x '(1 2 3))
				    (unwind-protect
				        (when (= x 2) (return))
				      (setq log (cons x log))))
				  log)
				""").print()).isEqualTo("(2 1)");
	}

	@Test
	void unwindProtectRunsCleanupOnReturnFromDefun() {
		assertThat(evalMulti("""
				(setq up-log nil)
				(defun up-f ()
				  (unwind-protect (return-from up-f :early) (setq up-log :cleaned)))
				(list (up-f) up-log)
				""").print()).isEqualTo("(:EARLY :CLEANED)");
	}

	@Test
	void unwindProtectNestedCleanupsRunInnermostFirst() {
		assertThat(eval("""
				(let ((log nil))
				  (dolist (x '(1))
				    (unwind-protect
				        (unwind-protect (return) (setq log (cons :inner log)))
				      (setq log (cons :outer log))))
				  log)
				""").print()).isEqualTo("(:OUTER :INNER)");
	}

	@Test
	void unwindProtectCleanupErrorReplacesPendingUnwind() {
		// CL semantics: a cleanup that itself signals replaces the pending unwind.
		assertThatThrownBy(() -> eval("(unwind-protect (error \"first\") (error \"second\"))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("second");
	}

	@Test
	void withOutputToStringClosesStreamOnErrorUnwind() {
		// The stream handle is released by the unwind-protect cleanup: writing to it
		// after the error signals instead of appending.
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.eval(LispReader.readFromString("(setq up-kept nil)"));
		assertThatThrownBy(() -> evaluator.eval(LispReader
			.readFromString("(with-output-to-string (s) (setq up-kept s) (princ \"x\" s) (error \"boom\"))")))
			.isInstanceOf(LispEvalException.class);
		assertThatThrownBy(() -> evaluator.eval(LispReader.readFromString("(princ \"y\" up-kept)")))
			.isInstanceOf(LispEvalException.class);
	}

	@Test
	void usocketWithConnectedSocketClosesOnErrorExit() {
		// The error-path sibling of usocketWithConnectedSocketClosesOnNormalExit: the
		// unwind-protect cleanup closes the socket even when the body signals.
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.eval(LispReader.readFromString("(setq up-sock-listener (usocket:socket-listen \"127.0.0.1\" 0))"));
		evaluator.eval(LispReader.readFromString(
				"(setq up-sock-client (usocket:socket-connect \"127.0.0.1\" (usocket:get-local-port up-sock-listener)))"));
		evaluator.eval(LispReader.readFromString("(setq up-sock-kept nil)"));
		assertThatThrownBy(() -> evaluator.eval(LispReader.readFromString("""
				(usocket:with-connected-socket (server (usocket:socket-accept up-sock-listener))
				  (setq up-sock-kept server)
				  (error "boom"))
				"""))).isInstanceOf(LispEvalException.class);
		assertThatThrownBy(() -> evaluator.eval(LispReader.readFromString("(read-line up-sock-kept)")))
			.isInstanceOf(LispEvalException.class);
		evaluator.eval(LispReader.readFromString("(usocket:socket-close up-sock-client)"));
		evaluator.eval(LispReader.readFromString("(usocket:socket-close up-sock-listener)"));
	}

	@Test
	void usocketWithServerSocketIsAliasOfWithConnectedSocket() {
		String program = """
				(let ((listener (usocket:socket-listen "127.0.0.1" 0)))
				  (usocket:with-server-socket (l listener)
				    (> (usocket:get-local-port l) 0)))
				""";
		assertThat(eval(program)).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void tlsEchoRoundTripOnLoopback() throws Exception {
		// A TLS handshake needs the server to participate concurrently (unlike the
		// plain-TCP backlog trick), so the echo peer runs on a background thread. The
		// self-signed certificate is trusted by pointing javax.net.ssl.trustStore at
		// the test keystore; tls-connect re-reads it per call.
		am.ik.rontolisp.TlsTestSupport.withTrustStore(() -> {
			try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
				Thread echo = am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
				String program = """
						(let ((client (rontolisp:tls-connect "127.0.0.1" %d)))
						  (write-line "hello over tls" client)
						  (let ((reply (read-line client)))
						    (close client)
						    reply))
						""".formatted(server.getLocalPort());
				assertThat(eval(program)).isEqualTo(new LispString("hello over tls"));
				echo.join();
			}
		});
	}

	@Test
	void tlsConnectUntrustedCertificateSignalsError() throws Exception {
		// Without the trust-store override the JDK default trust store does not trust
		// the self-signed server certificate, so the handshake must fail with an error.
		try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
			am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
			String program = "(rontolisp:tls-connect \"127.0.0.1\" " + server.getLocalPort() + ")";
			assertThatThrownBy(() -> eval(program)).isInstanceOf(LispEvalException.class)
				.hasMessageContaining("tls-connect");
		}
	}

	@Test
	void tlsConnectRefusedSignalsError() {
		// Listen on an ephemeral port, close it, then connect to the now-free port.
		String program = """
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener)))
				  (close listener)
				  (rontolisp:tls-connect "127.0.0.1" port))
				""";
		assertThatThrownBy(() -> eval(program)).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("tls-connect");
	}

	@Test
	void tlsListenEchoRoundTripOnLoopback() throws Exception {
		// The Lisp side is the TLS *server* (tls-listen + the plain tcp-accept); the
		// peer is a Java client thread that trusts the self-signed certificate
		// directly and retries connecting until the listener is bound. The accepted
		// socket handshakes lazily on the first read.
		int port = am.ik.rontolisp.TlsTestSupport.freePort();
		Thread client = am.ik.rontolisp.TlsTestSupport.startOneShotEchoClient(port, "hello over server tls");
		String program = """
				(let* ((listener (rontolisp:tls-listen "%s" "%s" %d "127.0.0.1"))
				       (bound-port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-accept listener))
				       (line (read-line client)))
				  (write-line line client)
				  (close client)
				  (close listener)
				  (list bound-port line))
				""".formatted(am.ik.rontolisp.TlsTestSupport.keyStore(), am.ik.rontolisp.TlsTestSupport.STORE_PASSWORD,
				port);
		assertThat(eval(program).print()).isEqualTo("(" + port + " \"hello over server tls\")");
		client.join();
	}

	@Test
	void tlsListenPemEchoRoundTripOnLoopback() throws Exception {
		// The Lisp side is the TLS server configured from PEM files (cert + PKCS#8
		// key); the peer trusts the same self-signed certificate. The PEM cert is the
		// very certificate the shared keystore holds, so the existing echo client
		// trusts it unchanged.
		int port = am.ik.rontolisp.TlsTestSupport.freePort();
		java.nio.file.Path[] pem = am.ik.rontolisp.TlsTestSupport.pemFiles();
		Thread client = am.ik.rontolisp.TlsTestSupport.startOneShotEchoClient(port, "hello over pem tls");
		String program = """
				(let* ((listener (rontolisp:tls-listen-pem "%s" "%s" %d "127.0.0.1"))
				       (client (rontolisp:tcp-accept listener))
				       (line (read-line client)))
				  (write-line line client)
				  (close client)
				  (close listener)
				  line)
				""".formatted(pem[0], pem[1], port);
		assertThat(eval(program)).isEqualTo(new LispString("hello over pem tls"));
		client.join();
	}

	@Test
	void tlsListenPemBadFilesSignalsError() {
		assertThatThrownBy(
				() -> eval("(rontolisp:tls-listen-pem \"/no/such/cert.pem\" \"/no/such/key.pem\" 0 \"127.0.0.1\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("tls-listen-pem");
	}

	@Test
	void tlsListenPemArgumentValidation() {
		assertThatThrownBy(() -> eval("(rontolisp:tls-listen-pem \"c.pem\" \"k.pem\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("TLS-LISTEN-PEM");
		assertThatThrownBy(() -> eval("(rontolisp:tls-listen-pem 1 \"k.pem\" 0)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a string certificate path");
		assertThatThrownBy(() -> eval("(rontolisp:tls-listen-pem \"c.pem\" 1 0)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a string key path");
		assertThatThrownBy(() -> eval("(rontolisp:tls-listen-pem \"c.pem\" \"k.pem\" \"nope\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects an integer port");
	}

	@Test
	void tlsListenBadKeyStoreSignalsError() {
		assertThatThrownBy(() -> eval("(rontolisp:tls-listen \"/no/such/keystore.p12\" \"changeit\" 0 \"127.0.0.1\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("tls-listen");
	}

	@Test
	void tlsListenArgumentValidation() {
		assertThatThrownBy(() -> eval("(rontolisp:tls-listen \"ks.p12\" \"pw\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("TLS-LISTEN");
		assertThatThrownBy(() -> eval("(rontolisp:tls-listen 1 \"pw\" 0)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a string keystore path");
		assertThatThrownBy(() -> eval("(rontolisp:tls-listen \"ks.p12\" 1 0)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a string password");
		assertThatThrownBy(() -> eval("(rontolisp:tls-listen \"ks.p12\" \"pw\" \"nope\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects an integer port");
	}

	@Test
	void tlsArgumentValidation() {
		assertThatThrownBy(() -> eval("(rontolisp:tls-connect \"127.0.0.1\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("TLS-CONNECT");
		assertThatThrownBy(() -> eval("(rontolisp:tls-connect 443 443)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a string host");
		assertThatThrownBy(() -> eval("(rontolisp:tls-connect \"127.0.0.1\" \"nope\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects an integer port");
		// the only accepted option is the :insecure keyword-value pair (arity 4)
		assertThatThrownBy(() -> eval("(rontolisp:tls-connect \"127.0.0.1\" 443 :insecure)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("TLS-CONNECT");
		assertThatThrownBy(() -> eval("(rontolisp:tls-connect \"127.0.0.1\" 443 :verify t)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects :insecure");
	}

	@Test
	void tlsConnectInsecureSkipsCertificateVerification() throws Exception {
		// The self-signed server certificate is NOT in any trust store here;
		// :insecure t must skip both the chain validation and the hostname check.
		try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
			Thread echo = am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
			String program = """
					(let ((client (rontolisp:tls-connect "127.0.0.1" %d :insecure t)))
					  (write-line "hello insecurely" client)
					  (let ((reply (read-line client)))
					    (close client)
					    reply))
					""".formatted(server.getLocalPort());
			assertThat(eval(program)).isEqualTo(new LispString("hello insecurely"));
			echo.join();
		}
	}

	@Test
	void tlsConnectInsecureNilStillVerifies() throws Exception {
		// :insecure nil is the verifying default, so the untrusted self-signed
		// certificate must still fail the handshake.
		try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
			am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
			String program = "(rontolisp:tls-connect \"127.0.0.1\" " + server.getLocalPort() + " :insecure nil)";
			assertThatThrownBy(() -> eval(program)).isInstanceOf(LispEvalException.class)
				.hasMessageContaining("tls-connect");
		}
	}

	@Test
	void fetchSendsRequestHeaders() throws Exception {
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
			.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/echo", exchange -> {
			String received = exchange.getRequestHeaders().getFirst("X-Custom");
			byte[] body = ("got:" + received).getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			int port = server.getAddress().getPort();
			LispVal result = eval("(rontolisp:await (rontolisp:read-all (getf (rontolisp:await (rontolisp:fetch"
					+ " \"http://127.0.0.1:" + port
					+ "/echo\" (list :headers (list (cons \"X-Custom\" \"abc\"))))) :body)))");
			assertThat(result).isEqualTo(new LispString("got:abc"));
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void fetchSendsMethodAndBody() throws Exception {
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
			.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/post", exchange -> {
			String received = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			byte[] body = (exchange.getRequestMethod() + ":" + received).getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			int port = server.getAddress().getPort();
			LispVal result = eval("(rontolisp:await (rontolisp:read-all (getf (rontolisp:await (rontolisp:fetch"
					+ " \"http://127.0.0.1:" + port + "/post\" (list :method \"POST\" :body \"hello\"))) :body)))");
			assertThat(result).isEqualTo(new LispString("POST:hello"));
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void fetchRejectsUnsupportedMethod() {
		assertThatThrownBy(() -> eval("(rontolisp:fetch \"http://127.0.0.1:1/x\" (list :method \"FOO\"))"))
			.hasMessageContaining("unsupported method");
	}

	@Test
	void fetchRejectsWrongArgCount() {
		assertThatThrownBy(() -> eval("(rontolisp:fetch)")).hasMessageContaining("FETCH");
	}

	@Test
	void fetchFailureSignalsAtAwaitTime() {
		// a refused connection settles the promise exceptionally; the error surfaces
		// when the promise is awaited (like a JavaScript await rejection), not at fetch
		assertThatThrownBy(() -> eval("(rontolisp:await (rontolisp:fetch \"http://127.0.0.1:1/x\"))"))
			.isInstanceOf(LispEvalException.class);
	}

	@Test
	void awaitPassesNonPromiseThrough() {
		// like JavaScript await, a non-promise value is returned unchanged
		assertThat(eval("(rontolisp:await 42)")).isEqualTo(new LispInteger(42));
		assertThat(eval("(rontolisp:await \"p\")")).isEqualTo(new LispString("p"));
		assertThat(eval("(rontolisp:await nil)").print()).isEqualTo("NIL");
		assertThatThrownBy(() -> eval("(rontolisp:await)")).hasMessageContaining("AWAIT");
	}

	@Test
	void promisepIsGone() {
		// The promise-era promisep was deleted in the async/await redesign: futures
		// are the one asynchronous value (rontolisp:futurep). The name rontolisp:then
		// was later restored as a future-as-value combinator on top of the async
		// surface -- exercised by AsyncEvalTest -- so only promisep is expected to
		// fail resolution now.
		assertThatThrownBy(() -> eval("(rontolisp:promisep 42)"))
			.hasMessageContaining("not external in the RONTOLISP package");
	}

	// Characters and string/number parsing

	private String capture(String input) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		for (LispVal expr : LispReader.readAllFromString(input)) {
			evaluator.eval(expr);
		}
		return baos.toString();
	}

	@Test
	void evalCharLiteralCodeRoundTrip() {
		assertThat(eval("#\\a")).isEqualTo(new LispChar('a'));
		assertThat(eval("(char-code #\\A)")).isEqualTo(new LispInteger(65));
		assertThat(eval("(code-char 66)")).isEqualTo(new LispChar('B'));
	}

	@Test
	void evalCharNamedLiterals() {
		assertThat(eval("(char-code #\\Space)")).isEqualTo(new LispInteger(32));
		assertThat(eval("(char-code #\\Newline)")).isEqualTo(new LispInteger(10));
		assertThat(eval("(char-code #\\Tab)")).isEqualTo(new LispInteger(9));
		assertThat(eval("(char-code #\\()")).isEqualTo(new LispInteger('('));
	}

	@Test
	void evalCharIndexingAndCaseFolding() {
		assertThat(eval("(char \"hello\" 1)")).isEqualTo(new LispChar('e'));
		assertThat(eval("(schar \"hello\" 0)")).isEqualTo(new LispChar('h'));
		assertThat(eval("(char-upcase #\\a)")).isEqualTo(new LispChar('A'));
		assertThat(eval("(char-downcase #\\Z)")).isEqualTo(new LispChar('z'));
	}

	// A CHARACTER is a Unicode code point: (code-char 128512) is U+1F600 unchanged, and
	// char-code round-trips. Pins the interpreter half of todo 153; see
	// .kb/characters-code-points.md.
	@Test
	void evalCharBeyondBmpCodePoint() {
		assertThat(eval("(code-char 128512)")).isEqualTo(new LispChar(0x1F600));
		assertThat(eval("(char-code (code-char 128512))")).isEqualTo(new LispInteger(0x1F600));
		assertThat(eval("(string (code-char 233))"))
			.isEqualTo(new am.ik.rontolisp.LispString(new String(Character.toChars(233))));
		assertThat(eval("(string (code-char 128512))"))
			.isEqualTo(new am.ik.rontolisp.LispString(new String(Character.toChars(128512))));
	}

	// Full-Unicode case fold via Character.toUpperCase(int) / toLowerCase(int).
	@Test
	void evalCharCaseFoldBeyondAscii() {
		assertThat(eval("(char-upcase (code-char 233))")).isEqualTo(new LispChar(201));
		assertThat(eval("(char-downcase (code-char 201))")).isEqualTo(new LispChar(233));
		assertThat(eval("(char-upcase (code-char 945))")).isEqualTo(new LispChar(913));
		assertThat(eval("(char-downcase (code-char 1040))")).isEqualTo(new LispChar(1072));
	}

	// String indexing (length / char / subseq) walks BY CODE POINT; a supplementary
	// code point is one indexed character.
	@Test
	void evalStringIndexingByCodePoint() {
		assertThat(eval("(length \"😀\")")).isEqualTo(new LispInteger(1));
		assertThat(eval("(length \"aé😀b\")")).isEqualTo(new LispInteger(4));
		assertThat(eval("(char-code (char \"aé😀b\" 2))")).isEqualTo(new LispInteger(0x1F600));
		assertThat(eval("(subseq \"aé😀b\" 1 3)")).isEqualTo(new am.ik.rontolisp.LispString("é😀"));
	}

	// read-char over a supplementary code point reads it as one CHARACTER (a full
	// code point), not as its high UTF-16 surrogate: BufferedReader.read() delivers
	// two code units, the runtime combines them via mark(1)/reset() on the low half.
	@Test
	void evalReadCharCombinesSurrogatePairsOnSupplementaryCodePoint() {
		assertThat(evalMulti("""
				(with-input-from-string (s "😀X")
				  (let* ((c1 (read-char s))
				         (c2 (read-char s))
				         (c3 (read-char s nil :eof)))
				    (list (char-code c1) (char-code c2) c3)))
				""")).isEqualTo(evalMulti("(list 128512 88 :eof)"));
	}

	// Mutation (setf (schar s i) c) / (setf (aref s i) c) accepts a supplementary
	// code point in one indexed slot, matching the JVM and WASM char-vec models.
	// A mutable string of :element-type 'character is one code point per slot on
	// every backend now.
	@Test
	void evalStringMutationSupplementaryCodePointRoundTrip() {
		assertThat(evalMulti("""
				(let ((s (make-string 1 :initial-element #\\a)))
				  (setf (schar s 0) (code-char 128512))
				  (list (length s) (char-code (schar s 0)) s))
				""")).isEqualTo(evalMulti("(list 1 128512 \"😀\")"));
		assertThat(evalMulti("""
				(let ((s (make-array 3 :element-type 'character :initial-element #\\a)))
				  (setf (aref s 1) (code-char 128512))
				  (list (length s) (char-code (aref s 1)) s))
				""")).isEqualTo(evalMulti("(list 3 128512 \"a😀a\")"));
		// vector-push-extend of an astral char lands in exactly one slot.
		assertThat(evalMulti("""
				(let ((s (make-array 0 :element-type 'character :fill-pointer 0 :adjustable t)))
				  (vector-push-extend #\\a s)
				  (vector-push-extend (code-char 128512) s)
				  (vector-push-extend #\\b s)
				  (list (length s) (char-code (aref s 1)) s))
				""")).isEqualTo(evalMulti("(list 3 128512 \"a😀b\")"));
	}

	@Test
	void evalCharComparisons() {
		assertThat(eval("(char= #\\a #\\a)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(char= #\\a #\\b)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(char< #\\a #\\b #\\c)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(char<= #\\a #\\a #\\b)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(char< #\\b #\\a)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalCharPredicates() {
		assertThat(eval("(characterp #\\a)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(characterp 5)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(alpha-char-p #\\x)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(alpha-char-p #\\5)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(digit-char-p #\\7)")).isEqualTo(new LispInteger(7));
		assertThat(eval("(digit-char-p #\\f 16)")).isEqualTo(new LispInteger(15));
		assertThat(eval("(digit-char-p #\\9 8)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalCharEquality() {
		assertThat(eval("(eql #\\a #\\a)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(eql #\\a #\\b)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(equal (list #\\a #\\b) (list #\\a #\\b))")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void evalCharPrinting() {
		// prin1 prints the readable #\name form; princ prints the bare glyph.
		assertThat(capture("(prin1 #\\a)")).isEqualTo("#\\a");
		assertThat(capture("(prin1 #\\Space)")).isEqualTo("#\\Space");
		assertThat(capture("(prin1 #\\Newline)")).isEqualTo("#\\Newline");
		assertThat(capture("(princ #\\a)")).isEqualTo("a");
		assertThat(capture("(princ #\\Space)")).isEqualTo(" ");
	}

	@Test
	void evalParseInteger() {
		assertThat(eval("(parse-integer \"42\")")).isEqualTo(new LispInteger(42));
		assertThat(eval("(parse-integer \"  -13  \")")).isEqualTo(new LispInteger(-13));
		assertThat(eval("(parse-integer \"ff\" :radix 16)")).isEqualTo(new LispInteger(255));
		assertThat(eval("(parse-integer \"12abc\" :junk-allowed t)")).isEqualTo(new LispInteger(12));
		assertThat(eval("(parse-integer \"xyz\" :junk-allowed t)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(parse-integer \"x9x\" :start 1 :end 2)")).isEqualTo(new LispInteger(9));
	}

	@Test
	void evalParseIntegerRejectsJunkByDefault() {
		assertThatThrownBy(() -> eval("(parse-integer \"12abc\")")).hasMessageContaining("parse-integer");
		assertThatThrownBy(() -> eval("(parse-integer \"\")")).hasMessageContaining("parse-integer");
	}

	@Test
	void evalReadFromString() {
		assertThat(eval("(read-from-string \"(+ 1 2)\")")).isEqualTo(new LispCons(new LispSymbol("+"),
				new LispCons(new LispInteger(1), new LispCons(new LispInteger(2), LispNil.INSTANCE))));
		assertThat(eval("(read-from-string \"42\")")).isEqualTo(new LispInteger(42));
		// Runtime read follows the upcase premise: a user symbol reads upcased (CL's
		// answer), where the old case-preserving runtime read returned foo.
		assertThat(eval("(read-from-string \"foo\")")).isEqualTo(new LispSymbol("FOO"));
	}

	@Test
	void readStreamRoundTrip(@TempDir Path tempDir) {
		String file = tempDir.resolve("data.txt").toString().replace("\\", "\\\\");
		LispVal result = evalMulti("""
				(with-open-file (out "%s" :direction :output)
				  (write-line (prin1-to-string (list 10 20 30)) out)
				  (write-line (prin1-to-string 99) out))
				(with-open-file (in "%s")
				  (list (read in) (read in) (read in)))
				""".formatted(file, file));
		assertThat(result.print()).isEqualTo("((10 20 30) 99 NIL)");
	}

	@Test
	void hashTablePutAndGet() {
		LispVal result = evalMulti("""
				(defparameter *h* (make-hash-table :test 'equal))
				(setf (gethash "a" *h*) 1)
				(setf (gethash "b" *h*) 2)
				(list (gethash "a" *h*) (gethash "b" *h*) (gethash "c" *h*))
				""");
		assertThat(result.print()).isEqualTo("(1 2 NIL)");
	}

	@Test
	void hashTableGetWithDefault() {
		assertThat(eval("(gethash 'x (make-hash-table) 42)")).isEqualTo(new LispInteger(42));
	}

	@Test
	void hashTableListKeysWithEqual() {
		LispVal result = evalMulti("""
				(defparameter *q* (make-hash-table :test 'equal))
				(setf (gethash (list 0 1 2) *q*) 1.5)
				(gethash (list 0 1 2) *q* 0.0)
				""");
		assertThat(result).isEqualTo(new LispDouble(1.5));
	}

	@Test
	void hashTableIncf() {
		LispVal result = evalMulti("""
				(defparameter *h* (make-hash-table :test 'equal))
				(dolist (w (list "a" "b" "a" "a" "b"))
				  (incf (gethash w *h* 0)))
				(list (gethash "a" *h*) (gethash "b" *h*))
				""");
		assertThat(result.print()).isEqualTo("(3 2)");
	}

	@Test
	void hashTableCountAndRemhash() {
		LispVal result = evalMulti("""
				(defparameter *h* (make-hash-table))
				(setf (gethash 1 *h*) 'a)
				(setf (gethash 2 *h*) 'b)
				(remhash 1 *h*)
				(list (hash-table-count *h*) (gethash 1 *h*) (gethash 2 *h*))
				""");
		assertThat(result.print()).isEqualTo("(1 NIL B)");
	}

	@Test
	void hashTableMaphashSumsValues() {
		LispVal result = evalMulti("""
				(defparameter *h* (make-hash-table :test 'equal))
				(setf (gethash "a" *h*) 10)
				(setf (gethash "b" *h*) 20)
				(setf (gethash "c" *h*) 30)
				(defparameter *sum* 0)
				(maphash (lambda (k v) (setq *sum* (+ *sum* v))) *h*)
				*sum*
				""");
		assertThat(result).isEqualTo(new LispInteger(60));
	}

	@Test
	void hashTablePredicate() {
		assertThat(eval("(hash-table-p (make-hash-table))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(hash-table-p (list 1 2))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(consp (make-hash-table))")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void hashTableFunctionsAsFirstClassValues() {
		LispVal result = evalMulti("""
				(defparameter *h* (funcall #'make-hash-table))
				(setf (gethash "a" *h*) 1)
				(setf (gethash "b" *h*) 2)
				(list (funcall #'gethash "a" *h*)
				      (mapcar #'hash-table-p (list *h* 5))
				      (funcall #'hash-table-count *h*))
				""");
		assertThat(result.print()).isEqualTo("(1 (T NIL) 2)");
	}

	@Test
	void makeArrayVectorRefAndSet() {
		LispVal result = evalMulti("""
				(defparameter *v* (make-array 5 :initial-element 0))
				(setf (aref *v* 0) 10)
				(setf (aref *v* 4) 40)
				(incf (aref *v* 0) 5)
				(list (aref *v* 0) (aref *v* 1) (aref *v* 4))
				""");
		assertThat(result.print()).isEqualTo("(15 0 40)");
	}

	@Test
	void makeArrayTwoDimensional() {
		LispVal result = evalMulti("""
				(defparameter *m* (make-array (list 2 3) :initial-element 7))
				(setf (aref *m* 0 0) 1)
				(setf (aref *m* 1 2) 99)
				(list (aref *m* 0 0) (aref *m* 0 1) (aref *m* 1 2))
				""");
		assertThat(result.print()).isEqualTo("(1 7 99)");
	}

	@Test
	void makeArraySingleElementListIsRankOne() {
		LispVal result = evalMulti("""
				(defparameter *w* (make-array (list 3) :initial-element 2))
				(setf (aref *w* 1) 8)
				(list (aref *w* 0) (aref *w* 1) (aref *w* 2))
				""");
		assertThat(result.print()).isEqualTo("(2 8 2)");
	}

	@Test
	void makeArrayDefaultInitialElementIsNil() {
		assertThat(eval("(aref (make-array 3) 2)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void arrayCapturedInClosure() {
		LispVal result = evalMulti("""
				(defun make-counter (vec)
				  (lambda (i) (setf (aref vec i) (+ 1 (aref vec i))) (aref vec i)))
				(defparameter *c* (make-array 2 :initial-element 0))
				(defparameter *bump* (make-counter *c*))
				(defparameter *a* (funcall *bump* 0))
				(defparameter *b* (funcall *bump* 0))
				(defparameter *d* (funcall *bump* 1))
				(list *a* *b* *d*)
				""");
		assertThat(result.print()).isEqualTo("(1 2 1)");
	}

	@Test
	void arrayIsNotConsAndNotEqual() {
		assertThat(eval("(consp (make-array 3))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(equal (make-array 3) (make-array 3))")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void vectorLiteralSelfEvaluatesToReadableArray() {
		LispVal result = eval("#(1 2 3)");
		assertThat(result.print()).isEqualTo("#(1 2 3)");
		assertThat(eval("(aref #(10 20 30) 1)").print()).isEqualTo("20");
	}

	@Test
	void vectorLiteralPrin1QuotesStringsPrincDoesNot() {
		LispVal vec = eval("#(a \"b\")");
		assertThat(vec.print()).isEqualTo("#(A \"b\")");
		assertThat(vec.display()).isEqualTo("#(A b)");
	}

	@Test
	void nestedAndEmptyVectorLiterals() {
		assertThat(eval("#(#(1 2) #(3 4))").print()).isEqualTo("#(#(1 2) #(3 4))");
		assertThat(eval("#()").print()).isEqualTo("#()");
	}

	@Test
	void rank2ArrayLiteralSelfEvaluatesToReadableArray() {
		assertThat(eval("#2A((1 2 3) (4 5 6))").print()).isEqualTo("#2A((1 2 3) (4 5 6))");
		assertThat(eval("(aref #2A((1 2) (3 4)) 1 0)").print()).isEqualTo("3");
		assertThat(eval("(array-dimensions #2A((1 2 3) (4 5 6)))").print()).isEqualTo("(2 3)");
	}

	@Test
	void rank3ArrayLiteralSelfEvaluatesToReadableArray() {
		assertThat(eval("#3A(((1 2) (3 4)) ((5 6) (7 8)))").print()).isEqualTo("#3A(((1 2) (3 4)) ((5 6) (7 8)))");
		assertThat(eval("(aref #3A(((1 2) (3 4)) ((5 6) (7 8))) 1 0 1)").print()).isEqualTo("6");
	}

	@Test
	void rank2ArrayLiteralIsMutable() {
		assertThat(eval("(let ((m #2A((1 2) (3 4)))) (setf (aref m 0 1) 9) m)").print()).isEqualTo("#2A((1 9) (3 4))");
	}

	@Test
	void lengthOfVectorReturnsElementCount() {
		assertThat(eval("(length (make-array 5 :initial-element 0))")).isEqualTo(new LispInteger(5));
		assertThat(eval("(length #(10 20 30))")).isEqualTo(new LispInteger(3));
		assertThat(eval("(length #())")).isEqualTo(new LispInteger(0));
	}

	@Test
	void lengthOfTwoDimensionalArrayIsAnError() {
		assertThatThrownBy(() -> eval("(length (make-array (list 2 3) :initial-element 0))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("LENGTH");
	}

	@Test
	void twoDimensionalArrayPrintsAsHash2A() {
		LispVal result = evalMulti("""
				(defparameter *m* (make-array (list 2 3) :initial-element 0))
				(setf (aref *m* 0 0) 1)
				(setf (aref *m* 1 2) 9)
				*m*
				""");
		assertThat(result.print()).isEqualTo("#2A((1 0 0) (0 0 9))");
	}

	@Test
	void makeArrayRankThreeRefAndSet() {
		LispVal result = evalMulti("""
				(defparameter *t* (make-array (list 2 2 2) :initial-element 0))
				(setf (aref *t* 0 0 0) 1)
				(setf (aref *t* 0 1 1) 4)
				(setf (aref *t* 1 0 1) 6)
				(list (aref *t* 0 0 0) (aref *t* 0 1 1) (aref *t* 1 0 1) (aref *t* 1 1 0))
				""");
		assertThat(result.print()).isEqualTo("(1 4 6 0)");
	}

	@Test
	void rankThreeArrayPrintsAsHash3A() {
		LispVal result = evalMulti("""
				(defparameter *t* (make-array (list 2 2 2) :initial-element 0))
				(setf (aref *t* 0 0 0) 1)
				(setf (aref *t* 0 1 1) 4)
				(setf (aref *t* 1 0 1) 6)
				*t*
				""");
		assertThat(result.print()).isEqualTo("#3A(((1 0) (0 4)) ((0 6) (0 0)))");
	}

	@Test
	void rankNArrayDimensionsAndIntrospection() {
		LispVal result = evalMulti("""
				(defparameter *t4* (make-array (list 2 3 4 5) :initial-element 0))
				(list (array-dimensions *t4*) (array-rank *t4*) (array-dimension *t4* 2)
				      (array-total-size *t4*))
				""");
		assertThat(result.print()).isEqualTo("((2 3 4 5) 4 4 120)");
	}

	@Test
	void linalgRankThreeElementwise() {
		LispVal result = evalMulti("""
				(defparameter *c* (linalg:reshape (linalg:arange 8) '(2 2 2)))
				(list (linalg:sum *c*) (linalg:amax *c*)
				      (linalg:array-equal (linalg:add *c* 10) (linalg:reshape (linalg:arange 10 18) '(2 2 2))))
				""");
		assertThat(result.print()).isEqualTo("(28.0 7.0 T)");
	}

	@Test
	void linalgNumpyStyleBroadcasting() {
		// numpy general broadcasting: trailing axes align, and an axis of length 1
		// (or a missing leading axis) stretches over the other operand's extent.
		// The result width keeps the FIRST array operand's element type, like the
		// mixed-width rule.
		LispVal result = evalMulti("""
				(list (linalg:mul #2A((1 2) (3 4)) #(10 20))
				      (linalg:add #2A((1 2) (3 4)) #2A((100) (200)))
				      (linalg:add #2A((0) (10) (20)) #(1 2 3))
				      (linalg:sub #(1 2) #d(1.0))
				      (linalg:maximum #2A((1 5) (4 2)) #(3 3))
				      (linalg:minimum #(9 0) #2A((5) (1)))
				      (linalg:div #3A(((2.0 4.0) (6.0 8.0))) #(2 4)))
				""");
		assertThat(result.print()).isEqualTo("(#d((10.0 40.0) (30.0 80.0))"
				+ " #d((101.0 102.0) (203.0 204.0)) #d((1.0 2.0 3.0) (11.0 12.0 13.0) (21.0 22.0 23.0))"
				+ " #d(0.0 1.0) #d((3.0 5.0) (4.0 3.0)) #d((5.0 0.0) (1.0 0.0)) #d(((1.0 1.0) (3.0 2.0))))");
	}

	@Test
	void linalgBroadcastingPreservesTheFirstOperandWidth() {
		LispVal result = evalMulti("""
				(list (linalg:mul (linalg:from-list '((1 2) (3 4)) 'single-float) #(10 20))
				      (array-element-type (linalg:mul #(10 20) (linalg:ones '(2 2) 'single-float))))
				""");
		assertThat(result.print()).isEqualTo("(#f((10.0 40.0) (30.0 80.0)) DOUBLE-FLOAT)");
	}

	@Test
	void linalgNdim() {
		// numpy's np.ndim: 0 for a plain number, else the array's rank.
		LispVal result = evalMulti("""
				(list (linalg:ndim 3.0)
				      (linalg:ndim #(1 2 3))
				      (linalg:ndim #2A((1 2) (3 4)))
				      (linalg:ndim (linalg:reshape (linalg:arange 8) '(2 2 2))))
				""");
		assertThat(result.print()).isEqualTo("(0 1 2 3)");
	}

	@Test
	void linalgBroadcastIncompatibleShapesStillSignal() {
		// Neither axis is 1, so numpy would refuse these too.
		assertThatThrownBy(() -> eval("(linalg:add #d(1.0 2.0) #d(1.0 2.0 3.0))"))
			.hasMessageContaining("linalg: shape mismatch");
		assertThatThrownBy(() -> eval("(linalg:add (linalg:zeros '(2 3)) (linalg:zeros '(3 2)))"))
			.hasMessageContaining("linalg: shape mismatch");
	}

	@Test
	void linalgSingleFloatWidthPolymorphism() {
		// A linalg constructor opts into single-float (#f) with a trailing
		// element-type (double stays the default), and every transform PRESERVES the
		// input width, so a #f array is never silently widened back to double.
		LispVal result = evalMulti("""
				(let ((w (linalg:from-list '((1 2) (3 4)) 'single-float)))
				  (list (array-element-type (linalg:zeros 3 'single-float))
				        (array-element-type (linalg:zeros 3))
				        (linalg:sub w (linalg:mul (linalg:ones '(2 2) 'single-float) 0.5))
				        (array-element-type (linalg:transpose w))
				        (array-element-type (linalg:dot w (linalg:from-list '(1 1) 'single-float)))
				        (array-element-type (linalg:add (linalg:from-list '(1 2 3)) 10))))
				""");
		assertThat(result.print())
			.isEqualTo("(SINGLE-FLOAT DOUBLE-FLOAT #f((0.5 1.5) (2.5 3.5)) SINGLE-FLOAT SINGLE-FLOAT DOUBLE-FLOAT)");
	}

	@Test
	void linalgDiffAndGradient() {
		// numpy calculus parity: diff = the n-th discrete difference along the last
		// axis (numpy's own docs example), gradient = second-order central
		// differences with first-order one-sided ends, over a uniform scalar
		// spacing or a non-uniform coordinate vector (exact for quadratics:
		// f = x^2 sampled at x = (0 1 3) differentiates to exactly 2x). Both
		// preserve the input width like every other linalg transform.
		LispVal result = evalMulti("""
				(list (linalg:diff #(1 2 4 7 0))
				      (linalg:diff #(1 2 4 7 0) 2)
				      (linalg:diff #2A((1 3 6) (0 5 6)))
				      (linalg:diff #(5))
				      (linalg:gradient #(0 1 4 9 16))
				      (linalg:gradient #(0 1 4 9 16) 2)
				      (linalg:gradient #(0 1 9) #(0 1 3))
				      (array-element-type (linalg:diff (linalg:arange 0 4 'single-float)))
				      (array-element-type (linalg:gradient (linalg:arange 0 4 'single-float))))
				""");
		assertThat(result.print()).isEqualTo("(#d(1.0 2.0 3.0 -7.0) #d(1.0 1.0 -10.0) #d((2.0 3.0) (5.0 1.0)) #d()"
				+ " #d(1.0 2.0 4.0 6.0 7.0) #d(0.5 1.0 2.0 3.0 3.5) #d(1.0 2.0 4.0) SINGLE-FLOAT SINGLE-FLOAT)");
		assertThatThrownBy(() -> eval("(linalg:gradient #(1))"))
			.hasMessageContaining("linalg: gradient needs at least 2 samples");
		assertThatThrownBy(() -> eval("(linalg:gradient #2A((1 2) (3 4)))"))
			.hasMessageContaining("linalg: gradient expects a vector");
		assertThatThrownBy(() -> eval("(linalg:gradient #(0 2 6) #(0 1))"))
			.hasMessageContaining("linalg: gradient coordinates must match the sample length");
		assertThatThrownBy(() -> eval("(linalg:diff #(1 2) -1)"))
			.hasMessageContaining("linalg: diff order must be non-negative");
	}

	@Test
	void linalgAxisReductions() {
		// numpy axis semantics (todo: deep-learning-from-scratch port): an integer
		// axis (negative counts from the end) reduces along that axis with the axis
		// dropped, keepdims keeps it as extent 1, and a vector without keepdims
		// reduces to the scalar itself. A no-axis reduction with keepdims wraps the
		// scalar in an all-ones-shape array.
		LispVal result = evalMulti("""
				(defparameter *m* (linalg:from-list '((1 2 3) (4 5 6))))
				(list (linalg:sum *m* 0) (linalg:sum *m* 1) (linalg:sum *m* -1)
				      (linalg:sum *m* 1 t) (linalg:sum *m* nil t)
				      (linalg:mean *m* 0) (linalg:amax *m* 1) (linalg:amin *m* 0 t)
				      (linalg:sum #(1 2 3) 0) (linalg:sum #(1 2 3) 0 t)
				      (array-element-type (linalg:sum (linalg:ones '(2 2) 'single-float) 0)))
				""");
		assertThat(result.print()).isEqualTo("(#d(5.0 7.0 9.0) #d(6.0 15.0) #d(6.0 15.0)"
				+ " #d((6.0) (15.0)) #d((21.0)) #d(2.5 3.5 4.5) #d(3.0 6.0) #d((1.0 2.0 3.0))"
				+ " 6 #d(6.0) SINGLE-FLOAT)");
		// Rank-3 middle axis: out[o, i] folds over a[o, j, i].
		LispVal rank3 = evalMulti("""
				(defparameter *c* (linalg:reshape (linalg:arange 24) '(2 3 4)))
				(list (linalg:shape (linalg:sum *c* 1)) (linalg:shape (linalg:sum *c* 1 t))
				      (linalg:sum *c* 1))
				""");
		assertThat(rank3.print()).isEqualTo("((2 4) (2 1 4) #d((12.0 15.0 18.0 21.0) (48.0 51.0 54.0 57.0)))");
		assertThatThrownBy(() -> eval("(linalg:sum #(1 2 3) 1)")).hasMessageContaining("linalg: axis out of range");
		assertThatThrownBy(() -> eval("(linalg:amax (linalg:zeros '(0 3)) 0)"))
			.hasMessageContaining("linalg: reduction of an empty axis");
	}

	@Test
	void linalgArgmaxArgminAxis() {
		// With an axis, argmax/argmin return per-slice indices: a packed DOUBLE
		// array for rank >= 2 (linalg arrays have no integer width), the integer
		// index itself for a vector; first wins ties, like the no-axis form.
		LispVal result = evalMulti("""
				(defparameter *m* (linalg:from-list '((1 9 3) (7 5 6))))
				(list (linalg:argmax *m* 1) (linalg:argmax *m* 0) (linalg:argmax *m* -1)
				      (linalg:argmin *m* 1) (linalg:argmax #(3 1 2) 0)
				      (linalg:argmax (linalg:from-list '((2 2) (1 1))) 1))
				""");
		assertThat(result.print()).isEqualTo("(#d(1.0 0.0) #d(1.0 0.0 1.0) #d(1.0 0.0) #d(0.0 1.0) 0 #d(0.0 0.0))");
	}

	@Test
	void linalgReshapeInfersMinusOne() {
		// numpy's -1 extent: inferred from the element count (at most one); a bare
		// -1 shape flattens.
		LispVal result = evalMulti("""
				(list (linalg:shape (linalg:reshape (linalg:arange 12) '(3 -1)))
				      (linalg:shape (linalg:reshape (linalg:arange 12) '(-1 6)))
				      (linalg:shape (linalg:reshape (linalg:reshape (linalg:arange 6) '(2 3)) -1)))
				""");
		assertThat(result.print()).isEqualTo("((3 4) (2 6) (6))");
		assertThatThrownBy(() -> eval("(linalg:reshape (linalg:arange 12) '(-1 -1))"))
			.hasMessageContaining("linalg: reshape allows at most one -1");
		assertThatThrownBy(() -> eval("(linalg:reshape (linalg:arange 12) '(5 -1))"))
			.hasMessageContaining("linalg: reshape size mismatch");
	}

	@Test
	void linalgSeededRandomIsDeterministic() {
		// The Wichmann-Hill generator: linalg:seed makes rand/randn/uniform/choice/
		// permutation reproducible, and every draw is exact integer + IEEE
		// arithmetic, so the sequence is bit-identical on every backend.
		LispVal result = evalMulti("""
				(linalg:seed 42)
				(defparameter *c1* (linalg:choice 60000 4))
				(linalg:seed 42)
				(defparameter *c2* (linalg:choice 60000 4))
				(linalg:seed 9)
				(defparameter *p* (linalg:permutation 10))
				(linalg:seed 1)
				(defparameter *r* (linalg:rand '(2 2)))
				(linalg:seed 7)
				(defparameter *g* (linalg:randn 3))
				(linalg:seed 3)
				(defparameter *u* (linalg:uniform -2.0 2.0 100))
				(list (linalg:array-equal *c1* *c2*) *c1*
				      (linalg:sum *p*)
				      (linalg:emap (lambda (x) (truncate (* 1024 x))) *r*)
				      (linalg:emap (lambda (x) (truncate (* 1024 x))) *g*)
				      (and (>= (linalg:amin *u*) -2.0) (< (linalg:amax *u*) 2.0))
				      (array-element-type (linalg:rand 3 'single-float))
				      (linalg:shape (linalg:randn '(2 3))))
				""");
		assertThat(result.print()).isEqualTo("(T #d(26833.0 11120.0 29256.0 22347.0) 45.0"
				+ " #d((317.0 637.0) (949.0 376.0)) #d(284.0 -21.0 221.0) T SINGLE-FLOAT (2 3))");
	}

	@Test
	void linalgIndexingSelection() {
		// take-rows = numpy x[batch-mask] (any rank, whole axis-0 slabs), row = the
		// axis-dropping numpy x[i], gather = y[np.arange(n), t], one-hot builds the
		// (len idx) x n label matrix.
		LispVal result = evalMulti("""
				(defparameter *m* (linalg:from-list '((10 11 12) (20 21 22) (30 31 32))))
				(list (linalg:take-rows *m* #(2 0 2))
				      (linalg:take-rows (linalg:arange 5) #(4 0))
				      (linalg:shape (linalg:take-rows (linalg:reshape (linalg:arange 24) '(4 3 2)) #(1 3)))
				      (linalg:row *m* 2)
				      (linalg:shape (linalg:row (linalg:reshape (linalg:arange 24) '(4 3 2)) 1))
				      (linalg:gather *m* #(2 0 1))
				      (linalg:one-hot #(1 0 2) 3)
				      (array-element-type (linalg:one-hot #(0) 2 'single-float))
				      (array-element-type (linalg:take-rows (linalg:ones '(2 2) 'single-float) #(0)))
				      (array-element-type (linalg:row (linalg:ones '(2 2) 'single-float) 0)))
				""");
		assertThat(result.print()).isEqualTo("(#d((30.0 31.0 32.0) (10.0 11.0 12.0) (30.0 31.0 32.0))"
				+ " #d(4.0 0.0) (2 3 2) #d(30.0 31.0 32.0) (3 2) #d(12.0 20.0 31.0)"
				+ " #d((0.0 1.0 0.0) (1.0 0.0 0.0) (0.0 0.0 1.0)) SINGLE-FLOAT SINGLE-FLOAT SINGLE-FLOAT)");
		assertThatThrownBy(() -> eval("(linalg:row #(1 2 3) 1)")).hasMessageContaining("linalg: row expects rank >= 2");
		assertThatThrownBy(() -> eval("(linalg:gather #(1 2 3) #(0))"))
			.hasMessageContaining("linalg: gather expects a matrix");
		assertThatThrownBy(() -> eval("(linalg:gather (linalg:zeros '(2 2)) #(0))"))
			.hasMessageContaining("linalg: gather index length must match the rows");
	}

	@Test
	void linalgTransposeAxesPadIm2col() {
		// The ch07 CNN additions: transpose with an axes list = numpy
		// x.transpose(1 0 2) (rank-n permutation), pad = np.pad's constant-0 mode,
		// and the internal rank-4 %la-im2col / %la-col2im pair (window unfold and
		// its scatter-add adjoint) behind the convolution examples.
		LispVal result = evalMulti("""
				(defparameter *x* (linalg:reshape (linalg:arange 24) '(2 3 4)))
				(defparameter *img* (linalg:reshape (linalg:arange 16) '(1 1 4 4)))
				(defparameter *col* (linalg::%la-im2col *img* 2 2 2 0))
				(list (linalg:shape (linalg:transpose *x* '(1 0 2)))
				      (linalg:transpose (linalg:from-list '((1 2) (3 4))) '(1 0))
				      (linalg:array-equal (linalg:transpose (linalg:transpose *x* '(1 2 0)) '(2 0 1)) *x*)
				      (linalg:pad (linalg:from-list '((1 2) (3 4))) '((1 1) (2 2)))
				      (linalg:pad #(1 2) 1)
				      *col*
				      (linalg:array-equal (linalg::%la-col2im *col* '(1 1 4 4) 2 2 2 0) *img*)
				      (linalg::%la-col2im (linalg::%la-im2col (linalg:ones '(1 1 3 3)) 2 2 1 0) '(1 1 3 3) 2 2 1 0)
				      (array-element-type (linalg:transpose (linalg:ones '(2 2 2) 'single-float) '(2 1 0)))
				      (array-element-type (linalg:pad (linalg:ones 2 'single-float) 1)))
				""");
		assertThat(result.print()).isEqualTo("((3 2 4) #d((1.0 3.0) (2.0 4.0)) T"
				+ " #d((0.0 0.0 0.0 0.0 0.0 0.0) (0.0 0.0 1.0 2.0 0.0 0.0) (0.0 0.0 3.0 4.0 0.0 0.0)"
				+ " (0.0 0.0 0.0 0.0 0.0 0.0)) #d(0.0 1.0 2.0 0.0)"
				+ " #d((0.0 1.0 4.0 5.0) (2.0 3.0 6.0 7.0) (8.0 9.0 12.0 13.0) (10.0 11.0 14.0 15.0)) T"
				+ " #d((((1.0 2.0 1.0) (2.0 4.0 2.0) (1.0 2.0 1.0)))) SINGLE-FLOAT SINGLE-FLOAT)");
		assertThatThrownBy(() -> eval("(linalg:transpose (linalg:zeros '(2 3)) '(0 0))"))
			.hasMessageContaining("linalg: transpose axes must be a permutation");
		assertThatThrownBy(() -> eval("(linalg:transpose (linalg:zeros '(2 3)) '(0))"))
			.hasMessageContaining("linalg: transpose axes must be a permutation");
		assertThatThrownBy(() -> eval("(linalg:pad (linalg:zeros '(2 2)) '((1 1)))"))
			.hasMessageContaining("linalg: pad expects one (before after) pair per axis");
		assertThatThrownBy(() -> eval("(linalg:pad #(1 2) -1)"))
			.hasMessageContaining("linalg: pad widths must be non-negative");
	}

	@Test
	void linalgElementwiseComparisonsAndZerosLike() {
		// The comparison masks are 0.0/1.0 arrays over %la-bcast, so scalars and
		// numpy broadcasting come for free; zeros-like preserves shape AND width.
		LispVal result = evalMulti("""
				(list (linalg:equal #(1 5 3) #(2 5 1))
				      (linalg:greater #(1 5 3) #(2 5 1))
				      (linalg:greater-equal #(1 5 3) #(2 5 1))
				      (linalg:less #(1 5 3) #(2 5 1))
				      (linalg:less-equal #(1 5 3) #(2 5 1))
				      (linalg:greater #(1 5 3) 2)
				      (linalg:equal #2A((1 2) (3 4)) #(1 4))
				      (linalg:zeros-like #2A((1 2) (3 4)))
				      (array-element-type (linalg:zeros-like (linalg:ones 2 'single-float)))
				      (linalg:sum (linalg:equal (linalg:argmax (linalg:from-list '((0.1 0.8) (0.9 0.1))) 1) #(1 1))))
				""");
		assertThat(result.print()).isEqualTo("(#d(0.0 1.0 0.0) #d(0.0 0.0 1.0) #d(0.0 1.0 1.0)"
				+ " #d(1.0 0.0 0.0) #d(1.0 1.0 0.0) #d(0.0 1.0 1.0) #d((1.0 0.0) (0.0 1.0))"
				+ " #d((0.0 0.0) (0.0 0.0)) SINGLE-FLOAT 1.0)");
	}

	@Test
	void rowMajorArefReadsAndWritesFlat() {
		LispVal result = evalMulti("""
				(defparameter *m* (make-array (list 2 3) :initial-element 0))
				(setf (row-major-aref *m* 4) 9)
				(list (row-major-aref *m* 4) (aref *m* 1 1) (array-row-major-index *m* 1 1)
				      (array-row-major-index *m* 0 2))
				""");
		assertThat(result.print()).isEqualTo("(9 9 4 2)");
	}

	@Test
	void rowMajorArefWorksOnRankThree() {
		LispVal result = evalMulti("""
				(defparameter *t* (make-array (list 2 2 2) :initial-element 0))
				(setf (aref *t* 1 0 1) 7)
				(list (row-major-aref *t* 5) (array-row-major-index *t* 1 0 1)
				      (row-major-aref #(10 20 30) 2))
				""");
		assertThat(result.print()).isEqualTo("(7 5 30)");
	}

	@Test
	void makeArrayRejectsEmptyDimensionList() {
		assertThatThrownBy(() -> eval("(make-array (list))")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("dimension");
	}

	// --- defmacro (user macros) ---

	@Test
	void defmacroReturnsName() {
		assertThat(evalMulti("(defmacro my-noop (x) x)")).isEqualTo(new LispSymbol("MY-NOOP"));
	}

	@Test
	void defmacroWithBackquoteBody() {
		LispVal result = evalMulti("""
				(defmacro my-when2 (test &body body)
				  `(if ,test (progn ,@body) nil))
				(my-when2 (> 3 1) 10 20)
				""");
		assertThat(result).isEqualTo(new LispInteger(20));
	}

	@Test
	void defmacroNestedBackquoteOnceOnly() {
		// once-only uses three levels of backquote. Its guard against multiple
		// evaluation must hold: the argument form runs exactly once. Verified
		// structurally against SBCL's (let ((#:g (foo))) (* #:g #:g)).
		LispVal result = evalMulti("""
				(defmacro once-only (names &body body)
				  (let ((gensyms (loop for name in names collect (gensym (symbol-name name)))))
				    `(let (,@(loop for g in gensyms
				                   for name in names
				                   collect `(,g (gensym ,(symbol-name name)))))
				       `(let (,,@(loop for g in gensyms for n in names
				                       collect ``(,,g ,,n)))
				          ,(let (,@(loop for n in names for g in gensyms
				                         collect `(,n ,g)))
				             ,@body)))))
				(defmacro square (x)
				  (once-only (x)
				    `(* ,x ,x)))
				(defvar *calls* 0)
				(defun bump () (setq *calls* (+ *calls* 1)) 5)
				(list (square (bump)) *calls*)
				""");
		// 5*5 = 25, and bump was evaluated exactly once.
		assertThat(result.print()).isEqualTo("(25 1)");
	}

	@Test
	void defmacroReceivesUnevaluatedForms() {
		// swap! must see the variable names, not their values.
		LispVal result = evalMulti("""
				(defmacro swap! (a b)
				  `(let ((__tmp ,a)) (setq ,a ,b) (setq ,b __tmp)))
				(setq p 1)
				(setq q 2)
				(swap! p q)
				(list p q)
				""");
		assertThat(result.print()).isEqualTo("(2 1)");
	}

	@Test
	void defmacroBodyRunsAtExpansionTime() {
		// The helper is called while expanding, not at run time.
		LispVal result = evalMulti("""
				(defun expand-helper (n) (* n 2))
				(defmacro with-doubled (n x) `(+ ,(expand-helper n) ,x))
				(with-doubled 5 1)
				""");
		assertThat(result).isEqualTo(new LispInteger(11));
	}

	@Test
	void defmacroRestCollectsArguments() {
		LispVal result = evalMulti("""
				(defmacro as-list (&rest forms) `(list ,@forms))
				(as-list 1 (+ 1 1) 3)
				""");
		assertThat(result.print()).isEqualTo("(1 2 3)");
	}

	@Test
	void defmacroExpansionMayBeAnotherMacroCall() {
		LispVal result = evalMulti("""
				(defmacro inner (x) `(+ ,x 1))
				(defmacro outer (x) `(inner ,x))
				(outer 41)
				""");
		assertThat(result).isEqualTo(new LispInteger(42));
	}

	@Test
	void defmacroCannotRedefineStandardOperator() {
		assertThatThrownBy(() -> evalMulti("(defmacro when (x) x)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("cannot redefine");
		assertThatThrownBy(() -> evalMulti("(defmacro cadr (x) x)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("cannot redefine");
	}

	@Test
	void defmacroHasNoFunctionValue() {
		assertThatThrownBy(() -> evalMulti("(defmacro my-mac (x) x) #'my-mac")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("not a function");
	}

	@Test
	void defmacroArgumentCountIsChecked() {
		assertThatThrownBy(() -> evalMulti("(defmacro my-mac (a b) `(+ ,a ,b)) (my-mac 1)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects 2 arguments");
	}

	@Test
	void defmacroWithOptionalParameters() {
		LispVal result = evalMulti("""
				(defmacro add-defaulted (a &optional (b 10)) `(+ ,a ,b))
				(list (add-defaulted 1) (add-defaulted 1 2))
				""");
		assertThat(result.print()).isEqualTo("(11 3)");
	}

	@Test
	void defmacroWithKeyParameters() {
		LispVal result = evalMulti("""
				(defmacro scaled (x &key (by 1)) `(* ,x ,by))
				(list (scaled 5) (scaled 5 :by 3))
				""");
		assertThat(result.print()).isEqualTo("(5 15)");
	}

	@Test
	void defmacroWithDestructuringParameterList() {
		LispVal result = evalMulti("""
				(defmacro with-pair ((a b) &body body)
				  `(let ((,a 1) (,b 2)) ,@body))
				(with-pair (p q) (+ p q))
				""");
		assertThat(result).isEqualTo(new LispInteger(3));
	}

	@Test
	void defmacroDestructuringReceivesUnevaluatedForms() {
		// The nested pattern binds the argument FORMS, not their values.
		LispVal result = evalMulti("""
				(defmacro first-form ((a b)) `(quote ,a))
				(first-form ((+ 1 2) x))
				""");
		assertThat(result.print()).isEqualTo("(+ 1 2)");
	}

	@Test
	void defmacroRejectsUnsupportedLambdaListKeywords() {
		// &whole stays unsupported and signals at definition time.
		assertThatThrownBy(() -> evalMulti("(defmacro my-mac (&whole w a) a)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("Unsupported lambda-list keyword");
		// &environment is accepted (lite): the parameter binds to nil, so threading it
		// into constantp/get-setf-expansion works.
		assertThat(evalMulti("""
				(defmacro my-env-mac (a &environment e) `(list ,a ',e))
				(my-env-mac 7)
				""").print()).isEqualTo("(7 NIL)");
		assertThatThrownBy(() -> evalMulti("(defmacro my-mac2 (a &environment) a)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("&ENVIRONMENT must be followed by exactly one parameter symbol");
	}

	@Test
	void defmacroWorksThroughRuntimeEval() {
		LispVal result = evalMulti("""
				(defmacro twice (x) `(* 2 ,x))
				(eval '(twice 21))
				""");
		assertThat(result).isEqualTo(new LispInteger(42));
	}

	// --- gensym ---

	@Test
	void gensymReturnsFreshSymbols() {
		LispVal result = evalMulti("(list (gensym) (gensym))");
		assertThat(result.print()).isEqualTo("(#:g1 #:g2)");
		assertThat(evalMulti("(eq (gensym) (gensym))")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void gensymResultIsASymbol() {
		assertThat(evalMulti("(symbolp (gensym))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(stringp (gensym))")).isEqualTo(LispNil.INSTANCE);
		assertThat(evalMulti("(keywordp (gensym))")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void gensymAcceptsAPrefixString() {
		assertThat(evalMulti("(gensym \"tmp\")").print()).isEqualTo("#:tmp1");
	}

	@Test
	void gensymRejectsANonStringPrefix() {
		assertThatThrownBy(() -> evalMulti("(gensym 'tmp)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("prefix must be a string");
		assertThatThrownBy(() -> evalMulti("(gensym \"a\" \"b\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("at most 1 argument");
	}

	@Test
	void gensymIsAFirstClassFunction() {
		assertThat(evalMulti("(symbolp (funcall #'gensym))")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void symbolNameStripsThePackageMarker() {
		assertThat(evalMulti("(symbol-name 'foo)").print()).isEqualTo("\"FOO\"");
		assertThat(evalMulti("(symbol-name :bar)").print()).isEqualTo("\"BAR\"");
		assertThat(evalMulti("(symbol-name (gensym))").print()).isEqualTo("\"g1\"");
		assertThat(evalMulti("(symbol-name t)").print()).isEqualTo("\"t\"");
		assertThat(evalMulti("(symbol-name nil)").print()).isEqualTo("\"nil\"");
	}

	@Test
	void symbolNameRejectsANonSymbol() {
		assertThatThrownBy(() -> evalMulti("(symbol-name \"foo\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a symbol");
		assertThatThrownBy(() -> evalMulti("(symbol-name 1)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a symbol");
	}

	@Test
	void stringCoercesDesignatorsToStrings() {
		assertThat(evalMulti("(string \"foo\")").print()).isEqualTo("\"foo\"");
		assertThat(evalMulti("(string 'foo)").print()).isEqualTo("\"FOO\"");
		// A keyword's package colon is a marker, not part of the name (matches CL):
		// cl-who
		// relies on (string :html) being "html" so it emits <html>, not <:html>.
		assertThat(evalMulti("(string :bar)").print()).isEqualTo("\"BAR\"");
		assertThat(evalMulti("(string #\\a)").print()).isEqualTo("\"a\"");
		assertThat(evalMulti("(string t)").print()).isEqualTo("\"t\"");
		assertThat(evalMulti("(string nil)").print()).isEqualTo("\"nil\"");
		assertThat(evalMulti("(gensym (string 'x))").print()).isEqualTo("#:X1");
	}

	@Test
	void stringRejectsANonDesignator() {
		assertThatThrownBy(() -> evalMulti("(string 1)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("cannot coerce");
		assertThatThrownBy(() -> evalMulti("(string '(1 2))")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("cannot coerce");
	}

	@Test
	void internReturnsTheSymbolNamedByTheString() {
		assertThat(evalMulti("(intern \"hello\")").print()).isEqualTo("hello");
		assertThat(evalMulti("(symbolp (intern \"hello\"))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(eq (intern \"FOO\") 'foo)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(intern (symbol-name 'round-trip))").print()).isEqualTo("ROUND-TRIP");
	}

	@Test
	void internRejectsAPackageArgumentAndNonStrings() {
		assertThatThrownBy(() -> evalMulti("(intern \"foo\" :cl-user)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("package argument is not supported");
		assertThatThrownBy(() -> evalMulti("(intern 'foo)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a string");
	}

	@Test
	void findSymbolReturnsKnownNamesOnly() {
		assertThat(evalMulti("(find-symbol \"CAR\")").print()).isEqualTo("CAR");
		assertThat(evalMulti("(find-symbol \"COND\")").print()).isEqualTo("COND");
		assertThat(evalMulti("(find-symbol \":KW\")").print()).isEqualTo(":KW");
		assertThat(evalMulti("(find-symbol \"no-such-name\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(evalMulti("(defun my-fn (x) x) (find-symbol \"MY-FN\")").print()).isEqualTo("MY-FN");
		assertThat(evalMulti("(defvar *my-var* 1) (find-symbol \"*MY-VAR*\")").print()).isEqualTo("*MY-VAR*");
	}

	@Test
	void makeSymbolReturnsAFreshUninternedSymbol() {
		assertThat(evalMulti("(make-symbol \"temp\")").print()).isEqualTo("#:temp");
		assertThat(evalMulti("(symbolp (make-symbol \"temp\"))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(eq (make-symbol \"foo\") 'foo)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void boundpChecksTheGlobalVariableNamespace() {
		assertThat(evalMulti("(defvar *bp-var* 1) (boundp '*bp-var*)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(boundp '*bp-nope*)")).isEqualTo(LispNil.INSTANCE);
		assertThat(evalMulti("(boundp :kw)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(boundp t)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(boundp nil)")).isEqualTo(LispTrue.INSTANCE);
		// lexical bindings are invisible, like CL's dynamic-only boundp
		assertThat(evalMulti("(let ((lex 1)) (boundp 'lex))")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void symbolValueReadsTheGlobalVariableNamespace() {
		assertThat(evalMulti("(defvar *sv-var* 42) (symbol-value '*sv-var*)")).isEqualTo(new LispInteger(42));
		assertThat(evalMulti("(setq *sv-var2* 7) (symbol-value (intern \"*SV-VAR2*\"))")).isEqualTo(new LispInteger(7));
		assertThat(evalMulti("(symbol-value :kw)").print()).isEqualTo(":KW");
		assertThat(evalMulti("(symbol-value t)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(symbol-value nil)")).isEqualTo(LispNil.INSTANCE);
		assertThatThrownBy(() -> evalMulti("(symbol-value '*sv-unbound*)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("The variable *SV-UNBOUND* is unbound");
	}

	@Test
	void fboundpChecksFunctionsMacrosAndSpecialForms() {
		assertThat(evalMulti("(fboundp 'car)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(fboundp 'cond)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(fboundp 'defun)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(fboundp 'cadr)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(fboundp 'no-such-fn)")).isEqualTo(LispNil.INSTANCE);
		assertThat(evalMulti("(defun fb-fn (x) x) (fboundp 'fb-fn)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(defmacro fb-mac (x) x) (fboundp 'fb-mac)")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void symbolApiFunctionsAreFirstClassValues() {
		assertThat(evalMulti("(funcall #'symbol-name 'foo)").print()).isEqualTo("\"FOO\"");
		assertThat(evalMulti("(funcall #'intern \"foo\")").print()).isEqualTo("foo");
		assertThat(evalMulti("(mapcar #'symbol-name '(a b))").print()).isEqualTo("(\"A\" \"B\")");
		assertThat(evalMulti("(defvar *fc-var* 1) (funcall #'boundp '*fc-var*)")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void gensymMakesMacroTemporariesCaptureSafe() {
		LispVal result = evalMulti("""
				(defmacro swap2 (a b)
				  (let ((tmp (gensym)))
				    `(let ((,tmp ,a)) (setq ,a ,b) (setq ,b ,tmp))))
				(setq tmp 99)
				(setq other 1)
				(swap2 tmp other)
				(list tmp other)
				""");
		assertThat(result.print()).isEqualTo("(1 99)");
	}

	// --- macroexpand-1 / macroexpand ---

	@Test
	void macroexpand1ExpandsAUserMacroOnce() {
		LispVal result = evalMulti("""
				(defmacro my-when2 (test &body body) `(if ,test (progn ,@body) nil))
				(macroexpand-1 '(my-when2 (> 2 1) 'a 'b))
				""");
		assertThat(result.print()).isEqualTo("(IF (> 2 1) (PROGN (QUOTE A) (QUOTE B)) NIL)");
	}

	@Test
	void macroexpand1ExpandsABuiltinMacroOnce() {
		assertThat(evalMulti("(macroexpand-1 '(unless c x))").print()).isEqualTo("(IF C NIL X)");
		// incf expands to setf: one step only, the setf is left for another round.
		assertThat(evalMulti("(macroexpand-1 '(incf n 2))").print()).isEqualTo("(SETF N (+ N 2))");
	}

	@Test
	void macroexpand1ReturnsANonMacroFormUnchanged() {
		assertThat(evalMulti("(macroexpand-1 '(+ 1 2))").print()).isEqualTo("(+ 1 2)");
		assertThat(evalMulti("(macroexpand-1 'x)").print()).isEqualTo("X");
		assertThat(evalMulti("(macroexpand-1 12)").print()).isEqualTo("12");
		assertThat(evalMulti("(macroexpand-1 '(if a b c))").print()).isEqualTo("(IF A B C)");
	}

	@Test
	void macroexpandExpandsToAFixpoint() {
		// outer expands to inner, which expands again; subforms are not walked.
		LispVal result = evalMulti("""
				(defmacro inner (x) `(+ ,x 1))
				(defmacro outer (x) `(inner ,x))
				(macroexpand '(outer 41))
				""");
		assertThat(result.print()).isEqualTo("(+ 41 1)");
		assertThat(evalMulti("(macroexpand '(when a (when b c)))").print()).isEqualTo("(IF A (WHEN B C) NIL)");
	}

	@Test
	void macroexpandWorksThroughRuntimeEval() {
		assertThat(evalMulti("(eval '(macroexpand-1 '(unless c x)))").print()).isEqualTo("(IF C NIL X)");
	}

	@Test
	void vectorSvrefAndArrayIntrospection() {
		assertThat(eval("(vector 1 2 3)").print()).isEqualTo("#(1 2 3)");
		assertThat(eval("(vector)").print()).isEqualTo("#()");
		assertThat(eval("(svref (vector 10 20 30) 1)").print()).isEqualTo("20");
		assertThat(evalMulti("(defparameter *v* (vector 1 2 3)) (setf (svref *v* 0) 99) *v*").print())
			.isEqualTo("#(99 2 3)");
		assertThat(eval("(array-dimensions (make-array '(2 3) :initial-element 0))").print()).isEqualTo("(2 3)");
		assertThat(eval("(array-dimensions (vector 1 2))").print()).isEqualTo("(2)");
		assertThat(eval("(array-rank (make-array '(2 3)))").print()).isEqualTo("2");
		assertThat(eval("(array-rank (vector 1))").print()).isEqualTo("1");
		assertThat(eval("(array-dimension (make-array '(2 3)) 1)").print()).isEqualTo("3");
		assertThat(eval("(array-total-size (make-array '(2 3)))").print()).isEqualTo("6");
		assertThat(eval("(array-total-size (vector 1 2 3))").print()).isEqualTo("3");
	}

	@Test
	void coerceConvertsBetweenListVectorAndString() {
		assertThat(eval("(coerce '(1 2 3) 'vector)").print()).isEqualTo("#(1 2 3)");
		assertThat(eval("(coerce (vector 1 2 3) 'list)").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(coerce \"ab\" 'list)").print()).isEqualTo("(#\\a #\\b)");
		assertThat(eval("(coerce '(#\\a #\\b) 'string)").print()).isEqualTo("\"ab\"");
		assertThat(eval("(coerce \"ab\" 'vector)").print()).isEqualTo("#(#\\a #\\b)");
		// A value already of the requested type is returned unchanged.
		assertThat(eval("(coerce '(1 2) 'list)").print()).isEqualTo("(1 2)");
		assertThat(eval("(coerce \"hi\" 'string)").print()).isEqualTo("\"hi\"");
	}

	@Test
	void linalgFunctionsLoadLazilyOnFirstUse() {
		assertThat(eval("(linalg:matmul (linalg:from-list '((1 2) (3 4))) (linalg:from-list '((5 6) (7 8))))").print())
			.isEqualTo("#d((19.0 22.0) (43.0 50.0))");
		assertThat(eval("(linalg:det (linalg:from-list '((1 2) (3 4))))").print()).isEqualTo("-2.0");
		// linalg is packed double-float; a power-of-two matrix inverts without roundoff.
		assertThat(eval("(linalg:inv (linalg:from-list '((4 0) (2 4))))").print())
			.isEqualTo("#d((0.25 0.0) (-0.125 0.25))");
		assertThat(eval("(linalg:solve (linalg:from-list '((4 0) (2 4))) (linalg:from-list '(8 8)))").print())
			.isEqualTo("#d(2.0 1.0)");
		assertThat(eval("(linalg:dot (linalg:arange 3) (linalg:from-list '(4 5 6)))").print()).isEqualTo("17.0");
		assertThat(eval("(linalg:add 10 (linalg:from-list '(1 2)))").print()).isEqualTo("#d(11.0 12.0)");
		assertThat(eval("(linalg:argmax (linalg:from-list '(1 9 3)))").print()).isEqualTo("1");
		// #'linalg:norm resolves through the same lazy load.
		assertThat(eval("(funcall #'linalg:norm (linalg:from-list '(3 4)))").print()).isEqualTo("5.0");
	}

	@Test
	void jsonParseReturnsHashTablesAndVectors() {
		// jzon's defaults: objects become hash tables with string keys, arrays
		// become vectors, and true/false/null become t/nil/the symbol null.
		assertThat(eval("""
				(let ((h (rontolisp:json-parse "{\\"name\\": \\"rontolisp\\", \\"n\\": 2}")))
				  (list (gethash "name" h) (gethash "n" h)))""").print()).isEqualTo("(\"rontolisp\" 2)");
		assertThat(eval(
				"(gethash \"b\" (gethash \"a\" (rontolisp:json-parse \"{\\\"a\\\": {\\\"b\\\": [1, true, null]}}\")))")
			.print()).isEqualTo("#(1 T NULL)");
		assertThat(eval("(hash-table-count (rontolisp:json-parse \"{}\"))").print()).isEqualTo("0");
	}

	@Test
	void jsonDoubleColonQualifierNamesTheSameFunctions() {
		// pkg::name also reaches external symbols, like Common Lisp.
		assertThat(eval("(rontolisp::json-stringify (list 1 2 3))").print()).isEqualTo("\"[1,2,3]\"");
		assertThat(eval("(gethash \"n\" (rontolisp::json-parse \"{\\\"n\\\": 5}\"))").print()).isEqualTo("5");
	}

	@Test
	void jsonSingleColonAccessToInternalHelperIsRejected() {
		// %json-parse is internal to the rontolisp package: a single colon only
		// reaches external symbols.
		assertThatThrownBy(() -> eval("(rontolisp:%json-parse \"1\")"))
			.isInstanceOf(am.ik.rontolisp.LispPackageException.class)
			.hasMessageContaining("The symbol %JSON-PARSE is not external in the RONTOLISP package");
	}

	@Test
	void jsonParseParsesScalarsArraysAndEscapes() {
		assertThat(eval("(rontolisp:json-parse \"42\")").print()).isEqualTo("42");
		assertThat(eval("(rontolisp:json-parse \"-3.5\")").print()).isEqualTo("-3.5");
		assertThat(eval("(rontolisp:json-parse \"1e3\")").print()).isEqualTo("1000.0");
		// integers wider than 9 digits become floats on every backend (WASM i31)
		assertThat(eval("(floatp (rontolisp:json-parse \"1234567890123\"))").print()).isEqualTo("T");
		assertThat(eval("(rontolisp:json-parse \"true\")").print()).isEqualTo("T");
		assertThat(eval("(rontolisp:json-parse \"false\")").print()).isEqualTo("NIL");
		// JSON null parses to the symbol null (jzon's sentinel), not nil
		assertThat(eval("(rontolisp:json-parse \"null\")").print()).isEqualTo("NULL");
		assertThat(eval("(rontolisp:json-parse \"[1, [2, \\\"x\\\"], null]\")").print())
			.isEqualTo("#(1 #(2 \"x\") NULL)");
		assertThat(eval("(rontolisp:json-parse \"\\\"a\\\\nb\\\"\")").print()).isEqualTo("\"a\nb\"");
		assertThat(eval("(rontolisp:json-parse \"\\\"\\\\u0041\\\\u3042\\\"\")").print()).isEqualTo("\"Aあ\"");
	}

	@Test
	void jsonParseObjectsAreHashTablesWithStringKeys() {
		assertThat(eval("""
				(gethash "content-type"
				         (rontolisp:json-parse "{\\"content-type\\": \\"text/html\\"}"))""").print())
			.isEqualTo("\"text/html\"");
		// nesting works, and arbitrary key strings (spaces and all) are fine
		assertThat(eval("""
				(let ((h (rontolisp:json-parse "{\\"a b\\": {\\"c\\": 5}}")))
				  (gethash "c" (gethash "a b" h)))""").print()).isEqualTo("5");
	}

	@Test
	void jsonParseSignalsOnInvalidInput() {
		assertThatThrownBy(() -> eval("(rontolisp:json-parse \"{\\\"a\\\": \")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("json-parse");
		assertThatThrownBy(() -> eval("(rontolisp:json-parse \"1 2\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("trailing");
		assertThatThrownBy(() -> eval("(rontolisp:json-parse 42)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a string");
		// json-parse takes exactly the JSON string -- there is no representation argument
		assertThatThrownBy(() -> eval("(rontolisp:json-parse \"1\" :hash-table)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("1 argument");
	}

	@Test
	void jsonStringifySerializesLispValues() {
		// nil is false, the symbol null is null, a list or vector is an array
		assertThat(eval("(rontolisp:json-stringify (list 1 (list 2 3) nil))").print()).isEqualTo("\"[1,[2,3],false]\"");
		assertThat(eval("(rontolisp:json-stringify (vector t 'null 1.5))").print()).isEqualTo("\"[true,null,1.5]\"");
		assertThat(eval("(rontolisp:json-stringify \"a\\\"b\")").print()).isEqualTo("\"\"a\\\"b\"\"");
		assertThat(eval("(rontolisp:json-stringify :key)").print()).isEqualTo("\"\"KEY\"\"");
		assertThat(eval("(rontolisp:json-stringify 3/2)").print()).isEqualTo("\"1.5\"");
		// a hash table becomes an object
		assertThat(eval("""
				(let ((h (make-hash-table :test 'equal)))
				  (setf (gethash "x" h) (list 1 2))
				  (rontolisp:json-stringify h))""").print()).isEqualTo("\"{\"x\":[1,2]}\"");
	}

	@Test
	void jsonRoundTripPreservesStructure() {
		assertThat(eval(
				"(rontolisp:json-stringify (rontolisp:json-parse \"{\\\"deep\\\": {\\\"list\\\": [{\\\"k\\\": \\\"v\\\"}, 2.5, true]}}\"))")
			.print()).isEqualTo("\"{\"deep\":{\"list\":[{\"k\":\"v\"},2.5,true]}}\"");
	}

	@Test
	void jsonFunctionsAreFirstClass() {
		assertThat(eval("(funcall #'rontolisp:json-stringify (list 1 2))").print()).isEqualTo("\"[1,2]\"");
		assertThat(eval("(funcall #'rontolisp:json-parse \"[7]\")").print()).isEqualTo("#(7)");
	}

	@Test
	void plistHashTableAndHashTablePlist() {
		// subsets of alexandria:plist-hash-table / hash-table-plist; keyword keys
		// downcase in the JSON, so the pair builds JSON objects ergonomically
		assertThat(eval("""
				(rontolisp:json-stringify
				 (rontolisp:plist-hash-table (list :name "rontolisp" :ok t :ver 1.5)))""").print())
			.isEqualTo("\"{\"name\":\"rontolisp\",\"ok\":true,\"ver\":1.5}\"");
		assertThat(eval("(rontolisp:hash-table-plist (rontolisp:plist-hash-table (list :a 5)))").print())
			.isEqualTo("(:A 5)");
		// trailing arguments pass through to make-hash-table, like alexandria
		assertThat(eval("(gethash \"k\" (rontolisp:plist-hash-table (list \"k\" 9) :test 'equal))").print())
			.isEqualTo("9");
	}

	@Test
	void alistHashTableAndHashTableAlist() {
		// subsets of alexandria:alist-hash-table / hash-table-alist; an alist
		// (like the request headers or query-params) becomes a JSON object
		assertThat(eval("""
				(rontolisp:json-stringify
				 (rontolisp:alist-hash-table (list (cons "host" "localhost") (cons "n" 2))))""").print())
			.isEqualTo("\"{\"host\":\"localhost\",\"n\":2}\"");
		assertThat(eval("(rontolisp:hash-table-alist (rontolisp:alist-hash-table (list (cons \"k\" 7))))").print())
			.isEqualTo("((\"k\" . 7))");
		// first occurrence of a key wins, like alexandria
		assertThat(eval("""
				(hash-table-count
				 (rontolisp:alist-hash-table (list (cons "a" 1) (cons "a" 9)) :test 'equal))""").print())
			.isEqualTo("1");
	}

	@Test
	void jsonStringifySerializesClosInstancesAsObjects() {
		// a CLOS instance serializes as an object (slots in definition order),
		// matching jzon; a hash-table slot nests as an object, a list slot as an array
		assertThat(evalMulti("""
				(defclass json-resp () ((status :initarg :status) (headers :initarg :headers) (items :initarg :items)))
				(let ((h (make-hash-table :test 'equal)))
				  (setf (gethash "content-type" h) "application/json")
				  (rontolisp:json-stringify
				   (make-instance 'json-resp :status 200 :headers h :items (list 1 2 3))))""").print())
			.isEqualTo("\"{\"status\":200,\"headers\":{\"content-type\":\"application/json\"},\"items\":[1,2,3]}\"");
	}

	@Test
	void urlDecodeDecodesEscapesAndPlus() {
		assertThat(eval("(rontolisp:url-decode \"Will+it+work%3F\")").print()).isEqualTo("\"Will it work?\"");
		assertThat(eval("(rontolisp:url-decode \"%E3%81%82%E3%81%84\")").print()).isEqualTo("\"あい\"");
		assertThat(eval("(rontolisp:url-decode \"%F0%9F%98%80\")").print()).isEqualTo("\"😀\"");
		assertThat(eval("(rontolisp:url-decode \"plain\")").print()).isEqualTo("\"plain\"");
		assertThat(eval("(rontolisp:url-decode \"\")").print()).isEqualTo("\"\"");
	}

	@Test
	void urlDecodeSignalsOnInvalidInput() {
		assertThatThrownBy(() -> eval("(rontolisp:url-decode \"%2\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("unterminated percent escape");
		assertThatThrownBy(() -> eval("(rontolisp:url-decode \"%GG\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("invalid hex digit");
		assertThatThrownBy(() -> eval("(rontolisp:url-decode 42)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a string");
	}

	@Test
	void urlEncodeEncodesReservedAndMultibyte() {
		assertThat(eval("(rontolisp:url-encode \"a b/c~d\")").print()).isEqualTo("\"a%20b%2Fc~d\"");
		assertThat(eval("(rontolisp:url-encode \"あ\")").print()).isEqualTo("\"%E3%81%82\"");
		assertThat(eval("(rontolisp:url-encode \"😀\")").print()).isEqualTo("\"%F0%9F%98%80\"");
		assertThat(eval("(rontolisp:url-encode \"AZaz09-_.~\")").print()).isEqualTo("\"AZaz09-_.~\"");
		assertThat(eval("(rontolisp:url-decode (rontolisp:url-encode \"日本語 text?&=\"))").print())
			.isEqualTo("\"日本語 text?&=\"");
	}

	@Test
	void queryParamsParsesIntoAlist() {
		assertThat(eval("(rontolisp:query-params \"a=1&b=two&flag\")").print())
			.isEqualTo("((\"a\" . \"1\") (\"b\" . \"two\") (\"flag\" . \"\"))");
		assertThat(eval("(rontolisp:query-params \"q=%E3%81%82&q=2\")").print())
			.isEqualTo("((\"q\" . \"あ\") (\"q\" . \"2\"))");
		assertThat(eval("(rontolisp:query-params \"a=1&&b=2&\")").print())
			.isEqualTo("((\"a\" . \"1\") (\"b\" . \"2\"))");
		assertThat(eval("(rontolisp:query-params nil)").print()).isEqualTo("NIL");
		assertThat(eval("(rontolisp:query-params \"\")").print()).isEqualTo("NIL");
	}

	@Test
	void queryParamReturnsFirstDecodedMatchOrNil() {
		assertThat(eval("(rontolisp:query-param \"a=1&name=ronto%20lisp\" \"name\")").print())
			.isEqualTo("\"ronto lisp\"");
		assertThat(eval("(rontolisp:query-param \"q=1&q=2\" \"q\")").print()).isEqualTo("\"1\"");
		assertThat(eval("(rontolisp:query-param \"a=1\" \"missing\")").print()).isEqualTo("NIL");
		assertThat(eval("(rontolisp:query-param nil \"a\")").print()).isEqualTo("NIL");
	}

	@Test
	void urlPathAndUrlQuerySplitAtQuestionMark() {
		assertThat(eval("(rontolisp:url-path \"/get?a=1\")").print()).isEqualTo("\"/get\"");
		assertThat(eval("(rontolisp:url-path \"/get\")").print()).isEqualTo("\"/get\"");
		assertThat(eval("(rontolisp:url-query \"/get?a=1\")").print()).isEqualTo("\"a=1\"");
		assertThat(eval("(rontolisp:url-query \"/get\")").print()).isEqualTo("NIL");
		assertThat(eval("(rontolisp:url-query \"/get?\")").print()).isEqualTo("\"\"");
	}

	@Test
	void urlFunctionsAreFirstClass() {
		assertThat(eval("(mapcar #'rontolisp:url-decode (list \"a%2Bb\" \"1+2\"))").print())
			.isEqualTo("(\"a+b\" \"1 2\")");
		assertThat(eval("(funcall #'rontolisp:url-encode \"x y\")").print()).isEqualTo("\"x%20y\"");
	}

	@Test
	void defunRestCollectsSurplusArguments() {
		assertThat(evalMulti("(defun f (a &rest r) (list a r)) (f 1 2 3)").print()).isEqualTo("(1 (2 3))");
		assertThat(evalMulti("(defun f (a &rest r) (list a r)) (f 1)").print()).isEqualTo("(1 NIL)");
	}

	@Test
	void defunOptionalDefaultsAndSuppliedP() {
		String def = "(defun f (x &optional (y 10) (z (* y 2) zp)) (list x y z zp)) ";
		assertThat(evalMulti(def + "(f 1)").print()).isEqualTo("(1 10 20 NIL)");
		assertThat(evalMulti(def + "(f 1 2)").print()).isEqualTo("(1 2 4 NIL)");
		assertThat(evalMulti(def + "(f 1 2 3)").print()).isEqualTo("(1 2 3 T)");
	}

	@Test
	void defunKeywordArguments() {
		String def = "(defun f (a &key (k 1 kp) m) (list a k kp m)) ";
		assertThat(evalMulti(def + "(f 0)").print()).isEqualTo("(0 1 NIL NIL)");
		assertThat(evalMulti(def + "(f 0 :k 5)").print()).isEqualTo("(0 5 T NIL)");
		assertThat(evalMulti(def + "(f 0 :m 7 :k 9)").print()).isEqualTo("(0 9 T 7)");
	}

	@Test
	void defunKeywordRenamedIndicator() {
		assertThat(evalMulti("(defun f (&key ((:in x) 0)) x) (f :in 42)").print()).isEqualTo("42");
	}

	@Test
	void defunUnknownKeywordSignals() {
		assertThatThrownBy(() -> evalMulti("(defun f (&key k) k) (f :bogus 1)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("Unknown keyword argument: :BOGUS");
		assertThat(evalMulti("(defun f (&key k) k) (f :bogus 1 :allow-other-keys t)").print()).isEqualTo("NIL");
		assertThat(evalMulti("(defun f (&key k &allow-other-keys) k) (f :bogus 1 :k 2)").print()).isEqualTo("2");
	}

	@Test
	void defunOptionalRestKeyCombined() {
		assertThat(evalMulti(
				"(defun f (a &optional b &rest r &key c &allow-other-keys) (list a b r c))" + " (f 1 2 :c 3 :d 4)")
			.print()).isEqualTo("(1 2 (:C 3 :D 4) 3)");
	}

	@Test
	void defunAuxVariables() {
		assertThat(evalMulti("(defun f (x &aux (y (+ x 1)) z) (list x y z)) (f 5)").print()).isEqualTo("(5 6 NIL)");
	}

	@Test
	void lambdaRestWithFuncallAndApply() {
		assertThat(eval("(funcall (lambda (&rest xs) xs) 1 2 3)").print()).isEqualTo("(1 2 3)");
		assertThat(evalMulti("(defun f (a &rest r) (list a r)) (apply #'f 1 (list 2 3))").print())
			.isEqualTo("(1 (2 3))");
		assertThat(eval("(mapcar (lambda (x &optional (y 100)) (+ x y)) (list 1 2 3))").print())
			.isEqualTo("(101 102 103)");
	}

	@Test
	void defunArityMismatchSignals() {
		assertThatThrownBy(() -> evalMulti("(defun f (a b) (+ a b)) (f 1)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects 2 arguments, got 1");
		assertThatThrownBy(() -> evalMulti("(defun f (a b) (+ a b)) (f 1 2 3)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects 2 arguments, got 3");
		assertThatThrownBy(() -> evalMulti("(defun f (a &rest r) r) (f)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects at least 1 argument, got 0");
	}

	@Test
	void defstructConstructorAccessorsAndDefaults() {
		assertThat(evalMulti("""
				(defstruct point x (y 10))
				(setq p (make-point :x 1))
				(list (point-x p) (point-y p))
				""").print()).isEqualTo("(1 10)");
	}

	@Test
	void defstructWithNoSlotsBuildsAnInstance() {
		assertThat(evalMulti("""
				(defstruct empty)
				(list (empty-p (make-empty)) (empty-p 42))
				""").print()).isEqualTo("(T NIL)");
	}

	// The instance tag is written with |...| here because the reader upcases every
	// ordinary symbol: a source-written '%struct-POINT reads as %STRUCT-POINT and can
	// never match a real tag, which is what stops a program forging an instance.
	@Test
	void instancePrimitivesBuildReadWriteAndTestAnInstance() {
		assertThat(evalMulti("""
				(defstruct point x y)
				(setq i (%obj-new '|%struct-POINT| 1 2))
				(%obj-set i 1 99)
				(list (%obj-ref i 0) (%obj-ref i 1) (%obj-is i '|%struct-POINT|)
				      (%obj-is i '|%struct-OTHER|) (%obj-is 5 '|%struct-POINT|)
				      (%obj-tag i) (%obj-p i) (%obj-p '(1 2)))
				""").print()).isEqualTo("(1 99 T NIL NIL %struct-POINT T NIL)");
	}

	@Test
	void instancePrintsInStructSyntax() {
		assertThat(evalMulti("""
				(defstruct point x y)
				(%obj-new '|%struct-POINT| 1 "hi")
				""").print()).isEqualTo("#S(POINT :X 1 :Y \"hi\")");
	}

	@Test
	void classInstancePrintsInAngleSyntax() {
		assertThat(evalMulti("""
				(defclass pt () ((x :initarg :x) (y :initarg :y)))
				(%obj-new '|%class-PT| 5 nil)
				""").print()).isEqualTo("#<PT :X 5 :Y NIL>");
	}

	@Test
	void defstructReturnsStructName() {
		assertThat(eval("(defstruct point x y)").print()).isEqualTo("POINT");
	}

	@Test
	void defstructPredicate() {
		assertThat(evalMulti("""
				(defstruct point x y)
				(defstruct circle r)
				(list (point-p (make-point :x 1 :y 2)) (point-p (make-circle :r 3)) (point-p '(1 2)) (point-p 42))
				""").print()).isEqualTo("(T NIL NIL NIL)");
	}

	@Test
	void defstructAccessorIsASetfPlace() {
		assertThat(evalMulti("""
				(defstruct point x y)
				(setq p (make-point :x 1 :y 2))
				(setf (point-x p) 99)
				(incf (point-y p) 5)
				(list (point-x p) (point-y p))
				""").print()).isEqualTo("(99 7)");
	}

	@Test
	void defstructCopierIsShallowAndIndependent() {
		assertThat(evalMulti("""
				(defstruct point x y)
				(setq p (make-point :x 1 :y 2))
				(setq q (copy-point p))
				(setf (point-x q) 100)
				(list (point-x p) (point-x q) (point-p q))
				""").print()).isEqualTo("(1 100 T)");
	}

	@Test
	void defstructAccessorsAreFirstClassFunctions() {
		assertThat(evalMulti("""
				(defstruct point x y)
				(mapcar #'point-x (list (make-point :x 1) (make-point :x 2)))
				""").print()).isEqualTo("(1 2)");
	}

	@Test
	void defstructSlotDefaultsEvaluatedAtConstructionTime() {
		assertThat(evalMulti("""
				(setq counter 0)
				(defstruct item (id (incf counter)))
				(make-item)
				(make-item)
				(list (item-id (make-item)) (item-id (make-item :id 100)))
				""").print()).isEqualTo("(3 100)");
	}

	@Test
	void defstructInUserPackage() {
		assertThat(evalMulti("""
				(defpackage :geo (:use :cl))
				(in-package :geo)
				(defstruct pt x y)
				(in-package :cl-user)
				(setq p (geo::make-pt :x 3 :y 4))
				(setf (geo::pt-y p) 5)
				(list (geo::pt-x p) (geo::pt-y p) (geo::pt-p p))
				""").print()).isEqualTo("(3 5 T)");
	}

	@Test
	void defstructOptionsRenameAndSuppressGeneratedDefuns() {
		assertThat(evalMulti("""
				(defstruct (point (:constructor %mk-point) (:conc-name pt-) (:copier nil) (:predicate nil))
				  (x 0 :type integer) y)
				(let ((p (%mk-point :x 3 :y 4)))
				  (list (pt-x p) (pt-y p) (fboundp 'point-p) (fboundp 'copy-point)))
				""").print()).isEqualTo("(3 4 NIL NIL)");
	}

	@Test
	void defstructConcNameNilUsesBareSlotNamesAsAccessors() {
		assertThat(evalMulti("""
				(defstruct (st (:constructor %make-st) (:conc-name nil)) (sst 'toplevel :type symbol))
				(sst (%make-st))
				""").print()).isEqualTo("TOPLEVEL");
	}

	@Test
	void defstructIncludeIsNotSupported() {
		assertThatThrownBy(() -> eval("(defstruct (point (:include base)) x y)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("DEFSTRUCT option is not supported");
	}

	@Test
	void defstructUnknownKeywordSignals() {
		assertThatThrownBy(() -> evalMulti("(defstruct point x y) (make-point :z 1)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("Unknown keyword argument: :Z");
	}

	@Test
	void setfFunctionDefinitionAndCallSite() {
		assertThat(evalMulti("""
				(defvar *mode* :xml)
				(defun (setf my-mode) (m) (setq *mode* m))
				(setf (my-mode) :html5)
				*mode*
				""").print()).isEqualTo(":HTML5");
	}

	@Test
	void setfFunctionIsFirstClassViaFunctionQuote() {
		assertThat(evalMulti("""
				(defvar *mode* :xml)
				(defun (setf my-mode) (m) (setq *mode* m))
				(funcall #'(setf my-mode) :sgml)
				*mode*
				""").print()).isEqualTo(":SGML");
	}

	@Test
	void defgenericDefmethodEqlDispatchAndFuncall() {
		assertThat(evalMulti("""
				(defgeneric describe-it (x))
				(defmethod describe-it (x) (list :default x))
				(defmethod describe-it ((x (eql :br))) (list :special x))
				(list (describe-it 5) (describe-it :br) (funcall #'describe-it 9))
				""").print()).isEqualTo("((:DEFAULT 5) (:SPECIAL :BR) (:DEFAULT 9))");
	}

	@Test
	void defclassSlotsAccessorsInheritanceAndClassDispatch() {
		assertThat(evalMulti("""
				(defclass animal () ((name :initarg :name :accessor animal-name)))
				(defclass dog (animal) ((breed :initarg :breed :initform "mixed" :reader dog-breed)))
				(defgeneric speak (x))
				(defmethod speak ((x dog)) "woof")
				(defmethod speak ((x animal)) "...")
				(defmethod speak ((x integer)) "number")
				(defmethod speak (x) "?")
				(setq d (make-instance 'dog :name "Rex"))
				(list (speak d) (speak (make-instance 'animal :name "A")) (speak 1) (speak "s")
				      (animal-name d) (dog-breed d) (slot-value d 'name))
				""").print()).isEqualTo("(\"woof\" \"...\" \"number\" \"?\" \"Rex\" \"mixed\" \"Rex\")");
	}

	@Test
	void listSpecializedMethodDoesNotCaptureClassInstances() {
		// An instance is a tagged cons internally, but must dispatch to the
		// standard-object/default method, not a list/cons/sequence-specialized one --
		// even when the class is defined AFTER the generic (jzon's write-value).
		assertThat(evalMulti("""
				(defgeneric spx-kind (x)
				  (:method (x) :object)
				  (:method ((x list)) :list)
				  (:method ((x standard-object)) :instance))
				(defclass spx-thing () ((v :initarg :v)))
				(list (spx-kind (make-instance 'spx-thing :v 1)) (spx-kind '(1 2)) (spx-kind 5))
				""").print()).isEqualTo("(:INSTANCE :LIST :OBJECT)");
	}

	@Test
	void slotValueAcceptsAComputedSlotName() {
		// A serializer walking slot names as data (jzon's coerced-fields) reads
		// (slot-value obj name-variable); the literal-name path stays positional.
		assertThat(evalMulti("""
				(defclass svx-p () ((name :initarg :name) (age :initarg :age)))
				(setq svx (make-instance 'svx-p :name "Anya" :age 6))
				(list (slot-value svx 'name) (let ((n 'age)) (slot-value svx n)))
				""").print()).isEqualTo("(\"Anya\" 6)");
	}

	@Test
	void classSlotDefsReturnsSlotNamesAndDeclaredTypes() {
		// %class-slot-defs feeds the closer-mop shim's class-slots: (name type) pairs,
		// :type recorded (t when omitted), keyed by the class-of tag symbol.
		assertThat(evalMulti("""
				(defclass csd-p () ((name :initarg :name) (married :initarg :married :type boolean)))
				(%class-slot-defs (class-of (make-instance 'csd-p)))
				""").print()).isEqualTo("((NAME T) (MARRIED BOOLEAN))");
	}

	@Test
	void macroletLocalMacroExpandsInsideCapturedDefgenericMethodBody() {
		// The macrolet body is pre-expanded before evaluation, so a method body that
		// only CAPTURES code still bakes the local macro in (jzon defines its
		// %coerced-fields-slots template this way).
		assertThat(evalMulti("""
				(macrolet ((mlx-double (x) (list '* 2 x)))
				  (defgeneric mlx-f (a)
				    (:method (a) (mlx-double a))))
				(mlx-f 21)
				""").print()).isEqualTo("42");
	}

	@Test
	void defclassAccessorAndSlotValueAreSetfPlaces() {
		assertThat(evalMulti("""
				(defclass counter () ((n :initarg :n :accessor counter-n)))
				(setq c (make-instance 'counter :n 1))
				(setf (counter-n c) 10)
				(incf (counter-n c))
				(setf (slot-value c 'n) (+ (slot-value c 'n) 100))
				(counter-n c)
				""").print()).isEqualTo("111");
	}

	@Test
	void defclassDefinedAfterMethodExtendsClassDispatch() {
		// The interpreter regenerates class-specialized dispatchers on defclass, so a
		// subclass defined AFTER the method still matches it.
		assertThat(evalMulti("""
				(defclass animal () ())
				(defgeneric speak (x))
				(defmethod speak ((x animal)) :animal)
				(defclass cat (animal) ())
				(speak (make-instance 'cat))
				""").print()).isEqualTo(":ANIMAL");
	}

	@Test
	void defmethodRedefinitionReplacesSameSpecializer() {
		assertThat(evalMulti("""
				(defgeneric f (x))
				(defmethod f (x) :old)
				(defmethod f (x) :new)
				(defmethod f ((x (eql 1))) :one)
				(defmethod f ((x (eql 1))) :uno)
				(list (f 0) (f 1))
				""").print()).isEqualTo("(:NEW :UNO)");
	}

	@Test
	void defgenericReturnsNameAndRecordsDocumentation() {
		assertThat(eval("(defgeneric g (x) (:documentation \"doc\"))").print()).isEqualTo("G");
	}

	@Test
	void defgenericNoApplicableMethodSignals() {
		assertThatThrownBy(() -> evalMulti("(defgeneric g (x)) (g 1)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("No applicable method: G");
	}

	@Test
	void defmethodBeforeAndAfterQualifiersRunAroundThePrimary() {
		// :before methods run most-specific-first, :after least-specific-first, and the
		// primary value is returned.
		assertThat(evalMulti("""
				(defclass animal () ())
				(defclass dog (animal) ())
				(defparameter *log* nil)
				(defgeneric touch (x))
				(defmethod touch ((x animal)) (push :primary-animal *log*) :done)
				(defmethod touch ((x dog)) (push :primary-dog *log*) (call-next-method))
				(defmethod touch :before ((x animal)) (push :before-animal *log*))
				(defmethod touch :before ((x dog)) (push :before-dog *log*))
				(defmethod touch :after ((x animal)) (push :after-animal *log*))
				(defmethod touch :after ((x dog)) (push :after-dog *log*))
				(list (touch (make-instance 'dog)) (reverse *log*))
				""").print())
			.isEqualTo("(:DONE (:BEFORE-DOG :BEFORE-ANIMAL :PRIMARY-DOG :PRIMARY-ANIMAL :AFTER-ANIMAL :AFTER-DOG))");
	}

	@Test
	void callNextMethodChainsPrimariesAndNextMethodP() {
		assertThat(evalMulti("""
				(defclass base () ())
				(defclass mid (base) ())
				(defclass leaf (mid) ())
				(defgeneric describe-chain (x))
				(defmethod describe-chain ((x base)) (list :base (next-method-p)))
				(defmethod describe-chain ((x mid)) (cons :mid (call-next-method)))
				(defmethod describe-chain ((x leaf)) (cons :leaf (call-next-method)))
				(describe-chain (make-instance 'leaf))
				""").print()).isEqualTo("(:LEAF :MID :BASE NIL)");
	}

	@Test
	void aroundMethodWrapsAndCallNextMethodInvokesTheCore() {
		assertThat(evalMulti("""
				(defclass thing () ())
				(defgeneric render (x))
				(defmethod render ((x thing)) :inner)
				(defmethod render :around ((x thing)) (list :before-around (call-next-method) :after-around))
				(render (make-instance 'thing))
				""").print()).isEqualTo("(:BEFORE-AROUND :INNER :AFTER-AROUND)");
	}

	@Test
	void callNextMethodWithNewArguments() {
		assertThat(evalMulti("""
				(defgeneric g (x))
				(defmethod g (x) (list :default x))
				(defmethod g ((x integer)) (call-next-method (* x 10)))
				(g 5)
				""").print()).isEqualTo("(:DEFAULT 50)");
	}

	@Test
	void callNextMethodWithNoNextMethodSignals() {
		assertThatThrownBy(() -> evalMulti("""
				(defgeneric g (x))
				(defmethod g (x) (call-next-method))
				(g 1)
				""")).isInstanceOf(LispEvalException.class).hasMessageContaining("no next method");
	}

	@Test
	void defmethodDispatchesOnALaterParameterSpecializer() {
		assertThat(evalMulti("""
				(defmethod g (a (b integer)) :int)
				(defmethod g (a (b string)) :str)
				(defmethod g (a b) :default)
				(list (g 1 2) (g 1 "x") (g 1 'sym))
				""").print()).isEqualTo("(:INT :STR :DEFAULT)");
	}

	@Test
	void defmethodOrdersMultiParameterSpecializersLeftmostFirst() {
		// CL's method ordering: the leftmost parameter's specificity dominates.
		assertThat(evalMulti("""
				(defmethod h ((a integer) b) :int-any)
				(defmethod h (a (b integer)) :any-int)
				(h 1 2)
				""").print()).isEqualTo(":INT-ANY");
	}

	@Test
	void defgenericInlineMethodsAndVariadicLambdaListDispatch() {
		assertThat(evalMulti("""
				(defgeneric wri (x &optional suffix)
				  (:method ((x integer) &optional suffix) (list :int suffix))
				  (:method ((x string) &optional (suffix :none)) (list :str suffix)))
				(list (wri 1 'a) (wri "s"))
				""").print()).isEqualTo("((:INT A) (:STR :NONE))");
	}

	@Test
	void defmethodLambdaListMustMatchTheGeneric() {
		assertThatThrownBy(() -> evalMulti("(defgeneric g (x y)) (defmethod g (x) x)"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("does not match the generic function");
	}

	@Test
	void defclassMultipleInheritanceIsNotSupported() {
		assertThatThrownBy(() -> evalMulti("(defclass a () ()) (defclass b () ()) (defclass c (a b) ())"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("at most one superclass");
	}

	@Test
	void defclassUnsupportedSlotOptionSignals() {
		assertThatThrownBy(() -> eval("(defclass a () ((x :allocation :class)))"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("slot option :ALLOCATION is not supported");
	}

	@Test
	void makeInstanceRequiresALiteralClassName() {
		assertThatThrownBy(() -> evalMulti("(defclass a () ()) (setq n 'a) (make-instance n)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("requires a literal quoted class name");
	}

	@Test
	void closInUserPackage() {
		assertThat(evalMulti("""
				(defpackage :zoo (:use :cl) (:export :speak :make-dog))
				(in-package :zoo)
				(defclass dog () ((name :initarg :name :accessor dog-name)))
				(defgeneric speak (x))
				(defmethod speak ((x dog)) (list :woof (dog-name x)))
				(defmethod speak (x) :silence)
				(defun make-dog (name) (make-instance 'dog :name name))
				(in-package :cl-user)
				(list (zoo:speak (zoo:make-dog "Rex")) (zoo:speak 42))
				""").print()).isEqualTo("((:WOOF \"Rex\") :SILENCE)");
	}

	@Test
	void fillPointerLengthAndAccessors() {
		assertThat(evalMulti("""
				(setq v (make-array 5 :fill-pointer 2 :initial-element 0))
				(list (length v) (fill-pointer v) (array-has-fill-pointer-p v) (adjustable-array-p v))
				""").print()).isEqualTo("(2 2 T NIL)");
	}

	@Test
	void fillPointerVectorPrintsUpToFillPointer() {
		assertThat(eval("""
				(let ((v (make-array 5 :fill-pointer 3 :initial-element 9)))
				  (prin1-to-string v))
				""")).isEqualTo(new LispString("#(9 9 9)"));
	}

	@Test
	void vectorPushStoresAndReturnsIndexOrNil() {
		assertThat(evalMulti("""
				(setq v (make-array 3 :fill-pointer 0))
				(list (vector-push 10 v) (vector-push 20 v) (vector-push 30 v) (vector-push 40 v))
				""").print()).isEqualTo("(0 1 2 NIL)");
	}

	@Test
	void vectorPushThenReadBack() {
		assertThat(evalMulti("""
				(setq v (make-array 3 :fill-pointer 0))
				(vector-push 10 v)
				(vector-push 20 v)
				(list (length v) (aref v 0) (aref v 1))
				""").print()).isEqualTo("(2 10 20)");
	}

	@Test
	void vectorPop() {
		assertThat(evalMulti("""
				(setq v (make-array 5 :fill-pointer 3 :initial-element 0))
				(setf (aref v 2) 99)
				(list (vector-pop v) (length v))
				""").print()).isEqualTo("(99 2)");
	}

	@Test
	void vectorPushExtendGrowsBeyondCapacity() {
		assertThat(evalMulti("""
				(setq v (make-array 2 :fill-pointer 0 :adjustable t))
				(vector-push-extend 1 v)
				(vector-push-extend 2 v)
				(vector-push-extend 3 v)
				(list (length v) (adjustable-array-p v) (aref v 2))
				""").print()).isEqualTo("(3 T 3)");
	}

	@Test
	void setfFillPointer() {
		assertThat(evalMulti("""
				(setq v (make-array 5 :fill-pointer 5 :initial-element 7))
				(setf (fill-pointer v) 2)
				(list (length v) (fill-pointer v))
				""").print()).isEqualTo("(2 2)");
	}

	@Test
	void simpleVectorHasNoFillPointer() {
		assertThat(evalMulti("""
				(setq v (make-array 3 :initial-element 0))
				(list (array-has-fill-pointer-p v) (adjustable-array-p v))
				""").print()).isEqualTo("(NIL NIL)");
	}

	@Test
	void fillPointerOnNonFillPointerVectorSignals() {
		assertThatThrownBy(() -> eval("(fill-pointer (make-array 3))")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("no fill pointer");
	}

	@Test
	void clUtilitiesCopyArrayRunsOnInterpreter() {
		// The cl-utilities copy-array definition verbatim, the headline adjustable-array
		// exercise: verifies array-element-type, make-array
		// :element-type/:adjustable/:fill-pointer,
		// array-has-fill-pointer-p, fill-pointer, adjustable-array-p, array-total-size
		// and
		// row-major-aref all cooperate.
		assertThat(evalMulti("""
				(defun copy-array (array &key
				                   (element-type (array-element-type array))
				                   (fill-pointer (and (array-has-fill-pointer-p array)
				                                      (fill-pointer array)))
				                   (adjustable (adjustable-array-p array)))
				  (let* ((dimensions (array-dimensions array))
				         (new-array (make-array dimensions
				                                :element-type element-type
				                                :adjustable adjustable
				                                :fill-pointer fill-pointer)))
				    (dotimes (i (array-total-size array))
				      (setf (row-major-aref new-array i)
				            (row-major-aref array i)))
				    new-array))
				(setq src (make-array 5 :fill-pointer 3 :adjustable t :initial-element 0))
				(setf (aref src 0) 11)
				(setf (aref src 1) 22)
				(setf (aref src 2) 33)
				(setq dst (copy-array src))
				(list (length dst) (aref dst 0) (aref dst 1) (aref dst 2)
				      (array-has-fill-pointer-p dst) (adjustable-array-p dst) (eq src dst))
				""").print()).isEqualTo("(3 11 22 33 T T NIL)");
	}

	@Test
	void adjustArrayGrowsNonAdjustableIntoFreshArray() {
		assertThat(evalMulti("""
				(setq v (make-array 3 :initial-element 7))
				(setq v2 (adjust-array v 5 :initial-element 0))
				(list v2 (eq v v2))
				""").print()).isEqualTo("(#(7 7 7 0 0) NIL)");
	}

	@Test
	void adjustArrayAdjustsAdjustableInPlace() {
		assertThat(evalMulti("""
				(setq w (make-array 3 :adjustable t :initial-element 1))
				(setq w2 (adjust-array w 5 :initial-element 9))
				(list (eq w w2) w)
				""").print()).isEqualTo("(T #(1 1 1 9 9))");
	}

	@Test
	void adjustArrayPreservesElementsBySubscriptsOnRank2() {
		// Resizing a 2x2 to 3x3 keeps (i, j) at (i, j) -- NOT a flat copy.
		assertThat(evalMulti("""
				(setq m (make-array '(2 2) :initial-element 0))
				(setf (aref m 0 0) 1) (setf (aref m 0 1) 2)
				(setf (aref m 1 0) 3) (setf (aref m 1 1) 4)
				(adjust-array m '(3 3) :initial-element 0)
				""").print()).isEqualTo("#2A((1 2 0) (3 4 0) (0 0 0))");
	}

	@Test
	void adjustArrayCarriesTheFillPointerOver() {
		assertThat(evalMulti("""
				(setq fv (make-array 4 :fill-pointer 2 :initial-element 5))
				(fill-pointer (adjust-array fv 8))
				""").print()).isEqualTo("2");
	}

	@Test
	void adjustArrayErrors() {
		assertThatThrownBy(() -> evalMulti("(adjust-array (make-array '(2 2)) 5)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("rank mismatch");
		assertThatThrownBy(() -> evalMulti("(adjust-array (make-array 2 :displaced-to (make-array 5)) 3)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("displaced arrays are not supported");
	}

	@Test
	void displacedArrayAliasesTheTargetStorage() {
		assertThat(evalMulti("""
				(setq base (make-array 6 :initial-element 0))
				(dotimes (i 6) (setf (aref base i) (* i 10)))
				(setq view (make-array 3 :displaced-to base :displaced-index-offset 2))
				(setf (aref view 0) 99)
				(setq r1 (aref base 2))
				(setf (aref base 4) 111)
				(list view r1 (aref view 2) (length view))
				""").print()).isEqualTo("(#(99 30 111) 99 111 3)");
	}

	@Test
	void displacedArrayOverRank2GivesARowView() {
		assertThat(evalMulti("""
				(setq mat (make-array '(2 3) :initial-element 0))
				(dotimes (i 6) (%row-major-aset mat i i))
				(make-array 3 :displaced-to mat :displaced-index-offset 3)
				""").print()).isEqualTo("#(3 4 5)");
	}

	@Test
	void displacedArraySeesTheTargetGrowInPlace() {
		// adjust-array on an adjustable target replaces its storage IN PLACE, so an
		// existing displaced view follows the new storage (chain-resolved access).
		assertThat(evalMulti("""
				(setq tgt (make-array 4 :adjustable t :initial-element 1))
				(setq v (make-array 2 :displaced-to tgt :displaced-index-offset 1))
				(adjust-array tgt 6 :initial-element 8)
				(setf (aref tgt 1) 55)
				(aref v 0)
				""").print()).isEqualTo("55");
	}

	@Test
	void arrayDisplacementReturnsTargetAndOffset() {
		assertThat(evalMulti("""
				(setq base (make-array 5))
				(setq view (make-array 2 :displaced-to base :displaced-index-offset 3))
				(multiple-value-bind (tgt off) (array-displacement view)
				  (list (eq tgt base) off))
				""").print()).isEqualTo("(T 3)");
		assertThat(evalMulti("""
				(multiple-value-bind (tgt off) (array-displacement (make-array 2))
				  (list tgt off))
				""").print()).isEqualTo("(NIL 0)");
	}

	@Test
	void makeArrayDisplacedErrors() {
		assertThatThrownBy(() -> evalMulti("(make-array 3 :displaced-to (make-array 5) :fill-pointer 2)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("cannot be combined");
		assertThatThrownBy(() -> evalMulti("(make-array 4 :displaced-to (make-array 3) :displaced-index-offset 2)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("too small");
	}

	// --- Dynamic (special) variable binding ---

	@Test
	void specialVarLetBindingHasDynamicExtent() {
		// let of a defvar special rebinds it dynamically; the old value is restored on
		// exit.
		assertThat(evalMulti("""
				(defvar *x* 10)
				(list *x* (let ((*x* 20)) *x*) *x*)
				""").print()).isEqualTo("(10 20 10)");
	}

	@Test
	void specialVarBindingVisibleAcrossFunctionCalls() {
		// The dynamic binding is visible to a called function, not just lexically nested
		// code.
		assertThat(evalMulti("""
				(defvar *y* 1)
				(defun get-y () *y*)
				(list (get-y) (let ((*y* 2)) (get-y)) (get-y))
				""").print()).isEqualTo("(1 2 1)");
	}

	@Test
	void specialVarLetIsParallel() {
		// let is parallel: a later init sees the OUTER value of an earlier special
		// binding.
		assertThat(evalMulti("""
				(defvar *a* 1)
				(let ((*a* 2) (b *a*)) (list *a* b))
				""").print()).isEqualTo("(2 1)");
	}

	@Test
	void specialVarLetStarIsSequential() {
		// let* is sequential: a later init sees the NEW value of an earlier special
		// binding.
		assertThat(evalMulti("""
				(defvar *a* 1)
				(let* ((*a* 2) (b *a*)) (list *a* b))
				""").print()).isEqualTo("(2 2)");
	}

	@Test
	void specialVarSetqAffectsCurrentBinding() {
		// setq of a bound special changes the dynamic binding, not the global default.
		assertThat(evalMulti("""
				(defvar *x* 10)
				(list (let ((*x* 1)) (setq *x* 2) *x*) *x*)
				""").print()).isEqualTo("(2 10)");
	}

	@Test
	void specialVarNestedBindingsStack() {
		assertThat(evalMulti("""
				(defvar *x* 1)
				(list *x* (let ((*x* 2)) (let ((*x* 3)) *x*)) (let ((*x* 2)) *x*) *x*)
				""").print()).isEqualTo("(1 3 2 1)");
	}

	@Test
	void specialVarRestoredOnNonLocalExit() {
		// A return that unwinds through the special let must still restore the binding.
		assertThat(evalMulti("""
				(defvar *x* 0)
				(defun outer () (dolist (i '(1)) (let ((*x* 9)) (return *x*))))
				(list (outer) *x*)
				""").print()).isEqualTo("(9 0)");
	}

	@Test
	void specialVarRestoredOnErrorUnwind() {
		// An error unwinding out of the let restores the binding (interpreter keeps
		// running).
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.eval(LispReader.readFromString("(defvar *x* 7)"));
		assertThatThrownBy(() -> evaluator.eval(LispReader.readFromString("(let ((*x* 99)) (error \"boom\"))")))
			.isInstanceOf(LispEvalException.class);
		assertThat(evaluator.eval(LispReader.readFromString("*x*")).print()).isEqualTo("7");
	}

	@Test
	void defparameterIsSpecial() {
		assertThat(evalMulti("""
				(defparameter *p* 5)
				(list (let ((*p* 6)) *p*) *p*)
				""").print()).isEqualTo("(6 5)");
	}

	@Test
	void declaimSpecialMakesVariableDynamic() {
		assertThat(evalMulti("""
				(declaim (special *s*))
				(setq *s* 100)
				(list (let ((*s* 7)) *s*) *s*)
				""").print()).isEqualTo("(7 100)");
	}

	@Test
	void proclaimSpecialMakesVariableDynamic() {
		assertThat(evalMulti("""
				(proclaim '(special *s*))
				(setq *s* 100)
				(list (let ((*s* 7)) *s*) *s*)
				""").print()).isEqualTo("(7 100)");
	}

	@Test
	void lexicalVariablesUnaffectedBySpecials() {
		// A non-special var is still lexical: it does not leak into a called function.
		assertThat(evalMulti("""
				(defvar *x* 1)
				(defun f (n) n)
				(let ((x 5)) (f 10))
				""").print()).isEqualTo("10");
		// Shadowing a plain lexical stays lexical.
		assertThat(evalMulti("(let ((a 1)) (let ((a 2)) a))").print()).isEqualTo("2");
	}

	@Test
	void progvBindsRuntimeComputedSpecials() {
		assertThat(evalMulti("(progv '(foo bar) '(10 20) (list (symbol-value 'foo) (symbol-value 'bar)))").print())
			.isEqualTo("(10 20)");
		// A progv-bound symbol reads directly too, and is unbound again after the extent.
		assertThat(evalMulti("(progv '(foo) '(42) foo)").print()).isEqualTo("42");
		assertThat(evalMulti("(progv '(foo) '(42) foo) (boundp 'foo)").print()).isEqualTo("NIL");
	}

	@Test
	void symbolValueAndBoundpSeeDynamicBinding() {
		assertThat(evalMulti("""
				(defvar *x* 1)
				(let ((*x* 42)) (list (symbol-value '*x*) (boundp '*x*)))
				""").print()).isEqualTo("(42 T)");
	}

	@Test
	void specialVariablesAreThreadScoped() throws Exception {
		// The flagship acceptance case: one shared evaluator (like the HTTP handler,
		// which
		// serves one virtual thread per request), concurrent dynamic bindings of the same
		// special must not leak across threads.
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.eval(LispReader.readFromString("(defvar *ctx* 0)"));
		int threads = 8;
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
		java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threads);
		java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
		for (int t = 1; t <= threads; t++) {
			final int id = t;
			futures.add(pool.submit(() -> {
				barrier.await();
				// Bind *ctx* to this thread's id, churn (widening the overlap window),
				// and
				// read it back; a shared-global implementation would see another thread's
				// id.
				LispVal r = evaluator
					.eval(LispReader.readFromString("(let ((*ctx* " + id + ")) (dotimes (i 20000) (+ i 1)) *ctx*)"));
				return r.print();
			}));
		}
		for (int t = 1; t <= threads; t++) {
			assertThat(futures.get(t - 1).get()).isEqualTo(String.valueOf(t));
		}
		pool.shutdown();
	}

	// ---- IEEE-754 float edge semantics: the interpreter's comparison group ----

	@Test
	void negativeZeroComparesEqualToPositiveZero() {
		assertThat(eval("(= 0.0 -0.0)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(= 0 -0.0)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(< -0.0 0.0)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(> 0.0 -0.0)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(<= 0.0 -0.0)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(>= -0.0 0.0)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(sort (list 0.0 -0.0) #'<)").print()).isEqualTo("(0.0 -0.0)");
	}

	@Test
	void negativeZeroPredicatesMatchTheirFunctionValues() {
		// The direct call and the #' function value used to disagree on the
		// interpreter (the call expands to (= x 0) over compareNumeric, the
		// function value is Environment's own IEEE definition).
		assertThat(eval("(zerop (* -1.0 0.0))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(minusp (* -1.0 0.0))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(plusp (* -1.0 0.0))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(funcall #'zerop (* -1.0 0.0))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(funcall #'minusp (* -1.0 0.0))")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void nanComparisonsAreUnordered() {
		assertThat(eval("(= (/ 0.0 0.0) (/ 0.0 0.0))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(< (/ 0.0 0.0) 1.0)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(> (/ 0.0 0.0) 1.0)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(<= (/ 0.0 0.0) 1.0)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(>= (/ 0.0 0.0) 1.0)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(/= (/ 0.0 0.0) (/ 0.0 0.0))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(let ((n (/ 0.0 0.0))) (= n n))")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void minMaxHandleSignedZerosAndNansLikeMathMinMax() {
		assertThat(eval("(min 0.0 -0.0)")).isEqualTo(new LispDouble(-0.0));
		assertThat(eval("(min -0.0 0.0)")).isEqualTo(new LispDouble(-0.0));
		assertThat(eval("(max -0.0 0.0)")).isEqualTo(new LispDouble(0.0));
		assertThat(eval("(max 0.0 -0.0)")).isEqualTo(new LispDouble(0.0));
		assertThat(eval("(min 0 -0.0)")).isEqualTo(new LispDouble(-0.0));
		assertThat(eval("(min 1.0 (/ 0.0 0.0))")).isEqualTo(new LispDouble(Double.NaN));
		assertThat(eval("(min (/ 0.0 0.0) 1.0)")).isEqualTo(new LispDouble(Double.NaN));
		assertThat(eval("(max 1.0 (/ 0.0 0.0))")).isEqualTo(new LispDouble(Double.NaN));
		assertThat(eval("(max (/ 0.0 0.0) 1.0)")).isEqualTo(new LispDouble(Double.NaN));
		// float contagion on the result is unchanged
		assertThat(eval("(min 1 2.0)")).isEqualTo(new LispDouble(1.0));
	}

	// ---- rontolisp:wit-import: one WIT, a provider per backend ----
	//
	// The interpreter half of the boundary. The same programs are compiled and RUN on the
	// JVM backend by JvmLispCompilerTest (the compile path lowers the directive through
	// WitImportInliner instead of evaluating it), and the Preview 1 WASM half -- where
	// the
	// host is the provider and the lowering is a rontolisp:wasm-import block -- is pinned
	// by WitImportInlinerTest. One source, three bindings.

	/**
	 * The interface the tests bind: a wasi:keyvalue-shaped store, whose {@code bucket} is
	 * a WIT resource (so its methods bind as {@code bucket-get} / {@code bucket-set} /
	 * ... taking the handle as their first argument), with a {@code variant} error arm
	 * and a freestanding opener.
	 */
	private static final String KEYVALUE_WIT = """
			package wasi:keyvalue@0.2.0;

			interface store {
			  variant error {
			    no-such-store,
			    access-denied,
			    other(string),
			  }

			  resource bucket {
			    get: func(key: string) -> result<option<list<u8>>, error>;
			    set: func(key: string, value: list<u8>) -> result<_, error>;
			    delete: func(key: string) -> result<_, error>;
			    exists: func(key: string) -> result<bool, error>;
			    list-keys: func() -> result<list<string>, error>;
			  }

			  open: func(identifier: string) -> result<bucket, error>;
			}
			""";

	/**
	 * A second interface, bound by the tests that pin what a call with NO provider does.
	 */
	private static final String WEBGL_WIT = """
			package local:webgl;

			interface gl {
			  create-shader: func(kind: s32) -> s32;
			}
			""";

	/**
	 * The store the tests bind -- an in-memory wasi:keyvalue/store, the shape of
	 * {@code examples/wit/keyvalue/memory-store.lisp}.
	 *
	 * <p>
	 * rontolisp ships NO provider for any concrete interface: the core knows the provider
	 * MECHANISM, and nothing about what wasi:keyvalue is. Implementing a WIT interface is
	 * therefore ordinary user code, so a test writes its own store exactly as a program
	 * does -- and if this file's stopped compiling because a built-in store went away,
	 * the fix is to write one here, never to put one back in the core.
	 *
	 * <p>
	 * A provider is an ordinary Lisp callable taking the bound function's Lisp member
	 * NAME (a string: {@code "open"}, {@code "bucket-get"}) and then that function's
	 * arguments, a resource method's handle included. The ok arm of a WIT result IS the
	 * return value, so nothing is wrapped -- {@code get} answers
	 * {@code option<list<u8>>}, which is the value string or nil -- and the error arm
	 * SIGNALS {@code rontolisp:wit-error}, which is what an unknown handle does here.
	 */
	private static final String MEMORY_STORE = """
			(defvar *buckets* (make-hash-table :test #'eql))
			(defvar *next-handle* 1)
			(defun store-bucket (handle)
			  (let ((bucket (gethash handle *buckets*)))
			    (if (null bucket)
			        (error 'rontolisp:wit-error :payload :no-such-store
			               :message "memory store: not an open bucket handle")
			        bucket)))
			(defun memory-store (member &rest args)
			  (cond ((string= member "open")
			         (let ((handle *next-handle*))
			           (setq *next-handle* (+ handle 1))
			           (setf (gethash handle *buckets*) (make-hash-table :test #'equal))
			           handle))
			        ((string= member "bucket-get")
			         (gethash (nth 1 args) (store-bucket (nth 0 args))))
			        ((string= member "bucket-set")
			         (setf (gethash (nth 1 args) (store-bucket (nth 0 args))) (nth 2 args))
			         nil)
			        ((string= member "bucket-delete")
			         (remhash (nth 1 args) (store-bucket (nth 0 args)))
			         nil)
			        ((string= member "bucket-exists")
			         (if (gethash (nth 1 args) (store-bucket (nth 0 args))) t nil))
			        ((string= member "bucket-list-keys")
			         (let ((keys nil))
			           (maphash (lambda (key value) value (push key keys))
			                    (store-bucket (nth 0 args)))
			           (nreverse keys)))
			        (t (error 'rontolisp:wit-error :payload (list :other member)
			                  :message "memory store: no such member"))))
			(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'memory-store)
			""";

	// Writes a WIT file and returns the (rontolisp:wit-import ...) directive binding it.
	// The path is absolute because these programs are evaluated form by form, like the
	// REPL: there is no source file for a relative path to resolve against.
	private static String witImport(Path tempDir, String file, String wit, String iface, String pkg) throws Exception {
		Path path = tempDir.resolve(file);
		Files.writeString(path, wit);
		return "(rontolisp:wit-import \"" + path.toString().replace("\\", "\\\\") + "\" :interface \"" + iface
				+ "\" :package " + pkg + ")\n";
	}

	private static String keyvalueImport(Path tempDir) throws Exception {
		return witImport(tempDir, "kv.wit", KEYVALUE_WIT, "wasi:keyvalue/store@0.2.0", "kv");
	}

	@Test
	void witImportRunsAgainstTheProviderTheProgramBinds(@TempDir Path tempDir) throws Exception {
		// The whole interface, driven end to end through a store the PROGRAM binds: a
		// resource is an opaque integer handle, a value is a byte string (list<u8>), and
		// a
		// missing key is nil (option<list<u8>> = value-or-nil, NOT an error).
		// The writes are separate top-level forms, and only pure reads are grouped into a
		// list, so nothing here depends on argument evaluation order.
		assertThat(evalMulti(keyvalueImport(tempDir) + MEMORY_STORE + """
				(defvar *b* (kv:open "cache"))
				(kv:bucket-set *b* "greeting" "hello")
				(kv:bucket-set *b* "count" "3")
				(defvar *stored* (list (integerp *b*)
				                       (kv:bucket-get *b* "greeting")
				                       (kv:bucket-get *b* "absent")
				                       (kv:bucket-exists *b* "greeting")
				                       (sort (kv:bucket-list-keys *b*) #'string<)))
				(kv:bucket-delete *b* "greeting")
				(append *stored* (list (kv:bucket-exists *b* "greeting")
				                       (sort (kv:bucket-list-keys *b*) #'string<)))
				""").print()).isEqualTo("(T \"hello\" NIL T (\"count\" \"greeting\") NIL (\"count\"))");
	}

	@Test
	void witImportBindingsAreOrdinaryDefunsSoTheyWorkAsValues(@TempDir Path tempDir) throws Exception {
		// The property most likely to regress: a binding is an ORDINARY defun, not a
		// special call form, so #'kv:bucket-get / funcall / apply / mapcar need no wiring
		// of their own.
		assertThat(evalMulti(keyvalueImport(tempDir) + MEMORY_STORE + """
				(defvar *b* (kv:open "cache"))
				(kv:bucket-set *b* "a" "1")
				(kv:bucket-set *b* "b" "2")
				(list (functionp #'kv:bucket-get)
				      (funcall #'kv:bucket-get *b* "a")
				      (apply #'kv:bucket-get (list *b* "b"))
				      (mapcar (lambda (k) (funcall #'kv:bucket-get *b* k)) '("a" "b" "zz")))
				""").print()).isEqualTo("(T \"1\" \"2\" (\"1\" \"2\" NIL))");
	}

	@Test
	void aSecondWitProvideReplacesTheFirst(@TempDir Path tempDir) throws Exception {
		// Binding a provider REPLACES whatever was bound for the interface before it, and
		// that is what makes a store swappable: examples/wit/keyvalue develops against an
		// in-memory store and then, on the JVM, binds a java.util.LinkedHashMap-backed
		// one
		// over the top of it -- one line, and not a character of the program changes.
		// Here
		// the memory store is bound first and a stub replaces it, so every call below
		// lands
		// in the stub.
		assertThat(evalMulti(keyvalueImport(tempDir) + MEMORY_STORE + """
				(defvar *writes* nil)
				(defun stub-store (member &rest args)
				  (cond ((string= member "open") 7)
				        ((string= member "bucket-set")
				         (setq *writes* (cons (nth 1 args) *writes*))
				         nil)
				        ((string= member "bucket-get")
				         (concatenate 'string "stub:" (nth 1 args)))
				        (t (error 'rontolisp:wit-error :payload (list :other member)
				                  :message "the stub store does not implement it"))))
				(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'stub-store)
				(defvar *b* (kv:open "cache"))
				(kv:bucket-set *b* "k" "v")
				(list *b* (kv:bucket-get *b* "k") *writes*)
				""").print()).isEqualTo("(7 \"stub:k\" (\"k\"))");
	}

	@Test
	void witErrorArmSignalsWitErrorCarryingTheMappedPayload(@TempDir Path tempDir) throws Exception {
		// The settled mapping: a WIT result<T, E>'s ok arm is the value and its error arm
		// SIGNALS rontolisp:wit-error, whose payload is the mapped E. The PROVIDER is
		// what
		// signals it -- so both arms below come out of a store written in the test: the
		// memory store's `no-such-store` for a handle it never handed out (an enum-shaped
		// variant arm = a keyword), and a stub's own `other(string)` (a payload-carrying
		// arm = a tagged list).
		// handler-case sits in a defvar initform, never in argument position: the JVM
		// sibling of this test cannot compile a handler-case with a non-empty operand
		// stack.
		assertThat(evalMulti(keyvalueImport(tempDir) + MEMORY_STORE + """
				(defvar *bad-handle* (handler-case (kv:bucket-get 424242 "k")
				                       (rontolisp:wit-error (e) (rontolisp:wit-error-payload e))))
				(defun stub-store (member &rest args)
				  (if (string= member "open")
				      7
				      (error 'rontolisp:wit-error :payload (list :other member)
				             :message "the stub store does not implement it")))
				(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'stub-store)
				(defvar *stub* (handler-case (kv:bucket-set (kv:open "cache") "k" "v")
				                 (rontolisp:wit-error (e) (rontolisp:wit-error-payload e))))
				(list *bad-handle* *stub*)
				""").print()).isEqualTo("(:NO-SUCH-STORE (:OTHER \"bucket-set\"))");
	}

	@Test
	void witImportWithNoProviderBoundSignalsAClearWitError(@TempDir Path tempDir) throws Exception {
		// The honest statement of what the core does with a wit-import: it binds the
		// WIT's
		// functions, and NOTHING ELSE. rontolisp knows the provider mechanism; it does
		// not
		// know what any concrete interface means, and it ships an implementation of none
		// --
		// so a bound function with no provider behind it does not quietly answer a
		// default,
		// it says so, and names the one thing that fixes it.
		String directive = witImport(tempDir, "gl.wit", WEBGL_WIT, "local:webgl/gl", "gl");
		assertThatThrownBy(() -> evalMulti(directive + "(gl:create-shader 35633)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("No provider is bound for the WIT interface local:webgl/gl")
			.hasMessageContaining("rontolisp:wit-provide");
		// And it is an ordinary rontolisp:wit-error, so a program can catch it; the
		// payload
		// is the interface nothing was bound for.
		assertThat(evalMulti(directive + """
				(handler-case (gl:create-shader 35633)
				  (rontolisp:wit-error (e) (rontolisp:wit-error-payload e)))
				""").print()).isEqualTo("\"local:webgl/gl\"");
	}

	@Test
	void evaluatesTagbodyGoAndProg() {
		assertThat(evalMulti("""
				(prog ((n 5) (acc 1))
				 top
				  (when (<= n 1) (return acc))
				  (setq acc (* acc n))
				  (setq n (- n 1))
				  (go top))
				""").print()).isEqualTo("120");
	}

	@Test
	void expandsShiftfReturningTheFirstPlacesOldValue() {
		assertThat(evalMulti("""
				(defvar *a* 1)
				(defvar *b* 2)
				(list (shiftf *a* *b* 9) *a* *b*)
				""").print()).isEqualTo("(1 2 9)");
	}

	@Test
	void typepTestsLiteralCompoundTypeSpecifiers() {
		assertThat(
				eval("(list (typep 5 '(unsigned-byte 8)) (typep 500 '(unsigned-byte 8)) (typep -1 'integer))").print())
			.isEqualTo("(T NIL T)");
	}

	@Test
	void typepResolvesUserDeftypeThroughSatisfies() {
		assertThat(evalMulti("""
				(deftype my-even () '(satisfies evenp))
				(list (typep 4 'my-even) (typep 3 'my-even))
				""").print()).isEqualTo("(T NIL)");
	}

	@Test
	void typepResolvesUserDeftypeOverAUserPredicateAndChainedNames() {
		assertThat(evalMulti("""
				(defun my-alistp (x) (and (listp x) (every #'consp x)))
				(deftype my-alist () '(satisfies my-alistp))
				(deftype my-int () 'integer)
				(list (typep '((a . 1) (b . 2)) 'my-alist) (typep '(a b) 'my-alist)
				      (typep 7 'my-int) (typep 'x 'my-int))
				""").print()).isEqualTo("(T NIL T NIL)");
	}

	@Test
	void typecaseResolvesAUserDeftype() {
		assertThat(evalMulti("""
				(deftype my-even () '(satisfies evenp))
				(defun classify (n) (typecase n (my-even :even) (t :other)))
				(list (classify 4) (classify 5))
				""").print()).isEqualTo("(:EVEN :OTHER)");
	}

	@Test
	void subtypepAnswersTheBuiltinLatticeAndConditionClasses() {
		assertThat(eval("(list (subtypep 'integer 'number) (subtypep 'number 'integer)"
				+ " (subtypep 'type-error 'error) (subtypep 'short-float 'single-float))")
			.print()).isEqualTo("(T NIL T T)");
	}

	@Test
	void defineSetfExpanderMakesAUserPlaceSettable() {
		assertThat(evalMulti("""
				(defun aget (alist key) (cdr (assoc key alist :test #'equal)))
				(defun %aput (alist key value)
				  (let ((kv (assoc key alist :test #'equal)))
				    (if kv (progn (rplacd kv value) alist) (cons (cons key value) alist))))
				(define-setf-expander aget (alist key &environment env)
				  (multiple-value-bind (d v n setter getter) (get-setf-expansion alist env)
				    (let ((nv (first n)))
				      (values d v n `(let ((,nv (%aput ,alist ,key ,nv))) ,setter ,nv) `(aget ,getter ,key)))))
				(let ((d (list (cons :a 1))))
				  (setf (aget d :a) 100)
				  (setf (aget d :b) 2)
				  (incf (aget d :a) 5)
				  (list (aget d :a) (aget d :b)))
				""").print()).isEqualTo("(105 2)");
	}

	@Test
	void defsetfShortAndLongForms() {
		assertThat(evalMulti("""
				(defun ref (box) (car box))
				(defsetf ref rplaca)
				(defun head (x) (car x))
				(defsetf head (lst) (v) `(progn (rplaca ,lst ,v) ,v))
				(list (let ((b (list 1))) (setf (ref b) 9) b)
				      (let ((l (list 7 8))) (setf (head l) 70) l))
				""").print()).isEqualTo("((9) (70 8))");
	}

	@Test
	void setfValuesAssignsEachPlaceFromTheProducer() {
		assertThat(evalMulti("""
				(defvar *x* nil)
				(defvar *y* nil)
				(setf (values *x* *y*) (floor 7 2))
				(list *x* *y*)
				""").print()).isEqualTo("(3 1)");
	}

	@Test
	void withSlotsWritesThroughToTheSlot() {
		assertThat(evalMulti("""
				(defclass box () ((items :initform nil)))
				(let ((b (make-instance 'box)))
				  (with-slots (items) b
				    (push 1 items)
				    (push 2 items))
				  (slot-value b 'items))
				""").print()).isEqualTo("(2 1)");
	}

	@Test
	void makeArrayCharacterWithFillPointerBuildsAGrowableString() {
		assertThat(evalMulti("""
				(let ((s (make-array 2 :element-type 'character :adjustable t :fill-pointer 0)))
				  (vector-push-extend #\\a s)
				  (vector-push-extend #\\b s)
				  (vector-push-extend #\\c s)
				  (list s (length s) (array-dimension s 0) (adjustable-array-p s) (array-has-fill-pointer-p s)))
				""").print()).isEqualTo("(\"abc\" 3 4 T T)");
	}

	@Test
	void setfOfATheWrappedPlaceIgnoresTheDeclaration() {
		assertThat(evalMulti("""
				(let ((cell (list 1 2)))
				  (incf (the integer (car cell)))
				  cell)
				""").print()).isEqualTo("(2 2)");
	}

	@Test
	void loadTimeValueEvaluatesItsForm() {
		assertThat(eval("(load-time-value (+ 1 2))").print()).isEqualTo("3");
	}

	@Test
	void ieee754BitsRoundTripDoublesAsUnsignedIntegers() {
		assertThat(eval("(list (%ieee754-double-bits 1.0d0) (%ieee754-double-from-bits 4607182418800017408))").print())
			.isEqualTo("(4607182418800017408 1.0)");
		// The sign bit makes the unsigned bits a bignum beyond Long.MAX_VALUE.
		assertThat(eval("(%ieee754-double-bits -2.0d0)").print()).isEqualTo("13835058055282163712");
	}

	@Test
	void ecaseMatchesAPackageQualifiedClauseKeyAgainstQuotedData() {
		assertThat(evalMulti("""
				(defpackage #:casepkg (:use #:cl))
				(in-package #:casepkg)
				(ecase 'toplevel (toplevel :matched) (:other :no))
				""").print()).isEqualTo(":MATCHED");
	}

	@Test
	void symbolpAnswersTrueForNilAndT() {
		assertThat(eval("(list (symbolp nil) (symbolp t) (symbolp 'x) (symbolp \"s\"))").print())
			.isEqualTo("(T T T NIL)");
	}

	@Test
	void uiopAddPackageLocalNicknameShortensAPackageName() {
		assertThat(evalMulti("""
				(defpackage #:com.example.deeply.nested (:use #:cl) (:export #:answer))
				(in-package #:com.example.deeply.nested)
				(defun answer () 42)
				(in-package #:cl-user)
				(uiop:add-package-local-nickname '#:nick '#:com.example.deeply.nested)
				(nick:answer)
				""").print()).isEqualTo("42");
	}

	@Test
	void grayStreamInstanceReceivesWriteCharAndWriteString() {
		assertThat(evalMulti("""
				(asdf:load-system "trivial-gray-streams")
				(defclass sink (trivial-gray-streams:fundamental-character-output-stream)
				  ((buf :initform (make-array 0 :element-type 'character :adjustable t :fill-pointer 0))))
				(defmethod trivial-gray-streams:stream-write-char ((stream sink) character)
				  (vector-push-extend character (slot-value stream 'buf)))
				(defmethod trivial-gray-streams:stream-write-string ((stream sink) string &optional start end)
				  (let ((s (subseq string (or start 0) end)))
				    (dotimes (i (length s)) (vector-push-extend (char s i) (slot-value stream 'buf)))))
				(let ((sink (make-instance 'sink)))
				  (write-string "he" sink)
				  (write-char #\\y sink)
				  (slot-value sink 'buf))
				""").print()).isEqualTo("\"hey\"");
	}

	@Test
	void grayBaseClassSuperclassLoadsGrayStreamsEagerly() {
		// A user class extending rontolisp's own Gray base class without going
		// through the trivial-gray-streams shim must find the superclass: the
		// defclass pulls gray.lisp in eagerly.
		assertThat(evalMulti("""
				(defclass gs-count (rontolisp:fundamental-character-output-stream)
				  ((n :initform 0)))
				(defmethod rontolisp:stream-write-string ((s gs-count) str)
				  (setf (slot-value s 'n) (+ (slot-value s 'n) (length str)))
				  str)
				(let ((s (make-instance 'gs-count)))
				  (write-string "hello" s)
				  (write-char #\\! s)
				  (slot-value s 'n))
				""").print()).isEqualTo("6");
	}

	@Test
	void loadResolvesReadTimeEvalAgainstEarlierTopLevelForms(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("rt.lisp");
		Files.writeString(file, """
				(defconstant +base+ 40)
				(defvar *result* (list #.(+ 1 2) #.+base+ #.(char-code #\\0)))
				""");
		assertThat(evalMulti("(load \"" + file.toString().replace("\\", "\\\\") + "\") *result*").print())
			.isEqualTo("(3 40 48)");
	}

	@Test
	void witErrorIsUsableAsAConditionClassWithoutAnyWitImport() {
		// The WIT runtime defines rontolisp:wit-error as a CONDITION CLASS, and a class
		// name is a quoted datum -- not a resolved function name. The interpreter's lazy
		// load used to trigger only on the latter, so `error` expanded against a
		// ClosRegistry that had never heard of the class and built a bogus condition
		// whose payload reader answered :payload. The compile path always got this right
		// (its pre-pass walks the AST, quoted symbols included), so the interpreter
		// DIVERGED from the JVM on the same source.
		assertThat(evalMulti("""
				(defun boom () (error 'rontolisp:wit-error :payload (list :other "no store")))
				(handler-case (boom)
				  (rontolisp:wit-error (e) (rontolisp:wit-error-payload e)))
				""").print()).isEqualTo("(:OTHER \"no store\")");
	}

	// --- the reader's upcase premise ---

	private LispVal evalUpcase(String input) {
		return evalMulti(input);
	}

	@Test
	void upcaseReaderModeRunsMixedCaseProgram() {
		// Standard names fold to their canonical lowercase spelling; user symbols
		// upcase, so ADD2 and add2 name the same function -- CL's :upcase reader.
		assertThat(evalUpcase("(DEFUN ADD2 (X) (+ X 2)) (add2 40)")).isEqualTo(new LispInteger(42));
	}

	@Test
	void upcaseReaderModeMatchesUpcasedKeywordArguments() {
		// Source keywords read upcased (:TEST), and built-in keyword parameters match
		// case-insensitively; keyword DATA stays upcased, so the alist keys written
		// :A here are the upcased :A the query folds to as well.
		assertThat(evalUpcase("(CDR (ASSOC \"x\" '((\"x\" . 1)) :TEST #'EQUAL))")).isEqualTo(new LispInteger(1));
	}

	@Test
	void upcaseReaderModeInternFoldsLikeTheReader() {
		// (intern (string-upcase ...)) name synthesis: the runtime name "TIME" is the
		// standard time under the mode (CL's upcase world), so it matches a folded
		// body reference -- the assoc-utils with-keys shape.
		assertThat(evalUpcase("(EQ (INTERN (STRING-UPCASE \"time\")) 'TIME)")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void upcaseReaderModeKeywordDataUpcases() {
		// Data keywords upcase and symbol-name reports the upcased spelling -- the CL
		// answer for (symbol-name :foo).
		assertThat(evalUpcase("(SYMBOL-NAME :foo)")).isEqualTo(new LispString("FOO"));
	}

	@Test
	void upcaseReaderModeFoldsRuntimeReadFromString() {
		// Runtime read follows the same upcase premise as the frontend reader: a user
		// symbol upcases, so (read-from-string "foo") is the symbol FOO -- CL's answer,
		// where the old case-preserving runtime read returned foo.
		assertThat(evalUpcase("(READ-FROM-STRING \"foo\")").print()).isEqualTo("FOO");
		assertThat(evalUpcase("(SYMBOL-NAME (READ-FROM-STRING \"foo\"))")).isEqualTo(new LispString("FOO"));
		// A standard operator read at runtime folds back to its canonical lowercase
		// spelling, so it stays eq to a compiled quoted reference.
		assertThat(evalUpcase("(EQ (READ-FROM-STRING \"car\") 'car)")).isEqualTo(LispTrue.INSTANCE);
		// User symbols inside a read datum upcase (the dotted-pair shape).
		assertThat(evalUpcase("(READ-FROM-STRING \"(x . 9)\")").print()).isEqualTo("(X . 9)");
	}

	@Test
	void upcaseReaderModeFoldsRuntimeReadFromStream() {
		// (read stream) shares the read-from-string fold: a user symbol read from an
		// input stream upcases too.
		assertThat(evalUpcase("(WITH-INPUT-FROM-STRING (S \"foo\") (READ S))").print()).isEqualTo("FOO");
	}

}
