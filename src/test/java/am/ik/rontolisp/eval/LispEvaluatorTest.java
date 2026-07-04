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
			.hasMessageContaining("The variable 1/2x is unbound");
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
		assertThatThrownBy(() -> eval("(map 'vector #'1+ '(1 2 3))")).isInstanceOf(UnsupportedOperationException.class);
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
		assertThat(eval("(defvar *x* 42)")).isEqualTo(new LispSymbol("*x*"));
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
	void evalFormat() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		LispVal result = evaluator.eval(LispReader.readFromString("(format t \"Hello ~a, you are ~d!~%\" 'world 42)"));
		assertThat(baos.toString()).isEqualTo("Hello world, you are 42!" + System.lineSeparator());
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
		assertThat(baos.toString()).isEqualTo("sym 42 \"str\"");
	}

	@Test
	void evalFormatNilReturnsString() {
		assertThat(eval("(format nil \"Hello ~a, you are ~d!~%\" 'world 42)"))
			.isEqualTo(new LispString("Hello world, you are 42!\n"));
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
		assertThat(eval("(format nil \"~a\" nil)")).isEqualTo(new LispString("nil"));
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
		assertThat(eval("(format nil \"~{ ~a,~}\" '(a b c d))")).isEqualTo(new LispString(" a, b, c, d,"));
		assertThat(eval("(format nil \"~{ <~a, ~a> ~}\" '(a 1 b 2 c 3))"))
			.isEqualTo(new LispString(" <a, 1>  <b, 2>  <c, 3> "));
		assertThat(eval("(format nil \"~2{ <~a, ~a> ~}\" '(a 1 b 2 c 3))"))
			.isEqualTo(new LispString(" <a, 1>  <b, 2> "));
		assertThat(eval("(format nil \"~:{ <~a, ~a> ~}\" '((a 1) (b 2) (c 3)))"))
			.isEqualTo(new LispString(" <a, 1>  <b, 2>  <c, 3> "));
		assertThat(eval("(format nil \"~{~a~}\" nil)")).isEqualTo(new LispString(""));
	}

	@Test
	void evalFormatIterationOverRemainingArgs() {
		assertThat(eval("(format nil \"~@{ ~a,~}\" 1 2 3 4 5)")).isEqualTo(new LispString(" 1, 2, 3, 4, 5,"));
		assertThat(eval("(format nil \"~4@{ ~a,~} ~4d\" 1 2 3 4 5)")).isEqualTo(new LispString(" 1, 2, 3, 4,    5"));
		assertThat(eval("(format nil \"~:@{ <~a, ~a> ~}\" '(a 1) '(b 2) '(c 3))"))
			.isEqualTo(new LispString(" <a, 1>  <b, 2>  <c, 3> "));
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
	void evalFormatConditionalUnequalConsumptionRejected() {
		assertThatThrownBy(() -> eval("(format nil \"~[~a~;~a ~a~]\" 0 1 2)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("clause");
	}

	@Test
	void evalFormatUnsupportedDestination() {
		assertThatThrownBy(() -> eval("(format 'foo \"~a\" 1)")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("destination");
	}

	@Test
	void evalPrincToString() {
		assertThat(eval("(princ-to-string 42)")).isEqualTo(new LispString("42"));
		assertThat(eval("(princ-to-string \"abc\")")).isEqualTo(new LispString("abc"));
		assertThat(eval("(princ-to-string 'sym)")).isEqualTo(new LispString("sym"));
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
		assertThat(eval("(subseq '(a b c) 0)").print()).isEqualTo("(a b c)");
		assertThat(eval("(subseq '(1 2 3) 3)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(subseq '() 0)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalStringEquality() {
		assertThat(eval("(string= \"abc\" \"abc\")")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(string= \"abc\" \"abd\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(string-equal \"ABC\" \"abc\")")).isEqualTo(LispTrue.INSTANCE);
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
		assertThatThrownBy(() -> eval("(format t x)")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("literal control string");
	}

	@Test
	void evalFormatNotEnoughArguments() {
		assertThatThrownBy(() -> eval("(format t \"~a ~a\" 1)")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not enough arguments");
	}

	@Test
	void evalFormatUnsupportedDirective() {
		assertThatThrownBy(() -> eval("(format t \"~<~>\" 65)")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("unsupported directive");
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
		assertThat(eval("(case 2 (1 'one) (2 'two) (3 'three))")).isEqualTo(new LispSymbol("two"));
	}

	@Test
	void evalCaseKeyList() {
		assertThat(eval("(case 3 (1 'one) ((2 3 4) 'small) (otherwise 'big))")).isEqualTo(new LispSymbol("small"));
	}

	@Test
	void evalCaseOtherwise() {
		assertThat(eval("(case 99 (1 'one) ((2 3 4) 'small) (otherwise 'big))")).isEqualTo(new LispSymbol("big"));
	}

	@Test
	void evalCaseTDefault() {
		assertThat(eval("(case 99 (1 'one) (t 'fallback))")).isEqualTo(new LispSymbol("fallback"));
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
			.isEqualTo("(b 2 c 3)");
	}

	@Test
	void evalRemfMiddle() {
		assertThat(evalMulti("(setq plist (list 'a 1 'b 2 'c 3)) (remf plist 'b) plist").print())
			.isEqualTo("(a 1 c 3)");
	}

	@Test
	void evalRemfTail() {
		assertThat(evalMulti("(setq plist (list 'a 1 'b 2 'c 3)) (remf plist 'c) plist").print())
			.isEqualTo("(a 1 b 2)");
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
		assertThat(eval(":foo")).isEqualTo(new LispSymbol(":foo"));
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
		assertThat(eval("(car (list :foo :bar))")).isEqualTo(new LispSymbol(":foo"));
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
		assertThat(eval("(loop for x in '(a b c) collect x)").print()).isEqualTo("(a b c)");
		assertThat(eval("(loop for x on '(1 2 3) collect x)").print()).isEqualTo("((1 2 3) (2 3) (3))");
		// Parallel for clauses terminate when the shortest runs out (indexed map).
		assertThat(eval("(loop for x in '(a b c) for i from 0 collect (list i x))").print())
			.isEqualTo("((0 a) (1 b) (2 c))");
	}

	@Test
	void evalLoopAcross() {
		assertThat(eval("(loop for c across \"hello\" collect c)").print()).isEqualTo("(#\\h #\\e #\\l #\\l #\\o)");
		assertThat(eval("(loop for c across \"hello\" count (eql c #\\l))")).isEqualTo(new LispInteger(2));
		assertThat(eval("(loop for c across \"\" collect c)").print()).isEqualTo("nil");
		// across also walks a vector's elements.
		assertThat(eval("(loop for x across #(1 2 3 4 5) collect (* x x))").print()).isEqualTo("(1 4 9 16 25)");
		assertThat(eval("(loop for x across #(3 1 4 1 5) maximize x)")).isEqualTo(new LispInteger(5));
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
		assertThat(eval("(loop repeat 3 collect 'x)").print()).isEqualTo("(x x x)");
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
			.isEqualTo("((1 init) (2 1) (3 2))");
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
		assertThat(eval("(member '(a d) '((a b) (a c) (a d) (a e)) :test 'equal)").print()).isEqualTo("((a d) (a e))");
		assertThat(eval("(member '(a d) '((a b) (a c) (a d) (a e)))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(member 3 '(1 2 3 4) :test #'equal)").print()).isEqualTo("(3 4)");
		assertThat(eval("(member 9 '(1 2 3) :test 'equal)")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalFind() {
		assertThat(eval("(find 3 '(1 2 3 4))").print()).isEqualTo("3");
		assertThat(eval("(find 'b '(a b c))").print()).isEqualTo("b");
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
		assertThat(eval("(every #'digit-char-p \"123\")").print()).isEqualTo("t");
		assertThat(eval("(every #'digit-char-p \"12a\")")).isSameAs(LispNil.INSTANCE);
		// some yields the first non-nil predicate value: digit-char-p's weight of #\1
		assertThat(eval("(some #'digit-char-p \"abc1\")").print()).isEqualTo("1");
		assertThat(eval("(notany #'digit-char-p \"ab1\")")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(notevery #'digit-char-p \"12a\")").print()).isEqualTo("t");
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
		assertThat(eval("(assoc 'b '((a 1) (b 2) (c 3)))").print()).isEqualTo("(b 2)");
		assertThat(eval("(assoc 'z '((a 1)))")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalAssocOnDottedAlistLiteral() {
		assertThat(eval("(assoc 'b '((a . 1) (b . 2) (c . 3)))").print()).isEqualTo("(b . 2)");
		assertThat(eval("(cdr (assoc 'b '((a . 1) (b . 2))))").print()).isEqualTo("2");
	}

	@Test
	void evalAssocWithTest() {
		assertThat(eval("(assoc \"b\" '((\"a\" . 1) (\"b\" . 2)) :test #'equal)").print()).isEqualTo("(\"b\" . 2)");
		assertThat(eval("(assoc \"z\" '((\"a\" . 1)) :test 'equal)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'assoc 'b '((a . 1) (b . 2)))").print()).isEqualTo("(b . 2)");
	}

	@Test
	void evalRassocWithTest() {
		assertThat(eval("(rassoc \"x\" '((a . \"w\") (b . \"x\")) :test #'equal)").print()).isEqualTo("(b . \"x\")");
		assertThat(eval("(rassoc \"z\" '((a . \"w\")) :test 'equal)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'rassoc 2 '((a . 1) (b . 2)))").print()).isEqualTo("(b . 2)");
	}

	@Test
	void evalAconsAsFunctionValue() {
		assertThat(eval("(funcall #'acons 'a 1 '((b . 2)))").print()).isEqualTo("((a . 1) (b . 2))");
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
		assertThat(eval("(pairlis '(a b c) '(1 2 3))").print()).isEqualTo("((a . 1) (b . 2) (c . 3))");
		assertThat(eval("(pairlis '(a b) '(1 2) '((c . 3)))").print()).isEqualTo("((a . 1) (b . 2) (c . 3))");
		assertThat(eval("(pairlis nil nil)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(pairlis '(a b) '(1))").print()).isEqualTo("((a . 1))");
		assertThat(eval("(funcall #'pairlis '(a) '(1))").print()).isEqualTo("((a . 1))");
	}

	@Test
	void evalCopyAlist() {
		assertThat(eval("(copy-alist '((a . 1) (b . 2)))").print()).isEqualTo("((a . 1) (b . 2))");
		assertThat(eval("(copy-alist nil)")).isSameAs(LispNil.INSTANCE);
		// The pair cells are copied: mutating a copied pair leaves the original alist
		// intact.
		assertThat(eval("""
				(let* ((orig (list (cons 'a 1) (cons 'b 2)))
				       (copy (copy-alist orig)))
				  (rplacd (assoc 'a copy) 99)
				  (cdr (assoc 'a orig)))""").print()).isEqualTo("1");
		assertThat(eval("(funcall #'copy-alist '((a . 1)))").print()).isEqualTo("((a . 1))");
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
		assertThat(eval("(assoc-if #'oddp '((2 a) (3 b) (5 c)))").print()).isEqualTo("(3 b)");
		assertThat(eval("(assoc-if #'evenp '((1 a) (3 b)))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'assoc-if #'plusp '((-1 a) (2 b)))").print()).isEqualTo("(2 b)");
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
		assertThat(eval("(funcall #'remove-duplicates '(a b a a c))").print()).isEqualTo("(b a c)");
	}

	@Test
	void evalButlast() {
		assertThat(eval("(butlast '(1 2 3))").print()).isEqualTo("(1 2)");
		assertThat(eval("(butlast '(1))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(butlast nil)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'butlast '(a b c d))").print()).isEqualTo("(a b c)");
	}

	@Test
	void evalNconc() {
		assertThat(eval("(nconc (list 1 2) (list 3 4))").print()).isEqualTo("(1 2 3 4)");
		assertThat(eval("(nconc nil (list 1 2))").print()).isEqualTo("(1 2)");
		assertThat(eval("(nconc (list 1 2) nil)").print()).isEqualTo("(1 2)");
		assertThat(eval("(funcall #'nconc (list 'a) (list 'b 'c))").print()).isEqualTo("(a b c)");
	}

	@Test
	void evalIdentity() {
		assertThat(eval("(identity 42)").print()).isEqualTo("42");
		assertThat(eval("(identity '(1 2 3))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(identity nil)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'identity 'x)").print()).isEqualTo("x");
	}

	@Test
	void evalCopyList() {
		assertThat(eval("(copy-list '(1 2 3))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(copy-list nil)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'copy-list '(a b))").print()).isEqualTo("(a b)");
	}

	@Test
	void evalNreverse() {
		assertThat(eval("(nreverse '(1 2 3))").print()).isEqualTo("(3 2 1)");
		assertThat(eval("(nreverse nil)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'nreverse '(a b c))").print()).isEqualTo("(c b a)");
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
		assertThat(eval("(make-list 3)").print()).isEqualTo("(nil nil nil)");
		assertThat(eval("(make-list 0)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'make-list 2)").print()).isEqualTo("(nil nil)");
	}

	@Test
	void evalUnion() {
		assertThat(eval("(union '(1 2 3) '(2 3 4))").print()).isEqualTo("(4 1 2 3)");
		assertThat(eval("(union nil '(1 2))").print()).isEqualTo("(2 1)");
		assertThat(eval("(union '(1 2) nil)").print()).isEqualTo("(1 2)");
		assertThat(eval("(funcall #'union '(a) '(a b))").print()).isEqualTo("(b a)");
	}

	@Test
	void evalIntersection() {
		assertThat(eval("(intersection '(1 2 3) '(2 3 4))").print()).isEqualTo("(3 2)");
		assertThat(eval("(intersection '(1 2) '(3 4))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'intersection '(a b c) '(b c d))").print()).isEqualTo("(c b)");
	}

	@Test
	void evalSetDifference() {
		assertThat(eval("(set-difference '(1 2 3) '(2))").print()).isEqualTo("(3 1)");
		assertThat(eval("(set-difference '(1 2 3) '(1 2 3))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'set-difference '(a b c) '(b))").print()).isEqualTo("(c a)");
	}

	@Test
	void evalAdjoin() {
		assertThat(eval("(adjoin 1 '(2 3))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(adjoin 2 '(1 2 3))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(adjoin 'a nil)").print()).isEqualTo("(a)");
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
	void evalListStarAndAcons() {
		assertThat(eval("(list* 1 2 '(3 4))").print()).isEqualTo("(1 2 3 4)");
		assertThat(eval("(list* 1 2 3)").print()).isEqualTo("(1 2 . 3)");
		assertThat(eval("(list* 'x)").print()).isEqualTo("x");
		assertThat(eval("(acons 'a 1 nil)").print()).isEqualTo("((a . 1))");
		assertThat(eval("(acons 'b 2 (list (cons 'a 1)))").print()).isEqualTo("((b . 2) (a . 1))");
	}

	@Test
	void evalEltEndpRassoc() {
		assertThat(eval("(elt '(a b c) 1)").print()).isEqualTo("b");
		assertThat(eval("(elt \"abcd\" 1)").print()).isEqualTo("#\\b");
		assertThat(eval("(endp nil)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(endp '(1))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(rassoc 2 (list (cons 'a 1) (cons 'b 2)))").print()).isEqualTo("(b . 2)");
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
		// error rather than silently returning nil, which would hide a caller's mistake
		// (.todo/26). nil is a valid empty list and must stay accepted.
		assertThatThrownBy(() -> eval("(mapcar #'identity \"abc\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("mapcar: argument is not a list: \"abc\"");
		assertThatThrownBy(() -> eval("(mapc #'identity \"abc\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("mapc: argument is not a list");
		assertThatThrownBy(() -> eval("(mapcan #'list \"abc\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("mapcan: argument is not a list");
		assertThatThrownBy(() -> eval("(maplist #'identity \"abc\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("maplist: argument is not a list");
		assertThatThrownBy(() -> eval("(mapcon #'list \"abc\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("mapcon: argument is not a list");
		assertThatThrownBy(() -> eval("(mapcar #'1+ 5)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("mapcar: argument is not a list: 5");
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
		assertThat(eval("(remove 1 '())").print()).isEqualTo("nil");
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
		assertThat(eval("(remove-if-not #'evenp '())").print()).isEqualTo("nil");
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
		assertThat(eval("(substitute 9 1 '())").print()).isEqualTo("nil");
		assertThat(eval("(nsubstitute 9 1 '(1 2 1 3))").print()).isEqualTo("(9 2 9 3)");
		assertThat(evalMulti("(funcall #'substitute 0 2 '(2 2 2))").print()).isEqualTo("(0 0 0)");
	}

	@Test
	void evalDefparameterAlwaysAssigns() {
		// Unlike defvar, defparameter re-assigns even when already bound.
		assertThat(evalMulti("(defparameter *x* 1) (defparameter *x* 2) *x*")).isEqualTo(new LispInteger(2));
		assertThat(eval("(defparameter *y* 7)")).isEqualTo(new LispSymbol("*y*"));
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
		assertThat(eval("(mapcan #'list '())").print()).isEqualTo("nil");
		assertThat(evalMulti("(funcall #'mapcan (lambda (x) (list x)) '(1 2 3))").print()).isEqualTo("(1 2 3)");
	}

	@Test
	void evalSort() {
		assertThat(eval("(sort '(3 1 4 1 5 9 2 6) #'<)").print()).isEqualTo("(1 1 2 3 4 5 6 9)");
		assertThat(eval("(sort '(3 1 4) #'>)").print()).isEqualTo("(4 3 1)");
		assertThat(eval("(sort '() #'<)").print()).isEqualTo("nil");
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
			.hasMessageContaining("The variable car is unbound");
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
			.hasMessageContaining("The function nosuchfn is undefined");
	}

	@Test
	void evalVariableBindingDoesNotShadowFunction() {
		assertThat(eval("(let ((car 5)) (car (list car 2)))")).isEqualTo(new LispInteger(5));
	}

	@Test
	void evalDefunReturnsFunctionNameSymbol() {
		assertThat(eval("(defun f (x) x)")).isEqualTo(new LispSymbol("f"));
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
		assertThat(result.print()).isEqualTo("(function car)");
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
		assertThat(result.print()).isEqualTo("(\"hello\" \"world\" nil)");
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
		assertThatThrownBy(() -> eval("(with-open-file (s \"" + file + "\" :if-exists :append) s)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("supports only the :direction and :element-type options");
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
		assertThat(result.print()).isEqualTo("(0 10 34 255 nil)");
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
		assertThat(evaluator.eval(LispReader.readFromString("(require :util)"))).isEqualTo(new LispSymbol("util"));
		assertThat(evaluator.eval(LispReader.readFromString("(require :util)"))).isEqualTo(new LispSymbol("util"));
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
			.isEqualTo(new LispSymbol("util"));
		assertThat(evaluator.eval(LispReader.readFromString("(util-version)"))).isEqualTo(new LispInteger(2));
		// Symbol and string designators name the same (already provided) module.
		assertThat(evaluator.eval(LispReader.readFromString("(require 'util)"))).isEqualTo(new LispSymbol("util"));
		assertThat(evaluator.eval(LispReader.readFromString("(require \"util\")"))).isEqualTo(new LispSymbol("util"));
	}

	@Test
	void provideMarksTheModuleAndDuplicateProvideIsANoOp() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		assertThat(evaluator.eval(LispReader.readFromString("(provide :mod)"))).isEqualTo(new LispSymbol("mod"));
		assertThat(evaluator.eval(LispReader.readFromString("(provide :mod)"))).isEqualTo(new LispSymbol("mod"));
		// A require of the provided module returns the name without touching any file.
		assertThat(evaluator.eval(LispReader.readFromString("(require :mod)"))).isEqualTo(new LispSymbol("mod"));
	}

	@Test
	void requireMissingModuleFileThrows(@TempDir Path tempDir) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.setLoadBaseDir(tempDir.toString());
		assertThatThrownBy(() -> evaluator.eval(LispReader.readFromString("(require :nope)")))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("require: cannot read file");
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
		assertThat(eval("(funcall #'provide :fc-mod)")).isEqualTo(new LispSymbol("fc-mod"));
	}

	@Test
	void rontolispVersionReturnsPlist() {
		assertThat(eval("(car (rontolisp:version))")).isEqualTo(new LispSymbol(":version"));
		assertThat(eval("(cadr (rontolisp:version))")).isEqualTo(new LispString(am.ik.rontolisp.Version.getVersion()));
	}

	@Test
	void versionIsNotVisibleUnqualifiedInClUser() {
		assertThatThrownBy(() -> eval("(version)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("The function version is undefined");
	}

	@Test
	void wasmExportIsNoOpReturningTheNamedSymbol() {
		// The directive returns the named symbol and does not affect normal evaluation.
		assertThat(evalMulti("(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))"
				+ "(rontolisp:wasm-export 'fact :params '(:int) :returns :int)" + "(fact 5)"))
			.isEqualTo(new LispInteger(120));
		assertThat(eval("(rontolisp:wasm-export 'fact :params '(:int) :returns :int)"))
			.isEqualTo(new LispSymbol("fact"));
	}

	@Test
	void wasmImportDefinesAnErrorSignallingStub() {
		// The directive returns the named symbol; the imported host function only
		// exists in compiled WASM output, so calling the stub signals an error.
		assertThat(eval("(rontolisp:wasm-import 'add :from \"host\" :params '(:int :int) :returns :int)"))
			.isEqualTo(new LispSymbol("add"));
		assertThatThrownBy(() -> evalMulti(
				"(rontolisp:wasm-import 'add :from \"host\" :params '(:int :int) :returns :int)" + "(add 1 2)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("rontolisp:wasm-import");
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
				"(and case ccase cond decf do do* dolist dotimes ecase error etypecase format incf let* loop or pop prog1 prog2 psetq push remf setf time typecase unless when with-open-file)");
	}

	@Test
	void listSpecialFormsReturnsSortedClSpecialForms() {
		assertThat(eval("(rontolisp:list-special-forms)").print()).isEqualTo(
				"(defconstant defmacro defpackage defparameter defstruct defun defvar function if in-package lambda let progn quote return setq while)");
	}

	@Test
	void listFunctionsReturnsSortedClFunctions() {
		java.util.List<String> names = symbolNames(eval("(rontolisp:list-functions)"));
		assertThat(names)
			.contains("first", "rest", "nth", "funcall", "length", "1+", "car", "eval", "not", "equal", "map", "mapc",
					"every", "some", "remove", "remove-if", "remove-if-not", "find", "find-if", "find-if-not",
					"position", "position-if", "count", "count-if", "mapcan", "apply", "sort", "member-if", "assoc-if",
					"getf", "butlast", "remove-duplicates", "nconc", "identity", "copy-list", "nreverse", "make-list",
					"union", "intersection", "set-difference", "adjoin", "logand", "logior", "logxor", "lognot", "ash",
					"list*", "acons", "endp", "elt", "rassoc", "pairlis", "copy-alist", "revappend", "nreconc",
					"maplist", "mapcon", "notany", "notevery", "delete", "delete-if", "delete-if-not", "substitute",
					"nsubstitute", "fresh-line")
			.doesNotContain("cond", "quote", "defun", "setf", "%remf-tail", "cadr", "*package*", "error", "%fmt-pad")
			.contains("random", "get-universal-time", "get-internal-real-time", "get-internal-run-time", "getenv")
			.contains("read-from-string", "parse-integer", "char", "schar", "char-code", "code-char", "char=", "char<",
					"char<=", "char-upcase", "char-downcase", "characterp", "alpha-char-p", "digit-char-p")
			.contains("make-hash-table", "gethash", "remhash", "clrhash", "hash-table-count", "hash-table-p", "maphash")
			.contains("make-array", "aref", "row-major-aref", "array-row-major-index")
			.contains("gensym", "macroexpand", "macroexpand-1")
			.contains("require", "provide")
			.contains("read-byte", "write-byte", "read-sequence", "write-sequence")
			.doesNotContain("%puthash", "%aset", "%row-major-aset")
			.isSorted()
			.hasSize(207);
	}

	@Test
	void listFunctionsAcceptsAllDesignatorSpellings() {
		LispVal byDefault = eval("(rontolisp:list-functions)");
		assertThat(eval("(rontolisp:list-functions :cl)")).isEqualTo(byDefault);
		assertThat(eval("(rontolisp:list-functions cl)")).isEqualTo(byDefault);
		assertThat(eval("(rontolisp:list-functions \"cl\")")).isEqualTo(byDefault);
		assertThat(eval("(rontolisp:list-functions 'cl)")).isEqualTo(byDefault);
	}

	@Test
	void listFunctionsForClUserReflectsUserDefuns() {
		assertThat(eval("(rontolisp:list-functions :cl-user)")).isEqualTo(LispNil.INSTANCE);
		assertThat(evalMulti("""
				(defun fib (n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
				(defun add2 (a) (+ a 2))
				(rontolisp:list-functions :cl-user)
				""").print()).isEqualTo("(add2 fib)");
	}

	@Test
	void listFunctionsForClUserExcludesShadowingAndInternalNames() {
		// A defun shadowing a cl name is filtered so all backends agree.
		assertThat(evalMulti("(defun length (x) 42) (rontolisp:list-functions :cl-user)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void listFunctionsForRontolispReturnsOwnedFunctions() {
		assertThat(eval("(rontolisp:list-functions :rontolisp)").print()).isEqualTo(
				"(await fetch json-parse json-stringify list-functions list-macros list-special-forms promisep tcp-accept tcp-connect tcp-listen tcp-local-port then version)");
	}

	@Test
	void listFunctionsForJavaReturnsOwnedFunctions() {
		assertThat(eval("(rontolisp:list-functions :java)").print()).isEqualTo("(call field new proxy static)");
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
			.hasMessageContaining("No such package: foo");
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
				"(await fetch json-parse json-stringify list-functions list-macros list-special-forms promisep tcp-accept tcp-connect tcp-listen tcp-local-port then version)");
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
		assertThat(eval("*package*")).isEqualTo(new LispSymbol("cl-user"));
	}

	@Test
	void inPackageMakesVersionVisibleUnqualified() {
		assertThat(evalMulti("(in-package :rontolisp) (cl:cadr (version))"))
			.isEqualTo(new LispString(am.ik.rontolisp.Version.getVersion()));
	}

	@Test
	void inPackageUpdatesPackageVar() {
		assertThat(evalMulti("(in-package :rontolisp) cl:*package*")).isEqualTo(new LispSymbol("rontolisp"));
	}

	@Test
	void clQualifiedStandardFunctionWorksInRontolisp() {
		assertThat(evalMulti("(in-package :rontolisp) (cl:car '(1 2))")).isEqualTo(new LispInteger(1));
	}

	@Test
	void unqualifiedStandardSymbolInRontolispIsRejected() {
		assertThatThrownBy(() -> evalMulti("(in-package :rontolisp) (car '(1 2))"))
			.isInstanceOf(am.ik.rontolisp.LispPackageException.class)
			.hasMessageContaining("use cl:car");
	}

	@Test
	void inPackageCanSwitchBackToClUser() {
		assertThat(evalMulti("(in-package :rontolisp) (in-package :cl-user) *package*"))
			.isEqualTo(new LispSymbol("cl-user"));
	}

	@Test
	void inPackageUnknownPackageThrows() {
		assertThatThrownBy(() -> eval("(in-package foo)")).isInstanceOf(am.ik.rontolisp.LispPackageException.class)
			.hasMessageContaining("No such package: foo");
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
			.hasMessageContaining("The symbol helper is not external in the mypkg package");
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
			.hasMessageContaining("The function client::priv is undefined");
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
				""").print()).isEqualTo("(mypkg::priv mypkg:pub)");
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
			// fetch returns a promise immediately; await resolves it into the plist
			// (:status 200 :body "hello world" :headers (...))
			assertThat(
					eval("(getf (rontolisp:await (rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\")) :status)"))
				.isEqualTo(new LispInteger(200));
			assertThat(eval("(getf (rontolisp:await (rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\")) :body)"))
				.isEqualTo(new LispString("hello world"));
			LispVal headers = eval(
					"(getf (rontolisp:await (rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\")) :headers)");
			// the JDK HttpClient normalizes response header names to lower case
			assertThat(headers.print()).contains("x-test").contains("ok");
			// fetch itself returns an opaque promise, not the result; it prints as
			// #<PROMISE> and satisfies promisep
			assertThat(eval("(rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\")"))
				.isInstanceOf(am.ik.rontolisp.LispPromise.class);
			assertThat(eval("(rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\")").print())
				.isEqualTo("#<PROMISE>");
			assertThat(eval("(rontolisp:promisep (rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\"))").print())
				.isEqualTo("t");
			// a settled promise can be awaited more than once
			assertThat(eval("(let ((p (rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\")))"
					+ " (rontolisp:await p) (getf (rontolisp:await p) :status))"))
				.isEqualTo(new LispInteger(200));
			// two in-flight promises resolve independently
			assertThat(eval("(let ((p1 (rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\"))"
					+ " (p2 (rontolisp:fetch \"http://127.0.0.1:" + port + "/hello\")))"
					+ " (list (getf (rontolisp:await p2) :status) (getf (rontolisp:await p1) :status)))")
				.print()).isEqualTo("(200 200)");
			// a fetch promise chains with then
			assertThat(eval("(rontolisp:await (rontolisp:then (rontolisp:fetch \"http://127.0.0.1:" + port
					+ "/hello\") (lambda (r) (getf r :status))))"))
				.isEqualTo(new LispInteger(200));
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
			.hasMessageContaining("tcp-connect");
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
			LispVal result = eval("(getf (rontolisp:await (rontolisp:fetch \"http://127.0.0.1:" + port
					+ "/echo\" (list :headers (list (cons \"X-Custom\" \"abc\"))))) :body)");
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
			LispVal result = eval("(getf (rontolisp:await (rontolisp:fetch \"http://127.0.0.1:" + port
					+ "/post\" (list :method \"POST\" :body \"hello\"))) :body)");
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
		assertThatThrownBy(() -> eval("(rontolisp:fetch)")).hasMessageContaining("fetch");
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
		assertThat(eval("(rontolisp:await nil)").print()).isEqualTo("nil");
		assertThatThrownBy(() -> eval("(rontolisp:await)")).hasMessageContaining("await");
	}

	@Test
	void promisepDistinguishesPromises() {
		assertThat(eval("(rontolisp:promisep 42)").print()).isEqualTo("nil");
		assertThat(eval("(rontolisp:promisep nil)").print()).isEqualTo("nil");
		assertThat(eval("(rontolisp:promisep \"p\")").print()).isEqualTo("nil");
		assertThatThrownBy(() -> eval("(rontolisp:promisep)")).hasMessageContaining("promisep");
	}

	@Test
	void thenDerivesChainablePromises() {
		// then always yields a promise, even from a plain value
		assertThat(eval("(rontolisp:promisep (rontolisp:then 1 (lambda (x) x)))").print()).isEqualTo("t");
		assertThat(eval("(rontolisp:then 1 (lambda (x) x))").print()).isEqualTo("#<PROMISE>");
		assertThat(eval("(rontolisp:await (rontolisp:then 21 (lambda (x) (* x 2))))")).isEqualTo(new LispInteger(42));
		// chains compose left to right
		assertThat(eval(
				"(rontolisp:await (rontolisp:then (rontolisp:then 10 (lambda (x) (+ x 1))) (lambda (x) (* x 3))))"))
			.isEqualTo(new LispInteger(33));
		// a callback returning a promise is flattened, like JavaScript then
		assertThat(eval("(rontolisp:await (rontolisp:then 5 (lambda (x) (rontolisp:then x (lambda (y) (+ y 1))))))"))
			.isEqualTo(new LispInteger(6));
		// the callback runs at first await only; the result is memoized
		assertThat(eval("(progn (setq cnt 0)" + " (let ((p (rontolisp:then 1 (lambda (x) (setq cnt (+ cnt 1)) x))))"
				+ " (rontolisp:await p) (rontolisp:await p) cnt))"))
			.isEqualTo(new LispInteger(1));
		assertThatThrownBy(() -> eval("(rontolisp:then 1)")).hasMessageContaining("then");
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
		assertThat(eval("(read-from-string \"foo\")")).isEqualTo(new LispSymbol("foo"));
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
		assertThat(result.print()).isEqualTo("((10 20 30) 99 nil)");
	}

	@Test
	void hashTablePutAndGet() {
		LispVal result = evalMulti("""
				(defparameter *h* (make-hash-table :test 'equal))
				(setf (gethash "a" *h*) 1)
				(setf (gethash "b" *h*) 2)
				(list (gethash "a" *h*) (gethash "b" *h*) (gethash "c" *h*))
				""");
		assertThat(result.print()).isEqualTo("(1 2 nil)");
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
		assertThat(result.print()).isEqualTo("(1 nil b)");
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
		assertThat(result.print()).isEqualTo("(1 (t nil) 2)");
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
		assertThat(vec.print()).isEqualTo("#(a \"b\")");
		assertThat(vec.display()).isEqualTo("#(a b)");
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
			.hasMessageContaining("length");
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
		assertThat(result.print()).isEqualTo("(28 7 t)");
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
		assertThat(evalMulti("(defmacro my-noop (x) x)")).isEqualTo(new LispSymbol("my-noop"));
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
	void defmacroRejectsUnsupportedLambdaListKeywords() {
		assertThatThrownBy(() -> evalMulti("(defmacro my-mac (a &optional b) a)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("&rest/&body");
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
		assertThat(result.print()).isEqualTo("(if (> 2 1) (progn (quote a) (quote b)) nil)");
	}

	@Test
	void macroexpand1ExpandsABuiltinMacroOnce() {
		assertThat(evalMulti("(macroexpand-1 '(unless c x))").print()).isEqualTo("(if c nil x)");
		// incf expands to setf: one step only, the setf is left for another round.
		assertThat(evalMulti("(macroexpand-1 '(incf n 2))").print()).isEqualTo("(setf n (+ n 2))");
	}

	@Test
	void macroexpand1ReturnsANonMacroFormUnchanged() {
		assertThat(evalMulti("(macroexpand-1 '(+ 1 2))").print()).isEqualTo("(+ 1 2)");
		assertThat(evalMulti("(macroexpand-1 'x)").print()).isEqualTo("x");
		assertThat(evalMulti("(macroexpand-1 12)").print()).isEqualTo("12");
		assertThat(evalMulti("(macroexpand-1 '(if a b c))").print()).isEqualTo("(if a b c)");
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
		assertThat(evalMulti("(macroexpand '(when a (when b c)))").print()).isEqualTo("(if a (when b c) nil)");
	}

	@Test
	void macroexpandWorksThroughRuntimeEval() {
		assertThat(evalMulti("(eval '(macroexpand-1 '(unless c x)))").print()).isEqualTo("(if c nil x)");
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
			.isEqualTo("#2A((19 22) (43 50))");
		assertThat(eval("(linalg:det (linalg:from-list '((1 2) (3 4))))").print()).isEqualTo("-2");
		assertThat(eval("(linalg:inv (linalg:from-list '((1 2) (3 4))))").print()).isEqualTo("#2A((-2 1) (3/2 -1/2))");
		assertThat(eval("(linalg:solve (linalg:from-list '((2 1) (1 3))) (linalg:from-list '(3 5)))").print())
			.isEqualTo("#(4/5 7/5)");
		assertThat(eval("(linalg:dot (linalg:arange 3) (linalg:from-list '(4 5 6)))").print()).isEqualTo("17");
		assertThat(eval("(linalg:add 10 (linalg:from-list '(1 2)))").print()).isEqualTo("#(11 12)");
		assertThat(eval("(linalg:argmax (linalg:from-list '(1 9 3)))").print()).isEqualTo("1");
		// #'linalg:norm resolves through the same lazy load.
		assertThat(eval("(funcall #'linalg:norm (linalg:from-list '(3 4)))").print()).isEqualTo("5.0");
	}

	@Test
	void jsonParseReturnsPlistByDefault() {
		assertThat(eval("(rontolisp:json-parse \"{\\\"name\\\": \\\"rontolisp\\\", \\\"n\\\": 2}\")").print())
			.isEqualTo("(:name \"rontolisp\" :n 2)");
		assertThat(eval("(getf (rontolisp:json-parse \"{\\\"a\\\": {\\\"b\\\": [1, true, null]}}\") :a)").print())
			.isEqualTo("(:b (1 t nil))");
		assertThat(eval("(rontolisp:json-parse \"{}\")").print()).isEqualTo("nil");
	}

	@Test
	void jsonDoubleColonQualifierNamesTheSameFunctions() {
		// pkg::name also reaches external symbols, like Common Lisp.
		assertThat(eval("(rontolisp::json-stringify (list 1 2 3))").print()).isEqualTo("\"[1,2,3]\"");
		assertThat(eval("(getf (rontolisp::json-parse \"{\\\"n\\\": 5}\") :n)").print()).isEqualTo("5");
	}

	@Test
	void jsonSingleColonAccessToInternalHelperIsRejected() {
		// %json-parse is internal to the rontolisp package: a single colon only
		// reaches external symbols.
		assertThatThrownBy(() -> eval("(rontolisp:%json-parse \"1\" nil)"))
			.isInstanceOf(am.ik.rontolisp.LispPackageException.class)
			.hasMessageContaining("The symbol %json-parse is not external in the rontolisp package");
	}

	@Test
	void jsonParseParsesScalarsArraysAndEscapes() {
		assertThat(eval("(rontolisp:json-parse \"42\")").print()).isEqualTo("42");
		assertThat(eval("(rontolisp:json-parse \"-3.5\")").print()).isEqualTo("-3.5");
		assertThat(eval("(rontolisp:json-parse \"1e3\")").print()).isEqualTo("1000.0");
		// integers wider than 9 digits become floats on every backend (WASM i31)
		assertThat(eval("(floatp (rontolisp:json-parse \"1234567890123\"))").print()).isEqualTo("t");
		assertThat(eval("(rontolisp:json-parse \"true\")").print()).isEqualTo("t");
		assertThat(eval("(rontolisp:json-parse \"false\")").print()).isEqualTo("nil");
		assertThat(eval("(rontolisp:json-parse \"null\")").print()).isEqualTo("nil");
		assertThat(eval("(rontolisp:json-parse \"[1, [2, \\\"x\\\"], null]\")").print()).isEqualTo("(1 (2 \"x\") nil)");
		assertThat(eval("(rontolisp:json-parse \"\\\"a\\\\nb\\\"\")").print()).isEqualTo("\"a\nb\"");
		assertThat(eval("(rontolisp:json-parse \"\\\"\\\\u0041\\\\u3042\\\"\")").print()).isEqualTo("\"Aあ\"");
	}

	@Test
	void jsonParseHashTableMode() {
		assertThat(eval("""
				(let ((h (rontolisp:json-parse "{\\"content-type\\": \\"text/html\\"}" :hash-table)))
				  (gethash "content-type" h))""").print()).isEqualTo("\"text/html\"");
		// the representation applies recursively
		assertThat(eval("""
				(let ((h (rontolisp:json-parse "{\\"a\\": {\\"b\\": 5}}" :hash-table)))
				  (gethash "b" (gethash "a" h)))""").print()).isEqualTo("5");
	}

	@Test
	void jsonParseSignalsOnInvalidInput() {
		assertThatThrownBy(() -> eval("(rontolisp:json-parse \"{\\\"a\\\": \")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("json-parse");
		assertThatThrownBy(() -> eval("(rontolisp:json-parse \"1 2\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("trailing");
		assertThatThrownBy(() -> eval("(rontolisp:json-parse \"1\" :alist)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining(":plist or :hash-table");
		assertThatThrownBy(() -> eval("(rontolisp:json-parse 42)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a string");
		// an object key that does not read as a keyword only works with :hash-table
		assertThatThrownBy(() -> eval("(rontolisp:json-parse \"{\\\"a b\\\": 1}\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining(":hash-table");
		assertThat(eval("""
				(let ((h (rontolisp:json-parse "{\\"a b\\": 1}" :hash-table)))
				  (gethash "a b" h))""").print()).isEqualTo("1");
	}

	@Test
	void jsonStringifySerializesLispValues() {
		assertThat(eval("(rontolisp:json-stringify (list :name \"rontolisp\" :ok t :ver 1.5))").print())
			.isEqualTo("\"{\"name\":\"rontolisp\",\"ok\":true,\"ver\":1.5}\"");
		assertThat(eval("(rontolisp:json-stringify (list 1 (list 2 3) nil))").print()).isEqualTo("\"[1,[2,3],null]\"");
		assertThat(eval("(rontolisp:json-stringify \"a\\\"b\")").print()).isEqualTo("\"\"a\\\"b\"\"");
		assertThat(eval("(rontolisp:json-stringify :key)").print()).isEqualTo("\"\"key\"\"");
		assertThat(eval("(rontolisp:json-stringify 3/2)").print()).isEqualTo("\"1.5\"");
		assertThat(eval("""
				(let ((h (make-hash-table)))
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
		assertThat(eval("(funcall #'rontolisp:json-parse \"[7]\")").print()).isEqualTo("(7)");
	}

	@Test
	void defunRestCollectsSurplusArguments() {
		assertThat(evalMulti("(defun f (a &rest r) (list a r)) (f 1 2 3)").print()).isEqualTo("(1 (2 3))");
		assertThat(evalMulti("(defun f (a &rest r) (list a r)) (f 1)").print()).isEqualTo("(1 nil)");
	}

	@Test
	void defunOptionalDefaultsAndSuppliedP() {
		String def = "(defun f (x &optional (y 10) (z (* y 2) zp)) (list x y z zp)) ";
		assertThat(evalMulti(def + "(f 1)").print()).isEqualTo("(1 10 20 nil)");
		assertThat(evalMulti(def + "(f 1 2)").print()).isEqualTo("(1 2 4 nil)");
		assertThat(evalMulti(def + "(f 1 2 3)").print()).isEqualTo("(1 2 3 t)");
	}

	@Test
	void defunKeywordArguments() {
		String def = "(defun f (a &key (k 1 kp) m) (list a k kp m)) ";
		assertThat(evalMulti(def + "(f 0)").print()).isEqualTo("(0 1 nil nil)");
		assertThat(evalMulti(def + "(f 0 :k 5)").print()).isEqualTo("(0 5 t nil)");
		assertThat(evalMulti(def + "(f 0 :m 7 :k 9)").print()).isEqualTo("(0 9 t 7)");
	}

	@Test
	void defunKeywordRenamedIndicator() {
		assertThat(evalMulti("(defun f (&key ((:in x) 0)) x) (f :in 42)").print()).isEqualTo("42");
	}

	@Test
	void defunUnknownKeywordSignals() {
		assertThatThrownBy(() -> evalMulti("(defun f (&key k) k) (f :bogus 1)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("Unknown keyword argument: :bogus");
		assertThat(evalMulti("(defun f (&key k) k) (f :bogus 1 :allow-other-keys t)").print()).isEqualTo("nil");
		assertThat(evalMulti("(defun f (&key k &allow-other-keys) k) (f :bogus 1 :k 2)").print()).isEqualTo("2");
	}

	@Test
	void defunOptionalRestKeyCombined() {
		assertThat(evalMulti(
				"(defun f (a &optional b &rest r &key c &allow-other-keys) (list a b r c))" + " (f 1 2 :c 3 :d 4)")
			.print()).isEqualTo("(1 2 (:c 3 :d 4) 3)");
	}

	@Test
	void defunAuxVariables() {
		assertThat(evalMulti("(defun f (x &aux (y (+ x 1)) z) (list x y z)) (f 5)").print()).isEqualTo("(5 6 nil)");
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
	void defstructReturnsStructName() {
		assertThat(eval("(defstruct point x y)").print()).isEqualTo("point");
	}

	@Test
	void defstructPredicate() {
		assertThat(evalMulti("""
				(defstruct point x y)
				(defstruct circle r)
				(list (point-p (make-point :x 1 :y 2)) (point-p (make-circle :r 3)) (point-p '(1 2)) (point-p 42))
				""").print()).isEqualTo("(t nil nil nil)");
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
				""").print()).isEqualTo("(1 100 t)");
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
				""").print()).isEqualTo("(3 5 t)");
	}

	@Test
	void defstructOptionsAreNotSupported() {
		assertThatThrownBy(() -> eval("(defstruct (point (:conc-name pt-)) x y)"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("defstruct options are not supported");
	}

	@Test
	void defstructUnknownKeywordSignals() {
		assertThatThrownBy(() -> evalMulti("(defstruct point x y) (make-point :z 1)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("Unknown keyword argument: :z");
	}

}
