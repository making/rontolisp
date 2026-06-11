package am.ik.rontolisp.eval;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import am.ik.rontolisp.LispBigInteger;
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
	void evalDefunAndCall() {
		assertThat(evalMulti("(defun square (x) (* x x)) (square 5)")).isEqualTo(new LispInteger(25));
	}

	@Test
	void evalLambda() {
		assertThat(eval("((lambda (x) (* x x)) 5)")).isEqualTo(new LispInteger(25));
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
		assertThat(eval("(reduce #'+ 0 '(1 2 3 4 5))")).isEqualTo(new LispInteger(15));
	}

	@Test
	void evalReduceWithBuiltinMul() {
		assertThat(eval("(reduce #'* 1 '(1 2 3 4 5))")).isEqualTo(new LispInteger(120));
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
	void evalAssoc() {
		assertThat(eval("(assoc 'b '((a 1) (b 2) (c 3)))").print()).isEqualTo("(b 2)");
		assertThat(eval("(assoc 'z '((a 1)))")).isSameAs(LispNil.INSTANCE);
	}

	@Test
	void evalLast() {
		assertThat(eval("(last '(1 2 3))").print()).isEqualTo("(3)");
		assertThat(eval("(last nil)")).isSameAs(LispNil.INSTANCE);
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
	void evalBuiltinAsVariable() {
		assertThat(evalMulti("(setq my-op #'+) (funcall my-op 10 20)")).isEqualTo(new LispInteger(30));
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
	void loadMissingFileThrows(@TempDir Path tempDir) {
		Path missing = tempDir.resolve("nope.lisp");
		assertThatThrownBy(() -> eval("(load \"" + missing.toString().replace("\\", "\\\\") + "\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("cannot read file");
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
		assertThat(eval("(rontolisp:list-macros)").print())
			.isEqualTo("(and cond decf dolist dotimes incf let* or pop push remf setf unless when)");
	}

	@Test
	void listSpecialFormsReturnsSortedClSpecialForms() {
		assertThat(eval("(rontolisp:list-special-forms)").print())
			.isEqualTo("(defun function if in-package lambda let progn quote setq while)");
	}

	@Test
	void listFunctionsReturnsSortedClFunctions() {
		java.util.List<String> names = symbolNames(eval("(rontolisp:list-functions)"));
		assertThat(names).contains("first", "rest", "nth", "funcall", "length", "1+", "car", "eval", "not")
			.doesNotContain("cond", "quote", "defun", "setf", "%remf-tail", "cadr", "*package*")
			.isSorted()
			.hasSize(88);
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
		assertThat(eval("(rontolisp:list-functions :rontolisp)").print())
			.isEqualTo("(list-functions list-macros list-special-forms version)");
	}

	@Test
	void listMacrosAndSpecialFormsAreNilForClUserAndRontolisp() {
		assertThat(eval("(rontolisp:list-macros :cl-user)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(rontolisp:list-macros :rontolisp)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(rontolisp:list-special-forms :cl-user)")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(rontolisp:list-special-forms :rontolisp)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void listFunctionsUnknownPackageThrows() {
		assertThatThrownBy(() -> eval("(rontolisp:list-functions :foo)"))
			.isInstanceOf(am.ik.rontolisp.LispPackageException.class)
			.hasMessageContaining("No such package: foo");
	}

	@Test
	void listFunctionsUnknownPackageViaFuncallThrows() {
		assertThatThrownBy(() -> eval("(funcall #'rontolisp:list-functions :foo)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("No such package: foo");
	}

	@Test
	void listMacrosWorksViaEvalAndFuncall() {
		LispVal expected = eval("(rontolisp:list-macros)");
		assertThat(eval("(eval '(rontolisp:list-macros))")).isEqualTo(expected);
		assertThat(eval("(funcall #'rontolisp:list-macros :cl)")).isEqualTo(expected);
	}

	@Test
	void unqualifiedIntrospectionWorksInRontolispPackage() {
		assertThat(evalMulti("(in-package :rontolisp) (list-functions :rontolisp)").print())
			.isEqualTo("(list-functions list-macros list-special-forms version)");
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

}
