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
	void compileAndRunWasmExportIsNoOp() throws Exception {
		// rontolisp:wasm-export is a directive for the WASM backend; on the JVM it is a
		// no-op and
		// the
		// marked function still compiles and runs normally.
		assertThat(compileAndRun("(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))"
				+ "(rontolisp:wasm-export 'fact :params '(:int) :returns :int)" + "(print (fact 5))"))
			.isEqualTo("120");
	}

	@Test
	void compileAndRunAddition() throws Exception {
		assertThat(compileAndRun("(print (+ 1 2))")).isEqualTo("3");
	}

	@Test
	void compileAndRunDefvarDefinesGlobal() throws Exception {
		assertThat(compileAndRun("(defvar *x* 42) (print *x*)")).isEqualTo("42");
	}

	@Test
	void compileAndRunDefvarReturnsName() throws Exception {
		assertThat(compileAndRun("(print (defvar *x* 42))")).isEqualTo("*x*");
	}

	@Test
	void compileAndRunDefvarIsIdempotent() throws Exception {
		assertThat(compileAndRun("(defvar *x* 1) (defvar *x* 2) (print *x*)")).isEqualTo("1");
	}

	@Test
	void compileAndRunDefparameterAlwaysAssigns() throws Exception {
		assertThat(compileAndRun("(defparameter *x* 1) (defparameter *x* 2) (print *x*)")).isEqualTo("2");
	}

	@Test
	void compileAndRunDefconstant() throws Exception {
		assertThat(compileAndRun("(defconstant +k+ 7) (print +k+)")).isEqualTo("7");
	}

	@Test
	void compileAndRunGlobalReadInsideFunction() throws Exception {
		// A defparameter global referenced inside a defun body must resolve (previously
		// failed to compile: "Cannot compile symbol reference: *k*").
		assertThat(compileAndRun("(defparameter *k* 3) (defun f (x) (* x *k*)) (print (f 5))")).isEqualTo("15");
	}

	@Test
	void compileAndRunGlobalAssignInsideFunctionVisibleAtTopLevel() throws Exception {
		// setq of a global inside a function mutates the shared backing store, so a
		// top-level read sees the update.
		assertThat(compileAndRun(
				"(defvar *acc* 0) (defun bump () (setq *acc* (+ *acc* 1))) (bump) (bump) (bump) (print *acc*)"))
			.isEqualTo("3");
	}

	@Test
	void compileAndRunLargeTopLevelBodySplitsAcrossMethods() throws Exception {
		// A top-level body that exceeds the JVM 64 KB per-method bytecode limit must be
		// split across several helper methods, called in order from main(), without
		// losing cross-form shared state. *n* is a top-level global (a static field), so
		// the running total survives the method boundaries.
		StringBuilder sb = new StringBuilder("(setq *n* 0)\n");
		int forms = 6000;
		for (int i = 0; i < forms; i++) {
			sb.append("(setq *n* (+ *n* 1))\n");
		}
		sb.append("(print *n*)\n");
		assertThat(compileAndRun(sb.toString())).isEqualTo(String.valueOf(forms));
	}

	@Test
	void compileAndRunNestedFreeSetqSurvivesTopLevelSplit() throws Exception {
		// A variable assigned by a setq nested inside a top-level form (never a direct
		// top-level setq) is, per Common Lisp, a global. It must get a persistent backing
		// store so a value set in one chunk is visible from a later chunk, even when the
		// body is split. The padding forms force more than 64 KB of bytecode so the two
		// references to *m* land in different methods.
		StringBuilder sb = new StringBuilder("(progn (setq *m* 7) nil)\n(setq *pad* 0)\n");
		for (int i = 0; i < 6000; i++) {
			sb.append("(setq *pad* (+ *pad* 1))\n");
		}
		sb.append("(print *m*)\n");
		assertThat(compileAndRun(sb.toString())).isEqualTo("7");
	}

	@Test
	void compileAndRunTopLevelClosureOverLetStillCaptures() throws Exception {
		// Promoting top-level free setq targets to globals must not promote a let-bound
		// variable that a lambda closes over: *make* is a global, but n stays a captured
		// lexical, so the counter increments correctly.
		assertThat(compileAndRun(
				"(setq *make* (let ((n 0)) (lambda () (setq n (+ n 1)) n))) (funcall *make*) (funcall *make*) (print (funcall *make*))"))
			.isEqualTo("3");
	}

	@Test
	void compileAndRunGlobalReadInsideLambda() throws Exception {
		// A global referenced from a lambda nested in a defun (it must be resolved from
		// its static field, not captured as a free variable).
		assertThat(compileAndRun(
				"(defparameter *base* 10) (defun adders (xs) (mapcar (lambda (x) (+ x *base*)) xs)) (print (adders '(1 2 3)))"))
			.isEqualTo("(11 12 13)");
	}

	@Test
	void compileAndRunTime() throws Exception {
		// time prints the elapsed real time and returns the form's value (here printed).
		String output = compileAndRun("(print (time (+ 1 2)))");
		assertThat(output).contains("; Elapsed real time: ").contains(" ms").endsWith("3");
	}

	@Test
	void compileAndRunDoStar() throws Exception {
		assertThat(compileAndRun("(print (do* ((i 1 (+ i 1)) (acc i (* acc i))) ((> i 5) acc)))")).isEqualTo("720");
	}

	@Test
	void compileAndRunLoopNumericCollect() throws Exception {
		assertThat(compileAndRun("(print (loop for i from 1 to 5 collect i))")).isEqualTo("(1 2 3 4 5)");
		assertThat(compileAndRun("(print (loop for i below 5 collect i))")).isEqualTo("(0 1 2 3 4)");
		assertThat(compileAndRun("(print (loop for i from 10 downto 7 collect i))")).isEqualTo("(10 9 8 7)");
	}

	@Test
	void compileAndRunLoopListAndIndex() throws Exception {
		assertThat(compileAndRun("(print (loop for x in '(a b c) for i from 0 collect (list i x)))"))
			.isEqualTo("((0 a) (1 b) (2 c))");
		assertThat(compileAndRun("(print (loop for x on '(1 2 3) collect x))")).isEqualTo("((1 2 3) (2 3) (3))");
		assertThat(compileAndRun("(print (loop for c across \"hello\" collect c))"))
			.isEqualTo("(#\\h #\\e #\\l #\\l #\\o)");
		assertThat(compileAndRun("(print (loop for c across \"hello\" count (eql c #\\l)))")).isEqualTo("2");
	}

	@Test
	void compileAndRunLoopAccumulators() throws Exception {
		assertThat(compileAndRun("(print (loop for i from 1 to 5 sum i))")).isEqualTo("15");
		assertThat(compileAndRun("(print (loop for i from 1 to 10 count (evenp i)))")).isEqualTo("5");
		assertThat(compileAndRun("(print (loop for i in '(3 1 4 1 5) maximize i))")).isEqualTo("5");
		assertThat(compileAndRun("(print (loop for i from 1 to 3 append (list i i)))")).isEqualTo("(1 1 2 2 3 3)");
	}

	@Test
	void compileAndRunLoopControl() throws Exception {
		assertThat(compileAndRun("(print (loop repeat 3 collect 'x))")).isEqualTo("(x x x)");
		assertThat(compileAndRun("(print (loop for i from 1 to 10 when (evenp i) collect i))"))
			.isEqualTo("(2 4 6 8 10)");
		assertThat(compileAndRun("(print (loop for i from 1 do (when (> i 3) (return i))))")).isEqualTo("4");
		assertThat(compileAndRun("(print (loop with a = 10 for i from 1 to 3 collect (+ a i)))"))
			.isEqualTo("(11 12 13)");
	}

	@Test
	void compileAndRunDelete() throws Exception {
		assertThat(compileAndRun("(print (delete 2 '(1 2 3 2 1)))")).isEqualTo("(1 3 1)");
		assertThat(compileAndRun("(print (delete-if #'evenp '(1 2 3 4 5)))")).isEqualTo("(1 3 5)");
		assertThat(compileAndRun("(print (delete-if-not #'oddp '(1 2 3 4 5)))")).isEqualTo("(1 3 5)");
	}

	@Test
	void compileAndRunSubstitute() throws Exception {
		assertThat(compileAndRun("(print (substitute 0 2 '(1 2 3 2 1)))")).isEqualTo("(1 0 3 0 1)");
		assertThat(compileAndRun("(print (nsubstitute 9 1 '(1 2 1 3)))")).isEqualTo("(9 2 9 3)");
	}

	@Test
	void compileAndRunDestructiveListOps() throws Exception {
		// The destructive ops reuse cons cells; an alias to the original list observes
		// the
		// mutation (Common Lisp semantics).
		assertThat(compileAndRun("(setq a (list 1 2 3)) (setq b a) (nreverse a) (print b)")).isEqualTo("(1)");
		assertThat(compileAndRun("(setq a (list 1 2 3 2 1)) (setq b a) (delete 2 a) (print b)")).isEqualTo("(1 3 1)");
		assertThat(compileAndRun("(setq a (list 1 2 3 4 5)) (setq b a) (delete-if #'evenp a) (print b)"))
			.isEqualTo("(1 3 5)");
		assertThat(compileAndRun("(setq a (list 1 2 1 3)) (setq b a) (nsubstitute 9 1 a) (print b)"))
			.isEqualTo("(9 2 9 3)");
	}

	@Test
	void compileAndRunSubstituteAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (funcall #'substitute 0 2 '(2 2 2)))")).isEqualTo("(0 0 0)");
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
	void compileAndRunFormatDollarAndFixed() throws Exception {
		assertThat(compileAndRun("(format t \"~$ ~5$ ~,2f ~v$\" 3.14159 3.14159 3.14159 3 3.14159)"))
			.isEqualTo("3.14 3.14159 3.14 3.142");
	}

	@Test
	void compileAndRunFormatExponential() throws Exception {
		assertThat(compileAndRun("(format t \"~e ~,4e ~e ~,2e ~e\" pi pi 1234.5 9.999 0.0)"))
			.isEqualTo("3.141593e+0 3.1416e+0 1.2345e+3 1.00e+1 0.0e+0");
	}

	@Test
	void compileAndRunFormatDecimalModifiers() throws Exception {
		assertThat(compileAndRun("(format t \"~:d ~@d ~:@d\" 1000000 1000000 1000000)"))
			.isEqualTo("1,000,000 +1000000 +1,000,000");
	}

	@Test
	void compileAndRunFormatPadding() throws Exception {
		assertThat(compileAndRun("(format t \"~10a|~10@a|~5,'0d|\" \"foo\" \"foo\" 42)"))
			.isEqualTo("foo       |       foo|00042|");
	}

	@Test
	void compileAndRunFormatFreshLine() throws Exception {
		assertThat(compileAndRun("(format t \"a\") (format t \"~&b~&c~%\") (fresh-line) (princ \"d\")"))
			.isEqualTo("a\nb\nc\nd");
	}

	@Test
	void compileAndRunFormatEdges() throws Exception {
		// Negative-width padding, a custom comma character, a runtime (v) width and
		// bignum
		// grouping all run through the same expansion on the JVM backend.
		assertThat(compileAndRun("(format t \"[~6d][~,,'.:d][~va][~:d]\" -42 1234567 8 \"hi\" 100000000000000000000)"))
			.isEqualTo("[   -42][1.234.567][hi      ][100,000,000,000,000,000,000]");
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
	void compileAndRunMapListOverLists() throws Exception {
		assertThat(compileAndRun("(print (map 'list #'+ '(1 2 3) '(10 20 30)))")).isEqualTo("(11 22 33)");
	}

	@Test
	void compileAndRunMapListStopsAtShortestSequence() throws Exception {
		assertThat(compileAndRun("(print (map 'list #'+ '(1 2 3) '(10 20)))")).isEqualTo("(11 22)");
	}

	@Test
	void compileAndRunMapStringOverString() throws Exception {
		assertThat(compileAndRun("(print (map 'string #'char-upcase \"abc\"))")).isEqualTo("\"ABC\"");
	}

	@Test
	void compileAndRunMapListOverString() throws Exception {
		assertThat(compileAndRun("(print (map 'list (lambda (c) (char-code c)) \"AB\"))")).isEqualTo("(65 66)");
	}

	@Test
	void compileAndRunMapNilCallsForEffect() throws Exception {
		assertThat(compileAndRun("(map nil #'print '(7 8 9))")).isEqualTo("7\n8\n9");
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
	void compileAndRunCarCdrOfNil() throws Exception {
		assertThat(compileAndRun("(print (car nil))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (cdr nil))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (car '()))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (cdr '()))")).isEqualTo("nil");
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
	void compileAndRunExponentFloatLiteral() throws Exception {
		// Common Lisp exponent-marker float literals all compile to a double.
		assertThat(compileAndRun("(print 1d0)")).isEqualTo("1.0");
		assertThat(compileAndRun("(print (* 2 1d0))")).isEqualTo("2.0");
		assertThat(compileAndRun("(print 1.5d3)")).isEqualTo("1500.0");
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
	void compileAndRunAbsOnFloatThroughAVariable() throws Exception {
		// Regression: abs of a float reaching it through a variable (no compile-time
		// double literal in the argument) used to take the integer runtime path and trap
		// with "Double cannot be cast to BigInteger". _abs now dispatches on the runtime
		// type and handles a Double.
		assertThat(compileAndRun("(let ((x -0.5)) (print (abs x)))")).isEqualTo("0.5");
		assertThat(compileAndRun("(let ((x 0.5)) (print (abs x)))")).isEqualTo("0.5");
		assertThat(compileAndRun("""
				(defun g (a) (abs a))
				(print (g -2.5))
				""")).isEqualTo("2.5");
		// A literal-double argument (the compile-time fast path) still works.
		assertThat(compileAndRun("(print (abs -2.5))")).isEqualTo("2.5");
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
	void compileAndRunFloatArithmeticOnNonLiteralOperands() throws Exception {
		// Float values reaching an operator through variables/parameters (not as a
		// literal) must still use float arithmetic, with integer contagion.
		assertThat(compileAndRun("(defun f (a b) (+ a b)) (print (f 1.5 2.5))")).isEqualTo("4.0");
		assertThat(compileAndRun("(defun f (a b) (- a b)) (print (f 1.0 0.25))")).isEqualTo("0.75");
		assertThat(compileAndRun("(defun f (a b) (* a b)) (print (f 1.5 2.5))")).isEqualTo("3.75");
		assertThat(compileAndRun("(defun f (a b) (/ a b)) (print (f 3.0 2.0))")).isEqualTo("1.5");
		assertThat(compileAndRun("(defun f (a b) (+ a b)) (print (f 1 2.0))")).isEqualTo("3.0");
		assertThat(compileAndRun("(defun neg (a) (- a)) (print (neg 2.5))")).isEqualTo("-2.5");
		// Comparisons on non-literal float operands.
		assertThat(compileAndRun("(defun lt (a b) (if (< a b) 1 0)) (print (lt 1.5 2.5))")).isEqualTo("1");
		assertThat(compileAndRun("(defun gt (a b) (if (> a b) 1 0)) (print (gt 1.5 2.5))")).isEqualTo("0");
		assertThat(compileAndRun("(defun eq2 (a b) (if (= a b) 1 0)) (print (eq2 2.0 2.0))")).isEqualTo("1");
		// Operators as first-class values over floats.
		assertThat(compileAndRun("(print (reduce #'+ (list 1.0 2.0 3.0) :initial-value 0))")).isEqualTo("6.0");
		assertThat(compileAndRun("(print (funcall #'* 1.5 2.0))")).isEqualTo("3.0");
		// Integer, big-integer and ratio paths are unaffected by the float fast path.
		assertThat(compileAndRun("(defun f (a b) (+ a b)) (print (f 1 2))")).isEqualTo("3");
		assertThat(compileAndRun("(defun f (a b) (* a b)) (print (f 1000000000000 1000000000000))"))
			.isEqualTo("1000000000000000000000000");
		assertThat(compileAndRun("(defun f (a b) (/ a b)) (print (f 1 3))")).isEqualTo("1/3");
	}

	@Test
	void compileAndRunRandom() throws Exception {
		// (random 1) is always 0; the result type follows the limit and stays in range.
		assertThat(compileAndRun("(print (random 1))")).isEqualTo("0");
		assertThat(compileAndRun("(print (integerp (random 100)))")).isEqualTo("t");
		assertThat(compileAndRun("(print (floatp (random 5.0)))")).isEqualTo("t");
		assertThat(compileAndRun("(let ((r (random 10))) (if (and (>= r 0) (< r 10)) (print \"in\") (print \"oob\")))"))
			.isEqualTo("\"in\"");
		assertThat(compileAndRun(
				"(let ((r (random 1.0))) (if (and (>= r 0.0) (< r 1.0)) (print \"in\") (print \"oob\")))"))
			.isEqualTo("\"in\"");
	}

	@Test
	void compileAndRunRandomOnFloatLimitThroughAVariable() throws Exception {
		// Regression: like abs/signum, a float limit reaching random through a variable
		// (no float literal in the argument) used to take the integer path and trap with
		// "Double cannot be cast to Long". _random now dispatches on the runtime type, so
		// a float limit yields a float and an integer limit yields an integer.
		assertThat(compileAndRun("(let ((x 5.0)) (print (floatp (random x))))")).isEqualTo("t");
		assertThat(compileAndRun("(let ((x 5)) (print (integerp (random x))))")).isEqualTo("t");
		assertThat(compileAndRun("""
				(defun rnd (limit) (random limit))
				(let ((r (rnd 1.0))) (if (and (>= r 0.0) (< r 1.0)) (print "in") (print "oob")))
				""")).isEqualTo("\"in\"");
		assertThat(compileAndRun("""
				(defun rndi (limit) (random limit))
				(let ((r (rndi 10))) (if (and (>= r 0) (< r 10)) (print "in") (print "oob")))
				""")).isEqualTo("\"in\"");
	}

	@Test
	void compileAndRunTimeFunctions() throws Exception {
		// get-universal-time is seconds since 1900; well past 2020 (> 3.78e9).
		assertThat(compileAndRun("(print (> (get-universal-time) 3786825600))")).isEqualTo("t");
		assertThat(compileAndRun("(print (integerp (get-internal-real-time)))")).isEqualTo("t");
		assertThat(compileAndRun("(print (integerp (get-internal-run-time)))")).isEqualTo("t");
	}

	@Test
	void compileAndRunGetenv() throws Exception {
		// PATH is set in the test environment; an unset variable yields nil.
		assertThat(compileAndRun("(print (stringp (getenv \"PATH\")))")).isEqualTo("t");
		assertThat(compileAndRun("(print (getenv \"RONTOLISP_DEFINITELY_UNSET_VAR\"))")).isEqualTo("nil");
	}

	@Test
	void compileAndRunSignum() throws Exception {
		assertThat(compileAndRun("(print (signum -5))")).isEqualTo("-1");
		assertThat(compileAndRun("(print (signum 0))")).isEqualTo("0");
		assertThat(compileAndRun("(print (signum 7))")).isEqualTo("1");
		assertThat(compileAndRun("(print (signum 3.5))")).isEqualTo("1.0");
	}

	@Test
	void compileAndRunSignumOnFloatThroughAVariable() throws Exception {
		// Regression: like abs, signum of a float reaching it through a variable used to
		// take the integer path (_ratnum -> _big) and trap with "Double cannot be cast to
		// BigInteger". _signum now dispatches on the runtime type.
		assertThat(compileAndRun("(let ((x -0.5)) (print (signum x)))")).isEqualTo("-1.0");
		assertThat(compileAndRun("(let ((x 2.5)) (print (signum x)))")).isEqualTo("1.0");
		assertThat(compileAndRun("(let ((x 0.0)) (print (signum x)))")).isEqualTo("0.0");
		assertThat(compileAndRun("""
				(defun s (a) (signum a))
				(print (s -3.0))
				""")).isEqualTo("-1.0");
		// Integer / ratio still return an integer sign.
		assertThat(compileAndRun("(let ((x -5)) (print (signum x)))")).isEqualTo("-1");
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
	void compileAndRunProg1() throws Exception {
		assertThat(compileAndRun("(print (prog1 1 2 3))")).isEqualTo("1");
		assertThat(compileAndRun("(print (prog1 99))")).isEqualTo("99");
		assertThat(compileAndRun("(print (let ((x (list 1 2 3))) (prog1 (car x) (setq x (cdr x)))))")).isEqualTo("1");
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
	void compileAndRunEqlSameInteger() throws Exception {
		assertThat(compileAndRun("(print (if (eql 3 3) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunEqlDifferentTypeNumbers() throws Exception {
		assertThat(compileAndRun("(print (if (eql 3 3.0) 42 99))")).isEqualTo("99");
	}

	@Test
	void compileAndRunEqlSymbols() throws Exception {
		assertThat(compileAndRun("(print (if (eql 'foo 'foo) 42 99))")).isEqualTo("42");
	}

	@Test
	void compileAndRunEqlAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (funcall #'eql 5 5))")).isEqualTo("t");
	}

	@Test
	void compileAndRunEqlFloatsByValue() throws Exception {
		assertThat(compileAndRun("(print (eql 1.5 1.5))")).isEqualTo("t");
	}

	@Test
	void compileAndRunEqFloatsNotEq() throws Exception {
		assertThat(compileAndRun("(print (eq 1.5 1.5))")).isEqualTo("nil");
	}

	@Test
	void compileAndRunEqIntegersStillEq() throws Exception {
		assertThat(compileAndRun("(print (eq 3 3))")).isEqualTo("t");
	}

	@Test
	void compileAndRunEqualNestedLists() throws Exception {
		assertThat(compileAndRun("(print (equal '(1 2 (3)) '(1 2 (3))))")).isEqualTo("t");
	}

	@Test
	void compileAndRunEqualDifferentLists() throws Exception {
		assertThat(compileAndRun("(print (equal '(1 2) '(1 3)))")).isEqualTo("nil");
	}

	@Test
	void compileAndRunEqualStrings() throws Exception {
		assertThat(compileAndRun("(print (equal \"abc\" \"abc\"))")).isEqualTo("t");
	}

	@Test
	void compileAndRunEqualDifferentTypeNumbers() throws Exception {
		assertThat(compileAndRun("(print (equal 3 3.0))")).isEqualTo("nil");
	}

	@Test
	void compileAndRunEqualFreshConsesUnlikeEql() throws Exception {
		assertThat(compileAndRun("(print (eql (list 1 2) (list 1 2)))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (equal (list 1 2) (list 1 2)))")).isEqualTo("t");
	}

	@Test
	void compileAndRunEqualAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (funcall #'equal '(1) '(1)))")).isEqualTo("t");
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
		assertThat(compileAndRun("(print (reduce #'+ '(1 2 3 4 5) :initial-value 0))")).isEqualTo("15");
	}

	@Test
	void compileAndRunReduceWithBuiltinMul() throws Exception {
		assertThat(compileAndRun("(print (reduce #'* '(1 2 3 4 5) :initial-value 1))")).isEqualTo("120");
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
	void compileAndRunDo() throws Exception {
		assertThat(compileAndRun("(print (do ((i 0 (+ i 1)) (s 0)) ((= i 5) s) (setq s (+ s i))))")).isEqualTo("10");
	}

	@Test
	void compileAndRunDoParallelStep() throws Exception {
		assertThat(compileAndRun("(print (do ((i 0 (+ i 1)) (a 0 b) (b 1 (+ a b))) ((= i 10) a)))")).isEqualTo("55");
	}

	@Test
	void compileAndRunReturnFromDolist() throws Exception {
		assertThat(compileAndRun("(print (dolist (m '(2 3 5) t) (if (= m 3) (return))))")).isEqualTo("nil");
	}

	@Test
	void compileAndRunReturnWithValue() throws Exception {
		assertThat(compileAndRun("(print (dotimes (i 5 -1) (if (evenp i) (return i))))")).isEqualTo("0");
	}

	@Test
	void compileAndRunReturnFromDo() throws Exception {
		assertThat(compileAndRun("(print (do ((i 0 (+ i 1))) ((> i 100) -1) (if (= i 4) (return i))))")).isEqualTo("4");
	}

	@Test
	void compileAndRunReturnExitsInnermostLoopOnly() throws Exception {
		assertThat(compileAndRun("""
				(setq total 0)
				(dolist (a '(1 2 3))
				  (dolist (b '(10 20 30))
				    (if (= b 20) (return))
				    (setq total (+ total b))))
				(print total)""")).isEqualTo("30");
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
	void compileAndRunMemberWithTest() throws Exception {
		assertThat(compileAndRun("(print (member '(a d) '((a b) (a c) (a d) (a e)) :test 'equal)) "
				+ "(print (member '(a d) '((a b) (a c) (a d) (a e))))"))
			.isEqualTo("((a d) (a e))\nnil");
	}

	@Test
	void compileAndRunFind() throws Exception {
		assertThat(compileAndRun(
				"(print (find 3 '(1 2 3 4))) (print (find 9 '(1 2 3))) (print (funcall #'find 2 '(1 2 3)))"))
			.isEqualTo("3\nnil\n2");
	}

	@Test
	void compileAndRunFindIf() throws Exception {
		assertThat(compileAndRun(
				"(print (find-if #'evenp '(1 3 5 6 7))) (print (find-if #'oddp '(2 4 6))) (print (funcall #'find-if #'plusp '(-1 -2 3 4)))"))
			.isEqualTo("6\nnil\n3");
	}

	@Test
	void compileAndRunFindIfNot() throws Exception {
		assertThat(compileAndRun(
				"(print (find-if-not #'evenp '(2 4 5 6))) (print (find-if-not #'plusp '(1 2 3))) (print (funcall #'find-if-not #'oddp '(1 3 4)))"))
			.isEqualTo("5\nnil\n4");
	}

	@Test
	void compileAndRunPosition() throws Exception {
		assertThat(compileAndRun(
				"(print (position 3 '(1 2 3 4))) (print (position 9 '(1 2 3))) (print (funcall #'position 2 '(5 2 8)))"))
			.isEqualTo("2\nnil\n1");
	}

	@Test
	void compileAndRunPositionIf() throws Exception {
		assertThat(compileAndRun(
				"(print (position-if #'evenp '(1 3 5 6 7))) (print (position-if #'plusp '(-1 -2 -3))) (print (funcall #'position-if #'oddp '(2 4 5)))"))
			.isEqualTo("3\nnil\n2");
	}

	@Test
	void compileAndRunCount() throws Exception {
		assertThat(compileAndRun(
				"(print (count 2 '(1 2 3 2 2))) (print (count 9 '(1 2 3))) (print (funcall #'count 2 '(2 2 8)))"))
			.isEqualTo("3\n0\n2");
	}

	@Test
	void compileAndRunCountIf() throws Exception {
		assertThat(compileAndRun(
				"(print (count-if #'evenp '(1 2 3 4 5 6))) (print (count-if #'oddp '(2 4 6))) (print (funcall #'count-if #'evenp '(2 2 8 1)))"))
			.isEqualTo("3\n0\n3");
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
	void compileAndRunMemberIf() throws Exception {
		assertThat(compileAndRun(
				"(print (member-if #'oddp '(2 4 5 6))) (print (member-if #'evenp '(1 3 5))) (print (funcall #'member-if #'plusp '(-1 3 4)))"))
			.isEqualTo("(5 6)\nnil\n(3 4)");
	}

	@Test
	void compileAndRunAssocIf() throws Exception {
		assertThat(compileAndRun(
				"(print (assoc-if #'oddp '((2 a) (3 b) (5 c)))) (print (assoc-if #'evenp '((1 a) (3 b)))) (print (funcall #'assoc-if #'plusp '((-1 a) (2 b))))"))
			.isEqualTo("(3 b)\nnil\n(2 b)");
	}

	@Test
	void compileAndRunGetf() throws Exception {
		assertThat(compileAndRun(
				"(print (getf '(:a 1 :b 2) :b)) (print (getf '(:a 1) :x)) (print (funcall #'getf '(:x 10 :y 20) :y))"))
			.isEqualTo("2\nnil\n20");
	}

	@Test
	void compileAndRunRemoveDuplicates() throws Exception {
		assertThat(compileAndRun(
				"(print (remove-duplicates '(1 2 1 3))) (print (remove-duplicates '(1 2 3))) (print (funcall #'remove-duplicates '(a b a a c)))"))
			.isEqualTo("(2 1 3)\n(1 2 3)\n(b a c)");
	}

	@Test
	void compileAndRunButlast() throws Exception {
		assertThat(compileAndRun(
				"(print (butlast '(1 2 3))) (print (butlast '(1))) (print (butlast nil)) (print (funcall #'butlast '(a b c d)))"))
			.isEqualTo("(1 2)\nnil\nnil\n(a b c)");
	}

	@Test
	void compileAndRunNconc() throws Exception {
		assertThat(compileAndRun(
				"(print (nconc (list 1 2) (list 3 4))) (print (nconc nil (list 1 2))) (print (funcall #'nconc (list 'a) (list 'b 'c)))"))
			.isEqualTo("(1 2 3 4)\n(1 2)\n(a b c)");
	}

	@Test
	void compileAndRunIdentity() throws Exception {
		assertThat(compileAndRun("(print (identity 42)) (print (identity '(1 2 3))) (print (funcall #'identity 'x))"))
			.isEqualTo("42\n(1 2 3)\nx");
	}

	@Test
	void compileAndRunCopyList() throws Exception {
		assertThat(compileAndRun(
				"(print (copy-list '(1 2 3))) (print (copy-list nil)) (print (funcall #'copy-list '(a b)))"))
			.isEqualTo("(1 2 3)\nnil\n(a b)");
	}

	@Test
	void compileAndRunNreverse() throws Exception {
		assertThat(compileAndRun(
				"(print (nreverse '(1 2 3))) (print (nreverse nil)) (print (funcall #'nreverse '(a b c)))"))
			.isEqualTo("(3 2 1)\nnil\n(c b a)");
	}

	@Test
	void compileAndRunMakeList() throws Exception {
		assertThat(compileAndRun("(print (make-list 3)) (print (make-list 0)) (print (funcall #'make-list 2))"))
			.isEqualTo("(nil nil nil)\nnil\n(nil nil)");
	}

	@Test
	void compileAndRunUnion() throws Exception {
		assertThat(compileAndRun(
				"(print (union '(1 2 3) '(2 3 4))) (print (union nil '(1 2))) (print (funcall #'union '(a) '(a b)))"))
			.isEqualTo("(4 1 2 3)\n(2 1)\n(b a)");
	}

	@Test
	void compileAndRunIntersection() throws Exception {
		assertThat(compileAndRun(
				"(print (intersection '(1 2 3) '(2 3 4))) (print (intersection '(1 2) '(3 4))) (print (funcall #'intersection '(a b c) '(b c d)))"))
			.isEqualTo("(3 2)\nnil\n(c b)");
	}

	@Test
	void compileAndRunSetDifference() throws Exception {
		assertThat(compileAndRun(
				"(print (set-difference '(1 2 3) '(2))) (print (set-difference '(1 2 3) '(1 2 3))) (print (funcall #'set-difference '(a b c) '(b)))"))
			.isEqualTo("(3 1)\nnil\n(c a)");
	}

	@Test
	void compileAndRunAdjoin() throws Exception {
		assertThat(compileAndRun(
				"(print (adjoin 1 '(2 3))) (print (adjoin 2 '(1 2 3))) (print (adjoin 'a nil)) (print (funcall #'adjoin 5 '(5 6)))"))
			.isEqualTo("(1 2 3)\n(1 2 3)\n(a)\n(5 6)");
	}

	@Test
	void compileAndRunBitwiseOps() throws Exception {
		assertThat(compileAndRun(
				"(print (logand 12 10)) (print (logior 12 10)) (print (logxor 12 10)) (print (lognot 5)) (print (ash 1 4)) (print (ash 255 -4))"))
			.isEqualTo("8\n14\n6\n-6\n16\n15");
	}

	@Test
	void compileAndRunBitwiseVariadicAndFirstClass() throws Exception {
		assertThat(compileAndRun(
				"(print (logand 12 10 6)) (print (logior 1 2 4 8)) (print (funcall #'logand 6 3)) (print (funcall #'lognot 0))"))
			.isEqualTo("0\n15\n2\n-1");
	}

	@Test
	void compileAndRunListStarAndAcons() throws Exception {
		assertThat(compileAndRun(
				"(print (list* 1 2 '(3 4))) (print (list* 1 2 3)) (print (list* 'x)) (print (acons 'a 1 nil))"))
			.isEqualTo("(1 2 3 4)\n(1 2 . 3)\nx\n((a . 1))");
	}

	@Test
	void compileAndRunEltEndpRassoc() throws Exception {
		assertThat(compileAndRun(
				"(print (elt '(a b c) 1)) (print (endp nil)) (print (endp '(1))) (print (rassoc 2 (list (cons 'a 1) (cons 'b 2))))"))
			.isEqualTo("b\nt\nnil\n(b . 2)");
	}

	@Test
	void compileAndRunRevappendMaplistMapcon() throws Exception {
		assertThat(compileAndRun(
				"(print (revappend '(1 2 3) '(4 5))) (print (nreconc '(1 2 3) '(4 5))) (print (maplist #'identity '(1 2 3))) (print (mapcon #'(lambda (x) (list (car x))) '(1 2 3)))"))
			.isEqualTo("(3 2 1 4 5)\n(3 2 1 4 5)\n((1 2 3) (2 3) (3))\n(1 2 3)");
	}

	@Test
	void compileAndRunNotanyNotevery() throws Exception {
		assertThat(compileAndRun(
				"(print (notany #'evenp '(1 3 5))) (print (notany #'evenp '(1 2 3))) (print (notevery #'evenp '(2 4 5))) (print (notevery #'evenp '(2 4 6)))"))
			.isEqualTo("t\nnil\nt\nnil");
	}

	@Test
	void compileAndRunProg2Psetq() throws Exception {
		assertThat(compileAndRun("(print (prog2 1 2 3)) (print (let ((a 1) (b 2)) (psetq a b b a) (list a b)))"))
			.isEqualTo("2\n(2 1)");
	}

	@Test
	void compileAndRunTypecase() throws Exception {
		assertThat(compileAndRun(
				"(print (typecase 42 (string \"s\") (integer \"i\") (t \"?\"))) (print (typecase \"x\" (string \"s\") (integer \"i\") (t \"?\"))) (print (typecase 'sym (string \"s\") (integer \"i\") (t \"?\")))"))
			.isEqualTo("\"i\"\n\"s\"\n\"?\"");
	}

	@Test
	void compileAndRunEcase() throws Exception {
		assertThat(compileAndRun(
				"(print (ecase 2 (1 \"one\") (2 \"two\") (3 \"three\"))) (print (ecase 'b ((a) \"A\") ((b c) \"BC\")))"))
			.isEqualTo("\"two\"\n\"BC\"");
		assertThatThrownBy(() -> compileAndRun("(print (ecase 9 (1 \"one\") (2 \"two\")))"))
			.hasRootCauseMessage("ECASE: no clause matches 9");
	}

	@Test
	void compileAndRunCcase() throws Exception {
		assertThat(compileAndRun("(print (ccase 1 (1 \"one\") (2 \"two\")))")).isEqualTo("\"one\"");
		assertThatThrownBy(() -> compileAndRun("(print (ccase 9 (1 \"one\")))"))
			.hasRootCauseMessage("ECASE: no clause matches 9");
	}

	@Test
	void compileAndRunEtypecase() throws Exception {
		assertThat(compileAndRun(
				"(print (etypecase 42 (string \"s\") (integer \"i\"))) (print (etypecase \"x\" (string \"s\") (integer \"i\")))"))
			.isEqualTo("\"i\"\n\"s\"");
		assertThatThrownBy(() -> compileAndRun("(print (etypecase 'sym (string \"s\") (integer \"i\")))"))
			.hasRootCauseMessage("ETYPECASE: no clause matches sym");
	}

	@Test
	void compileAndRunError() throws Exception {
		assertThatThrownBy(() -> compileAndRun("(error \"boom\")")).hasRootCauseMessage("boom");
		assertThatThrownBy(() -> compileAndRun("(error \"bad value: ~a\" (+ 1 2))"))
			.hasRootCauseMessage("bad value: 3");
	}

	@Test
	void compileAndRunMapFamilyErrorsOnNonList() throws Exception {
		// The map* family operates on lists; a non-list (e.g. a string) signals an error
		// rather than silently returning nil, matching the interpreter (.todo/26).
		assertThatThrownBy(() -> compileAndRun("(mapcar #'identity \"abc\")"))
			.hasRootCauseMessage("mapcar: argument is not a list (use map for strings/vectors)");
		assertThatThrownBy(() -> compileAndRun("(mapc #'identity \"abc\")"))
			.hasRootCauseMessage("mapc: argument is not a list (use map for strings/vectors)");
		assertThatThrownBy(() -> compileAndRun("(mapcan #'list \"abc\")"))
			.hasRootCauseMessage("mapcan: argument is not a list (use map for strings/vectors)");
		assertThatThrownBy(() -> compileAndRun("(maplist #'identity \"abc\")"))
			.hasRootCauseMessage("maplist: argument is not a list: \"abc\" (use map for strings/vectors)");
		assertThatThrownBy(() -> compileAndRun("(mapcon #'list \"abc\")"))
			.hasRootCauseMessage("mapcon: argument is not a list: \"abc\" (use map for strings/vectors)");
		// nil (the empty list) and proper lists stay accepted.
		assertThat(compileAndRun("(print (mapcar #'1+ '(1 2 3))) (print (mapcar #'1+ nil))")).isEqualTo("(2 3 4)\nnil");
		assertThat(compileAndRun("(print (maplist #'identity nil))")).isEqualTo("nil");
	}

	@Test
	void compileAndRunEvery() throws Exception {
		assertThat(compileAndRun("(print (every #'evenp '(2 4 6))) (print (every #'evenp '(2 3 6)))"))
			.isEqualTo("t\nnil");
	}

	@Test
	void compileAndRunSome() throws Exception {
		assertThat(compileAndRun("(print (some #'oddp '(2 4 5))) (print (some #'oddp '(2 4 6)))")).isEqualTo("t\nnil");
		// some returns the first non-nil predicate result.
		assertThat(compileAndRun("(print (some (lambda (x) (if (> x 3) (* x 10))) '(1 2 5)))")).isEqualTo("50");
	}

	@Test
	void compileAndRunRemove() throws Exception {
		assertThat(compileAndRun("(print (remove 2 '(1 2 3 2 4))) (print (remove 9 '(1 2 3)))"))
			.isEqualTo("(1 3 4)\n(1 2 3)");
	}

	@Test
	void compileAndRunRemoveIf() throws Exception {
		assertThat(compileAndRun("(print (remove-if #'evenp '(1 2 3 4 5)))")).isEqualTo("(1 3 5)");
	}

	@Test
	void compileAndRunRemoveIfNot() throws Exception {
		assertThat(compileAndRun("(print (remove-if-not #'evenp '(1 2 3 4 5)))")).isEqualTo("(2 4)");
	}

	@Test
	void compileAndRunRemoveIfNotAsFirstClass() throws Exception {
		assertThat(compileAndRun("(print (funcall #'remove-if-not #'oddp '(1 2 3 4)))")).isEqualTo("(1 3)");
	}

	@Test
	void compileAndRunRemoveAsFirstClass() throws Exception {
		assertThat(compileAndRun("(print (funcall #'remove 2 '(1 2 3 2)))")).isEqualTo("(1 3)");
	}

	@Test
	void compileAndRunMapcan() throws Exception {
		assertThat(compileAndRun("(print (mapcan (lambda (x) (list x x)) '(1 2 3)))")).isEqualTo("(1 1 2 2 3 3)");
		assertThat(compileAndRun("(print (mapcan (lambda (x) (if (evenp x) (list x) nil)) '(1 2 3 4)))"))
			.isEqualTo("(2 4)");
		assertThat(compileAndRun("(print (funcall #'mapcan (lambda (x) (list x)) '(1 2 3)))")).isEqualTo("(1 2 3)");
	}

	@Test
	void compileAndRunSort() throws Exception {
		assertThat(compileAndRun("(print (sort '(3 1 4 1 5 9 2 6) #'<))")).isEqualTo("(1 1 2 3 4 5 6 9)");
		assertThat(compileAndRun("(print (sort '(3 1 4) #'>))")).isEqualTo("(4 3 1)");
		assertThat(compileAndRun("(print (sort '() #'<)) (print (sort '(5) #'<))")).isEqualTo("nil\n(5)");
		assertThat(compileAndRun("(print (funcall #'sort '(2 3 1) #'<))")).isEqualTo("(1 2 3)");
	}

	@Test
	void compileAndRunApply() throws Exception {
		// In compiled code apply dispatches by the actual argument count, so the applied
		// function must have a matching arity (the eval-runtime limitation): binary
		// built-in wrappers and user defuns of any fixed arity work.
		assertThat(compileAndRun("(print (apply #'+ '(1 2)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (apply #'cons 1 '(2)))")).isEqualTo("(1 . 2)");
		assertThat(compileAndRun("(print (apply (lambda (a b) (+ a b)) '(3 4)))")).isEqualTo("7");
		assertThat(compileAndRun("(defun add3 (a b c) (+ a (+ b c))) (print (apply #'add3 1 2 '(3)))")).isEqualTo("6");
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
	void compileAndRunMapcReturnsOriginalList() throws Exception {
		// mapc prints each element (side effect) and returns the original list.
		assertThat(compileAndRun("(print (mapc #'print '(10 20)))")).isEqualTo("10\n20\n(10 20)");
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
		assertThat(compileAndRun("(print (eql 1/2 1/2))")).isEqualTo("t");
		assertThat(compileAndRun("(print (eq 1/2 1/2))")).isEqualTo("nil");
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
	void compileEvalResolvesTopLevelGlobalVariable() throws Exception {
		// A top-level setq/defvar global must be visible to the embedded eval runtime
		// (its value is mirrored into the runtime's global environment).
		assertThat(compileAndRun("(setq foo 42) (print (eval (quote foo)))")).isEqualTo("42");
		assertThat(compileAndRun("(defvar *g* 99) (print (eval (quote *g*)))")).isEqualTo("99");
		assertThat(compileAndRun("(defparameter *p* 7) (print (eval (quote *p*)))")).isEqualTo("7");
		// A closure stored in a top-level global, then funcall'd through eval.
		assertThat(compileAndRun(
				"(defun make-adder (n) (lambda (x) (+ x n))) (setq add10 (make-adder 10)) (print (eval (quote (funcall add10 100))))"))
			.isEqualTo("110");
	}

	@Test
	void compileEvalResolvesGlobalClosureViaReadFuncall() throws Exception {
		// The exact playground scenario: define a closure global, then funcall it from
		// an expression read at runtime.
		assertThat(compileAndRunWithStdin(
				"(defun make-adder (n) (lambda (x) (+ x n))) (setq add10 (make-adder 10)) (print (eval (read)))",
				"(funcall add10 100)\n"))
			.isEqualTo("110");
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
				"(and case ccase cond decf do do* dolist dotimes ecase error etypecase format incf let* loop or pop prog1 prog2 psetq push remf setf time typecase unless when with-open-file)");
	}

	@Test
	void compileAndRunListSpecialForms() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-special-forms))")).isEqualTo(
				"(defconstant defparameter defun defvar function if in-package lambda let progn quote return setq while)");
	}

	@Test
	void compileAndRunListFunctionsLength() throws Exception {
		assertThat(compileAndRun("(print (length (rontolisp:list-functions)))")).isEqualTo("187");
	}

	@Test
	void compileAndRunListFunctionsAcceptsBareSymbolDesignator() throws Exception {
		assertThat(compileAndRun("(print (length (rontolisp:list-functions cl)))")).isEqualTo("187");
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
			.isEqualTo("(fetch list-functions list-macros list-special-forms version)");
		assertThat(compileAndRun("(print (rontolisp:list-macros :rontolisp))")).isEqualTo("nil");
	}

	@Test
	void compileAndRunListFunctionsForJava() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-functions :java))"))
			.isEqualTo("(call field new proxy static)");
		assertThat(compileAndRun("(print (rontolisp:list-macros :java))")).isEqualTo("nil");
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

	@Test
	void compileAndRunFetchReturnsStatusBodyAndHeaders() throws Exception {
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
			.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/echo", exchange -> {
			String received = exchange.getRequestHeaders().getFirst("X-Custom");
			byte[] body = ("got:" + received).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("X-Test", "ok");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			int port = server.getAddress().getPort();
			String url = "http://127.0.0.1:" + port + "/echo";
			assertThat(compileAndRun("(print (getf (rontolisp:fetch \"" + url
					+ "\" (list :headers (list (cons \"X-Custom\" \"abc\")))) " + ":status))"))
				.isEqualTo("200");
			assertThat(compileAndRun("(print (getf (rontolisp:fetch \"" + url
					+ "\" (list :headers (list (cons \"X-Custom\" \"abc\")))) " + ":body))"))
				.isEqualTo("\"got:abc\"");
			String headers = compileAndRun("(print (getf (rontolisp:fetch \"" + url + "\") :headers))");
			assertThat(headers).contains("x-test");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void compileAndRunFetchSendsMethodAndBody() throws Exception {
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
			assertThat(compileAndRun("(print (getf (rontolisp:fetch \"http://127.0.0.1:" + port
					+ "/post\" (list :method \"post\" :body \"hello\")) :body))"))
				.isEqualTo("\"POST:hello\"");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void compileAndRunFetchRejectsUnsupportedMethod() throws Exception {
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
			.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/x", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			exchange.close();
		});
		server.start();
		try {
			int port = server.getAddress().getPort();
			assertThatThrownBy(
					() -> compileAndRun("(rontolisp:fetch \"http://127.0.0.1:" + port + "/x\" (list :method \"FOO\"))"))
				.hasStackTraceContaining("unsupported method");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void compileFetchRejectsWrongArgCount() {
		assertThatThrownBy(() -> compileAndRun("(rontolisp:fetch)")).isInstanceOf(UnsupportedOperationException.class);
	}

	// Characters and string/number parsing

	@Test
	void compileCharAccessors() throws Exception {
		assertThat(compileAndRun("(print (char-code #\\A)) (print (code-char 66)) (print (char \"hello\" 1))"))
			.isEqualTo("65\n#\\B\n#\\e");
	}

	@Test
	void compileCharCaseAndPredicates() throws Exception {
		assertThat(compileAndRun("""
				(print (char-upcase #\\a))
				(print (char-downcase #\\Z))
				(print (characterp #\\a))
				(print (characterp 5))
				(print (alpha-char-p #\\x))
				(print (alpha-char-p #\\5))
				(print (digit-char-p #\\7))
				(print (digit-char-p #\\f 16))
				(print (digit-char-p #\\9 8))
				""")).isEqualTo("#\\A\n#\\z\nt\nnil\nt\nnil\n7\n15\nnil");
	}

	@Test
	void compileCharComparisonsVariadic() throws Exception {
		assertThat(compileAndRun(
				"(print (char= #\\a #\\a)) (print (char< #\\a #\\b #\\c)) (print (char<= #\\a #\\a #\\b)) (print (char< #\\b #\\a))"))
			.isEqualTo("t\nt\nt\nnil");
	}

	@Test
	void compileCharEqualityAndFirstClass() throws Exception {
		assertThat(compileAndRun("""
				(print (eql #\\a #\\a))
				(print (equal (list #\\a #\\b) (list #\\a #\\b)))
				(print (mapcar #'char-upcase (list #\\a #\\b #\\c)))
				""")).isEqualTo("t\nt\n(#\\A #\\B #\\C)");
	}

	@Test
	void compileCharPrinting() throws Exception {
		assertThat(compileAndRun("(prin1 #\\a) (prin1 #\\Space) (prin1 #\\Newline) (princ #\\!)"))
			.isEqualTo("#\\a#\\Space#\\Newline!");
	}

	@Test
	void compileParseInteger() throws Exception {
		assertThat(compileAndRun("""
				(print (parse-integer "42"))
				(print (parse-integer "  -13  "))
				(print (parse-integer "ff" :radix 16))
				(print (parse-integer "12abc" :junk-allowed t))
				(print (parse-integer "xyz" :junk-allowed t))
				""")).isEqualTo("42\n-13\n255\n12\nnil");
	}

	@Test
	void compileReadFromString() throws Exception {
		assertThat(compileAndRun("(print (read-from-string \"(+ 1 2)\")) (print (read-from-string \"42\"))"))
			.isEqualTo("(+ 1 2)\n42");
	}

	@Test
	void compileParseIntegerAndReadFromStringAsValues() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'parse-integer (list \"1\" \"2\" \"3\")))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (funcall #'read-from-string \"(a b c)\"))")).isEqualTo("(a b c)");
	}

	@Test
	void compileHashTablePutGet() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *h* (make-hash-table :test 'equal))
				(setf (gethash "a" *h*) 1)
				(setf (gethash "b" *h*) 2)
				(print (list (gethash "a" *h*) (gethash "b" *h*) (gethash "c" *h*)))
				""")).isEqualTo("(1 2 nil)");
	}

	@Test
	void compileHashTableGetWithDefault() throws Exception {
		assertThat(compileAndRun("(print (gethash 'x (make-hash-table) 42))")).isEqualTo("42");
	}

	@Test
	void compileHashTableListKeysWithEqual() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *q* (make-hash-table :test 'equal))
				(setf (gethash (list 0 1 2) *q*) 7)
				(print (gethash (list 0 1 2) *q* 0))
				""")).isEqualTo("7");
	}

	@Test
	void compileHashTableIncf() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *h* (make-hash-table :test 'equal))
				(dolist (w (list "a" "b" "a" "a" "b"))
				  (incf (gethash w *h* 0)))
				(print (list (gethash "a" *h*) (gethash "b" *h*)))
				""")).isEqualTo("(3 2)");
	}

	@Test
	void compileHashTableCountRemhashAndPredicate() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *h* (make-hash-table))
				(setf (gethash 1 *h*) 'a)
				(setf (gethash 2 *h*) 'b)
				(remhash 1 *h*)
				(print (list (hash-table-count *h*) (gethash 1 *h*) (gethash 2 *h*)
				             (hash-table-p *h*) (hash-table-p 5) (consp *h*)))
				""")).isEqualTo("(1 nil b t nil nil)");
	}

	@Test
	void compileHashTableMaphashSumsValues() throws Exception {
		assertThat(compileAndRun("""
				(defun sum-values (h)
				  (let ((acc 0))
				    (maphash (lambda (k v) (setq acc (+ acc v))) h)
				    acc))
				(defparameter *h* (make-hash-table :test 'equal))
				(setf (gethash "a" *h*) 10)
				(setf (gethash "b" *h*) 20)
				(setf (gethash "c" *h*) 30)
				(print (sum-values *h*))
				""")).isEqualTo("60");
	}

	@Test
	void compileHashTableFunctionsAsFirstClassValues() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *h* (make-hash-table :test 'equal))
				(setf (gethash "a" *h*) 1)
				(setf (gethash "b" *h*) 2)
				(print (list (funcall #'gethash "a" *h*)
				             (mapcar #'hash-table-p (list *h* 5))
				             (funcall #'hash-table-count *h*)))
				""")).isEqualTo("(1 (t nil) 2)");
	}

	@Test
	void compileMakeHashTableAsFirstClassValue() throws Exception {
		assertThat(compileAndRun("""
				(let ((h (funcall #'make-hash-table)))
				  (setf (gethash 1 h) 'x)
				  (print (gethash 1 h)))
				""")).isEqualTo("x");
	}

	@Test
	void compileMakeArrayVectorRefAndSet() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 5 :initial-element 0))
				(setf (aref *v* 0) 10)
				(setf (aref *v* 4) 40)
				(incf (aref *v* 0) 5)
				(print (list (aref *v* 0) (aref *v* 1) (aref *v* 4)))
				""")).isEqualTo("(15 0 40)");
	}

	@Test
	void compileMakeArrayTwoDimensional() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *m* (make-array (list 2 3) :initial-element 7))
				(setf (aref *m* 0 0) 1)
				(setf (aref *m* 1 2) 99)
				(print (list (aref *m* 0 0) (aref *m* 0 1) (aref *m* 1 2)))
				""")).isEqualTo("(1 7 99)");
	}

	@Test
	void compileMakeArraySingleElementListIsRankOne() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *w* (make-array (list 3) :initial-element 2))
				(setf (aref *w* 1) 8)
				(print (list (aref *w* 0) (aref *w* 1) (aref *w* 2)))
				""")).isEqualTo("(2 8 2)");
	}

	@Test
	void compileArrayCapturedInClosure() throws Exception {
		// Each bump is sequenced through a top-level defparameter so the result does not
		// depend on argument evaluation order within a single call form.
		assertThat(compileAndRun("""
				(defun make-counter (vec)
				  (lambda (i) (setf (aref vec i) (+ 1 (aref vec i))) (aref vec i)))
				(defparameter *c* (make-array 2 :initial-element 0))
				(defparameter *bump* (make-counter *c*))
				(defparameter *a* (funcall *bump* 0))
				(defparameter *b* (funcall *bump* 0))
				(defparameter *d* (funcall *bump* 1))
				(print (list *a* *b* *d*))
				""")).isEqualTo("(1 2 1)");
	}

	@Test
	void compileArrayDefaultInitialElementIsNil() throws Exception {
		assertThat(compileAndRun("(print (aref (make-array 3) 2))")).isEqualTo("nil");
	}

	@Test
	void compileVectorLiteralPrintsAsHashParen() throws Exception {
		assertThat(compileAndRun("(print #(1 2 3))")).isEqualTo("#(1 2 3)");
	}

	@Test
	void compileVectorLiteralIsAReadableArray() throws Exception {
		assertThat(compileAndRun("(print (aref #(10 20 30) 1))")).isEqualTo("20");
	}

	@Test
	void compileVectorLiteralPrin1QuotesStringsPrincDoesNot() throws Exception {
		assertThat(compileAndRun("(prin1 #(a \"b\")) (terpri) (princ #(a \"b\"))")).isEqualTo("#(a \"b\")\n#(a b)");
	}

	@Test
	void compileNestedVectorLiteral() throws Exception {
		assertThat(compileAndRun("(print #(#(1 2) #(3 4)))")).isEqualTo("#(#(1 2) #(3 4))");
	}

	@Test
	void compileMakeArrayResultPrintsAsHashParen() throws Exception {
		assertThat(compileAndRun("(print (make-array 3 :initial-element 7))")).isEqualTo("#(7 7 7)");
	}

	@Test
	void compileTwoDimensionalArrayPrintsAsHash2A() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *m* (make-array (list 2 3) :initial-element 0))
				(setf (aref *m* 0 0) 1)
				(setf (aref *m* 1 2) 9)
				(print *m*)
				""")).isEqualTo("#2A((1 0 0) (0 0 9))");
	}

	@Test
	void compileEmptyVectorLiteral() throws Exception {
		assertThat(compileAndRun("(print #())")).isEqualTo("#()");
	}

	@Test
	void compileLengthOfVectorReturnsElementCount() throws Exception {
		assertThat(compileAndRun("(print (length (make-array 5 :initial-element 0)))")).isEqualTo("5");
		assertThat(compileAndRun("(print (length #(10 20 30)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (length #()))")).isEqualTo("0");
	}

}
