package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import am.ik.rontolisp.SourceProvenance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RontoLispCliTest {

	@TempDir
	Path tempDir;

	private String runCli(String input, String... args) {
		ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RontoLispCli cli = new RontoLispCli(in, new PrintStream(out));
		cli.run(args);
		return out.toString(StandardCharsets.UTF_8);
	}

	@Test
	void replEvaluatesExpression() {
		String output = runCli("(+ 1 2)\n");
		assertThat(output).contains("3");
	}

	@Test
	void replEchoesEveryValueOnItsOwnLine() {
		// As in any CL REPL: (floor 10 3) echoes the quotient AND the remainder.
		assertThat(runCli("(floor 10 3)\n")).contains("3\n1\n");
		assertThat(runCli("(values 1 2 3)\n")).contains("1\n2\n3\n");
		assertThat(runCli("(defun f () (values 1 2))\n(f)\n")).contains("1\n2\n");
		// No values at all echoes nothing, and a single value stays a single line.
		assertThat(runCli("(values)\n")).isEqualTo("> > ");
		assertThat(runCli("(+ 1 2)\n")).isEqualTo("> 3\n> ");
		// Two forms on one line echo twice, as SBCL does reading them one at a time,
		// and each form's own output precedes its own value.
		assertThat(runCli("(values 1 2) (+ 3 4)\n")).isEqualTo("> 1\n2\n7\n> ");
		assertThat(runCli("(print 'a) (print 'b)\n")).isEqualTo("> A\nA\nB\nB\n> ");
	}

	@Test
	void replWithSimdInterceptsVecKernels() {
		// A vec.lisp defun prints as #<lambda>; the installed Vector API kernel prints
		// as #<function vec:dot>. The surefire JVM has jdk.incubator.vector on the
		// module path, so VecSimd.available() is true here.
		String output = runCli("(vec:dot #d(1.0) #d(1.0)) #'vec:dot\n", "--simd");
		assertThat(output).contains("#<function VEC:DOT>");
	}

	@Test
	void replWithoutSimdKeepsScalarVecKernels() {
		String output = runCli("(vec:dot #d(1.0) #d(1.0)) #'vec:dot\n");
		assertThat(output).contains("#<lambda>").doesNotContain("#<function VEC:DOT>");
	}

	@Test
	void interpretFile() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print (+ 1 2))");
		String output = runCli("", file.toString());
		assertThat(output).contains("3");
	}

	@Test
	void compileToClassFile() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print (+ 1 2))");
		Path classFile = tempDir.resolve("Test.class");
		runCli("", file.toString(), "-o", classFile.toString());
		assertThat(Files.exists(classFile)).isTrue();
		byte[] bytes = Files.readAllBytes(classFile);
		// Check class file magic number
		assertThat(bytes[0]).isEqualTo((byte) 0xCA);
		assertThat(bytes[1]).isEqualTo((byte) 0xFE);
		assertThat(bytes[2]).isEqualTo((byte) 0xBA);
		assertThat(bytes[3]).isEqualTo((byte) 0xBE);
	}

	@Test
	void compileToWasmFile() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print (+ 1 2))");
		Path wasmFile = tempDir.resolve("test.wasm");
		runCli("", file.toString(), "-o", wasmFile.toString());
		assertThat(Files.exists(wasmFile)).isTrue();
		byte[] bytes = Files.readAllBytes(wasmFile);
		// Check WASM magic number
		assertThat(bytes[0]).isEqualTo((byte) 0x00);
		assertThat(bytes[1]).isEqualTo((byte) 'a');
		assertThat(bytes[2]).isEqualTo((byte) 's');
		assertThat(bytes[3]).isEqualTo((byte) 'm');
	}

	@Test
	void compileToComponentWithWitWritesTheWitFileNextToTheWasm() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, """
				(defun pure-add (a b) (+ a b))
				(rontolisp:wasm-export 'pure-add :params '(:int :int) :returns :int)
				""");
		Path wasmFile = tempDir.resolve("test.wasm");
		runCli("", file.toString(), "-o", wasmFile.toString(), "--component", "--emit-wit");
		assertThat(Files.exists(wasmFile)).isTrue();
		String wit = Files.readString(tempDir.resolve("test.wit"));
		assertThat(wit).startsWith("package root:component;\n")
			.contains("  export pure-add: func(p0: s32, p1: s32) -> s32;");
	}

	@Test
	void witWithoutComponentIsAClearError() throws Exception {
		Path file = tempDir.resolve("test.lisp");
		Files.writeString(file, "(print 1)");
		Path wasmFile = tempDir.resolve("test.wasm");
		assertThatThrownBy(() -> runCli("", file.toString(), "-o", wasmFile.toString(), "--emit-wit"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--emit-wit requires --component");
		Path classFile = tempDir.resolve("Test.class");
		assertThatThrownBy(() -> runCli("", file.toString(), "-o", classFile.toString(), "--component", "--emit-wit"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--emit-wit requires --component");
	}

	@Test
	void helpOption() {
		String output = runCli("", "-h");
		assertThat(output).contains("Usage:");
		assertThat(output).contains("rontolisp");
	}

	@Test
	void versionOption() {
		String output = runCli("", "-v");
		assertThat(output).contains("\"version\":");
		assertThat(output).contains("\"buildTimestamp\":");
		assertThat(output).contains("\"gitCommit\":");
	}

	@Test
	void interpretFileUsingPackages() throws Exception {
		Path file = tempDir.resolve("pkg.lisp");
		Files.writeString(file, """
				(print *package*)
				(in-package :rontolisp)
				(cl:print (cl:car (version)))
				""");
		String output = runCli("", file.toString());
		assertThat(output).contains("CL-USER").contains(":VERSION");
	}

	// -- frontend source positions (.todo/151 phase 2) ------------------------

	@Test
	void aMacroThatSignalsWhileExpandingNamesItsCallSiteInTheLoadedFile() throws Exception {
		// The error comes out of the macro-time evaluator, whose forms the macro BUILT:
		// nothing in the exception knows a file. The nearest enclosing form that WAS read
		// is the call site, and that is what has to be reported -- in the spliced file's
		// own coordinates, not the flattened entry program's.
		Files.writeString(this.tempDir.resolve("lib.lisp"), """
				(defmacro twice (x)
				  (error "twice: bad argument ~a" x))

				(defun f (n)
				  (twice n))
				""");
		Path main = this.tempDir.resolve("main.lisp");
		Files.writeString(main, "(load \"lib.lisp\")\n(print (f 1))\n");
		assertThatThrownBy(() -> runCli("", main.toString(), "-o", this.tempDir.resolve("Main.class").toString()))
			.isInstanceOf(LispCompileException.class)
			.hasMessageContaining("lib.lisp:5:3: twice: bad argument N")
			.hasCauseInstanceOf(RuntimeException.class);
	}

	@Test
	void aMalformedFormDeepInsideADefunNamesItsOwnLineOnBothCompileBackends() throws Exception {
		// The position must survive the whole pipeline -- inliner, resolver, expander,
		// lambda-list desugaring, the cross-lambda exit lowering -- down to the form that
		// actually fails, on the JVM and the WASM backend alike.
		Path file = this.tempDir.resolve("bad.lisp");
		Files.writeString(file, """
				(defun g (x)
				  (let ((a 1) 2)
				    a))
				(print (g 1))
				""");
		for (String output : new String[] { "Bad.class", "bad.wasm" }) {
			assertThatThrownBy(() -> runCli("", file.toString(), "-o", this.tempDir.resolve(output).toString()))
				.isInstanceOf(LispCompileException.class)
				.hasMessageContaining("bad.lisp:2:3:");
		}
	}

	@Test
	void aCallToAnUndefinedFunctionWarnsAtItsCallSite() throws Exception {
		Files.writeString(this.tempDir.resolve("lib.lisp"), """
				(defun broken (y)
				  (no-such-function y))
				""");
		Path main = this.tempDir.resolve("main.lisp");
		Files.writeString(main, "(load \"lib.lisp\")\n(print (broken 1))\n");
		PrintStream savedErr = System.err;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try {
			System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
			runCli("", main.toString(), "-o", this.tempDir.resolve("Main.class").toString());
		}
		finally {
			System.setErr(savedErr);
		}
		assertThat(captured.toString(StandardCharsets.UTF_8))
			.contains("lib.lisp:2:3: warning: the function NO-SUCH-FUNCTION is undefined");
	}

	@Test
	void theRecordingScopeIsClosedEvenWhenTheCompileFails() throws Exception {
		// The table holds the whole program's conses alive; a scope left open on a failed
		// compile would keep them for the life of the thread.
		Path file = this.tempDir.resolve("bad.lisp");
		Files.writeString(file, "(defun g (x) (let ((a 1) 2) a))\n");
		assertThatThrownBy(() -> runCli("", file.toString(), "-o", this.tempDir.resolve("Bad.class").toString()))
			.isInstanceOf(LispCompileException.class);
		assertThat(SourceProvenance.isRecording()).isFalse();
	}

	@Test
	void theInterpreterKeepsItsBareErrorText() throws Exception {
		// The deliberate divergence: the interpreter reaches the same expander at
		// EVALUATION time, so a position prefix there would land on ordinary runtime
		// error text (which ci-spec.yaml and the doc examples pin byte for byte). It
		// records nothing, and its message stays exactly as it was.
		Files.writeString(this.tempDir.resolve("lib.lisp"), """
				(defmacro twice (x)
				  (error "twice: bad argument ~a" x))

				(defun f (n)
				  (twice n))
				""");
		Path main = this.tempDir.resolve("main.lisp");
		Files.writeString(main, "(load \"lib.lisp\")\n(print (f 1))\n");
		assertThatThrownBy(() -> runCli("", main.toString())).hasMessage("twice: bad argument N");
		assertThat(SourceProvenance.isRecording()).isFalse();
	}

}
