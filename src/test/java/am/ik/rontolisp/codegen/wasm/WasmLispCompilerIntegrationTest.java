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

/**
 * Integration tests that compile Lisp to WASM and run it with wasmtime inside a
 * container.
 */
@Testcontainers(disabledWithoutDocker = true)
class WasmLispCompilerIntegrationTest {

	@Container
	static GenericContainer<?> wasmtime = new GenericContainer<>(
			new ImageFromDockerfile().withDockerfileFromBuilder(builder -> builder.from("debian:bookworm-slim")
				.run("apt-get update && apt-get install -y --no-install-recommends curl ca-certificates xz-utils"
						+ " && curl https://wasmtime.dev/install.sh -sSf | bash"
						+ " && ln -s /root/.wasmtime/bin/wasmtime /usr/local/bin/wasmtime"
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
	void multiplication() throws Exception {
		assertThat(compileAndRun("(print (* 3 4))")).isEqualTo("12");
	}

	@Test
	void division() throws Exception {
		assertThat(compileAndRun("(print (/ 10 3))")).isEqualTo("3");
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
	void lambdaImmediateCall() throws Exception {
		assertThat(compileAndRun("(print ((lambda (x) (* x x)) 5))")).isEqualTo("25");
	}

	@Test
	void printString() throws Exception {
		assertThat(compileAndRun("(print \"hello\")")).isEqualTo("\"hello\"");
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

}
