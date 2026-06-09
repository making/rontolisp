package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class JvmLispCompilerTest {

	@TempDir
	Path tempDir;

	private String compileAndRun(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler.compile(program);
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);

		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			return baos.toString().trim();
		}
	}

	@Test
	void compileAndRunAddition() throws Exception {
		assertThat(compileAndRun("(print (+ 1 2))")).isEqualTo("3");
	}

	@Test
	void compileAndRunMultiplication() throws Exception {
		assertThat(compileAndRun("(print (* 3 4))")).isEqualTo("12");
	}

	@Test
	void compileAndRunNestedArithmetic() throws Exception {
		assertThat(compileAndRun("(print (+ (* 2 3) (- 10 4)))")).isEqualTo("12");
	}

	@Test
	void compileAndRunMultipleExpressions() throws Exception {
		assertThat(compileAndRun("(print 1) (print 2)")).isEqualTo("1\n2");
	}

	@Test
	void compileAndRunIfTrue() throws Exception {
		assertThat(compileAndRun("(print (if t 1 2))")).isEqualTo("1");
	}

	@Test
	void compileAndRunIfFalse() throws Exception {
		assertThat(compileAndRun("(print (if nil 1 2))")).isEqualTo("2");
	}

	@Test
	void compileAndRunLet() throws Exception {
		assertThat(compileAndRun("(print (let ((x 10)) (+ x 5)))")).isEqualTo("15");
	}

	@Test
	void compileAndRunSubtraction() throws Exception {
		assertThat(compileAndRun("(print (- 10 3))")).isEqualTo("7");
	}

	@Test
	void compileAndRunDivision() throws Exception {
		assertThat(compileAndRun("(print (/ 10 3))")).isEqualTo("3");
	}

	@Test
	void compileAndRunComparisonEqual() throws Exception {
		assertThat(compileAndRun("(print (if (= 1 1) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunComparisonNotEqual() throws Exception {
		assertThat(compileAndRun("(print (if (= 1 2) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunComparisonLessThan() throws Exception {
		assertThat(compileAndRun("(print (if (< 1 2) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunComparisonGreaterThan() throws Exception {
		assertThat(compileAndRun("(print (if (> 3 2) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunComparisonLessOrEqual() throws Exception {
		assertThat(compileAndRun("(print (if (<= 2 2) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunComparisonGreaterOrEqual() throws Exception {
		assertThat(compileAndRun("(print (if (>= 2 3) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunDefunSquare() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(print (square 5))
				""")).isEqualTo("25");
	}

	@Test
	void compileAndRunDefunFactorial() throws Exception {
		assertThat(compileAndRun("""
				(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
				(print (fact 5))
				""")).isEqualTo("120");
	}

	@Test
	void compileAndRunDefunFibonacci() throws Exception {
		assertThat(compileAndRun("""
				(defun fib (n) (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))
				(print (fib 10))
				""")).isEqualTo("55");
	}

	@Test
	void compileAndRunMultipleDefuns() throws Exception {
		assertThat(compileAndRun("""
				(defun double (x) (* x 2))
				(defun add1 (x) (+ x 1))
				(print (add1 (double 5)))
				""")).isEqualTo("11");
	}

	@Test
	void compileAndRunDefunNoParams() throws Exception {
		assertThat(compileAndRun("""
				(defun answer () 42)
				(print (answer))
				""")).isEqualTo("42");
	}

	@Test
	void compileAndRunSetqBasic() throws Exception {
		assertThat(compileAndRun("(print (progn (setq x 10) x))")).isEqualTo("10");
	}

	@Test
	void compileAndRunSetqReassign() throws Exception {
		assertThat(compileAndRun("(print (progn (setq x 10) (setq x 20) x))")).isEqualTo("20");
	}

	@Test
	void compileAndRunSetqMutateLet() throws Exception {
		assertThat(compileAndRun("(print (let ((x 1)) (setq x 2) x))")).isEqualTo("2");
	}

	@Test
	void compileAndRunSetqInExpression() throws Exception {
		assertThat(compileAndRun("(print (+ (setq x 5) 3))")).isEqualTo("8");
	}

	@Test
	void compileAndRunSetqLambdaSquare() throws Exception {
		assertThat(compileAndRun("""
				(setq square (lambda (x) (* x x)))
				(print (square 5))
				""")).isEqualTo("25");
	}

	@Test
	void compileAndRunSetqLambdaFactorial() throws Exception {
		assertThat(compileAndRun("""
				(setq fact (lambda (n) (if (<= n 1) 1 (* n (fact (- n 1))))))
				(print (fact 5))
				""")).isEqualTo("120");
	}

	@Test
	void compileAndRunSetqLambdaNoParams() throws Exception {
		assertThat(compileAndRun("""
				(setq answer (lambda () 42))
				(print (answer))
				""")).isEqualTo("42");
	}

	@Test
	void compileAndRunSetqLambdaMultipleFunctions() throws Exception {
		assertThat(compileAndRun("""
				(setq double (lambda (x) (* x 2)))
				(setq add1 (lambda (x) (+ x 1)))
				(print (add1 (double 5)))
				""")).isEqualTo("11");
	}

	@Test
	void compileAndRunMixedDefunAndSetqLambda() throws Exception {
		assertThat(compileAndRun("""
				(defun double (x) (* x 2))
				(setq add1 (lambda (x) (+ x 1)))
				(print (add1 (double 5)))
				""")).isEqualTo("11");
	}

	@Test
	void compileAndRunLambdaImmediateCall() throws Exception {
		assertThat(compileAndRun("(print ((lambda (x) (* x x)) 5))")).isEqualTo("25");
	}

	@Test
	void compileAndRunLambdaMultipleParams() throws Exception {
		assertThat(compileAndRun("(print ((lambda (x y) (+ x y)) 3 4))")).isEqualTo("7");
	}

	@Test
	void compileAndRunPrintString() throws Exception {
		assertThat(compileAndRun("(print \"hello\")")).isEqualTo("\"hello\"");
	}

	@Test
	void compileAndRunPrin1() throws Exception {
		assertThat(compileAndRun("(prin1 42) (terpri) (prin1 \"hello\")")).isEqualTo("42\n\"hello\"");
	}

	@Test
	void compileAndRunPrinc() throws Exception {
		assertThat(compileAndRun("(princ 42) (terpri) (princ \"hello\")")).isEqualTo("42\nhello");
	}

	@Test
	void compileAndRunPrincList() throws Exception {
		assertThat(compileAndRun("(princ '(1 \"hello\" 3))")).isEqualTo("(1 hello 3)");
	}

	@Test
	void compileAndRunTerpri() throws Exception {
		assertThat(compileAndRun("(prin1 1) (princ 2) (terpri)")).isEqualTo("12");
	}

	@Test
	void compileAndRunQuoteInteger() throws Exception {
		assertThat(compileAndRun("(print '42)")).isEqualTo("42");
	}

	@Test
	void compileAndRunQuoteList() throws Exception {
		assertThat(compileAndRun("(print '(1 2 3))")).isEqualTo("(1 2 3)");
	}

	@Test
	void compileAndRunQuoteNestedList() throws Exception {
		assertThat(compileAndRun("(print '(1 (2 3) 4))")).isEqualTo("(1 (2 3) 4)");
	}

	@Test
	void compileAndRunQuoteNil() throws Exception {
		assertThat(compileAndRun("(print (quote nil))")).isEqualTo("nil");
	}

	@Test
	void compileAndRunStringInLet() throws Exception {
		assertThat(compileAndRun("(let ((x \"world\")) (print x))")).isEqualTo("\"world\"");
	}

	@Test
	void compileAndRunQuoteWithSymbol() throws Exception {
		assertThat(compileAndRun("(print '(+ 1 2))")).isEqualTo("(+ 1 2)");
	}

	@Test
	void compileAndRunListCarCdr() throws Exception {
		assertThat(compileAndRun("(print (car (list 1 2 3)))")).isEqualTo("1");
	}

	@Test
	void compileAndRunListCarCdr2() throws Exception {
		assertThat(compileAndRun("(print (car (cdr (list 1 2 3))))")).isEqualTo("2");
	}

	@Test
	void compileAndRunCons() throws Exception {
		assertThat(compileAndRun("(print (car (cons 1 2)))")).isEqualTo("1");
		assertThat(compileAndRun("(print (cdr (cons 1 2)))")).isEqualTo("2");
	}

	@Test
	void compileAndRunHigherOrderFunction() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(defun apply-twice (f x) (f (f x)))
				(print (apply-twice square 3))
				""")).isEqualTo("81");
	}

	@Test
	void compileAndRunLambdaAsArgument() throws Exception {
		assertThat(compileAndRun("""
				(defun apply-twice (f x) (f (f x)))
				(print (apply-twice (lambda (x) (+ x 10)) 5))
				""")).isEqualTo("25");
	}

	@Test
	void compileAndRunClosure() throws Exception {
		assertThat(compileAndRun("""
				(defun make-adder (n) (lambda (x) (+ x n)))
				(setq add5 (make-adder 5))
				(print (add5 10))
				""")).isEqualTo("15");
	}

	@Test
	void compileAndRunClosureMutation() throws Exception {
		assertThat(compileAndRun("""
				(defun make-counter ()
				  (let ((n 0))
				    (lambda ()
				      (setq n (+ n 1))
				      n)))
				(setq counter (make-counter))
				(counter)
				(counter)
				(print (counter))
				""")).isEqualTo("3");
	}

	@Test
	void compileAndRunDynamicFunctionSelection() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(defun forty-two (x) 42)
				(setq f (if t square forty-two))
				(print (f 6))
				""")).isEqualTo("36");
	}

	@Test
	void compileAndRunFuncall() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(print (funcall square 7))
				""")).isEqualTo("49");
	}

	@Test
	void compileAndRunFuncallLambda() throws Exception {
		assertThat(compileAndRun("""
				(print (funcall (lambda (x) (* x x)) 5))
				""")).isEqualTo("25");
	}

	@Test
	void compileAndRunFunctionInList() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(print (funcall (car (list square)) 5))
				""")).isEqualTo("25");
	}

	@Test
	void compileAndRunNullPredicate() throws Exception {
		assertThat(compileAndRun("(print (if (null nil) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (null 1) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunAtom() throws Exception {
		assertThat(compileAndRun("(print (if (atom 1) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (atom '(1 2)) 42 99))")).isEqualTo("99");
		assertThat(compileAndRun("(print (if (atom nil) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunNumberp() throws Exception {
		assertThat(compileAndRun("(print (if (numberp 42) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (numberp 3.14) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (numberp \"hello\") 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunIntegerp() throws Exception {
		assertThat(compileAndRun("(print (if (integerp 42) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (integerp 3.14) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunFloatp() throws Exception {
		assertThat(compileAndRun("(print (if (floatp 3.14) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (floatp 42) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunSymbolp() throws Exception {
		assertThat(compileAndRun("(print (if (symbolp 'foo) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (symbolp 42) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunStringp() throws Exception {
		assertThat(compileAndRun("(print (if (stringp \"hello\") 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (stringp 42) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunListp() throws Exception {
		assertThat(compileAndRun("(print (if (listp '(1 2)) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (listp nil) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (listp 42) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunConsp() throws Exception {
		assertThat(compileAndRun("(print (if (consp '(1 2)) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (consp nil) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunDoubleLiteral() throws Exception {
		assertThat(compileAndRun("(print 3.14)")).isEqualTo("3.14");
	}

	@Test
	void compileAndRunDoubleAddition() throws Exception {
		assertThat(compileAndRun("(print (+ 1.5 2.5))")).isEqualTo("4.0");
	}

	@Test
	void compileAndRunDoubleMixedAddition() throws Exception {
		assertThat(compileAndRun("(print (+ 1 1.5))")).isEqualTo("2.5");
	}

	@Test
	void compileAndRunDoubleSubtraction() throws Exception {
		assertThat(compileAndRun("(print (- 3.5 1.5))")).isEqualTo("2.0");
	}

	@Test
	void compileAndRunDoubleMultiplication() throws Exception {
		assertThat(compileAndRun("(print (* 2.0 3.0))")).isEqualTo("6.0");
	}

	@Test
	void compileAndRunDoubleDivision() throws Exception {
		assertThat(compileAndRun("(print (/ 7.0 2.0))")).isEqualTo("3.5");
	}

	@Test
	void compileAndRunDoubleComparison() throws Exception {
		assertThat(compileAndRun("(print (if (= 1.0 1.0) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (< 1.0 2.0) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (> 2.0 1.0) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (<= 1.5 1.5) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (>= 2.0 3.0) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunDoubleNestedArithmetic() throws Exception {
		assertThat(compileAndRun("(print (+ (* 2.0 3.0) (- 10.0 4.0)))")).isEqualTo("12.0");
	}

	@Test
	void compileAndRunOnePlus() throws Exception {
		assertThat(compileAndRun("(print (1+ 5))")).isEqualTo("6");
	}

	@Test
	void compileAndRunOneMinus() throws Exception {
		assertThat(compileAndRun("(print (1- 5))")).isEqualTo("4");
	}

	@Test
	void compileAndRunZerop() throws Exception {
		assertThat(compileAndRun("(print (if (zerop 0) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (zerop 1) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunPlusp() throws Exception {
		assertThat(compileAndRun("(print (if (plusp 1) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (plusp 0) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunMinusp() throws Exception {
		assertThat(compileAndRun("(print (if (minusp -1) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (minusp 0) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunEvenp() throws Exception {
		assertThat(compileAndRun("(print (if (evenp 4) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (evenp 3) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunOddp() throws Exception {
		assertThat(compileAndRun("(print (if (oddp 3) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (oddp 4) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunAbs() throws Exception {
		assertThat(compileAndRun("(print (abs 5))")).isEqualTo("5");
		assertThat(compileAndRun("(print (abs -5))")).isEqualTo("5");
	}

	@Test
	void compileAndRunMin() throws Exception {
		assertThat(compileAndRun("(print (min 3 5))")).isEqualTo("3");
		assertThat(compileAndRun("(print (min 5 3))")).isEqualTo("3");
	}

	@Test
	void compileAndRunMax() throws Exception {
		assertThat(compileAndRun("(print (max 3 5))")).isEqualTo("5");
		assertThat(compileAndRun("(print (max 5 3))")).isEqualTo("5");
	}

	@Test
	void compileAndRunUnless() throws Exception {
		assertThat(compileAndRun("(print (unless nil 42))")).isEqualTo("42");
		assertThat(compileAndRun("(print (unless t 42))")).isEqualTo("nil");
	}

	@Test
	void compileAndRunWhile() throws Exception {
		assertThat(compileAndRun("(print (let ((n 0) (s 0)) (while (< n 5) (setq s (+ s n)) (setq n (+ n 1))) s))"))
			.isEqualTo("10");
		assertThat(compileAndRun("(print (let ((n 0)) (while nil (setq n 99)) n))")).isEqualTo("0");
	}

	@Test
	void compileAndRunDotimes() throws Exception {
		assertThat(compileAndRun("(print (let ((s 0)) (dotimes (i 5) (setq s (+ s i))) s))")).isEqualTo("10");
		assertThat(compileAndRun("(print (dotimes (i 3)))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (let ((acc 1)) (dotimes (i 4 acc) (setq acc (* acc 2)))))")).isEqualTo("16");
		assertThat(compileAndRun("(print (let ((s 7)) (dotimes (i 0) (setq s 0)) s))")).isEqualTo("7");
		assertThat(compileAndRun("(print (let ((s 0)) (dotimes (i 3) (dotimes (j 2) (setq s (+ s 1)))) s))"))
			.isEqualTo("6");
	}

	@Test
	void compileAndRunFirst() throws Exception {
		assertThat(compileAndRun("(print (first '(1 2 3)))")).isEqualTo("1");
	}

	@Test
	void compileAndRunNth() throws Exception {
		assertThat(compileAndRun("(print (nth 0 '(1 2 3)))")).isEqualTo("1");
		assertThat(compileAndRun("(print (nth 2 '(1 2 3)))")).isEqualTo("3");
	}

	@Test
	void compileAndRunNthcdr() throws Exception {
		assertThat(compileAndRun("(print (nthcdr 0 '(1 2 3)))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (nthcdr 2 '(1 2 3)))")).isEqualTo("(3)");
	}

	@Test
	void compileAndRunSecond() throws Exception {
		assertThat(compileAndRun("(print (second '(1 2 3)))")).isEqualTo("2");
	}

	@Test
	void compileAndRunThird() throws Exception {
		assertThat(compileAndRun("(print (third '(1 2 3)))")).isEqualTo("3");
	}

	@Test
	void compileAndRunFourth() throws Exception {
		assertThat(compileAndRun("(print (fourth '(1 2 3 4)))")).isEqualTo("4");
	}

	@Test
	void compileAndRunCarCdrComposition() throws Exception {
		assertThat(compileAndRun("(print (cadr '(1 2 3)))")).isEqualTo("2");
		assertThat(compileAndRun("(print (caddr '(1 2 3)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (caar '((1 2) 3)))")).isEqualTo("1");
		assertThat(compileAndRun("(print (cadddr '(1 2 3 4)))")).isEqualTo("4");
	}

	@Test
	void compileAndRunRplaca() throws Exception {
		assertThat(compileAndRun("""
				(setq x (cons 1 2))
				(rplaca x 10)
				(print (car x))
				""")).isEqualTo("10");
	}

	@Test
	void compileAndRunRplacd() throws Exception {
		assertThat(compileAndRun("""
				(setq x (cons 1 2))
				(rplacd x 20)
				(print (cdr x))
				""")).isEqualTo("20");
	}

	@Test
	void compileAndRunSetfCar() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 1 2 3))
				(setf (car x) 10)
				(print (car x))
				""")).isEqualTo("10");
	}

	@Test
	void compileAndRunSetfCdr() throws Exception {
		assertThat(compileAndRun("""
				(setq x (cons 1 2))
				(setf (cdr x) 20)
				(print (cdr x))
				""")).isEqualTo("20");
	}

	@Test
	void compileAndRunSetfNth() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 1 2 3))
				(setf (nth 1 x) 20)
				(print (nth 1 x))
				""")).isEqualTo("20");
	}

	@Test
	void compileAndRunSetfSecond() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 1 2 3))
				(setf (second x) 20)
				(print (second x))
				""")).isEqualTo("20");
	}

	@Test
	void compileAndRunSetfReturnsValue() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 1 2 3))
				(print (setf (car x) 42))
				""")).isEqualTo("42");
	}

	@Test
	void compileAndRunEqSameInteger() throws Exception {
		assertThat(compileAndRun("(print (if (eq 1 1) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunEqDifferentInteger() throws Exception {
		assertThat(compileAndRun("(print (if (eq 1 2) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunEqSymbols() throws Exception {
		assertThat(compileAndRun("(print (if (eq 'foo 'foo) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunEqNilNil() throws Exception {
		assertThat(compileAndRun("(print (if (eq nil nil) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunEqNilAndValue() throws Exception {
		assertThat(compileAndRun("(print (if (eq nil 1) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunPush() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 2 3))
				(push 1 x)
				(print x)
				""")).isEqualTo("(1 2 3)");
	}

	@Test
	void compileAndRunPop() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 1 2 3))
				(print (pop x))
				(print x)
				""")).isEqualTo("1\n(2 3)");
	}

	@Test
	void compileAndRunRemfHead() throws Exception {
		assertThat(compileAndRun("""
				(setq plist (list 'a 1 'b 2 'c 3))
				(remf plist 'a)
				(print plist)
				""")).isEqualTo("(b 2 c 3)");
	}

	@Test
	void compileAndRunRemfMiddle() throws Exception {
		assertThat(compileAndRun("""
				(setq plist (list 'a 1 'b 2 'c 3))
				(remf plist 'b)
				(print plist)
				""")).isEqualTo("(a 1 c 3)");
	}

	@Test
	void compileAndRunRemfTail() throws Exception {
		assertThat(compileAndRun("""
				(setq plist (list 'a 1 'b 2 'c 3))
				(remf plist 'c)
				(print plist)
				""")).isEqualTo("(a 1 b 2)");
	}

	@Test
	void compileAndRunRemfNotFound() throws Exception {
		assertThat(compileAndRun("""
				(setq plist (list 'a 1 'b 2))
				(print (if (remf plist 'z) 42 99))
				""")).isEqualTo("99");
	}

	@Test
	void compileAndRunRemfEmpty() throws Exception {
		assertThat(compileAndRun("""
				(setq plist nil)
				(print (if (remf plist 'a) 42 99))
				""")).isEqualTo("99");
	}

	@Test
	void compileAndRunKeywordPrint() throws Exception {
		assertThat(compileAndRun("(print :foo)")).isEqualTo(":foo");
	}

	@Test
	void compileAndRunKeywordEq() throws Exception {
		assertThat(compileAndRun("(print (if (eq :foo :foo) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (eq :foo :bar) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunKeywordp() throws Exception {
		assertThat(compileAndRun("(print (if (keywordp :foo) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (keywordp 'foo) 42 99))")).isEqualTo("99");
		assertThat(compileAndRun("(print (if (keywordp 42) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunKeywordSymbolp() throws Exception {
		assertThat(compileAndRun("(print (if (symbolp :foo) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunReduceWithBuiltinPlus() throws Exception {
		assertThat(compileAndRun("(print (reduce + 0 '(1 2 3 4 5)))")).isEqualTo("15");
	}

	@Test
	void compileAndRunReduceWithBuiltinMul() throws Exception {
		assertThat(compileAndRun("(print (reduce * 1 '(1 2 3 4 5)))")).isEqualTo("120");
	}

	@Test
	void compileAndRunMapWithBuiltinCar() throws Exception {
		assertThat(compileAndRun("(print (map car '((1 2) (3 4) (5 6))))")).isEqualTo("(1 3 5)");
	}

	@Test
	void compileAndRunMapWithBuiltinCdr() throws Exception {
		assertThat(compileAndRun("(print (map cdr '((1 2) (3 4) (5 6))))")).isEqualTo("((2) (4) (6))");
	}

	@Test
	void compileAndRunMapWithBuiltin1Plus() throws Exception {
		assertThat(compileAndRun("(print (map 1+ '(1 2 3)))")).isEqualTo("(2 3 4)");
	}

	@Test
	void compileAndRunFuncallWithBuiltinPlus() throws Exception {
		assertThat(compileAndRun("(print (funcall + 3 4))")).isEqualTo("7");
	}

	@Test
	void compileAndRunBuiltinAsVariable() throws Exception {
		assertThat(compileAndRun("""
				(setq my-op +)
				(print (funcall my-op 10 20))
				""")).isEqualTo("30");
	}

	// read-line tests

	private String compileAndRunWithStdin(String lispCode, String stdin) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler.compile(program);
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);

		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			InputStream oldIn = System.in;
			System.setOut(new PrintStream(baos));
			System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
				System.setIn(oldIn);
			}
			return baos.toString().trim();
		}
	}

	@Test
	void compileAndRunReadLine() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read-line))", "hello\n")).isEqualTo("\"hello\"");
	}

	@Test
	void compileAndRunReadLineEof() throws Exception {
		assertThat(compileAndRunWithStdin("(print (null (read-line)))", "")).isEqualTo("1");
	}

	@Test
	void compileAndRunReadLineStringp() throws Exception {
		assertThat(compileAndRunWithStdin("(print (stringp (read-line)))", "hello\n")).isEqualTo("1");
	}

	// === read ===

	@Test
	void compileAndRunReadInteger() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "42\n")).isEqualTo("42");
	}

	@Test
	void compileAndRunReadNegativeInteger() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "-7\n")).isEqualTo("-7");
	}

	@Test
	void compileAndRunReadBigInteger() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "100000000000000000000\n"))
			.isEqualTo("100000000000000000000");
	}

	@Test
	void compileAndRunReadFloat() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "3.14\n")).isEqualTo("3.14");
	}

	@Test
	void compileAndRunReadSymbol() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "foo\n")).isEqualTo("foo");
	}

	@Test
	void compileAndRunReadString() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "\"hello\"\n")).isEqualTo("\"hello\"");
	}

	@Test
	void compileAndRunReadList() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "(+ 1 2)\n")).isEqualTo("(+ 1 2)");
	}

	@Test
	void compileAndRunReadCarList() throws Exception {
		assertThat(compileAndRunWithStdin("(print (car (read)))", "(a b c)\n")).isEqualTo("a");
	}

	@Test
	void compileAndRunReadQuote() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "'x\n")).isEqualTo("(quote x)");
	}

	@Test
	void compileAndRunReadNil() throws Exception {
		assertThat(compileAndRunWithStdin("(print (null (read)))", "nil\n")).isEqualTo("1");
	}

	@Test
	void compileAndRunReadThenEval() throws Exception {
		assertThat(compileAndRunWithStdin("(print (eval (read)))", "(+ 1 2 3)\n")).isEqualTo("6");
	}

	@Test
	void compileAndRunReadEof() throws Exception {
		assertThat(compileAndRunWithStdin("(print (null (read)))", "")).isEqualTo("1");
	}

	// === load ===

	@Test
	void compileAndRunLoadDefun() throws Exception {
		Path lib = tempDir.resolve("lib.lisp");
		Files.writeString(lib, "(defun square (x) (* x x))\n(setq base 10)\n");
		// Definitions from the loaded file live in the eval runtime's global env, so they
		// are used through eval.
		String code = "(load \"" + lib + "\") (print (eval '(square base)))";
		assertThat(compileAndRun(code)).isEqualTo("100");
	}

	@Test
	void compileAndRunLoadMultipleForms() throws Exception {
		Path lib = tempDir.resolve("lib2.lisp");
		Files.writeString(lib, "(defun inc (x) (+ x 1))\n(defun dbl (x) (* x 2))\n");
		String code = "(load \"" + lib + "\") (print (eval '(dbl (inc 4))))";
		assertThat(compileAndRun(code)).isEqualTo("10");
	}

	// === eval ===

	@Test
	void evalSelfEvaluatingInteger() throws Exception {
		assertThat(compileAndRun("(print (eval 42))")).isEqualTo("42");
	}

	@Test
	void evalQuotedArithmetic() throws Exception {
		assertThat(compileAndRun("(print (eval '(+ 1 2)))")).isEqualTo("3");
	}

	@Test
	void evalVariadicArithmetic() throws Exception {
		assertThat(compileAndRun("(print (eval '(+ 1 2 3 4)))")).isEqualTo("10");
	}

	@Test
	void evalQuotedList() throws Exception {
		assertThat(compileAndRun("(print (eval '(list 1 2 3)))")).isEqualTo("(1 2 3)");
	}

	@Test
	void evalIf() throws Exception {
		assertThat(compileAndRun("(print (eval '(if (= 1 1) 10 20)))")).isEqualTo("10");
	}

	@Test
	void evalLetBindsVariable() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((x 10)) (+ x 5))))")).isEqualTo("15");
	}

	@Test
	void evalLambdaCapturesLexicalEnv() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((n 3)) (funcall (lambda (x) (+ x n)) 4))))")).isEqualTo("7");
	}

	@Test
	void evalCond() throws Exception {
		assertThat(compileAndRun("(print (eval '(cond ((= 1 2) 10) ((= 1 1) 20) (t 30))))")).isEqualTo("20");
	}

	@Test
	void evalAnd() throws Exception {
		assertThat(compileAndRun("(print (eval '(and 1 2 3)))")).isEqualTo("3");
	}

	@Test
	void evalOr() throws Exception {
		assertThat(compileAndRun("(print (eval '(or nil 5 nil)))")).isEqualTo("5");
	}

	@Test
	void evalWhenUnless() throws Exception {
		assertThat(compileAndRun("(print (eval '(when (= 1 1) 99)))")).isEqualTo("99");
		assertThat(compileAndRun("(print (eval '(unless (= 1 2) 88)))")).isEqualTo("88");
	}

	@Test
	void evalWhile() throws Exception {
		assertThat(compileAndRun(
				"(print (eval '(let ((n 0) (s 0)) (while (< n 5) (setq s (+ s n)) (setq n (+ n 1))) s)))"))
			.isEqualTo("10");
	}

	@Test
	void evalDotimes() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((s 0)) (dotimes (i 5) (setq s (+ s i))) s)))")).isEqualTo("10");
		assertThat(compileAndRun("(print (eval '(dotimes (i 3))))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (eval '(let ((acc 1)) (dotimes (i 4 acc) (setq acc (* acc 2))))))"))
			.isEqualTo("16");
		// the loop variable holds the count value when the result form is evaluated
		assertThat(compileAndRun("(print (eval '(dotimes (i 3 i))))")).isEqualTo("3");
	}

	@Test
	void evalSetqInLet() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((x 1)) (setq x 42) x)))")).isEqualTo("42");
	}

	@Test
	void evalSetqGlobalPersistsAcrossEvalCalls() throws Exception {
		assertThat(compileAndRun("(eval '(setq counter 10)) (print (eval 'counter))")).isEqualTo("10");
	}

	@Test
	void evalRuntimeFunctionDefinition() throws Exception {
		assertThat(compileAndRun("(eval '(setq sq (lambda (x) (* x x)))) (print (eval '(funcall sq 6)))"))
			.isEqualTo("36");
	}

	@Test
	void evalNestedEval() throws Exception {
		assertThat(compileAndRun("(print (eval '(eval '(+ 2 3))))")).isEqualTo("5");
	}

	@Test
	void evalFuncall() throws Exception {
		assertThat(compileAndRun("(print (eval '(funcall (lambda (x y) (+ x y)) 3 4)))")).isEqualTo("7");
	}

	@Test
	void evalMapWithLambda() throws Exception {
		assertThat(compileAndRun("(print (eval '(map (lambda (x) (* x x)) (list 1 2 3))))")).isEqualTo("(1 4 9)");
	}

	@Test
	void evalReduce() throws Exception {
		assertThat(compileAndRun("(print (eval '(reduce (lambda (a b) (+ a b)) (list 1 2 3 4))))")).isEqualTo("10");
	}

	@Test
	void evalCarCdrComposition() throws Exception {
		assertThat(compileAndRun("(print (eval '(cadr (list 1 2 3))))")).isEqualTo("2");
	}

	@Test
	void evalNumberedAccessors() throws Exception {
		assertThat(compileAndRun("(print (eval '(third (list 10 20 30 40))))")).isEqualTo("30");
	}

	@Test
	void evalNth() throws Exception {
		assertThat(compileAndRun("(print (eval '(nth 2 (list 10 20 30 40))))")).isEqualTo("30");
	}

	@Test
	void evalUserDefinedFunction() throws Exception {
		assertThat(compileAndRun("(defun double (x) (* x 2)) (print (eval '(double 21)))")).isEqualTo("42");
	}

	@Test
	void evalBuiltinAsValue() throws Exception {
		assertThat(compileAndRun("(print (eval '(funcall + 10 20)))")).isEqualTo("30");
	}

	@Test
	void evalSetfCarCdr() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((x (list 1 2 3))) (setf (car x) 99) x)))")).isEqualTo("(99 2 3)");
	}

	@Test
	void evalPush() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((x (list 2 3))) (push 1 x) x)))")).isEqualTo("(1 2 3)");
	}

	@Test
	void evalPop() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((x (list 1 2 3))) (pop x))))")).isEqualTo("1");
	}

	@Test
	void bigIntegerFactorialPromotesOnOverflow() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (fact 32))
				""";
		assertThat(compileAndRun(code)).isEqualTo("263130836933693530167218012160000000");
	}

	@Test
	void bigIntegerAdditionOverflow() throws Exception {
		assertThat(compileAndRun("(print (+ 9223372036854775807 1))")).isEqualTo("9223372036854775808");
	}

	@Test
	void bigIntegerMultiplicationOverflow() throws Exception {
		assertThat(compileAndRun("(print (* 1000000000000 1000000000000))")).isEqualTo("1000000000000000000000000");
	}

	@Test
	void bigIntegerSubtractionUnderflow() throws Exception {
		assertThat(compileAndRun("(print (- -9223372036854775807 1000))")).isEqualTo("-9223372036854776807");
	}

	@Test
	void bigIntegerResultDemotedWhenFitsInLong() throws Exception {
		// (fact 32) / (fact 31) = 32 fits in a long again.
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (/ (fact 32) (fact 31)))
				""";
		assertThat(compileAndRun(code)).isEqualTo("32");
	}

	@Test
	void bigIntegerLiteral() throws Exception {
		assertThat(compileAndRun("(print 123456789012345678901234567890)")).isEqualTo("123456789012345678901234567890");
	}

	@Test
	void bigIntegerLiteralArithmetic() throws Exception {
		assertThat(compileAndRun("(print (+ 123456789012345678901234567890 1))"))
			.isEqualTo("123456789012345678901234567891");
	}

	@Test
	void bigIntegerComparison() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (> (fact 30) (fact 25)))
				(print (< (fact 30) (fact 25)))
				""";
		// In compiled output a true boolean is represented as the integer 1.
		assertThat(compileAndRun(code)).isEqualTo("1\nnil");
	}

	@Test
	void bigIntegerIntegerp() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (integerp (fact 25)))
				""";
		// In compiled output a true boolean is represented as the integer 1.
		assertThat(compileAndRun(code)).isEqualTo("1");
	}

	@Test
	void bigIntegerEvenp() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (evenp (fact 25)))
				""";
		// In compiled output a true boolean is represented as the integer 1.
		assertThat(compileAndRun(code)).isEqualTo("1");
	}

	@Test
	void bigIntegerMod() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (mod (fact 25) 1000000007))
				""";
		assertThat(compileAndRun(code)).isEqualTo("440732388");
	}

	@Test
	void bigIntegerAbsAndNegation() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (abs (- (fact 25))))
				""";
		assertThat(compileAndRun(code)).isEqualTo("15511210043330985984000000");
	}

	@Test
	void bigIntegerMax() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (max (fact 25) (fact 30)))
				""";
		assertThat(compileAndRun(code)).isEqualTo("265252859812191058636308480000000");
	}

}
