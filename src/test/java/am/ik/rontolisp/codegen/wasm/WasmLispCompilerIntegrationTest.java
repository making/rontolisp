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
import static org.assertj.core.api.Assertions.within;

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
						// curl stays installed: the http-handler serve test curls the
						// wasi:http/incoming-handler component running under `wasmtime
						// serve`.
						+ " && rm -rf /var/lib/apt/lists/*")
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

	// JSON tests pre-process with JsonLibrary.process, mirroring the compile-path
	// pre-pass run by RontoLispCli.
	private static String compileAndRunJson(String lispCode) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.JsonLibrary.process(LispReader.readAllFromString(lispCode));
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "/tmp/test.wasm");
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	// linalg tests pre-process with LinalgLibrary.process, mirroring the compile-path
	// pre-pass run by RontoLispCli.
	private static String compileAndRunLinalg(String lispCode) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LinalgLibrary.process(LispReader.readAllFromString(lispCode));
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "/tmp/test.wasm");
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	// URL tests pre-process with UrlLibrary.process, mirroring the compile-path
	// pre-pass run by RontoLispCli.
	private static String compileAndRunUrl(String lispCode) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.UrlLibrary.process(LispReader.readAllFromString(lispCode));
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "/tmp/test.wasm");
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	// Preview 1 variant that passes an environment variable and returns the raw
	// (untrimmed)
	// stdout, so callers can assert on exact bytes (e.g. that a newline stays 0x0a).
	private static String compileAndRunRawWithEnv(String lispCode, String env) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--env", env, "/tmp/test.wasm");
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout();
	}

	// Compiles a program that uses (rontolisp:wasm-export ...) and invokes one of the
	// exported Lisp
	// functions directly via `wasmtime --invoke <fn> ... <args>` (scalar arguments only).
	private static String compileAndInvoke(String lispCode, String function, String... args) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		List<String> command = new java.util.ArrayList<>(
				List.of("wasmtime", "run", "--invoke", function, "-W", "gc", "/tmp/test.wasm"));
		command.addAll(List.of(args));
		ExecResult result = wasmtime.execInContainer(command.toArray(new String[0]));
		assertThat(result.getExitCode())
			.as("exit code for invoke %s: %s\nstderr: %s", function, lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	@Test
	void exportScalarFunctionsCallableViaInvoke() throws Exception {
		String program = """
				(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
				(defun half (x) (/ x 2.0))
				(defun evenp2 (n) (= (mod n 2) 0))
				(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
				(rontolisp:wasm-export 'half :params '(:float) :returns :float)
				(rontolisp:wasm-export 'evenp2 :params '(:int) :returns :bool)
				""";
		assertThat(compileAndInvoke(program, "fact", "5")).isEqualTo("120");
		assertThat(compileAndInvoke(program, "fact", "10")).isEqualTo("3628800");
		assertThat(compileAndInvoke(program, "half", "7.0")).isEqualTo("3.5");
		assertThat(compileAndInvoke(program, "evenp2", "4")).isEqualTo("1");
		assertThat(compileAndInvoke(program, "evenp2", "5")).isEqualTo("0");
	}

	@Test
	void exportVoidFunctionRunsForItsSideEffect() throws Exception {
		// An omitted :returns makes a side-effecting export with no WASM result; invoking
		// it
		// runs the body (here printing) and returns nothing.
		String program = """
				(defun shout-square (n) (print (* n n)))
				(rontolisp:wasm-export 'shout-square :params '(:int))
				""";
		assertThat(compileAndInvoke(program, "shout-square", "6")).isEqualTo("36");
	}

	@Test
	void exportMemoryTypesProduceInstantiableModule() throws Exception {
		// :string/:s-expr need a memory-writing host (round-trip verified out of band);
		// here
		// we confirm the module with the bump allocator instantiates and its _start runs.
		String program = """
				(defun shout (s) (string-upcase s))
				(defun rev (lst) (reverse lst))
				(rontolisp:wasm-export 'shout :params '(:string) :returns :string)
				(rontolisp:wasm-export 'rev :params '(:s-expr) :returns :s-expr)
				(print "ok")
				""";
		assertThat(compileAndRun(program)).isEqualTo("\"ok\"");
	}

	@Test
	void heapGrowsForStringsLargerThanInitialMemory() throws Exception {
		// Regression: the GC heap is a bump allocator over linear memory. It used to
		// never
		// call memory.grow, so any program whose cumulative string allocation exceeded
		// the
		// initial 4 pages (256 KB) trapped with "memory access out of bounds". Build a
		// ~640 KB string by doubling (shallow recursion, so this exercises the heap, not
		// the call stack) and print its length; before the fix _start trapped (non-zero
		// exit), now it completes.
		String program = """
				(defun double-it (s n) (if (<= n 0) s (double-it (concatenate 'string s s) (- n 1))))
				(print (length (double-it "0123456789" 16)))
				""";
		assertThat(compileAndRun(program)).isEqualTo("655360");
	}

	@Test
	void floatModAndRemComputeCorrectly() throws Exception {
		// Regression: float mod/rem on the GC backend used to miscompile. A literal float
		// operand wrote an invalid f64 opcode (byte 0xff, there is no f64.rem), and a
		// variable-borne float fell through to the i32-only path and trapped at runtime
		// with "illegal cast". Now mod/rem dispatch through the rational runtime
		// (FUNC_RAT_MOD/FUNC_RAT_REM): rem = a - b*trunc(a/b), mod = a - b*floor(a/b),
		// computed in f64 when either operand is a float. The operands here arrive via
		// :float params, so they are NOT literals -- this is the path that used to trap.
		// Results are scaled to an integer (round of a float, which the backend already
		// supports) so the assertion does not depend on float-printing format.
		String floats = """
				(defun fmod6 (a b) (round (* (mod a b) 1000000.0)))
				(defun frem6 (a b) (round (* (rem a b) 1000000.0)))
				(rontolisp:wasm-export 'fmod6 :params '(:float :float) :returns :int)
				(rontolisp:wasm-export 'frem6 :params '(:float :float) :returns :int)
				""";
		// (mod 4.6666 2.0) = 0.6666 ; (mod -0.3 6.0) = 5.7 (sign of the divisor)
		assertThat(compileAndInvoke(floats, "fmod6", "4.6666", "2.0")).isEqualTo("666600");
		assertThat(compileAndInvoke(floats, "fmod6", "-0.3", "6.0")).isEqualTo("5700000");
		// (rem 4.6666 2.0) = 0.6666 ; (rem -4.6 2.0) = -0.6 (sign of the dividend)
		assertThat(compileAndInvoke(floats, "frem6", "4.6666", "2.0")).isEqualTo("666600");
		assertThat(compileAndInvoke(floats, "frem6", "-4.6", "2.0")).isEqualTo("-600000");

		// Integer mod/rem still work (the i31 fast path), including negative operands.
		String ints = """
				(defun imod (a b) (mod a b))
				(defun irem (a b) (rem a b))
				(rontolisp:wasm-export 'imod :params '(:int :int) :returns :int)
				(rontolisp:wasm-export 'irem :params '(:int :int) :returns :int)
				""";
		assertThat(compileAndInvoke(ints, "imod", "-7", "3")).isEqualTo("2");
		assertThat(compileAndInvoke(ints, "irem", "-7", "3")).isEqualTo("-1");
		assertThat(compileAndInvoke(ints, "imod", "7", "-3")).isEqualTo("-2");
		assertThat(compileAndInvoke(ints, "irem", "7", "-3")).isEqualTo("1");
	}

	// Compiles in --no-wasi (reactor) mode -- the module has no wasi_snapshot_preview1
	// imports -- and invokes a scalar export. wasmtime instantiates it with no WASI
	// provided.
	private static String compileNoWasiAndInvoke(String lispCode, String function, String... args) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler(false, false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		List<String> command = new java.util.ArrayList<>(
				List.of("wasmtime", "run", "--invoke", function, "-W", "gc", "/tmp/test.wasm"));
		command.addAll(List.of(args));
		ExecResult result = wasmtime.execInContainer(command.toArray(new String[0]));
		assertThat(result.getExitCode())
			.as("exit code for no-wasi invoke %s: %s\nstderr: %s", function, lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	@Test
	void noWasiModuleInstantiatesWithoutWasiAndInvokesScalarExport() throws Exception {
		// Reactor mode: the module imports no WASI functions, so a host instantiates it
		// with
		// no import object. A pure-compute export is still callable.
		String program = """
				(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
				(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
				""";
		assertThat(compileNoWasiAndInvoke(program, "fact", "5")).isEqualTo("120");
		assertThat(compileNoWasiAndInvoke(program, "fact", "10")).isEqualTo("3628800");
	}

	// Compiles a "host" module (whose wasm-exports play the imported host functions)
	// and a main module using (rontolisp:wasm-import ... :from "host"), then runs the
	// main module with the host instance preloaded (`wasmtime run --preload host=...`).
	private static String compileAndRunWithPreload(String hostCode, String mainCode, boolean optimize)
			throws Exception {
		byte[] host = new WasmLispCompiler(false, false, true).compile(LispReader.readAllFromString(hostCode));
		byte[] main = new WasmLispCompiler(false, false, false, optimize)
			.compile(LispReader.readAllFromString(mainCode));
		wasmtime.copyFileToContainer(Transferable.of(host), "/tmp/host.wasm");
		wasmtime.copyFileToContainer(Transferable.of(main), "/tmp/main.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc", "--preload", "host=/tmp/host.wasm",
				"/tmp/main.wasm");
		assertThat(result.getExitCode()).as("exit code for preload run: %s\nstderr: %s", mainCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	@Test
	void importedHostFunctionIsCallableLikeADefun() throws Exception {
		// The host module exports its functions under :as aliases; the main module
		// imports them (one under its own :as alias) and calls them directly, as a
		// first-class #'value, from a closure, and through eval.
		String host = """
				(defun host-add (a b) (+ a b))
				(defun host-scale (x) (* x 2.5))
				(rontolisp:wasm-export 'host-add :as "add" :params '(:int :int) :returns :int)
				(rontolisp:wasm-export 'host-scale :as "scale" :params '(:float) :returns :float)
				""";
		String main = """
				(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)
				(rontolisp:wasm-import 'scale-it :from "host" :as "scale" :params '(:float) :returns :float)
				(print (add 20 22))
				(print (funcall #'add 1 2))
				(print (scale-it 4.0))
				(print (mapcar (lambda (x) (add x 100)) (list 1 2 3)))
				(print (eval '(add 5 6)))
				""";
		assertThat(compileAndRunWithPreload(host, main, false)).isEqualTo("""
				42
				3
				10.0
				(101 102 103)
				11""");
	}

	@Test
	void importedHostFunctionInsideUserPackageResolvesUnqualifiedName() throws Exception {
		// A wasm-import declared inside a user package with a plain unqualified quoted
		// name registers under the canonical qualified name (PackageResolver resolves
		// the name argument like a defun name), so pkg:name call sites -- direct and
		// from a package-local helper -- find the synthetic defun.
		String host = """
				(defun host-add (a b) (+ a b))
				(rontolisp:wasm-export 'host-add :as "add" :params '(:int :int) :returns :int)
				""";
		String main = """
				(defpackage :hostapi (:use :cl) (:export :add :add3))
				(in-package :hostapi)
				(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)
				(defun add3 (a b c) (add (add a b) c))
				(in-package :cl-user)
				(print (hostapi:add 40 2))
				(print (hostapi:add3 1 2 3))
				""";
		assertThat(compileAndRunWithPreload(host, main, false)).isEqualTo("""
				42
				6""");
	}

	@Test
	void importedHostFunctionSurvivesTheTreeShaker() throws Exception {
		// --optimize runs after import injection; the used import must survive and stay
		// correctly renumbered.
		String host = """
				(defun host-add (a b) (+ a b))
				(rontolisp:wasm-export 'host-add :as "add" :params '(:int :int) :returns :int)
				""";
		String main = """
				(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)
				(print (add 40 2))
				""";
		assertThat(compileAndRunWithPreload(host, main, true)).isEqualTo("42");
	}

	@Test
	void importedBoolAndSexprRoundTripThroughTheHost() throws Exception {
		// :bool crosses as i32 (0 = nil); :s-expr crosses as (ptr,len) of readable
		// text -- but only within one module's memory, so here the host takes ints and
		// the main module exercises :bool marshalling.
		String host = """
				(defun host-big (n) (> n 100))
				(rontolisp:wasm-export 'host-big :as "big" :params '(:int) :returns :bool)
				""";
		String main = """
				(rontolisp:wasm-import 'big :from "host" :params '(:int) :returns :bool)
				(print (big 200))
				(print (big 3))
				""";
		assertThat(compileAndRunWithPreload(host, main, false)).isEqualTo("t\nnil");
	}

	@Test
	void exportAliasIsInvokableUnderTheAliasName() throws Exception {
		String program = """
				(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
				(rontolisp:wasm-export 'fact :as "fibonacci-ish" :params '(:int) :returns :int)
				""";
		assertThat(compileAndInvoke(program, "fibonacci-ish", "5")).isEqualTo("120");
	}

	// Compiles with --no-gc (the scalar non-GC lowering) and invokes an export
	// WITHOUT `-W gc`, proving the module runs on a plain MVP runtime with no wasm-GC and
	// no import object.
	private static String compileNoGcAndInvoke(boolean optimize, String lispCode, String function, String... args)
			throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new ScalarWasmCompiler(optimize).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		List<String> command = new java.util.ArrayList<>(
				List.of("wasmtime", "run", "--invoke", function, "/tmp/test.wasm"));
		command.addAll(List.of(args));
		ExecResult result = wasmtime.execInContainer(command.toArray(new String[0]));
		assertThat(result.getExitCode())
			.as("exit code for no-gc invoke %s: %s\nstderr: %s", function, lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	@Test
	void noGcModuleRunsWithoutWasmGcAndMatchesTheInterpreter() throws Exception {
		// The headline case: a recursive factorial compiled to a plain MVP module runs
		// with no `-W gc`. Integers are unboxed i64, floats f64, so the results match the
		// interpreter across the pure-numeric range.
		String fact = """
				(defun fact (n) (if (<= n 1) 1 (* n (fact (1- n)))))
				(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
				""";
		assertThat(compileNoGcAndInvoke(false, fact, "fact", "5")).isEqualTo("120");
		assertThat(compileNoGcAndInvoke(false, fact, "fact", "10")).isEqualTo("3628800");
	}

	@Test
	void noGcUsesExactI64IntegerArithmetic() throws Exception {
		// f(a) = a^2 - (a-1)(a+1) = 1 for every a. With a = 10^8 the intermediates reach
		// 10^16, beyond f64's exact integer range (2^53): an f64 lowering would lose the
		// odd product and return 0. The i64 path stays exact and returns 1 (the result
		// itself fits the :int/i32 boundary).
		String program = """
				(defun f (a) (- (* a a) (* (- a 1) (+ a 1))))
				(rontolisp:wasm-export 'f :params '(:int) :returns :int)
				""";
		assertThat(compileNoGcAndInvoke(false, program, "f", "100000000")).isEqualTo("1");
	}

	@Test
	void noGcComposesWithOptimize() throws Exception {
		// --no-gc --optimize: the (GC-agnostic) tree shaker runs on the non-GC module
		// too,
		// dropping anything unreachable from the exports while preserving behavior.
		// `used`
		// is reachable and kept; `dead` is not and is removed, yet the module still runs
		// with no `-W gc`.
		String program = """
				(defun used (n) (* n 2))
				(defun dead (n) (+ n 999))
				(defun entry (n) (used (used n)))
				(rontolisp:wasm-export 'entry :params '(:int) :returns :int)
				""";
		List<LispVal> parsed = LispReader.readAllFromString(program);
		assertThat(compileNoGcAndInvoke(true, program, "entry", "5")).isEqualTo("20");
		// The optimized module is no larger than the plain one (the unreachable `dead`
		// function is dropped); behavior is identical either way.
		int plain = new ScalarWasmCompiler(false).compile(parsed).length;
		int optimized = new ScalarWasmCompiler(true).compile(parsed).length;
		assertThat(optimized).isLessThanOrEqualTo(plain);
		assertThat(compileNoGcAndInvoke(false, program, "entry", "5")).isEqualTo("20");
	}

	@Test
	void noGcSupportsFloatBoolAndCrossFunctionCalls() throws Exception {
		// :float (f64) and :bool (i32 0/1) boundaries, mutual recursion / cross-calls,
		// and mod -- all on the unboxed scalar path, invoked with no `-W gc`.
		String program = """
				(defun gcd2 (a b) (if (= b 0) a (gcd2 b (mod a b))))
				(defun area (r) (* 3.14159 (* r r)))
				(defun in-range (x) (if (< x 0) nil (if (> x 100) nil t)))
				(rontolisp:wasm-export 'gcd2 :params '(:int :int) :returns :int)
				(rontolisp:wasm-export 'area :params '(:float) :returns :float)
				(rontolisp:wasm-export 'in-range :params '(:int) :returns :bool)
				""";
		assertThat(compileNoGcAndInvoke(false, program, "gcd2", "48", "36")).isEqualTo("12");
		assertThat(compileNoGcAndInvoke(false, program, "area", "2.0")).isEqualTo("12.56636");
		assertThat(compileNoGcAndInvoke(false, program, "in-range", "50")).isEqualTo("1");
		assertThat(compileNoGcAndInvoke(false, program, "in-range", "200")).isEqualTo("0");
	}

	@Test
	void noGcSupportsIterationAndLocalMutation() throws Exception {
		// dotimes / do loops with a let-bound accumulator mutated by setq -- the
		// iterative
		// counterparts of recursion, all on the unboxed scalar path with no `-W gc`. The
		// accumulator in `sumsq` starts as integer 0 but is summed with floats, so its
		// inferred type widens to f64.
		String program = """
				(defun sum-upto (n)
				  (let ((acc 0)) (dotimes (i n) (setq acc (+ acc i))) acc))
				(defun ifact (n)
				  (do ((i 1 (+ i 1)) (acc 1 (* acc i))) ((> i n) acc)))
				(defun sumsq (n)
				  (let ((acc 0)) (dotimes (i n) (setq acc (+ acc (* (float i) (float i))))) acc))
				(rontolisp:wasm-export 'sum-upto :params '(:int) :returns :int)
				(rontolisp:wasm-export 'ifact :params '(:int) :returns :int)
				(rontolisp:wasm-export 'sumsq :params '(:int) :returns :float)
				""";
		assertThat(compileNoGcAndInvoke(false, program, "sum-upto", "100")).isEqualTo("4950");
		assertThat(compileNoGcAndInvoke(false, program, "ifact", "10")).isEqualTo("3628800");
		// 0^2 + 1^2 + ... + 4^2 = 30; printed by wasmtime as an f64.
		assertThat(compileNoGcAndInvoke(false, program, "sumsq", "5")).isEqualTo("30");
	}

	@Test
	void noGcSupportsReturnFromALoop() throws Exception {
		// `return` is a non-local exit from the loop's %block boundary: count up but bail
		// out early once the limit is hit.
		String program = """
				(defun count-down (n)
				  (let ((c 0))
				    (dotimes (i 1000000)
				      (when (>= i n) (return c))
				      (setq c (+ c 1)))
				    c))
				(rontolisp:wasm-export 'count-down :params '(:int) :returns :int)
				""";
		assertThat(compileNoGcAndInvoke(false, program, "count-down", "42")).isEqualTo("42");
		assertThat(compileNoGcAndInvoke(false, program, "count-down", "0")).isEqualTo("0");
	}

	@Test
	void noGcSupportsSqrtAndBitwiseOps() throws Exception {
		// sqrt (f64.sqrt) and the integer bitwise operators (logand/logior/logxor/lognot/
		// ash), including a popcount loop that combines do + ash + logand + setq.
		String program = """
				(defun root (x) (sqrt x))
				(defun band (a b) (logand a b))
				(defun shr (a n) (ash a (- 0 n)))
				(defun popcount (x)
				  (let ((c 0))
				    (do ((v x (ash v -1))) ((= v 0) c)
				      (setq c (+ c (logand v 1))))))
				(rontolisp:wasm-export 'root :params '(:float) :returns :float)
				(rontolisp:wasm-export 'band :params '(:int :int) :returns :int)
				(rontolisp:wasm-export 'shr :params '(:int :int) :returns :int)
				(rontolisp:wasm-export 'popcount :params '(:int) :returns :int)
				""";
		assertThat(compileNoGcAndInvoke(false, program, "root", "16.0")).isEqualTo("4");
		assertThat(compileNoGcAndInvoke(false, program, "band", "12", "10")).isEqualTo("8");
		assertThat(compileNoGcAndInvoke(false, program, "shr", "1024", "3")).isEqualTo("128");
		assertThat(compileNoGcAndInvoke(false, program, "popcount", "255")).isEqualTo("8");
		// The loop-based popcount also survives the tree shaker under --optimize.
		assertThat(compileNoGcAndInvoke(true, program, "popcount", "43")).isEqualTo("4");
	}

	@Test
	void noGcSupportsStringConcatenationAtTheBoundary() throws Exception {
		// The string slice of --no-gc: literals, (concatenate 'string ...) in a loop and
		// a
		// :string return. This is the kernel of the string-returning mandelbrot example.
		// The wrapper returns (content-ptr, length); we assert the round-tripped length
		// (content bytes are checked structurally + by the in-tree examples).
		String program = """
				(defun shade (i max) (cond ((>= i max) "#") ((>= i 5) ".") (t " ")))
				(defun band (n)
				  (let ((out ""))
				    (dotimes (k n) (setq out (concatenate 'string out (shade k 8))))
				    out))
				(rontolisp:wasm-export 'band :params '(:int) :returns :string)
				""";
		assertThat(noGcStringLength(false, program, "band", "12")).isEqualTo(12);
		assertThat(noGcStringLength(false, program, "band", "0")).isZero();
		// Composes with the tree shaker (--optimize).
		assertThat(noGcStringLength(true, program, "band", "30")).isEqualTo(30);
	}

	@Test
	void noGcSupportsStringPrimitives() throws Exception {
		// The Phase-2b string primitives: length / subseq / string= / char (a character
		// is its code point under --no-gc) / char-code / char= / princ-to-string. All
		// exercised through scalar boundaries so wasmtime --invoke can drive them.
		String program = """
				(defun route (n)
				  (let ((path (if (< n 0) "other" (subseq "/hello/world" 0 n))))
				    (cond ((string= path "/hello") 1)
				          ((char= (char path 1) #\\h) 2)
				          (t 3))))
				(rontolisp:wasm-export 'route :params '(:int) :returns :int)
				(defun digits (n) (length (princ-to-string n)))
				(rontolisp:wasm-export 'digits :params '(:int) :returns :int)
				(defun code-at (n) (char-code (char "abc" n)))
				(rontolisp:wasm-export 'code-at :params '(:int) :returns :int)
				""";
		assertThat(compileNoGcAndInvoke(false, program, "route", "6")).isEqualTo("1");
		assertThat(compileNoGcAndInvoke(false, program, "route", "4")).isEqualTo("2");
		assertThat(compileNoGcAndInvoke(false, program, "route", "-1")).isEqualTo("3");
		assertThat(compileNoGcAndInvoke(false, program, "digits", "0")).isEqualTo("1");
		assertThat(compileNoGcAndInvoke(false, program, "digits", "12345")).isEqualTo("5");
		assertThat(compileNoGcAndInvoke(false, program, "digits", "-42")).isEqualTo("3");
		assertThat(compileNoGcAndInvoke(false, program, "code-at", "1")).isEqualTo("98");
		// Composes with the tree shaker (--optimize).
		assertThat(compileNoGcAndInvoke(true, program, "route", "6")).isEqualTo("1");
		// A string-producing op with no literal and no :string boundary still gets the
		// memory + helpers (subseq/princ-to-string flag the memory as used).
		String noLiteral = """
				(defun width (n) (length (princ-to-string (* n n))))
				(rontolisp:wasm-export 'width :params '(:int) :returns :int)
				""";
		assertThat(compileNoGcAndInvoke(false, noLiteral, "width", "100")).isEqualTo("5");
	}

	// Invokes a --no-gc :string-returning export and returns the length component of the
	// (content-ptr, length) host result. wasmtime prints multi-value results one per
	// line,
	// so the length is the last whitespace-separated token. (The string-parameter side of
	// the ABI needs a host that writes linear memory and is exercised by the runnable
	// docs
	// examples / playground rather than the wasmtime-only container here.)
	private static int noGcStringLength(boolean optimize, String lispCode, String function, String... args)
			throws Exception {
		String out = compileNoGcAndInvoke(optimize, lispCode, function, args);
		String[] tokens = out.trim().split("\\s+");
		return Integer.parseInt(tokens[tokens.length - 1]);
	}

	// Compiles with --optimize (dead-code elimination) and invokes a scalar export, in
	// the
	// given mode. Used to confirm the tree-shaken module still behaves identically.
	private static String compileOptimizedAndInvoke(String lispCode, boolean noWasi, String function, String... args)
			throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler(false, false, noWasi, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		List<String> command = new java.util.ArrayList<>(
				List.of("wasmtime", "run", "--invoke", function, "-W", "gc", "/tmp/test.wasm"));
		command.addAll(List.of(args));
		ExecResult result = wasmtime.execInContainer(command.toArray(new String[0]));
		assertThat(result.getExitCode())
			.as("exit code for optimized invoke %s: %s\nstderr: %s", function, lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	@Test
	void optimizedNoWasiExportBehavesIdenticallyAndShrinks() throws Exception {
		// --optimize drops every function unreachable from the roots (the exports plus
		// the
		// `_initialize` reactor entry). A pure-compute reactor module shrinks
		// dramatically
		// yet computes the same result.
		String program = """
				(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
				(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
				""";
		assertThat(compileOptimizedAndInvoke(program, true, "fact", "5")).isEqualTo("120");
		assertThat(compileOptimizedAndInvoke(program, true, "fact", "10")).isEqualTo("3628800");

		List<LispVal> parsed = LispReader.readAllFromString(program);
		int plain = new WasmLispCompiler(false, false, true, false).compile(parsed).length;
		int optimized = new WasmLispCompiler(false, false, true, true).compile(parsed).length;
		assertThat(optimized).isLessThan(plain / 5);
	}

	@Test
	void optimizedPrintProgramRunsIdentically() throws Exception {
		// Default (WASI) mode: --optimize also drops the unused WASI imports. Behavior
		// and
		// stdout must be unchanged.
		String program = """
				(print (+ 1 2))
				(print (string-upcase "hi"))
				""";
		byte[] wasmBytes = new WasmLispCompiler(false, false, false, true)
			.compile(LispReader.readAllFromString(program));
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "/tmp/test.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("3\n\"HI\"");
	}

	@Test
	void optimizedEvalProgramResolvesDynamically() throws Exception {
		// A program using eval keeps the interpreter + dispatch reachable; --optimize
		// must
		// not prune a dynamically-reached target.
		String program = """
				(defun sq (x) (* x x))
				(print (eval '(sq 9)))
				""";
		byte[] wasmBytes = new WasmLispCompiler(false, false, false, true)
			.compile(LispReader.readAllFromString(program));
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "/tmp/test.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("81");
	}

	@Test
	void noWasiModuleTrapsWhenItAttemptsIo() throws Exception {
		// I/O is unsupported in no-wasi mode: the omitted fd_write becomes a trap stub,
		// so an
		// export that prints traps (non-zero exit) -- the documented contract.
		String program = """
				(defun shout (n) (print n))
				(rontolisp:wasm-export 'shout :params '(:int))
				""";
		byte[] wasmBytes = new WasmLispCompiler(false, false, true).compile(LispReader.readAllFromString(program));
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "--invoke", "shout", "-W", "gc",
				"/tmp/test.wasm", "7");
		assertThat(result.getExitCode()).isNotZero();
		assertThat(result.getStderr()).contains("unreachable");
	}

	@Test
	void mapFamilyTrapsOnNonList() throws Exception {
		// The map* family operates on lists; a non-list (e.g. a string) traps rather than
		// silently returning nil, matching the interpreter (.todo/26). WASM error is an
		// unreachable trap (it carries no message).
		for (String form : List.of("(mapcar #'identity \"abc\")", "(mapc #'identity \"abc\")",
				"(mapcan #'list \"abc\")", "(maplist #'identity \"abc\")", "(mapcon #'list \"abc\")")) {
			byte[] wasmBytes = new WasmLispCompiler().compile(LispReader.readAllFromString(form));
			wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
			ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "/tmp/test.wasm");
			assertThat(result.getExitCode()).as("expected a trap for: %s", form).isNotZero();
			assertThat(result.getStderr()).as("trap message for: %s", form).contains("unreachable");
		}
		// nil (the empty list) and proper lists stay accepted (no trap).
		assertThat(compileAndRun("(print (mapcar #'1+ '(1 2 3))) (print (mapcar #'1+ nil))")).isEqualTo("(2 3 4)\nnil");
		assertThat(compileAndRun("(print (maplist #'identity nil))")).isEqualTo("nil");
	}

	@Test
	void loopMacroCompilesAndRuns() throws Exception {
		assertThat(compileAndRun("(print (loop for i from 1 to 5 collect i))")).isEqualTo("(1 2 3 4 5)");
		assertThat(compileAndRun("(print (loop for x in '(a b c) for i from 0 collect (list i x)))"))
			.isEqualTo("((0 a) (1 b) (2 c))");
		assertThat(compileAndRun("(print (loop for i from 1 to 5 sum i))")).isEqualTo("15");
		assertThat(compileAndRun("(print (loop for i from 1 to 10 when (evenp i) collect i))"))
			.isEqualTo("(2 4 6 8 10)");
		assertThat(compileAndRun("(print (loop repeat 3 collect 'x))")).isEqualTo("(x x x)");
		assertThat(compileAndRun("(print (loop for i from 1 do (when (> i 3) (return i))))")).isEqualTo("4");
		assertThat(compileAndRun("(print (loop for c across \"hello\" collect c))"))
			.isEqualTo("(#\\h #\\e #\\l #\\l #\\o)");
	}

	@Test
	void loopExtendedClausesCompileAndRun() throws Exception {
		// Positional while, thereis/always, anaphoric it, loop-finish, parallel and,
		// destructuring.
		assertThat(compileAndRun("(print (loop for x in '(1 2 3 9 4) while (< x 4) collect x))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (loop for x in '(nil nil 7 9) thereis x))")).isEqualTo("7");
		assertThat(compileAndRun("(print (loop for x in '(1 2 9) always (< x 5)))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (loop for x in '(1 nil 3 nil 5) when x collect it))")).isEqualTo("(1 3 5)");
		assertThat(compileAndRun(
				"(print (loop for i from 1 collect i into xs do (when (>= i 3) (loop-finish)) finally (return (length xs))))"))
			.isEqualTo("3");
		assertThat(compileAndRun("(print (loop for a = 0 then b and b = 1 then (+ a b) repeat 8 collect b))"))
			.isEqualTo("(1 1 2 3 5 8 13 21)");
		assertThat(compileAndRun("(print (loop for (a b) in '((1 2) (3 4) (5 6)) collect (+ a b)))"))
			.isEqualTo("(3 7 11)");
		assertThat(compileAndRun("(print (loop with (x y) = '(10 20) repeat 1 collect (+ x y)))")).isEqualTo("(30)");
		assertThat(compileAndRun("(print (loop for x across #(1 2 3 4 5) collect (* x x)))"))
			.isEqualTo("(1 4 9 16 25)");
		assertThat(compileAndRun("(print (let ((l (list 1 2 3))) (setf (car l) 9 (second l) 8) l))"))
			.isEqualTo("(9 8 3)");
		assertThat(compileAndRun("(print (loop for x in '(1 2 3 4 5) for a = x then (+ a x) finally (return a)))"))
			.isEqualTo("15");
	}

	@Test
	void exportUnknownFunctionFailsToCompile() {
		assertThatThrownBy(
				() -> compileAndInvoke("(rontolisp:wasm-export 'nope :params '(:int) :returns :int)", "nope"))
			.hasMessageContaining("unknown function");
	}

	@Test
	void exportArityMismatchFailsToCompile() {
		assertThatThrownBy(() -> compileAndInvoke(
				"(defun f (a b) (+ a b)) (rontolisp:wasm-export 'f :params '(:int) :returns :int)", "f"))
			.hasMessageContaining("arity mismatch");
	}

	private static String compileAndRunComponent(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/test.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "component-model-async=y",
				"-W", "component-model-async-stackful=y", "-W", "component-model-more-async-builtins=y",
				"/tmp/test.component.wasm");
		assertThat(result.getExitCode()).as("exit code for component: %s\nstderr: %s", lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	@Test
	void componentPrintsInteger() throws Exception {
		assertThat(compileAndRunComponent("(print (+ 1 2))")).isEqualTo("3");
	}

	@Test
	void timeReportsElapsedAndReturnsValue() throws Exception {
		// On WASM get-internal-real-time is a float, so the elapsed reads as float ms.
		String output = compileAndRun("(print (time (+ 1 2)))");
		assertThat(output).contains("; Elapsed real time: ").contains(" ms").endsWith("3");
	}

	@Test
	void componentTimeReportsElapsedAndReturnsValue() throws Exception {
		String output = compileAndRunComponent("(print (time (+ 1 2)))");
		assertThat(output).contains("; Elapsed real time: ").contains(" ms").endsWith("3");
	}

	@Test
	void componentPrintsString() throws Exception {
		assertThat(compileAndRunComponent("(print \"hello\")")).isEqualTo("\"hello\"");
	}

	@Test
	void componentRunsDefunAndMultiplePrints() throws Exception {
		assertThat(compileAndRunComponent("(defun sq (x) (* x x)) (print (sq 7)) (print (list 1 2 3))"))
			.isEqualTo("49\n(1 2 3)");
	}

	private static String compileAndRunComponentWithEnv(String lispCode, String env) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/test.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "component-model-async=y",
				"-W", "component-model-async-stackful=y", "-W", "component-model-more-async-builtins=y", "--env", env,
				"/tmp/test.component.wasm");
		assertThat(result.getExitCode()).as("exit code for component: %s\nstderr: %s", lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	@Test
	void componentGetenvFromWasiEnvironment() throws Exception {
		// Component mode reads environment variables through wasi:cli/environment.
		assertThat(compileAndRunComponentWithEnv("(print (getenv \"RLENV\"))", "RLENV=hello")).isEqualTo("\"hello\"");
		assertThat(compileAndRunComponentWithEnv("(print (stringp (getenv \"RLENV\")))", "RLENV=hello")).isEqualTo("t");
		assertThat(compileAndRunComponentWithEnv("(print (getenv \"RL_UNSET\"))", "RLENV=hello")).isEqualTo("nil");
	}

	@Test
	void preview1GetenvDoesNotCorruptNewline() throws Exception {
		// Regression for .todo/13: getenv calls environ_sizes_get with scratch addresses
		// (ENV_COUNT_ADDR=136 ..) that must NOT overlap the interned-string data segment.
		// When they did (DATA_BASE_OFFSET=128), the host's count write at 136..139
		// clobbered
		// the shared newline byte at offset 137, so every newline after a getenv printed
		// as a
		// NUL (0x00) instead of 0x0a. Assert the raw bytes keep real newlines.
		assertThat(compileAndRunRawWithEnv("(getenv \"RLENV\") (format t \"X~%Y~%\")", "RLENV=hello"))
			.isEqualTo("X\nY\n");
	}

	@Test
	void preview1TimeDoesNotCorruptNilLiteral() throws Exception {
		// Companion regression for .todo/13: clock_time_get writes 8 bytes at
		// TIME_SCRATCH_ADDR=128, which overlapped the data segment's leading "nil"
		// literal
		// when DATA_BASE_OFFSET=128. Reading the clock then printing nil must still print
		// nil.
		assertThat(compileAndRunRawWithEnv("(get-internal-real-time) (print nil)", "RLENV=hello")).isEqualTo("nil\n");
	}

	@Test
	void componentTimeFromWasiClocks() throws Exception {
		// Component mode reads time from wasi:clocks; WASM returns it as a float (i31
		// can't
		// hold the magnitude). Compare/difference rather than print the raw value.
		assertThat(compileAndRunComponent("(print (> (get-universal-time) 3786825600.0))")).isEqualTo("t");
		assertThat(compileAndRunComponent("(print (floatp (get-internal-real-time)))")).isEqualTo("t");
		assertThat(compileAndRunComponent(
				"(let ((s (get-internal-run-time))) (print (>= (- (get-internal-run-time) s) 0.0)))"))
			.isEqualTo("t");
	}

	@Test
	void preview1TimeFromHostClock() throws Exception {
		// Preview 1 mode binds the real wasi_snapshot_preview1 clock_time_get.
		assertThat(compileAndRun("(print (> (get-universal-time) 3786825600.0))")).isEqualTo("t");
		assertThat(compileAndRun("(print (floatp (get-internal-real-time)))")).isEqualTo("t");
	}

	@Test
	void componentRandomDrawsFromWasiRandom() throws Exception {
		// Component mode draws entropy from wasi:random (Preview 1 uses the host's
		// random_get); both are non-reproducible, so only the range and type are
		// asserted.
		assertThat(compileAndRunComponent(
				"(let ((r (random 100))) (if (and (>= r 0) (< r 100)) (print \"in\") (print \"oob\")))"))
			.isEqualTo("\"in\"");
		assertThat(compileAndRunComponent("(print (integerp (random 10)))")).isEqualTo("t");
		assertThat(compileAndRunComponent(
				"(let ((r (random 1.0))) (if (and (>= r 0.0) (< r 1.0)) (print \"in\") (print \"oob\")))"))
			.isEqualTo("\"in\"");
	}

	private static String compileAndRunComponentWithDir(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/test.component.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"cd /tmp && wasmtime run -W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y --dir . test.component.wasm");
		assertThat(result.getExitCode()).as("exit code for component: %s\nstderr: %s", lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	@Test
	void httpHandlerServesUnderWasmtimeServe() throws Exception {
		// rontolisp:http-handler compiles (via the CLI's HttpHandlerInliner + serve mode)
		// to a wasi:http/incoming-handler component; run it under `wasmtime serve` and
		// hit
		// it with curl (installed in the image), asserting the handler echoes the
		// request.
		List<LispVal> program = am.ik.rontolisp.cli.HttpHandlerInliner.inline(LispReader.readAllFromString("""
				(defun handle (request)
				  (list :status 200
				        :body (concatenate 'string (getf request :method) " " (getf request :path))))
				(rontolisp:http-handler 'handle)
				"""));
		byte[] componentBytes = new WasmLispCompiler(false, true, false, false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"cd /tmp && wasmtime serve -W gc=y -W component-model-async=y -W component-model-async-stackful=y"
						+ " -W component-model-more-async-builtins=y serve.wasm >/tmp/serve.log 2>&1 &"
						+ " for i in $(seq 1 60); do out=$(curl -s http://127.0.0.1:8080/hello) && [ -n \"$out\" ]"
						+ " && { echo \"$out\"; exit 0; }; sleep 0.25; done; cat /tmp/serve.log; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve round trip; log: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("GET /hello");
	}

	@Test
	void httpHandlerServesResponseLargerThanIoChunkUnderWasmtimeServe() throws Exception {
		// wasi:io's blocking-write-and-flush accepts at most 4096 bytes per call, so the
		// serve adapter must chunk the response body (adapter-serve.wat, like
		// adapter-http.wat's request-body loop). 64 chars doubled 7 times = 8192 bytes.
		List<LispVal> program = am.ik.rontolisp.cli.HttpHandlerInliner.inline(LispReader.readAllFromString("""
				(defun handle (request)
				  (let ((s "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))
				    (dotimes (i 7)
				      (setq s (concatenate 'string s s)))
				    (list :status 200 :body s)))
				(rontolisp:http-handler 'handle)
				"""));
		byte[] componentBytes = new WasmLispCompiler(false, true, false, false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve-big.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"cd /tmp && wasmtime serve -W gc=y --addr 127.0.0.1:8081 serve-big.wasm >/tmp/serve-big.log 2>&1 &"
						+ " for i in $(seq 1 60); do code=$(curl -s -m 20 -o /tmp/big.out -w '%{http_code}'"
						+ " http://127.0.0.1:8081/big) && [ \"$code\" != 000 ]"
						+ " && { echo \"$code $(wc -c < /tmp/big.out)\"; exit 0; }; sleep 0.25; done;"
						+ " cat /tmp/serve-big.log; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve large response; log: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("200 8192");
	}

	@Test
	void httpHandlerRandomClockAndPrintUnderWasmtimeServe() throws Exception {
		// Inside a served handler random / time / print must work: the serve component
		// bridges the preview1 random_get / clock_time_get / fd_write imports to the
		// wasi:random / wasi:clocks / wasi:cli interfaces of the wasi:http proxy world
		// (adapter-serve-p1.wat) instead of trapping.
		List<LispVal> program = am.ik.rontolisp.cli.HttpHandlerInliner.inline(LispReader.readAllFromString("""
				(defun handle (request)
				  (print "handling")
				  (let ((r (random 10)))
				    (list :status 200
				          :body (concatenate 'string
				                             (if (and (integerp r) (>= r 0) (< r 10)) "r-in" "r-out")
				                             " "
				                             (if (numberp (get-universal-time)) "t-num" "t-bad")))))
				(rontolisp:http-handler 'handle)
				"""));
		byte[] componentBytes = new WasmLispCompiler(false, true, false, false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve-rand.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"cd /tmp && wasmtime serve -W gc=y --addr 127.0.0.1:8082 serve-rand.wasm >/tmp/serve-rand.log 2>&1 &"
						+ " for i in $(seq 1 60); do out=$(curl -s http://127.0.0.1:8082/) && [ -n \"$out\" ]"
						+ " && { echo \"$out\"; exit 0; }; sleep 0.25; done; cat /tmp/serve-rand.log; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve random/clock round trip; log: %s", result.getStderr())
			.isZero();
		assertThat(result.getStdout().trim()).isEqualTo("r-in t-num");
	}

	@Test
	void httpHandlerFetchInsideServeUnderWasmtimeServe() throws Exception {
		// A proxy-style handler: rontolisp:fetch inside a served handler works because
		// the serve+fetch component variant (WasmServeComponentBuilder.buildHttp) wires
		// the wasi:http outgoing machinery into the preview1 bridge
		// (adapter-serve-p1-http.wat); run the proxy with `wasmtime serve -S http=y`.
		// The backend is itself a plain rontolisp serve component, so the test stays
		// offline.
		List<LispVal> backend = am.ik.rontolisp.cli.HttpHandlerInliner.inline(LispReader.readAllFromString("""
				(defun handle (request)
				  (list :status 200
				        :body (concatenate 'string "backend " (getf request :path))))
				(rontolisp:http-handler 'handle)
				"""));
		byte[] backendBytes = new WasmLispCompiler(false, true, false, false, true).compile(backend);
		List<LispVal> proxy = am.ik.rontolisp.cli.HttpHandlerInliner.inline(LispReader.readAllFromString("""
				(defun handle (request)
				  (let ((resp (rontolisp:await (rontolisp:fetch "http://127.0.0.1:8083/up"))))
				    (list :status 200
				          :body (concatenate 'string "proxied " (getf resp :body)
				                             " " (princ-to-string (getf resp :status))))))
				(rontolisp:http-handler 'handle)
				"""));
		byte[] proxyBytes = new WasmLispCompiler(false, true, false, false, true).compile(proxy);
		wasmtime.copyFileToContainer(Transferable.of(backendBytes), "/tmp/serve-backend.wasm");
		wasmtime.copyFileToContainer(Transferable.of(proxyBytes), "/tmp/serve-proxy.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y --addr 127.0.0.1:8083 /tmp/serve-backend.wasm >/tmp/serve-backend.log 2>&1 &"
						+ " wasmtime serve -W gc=y -S http=y --addr 127.0.0.1:8084 /tmp/serve-proxy.wasm >/tmp/serve-proxy.log 2>&1 &"
						+ " for i in $(seq 1 60); do out=$(curl -s http://127.0.0.1:8084/) && [ -n \"$out\" ]"
						+ " && { echo \"$out\"; exit 0; }; sleep 0.25; done;"
						+ " cat /tmp/serve-backend.log /tmp/serve-proxy.log 1>&2; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve fetch-inside-serve round trip; log: %s", result.getStderr())
			.isZero();
		assertThat(result.getStdout().trim()).isEqualTo("proxied backend /up 200");
	}

	@Test
	void componentFileWriteThenRead() throws Exception {
		// Component mode does file I/O over wasi:filesystem@0.3.0 (read-via-stream /
		// append-via-stream, driven through stream/future): the adapter maps the
		// preview1 path_open/fd_read/fd_write/fd_close onto WASI 0.3.
		String code = """
				(with-open-file (out "cfile.txt" :direction :output)
				  (write-line "hello" out)
				  (write-line "world" out))
				(with-open-file (in "cfile.txt")
				  (print (read-line in))
				  (print (read-line in))
				  (print (read-line in)))
				""";
		assertThat(compileAndRunComponentWithDir(code)).isEqualTo("\"hello\"\n\"world\"\nnil");
	}

	@Test
	void componentReadLinesInLoop() throws Exception {
		String code = """
				(with-open-file (out "cloop.txt" :direction :output)
				  (write-line "a" out)
				  (write-line "b" out)
				  (write-line "c" out))
				(with-open-file (in "cloop.txt")
				  (setq line (read-line in))
				  (while line
				    (princ line)
				    (setq line (read-line in))))
				""";
		assertThat(compileAndRunComponentWithDir(code)).isEqualTo("abc");
	}

	@Test
	void componentBinaryRoundTrip() throws Exception {
		// The adapter's fd_read/fd_write are byte-clean, so binary data (including NUL,
		// LF and the quote byte) passes through unchanged in component mode too.
		String code = """
				(with-open-file (out "cbin.dat" :direction :output :element-type '(unsigned-byte 8))
				  (write-byte 0 out)
				  (write-byte 10 out)
				  (write-byte 34 out)
				  (write-byte 255 out))
				(with-open-file (in "cbin.dat" :element-type '(unsigned-byte 8))
				  (print (read-byte in))
				  (print (read-byte in))
				  (print (read-byte in))
				  (print (read-byte in))
				  (print (read-byte in nil nil)))
				""";
		assertThat(compileAndRunComponentWithDir(code)).isEqualTo("0\n10\n34\n255\nnil");
	}

	@Test
	void componentLoadFromFile() throws Exception {
		// load reads and evaluates a file; the defined function is resolved via the eval
		// runtime, so the program is compiled in --dynamic mode.
		List<LispVal> program = LispReader.readAllFromString("(load \"clib.lisp\")\n(print (sq 9))");
		byte[] componentBytes = new WasmLispCompiler(true, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/test.component.wasm");
		wasmtime.copyFileToContainer(
				Transferable.of("(defun sq (x) (* x x))".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				"/tmp/clib.lisp");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"cd /tmp && wasmtime run -W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y --dir . test.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("81");
	}

	private static String compileAndRunComponentWithStdin(String lispCode, String stdin) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/test.component.wasm");
		wasmtime.copyFileToContainer(Transferable.of(stdin.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				"/tmp/stdin.txt");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime run -W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y /tmp/test.component.wasm < /tmp/stdin.txt");
		assertThat(result.getExitCode()).as("exit code for component: %s\nstderr: %s", lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	@Test
	void componentReadLineFromStdin() throws Exception {
		// Component mode reads stdin through wasi:cli/stdin@0.3.0 (read-via-stream).
		assertThat(compileAndRunComponentWithStdin("(print (read-line))", "hello-stdin\n"))
			.isEqualTo("\"hello-stdin\"");
	}

	@Test
	void componentReadExprFromStdin() throws Exception {
		assertThat(compileAndRunComponentWithStdin("(print (+ 1 (read)))", "41\n")).isEqualTo("42");
	}

	@Test
	void addition() throws Exception {
		assertThat(compileAndRun("(print (+ 1 2))")).isEqualTo("3");
	}

	@Test
	void defvarDefinesGlobal() throws Exception {
		assertThat(compileAndRun("(defvar *x* 42) (print *x*)")).isEqualTo("42");
	}

	@Test
	void defvarReturnsName() throws Exception {
		assertThat(compileAndRun("(print (defvar *x* 42))")).isEqualTo("*x*");
	}

	@Test
	void defvarIsIdempotent() throws Exception {
		assertThat(compileAndRun("(defvar *x* 1) (defvar *x* 2) (print *x*)")).isEqualTo("1");
	}

	@Test
	void defparameterAlwaysAssigns() throws Exception {
		assertThat(compileAndRun("(defparameter *x* 1) (defparameter *x* 2) (print *x*)")).isEqualTo("2");
	}

	@Test
	void defconstant() throws Exception {
		assertThat(compileAndRun("(defconstant +k+ 7) (print +k+)")).isEqualTo("7");
	}

	@Test
	void globalReadInsideFunction() throws Exception {
		// A defparameter global referenced inside a defun body must resolve (previously
		// failed to compile: "Cannot compile symbol: *k*").
		assertThat(compileAndRun("(defparameter *k* 3) (defun f (x) (* x *k*)) (print (f 5))")).isEqualTo("15");
	}

	@Test
	void globalAssignInsideFunctionVisibleAtTopLevel() throws Exception {
		assertThat(compileAndRun(
				"(defvar *acc* 0) (defun bump () (setq *acc* (+ *acc* 1))) (bump) (bump) (bump) (print *acc*)"))
			.isEqualTo("3");
	}

	@Test
	void globalReadInsideLambda() throws Exception {
		assertThat(compileAndRun(
				"(defparameter *base* 10) (defun adders (xs) (mapcar (lambda (x) (+ x *base*)) xs)) (print (adders '(1 2 3)))"))
			.isEqualTo("(11 12 13)");
	}

	@Test
	void doStar() throws Exception {
		assertThat(compileAndRun("(print (do* ((i 1 (+ i 1)) (acc i (* acc i))) ((> i 5) acc)))")).isEqualTo("720");
	}

	@Test
	void delete() throws Exception {
		assertThat(compileAndRun("(print (delete 2 '(1 2 3 2 1)))")).isEqualTo("(1 3 1)");
		assertThat(compileAndRun("(print (delete-if #'evenp '(1 2 3 4 5)))")).isEqualTo("(1 3 5)");
		assertThat(compileAndRun("(print (delete-if-not #'oddp '(1 2 3 4 5)))")).isEqualTo("(1 3 5)");
	}

	@Test
	void substitute() throws Exception {
		assertThat(compileAndRun("(print (substitute 0 2 '(1 2 3 2 1)))")).isEqualTo("(1 0 3 0 1)");
		assertThat(compileAndRun("(print (nsubstitute 9 1 '(1 2 1 3)))")).isEqualTo("(9 2 9 3)");
	}

	@Test
	void destructiveListOps() throws Exception {
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
		assertThat(compileAndRun("(print (eql 1/2 1/2))")).isEqualTo("t");
		assertThat(compileAndRun("(print (eq 1/2 1/2))")).isEqualTo("nil");
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
	void prog1() throws Exception {
		assertThat(compileAndRun("(print (prog1 1 2 3))")).isEqualTo("1");
		assertThat(compileAndRun("(print (prog1 99))")).isEqualTo("99");
		assertThat(compileAndRun("(print (let ((x (list 1 2 3))) (prog1 (car x) (setq x (cdr x)))))")).isEqualTo("1");
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
	void formatExponential() throws Exception {
		assertThat(compileAndRun("(format t \"~e ~,4e ~e ~,2e ~e\" pi pi 1234.5 9.999 0.0)"))
			.isEqualTo("3.141593e+0 3.1416e+0 1.2345e+3 1.00e+1 0.0e+0");
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
	void formatDollarAndFixed() throws Exception {
		assertThat(compileAndRun("(format t \"~$ ~5$ ~,2f ~v$\" 3.14159 3.14159 3.14159 3 3.14159)"))
			.isEqualTo("3.14 3.14159 3.14 3.142");
	}

	@Test
	void formatDecimalModifiers() throws Exception {
		assertThat(compileAndRun("(format t \"~:d ~@d ~:@d\" 1000000 1000000 1000000)"))
			.isEqualTo("1,000,000 +1000000 +1,000,000");
	}

	@Test
	void formatPadding() throws Exception {
		assertThat(compileAndRun("(format t \"~10a|~10@a|~5,'0d|\" \"foo\" \"foo\" 42)"))
			.isEqualTo("foo       |       foo|00042|");
	}

	@Test
	void formatFreshLine() throws Exception {
		assertThat(compileAndRun("(format t \"a\") (format t \"~&b~&c~%\") (fresh-line) (princ \"d\")"))
			.isEqualTo("a\nb\nc\nd");
	}

	@Test
	void formatEdges() throws Exception {
		// Negative-width padding, a custom comma character and a runtime (v) width
		// (integers stay within the i31 range the WASM backend supports).
		assertThat(compileAndRun("(format t \"[~6d][~,,'.:d][~va]\" -42 1234567 8 \"hi\")"))
			.isEqualTo("[   -42][1.234.567][hi      ]");
	}

	@Test
	void formatRadix() throws Exception {
		assertThat(compileAndRun("(format t \"~x ~o ~b ~8r ~x [~8,'0x]\" 255 256 10 4096 -255 255)"))
			.isEqualTo("FF 400 1010 10000 -FF [000000FF]");
	}

	@Test
	void formatCharacter() throws Exception {
		assertThat(compileAndRun("(format t \"~c ~@c ~:c ~:c\" #\\a #\\b #\\Newline #\\z)"))
			.isEqualTo("a #\\b Newline z");
	}

	@Test
	void formatCaseConversion() throws Exception {
		assertThat(compileAndRun(
				"(format t \"~(~a~) ~:@(~a~) ~:(~a~) ~@(~a~)\" \"FOO BAR\" \"foo bar\" \"foo bar\" \"foo BAR\")"))
			.isEqualTo("foo bar FOO BAR Foo Bar Foo bar");
	}

	@Test
	void formatConditional() throws Exception {
		assertThat(compileAndRun("(format t \"~[a~a~;b~a~:;c~a~]|~:[no~a~;yes~a~]|~@[v=~a~] ~a\" 1 10 nil 20 nil 30)"))
			.isEqualTo("b10|no20| 30");
		assertThat(compileAndRun("(format t \"~#[none~;one ~a~;two ~a ~a~]\" 5 6)")).isEqualTo("two 5 6");
	}

	@Test
	void formatIteration() throws Exception {
		assertThat(compileAndRun("(format t \"~{<~a>~}|~2{ ~a~}|~:{(~a,~a)~}\" '(1 2) '(a b c d) '((x 1) (y 2)))"))
			.isEqualTo("<1><2>| a b|(x,1)(y,2)");
		assertThat(compileAndRun("(format t \"x~2@{ ~a~}|~:@{(~a)~}\" 1 2 '(3) '(4))")).isEqualTo("x 1 2|(3)(4)");
	}

	@Test
	void formatArgumentJump() throws Exception {
		assertThat(compileAndRun("(format t \"~a ~2* ~a ~2:* ~a\" 1 2 3 4)")).isEqualTo("1  4  3");
	}

	@Test
	void formatRuntimePadCharAndExponentParams() throws Exception {
		assertThat(compileAndRun("(format t \"~v,vd [~15,5,3e] [~8,4,,,'*e]\" 6 #\\0 42 pi pi)"))
			.isEqualTo("000042 [   3.14159e+000] [********]");
	}

	@Test
	void formatGeneralFloat() throws Exception {
		// The fixed branch uses the backend's native float printing; keep to simple
		// values that print identically everywhere.
		assertThat(compileAndRun("(format t \"~g ~g ~g\" 0.5 0.00012345 0.0)")).isEqualTo("0.5 1.2345e-4 0.0");
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
	void mapListOverLists() throws Exception {
		assertThat(compileAndRun("(print (map 'list #'+ '(1 2 3) '(10 20 30)))")).isEqualTo("(11 22 33)");
	}

	@Test
	void mapListStopsAtShortestSequence() throws Exception {
		assertThat(compileAndRun("(print (map 'list #'+ '(1 2 3) '(10 20)))")).isEqualTo("(11 22)");
	}

	@Test
	void mapStringOverString() throws Exception {
		assertThat(compileAndRun("(print (map 'string #'char-upcase \"abc\"))")).isEqualTo("\"ABC\"");
	}

	@Test
	void mapListOverString() throws Exception {
		assertThat(compileAndRun("(print (map 'list (lambda (c) (char-code c)) \"AB\"))")).isEqualTo("(65 66)");
	}

	@Test
	void mapNilCallsForEffect() throws Exception {
		assertThat(compileAndRun("(map nil #'print '(7 8 9))")).isEqualTo("7\n8\n9");
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
	void carCdrOfNil() throws Exception {
		assertThat(compileAndRun("(print (car nil))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (cdr nil))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (car '()))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (cdr '()))")).isEqualTo("nil");
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
	void exponentFloatLiteral() throws Exception {
		// Common Lisp exponent-marker float literals all compile to a double.
		assertThat(compileAndRun("(print 1d0)")).isEqualTo("1.0");
		assertThat(compileAndRun("(print (* 2 1d0))")).isEqualTo("2.0");
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
	void eqlSameInteger() throws Exception {
		assertThat(compileAndRun("(print (if (eql 3 3) 42 99))")).isEqualTo("42");
	}

	@Test
	void eqlDifferentTypeNumbers() throws Exception {
		assertThat(compileAndRun("(print (if (eql 3 3.0) 42 99))")).isEqualTo("99");
	}

	@Test
	void eqlSymbols() throws Exception {
		assertThat(compileAndRun("(print (if (eql 'foo 'foo) 42 99))")).isEqualTo("42");
	}

	@Test
	void eqlAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (funcall #'eql 5 5))")).isEqualTo("t");
	}

	@Test
	void eqlFloatsByValue() throws Exception {
		assertThat(compileAndRun("(print (eql 1.5 1.5))")).isEqualTo("t");
	}

	@Test
	void eqFloatsNotEq() throws Exception {
		assertThat(compileAndRun("(print (eq 1.5 1.5))")).isEqualTo("nil");
	}

	@Test
	void eqIntegersStillEq() throws Exception {
		assertThat(compileAndRun("(print (eq 3 3))")).isEqualTo("t");
	}

	@Test
	void equalNestedLists() throws Exception {
		assertThat(compileAndRun("(print (equal '(1 2 (3)) '(1 2 (3))))")).isEqualTo("t");
	}

	@Test
	void equalDifferentLists() throws Exception {
		assertThat(compileAndRun("(print (equal '(1 2) '(1 3)))")).isEqualTo("nil");
	}

	@Test
	void equalStrings() throws Exception {
		assertThat(compileAndRun("(print (equal \"abc\" \"abc\"))")).isEqualTo("t");
	}

	@Test
	void equalDifferentTypeNumbers() throws Exception {
		assertThat(compileAndRun("(print (equal 3 3.0))")).isEqualTo("nil");
	}

	@Test
	void equalFreshConsesUnlikeEql() throws Exception {
		assertThat(compileAndRun("(print (eql (list 1 2) (list 1 2)))")).isEqualTo("nil");
		assertThat(compileAndRun("(print (equal (list 1 2) (list 1 2)))")).isEqualTo("t");
	}

	@Test
	void equalAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (funcall #'equal '(1) '(1)))")).isEqualTo("t");
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
		assertThat(compileAndRun("(print (reduce #'+ '(1 2 3 4 5) :initial-value 0))")).isEqualTo("15");
	}

	@Test
	void reduceWithBuiltinMul() throws Exception {
		assertThat(compileAndRun("(print (reduce #'* '(1 2 3 4 5) :initial-value 1))")).isEqualTo("120");
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
	void caseSingleKey() throws Exception {
		assertThat(compileAndRun("(print (case 2 (1 'one) (2 'two) (3 'three)))")).isEqualTo("two");
	}

	@Test
	void caseKeyList() throws Exception {
		assertThat(compileAndRun("(print (case 3 (1 'one) ((2 3 4) 'small) (otherwise 'big)))")).isEqualTo("small");
	}

	@Test
	void caseOtherwise() throws Exception {
		assertThat(compileAndRun("(print (case 99 (1 'one) ((2 3 4) 'small) (otherwise 'big)))")).isEqualTo("big");
	}

	@Test
	void caseNoMatchReturnsNil() throws Exception {
		assertThat(compileAndRun("(print (case 5 (1 'a) (2 'b)))")).isEqualTo("nil");
	}

	@Test
	void dolistResultForm() throws Exception {
		assertThat(compileAndRun("(print (dolist (e '(1 2) 99)))")).isEqualTo("99");
	}

	@Test
	void doLoop() throws Exception {
		assertThat(compileAndRun("(print (do ((i 0 (+ i 1)) (s 0)) ((= i 5) s) (setq s (+ s i))))")).isEqualTo("10");
	}

	@Test
	void doParallelStep() throws Exception {
		assertThat(compileAndRun("(print (do ((i 0 (+ i 1)) (a 0 b) (b 1 (+ a b))) ((= i 10) a)))")).isEqualTo("55");
	}

	@Test
	void returnFromDolist() throws Exception {
		assertThat(compileAndRun("(print (dolist (m '(2 3 5) t) (if (= m 3) (return))))")).isEqualTo("nil");
	}

	@Test
	void returnWithValue() throws Exception {
		assertThat(compileAndRun("(print (dotimes (i 5 -1) (if (evenp i) (return i))))")).isEqualTo("0");
	}

	@Test
	void returnFromDo() throws Exception {
		assertThat(compileAndRun("(print (do ((i 0 (+ i 1))) ((> i 100) -1) (if (= i 4) (return i))))")).isEqualTo("4");
	}

	@Test
	void returnExitsInnermostLoopOnly() throws Exception {
		assertThat(compileAndRun("""
				(setq total 0)
				(dolist (a '(1 2 3))
				  (dolist (b '(10 20 30))
				    (if (= b 20) (return))
				    (setq total (+ total b))))
				(print total)""")).isEqualTo("30");
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
	void memberWithTestKeyword() throws Exception {
		assertThat(compileAndRun("(print (member '(a d) '((a b) (a c) (a d) (a e)) :test 'equal)) "
				+ "(print (member '(a d) '((a b) (a c) (a d) (a e))))"))
			.isEqualTo("((a d) (a e))\nnil");
	}

	@Test
	void findFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (find 3 '(1 2 3 4))) (print (find 9 '(1 2 3))) (print (funcall #'find 2 '(1 2 3)))"))
			.isEqualTo("3\nnil\n2");
	}

	@Test
	void findIfFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (find-if #'evenp '(1 3 5 6 7))) (print (find-if #'oddp '(2 4 6))) (print (funcall #'find-if #'plusp '(-1 -2 3 4)))"))
			.isEqualTo("6\nnil\n3");
	}

	@Test
	void findIfNotFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (find-if-not #'evenp '(2 4 5 6))) (print (find-if-not #'plusp '(1 2 3))) (print (funcall #'find-if-not #'oddp '(1 3 4)))"))
			.isEqualTo("5\nnil\n4");
	}

	@Test
	void positionFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (position 3 '(1 2 3 4))) (print (position 9 '(1 2 3))) (print (funcall #'position 2 '(5 2 8)))"))
			.isEqualTo("2\nnil\n1");
	}

	@Test
	void positionOnString() throws Exception {
		assertThat(compileAndRun(
				"(print (position #\\space \"hello world\")) (print (position #\\z \"abc\")) (print (funcall #'position #\\l \"hello\"))"))
			.isEqualTo("5\nnil\n2");
	}

	@Test
	void runtimeInternTableSurvivesLargeStaticData() throws Exception {
		// A large program pushes the static string segment past the runtime intern
		// table's historical fixed base (8192); runtime-interned symbols then clobbered
		// static strings and the eval function registry. Pin that the intern table is
		// relocated above the static data: after inflating the segment well past 8KiB
		// and interning hundreds of fresh symbols at runtime, every static string still
		// prints intact and an eval call through the registry still resolves.
		StringBuilder program = new StringBuilder();
		StringBuilder expected = new StringBuilder();
		program.append("(defun bigdata-probe (a &rest r) (list a r))\n");
		StringBuilder syms = new StringBuilder();
		for (int i = 0; i < 300; i++) {
			syms.append(" fresh-sym-").append(i);
		}
		program.append("(print (car (read-from-string \"(").append(syms).append(")\")))\n");
		expected.append("fresh-sym-0\n");
		for (int i = 0; i < 200; i++) {
			String pad = "inflate-" + i + "-abcdefghijklmnopqrstuvwxyz-0123456789";
			program.append("(print \"").append(pad).append("\")\n");
			expected.append('"').append(pad).append("\"\n");
		}
		program.append("(print (eval (quote (bigdata-probe 1 2 3))))\n");
		expected.append("(1 (2 3))");
		assertThat(compileAndRun(program.toString())).isEqualTo(expected.toString());
	}

	@Test
	void scanFunctionsOnStrings() throws Exception {
		assertThat(compileAndRun("""
				(print (find #\\l "hello"))
				(print (find-if #'digit-char-p "ab3c"))
				(print (find-if-not #'digit-char-p "12a3"))
				(print (position-if #'digit-char-p "ab3c"))
				(print (count #\\a "banana"))
				(print (count-if #'digit-char-p "a1b2"))
				(print (every #'digit-char-p "12a"))
				(print (some #'digit-char-p "abc1"))
				(print (notany #'digit-char-p "ab1"))
				(print (notevery #'digit-char-p "12a"))
				(print (reduce (lambda (acc c) (if (char= c #\\a) (+ acc 1) acc)) "banana" :initial-value 0))"""))
			.isEqualTo("#\\l\n#\\3\n#\\a\n2\n3\n2\nnil\n1\nnil\nt\n3");
	}

	@Test
	void sequenceReturningFunctionsOnStrings() throws Exception {
		assertThat(compileAndRun("""
				(print (reverse "abc"))
				(print (remove #\\l "hello"))
				(print (remove-if #'digit-char-p "a1b2"))
				(print (remove-if-not #'digit-char-p "a1b2"))
				(print (remove-duplicates "banana"))
				(print (substitute #\\o #\\a "banana"))
				(print (sort "cab" #'char<))
				(print (funcall #'reverse "abc"))"""))
			.isEqualTo("\"cba\"\n\"heo\"\n\"ab\"\n\"12\"\n\"bna\"\n\"bonono\"\n\"abc\"\n\"cba\"");
	}

	@Test
	void positionIfFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (position-if #'evenp '(1 3 5 6 7))) (print (position-if #'plusp '(-1 -2 -3))) (print (funcall #'position-if #'oddp '(2 4 5)))"))
			.isEqualTo("3\nnil\n2");
	}

	@Test
	void countFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (count 2 '(1 2 3 2 2))) (print (count 9 '(1 2 3))) (print (funcall #'count 2 '(2 2 8)))"))
			.isEqualTo("3\n0\n2");
	}

	@Test
	void countIfFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (count-if #'evenp '(1 2 3 4 5 6))) (print (count-if #'oddp '(2 4 6))) (print (funcall #'count-if #'evenp '(2 2 8 1)))"))
			.isEqualTo("3\n0\n3");
	}

	@Test
	void assocFunction() throws Exception {
		assertThat(compileAndRun("(print (assoc 'b '((a 1) (b 2) (c 3)))) (print (assoc 'z '((a 1))))"))
			.isEqualTo("(b 2)\nnil");
	}

	@Test
	void assocOnDottedAlistLiteral() throws Exception {
		assertThat(compileAndRun(
				"(print (assoc 'b '((a . 1) (b . 2) (c . 3)))) (print (cdr (assoc 'b '((a . 1) (b . 2)))))"))
			.isEqualTo("(b . 2)\n2");
	}

	@Test
	void assocWithTest() throws Exception {
		assertThat(compileAndRun("(print (assoc \"b\" '((\"a\" . 1) (\"b\" . 2)) :test #'equal)) "
				+ "(print (assoc \"z\" '((\"a\" . 1)) :test 'equal)) (print (funcall #'assoc 'b '((a . 1) (b . 2))))"))
			.isEqualTo("(\"b\" . 2)\nnil\n(b . 2)");
	}

	@Test
	void rassocWithTest() throws Exception {
		assertThat(compileAndRun("(print (rassoc \"x\" '((a . \"w\") (b . \"x\")) :test #'equal)) "
				+ "(print (rassoc \"z\" '((a . \"w\")) :test 'equal)) (print (funcall #'rassoc 2 '((a . 1) (b . 2))))"))
			.isEqualTo("(b . \"x\")\nnil\n(b . 2)");
	}

	@Test
	void assocWithKey() throws Exception {
		assertThat(compileAndRun("(print (assoc 2 '((1 . a) (2 . b) (3 . c)) :key (lambda (k) (+ k 1)))) "
				+ "(print (member 3 '((1 2) (3 4) (5 6)) :key #'car)) "
				+ "(print (rassoc 2 '((a . 1) (b . 3)) :key (lambda (v) (- v 1))))"))
			.isEqualTo("(1 . a)\n((3 4) (5 6))\n(b . 3)");
	}

	@Test
	void sequenceFunctionsWithTest() throws Exception {
		assertThat(compileAndRun("(print (find \"b\" '(\"a\" \"b\" \"c\") :test #'string=)) "
				+ "(print (position \"b\" '(\"a\" \"b\" \"c\") :test #'string=)) "
				+ "(print (count \"a\" '(\"a\" \"b\" \"a\") :test #'string=)) "
				+ "(print (remove \"b\" '(\"a\" \"b\" \"c\") :test #'string=)) "
				+ "(print (delete \"b\" (list \"a\" \"b\" \"c\") :test #'string=)) "
				+ "(print (remove-duplicates '(\"a\" \"b\" \"a\" \"c\") :test #'string=)) "
				+ "(print (substitute \"X\" \"b\" '(\"a\" \"b\" \"c\") :test #'string=)) "
				+ "(print (nsubstitute \"X\" \"b\" (list \"a\" \"b\") :test #'string=)) "
				+ "(print (union '(\"a\" \"b\") '(\"b\" \"c\") :test #'string=)) "
				+ "(print (intersection '(\"a\" \"b\") '(\"b\" \"c\") :test #'string=)) "
				+ "(print (set-difference '(\"a\" \"b\") '(\"b\" \"c\") :test #'string=)) "
				+ "(print (adjoin \"a\" '(\"a\" \"b\") :test #'string=)) "
				+ "(print (adjoin \"z\" '(\"a\" \"b\") :test #'string=))"))
			.isEqualTo("\"b\"\n1\n2\n(\"a\" \"c\")\n(\"a\" \"c\")\n(\"b\" \"a\" \"c\")\n(\"a\" \"X\" \"c\")\n"
					+ "(\"a\" \"X\")\n(\"c\" \"a\" \"b\")\n(\"b\")\n(\"a\")\n(\"a\" \"b\")\n(\"z\" \"a\" \"b\")");
	}

	@Test
	void sequenceFunctionsWithKey() throws Exception {
		assertThat(compileAndRun("(print (find 4 '((1 2) (3 4)) :key #'cadr)) "
				+ "(print (position 3 '(1 2 3 4) :key (lambda (x) (- x 1)))) "
				+ "(print (count 2 '((1) (2) (2) (3)) :key #'car)) "
				+ "(print (remove 1 '((1 a) (2 b) (1 c)) :key #'car)) "
				+ "(print (delete 1 (list '(1 a) '(2 b)) :key #'car)) "
				+ "(print (remove-duplicates '((1 a) (2 b) (1 c)) :key #'car)) "
				+ "(print (substitute 'x 2 '((1) (2) (3)) :key #'car)) "
				+ "(print (nsubstitute 'x 2 (list '(1) '(2)) :key #'car)) "
				+ "(print (union '((1)) '((1) (2)) :test #'equal :key #'car)) "
				+ "(print (intersection '((1) (2)) '((2) (3)) :key #'car)) "
				+ "(print (set-difference '((1) (2)) '((2) (3)) :key #'car)) "
				+ "(print (adjoin '(1 x) '((1 a) (2 b)) :key #'car))"))
			.isEqualTo("(3 4)\n3\n2\n((2 b))\n((2 b))\n((2 b) (1 c))\n((1) x (3))\n((1) x)\n((2) (1))\n"
					+ "((2))\n((1))\n((1 a) (2 b))");
	}

	@Test
	void aconsAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (funcall #'acons 'a 1 '((b . 2))))")).isEqualTo("((a . 1) (b . 2))");
	}

	@Test
	void quotedCharacterList() throws Exception {
		assertThat(compileAndRun("(print '(#\\a #\\b)) (print (char= (car '(#\\a #\\b)) #\\a)) (print '(#\\a . #\\b))"))
			.isEqualTo("(#\\a #\\b)\nt\n(#\\a . #\\b)");
	}

	@Test
	void improperCallFormFails() {
		assertThatThrownBy(() -> compileAndRun("(+ 1 . 2)")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("Improper list in call position");
	}

	@Test
	void pairlisFunction() throws Exception {
		assertThat(compileAndRun("(print (pairlis '(a b c) '(1 2 3))) (print (pairlis '(a b) '(1 2) '((c . 3)))) "
				+ "(print (pairlis nil nil)) (print (pairlis '(a b) '(1))) (print (funcall #'pairlis '(a) '(1)))"))
			.isEqualTo("((a . 1) (b . 2) (c . 3))\n((a . 1) (b . 2) (c . 3))\nnil\n((a . 1))\n((a . 1))");
	}

	@Test
	void copyAlistFunction() throws Exception {
		assertThat(compileAndRun("""
				(print (copy-alist '((a . 1) (b . 2))))
				(print (copy-alist nil))
				(let* ((orig (list (cons 'a 1) (cons 'b 2)))
				       (copy (copy-alist orig)))
				  (rplacd (assoc 'a copy) 99)
				  (print (cdr (assoc 'a orig))))
				(print (funcall #'copy-alist '((a . 1))))""")).isEqualTo("((a . 1) (b . 2))\nnil\n1\n((a . 1))");
	}

	@Test
	void lastFunction() throws Exception {
		assertThat(compileAndRun("(print (last '(1 2 3))) (print (last nil))")).isEqualTo("(3)\nnil");
	}

	@Test
	void memberIfFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (member-if #'oddp '(2 4 5 6))) (print (member-if #'evenp '(1 3 5))) (print (funcall #'member-if #'plusp '(-1 3 4)))"))
			.isEqualTo("(5 6)\nnil\n(3 4)");
	}

	@Test
	void assocIfFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (assoc-if #'oddp '((2 a) (3 b) (5 c)))) (print (assoc-if #'evenp '((1 a) (3 b)))) (print (funcall #'assoc-if #'plusp '((-1 a) (2 b))))"))
			.isEqualTo("(3 b)\nnil\n(2 b)");
	}

	@Test
	void getfFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (getf '(:a 1 :b 2) :b)) (print (getf '(:a 1) :x)) (print (funcall #'getf '(:x 10 :y 20) :y))"))
			.isEqualTo("2\nnil\n20");
	}

	@Test
	void removeDuplicatesFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (remove-duplicates '(1 2 1 3))) (print (remove-duplicates '(1 2 3))) (print (funcall #'remove-duplicates '(a b a a c)))"))
			.isEqualTo("(2 1 3)\n(1 2 3)\n(b a c)");
	}

	@Test
	void butlastFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (butlast '(1 2 3))) (print (butlast '(1))) (print (butlast nil)) (print (funcall #'butlast '(a b c d)))"))
			.isEqualTo("(1 2)\nnil\nnil\n(a b c)");
	}

	@Test
	void nconcFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (nconc (list 1 2) (list 3 4))) (print (nconc nil (list 1 2))) (print (funcall #'nconc (list 'a) (list 'b 'c)))"))
			.isEqualTo("(1 2 3 4)\n(1 2)\n(a b c)");
	}

	@Test
	void identityFunction() throws Exception {
		assertThat(compileAndRun("(print (identity 42)) (print (identity '(1 2 3))) (print (funcall #'identity 'x))"))
			.isEqualTo("42\n(1 2 3)\nx");
	}

	@Test
	void copyListFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (copy-list '(1 2 3))) (print (copy-list nil)) (print (funcall #'copy-list '(a b)))"))
			.isEqualTo("(1 2 3)\nnil\n(a b)");
	}

	@Test
	void nreverseFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (nreverse '(1 2 3))) (print (nreverse nil)) (print (funcall #'nreverse '(a b c)))"))
			.isEqualTo("(3 2 1)\nnil\n(c b a)");
	}

	@Test
	void makeListFunction() throws Exception {
		assertThat(compileAndRun("(print (make-list 3)) (print (make-list 0)) (print (funcall #'make-list 2))"))
			.isEqualTo("(nil nil nil)\nnil\n(nil nil)");
	}

	@Test
	void unionFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (union '(1 2 3) '(2 3 4))) (print (union nil '(1 2))) (print (funcall #'union '(a) '(a b)))"))
			.isEqualTo("(4 1 2 3)\n(2 1)\n(b a)");
	}

	@Test
	void intersectionFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (intersection '(1 2 3) '(2 3 4))) (print (intersection '(1 2) '(3 4))) (print (funcall #'intersection '(a b c) '(b c d)))"))
			.isEqualTo("(3 2)\nnil\n(c b)");
	}

	@Test
	void setDifferenceFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (set-difference '(1 2 3) '(2))) (print (set-difference '(1 2 3) '(1 2 3))) (print (funcall #'set-difference '(a b c) '(b)))"))
			.isEqualTo("(3 1)\nnil\n(c a)");
	}

	@Test
	void adjoinFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (adjoin 1 '(2 3))) (print (adjoin 2 '(1 2 3))) (print (adjoin 'a nil)) (print (funcall #'adjoin 5 '(5 6)))"))
			.isEqualTo("(1 2 3)\n(1 2 3)\n(a)\n(5 6)");
	}

	@Test
	void bitwiseOps() throws Exception {
		assertThat(compileAndRun(
				"(print (logand 12 10)) (print (logior 12 10)) (print (logxor 12 10)) (print (lognot 5)) (print (ash 1 4)) (print (ash 255 -4))"))
			.isEqualTo("8\n14\n6\n-6\n16\n15");
	}

	@Test
	void bitwiseVariadicAndFirstClass() throws Exception {
		assertThat(compileAndRun(
				"(print (logand 12 10 6)) (print (logior 1 2 4 8)) (print (funcall #'logand 6 3)) (print (funcall #'lognot 0))"))
			.isEqualTo("0\n15\n2\n-1");
	}

	@Test
	void listStarAndAcons() throws Exception {
		assertThat(compileAndRun(
				"(print (list* 1 2 '(3 4))) (print (list* 1 2 3)) (print (list* 'x)) (print (acons 'a 1 nil))"))
			.isEqualTo("(1 2 3 4)\n(1 2 . 3)\nx\n((a . 1))");
	}

	@Test
	void eltEndpRassoc() throws Exception {
		assertThat(compileAndRun(
				"(print (elt '(a b c) 1)) (print (endp nil)) (print (endp '(1))) (print (rassoc 2 (list (cons 'a 1) (cons 'b 2))))"))
			.isEqualTo("b\nt\nnil\n(b . 2)");
	}

	@Test
	void revappendMaplistMapcon() throws Exception {
		assertThat(compileAndRun(
				"(print (revappend '(1 2 3) '(4 5))) (print (nreconc '(1 2 3) '(4 5))) (print (maplist #'identity '(1 2 3))) (print (mapcon #'(lambda (x) (list (car x))) '(1 2 3)))"))
			.isEqualTo("(3 2 1 4 5)\n(3 2 1 4 5)\n((1 2 3) (2 3) (3))\n(1 2 3)");
	}

	@Test
	void notanyNotevery() throws Exception {
		assertThat(compileAndRun(
				"(print (notany #'evenp '(1 3 5))) (print (notany #'evenp '(1 2 3))) (print (notevery #'evenp '(2 4 5))) (print (notevery #'evenp '(2 4 6)))"))
			.isEqualTo("t\nnil\nt\nnil");
	}

	@Test
	void prog2Psetq() throws Exception {
		assertThat(compileAndRun("(print (prog2 1 2 3)) (print (let ((a 1) (b 2)) (psetq a b b a) (list a b)))"))
			.isEqualTo("2\n(2 1)");
	}

	@Test
	void typecaseForm() throws Exception {
		assertThat(compileAndRun(
				"(print (typecase 42 (string \"s\") (integer \"i\") (t \"?\"))) (print (typecase \"x\" (string \"s\") (integer \"i\") (t \"?\"))) (print (typecase 'sym (string \"s\") (integer \"i\") (t \"?\")))"))
			.isEqualTo("\"i\"\n\"s\"\n\"?\"");
	}

	/**
	 * Compiles and runs the program, asserting that it traps (non-zero exit). Used for
	 * the error / exhaustive-case fall-through paths, which abort via
	 * {@code unreachable}.
	 */
	private static void compileAndExpectTrap(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "/tmp/test.wasm");
		assertThat(result.getExitCode()).as("expected a trap for: %s\nstdout: %s", lispCode, result.getStdout())
			.isNotZero();
	}

	@Test
	void ecaseForm() throws Exception {
		assertThat(compileAndRun(
				"(print (ecase 2 (1 \"one\") (2 \"two\") (3 \"three\"))) (print (ecase 'b ((a) \"A\") ((b c) \"BC\")))"))
			.isEqualTo("\"two\"\n\"BC\"");
		compileAndExpectTrap("(print (ecase 9 (1 \"one\") (2 \"two\")))");
	}

	@Test
	void ccaseForm() throws Exception {
		assertThat(compileAndRun("(print (ccase 1 (1 \"one\") (2 \"two\")))")).isEqualTo("\"one\"");
		compileAndExpectTrap("(print (ccase 9 (1 \"one\")))");
	}

	@Test
	void etypecaseForm() throws Exception {
		assertThat(compileAndRun(
				"(print (etypecase 42 (string \"s\") (integer \"i\"))) (print (etypecase \"x\" (string \"s\") (integer \"i\")))"))
			.isEqualTo("\"i\"\n\"s\"");
		compileAndExpectTrap("(print (etypecase 'sym (string \"s\") (integer \"i\")))");
	}

	@Test
	void errorForm() throws Exception {
		compileAndExpectTrap("(error \"boom\")");
		compileAndExpectTrap("(error \"bad value: ~a\" (+ 1 2))");
	}

	@Test
	void declarationsTheAndEvalWhen() throws Exception {
		assertThat(compileAndRun("(declaim (optimize (speed 3))) (proclaim '(special *x*))"
				+ " (defun decl-fn (x) (declare (ignore x)) 42) (print (decl-fn 1))" + " (print (the integer (+ 1 2)))"
				+ " (eval-when (:compile-toplevel :load-toplevel :execute) (defun ew-fn (x) (* x 2)))"
				+ " (print (ew-fn 21))"))
			.isEqualTo("42\n3\n42");
	}

	@Test
	void checkTypeAndAssertForms() throws Exception {
		assertThat(compileAndRun(
				"(let ((n 5)) (check-type n (integer 0 9)) (print \"ok\"))" + " (assert (= 1 1)) (print \"ok2\")"))
			.isEqualTo("\"ok\"\n\"ok2\"");
		compileAndExpectTrap("(let ((n \"5\")) (check-type n integer))");
		compileAndExpectTrap("(assert (= 1 2))");
	}

	@Test
	void fletForms() throws Exception {
		assertThat(compileAndRun("(flet ((sq (x) (* x x)) (dbl (x) (* 2 x)))"
				+ " (print (mapcar #'sq '(1 2 3))) (print (funcall #'dbl 21)) (print (sq (dbl 3))))"))
			.isEqualTo("(1 4 9)\n42\n36");
		// Non-recursive: the definition body sees the OUTER function binding; nested
		// flets shadow lexically.
		assertThat(compileAndRun("(defun shadow-fn (x) (* 100 x))"
				+ " (print (flet ((shadow-fn (x) (if (= x 0) 'zero (shadow-fn 0)))) (shadow-fn 5)))"
				+ " (flet ((g () 1)) (flet ((h () (g))) (flet ((g () 2)) (print (list (g) (h))))))"))
			.isEqualTo("0\n(2 1)");
		assertThat(compileAndRun(
				"(flet ((opt (a &optional (b 10) &rest r) (list a b r)))" + " (print (opt 1)) (print (opt 1 2 3 4)))"
						+ " (let ((base 100)) (flet ((offs (x) (+ base x))) (print (offs 5))))"))
			.isEqualTo("(1 10 nil)\n(1 2 (3 4))\n105");
	}

	@Test
	void labelsForms() throws Exception {
		assertThat(compileAndRun("(labels ((fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))) (print (fact 6)))"
				+ " (labels ((ev (n) (if (= n 0) t (od (- n 1))))"
				+ " (od (n) (if (= n 0) nil (ev (- n 1))))) (print (list (ev 10) (od 10))))"
				+ " (defun count-down (n) (labels ((go-down (i acc) (if (= i 0) acc (go-down (- i 1) (cons i acc)))))"
				+ " (go-down n nil))) (print (count-down 4))"))
			.isEqualTo("720\n(t nil)\n(1 2 3 4)");
	}

	@Test
	void multipleValueForms() throws Exception {
		assertThat(compileAndRun("(setq mv-side 0) (setq mv-primary (values 1 (setq mv-side 9)))"
				+ " (print (list mv-primary mv-side)) (print (values)) (print (funcall #'values 1 2))"
				+ " (multiple-value-bind (a b c) (values 1 2) (print (list a b c)))"
				+ " (multiple-value-bind (q r) (floor 7 2) (print (list q r)))"
				+ " (multiple-value-bind (q r) (truncate -7 2) (print (list q r)))"
				+ " (multiple-value-bind (q r) (floor 7.5) (print (list q r)))"
				+ " (print (multiple-value-list (floor 17 5)))" + " (print (nth-value 1 (floor 7 2)))"
				+ " (print (multiple-value-call #'+ (values 1 2)))" + " (defun mv-collect (&rest args) args)"
				+ " (print (multiple-value-call #'mv-collect 1 (values 2 3) (floor 9 4)))"
				+ " (print (floor 7 2)) (print (round 7 2))"))
			.isEqualTo("(1 9)\nnil\n1\n(1 2 nil)\n(3 1)\n(-3 -1)\n(7 0.5)\n(3 2)\n1\n3\n(1 2 3 2 1)\n3\n4");
	}

	@Test
	void multipleValueGethash() throws Exception {
		// The gethash lowering distinguishes a stored nil from a missing key via a
		// runtime gensym sentinel passed as the gethash default.
		assertThat(compileAndRun(
				"(setq mv-h (make-hash-table))" + " (setf (gethash 'x mv-h) nil) (setf (gethash 'y mv-h) 42)"
						+ " (multiple-value-bind (v p) (gethash 'y mv-h) (print (list v p)))"
						+ " (multiple-value-bind (v p) (gethash 'x mv-h) (print (list v p)))"
						+ " (multiple-value-bind (v p) (gethash 'z mv-h) (print (list v p)))"
						+ " (multiple-value-bind (v p) (gethash 'z mv-h 'dflt) (print (list v p)))"))
			.isEqualTo("(42 t)\n(nil t)\n(nil nil)\n(dflt nil)");
	}

	@Test
	void destructuringBindForms() throws Exception {
		assertThat(compileAndRun("(destructuring-bind (a (b c) d) '(1 (2 3) 4) (print (+ a b c d)))"
				+ " (destructuring-bind (a &optional (b 10) c) '(1) (print (list a b c)))"
				+ " (destructuring-bind (a &rest r) '(1 2 3) (print (list a r)))"
				+ " (destructuring-bind (a &key k (j 5)) '(1 :k 2) (print (list a k j)))"
				+ " (destructuring-bind ((a &key k) b) '((1 :k 2) 3) (print (list a k b)))"
				+ " (defun db-sum (pair) (destructuring-bind (x y) pair (+ x y))) (print (db-sum '(1 2)))"
				+ " (print (mapcar (lambda (p) (destructuring-bind (x y) p (* x y))) '((1 2) (3 4))))"))
			.isEqualTo("10\n(1 10 nil)\n(1 (2 3))\n(1 2 5)\n(1 2 3)\n3\n(2 12)");
	}

	@Test
	void everyFunction() throws Exception {
		assertThat(compileAndRun("(print (every #'evenp '(2 4 6))) (print (every #'evenp '(2 3 6)))"))
			.isEqualTo("t\nnil");
	}

	@Test
	void someFunction() throws Exception {
		assertThat(compileAndRun("(print (some #'oddp '(2 4 5))) (print (some #'oddp '(2 4 6)))")).isEqualTo("t\nnil");
		assertThat(compileAndRun("(print (some (lambda (x) (if (> x 3) (* x 10))) '(1 2 5)))")).isEqualTo("50");
	}

	@Test
	void removeFunction() throws Exception {
		assertThat(compileAndRun("(print (remove 2 '(1 2 3 2 4))) (print (remove 9 '(1 2 3)))"))
			.isEqualTo("(1 3 4)\n(1 2 3)");
	}

	@Test
	void removeIfFunction() throws Exception {
		assertThat(compileAndRun("(print (remove-if #'evenp '(1 2 3 4 5)))")).isEqualTo("(1 3 5)");
	}

	@Test
	void removeIfNotFunction() throws Exception {
		assertThat(compileAndRun("(print (remove-if-not #'evenp '(1 2 3 4 5)))")).isEqualTo("(2 4)");
	}

	@Test
	void removeIfNotAsFirstClass() throws Exception {
		assertThat(compileAndRun("(print (funcall #'remove-if-not #'oddp '(1 2 3 4)))")).isEqualTo("(1 3)");
	}

	@Test
	void removeAsFirstClass() throws Exception {
		assertThat(compileAndRun("(print (funcall #'remove 2 '(1 2 3 2)))")).isEqualTo("(1 3)");
	}

	@Test
	void mapcanFunction() throws Exception {
		assertThat(compileAndRun("(print (mapcan (lambda (x) (list x x)) '(1 2 3)))")).isEqualTo("(1 1 2 2 3 3)");
		assertThat(compileAndRun("(print (mapcan (lambda (x) (if (evenp x) (list x) nil)) '(1 2 3 4)))"))
			.isEqualTo("(2 4)");
		assertThat(compileAndRun("(print (funcall #'mapcan (lambda (x) (list x)) '(1 2 3)))")).isEqualTo("(1 2 3)");
	}

	@Test
	void sortFunction() throws Exception {
		assertThat(compileAndRun("(print (sort '(3 1 4 1 5 9 2 6) #'<))")).isEqualTo("(1 1 2 3 4 5 6 9)");
		assertThat(compileAndRun("(print (sort '(3 1 4) #'>))")).isEqualTo("(4 3 1)");
		assertThat(compileAndRun("(print (sort '() #'<)) (print (sort '(5) #'<))")).isEqualTo("nil\n(5)");
		assertThat(compileAndRun("(print (funcall #'sort '(2 3 1) #'<))")).isEqualTo("(1 2 3)");
	}

	@Test
	void applyFunction() throws Exception {
		// In compiled code apply dispatches by the actual argument count, so the applied
		// function must have a matching arity (the eval-runtime limitation).
		assertThat(compileAndRun("(print (apply #'+ '(1 2)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (apply #'cons 1 '(2)))")).isEqualTo("(1 . 2)");
		assertThat(compileAndRun("(print (apply (lambda (a b) (+ a b)) '(3 4)))")).isEqualTo("7");
		assertThat(compileAndRun("(defun add3 (a b c) (+ a (+ b c))) (print (apply #'add3 1 2 '(3)))")).isEqualTo("6");
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
	void mapcReturnsOriginalList() throws Exception {
		// mapc prints each element (side effect) and returns the original list.
		assertThat(compileAndRun("(print (mapc #'print '(10 20)))")).isEqualTo("10\n20\n(10 20)");
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
	void withOpenFileBinaryRoundTrip() throws Exception {
		// Bytes 0 (NUL), 10 (LF) and 34 (the quote byte) prove the text framing (quote
		// wrapping, newline scan) is bypassed on the binary path.
		String code = """
				(with-open-file (out "bin.dat" :direction :output :element-type '(unsigned-byte 8))
				  (write-byte 0 out)
				  (write-byte 10 out)
				  (write-byte 34 out)
				  (write-byte 255 out))
				(with-open-file (in "bin.dat" :element-type '(unsigned-byte 8))
				  (print (read-byte in))
				  (print (read-byte in))
				  (print (read-byte in))
				  (print (read-byte in))
				  (print (read-byte in nil nil)))
				""";
		assertThat(compileAndRunWithDir(code)).isEqualTo("0\n10\n34\n255\nnil");
	}

	@Test
	void readByteEofValueReturned() throws Exception {
		String code = """
				(with-open-file (out "eofv.dat" :direction :output :element-type '(unsigned-byte 8)))
				(with-open-file (in "eofv.dat" :element-type '(unsigned-byte 8))
				  (print (read-byte in nil -1)))
				""";
		assertThat(compileAndRunWithDir(code)).isEqualTo("-1");
	}

	@Test
	void writeByteReturnsByte() throws Exception {
		String code = """
				(with-open-file (out "wb.dat" :direction :output :element-type '(unsigned-byte 8))
				  (print (write-byte 65 out)))
				""";
		assertThat(compileAndRunWithDir(code)).isEqualTo("65");
	}

	@Test
	void readWriteSequenceRoundTrip() throws Exception {
		String code = """
				(setq buf (make-array 4))
				(setf (aref buf 0) 65)
				(setf (aref buf 1) 0)
				(setf (aref buf 2) 10)
				(setf (aref buf 3) 34)
				(with-open-file (out "seq.dat" :direction :output :element-type '(unsigned-byte 8))
				  (write-sequence buf out))
				(setq buf2 (make-array 8 :initial-element 99))
				(with-open-file (in "seq.dat" :element-type '(unsigned-byte 8))
				  (print (read-sequence buf2 in)))
				(print (aref buf2 0))
				(print (aref buf2 1))
				(print (aref buf2 2))
				(print (aref buf2 3))
				(print (aref buf2 4))
				""";
		assertThat(compileAndRunWithDir(code)).isEqualTo("4\n65\n0\n10\n34\n99");
	}

	@Test
	void readWriteSequenceStartEnd() throws Exception {
		String code = """
				(setq buf (make-array 4))
				(setf (aref buf 0) 1)
				(setf (aref buf 1) 2)
				(setf (aref buf 2) 3)
				(setf (aref buf 3) 4)
				(with-open-file (out "se.dat" :direction :output :element-type '(unsigned-byte 8))
				  (write-sequence buf out :start 1 :end 3))
				(setq buf2 (make-array 4 :initial-element 0))
				(with-open-file (in "se.dat" :element-type '(unsigned-byte 8))
				  (print (read-sequence buf2 in :start 2)))
				(print (aref buf2 2))
				(print (aref buf2 3))
				""";
		assertThat(compileAndRunWithDir(code)).isEqualTo("4\n2\n3");
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

	@Test
	void requireSplicedByLoadInlinerCompilesAndRuns() throws Exception {
		// require/provide are consumed by the compile-time LoadInliner pass (cli), so
		// the WASM module contains the spliced defuns natively; the duplicate require
		// is consumed without splicing again.
		List<LispVal> program = am.ik.rontolisp.cli.LoadInliner
			.inline(LispReader.readAllFromString("(require :util) (require :util) (print (u-sq 8))"), path -> {
				if (!"util.lisp".equals(path)) {
					throw new java.io.FileNotFoundException(path);
				}
				return "(provide :util) (defun u-sq (x) (* x x))";
			});
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), "/tmp/test.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "/tmp/test.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("64");
	}

	@Test
	void requireNotConsumedByInlinerThrows() {
		// One that reaches the compiler (nested, or a unit test bypassing the pass) is
		// a hard error -- unlike load, the compiled runtime reader cannot execute it.
		assertThatThrownBy(() -> new WasmLispCompiler().compile(LispReader.readAllFromString("(if t (require :util))")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("require is only supported as a literal top-level form");
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
		assertThat(compileAndRun("(print (eval '(reduce #'+ (list 1 2 3) :initial-value 100)))")).isEqualTo("106");
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
	void expSoftwareApproximation() throws Exception {
		// WASM has no native exp instruction; it is approximated in f64 (argument
		// reduction + Taylor polynomial), so results match Math.exp closely but not
		// bit-exactly. exp(0) is exactly 1.0.
		assertThat(compileAndRun("(print (exp 0))")).isEqualTo("1.0");
		assertThat(Double.parseDouble(compileAndRun("(print (exp 1))"))).isCloseTo(Math.exp(1), within(1e-4));
		assertThat(Double.parseDouble(compileAndRun("(print (exp 2.0))"))).isCloseTo(Math.exp(2), within(1e-3));
		assertThat(Double.parseDouble(compileAndRun("(print (exp -1.0))"))).isCloseTo(Math.exp(-1), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (exp -5.0))"))).isCloseTo(Math.exp(-5), within(1e-6));
		// A sigmoid built on exp: 1/(1+exp(-x)). sigmoid(0) is exactly 0.5.
		assertThat(compileAndRun("(defun sg (x) (/ 1.0 (+ 1.0 (exp (- 0 x))))) (print (sg 0.0))")).isEqualTo("0.5");
		assertThat(Double.parseDouble(compileAndRun("(defun sg (x) (/ 1.0 (+ 1.0 (exp (- 0 x))))) (print (sg 2.0))")))
			.isCloseTo(1.0 / (1.0 + Math.exp(-2.0)), within(1e-5));
		// exp as a first-class value over an integer argument.
		assertThat(Double.parseDouble(compileAndRun("(print (funcall #'exp 1))"))).isCloseTo(Math.exp(1), within(1e-4));
	}

	@Test
	void sqrt() throws Exception {
		assertThat(compileAndRun("(print (sqrt 16))")).isEqualTo("4.0");
		assertThat(compileAndRun("(print (sqrt 25))")).isEqualTo("5.0");
		assertThat(compileAndRun("(print (sqrt 6.25))")).isEqualTo("2.5");
	}

	@Test
	void floatArithmeticOnNonLiteralOperands() throws Exception {
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
		// Integer and ratio paths are unaffected by the float fast path.
		assertThat(compileAndRun("(defun f (a b) (+ a b)) (print (f 1 2))")).isEqualTo("3");
		assertThat(compileAndRun("(defun f (a b) (/ a b)) (print (f 1 3))")).isEqualTo("1/3");
	}

	@Test
	void random() throws Exception {
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
	void randomOnFloatLimitThroughAVariable() throws Exception {
		// Regression: a float limit reaching random through a variable (no float literal
		// in
		// the argument) used to take the integer path -- which unboxes the limit as an
		// i31
		// -- and trapped at runtime. random now ref.tests the limit and picks the float
		// vs
		// integer path accordingly.
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
	void preview1RandomDrawsFromHostEntropy() throws Exception {
		// Preview 1 mode binds the real wasi_snapshot_preview1 random_get, so the
		// sequence
		// is NOT reproducible across fresh runs. Two runs of a five-sample program over a
		// large range collide with negligible probability (~5^2 / 10^9), so distinct
		// output confirms real entropy is used (regression: it used to be a deterministic
		// LCG that ignored random_get).
		String program = "(dotimes (i 5) (print (random 1000000000)))";
		assertThat(compileAndRun(program)).isNotEqualTo(compileAndRun(program));
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
	void defpackageDefunAndCallAcrossPackages() throws Exception {
		String code = """
				(defpackage :mypkg (:use :cl) (:export :greet :twice))
				(in-package :mypkg)
				(defun greet (name) (concatenate 'string "hello, " name))
				(defun twice (x) (* x 2))
				(defun helper () 42)
				(in-package :cl-user)
				(print (mypkg:greet "world"))
				(print (mapcar #'mypkg:twice '(1 2 3)))
				(print (mypkg::helper))
				(print (rontolisp:list-functions :mypkg))
				""";
		assertThat(compileAndRun(code))
			.isEqualTo("\"hello, world\"\n(2 4 6)\n42\n(mypkg::helper mypkg:greet mypkg:twice)");
	}

	@Test
	void listMacros() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-macros))")).isEqualTo(
				"(and assert case ccase check-type cond decf declaim declare destructuring-bind do do* dolist dotimes ecase error etypecase eval-when flet format incf labels let* loop multiple-value-bind multiple-value-call multiple-value-list nth-value or pop proclaim prog1 prog2 psetq push remf setf the time typecase unless when with-open-file)");
	}

	@Test
	void listSpecialForms() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-special-forms))")).isEqualTo(
				"(defconstant defmacro defpackage defparameter defstruct defun defvar function if in-package lambda let progn quote return setq while)");
	}

	@Test
	void listFunctionsLength() throws Exception {
		assertThat(compileAndRun("(print (length (rontolisp:list-functions)))")).isEqualTo("208");
	}

	@Test
	void gensymReturnsFreshSymbols() throws Exception {
		assertThat(compileAndRun("(print (gensym)) (print (gensym \"tmp\")) (print (eq (gensym) (gensym)))"
				+ "(print (symbolp (gensym))) (print (symbolp (funcall #'gensym)))"))
			.isEqualTo("#:g1\n#:tmp2\nnil\nt\nt");
	}

	@Test
	void gensymRejectsANonLiteralPrefix() {
		assertThatThrownBy(
				() -> new WasmLispCompiler().compile(LispReader.readAllFromString("(setq p \"t\") (gensym p)")))
			.hasMessageContaining("literal string");
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
		assertThat(compileAndRun("(print (rontolisp:list-functions :rontolisp))")).isEqualTo(
				"(await fetch http-handler json-parse json-stringify list-functions list-macros list-special-forms promisep query-param query-params tcp-accept tcp-connect tcp-listen tcp-local-port then tls-connect tls-listen tls-listen-pem url-decode url-encode url-path url-query version)");
		assertThat(compileAndRun("(print (rontolisp:list-special-forms :cl-user))")).isEqualTo("nil");
	}

	@Test
	void listFunctionsForJava() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-functions :java))"))
			.isEqualTo("(call field new proxy static)");
		assertThat(compileAndRun("(print (rontolisp:list-macros :java))")).isEqualTo("nil");
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

	@Test
	void promiseOpsWorkInPreview1Mode() throws Exception {
		// await/then/promisep are generic promise operations that run in Preview 1 too
		// (only fetch itself is component-only): non-promise passthrough, then chains
		// with flattening and at-first-await memoization, promisep, and opaque printing.
		assertThat(compileAndRun("(print (rontolisp:await 42)) (print (rontolisp:await nil))")).isEqualTo("42\nnil");
		assertThat(compileAndRun("(print (rontolisp:await (rontolisp:then 21 (lambda (x) (* x 2)))))")).isEqualTo("42");
		assertThat(compileAndRun(
				"(print (rontolisp:await (rontolisp:then (rontolisp:then 10 (lambda (x) (+ x 1))) (lambda (x) (* x 3)))))"))
			.isEqualTo("33");
		assertThat(compileAndRun(
				"(print (rontolisp:await (rontolisp:then 5 (lambda (x) (rontolisp:then x (lambda (y) (+ y 1)))))))"))
			.isEqualTo("6");
		assertThat(compileAndRun("(setq cnt 0)" + " (let ((p (rontolisp:then 1 (lambda (x) (setq cnt (+ cnt 1)) x))))"
				+ " (rontolisp:await p) (rontolisp:await p) (print cnt))"))
			.isEqualTo("1");
		assertThat(compileAndRun(
				"(print (rontolisp:promisep 42))" + " (print (rontolisp:promisep (rontolisp:then 1 (lambda (x) x))))"
						+ " (print (rontolisp:then 1 (lambda (x) x)))"))
			.isEqualTo("nil\nt\n#<PROMISE>");
	}

	@Test
	void componentFetchUnreachableReturnsNil() throws Exception {
		// A fetch component requires wasi:http (so it must be run with -S http=y) and
		// must
		// handle a failed request without trapping. Fetching an unreachable port
		// exercises
		// the full request-start/poll/response path; the connection error is detected at
		// await time and await returns nil (deterministic, no server).
		String program = "(print (rontolisp:await (rontolisp:fetch \"http://127.0.0.1:1/nope\")))";
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(program));
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/fetch-err.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "component-model-async=y",
				"-W", "component-model-async-stackful=y", "-W", "component-model-more-async-builtins=y", "-S", "http=y",
				"/tmp/fetch-err.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("nil");
	}

	@Test
	void componentFetchRequiresHttpFlag() throws Exception {
		// Without -S http=y the wasi:http imports are unsatisfied, so a fetch component
		// fails to instantiate -- confirming non-fetch components (which do not import
		// wasi:http) keep running without the flag.
		String program = "(print (rontolisp:fetch \"http://127.0.0.1:1/nope\"))";
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(program));
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/fetch-noflag.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "component-model-async=y",
				"-W", "component-model-async-stackful=y", "-W", "component-model-more-async-builtins=y",
				"/tmp/fetch-noflag.component.wasm");
		assertThat(result.getExitCode()).isNotZero();
	}

	@Test
	void componentTcpLoopbackEcho() throws Exception {
		// Full echo round trip over the container's loopback, single-threaded
		// choreography (connect before accept, write before the peer reads): listen on
		// port 0, read the ephemeral port back, connect, exchange a line and a byte,
		// then read-line after the peer closes returns nil. Deterministic -- no
		// external network. Requires -S tcp=y -S inherit-network=y.
		String program = """
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port)))
				  (write-line "hello" client)
				  (write-byte 65 client)
				  (let* ((server (rontolisp:tcp-accept listener))
				         (line (read-line server)))
				    (write-line line server)
				    (print (read-line client))
				    (print (read-byte server))
				    (close client)
				    (print (read-line server))
				    (close server)
				    (close listener)))
				""";
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(program));
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/tcp-echo.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "component-model-async=y",
				"-W", "component-model-async-stackful=y", "-W", "component-model-more-async-builtins=y", "-S", "tcp=y",
				"-S", "inherit-network=y", "/tmp/tcp-echo.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("\"hello\"\n65\nnil");
	}

	@Test
	void componentTcpConnectRefusedReturnsNil() throws Exception {
		// Connecting to a closed port returns nil instead of trapping (the fetch error
		// convention). Deterministic, no server.
		String program = "(print (rontolisp:tcp-connect \"127.0.0.1\" 1))";
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(program));
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/tcp-refused.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "component-model-async=y",
				"-W", "component-model-async-stackful=y", "-W", "component-model-more-async-builtins=y", "-S", "tcp=y",
				"-S", "inherit-network=y", "/tmp/tcp-refused.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("nil");
	}

	@Test
	void componentTcpWithoutNetworkFlagsReturnsNil() throws Exception {
		// Unlike wasi:http (whose absence fails instantiation without -S http=y),
		// wasmtime always hosts wasi:sockets and gates it by permission: a socket
		// component still instantiates without -S tcp / -S inherit-network, and the
		// socket operations fail, so the built-ins yield nil.
		String program = "(print (rontolisp:tcp-listen 0 \"127.0.0.1\"))";
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(program));
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/tcp-noflag.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "component-model-async=y",
				"-W", "component-model-async-stackful=y", "-W", "component-model-more-async-builtins=y",
				"/tmp/tcp-noflag.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("nil");
	}

	@Test
	@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RONTOLISP_HTTP_E2E", matches = "1")
	void componentFetchOverHttp(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
		// Full success-path E2E against a local HTTP server, run with the host's wasmtime
		// (must be on PATH). Opt-in (RONTOLISP_HTTP_E2E=1) and uses local wasmtime rather
		// than the container because reaching a host server from the container needs
		// host-port bridging that is environment-sensitive.
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
			.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/hello", exchange -> {
			byte[] body = "hello-from-fetch".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			if ("abc".equals(exchange.getRequestHeaders().getFirst("X-Custom"))) {
				body = "got-header".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			}
			exchange.getResponseHeaders().add("X-Test", "ok");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		// Echoes "<method>:<request-body>" so the test can verify the method and body are
		// sent.
		server.createContext("/echo", exchange -> {
			String received = new String(exchange.getRequestBody().readAllBytes(),
					java.nio.charset.StandardCharsets.UTF_8);
			byte[] body = (exchange.getRequestMethod() + ":" + received)
				.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			String url = base + "/hello";
			String echo = base + "/echo";
			String program = "(let ((r (rontolisp:await (rontolisp:fetch \"" + url + "\"))))"
					+ " (print (getf r :status))" + " (print (getf r :body)) (print (getf r :headers)))"
					+ " (print (getf (rontolisp:await (rontolisp:fetch \"" + url
					+ "\" (list :headers (list (cons \"X-Custom\" \"abc\"))))) :body))"
					+ " (print (getf (rontolisp:await (rontolisp:fetch \"" + echo
					+ "\" (list :method \"POST\" :body \"hi\"))) :body))"
					// two promises in flight at once, awaited out of order; p1 is awaited
					// twice (the second await must come from the result cache --
					// wasi:http
					// hands out the response only once)
					+ " (let ((p1 (rontolisp:fetch \"" + echo + "\" (list :method \"POST\" :body \"one\")))"
					+ "       (p2 (rontolisp:fetch \"" + echo + "\" (list :method \"POST\" :body \"two\"))))"
					+ "   (print (getf (rontolisp:await p2) :body))" + "   (print (getf (rontolisp:await p1) :body))"
					+ "   (print (getf (rontolisp:await p1) :status)))"
					// a fetch promise chains with then (the callback extracts the status)
					+ " (print (rontolisp:await (rontolisp:then (rontolisp:fetch \"" + url
					+ "\") (lambda (r) (getf r :status)))))";
			byte[] componentBytes = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(program));
			java.nio.file.Path wasm = tempDir.resolve("fetch.component.wasm");
			java.nio.file.Files.write(wasm, componentBytes);
			Process p = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "component-model-async=y", "-W",
					"component-model-async-stackful=y", "-W", "component-model-more-async-builtins=y", "-S", "http=y",
					wasm.toString())
				.redirectErrorStream(true)
				.start();
			String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			p.waitFor();
			assertThat(out).contains("200")
				.contains("\"hello-from-fetch\"")
				.contains("x-test")
				.contains("\"got-header\"")
				.contains("\"POST:hi\"")
				.contains("\"POST:two\"")
				.contains("\"POST:one\"");
		}
		finally {
			server.stop(0);
		}
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
	void compileReadFromStringDottedPair() throws Exception {
		assertThat(compileAndRun("(print (read-from-string \"(a . 1)\")) (print (read-from-string \"(a b . c)\")) "
				+ "(print (read-from-string \"((a . 1) (b . 2))\")) (print (read-from-string \"3.5\"))"))
			.isEqualTo("(a . 1)\n(a b . c)\n((a . 1) (b . 2))\n3.5");
	}

	@Test
	void compileReadFloatLiterals() throws Exception {
		// Regression: the WASM runtime reader parsed only integers, so a float token such
		// as "1.0" was interned as a symbol. floatp returned nil and any arithmetic on
		// the
		// "read" value trapped with a cast failure (e.g. feeding `(render -2.5 1.0 ...)`
		// to
		// the `(print (eval (read)))` driver used by the browser playground).
		assertThat(compileAndRun("(print (read-from-string \"1.5\"))")).isEqualTo("1.5");
		assertThat(compileAndRun("(print (floatp (read-from-string \"1.0\")))")).isEqualTo("t");
		assertThat(compileAndRun("(print (= 1.2 (read-from-string \"1.2\")))")).isEqualTo("t");
		assertThat(compileAndRun("(print (+ (read-from-string \"-2.5\") (read-from-string \"1.0\")))"))
			.isEqualTo("-1.5");
		assertThat(compileAndRun("(print (read-from-string \".5\"))")).isEqualTo("0.5");
		assertThat(compileAndRun("(print (read-from-string \"5.\"))")).isEqualTo("5.0");
		// A token with two dots or non-numeric characters stays a symbol.
		assertThat(compileAndRun("(print (symbolp (read-from-string \"1.2.3\")))")).isEqualTo("t");
		assertThat(compileAndRun("(print (symbolp (read-from-string \"foo.bar\")))")).isEqualTo("t");
		// Integers are unaffected.
		assertThat(compileAndRun("(print (integerp (read-from-string \"30\")))")).isEqualTo("t");
	}

	@Test
	void compileEvalReadFloatArithmetic() throws Exception {
		// The browser "compile & run WASM" playground appends `(print (eval (read)))` and
		// feeds a call expression on stdin; float arguments must round-trip through read.
		assertThat(compileAndRunWithStdinFile("(print (eval (read)))", "(+ 1.0 2.0)\n")).isEqualTo("3.0");
		assertThat(compileAndRunWithStdinFile("(print (eval (read)))", "(< -1.2 1.2)\n")).isEqualTo("t");
	}

	@Test
	void compileEvalResolvesTopLevelGlobalVariable() throws Exception {
		// A top-level setq/defvar global must be visible to the embedded eval runtime
		// (its value is mirrored into GLOBAL_ENV).
		assertThat(compileAndRun("(setq foo 42) (print (eval (quote foo)))")).isEqualTo("42");
		assertThat(compileAndRun("(defvar *g* 99) (print (eval (quote *g*)))")).isEqualTo("99");
		// A closure stored in a top-level global, then funcall'd through eval.
		assertThat(compileAndRun(
				"(defun make-adder (n) (lambda (x) (+ x n))) (setq add10 (make-adder 10)) (print (eval (quote (funcall add10 100))))"))
			.isEqualTo("110");
	}

	@Test
	void compileEvalResolvesGlobalClosureViaReadFuncall() throws Exception {
		// The exact playground scenario: define a closure global, then funcall it from an
		// expression read at runtime.
		assertThat(compileAndRunWithStdinFile(
				"(defun make-adder (n) (lambda (x) (+ x n))) (setq add10 (make-adder 10)) (print (eval (read)))",
				"(funcall add10 100)\n"))
			.isEqualTo("110");
	}

	@Test
	void compileParseIntegerAndReadFromStringAsValues() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'parse-integer (list \"1\" \"2\" \"3\")))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (funcall #'read-from-string \"(a b c)\"))")).isEqualTo("(a b c)");
	}

	@Test
	void compileModWithLargeOperandsFlooredCorrectly() throws Exception {
		// Regression: mod's floored-sign correction must not overflow the i31 range. For
		// large operands (* r b) wraps negative and used to spuriously add the divisor,
		// leaving a result still larger than the divisor.
		assertThat(compileAndRun("(print (mod 843749 65537))")).isEqualTo("57305");
		assertThat(compileAndRun("(print (list (mod -13 4) (mod 13 -4) (mod -13 -4) (mod 13 4)))"))
			.isEqualTo("(3 -3 -1 1)");
	}

	@Test
	void compileHashTablePutGetAndDefault() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *h* (make-hash-table :test 'equal))
				(setf (gethash "a" *h*) 1)
				(setf (gethash "b" *h*) 2)
				(print (list (gethash "a" *h*) (gethash "b" *h*) (gethash "c" *h*)))
				(print (gethash 'x (make-hash-table) 42))
				""")).isEqualTo("(1 2 nil)\n42");
	}

	@Test
	void compileHashTableListKeysIncfCountAndPredicate() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *q* (make-hash-table :test 'equal))
				(setf (gethash (list 0 1 2) *q*) 7)
				(print (gethash (list 0 1 2) *q* 0))
				(defparameter *h* (make-hash-table :test 'equal))
				(dolist (w (list "a" "b" "a" "a" "b")) (incf (gethash w *h* 0)))
				(print (list (gethash "a" *h*) (gethash "b" *h*)))
				(print (list (hash-table-count *h*) (hash-table-p *h*) (hash-table-p 5) (consp *h*)))
				""")).isEqualTo("7\n(3 2)\n(2 t nil nil)");
	}

	@Test
	void compileHashTableMaphashRemhashAndClrhash() throws Exception {
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
				(remhash "b" *h*)
				(print (list (hash-table-count *h*) (gethash "b" *h* 'gone)))
				(clrhash *h*)
				(print (hash-table-count *h*))
				""")).isEqualTo("60\n(2 gone)\n0");
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
	void compileLinalgRankThreeElementwise() throws Exception {
		assertThat(compileAndRunLinalg("""
				(defparameter *c* (linalg:reshape (linalg:arange 8) '(2 2 2)))
				(print (linalg:add *c* 10))
				(print (linalg:sum *c*))
				(print (linalg:array-equal (linalg:flatten *c*) (linalg:arange 8)))
				""")).isEqualTo("#3A(((10 11) (12 13)) ((14 15) (16 17)))\n28\nt");
	}

	@Test
	void compileRankThreeArrayRefSetAndPrint() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *t* (make-array (list 2 2 2) :initial-element 0))
				(setf (aref *t* 0 0 0) 1)
				(setf (aref *t* 0 1 1) 4)
				(setf (aref *t* 1 0 1) 6)
				(print (list (aref *t* 0 0 0) (aref *t* 0 1 1) (aref *t* 1 0 1) (aref *t* 1 1 0)))
				(print *t*)
				""")).isEqualTo("(1 4 6 0)\n#3A(((1 0) (0 4)) ((0 6) (0 0)))");
	}

	@Test
	void compileRankNArrayDimensionsAndIntrospection() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *t4* (make-array (list 2 3 4 5) :initial-element 0))
				(print (array-dimensions *t4*))
				(print (array-rank *t4*))
				(print (array-dimension *t4* 2))
				(print (array-total-size *t4*))
				""")).isEqualTo("(2 3 4 5)\n4\n4\n120");
	}

	@Test
	void compileRowMajorArefReadsAndWritesFlat() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *m* (make-array (list 2 3) :initial-element 0))
				(setf (row-major-aref *m* 4) 9)
				(print (list (row-major-aref *m* 4) (aref *m* 1 1) (array-row-major-index *m* 1 1)))
				(defparameter *t* (make-array (list 2 2 2) :initial-element 0))
				(setf (aref *t* 1 0 1) 7)
				(print (list (row-major-aref *t* 5) (array-row-major-index *t* 1 0 1)
				             (row-major-aref #(10 20 30) 2)))
				""")).isEqualTo("(9 9 4)\n(7 5 30)");
	}

	@Test
	void compileClosureReadsLetVarShadowingGlobal() throws Exception {
		// A lambda capturing a let variable must read the captured binding, not a
		// same-named top-level global.
		assertThat(compileAndRun("(setq c 777) (print (let ((c 5)) (funcall (lambda () c))))")).isEqualTo("5");
	}

	@Test
	void compileClosureWritesLetVarShadowingGlobal() throws Exception {
		// A setq inside the lambda must write the captured cell, and the global must
		// stay untouched.
		assertThat(
				compileAndRun("(setq d 666) (print (let ((d 5)) (funcall (lambda () (setq d (+ d 1)) d)))) (print d)"))
			.isEqualTo("6\n666");
	}

	@Test
	void compileClosureCapturesParamShadowingGlobal() throws Exception {
		// A lambda capturing an enclosing defun parameter must not resolve it to a
		// same-named global.
		assertThat(compileAndRun("(setq e2 555) (defun g (e2) (funcall (lambda () e2))) (print (g 42))"))
			.isEqualTo("42");
	}

	@Test
	void compileArray2DSum() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *m* (make-array (list 2 3) :initial-element 0))
				(dotimes (i 2)
				  (dotimes (j 3)
				    (setf (aref *m* i j) (+ (* i 3) j))))
				(let ((s 0))
				  (dotimes (i 2)
				    (dotimes (j 3)
				      (incf s (aref *m* i j))))
				  (print s))
				""")).isEqualTo("15");
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
	void compileRank2ArrayLiteralPrintsAsHash2A() throws Exception {
		assertThat(compileAndRun("(print #2A((1 2 3) (4 5 6)))")).isEqualTo("#2A((1 2 3) (4 5 6))");
	}

	@Test
	void compileRank2ArrayLiteralIsAReadableArray() throws Exception {
		assertThat(compileAndRun("(print (aref #2A((1 2) (3 4)) 1 0))")).isEqualTo("3");
		assertThat(compileAndRun("(print (array-dimensions #2A((1 2 3) (4 5 6))))")).isEqualTo("(2 3)");
	}

	@Test
	void compileRank3ArrayLiteral() throws Exception {
		assertThat(compileAndRun("(print (aref #3A(((1 2) (3 4)) ((5 6) (7 8))) 1 0 1))")).isEqualTo("6");
	}

	@Test
	void compileLengthOfVectorReturnsElementCount() throws Exception {
		assertThat(compileAndRun("(print (length (make-array 5 :initial-element 0)))")).isEqualTo("5");
		assertThat(compileAndRun("(print (length #(10 20 30)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (length #()))")).isEqualTo("0");
	}

	@Test
	void vectorSvrefCoerceAndArrayIntrospectionWork() throws Exception {
		assertThat(compileAndRun("""
				(print (vector 1 2 3))
				(print (svref (vector 10 20 30) 1))
				(defparameter *v* (vector 1 2 3))
				(setf (svref *v* 0) 99)
				(print *v*)
				(print (array-dimensions (make-array '(2 3) :initial-element 0)))
				(print (array-dimensions (vector 1 2)))
				(print (array-rank (make-array '(2 3))))
				(print (array-dimension (make-array '(2 3)) 1))
				(print (array-total-size (make-array '(2 3))))
				(print (coerce '(1 2 3) 'vector))
				(print (coerce (vector 1 2 3) 'list))
				(print (coerce "ab" 'list))
				(print (coerce '(#\\a #\\b) 'string))
				""")).isEqualTo("#(1 2 3)\n20\n#(99 2 3)\n(2 3)\n(2)\n2\n3\n6\n#(1 2 3)\n(1 2 3)\n(#\\a #\\b)\n\"ab\"");
	}

	@Test
	void linalgOpsWorkInPreview1Mode() throws Exception {
		// The Lisp-source linalg library (spliced by LinalgLibrary.process, mirroring
		// the cli pre-pass) runs in Preview 1: constructors, shape ops, broadcasting
		// arithmetic, products, reductions and exact rational linear algebra.
		assertThat(compileAndRunLinalg("""
				(print (linalg:eye 2))
				(print (linalg:arange 2 10 2))
				(print (linalg:reshape (linalg:arange 6) '(2 3)))
				(print (linalg:add (linalg:from-list '(1 2 3)) 10))
				(print (linalg:dot (linalg:from-list '(1 2 3)) (linalg:from-list '(4 5 6))))
				(print (linalg:matmul (linalg:from-list '((1 2) (3 4))) (linalg:from-list '((5 6) (7 8)))))
				(print (linalg:mean (linalg:from-list '(1 2 3 4))))
				(print (linalg:norm (linalg:from-list '(3 4))))
				(print (linalg:det (linalg:from-list '((1 2) (3 4)))))
				(print (linalg:inv (linalg:from-list '((1 2) (3 4)))))
				(print (linalg:solve (linalg:from-list '((2 1) (1 3))) (linalg:from-list '(3 5))))
				(print (funcall #'linalg:argmax (linalg:from-list '(1 9 3))))
				""")).isEqualTo("#2A((1 0) (0 1))\n#(2 4 6 8)\n#2A((0 1 2) (3 4 5))\n#(11 12 13)\n32\n"
				+ "#2A((19 22) (43 50))\n5/2\n5.0\n-2\n#2A((-2 1) (3/2 -1/2))\n#(4/5 7/5)\n1");
	}

	@Test
	void urlOpsWorkInPreview1Mode() throws Exception {
		// The Lisp-source URL library (spliced by UrlLibrary.process, mirroring the
		// cli pre-pass) runs in Preview 1: multi-byte percent decoding/encoding over
		// UTF-8 byte-indexed strings, query parsing and the splitting helpers.
		assertThat(compileAndRunUrl("""
				(print (rontolisp:url-decode "Will+it+work%3F"))
				(print (rontolisp:url-decode "%E3%81%82%E3%81%84"))
				(print (rontolisp:url-encode "a b/c~d"))
				(print (rontolisp:url-encode "あ"))
				(print (rontolisp:url-decode (rontolisp:url-encode "日本語 text?&=")))
				(print (rontolisp:query-params "a=1&b=two&flag"))
				(print (rontolisp:query-params nil))
				(print (rontolisp:query-param "a=1&name=ronto%20lisp" "name"))
				(print (rontolisp:query-param "a=1" "missing"))
				(print (rontolisp:url-path "/get?a=1"))
				(print (rontolisp:url-query "/get?a=1"))
				(print (rontolisp:url-query "/get"))
				(print (mapcar #'rontolisp:url-decode (list "a%2Bb" "1+2")))
				""")).isEqualTo("\"Will it work?\"\n\"あい\"\n\"a%20b%2Fc~d\"\n\"%E3%81%82\"\n\"日本語 text?&=\"\n"
				+ "((\"a\" . \"1\") (\"b\" . \"two\") (\"flag\" . \"\"))\nnil\n\"ronto lisp\"\nnil\n"
				+ "\"/get\"\n\"a=1\"\nnil\n(\"a+b\" \"1 2\")");
	}

	@Test
	void jsonOpsWorkInPreview1Mode() throws Exception {
		// The Lisp-source JSON library (spliced by JsonLibrary.process, mirroring the
		// cli pre-pass) runs in Preview 1: plist and hash-table object modes, escapes
		// (including \\uXXXX decoded to UTF-8 bytes), numbers, stringify and the
		// #' wrappers.
		assertThat(
				compileAndRunJson("(print (rontolisp:json-parse \"{\\\"name\\\": \\\"rontolisp\\\", \\\"n\\\": 2}\"))"))
			.isEqualTo("(:name \"rontolisp\" :n 2)");
		assertThat(compileAndRunJson("""
				(print (rontolisp:json-parse "42"))
				(print (rontolisp:json-parse "1e3"))
				(print (floatp (rontolisp:json-parse "1234567890123")))
				(print (rontolisp:json-parse "[1, [2, \\"x\\"], null]"))
				(print (rontolisp:json-parse "\\"\\\\u0041\\\\u3042\\""))
				""")).isEqualTo("42\n1000.0\nt\n(1 (2 \"x\") nil)\n\"A\u3042\"");
		assertThat(compileAndRunJson("""
				(let ((h (rontolisp:json-parse "{\\"content-type\\": \\"text/html\\"}" :hash-table)))
				  (print (gethash "content-type" h)))
				""")).isEqualTo("\"text/html\"");
		assertThat(compileAndRunJson(
				"""
						(print (rontolisp:json-stringify (list :name "rontolisp" :ok t :ver 1.5)))
						(print (rontolisp:json-stringify (rontolisp:json-parse "{\\"deep\\": {\\"list\\": [{\\"k\\": \\"v\\"}, 2.5, true]}}")))
						(print (funcall #'rontolisp:json-stringify (list 1 2)))
						"""))
			.isEqualTo(
					"\"{\"name\":\"rontolisp\",\"ok\":true,\"ver\":1.5}\"\n\"{\"deep\":{\"list\":[{\"k\":\"v\"},2.5,true]}}\"\n\"[1,2]\"");
	}

	@Test
	void equalAndHashTablesAcceptRuntimeBuiltStringKeys() throws Exception {
		// Regression: _equal compares string content (via _string_eq) and _hash folds
		// the content bytes, so a runtime-built string (concatenate/subseq/JSON parse)
		// is equal to -- and hashes like -- an interned literal.
		assertThat(compileAndRun("""
				(print (equal (concatenate 'string "a" "b") "ab"))
				(print (equal "ab" 'ab))
				(let ((h (make-hash-table)))
				  (setf (gethash (concatenate 'string "a" "b") h) 1)
				  (print (gethash "ab" h))
				  (setf (gethash (subseq "xaby" 1 3) h) 2)
				  (print (gethash "ab" h))
				  (print (hash-table-count h)))
				""")).isEqualTo("t\nnil\n1\n2\n1");
	}

	@Test
	void compileAndRunDefunRestAndOptional() throws Exception {
		assertThat(compileAndRun("""
				(defun f (a &rest r) (list a r))
				(print (f 1 2 3))
				(print (f 1))
				(defun g (x &optional (y 10) (z (* y 2) zp)) (list x y z zp))
				(print (g 1))
				(print (g 1 2 3))
				""")).isEqualTo("(1 (2 3))\n(1 nil)\n(1 10 20 nil)\n(1 2 3 t)");
	}

	@Test
	void compileAndRunDefunKeywordArguments() throws Exception {
		assertThat(compileAndRun("""
				(defun f (a &key (k 1 kp) m) (list a k kp m))
				(print (f 0))
				(print (f 0 :k 5))
				(print (f 0 :m 7 :k 9))
				(defun g (a &optional b &rest r &key c &allow-other-keys) (list a b r c))
				(print (g 1 2 :c 3 :d 4))
				""")).isEqualTo("(0 1 nil nil)\n(0 5 t nil)\n(0 9 t 7)\n(1 2 (:c 3 :d 4) 3)");
	}

	@Test
	void compileAndRunVariadicFirstClass() throws Exception {
		assertThat(compileAndRun("""
				(defun f (a &rest r) (list a r))
				(print (funcall (lambda (&rest xs) xs) 1 2 3))
				(print (funcall #'f 1 2 3))
				(print (apply #'f 1 (list 2 3)))
				(print (mapcar (lambda (x &optional (y 100)) (+ x y)) (list 1 2 3)))
				(print ((lambda (a &rest r) (list a r)) 1 2 3))
				""")).isEqualTo("(1 2 3)\n(1 (2 3))\n(1 (2 3))\n(101 102 103)\n(1 (2 3))");
	}

	@Test
	void compileAndRunDefstructBasics() throws Exception {
		assertThat(compileAndRun("""
				(defstruct point x (y 10))
				(setq p (make-point :x 1))
				(print (point-x p))
				(print (point-y p))
				(print (point-p p))
				(print (point-p '(1 2)))
				(setq q (copy-point p))
				(setf (point-x q) 100)
				(print (point-x p))
				(print (point-x q))
				""")).isEqualTo("1\n10\nt\nnil\n1\n100");
	}

	@Test
	void compileAndRunDefstructSetfPlacesAndFirstClassAccessors() throws Exception {
		assertThat(compileAndRun("""
				(defstruct point x y)
				(setq p (make-point :x 1 :y 2))
				(setf (point-x p) 99)
				(incf (point-y p) 5)
				(print (list (point-x p) (point-y p)))
				(print (mapcar #'point-x (list (make-point :x 10) (make-point :x 20))))
				(defpackage :geo (:use :cl))
				(in-package :geo)
				(defstruct pt x y)
				(in-package :cl-user)
				(setq gp (geo::make-pt :x 3 :y 4))
				(print (list (geo::pt-x gp) (geo::pt-p gp) (geo::pt-p p)))
				""")).isEqualTo("(99 7)\n(10 20)\n(3 t nil)");
	}

}
