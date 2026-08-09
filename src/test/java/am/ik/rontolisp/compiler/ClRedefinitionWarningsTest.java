package am.ik.rontolisp.compiler;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.function.Consumer;

import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cross-backend pin for {@code (defun <cl-function> ...)}: the interpreter calls the
 * definition, the compile backends emit the standard operator, and -- the point of
 * {@link ClRedefinitionWarnings} -- they now SAY so. CL leaves the consequences undefined
 * (CLHS 11.1.2.1.2), so the defect being closed here is the silence, not the divergence.
 */
class ClRedefinitionWarningsTest {

	private static final String REDEFINES_RANDOM = """
			(defun random (&rest args) (declare (ignore args)) 42)
			(print (random 1000))
			(print (random 1000))
			""";

	private static List<LispVal> read(String source) {
		return LispReader.readAllFromString(source);
	}

	// The compile-time warnings of one compile, which both backends write to stderr
	// through CompileWarnings.
	private static String compileCapturingErr(Consumer<List<LispVal>> compile, String source) {
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(err));
		try {
			compile.accept(read(source));
		}
		finally {
			System.setErr(oldErr);
		}
		return err.toString();
	}

	@Test
	void theInterpreterCallsTheDefinitionAndTheCompileBackendsSayTheyDoNot() {
		// The interpreter resolves the call through the function cell, so 42 wins there
		// -- that half is unchanged, and is what makes the silence on the other two a
		// defect worth a diagnostic.
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal last = null;
		for (LispVal expr : read(REDEFINES_RANDOM)) {
			last = evaluator.eval(expr);
		}
		assertThat(last).isEqualTo(new LispInteger(42));

		// Both compile backends name the operator, the file position of the FIRST call
		// site, and the way out -- once, however many call sites there are.
		String jvm = compileCapturingErr(program -> new JvmLispCompiler("Test").compile(program), REDEFINES_RANDOM);
		String wasm = compileCapturingErr(program -> new WasmLispCompiler().compile(program), REDEFINES_RANDOM);
		for (String warnings : List.of(jvm, wasm)) {
			assertThat(warnings).contains("redefines the COMMON-LISP function RANDOM")
				.contains("#'RANDOM function value still names it")
				.contains("CLHS 11.1.2.1.2");
			assertThat(warnings.lines().filter(line -> line.contains("redefines the COMMON-LISP")).count())
				.as("one warning per name, not per call site")
				.isEqualTo(1);
		}
	}

	@Test
	void theScalarBackendReportsItToo() {
		// --no-gc has its own dispatcher and its own reachability walk: a (defun sqrt
		// ...) is never even enqueued there, because every (sqrt ...) call site
		// compiles to the built-in -- which is precisely the override to report, so the
		// warning is armed against the DEFINED names rather than the reachable ones.
		String source = """
				(defun sqrt (x) (* x 2))
				(defun twice (x) (sqrt x))
				(rontolisp:wasm-export 'twice :params '(:long) :returns :long)
				""";
		assertThat(compileCapturingErr(
				program -> new am.ik.rontolisp.codegen.wasm.NoGcWasmCompiler(OptimizeLevel.NONE, false, false, false)
					.compile(program),
				source))
			.contains("redefines the COMMON-LISP function SQRT");
	}

	@Test
	void aNameTheBackendDoesNotInterceptIsNotReported() {
		// The warning is armed before the operator dispatch and disarmed by the
		// ordinary call path, so it fires only where an interception really overrode a
		// definition. A defun of a name outside the cl package is nobody's business,
		// and a cl name reached through the ordinary path (the shape wait.lisp's `sleep`
		// and compile-runtime.lisp's `compile` rely on) must stay silent -- otherwise
		// every program splicing those libraries would be told its own runtime is
		// wrong.
		String ownName = """
				(defun my-random (n) (* n 2))
				(print (my-random 21))
				""";
		assertThat(compileCapturingErr(program -> new JvmLispCompiler("Test").compile(program), ownName))
			.doesNotContain("redefines the COMMON-LISP");
		assertThat(compileCapturingErr(program -> new WasmLispCompiler().compile(program), ownName))
			.doesNotContain("redefines the COMMON-LISP");

		// A definition that is never CALLED overrode nothing, so there is nothing to
		// report either.
		String definedButUnused = "(defun random (&rest args) (declare (ignore args)) 42)\n(print 1)\n";
		assertThat(compileCapturingErr(program -> new JvmLispCompiler("Test").compile(program), definedButUnused))
			.doesNotContain("redefines the COMMON-LISP");
		assertThat(compileCapturingErr(program -> new WasmLispCompiler().compile(program), definedButUnused))
			.doesNotContain("redefines the COMMON-LISP");
	}

}
