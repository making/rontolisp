package am.ik.rontolisp.compiler;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pin for the {@code :async t} build obligations: the standing
 * {@code WebAssembly.Suspending}/{@code promising} line, the per-export listing the host
 * wraps by, and the load-path ERROR -- {@code _initialize} runs on a stack no
 * {@code promising} entered, so a suspension there is a trap naming nobody, and the
 * program itself declared that the host may suspend.
 */
class SuspendingImportsTest {

	/** The warnings one compile writes to stderr through {@link CompileWarnings}. */
	private static String warnings(String source, boolean noWasi) {
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(err));
		try {
			new WasmLispCompiler(false, false, noWasi).compile(LispReader.readAllFromString(source));
		}
		finally {
			System.setErr(oldErr);
		}
		return err.toString();
	}

	@Test
	void anAsyncImportStatesTheStandingHostObligation() {
		// The wrapper only wraps the result in a settled future -- the suspension is the
		// host's business -- so the build is the only place that can say what the host
		// owes: Suspending on the import, promising on the exports, serialised calls.
		String out = warnings("""
				(rontolisp:wasm-import 'pull :from "net" :as "pull" :params '(:string) :returns :string :async t)
				(rontolisp:async-defun grab (u) (rontolisp:await (pull u)))
				(rontolisp:wasm-export 'grab :params '(:string) :returns :string)
				""", true);
		assertThat(out).contains("net.pull")
			.contains("WebAssembly.Suspending")
			.contains("WebAssembly.promising")
			.contains("serialise");
	}

	@Test
	void theExportsThatCanReachASuspendingImportAreListedAndOneThatCannotIsNot() {
		// The host wraps exactly the exports that can reach a suspension; the listing is
		// by the export NAME (the host's spelling), through the transitive call graph.
		String out = warnings("""
				(rontolisp:wasm-import 'pull :from "net" :params '(:string) :returns :string :async t)
				(rontolisp:async-defun fetch-it (u) (rontolisp:await (pull u)))
				(defun handler (u) (fetch-it u))
				(defun pure (n) (* n n))
				(rontolisp:wasm-export 'handler :as "handleRequest" :params '(:string) :returns :string)
				(rontolisp:wasm-export 'pure :params '(:int) :returns :int)
				""", true);
		assertThat(out).contains("exports that can reach a suspending import").contains("handleRequest");
		assertThat(out.substring(out.indexOf("exports that can reach"))).doesNotContain("pure");
	}

	@Test
	void anImportTakenAsAValueWidensTheAnswerToEveryExport() {
		// The walk follows calls, not values: #'pull handed around can be called by
		// whoever received it, so the per-export listing would under-report -- the line
		// says so instead of guessing.
		String out = warnings("""
				(rontolisp:wasm-import 'pull :from "net" :params '(:string) :returns :string :async t)
				(defun run (f u) (funcall f u))
				(defun handler (u) (run #'pull u))
				(rontolisp:wasm-export 'handler :params '(:string) :returns :string)
				""", true);
		assertThat(out).contains("taken as a value").contains("ANY export");
	}

	@Test
	void aLoadPathCallOfAnAsyncImportIsAnErrorOnTheReactor() {
		// _initialize is a stack no WebAssembly.promising entered; the program DECLARED
		// the host may suspend, so reaching the import while loading is an error, not a
		// line -- and the message names the chain that got there.
		assertThatThrownBy(() -> warnings("""
				(rontolisp:wasm-import 'pull :from "net" :params '(:string) :returns :string :async t)
				(rontolisp:async-defun warm () (rontolisp:await (pull "https://x")))
				(defvar *cache* (warm))
				""", true)).hasMessageContaining("declared :async t")
			.hasMessageContaining("while the module LOADS")
			.hasMessageContaining("-> WARM");
	}

	@Test
	void aHandlerDoesNotSilenceTheLoadPathError() {
		// A suspension is a TRAP, not a condition: handler-case around the call changes
		// nothing about what the host sees, so the error stands.
		assertThatThrownBy(() -> warnings("""
				(rontolisp:wasm-import 'pull :from "net" :params '(:string) :returns :string :async t)
				(defvar *cache* (handler-case (rontolisp:await (pull "https://x")) (error () nil)))
				""", true)).hasMessageContaining("declared :async t");
	}

	@Test
	void aWasiCommandModuleKeepsTheObligationButNotTheError() {
		// _start on a synchronous host (wasmtime --preload) is a perfectly valid way to
		// run an :async t import -- the future is settled either way -- so a WASI build
		// states the obligation and refuses nothing.
		String out = warnings("""
				(rontolisp:wasm-import 'pull :from "net" :params '(:string) :returns :string :async t)
				(print (rontolisp:await (pull "https://x")))
				""", false);
		assertThat(out).contains("WebAssembly.Suspending");
	}

	@Test
	void aSynchronousImportSaysNothing() {
		// Without :async t nothing changed: no obligation, no error, byte-identical
		// modules -- the option is the declaration, not the mechanism.
		String out = warnings("""
				(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)
				(defvar *x* (add 1 2))
				""", true);
		assertThat(out).doesNotContain("WebAssembly.Suspending").doesNotContain(":async t");
	}

}
