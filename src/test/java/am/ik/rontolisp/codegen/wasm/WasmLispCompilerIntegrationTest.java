package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests that compile Lisp to WASM and run it with wasmtime inside a
 * container.
 */
@Testcontainers(disabledWithoutDocker = true)
class WasmLispCompilerIntegrationTest {

	// The install script is downloaded to a file first (a failed `curl | bash` exits 0
	// because bash just sees EOF) and the installed binary is verified, so a transient
	// download failure fails the image build instead of producing an image without
	// wasmtime (every test then fails with exit code 127).
	@Container
	static GenericContainer<?> wasmtime = new GenericContainer<>(
			new ImageFromDockerfile().withDockerfileFromBuilder(builder -> builder.from("debian:bookworm-slim")
				.run("apt-get update && apt-get install -y --no-install-recommends curl ca-certificates xz-utils"
						+ " && curl https://wasmtime.dev/install.sh -sSf -o /tmp/install-wasmtime.sh"
						+ " && bash /tmp/install-wasmtime.sh && rm /tmp/install-wasmtime.sh"
						+ " && ln -s /root/.wasmtime/bin/wasmtime /usr/local/bin/wasmtime" + " && wasmtime --version"
						+ " && apt-get remove -y curl && apt-get autoremove -y" + " && rm -rf /var/lib/apt/lists/*")
				.build()))
		.withCommand("sleep", "infinity");

	private static String compileAndRun(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "/tmp/test.wasm");
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	@Test
	void addition() throws Exception {
		assertThat(compileAndRun("(print (+ 1 2))")).isEqualTo("3");
	}

	@Test
	void subtraction() throws Exception {
		assertThat(compileAndRun("(print (- 10 3))")).isEqualTo("7");
	}

	@Test
	void piConstant() throws Exception {
		// WASM float printing is limited to 6 fractional digits.
		assertThat(compileAndRun("(print pi)")).isEqualTo("3.141592");
	}

	@Test
	void multiplication() throws Exception {
		assertThat(compileAndRun("(print (* 3 4))")).isEqualTo("12");
	}

	@Test
	void division() throws Exception {
		assertThat(compileAndRun("(print (/ 10 3))")).isEqualTo("10/3");
		assertThat(compileAndRun("(print (/ 10 2))")).isEqualTo("5");
	}

	@Test
	void modTakesSignOfDivisor() throws Exception {
		assertThat(compileAndRun("(print (mod 13 4))")).isEqualTo("1");
		assertThat(compileAndRun("(print (mod -13 4))")).isEqualTo("3");
		assertThat(compileAndRun("(print (mod 13 -4))")).isEqualTo("-3");
		assertThat(compileAndRun("(print (mod -13 -4))")).isEqualTo("-1");
	}

	@Test
	void remTakesSignOfDividend() throws Exception {
		assertThat(compileAndRun("(print (rem 13 4))")).isEqualTo("1");
		assertThat(compileAndRun("(print (rem -13 4))")).isEqualTo("-1");
		assertThat(compileAndRun("(print (rem 13 -4))")).isEqualTo("1");
		assertThat(compileAndRun("(print (rem -13 -4))")).isEqualTo("-1");
	}

	@Test
	void variadicComparison() throws Exception {
		// A true boolean is the symbol t, like the interpreter.
		assertThat(compileAndRun("(print (< 1 2 3 4))")).isEqualTo("t");
		assertThat(compileAndRun("(print (< 1 2 2 4))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (<= 1 2 2 4))")).isEqualTo("t");
		assertThat(compileAndRun("(print (= 3 3 3))")).isEqualTo("t");
		assertThat(compileAndRun("(print (> 5 4 3 2 1))")).isEqualTo("t");
		assertThat(compileAndRun("(print (< 5))")).isEqualTo("t");
	}

	@Test
	void booleanIsSymbolT() throws Exception {
		// A boolean true prints as the symbol t (not the integer 1), matching the
		// interpreter, so it is indistinguishable from t in a list.
		assertThat(compileAndRun("(print (list (= 1 1) (= 1 0)))")).isEqualTo("(t nil)");
		assertThat(compileAndRun("(print t)")).isEqualTo("t");
		assertThat(compileAndRun("(print (eq t (= 1 1)))")).isEqualTo("t");
	}

	@Test
	void variadicMinMaxGcdLcm() throws Exception {
		assertThat(compileAndRun("(print (min 5 2 8 1 9))")).isEqualTo("1");
		assertThat(compileAndRun("(print (max 5 2 8 1 9))")).isEqualTo("9");
		assertThat(compileAndRun("(print (gcd 24 36 60))")).isEqualTo("12");
		assertThat(compileAndRun("(print (lcm 2 3 4))")).isEqualTo("12");
		assertThat(compileAndRun("(print (gcd -8))")).isEqualTo("8");
	}

	@Test
	void lengthOfStringAndList() throws Exception {
		assertThat(compileAndRun("(print (length \"hello\"))")).isEqualTo("5");
		assertThat(compileAndRun("(print (length \"\"))")).isEqualTo("0");
		assertThat(compileAndRun("(print (length (list 10 20 30)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (length nil))")).isEqualTo("0");
	}

	@Test
	void ratioLiteral() throws Exception {
		assertThat(compileAndRun("(print 1/3)")).isEqualTo("1/3");
		assertThat(compileAndRun("(print -2/4)")).isEqualTo("-1/2");
		assertThat(compileAndRun("(print '4/2)")).isEqualTo("2");
	}

	@Test
	void ratioArithmetic() throws Exception {
		assertThat(compileAndRun("(print (+ 1/2 1/3))")).isEqualTo("5/6");
		assertThat(compileAndRun("(print (+ 1/2 1/2))")).isEqualTo("1");
		assertThat(compileAndRun("(print (- 1/2 1/3))")).isEqualTo("1/6");
		assertThat(compileAndRun("(print (* 2/3 3))")).isEqualTo("2");
		assertThat(compileAndRun("(print (/ 1/2 1/3))")).isEqualTo("3/2");
		assertThat(compileAndRun("(print (- 1/2))")).isEqualTo("-1/2");
		assertThat(compileAndRun("(print (+ 1 1/2))")).isEqualTo("3/2");
		assertThat(compileAndRun("(print (1+ 1/2))")).isEqualTo("3/2");
	}

	@Test
	void ratioFloatContagion() throws Exception {
		assertThat(compileAndRun("(print (/ 1 2.0))")).isEqualTo("0.5");
		assertThat(compileAndRun("(print (float 1/2))")).isEqualTo("0.5");
		assertThat(compileAndRun("(print (+ 1/2 0.5))")).isEqualTo("1.0");
	}

	@Test
	void ratioComparison() throws Exception {
		assertThat(compileAndRun("(print (if (< 1/3 1/2) 1 0))")).isEqualTo("1");
		assertThat(compileAndRun("(print (if (= 2/4 1/2) 1 0))")).isEqualTo("1");
		assertThat(compileAndRun("(print (if (= 1/2 0.5) 1 0))")).isEqualTo("1");
		assertThat(compileAndRun("(print (eq 1/2 1/2))")).isEqualTo("t");
		assertThat(compileAndRun("(print (max 1/2 1/3))")).isEqualTo("1/2");
		assertThat(compileAndRun("(print (min 1/2 1/3))")).isEqualTo("1/3");
		assertThat(compileAndRun("(print (abs -1/2))")).isEqualTo("1/2");
	}

	@Test
	void ratioConversions() throws Exception {
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
	void ratioPredicatesAndAccessors() throws Exception {
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
		assertThat(compileAndRun("(print (zerop 1/2))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (plusp 1/2))")).isEqualTo("t");
		assertThat(compileAndRun("(print (minusp -1/2))")).isEqualTo("t");
		assertThat(compileAndRun("(print (signum -1/2))")).isEqualTo("-1");
	}

	@Test
	void ratioExpt() throws Exception {
		assertThat(compileAndRun("(print (expt 1/2 2))")).isEqualTo("1/4");
		assertThat(compileAndRun("(print (expt 1/2 -2))")).isEqualTo("4");
		assertThat(compileAndRun("(print (expt 2 -1))")).isEqualTo("1/2");
	}

	@Test
	void ratioInList() throws Exception {
		assertThat(compileAndRun("(print (list 1/2 2/3))")).isEqualTo("(1/2 2/3)");
		assertThat(compileAndRun("(print (cons 1 1/2))")).isEqualTo("(1 . 1/2)");
	}

	@Test
	void nestedArithmetic() throws Exception {
		assertThat(compileAndRun("(print (+ (* 2 3) (- 10 4)))")).isEqualTo("12");
	}

	@Test
	void negativeResult() throws Exception {
		assertThat(compileAndRun("(print (- 3 10))")).isEqualTo("-7");
	}

	@Test
	void whileLoop() throws Exception {
		assertThat(compileAndRun("(print (let ((n 0) (s 0)) (while (< n 5) (setq s (+ s n)) (setq n (+ n 1))) s))"))
			.isEqualTo("10");
		assertThat(compileAndRun("(print (let ((n 0)) (while nil (setq n 99)) n))")).isEqualTo("0");
	}

	@Test
	void dotimes() throws Exception {
		assertThat(compileAndRun("(print (let ((s 0)) (dotimes (i 5) (setq s (+ s i))) s))")).isEqualTo("10");
		assertThat(compileAndRun("(print (dotimes (i 3)))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (let ((acc 1)) (dotimes (i 4 acc) (setq acc (* acc 2)))))")).isEqualTo("16");
		assertThat(compileAndRun("(print (let ((s 7)) (dotimes (i 0) (setq s 0)) s))")).isEqualTo("7");
		assertThat(compileAndRun("(print (let ((s 0)) (dotimes (i 3) (dotimes (j 2) (setq s (+ s 1)))) s))"))
			.isEqualTo("6");
	}

	@Test
	void ifTrue() throws Exception {
		assertThat(compileAndRun("(print (if t 1 2))")).isEqualTo("1");
	}

	@Test
	void ifFalse() throws Exception {
		assertThat(compileAndRun("(print (if nil 1 2))")).isEqualTo("2");
	}

	@Test
	void letBinding() throws Exception {
		assertThat(compileAndRun("(print (let ((x 10) (y 20)) (+ x y)))")).isEqualTo("30");
	}

	@Test
	void multipleExpressions() throws Exception {
		assertThat(compileAndRun("(print 1) (print 2) (print 3)")).isEqualTo("1\n2\n3");
	}

	@Test
	void comparison() throws Exception {
		assertThat(compileAndRun("(print (if (= 1 1) 42 99))")).isEqualTo("42");
	}

	@Test
	void defunSquare() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(print (square 5))
				""")).isEqualTo("25");
	}

	@Test
	void defunFactorial() throws Exception {
		assertThat(compileAndRun("""
				(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
				(print (fact 5))
				""")).isEqualTo("120");
	}

	@Test
	void defunFibonacci() throws Exception {
		assertThat(compileAndRun("""
				(defun fib (n) (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))
				(print (fib 10))
				""")).isEqualTo("55");
	}

	@Test
	void multipleDefuns() throws Exception {
		assertThat(compileAndRun("""
				(defun double (x) (* x 2))
				(defun add1 (x) (+ x 1))
				(print (add1 (double 5)))
				""")).isEqualTo("11");
	}

	@Test
	void defunNoParams() throws Exception {
		assertThat(compileAndRun("""
				(defun answer () 42)
				(print (answer))
				""")).isEqualTo("42");
	}

	@Test
	void setqBasic() throws Exception {
		assertThat(compileAndRun("(print (progn (setq x 10) x))")).isEqualTo("10");
	}

	@Test
	void setqReassign() throws Exception {
		assertThat(compileAndRun("(print (progn (setq x 10) (setq x 20) x))")).isEqualTo("20");
	}

	@Test
	void setqMutateLet() throws Exception {
		assertThat(compileAndRun("(print (let ((x 1)) (setq x 2) x))")).isEqualTo("2");
	}

	@Test
	void setqInExpression() throws Exception {
		assertThat(compileAndRun("(print (+ (setq x 5) 3))")).isEqualTo("8");
	}

	@Test
	void setqLambdaSquare() throws Exception {
		assertThat(compileAndRun("""
				(setq square (lambda (x) (* x x)))
				(print (funcall square 5))
				""")).isEqualTo("25");
	}

	@Test
	void setqLambdaFactorial() throws Exception {
		// Lisp-2: a recursive function must be defined with defun (a lambda bound by
		// setq cannot refer to itself through the variable namespace in compiled code).
		assertThat(compileAndRun("""
				(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
				(setq fact5 #'fact)
				(print (funcall fact5 5))
				""")).isEqualTo("120");
	}

	@Test
	void setqLambdaNoParams() throws Exception {
		assertThat(compileAndRun("""
				(setq answer (lambda () 42))
				(print (funcall answer))
				""")).isEqualTo("42");
	}

	@Test
	void setqLambdaMultipleFunctions() throws Exception {
		assertThat(compileAndRun("""
				(setq double (lambda (x) (* x 2)))
				(setq add1 (lambda (x) (+ x 1)))
				(print (funcall add1 (funcall double 5)))
				""")).isEqualTo("11");
	}

	@Test
	void mixedDefunAndSetqLambda() throws Exception {
		assertThat(compileAndRun("""
				(defun double (x) (* x 2))
				(setq add1 (lambda (x) (+ x 1)))
				(print (funcall add1 (double 5)))
				""")).isEqualTo("11");
	}

	@Test
	void lambdaImmediateCall() throws Exception {
		assertThat(compileAndRun("(print ((lambda (x) (* x x)) 5))")).isEqualTo("25");
	}

	@Test
	void printString() throws Exception {
		assertThat(compileAndRun("(print \"hello\")")).isEqualTo("\"hello\"");
	}

	@Test
	void prin1() throws Exception {
		assertThat(compileAndRun("(prin1 42) (terpri) (prin1 \"hello\")")).isEqualTo("42\n\"hello\"");
	}

	@Test
	void princ() throws Exception {
		assertThat(compileAndRun("(princ 42) (terpri) (princ \"hello\")")).isEqualTo("42\nhello");
	}

	@Test
	void princList() throws Exception {
		assertThat(compileAndRun("(princ '(1 \"hello\" 3))")).isEqualTo("(1 hello 3)");
	}

	@Test
	void terpri() throws Exception {
		assertThat(compileAndRun("(prin1 1) (princ 2) (terpri)")).isEqualTo("12");
	}

	@Test
	void format() throws Exception {
		assertThat(compileAndRun("(format t \"Hello ~a, you are ~d! ~s~%\" 'world 42 \"str\")"))
			.isEqualTo("Hello world, you are 42! \"str\"");
	}

	@Test
	void formatList() throws Exception {
		assertThat(compileAndRun("(format t \"list=~a tilde=~~\" (list 1 2 3))")).isEqualTo("list=(1 2 3) tilde=~");
	}

	@Test
	void formatInsideDefun() throws Exception {
		assertThat(compileAndRun("(defun greet (name) (format t \"Hi, ~a!~%\" name)) (greet 'alice) (greet \"bob\")"))
			.isEqualTo("Hi, alice!\nHi, bob!");
	}

	@Test
	void formatNil() throws Exception {
		assertThat(compileAndRun("(princ (format nil \"Hello ~a, ~d! ~s~%\" 'world 42 \"str\"))"))
			.isEqualTo("Hello world, 42! \"str\"");
	}

	@Test
	void formatNilList() throws Exception {
		assertThat(compileAndRun("(princ (format nil \"list=~a tilde=~~\" (list 1 2 3)))"))
			.isEqualTo("list=(1 2 3) tilde=~");
	}

	@Test
	void formatNilIsString() throws Exception {
		assertThat(compileAndRun("(print (stringp (format nil \"~a\" 1))) (print (length (format nil \"~a\" 12345)))"))
			.isEqualTo("t\n5");
	}

	@Test
	void princToString() throws Exception {
		assertThat(compileAndRun("(print (princ-to-string 42)) (princ (princ-to-string 'sym))"))
			.isEqualTo("\"42\"\nsym");
	}

	@Test
	void prin1ToString() throws Exception {
		assertThat(compileAndRun("(princ (prin1-to-string \"abc\"))")).isEqualTo("\"abc\"");
	}

	@Test
	void concatenate() throws Exception {
		assertThat(compileAndRun("(princ (concatenate 'string \"foo\" \"bar\" \"baz\"))")).isEqualTo("foobarbaz");
	}

	@Test
	void princToStringAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'princ-to-string (list 1 2)))")).isEqualTo("(\"1\" \"2\")");
	}

	@Test
	void stringUpcaseDowncase() throws Exception {
		assertThat(compileAndRun("(princ (string-upcase \"Hello, World\"))")).isEqualTo("HELLO, WORLD");
		assertThat(compileAndRun("(princ (string-downcase \"Hello, World\"))")).isEqualTo("hello, world");
	}

	@Test
	void stringCapitalize() throws Exception {
		assertThat(compileAndRun("(princ (string-capitalize \"hello world  foo\"))")).isEqualTo("Hello World  Foo");
	}

	@Test
	void subseq() throws Exception {
		assertThat(compileAndRun("(princ (subseq \"hello world\" 6))")).isEqualTo("world");
		assertThat(compileAndRun("(princ (subseq \"hello world\" 0 5))")).isEqualTo("hello");
	}

	@Test
	void subseqList() throws Exception {
		assertThat(compileAndRun("(print (subseq '(1 2 3 4 5) 1 3))")).isEqualTo("(2 3)");
		assertThat(compileAndRun("(print (subseq '(1 2 3 4 5) 2))")).isEqualTo("(3 4 5)");
		assertThat(compileAndRun("(print (subseq '(a b c) 0))")).isEqualTo("(a b c)");
		assertThat(compileAndRun("(print (subseq '(1 2 3) 3))")).isEqualTo("nil");
	}

	@Test
	void stringEquality() throws Exception {
		assertThat(compileAndRun("(print (string= \"abc\" \"abc\"))")).isEqualTo("t");
		assertThat(compileAndRun("(print (string= \"abc\" \"abd\"))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (string-equal \"ABC\" \"abc\"))")).isEqualTo("t");
	}

	@Test
	void stringTrim() throws Exception {
		assertThat(compileAndRun("(princ (string-trim \" xy\" \"xyhelloyx \"))")).isEqualTo("hello");
		assertThat(compileAndRun("(princ (string-left-trim \"x\" \"xxhello\"))")).isEqualTo("hello");
		assertThat(compileAndRun("(princ (string-right-trim \"x\" \"helloxx\"))")).isEqualTo("hello");
	}

	@Test
	void stringFunctionsAsValues() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'string-upcase (list \"ab\" \"cd\")))")).isEqualTo("(\"AB\" \"CD\")");
		assertThat(compileAndRun("(print (funcall #'subseq \"hello\" 2))")).isEqualTo("\"llo\"");
	}

	@Test
	void princToStringFloat() throws Exception {
		assertThat(compileAndRun("(princ (princ-to-string 3.14))")).isEqualTo("3.14");
	}

	@Test
	void quoteInteger() throws Exception {
		assertThat(compileAndRun("(print '42)")).isEqualTo("42");
	}

	@Test
	void quoteList() throws Exception {
		assertThat(compileAndRun("(print '(1 2 3))")).isEqualTo("(1 2 3)");
	}

	@Test
	void quoteNestedList() throws Exception {
		assertThat(compileAndRun("(print '(1 (2 3) 4))")).isEqualTo("(1 (2 3) 4)");
	}

	@Test
	void quoteNil() throws Exception {
		assertThat(compileAndRun("(print (quote nil))")).isEqualTo("nil");
	}

	@Test
	void stringInLet() throws Exception {
		assertThat(compileAndRun("(let ((x \"world\")) (print x))")).isEqualTo("\"world\"");
	}

	@Test
	void quoteWithSymbol() throws Exception {
		assertThat(compileAndRun("(print '(+ 1 2))")).isEqualTo("(+ 1 2)");
	}

	@Test
	void listCarCdr() throws Exception {
		assertThat(compileAndRun("(print (car (list 1 2 3)))")).isEqualTo("1");
	}

	@Test
	void listCarCdr2() throws Exception {
		assertThat(compileAndRun("(print (car (cdr (list 1 2 3))))")).isEqualTo("2");
	}

	@Test
	void cons() throws Exception {
		assertThat(compileAndRun("(print (car (cons 1 2)))")).isEqualTo("1");
		assertThat(compileAndRun("(print (cdr (cons 1 2)))")).isEqualTo("2");
	}

	@Test
	void higherOrderFunction() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(defun apply-twice (f x) (funcall f (funcall f x)))
				(print (apply-twice #'square 3))
				""")).isEqualTo("81");
	}

	@Test
	void lambdaAsArgument() throws Exception {
		assertThat(compileAndRun("""
				(defun apply-twice (f x) (funcall f (funcall f x)))
				(print (apply-twice (lambda (x) (+ x 10)) 5))
				""")).isEqualTo("25");
	}

	@Test
	void closure() throws Exception {
		assertThat(compileAndRun("""
				(defun make-adder (n) (lambda (x) (+ x n)))
				(setq add5 (make-adder 5))
				(print (funcall add5 10))
				""")).isEqualTo("15");
	}

	@Test
	void closureMutation() throws Exception {
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
	void dynamicFunctionSelection() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(defun forty-two (x) 42)
				(setq f (if t #'square #'forty-two))
				(print (funcall f 6))
				""")).isEqualTo("36");
	}

	@Test
	void funcall() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(print (funcall #'square 7))
				""")).isEqualTo("49");
	}

	@Test
	void funcallLambda() throws Exception {
		assertThat(compileAndRun("""
				(print (funcall (lambda (x) (* x x)) 5))
				""")).isEqualTo("25");
	}

	@Test
	void functionInList() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(print (funcall (car (list #'square)) 5))
				""")).isEqualTo("25");
	}

	@Test
	void nullPredicate() throws Exception {
		assertThat(compileAndRun("(print (if (null nil) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (null 1) 42 99))")).isEqualTo("99");
	}

	@Test
	void atom() throws Exception {
		assertThat(compileAndRun("(print (if (atom 1) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (atom '(1 2)) 42 99))")).isEqualTo("99");
		assertThat(compileAndRun("(print (if (atom nil) 42 99))")).isEqualTo("42");
	}

	@Test
	void numberp() throws Exception {
		assertThat(compileAndRun("(print (if (numberp 42) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (numberp 3.14) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (numberp \"hello\") 42 99))")).isEqualTo("99");
	}

	@Test
	void integerp() throws Exception {
		assertThat(compileAndRun("(print (if (integerp 42) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (integerp 3.14) 42 99))")).isEqualTo("99");
	}

	@Test
	void floatp() throws Exception {
		assertThat(compileAndRun("(print (if (floatp 3.14) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (floatp 42) 42 99))")).isEqualTo("99");
	}

	@Test
	void symbolp() throws Exception {
		assertThat(compileAndRun("(print (if (symbolp 'foo) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (symbolp 42) 42 99))")).isEqualTo("99");
	}

	@Test
	void stringp() throws Exception {
		assertThat(compileAndRun("(print (if (stringp \"hello\") 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (stringp 42) 42 99))")).isEqualTo("99");
	}

	@Test
	void listp() throws Exception {
		assertThat(compileAndRun("(print (if (listp '(1 2)) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (listp nil) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (listp 42) 42 99))")).isEqualTo("99");
	}

	@Test
	void consp() throws Exception {
		assertThat(compileAndRun("(print (if (consp '(1 2)) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (consp nil) 42 99))")).isEqualTo("99");
	}

	@Test
	void doubleLiteral() throws Exception {
		assertThat(compileAndRun("(print 3.14)")).isEqualTo("3.14");
	}

	@Test
	void doubleAddition() throws Exception {
		assertThat(compileAndRun("(print (+ 1.5 2.5))")).isEqualTo("4.0");
	}

	@Test
	void doubleMixedAddition() throws Exception {
		assertThat(compileAndRun("(print (+ 1 1.5))")).isEqualTo("2.5");
	}

	@Test
	void doubleSubtraction() throws Exception {
		assertThat(compileAndRun("(print (- 3.5 1.5))")).isEqualTo("2.0");
	}

	@Test
	void doubleMultiplication() throws Exception {
		assertThat(compileAndRun("(print (* 2.0 3.0))")).isEqualTo("6.0");
	}

	@Test
	void doubleDivision() throws Exception {
		assertThat(compileAndRun("(print (/ 7.0 2.0))")).isEqualTo("3.5");
	}

	@Test
	void doubleComparison() throws Exception {
		assertThat(compileAndRun("(print (if (= 1.0 1.0) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (< 1.0 2.0) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (> 2.0 1.0) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (<= 1.5 1.5) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (>= 2.0 3.0) 42 99))")).isEqualTo("99");
	}

	@Test
	void doubleNestedArithmetic() throws Exception {
		assertThat(compileAndRun("(print (+ (* 2.0 3.0) (- 10.0 4.0)))")).isEqualTo("12.0");
	}

	@Test
	void onePlus() throws Exception {
		assertThat(compileAndRun("(print (1+ 5))")).isEqualTo("6");
	}

	@Test
	void oneMinus() throws Exception {
		assertThat(compileAndRun("(print (1- 5))")).isEqualTo("4");
	}

	@Test
	void zerop() throws Exception {
		assertThat(compileAndRun("(print (if (zerop 0) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (zerop 1) 42 99))")).isEqualTo("99");
	}

	@Test
	void plusp() throws Exception {
		assertThat(compileAndRun("(print (if (plusp 1) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (plusp 0) 42 99))")).isEqualTo("99");
	}

	@Test
	void minusp() throws Exception {
		assertThat(compileAndRun("(print (if (minusp -1) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (minusp 0) 42 99))")).isEqualTo("99");
	}

	@Test
	void evenp() throws Exception {
		assertThat(compileAndRun("(print (if (evenp 4) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (evenp 3) 42 99))")).isEqualTo("99");
	}

	@Test
	void oddp() throws Exception {
		assertThat(compileAndRun("(print (if (oddp 3) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (oddp 4) 42 99))")).isEqualTo("99");
	}

	@Test
	void abs() throws Exception {
		assertThat(compileAndRun("(print (abs 5))")).isEqualTo("5");
		assertThat(compileAndRun("(print (abs -5))")).isEqualTo("5");
	}

	@Test
	void min() throws Exception {
		assertThat(compileAndRun("(print (min 3 5))")).isEqualTo("3");
		assertThat(compileAndRun("(print (min 5 3))")).isEqualTo("3");
	}

	@Test
	void max() throws Exception {
		assertThat(compileAndRun("(print (max 3 5))")).isEqualTo("5");
		assertThat(compileAndRun("(print (max 5 3))")).isEqualTo("5");
	}

	@Test
	void unless() throws Exception {
		assertThat(compileAndRun("(print (unless nil 42))")).isEqualTo("42");
		assertThat(compileAndRun("(print (unless t 42))")).isEqualTo("nil");
	}

	@Test
	void first() throws Exception {
		assertThat(compileAndRun("(print (first '(1 2 3)))")).isEqualTo("1");
	}

	@Test
	void nth() throws Exception {
		assertThat(compileAndRun("(print (nth 0 '(1 2 3)))")).isEqualTo("1");
		assertThat(compileAndRun("(print (nth 2 '(1 2 3)))")).isEqualTo("3");
	}

	@Test
	void nthcdr() throws Exception {
		assertThat(compileAndRun("(print (nthcdr 0 '(1 2 3)))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (nthcdr 2 '(1 2 3)))")).isEqualTo("(3)");
	}

	@Test
	void second() throws Exception {
		assertThat(compileAndRun("(print (second '(1 2 3)))")).isEqualTo("2");
	}

	@Test
	void third() throws Exception {
		assertThat(compileAndRun("(print (third '(1 2 3)))")).isEqualTo("3");
	}

	@Test
	void fourth() throws Exception {
		assertThat(compileAndRun("(print (fourth '(1 2 3 4)))")).isEqualTo("4");
	}

	@Test
	void carCdrComposition() throws Exception {
		assertThat(compileAndRun("(print (cadr '(1 2 3)))")).isEqualTo("2");
		assertThat(compileAndRun("(print (caddr '(1 2 3)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (caar '((1 2) 3)))")).isEqualTo("1");
		assertThat(compileAndRun("(print (cadddr '(1 2 3 4)))")).isEqualTo("4");
	}

	@Test
	void rplaca() throws Exception {
		assertThat(compileAndRun("""
				(setq x (cons 1 2))
				(rplaca x 10)
				(print (car x))
				""")).isEqualTo("10");
	}

	@Test
	void rplacd() throws Exception {
		assertThat(compileAndRun("""
				(setq x (cons 1 2))
				(rplacd x 20)
				(print (cdr x))
				""")).isEqualTo("20");
	}

	@Test
	void setfCar() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 1 2 3))
				(setf (car x) 10)
				(print (car x))
				""")).isEqualTo("10");
	}

	@Test
	void setfCdr() throws Exception {
		assertThat(compileAndRun("""
				(setq x (cons 1 2))
				(setf (cdr x) 20)
				(print (cdr x))
				""")).isEqualTo("20");
	}

	@Test
	void setfNth() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 1 2 3))
				(setf (nth 1 x) 20)
				(print (nth 1 x))
				""")).isEqualTo("20");
	}

	@Test
	void setfSecond() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 1 2 3))
				(setf (second x) 20)
				(print (second x))
				""")).isEqualTo("20");
	}

	@Test
	void setfReturnsValue() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 1 2 3))
				(print (setf (car x) 42))
				""")).isEqualTo("42");
	}

	@Test
	void eqSameInteger() throws Exception {
		assertThat(compileAndRun("(print (if (eq 1 1) 42 99))")).isEqualTo("42");
	}

	@Test
	void eqDifferentInteger() throws Exception {
		assertThat(compileAndRun("(print (if (eq 1 2) 42 99))")).isEqualTo("99");
	}

	@Test
	void eqSymbols() throws Exception {
		assertThat(compileAndRun("(print (if (eq 'foo 'foo) 42 99))")).isEqualTo("42");
	}

	@Test
	void eqNilNil() throws Exception {
		assertThat(compileAndRun("(print (if (eq nil nil) 42 99))")).isEqualTo("42");
	}

	@Test
	void eqNilAndValue() throws Exception {
		assertThat(compileAndRun("(print (if (eq nil 1) 42 99))")).isEqualTo("99");
	}

	@Test
	void push() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 2 3))
				(push 1 x)
				(print x)
				""")).isEqualTo("(1 2 3)");
	}

	@Test
	void pop() throws Exception {
		assertThat(compileAndRun("""
				(setq x (list 1 2 3))
				(print (pop x))
				(print x)
				""")).isEqualTo("1\n(2 3)");
	}

	@Test
	void remfHead() throws Exception {
		assertThat(compileAndRun("""
				(setq plist (list 'a 1 'b 2 'c 3))
				(remf plist 'a)
				(print plist)
				""")).isEqualTo("(b 2 c 3)");
	}

	@Test
	void remfMiddle() throws Exception {
		assertThat(compileAndRun("""
				(setq plist (list 'a 1 'b 2 'c 3))
				(remf plist 'b)
				(print plist)
				""")).isEqualTo("(a 1 c 3)");
	}

	@Test
	void remfTail() throws Exception {
		assertThat(compileAndRun("""
				(setq plist (list 'a 1 'b 2 'c 3))
				(remf plist 'c)
				(print plist)
				""")).isEqualTo("(a 1 b 2)");
	}

	@Test
	void remfNotFound() throws Exception {
		assertThat(compileAndRun("""
				(setq plist (list 'a 1 'b 2))
				(print (if (remf plist 'z) 42 99))
				""")).isEqualTo("99");
	}

	@Test
	void remfEmpty() throws Exception {
		assertThat(compileAndRun("""
				(setq plist nil)
				(print (if (remf plist 'a) 42 99))
				""")).isEqualTo("99");
	}

	@Test
	void keywordPrint() throws Exception {
		assertThat(compileAndRun("(print :foo)")).isEqualTo(":foo");
	}

	@Test
	void keywordEq() throws Exception {
		assertThat(compileAndRun("(print (if (eq :foo :foo) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (eq :foo :bar) 42 99))")).isEqualTo("99");
	}

	@Test
	void keywordp() throws Exception {
		assertThat(compileAndRun("(print (if (keywordp :foo) 42 99))")).isEqualTo("42");
		assertThat(compileAndRun("(print (if (keywordp 'foo) 42 99))")).isEqualTo("99");
		assertThat(compileAndRun("(print (if (keywordp 42) 42 99))")).isEqualTo("99");
	}

	@Test
	void keywordSymbolp() throws Exception {
		assertThat(compileAndRun("(print (if (symbolp :foo) 42 99))")).isEqualTo("42");
	}

	@Test
	void reduceWithBuiltinPlus() throws Exception {
		assertThat(compileAndRun("(print (reduce #'+ 0 '(1 2 3 4 5)))")).isEqualTo("15");
	}

	@Test
	void reduceWithBuiltinMul() throws Exception {
		assertThat(compileAndRun("(print (reduce #'* 1 '(1 2 3 4 5)))")).isEqualTo("120");
	}

	@Test
	void restAccessor() throws Exception {
		assertThat(compileAndRun("(print (rest '(1 2 3))) (print (rest '(1)))")).isEqualTo("(2 3)\nnil");
	}

	@Test
	void setfRestPlace() throws Exception {
		assertThat(compileAndRun("(setq l (list 1 2 3)) (setf (rest l) '(9)) (print l)")).isEqualTo("(1 9)");
	}

	@Test
	void restInsideEval() throws Exception {
		assertThat(compileAndRun("(print (eval '(rest '(1 2 3))))")).isEqualTo("(2 3)");
	}

	@Test
	void letStar() throws Exception {
		assertThat(compileAndRun("(print (let* ((x 2) (y (* x 3))) (+ x y)))")).isEqualTo("8");
	}

	@Test
	void dolist() throws Exception {
		assertThat(compileAndRun("(setq s 0) (dolist (e '(1 2 3 4)) (setq s (+ s e))) (print s)")).isEqualTo("10");
	}

	@Test
	void dolistResultForm() throws Exception {
		assertThat(compileAndRun("(print (dolist (e '(1 2) 99)))")).isEqualTo("99");
	}

	@Test
	void incfDecf() throws Exception {
		assertThat(compileAndRun("(setq n 10) (incf n) (incf n 5) (decf n 6) (print n)")).isEqualTo("10");
	}

	@Test
	void incfPlace() throws Exception {
		assertThat(compileAndRun("(setq l (list 1 2 3)) (incf (cadr l)) (print l)")).isEqualTo("(1 3 3)");
	}

	@Test
	void lengthFunction() throws Exception {
		assertThat(compileAndRun("(print (length '(1 2 3 4 5))) (print (length nil))")).isEqualTo("5\n0");
	}

	@Test
	void reverseFunction() throws Exception {
		assertThat(compileAndRun("(print (reverse '(1 2 3))) (print (reverse nil))")).isEqualTo("(3 2 1)\nnil");
	}

	@Test
	void memberFunction() throws Exception {
		assertThat(compileAndRun("(print (member 3 '(1 2 3 4))) (print (member 9 '(1 2 3)))")).isEqualTo("(3 4)\nnil");
	}

	@Test
	void assocFunction() throws Exception {
		assertThat(compileAndRun("(print (assoc 'b '((a 1) (b 2) (c 3)))) (print (assoc 'z '((a 1))))"))
			.isEqualTo("(b 2)\nnil");
	}

	@Test
	void lastFunction() throws Exception {
		assertThat(compileAndRun("(print (last '(1 2 3))) (print (last nil))")).isEqualTo("(3)\nnil");
	}

	@Test
	void sequenceFunctionsAsFirstClass() throws Exception {
		assertThat(compileAndRun("(print (funcall #'length '(7 8 9))) (print (mapcar #'reverse '((1 2) (3 4))))"))
			.isEqualTo("3\n((2 1) (4 3))");
	}

	@Test
	void sequenceFunctionInsideEval() throws Exception {
		assertThat(compileAndRun("(print (eval '(reverse '(1 2 3))))")).isEqualTo("(3 2 1)");
	}

	@Test
	void mapWithBuiltinCar() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'car '((1 2) (3 4) (5 6))))")).isEqualTo("(1 3 5)");
	}

	@Test
	void mapWithBuiltinCdr() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'cdr '((1 2) (3 4) (5 6))))")).isEqualTo("((2) (4) (6))");
	}

	@Test
	void mapWithBuiltin1Plus() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'1+ '(1 2 3)))")).isEqualTo("(2 3 4)");
	}

	@Test
	void funcallWithBuiltinPlus() throws Exception {
		assertThat(compileAndRun("(print (funcall #'+ 3 4))")).isEqualTo("7");
	}

	@Test
	void builtinAsVariable() throws Exception {
		assertThat(compileAndRun("""
				(setq my-op #'+)
				(print (funcall my-op 10 20))
				""")).isEqualTo("30");
	}

	// Lisp-2 (separate function/variable namespaces) tests

	@Test
	void funcallSharpQuotedPlus() throws Exception {
		assertThat(compileAndRun("(print (funcall #'+ 1 2))")).isEqualTo("3");
	}

	@Test
	void mapSharpQuotedCar() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'car '((1 2) (3 4))))")).isEqualTo("(1 3)");
	}

	@Test
	void funcallQuotedSymbolDesignator() throws Exception {
		assertThat(compileAndRun("(print (funcall 'car '(9 8)))")).isEqualTo("9");
	}

	@Test
	void mapSharpQuotedCadr() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'cadr '((1 2) (3 4))))")).isEqualTo("(2 4)");
	}

	@Test
	void setqSharpQuotedBuiltinThenFuncall() throws Exception {
		assertThat(compileAndRun("""
				(setq f #'+)
				(print (funcall f 1 2))
				""")).isEqualTo("3");
	}

	@Test
	void symbolFunction() throws Exception {
		assertThat(compileAndRun("(print (funcall (symbol-function 'car) '(5 6)))")).isEqualTo("5");
	}

	@Test
	void bareFunctionNameInValuePositionIsRejected() {
		// Compile-time only: no container run is needed to assert the rejection.
		assertThatThrownBy(() -> new WasmLispCompiler().compile(LispReader.readAllFromString("(print car)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("Cannot compile symbol: car");
	}

	// read-line tests

	private static String compileAndRunWithStdin(String lispCode, String stdin) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"echo '" + stdin + "' | wasmtime --wasm gc /tmp/test.wasm");
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	@Test
	void readLine() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read-line))", "hello")).isEqualTo("\"hello\"");
	}

	@Test
	void readLineEof() throws Exception {
		List<LispVal> program = LispReader.readAllFromString("(print (null (read-line)))");
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c", "echo -n '' | wasmtime --wasm gc /tmp/test.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("t");
	}

	@Test
	void readLineStringp() throws Exception {
		assertThat(compileAndRunWithStdin("(print (stringp (read-line)))", "hello")).isEqualTo("t");
	}

	// read tests

	@Test
	void readInteger() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "42")).isEqualTo("42");
	}

	@Test
	void readNegativeInteger() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "-7")).isEqualTo("-7");
	}

	@Test
	void readSymbol() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "foo")).isEqualTo("foo");
	}

	@Test
	void readString() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "\"hello\"")).isEqualTo("\"hello\"");
	}

	@Test
	void readList() throws Exception {
		assertThat(compileAndRunWithStdin("(print (read))", "(+ 1 2)")).isEqualTo("(+ 1 2)");
	}

	@Test
	void readCarOfList() throws Exception {
		assertThat(compileAndRunWithStdin("(print (car (read)))", "(a b c)")).isEqualTo("a");
	}

	@Test
	void readNil() throws Exception {
		assertThat(compileAndRunWithStdin("(print (null (read)))", "nil")).isEqualTo("t");
	}

	@Test
	void readThenEval() throws Exception {
		assertThat(compileAndRunWithStdin("(print (eval (read)))", "(+ 1 2 3)")).isEqualTo("6");
	}

	// Pipes stdin through a file in the container so the input may contain single
	// quotes (e.g. #'car), which would break the echo '...' form above.
	private static String compileAndRunWithStdinFile(String lispCode, String stdin) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		wasmtime.copyFileToContainer(Transferable.of(stdin.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				"/tmp/stdin.txt");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime --wasm gc /tmp/test.wasm < /tmp/stdin.txt");
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	@Test
	void readSharpQuote() throws Exception {
		assertThat(compileAndRunWithStdinFile("(print (read))", "#'car\n")).isEqualTo("(function car)");
	}

	@Test
	void readSharpQuoteThenEvalFuncall() throws Exception {
		assertThat(compileAndRunWithStdinFile("(print (eval (read)))", "(funcall #'+ 1 2)\n")).isEqualTo("3");
	}

	@Test
	void readSharpQuoteLambdaThenEval() throws Exception {
		assertThat(compileAndRunWithStdinFile("(print (eval (read)))", "(mapcar #'(lambda (x) (* x x)) '(1 2 3))\n"))
			.isEqualTo("(1 4 9)");
	}

	@Test
	void readSkipsBlankAndCommentLines() throws Exception {
		assertThat(compileAndRunWithStdinFile("(print (read))", "\n   \n; comment only\n42\n")).isEqualTo("42");
	}

	@Test
	void readEvalPrintLoop() throws Exception {
		String repl = "(setq form (read)) (while form (print (eval form)) (setq form (read)))";
		assertThat(compileAndRunWithStdinFile(repl,
				"(defun square (x) (* x x))\n(square 7)\n\n(mapcar #'square '(1 2 3))\n"))
			.isEqualTo("square\n49\n(1 4 9)");
	}

	// load tests

	private static String compileAndRunLoad(String lispCode, String libContent) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		wasmtime.copyFileToContainer(Transferable.of(libContent.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				"/tmp/lib.lisp");
		ExecResult result = wasmtime.execInContainer("bash", "-c", "cd /tmp && wasmtime --wasm gc --dir . test.wasm");
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	// file I/O tests (with-open-file/open/close/write-line/read-line)

	private static String compileAndRunWithDir(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c", "cd /tmp && wasmtime --wasm gc --dir . test.wasm");
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	@Test
	void withOpenFileWriteThenRead() throws Exception {
		String code = """
				(with-open-file (out "wof.txt" :direction :output)
				  (write-line "hello" out)
				  (write-line "world" out))
				(with-open-file (in "wof.txt")
				  (print (read-line in))
				  (print (read-line in))
				  (print (read-line in)))
				""";
		assertThat(compileAndRunWithDir(code)).isEqualTo("\"hello\"\n\"world\"\nnil");
	}

	@Test
	void withOpenFileReturnsBodyValue() throws Exception {
		String code = "(print (with-open-file (out \"wof-ret.txt\" :direction :output) (write-line \"x\" out) 42))";
		assertThat(compileAndRunWithDir(code)).isEqualTo("42");
	}

	@Test
	void openCloseExplicitStreams() throws Exception {
		String code = """
				(setq out (open "manual.txt" :output))
				(write-line "line1" out)
				(close out)
				(setq in (open "manual.txt" :input))
				(print (read-line in))
				(close in)
				""";
		assertThat(compileAndRunWithDir(code)).isEqualTo("\"line1\"");
	}

	@Test
	void writeLineWithoutStreamPrintsToStdout() throws Exception {
		assertThat(compileAndRun("(write-line \"to stdout\")")).isEqualTo("to stdout");
	}

	@Test
	void readLinesInLoop() throws Exception {
		String code = """
				(with-open-file (out "loop.txt" :direction :output)
				  (write-line "a" out)
				  (write-line "b" out)
				  (write-line "c" out))
				(with-open-file (in "loop.txt")
				  (setq line (read-line in))
				  (while line
				    (princ line)
				    (setq line (read-line in))))
				""";
		assertThat(compileAndRunWithDir(code)).isEqualTo("abc");
	}

	@Test
	void loadDefunAndUseViaEval() throws Exception {
		// Definitions from the loaded file live in the eval runtime's global env.
		String lib = "(defun square (x) (* x x))\n(setq base 5)\n";
		String code = "(load \"lib.lisp\") (print (eval '(square base)))";
		assertThat(compileAndRunLoad(code, lib)).isEqualTo("25");
	}

	@Test
	void loadMultipleForms() throws Exception {
		String lib = "(defun inc (x) (+ x 1))\n(defun dbl (x) (* x 2))\n";
		String code = "(load \"lib.lisp\") (print (eval '(dbl (inc 4))))";
		assertThat(compileAndRunLoad(code, lib)).isEqualTo("10");
	}

	// dynamic mode (late binding) tests

	private static String compileAndRunLoadDynamic(String lispCode, String libContent) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler(true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		wasmtime.copyFileToContainer(Transferable.of(libContent.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				"/tmp/lib.lisp");
		ExecResult result = wasmtime.execInContainer("bash", "-c", "cd /tmp && wasmtime --wasm gc --dir . test.wasm");
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	@Test
	void dynamicCallToLoadDefinedFunction() throws Exception {
		// (cube 3) is unknown at compile time; dynamic mode resolves it at runtime.
		String lib = "(defun cube (x) (* x x x))\n";
		String code = "(load \"lib.lisp\") (print (cube 3))";
		assertThat(compileAndRunLoadDynamic(code, lib)).isEqualTo("27");
	}

	@Test
	void dynamicCallFromCompiledFunctionSeesLocals() throws Exception {
		// caller is compiled; its local n must reach the runtime-resolved cube/square.
		String lib = "(defun cube (x) (* x x x))\n(defun square (x) (* x x))\n";
		String code = "(load \"lib.lisp\") (defun caller (n) (+ (cube n) (square n))) (print (caller 5))";
		assertThat(compileAndRunLoadDynamic(code, lib)).isEqualTo("150");
	}

	@Test
	void dynamicReferenceToLoadDefinedVariable() throws Exception {
		String lib = "(setq base 7)\n";
		String code = "(load \"lib.lisp\") (print base)";
		assertThat(compileAndRunLoadDynamic(code, lib)).isEqualTo("7");
	}

	// eval tests

	@Test
	void evalSelfEvaluating() throws Exception {
		assertThat(compileAndRun("(print (eval 42))")).isEqualTo("42");
	}

	@Test
	void evalQuotedForm() throws Exception {
		assertThat(compileAndRun("(print (eval '(+ 1 2)))")).isEqualTo("3");
	}

	@Test
	void evalListBuiltForm() throws Exception {
		assertThat(compileAndRun("(print (eval (list '+ 1 2)))")).isEqualTo("3");
	}

	@Test
	void evalFormFromVariable() throws Exception {
		assertThat(compileAndRun("(let ((x '(+ 1 2))) (print (eval x)))")).isEqualTo("3");
	}

	@Test
	void evalNestedCalls() throws Exception {
		assertThat(compileAndRun("(print (eval '(+ 1 (car (cdr (list 9 5))))))")).isEqualTo("6");
	}

	@Test
	void evalVariadicArithmetic() throws Exception {
		assertThat(compileAndRun("(print (eval '(+ 1 2 3 4 5)))")).isEqualTo("15");
		assertThat(compileAndRun("(print (eval '(- 10 3 2)))")).isEqualTo("5");
		assertThat(compileAndRun("(print (eval '(* 2 3 4)))")).isEqualTo("24");
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
	void evalVariadicList() throws Exception {
		assertThat(compileAndRun("(print (eval '(list 1 2 3)))")).isEqualTo("(1 2 3)");
	}

	@Test
	void evalIfSpecialForm() throws Exception {
		assertThat(compileAndRun("(print (eval '(if (= 1 1) 10 20)))")).isEqualTo("10");
		assertThat(compileAndRun("(print (eval '(if (= 1 2) 10 20)))")).isEqualTo("20");
	}

	@Test
	void evalPrognSpecialForm() throws Exception {
		assertThat(compileAndRun("(print (eval '(progn 1 2 (+ 5 6))))")).isEqualTo("11");
	}

	@Test
	void evalQuoteSpecialForm() throws Exception {
		assertThat(compileAndRun("(print (eval ''hello))")).isEqualTo("hello");
	}

	@Test
	void evalUserDefinedFunction() throws Exception {
		assertThat(compileAndRun("""
				(defun square (x) (* x x))
				(print (eval '(square 7)))
				""")).isEqualTo("49");
	}

	@Test
	void evalLetBindsVariable() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((x 5)) x)))")).isEqualTo("5");
	}

	@Test
	void evalLetMultipleBindings() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((x 5) (y 10)) (+ x y))))")).isEqualTo("15");
	}

	@Test
	void evalLetInitsUseOuterEnv() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((x 3)) (let ((y (+ x 1))) (+ x y)))))")).isEqualTo("7");
	}

	@Test
	void evalNestedLetShadowing() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((x 1)) (let ((x 2)) x))))")).isEqualTo("2");
	}

	@Test
	void evalInlineLambdaApplication() throws Exception {
		assertThat(compileAndRun("(print (eval '((lambda (x) (+ x 1)) 5)))")).isEqualTo("6");
	}

	@Test
	void evalLambdaCapturesLexicalEnv() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((n 10)) ((lambda (x) (+ x n)) 5))))")).isEqualTo("15");
	}

	@Test
	void evalLambdaBoundInLet() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((f (lambda (x) (* x x)))) (funcall f 6))))")).isEqualTo("36");
	}

	@Test
	void evalLambdaMultipleParams() throws Exception {
		assertThat(compileAndRun("(print (eval '((lambda (a b) (- a b)) 10 3)))")).isEqualTo("7");
	}

	@Test
	void evalUnboundSymbolSelfEvaluates() throws Exception {
		assertThat(compileAndRun("(print (eval ':foo))")).isEqualTo(":foo");
	}

	@Test
	void evalCond() throws Exception {
		assertThat(compileAndRun("(print (eval '(cond ((= 1 2) 10) ((= 1 1) 20) (t 30))))")).isEqualTo("20");
		assertThat(compileAndRun("(print (eval '(cond (nil 1))))")).isEqualTo("nil");
	}

	@Test
	void evalAnd() throws Exception {
		assertThat(compileAndRun("(print (eval '(and 1 2 3)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (eval '(and 1 nil 3)))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (eval '(and)))")).isEqualTo("t");
	}

	@Test
	void evalOr() throws Exception {
		assertThat(compileAndRun("(print (eval '(or nil nil 5)))")).isEqualTo("5");
		assertThat(compileAndRun("(print (eval '(or nil nil)))")).isEqualTo("nil");
	}

	@Test
	void evalWhenUnless() throws Exception {
		assertThat(compileAndRun("(print (eval '(when (= 1 1) 7 8 9)))")).isEqualTo("9");
		assertThat(compileAndRun("(print (eval '(when nil 1)))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (eval '(unless nil 42)))")).isEqualTo("42");
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
		assertThat(compileAndRun("(print (eval '(let ((x 1)) (setq x 99) x)))")).isEqualTo("99");
	}

	@Test
	void evalSetqGlobalPersistsWithinEval() throws Exception {
		assertThat(compileAndRun("(print (eval '(progn (setq x 5) (* x x))))")).isEqualTo("25");
	}

	@Test
	void evalSetqGlobalPersistsAcrossEvalCalls() throws Exception {
		assertThat(compileAndRun("(eval '(setq g 42)) (print (eval 'g))")).isEqualTo("42");
	}

	@Test
	void evalSetqRuntimeFunctionDefinition() throws Exception {
		assertThat(compileAndRun("(print (eval '(progn (setq f (lambda (n) (* n 3))) (funcall f 7))))"))
			.isEqualTo("21");
	}

	@Test
	void evalNestedEval() throws Exception {
		assertThat(compileAndRun("(print (eval '(eval (list '+ 2 3))))")).isEqualTo("5");
	}

	@Test
	void evalFuncall() throws Exception {
		assertThat(compileAndRun("(print (eval '(funcall (lambda (x y) (+ x y)) 3 4)))")).isEqualTo("7");
		assertThat(compileAndRun("(print (eval '(funcall #'+ 10 20)))")).isEqualTo("30");
	}

	@Test
	void evalMapWithLambda() throws Exception {
		assertThat(compileAndRun("(print (eval '(mapcar (lambda (x) (* x x)) (list 1 2 3 4))))"))
			.isEqualTo("(1 4 9 16)");
	}

	@Test
	void evalReduce() throws Exception {
		assertThat(compileAndRun("(print (eval '(reduce (lambda (a b) (+ a b)) (list 1 2 3 4))))")).isEqualTo("10");
		assertThat(compileAndRun("(print (eval '(reduce #'+ 100 (list 1 2 3))))")).isEqualTo("106");
	}

	@Test
	void evalCarCdrComposition() throws Exception {
		assertThat(compileAndRun("(print (eval '(cadr (list 1 2 3))))")).isEqualTo("2");
		assertThat(compileAndRun("(print (eval '(caddr (list 1 2 3))))")).isEqualTo("3");
		assertThat(compileAndRun("(print (eval '(cddr (list 1 2 3 4))))")).isEqualTo("(3 4)");
	}

	@Test
	void evalNumberedAccessors() throws Exception {
		assertThat(compileAndRun("(print (eval '(first (list 10 20 30))))")).isEqualTo("10");
		assertThat(compileAndRun("(print (eval '(second (list 10 20 30))))")).isEqualTo("20");
		assertThat(compileAndRun("(print (eval '(third (list 10 20 30))))")).isEqualTo("30");
		assertThat(compileAndRun("(print (eval '(fourth (list 10 20 30 40))))")).isEqualTo("40");
	}

	@Test
	void evalNth() throws Exception {
		assertThat(compileAndRun("(print (eval '(nth 0 (list 10 20 30))))")).isEqualTo("10");
		assertThat(compileAndRun("(print (eval '(nth 2 (list 10 20 30))))")).isEqualTo("30");
		assertThat(compileAndRun("(print (eval '(nth 5 (list 10 20 30))))")).isEqualTo("nil");
	}

	@Test
	void evalSetfSymbol() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((x 1)) (setf x 9) x)))")).isEqualTo("9");
	}

	@Test
	void evalSetfCarCdr() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((c (cons 1 2))) (setf (car c) 99) c)))")).isEqualTo("(99 . 2)");
		assertThat(compileAndRun("(print (eval '(let ((c (cons 1 2))) (setf (cdr c) 99) c)))")).isEqualTo("(1 . 99)");
	}

	@Test
	void evalSetfAccessors() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((l (list 1 2 3))) (setf (cadr l) 99) l)))"))
			.isEqualTo("(1 99 3)");
		assertThat(compileAndRun("(print (eval '(let ((l (list 1 2 3))) (setf (nth 2 l) 99) l)))"))
			.isEqualTo("(1 2 99)");
		assertThat(compileAndRun("(print (eval '(let ((l (list 1 2 3))) (setf (second l) 88) l)))"))
			.isEqualTo("(1 88 3)");
	}

	@Test
	void evalPush() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((s nil)) (push 1 s) (push 2 s) s)))")).isEqualTo("(2 1)");
	}

	@Test
	void evalPop() throws Exception {
		assertThat(compileAndRun("(print (eval '(let ((s (list 1 2 3))) (pop s))))")).isEqualTo("1");
		assertThat(compileAndRun("(print (eval '(let ((s (list 1 2 3))) (pop s) s)))")).isEqualTo("(2 3)");
	}

	@Test
	void sqrt() throws Exception {
		assertThat(compileAndRun("(print (sqrt 16))")).isEqualTo("4.0");
		assertThat(compileAndRun("(print (sqrt 25))")).isEqualTo("5.0");
		assertThat(compileAndRun("(print (sqrt 6.25))")).isEqualTo("2.5");
	}

	@Test
	void isqrt() throws Exception {
		assertThat(compileAndRun("(print (isqrt 17))")).isEqualTo("4");
		assertThat(compileAndRun("(print (isqrt 16))")).isEqualTo("4");
		assertThat(compileAndRun("(print (isqrt 0))")).isEqualTo("0");
	}

	@Test
	void expt() throws Exception {
		assertThat(compileAndRun("(print (expt 2 10))")).isEqualTo("1024");
		assertThat(compileAndRun("(print (expt 3 0))")).isEqualTo("1");
		assertThat(compileAndRun("(print (expt 5 3))")).isEqualTo("125");
	}

	@Test
	void gcdLcm() throws Exception {
		assertThat(compileAndRun("(print (gcd 12 18))")).isEqualTo("6");
		assertThat(compileAndRun("(print (gcd 0 5))")).isEqualTo("5");
		assertThat(compileAndRun("(print (gcd -12 18))")).isEqualTo("6");
		assertThat(compileAndRun("(print (lcm 4 6))")).isEqualTo("12");
		assertThat(compileAndRun("(print (lcm 0 6))")).isEqualTo("0");
	}

	@Test
	void signum() throws Exception {
		assertThat(compileAndRun("(print (signum -5))")).isEqualTo("-1");
		assertThat(compileAndRun("(print (signum 0))")).isEqualTo("0");
		assertThat(compileAndRun("(print (signum 7))")).isEqualTo("1");
		assertThat(compileAndRun("(print (signum 3.5))")).isEqualTo("1.0");
		assertThat(compileAndRun("(print (signum -2.0))")).isEqualTo("-1.0");
	}

	@Test
	void mathAsFirstClass() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'sqrt (list 1 4 9)))")).isEqualTo("(1.0 2.0 3.0)");
		assertThat(compileAndRun("(print (reduce #'gcd (list 24 36 48)))")).isEqualTo("12");
		assertThat(compileAndRun("(print (eval (list (quote expt) 2 8)))")).isEqualTo("256");
		assertThat(compileAndRun("(print (eval (list (quote sqrt) 25)))")).isEqualTo("5.0");
	}

	@Test
	void transcendentalFunctionsAreUnsupported() {
		// sin/cos/tan/exp/log/etc. have no native WASM instruction and are rejected.
		assertThatThrownBy(() -> new WasmLispCompiler().compile(LispReader.readAllFromString("(print (sin 0))")))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void packageVar() throws Exception {
		assertThat(compileAndRun("(print *package*)")).isEqualTo("cl-user");
	}

	@Test
	void inPackageThenUnqualifiedVersion() throws Exception {
		String code = """
				(in-package rontolisp)
				(cl:print (cl:cadr (version)))
				""";
		assertThat(compileAndRun(code)).isEqualTo("\"" + am.ik.rontolisp.Version.getVersion() + "\"");
	}

	@Test
	void listMacros() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-macros))")).isEqualTo(
				"(and cond decf dolist dotimes format incf let* or pop push remf setf unless when with-open-file)");
	}

	@Test
	void listSpecialForms() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-special-forms))"))
			.isEqualTo("(defun function if in-package lambda let progn quote setq while)");
	}

	@Test
	void listFunctionsLength() throws Exception {
		assertThat(compileAndRun("(print (length (rontolisp:list-functions)))")).isEqualTo("104");
	}

	@Test
	void listFunctionsForClUserListsUserDefunsOnly() throws Exception {
		// Wrapper defuns injected for built-ins (car, +, ...) must not appear.
		String code = """
				(defun fib (n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
				(defun add2 (a) (+ a 2))
				(print (rontolisp:list-functions :cl-user))
				""";
		assertThat(compileAndRun(code)).isEqualTo("(add2 fib)");
	}

	@Test
	void listFunctionsForRontolisp() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-functions :rontolisp))"))
			.isEqualTo("(list-functions list-macros list-special-forms version)");
		assertThat(compileAndRun("(print (rontolisp:list-special-forms :cl-user))")).isEqualTo("nil");
	}

	@Test
	void listFunctionsUnknownPackageIsRejected() {
		assertThatThrownBy(() -> new WasmLispCompiler()
			.compile(LispReader.readAllFromString("(print (rontolisp:list-functions :foo))")))
			.isInstanceOf(am.ik.rontolisp.LispPackageException.class)
			.hasMessageContaining("No such package: foo");
	}

	@Test
	void firstRestNthAsFunctionValues() throws Exception {
		assertThat(compileAndRun("(print (funcall #'first '(1 2 3)))")).isEqualTo("1");
		assertThat(compileAndRun("(print (mapcar #'rest '((1 2) (3 4))))")).isEqualTo("((2) (4))");
		assertThat(compileAndRun("(print (funcall #'nth 1 '(1 2 3)))")).isEqualTo("2");
		assertThat(compileAndRun("(print (mapcar #'second '((1 2) (3 4))))")).isEqualTo("(2 4)");
	}

}
