package am.ik.rontolisp.eval;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
import am.ik.rontolisp.compiler.StreamDesignators;
import am.ik.rontolisp.macro.FoldDifferential;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.testsupport.LoweredBuiltinValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
	void evalTheLoweredOnlyBuiltinsAsFunctionValues() {
		// The sweep: a CL FUNCTION with an evalCons case and no function VALUE answered
		// "The function NAME is undefined" for #'name -- and the consumer is not only
		// mapcar, rove's form-inspect rewrites every non-macro form inside an ok into
		// (apply #'op args). The interpreter reads the value out of the same
		// BuiltinFunctionWrappers catalog the compile paths inject, so this is the same
		// program and the same expectation as JvmLispCompilerTest and
		// WasmLispCompilerIntegrationTest.
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		for (LispVal expr : LispReader.readAllFromString(LoweredBuiltinValues.PROGRAM)) {
			evaluator.eval(expr);
		}
		assertThat(baos.toString().stripTrailing()).isEqualTo(LoweredBuiltinValues.OUTPUT);
	}

	@Test
	void evalReadSequenceAndWriteSequenceAsFunctionValues(@TempDir Path dir) throws Exception {
		// The bounding-index pair is its own shape: the operator reads :start / :end as
		// LITERAL keywords, so the wrapper re-extracts the runtime plist -- and an
		// ABSENT :end must not become an explicit nil, which the expansion would not
		// default to (length seq).
		Path file = dir.resolve("seq.txt");
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		for (LispVal expr : LispReader.readAllFromString("""
				(with-open-file (s "%s" :direction :output)
				  (funcall #'write-sequence "hello world" s))
				(let ((buf (make-string 5)))
				  (with-open-file (s "%s") (print (funcall #'read-sequence buf s)))
				  (print buf))
				(let ((buf (make-string 5)))
				  (with-open-file (s "%s") (print (funcall #'read-sequence buf s :start 1 :end 3)))
				  (print (funcall #'elt buf 1)))
				""".formatted(file, file, file))) {
			evaluator.eval(expr);
		}
		assertThat(baos.toString()).isEqualTo("""
				5
				"hello"
				3
				#\\h
				""");
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
	void evalStringOutputStreamNamesClearOnRead() {
		// CL's get-output-stream-string answers what has accumulated AND empties the
		// stream, so the second fetch sees only the writes that followed the first.
		assertThat(evalMulti("""
				(defvar *s* (make-string-output-stream))
				(write-string "ab" *s*)
				(list (get-output-stream-string *s*)
				      (progn (write-string "cd" *s*) (get-output-stream-string *s*))
				      (get-output-stream-string *s*))""").print()).isEqualTo("(\"ab\" \"cd\" \"\")");
	}

	@Test
	void evalStringInputStreamReadsWithoutWithInputFromString() {
		// The public spelling of the stream with-input-from-string binds: a library
		// that hands the stream around instead of scoping it needs this one.
		assertThat(evalMulti("""
				(defvar *in* (make-string-input-stream "ab
				cd"))
				(list (read-line *in*) (read-line *in*) (read-line *in* nil :eof))""").print())
			.isEqualTo("(\"ab\" \"cd\" :EOF)");
	}

	@Test
	void evalStringInputStreamHonoursStartAndEnd() {
		// CL's (make-string-input-stream string &optional start end) reads the
		// bounded substring only.
		assertThat(evalMulti("""
				(defvar *bounded* (make-string-input-stream "xxhixx" 2 4))
				(list (read-char *bounded*) (read-char *bounded*) (read-char *bounded* nil :eof))""").print())
			.isEqualTo("(#\\h #\\i :EOF)");
	}

	@Test
	void evalPeekCharLeavesTheCharacterInTheStream() {
		assertThat(eval("""
				(with-input-from-string (s "ab")
				  (list (peek-char nil s) (peek-char nil s) (read-char s) (read-char s)
				        (peek-char nil s nil :eof)))""").print()).isEqualTo("(#\\a #\\a #\\a #\\b :EOF)");
	}

	@Test
	void evalPeekCharSkipsWhitespaceAndUpToACharacter() {
		assertThat(eval("""
				(with-input-from-string (s "   xy")
				  (list (peek-char t s) (read-char s) (peek-char #\\y s) (read-char s)))""").print())
			.isEqualTo("(#\\x #\\x #\\y #\\y)");
	}

	@Test
	void evalReadCharEndOfFileIsCatchableAsEndOfFile() {
		// The read family signals the REGISTERED end-of-file class, which is what makes
		// a CL lexer's (handler-case ... (end-of-file (e) ...)) loop terminate.
		assertThat(eval("""
				(with-input-from-string (s "")
				  (handler-case (read-char s) (end-of-file () :caught)))""").print()).isEqualTo(":CAUGHT");
		assertThat(eval("""
				(with-input-from-string (s "")
				  (handler-case (read-char s) (error () :as-error)))""").print()).isEqualTo(":AS-ERROR");
	}

	@Test
	void evalMakeSynonymStreamResolvesTheNamedVariable() {
		// A synonym stream is a VALUE, not the nil designator: it answers true, it is a
		// stream, and it remembers the symbol it forwards to.
		assertThat(eval("(if (make-synonym-stream '*standard-output*) :true :false)").print()).isEqualTo(":TRUE");
		assertThat(eval("(streamp (make-synonym-stream '*standard-output*))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(input-stream-p (make-synonym-stream '*standard-input*))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(output-stream-p (make-synonym-stream '*standard-output*))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(close (make-synonym-stream '*standard-output*))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(synonym-stream-symbol (make-synonym-stream '*standard-output*))").print())
			.isEqualTo("*STANDARD-OUTPUT*");
		assertThat(eval("(make-synonym-stream '*standard-output*)").print())
			.isEqualTo("#<SYNONYM-STREAM :SYMBOL *STANDARD-OUTPUT*>");
		// Writing through it resolves the symbol AT WRITE TIME, for any symbol.
		assertThat(eval("""
				(progn
				  (defvar *port* t)
				  (defvar *syn* (make-synonym-stream '*port*))
				  (with-output-to-string (s)
				    (let ((*port* s)) (write-string "via" *syn*))))""")).isEqualTo(new LispString("via"));
	}

	@Test
	void evalSynonymStreamOverStandardOutputFollowsALaterBinding() {
		// The construct-once snapshot could not see a binding established AFTER the
		// synonym was built; the per-operation reader does.
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		LispVal result = evaluator.eval(LispReader.readFromString("""
				(progn
				  (defvar *sink* (make-synonym-stream '*standard-output*))
				  (with-output-to-string (*standard-output*)
				    (write-line "captured" *sink*)))"""));
		assertThat(result).isEqualTo(new LispString("captured\n"));
		assertThat(baos.toString()).isEmpty();
	}

	@Test
	void evalBindingStandardInputRedirectsTheStreamlessReadFamily() {
		// The input mirror of the *standard-output* redirect: binding *standard-input*
		// redirects read-line / read-char / read, including inside called functions, and
		// an explicit nil argument is the same designator.
		assertThat(eval("""
				(progn
				  (defun slurp (&optional stream) (read-line stream))
				  (with-input-from-string (*standard-input* "one")
				    (slurp)))""")).isEqualTo(new LispString("one"));
		assertThat(eval("(with-input-from-string (*standard-input* \"abc\") (read-char))").print()).isEqualTo("#\\a");
		assertThat(eval("(with-input-from-string (*standard-input* \"(1 2 3)\") (read))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(with-input-from-string (*standard-input* \"x\") (read-line nil))"))
			.isEqualTo(new LispString("x"));
	}

	@Test
	void evalMakeSynonymStreamOverStandardInputFollowsALaterBinding() {
		assertThat(eval("""
				(progn
				  (defvar *src* (make-synonym-stream '*standard-input*))
				  (with-input-from-string (*standard-input* "later")
				    (read-line *src*)))""")).isEqualTo(new LispString("later"));
	}

	@Test
	void evalExplicitNilStreamArgumentIsTheStandardOutputDesignator() {
		// CL's stream designator rule: a forwarded optional that arrives as nil must
		// reach the CURRENT *standard-output*, not raw stdout.
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		LispVal result = evaluator.eval(LispReader.readFromString("""
				(progn
				  (defun emit (x &optional stream)
				    (princ x stream)
				    (write-string "|" stream)
				    (write-line "" stream)
				    (fresh-line stream)
				    (terpri stream))
				  (with-output-to-string (*standard-output*)
				    (emit "a")
				    (print 1 nil)))"""));
		assertThat(result).isEqualTo(new LispString("a|\n\n1\n"));
		assertThat(baos.toString()).isEmpty();
	}

	@Test
	void evalErrorOutputIsTheProcessErrorStreamAndWarnFollowsARebinding() {
		// *error-output* is the process standard ERROR (the reserved handle 2), not the
		// t designator, so a diagnostic written through it stays off the program's
		// standard output; warn's report defaults to it and follows a rebinding.
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		LispVal result = evaluator.eval(LispReader.readFromString("""
				(let ((str (%make-string-output-stream)))
				  (let ((*error-output* str))
				    (warn "captured")
				    (format *error-output* "diag~%"))
				  (princ "out")
				  (%string-stream-contents str))"""));
		assertThat(result).isEqualTo(new LispString("WARNING: captured\ndiag\n"));
		assertThat(baos.toString()).isEqualTo("out");
	}

	@Test
	void evalErrorOutputIsAnOpenStreamThatSurvivesAClose() {
		assertThat(eval("(open-stream-p *error-output*)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(close *error-output*)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(open-stream-p *error-output*)")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void evalWithOutputToStringEmptyBody() {
		assertThat(eval("(with-output-to-string (s))")).isEqualTo(new LispString(""));
	}

	@Test
	void evalWithOutputToStringBindingStandardOutputCapturesStreamlessPrints() {
		// Binding *standard-output* as the target variable redirects the whole
		// stream-argument-less print family, including inside called functions
		// (s-sql's to-sql-name / sql-escape-string shape).
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		LispVal result = evaluator.eval(LispReader.readFromString("""
				(progn
				  (defun emit-name () (princ "foo") (write-char #\\.) (write-string "bar"))
				  (with-output-to-string (*standard-output*)
				    (emit-name)
				    (format t "~a" 42)))"""));
		assertThat(result).isEqualTo(new LispString("foo.bar42"));
		assertThat(baos.toString()).isEmpty();
	}

	@Test
	void evalLetBoundStandardOutputRedirectsAndRestores() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		LispVal result = evaluator.eval(LispReader.readFromString("""
				(let ((str (%make-string-output-stream)))
				  (let ((*standard-output* str))
				    (princ "in"))
				  (princ "out")
				  (%string-stream-contents str))"""));
		assertThat(result).isEqualTo(new LispString("in"));
		assertThat(baos.toString()).isEqualTo("out");
	}

	@Test
	void evalLoopSequentialForEqualsSeesPreviousLaterClauseValues() {
		// CL steps sequential for-clauses in source order at the top of each
		// iteration: a `for X = ... then` BEFORE a `for Y in ...` computes its step
		// against the PREVIOUS iteration's Y (s-sql's strcat position loop).
		assertThat(eval("(loop :for pos = 0 :then (+ pos (length arg)) "
				+ ":for arg :in '(\"ab\" \"cde\" \"f\") :collect pos)"))
			.isEqualTo(LispReader.readFromString("(0 2 5)"));
		// The other direction: a `for ... = ... then` AFTER a `for Y in` sees the
		// CURRENT iteration's Y (the previous-element idiom needs `and` for old Y).
		assertThat(eval("(loop for x in '(1 2 3) for prev = nil then x collect (list prev x))"))
			.isEqualTo(LispReader.readFromString("((nil 1) (2 2) (3 3))"));
		// A step AFTER an exhausted driver must not run at all (its form may not be
		// safe on the exhausted state).
		assertThat(eval("(loop for x in '(1 2) for a = 0 then (+ a (car (list x))) collect a)"))
			.isEqualTo(LispReader.readFromString("(0 2)"));
	}

	@Test
	void evalReadtableCaseIsConstantUpcase() {
		// The reader always upcases unescaped names (the standard readtable's :upcase
		// mode); s-sql's from-sql-name branches on this.
		assertThat(eval("(readtable-case *readtable*)")).isEqualTo(new LispSymbol(":UPCASE"));
		assertThat(eval("(readtable-case (copy-readtable))")).isEqualTo(new LispSymbol(":UPCASE"));
	}

	@Test
	void evalInternIntoFoundKeywordPackage() {
		// (find-package :keyword) answers the keyword-package designator; intern must
		// accept it like the literal :keyword (s-sql's from-sql-name).
		assertThat(eval("(intern \"ZAP\" (find-package :keyword))")).isEqualTo(new LispSymbol(":ZAP"));
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

	/**
	 * {@code ~W} writes its argument the way {@code write} does -- {@code prin1} under
	 * the printer control variables -- and consumes exactly one argument, so a following
	 * directive still sees the right one (rove's assertion description is
	 * {@code "Expect ~W to be ~:[true~;false~]."}).
	 */
	@Test
	void evalFormatWriteDirective() {
		assertThat(eval("(format nil \"~w\" \"str\")")).isEqualTo(new LispString("\"str\""));
		assertThat(eval("(format nil \"~w|~:w|~@w\" '(1 \"s\") 'a nil)")).isEqualTo(new LispString("(1 \"s\")|A|NIL"));
		assertThat(eval("(format nil \"Expect ~W to be ~:[true~;false~].\" '(= (add 1 2) 3) nil)"))
			.isEqualTo(new LispString("Expect (= (ADD 1 2) 3) to be true."));
		assertThat(eval("(let ((c \"Expect ~W to be ~:[true~;false~].\")) (format nil c '(= (add 1 2) 3) t))"))
			.isEqualTo(new LispString("Expect (= (ADD 1 2) 3) to be false."));
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
	void evalFormatNestedConditionalClauses() {
		// A ~:[ nested INSIDE another ~:[ clause. The two outer clauses consume a
		// different number of arguments, so the argument position after the conditional
		// is only known at run time -- expandFormat distributes the remainder of the
		// control string over both branches. postmodern's deftable constraint strings
		// end with exactly this shape.
		String deferrable = "~:[NOT DEFERRABLE~;DEFERRABLE INITIALLY ~:[IMMEDIATE~;DEFERRED~]~]";
		assertThat(eval("(format nil \"" + deferrable + "\" nil nil)")).isEqualTo(new LispString("NOT DEFERRABLE"));
		assertThat(eval("(format nil \"" + deferrable + "\" t nil)"))
			.isEqualTo(new LispString("DEFERRABLE INITIALLY IMMEDIATE"));
		assertThat(eval("(format nil \"" + deferrable + "\" t t)"))
			.isEqualTo(new LispString("DEFERRABLE INITIALLY DEFERRED"));
		// The whole \!unique control string, with ~{~^~} and the nested conditional.
		String unique = "ALTER TABLE ~A ADD CONSTRAINT ~A UNIQUE (~{~A~^, ~}) " + deferrable;
		assertThat(eval("(format nil \"" + unique + "\" \"t1\" \"c1\" '(\"a\" \"b\") t t)"))
			.isEqualTo(new LispString("ALTER TABLE t1 ADD CONSTRAINT c1 UNIQUE (a, b) DEFERRABLE INITIALLY DEFERRED"));
		assertThat(eval("(format nil \"" + unique + "\" \"t1\" \"c1\" '(\"a\") nil nil)"))
			.isEqualTo(new LispString("ALTER TABLE t1 ADD CONSTRAINT c1 UNIQUE (a) NOT DEFERRABLE"));
		// A directive AFTER an argument-divergent conditional: each branch continues
		// from its OWN argument position, so the trailing ~a differs per branch.
		assertThat(eval("(format nil \"[~:[x~;y~a~]|~a]\" nil \"P\" \"Q\")")).isEqualTo(new LispString("[x|P]"));
		assertThat(eval("(format nil \"[~:[x~;y~a~]|~a]\" t \"P\" \"Q\")")).isEqualTo(new LispString("[yP|Q]"));
		// Two divergent conditionals in sequence: the remainder distributes twice, so
		// each of the four argument positions is reachable.
		assertThat(eval("(format nil \"~:[~;~a~]~:[~;~a~]<~a>\" nil 2 \"B\" \"C\")")).isEqualTo(new LispString("B<C>"));
		assertThat(eval("(format nil \"~:[~;~a~]~:[~;~a~]<~a>\" 1 \"A\" 2 \"B\" \"C\")"))
			.isEqualTo(new LispString("AB<C>"));
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
	void evalFormatIterationEscape() {
		// ~^ terminates the iteration when no items remain, so the separator after
		// it is not emitted on the last pass (the s-sql/join idiom).
		assertThat(eval("(format nil \"~{~a~^, ~}\" '(1 2 3))")).isEqualTo(new LispString("1, 2, 3"));
		assertThat(eval("(format nil \"~{~a~^, ~}\" '(1))")).isEqualTo(new LispString("1"));
		assertThat(eval("(format nil \"~{~a~^, ~}\" nil)")).isEqualTo(new LispString(""));
		// Two items per pass: ~^ checks the elements beyond those already consumed.
		assertThat(eval("(format nil \"~{~a=~a~^&~}\" '(a 1 b 2))")).isEqualTo(new LispString("A=1&B=2"));
		// Inside ~:{ the escape ends the current sublist's body only -- including
		// the text after it, as in standard CL (SBCL prints "(1,2)(3" here too).
		assertThat(eval("(format nil \"~:{(~a~^,~a)~}\" '((1 2) (3)))")).isEqualTo(new LispString("(1,2)(3"));
		// Over the remaining top-level arguments (~@{ unrolls at expansion time).
		assertThat(eval("(format nil \"~@{~a~^, ~}\" 1 2 3)")).isEqualTo(new LispString("1, 2, 3"));
	}

	@Test
	void evalFormatEscapeAtTopLevel() {
		// At the top level the argument count is known statically: ~^ with
		// arguments left is dropped, with none left it truncates the control.
		assertThat(eval("(format nil \"~a~^, ~a\" 1 2)")).isEqualTo(new LispString("1, 2"));
		assertThat(eval("(format nil \"~a~^, ~a\" 1)")).isEqualTo(new LispString("1"));
	}

	@Test
	void evalFormatEscapeInsideConditionalIteration() {
		// The s-sql ARRAY[...] shape: ~:* backs up to re-read the tested argument
		// as the ~{ list, and ~^ joins its elements.
		assertThat(eval("(format nil \"~:['{}'~;ARRAY[~:*~{~A~^, ~}]~]\" '(1 2 3))"))
			.isEqualTo(new LispString("ARRAY[1, 2, 3]"));
		assertThat(eval("(format nil \"~:['{}'~;ARRAY[~:*~{~A~^, ~}]~]\" nil)")).isEqualTo(new LispString("'{}'"));
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
	void prin1EscapesQuotesAndBackslashesInStrings() {
		// *print-escape* = t: an embedded " or \ is preceded by a \, so the output reads
		// back. A NEWLINE is deliberately NOT escaped -- CLHS escapes only the string
		// terminator and the single-escape character (todo 216).
		assertThat(capture("(prin1 \"{\\\"hello\\\":\\\"aaa\\\"}\")")).isEqualTo("\"{\\\"hello\\\":\\\"aaa\\\"}\"");
		assertThat(capture("(prin1 \"a\\\"b\\\\c\")")).isEqualTo("\"a\\\"b\\\\c\"");
		assertThat(capture("(prin1 (list \"x\\\"y\" 'foo))")).isEqualTo("(\"x\\\"y\" FOO)");
		assertThat(eval("(format nil \"~s\" \"a\\\"b\")")).isEqualTo(new LispString("\"a\\\"b\""));
		assertThat(eval("(write-to-string \"a\\\\b\")")).isEqualTo(new LispString("\"a\\\\b\""));
		// princ / ~a stay the no-escape half by definition.
		assertThat(capture("(princ \"{\\\"hello\\\":\\\"aaa\\\"}\")")).isEqualTo("{\"hello\":\"aaa\"}");
		assertThat(eval("(princ-to-string \"a\\\"b\\\\c\")")).isEqualTo(new LispString("a\"b\\c"));
	}

	@Test
	void prin1OutputReadsBackAsTheSameString() {
		// The defining contract of prin1: (read-from-string (prin1-to-string s)) == s,
		// for a string carrying a quote, a backslash and a literal newline (todo 216).
		assertThat(evalMulti("""
				(let ((s (concatenate 'string "a" (string (code-char 34)) "b"
				                      (string (code-char 92)) "c"
				                      (string #\\Newline) "d")))
				  (list (equal (read-from-string (prin1-to-string s)) s) (length s)))
				""").print()).isEqualTo("(T 7)");
	}

	@Test
	void evalConcatenateStrings() {
		assertThat(eval("(concatenate 'string \"foo\" \"bar\" \"baz\")")).isEqualTo(new LispString("foobarbaz"));
		assertThat(eval("(concatenate 'string)")).isEqualTo(new LispString(""));
		assertThat(eval("(concatenate 'string \"x\")")).isEqualTo(new LispString("x"));
	}

	@Test
	void evalConcatenateListAndVectorResultTypes() {
		// The list / vector families walk elements, so the arguments may be any mix of
		// sequences; the compound spellings normalize to the same families.
		assertThat(eval("(princ-to-string (concatenate 'list '(1 2) \"ab\" #(3)))"))
			.isEqualTo(new LispString("(1 2 a b 3)"));
		assertThat(eval("(princ-to-string (concatenate 'vector '(1 2) #(3)))")).isEqualTo(new LispString("#(1 2 3)"));
		assertThat(eval("(princ-to-string (concatenate '(vector (unsigned-byte 8)) #(1) #(2 3)))"))
			.isEqualTo(new LispString("#(1 2 3)"));
		assertThat(eval("(concatenate 'list)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(princ-to-string (concatenate 'vector))")).isEqualTo(new LispString("#()"));
		// The result is always fresh: the last argument is copied, not shared.
		assertThat(eval("(let ((a (list 1 2))) (eq a (concatenate 'list a)))")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalConcatenateKeepsThePackedElementType() {
		// An (unsigned-byte 8|16|32) element type asks for the PACKED representation
		// make-array builds, not a general vector: ANSI requires the result to BE of the
		// requested type, and md5:md5sum-sequence's etypecase has a
		// (simple-array (unsigned-byte 8) (*)) arm and no general-vector one, which is
		// what cl-postgres' md5 authentication rides on (.todo/262).
		assertThat(eval("""
				(let ((v (concatenate '(vector (unsigned-byte 8)) #(1) '(2 3))))
				  (list (array-element-type v) (typep v '(simple-array (unsigned-byte 8) (*)))))""").print())
			.isEqualTo("((UNSIGNED-BYTE 8) T)");
		// The trailing * of (vector (unsigned-byte 8) *) is not part of the element type.
		assertThat(eval("(array-element-type (concatenate '(vector (unsigned-byte 8) *) #(1)))").print())
			.isEqualTo("(UNSIGNED-BYTE 8)");
		assertThat(eval("(array-element-type (concatenate '(simple-array (unsigned-byte 16) (*)) #(1)))").print())
			.isEqualTo("(UNSIGNED-BYTE 16)");
		assertThat(eval("(array-element-type (concatenate '(array (unsigned-byte 32) (*))))").print())
			.isEqualTo("(UNSIGNED-BYTE 32)");
		// Stores mask to the element width, exactly like make-array's.
		assertThat(eval("(princ-to-string (concatenate '(vector (unsigned-byte 8)) '(260 -1)))"))
			.isEqualTo(new LispString("#(4 255)"));
		// Any other element type -- and the spellings that carry a SIZE rather than an
		// element type -- stay the general vector.
		assertThat(eval("(array-element-type (concatenate '(vector character) \"ab\"))").print()).isEqualTo("T");
		assertThat(eval("(array-element-type (concatenate '(simple-vector 3) '(1 2 3)))").print()).isEqualTo("T");
		assertThat(eval("(array-element-type (concatenate '(vector (unsigned-byte 4)) '(1)))").print()).isEqualTo("T");
		assertThat(eval("(array-element-type (concatenate 'vector '(1)))").print()).isEqualTo("T");
	}

	@Test
	void evalCoerceKeepsThePackedElementType() {
		// The same result-type designator means the same thing whichever operator reads
		// it: (coerce seq '(vector (unsigned-byte 8))) is the packed vector concatenate
		// already built, not a general one. Every CL library spells its lookup tables
		// this way -- chipz's +crc32-table+ is (coerce '(...) '(vector (unsigned-byte
		// 32))) -- and the general answer lost both the element type and, on the compile
		// paths, the unboxed representation.
		assertThat(eval("""
				(let ((v (coerce '(1 2 3) '(vector (unsigned-byte 8)))))
				  (list (array-element-type v) (typep v '(simple-array (unsigned-byte 8) (*))) v))""").print())
			.isEqualTo("((UNSIGNED-BYTE 8) T #(1 2 3))");
		assertThat(eval("(array-element-type (coerce #(1 2) '(simple-array (unsigned-byte 16) (*))))").print())
			.isEqualTo("(UNSIGNED-BYTE 16)");
		assertThat(eval("(array-element-type (coerce '(1) '(array (unsigned-byte 32) (*))))").print())
			.isEqualTo("(UNSIGNED-BYTE 32)");
		// Stores mask to the element width, exactly like make-array's.
		assertThat(eval("(princ-to-string (coerce '(260 -1) '(vector (unsigned-byte 8))))"))
			.isEqualTo(new LispString("#(4 255)"));
		// Every other designator is untouched: the general vector, the spellings that
		// carry a SIZE, an unsupported width, and the non-vector families.
		assertThat(eval("(array-element-type (coerce '(1 2) 'vector))").print()).isEqualTo("T");
		assertThat(eval("(array-element-type (coerce '(1 2) '(vector character)))").print()).isEqualTo("T");
		assertThat(eval("(array-element-type (coerce '(1 2) '(simple-vector 2)))").print()).isEqualTo("T");
		assertThat(eval("(array-element-type (coerce '(1) '(vector (unsigned-byte 4))))").print()).isEqualTo("T");
		assertThat(eval("(coerce #(1 2) 'list)").print()).isEqualTo("(1 2)");
	}

	@Test
	void evalConcatenateAliasResultTypeKeepsThePackedElementType() {
		// The deftype chain carries the element type too, so fast-http's
		// 'simple-byte-vector is a packed result and not merely a vector one.
		assertThat(eval("""
				(progn (deftype simple-byte-vector (&optional (len '*))
				         `(simple-array (unsigned-byte 8) (,len)))
				       (array-element-type (concatenate 'simple-byte-vector #(1 2) #(3))))""").print())
			.isEqualTo("(UNSIGNED-BYTE 8)");
	}

	@Test
	void evalSeqIntVectorHelper() {
		// The internal helper the compile paths call: any sequence of integers, one
		// packed vector. The interpreter answers it too (it is a cl internal name).
		assertThat(eval("(array-element-type (%seq-int-vector '(1 2) 16))").print()).isEqualTo("(UNSIGNED-BYTE 16)");
		assertThat(eval("(princ-to-string (%seq-int-vector #(1 260) 8))")).isEqualTo(new LispString("#(1 4)"));
		assertThatThrownBy(() -> eval("(%seq-int-vector '(1) 12)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("unsupported element width");
	}

	@Test
	void evalConcatenateResolvesADeftypeAliasResultType() {
		// fast-http's multipart parser concatenates into 'simple-byte-vector, its own
		// (deftype simple-byte-vector (&optional (len '*)) `(simple-array (unsigned-byte
		// 8) (,len))): a result-type designator naming a registered deftype resolves
		// through its expansion to the family, for the zero-parameter and the
		// defaulted-parameter shapes alike.
		assertThat(eval("""
				(progn (deftype octet-vector () '(simple-array (unsigned-byte 8) (*)))
				       (princ-to-string (concatenate 'octet-vector #(1) #(2 3))))"""))
			.isEqualTo(new LispString("#(1 2 3)"));
		assertThat(eval("""
				(progn (deftype simple-byte-vector (&optional (len '*))
				         `(simple-array (unsigned-byte 8) (,len)))
				       (princ-to-string (concatenate 'simple-byte-vector #(1 2) #(3))))"""))
			.isEqualTo(new LispString("#(1 2 3)"));
	}

	@Test
	void evalConcatenateRejectsUnsupportedResultType() {
		assertThatThrownBy(() -> eval("(concatenate 'hash-table \"a\" \"b\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("string, list and vector result types");
		// A 'string result needs CHARACTERS: the sequence may be any kind, but an
		// element that is not a character is an error, not a silent princ.
		assertThatThrownBy(() -> eval("(concatenate 'string \"a\" '(7))")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("a 'string result needs characters");
	}

	@Test
	void evalConcatenateStringTakesAnySequence() {
		// Common Lisp's string family walks any character sequence, and nil -- the empty
		// list -- is the one real code leans on: s-sql builds "CREATE TABLE x" as
		// (concatenate 'string (unless tableset "TABLE ") name).
		assertThat(eval("(concatenate 'string \"a\" '(#\\b #\\c) #(#\\d) nil \"e\")"))
			.isEqualTo(new LispString("abcde"));
		assertThat(eval("(concatenate 'string nil nil)")).isEqualTo(new LispString(""));
		assertThat(eval("(concatenate 'string (unless t \"TABLE \") \"person\")")).isEqualTo(new LispString("person"));
	}

	@Test
	void evalConcatenateAsFunctionValue() {
		assertThat(eval("(princ-to-string (apply #'concatenate '(vector (unsigned-byte 8)) (list #(1) #(2 3))))"))
			.isEqualTo(new LispString("#(1 2 3)"));
		assertThat(eval("(apply #'concatenate 'string (list \"a\" \"b\"))")).isEqualTo(new LispString("ab"));
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
	void evalStringCaseOpsFoldEveryCharacterIndependently() {
		// CLHS defines string-upcase / string-downcase as char-upcase / char-downcase
		// applied to each character, so the fold is full-Unicode AND length-preserving:
		// no multi-character special casing (sharp s stays one character) and no
		// context-sensitive final sigma.
		assertThat(eval("(string-downcase \"ÉΛΩ\")")).isEqualTo(new LispString("éλω"));
		assertThat(eval("(string-upcase \"éλω\")")).isEqualTo(new LispString("ÉΛΩ"));
		assertThat(eval("(string-upcase \"straße\")")).isEqualTo(new LispString("STRAßE"));
		assertThat(eval("(length (string-upcase \"straße\"))")).isEqualTo(new LispInteger(6));
		assertThat(eval("(string-downcase \"ΑΣ\")")).isEqualTo(new LispString("ασ"));
		// Astral cased letters (Deseret U+10428 <-> U+10400) fold as single characters.
		assertThat(eval("(string-upcase \"𐐨𐐩\")")).isEqualTo(new LispString("𐐀𐐁"));
		assertThat(eval("(string-downcase \"𐐀𐐁\")")).isEqualTo(new LispString("𐐨𐐩"));
	}

	@Test
	void evalStringCapitalizeIsFullUnicode() {
		assertThat(eval("(string-capitalize \"élan vital\")")).isEqualTo(new LispString("Élan Vital"));
		assertThat(eval("(string-capitalize \"ЗДРАВСТВУЙ мир\")")).isEqualTo(new LispString("Здравствуй Мир"));
		// A caseless letter is still a word constituent, so the following letter is NOT
		// treated as a word start.
		assertThat(eval("(string-capitalize \"aあb 42x\")")).isEqualTo(new LispString("Aあb 42x"));
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
	void evalSetfEltDispatchesOverListStringAndVector() {
		// (setf (elt seq i) v) reaches all three sequence representations; the string
		// arm is the one the compile backends were missing (.todo/209).
		assertThat(evalMulti("(let ((s \"abc\")) (setf (elt s 0) #\\z) s)")).isEqualTo(new LispString("zbc"));
		assertThat(evalMulti("(let ((l (list 1 2 3))) (setf (elt l 0) 8) l)").print()).isEqualTo("(8 2 3)");
		assertThat(evalMulti("(let ((v (vector 1 2 3))) (setf (elt v 0) 9) v)").print()).isEqualTo("#(9 2 3)");
		assertThat(evalMulti("(let ((s (make-string 3 :initial-element #\\a))) (setf (elt s 1) #\\z) s)"))
			.isEqualTo(new LispString("aza"));
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
	void evalStringTrimAcceptsAListCharacterBag() {
		// CL's character bag is any sequence of characters; a LIST bag is what libraries
		// write (postmodern's execute-file lexer trims with '(#\Space #\Tab)).
		assertThat(eval("(string-trim '(#\\Space #\\Tab) \"\tx y \t\")")).isEqualTo(new LispString("x y"));
		assertThat(eval("(string-left-trim '(#\\Space) \"  z\")")).isEqualTo(new LispString("z"));
		assertThat(eval("(string-right-trim '(#\\x #\\y) \"helloxy\")")).isEqualTo(new LispString("hello"));
		assertThat(eval("(string-trim '() \" a \")")).isEqualTo(new LispString(" a "));
		// A runtime (non-literal) bag goes through the same widening.
		assertThat(eval("(let ((bag (list #\\Space))) (string-trim bag \" q \"))")).isEqualTo(new LispString("q"));
		assertThat(eval("(funcall #'string-trim '(#\\Space) \"  w  \")")).isEqualTo(new LispString("w"));
	}

	@Test
	void evalStringUpcaseAsFunctionValue() {
		assertThat(eval("(mapcar #'string-upcase (list \"ab\" \"cd\"))"))
			.isEqualTo(new LispCons(new LispString("AB"), new LispCons(new LispString("CD"), LispNil.INSTANCE)));
	}

	@Test
	void evalFormatNonLiteralControlString() {
		// A computed (non-literal) control string is rendered at runtime by the shared
		// runtime renderer (used by cl-who's escape-string) rather than being an error,
		// with the same directive semantics the literal expansion has -- ~x answers
		// uppercase digits on both.
		assertThat(eval("(let ((c \"~a-~a\")) (format nil c 1 2))")).isEqualTo(new LispString("1-2"));
		assertThat(eval("(let ((c \"&#x~x;\")) (format nil c 233))")).isEqualTo(new LispString("&#xE9;"));
	}

	@Test
	void evalFormatNotEnoughArguments() {
		assertThatThrownBy(() -> eval("(format t \"~a ~a\" 1)")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not enough arguments");
	}

	@Test
	void evalFormatUnsupportedDirectiveFallsBackToRuntimeRenderer() {
		// Same fallback as the uneven-~[ case: the static expansion declines and the
		// runtime renderer takes over -- it renders what it knows and emits an UNKNOWN
		// directive verbatim rather than failing the whole compile. ~< is declined but
		// rendered (a logical block); ~Q is neither.
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		evaluator.eval(LispReader.readFromString("(format t \"~@<[~a]~:>\" 65)"));
		assertThat(baos.toString()).isEqualTo("[65]");
		ByteArrayOutputStream unknown = new ByteArrayOutputStream();
		new LispEvaluator(new PrintStream(unknown)).eval(LispReader.readFromString("(format t \"~@<x~:>~Q\" 65)"));
		assertThat(unknown.toString()).isEqualTo("x~Q");
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
		assertThat(eval("(uiop:getenv \"RONTOLISP_DEFINITELY_UNSET_VAR\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(stringp (uiop:getenv \"PATH\"))")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalUiopSplitString() {
		// Upstream's semantics: split on ANY character of the separator sequence
		// (a string or a character list), scanning right to left so :max keeps the
		// UNsplit head; the empty string yields (""). sxql tokenizes dotted column
		// names with (uiop:split-string name :separator ".").
		assertThat(eval("(uiop:split-string \"a.b.c\" :separator \".\")").print()).isEqualTo("(\"a\" \"b\" \"c\")");
		assertThat(eval("(uiop:split-string \"a.b.c.d.e\" :max 3 :separator \".\")").print())
			.isEqualTo("(\"a.b.c\" \"d\" \"e\")");
		assertThat(eval("(uiop:split-string \"a b\tc\")").print()).isEqualTo("(\"a\" \"b\" \"c\")");
		assertThat(eval("(uiop:split-string \"\")").print()).isEqualTo("(\"\")");
		assertThat(eval("(uiop:split-string \"abc\" :separator \".\")").print()).isEqualTo("(\"abc\")");
		assertThat(eval("(uiop:split-string \"a..b\" :separator \".\")").print()).isEqualTo("(\"a\" \"\" \"b\")");
	}

	@Test
	void evalUiopStringHelpers() {
		// strcat's contract is the interesting one: nil is the empty string and a
		// character is a string of length one, so a caller can concatenate an optional
		// piece without testing it first.
		assertThat(eval("(uiop:strcat \"a\" nil #\\b \"c\")")).isEqualTo(new LispString("abc"));
		assertThat(eval("(uiop:strcat)")).isEqualTo(new LispString(""));
		assertThat(eval("(uiop:reduce/strcat (list \"aa\" \"bb\" \"cc\") :start 1)")).isEqualTo(new LispString("bbcc"));
		assertThat(eval("(uiop:reduce/strcat (list 1 22) :key #'princ-to-string)")).isEqualTo(new LispString("122"));
		assertThat(eval("(uiop:string-prefix-p \"ab\" \"abc\")")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(uiop:string-prefix-p \"abc\" \"ab\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(uiop:string-suffix-p \"abc\" \"bc\")")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(uiop:string-suffix-p \"abc\" \"a\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(uiop:string-enclosed-p \"a\" \"abc\" \"c\")")).isSameAs(LispTrue.INSTANCE);
		// A symbol is a string designator on both sides, as upstream.
		assertThat(eval("(uiop:string-prefix-p \"FO\" 'foo)")).isSameAs(LispTrue.INSTANCE);
		// stripln returns the stripped string AND the ending, so strcat of the two
		// reconstitutes the original.
		assertThat(eval("""
				(multiple-value-bind (s e) (uiop:stripln (uiop:strcat "hi" uiop:+crlf+))
				  (list s (length e) (equal e uiop:+crlf+)))
				""").print()).isEqualTo("(\"hi\" 2 T)");
		assertThat(eval("""
				(multiple-value-bind (s e) (uiop:stripln (uiop:strcat "hi" uiop:+lf+))
				  (list s (equal e uiop:+lf+)))
				""").print()).isEqualTo("(\"hi\" T)");
		assertThat(eval("(multiple-value-list (uiop:stripln \"hi\"))").print()).isEqualTo("(\"hi\" NIL)");
		assertThat(eval("(list (length uiop:+cr+) (length uiop:+lf+) (length uiop:+crlf+))").print())
			.isEqualTo("(1 1 2)");
		assertThat(eval("(uiop:standard-case-symbol-name \"foo\")")).isEqualTo(new LispString("FOO"));
		assertThat(eval("(uiop:standard-case-symbol-name 'foo)")).isEqualTo(new LispString("FOO"));
		assertThat(eval("(uiop:find-standard-case-symbol \"car\" :cl)").print()).isEqualTo("CAR");
		assertThat(eval("(uiop:find-standard-case-symbol \"definitely-absent\" :cl nil)")).isEqualTo(LispNil.INSTANCE);
		// frob-substrings: each substring is replaced (or removed with no frob), and a
		// later substring never matches inside an earlier match.
		assertThat(eval("(uiop:frob-substrings \"hello world\" (list \"o\") \"0\")"))
			.isEqualTo(new LispString("hell0 w0rld"));
		assertThat(eval("(uiop:frob-substrings \"hello world\" (list \"l\"))")).isEqualTo(new LispString("heo word"));
	}

	@Test
	void evalUiopCharacterTypeQuartetAnswersOneCharacterType() {
		// rontolisp has ONE character type -- (subtypep 'character 'base-char) is true --
		// so upstream's own derivation collapses to a single-element vector, index 0 and
		// a false +non-base-chars-exist-p+; base-string-p is then upstream's (and) = t
		// for every string, and the common element type is the constant 'character.
		assertThat(eval("(subtypep 'character 'base-char)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(length uiop:+character-types+)")).isEqualTo(new LispInteger(1));
		assertThat(eval("(aref uiop:+character-types+ 0)").print()).isEqualTo("CHARACTER");
		assertThat(eval("uiop:+max-character-type-index+")).isEqualTo(new LispInteger(0));
		assertThat(eval("uiop:+non-base-chars-exist-p+")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(uiop:character-type-index #\\a)")).isEqualTo(new LispInteger(0));
		assertThat(eval("(uiop:base-string-p \"abc\")")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(uiop:strings-common-element-type (list \"a\" #\\b))").print()).isEqualTo("CHARACTER");
	}

	@Test
	void evalUiopTimestampFamily() {
		// A timestamp is a REAL or a boolean, where t is -infinity and nil is +infinity.
		assertThat(eval("(uiop:timestamp< 1 2)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(uiop:timestamp< 2 1)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(uiop:timestamp< t 3)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(uiop:timestamp< t t)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(uiop:timestamp< 3 nil)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(uiop:timestamp< nil 3)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(uiop:timestamp<= 2 2)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(uiop:earlier-timestamp 1 2)")).isEqualTo(new LispInteger(1));
		assertThat(eval("(uiop:later-timestamp 1 2)")).isEqualTo(new LispInteger(2));
		assertThat(eval("(uiop:earliest-timestamp 3 1 2)")).isEqualTo(new LispInteger(1));
		assertThat(eval("(uiop:latest-timestamp 3 1 2)")).isEqualTo(new LispInteger(3));
		assertThat(eval("(uiop:timestamps-earliest (list 3 1))")).isEqualTo(new LispInteger(1));
		assertThat(eval("(uiop:timestamps-latest (list 3 1))")).isEqualTo(new LispInteger(3));
		// The empty list has no bound, so earliest is +infinity (nil) and latest is
		// -infinity (t) -- the reduce initial values upstream picks.
		assertThat(eval("(uiop:timestamps-earliest nil)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(uiop:timestamps-latest nil)")).isSameAs(LispTrue.INSTANCE);
		// timestamps< chains from nil = +infinity, so nothing non-empty is increasing.
		// Upstream's own answer, kept rather than "fixed".
		assertThat(eval("(uiop:timestamps< nil)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(uiop:timestamps< (list 1 2))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(uiop:timestamp*< 1 2)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(let ((s 1)) (uiop:latest-timestamp-f s 5 3) s)")).isEqualTo(new LispInteger(5));
	}

	@Test
	void evalUiopAccessAtAndFunctionDesignators() {
		// An AT specifier is a list of accessors applied in turn: an integer is ELT, a
		// keyword is GETF, nil is identity, a symbol or function is called, a cons is a
		// partially applied call (ensure-function).
		assertThat(eval("(uiop:access-at (list :a (list 10 20)) (list :a 1))")).isEqualTo(new LispInteger(20));
		assertThat(eval("(uiop:access-at (list 1 2 3) 2)")).isEqualTo(new LispInteger(3));
		assertThat(eval("(uiop:access-at (list 1 2) nil)").print()).isEqualTo("(1 2)");
		assertThat(eval("(uiop:access-at (list 1 2) 'length)")).isEqualTo(new LispInteger(2));
		assertThat(eval("(uiop:access-at 3 (list (list '+ 4)))")).isEqualTo(new LispInteger(7));
		assertThat(eval("(uiop:access-at-count 4)")).isEqualTo(new LispInteger(5));
		assertThat(eval("(uiop:access-at-count (list 2 :x))")).isEqualTo(new LispInteger(3));
		assertThat(eval("(uiop:access-at-count :k)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(funcall (uiop:ensure-function 'car) (list 9 8))")).isEqualTo(new LispInteger(9));
		assertThat(eval("(funcall (uiop:ensure-function 7))")).isEqualTo(new LispInteger(7));
		assertThat(eval("(funcall (uiop:ensure-function '(lambda (x) (* x 2))) 21)")).isEqualTo(new LispInteger(42));
		assertThat(eval("(uiop:call-function (list '+ 1) 2)")).isEqualTo(new LispInteger(3));
		assertThat(eval("(uiop:call-function \"car\" (list 4 5))")).isEqualTo(new LispInteger(4));
	}

	@Test
	void evalUiopListPlistAndHashHelpers() {
		assertThat(eval("(uiop:ensure-list 1)").print()).isEqualTo("(1)");
		assertThat(eval("(uiop:ensure-list (list 1 2))").print()).isEqualTo("(1 2)");
		assertThat(eval("(uiop:length=n-p (list 1 2) 2)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(uiop:length=n-p (list 1 2) 3)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(uiop:length=n-p nil 0)")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(uiop:remove-plist-key :b (list :a 1 :b 2 :c 3))").print()).isEqualTo("(:A 1 :C 3)");
		assertThat(eval("(uiop:remove-plist-keys (list :b :c) (list :a 1 :b 2 :c 3))").print()).isEqualTo("(:A 1)");
		assertThat(eval("(let ((l (list 1))) (uiop:appendf l (list 2 3)) l)").print()).isEqualTo("(1 2 3)");
		// ensure-gethash answers the entry AND whether it was already there, computing
		// the default through call-function only on a miss.
		assertThat(eval("""
				(let ((h (make-hash-table :test 'equal)))
				  (list (multiple-value-list (uiop:ensure-gethash "k" h (constantly 5)))
				        (multiple-value-list (uiop:ensure-gethash "k" h (constantly 6)))))
				""").print()).isEqualTo("((5 NIL) (5 T))");
		assertThat(eval("(gethash 2 (uiop:list-to-hash-set (list 1 2)))")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(uiop:lexicographic< #'< (list 1 2) (list 1 3))")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(uiop:lexicographic< #'< (list 1 3) (list 1 2))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(uiop:lexicographic<= #'< (list 1 2) (list 1 2))")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void evalUiopMacros() {
		// nest is purely syntactic: each form is nested inside the previous one's tail.
		assertThat(eval("(uiop:nest (list 1) (list 2) (list 3))").print()).isEqualTo("(1 (2 (3)))");
		assertThat(eval("(uiop:nest (let ((%n 2))) (* %n 3))")).isEqualTo(new LispInteger(6));
		// while-collecting binds one collector FUNCTION per name and answers one list
		// each, in order, as multiple values.
		assertThat(eval("""
				(multiple-value-list
				 (uiop:while-collecting (%foo %bar)
				   (dolist (x (list (list 'a 1) (list 'b 2)))
				     (%foo (first x))
				     (%bar (second x)))))
				""").print()).isEqualTo("((A B) (1 2))");
		// with-upgradability is a progn: rontolisp has no image to upgrade.
		assertThat(eval("(uiop:with-upgradability () 1 2 3)")).isEqualTo(new LispInteger(3));
		// compatfmt keeps the string: rontolisp reads every directive upstream strips.
		assertThat(eval("(uiop:compatfmt \"~3i~_ok\")")).isEqualTo(new LispString("~3i~_ok"));
		assertThat(eval("(uiop:parse-body '((declare (ignore x)) (+ 1 2)))").print()).isEqualTo("((+ 1 2))");
		assertThat(eval("(multiple-value-list (uiop:parse-body '(\"doc\" (+ 1 2)) :documentation t))").print())
			.isEqualTo("(((+ 1 2)) NIL \"doc\")");
		// A lone string is the BODY, not documentation -- the ordering rule a
		// macro-writing library depends on.
		assertThat(eval("(multiple-value-list (uiop:parse-body '(\"doc\") :documentation t))").print())
			.isEqualTo("((\"doc\") NIL NIL)");
	}

	@Test
	void evalUiopConditionHelpers() {
		assertThat(eval("(uiop:match-condition-p 'error (make-condition 'simple-error))")).isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(uiop:match-condition-p 'warning (make-condition 'simple-error))"))
			.isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(uiop:match-any-condition-p (make-condition 'simple-error) (list 'warning 'error))"))
			.isSameAs(LispTrue.INSTANCE);
		assertThat(eval("(uiop:match-condition-p #'(lambda (c) (typep c 'error)) (make-condition 'simple-error))"))
			.isSameAs(LispTrue.INSTANCE);
		// with-muffled-conditions invokes the muffle-warning restart for every condition
		// matching one of the patterns; the body's value is the form's value.
		assertThat(eval("(uiop:with-muffled-conditions ('(warning)) (warn \"quiet\") :muffled)").print())
			.isEqualTo(":MUFFLED");
		// style-warn signals uiop's own simple-style-warning, which really is a
		// style-warning: a handler for the CL supertype catches it. The class is
		// registered before the first handler form is expanded, or the interpreter would
		// miss what the compile paths catch.
		assertThat(eval("""
				(handler-bind ((style-warning (lambda (c) (muffle-warning c))))
				  (uiop:style-warn "styled ~A" 2)
				  :done)
				""").print()).isEqualTo(":DONE");
		assertThat(eval("""
				(let ((c (make-condition 'uiop:simple-style-warning
				                         :format-control "x" :format-arguments nil)))
				  (list (typep c 'style-warning) (typep c 'simple-condition)
				        (princ-to-string c)))
				""").print()).isEqualTo("(T T \"x\")");
		assertThat(
				eval("(list (uiop:boolean-to-feature-expression t) (uiop:boolean-to-feature-expression nil))").print())
			.isEqualTo("((:AND) (:OR))");
		assertThat(eval("(uiop:symbol-test-to-feature-expression \"CAR\" :cl)").print()).isEqualTo("(:AND)");
		assertThat(eval("(uiop:symbol-test-to-feature-expression \"DEFINITELY-ABSENT\" :cl)").print())
			.isEqualTo("(:OR)");
	}

	@Test
	void uiopUtilityMembersWithoutAPrimitiveNameThemselves() {
		// The two members of uiop/utility rontolisp cannot implement, and why: pushing
		// onto a hook needs (setf (symbol-value ...)) -- not a place on any backend --
		// and the debug loader needs a run-time load of a computed pathname. They carry
		// real definitions that signal, rather than a synthesized stub, so the message
		// says what is missing instead of only which name.
		assertThat(eval("""
				(handler-case (uiop:register-hook-function '*h* (lambda () 1))
				  (uiop:not-implemented-error (c) (princ-to-string c)))
				""").print()).contains("REGISTER-HOOK-FUNCTION", "symbol-value");
		assertThat(eval("""
				(handler-case (uiop:uiop-debug)
				  (uiop:not-implemented-error (c) (princ-to-string c)))
				""").print()).contains("LOAD-UIOP-DEBUG-UTILITY", "computed pathname");
		assertThat(eval("(consp uiop:*uiop-debug-utility*)")).isSameAs(LispTrue.INSTANCE);
	}

	@Test
	void bareGetenvIsNotACommonLispFunction() {
		// Common Lisp has no getenv: the only spelling is uiop's. An unqualified call is
		// an ordinary unknown symbol, not a built-in.
		assertThatThrownBy(() -> eval("(getenv \"PATH\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("GETENV");
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
	void evalReduceOverAnEmptySequenceCallsTheFunctionWithNoArguments() {
		// CL's rule, and it is load-bearing rather than a curiosity: esrap's
		// (reduce #'append all-children) answers nil for a result node with no children
		// while BUILDING a parse-error report, and used to signal instead.
		assertThat(eval("(reduce #'append '())")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(reduce #'+ '())")).isEqualTo(new LispInteger(0));
		assertThat(eval("(reduce #'append \"\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(reduce #'append '() :from-end t)")).isEqualTo(LispNil.INSTANCE);
		// An :initial-value keeps the old answer, and a non-empty sequence never calls
		// the function with zero arguments.
		assertThat(eval("(reduce #'+ '() :initial-value 7)")).isEqualTo(new LispInteger(7));
		assertThat(eval("(reduce #'append '((1 2) (3)))").print()).isEqualTo("(1 2 3)");
		// #'append itself must therefore accept zero arguments (its wrapper is what a
		// compiled backend funcalls here).
		assertThat(eval("(funcall #'append)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(funcall #'append '(1) '(2) '(3))").print()).isEqualTo("(1 2 3)");
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
	void evalLoopAnaphoricItOutsideClUser() {
		// The anaphor is whatever symbol named IT the loop was read with: read outside
		// cl-user it arrives package-qualified (ZZ::IT), and every selectable-clause
		// shape must still see it.
		assertThat(evalMulti("""
				(defpackage :zzit (:use :cl))
				(in-package :zzit)
				(list (loop for x in '(nil nil 3 4) when x return it)
				      (loop for x in '(1 nil 3 nil 5) when x collect it)
				      (let ((acc nil)) (loop for x in '(1 nil 2) when x do (push it acc)) (nreverse acc))
				      (loop for x in '(1 nil 3) when x collect it else collect :none)
				      (loop for x in '(4 nil 6) when x when (* x 10) collect it))
				""").print()).isEqualTo("(3 (1 3 5) (1 2) (1 :NONE 3) (40 60))");
		// A nested loop's conditionals still own their it in a foreign package too.
		assertThat(evalMulti("""
				(defpackage :zzit2 (:use :cl))
				(in-package :zzit2)
				(loop for x in '(1 nil) when x collect (loop for y in '(5 nil 6) when y collect it))
				""").print()).isEqualTo("((5 6))");
		// (loop-finish) is read the same way and must terminate normally there too.
		assertThat(evalMulti("""
				(defpackage :zzit3 (:use :cl))
				(in-package :zzit3)
				(loop for i from 1 collect i into xs do (when (>= i 3) (loop-finish)) finally (return (length xs)))
				""")).isEqualTo(new LispInteger(3));
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
	void evalLoopVariableKeepsLastValueAfterTermination() {
		// A loop variable is ONE binding stepped in place, so after the loop it holds
		// the value from the final iteration -- what a closure built in the body sees
		// when called afterwards. Every expectation here was measured on SBCL 2.6.5.
		assertThat(eval("(mapcar #'funcall (loop for x in '(1 2 3) collect (lambda () x)))").print())
			.isEqualTo("(3 3 3)");
		assertThat(eval("(mapcar #'funcall (loop for (a b) in '((1 2) (3 4)) collect (lambda () (list a b))))").print())
			.isEqualTo("((3 4) (3 4))");
		assertThat(eval("(mapcar #'funcall (loop for (a b) on '(1 2 3 4) by #'cddr collect (lambda () (list a b))))")
			.print()).isEqualTo("((3 4) (3 4))");
		assertThat(eval("(mapcar #'funcall (loop for x across #(1 2 3) collect (lambda () x)))").print())
			.isEqualTo("(3 3 3)");
		assertThat(eval("(mapcar #'funcall (loop for c across \"abc\" collect (lambda () c)))").print())
			.isEqualTo("(#\\c #\\c #\\c)");
		// dolist binds freshly per iteration; loop steps one binding. They are
		// supposed to disagree -- both match SBCL.
		assertThat(eval("(let (fs) (dolist (x '(1 2 3) (mapcar #'funcall (reverse fs))) (push (lambda () x) fs)))")
			.print()).isEqualTo("(1 2 3)");
		// `finally` sees the same last value.
		assertThat(eval("(loop for x in '(1 2 3) finally (return x))")).isEqualTo(new LispInteger(3));
		assertThat(eval("(loop for x in '(1 2 3 4) by #'cddr finally (return x))")).isEqualTo(new LispInteger(3));
		assertThat(eval("(loop for x across #(1 2 3) finally (return x))")).isEqualTo(new LispInteger(3));
		// The clauses that legitimately end at nil keep doing so: `on`'s variable IS
		// the cursor, and CL leaves a hash-key variable nil after the loop.
		assertThat(eval("(mapcar #'funcall (loop for c on '(1 2 3) collect (lambda () c)))").print())
			.isEqualTo("(NIL NIL NIL)");
		assertThat(eval(
				"(let ((h (make-hash-table))) (setf (gethash :a h) 1) (loop for k being the hash-key of h finally (return k)))"))
			.isSameAs(LispNil.INSTANCE);
		// A numeric variable steps PAST its limit, as in CL.
		assertThat(eval("(loop for i from 1 to 3 finally (return i))")).isEqualTo(new LispInteger(4));
		// Per-clause heads: the driver that ended keeps its previous value while an
		// earlier driver whose own test passed does advance.
		assertThat(eval("(loop for x in '(1 2 3) for y in '(10 20) finally (return (list x y)))").print())
			.isEqualTo("(3 20)");
		assertThat(eval("(loop for x in '(1 2 3) and y in '(10 20) finally (return (list x y)))").print())
			.isEqualTo("(2 20)");
		// A zero-iteration loop leaves the variable nil (nothing was ever assigned).
		assertThat(eval("(loop for x in '() finally (return x))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(loop for x across #() finally (return x))")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalLoopRepeatIsAClauseOrderedDriver() {
		// `repeat` terminates at its own position in the clause order, so a clause
		// written AFTER it does not run one extra time on the terminating pass...
		assertThat(eval("(let ((s (list 1 2 3 4 5))) (loop repeat 3 for x = (pop s) collect x) s)").print())
			.isEqualTo("(4 5)");
		assertThat(eval("(let ((n 0)) (loop repeat 3 for x = 0 then (progn (incf n) (1+ x))) n)"))
			.isEqualTo(new LispInteger(2));
		assertThat(eval("(loop repeat 3 for x = 1 then (* x 2) finally (return x))")).isEqualTo(new LispInteger(4));
		assertThat(eval("(loop repeat 2 for x in '(1 2 3) finally (return x))")).isEqualTo(new LispInteger(2));
		// ...while a clause written BEFORE it does, which is the mirrored answer and
		// equally what CL specifies (both measured on SBCL 2.6.5).
		assertThat(eval("(let ((n 0)) (loop for x = 0 then (progn (incf n) (1+ x)) repeat 3) n)"))
			.isEqualTo(new LispInteger(3));
		assertThat(eval("(loop for x in '(1 2 3) repeat 2 finally (return x))")).isEqualTo(new LispInteger(3));
		// The count itself is unaffected.
		assertThat(eval("(loop repeat 3 collect 'x)").print()).isEqualTo("(X X X)");
		assertThat(eval("(loop repeat 0 collect 'x)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(loop for x in '(1 2) repeat 5 collect x)").print()).isEqualTo("(1 2)");
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
		// silently ignored. The find family is NOT among them any more: it shares the
		// position family's scan and so takes the whole keyword set (see
		// evalFindFamilyTakesThePositionKeywordSet).
		assertThatThrownBy(() -> eval("(assoc 1 '((1 . a)) :from-end t)")).isInstanceOf(RuntimeException.class)
			.hasMessageContaining(":test/:test-not/:key");
		assertThatThrownBy(() -> eval("(remove 1 '(1 2) :count 1)")).isInstanceOf(RuntimeException.class)
			.hasMessageContaining(":test/:test-not/:key");
		assertThatThrownBy(() -> eval("(find 1 '(1 2) :count 1)")).isInstanceOf(RuntimeException.class)
			.hasMessageContaining(":FROM-END");
	}

	@Test
	void evalSequenceFunctionsTakeTestNot() {
		// :test-not is CL's complemented equality designator: it matches exactly where
		// the predicate is FALSE. esrap's error-report tree prunes with
		// (remove max children :test-not #'= :key #'first) -- keep only the entries whose
		// first is = max. One shared TestSpec decides it for the whole family, so the
		// compiled call and the first-class function value agree.
		assertThat(eval("(remove 3 '((1 a) (3 b) (3 c) (2 d)) :test-not #'= :key #'first)").print())
			.isEqualTo("((3 B) (3 C))");
		assertThat(eval("(member 3 '(1 2 3) :test-not #'=)").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(assoc 1 '((1 . a) (2 . b)) :test-not #'=)").print()).isEqualTo("(2 . B)");
		assertThat(eval("(rassoc 1 '((a . 1) (b . 2)) :test-not #'=)").print()).isEqualTo("(B . 2)");
		assertThat(eval("(count 1 '(1 2 3) :test-not #'=)").print()).isEqualTo("2");
		assertThat(eval("(position 1 '(1 2 3) :test-not #'=)").print()).isEqualTo("1");
		assertThat(eval("(find 1 '(1 2 3) :test-not #'=)").print()).isEqualTo("2");
		// The first-class function values decide the same way.
		assertThat(eval("(apply #'member 3 '(1 2 3) '(:test-not =))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(apply #'assoc 1 '((1 . a) (2 . b)) '(:test-not =))").print()).isEqualTo("(2 . B)");
	}

	@Test
	void evalFindFamilyTakesThePositionKeywordSet() {
		// find/find-if/find-if-not ARE the position family's scan with the matching
		// element as the answer, so they take :test/:test-not/:key/:start/:end/
		// :from-end too -- local-time's timestring splitter scans a bounded window.
		assertThat(eval("(find #\\Z \"2024-12-25T00:00:00Z\" :test #'char-equal :start 10)").print()).isEqualTo("#\\Z");
		assertThat(eval("(find #\\Z \"2024-12-25T00:00:00Z\" :test #'char-equal :start 10 :end 15)").print())
			.isEqualTo("NIL");
		assertThat(eval("(find 3 '(1 2 3 4 3) :from-end t)").print()).isEqualTo("3");
		assertThat(eval("(find 30 '((1 10) (2 30) (3 30)) :key #'second)").print()).isEqualTo("(2 30)");
		assertThat(eval("(find-if #'evenp '(1 3 4 6 7) :from-end t)").print()).isEqualTo("6");
		assertThat(eval("(find-if #'evenp '(1 3 4 6 7) :start 4)").print()).isEqualTo("NIL");
		assertThat(eval("(find-if-not #'evenp '(2 4 5 7))").print()).isEqualTo("5");
		assertThat(eval("(find-if #'oddp '((1 2) (2 3)) :key #'second)").print()).isEqualTo("(2 3)");
		// ... including as first-class values, where the keywords arrive at run time.
		assertThat(eval("(apply #'find 3 '(1 2 3 4 3) '(:from-end t))").print()).isEqualTo("3");
		assertThat(eval("(funcall #'find-if #'evenp '(1 3 4 6 7) :from-end t)").print()).isEqualTo("6");
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
	void evalLastWithACount() {
		// (last list &optional n): the last n conses. n beyond the length answers the
		// whole list, n of 0 the terminating atom -- alexandria:rotate needs both.
		assertThat(eval("(last '(1 2 3) 2)").print()).isEqualTo("(2 3)");
		assertThat(eval("(last '(1 2 3) 1)").print()).isEqualTo("(3)");
		assertThat(eval("(last '(1 2 3) 0)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(last '(1 2 3) 5)").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(last nil 2)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(last '(1 2 . 3) 1)").print()).isEqualTo("(2 . 3)");
		assertThat(evalMulti("(funcall #'last '(1 2 3) 2)").print()).isEqualTo("(2 3)");
		assertThat(evalMulti("(funcall #'last '(1 2 3))").print()).isEqualTo("(3)");
		assertThat(evalMulti("(apply #'last (list '(1 2 3) 2))").print()).isEqualTo("(2 3)");
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
	void evalGetfDefault() {
		// (getf plist indicator default): postmodern's deftable reads its foreign-key
		// options with (getf args :on-delete :restrict).
		assertThat(eval("(getf '(:a 1) :on-delete :restrict)").print()).isEqualTo(":RESTRICT");
		assertThat(eval("(getf '(:on-delete :cascade) :on-delete :restrict)").print()).isEqualTo(":CASCADE");
		assertThat(eval("(getf nil :x 7)").print()).isEqualTo("7");
		// A present indicator whose value is nil beats the default.
		assertThat(eval("(getf '(:a nil) :a :fallback)")).isSameAs(LispNil.INSTANCE);
		// getf is a FUNCTION, so the default is evaluated either way -- hit or miss.
		// (The compile paths must agree: the expansion binds it in the do init list.)
		assertThat(eval("(let ((n 0)) (getf '(:a 1) :a (setq n 1)) n)").print()).isEqualTo("1");
		assertThat(eval("(let ((n 0)) (getf '(:a 1) :b (setq n 1)) n)").print()).isEqualTo("1");
		assertThat(eval("(funcall #'getf '(:x 10) :y :none)").print()).isEqualTo(":NONE");
	}

	@Test
	void evalRassocIf() {
		// rassoc-if tests each pair's CDR; postmodern's json-encoder picks its unicode
		// escape entry with (rassoc-if #'consp ...).
		assertThat(eval("(rassoc-if #'consp '((1 . 2) (3 4 . 5)))").print()).isEqualTo("(3 4 . 5)");
		assertThat(eval("(rassoc-if #'oddp '((a . 2) (b . 3)))").print()).isEqualTo("(B . 3)");
		assertThat(eval("(rassoc-if #'evenp '((a . 1) (b . 3)))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(rassoc-if #'consp nil)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'rassoc-if #'consp '((1 . 2) (3 4 . 5)))").print()).isEqualTo("(3 4 . 5)");
	}

	@Test
	void evalRemoveDuplicates() {
		assertThat(eval("(remove-duplicates '(1 2 1 3))").print()).isEqualTo("(2 1 3)");
		assertThat(eval("(remove-duplicates '(1 2 3))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(remove-duplicates nil)")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(funcall #'remove-duplicates '(a b a a c))").print()).isEqualTo("(B A C)");
	}

	@Test
	void evalDeleteDuplicatesAndFromEnd() {
		// delete-duplicates shares remove-duplicates' lowering (the caller must use
		// the RESULT, so the non-destructive rendering is conforming), and both take
		// :from-end -- t keeps the FIRST occurrence of each duplicate set. sxql's
		// group-by and select-statement ordering call
		// (delete-duplicates l :test #'eq :from-end t).
		assertThat(eval("(delete-duplicates '(1 2 1 3 2))").print()).isEqualTo("(1 3 2)");
		assertThat(eval("(delete-duplicates '(1 2 1 3 2) :from-end t)").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(remove-duplicates '(1 2 1 3 2) :from-end t)").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(remove-duplicates '(1 2 1 3 2) :from-end nil)").print()).isEqualTo("(1 3 2)");
		assertThat(eval("(delete-duplicates '(\"a\" \"A\" \"b\") :test #'string-equal :from-end t)").print())
			.isEqualTo("(\"a\" \"b\")");
		assertThat(eval("(delete-duplicates '((1 . :a) (1 . :b) (2 . :c)) :key #'car :from-end t)").print())
			.isEqualTo("((1 . :A) (2 . :C))");
		assertThat(eval("(funcall #'delete-duplicates '(a b a a c))").print()).isEqualTo("(B A C)");
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
	void evalNconcOfAListOntoItself() {
		// Splicing a list onto ITSELF is the standard way to build a circular list: the
		// last argument is left untouched, so the splice must not walk into the cycle it
		// has just created. Preemptive so a regression fails instead of hanging the
		// suite.
		assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
			assertThat(eval("(let ((s (list 1 2 3))) (nconc s s) (list (nth 3 s) (nth 4 s) (nth 6 s)))").print())
				.isEqualTo("(1 2 1)");
			assertThat(eval("(let ((s (list 1 2))) (eq (nconc s s) s))")).isSameAs(LispTrue.INSTANCE);
			assertThat(eval("(let ((s (list 1 2))) (nconc nil s s) (nth 3 s))").print()).isEqualTo("2");
		});
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
	void mapcarAsValueOverMultipleLists() {
		// #'mapcar as a VALUE, applied to a list-of-lists (alexandria:mappend's shape).
		// The interpreter has always been right here; the case pins it as the reference
		// the compile backends' wrapper (BuiltinFunctionWrappers.mapFamilyWrapper)
		// matches.
		assertThat(eval("(apply #'mapcar #'list '((1 2) (3 4)))").print()).isEqualTo("((1 3) (2 4))");
		assertThat(eval("(apply #'mapcar #'+ '((1 2) (10 20) (100 200)))").print()).isEqualTo("(111 222)");
		assertThat(eval("(apply #'mapcar #'list '((1 2 3) (3 4)))").print()).isEqualTo("((1 3) (2 4))");
		assertThat(eval("(funcall #'mapcar #'1+ '(1 2 3))").print()).isEqualTo("(2 3 4)");
	}

	@Test
	void mapFamilyOverMultipleLists() {
		// Every member of the map* family takes N lists in Common Lisp, not just mapcar:
		// the function is called with one argument per list and the walk stops at the
		// SHORTEST list. Until .todo/218 mapcar was the only one -- the interpreter
		// signalled an arity error here and the compile backends silently dropped every
		// list but the first.
		assertThat(eval("(mapcan #'list '(1 2) '(3 4))").print()).isEqualTo("(1 3 2 4)");
		assertThat(eval("(maplist #'list '(1 2) '(3 4))").print()).isEqualTo("(((1 2) (3 4)) ((2) (4)))");
		assertThat(eval("(mapcon #'list '(1 2) '(3 4))").print()).isEqualTo("((1 2) (3 4) (2) (4))");
		// mapc/mapl run for effect and answer the FIRST list.
		assertThat(eval("(mapc #'list '(1 2) '(3 4))").print()).isEqualTo("(1 2)");
		assertThat(eval("(mapl #'list '(1 2) '(3 4))").print()).isEqualTo("(1 2)");
		assertThat(eval("""
				(let ((acc nil))
				  (mapc (lambda (a b) (setq acc (cons (list a b) acc))) '(1 2) '(3 4))
				  (reverse acc))""").print()).isEqualTo("((1 3) (2 4))");
		assertThat(eval("""
				(let ((acc nil))
				  (mapl (lambda (a b) (setq acc (cons (list a b) acc))) '(1 2) '(3 4))
				  (reverse acc))""").print()).isEqualTo("(((1 2) (3 4)) ((2) (4)))");
		// The shortest list stops the walk, whichever argument it is.
		assertThat(eval("(mapcan #'list '(1 2 3) '(3 4))").print()).isEqualTo("(1 3 2 4)");
		assertThat(eval("(mapc #'list nil '(1 2))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(maplist (lambda (a b) (+ (length a) (length b))) '(1 2 3) '(a b c d))").print())
			.isEqualTo("(7 5 3)");
		// Three lists work the same as two.
		assertThat(eval("(mapcan #'list '(1 2) '(3 4) '(5 6))").print()).isEqualTo("(1 3 5 2 4 6)");
	}

	@Test
	void mapFamilyAsValuesOverMultipleLists() {
		// The whole family as first-class VALUES, the (apply #'op function lists) shape.
		// maplist/mapcon/mapl are macro-expanded in call position, so the interpreter
		// used
		// to have no function object for them at all ("The function MAPLIST is
		// undefined")
		// while both compile backends wrapped a one-list version: .todo/218.
		assertThat(eval("(apply #'mapcan #'list '((1 2) (3 4)))").print()).isEqualTo("(1 3 2 4)");
		assertThat(eval("(apply #'maplist #'list '((1 2) (3 4)))").print()).isEqualTo("(((1 2) (3 4)) ((2) (4)))");
		assertThat(eval("(apply #'mapcon #'list '((1 2) (3 4)))").print()).isEqualTo("((1 2) (3 4) (2) (4))");
		assertThat(eval("(apply #'mapc #'list '((1 2) (3 4)))").print()).isEqualTo("(1 2)");
		assertThat(eval("(apply #'mapl #'list '((1 2) (3 4)))").print()).isEqualTo("(1 2)");
		// One list still answers what the call-position form answers.
		assertThat(eval("(funcall #'maplist #'identity '(1 2 3))").print()).isEqualTo("((1 2 3) (2 3) (3))");
		assertThat(eval("(funcall #'mapcan #'list '(1 2 3))").print()).isEqualTo("(1 2 3)");
		assertThat(eval("(funcall #'mapl #'car '(1 2))").print()).isEqualTo("(1 2)");
		assertThat(eval("(funcall #'mapc #'1+ '(1 2))").print()).isEqualTo("(1 2)");
		assertThat(eval("(funcall #'mapcon (lambda (x) (list (car x))) '(1 2 3))").print()).isEqualTo("(1 2 3)");
	}

	@Test
	void mapFamilyRejectsACallWithNoList() {
		// A count the operator cannot walk is rejected, not silently taken for one list.
		assertThatThrownBy(() -> eval("(mapcar #'1+)")).hasMessageContaining("MAPCAR expects at least 2 arguments");
		assertThatThrownBy(() -> eval("(mapc #'1+)")).hasMessageContaining("MAPC expects at least 2 arguments");
		assertThatThrownBy(() -> eval("(mapcan #'list)")).hasMessageContaining("MAPCAN expects at least 2 arguments");
		assertThatThrownBy(() -> eval("(maplist #'car)")).hasMessageContaining("MAPLIST expects at least 2 arguments");
		assertThatThrownBy(() -> eval("(mapcon #'list)")).hasMessageContaining("MAPCON expects at least 2 arguments");
		assertThatThrownBy(() -> eval("(mapl #'car)")).hasMessageContaining("MAPL expects at least 2 arguments");
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
		assertThatThrownBy(() -> eval("(mapl #'identity \"abc\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("MAPL: argument is not a list");
		assertThatThrownBy(() -> eval("(mapcar #'1+ 5)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("MAPCAR: argument is not a list: 5");
		// Every list position is guarded, not just the first.
		assertThatThrownBy(() -> eval("(mapcar #'list '(1) \"ab\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("MAPCAR: argument is not a list: \"ab\"");
		assertThatThrownBy(() -> eval("(mapc #'list '(1) \"ab\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("MAPC: argument is not a list: \"ab\"");
		assertThatThrownBy(() -> eval("(maplist #'list '(1) \"ab\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("MAPLIST: argument is not a list: \"ab\"");
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
	void evalCatchAndThrow() {
		// The catch value is the body's, or the thrown one; the tag is a runtime value
		// compared with eq, so the throw only has to be in the catch's DYNAMIC extent.
		assertThat(eval("(catch 'done (+ 1 2))").print()).isEqualTo("3");
		assertThat(eval("(catch 'done (throw 'done :thrown) :not-reached)").print()).isEqualTo(":THROWN");
		assertThat(eval("(catch 'outer (catch 'inner (throw 'outer :to-outer)) :not-reached)").print())
			.isEqualTo(":TO-OUTER");
		assertThat(evalMulti("""
				(defun probe (xs)
				  (catch 'found
				    (map nil (lambda (x) (if (evenp x) (throw 'found x))) xs)
				    :none))
				(list (probe '(1 3 4 5)) (probe '(1 3 5)))
				""").print()).isEqualTo("(4 :NONE)");
		// A computed tag: eq, so a fresh cons matches only itself.
		assertThat(eval("(let ((tag (list 1))) (catch tag (throw tag :computed)))").print()).isEqualTo(":COMPUTED");
		assertThat(eval("(catch 'outer (catch (list 1) (throw 'outer :not-eq-to-inner)))").print())
			.isEqualTo(":NOT-EQ-TO-INNER");
		// Intervening unwind-protect cleanups run, innermost first.
		assertThat(evalMulti("""
				(let ((log nil))
				  (list (catch 'z
				          (unwind-protect
				              (unwind-protect (throw 'z :deep) (setq log (cons :inner log)))
				            (setq log (cons :outer log))))
				        log))
				""").print()).isEqualTo("(:DEEP (:OUTER :INNER))");
		// A throw is not a condition: handler-case must not intercept it.
		assertThat(eval("(catch 'up (handler-case (throw 'up :through) (error (e) :caught)))").print())
			.isEqualTo(":THROUGH");
		// An unmatched throw is an ordinary error, not a raw signal.
		assertThatThrownBy(() -> eval("(throw 'nope 1)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("no enclosing catch for tag NOPE");
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
	void evalTreeEqual() {
		assertThat(eval("(tree-equal (list 1 (list 2 3)) (list 1 (list 2 3)))").print()).isEqualTo("T");
		assertThat(eval("(tree-equal (list 1 (list 2 3)) (list 1 (list 2 4)))").print()).isEqualTo("NIL");
		// A cons never matches a leaf, in either direction, so a shorter list and a
		// longer one differ at the cdr where one side has run out.
		assertThat(eval("(tree-equal (list 1 2) (list 1 2 3))").print()).isEqualTo("NIL");
		assertThat(eval("(tree-equal (list 1 (list 2)) (list 1 2))").print()).isEqualTo("NIL");
		assertThat(eval("(tree-equal 1 1)").print()).isEqualTo("T");
		assertThat(eval("(tree-equal (cons 1 2) (cons 1 2))").print()).isEqualTo("T");
		// The leaves compare with :test / :test-not, and nothing else does. (Two equal
		// strings are already eql here -- .kb/core-representation.md -- so the case that
		// separates the tests is one the default really rejects.)
		assertThat(eval("(tree-equal (list \"a\" (list \"b\")) (list \"A\" (list \"B\")))").print()).isEqualTo("NIL");
		assertThat(
				eval("(tree-equal (list \"a\" (list \"b\")) (list \"A\" (list \"B\")) :test #'string-equal)").print())
			.isEqualTo("T");
		assertThat(eval("(tree-equal (list 1 2) (list 1 2) :test-not #'eql)").print()).isEqualTo("NIL");
		assertThat(eval("(tree-equal (list 1 2) (list 3 4) :test-not #'eql)").print()).isEqualTo("NIL");
		// First-class: rove's `expands` assertion passes the test as #'name.
		assertThat(eval("(funcall #'tree-equal (list 1 2) (list 1 2))").print()).isEqualTo("T");
		// The cdr direction is a loop, not a recursion -- recursing there is one frame
		// per element and two long flat lists then overflow the stack. The interpreter
		// is the strictest of the four (one Java frame chain per Lisp call).
		assertThat(eval("""
				(let ((a nil) (b nil))
				  (dotimes (i 10000) (setq a (cons i a)) (setq b (cons i b)))
				  (tree-equal a b))
				""").print()).isEqualTo("T");
	}

	@Test
	void evalCountIfNot() {
		assertThat(eval("(count-if-not #'evenp (list 1 2 3 4 5))").print()).isEqualTo("3");
		assertThat(eval("(count-if-not #'evenp (vector 1 2 3 4 5))").print()).isEqualTo("3");
		assertThat(eval("(count-if-not #'alpha-char-p \"ab1c2\")").print()).isEqualTo("2");
		assertThat(eval("(count-if-not #'evenp (list 1 2 3 4 5) :start 1 :end 4)").print()).isEqualTo("1");
		assertThat(eval("(count-if-not #'oddp (list (list 1) (list 2) (list 3)) :key #'car)").print()).isEqualTo("1");
		// :from-end only reorders the predicate calls, so the count is the same.
		assertThat(eval("(count-if-not #'evenp (list 1 2 3 4 5) :from-end t)").print()).isEqualTo("3");
		assertThat(eval("(count-if-not #'evenp nil)").print()).isEqualTo("0");
	}

	@Test
	void evalSetExclusiveOr() {
		assertThat(eval("(set-exclusive-or (list 1 2 3) (list 2 3 4))").print()).isEqualTo("(1 4)");
		assertThat(eval("(set-exclusive-or (list 1 2) nil)").print()).isEqualTo("(1 2)");
		assertThat(eval("(set-exclusive-or nil (list 1 2))").print()).isEqualTo("(1 2)");
		assertThat(eval("(set-exclusive-or (list 1 2) (list 2 1))").print()).isEqualTo("NIL");
		assertThat(eval("(set-exclusive-or (list \"a\" \"b\") (list \"b\" \"c\") :test #'equal)").print())
			.isEqualTo("(\"a\" \"c\")");
		assertThat(eval("(set-exclusive-or (list \"a\" \"b\") (list \"b\" \"c\") :test-not #'string/=)").print())
			.isEqualTo("(\"a\" \"c\")");
		assertThat(eval("(set-exclusive-or (list (list 1 'a) (list 2 'b)) (list (list 2 'x)) :key #'car)").print())
			.isEqualTo("((1 A))");
	}

	@Test
	void evalMerge() {
		assertThat(eval("(merge 'list (list 1 3 5) (list 2 4 6) #'<)").print()).isEqualTo("(1 2 3 4 5 6)");
		assertThat(eval("(merge 'vector (vector 1 3) (vector 2 4) #'<)").print()).isEqualTo("#(1 2 3 4)");
		assertThat(eval("(merge 'string \"ac\" \"bd\" #'char<)").print()).isEqualTo("\"abcd\"");
		assertThat(eval("(merge 'list nil (list 1 2) #'<)").print()).isEqualTo("(1 2)");
		assertThat(eval("(merge 'list (list 1 2) nil #'<)").print()).isEqualTo("(1 2)");
		// Stable: a tie takes from sequence-1 first, so the (1 A) pair precedes (1 B).
		assertThat(eval("(merge 'list (list (list 1 'a)) (list (list 1 'b) (list 2 'c)) #'< :key #'car)").print())
			.isEqualTo("((1 A) (1 B) (2 C))");
		// The result type may be a run-time value -- merge coerces to whatever it holds.
		assertThat(eval("(let ((ty 'vector)) (merge ty (list 1) (list 2) #'<))").print()).isEqualTo("#(1 2)");
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
	void evalRuntimeClassDesignatorResolvesAnInternedNonExportedName() {
		// rove's make-reporter shape ((make-instance (intern (format nil "~A-~A" style
		// '#:reporter) package) ...)): a class named by a runtime-interned symbol
		// resolves whether or not its package exports it -- the interpreter registry
		// folds the single-colon and double-colon spellings alike
		// (ClosRegistry.normalize), which is what the compile paths' designator
		// dispatches were widened to match
		// (JvmLispCompilerTest#compileRuntimeClassDesignatorResolvesAnInternedNonExportedName).
		LispVal result = evalMulti("""
				(defpackage :rcd-pkg (:use :cl))
				(in-package :rcd-pkg)
				(defclass spec-rep () ((s :initarg :s)))
				(defclass sub-rep (spec-rep) ())
				(define-condition rcd-err (error) ((k :initarg :k)))
				(defun make-rep (style package)
				  (make-instance (intern (format nil "~A-~A" style '#:rep) package) :s 7))
				(in-package :cl-user)
				(let ((n (intern "SPEC-REP" :rcd-pkg)))
				  (list (slot-value (rcd-pkg::make-rep '#:spec (find-package :rcd-pkg)) 's)
				        (eq (find-class n) (find-class 'rcd-pkg::spec-rep))
				        (typep (make-instance n :s 1) n)
				        (subtypep (intern "SUB-REP" :rcd-pkg) n)
				        (handler-case (error (intern "RCD-ERR" :rcd-pkg) :k 5)
				          (rcd-pkg::rcd-err (c) (slot-value c 'k)))))
				""");
		assertThat(result.print()).isEqualTo("(7 T T T 5)");
	}

	@Test
	void evalTypeOfAnswersAForeignPackageClassNameUnqualifiedByTheCurrentPackage() {
		// type-of is the prelude defun that peels the %class- / %struct- tag prefix off
		// %class-designator and INTERNS the remainder, and the remainder of a class
		// defined in another package is already a canonical PKG:NAME spelling. The
		// interpreter's intern used to home that whole string into the current package
		// (TOF-APP::TOF-LIB:WIDGET), so (eq (type-of x) 'tof-lib:widget) was nil here
		// while the package-blind intern of the compile paths answered t
		// (JvmLispCompilerTest#compileTypeOfAnswersAForeignPackageClassNameUnqualifiedByTheCurrentPackage).
		// An already-qualified spelling now names the symbol it spells
		// (PackageResolver.internSpelling), so type-of agrees with class-name and with
		// the other three backends -- and an exported class keeps its single colon while
		// an internal one keeps its double colon.
		LispVal result = evalMulti("""
				(defpackage :tof-lib (:use :cl) (:export :widget :make-w))
				(in-package :tof-lib)
				(defclass widget () ())
				(defclass gadget () ())
				(defstruct point x y)
				(defun make-w () (make-instance 'widget))
				(defun make-g () (make-instance 'gadget))
				(defpackage :tof-app (:use :cl))
				(in-package :tof-app)
				(list (type-of (tof-lib:make-w))
				      (type-of (tof-lib::make-g))
				      (type-of (tof-lib::make-point :x 1 :y 2))
				      (eq (type-of (tof-lib:make-w)) 'tof-lib:widget)
				      (eq (type-of (tof-lib::make-g)) 'tof-lib::gadget)
				      (eq (type-of (tof-lib::make-point :x 1 :y 2)) 'tof-lib::point)
				      (eq (type-of (tof-lib:make-w)) (class-name (class-of (tof-lib:make-w))))
				      (type-of 42))
				""");
		assertThat(result.print()).isEqualTo("(TOF-LIB:WIDGET TOF-LIB::GADGET TOF-LIB::POINT T T T T INTEGER)");
	}

	@Test
	void evalTypecase() {
		assertThat(eval("(typecase 42 (string \"s\") (integer \"i\") (t \"?\"))").print()).isEqualTo("\"i\"");
		assertThat(eval("(typecase \"x\" (string \"s\") (integer \"i\") (t \"?\"))").print()).isEqualTo("\"s\"");
		assertThat(eval("(typecase 'sym (string \"s\") (integer \"i\") (t \"?\"))").print()).isEqualTo("\"?\"");
		assertThat(eval("(typecase '(1) (cons \"c\") (null \"n\"))").print()).isEqualTo("\"c\"");
	}

	@Test
	void evalCaseInsensitiveCharacterPredicates() {
		assertThat(eval("(list (char-lessp #\\a #\\B) (char-lessp #\\B #\\a) (char-lessp #\\a)"
				+ " (char-lessp #\\a #\\b #\\C))")
			.print()).isEqualTo("(T NIL T T)");
		assertThat(
				eval("(list (char-greaterp #\\b #\\A) (char-not-lessp #\\b #\\A)" + " (char-not-greaterp #\\a #\\B))")
					.print())
			.isEqualTo("(T T T)");
		// char-not-equal is ALL arguments pairwise distinct, like char/=
		assertThat(eval("(list (char-not-equal #\\a #\\b #\\c) (char-not-equal #\\a #\\b #\\A))").print())
			.isEqualTo("(T NIL)");
		// #\Newline is the one character graphic-char-p and standard-char-p disagree on
		assertThat(eval("(list (graphic-char-p #\\a) (graphic-char-p #\\Space) (graphic-char-p #\\Newline)"
				+ " (standard-char-p #\\Newline) (standard-char-p #\\Tab))")
			.print()).isEqualTo("(T T NIL T NIL)");
	}

	@Test
	void evalPrintCase() {
		// *print-case* converts the case of every SYMBOL the printer spells -- the value
		// verified against SBCL 2.2.9. :downcase and :capitalize are the two that
		// convert; :upcase is the stored spelling and the default.
		assertThat(evalMulti("""
				(list (let ((*print-case* :upcase)) (princ-to-string 'add-test))
				      (let ((*print-case* :downcase)) (princ-to-string 'add-test))
				      (let ((*print-case* :capitalize)) (princ-to-string 'add-test)))
				""").print()).isEqualTo("(\"ADD-TEST\" \"add-test\" \"Add-Test\")");
		// Every printing entry point: princ / prin1 / print / write-to-string and the
		// ~A / ~S directives, which lower to the first two.
		assertThat(evalMulti("""
				(let ((*print-case* :downcase))
				  (list (with-output-to-string (s) (princ 'foo s))
				        (with-output-to-string (s) (prin1 :foo s))
				        (write-to-string 'foo)
				        (format nil "~a ~s" 'foo 'foo)))
				""").print()).isEqualTo("(\"foo\" \":foo\" \"foo\" \"foo foo\")");
		// A STRING keeps its own characters and a character prints as itself, so the
		// conversion has to walk the value rather than the rendered text. nil and t are
		// symbols and do convert.
		assertThat(evalMulti("""
				(let ((*print-case* :downcase))
				  (list (prin1-to-string '(foo "Str" #\\A nil t 1))
				        (princ-to-string '(a . b))
				        (prin1-to-string (vector 'a 'b))
				        (princ-to-string nil)))
				""").print()).isEqualTo("(\"(foo \\\"Str\\\" #\\\\A nil t 1)\" \"(a . b)\" \"#(a b)\" \"nil\")");
		// A #'-reference honors it too: (mapcar #'princ-to-string names) never reaches
		// the operator seam, so the function VALUE carries the same route (the compile
		// paths get it from the wrapper defun whose body is the operator form).
		assertThat(evalMulti("""
				(let ((*print-case* :downcase))
				  (list (car (mapcar #'princ-to-string (list 'abc)))
				        (funcall #'prin1-to-string :def)
				        (apply #'write-to-string (list 'ghi))))
				""").print()).isEqualTo("(\"abc\" \":def\" \"ghi\")");
		// :capitalize keeps each word's first character as it stands and downcases the
		// rest of the word; a word is a run of alphanumerics. It never UPCASES a
		// lower-case character -- CLHS 22.1.3.3 converts only the upper-case ones, which
		// is where the rule parts company with string-capitalize.
		assertThat(evalMulti("""
				(let ((*print-case* :capitalize))
				  (list (princ-to-string (intern "*FOO*"))
				        (princ-to-string (intern "A1B2-C3"))
				        (princ-to-string (intern "foo-BAR"))
				        (string-capitalize "foo-BAR")))
				""").print()).isEqualTo("(\"*Foo*\" \"A1b2-C3\" \"foo-Bar\" \"Foo-Bar\")");
	}

	@Test
	void evalWriteAndPprintDispatch() {
		// write's keywords BIND the printer control variables around one print, which is
		// CL's own definition of them; only :escape / :readably change the text.
		assertThat(eval("(with-output-to-string (s) (write \"hi\" :stream s))").print()).isEqualTo("\"\\\"hi\\\"\"");
		assertThat(eval("(with-output-to-string (s) (write \"hi\" :stream s :escape nil :pretty nil))").print())
			.isEqualTo("\"hi\"");
		// The dispatch table is real: real entries, real typep matching, real priority.
		// set-pprint-dispatch MUTATES the table it is handed, which is what makes the
		// (copy-pprint-dispatch) + set-pprint-dispatch idiom work.
		assertThat(evalMulti("""
				(defvar tbl (copy-pprint-dispatch))
				(set-pprint-dispatch 'integer (lambda (s x) (princ (* 2 x) s)) 0 tbl)
				(set-pprint-dispatch 'string (lambda (s x) (princ (length x) s)) 5 tbl)
				(list (with-output-to-string (out) (funcall (pprint-dispatch 21 tbl) out 21))
				      (with-output-to-string (out) (funcall (pprint-dispatch "abc" tbl) out "abc"))
				      (nth-value 1 (pprint-dispatch 21 tbl))
				      (nth-value 1 (pprint-dispatch #\\a tbl)))
				""").print()).isEqualTo("(\"42\" \"3\" T NIL)");
		// A nil function removes the entry, and a copy is independent of its original.
		assertThat(evalMulti("""
				(defvar t1 (copy-pprint-dispatch))
				(set-pprint-dispatch 'integer (lambda (s x) (princ :hit s)) 0 t1)
				(defvar t2 (copy-pprint-dispatch t1))
				(set-pprint-dispatch 'integer nil 0 t1)
				(list (nth-value 1 (pprint-dispatch 1 t1)) (nth-value 1 (pprint-dispatch 1 t2)))
				""").print()).isEqualTo("(NIL T)");
	}

	@Test
	void evalPprintLogicalBlock() {
		// prefix + body + suffix; a NON-LIST object prints with write and the body is
		// skipped, which is CL's own rule.
		assertThat(eval("""
				(list (with-output-to-string (s)
				        (pprint-logical-block (s '(1 2 3) :prefix "<" :suffix ">") (princ "body" s)))
				      (with-output-to-string (s)
				        (pprint-logical-block (s 5 :prefix "<" :suffix ">") (princ "body" s)))
				      (with-output-to-string (s)
				        (pprint-logical-block (s nil) (princ "bare" s))))
				""").print()).isEqualTo("(\"<body>\" \"5\" \"bare\")");
		// No stream carries a column, so only the MANDATORY conditional newline breaks a
		// line and *print-pretty* gates even that (.kb/pretty-printer.md).
		assertThat(eval("""
				(list (with-output-to-string (s) (princ "a" s) (pprint-newline :fill s) (princ "b" s))
				      (with-output-to-string (s) (princ "a" s) (pprint-indent :block 4 s) (princ "b" s))
				      (length (with-output-to-string (s)
				                (princ "a" s) (pprint-newline :mandatory s) (princ "b" s)))
				      (let ((*print-pretty* nil))
				        (with-output-to-string (s)
				          (princ "a" s) (pprint-newline :mandatory s) (princ "b" s))))
				""").print()).isEqualTo("(\"ab\" \"ab\" 3 \"ab\")");
	}

	@Test
	void evalConsCompoundTypeSpecifier() {
		// (cons CAR-TYPE CDR-TYPE) tests each half. Before todo-248 the arguments fell
		// through to the ranged-NUMERIC default and were compiled as bounds, so
		// esrap's expression-kind table -- (cons (eql function) (cons symbol null)) --
		// evaluated the symbol `function` as a variable.
		assertThat(eval("(typep '(function bar) '(cons (eql function) (cons symbol null)))").print()).isEqualTo("T");
		assertThat(eval("(typep '(function bar baz) '(cons (eql function) (cons symbol null)))").print())
			.isEqualTo("NIL");
		assertThat(eval("(typep '(or \"a\") '(cons (eql or)))").print()).isEqualTo("T");
		assertThat(eval("(typep '(and \"a\") '(cons (eql or)))").print()).isEqualTo("NIL");
		assertThat(eval("(typep 5 '(cons (eql or)))").print()).isEqualTo("NIL");
		// * is "any", and an omitted half is the same
		assertThat(eval("(typep '(1 . 2) '(cons integer integer))").print()).isEqualTo("T");
		assertThat(eval("(typep '(1 . 2) '(cons * integer))").print()).isEqualTo("T");
		assertThat(eval("(typep '(1 . 2) '(cons integer string))").print()).isEqualTo("NIL");
		assertThat(eval("(typep '(1 . 2) '(cons))").print()).isEqualTo("T");
		assertThat(
				eval("(typecase '(function bar) ((cons (eql function) (cons symbol null)) \"f\") (t \"?\"))").print())
			.isEqualTo("\"f\"");
		// A compound spelling of a non-numeric atomic type carries no range bounds: the
		// base predicate alone.
		assertThat(eval("(typep #'car '(function (t) t))").print()).isEqualTo("T");
	}

	@Test
	void evalSizedStringTypeSpecifier() {
		// (string SIZE) is a string of EXACTLY that length. The size used to be ignored,
		// which made esrap's (typep sub '(or character (string 1))) answer true for every
		// string -- so (or "foo" "bar") compiled to the single-CHARACTER choice and a
		// successful parse advanced one position instead of three.
		assertThat(eval("(typep \"f\" '(string 1))").print()).isEqualTo("T");
		assertThat(eval("(typep \"foo\" '(string 1))").print()).isEqualTo("NIL");
		assertThat(eval("(typep \"foo\" '(string 3))").print()).isEqualTo("T");
		assertThat(eval("(typep 'foo '(string 3))").print()).isEqualTo("NIL");
		assertThat(eval("(typep \"foo\" '(string *))").print()).isEqualTo("T");
		assertThat(eval("(typep \"foo\" '(simple-string 3))").print()).isEqualTo("T");
		assertThat(eval("(typep #\\f '(or character (string 1)))").print()).isEqualTo("T");
		assertThat(eval("(typep \"foo\" '(or character (string 1)))").print()).isEqualTo("NIL");
	}

	@Test
	void evalSizedVectorTypeSpecifier() {
		// (simple-vector SIZE) carries only a SIZE -- its element type is always t --
		// while (vector ELEMENT-TYPE SIZE) leads with the element type. Reading the
		// simple-vector size as an element type made esrap's packrat cache dispatch
		// (typep cell '(simple-vector 41)) answer nil for its own vector and fall
		// through to the hash-table arm, which then got the vector.
		assertThat(eval("(typep (make-array 41) '(simple-vector 41))").print()).isEqualTo("T");
		assertThat(eval("(typep (make-array 4) '(simple-vector 41))").print()).isEqualTo("NIL");
		assertThat(eval("(typep (make-array 41) '(simple-vector *))").print()).isEqualTo("T");
		assertThat(eval("(typep (make-array 41) 'simple-vector)").print()).isEqualTo("T");
		// t as an element type reads as LispTrue, not a symbol, and must still name the
		// general array.
		assertThat(eval("(typep (make-array 3) '(vector t))").print()).isEqualTo("T");
		assertThat(eval("(typep (make-array 3) '(vector t 3))").print()).isEqualTo("T");
		assertThat(eval("(typep (make-array 3) '(vector t 4))").print()).isEqualTo("NIL");
		// A packed element type still rejects the general array.
		assertThat(eval("(typep (make-array 3) '(vector (unsigned-byte 8)))").print()).isEqualTo("NIL");
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
	void evalCtypecase() {
		assertThat(eval("(ctypecase 42 (string \"s\") (integer \"i\"))").print()).isEqualTo("\"i\"");
		assertThat(eval("(ctypecase \"x\" (string \"s\") (integer \"i\"))").print()).isEqualTo("\"s\"");
		assertThatThrownBy(() -> eval("(ctypecase 'sym (string \"s\") (integer \"i\"))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("ETYPECASE");
	}

	@Test
	void evalCaseKeyListSymbolsResolveLikeQuotedData() {
		// A clause KEY LIST's symbols must resolve against the reading package like a
		// lone-symbol key does, or a key never matches an imported symbol: sxql's
		// define-op dispatches (ecase struct-type ((unary-op ...) ...)) at macro
		// time where struct-type holds SXQL/SQL-TYPE:UNARY-OP.
		assertThat(evalMulti("""
				(defpackage #:ckl-home (:use #:cl) (:export #:aa #:bb))
				(defpackage #:ckl-user (:use #:cl) (:import-from #:ckl-home #:aa #:bb))
				(in-package #:ckl-user)
				(defmacro ckl-m (x)
				  `(quote ,(ecase x ((aa) 1) ((bb) 2))))
				(in-package #:cl-user)
				(list (ckl-user::ckl-m ckl-home:aa) (ckl-user::ckl-m ckl-home:bb))
				""").print()).isEqualTo("(1 2)");
	}

	@Test
	void evalSubtypepWalksStructIncludeChainsAndDeftypes() {
		// subtypep answers struct :include ancestry (sxql's (subtypep type
		// 'multiple-allowed-clause) gates whether a second where/join clause merges
		// or errors), structure-object as every struct's supertype, and a user
		// deftype expanding to an (or ...) of struct names -- exactly
		// multiple-allowed-clause's shape.
		assertThat(evalMulti("""
				(defstruct stp-base)
				(defstruct (stp-mid (:include stp-base)))
				(defstruct (stp-leaf (:include stp-mid)))
				(defstruct stp-other)
				(deftype stp-either () '(or stp-mid stp-other))
				(list (subtypep 'stp-leaf 'stp-base)
				      (subtypep 'stp-base 'stp-leaf)
				      (subtypep 'stp-leaf 'structure-object)
				      (subtypep 'stp-leaf 'stp-either)
				      (subtypep 'stp-other 'stp-either)
				      (subtypep 'stp-base 'stp-either))
				""").print()).isEqualTo("(T NIL T T T NIL)");
	}

	@Test
	void evalPackageTypeSpecifier() {
		// A package value is find-package's answer -- the name keyword
		// (.kb/symbol-runtime-api.md) -- so the package type test accepts exactly
		// those. cl-package-locks' resolve-package dispatches
		// (etypecase p (package p) (symbol (find-package p))), which sxql loads
		// through.
		assertThat(eval("(typep (find-package :cl-user) 'package)").print()).isEqualTo("T");
		assertThat(eval("(typep :no-such-package-xyz 'package)").print()).isEqualTo("NIL");
		assertThat(eval("(typep 'cl-user 'package)").print()).isEqualTo("NIL");
		assertThat(eval("(typep 42 'package)").print()).isEqualTo("NIL");
		assertThat(eval("(etypecase (find-package :cl) (package \"pkg\") (symbol \"sym\"))").print())
			.isEqualTo("\"pkg\"");
		assertThat(eval("(etypecase 'cl (package \"pkg\") (symbol \"sym\"))").print()).isEqualTo("\"sym\"");
	}

	@Test
	void evalPackageIsADefmethodSpecializer() {
		// rove's find-suite: a (package) method beside an unspecialized DESIGNATOR
		// method that calls find-package and recurses. The specializer shares the
		// package TYPE test above, and must rank ahead of keyword/symbol -- misordered,
		// the designator method would recurse forever.
		assertThat(evalMulti("""
				(defpackage :rov-suite (:use :cl))
				(defvar *package-suites* (make-hash-table :test 'equal))
				(defgeneric find-suite (package)
				  (:method ((package package))
				    (values (gethash package *package-suites*)))
				  (:method (package-name)
				    (check-type package-name string-designator)
				    (let ((package (find-package package-name)))
				      (unless package (error "No package '~A' found" package-name))
				      (find-suite package))))
				(setf (gethash :rov-suite *package-suites*) "suite")
				(list (find-suite :rov-suite) (find-suite "ROV-SUITE") (find-suite 'rov-suite))
				""").print()).isEqualTo("(\"suite\" \"suite\" \"suite\")");
	}

	@Test
	void evalPackageSpecializerOutranksKeywordAndSymbol() {
		// A package IS a keyword in this value model, so the package branch must be
		// tested first; a keyword naming no package falls through to the keyword method.
		assertThat(evalMulti("""
				(defpackage :psk-pkg (:use :cl))
				(defgeneric psk-kind (x))
				(defmethod psk-kind ((x symbol)) :symbol)
				(defmethod psk-kind ((x keyword)) :keyword)
				(defmethod psk-kind ((x package)) :package)
				(defmethod psk-kind (x) :other)
				(list (psk-kind :psk-pkg) (psk-kind :no-such-pkg-xyz) (psk-kind 'psk-pkg) (psk-kind 42))
				""").print()).isEqualTo("(:PACKAGE :KEYWORD :SYMBOL :OTHER)");
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
	void macroFunctionAndSpecialOperatorPPartitionTheOperators() {
		// Every operator with no function value is EITHER a special operator OR a macro,
		// and the two predicates agree on which -- the decision rove's assertion macro
		// makes about a form it is handed. Verified form for form against SBCL 2.2.9.
		assertThat(evalMulti("""
				(defmacro mfp-mac (x) `(list ,x))
				(defun mfp-probe (form)
				  (cond ((special-operator-p (first form)) :special)
				        ((macro-function (first form)) :macro)
				        (t :function)))
				(list (mfp-probe '(if a b)) (mfp-probe '(quote a)) (mfp-probe '(mfp-mac 1))
				      (mfp-probe '(when a b)) (mfp-probe '(handler-case a)) (mfp-probe '(car x))
				      (mfp-probe '(+ 1 2)))
				""").print()).isEqualTo("(:SPECIAL :SPECIAL :MACRO :MACRO :MACRO :FUNCTION :FUNCTION)");
		// defun/dolist are CL MACROS that rontolisp implements as special forms of its
		// own: special-operator-p says nil (a caller only asks "may I apply this"), and
		// macro-function answers for them instead. A computed name is decided the same
		// way as a literal one.
		assertThat(eval("(list (special-operator-p 'defun) (and (macro-function 'defun) t)"
				+ " (and (macro-function (intern \"WHEN\")) t) (macro-function 'car) (macro-function 'if))")
			.print()).isEqualTo("(NIL T T NIL NIL)");
	}

	@Test
	void macroFunctionIsTheRealExpanderOnTheInterpreter() {
		// The value is callable as CL's (funcall expander form env) single-step
		// expansion. The form's own car is data to the expander, so a form headed by
		// another name still expands through the macro that was asked for (SBCL's
		// answer). A compiled program answers a signalling stub instead -- the predicate
		// above is what every real caller reads.
		assertThat(evalMulti("""
				(defmacro mfe-mac (x) `(list ,x))
				(list (funcall (macro-function 'when) '(when t 1) nil)
				      (funcall (macro-function 'mfe-mac) '(mfe-mac 2))
				      (funcall (macro-function 'when) '(foo t 3) nil))
				""").print()).isEqualTo("((IF T 1 NIL) (LIST 2) (IF T 3 NIL))");
	}

	@Test
	void macroexpand1AnswersTheExpandedPFlag() {
		// CL's second value: t when the operator was a macro, nil when the form came
		// back unchanged (the .todo/214 inventory row).
		assertThat(evalMulti("""
				(defmacro mxp-mac (x) `(list ,x))
				(list (multiple-value-list (macroexpand-1 '(mxp-mac 1)))
				      (multiple-value-list (macroexpand-1 '(+ 1 2)))
				      (multiple-value-list (macroexpand '(mxp-mac 1))))
				""").print()).isEqualTo("(((LIST 1) T) ((+ 1 2) NIL) ((LIST 1) T))");
	}

	@Test
	void macroexpand1OfAComputedArgumentExpandsOnTheInterpreter() {
		// The interpreter still HOLDS the macro table, so a computed argument expands
		// like a literal one and the standard "expand until it stops expanding" loop
		// terminates by making progress. The compiled backends cannot expand, and they
		// SIGNAL rather than answer the form itself, so the same loop terminates there
		// too (JvmLispCompilerTest / WasmLispCompilerIntegrationTest).
		assertThat(evalMulti("""
				(defmacro mxc-mac (x) `(list ,x))
				(defun mxc-steps (form)
				  (do ((step form (macroexpand-1 step)))
				      ((or (special-operator-p (first step)) (not (macro-function (first step)))) step)))
				(list (mxc-steps (list 'car 'x)) (mxc-steps (list 'mxc-mac 9)) (mxc-steps (list 'when 'a 'b)))
				""").print()).isEqualTo("((CAR X) (LIST 9) (IF A B NIL))");
	}

	@Test
	void setfMacroFunctionAliasesAUserMacro() {
		// lisp-namespace's (setf (macro-function 'nslet) (macro-function
		// 'namespace-let)):
		// the new name shares the existing macro's expander, so both spellings expand
		// identically -- and macroexpand-1 through the alias yields the same form.
		assertThat(evalMulti("""
				(defmacro sfmf-greet (x) `(list :hello ,x))
				(setf (macro-function 'sfmf-hi) (macro-function 'sfmf-greet))
				(list (sfmf-hi "world") (macroexpand-1 '(sfmf-hi 1)))
				""").print()).isEqualTo("((:HELLO \"world\") (LIST :HELLO 1))");
	}

	@Test
	void setfMacroFunctionRejectsNonAliasShapes() {
		// There is no runtime macro table on any backend, so only aliasing an existing
		// user macro is supported -- an arbitrary expander function is rejected, as is
		// an alias of a name that is not a defmacro-defined macro.
		assertThatThrownBy(() -> eval("(setf (macro-function 'sfmf-x) (lambda (form env) form))"))
			.hasMessageContaining("only supports aliasing a user macro");
		assertThatThrownBy(() -> eval("(setf (macro-function 'sfmf-y) (macro-function 'sfmf-no-such))"))
			.hasMessageContaining("is not a user macro");
	}

	@Test
	void evalBackquoteSplicingIntoQuote() {
		// ',@xs = (quote ,@xs) = (cons 'quote xs): the one-element splice yields 'x,
		// the empty splice (QUOTE), the two-element splice (QUOTE A B) -- the exact
		// list structure SBCL produces, no arity special-casing.
		assertThat(eval("`(a ',@'(b))").print()).isEqualTo("(A (QUOTE B))");
		assertThat(eval("(let ((args nil)) `(f ',@args))").print()).isEqualTo("(F (QUOTE))");
		assertThat(eval("(let ((args '(a b))) `(f ',@args))").print()).isEqualTo("(F (QUOTE A B))");
		assertThat(eval("(let ((fns '(g))) `(f #',@fns))").print()).isEqualTo("(F (FUNCTION G))");
	}

	@Test
	void evalSymbolMacroletBasic() {
		// Free references substitute; quoted data and empty binding lists do not.
		assertThat(eval("(symbol-macrolet ((x 42)) x)")).isEqualTo(new LispInteger(42));
		assertThat(eval("(symbol-macrolet ((x (+ 1 2))) (* x 10))")).isEqualTo(new LispInteger(30));
		assertThat(eval("(symbol-macrolet () 'ok)").print()).isEqualTo("OK");
		assertThat(eval("(symbol-macrolet ((x 42)) (list 'x x))").print()).isEqualTo("(X 42)");
		// Declarations directly in the body describe the macro names; they are dropped.
		assertThat(eval("(symbol-macrolet ((x 42)) (declare (ignorable x)) x)")).isEqualTo(new LispInteger(42));
		// case-family keys are data, never substituted.
		assertThat(eval("(symbol-macrolet ((x 42)) (case 'x ((x) 'sym) (t 'other)))").print()).isEqualTo("SYM");
	}

	@Test
	void evalSymbolMacroletShadowing() {
		// trivia's match expansions rely on an inner let rebinding a symbol-macro name.
		assertThat(eval("(symbol-macrolet ((x 42)) (let ((x 1)) x))")).isEqualTo(new LispInteger(1));
		assertThat(eval("(symbol-macrolet ((x 42)) (list (let ((x 1)) x) x))").print()).isEqualTo("(1 42)");
		// The shadowing binding's own init is still in the outer scope.
		assertThat(eval("(symbol-macrolet ((x 42)) (let ((x (+ x 1))) x))")).isEqualTo(new LispInteger(43));
		// A lambda parameter and an iteration variable shadow too.
		assertThat(eval("(symbol-macrolet ((x 42)) (funcall (lambda (x) x) 7))")).isEqualTo(new LispInteger(7));
		assertThat(eval("(symbol-macrolet ((x 42)) (dotimes (x 3 x)))")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalSymbolMacroletNested() {
		assertThat(eval("(symbol-macrolet ((x 1)) (symbol-macrolet ((y 2)) (+ x y)))")).isEqualTo(new LispInteger(3));
		// An inner binding of the same name shadows the outer one.
		assertThat(eval("(symbol-macrolet ((x 1)) (symbol-macrolet ((x 2)) x))")).isEqualTo(new LispInteger(2));
		// Sibling macros chain through each other's expansions.
		assertThat(eval("(symbol-macrolet ((a b) (b 42)) a)")).isEqualTo(new LispInteger(42));
	}

	@Test
	void evalSymbolMacroletSetfThroughSlotValuePlace() {
		// The dbi driver shape: the body ASSIGNS through a slot-value expansion.
		assertThat(evalMulti("""
				(defclass conn () ((auto-commit :initform nil)))
				(defvar *c* (make-instance 'conn))
				(symbol-macrolet ((auto-commit (slot-value *c* 'auto-commit)))
				  (setf auto-commit 'on)
				  auto-commit)
				""").print()).isEqualTo("ON");
	}

	@Test
	void evalSymbolMacroletSetqAndIncfWriteThrough() {
		// setq of a symbol-macro target becomes a setf of the expansion place.
		assertThat(eval("(let ((cell (list 1 2)))" + " (symbol-macrolet ((head (car cell))) (setq head 99) cell))")
			.print()).isEqualTo("(99 2)");
		// A mixed setq keeps the plain-variable pairs working.
		assertThat(eval("(let ((y 0) (cell (cons 1 nil)))"
				+ " (symbol-macrolet ((head (car cell))) (setq y 5 head 9) (list y (car cell))))")
			.print()).isEqualTo("(5 9)");
		// Read-modify-write macros expand against the substituted place.
		assertThat(
				eval("(let ((cell (cons 1 nil)))" + " (symbol-macrolet ((head (car cell))) (incf head) (car cell)))"))
			.isEqualTo(new LispInteger(2));
	}

	@Test
	void evalSymbolMacroletInsideLambdaBodyAndUserMacro() {
		// A reference inside a lambda body substitutes (the expansion closes over n).
		assertThat(eval("(let ((n 10)) (symbol-macrolet ((big (* n n))) (funcall (lambda () big))))"))
			.isEqualTo(new LispInteger(100));
		// A user macro met by the walk is expanded first, then its expansion substituted
		// (macro arguments may be data; the expansion is code).
		assertThat(evalMulti("(defmacro twice (f) `(progn ,f ,f))" + " (defvar *acc* nil)"
				+ " (symbol-macrolet ((x (push 1 *acc*))) (twice x))" + " (length *acc*)"))
			.isEqualTo(new LispInteger(2));
	}

	@Test
	void evalDefineCompilerMacroRewritesCallSites() {
		// A compiler macro rewrites calls to the function it names.
		assertThat(evalMulti("(defun myinc (x) (+ x 1))" + " (define-compiler-macro myinc (x) `(+ ,x 100)) (myinc 10)"))
			.isEqualTo(new LispInteger(110));
		// Returning the &whole form is the decline idiom: the ordinary function wins,
		// and the application must not loop on the freshly consed copy it gets back.
		assertThat(evalMulti("(defun mydec (x) (- x 1))"
				+ " (define-compiler-macro mydec (&whole form x) (declare (ignore x)) form) (mydec 10)"))
			.isEqualTo(new LispInteger(9));
		// A macro that decides per call site: constant argument rewritten, variable not.
		assertThat(evalMulti("(defun mytwice (x) (* x 2))"
				+ " (define-compiler-macro mytwice (&whole form x) (if (constantp x) `(+ ,x ,x) form))"
				+ " (list (mytwice 5) (let ((v 5)) (mytwice v)))")
			.print()).isEqualTo("(10 10)");
		// A body that signals is a hint that cannot be honoured, not a program error.
		assertThat(evalMulti(
				"(defun mysafe (x) (+ x 1))" + " (define-compiler-macro mysafe (x) (error \"no\")) (mysafe 10)"))
			.isEqualTo(new LispInteger(11));
		// A compiler macro over a standard operator is never registered.
		assertThat(evalMulti("(define-compiler-macro car (x) 99) (car (list 1 2))")).isEqualTo(new LispInteger(1));
	}

	@Test
	void evalDefmacroWinsOverCompilerMacro() {
		// CL: a macro function of the same name is expanded, the compiler macro ignored.
		assertThat(evalMulti(
				"(defmacro mypick (x) `(* ,x 3))" + " (define-compiler-macro mypick (x) `(* ,x 7))" + " (mypick 2)"))
			.isEqualTo(new LispInteger(6));
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
	void evalDestructuringBindDottedTailWithNestedKeywords() {
		// A dotted tail in a keyword-using pattern is CL shorthand for &rest (trivia
		// level0's ematch0 destructures clauses as ((pattern &rest body) . rest)).
		assertThat(eval("(destructuring-bind ((pat &rest body) . rest) '((p 1 2) (q 3)) (list pat body rest))").print())
			.isEqualTo("(P (1 2) ((Q 3)))");
		assertThat(eval("(destructuring-bind ((a &optional (b 9)) . more) '((1)) (list a b more))").print())
			.isEqualTo("(1 9 NIL)");
	}

	@Test
	void evalDestructuringBindUnknownKeywordSignals() {
		assertThatThrownBy(() -> eval("(destructuring-bind (&key k) '(:other 1) k)"))
			.hasMessageContaining("Unknown keyword argument");
		assertThat(eval("(destructuring-bind (&key k &allow-other-keys) '(:other 1) k)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalDestructuringBindBindsWholeAndRejectsEnvironment() {
		// &whole binds the whole source list (first element of the pattern, per CL).
		assertThat(eval("(destructuring-bind (&whole w a b) '(1 2) (list w a b))").print()).isEqualTo("((1 2) 1 2)");
		// &environment stays unsupported: there is no environment object to bind.
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

	// Renders the values of one top-level form the way the REPL echoes them.
	private static String topLevelValues(LispEvaluator evaluator, String input) {
		return evaluator.evalValues(LispReader.readFromString(input))
			.stream()
			.map(LispVal::print)
			.collect(joining(" "));
	}

	@Test
	void evalValuesAtTopLevelYieldsEveryValue() {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		// The syntactic producers: every value, in order.
		assertThat(topLevelValues(evaluator, "(floor 10 3)")).isEqualTo("3 1");
		assertThat(topLevelValues(evaluator, "(truncate -7 2)")).isEqualTo("-3 -1");
		assertThat(topLevelValues(evaluator, "(values 1 2 3)")).isEqualTo("1 2 3");
		// (values) yields NO value at all -- the REPL echoes nothing.
		assertThat(topLevelValues(evaluator, "(values)")).isEmpty();
		assertThat(topLevelValues(evaluator, "(values-list '(1 2))")).isEqualTo("1 2");
		assertThat(topLevelValues(evaluator, "(parse-integer \"12abc\" :junk-allowed t)")).isEqualTo("12 2");
		// A single-value form keeps yielding exactly one value.
		assertThat(topLevelValues(evaluator, "(+ 1 2)")).isEqualTo("3");
		assertThat(topLevelValues(evaluator, "'(1 2)")).isEqualTo("(1 2)");
		assertThat(topLevelValues(evaluator, "(defun tlv-f () (values 1 2))")).isEqualTo("TLV-F");
		// A user function's tail (values ...) crosses the call boundary.
		assertThat(topLevelValues(evaluator, "(tlv-f)")).isEqualTo("1 2");
		assertThat(topLevelValues(evaluator, "(funcall #'tlv-f)")).isEqualTo("1 2");
		// gethash's present-p.
		evaluator.evalValues(LispReader.readFromString("(setq tlv-h (make-hash-table))"));
		evaluator.evalValues(LispReader.readFromString("(setf (gethash 'x tlv-h) 42)"));
		assertThat(topLevelValues(evaluator, "(gethash 'x tlv-h)")).isEqualTo("42 T");
		assertThat(topLevelValues(evaluator, "(gethash 'z tlv-h)")).isEqualTo("NIL NIL");
		// A syntactic producer in a user function's tail crosses the call boundary
		// too (the spill rewrite of evalDefun), so the echo matches SBCL's.
		evaluator.evalValues(LispReader.readFromString("(defun tlv-g () (floor 10 3))"));
		assertThat(topLevelValues(evaluator, "(tlv-g)")).isEqualTo("3 1");
		evaluator.evalValues(LispReader.readFromString("(defun tlv-h-get () (gethash 'x tlv-h))"));
		assertThat(topLevelValues(evaluator, "(tlv-h-get)")).isEqualTo("42 T");
	}

	@Test
	void evalValuesAtTopLevelIgnoresValuesConsumedInsideTheForm() {
		// A callee that CONSUMES another function's values returns a single value: the
		// consumer clears the spill channel once it has snapshotted it, so the
		// consumed extra values do not surface as the caller's (here: the REPL's).
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.eval(LispReader.readFromString("(defun tlv-inner () (values 7 8))"));
		evaluator
			.eval(LispReader.readFromString("(defun tlv-outer () (multiple-value-bind (a b) (tlv-inner) (+ a b)))"));
		assertThat(topLevelValues(evaluator, "(tlv-outer)")).isEqualTo("15");
		assertThat(topLevelValues(evaluator, "(list (tlv-inner) (tlv-outer))")).isEqualTo("(7 15)");
	}

	@Test
	void evalMultipleValueConsumerClearsTheSpillChannel() {
		// Pins the clear-after-snapshot half of the %mv-spill protocol.
		assertThat(evalMulti(
				"(defun mvs-f () (values 1 2))" + " (progn (multiple-value-bind (a b) (mvs-f) (list a b)) %mv-spill)"))
			.isSameAs(LispNil.INSTANCE);
		assertThat(evalMulti("(defun mvs-g () (values 1 2))" + " (progn (multiple-value-list (mvs-g)) %mv-spill)"))
			.isSameAs(LispNil.INSTANCE);
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

	/**
	 * The syntactic producer definitions of
	 * {@link #evalSyntacticMvProducerTailPublishesThroughAFunctionReturn()}, shared
	 * verbatim with the compile backends' copies (JvmLispCompilerTest /
	 * WasmLispCompilerIntegrationTest) and the {@code mv-producer-function-return}
	 * ci-spec case.
	 */
	static final String MV_PRODUCER_TAIL_DEFS = """
			(defun f-gethash (h) (gethash "K" h))
			(defun f-floor (a b) (floor a b))
			(defun f-find (n) (find-symbol n))
			(defun f-intern (n) (intern n))
			(defun f-disp (a) (array-displacement a))
			(defmethod ctx-get ((key string) (context hash-table))
			  (gethash (string-upcase key) context))
			(defun f-cond (h k) (cond (k (gethash "K" h)) (t nil)))
			(setq mv427-tbl (make-hash-table :test 'equal))
			(setf (gethash "K" mv427-tbl) "V")
			""";

	@Test
	void evalSyntacticMvProducerTailPublishesThroughAFunctionReturn() {
		// A syntactic multiple-value producer (gethash / the floor family /
		// find-symbol / intern / array-displacement) in tail position of a function
		// body publishes its secondary value through %mv-spill, so the extra value
		// survives an arbitrary call chain -- including a defmethod, the shape that
		// found this (cl-mustache's context-get IS a gethash).
		assertThat(evalMulti(MV_PRODUCER_TAIL_DEFS + " (multiple-value-list (f-gethash mv427-tbl))").print())
			.isEqualTo("(\"V\" T)");
		// A stored nil is still distinguished from a missing key through the return.
		assertThat(evalMulti(MV_PRODUCER_TAIL_DEFS + " (multiple-value-list (f-gethash (make-hash-table)))").print())
			.isEqualTo("(NIL NIL)");
		assertThat(evalMulti(MV_PRODUCER_TAIL_DEFS + " (multiple-value-list (f-floor 7 2))").print())
			.isEqualTo("(3 1)");
		assertThat(evalMulti(MV_PRODUCER_TAIL_DEFS + " (multiple-value-list (f-find \"CAR\"))").print())
			.isEqualTo("(CAR :INHERITED)");
		assertThat(evalMulti(MV_PRODUCER_TAIL_DEFS + " (multiple-value-list (f-intern \"CAR\"))").print())
			.isEqualTo("(CAR :INHERITED)");
		assertThat(evalMulti(MV_PRODUCER_TAIL_DEFS + " (multiple-value-list (f-disp (make-array 3)))").print())
			.isEqualTo("(NIL 0)");
		assertThat(evalMulti(MV_PRODUCER_TAIL_DEFS + " (multiple-value-list (ctx-get \"k\" mv427-tbl))").print())
			.isEqualTo("(\"V\" T)");
		// A producer under a tail cond clause escapes too; the untaken branch is a
		// single value.
		assertThat(evalMulti(MV_PRODUCER_TAIL_DEFS + " (multiple-value-list (f-cond mv427-tbl t))").print())
			.isEqualTo("(\"V\" T)");
		assertThat(evalMulti(MV_PRODUCER_TAIL_DEFS + " (multiple-value-list (f-cond mv427-tbl nil))").print())
			.isEqualTo("(NIL)");
		// multiple-value-bind through the same indirection: the shape the todo names.
		assertThat(
				evalMulti(MV_PRODUCER_TAIL_DEFS + " (multiple-value-bind (v f) (ctx-get \"k\" mv427-tbl) (list v f))")
					.print())
			.isEqualTo("(\"V\" T)");
		// A NON-tail producer stays single-valued: the caller's consumer reads nil.
		assertThat(evalMulti(MV_PRODUCER_TAIL_DEFS + " (defun f-nontail (h) (let ((v (gethash \"K\" h))) v))"
				+ " (multiple-value-list (f-nontail mv427-tbl))")
			.print()).isEqualTo("(\"V\")");
	}

	/**
	 * The cleanup-shape x exit-shape matrix of
	 * {@link #evalUnwindProtectCleanupKeepsTheProtectedFormsValues()}, shared verbatim
	 * with the compile backends' copies (JvmLispCompilerTest /
	 * WasmLispCompilerIntegration Test) and the {@code unwind-protect-values} ci-spec
	 * case.
	 */
	private static final String UNWIND_PROTECT_VALUES_DEFS = """
			(setq uwp-log nil)
			(defun uwp-zero () (values))
			(defun uwp-one () (values 7))
			(defun uwp-two () (values 7 8))
			(defun uwp-release () (setq uwp-log (cons 'released uwp-log)) (values))
			(defun uwp-compute () (values 1 2 3))
			(defun uwp-nil () (unwind-protect (values 1 2 3) nil))
			(defun uwp-v0 () (unwind-protect (values 1 2 3) (uwp-zero)))
			(defun uwp-v1 () (unwind-protect (values 1 2 3) (uwp-one)))
			(defun uwp-v2 () (unwind-protect (values 1 2 3) (values 7 8)))
			(defun uwp-call () (unwind-protect (uwp-compute) (uwp-release)))
			(defun uwp-nested ()
			  (unwind-protect (values 1 2 3) (unwind-protect (uwp-two) (uwp-zero))))
			(defun uwp-return ()
			  (block b (unwind-protect (return-from b (values 1 2 3)) (uwp-two))))
			(defun uwp-return-call ()
			  (block b (unwind-protect (return-from b (uwp-compute)) (uwp-release))))
			(defun uwp-go ()
			  (let ((r 0))
			    (block b (tagbody (unwind-protect (go done) (uwp-two)) done))
			    (values r 2 3)))
			(defun uwp-signal ()
			  (handler-case (unwind-protect (error "boom") (uwp-release))
			    (error (e) (values 1 2 3))))
			(defun uwp-signal-plain ()
			  (handler-case (unwind-protect (error "boom") (uwp-two)) (error (e) 'caught)))
			""";

	@Test
	void evalUnwindProtectCleanupKeepsTheProtectedFormsValues() {
		// A cleanup runs for effect: its values -- and its value COUNT -- are discarded,
		// so the whole form answers the protected form's values, all of them
		// (.kb/multiple-values.md). Every cleanup shape below used to truncate the
		// protected (values 1 2 3) to whatever it left on the %mv-spill channel: (1) for
		// a cleanup returning zero or one values, (1 8) for one returning two.
		for (String call : List.of("(uwp-nil)", "(uwp-v0)", "(uwp-v1)", "(uwp-v2)", "(uwp-call)", "(uwp-nested)",
				// exit shapes: the values of a return-from are already in flight when the
				// cleanup runs
				"(uwp-return)", "(uwp-return-call)",
				// a signalled unwind: the handler's own values, not the cleanup's
				"(uwp-signal)",
				// and inline, without a function boundary in between
				"(unwind-protect (values 1 2 3) (uwp-zero))")) {
			assertThat(evalMulti(UNWIND_PROTECT_VALUES_DEFS + "(multiple-value-list " + call + ")").print()).as(call)
				.isEqualTo("(1 2 3)");
		}
		// A go exit runs the cleanups too, and leaves nothing of theirs behind.
		assertThat(evalMulti(UNWIND_PROTECT_VALUES_DEFS + "(multiple-value-list (uwp-go))").print())
			.isEqualTo("(0 2 3)");
		// A single-valued form stays single-valued across a two-valued cleanup.
		assertThat(evalMulti(UNWIND_PROTECT_VALUES_DEFS + "(multiple-value-list (uwp-signal-plain))").print())
			.isEqualTo("(CAUGHT)");
		// The cleanups still ran -- once per exit path, no more.
		assertThat(evalMulti(UNWIND_PROTECT_VALUES_DEFS + "(uwp-call) (uwp-return-call) (uwp-signal) uwp-log").print())
			.isEqualTo("(RELEASED RELEASED RELEASED)");
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
	void evalEverySomeOverMultipleSequences() {
		// CL's (every predicate &rest sequences): one argument per sequence, walk stops
		// at the SHORTEST. alexandria-2:dim-in-bounds-p is the two-sequence caller.
		assertThat(eval("(every #'< '(1 2) '(3 4))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(every #'< '(1 2) '(3 0))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(every #'< '(1 2 3) '(9 9))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(every (lambda (a b c) (= (+ a b) c)) '(1 2) '(3 4) '(4 6))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(every #'char= \"abc\" \"abd\")")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(some #'> '(1 5) '(3 4))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(some #'> '(1 2) '(3 4))")).isSameAs(LispNil.INSTANCE);
		assertThat(eval("(notany #'> '(1 2) '(3 4))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(notevery #'< '(1 2) '(3 0))")).isEqualTo(LispTrue.INSTANCE);
		// The value path forwards the extra sequences too (a fixed-arity wrapper cannot).
		assertThat(evalMulti("(funcall #'every #'< '(1 2) '(3 4))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(funcall #'some #'> '(1 5) '(3 4))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(apply #'every (list #'< '(1 2) '(3 4)))")).isEqualTo(LispTrue.INSTANCE);
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
	void evalRemoveIfWithKey() {
		// The predicate sees the keyed value, the kept elements are the originals
		// (trivia level2's find-effective-slot filters slot metaobjects by
		// :key #'slot-definition-initargs).
		assertThat(eval("(remove-if #'evenp '((1 a) (2 b) (3 c)) :key #'car)").print()).isEqualTo("((1 A) (3 C))");
		assertThat(eval("(remove-if-not #'evenp '((1 a) (2 b) (3 c)) :key #'car)").print()).isEqualTo("((2 B))");
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
	void evalSubstituteIf() {
		assertThat(eval("(substitute-if 0 #'oddp '(1 2 3 4 5))").print()).isEqualTo("(0 2 0 4 0)");
		assertThat(eval("(substitute-if-not 0 #'oddp '(1 2 3 4 5))").print()).isEqualTo("(1 0 3 0 5)");
		// :key selects what the predicate sees; the REPLACED value is still the element.
		assertThat(eval("(substitute-if 0 #'oddp '((1) (2) (3)) :key #'car)").print()).isEqualTo("(0 (2) 0)");
		// A string sequence rebuilds as a string -- lack/util:find-middleware's call.
		assertThat(eval("(substitute-if #\\- (lambda (c) (member c '(#\\. #\\/) :test 'char=)) \"lack/mw.backtrace\")")
			.print()).isEqualTo("\"lack-mw-backtrace\"");
		assertThat(eval("(nsubstitute-if 0 #'oddp (list 1 2 3))").print()).isEqualTo("(0 2 0)");
		assertThat(eval("(nsubstitute-if-not 0 #'oddp (list 1 2 3))").print()).isEqualTo("(1 0 3)");
		assertThat(evalMulti("(funcall #'substitute-if 0 #'oddp '(1 2 3))").print()).isEqualTo("(0 2 0)");
		// The -if family takes :key only; the predicate IS the test.
		assertThatThrownBy(() -> eval("(substitute-if 0 #'oddp '(1) :test #'eql)"))
			.hasMessageContaining("expects keyword arguments :KEY, got: :TEST");
	}

	@Test
	void evalSleepParksAndReturnsNil() {
		assertThat(eval("(sleep 0)")).isEqualTo(LispNil.INSTANCE);
		long before = System.currentTimeMillis();
		assertThat(eval("(sleep 0.05)")).isEqualTo(LispNil.INSTANCE);
		assertThat(System.currentTimeMillis() - before).isGreaterThanOrEqualTo(40L);
		assertThat(evalMulti("(funcall #'sleep 0)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalFileWriteDateAndFileLength(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("wd.txt");
		Files.writeString(file, "hello\n");
		String path = file.toString().replace("\\", "\\\\");
		// A universal time is seconds since 1900, so it is well past the 1970 offset.
		LispVal date = eval("(file-write-date \"" + path + "\")");
		assertThat(date).isInstanceOf(LispInteger.class);
		assertThat(((LispInteger) date).value()).isGreaterThan(2208988800L);
		// Common Lisp's "cannot be determined" answer, not a signal.
		assertThat(eval("(file-write-date \"" + path + ".missing\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(evalMulti("(with-open-file (in \"" + path + "\") (file-length in))")).isEqualTo(new LispInteger(6));
		// Only an OPEN file stream has a file behind it; a closed handle, a string stream
		// and a non-stream all answer nil.
		assertThat(evalMulti("(setq h (open \"" + path + "\")) (close h) (file-length h)")).isEqualTo(LispNil.INSTANCE);
		assertThat(evalMulti("(with-output-to-string (s) (file-length s))").print()).isEqualTo("\"\"");
		assertThat(eval("(file-length \"not-a-stream\")")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalEnsureDirectoriesExist(@TempDir Path tempDir) {
		String base = tempDir.toString().replace("\\", "\\\\");
		// The DIRECTORY component is everything up to the last slash, so the file name
		// itself is not created.
		assertThat(eval("(ensure-directories-exist \"" + base + "/a/b/app.log\")").print())
			.isEqualTo("\"" + base + "/a/b/app.log\"");
		assertThat(Files.isDirectory(tempDir.resolve("a/b"))).isTrue();
		assertThat(Files.exists(tempDir.resolve("a/b/app.log"))).isFalse();
		// A namestring that already ends in a slash IS the directory.
		assertThat(eval("(ensure-directories-exist \"" + base + "/c/d/\")").print()).isEqualTo("\"" + base + "/c/d/\"");
		assertThat(Files.isDirectory(tempDir.resolve("c/d"))).isTrue();
		// No slash at all: a file in the working directory, so nothing is created.
		assertThat(eval("(ensure-directories-exist \"plain.txt\")").print()).isEqualTo("\"plain.txt\"");
	}

	@Test
	void evalExportMakesASymbolExternal() {
		// export before the definitions; the mirror case (export after them) is
		// evalExportAfterTheDefinitionsPublishesThemUnderTheSingleColon below.
		assertThat(evalMulti("""
				(defpackage :expkg (:use :cl))
				(in-package :expkg)
				(export '(run))
				(defun run () 42)
				(in-package :cl-user)
				(expkg:run)
				""")).isEqualTo(new LispInteger(42));
	}

	@Test
	void evalExportAfterTheDefinitionsPublishesThemUnderTheSingleColon() {
		// export grants ACCESSIBILITY and never re-keys the symbol, so the function, the
		// value and the setf-function cells a defun/defvar filled BEFORE the export are
		// all reachable through the external spelling afterwards.
		assertThat(evalMulti("""
				(defpackage :latepkg (:use :cl))
				(defun latepkg::my-fn (x) (* x 2))
				(defvar latepkg::*v* 7)
				(defun (setf latepkg::slot) (v c) (rplaca c v) v)
				(export '(latepkg::my-fn latepkg::*v* latepkg::slot) :latepkg)
				(let ((c (list 1 2)))
				  (setf (latepkg:slot c) 9)
				  (list (latepkg:my-fn 21) latepkg:*v* (car c)))
				""").print()).isEqualTo("(42 7 9)");
	}

	@Test
	void evalUnexportMakesASymbolInternalAgain() {
		assertThat(evalMulti("""
				(defpackage :unexpkg (:use :cl) (:export #:a #:b))
				(in-package :unexpkg)
				(unexport 'b)
				(defun a () 1)
				(defun b () 2)
				(in-package :cl-user)
				(+ (unexpkg:a) (unexpkg::b))
				""")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalImportMakesASymbolAccessibleUnqualified() {
		// The runtime spelling of defpackage's :import-from clause: a literal top-level
		// call is consumed by the resolver, so the forms after it see the redirect.
		assertThat(evalMulti("""
				(defpackage :impkg (:use :cl) (:export #:pub))
				(in-package :impkg)
				(defun pub () 1)
				(defun priv () 2)
				(in-package :cl-user)
				(import 'impkg:pub)
				(import '(impkg::priv))
				(+ (pub) (priv))
				""")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalPackageRegistryQueriesReadTheLiveRegistry() {
		assertThat(evalMulti("""
				(defpackage :pql-a (:use :cl) (:export #:hi))
				(defpackage :pql-b (:use :cl :pql-a))
				(list (package-use-list :pql-b)
				      (package-used-by-list :pql-a)
				      (package-use-list :cl)
				      (package-shadowing-symbols :cl-user)
				      (car (member :pql-a (list-all-packages))))
				""").print()).isEqualTo("((:CL :PQL-A) (:PQL-B) NIL NIL :PQL-A)");
		// A designator is anything find-package accepts, and the answer is the same
		// keyword shape find-package returns.
		assertThat(evalMulti("(package-use-list (find-package \"CL-USER\"))").print()).isEqualTo("(:CL)");
		assertThat(evalMulti("(mapcar #'package-name (package-use-list :cl-user))").print()).isEqualTo("(\"CL\")");
		assertThatThrownBy(() -> evalMulti("(package-use-list :no-such-package)"))
			.hasMessageContaining("no package named");
	}

	@Test
	void rempropDropsOnePropertyFromThePlist() {
		assertThat(evalMulti("""
				(setf (get 'rp 'a) 1)
				(setf (get 'rp 'b) 2)
				(setf (get 'rp 'c) 3)
				(list (remprop 'rp 'b) (symbol-plist 'rp) (remprop 'rp 'zz)
				      (remprop 'rp 'c) (symbol-plist 'rp) (get 'rp 'a))
				""").print()).isEqualTo("(T (C 3 A 1) NIL T (A 1) 1)");
		// The head of the list, and a symbol with no plist at all.
		assertThat(evalMulti("(setf (get 'rp2 'only) 9) (list (remprop 'rp2 'only) (symbol-plist 'rp2))").print())
			.isEqualTo("(T NIL)");
		assertThat(evalMulti("(remprop 'rp3 'nothing)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void evalLoadContextSpecialsAreLetBindable() {
		// clack's %load-file binds all four around its read/eval loop.
		assertThat(evalMulti("""
				(let ((*package* *package*)
				      (*readtable* *readtable*)
				      (*load-pathname* "p")
				      (*load-truename* "t"))
				  (list *load-pathname* *load-truename* *readtable*))
				""").print()).isEqualTo("(\"p\" \"t\" NIL)");
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
	void readerErrorInARuntimeLoadNamesTheLoadedFile(@TempDir Path tempDir) throws Exception {
		// The interpreter keeps its own runtime load (no LoadInliner), so it has to pass
		// the origin file to the reader itself: without it a stray token in a loaded
		// library reports a bare message, while the compile path names file:line:column.
		Path dir = Files.createDirectories(tempDir.resolve("proj"));
		Files.writeString(dir.resolve("core.lisp"), "(defun f (x)\n  #\\Nope)\n");
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.setLoadBaseDir(dir.toString());
		assertThatThrownBy(() -> evaluator.eval(LispReader.readFromString("(load \"core.lisp\")")))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("core.lisp:2:3: ")
			.hasMessageContaining("Unknown character name: #\\Nope");
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
		// :if-exists is accepted with the value the native behavior already implements
		// (:supersede) and with :append, which is real (.kb/read-load-streams.md);
		// anything else must not be silently reinterpreted. It signals at CALL time as
		// a Lisp condition (was an expansion-time throw): the eager compile paths
		// expand every branch of a spliced library.
		assertThatThrownBy(() -> eval("(with-open-file (s \"" + file + "\" :if-exists :rename) s)"))
			.isInstanceOf(LispEvalException.class)
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
	void probeFileAnswersThePathOrNil(@TempDir Path tempDir) throws Exception {
		Path present = tempDir.resolve("there.txt");
		Files.writeString(present, "x\n");
		String there = present.toString().replace("\\", "\\\\");
		String missing = tempDir.resolve("nope.txt").toString().replace("\\", "\\\\");
		assertThat(eval("(probe-file \"" + there + "\")").print()).isEqualTo("#P\"" + present + "\"");
		assertThat(eval("(probe-file \"" + missing + "\")")).isEqualTo(LispNil.INSTANCE);
		// The whole point of the primitive: a missing path answers, it does not signal.
		assertThat(eval("(if (probe-file \"" + missing + "\") 1 2)")).isEqualTo(new LispInteger(2));
	}

	@Test
	void probeFileSeesADirectoryAndABinaryFile(@TempDir Path tempDir) throws Exception {
		// A file that is not decodable text still exists -- the SourceLoader-mediated
		// probe must not degrade to "can I read this as a string?".
		Path binary = tempDir.resolve("blob.bin");
		Files.write(binary, new byte[] { (byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0x80 });
		assertThat(eval("(probe-file \"" + binary.toString().replace("\\", "\\\\") + "\")").print())
			.isEqualTo("#P\"" + binary + "\"");
		assertThat(eval("(probe-file \"" + tempDir.toString().replace("\\", "\\\\") + "\")").print())
			.isEqualTo("#P\"" + tempDir + "\"");
	}

	@Test
	void probeFileIsFirstClassAndDrivesUiopFileExistsP(@TempDir Path tempDir) throws Exception {
		Path present = tempDir.resolve("f.txt");
		Files.writeString(present, "x\n");
		String there = present.toString().replace("\\", "\\\\");
		String missing = tempDir.resolve("g.txt").toString().replace("\\", "\\\\");
		assertThat(eval("(mapcar #'probe-file (list \"" + there + "\" \"" + missing + "\"))").print())
			.isEqualTo("(#P\"" + present + "\" NIL)");
		assertThat(eval("(uiop:file-exists-p \"" + there + "\")").print()).isEqualTo("#P\"" + present + "\"");
		assertThat(eval("(uiop:file-exists-p \"" + missing + "\")")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void probeFileGoesThroughTheInstalledSourceLoader() {
		// A host with no filesystem (the browser playground) answers from its own
		// in-memory map, and never from java.nio.file.Files.
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.setSourceLoader(path -> {
			if ("mem.lisp".equals(path)) {
				return "(defun f () 1)";
			}
			throw new java.io.FileNotFoundException(path);
		});
		assertThat(evaluator.eval(LispReader.readFromString("(probe-file \"mem.lisp\")")).print())
			.isEqualTo("#P\"mem.lisp\"");
		assertThat(evaluator.eval(LispReader.readFromString("(probe-file \"/etc/hosts\")")))
			.isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void directoryMatchesPathnamesTheWayAnsiDoes(@TempDir Path tempDir) throws Exception {
		// Every expectation here was checked against SBCL on the same tree: `directory`
		// MATCHES a pathspec, it does not simply list a directory. A wild name component
		// with no type ("*") therefore matches only untyped entries, which is why the
		// two subdirectories come back and the two .txt files do not.
		Files.writeString(tempDir.resolve("b.txt"), "b\n");
		Files.writeString(tempDir.resolve("a.txt"), "a\n");
		Files.createDirectory(tempDir.resolve("sub"));
		Files.createDirectory(tempDir.resolve("empty"));
		String dir = tempDir.toString().replace("\\", "\\\\");
		assertThat(eval("(directory \"" + dir + "/*.*\")").print()).isEqualTo("(#P\"" + tempDir + "/a.txt\" #P\""
				+ tempDir + "/b.txt\" #P\"" + tempDir + "/empty/\" #P\"" + tempDir + "/sub/\")");
		assertThat(eval("(directory \"" + dir + "/*\")").print())
			.isEqualTo("(#P\"" + tempDir + "/empty/\" #P\"" + tempDir + "/sub/\")");
		assertThat(eval("(directory \"" + dir + "/*.txt\")").print())
			.isEqualTo("(#P\"" + tempDir + "/a.txt\" #P\"" + tempDir + "/b.txt\")");
		assertThat(eval("(directory \"" + dir + "/?.txt\")").print())
			.isEqualTo("(#P\"" + tempDir + "/a.txt\" #P\"" + tempDir + "/b.txt\")");
		assertThat(eval("(directory \"" + dir + "/a*\")")).isEqualTo(LispNil.INSTANCE);
		// A non-wild pathspec designates ITSELF: a directory in directory form, a file
		// as given, and nothing at all when it does not exist.
		assertThat(eval("(directory \"" + dir + "/\")").print()).isEqualTo("(#P\"" + tempDir + "/\")");
		assertThat(eval("(directory \"" + dir + "\")").print()).isEqualTo("(#P\"" + tempDir + "/\")");
		assertThat(eval("(directory \"" + dir + "/a.txt\")").print()).isEqualTo("(#P\"" + tempDir + "/a.txt\")");
		assertThat(eval("(directory \"" + dir + "/nope.txt\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(directory \"" + dir + "/empty/*.*\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(directory \"" + dir + "/nope/*.*\")")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void uiopDirectoryWalkersRunOverTheSamePrimitive(@TempDir Path tempDir) throws Exception {
		Files.writeString(tempDir.resolve("a.txt"), "a\n");
		Files.createDirectory(tempDir.resolve("sub"));
		Files.writeString(tempDir.resolve("sub/c.txt"), "c\n");
		Files.createDirectory(tempDir.resolve("sub/deep"));
		String dir = tempDir.toString().replace("\\", "\\\\");
		assertThat(eval("(uiop:directory-exists-p \"" + dir + "\")").print()).isEqualTo("#P\"" + tempDir + "/\"");
		assertThat(eval("(uiop:directory-exists-p \"" + dir + "/a.txt\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(uiop:directory-exists-p \"" + dir + "/nope\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(uiop:directory-files \"" + dir + "\")").print()).isEqualTo("(#P\"" + tempDir + "/a.txt\")");
		assertThat(eval("(uiop:subdirectories \"" + dir + "\")").print()).isEqualTo("(#P\"" + tempDir + "/sub/\")");
		assertThat(evalMulti("""
				(let ((acc nil))
				  (uiop:collect-sub*directories "%s" (constantly t) (constantly t)
				                                (lambda (d) (setq acc (cons d acc))))
				  (reverse acc))
				""".formatted(dir)).print())
			.isEqualTo("(#P\"" + tempDir + "/\" #P\"" + tempDir + "/sub/\" #P\"" + tempDir + "/sub/deep/\")");
	}

	@Test
	void pathnameIsADistinctValue() {
		// A pathname is an instance carrying its namestring, not the namestring itself:
		// the predicates, the reader literal and equality all checked
		// against SBCL 2.6.5.
		assertThat(eval("(list (pathnamep \"x\") (pathnamep #P\"x\") (pathnamep 42) (pathnamep nil))").print())
			.isEqualTo("(NIL T NIL NIL)");
		assertThat(eval("(list (typep #P\"x\" 'pathname) (typep \"x\" 'pathname) (typep #P\"x\" 'structure-object)"
				+ " (typep #P\"x\" 'standard-object) (typep #P\"x\" 'atom))")
			.print()).isEqualTo("(T NIL NIL NIL T)");
		// prin1 prints #P with the namestring escaped; princ prints the bare
		// namestring, nested elements included (CLHS 22.1.3.11).
		assertThat(eval("(prin1-to-string #P\"d/a.txt\")")).isEqualTo(new LispString("#P\"d/a.txt\""));
		assertThat(eval("(princ-to-string #P\"d/a.txt\")")).isEqualTo(new LispString("d/a.txt"));
		assertThat(eval("(prin1-to-string (list #P\"a b\"))")).isEqualTo(new LispString("(#P\"a b\")"));
		assertThat(eval("(princ-to-string (list #P\"a b\"))")).isEqualTo(new LispString("(a b)"));
		assertThat(eval("(format nil \"~A |~S\" #P\"f o\" #P\"f o\")")).isEqualTo(new LispString("f o |#P\"f o\""));
		// equal compares the namestring; a pathname never equals its namestring.
		assertThat(eval("(list (equal #P\"a\" #P\"a\") (equal #P\"a\" \"a\") (equal #P\"a\" #P\"b\")"
				+ " (equalp #P\"a\" #P\"a\"))")
			.print()).isEqualTo("(T NIL NIL T)");
		assertThat(eval("(type-of #P\"x\")").print()).isEqualTo("PATHNAME");
	}

	@Test
	void pathnameProducersAnswerPathnamesAndConsumersTakeBoth() {
		// The producers answer pathname values, namestring unwraps, and every
		// path-taking operator accepts both spellings; expectations
		// SBCL-checked except where a rontolisp namestring stays relative.
		assertThat(eval("(namestring #P\"d/a.txt\")")).isEqualTo(new LispString("d/a.txt"));
		assertThat(eval("(namestring \"d/a.txt\")")).isEqualTo(new LispString("d/a.txt"));
		assertThat(eval("(pathname \"d/x\")").print()).isEqualTo("#P\"d/x\"");
		assertThat(eval("(pathname #P\"d/x\")").print()).isEqualTo("#P\"d/x\"");
		assertThat(eval("(multiple-value-list (parse-namestring \"d/a.txt\"))").print()).isEqualTo("(#P\"d/a.txt\" 7)");
		assertThat(eval("(merge-pathnames \"b.txt\" \"d/a.sql\")").print()).isEqualTo("#P\"d/b.txt\"");
		assertThat(eval("(merge-pathnames #P\"b.txt\" #P\"d/a.sql\")").print()).isEqualTo("#P\"d/b.txt\"");
		assertThat(eval("(make-pathname :name \"x\" :defaults #P\"d/a.txt\")").print()).isEqualTo("#P\"d/x.txt\"");
		// The component readers take both designator spellings.
		assertThat(eval("(list (pathname-name #P\"d/a.b.c\") (pathname-type #P\"d/a.b.c\")"
				+ " (pathname-directory #P\"a/b/c.txt\"))")
			.print()).isEqualTo("(\"a.b\" \"c\" (:RELATIVE \"a\" \"b\"))");
		assertThat(eval("(uiop:native-namestring #P\"d/a.txt\")")).isEqualTo(new LispString("d/a.txt"));
	}

	@Test
	void pathnameFileOperatorsRoundTripOnTheFilesystem(@TempDir Path tempDir) throws Exception {
		Files.writeString(tempDir.resolve("a.txt"), "hi\n");
		String dir = tempDir.toString().replace("\\", "\\\\");
		// probe-file answers a PATHNAME (the truename is the argument namestring);
		// truename unwraps through it; a missing file stays nil.
		assertThat(eval("(probe-file \"" + dir + "/a.txt\")").print()).isEqualTo("#P\"" + tempDir + "/a.txt\"");
		assertThat(eval("(probe-file #P\"" + dir + "/a.txt\")").print()).isEqualTo("#P\"" + tempDir + "/a.txt\"");
		assertThat(eval("(probe-file \"" + dir + "/nope\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(truename #P\"" + dir + "/a.txt\")").print()).isEqualTo("#P\"" + tempDir + "/a.txt\"");
		// open / with-open-file / load / delete-file / ensure-directories-exist /
		// file-write-date take a pathname argument.
		assertThat(eval("(with-open-file (in #P\"" + dir + "/a.txt\") (read-line in))"))
			.isEqualTo(new LispString("hi"));
		assertThat(eval("(integerp (file-write-date #P\"" + dir + "/a.txt\"))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(ensure-directories-exist #P\"" + dir + "/made/f.txt\")").print())
			.isEqualTo("#P\"" + tempDir + "/made/f.txt\"");
		assertThat(Files.isDirectory(tempDir.resolve("made"))).isTrue();
		assertThat(eval("(delete-file #P\"" + dir + "/a.txt\")")).isEqualTo(LispTrue.INSTANCE);
		assertThat(Files.exists(tempDir.resolve("a.txt"))).isFalse();
	}

	@Test
	void directoryFamilyAnswersPathnames(@TempDir Path tempDir) throws Exception {
		Files.writeString(tempDir.resolve("a.txt"), "a\n");
		Files.createDirectory(tempDir.resolve("sub"));
		String dir = tempDir.toString().replace("\\", "\\\\");
		assertThat(eval("(directory \"" + dir + "/*.*\")").print())
			.isEqualTo("(#P\"" + tempDir + "/a.txt\" #P\"" + tempDir + "/sub/\")");
		assertThat(eval("(directory #P\"" + dir + "/*.txt\")").print()).isEqualTo("(#P\"" + tempDir + "/a.txt\")");
		assertThat(eval("(uiop:directory-files #P\"" + dir + "\")").print()).isEqualTo("(#P\"" + tempDir + "/a.txt\")");
		assertThat(eval("(uiop:subdirectories #P\"" + dir + "\")").print()).isEqualTo("(#P\"" + tempDir + "/sub/\")");
		assertThat(eval("(uiop:directory-exists-p #P\"" + dir + "\")").print()).isEqualTo("#P\"" + tempDir + "/\"");
		assertThat(eval("(mapcar #'pathnamep (directory \"" + dir + "/*.*\"))").print()).isEqualTo("(T T)");
	}

	@Test
	void pathnameComponentsRontolispDoesNotModelAnswerNil() {
		// host / device / version are components a flat namestring does not carry, so
		// the accessors answer nil -- what CL prescribes for an absent component, and
		// what SBCL answers on Unix for :device. They still validate the designator.
		assertThat(eval("(list (pathname-host \"d/a.txt\") (pathname-device #P\"d/a.txt\")"
				+ " (pathname-version #P\"d/a.txt\"))")
			.print()).isEqualTo("(NIL NIL NIL)");
		assertThatThrownBy(() -> eval("(pathname-device 42)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("not a pathname designator");
	}

	@Test
	void wildPathnamePAnswersPerComponent() {
		// The field keys, checked against SBCL 2.2.9 for truth/falsehood (SBCL answers
		// the wild component itself where this answers T; both are generalized
		// booleans).
		assertThat(eval("(list (wild-pathname-p \"d/*.txt\") (wild-pathname-p \"d/a.txt\")"
				+ " (wild-pathname-p \"d/a?.txt\") (wild-pathname-p #P\"*/a.txt\"))")
			.print()).isEqualTo("(T NIL T T)");
		assertThat(eval("(list (wild-pathname-p \"d/*.txt\" :name) (wild-pathname-p \"d/*.txt\" :type)"
				+ " (wild-pathname-p \"d/a.*\" :type) (wild-pathname-p \"*/a.txt\" :directory)"
				+ " (wild-pathname-p \"*/a.txt\" :name) (wild-pathname-p \"d/*.txt\" :host)"
				+ " (wild-pathname-p \"d/*.txt\" :device) (wild-pathname-p \"d/*.txt\" :version))")
			.print()).isEqualTo("(T NIL T T NIL NIL NIL NIL)");
	}

	@Test
	void enoughNamestringDropsTheDefaultsDirectoryPrefix() {
		// The inverse of merge-pathnames: what it prefixes, this drops. All four
		// expectations SBCL-checked.
		assertThat(eval("(enough-namestring \"/a/b/c.lisp\" \"/a/\")")).isEqualTo(new LispString("b/c.lisp"));
		assertThat(eval("(enough-namestring #P\"/a/b/c.lisp\" #P\"/x/\")")).isEqualTo(new LispString("/a/b/c.lisp"));
		assertThat(eval("(merge-pathnames (enough-namestring \"/a/b/c.lisp\" \"/a/\") \"/a/\")").print())
			.isEqualTo("#P\"/a/b/c.lisp\"");
		// *default-pathname-defaults* is the default defaults, and it BINDS.
		assertThat(eval("(namestring *default-pathname-defaults*)")).isEqualTo(new LispString(""));
		assertThat(eval("(pathnamep *default-pathname-defaults*)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(enough-namestring \"/a/b/c.lisp\")")).isEqualTo(new LispString("/a/b/c.lisp"));
		assertThat(eval("(let ((*default-pathname-defaults* #P\"/a/b/\")) (enough-namestring \"/a/b/c.lisp\"))"))
			.isEqualTo(new LispString("c.lisp"));
	}

	@Test
	void namestringHalvesSplitAtTheDirectoryBoundary() {
		// file-namestring and directory-namestring are complements -- they split the
		// namestring exactly where %pathname-split does -- so concatenating them back
		// gives the namestring for every shape. host-namestring is "", the STRING
		// counterpart of pathname-host's nil. Every expectation SBCL-checked.
		assertThat(eval("""
				(list (file-namestring #P"/a/b/c.txt") (directory-namestring #P"/a/b/c.txt")
				      (host-namestring #P"/a/b/c.txt"))
				""").print()).isEqualTo("(\"c.txt\" \"/a/b/\" \"\")");
		// No slash at all: the whole namestring is the file half.
		assertThat(eval("(list (file-namestring \"a.txt\") (directory-namestring \"a.txt\"))").print())
			.isEqualTo("(\"a.txt\" \"\")");
		// A namestring that names a DIRECTORY has no file half.
		assertThat(eval("(list (file-namestring \"/a/b/\") (directory-namestring \"/a/b/\"))").print())
			.isEqualTo("(\"\" \"/a/b/\")");
		// The leading dot belongs to the NAME, the same rule pathname-name follows.
		assertThat(eval("(list (file-namestring \"/a/.bashrc\") (directory-namestring \"/a/.bashrc\"))").print())
			.isEqualTo("(\".bashrc\" \"/a/\")");
		assertThat(eval("""
				(let ((p "d/e/f.g"))
				  (string= (concatenate 'string (directory-namestring p) (file-namestring p)) (namestring p)))
				""")).isEqualTo(LispTrue.INSTANCE);
		// A non-designator signals where the rest of the family signals.
		assertThatThrownBy(() -> eval("(file-namestring 42)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("not a pathname designator");
		assertThatThrownBy(() -> eval("(host-namestring 42)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("not a pathname designator");
	}

	@Test
	void nstringCaseFamilyWritesTheFoldBackIntoTheString() {
		// The destructive spelling of the case family: the fold is the one
		// string-upcase / -downcase / -capitalize performs, and the write goes back into
		// the argument. Every expectation SBCL-checked.
		assertThat(eval("(nstring-upcase (copy-seq \"hello world\"))")).isEqualTo(new LispString("HELLO WORLD"));
		assertThat(eval("(nstring-downcase (copy-seq \"ABC\"))")).isEqualTo(new LispString("abc"));
		assertThat(eval("(nstring-capitalize (copy-seq \"hello world\"))")).isEqualTo(new LispString("Hello World"));
		// It answers the SAME object, and the caller sees the change through its own
		// reference -- what a plain string-upcase under another name could not give.
		assertThat(eval("""
				(let ((s (make-string 3 :initial-element #\\a)))
				  (list (eq s (nstring-upcase s)) s))
				""").print()).isEqualTo("(T \"AAA\")");
		assertThat(eval("(let ((s (copy-seq \"ab\"))) (nstring-upcase s) s)")).isEqualTo(new LispString("AB"));
		// First-class: chunga's make-keyword passes #'nstring-upcase to intern.
		assertThat(eval("(funcall #'nstring-downcase (copy-seq \"ABC\"))")).isEqualTo(new LispString("abc"));
		assertThat(eval("(mapcar #'nstring-upcase (list (copy-seq \"a\") (copy-seq \"b\")))").print())
			.isEqualTo("(\"A\" \"B\")");
	}

	@Test
	void environmentEnquiryFamilyAnswersPerBackendConstants() {
		// CLHS 25.1.5. Every answer is a constant here: the implementation pair names
		// the build, software-type is the one supporting-software claim rontolisp makes
		// everywhere (uiop:operating-system says the same), machine-type is the ABI the
		// artifact targets (uiop:architecture's rule), and everything rontolisp cannot
		// know is nil rather than a fabricated string.
		assertThat(eval("(lisp-implementation-type)")).isEqualTo(new LispString("rontolisp"));
		assertThat(eval("(lisp-implementation-version)"))
			.isEqualTo(new LispString(am.ik.rontolisp.Version.getVersion()));
		assertThat(eval("(equal (lisp-implementation-version) (getf (rontolisp:version) :version))"))
			.isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(list (software-type) (software-version))").print()).isEqualTo("(\"Unix\" NIL)");
		// The interpreter runs on the JVM, so it answers what the JVM backend's emitted
		// class does; both WASM backends answer "WASM32".
		assertThat(eval("(list (machine-type) (machine-version) (machine-instance))").print())
			.isEqualTo("(\"JVM\" NIL NIL)");
		assertThat(eval("(list (short-site-name) (long-site-name))").print()).isEqualTo("(NIL NIL)");
		// A User-Agent built out of the family is the shape dexador composes.
		assertThat(eval("""
				(format nil "dexador/1.0 (~A ~A); ~A; ~A"
				        (lisp-implementation-type) (lisp-implementation-version)
				        (software-type) (machine-type))
				""")).isEqualTo(
				new LispString("dexador/1.0 (rontolisp " + am.ik.rontolisp.Version.getVersion() + "); Unix; JVM"));
	}

	@Test
	void translatePathnameSubstitutesTheCapturedWildcards() {
		// Every expectation checked against SBCL 2.2.9 on the same forms.
		assertThat(eval("(translate-pathname \"d/a.txt\" \"d/*.*\" \"e/*.*\")").print()).isEqualTo("#P\"e/a.txt\"");
		assertThat(eval("(translate-pathname \"src/foo.lisp\" \"src/*.lisp\" \"build/*.fasl\")").print())
			.isEqualTo("#P\"build/foo.fasl\"");
		assertThat(eval("(namestring (translate-pathname \"a/b.c\" \"*/*.*\" \"x/*-y.*\"))"))
			.isEqualTo(new LispString("x/a-y.b"));
		assertThatThrownBy(() -> eval("(translate-pathname \"d/a.txt\" \"e/*.*\" \"f/*.*\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("does not match");
		// Every rontolisp pathname is physical, so the logical translation is the
		// identity and logical-pathname itself can never succeed.
		assertThat(eval("(translate-logical-pathname \"d/a.txt\")").print()).isEqualTo("#P\"d/a.txt\"");
		assertThatThrownBy(() -> eval("(logical-pathname \"SYS:SRC;\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("does not name a logical pathname");
	}

	@Test
	void renameFileMovesTheFileAndSignalsWhenItIsNotThere(@TempDir Path tempDir) throws Exception {
		Files.writeString(tempDir.resolve("a.txt"), "hi\n");
		String dir = tempDir.toString().replace("\\", "\\\\");
		// The new name is MERGED with the old one, so a bare file name stays in the
		// same directory (CL's defaulted-new-name), and the answer is that pathname.
		assertThat(eval("(rename-file \"" + dir + "/a.txt\" \"b.txt\")").print())
			.isEqualTo("#P\"" + tempDir + "/b.txt\"");
		assertThat(Files.exists(tempDir.resolve("a.txt"))).isFalse();
		assertThat(Files.readString(tempDir.resolve("b.txt"))).isEqualTo("hi\n");
		assertThat(eval("(rename-file #P\"" + dir + "/b.txt\" #P\"" + dir + "/c.txt\")").print())
			.isEqualTo("#P\"" + tempDir + "/c.txt\"");
		assertThatThrownBy(() -> eval("(rename-file \"" + dir + "/nope.txt\" \"d.txt\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("cannot rename");
	}

	@Test
	void pathnameDiscriminatesFromStringContentInLackAndJzonShapes() {
		// The two library shapes the old pathnameClauseYields heuristic served, now
		// answered by the type itself: lack's finalize-response cond and jzon's
		// typecase both discriminate a FILE from TEXT.
		assertThat(evalMulti("""
				(defun body-shape (body)
				  (cond ((or (consp body) (pathnamep body) (and (not (stringp body)) (vectorp body)))
				         (list body))
				        (t (list (list body)))))
				(list (body-shape "hello") (body-shape #P"f.txt") (body-shape '("x")))
				""").print()).isEqualTo("(((\"hello\")) (#P\"f.txt\") ((\"x\")))");
		assertThat(eval("(typecase \"text\" (pathname :path) (t :content))").print()).isEqualTo(":CONTENT");
		assertThat(eval("(typecase #P\"f.json\" (pathname :path) (t :content))").print()).isEqualTo(":PATH");
		assertThat(eval("(etypecase #P\"a.sql\" (null :none) (pathname :path))").print()).isEqualTo(":PATH");
		// mito's migrate guard: a pathname passes check-type, a string no longer does.
		assertThat(evalMulti("(defun m (directory) (check-type directory pathname) :ok) (m #P\"db/\")").print())
			.isEqualTo(":OK");
		assertThatThrownBy(() -> evalMulti("(defun m2 (directory) (check-type directory pathname) :ok) (m2 \"db/\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("not of type");
	}

	@Test
	void uiopSymbolCallLooksTheNameUpAtRuntimeAndApplies() {
		// The late-binding call real UIOP offers. Both designator spellings a caller
		// uses (keyword and string) name the same symbol, and the arguments after the
		// name are the call's own.
		assertThat(eval("(uiop:symbol-call :cl :+ 1 2 3)")).isEqualTo(new LispInteger(6));
		assertThat(eval("(uiop:symbol-call \"CL\" \"LIST\" 1 2)").print()).isEqualTo("(1 2)");
		assertThat(evalMulti("""
				(defpackage :sc-demo (:use :cl) (:export :twice))
				(in-package :sc-demo)
				(defun twice (x) (* 2 x))
				(in-package :cl-user)
				(uiop:symbol-call :sc-demo :twice 21)
				""")).isEqualTo(new LispInteger(42));
		// dexador's shape: the operator as a VALUE, over uninterned designators. The
		// interpreter's Java built-in is a first-class function already -- the compile
		// paths get uiop's own definition of it (.todo/404).
		assertThat(evalMulti("""
				(defpackage :sc-uninterned (:use :cl) (:export :thrice))
				(in-package :sc-uninterned)
				(defun thrice (x) (* 3 x))
				(in-package :cl-user)
				(apply #'uiop:symbol-call '#:sc-uninterned '#:thrice (list 14))
				""")).isEqualTo(new LispInteger(42));
	}

	@Test
	void uiopSymbolCallSignalsForAnAbsentPackageOrSymbol() {
		// find-symbol* semantics: the caller is about to apply the result, so an
		// absent name is an error rather than a nil that fails one frame later.
		assertThatThrownBy(() -> eval("(uiop:symbol-call :no-such-package :foo)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("package NO-SUCH-PACKAGE does not exist");
		assertThatThrownBy(() -> eval("(uiop:symbol-call :cl :definitely-not-a-cl-symbol)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("is not present in package");
	}

	@Test
	void printConditionBacktracePrintsTheConditionUnderBothSpellings() {
		// Lite: no backend carries a Lisp-level call stack, so the condition alone is
		// the whole report. uiop:print-condition-backtrace is an IMPORT of the
		// uiop/image symbol, so both spellings must reach the one definition --
		// lack-middleware-backtrace imports the uiop/image one.
		assertThat(evalMulti("""
				(with-output-to-string (s)
				  (handler-case (error "boom")
				    (error (c) (uiop/image:print-condition-backtrace c :stream s))))
				""")).isEqualTo(new LispString("boom\n"));
		assertThat(evalMulti("""
				(with-output-to-string (s)
				  (handler-case (error "boom")
				    (error (c) (uiop:print-condition-backtrace c :stream s))))
				""")).isEqualTo(new LispString("boom\n"));
	}

	@Test
	void directoryGoesThroughTheInstalledSourceLoader() {
		// A host with no filesystem (the browser playground) has no directories, so the
		// whole family answers rather than failing.
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.setSourceLoader(path -> {
			throw new java.io.FileNotFoundException(path);
		});
		assertThat(evaluator.eval(LispReader.readFromString("(directory \"/etc/*.*\")"))).isEqualTo(LispNil.INSTANCE);
		assertThat(evaluator.eval(LispReader.readFromString("(uiop:directory-exists-p \"/etc\")")))
			.isEqualTo(LispNil.INSTANCE);
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
	void evalBinaryStandardStreamsAreByteTransparent() {
		// stdin -> stdout as octets: the bytes below are a NUL, a high byte and a
		// newline plus one UTF-8 lead byte, none of which may be decoded, re-encoded or
		// line-buffered on the way through. The spelling is CL's own -- the standard
		// stream variables hold the t designator, so no handle is involved.
		byte[] octets = { 'h', 'i', 0, (byte) 0xE6, (byte) 0x97, (byte) 0xA5, (byte) 0xFF, '\n', 'z' };
		java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
		PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);
		LispEvaluator evaluator = new LispEvaluator(out, new ByteArrayInputStream(octets));
		for (LispVal expr : LispReader.readAllFromString("""
				(let ((b (read-byte *standard-input* nil nil)))
				  (while b
				    (write-byte b *standard-output*)
				    (setq b (read-byte *standard-input* nil nil))))
				""")) {
			evaluator.eval(expr);
		}
		out.flush();
		assertThat(captured.toByteArray()).isEqualTo(octets);
	}

	@Test
	void evalBinaryStandardStreamDesignators() {
		// nil and t are the same two designators every other stream operation takes, and
		// read-sequence / write-sequence inherit them through their read-byte /
		// write-byte lowering.
		java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
		PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);
		LispEvaluator evaluator = new LispEvaluator(out, new ByteArrayInputStream(new byte[] { 65, 66, 67 }));
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString("""
				(setq buf (make-array 4 :initial-element 0))
				(setq filled (read-sequence buf t))
				(write-byte 62 nil)
				(write-sequence buf t :end filled)
				(write-byte 60 *standard-output*)
				(read-byte *standard-input* nil :eof)
				""")) {
			result = evaluator.eval(expr);
		}
		out.flush();
		assertThat(captured.toString(StandardCharsets.UTF_8)).isEqualTo(">ABC<");
		// The stream is drained, so the eof-value comes back instead of a signal.
		assertThat(result).isEqualTo(new LispSymbol(":EOF"));
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
		// :async t (the host may suspend; WASM answers a future) loads unchanged here:
		// the host does not exist off WASM, so the stub is the same either way.
		assertThat(eval("(rontolisp:wasm-import 'pull :params '(:string) :returns :string :async t)"))
			.isEqualTo(new LispSymbol("PULL"));
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
				"(AND ASSERT BLOCK CASE CCASE CERROR CHANGE-CLASS CHECK-TYPE COMPLEMENT COMPLEX COND CTYPECASE DECF DECLAIM DECLARE DEFINE-COMPILER-MACRO DEFINE-CONDITION DEFINE-MODIFY-MACRO DEFINE-SETF-EXPANDER DEFSETF DEFTYPE DESTRUCTURING-BIND DO DO* DO-EXTERNAL-SYMBOLS DOCUMENTATION DOLIST DOTIMES ECASE ERROR ETYPECASE EVAL-WHEN FLET FORMAT HANDLER-BIND HANDLER-CASE IGNORE-ERRORS INCF LABELS LET* LOAD-TIME-VALUE LOCALLY LOOP MACROLET MAKE-CONDITION MAKE-INSTANCE MAKE-SEQUENCE MULTIPLE-VALUE-BIND MULTIPLE-VALUE-CALL MULTIPLE-VALUE-LIST MULTIPLE-VALUE-PROG1 MULTIPLE-VALUE-SETQ NTH-VALUE OR POP PPRINT-LOGICAL-BLOCK PRINT-UNREADABLE-OBJECT PROCLAIM PROG PROG* PROG1 PROG2 PSETF PSETQ PUSH PUSHNEW REMF RESTART-BIND RESTART-CASE RETURN-FROM ROTATEF SETF SHIFTF SIGNAL SLOT-BOUNDP SLOT-EXISTS-P SLOT-MAKUNBOUND SLOT-VALUE SYMBOL-MACROLET THE TIME TYPECASE TYPEP UNLESS WARN WHEN WITH-ACCESSORS WITH-INPUT-FROM-STRING WITH-OPEN-FILE WITH-OPEN-STREAM WITH-OUTPUT-TO-STRING WITH-PACKAGE-ITERATOR WITH-SIMPLE-RESTART WITH-SLOTS WITH-STANDARD-IO-SYNTAX WRITE-CHAR)");
	}

	@Test
	void listSpecialFormsReturnsSortedClSpecialForms() {
		assertThat(eval("(rontolisp:list-special-forms)").print()).isEqualTo(
				"(CATCH DEFCLASS DEFCONSTANT DEFGENERIC DEFMACRO DEFMETHOD DEFPACKAGE DEFPARAMETER DEFSTRUCT DEFUN DEFVAR FUNCTION GO IF IN-PACKAGE LAMBDA LET PROGN PROGV QUOTE RETURN SETQ TAGBODY THROW UNWIND-PROTECT WHILE)");
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
			// GETENV is deliberately absent: it is uiop's, not Common Lisp's.
			.doesNotContain("COND", "QUOTE", "DEFUN", "SETF", "%remf-tail", "CADR", "*package*", "ERROR", "%fmt-pad",
					"GETENV")
			.contains("RANDOM", "GET-UNIVERSAL-TIME", "GET-INTERNAL-REAL-TIME", "GET-INTERNAL-RUN-TIME")
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
			.contains("SYMBOL-NAME", "INTERN", "FIND-SYMBOL", "MAKE-SYMBOL", "BOUNDP", "FBOUNDP", "FMAKUNBOUND",
					"SYMBOL-VALUE")
			.contains("BYTE", "BYTE-SIZE", "BYTE-POSITION", "LDB", "DPB")
			.contains("STRING")
			.contains("PEEK-CHAR", "MAKE-STRING-OUTPUT-STREAM", "MAKE-STRING-INPUT-STREAM", "GET-OUTPUT-STREAM-STRING",
					"MAKE-SYNONYM-STREAM", "SYNONYM-STREAM-SYMBOL")
			.contains("INVOKE-RESTART", "FIND-RESTART", "COMPUTE-RESTARTS", "RESTART-NAME", "MUFFLE-WARNING", "ABORT",
					"CONTINUE")
			.doesNotContain("%puthash", "%aset", "%row-major-aset", "%make-string-output-stream",
					"%make-string-input-stream", "%string-stream-contents", "%peek-char", "%set-fill-pointer",
					"%string-compare", "%run-handlers")
			.contains("MAKE-PATHNAME", "MERGE-PATHNAMES", "TRUENAME", "PROBE-FILE", "DIRECTORY", "PATHNAME-DIRECTORY",
					"PATHNAME", "PARSE-NAMESTRING")
			.contains("PATHNAME-HOST", "PATHNAME-DEVICE", "PATHNAME-VERSION", "WILD-PATHNAME-P", "ENOUGH-NAMESTRING",
					"TRANSLATE-PATHNAME", "TRANSLATE-LOGICAL-PATHNAME", "LOGICAL-PATHNAME", "RENAME-FILE")
			.doesNotContain("%list-directory", "%wild-match", "%dir-namestring", "%pathname-typed-p", "%path-ns",
					"%probe-file", "%wild-captures", "%wild-component-p", "%rename-file")
			.contains("CLASS-OF", "CLASS-NAME", "FIND-CLASS", "TYPE-OF", "COMPILE")
			.doesNotContain("%class-designator", "%find-class")
			.contains("CHAR-LESSP", "CHAR-GREATERP", "CHAR-NOT-LESSP", "CHAR-NOT-GREATERP", "CHAR-NOT-EQUAL",
					"GRAPHIC-CHAR-P", "STANDARD-CHAR-P")
			.contains("WRITE", "PPRINT", "PPRINT-NEWLINE", "PPRINT-INDENT", "PPRINT-TAB", "COPY-PPRINT-DISPATCH",
					"SET-PPRINT-DISPATCH", "PPRINT-DISPATCH")
			.doesNotContain("%char-fold-chain", "%pprint-dispatch-default", "%synonym-target")
			.contains("TREE-EQUAL", "COUNT-IF-NOT", "SET-EXCLUSIVE-OR", "MERGE")
			.doesNotContain("%set-xor-match")
			.contains("FILE-NAMESTRING", "DIRECTORY-NAMESTRING", "HOST-NAMESTRING", "NSTRING-UPCASE",
					"NSTRING-DOWNCASE", "NSTRING-CAPITALIZE")
			.contains("LISP-IMPLEMENTATION-TYPE", "LISP-IMPLEMENTATION-VERSION", "SOFTWARE-TYPE", "SOFTWARE-VERSION",
					"MACHINE-TYPE", "MACHINE-VERSION", "MACHINE-INSTANCE", "SHORT-SITE-NAME", "LONG-SITE-NAME")
			.doesNotContain("%nstring-replace", "%target-machine-type")
			.isSorted()
			.hasSize(429);
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
				"(AWAIT CATCH FETCH FINALLY HTTP-HANDLER JSON-PARSE JSON-STRINGIFY LIST-FUNCTIONS LIST-MACROS LIST-SPECIAL-FORMS MAKE-MUTEX MUTEX-ACQUIRE MUTEX-RELEASE QUERY-PARAM QUERY-PARAMS RANDOM-BYTES TCP-ACCEPT TCP-CONNECT TCP-LISTEN TCP-LOCAL-ADDRESS TCP-LOCAL-PORT TCP-PEER-ADDRESS TCP-PEER-PORT TCP-SET-TIMEOUT THEN THEN* TLS-CONNECT TLS-LISTEN TLS-LISTEN-PEM TLS-UPGRADE URL-DECODE URL-ENCODE URL-PATH URL-QUERY VERSION WIT-ERROR-PAYLOAD WIT-PROVIDE)");
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
				"(AWAIT CATCH FETCH FINALLY HTTP-HANDLER JSON-PARSE JSON-STRINGIFY LIST-FUNCTIONS LIST-MACROS LIST-SPECIAL-FORMS MAKE-MUTEX MUTEX-ACQUIRE MUTEX-RELEASE QUERY-PARAM QUERY-PARAMS RANDOM-BYTES TCP-ACCEPT TCP-CONNECT TCP-LISTEN TCP-LOCAL-ADDRESS TCP-LOCAL-PORT TCP-PEER-ADDRESS TCP-PEER-PORT TCP-SET-TIMEOUT THEN THEN* TLS-CONNECT TLS-LISTEN TLS-LISTEN-PEM TLS-UPGRADE URL-DECODE URL-ENCODE URL-PATH URL-QUERY VERSION WIT-ERROR-PAYLOAD WIT-PROVIDE)");
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
		// The value is the package KEYWORD find-package answers, so the two are eq.
		assertThat(eval("*package*")).isEqualTo(new LispSymbol(":CL-USER"));
		assertThat(eval("(eq *package* (find-package \"CL-USER\"))")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void packageVarIsReadWhenTheFormRunsNotWhenItIsResolved() {
		// CL's *package* is dynamic: a defun reads the package current at CALL time.
		// The pre-2026-08-15 fold froze it to the DEFINING package (alexandria's
		// maybe-intern interned into ALEXANDRIA for every caller; rove's set-test
		// registered every test under rove's own package).
		assertThat(evalMulti("""
				(defun cur () *package*)
				(defpackage :caller (:use :cl))
				(in-package :caller)
				(list (cl-user::cur) (let ((*package* (find-package :cl))) (cl-user::cur)) (cl-user::cur))
				""").print()).isEqualTo("(:CALLER :CL :CALLER)");
	}

	@Test
	void setqOfPackageVarSwitchesTheCurrentPackage() {
		// A setq writes through to the resolver's current package: the interpreter
		// resolves the NEXT top-level form in the assigned package, like CL's reader.
		assertThat(evalMulti("""
				(defpackage :target (:use :cl))
				(setq *package* (find-package :target))
				(list *package* (symbol-package 'probe))
				""").print()).isEqualTo("(:TARGET :TARGET)");
		assertThatThrownBy(() -> eval("(setq *package* 42)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("package designator");
	}

	@Test
	void withStandardIoSyntaxBindsPackageToClUser() {
		assertThat(evalMulti("""
				(defpackage :wsios (:use :cl))
				(in-package :wsios)
				(list (with-standard-io-syntax *package*) *package*)
				""").print()).isEqualTo("(:CL-USER :WSIOS)");
	}

	@Test
	void inPackageMakesVersionVisibleUnqualified() {
		assertThat(evalMulti("(in-package :rontolisp) (cl:cadr (version))"))
			.isEqualTo(new LispString(am.ik.rontolisp.Version.getVersion()));
	}

	@Test
	void inPackageUpdatesPackageVar() {
		assertThat(evalMulti("(in-package :rontolisp) cl:*package*")).isEqualTo(new LispSymbol(":RONTOLISP"));
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
			.isEqualTo(new LispSymbol(":CL-USER"));
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
	void tcpCharacterOpsOnSocket() {
		// write-string / write-char / read-char on a socket handle: the
		// write side puts the string's UTF-8 bytes on the wire (read back one by one
		// through read-byte), and read-char assembles ONE code point from the raw
		// bytes -- 199 184 is U+01F8, which a per-byte decode would have split. The
		// program is the interpreter twin of
		// WasmLispCompilerIntegrationTest#componentTcpBinaryBytesAreWireTransparent,
		// whose read-char half used to be component-only; the answers must agree.
		String program = """
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port))
				       (server (rontolisp:tcp-accept listener)))
				  (write-string "AÇB" client)
				  (let* ((b1 (read-byte server))
				         (b2 (read-byte server))
				         (b3 (read-byte server))
				         (b4 (read-byte server)))
				    (write-byte 199 client)
				    (write-byte 184 client)
				    (let ((multi (char-code (read-char server))))
				      (write-char #\\Z client)
				      (write-string "xyz" client :start 1 :end 2)
				      (let* ((ch (char-code (read-char server)))
				             (bounded (char-code (read-char server))))
				        (close client)
				        (close server)
				        (close listener)
				        (list b1 b2 b3 b4 multi ch bounded)))))
				""";
		assertThat(eval(program).print()).isEqualTo("(65 195 135 66 504 90 121)");
	}

	@Test
	void tcpReadCharAtPeerCloseHonoursTheEofArguments() {
		// The socket read answers nil at peer close, and read-char turns that into the
		// SAME eof contract a file stream gets: the 3-arg form yields the eof value,
		// the bare form signals end-of-file.
		String program = """
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port))
				       (server (rontolisp:tcp-accept listener)))
				  (close client)
				  (let ((c (read-char server nil :eof)))
				    (close server)
				    (close listener)
				    c))
				""";
		assertThat(eval(program).print()).isEqualTo(":EOF");
		String signalling = """
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port))
				       (server (rontolisp:tcp-accept listener)))
				  (close client)
				  (handler-case (read-char server)
				    (end-of-file () :signalled)))
				""";
		assertThat(eval(signalling).print()).isEqualTo(":SIGNALLED");
		// The bare read-byte carries the same eof-error-p t default, and the component
		// answers the same (componentTcpBareReadCharSignalsAtPeerClose).
		String bareByte = """
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port))
				       (server (rontolisp:tcp-accept listener)))
				  (close client)
				  (handler-case (read-byte server)
				    (end-of-file () :signalled)))
				""";
		assertThat(eval(bareByte).print()).isEqualTo(":SIGNALLED");
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
	void usocketHostToHostnameRendersEveryHostDesignatorShape() {
		// The four shapes upstream accepts: nil (wildcard), a string (identity), a
		// vector quad and a host-byte-order 32-bit integer.
		String program = """
				(list (usocket:host-to-hostname nil)
				      (usocket:host-to-hostname "example.com")
				      (usocket:host-to-hostname #(192 168 0 1))
				      (usocket:host-to-hostname 2130706433))
				""";
		assertThat(eval(program).print()).isEqualTo("(\"0.0.0.0\" \"example.com\" \"192.168.0.1\" \"127.0.0.1\")");
	}

	@Test
	void usocketGetHostByNameRendersTheDesignatorWithoutResolving() {
		// Lite by construction (no backend has a name-resolution primitive), and the
		// property clack depends on: normalizing an address through the pair that
		// clack.handler:run uses leaves the address it was given, so the socket call
		// downstream still receives something it can resolve itself.
		String program = """
				(list (usocket:host-to-hostname (usocket:get-host-by-name "127.0.0.1"))
				      (usocket:host-to-hostname (usocket:get-host-by-name "example.com")))
				""";
		assertThat(eval(program).print()).isEqualTo("(\"127.0.0.1\" \"example.com\")");
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
	void usocketSocketOptionReceiveTimeoutIsARealReadDeadline() {
		// (setf (usocket:socket-option s :receive-timeout) seconds) is the portable
		// usocket read-timeout spelling (dexador sets it on every connection). It
		// rides rontolisp:tcp-set-timeout (SO_TIMEOUT), and the deadline FIRES: a
		// read on a silent peer signals a catchable error instead of blocking
		// forever. The getter reads the set seconds back, nil clears the deadline,
		// and any other option signals loudly (never accept-and-ignore --
		// .kb/tcp-sockets.md).
		String program = """
				(let* ((listener (usocket:socket-listen "127.0.0.1" 0))
				       (port (usocket:get-local-port listener))
				       (client (usocket:socket-connect "127.0.0.1" port)))
				  (setf (usocket:socket-option client :receive-timeout) 0.2)
				  (let ((result (list (usocket:socket-option client :receive-timeout)
				                      (handler-case (progn (read-line client) :read)
				                        (error (e) :timed-out))
				                      (progn
				                        (setf (usocket:socket-option client :receive-timeout) nil)
				                        (usocket:socket-option client :receive-timeout))
				                      (handler-case (setf (usocket:socket-option client :tcp-nodelay) t)
				                        (error (e) :unsupported)))))
				    (usocket:socket-close client)
				    (usocket:socket-close listener)
				    result))
				""";
		assertThat(eval(program).print()).isEqualTo("(0.2 :TIMED-OUT NIL :UNSUPPORTED)");
	}

	@Test
	void usocketSetfSocketOptionAsTheFirstReferenceLoadsLibraryAndSignalsTyped() {
		// The program's FIRST usocket touch is the setf place write: the
		// ensureUsocketSetfPlaceLoaded hook must load the shim before the place
		// expands. A non-socket handle fails inside the %usock-guard, so the
		// failure is a typed usocket:socket-error.
		assertThat(eval("""
				(handler-case (setf (usocket:socket-option 999 :receive-timeout) 1)
				  (usocket:socket-error (e) :no-such-socket))
				""").print()).isEqualTo(":NO-SUCH-SOCKET");
	}

	@Test
	void usocketWaitForInputPollsThroughListen() {
		// A REAL wait on this backend: listen probes the kernel receive buffer, so
		// an empty socket polls to its timeout (nil with :ready-only) and a socket
		// with data comes back ready with time remaining. Without :ready-only the
		// original argument is returned, as upstream documents.
		String program = """
				(let* ((listener (usocket:socket-listen "127.0.0.1" 0))
				       (port (usocket:get-local-port listener))
				       (client (usocket:socket-connect "127.0.0.1" port))
				       (server (usocket:socket-accept listener)))
				  (let ((empty (usocket:wait-for-input (list client) :timeout 0 :ready-only t)))
				    (write-line "ping" server)
				    (multiple-value-bind (ready remaining)
				        (usocket:wait-for-input (list client) :timeout 5 :ready-only t)
				      (let ((result (list empty
				                          (equal ready (list client))
				                          (if remaining t nil)
				                          (eql (usocket:wait-for-input client :timeout 1) client)
				                          (read-line client))))
				        (usocket:socket-close server)
				        (usocket:socket-close client)
				        (usocket:socket-close listener)
				        result))))
				""";
		assertThat(eval(program).print()).isEqualTo("(NIL T T T \"ping\")");
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
				  (error (e) (list :caught (simple-condition-format-control e))))
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
	void signalFallsThroughAHandlerCaseWhoseClausesDoNotMatch() {
		// CL: signal unwinds only to a handler that will actually handle the
		// condition; an active handler-case for an unrelated type must not turn the
		// signal into an error. trivia level2's pattern expander signals its own
		// wildcard condition inside user handler-case bodies (the ematch expansion
		// under a user (handler-case ... (error ...))).
		assertThat(evalMulti("""
				(define-condition ping-sig () ())
				(handler-case (progn (signal 'ping-sig) :fell-through) (error (e) :caught))
				""").print()).isEqualTo(":FELL-THROUGH");
		// A matching clause still catches, through a non-matching inner frame.
		assertThat(evalMulti("""
				(define-condition ping-sig2 () ())
				(handler-case
				    (handler-case (progn (signal 'ping-sig2) :no) (error (e) :inner))
				  (ping-sig2 () :outer))
				""").print()).isEqualTo(":OUTER");
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
	void restartCaseNormalCompletionReturnsPrimaryValues() {
		assertThat(eval("(restart-case (+ 1 2) (retry () :retried))")).isEqualTo(new LispInteger(3));
		assertThat(eval("(multiple-value-list (restart-case (values 1 2) (retry () nil)))").print()).isEqualTo("(1 2)");
	}

	@Test
	void handlerBindInvokesKeywordRestartAcrossFunctions() {
		// The postmodern prepare.lisp shape: the restart is ESTABLISHED in one
		// function and INVOKED (by keyword name, with an argument) from a
		// handler-bind handler running in another, before unwinding.
		assertThat(evalMulti("""
				(defun rs-f ()
				  (restart-case (progn (error "boom") :not-reached)
				    (:reconnect (x) (list :reconnected x))))
				(handler-bind ((error (lambda (c) (invoke-restart :reconnect 42))))
				  (rs-f))
				""").print()).isEqualTo("(:RECONNECTED 42)");
	}

	@Test
	void handlerBindDecliningHandlerFallsThroughToHandlerCase() {
		assertThat(eval("""
				(let ((log nil))
				  (handler-case
				      (handler-bind ((error (lambda (c) (setq log (cons :seen log)))))
				        (error "boom"))
				    (error (e) (cons :caught log))))
				""").print()).isEqualTo("(:CAUGHT :SEEN)");
	}

	@Test
	void handlerBindReceivesTypedConditionWithSlots() {
		assertThat(evalMulti("""
				(define-condition hb-err (error) ((v :initarg :v :reader hb-err-v)))
				(handler-bind ((hb-err (lambda (c) (invoke-restart :use (hb-err-v c)))))
				  (restart-case (error 'hb-err :v 7)
				    (:use (x) (list :slot x))))
				""").print()).isEqualTo("(:SLOT 7)");
	}

	@Test
	void findRestartReturnsObjectAndGoLeavesClauseIntoTagbody() {
		// The postmodern transaction.lisp shape: find-restart with a condition
		// argument returns a first-class restart object, invoke-restart on the
		// object transfers to the clause, and the clause body (go start) re-enters
		// the enclosing tagbody.
		assertThat(evalMulti("""
				(defun rs-retry (c)
				  (let ((r (find-restart 'retry-me c)))
				    (if (null r) :none (invoke-restart r))))
				(handler-bind ((error (lambda (c) (rs-retry c))))
				  (let ((n 0))
				    (tagbody start
				      (restart-case
				          (progn (setq n (+ n 1)) (when (< n 3) (error "again")))
				        (retry-me () (go start))))
				    n))
				""")).isEqualTo(new LispInteger(3));
	}

	@Test
	void restartsDisappearOutsideTheirExtent() {
		assertThat(eval("(progn (restart-case 1 (gone () nil)) (find-restart 'gone))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(handler-case (invoke-restart :nope) (error (e) :no-restart))").print())
			.isEqualTo(":NO-RESTART");
	}

	@Test
	void computeRestartsListsInnermostFirstAndRestartNameReads() {
		assertThat(eval("""
				(restart-case
				    (restart-case (mapcar (function restart-name) (compute-restarts))
				      (aaa () nil)
				      (bbb () nil))
				  (ccc () nil))
				""").print()).isEqualTo("(AAA BBB CCC)");
	}

	@Test
	void restartCasePassesFiveArguments() {
		// The postmodern roles.lisp shape: a restart taking 5 arguments.
		assertThat(eval("""
				(handler-bind ((error (lambda (c) (invoke-restart :five 1 2 3 4 5))))
				  (restart-case (error "x")
				    (:five (a b c d e) (list a b c d e))))
				""").print()).isEqualTo("(1 2 3 4 5)");
	}

	@Test
	void nestedHandlerBindLayersInnerDeclinesOuterInvokes() {
		// The prepare.lisp shape: nested handler-bind layers around one
		// restart-case; the inner cluster's handler declines (returns), the outer
		// cluster's handler invokes the restart.
		assertThat(eval("""
				(let ((log nil))
				  (handler-bind ((error (lambda (c) (invoke-restart :reconnect))))
				    (handler-bind ((error (lambda (c) (setq log (cons :inner-saw log)))))
				      (restart-case (error "conn lost")
				        (:reconnect () (cons :reconnected log))))))
				""").print()).isEqualTo("(:RECONNECTED :INNER-SAW)");
	}

	@Test
	void handlerSignalingInsideHandlerDoesNotSeeOwnCluster() {
		assertThat(eval("""
				(handler-case
				    (handler-bind ((error (lambda (c) (error "inner"))))
				      (error "outer"))
				  (error (e) (simple-condition-format-control e)))
				""").print()).isEqualTo("\"inner\"");
	}

	@Test
	void anInnerHandlerCaseShadowsAnEnclosingHandlerBind() {
		// CLHS 9.1.4.1: handlers run MOST RECENT FIRST and handler-case transfers
		// control, so a handler-case established inside a handler-bind's extent
		// handles the condition and the enclosing handler-bind handler never runs.
		// The visible break is an outer handler that transfers control itself --
		// every test framework's failure recorder.
		assertThat(eval("""
				(block b
				  (handler-bind ((error (lambda (e) (return-from b :outer-ran))))
				    (handler-case (error "boom") (error () :caught))))
				""").print()).isEqualTo(":CAUGHT");
		assertThat(eval("""
				(block b
				  (handler-bind ((error (lambda (e) (return-from b :outer-ran))))
				    (ignore-errors (error "boom"))))
				""").print()).isEqualTo("NIL");
	}

	@Test
	void anInnerHandlerCaseWhoseClausesDoNotMatchStillLetsTheHandlerBindRun() {
		assertThat(eval("""
				(block b
				  (handler-bind ((error (lambda (e) (return-from b :outer-ran))))
				    (handler-case (error "boom") (end-of-file () :caught))))
				""").print()).isEqualTo(":OUTER-RAN");
	}

	@Test
	void aHandlerCaseClauseBodyDoesNotCatchWhatItSignals() {
		// The clause body runs with this handler-case's own cluster popped, so its
		// error reaches the enclosing handler-bind instead of looping back here.
		assertThat(eval("""
				(block b
				  (handler-bind ((error (lambda (e) (return-from b :outer-ran))))
				    (handler-case (error "boom") (error () (error "again")))))
				""").print()).isEqualTo(":OUTER-RAN");
	}

	@Test
	void anInnerHandlerCaseShadowsAnEnclosingHandlerBindForABuiltInError() {
		assertThat(eval("""
				(block b
				  (handler-bind ((error (lambda (e) (return-from b :outer-ran))))
				    (handler-case (car 1) (error () :caught))))
				""").print()).isEqualTo(":CAUGHT");
	}

	@Test
	void handlerBindSeesTheErrorABuiltInRaises() {
		// The rove shape (.todo/379): a broken test body -- a bad car, an index out
		// of bounds, a bad argument type -- must run the handler-bind handler, not
		// abort the run. The built-in seam runs the cluster stack at the signal
		// point.
		assertThat(eval("(block b (handler-bind ((error (lambda (e) (return-from b :caught)))) (car 1)))").print())
			.isEqualTo(":CAUGHT");
		assertThat(eval("(block b (handler-bind ((error (lambda (e) (return-from b :caught)))) (aref (vector 1 2) 5)))")
			.print()).isEqualTo(":CAUGHT");
		assertThat(eval("(block b (handler-bind ((error (lambda (e) (return-from b :caught)))) (+ 1 \"a\")))").print())
			.isEqualTo(":CAUGHT");
	}

	@Test
	void handlerBindSeesAnUndefinedFunctionError() {
		// Raised outside the built-in seam (function resolution), so the %hb-guard
		// landing pad of the handler-bind expansion is what catches it.
		assertThat(
				eval("(block b (handler-bind ((error (lambda (e) (return-from b :caught)))) (no-such-function-xyz 1)))")
					.print())
			.isEqualTo(":CAUGHT");
	}

	@Test
	void returnInAHandlerBindHandlerExitsTheLEXICALNilBlock() {
		// rove's SIGNALS shape: the handler's (return c) names the (block nil ...)
		// that LEXICALLY encloses the handler, never the implicit nil block of
		// whatever iteration form the SIGNALLING function happens to be running.
		assertThat(evalMulti("""
				(define-condition my-error (error) ())
				(defun raise () (error 'my-error))
				(defun sig-nil (thunk)
				  (block nil
				    (handler-bind ((condition (lambda (c) (return c))))
				      (funcall thunk)
				      nil)))
				(list (type-of (sig-nil (lambda () (raise))))
				      (type-of (sig-nil (lambda () (loop :for i :from 1 :to 3 :collect (raise)))))
				      (type-of (sig-nil (lambda () (dolist (x (list 1 2)) (raise)))))
				      (type-of (sig-nil (lambda () (dotimes (i 2) (raise)))))
				      (type-of (sig-nil (lambda () (do ((i 0 (+ i 1))) ((> i 2)) (raise))))))
				""").print()).isEqualTo("(MY-ERROR MY-ERROR MY-ERROR MY-ERROR MY-ERROR)");
	}

	@Test
	void returnFromANamedBlockInAHandlerBindHandlerStillExitsIt() {
		// The named twin of the shape above -- it always worked, and must keep
		// working now that both resolve through the same lexical lookup.
		assertThat(evalMulti("""
				(define-condition my-error (error) ())
				(defun raise () (error 'my-error))
				(defun sig-named (thunk)
				  (block outer
				    (handler-bind ((condition (lambda (c) (return-from outer c))))
				      (funcall thunk)
				      nil)))
				(type-of (sig-named (lambda () (loop :for i :from 1 :to 3 :collect (raise)))))
				""").print()).isEqualTo("MY-ERROR");
	}

	@Test
	void roveShapedSignalsSeesAConditionRaisedFromInsideALoop() {
		// What rove's (signals FORM 'type) expands to, verbatim in essence.
		assertThat(evalMulti("""
				(define-condition my-error (error) ())
				(defun loop-raiser () (loop :for i :from 1 :to 3 :collect (error 'my-error)))
				(let ((g 'my-error))
				  (typep (block nil
				           (handler-bind ((condition (lambda (c) (when (typep c g) (return c)))))
				             (loop-raiser)
				             nil))
				         g))
				""").print()).isEqualTo("T");
	}

	@Test
	void returnExitsTheInnermostLexicalIterationNotADynamicOne() {
		// The lookup must stay LEXICAL in the ordinary direction too: the inner
		// loop's return exits the inner loop, and a callee's return never reaches
		// its caller's loop.
		assertThat(eval("""
				(loop :for i :from 1 :to 3
				      :collect (loop :for j :from 1 :to 9 :do (if (= j i) (return (* 10 j)))))
				""").print()).isEqualTo("(10 20 30)");
		assertThatThrownBy(() -> evalMulti("""
				(defun callee () (return :dynamic))
				(dotimes (i 1) (callee))
				""")).hasMessageContaining("no enclosing block named NIL");
	}

	@Test
	void anExitToABlockThatIsNoLongerActiveIsAnError() {
		// The closure captures the block LEXICALLY, so calling it after the block
		// returned is the current error -- not a silent no-op, and not an exit to
		// some unrelated block that happens to be active.
		assertThatThrownBy(() -> evalMulti("""
				(defun escaper () (block nil (lambda () (return :late))))
				(funcall (escaper))
				""")).hasMessageContaining("no enclosing block named NIL");
		assertThatThrownBy(() -> eval("(return 1)")).hasMessageContaining("no enclosing block named NIL");
		assertThatThrownBy(() -> eval("(return-from nowhere 1)")).hasMessageContaining("no enclosing block named");
	}

	@Test
	void handlerBindRunsEachClusterOnceForABuiltInErrorInnermostFirst() {
		// Declining handlers: inner cluster first, then outer, each exactly once,
		// and the escaping error is still catchable as a typed condition outside.
		assertThat(eval("""
				(let ((log nil))
				  (handler-case
				      (handler-bind ((error (lambda (c) (setq log (cons :outer log)))))
				        (handler-bind ((error (lambda (c) (setq log (cons :inner log)))))
				          (car 1)))
				    (error (e) (cons :caught log))))
				""").print()).isEqualTo("(:CAUGHT :OUTER :INNER)");
	}

	@Test
	void handlerBindHandlerAndHandlerCaseSeeTheSameInstance() {
		// The signal path attaches the instance %run-handlers saw to the throw, so
		// the handler-case clause dispatches on the IDENTICAL condition -- and the
		// handlers run once, not once per seam.
		assertThat(eval("""
				(let ((seen nil) (n 0))
				  (handler-case
				      (handler-bind ((error (lambda (c) (setq n (+ n 1)) (setq seen c))))
				        (error "boom"))
				    (error (e) (list :caught (eq e seen) n))))
				""").print()).isEqualTo("(:CAUGHT T 1)");
	}

	@Test
	void arefOutOfBoundsAndNegativeMakeArrayAreCatchable() {
		// These used to escape even handler-case as raw Java exceptions; the
		// built-in seam wraps them into conditions.
		assertThat(eval("(handler-case (aref (vector 1 2) 5) (error (e) :caught))").print()).isEqualTo(":CAUGHT");
		assertThat(eval("(handler-case (make-array -1) (error (e) :caught))").print()).isEqualTo(":CAUGHT");
	}

	@Test
	void standardConditionTypeNamesAreClSymbols() {
		// .todo/380: inside a (:use #:cl) package they must NOT read as
		// MY-PKG::TYPE-ERROR -- two packages naming one condition would hold two
		// symbols, and the runtime type test (which matches the registry's plain
		// class name by spelling) would answer nil.
		assertThat(evalMulti("""
				(defpackage #:ct-pkg (:use #:cl))
				(in-package #:ct-pkg)
				(list 'type-error 'condition 'warning 'division-by-zero 'undefined-function 'end-of-file)
				""").print())
			.isEqualTo("(TYPE-ERROR CONDITION WARNING DIVISION-BY-ZERO UNDEFINED-FUNCTION END-OF-FILE)");
	}

	@Test
	void aRuntimeTypeSpecifierNamingASeededConditionClassMatches() {
		// rove's (signals form 'type-error) binds the type to a variable, so the
		// specifier is only known at run time.
		assertThat(eval("(let ((ty 'type-error)) (typep (make-condition 'type-error) ty))").print()).isEqualTo("T");
		assertThat(eval("(let ((ty 'condition)) (typep (make-condition 'simple-warning) ty))").print()).isEqualTo("T");
		assertThat(eval("(let ((ty 'type-error)) (typep (make-condition 'simple-warning) ty))").print())
			.isEqualTo("NIL");
		assertThat(evalMulti("""
				(defpackage #:ct-rt-pkg (:use #:cl))
				(in-package #:ct-rt-pkg)
				(let ((ty 'arithmetic-error)) (typep (make-condition 'division-by-zero) ty))
				""").print()).isEqualTo("T");
	}

	@Test
	void aBuiltInErrorCarriesItsConditionClass() {
		// .todo/380: the rove acceptance shape, (ok (signals (car 1) 'type-error)).
		assertThat(eval("(handler-case (car 1) (type-error (e) :type-error) (error (e) :plain))").print())
			.isEqualTo(":TYPE-ERROR");
		assertThat(eval("(handler-case (/ 1 0) (division-by-zero (e) :dbz) (error (e) :plain))").print())
			.isEqualTo(":DBZ");
		assertThat(eval("(handler-case (aref (vector 1 2) 5) (type-error (e) :type-error) (error (e) :plain))").print())
			.isEqualTo(":TYPE-ERROR");
		assertThat(eval("(handler-case (+ 1 \"a\") (type-error (e) :type-error) (error (e) :plain))").print())
			.isEqualTo(":TYPE-ERROR");
		assertThat(
				eval("(handler-case (no-such-function-xyz 1) (undefined-function (e) :undefined) (error (e) :plain))")
					.print())
			.isEqualTo(":UNDEFINED");
		assertThat(eval(
				"(handler-case (symbol-value 'no-such-var-xyz) (unbound-variable (e) :unbound) (error (e) :plain))")
			.print()).isEqualTo(":UNBOUND");
		// A plain (error "text") stays a simple-error -- nothing named a class.
		assertThat(eval("(handler-case (error \"boom\") (type-error (e) :wrong) (simple-error (e) :simple))").print())
			.isEqualTo(":SIMPLE");
	}

	@Test
	void aSynthesizedBuiltInConditionStillReportsItsMessage() {
		// The four synthesized classes carry format-control, so princ prints the
		// message rather than a bare #<TYPE-ERROR>.
		assertThat(eval("(handler-case (car 1) (type-error (e) (princ-to-string e)))").print())
			.isEqualTo("\"car expects a cons cell, got: 1\"");
		assertThat(eval("(handler-case (/ 1 0) (division-by-zero (e) (princ-to-string e)))").print())
			.isEqualTo("\"Division by zero\"");
	}

	@Test
	void restartBindInvokesFunctionAtInvocationPoint() {
		assertThat(eval("""
				(let ((hit nil))
				  (restart-bind ((poke (lambda (v) (setq hit v))))
				    (invoke-restart 'poke 9)
				    hit))
				""")).isEqualTo(new LispInteger(9));
	}

	@Test
	void withSimpleRestartReturnsNilAndT() {
		assertThat(eval("""
				(handler-bind ((error (lambda (c) (invoke-restart 'skip))))
				  (multiple-value-list (with-simple-restart (skip "Skip it") (error "x"))))
				""").print()).isEqualTo("(NIL T)");
	}

	@Test
	void cerrorEstablishesContinueRestart() {
		assertThat(eval("""
				(handler-bind ((error (lambda (c) (continue))))
				  (list :after (cerror "Continue." "problem")))
				""").print()).isEqualTo("(:AFTER NIL)");
	}

	@Test
	void signalRunsHandlerBindHandlersAndReturnsNil() {
		assertThat(eval("""
				(let ((log nil))
				  (handler-bind ((condition (lambda (c) (setq log :ran))))
				    (signal "s"))
				  log)
				""").print()).isEqualTo(":RAN");
	}

	@Test
	void muffleWarningAbortsWarnOutput() {
		assertThat(eval("""
				(handler-bind ((warning (lambda (w) (muffle-warning))))
				  (list :done (warn "noise")))
				""").print()).isEqualTo("(:DONE NIL)");
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
				assertThat(java.util.Objects.requireNonNull(e.condition()).print()).isEqualTo("#<MY-COND-ERR :V 42>");
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
			.isEqualTo("#<SIMPLE-ERROR :FORMAT-CONTROL \"x\" :FORMAT-ARGUMENTS NIL>");
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
	void unwindProtectCleanupCompletingItsOwnExitKeepsThePendingOne() {
		// A cleanup that itself completes a non-local exit runs WHILE the outer exit is
		// still travelling, so it must not consume it: the outer exit still has to reach
		// its own block afterwards, and must not be reported as an error on the way.
		// Pinned here because the JVM backend's shared exit channel got this wrong.
		assertThat(evalMulti("""
				(defun run-protected (thunk cleanup)
				  (unwind-protect (funcall thunk) (funcall cleanup)))
				(defun inner-block-exit ()
				  (block in (mapcar (lambda (x) (return-from in x)) '(:inner))))
				(defun catch-throw-cleanup () (catch 'tag (throw 'tag :cleaned)))
				(defun probe (cleanup)
				  (block done
				    (run-protected (lambda () (return-from done :from-inner)) cleanup)))
				(list (probe #'catch-throw-cleanup)
				      (probe #'inner-block-exit)
				      (handler-case (probe #'catch-throw-cleanup) (error (e) :swallowed))
				      (block done
				        (handler-bind ((error (lambda (e) e)))
				          (run-protected (lambda () (return-from done :past-guard))
				                         #'catch-throw-cleanup))))
				""").print()).isEqualTo("(:FROM-INNER :FROM-INNER :FROM-INNER :PAST-GUARD)");
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
	void tlsUpgradeEchoRoundTripOnLoopback() throws Exception {
		// tls-upgrade wraps an ALREADY-CONNECTED handle (the cl+ssl
		// make-ssl-client-stream shape): plain tcp-connect first, then the upgrade
		// performs the handshake over that connection and answers a NEW handle.
		am.ik.rontolisp.TlsTestSupport.withTrustStore(() -> {
			try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
				Thread echo = am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
				String program = """
						(let* ((sock (rontolisp:tcp-connect "127.0.0.1" %d))
						       (tls (rontolisp:tls-upgrade sock "127.0.0.1")))
						  (write-line "hello over upgraded tls" tls)
						  (let ((reply (read-line tls)))
						    (close tls)
						    reply))
						""".formatted(server.getLocalPort());
				assertThat(eval(program)).isEqualTo(new LispString("hello over upgraded tls"));
				echo.join();
			}
		});
	}

	@Test
	void tlsUpgradeInsecureSkipsCertificateVerification() throws Exception {
		// No trust store here; :insecure t must skip both the chain validation and
		// the hostname check, like tls-connect's.
		try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
			Thread echo = am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
			String program = """
					(let* ((sock (rontolisp:tcp-connect "127.0.0.1" %d))
					       (tls (rontolisp:tls-upgrade sock "127.0.0.1" :insecure t)))
					  (write-line "hello insecurely upgraded" tls)
					  (let ((reply (read-line tls)))
					    (close tls)
					    reply))
					""".formatted(server.getLocalPort());
			assertThat(eval(program)).isEqualTo(new LispString("hello insecurely upgraded"));
			echo.join();
		}
	}

	@Test
	void tlsUpgradeUntrustedCertificateSignalsError() throws Exception {
		// The verifying default must reject the untrusted self-signed certificate.
		try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
			am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
			String program = """
					(let ((sock (rontolisp:tcp-connect "127.0.0.1" %d)))
					  (rontolisp:tls-upgrade sock "127.0.0.1"))
					""".formatted(server.getLocalPort());
			assertThatThrownBy(() -> eval(program)).isInstanceOf(LispEvalException.class)
				.hasMessageContaining("tls-upgrade");
		}
	}

	@Test
	void tlsUpgradeArgumentValidation() {
		assertThatThrownBy(() -> eval("(rontolisp:tls-upgrade 99)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("TLS-UPGRADE");
		assertThatThrownBy(() -> eval("(rontolisp:tls-upgrade 99 \"h\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a connected socket handle");
		assertThatThrownBy(() -> evalMulti("""
				(let ((listener (rontolisp:tcp-listen 0 "127.0.0.1")))
				  (rontolisp:tls-upgrade listener "h"))
				""")).isInstanceOf(LispEvalException.class).hasMessageContaining("expects a connected socket handle");
		assertThatThrownBy(() -> eval("(rontolisp:tls-upgrade \"nope\" \"h\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a connected socket handle");
	}

	@Test
	void clSslShimHttpsRequestAgainstLocalTlsServer() throws Exception {
		// The cl+ssl shim end to end, the dexador shape: usocket:socket-connect,
		// then make-ssl-client-stream upgrades the connected stream (default
		// :verify from ssl-check-verify-p), then an HTTP-style line goes over the
		// encrypted stream and the echo comes back.
		am.ik.rontolisp.TlsTestSupport.withTrustStore(() -> {
			try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
				Thread echo = am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
				String program = """
						(asdf:load-system "cl+ssl")
						(let* ((sock (usocket:socket-connect "127.0.0.1" %d))
						       (tls (cl+ssl:make-ssl-client-stream (usocket:socket-stream sock)
						                                           :hostname "127.0.0.1")))
						  (write-line "GET / HTTP/1.1" tls)
						  (let ((reply (read-line tls)))
						    (close tls)
						    reply))
						""".formatted(server.getLocalPort());
				assertThat(evalMulti(program)).isEqualTo(new LispString("GET / HTTP/1.1"));
				echo.join();
			}
		});
	}

	@Test
	void clSslShimVerifyNoneContextReachesTheInsecureFlag() throws Exception {
		// dex:*not-verify-ssl* / :insecure travels as make-context :verify-mode
		// +ssl-verify-none+ (installed by with-global-context) plus :verify nil;
		// both must reach tls-upgrade's :insecure -- no trust store is set here.
		try (javax.net.ssl.SSLServerSocket server = am.ik.rontolisp.TlsTestSupport.newServerSocket()) {
			Thread echo = am.ik.rontolisp.TlsTestSupport.startOneShotEchoServer(server);
			String program = """
					(asdf:load-system "cl+ssl")
					(cl+ssl:ensure-initialized)
					(cl+ssl:with-global-context
					    ((cl+ssl:make-context :verify-mode cl+ssl:+ssl-verify-none+) :auto-free-p t)
					  (let* ((sock (usocket:socket-connect "127.0.0.1" %d))
					         (tls (cl+ssl:make-ssl-client-stream (usocket:socket-stream sock)
					                                             :hostname "127.0.0.1"
					                                             :verify (cl+ssl:ssl-check-verify-p))))
					    (write-line "hello via verify-none context" tls)
					    (let ((reply (read-line tls)))
					      (close tls)
					      reply)))
					""".formatted(server.getLocalPort());
			assertThat(evalMulti(program)).isEqualTo(new LispString("hello via verify-none context"));
			echo.join();
		}
	}

	@Test
	void clSslShimSignalsOnWhatHasNoBacking() {
		// Client certificates and CA paths signal instead of being accepted and
		// ignored: silently unauthenticated (or silently trusting the default
		// store where the caller named a CA path) is worse than a message.
		assertThatThrownBy(() -> evalMulti("""
				(asdf:load-system "cl+ssl")
				(cl+ssl:make-ssl-client-stream 99 :hostname "h" :key "client-key.pem")
				""")).isInstanceOf(LispEvalException.class).hasMessageContaining("client certificates");
		assertThatThrownBy(() -> evalMulti("""
				(asdf:load-system "cl+ssl")
				(cl+ssl:use-certificate-chain-file "client-cert.pem")
				""")).isInstanceOf(LispEvalException.class).hasMessageContaining("client certificates");
		assertThatThrownBy(() -> evalMulti("""
				(asdf:load-system "cl+ssl")
				(cl+ssl:make-context :verify-location "/etc/ssl/certs")
				""")).isInstanceOf(LispEvalException.class).hasMessageContaining("javax.net.ssl.trustStore");
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
	void fetchSendsADefaultUserAgent() throws Exception {
		// A caller-silent request carries OUR agent string, not the JDK's
		// Java-http-client/<jdk>: fetch sends the same request on every backend, and the
		// component path has no client of its own to default it.
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
			.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/agent", exchange -> {
			java.util.List<String> received = exchange.getRequestHeaders().get("User-Agent");
			byte[] body = String.join("|", (received == null) ? java.util.List.<String>of() : received)
				.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/agent";
			LispVal sent = eval("(rontolisp:await (rontolisp:read-all (getf (rontolisp:await (rontolisp:fetch \"" + url
					+ "\")) :body)))");
			assertThat(sent).isEqualTo(new LispString(am.ik.rontolisp.compiler.FetchResponseShape.defaultUserAgent()));
			// A caller who set the field owns it -- including under a different spelling,
			// HTTP field names being case-insensitive -- and gets exactly one value.
			LispVal custom = eval("(rontolisp:await (rontolisp:read-all (getf (rontolisp:await (rontolisp:fetch \""
					+ url + "\" (list :headers (list (cons \"user-agent\" \"custom/1\"))))) :body)))");
			assertThat(custom).isEqualTo(new LispString("custom/1"));
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
	void theInterpreterIsTheReferenceThePureBuiltinFoldIsMeasuredAgainst() {
		// The interpreter never folds -- it has no reachability to win -- so both
		// columns of the harness are the same runtime twice here. That is exactly its
		// role: it is the answer the compile backends' folded column has to match, and
		// running the probe program here is what proves every probe is a legal call in
		// the first place.
		FoldDifferential.assertNoDivergence(capture(FoldDifferential.program()));
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
	void evalRuntimeReadSharpDotEvaluates() {
		// #. in runtime-read data evaluates through the evaluator's marker resolver
		// (Environment.readRuntimeDatum), like CL's read under a true *read-eval*.
		assertThat(eval("(read-from-string \"#.(+ 1 2)\")")).isEqualTo(new LispInteger(3));
		assertThat(eval("(read-from-string \"(a #.(* 2 3) c)\")").print()).isEqualTo("(A 6 C)");
		assertThat(eval("(with-input-from-string (s \"#.(+ 2 3)\") (read s))")).isEqualTo(new LispInteger(5));
	}

	@Test
	void loadSharpDotSymbolValueInCodePositionIsTheObject(@TempDir Path tempDir) throws Exception {
		// A #. whose value is a SYMBOL spliced into an evaluated position must stand
		// for the object itself, not become a variable reference: sxql's
		// (intern name #.*package*) idiom (compile.lisp/statement.lisp/clause.lisp)
		// splices the package value -- a plain symbol here -- as an argument.
		Path lib = tempDir.resolve("rte.lisp");
		java.nio.file.Files.writeString(lib, """
				(defpackage #:rte-pkg (:use #:cl))
				(in-package #:rte-pkg)
				(defun rte-make (name) (intern name #.*package*))
				""");
		LispVal result = evalMulti("""
				(load "%s")
				(eq (rte-pkg::rte-make "ZZZ") 'rte-pkg::zzz)
				""".formatted(lib.toString().replace("\\", "\\\\")));
		assertThat(result.print()).isEqualTo("T");
	}

	@Test
	void evalFeaturesIsAnOrdinarySpecialVariableAndItsOwnPushIsRead() {
		// The interpreter half of the four-backend pin: *features* is a list-valued
		// special a program pushes onto and binds, and the reader lets a source's own
		// literal top-level push reach the #+ below it (reader.FeaturePushes). All four
		// backends answer this the same way -- ci-spec case
		// reader-features-own-push-is-visible.
		assertThat(evalMulti("""
				(defun feature-default (&optional (fs *features*)) (car fs))
				(eval-when (:compile-toplevel :load-toplevel :execute)
				  (pushnew :announced *features*))
				(list #+announced :saw-it #-announced :missed-it
				      (and (member :announced *features*) t)
				      (let ((*features* nil)) *features*)
				      ;; the binding is DYNAMIC -- it has to reach a callee reading the
				      ;; variable, which upstream uiop:featurep's own parameter list
				      ;; (&optional (features *features*)) invites a caller to do.
				      (let ((*features* '(:rebound))) (feature-default)))
				""").print()).isEqualTo("(:SAW-IT T NIL :REBOUND)");
	}

	@Test
	void evalReadEvalNilMakesSharpDotSignal() {
		// CLHS: binding *read-eval* to nil makes reading #. signal, catchably; the
		// read after the binding exits works again.
		assertThat(eval("""
				(let ((*read-eval* nil))
				  (handler-case (read-from-string "#.(+ 1 2)")
				    (error (e) :signaled)))""").print()).isEqualTo(":SIGNALED");
		assertThat(evalMulti("""
				(let ((*read-eval* nil)) nil)
				(read-from-string "#.(+ 1 2)")
				""")).isEqualTo(new LispInteger(3));
	}

	@Test
	void evalSharpDotGeneratedDefconstants() {
		// fast-http's multipart-parser idiom on the interpreter path: the marker read
		// plus per-form resolution, the same pipeline loadFile/interpret use.
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos));
		for (LispVal expr : LispReader.readAllWithReadEvalMarkers("""
				#.`(eval-when (:compile-toplevel :load-toplevel :execute)
				     ,@(loop for i from 0
				             for state in '(re-state-alpha re-state-beta re-state-gamma)
				             collect `(defconstant ,(intern (format nil "+~A+" state)) ,i)))
				(print +re-state-beta+)
				(print +re-state-gamma+)
				""", am.ik.rontolisp.reader.Features.INTERPRETER)) {
			evaluator.eval(evaluator.resolveReadTimeEvalInCode(expr));
		}
		assertThat(baos.toString().trim()).isEqualTo("1\n2");
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
	void defmacroSupportsWholeAndLiteEnvironment() {
		// &whole binds the whole macro CALL form (alexandria's switch/eswitch idiom).
		assertThat(evalMulti("""
				(defmacro my-whole-mac (&whole w a) `(list ',w ,a))
				(my-whole-mac 7)
				""").print()).isEqualTo("((MY-WHOLE-MAC 7) 7)");
		assertThatThrownBy(() -> evalMulti("(defmacro my-mac (&whole) 1)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("&WHOLE must be followed by exactly one parameter symbol");
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
	void setfGetfMutatesAnExistingPairInPlace() {
		// CL's plist place: an indicator that already has a cell is updated through
		// rplaca, so an alias of the same list observes the change.
		assertThat(evalMulti("""
				(let* ((p (list :a 1 :b 2)) (alias (cdr (cdr p))))
				  (setf (getf p :b) 20)
				  (list p alias))
				""").print()).isEqualTo("((:A 1 :B 20) (:B 20))");
	}

	@Test
	void setfGetfPushesAMissingPairAndStoresItBack() {
		assertThat(evalMulti("(let ((p nil)) (setf (getf p :x) 7) p)").print()).isEqualTo("(:X 7)");
		// setf returns the VALUE, not the plist.
		assertThat(evalMulti("(let ((p (list :a 1))) (setf (getf p :a) 9))").print()).isEqualTo("9");
		// The optional default in the place is read by getf only, and dropped here.
		assertThat(evalMulti("(let ((p nil)) (setf (getf p :x 99) 7) p)").print()).isEqualTo("(:X 7)");
	}

	@Test
	void formatDestinationNilReturnsTheStringEvenThroughAVariable() {
		// nil as a format destination is not a stream but the "return the string"
		// destination, so a destination arriving through a variable has to be tested at
		// run time -- quri's (render-uri uri &optional stream) passes its optional
		// straight through.
		assertThat(evalMulti("""
				(progn (defun f (&optional s) (format s "~a-~a" 1 2)) (list (f) (f nil)))
				""").print()).isEqualTo("(\"1-2\" \"1-2\")");
		// A stream destination still writes and answers nil.
		assertThat(evalMulti("""
				(progn (defun g (s) (format s "~a" 5))
				       (let ((out (with-output-to-string (o) (setq %r (g o))))) (list out %r)))
				""").print()).isEqualTo("(\"5\" NIL)");
	}

	@Test
	void makeListTakesAnInitialElement() {
		// quri's ip-addr= pads an abbreviated IPv6 address with
		// (make-list (- 9 len) :initial-element 0).
		assertThat(evalMulti("(make-list 3 :initial-element 0)").print()).isEqualTo("(0 0 0)");
		assertThat(evalMulti("(make-list 0 :initial-element :x)").print()).isEqualTo("NIL");
		assertThat(evalMulti("(make-list 2)").print()).isEqualTo("(NIL NIL)");
		assertThatThrownBy(() -> evalMulti("(make-list 2 :bogus 1)")).hasMessageContaining(":initial-element");
	}

	@Test
	void nilAndTAreStringDesignators() {
		// Both are SYMBOLS, so they designate their upcase-canonical names -- quri's
		// scheme-constructor asks (string= scheme "http") of a relative reference whose
		// scheme is nil, and CL answers false rather than signalling.
		assertThat(evalMulti("(string= nil \"http\")").print()).isEqualTo("NIL");
		assertThat(evalMulti("(string= nil \"NIL\")").print()).isEqualTo("T");
		assertThat(evalMulti("(string-equal t \"t\")").print()).isEqualTo("T");
		assertThat(evalMulti("(string-upcase nil)").print()).isEqualTo("\"NIL\"");
	}

	@Test
	void princWritesASymbolNameWithoutItsPackageQualifier() {
		// CLHS 22.1.3.3: with *print-escape* false only the characters of the name are
		// output. Load-bearing beyond printing -- a library that synthesizes a function
		// name with (intern (format nil "~:@(~a-~a~)" name :string)) would otherwise
		// intern the qualifier INTO the name (quri's defun-with-array-parsing).
		assertThat(evalMulti("(princ-to-string 'rontolisp:version)").print()).isEqualTo("\"VERSION\"");
		assertThat(evalMulti("(prin1-to-string 'rontolisp:version)").print()).isEqualTo("\"RONTOLISP:VERSION\"");
		assertThat(evalMulti("(format nil \"~a\" 'rontolisp:version)").print()).isEqualTo("\"VERSION\"");
	}

	@Test
	void aDefstructInAPackageThatExportsItsGeneratedNamesDefinesThem() {
		// The generated constructor/predicate/accessor land in the struct's package
		// spelled the way a CALL SITE resolves them: one colon for an exported member.
		// Reading that wrong is not a package error but an undefined function.
		assertThat(evalMulti("""
				(defpackage :ci-pt-pkg (:use :cl) (:export :pt :make-pt :pt-p :pt-x))
				(in-package :ci-pt-pkg)
				(defstruct pt x)
				(in-package :cl-user)
				(list (ci-pt-pkg:pt-p (ci-pt-pkg:make-pt :x 1))
				      (ci-pt-pkg:pt-x (ci-pt-pkg:make-pt :x 7)))
				""").print()).isEqualTo("(T 7)");
	}

	@Test
	void defstructIncludeOverridesAnInheritedSlotDefault() {
		// quri's shape: uri-http re-defaults the inherited scheme/port slots while
		// keeping their inherited indices, so the parent's accessors still read them.
		assertThat(evalMulti("""
				(progn
				  (defstruct base (scheme nil) (port nil))
				  (defstruct (child (:include base (scheme "http") (port 80))))
				  (let ((c (make-child)))
				    (list (base-scheme c) (base-port c) (base-scheme (make-base)))))
				""").print()).isEqualTo("(\"http\" 80 NIL)");
	}

	@Test
	void defstructIncludeRejectsAnOverrideOfAnUnknownSlot() {
		assertThatThrownBy(() -> evalMulti("""
				(progn (defstruct base a)
				       (defstruct (child (:include base (nope 1)))))
				""")).hasMessageContaining("NOPE");
	}

	@Test
	void printObjectSeesThePrinterModeThroughPrintEscape() {
		// *print-escape* is bound around the method call, so a portable print-object
		// method can tell prin1 from princ exactly as it does in CL.
		assertThat(evalMulti("""
				(progn
				  (defstruct pe x)
				  (defmethod print-object ((p pe) stream)
				    (if (and (null *print-readably*) (null *print-escape*))
				        (format stream "bare-~a" (pe-x p))
				        (format stream "#<PE ~a>" (pe-x p))))
				  (list (prin1-to-string (make-pe :x 1)) (princ-to-string (make-pe :x 1))))
				""").print()).isEqualTo("(\"#<PE 1>\" \"bare-1\")");
	}

	@Test
	void applyThroughAComputedDesignatorTakesAnyArgumentCount() {
		// The per-arity dispatch the compile backends use stops at seven; apply must
		// not, and the interpreter is the reference the ci-spec case compares against.
		assertThat(evalMulti("""
				(progn (defun r (&rest xs) (length xs))
				       (defun f () (function r))
				       (list (apply (f) (list 1 2 3 4 5 6 7 8))
				             (apply (f) 1 2 3 4 5 6 (list 7 8 9 10))))
				""").print()).isEqualTo("(8 10)");
	}

	@Test
	void symbolNameStripsThePackageMarker() {
		assertThat(evalMulti("(symbol-name 'foo)").print()).isEqualTo("\"FOO\"");
		assertThat(evalMulti("(symbol-name :bar)").print()).isEqualTo("\"BAR\"");
		assertThat(evalMulti("(symbol-name (gensym))").print()).isEqualTo("\"g1\"");
		// nil and t are the symbols NIL and T; CL upcases their names like any other.
		assertThat(evalMulti("(symbol-name t)").print()).isEqualTo("\"T\"");
		assertThat(evalMulti("(symbol-name nil)").print()).isEqualTo("\"NIL\"");
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
		assertThat(evalMulti("(string t)").print()).isEqualTo("\"T\"");
		assertThat(evalMulti("(string nil)").print()).isEqualTo("\"NIL\"");
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
	void internHomesTheNameInTheDesignatedPackage() {
		// A package designator interns into THAT package (alexandria's ensure-symbol):
		// a cl-user name stays bare, a keyword designator builds a keyword, and an
		// unknown package signals.
		assertThat(evalMulti("(intern \"FOO\" :cl-user)").print()).isEqualTo("FOO");
		assertThat(evalMulti("(intern \"FOO\" :keyword)").print()).isEqualTo(":FOO");
		assertThat(evalMulti("(intern \"CAR\" :cl)").print()).isEqualTo("CAR");
		assertThatThrownBy(() -> evalMulti("(intern \"FOO\" :no-such-package)"))
			.hasMessageContaining("No such package");
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
	void findSymbolSeesDefinitionsMadeInsideAUserPackage() {
		// A definition IS an interning: a defun (or a defstruct-GENERATED defun)
		// under (in-package p) is registered only in the function namespace under
		// its canonical spelling, never in the package registry, so find-symbol
		// must probe that namespace too. trivia level2's predicatep resolves a
		// struct's predicate with (find-symbol "POINT-P" (symbol-package 'point-)).
		assertThat(evalMulti("""
				(defpackage :fs-probe (:use :cl))
				(in-package :fs-probe)
				(defun my-local-fn (x) x)
				(defstruct fspt a b)
				(list (find-symbol "MY-LOCAL-FN")
				      (find-symbol "FSPT-P")
				      (find-symbol "FSPT-P" :fs-probe)
				      (find-symbol "MAKE-FSPT" :fs-probe)
				      (find-symbol "NO-SUCH-FN" :fs-probe))
				""").print())
			.isEqualTo("(FS-PROBE::MY-LOCAL-FN FS-PROBE::FSPT-P FS-PROBE::FSPT-P FS-PROBE::MAKE-FSPT NIL)");
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
	void standardStreamVariablesAreBoundToTheirDefaultsThroughTheSymbolApi() {
		// The reference answers the three compile backends had to grow (their
		// eval-runtime
		// mirror knew nothing of the global seeding, so symbol-value signalled
		// "unbound").
		// lack's backtrace middleware reaches *error-output* exactly this way: it carries
		// the SYMBOL and reports through (symbol-value output).
		assertThat(evalMulti("(boundp '*error-output*)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(symbol-value '*error-output*)"))
			.isEqualTo(new LispInteger(StreamDesignators.STANDARD_ERROR_HANDLE));
		assertThat(evalMulti("(boundp '*standard-output*)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(symbol-value '*standard-output*)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(boundp '*standard-input*)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(symbol-value '*standard-input*)")).isEqualTo(LispTrue.INSTANCE);
		assertThat(evalMulti("(defvar *sv-stream-name* '*error-output*) (symbol-value *sv-stream-name*)"))
			.isEqualTo(new LispInteger(StreamDesignators.STANDARD_ERROR_HANDLE));
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
	void fmakunboundMakesTheNameUndefinedAgain() {
		assertThat(evalMulti("(defun fmk-fn (x) x) (fmakunbound 'fmk-fn)").print()).isEqualTo("FMK-FN");
		assertThat(evalMulti("(defun fmk-fn2 (x) x) (fmakunbound 'fmk-fn2) (fboundp 'fmk-fn2)"))
			.isEqualTo(LispNil.INSTANCE);
		assertThatThrownBy(() -> evalMulti("(defun fmk-fn3 (x) x) (fmakunbound 'fmk-fn3) (fmk-fn3 1)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("The function FMK-FN3 is undefined");
		// a user macro of the same name goes too, and an unknown name is a no-op
		assertThat(evalMulti("(defmacro fmk-mac (x) x) (fmakunbound 'fmk-mac) (fboundp 'fmk-mac)"))
			.isEqualTo(LispNil.INSTANCE);
		assertThat(evalMulti("(fmakunbound 'fmk-never-defined)").print()).isEqualTo("FMK-NEVER-DEFINED");
	}

	@Test
	void setfSymbolFunctionInstallsAGlobalFunction() {
		assertThat(evalMulti("""
				(defun ssf-orig (x) (* x 2))
				(setf (symbol-function 'ssf-alias) #'ssf-orig)
				(list (ssf-alias 21) (funcall 'ssf-alias 5) (fboundp 'ssf-alias))
				""").print()).isEqualTo("(42 10 T)");
	}

	@Test
	void setfFdefinitionReplacesAnExistingFunction() {
		assertThat(evalMulti("""
				(defun ssf2-f (x) :old)
				(setf (fdefinition 'ssf2-f) (lambda (x) (list :new x)))
				(ssf2-f 1)
				""").print()).isEqualTo("(:NEW 1)");
	}

	@Test
	void setfSymbolFunctionReturnsTheFunctionValue() {
		assertThat(evalMulti("""
				(defun ssf3-add (a b) (+ a b))
				(funcall (setf (symbol-function 'ssf3-alias) #'ssf3-add) 1 2)
				""").print()).isEqualTo("3");
	}

	@Test
	void findSymbolAnswersNilForAPackageThatDoesNotExist() {
		// CL signals a package-error; the compile paths cannot (no registry at run
		// time), and probing an OPTIONAL system this way is what libraries do
		// (postmodern's json-encoder), so all four backends answer nil.
		assertThat(evalMulti("(find-symbol \"TIMESTAMP\" :simple-date)")).isEqualTo(LispNil.INSTANCE);
		assertThat(evalMulti("(find-symbol \"TIMESTAMP\" \"SIMPLE-DATE\")")).isEqualTo(LispNil.INSTANCE);
		assertThat(evalMulti("(find-symbol \"CAR\" nil)")).isEqualTo(LispNil.INSTANCE);
		assertThat(evalMulti("(find-package :simple-date)")).isEqualTo(LispNil.INSTANCE);
		assertThat(evalMulti("(find-package nil)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void findSymbolTakesAStringPackageDesignator() {
		// postmodern's to-sql-name: (intern (string-upcase name) "KEYWORD")
		assertThat(evalMulti("(intern (string-upcase \"some-col\") \"KEYWORD\")").print()).isEqualTo(":SOME-COL");
		assertThat(evalMulti("(find-symbol \"CAR\" \"CL\")").print()).isEqualTo("CAR");
	}

	@Test
	void findSymbolAnswersTheAccessibilityStatusAsItsSecondValue() {
		// The ANSI suite's cl-symbols.lsp reads the status once per standard symbol:
		// (multiple-value-bind (sym status) (find-symbol name 'common-lisp) ...).
		assertThat(evalMulti("(multiple-value-list (find-symbol \"CAR\" 'common-lisp))").print())
			.isEqualTo("(CAR :EXTERNAL)");
		assertThat(evalMulti("(multiple-value-list (find-symbol \"CAR\" \"COMMON-LISP\"))").print())
			.isEqualTo("(CAR :EXTERNAL)");
		assertThat(evalMulti("(multiple-value-list (find-symbol \"CAR\" :cl))").print()).isEqualTo("(CAR :EXTERNAL)");
		// A name the cl package does not own: symbol and status are nil together.
		assertThat(evalMulti("(multiple-value-list (find-symbol \"NO-SUCH-NAME\" 'common-lisp))").print())
			.isEqualTo("(NIL NIL)");
		// cl-user uses cl, so a standard symbol reaches the current package inherited.
		assertThat(evalMulti("(multiple-value-list (find-symbol \"CAR\"))").print()).isEqualTo("(CAR :INHERITED)");
		assertThat(evalMulti("(multiple-value-list (find-symbol \"FOO\" :keyword))").print())
			.isEqualTo("(:FOO :EXTERNAL)");
		assertThat(evalMulti("(multiple-value-list (intern \"CAR\" 'common-lisp))").print())
			.isEqualTo("(CAR :EXTERNAL)");
		// A package that does not exist provides neither.
		assertThat(evalMulti("(multiple-value-list (find-symbol \"CAR\" :simple-date))").print())
			.isEqualTo("(NIL NIL)");
	}

	@Test
	void symbolPlistReadsTheWholePropertyList() {
		assertThat(evalMulti("(symbol-plist 'sp-none)")).isEqualTo(LispNil.INSTANCE);
		assertThat(evalMulti("(setf (get 'sp-x 'a) 1) (symbol-plist 'sp-x)").print()).isEqualTo("(A 1)");
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
	void coerceAcceptsAComputedResultType() {
		// The result type in a VARIABLE dispatches at run time over the same families
		// the literal path handles -- alexandria:copy-sequence / median / coercef.
		String cs = "(defun cs (type seq) (coerce seq type))\n";
		assertThat(evalMulti(cs + "(cs 'list (vector 1 2))").print()).isEqualTo("(1 2)");
		assertThat(evalMulti(cs + "(cs 'list \"ab\")").print()).isEqualTo("(#\\a #\\b)");
		assertThat(evalMulti(cs + "(cs 'vector '(1 2))").print()).isEqualTo("#(1 2)");
		assertThat(evalMulti(cs + "(cs 'string '(#\\a #\\b))").print()).isEqualTo("\"ab\"");
		assertThat(evalMulti(cs + "(cs 'simple-string '(#\\a #\\b))").print()).isEqualTo("\"ab\"");
		// A computed COMPOUND spec dispatches on its head, and t is the identity.
		assertThat(evalMulti(cs + "(cs '(vector t) '(1 2))").print()).isEqualTo("#(1 2)");
		assertThat(evalMulti(cs + "(cs t '(1 2))").print()).isEqualTo("(1 2)");
		assertThat(evalMulti(cs + "(cs 'double-float 3)").print()).isEqualTo("3.0");
		assertThatThrownBy(() -> evalMulti(cs + "(cs 'hash-table '(1 2))"))
			.hasMessageContaining("unsupported result type");
	}

	@Test
	void readSequenceFillsACharacterBuffer() {
		// The BUFFER decides which element is read: a character vector -- the one rank-1
		// array that answers stringp -- reads characters, not bytes
		// (alexandria:read-stream-content-into-string).
		assertThat(evalMulti("""
				(with-input-from-string (s "abcdef")
				  (let ((buf (make-array 4 :element-type 'character)))
				    (list (read-sequence buf s) buf)))
				""").print()).isEqualTo("(4 \"abcd\")");
		// A short read answers the fill position, as for the byte form.
		assertThat(evalMulti("""
				(with-input-from-string (s "ab")
				  (let ((buf (make-array 4 :element-type 'character)))
				    (let ((n (read-sequence buf s))) (list n (subseq buf 0 n)))))
				""").print()).isEqualTo("(2 \"ab\")");
		// The element type may be COMPUTED: make-array then picks the character-vector
		// representation at run time.
		assertThat(evalMulti("""
				(with-input-from-string (s "xyz")
				  (let ((buf (make-array 3 :element-type (stream-element-type s))))
				    (list (read-sequence buf s) buf)))
				""").print()).isEqualTo("(3 \"xyz\")");
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
		// integers up to 18 digits stay exact on every backend (the WASM GC backend
		// carries them in the boxed exact-integer i64 range); wider becomes a float
		assertThat(eval("(rontolisp:json-parse \"1234567890123\")").print()).isEqualTo("1234567890123");
		assertThat(eval("(floatp (rontolisp:json-parse \"1234567890123456789\"))").print()).isEqualTo("T");
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
		assertThat(eval("(rontolisp:json-stringify \"a\\\"b\")").print()).isEqualTo("\"\\\"a\\\\\\\"b\\\"\"");
		assertThat(eval("(rontolisp:json-stringify :key)").print()).isEqualTo("\"\\\"KEY\\\"\"");
		assertThat(eval("(rontolisp:json-stringify 3/2)").print()).isEqualTo("\"1.5\"");
		// a hash table becomes an object
		assertThat(eval("""
				(let ((h (make-hash-table :test 'equal)))
				  (setf (gethash "x" h) (list 1 2))
				  (rontolisp:json-stringify h))""").print()).isEqualTo("\"{\\\"x\\\":[1,2]}\"");
	}

	@Test
	void jsonRoundTripPreservesStructure() {
		assertThat(eval(
				"(rontolisp:json-stringify (rontolisp:json-parse \"{\\\"deep\\\": {\\\"list\\\": [{\\\"k\\\": \\\"v\\\"}, 2.5, true]}}\"))")
			.print()).isEqualTo("\"{\\\"deep\\\":{\\\"list\\\":[{\\\"k\\\":\\\"v\\\"},2.5,true]}}\"");
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
			.isEqualTo("\"{\\\"name\\\":\\\"rontolisp\\\",\\\"ok\\\":true,\\\"ver\\\":1.5}\"");
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
			.isEqualTo("\"{\\\"host\\\":\\\"localhost\\\",\\\"n\\\":2}\"");
		assertThat(eval("(rontolisp:hash-table-alist (rontolisp:alist-hash-table (list (cons \"k\" 7))))").print())
			.isEqualTo("((\"k\" . 7))");
		// first occurrence of a key wins, like alexandria
		assertThat(eval("""
				(hash-table-count
				 (rontolisp:alist-hash-table (list (cons "a" 1) (cons "a" 9)) :test 'equal))""").print())
			.isEqualTo("1");
	}

	@Test
	void alistPlistAndPlistAlist() {
		// subsets of alexandria:alist-plist / plist-alist; no hash table in
		// between, so both directions keep the input order
		assertThat(eval("(rontolisp:alist-plist (list (cons :a 1) (cons :b 2)))").print()).isEqualTo("(:A 1 :B 2)");
		assertThat(eval("(rontolisp:plist-alist (list :a 1 :b 2))").print()).isEqualTo("((:A . 1) (:B . 2))");
		// round trips in both directions, order intact
		assertThat(eval("(rontolisp:alist-plist (rontolisp:plist-alist (list :x 1 :y 2)))").print())
			.isEqualTo("(:X 1 :Y 2)");
		assertThat(eval("(rontolisp:plist-alist (rontolisp:alist-plist (list (cons \"k\" 7))))").print())
			.isEqualTo("((\"k\" . 7))");
		// duplicate keys are preserved -- neither direction dedupes, unlike the
		// *-hash-table pair
		assertThat(eval("(rontolisp:plist-alist (list :a 1 :a 9))").print()).isEqualTo("((:A . 1) (:A . 9))");
		assertThat(eval("(rontolisp:alist-plist nil)").print()).isEqualTo("NIL");
		assertThat(eval("(rontolisp:plist-alist nil)").print()).isEqualTo("NIL");
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
				   (make-instance 'json-resp :status 200 :headers h :items (list 1 2 3))))""").print()).isEqualTo(
				"\"{\\\"status\\\":200,\\\"headers\\\":{\\\"content-type\\\":\\\"application/json\\\"},\\\"items\\\":[1,2,3]}\"");
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
	void defunEmptyKeySection() {
		// &key with NO key parameters must still accept a keyword tail: trivia's
		// :trivial optimizer is (lambda (clauses &key &allow-other-keys) clauses)
		// funcalled as (funcall opt clauses :types types).
		assertThat(evalMulti("(defun f (x &key &allow-other-keys) x) (f 1 :types '(t))").print()).isEqualTo("1");
		assertThat(eval("(funcall (lambda (x &key &allow-other-keys) x) 1 :a 2 :b 3)").print()).isEqualTo("1");
		// Without &allow-other-keys, every keyword is unknown -- but the tail is
		// still consumed, and :allow-other-keys t still overrides.
		assertThat(evalMulti("(defun f (x &key) x) (f 1)").print()).isEqualTo("1");
		assertThatThrownBy(() -> evalMulti("(defun f (x &key) x) (f 1 :bogus 2)")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("Unknown keyword argument: :BOGUS");
		assertThat(evalMulti("(defun f (x &key) x) (f 1 :bogus 2 :allow-other-keys t)").print()).isEqualTo("1");
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
	void defstructDefinesEveryDeclaredConstructor() {
		// CL allows more than one (:constructor ...) option and defines them ALL; only
		// the last one used to survive. esrap's failed-parse declares a full BOA plus a
		// make-failed-parse/no-position over a subset of the inherited slots, so the
		// missing one showed up as "The function MAKE-FAILED-PARSE is undefined" the
		// first time a rule failed to match.
		assertThat(evalMulti("""
				(defstruct (span (:constructor make-span (start end))
				                 (:constructor make-empty-span (start))
				                 (:copier nil))
				  start (end 0))
				(list (span-start (make-span 1 2)) (span-end (make-span 1 2))
				      (span-start (make-empty-span 7)) (span-end (make-empty-span 7)))
				""").print()).isEqualTo("(1 2 7 0)");
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
	void instanceSlotsListsTheSlotValuesInLayoutOrder() {
		assertThat(evalMulti("""
				(defstruct point x y)
				(defstruct empty)
				(list (%obj-slots (%obj-new '|%struct-POINT| 1 "hi"))
				      (%obj-slots (%obj-new '|%struct-EMPTY|))
				      (%obj-slots '(1 2)) (%obj-slots 5))
				""").print()).isEqualTo("((1 \"hi\") NIL NIL NIL)");
	}

	@Test
	void equalpDescendsIntoInstanceSlots() {
		assertThat(evalMulti("""
				(defstruct point x y)
				(defclass box () ((w :initarg :w)))
				(list (equalp (make-point :x 1 :y "A") (make-point :x 1 :y "a"))
				      (equalp (make-point :x 1 :y "A") (make-point :x 2 :y "a"))
				      (equalp (make-point :x 1 :y 2) (make-point :x 1.0 :y 2))
				      (equalp (make-instance 'box :w 1) (make-instance 'box :w 1))
				      (equalp (make-point :x 1 :y 2) (make-instance 'box :w 1)))
				""").print()).isEqualTo("(T NIL T T NIL)");
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
	void structLiteralReadsIntoAnInstance() {
		assertThat(evalMulti("""
				(defstruct point x y)
				#S(POINT :X 1 :Y "hi")
				""").print()).isEqualTo("#S(POINT :X 1 :Y \"hi\")");
	}

	@Test
	void structLiteralIsAnInstanceNotAList() {
		assertThat(evalMulti("""
				(defstruct point x y)
				(list (point-p #S(POINT :X 1 :Y 2)) (consp #S(POINT :X 1 :Y 2)) (point-x #S(POINT :X 7 :Y 2)))
				""").print()).isEqualTo("(T NIL 7)");
	}

	@Test
	void structLiteralReadsInLowercaseSpelling() {
		assertThat(evalMulti("""
				(defstruct point x y)
				#s(point :x 1 :y 2)
				""").print()).isEqualTo("#S(POINT :X 1 :Y 2)");
	}

	@Test
	void structLiteralAcceptsNonKeywordSlotNames() {
		assertThat(evalMulti("""
				(defstruct point x y)
				#S(POINT X 1 Y 2)
				""").print()).isEqualTo("#S(POINT :X 1 :Y 2)");
	}

	@Test
	void structLiteralSurvivesQuoteAndBackquote() {
		assertThat(evalMulti("""
				(defstruct point x y)
				(list '#S(POINT :X 1 :Y 2) `(a #S(POINT :X 3 :Y 4)) `(b `(c #S(POINT :X 5 :Y 6))))
				""").print())
			.isEqualTo("(#S(POINT :X 1 :Y 2) (A #S(POINT :X 3 :Y 4)) (B (QUOTE (C #S(POINT :X 5 :Y 6)))))");
	}

	@Test
	void structLiteralInsideAVectorLiteral() {
		assertThat(evalMulti("""
				(defstruct point x y)
				(aref #(#S(POINT :X 1 :Y 2)) 0)
				""").print()).isEqualTo("#S(POINT :X 1 :Y 2)");
	}

	@Test
	void structLiteralValuesAreReadAsData() {
		assertThat(evalMulti("""
				(defstruct box v)
				(box-v #S(BOX :V (+ 1 2)))
				""").print()).isEqualTo("(+ 1 2)");
	}

	@Test
	void structLiteralNestsAnotherStructLiteral() {
		assertThat(evalMulti("""
				(defstruct inner n)
				(defstruct outer i)
				#S(OUTER :I #S(INNER :N 5))
				""").print()).isEqualTo("#S(OUTER :I #S(INNER :N 5))");
	}

	@Test
	void structLiteralOfASlotlessType() {
		assertThat(evalMulti("""
				(defstruct empty)
				#S(EMPTY)
				""").print()).isEqualTo("#S(EMPTY)");
	}

	@Test
	void structLiteralOmittedSlotTakesItsInitform() {
		assertThat(evalMulti("""
				(defstruct point (x 10) (y 20))
				#S(POINT :Y 2)
				""").print()).isEqualTo("#S(POINT :X 10 :Y 2)");
	}

	@Test
	void structLiteralDuplicateSlotKeepsTheLeftmostValue() {
		assertThat(evalMulti("""
				(defstruct point x y)
				#S(POINT :X 1 :Y 2 :X 99)
				""").print()).isEqualTo("#S(POINT :X 1 :Y 2)");
	}

	@Test
	void structLiteralReadsAPackageQualifiedTypeName() {
		assertThat(evalMulti("""
				(defpackage :geo (:use :cl))
				(in-package :geo)
				(defstruct pt x)
				(in-package :cl-user)
				#S(GEO::PT :X 3)
				""").print()).isEqualTo("#S(GEO::PT :X 3)");
	}

	// The type name sits in a symbol position, so it is package-resolved like the
	// defstruct's own name: the bare spelling inside the package finds it.
	@Test
	void structLiteralReadsAnUnqualifiedTypeNameInsideItsPackage() {
		assertThat(evalMulti("""
				(defpackage :geo (:use :cl))
				(in-package :geo)
				(defstruct pt x)
				#S(PT :X 3)
				""").print()).isEqualTo("#S(GEO::PT :X 3)");
	}

	@Test
	void structLiteralUnderAFailingFeatureConditionalIsSkipped() {
		assertThat(evalMulti("""
				(defstruct point x y)
				(list 1 #+no-such-feature #S(POINT :X 1 :Y 2) 3)
				""").print()).isEqualTo("(1 3)");
	}

	@Test
	void structLiteralOfAnUnknownTypeSignals() {
		assertThatThrownBy(() -> evalMulti("#S(NOPE :X 1)"))
			.hasMessageContaining("NOPE is not a defined structure type");
	}

	@Test
	void structLiteralOfAClassSignals() {
		assertThatThrownBy(() -> evalMulti("""
				(defclass pt () ((x :initarg :x)))
				#S(PT :X 1)
				""")).hasMessageContaining("#S reads defstruct types only");
	}

	@Test
	void structLiteralMustFollowItsDefstruct() {
		assertThatThrownBy(() -> evalMulti("""
				#S(POINT :X 1)
				(defstruct point x)
				""")).hasMessageContaining("POINT is not a defined structure type");
	}

	@Test
	void structLiteralOfAnUnknownSlotSignals() {
		assertThatThrownBy(() -> evalMulti("""
				(defstruct point x y)
				#S(POINT :Z 1)
				""")).hasMessageContaining("POINT has no slot named :Z");
	}

	@Test
	void structLiteralWithAnOddArgumentListIsAReadError() {
		assertThatThrownBy(() -> evalMulti("""
				(defstruct point x y)
				#S(POINT :X)
				""")).hasMessageContaining("odd number of slot name/value items");
	}

	@Test
	void structLiteralWithoutATypeNameIsAReadError() {
		assertThatThrownBy(() -> evalMulti("#S()")).hasMessageContaining("a structure literal needs a type name");
	}

	@Test
	void structLiteralOmittingASlotWithANonConstantInitformSignals() {
		assertThatThrownBy(() -> evalMulti("""
				(defstruct point (x (+ 1 2)))
				#S(POINT)
				""")).hasMessageContaining("is not a constant");
	}

	@Test
	void runtimeReadFromStringBuildsTheInstance() {
		assertThat(evalMulti("""
				(defstruct point x y)
				(let ((p (read-from-string (prin1-to-string (make-point :x 1 :y "hi")))))
				  (list p (point-p p) (point-y p) (funcall #'read-from-string "#S(POINT :X 5 :Y 6)")))
				""").print()).isEqualTo("(#S(POINT :X 1 :Y \"hi\") T \"hi\" #S(POINT :X 5 :Y 6))");
	}

	@Test
	void runtimeReadErrorsAreCatchableConditions() {
		// A runtime read error is a catchable condition (CL's reader-error is an error
		// subtype), carrying the frontend's message -- the same contract the compiled
		// backends' emitted readers follow.
		assertThat(evalMulti("""
				(defstruct point x y)
				(list
				  (handler-case (read-from-string "#\\\\Foo") (error (e) (simple-condition-format-control e)))
				  (handler-case (read-from-string "#xZZ") (error (e) (simple-condition-format-control e)))
				  (handler-case (read-from-string "#S(NOSUCH :X 1)") (error (e) (simple-condition-format-control e)))
				  (handler-case (read-from-string "#S(POINT :Z 1)") (error (e) (simple-condition-format-control e)))
				  (handler-case (read-from-string "1/0") (error (e) (simple-condition-format-control e))))
				""").print()).isEqualTo("(\"Unknown character name: #\\\\Foo\" \"Invalid digits after #x: Z\" "
				+ "\"#S(NOSUCH ...): NOSUCH is not a defined structure type\" "
				+ "\"#S(POINT ...): POINT has no slot named :Z\" \"Division by zero in ratio literal: 1/0\")");
	}

	@Test
	void structLiteralIsEqualToTheConstructedInstance() {
		assertThat(evalMulti(
				"""
						(defstruct point x y)
						(list (equal #S(POINT :X 1 :Y 2) (make-point :x 1 :y 2)) (equal #S(POINT :X 1 :Y 2) (make-point :x 9 :y 2)))
						""")
			.print()).isEqualTo("(T NIL)");
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
	void defstructIncludeInheritsSlotsAndTypeTests() {
		// The parent's slots come first, so its accessors read a child instance too, and
		// both (typep x 'parent) and the parent's PREDICATE match the child -- the
		// predicate is regenerated when the child's defstruct widens the tag set, even
		// though the parent's defstruct came first (see .kb/defstruct.md).
		assertThat(evalMulti("""
				(defstruct base (a 1) (b 2))
				(defstruct (child (:include base)) (c 3))
				(defstruct (grand (:include child)) (d 4))
				(let ((k (make-child :a 10 :c 30)))
				  (list (base-a k) (base-b k) (child-c k) (child-p k) (base-p k)
				        (base-p (make-grand)) (child-p (make-grand)) (grand-p k)
				        (child-p (make-base)) (typep k 'base) (typep (make-base) 'child)))
				""").print()).isEqualTo("(10 2 30 T T T T NIL NIL T NIL)");
	}

	@Test
	void defstructIncludeUnknownParentSignals() {
		assertThatThrownBy(() -> eval("(defstruct (point (:include base)) x y)"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(":include names an unknown struct");
	}

	@Test
	void defstructTypeVectorMakesPlainVectors() {
		// A (:type (vector ...)) struct IS a vector: accessors are aref reads (and setf
		// places through the generated writer), and it is not an instance object.
		assertThat(evalMulti("""
				(defstruct (regs (:type (vector (unsigned-byte 32))) (:constructor initial-regs ())) (a 7) (b 8))
				(let ((r (initial-regs)))
				  (setf (regs-b r) 99)
				  (list r (regs-a r) (regs-b r) (vectorp r)))
				""").print()).isEqualTo("(#(7 99) 7 99 T)");
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
	void setfMethodDefinesAWriterGeneric() {
		assertThat(evalMulti("""
				(defclass sm-box () ((v :initarg :v :reader sm-content)))
				(defmethod (setf sm-content) (new (b sm-box)) (setf (slot-value b 'v) new) new)
				(let ((b (make-instance 'sm-box :v 1)))
				  (list (setf (sm-content b) 42) (sm-content b)))
				""").print()).isEqualTo("(42 42)");
	}

	@Test
	void setfMethodDispatchesPerClassAndMergesWithAccessorWriters() {
		// sm2-a's :accessor generates writer methods on the same %setf- generic; the
		// user's (setf sm2-val) method on sm2-b merges instead of shadowing.
		assertThat(evalMulti("""
				(defclass sm2-a () ((x :initarg :x :accessor sm2-val)))
				(defclass sm2-b () ((log :initform nil :reader sm2-log)))
				(defmethod (setf sm2-val) (new (b sm2-b)) (setf (slot-value b 'log) (list :wrote new)) new)
				(let ((a (make-instance 'sm2-a :x 1))
				      (b (make-instance 'sm2-b)))
				  (setf (sm2-val a) 2)
				  (setf (sm2-val b) 3)
				  (list (sm2-val a) (sm2-log b)))
				""").print()).isEqualTo("(2 (:WROTE 3))");
	}

	@Test
	void setfMethodIsFirstClassViaFunctionQuote() {
		assertThat(evalMulti("""
				(defclass sm3-box () ((v :initform 0 :reader sm3-content)))
				(defmethod (setf sm3-content) (new (b sm3-box)) (setf (slot-value b 'v) new))
				(let ((b (make-instance 'sm3-box)))
				  (funcall #'(setf sm3-content) 7 b)
				  (sm3-content b))
				""").print()).isEqualTo("7");
	}

	@Test
	void defgenericSetfNameWithInlineMethod() {
		assertThat(evalMulti("""
				(defclass sm4-box () ((v :initform 0 :reader sm4-content)))
				(defgeneric (setf sm4-content) (new box)
				  (:method (new (b sm4-box)) (setf (slot-value b 'v) new)))
				(let ((b (make-instance 'sm4-box)))
				  (setf (sm4-content b) 9)
				  (sm4-content b))
				""").print()).isEqualTo("9");
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
	void closerMopShimAnswersOverClassMetaobjectsAndLegacyTagDesignators() {
		// The shim serves BOTH generations: real metaobjects (find-class) answer with
		// effective-slot-definition instances; a class-of TAG symbol keeps the legacy
		// (name type) pairs so slot-walking serializers (jzon) are unchanged.
		assertThat(evalMulti("""
				(asdf:load-system "closer-mop")
				(defclass cmm-p () ((name :initarg :name) (age :initarg :age :type integer)))
				(let ((c (find-class 'cmm-p)))
				  (list (closer-mop:classp c)
				        (closer-mop:classp 42)
				        (closer-mop:class-name c)
				        (closer-mop:class-finalized-p c)
				        (mapcar #'closer-mop:slot-definition-name (closer-mop:class-slots c))
				        (mapcar #'closer-mop:slot-definition-type (closer-mop:class-slots c))
				        (mapcar #'closer-mop:slot-definition-initargs (closer-mop:class-slots c))
				        (mapcar #'closer-mop:slot-definition-name
				                (closer-mop:class-slots (class-of (make-instance 'cmm-p :name "x"))))))
				""").print()).isEqualTo("(T NIL CMM-P T (NAME AGE) (T INTEGER) ((:NAME) (:AGE)) (NAME AGE))");
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
	void noApplicableMethodIsCatchableAndReportsTheSameText() {
		// The last resort signals a typed condition whose :report renders lazily; the
		// text a handler that PRINTS it sees must be exactly the old eager message.
		assertThat(evalMulti("""
				(defclass nam-box () ((v :initarg :v :reader nam-box-v)))
				(handler-case (nam-box-v 42) (error (e) (princ-to-string e)))
				""").print()).isEqualTo("\"No applicable method: NAM-BOX-V on INTEGER\"");
	}

	@Test
	void defmethodOnABuiltinNameKeepsTheBuiltinAsTheDefaultMethod() {
		// The dispatcher SHADOWS the built-in defun; without stashing it as the
		// generic's default method every non-instance argument dies with "No
		// applicable method". In CL these are generic functions whose standard
		// methods survive a user defmethod.
		assertThat(evalMulti("""
				(defclass mycls () ())
				(defmethod length ((x mycls)) 42)
				(list (length (make-instance 'mycls)) (length "abc") (length '(1 2 3)))
				""").print()).isEqualTo("(42 3 3)");
	}

	@Test
	void defmethodOnABuiltinNameStashesTheBuiltinExactlyOnce() {
		// The dispatcher is regenerated on EVERY defmethod, so a stash that reruns
		// captures the dispatcher itself and the fall-through recurses forever.
		assertThat(evalMulti("""
				(defclass a () ())
				(defclass b () ())
				(defmethod length ((x a)) 1)
				(defmethod length ((x b)) 2)
				(defmethod length ((x (eql :three))) 3)
				(list (length (make-instance 'a)) (length (make-instance 'b)) (length :three) (length "abcd"))
				""").print()).isEqualTo("(1 2 3 4)");
	}

	@Test
	void defgenericOnABuiltinNameKeepsTheBuiltinAsTheDefaultMethod() {
		assertThat(evalMulti("""
				(defgeneric length (x))
				(length "abc")
				""").print()).isEqualTo("3");
	}

	@Test
	void defmethodOnAVariadicBuiltinNameForwardsTheKeywordTail(@TempDir Path tempDir) {
		// fast-io's gray.lisp shape: a (close (s mycls) &key abort) method used to
		// poison close for the whole image, so any later with-open-file -- on an
		// unrelated file -- died with "No applicable method: CLOSE on INTEGER".
		// The dispatcher is variadic here, so the built-in has to be reached
		// through its %gf-rest tail.
		String file = tempDir.resolve("closed.txt").toString().replace("\\", "\\\\");
		assertThat(evalMulti("""
				(defclass mycls () ())
				(defmethod close ((s mycls) &key abort) (declare (ignore abort)) :closed)
				(list (close (make-instance 'mycls))
				      (with-open-file (out "%s" :direction :output) (write-line "hi" out))
				      (with-open-file (in "%s") (read-line in))
				      (let ((s (open "%s"))) (close s :abort t)))
				""".formatted(file, file, file)).print()).isEqualTo("(:CLOSED \"hi\" \"hi\" T)");
	}

	@Test
	void defmethodOnABuiltinNameQualifiersRunAroundTheBuiltin() {
		// With the built-in as the default method, a :before/:after-only method
		// composes with it instead of signalling "No applicable primary method",
		// and the least-specific primary's call-next-method reaches it.
		assertThat(evalMulti("""
				(defclass mycls () ())
				(defparameter *log* nil)
				(defmethod length :before ((x string)) (push :before *log*))
				(defmethod length ((x mycls)) (call-next-method))
				(list (length "abc") (reverse *log*))
				""").print()).isEqualTo("(3 (:BEFORE))");
	}

	@Test
	void defmethodOnABuiltinNameUserDefaultMethodStillWins() {
		// A user default (unspecialized) method replaces the built-in outright --
		// the built-in is the LAST resort, not a method the sort can outrank.
		assertThat(evalMulti("""
				(defclass mycls () ())
				(defmethod length ((x mycls)) 42)
				(defmethod length (x) :fallback)
				(list (length (make-instance 'mycls)) (length "abc"))
				""").print()).isEqualTo("(42 :FALLBACK)");
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
	void callNextMethodChainsStructIncludeAncestry() {
		// A method specialized on an :include PARENT struct is applicable to the
		// child's branch, so the child's call-next-method reaches it (sxql's yield
		// methods over the sql-statement :include tree), and an :around on t wraps
		// a struct-specialized primary the same way it wraps a class one.
		assertThat(evalMulti("""
				(defstruct cnm-base (name "b"))
				(defstruct (cnm-child (:include cnm-base)))
				(defgeneric cnm-render (x))
				(defmethod cnm-render ((x cnm-base)) (list :base (cnm-base-name x) (next-method-p)))
				(defmethod cnm-render ((x cnm-child)) (cons :child (call-next-method)))
				(defmethod cnm-render :around ((x t)) (cons :around (call-next-method)))
				(cnm-render (make-cnm-child))
				""").print()).isEqualTo("(:AROUND :CHILD :BASE \"b\" NIL)");
	}

	@Test
	void aStructDefinedAfterTheMethodJoinsStructDispatch() {
		// A defstruct must refresh the dispatchers that test struct specializers
		// (the struct-side twin of evalDefclass's regeneration): sxql's
		// convert-for-sql declares (:method ((object structure-object)) ...) in
		// operator.lisp while the set=-clause struct arrives later from clause.lisp,
		// and a struct-specialized method's descendant tag set must widen when a
		// later (:include it) struct appears.
		assertThat(evalMulti("""
				(defgeneric sod-render (x))
				(defmethod sod-render ((x structure-object)) :struct)
				(defmethod sod-render ((x t)) :other)
				(defstruct sod-late)
				(defstruct sod-base)
				(defmethod sod-tag ((x sod-base)) :base)
				(defstruct (sod-kid (:include sod-base)))
				(list (sod-render (make-sod-late)) (sod-render 42) (sod-tag (make-sod-kid)))
				""").print()).isEqualTo("(:STRUCT :OTHER :BASE)");
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
	void defclassSlotWithoutInitformStartsUnbound() {
		assertThat(evalMulti("""
				(defclass ub-box () ((a :initarg :a) (b :initform 7)))
				(let ((o (make-instance 'ub-box)))
				  (list (slot-boundp o 'a) (slot-boundp o 'b)
				        (progn (setf (slot-value o 'a) 1) (slot-boundp o 'a))
				        (progn (slot-makunbound o 'a) (slot-boundp o 'a))
				        (handler-case (slot-value o 'a)
				          (unbound-slot (e) (list (cell-error-name e) (type-of (unbound-slot-instance e)))))))
				""").print()).isEqualTo("(NIL T T NIL (A UB-BOX))");
	}

	@Test
	void slotExistsPAnswersDeclaredSlotsRegardlessOfBoundness() {
		// mito's compute-effective-slot-definition :around probes direct-slot
		// definitions with (slot-exists-p x 'col-type) before slot-boundp: an unbound
		// slot EXISTS, an undeclared one does not, and a non-instance answers nil
		// rather than signalling. The last leg is a RUNTIME slot name.
		assertThat(evalMulti("""
				(defclass se-box () ((a :initarg :a) (b :initform 7)))
				(let ((o (make-instance 'se-box)))
				  (list (slot-exists-p o 'a) (slot-exists-p o 'b)
				        (slot-exists-p o 'zz) (slot-exists-p 42 'a)
				        (slot-exists-p o (car (list 'a)))))
				""").print()).isEqualTo("(T T NIL NIL T)");
	}

	@Test
	void slotValueOnAnUndeclaredSlotSignalsAtRunTime() {
		// No registered class declares the slot: CL reaches such a read through
		// slot-missing at RUN time, so it is a catchable condition, never a
		// compile-time/expansion-time failure. fast-io's open-stream-p reads a slot
		// name that is a typo for its own, in a method nothing calls.
		assertThat(evalMulti("""
				(defclass ms-box () ((a :initform 1)))
				(list (handler-case (slot-value (make-instance 'ms-box) 'nope)
				        (error (e) (princ-to-string e)))
				      (handler-case (setf (slot-value (make-instance 'ms-box) 'nope) 3)
				        (error (e) (princ-to-string e))))
				""").print()).isEqualTo("(\"The slot NOPE is missing\" \"The slot NOPE is missing\")");
	}

	@Test
	void defclassSubclassShadowsAnInheritedSlot() {
		// The storage stays the ONE inherited slot: the superclass reader and the
		// subclass accessor see the same value, at the same index.
		assertThat(evalMulti("""
				(defclass sh-base () ((open-p :initform t :reader sh-open-p) (conn :initarg :conn :reader sh-conn)))
				(defclass sh-sub (sh-base) ((open-p :initform :maybe :accessor sh-sub-open-p)))
				(let ((o (make-instance 'sh-sub :conn "c")))
				  (setf (sh-sub-open-p o) :closed)
				  (list (sh-open-p o) (sh-sub-open-p o) (sh-conn o)))
				""").print()).isEqualTo("(:CLOSED :CLOSED \"c\")");
	}

	@Test
	void findClassReturnsAnEqStableClassMetaobject() {
		// The metaobject is memoized (eq across calls), carries the class name at slot
		// 0, the direct-superclass metaobjects at slot 1 and finalized-p at slot 4.
		assertThat(evalMulti("""
				(defclass fc-animal () ((legs :initarg :legs :accessor fc-legs :type integer)))
				(defclass fc-dog (fc-animal) ((name :initarg :name)))
				(let ((c (find-class 'fc-dog)))
				  (list (eq c (find-class 'fc-dog))
				        (%obj-ref c 0)
				        (eq (car (%obj-ref c 1)) (find-class 'fc-animal))
				        (%obj-ref c 4)))
				""").print()).isEqualTo("(T FC-DOG T T)");
	}

	@Test
	void findClassMetaobjectCarriesEffectiveSlotDefinitions() {
		// Slot 3 of the metaobject is the effective-slot-definition list: each entry
		// holds (name initargs initform type readers), inherited slots first.
		assertThat(evalMulti("""
				(defclass fc-box () ((w :initarg :w :accessor fc-w :type integer) (h :initform 2)))
				(let* ((c (find-class 'fc-box))
				       (slots (%obj-ref c 3)))
				  (list (length slots)
				        (mapcar (lambda (s) (%obj-ref s 0)) slots)
				        (%obj-ref (car slots) 1)
				        (%obj-ref (car slots) 3)
				        (%obj-ref (car slots) 4)
				        (%obj-ref (car (cdr slots)) 2)))
				""").print()).isEqualTo("(2 (W H) (:W) INTEGER (FC-W) 2)");
	}

	@Test
	void findClassUnknownSignalsUnlessErrorpNil() {
		assertThat(evalMulti("""
				(list (find-class 'fc-no-such nil)
				      (handler-case (find-class 'fc-no-such) (error (e) :signaled)))
				""").print()).isEqualTo("(NIL :SIGNALED)");
	}

	@Test
	void setfFindClassRegistersAnAliasNameForTheSameClass() {
		// (setf (find-class 'alias) (find-class 'target)) is cl-dbi's defclass/a idiom:
		// the alias is a SECOND NAME of one class, so the metaobject is eq, and
		// make-instance / typep / class-name all behave as through the target.
		assertThat(evalMulti("""
				(defclass fca-shape () ((n :initarg :n :reader fca-n)))
				(setf (find-class '<fca-shape>) (find-class 'fca-shape))
				(list (eq (find-class '<fca-shape>) (find-class 'fca-shape))
				      (fca-n (make-instance '<fca-shape> :n 7))
				      (typep (make-instance 'fca-shape :n 1) '<fca-shape>)
				      (class-name (find-class '<fca-shape>))
				      (subtypep '<fca-shape> 'fca-shape))
				""").print()).isEqualTo("(T 7 T FCA-SHAPE T)");
	}

	@Test
	void setfFindClassAliasIsVisibleToHandlerCase() {
		// The condition half of the same idiom (define-condition/a): a handler-case
		// clause naming the alias catches the condition signaled under the real name.
		assertThat(evalMulti("""
				(define-condition fca-error (error) ((code :initarg :code :reader fca-code)))
				(setf (find-class '<fca-error>) (find-class 'fca-error))
				(handler-case (error 'fca-error :code 42) (<fca-error> (e) (fca-code e)))
				""").print()).isEqualTo("42");
	}

	@Test
	void setfFindClassRejectsNonAliasShapesAndUnknownTargets() {
		// Only the aliasing shape is supported (the class table is fixed at compile time
		// on the compiled backends), and the target must already be a registered class.
		assertThatThrownBy(() -> evalMulti("""
				(defclass fca-thing () ())
				(setf (find-class 'fca-other) (make-instance 'fca-thing))
				""")).hasMessageContaining("only supports aliasing an existing class");
		assertThatThrownBy(() -> eval("(setf (find-class 'fca-gone) (find-class 'fca-no-such))"))
			.hasMessageContaining("there is no class named");
		// An exact class name wins over the alias table, so rebinding one would be a
		// silent no-op -- it is rejected instead.
		assertThatThrownBy(() -> evalMulti("""
				(defclass fca-a () ())
				(defclass fca-b () ())
				(setf (find-class 'fca-a) (find-class 'fca-b))
				""")).hasMessageContaining("a class of that name is already defined");
	}

	@Test
	void findClassAnswersForSeededConditionClasses() {
		assertThat(evalMulti("""
				(let ((c (find-class 'type-error)))
				  (list (%obj-ref c 0) (%obj-ref (car (%obj-ref c 1)) 0)))
				""").print()).isEqualTo("(TYPE-ERROR ERROR)");
	}

	@Test
	void classOfReturnsTheClassMetaobjectEqToFindClass() {
		// class-of answers the metaobject view: the same memoized standard-class
		// instance find-class yields, for CLOS instances AND struct instances (a struct
		// class is a standard-class instance too -- no structure-class, a documented
		// divergence). class-name reads its name.
		assertThat(evalMulti("""
				(defclass co-pt () ((x :initarg :x)))
				(defstruct co-node value)
				(list (eq (class-of (make-instance 'co-pt :x 1)) (find-class 'co-pt))
				      (eq (class-of (make-co-node :value 1)) (find-class 'co-node))
				      (class-name (class-of (make-instance 'co-pt :x 1)))
				      (class-name (class-of (make-co-node :value 2))))
				""").print()).isEqualTo("(T T CO-PT CO-NODE)");
	}

	@Test
	void classOfBuiltinValuesAnswerBuiltinClassMetaobjects() {
		// A non-instance value answers a slot-less built-in class metaobject, memoized
		// under its name so find-class resolves it to the same object.
		assertThat(eval("""
				(list (eq (class-of 42) (find-class 'integer))
				      (class-name (class-of 42))
				      (class-name (class-of "s"))
				      (class-name (class-of nil))
				      (class-name (class-of t))
				      (class-name (class-of 'sym))
				      (class-name (class-of (make-array 1))))
				""").print()).isEqualTo("(T INTEGER STRING NULL BOOLEAN SYMBOL T)");
	}

	@Test
	void classOfConditionAndTypeOfStayCoherent() {
		// type-of rides the %class-designator view (the plain NAME), untouched by the
		// metaobject migration; class-of on a condition answers the seeded class.
		assertThat(evalMulti("""
				(define-condition co-oops (simple-error) ())
				(handler-case (error 'co-oops :format-control "x")
				  (error (e) (list (class-name (class-of e)) (type-of e) (type-of 42))))
				""").print()).isEqualTo("(CO-OOPS CO-OOPS INTEGER)");
	}

	@Test
	void typepAndSubtypepAcceptClassMetaobjectsAsTypeSpecifiers() {
		// A class metaobject stands where a type specifier is expected: it designates
		// its own class, so subtypep consults the registry's ancestor sets exactly as
		// the name spelling does, and typep tests the value against it. `class' is a
		// real class (the superclass of standard-class), so (typep x 'class) is the
		// metaobject predicate -- mito's contains-class-or-subclasses idiom.
		assertThat(evalMulti("""
				(defclass mo-super () ())
				(defclass mo-sub (mo-super) ())
				(list (subtypep (find-class 'mo-sub) (find-class 'mo-super))
				      (subtypep (find-class 'mo-super) (find-class 'mo-sub))
				      (subtypep (find-class 'mo-sub) 'mo-super)
				      (subtypep 'mo-sub (find-class 'mo-super))
				      (typep (find-class 'mo-sub) 'class)
				      (typep (find-class 'mo-sub) 'standard-class)
				      (typep 42 'class)
				      (typep (make-instance 'mo-sub) (find-class 'mo-super))
				      (typep (make-instance 'mo-super) (find-class 'mo-sub))
				      (typep 42 (find-class 'integer))
				      (typep 42 (find-class 't))
				      (typep (make-instance 'mo-sub) (find-class 't)))
				""").print()).isEqualTo("(T NIL T T T T NIL T NIL T T T)");
	}

	@Test
	void allocateInstanceAnswersAnAllSlotsUnboundInstance() {
		// allocate-instance takes the class metaobject (or its name) and answers an
		// instance with EVERY slot unbound -- no initforms, no initialize-instance;
		// the dao-from-fields idiom fills the slots by setf slot-value afterwards.
		assertThat(evalMulti("""
				(defclass ai-pt () ((x :initarg :x :initform 7) (y :initarg :y)))
				(let ((p (allocate-instance (find-class 'ai-pt))))
				  (list (typep p 'ai-pt)
				        (eq (class-of p) (find-class 'ai-pt))
				        (slot-boundp p 'x)
				        (slot-boundp p 'y)
				        (progn (setf (slot-value p 'x) 10) (slot-value p 'x))
				        (let ((q (allocate-instance 'ai-pt))) (slot-boundp q 'x))))
				""").print()).isEqualTo("(T T NIL NIL 10 NIL)");
	}

	@Test
	void allocateInstanceRejectsNonClosClasses() {
		// Only registered CLOS classes allocate: a built-in class and a struct class
		// signal, like CL's built-in-class behavior; extra initargs are ignored.
		assertThat(evalMulti("""
				(defclass ai-ok () ((v :initarg :v)))
				(defstruct ai-node value)
				(list (handler-case (allocate-instance (find-class 'integer)) (error (e) :signaled))
				      (handler-case (allocate-instance 'ai-node) (error (e) :signaled))
				      (typep (allocate-instance (find-class 'ai-ok) :v 1 :junk 2) 'ai-ok))
				""").print()).isEqualTo("(:SIGNALED :SIGNALED T)");
	}

	@Test
	void setfSlotValueWithARuntimeSlotName() {
		// (setf (slot-value obj name-var) v) -- postmodern's dao-from-fields writes
		// every column through a runtime slot name. The write dispatches like the
		// runtime-name read and answers the stored value.
		assertThat(evalMulti("""
				(defclass rs-pt () ((x :initarg :x :initform 0) (y :initform 1)))
				(let ((p (make-instance 'rs-pt))
				      (n 'y))
				  (list (setf (slot-value p n) 42) (slot-value p 'y) (slot-value p n)))
				""").print()).isEqualTo("(42 42 42)");
	}

	@Test
	void makeInstanceIsAFirstClassFunction() {
		// (apply #'make-instance class initargs) is the postmodern make-dao idiom: the
		// class arrives at RUN time (a metaobject or a name), which the static
		// make-instance expansion cannot serve -- the function value routes through
		// the runtime-class construction path instead.
		assertThat(evalMulti("""
				(defclass fv-pt () ((x :initarg :x :initform 0)))
				(defclass fv-line () ((n :initarg :n)))
				(let ((a (apply #'make-instance (list (find-class 'fv-pt) :x 7)))
				      (b (funcall #'make-instance 'fv-line :n 3)))
				  (list (slot-value a 'x) (slot-value b 'n) (typep a 'fv-pt) (typep b 'fv-line)))
				""").print()).isEqualTo("(7 3 T T)");
	}

	@Test
	void aCapturedGenericFunctionValueSeesLaterMethods() {
		// #'generic is late-bound: defmethod REDEFINES the dispatcher under the name,
		// so a snapshot captured before a later-loaded system adds its method would
		// silently miss it -- dbi stashes #'disconnect in its connection pool at load
		// time and dbd-postgres defines the dbd-postgres-connection method afterwards.
		assertThat(evalMulti("""
				(defgeneric poke (x))
				(defmethod poke ((x integer)) :int)
				(defvar *captured* #'poke)
				(defclass zebra () ())
				(defmethod poke ((x zebra)) :zebra)
				(list (funcall *captured* 1) (funcall *captured* (make-instance 'zebra)))
				""").print()).isEqualTo("(:INT :ZEBRA)");
	}

	@Test
	void initProtocolGenericsAreSharedAcrossPackages() {
		// initialize-instance / shared-initialize / reinitialize-instance are CL
		// symbols with ONE generic each, like print-object: a method defined inside
		// any (:use :cl) package joins the same generic. Before that classification a
		// package minted its own generic (CL-PPCRE::INITIALIZE-INSTANCE), so
		// make-instance found the first plain-name match and another package's
		// shared-initialize hooks (postmodern's sql-name computation on its slot
		// metaobjects) silently never ran.
		assertThat(evalMulti("""
				(defpackage :ip-lib (:use :cl) (:export :ip-str :ip-str-len))
				(in-package :ip-lib)
				(defclass ip-str () ((len :initform :unset :accessor ip-str-len)))
				(defmethod initialize-instance :after ((s ip-str) &rest initargs)
				  (declare (ignore initargs))
				  (setf (ip-str-len s) 0))
				(defpackage :ip-dao (:use :cl))
				(in-package :ip-dao)
				(defclass ip-slot () ((sql-name :reader ip-sql-name)))
				(defmethod shared-initialize :after ((slot ip-slot) slot-names &key name &allow-other-keys)
				  (declare (ignore slot-names))
				  (setf (slot-value slot 'sql-name) (string-downcase (symbol-name name))))
				(in-package :cl-user)
				(list (ip-lib:ip-str-len (make-instance 'ip-lib:ip-str))
				      (ip-dao::ip-sql-name (make-instance 'ip-dao::ip-slot :name 'ip-dao::user-id)))
				""").print()).isEqualTo("(0 \"user-id\")");
	}

	@Test
	void defclassMetaclassRunsTheClassDefinitionProtocol() {
		// The postmodern dao-class shape end to end, WITHOUT the closer-mop shim (the
		// protocol is self-contained): the metaclass instance is what find-class and
		// class-of answer; the unknown class options arrive as initargs whose value is
		// the option tail (:table-name -> ("users"), parsed by the user's
		// shared-initialize :before hook); unknown slot options pick the
		// direct-slot-definition class; compute-effective-slot-definition's
		// call-next-method runs the effective-slot instantiation INSIDE the user's
		// *direct-column-slot* binding (the :initform of the effective slot class reads
		// it); finalize-inheritance :after fires eagerly at definition time; instances
		// of the class stay ordinary.
		assertThat(evalMulti("""
				(defvar *direct-column-slot* nil)
				(defclass mc-dao-class (standard-class)
				  ((direct-keys :initarg :keys :initform nil :accessor mc-direct-keys)
				   (table-name)))
				(defclass mc-direct-column-slot (closer-mop:standard-direct-slot-definition)
				  ((col-type :initarg :col-type :accessor mc-column-type)))
				(defclass mc-effective-column-slot (closer-mop:standard-effective-slot-definition)
				  ((direct-slot :initform *direct-column-slot* :reader mc-slot-column)))
				(defmethod closer-mop:validate-superclass ((class mc-dao-class) (super standard-class)) t)
				(defmethod shared-initialize :before ((class mc-dao-class) slot-names
				                                      &key table-name &allow-other-keys)
				  (if table-name
				      (setf (slot-value class 'table-name) (car table-name))
				      (slot-makunbound class 'table-name)))
				(defmethod closer-mop:direct-slot-definition-class ((class mc-dao-class) &rest initargs
				                                                    &key col-type &allow-other-keys)
				  (if col-type (find-class 'mc-direct-column-slot) (call-next-method)))
				(defmethod closer-mop:compute-effective-slot-definition ((class mc-dao-class) name dsds)
				  (let ((*direct-column-slot* (find-if (lambda (s) (typep s 'mc-direct-column-slot)) dsds)))
				    (call-next-method)))
				(defmethod closer-mop:effective-slot-definition-class ((class mc-dao-class) &rest initargs)
				  (if *direct-column-slot* (find-class 'mc-effective-column-slot) (call-next-method)))
				(defvar *mc-finalized* nil)
				(defmethod closer-mop:finalize-inheritance :after ((class mc-dao-class))
				  (setq *mc-finalized* (cons (%obj-ref class 0) *mc-finalized*)))
				(defclass mc-user ()
				  ((id :col-type integer :initarg :id :accessor mc-user-id)
				   (note :initarg :note :initform "n/a"))
				  (:metaclass mc-dao-class)
				  (:table-name "users")
				  (:keys id))
				(list (let ((c (find-class 'mc-user)))
				        (list (%obj-ref c 0)
				              (typep c 'mc-dao-class)
				              (eq (class-of c) (find-class 'mc-dao-class))
				              (slot-value c 'table-name)
				              (mc-direct-keys c)
				              *mc-finalized*
				              (%obj-ref c 4)))
				      (let ((u (make-instance 'mc-user :id 7)))
				        (list (mc-user-id u) (slot-value u 'note) (eq (class-of u) (find-class 'mc-user))))
				      (mapcar (lambda (s)
				                (list (%obj-ref s 0)
				                      (if (typep s 'mc-effective-column-slot)
				                          (mc-column-type (mc-slot-column s))
				                          :plain)))
				              (%obj-ref (find-class 'mc-user) 3)))
				""").print())
			.isEqualTo("((MC-USER T T \"users\" (ID) (MC-USER) T) (7 \"n/a\" T) ((ID INTEGER) (NOTE :PLAIN)))");
	}

	@Test
	void defclassMetaclassSharedInitializeBeforeRunsBeforeInitargFilling() {
		// Upstream postmodern's dao-class shared-initialize :before RESETS its
		// direct-keys slot and relies on CL's initialization order (:before methods run
		// BEFORE the initargs fill the slots) to see the reset overwritten by the :keys
		// class option. The static model fills slots in the constructor first, so
		// initarg-SUPPLIED slots are re-filled after the initialization generic returns
		// (for classes a :before method specializes); a slot without a declared
		// :initarg (table-name) keeps the :before's write. The #'make-instance leg
		// covers the runtime-class path next to the defclass driver's.
		assertThat(evalMulti("""
				(defclass mcb-meta (standard-class)
				  ((ks :initarg :keys :initform nil :reader mcb-ks)
				   (table-name)))
				(defmethod shared-initialize :before ((c mcb-meta) slot-names
				                                      &key table-name &allow-other-keys)
				  (setf (slot-value c 'ks) nil)
				  (if table-name
				      (setf (slot-value c 'table-name) (car table-name))
				      (slot-makunbound c 'table-name)))
				(defclass mcb-user () ((id)) (:metaclass mcb-meta) (:keys id) (:table-name "users"))
				(let ((c (find-class 'mcb-user))
				      (m (apply #'make-instance (list 'mcb-meta :name 'raw :keys '(k)))))
				  (list (mcb-ks c) (slot-value c 'table-name) (mcb-ks m)))
				""").print()).isEqualTo("((ID) \"users\" (K))");
	}

	@Test
	void defclassMetaclassEnsureClassUsingClassAndInitargMunging() {
		// The mito metaclass shape WITHOUT mito (todo-246): the driver routes through
		// ensure-class-using-class, so the user :around on it fires on REdefinition
		// only (0 then 1); an initialize-instance :around's MUNGED initargs take
		// effect because the fill runs INSIDE the chain (the injected dao-class
		// superclass, the :extra initarg pushed into a slot-definition's :initargs);
		// custom direct/effective slot classes carry an extra col-type slot copied by
		// the user's compute-effective-slot-definition :around; effective slots carry
		// the initform THUNK at index 5 (slot-definition-initfunction); redefining
		// the same class name reinitializes the SAME metaobject in place; and
		// %class-direct-subclasses sees the runtime-injected superclass edge.
		assertThat(evalMulti(am.ik.rontolisp.MopWideningFixture.MITO_SHAPE_SOURCE + """
				(list *mt-first*
				      (let ((c (find-class 'mt-user)))
				        (list *mt-ecuc*
				              (slot-value c 'table-name)
				              (mapcar (lambda (s) (%obj-ref s 0)) (%obj-ref c 2))
				              (slot-value c 'col-count)
				              (mt-user-id (make-instance 'mt-user :id 1)))))
				""").print()).isEqualTo(am.ik.rontolisp.MopWideningFixture.MITO_SHAPE_EXPECTED);
	}

	@Test
	void defclassMetaclassRequiresARegisteredMetaclass() {
		// :metaclass must name a class inheriting standard-class, defined first -- the
		// static model's definition-time contract.
		assertThatThrownBy(() -> evalMulti("""
				(defclass mc-bad () ((x)) (:metaclass mc-no-such-metaclass))
				""")).hasMessageContaining(":metaclass must name a class inheriting standard-class");
		assertThatThrownBy(() -> evalMulti("""
				(defclass mc-plain () ())
				(defclass mc-bad2 () ((x)) (:metaclass mc-plain))
				""")).hasMessageContaining(":metaclass must name a class inheriting standard-class");
	}

	@Test
	void compileCoercesALambdaExpressionToAFunction() {
		// (compile nil '(lambda ...)) answers the function (null lexical environment);
		// (compile 'name '(lambda ...)) also installs it and answers the name, per CL.
		assertThat(evalMulti("""
				(list (funcall (compile nil '(lambda (x) (* x x))) 7)
				      (compile 'cmp-inc '(lambda (x) (+ x 1)))
				      (cmp-inc 41))
				""").print()).isEqualTo("(49 CMP-INC 42)");
	}

	@Test
	void compileInterceptsDefinitionTimeMethodConstruction() {
		// The build-dao-methods idiom (postmodern table.lisp): a finalize-inheritance
		// :after hook funcalls (compile nil `(lambda () ,code)) where code defines
		// methods whose specializers are the class METAOBJECT spliced as a literal --
		// plus an (eql (class-name ,class)) form -- and whose bodies close over let*
		// bindings and labels functions computed from the metaobject. The interception
		// folds the metaobject literals (specializers to names, expressions to
		// (find-class 'name), the eql form to the literal name) and evaluates the body
		// in place; the driver registers the metaobject BEFORE finalization, so the
		// find-class lookups inside answer the metaclass instance.
		assertThat(evalMulti("""
				(defclass ce-tbl-class (standard-class)
				  ((table-name :initform nil)))
				(defmethod shared-initialize :before ((class ce-tbl-class) slot-names
				                                      &key table-name &allow-other-keys)
				  (if table-name (setf (slot-value class 'table-name) (car table-name)) nil))
				(defgeneric ce-row-tag (obj))
				(defgeneric ce-fetch-row (type key))
				(defun ce-eval (code)
				  (funcall (compile nil (list 'lambda nil code))))
				(defun ce-build-methods (class)
				  (ce-eval
				   `(let* ((tname (slot-value ,class 'table-name)))
				      (labels ((prefix (s) (concatenate 'string tname ":" s)))
				        (defmethod ce-row-tag ((object ,class))
				          (prefix "row"))
				        (defmethod ce-fetch-row ((type (eql (class-name ,class))) key)
				          (prefix key))))))
				(defmethod closer-mop:finalize-inheritance :after ((class ce-tbl-class))
				  (ce-build-methods class))
				(defclass ce-user ()
				  ((id :initarg :id))
				  (:metaclass ce-tbl-class)
				  (:table-name "users"))
				(list (ce-row-tag (make-instance 'ce-user :id 1))
				      (ce-fetch-row 'ce-user "k7"))
				""").print()).isEqualTo("(\"users:row\" \"users:k7\")");
	}

	@Test
	void closerCommonLispPackageServesTheDaoPackageShape() {
		// (:use :closer-common-lisp) -- postmodern's DAO package -- sees cl AND the
		// closer-mop overlay; the qualified c2cl spellings resolve to the home
		// packages, so the shim defuns serve them.
		assertThat(evalMulti("""
				(asdf:load-system "closer-mop")
				(defpackage :ccl-probe (:use :closer-common-lisp) (:export :probe))
				(in-package :ccl-probe)
				(defclass ccl-pt () ((x :initarg :x) (y :initarg :y)))
				(defun probe ()
				  (let ((c (find-class 'ccl-pt)))
				    (list (classp c)
				          (mapcar #'slot-definition-name (class-slots c))
				          (c2cl:class-name c)
				          (car (c2cl:list 1 2)))))
				(in-package :cl-user)
				(ccl-probe:probe)
				""").print()).isEqualTo("(T (X Y) CCL-PROBE::CCL-PT 1)");
	}

	@Test
	void classDesignatorKeepsTheTagView() {
		// The internal %class-designator is the pre-migration class-of: instance tags
		// and built-in type name symbols.
		assertThat(evalMulti("""
				(defclass cd-pt () ())
				(defstruct cd-node)
				(list (%class-designator (make-instance 'cd-pt))
				      (%class-designator (make-cd-node))
				      (%class-designator 42)
				      (%class-designator "s"))
				""").print()).isEqualTo("(%class-CD-PT %struct-CD-NODE INTEGER STRING)");
	}

	@Test
	void classSlotDefsAcceptsAClassMetaobjectDesignator() {
		// A metaobject designates through its name slot, so the pre-migration idiom
		// (%class-slot-defs (class-of x)) keeps answering the (name type) pairs.
		assertThat(evalMulti("""
				(defclass csm-p () ((name :initarg :name) (age :type integer)))
				(list (%class-slot-defs (class-of (make-instance 'csm-p)))
				      (%class-slot-defs (%class-designator (make-instance 'csm-p)))
				      (%class-slot-defs (find-class 'integer)))
				""").print()).isEqualTo("(((NAME T) (AGE INTEGER)) ((NAME T) (AGE INTEGER)) NIL)");
	}

	@Test
	void changeClassMutatesTheInstanceInPlace() {
		assertThat(evalMulti("""
				(defclass cc-conn () ((host :initarg :host :accessor cc-host)))
				(defclass cc-pooled (cc-conn) ((kind :initarg :kind :accessor cc-kind :initform :none)))
				(let* ((c (make-instance 'cc-conn :host "db")) (alias c))
				  (change-class c 'cc-pooled :kind :shared)
				  (list (eq c alias) (type-of alias) (cc-host alias) (cc-kind alias)))
				""").print()).isEqualTo("(T CC-POOLED \"db\" :SHARED)");
	}

	@Test
	void withAccessorsReadsAndWritesThroughTheAccessors() {
		assertThat(evalMulti("""
				(defclass wa-pt () ((x :initarg :x :accessor wa-x) (y :initarg :y :accessor wa-y)))
				(let ((p (make-instance 'wa-pt :x 3 :y 4)))
				  (with-accessors ((x wa-x) (y wa-y)) p (setf x (+ x y)))
				  (list (wa-x p) (wa-y p)))
				""").print()).isEqualTo("(7 4)");
	}

	@Test
	void withSlotsResolvesDefstructSlots() {
		assertThat(evalMulti("""
				(defstruct ws-parser (pos 0) (text "ab"))
				(let ((p (make-ws-parser)))
				  (with-slots (pos text) p (incf pos) (list pos text)))
				""").print()).isEqualTo("(1 \"ab\")");
	}

	@Test
	void printObjectIsConsultedByThePrinter() {
		assertThat(evalMulti("""
				(defstruct po-node value)
				(defmethod print-object ((n po-node) stream)
				  (print-unreadable-object (n stream :type t) (princ (po-node-value n) stream)))
				(list (princ-to-string (make-po-node :value 42))
				      (format nil "~a|~s" (make-po-node :value 1) (make-po-node :value 2)))
				""").print()).isEqualTo("(\"#<PO-NODE 42>\" \"#<PO-NODE 1>|#<PO-NODE 2>\")");
	}

	@Test
	void conditionReportRendersUnderPrincButNotUnderPrin1() {
		assertThat(evalMulti("""
				(define-condition cr-lam (error) ((msg :initarg :msg :reader cr-msg))
				  (:report (lambda (c s) (format s "cr-lam: ~a" (cr-msg c)))))
				(define-condition cr-str (error) () (:report "fixed text"))
				(handler-case (error 'cr-lam :msg "boom")
				  (error (e) (list (format nil "~a" e) (format nil "~s" e)
				                   (princ-to-string (make-condition 'cr-str)))))
				""").print()).isEqualTo("(\"cr-lam: boom\" \"#<CR-LAM :MSG \\\"boom\\\">\" \"fixed text\")");
	}

	@Test
	void conditionReportIsInheritedByASubtypeThatDoesNotOverrideIt() {
		assertThat(evalMulti("""
				(define-condition cri-base (error) ((n :initarg :n :reader cri-n))
				  (:report (lambda (c s) (format s "base ~a" (cri-n c)))))
				(define-condition cri-sub (cri-base) ())
				(handler-case (error 'cri-sub :n 3) (error (e) (princ-to-string e)))
				""").print()).isEqualTo("\"base 3\"");
	}

	@Test
	void simpleConditionFamilyReportsThroughFormatControlAndArguments() {
		assertThat(evalMulti("""
				(define-condition cr-pg (simple-warning) ())
				(list (princ-to-string
				        (make-condition 'cr-pg :format-control "pg ~A/~A" :format-arguments (list 1 2)))
				      (handler-case (error "plain ~a" 7) (error (e) (princ-to-string e))))
				""").print()).isEqualTo("(\"pg 1/2\" \"plain 7\")");
	}

	@Test
	void aRuntimeControlStringDatumRendersItsFormatArguments() {
		assertThat(evalMulti("""
				(list (handler-case (error "lit ~a-~a" 1 2) (error (e) (princ-to-string e)))
				      (let ((c "~a-~a"))
				        (handler-case (error c 1 2) (error (e) (princ-to-string e))))
				      (let ((c "PostgreSQL warning: ~A~@[~%~A~]"))
				        (handler-case (error c "relation already exists, skipping" nil)
				          (error (e) (princ-to-string e))))
				      (let ((c "sig ~a/~a"))
				        (handler-case (signal c 3 4) (condition (e) (princ-to-string e)))))
				""").print())
			.isEqualTo("(\"lit 1-2\" \"1-2\" \"PostgreSQL warning: relation already exists, skipping\" \"sig 3/4\")");
	}

	@Test
	void aRuntimeControlStringDatumRendersItsFormatArgumentsUnderWarn() {
		// warn's line goes to standard ERROR, so it needs a capture of its own.
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(err));
		try {
			new LispEvaluator(new PrintStream(new ByteArrayOutputStream()))
				.eval(LispReader.readFromString("(let ((c \"rt ~a/~a\")) (warn c 1 2))"));
		}
		finally {
			System.setErr(oldErr);
		}
		assertThat(err.toString()).contains("WARNING: rt 1/2");
	}

	@Test
	void warnRendersTheFormatControlArgumentsOfItsCondition() {
		// warn's line goes to standard ERROR, so it needs a capture of its own.
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(err));
		try {
			new LispEvaluator(new PrintStream(new ByteArrayOutputStream())).eval(LispReader
				.readFromString("(warn 'simple-warning :format-control \"sw ~A/~A\" :format-arguments (list 1 2))"));
		}
		finally {
			System.setErr(oldErr);
		}
		assertThat(err.toString()).contains("WARNING: sw 1/2");
	}

	@Test
	void aPrintObjectMethodStillWinsOverTheConditionReport() {
		assertThat(evalMulti("""
				(define-condition cr-po (error) () (:report "report text"))
				(defmethod print-object ((c cr-po) s) (format s "PO"))
				(list (princ-to-string (make-condition 'cr-po)) (prin1-to-string (make-condition 'cr-po)))
				""").print()).isEqualTo("(\"PO\" \"PO\")");
	}

	@Test
	void aConditionWithNoReportKeepsTheGenericInstanceRendering() {
		assertThat(evalMulti("""
				(define-condition cr-bare (error) ((v :initarg :v)))
				(princ-to-string (make-condition 'cr-bare :v 1))
				""").print()).isEqualTo("\"#<CR-BARE :V 1>\"");
	}

	@Test
	void defineConditionDefaultInitargsReachTheTypedSignal() {
		assertThat(evalMulti("""
				(define-condition di-err (error) ((v :initarg :v :reader di-v))
				  (:default-initargs :v 7)
				  (:report (lambda (c s) (format s "di-err: ~a" (di-v c)))))
				(handler-case (error 'di-err) (di-err (e) (di-v e)))
				""").print()).isEqualTo("7");
	}

	@Test
	void defclassMultipleInheritanceMergesSlotsAcrossSupers() {
		assertThat(evalMulti("""
				(defclass mi-a () ((x :initarg :x :accessor mi-x)))
				(defclass mi-b () ((y :initarg :y :accessor mi-y)))
				(defclass mi-c (mi-a mi-b) ((z :initarg :z :accessor mi-z)))
				(let ((c (make-instance 'mi-c :x 1 :y 2 :z 3)))
				  (list (mi-x c) (mi-y c) (mi-z c)))
				""").print()).isEqualTo("(1 2 3)");
	}

	@Test
	void defclassMultipleInheritanceSecondSuperAccessorsWriteTheRightSlot() {
		// mi2-b's slot y sits at index 0 in mi2-b but at index 1 in mi2-c, so the
		// subclass must carry an overriding accessor method; without it mi2-b's
		// method would read/write mi2-c's x slot.
		assertThat(evalMulti("""
				(defclass mi2-a () ((x :initarg :x :accessor mi2-x)))
				(defclass mi2-b () ((y :initarg :y :accessor mi2-y)))
				(defclass mi2-c (mi2-a mi2-b) ())
				(let ((b (make-instance 'mi2-b :y 7))
				      (c (make-instance 'mi2-c :x 1 :y 2)))
				  (setf (mi2-y c) 9)
				  (list (mi2-y b) (mi2-x c) (mi2-y c)))
				""").print()).isEqualTo("(7 1 9)");
	}

	@Test
	void defclassDiamondInheritanceKeepsOneCopyOfTheSharedSlot() {
		assertThat(evalMulti("""
				(defclass di-base () ((v :initarg :v :accessor di-vv)))
				(defclass di-l (di-base) ((l :initarg :l :accessor di-ll)))
				(defclass di-r (di-base) ((r :initarg :r :accessor di-rr)))
				(defclass di-d (di-l di-r) ())
				(let ((d (make-instance 'di-d :v 1 :l 2 :r 3)))
				  (list (di-vv d) (di-ll d) (di-rr d) (length (%obj-slots d))))
				""").print()).isEqualTo("(1 2 3 3)");
	}

	@Test
	void defclassDiamondShadowedInitformFollowsClassPrecedence() {
		// Only di2-r re-declares v's initform; di2-r precedes di2-base in di2-d's
		// class precedence list, so its initform wins even though di2-l is the
		// first (layout-providing) superclass.
		assertThat(evalMulti("""
				(defclass di2-base () ((v :initform :base :reader di2-v)))
				(defclass di2-l (di2-base) ())
				(defclass di2-r (di2-base) ((v :initform :right)))
				(defclass di2-d (di2-l di2-r) ())
				(di2-v (make-instance 'di2-d))
				""").print()).isEqualTo(":RIGHT");
	}

	@Test
	void defclassMultipleInheritanceMethodDispatchFollowsLocalPrecedenceOrder() {
		assertThat(evalMulti("""
				(defclass lp-a () ())
				(defclass lp-b () ())
				(defgeneric lp-who (x))
				(defmethod lp-who ((x lp-a)) :a)
				(defmethod lp-who ((x lp-b)) :b)
				(defclass lp-ab (lp-a lp-b) ())
				(defclass lp-ba (lp-b lp-a) ())
				(list (lp-who (make-instance 'lp-ab)) (lp-who (make-instance 'lp-ba)))
				""").print()).isEqualTo("(:A :B)");
	}

	@Test
	void defclassMultipleInheritanceCallNextMethodChainsAcrossBothSupers() {
		assertThat(evalMulti("""
				(defclass cn-a () ())
				(defclass cn-b () ())
				(defclass cn-ab (cn-a cn-b) ())
				(defgeneric cn-trace (x))
				(defmethod cn-trace ((x cn-a)) (cons :a (if (next-method-p) (call-next-method) nil)))
				(defmethod cn-trace ((x cn-b)) (cons :b (if (next-method-p) (call-next-method) nil)))
				(defmethod cn-trace (x) (list :default))
				(cn-trace (make-instance 'cn-ab))
				""").print()).isEqualTo("(:A :B :DEFAULT)");
	}

	@Test
	void defclassMultipleInheritanceAncestrySatisfiesTypep() {
		assertThat(evalMulti("""
				(defclass ty-a () ())
				(defclass ty-b () ())
				(defclass ty-ab (ty-a ty-b) ())
				(let ((x (make-instance 'ty-ab)))
				  (list (typep x 'ty-a) (typep x 'ty-b) (typep x 'ty-ab)))
				""").print()).isEqualTo("(T T T)");
	}

	@Test
	void defclassCircularSuperclassesSignalInconsistentPrecedence() {
		// (c (a b)) and (d (b a)) are fine apart, but a class inheriting both has
		// no consistent precedence order for a and b.
		assertThatThrownBy(() -> evalMulti("""
				(defclass cy-a () ())
				(defclass cy-b () ())
				(defclass cy-c (cy-a cy-b) ())
				(defclass cy-d (cy-b cy-a) ())
				(defclass cy-e (cy-c cy-d) ())
				""")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("class precedence");
	}

	@Test
	void defclassUnsupportedSlotOptionSignals() {
		assertThatThrownBy(() -> eval("(defclass a () ((x :allocation :class)))"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("slot option :ALLOCATION is not supported");
	}

	@Test
	void makeInstanceTakesAComputedClassDesignator() {
		// A non-literal class expression routes through the runtime
		// %mop-make-instance (a name symbol or a class metaobject both work) -- dbi's
		// connect instantiates the class metaobject find-driver returned. A literal
		// quoted name keeps the static constructor expansion.
		assertThat(evalMulti("""
				(defclass a () ((v :initarg :v :reader a-v)))
				(setq n 'a)
				(list (a-v (make-instance n :v 1))
				      (a-v (make-instance (find-class 'a) :v 2)))
				""").print()).isEqualTo("(1 2)");
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
	void typepVectorWithPackedElementTypeMatchesOnlyPackedArrays() {
		// (vector (unsigned-byte 8)) names the SPECIALIZED byte vector: a general
		// vector or a string is not one (s-sql's sql-escape dispatches on this).
		assertThat(evalMulti("""
				(list (typep #("a" "b") '(vector (unsigned-byte 8)))
				      (typep (make-array 2 :element-type '(unsigned-byte 8)) '(vector (unsigned-byte 8)))
				      (typep "ab" '(vector (unsigned-byte 8)))
				      (typep #(1 2) '(vector (unsigned-byte 8)))
				      (typep (make-array 2 :element-type '(unsigned-byte 16)) '(vector (unsigned-byte 8))))
				""").print()).isEqualTo("(NIL T NIL NIL NIL)");
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
	void withSlotsBindsAWriteOnlyUnboundSlot() {
		// with-slots establishes bindings, it never reads: a body that only ASSIGNS a
		// slot declared without an :initform must not signal on entry. fast-io's
		// initialize-instance fills its buffer slot exactly this way.
		assertThat(evalMulti("""
				(defclass wsu-box () ((buffer)))
				(defmethod initialize-instance ((self wsu-box) &key)
				  (call-next-method)
				  (with-slots (buffer) self
				    (setf buffer (list 1 2))))
				(slot-value (make-instance 'wsu-box) 'buffer)
				""").print()).isEqualTo("(1 2)");
	}

	@Test
	void withSlotsStillSignalsWhenTheBodyReadsAnUnboundSlot() {
		// The entry-time fallback is boundness-guarded; the body's own reads are not --
		// they are the slot itself, and an unbound one signals like any slot-value.
		assertThat(evalMulti("""
				(defclass wsr-box () ((buffer)))
				(handler-case (with-slots (buffer) (make-instance 'wsr-box) buffer)
				  (unbound-slot (e) (cell-error-name e)))
				""").print()).isEqualTo("BUFFER");
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
	void loadTimeValueEvaluatesOncePerOccurrence() {
		// One occurrence, three calls: the value form runs once (the memo is keyed on
		// the occurrence, so a second occurrence of the same text still runs).
		assertThat(evalMulti("(defvar ltv-n 0)" + " (defun ltv-bump () (setq ltv-n (+ ltv-n 1)) ltv-n)"
				+ " (defun ltv-probe () (load-time-value (ltv-bump)))"
				+ " (defun ltv-other () (load-time-value (ltv-bump)))"
				+ " (list (ltv-probe) (ltv-probe) (ltv-probe) (ltv-other) ltv-n)")
			.print()).isEqualTo("(1 1 1 2 2)");
		// A nil result still counts as computed.
		assertThat(evalMulti(
				"(defvar ltv-m 0)" + " (defun ltv-nil () (load-time-value (progn (setq ltv-m (+ ltv-m 1)) nil)))"
						+ " (list (ltv-nil) (ltv-nil) ltv-m)")
			.print()).isEqualTo("(NIL NIL 1)");
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
	void usePackageMakesAPackagesExternalSymbolsVisibleUnqualified() {
		assertThat(evalMulti("""
				(defpackage #:greeter (:use #:cl) (:export #:hello))
				(in-package #:greeter)
				(defun hello () "hi")
				(defun secret () "shh")
				(in-package #:cl-user)
				(use-package '#:greeter)
				(hello)
				""").print()).isEqualTo("\"hi\"");
	}

	@Test
	void usePackageDoesNotInheritInternalSymbols() {
		assertThatThrownBy(() -> evalMulti("""
				(defpackage #:greeter2 (:use #:cl) (:export #:hello))
				(in-package #:greeter2)
				(defun secret () "shh")
				(in-package #:cl-user)
				(use-package '#:greeter2)
				(secret)
				""")).hasMessageContaining("SECRET");
	}

	@Test
	void usePackageAcceptsAListAndATargetPackageAtRuntime() {
		// A computed call: the resolver leaves it alone and the runtime function widens
		// the use list of the named package, in time for the forms read after it.
		assertThat(evalMulti("""
				(defpackage #:m1 (:use #:cl) (:export #:one))
				(defpackage #:m2 (:use #:cl) (:export #:two))
				(defpackage #:host (:use #:cl))
				(in-package #:m1)
				(defun one () 1)
				(in-package #:m2)
				(defun two () 2)
				(in-package #:cl-user)
				(use-package (list :m1 :m2) :host)
				(in-package #:host)
				(list (one) (two))
				""").print()).isEqualTo("(1 2)");
	}

	@Test
	void usePackageRejectsAnUnknownPackage() {
		assertThatThrownBy(() -> eval("(use-package :nosuch)")).hasMessageContaining("No such package: NOSUCH");
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
	void grayCharacterOutputStreamTakesTheWholeOutputProtocol() {
		// todo-252: a class defining ONLY stream-write-char -- full Gray's one
		// required method, and rove's indent-stream shape -- answers every output
		// operator, and the column protocol is what lets fresh-line decide.
		assertThat(evalMulti("""
				(defclass gw-col (rontolisp:fundamental-character-output-stream)
				  ((acc :initform "") (col :initform 0)))
				(defmethod rontolisp:stream-write-char ((s gw-col) c)
				  (setf (slot-value s 'acc) (concatenate 'string (slot-value s 'acc) (string c)))
				  (setf (slot-value s 'col) (if (char= c #\\Newline) 0 (+ (slot-value s 'col) 1)))
				  c)
				(defmethod rontolisp:stream-line-column ((s gw-col)) (slot-value s 'col))
				(let ((s (make-instance 'gw-col)))
				  (write-char #\\a s)
				  (write-string "bc" s)
				  (princ "-" s)
				  (prin1 :k s)
				  (fresh-line s)
				  (fresh-line s)
				  (terpri s)
				  (write-line "l" s)
				  (print 7 s)
				  (format s "f~a" 1)
				  (list (slot-value s 'acc) (force-output s) (finish-output s) (clear-output s) (close s)))
				""").print()).isEqualTo("(\"abc-:K\n\nl\n7\nf1\" NIL NIL NIL T)");
	}

	@Test
	void grayStreamWriteStringOnlyClassStillAnswersWriteCharAndTerpri() {
		// The other half of the todo-252 pair: the default stream-write-char hands
		// the one-character string to stream-write-string, so the shape every
		// existing program wrote (rontolisp's own broadcast stream, jzon's writer)
		// keeps working and gains the line operators.
		assertThat(evalMulti("""
				(defclass gw-str (rontolisp:fundamental-character-output-stream)
				  ((acc :initform "")))
				(defmethod rontolisp:stream-write-string ((s gw-str) str &optional start end)
				  (setf (slot-value s 'acc)
				        (concatenate 'string (slot-value s 'acc)
				                     (subseq str (or start 0) (or end (length str)))))
				  str)
				(let ((s (make-instance 'gw-str)))
				  (write-char #\\a s)
				  (terpri s)
				  (write-line "b" s)
				  (fresh-line s)
				  (slot-value s 'acc))
				""").print()).isEqualTo("\"a\nb\n\n\"");
	}

	@Test
	void grayShimStreamWriteCharOnlyClassAnswersTheOutputProtocol() {
		// The same widening through the portable spelling: rove's reporter stream
		// is (trivial-gray-stream-mixin fundamental-character-output-stream) with
		// stream-write-char and stream-line-column and nothing else.
		assertThat(evalMulti("""
				(asdf:load-system "trivial-gray-streams")
				(defclass gsw-ind (trivial-gray-streams:trivial-gray-stream-mixin
				                   trivial-gray-streams:fundamental-character-output-stream)
				  ((acc :initform "") (col :initform 0)))
				(defmethod trivial-gray-streams:stream-write-char ((s gsw-ind) c)
				  (setf (slot-value s 'acc) (concatenate 'string (slot-value s 'acc) (string c)))
				  (setf (slot-value s 'col) (if (char= c #\\Newline) 0 (+ (slot-value s 'col) 1)))
				  c)
				(defmethod trivial-gray-streams:stream-line-column ((s gsw-ind)) (slot-value s 'col))
				(let ((s (make-instance 'gsw-ind)))
				  (princ "hi" s)
				  (fresh-line s)
				  (fresh-line s)
				  (write-char #\\z s)
				  (finish-output s)
				  (slot-value s 'acc))
				""").print()).isEqualTo("\"hi\nz\"");
	}

	@Test
	void grayBroadcastStreamTakesTheLineOperators() {
		// The broadcast stream is prelude Lisp over this protocol, so widening the
		// protocol widened it too (.kb/gray-streams.md).
		assertThat(evalMulti("""
				(with-output-to-string (o)
				  (let ((b (make-broadcast-stream o)))
				    (princ "a" b)
				    (terpri b)
				    (write-line "b" b)
				    (print :c b)
				    (close b)))
				""").print()).isEqualTo("\"a\nb\n:C\n\"");
	}

	@Test
	void grayCloseStandsDownForAProgramThatDefinesACloseMethod() {
		// close is CL's own generic: a program that methods it owns the operator on
		// every backend, and the Gray default must not get in front of it.
		assertThat(evalMulti("""
				(defclass gc-s (rontolisp:fundamental-character-output-stream)
				  ((closed :initform nil)))
				(defmethod rontolisp:stream-write-char ((s gc-s) c) c)
				(defmethod close ((s gc-s) &key abort)
				  (declare (ignore abort))
				  (setf (slot-value s 'closed) :by-method))
				(let ((s (make-instance 'gc-s)))
				  (list (close s) (slot-value s 'closed)))
				""").print()).isEqualTo("(:BY-METHOD :BY-METHOD)");
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
	void grayBinaryStreamReadWriteBytesAndFilePosition() {
		// The read side of the Gray protocol (todo-235): the read-byte/write-byte
		// built-ins dispatch an INSTANCE stream to rontolisp:stream-read-byte /
		// -write-byte, the :eof answer translates through the eof-error-p/eof-value
		// contract, and file-position routes through the stream-file-position
		// generic (2-argument form through its (setf ...) writer generic).
		assertThat(evalMulti("""
				(defclass gbs-sink (rontolisp:fundamental-binary-output-stream)
				  ((bytes :initform nil)))
				(defmethod rontolisp:stream-write-byte ((s gbs-sink) byte)
				  (setf (slot-value s 'bytes) (cons byte (slot-value s 'bytes)))
				  byte)
				(defclass gbs-source (rontolisp:fundamental-binary-input-stream)
				  ((items :initarg :items) (pos :initform 0)))
				(defmethod rontolisp:stream-read-byte ((s gbs-source))
				  (let ((items (slot-value s 'items)) (pos (slot-value s 'pos)))
				    (if (>= pos (length items))
				        :eof
				        (progn (setf (slot-value s 'pos) (+ pos 1)) (nth pos items)))))
				(defmethod rontolisp:stream-file-position ((s gbs-source)) (slot-value s 'pos))
				(defmethod (setf rontolisp:stream-file-position) (position (s gbs-source))
				  (setf (slot-value s 'pos) position))
				(let ((out (make-instance 'gbs-sink))
				      (in (make-instance 'gbs-source :items (list 10 20 30))))
				  (write-byte 7 out)
				  (write-byte 250 out)
				  (list (reverse (slot-value out 'bytes))
				        (read-byte in)
				        (file-position in)
				        (progn (file-position in 0) (read-byte in))
				        (read-byte in nil :done)
				        (read-byte in nil :done)
				        (read-byte in nil :done)))
				""").print()).isEqualTo("((7 250) 10 1 10 20 30 :DONE)");
	}

	@Test
	void grayInputStreamReadCharReadLineAndSequenceDefaults() {
		// A character input class defining only stream-read-char: read-char
		// dispatches, read-line runs the protocol's default element loop (nil at
		// EOF, the built-in's lite default), and read-sequence/write-sequence use
		// the default element loops over stream-read-char/stream-write-char.
		assertThat(evalMulti("""
				(defclass gcs-source (rontolisp:fundamental-character-input-stream)
				  ((text :initarg :text) (pos :initform 0)))
				(defmethod rontolisp:stream-read-char ((s gcs-source))
				  (let ((text (slot-value s 'text)) (pos (slot-value s 'pos)))
				    (if (>= pos (length text))
				        :eof
				        (progn (setf (slot-value s 'pos) (+ pos 1)) (char text pos)))))
				(defclass gcs-sink (rontolisp:fundamental-character-output-stream)
				  ((acc :initform "")))
				(defmethod rontolisp:stream-write-string ((s gcs-sink) string &optional start end)
				  (setf (slot-value s 'acc) (concatenate 'string (slot-value s 'acc) string))
				  string)
				(defmethod rontolisp:stream-write-char ((s gcs-sink) c)
				  (rontolisp:stream-write-string s (string c)))
				(let ((in (make-instance 'gcs-source :text (format nil "ab~%cd")))
				      (buf (make-string 2))
				      (out (make-instance 'gcs-sink)))
				  (write-sequence "xy" out)
				  (list (read-char in)
				        (read-line in)
				        (read-line in)
				        (read-line in)
				        (read-line in nil :end)
				        (progn (setf (slot-value in 'pos) 0)
				               (list (read-sequence buf in) buf))
				        (slot-value out 'acc)))
				""").print()).isEqualTo("(#\\a \"b\" \"cd\" NIL :END (2 \"ab\") \"xy\")");
	}

	@Test
	void grayInputStreamPeekUnreadNoHangAndStreamQueries() {
		// The rest of the input protocol over the ONE required method
		// (stream-read-char): peek-char in all three peek-type forms, unread-char
		// through the protocol's own pushback, read-char-no-hang, and the two
		// stream queries a program did NOT take over -- open-stream-p (an instance
		// is open) and stream-element-type (character here, octets for a binary
		// base class).
		assertThat(evalMulti("""
				(defclass gin-source (rontolisp:fundamental-character-input-stream)
				  ((text :initarg :text) (pos :initform 0)))
				(defmethod rontolisp:stream-read-char ((s gin-source))
				  (let ((text (slot-value s 'text)) (pos (slot-value s 'pos)))
				    (if (>= pos (length text))
				        :eof
				        (progn (setf (slot-value s 'pos) (+ pos 1)) (char text pos)))))
				(defclass gin-bytes (rontolisp:fundamental-binary-input-stream) ())
				(let ((in (make-instance 'gin-source :text (format nil "ab~%  cd"))))
				  (list (peek-char nil in)
				        (read-char in)
				        (unread-char #\\a in)
				        (read-char in)
				        (read-char-no-hang in)
				        (read-line in)
				        (peek-char t in)
				        (peek-char #\\d in)
				        (read-line in)
				        (peek-char nil in nil :done)
				        (open-stream-p in)
				        (stream-element-type in)
				        (stream-element-type (make-instance 'gin-bytes))))
				""").print())
			.isEqualTo("(#\\a #\\a NIL #\\a #\\b \"\" #\\c #\\d \"d\" :DONE T CHARACTER (UNSIGNED-BYTE 8))");
	}

	@Test
	void grayInputStreamUnreadCharMethodOwnsThePushback() {
		// dexador's decoding-stream shape: the class defines stream-unread-char and
		// rewinds its OWN source, so the protocol's pushback cell is never written
		// and peek-char goes through the class's rewind instead.
		assertThat(evalMulti("""
				(defclass gin-rewind (rontolisp:fundamental-character-input-stream)
				  ((text :initarg :text) (pos :initform 0)))
				(defmethod rontolisp:stream-read-char ((s gin-rewind))
				  (let ((text (slot-value s 'text)) (pos (slot-value s 'pos)))
				    (if (>= pos (length text))
				        :eof
				        (progn (setf (slot-value s 'pos) (+ pos 1)) (char text pos)))))
				(defmethod rontolisp:stream-unread-char ((s gin-rewind) c)
				  (setf (slot-value s 'pos) (- (slot-value s 'pos) 1))
				  nil)
				(let ((in (make-instance 'gin-rewind :text "xyz")))
				  (list (peek-char nil in)
				        (slot-value in 'pos)
				        (read-char in)
				        (unread-char #\\x in)
				        (slot-value in 'pos)
				        (read-char in)))
				""").print()).isEqualTo("(#\\x 0 #\\x NIL 0 #\\x)");
	}

	@Test
	void unreadCharOnAStreamHandleSignals() {
		// No backend keeps a pushback a handle-based read would drain, so the
		// operator signals rather than dropping the character silently. Same
		// message on the compiled backends (LispMacroExpander.expandUnreadChar).
		assertThatThrownBy(() -> evalMulti("""
				(let ((s (make-string-input-stream "abc")))
				  (read-char s)
				  (unread-char #\\a s))
				""")).hasMessageContaining("UNREAD-CHAR is supported only on a Gray input stream");
	}

	@Test
	void grayShimBinaryInputStreamWithMixinAndSetfFilePosition() {
		// The trivial-gray-streams shim, circular-streams' exact class shape:
		// trivial-gray-stream-mixin plus fundamental-binary-input-stream, methods
		// on the PORTABLE generics, and a (setf stream-file-position) writer
		// delegated both directions.
		assertThat(evalMulti("""
				(asdf:load-system "trivial-gray-streams")
				(defclass mem-in (trivial-gray-streams:trivial-gray-stream-mixin
				                  trivial-gray-streams:fundamental-binary-input-stream)
				  ((items :initarg :items) (pos :initform 0)))
				(defmethod trivial-gray-streams:stream-read-byte ((s mem-in))
				  (let ((items (slot-value s 'items)) (pos (slot-value s 'pos)))
				    (if (>= pos (length items))
				        :eof
				        (progn (setf (slot-value s 'pos) (+ pos 1)) (nth pos items)))))
				(defmethod trivial-gray-streams:stream-file-position ((s mem-in))
				  (slot-value s 'pos))
				(defmethod (setf trivial-gray-streams:stream-file-position) (new-pos (s mem-in))
				  (setf (slot-value s 'pos) new-pos))
				(let ((in (make-instance 'mem-in :items (list 7 8 9))))
				  (list (read-byte in)
				        (file-position in)
				        (progn (file-position in 0) (read-byte in))
				        (read-byte in nil :eof-hit)
				        (read-byte in nil :eof-hit)
				        (read-byte in nil :eof-hit)))
				""").print()).isEqualTo("(7 1 7 8 9 :EOF-HIT)");
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

	@Test
	void packedIntVectorMakeArrayMasksAndReadsUnsigned() {
		// .kb/packed-integer-vectors.md: stores mask to the width, reads widen
		// unsigned, setf returns the value AS STORED.
		// let* sequencing keeps the program order-independent, matching the wasm test
		// (compiled list arguments evaluate right-to-left, .todo/014).
		assertThat(eval("""
				(let* ((a (make-array 4 :element-type '(unsigned-byte 8) :initial-element 7))
				       (stored (setf (aref a 1) 300))
				       (readback (aref a 1)))
				  (list stored readback (aref a 0) (length a) a))
				""").print()).isEqualTo("(44 44 7 4 #(7 44 7 7))");
		assertThat(eval("(make-array 3 :element-type '(unsigned-byte 16) :initial-contents '(1 70000 3))").print())
			.isEqualTo("#(1 4464 3)");
		assertThat(eval("""
				(let ((a (make-array 2 :element-type '(unsigned-byte 32))))
				  (setf (aref a 0) 4294967295)
				  (setf (aref a 1) 4294967296)
				  a)
				""").print()).isEqualTo("#(4294967295 0)");
	}

	@Test
	void packedIntVectorIntrospection() {
		assertThat(eval("""
				(let ((a (make-array 3 :element-type '(unsigned-byte 8))))
				  (list (array-element-type a) (arrayp a) (vectorp a) (array-dimensions a)
				        (typep a '(simple-array (unsigned-byte 8) (*)))))
				""").print()).isEqualTo("((UNSIGNED-BYTE 8) T T (3) T)");
		// A rank-n / fill-pointer / adjustable combination keeps the general boxed
		// representation (element type reads back t).
		assertThat(eval("(array-element-type (make-array '(2 2) :element-type '(unsigned-byte 8)))").print())
			.isEqualTo("T");
		assertThat(eval("(array-element-type (make-array 4 :element-type '(unsigned-byte 8) :fill-pointer 2))").print())
			.isEqualTo("T");
	}

	@Test
	void fillWritesEveryElementInRange() {
		// Destructive over every mutable sequence, like replace: the SAME object comes
		// back. chipz's code-length tables and salza2's bitstream reset both clear a
		// buffer this way, and (fill v 0) with :start/:end is the only shape they use.
		assertThat(evalMulti("""
				(let* ((a (make-array 5 :element-type '(unsigned-byte 8) :initial-element 9))
				       (same (eq a (fill a 300 :start 1 :end 4))))
				  (list a same (array-element-type a)))
				""").print()).isEqualTo("(#(9 44 44 44 9) T (UNSIGNED-BYTE 8))");
		assertThat(eval("(let ((g (make-array 4 :initial-element 'x))) (list (eq g (fill g 7)) g))").print())
			.isEqualTo("(T #(7 7 7 7))");
		assertThat(eval("(let ((l (list 1 2 3 4 5))) (fill l 0 :start 1 :end 3) l)").print()).isEqualTo("(1 0 0 4 5)");
		assertThat(eval("(fill (make-array 5 :element-type 'character :initial-element #\\a) #\\z :start 2)").print())
			.isEqualTo("\"aazzz\"");
		// As a first-class value, like #'replace.
		assertThat(eval("(funcall #'fill (make-array 2 :initial-element 1) 8)").print()).isEqualTo("#(8 8)");
	}

	@Test
	void makeArrayElementTypeResolvesADeftypeAlias() {
		// A zero-parameter deftype alias in :element-type selects the representation its
		// expansion designates -- the same one the literal spelling would, on all four
		// backends. salza2 allocates every buffer as :element-type 'octet, and getting a
		// general array of nil instead of a packed vector of 0 broke its match scanner
		// on a comparison against an element past the copied input.
		assertThat(evalMulti("""
				(deftype octet () '(unsigned-byte 8))
				(deftype byte-buffer () 'octet)
				(deftype real-double () 'double-float)
				(deftype char-buf () 'character)
				(let ((a (make-array 3 :element-type 'octet))
				      (b (make-array 2 :element-type 'byte-buffer :initial-contents '(1 300)))
				      (c (make-array 2 :element-type 'real-double))
				      (s (make-array 3 :element-type 'char-buf :initial-element #\\x)))
				  (list (array-element-type a) (aref a 0) b (array-element-type c) (aref c 0) (stringp s) s))
				""").print()).isEqualTo("((UNSIGNED-BYTE 8) 0 #(1 44) DOUBLE-FLOAT 0.0 T \"xxx\")");
		// A name with no deftype behind it is left alone, and a self-referential one
		// terminates on the hop bound instead of spinning: both keep the general array.
		assertThat(eval("(array-element-type (make-array 2 :element-type 'not-a-type))").print()).isEqualTo("T");
		assertThat(evalMulti("""
				(deftype loopy () 'loopy)
				(array-element-type (make-array 2 :element-type 'loopy))
				""").print()).isEqualTo("T");
	}

	@Test
	void packedIntVectorSubseqCopySeqReplacePreserveThePackedType() {
		assertThat(eval("""
				(let* ((a (make-array 4 :element-type '(unsigned-byte 8) :initial-contents '(9 8 7 6)))
				       (s (subseq a 1 3)))
				  (setf (aref s 0) 300)
				  (list s (array-element-type s) a))
				""").print()).isEqualTo("(#(44 7) (UNSIGNED-BYTE 8) #(9 8 7 6))");
		assertThat(eval("""
				(let* ((a (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(1 2 3)))
				       (c (copy-seq a)))
				  (list c (array-element-type c)))
				""").print()).isEqualTo("(#(1 2 3) (UNSIGNED-BYTE 8))");
		// replace mask-stores element-wise into a packed target.
		assertThat(eval("""
				(let ((dst (make-array 3 :element-type '(unsigned-byte 8)))
				      (src #(300 2 3)))
				  (replace dst src)
				  dst)
				""").print()).isEqualTo("#(44 2 3)");
	}

	@Test
	void packedIntVectorReaderLiteralAndRowMajor() {
		// ironclad's #N@(...) table syntax reads packed for the 8/16/32 widths.
		assertThat(eval("(list #8@(1 2 300) (array-element-type #32@(1 2)))").print())
			.isEqualTo("(#(1 2 44) (UNSIGNED-BYTE 32))");
		assertThat(eval("""
				(let ((a #8@(1 2 3)))
				  (%row-major-aset a 1 999)
				  (list (row-major-aref a 1) a))
				""").print()).isEqualTo("(231 #(1 231 3))");
	}

	@Test
	void packedIntVectorRejectsNonIntegerStoresAndFillPointerSurface() {
		assertThatThrownBy(() -> eval("(setf (aref (make-array 2 :element-type '(unsigned-byte 8)) 0) 1.5)"))
			.hasMessageContaining("integer");
		assertThatThrownBy(() -> eval("(aref (make-array 2 :element-type '(unsigned-byte 8)) 5)"))
			.hasMessageContaining("out of range");
		assertThatThrownBy(() -> eval("(vector-push 1 (make-array 2 :element-type '(unsigned-byte 8)))"))
			.hasMessageContaining("packed integer vector");
	}

	@Test
	void concurrentFirstCallsOfALazyLoadedLibraryAllResolve() throws Exception {
		// A Lisp-source library (url.lisp here, through rontolisp:query-param) is
		// evaluated into the global environment on its FIRST resolution, and http-handler
		// puts one virtual thread per request on that environment -- so the first burst
		// of
		// requests resolves the same library from many threads at once. The loader must
		// not publish "loaded" before the definitions are installed, or the threads that
		// arrive in between see "The function ... is undefined" (.todo/193: what a 12-way
		// POST burst against examples/db/postgres-web.lisp lost requests to). Several
		// rounds with a FRESH evaluator each: the load is a cold library exactly once per
		// evaluator, and the window only opens once the JIT has warmed the loader up.
		int rounds = 5;
		int threads = 16;
		java.util.List<String> results = new java.util.ArrayList<>();
		for (int round = 0; round < rounds; round++) {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
			java.util.concurrent.CyclicBarrier startTogether = new java.util.concurrent.CyclicBarrier(threads);
			java.util.List<java.util.concurrent.Callable<String>> calls = new java.util.ArrayList<>();
			for (int i = 0; i < threads; i++) {
				calls.add(() -> {
					startTogether.await();
					try {
						return evaluator.eval(LispReader.readFromString("(rontolisp:query-param \"q=tok\" \"q\")"))
							.print();
					}
					catch (RuntimeException ex) {
						return "FAILED: " + ex.getMessage();
					}
				});
			}
			try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
				for (var result : pool.invokeAll(calls)) {
					results.add(result.get());
				}
			}
		}
		assertThat(results).allSatisfy(value -> assertThat(value).isEqualTo("\"tok\""));
	}

	@Test
	void evalShortFormMethodCombinationProgn() {
		// Every applicable progn-qualified method runs, most specific first; the value
		// is the LAST one's, as CL's progn combination says.
		assertThat(evalMulti("""
				(defclass mc-base () ())
				(defclass mc-leaf (mc-base) ())
				(defgeneric mc-trace (x) (:method-combination progn))
				(defmethod mc-trace progn ((x mc-base)) (push :base *mc-log*) :base)
				(defmethod mc-trace progn ((x mc-leaf)) (push :leaf *mc-log*) :leaf)
				(defvar *mc-log* nil)
				(list (mc-trace (make-instance 'mc-leaf)) (reverse *mc-log*))
				""").print()).isEqualTo("(:BASE (:LEAF :BASE))");
	}

	@Test
	void evalShortFormMethodCombinationMostSpecificLast() {
		// yason's encode-slots order: the LEAST specific method runs first, so a
		// subclass's slots are written after its superclass's.
		assertThat(evalMulti("""
				(defvar *mc-log* nil)
				(defclass mc-base () ())
				(defclass mc-leaf (mc-base) ())
				(defgeneric mc-trace (x) (:method-combination progn :most-specific-last))
				(defmethod mc-trace progn ((x mc-base)) (push :base *mc-log*))
				(defmethod mc-trace progn ((x mc-leaf)) (push :leaf *mc-log*))
				(mc-trace (make-instance 'mc-leaf))
				(reverse *mc-log*)
				""").print()).isEqualTo("(:BASE :LEAF)");
	}

	@Test
	void evalShortFormMethodCombinationOperators() {
		// The rest of the CLHS short-form family over the same mechanism.
		assertThat(evalMulti("""
				(defclass mc-base () ())
				(defclass mc-leaf (mc-base) ())
				(defgeneric mc-sum (x) (:method-combination +))
				(defmethod mc-sum + ((x mc-base)) 1)
				(defmethod mc-sum + ((x mc-leaf)) 100)
				(defgeneric mc-names (x) (:method-combination list))
				(defmethod mc-names list ((x mc-base)) 'base)
				(defmethod mc-names list ((x mc-leaf)) 'leaf)
				(defgeneric mc-all (x) (:method-combination and))
				(defmethod mc-all and ((x mc-base)) t)
				(defmethod mc-all and ((x mc-leaf)) nil)
				(defgeneric mc-app (x) (:method-combination append))
				(defmethod mc-app append ((x mc-base)) (list 'b))
				(defmethod mc-app append ((x mc-leaf)) (list 'l))
				(let ((it (make-instance 'mc-leaf)))
				  (list (mc-sum it) (mc-names it) (mc-all it) (mc-app it)))
				""").print()).isEqualTo("(101 (LEAF BASE) NIL (L B))");
	}

	@Test
	void evalShortFormMethodCombinationWrapsInAround() {
		// :around is the one other legal qualifier: its call-next-method reaches the
		// COMBINED form, not the most specific primary.
		assertThat(evalMulti("""
				(defvar *mc-log* nil)
				(defclass mc-base () ())
				(defgeneric mc-trace (x) (:method-combination progn))
				(defmethod mc-trace progn ((x mc-base)) (push :inner *mc-log*))
				(defmethod mc-trace :around ((x mc-base))
				  (push :in *mc-log*) (call-next-method) (push :out *mc-log*))
				(mc-trace (make-instance 'mc-base))
				(reverse *mc-log*)
				""").print()).isEqualTo("(:IN :INNER :OUT)");
	}

	@Test
	void evalShortFormMethodCombinationRejectsBeforeAndAfter() {
		// CLHS: only the combination name and :around are legal qualifiers there.
		assertThatThrownBy(() -> evalMulti("""
				(defclass mc-base () ())
				(defgeneric mc-trace (x) (:method-combination progn))
				(defmethod mc-trace :before ((x mc-base)) nil)
				""")).hasMessageContaining("not allowed under the PROGN method combination");
	}

	@Test
	void evalOpenAppendKeepsTheExistingContent(@TempDir Path tempDir) {
		String file = tempDir.resolve("append.txt").toString().replace("\\", "\\\\");
		assertThat(evalMulti("""
				(with-open-file (out "%s" :direction :output) (write-string "one" out))
				(with-open-file (out "%s" :direction :output :if-exists :append) (write-string "two" out))
				(with-open-file (in "%s") (read-line in))
				""".formatted(file, file, file)).print()).isEqualTo("\"onetwo\"");
	}

	@Test
	void evalUiopWithTemporaryFileWritesAndCleansUp(@TempDir Path tempDir) {
		String dir = (tempDir.toString() + "/wtf/").replace("\\", "\\\\");
		// :keep t hands the pathname back with the file still there; the default
		// deletes it on the way out.
		assertThat(evalMulti("""
				(list (let ((kept (uiop:with-temporary-file (:stream s :pathname p :directory "%s" :keep t)
				                    (write-string "kept" s)
				                    p)))
				        (and (probe-file kept) t))
				      (let ((gone (uiop:with-temporary-file (:stream s :pathname p :directory "%s")
				                    (write-string "gone" s)
				                    p)))
				        (and (probe-file gone) t)))
				""".formatted(dir, dir)).print()).isEqualTo("(T NIL)");
	}

	@Test
	void evalUiopBindingMacrosAndWithDeprecation() {
		// if-let binds like let and takes the then branch only when EVERY variable came
		// out non-nil; a single un-nested binding is accepted; when-let* is sequential
		// and short-circuits before evaluating the rest. with-deprecation establishes
		// its definitions verbatim (the level form is ignored -- rontolisp has no
		// deprecation-warning channel) both at top level and inside eval-when.
		assertThat(evalMulti("""
				(uiop:with-deprecation (:style-warning)
				  (defun dep-a (x) (* x 2))
				  (defun dep-b (x) (+ x 1)))
				(eval-when (:compile-toplevel :load-toplevel :execute)
				  (uiop:with-deprecation (:style-warning)
				    (defun dep-c (x) (- x 1))))
				(list (uiop:if-let ((a 1) (b 2)) (list a b) 0)
				      (uiop:if-let ((a 1) (b nil)) (list a b) 0)
				      (uiop:if-let (x (+ 1 2)) (* x 10) 0)
				      (uiop:if-let ((a nil)) 7)
				      (uiop:when-let ((a 3) (b 4)) (+ a b) (* a b))
				      (uiop:when-let ((a 3) (b nil)) (+ a 1))
				      (uiop:when-let* ((a 5) (b (* a 2))) (+ a b))
				      (uiop:when-let* ((a nil) (b (error "no"))) b)
				      (dep-a 3) (dep-b 3) (dep-c 3))
				""").print()).isEqualTo("((1 2) 0 30 NIL 12 NIL 15 NIL 6 4 2)");
	}

	@Test
	void evalUiopEnsureDirectoryPathnameAndDeleteFileIfExists() {
		assertThat(evalMulti("""
				(list (uiop:ensure-directory-pathname "/tmp/x")
				      (uiop:ensure-directory-pathname "/tmp/x/")
				      (uiop:delete-file-if-exists "/tmp/rontolisp-no-such-file-9f3a"))
				""").print()).isEqualTo("(#P\"/tmp/x/\" #P\"/tmp/x/\" NIL)");
	}

	@Test
	void evalUiopPathnameSubpathFamily() {
		// The uiop/pathname algebra over the flat namestring (.kb/uiop.md): subpathname
		// merges a relative subpath under the base's DIRECTORY, subpathp answers the
		// base-relative remainder, enough-pathname falls back to the argument.
		assertThat(evalMulti("""
				(list (uiop:subpathname #P"/tmp/foo/" "bar/baz.txt")
				      (uiop:subpathname #P"/tmp/foo/x.lisp" "n" :type "txt")
				      (uiop:subpathname* "/tmp/foo" "x.txt")
				      (uiop:subpathname* nil "x")
				      (uiop:subpathp #P"/tmp/foo/bar.txt" #P"/tmp/")
				      (uiop:subpathp #P"/other/x" #P"/tmp/")
				      (uiop:subpathp "/tmp/foo" #P"/tmp/")
				      (uiop:enough-pathname #P"/tmp/a/b.txt" #P"/tmp/")
				      (uiop:enough-pathname #P"/x/a.txt" #P"/tmp/"))
				""").print()).isEqualTo("(#P\"/tmp/foo/bar/baz.txt\" #P\"/tmp/foo/n.txt\" #P\"/tmp/foo/x.txt\" NIL"
				+ " #P\"foo/bar.txt\" NIL NIL #P\"a/b.txt\" #P\"/x/a.txt\")");
	}

	@Test
	void evalUiopPathnamePredicates() {
		// absolute / relative / file answer the parsed PATHNAME (a generalized
		// boolean), directory-pathname-p and the rest answer T/NIL.
		assertThat(evalMulti("""
				(list (uiop:absolute-pathname-p "/a/b") (uiop:absolute-pathname-p "a/b")
				      (uiop:relative-pathname-p "a/b") (uiop:relative-pathname-p "/a/b")
				      (uiop:directory-pathname-p "/a/b/") (uiop:directory-pathname-p "/a/b")
				      (uiop:directory-pathname-p "*/") (uiop:file-pathname-p "/a/b")
				      (uiop:file-pathname-p "/a/b/") (uiop:hidden-pathname-p ".gitignore")
				      (uiop:hidden-pathname-p "x.txt") (uiop:logical-pathname-p #P"/a")
				      (uiop:physical-pathname-p #P"/a") (uiop:physicalize-pathname "/a")
				      (uiop:pathname-equal "/a/b" #P"/a/b") (uiop:pathname-equal #P"a" #P"b")
				      (uiop:pathname-equal nil nil))
				""").print())
			.isEqualTo("(#P\"/a/b\" NIL #P\"a/b\" NIL T NIL NIL #P\"/a/b\" NIL T NIL NIL T" + " #P\"/a\" T NIL T)");
	}

	@Test
	void evalUiopPathnameParsingAndDirectories() {
		assertThat(evalMulti("""
				(list (uiop:pathname-directory-pathname #P"/a/b/c.txt")
				      (uiop:pathname-parent-directory-pathname #P"/a/b/c.txt")
				      (uiop:pathname-parent-directory-pathname #P"/a/")
				      (uiop:pathname-parent-directory-pathname #P"a/")
				      (uiop:parse-unix-namestring "a//b/./c.txt")
				      (uiop:parse-unix-namestring "foo/bar" :type "lisp")
				      (uiop:parse-unix-namestring "foo/bar" :ensure-directory t)
				      (uiop:parse-unix-namestring :foo)
				      (uiop:parse-unix-namestring nil)
				      (uiop:unix-namestring #P"/a/b.c")
				      (uiop:pathname-root #P"/a/b")
				      (uiop:pathname-host-pathname #P"/a/b")
				      (uiop:nil-pathname)
				      uiop:*nil-pathname*)
				""").print()).isEqualTo("(#P\"/a/b/\" #P\"/a/\" #P\"/\" #P\"\" #P\"a/b/c.txt\" #P\"foo/bar.lisp\""
				+ " #P\"foo/bar/\" #P\"foo\" NIL \"/a/b.c\" #P\"/\" #P\"\" #P\"\" #P\"\")");
	}

	@Test
	void evalUiopSplitNameTypeAndSplitUnixNamestringYieldTheirValues() {
		// Both are MULTIPLE-VALUE producers, and a lone leading dot makes the whole
		// filename the NAME (type nil = *unspecific-pathname-type*).
		assertThat(evalMulti("""
				(append (multiple-value-list (uiop:split-name-type "foo.lisp"))
				        (multiple-value-list (uiop:split-name-type ".hidden"))
				        (multiple-value-list (uiop:split-name-type "foo"))
				        (multiple-value-list
				         (uiop:split-unix-namestring-directory-components "/a/b/c.txt"))
				        (multiple-value-list
				         (uiop:split-unix-namestring-directory-components "a/../b/" :dot-dot :up)))
				""").print()).isEqualTo("(\"foo\" \"lisp\" \".hidden\" NIL \"foo\" NIL"
				+ " :ABSOLUTE (\"a\" \"b\") \"c.txt\" NIL :RELATIVE (\"a\" :UP \"b\") NIL NIL)");
	}

	@Test
	void evalUiopWildPathnamesAndTranslatePathnameStar() {
		// The *wild* family are namestring literals over the two wildcards
		// %wild-match reads; wilden appends *wild-path* under the argument's
		// directory; translate-pathname* is the output-translations wrapper.
		assertThat(evalMulti("""
				(list uiop:*wild* uiop:*wild-file* uiop:*wild-file-for-directory*
				      uiop:*wild-directory* uiop:*wild-inferiors* uiop:*wild-path*
				      uiop:*unspecific-pathname-type* uiop:*output-translation-function*
				      (uiop:wilden #P"/tmp/foo")
				      (uiop:translate-pathname* #P"/src/a/b.lisp" #P"/src/**/*.*" #P"/out/**/*.*")
				      (uiop:translate-pathname* #P"/a/x" #P"/a/*" t)
				      (uiop:translate-pathname* #P"/a/x" #P"/a/*" (lambda (p s) (list p s)))
				      (uiop:relativize-pathname-directory #P"/a/b/c.txt")
				      (uiop:relativize-directory-component '(:absolute "a" "b"))
				      (uiop:directory-separator-for-host)
				      (uiop:directorize-pathname-host-device #P"/a/b"))
				""").print()).isEqualTo("(\"*\" #P\"*.*\" #P\"*.*\" #P\"*/\" #P\"**/\" #P\"**/*.*\" NIL IDENTITY"
				+ " #P\"/tmp/**/*.*\" #P\"/out/a/b.lisp\" #P\"/a/x\" (#P\"/a/x\" #P\"/a/*\")"
				+ " #P\"a/b/c.txt\" (:RELATIVE \"a\" \"b\") #\\/ #P\"/a/b\")");
	}

	@Test
	void evalUiopDirectoryComponentAlgebraAndMakePathnameStar() {
		assertThat(evalMulti("""
				(list (uiop:normalize-pathname-directory-component "foo")
				      (uiop:normalize-pathname-directory-component '(:relative "a"))
				      (uiop:denormalize-pathname-directory-component '(:absolute "a"))
				      (uiop:merge-pathname-directory-components '(:relative "x") '(:absolute "a" "b"))
				      (uiop:merge-pathname-directory-components '(:relative :back "x") '(:absolute "a" "b"))
				      (uiop:merge-pathname-directory-components '(:absolute "z") '(:absolute "a"))
				      (uiop:make-pathname-component-logical :unspecific)
				      (uiop:make-pathname-component-logical "x")
				      (uiop:make-pathname* :name "n" :type "t" :directory '(:absolute "d")))
				""").print()).isEqualTo("((:ABSOLUTE \"foo\") (:RELATIVE \"a\") (:ABSOLUTE \"a\")"
				+ " (:ABSOLUTE \"a\" \"b\" \"x\") (:ABSOLUTE \"a\" \"x\") (:ABSOLUTE \"z\")"
				+ " NIL \"x\" #P\"/d/n.t\")");
	}

	@Test
	void evalUiopEnsurePathnameChecksAndTransforms() {
		// The lite constraint machine: want-* checks signal (or route through a custom
		// ON-ERROR), ensure-* transforms, and make-pathname-logical names the missing
		// logical-host model in a not-implemented-error.
		assertThat(evalMulti("""
				(list (uiop:ensure-pathname "a/b" :want-relative t)
				      (handler-case (uiop:ensure-pathname "/a/b" :want-relative t) (error () :err))
				      (uiop:ensure-pathname "a/b/" :want-directory t)
				      (uiop:ensure-pathname "a/b" :ensure-directory t)
				      (uiop:ensure-pathname "" :empty-is-nil t)
				      (uiop:ensure-pathname nil)
				      (handler-case (uiop:ensure-pathname nil :want-pathname t) (error () :err))
				      (uiop:ensure-pathname "a" :ensure-absolute t :defaults "/base/")
				      (uiop:ensure-pathname "*.lisp" :want-wild t)
				      (uiop:ensure-pathname "/a/b.txt" :ensure-subpath t :defaults "/a/")
				      (uiop:ensure-pathname "/a/b" :on-error (lambda (&rest args) (length args)))
				      (handler-case (uiop:make-pathname-logical #P"/a" "HOST")
				        (uiop:not-implemented-error () :nie)))
				""").print()).isEqualTo("(#P\"a/b\" :ERR #P\"a/b/\" #P\"a/b/\" NIL NIL :ERR #P\"/base/a\""
				+ " #P\"*.lisp\" #P\"/a/b.txt\" #P\"/a/b\" :NIE)");
	}

	@Test
	void evalUiopPathnameDefaultsMacrosAndGetPathnameDefaults() {
		// get-pathname-defaults reads the *default-pathname-defaults* special (the
		// .todo/036-era "" Java built-in is retired); the two macros expand into a
		// dynamic rebinding / call-with-enough-pathname.
		assertThat(evalMulti("""
				(list (uiop:get-pathname-defaults)
				      (let ((*default-pathname-defaults* #P"/dpd/")) (uiop:get-pathname-defaults))
				      (uiop:get-pathname-defaults #P"/g/")
				      (uiop:with-pathname-defaults (#P"/wpd/") *default-pathname-defaults*)
				      (uiop:with-pathname-defaults () *default-pathname-defaults*)
				      (let ((p #P"/tmp/a/b.txt"))
				        (uiop:with-enough-pathname (p :defaults #P"/tmp/") p))
				      (uiop:with-enough-pathname (e :pathname #P"/tmp/x/y.txt" :defaults #P"/tmp/")
				        (namestring e))
				      (uiop:call-with-enough-pathname #P"/tmp/x/y.txt" #P"/tmp/" #'namestring))
				""").print())
			.isEqualTo("(#P\"\" #P\"/dpd/\" #P\"/g/\" #P\"/wpd/\" #P\"\" #P\"a/b.txt\"" + " \"x/y.txt\" \"x/y.txt\")");
	}

	@Test
	void evalUiopOsHostIdentityIsDerivedFromFeaturep() {
		// Every host answer in uiop/os comes from ONE source, upstream's own: featurep
		// over *features*. os-unix-p is the exception and is t outright -- every backend
		// presents the POSIX-shaped file model, while *features* deliberately carries no
		// :unix (.kb/uiop.md).
		assertThat(evalMulti("""
				(list (uiop:featurep :rontolisp) (uiop:featurep :rontolisp-interpreter)
				      (uiop:featurep '(:and :rontolisp :unicode)) (uiop:featurep '(:or :nope :rontolisp))
				      (uiop:featurep '(:not :nope)) (uiop:featurep :nope)
				      (uiop:featurep :rontolisp '(:other)) (uiop:featurep :other '(:other))
				      (uiop:os-unix-p) (uiop:os-macosx-p) (uiop:os-windows-p) (uiop:os-genera-p)
				      (uiop:detect-os) (uiop:operating-system) (uiop:implementation-type)
				      uiop:*implementation-type* (uiop:architecture)
				      (uiop:os-cond ((uiop:os-windows-p) :win) ((uiop:os-unix-p) :unix) (t :other))
				      (uiop:hostname))
				""").print())
			.isEqualTo("(T T T T T NIL NIL T T NIL NIL NIL :OS-UNIX :UNIX :RONTOLISP :RONTOLISP" + " :JVM :UNIX NIL)");
		// The version string is the build's own, so the identifier is pinned by shape.
		assertThat(evalMulti("(uiop:implementation-identifier)").print()).isEqualTo("\"rontolisp-"
				+ am.ik.rontolisp.Version.getVersion().toLowerCase(java.util.Locale.ROOT) + "-unix-jvm\"");
		assertThat(evalMulti("(uiop:lisp-version-string)").print())
			.isEqualTo("\"" + am.ik.rontolisp.Version.getVersion() + "\"");
	}

	@Test
	void evalUiopOsGetenvReadsTheHostAndSetfWritesAnOverride() {
		// No backend can rewrite its own process environment (the JVM cannot at all,
		// WASI's is read-only), so (setf (uiop:getenv x) v) records an override that
		// getenv consults BEFORE the host -- which is what rove's with-local-envs needs.
		// A nil value is an unset, upstream's own semantics.
		assertThat(evalMulti("""
				(list (uiop:getenv "CI_EVAL_UIOP_OS")
				      (setf (uiop:getenv "CI_EVAL_UIOP_OS") "one")
				      (uiop:getenv "CI_EVAL_UIOP_OS")
				      (uiop:getenvp "CI_EVAL_UIOP_OS")
				      (progn (setf (uiop:getenv "CI_EVAL_UIOP_OS") "two") (uiop:getenv "CI_EVAL_UIOP_OS"))
				      (progn (setf (uiop:getenv "CI_EVAL_UIOP_OS") nil) (uiop:getenv "CI_EVAL_UIOP_OS"))
				      (uiop:getenvp "CI_EVAL_UIOP_OS")
				      (progn (setf (uiop:getenv "CI_EVAL_UIOP_OS") "") (uiop:getenvp "CI_EVAL_UIOP_OS")))
				""").print()).isEqualTo("(NIL \"one\" \"one\" \"one\" \"two\" NIL NIL NIL)");
		// The host is still readable through the override map: PATH is set for the test
		// JVM and no override touched it.
		assertThat(evalMulti("(stringp (uiop:getenv \"PATH\"))").print()).isEqualTo("T");
	}

	@Test
	void evalUiopOsSetfGetenvIsTheFirstTouchOfTheMember() {
		// The interpreter lazy-loads a uiop definition on FUNCTION or VARIABLE
		// resolution; a setf PLACE is the third trigger, and without it a program whose
		// first touch of getenv is the write (rove's with-local-envs, again) expands
		// against an empty place registry and fails with "setf does not support place".
		assertThat(evalMulti("""
				(defun ci-set-env (k v) (setf (uiop:getenv k) v))
				(ci-set-env "CI_EVAL_UIOP_FIRST" "written")
				(uiop:getenv "CI_EVAL_UIOP_FIRST")
				""").print()).isEqualTo("\"written\"");
	}

	@Test
	void evalUiopOsWorkingDirectoryAndTheWindowsShortcutFamily() {
		// getcwd is real where the host has a working directory (here: user.dir);
		// chdir signals on every backend, because none can move one. The two octet
		// readers are real stream work; the two .lnk parsers name the primitive they
		// would need -- file-position on a binary stream, which is nil here.
		assertThat(evalMulti("(pathnamep (uiop:getcwd))").print()).isEqualTo("T");
		assertThat(evalMulti("""
				(list (handler-case (uiop:chdir "/tmp") (uiop:not-implemented-error () :chdir))
				      (handler-case (uiop:parse-windows-shortcut "x.lnk")
				        (uiop:not-implemented-error () :parse-windows-shortcut))
				      (handler-case (uiop:parse-file-location-info nil)
				        (uiop:not-implemented-error () :parse-file-location-info)))
				""").print()).isEqualTo("(:CHDIR :PARSE-WINDOWS-SHORTCUT :PARSE-FILE-LOCATION-INFO)");
		assertThat(evalMulti("""
				(asdf:load-system "flexi-streams")
				(let* ((v (make-array 7 :element-type '(unsigned-byte 8)
				                        :initial-contents '(1 2 0 0 104 105 0)))
				       (s (flex:make-in-memory-input-stream v)))
				  (list (uiop:read-little-endian s) (uiop:read-null-terminated-string s)))
				""").print()).isEqualTo("(513 \"hi\")");
	}

	@Test
	void evalUiopImageQuitIsTheHostExit() {
		// uiop:quit ends the process with a status code on all four backends. On the
		// interpreter that is a SIGNAL rather than a System.exit -- run() is embedded,
		// and
		// only RontoLispCli.main may turn a program's code into the process's. The code
		// is
		// masked to eight bits, which is what a POSIX host does with it anyway and what
		// wasi:cli/exit's u8 accepts, so the backends agree past 255 too.
		assertThatThrownBy(() -> evalMulti("(uiop:quit 3)")).isInstanceOf(LispExitSignal.class)
			.extracting(ex -> ((LispExitSignal) ex).code())
			.isEqualTo(3);
		assertThatThrownBy(() -> evalMulti("(uiop:quit)")).isInstanceOf(LispExitSignal.class)
			.extracting(ex -> ((LispExitSignal) ex).code())
			.isEqualTo(0);
		assertThatThrownBy(() -> evalMulti("(uiop:quit 300)")).isInstanceOf(LispExitSignal.class)
			.extracting(ex -> ((LispExitSignal) ex).code())
			.isEqualTo(44);
		assertThatThrownBy(() -> evalMulti("(uiop:shell-boolean-exit nil)")).isInstanceOf(LispExitSignal.class)
			.extracting(ex -> ((LispExitSignal) ex).code())
			.isEqualTo(1);
		assertThatThrownBy(() -> evalMulti("(uiop:shell-boolean-exit :yes)")).isInstanceOf(LispExitSignal.class)
			.extracting(ex -> ((LispExitSignal) ex).code())
			.isEqualTo(0);
		// die reports on *error-output* and quits with the code it was given.
		assertThatThrownBy(() -> evalMulti("""
				(let ((*error-output* (make-string-output-stream)))
				  (uiop:die 7 "no such thing: ~A" :widget))
				""")).isInstanceOf(LispExitSignal.class).extracting(ex -> ((LispExitSignal) ex).code()).isEqualTo(7);
	}

	@Test
	void evalUiopImageQuitNeitherUnwindsNorIsCatchable() {
		// System.exit / proc_exit / wasi:cli/exit end the process where they stand, so
		// nothing runs after a quit on the compile backends -- and the interpreter's exit
		// signal is deliberately invisible to unwind-protect and to handler-case for
		// exactly that reason. All four backends print :START and nothing else.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out));
		assertThatThrownBy(() -> {
			for (LispVal expr : LispReader.readAllFromString("""
					(print :start)
					(unwind-protect
					     (handler-case (uiop:quit 5) (error (e) (print :caught)))
					  (print :cleanup))
					(print :after)
					""")) {
				evaluator.eval(expr);
			}
		}).isInstanceOf(LispExitSignal.class).extracting(ex -> ((LispExitSignal) ex).code()).isEqualTo(5);
		assertThat(out.toString()).isEqualTo(":START\n");
	}

	@Test
	void evalUiopImageHooksAndTheFatalConditionFamily() {
		// The hooks are REAL lists -- a library may register into one at load time, and
		// only the act of dumping an image is impossible. Upstream routes both registrars
		// through register-hook-function, which needs (setf (symbol-value ...)); naming
		// the variable literally is the same registration without that primitive.
		assertThat(evalMulti("""
				(uiop:register-image-dump-hook 'a)
				(uiop:register-image-dump-hook 'b)
				(uiop:register-image-dump-hook 'a)
				(defvar *ran* nil)
				(uiop:register-image-restore-hook (lambda () (push :restored *ran*)) nil)
				(uiop:call-image-restore-hook)
				(list uiop:*image-dump-hook* *ran* uiop:*image-dumped-p* uiop:*lisp-interaction*
				      uiop:*image-prelude* uiop:*image-entry-point* uiop:*image-postlude*)
				""").print()).isEqualTo("((B A) (:RESTORED) NIL NIL NIL NIL NIL)");
		// fatal-condition is a deftype over serious-condition, so a RUNTIME typep and a
		// handler-bind clause both match it.
		assertThat(evalMulti("""
				(list (uiop:fatal-condition-p (make-condition 'error))
				      (uiop:fatal-condition-p (make-condition 'warning))
				      (uiop:fatal-condition-p 42))
				""").print()).isEqualTo("(T NIL NIL)");
		// The backtrace family is LITE and stays lite: no backend carries a Lisp-level
		// call stack, so the honest rendering is the condition and no frames.
		assertThat(evalMulti("""
				(with-output-to-string (s)
				  (uiop:print-condition-backtrace (make-condition 'simple-error :format-control "boom")
				                                  :stream s)
				  (uiop:print-backtrace :stream s :condition "second")
				  (uiop:raw-print-backtrace :stream s))
				""").print()).isEqualTo("\"boom\nsecond\n\"");
	}

	@Test
	void evalUiopImageHandleFatalConditionReportsAndExits99() {
		// *lisp-interaction* is nil here where upstream defaults to t: there is no
		// debugger to enter on any backend, so the handler reports and dies with
		// upstream's own status 99.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out));
		assertThatThrownBy(() -> {
			for (LispVal expr : LispReader.readAllFromString("""
					(defvar *report* nil)
					(let ((*error-output* (make-string-output-stream)))
					  (uiop:with-fatal-condition-handler () (error "the sky is falling")))
					""")) {
				evaluator.eval(expr);
			}
		}).isInstanceOf(LispExitSignal.class).extracting(ex -> ((LispExitSignal) ex).code()).isEqualTo(99);
	}

	@Test
	void evalUiopImageTheImageItselfIsNotImplemented() {
		// There is no image: a program is started from source and the compile backends
		// emit an artifact rather than saving a heap. The three name that instead of
		// half-doing it.
		assertThat(evalMulti("""
				(list (handler-case (uiop:dump-image "x.img") (uiop:not-implemented-error () :dump-image))
				      (handler-case (uiop:restore-image) (uiop:not-implemented-error () :restore-image))
				      (handler-case (uiop:create-image "x" nil) (uiop:not-implemented-error () :create-image)))
				""").print()).isEqualTo("(:DUMP-IMAGE :RESTORE-IMAGE :CREATE-IMAGE)");
	}

	@Test
	void evalFlexiStreamsInMemoryInputStreamIsARealBinaryStream() {
		// smart-buffer's finalize-buffer hands one of these to the multipart parser,
		// and http-body type-tests it against flex:vector-stream for its fast path.
		assertThat(evalMulti("""
				(asdf:load-system "flexi-streams")
				(let* ((v (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(7 8 9)))
				       (s (flex:make-in-memory-input-stream v))
				       (first-byte (read-byte s))
				       (buf (make-array 2 :element-type '(unsigned-byte 8))))
				  (list (and (typep s 'flex:vector-stream) t)
				        first-byte
				        (read-sequence buf s)
				        (read-byte s nil :eof)
				        (progn (file-position s 0) (read-byte s))))
				""").print()).isEqualTo("(T 7 2 :EOF 7)");
	}

	@Test
	void evalDefstructAcceptsAKeywordConcName() {
		// :conc-name takes a STRING DESIGNATOR, so a keyword designates its name
		// WITHOUT the colon -- fast-http's (defstruct (http (:conc-name :http-))).
		assertThat(evalMulti("""
				(defstruct (kw-thing (:conc-name :kw-thing-)) (state 0))
				(let ((it (make-kw-thing)))
				  (setf (kw-thing-state it) 5)
				  (kw-thing-state it))
				""").print()).isEqualTo("5");
	}

	@Test
	void evalDefstructPrintObjectTakesASymbolDesignator() {
		// (:print-object fn) rides the print-object seam, so every printing operator
		// renders the struct through it -- princ/prin1 and format's ~A/~S alike.
		assertThat(evalMulti("""
				(defstruct (po-pt (:print-object po-pt-printer)) (x 1) (y 2))
				(defun po-pt-printer (obj stream)
				  (format stream "<~D,~D>" (po-pt-x obj) (po-pt-y obj)))
				(format nil "~A ~S ~A" (make-po-pt) (make-po-pt :x 3) (princ-to-string (make-po-pt :y 9)))
				""").print()).isEqualTo("\"<1,2> <3,2> <1,9>\"");
	}

	@Test
	void evalDefstructPrintFunctionTakesALambdaAndADepthArgument() {
		// The CLtL1 spelling: the function takes a third `depth` argument, and 0 is
		// what an implementation that tracks no depth passes. map-set's one struct is
		// exactly this shape.
		assertThat(evalMulti("""
				(defstruct (pf-set (:print-function (lambda (obj stream depth)
				                                      (print-unreadable-object (obj stream :type t)
				                                        (format stream "of ~D element~:P at depth ~D"
				                                                (pf-set-size obj) depth)))))
				  (size 1))
				(list (princ-to-string (make-pf-set)) (princ-to-string (make-pf-set :size 3)))
				""").print())
			.isEqualTo("(\"#<PF-SET of 1 element at depth 0>\" \"#<PF-SET of 3 elements at depth 0>\")");
	}

	@Test
	void evalDefstructPrinterOnATypeStructIsRejected() {
		// A :type struct IS a plain vector: no instance tag to specialize a
		// print-object method on, so the combination is refused rather than ignored.
		assertThatThrownBy(() -> evalMulti("""
				(defstruct (tv-pt (:type (vector t)) (:print-object tv-printer)) x)
				""")).hasMessageContaining(":print-object / :print-function on a :type struct");
	}

	@Test
	void evalDefstructRejectsBothPrinterOptions() {
		assertThatThrownBy(() -> evalMulti("""
				(defstruct (two-pt (:print-object a) (:print-function b)) x)
				""")).hasMessageContaining("only one of :print-object / :print-function");
	}

	@Test
	void evalPrintUnreadableObjectTypeFollowsPrintEscape() {
		// print-unreadable-object's :type t writes the type SYMBOL the way the current
		// *print-escape* would: prin1 keeps the package qualifier, princ writes only
		// the symbol's name (SBCL-checked).
		assertThat(evalMulti("""
				(defpackage :puo-lib (:use :cl) (:export :thing :make-thing))
				(in-package :puo-lib)
				(defstruct (thing (:print-object (lambda (obj stream)
				                                   (print-unreadable-object (obj stream :type t)
				                                     (princ (thing-v obj) stream)))))
				  (v 7))
				(in-package :cl-user)
				(list (princ-to-string (puo-lib:make-thing)) (prin1-to-string (puo-lib:make-thing)))
				""").print()).isEqualTo("(\"#<THING 7>\" \"#<PUO-LIB:THING 7>\")");
	}

	@Test
	void evalMacroBodyInAFunctionBodyRunsInItsDefiningPackage() {
		// fast-http's callback-data shape: a macro that COMPUTES a symbol name, used
		// inside a function body of its OWN file and called from another package. The
		// interpreter expands it lazily, at call time, so without the definition-package
		// swap the (intern "SECRET") landed in the caller's package. A TOP-LEVEL macro
		// call keeps the current package (trivia's lispn:define-namespace needs that).
		assertThat(evalMulti("""
				(defpackage :mp-lib (:use :cl) (:export :call-it))
				(in-package :mp-lib)
				(defun mp-lib::secret (x) (* x 2))
				(defmacro mp-lib::name-of (x) (list (intern "SECRET") x))
				(defun mp-lib:call-it (x) (mp-lib::name-of x))
				(in-package :cl-user)
				(mp-lib:call-it 21)
				""").print()).isEqualTo("42");
	}

}
