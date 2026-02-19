package am.ik.femtolisp;

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

import am.ik.femtolisp.cli.FemtoLispCli;
import am.ik.femtolisp.codegen.jvm.JvmLispCompiler;
import am.ik.femtolisp.eval.LispEvaluator;
import am.ik.femtolisp.reader.LispReader;
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
		new FemtoLispCli(in, new PrintStream(out)).run(new String[0]);
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

	}

	// == Built-in function examples (Language Reference > Built-in Functions table) ==

	@Nested
	class BuiltInFunctionExamples {

		@Test
		void add() {
			assertThat(eval("(+ 1 2 3)")).isEqualTo(new LispInteger(6));
		}

		@Test
		void subtract() {
			assertThat(eval("(- 10 3)")).isEqualTo(new LispInteger(7));
		}

		@Test
		void multiply() {
			assertThat(eval("(* 3 4)")).isEqualTo(new LispInteger(12));
		}

		@Test
		void divide() {
			assertThat(eval("(/ 10 3)")).isEqualTo(new LispInteger(3));
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
		void nullPredicate() {
			assertThat(eval("(null nil)")).isSameAs(LispTrue.INSTANCE);
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

	}

}
