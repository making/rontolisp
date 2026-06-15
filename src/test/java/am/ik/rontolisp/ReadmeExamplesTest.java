package am.ik.rontolisp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that verify all code examples shown in the README.
 */
class ReadmeExamplesTest {

	// -- Helpers for evaluating Lisp expressions --

	private LispVal eval(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		return evaluator.eval(LispReader.readFromString(input));
	}

	private LispVal evalAll(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	private String evalAndCaptureOutput(String input) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out));
		for (LispVal expr : LispReader.readAllFromString(input)) {
			evaluator.eval(expr);
		}
		return out.toString(StandardCharsets.UTF_8).trim();
	}

	private String runRepl(String input) {
		ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new RontoLispCli(in, new PrintStream(out)).run(new String[0]);
		return out.toString(StandardCharsets.UTF_8);
	}

	// == REPL examples (Usage > REPL section) ==

	@Nested
	class ReplExamples {

		@Test
		void addition() {
			String output = runRepl("(+ 1 2)\n(quit)\n");
			assertThat(output).contains("3");
		}

		@Test
		void nestedArithmetic() {
			String output = runRepl("(* 3 (+ 4 5))\n(quit)\n");
			assertThat(output).contains("27");
		}

		@Test
		void factorialDefinitionAndCall() {
			String output = runRepl("(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))\n(fact 10)\n(quit)\n");
			assertThat(output).contains("fact");
			assertThat(output).contains("3628800");
		}

		@Test
		void letBinding() {
			String output = runRepl("(let ((x 10) (y 20)) (+ x y))\n(quit)\n");
			assertThat(output).contains("30");
		}

	}

	// == File interpretation example (Usage > File Interpretation section) ==

	@Nested
	class FileInterpretationExamples {

		@Test
		void squareProgram() {
			String output = evalAndCaptureOutput("""
					(defun square (x) (* x x))
					(print (square 5))
					(print (square 12))
					""");
			assertThat(output).isEqualTo("25\n144");
		}

	}

	// == JVM compilation example (Usage > Compile to JVM Bytecode section) ==

	@Nested
	class JvmCompilationExamples {

		@TempDir
		Path tempDir;

		private String compileAndRun(String lispCode) throws Exception {
			List<LispVal> program = LispReader.readAllFromString(lispCode);
			byte[] classBytes = new JvmLispCompiler("Test").compile(program);
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
		void helloLisp() throws Exception {
			assertThat(compileAndRun("(print (+ 1 2))")).isEqualTo("3");
		}

		private String compileAndRunWithStdin(String lispCode, String stdin) throws Exception {
			java.io.InputStream oldIn = System.in;
			System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
			try {
				return compileAndRun(lispCode);
			}
			finally {
				System.setIn(oldIn);
			}
		}

		// "Self-Hosted REPL" section: repl.lisp compiled to a standalone class
		@Test
		void selfHostedRepl() throws Exception {
			String repl = """
					(princ "> ")
					(setq form (read))
					(while form
					  (print (eval form))
					  (princ "> ")
					  (setq form (read)))
					""";
			String session = """
					(defun square (x) (* x x))
					(mapcar #'square '(1 2 3))
					(- 5)
					""";
			assertThat(compileAndRunWithStdin(repl, session)).isEqualTo("""
					> square
					> (1 4 9)
					> -5
					>""");
		}

	}

	// == Data type examples (Language Reference > Data Types section) ==

	@Nested
	class DataTypeExamples {

		@Test
		void groupedInteger() {
			assertThat(eval("1,000")).isEqualTo(new LispInteger(1000));
			assertThat(eval("(+ 1,000 100)")).isEqualTo(new LispInteger(1100));
		}

		@Test
		void groupedDouble() {
			assertThat(eval("3,000.50")).isEqualTo(new LispDouble(3000.5));
		}

		@Test
		void piConstant() {
			assertThat(eval("pi")).isEqualTo(new LispDouble(Math.PI));
		}

	}

	// == Built-in function examples (Language Reference > Built-in Functions table) ==

	@Nested
	class BuiltInFunctionExamples {

		@Test
		void withOpenFileWriteThenRead(@TempDir Path tempDir) {
			String file = tempDir.resolve("greeting.txt").toString().replace("\\", "\\\\");
			String output = evalAndCaptureOutput("""
					(with-open-file (out "%s" :direction :output)
					  (write-line "hello" out)
					  (write-line "world" out))

					(with-open-file (in "%s")
					  (print (read-line in)) ; => "hello"
					  (print (read-line in)) ; => "world"
					  (print (read-line in))) ; => nil (EOF)
					""".formatted(file, file));
			assertThat(output.lines().toList()).containsExactly("\"hello\"", "\"world\"", "nil");
		}

		@Test
		void add() {
			assertThat(eval("(+ 1 2 3)")).isEqualTo(new LispInteger(6));
			assertThat(eval("(+ 1.5 2.5)")).isEqualTo(new LispDouble(4.0));
		}

		@Test
		void subtract() {
			assertThat(eval("(- 10 3)")).isEqualTo(new LispInteger(7));
			assertThat(eval("(- 3.5 1.5)")).isEqualTo(new LispDouble(2.0));
		}

		@Test
		void multiply() {
			assertThat(eval("(* 3 4)")).isEqualTo(new LispInteger(12));
			assertThat(eval("(* 2.0 3.0)")).isEqualTo(new LispDouble(6.0));
		}

		@Test
		void divide() {
			assertThat(eval("(/ 10 3)"))
				.isEqualTo(new LispRatio(java.math.BigInteger.TEN, java.math.BigInteger.valueOf(3)));
			assertThat(eval("(/ 10 2)")).isEqualTo(new LispInteger(5));
			assertThat(eval("(/ 7.0 2.0)")).isEqualTo(new LispDouble(3.5));
		}

		@Test
		void ratios() {
			assertThat(eval("1/3").print()).isEqualTo("1/3");
			assertThat(eval("(/ 1 2)").print()).isEqualTo("1/2");
			assertThat(eval("(+ 1/2 1/3)").print()).isEqualTo("5/6");
			assertThat(eval("(/ 1 2.0)")).isEqualTo(new LispDouble(0.5));
			assertThat(eval("(float 1/2)")).isEqualTo(new LispDouble(0.5));
			assertThat(eval("(* 2/3 3)")).isEqualTo(new LispInteger(2));
			assertThat(eval("(numerator 3/4)")).isEqualTo(new LispInteger(3));
			assertThat(eval("(denominator 3/4)")).isEqualTo(new LispInteger(4));
			assertThat(eval("(rationalp 1/2)")).isEqualTo(LispTrue.INSTANCE);
			assertThat(eval("(expt 2 -1)").print()).isEqualTo("1/2");
			assertThat(eval("(/ 2)").print()).isEqualTo("1/2");
		}

		@Test
		void mod() {
			assertThat(eval("(mod 10 3)")).isEqualTo(new LispInteger(1));
		}

		@Test
		void eq() {
			assertThat(eval("(= 1 1)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void lessThan() {
			assertThat(eval("(< 1 2)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void greaterThan() {
			assertThat(eval("(> 2 1)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void lessOrEqual() {
			assertThat(eval("(<= 1 1)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void greaterOrEqual() {
			assertThat(eval("(>= 2 1)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void print() {
			String output = evalAndCaptureOutput("(print 42)");
			assertThat(output).isEqualTo("42");
		}

		@Test
		void prin1() {
			String output = evalAndCaptureOutput("(prin1 42)");
			assertThat(output).isEqualTo("42");
		}

		@Test
		void princ() {
			String output = evalAndCaptureOutput("(princ \"hello\")");
			assertThat(output).isEqualTo("hello");
		}

		@Test
		void terpri() {
			String output = evalAndCaptureOutput("(terpri)");
			assertThat(output).isEmpty();
		}

		@Test
		void princToString() {
			assertThat(eval("(princ-to-string '(1 \"x\"))")).isEqualTo(new LispString("(1 x)"));
		}

		@Test
		void prin1ToString() {
			assertThat(eval("(prin1-to-string \"abc\")")).isEqualTo(new LispString("\"abc\""));
		}

		@Test
		void concatenate() {
			assertThat(eval("(concatenate 'string \"foo\" \"bar\")")).isEqualTo(new LispString("foobar"));
		}

		@Test
		void stringUpcase() {
			assertThat(eval("(string-upcase \"abc\")")).isEqualTo(new LispString("ABC"));
		}

		@Test
		void stringDowncase() {
			assertThat(eval("(string-downcase \"ABC\")")).isEqualTo(new LispString("abc"));
		}

		@Test
		void stringCapitalize() {
			assertThat(eval("(string-capitalize \"hello world\")")).isEqualTo(new LispString("Hello World"));
		}

		@Test
		void subseq() {
			assertThat(eval("(subseq \"hello\" 1 3)")).isEqualTo(new LispString("el"));
			assertThat(eval("(subseq '(1 2 3 4) 1 3)").print()).isEqualTo("(2 3)");
		}

		@Test
		void stringEq() {
			assertThat(eval("(string= \"abc\" \"abc\")")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void stringEqual() {
			assertThat(eval("(string-equal \"ABC\" \"abc\")")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void stringTrim() {
			assertThat(eval("(string-trim \" \" \"  hi  \")")).isEqualTo(new LispString("hi"));
		}

		@Test
		void stringLeftTrim() {
			assertThat(eval("(string-left-trim \"x\" \"xxhi\")")).isEqualTo(new LispString("hi"));
		}

		@Test
		void stringRightTrim() {
			assertThat(eval("(string-right-trim \"x\" \"hixx\")")).isEqualTo(new LispString("hi"));
		}

		@Test
		void nullPredicate() {
			assertThat(eval("(null nil)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void notPredicate() {
			assertThat(eval("(not nil)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void cons() {
			assertThat(eval("(car (cons 1 2))")).isEqualTo(new LispInteger(1));
			assertThat(eval("(cdr (cons 1 2))")).isEqualTo(new LispInteger(2));
		}

		@Test
		void list() {
			assertThat(eval("(car (list 1 2 3))")).isEqualTo(new LispInteger(1));
		}

		@Test
		void lengthFn() {
			assertThat(eval("(length '(1 2 3))")).isEqualTo(new LispInteger(3));
			assertThat(eval("(length nil)")).isEqualTo(new LispInteger(0));
		}

		@Test
		void reverseFn() {
			assertThat(eval("(reverse '(1 2 3))").print()).isEqualTo("(3 2 1)");
		}

		@Test
		void memberFn() {
			assertThat(eval("(member 2 '(1 2 3))").print()).isEqualTo("(2 3)");
			assertThat(eval("(member 9 '(1 2 3))")).isSameAs(LispNil.INSTANCE);
		}

		@Test
		void assocFn() {
			assertThat(eval("(assoc 'b '((a 1) (b 2)))").print()).isEqualTo("(b 2)");
			assertThat(eval("(assoc 'z '((a 1)))")).isSameAs(LispNil.INSTANCE);
		}

		@Test
		void lastFn() {
			assertThat(eval("(last '(1 2 3))").print()).isEqualTo("(3)");
			assertThat(eval("(last nil)")).isSameAs(LispNil.INSTANCE);
		}

		@Test
		void memberIfFn() {
			assertThat(eval("(member-if #'oddp '(2 4 5 6))").print()).isEqualTo("(5 6)");
		}

		@Test
		void assocIfFn() {
			assertThat(eval("(assoc-if #'oddp '((2 a) (3 b)))").print()).isEqualTo("(3 b)");
		}

		@Test
		void getfFn() {
			assertThat(eval("(getf '(:a 1 :b 2) :b)").print()).isEqualTo("2");
		}

		@Test
		void butlastFn() {
			assertThat(eval("(butlast '(1 2 3))").print()).isEqualTo("(1 2)");
		}

		@Test
		void removeDuplicatesFn() {
			assertThat(eval("(remove-duplicates '(1 2 1 3))").print()).isEqualTo("(2 1 3)");
		}

		@Test
		void nconcFn() {
			assertThat(eval("(nconc (list 1 2) (list 3 4))").print()).isEqualTo("(1 2 3 4)");
		}

		@Test
		void identityFn() {
			assertThat(eval("(identity 42)").print()).isEqualTo("42");
		}

		@Test
		void copyListFn() {
			assertThat(eval("(copy-list '(1 2 3))").print()).isEqualTo("(1 2 3)");
		}

		@Test
		void nreverseFn() {
			assertThat(eval("(nreverse '(1 2 3))").print()).isEqualTo("(3 2 1)");
		}

		@Test
		void makeListFn() {
			assertThat(eval("(make-list 3)").print()).isEqualTo("(nil nil nil)");
		}

		@Test
		void unionFn() {
			assertThat(eval("(union '(1 2 3) '(2 3 4))").print()).isEqualTo("(4 1 2 3)");
		}

		@Test
		void intersectionFn() {
			assertThat(eval("(intersection '(1 2 3) '(2 3 4))").print()).isEqualTo("(3 2)");
		}

		@Test
		void setDifferenceFn() {
			assertThat(eval("(set-difference '(1 2 3) '(2))").print()).isEqualTo("(3 1)");
		}

		@Test
		void adjoinFn() {
			assertThat(eval("(adjoin 1 '(2 3))").print()).isEqualTo("(1 2 3)");
		}

		@Test
		void funcall() {
			assertThat(evalAll("(defun square (x) (* x x)) (funcall #'square 5)")).isEqualTo(new LispInteger(25));
		}

		@Test
		void atom() {
			assertThat(eval("(atom 1)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void numberp() {
			assertThat(eval("(numberp 42)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void integerp() {
			assertThat(eval("(integerp 42)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void floatp() {
			assertThat(eval("(floatp 3.14)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void symbolp() {
			assertThat(eval("(symbolp 'foo)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void stringp() {
			assertThat(eval("(stringp \"hello\")")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void listp() {
			assertThat(eval("(listp '(1 2))")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void consp() {
			assertThat(eval("(consp '(1 2))")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void keywordp() {
			assertThat(eval("(keywordp :foo)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void carCdrComposition() {
			assertThat(eval("(cadr '(1 2 3))")).isEqualTo(new LispInteger(2));
		}

		@Test
		void eqGeneral() {
			assertThat(eval("(eq 'foo 'foo)")).isSameAs(LispTrue.INSTANCE);
			assertThat(eval("(eq 1.5 1.5)")).isSameAs(LispNil.INSTANCE);
		}

		@Test
		void eql() {
			assertThat(eval("(eql 1.5 1.5)")).isSameAs(LispTrue.INSTANCE);
			assertThat(eval("(eql 3 3.0)")).isSameAs(LispNil.INSTANCE);
		}

		@Test
		void rplaca() {
			assertThat(evalAll("(setq x (cons 1 2)) (car (rplaca x 10))")).isEqualTo(new LispInteger(10));
		}

		@Test
		void rplacd() {
			assertThat(evalAll("(setq x (cons 1 2)) (cdr (rplacd x 20))")).isEqualTo(new LispInteger(20));
		}

		@Test
		void abs() {
			assertThat(eval("(abs -5)")).isEqualTo(new LispInteger(5));
			assertThat(eval("(abs -3.14)")).isEqualTo(new LispDouble(3.14));
		}

		@Test
		void min() {
			assertThat(eval("(min 3 5)")).isEqualTo(new LispInteger(3));
			assertThat(eval("(min 1.5 2.5)")).isEqualTo(new LispDouble(1.5));
		}

		@Test
		void max() {
			assertThat(eval("(max 3 5)")).isEqualTo(new LispInteger(5));
			assertThat(eval("(max 1.5 2.5)")).isEqualTo(new LispDouble(2.5));
		}

		@Test
		void floatConversion() {
			assertThat(eval("(float 42)")).isEqualTo(new LispDouble(42.0));
		}

		@Test
		void truncate() {
			assertThat(eval("(truncate 3.7)")).isEqualTo(new LispInteger(3));
			assertThat(eval("(truncate -3.7)")).isEqualTo(new LispInteger(-3));
		}

		@Test
		void floor() {
			assertThat(eval("(floor 3.7)")).isEqualTo(new LispInteger(3));
			assertThat(eval("(floor -3.7)")).isEqualTo(new LispInteger(-4));
		}

		@Test
		void ceiling() {
			assertThat(eval("(ceiling 3.2)")).isEqualTo(new LispInteger(4));
			assertThat(eval("(ceiling -3.2)")).isEqualTo(new LispInteger(-3));
		}

		@Test
		void round() {
			assertThat(eval("(round 3.5)")).isEqualTo(new LispInteger(4));
			assertThat(eval("(round 2.5)")).isEqualTo(new LispInteger(2));
		}

		@Test
		void sqrt() {
			assertThat(eval("(sqrt 16)")).isEqualTo(new LispDouble(4.0));
			assertThat(eval("(sqrt 2)")).isEqualTo(new LispDouble(1.4142135623730951));
		}

		@Test
		void isqrt() {
			assertThat(eval("(isqrt 17)")).isEqualTo(new LispInteger(4));
		}

		@Test
		void expt() {
			assertThat(eval("(expt 2 10)")).isEqualTo(new LispInteger(1024));
			assertThat(eval("(expt 2.0 3)")).isEqualTo(new LispDouble(8.0));
		}

		@Test
		void exp() {
			// Math.exp is accurate to within 1 ulp and is platform-dependent in its last
			// digit, so use exp(0), whose result (1.0) is exact on every backend.
			assertThat(eval("(exp 0)")).isEqualTo(new LispDouble(1.0));
		}

		@Test
		void log() {
			assertThat(eval("(log 1)")).isEqualTo(new LispDouble(0.0));
		}

		@Test
		void trigonometric() {
			assertThat(eval("(sin 0)")).isEqualTo(new LispDouble(0.0));
			assertThat(eval("(cos 0)")).isEqualTo(new LispDouble(1.0));
			assertThat(eval("(atan 0)")).isEqualTo(new LispDouble(0.0));
			assertThat(eval("(tanh 0)")).isEqualTo(new LispDouble(0.0));
		}

		@Test
		void gcd() {
			assertThat(eval("(gcd 12 18)")).isEqualTo(new LispInteger(6));
		}

		@Test
		void lcm() {
			assertThat(eval("(lcm 4 6)")).isEqualTo(new LispInteger(12));
		}

		@Test
		void signum() {
			assertThat(eval("(signum -5)")).isEqualTo(new LispInteger(-1));
			assertThat(eval("(signum 3.5)")).isEqualTo(new LispDouble(1.0));
		}

		@Test
		void evalExpression() {
			assertThat(eval("(eval '(+ 1 2))")).isEqualTo(new LispInteger(3));
		}

	}

	// == First-class function examples (First-Class Functions section) ==

	@Nested
	class FirstClassFunctionExamples {

		@Test
		void higherOrderFunction() {
			String output = evalAndCaptureOutput("""
					(defun apply-twice (f x) (funcall f (funcall f x)))
					(defun square (x) (* x x))
					(print (apply-twice #'square 3))
					""");
			assertThat(output).isEqualTo("81");
		}

		@Test
		void closureCaptureByReference() {
			assertThat(evalAll("""
					(defun make-counter ()
					  (let ((n 0))
					    (lambda ()
					      (setq n (+ n 1))
					      n)))
					(setq c (make-counter))
					(funcall c) (funcall c) (funcall c)
					""")).isEqualTo(new LispInteger(3));
		}

		@Test
		void lambdaAsArgument() {
			String output = evalAndCaptureOutput("""
					(defun apply-twice (f x) (funcall f (funcall f x)))
					(print (apply-twice (lambda (x) (+ x 10)) 5))
					""");
			assertThat(output).isEqualTo("25");
		}

		@Test
		void builtinOperatorsAsFirstClassValues() {
			String output = evalAndCaptureOutput("""
					(print (reduce #'+ 0 '(1 2 3 4 5)))
					(print (reduce #'* 1 '(1 2 3 4 5)))
					(print (mapcar #'car '((1 2) (3 4) (5 6))))
					(print (mapcar #'1+ '(1 2 3)))
					(print (funcall #'+ 3 4))
					(setq my-op #'+)
					(print (funcall my-op 10 20))
					(print (funcall (symbol-function 'car) '(9 8)))
					""");
			assertThat(output).isEqualTo("15\n120\n(1 3 5)\n(2 3 4)\n7\n30\n9");
		}

	}

	// == Function namespace examples (Function Namespace section) ==

	@Nested
	class FunctionNamespaceExamples {

		@Test
		void bareSymbolIsAVariableReference() {
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> eval("car"))
				.hasMessageContaining("The variable car is unbound");
		}

		@Test
		void variableDoesNotShadowFunctionInCallPosition() {
			assertThat(eval("(let ((car 5)) (car (list car 2)))")).isEqualTo(new LispInteger(5));
		}

		@Test
		void symbolAsFunctionDesignator() {
			assertThat(eval("(funcall 'car '(1 2))")).isEqualTo(new LispInteger(1));
		}

		@Test
		void defunReturnsTheFunctionName() {
			assertThat(eval("(defun square (x) (* x x))")).isEqualTo(new LispSymbol("square"));
		}

		@Test
		void setqLambdaBindsAVariable() {
			assertThat(evalAll("(setq f (lambda (x) (* x x))) (funcall f 5)")).isEqualTo(new LispInteger(25));
		}

		@Test
		void sharpQuoteOfSpecialOperatorIsAnError() {
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> eval("#'if"))
				.hasMessageContaining("is a macro or special operator, not a function");
		}

	}

	// == Macro examples (Language Reference > Macros table) ==

	@Nested
	class MacroExamples {

		@Test
		void cond() {
			assertThat(evalAll("(cond (nil 1) (t 2))")).isEqualTo(new LispInteger(2));
		}

		@Test
		void and() {
			assertThat(eval("(and)")).isSameAs(LispTrue.INSTANCE);
			assertThat(eval("(and 1 2 3)")).isEqualTo(new LispInteger(3));
			assertThat(eval("(and 1 nil 3)")).isSameAs(LispNil.INSTANCE);
		}

		@Test
		void or() {
			assertThat(eval("(or)")).isSameAs(LispNil.INSTANCE);
			assertThat(eval("(or nil nil 3)")).isEqualTo(new LispInteger(3));
			assertThat(eval("(or nil nil nil)")).isSameAs(LispNil.INSTANCE);
		}

		@Test
		void when() {
			assertThat(eval("(when t 42)")).isEqualTo(new LispInteger(42));
			assertThat(eval("(when nil 42)")).isSameAs(LispNil.INSTANCE);
			assertThat(eval("(when t 1 2 3)")).isEqualTo(new LispInteger(3));
		}

		@Test
		void unless() {
			assertThat(eval("(unless nil 42)")).isEqualTo(new LispInteger(42));
			assertThat(eval("(unless t 42)")).isSameAs(LispNil.INSTANCE);
		}

		@Test
		void dotimes() {
			assertThat(evalAll("(let ((s 0)) (dotimes (i 5) (setq s (+ s i))) s)")).isEqualTo(new LispInteger(10));
			assertThat(eval("(dotimes (i 3))")).isSameAs(LispNil.INSTANCE);
			assertThat(evalAll("(let ((acc 1)) (dotimes (i 4 acc) (setq acc (* acc 2))))"))
				.isEqualTo(new LispInteger(16));
		}

		@Test
		void prog1() {
			assertThat(eval("(prog1 1 2 3)")).isEqualTo(new LispInteger(1));
			assertThat(eval("(prog1 99)")).isEqualTo(new LispInteger(99));
			assertThat(evalAll("(let ((x (list 1 2 3))) (prog1 (car x) (setq x (cdr x))))"))
				.isEqualTo(new LispInteger(1));
		}

		@Test
		void onePlus() {
			assertThat(eval("(1+ 5)")).isEqualTo(new LispInteger(6));
		}

		@Test
		void oneMinus() {
			assertThat(eval("(1- 5)")).isEqualTo(new LispInteger(4));
		}

		@Test
		void zerop() {
			assertThat(eval("(zerop 0)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void plusp() {
			assertThat(eval("(plusp 1)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void minusp() {
			assertThat(eval("(minusp -1)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void evenp() {
			assertThat(eval("(evenp 4)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void oddp() {
			assertThat(eval("(oddp 3)")).isSameAs(LispTrue.INSTANCE);
		}

		@Test
		void rest() {
			assertThat(eval("(rest '(1 2 3))").print()).isEqualTo("(2 3)");
		}

		@Test
		void second() {
			assertThat(eval("(second '(1 2 3))")).isEqualTo(new LispInteger(2));
		}

		@Test
		void third() {
			assertThat(eval("(third '(1 2 3))")).isEqualTo(new LispInteger(3));
		}

		@Test
		void fourth() {
			assertThat(eval("(fourth '(1 2 3 4))")).isEqualTo(new LispInteger(4));
		}

		@Test
		void setfCar() {
			assertThat(evalAll("(setq x (list 1 2 3)) (setf (car x) 10) (car x)")).isEqualTo(new LispInteger(10));
		}

		@Test
		void setfNth() {
			assertThat(evalAll("(setq x (list 1 2 3)) (setf (nth 1 x) 20) (nth 1 x)")).isEqualTo(new LispInteger(20));
		}

		@Test
		void push() {
			assertThat(evalAll("(setq x (list 2 3)) (push 1 x) x").print()).isEqualTo("(1 2 3)");
		}

		@Test
		void pop() {
			assertThat(evalAll("(setq x (list 1 2 3)) (pop x)")).isEqualTo(new LispInteger(1));
		}

		@Test
		void remf() {
			assertThat(evalAll("(setq plist (list 'a 1 'b 2 'c 3)) (remf plist 'b) plist").print())
				.isEqualTo("(a 1 c 3)");
		}

		@Test
		void letStar() {
			assertThat(eval("(let* ((x 1) (y x)) (+ x y))")).isEqualTo(new LispInteger(2));
		}

		@Test
		void dolist() {
			assertThat(evalAll("(setq s 0) (dolist (e '(1 2 3)) (setq s (+ s e))) s")).isEqualTo(new LispInteger(6));
			assertThat(eval("(dolist (e '(1 2) 99))")).isEqualTo(new LispInteger(99));
		}

		@Test
		void incf() {
			assertThat(evalAll("(setq n 10) (incf n)")).isEqualTo(new LispInteger(11));
			assertThat(evalAll("(setq n 10) (incf n 5)")).isEqualTo(new LispInteger(15));
		}

		@Test
		void decf() {
			assertThat(evalAll("(setq n 10) (decf n)")).isEqualTo(new LispInteger(9));
			assertThat(evalAll("(setq n 10) (decf n 4)")).isEqualTo(new LispInteger(6));
		}

		@Test
		void format() {
			assertThat(evalAndCaptureOutput("(format t \"Hello ~a, you are ~d years old.~%\" 'world 42)"))
				.isEqualTo("Hello world, you are 42 years old.");
			assertThat(evalAndCaptureOutput("(format t \"~s and ~a~%\" \"str\" \"str\")")).isEqualTo("\"str\" and str");
			assertThat(eval("(format nil \"list=~a\" (list 1 2 3))")).isEqualTo(new LispString("list=(1 2 3)"));
			assertThat(evalAndCaptureOutput("(princ (format nil \"Hello ~a!\" 'world))")).isEqualTo("Hello world!");
		}

	}

	// == Special form examples (Language Reference > Special Forms table) ==

	@Nested
	class SpecialFormExamples {

		@Test
		void quote() {
			LispVal result = eval("(quote (1 2 3))");
			assertThat(result.print()).isEqualTo("(1 2 3)");
		}

		@Test
		void quoteSugar() {
			LispVal result = eval("'(1 2 3)");
			assertThat(result.print()).isEqualTo("(1 2 3)");
		}

		@Test
		void ifForm() {
			assertThat(eval("(if t 1 2)")).isEqualTo(new LispInteger(1));
			assertThat(eval("(if nil 1 2)")).isEqualTo(new LispInteger(2));
		}

		@Test
		void letForm() {
			assertThat(eval("(let ((x 1) (y 2)) (+ x y))")).isEqualTo(new LispInteger(3));
		}

		@Test
		void defunForm() {
			assertThat(evalAll("(defun square (x) (* x x)) (square 5)")).isEqualTo(new LispInteger(25));
		}

		@Test
		void lambdaForm() {
			assertThat(eval("((lambda (x) (* x x)) 5)")).isEqualTo(new LispInteger(25));
		}

		@Test
		void prognForm() {
			assertThat(eval("(progn 1 2 3)")).isEqualTo(new LispInteger(3));
		}

		@Test
		void setqForm() {
			assertThat(eval("(progn (setq x 10) x)")).isEqualTo(new LispInteger(10));
		}

		@Test
		void whileForm() {
			assertThat(evalAll("(let ((n 0) (s 0)) (while (< n 5) (setq s (+ s n)) (setq n (+ n 1))) s)"))
				.isEqualTo(new LispInteger(10));
		}

	}

	// == Packages examples (Language Reference > Packages section) ==

	@Nested
	class PackageExamples {

		@Test
		void packageVarDefaultsToClUser() {
			assertThat(evalAndCaptureOutput("(print *package*)")).isEqualTo("cl-user");
		}

		@Test
		void rontolispVersionReturnsPlist() {
			assertThat(evalAndCaptureOutput("(print (rontolisp:version))")).startsWith("(:version ");
		}

		@Test
		void inPackageRontolisp() {
			String output = evalAndCaptureOutput("""
					(in-package rontolisp)
					(cl:print (version))
					(cl:print (cl:car '(1 2)))
					""");
			assertThat(output).contains(":version").endsWith("1");
		}

		@Test
		void packageIntrospection() {
			String output = evalAndCaptureOutput("""
					(print (rontolisp:list-macros))
					(print (rontolisp:list-special-forms))
					(print (length (rontolisp:list-functions)))
					(defun square (x) (* x x))
					(print (rontolisp:list-functions :cl-user))
					(print (rontolisp:list-functions :rontolisp))
					""");
			assertThat(output.lines().toList()).containsExactly(
					"(and case cond decf do dolist dotimes format incf let* or pop prog1 prog2 psetq push remf setf typecase unless when with-open-file)",
					"(defun defvar function if in-package lambda let progn quote return setq while)", "152", "(square)",
					"(list-functions list-macros list-special-forms version)");
		}

	}

}
