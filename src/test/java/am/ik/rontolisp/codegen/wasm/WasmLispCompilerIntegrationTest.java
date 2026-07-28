package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.testsupport.WasmtimeSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests that compile Lisp to WASM and run it with wasmtime inside a
 * container.
 *
 * <p>
 * This is by far the longest class in the build: every case is a serial chain of compile
 * the module, stage it in the container, run wasmtime (roughly 250 ms / 20 ms / 150 ms
 * for a linalg program), and there are thousands of them. None of that chain is shared
 * between cases, so the methods run concurrently inside the surefire fork -- the thread
 * cap lives in {@code junit-platform.properties}. Everything a test writes into the
 * container therefore has to be private to the running test: stage modules and
 * guest-visible data files through {@link #path(String)}, never at a fixed
 * {@code /tmp/...} literal shared with another method.
 */
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.CONCURRENT)
class WasmLispCompilerIntegrationTest {

	// The wasmtime container comes from a prebuilt GHCR image (see WasmtimeSupport); it
	// is shared across every WASM test class and started once per JVM. The class-level
	// @Testcontainers(disabledWithoutDocker = true) disables all of these Docker-only
	// tests when no daemon is reachable, so the container is only obtained (and Docker
	// only contacted) from @BeforeAll, which never runs for a disabled class.
	static GenericContainer<?> wasmtime;

	// Scratch directory in the container, one per test worker thread (created on that
	// thread's first use). Scoping by thread rather than by test keeps the container's
	// /tmp to one file set per worker, and gives the `--dir .` tests -- whose guest
	// programs open relative names like "lib.lisp" or "wof.txt" -- a working directory
	// no concurrently running test can write into.
	private static final ConcurrentHashMap<Long, String> WORK_DIRS = new ConcurrentHashMap<>();

	// Workers for the second half of a two-module comparison (see
	// assertLinalgMatchesTheScalarPath). A fixed pool rather than a virtual-thread
	// executor: a worker pays one mkdir exec for its scratch directory on first use, so
	// the threads are worth reusing. Daemon threads, so a pool idling at the end of the
	// class never holds the JVM open.
	private static final ExecutorService PAIRS = Executors
		.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()), runnable -> {
			Thread thread = new Thread(runnable, "wasm-compare");
			thread.setDaemon(true);
			return thread;
		});

	@BeforeAll
	static void startWasmtime() {
		wasmtime = WasmtimeSupport.container();
	}

	/**
	 * Waits for a {@link #PAIRS} task, unwrapping the {@link ExecutionException} so an
	 * assertion that failed on the worker surfaces as itself.
	 * @param <T> the task's result type
	 * @param task the submitted task
	 * @return its result
	 * @throws Exception whatever the task threw
	 */
	private static <T> T await(Future<T> task) throws Exception {
		try {
			return task.get();
		}
		catch (ExecutionException failed) {
			switch (failed.getCause()) {
				case Exception cause -> throw cause;
				case Error cause -> throw cause;
				case null, default -> throw failed;
			}
		}
	}

	/**
	 * Returns a container path under the calling thread's scratch directory. Use it for
	 * every file a test stages or the guest program opens; a fixed path would be raced by
	 * the tests running concurrently on the other threads.
	 * @param name the file name inside the scratch directory
	 * @return the absolute container path
	 */
	private static String path(String name) {
		return workDir() + "/" + name;
	}

	private static String workDir() {
		return WORK_DIRS.computeIfAbsent(Thread.currentThread().threadId(), id -> {
			String dir = "/tmp/w" + id;
			try {
				wasmtime.execInContainer("mkdir", "-p", dir);
			}
			catch (Exception e) {
				throw new IllegalStateException("cannot create the container scratch dir " + dir, e);
			}
			return dir;
		});
	}

	private static String compileAndRun(String lispCode) throws Exception {
		return compileAndRunProgram(LispReader.readAllFromString(lispCode));
	}

	@Test
	void compileAndRunUpcaseReaderMode() throws Exception {
		// The reader's upcase premise is a frontend concern: the WASM compiler sees
		// the folded token stream, so mixed-case operators, upcased user symbols and
		// upcased keyword arguments behave as on the interpreter and the JVM.
		am.ik.rontolisp.reader.Features upcase = am.ik.rontolisp.reader.Features.WASM;
		String output = compileAndRunProgram(LispReader.readAllFromString("""
				(DEFUN ADD2 (X) (+ X 2))
				(PRINT (add2 40))
				(PRINT (CDR (ASSOC :b '((:A . 1) (:B . 2)))))
				(PRINT (SYMBOL-NAME :foo))
				""", upcase));
		assertThat(output).isEqualTo("42\n2\n\"FOO\"");
	}

	@Test
	void compileAndRunUpcaseReaderModeFoldsRuntimeRead() throws Exception {
		// The embedded reader runtime upcases like the frontend (uppercase-canonical: the
		// reader upcases every unescaped symbol character with no fold back to a
		// lowercase
		// form). A user symbol and a standard operator both read upcased, so a read token
		// stays eq to a compiled quoted reference, byte-identical to the interpreter and
		// the JVM.
		assertThat(compileAndRun("(print (read-from-string \"foo\"))")).isEqualTo("FOO");
		assertThat(compileAndRun("(print (symbol-name (read-from-string \"foo\")))")).isEqualTo("\"FOO\"");
		assertThat(compileAndRun("(print (eq (read-from-string \"car\") 'car))")).isEqualTo("T");
		assertThat(compileAndRun("(print (read-from-string \"(x . 9)\"))")).isEqualTo("(X . 9)");
		// A '&' lambda-list marker upcases too (no fold), and nil reads as the null
		// value.
		assertThat(compileAndRun("(print (read-from-string \"&optional\"))")).isEqualTo("&OPTIONAL");
		assertThat(compileAndRun("(print (null (read-from-string \"nil\")))")).isEqualTo("T");
		assertThat(compileAndRun("(print (read-from-string \"t\"))")).isEqualTo("T");
		// A standard name reads upcased even when the program does not otherwise use it,
		// and eval can then run it.
		assertThat(compileAndRun("(print (eval (read-from-string \"(reverse (list 1 2 3))\")))")).isEqualTo("(3 2 1)");
	}

	private static String compileAndRunProgram(List<LispVal> program) throws Exception {
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", program, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	// EH-mode variant (handler-case / ignore-errors / unwind-protect): the emitted
	// module carries the $lisp-cond tag section, so wasmtime needs the
	// exception-handling proposal enabled (`-W exceptions=y`, wasmtime 37+).
	private static String compileAndRunEh(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc", "-W", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	// Compiles a rontolisp:http-handler program to a plain-serve component the way the
	// CLI
	// does: serve.lisp's HTTP glue is spliced (ServeLibrary), its own wasi:io / wasi:http
	// wit-imports lowered, and the `handle` wrapper lifted into
	// wasi:http/incoming-handler --
	// no hand-written serve adapter. A wit-import in the program (e.g. wasi:keyvalue) is
	// lowered against baseDir (where its .wit lives) first, exactly like the CLI. This
	// mirrors RontoLispCli's --component serve pipeline for the library steps these
	// handlers
	// need -- including FetchLibrary, so a handler that also calls rontolisp:fetch (the
	// proxy shape) gets fetch.lisp spliced alongside serve.lisp, over the wider block.
	private static byte[] compileServeComponent(String source, @org.jspecify.annotations.Nullable String baseDir) {
		var witBackend = am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT;
		List<LispVal> loaded = am.ik.rontolisp.eval.WitImportInliner.inline(LispReader.readAllFromString(source),
				baseDir, witBackend, am.ik.rontolisp.eval.SourceLoader.fileSystem());
		loaded = am.ik.rontolisp.eval.HttpLibrary.process(loaded, witBackend, true);
		// The wait-for / sockets / stdin splices mirror the CLI order (each a no-op for
		// a handler that references nothing of it), so a served handler may also use
		// the tcp built-ins.
		loaded = am.ik.rontolisp.eval.WaitForLibrary.process(loaded, witBackend);
		loaded = am.ik.rontolisp.eval.SocketsLibrary.process(loaded, witBackend);
		loaded = am.ik.rontolisp.eval.StdinLibrary.process(loaded, witBackend, true);
		// The prelude splice mirrors the CLI: a handler draining a body stream calls
		// the prelude's rontolisp:read-all.
		List<LispVal> program = am.ik.rontolisp.eval.WitLibrary.process(
				am.ik.rontolisp.eval.LispPreludeLibrary.process(am.ik.rontolisp.eval.UserMacroExpander.expand(loaded)));
		return new WasmLispCompiler(false, true, false, false, true).compile(program);
	}

	// EH-mode variant asserting an ABNORMAL exit: an uncaught condition must keep the
	// trap shape (`unreachable`), so the run fails and the stderr is returned.
	private static String compileAndRunEhExpectTrap(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc", "-W", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("expected a trap for: %s\nstdout: %s", lispCode, result.getStdout())
			.isNotZero();
		return result.getStderr();
	}

	// JSON tests pre-process with JsonLibrary.process, mirroring the compile-path
	// pre-pass run by RontoLispCli.
	private static String compileAndRunJson(String lispCode) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.JsonLibrary.process(LispReader.readAllFromString(lispCode));
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	// linalg tests pre-process with LinalgLibrary.process, mirroring the compile-path
	// pre-pass run by RontoLispCli.
	private static String compileAndRunLinalg(String lispCode) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LinalgLibrary.process(LispReader.readAllFromString(lispCode));
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	// URL tests pre-process with UrlLibrary.process, mirroring the compile-path
	// pre-pass run by RontoLispCli.
	private static String compileAndRunUrl(String lispCode) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.UrlLibrary.process(LispReader.readAllFromString(lispCode));
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	// Preview 1 variant that passes an environment variable and returns the raw
	// (untrimmed)
	// stdout, so callers can assert on exact bytes (e.g. that a newline stays 0x0a).
	private static String compileAndRunRawWithEnv(String lispCode, String env) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y", "--env", env,
				path("test.wasm"));
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout();
	}

	// Compiles a program that uses (rontolisp:wasm-export ...) and invokes one of the
	// exported Lisp
	// functions directly via `wasmtime --invoke <fn> ... <args>` (scalar arguments only).
	private static String compileAndInvoke(String lispCode, String function, String... args) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		List<String> command = new java.util.ArrayList<>(
				List.of("wasmtime", "run", "--invoke", function, "-W", "gc", "-W", "exceptions=y", path("test.wasm")));
		command.addAll(List.of(args));
		ExecResult result = wasmtime.execInContainer(command.toArray(new String[0]));
		assertThat(result.getExitCode())
			.as("exit code for invoke %s: %s\nstderr: %s", function, lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	// rontolisp:fetch on the --component path is the http.lisp library over wit-imported
	// wasi:http, spliced by HttpLibrary in the CLI front-end. A raw compiler splices no
	// library, so a fetch program is compiled through the same passes the CLI runs, in
	// the CLI's order (each a no-op for a program that references nothing of it):
	// http, wait-for, then prelude (read-all is a prelude async-defun) and the wit
	// runtime.
	private static byte[] compileFetchComponent(String program) {
		var witBackend = am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT;
		List<am.ik.rontolisp.LispVal> forms = am.ik.rontolisp.eval.WitLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary
				.process(am.ik.rontolisp.eval.StdinLibrary.process(am.ik.rontolisp.eval.SocketsLibrary.process(
						am.ik.rontolisp.eval.WaitForLibrary.process(am.ik.rontolisp.eval.HttpLibrary
							.process(LispReader.readAllFromString(program), witBackend, false), witBackend),
						witBackend), witBackend, false)));
		return new WasmLispCompiler(false, true).compile(forms);
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

	// Compiles a --component program and invokes a component-model function by its WAVE
	// signature (`name(arg, ...)`), the form an interface member is reached by.
	private static String compileComponentAndInvoke(String lispCode, String invocation) throws Exception {
		byte[] component = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(lispCode));
		wasmtime.copyFileToContainer(Transferable.of(component), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "--invoke", invocation,
				path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	@Test
	void interfaceExportIsCallableAsAComponentInstance() throws Exception {
		// A world's interface export (`export docs:adder/add;`, the idiomatic separated
		// shape) lowers into a :interface wasm-export, which the component builder
		// bundles
		// into an exported component INSTANCE. wasmtime resolves the member inside it
		// (WAVE
		// `add(20, 22)`), proving the emitted instance genuinely carries the function --
		// not a flattened top-level export.
		assertThat(compileComponentAndInvoke(
				"""
						(defun add (x y) (+ x y))
						(rontolisp:wasm-export 'add :params '(:int :int) :param-names '(x y) :returns :int :interface "docs:adder/add@0.1.0")
						""",
				"add(20, 22)"))
			.isEqualTo("42");
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
	void concatenateBuildsListAndVectorResultTypes() throws Exception {
		// concatenate's list / vector families (ConcatenateForms): they walk elements, so
		// any mix of sequences works and a compound spec like '(vector (unsigned-byte 8))
		// -- what ironclad's HKDF builds its output with -- normalizes to the vector
		// family. Also the first-class #'concatenate wrapper, whose result type is a
		// runtime value.
		String program = """
				(print (concatenate 'list '(1 2) "ab" #(3)))
				(print (concatenate 'vector '(1 2) #(3)))
				(print (concatenate '(vector (unsigned-byte 8)) #(1) #(2 3)))
				(print (concatenate 'list))
				(print (concatenate 'vector))
				(print (let ((a (list 1 2))) (eq a (concatenate 'list a))))
				(print (apply #'concatenate '(vector (unsigned-byte 8)) (list #(1) #(2 3))))
				(print (apply #'concatenate 'string (list "a" "b")))
				""";
		assertThat(compileAndRun(program)).isEqualTo("""
				(1 2 #\\a #\\b 3)
				#(1 2 3)
				#(1 2 3)
				NIL
				#()
				NIL
				#(1 2 3)
				"ab\"""");
	}

	@Test
	void exactIntegersBeyondI31PromoteToBoxedI64() throws Exception {
		// The boxed exact-integer overflow path (TYPE_BIGNUM, .kb/wasm-bignum.md): an
		// arithmetic or shift result outside the i31 fixnum range promotes to a boxed
		// i64 value instead of silently wrapping, and a result that shrinks back into
		// range demotes to a plain i31 (so ref.eq/eql fast paths stay valid). This is
		// what unlocks unsigned 32-bit workloads (md5's #xEFCDAB89 magic constants and
		// (ldb (byte 32 0) ...) sums) on both WASM backends.
		String program = """
				(print #xEFCDAB89)
				(print (+ 1073741823 1))
				(print (* 65536 65536))
				(print (ash 1 32))
				(print (ash 4294967296 -32))
				(print (ldb (byte 32 0) (+ #xFFFFFFFF #xFFFFFFFF)))
				(print (logior (ash #x12 24) (ash #x34 16) (ash #x56 8) #x78))
				(print (lognot #xFFFFFFFF))
				(print (mod 4294967296 10))
				(print (truncate 4294967296))
				(print (/ 4294967296 2))
				(print (- 4294967296 4294967290))
				(print (= 4294967296 4294967296))
				(print (< 4294967295 4294967296))
				(print (eql 4294967296 4294967296))
				(print (equal (list 4294967296) (list 4294967296)))
				(print (integerp 4294967296))
				(print (evenp 4294967296))
				(print (abs -4294967296))
				(print (signum -4294967296))
				(print (integer-length 4294967296))
				(print (read-from-string "4294967296"))
				(print (read-from-string "#xEFCDAB89"))
				""";
		assertThat(compileAndRun(program)).isEqualTo("""
				4023233417
				1073741824
				4294967296
				4294967296
				1
				4294967294
				305419896
				-4294967296
				6
				4294967296
				2147483648
				6
				T
				T
				T
				T
				T
				T
				4294967296
				-1
				33
				4294967296
				4023233417""");
	}

	@Test
	void exactIntegersBeyondI64PromoteToLimbBigints() throws Exception {
		// The third exact-integer tier (TYPE_BIGINT, .kb/wasm-bignum.md): a literal,
		// arithmetic result or shift outside the signed 64-bit range is a
		// two's-complement limb integer -- exact at any magnitude, as Common Lisp
		// requires -- and a shrinking result demotes back through the boxed-i64 and
		// i31 tiers (so ref.eq/eql fast paths stay valid). This is what unlocks
		// SCRAM-SHA-256-style 256-bit working state.
		String program = """
				(defvar *a* #xba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad)
				(print *a*)
				(print (+ 9223372036854775807 1))
				(print (* 4611686018427387904 4))
				(print (logxor *a* *a*))
				(print (integer-length *a*))
				(print (ldb (byte 8 248) *a*))
				(print (ash *a* 8))
				(print (ash *a* -248))
				(print (truncate *a* 16))
				(print (mod (- 0 *a*) 1000000007))
				(print (gcd (* *a* 6) (* *a* 15)))
				(print (= *a* *a*))
				(print (< (- 0 *a*) *a*))
				(print (eql (- (+ *a* 5) *a*) 5))
				(print (equal (list *a*) (list *a*)))
				(print (expt 2 100))
				(print (/ (* *a* 8) 8))
				(print (read-from-string "1267650600228229401496703205376"))
				(print (read-from-string "#x-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
				(print (eval (list '+ *a* 1)))
				""";
		assertThat(compileAndRun(program)).isEqualTo("""
				84342368487090800366523834928142263660104883695016514377462985829716817089965
				9223372036854775808
				18446744073709551616
				0
				256
				186
				21591646332695244893830101741604419496986850225924227680630524372407505175031040
				186
				5271398030443175022907739683008891478756555230938532148591436614357301068122
				2077788
				253027105461272401099571504784426790980314651085049543132388957489150451269895
				T
				T
				T
				T
				1267650600228229401496703205376
				84342368487090800366523834928142263660104883695016514377462985829716817089965
				1267650600228229401496703205376
				-84342368487090800366523834928142263660104883695016514377462985829716817089965
				84342368487090800366523834928142263660104883695016514377462985829716817089966""");
	}

	@Test
	void fusedIntegerExpressionTreesMatchTheGenericPath() throws Exception {
		// Integer expression-tree fusion (WasmIntFusionCompiler, todo 194): a nested
		// arithmetic/bitwise tree keeps its intermediates as raw i64 on the wasm stack
		// and boxes only at the root, bailing per leaf / per overflow to a fallback
		// that recomputes through the generic helpers. These pin the equivalences the
		// fast path must preserve: i64 overflow still promotes to the limb tier
		// (.kb/wasm-bignum.md's narrowest-tier invariant), a float or ratio leaf takes
		// the generic result, CL mod/rem/ash sign semantics survive the raw path
		// (including the mod-by-2^k mask and literal-negative-count ash shortcuts),
		// and the leaves' side effects run exactly once, left to right.
		String program = """
				(print (logand (+ 3 4) 5))
				(print (logxor (logand 255 170) (logior 15 240)))
				(print (+ (* 3 5) (- 10 4) (mod 17 5)))
				(print (mod (+ -10 3) 3))
				(print (rem (- 3 10) 3))
				(print (lognot (+ 5 5)))
				(print (+ (* 4611686018427387904 4) 1))
				(print (* (+ 4294967296 1) (+ 4294967296 1)))
				(print (logand (ash 1 100) (ash 3 99)))
				(print (ash (+ 5 5) -70))
				(print (ash (+ -5 0) -70))
				(print (ash (* 1 0) 100))
				(print (mod (- 0 4294967297) 4294967296))
				(let ((f 1.5)) (print (+ (logand 3 1) f)))
				(print (+ (/ 1 2) (+ 1 0) 1))
				(let ((n 0))
				  (print (+ (progn (setq n (+ n 1)) n)
				            (progn (setq n (+ n 10)) n)
				            (progn (setq n (+ n 100)) n)))
				  (print n))
				(defun rol32 (x s) (logand (logior (ash x s) (ash x (- s 32))) 4294967295))
				(print (rol32 2882400001 8))
				(print (mod (ash 1 62) 1000000007))
				""";
		assertThat(compileAndRun(program)).isEqualTo("""
				5
				85
				23
				2
				-1
				-11
				18446744073709551617
				18446744082299486209
				1267650600228229401496703205376
				0
				-1
				0
				4294967295
				2.5
				5/2
				123
				111
				3454992811
				145586002""");
	}

	@Test
	void fusedLocalFunctionsAndUnboxedLocalsMatchTheGenericPath() throws Exception {
		// Todo 194 stage 3. Pins, in order: (1) flet one-liner bodies (over params and
		// inlinable defuns like rol32b) substitute into fused trees -- results identical
		// to the generic call chain; (2) an flet function used as a VALUE (#'add2 via
		// funcall and reduce) still exists as a closure; (3) labels recursion is
		// untouched by the inliner; (4) an unboxed (dual-representation) local promotes
		// through its boxed shadow when the raw i64 overflows (3^45 needs the limb
		// tier) and (5) accepts a non-integer assignment (a list, a float) through the
		// same shadow; (6)/(7) the masked-wrap peephole (+/*/ash under a literal logand
		// or ldb mask compile as unchecked wrap-around i64) keeps exact low bits at and
		// beyond the i64 edge; (8) a comparison's t is the cached shared symbol, still
		// eq to a quoted 't; (9) a side-effecting argument of an inlinable-defun call
		// whose body substitution FAILS (the parameter-shaped aref) still runs exactly
		// once -- the failed attempt's registered leaves must be rolled back, or the
		// argument evaluates once as a discarded leaf and once in the call.
		String program = """
				(defun rol32b (x s) (logand (logior (ash x s) (ash x (- s 32))) 4294967295))
				(defun mix (a b c)
				  (flet ((ch (x y z) (logxor z (logand x (logxor y z))))
				         (sigma0 (x) (logxor (rol32b x 30) (rol32b x 19) (rol32b x 10))))
				    (logand (+ (sigma0 a) (ch a b c)) 4294967295)))
				(print (mix 1779033703 3144134277 1013904242))
				(flet ((add2 (x y) (+ x y)))
				  (print (funcall #'add2 40 2))
				  (print (reduce #'add2 (list 1 2 3 4))))
				(labels ((fact (n) (if (< n 2) 1 (* n (fact (- n 1))))))
				  (print (fact 25)))
				(let ((x 1) (i 0))
				  (tagbody top (setq x (* x 3)) (setq i (+ i 1)) (if (< i 45) (go top)))
				  (print x))
				(let ((y 5))
				  (setq y (+ y 1))
				  (setq y (list y))
				  (print y))
				(print (logand (* 123456789123 987654321987) 4294967295))
				(print (ldb (byte 32 0) (+ 9223372036854775807 9223372036854775807)))
				(print (eq (< 1 2) 't))
				(defun g8 (a i) (aref a i))
				(let ((arr (make-array 3 :element-type '(unsigned-byte 8))) (n 0))
				  (setf (aref arr 0) 7)
				  (print (+ (g8 arr (progn (setq n (+ n 1)) 0)) 1))
				  (print n))
				(let ((f 2.5) (k 3))
				  (setq f (+ f 0.5))
				  (setq k (+ k 1))
				  (print (list f k)))
				(let ((fs nil) (fe nil) (k 7))
				  (when (> k 5) (setq fs (+ k 1)))
				  (print (list fs fe (if fe fe k) (if fs fs k)))
				  (setq fs nil)
				  (print (if fs 'set 'unset)))
				""";
		// The last let is the jzon %json-number shape: a local INITIALIZED to nil
		// whose only assignments are integer trees. nil is ref.null, so "shadow
		// null = raw valid" cannot be the unboxed-local invariant -- the sentinel
		// scheme must read the untouched fe (and the re-nil'd fs) as nil, never as
		// the stale raw i64 slot.
		assertThat(compileAndRun(program)).isEqualTo("""
				210267027
				42
				10
				15511210043330985984000000
				2954312706550833698643
				(6)
				754865481
				4294967294
				T
				8
				1
				(3.0 4)
				(8 NIL 7 8)
				UNSET""");
	}

	@Test
	void fusedComparisonsAndRawLeafStoresMatchTheGenericPath() throws Exception {
		// Todo 194 stage 4. Pins, in order: (1) fused raw i64 comparisons agree with
		// the generic _rat_cmp_bits path across the i64 promotion boundary (the +1 on
		// most-positive-fixnum overflows into the limb tier, so the fast path bails)
		// and (2) bail for a float / ratio operand, including through an unboxed
		// local's shadow; (3) a single fused op with a literal operand (the incf of a
		// PARAMETER) promotes exactly at the i64 edge; (4) a raw-to-raw local copy
		// carries a degraded tier (float) through the shadow; (5) a packed aref as the
		// WHOLE stored value stays raw (out-of-i31 u32 element round-trips), and a
		// general-array source bails to the boxed read; (6) replace copies
		// packed-to-packed with offsets, from a list, and from a string, and an
		// immutable string target still takes the functional branch; (7) stringp
		// answers nil for a packed vector with no normalization call, t for a string;
		// (8) a docstring in defun statement position materializes nothing but a
		// docstring in TAIL position is still the return value.
		String program = """
				(print (< 9223372036854775807 (+ 9223372036854775807 1)))
				(print (> (* 3037000500 3037000500) 9223372036854775807))
				(print (= (+ 4611686018427387904 4611686018427387904) 9223372036854775808))
				(let ((f 2.5) (r 7/2) (i 0))
				  (setq i (+ i 1))
				  (print (list (< f 3) (< 3 f) (<= r 7/2) (< i 64) (>= i 1) (= i 2.0))))
				(let ((x 1))
				  (setq x (+ x 1))
				  (setq x 2.5)
				  (print (< x 3)))
				(defun inc-edge (n) (+ n 1))
				(print (inc-edge 9223372036854775807))
				(let ((a 1) (b 0))
				  (setq a (+ a 41))
				  (setq b a)
				  (setq a 1.5)
				  (setq b a)
				  (print b))
				(let ((u (make-array 3 :element-type '(unsigned-byte 32) :initial-element 0))
				      (g (make-array 2 :initial-element 4000000001)))
				  (setf (aref u 0) 4000000000)
				  (setf (aref u 1) (aref u 0))
				  (setf (aref u 2) (aref g 0))
				  (print u))
				(let ((dst (make-array 8 :element-type '(unsigned-byte 8) :initial-element 0))
				      (src (make-array 8 :element-type '(unsigned-byte 8) :initial-element 9)))
				  (setf (aref src 1) 250)
				  (replace dst src :start1 2 :end1 6 :start2 1)
				  (print dst)
				  (replace dst '(1 2 3))
				  (print dst)
				  (print (replace (make-array 3 :initial-element 0) "ab"))
				  (print (replace "xyz" "AB")))
				(print (stringp (make-array 2 :element-type '(unsigned-byte 8) :initial-element 0)))
				(print (stringp "s"))
				(defun doc-mid () "doc" 42)
				(defun doc-tail () "only-doc")
				(print (doc-mid))
				(print (doc-tail))
				""";
		assertThat(compileAndRun(program)).isEqualTo("""
				T
				T
				T
				(T NIL T T T NIL)
				T
				9223372036854775808
				1.5
				#(4000000000 4000000000 4000000001)
				#(0 0 250 9 9 9 0 0)
				#(1 2 3 9 9 9 0 0)
				#(#\\a #\\b 0)
				"ABz"
				NIL
				T
				42
				"only-doc\"""");
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
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		List<String> command = new java.util.ArrayList<>(
				List.of("wasmtime", "run", "--invoke", function, "-W", "gc", "-W", "exceptions=y", path("test.wasm")));
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
		wasmtime.copyFileToContainer(Transferable.of(host), path("host.wasm"));
		wasmtime.copyFileToContainer(Transferable.of(main), path("main.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc", "-W", "exceptions=y", "--preload",
				"host=" + path("host.wasm"), path("main.wasm"));
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
		assertThat(compileAndRunWithPreload(host, main, false)).isEqualTo("T\nNIL");
	}

	@Test
	void exportAliasIsInvokableUnderTheAliasName() throws Exception {
		String program = """
				(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
				(rontolisp:wasm-export 'fact :as "fibonacci-ish" :params '(:int) :returns :int)
				""";
		assertThat(compileAndInvoke(program, "fibonacci-ish", "5")).isEqualTo("120");
	}

	// Compiles with --no-gc (the non-GC lowering, scalar vec: kernels) and invokes an
	// export
	// WITHOUT `-W gc`, proving the module runs on a plain MVP runtime with no wasm-GC and
	// no import object.
	private static String compileNoGcAndInvoke(boolean optimize, String lispCode, String function, String... args)
			throws Exception {
		return compileNoGcAndInvoke(optimize, false, lispCode, function, args);
	}

	// As above, with an explicit --simd switch: simd=true lowers the vectorizable vec:
	// kernels to native v128 (f64x2/f32x4), simd=false to scalar linear-memory loops.
	// Both
	// run on a plain MVP runtime; the v128 build additionally needs the SIMD proposal (on
	// by
	// default in wasmtime).
	private static String compileNoGcAndInvoke(boolean optimize, boolean simd, String lispCode, String function,
			String... args) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new NoGcWasmCompiler(optimize, simd).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		List<String> command = new java.util.ArrayList<>(
				List.of("wasmtime", "run", "--invoke", function, path("test.wasm")));
		command.addAll(List.of(args));
		ExecResult result = wasmtime.execInContainer(command.toArray(new String[0]));
		assertThat(result.getExitCode())
			.as("exit code for no-gc invoke %s (simd=%s): %s\nstderr: %s", function, simd, lispCode, result.getStderr())
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
	void noGcLongBoundaryCrossesTheFull64BitRange() throws Exception {
		// :long makes both the parameter and the result i64 at the host boundary (no
		// wrap/extend), so a value beyond the 32-bit range round-trips exactly. With
		// :int, (100000+100000)^2 = 4e10 would be i32.wrap_i64-truncated to 1345294336;
		// :long returns the true 40000000000.
		String program = """
				(defun sumsquared (a b) (* (+ a b) (+ a b)))
				(rontolisp:wasm-export 'sumsquared :params '(:long :long) :returns :long)
				""";
		assertThat(compileNoGcAndInvoke(false, program, "sumsquared", "3", "4")).isEqualTo("49");
		assertThat(compileNoGcAndInvoke(false, program, "sumsquared", "100000", "100000")).isEqualTo("40000000000");
		assertThat(compileNoGcAndInvoke(true, program, "sumsquared", "100000", "100000")).isEqualTo("40000000000");
	}

	// Compiles with --no-gc --component (the compact reactor component) and
	// calls an export through the canonical ABI with WAVE syntax. The component carries
	// the plain MVP core module with NO adapter / import block / mem module, so it runs
	// with ZERO extra flags: no `-W gc`, no component-model-async flags -- assert that by
	// passing none. Like the GC component path this is the supported invoke path, so
	// stderr must carry no "experimental" warning.
	private static String compileNoGcComponentAndInvoke(String lispCode, String waveInvocation) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] componentBytes = new NoGcWasmCompiler(false, false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("test.component.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "--invoke", waveInvocation,
				path("test.component.wasm"));
		assertThat(result.getExitCode())
			.as("exit code for no-gc component invoke %s: %s\nstderr: %s", waveInvocation, lispCode, result.getStderr())
			.isZero();
		assertThat(result.getStderr()).as("WAVE invoke on a component is the supported path, no experimental warning")
			.doesNotContain("experimental");
		return result.getStdout().trim();
	}

	private static final String NO_GC_COMPONENT_PROGRAM = """
			(defun sumsquared (a b) (+ (* a a) (* b b)))
			(defun bigmul (a b) (* a b))
			(defun hyp (a b) (sqrt (+ (* a a) (* b b))))
			(defun evenish (n) (evenp n))
			(defun quiet-double (n) (* n 2))
			(rontolisp:wasm-export 'sumsquared :params '(:int :int) :returns :int)
			(rontolisp:wasm-export 'bigmul :params '(:long :long) :returns :long)
			(rontolisp:wasm-export 'hyp :params '(:float :float) :returns :float)
			(rontolisp:wasm-export 'evenish :params '(:int) :returns :bool)
			(rontolisp:wasm-export 'quiet-double :params '(:int))
			""";

	@Test
	void noGcComponentExportsCallableViaWaveInvokeWithNoFlags() throws Exception {
		// Every --no-gc scalar type crosses the canonical ABI: :int -> s32, :long -> s64
		// (the full 64-bit range -- :long is valid here, unlike the GC component),
		// :float -> f64, :bool -> bool (WAVE prints true/false), omitted :returns -> no
		// result. All with zero wasmtime flags (no -W gc, no async built-ins).
		assertThat(compileNoGcComponentAndInvoke(NO_GC_COMPONENT_PROGRAM, "sumsquared(2, 3)")).isEqualTo("13");
		assertThat(compileNoGcComponentAndInvoke(NO_GC_COMPONENT_PROGRAM, "bigmul(3000000000, 3)"))
			.isEqualTo("9000000000");
		assertThat(compileNoGcComponentAndInvoke(NO_GC_COMPONENT_PROGRAM, "hyp(3.0, 4.0)")).isEqualTo("5");
		assertThat(compileNoGcComponentAndInvoke(NO_GC_COMPONENT_PROGRAM, "evenish(4)")).isEqualTo("true");
		assertThat(compileNoGcComponentAndInvoke(NO_GC_COMPONENT_PROGRAM, "evenish(5)")).isEqualTo("false");
		// wasmtime prints a void invocation's (absent) result as the empty tuple ().
		assertThat(compileNoGcComponentAndInvoke(NO_GC_COMPONENT_PROGRAM, "quiet-double(6)")).isEqualTo("()");
	}

	@Test
	void noGcComponentCarriesEveryFixedWidthIntegerType() throws Exception {
		// The house integer here is i64, so the whole family crosses exactly -- including
		// the u32 the canonical tutorial world uses at its full 2^32 range, and the u64
		// range up to 2^63-1 (past that a value has no place in the signed house
		// integer, which is why the wrapper traps rather than reporting a negative).
		String program = """
				(defun bump (n) (+ n 1))
				(defun sum4 (a b c d) (+ a b c d))
				(defun scale (n) (* n 1000000))
				(rontolisp:wasm-export 'bump :params '(:u32) :returns :u32)
				(rontolisp:wasm-export 'sum4 :params '(:s8 :s16 :u8 :u16) :returns :u16)
				(rontolisp:wasm-export 'scale :params '(:u64) :returns :u64)
				""";
		assertThat(compileNoGcComponentAndInvoke(program, "bump(4294967294)")).isEqualTo("4294967295");
		assertThat(compileNoGcComponentAndInvoke(program, "sum4(-8, -300, 250, 60000)")).isEqualTo("59942");
		assertThat(compileNoGcComponentAndInvoke(program, "scale(9000000000000)")).isEqualTo("9000000000000000000");
	}

	@Test
	void noGcComponentRefusesAValueTheDeclaredTypeCannotState() throws Exception {
		// Same rule as the GC path: the boundary carries the value exactly or it traps.
		// :s32 keeps that promise too -- what used to be a silent i32.wrap_i64 of the
		// i64 house integer is now a refusal.
		byte[] component = new NoGcWasmCompiler(false, false, true).compile(LispReader.readAllFromString("""
				(defun down (n) (- n))
				(defun wide (n) (* n 1000000))
				(rontolisp:wasm-export 'down :params '(:s32) :returns :u32)
				(rontolisp:wasm-export 'wide :params '(:s32) :returns :s32)
				"""));
		wasmtime.copyFileToContainer(Transferable.of(component), path("test.component.wasm"));
		for (String invocation : new String[] { "down(5)", "wide(1000000)" }) {
			ExecResult result = wasmtime.execInContainer("wasmtime", "run", "--invoke", invocation,
					path("test.component.wasm"));
			assertThat(result.getExitCode()).as(invocation).isNotZero();
			assertThat(result.getStderr()).as(invocation).contains("wasm trap");
		}
	}

	@Test
	void noGcComponentHonorsAsAliasAndComposesWithOptimize() throws Exception {
		// :as renames to a valid component label; --optimize tree-shakes the core module
		// before the wrap (unlike the GC path, where --optimize is skipped under
		// --component).
		String program = """
				(defun sum-sq (a b) (* (+ a b) (+ a b)))
				(rontolisp:wasm-export 'sum-sq :as "sum-squared" :params '(:int :int) :returns :int)
				""";
		assertThat(compileNoGcComponentAndInvoke(program, "sum-squared(2, 3)")).isEqualTo("25");
		byte[] optimized = new NoGcWasmCompiler(true, false, true).compile(LispReader.readAllFromString(program));
		wasmtime.copyFileToContainer(Transferable.of(optimized), path("test.component.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "--invoke", "sum-squared(2, 3)",
				path("test.component.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("25");
	}

	private static final String NO_GC_COMPONENT_STRING_PROGRAM = """
			(defun count-a (s)
			  (let ((n 0))
			    (dotimes (i (length s))
			      (when (char= (char s i) #\\a)
			        (setq n (+ n 1))))
			    n))
			(defun shout (s) (concatenate 'string s "!!"))
			(defun greet (s) (concatenate 'string "Hello, " s))
			(rontolisp:wasm-export 'count-a :params '(:string) :returns :int)
			(rontolisp:wasm-export 'shout :params '(:string) :returns :string)
			(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
			""";

	@Test
	void noGcComponentStringExportsLiftThroughTheCanonicalAbi() throws Exception {
		// :string boundaries under --no-gc --component: a string
		// argument is lowered by the host into the module's own memory via the
		// cabi_realloc shim, a string result crosses as typed component-model string
		// through the retptr shim, and the cabi_post_* post-return pops the bump heap
		// afterwards. Both directions (:string->:int and :string->:string), plus UTF-8
		// multi-byte content, all with zero wasmtime flags.
		assertThat(compileNoGcComponentAndInvoke(NO_GC_COMPONENT_STRING_PROGRAM, "count-a(\"banana\")")).isEqualTo("3");
		assertThat(compileNoGcComponentAndInvoke(NO_GC_COMPONENT_STRING_PROGRAM, "shout(\"hello\")"))
			.isEqualTo("\"hello!!\"");
		assertThat(compileNoGcComponentAndInvoke(NO_GC_COMPONENT_STRING_PROGRAM, "greet(\"世界\")"))
			.isEqualTo("\"Hello, 世界\"");
	}

	@Test
	void noGcComponentPrintWorksInsideAsyncLiftedExportsViaTheMicroAdapter() throws Exception {
		// The print micro-adapter: print/princ/terpri inside an export write to stdout
		// through the fixed WASI 0.3 bridge (the core's fd_write import over
		// wasi:cli/stdout.write-via-stream + the async stream/future built-ins, parking
		// on a blocking waitable-set.wait) with ZERO wasmtime flags -- the exports are
		// async lifts (only an async-typed task may block), which is base
		// component-model-async, default-on in wasmtime 46+. Interpreter-identical
		// print output ahead of the WAVE-printed return value. A :string export (with
		// its heap-popping post-return) composes in the same component.
		String program = """
				(defun show (n)
				  (print "hello")
				  (princ n)
				  (terpri)
				  (* n 2))
				(defun shout (s)
				  (princ "loud")
				  (terpri)
				  (concatenate 'string s "!"))
				(rontolisp:wasm-export 'show :params '(:int) :returns :int)
				(rontolisp:wasm-export 'shout :params '(:string) :returns :string)
				""";
		assertThat(compileNoGcComponentAndInvoke(program, "show(21)")).isEqualTo("\"hello\"\n21\n42");
		assertThat(compileNoGcComponentAndInvoke(program, "shout(\"hey\")")).isEqualTo("loud\n\"hey!\"");
	}

	@Test
	void noGcComponentPrintCrossesTheBridgeChunkCap() throws Exception {
		// One princ larger than the 0.2-era bridge's 4096-byte chunk cap crosses intact
		// (the 0.3 bridge pushes the whole iovec through one async stream.write, whose
		// BLOCKED path parks on the waitable-set).
		String program = """
				(defun spam ()
				  (let ((s "0123456789abcdef"))
				    (dotimes (i 4)
				      (setq s (concatenate 'string s s s s)))
				    (princ s)
				    (length s)))
				(rontolisp:wasm-export 'spam :params '() :returns :int)
				""";
		String out = compileNoGcComponentAndInvoke(program, "spam()");
		// 16 * 4^4 = 4096 printed characters, then wasmtime appends the return value.
		assertThat(out).hasSize(4100).endsWith("0123456789abcdef4096");
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
		int plain = new NoGcWasmCompiler(false).compile(parsed).length;
		int optimized = new NoGcWasmCompiler(true).compile(parsed).length;
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

	// Invokes an export of an already-compiled --no-gc module under a hard linear-memory
	// cap (wasmtime -W max-memory-size), returning the raw ExecResult so callers can
	// assert growth (an out-of-bounds trap) as well as flatness (a clean exit).
	private static ExecResult invokeNoGcWithMemoryCap(byte[] wasmBytes, long capBytes, String function, String... args)
			throws Exception {
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		List<String> command = new java.util.ArrayList<>(List.of("wasmtime", "run", "-W", "max-memory-size=" + capBytes,
				"--invoke", function, path("test.wasm")));
		command.addAll(List.of(args));
		return wasmtime.execInContainer(command.toArray(new String[0]));
	}

	@Test
	void noGcPrintWritesToStdoutMatchingTheInterpreter() throws Exception {
		// print = prin1 text + a trailing newline (strings quoted), princ =
		// display text (no quotes, no newline), terpri = a newline -- byte-for-byte the
		// interpreter's output for every line below. The float lines ride the __ftoa
		// port of the GC backend's IEEE-hardened printer (NaN / Infinity / -0.0 by
		// sign bit); a magnitude >= 2^63 prints in the WASM backends' E-notation digit
		// shape (the interpreter's Double.toString shape differs there -- the known
		// large-finite-float print-shape divergence, same as the GC backend).
		String program = """
				(defun show ()
				  (print 42)
				  (print -7)
				  (print 3.14)
				  (print -0.0)
				  (print (/ 0.0 0.0))
				  (print (/ 1.0 0.0))
				  (print (/ -1.0 0.0))
				  (print 9223372036854775808.0)
				  (print "hello")
				  (princ "bare")
				  (terpri)
				  (print t)
				  (print nil)
				  (princ 5)
				  (terpri)
				  (print (concatenate 'string "a" (princ-to-string 2.5))))
				(rontolisp:wasm-export 'show :params '() :returns :void)
				""";
		String expected = """
				42
				-7
				3.14
				-0.0
				NaN
				Infinity
				-Infinity
				9.223372E18
				"hello"
				bare
				T
				NIL
				5
				"a2.5\"""";
		assertThat(compileNoGcAndInvoke(false, program, "show")).isEqualTo(expected);
		// The import + __write_stdout funnel survive the tree shaker under --optimize.
		assertThat(compileNoGcAndInvoke(true, program, "show")).isEqualTo(expected);
	}

	@Test
	void noGcPrintLoopKeepsTheHeapFlat() throws Exception {
		// Each print renders a transient digit string inside an internal heap-pointer
		// mark/reset bracket, so 20000 prints stay within a 2-page memory cap -- a
		// leaking bracket would grow past it and trap.
		String program = """
				(defun ploop (n)
				  (dotimes (i n) (print i))
				  n)
				(rontolisp:wasm-export 'ploop :params '(:int) :returns :int)
				""";
		byte[] wasm = new NoGcWasmCompiler().compile(LispReader.readAllFromString(program));
		ExecResult result = invokeNoGcWithMemoryCap(wasm, 131072, "ploop", "20000");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout()).startsWith("0\n1\n2\n");
		assertThat(result.getStdout().trim()).endsWith("19999\n20000");
	}

	@Test
	void noGcWithArenaKeepsALoopFlatWhereTheBareLoopGrows() throws Exception {
		// The with-arena contract: (with-arena () ...) pops everything allocated inside
		// at the boundary, so 100000 iterations each building an 8KB vector run under a
		// 2-page cap; the SAME loop without the arena has no reclamation within one
		// export call (--no-gc bump-allocates with no free) and traps on the cap.
		String program = """
				(defun work (n)
				  (let ((acc 0.0))
				    (dotimes (i n)
				      (rontolisp:with-arena ()
				        (setq acc (+ acc (vec:sum (vec:ones 1000))))))
				    acc))
				(defun grow (n)
				  (let ((acc 0.0))
				    (dotimes (i n)
				      (setq acc (+ acc (vec:sum (vec:ones 1000)))))
				    acc))
				(defun escaped (n)
				  (vec:aref (rontolisp:with-arena () (vec:ones n)) 0))
				(rontolisp:wasm-export 'work :params '(:int) :returns :float)
				(rontolisp:wasm-export 'grow :params '(:int) :returns :float)
				(rontolisp:wasm-export 'escaped :params '(:int) :returns :float)
				""";
		byte[] wasm = new NoGcWasmCompiler().compile(LispReader.readAllFromString(program));
		ExecResult flat = invokeNoGcWithMemoryCap(wasm, 131072, "work", "100000");
		assertThat(flat.getExitCode()).as("stderr: %s", flat.getStderr()).isZero();
		assertThat(flat.getStdout().trim()).isEqualTo("100000000");
		ExecResult grown = invokeNoGcWithMemoryCap(wasm, 131072, "grow", "100000");
		assertThat(grown.getExitCode()).isNotZero();
		assertThat(grown.getStderr()).contains("out of bounds");
		// The body's value escapes the pop: it is copied down to the mark and stays
		// readable after the arena closes.
		ExecResult escaped = invokeNoGcWithMemoryCap(wasm, 131072, "escaped", "8");
		assertThat(escaped.getExitCode()).as("stderr: %s", escaped.getStderr()).isZero();
		assertThat(escaped.getStdout().trim()).isEqualTo("1");
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

	@Test
	void noGcRunsDoubleFloatVecKernelsWithF64x2Simd() throws Exception {
		// Under --no-gc --simd, the packed double-float (F64VEC) vec: kernels lower to
		// native
		// f64x2 v128 SIMD and
		// run on a plain MVP runtime (no `-W gc`). Results are wrapped in truncate so the
		// host boundary is a deterministic :int (independent of wasmtime's float
		// printing).
		// vec:dot #d(1..5) with itself = 1+4+9+16+25 = 55 (count 5 = one f64x2 pair + odd
		// tail); vec:sum over 7 elements = 280; vec:scale x3 then sum = 45; make-array
		// 'double-float + setf aref building i*i sums to 30 (n=5) / 140 (n=8).
		String program = """
				(defun dot55 (i) (let ((v #d(1.0 2.0 3.0 4.0 5.0))) (truncate (vec:dot v v))))
				(defun sum280 (i) (truncate (vec:sum #d(10.0 20.0 30.0 40.0 50.0 60.0 70.0))))
				(defun scalesum (i) (truncate (vec:sum (vec:scale #d(1.0 2.0 3.0 4.0 5.0) 3.0))))
				(defun buildsum (n)
				  (let ((v (make-array n :element-type 'double-float :initial-element 0.0)) (acc 0))
				    (dotimes (i n) (setf (aref v i) (float (* i i))))
				    (dotimes (i n) (setq acc (+ acc (truncate (aref v i)))))
				    acc))
				(defun consd (i) (truncate (vec:sum (vec:ones 5))))
				(defun arand (i) (truncate (vec:sum (vec:arange 4))))
				(rontolisp:wasm-export 'dot55 :params '(:int) :returns :int)
				(rontolisp:wasm-export 'sum280 :params '(:int) :returns :int)
				(rontolisp:wasm-export 'scalesum :params '(:int) :returns :int)
				(rontolisp:wasm-export 'buildsum :params '(:int) :returns :int)
				(rontolisp:wasm-export 'consd :params '(:int) :returns :int)
				(rontolisp:wasm-export 'arand :params '(:int) :returns :int)
				""";
		assertThat(compileNoGcAndInvoke(false, true, program, "dot55", "0")).isEqualTo("55");
		assertThat(compileNoGcAndInvoke(false, true, program, "sum280", "0")).isEqualTo("280");
		assertThat(compileNoGcAndInvoke(false, true, program, "scalesum", "0")).isEqualTo("45");
		assertThat(compileNoGcAndInvoke(false, true, program, "buildsum", "5")).isEqualTo("30");
		assertThat(compileNoGcAndInvoke(false, true, program, "buildsum", "8")).isEqualTo("140");
		// vec:ones / vec:arange with no element-type build the default F64VEC (the
		// double path is unaffected by the element-type constructor argument).
		assertThat(compileNoGcAndInvoke(false, true, program, "consd", "0")).isEqualTo("5");
		assertThat(compileNoGcAndInvoke(false, true, program, "arand", "0")).isEqualTo("6");
	}

	@Test
	void noGcRunsSingleFloatVecKernelsWithF32x4Simd() throws Exception {
		// Under --no-gc --simd, the packed single-float (F32VEC) vec: kernels lower to
		// native
		// f32x4 v128 SIMD
		// (four lanes per iteration) and run on a plain MVP runtime (no `-W gc`). Inputs
		// are
		// f32-exact (integer-valued) so the f32-throughout computation matches the exact
		// result. Covers every scalar-tail configuration of the count & 3 remainder loop:
		// dot55 #f(1..5) . itself = 55 (count 5 = one f32x4 quad + 1 tail element)
		// sum280 over 7 elements = 280 (one quad + a 3-element remainder loop)
		// addref #f(1 2 3)+#f(10 20 30) = #f(11 22 33); aref = 11 / 33 (count 3 = pure
		// 3-element tail, ZERO quads -- proves the tail loop runs with no SIMD pass)
		// scalesum #f(1..5)*3 then sum = 45
		// buildsum make-array 'single-float + setf aref, i*i sums to 30 (n=5) / 140 (n=8)
		String program = """
				(defun dot55 (i) (let ((v #f(1.0 2.0 3.0 4.0 5.0))) (truncate (vec:dot v v))))
				(defun sum280 (i) (truncate (vec:sum #f(10.0 20.0 30.0 40.0 50.0 60.0 70.0))))
				(defun addref (i)
				  (let* ((a #f(1.0 2.0 3.0)) (b #f(10.0 20.0 30.0)) (c (vec:add a b)))
				    (truncate (aref c i))))
				(defun scalesum (i) (truncate (vec:sum (vec:scale #f(1.0 2.0 3.0 4.0 5.0) 3.0))))
				(defun buildsum (n)
				  (let ((v (make-array n :element-type 'single-float :initial-element 0.0)) (acc 0))
				    (dotimes (i n) (setf (aref v i) (float (* i i))))
				    (dotimes (i n) (setq acc (+ acc (truncate (aref v i)))))
				    acc))
				(defun consf (i) (truncate (vec:sum (vec:ones 5 'single-float))))
				(defun aranf (i) (truncate (vec:sum (vec:arange 4 'single-float))))
				(rontolisp:wasm-export 'dot55 :params '(:int) :returns :int)
				(rontolisp:wasm-export 'sum280 :params '(:int) :returns :int)
				(rontolisp:wasm-export 'addref :params '(:int) :returns :int)
				(rontolisp:wasm-export 'scalesum :params '(:int) :returns :int)
				(rontolisp:wasm-export 'buildsum :params '(:int) :returns :int)
				(rontolisp:wasm-export 'consf :params '(:int) :returns :int)
				(rontolisp:wasm-export 'aranf :params '(:int) :returns :int)
				""";
		assertThat(compileNoGcAndInvoke(false, true, program, "dot55", "0")).isEqualTo("55");
		assertThat(compileNoGcAndInvoke(false, true, program, "sum280", "0")).isEqualTo("280");
		assertThat(compileNoGcAndInvoke(false, true, program, "addref", "0")).isEqualTo("11");
		assertThat(compileNoGcAndInvoke(false, true, program, "addref", "2")).isEqualTo("33");
		assertThat(compileNoGcAndInvoke(false, true, program, "scalesum", "0")).isEqualTo("45");
		assertThat(compileNoGcAndInvoke(false, true, program, "buildsum", "5")).isEqualTo("30");
		assertThat(compileNoGcAndInvoke(false, true, program, "buildsum", "8")).isEqualTo("140");
		// vec:ones / vec:arange with a literal 'single-float construct an F32VEC
		// natively: sum(ones 5) = 5, sum(arange 4) = 0+1+2+3 = 6.
		assertThat(compileNoGcAndInvoke(false, true, program, "consf", "0")).isEqualTo("5");
		assertThat(compileNoGcAndInvoke(false, true, program, "aranf", "0")).isEqualTo("6");
		// --optimize (tree-shaken module) still runs the f32x4 kernels identically.
		assertThat(compileNoGcAndInvoke(true, true, program, "dot55", "0")).isEqualTo("55");
		assertThat(compileNoGcAndInvoke(true, true, program, "sum280", "0")).isEqualTo("280");
	}

	@Test
	void noGcRunsVecKernelsScalarWithoutSimdMatchingTheV128Results() throws Exception {
		// The orthogonal --simd switch: WITHOUT --simd the same vec: kernels lower to
		// plain
		// scalar linear-memory loops (no v128) over the byte-identical [count][data]
		// block
		// and produce the SAME results the f64x2/f32x4 build does -- element-wise
		// bit-for-bit, and reductions bit-for-bit here too because the inputs are exact.
		// This proves --no-gc alone emits a SIMD-proposal-free module that still runs and
		// computes correctly (a portability win). The precise "no 0xFD opcode" byte check
		// lives in NoGcWasmCompilerTest; here we prove the scalar module executes.
		String doubles = """
				(defun dot55 (i) (let ((v #d(1.0 2.0 3.0 4.0 5.0))) (truncate (vec:dot v v))))
				(defun sum280 (i) (truncate (vec:sum #d(10.0 20.0 30.0 40.0 50.0 60.0 70.0))))
				(defun scalesum (i) (truncate (vec:sum (vec:scale #d(1.0 2.0 3.0 4.0 5.0) 3.0))))
				(rontolisp:wasm-export 'dot55 :params '(:int) :returns :int)
				(rontolisp:wasm-export 'sum280 :params '(:int) :returns :int)
				(rontolisp:wasm-export 'scalesum :params '(:int) :returns :int)
				""";
		// simd=false -- the scalar loops, run with no `-W gc` (and no SIMD proposal
		// needed).
		assertThat(compileNoGcAndInvoke(false, false, doubles, "dot55", "0")).isEqualTo("55");
		assertThat(compileNoGcAndInvoke(false, false, doubles, "sum280", "0")).isEqualTo("280");
		assertThat(compileNoGcAndInvoke(false, false, doubles, "scalesum", "0")).isEqualTo("45");
		// Single-float scalar path: f32.load/store, computed in f32, promoted on return.
		// Covers a pure-tail case (count 3 < a lane group) and a mixed case.
		String singles = """
				(defun dot55 (i) (let ((v #f(1.0 2.0 3.0 4.0 5.0))) (truncate (vec:dot v v))))
				(defun addref (i)
				  (let* ((a #f(1.0 2.0 3.0)) (b #f(10.0 20.0 30.0)) (c (vec:add a b)))
				    (truncate (aref c i))))
				(rontolisp:wasm-export 'dot55 :params '(:int) :returns :int)
				(rontolisp:wasm-export 'addref :params '(:int) :returns :int)
				""";
		assertThat(compileNoGcAndInvoke(false, false, singles, "dot55", "0")).isEqualTo("55");
		assertThat(compileNoGcAndInvoke(false, false, singles, "addref", "0")).isEqualTo("11");
		assertThat(compileNoGcAndInvoke(false, false, singles, "addref", "2")).isEqualTo("33");
	}

	@Test
	void noGcRunsDestinationPassingVecKernelsUnderBothLowerings() throws Exception {
		// The -into kernels write into the caller's [count][data] block instead
		// of bump-allocating a fresh one, so a loop over them keeps the (never-freed)
		// linear heap flat. Here we prove the emitted loops COMPUTE correctly on both
		// lowerings and both widths; the "no __alloc call in the kernel" property is
		// asserted structurally in NoGcWasmCompilerTest, and the resulting flat peak RSS
		// is measured in .kb/vec.md.
		String doubles = """
				(defun addinto (i)
				  (let* ((o (vec:zeros 5)) (a #d(1.0 2.0 3.0 4.0 5.0)))
				    (truncate (aref (vec:add-into o a a) i))))
				(defun scaleinto (i)
				  (let* ((o (vec:zeros 5)) (a #d(1.0 2.0 3.0 4.0 5.0)))
				    (truncate (vec:sum (vec:scale-into o a 3.0)))))
				(defun accumulate (iters)
				  ;; in-place accumulation: out aliases the first operand, allocating nothing
				  ;; inside the loop. acc[3] = 4 * iters.
				  (let ((acc (vec:zeros 5)) (d #d(1.0 2.0 3.0 4.0 5.0)))
				    (dotimes (i iters) (vec:add-into acc acc d))
				    (truncate (aref acc 3))))
				(rontolisp:wasm-export 'addinto :params '(:int) :returns :int)
				(rontolisp:wasm-export 'scaleinto :params '(:int) :returns :int)
				(rontolisp:wasm-export 'accumulate :params '(:int) :returns :int)
				""";
		for (boolean simd : new boolean[] { false, true }) {
			assertThat(compileNoGcAndInvoke(false, simd, doubles, "addinto", "0")).isEqualTo("2");
			assertThat(compileNoGcAndInvoke(false, simd, doubles, "addinto", "4")).isEqualTo("10");
			assertThat(compileNoGcAndInvoke(false, simd, doubles, "scaleinto", "0")).isEqualTo("45");
			// 100000 iterations x a fresh 5-element vector would be ~4.4 MB of bump heap
			// in
			// the allocating form; -into allocates once, before the loop.
			assertThat(compileNoGcAndInvoke(false, simd, doubles, "accumulate", "100000")).isEqualTo("400000");
		}
		// Single-float: the f32 stride, a pure scalar-tail count (3) and a lane-group
		// count.
		String singles = """
				(defun addinto (i)
				  (let* ((o (vec:zeros 3 'single-float)) (a #f(1.0 2.0 3.0)) (b #f(10.0 20.0 30.0)))
				    (truncate (aref (vec:add-into o a b) i))))
				(defun mulinto (i)
				  (let* ((o (vec:zeros 8 'single-float)) (a (vec:arange 8 'single-float)))
				    (truncate (vec:sum (vec:mul-into o a a)))))
				(rontolisp:wasm-export 'addinto :params '(:int) :returns :int)
				(rontolisp:wasm-export 'mulinto :params '(:int) :returns :int)
				""";
		for (boolean simd : new boolean[] { false, true }) {
			assertThat(compileNoGcAndInvoke(false, simd, singles, "addinto", "0")).isEqualTo("11");
			assertThat(compileNoGcAndInvoke(false, simd, singles, "addinto", "2")).isEqualTo("33");
			// sum of i^2 for i in 0..7 = 140
			assertThat(compileNoGcAndInvoke(false, simd, singles, "mulinto", "0")).isEqualTo("140");
		}
	}

	@Test
	void noGcRunsUnaryUfuncsUnderBothLowerings() throws Exception {
		// The arithmetic unary ufuncs: sqrt / abs / square / negative /
		// reciprocal + -into, both widths, both lowerings (scalar loops by default,
		// v128 under --simd), matching the interpreter oracle on exact inputs. exp /
		// sign lower natively too and are covered separately below.
		String doubles = """
				(defun ufuncs (i)
				  (let* ((v #d(-3.0 4.0 -5.0 12.0 -2.0))
				         (s (vec:sqrt (vec:square v)))
				         (a (vec:abs v))
				         (n (vec:negative v))
				         (r (vec:reciprocal #d(2.0 4.0 8.0 16.0 32.0))))
				    (truncate (+ (vec:sum s) (vec:sum a) (vec:sum n) (* 32.0 (vec:sum r))))))
				(defun intos (i)
				  (let ((o (vec:zeros 5)) (v #d(-3.0 4.0 -5.0 12.0 -2.0)))
				    (vec:square-into o v)
				    (vec:sqrt-into o o)
				    (vec:negative-into o o)
				    (vec:abs-into o o)
				    (truncate (aref o i))))
				(rontolisp:wasm-export 'ufuncs :params '(:int) :returns :int)
				(rontolisp:wasm-export 'intos :params '(:int) :returns :int)
				""";
		for (boolean simd : new boolean[] { false, true }) {
			// sum s = 26, sum |v| = 26, sum -v = -6, 32 * sum(1/2^k) = 31 -> 26+26-6+31 =
			// 77
			assertThat(compileNoGcAndInvoke(false, simd, doubles, "ufuncs", "0")).isEqualTo("77");
			assertThat(compileNoGcAndInvoke(false, simd, doubles, "intos", "0")).isEqualTo("3");
			assertThat(compileNoGcAndInvoke(false, simd, doubles, "intos", "3")).isEqualTo("12");
		}
		String singles = """
				(defun ufuncs (i)
				  (let* ((v (vec:negative (vec:arange 6 'single-float)))
				         (a (vec:abs v))
				         (s (vec:sqrt (vec:square v)))
				         (r (vec:reciprocal #f(2.0 4.0 8.0))))
				    (truncate (+ (vec:sum a) (vec:sum s) (* 8.0 (vec:sum r))))))
				(rontolisp:wasm-export 'ufuncs :params '(:int) :returns :int)
				""";
		for (boolean simd : new boolean[] { false, true }) {
			// sum a = 15, sum s = 15, 8 * (1/2 + 1/4 + 1/8) = 7 -> 37
			assertThat(compileNoGcAndInvoke(false, simd, singles, "ufuncs", "0")).isEqualTo("37");
		}
	}

	@Test
	void noGcRunsExpAndSignUnderBothLowerings() throws Exception {
		// vec:exp / vec:sign (+ -into) reuse the GC backend's
		// raw-f64 emitters (the WasmExpCompiler software approximation and the
		// (x>0)-(x<0) sign), so a --no-gc value equals the wasm-GC backend's exactly
		// at both widths -- the nontrivial exp probes are compared against a wasm-GC
		// run rather than a hardcoded constant, the exact ones (exp(0) = 1, sign) to
		// literals. Both lowerings drive the same element loop.
		String wasmGcExpD = compileAndRunVec("(print (truncate (* 1000000 (vec:aref (vec:exp #d(1.0)) 0))))", false);
		String wasmGcExpF = compileAndRunVec("(print (truncate (* 1000000 (vec:aref (vec:exp #f(1.0)) 0))))", false);
		String source = """
				(defun expd (i) (truncate (* 1000000 (vec:aref (vec:exp #d(1.0)) 0))))
				(defun expf (i) (truncate (* 1000000 (vec:aref (vec:exp #f(1.0)) 0))))
				(defun expzero (i) (truncate (vec:sum (vec:exp (vec:zeros 3)))))
				(defun sgn (i) (truncate (vec:aref (vec:sign #d(-3.5 0.0 7.25)) i)))
				(defun sgninto (i)
				  (let ((o (vec:zeros 3)))
				    (vec:sign-into o #d(-3.5 0.0 7.25))
				    (vec:sign-into o o)
				    (truncate (+ (* 100 (vec:aref o 0)) (* 10 (vec:aref o 1)) (vec:aref o 2)))))
				(defun sgnf (i)
				  (truncate (vec:sum (vec:sign (vec:negative (vec:arange 4 'single-float))))))
				(rontolisp:wasm-export 'expd :params '(:int) :returns :int)
				(rontolisp:wasm-export 'expf :params '(:int) :returns :int)
				(rontolisp:wasm-export 'expzero :params '(:int) :returns :int)
				(rontolisp:wasm-export 'sgn :params '(:int) :returns :int)
				(rontolisp:wasm-export 'sgninto :params '(:int) :returns :int)
				(rontolisp:wasm-export 'sgnf :params '(:int) :returns :int)
				""";
		for (boolean simd : new boolean[] { false, true }) {
			assertThat(compileNoGcAndInvoke(false, simd, source, "expd", "0")).isEqualTo(wasmGcExpD);
			assertThat(compileNoGcAndInvoke(false, simd, source, "expf", "0")).isEqualTo(wasmGcExpF);
			assertThat(compileNoGcAndInvoke(false, simd, source, "expzero", "0")).isEqualTo("3");
			assertThat(compileNoGcAndInvoke(false, simd, source, "sgn", "0")).isEqualTo("-1");
			assertThat(compileNoGcAndInvoke(false, simd, source, "sgn", "1")).isEqualTo("0");
			assertThat(compileNoGcAndInvoke(false, simd, source, "sgn", "2")).isEqualTo("1");
			// sign-into aliases its operand (sign of sign is sign): -100 + 0 + 1.
			assertThat(compileNoGcAndInvoke(false, simd, source, "sgninto", "0")).isEqualTo("-99");
			// negative(arange) = (-0.0 -1.0 -2.0 -3.0); sign maps -0.0 to 0.0 here
			// (the wasm family's own edge), so the sum is -3.
			assertThat(compileNoGcAndInvoke(false, simd, source, "sgnf", "0")).isEqualTo("-3");
		}
	}

	@Test
	void noGcRunsLogAndTanhUnderBothLowerings() throws Exception {
		// vec:log / vec:tanh (+ -into) reuse the GC backend's raw-f64
		// emitters (the WasmLogCompiler atanh series, the WasmTanhCompiler clamped exp
		// derivation), so a --no-gc value equals the wasm-GC backend's exactly at both
		// widths -- the nontrivial probes are compared against a wasm-GC run, the exact
		// ones (log(1) = 0, tanh(0) = 0, the tanh saturation) to literals.
		String wasmGcLogD = compileAndRunVec("(print (truncate (* 1000000 (vec:aref (vec:log #d(10.0)) 0))))", false);
		String wasmGcLogF = compileAndRunVec("(print (truncate (* 1000000 (vec:aref (vec:log #f(10.0)) 0))))", false);
		String wasmGcTanhD = compileAndRunVec("(print (truncate (* 1000000 (vec:aref (vec:tanh #d(1.0)) 0))))", false);
		String wasmGcTanhF = compileAndRunVec("(print (truncate (* 1000000 (vec:aref (vec:tanh #f(1.0)) 0))))", false);
		String source = """
				(defun logd (i) (truncate (* 1000000 (vec:aref (vec:log #d(10.0)) 0))))
				(defun logf (i) (truncate (* 1000000 (vec:aref (vec:log #f(10.0)) 0))))
				(defun logone (i) (truncate (+ 5 (vec:sum (vec:log (vec:ones 3))))))
				(defun loginto (i)
				  (let ((v #d(1.0 1.0 1.0 1.0)))
				    (vec:log-into v v)
				    (truncate (+ 5 (vec:sum v)))))
				(defun tanhd (i) (truncate (* 1000000 (vec:aref (vec:tanh #d(1.0)) 0))))
				(defun tanhf (i) (truncate (* 1000000 (vec:aref (vec:tanh #f(1.0)) 0))))
				(defun tanhsat (i) (truncate (vec:aref (vec:tanh #d(-25.0 0.0 25.0)) i)))
				(defun tanhinto (i)
				  (let ((o (vec:zeros 3)))
				    (vec:tanh-into o #d(-25.0 0.0 25.0))
				    (truncate (+ (* 100 (vec:aref o 0)) (* 10 (vec:aref o 1)) (vec:aref o 2)))))
				(rontolisp:wasm-export 'logd :params '(:int) :returns :int)
				(rontolisp:wasm-export 'logf :params '(:int) :returns :int)
				(rontolisp:wasm-export 'logone :params '(:int) :returns :int)
				(rontolisp:wasm-export 'loginto :params '(:int) :returns :int)
				(rontolisp:wasm-export 'tanhd :params '(:int) :returns :int)
				(rontolisp:wasm-export 'tanhf :params '(:int) :returns :int)
				(rontolisp:wasm-export 'tanhsat :params '(:int) :returns :int)
				(rontolisp:wasm-export 'tanhinto :params '(:int) :returns :int)
				""";
		for (boolean simd : new boolean[] { false, true }) {
			assertThat(compileNoGcAndInvoke(false, simd, source, "logd", "0")).isEqualTo(wasmGcLogD);
			assertThat(compileNoGcAndInvoke(false, simd, source, "logf", "0")).isEqualTo(wasmGcLogF);
			assertThat(compileNoGcAndInvoke(false, simd, source, "logone", "0")).isEqualTo("5");
			assertThat(compileNoGcAndInvoke(false, simd, source, "loginto", "0")).isEqualTo("5");
			assertThat(compileNoGcAndInvoke(false, simd, source, "tanhd", "0")).isEqualTo(wasmGcTanhD);
			assertThat(compileNoGcAndInvoke(false, simd, source, "tanhf", "0")).isEqualTo(wasmGcTanhF);
			assertThat(compileNoGcAndInvoke(false, simd, source, "tanhsat", "0")).isEqualTo("-1");
			assertThat(compileNoGcAndInvoke(false, simd, source, "tanhsat", "1")).isEqualTo("0");
			assertThat(compileNoGcAndInvoke(false, simd, source, "tanhsat", "2")).isEqualTo("1");
			// tanh-into then read back the saturated triple: -100 + 0 + 1.
			assertThat(compileNoGcAndInvoke(false, simd, source, "tanhinto", "0")).isEqualTo("-99");
		}
	}

	@Test
	void noGcRunsSinCosTanUnderBothLowerings() throws Exception {
		// vec:sin / vec:cos / vec:tan (+ -into) reuse
		// the GC backend's raw-f64 emitter (the WasmSinCosCompiler Cody-Waite
		// reduction), so a --no-gc value equals the wasm-GC backend's exactly at both
		// widths -- the nontrivial probes are compared against a wasm-GC run, the exact
		// ones (sin(0) = 0, cos(0) = 1, tan(0) = 0) to literals.
		String wasmGcSinD = compileAndRunVec("(print (truncate (* 1000000 (vec:aref (vec:sin #d(1.0 -2.5 100.0)) 2))))",
				false);
		String wasmGcSinF = compileAndRunVec("(print (truncate (* 1000000 (vec:aref (vec:sin #f(1.0 -2.5 100.0)) 1))))",
				false);
		String wasmGcCosD = compileAndRunVec("(print (truncate (* 1000000 (vec:aref (vec:cos #d(-2.5)) 0))))", false);
		String wasmGcTanD = compileAndRunVec("(print (truncate (* 1000000 (vec:aref (vec:tan #d(2.0)) 0))))", false);
		String source = """
				(defun sind (i) (truncate (* 1000000 (vec:aref (vec:sin #d(1.0 -2.5 100.0)) 2))))
				(defun sinf (i) (truncate (* 1000000 (vec:aref (vec:sin #f(1.0 -2.5 100.0)) 1))))
				(defun cosd (i) (truncate (* 1000000 (vec:aref (vec:cos #d(-2.5)) 0))))
				(defun tand (i) (truncate (* 1000000 (vec:aref (vec:tan #d(2.0)) 0))))
				(defun zeros (i)
				  (truncate (+ (vec:aref (vec:sin (vec:zeros 1)) 0)
				               (vec:sum (vec:cos (vec:zeros 3)))
				               (vec:aref (vec:tan (vec:zeros 1)) 0))))
				(defun sininto (i)
				  (let ((o (vec:zeros 3)))
				    (vec:sin-into o (vec:zeros 3))
				    (vec:cos-into o o)
				    (truncate (vec:sum (vec:tan-into o o)))))
				(rontolisp:wasm-export 'sind :params '(:int) :returns :int)
				(rontolisp:wasm-export 'sinf :params '(:int) :returns :int)
				(rontolisp:wasm-export 'cosd :params '(:int) :returns :int)
				(rontolisp:wasm-export 'tand :params '(:int) :returns :int)
				(rontolisp:wasm-export 'zeros :params '(:int) :returns :int)
				(rontolisp:wasm-export 'sininto :params '(:int) :returns :int)
				""";
		for (boolean simd : new boolean[] { false, true }) {
			assertThat(compileNoGcAndInvoke(false, simd, source, "sind", "0")).isEqualTo(wasmGcSinD);
			assertThat(compileNoGcAndInvoke(false, simd, source, "sinf", "0")).isEqualTo(wasmGcSinF);
			assertThat(compileNoGcAndInvoke(false, simd, source, "cosd", "0")).isEqualTo(wasmGcCosD);
			assertThat(compileNoGcAndInvoke(false, simd, source, "tand", "0")).isEqualTo(wasmGcTanD);
			// sin(0) + 3 * cos(0) + tan(0) = 3.
			assertThat(compileNoGcAndInvoke(false, simd, source, "zeros", "0")).isEqualTo("3");
			// tan(cos(sin(0))) per element = tan(1); 3 * tan(1) truncates to 4.
			assertThat(compileNoGcAndInvoke(false, simd, source, "sininto", "0")).isEqualTo("4");
		}
	}

	@Test
	void noGcRunsArcAndHyperbolicUnderBothLowerings() throws Exception {
		// vec:asin / vec:acos / vec:atan / vec:sinh /
		// vec:cosh (+ -into) reuse the GC backend's raw-f64 emitters
		// (WasmAtanCompiler's fold-and-series, WasmSinhCoshCompiler's exp derivation),
		// so a --no-gc value equals the wasm-GC backend's exactly at both widths -- the
		// nontrivial probes are compared against a wasm-GC run, the exact ones
		// (atan(0) = asin(0) = sinh(0) = 0, acos(1) = 0, cosh(0) = 1) to literals.
		String wasmGcAtanD = compileAndRunVec(
				"(print (truncate (* 1000000 (vec:aref (vec:atan #d(1.0 -2.5 100.0)) 1))))", false);
		String wasmGcAsinF = compileAndRunVec("(print (truncate (* 1000000 (vec:aref (vec:asin #f(0.5 -0.5)) 0))))",
				false);
		String wasmGcAcosD = compileAndRunVec("(print (truncate (* 1000000 (vec:aref (vec:acos #d(-0.5)) 0))))", false);
		String wasmGcSinhD = compileAndRunVec("(print (truncate (* 1000000 (vec:aref (vec:sinh #d(0.1 2.0)) 1))))",
				false);
		String wasmGcCoshF = compileAndRunVec("(print (truncate (* 1000000 (vec:aref (vec:cosh #f(2.0)) 0))))", false);
		String source = """
				(defun atand (i) (truncate (* 1000000 (vec:aref (vec:atan #d(1.0 -2.5 100.0)) 1))))
				(defun asinf (i) (truncate (* 1000000 (vec:aref (vec:asin #f(0.5 -0.5)) 0))))
				(defun acosd (i) (truncate (* 1000000 (vec:aref (vec:acos #d(-0.5)) 0))))
				(defun sinhd (i) (truncate (* 1000000 (vec:aref (vec:sinh #d(0.1 2.0)) 1))))
				(defun coshf (i) (truncate (* 1000000 (vec:aref (vec:cosh #f(2.0)) 0))))
				(defun anchors (i)
				  (truncate (+ (vec:aref (vec:atan (vec:zeros 1)) 0)
				               (vec:aref (vec:asin (vec:zeros 1)) 0)
				               (vec:aref (vec:acos (vec:ones 1)) 0)
				               (vec:aref (vec:sinh (vec:zeros 1)) 0)
				               (vec:sum (vec:cosh (vec:zeros 3))))))
				(defun arcinto (i)
				  (let ((o (vec:zeros 3)))
				    (vec:asin-into o (vec:zeros 3))
				    (vec:acos-into o (vec:atan-into o o))
				    (vec:cosh-into o (vec:sinh-into o o))
				    (truncate (* 1000000 (vec:aref o 0)))))
				(rontolisp:wasm-export 'atand :params '(:int) :returns :int)
				(rontolisp:wasm-export 'asinf :params '(:int) :returns :int)
				(rontolisp:wasm-export 'acosd :params '(:int) :returns :int)
				(rontolisp:wasm-export 'sinhd :params '(:int) :returns :int)
				(rontolisp:wasm-export 'coshf :params '(:int) :returns :int)
				(rontolisp:wasm-export 'anchors :params '(:int) :returns :int)
				(rontolisp:wasm-export 'arcinto :params '(:int) :returns :int)
				""";
		// The -into chain, computed once against the wasm-GC backend too.
		String wasmGcInto = compileAndRunVec("""
				(let ((o (vec:zeros 3)))
				  (vec:asin-into o (vec:zeros 3))
				  (vec:acos-into o (vec:atan-into o o))
				  (vec:cosh-into o (vec:sinh-into o o))
				  (print (truncate (* 1000000 (vec:aref o 0)))))
				""", false);
		for (boolean simd : new boolean[] { false, true }) {
			assertThat(compileNoGcAndInvoke(false, simd, source, "atand", "0")).isEqualTo(wasmGcAtanD);
			assertThat(compileNoGcAndInvoke(false, simd, source, "asinf", "0")).isEqualTo(wasmGcAsinF);
			assertThat(compileNoGcAndInvoke(false, simd, source, "acosd", "0")).isEqualTo(wasmGcAcosD);
			assertThat(compileNoGcAndInvoke(false, simd, source, "sinhd", "0")).isEqualTo(wasmGcSinhD);
			assertThat(compileNoGcAndInvoke(false, simd, source, "coshf", "0")).isEqualTo(wasmGcCoshF);
			// atan(0) + asin(0) + acos(1) + sinh(0) + 3 * cosh(0) = 3.
			assertThat(compileNoGcAndInvoke(false, simd, source, "anchors", "0")).isEqualTo("3");
			assertThat(compileNoGcAndInvoke(false, simd, source, "arcinto", "0")).isEqualTo(wasmGcInto);
		}
	}

	@Test
	void noGcRunsComparisonSelectsUnderBothLowerings() throws Exception {
		// vec:maximum / vec:minimum / vec:relu / vec:clip (+ -into)
		// are strict-comparison selects ((if (> x y) x y) and its mirrors), so every
		// probe value is exact and pinned to a literal -- and, unlike exp/sign, the
		// values agree with every other backend, because a select only copies input
		// bits and > agrees on every backend. maximum/minimum run v128
		// gt/lt+bitselect under --simd and a compare+select scalar loop otherwise;
		// relu rides the U_RELU map1 form; clip is the same element loop in both
		// modes.
		String source = """
				(defun mkab (a b)
				  (dotimes (i 5)
				    (vec:aset a i (- (float i) 2.0))
				    (vec:aset b i (- 2.0 (float i)))))
				(defun probe (i)
				  (let ((a (vec:zeros 5))
				        (b (vec:zeros 5)))
				    (mkab a b)
				    (round (+ (vec:sum (vec:maximum a b))
				              (* 100 (vec:sum (vec:minimum a b)))
				              (* 10000 (vec:sum (vec:relu a)))
				              (* 1000000 (vec:sum (vec:clip a -1.0 1.0)))))))
				(defun probef (i)
				  (let ((a (vec:zeros 5 'single-float))
				        (out (vec:zeros 5 'single-float)))
				    (dotimes (i 5)
				      (vec:aset a i (- (float i) 2.0)))
				    (vec:relu-into out a)
				    (vec:maximum-into out out a)
				    (vec:clip-into out out -1.5 1.5)
				    (round (* 100 (vec:sum out)))))
				(defun probeinto (i)
				  (let ((a (vec:zeros 5))
				        (b (vec:zeros 5))
				        (o (vec:zeros 5)))
				    (mkab a b)
				    (vec:minimum-into o a b)
				    (vec:maximum-into o o a)
				    (round (vec:sum (vec:relu-into o o)))))
				(rontolisp:wasm-export 'probe :params '(:int) :returns :int)
				(rontolisp:wasm-export 'probef :params '(:int) :returns :int)
				(rontolisp:wasm-export 'probeinto :params '(:int) :returns :int)
				""";
		for (boolean simd : new boolean[] { false, true }) {
			// a = (-2 -1 0 1 2), b = (2 1 0 -1 -2): maximum sums to 6, minimum to -6,
			// relu(a) to 3, clip(a, -1, 1) to 0 -> 6 - 600 + 30000 + 0 = 29406.
			assertThat(compileNoGcAndInvoke(false, simd, source, "probe", "0")).isEqualTo("29406");
			// relu(a) = (0 0 0 1 2); maximum with a is unchanged; clip to [-1.5, 1.5]
			// gives (0 0 0 1 1.5), whose sum is 2.5 -> 250.
			assertThat(compileNoGcAndInvoke(false, simd, source, "probef", "0")).isEqualTo("250");
			// minimum(a, b) = (-2 -1 0 -1 -2); maximum with a = (-2 -1 0 1 2);
			// relu of that = (0 0 0 1 2) -> 3. Exercises aliasing at every step.
			assertThat(compileNoGcAndInvoke(false, simd, source, "probeinto", "0")).isEqualTo("3");
		}
	}

	@Test
	void noGcRunsMatvecGemvOverTheRank2PackedMatrix() throws Exception {
		// vec:matvec (GEMV) over the rank-2 packed matrix layout
		// [rows][cols][data], built with (make-array (list d n) :element-type ...) +
		// two-subscript setf aref, under BOTH lowerings (per-row f64x2/f32x4 dot under
		// --simd, a v128-free scalar loop otherwise). Expected values are hand-computed
		// exact integers, verified against the wasm-GC oracle. The f64 shape d=3, n=5
		// exercises two f64x2 pairs + the odd tail per row; W[r][c] = 10r + c against
		// x = (1..5) gives y[r] = 150r + 40. row-major-aref reads the same block flat
		// (element 6 = W[1][1] = 11).
		String doubles = """
				(defun gemv (i)
				  (let ((w (make-array (list 3 5) :element-type 'double-float))
				        (x #d(1.0 2.0 3.0 4.0 5.0)))
				    (dotimes (r 3)
				      (dotimes (c 5)
				        (setf (aref w r c) (float (+ (* r 10) c)))))
				    (truncate (aref (vec:matvec w x) i))))
				(defun gemvinto (i)
				  (let ((w (make-array (list 3 5) :element-type 'double-float))
				        (x #d(1.0 2.0 3.0 4.0 5.0))
				        (o (vec:zeros 3)))
				    (dotimes (r 3)
				      (dotimes (c 5)
				        (setf (aref w r c) (float (+ (* r 10) c)))))
				    (vec:matvec-into o w x)
				    (truncate (aref o i))))
				(defun flat (i)
				  (let ((w (make-array '(3 5) :element-type 'double-float :initial-element 0.0)))
				    (setf (aref w 1 1) 11.0)
				    (truncate (row-major-aref w i))))
				(rontolisp:wasm-export 'gemv :params '(:int) :returns :int)
				(rontolisp:wasm-export 'gemvinto :params '(:int) :returns :int)
				(rontolisp:wasm-export 'flat :params '(:int) :returns :int)
				""";
		// The f32 shape d=2, n=6 exercises one f32x4 quad + a 2-element remainder loop
		// per row; all inputs integer-valued, so f32-throughout is exact. W[r][c] =
		// 10r + c against x = (1..6): y[0] = 70, y[1] = 10*21 + 70 = 280.
		String singles = """
				(defun gemvf (i)
				  (let ((w (make-array (list 2 6) :element-type 'single-float))
				        (x #f(1.0 2.0 3.0 4.0 5.0 6.0)))
				    (dotimes (r 2)
				      (dotimes (c 6)
				        (setf (aref w r c) (float (+ (* r 10) c)))))
				    (truncate (aref (vec:matvec w x) i))))
				(rontolisp:wasm-export 'gemvf :params '(:int) :returns :int)
				""";
		for (boolean simd : new boolean[] { false, true }) {
			assertThat(compileNoGcAndInvoke(false, simd, doubles, "gemv", "0")).isEqualTo("40");
			assertThat(compileNoGcAndInvoke(false, simd, doubles, "gemv", "1")).isEqualTo("190");
			assertThat(compileNoGcAndInvoke(false, simd, doubles, "gemv", "2")).isEqualTo("340");
			assertThat(compileNoGcAndInvoke(false, simd, doubles, "gemvinto", "2")).isEqualTo("340");
			assertThat(compileNoGcAndInvoke(false, simd, doubles, "flat", "6")).isEqualTo("11");
			assertThat(compileNoGcAndInvoke(false, simd, doubles, "flat", "5")).isEqualTo("0");
			assertThat(compileNoGcAndInvoke(false, simd, singles, "gemvf", "0")).isEqualTo("70");
			assertThat(compileNoGcAndInvoke(false, simd, singles, "gemvf", "1")).isEqualTo("280");
		}
		// Composes with the tree shaker (--optimize).
		assertThat(compileNoGcAndInvoke(true, true, doubles, "gemv", "1")).isEqualTo("190");
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
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		List<String> command = new java.util.ArrayList<>(
				List.of("wasmtime", "run", "--invoke", function, "-W", "gc", "-W", "exceptions=y", path("test.wasm")));
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
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
				path("test.wasm"));
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
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
				path("test.wasm"));
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
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "--invoke", "shout", "-W", "gc", "-W",
				"exceptions=y", path("test.wasm"), "7");
		assertThat(result.getExitCode()).isNotZero();
		assertThat(result.getStderr()).contains("unreachable");
	}

	@Test
	void mapFamilyTrapsOnNonList() throws Exception {
		// The map* family operates on lists; a non-list (e.g. a string) traps rather than
		// silently returning nil, matching the interpreter. WASM error is an
		// unreachable trap (it carries no message).
		for (String form : List.of("(mapcar #'identity \"abc\")", "(mapc #'identity \"abc\")",
				"(mapcan #'list \"abc\")", "(maplist #'identity \"abc\")", "(mapcon #'list \"abc\")")) {
			byte[] wasmBytes = new WasmLispCompiler().compile(LispReader.readAllFromString(form));
			wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
			ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
					path("test.wasm"));
			assertThat(result.getExitCode()).as("expected a trap for: %s", form).isNotZero();
			assertThat(result.getStderr()).as("trap message for: %s", form).contains("unreachable");
		}
		// nil (the empty list) and proper lists stay accepted (no trap).
		assertThat(compileAndRun("(print (mapcar #'1+ '(1 2 3))) (print (mapcar #'1+ nil))")).isEqualTo("(2 3 4)\nNIL");
		assertThat(compileAndRun("(print (maplist #'identity nil))")).isEqualTo("NIL");
	}

	@Test
	void loopMacroCompilesAndRuns() throws Exception {
		assertThat(compileAndRun("(print (loop for i from 1 to 5 collect i))")).isEqualTo("(1 2 3 4 5)");
		assertThat(compileAndRun("(print (loop for x in '(a b c) for i from 0 collect (list i x)))"))
			.isEqualTo("((0 A) (1 B) (2 C))");
		assertThat(compileAndRun("(print (loop for i from 1 to 5 sum i))")).isEqualTo("15");
		assertThat(compileAndRun("(print (loop for i from 1 to 10 when (evenp i) collect i))"))
			.isEqualTo("(2 4 6 8 10)");
		assertThat(compileAndRun("(print (loop repeat 3 collect 'x))")).isEqualTo("(X X X)");
		assertThat(compileAndRun("(print (loop for i from 1 do (when (> i 3) (return i))))")).isEqualTo("4");
		assertThat(compileAndRun("(print (loop for c across \"hello\" collect c))"))
			.isEqualTo("(#\\h #\\e #\\l #\\l #\\o)");
		// Lite `being` package iteration: parses and iterates the empty sequence.
		assertThat(compileAndRun("(print (loop for s being the external-symbols of :cl collect s))")).isEqualTo("NIL");
	}

	@Test
	void loopExtendedClausesCompileAndRun() throws Exception {
		// Positional while, thereis/always, anaphoric it, loop-finish, parallel and,
		// destructuring.
		assertThat(compileAndRun("(print (loop for x in '(1 2 3 9 4) while (< x 4) collect x))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (loop for x in '(nil nil 7 9) thereis x))")).isEqualTo("7");
		assertThat(compileAndRun("(print (loop for x in '(1 2 9) always (< x 5)))")).isEqualTo("NIL");
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
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("test.component.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
				path("test.component.wasm"));
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
		// get-internal-real-time is an exact integer, so the elapsed reads as integer
		// ms, like the interpreter and JVM.
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

	// Compiles a (rontolisp:wasm-export ...) program in component mode and calls one
	// export through the canonical ABI with WAVE syntax: `wasmtime run --invoke
	// 'name(args)'`. Unlike core-module --invoke this is the supported path, so stderr
	// must carry no "experimental" warning.
	private static String compileAndInvokeComponent(String lispCode, String waveInvocation) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("test.component.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--invoke",
				waveInvocation, path("test.component.wasm"));
		assertThat(result.getExitCode())
			.as("exit code for component invoke %s: %s\nstderr: %s", waveInvocation, lispCode, result.getStderr())
			.isZero();
		assertThat(result.getStderr()).as("WAVE invoke on a component is the supported path, no experimental warning")
			.doesNotContain("experimental");
		return result.getStdout().trim();
	}

	private static final String COMPONENT_EXPORT_PROGRAM = """
			(defun sumsquared (a b) (* (+ a b) (+ a b)))
			(defun half (x) (/ x 2.0))
			(defun bigp (n) (> n 100))
			(defun quiet-square (n) (* n n))
			(rontolisp:wasm-export 'sumsquared :params '(:int :int) :returns :int)
			(rontolisp:wasm-export 'half :params '(:float) :returns :float)
			(rontolisp:wasm-export 'bigp :params '(:int) :returns :bool)
			(rontolisp:wasm-export 'quiet-square :params '(:int))
			(print (sumsquared 2 3))
			""";

	@Test
	void componentExportScalarFunctionsCallableViaWaveInvoke() throws Exception {
		// Each Tier-1 scalar type crosses the canonical ABI: :int -> s32, :float -> f64,
		// :bool -> bool (WAVE prints true/false), omitted :returns -> no result (the
		// body runs, its value is discarded, nothing is printed). Component exports are
		// lifted synchronously by default, so they must be pure-compute: I/O inside one
		// (print) hits a blocking stream built-in and traps ("cannot block a synchronous
		// task"); an I/O-bearing export opts into the stackful-async lift with :async t
		// (see componentAsyncExportAllowsIoInside).
		assertThat(compileAndInvokeComponent(COMPONENT_EXPORT_PROGRAM, "sumsquared(2, 3)")).isEqualTo("25");
		assertThat(compileAndInvokeComponent(COMPONENT_EXPORT_PROGRAM, "half(7.0)")).isEqualTo("3.5");
		assertThat(compileAndInvokeComponent(COMPONENT_EXPORT_PROGRAM, "bigp(101)")).isEqualTo("true");
		assertThat(compileAndInvokeComponent(COMPONENT_EXPORT_PROGRAM, "bigp(5)")).isEqualTo("false");
		// wasmtime prints a void invocation's (absent) result as the empty tuple ().
		assertThat(compileAndInvokeComponent(COMPONENT_EXPORT_PROGRAM, "quiet-square(6)")).isEqualTo("()");
	}

	@Test
	void componentExportCarriesTheWholeUnsignedRange() throws Exception {
		// The canonical component-model tutorial world's type. A u32 above the i31 house
		// range crosses exactly, because the wrapper boxes it the way this backend
		// represents every wide integer -- as a boxed exact integer (TYPE_BIGNUM).
		// Lifting it as s32 instead would not merely print differently: wasm-tools
		// component targets rejects the component against its own world, since the
		// component model has no integer subtyping.
		String program = """
				(defun bump (n) (+ n 1))
				(rontolisp:wasm-export 'bump :params '(:u32) :returns :u32)
				""";
		assertThat(compileAndInvokeComponent(program, "bump(1073741824)")).isEqualTo("1073741825");
		assertThat(compileAndInvokeComponent(program, "bump(3000000000)")).isEqualTo("3000000001");
		assertThat(compileAndInvokeComponent(program, "bump(4294967294)")).isEqualTo("4294967295");
	}

	@Test
	void componentExportCarriesTheSixtyFourBitTypes() throws Exception {
		// s64/u64 used to be a compile-time refusal on the GC backend (its integers
		// widened to a float past i31, exact only below 2^53). The boxed exact-integer
		// representation carries the full signed 64-bit range, so the whole family
		// crosses exactly now -- like --no-gc. A u64 at or above 2^63 still has no
		// exact representation, so the wrapper traps rather than answering a negative.
		String program = """
				(defun bump (n) (+ n 1))
				(defun scale (n) (* n 1000000))
				(rontolisp:wasm-export 'bump :params '(:s64) :returns :s64)
				(rontolisp:wasm-export 'scale :params '(:u64) :returns :u64)
				""";
		assertThat(compileAndInvokeComponent(program, "bump(9007199254740993)")).isEqualTo("9007199254740994");
		assertThat(compileAndInvokeComponent(program, "bump(-9007199254740995)")).isEqualTo("-9007199254740994");
		assertThat(compileAndInvokeComponent(program, "scale(9000000000000)")).isEqualTo("9000000000000000000");
		byte[] component = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(program));
		wasmtime.copyFileToContainer(Transferable.of(component), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "--invoke",
				"scale(9300000000000000000)", path("test.wasm"));
		assertThat(result.getExitCode()).isNotZero();
		assertThat(result.getStderr()).contains("wasm trap");
	}

	@Test
	void componentExportRefusesAValueTheDeclaredTypeCannotState() throws Exception {
		// The boundary carries the value exactly or it traps: a negative is not a u32,
		// and 300 is not a u8. Neither is silently masked -- masking is what the
		// canonical ABI would do and what makes a component behave differently under
		// jco (which throws) than under wasmtime.
		byte[] component = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString("""
				(defun down (n) (- n))
				(defun wide (n) (* n 100))
				(rontolisp:wasm-export 'down :params '(:s32) :returns :u32)
				(rontolisp:wasm-export 'wide :params '(:u8) :returns :u8)
				"""));
		wasmtime.copyFileToContainer(Transferable.of(component), path("test.wasm"));
		for (String invocation : new String[] { "down(5)", "wide(7)" }) {
			ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "--invoke", invocation,
					path("test.wasm"));
			assertThat(result.getExitCode()).as(invocation).isNotZero();
			assertThat(result.getStderr()).as(invocation).contains("wasm trap");
		}
	}

	@Test
	void componentExportCoexistsWithRun() throws Exception {
		// The wasi:cli/run export (the program's top level) still runs as a command.
		assertThat(compileAndRunComponent(COMPONENT_EXPORT_PROGRAM)).isEqualTo("25");
	}

	@Test
	void componentExportHonorsAsAlias() throws Exception {
		String program = """
				(defun sum-sq (a b) (* (+ a b) (+ a b)))
				(rontolisp:wasm-export 'sum-sq :as "sum-squared" :params '(:int :int) :returns :int)
				""";
		assertThat(compileAndInvokeComponent(program, "sum-squared(2, 3)")).isEqualTo("25");
	}

	private static final String COMPONENT_STRING_PROGRAM = """
			(defun count-a (s)
			  (let ((n 0))
			    (dotimes (i (length s))
			      (when (char= (char s i) #\\a)
			        (setq n (+ n 1))))
			    n))
			(defun shout (s) (concatenate 'string s "!!"))
			(defun greet (s) (concatenate 'string "Hello, " s))
			(defun hello () "hi there")
			(defun sink (s) nil)
			(rontolisp:wasm-export 'count-a :params '(:string) :returns :int)
			(rontolisp:wasm-export 'shout :params '(:string) :returns :string)
			(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
			(rontolisp:wasm-export 'hello :params '() :returns :string)
			(rontolisp:wasm-export 'sink :params '(:string) :returns :void)
			(print (greet "world"))
			""";

	@Test
	void componentExportStringLiftsThroughTheCanonicalStringAbi() throws Exception {
		// :string boundaries under --component on the GC backend: a
		// string argument is lowered by the host into the shared linear memory via the
		// core's appended cabi_realloc, the wrapper copies it onto the GC heap, a string
		// result crosses as a typed component-model string through the retptr shim, and
		// the cabi_post_* post-return pops the bump heap back to the per-call snapshot.
		// Both directions (:string->:int and :string->:string), no-arg and :void
		// shapes, plus UTF-8 multi-byte content -- the same UX as --no-gc --component,
		// just with the GC flags.
		assertThat(compileAndInvokeComponent(COMPONENT_STRING_PROGRAM, "count-a(\"banana\")")).isEqualTo("3");
		assertThat(compileAndInvokeComponent(COMPONENT_STRING_PROGRAM, "shout(\"hello\")")).isEqualTo("\"hello!!\"");
		assertThat(compileAndInvokeComponent(COMPONENT_STRING_PROGRAM, "greet(\"世界\")")).isEqualTo("\"Hello, 世界\"");
		assertThat(compileAndInvokeComponent(COMPONENT_STRING_PROGRAM, "hello()")).isEqualTo("\"hi there\"");
		assertThat(compileAndInvokeComponent(COMPONENT_STRING_PROGRAM, "sink(\"bye\")")).isEqualTo("()");
	}

	@Test
	void componentExportStringCoexistsWithRun() throws Exception {
		// The wasi:cli/run export (the program's top level) still runs as a command
		// alongside the string-ABI exports.
		assertThat(compileAndRunComponent(COMPONENT_STRING_PROGRAM)).isEqualTo("\"Hello, world\"");
	}

	@Test
	void componentExportSExprLiftsAsString() throws Exception {
		// :s-expr crosses the component boundary as its printed text (WIT string): the
		// wrapper parses a parameter with the embedded reader (exportNeedsReader) and
		// prints a result with prin1-to-string.
		String program = """
				(defun swap-pair (p) (list (car (cdr p)) (car p)))
				(defun sum-expr (e) (+ (car e) (car (cdr e))))
				(rontolisp:wasm-export 'swap-pair :as "swap" :params '(:s-expr) :returns :s-expr)
				(rontolisp:wasm-export 'sum-expr :params '(:s-expr) :returns :int)
				""";
		assertThat(compileAndInvokeComponent(program, "swap(\"(1 2)\")")).isEqualTo("\"(2 1)\"");
		assertThat(compileAndInvokeComponent(program, "sum-expr(\"(40 2)\")")).isEqualTo("42");
	}

	private static final String COMPONENT_ASYNC_PROGRAM = """
			(defun noisy-add (a b)
			  (print (+ a b))
			  (+ a b))
			(defun shout (s)
			  (print s)
			  (concatenate 'string s "!"))
			(defun pure-add (a b) (+ a b))
			(rontolisp:wasm-export 'noisy-add :params '(:int :int) :returns :int :async t)
			(rontolisp:wasm-export 'shout :params '(:string) :returns :string :async t)
			(rontolisp:wasm-export 'pure-add :params '(:int :int) :returns :int)
			(print "top")
			""";

	@Test
	void componentAsyncExportAllowsIoInside() throws Exception {
		// Tier 3: an :async t export lifts against an async function type (the stackful
		// run shape), so print inside it writes through the adapter's blocking stream
		// built-ins instead of trapping with "cannot block a synchronous task". The
		// invoke output is the print's line followed by the WAVE-rendered result.
		assertThat(compileAndInvokeComponent(COMPONENT_ASYNC_PROGRAM, "noisy-add(20, 22)")).isEqualTo("42\n42");
	}

	@Test
	void componentAsyncStringExportAllowsIoInside() throws Exception {
		// :async composes with the Tier 2 canonical string ABI unchanged (the async-typed
		// lift keeps the memory/realloc/utf8/post-return options): the argument crosses
		// in, the print inside runs, and the result crosses back through the retptr shim.
		assertThat(compileAndInvokeComponent(COMPONENT_ASYNC_PROGRAM, "shout(\"世界\")")).isEqualTo("\"世界\"\n\"世界!\"");
	}

	@Test
	void componentAsyncExportCoexistsWithSyncExportsAndRun() throws Exception {
		// Sync and async exports mix freely in one component, and wasi:cli/run still
		// runs the top level as a command.
		assertThat(compileAndInvokeComponent(COMPONENT_ASYNC_PROGRAM, "pure-add(1, 2)")).isEqualTo("3");
		assertThat(compileAndRunComponent(COMPONENT_ASYNC_PROGRAM)).isEqualTo("\"top\"");
	}

	@Test
	void componentSyncExportWithIoWorksWhenTheHostDoesNotBlock() throws Exception {
		// Without :async the lift stays synchronous. The adapter's I/O built-ins are
		// the async (non-blocking) variants now, and a host that accepts the bytes
		// immediately (stdout here) never parks the task -- so print inside a sync
		// export SUCCEEDS. Only when the host reports BLOCKED would the blocking park
		// trap a synchronous task ("cannot block a synchronous task"); :async t
		// removes that residual risk.
		List<LispVal> program = LispReader.readAllFromString(COMPONENT_ASYNC_PROGRAM.replace(":async t", ":async nil"));
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("test.component.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--invoke",
				"noisy-add(20, 22)", path("test.component.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout()).contains("42");
	}

	@Test
	void componentExportRejectsNonKebabName() {
		// A component-model export name must be a lower-kebab-case label; Lisp names
		// outside that grammar (here *of*) must be renamed with :as.
		assertThatThrownBy(() -> compileAndInvokeComponent("""
				(defun sum*of* (a b) (+ a b))
				(rontolisp:wasm-export 'sum*of* :params '(:int :int) :returns :int)
				""", "sum*of*(1, 2)")).hasMessageContaining("not a valid component-model export name");
	}

	private static String compileAndRunComponentWithEnv(String lispCode, String env) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("test.component.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--env",
				env, path("test.component.wasm"));
		assertThat(result.getExitCode()).as("exit code for component: %s\nstderr: %s", lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	@Test
	void componentGetenvFromWasiEnvironment() throws Exception {
		// Component mode reads environment variables through wasi:cli/environment.
		assertThat(compileAndRunComponentWithEnv("(print (getenv \"RLENV\"))", "RLENV=hello")).isEqualTo("\"hello\"");
		assertThat(compileAndRunComponentWithEnv("(print (stringp (getenv \"RLENV\")))", "RLENV=hello")).isEqualTo("T");
		assertThat(compileAndRunComponentWithEnv("(print (getenv \"RL_UNSET\"))", "RLENV=hello")).isEqualTo("NIL");
	}

	@Test
	void preview1GetenvDoesNotCorruptNewline() throws Exception {
		// getenv calls environ_sizes_get with scratch addresses (ENV_COUNT_ADDR=136 ..)
		// that must NOT overlap the interned-string data segment. When they did
		// (DATA_BASE_OFFSET=128), the host's count write at 136..139 clobbered the
		// shared newline byte at offset 137, so every newline after a getenv printed as
		// a NUL (0x00) instead of 0x0a. Assert the raw bytes keep real newlines.
		assertThat(compileAndRunRawWithEnv("(getenv \"RLENV\") (format t \"X~%Y~%\")", "RLENV=hello"))
			.isEqualTo("X\nY\n");
	}

	@Test
	void preview1TimeDoesNotCorruptNilLiteral() throws Exception {
		// The companion scratch-overlap guard: clock_time_get writes 8 bytes at
		// TIME_SCRATCH_ADDR=128, which overlapped the data segment's leading "nil"
		// literal when DATA_BASE_OFFSET=128. Reading the clock then printing nil must
		// still print nil.
		assertThat(compileAndRunRawWithEnv("(get-internal-real-time) (print nil)", "RLENV=hello")).isEqualTo("NIL\n");
	}

	@Test
	void componentTimeFromWasiClocks() throws Exception {
		// Component mode reads time from wasi:clocks. The value is an exact integer
		// (boxed past the i31 range), like the interpreter and JVM.
		assertThat(compileAndRunComponent("(print (> (get-universal-time) 3786825600))")).isEqualTo("T");
		assertThat(compileAndRunComponent("(print (integerp (get-internal-real-time)))")).isEqualTo("T");
		assertThat(compileAndRunComponent(
				"(let ((s (get-internal-run-time))) (print (>= (- (get-internal-run-time) s) 0)))"))
			.isEqualTo("T");
	}

	@Test
	void preview1TimeFromHostClock() throws Exception {
		// Preview 1 mode binds the real wasi_snapshot_preview1 clock_time_get.
		assertThat(compileAndRun("(print (> (get-universal-time) 3786825600))")).isEqualTo("T");
		assertThat(compileAndRun("(print (integerp (get-internal-real-time)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (integerp (get-universal-time)))")).isEqualTo("T");
	}

	@Test
	void componentRandomDrawsFromWasiRandom() throws Exception {
		// Component mode draws entropy from wasi:random (Preview 1 uses the host's
		// random_get); both are non-reproducible, so only the range and type are
		// asserted.
		assertThat(compileAndRunComponent(
				"(let ((r (random 100))) (if (and (>= r 0) (< r 100)) (print \"in\") (print \"oob\")))"))
			.isEqualTo("\"in\"");
		assertThat(compileAndRunComponent("(print (integerp (random 10)))")).isEqualTo("T");
		assertThat(compileAndRunComponent(
				"(let ((r (random 1.0))) (if (and (>= r 0.0) (< r 1.0)) (print \"in\") (print \"oob\")))"))
			.isEqualTo("\"in\"");
	}

	private static String compileAndRunComponentWithDir(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("test.component.wasm"));
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"cd " + workDir() + " && wasmtime run -W gc=y -W exceptions=y --dir . test.component.wasm");
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
		byte[] componentBytes = compileServeComponent("""
				(defun handle (request)
				  (list :status 200
				        :body (concatenate 'string (getf request :method) " " (getf request :path))))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"cd /tmp && wasmtime serve -W gc=y -W exceptions=y serve.wasm >/tmp/serve.log 2>&1 &"
						+ " for i in $(seq 1 60); do out=$(curl -s http://127.0.0.1:8080/hello) && [ -n \"$out\" ]"
						+ " && { echo \"$out\"; exit 0; }; sleep 0.25; done; cat /tmp/serve.log; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve round trip; log: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("GET /hello");
	}

	@Test
	void httpHandlerServesResponseLargerThanIoChunkUnderWasmtimeServe() throws Exception {
		// wasi:io's blocking-write-and-flush accepts at most 4096 bytes per call, so the
		// serve adapter must chunk the response body (adapter-http-server.wat, like
		// adapter-http-client.wat's request-body loop). 64 chars doubled 7 times = 8192
		// bytes.
		byte[] componentBytes = compileServeComponent("""
				(defun handle (request)
				  (let ((s "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))
				    (dotimes (i 7)
				      (setq s (concatenate 'string s s)))
				    (list :status 200 :body s)))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve-big.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"cd /tmp && wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8081 serve-big.wasm >/tmp/serve-big.log 2>&1 &"
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
		// (adapter-http-server-p1.wat) instead of trapping.
		byte[] componentBytes = compileServeComponent("""
				(defun handle (request)
				  (print "handling")
				  (let ((r (random 10)))
				    (list :status 200
				          :body (concatenate 'string
				                             (if (and (integerp r) (>= r 0) (< r 10)) "r-in" "r-out")
				                             " "
				                             (if (numberp (get-universal-time)) "t-num" "t-bad")))))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve-rand.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"cd /tmp && wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8082 serve-rand.wasm >/tmp/serve-rand.log 2>&1 &"
						+ " for i in $(seq 1 60); do out=$(curl -s http://127.0.0.1:8082/) && [ -n \"$out\" ]"
						+ " && { echo \"$out\"; exit 0; }; sleep 0.25; done; cat /tmp/serve-rand.log; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve random/clock round trip; log: %s", result.getStderr())
			.isZero();
		assertThat(result.getStdout().trim()).isEqualTo("r-in t-num");
	}

	@Test
	void httpHandlerFetchInsideServeUnderWasmtimeServe() throws Exception {
		// A proxy-style handler: rontolisp:fetch inside a served handler. Both halves are
		// Lisp over wit-imported wasi:http now -- serve.lisp (incoming-handler) and
		// fetch.lisp
		// (outgoing-handler) spliced into one component over the wider serve+fetch block,
		// no
		// hand-written adapter -- so the proxy compiles through the same CLI pipeline
		// (compileServeComponent runs FetchLibrary then ServeLibrary). Both are EH-mode,
		// and
		// fetch rides the host-provided wasi:http client the service world imports.
		// The
		// backend is itself a plain rontolisp serve component, so the test stays offline.
		byte[] backendBytes = compileServeComponent("""
				(defun handle (request)
				  (list :status 200
				        :body (concatenate 'string "backend " (getf request :path))))
				(rontolisp:http-handler 'handle)
				""", null);
		byte[] proxyBytes = compileServeComponent("""
				(rontolisp:async-defun handle (request)
				  (let* ((resp (rontolisp:await (rontolisp:fetch "http://127.0.0.1:8083/up")))
				         (body (rontolisp:await (rontolisp:read-all (getf resp :body)))))
				    (list :status 200
				          :body (concatenate 'string "proxied " body
				                             " " (princ-to-string (getf resp :status))))))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(backendBytes), "/tmp/serve-backend.wasm");
		wasmtime.copyFileToContainer(Transferable.of(proxyBytes), "/tmp/serve-proxy.wasm");
		// Wait for the BACKEND before querying the proxy: if the proxy answers first,
		// its fetch fails and it serves the non-empty body "proxied nil nil", which
		// would end the poll loop with the wrong output (a startup race, not a bug).
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8083 /tmp/serve-backend.wasm >/tmp/serve-backend.log 2>&1 &"
						+ " wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8084 /tmp/serve-proxy.wasm >/tmp/serve-proxy.log 2>&1 &"
						+ " for i in $(seq 1 60); do curl -sf http://127.0.0.1:8083/up >/dev/null && break; sleep 0.25; done;"
						+ " curl -sf http://127.0.0.1:8083/up >/dev/null"
						+ " || { echo 'backend never came up' 1>&2; cat /tmp/serve-backend.log 1>&2; exit 1; };"
						+ " for i in $(seq 1 60); do out=$(curl -s http://127.0.0.1:8084/) && [ -n \"$out\" ]"
						+ " && { echo \"$out\"; exit 0; }; sleep 0.25; done;"
						+ " cat /tmp/serve-backend.log /tmp/serve-proxy.log 1>&2; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve fetch-inside-serve round trip; log: %s", result.getStderr())
			.isZero();
		assertThat(result.getStdout().trim()).isEqualTo("proxied backend /up 200");
	}

	// The wasi:keyvalue store, cut down to what a page-hit counter binds. It is the real
	// upstream interface (wasmtime's own host answers it under -S keyvalue=y), so every
	// function returns a result and `get` an option<list<u8>> -- exactly the shapes only
	// the canonical ABI carries.
	private static final String WASI_KEYVALUE_WIT = """
			package wasi:keyvalue@0.2.0-draft;

			interface store {
			  variant error {
			    no-such-store,
			    access-denied,
			    other(string)
			  }

			  open: func(identifier: string) -> result<bucket, error>;

			  resource bucket {
			    get: func(key: string) -> result<option<list<u8>>, error>;
			    set: func(key: string, value: list<u8>) -> result<_, error>;
			  }
			}
			""";

	@Test
	void httpHandlerCallsAUserWitImportUnderWasmtimeServe() throws Exception {
		// A served handler whose state lives in a real key-value store: the component
		// exports wasi:http/incoming-handler AND imports wasi:keyvalue/store, and
		// wasmtime answers both. The counter is seeded through the host's own CLI
		// (-S keyvalue-in-memory-data), so the reply proves the handler READ the host's
		// store across the canonical ABI and wrote back a value it read again -- which no
		// process-local hash table could fake.
		java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("wit-serve");
		java.nio.file.Files.writeString(dir.resolve("kv.wit"), WASI_KEYVALUE_WIT);
		byte[] component = compileServeComponent("""
				(rontolisp:wit-import "kv.wit" :interface "wasi:keyvalue/store@0.2.0-draft" :package kv)
				(defun handle (request)
				  (let* ((page (getf request :path))
				         (bucket (kv:open ""))
				         (seen (kv:bucket-get bucket page)))
				    (kv:bucket-set bucket page
				                   (princ-to-string (+ 1 (if seen (parse-integer seen) 0))))
				    (list :status 200
				          :body (concatenate 'string page " " (kv:bucket-get bucket page)))))
				(rontolisp:http-handler 'handle)
				""", dir.toString());
		wasmtime.copyFileToContainer(Transferable.of(component), "/tmp/serve-kv.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y -S keyvalue=y -S keyvalue-in-memory-data=/hits=41"
						+ " --addr 127.0.0.1:8085 /tmp/serve-kv.wasm >/tmp/serve-kv.log 2>&1 &"
						+ " for i in $(seq 1 60); do out=$(curl -s http://127.0.0.1:8085/hits) && [ -n \"$out\" ]"
						+ " && { echo \"$out\"; exit 0; }; sleep 0.25; done; cat /tmp/serve-kv.log; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve keyvalue round trip; log: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("/hits 42");
	}

	@Test
	void httpHandlerReadsATopLevelGlobalUnderWasmtimeServe() throws Exception {
		// A serve component never lifts `run`, so the handle wrapper itself must run
		// the program's top level once before the first request: without that init a
		// defvar global reads back null inside the handler and the first arithmetic
		// on it traps with "cast failure".
		byte[] componentBytes = compileServeComponent("""
				(defvar *base* 41)
				(defun handle (request)
				  (list :status 200 :body (princ-to-string (+ *base* 1))))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve-global.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8090 /tmp/serve-global.wasm >/tmp/serve-global.log 2>&1 &"
						+ " for i in $(seq 1 60); do out=$(curl -sf http://127.0.0.1:8090/) && [ -n \"$out\" ]"
						+ " && { echo \"$out\"; exit 0; }; sleep 0.25; done; cat /tmp/serve-global.log 1>&2; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve top-level global; log: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("42");
	}

	@Test
	void httpHandlerConnectsTcpUnderWasmtimeServe() throws Exception {
		// serve + tcp actually RUNS: a served handler opens a plain TCP connection
		// through the sync surface (%future-force under the callback driver) -- to the
		// server's own listening socket, so no second process is needed -- and answers
		// from the connected handle. The tcp state lives in sockets.lisp defvars, so
		// this also covers the top-level init the handle wrapper performs. A
		// compile-only assertion cannot catch a runtime-composition failure here;
		// this test exists because serve+tcp once compiled fine and trapped on every
		// request. Needs -S cli=y: without it wasmtime serve's linker reports the
		// tcp-socket resource as missing at instantiation.
		byte[] componentBytes = compileServeComponent("""
				(defun handle (request)
				  (let ((sock (rontolisp:tcp-connect "127.0.0.1" 8091)))
				    (if sock
				        (progn
				          (close sock)
				          (list :status 200 :body "connected"))
				        (list :status 200 :body "no-listener"))))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve-tcp.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y"
						+ " --addr 127.0.0.1:8091 /tmp/serve-tcp.wasm >/tmp/serve-tcp.log 2>&1 &"
						+ " for i in $(seq 1 60); do out=$(curl -sf http://127.0.0.1:8091/) && [ -n \"$out\" ]"
						+ " && { echo \"$out\"; exit 0; }; sleep 0.25; done; cat /tmp/serve-tcp.log 1>&2; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve tcp-connect; log: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("connected");
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
		assertThat(compileAndRunComponentWithDir(code)).isEqualTo("\"hello\"\n\"world\"\nNIL");
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
		assertThat(compileAndRunComponentWithDir(code)).isEqualTo("0\n10\n34\n255\nNIL");
	}

	@Test
	void componentLoadFromFile() throws Exception {
		// load reads and evaluates a file; the defined function is resolved via the eval
		// runtime, so the program is compiled in --dynamic mode.
		List<LispVal> program = LispReader.readAllFromString("(load \"clib.lisp\")\n(print (sq 9))");
		byte[] componentBytes = new WasmLispCompiler(true, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("test.component.wasm"));
		wasmtime.copyFileToContainer(
				Transferable.of("(defun SQ (x) (* x x))".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				path("clib.lisp"));
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"cd " + workDir() + " && wasmtime run -W gc=y -W exceptions=y --dir . test.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("81");
	}

	private static String compileAndRunComponentWithStdin(String lispCode, String stdin) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("test.component.wasm"));
		wasmtime.copyFileToContainer(Transferable.of(stdin.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				path("stdin.txt"));
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime run -W gc=y " + path("test.component.wasm") + " < " + path("stdin.txt"));
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
		assertThat(compileAndRun("(print (defvar *x* 42))")).isEqualTo("*X*");
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
		assertThat(compileAndRun("(print (< 1 2 3 4))")).isEqualTo("T");
		assertThat(compileAndRun("(print (< 1 2 2 4))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (<= 1 2 2 4))")).isEqualTo("T");
		assertThat(compileAndRun("(print (= 3 3 3))")).isEqualTo("T");
		assertThat(compileAndRun("(print (> 5 4 3 2 1))")).isEqualTo("T");
		assertThat(compileAndRun("(print (< 5))")).isEqualTo("T");
	}

	@Test
	void booleanIsSymbolT() throws Exception {
		// A boolean true prints as the symbol t (not the integer 1), matching the
		// interpreter, so it is indistinguishable from t in a list.
		assertThat(compileAndRun("(print (list (= 1 1) (= 1 0)))")).isEqualTo("(T NIL)");
		assertThat(compileAndRun("(print t)")).isEqualTo("T");
		assertThat(compileAndRun("(print (eq t (= 1 1)))")).isEqualTo("T");
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
		assertThat(compileAndRun("(print (eql 1/2 1/2))")).isEqualTo("T");
		assertThat(compileAndRun("(print (eq 1/2 1/2))")).isEqualTo("NIL");
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
		assertThat(compileAndRun("(print (numberp 1/2))")).isEqualTo("T");
		assertThat(compileAndRun("(print (integerp 1/2))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (rationalp 1/2))")).isEqualTo("T");
		assertThat(compileAndRun("(print (rationalp 5))")).isEqualTo("T");
		assertThat(compileAndRun("(print (rationalp 0.5))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (numerator 3/4))")).isEqualTo("3");
		assertThat(compileAndRun("(print (denominator 3/4))")).isEqualTo("4");
		assertThat(compileAndRun("(print (numerator 5))")).isEqualTo("5");
		assertThat(compileAndRun("(print (denominator 5))")).isEqualTo("1");
		assertThat(compileAndRun("(print (consp 1/2))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (atom 1/2))")).isEqualTo("T");
		assertThat(compileAndRun("(print (zerop 1/2))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (plusp 1/2))")).isEqualTo("T");
		assertThat(compileAndRun("(print (minusp -1/2))")).isEqualTo("T");
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
		assertThat(compileAndRun("(print (dotimes (i 3)))")).isEqualTo("NIL");
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
	void letShadowingBoxedOuterVariable() throws Exception {
		// An inner let binding a RAW value under a name whose outer
		// binding was boxed (captured by the init lambda) must not be cell-read.
		assertThat(compileAndRun("""
				(let ((g (lambda () 1)))
				  (let ((g (lambda () (+ 10 (funcall g)))))
				    (print (funcall g))))
				""")).isEqualTo("11");
	}

	@Test
	void lambdaCapturesVariableShadowingFunctionName() throws Exception {
		// A lexical variable named like a built-in function (count/list) is a plain
		// capturable variable (Lisp-2).
		assertThat(compileAndRun("""
				(defun grab (list count)
				  (let ((list (nthcdr count list)))
				    (let ((g (lambda () (car list))))
				      (funcall g))))
				(print (grab '(1 2 3) 1))
				""")).isEqualTo("2");
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

	// print / prin1 / princ return their argument (CL semantics), so the value is usable
	// in a surrounding form -- not nil.
	@Test
	void printReturnsArgument() throws Exception {
		assertThat(compileAndRun("(print (print 11))")).isEqualTo("11\n11");
	}

	@Test
	void prin1ReturnsArgument() throws Exception {
		assertThat(compileAndRun("(prin1 (prin1 11))")).isEqualTo("1111");
	}

	@Test
	void princReturnsArgument() throws Exception {
		assertThat(compileAndRun("(princ (princ 11))")).isEqualTo("1111");
	}

	@Test
	void printReturnValueThroughLet() throws Exception {
		assertThat(compileAndRun("(let ((x (print 11))) (let ((a x)) (print a)))")).isEqualTo("11\n11");
	}

	@Test
	void printToStringStreamReturnsArgument() throws Exception {
		assertThat(compileAndRun("(let ((v nil)) (with-output-to-string (s) (setq v (print 11 s))) (print v))"))
			.isEqualTo("11");
	}

	@Test
	void format() throws Exception {
		assertThat(compileAndRun("(format t \"Hello ~a, you are ~d! ~s~%\" 'world 42 \"str\")"))
			.isEqualTo("Hello WORLD, you are 42! \"str\"");
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
			.isEqualTo("Hi, ALICE!\nHi, bob!");
	}

	@Test
	void formatNil() throws Exception {
		assertThat(compileAndRun("(princ (format nil \"Hello ~a, ~d! ~s~%\" 'world 42 \"str\"))"))
			.isEqualTo("Hello WORLD, 42! \"str\"");
	}

	@Test
	void formatNilList() throws Exception {
		assertThat(compileAndRun("(princ (format nil \"list=~a tilde=~~\" (list 1 2 3)))"))
			.isEqualTo("list=(1 2 3) tilde=~");
	}

	@Test
	void formatNilIsString() throws Exception {
		assertThat(compileAndRun("(print (stringp (format nil \"~a\" 1))) (print (length (format nil \"~a\" 12345)))"))
			.isEqualTo("T\n5");
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
			.isEqualTo("<1><2>| A B|(X,1)(Y,2)");
		assertThat(compileAndRun("(format t \"x~2@{ ~a~}|~:@{(~a)~}\" 1 2 '(3) '(4))")).isEqualTo("x 1 2|(3)(4)");
	}

	@Test
	void formatArgumentJump() throws Exception {
		assertThat(compileAndRun("(format t \"~a ~2* ~a ~2:* ~a\" 1 2 3 4)")).isEqualTo("1  4  3");
	}

	@Test
	void formatIterationEscape() throws Exception {
		assertThat(compileAndRun("(format t \"~{~a~^, ~}|~@{~a~^, ~}\" '(1 2 3) 4 5)")).isEqualTo("1, 2, 3|4, 5");
		assertThat(compileAndRun("(format t \"~:['{}'~;ARRAY[~:*~{~A~^, ~}]~]\" '(1 2 3))"))
			.isEqualTo("ARRAY[1, 2, 3]");
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
			.isEqualTo("\"42\"\nSYM");
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
		assertThat(compileAndRun("(print (subseq '(a b c) 0))")).isEqualTo("(A B C)");
		assertThat(compileAndRun("(print (subseq '(1 2 3) 3))")).isEqualTo("NIL");
	}

	@Test
	void makeString() throws Exception {
		assertThat(compileAndRun("(princ (make-string 3 :initial-element #\\x))")).isEqualTo("xxx");
		assertThat(compileAndRun("(princ (length (make-string 5)))")).isEqualTo("5");
		// (length ...) avoids the compileAndRun trim() eating the funcall's trailing
		// spaces.
		assertThat(compileAndRun("(princ (length (funcall #'make-string 2)))")).isEqualTo("2");
	}

	@Test
	void replace() throws Exception {
		assertThat(compileAndRun("(princ (replace (make-string 5 :initial-element #\\a) \"XY\" :start1 1))"))
			.isEqualTo("aXYaa");
		assertThat(compileAndRun("(princ (replace \"aaaaa\" \"XY\"))")).isEqualTo("XYaaa");
		assertThat(compileAndRun("(princ (funcall #'replace \"aaaaa\" \"XY\"))")).isEqualTo("XYaaa");
	}

	@Test
	void writeSequenceString() throws Exception {
		assertThat(compileAndRun("(princ (with-output-to-string (s) (write-sequence \"abcd\" s :start 1 :end 3)))"))
			.isEqualTo("bc");
	}

	@Test
	void casePredicatesAndConstantp() throws Exception {
		assertThat(compileAndRun("(print (list (lower-case-p #\\a) (upper-case-p #\\A)))")).isEqualTo("(T T)");
		assertThat(compileAndRun("(print (list (lower-case-p #\\A) (upper-case-p #\\a)))")).isEqualTo("(NIL NIL)");
		assertThat(compileAndRun("(print (list (constantp 5) (constantp 'x) (constantp '(quote y))))"))
			.isEqualTo("(T NIL T)");
		assertThat(compileAndRun("(print (mapcar #'upper-case-p '(#\\A #\\b)))")).isEqualTo("(T NIL)");
	}

	@Test
	void streamp() throws Exception {
		assertThat(compileAndRun("(princ (with-output-to-string (s) (princ (streamp s) s)))")).isEqualTo("T");
		assertThat(compileAndRun("(princ (with-output-to-string (s) (check-type s stream) (write-string \"ok\" s)))"))
			.isEqualTo("ok");
	}

	@Test
	void stringEquality() throws Exception {
		assertThat(compileAndRun("(print (string= \"abc\" \"abc\"))")).isEqualTo("T");
		assertThat(compileAndRun("(print (string= \"abc\" \"abd\"))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (string-equal \"ABC\" \"abc\"))")).isEqualTo("T");
		// The bounding-index keywords lower onto subseq; the two-string intrinsic is
		// unchanged.
		assertThat(compileAndRun("(print (string= \"together\" \"frog\" :start1 1 :end1 3 :start2 2))")).isEqualTo("T");
		assertThat(compileAndRun("(print (string= \"abc\" \"xabc\" :start2 1))")).isEqualTo("T");
		assertThat(compileAndRun("(print (string-equal \"TOGETHER\" \"frog\" :start1 1 :end1 3 :start2 2))"))
			.isEqualTo("T");
		// ... and through the first-class value, whose wrapper re-reads the keywords
		// from its &rest list (an ordinary two-argument :test #'string= stays direct).
		assertThat(compileAndRun("(print (funcall #'string= \"xabc\" \"abc\" :start1 1)) "
				+ "(print (apply #'string-equal \"XABC\" \"abc\" '(:start1 1))) "
				+ "(print (find \"b\" '(\"a\" \"b\" \"c\") :test #'string=))"))
			.isEqualTo("T\nT\n\"b\"");
	}

	@Test
	void stringOrderingPredicates() throws Exception {
		// The prelude splice mirrors the CLI pipeline (the whole comparison family are
		// prelude defuns over %string-compare, pulled in transitively).
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(print (list (string< "aaaa" "aaab") (string< "abc" "abc")))
				(print (list (string> "abcd" "abc") (string> "abc" "abd")))
				(print (list (string<= "abc" "abc") (string<= "abd" "abc")))
				(print (list (string>= "aaaaa" "aaaa") (string>= "abc" "abd")))
				(print (list (string/= "abc" "abd") (string/= "abc" "abc")))
				(print (list (string-lessp "ABC" "abd") (string-greaterp "ABD" "abc")))
				(print (list (string-not-greaterp "Abcde" "abcdE") (string-not-lessp "Abcde" "abcdE")))
				(print (list (string-not-equal "AAAA" "aaaA") (string-not-equal "AAAB" "aaaa")))
				(print (string-lessp "012AAAA789" "01aaab6" :start1 3 :end1 7 :start2 2 :end2 6))
				(print (sort (list "pear" "Apple" "banana") #'string-lessp))
				""")))).isEqualTo("""
				(3 NIL)
				(3 NIL)
				(3 NIL)
				(4 NIL)
				(2 NIL)
				(2 2)
				(5 5)
				(NIL 3)
				6
				("Apple" "banana" "pear")""");
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
		assertThat(compileAndRun("(print (quote nil))")).isEqualTo("NIL");
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
		assertThat(compileAndRun("(print (car nil))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (cdr nil))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (car '()))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (cdr '()))")).isEqualTo("NIL");
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
	void variadicBuiltinWrappers() throws Exception {
		// Regression: funcall/apply of a variadic builtin wrapper with an
		// arity other than the old fixed one trapped (unreachable) on WASM.
		assertThat(compileAndRun("(print (funcall #'+ 1 2 3))")).isEqualTo("6");
		assertThat(compileAndRun("(print (funcall #'+))")).isEqualTo("0");
		assertThat(compileAndRun("(print (apply #'+ (list 1 2 3 4)))")).isEqualTo("10");
		assertThat(compileAndRun("(print (funcall #'* 2 3 4))")).isEqualTo("24");
		assertThat(compileAndRun("(print (funcall #'- 10 1 2))")).isEqualTo("7");
		assertThat(compileAndRun("(print (funcall #'- 5))")).isEqualTo("-5");
		assertThat(compileAndRun("(print (funcall #'list 1 2 3))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (funcall #'max 3 7 2))")).isEqualTo("7");
		assertThat(compileAndRun("(print (funcall #'min 3 7 2))")).isEqualTo("2");
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
		assertThat(compileAndRun("(print (unless t 42))")).isEqualTo("NIL");
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
		assertThat(compileAndRun("(print (funcall #'eql 5 5))")).isEqualTo("T");
	}

	@Test
	void eqlFloatsByValue() throws Exception {
		assertThat(compileAndRun("(print (eql 1.5 1.5))")).isEqualTo("T");
	}

	@Test
	void eqFloatsNotEq() throws Exception {
		assertThat(compileAndRun("(print (eq 1.5 1.5))")).isEqualTo("NIL");
	}

	@Test
	void eqIntegersStillEq() throws Exception {
		assertThat(compileAndRun("(print (eq 3 3))")).isEqualTo("T");
	}

	@Test
	void eqOnCharactersComparesByCodePoint() throws Exception {
		// CL permits eq to return T when char= would. TYPE_CHAR is a struct so ref.eq
		// on two separately allocated char structs returns 0; emitEqComparison's added
		// TYPE_CHAR branch compares the code-point field, matching the interpreter's
		// value-based LispChar.equals and the JVM _eqv int[] fast path.
		assertThat(compileAndRun("(print (eq #\\A #\\A))")).isEqualTo("T");
		assertThat(compileAndRun("(print (eq (code-char 65) #\\A))")).isEqualTo("T");
		assertThat(compileAndRun("(print (eq (code-char 128512) (code-char 128512)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (eq (code-char 128512) #\\A))")).isEqualTo("NIL");
		// Guard against constant folding: build the two char structs from a runtime
		// integer so the compile-time constant path can't collapse them into one.
		assertThat(compileAndRun("(let ((cp 128513)) (print (eq (code-char cp) (code-char cp))))")).isEqualTo("T");
	}

	@Test
	void equalNestedLists() throws Exception {
		assertThat(compileAndRun("(print (equal '(1 2 (3)) '(1 2 (3))))")).isEqualTo("T");
	}

	@Test
	void equalDifferentLists() throws Exception {
		assertThat(compileAndRun("(print (equal '(1 2) '(1 3)))")).isEqualTo("NIL");
	}

	@Test
	void equalStrings() throws Exception {
		assertThat(compileAndRun("(print (equal \"abc\" \"abc\"))")).isEqualTo("T");
	}

	@Test
	void equalDifferentTypeNumbers() throws Exception {
		assertThat(compileAndRun("(print (equal 3 3.0))")).isEqualTo("NIL");
	}

	@Test
	void equalFreshConsesUnlikeEql() throws Exception {
		assertThat(compileAndRun("(print (eql (list 1 2) (list 1 2)))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (equal (list 1 2) (list 1 2)))")).isEqualTo("T");
	}

	@Test
	void equalAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (funcall #'equal '(1) '(1)))")).isEqualTo("T");
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
				""")).isEqualTo("(B 2 C 3)");
	}

	@Test
	void remfMiddle() throws Exception {
		assertThat(compileAndRun("""
				(setq plist (list 'a 1 'b 2 'c 3))
				(remf plist 'b)
				(print plist)
				""")).isEqualTo("(A 1 C 3)");
	}

	@Test
	void remfTail() throws Exception {
		assertThat(compileAndRun("""
				(setq plist (list 'a 1 'b 2 'c 3))
				(remf plist 'c)
				(print plist)
				""")).isEqualTo("(A 1 B 2)");
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
		assertThat(compileAndRun("(print :foo)")).isEqualTo(":FOO");
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
	void reduceFromEnd() throws Exception {
		assertThat(compileAndRun("(print (reduce #'- '(1 2 3 4) :from-end t))")).isEqualTo("-2");
	}

	@Test
	void reduceFromEndWithInitialValue() throws Exception {
		assertThat(compileAndRun("(print (reduce #'cons '(1 2 3) :from-end t :initial-value nil))"))
			.isEqualTo("(1 2 3)");
	}

	@Test
	void reduceKey() throws Exception {
		assertThat(compileAndRun("(print (reduce #'+ '((1) (2) (3)) :key #'car))")).isEqualTo("6");
	}

	@Test
	void reduceKeyAndFromEnd() throws Exception {
		assertThat(compileAndRun("(print (reduce #'cons '((1) (2) (3)) :initial-value nil :from-end t :key #'car))"))
			.isEqualTo("(1 2 3)");
	}

	@Test
	void restAccessor() throws Exception {
		assertThat(compileAndRun("(print (rest '(1 2 3))) (print (rest '(1)))")).isEqualTo("(2 3)\nNIL");
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
		assertThat(compileAndRun("(print (case 2 (1 'one) (2 'two) (3 'three)))")).isEqualTo("TWO");
	}

	@Test
	void caseKeyList() throws Exception {
		assertThat(compileAndRun("(print (case 3 (1 'one) ((2 3 4) 'small) (otherwise 'big)))")).isEqualTo("SMALL");
	}

	@Test
	void caseOtherwise() throws Exception {
		assertThat(compileAndRun("(print (case 99 (1 'one) ((2 3 4) 'small) (otherwise 'big)))")).isEqualTo("BIG");
	}

	@Test
	void caseNoMatchReturnsNil() throws Exception {
		assertThat(compileAndRun("(print (case 5 (1 'a) (2 'b)))")).isEqualTo("NIL");
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
		assertThat(compileAndRun("(print (dolist (m '(2 3 5) t) (if (= m 3) (return))))")).isEqualTo("NIL");
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
		assertThat(compileAndRun("(print (reverse '(1 2 3))) (print (reverse nil))")).isEqualTo("(3 2 1)\nNIL");
	}

	@Test
	void memberFunction() throws Exception {
		assertThat(compileAndRun("(print (member 3 '(1 2 3 4))) (print (member 9 '(1 2 3)))")).isEqualTo("(3 4)\nNIL");
	}

	@Test
	void memberWithTestKeyword() throws Exception {
		assertThat(compileAndRun("(print (member '(a d) '((a b) (a c) (a d) (a e)) :test 'equal)) "
				+ "(print (member '(a d) '((a b) (a c) (a d) (a e))))"))
			.isEqualTo("((A D) (A E))\nNIL");
	}

	@Test
	void findFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (find 3 '(1 2 3 4))) (print (find 9 '(1 2 3))) (print (funcall #'find 2 '(1 2 3)))"))
			.isEqualTo("3\nNIL\n2");
	}

	@Test
	void findIfFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (find-if #'evenp '(1 3 5 6 7))) (print (find-if #'oddp '(2 4 6))) (print (funcall #'find-if #'plusp '(-1 -2 3 4)))"))
			.isEqualTo("6\nNIL\n3");
	}

	@Test
	void findIfNotFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (find-if-not #'evenp '(2 4 5 6))) (print (find-if-not #'plusp '(1 2 3))) (print (funcall #'find-if-not #'oddp '(1 3 4)))"))
			.isEqualTo("5\nNIL\n4");
	}

	@Test
	void positionFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (position 3 '(1 2 3 4))) (print (position 9 '(1 2 3))) (print (funcall #'position 2 '(5 2 8)))"))
			.isEqualTo("2\nNIL\n1");
	}

	@Test
	void positionOnString() throws Exception {
		assertThat(compileAndRun(
				"(print (position #\\space \"hello world\")) (print (position #\\z \"abc\")) (print (funcall #'position #\\l \"hello\"))"))
			.isEqualTo("5\nNIL\n2");
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
		expected.append("FRESH-SYM-0\n");
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
			.isEqualTo("#\\l\n#\\3\n#\\a\n2\n3\n2\nNIL\n1\nNIL\nT\n3");
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
			.isEqualTo("3\nNIL\n2");
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
			.isEqualTo("(B 2)\nNIL");
	}

	@Test
	void assocOnDottedAlistLiteral() throws Exception {
		assertThat(compileAndRun(
				"(print (assoc 'b '((a . 1) (b . 2) (c . 3)))) (print (cdr (assoc 'b '((a . 1) (b . 2)))))"))
			.isEqualTo("(B . 2)\n2");
	}

	@Test
	void assocWithTest() throws Exception {
		assertThat(compileAndRun("(print (assoc \"b\" '((\"a\" . 1) (\"b\" . 2)) :test #'equal)) "
				+ "(print (assoc \"z\" '((\"a\" . 1)) :test 'equal)) (print (funcall #'assoc 'b '((a . 1) (b . 2))))"))
			.isEqualTo("(\"b\" . 2)\nNIL\n(B . 2)");
	}

	@Test
	void rassocWithTest() throws Exception {
		assertThat(compileAndRun("(print (rassoc \"x\" '((a . \"w\") (b . \"x\")) :test #'equal)) "
				+ "(print (rassoc \"z\" '((a . \"w\")) :test 'equal)) (print (funcall #'rassoc 2 '((a . 1) (b . 2))))"))
			.isEqualTo("(B . \"x\")\nNIL\n(B . 2)");
	}

	@Test
	void assocWithKey() throws Exception {
		assertThat(compileAndRun("(print (assoc 2 '((1 . a) (2 . b) (3 . c)) :key (lambda (k) (+ k 1)))) "
				+ "(print (member 3 '((1 2) (3 4) (5 6)) :key #'car)) "
				+ "(print (rassoc 2 '((a . 1) (b . 3)) :key (lambda (v) (- v 1))))"))
			.isEqualTo("(1 . A)\n((3 4) (5 6))\n(B . 3)");
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
			.isEqualTo("(3 4)\n3\n2\n((2 B))\n((2 B))\n((2 B) (1 C))\n((1) X (3))\n((1) X)\n((2) (1))\n"
					+ "((2))\n((1))\n((1 A) (2 B))");
	}

	@Test
	void aconsAsFunctionValue() throws Exception {
		assertThat(compileAndRun("(print (funcall #'acons 'a 1 '((b . 2))))")).isEqualTo("((A . 1) (B . 2))");
	}

	@Test
	void quotedCharacterList() throws Exception {
		assertThat(compileAndRun("(print '(#\\a #\\b)) (print (char= (car '(#\\a #\\b)) #\\a)) (print '(#\\a . #\\b))"))
			.isEqualTo("(#\\a #\\b)\nT\n(#\\a . #\\b)");
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
			.isEqualTo("((A . 1) (B . 2) (C . 3))\n((A . 1) (B . 2) (C . 3))\nNIL\n((A . 1))\n((A . 1))");
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
				(print (funcall #'copy-alist '((a . 1))))""")).isEqualTo("((A . 1) (B . 2))\nNIL\n1\n((A . 1))");
	}

	@Test
	void lastFunction() throws Exception {
		assertThat(compileAndRun("(print (last '(1 2 3))) (print (last nil))")).isEqualTo("(3)\nNIL");
	}

	@Test
	void memberIfFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (member-if #'oddp '(2 4 5 6))) (print (member-if #'evenp '(1 3 5))) (print (funcall #'member-if #'plusp '(-1 3 4)))"))
			.isEqualTo("(5 6)\nNIL\n(3 4)");
	}

	@Test
	void assocIfFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (assoc-if #'oddp '((2 a) (3 b) (5 c)))) (print (assoc-if #'evenp '((1 a) (3 b)))) (print (funcall #'assoc-if #'plusp '((-1 a) (2 b))))"))
			.isEqualTo("(3 B)\nNIL\n(2 B)");
	}

	@Test
	void getfFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (getf '(:a 1 :b 2) :b)) (print (getf '(:a 1) :x)) (print (funcall #'getf '(:x 10 :y 20) :y))"))
			.isEqualTo("2\nNIL\n20");
	}

	@Test
	void removeDuplicatesFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (remove-duplicates '(1 2 1 3))) (print (remove-duplicates '(1 2 3))) (print (funcall #'remove-duplicates '(a b a a c)))"))
			.isEqualTo("(2 1 3)\n(1 2 3)\n(B A C)");
	}

	@Test
	void butlastFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (butlast '(1 2 3))) (print (butlast '(1))) (print (butlast nil)) (print (funcall #'butlast '(a b c d)))"))
			.isEqualTo("(1 2)\nNIL\nNIL\n(A B C)");
	}

	@Test
	void nconcFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (nconc (list 1 2) (list 3 4))) (print (nconc nil (list 1 2))) (print (funcall #'nconc (list 'a) (list 'b 'c)))"))
			.isEqualTo("(1 2 3 4)\n(1 2)\n(A B C)");
	}

	@Test
	void identityFunction() throws Exception {
		assertThat(compileAndRun("(print (identity 42)) (print (identity '(1 2 3))) (print (funcall #'identity 'x))"))
			.isEqualTo("42\n(1 2 3)\nX");
	}

	@Test
	void copyListFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (copy-list '(1 2 3))) (print (copy-list nil)) (print (funcall #'copy-list '(a b)))"))
			.isEqualTo("(1 2 3)\nNIL\n(A B)");
	}

	@Test
	void nreverseFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (nreverse '(1 2 3))) (print (nreverse nil)) (print (funcall #'nreverse '(a b c)))"))
			.isEqualTo("(3 2 1)\nNIL\n(C B A)");
	}

	@Test
	void makeListFunction() throws Exception {
		assertThat(compileAndRun("(print (make-list 3)) (print (make-list 0)) (print (funcall #'make-list 2))"))
			.isEqualTo("(NIL NIL NIL)\nNIL\n(NIL NIL)");
	}

	@Test
	void unionFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (union '(1 2 3) '(2 3 4))) (print (union nil '(1 2))) (print (funcall #'union '(a) '(a b)))"))
			.isEqualTo("(4 1 2 3)\n(2 1)\n(B A)");
	}

	@Test
	void intersectionFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (intersection '(1 2 3) '(2 3 4))) (print (intersection '(1 2) '(3 4))) (print (funcall #'intersection '(a b c) '(b c d)))"))
			.isEqualTo("(3 2)\nNIL\n(C B)");
	}

	@Test
	void setDifferenceFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (set-difference '(1 2 3) '(2))) (print (set-difference '(1 2 3) '(1 2 3))) (print (funcall #'set-difference '(a b c) '(b)))"))
			.isEqualTo("(3 1)\nNIL\n(C A)");
	}

	@Test
	void adjoinFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (adjoin 1 '(2 3))) (print (adjoin 2 '(1 2 3))) (print (adjoin 'a nil)) (print (funcall #'adjoin 5 '(5 6)))"))
			.isEqualTo("(1 2 3)\n(1 2 3)\n(A)\n(5 6)");
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
	void integerLengthAndLogbitp() throws Exception {
		assertThat(compileAndRun(
				"(print (integer-length 0)) (print (integer-length 5)) (print (integer-length 255)) (print (integer-length -1)) (print (integer-length -5))"))
			.isEqualTo("0\n3\n8\n0\n3");
		assertThat(compileAndRun(
				"(print (logbitp 0 5)) (print (logbitp 1 5)) (print (logbitp 2 5)) (print (logbitp 3 -1)) (print (funcall #'integer-length 8)) (print (funcall #'logbitp 1 2))"))
			.isEqualTo("T\nNIL\nT\nT\n4\nT");
	}

	@Test
	void byteFieldOps() throws Exception {
		assertThat(compileAndRun(
				"(print (byte-size (byte 8 3))) (print (byte-position (byte 8 3))) (print (ldb (byte 8 0) 255)) (print (ldb (byte 4 4) 255)) (print (ldb (byte 8 8) 65535))"))
			.isEqualTo("8\n3\n255\n15\n255");
		assertThat(compileAndRun(
				"(print (dpb 0 (byte 4 0) 255)) (print (dpb 5 (byte 4 4) 0)) (print (funcall #'ldb (byte 4 4) 255)) (print (funcall #'dpb 0 (byte 4 0) 255)) (print (funcall #'byte-size (byte 6 2)))"))
			.isEqualTo("240\n80\n15\n240\n6");
	}

	@Test
	void listStarAndAcons() throws Exception {
		assertThat(compileAndRun(
				"(print (list* 1 2 '(3 4))) (print (list* 1 2 3)) (print (list* 'x)) (print (acons 'a 1 nil))"))
			.isEqualTo("(1 2 3 4)\n(1 2 . 3)\nX\n((A . 1))");
	}

	@Test
	void eltEndpRassoc() throws Exception {
		assertThat(compileAndRun(
				"(print (elt '(a b c) 1)) (print (endp nil)) (print (endp '(1))) (print (rassoc 2 (list (cons 'a 1) (cons 'b 2))))"))
			.isEqualTo("B\nT\nNIL\n(B . 2)");
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
			.isEqualTo("T\nNIL\nT\nNIL");
	}

	@Test
	void prog2Psetq() throws Exception {
		assertThat(compileAndRun("(print (prog2 1 2 3)) (print (let ((a 1) (b 2)) (psetq a b b a) (list a b)))"))
			.isEqualTo("2\n(2 1)");
	}

	@Test
	void namedBlockReturnFrom() throws Exception {
		// Lexical named blocks: the return-from crosses the dotimes loop's %block
		// (which does not catch the named exit) straight to the named block, so the
		// after-loop code never runs -- matching the interpreter.
		assertThat(compileAndRun("""
				(print (block scan
				         (dotimes (i 10)
				           (when (= i 4) (return-from scan (* i 100))))
				         :fell-through))
				(print (block nil (return 7) 9))
				(print (block direct (return-from direct 42) 9))
				""")).isEqualTo("400\n7\n42");
	}

	@Test
	void returnFromExitsDefunAcrossLoop() throws Exception {
		// The %fn-block function boundary: a return-from naming the defun exits the
		// function from inside a loop whose after-loop code must not run; a return-from
		// inside a lambda now exits the outer defun as a non-local exit (matching the
		// interpreter and CL) via the block-exit tag.
		assertThat(compileAndRun("""
				(defun find-first-even (xs)
				  (dolist (x xs)
				    (when (evenp x) (return-from find-first-even x)))
				  :none)
				(print (find-first-even '(1 3 6 7)))
				(print (find-first-even '(1 3 5)))
				(defun probe (xs)
				  (mapcar #'(lambda (x) (if (evenp x) (return-from probe :even) x)) xs))
				(print (probe '(1 2 3)))
				""")).isEqualTo("6\n:NONE\n:EVEN");
	}

	@Test
	void crossLambdaReturnFromMidExpression() throws Exception {
		// The cross-lambda exit value is consumed mid-expression: the abandoned outer
		// (list :head ... :tail) operands are discarded, like a plain return.
		assertThat(compileAndRun("""
				(defun probe (xs)
				  (list :head (mapcar #'(lambda (x) (if (evenp x) (return-from probe :even) x)) xs) :tail))
				(print (probe '(1 2 3)))
				(print (probe '(1 3 5)))
				""")).isEqualTo(":EVEN\n(:HEAD (1 3 5) :TAIL)");
	}

	@Test
	void crossLambdaReturnFromRecursiveTargetsCorrectFrame() throws Exception {
		// A recursive defun whose lambda does a cross-lambda return-from: the dynamic
		// block-instance id targets the innermost active frame, so the exit unwinds one
		// activation, not the whole recursion.
		assertThat(compileAndRun("""
				(defun deep (n)
				  (if (= n 0)
				      :bottom
				      (block done
				        (mapcar #'(lambda (x) (return-from done (list n (deep (- n 1)) x))) '(:mark))
				        :unreached)))
				(print (deep 3))
				""")).isEqualTo("(3 (2 (1 :BOTTOM :MARK) :MARK) :MARK)");
	}

	@Test
	void crossLambdaExitToleratesBlockAsVariableName() throws Exception {
		// A cons headed by `block`/`return-from` in a non-operator position -- here a let
		// binding whose variable is named `block` -- must not be mis-parsed as the
		// special
		// form; the cross-lambda return-from referencing it still works.
		assertThat(compileAndRun("""
				(defun uses-block-as-var (xs)
				  (let ((block 10) (return-from 20))
				    (mapcar #'(lambda (x)
				                (if (evenp x)
				                    (return-from uses-block-as-var (+ x block return-from))
				                    x))
				            xs)))
				(print (uses-block-as-var '(1 2 3)))
				(print (uses-block-as-var '(1 3 5)))
				""")).isEqualTo("32\n(1 3 5)");
	}

	@Test
	void crossLambdaReturnFromUserBlockAndFlet() throws Exception {
		// A cross-lambda return-from to a user (block name ...) in argument position,
		// plus
		// one that also crosses an flet local function holding the lambda (the id is
		// captured through both closure levels).
		assertThat(compileAndRun("""
				(defun f (v)
				  (+ 100 (block b (mapcar #'(lambda (x) (if (evenp x) (return-from b x) 0)) v) 7)))
				(print (f '(1 4 5)))
				(print (f '(1 3 5)))
				(defun g (xs)
				  (flet ((h () (mapcar #'(lambda (x) (return-from g (* x 10))) xs)))
				    (h)))
				(print (g '(3 4)))
				""")).isEqualTo("104\n107\n30");
	}

	@Test
	void crossLambdaReturnFromDoesNotLeakHandlerDepth() throws Exception {
		// A cross-lambda return-from unwinding through a handler-case must restore the
		// handler-depth global on the way out (the block-exit passthrough); otherwise a
		// later unhandled signal would wrongly raise (trap) instead of yielding nil.
		assertThat(compileAndRun("""
				(defun leak (xs)
				  (handler-case
				      (mapcar #'(lambda (x) (return-from leak :exited)) xs)
				    (error (e) :caught)))
				(print (leak '(1)))
				(print (signal "quiet"))
				""")).isEqualTo(":EXITED\nNIL");
	}

	@Test
	void crossLambdaReturnFromThroughHandlerCase() throws Exception {
		// A cross-lambda return-from unwinding through a handler-case is NOT intercepted
		// by it (a distinct block-exit tag), and the handler-depth global is restored on
		// the way out so a later signal still sees no established handler.
		assertThat(compileAndRun("""
				(defun probe (xs)
				  (handler-case
				      (mapcar #'(lambda (x) (if (evenp x) (return-from probe :even) x)) xs)
				    (error (e) :caught)))
				(print (probe '(1 2 3)))
				(print (probe '(1 3 5)))
				""")).isEqualTo(":EVEN\n(1 3 5)");
	}

	@Test
	void catchThrow() throws Exception {
		// catch/throw: a DYNAMIC non-local exit riding the block-exit tag -- the throw
		// need not be lexically inside the catch, only within its dynamic extent, and the
		// tag is a runtime value compared with eq.
		assertThat(compileAndRun("""
				(print (catch 'done (+ 1 2)))
				(print (catch 'done (throw 'done :thrown) :not-reached))
				(defun deep (n) (if (= n 0) (throw 'bottom :hit) (deep (- n 1))))
				(print (catch 'bottom (deep 5)))
				(print (catch 'outer (catch 'inner (throw 'outer :to-outer)) :not-reached))
				(print (list :a (catch 'k (throw 'k :b)) :c))
				(let ((tag (list 1)))
				  (print (catch tag (throw tag :computed-tag))))
				(print (catch 'outer (catch (list 1) (throw 'outer :not-eq-to-inner))))
				""")).isEqualTo("3\n:THROWN\n:HIT\n:TO-OUTER\n(:A :B :C)\n:COMPUTED-TAG\n:NOT-EQ-TO-INNER");
	}

	@Test
	void catchThrowRunsUnwindProtectCleanups() throws Exception {
		// A throw unwinds the real wasm stack, so every intervening unwind-protect
		// cleanup (a catch_all_ref that reraises with throw_ref) runs -- innermost first.
		assertThat(compileAndRun("""
				(let ((log nil))
				  (print (catch 'z
				           (unwind-protect
				               (unwind-protect (throw 'z :deep) (setq log (cons :inner log)))
				             (setq log (cons :outer log)))))
				  (print log))
				(print (catch 'c (unwind-protect :body (throw 'c :from-cleanup))))
				""")).isEqualTo(":DEEP\n(:OUTER :INNER)\n:FROM-CLEANUP");
	}

	@Test
	void throwIsNotCaughtByHandlerCase() throws Exception {
		// A throw is a non-local exit, not a signaled condition: the handler-case
		// block-exit passthrough must let it through AND restore the handler-depth
		// global,
		// so a later unhandled signal still yields nil instead of trapping.
		assertThat(compileAndRun("""
				(defun hc-throw (xs)
				  (handler-case (mapcar (lambda (x) (if (evenp x) (throw 'up :hit) x)) xs)
				    (error (e) :caught)))
				(print (catch 'up (hc-throw '(1 2 3))))
				(print (hc-throw '(1 3)))
				(print (signal "quiet"))
				(print (catch 'up (handler-case (error "boom") (error (e) :caught))))
				""")).isEqualTo(":HIT\n(1 3)\nNIL\n:CAUGHT");
	}

	@Test
	void catchThrowAndCrossLambdaExitDoNotCollide() throws Exception {
		// Both kinds throw on the block-exit tag, so each must pass through the other's
		// landing pad untouched. The payload shape is what tells them apart: a block exit
		// carries (id . value) with an i31 id, a user throw ((tag) . value). Without the
		// wrapper cons the fixnum tag 1 below would ref.eq a live block-instance id and
		// the catch would swallow the return-from (it did, before the wrapper).
		assertThat(compileAndRun("""
				(defun probe2 (xs)
				  (list :after (catch 1 (mapcar #'(lambda (x) (if (evenp x) (return-from probe2 :exited) x)) xs))))
				(print (probe2 '(1 2)))
				(defun probe3 (xs)
				  (list :from-probe
				        (mapcar #'(lambda (x) (if (evenp x) (return-from probe3 :nlx) (throw 1 :thrown))) xs)))
				(print (catch 1 (list :outer (probe3 '(1 2)))))
				""")).isEqualTo(":EXITED\n:THROWN");
	}

	@Test
	void charComparisonExtensions() throws Exception {
		assertThat(compileAndRun("""
				(print (char> #\\c #\\b #\\a))
				(print (char>= #\\b #\\b #\\a))
				(print (char/= #\\a #\\b #\\a))
				(print (char-equal #\\A #\\a))
				""")).isEqualTo("T\nT\nNIL\nT");
	}

	@Test
	void psetfParallelPlaces() throws Exception {
		assertThat(compileAndRun("""
				(print (let ((a 1) (b 2)) (psetf a b b a) (list a b)))
				(print (let* ((tail (list 2))
				              (last-cdr tail)
				              (fresh (list 3)))
				         (psetf last-cdr fresh
				                (cdr last-cdr) fresh)
				         (list tail last-cdr)))
				""")).isEqualTo("(2 1)\n((2 3) (3))");
	}

	@Test
	void substAndSimpleStringP() throws Exception {
		// The prelude splice mirrors the CLI pipeline (subst is a prelude defun).
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(print (subst 'x 'a '(a (b a) c)))
				(print (subst 9 '(m) '(f (m) g) :test #'equal))
				(print (simple-string-p "abc"))
				(print (simple-string-p 42))
				""")))).isEqualTo("(X (B X) C)\n(F 9 G)\nT\nNIL");
	}

	@Test
	void closReaderMethodsDispatchPerClass() throws Exception {
		assertThat(compileAndRun("""
				(defclass w1 () ((pad :initarg :pad) (size :initarg :size :accessor size)))
				(defclass w2 () ((size :initarg :size :accessor size)))
				(defclass w3 () ())
				(defmethod size ((w w3)) 0)
				(defvar *w2* (make-instance 'w2 :size 22))
				(setf (size *w2*) 23)
				(print (list (size (make-instance 'w1 :pad 9 :size 11)) (size *w2*) (size (make-instance 'w3))))
				""")).isEqualTo("(11 23 0)");
	}

	@Test
	void initializeInstanceAfterMethod() throws Exception {
		assertThat(compileAndRun("""
				(defclass counted () ((n :initarg :n :accessor n)))
				(defmethod initialize-instance :after ((c counted) &rest init-args)
				  (setf (n c) (* 10 (n c))))
				(print (list (n (make-instance 'counted :n 4)) (n (make-instance 'counted :n 5))))
				""")).isEqualTo("(40 50)");
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
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
				path("test.wasm"));
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
			.isEqualTo("(1 10 NIL)\n(1 2 (3 4))\n105");
	}

	@Test
	void labelsForms() throws Exception {
		assertThat(compileAndRun("(labels ((fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))) (print (fact 6)))"
				+ " (labels ((ev (n) (if (= n 0) t (od (- n 1))))"
				+ " (od (n) (if (= n 0) nil (ev (- n 1))))) (print (list (ev 10) (od 10))))"
				+ " (defun count-down (n) (labels ((go-down (i acc) (if (= i 0) acc (go-down (- i 1) (cons i acc)))))"
				+ " (go-down n nil))) (print (count-down 4))"))
			.isEqualTo("720\n(T NIL)\n(1 2 3 4)");
	}

	@Test
	void macroletForms() throws Exception {
		// macrolet is consumed by eval.UserMacroExpander (the compilers never see it), so
		// mirror the CLI by running the pass before compiling.
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(macrolet ((sq (x) `(* ,x ,x)) (twice (x) `(+ ,x ,x)))
				  (print (+ (sq 5) (twice 5))))
				(defun apply-in-body (n)
				  (macrolet ((mklist (&rest xs) `(list ,@xs)))
				    (mklist n (* n n))))
				(print (apply-in-body 4))
				"""));
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("35\n(4 16)");
	}

	@Test
	void nestedBackquoteOnceOnly() throws Exception {
		// once-only uses three levels of read-time backquote; the reader expands
		// every level to list/cons/quote, so the WASM compiler only sees ordinary
		// forms. Verifies the multiple-evaluation guard holds (bump runs once).
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defmacro once-only (names &body body)
				  (let ((gensyms (loop for name in names collect (gensym (symbol-name name)))))
				    `(let (,@(loop for g in gensyms
				                   for name in names
				                   collect `(,g (gensym ,(symbol-name name)))))
				       `(let (,,@(loop for g in gensyms for n in names
				                       collect ``(,,g ,,n)))
				          ,(let (,@(loop for n in names for g in gensyms
				                         collect `(,n ,g)))
				             ,@body)))))
				(defmacro square (x) (once-only (x) `(* ,x ,x)))
				(defvar *calls* 0)
				(defun bump () (setq *calls* (+ *calls* 1)) 5)
				(print (square (bump)))
				(print *calls*)
				"""));
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("25\n1");
	}

	@Test
	void defineCompilerMacroAndRestartCase() throws Exception {
		// define-compiler-macro is consumed by eval.UserMacroExpander (the compilers
		// never
		// see it), so mirror the CLI by running the pass before compiling. The macro
		// rewrites the call site; returning the &whole form declines; restart-case is a
		// lite lowering to its primary form.
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defun myinc (x) (+ x 1))
				(define-compiler-macro myinc (x) `(+ ,x 100))
				(print (myinc 10))
				(defun mydec (x) (- x 1))
				(define-compiler-macro mydec (&whole form x) (declare (ignore x)) form)
				(print (mydec 10))
				(print (restart-case (+ 1 2) (continue () 99)))
				"""));
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("110\n9\n3");
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
			.isEqualTo("(1 9)\nNIL\n1\n(1 2 NIL)\n(3 1)\n(-3 -1)\n(7 0.5)\n(3 2)\n1\n3\n(1 2 3 2 1)\n3\n4");
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
			.isEqualTo("(42 T)\n(NIL T)\n(NIL NIL)\n(DFLT NIL)");
	}

	@Test
	void multipleValueSetqAndRotatef() throws Exception {
		assertThat(compileAndRun("(let (a b) (multiple-value-setq (a b) (values 1 2)) (print (list a b)))"
				+ " (let (a b) (print (multiple-value-setq (a b) (floor 17 5))) (print (list a b)))"
				+ " (let (a b c) (multiple-value-setq (a b c) (values 1 2)) (print (list a b c)))"
				+ " (let ((x 1) (y 2)) (rotatef x y) (print (list x y)))"
				+ " (let ((a 1) (b 2) (c 3)) (rotatef a b c) (print (list a b c)))"
				+ " (let ((x (cons 1 2))) (rotatef (car x) (cdr x)) (print x))"))
			.isEqualTo("(1 2)\n3\n(3 2)\n(1 2 NIL)\n(2 1)\n(2 3 1)\n(2 . 1)");
	}

	@Test
	void shiftfAndLoadTimeValue() throws Exception {
		assertThat(compileAndRun("(let ((x 1) (y 2) (z 3)) (print (shiftf x y z 4)) (print (list x y z)))"
				+ " (let ((c (cons 1 2))) (print (shiftf (car c) 9)) (print c))"
				+ " (print (+ (load-time-value (* 2 3)) 4))" + " (print (load-time-value 7 t))"
				// Hoisted into a lazily filled slot: one evaluation per occurrence, and a
				// second occurrence gets its own slot.
				+ " (defvar ltv-n 0) (defun ltv-bump () (setq ltv-n (+ ltv-n 1)) ltv-n)"
				+ " (defun ltv-probe () (load-time-value (ltv-bump)))"
				+ " (defun ltv-other () (load-time-value (ltv-bump)))"
				+ " (print (ltv-probe)) (print (ltv-probe)) (print (ltv-probe))"
				+ " (print (ltv-other)) (print ltv-n)"))
			.isEqualTo("1\n(2 3 4)\n1\n(9 . 2)\n10\n7\n1\n1\n1\n2\n2");
	}

	@Test
	void typepAndSubtypep() throws Exception {
		assertThat(compileAndRun("(print (typep 1 'integer))" + " (print (typep \"s\" 'string))"
				+ " (print (typep 1 'string))" + " (print (typep 1.5 'number))" + " (print (typep nil 'null))"
				+ " (defclass pt () ((x :initarg :x))) (print (typep (make-instance 'pt :x 1) 'pt))"
				+ " (print (typep 1 'pt))" + " (print (subtypep 'cons 'list))" + " (print (subtypep 'integer 'number))"
				+ " (print (subtypep 'string 'number))" + " (print (subtypep 'single-float 'float))"
				+ " (defclass base () ()) (defclass derived (base) ()) (print (subtypep 'derived 'base))"))
			.isEqualTo("T\nT\nNIL\nT\nT\nT\nNIL\nT\nT\nNIL\nT\nT");
	}

	@Test
	void typepWithComputedSpecifierAndRuntimeErrorType() throws Exception {
		// A computed typep specifier lowers to the shared %typep-runtime defun and a
		// computed error condition-type with initargs to %error-runtime (both
		// data-table-backed; the inline per-class dispatches overflowed JVM method
		// limits at cl-postgres scale and inflate wasm bodies identically). The
		// answers must match the literal-specifier expansions on every backend.
		assertThat(compileAndRun("""
				(defclass animal () ((name :initarg :name)))
				(defclass dog (animal) ())
				(defstruct point x y)
				(define-condition my-err (error) ((code :initarg :code :reader my-err-code)))
				(let ((d (make-instance 'dog)) (p (make-point :x 1 :y 2)))
				  (let ((ty 'dog)) (print (typep d ty)))
				  (let ((ty 'animal)) (print (typep d ty)))
				  (let ((ty 'point)) (print (typep p ty)))
				  (let ((ty 'integer)) (print (typep 42 ty)))
				  (let ((ty 'string)) (print (typep d ty)))
				  (let ((ty 'standard-object)) (print (typep d ty)))
				  (let ((ty 'structure-object)) (print (typep p ty)))
				  (let ((ty 'no-such-type)) (print (typep 1 ty)))
				  (let ((ty t)) (print (typep 1 ty))))
				(defun boom (ty) (error ty :code 42))
				(print (handler-case (boom 'my-err) (my-err (c) (list :caught (my-err-code c)))))
				""")).isEqualTo("T\nT\nT\nT\nNIL\nT\nT\nNIL\nT\n(:CAUGHT 42)");
	}

	// define-setf-expander / defsetf are consumed by the compile-path pass
	// (eval.UserMacroExpander) and the setf call sites rewritten before the WASM
	// compiler.
	@Test
	void defineSetfExpanderAndDefsetf() throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(am.ik.rontolisp.eval.UserMacroExpander
			.expand(LispReader.readAllFromString("(defun aget (alist key) (cdr (assoc key alist :test #'equal)))"
					+ " (defun %aput (alist key value)" + "   (let ((kv (assoc key alist :test #'equal)))"
					+ "     (if kv (progn (rplacd kv value) alist) (cons (cons key value) alist))))"
					+ " (define-setf-expander aget (alist key &environment env)"
					+ "   (multiple-value-bind (d v n setter getter) (get-setf-expansion alist env)"
					+ "     (let ((nv (first n)))"
					+ "       (values d v n `(let ((,nv (%aput ,alist ,key ,nv))) ,setter ,nv) `(aget ,getter ,key)))))"
					+ " (defun ref (box) (car box)) (defsetf ref rplaca)"
					+ " (let ((d (list (cons :a 1)))) (setf (aget d :a) 100) (setf (aget d :b) 2) (incf (aget d :a) 5)"
					+ "   (print (list (aget d :a) (aget d :b))))"
					+ " (print (let ((b (list 1))) (setf (ref b) 9) b))")));
		assertThat(compileAndRunProgram(program)).isEqualTo("(105 2)\n(9)");
	}

	@Test
	void typepResolvesUserDeftype() throws Exception {
		assertThat(compileAndRun(
				"(deftype my-even () '(satisfies evenp))" + " (defun my-alistp (x) (and (listp x) (every #'consp x)))"
						+ " (deftype my-alist () '(satisfies my-alistp))" + " (deftype my-int () 'integer)"
						+ " (print (typep 4 'my-even)) (print (typep 3 'my-even))"
						+ " (print (typep '((a . 1)) 'my-alist)) (print (typep '(a b) 'my-alist))"
						+ " (print (typep 7 'my-int)) (print (typep 'x 'my-int))"))
			.isEqualTo("T\nNIL\nT\nNIL\nT\nNIL");
	}

	@Test
	void liteBuiltinsResidue() throws Exception {
		assertThat(compileAndRun("(print (mask-field (byte 4 4) 255))" + " (print (mask-field (byte 8 0) 300))"
				+ " (print (scale-float 1.5 3))" + " (print (scale-float 1.0 -100000))"
				+ " (defun fd-doubler (x) (* x 2)) (print (funcall (fdefinition 'fd-doubler) 21))"
				+ " (print (file-position t)) (print (file-length t)) (print (pathnamep \"/tmp/x\"))"
				+ " (print (stream-element-type t))" + " (print (input-stream-p t))"
				+ " (print (output-stream-p (make-broadcast-stream)))" + " (print (input-stream-p \"s\"))"))
			.isEqualTo("240\n44\n12.0\n0.0\n42\nNIL\nNIL\nNIL\nCHARACTER\nT\nT\nNIL");
	}

	@Test
	void charNamePrelude() throws Exception {
		// The prelude splice mirrors the CLI pipeline (char-name is a prelude defun).
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(print (char-name #\\Space))
				(print (char-name #\\a))
				(print (char-name (code-char 1)))
				(print (char-name (code-char 128)))
				""")))).isEqualTo("\"Space\"\nNIL\n\"U+0001\"\n\"U+0080\"");
	}

	@Test
	void classOfAndSlotAccessors() throws Exception {
		assertThat(compileAndRun("(print (class-of 42)) (print (class-of \"s\")) (print (class-of 'foo))"
				+ " (print (class-of :k)) (print (class-of 1.5)) (print (class-of (cons 1 2)))"
				+ " (print (class-of nil)) (print (class-of t)) (print (class-of (make-hash-table)))"
				+ " (print (class-of #'car))"
				+ " (defclass co-pt () ((x :initarg :x))) (print (class-of (make-instance 'co-pt :x 1)))"))
			.isEqualTo(
					"INTEGER\nSTRING\nSYMBOL\nKEYWORD\nFLOAT\nCONS\nNULL\nBOOLEAN\nHASH-TABLE\nFUNCTION\n%class-CO-PT");
		// Real unboundness (todo-199): the supplied slot is bound, an unknown one is
		// not, and slot-makunbound puts it back to unbound.
		assertThat(compileAndRun(
				"(defclass sb-pt () ((x :initarg :x) (y :initarg :y)))" + " (let ((p (make-instance 'sb-pt :x 1 :y 2)))"
						+ " (print (slot-boundp p 'x)) (print (slot-boundp p 'sb-absent))"
						+ " (slot-makunbound p 'x) (print (slot-boundp p 'x)))"))
			.isEqualTo("T\nNIL\nNIL");
	}

	@Test
	void simpleConditionFormatAccessors() throws Exception {
		assertThat(compileAndRun(
				"(handler-case (error \"boom ~a\" 1)" + " (error (c) (print (simple-condition-format-control c))"
						+ " (print (simple-condition-format-arguments c))))"))
			.isEqualTo("\"boom 1\"\nNIL");
		assertThat(compileAndRun("(define-condition sc-err (error)"
				+ " ((format-control :initarg :format-control) (format-arguments :initarg :format-arguments)))"
				+ " (handler-case (error 'sc-err :format-control \"ctl\" :format-arguments '(1 2))"
				+ " (error (c) (print (simple-condition-format-control c))"
				+ " (print (simple-condition-format-arguments c))))"))
			.isEqualTo("\"ctl\"\n(1 2)");
	}

	@Test
	void multiParameterDispatchVariadicGenericsAndDefaultInitargs() throws Exception {
		assertThat(compileAndRun("""
				(defclass mp-animal () ())
				(defclass mp-dog (mp-animal) ())
				(defclass mp-cat (mp-animal) ())
				(defgeneric mp-meets (a b))
				(defmethod mp-meets ((a mp-dog) (b mp-cat)) :chase)
				(defmethod mp-meets ((a mp-cat) (b mp-dog)) :flee)
				(defmethod mp-meets ((a mp-animal) (b mp-animal)) :ignore)
				(let ((d (make-instance 'mp-dog)) (c (make-instance 'mp-cat)))
				  (print (list (mp-meets d c) (mp-meets c d) (mp-meets d d))))
				(defgeneric vg-desc (x &rest extras))
				(defmethod vg-desc ((x mp-dog) &rest extras) (list :dog extras))
				(defmethod vg-desc ((x mp-animal) &rest extras) (list :animal extras))
				(print (vg-desc (make-instance 'mp-dog) 1 2))
				(defclass di-conf () ((host :initarg :host) (port :initarg :port))
				  (:default-initargs :host "localhost" :port 8080))
				(let ((c1 (make-instance 'di-conf)) (c2 (make-instance 'di-conf :port 9090)))
				  (print (list (slot-value c1 'host) (slot-value c1 'port) (slot-value c2 'port))))
				(defclass ws-pt () ((x :initarg :x) (y :initarg :y)))
				(let ((p (make-instance 'ws-pt :x 1 :y 2)))
				  (with-slots (x (why y)) p (setf x (+ x 10)) (print (list x why)))
				  (print (slot-value p 'x)))
				(defstruct (so-kv (:constructor make-so-pair) (:conc-name so-get-)
				                  (:predicate so-kv?) (:copier so-clone))
				  key val)
				(let ((k (make-so-pair :key 'a :val 1)))
				  (print (list (so-get-key k) (so-kv? k) (so-kv? 5)))
				  (let ((k2 (so-clone k)))
				    (setf (so-get-val k2) 99)
				    (print (list (so-get-val k) (so-get-val k2)))))
				""")).isEqualTo(
				"(:CHASE :FLEE :IGNORE)\n(:DOG (1 2))\n(\"localhost\" 8080 9090)\n(11 2)\n11\n" + "(A T NIL)\n(1 99)");
	}

	@Test
	void grayStreamInstanceDispatch() throws Exception {
		// The GrayStreamsLibrary pre-pass splices gray.lisp and rewrites the
		// write-string/write-char call sites onto the dispatch helpers, mirroring the
		// CLI pipeline.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.GrayStreamsLibrary.process(LispReader.readAllFromString("""
				(defclass gs-upcase (rontolisp:fundamental-character-output-stream)
				  ((acc :initform "")))
				(defmethod rontolisp:stream-write-string ((s gs-upcase) str)
				  (setf (slot-value s 'acc) (concatenate 'string (slot-value s 'acc) (string-upcase str)))
				  str)
				(let ((s (make-instance 'gs-upcase)))
				  (write-string "hello" s)
				  (write-char #\\! s)
				  (print (slot-value s 'acc)))
				(write-string "still-works" t)
				(terpri)
				""")))).isEqualTo("\"HELLO!\"\nstill-works");
	}

	@Test
	void readTimeEvalMarkers() throws Exception {
		// The CLI pipeline: marker read -> UserMacroExpander resolves each marker
		// against the macro-time evaluator -> the compilers see plain forms.
		assertThat(compileAndRunProgram(
				am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllWithReadEvalMarkers(
						"(defvar +re-six+ #.(* 2 3)) (print +re-six+)" + " (print #.(+ 40 2)) (print '(a #.(+ 1 2) c))"
								+ " (defmacro re-stamp (&rest body) `(list #.(* 7 6) ,@body)) (print (re-stamp 1 2))",
						am.ik.rontolisp.reader.Features.WASM))))
			.isEqualTo("6\n42\n(A 3 C)\n(42 1 2)");
	}

	@Test
	void writeStringBoundsAndReplaceNilBounds() throws Exception {
		assertThat(compileAndRun("(write-string \"hello\" t :start 1 :end 3) (terpri)"
				+ " (write-string \"hello\" t :start 1 :end nil) (terpri) (print (write-string \"xy\"))"))
			.isEqualTo("el\nello\nxy\"xy\"");
		assertThat(compileAndRun("(let ((s \"abcdef\")) (print (replace s \"XYZ\" :start1 1 :end1 nil)))"))
			.isEqualTo("\"aXYZef\"");
	}

	@Test
	void setfValuesAndSetfThroughTheAndSymbolp() throws Exception {
		assertThat(compileAndRun("(let ((a 0) (b 0)) (setf (values a b) (values 1 2)) (print (list a b)))"
				+ " (let ((x 0)) (setf (the integer x) 5) (print x))"
				+ " (print (list (symbolp nil) (symbolp t) (symbolp 'foo) (symbolp \"s\") (symbolp 1)))"))
			.isEqualTo("(1 2)\n5\n(T T T NIL NIL)");
	}

	@Test
	void tagbodyGoAndProg() throws Exception {
		assertThat(compileAndRun("(let ((i 0)) (tagbody top (setq i (+ i 1)) (if (< i 3) (go top))) (print i))"
				+ " (tagbody (print 1) (go skip) (print 2) skip (print 3))"
				+ " (let ((n 0) (acc nil)) (tagbody loop (setq n (+ n 1)) (push n acc)"
				+ " (if (< n 4) (go loop)) done) (print acc))" + " (let ((x 0)) (tagbody outer (setq x (+ x 10))"
				+ " (tagbody (if (> x 10) (go end)) (go outer)) end) (print x))" + " (print (tagbody (print 1)))"
				+ " (let ((i 0)) (tagbody top (setq i (+ i 1)) (print (+ 100 (if (< i 3) (go top) i)))))"
				+ " (print (prog ((i 0)) top (setq i (+ i 1)) (if (< i 3) (go top)) (return i)))"
				+ " (print (prog* ((x 2) (y (* x 3))) (return (+ x y))))" + " (print (prog ((x 1)) (print x)))"))
			.isEqualTo("3\n1\n3\n(4 3 2 1)\n20\n1\nNIL\n103\n3\n8\n1\nNIL");
	}

	@Test
	void goEscapesUnwindProtect() throws Exception {
		assertThat(compileAndRunEh("(let ((i 0)) (tagbody top (setq i (+ i 1))"
				+ " (unwind-protect (if (< i 3) (go top)) (print (list :cleanup i)))) (print i))"))
			.isEqualTo("(:CLEANUP 1)\n(:CLEANUP 2)\n(:CLEANUP 3)\n3");
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
			.isEqualTo("10\n(1 10 NIL)\n(1 (2 3))\n(1 2 5)\n(1 2 3)\n3\n(2 12)");
	}

	@Test
	void everyFunction() throws Exception {
		assertThat(compileAndRun("(print (every #'evenp '(2 4 6))) (print (every #'evenp '(2 3 6)))"))
			.isEqualTo("T\nNIL");
	}

	@Test
	void someFunction() throws Exception {
		assertThat(compileAndRun("(print (some #'oddp '(2 4 5))) (print (some #'oddp '(2 4 6)))")).isEqualTo("T\nNIL");
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
		assertThat(compileAndRun("(print (sort '() #'<)) (print (sort '(5) #'<))")).isEqualTo("NIL\n(5)");
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
	void mapcarMultipleLists() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'+ '(1 2 3 4) '(10 20 30 40)))")).isEqualTo("(11 22 33 44)");
	}

	@Test
	void mapcarMultipleListsStopsAtShortest() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'cons '(1 2 3) '(a b)))")).isEqualTo("((1 . A) (2 . B))");
	}

	@Test
	void mapIntoList() throws Exception {
		assertThat(compileAndRun("(print (map-into (list 0 0 0 0) #'+ '(1 2 3) '(10 20 30 40)))"))
			.isEqualTo("(11 22 33 0)");
	}

	@Test
	void mapIntoVectorAndSymbolDesignator() throws Exception {
		assertThat(compileAndRun("(print (map-into (make-array 3) #'* #(2 3 4) #(5 6 7)))")).isEqualTo("#(10 18 28)");
		assertThat(compileAndRun("(print (map-into (list nil nil nil) '1+ '(7 8 9)))")).isEqualTo("(8 9 10)");
	}

	@Test
	void mapIntoMixedOperandsAndLargeList() throws Exception {
		// Runtime list-or-vector dispatch per operand (result and sources independent).
		assertThat(compileAndRun("(print (map-into (list 0 0 0) #'+ #(1 2 3) '(10 20 30)))")).isEqualTo("(11 22 33)");
		assertThat(compileAndRun("(print (map-into (make-array 3) #'+ '(1 2 3) #(10 20 30)))"))
			.isEqualTo("#(11 22 33)");
		// Regression guard: all-list operands must stay O(n).
		assertThat(compileAndRun("(print (length (map-into (make-list 20000) (lambda (x) 1) (make-list 20000))))"))
			.isEqualTo("20000");
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
			.hasMessageContaining("Cannot compile symbol: CAR");
	}

	// read-line tests

	private static String compileAndRunWithStdin(String lispCode, String stdin) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"echo '" + stdin + "' | wasmtime --wasm gc " + path("test.wasm"));
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
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"echo -n '' | wasmtime --wasm gc " + path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("T");
	}

	@Test
	void readLineStringp() throws Exception {
		assertThat(compileAndRunWithStdin("(print (stringp (read-line)))", "hello")).isEqualTo("T");
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
		assertThat(compileAndRunWithStdin("(print (read))", "foo")).isEqualTo("FOO");
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
		assertThat(compileAndRunWithStdin("(print (car (read)))", "(a b c)")).isEqualTo("A");
	}

	@Test
	void readNil() throws Exception {
		assertThat(compileAndRunWithStdin("(print (null (read)))", "nil")).isEqualTo("T");
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
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		wasmtime.copyFileToContainer(Transferable.of(stdin.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				path("stdin.txt"));
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime --wasm gc " + path("test.wasm") + " < " + path("stdin.txt"));
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	@Test
	void readSharpQuote() throws Exception {
		assertThat(compileAndRunWithStdinFile("(print (read))", "#'car\n")).isEqualTo("(FUNCTION CAR)");
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
			.isEqualTo("SQUARE\n49\n(1 4 9)");
	}

	// load tests

	private static String compileAndRunLoad(String lispCode, String libContent) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		wasmtime.copyFileToContainer(Transferable.of(libContent.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				path("lib.lisp"));
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"cd " + workDir() + " && wasmtime --wasm gc --wasm exceptions=y --dir . test.wasm");
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	// file I/O tests (with-open-file/open/close/write-line/read-line)

	private static String compileAndRunWithDir(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"cd " + workDir() + " && wasmtime --wasm gc --wasm exceptions=y --dir . test.wasm");
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
		assertThat(compileAndRunWithDir(code)).isEqualTo("\"hello\"\n\"world\"\nNIL");
	}

	@Test
	void withOpenFileReturnsBodyValue() throws Exception {
		String code = "(print (with-open-file (out \"wof-ret.txt\" :direction :output) (write-line \"x\" out) 42))";
		assertThat(compileAndRunWithDir(code)).isEqualTo("42");
	}

	@Test
	void probeFileAnswersThePathOrNil() throws Exception {
		// The missing-path branch is the load-bearing one: _open TRAPS on a non-zero
		// path_open errno (and a trap is not catchable), so the program only survives to
		// print anything if _probe_file has its own errno-to-nil branch.
		String code = """
				(with-open-file (out "probe.txt" :direction :output) (write-line "x" out))
				(print (probe-file "probe.txt"))
				(print (probe-file "absent.txt"))
				(print (if (probe-file "absent.txt") 'yes 'no))
				""";
		assertThat(compileAndRunWithDir(code)).isEqualTo("\"probe.txt\"\nNIL\nNO");
	}

	@Test
	void probeFileLeaksNoDescriptor() throws Exception {
		// A probe must close what it opened: 300 probes with a leak exhaust the
		// descriptor table and the subsequent open fails (traps).
		String code = """
				(with-open-file (out "probe-fd.txt" :direction :output) (write-line "x" out))
				(dotimes (i 300) (probe-file "probe-fd.txt"))
				(with-open-file (in "probe-fd.txt") (print (read-line in)))
				""";
		assertThat(compileAndRunWithDir(code)).isEqualTo("\"x\"");
	}

	@Test
	void probeFileAsFunctionValueAndViaUiop() throws Exception {
		String code = """
				(with-open-file (out "probe-fc.txt" :direction :output) (write-line "x" out))
				(print (mapcar #'probe-file (list "probe-fc.txt" "probe-nope.txt")))
				(print (uiop:file-exists-p "probe-fc.txt"))
				(print (uiop:file-exists-p "probe-nope.txt"))
				""";
		assertThat(compileAndRunWithDir(code)).isEqualTo("(\"probe-fc.txt\" NIL)\n\"probe-fc.txt\"\nNIL");
	}

	@Test
	void componentProbeFile() throws Exception {
		// The component path opens over wasi:filesystem@0.3.0 through a different
		// adapter, so the errno-to-nil branch is verified there too.
		String code = """
				(with-open-file (out "cprobe.txt" :direction :output) (write-line "x" out))
				(print (probe-file "cprobe.txt"))
				(print (probe-file "cabsent.txt"))
				""";
		assertThat(compileAndRunComponentWithDir(code)).isEqualTo("\"cprobe.txt\"\nNIL");
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
	void withOutputToStringCollectsPrintFamilyOutput() throws Exception {
		assertThat(compileAndRun("""
				(print (with-output-to-string (s)
				  (princ "a=" s)
				  (princ 42 s)
				  (terpri s)
				  (prin1 "q" s)
				  (write-line " end" s)
				  (write-string "tail" s)))""")).isEqualTo("\"a=42\n\"q\" end\ntail\"");
	}

	@Test
	void withOutputToStringEmptyBodyIsEmptyString() throws Exception {
		assertThat(compileAndRun("(print (with-output-to-string (s)))")).isEqualTo("\"\"");
	}

	@Test
	void withOutputToStringDoesNotTouchStandardOutput() throws Exception {
		assertThat(compileAndRun("(with-output-to-string (s) (princ \"hidden\" s)) (princ \"visible\")"))
			.isEqualTo("visible");
	}

	@Test
	void readtableCaseIsConstantUpcaseAndInternAcceptsFoundKeywordPackage() throws Exception {
		assertThat(compileAndRun("(print (readtable-case *readtable*))")).isEqualTo(":UPCASE");
		assertThat(compileAndRun("(print (intern \"ZAP\" (find-package :keyword)))")).isEqualTo(":ZAP");
	}

	@Test
	void withOutputToStringBindingStandardOutputCapturesStreamlessPrints() throws Exception {
		// Binding *standard-output* as the target variable redirects the whole
		// stream-argument-less print family, including inside called functions
		// (s-sql's to-sql-name / sql-escape-string shape).
		assertThat(compileAndRun("""
				(defun emit-name () (princ "foo") (write-char #\\.) (write-string "bar"))
				(princ (with-output-to-string (*standard-output*)
				  (emit-name)
				  (format t "~a" 42)))
				(princ "|")
				(let ((*standard-output* (%make-string-output-stream)))
				  (princ "hidden"))
				(princ "visible")""")).isEqualTo("foo.bar42|visible");
	}

	@Test
	void formatStreamDestinationWritesToStringStream() throws Exception {
		assertThat(compileAndRun("(princ (with-output-to-string (s) (format s \"x=~a, y=~s~%\" 1 \"two\")))"))
			.isEqualTo("x=1, y=\"two\"");
	}

	@Test
	void printToStringStream() throws Exception {
		assertThat(compileAndRun("(princ (with-output-to-string (s) (print 42 s)))")).isEqualTo("42");
	}

	@Test
	void withInputFromStringReadsLinesAndData() throws Exception {
		assertThat(compileAndRun("""
				(with-input-from-string (s "first line
				(1 2 3)
				third")
				  (print (read-line s))
				  (print (read s))
				  (print (read-line s))
				  (print (read-line s)))""")).isEqualTo("\"first line\"\n(1 2 3)\n\"third\"\nNIL");
	}

	@Test
	void withInputFromStringInLoop() throws Exception {
		assertThat(compileAndRun("""
				(with-input-from-string (s "a
				b
				c")
				  (setq line (read-line s))
				  (while line
				    (princ line)
				    (setq line (read-line s))))""")).isEqualTo("abc");
	}

	// A supplementary code point read through read-char comes back as ONE CHARACTER
	// (a full code point), not as its UTF-8 lead byte: _read_char decodes 1-4 byte
	// sequences via the lead byte's high-bit range in both string-stream and WASI fd
	// branches. Matches the interpreter and JVM (surrogate-pair combining there;
	// UTF-8 sequence walk here).
	@Test
	void readCharDecodesSupplementaryCodePointFromStringStream() throws Exception {
		// A plain symbol (not keyword) as the eof-value sidesteps the WASM princ-list
		// keyword-with-colon divergence -- the assertion is about the astral decode.
		assertThat(compileAndRun("""
				(with-input-from-string (s "😀X")
				  (let* ((c1 (read-char s))
				         (c2 (read-char s))
				         (c3 (read-char s nil 'done)))
				    (princ (list (char-code c1) (char-code c2) c3))))""")).isEqualTo("(128512 88 DONE)");
	}

	// Same, for a 2-byte (Latin-1 supplement) and 3-byte (Greek) sequence.
	@Test
	void readCharDecodesMultibyteBmpFromStringStream() throws Exception {
		assertThat(compileAndRun("""
				(with-input-from-string (s "éαa")
				  (princ (char-code (read-char s)))
				  (princ #\\Space)
				  (princ (char-code (read-char s)))
				  (princ #\\Space)
				  (princ (char-code (read-char s))))""")).isEqualTo("233 945 97");
	}

	@Test
	void writeStringWithoutStreamPrintsToStdoutWithoutNewline() throws Exception {
		assertThat(compileAndRun("(write-string \"no\") (write-string \" newline\")")).isEqualTo("no newline");
	}

	@Test
	void writeToStringIsPrin1ToString() throws Exception {
		assertThat(compileAndRun("(princ (write-to-string '(a \"b\" 3)))")).isEqualTo("(A \"b\" 3)");
	}

	@Test
	void princStreamDesignatorTGoesToStandardOutput() throws Exception {
		assertThat(compileAndRun("(princ \"a\" t) (princ \"b\" nil)")).isEqualTo("ab");
	}

	@Test
	void writeStringToFileStream() throws Exception {
		String code = """
				(with-open-file (out "ws.txt" :direction :output)
				  (write-string "ab" out)
				  (write-string "cd" out)
				  (terpri out))
				(with-open-file (in "ws.txt")
				  (print (read-line in)))
				""";
		assertThat(compileAndRunWithDir(code)).isEqualTo("\"abcd\"");
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
		assertThat(compileAndRunWithDir(code)).isEqualTo("0\n10\n34\n255\nNIL");
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
		// The embedded runtime reader is case-preserving while compiled references
		// read upcased, so runtime-loaded definitions are spelled uppercase to be
		// reachable from compiled code on this backend (the JVM eval runtime bridges
		// the cases; the WASM one does not).
		String lib = "(defun SQUARE (x) (* x x))\n(setq BASE 5)\n";
		String code = "(load \"lib.lisp\") (print (eval '(square base)))";
		assertThat(compileAndRunLoad(code, lib)).isEqualTo("25");
	}

	@Test
	void loadMultipleForms() throws Exception {
		String lib = "(defun INC (x) (+ x 1))\n(defun DBL (x) (* x 2))\n";
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
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("64");
	}

	@Test
	void requireNotConsumedByInlinerThrows() {
		// One that reaches the compiler (nested, or a unit test bypassing the pass) is
		// a hard error -- unlike load, the compiled runtime reader cannot execute it.
		assertThatThrownBy(() -> new WasmLispCompiler().compile(LispReader.readAllFromString("(if t (require :util))")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("REQUIRE is only supported as a literal top-level form");
	}

	// dynamic mode (late binding) tests

	private static String compileAndRunLoadDynamic(String lispCode, String libContent) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler(true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		wasmtime.copyFileToContainer(Transferable.of(libContent.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				path("lib.lisp"));
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"cd " + workDir() + " && wasmtime --wasm gc --wasm exceptions=y --dir . test.wasm");
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	@Test
	void dynamicCallToLoadDefinedFunction() throws Exception {
		// (cube 3) is unknown at compile time; dynamic mode resolves it at runtime.
		String lib = "(defun CUBE (x) (* x x x))\n";
		String code = "(load \"lib.lisp\") (print (cube 3))";
		assertThat(compileAndRunLoadDynamic(code, lib)).isEqualTo("27");
	}

	@Test
	void dynamicCallFromCompiledFunctionSeesLocals() throws Exception {
		// caller is compiled; its local n must reach the runtime-resolved cube/square.
		String lib = "(defun CUBE (x) (* x x x))\n(defun SQUARE (x) (* x x))\n";
		String code = "(load \"lib.lisp\") (defun caller (n) (+ (cube n) (square n))) (print (caller 5))";
		assertThat(compileAndRunLoadDynamic(code, lib)).isEqualTo("150");
	}

	@Test
	void dynamicReferenceToLoadDefinedVariable() throws Exception {
		String lib = "(setq BASE 7)\n";
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
		assertThat(compileAndRun("(print (eval ''hello))")).isEqualTo("HELLO");
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
		assertThat(compileAndRun("(print (eval ':foo))")).isEqualTo(":FOO");
	}

	@Test
	void evalCond() throws Exception {
		assertThat(compileAndRun("(print (eval '(cond ((= 1 2) 10) ((= 1 1) 20) (t 30))))")).isEqualTo("20");
		assertThat(compileAndRun("(print (eval '(cond (nil 1))))")).isEqualTo("NIL");
	}

	@Test
	void evalAnd() throws Exception {
		assertThat(compileAndRun("(print (eval '(and 1 2 3)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (eval '(and 1 nil 3)))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (eval '(and)))")).isEqualTo("T");
	}

	@Test
	void evalOr() throws Exception {
		assertThat(compileAndRun("(print (eval '(or nil nil 5)))")).isEqualTo("5");
		assertThat(compileAndRun("(print (eval '(or nil nil)))")).isEqualTo("NIL");
	}

	@Test
	void evalWhenUnless() throws Exception {
		assertThat(compileAndRun("(print (eval '(when (= 1 1) 7 8 9)))")).isEqualTo("9");
		assertThat(compileAndRun("(print (eval '(when nil 1)))")).isEqualTo("NIL");
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
		assertThat(compileAndRun("(print (eval '(dotimes (i 3))))")).isEqualTo("NIL");
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
		assertThat(compileAndRun("(print (eval '(nth 5 (list 10 20 30))))")).isEqualTo("NIL");
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
	void logSoftwareApproximation() throws Exception {
		// WASM has no native log instruction; it is approximated in f64 (exponent
		// extraction + an atanh series, relative error ~1e-10), so results match
		// Math.log up to the printer's six decimal places but not bit-exactly. log(1)
		// is exactly 0.0.
		assertThat(compileAndRun("(print (log 1))")).isEqualTo("0.0");
		assertThat(Double.parseDouble(compileAndRun("(print (log 2))"))).isCloseTo(Math.log(2), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (log 10.0))"))).isCloseTo(Math.log(10), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (log 0.9))"))).isCloseTo(Math.log(0.9), within(1e-5));
		// Both far ends of the exponent range, including a denormal (pre-scaled by 2^54).
		assertThat(Double.parseDouble(compileAndRun("(print (log 1e300))"))).isCloseTo(Math.log(1e300), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (log 4.9e-324))"))).isCloseTo(Math.log(4.9e-324),
				within(1e-5));
		// The IEEE edges match Math.log.
		assertThat(compileAndRun("(print (log 0.0))")).isEqualTo("-Infinity");
		assertThat(compileAndRun("(print (log -1.0))")).isEqualTo("NaN");
		// log as a first-class value over an integer argument.
		assertThat(Double.parseDouble(compileAndRun("(print (funcall #'log 10))"))).isCloseTo(Math.log(10),
				within(1e-5));
	}

	@Test
	void tanhSoftwareApproximation() throws Exception {
		// WASM derives tanh from the software exp -- (e^(2x)-1)/(e^(2x)+1) with the
		// doubled argument clamped to +/-40 -- so results match Math.tanh up to the
		// printer's six decimal places but not bit-exactly. tanh(0) is exactly 0.0 and
		// the clamp saturates large arguments to exactly +/-1.0.
		assertThat(compileAndRun("(print (tanh 0))")).isEqualTo("0.0");
		assertThat(Double.parseDouble(compileAndRun("(print (tanh 1.0))"))).isCloseTo(Math.tanh(1), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (tanh -0.5))"))).isCloseTo(Math.tanh(-0.5), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (tanh 0.001))"))).isCloseTo(Math.tanh(0.001), within(1e-5));
		assertThat(compileAndRun("(print (tanh 25.0))")).isEqualTo("1.0");
		assertThat(compileAndRun("(print (tanh -25.0))")).isEqualTo("-1.0");
		// tanh as a first-class value.
		assertThat(Double.parseDouble(compileAndRun("(print (funcall #'tanh 1))"))).isCloseTo(Math.tanh(1),
				within(1e-5));
	}

	@Test
	void sinCosTanSoftwareApproximation() throws Exception {
		// WASM has no native trigonometric instruction; sin/cos/tan are approximated in
		// f64 (Cody-Waite reduction over pi/2 quadrants + Taylor polynomials, relative
		// error ~1e-11 for |x| up to ~1e6), so results match Math.sin/cos/tan up to the
		// printer's six decimal places but not bit-exactly. The zero and quadrant
		// anchors are exact.
		assertThat(compileAndRun("(print (sin 0))")).isEqualTo("0.0");
		assertThat(compileAndRun("(print (cos 0))")).isEqualTo("1.0");
		assertThat(compileAndRun("(print (tan 0))")).isEqualTo("0.0");
		assertThat(compileAndRun("(print (sin (/ pi 2)))")).isEqualTo("1.0");
		assertThat(compileAndRun("(print (cos pi))")).isEqualTo("-1.0");
		assertThat(Double.parseDouble(compileAndRun("(print (sin 1.0))"))).isCloseTo(Math.sin(1), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (cos -2.5))"))).isCloseTo(Math.cos(-2.5), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (tan 2.0))"))).isCloseTo(Math.tan(2), within(1e-5));
		// Every quadrant of the reduction, plus a large argument (|x| up to ~1e6 keeps
		// full precision; beyond that the low digits diverge, documented like exp's).
		assertThat(Double.parseDouble(compileAndRun("(print (sin 100.0))"))).isCloseTo(Math.sin(100), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (cos 4.0))"))).isCloseTo(Math.cos(4), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (sin -7.5))"))).isCloseTo(Math.sin(-7.5), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (sin 1000000.0))"))).isCloseTo(Math.sin(1e6), within(1e-5));
		// The IEEE edges: NaN and +/-inf map to NaN, matching Math.sin/cos/tan.
		assertThat(compileAndRun("(print (sin (/ 0.0 0.0)))")).isEqualTo("NaN");
		assertThat(compileAndRun("(print (cos (/ 1.0 0.0)))")).isEqualTo("NaN");
		assertThat(compileAndRun("(print (tan (/ -1.0 0.0)))")).isEqualTo("NaN");
		// First-class values over integer arguments.
		assertThat(Double.parseDouble(compileAndRun("(print (funcall #'sin 1))"))).isCloseTo(Math.sin(1), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (funcall #'cos 1))"))).isCloseTo(Math.cos(1), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (funcall #'tan 1))"))).isCloseTo(Math.tan(1), within(1e-5));
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
		assertThat(compileAndRun("(print (integerp (random 100)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (floatp (random 5.0)))")).isEqualTo("T");
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
		assertThat(compileAndRun("(let ((x 5.0)) (print (floatp (random x))))")).isEqualTo("T");
		assertThat(compileAndRun("(let ((x 5)) (print (integerp (random x))))")).isEqualTo("T");
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
		// The i64 path: operands and results beyond the i31 fixnum range stay exact
		// (gcd/lcm compute in i64 over the boxed exact-integer representation).
		assertThat(compileAndRun("(print (gcd 4294967296 6442450944))")).isEqualTo("2147483648");
		assertThat(compileAndRun("(print (gcd 9000000000 -6000000000))")).isEqualTo("3000000000");
		assertThat(compileAndRun("(print (lcm 4294967296 6442450944))")).isEqualTo("12884901888");
		assertThat(compileAndRun("(print (lcm 3000000000 2))")).isEqualTo("3000000000");
	}

	@Test
	void randomOnABoxedIntegerLimit() throws Exception {
		// An integer limit beyond the i31 range used to trap on the i31 cast; the
		// integer path now draws 63 bits and computes rem in i64.
		assertThat(compileAndRun("""
				(let ((r (random 10000000000)))
				  (print (integerp r))
				  (print (and (>= r 0) (< r 10000000000))))
				""")).isEqualTo("T\nT");
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
	void arcAndHyperbolicSoftwareApproximation() throws Exception {
		// asin/acos/atan/sinh/cosh were the LAST
		// members of BuiltinFunctionWrappers.WASM_UNSUPPORTED -- every transcendental
		// built-in now has a WASM software approximation. atan = odd/reciprocal folds +
		// two half-angle folds + a 10-term Taylor series (~1e-15 relative); asin/acos
		// derive from it; sinh/cosh derive from the software exp (~1e-7 relative for
		// |x| up to ~20, degrading beyond like exp itself), sinh switching to its odd
		// Taylor series below |x| = 0.25 to dodge the e - 1/e cancellation. Exact
		// anchors and IEEE edges are exact; everything else matches java.lang.Math to
		// the printer's six decimal places but not bit-exactly.
		assertThat(compileAndRun("(print (atan 0))")).isEqualTo("0.0");
		assertThat(compileAndRun("(print (asin 0))")).isEqualTo("0.0");
		assertThat(compileAndRun("(print (acos 1))")).isEqualTo("0.0");
		assertThat(compileAndRun("(print (sinh 0))")).isEqualTo("0.0");
		assertThat(compileAndRun("(print (cosh 0))")).isEqualTo("1.0");
		// The reciprocal fold maps the domain edges exactly: atan(inf) = asin(1) =
		// pi/2, acos(-1) = pi.
		assertThat(Double.parseDouble(compileAndRun("(print (asin 1))"))).isCloseTo(Math.PI / 2, within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (asin -1))"))).isCloseTo(-Math.PI / 2, within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (acos -1))"))).isCloseTo(Math.PI, within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (atan (/ 1.0 0.0)))"))).isCloseTo(Math.PI / 2,
				within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (atan (/ -1.0 0.0)))"))).isCloseTo(-Math.PI / 2,
				within(1e-5));
		// Both atan folds (|x| <= 1 and the reciprocal branch), and the derivations.
		assertThat(Double.parseDouble(compileAndRun("(print (atan 1.0))"))).isCloseTo(Math.atan(1), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (atan -2.5))"))).isCloseTo(Math.atan(-2.5), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (atan 1000000.0))"))).isCloseTo(Math.atan(1e6),
				within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (asin 0.5))"))).isCloseTo(Math.asin(0.5), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (asin -0.9999))"))).isCloseTo(Math.asin(-0.9999),
				within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (acos 0.5))"))).isCloseTo(Math.acos(0.5), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (acos -0.5))"))).isCloseTo(Math.acos(-0.5), within(1e-5));
		// sinh's small-x series branch (|x| <= 0.25), the exp branch on both sides of
		// it, and cosh.
		assertThat(Double.parseDouble(compileAndRun("(print (sinh 0.1))"))).isCloseTo(Math.sinh(0.1), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (sinh -0.3))"))).isCloseTo(Math.sinh(-0.3), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (sinh 2.0))"))).isCloseTo(Math.sinh(2), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (sinh -2.0))"))).isCloseTo(Math.sinh(-2), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (cosh 2.0))"))).isCloseTo(Math.cosh(2), within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (cosh -1.0))"))).isCloseTo(Math.cosh(-1), within(1e-5));
		// The IEEE edges: out-of-domain asin/acos and NaN map to NaN; infinities.
		assertThat(compileAndRun("(print (asin 1.5))")).isEqualTo("NaN");
		assertThat(compileAndRun("(print (acos -1.5))")).isEqualTo("NaN");
		assertThat(compileAndRun("(print (asin (/ 1.0 0.0)))")).isEqualTo("NaN");
		assertThat(compileAndRun("(print (atan (/ 0.0 0.0)))")).isEqualTo("NaN");
		assertThat(compileAndRun("(print (sinh (/ 0.0 0.0)))")).isEqualTo("NaN");
		assertThat(compileAndRun("(print (sinh (/ 1.0 0.0)))")).isEqualTo("Infinity");
		assertThat(compileAndRun("(print (sinh (/ -1.0 0.0)))")).isEqualTo("-Infinity");
		assertThat(compileAndRun("(print (cosh (/ -1.0 0.0)))")).isEqualTo("Infinity");
		assertThat(compileAndRun("(print (sinh 800.0))")).isEqualTo("Infinity");
		// First-class values over integer arguments (the wrappers left
		// WASM_UNSUPPORTED, which is now empty).
		assertThat(Double.parseDouble(compileAndRun("(print (funcall #'atan 1))"))).isCloseTo(Math.atan(1),
				within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (funcall #'asin 1))"))).isCloseTo(Math.PI / 2,
				within(1e-5));
		assertThat(Double.parseDouble(compileAndRun("(print (funcall #'cosh 1))"))).isCloseTo(Math.cosh(1),
				within(1e-5));
	}

	@Test
	void packageVar() throws Exception {
		assertThat(compileAndRun("(print *package*)")).isEqualTo("CL-USER");
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
			.isEqualTo("\"hello, world\"\n(2 4 6)\n42\n(MYPKG::HELPER MYPKG:GREET MYPKG:TWICE)");
	}

	@Test
	void listMacros() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-macros))")).isEqualTo(
				"(AND ASSERT BLOCK CASE CCASE CERROR CHANGE-CLASS CHECK-TYPE COMPLEMENT COMPLEX COND DECF DECLAIM DECLARE DEFINE-COMPILER-MACRO DEFINE-CONDITION DEFINE-MODIFY-MACRO DEFINE-SETF-EXPANDER DEFSETF DEFTYPE DESTRUCTURING-BIND DO DO* DO-EXTERNAL-SYMBOLS DOCUMENTATION DOLIST DOTIMES ECASE ERROR ETYPECASE EVAL-WHEN FLET FORMAT HANDLER-BIND HANDLER-CASE IGNORE-ERRORS INCF LABELS LET* LOAD-TIME-VALUE LOCALLY LOOP MACROLET MAKE-CONDITION MAKE-INSTANCE MAKE-SEQUENCE MULTIPLE-VALUE-BIND MULTIPLE-VALUE-CALL MULTIPLE-VALUE-LIST MULTIPLE-VALUE-PROG1 MULTIPLE-VALUE-SETQ NTH-VALUE OR POP PRINT-UNREADABLE-OBJECT PROCLAIM PROG PROG* PROG1 PROG2 PSETF PSETQ PUSH PUSHNEW REMF RESTART-CASE RETURN-FROM ROTATEF SETF SHIFTF SIGNAL SLOT-BOUNDP SLOT-MAKUNBOUND SLOT-VALUE THE TIME TYPECASE TYPEP UNLESS WARN WHEN WITH-ACCESSORS WITH-INPUT-FROM-STRING WITH-OPEN-FILE WITH-OPEN-STREAM WITH-OUTPUT-TO-STRING WITH-PACKAGE-ITERATOR WITH-SLOTS WITH-STANDARD-IO-SYNTAX WRITE-CHAR)");
	}

	@Test
	void listSpecialForms() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-special-forms))")).isEqualTo(
				"(CATCH DEFCLASS DEFCONSTANT DEFGENERIC DEFMACRO DEFMETHOD DEFPACKAGE DEFPARAMETER DEFSTRUCT DEFUN DEFVAR FUNCTION GO IF IN-PACKAGE LAMBDA LET PROGN PROGV QUOTE RETURN SETQ TAGBODY THROW UNWIND-PROTECT WHILE)");
	}

	@Test
	void listFunctionsLength() throws Exception {
		assertThat(compileAndRun("(print (length (rontolisp:list-functions)))")).isEqualTo("329");
	}

	@Test
	void gensymReturnsFreshSymbols() throws Exception {
		assertThat(compileAndRun("(print (gensym)) (print (gensym \"tmp\")) (print (eq (gensym) (gensym)))"
				+ "(print (symbolp (gensym))) (print (symbolp (funcall #'gensym)))"))
			.isEqualTo("#:g1\n#:tmp2\nNIL\nT\nT");
	}

	@Test
	void gensymAcceptsAComputedPrefix() throws Exception {
		// A computed prefix lowers to string construction over the literal-prefix
		// gensym, so the printed name matches the interpreter's ("#:<prefix><n>").
		assertThat(compileAndRun("(setq p \"tmp\") (print (gensym p)) (print (gensym p))")).isEqualTo("#:tmp1\n#:tmp2");
	}

	@Test
	void symbolNameStripsThePackageMarker() throws Exception {
		assertThat(compileAndRun("(print (symbol-name 'foo)) (print (symbol-name :bar))"
				+ "(print (symbol-name (gensym))) (print (symbol-name nil))"
				+ "(print (funcall #'symbol-name 'xyz)) (print (mapcar #'symbol-name '(a b)))"))
			.isEqualTo("\"FOO\"\n\"BAR\"\n\"g1\"\n\"NIL\"\n\"XYZ\"\n(\"A\" \"B\")");
	}

	@Test
	void stringCoercesDesignatorsToStrings() throws Exception {
		assertThat(compileAndRun("(print (string \"foo\")) (print (string 'foo)) (print (string :bar))"
				+ "(print (string #\\a)) (print (string t)) (print (string nil))"
				+ "(print (funcall #'string 'xyz)) (print (mapcar #'string '(a b)))"))
			.isEqualTo("\"foo\"\n\"FOO\"\n\"BAR\"\n\"a\"\n\"T\"\n\"NIL\"\n\"XYZ\"\n(\"A\" \"B\")");
	}

	@Test
	void internReturnsCanonicalSymbols() throws Exception {
		// the interned symbol's offset is canonicalized through _intern, so eq against
		// a literal and env lookups (symbol-value below) both work
		assertThat(compileAndRun("(print (intern \"hello\")) (print (eq (intern \"FOO\") 'foo))"
				+ "(print (symbolp (intern \"hello\"))) (print (intern (symbol-name 'round-trip)))"
				+ "(print (funcall #'intern \"abc\"))"))
			.isEqualTo("hello\nT\nT\nROUND-TRIP\nabc");
	}

	@Test
	void internWithARuntimePackageArgumentSignalsAtCallTime() throws Exception {
		// Interning into a designated package needs the resolver's package state, which
		// only the interpreter carries at run time: the call COMPILES (so a library
		// merely containing the form builds) and signals when reached. WASM cannot
		// catch a signal, so the compile-level check is what this pins.
		assertThat(new WasmLispCompiler().compile(LispReader.readAllFromString("(print (intern \"foo\" :cl-user))")))
			.isNotEmpty();
	}

	@Test
	void makeSymbolReturnsAFreshUninternedSymbol() throws Exception {
		assertThat(compileAndRun("(print (make-symbol \"temp\")) (print (symbolp (make-symbol \"temp\")))"
				+ "(print (eq (make-symbol \"foo\") 'foo)) (print (funcall #'make-symbol \"m\"))"))
			.isEqualTo("#:temp\nT\nNIL\n#:m");
	}

	@Test
	void findSymbolIsVerbatim() throws Exception {
		// Uppercase-canonical: find-symbol looks up its literal argument VERBATIM (no
		// fold),
		// like CL, so a lowercase "car"/"cond" names no standard symbol and returns nil;
		// a
		// name matching a defun's upcased spelling ("FS-FN") is found.
		assertThat(compileAndRun("(print (find-symbol \"car\")) (print (find-symbol \"cond\"))"
				+ "(print (find-symbol \"no-such-name\")) (defun fs-fn (x) x) (print (find-symbol \"FS-FN\"))"))
			.isEqualTo("NIL\nNIL\nNIL\nFS-FN");
	}

	@Test
	void findSymbolWithAComputedArgumentInterns() throws Exception {
		// A computed name cannot be folded, so it lowers to intern: a symbol IS its
		// canonical spelling here, and "already interned" is knowledge only an intern
		// table could hold (.kb/symbol-runtime-api.md).
		assertThat(compileAndRun(
				"(setq n \"CAR\") (print (find-symbol n))" + "(setq m \"NO-SUCH-NAME\") (print (find-symbol m))"))
			.isEqualTo("CAR\nNO-SUCH-NAME");
	}

	@Test
	void boundpChecksTheGlobalVariableNamespace() throws Exception {
		assertThat(compileAndRun("(defvar *bp-var* 1) (print (boundp '*bp-var*)) (print (boundp '*bp-nope*))"
				+ "(print (boundp :kw)) (print (boundp t)) (print (boundp nil))"
				+ "(let ((lex 1)) (print (boundp 'lex)))"))
			.isEqualTo("T\nNIL\nT\nT\nT\nNIL");
	}

	@Test
	void symbolValueReadsTheGlobalVariableNamespace() throws Exception {
		assertThat(compileAndRun("(defvar *sv-var* 42) (print (symbol-value '*sv-var*))"
				+ "(setq *sv-var2* 7) (print (symbol-value (intern \"*SV-VAR2*\")))"
				+ "(print (symbol-value :kw)) (print (symbol-value t)) (print (symbol-value nil))"))
			.isEqualTo("42\n7\n:KW\nT\nNIL");
	}

	@Test
	void fboundpChecksFunctionsMacrosAndSpecialForms() throws Exception {
		// literal arguments resolve at compile time (macros/special forms included);
		// computed arguments probe the runtime _fenv/_lookup registries (functions only).
		// Uppercase-canonical: (intern "car") is the verbatim lowercase symbol "car" (not
		// the standard CAR), so it is not fbound -- the CL answer.
		assertThat(compileAndRun("(print (fboundp 'car)) (print (fboundp 'cond)) (print (fboundp 'defun))"
				+ "(print (fboundp 'cadr)) (print (fboundp 'no-such-fn))"
				+ "(defun fb-fn (x) x) (print (fboundp 'fb-fn))"
				+ "(print (fboundp (intern \"FB-FN\"))) (print (fboundp (intern \"car\")))"
				+ "(print (fboundp (intern \"nothing\")))"))
			.isEqualTo("T\nT\nT\nT\nNIL\nT\nT\nNIL\nNIL");
	}

	@Test
	void fmakunboundMakesTheNameUndefinedAgain() throws Exception {
		// The tombstone in GLOBAL_FENV shadows the compiled-function registry, so a
		// retired name answers nil at a LITERAL fboundp too (that fold is emitted behind
		// the probe whenever the program calls fmakunbound); an unknown name is a no-op.
		assertThat(compileAndRun("(defun fmk-fn (x) x) (print (fboundp 'fmk-fn))"
				+ "(print (fmakunbound 'fmk-fn)) (print (fboundp 'fmk-fn))"
				+ "(print (fboundp (intern \"FMK-FN\"))) (print (fmakunbound 'fmk-never-defined))"))
			.isEqualTo("T\nFMK-FN\nNIL\nNIL\nFMK-NEVER-DEFINED");
	}

	@Test
	void findPackageAnswersNilForAPackageThatDoesNotExist() throws Exception {
		assertThat(compileAndRun("(print (find-package :simple-date))"
				+ "(defun probe (p) (find-package p)) (print (probe :simple-date)) (print (probe nil))"
				+ "(print (probe :cl)) (print (probe :keyword))"))
			.isEqualTo("NIL\nNIL\nNIL\n:CL\n:KEYWORD");
	}

	@Test
	void findPackageWithAComputedDesignator() throws Exception {
		// A literal designator folds in PackageResolver; a computed one is answered from
		// the package table the backend bakes in from the resolver's final registry.
		assertThat(
				compileAndRun("(defpackage :mypkg (:use :cl) (:nicknames :mp))" + "(defun probe (p) (find-package p))"
						+ "(print (probe :mypkg)) (print (probe \"MP\")) (print (probe \"mypkg\"))"))
			.isEqualTo(":MYPKG\n:MYPKG\nNIL");
	}

	@Test
	void findSymbolWithAComputedPackageDesignator() throws Exception {
		// (find-symbol name pkg) with pkg in a variable: keyword/cl/cl-user need no
		// qualifier, anything else gets the external "PKG:" spelling.
		assertThat(compileAndRun("(defun fs (n p) (find-symbol n p))"
				+ "(print (fs \"FOO\" :keyword)) (print (fs \"CAR\" :cl)) (print (fs \"BAR\" nil))"))
			.isEqualTo(":FOO\nCAR\nNIL");
	}

	@Test
	void listFunctionsForClUserListsUserDefunsOnly() throws Exception {
		// Wrapper defuns injected for built-ins (car, +, ...) must not appear.
		String code = """
				(defun fib (n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
				(defun add2 (a) (+ a 2))
				(print (rontolisp:list-functions :cl-user))
				""";
		assertThat(compileAndRun(code)).isEqualTo("(ADD2 FIB)");
	}

	@Test
	void listFunctionsForRontolisp() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-functions :rontolisp))")).isEqualTo(
				"(AWAIT CATCH FETCH FINALLY HTTP-HANDLER JSON-PARSE JSON-STRINGIFY LIST-FUNCTIONS LIST-MACROS LIST-SPECIAL-FORMS MAKE-MUTEX MUTEX-ACQUIRE MUTEX-RELEASE QUERY-PARAM QUERY-PARAMS RANDOM-BYTES TCP-ACCEPT TCP-CONNECT TCP-LISTEN TCP-LOCAL-ADDRESS TCP-LOCAL-PORT TCP-PEER-ADDRESS TCP-PEER-PORT THEN THEN* TLS-CONNECT TLS-LISTEN TLS-LISTEN-PEM URL-DECODE URL-ENCODE URL-PATH URL-QUERY VERSION WIT-ERROR-PAYLOAD WIT-PROVIDE)");
		assertThat(compileAndRun("(print (rontolisp:list-special-forms :cl-user))")).isEqualTo("NIL");
	}

	@Test
	void listFunctionsForJava() throws Exception {
		assertThat(compileAndRun("(print (rontolisp:list-functions :java))"))
			.isEqualTo("(CALL FIELD NEW PROXY STATIC)");
		assertThat(compileAndRun("(print (rontolisp:list-macros :java))")).isEqualTo("NIL");
	}

	@Test
	void listFunctionsUnknownPackageIsRejected() {
		assertThatThrownBy(() -> new WasmLispCompiler()
			.compile(LispReader.readAllFromString("(print (rontolisp:list-functions :foo))")))
			.isInstanceOf(am.ik.rontolisp.LispPackageException.class)
			.hasMessageContaining("No such package: FOO");
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
		// await is the one generic asynchronous operation that runs in Preview 1 too
		// (only fetch itself is component-only): a non-future passes through unchanged.
		// The promise-era promisep was deleted in the async/await redesign; the new
		// future-as-value combinators (rontolisp:then/then*/catch/finally, added on
		// this shape) are exercised through their own p1 / component tests below.
		assertThat(compileAndRun("(print (rontolisp:await 42)) (print (rontolisp:await nil))")).isEqualTo("42\nNIL");
		assertThatThrownBy(() -> compileAndRun("(rontolisp:promisep 42)"))
			.hasMessageContaining("not external in the RONTOLISP package");
	}

	@Test
	void asyncDefunRunsDegenerateSynchronousInPreview1Mode() throws Exception {
		// Preview 1 has no asynchronous host I/O: an async-defun body runs to
		// completion at the call and its value comes back as a settled future --
		// same surface, degenerate timing (eager start trivially holds).
		assertThat(compileAndRun("""
				(rontolisp:async-defun add (a b) (print "in") (+ a b))
				(print "before")
				(let ((f (add 1 2)))
				  (print "after")
				  (print (rontolisp:futurep f))
				  (print (rontolisp:await f))
				  (print (rontolisp:await f))
				  (print f))
				(rontolisp:async-defun inner () 10)
				(rontolisp:async-defun outer () (+ (rontolisp:await (inner)) 1))
				(print (rontolisp:await (outer)))
				(print (rontolisp:await (funcall (rontolisp:async-lambda (x) (* x 2)) 21)))
				""")).isEqualTo("\"before\"\n\"in\"\n\"after\"\nT\n3\n3\n#<FUTURE>\n11\n42");
	}

	@Test
	void componentAsyncDefunCompilesAsStateMachine() throws Exception {
		// --component compiles async-defun/async-lambda/await as entry+resume state
		// machines over first-class TYPE_FUTUREs. The eager subset (everything settles
		// immediately) must match the interpreter/JVM/Preview-1 outputs verbatim --
		// including the error-at-await re-signal, which the P1 degenerate path can only
		// approximate.
		assertThat(compileAndRunComponent("""
				(rontolisp:async-defun add (a b) (print "in") (+ a b))
				(print "before")
				(let ((f (add 1 2)))
				  (print "after")
				  (print (rontolisp:futurep f))
				  (print (rontolisp:await f))
				  (print (rontolisp:await f))
				  (print f))
				(rontolisp:async-defun inner () 10)
				(rontolisp:async-defun outer () (+ (rontolisp:await (inner)) 1))
				(print (rontolisp:await (outer)))
				(print (rontolisp:await (funcall (rontolisp:async-lambda (x) (* x 2)) 21)))
				""")).isEqualTo("\"before\"\n\"in\"\n\"after\"\nT\n3\n3\n#<FUTURE>\n11\n42");
	}

	@Test
	void componentAsyncWrapperCompilesThroughTheStateMachines() throws Exception {
		// The (rontolisp:async (defun ...)) / (rontolisp:async (lambda ...)) wrapper is
		// a pure frontend rewrite: the component state machines see only the canonical
		// async-defun/async-lambda forms, real suspension included.
		assertThat(compileAndRunComponent("""
				(defvar *f* (rontolisp::%future-new))
				(rontolisp:async (defun task ()
				  (print 1)
				  (+ (rontolisp:await *f*) 1)))
				(defvar *tf* (task))
				(print 2)
				(rontolisp::%future-settle *f* 40)
				(print (rontolisp:await *tf*))
				(print (rontolisp:await (funcall (rontolisp:async (lambda (x) (* x 2))) 21)))
				""")).isEqualTo("1\n2\n41\n42");
	}

	@Test
	void componentRejectedAwaitResignalsMemoizedCondition() throws Exception {
		// An errored async body rejects its future at the entry's catch; the condition
		// re-signals AT AWAIT (memoized, however often it is awaited) -- the
		// interpreter/JVM contract, now exact on the component state machines.
		assertThat(compileAndRunComponent("""
				(rontolisp:async-defun boom () (error "kaboom") 1)
				(defvar *bf* (boom))
				(print :not-yet)
				(print (handler-case (rontolisp:await *bf*) (error (e) :caught)))
				(print (handler-case (rontolisp:await *bf*) (error (e) :caught-again)))
				""")).isEqualTo(":NOT-YET\n:CAUGHT\n:CAUGHT-AGAIN");
	}

	@Test
	void componentAsyncSuspensionResumesOnSettle() throws Exception {
		// A REAL suspension: the await of a pending future spills the frame, returns,
		// and the later settle wakes the waiter, restores the locals and cascades the
		// completion into the suspended function's own future. The internal
		// %future-new/%future-settle test primitives stand in for the Phase-8 import
		// layer as the pending-future source.
		assertThat(compileAndRunComponent("""
				(defvar *f* (rontolisp::%future-new))
				(rontolisp:async-defun task ()
				  (print 1)
				  (let ((v (rontolisp:await *f*)))
				    (print v)
				    (print 3)
				    99))
				(defvar *tf* (task))
				(print 2)
				(rontolisp::%future-settle *f* 7)
				(print (rontolisp:await *tf*))
				""")).isEqualTo("1\n2\n7\n3\n99");
	}

	@Test
	void componentHandlerCaseCatchesAcrossSuspension() throws Exception {
		// An await inside a handler-case protected region suspends (undoing the
		// handler-depth increment); the resume re-enters the try_table from the top, so
		// a rejection delivered AFTER the suspension is caught by the re-armed handler.
		assertThat(compileAndRunComponent("""
				(defvar *f* (rontolisp::%future-new))
				(rontolisp:async-defun task ()
				  (handler-case (progn (print 10) (rontolisp:await *f*) :no)
				    (error (e) (print :in-clause) 5)))
				(defvar *tf* (task))
				(print 20)
				(rontolisp::%future-reject *f* "boom")
				(print (rontolisp:await *tf*))
				""")).isEqualTo("10\n20\n:IN-CLAUSE\n5");
	}

	@Test
	void componentUnwindProtectSkipsAndReArmsCleanupAcrossSuspension() throws Exception {
		// A suspension is a plain return out of the protected region -- the cleanup
		// does NOT run at the suspend (the task is not exiting) and re-arms on
		// re-entry, firing once on the resumed completion.
		assertThat(compileAndRunComponent("""
				(defvar *u* (rontolisp::%future-new))
				(rontolisp:async-defun up ()
				  (unwind-protect (progn (print 30) (rontolisp:await *u*) (print 31))
				    (print :cleanup)))
				(defvar *uf* (up))
				(print 32)
				(rontolisp::%future-settle *u* 0)
				(rontolisp:await *uf*)
				""")).isEqualTo("30\n32\n31\n:CLEANUP");
	}

	@Test
	void componentAsyncLambdaCapturesAndArgumentHoistAcrossSuspension() throws Exception {
		// An async-lambda's captures live in the frame across a suspension, and an
		// await in a strict call's argument position rides the let* hoist
		// (WasmAwaitNormalizer).
		assertThat(compileAndRunComponent("""
				(defvar *g* (rontolisp::%future-new))
				(defvar *lam* (let ((n 100)) (rontolisp:async-lambda (x) (+ x n (rontolisp:await *g*)))))
				(defvar *lf* (funcall *lam* 1))
				(print 40)
				(rontolisp::%future-settle *g* 10)
				(print (rontolisp:await *lf*))
				""")).isEqualTo("40\n111");
	}

	@Test
	void componentLoopAndWhileTestAwaitsResume() throws Exception {
		// Awaits inside loop bodies (a setq value) and inside a while TEST both
		// dispatch correctly: the first iteration suspends, the settle resumes it, and
		// later iterations see the settled future immediately.
		assertThat(compileAndRunComponent("""
				(defvar *h* (rontolisp::%future-new))
				(rontolisp:async-defun looper ()
				  (let ((sum 0))
				    (dotimes (i 3) (setq sum (+ sum (rontolisp:await *h*))))
				    sum))
				(defvar *lpf* (looper))
				(print 50)
				(rontolisp::%future-settle *h* 5)
				(print (rontolisp:await *lpf*))
				(defvar *w* (rontolisp::%future-new))
				(rontolisp:async-defun wloop ()
				  (let ((n 0))
				    (while (< n (rontolisp:await *w*))
				      (setq n (+ n 1)))
				    n))
				(defvar *wf* (wloop))
				(print 60)
				(rontolisp::%future-settle *w* 3)
				(print (rontolisp:await *wf*))
				""")).isEqualTo("50\n15\n60\n3");
	}

	@Test
	void componentAsyncRestrictionsAreCompileErrors() {
		// The v1 restrictions of the component state machines, each a clear error.
		assertThatThrownBy(() -> compileAndRunComponent("""
				(rontolisp:async-defun f ()
				  (unwind-protect 1 (rontolisp:await (f))))
				(f)
				""")).hasMessageContaining("UNWIND-PROTECT cleanup form is not supported");
		assertThatThrownBy(() -> compileAndRunComponent("""
				(rontolisp:async-defun f ()
				  (handler-case 1 (error (e) (rontolisp:await (f)))))
				(f)
				""")).hasMessageContaining("clause body is not supported");
		assertThatThrownBy(() -> compileAndRunComponent("""
				(defvar *sp* 1)
				(declaim (special *sp*))
				(rontolisp:async-defun f ()
				  (let ((*sp* 2)) (rontolisp:await (f))))
				(f)
				""")).hasMessageContaining("dynamic (special) binding");
		assertThatThrownBy(() -> compileAndRunComponent("""
				(defun outer ()
				  (rontolisp:async-defun nested () 1))
				(outer)
				""")).hasMessageContaining("only supported as a top-level form");
	}

	@Test
	void asyncStreamsAreACompileErrorInPreview1Mode() {
		assertThatThrownBy(() -> compileAndRun("(print (rontolisp:make-stream))"))
			.hasMessageContaining("guest-created streams are not available on the WASM backends yet");
		assertThatThrownBy(() -> compileAndRun("(print (rontolisp:stream-read 1))"))
			.hasMessageContaining("requires the interpreter, the JVM backend or an asynchronous --component program");
		assertThatThrownBy(() -> compileAndRun("(defun bad () (rontolisp:await 1))"))
			.hasMessageContaining("only allowed inside");
	}

	@Test
	void waitForIsACompileErrorInPreview1Mode() {
		assertThatThrownBy(() -> compileAndRun("(print (rontolisp:await (rontolisp:wait-for 10)))"))
			.hasMessageContaining("rontolisp:WAIT-FOR requires the interpreter, the JVM backend or --component");
	}

	// rontolisp:wait-for on the --component path is the wait.lisp shim over the
	// wit-imported wasi:clocks/monotonic-clock, spliced by WaitForLibrary in the CLI
	// front-end; a raw compiler does not splice it, so wait-for programs run the same
	// library pass the CLI does.
	private static String compileAndRunWaitForComponent(String lispCode) throws Exception {
		List<LispVal> forms = am.ik.rontolisp.eval.WaitForLibrary.process(LispReader.readAllFromString(lispCode),
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT);
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(forms);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("test.component.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
				path("test.component.wasm"));
		assertThat(result.getExitCode()).as("exit code for component: %s\nstderr: %s", lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	@Test
	void componentWaitForSettlesToNil() throws Exception {
		// wait-for lowers to the block's async wasi:clocks monotonic-clock wait-for: a
		// pending TYPE_FUTURE the scheduler settles when the timer subtask returns.
		assertThat(compileAndRunWaitForComponent("""
				(print (rontolisp:await (rontolisp:wait-for 20)))
				(print (rontolisp:futurep (rontolisp:wait-for 0)))
				(print 'done)
				""")).isEqualTo("NIL\nT\nDONE");
	}

	@Test
	void componentWaitForTimersGenuinelyOverlap() throws Exception {
		// Two async bodies suspended on timers resolve in DELAY order, not start order
		// (the interpreter/JVM contract): the scheduler parks on the task's
		// waitable-set and each EVENT_SUBTASK wakes the frame whose timer fired.
		assertThat(compileAndRunWaitForComponent("""
				(rontolisp:async-defun slow () (rontolisp:await (rontolisp:wait-for 300)) (print 'slow))
				(rontolisp:async-defun quick () (rontolisp:await (rontolisp:wait-for 50)) (print 'quick))
				(let ((a (slow)) (b (quick)))
				  (rontolisp:await a)
				  (rontolisp:await b))
				(print 'end)
				""")).isEqualTo("QUICK\nSLOW\nEND");
	}

	@Test
	void componentWaitForRejectsNegativeAndNonInteger() throws Exception {
		assertThat(compileAndRunWaitForComponent("""
				(print (handler-case (rontolisp:await (rontolisp:wait-for -5)) (error (e) 'caught)))
				(print (handler-case (rontolisp:await (rontolisp:wait-for "x")) (error (e) 'caught)))
				""")).isEqualTo("CAUGHT\nCAUGHT");
	}

	// ---------- Future-as-value combinators: then / then* / catch / finally ----------
	//
	// The combinators are LispPreludeLibrary defuns that expand to async-lambda + await
	// + handler-case + unwind-protect, so each backend must run its own splice AND flip
	// EH mode (the WASM compiler auto-detects the head symbols and produces the tag
	// section, so wasmtime needs `-W exceptions=y` on both variants).
	private static String compileAndRunCombinatorsP1(String lispCode) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode));
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc", "-W", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	private static String compileAndRunCombinatorsComponent(String lispCode) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode));
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("test.component.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
				path("test.component.wasm"));
		assertThat(result.getExitCode()).as("exit code for component: %s\nstderr: %s", lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	@Test
	void p1ThenChainsOnFutureSettledValue() throws Exception {
		assertThat(compileAndRunCombinatorsP1("""
				(rontolisp:async-defun produce () 21)
				(print (rontolisp:await (rontolisp:then (produce) (lambda (v) (* v 2)))))
				""")).isEqualTo("42");
	}

	@Test
	void componentThenChainsOnFutureSettledValue() throws Exception {
		assertThat(compileAndRunCombinatorsComponent("""
				(rontolisp:async-defun produce () 21)
				(print (rontolisp:await (rontolisp:then (produce) (lambda (v) (* v 2)))))
				""")).isEqualTo("42");
	}

	@Test
	void p1ThenStarVariadicChainsAcrossStages() throws Exception {
		assertThat(compileAndRunCombinatorsP1("""
				(rontolisp:async-defun produce () 40)
				(print (rontolisp:await (rontolisp:then* (produce) #'1+ #'1+)))
				""")).isEqualTo("42");
	}

	@Test
	void componentThenStarVariadicChainsAcrossStages() throws Exception {
		assertThat(compileAndRunCombinatorsComponent("""
				(rontolisp:async-defun produce () 40)
				(print (rontolisp:await (rontolisp:then* (produce) #'1+ #'1+)))
				""")).isEqualTo("42");
	}

	@Test
	void p1CatchPassesUpstreamValueThroughOnSuccess() throws Exception {
		// P1 is degenerate-synchronous: an errored async body signals AT THE CALL, not
		// at await (per .kb/async-await.md), so an error-path test cannot pass here --
		// the error escapes past (catch ...) before catch's own body runs. The
		// success-path is byte-identical; the full error-channel semantics are pinned
		// on --component below.
		assertThat(compileAndRunCombinatorsP1("""
				(rontolisp:async-defun ok () 99)
				(print (rontolisp:await
				         (rontolisp:catch (ok) (lambda (c) (declare (ignore c)) :should-not-see))))
				""")).isEqualTo("99");
	}

	@Test
	void componentCatchRunsOnlyWhenUpstreamSignals() throws Exception {
		assertThat(compileAndRunCombinatorsComponent("""
				(rontolisp:async-defun boom () (error "nope"))
				(rontolisp:async-defun ok () 99)
				(print (rontolisp:await
				         (rontolisp:catch (boom) (lambda (c) (declare (ignore c)) :fallback))))
				(print (rontolisp:await
				         (rontolisp:catch (ok) (lambda (c) (declare (ignore c)) :should-not-see))))
				""")).isEqualTo(":FALLBACK\n99");
	}

	@Test
	void p1FinallyRunsOnSuccessPathAndPreservesValue() throws Exception {
		// P1 divergence again: the error-arm of finally would require the futured
		// error-at-await contract; the success arm is byte-identical.
		assertThat(compileAndRunCombinatorsP1("""
				(defvar *finlog-p1* nil)
				(rontolisp:async-defun ok () 5)
				(print (rontolisp:await
				         (rontolisp:finally (ok) (lambda () (push :ok-cleanup *finlog-p1*)))))
				(print (reverse *finlog-p1*))
				""")).isEqualTo("5\n(:OK-CLEANUP)");
	}

	@Test
	void componentFinallyRunsOnBothPathsAndPreservesOutcome() throws Exception {
		assertThat(compileAndRunCombinatorsComponent("""
				(defvar *finlog* nil)
				(rontolisp:async-defun ok () 5)
				(rontolisp:async-defun boom () (error "detonate"))
				(print (rontolisp:await
				         (rontolisp:finally (ok) (lambda () (push :ok-cleanup *finlog*)))))
				(print (handler-case
				         (rontolisp:await
				           (rontolisp:finally (boom) (lambda () (push :err-cleanup *finlog*))))
				         (error (e) (simple-condition-format-control e))))
				(print (reverse *finlog*))
				""")).isEqualTo("5\n\"detonate\"\n(:OK-CLEANUP :ERR-CLEANUP)");
	}

	@Test
	void componentFetchUnreachableSignalsAtAwait() throws Exception {
		// A fetch component requires wasi:http (so it must be run with -S http=y) and a
		// failed request unifies on the condition system: fetching an unreachable port
		// exercises the full async-send/waitable-set/result-lift path, and the
		// connection error (the send result's error arm) signals rontolisp:wit-error at
		// AWAIT time -- exactly like the interpreter and the JVM. The 0.2-era
		// nil-on-failure convention is gone with the wasi:http@0.3 cutover.
		String program = "(print (handler-case (rontolisp:await (rontolisp:fetch \"http://127.0.0.1:1/nope\"))"
				+ " (rontolisp:wit-error () :refused)))";
		byte[] componentBytes = compileFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/fetch-err.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S",
				"http=y", "/tmp/fetch-err.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo(":REFUSED");
	}

	@Test
	void componentFetchRequiresHttpFlag() throws Exception {
		// Without -S http=y the wasi:http imports are unsatisfied, so a fetch component
		// fails to instantiate -- confirming non-fetch components (which do not import
		// wasi:http) keep running without the flag.
		String program = "(print (rontolisp:fetch \"http://127.0.0.1:1/nope\"))";
		byte[] componentBytes = compileFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/fetch-noflag.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
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
		byte[] componentBytes = compileFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/tcp-echo.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S",
				"tcp=y", "-S", "inherit-network=y", "/tmp/tcp-echo.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("\"hello\"\n65\nNIL");
	}

	@Test
	void componentTcpBinaryBytesAreWireTransparent() throws Exception {
		// The socket chunk cursor walks BYTES, not UTF-8 characters (todo-177's root
		// cause: binary bytes forming a valid multi-byte sequence collapsed into one
		// char and shifted the stream). Both directions are pinned: a string's UTF-8
		// bytes read back byte-by-byte through read-byte, and write-byte puts exactly
		// ONE byte on the wire (two raw bytes forming a valid 2-byte sequence decode
		// as ONE char on the peer -- under the old per-char model each write-byte
		// emitted a full UTF-8 sequence, invisible in loopback because the read side
		// decoded it back).
		String program = """
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port))
				       (server (rontolisp:tcp-accept listener)))
				  (write-string "AÇB" client)
				  (let* ((b1 (read-byte server))
				         (b2 (read-byte server))
				         (b3 (read-byte server))
				         (b4 (read-byte server)))
				    (print (list b1 b2 b3 b4)))
				  (write-byte 199 client)
				  (write-byte 184 client)
				  (print (char-code (read-char server)))
				  (close client)
				  (close server)
				  (close listener))
				""";
		byte[] componentBytes = compileFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/tcp-binary.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S",
				"tcp=y", "-S", "inherit-network=y", "/tmp/tcp-binary.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("(65 195 135 66)\n504");
	}

	@Test
	void componentTcpSequenceOpsReachTheSocketDispatch() throws Exception {
		// read-sequence / write-sequence / 3-arg read-byte on a socket handle -- the
		// cl-postgres surface -- must reach the sockets.lisp dispatch on BOTH the sync
		// path (inside a defun, the driver's shape) and the async top level (promoted
		// futures). Unrewritten they compile to the native stream built-ins, whose
		// fd_read/fd_write on a socket fd (>= 200) walks off the preview1 adapter's
		// 64-slot fd table ("unknown handle index"). The :eof value pins the
		// eof-tolerant read-byte after peer close.
		String program = """
				(defun run-sync ()
				  (let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				         (port (rontolisp:tcp-local-port listener))
				         (client (rontolisp:tcp-connect "127.0.0.1" port))
				         (server (rontolisp:tcp-accept listener))
				         (buf (make-array 4 :initial-element 0)))
				    (write-sequence (vector 1 2 250 4) client)
				    (read-sequence buf server)
				    (print (list (aref buf 0) (aref buf 1) (aref buf 2) (aref buf 3)))
				    (write-byte 65 client)
				    (print (read-byte server nil :eof))
				    (close client)
				    (print (read-byte server nil :eof))
				    (close server)
				    (close listener)))
				(run-sync)
				(let* ((l2 (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (c2 (rontolisp:tcp-connect "127.0.0.1" (rontolisp:tcp-local-port l2)))
				       (s2 (rontolisp:tcp-accept l2))
				       (buf2 (make-array 2 :initial-element 0)))
				  (write-sequence (vector 7 200) c2)
				  (read-sequence buf2 s2)
				  (print (list (aref buf2 0) (aref buf2 1)))
				  (close c2)
				  (print (read-byte s2 nil :eof))
				  (close s2)
				  (close l2))
				""";
		byte[] componentBytes = compileFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/tcp-seq.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S",
				"tcp=y", "-S", "inherit-network=y", "/tmp/tcp-seq.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("(1 2 250 4)\n65\n:EOF\n(7 200)\n:EOF");
	}

	@Test
	void componentUsocketEchoOverLoopback() throws Exception {
		// The usocket shim (usocket.lisp, spliced by UsocketLibrary.process like the
		// CLI pre-pass) over the same loopback choreography as componentTcpLoopbackEcho.
		// The address accessors are REAL on the component now (sockets.lisp reads
		// get-remote-address), so the peer address prints like the interpreter/JVM.
		String program = """
				(let* ((listener (usocket:socket-listen "127.0.0.1" usocket:*auto-port*))
				       (port (usocket:get-local-port listener))
				       (client (usocket:socket-connect "127.0.0.1" port :element-type '(unsigned-byte 8))))
				  (write-line "hello" (usocket:socket-stream client))
				  (let* ((server (usocket:socket-accept listener))
				         (line (read-line (usocket:socket-stream server))))
				    (print line)
				    (print (usocket:get-peer-address server))
				    (usocket:socket-close server)
				    (usocket:socket-close client)
				    (usocket:socket-close listener)))
				""";
		List<LispVal> spliced = am.ik.rontolisp.eval.WitLibrary
			.process(
					am.ik.rontolisp.eval.StdinLibrary
						.process(
								am.ik.rontolisp.eval.SocketsLibrary.process(
										am.ik.rontolisp.eval.UsocketLibrary
											.process(LispReader.readAllFromString(program)),
										am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT),
								am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT, false));
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(spliced);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/usocket-echo.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S",
				"tcp=y", "-S", "inherit-network=y", "/tmp/usocket-echo.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("\"hello\"\n\"127.0.0.1\"");
	}

	@Test
	void componentAsyncStdinReadDoesNotStallTheInstance() throws Exception {
		// The stdin migration's promotion goal: a pending stdin read in an async body
		// suspends the task, so a concurrent 100ms timer fires BEFORE the line the
		// pipe delivers much later. The preview1 adapter's blocking stdin branch
		// could never do this -- it parks the whole instance.
		//
		// The pipe delay must comfortably exceed wasmtime's cold-start: `sleep` starts
		// with the subshell, in parallel with wasmtime launch, so any startup latency
		// (heavier under a loaded/shared-container CI runner) eats into the margin. Too
		// tight and the line is already buffered by the time the body reaches read-line,
		// inverting the order ("hello" before "timer fired"). 2s vs a 100ms timer keeps
		// a ~1.9s cushion while still proving the timer wins.
		String program = """
				(rontolisp:async-defun reader ()
				  (print (read-line)))
				(rontolisp:async-defun timer ()
				  (rontolisp:await (rontolisp:wait-for 100))
				  (print "timer fired"))
				(let ((r (reader)) (tm (timer)))
				  (rontolisp:await tm)
				  (rontolisp:await r))
				""";
		byte[] componentBytes = compileFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/stdin-async.component.wasm");
		ExecResult result = wasmtime.execInContainer("sh", "-c",
				"(sleep 2; echo hello) | wasmtime run -W gc=y -W exceptions=y /tmp/stdin-async.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("\"timer fired\"\n\"hello\"");
	}

	@Test
	void componentAsyncStdinEchoesLinesUntilEof() throws Exception {
		// The chunk-buffered stdin machinery preserves line semantics and the
		// nil-at-EOF contract across a multi-line pipe, matching the interpreter.
		String program = """
				(rontolisp:async-defun main ()
				  (let ((done nil))
				    (while (not done)
				      (let ((line (read-line)))
				        (if line (print line) (setq done t))))))
				(rontolisp:await (main))
				""";
		byte[] componentBytes = compileFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/stdin-echo.component.wasm");
		ExecResult result = wasmtime.execInContainer("sh", "-c",
				"printf 'alpha\\nbeta\\ngamma\\n' | wasmtime run -W gc=y -W exceptions=y /tmp/stdin-echo.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("\"alpha\"\n\"beta\"\n\"gamma\"");
	}

	@Test
	void componentNonAsyncStdinKeepsTheAdapterPathAndItsFlags() throws Exception {
		// The byte-stability contract at run level: a synchronous stdin program is
		// NOT migrated -- it keeps the preview1 adapter's stdin branch, so it still
		// runs WITHOUT -W exceptions=y.
		String program = "(print (read-line))";
		byte[] componentBytes = compileFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/stdin-sync.component.wasm");
		ExecResult result = wasmtime.execInContainer("sh", "-c",
				"echo plain | wasmtime run -W gc=y /tmp/stdin-sync.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("\"plain\"");
	}

	@Test
	void componentTcpProgramReadsStdinThroughTheSameDispatch() throws Exception {
		// sockets.lisp + stdin.lisp in one component: a socket read and a stdin read
		// flow through the same %io dispatch, each reaching its own wit-imported
		// stream.
		String program = """
				(rontolisp:async-defun main ()
				  (let* ((listener (rontolisp:tcp-listen 0))
				         (port (rontolisp:tcp-local-port listener))
				         (client (rontolisp:tcp-connect "127.0.0.1" port))
				         (server (rontolisp:tcp-accept listener)))
				    (write-line "from-socket" client)
				    (close client)
				    (print (read-line server))
				    (print (read-line))
				    (close server)
				    (close listener)))
				(rontolisp:await (main))
				""";
		byte[] componentBytes = compileFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/tcp-stdin.component.wasm");
		ExecResult result = wasmtime.execInContainer("sh", "-c", "echo from-stdin | wasmtime run -W gc=y "
				+ "-W exceptions=y -S tcp=y -S inherit-network=y /tmp/tcp-stdin.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("\"from-socket\"\n\"from-stdin\"");
	}

	@Test
	void componentTcpConnectRefusedReturnsNil() throws Exception {
		// Connecting to a closed port returns nil instead of trapping (the fetch error
		// convention). Deterministic, no server.
		String program = "(print (rontolisp:tcp-connect \"127.0.0.1\" 1))";
		byte[] componentBytes = compileFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/tcp-refused.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S",
				"tcp=y", "-S", "inherit-network=y", "/tmp/tcp-refused.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("NIL");
	}

	@Test
	void componentTcpWithoutNetworkFlagsReturnsNil() throws Exception {
		// Unlike wasi:http (whose absence fails instantiation without -S http=y),
		// wasmtime always hosts wasi:sockets and gates it by permission: a socket
		// component still instantiates without -S tcp / -S inherit-network, and the
		// socket operations fail, so the built-ins yield nil.
		String program = "(print (rontolisp:tcp-listen 0 \"127.0.0.1\"))";
		byte[] componentBytes = compileFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/tcp-noflag.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
				"/tmp/tcp-noflag.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("NIL");
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
					+ " (print (getf r :status))"
					+ " (print (rontolisp:await (rontolisp:read-all (getf r :body)))) (print (getf r :headers)))"
					+ " (print (rontolisp:await (rontolisp:read-all (getf (rontolisp:await (rontolisp:fetch \"" + url
					+ "\" (list :headers (list (cons \"X-Custom\" \"abc\"))))) :body))))"
					+ " (print (rontolisp:await (rontolisp:read-all (getf (rontolisp:await (rontolisp:fetch \"" + echo
					+ "\" (list :method \"POST\" :body \"hi\"))) :body))))"
					// two futures in flight at once, awaited out of order; p1 is awaited
					// twice (the second await must come from the settled future --
					// wasi:http hands out the response only once)
					+ " (let ((p1 (rontolisp:fetch \"" + echo + "\" (list :method \"POST\" :body \"one\")))"
					+ "       (p2 (rontolisp:fetch \"" + echo + "\" (list :method \"POST\" :body \"two\"))))"
					+ "   (print (rontolisp:await (rontolisp:read-all (getf (rontolisp:await p2) :body))))"
					+ "   (print (rontolisp:await (rontolisp:read-all (getf (rontolisp:await p1) :body))))"
					+ "   (print (getf (rontolisp:await p1) :status)))";
			byte[] componentBytes = compileFetchComponent(program);
			java.nio.file.Path wasm = tempDir.resolve("fetch.component.wasm");
			java.nio.file.Files.write(wasm, componentBytes);
			Process p = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S", "http=y",
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

	@Test
	@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RONTOLISP_HTTP_E2E", matches = "1")
	void componentFetchWithRuntimeBuiltUrls(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
			throws Exception {
		// Regression: the fetch call site used to read a string's field 0 (its identity
		// id) as a linear-memory pointer, which holds the right bytes only for the FIRST
		// runtime-built string (the id counter and the heap scratch both start at
		// heapBase). With TWO runtime-built URLs the first fetch silently used the
		// second URL's bytes. The URL/body staging now copies through _str_to_mem.
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
			.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/a", exchange -> {
			byte[] body = "route-a".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.createContext("/b", exchange -> {
			byte[] body = "route-b".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			String program = "(let ((u1 (concatenate 'string \"" + base + "\" \"/a\"))"
					+ "      (u2 (concatenate 'string \"" + base + "\" \"/b\")))"
					+ "  (print (rontolisp:await (rontolisp:read-all (getf (rontolisp:await (rontolisp:fetch u1)) :body))))"
					+ "  (print (rontolisp:await (rontolisp:read-all (getf (rontolisp:await (rontolisp:fetch u2)) :body)))))"
					// a runtime-built request body must be staged too
					+ " (let ((body (concatenate 'string \"pay\" \"load\")))"
					+ "   (print (getf (rontolisp:await (rontolisp:fetch \"" + base
					+ "/a\" (list :method \"POST\" :body body))) :status)))";
			byte[] componentBytes = compileFetchComponent(program);
			java.nio.file.Path wasm = tempDir.resolve("fetch-fresh-urls.component.wasm");
			java.nio.file.Files.write(wasm, componentBytes);
			Process p = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S", "http=y",
					wasm.toString())
				.redirectErrorStream(true)
				.start();
			String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			p.waitFor();
			assertThat(out).contains("\"route-a\"").contains("\"route-b\"").contains("200");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RONTOLISP_HTTP_E2E", matches = "1")
	void componentFetchInsideAsyncExport(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
			throws Exception {
		// Tier 3 payoff: fetch inside an :async t export works under `wasmtime run
		// --invoke` (the export runs as a stackful async task, so the adapter's blocking
		// wasi:http machinery is legal; the URL argument is a runtime-built string
		// crossing the canonical string ABI).
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
			.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/ping", exchange -> {
			byte[] body = "pong".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/ping";
			String program = """
					(rontolisp:async-defun fetch-body (url)
					  (print "fetching")
					  (let* ((r (rontolisp:await (rontolisp:fetch url)))
					         (body (rontolisp:await (rontolisp:read-all (getf r :body)))))
					    body))
					(rontolisp:wasm-export 'fetch-body :params '(:string) :returns :string :async t)
					""";
			byte[] componentBytes = compileFetchComponent(program);
			java.nio.file.Path wasm = tempDir.resolve("fetch-export.component.wasm");
			java.nio.file.Files.write(wasm, componentBytes);
			Process p = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S", "http=y",
					"--invoke", "fetch-body(\"" + url + "\")", wasm.toString())
				.redirectErrorStream(true)
				.start();
			String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			p.waitFor();
			assertThat(out).contains("\"fetching\"").contains("\"pong\"");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RONTOLISP_HTTP_E2E", matches = "1")
	void componentPendingBodyReadOverlapsTimer(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
			throws Exception {
		// True intra-instance concurrency: a stream-read the host reports in flight is a
		// PENDING future settled by the scheduler's EVENT_STREAM_READ dispatch, so one
		// async body draining a slow fetch body no longer parks the whole instance --
		// another body's wait-for timer fires in between. Delay order, not start order:
		// the drain starts first but its slow chunk (800 ms) arrives after the timer
		// (200 ms), and with the old blocking read "timer-fired" could only print after
		// the drain completed.
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
			.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/slow", exchange -> {
			exchange.sendResponseHeaders(200, 0); // chunked
			exchange.getResponseBody().write("first-".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			try {
				Thread.sleep(800);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.getResponseBody().write("second".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			exchange.close();
		});
		server.start();
		try {
			String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";
			String program = """
					(rontolisp:async-defun drain (url)
					  (let* ((r (rontolisp:await (rontolisp:fetch url)))
					         (body (rontolisp:await (rontolisp:read-all (getf r :body)))))
					    (print (list 'drained body))))
					(rontolisp:async-defun timer ()
					  (rontolisp:await (rontolisp:wait-for 200))
					  (print 'timer-fired))
					(let ((a (drain "%s"))
					      (b (timer)))
					  (rontolisp:await a)
					  (rontolisp:await b)
					  (print 'end))
					""".formatted(url);
			byte[] componentBytes = compileFetchComponent(program);
			java.nio.file.Path wasm = tempDir.resolve("overlap.component.wasm");
			java.nio.file.Files.write(wasm, componentBytes);
			Process p = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S", "http=y",
					wasm.toString())
				.redirectErrorStream(true)
				.start();
			String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			p.waitFor();
			assertThat(out.trim()).isEqualTo("""
					timer-fired
					(drained "first-second")
					end""");
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
				""")).isEqualTo("#\\A\n#\\z\nT\nNIL\nT\nNIL\n7\n15\nNIL");
	}

	@Test
	void compileCharComparisonsVariadic() throws Exception {
		assertThat(compileAndRun(
				"(print (char= #\\a #\\a)) (print (char< #\\a #\\b #\\c)) (print (char<= #\\a #\\a #\\b)) (print (char< #\\b #\\a))"))
			.isEqualTo("T\nT\nT\nNIL");
	}

	@Test
	void compileCharEqualityAndFirstClass() throws Exception {
		assertThat(compileAndRun("""
				(print (eql #\\a #\\a))
				(print (equal (list #\\a #\\b) (list #\\a #\\b)))
				(print (mapcar #'char-upcase (list #\\a #\\b #\\c)))
				""")).isEqualTo("T\nT\n(#\\A #\\B #\\C)");
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
				""")).isEqualTo("42\n-13\n255\n12\nNIL");
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
			.isEqualTo("(A . 1)\n(A B . C)\n((A . 1) (B . 2))\n3.5");
	}

	// === the emitted reader's # dispatch (frontend parity) ===

	@Test
	void compileReadFromStringCharLiterals() throws Exception {
		assertThat(compileAndRun("""
				(print (read-from-string "#\\\\a"))
				(print (read-from-string "#\\\\Space"))
				(print (read-from-string (prin1-to-string #\\Newline)))
				(print (char-code (read-from-string "#\\\\A")))
				(print (read-from-string "#\\\\("))
				""")).isEqualTo("#\\a\n#\\Space\n#\\Newline\n65\n#\\(");
	}

	@Test
	void compileReadFromStringRatiosAndRadix() throws Exception {
		assertThat(compileAndRun("""
				(print (read-from-string "1/3"))
				(print (read-from-string "-2/8"))
				(print (* 3 (read-from-string "1/3")))
				(print (read-from-string "4/2"))
				(print (read-from-string "#x10"))
				(print (read-from-string "#o17"))
				(print (read-from-string "#b101"))
				(print (read-from-string "#x-ff"))
				(print (read-from-string "+347"))
				(print (read-from-string "+2.5"))
				""")).isEqualTo("1/3\n-1/4\n1\n2\n16\n15\n5\n-255\n347\n2.5");
	}

	@Test
	void compileReadFromStringVectorsAndArrays() throws Exception {
		assertThat(compileAndRun("""
				(print (read-from-string (prin1-to-string #(1 2 3))))
				(print (aref (read-from-string "#(7 8 9)") 1))
				(print (read-from-string "#2A((1 2) (3 4))"))
				(print (read-from-string "#*101"))
				(print (read-from-string (prin1-to-string #f(1.0 2.0))))
				(print (read-from-string (prin1-to-string #d((1.0 2.0) (3.0 4.0)))))
				""")).isEqualTo("#(1 2 3)\n8\n#2A((1 2) (3 4))\n#(1 0 1)\n#f(1.0 2.0)\n#d((1.0 2.0) (3.0 4.0))");
	}

	@Test
	void compileReadFromStringStructLiterals() throws Exception {
		assertThat(compileAndRun("""
				(defstruct rdpt x y)
				(print (read-from-string (prin1-to-string (make-rdpt :x 1 :y 2))))
				(print (rdpt-y (read-from-string "#S(RDPT :X 5 :Y 6)")))
				(print (read-from-string "#S(RDPT :X 5)"))
				(defstruct rdcfg (retries 3) (host "h") (tag 'none))
				(print (read-from-string "#S(RDCFG)"))
				"""))
			.isEqualTo("#S(RDPT :X 1 :Y 2)\n6\n#S(RDPT :X 5 :Y NIL)\n#S(RDCFG :RETRIES 3 :HOST \"h\" :TAG NONE)");
	}

	@Test
	void compileReadFromStringSymbolParityAndBlockComments() throws Exception {
		assertThat(compileAndRun("""
				(print (read-from-string "#foo"))
				(print (read-from-string "#|note|# 7"))
				""")).isEqualTo("#FOO\n7");
	}

	@Test
	void compileReadFromStringReaderErrorsSignalCatchably() throws Exception {
		// EH mode: every reader error is a catchable $lisp-cond throw carrying a STATIC
		// message (WASM cannot interpolate names into a baked string; the JVM and the
		// interpreter carry the frontend's exact interpolated messages instead).
		assertThat(compileAndRunEh("""
				(defstruct rdpt x y)
				(defun try (s)
				  (handler-case (progn (read-from-string s) "no-error")
				    (error (e) (simple-condition-format-control e))))
				(print (try "#\\\\Foo"))
				(print (try "#xZZ"))
				(print (try "#S(NOSUCH :X 1)"))
				(print (try "#S(RDPT :Z 1)"))
				(print (try "#2A((1 2) (3))"))
				(print (try "1/0"))
				(print (try "#.(+ 1 2)"))
				(print (try "#1=(a b)"))
				""")).isEqualTo("""
				"Unknown character name after #\\"
				"Invalid digits after #x/#o/#b"
				"#S: not a defined structure type"
				"#S: no slot with that name"
				"ragged array contents"
				"Division by zero in ratio literal"
				"#. read-time evaluation is not supported"
				"reader labels (#N=/#N#) are not supported by the compiled runtime reader"
				""".trim());
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
		assertThat(compileAndRun("(print (floatp (read-from-string \"1.0\")))")).isEqualTo("T");
		assertThat(compileAndRun("(print (= 1.2 (read-from-string \"1.2\")))")).isEqualTo("T");
		assertThat(compileAndRun("(print (+ (read-from-string \"-2.5\") (read-from-string \"1.0\")))"))
			.isEqualTo("-1.5");
		assertThat(compileAndRun("(print (read-from-string \".5\"))")).isEqualTo("0.5");
		assertThat(compileAndRun("(print (read-from-string \"5.\"))")).isEqualTo("5.0");
		// A token with two dots or non-numeric characters stays a symbol.
		assertThat(compileAndRun("(print (symbolp (read-from-string \"1.2.3\")))")).isEqualTo("T");
		assertThat(compileAndRun("(print (symbolp (read-from-string \"foo.bar\")))")).isEqualTo("T");
		// Integers are unaffected.
		assertThat(compileAndRun("(print (integerp (read-from-string \"30\")))")).isEqualTo("T");
	}

	@Test
	void compileEvalReadFloatArithmetic() throws Exception {
		// The browser "compile & run WASM" playground appends `(print (eval (read)))` and
		// feeds a call expression on stdin; float arguments must round-trip through read.
		assertThat(compileAndRunWithStdinFile("(print (eval (read)))", "(+ 1.0 2.0)\n")).isEqualTo("3.0");
		assertThat(compileAndRunWithStdinFile("(print (eval (read)))", "(< -1.2 1.2)\n")).isEqualTo("T");
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
				"(funcall ADD10 100)\n"))
			.isEqualTo("110");
	}

	@Test
	void compileParseIntegerAndReadFromStringAsValues() throws Exception {
		assertThat(compileAndRun("(print (mapcar #'parse-integer (list \"1\" \"2\" \"3\")))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (funcall #'read-from-string \"(a b c)\"))")).isEqualTo("(A B C)");
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
				""")).isEqualTo("(1 2 NIL)\n42");
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
				""")).isEqualTo("7\n(3 2)\n(2 T NIL NIL)");
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
				""")).isEqualTo("60\n(2 GONE)\n0");
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
				""")).isEqualTo("(1 (T NIL) 2)");
	}

	@Test
	void compileMakeHashTableAsFirstClassValue() throws Exception {
		assertThat(compileAndRun("""
				(let ((h (funcall #'make-hash-table)))
				  (setf (gethash 1 h) 'x)
				  (print (gethash 1 h)))
				""")).isEqualTo("X");
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
				""")).isEqualTo("#d(((10.0 11.0) (12.0 13.0)) ((14.0 15.0) (16.0 17.0)))\n28.0\nT");
	}

	@Test
	void compileLinalgAxisReductionsAndRandom() throws Exception {
		// The deep-learning-from-scratch additions: numpy axis/keepdims reductions,
		// reshape -1 inference, the seeded Wichmann-Hill RNG (bit-identical on every
		// backend), and the indexing/selection/comparison helpers. Same program and
		// expectation as the JVM compileAndRunLinalgAxisReductionsAndRandom case.
		assertThat(compileAndRunLinalg("""
				(defparameter *m* (linalg:from-list '((1 2 3) (4 5 6))))
				(print (linalg:sum *m* 0))
				(print (linalg:sum *m* -1 t))
				(print (linalg:mean *m* 0))
				(print (linalg:amax *m* 1))
				(print (linalg:amin *m* 0 t))
				(print (linalg:argmax *m* 1))
				(print (linalg:argmin *m* 0))
				(print (linalg:sum (linalg:reshape (linalg:arange 24) '(2 3 4)) 1))
				(print (linalg:shape (linalg:reshape (linalg:arange 12) '(3 -1))))
				(linalg:seed 42)
				(print (linalg:choice 60000 4))
				(linalg:seed 9)
				(print (linalg:permutation 10))
				(linalg:seed 1)
				(print (linalg:emap (lambda (x) (truncate (* 1024 x))) (linalg:rand '(2 2))))
				(linalg:seed 7)
				(print (linalg:emap (lambda (x) (truncate (* 1024 x))) (linalg:randn 4)))
				(print (linalg:take-rows *m* #(1 0)))
				(print (linalg:row *m* 1))
				(print (linalg:gather *m* #(2 0)))
				(print (linalg:one-hot #(1 0 2) 3))
				(print (linalg:greater *m* 3))
				(print (linalg:equal (linalg:argmax *m* 1) #(2 2)))
				(print (linalg:zeros-like (linalg:ones 2 'single-float)))
				""")).isEqualTo("#d(5.0 7.0 9.0)\n#d((6.0) (15.0))\n#d(2.5 3.5 4.5)\n#d(3.0 6.0)\n#d((1.0 2.0 3.0))\n"
				+ "#d(2.0 2.0)\n#d(0.0 0.0 0.0)\n#d((12.0 15.0 18.0 21.0) (48.0 51.0 54.0 57.0))\n(3 4)\n"
				+ "#d(26833.0 11120.0 29256.0 22347.0)\n#d(4.0 5.0 6.0 2.0 9.0 7.0 1.0 0.0 8.0 3.0)\n"
				+ "#d((317.0 637.0) (949.0 376.0))\n#d(284.0 -21.0 221.0 -1653.0)\n"
				+ "#d((4.0 5.0 6.0) (1.0 2.0 3.0))\n#d(4.0 5.0 6.0)\n#d(3.0 4.0)\n"
				+ "#d((0.0 1.0 0.0) (1.0 0.0 0.0) (0.0 0.0 1.0))\n#d((0.0 0.0 0.0) (1.0 1.0 1.0))\n"
				+ "#d(1.0 1.0)\n#f(0.0 0.0)");
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
		assertThat(compileAndRun("(prin1 #(a \"b\")) (terpri) (princ #(a \"b\"))")).isEqualTo("#(A \"b\")\n#(A b)");
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

	// --- packed float arrays (#f / :element-type 'double-float) -------------

	@Test
	void compilePackedFloatVectorLiteralArefAndPrint() throws Exception {
		assertThat(compileAndRun("(print (aref #d(1.0 2.5 3.0) 1))")).isEqualTo("2.5");
		assertThat(compileAndRun("(print #d(1 2 3))")).isEqualTo("#d(1.0 2.0 3.0)");
	}

	@Test
	void compilePackedFloatMatrixLiteralIntrospection() throws Exception {
		assertThat(compileAndRun("""
				(let ((m #d((1 2 3) (4 5 6))))
				  (print (list (array-rank m) (array-dimensions m) (aref m 1 2))))
				""")).isEqualTo("(2 (2 3) 6.0)");
		assertThat(compileAndRun("(print #d((1 2) (3 4)))")).isEqualTo("#d((1.0 2.0) (3.0 4.0))");
	}

	@Test
	void compilePackedFloatArefSetCoercesToDouble() throws Exception {
		assertThat(compileAndRun("""
				(let ((v #d(1.0 2.0 3.0)))
				  (setf (aref v 1) 42)
				  (print v))
				""")).isEqualTo("#d(1.0 42.0 3.0)");
	}

	@Test
	void compileMakeArrayDoubleFloatIsPacked() throws Exception {
		assertThat(compileAndRun("""
				(let ((a (make-array 3 :element-type 'double-float :initial-element 2.5)))
				  (setf (aref a 0) 9)
				  (print (list a (length a) (array-element-type a))))
				""")).isEqualTo("(#d(9.0 2.5 2.5) 3 DOUBLE-FLOAT)");
	}

	@Test
	void compileMakeArrayDoubleFloatRankTwo() throws Exception {
		assertThat(compileAndRun("""
				(let ((m (make-array (list 2 2) :element-type 'double-float)))
				  (dotimes (i 2) (dotimes (j 2) (setf (aref m i j) (+ (* i 10) j))))
				  (print m))
				""")).isEqualTo("#d((0.0 1.0) (10.0 11.0))");
	}

	@Test
	void compilePackedFloatRowMajorArefAndElementType() throws Exception {
		assertThat(compileAndRun("(print (row-major-aref #d((1 2) (3 4)) 3))")).isEqualTo("4.0");
		assertThat(compileAndRun("(print (array-element-type #d(1 2 3)))")).isEqualTo("DOUBLE-FLOAT");
	}

	@Test
	void compilePackedFloatIsAnArrayAndVector() throws Exception {
		assertThat(compileAndRun("(print (typecase #d(1 2 3) (array 'arr) (t 'no)))")).isEqualTo("ARR");
		assertThat(compileAndRun("(print (typecase #d(1 2 3) (vector 'vec) (t 'no)))")).isEqualTo("VEC");
	}

	@Test
	void compilePackedFloatDotProductLoop() throws Exception {
		assertThat(compileAndRun("""
				(defun dot (a b n)
				  (let ((s 0.0) (i 0))
				    (loop while (< i n) do
				      (setf s (+ s (* (aref a i) (aref b i)))) (setf i (+ i 1)))
				    s))
				(print (dot #d(1 2 3) #d(4 5 6) 3))
				""")).isEqualTo("32.0");
	}

	@Test
	void compilePackedFloatCoerceToList() throws Exception {
		assertThat(compileAndRun("(print (coerce #d(1 2 3) 'list))")).isEqualTo("(1.0 2.0 3.0)");
	}

	// --- packed single-float arrays (#f / :element-type 'single-float) -------
	// The same TYPE_FARRAY struct as #d, distinguished by a TYPE_F32ARR data array;
	// reads widen f32->f64, writes narrow. Cross-backend assertions use exact
	// (power-of-two / integer-valued) f32 values so the printed form is byte-identical
	// (a non-terminating f32 decimal renders differently under the WASM float printer;
	// lossy narrowing is asserted arithmetically below and byte-exactly in
	// JvmFloatArrayTest).

	@Test
	void compilePackedSingleFloatVectorLiteralArefAndPrint() throws Exception {
		assertThat(compileAndRun("(print (aref #f(1.0 2.5 3.0) 1))")).isEqualTo("2.5");
		assertThat(compileAndRun("(print #f(1 2 3))")).isEqualTo("#f(1.0 2.0 3.0)");
	}

	@Test
	void compilePackedSingleFloatMatrixLiteralIntrospection() throws Exception {
		assertThat(compileAndRun("""
				(let ((m #f((1 2 3) (4 5 6))))
				  (print (list (array-rank m) (array-dimensions m) (aref m 1 2))))
				""")).isEqualTo("(2 (2 3) 6.0)");
		assertThat(compileAndRun("(print #f((1 2) (3 4)))")).isEqualTo("#f((1.0 2.0) (3.0 4.0))");
	}

	@Test
	void compilePackedSingleFloatArefSetReturnsStoredValue() throws Exception {
		assertThat(compileAndRun("""
				(let ((v #f(1.0 2.0 3.0)))
				  (print (setf (aref v 1) 42))
				  (print v))
				""")).isEqualTo("42.0\n#f(1.0 42.0 3.0)");
	}

	@Test
	void compileMakeArraySingleFloatIsPacked() throws Exception {
		assertThat(compileAndRun("""
				(let ((a (make-array 3 :element-type 'single-float :initial-element 2.5)))
				  (setf (aref a 0) 9)
				  (print (list a (length a) (array-element-type a))))
				""")).isEqualTo("(#f(9.0 2.5 2.5) 3 SINGLE-FLOAT)");
	}

	@Test
	void compileMakeArraySingleFloatRankTwo() throws Exception {
		assertThat(compileAndRun("""
				(let ((m (make-array (list 2 2) :element-type 'single-float)))
				  (dotimes (i 2) (dotimes (j 2) (setf (aref m i j) (+ (* i 10) j))))
				  (print m))
				""")).isEqualTo("#f((0.0 1.0) (10.0 11.0))");
	}

	@Test
	void compilePackedSingleFloatRowMajorArefAndElementType() throws Exception {
		assertThat(compileAndRun("(print (row-major-aref #f((1 2) (3 4)) 3))")).isEqualTo("4.0");
		assertThat(compileAndRun("(print (array-element-type #f(1 2 3)))")).isEqualTo("SINGLE-FLOAT");
	}

	@Test
	void compilePackedSingleFloatIsAnArrayAndVector() throws Exception {
		assertThat(compileAndRun("(print (typecase #f(1 2 3) (array 'arr) (t 'no)))")).isEqualTo("ARR");
		assertThat(compileAndRun("(print (typecase #f(1 2 3) (vector 'vec) (t 'no)))")).isEqualTo("VEC");
	}

	@Test
	void compilePackedSingleFloatDotProductLoop() throws Exception {
		assertThat(compileAndRun("""
				(defun dot (a b n)
				  (let ((s 0.0) (i 0))
				    (loop while (< i n) do
				      (setf s (+ s (* (aref a i) (aref b i)))) (setf i (+ i 1)))
				    s))
				(print (dot #f(1 2 3) #f(4 5 6) 3))
				""")).isEqualTo("32.0");
	}

	@Test
	void compilePackedSingleFloatCoerceToList() throws Exception {
		assertThat(compileAndRun("(print (coerce #f(1 2 3) 'list))")).isEqualTo("(1.0 2.0 3.0)");
	}

	@Test
	void compileSingleFloatStorageNarrowsToF32() throws Exception {
		// f32(0.1) widened != f64 0.1, so a single-float element differs from the double
		// (proving the store really narrows to f32); the double width keeps the value.
		// The boolean result is byte-identical across every backend (unlike the printed
		// decimal, which the WASM float printer renders differently).
		assertThat(compileAndRun("""
				(let ((a (make-array 1 :element-type 'single-float)))
				  (setf (aref a 0) 0.1)
				  (print (= (aref a 0) 0.1)))
				""")).isEqualTo("NIL");
		assertThat(compileAndRun("""
				(let ((b (make-array 1 :element-type 'double-float)))
				  (setf (aref b 0) 0.1)
				  (print (= (aref b 0) 0.1)))
				""")).isEqualTo("T");
	}

	@Test
	void compileLengthOfVectorReturnsElementCount() throws Exception {
		assertThat(compileAndRun("(print (length (make-array 5 :initial-element 0)))")).isEqualTo("5");
		assertThat(compileAndRun("(print (length #(10 20 30)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (length #()))")).isEqualTo("0");
	}

	@Test
	void compileFillPointerLengthAndAccessors() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 5 :fill-pointer 2 :initial-element 0))
				(print (list (length *v*) (fill-pointer *v*) (array-has-fill-pointer-p *v*) (adjustable-array-p *v*)))
				""")).isEqualTo("(2 2 T NIL)");
	}

	@Test
	void compileFillPointerVectorPrintsUpToFillPointer() throws Exception {
		assertThat(compileAndRun("""
				(let ((v (make-array 5 :fill-pointer 3 :initial-element 9)))
				  (print v))
				""")).isEqualTo("#(9 9 9)");
	}

	@Test
	void compileFillPointerTIsTheVectorSize() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 4 :fill-pointer t :initial-element 1))
				(print (list (length *v*) (fill-pointer *v*)))
				""")).isEqualTo("(4 4)");
	}

	@Test
	void compileVectorPushStoresAndReturnsIndexOrNil() throws Exception {
		// Each push is sequenced through a top-level defparameter: the compilers
		// evaluate list argument forms right-to-left, so side-effecting
		// forms must not share one list form.
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 3 :fill-pointer 0))
				(defparameter *a* (vector-push 10 *v*))
				(defparameter *b* (vector-push 20 *v*))
				(defparameter *c* (vector-push 30 *v*))
				(defparameter *d* (vector-push 40 *v*))
				(print (list *a* *b* *c* *d*))
				""")).isEqualTo("(0 1 2 NIL)");
	}

	@Test
	void compileVectorPushThenReadBack() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 3 :fill-pointer 0))
				(vector-push 10 *v*)
				(vector-push 20 *v*)
				(print (list (length *v*) (aref *v* 0) (aref *v* 1)))
				""")).isEqualTo("(2 10 20)");
	}

	@Test
	void compileVectorPop() throws Exception {
		// The pop is sequenced before the length read (the compile path evaluates
		// argument forms right-to-left).
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 5 :fill-pointer 3 :initial-element 0))
				(setf (aref *v* 2) 99)
				(defparameter *p* (vector-pop *v*))
				(print (list *p* (length *v*)))
				""")).isEqualTo("(99 2)");
	}

	@Test
	void compileVectorPushExtendGrowsBeyondCapacity() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 2 :fill-pointer 0 :adjustable t))
				(vector-push-extend 1 *v*)
				(vector-push-extend 2 *v*)
				(vector-push-extend 3 *v*)
				(print (list (length *v*) (adjustable-array-p *v*) (aref *v* 2)))
				""")).isEqualTo("(3 T 3)");
	}

	@Test
	void compileSetfFillPointer() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 5 :fill-pointer 5 :initial-element 7))
				(setf (fill-pointer *v*) 2)
				(print (list (length *v*) (fill-pointer *v*)))
				""")).isEqualTo("(2 2)");
	}

	@Test
	void compileSimpleVectorHasNoFillPointer() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 3 :initial-element 0))
				(print (list (array-has-fill-pointer-p *v*) (adjustable-array-p *v*) (array-element-type *v*)))
				""")).isEqualTo("(NIL NIL T)");
	}

	@Test
	void compileFillPointerFirstClassWrappers() throws Exception {
		// The fill-pointer read is sequenced before the mutating pop, since the compile
		// path evaluates argument forms right-to-left.
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 3 :fill-pointer 0))
				(funcall #'vector-push 5 *v*)
				(defparameter *fp* (funcall #'fill-pointer *v*))
				(defparameter *popped* (funcall #'vector-pop *v*))
				(print (list *fp* *popped* (funcall #'array-has-fill-pointer-p *v*)))
				""")).isEqualTo("(1 5 T)");
	}

	@Test
	void compileClUtilitiesCopyArray() throws Exception {
		// The cl-utilities copy-array definition verbatim on the
		// WASM backend: array-element-type, make-array :adjustable/:fill-pointer,
		// array-has-fill-pointer-p, fill-pointer, adjustable-array-p, array-total-size
		// and row-major-aref cooperating.
		assertThat(compileAndRun("""
				(defun copy-array (array &key
				                   (element-type (array-element-type array))
				                   (fill-pointer (and (array-has-fill-pointer-p array)
				                                      (fill-pointer array)))
				                   (adjustable (adjustable-array-p array)))
				  (let* ((dimensions (array-dimensions array))
				         (new-array (make-array dimensions
				                                :element-type element-type
				                                :adjustable adjustable
				                                :fill-pointer fill-pointer)))
				    (dotimes (i (array-total-size array))
				      (setf (row-major-aref new-array i)
				            (row-major-aref array i)))
				    new-array))
				(defparameter *v* (make-array 4 :fill-pointer 3 :adjustable t :initial-element 5))
				(setf (aref *v* 1) 8)
				(defparameter *c* (copy-array *v*))
				(setf (aref *c* 0) 99)
				(print (list *c* *v* (fill-pointer *c*) (adjustable-array-p *c*)))
				""")).isEqualTo("(#(99 8 5) #(5 8 5) 3 T)");
	}

	@Test
	void compileAdjustArray() throws Exception {
		// Non-adjustable -> fresh array; :adjustable -> adjusted in place (eq);
		// rank-2 keeps the elements at their subscripts; the fill pointer carries
		// over without an explicit :fill-pointer.
		assertThat(compileAndRun("""
				(defparameter *v* (make-array 3 :initial-element 7))
				(defparameter *v2* (adjust-array *v* 5 :initial-element 0))
				(print (list *v2* (eq *v* *v2*)))
				(defparameter *w* (make-array 3 :adjustable t :initial-element 1))
				(defparameter *w2* (adjust-array *w* 5 :initial-element 9))
				(print (list (eq *w* *w2*) *w*))
				(defparameter *m* (make-array '(2 2) :initial-element 0))
				(setf (aref *m* 0 0) 1) (setf (aref *m* 0 1) 2)
				(setf (aref *m* 1 0) 3) (setf (aref *m* 1 1) 4)
				(print (adjust-array *m* '(3 3) :initial-element 0))
				(defparameter *fv* (make-array 4 :fill-pointer 2 :initial-element 5))
				(print (fill-pointer (adjust-array *fv* 8)))
				""")).isEqualTo("(#(7 7 7 0 0) NIL)\n(T #(1 1 1 9 9))\n#2A((1 2 0) (3 4 0) (0 0 0))\n2");
	}

	@Test
	void compileDisplacedArrays() throws Exception {
		// A displaced view aliases the target's storage in both directions, prints and
		// measures with its own dims, works over a rank-2 target, and keeps following
		// an adjustable target grown in place by adjust-array.
		assertThat(compileAndRun("""
				(defparameter *base* (make-array 6 :initial-element 0))
				(dotimes (i 6) (setf (aref *base* i) (* i 10)))
				(defparameter *view* (make-array 3 :displaced-to *base* :displaced-index-offset 2))
				(setf (aref *view* 0) 99)
				(print (aref *base* 2))
				(setf (aref *base* 4) 111)
				(print (list *view* (aref *view* 2) (length *view*)))
				(defparameter *mat* (make-array '(2 3) :initial-element 0))
				(dotimes (i 6) (setf (row-major-aref *mat* i) i))
				(print (make-array 3 :displaced-to *mat* :displaced-index-offset 3))
				(defparameter *tgt* (make-array 4 :adjustable t :initial-element 1))
				(defparameter *dv* (make-array 2 :displaced-to *tgt* :displaced-index-offset 1))
				(adjust-array *tgt* 6 :initial-element 8)
				(setf (aref *tgt* 1) 55)
				(print (aref *dv* 0))
				""")).isEqualTo("99\n(#(99 30 111) 111 3)\n#(3 4 5)\n55");
	}

	@Test
	void compileArrayDisplacementValues() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *base* (make-array 5))
				(defparameter *view* (make-array 2 :displaced-to *base* :displaced-index-offset 3))
				(multiple-value-bind (tgt off) (array-displacement *view*)
				  (print (list (eq tgt *base*) off)))
				(multiple-value-bind (tgt off) (array-displacement *base*)
				  (print (list tgt off)))
				""")).isEqualTo("(T 3)\n(NIL 0)");
	}

	@Test
	void compileCharVectorAccumulator() throws Exception {
		// A fill-pointered/adjustable character vector is a mutable string on the
		// wasm-GC backend: stringp, length, string=, princ (bare content) and prin1
		// (quoted) all treat it as a string.
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 4 :element-type 'character :fill-pointer 0 :adjustable t))
				(vector-push-extend #\\a *s*)
				(vector-push-extend #\\b *s*)
				(print (list (stringp *s*) (length *s*) (string= *s* "ab")))
				(princ *s*)
				(terpri)
				(prin1 *s*)
				""")).isEqualTo("(T 2 T)\nab\n\"ab\"");
	}

	@Test
	void compileCharVectorReplaceMutatesInPlace() throws Exception {
		// replace on a character vector mutates IN PLACE (visible through an alias
		// made before the write), unlike the functional rebuild on an immutable string.
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 8 :element-type 'character :fill-pointer 0 :adjustable t))
				(vector-push-extend #\\h *s*)
				(vector-push-extend #\\e *s*)
				(vector-push-extend #\\l *s*)
				(vector-push-extend #\\l *s*)
				(vector-push-extend #\\o *s*)
				(setf (fill-pointer *s*) 5)
				(defparameter *alias* *s*)
				(replace *s* "xyz" :start1 1 :start2 0 :end2 2)
				(print (list (string= *alias* "hxylo") (subseq *alias* 0)))
				""")).isEqualTo("(T \"hxylo\")");
	}

	@Test
	void compileCharVectorAdjustArrayGrows() throws Exception {
		// adjust-array on an adjustable character vector grows it in place: the
		// mutable-string marker survives %array-become (it keeps the old meta.cdr),
		// the fill pointer carries over, and pushes beyond the old capacity land
		// after the kept content.
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 2 :element-type 'character :fill-pointer 0 :adjustable t))
				(vector-push-extend #\\a *s*)
				(vector-push-extend #\\b *s*)
				(adjust-array *s* 6)
				(vector-push-extend #\\c *s*)
				(print (list (stringp *s*) (string= (subseq *s* 0) "abc") (length *s*)))
				""")).isEqualTo("(T T 3)");
	}

	@Test
	void compileCharVectorInitialContentsCopies() throws Exception {
		// (make-array n :element-type 'character :initial-contents charvec) yields a
		// fresh simple string of the ACTIVE content, unaffected by later pushes.
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 4 :element-type 'character :fill-pointer 0 :adjustable t))
				(vector-push-extend #\\a *s*)
				(vector-push-extend #\\b *s*)
				(defparameter *copy* (make-array (fill-pointer *s*) :element-type 'character :initial-contents *s*))
				(vector-push-extend #\\c *s*)
				(print (list *copy* (stringp *copy*) (string= *copy* "ab") (string= *s* "abc")))
				""")).isEqualTo("(\"ab\" T T T)");
	}

	@Test
	void compileCharVectorEqualAndHashKey() throws Exception {
		// equal compares a character vector to a string by content, and an
		// equal-test hash table finds a string-keyed entry through it.
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 2 :element-type 'character :fill-pointer 0))
				(vector-push #\\a *s*)
				(vector-push #\\b *s*)
				(defparameter *h* (make-hash-table :test 'equal))
				(setf (gethash "ab" *h*) 1)
				(print (list (equal *s* "ab") (gethash *s* *h*)))
				""")).isEqualTo("(T 1)");
	}

	@Test
	void compileScharSetfMutatesCharVectorInPlace() throws Exception {
		// (setf (schar cv i) ch) writes the character vector IN PLACE, so an alias
		// made before the write sees the update (an immutable string place only
		// rebinds the variable).
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 3 :element-type 'character :fill-pointer 3 :initial-element #\\a))
				(defparameter *alias* *s*)
				(setf (schar *s* 1) #\\z)
				(print (list (string= *alias* "aza") (schar *s* 1)))
				""")).isEqualTo("(T #\\z)");
	}

	@Test
	void compileCharVectorEltAndCoerce() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 3 :element-type 'character :fill-pointer 0))
				(vector-push #\\x *s*)
				(vector-push #\\y *s*)
				(vector-push #\\z *s*)
				(print (list (elt *s* 1) (coerce *s* 'list)))
				""")).isEqualTo("(#\\y (#\\x #\\y #\\z))");
	}

	@Test
	void compileJzonAccumulatorPattern() throws Exception {
		// The jzon writer shape: a reusable adjustable accumulator filled with
		// vector-push-extend, snapshotted via make-array :initial-contents, and reset
		// with (setf (fill-pointer a) 0) between uses.
		assertThat(compileAndRun("""
				(defun make-adjustable-string ()
				  (make-array 256 :element-type 'character :fill-pointer 0 :adjustable t))
				(defparameter *acc* (make-adjustable-string))
				(dolist (ch (coerce "hello" 'list))
				  (vector-push-extend ch *acc*))
				(defparameter *out1* (make-array (fill-pointer *acc*) :element-type 'character :initial-contents *acc*))
				(setf (fill-pointer *acc*) 0)
				(dolist (ch (coerce "world" 'list))
				  (vector-push-extend ch *acc*))
				(defparameter *out2* (make-array (fill-pointer *acc*) :element-type 'character :initial-contents *acc*))
				(print (list *out1* *out2*))
				""")).isEqualTo("(\"hello\" \"world\")");
	}

	@Test
	void compileReplaceOnImmutableStringStaysFunctional() throws Exception {
		// replace on an immutable string keeps returning a fresh rebuilt string; the
		// original is untouched. The make-array keeps the runtime %arrayp branch
		// exercised in an array-using program.
		assertThat(compileAndRun("""
				(defparameter *pad* (make-array 1 :initial-element 0))
				(defparameter *orig* "abc")
				(defparameter *r* (replace *orig* "xy"))
				(print (list *r* *orig*))
				""")).isEqualTo("(\"xyc\" \"abc\")");
	}

	@Test
	void compileReplaceCompilesWithoutAnyOtherArrayUse() throws Exception {
		// The shared replace expansion emits %arrayp/%row-major-aset even in a
		// program that never builds an array -- the mutating branch is dead at
		// runtime but must still compile and validate (array ops are inline on this
		// backend, no function gate).
		assertThat(compileAndRun("""
				(print (replace "abc" "xy"))
				""")).isEqualTo("\"xyc\"");
	}

	@Test
	void compilePlainCharacterMakeArrayIsStillAString() throws Exception {
		// Without :fill-pointer/:adjustable a rank-1 character array keeps the
		// make-string lowering: an immutable simple string.
		assertThat(compileAndRun("""
				(defparameter *s* (make-array 3 :element-type 'character :initial-element #\\x))
				(print (list *s* (stringp *s*)))
				""")).isEqualTo("(\"xxx\" T)");
	}

	@Test
	void compileEmptyCharVectorPrintsAsEmptyString() throws Exception {
		// An empty character vector (fill pointer 0) is "" in prin1 and empty in
		// princ; _charvec_to_str uses dims[0] when the fill pointer is nil, and a
		// character vector nested in a list renders through the recursive printer.
		assertThat(compileAndRun("""
				(defparameter *e* (make-array 4 :element-type 'character :fill-pointer 0 :adjustable t))
				(prin1 *e*)
				(terpri)
				(princ *e*)
				(terpri)
				(defparameter *s* (make-array 2 :element-type 'character :fill-pointer 0))
				(vector-push #\\o *s*)
				(vector-push #\\k *s*)
				(print (list *s* 1 *e*))
				(print (princ-to-string *s*))
				""")).isEqualTo("\"\"\n\n(\"ok\" 1 \"\")\n\"ok\"");
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
		// arithmetic, products, reductions and double-float linear algebra. inv/solve
		// use a power-of-two matrix whose float result is exact, so it prints identically
		// here (WASM renders a rounded general inverse at fewer significant digits).
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
				(print (linalg:inv (linalg:from-list '((4 0) (2 4)))))
				(print (linalg:solve (linalg:from-list '((4 0) (2 4))) (linalg:from-list '(8 8))))
				(print (funcall #'linalg:argmax (linalg:from-list '(1 9 3))))
				(print (linalg:ndim (linalg:from-list '((1 2) (3 4)))))
				""")).isEqualTo("#d((1.0 0.0) (0.0 1.0))\n#d(2.0 4.0 6.0 8.0)\n#d((0.0 1.0 2.0) (3.0 4.0 5.0))\n"
				+ "#d(11.0 12.0 13.0)\n32.0\n#d((19.0 22.0) (43.0 50.0))\n2.5\n5.0\n-2.0\n"
				+ "#d((0.25 0.0) (-0.125 0.25))\n#d(2.0 1.0)\n1\n2");
	}

	@Test
	void linalgDiffAndGradientWorkInPreview1Mode() throws Exception {
		// numpy calculus parity: diff = n-th discrete difference along the last axis,
		// gradient = second-order central differences with first-order one-sided ends
		// over a uniform scalar spacing or a non-uniform coordinate vector. All sample
		// values differentiate exactly, so the printed doubles match every backend.
		assertThat(compileAndRunLinalg("""
				(print (linalg:diff #(1 2 4 7 0)))
				(print (linalg:diff #(1 2 4 7 0) 2))
				(print (linalg:diff #2A((1 3 6) (0 5 6))))
				(print (linalg:gradient #(0 1 4 9 16)))
				(print (linalg:gradient #(0 1 4 9 16) 2))
				(print (linalg:gradient #(0 1 9) #(0 1 3)))
				(print (array-element-type (linalg:gradient (linalg:arange 0 4 'single-float))))
				""")).isEqualTo("#d(1.0 2.0 3.0 -7.0)\n#d(1.0 1.0 -10.0)\n#d((2.0 3.0) (5.0 1.0))\n"
				+ "#d(1.0 2.0 4.0 6.0 7.0)\n#d(0.5 1.0 2.0 3.0 3.5)\n#d(1.0 2.0 4.0)\nSINGLE-FLOAT");
	}

	@Test
	void linalgNumpyStyleBroadcastingWorksInPreview1Mode() throws Exception {
		// Same program as the JVM compileAndRunLinalgNumpyStyleBroadcasting case:
		// trailing axes align, an axis of extent 1 (or a missing leading axis)
		// stretches, and the result keeps the FIRST array operand's width.
		assertThat(compileAndRunLinalg("""
				(print (linalg:mul #2A((1 2) (3 4)) #(10 20)))
				(print (linalg:add #2A((1 2) (3 4)) #2A((100) (200))))
				(print (linalg:sub #(1 2) #d(1.0)))
				(print (linalg:maximum #2A((1 5) (4 2)) #(3 3)))
				(print (linalg:mul (linalg:from-list '((1 2) (3 4)) 'single-float) #(10 20)))
				(print (linalg:div #3A(((2.0 4.0) (6.0 8.0))) #(2 4)))
				""")).isEqualTo("#d((10.0 40.0) (30.0 80.0))\n#d((101.0 102.0) (203.0 204.0))\n#d(0.0 1.0)\n"
				+ "#d((3.0 5.0) (4.0 3.0))\n#f((10.0 40.0) (30.0 80.0))\n#d(((1.0 1.0) (3.0 2.0)))");
	}

	@Test
	void linalgTransposeAxesPadIm2colWorkInPreview1Mode() throws Exception {
		// Same program as the JVM compileAndRunLinalgTransposeAxesPadIm2col case:
		// the ch07 CNN additions -- transpose with an axes list, np.pad's
		// constant-0 mode, and the internal rank-4 %la-im2col/%la-col2im pair.
		assertThat(compileAndRunLinalg("""
				(defparameter *x* (linalg:reshape (linalg:arange 24) '(2 3 4)))
				(print (linalg:shape (linalg:transpose *x* '(1 0 2))))
				(print (linalg:transpose (linalg:from-list '((1 2) (3 4))) '(1 0)))
				(print (linalg:array-equal (linalg:transpose (linalg:transpose *x* '(1 2 0)) '(2 0 1)) *x*))
				(print (linalg:pad (linalg:from-list '((1 2) (3 4))) '((1 1) (2 2))))
				(print (linalg:pad #(1 2) 1))
				(defparameter *img* (linalg:reshape (linalg:arange 16) '(1 1 4 4)))
				(defparameter *col* (linalg::%la-im2col *img* 2 2 2 0))
				(print *col*)
				(print (linalg:array-equal (linalg::%la-col2im *col* '(1 1 4 4) 2 2 2 0) *img*))
				(print (linalg::%la-col2im (linalg::%la-im2col (linalg:ones '(1 1 3 3)) 2 2 1 0) '(1 1 3 3) 2 2 1 0))
				(print (array-element-type (linalg:transpose (linalg:ones '(2 2 2) 'single-float) '(2 1 0))))
				(print (array-element-type (linalg:pad (linalg:ones 2 'single-float) 1)))
				""")).isEqualTo("(3 2 4)\n#d((1.0 3.0) (2.0 4.0))\nT\n"
				+ "#d((0.0 0.0 0.0 0.0 0.0 0.0) (0.0 0.0 1.0 2.0 0.0 0.0) (0.0 0.0 3.0 4.0 0.0 0.0)"
				+ " (0.0 0.0 0.0 0.0 0.0 0.0))\n#d(0.0 1.0 2.0 0.0)\n"
				+ "#d((0.0 1.0 4.0 5.0) (2.0 3.0 6.0 7.0) (8.0 9.0 12.0 13.0) (10.0 11.0 14.0 15.0))\nT\n"
				+ "#d((((1.0 2.0 1.0) (2.0 4.0 2.0) (1.0 2.0 1.0))))\nSINGLE-FLOAT\nSINGLE-FLOAT");
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
				+ "((\"a\" . \"1\") (\"b\" . \"two\") (\"flag\" . \"\"))\nNIL\n\"ronto lisp\"\nNIL\n"
				+ "\"/get\"\n\"a=1\"\nNIL\n(\"a+b\" \"1 2\")");
	}

	@Test
	void jsonOpsWorkInPreview1Mode() throws Exception {
		// The Lisp-source JSON library (spliced by JsonLibrary.process, mirroring the
		// cli pre-pass) runs in Preview 1 with jzon's value mapping: objects become
		// hash tables with string keys, arrays vectors, null the symbol null, plus
		// escapes (\\uXXXX decoded to UTF-8 bytes), numbers, stringify and the
		// #' wrappers.
		assertThat(compileAndRunJson("""
				(let ((h (rontolisp:json-parse "{\\"name\\": \\"rontolisp\\", \\"n\\": 2}")))
				  (print (list (gethash "name" h) (gethash "n" h))))
				""")).isEqualTo("(\"rontolisp\" 2)");
		assertThat(compileAndRunJson("""
				(print (rontolisp:json-parse "42"))
				(print (rontolisp:json-parse "1e3"))
				(print (rontolisp:json-parse "1234567890123"))
				(print (floatp (rontolisp:json-parse "1234567890123456789")))
				(print (rontolisp:json-parse "[1, [2, \\"x\\"], null]"))
				(print (rontolisp:json-parse "\\"\\\\u0041\\\\u3042\\""))
				""")).isEqualTo("42\n1000.0\n1234567890123\nT\n#(1 #(2 \"x\") NULL)\n\"A\u3042\"");
		assertThat(compileAndRunJson("""
				(print (gethash "content-type"
				                (rontolisp:json-parse "{\\"content-type\\": \\"text/html\\"}")))
				""")).isEqualTo("\"text/html\"");
		assertThat(compileAndRunJson(
				"""
						(print (rontolisp:json-stringify (list 1 (list 2 3) nil)))
						(print (rontolisp:json-stringify (rontolisp:json-parse "{\\"deep\\": {\\"list\\": [{\\"k\\": \\"v\\"}, 2.5, true]}}")))
						(print (funcall #'rontolisp:json-stringify (list 1 2)))
						"""))
			.isEqualTo("\"[1,[2,3],false]\"\n\"{\"deep\":{\"list\":[{\"k\":\"v\"},2.5,true]}}\"\n\"[1,2]\"");
	}

	@Test
	void plistHashTableAndClosObjectsInPreview1Mode() throws Exception {
		// rontolisp:plist-hash-table (prelude, an alexandria subset) builds objects,
		// and json-stringify serializes a CLOS instance as an object (slots in
		// definition order; a hash-table slot nests as an object) -- single-key
		// objects keep the output backend-stable.
		List<LispVal> program = am.ik.rontolisp.eval.JsonLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(
					"""
							(print (rontolisp:json-stringify (rontolisp:plist-hash-table (list :msg "hi"))))
							(print (rontolisp:hash-table-plist (rontolisp:plist-hash-table (list :a 1))))
							(print (rontolisp:json-stringify (rontolisp:alist-hash-table (list (cons "x" 1)))))
							(defclass json-resp () ((status :initarg :status) (headers :initarg :headers) (items :initarg :items)))
							(let ((h (make-hash-table :test 'equal)))
							  (setf (gethash "content-type" h) "application/json")
							  (print (rontolisp:json-stringify
							          (make-instance 'json-resp :status 200 :headers h :items (list 1 2 3)))))
							""")));
		assertThat(compileAndRunProgram(program)).isEqualTo(
				"\"{\"msg\":\"hi\"}\"\n(:A 1)\n\"{\"x\":1}\"\n\"{\"status\":200,\"headers\":{\"content-type\":\"application/json\"},\"items\":[1,2,3]}\"");
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
				""")).isEqualTo("T\nNIL\n1\n2\n1");
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
				""")).isEqualTo("(1 (2 3))\n(1 NIL)\n(1 10 20 NIL)\n(1 2 3 T)");
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
				""")).isEqualTo("(0 1 NIL NIL)\n(0 5 T NIL)\n(0 9 T 7)\n(1 2 (:C 3 :D 4) 3)");
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

	// The instance tag is written with |...| because the reader upcases every ordinary
	// symbol: a source-written '%struct-POINT reads as %STRUCT-POINT and can never match
	// a real tag, which is what stops a program forging an instance.
	@Test
	void compileAndRunInstancePrimitives() throws Exception {
		assertThat(compileAndRun("""
				(defstruct point x y)
				(setq p (%obj-new '|%struct-POINT| 10 20))
				(print (%obj-ref p 0))
				(print (%obj-set p 1 99))
				(print (%obj-ref p 1))
				(print (%obj-is p '|%struct-POINT|))
				(print (%obj-is p '|%struct-OTHER| '|%struct-POINT|))
				(print (%obj-is p '|%struct-OTHER|))
				(print (%obj-tag p))
				(print (list (%obj-p p) (%obj-p (cons 1 2)) (%obj-p nil) (%obj-p 5)))
				(print (eq (%obj-tag p) (%obj-tag (%obj-new '|%struct-POINT| 1 2))))
				""")).isEqualTo("10\n99\n99\nT\nT\nNIL\n%struct-POINT\n(T NIL NIL NIL)\nT");
	}

	// equalp lives in the spliced prelude, which this harness does not run: the
	// instance-descending equalp is pinned end-to-end by the ci-spec case
	// instance-print-syntax-and-identity (all four backends through the real CLI).
	@Test
	void compileAndRunInstanceSlotsAndStructuralEqual() throws Exception {
		assertThat(compileAndRun("""
				(defstruct point x y)
				(defstruct empty)
				(print (list (%obj-slots (%obj-new '|%struct-POINT| 1 "hi"))
				             (%obj-slots (%obj-new '|%struct-EMPTY|))
				             (%obj-slots '(1 2)) (%obj-slots 5)))
				(print (list (equal (make-point :x 1 :y 2) (make-point :x 1 :y 2))
				             (equal (make-point :x 1 :y 2) (make-point :x 1 :y 9))
				             (equal (make-point :x 1 :y 2) (list 1 2))
				             (equal (make-point :x 1 :y 2) 5)))
				""")).isEqualTo("((1 \"hi\") NIL NIL NIL)\n(T NIL NIL NIL)");
	}

	@Test
	void compileAndRunInstancePrintsInStructAndAngleSyntax() throws Exception {
		assertThat(compileAndRun("""
				(defstruct point x y)
				(defstruct empty)
				(defclass pt () ((a :initarg :a) (b :initarg :b)))
				(defclass bare () ())
				(setq p (%obj-new '|%struct-POINT| "str" nil))
				(print p)
				(princ p) (terpri)
				(print (%obj-new '|%struct-POINT| p 3))
				(print (%obj-new '|%struct-EMPTY|))
				(print (list 1 p 2))
				(print (%obj-new '|%class-PT| 5 nil))
				(print (%obj-new '|%class-BARE|))
				""")).isEqualTo("""
				#S(POINT :X "str" :Y NIL)
				#S(POINT :X str :Y NIL)
				#S(POINT :X #S(POINT :X "str" :Y NIL) :Y 3)
				#S(EMPTY)
				(1 #S(POINT :X "str" :Y NIL) 2)
				#<PT :A 5 :B NIL>
				#<BARE>""");
	}

	// A #S(...) source literal is folded into an instance before compilation: the same
	// literal arm the quote compiler uses, so it must print and behave like a constructed
	// instance. The emit gate (usesInstances) has to be on for these to compile at all.
	@Test
	void compileAndRunStructLiteral() throws Exception {
		assertThat(compileAndRun("""
				(defstruct point x y)
				(print #S(POINT :X 1 :Y "hi"))
				(print (list (point-p #S(POINT :X 1 :Y 2)) (consp #S(POINT :X 1 :Y 2)) (point-x #S(POINT :X 7 :Y 2))))
				(print (equal #S(POINT :X 1 :Y 2) (make-point :x 1 :y 2)))
				""")).isEqualTo("#S(POINT :X 1 :Y \"hi\")\n(T NIL 7)\nT");
	}

	@Test
	void compileAndRunStructLiteralShapes() throws Exception {
		assertThat(compileAndRun("""
				(defstruct point (x 10) (y 20))
				(defstruct empty)
				(defstruct outer i)
				(print '#S(POINT :X 1 :Y 2))
				(print `(a #S(POINT :X 3 :Y 4)))
				(print (aref #(#S(POINT :X 5 :Y 6)) 0))
				(print #s(point :y 2))
				(print #S(POINT :X 1 :Y 2 :X 99))
				(print #S(EMPTY))
				(print #S(OUTER :I #S(EMPTY)))
				(print (point-x #S(POINT :X (+ 1 2))))
				""")).isEqualTo("""
				#S(POINT :X 1 :Y 2)
				(A #S(POINT :X 3 :Y 4))
				#S(POINT :X 5 :Y 6)
				#S(POINT :X 10 :Y 2)
				#S(POINT :X 1 :Y 2)
				#S(EMPTY)
				#S(OUTER :I #S(EMPTY))
				(+ 1 2)""");
	}

	@Test
	void compileStructLiteralOfAnUnknownTypeFails() {
		assertThatThrownBy(() -> compileAndRun("(print #S(NOPE :X 1))"))
			.hasMessageContaining("NOPE is not a defined structure type");
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
				""")).isEqualTo("1\n10\nT\nNIL\n1\n100");
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
				""")).isEqualTo("(99 7)\n(10 20)\n(3 T NIL)");
	}

	@Test
	void compileAndRunDefstructIncludePredicateMatchesLaterChildren() throws Exception {
		// The parent's predicate is regenerated once the whole program is registered, so
		// a child (and grandchild) whose defstruct FOLLOWS the parent's still answers T.
		assertThat(compileAndRun("""
				(defstruct dp-base a)
				(defstruct (dp-child (:include dp-base)) b)
				(defstruct (dp-grand (:include dp-child)) c)
				(print (list (dp-base-p (make-dp-child)) (dp-base-p (make-dp-grand))
				             (dp-child-p (make-dp-grand)) (dp-grand-p (make-dp-child))
				             (dp-base-p (make-dp-base)) (dp-base-p 5)))
				""")).isEqualTo("(T T T NIL T NIL)");
	}

	@Test
	void compileAndRunSetfFunctionDefinition() throws Exception {
		assertThat(compileAndRun("""
				(defvar *mode* :xml)
				(defun (setf my-mode) (m) (setq *mode* m))
				(setf (my-mode) :html5)
				(print *mode*)
				(funcall #'(setf my-mode) :sgml)
				(print *mode*)
				""")).isEqualTo(":HTML5\n:SGML");
	}

	@Test
	void compileAndRunDefgenericDefmethodEqlDispatch() throws Exception {
		assertThat(compileAndRun("""
				(defgeneric describe-it (x))
				(defmethod describe-it (x) (list :default x))
				(defmethod describe-it ((x (eql :br))) (list :special x))
				(print (list (describe-it 5) (describe-it :br)))
				(print (funcall #'describe-it 9))
				""")).isEqualTo("((:DEFAULT 5) (:SPECIAL :BR))\n(:DEFAULT 9)");
	}

	@Test
	void compileAndRunSlotShadowingChangeClassWithAccessorsAndPrintObject() throws Exception {
		assertThat(compileAndRun("""
				(defclass wc-base () ((open-p :initform t :reader wc-open-p) (conn :initarg :conn :reader wc-conn)))
				(defclass wc-sub (wc-base) ((open-p :initform :maybe :accessor wc-sub-open-p)
				                            (extra :initarg :extra :initform :none :accessor wc-extra)))
				(defstruct wc-node value)
				(defmethod print-object ((n wc-node) stream)
				  (print-unreadable-object (n stream :type t) (princ (wc-node-value n) stream)))
				(let* ((b (make-instance 'wc-base :conn "c")) (alias b))
				  (print (list (wc-open-p b) (slot-boundp b 'conn)))
				  (change-class b 'wc-sub :extra :pooled)
				  (print (list (class-of alias) (wc-conn alias) (wc-extra alias) (wc-sub-open-p alias)))
				  (with-accessors ((e wc-extra)) alias (setf e (list e :seen)))
				  (print (wc-extra alias)))
				(print (format nil "~a|~s" (make-wc-node :value 1) (make-wc-node :value 2)))
				"""))
			.isEqualTo("(T T)\n(%class-WC-SUB \"c\" :POOLED T)\n(:POOLED :SEEN)" + "\n\"#<WC-NODE 1>|#<WC-NODE 2>\"");
	}

	@Test
	void compileAndRunUnboundSlotSignalsUnboundSlot() throws Exception {
		assertThat(compileAndRun("""
				(defclass wu-box () ((a :initarg :a) (b :initform 7)))
				(let ((o (make-instance 'wu-box)))
				  (print (list (slot-boundp o 'a) (slot-boundp o 'b)))
				  (print (handler-case (slot-value o 'a)
				           (unbound-slot (e) (list (slot-value e 'name) (class-of (slot-value e 'instance))))))
				  (setf (slot-value o 'a) 1)
				  (print (list (slot-boundp o 'a) (slot-value o 'a)))
				  (slot-makunbound o 'a)
				  (print (slot-boundp o 'a)))
				""")).isEqualTo("(NIL T)\n(A %class-WU-BOX)\n(T 1)\nNIL");
	}

	@Test
	void compileAndRunDefclassSlotsAccessorsAndDispatch() throws Exception {
		assertThat(compileAndRun("""
				(defclass animal () ((name :initarg :name :accessor animal-name)))
				(defclass dog (animal) ((breed :initarg :breed :initform "mixed" :reader dog-breed)))
				(defgeneric speak (x))
				(defmethod speak ((x dog)) "woof")
				(defmethod speak ((x animal)) "...")
				(defmethod speak ((x integer)) "number")
				(defmethod speak (x) "?")
				(setq d (make-instance 'dog :name "Rex"))
				(print (list (speak d) (speak (make-instance 'animal :name "A")) (speak 1) (speak "s")))
				(print (list (animal-name d) (dog-breed d) (slot-value d 'name)))
				(setf (animal-name d) "Max")
				(setf (slot-value d 'name) (concatenate 'string (slot-value d 'name) "!"))
				(print (animal-name d))
				""")).isEqualTo("(\"woof\" \"...\" \"number\" \"?\")\n(\"Rex\" \"mixed\" \"Rex\")\n\"Max!\"");
	}

	@Test
	void compileAndRunListSpecializedMethodExcludesClassInstances() throws Exception {
		// An instance is a tagged cons internally, but must dispatch to the
		// standard-object/default method, not a list/cons/sequence-specialized one.
		assertThat(compileAndRun("""
				(defgeneric spx-kind (x)
				  (:method (x) :object)
				  (:method ((x list)) :list)
				  (:method ((x standard-object)) :instance))
				(defclass spx-thing () ((v :initarg :v)))
				(print (list (spx-kind (make-instance 'spx-thing :v 1)) (spx-kind '(1 2)) (spx-kind 5)))
				""")).isEqualTo("(:INSTANCE :LIST :OBJECT)");
	}

	@Test
	void compileAndRunEqualpComparesArraysElementwise() throws Exception {
		// The prelude splice mirrors the CLI pipeline (equalp is a prelude defun).
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(print (equalp #(1 "A" (2 3)) #(1 "a" (2 3.0))))
				(print (equalp #(1) #(1 2)))
				(print (equalp #(1) "x"))
				""")))).isEqualTo("T\nNIL\nNIL");
	}

	@Test
	void compileAndRunStreampAcceptsTheStandardOutputDesignator() throws Exception {
		assertThat(compileAndRun("""
				(print (streamp t))
				(print (streamp "x"))
				(let ((s t)) (check-type s stream) (print :ok))
				""")).isEqualTo("T\nNIL\n:OK");
	}

	@Test
	void compileAndRunFormatAsFirstClassFunction() throws Exception {
		assertThat(compileAndRun("""
				(print (apply #'format nil "x=~a y=~d" '(5 7)))
				(funcall #'format t "to-stdout ~a~%" "ok")
				(print (funcall #'format nil "~s" "q"))
				""")).isEqualTo("\"x=5 y=7\"\nto-stdout ok\n\"\"q\"\"");
	}

	@Test
	void compileAndRunPipeEscapedSymbols() throws Exception {
		assertThat(compileAndRun("""
				(print (symbol-name '|when used|))
				(print '|noChange|)
				""")).isEqualTo("\"when used\"\nnoChange");
	}

	@Test
	void compileAndRunMethodQualifiersAndCallNextMethod() throws Exception {
		assertThat(compileAndRun("""
				(defclass animal () ())
				(defclass dog (animal) ())
				(defparameter *log* nil)
				(defgeneric touch (x))
				(defmethod touch ((x animal)) (push :primary-animal *log*) :done)
				(defmethod touch ((x dog)) (push :primary-dog *log*) (call-next-method))
				(defmethod touch :before ((x animal)) (push :before-animal *log*))
				(defmethod touch :before ((x dog)) (push :before-dog *log*))
				(defmethod touch :after ((x animal)) (push :after-animal *log*))
				(defmethod touch :after ((x dog)) (push :after-dog *log*))
				(print (touch (make-instance 'dog)))
				(print (reverse *log*))
				""")).isEqualTo(
				":DONE\n" + "(:BEFORE-DOG :BEFORE-ANIMAL :PRIMARY-DOG :PRIMARY-ANIMAL :AFTER-ANIMAL :AFTER-DOG)");
	}

	@Test
	void compileAndRunAroundMethodAndNextMethodP() throws Exception {
		assertThat(compileAndRun("""
				(defclass thing () ())
				(defclass gadget (thing) ())
				(defgeneric render (x))
				(defmethod render ((x thing)) (list :thing (next-method-p)))
				(defmethod render ((x gadget)) (cons :gadget (call-next-method)))
				(defmethod render :around ((x thing)) (list :around (call-next-method)))
				(print (render (make-instance 'gadget)))
				""")).isEqualTo("(:AROUND (:GADGET :THING NIL))");
	}

	// --- Dynamic (special) variable binding ---

	@Test
	void specialVarLetHasDynamicExtent() throws Exception {
		assertThat(compileAndRun("""
				(defvar *x* 10)
				(print (list *x* (let ((*x* 20)) *x*) *x*))
				""")).isEqualTo("(10 20 10)");
	}

	@Test
	void specialVarBindingVisibleAcrossFunctionCalls() throws Exception {
		assertThat(compileAndRun("""
				(defvar *y* 1)
				(defun get-y () *y*)
				(print (list (get-y) (let ((*y* 2)) (get-y)) (get-y)))
				""")).isEqualTo("(1 2 1)");
	}

	@Test
	void specialVarNestedBindingsStackAndSetq() throws Exception {
		assertThat(compileAndRun("""
				(defvar *x* 1)
				(print (list *x* (let ((*x* 2)) (let ((*x* 3)) *x*)) (let ((*x* 5)) (setq *x* 6) *x*) *x*))
				""")).isEqualTo("(1 3 6 1)");
	}

	@Test
	void specialVarLetStarIsSequential() throws Exception {
		assertThat(compileAndRun("""
				(defvar *a* 1)
				(print (let* ((*a* 2) (b *a*)) (list *a* b)))
				""")).isEqualTo("(2 2)");
	}

	@Test
	void defparameterAndDeclaimSpecialAreDynamic() throws Exception {
		assertThat(compileAndRun("""
				(defparameter *p* 5)
				(print (list (let ((*p* 6)) *p*) *p*))
				""")).isEqualTo("(6 5)");
		assertThat(compileAndRun("""
				(declaim (special *s*))
				(setq *s* 100)
				(print (list (let ((*s* 7)) *s*) *s*))
				""")).isEqualTo("(7 100)");
	}

	@Test
	void lexicalGlobalLetStaysLexical() throws Exception {
		// A top-level setq global that is NOT special is rebound lexically by let.
		assertThat(compileAndRun("""
				(setq g 1)
				(defun read-g () g)
				(print (list (let ((g 2)) (read-g)) g))
				""")).isEqualTo("(1 1)");
	}

	@Test
	void progvIsRejectedOnWasm() {
		assertThatThrownBy(() -> new WasmLispCompiler().compile(LispReader.readAllFromString("(progv '(a) '(1) a)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("PROGV");
	}

	// --- wasm-GC --simd --------------------------------------------------------------

	// Compiles a vec:-using program on the wasm-GC backend, with or without --simd, and
	// runs it. Splices linalg.lisp then vec.lisp in RontoLispCli's order (the scalar vec:
	// members -- zeros/ones/arange/aref/length/mean/norm/to-list -- and the whole linalg:
	// surface keep running as defuns over the packed representation even under --simd;
	// only the vectorizable kernels are intercepted).
	private static String compileAndRunVec(String lispCode, boolean simd, String... extraFlags) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.VecLibrary
			.process(am.ik.rontolisp.eval.LinalgLibrary.process(LispReader.readAllFromString(lispCode)));
		byte[] wasmBytes = new WasmLispCompiler(false, false, false, false, false, simd).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		List<String> command = new java.util.ArrayList<>(List.of("wasmtime", "run", "--wasm", "gc"));
		command.addAll(List.of(extraFlags));
		command.add(path("test.wasm"));
		ExecResult result = wasmtime.execInContainer(command.toArray(new String[0]));
		assertThat(result.getExitCode()).as("exit code (simd=%s): %s\nstderr: %s", simd, lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	// The whole vec: surface at both widths, at lengths covering every group-padding
	// configuration: f64x2 needs even/odd, f32x4 needs 0/1/2/3 padding lanes. Under
	// --simd these no longer drive a scalar tail loop -- they drive the ZERO PADDING the
	// kernels rely on, so a padding lane that ever became non-zero shows up here.
	private static final String VEC_SURFACE = """
			(dolist (n '(1 2 3 4 5 7 8))
			  (let ((a (vec:arange n)) (b (vec:ones n)))
			    (print (list n (vec:dot a b) (vec:sum a) (vec:add a b) (vec:sub a b)
			                 (vec:mul a a) (vec:scale a 2.5) (vec:mean a)))))
			(dolist (n '(1 2 3 4 5 7 8))
			  (let ((a (vec:arange n 'single-float)) (b (vec:ones n 'single-float)))
			    (print (list n (vec:dot a b) (vec:sum a) (vec:add a b) (vec:scale a 2.0)))))
			(let ((o (vec:zeros 5)) (d (vec:ones 5)))
			  (print (eq o (vec:add-into o o d)))
			  (print o)
			  (print (vec:sub-into o o d))
			  (print (vec:mul-into o o o))
			  (print (vec:scale-into o o 3.0)))
			(let ((w #d((1.0 2.0 3.0) (4.0 5.0 6.0))) (x #d(1.0 2.0 3.0)) (o (vec:zeros 2)))
			  (print (vec:matvec w x))
			  (print (vec:matvec-into o w x)))
			(print (vec:matvec #f((1.0 2.0) (3.0 4.0) (5.0 6.0)) #f(2.0 4.0)))
			(let ((d #d(1.0 2.0 3.0 4.0 5.0)) (f #f(1.0 2.0 3.0)))
			  (setf (aref d 0) 9.5)
			  (print (list d (aref d 0) (length d) (array-dimensions d) (array-element-type d)))
			  (print (list f (array-element-type f) (row-major-aref f 1))))
			(print (vec:to-list #d(1.0 2.0 3.0)))
			(print (vec:from-list '(4.0 5.0 6.0)))
			(print (make-array '(2 3) :element-type 'double-float :initial-element 2.0))
			""";

	// GEMV at every lane offset a row start can land on. Row r of a d x n matrix begins
	// at
	// lane (r*n) & (lanes-1), so cycling n over 1,2,3,5 hits all four f32x4 offsets and
	// all
	// two f64x2 offsets -- i.e. both the plain array.get row read and every i8x16.shuffle
	// window immediate. The row sums of consecutive integers make a wrong window obvious.
	private static final String GEMV_LANE_OFFSETS = """
			(dolist (n '(1 2 3 5))
			  (let* ((w (linalg:reshape (linalg:arange 0 (* 3 n) 'single-float) (list 3 n)))
			         (x (vec:ones n 'single-float)))
			    (print (list n (vec:matvec w x)))))
			(dolist (n '(1 2 3 5 7))
			  (let* ((w (linalg:reshape (linalg:arange 0 (* 4 n)) (list 4 n)))
			         (x (vec:ones n)))
			    (print (list n (vec:matvec w x)))))
			""";

	// The generic packed accessors at every immediate lane index of both widths, plus the
	// make-array initial-element cases: absent and literal +0.0 skip the fill loop (a
	// zeroed (array (mut v128)) already holds them), -0.0 and a computed value do not.
	private static final String PACKED_ACCESSORS = """
			(let ((d (make-array 5 :element-type 'double-float))
			      (f (make-array 7 :element-type 'single-float)))
			  (dotimes (i 5) (setf (aref d i) (* 1.5 (+ i 1))))
			  (dotimes (i 7) (setf (row-major-aref f i) (* 0.25 (+ i 1))))
			  (print (list d f (aref d 4) (row-major-aref f 6) (array-total-size f))))
			(print (make-array 3 :element-type 'single-float :initial-element 1.25))
			(print (make-array 3 :element-type 'double-float :initial-element -0.0))
			(print (make-array 4 :element-type 'double-float :initial-element 0.0))
			(print (let ((k 7.5)) (make-array 5 :element-type 'double-float :initial-element k)))
			(print (list (vec:sum (vec:zeros 0)) (vec:add (vec:zeros 0) (vec:zeros 0))))
			(print (linalg:matmul #d((1.0 2.0) (3.0 4.0)) #d((5.0 6.0) (7.0 8.0))))
			(print (linalg:transpose #f((1.0 2.0) (3.0 4.0))))
			""";

	@Test
	void wasmGcSimdIsByteIdenticalToTheScalarPathOverTheWholeVecSurface() throws Exception {
		// The correctness contract: --simd changes the packed representation (a
		// TYPE_F64ARR/TYPE_F32ARR GC array -> a TYPE_VBLOCK over an (array (mut v128)) of
		// lane groups) and the kernels (scalar defuns -> v128), and must produce exactly
		// what the scalar wasm-GC path -- the cross-backend oracle -- produces. Covers
		// both
		// widths, every group-padding configuration, the -into kernels, GEMV, the generic
		// packed accessors and make-array/#d/#f literals.
		assertThat(compileAndRunVec(VEC_SURFACE, true)).isEqualTo(compileAndRunVec(VEC_SURFACE, false));
	}

	@Test
	void wasmGcSimdMatvecMatchesTheScalarPathAtEveryRowLaneOffset() throws Exception {
		// The shuffle window: a row that starts mid-group is read as two array.gets and
		// an
		// i8x16.shuffle whose immediate depends on the offset, so each offset is a
		// separate
		// emitted loop. This runs all six (four f32x4, two f64x2).
		assertThat(compileAndRunVec(GEMV_LANE_OFFSETS, true)).isEqualTo(compileAndRunVec(GEMV_LANE_OFFSETS, false));
	}

	@Test
	void wasmGcSimdPackedAccessorsMatchTheScalarPathAtEveryLane() throws Exception {
		// aref / row-major-aref / (setf aref) go through _v_get / _v_set, whose lane
		// index
		// is an instruction immediate and so a branch chain. A wrong arm shows up as a
		// misplaced element.
		assertThat(compileAndRunVec(PACKED_ACCESSORS, true)).isEqualTo(compileAndRunVec(PACKED_ACCESSORS, false));
	}

	@Test
	void wasmGcSimdSingleFloatReductionsAccumulateInSinglePrecision() throws Exception {
		// The wasm leg of the --simd precision contract: an #f reduction folds in f32
		// lanes and promotes only at the value boundary. wasm-GC has always done this;
		// the interpreter and JVM kernels now do it too (they used to widen every lane
		// to f64 first), so all four --simd backends print the same 16777984 here while
		// the scalar reference stays the exact oracle. eval/VecSimdTest and
		// JvmSimdAccelCompilerTest pin the same probe with the same numbers.
		//
		// dot(v,v) = 4096^2 + 1023 = 16778239 exactly; 4096^2 is 2^24, where the f32
		// spacing is 2, so the lane holding it swallows its 1.0s and the other three
		// lanes fold 256 each -> 2^24 + 768. matvec's scalar path narrows an f64 16778239
		// on store, which ties to even -> 16778240.
		String dot = "(let ((v (vec:ones 1024 'single-float))) (setf (aref v 0) 4096.0)"
				+ " (print (round (vec:dot v v))))";
		assertThat(compileAndRunVec(dot, true)).isEqualTo("16777984");
		assertThat(compileAndRunVec(dot, false)).isEqualTo("16778239");

		String sum = "(let ((v (vec:ones 1024 'single-float))) (setf (aref v 0) 16777216.0)"
				+ " (print (round (vec:sum v))))";
		assertThat(compileAndRunVec(sum, true)).isEqualTo("16777984");
		assertThat(compileAndRunVec(sum, false)).isEqualTo("16778239");

		String gemv = "(let ((m (make-array '(1 1024) :element-type 'single-float :initial-element 1.0))"
				+ " (v (vec:ones 1024 'single-float)))" + " (setf (aref m 0 0) 4096.0) (setf (aref v 0) 4096.0)"
				+ " (print (round (aref (vec:matvec m v) 0))))";
		assertThat(compileAndRunVec(gemv, true)).isEqualTo("16777984");
		assertThat(compileAndRunVec(gemv, false)).isEqualTo("16778240");

		// The #d control: double-float reductions are exact on both paths.
		String d = "(let ((v (vec:ones 1024))) (setf (aref v 0) 4096.0) (print (round (vec:dot v v))))";
		assertThat(compileAndRunVec(d, true)).isEqualTo("16778239");
		assertThat(compileAndRunVec(d, false)).isEqualTo("16778239");
	}

	@Test
	void wasmGcSimdOptimizedIsByteIdenticalToTheScalarPath() throws Exception {
		// --optimize shakes the (now dead) vec.lisp kernel defuns and must not disturb
		// the
		// v128 bodies; the tree shaker's 0xFD decoder is what makes that safe.
		String source = "(print (vec:dot (vec:arange 9) (vec:ones 9)))"
				+ "(print (vec:sum (vec:add (vec:arange 7 'single-float) (vec:ones 7 'single-float))))"
				+ "(print (vec:matvec #d((1.0 2.0) (3.0 4.0)) #d(5.0 6.0)))";
		List<LispVal> program = am.ik.rontolisp.eval.VecLibrary.process(LispReader.readAllFromString(source));
		byte[] optimized = new WasmLispCompiler(false, false, false, true, false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(optimized), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "--wasm", "gc", path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo(compileAndRunVec(source, false));
	}

	@Test
	void wasmGcSimdModuleNeedsTheSimdProposalAndTheDefaultOneDoesNot() throws Exception {
		// The runnable dead-flag guard, the wasm-GC counterpart of the JVM's
		// NoClassDefFoundError-without-the-incubator-module check: turning the SIMD
		// proposal off makes wasmtime REFUSE to compile the --simd module (its v128
		// opcodes no longer validate), while the default module still runs. Proves the
		// interception fired -- correctness alone cannot, since both compute the same
		// values. (relaxed-simd must be disabled too: wasmtime rejects simd=n on its
		// own.)
		String source = "(print (vec:dot (vec:ones 5) (vec:ones 5)))";
		String[] noSimd = { "--wasm", "simd=n", "--wasm", "relaxed-simd=n" };
		assertThat(compileAndRunVec(source, false, noSimd)).isEqualTo("5.0");

		List<LispVal> program = am.ik.rontolisp.eval.VecLibrary.process(LispReader.readAllFromString(source));
		byte[] simdBytes = new WasmLispCompiler(false, false, false, false, false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(simdBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "--wasm", "gc", "--wasm", "simd=n", "--wasm",
				"relaxed-simd=n", path("test.wasm"));
		assertThat(result.getExitCode()).as("a --simd module must not validate without the SIMD proposal").isNotZero();
	}

	@Test
	void wasmGcSimdPackedArraysAreCollectedRatherThanAccumulated() throws Exception {
		// The point of putting the wasm-GC --simd packed arrays on the GC heap. Each
		// `vec:add` allocates a fresh 1048576-element packed array -- 8 MiB -- and drops
		// the previous one; 700 of them is 5.6 GiB, MORE than the whole 4 GiB address
		// space a wasm32 linear memory can ever reach. So this can only complete if the
		// packed arrays are collected GC objects; a never-freed linear arena would trap
		// on memory.grow long before the last iteration. (Peak RSS measured at ~83 MB,
		// flat in the iteration count.)
		String source = """
				(let* ((n 1048576) (b (vec:ones n)) (o (vec:zeros n)))
				  (dotimes (i 700) (setq o (vec:add o b)))
				  (print (vec:aref o 0)))
				""";
		assertThat(compileAndRunVec(source, true)).isEqualTo("700.0");
	}

	@Test
	void wasmGcSimdIntoKernelsReuseTheCallersDestination() throws Exception {
		// -into is no longer a memory-safety requirement on wasm-GC (the collector
		// handles
		// that now), but it is still the allocation-rate optimization it is on the JVM
		// and
		// the interpreter: the destination is returned identically (eq), not copied.
		String source = """
				(let* ((n 65536) (a (vec:ones n)) (b (vec:ones n)) (o (vec:zeros n)))
				  (print (vec:dot a b))
				  (print (eq o (vec:add-into o o b)))
				  (dotimes (i 100) (vec:add-into o o b))
				  (print (vec:aref o 0))
				  (print (vec:aref o 65535)))
				""";
		assertThat(compileAndRunVec(source, true)).isEqualTo("65536.0\nT\n101.0\n101.0");
	}

	// An -into destination LONGER than its operands, with a non-lane-multiple operand
	// count. The kernels write whole lane groups, so the last store reaches up to lanes-1
	// elements past the operand count -- real elements of `out`, which the scalar
	// vec.lisp
	// defun leaves alone. gcSaveLastGroup / gcRestoreLastGroupTail blend them back.
	// Without
	// that blend `o[3]` reads 0.0 instead of 9.0 in the first case (and `o[5..7]` in the
	// second).
	private static final String INTO_LONGER_DESTINATION = """
			(let ((o (make-array 8 :element-type 'double-float :initial-element 9.0))
			      (a (vec:ones 3)) (b (vec:ones 3)))
			  (vec:add-into o a b)
			  (print o))
			(let ((o (make-array 8 :element-type 'single-float :initial-element 9.0))
			      (a (vec:ones 5 'single-float)) (b (vec:ones 5 'single-float)))
			  (vec:sub-into o a b)
			  (print o))
			(let ((o (make-array 6 :element-type 'double-float :initial-element 9.0))
			      (v (vec:ones 3)))
			  (vec:scale-into o v 2.0)
			  (print o))
			(let ((o (make-array 7 :element-type 'single-float :initial-element 9.0))
			      (v (vec:ones 3 'single-float)))
			  (vec:mul-into o v v)
			  (print o))
			""";

	@Test
	void wasmGcSimdIntoKernelsDoNotClobberADestinationLongerThanTheOperands() throws Exception {
		assertThat(compileAndRunVec(INTO_LONGER_DESTINATION, true))
			.isEqualTo(compileAndRunVec(INTO_LONGER_DESTINATION, false));
	}

	// The element-wise unary ufuncs: every op at lengths on both sides of a
	// lane-group boundary, both widths (the signed operand is arange - 2, so the sign
	// mix hits abs/negative/sign), exp over reciprocal's bounded (0, 1] range, log over
	// strictly positive inputs, tanh over the sign mix plus its saturation and -0.0 (0.0
	// on this backend) edges, sin/cos/tan over the sign mix plus every reduction
	// quadrant and the -0.0 (0.0 here) edge, the wasm defun's own signed-zero edges
	// (0 - x negation, abs keeping -0.0, sign mapping -0.0 to 0.0 -- the kernels mirror
	// THIS backend's defun, not java.lang.Math), and
	// the -into siblings: destination identity, in-place aliasing (the add-into rule)
	// and a destination longer than the operand keeping its tail elements.
	private static final String UNARY_UFUNCS = """
			(dolist (n '(1 4 5 8 200))
			  (let ((v (vec:sub (vec:arange n) (vec:scale (vec:ones n) 2.0)))
			        (f (vec:sub (vec:arange n 'single-float) (vec:scale (vec:ones n 'single-float) 2.0))))
			    (print (list n (vec:abs v) (vec:negative v) (vec:sign v) (vec:square v)))
			    (print (list (vec:abs f) (vec:negative f) (vec:sign f) (vec:square f)))
			    (print (list (vec:sqrt (vec:square v)) (vec:sqrt (vec:square f))))
			    (print (vec:reciprocal (vec:add (vec:arange n) (vec:ones n))))
			    (print (vec:reciprocal (vec:add (vec:arange n 'single-float) (vec:ones n 'single-float))))
			    (print (vec:exp (vec:reciprocal (vec:add (vec:arange n) (vec:ones n)))))
			    (print (vec:exp (vec:reciprocal (vec:add (vec:arange n 'single-float) (vec:ones n 'single-float)))))
			    (print (vec:log (vec:add (vec:arange n) (vec:ones n))))
			    (print (vec:log (vec:add (vec:arange n 'single-float) (vec:ones n 'single-float))))
			    (print (vec:tanh v))
			    (print (vec:tanh f))
			    (print (list (vec:sin v) (vec:cos v) (vec:tan v)))
			    (print (list (vec:sin f) (vec:cos f) (vec:tan f)))
			    (print (list (vec:asin (vec:scale v 0.00390625)) (vec:acos (vec:scale v 0.00390625)) (vec:atan v)))
			    (print (list (vec:asin (vec:scale f 0.00390625)) (vec:acos (vec:scale f 0.00390625)) (vec:atan f)))
			    (print (list (vec:sinh (vec:scale v 0.0625)) (vec:cosh (vec:scale v 0.0625))))
			    (print (list (vec:sinh (vec:scale f 0.0625)) (vec:cosh (vec:scale f 0.0625))))))
			(print (vec:negative #d(0.0 -0.0 1.5)))
			(print (vec:abs #d(-0.0 0.0 -2.5)))
			(print (vec:sign #d(-0.0 0.0 -3.5 3.5)))
			(print (vec:tanh #d(-25.0 -0.0 0.0 25.0)))
			(print (vec:log #d(1.0 0.5 4096.0)))
			(print (vec:sin #d(0.0 -0.0 1.0 -2.5 100.0)))
			(print (vec:cos #d(0.0 1.0 -2.5 100.0)))
			(print (vec:tan #d(0.0 1.0 -2.5 2.0)))
			(print (vec:asin #d(0.0 -0.0 1.0 -1.0 0.5)))
			(print (vec:acos #d(1.0 -1.0 0.0 0.5)))
			(print (vec:atan (vec:reciprocal #d(0.0 -0.0))))
			(print (vec:sinh #d(0.0 -0.0 0.25 -0.25 0.3)))
			(print (vec:cosh #d(0.0 -0.0 1.0)))
			(let ((o (vec:zeros 5)))
			  (print (eq o (vec:sqrt-into o #d(4.0 9.0 16.0 25.0 36.0))))
			  (print o)
			  (print (vec:exp-into o o))
			  (print (vec:negative-into o o))
			  (print (vec:abs-into o o))
			  (print (vec:sign-into o o))
			  (print (vec:reciprocal-into o (vec:add (vec:arange 5) (vec:ones 5))))
			  (print (vec:square-into o o))
			  (print (vec:log-into o (vec:add (vec:arange 5) (vec:ones 5))))
			  (print (vec:tanh-into o o))
			  (print (vec:sin-into o (vec:sub (vec:arange 5) (vec:scale (vec:ones 5) 2.0))))
			  (print (vec:cos-into o o))
			  (print (vec:tan-into o o))
			  (print (vec:asin-into o (vec:scale (vec:sub (vec:arange 5) (vec:scale (vec:ones 5) 2.0)) 0.4)))
			  (print (vec:acos-into o o))
			  (print (vec:atan-into o o))
			  (print (vec:sinh-into o o))
			  (print (vec:cosh-into o o)))
			(let ((long (vec:scale (vec:ones 7) 9.0)))
			  (print (vec:sqrt-into long #d(4.0 9.0 16.0)))
			  (print (vec:reciprocal-into long #d(2.0 4.0)))
			  (print long))
			(let ((flong (vec:scale (vec:ones 6 'single-float) 9.0)))
			  (print (vec:abs-into flong #f(-1.0 -2.0 -3.0)))
			  (print flong))
			""";

	@Test
	void wasmGcSimdUnaryUfuncsAreByteIdenticalToTheScalarPath() throws Exception {
		assertThat(compileAndRunVec(UNARY_UFUNCS, true)).isEqualTo(compileAndRunVec(UNARY_UFUNCS, false));
	}

	// The comparison-select ufuncs: maximum/minimum over operands
	// whose winner flips mid-vector, at lengths on both sides of a lane-group boundary
	// and both widths (integer-valued inputs, exact at either width; the gt/lt lane
	// mask + bitselect only copies input bits, so f32 lanes are bit-identical too);
	// relu over the sign mix (the U_RELU lane form); clip against fractional bounds
	// that are NOT f32-representable (the element loop must compare the widened
	// element against the full f64 bound); the strict-comparison edges (-0.0/0.0 ties
	// and NaN taking the second operand / the bound, inverted clip bounds ending at
	// hi); and the -into siblings with destination identity, aliasing, and a longer
	// destination keeping its tail.
	private static final String COMPARISON_SELECTS = """
			(dolist (n '(1 4 5 8 200))
			  (let ((v (vec:sub (vec:arange n) (vec:scale (vec:ones n) 2.0)))
			        (f (vec:sub (vec:arange n 'single-float) (vec:scale (vec:ones n 'single-float) 2.0))))
			    (print (list n (vec:maximum v (vec:negative v)) (vec:minimum v (vec:negative v))))
			    (print (list (vec:maximum f (vec:negative f)) (vec:minimum f (vec:negative f))))
			    (print (list (vec:relu v) (vec:relu f)))
			    (print (vec:clip v -1.5 1.5))
			    (print (vec:clip f -1.5 1.5))))
			(print (vec:maximum #d(-0.0 0.0) #d(0.0 -0.0)))
			(print (vec:minimum #d(-0.0 0.0) #d(0.0 -0.0)))
			(print (vec:maximum (vec:scale (vec:ones 2) (/ 0.0 0.0)) #d(1.0 2.0)))
			(print (vec:maximum #d(1.0 2.0) (vec:scale (vec:ones 2) (/ 0.0 0.0))))
			(print (vec:relu #d(-0.0 0.0)))
			(print (vec:relu (vec:scale (vec:ones 1) (/ 0.0 0.0))))
			(print (vec:clip (vec:scale (vec:ones 2) (/ 0.0 0.0)) -1.0 1.0))
			(print (vec:clip #d(0.0 3.0 -3.0) 2.0 1.0))
			(let ((o (vec:zeros 5)))
			  (print (eq o (vec:maximum-into o #d(1.0 5.0 3.0 -1.0 0.0) #d(4.0 2.0 3.0 1.0 -0.0))))
			  (print o)
			  (print (vec:minimum-into o o #d(2.0 2.0 2.0 2.0 2.0)))
			  (print (vec:relu-into o o))
			  (print (vec:clip-into o (vec:sub (vec:arange 5) (vec:scale (vec:ones 5) 2.0)) -1.0 1.0)))
			(let ((v (vec:sub (vec:arange 5) (vec:scale (vec:ones 5) 2.0))))
			  (vec:relu-into v v)
			  (print v))
			(let ((long (vec:scale (vec:ones 7) 9.0)))
			  (print (vec:maximum-into long #d(1.0 5.0) #d(4.0 2.0)))
			  (print (vec:relu-into long #d(-1.0 3.0)))
			  (print (vec:clip-into long #d(-9.0 0.5 9.0) -1.0 1.0))
			  (print long))
			(let ((flong (vec:scale (vec:ones 6 'single-float) 9.0)))
			  (print (vec:minimum-into flong #f(1.0 5.0 3.0) #f(4.0 2.0 3.0)))
			  (print flong))
			""";

	@Test
	void wasmGcSimdComparisonSelectsAreByteIdenticalToTheScalarPath() throws Exception {
		assertThat(compileAndRunVec(COMPARISON_SELECTS, true)).isEqualTo(compileAndRunVec(COMPARISON_SELECTS, false));
	}

	// Compiles and runs a vec: program without asserting success; returns the exit code.
	private static int compileAndRunVecExitCode(String lispCode, boolean simd) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.VecLibrary
			.process(am.ik.rontolisp.eval.LinalgLibrary.process(LispReader.readAllFromString(lispCode)));
		byte[] wasmBytes = new WasmLispCompiler(false, false, false, false, false, simd).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		return wasmtime.execInContainer("wasmtime", "run", "--wasm", "gc", path("test.wasm")).getExitCode();
	}

	@Test
	void wasmGcSimdMatvecIntoRejectsADestinationAliasingEitherOperand() throws Exception {
		// out[row] folds over ALL of x, and the row windows keep reading W while the rows
		// already computed are written back -- so `out` may alias neither. vec.lisp,
		// JvmSimdVectorTemplate and VecSimd all signal an error; the emitted kernel
		// traps. The v128 kernel REPLACES the vec.lisp defun, so its own guard is the
		// only one that runs, and it must reject an `out` aliasing `w` as well as one
		// aliasing `x`.
		String aliasesW = "(let ((m #d((1.0 2.0) (3.0 4.0)))) (print (vec:matvec-into m m #d(5.0 6.0))))";
		String aliasesX = "(let ((v #d(5.0 6.0))) (print (vec:matvec-into v #d((1.0 2.0) (3.0 4.0)) v)))";
		for (String source : List.of(aliasesW, aliasesX)) {
			assertThat(compileAndRunVecExitCode(source, true)).as("--simd must trap: %s", source).isNotZero();
			assertThat(compileAndRunVecExitCode(source, false)).as("scalar must trap too: %s", source).isNotZero();
		}
		// A non-aliasing destination of course still works.
		assertThat(compileAndRunVec(
				"(let ((o (vec:zeros 2))) (print (vec:matvec-into o #d((1.0 2.0) (3.0 4.0))" + " #d(5.0 6.0))))", true))
			.isEqualTo("#d(17.0 39.0)");
	}

	// --- linalg: kernel interception under --simd ------------------------------------
	//
	// Each source below is compiled and run TWICE (--simd and scalar) as its own module:
	// what is under test is how one call shape compiles, so the sources cannot be folded
	// into a single program the way the vec: surface tests above are. That makes this
	// section the bulk of the class's work, and it is spread two ways -- the sources come
	// from @MethodSource factories so JUnit schedules them individually (a plain @Test
	// looping over them is one unit of work, and a class's wall clock is its longest
	// method), and the two halves of a comparison run at once, see below.

	/**
	 * Asserts the accelerated wasm-GC module prints exactly what the scalar one prints.
	 * Both go through {@code LinalgLibrary.process}, so the only difference is the flag.
	 * The two halves are independent by construction, so the accelerated one is staged
	 * and run on a {@link #PAIRS} worker while this thread does the scalar one.
	 * @param lispCode the program to compile both ways
	 */
	private static void assertLinalgMatchesTheScalarPath(String lispCode) throws Exception {
		Future<String> accelerated = PAIRS.submit(() -> compileAndRunVec(lispCode, true));
		String scalar = compileAndRunVec(lispCode, false);
		assertThat(await(accelerated)).as(lispCode).isEqualTo(scalar);
	}

	// Guards the interception fix: before it, --simd switched the packed repr to a
	// vblock and every linalg row-major-aref paid _v_get/_v_set for no v128 in
	// return. Both widths, rank 1 and rank 2 (the case vec: never had), the scalar
	// broadcast on either side, and above/below any lane-group boundary.
	static List<String> elementWiseAndShapeKernelSources() {
		List<String> sources = new ArrayList<>();
		for (String op : List.of("add", "sub", "mul", "div")) {
			sources.add("(print (linalg:%s #d(1.0 2.0 3.0) #d(4.0 5.0 8.0)))".formatted(op));
			sources.add("(print (linalg:%s #f(1.0 2.0 3.0) #f(4.0 5.0 8.0)))".formatted(op));
			sources.add("(print (linalg:%s #d((1.0 2.0) (3.0 4.0)) #d((5.0 6.0) (7.0 8.0))))".formatted(op));
			sources.add("(print (linalg:%s #d(1.0 2.0 4.0) 2.0))".formatted(op));
			sources.add("(print (linalg:%s 2.0 #d(1.0 2.0 4.0)))".formatted(op));
			sources.add("(print (linalg:%s #f(1.0 2.0 4.0) 2))".formatted(op));
			// A single-float array against a scalar that is not representable in f32: the
			// kernel must widen, compute in f64 and narrow, exactly as emap does.
			// Splatting
			// (f32) 0.1 into f32 lanes would print different numbers here.
			sources.add("(print (linalg:%s #f(1.0 3.0 7.0) 0.1))".formatted(op));
			sources.add("(print (linalg:%s 0.1 #f(1.0 3.0 7.0)))".formatted(op));
			// 5 elements: a partial last lane group at both widths, whose zero padding
			// must
			// not leak into the result (0 - s = -s, s / 0 = inf).
			sources.add("(print (linalg:sum (linalg:%s (linalg:ones 5) 3.0)))".formatted(op));
			sources.add("(print (linalg:sum (linalg:%s 3.0 (linalg:ones 5 'single-float))))".formatted(op));
		}
		sources.add("(print (linalg:transpose #d((1.0 2.0 3.0) (4.0 5.0 6.0))))");
		sources.add("(print (linalg:transpose #f((1.0 2.0) (3.0 4.0))))");
		sources.add("(let ((v #d(1.0 2.0))) (print (eq v (linalg:transpose v))))");
		sources.add("(print (linalg:reshape (linalg:arange 12) '(3 4)))");
		sources.add("(print (linalg:reshape (linalg:arange 12) '(2 3 2)))");
		sources.add("(print (linalg:reshape (linalg:arange 0 12 'single-float) 12))");
		sources.add("(print (linalg:flatten #d((1.0 2.0) (3.0 4.0))))");
		return sources;
	}

	@ParameterizedTest
	@MethodSource("elementWiseAndShapeKernelSources")
	void wasmGcSimdLinalgElementWiseAndShapeKernelsAreByteIdenticalToTheScalarPath(String lispCode) throws Exception {
		assertLinalgMatchesTheScalarPath(lispCode);
	}

	// The axis forms are intercepted call shapes: an axis call
	// routes to the SUM_AXIS/AMAX_AXIS/ARGMAX_AXIS kernels (a 2-argument call
	// padded with a null keepdims), whose folds mirror %la-fold-axis /
	// %la-argfold-axis exactly; a 1-arg call still hits the base kernel whose
	// decline branch passes an extra null rest to the variadic defun, and a
	// declined AXIS call links the surplus locals into the rest list.
	static List<String> axisFormSources() {
		return List.of("(print (linalg:sum (linalg:reshape (linalg:arange 6) '(2 3)) 0))",
				"(print (linalg:sum (linalg:reshape (linalg:arange 6) '(2 3)) 1 t))",
				"(print (linalg:sum (linalg:reshape (linalg:arange 24) '(2 3 4)) -1))",
				"(print (linalg:sum (linalg:arange 5) 0))",
				"(print (linalg:sum (linalg:from-list '((0.5 0.25) (0.125 2.0)) 'single-float) 0))",
				"(print (linalg:mean (linalg:reshape (linalg:arange 6) '(2 3)) 0))",
				"(print (linalg:amax (linalg:reshape (linalg:arange 6) '(2 3)) 1))",
				"(print (linalg:amin (linalg:reshape (linalg:arange 6) '(2 3)) 0 t))",
				"(print (linalg:argmax (linalg:reshape (linalg:arange 6) '(2 3)) 1))",
				"(print (linalg:argmin (linalg:from-list '(3.0 9.0 2.0)) 0))",
				"(print (linalg:amax (linalg:from-list '((0.5 0.25) (0.125 2.0)) 'single-float) 1))",
				// 1-arg over a general (boxed) array exercises the decline branch itself;
				// an axis call over one exercises the extended decline's rest packaging.
				"(print (linalg:sum #(1 2 3)))", "(print (linalg:argmax #(1 9 3)))",
				"(print (linalg:sum #2A((1 2) (3 4)) 0))", "(print (linalg:sum #d((1.0 2.0)) nil))",
				// reshape keeps its fixed arity 2; a -1 extent declines inside the
				// kernel.
				"(print (linalg:reshape (linalg:arange 12) '(3 -1)))");
	}

	@ParameterizedTest
	@MethodSource("axisFormSources")
	void wasmGcSimdLinalgAxisFormsRunTheAxisKernelsAndMatchTheScalarPath(String lispCode) throws Exception {
		assertLinalgMatchesTheScalarPath(lispCode);
	}

	@Test
	void wasmGcSimdLinalgAxisFoldComparesStrictly() throws Exception {
		// The fold's strict comparison: the accumulator (first element) wins ties, and
		// an all-negative axis never answers 0.
		assertThat(compileAndRunVec("(print (linalg:amax #d((-0.0 0.0)) 1))", true)).isEqualTo("#d(-0.0)");
		assertThat(compileAndRunVec("(print (linalg:amax #d((-3.0 -1.0) (-5.0 -2.0)) 1))", true))
			.isEqualTo("#d(-1.0 -2.0)");
	}

	// The general numpy broadcast (BCAST, reached from the element-wise kernels'
	// unequal-dims branch) and the rank-n axes permutation (TRANSPOSE_AXES), both
	// vget/_v_set element walks -- widen, compute in f64, narrow on store, the
	// oracle's own rule, so byte-identical at both widths.
	static List<String> broadcastAndTransposeAxesSources() {
		return List.of("(print (linalg:add (linalg:reshape (linalg:arange 6) '(2 3)) (linalg:arange 3)))",
				"(print (linalg:mul (linalg:reshape (linalg:arange 8) '(4 2))"
						+ " (linalg:reshape (linalg:from-list '(5.0 6.0 7.0 8.0)) '(4 1))))",
				"(print (linalg:div (linalg:reshape (linalg:arange 24) '(2 3 4))"
						+ " (linalg:add (linalg:reshape (linalg:arange 12) '(3 4)) 1)))",
				"(print (linalg:sub (linalg:reshape (linalg:arange 0 4 'single-float) '(2 2))"
						+ " (linalg:arange 0 2 'single-float)))",
				"(print (linalg:maximum (linalg:reshape (linalg:arange 6) '(2 3)) (linalg:from-list '(2.0 4.0 1.0))))",
				// A mixed-width broadcast still declines to the defun (first operand's
				// width).
				"(print (array-element-type (linalg:div (linalg:ones '(2 2) 'single-float) #d(1.0 2.0))))",
				"(print (linalg:transpose (linalg:reshape (linalg:arange 24) '(2 3 2 2)) '(0 3 1 2)))",
				"(print (linalg:transpose (linalg:reshape (linalg:arange 6) '(2 3)) '(1 0)))",
				"(print (linalg:transpose (linalg:reshape (linalg:from-list '(1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0)"
						+ " 'single-float) '(2 2 2)) '(2 0 1)))",
				"(print (linalg:transpose (linalg:arange 3) '(0)))",
				"(print (linalg:transpose (linalg:reshape (linalg:arange 6) '(2 3)) nil))");
	}

	@ParameterizedTest
	@MethodSource("broadcastAndTransposeAxesSources")
	void wasmGcSimdLinalgBroadcastAndTransposeAxesMatchTheScalarPath(String lispCode) throws Exception {
		assertLinalgMatchesTheScalarPath(lispCode);
	}

	@Test
	void wasmGcSimdLinalgBroadcastKeepsTheSecondOperandOnASignedZeroTie() throws Exception {
		assertThat(compileAndRunVec("(print (linalg:maximum #d((0.0 -0.0)) #d(-0.0 0.0)))", true))
			.isEqualTo("#d((-0.0 0.0))");
	}

	static List<String> reductionAndProductSources() {
		List<String> sources = new ArrayList<>();
		for (String member : List.of("sum", "mean", "amax", "amin", "norm", "argmax", "argmin")) {
			sources.add("(print (linalg:%s #d(3.0 1.0 4.0 1.0 5.0)))".formatted(member));
			sources.add("(print (linalg:%s #f(3.0 1.0 4.0 1.0 5.0)))".formatted(member));
		}
		sources.add("(print (linalg:sum #d((1.0 2.0) (3.0 4.0))))");
		sources.add("(print (linalg:trace #d((1.0 2.0) (3.0 4.0))))");
		sources.add("(print (linalg:trace (linalg:eye 5 'single-float)))");
		// dot's four rank combinations, plus matmul and outer.
		sources.add("(print (linalg:dot #d(1.0 2.0) #d(3.0 4.0)))");
		sources.add("(print (linalg:dot #d((1.0 2.0) (3.0 4.0)) #d(1.0 1.0)))");
		sources.add("(print (linalg:dot #d(1.0 1.0) #d((1.0 2.0) (3.0 4.0))))");
		sources.add("(print (linalg:dot #d((1.0 2.0) (3.0 4.0)) #d((5.0 6.0) (7.0 8.0))))");
		sources.add("(print (linalg:dot #f((1.0 2.0)) #f((1.0) (2.0))))");
		sources.add("(print (linalg:matmul (linalg:eye 3) (linalg:reshape (linalg:arange 9) '(3 3))))");
		sources.add("(print (linalg:dot #d(1.0 2.0) 3.0))");
		sources.add("(print (linalg:outer #d(1.0 2.0) #d(3.0 4.0)))");
		sources.add("(print (linalg:outer #f(1.0 2.0 3.0) #f(3.0 4.0)))");
		sources.add("(print (linalg:outer (linalg:reshape (linalg:arange 6) '(2 3)) #d(1.0 2.0)))");
		// Gaussian elimination is never intercepted, but it calls the intercepted dot.
		sources.add("(print (linalg:inv #d((2.0 0.0) (0.0 4.0))))");
		sources.add("(print (linalg:solve #d((2.0 0.0) (0.0 4.0)) #d(2.0 4.0)))");
		return sources;
	}

	@ParameterizedTest
	@MethodSource("reductionAndProductSources")
	void wasmGcSimdLinalgReductionsAndProductsAreByteIdenticalToTheScalarPath(String lispCode) throws Exception {
		assertLinalgMatchesTheScalarPath(lispCode);
	}

	@Test
	void wasmGcSimdLinalgReductionsWalkElementsRatherThanLaneReducing() throws Exception {
		// An all-negative array is the trap a lane MAX reduce over the zero-padded last
		// group would fall into; these walk elements instead.
		assertThat(compileAndRunVec("(print (linalg:amax #d(-3.0 -1.0 -4.0 -1.0 -5.0)))", true)).isEqualTo("-1.0");
		assertThat(compileAndRunVec("(print (linalg:amin #f(-3.0 -1.0 -4.0)))", true)).isEqualTo("-4.0");
	}

	// The v.M / M.M lane loop (and the outer / transpose lane paths) read matrix rows
	// through the same i8x16.shuffle window as matvec, so every compile-time lane
	// offset variant must be exercised: a 7-column #f matrix puts row k at offset
	// (k * 7) mod 4 = 0, 3, 2, 1 and an odd p also drives the f32 high-half
	// accumulator into the scratch row's sentinel group; the #d sibling covers both
	// f64 offsets. arange values make any misplaced lane visible.
	private static final String LANE_PRODUCT_MATRICES = """
			(defun mk (r c et) (linalg:reshape (linalg:add (linalg:arange 0 (* r c) et) 0.5) (list r c)))
			(defun mkv (n et) (linalg:add (linalg:arange 0 n et) 0.25))
			""";

	static List<String> laneProductSources() {
		return List.of( //
				"(print (linalg:dot (mk 3 5 'single-float) (mk 5 7 'single-float)))", // off
																						// 0,3,2,1
				"(print (linalg:dot (mk 3 5 'double-float) (mk 5 7 'double-float)))", // off
																						// 0,1
				"(print (linalg:dot (mk 4 4 'single-float) (mk 4 4 'single-float)))", // aligned
				"(print (linalg:dot (mk 2 3 'single-float) (mk 3 1 'single-float)))", // p
																						// %
																						// 4
																						// =
																						// 1
				"(print (linalg:dot (mk 2 3 'single-float) (mk 3 2 'single-float)))", // p
																						// %
																						// 4
																						// =
																						// 2
				"(print (linalg:dot (mk 2 3 'double-float) (mk 3 5 'double-float)))", // p
																						// %
																						// 2
																						// =
																						// 1
				"(print (linalg:dot (mkv 5 'single-float) (mk 5 7 'single-float)))", // v.M
				"(print (linalg:dot (mkv 5 'double-float) (mk 5 7 'double-float)))", //
				// a next-row inf sits inside the last window of every row-0 group read;
				// it may only reach the accumulator lanes past p, which are never read
				"(let ((b (mk 3 3 'double-float))) (setf (aref b 2 0) (/ 1.0 0.0))"
						+ " (print (linalg:dot (mk 2 3 'double-float) b)))",
				// outer: group-aligned rows take the lane path, others the element loop
				"(print (linalg:outer (mkv 3 'single-float) (mkv 8 'single-float)))", //
				"(print (linalg:outer (mkv 3 'single-float) (mkv 7 'single-float)))", //
				"(print (linalg:outer (mkv 3 'double-float) (mkv 4 'double-float)))", //
				"(print (linalg:outer (mkv 3 'double-float) (mkv 5 'double-float)))", //
				// transpose: the register-block path needs BOTH dims lane-aligned
				"(print (linalg:transpose (mk 4 8 'single-float)))", // 4x4 blocks
				"(print (linalg:transpose (mk 8 4 'single-float)))", //
				"(print (linalg:transpose (mk 5 8 'single-float)))", // r misaligned
				"(print (linalg:transpose (mk 4 7 'single-float)))", // c misaligned
				"(print (linalg:transpose (mk 4 6 'double-float)))", // 2x2 blocks
				"(print (linalg:transpose (mk 3 6 'double-float)))"); // r misaligned
	}

	@ParameterizedTest
	@MethodSource("laneProductSources")
	void wasmGcSimdLinalgLaneProductsMatchTheScalarPathAtEveryRowLaneOffset(String probe) throws Exception {
		assertLinalgMatchesTheScalarPath(LANE_PRODUCT_MATRICES + probe);
	}

	@Test
	void wasmGcSimdLinalgDeclinedInputsRunTheScalarDefun() throws Exception {
		// Each kernel returns a null reference for an input it cannot read; the call site
		// then invokes the defun. So general (boxed) arrays, mixed widths and plain
		// numbers
		// keep working, and the library's own error still reaches the user.
		assertThat(compileAndRunVec("(print (linalg:add #(1 2 3) #(10 20 30)))", true)).isEqualTo("#d(11.0 22.0 33.0)");
		assertThat(compileAndRunVec("(print (linalg:sum #(1 2 3)))", true)).isEqualTo("6");
		assertThat(compileAndRunVec("(print (linalg:add #d(1.0 2.0) #f(10.0 20.0)))", true)).isEqualTo("#d(11.0 22.0)");
		assertThat(compileAndRunVec("(print (linalg:add #f(1.0 2.0) #d(10.0 20.0)))", true)).isEqualTo("#f(11.0 22.0)");
		assertThat(compileAndRunVec("(print (linalg:add 2 3))", true)).isEqualTo("5");
		assertThat(compileAndRunVec("(print (linalg:dot #d(1.0 2.0) #f(3.0 4.0)))", true)).isEqualTo("11.0");
		assertLinalgMatchesTheScalarPath("(print (linalg:dot #2A((1 2) (3 4)) #(1 1)))");
		// An argument form is evaluated exactly once even when the kernel declines: the
		// fallback reloads the locals rather than recompiling the forms.
		assertThat(compileAndRunVec("""
				(defparameter *n* 0)
				(defun bump () (setq *n* (+ *n* 1)) #(1 2 3))
				(linalg:add (bump) #(1 1 1))
				(print *n*)
				""", true)).isEqualTo("1");
	}

	// The internal CNN window unfolding pair. Batch > 1, channels > 1,
	// stride > 1 and pad > 0 exercise the skipped padding rows and the clipped
	// filter columns; col2im's overlapping windows (stride < filter) accumulate
	// through the promoting _v_get / _v_set round trip, exactly the defun's
	// widen-add-narrow, so both widths stay byte-identical.
	static List<String> im2colAndCol2imSources() {
		return List.of("(print (linalg::%la-im2col (linalg:reshape (linalg:arange 96) '(2 3 4 4)) 2 2 1 0))",
				"(print (linalg::%la-im2col (linalg:reshape (linalg:arange 96) '(2 3 4 4)) 3 3 2 1))",
				"(print (linalg::%la-im2col"
						+ " (linalg:reshape (linalg:arange 0 96 1 'single-float) '(2 3 4 4)) 3 3 2 1))",
				"(print (linalg::%la-col2im (linalg::%la-im2col"
						+ " (linalg:reshape (linalg:arange 96) '(2 3 4 4)) 3 3 1 1) '(2 3 4 4) 3 3 1 1))",
				"(print (linalg::%la-col2im (linalg::%la-im2col"
						+ " (linalg:reshape (linalg:arange 0 96 1 'single-float) '(2 3 4 4)) 3 3 1 1) '(2 3 4 4) 3 3 1 1))",
				// A general boxed rank-4 operand declines to the defun on both paths.
				"(print (linalg::%la-im2col (make-array '(1 1 2 2) :initial-element 1) 2 2 1 0))");
	}

	@ParameterizedTest
	@MethodSource("im2colAndCol2imSources")
	void wasmGcSimdLinalgIm2colAndCol2imAreByteIdenticalToTheScalarPath(String lispCode) throws Exception {
		assertLinalgMatchesTheScalarPath(lispCode);
	}

	@Test
	void wasmGcSimdLinalgSingleFloatReductionsAccumulateInSinglePrecision() throws Exception {
		// The linalg leg of the --simd precision contract, and the only test here that
		// proves the KERNEL ran rather than the defun -- a dead interception would print
		// the scalar 16778239. The same numbers as eval/LinalgSimdTest and
		// JvmLinalgSimdAccelCompilerTest, so the three --simd backends pin each other.
		String dot = "(let ((v (linalg:ones 1024 'single-float))) (setf (aref v 0) 4096.0)"
				+ " (print (round (linalg:dot v v))))";
		assertThat(compileAndRunVec(dot, true)).isEqualTo("16777984");
		assertThat(compileAndRunVec(dot, false)).isEqualTo("16778239");
		String sum = "(let ((v (linalg:ones 1024 'single-float))) (setf (aref v 0) 16777216.0)"
				+ " (print (round (linalg:sum v))))";
		assertThat(compileAndRunVec(sum, true)).isEqualTo("16777984");
		assertThat(compileAndRunVec(sum, false)).isEqualTo("16778239");
		// mean rides on sum, matrix . vector on the vec: GEMV kernel (a dot per row).
		String mean = "(let ((v (linalg:ones 1024 'single-float))) (setf (aref v 0) 16777216.0)"
				+ " (print (round (* 1024 (linalg:mean v)))))";
		assertThat(compileAndRunVec(mean, true)).isEqualTo("16777984");
		assertThat(compileAndRunVec(mean, false)).isEqualTo("16778239");
		String gemv = "(let ((v (linalg:ones 1024 'single-float))) (setf (aref v 0) 4096.0)"
				+ " (print (round (aref (linalg:dot (linalg:reshape v '(1 1024)) v) 0))))";
		assertThat(compileAndRunVec(gemv, true)).isEqualTo("16777984");
		assertThat(compileAndRunVec(gemv, false)).isEqualTo("16778240");
		// The MATRIX PRODUCT is exempt: its lanes run across the output row, not along
		// the
		// summation axis, so its accumulator stays f64 and it matches the oracle exactly.
		String vm = "(let ((v (linalg:ones 1024 'single-float))) (setf (aref v 0) 4096.0)"
				+ " (print (round (aref (linalg:dot v (linalg:reshape v '(1024 1))) 0))))";
		assertThat(compileAndRunVec(vm, true)).isEqualTo("16778240");
		assertThat(compileAndRunVec(vm, false)).isEqualTo("16778240");
		// The #d control: double-float reductions are exact on both paths here.
		String d = "(let ((v (linalg:ones 1024))) (setf (aref v 0) 4096.0) (print (round (linalg:dot v v))))";
		assertThat(compileAndRunVec(d, true)).isEqualTo("16778239");
		assertThat(compileAndRunVec(d, false)).isEqualTo("16778239");
	}

	@Test
	void wasmGcSimdLinalgComposesWithOptimize() throws Exception {
		// --optimize decodes the 0xFD prefix (skipSimd, which now also has to know
		// f32x4.div) and must keep BOTH the kernel and the scalar defun a declined call
		// falls back to -- the call site has a `call` edge to each.
		String source = "(print (linalg:sum (linalg:add (linalg:arange 200) (linalg:arange 200))))"
				+ "(print (linalg:add #(1 2) #(3 4)))";
		assertThat(compileAndRunLinalgSimdOptimized(source)).isEqualTo(compileAndRunVec(source, false));
	}

	// The named element-wise unary ufuncs: both widths, rank 1 and rank 2,
	// exp over reciprocal's bounded range, the wasm defun's own signed-zero edges,
	// and the declined inputs (general boxed arrays, plain numbers) running the
	// defun. square / reciprocal are accelerated transitively through mul / div.
	static List<String> unaryUfuncSources() {
		return List.of("(print (linalg:sqrt (linalg:reshape (linalg:arange 12) '(3 4))))",
				"(print (linalg:abs (linalg:sub (linalg:arange 7) 3)))",
				"(print (linalg:negative (linalg:reshape (linalg:arange 0 6 'single-float) '(2 3))))",
				"(print (linalg:sign (linalg:sub (linalg:arange 0 9 'single-float) 4)))",
				"(print (linalg:square (linalg:sub (linalg:arange 5) 2)))",
				"(print (linalg:square (linalg:arange 0 5 'single-float)))",
				"(print (linalg:reciprocal (linalg:add (linalg:arange 6) 1)))",
				"(print (linalg:exp (linalg:reciprocal (linalg:add (linalg:arange 200) 1))))",
				"(print (linalg:exp (linalg:reciprocal (linalg:add (linalg:arange 0 8 'single-float) 1))))",
				"(print (linalg:log (linalg:add (linalg:arange 200) 1)))",
				"(print (linalg:log (linalg:reshape (linalg:add (linalg:arange 12) 1) '(3 4))))",
				"(print (linalg:log (linalg:add (linalg:arange 0 8 'single-float) 1)))",
				"(print (linalg:tanh (linalg:mul (linalg:sub (linalg:arange 200) 100) 0.03)))",
				"(print (linalg:tanh (linalg:arange 0 8 'single-float)))",
				"(print (linalg:tanh #d(-25.0 -0.0 0.0 25.0)))",
				"(print (linalg:sin (linalg:sub (linalg:arange 200) 100)))",
				"(print (linalg:cos (linalg:reshape (linalg:arange 12) '(3 4))))",
				"(print (linalg:tan (linalg:sub (linalg:arange 0 8 'single-float) 4)))",
				"(print (linalg:sin #d(0.0 -0.0 1.0 -2.5 100.0)))",
				"(print (linalg:asin (linalg:mul (linalg:sub (linalg:arange 200) 100) 0.005)))",
				"(print (linalg:acos (linalg:mul (linalg:arange 0 8 'single-float) 0.005)))",
				"(print (linalg:atan (linalg:sub (linalg:arange 200) 100)))",
				"(print (linalg:atan (linalg:reshape (linalg:arange 0 12 'single-float) '(3 4))))",
				"(print (linalg:sinh (linalg:mul (linalg:sub (linalg:arange 200) 100) 0.05)))",
				"(print (linalg:cosh (linalg:mul (linalg:arange 0 8 'single-float) 0.05)))",
				"(print (linalg:asin #d(0.0 -0.0 1.0 -1.0 0.5)))", "(print (linalg:acos #d(1.0 -1.0 0.0 0.5)))",
				"(print (linalg:sinh #d(0.0 -0.0 0.25 -0.25 0.3)))", "(print (linalg:cosh #d(0.0 -0.0 1.0)))",
				"(print (linalg:negative #d(0.0 -0.0 1.5)))", "(print (linalg:abs #d(-0.0 0.0 -2.5)))",
				"(print (linalg:sign #d(-0.0 0.0 -3.5 3.5)))", "(print (linalg:sqrt #(4 9)))",
				"(print (linalg:abs #(-1 2)))", "(print (linalg:log #(1 4 9)))", "(print (linalg:tanh #(-1 0 1)))",
				"(print (linalg:sin #(-1 0 1)))", "(print (linalg:asin #(0 1)))", "(print (linalg:atan #(-1 0 1)))",
				"(print (linalg:cosh #(0 1)))", "(print (linalg:square 3))");
	}

	@ParameterizedTest
	@MethodSource("unaryUfuncSources")
	void wasmGcSimdLinalgUnaryUfuncsAreByteIdenticalToTheScalarPath(String lispCode) throws Exception {
		assertLinalgMatchesTheScalarPath(lispCode);
	}

	// The comparison-select ufuncs: array-array at both widths
	// and rank 2 (lane gt/lt + bitselect), the f64 scalar broadcast on either side
	// (the lane select), the f32 scalar broadcast against a NOT-f32-representable
	// bound (the widened element loop), the strict-comparison ties/NaN edges, the
	// declined inputs, and clip / relu riding maximum/minimum transitively.
	static List<String> comparisonSelectSources() {
		return List.of(
				"(let ((a (linalg:sub (linalg:arange 200) 100))) (print (linalg:maximum a (linalg:negative a))))",
				"(let ((a (linalg:sub (linalg:arange 0 8 'single-float) 4))) (print (linalg:minimum a (linalg:negative a))))",
				"(print (linalg:maximum (linalg:reshape (linalg:arange 12) '(3 4)) (linalg:negative (linalg:reshape (linalg:arange 12) '(3 4)))))",
				"(print (linalg:maximum (linalg:sub (linalg:arange 200) 100) 3.0))",
				"(print (linalg:minimum 3.0 (linalg:sub (linalg:arange 200) 100)))",
				"(print (linalg:maximum (linalg:arange 0 8 'single-float) 4.3))",
				"(print (linalg:minimum 4.3 (linalg:arange 0 8 'single-float)))",
				"(print (linalg:maximum #d(-0.0 0.0) #d(0.0 -0.0)))",
				"(print (linalg:minimum #d(-0.0 0.0) #d(0.0 -0.0)))", "(print (linalg:maximum #d(-0.0) 0.0))",
				"(print (linalg:maximum (linalg:mul (linalg:ones 2) (/ 0.0 0.0)) #d(1.0 2.0)))",
				"(print (linalg:maximum #d(1.0 2.0) (linalg:mul (linalg:ones 2) (/ 0.0 0.0))))",
				"(print (linalg:clip (linalg:sub (linalg:arange 200) 100) -50.0 50.0))",
				"(print (linalg:clip (linalg:arange 0 8 'single-float) 1.3 5.3))",
				"(print (linalg:relu (linalg:sub (linalg:arange 200) 100)))",
				"(print (linalg:relu (linalg:reshape (linalg:sub (linalg:arange 0 12 'single-float) 6) '(3 4))))",
				"(print (linalg:relu #d(-0.0 0.0)))",
				"(print (linalg:clip (linalg:mul (linalg:ones 1) (/ 0.0 0.0)) -1.0 1.0))",
				"(print (linalg:maximum #(1 5 3) #(4 2 3)))", "(print (linalg:minimum #d(1.0 5.0) #f(4.0 2.0)))",
				"(print (linalg:maximum 2 3))");
	}

	@ParameterizedTest
	@MethodSource("comparisonSelectSources")
	void wasmGcSimdLinalgComparisonSelectsAreByteIdenticalToTheScalarPath(String lispCode) throws Exception {
		assertLinalgMatchesTheScalarPath(lispCode);
	}

	/** {@code --simd --optimize} over the linalg library, run under wasm-GC. */
	private static String compileAndRunLinalgSimdOptimized(String lispCode) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.VecLibrary
			.process(am.ik.rontolisp.eval.LinalgLibrary.process(LispReader.readAllFromString(lispCode)));
		byte[] wasmBytes = new WasmLispCompiler(false, false, false, true, false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "--wasm", "gc", path("test.wasm"));
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	// --- IEEE-754 float edge semantics -----------------------------------------------

	@Test
	void unaryMinusOfFloatLiteralFormNegates() throws Exception {
		// The double-literal fast path used to compile unary minus as identity
		assertThat(compileAndRun("(print (- 5.0))")).isEqualTo("-5.0");
		assertThat(compileAndRun("(print (- (* 2.0 3.0)))")).isEqualTo("-6.0");
		assertThat(compileAndRun("(print (- -1.5))")).isEqualTo("1.5");
		assertThat(compileAndRun("(print (- 0.0))")).isEqualTo("-0.0");
	}

	@Test
	void nanComparisonsAreUnorderedOnBothPaths() throws Exception {
		// literal path: per-operator f64 opcodes, IEEE already
		assertThat(compileAndRun("(print (list (< (/ 0.0 0.0) 1.0) (<= (/ 0.0 0.0) 1.0) (> (/ 0.0 0.0) 1.0)"
				+ " (>= (/ 0.0 0.0) 1.0) (= (/ 0.0 0.0) (/ 0.0 0.0))))"))
			.isEqualTo("(NIL NIL NIL NIL NIL)");
		// The no-literal forms used to funnel through the signum _rat_cmp,
		// which answered "equal" for NaN; /= binds temps, so it ALWAYS took that path
		assertThat(compileAndRun("(let ((n (/ 0.0 0.0)) (one 1.0))"
				+ " (print (list (< n one) (<= n one) (> n one) (>= n one) (= n n) (/= n n))))"))
			.isEqualTo("(NIL NIL NIL NIL NIL T)");
		assertThat(compileAndRun("(print (/= (/ 0.0 0.0) (/ 0.0 0.0)))")).isEqualTo("T");
		assertThat(compileAndRun("(let ((z (* -1.0 0.0)) (p (* 1.0 0.0))) (print (= z p)))")).isEqualTo("T");
	}

	@Test
	void floatPrinterHandlesSignedZeroInfinityNanAndLargeMagnitudes() throws Exception {
		// The printer used to trap on |x| >= 2^31,
		// on Infinity and on NaN, and dropped -0.0's sign (is_neg was `x < 0.0`)
		assertThat(compileAndRun("(print -0.0)")).isEqualTo("-0.0");
		assertThat(compileAndRun("(print (* -1.0 0.0))")).isEqualTo("-0.0");
		assertThat(compileAndRun("(print (/ 1.0 0.0))")).isEqualTo("Infinity");
		assertThat(compileAndRun("(print (/ -1.0 0.0))")).isEqualTo("-Infinity");
		assertThat(compileAndRun("(print (/ 0.0 0.0))")).isEqualTo("NaN");
		assertThat(compileAndRun("(print (* 1.5 (expt 10.0 12)))")).isEqualTo("1500000000000.0");
		assertThat(compileAndRun("(print (- (* 2.5 (expt 10.0 15))))")).isEqualTo("-2500000000000000.0");
		assertThat(compileAndRun("(print (expt 10.0 19))")).isEqualTo("1.0E19");
		// print of the returned STRING prin1-quotes it; the point is princ-to-string
		// shares the fixed core and no longer traps
		assertThat(compileAndRun("(print (princ-to-string (/ 1.0 0.0)))")).isEqualTo("\"Infinity\"");
	}

	@Test
	void minMaxDoubleLiteralPathFollowsF64MinMax() throws Exception {
		assertThat(compileAndRun("(print (min 0.0 -0.0))")).isEqualTo("-0.0");
		assertThat(compileAndRun("(print (max -0.0 0.0))")).isEqualTo("0.0");
	}

	// --- Condition catching: the wasm-EH mirrors of the JVM handler-case pins.

	@Test
	void ehHandlerCaseCatchesTypedErrorByClass() throws Exception {
		assertThat(compileAndRunEh("""
				(define-condition hc-err (error) ((v :initarg :v :reader hc-err-v)))
				(print (handler-case (error 'hc-err :v 7)
				         (hc-err (e) (list :caught (hc-err-v e)))))
				""")).isEqualTo("(:CAUGHT 7)");
	}

	@Test
	void ehHandlerCaseCatchesPlainErrorAsError() throws Exception {
		assertThat(compileAndRunEh("""
				(print (handler-case (error "boom ~a" 1)
				         (error (e) (list :caught (simple-condition-format-control e)))))
				""")).isEqualTo("(:CAUGHT \"boom 1\")");
	}

	@Test
	void ehHandlerCaseCatchesErrorFromCalledFunction() throws Exception {
		assertThat(compileAndRunEh("""
				(defun hc-thrower () (error "deep"))
				(print (handler-case (hc-thrower) (error (e) :caught)))
				""")).isEqualTo(":CAUGHT");
	}

	@Test
	void ehHandlerCaseDispatchesByHierarchyAndClauseOrder() throws Exception {
		assertThat(compileAndRunEh("""
				(define-condition hc-sub (parse-error) ())
				(print (handler-case (error 'hc-sub)
				         (warning (w) :warning)
				         (parse-error (e) :parse)
				         (error (e) :error)))
				""")).isEqualTo(":PARSE");
	}

	@Test
	void ehHandlerCaseRethrowsUnmatchedToOuterHandler() throws Exception {
		assertThat(compileAndRunEh("""
				(define-condition hc-warn2 (warning) ())
				(print (handler-case
				           (handler-case (error 'hc-warn2)
				             (error (e) :inner))
				         (warning (w) :outer)))
				""")).isEqualTo(":OUTER");
	}

	@Test
	void ehHandlerCaseUnmatchedErrorKeepsTrapShape() throws Exception {
		// An uncaught condition (no matching clause anywhere) is converted back into a
		// trap by the top-level catch_all wrapper, so the host-visible failure class is
		// unchanged from the pre-EH `unreachable`.
		assertThat(compileAndRunEhExpectTrap("(handler-case (error \"boom\") (warning (w) :w))"))
			.contains("unreachable");
	}

	@Test
	void ehHandlerCaseNoErrorClauseReceivesValue() throws Exception {
		assertThat(compileAndRunEh("(print (handler-case (+ 1 2) (error (e) :err) (:no-error (v) (list :ok v))))"))
			.isEqualTo("(:OK 3)");
	}

	@Test
	void ehHandlerCaseCatchesSignal() throws Exception {
		assertThat(compileAndRunEh(
				"(print (handler-case (progn (signal \"quiet\") :not-raised) (condition (c) :raised))) (print (signal \"quiet\"))"))
			.isEqualTo(":RAISED\nNIL");
	}

	@Test
	void ehHandlerCaseRunsUnwindProtectCleanupBeforeHandler() throws Exception {
		assertThat(compileAndRunEh("""
				(let ((log nil))
				  (print (handler-case
				             (unwind-protect (error "boom") (setq log (cons :cleaned log)))
				           (error (e) (cons :caught log)))))
				""")).isEqualTo("(:CAUGHT :CLEANED)");
	}

	@Test
	void ehHandlerCaseReturnExitsProtectedRegion() throws Exception {
		// A return inside the protected form exits the loop through the exit
		// trampoline, which restores the handler depth, so a later unhandled signal
		// still falls through to nil.
		assertThat(compileAndRunEh("""
				(print (dolist (x '(1 2 3))
				         (handler-case (when (= x 2) (return :done)) (error (e) :err))))
				(print (signal "after"))
				""")).isEqualTo(":DONE\nNIL");
	}

	@Test
	void ehIgnoreErrors() throws Exception {
		assertThat(compileAndRunEh("(print (ignore-errors (error \"boom\"))) (print (ignore-errors (+ 1 2)))"))
			.isEqualTo("NIL\n3");
	}

	@Test
	void ehUnwindProtectRunsCleanupOnNormalExit() throws Exception {
		assertThat(compileAndRunEh("(print (unwind-protect (+ 1 2) (print :cleanup)))")).isEqualTo(":CLEANUP\n3");
	}

	@Test
	void ehUnwindProtectReturnRunsCleanupsInnermostFirst() throws Exception {
		// The return-exit trampolines cascade outward: inner cleanup, then outer.
		assertThat(compileAndRunEh("""
				(let ((log nil))
				  (dolist (x '(1))
				    (unwind-protect
				        (unwind-protect (return :nested)
				          (setq log (cons :inner log)))
				      (setq log (cons :outer log))))
				  (print log))
				""")).isEqualTo("(:OUTER :INNER)");
	}

	@Test
	void ehUnwindProtectCleanupRunsOnErrorUnwindThenTrapShapeKept() throws Exception {
		// The cleanup runs on the error path (visible print), then the rethrown
		// condition escapes the top level and keeps the trap shape.
		List<LispVal> program = LispReader.readAllFromString("(unwind-protect (error \"up-boom\") (print :cleaned))");
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc", "-W", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).isNotZero();
		assertThat(result.getStdout().trim()).isEqualTo(":CLEANED");
		assertThat(result.getStderr()).contains("unreachable");
	}

	@Test
	void ehSignalYieldsNilWhenUnhandled() throws Exception {
		// EH mode on (ignore-errors elsewhere), but the signal itself has no handler:
		// the depth global is 0, so %signal-cond falls through to nil.
		assertThat(compileAndRunEh("(ignore-errors 1) (print (signal \"quiet\"))")).isEqualTo("NIL");
	}

	// --- rontolisp:wit-import under --component: rich PARAMETERS across the canonical
	// ABI
	//
	// These run against wasmtime's own hosts, which is the only way to know a lowered
	// parameter is right: a component import is checked against the host's real instance
	// type, and the host then answers with what it actually received.

	// The full compile path of a wit-import program: the directive is inlined against a
	// WIT file (as the CLI does), then the WIT runtime library is spliced in, then the
	// component is built.
	private static byte[] compileWitImportComponent(String wit, String lispCode) throws Exception {
		java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("wit-import");
		java.nio.file.Files.writeString(dir.resolve("iface.wit"), wit);
		List<LispVal> program = am.ik.rontolisp.eval.WitImportInliner.inline(LispReader.readAllFromString(lispCode),
				dir.toString(), am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT,
				am.ik.rontolisp.eval.SourceLoader.fileSystem());
		return new WasmLispCompiler(false, true).compile(am.ik.rontolisp.eval.WitLibrary.process(program));
	}

	// A subset of the real wasi:http/types@0.2.0: enough of it to construct an outgoing
	// request and read back its method. `method` is the variant that makes this worth
	// running -- most cases carry nothing, `other` carries a string.
	private static final String WASI_HTTP_TYPES_WIT = """
			package wasi:http@0.2.0;

			interface types {
			  variant method {
			    get,
			    head,
			    post,
			    put,
			    delete,
			    connect,
			    options,
			    trace,
			    patch,
			    other(string)
			  }

			  resource fields {
			    constructor();
			  }

			  type headers = fields;

			  resource outgoing-request {
			    constructor(headers: headers);

			    method: func() -> method;

			    set-method: func(method: method) -> result;
			  }
			}
			""";

	@Test
	void componentImportLowersAVariantParameter() throws Exception {
		// wasmtime's real wasi:http host receives the method we lower and answers with
		// the
		// method it holds -- so a payload-less case and a string-payload case both make
		// the
		// round trip. The last one is the error arm: the host rejects a syntactically
		// invalid method, and the result signals rontolisp:wit-error.
		byte[] component = compileWitImportComponent(WASI_HTTP_TYPES_WIT, """
				(rontolisp:wit-import "iface.wit" :interface "wasi:http/types@0.2.0" :package http)
				(let ((req (http:outgoing-request-new (http:fields-new))))
				  (print (http:outgoing-request-method req))
				  (http:outgoing-request-set-method req :post)
				  (print (http:outgoing-request-method req))
				  (http:outgoing-request-set-method req '(:other . "PATCH"))
				  (print (http:outgoing-request-method req))
				  (handler-case (http:outgoing-request-set-method req '(:other . "bad method"))
				    (rontolisp:wit-error (e) (print :rejected))))
				""");
		wasmtime.copyFileToContainer(Transferable.of(component), "/tmp/wit-variant.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S",
				"http=y", "/tmp/wit-variant.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo(":GET\n:POST\n(:OTHER . \"PATCH\")\n:REJECTED");
	}

	// A subset of the real wasi:sockets/types@0.3.0: `create` takes an enum, `bind` takes
	// a
	// variant whose case payload is a record carrying a tuple -- the deepest parameter
	// shape the canonical ABI flattens.
	private static final String WASI_SOCKETS_TYPES_WIT = """
			package wasi:sockets@0.3.0;

			interface types {
			  variant error-code {
			    access-denied,
			    not-supported,
			    invalid-argument,
			    out-of-memory,
			    timeout,
			    invalid-state,
			    address-not-bindable,
			    address-in-use,
			    remote-unreachable,
			    connection-refused,
			    connection-broken,
			    connection-reset,
			    connection-aborted,
			    datagram-too-large,
			    other(option<string>)
			  }

			  enum ip-address-family {
			    ipv4,
			    ipv6
			  }

			  type ipv4-address = tuple<u8, u8, u8, u8>;
			  type ipv6-address = tuple<u16, u16, u16, u16, u16, u16, u16, u16>;

			  record ipv4-socket-address {
			    port: u16,
			    address: ipv4-address
			  }

			  record ipv6-socket-address {
			    port: u16,
			    flow-info: u32,
			    address: ipv6-address,
			    scope-id: u32
			  }

			  variant ip-socket-address {
			    ipv4(ipv4-socket-address),
			    ipv6(ipv6-socket-address)
			  }

			  resource tcp-socket {
			    create: static func(address-family: ip-address-family) -> result<tcp-socket, error-code>;

			    bind: func(local-address: ip-socket-address) -> result<_, error-code>;

			    get-local-address: func() -> result<ip-socket-address, error-code>;
			  }
			}
			""";

	@Test
	void componentImportLowersARecordInsideAVariantParameter() throws Exception {
		// The host binds the socket to the address we lower and then hands the address
		// back
		// through get-local-address: proof the record (a keyword plist) and the tuple (a
		// positional list) inside the variant case arrived intact. The port is ephemeral
		// (bind 0), so only the family and the address are pinned.
		byte[] component = compileWitImportComponent(WASI_SOCKETS_TYPES_WIT, """
				(rontolisp:wit-import "iface.wit" :interface "wasi:sockets/types@0.3.0" :package sock)
				(let ((s (sock:tcp-socket-create :ipv4)))
				  (sock:tcp-socket-bind s '(:ipv4 :port 0 :address (127 0 0 1)))
				  (let ((addr (sock:tcp-socket-get-local-address s)))
				    (print (list (car addr) (getf (cdr addr) :address)))
				    (print (> (getf (cdr addr) :port) 0))))
				""");
		wasmtime.copyFileToContainer(Transferable.of(component), "/tmp/wit-record.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S",
				"inherit-network=y", "/tmp/wit-record.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("(:IPV4 (127 0 0 1))\nT");
	}

	@Test
	void componentImportLowersAResultParameter() throws Exception {
		// wasi:cli/exit's `exit: func(status: result)` -- the cheapest result PARAMETER
		// there is, and the host reports which arm it received as the process exit code.
		// The ok arm is written as the bare keyword, the error arm as the envelope cons:
		// both are the shape a result RESULT lifts to.
		String wit = """
				package wasi:cli@0.3.0;

				interface exit {
				  exit: func(status: result);
				}
				""";
		byte[] ok = compileWitImportComponent(wit, """
				(rontolisp:wit-import "iface.wit" :interface "wasi:cli/exit@0.3.0" :package cli)
				(print :bye)
				(cli:exit :ok)
				(print :unreachable)
				""");
		wasmtime.copyFileToContainer(Transferable.of(ok), "/tmp/wit-exit-ok.component.wasm");
		ExecResult okResult = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
				"/tmp/wit-exit-ok.component.wasm");
		assertThat(okResult.getExitCode()).as("stderr: %s", okResult.getStderr()).isZero();
		assertThat(okResult.getStdout().trim()).isEqualTo(":BYE");

		byte[] err = compileWitImportComponent(wit, """
				(rontolisp:wit-import "iface.wit" :interface "wasi:cli/exit@0.3.0" :package cli)
				(print :bye)
				(cli:exit '(:error))
				(print :unreachable)
				""");
		wasmtime.copyFileToContainer(Transferable.of(err), "/tmp/wit-exit-err.component.wasm");
		ExecResult errResult = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
				"/tmp/wit-exit-err.component.wasm");
		assertThat(errResult.getExitCode()).isEqualTo(1);
		assertThat(errResult.getStdout().trim()).isEqualTo(":BYE");
	}

	// Every parameter shape the canonical ABI flattens, including the ones no wasmtime
	// host
	// happens to take: an f32 / f64 / u64 payload inside a variant (the join coercions --
	// a float flat rides in an integer local as its bit pattern), a record of floats,
	// strings and options, a tuple, a char, an option of a variant, a result whose arms
	// are
	// a record and a variant, and a result nested in a result.
	private static final String EXOTIC_PARAM_WIT = """
			package local:probe@0.1.0;

			interface p {
			  variant num {
			    none-of-it,
			    small(u8),
			    wide(u64),
			    single(f32),
			    double(f64),
			    text(string)
			  }

			  record pt {
			    x: f64,
			    y: s64,
			    label: string,
			    tag: option<string>
			  }

			  enum color { red, green }

			  record wide { a: u64, b: u64, c: u64, d: u64, e: u64 }

			  record size-payload {
			    field-name: option<string>,
			    field-size: option<u32>
			  }

			  // `wasi:http`'s error-code in miniature: the shape that decides how much
			  // scratch a wrapper needs -- result -> variant -> option -> record ->
			  // option<string>, five levels down.
			  variant deep {
			    timeout,
			    body-size(option<u64>),
			    header-size(option<size-payload>),
			    trailer-size(size-payload),
			    internal(option<string>)
			  }

			  record single { x: s32 }

			  resource thing {
			    constructor();
			    take-num: func(n: num) -> u32;
			    take-pt: func(p: pt) -> u32;
			    take-tuple: func(t: tuple<s32, f64, string>) -> u32;
			    take-char: func(c: char) -> u32;
			    take-opt-num: func(n: option<num>) -> u32;
			    take-res: func(r: result<pt, num>) -> u32;
			    take-color: func(c: color) -> u32;
			    take-nested: func(v: result<num, color>) -> u32;
			    take-wide: func(w: wide) -> u32;
			    take-deep: func(d: result<u32, deep>) -> u32;
			    take-many: func(a: option<s64>, b: option<s64>, c: option<s64>, d: option<s64>, e: option<s64>) -> u32;
			    get-single: func() -> single;
			  }
			}
			""";

	@Test
	void componentImportLowersEveryFlattenableParameterShape() throws Exception {
		// Nothing implements this probe interface, so the component cannot LINK -- but
		// wasmtime VALIDATES the bytes before it links, and validation is what
		// type-checks
		// every lowered instruction: the flats of each variant case against the joined
		// signature, the reinterprets, the locals, the stack balance of the case
		// dispatch.
		// So reaching the linker error IS the assertion. (The shapes a real wasmtime host
		// does take are pinned by value in the three tests above.)
		byte[] component = compileWitImportComponent(EXOTIC_PARAM_WIT, """
				(rontolisp:wit-import "iface.wit" :interface "local:probe/p@0.1.0" :package p)
				(let ((th (p:thing-new)))
				  (p:thing-take-num th :none-of-it)
				  (p:thing-take-num th '(:small . 7))
				  (p:thing-take-num th '(:wide . 9007199254740993))
				  (p:thing-take-num th '(:single . 1.5))
				  (p:thing-take-num th '(:double . 2.5))
				  (p:thing-take-num th '(:text . "hi"))
				  (p:thing-take-pt th '(:x 1.5 :y 42 :label "L" :tag nil))
				  (p:thing-take-tuple th (list 1 2.5 "t"))
				  (p:thing-take-char th #\\A)
				  (p:thing-take-opt-num th nil)
				  (p:thing-take-opt-num th '(:double . 3.5))
				  (p:thing-take-res th '(:ok :x 1.0 :y 2 :label "k" :tag "T"))
				  (p:thing-take-res th '(:error :text . "bad"))
				  (p:thing-take-color th :green)
				  (p:thing-take-nested th '(:ok :single . 0.5))
				  (p:thing-take-wide th '(:a 1 :b 2 :c 3 :d 4 :e 5))
				  (p:thing-take-deep th '(:ok . 200))
				  (p:thing-take-deep th '(:error :header-size :field-name "x" :field-size 9))
				  (p:thing-take-deep th '(:error :internal . "boom"))
				  (p:thing-take-many th 1 2 3 4 5)
				  (p:thing-get-single th))
				""");
		wasmtime.copyFileToContainer(Transferable.of(component), "/tmp/wit-exotic.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
				"/tmp/wit-exotic.component.wasm");
		assertThat(result.getExitCode()).isNotZero();
		assertThat(result.getStderr()).as("the bytes must reach the LINKER, i.e. validate")
			.contains("was not found in the linker")
			.doesNotContain("failed to parse");
	}

	// --- rontolisp:wit-import under --component: a FAMILY of interrelated interfaces

	// The REAL wasi:http@0.3.0 the repo vendors, folded into one document: each file's
	// `package wasi:http@0.3.0;` header is dropped in favour of one nested package
	// block, plus the wasi:clocks/types shim its `use` needs and -- appended inside the
	// types interface -- the same TRANSPARENT stream/future type aliases http.lisp uses
	// (an alias is structural and referenced by no function, so the instance types stay
	// the host's real ones; the aliases only drive the derived async built-in
	// bindings). Trimming the interfaces by hand would defeat the test -- the
	// component-model subtype check compares the instance types we emit against the
	// HOST's real ones, so ours have to be structurally the real ones.
	private static String vendoredWasiHttpWit() throws Exception {
		java.nio.file.Path deps = java.nio.file.Path.of("src", "wasm-component", "deps");
		String types = witBody(deps.resolve("http").resolve("types.wit"));
		String handler = witBody(deps.resolve("http").resolve("handler.wit"));
		String aliases = """
				    type body-stream = stream<u8>;
				    type trailers-future = future<result<option<trailers>, error-code>>;
				    type transmit-future = future<result<_, error-code>>;
				""";
		int close = types.lastIndexOf('}');
		types = types.substring(0, close) + aliases + types.substring(close);
		return "package root:fetchprobe;\n\npackage wasi:clocks@0.3.0 {\n  interface types {\n"
				+ "    type duration = u64;\n  }\n}\n\npackage wasi:http@0.3.0 {\n" + types + handler + "}\n";
	}

	// A vendored WIT file's interfaces, without its `package ...;` header and the
	// `@since` gates our parser tolerates but the nested-package fold does not need.
	private static String witBody(java.nio.file.Path wit) throws Exception {
		String text = java.nio.file.Files.readString(wit);
		if (text.startsWith("package ")) {
			text = text.substring(text.indexOf(';') + 1);
		}
		return text.lines()
			.filter(line -> !line.strip().startsWith("@since"))
			.map(line -> line + "\n")
			.reduce("", String::concat);
	}

	@Test
	void componentImportLetsLispDriveWasiHttpAcrossSeparatelyImportedInterfaces() throws Exception {
		// A fetch written entirely in Lisp over the wit-imported wasi:http@0.3.0
		// types + client interfaces -- USER code walking the exact path the built-in
		// http.lisp does: the async-lowered `client.send` promise, the stream/future
		// built-ins bound off transparent type aliases, and -- the load-bearing part --
		// a `request` resource minted by the types instance crossing into the
		// SEPARATELY imported client instance, which only works because a `use`d
		// resource is the same nominal type on both sides.
		// A unit test can only say the bytes name the import; only a host that ANSWERS --
		// wasmtime's real wasi:http, under -S http=y -- proves the types unified, because
		// otherwise the component fails the subtype check at instantiation (or reads the
		// stream out of the wrong handle table).
		// The backend is a plain rontolisp serve component, so the test stays offline.
		byte[] backendBytes = compileServeComponent("""
				(defun handle (request)
				  (list :status 200
				        :body (concatenate 'string "backend " (getf request :path))))
				(rontolisp:http-handler 'handle)
				""", null);
		// blocking-read signals rontolisp:wit-error on the `closed` arm (a WIT result's
		// error arm), so reading to EOF needs handler-case -- hence -W exceptions=y.
		byte[] fetchBytes = compileWitImportComponent(vendoredWasiHttpWit(), """
				(rontolisp:wit-import "iface.wit" :interface "wasi:http/types@0.3.0" :package http)
				(rontolisp:wit-import "iface.wit" :interface "wasi:http/client@0.3.0" :package client)

				;; body-stream-read answers the chunk immediately, or -- when the host
				;; reports the read in flight -- a PENDING future the scheduler settles;
				;; await passes an immediate chunk through and suspends on the pending
				;; one, so stream reads belong in an async function.
				(rontolisp:async-defun read-all (stream acc)
				  (let ((chunk (rontolisp:await (http:body-stream-read stream))))
				    (if (or (null chunk) (= (length chunk) 0))
				        acc
				        (rontolisp:await (read-all stream (concatenate 'string acc chunk))))))

				(rontolisp:async-defun get-url (authority path)
				  (let* ((trailers (http:trailers-future-new))
				         (reqpair (http:request-new (http:fields-new) nil (car trailers) nil))
				         (req (car reqpair)))
				    (http:request-set-method req :get)
				    ;; the scheme variant's cases are HTTP / HTTPS, and keywords are
				    ;; case-preserving.
				    (http:request-set-scheme req :HTTP)
				    (http:request-set-authority req authority)
				    (http:request-set-path-with-query req path)
				    ;; send is an `async func`: the generated binding starts the subtask and
				    ;; returns an ordinary promise whose await drives the waitable-set.
				    (let ((promise (client:send req)))
				      ;; resolve the request-side trailers (ok none) so the host can finish
				      ;; sending, and drop the transmission-result future unread.
				      (http:trailers-future-write (cdr trailers) (cons :ok nil))
				      (http:transmit-future-drop-readable (car (cdr reqpair)))
				      (let* ((response (rontolisp:await promise))
				             (status (http:response-get-status-code response))
				             (res (http:transmit-future-new))
				             ;; consume-body MOVES the response and takes a guest-created
				             ;; future through which we report our side's outcome.
				             (pair (http:response-consume-body response (car res)))
				             (stream (car pair))
				             (text (rontolisp:await (read-all stream ""))))
				        (http:body-stream-drop-readable stream)
				        (http:trailers-future-drop-readable (car (cdr pair)))
				        (http:transmit-future-write (cdr res) :ok)
				        (list :status status :body text)))))

				(let ((r (rontolisp:await (get-url "127.0.0.1:8086" "/hello"))))
				  (print (getf r :status))
				  (print (getf r :body)))
				""");
		wasmtime.copyFileToContainer(Transferable.of(backendBytes), "/tmp/wit-fetch-backend.wasm");
		wasmtime.copyFileToContainer(Transferable.of(fetchBytes), "/tmp/wit-fetch.component.wasm");
		// Wait for the backend before running the fetch: it has one shot, and a
		// connection
		// refused would be reported as a wit-error, not as this test's answer.
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8086 /tmp/wit-fetch-backend.wasm >/tmp/wit-fetch-backend.log 2>&1 &"
						+ " for i in $(seq 1 60); do curl -sf http://127.0.0.1:8086/hello >/dev/null && break; sleep 0.25; done;"
						+ " curl -sf http://127.0.0.1:8086/hello >/dev/null"
						+ " || { echo 'backend never came up' 1>&2; cat /tmp/wit-fetch-backend.log 1>&2; exit 1; };"
						+ " wasmtime run -W gc=y -W exceptions=y -S http=y" + " /tmp/wit-fetch.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("200\n\"backend /hello\"");
	}

	@Test
	void componentImportDropsResourcesSoALispRequestCanCarryABody() throws Exception {
		// The case the GET above dodges: a request that CARRIES a body. In 0.3 the body
		// is a guest-created stream<u8> handed to request.new, and the transfer only
		// completes when the guest closes the contents stream (drop-writable, the
		// end-of-stream signal) AND resolves the trailers future -- an unfinished body
		// is an error the host propagates. The drop built-ins are not WIT functions, so
		// nothing in an interface's bound surface can perform them; this program sends
		// its body only because the alias-derived `<alias>-drop-*` members lower to the
		// canonical drop built-ins.
		// The backend echoes the request body back, so the assertion proves the body
		// ARRIVED -- not merely that the request was accepted.
		byte[] backendBytes = compileServeComponent("""
				(rontolisp:async-defun handle (request)
				  (let ((body (rontolisp:await (rontolisp:read-all (getf request :body)))))
				    (list :status 200
				          :body (concatenate 'string "echo " body))))
				(rontolisp:http-handler 'handle)
				""", null);
		byte[] postBytes = compileWitImportComponent(vendoredWasiHttpWit(), """
				(rontolisp:wit-import "iface.wit" :interface "wasi:http/types@0.3.0" :package http)
				(rontolisp:wit-import "iface.wit" :interface "wasi:http/client@0.3.0" :package client)

				;; a read the host has in flight is a PENDING future: await it (an
				;; immediate chunk passes through), so read-all is an async function.
				(rontolisp:async-defun read-all (stream acc)
				  (let ((chunk (rontolisp:await (http:body-stream-read stream))))
				    (if (or (null chunk) (= (length chunk) 0))
				        acc
				        (rontolisp:await (read-all stream (concatenate 'string acc chunk))))))

				(rontolisp:async-defun post-url (authority path body)
				  ;; content-length goes on with fields.append -- its value (a field-value =
				  ;; list<u8>) crosses as a byte string -- BEFORE the fields are handed to
				  ;; request.new.
				  (let* ((headers (http:fields-new)))
				    (http:fields-append headers "content-length" (princ-to-string (length body)))
				    (let* ((contents (http:body-stream-new))
				           (trailers (http:trailers-future-new))
				           (reqpair (http:request-new headers (car contents) (car trailers) nil))
				           (req (car reqpair)))
				      (http:request-set-method req :post)
				      (http:request-set-scheme req :HTTP)
				      (http:request-set-authority req authority)
				      (http:request-set-path-with-query req path)
				      ;; start the async send FIRST: the body write below rendezvouses with
				      ;; the host's eager read of the contents stream.
				      (let ((promise (client:send req)))
				        (http:body-stream-write (cdr contents) body)
				        ;; THE lines this test exists for: close the contents stream and
				        ;; resolve the trailers future, or the body never completes.
				        (http:body-stream-drop-writable (cdr contents))
				        (http:trailers-future-write (cdr trailers) (cons :ok nil))
				        (http:transmit-future-drop-readable (car (cdr reqpair)))
				        (let* ((response (rontolisp:await promise))
				               (status (http:response-get-status-code response))
				               (res (http:transmit-future-new))
				               (pair (http:response-consume-body response (car res)))
				               (stream (car pair))
				               (text (rontolisp:await (read-all stream ""))))
				          (http:body-stream-drop-readable stream)
				          (http:trailers-future-drop-readable (car (cdr pair)))
				          (http:transmit-future-write (cdr res) :ok)
				          (list :status status :body text))))))

				(let ((r (rontolisp:await (post-url "127.0.0.1:8087" "/echo" "hello from a lisp POST"))))
				  (print (getf r :status))
				  (print (getf r :body)))
				""");
		wasmtime.copyFileToContainer(Transferable.of(backendBytes), "/tmp/wit-post-backend.wasm");
		wasmtime.copyFileToContainer(Transferable.of(postBytes), "/tmp/wit-post.component.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8087 /tmp/wit-post-backend.wasm >/tmp/wit-post-backend.log 2>&1 &"
						+ " for i in $(seq 1 60); do curl -sf http://127.0.0.1:8087/echo -d probe >/dev/null && break; sleep 0.25; done;"
						+ " curl -sf http://127.0.0.1:8087/echo -d probe >/dev/null"
						+ " || { echo 'backend never came up' 1>&2; cat /tmp/wit-post-backend.log 1>&2; exit 1; };"
						+ " wasmtime run -W gc=y -W exceptions=y -S http=y" + " /tmp/wit-post.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("200\n\"echo hello from a lisp POST\"");
	}

	// --- the intra-instance multi-task probe: two tasks in ONE component instance ---
	//
	// Real hosts never exercise the callback driver's cross-task half today (wasmtime
	// serve re-instantiates per request), so this probe drives it directly, in the
	// spike's driver style but through the GENERATED machinery: a rontolisp core with
	// a test-designated CALLBACK-lifted export is wrapped into a self-contained
	// component together with a hand-assembled driver core module. The driver
	// async-lowers a call to `begin`, which suspends on an internal pending future --
	// a live callback task parked on WAIT | (set << 4) with its context slot and
	// doorbell armed -- then calls `poke` (a SECOND task in the same instance), whose
	// settle finds a waiter owned by another task, defers it to begin's ready list and
	// rings begin's doorbell. The host delivers the doorbell event to the core's
	// async_cb (task identity restored from context slot 0), the drained waiter
	// resumes begin's frame ON ITS OWN TASK, begin delivers 42 through task.return and
	// EXITs, and the driver observes the subtask RETURNED event with the result. Any
	// deviation traps (non-zero exit).
	@Test
	void callbackProbeInterleavesTwoTasksInOneInstance() throws Exception {
		byte[] component = buildCallbackProbeComponent();
		wasmtime.copyFileToContainer(Transferable.of(component), "/tmp/cb-probe.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
				"/tmp/cb-probe.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
	}

	// Fixed scratch of the probe driver, in its own copy of the shared memory module
	// (page 5 is free there: the driver component has no WAT adapter).
	private static final int PROBE_RETPTR = 0x50100;

	private static final int PROBE_EVTP = 0x50110;

	// A component may not re-enter itself (a lowered call of its own lifted export
	// traps with "cannot enter component instance"), so the probe nests TWO components:
	// A (the rontolisp core with the callback-lifted `begin` + sync `poke`) and B (the
	// hand-assembled driver importing them), the multi-task interleaving happening
	// inside A's ONE instance.
	private static byte[] buildCallbackProbeComponent() throws Exception {
		byte[] mem;
		try (java.io.InputStream in = WasmLispCompilerIntegrationTest.class.getResourceAsStream("component/mem.wasm")) {
			mem = java.util.Objects.requireNonNull(in, "component/mem.wasm").readAllBytes();
		}
		final am.ik.wasm.ComponentWriter c = new am.ik.wasm.ComponentWriter();
		// Nested components 0 = A (probe), 1 = B (driver).
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_COMPONENT, probeInnerComponent(mem));
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_COMPONENT, probeDriverComponent(mem));
		// Instantiate A (component instance 0), project begin/poke (component funcs
		// 0/1), instantiate B with them (instance 1), project its run (func 2) and
		// export it as wasi:cli/run so `wasmtime run` drives the probe.
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_INSTANCE, am.ik.wasm.ComponentWriter
			.vec(List.of(am.ik.wasm.ComponentWriter.componentInstantiate(0, List.of(), List.of()))));
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_ALIAS,
				am.ik.wasm.ComponentWriter.vec(List.of(am.ik.wasm.ComponentWriter.aliasInstanceFunc(0, "begin"),
						am.ik.wasm.ComponentWriter.aliasInstanceFunc(0, "poke"))));
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_INSTANCE, am.ik.wasm.ComponentWriter
			.vec(List.of(am.ik.wasm.ComponentWriter.componentInstantiate(1, List.of("begin", "poke"), List.of(0, 1)))));
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_ALIAS,
				am.ik.wasm.ComponentWriter.vec(List.of(am.ik.wasm.ComponentWriter.aliasInstanceFunc(1, "run"))));
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_INSTANCE, am.ik.wasm.ComponentWriter
			.vec(List.of(am.ik.wasm.ComponentWriter.componentInstanceFromFunc("run", 2))));
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_EXPORT, am.ik.wasm.ComponentWriter
			.vec(List.of(am.ik.wasm.ComponentWriter.exportInstance("wasi:cli/run@0.3.0", 2))));
		return c.toByteArray();
	}

	// Component A: the probe core -- `begin` (the callback export) suspends on an
	// internal pending future; `poke` settles it from another task. The wit-imported
	// support interface exists purely for its type alias, whose derived task-return
	// built-in is how `begin` delivers its result mid-task.
	private static byte[] probeInnerComponent(byte[] mem) throws Exception {
		java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("cb-probe");
		java.nio.file.Files.writeString(dir.resolve("support.wit"), """
				package test:probe;

				interface support {
				  type done = s32;
				  // never bound (the resolver wants at least one function); the probe
				  // uses only the alias's derived task-return built-in
				  ping: func();
				}
				""");
		String lisp = """
				(rontolisp:wit-import "support.wit" :interface "test:probe/support" :package sup)
				(defvar *fut* nil)
				(rontolisp:async-defun begin (x)
				  (setq *fut* (rontolisp::%future-new))
				  (let ((v (rontolisp:await *fut*)))
				    (sup:done-task-return (+ x v)))
				  nil)
				(defun poke (v)
				  (rontolisp::%future-settle *fut* v)
				  v)
				(rontolisp:wasm-export 'begin :params '(:int))
				(rontolisp:wasm-export 'poke :params '(:int) :returns :int)
				""";
		List<LispVal> program = am.ik.rontolisp.eval.WitImportInliner.inline(LispReader.readAllFromString(lisp),
				dir.toString(), am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT,
				am.ik.rontolisp.eval.SourceLoader.fileSystem());
		WasmLispCompiler compiler = new WasmLispCompiler(false, true);
		compiler.callbackExportsForTest = java.util.Set.of("begin");
		compiler.rawCoreForTest = true;
		byte[] core = compiler.compile(am.ik.rontolisp.eval.WitLibrary.process(program));
		final am.ik.wasm.ComponentWriter c = new am.ik.wasm.ComponentWriter();
		// Component types: 0 = the task-return result (s32), 1 = the doorbell
		// stream<u64>, 2/3 = the lifted function types.
		final int tDone = 0, tU64Stream = 1, tBegin = 2, tPoke = 3;
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_TYPE,
				am.ik.wasm.ComponentWriter
					.vec(List.of(am.ik.wasm.ComponentWriter.definedPrim(am.ik.wasm.ComponentWriter.VT_S32),
							am.ik.wasm.ComponentWriter.definedStream(am.ik.wasm.ComponentWriter.VT_U64),
							am.ik.wasm.ComponentWriter.asyncFuncTypeScalars(List.of("x"),
									List.of(am.ik.wasm.ComponentWriter.VT_S32), am.ik.wasm.ComponentWriter.VT_S32),
							am.ik.wasm.ComponentWriter.funcTypeScalars(List.of("v"),
									List.of(am.ik.wasm.ComponentWriter.VT_S32), am.ik.wasm.ComponentWriter.VT_S32))));
		// Core modules: 0 = shared memory, 1 = preview1 trap stubs, 2 = probe core.
		// The memory must be sized from THIS core's import minimum, exactly as
		// WasmComponentBuilder does for a production component: the component memory
		// map puts the core's static data at page 6, so the stock six-page mem module
		// no longer satisfies any core.
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CORE_MODULE, WasmComponentBuilder.memModuleFor(core));
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CORE_MODULE, probePreview1Stub());
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CORE_MODULE, core);
		// Core instance 0 = memory, 1 = the preview1 stubs.
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CORE_INSTANCE, am.ik.wasm.ComponentWriter
			.vec(List.of(am.ik.wasm.ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_ALIAS,
				am.ik.wasm.ComponentWriter.vec(List.of(am.ik.wasm.ComponentWriter.aliasCoreMemory(0, "memory"),
						am.ik.wasm.ComponentWriter.aliasCoreFunc(0, "cabi_realloc"))));
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CORE_INSTANCE, am.ik.wasm.ComponentWriter
			.vec(List.of(am.ik.wasm.ComponentWriter.coreInstanceInstantiate(1, List.of(), List.of()))));
		// Canon built-ins (core funcs 1-8): the support task-return, then the $sched
		// seven exactly as the serve builder defines them.
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CANON,
				am.ik.wasm.ComponentWriter
					.vec(List.of(am.ik.wasm.ComponentWriter.canonTaskReturnTypeMemoryUtf8(tDone, 0), // 1
							am.ik.wasm.ComponentWriter.canonContextGet(0), // 2
							am.ik.wasm.ComponentWriter.canonContextSet(0), // 3
							am.ik.wasm.ComponentWriter.canonStreamNew(tU64Stream), // 4
							am.ik.wasm.ComponentWriter.canonStreamReadAsync(tU64Stream, 0), // 5
							am.ik.wasm.ComponentWriter.canonStreamWriteAsync(tU64Stream, 0), // 6
							am.ik.wasm.ComponentWriter.canonWaitableSetNew(), // 7
							am.ik.wasm.ComponentWriter.canonWaitableJoin()))); // 8
		// Core instance 2 = the support interface's task-return, 3 = $sched.
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CORE_INSTANCE,
				am.ik.wasm.ComponentWriter.vec(List.of(
						am.ik.wasm.ComponentWriter.coreInstanceFromFuncs(List.of("[task-return]done"), List.of(1)),
						am.ik.wasm.ComponentWriter.coreInstanceFromFuncs(WasmComponentImportCompiler.SCHED_FIELDS,
								List.of(2, 3, 4, 5, 6, 7, 8)))));
		// Core instance 4 = the probe core.
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CORE_INSTANCE,
				am.ik.wasm.ComponentWriter.vec(List
					.of(am.ik.wasm.ComponentWriter.coreInstanceInstantiate(2, List.of("mem", "wasi_snapshot_preview1",
							"test:probe/support", WasmComponentImportCompiler.SCHED_MODULE), List.of(0, 1, 2, 3)))));
		// Core funcs 9-11: the core's exports; component funcs 0/1: begin
		// CALLBACK-lifted against the core's async_cb (the serve handle shape), poke
		// sync-lifted. Nothing calls the core's `run` -- the probe's top level only
		// re-initializes wasm globals that start null anyway.
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_ALIAS,
				am.ik.wasm.ComponentWriter.vec(List.of(am.ik.wasm.ComponentWriter.aliasCoreFunc(4, "begin"),
						am.ik.wasm.ComponentWriter.aliasCoreFunc(4, "poke"),
						am.ik.wasm.ComponentWriter.aliasCoreFunc(4, "async_cb"))));
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CANON,
				am.ik.wasm.ComponentWriter
					.vec(List.of(am.ik.wasm.ComponentWriter.canonLiftMemoryUtf8AsyncCallback(9, tBegin, 0, 11),
							am.ik.wasm.ComponentWriter.canonLift(10, tPoke))));
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_EXPORT, am.ik.wasm.ComponentWriter.vec(List
			.of(am.ik.wasm.ComponentWriter.exportFunc("begin", 0), am.ik.wasm.ComponentWriter.exportFunc("poke", 1))));
		return c.toByteArray();
	}

	// Component B: the hand-assembled driver. Imports A's begin/poke as component
	// functions, async-lowers begin (its own memory module carries the return area),
	// and exports its lifted `run`.
	private static byte[] probeDriverComponent(byte[] mem) {
		final am.ik.wasm.ComponentWriter c = new am.ik.wasm.ComponentWriter();
		final int tBegin = 0, tPoke = 1, tRunResult = 2, tRun = 3;
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_TYPE,
				am.ik.wasm.ComponentWriter.vec(List.of(
						am.ik.wasm.ComponentWriter.asyncFuncTypeScalars(List.of("x"),
								List.of(am.ik.wasm.ComponentWriter.VT_S32), am.ik.wasm.ComponentWriter.VT_S32),
						am.ik.wasm.ComponentWriter.funcTypeScalars(List.of("v"),
								List.of(am.ik.wasm.ComponentWriter.VT_S32), am.ik.wasm.ComponentWriter.VT_S32),
						am.ik.wasm.ComponentWriter.definedResultVoid(),
						am.ik.wasm.ComponentWriter.asyncFuncTypeResultType(2))));
		// Component funcs 0/1: the imported begin/poke.
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_IMPORT,
				am.ik.wasm.ComponentWriter.vec(List.of(am.ik.wasm.ComponentWriter.importFunc("begin", tBegin),
						am.ik.wasm.ComponentWriter.importFunc("poke", tPoke))));
		// Core modules: 0 = the driver's own memory, 1 = the driver.
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CORE_MODULE, mem);
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CORE_MODULE, probeDriverModule());
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CORE_INSTANCE, am.ik.wasm.ComponentWriter
			.vec(List.of(am.ik.wasm.ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_ALIAS,
				am.ik.wasm.ComponentWriter.vec(List.of(am.ik.wasm.ComponentWriter.aliasCoreMemory(0, "memory"),
						am.ik.wasm.ComponentWriter.aliasCoreFunc(0, "cabi_realloc"))));
		// Core funcs 1-5: begin async-lowered (a subtask + RETURNED event), poke
		// sync-lowered, and the driver's own waitable trio.
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CANON,
				am.ik.wasm.ComponentWriter
					.vec(List.of(am.ik.wasm.ComponentWriter.canonLowerAsyncMemoryReallocUtf8(0, 0, 0), // 1
							am.ik.wasm.ComponentWriter.canonLower(1), // 2
							am.ik.wasm.ComponentWriter.canonWaitableSetNew(), // 3
							am.ik.wasm.ComponentWriter.canonWaitableJoin(), // 4
							am.ik.wasm.ComponentWriter.canonWaitableSetWait(0)))); // 5
		// Core instance 1 = the driver's import surface, 2 = the driver.
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CORE_INSTANCE,
				am.ik.wasm.ComponentWriter.vec(List.of(am.ik.wasm.ComponentWriter.coreInstanceFromFuncs(
						List.of("begin-start", "poke", "ws-new", "w-join", "ws-wait"), List.of(1, 2, 3, 4, 5)))));
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CORE_INSTANCE, am.ik.wasm.ComponentWriter
			.vec(List.of(am.ik.wasm.ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "p"), List.of(0, 1)))));
		// Core func 6 = the driver's run; lift it like rontolisp's own run export
		// (async-typed sync-ABI, so its blocking waits are legal).
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_ALIAS,
				am.ik.wasm.ComponentWriter.vec(List.of(am.ik.wasm.ComponentWriter.aliasCoreFunc(2, "run"))));
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_CANON,
				am.ik.wasm.ComponentWriter.vec(List.of(am.ik.wasm.ComponentWriter.canonLift(6, tRun))));
		c.rawSection(am.ik.wasm.ComponentWriter.SEC_EXPORT,
				am.ik.wasm.ComponentWriter.vec(List.of(am.ik.wasm.ComponentWriter.exportFunc("run", 2))));
		return c.toByteArray();
	}

	// The eight wasi_snapshot_preview1 imports of a component-mode core, as trap stubs:
	// the probe never does I/O, so reaching one is a probe bug worth trapping on.
	private static byte[] probePreview1Stub() {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		am.ik.wasm.WasmWriter w = new am.ik.wasm.WasmWriter(out);
		w.write("\0asm").writeLittleEndian4(1);
		w.writeTypeSection(types -> {
			types.addFunc(new am.ik.wasm.Type[] { am.ik.wasm.Type.I32, am.ik.wasm.Type.I32, am.ik.wasm.Type.I32,
					am.ik.wasm.Type.I32 }, new am.ik.wasm.Type[] { am.ik.wasm.Type.I32 }); // 0
			types
				.addFunc(
						new am.ik.wasm.Type[] { am.ik.wasm.Type.I32, am.ik.wasm.Type.I32, am.ik.wasm.Type.I32,
								am.ik.wasm.Type.I32, am.ik.wasm.Type.I32, am.ik.wasm.Type.I64, am.ik.wasm.Type.I64,
								am.ik.wasm.Type.I32, am.ik.wasm.Type.I32 },
						new am.ik.wasm.Type[] { am.ik.wasm.Type.I32 }); // 1
			types.addFunc(new am.ik.wasm.Type[] { am.ik.wasm.Type.I32 }, new am.ik.wasm.Type[] { am.ik.wasm.Type.I32 }); // 2
			types.addFunc(new am.ik.wasm.Type[] { am.ik.wasm.Type.I32, am.ik.wasm.Type.I32 },
					new am.ik.wasm.Type[] { am.ik.wasm.Type.I32 }); // 3
			types.addFunc(new am.ik.wasm.Type[] { am.ik.wasm.Type.I32, am.ik.wasm.Type.I64, am.ik.wasm.Type.I32 },
					new am.ik.wasm.Type[] { am.ik.wasm.Type.I32 }); // 4
		});
		w.writeFunction(f -> f.addFunction(0) // fd_write
			.addFunction(0) // fd_read
			.addFunction(1) // path_open
			.addFunction(2) // fd_close
			.addFunction(3) // random_get
			.addFunction(4) // clock_time_get
			.addFunction(3) // environ_sizes_get
			.addFunction(3)); // environ_get
		w.writeExport(e -> e.addExport("fd_write", am.ik.wasm.ExternalKind.FUNCTION, 0)
			.addExport("fd_read", am.ik.wasm.ExternalKind.FUNCTION, 1)
			.addExport("path_open", am.ik.wasm.ExternalKind.FUNCTION, 2)
			.addExport("fd_close", am.ik.wasm.ExternalKind.FUNCTION, 3)
			.addExport("random_get", am.ik.wasm.ExternalKind.FUNCTION, 4)
			.addExport("clock_time_get", am.ik.wasm.ExternalKind.FUNCTION, 5)
			.addExport("environ_sizes_get", am.ik.wasm.ExternalKind.FUNCTION, 6)
			.addExport("environ_get", am.ik.wasm.ExternalKind.FUNCTION, 7));
		w.writeCode(codes -> {
			for (int i = 0; i < 8; i++) {
				codes.addFunction(new byte[] { 0x00, 0x00, 0x0b }); // unreachable
			}
		});
		return out.toByteArray();
	}

	// The hand-assembled driver: run the core's top level, async-call begin(41) --
	// which MUST suspend -- join its subtask into an own waitable-set, poke(1) --
	// the second task, whose settle rings begin's doorbell -- then wait for begin's
	// RETURNED event and check the task-returned 42 in the return area.
	private static byte[] probeDriverModule() {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		am.ik.wasm.WasmWriter w = new am.ik.wasm.WasmWriter(out);
		w.write("\0asm").writeLittleEndian4(1);
		w.writeTypeSection(types -> {
			types.addFunc(new am.ik.wasm.Type[] { am.ik.wasm.Type.I32, am.ik.wasm.Type.I32 },
					new am.ik.wasm.Type[] { am.ik.wasm.Type.I32 }); // 0: begin-start /
																	// ws-wait
			types.addFunc(new am.ik.wasm.Type[] { am.ik.wasm.Type.I32 }, new am.ik.wasm.Type[] { am.ik.wasm.Type.I32 }); // 1:
																															// poke
			types.addFunc(new am.ik.wasm.Type[] {}, new am.ik.wasm.Type[] { am.ik.wasm.Type.I32 }); // 2:
																									// ws-new
																									// /
																									// run
			types.addFunc(new am.ik.wasm.Type[] { am.ik.wasm.Type.I32, am.ik.wasm.Type.I32 }, new am.ik.wasm.Type[] {}); // 3:
																															// w-join
		});
		w.writeImportSection(imports -> {
			imports.add(iw -> {
				iw.write("mem".length(), "mem", "memory".length(), "memory");
				iw.write(am.ik.wasm.ExternalKind.MEMORY);
				iw.write(0x00);
				iw.writeUnsignedLeb128(4);
			});
			imports.addImport("p", "begin-start", am.ik.wasm.ExternalKind.FUNCTION, 0);
			imports.addImport("p", "poke", am.ik.wasm.ExternalKind.FUNCTION, 1);
			imports.addImport("p", "ws-new", am.ik.wasm.ExternalKind.FUNCTION, 2);
			imports.addImport("p", "w-join", am.ik.wasm.ExternalKind.FUNCTION, 3);
			imports.addImport("p", "ws-wait", am.ik.wasm.ExternalKind.FUNCTION, 0);
		});
		w.writeFunction(f -> f.addFunction(2));
		w.writeExport(e -> e.addExport("run", am.ik.wasm.ExternalKind.FUNCTION, 5));
		w.writeCode(codes -> codes.addFunction(probeDriverBody()));
		return out.toByteArray();
	}

	private static byte[] probeDriverBody() {
		java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
		am.ik.wasm.WasmWriter w = new am.ik.wasm.WasmWriter(body);
		final int PACKED = 0, SUB = 1, SET = 2, EV = 3;
		w.write(1);
		w.writeUnsignedLeb128(4);
		w.write(am.ik.wasm.Type.I32);
		// packed = begin-start(41, RETPTR); a RETURNED (2) status means begin never
		// suspended -- the probe would observe nothing, so trap.
		w.write(am.ik.wasm.Instruction.I32_CONST);
		w.writeSignedLeb128(41);
		w.write(am.ik.wasm.Instruction.I32_CONST);
		w.writeSignedLeb128(PROBE_RETPTR);
		w.write(am.ik.wasm.Instruction.CALL);
		w.writeSignedLeb128(0);
		w.write(am.ik.wasm.Instruction.SET_LOCAL);
		w.writeSignedLeb128(PACKED);
		w.write(am.ik.wasm.Instruction.GET_LOCAL);
		w.writeSignedLeb128(PACKED);
		w.write(am.ik.wasm.Instruction.I32_CONST);
		w.writeSignedLeb128(0xF);
		w.write(am.ik.wasm.Instruction.I32_AND);
		w.write(am.ik.wasm.Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(am.ik.wasm.Instruction.I32_EQ);
		w.write(am.ik.wasm.Instruction.IF, 0x40);
		w.write(am.ik.wasm.Instruction.UNREACHABLE);
		w.write(am.ik.wasm.Instruction.END);
		w.write(am.ik.wasm.Instruction.GET_LOCAL);
		w.writeSignedLeb128(PACKED);
		w.write(am.ik.wasm.Instruction.I32_CONST);
		w.writeSignedLeb128(4);
		w.write(am.ik.wasm.Instruction.I32_SHR_U);
		w.write(am.ik.wasm.Instruction.SET_LOCAL);
		w.writeSignedLeb128(SUB);
		w.write(am.ik.wasm.Instruction.CALL);
		w.writeSignedLeb128(2);
		w.write(am.ik.wasm.Instruction.SET_LOCAL);
		w.writeSignedLeb128(SET);
		w.write(am.ik.wasm.Instruction.GET_LOCAL);
		w.writeSignedLeb128(SUB);
		w.write(am.ik.wasm.Instruction.GET_LOCAL);
		w.writeSignedLeb128(SET);
		w.write(am.ik.wasm.Instruction.CALL);
		w.writeSignedLeb128(3);
		// poke(1) -> 1: the second task settles begin's future and rings its doorbell.
		w.write(am.ik.wasm.Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(am.ik.wasm.Instruction.CALL);
		w.writeSignedLeb128(1);
		w.write(am.ik.wasm.Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(am.ik.wasm.Instruction.I32_NE);
		w.write(am.ik.wasm.Instruction.IF, 0x40);
		w.write(am.ik.wasm.Instruction.UNREACHABLE);
		w.write(am.ik.wasm.Instruction.END);
		// Wait for begin's subtask RETURNED event.
		w.write(am.ik.wasm.Instruction.BLOCK, 0x40);
		w.write(am.ik.wasm.Instruction.LOOP, 0x40);
		w.write(am.ik.wasm.Instruction.GET_LOCAL);
		w.writeSignedLeb128(SET);
		w.write(am.ik.wasm.Instruction.I32_CONST);
		w.writeSignedLeb128(PROBE_EVTP);
		w.write(am.ik.wasm.Instruction.CALL);
		w.writeSignedLeb128(4);
		w.write(am.ik.wasm.Instruction.SET_LOCAL);
		w.writeSignedLeb128(EV);
		w.write(am.ik.wasm.Instruction.GET_LOCAL);
		w.writeSignedLeb128(EV);
		w.write(am.ik.wasm.Instruction.I32_CONST);
		w.writeSignedLeb128(1); // EVENT_SUBTASK
		w.write(am.ik.wasm.Instruction.I32_EQ);
		w.write(am.ik.wasm.Instruction.I32_CONST);
		w.writeSignedLeb128(PROBE_EVTP);
		w.write(am.ik.wasm.Instruction.I32_LOAD, 0x02, 0x00);
		w.write(am.ik.wasm.Instruction.GET_LOCAL);
		w.writeSignedLeb128(SUB);
		w.write(am.ik.wasm.Instruction.I32_EQ);
		w.write(am.ik.wasm.Instruction.I32_AND);
		w.write(am.ik.wasm.Instruction.I32_CONST);
		w.writeSignedLeb128(PROBE_EVTP);
		w.write(am.ik.wasm.Instruction.I32_LOAD, 0x02, 0x04);
		w.write(am.ik.wasm.Instruction.I32_CONST);
		w.writeSignedLeb128(2); // RETURNED
		w.write(am.ik.wasm.Instruction.I32_EQ);
		w.write(am.ik.wasm.Instruction.I32_AND);
		w.write(am.ik.wasm.Instruction.BR_IF, 1);
		w.write(am.ik.wasm.Instruction.BR, 0);
		w.write(am.ik.wasm.Instruction.END); // loop
		w.write(am.ik.wasm.Instruction.END); // block
		// The task-returned result must be 41 + 1.
		w.write(am.ik.wasm.Instruction.I32_CONST);
		w.writeSignedLeb128(PROBE_RETPTR);
		w.write(am.ik.wasm.Instruction.I32_LOAD, 0x02, 0x00);
		w.write(am.ik.wasm.Instruction.I32_CONST);
		w.writeSignedLeb128(42);
		w.write(am.ik.wasm.Instruction.I32_NE);
		w.write(am.ik.wasm.Instruction.IF, 0x40);
		w.write(am.ik.wasm.Instruction.UNREACHABLE);
		w.write(am.ik.wasm.Instruction.END);
		// run's result: the ok discriminant of result<_,_>.
		w.write(am.ik.wasm.Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(am.ik.wasm.Instruction.END);
		return body.toByteArray();
	}

	@Test
	void compilePackedIntVectorMakeArrayMasksAndReadsUnsigned() throws Exception {
		// .kb/packed-integer-vectors.md: stores mask to the width, reads widen
		// unsigned, setf returns the value AS STORED -- matching the interpreter.
		// let* sequencing, not (list (setf ...) (aref ...)): compiled list arguments
		// evaluate right-to-left (.todo/014), so the store must be ordered explicitly.
		assertThat(compileAndRun("""
				(let* ((a (make-array 4 :element-type '(unsigned-byte 8) :initial-element 7))
				       (stored (setf (aref a 1) 300))
				       (readback (aref a 1)))
				  (print (list stored readback (aref a 0) (length a) a)))
				""")).isEqualTo("(44 44 7 4 #(7 44 7 7))");
		assertThat(compileAndRun(
				"(print (make-array 3 :element-type '(unsigned-byte 16) :initial-contents '(1 70000 3)))"))
			.isEqualTo("#(1 4464 3)");
		assertThat(compileAndRun("""
				(let ((a (make-array 2 :element-type '(unsigned-byte 32))))
				  (setf (aref a 0) 4294967295)
				  (setf (aref a 1) 4294967296)
				  (print a))
				""")).isEqualTo("#(4294967295 0)");
	}

	@Test
	void compilePackedIntVectorIntrospection() throws Exception {
		assertThat(compileAndRun("""
				(let ((a (make-array 3 :element-type '(unsigned-byte 8))))
				  (print (list (array-element-type a) (arrayp a) (vectorp a) (array-dimensions a)
				               (typep a '(simple-array (unsigned-byte 8) (*))))))
				""")).isEqualTo("((UNSIGNED-BYTE 8) T T (3) T)");
		// A rank-n shape (runtime-detected) and a fill-pointer combination keep the
		// general boxed representation.
		assertThat(compileAndRun("(print (array-element-type (make-array '(2 2) :element-type '(unsigned-byte 8))))"))
			.isEqualTo("T");
		assertThat(compileAndRun("""
				(let ((a (make-array '(2 2) :element-type '(unsigned-byte 8) :initial-element 3)))
				  (setf (aref a 1 1) 9)
				  (print (list (aref a 0 0) (aref a 1 1))))
				""")).isEqualTo("(3 9)");
	}

	@Test
	void compilePackedIntVectorSubseqCopySeqReplacePreserveThePackedType() throws Exception {
		assertThat(compileAndRun("""
				(let* ((a (make-array 4 :element-type '(unsigned-byte 8) :initial-contents '(9 8 7 6)))
				       (s (subseq a 1 3)))
				  (setf (aref s 0) 300)
				  (print (list s (array-element-type s) a)))
				""")).isEqualTo("(#(44 7) (UNSIGNED-BYTE 8) #(9 8 7 6))");
		assertThat(compileAndRun("""
				(let ((dst (make-array 3 :element-type '(unsigned-byte 8)))
				      (src #(300 2 3)))
				  (replace dst src)
				  (print dst))
				""")).isEqualTo("#(44 2 3)");
	}

	@Test
	void compilePackedIntVectorReaderLiteralAndRowMajor() throws Exception {
		assertThat(compileAndRun("(print (list #8@(1 2 300) (array-element-type #32@(1 2))))"))
			.isEqualTo("(#(1 2 44) (UNSIGNED-BYTE 32))");
		assertThat(compileAndRun("""
				(let ((a #8@(1 2 3)))
				  (%row-major-aset a 1 999)
				  (print (list (row-major-aref a 1) a)))
				""")).isEqualTo("(231 #(1 231 3))");
	}

	@Test
	void compilePackedIntVectorFusedArefAndStoreMatchTheGenericPath() throws Exception {
		// The SHA-256 shape: fused trees whose leaves are packed aref reads, storing
		// raw through _iv_set. The rolling loop crosses the i31 boundary in both
		// directions and must match the interpreter's boxed arithmetic bit for bit.
		assertThat(compileAndRun("""
				(let ((w (make-array 8 :element-type '(unsigned-byte 32))))
				  (setf (aref w 0) 1732584193)
				  (setf (aref w 1) 4023233417)
				  (dotimes (i 6)
				    (setf (aref w (+ i 2))
				          (logand 4294967295
				                  (+ (logxor (aref w i) (ash (aref w (+ i 1)) -3))
				                     (* 31 (aref w (+ i 1)))))))
				  (print w))
				"""))
			.isEqualTo("#(1732584193 4023233417 2225363975 255870178 1578413049 1761846468 178639645 2993401642)");
		// A GENERAL array reaching a fused aref leaf bails to the ordinary aref
		// dispatch; a string element likewise (and makes the whole tree fall back).
		assertThat(compileAndRun("""
				(let ((v (vector 10 20 3.5)))
				  (print (+ (aref v 0) (aref v 1) 100))
				  (print (+ (aref v 2) (aref v 0) 1)))
				""")).isEqualTo("130\n14.5");
	}

	@Test
	void compileInlinableDefunsFuseWithoutChangingResults() throws Exception {
		// mod32+/rol32-style one-liner wrappers substitute into fused trees AND at
		// direct call sites; results (including bignum promotion through the boxed
		// fallback and argument side effects run once) must be unchanged.
		assertThat(compileAndRun("""
				(defun mod32+ (a b) (ldb (byte 32 0) (+ a b)))
				(defun rol32 (a s)
				  (logior (ldb (byte 32 0) (ash a s)) (ash a (- s 32))))
				(print (mod32+ 4294967295 2))
				(print (rol32 2864434397 8))
				(print (mod32+ (rol32 305419896 4) (mod32+ 1 2)))
				""")).isEqualTo("1\n3150765482\n591751044");
		// A side-effecting argument bound to a multi-use parameter evaluates once.
		assertThat(compileAndRun("""
				(defun twice (x) (+ x x))
				(defvar *n* 0)
				(print (twice (setq *n* (+ *n* 5))))
				(print *n*)
				""")).isEqualTo("10\n5");
		// Out-of-i64 promotion inside an inlined body takes the generic fallback.
		assertThat(compileAndRun("""
				(defun sq (x) (* x x))
				(print (sq 4294967295))
				""")).isEqualTo("18446744065119617025");
	}

	// The mutex primitives exist on WASM as no-ops (single-threaded by construction) so
	// that a library taking a lock on a path this program never runs still COMPILES --
	// an undefined function is a compile-time error here, not a call-time one. The
	// handle is opaque, so only its identity through acquire/release is asserted.
	@Test
	void mutexPrimitivesAreNoOpsThatStillCompose() throws Exception {
		assertThat(compileAndRun("""
				(defvar *m* (rontolisp:make-mutex))
				(defvar *c* 0)
				(defun bump (n)
				  (dotimes (i n)
				    (rontolisp:with-mutex (*m*) (setq *c* (+ *c* 1)))))
				(bump 3)
				(print *c*)
				(print (rontolisp:with-mutex ((rontolisp:make-mutex)) 'held))
				(print (eq (rontolisp:mutex-release (rontolisp:mutex-acquire *m*)) *m*))
				""")).isEqualTo("3\nHELD\nT");
	}

}
