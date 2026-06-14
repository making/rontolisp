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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
	void withOpenFileWriteThenRead() throws Exception {
		String file = tempDir.resolve("wof.txt").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(with-open-file (out "%s" :direction :output)
				  (write-line "hello" out)
				  (write-line "world" out))
				(with-open-file (in "%s")
				  (print (read-line in))
				  (print (read-line in))
				  (print (read-line in)))
				""".formatted(file, file))).isEqualTo("\"hello\"\n\"world\"\nnil");
	}

	@Test
	void withOpenFileReturnsBodyValue() throws Exception {
		String file = tempDir.resolve("wof-ret.txt").toString().replace("\\", "\\\\");
		assertThat(compileAndRun(
				"(print (with-open-file (out \"" + file + "\" :direction :output) (write-line \"x\" out) 42))"))
			.isEqualTo("42");
	}

	@Test
	void openCloseExplicitStreams() throws Exception {
		String file = tempDir.resolve("manual.txt").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(setq out (open "%s" :output))
				(write-line "line1" out)
				(close out)
				(setq in (open "%s" :input))
				(print (read-line in))
				(close in)
				""".formatted(file, file))).isEqualTo("\"line1\"");
	}

	@Test
	void writeLineWithoutStreamPrintsToStdout() throws Exception {
		assertThat(compileAndRun("(write-line \"to stdout\")")).isEqualTo("to stdout");
	}

	@Test
	void readLinesInLoop() throws Exception {
		String file = tempDir.resolve("loop.txt").toString().replace("\\", "\\\\");
		assertThat(compileAndRun("""
				(with-open-file (out "%s" :direction :output)
				  (write-line "a" out)
				  (write-line "b" out)
				  (write-line "c" out))
				(with-open-file (in "%s")
				  (setq line (read-line in))
				  (while line
				    (princ line)
				    (setq line (read-line in))))
				""".formatted(file, file))).isEqualTo("abc");
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
	void compileAndRunModTakesSignOfDivisor() throws Exception {
		assertThat(compileAndRun("(print (mod 13 4))")).isEqualTo("1");
		assertThat(compileAndRun("(print (mod -13 4))")).isEqualTo("3");
		assertThat(compileAndRun("(print (mod 13 -4))")).isEqualTo("-3");
		assertThat(compileAndRun("(print (mod -13 -4))")).isEqualTo("-1");
	}

	@Test
	void compileAndRunRemTakesSignOfDividend() throws Exception {
		assertThat(compileAndRun("(print (rem 13 4))")).isEqualTo("1");
		assertThat(compileAndRun("(print (rem -13 4))")).isEqualTo("-1");
		assertThat(compileAndRun("(print (rem 13 -4))")).isEqualTo("1");
		assertThat(compileAndRun("(print (rem -13 -4))")).isEqualTo("-1");
	}

	@Test
	void compileAndRunModBigInteger() throws Exception {
		assertThat(compileAndRun("(print (mod (- 0 (* 100000000000 100000000000)) 7))")).isEqualTo("3");
	}

	@Test
	void compileAndRunModFloat() throws Exception {
		assertThat(compileAndRun("(print (mod -5.5 2.0))")).isEqualTo("0.5");
	}

	@Test
	void compileAndRunVariadicComparison() throws Exception {
		// A true boolean is the symbol t, like the interpreter.
		assertThat(compileAndRun("(print (< 1 2 3 4))")).isEqualTo("t");
		assertThat(compileAndRun("(print (< 1 2 2 4))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (<= 1 2 2 4))")).isEqualTo("t");
		assertThat(compileAndRun("(print (= 3 3 3))")).isEqualTo("t");
		assertThat(compileAndRun("(print (> 5 4 3 2 1))")).isEqualTo("t");
		assertThat(compileAndRun("(print (< 5))")).isEqualTo("t");
	}

	@Test
	void compileBooleanIsSymbolT() throws Exception {
		// A boolean true prints as the symbol t (not the integer 1), matching the
		// interpreter, so it is indistinguishable from t in a list.
		assertThat(compileAndRun("(print (list (= 1 1) (= 1 0)))")).isEqualTo("(t nil)");
		assertThat(compileAndRun("(print t)")).isEqualTo("t");
		assertThat(compileAndRun("(print (eq t (= 1 1)))")).isEqualTo("t");
	}

	@Test
	void compileAndRunVariadicMinMax() throws Exception {
		assertThat(compileAndRun("(print (min 5 2 8 1 9))")).isEqualTo("1");
		assertThat(compileAndRun("(print (max 5 2 8 1 9))")).isEqualTo("9");
		assertThat(compileAndRun("(print (min 7))")).isEqualTo("7");
	}

	@Test
	void compileAndRunVariadicGcdLcm() throws Exception {
		assertThat(compileAndRun("(print (gcd 24 36 60))")).isEqualTo("12");
		assertThat(compileAndRun("(print (lcm 2 3 4))")).isEqualTo("12");
		assertThat(compileAndRun("(print (gcd -8))")).isEqualTo("8");
	}

	@Test
	void compileAndRunLengthOfString() throws Exception {
		assertThat(compileAndRun("(print (length \"hello\"))")).isEqualTo("5");
		assertThat(compileAndRun("(print (length \"\"))")).isEqualTo("0");
	}

	@Test
	void compileAndRunLengthOfList() throws Exception {
		assertThat(compileAndRun("(print (length (list 10 20 30)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (length nil))")).isEqualTo("0");
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
		assertThat(compileAndRun("(print (/ 10 3))")).isEqualTo("10/3");
		assertThat(compileAndRun("(print (/ 10 2))")).isEqualTo("5");
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
				(print (funcall square 5))
				""")).isEqualTo("25");
	}

	@Test
	void compileAndRunSetqLambdaFactorial() throws Exception {
		// Lisp-2: a recursive function must be defined with defun (a lambda bound by
		// setq cannot refer to itself through the variable namespace in compiled code).
		assertThat(compileAndRun("""
				(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
				(setq fact5 #'fact)
				(print (funcall fact5 5))
				""")).isEqualTo("120");
	}

	@Test
	void compileAndRunSetqLambdaNoParams() throws Exception {
		assertThat(compileAndRun("""
				(setq answer (lambda () 42))
				(print (funcall answer))
				""")).isEqualTo("42");
	}

	@Test
	void compileAndRunSetqLambdaMultipleFunctions() throws Exception {
		assertThat(compileAndRun("""
				(setq double (lambda (x) (* x 2)))
				(setq add1 (lambda (x) (+ x 1)))
				(print (funcall add1 (funcall double 5)))
				""")).isEqualTo("11");
	}

	@Test
	void compileAndRunMixedDefunAndSetqLambda() throws Exception {
		assertThat(compileAndRun("""
				(defun double (x) (* x 2))
				(setq add1 (lambda (x) (+ x 1)))
				(print (funcall add1 (double 5)))
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
	void compileAndRunFormat() throws Exception {
		assertThat(compileAndRun("(format t \"Hello ~a, you are ~d! ~s~%\" 'world 42 \"str\")"))
			.isEqualTo("Hello world, you are 42! \"str\"");
	}

	@Test
	void compileAndRunFormatList() throws Exception {
		assertThat(compileAndRun("(format t \"list=~a tilde=~~\" (list 1 2 3))")).isEqualTo("list=(1 2 3) tilde=~");
	}

	@Test
	void compileAndRunFormatInsideDefun() throws Exception {
		assertThat(compileAndRun("(defun greet (name) (format t \"Hi, ~a!~%\" name)) (greet 'alice) (greet \"bob\")"))
			.isEqualTo("Hi, alice!\nHi, bob!");
	}

	@Test
	void compileAndRunFormatNil() throws Exception {
		assertThat(compileAndRun("(princ (format nil \"Hello ~a, ~d! ~s~%\" 'world 42 \"str\"))"))
			.isEqualTo("Hello world, 42! \"str\"");
	}

	@Test
	void compileAndRunFormatNilList() throws Exception {
		assertThat(compileAndRun("(princ (format nil \"list=~a tilde=~~\" (list 1 2 3)))"))
			.isEqualTo("list=(1 2 3) tilde=~");
	}

	@Test
	void compileAndRunFormatNilIsString() throws Exception {
		assertThat(compileAndRun("(print (stringp (format nil \"~a\" 1))) (print (length (format nil \"~a\" 12345)))"))
			.isEqualTo("t\n5");
	}

	@Test
	void compileAndRunPrincToString() throws Exception {
		assertThat(compileAndRun("(print (princ-to-string 42)) (princ (princ-to-string 'sym))"))
			.isEqualTo("\"42\"\nsym");
	}

	@Test
	void compileAndRunPrin1ToString() throws Exception {
		assertThat(compileAndRun("(princ (prin1-to-string \"abc\"))")).isEqualTo("\"abc\"");
	}

	@Test
	void compileAndRunConcatenate() throws Exception {
		assertThat(compileAndRun("(princ (concatenate 'string \"foo\" \"bar\" \"baz\"))")).isEqualTo("foobarbaz");
	}

	@Test
	void compileAndRunPrincToStringAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'princ-to-string (list 1 2)))")).isEqualTo("(\"1\" \"2\")");
	}

	@Test
	void compileAndRunStringUpcaseDowncase() throws Exception {
		assertThat(compileAndRun("(princ (string-upcase \"Hello, World\"))")).isEqualTo("HELLO, WORLD");
		assertThat(compileAndRun("(princ (string-downcase \"Hello, World\"))")).isEqualTo("hello, world");
	}

	@Test
	void compileAndRunStringCapitalize() throws Exception {
		assertThat(compileAndRun("(princ (string-capitalize \"hello world  foo\"))")).isEqualTo("Hello World  Foo");
	}

	@Test
	void compileAndRunSubseq() throws Exception {
		assertThat(compileAndRun("(princ (subseq \"hello world\" 6))")).isEqualTo("world");
		assertThat(compileAndRun("(princ (subseq \"hello world\" 0 5))")).isEqualTo("hello");
	}

	@Test
	void compileAndRunSubseqList() throws Exception {
		assertThat(compileAndRun("(print (subseq '(1 2 3 4 5) 1 3))")).isEqualTo("(2 3)");
		assertThat(compileAndRun("(print (subseq '(1 2 3 4 5) 2))")).isEqualTo("(3 4 5)");
		assertThat(compileAndRun("(print (subseq '(a b c) 0))")).isEqualTo("(a b c)");
		assertThat(compileAndRun("(print (subseq '(1 2 3) 3))")).isEqualTo("nil");
	}

	@Test
	void compileAndRunStringEquality() throws Exception {
		assertThat(compileAndRun("(print (string= \"abc\" \"abc\"))")).isEqualTo("t");
		assertThat(compileAndRun("(print (string= \"abc\" \"abd\"))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (string-equal \"ABC\" \"abc\"))")).isEqualTo("t");
	}

	@Test
	void compileAndRunStringTrim() throws Exception {
		assertThat(compileAndRun("(princ (string-trim \" xy\" \"xyhelloyx \"))")).isEqualTo("hello");
		assertThat(compileAndRun("(princ (string-left-trim \"x\" \"xxhello\"))")).isEqualTo("hello");
		assertThat(compileAndRun("(princ (string-right-trim \"x\" \"helloxx\"))")).isEqualTo("hello");
	}

	@Test
	void compileAndRunStringFunctionsAsValues() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'string-upcase (list \"ab\" \"cd\")))")).isEqualTo("(\"AB\" \"CD\")");
		assertThat(compileAndRun("(print (funcall #'subseq \"hello\" 2))")).isEqualTo("\"llo\"");
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
				(defun apply-twice (f x) (funcall f (funcall f x)))
				(print (apply-twice #'square 3))
				""")).isEqualTo("81");
	}

	@Test
	void compileAndRunLambdaAsArgument() throws Exception {
		assertThat(compileAndRun("""
				(defun apply-twice (f x) (funcall f (funcall f x)))
				(print (apply-twice (lambda (x) (+ x 10)) 5))
				""")).isEqualTo("25");
	}

	@Test
	void compileAndRunClosure() throws Exception {
		assertThat(compileAndRun("""
				(defun make-adder (n) (lambda (x) (+ x n)))
				(setq add5 (make-adder 5))
				(print (funcall add5 10))
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
				(funcall counter)
				(funcall counter)
				(print (funcall counter))
				""")).isEqualTo("3");
	}

	@Test
	void compileAndRunDynamicFunctionSelection() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(defun forty-two (x) 42)
				(setq f (if t #'square #'forty-two))
				(print (funcall f 6))
				""")).isEqualTo("36");
	}

	@Test
	void compileAndRunFuncall() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(print (funcall #'square 7))
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
				(print (funcall (car (list #'square)) 5))
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
	void compileAndRunPiConstant() throws Exception {
		assertThat(compileAndRun("(print pi)")).isEqualTo("3.141592653589793");
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
	void compileAndRunSqrt() throws Exception {
		assertThat(compileAndRun("(print (sqrt 16))")).isEqualTo("4.0");
		assertThat(compileAndRun("(print (sqrt 2))")).isEqualTo("1.4142135623730951");
		assertThat(compileAndRun("(print (sqrt 2.0))")).isEqualTo("1.4142135623730951");
	}

	@Test
	void compileAndRunIsqrt() throws Exception {
		assertThat(compileAndRun("(print (isqrt 17))")).isEqualTo("4");
		assertThat(compileAndRun("(print (isqrt 16))")).isEqualTo("4");
	}

	@Test
	void compileAndRunExpt() throws Exception {
		assertThat(compileAndRun("(print (expt 2 10))")).isEqualTo("1024");
		assertThat(compileAndRun("(print (expt 3 0))")).isEqualTo("1");
		assertThat(compileAndRun("(print (expt 2.0 3))")).isEqualTo("8.0");
		assertThat(compileAndRun("(print (expt 2 70))")).isEqualTo("1180591620717411303424");
	}

	@Test
	void compileAndRunGcdLcm() throws Exception {
		assertThat(compileAndRun("(print (gcd 12 18))")).isEqualTo("6");
		assertThat(compileAndRun("(print (gcd 0 5))")).isEqualTo("5");
		assertThat(compileAndRun("(print (lcm 4 6))")).isEqualTo("12");
		assertThat(compileAndRun("(print (lcm 0 6))")).isEqualTo("0");
	}

	@Test
	void compileAndRunSignum() throws Exception {
		assertThat(compileAndRun("(print (signum -5))")).isEqualTo("-1");
		assertThat(compileAndRun("(print (signum 0))")).isEqualTo("0");
		assertThat(compileAndRun("(print (signum 7))")).isEqualTo("1");
		assertThat(compileAndRun("(print (signum 3.5))")).isEqualTo("1.0");
	}

	@Test
	void compileAndRunTranscendental() throws Exception {
		assertThat(compileAndRun("(print (sin 0))")).isEqualTo("0.0");
		assertThat(compileAndRun("(print (cos 0))")).isEqualTo("1.0");
		assertThat(compileAndRun("(print (exp 0))")).isEqualTo("1.0");
		assertThat(compileAndRun("(print (log 1))")).isEqualTo("0.0");
		assertThat(compileAndRun("(print (atan 0))")).isEqualTo("0.0");
		assertThat(compileAndRun("(print (tanh 0))")).isEqualTo("0.0");
	}

	@Test
	void compileAndRunMathAsFirstClass() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'sqrt (list 1 4 9)))")).isEqualTo("(1.0 2.0 3.0)");
		assertThat(compileAndRun("(print (reduce #'gcd (list 24 36 48)))")).isEqualTo("12");
		assertThat(compileAndRun("(print (eval (list (quote expt) 2 8)))")).isEqualTo("256");
		assertThat(compileAndRun("(print (eval (list (quote sin) 0)))")).isEqualTo("0.0");
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
		assertThat(compileAndRun("(print (reduce #'+ 0 '(1 2 3 4 5)))")).isEqualTo("15");
	}

	@Test
	void compileAndRunReduceWithBuiltinMul() throws Exception {
		assertThat(compileAndRun("(print (reduce #'* 1 '(1 2 3 4 5)))")).isEqualTo("120");
	}

	@Test
	void compileAndRunRest() throws Exception {
		assertThat(compileAndRun("(print (rest '(1 2 3))) (print (rest '(1)))")).isEqualTo("(2 3)\nnil");
	}

	@Test
	void compileAndRunSetfRestPlace() throws Exception {
		assertThat(compileAndRun("(setq l (list 1 2 3)) (setf (rest l) '(9)) (print l)")).isEqualTo("(1 9)");
	}

	@Test
	void compileAndRunRestInsideEval() throws Exception {
		assertThat(compileAndRun("(print (eval '(rest '(1 2 3))))")).isEqualTo("(2 3)");
	}

	@Test
	void compileAndRunLetStar() throws Exception {
		assertThat(compileAndRun("(print (let* ((x 2) (y (* x 3))) (+ x y)))")).isEqualTo("8");
	}

	@Test
	void compileAndRunDolist() throws Exception {
		assertThat(compileAndRun("(setq s 0) (dolist (e '(1 2 3 4)) (setq s (+ s e))) (print s)")).isEqualTo("10");
	}

	@Test
	void compileAndRunCaseSingleKey() throws Exception {
		assertThat(compileAndRun("(print (case 2 (1 'one) (2 'two) (3 'three)))")).isEqualTo("two");
	}

	@Test
	void compileAndRunCaseKeyList() throws Exception {
		assertThat(compileAndRun("(print (case 3 (1 'one) ((2 3 4) 'small) (otherwise 'big)))")).isEqualTo("small");
	}

	@Test
	void compileAndRunCaseOtherwise() throws Exception {
		assertThat(compileAndRun("(print (case 99 (1 'one) ((2 3 4) 'small) (otherwise 'big)))")).isEqualTo("big");
	}

	@Test
	void compileAndRunCaseNoMatchReturnsNil() throws Exception {
		assertThat(compileAndRun("(print (case 5 (1 'a) (2 'b)))")).isEqualTo("nil");
	}

	@Test
	void compileAndRunDolistResultForm() throws Exception {
		assertThat(compileAndRun("(print (dolist (e '(1 2) 99)))")).isEqualTo("99");
	}

	@Test
	void compileAndRunIncfDecf() throws Exception {
		assertThat(compileAndRun("(setq n 10) (incf n) (incf n 5) (decf n 6) (print n)")).isEqualTo("10");
	}

	@Test
	void compileAndRunIncfPlace() throws Exception {
		assertThat(compileAndRun("(setq l (list 1 2 3)) (incf (cadr l)) (print l)")).isEqualTo("(1 3 3)");
	}

	@Test
	void compileAndRunLength() throws Exception {
		assertThat(compileAndRun("(print (length '(1 2 3 4 5))) (print (length nil))")).isEqualTo("5\n0");
	}

	@Test
	void compileAndRunReverse() throws Exception {
		assertThat(compileAndRun("(print (reverse '(1 2 3))) (print (reverse nil))")).isEqualTo("(3 2 1)\nnil");
	}

	@Test
	void compileAndRunMember() throws Exception {
		assertThat(compileAndRun("(print (member 3 '(1 2 3 4))) (print (member 9 '(1 2 3)))")).isEqualTo("(3 4)\nnil");
	}

	@Test
	void compileAndRunAssoc() throws Exception {
		assertThat(compileAndRun("(print (assoc 'b '((a 1) (b 2) (c 3)))) (print (assoc 'z '((a 1))))"))
			.isEqualTo("(b 2)\nnil");
	}

	@Test
	void compileAndRunLast() throws Exception {
		assertThat(compileAndRun("(print (last '(1 2 3))) (print (last nil))")).isEqualTo("(3)\nnil");
	}

	@Test
	void compileAndRunSequenceFunctionsAsFirstClass() throws Exception {
		assertThat(compileAndRun("(print (funcall #'length '(7 8 9))) (print (mapcar #'reverse '((1 2) (3 4))))"))
			.isEqualTo("3\n((2 1) (4 3))");
	}

	@Test
	void compileAndRunSequenceFunctionInsideEval() throws Exception {
		assertThat(compileAndRun("(print (eval '(reverse '(1 2 3))))")).isEqualTo("(3 2 1)");
	}

	@Test
	void compileAndRunMapWithBuiltinCar() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'car '((1 2) (3 4) (5 6))))")).isEqualTo("(1 3 5)");
	}

	@Test
	void compileAndRunMapWithBuiltinCdr() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'cdr '((1 2) (3 4) (5 6))))")).isEqualTo("((2) (4) (6))");
	}

	@Test
	void compileAndRunMapWithBuiltin1Plus() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'1+ '(1 2 3)))")).isEqualTo("(2 3 4)");
	}

	@Test
	void compileAndRunFuncallWithBuiltinPlus() throws Exception {
		assertThat(compileAndRun("(print (funcall #'+ 3 4))")).isEqualTo("7");
	}

	@Test
	void compileAndRunBuiltinAsVariable() throws Exception {
		assertThat(compileAndRun("""
				(setq my-op #'+)
				(print (funcall my-op 10 20))
				""")).isEqualTo("30");
	}

	// Lisp-2 (separate function/variable namespaces) tests

	@Test
	void compileAndRunFuncallSharpQuotedPlus() throws Exception {
		assertThat(compileAndRun("(print (funcall #'+ 1 2))")).isEqualTo("3");
	}

	@Test
	void compileAndRunMapSharpQuotedCar() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'car '((1 2) (3 4))))")).isEqualTo("(1 3)");
	}

	@Test
	void compileAndRunFuncallQuotedSymbolDesignator() throws Exception {
		assertThat(compileAndRun("(print (funcall 'car '(9 8)))")).isEqualTo("9");
	}

	@Test
	void compileAndRunMapSharpQuotedCadr() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'cadr '((1 2) (3 4))))")).isEqualTo("(2 4)");
	}

	@Test
	void compileAndRunSetqSharpQuotedBuiltinThenFuncall() throws Exception {
		assertThat(compileAndRun("""
				(setq f #'+)
				(print (funcall f 1 2))
				""")).isEqualTo("3");
	}

	@Test
	void compileAndRunSymbolFunction() throws Exception {
		assertThat(compileAndRun("(print (funcall (symbol-function 'car) '(5 6)))")).isEqualTo("5");
	}

	@Test
	void compileBareFunctionNameInValuePositionThrows() {
		assertThatThrownBy(() -> compileAndRun("(print car)")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("Cannot compile symbol reference: car");
	}

	@Test
	void compileRatioLiteral() throws Exception {
		assertThat(compileAndRun("(print 1/3)")).isEqualTo("1/3");
		assertThat(compileAndRun("(print -2/4)")).isEqualTo("-1/2");
		assertThat(compileAndRun("(print '4/2)")).isEqualTo("2");
	}

	@Test
	void compileRatioArithmetic() throws Exception {
		assertThat(compileAndRun("(print (+ 1/2 1/3))")).isEqualTo("5/6");
		assertThat(compileAndRun("(print (+ 1/2 1/2))")).isEqualTo("1");
		assertThat(compileAndRun("(print (- 1/2 1/3))")).isEqualTo("1/6");
		assertThat(compileAndRun("(print (* 2/3 3))")).isEqualTo("2");
		assertThat(compileAndRun("(print (/ 1/2 1/3))")).isEqualTo("3/2");
		assertThat(compileAndRun("(print (- 1/2))")).isEqualTo("-1/2");
		assertThat(compileAndRun("(print (+ 1 1/2))")).isEqualTo("3/2");
	}

	@Test
	void compileRatioFloatContagion() throws Exception {
		assertThat(compileAndRun("(print (/ 1 2.0))")).isEqualTo("0.5");
		assertThat(compileAndRun("(print (float 1/2))")).isEqualTo("0.5");
		assertThat(compileAndRun("(print (+ 1/2 0.5))")).isEqualTo("1.0");
	}

	@Test
	void compileRatioComparison() throws Exception {
		assertThat(compileAndRun("(print (if (< 1/3 1/2) 1 0))")).isEqualTo("1");
		assertThat(compileAndRun("(print (if (= 2/4 1/2) 1 0))")).isEqualTo("1");
		assertThat(compileAndRun("(print (if (= 1/2 0.5) 1 0))")).isEqualTo("1");
		assertThat(compileAndRun("(print (eq 1/2 1/2))")).isEqualTo("t");
		assertThat(compileAndRun("(print (max 1/2 1/3))")).isEqualTo("1/2");
		assertThat(compileAndRun("(print (min 1/2 1/3))")).isEqualTo("1/3");
		assertThat(compileAndRun("(print (abs -1/2))")).isEqualTo("1/2");
	}

	@Test
	void compileRatioConversions() throws Exception {
		assertThat(compileAndRun("(print (truncate 7/2))")).isEqualTo("3");
		assertThat(compileAndRun("(print (truncate -7/2))")).isEqualTo("-3");
		assertThat(compileAndRun("(print (floor 7/2))")).isEqualTo("3");
		assertThat(compileAndRun("(print (floor -7/2))")).isEqualTo("-4");
		assertThat(compileAndRun("(print (ceiling 7/2))")).isEqualTo("4");
		assertThat(compileAndRun("(print (ceiling -7/2))")).isEqualTo("-3");
		assertThat(compileAndRun("(print (round 7/2))")).isEqualTo("4");
		assertThat(compileAndRun("(print (round 5/2))")).isEqualTo("2");
		assertThat(compileAndRun("(print (round 1/3))")).isEqualTo("0");
	}

	@Test
	void compileRatioPredicatesAndAccessors() throws Exception {
		assertThat(compileAndRun("(print (numberp 1/2))")).isEqualTo("t");
		assertThat(compileAndRun("(print (integerp 1/2))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (rationalp 1/2))")).isEqualTo("t");
		assertThat(compileAndRun("(print (rationalp 5))")).isEqualTo("t");
		assertThat(compileAndRun("(print (rationalp 0.5))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (numerator 3/4))")).isEqualTo("3");
		assertThat(compileAndRun("(print (denominator 3/4))")).isEqualTo("4");
		assertThat(compileAndRun("(print (numerator 5))")).isEqualTo("5");
		assertThat(compileAndRun("(print (denominator 5))")).isEqualTo("1");
		assertThat(compileAndRun("(print (consp 1/2))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (atom 1/2))")).isEqualTo("t");
		assertThat(compileAndRun("(print (listp 1/2))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (zerop 1/2))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (plusp 1/2))")).isEqualTo("t");
		assertThat(compileAndRun("(print (minusp -1/2))")).isEqualTo("t");
		assertThat(compileAndRun("(print (signum -1/2))")).isEqualTo("-1");
	}

	@Test
	void compileRatioExpt() throws Exception {
		assertThat(compileAndRun("(print (expt 1/2 2))")).isEqualTo("1/4");
		assertThat(compileAndRun("(print (expt 1/2 -2))")).isEqualTo("4");
		assertThat(compileAndRun("(print (expt 2 -1))")).isEqualTo("1/2");
	}

	@Test
	void compileRatioInList() throws Exception {
		assertThat(compileAndRun("(print (list 1/2 2/3))")).isEqualTo("(1/2 2/3)");
		assertThat(compileAndRun("(print (cons 1 1/2))")).isEqualTo("(1 . 1/2)");
		assertThat(compileAndRun("(print (1+ 1/2))")).isEqualTo("3/2");
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
		assertThat(compileAndRunWithStdin("(print (null (read-line)))", "")).isEqualTo("t");
	}

	@Test
	void compileAndRunReadLineStringp() throws Exception {
		assertThat(compileAndRunWithStdin("(print (stringp (read-line)))", "hello\n")).isEqualTo("t");
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
		assertThat(compileAndRunWithStdin("(print (null (read)))", "nil\n")).isEqualTo("t");
	}

	@Test
	void compileAndRunReadThenEval() throws Exception {
		assertThat(compileAndRunWithStdin("(print (eval (read)))", "(+ 1 2 3)\n")).isEqualTo("6");
	}

	@Test
	void compileAndRunReadEof() throws Exception {
		assertThat(compileAndRunWithStdin("(print (null (read)))", "")).isEqualTo("t");
	}

	@Test
	void compileAndRunReadSharpQuote() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "#'car\n")).isEqualTo("(function car)");
	}

	@Test
	void compileAndRunReadSharpQuoteThenEvalFuncall() throws Exception {
		assertThat(compileAndRunWithStdin("(print (eval (read)))", "(funcall #'+ 1 2)\n")).isEqualTo("3");
	}

	@Test
	void compileAndRunReadSharpQuoteLambdaThenEval() throws Exception {
		assertThat(compileAndRunWithStdin("(print (eval (read)))", "(mapcar #'(lambda (x) (* x x)) '(1 2 3))\n"))
			.isEqualTo("(1 4 9)");
	}

	@Test
	void compileAndRunReadSkipsBlankAndCommentLines() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "\n   \n; comment only\n42\n")).isEqualTo("42");
	}

	@Test
	void compileAndRunReadEvalPrintLoop() throws Exception {
		String repl = "(setq form (read)) (while form (print (eval form)) (setq form (read)))";
		assertThat(
				compileAndRunWithStdin(repl, "(defun square (x) (* x x))\n(square 7)\n\n(mapcar #'square '(1 2 3))\n"))
			.isEqualTo("square\n49\n(1 4 9)");
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

	// === dynamic mode (late binding) ===

	private String compileAndRunDynamic(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		JvmLispCompiler compiler = new JvmLispCompiler("Test", true);
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
	void dynamicCallToLoadDefinedFunction() throws Exception {
		Path lib = tempDir.resolve("fn.lisp");
		Files.writeString(lib, "(defun cube (x) (* x x x))\n");
		// (cube 3) is unknown at compile time; --dynamic resolves it at runtime.
		String code = "(load \"" + lib + "\") (print (cube 3))";
		assertThat(compileAndRunDynamic(code)).isEqualTo("27");
	}

	@Test
	void dynamicCallFromCompiledFunctionSeesLocals() throws Exception {
		Path lib = tempDir.resolve("fn2.lisp");
		Files.writeString(lib, "(defun cube (x) (* x x x))\n(defun square (x) (* x x))\n");
		// caller is compiled; its local n must reach the runtime-resolved cube/square.
		String code = "(load \"" + lib + "\") (defun caller (n) (+ (cube n) (square n))) (print (caller 5))";
		assertThat(compileAndRunDynamic(code)).isEqualTo("150");
	}

	@Test
	void dynamicReferenceToLoadDefinedVariable() throws Exception {
		Path lib = tempDir.resolve("var.lisp");
		Files.writeString(lib, "(setq base 7)\n");
		String code = "(load \"" + lib + "\") (print base)";
		assertThat(compileAndRunDynamic(code)).isEqualTo("7");
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
	void evalUnaryMinusNegates() throws Exception {
		assertThat(compileAndRun("(print (eval '(- 5)))")).isEqualTo("-5");
		assertThat(compileAndRun("(print (eval '(- -5)))")).isEqualTo("5");
	}

	@Test
	void evalUnaryDivideReciprocal() throws Exception {
		assertThat(compileAndRun("(print (eval '(/ 2)))")).isEqualTo("1/2");
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
		assertThat(compileAndRun("(print (eval '(mapcar (lambda (x) (* x x)) (list 1 2 3))))")).isEqualTo("(1 4 9)");
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
		assertThat(compileAndRun("(print (eval '(funcall #'+ 10 20)))")).isEqualTo("30");
	}

	@Test
	void evalSharpQuotedBuiltinFuncall() throws Exception {
		assertThat(compileAndRun("(print (eval '(funcall #'+ 10 20)))")).isEqualTo("30");
	}

	@Test
	void evalDefunThenCall() throws Exception {
		assertThat(compileAndRun("(print (eval '(progn (defun sq2 (x) (* x x)) (sq2 6))))")).isEqualTo("36");
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
		// A true boolean is the symbol t, like the interpreter.
		assertThat(compileAndRun(code)).isEqualTo("t\nnil");
	}

	@Test
	void bigIntegerIntegerp() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (integerp (fact 25)))
				""";
		// A true boolean is the symbol t, like the interpreter.
		assertThat(compileAndRun(code)).isEqualTo("t");
	}

	@Test
	void bigIntegerEvenp() throws Exception {
		String code = """
				(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
				(print (evenp (fact 25)))
				""";
		// A true boolean is the symbol t, like the interpreter.
		assertThat(compileAndRun(code)).isEqualTo("t");
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

	@Test
	void compileAndRunRontolispVersion() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:version))")).contains(":version")
			.contains(am.ik.rontolisp.Version.getVersion());
	}

	@Test
	void compileAndRunPackageVar() throws Exception {
		assertThat(compileAndRun("(print *package*)")).isEqualTo("cl-user");
	}

	@Test
	void compileAndRunInPackageThenUnqualifiedVersion() throws Exception {
		String code = """
				(in-package rontolisp)
				(cl:print (cl:cadr (version)))
				""";
		assertThat(compileAndRun(code)).isEqualTo("\"" + am.ik.rontolisp.Version.getVersion() + "\"");
	}

	@Test
	void compileAndRunListMacros() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-macros))")).isEqualTo(
				"(and case cond decf dolist dotimes format incf let* or pop push remf setf unless when with-open-file)");
	}

	@Test
	void compileAndRunListSpecialForms() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-special-forms))"))
			.isEqualTo("(defun function if in-package lambda let progn quote setq while)");
	}

	@Test
	void compileAndRunListFunctionsLength() throws Exception {
		assertThat(compileAndRun("(print (length (rontolisp:list-functions)))")).isEqualTo("104");
	}

	@Test
	void compileAndRunListFunctionsAcceptsBareSymbolDesignator() throws Exception {
		assertThat(compileAndRun("(print (length (rontolisp:list-functions cl)))")).isEqualTo("104");
	}

	@Test
	void compileAndRunListFunctionsForClUserListsUserDefunsOnly() throws Exception {
		// Wrapper defuns injected for built-ins (car, +, ...) must not appear.
		String code = """
				(defun fib (n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
				(defun add2 (a) (+ a 2))
				(print (rontolisp:list-functions :cl-user))
				""";
		assertThat(compileAndRun(code)).isEqualTo("(add2 fib)");
	}

	@Test
	void compileAndRunListFunctionsForClUserWithoutDefunsIsNil() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-functions :cl-user))")).isEqualTo("nil");
	}

	@Test
	void compileAndRunListFunctionsForRontolisp() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-functions :rontolisp))"))
			.isEqualTo("(list-functions list-macros list-special-forms version)");
		assertThat(compileAndRun("(print (rontolisp:list-macros :rontolisp))")).isEqualTo("nil");
	}

	@Test
	void compileListFunctionsUnknownPackageThrows() {
		assertThatThrownBy(() -> compileAndRun("(print (rontolisp:list-functions :foo))"))
			.isInstanceOf(am.ik.rontolisp.LispPackageException.class)
			.hasMessageContaining("No such package: foo");
	}

	@Test
	void compileAndRunFirstRestNthAsFunctionValues() throws Exception {
		assertThat(compileAndRun("(print (funcall #'first '(1 2 3)))")).isEqualTo("1");
		assertThat(compileAndRun("(print (mapcar #'rest '((1 2) (3 4))))")).isEqualTo("((2) (4))");
		assertThat(compileAndRun("(print (funcall #'nth 1 '(1 2 3)))")).isEqualTo("2");
		assertThat(compileAndRun("(print (mapcar #'second '((1 2) (3 4))))")).isEqualTo("(2 4)");
	}

}
