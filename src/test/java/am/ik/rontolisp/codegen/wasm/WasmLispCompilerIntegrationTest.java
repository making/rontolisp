package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.macro.FoldDifferential;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.LoweredBuiltinValues;
import am.ik.rontolisp.testsupport.HostWasmtime.ExecResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.images.builder.Transferable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests that compile Lisp to WASM and run it with wasmtime.
 *
 * <p>
 * This is by far the longest class in the build: every case is a serial chain of compile
 * the module, stage it on disk, run wasmtime, and there are thousands of them. None of
 * that chain is shared between cases, so the methods run concurrently inside the surefire
 * fork -- the thread cap is derived from the machine's core count by
 * {@link am.ik.rontolisp.testsupport.CoreCountParallelismStrategy}, wired in from
 * {@code junit-platform.properties}. Everything a test writes therefore has to be private
 * to the running test: stage modules and guest-visible data files through
 * {@link #path(String)}, never at a fixed {@code /tmp/...} literal shared with another
 * method.
 *
 * <p>
 * wasmtime runs as a host process ({@link HostWasmtime}) rather than in the shared
 * container the other WASM test classes use, because the container's per-case exec and
 * file copy stopped the concurrency above from paying off on a 4 vCPU CI runner -- the
 * rationale, and what it costs in version pinning, is on that class.
 */
@EnabledIf("am.ik.rontolisp.testsupport.HostWasmtime#isAvailable")
@Execution(ExecutionMode.CONCURRENT)
class WasmLispCompilerIntegrationTest {

	// Named and shaped like the GenericContainer the other WASM classes hold, so the two
	// runners differ only in this declaration. The class-level @EnabledIf skips
	// everything
	// here when the host has no usable wasmtime, the way @Testcontainers used to skip it
	// when no Docker daemon was reachable.
	static final HostWasmtime wasmtime = HostWasmtime.INSTANCE;

	// Scratch directory, one per test worker thread (created on that thread's first use).
	// Scoping by thread rather than by test keeps it to one file set per worker, and
	// gives
	// the `--dir .` tests -- whose guest programs open relative names like "lib.lisp" or
	// "wof.txt" -- a working directory no concurrently running test can write into.
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
	 * Returns a path under the calling thread's scratch directory. Use it for every file
	 * a test stages or the guest program opens; a fixed path would be raced by the tests
	 * running concurrently on the other threads.
	 * @param name the file name inside the scratch directory
	 * @return the absolute path
	 */
	private static String path(String name) {
		return workDir() + "/" + name;
	}

	// Emptied rather than just created: unlike the container, which was new every run,
	// the
	// host directory outlives the JVM, and a stale file left by an earlier run is exactly
	// the kind of thing a `--dir .` test would happily open.
	private static String workDir() {
		return WORK_DIRS.computeIfAbsent(Thread.currentThread().threadId(), id -> {
			Path dir = Path.of(System.getProperty("java.io.tmpdir"), "rontolisp-wasmtime", "w" + id);
			try {
				if (Files.isDirectory(dir)) {
					try (Stream<Path> stale = Files.list(dir)) {
						for (Path file : stale.toList()) {
							Files.deleteIfExists(file);
						}
					}
				}
				Files.createDirectories(dir);
			}
			catch (IOException e) {
				throw new UncheckedIOException("cannot create the scratch dir " + dir, e);
			}
			return dir.toString();
		});
	}

	private static String compileAndRun(String lispCode) throws Exception {
		return compileAndRunProgram(LispReader.readAllFromString(lispCode));
	}

	// Programs that reach a prelude defun mirror the CLI pipeline's prelude splice; the
	// Gray variant adds GrayStreamsLibrary in the CLI's order, so the dispatch helpers
	// gray.lisp splices resolve their stream through the prelude's %stream-target.
	private static String compileAndRunPrelude(String lispCode) throws Exception {
		return compileAndRunProgram(
				am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode)));
	}

	private static String compileAndRunGray(String lispCode) throws Exception {
		return compileAndRunProgram(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode))));
	}

	// A --component program run for its standard output, the fourth backend. The core
	// module is the same one Preview 1 gets, but the I/O adapter is not, so a text
	// differential is only complete once it has run here too.
	// The component twin of compileAndRunPrelude: a program reaching a prelude defun
	// needs the CLI pipeline's splice here too, or the module compiles to a call-time
	// "undefined function".
	private static String compileComponentAndRunPrelude(String lispCode) throws Exception {
		byte[] component = new WasmLispCompiler(false, true)
			.compile(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode)));
		wasmtime.copyFileToContainer(Transferable.of(component), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	private static String compileComponentAndRun(String lispCode) throws Exception {
		byte[] component = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(lispCode));
		wasmtime.copyFileToContainer(Transferable.of(component), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	@Test
	void aFoldedCallPrintsWhatTheRuntimeWouldHave() throws Exception {
		// The pure-builtin fold renders in JAVA at compile time what the emitted module
		// would have computed at run time, so the two have to agree character for
		// character -- the generalization of
		// aFoldedLiteralPrintsWhatTheRuntimePrinterWouldHave to every table entry.
		// Hiding the arguments behind a function parameter is what keeps the runtime in
		// the picture (am.ik.rontolisp.macro.FoldDifferential).
		FoldDifferential.assertNoDivergence(compileAndRun(FoldDifferential.program()));
	}

	@Test
	void aFoldedCallPrintsWhatTheRuntimeWouldHaveOnTheComponentPath() throws Exception {
		FoldDifferential.assertNoDivergence(compileComponentAndRun(FoldDifferential.program()));
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
	// exception-handling proposal enabled.
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
		return compileServeComponent(source, baseDir, OptimizeLevel.NONE);
	}

	private static byte[] compileServeComponent(String source, @org.jspecify.annotations.Nullable String baseDir,
			OptimizeLevel optimize) {
		var witBackend = am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT;
		List<LispVal> loaded = am.ik.rontolisp.eval.WitImportInliner.inline(LispReader.readAllFromString(source),
				baseDir, witBackend, am.ik.rontolisp.eval.SourceLoader.fileSystem());
		boolean bufferBody = am.ik.rontolisp.compiler.ClackEnv.usesBufferedBody(loaded);
		loaded = am.ik.rontolisp.eval.HttpLibrary.process(loaded, witBackend, true);
		// The server value model (the Clack environment build + response normalizer)
		// mirrors the CLI splice; without it %http-serve-request is undefined.
		loaded = am.ik.rontolisp.eval.HttpServerLibrary.process(loaded, bufferBody);
		// The wait-for / sockets / stdin splices mirror the CLI order (each a no-op for
		// a handler that references nothing of it), so a served handler may also use
		// the tcp built-ins.
		loaded = am.ik.rontolisp.eval.WaitForLibrary.process(loaded, witBackend);
		loaded = am.ik.rontolisp.eval.SocketsLibrary.process(loaded, witBackend);
		loaded = am.ik.rontolisp.eval.StdinLibrary.process(loaded, witBackend, true);
		// The prelude splice mirrors the CLI: a handler draining a body stream calls
		// the prelude's rontolisp:read-all. GrayStreamsLibrary runs after the macro
		// expansion like the CLI; the buffered :raw-body Gray class needs its call-site
		// rewrite.
		List<LispVal> program = am.ik.rontolisp.eval.WitLibrary
			.process(am.ik.rontolisp.eval.GrayStreamsLibrary.process(am.ik.rontolisp.eval.LispPreludeLibrary
				.process(am.ik.rontolisp.eval.UserMacroExpander.expand(loaded), Features.WASM)));
		// EnvironmentLibrary runs LAST, exactly as in the CLI: its trigger is the
		// %host-getenv primitive, which the uiop splice above is what introduces (the
		// public uiop:getenv is a Lisp definition over it).
		program = am.ik.rontolisp.eval.EnvironmentLibrary.process(program, witBackend);
		return new WasmLispCompiler(false, true, false, optimize, true).compile(program);
	}

	// warn writes its "WARNING: ..." line to standard ERROR, which the stdout helpers
	// drop.
	private static String compileAndRunStderr(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc", path("test.wasm"));
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStderr().trim();
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

	// The non-EH twin of the above: no catching form, so the module carries no tag
	// section -- an uncaught condition is the bare `unreachable` %error has always been.
	private static String compileAndRunExpectTrap(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc", path("test.wasm"));
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

	// torch tests pre-process with TorchLibrary.process then LinalgLibrary.process,
	// mirroring the compile-path pre-pass order run by RontoLispCli (torch first, so
	// the linalg references inside the spliced torch defuns pull linalg in too).
	private static String compileAndRunTorch(String lispCode) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LinalgLibrary
			.process(am.ik.rontolisp.eval.TorchLibrary.process(LispReader.readAllFromString(lispCode)));
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
		// The prelude splice mirrors the CLI: uiop:getenv is a Lisp definition over the
		// %host-getenv primitive Preview 1 lowers to the environ-buffer scan.
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode),
				Features.WASM);
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
		return compileFetchComponent(program, OptimizeLevel.NONE);
	}

	private static byte[] compileFetchComponent(String program, OptimizeLevel optimize) {
		var witBackend = am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT;
		// TlsLibrary before SocketsLibrary, mirroring the CLI: tls.lisp references
		// rontolisp:tcp-connect, which is what fires the sockets trigger for a
		// tls-only program (a no-op for every program that names no client tls).
		List<am.ik.rontolisp.LispVal> forms = am.ik.rontolisp.eval.WitLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(am.ik.rontolisp.eval.StdinLibrary
				.process(am.ik.rontolisp.eval.SocketsLibrary.process(am.ik.rontolisp.eval.TlsLibrary.process(
						am.ik.rontolisp.eval.WaitForLibrary.process(am.ik.rontolisp.eval.HttpLibrary
							.process(LispReader.readAllFromString(program), witBackend, false), witBackend),
						witBackend), witBackend), witBackend, false)));
		return new WasmLispCompiler(false, true, false, optimize).compile(forms);
	}

	// The same component pipeline with GrayStreamsLibrary in it, in the CLI's order (the
	// Gray call-site rewrite runs AFTER the socket/stdin splices, so its fall-through
	// arms
	// are what the socket rewrite then has to recognize).
	private static byte[] compileGrayFetchComponent(String program) {
		var witBackend = am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT;
		List<am.ik.rontolisp.LispVal> forms = am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.WitLibrary
				.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(am.ik.rontolisp.eval.StdinLibrary.process(
						am.ik.rontolisp.eval.SocketsLibrary.process(am.ik.rontolisp.eval.WaitForLibrary
							.process(LispReader.readAllFromString(program), witBackend), witBackend),
						witBackend, false))));
		return new WasmLispCompiler(false, true).compile(forms);
	}

	@Test
	void jvmExportDirectiveIsANoOpOnWasm() throws Exception {
		// rontolisp:jvm-export declares a typed Java entry point for the JVM backend;
		// here it is skipped, exactly as wasm-export is a no-op on the JVM — one
		// library source can declare both.
		assertThat(compileAndRun("""
				(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
				(rontolisp:jvm-export 'fact :params '(:s32) :returns :s32)
				(print (fact 5))
				""")).isEqualTo("120");
	}

	@Test
	void theJvmOnlyHandleDesignatorsAreRefusedByNameOnWasm() {
		// :float-vector / :float-matrix ride a Java handle class; no WASM carrier states
		// one, so the designator is refused where it is written instead of failing later
		// in a lift (.kb/jvm-export.md).
		assertThatThrownBy(() -> compileAndRun("""
				(defun echo (v) v)
				(rontolisp:wasm-export 'echo :params '(:float-vector) :returns :float-vector)
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining(":float-vector is a JVM boundary type");
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

	// Compiles a --component --no-wasi REACTOR (zero imports; the top level runs from
	// the core start section at instantiation) and invokes a component-model export.
	private static ExecResult reactorComponentInvoke(String lispCode, OptimizeLevel optimize, String invocation)
			throws Exception {
		byte[] component = new WasmLispCompiler(false, true, true, optimize)
			.compile(LispReader.readAllFromString(lispCode));
		wasmtime.copyFileToContainer(Transferable.of(component), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "--invoke", invocation,
				path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		return result;
	}

	@Test
	void componentNoWasiReactorRunsTopLevelAtInstantiationAndInvokesExports() throws Exception {
		// The headline of the reactor shape: the top level lives in the core START
		// SECTION, so a defparameter is already assigned when the very first export
		// call arrives -- under the run-lifted component the same read answers nil
		// (--invoke never drives wasi:cli/run). With and without --optimize, since
		// the zero-import property must not ride on the tree shaker.
		String program = """
				(defparameter *greeting* "hello, ")
				(defun greet (name) (concatenate 'string *greeting* name))
				(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
				(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
				(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
				""";
		for (OptimizeLevel level : List.of(OptimizeLevel.NONE, OptimizeLevel.DEFAULT)) {
			assertThat(reactorComponentInvoke(program, level, "greet(\"world\")").getStdout().trim())
				.as("greet under " + level)
				.isEqualTo("\"hello, world\"");
		}
		assertThat(reactorComponentInvoke(program, OptimizeLevel.DEFAULT, "fact(10)").getStdout().trim())
			.isEqualTo("3628800");
	}

	@Test
	void httpHandlerDirectiveOnAReactorAnswersTheHostEnvelope() throws Exception {
		// The --no-wasi lowering of the rontolisp:http-handler DIRECTIVE: the same
		// source that binds a socket on the interpreter/JVM and serves wasi:http under
		// --component becomes the host-driven reactor (the clackup leg) -- a
		// handle-request export over the JSON envelope. The handler is an async-defun,
		// so this also pins the transport's %future-force boundary resolve on the
		// degenerate reactor future. Compiled --component --no-wasi so wasmtime can
		// speak the string boundary via --invoke; the core-module variant is the same
		// program minus the component wrap (driven by the node hosts in
		// examples/cloudflare-workers).
		var loaded = am.ik.rontolisp.eval.HttpReactorInliner.lowerHttpHandler(LispReader.readAllFromString("""
				(rontolisp:async-defun handle (env)
				  (list 200 (list :content-type "text/plain")
				        (list (concatenate 'string "hello " (getf env :path-info)))))
				(rontolisp:http-handler 'handle 8080)
				""", am.ik.rontolisp.reader.Features.WASM_REACTOR));
		loaded = am.ik.rontolisp.eval.HttpReactorInliner.process(loaded,
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT, true,
				am.ik.rontolisp.compiler.HostBoundary.STREAMING);
		loaded = am.ik.rontolisp.eval.HttpReactorLibrary.process(loaded);
		loaded = am.ik.rontolisp.eval.HttpServerLibrary.process(loaded, false);
		// GrayStreamsLibrary mirrors the CLI: the reactor's buffered request body is a
		// Gray class, so gray.lisp must be spliced like RontoLispCli does.
		List<LispVal> program = am.ik.rontolisp.eval.GrayStreamsLibrary.process(am.ik.rontolisp.eval.LispPreludeLibrary
			.process(am.ik.rontolisp.eval.JsonLibrary.process(am.ik.rontolisp.eval.UserMacroExpander.expand(loaded))));
		byte[] component = new WasmLispCompiler(false, true, true, OptimizeLevel.DEFAULT).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(component), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--invoke",
				"handle-request(\"{\\\"method\\\":\\\"GET\\\",\\\"target\\\":\\\"/dog\\\",\\\"headers\\\":{\\\"host\\\":\\\"h\\\"}}\")",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout()).contains("hello /dog").contains("\\\"status\\\":200");
	}

	@Test
	void componentNoWasiReactorDiscardsOutputInsteadOfTrapping() throws Exception {
		// The Preview 1 --no-wasi contract carried through the component wrap: the
		// fd_write sink discards a top-level print (which now runs at INSTANTIATION,
		// where a trap would kill the instance before any invoke) and an in-export
		// print alike; stdout carries only the invoke's result.
		String program = """
				(print "boot message")
				(defparameter *base* 41)
				(defun bump () (print "called") (+ *base* 1))
				(rontolisp:wasm-export 'bump :params '() :returns :int)
				""";
		ExecResult result = reactorComponentInvoke(program, OptimizeLevel.DEFAULT, "bump()");
		assertThat(result.getStdout().trim()).isEqualTo("42");
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
	void redefinedDefunKeepsTheTopLevelChunkIndicesRight() throws Exception {
		// A redefined defun emits one module function PER DEFINITION (the defuns list),
		// but the name->info map holds one entry per NAME -- so any funcIndex reserved
		// from functions.size() (top-level chunks, lambdas, async entries) was off by
		// the number of redefined entries and _start called into the wrong function
		// (an invalid module when the arities differ). fast-http redefines 11 struct
		// readers this way. The reservation must count the defuns LIST.
		String program = """
				(defun f (x) x)
				(defun f (x) (+ x 1))
				(print (f 41))
				""";
		assertThat(compileAndRun(program)).isEqualTo("42");
	}

	@Test
	void concatenateResolvesADeftypeAliasResultType() throws Exception {
		// fast-http's multipart parser concatenates into 'simple-byte-vector, its own
		// deftype alias of the packed octet vector: the registered deftype expansion
		// resolves the designator to the vector family at compile time.
		String program = """
				(deftype octet-vector () '(simple-array (unsigned-byte 8) (*)))
				(print (concatenate 'octet-vector #(1) #(2 3)))
				""";
		assertThat(compileAndRun(program)).isEqualTo("#(1 2 3)");
	}

	@Test
	void concatenateKeepsThePackedElementType() throws Exception {
		// An (unsigned-byte 8|16|32) result element type builds the PACKED vector, in
		// call position and through the #'concatenate wrapper's runtime width dispatch
		// alike. Any other element type -- and the spellings whose second
		// element is a SIZE -- stay the general vector.
		// The zero-parameter deftype shape, like concatenateResolvesADeftypeAliasResult
		// Type: this harness does not run the CLI's UserMacroExpander, which is what
		// folds a parameterized deftype into the registrable form (the parameterized
		// shape is pinned end to end by the ci-spec case and LackEcosystemE2eTest).
		String program = """
				(deftype simple-byte-vector () '(simple-array (unsigned-byte 8) (*)))
				(let ((v (concatenate '(vector (unsigned-byte 8)) #(1) '(2 260))))
				  (print (list (array-element-type v) (typep v '(simple-array (unsigned-byte 8) (*))) v)))
				(print (array-element-type (concatenate '(simple-array (unsigned-byte 16) (*)) #(1))))
				(print (array-element-type (concatenate '(vector (unsigned-byte 32) *))))
				(print (array-element-type (concatenate 'simple-byte-vector #(1 2))))
				(print (array-element-type (concatenate '(simple-vector 2) '(1 2))))
				(print (array-element-type (concatenate '(vector character) "ab")))
				(print (array-element-type (apply #'concatenate '(simple-array (unsigned-byte 8) (*))
				                                  (list '(1 2) #(3)))))
				(print (funcall #'concatenate '(vector (unsigned-byte 8)) '(1 260)))
				(print (array-element-type (funcall #'concatenate 'vector '(1))))
				""";
		assertThat(compileAndRun(program)).isEqualTo("""
				((UNSIGNED-BYTE 8) T #(1 2 4))
				(UNSIGNED-BYTE 16)
				(UNSIGNED-BYTE 32)
				(UNSIGNED-BYTE 8)
				T
				T
				(UNSIGNED-BYTE 8)
				#(1 4)
				T""");
	}

	@Test
	void arrayElementTypeOnAStringAnswersCharacter() throws Exception {
		// A string is a vector of characters, so it answers the one character type,
		// before
		// the packed/general dispatch. A general vector still answers t; a packed integer
		// vector keeps its (unsigned-byte N) answer. The quote-framed string and the
		// mutable character vector (a make-string result) must both answer character.
		assertThat(compileAndRun("""
				(print (array-element-type "abc"))
				(print (array-element-type #(1 2 3)))
				(print (array-element-type #8@(1 2)))
				(print (array-element-type (make-string 3)))
				""")).isEqualTo("""
				CHARACTER
				T
				(UNSIGNED-BYTE 8)
				CHARACTER""");
	}

	@Test
	void theLoweredOnlyBuiltinsAreFirstClassFunctionValues() throws Exception {
		// The sweep: every CL FUNCTION this compiler lowers in operator position must
		// also have a wrapper, or #'name is undefined here while the interpreter
		// answers. Same program and same expectation as the interpreter and the JVM.
		assertThat(compileAndRun(LoweredBuiltinValues.PROGRAM)).isEqualTo(LoweredBuiltinValues.OUTPUT);
	}

	@Test
	void coerceKeepsThePackedElementTypeAndBakesALiteralTable() throws Exception {
		// coerce reads the SAME result-type designator concatenate does, so a packed
		// element type means a packed vector there too. Both routes run: a LITERAL
		// sequence, which PureBuiltinFolder reduces to the packed literal the backend
		// bakes into its data segment, and one behind a function parameter, which builds
		// through the injected %seq-int-vector at run time. A table past the
		// data-segment threshold (16 elements) covers the baked emission, and the two
		// (table) calls pin the freshness the fold rests on -- one literal, two arrays.
		String program = """
				(defun %id (x) x)
				(defun table () (coerce '(0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 65535)
				                        '(vector (unsigned-byte 16))))
				(let ((v (coerce '(1 2 260) '(vector (unsigned-byte 8)))))
				  (print (list (array-element-type v) (typep v '(simple-array (unsigned-byte 8) (*))) v)))
				(let ((v (coerce (%id '(1 2 260)) '(vector (unsigned-byte 8)))))
				  (print (list (array-element-type v) (typep v '(simple-array (unsigned-byte 8) (*))) v)))
				(print (array-element-type (coerce #(1) '(simple-array (unsigned-byte 32) (*)))))
				(print (array-element-type (coerce '(1 2) '(simple-vector 2))))
				(print (array-element-type (coerce '(1 2) 'vector)))
				(print (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(7 8 9)))
				(let ((a (table)) (b (table)))
				  (setf (aref a 0) 99)
				  (print (list (aref a 0) (aref b 0) (aref a 17) (eq a b) (length a))))
				""";
		assertThat(compileAndRun(program)).isEqualTo("""
				((UNSIGNED-BYTE 8) T #(1 2 4))
				((UNSIGNED-BYTE 8) T #(1 2 4))
				(UNSIGNED-BYTE 32)
				T
				T
				#(7 8 9)
				(99 0 65535 NIL 18)""");
	}

	@Test
	void aQuotedDatumIsOneSharedConstantAcrossEvaluations() throws Exception {
		// quote is the CONSTANT syntax: every evaluation of one quote site answers the
		// SAME object, like the interpreter and like a real CL (CLHS leaves writes into
		// it undefined; here they reach the shared constant, as they always did on the
		// interpreter). A bare #(...) literal stays a constructor -- the freshness
		// invariant of .kb/array-literals.md (and the property PureBuiltinFolder's
		// packed-table fold rests on) is about literals OUTSIDE quote.
		String program = """
				(defun %ql () '(1 2 3))
				(print (eq (%ql) (%ql)))
				(let ((a (%ql))) (setf (car a) 99))
				(print (%ql))
				(let ((f (lambda () '#(7 8)))) (print (eq (funcall f) (funcall f))))
				(defun %qn () '(1 #(2 3)))
				(print (eq (cadr (%qn)) (cadr (%qn))))
				(defun %qd () '#d(1.0 2.0))
				(print (eq (%qd) (%qd)))
				""";
		assertThat(compileAndRun(program)).isEqualTo("T\n(99 2 3)\nT\nT\nT");
		// The component path shares the codegen but not the I/O adapter; the constant
		// table is codegen, so the answer must be identical.
		assertThat(compileComponentAndRun(program)).isEqualTo("T\n(99 2 3)\nT\nT\nT");
	}

	@Test
	void aBareInstanceLiteralIsOneSharedConstantAcrossEvaluations() throws Exception {
		// A bare #P"..." / #S(...) in code position is a CONSTANT, not a constructor:
		// the interpreter's self-evaluating LispInstance arm hands the reader's own
		// instance back at every evaluation, and cannot be moved (the same arm carries
		// every live instance spliced back through (quote <value>)), so the site
		// memoizes into the lazy module global a quoted datum uses
		// (.kb/quoted-data.md).
		String program = """
				(defun %fp () #P"a/b.txt")
				(print (eq (%fp) (%fp)))
				(print (namestring (%fp)))
				(defstruct %pt x y)
				(defun %fs () #S(%pt :x 1 :y 2))
				(print (eq (%fs) (%fs)))
				(print (%pt-x (%fs)))
				""";
		assertThat(compileAndRunPrelude(program)).isEqualTo("T\n\"a/b.txt\"\nT\n1");
		// The component path shares the codegen but not the I/O adapter; the constant
		// table is codegen, so the answer must be identical.
		assertThat(compileComponentAndRunPrelude(program)).isEqualTo("T\n\"a/b.txt\"\nT\n1");
	}

	@Test
	void concatenateStringTakesAnySequence() throws Exception {
		// The string family walks any character sequence, nil (the empty list) included:
		// s-sql builds "CREATE TABLE x" as
		// (concatenate 'string (unless tableset "TABLE ") name). Each non-literal
		// argument goes through the injected %seq-string helper -- one call, never an
		// inlined coerce loop (.kb/concatenate-result-families.md).
		String program = """
				(print (concatenate 'string "a" '(#\\b #\\c) #(#\\d) nil "e"))
				(print (concatenate 'string (unless t "TABLE ") "person"))
				(print (apply #'concatenate 'string (list nil "x" '(#\\y))))
				""";
		assertThat(compileAndRun(program)).isEqualTo("""
				"abcde"
				"person"
				"xy\"""");
	}

	@Test
	void defunClosingOverAWithMutexLockAndAnErrorOutputLambda() throws Exception {
		// Two closure-analysis shapes postmodern needs. with-mutex puts a VALUE in its
		// one-element spec (the opposite of the with-* stream macros), so FreeVarAnalyzer
		// has to expand it before it can see that the defun captures the top-level let's
		// lock -- prepare.lisp guards its statement-id counter exactly this way. And the
		// two standard stream variables are globals, not lexicals a lambda could capture:
		// generate-prepared reports its reconnect with (format *error-output* ...) from
		// inside a handler-bind handler -- and that report lands on standard ERROR (the
		// designator's default), not on standard output. (The mutex itself is a no-op on
		// WASM -- single-threaded by construction, .kb/mutexes.md -- so what is under
		// test here is only the analysis.)
		String program = """
				(let ((n 0) (lock (rontolisp:make-mutex)))
				  (defun next-id () (rontolisp:with-mutex (lock) (setf n (1+ n)) n)))
				(next-id)
				(print (next-id))
				(defun report (f) (funcall f "x"))
				(report (lambda (m) (format *error-output* "seen ~a" m)))
				""";
		assertThat(compileAndRun(program)).isEqualTo("2");
		assertThat(compileAndRunStderr(program)).isEqualTo("seen x");
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
	void countedDotimesLoopsMatchTheExpandedLowering() throws Exception {
		// A dotimes over a LITERAL bound compiles to a bare i64 counter with no boxed
		// shadow (.kb/wasm-counted-loops.md), so what has to be pinned is every way the
		// variable can still be observed -- and every shape the eligibility scan must
		// REFUSE, which then falls back to the ordinary let/while expansion. In order:
		// (1) the result form sees the counter holding the count; (2) a zero count runs
		// the body never and still binds it; (3) a `return` out of the body carries its
		// value through the %block; (4) a nested loop of the SAME name shadows and
		// restores; (5) an indexed store whose SUBSCRIPT is the counter stays eligible
		// (only the place's sub-place is a write); (6) a body that ASSIGNS the counter
		// falls back; (7) a counter CAPTURED by a lambda falls back (one binding for
		// the whole loop, so every closure sees the final value); (8) the binding
		// shadows an outer lexical of the same name; (9) the counter reads raw inside a
		// fused tree; (10) it is an ordinary Lisp value as a hash key and a string
		// index.
		String program = """
				(print (dotimes (i 5 i)))
				(print (dotimes (i 0 (list :done i))))
				(print (dotimes (i 10) (when (= i 3) (return (* i 100)))))
				(let ((acc nil))
				  (dotimes (i 2) (dotimes (i 3) (push i acc)))
				  (print (reverse acc)))
				(let ((v (make-array 4 :initial-element 0)))
				  (dotimes (i 4) (setf (aref v i) (* i i)))
				  (print v))
				(let ((seen nil))
				  (dotimes (i 6) (push i seen) (when (= i 1) (setq i 4)))
				  (print (reverse seen)))
				(let ((fs nil) (out nil))
				  (dotimes (i 3) (push (lambda () i) fs))
				  (dolist (f (reverse fs)) (push (funcall f) out))
				  (print (reverse out)))
				(let ((i :outer)) (dotimes (i 2)) (print i))
				(let ((s 0)) (dotimes (i 1000) (setq s (+ s (* i i) (- i 2)))) (print s))
				(let ((h (make-hash-table)) (str "abcdef") (out nil))
				  (dotimes (i 3) (setf (gethash i h) (char str i)))
				  (dotimes (i 3) (push (gethash i h) out))
				  (print (reverse out)))
				""";
		String expected = """
				5
				(:DONE 0)
				300
				(0 1 2 0 1 2)
				#(0 1 4 9)
				(0 1 5)
				(3 3 3)
				:OUTER
				333331000
				(#\\a #\\b #\\c)""";
		assertThat(compileAndRun(program)).isEqualTo(expected);
		// Unlike fusion and the unboxed locals this is not a speed-for-size trade, so
		// --optimize=size emits it too and must answer the same.
		byte[] small = new WasmLispCompiler(false, false, false, OptimizeLevel.SIZE)
			.compile(LispReader.readAllFromString(program));
		assertThat(runModule(small, "counted-dotimes-size.wasm")).isEqualTo(expected);
	}

	@Test
	void countedNumericForHeadsMatchTheExpandedLowering() throws Exception {
		// `loop`'s numeric `for` head lowers to a let* + while sandwich
		// (.kb/loop-iteration-heads.md), and the wasm backend recognizes the induction
		// variable in it and gives it the same bare i64 slot a dotimes counter gets
		// (.kb/wasm-counted-loops.md). Pinned here: every way the variable is still
		// observable, and every shape the eligibility scan must REFUSE -- which then
		// falls back to the ordinary boxed expansion. In order: (1) the accumulator sees
		// it; (2) an exclusive limit; (3) a descending step with `by`; (4) `finally`
		// sees the value ONE STEP PAST the limit, which is what the range proof has to
		// cover; (5) a `return` out of the body carries its value through the %block;
		// (6) a nested head of the SAME name shadows and restores; (7) an indexed store
		// whose SUBSCRIPT is the variable stays eligible; (8) a body that ASSIGNS it
		// falls back; (9) a CAPTURED variable falls back (one binding for the whole
		// loop, so every closure sees the final value); (10) a non-integral limit falls
		// back; (11) a limit at the i31 ceiling falls back -- the slot would box
		// inexactly; (12) the same loop written by hand as let + while; (13) a `while`
		// clause conjoined onto the head's own test; (14) the accumulator's overflow
		// promotion to exact arbitrary precision is untouched; (15) a negative start.
		String program = """
				(print (loop for i from 1 to 10 sum i))
				(print (loop for i from 0 below 4 collect i))
				(print (loop for i from 10 downto 1 by 3 collect i))
				(print (loop for i from 1 to 4 finally (return i)))
				(print (loop for i from 1 to 100 do (when (= i 3) (return (* i 100)))))
				(print (loop for i from 1 to 2 collect (loop for i from 7 to 8 collect i)))
				(let ((v (make-array 4 :initial-element 0)))
				  (loop for i from 0 below 4 do (setf (aref v i) (* i i)))
				  (print v))
				(print (loop for i from 1 to 6 do (when (= i 2) (setq i 4)) collect i))
				(let ((fs (loop for i from 1 to 3 collect (lambda () i))))
				  (print (mapcar #'funcall fs)))
				(print (loop for i from 1 to 4.5 collect i))
				(print (loop for i from 1073741820 to 1073741825 collect i))
				(let ((i :outer)) (loop for i from 1 to 2) (print i))
				(print (let ((i 0) (s 0))
				         (while (< i 5) (setq s (+ s (* i i))) (setq i (+ i 1)))
				         (list i s)))
				(print (loop for i from 1 to 10 while (< i 4) collect i))
				(print (loop for i from 1 to 3 sum (* i 1000000000000000000000)))
				(print (loop for i from -2 to 2 collect (- i)))
				""";
		String expected = """
				55
				(0 1 2 3)
				(10 7 4 1)
				5
				300
				((7 8) (7 8))
				#(0 1 4 9)
				(1 4 5 6)
				(4 4 4)
				(1 2 3 4)
				(1073741820 1073741821 1073741822 1073741823 1073741824 1073741825)
				:OUTER
				(5 30)
				(1 2 3)
				6000000000000000000000
				(2 1 0 -1 -2)""";
		assertThat(compileAndRun(program)).isEqualTo(expected);
		// Not a speed-for-size trade (no shadow, no duplicated fallback), so
		// --optimize=size emits it too and must answer the same.
		byte[] small = new WasmLispCompiler(false, false, false, OptimizeLevel.SIZE)
			.compile(LispReader.readAllFromString(program));
		assertThat(runModule(small, "counted-numeric-for.wasm")).isEqualTo(expected);
		// The assertions above pass whether or not the counter is unboxed -- the whole
		// point is that they must. So pin the recognizer FIRING too, by the one thing
		// output equality cannot show: the identical loop over a computed limit stays on
		// the boxed lowering and is bigger. Without this, a change to the loop expansion
		// that stopped fitting the pattern would cost the 2.2x in silence
		// (.kb/loop-iteration-heads.md).
		String literalLimit = "(let ((s 0)) (loop for i from 0 below 1000 do (setq s (+ s i))) (print s))";
		String computedLimit = "(let ((s 0) (n 1000)) (loop for i from 0 below n do (setq s (+ s i))) (print s))";
		byte[] counted = new WasmLispCompiler().compile(LispReader.readAllFromString(literalLimit));
		byte[] boxed = new WasmLispCompiler().compile(LispReader.readAllFromString(computedLimit));
		assertThat(runModule(counted, "counted-numeric-for-literal.wasm")).isEqualTo("499500");
		assertThat(runModule(boxed, "counted-numeric-for-computed.wasm")).isEqualTo("499500");
		assertThat(counted.length).as("the counted loop head is smaller than the boxed one it replaces")
			.isLessThan(boxed.length);
	}

	@Test
	void staticallyTypedPrintArgumentsPrintWhatTheValueDispatchWouldHave() throws Exception {
		// princ of a form the compiler can TYPE skips the value dispatch, which is what
		// keeps _princ_val and everything reachable only from it out of the module
		// (compiler/DoubleValuedForms, compiler/StringValuedForms). The shortcut is only
		// legal because it lands on the arm the dispatch would have taken, so the
		// rendering must be identical -- including the NaN/infinity text, the negative
		// zero and the readable spellings prin1/print produce for the same value.
		assertThat(compileAndRun("""
				(princ (* 1.0 3)) (terpri)
				(prin1 (+ 1.0 2)) (terpri)
				(print (/ 6.0 2))
				(princ (- 2.5)) (terpri)
				(princ (/ 2.0)) (terpri)
				(princ (* -1.0 0.0)) (terpri)
				(princ (/ 0.0 0.0)) (terpri)
				(princ (/ 1.0 0.0)) (terpri)
				(princ (/ -1.0 0.0)) (terpri)
				(princ (princ-to-string 42)) (terpri)
				(princ (format nil "a~ab" 7)) (terpri)
				(princ (with-output-to-string (s) (princ (* 1.0 3) s))) (terpri)
				(princ (with-output-to-string (*standard-output*) (princ (* 1.0 3))))
				""")).isEqualTo("""
				3.0
				3.0
				3.0
				-2.5
				0.5
				-0.0
				NaN
				Infinity
				-Infinity
				42
				a7b
				3.0
				3.0""");
	}

	@Test
	void theSizeLevelDeclinesTheSpeedTradesWithoutChangingAnyResult() throws Exception {
		// --optimize=size declines the two wasm-GC emissions that spend bytes on speed:
		// integer expression-tree fusion (every fused site emits its tree TWICE, raw
		// plus generic fallback) and the unboxed dual-representation locals that feed
		// it. Both are documented as optimizations with a TOTAL fallback
		// (.kb/wasm-int-fusion.md, .kb/wasm-unboxed-locals.md), so the level is only
		// honest if the generic-only module answers exactly what the fused one does --
		// which is what the two `MatchTheGenericPath` tests above assert WITHIN a
		// module and this one asserts BETWEEN the two levels, over the same shapes:
		// nested trees, overflow promotion into the limb tier, a float leaf, an
		// inlinable-defun substitution, an unboxed local degrading to a boxed shadow
		// (list, float, nil), the masked-wrap peephole, a fused comparison, and a
		// packed-vector raw store.
		String program = """
				(defun rol32c (x s) (logand (logior (ash x s) (ash x (- s 32))) 4294967295))
				(print (rol32c 2882400001 8))
				(print (logand (+ (* 3 5) (- 10 4) (mod 17 5)) 255))
				(print (* (+ 4294967296 1) (+ 4294967296 1)))
				(print (mod (- 0 4294967297) 4294967296))
				(let ((f 1.5)) (print (+ (logand 3 1) f)))
				(let ((acc 0) (i 0))
				  (tagbody top
				    (setq acc (logand (+ (rol32c acc 7) i) 4294967295))
				    (setq i (+ i 1))
				    (if (< i 64) (go top)))
				  (print acc))
				(let ((x 1))
				  (tagbody top (setq x (* x 3)) (if (< x 100000000000000000000) (go top)))
				  (print x))
				(let ((y 5)) (setq y (+ y 1)) (setq y (list y)) (print y))
				(let ((n nil)) (print n) (setq n (+ 1 2)) (print n) (setq n nil) (print n))
				(print (ldb (byte 32 0) (+ 9223372036854775807 9223372036854775807)))
				(print (< 9223372036854775807 (+ 9223372036854775807 1)))
				(let ((u (make-array 3 :element-type '(unsigned-byte 32) :initial-element 0)))
				  (setf (aref u 0) 4000000000)
				  (setf (aref u 1) (aref u 0))
				  (setf (aref u 2) (+ (aref u 0) 7))
				  (print u))
				""";
		List<LispVal> parsed = LispReader.readAllFromString(program);
		byte[] fast = new WasmLispCompiler(false, false, false, OptimizeLevel.DEFAULT).compile(parsed);
		byte[] small = new WasmLispCompiler(false, false, false, OptimizeLevel.SIZE).compile(parsed);
		assertThat(small.length).as("--optimize=size should emit a smaller module").isLessThan(fast.length);
		assertThat(runModule(small, "size.wasm")).isEqualTo(runModule(fast, "fast.wasm"));
		assertThat(runModule(fast, "fast.wasm")).isEqualTo("""
				3454992811
				23
				18446744082299486209
				4294967295
				2.5
				2434942088
				109418989131512359209
				(6)
				NIL
				3
				NIL
				4294967294
				T
				#(4000000000 4000000000 4000000007)""");
	}

	@Test
	void declaredArrayTypesEmitSingleArmAccessorsWithoutChangingResults() throws Exception {
		// Declaration-driven array emission (.kb/declarations-type-checks.md): a rank-1
		// aref/%aset/length site whose array representation is pinned down -- by a
		// (declare (type ...)) on a variable or parameter, a defstruct slot :type read
		// through its accessor (inherited slots included), an all-&optional deftype
		// alias, or a binding initializer this compile chose a representation for --
		// emits that ONE representation's accessor behind a trapping ref.cast instead of
		// the inline dispatch chain. The shapes below cover every source and every kind
		// (packed u8/u16/u32 with mask/readback edges past the i31 range, packed float,
		// general, string), plus shapes that must STAY generic and still answer right (a
		// rank-unknown declaration at a rank-2 site). Both optimize levels must agree
		// with each other and with the interpreter/JVM text.
		String program = """
				(deftype octet-vec (&optional length)
				  (let ((length (or length '*)))
				    `(simple-array (unsigned-byte 8) (,length))))
				(defstruct chunk
				  (data (make-array 4 :element-type '(unsigned-byte 8)) :type octet-vec)
				  (bits 0 :type (unsigned-byte 32)))
				(defstruct (wide-chunk (:include chunk))
				  (tab (make-array 3 :element-type '(unsigned-byte 16))
				       :type (simple-array (unsigned-byte 16) (3))))
				(defun fill-declared (buf n)
				  (declare (type (simple-array (unsigned-byte 8) (*)) buf)
				           (type (unsigned-byte 16) n))
				  (dotimes (i n buf)
				    (setf (aref buf i) (+ i 254))))
				(defun read-both (w i)
				  (declare (type wide-chunk w))
				  (+ (aref (chunk-data w) i) (aref (wide-chunk-tab w) i)))
				(defun sum-simple (v)
				  (declare (type simple-vector v))
				  (+ (svref v 0) (aref v 1)))
				(defun first-char (s)
				  (declare (type simple-string s))
				  (aref s 0))
				(let ((u32 (make-array 3 :element-type '(unsigned-byte 32)))
				      (f (make-array 2 :element-type 'double-float :initial-element 0.5d0))
				      (gen (make-array 2)))
				  (setf (aref u32 0) 4000000000)
				  (setf (aref u32 1) (aref u32 0))
				  (setf (aref u32 2) 4294967296)
				  (print (aref u32 0))
				  (print (aref u32 1))
				  (print (aref u32 2))
				  (print (length u32))
				  (setf (aref f 1) 2.25)
				  (print (aref f 1))
				  (setf (aref gen 0) 'a)
				  (print (aref gen 0))
				  (print (fill-declared (make-array 4 :element-type '(unsigned-byte 8)) 4))
				  (let ((w (make-wide-chunk)))
				    (setf (aref (chunk-data w) 1) 7)
				    (setf (aref (wide-chunk-tab w) 1) 65535)
				    (print (read-both w 1))
				    (print (aref (wide-chunk-data w) 1)))
				  (print (sum-simple (vector 30 12)))
				  (print (first-char "xyz"))
				  (print (typep (make-array 2 :element-type '(unsigned-byte 8)) 'octet-vec))
				  (let ((acc 0))
				    (do ((c (make-array 3 :element-type '(unsigned-byte 16)))
				         (i 0 (1+ i)))
				        ((>= i 3) nil)
				      (setf (aref c i) (* i 300))
				      (setq acc (+ acc (aref c i))))
				    (print acc))
				  (let ((maybe (make-array '(2 2))))
				    (declare (type (simple-array t *) maybe))
				    (setf (aref maybe 0 0) 5)
				    (print (aref maybe 0 0))))
				""";
		List<LispVal> parsed = LispReader.readAllFromString(program);
		byte[] fast = new WasmLispCompiler(false, false, false, OptimizeLevel.DEFAULT).compile(parsed);
		byte[] small = new WasmLispCompiler(false, false, false, OptimizeLevel.SIZE).compile(parsed);
		assertThat(runModule(small, "decl-size.wasm")).isEqualTo(runModule(fast, "decl-fast.wasm"));
		assertThat(runModule(fast, "decl-fast.wasm")).isEqualTo("""
				4000000000
				4000000000
				0
				3
				2.25
				A
				#(254 255 0 1)
				65542
				7
				42
				#\\x
				T
				900
				5""");
	}

	// Runs an already-compiled module. Used where the point of the test is to compare
	// two compilations of the SAME program, so the module name has to differ per run.
	private static String runModule(byte[] wasmBytes, String name) throws Exception {
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path(name));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc", "-W", "exceptions=y", path(name));
		assertThat(result.getExitCode()).as("exit code for %s%nstderr: %s", name, result.getStderr()).isZero();
		return result.getStdout().trim();
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

		// A RATIO operand takes the same a - b*(floor|trunc)(a/b) shape through the
		// exact rational helpers. This half was already right; it is pinned here
		// because the interpreter and the JVM had to be brought up to it
		// (ci-spec case ratio-mod-rem).
		assertThat(compileAndRun("""
				(let ((r 7/2))
				  (print (mod r 3))
				  (print (rem r 3))
				  (print (mod (- 0 r) 3))
				  (print (rem (- 0 r) 3))
				  (print (mod 5 3/4))
				  (print (mod r 1/2)))
				""")).isEqualTo("""
				1/2
				1/2
				5/2
				-1/2
				1/2
				0""");
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
	private static String compileAndRunWithPreload(String hostCode, String mainCode, OptimizeLevel optimize)
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
		assertThat(compileAndRunWithPreload(host, main, OptimizeLevel.NONE)).isEqualTo("""
				42
				3
				10.0
				(101 102 103)
				11""");
	}

	@Test
	void bytesBoundaryCrossesThePreloadBoundaryByLength() throws Exception {
		// A preloaded wasm host has its own linear memory, so byte CONTENT cannot cross
		// this pair (that is a JS-host affair -- WasmBytesBoundaryE2eTest round-trips
		// the ff fe 41 content on node). What a wasm-host pair still proves end-to-end
		// under a real engine is the whole :bytes plumbing -- parameter staging, the
		// caller-passed (ptr,cap) result convention, the full-length answer -- through
		// the values that DO cross: the lengths.
		String host = """
				(defun host-blen (v) (length v))
				(defun host-bsrc () (make-array 5 :element-type '(unsigned-byte 8)))
				(rontolisp:wasm-export 'host-blen :as "blen" :params '(:bytes) :returns :int)
				(rontolisp:wasm-export 'host-bsrc :as "bsrc" :params '() :returns :bytes)
				""";
		String main = """
				(rontolisp:wasm-import 'blen :from "host" :params '(:bytes) :returns :int)
				(rontolisp:wasm-import 'bsrc :from "host" :params '() :returns :bytes)
				(let ((buf (make-array 3 :element-type '(unsigned-byte 8))))
				  (print (blen buf))
				  (print (bsrc buf)))
				""";
		assertThat(compileAndRunWithPreload(host, main, OptimizeLevel.NONE)).isEqualTo("""
				3
				5""");
	}

	@Test
	void asyncImportAnswersASettledFutureThatAwaitResolves() throws Exception {
		// :async t: the call answers a FUTURE -- rontolisp:await resolves it and
		// futurep sees it, like an `async func` binding on every other backend. The
		// preloaded host module answers synchronously, which the option's contract
		// allows (started == settled on this backend either way). Scalar types only:
		// a preloaded wasm host has its own linear memory, so the (ptr,len) string
		// boundary is a JS-host affair.
		String host = """
				(defun host-add (a b) (+ a b))
				(defun host-scale (x) (* x 2.5))
				(rontolisp:wasm-export 'host-add :as "add" :params '(:int :int) :returns :int)
				(rontolisp:wasm-export 'host-scale :as "scale" :params '(:float) :returns :float)
				""";
		String main = """
				(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int :async t)
				(rontolisp:wasm-import 'scale :from "host" :params '(:float) :returns :float :async t)
				(print (rontolisp:futurep (add 1 2)))
				(print (rontolisp:await (add 20 22)))
				(print (rontolisp:await (scale 4.0)))
				(print (rontolisp:await (funcall #'add 1 2)))
				""";
		assertThat(compileAndRunWithPreload(host, main, OptimizeLevel.NONE)).isEqualTo("""
				T
				42
				10.0
				3""");
	}

	@Test
	void aGuardedExportAnswersThroughASynchronousHost() throws Exception {
		// An :async t import puts the re-entry guard into every export wrapper; a
		// synchronous preloaded host never re-enters, so the guard must be
		// invisible -- set on entry, cleared on return, the answer unchanged.
		String host = """
				(defun host-add (a b) (+ a b))
				(rontolisp:wasm-export 'host-add :as "add" :params '(:int :int) :returns :int)
				""";
		String main = """
				(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int :async t)
				(defun plus (a b) (rontolisp::%future-force (add a b)))
				(rontolisp:wasm-export 'plus :params '(:int :int) :returns :int)
				""";
		byte[] hostBytes = new WasmLispCompiler(false, false, true).compile(LispReader.readAllFromString(host));
		byte[] mainBytes = new WasmLispCompiler().compile(LispReader.readAllFromString(main));
		wasmtime.copyFileToContainer(Transferable.of(hostBytes), path("host.wasm"));
		wasmtime.copyFileToContainer(Transferable.of(mainBytes), path("main.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "--invoke", "plus", "-W", "gc", "-W",
				"exceptions=y", "--preload", "host=" + path("host.wasm"), path("main.wasm"), "20", "22");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("42");
	}

	@Test
	void anExportHandsBackAnAsyncImportsFutureWithoutForcingItByHand() throws Exception {
		// The same export minus the %future-force: the target simply returns what the
		// :async t import answered, which is a settled future. The boundary declares
		// :int, so the wrapper resolves it instead of unboxing a future as one -- with
		// no async-defun anywhere, which is why the module's future producer (and hence
		// the wrapper's resolve) is decided from the import declaration too.
		String host = """
				(defun host-add (a b) (+ a b))
				(rontolisp:wasm-export 'host-add :as "add" :params '(:int :int) :returns :int)
				""";
		String main = """
				(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int :async t)
				(defun plus (a b) (add a b))
				(rontolisp:wasm-export 'plus :params '(:int :int) :returns :int)
				""";
		byte[] hostBytes = new WasmLispCompiler(false, false, true).compile(LispReader.readAllFromString(host));
		byte[] mainBytes = new WasmLispCompiler().compile(LispReader.readAllFromString(main));
		wasmtime.copyFileToContainer(Transferable.of(hostBytes), path("host.wasm"));
		wasmtime.copyFileToContainer(Transferable.of(mainBytes), path("main.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "--invoke", "plus", "-W", "gc", "-W",
				"exceptions=y", "--preload", "host=" + path("host.wasm"), path("main.wasm"), "20", "22");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("42");
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
		assertThat(compileAndRunWithPreload(host, main, OptimizeLevel.NONE)).isEqualTo("""
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
		assertThat(compileAndRunWithPreload(host, main, OptimizeLevel.DEFAULT)).isEqualTo("42");
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
		assertThat(compileAndRunWithPreload(host, main, OptimizeLevel.NONE)).isEqualTo("T\nNIL");
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
	// export, proving the module runs on a plain MVP runtime with no wasm-GC and
	// no import object.
	private static String compileNoGcAndInvoke(OptimizeLevel optimize, String lispCode, String function, String... args)
			throws Exception {
		return compileNoGcAndInvoke(optimize, false, lispCode, function, args);
	}

	// As above, with an explicit --simd switch: simd=true lowers the vectorizable vec:
	// kernels to native v128 (f64x2/f32x4), simd=false to scalar linear-memory loops.
	// Both
	// run on a plain MVP runtime; the v128 build additionally needs the SIMD proposal (on
	// by
	// default in wasmtime).
	private static String compileNoGcAndInvoke(OptimizeLevel optimize, boolean simd, String lispCode, String function,
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
		// with no wasm-GC requirement. Integers are unboxed i64, floats f64, so the
		// results match the interpreter across the pure-numeric range.
		String fact = """
				(defun fact (n) (if (<= n 1) 1 (* n (fact (1- n)))))
				(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
				""";
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, fact, "fact", "5")).isEqualTo("120");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, fact, "fact", "10")).isEqualTo("3628800");
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
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "f", "100000000")).isEqualTo("1");
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
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "sumsquared", "3", "4")).isEqualTo("49");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "sumsquared", "100000", "100000"))
			.isEqualTo("40000000000");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.DEFAULT, program, "sumsquared", "100000", "100000"))
			.isEqualTo("40000000000");
	}

	// Compiles with --no-gc --component (the compact reactor component) and
	// calls an export through the canonical ABI with WAVE syntax. The component carries
	// the plain MVP core module with NO adapter / import block / mem module, so it runs
	// with ZERO extra flags: no wasm-GC, no component-model-async flags -- assert that by
	// passing none. Like the GC component path this is the supported invoke path, so
	// stderr must carry no "experimental" warning.
	private static String compileNoGcComponentAndInvoke(String lispCode, String waveInvocation) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		byte[] componentBytes = new NoGcWasmCompiler(OptimizeLevel.NONE, false, true).compile(program);
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
		// result. All with zero wasmtime flags (no wasm-GC, no async built-ins).
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
		byte[] component = new NoGcWasmCompiler(OptimizeLevel.NONE, false, true)
			.compile(LispReader.readAllFromString("""
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
		// before the wrap (the GC path does the same).
		String program = """
				(defun sum-sq (a b) (* (+ a b) (+ a b)))
				(rontolisp:wasm-export 'sum-sq :as "sum-squared" :params '(:int :int) :returns :int)
				""";
		assertThat(compileNoGcComponentAndInvoke(program, "sum-squared(2, 3)")).isEqualTo("25");
		byte[] optimized = new NoGcWasmCompiler(OptimizeLevel.DEFAULT, false, true)
			.compile(LispReader.readAllFromString(program));
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
		// with no wasm-GC requirement.
		String program = """
				(defun used (n) (* n 2))
				(defun dead (n) (+ n 999))
				(defun entry (n) (used (used n)))
				(rontolisp:wasm-export 'entry :params '(:int) :returns :int)
				""";
		List<LispVal> parsed = LispReader.readAllFromString(program);
		assertThat(compileNoGcAndInvoke(OptimizeLevel.DEFAULT, program, "entry", "5")).isEqualTo("20");
		// The optimized module is no larger than the plain one (the unreachable `dead`
		// function is dropped); behavior is identical either way.
		int plain = new NoGcWasmCompiler(OptimizeLevel.NONE).compile(parsed).length;
		int optimized = new NoGcWasmCompiler(OptimizeLevel.DEFAULT).compile(parsed).length;
		assertThat(optimized).isLessThanOrEqualTo(plain);
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "entry", "5")).isEqualTo("20");
	}

	@Test
	void noGcSupportsFloatBoolAndCrossFunctionCalls() throws Exception {
		// :float (f64) and :bool (i32 0/1) boundaries, mutual recursion / cross-calls,
		// and mod -- all on the unboxed scalar path, with no wasm-GC requirement.
		String program = """
				(defun gcd2 (a b) (if (= b 0) a (gcd2 b (mod a b))))
				(defun area (r) (* 3.14159 (* r r)))
				(defun in-range (x) (if (< x 0) nil (if (> x 100) nil t)))
				(rontolisp:wasm-export 'gcd2 :params '(:int :int) :returns :int)
				(rontolisp:wasm-export 'area :params '(:float) :returns :float)
				(rontolisp:wasm-export 'in-range :params '(:int) :returns :bool)
				""";
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "gcd2", "48", "36")).isEqualTo("12");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "area", "2.0")).isEqualTo("12.56636");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "in-range", "50")).isEqualTo("1");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "in-range", "200")).isEqualTo("0");
	}

	@Test
	void noGcSupportsIterationAndLocalMutation() throws Exception {
		// dotimes / do loops with a let-bound accumulator mutated by setq -- the
		// iterative
		// counterparts of recursion, all on the unboxed scalar path with no wasm-GC
		// requirement. The accumulator in `sumsq` starts as integer 0 but is summed with
		// floats, so its inferred type widens to f64.
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
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "sum-upto", "100")).isEqualTo("4950");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "ifact", "10")).isEqualTo("3628800");
		// 0^2 + 1^2 + ... + 4^2 = 30; printed by wasmtime as an f64.
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "sumsq", "5")).isEqualTo("30");
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
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "count-down", "42")).isEqualTo("42");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "count-down", "0")).isEqualTo("0");
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
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "root", "16.0")).isEqualTo("4");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "band", "12", "10")).isEqualTo("8");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "shr", "1024", "3")).isEqualTo("128");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "popcount", "255")).isEqualTo("8");
		// The loop-based popcount also survives the tree shaker under --optimize.
		assertThat(compileNoGcAndInvoke(OptimizeLevel.DEFAULT, program, "popcount", "43")).isEqualTo("4");
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
		assertThat(noGcStringLength(OptimizeLevel.NONE, program, "band", "12")).isEqualTo(12);
		assertThat(noGcStringLength(OptimizeLevel.NONE, program, "band", "0")).isZero();
		// Composes with the tree shaker (--optimize).
		assertThat(noGcStringLength(OptimizeLevel.DEFAULT, program, "band", "30")).isEqualTo(30);
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
		// interpreter's output for every line below, INCLUDING every float: __ftoa
		// renders the Schubfach shortest round-trip decimal, so the old
		// large-finite-float print-shape divergence is gone.
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
				9.223372036854776e18
				"hello"
				bare
				T
				NIL
				5
				"a2.5\"""";
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "show")).isEqualTo(expected);
		// The import + __write_stdout funnel survive the tree shaker under --optimize.
		assertThat(compileNoGcAndInvoke(OptimizeLevel.DEFAULT, program, "show")).isEqualTo(expected);
	}

	@Test
	void noGcPrintEscapesQuotesAndBackslashesInStrings() throws Exception {
		// The readable renderer owes the reader its escapes here too (todo 216):
		// emitWriteStringEscaped writes the content as runs, one __write_stdout per
		// unescaped stretch plus the single '\' literal, so nothing is allocated.
		// princ stays the no-escape half.
		String program = """
				(defun show ()
				  (print "{\\"hello\\":\\"aaa\\"}")
				  (print "a\\"b\\\\c")
				  (princ "a\\"b\\\\c")
				  (terpri)
				  (print "plain")
				  (print (concatenate 'string "x" "\\"" "y")))
				(rontolisp:wasm-export 'show :params '() :returns :void)
				""";
		String expected = """
				"{\\"hello\\":\\"aaa\\"}"
				"a\\"b\\\\c"
				a"b\\c
				"plain"
				"x\\"y\"""";
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "show")).isEqualTo(expected);
		assertThat(compileNoGcAndInvoke(OptimizeLevel.DEFAULT, program, "show")).isEqualTo(expected);
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
		// The --no-gc string primitives: length / subseq / string= / char (a character
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
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "route", "6")).isEqualTo("1");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "route", "4")).isEqualTo("2");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "route", "-1")).isEqualTo("3");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "digits", "0")).isEqualTo("1");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "digits", "12345")).isEqualTo("5");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "digits", "-42")).isEqualTo("3");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, program, "code-at", "1")).isEqualTo("98");
		// Composes with the tree shaker (--optimize).
		assertThat(compileNoGcAndInvoke(OptimizeLevel.DEFAULT, program, "route", "6")).isEqualTo("1");
		// A string-producing op with no literal and no :string boundary still gets the
		// memory + helpers (subseq/princ-to-string flag the memory as used).
		String noLiteral = """
				(defun width (n) (length (princ-to-string (* n n))))
				(rontolisp:wasm-export 'width :params '(:int) :returns :int)
				""";
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, noLiteral, "width", "100")).isEqualTo("5");
	}

	@Test
	void noGcRunsDoubleFloatVecKernelsWithF64x2Simd() throws Exception {
		// Under --no-gc --simd, the packed double-float (F64VEC) vec: kernels lower to
		// native
		// f64x2 v128 SIMD and
		// run on a plain MVP runtime (no wasm-GC requirement). Results are wrapped in
		// truncate so the host boundary is a deterministic :int (independent of
		// wasmtime's float printing).
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
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "dot55", "0")).isEqualTo("55");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "sum280", "0")).isEqualTo("280");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "scalesum", "0")).isEqualTo("45");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "buildsum", "5")).isEqualTo("30");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "buildsum", "8")).isEqualTo("140");
		// vec:ones / vec:arange with no element-type build the default F64VEC (the
		// double path is unaffected by the element-type constructor argument).
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "consd", "0")).isEqualTo("5");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "arand", "0")).isEqualTo("6");
	}

	@Test
	void noGcRunsSingleFloatVecKernelsWithF32x4Simd() throws Exception {
		// Under --no-gc --simd, the packed single-float (F32VEC) vec: kernels lower to
		// native
		// f32x4 v128 SIMD
		// (four lanes per iteration) and run on a plain MVP runtime (no wasm-GC
		// requirement). Inputs are
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
				(defun consf (i) (truncate (vec:sum (vec:ones 5 :element-type 'single-float))))
				(defun aranf (i) (truncate (vec:sum (vec:arange 4 :element-type 'single-float))))
				(rontolisp:wasm-export 'dot55 :params '(:int) :returns :int)
				(rontolisp:wasm-export 'sum280 :params '(:int) :returns :int)
				(rontolisp:wasm-export 'addref :params '(:int) :returns :int)
				(rontolisp:wasm-export 'scalesum :params '(:int) :returns :int)
				(rontolisp:wasm-export 'buildsum :params '(:int) :returns :int)
				(rontolisp:wasm-export 'consf :params '(:int) :returns :int)
				(rontolisp:wasm-export 'aranf :params '(:int) :returns :int)
				""";
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "dot55", "0")).isEqualTo("55");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "sum280", "0")).isEqualTo("280");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "addref", "0")).isEqualTo("11");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "addref", "2")).isEqualTo("33");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "scalesum", "0")).isEqualTo("45");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "buildsum", "5")).isEqualTo("30");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "buildsum", "8")).isEqualTo("140");
		// vec:ones / vec:arange with a literal 'single-float construct an F32VEC
		// natively: sum(ones 5) = 5, sum(arange 4) = 0+1+2+3 = 6.
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "consf", "0")).isEqualTo("5");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, true, program, "aranf", "0")).isEqualTo("6");
		// --optimize (tree-shaken module) still runs the f32x4 kernels identically.
		assertThat(compileNoGcAndInvoke(OptimizeLevel.DEFAULT, true, program, "dot55", "0")).isEqualTo("55");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.DEFAULT, true, program, "sum280", "0")).isEqualTo("280");
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
		// simd=false -- the scalar loops, run with no wasm-GC requirement (and no SIMD
		// proposal needed).
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, false, doubles, "dot55", "0")).isEqualTo("55");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, false, doubles, "sum280", "0")).isEqualTo("280");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, false, doubles, "scalesum", "0")).isEqualTo("45");
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
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, false, singles, "dot55", "0")).isEqualTo("55");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, false, singles, "addref", "0")).isEqualTo("11");
		assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, false, singles, "addref", "2")).isEqualTo("33");
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
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, doubles, "addinto", "0")).isEqualTo("2");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, doubles, "addinto", "4")).isEqualTo("10");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, doubles, "scaleinto", "0")).isEqualTo("45");
			// 100000 iterations x a fresh 5-element vector would be ~4.4 MB of bump heap
			// in
			// the allocating form; -into allocates once, before the loop.
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, doubles, "accumulate", "100000"))
				.isEqualTo("400000");
		}
		// Single-float: the f32 stride, a pure scalar-tail count (3) and a lane-group
		// count.
		String singles = """
				(defun addinto (i)
				  (let* ((o (vec:zeros 3 :element-type 'single-float)) (a #f(1.0 2.0 3.0)) (b #f(10.0 20.0 30.0)))
				    (truncate (aref (vec:add-into o a b) i))))
				(defun mulinto (i)
				  (let* ((o (vec:zeros 8 :element-type 'single-float)) (a (vec:arange 8 :element-type 'single-float)))
				    (truncate (vec:sum (vec:mul-into o a a)))))
				(rontolisp:wasm-export 'addinto :params '(:int) :returns :int)
				(rontolisp:wasm-export 'mulinto :params '(:int) :returns :int)
				""";
		for (boolean simd : new boolean[] { false, true }) {
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, singles, "addinto", "0")).isEqualTo("11");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, singles, "addinto", "2")).isEqualTo("33");
			// sum of i^2 for i in 0..7 = 140
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, singles, "mulinto", "0")).isEqualTo("140");
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
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, doubles, "ufuncs", "0")).isEqualTo("77");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, doubles, "intos", "0")).isEqualTo("3");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, doubles, "intos", "3")).isEqualTo("12");
		}
		String singles = """
				(defun ufuncs (i)
				  (let* ((v (vec:negative (vec:arange 6 :element-type 'single-float)))
				         (a (vec:abs v))
				         (s (vec:sqrt (vec:square v)))
				         (r (vec:reciprocal #f(2.0 4.0 8.0))))
				    (truncate (+ (vec:sum a) (vec:sum s) (* 8.0 (vec:sum r))))))
				(rontolisp:wasm-export 'ufuncs :params '(:int) :returns :int)
				""";
		for (boolean simd : new boolean[] { false, true }) {
			// sum a = 15, sum s = 15, 8 * (1/2 + 1/4 + 1/8) = 7 -> 37
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, singles, "ufuncs", "0")).isEqualTo("37");
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
				  (truncate (vec:sum (vec:sign (vec:negative (vec:arange 4 :element-type 'single-float))))))
				(rontolisp:wasm-export 'expd :params '(:int) :returns :int)
				(rontolisp:wasm-export 'expf :params '(:int) :returns :int)
				(rontolisp:wasm-export 'expzero :params '(:int) :returns :int)
				(rontolisp:wasm-export 'sgn :params '(:int) :returns :int)
				(rontolisp:wasm-export 'sgninto :params '(:int) :returns :int)
				(rontolisp:wasm-export 'sgnf :params '(:int) :returns :int)
				""";
		for (boolean simd : new boolean[] { false, true }) {
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "expd", "0")).isEqualTo(wasmGcExpD);
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "expf", "0")).isEqualTo(wasmGcExpF);
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "expzero", "0")).isEqualTo("3");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "sgn", "0")).isEqualTo("-1");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "sgn", "1")).isEqualTo("0");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "sgn", "2")).isEqualTo("1");
			// sign-into aliases its operand (sign of sign is sign): -100 + 0 + 1.
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "sgninto", "0")).isEqualTo("-99");
			// negative(arange) = (-0.0 -1.0 -2.0 -3.0); sign maps -0.0 to 0.0 here
			// (the wasm family's own edge), so the sum is -3.
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "sgnf", "0")).isEqualTo("-3");
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
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "logd", "0")).isEqualTo(wasmGcLogD);
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "logf", "0")).isEqualTo(wasmGcLogF);
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "logone", "0")).isEqualTo("5");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "loginto", "0")).isEqualTo("5");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "tanhd", "0")).isEqualTo(wasmGcTanhD);
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "tanhf", "0")).isEqualTo(wasmGcTanhF);
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "tanhsat", "0")).isEqualTo("-1");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "tanhsat", "1")).isEqualTo("0");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "tanhsat", "2")).isEqualTo("1");
			// tanh-into then read back the saturated triple: -100 + 0 + 1.
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "tanhinto", "0")).isEqualTo("-99");
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
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "sind", "0")).isEqualTo(wasmGcSinD);
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "sinf", "0")).isEqualTo(wasmGcSinF);
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "cosd", "0")).isEqualTo(wasmGcCosD);
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "tand", "0")).isEqualTo(wasmGcTanD);
			// sin(0) + 3 * cos(0) + tan(0) = 3.
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "zeros", "0")).isEqualTo("3");
			// tan(cos(sin(0))) per element = tan(1); 3 * tan(1) truncates to 4.
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "sininto", "0")).isEqualTo("4");
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
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "atand", "0")).isEqualTo(wasmGcAtanD);
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "asinf", "0")).isEqualTo(wasmGcAsinF);
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "acosd", "0")).isEqualTo(wasmGcAcosD);
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "sinhd", "0")).isEqualTo(wasmGcSinhD);
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "coshf", "0")).isEqualTo(wasmGcCoshF);
			// atan(0) + asin(0) + acos(1) + sinh(0) + 3 * cosh(0) = 3.
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "anchors", "0")).isEqualTo("3");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "arcinto", "0")).isEqualTo(wasmGcInto);
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
				  (let ((a (vec:zeros 5 :element-type 'single-float))
				        (out (vec:zeros 5 :element-type 'single-float)))
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
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "probe", "0")).isEqualTo("29406");
			// relu(a) = (0 0 0 1 2); maximum with a is unchanged; clip to [-1.5, 1.5]
			// gives (0 0 0 1 1.5), whose sum is 2.5 -> 250.
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "probef", "0")).isEqualTo("250");
			// minimum(a, b) = (-2 -1 0 -1 -2); maximum with a = (-2 -1 0 1 2);
			// relu of that = (0 0 0 1 2) -> 3. Exercises aliasing at every step.
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, source, "probeinto", "0")).isEqualTo("3");
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
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, doubles, "gemv", "0")).isEqualTo("40");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, doubles, "gemv", "1")).isEqualTo("190");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, doubles, "gemv", "2")).isEqualTo("340");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, doubles, "gemvinto", "2")).isEqualTo("340");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, doubles, "flat", "6")).isEqualTo("11");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, doubles, "flat", "5")).isEqualTo("0");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, singles, "gemvf", "0")).isEqualTo("70");
			assertThat(compileNoGcAndInvoke(OptimizeLevel.NONE, simd, singles, "gemvf", "1")).isEqualTo("280");
		}
		// Composes with the tree shaker (--optimize).
		assertThat(compileNoGcAndInvoke(OptimizeLevel.DEFAULT, true, doubles, "gemv", "1")).isEqualTo("190");
	}

	// Invokes a --no-gc :string-returning export and returns the length component of the
	// (content-ptr, length) host result. wasmtime prints multi-value results one per
	// line,
	// so the length is the last whitespace-separated token. (The string-parameter side of
	// the ABI needs a host that writes linear memory and is exercised by the runnable
	// docs
	// examples / playground rather than the wasmtime-only container here.)
	private static int noGcStringLength(OptimizeLevel optimize, String lispCode, String function, String... args)
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
		byte[] wasmBytes = new WasmLispCompiler(false, false, noWasi, OptimizeLevel.DEFAULT).compile(program);
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
		int plain = new WasmLispCompiler(false, false, true, OptimizeLevel.NONE).compile(parsed).length;
		int optimized = new WasmLispCompiler(false, false, true, OptimizeLevel.DEFAULT).compile(parsed).length;
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
		byte[] wasmBytes = new WasmLispCompiler(false, false, false, OptimizeLevel.DEFAULT)
			.compile(LispReader.readAllFromString(program));
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("3\n\"HI\"");
	}

	@Test
	void optimizedProgramKeepsEveryStringALiveBodyStillAddresses() throws Exception {
		// --optimize cuts out of the string blob every range no surviving body holds an
		// i32.const for -- the builtin wrappers Pass 2a compiles intern their literals
		// and the shaker then deletes the wrappers. The names below are interned by
		// wrapper bodies too (STRING, LIST, the sequence keywords), so a cut that used
		// the wrong owner would print garbage or an empty symbol rather than trap.
		// Symbol identity is offset equality, so the eq tests pin that the survivors
		// kept their addresses.
		String program = """
				(defun tag (x) (cond ((eq x 'alpha) "A") ((eq x 'beta) "B") (t "?")))
				(print (tag 'alpha))
				(print (tag 'gamma))
				(print '(string list alpha))
				(print (concatenate 'string "n=" "7"))
				(print (subseq "abcdef" 1 3))
				""";
		byte[] wasmBytes = new WasmLispCompiler(false, false, false, OptimizeLevel.DEFAULT)
			.compile(LispReader.readAllFromString(program));
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("""
				"A"
				"?"
				(STRING LIST ALPHA)
				"n=7"
				"bc\"""");
	}

	@Test
	void optimizedModulesPrintExactlyWhatTheUnoptimizedOnesDo() throws Exception {
		// A dropped byte range that a live body still reads shows up as WRONG OUTPUT,
		// not as a trap, so the guard has to be a differential run rather than a
		// validity check. Each program below prints values whose text comes out of the
		// string blob (symbols, strings, struct/keyword printing, a format control, a
		// hash-table key) on a shape where the funcall-dispatch gate applies. The
		// interning programs pin the per-entry range pairing: a runtime intern must
		// still canonicalize to a LIVE literal's offset, a name the module never spells
		// must stay self-consistent through the runtime table with cut holes present,
		// and a zero-length probe must not match a cut row (which reads as (0,0) --
		// _intern's skip arm; '|| holds a live zero-length entry to diverge onto).
		List<String> programs = List.of("(print (list 'a \"b\" #\\c :d 1/2 2.5 (list 1 2)))",
				"(defstruct pt x y) (let ((p (make-pt :x 1 :y 2))) (print p) (print (pt-x p)))",
				"(print (format nil \"~a/~s/~d\" 'sym \"str\" 42))",
				"(print (mapcar #'string-upcase (list \"ab\" \"cd\")))",
				"(print (assoc \"b\" (list (cons \"a\" 1) (cons \"b\" 2)) :test #'equal))",
				"(print (handler-case (error \"boom\") (error (e) 'caught)))",
				"(let ((h (make-hash-table :test 'equal))) (setf (gethash \"k\" h) 'v) (print (gethash \"k\" h)))",
				"(print (with-output-to-string (s) (princ 'hello s) (princ \" \" s) (princ 42 s)))",
				"(defun up (s) (intern (string-upcase s))) (print (eq (up \"alpha\") 'alpha)) (print (up \"alpha\"))",
				"(defun mk (a b) (intern (concatenate 'string a b)))"
						+ " (print (eq (mk \"ZE\" \"TA\") (mk \"ZE\" \"TA\"))) (print (mk \"ZE\" \"TA\"))",
				"(defun cut-of (s) (subseq s 1 1)) (print (eq (intern (cut-of \"x\")) '||))",
				// Byte-identical defun bodies fold to one shared body under --optimize;
				// each name keeps its own dispatch id and closure struct, so the direct
				// calls, the funcall path and eq-distinctness must all survive the fold.
				"(defun fold-twin-a (n) (* n 17)) (defun fold-twin-b (n) (* n 17))"
						+ " (print (fold-twin-a 2)) (print (funcall #'fold-twin-b 3))"
						+ " (print (eq #'fold-twin-a #'fold-twin-b))",
				// A generic with a branch no call site can select: the narrowed
				// dispatcher (compiler/GenericDispatchNarrowing) must answer every
				// live call -- the kept branch and the default fallback -- exactly
				// like the full one.
				"(defgeneric gsz (x)) (defmethod gsz ((x integer)) (* x 2))"
						+ " (defmethod gsz ((x string)) 999) (defmethod gsz (x) 'other)"
						+ " (print (gsz 21)) (print (gsz 'sym))");
		for (String program : programs) {
			List<LispVal> parsed = LispReader.readAllFromString(program);
			String plain = runOptimizeLevel(parsed, OptimizeLevel.NONE);
			String optimized = runOptimizeLevel(parsed, OptimizeLevel.DEFAULT);
			assertThat(optimized).as("--optimize changed the output of: %s", program).isEqualTo(plain);
		}
	}

	private static String runOptimizeLevel(List<LispVal> program, OptimizeLevel optimize) throws Exception {
		byte[] wasmBytes = new WasmLispCompiler(false, false, false, optimize).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc", "-W", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("exit code at %s\nstderr: %s", optimize, result.getStderr()).isZero();
		return result.getStdout().trim();
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
		byte[] wasmBytes = new WasmLispCompiler(false, false, false, OptimizeLevel.DEFAULT)
			.compile(LispReader.readAllFromString(program));
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("81");
	}

	@Test
	void noWasiModuleDiscardsOutputInsteadOfTrapping() throws Exception {
		// Output is the ONE no-wasi stub that is a sink rather than a trap: a reactor
		// host provides no file descriptors, so the bytes go nowhere and the call
		// returns normally. The export still answers -- only the print is lost.
		String program = """
				(defun shout (n) (print n) (* n 2))
				(rontolisp:wasm-export 'shout :params '(:int) :returns :int)
				""";
		byte[] wasmBytes = new WasmLispCompiler(false, false, true).compile(LispReader.readAllFromString(program));
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "--invoke", "shout", "-W", "gc", "-W",
				"exceptions=y", path("test.wasm"), "7");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		// 14 is the return value wasmtime prints; the 7 the program printed is gone.
		assertThat(result.getStdout().trim()).isEqualTo("14");
	}

	@Test
	void noWasiModuleWithATopLevelPrintStillInstantiates() throws Exception {
		// The shape that made (clack:clackup ...) impossible on a reactor: a print
		// from a TOP-LEVEL form runs inside _initialize, so a trapping stub killed the
		// instance before any export could be called -- with no diagnostic naming the
		// culprit. Every library that logs while it loads has this shape.
		String program = """
				(format t "loading~%")
				(defun answer () 42)
				(rontolisp:wasm-export 'answer :returns :int)
				""";
		assertThat(compileNoWasiAndInvoke(program, "answer")).isEqualTo("42");
	}

	@Test
	void noWasiModuleDrawsRandomNumbersFromItsOwnGenerator() throws Exception {
		// The shape that made lack-request (and every Clack application that reads a
		// request through it) unloadable on a Worker: a library names a temporary
		// directory with a TOP-LEVEL (random ...), so the draw happens inside
		// _initialize and a trapping stub killed the instance before any export could
		// run. random_get is a self-contained SplitMix64 here, so the draw answers.
		String program = """
				(defvar *seed* (random 1000000))
				(defun stamp () *seed*)
				(rontolisp:wasm-export 'stamp :returns :int)
				(defun draw (n) (random n))
				(rontolisp:wasm-export 'draw :params '(:int) :returns :int)
				(defun spread (n)
				  ;; distinct draws, not one value repeated: count the distinct results
				  ;; of n draws over a range wide enough that collisions are unlikely.
				  (let ((seen '()))
				    (dotimes (i n) (pushnew (random 1000000) seen))
				    (length seen)))
				(rontolisp:wasm-export 'spread :params '(:int) :returns :int)
				""";
		assertThat(Integer.parseInt(compileNoWasiAndInvoke(program, "stamp"))).isBetween(0, 999999);
		assertThat(Integer.parseInt(compileNoWasiAndInvoke(program, "draw", "10"))).isBetween(0, 9);
		assertThat(compileNoWasiAndInvoke(program, "spread", "50")).isEqualTo("50");
	}

	@Test
	void noWasiHostSeedReplacesTheGeneratorsStartState() throws Exception {
		// Two runs of the same module: unseeded they walk one fixed sequence (that is
		// the documented price of a module that imports nothing), and a host that calls
		// __ronto_seed_random first gets a different one. wasmtime's --invoke calls a
		// single export, so the seed and the draw are one Lisp function apart: `seeded`
		// pokes the state through the hook and then draws.
		String program = """
				(defun draw () (random 1000000))
				(rontolisp:wasm-export 'draw :returns :int)
				""";
		String first = compileNoWasiAndInvoke(program, "draw");
		assertThat(compileNoWasiAndInvoke(program, "draw")).as("unseeded: the same sequence every instance")
			.isEqualTo(first);
		// The hook is a plain export the host calls before anything else; driving it
		// from wasmtime needs a second --invoke, so this leg only pins that it exists
		// and is callable. The behavioural check (seeded runs differ) is the Node one in
		// examples/cloudflare-workers/.
		assertThat(compileNoWasiAndInvoke(program, "__ronto_seed_random", "42")).isEmpty();
	}

	// i64.const 0x9E3779B97F4A7C15 -- the SplitMix64 golden-ratio gamma, in the signed
	// LEB128 the emitter writes. Nothing else in an emitted module spells this constant,
	// so its presence IS "the generator is in here".
	private static final byte[] SPLITMIX64_GAMMA_CONST = { 0x42, (byte) 0x95, (byte) 0xf8, (byte) 0xa9, (byte) 0xfa,
			(byte) 0x97, (byte) 0xb7, (byte) 0xde, (byte) 0x9b, (byte) 0x9e, 0x7f };

	private static boolean carriesTheGenerator(byte[] module) {
		outer: for (int i = 0; i + SPLITMIX64_GAMMA_CONST.length <= module.length; i++) {
			for (int j = 0; j < SPLITMIX64_GAMMA_CONST.length; j++) {
				if (module[i + j] != SPLITMIX64_GAMMA_CONST[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	@Test
	void aWasiBuildDrawsFromTheInModuleGeneratorAndKeepsTheEntropyApiOnTheHost() throws Exception {
		// CL's random is a pseudo-random draw from *random-state*, so the draw is a
		// SplitMix64 step INLINED at the call site on every build -- never a host call
		// per draw, which cost 177 ns against the ~4 ns this does (.kb/random.md). The
		// gamma constant is that generator's signature: a Preview 1 module that draws
		// carries it, and one whose only randomness is rontolisp::%random-byte must NOT,
		// because the entropy API promises the HOST's own bytes and may never be
		// answered from the generator. That second assertion is the security half: it
		// fails the moment the module-local generator can reach %random-byte.
		assertThat(carriesTheGenerator(
				new WasmLispCompiler(false, false, false).compile(LispReader.readAllFromString("(print (random 10))"))))
			.as("the draw inlines the generator instead of calling random_get")
			.isTrue();
		assertThat(carriesTheGenerator(new WasmLispCompiler(false, false, false)
			.compile(LispReader.readAllFromString("(print (rontolisp::%random-byte))"))))
			.as("the entropy API never reaches the module-local generator")
			.isFalse();

		// And two runs of the same module still differ: the generator is seeded ONCE
		// per instance from the host's random_get, so a draw stays unpredictable
		// without paying a host call for every one of them.
		String draws = "(dotimes (i 4) (princ (random 1000000000)) (terpri))";
		assertThat(compileAndRun(draws)).as("seeded per instance, not per module").isNotEqualTo(compileAndRun(draws));
	}

	@Test
	void noWasiModuleAnswersAnEmptyEnvironmentAndAnEmptyFilesystem() throws Exception {
		// A stub may answer when the answer is TRUE OF THIS MODULE. A reactor has no
		// environment and no files, so getenv misses and probe-file finds nothing --
		// which is what every caller of a lookup already handles, and what
		// uiop:default-temporary-directory (the other half of smart-buffer's one
		// load-time form) needs in order to fall back.
		// %probe-file and %host-getenv, not probe-file and uiop:getenv: both public
		// spellings are spliced definitions the CLI adds (the prelude, and uiop-os.lisp
		// over the environment override map), and this compiler is driven directly. The
		// primitives are what reach path_open and the environ scan, which are the stubs
		// under test.
		String program = """
				(defun envp () (if (%host-getenv "HOME") 1 0))
				(rontolisp:wasm-export 'envp :returns :int)
				(defun probep () (if (%probe-file "/etc/hosts") 1 0))
				(rontolisp:wasm-export 'probep :returns :int)
				""";
		assertThat(compileNoWasiAndInvoke(program, "envp")).isEqualTo("0");
		assertThat(compileNoWasiAndInvoke(program, "probep")).isEqualTo("0");
	}

	@Test
	void noWasiModuleSignalsForTheClockAndForRealEntropy() throws Exception {
		// The other side of the same rule, for the two services a module with no imports
		// cannot answer by itself: a clock reading it invented would not BE the time,
		// and a fixed-seed generator is not cryptographic entropy. Both signal a
		// catchable Lisp condition naming what is missing rather than trapping, so a
		// library that probes either one inside ignore-errors still loads. Each has a
		// host-supplied way out -- __ronto_set_time here, --host-random for the entropy
		// -- and this is the state BEFORE it is used: no host has set the clock, so
		// there is no time to report and the built-in refuses rather than naming 1970.
		String program = """
				(defun clockp () (if (ignore-errors (get-universal-time)) 1 0))
				(rontolisp:wasm-export 'clockp :returns :int)
				(defun entropyp () (if (ignore-errors (rontolisp:random-bytes 4)) 1 0))
				(rontolisp:wasm-export 'entropyp :returns :int)
				""";
		assertThat(compileNoWasiAndInvoke(program, "clockp")).isEqualTo("0");
		assertThat(compileNoWasiAndInvoke(program, "entropyp")).isEqualTo("0");
		// The clock hook is a plain export the host calls before anything else -- before
		// _initialize, so that even a library's LOAD-TIME timestamp reads it. Driving it
		// from wasmtime needs a second --invoke (one run calls one export), so this leg
		// pins that it exists and is callable; the behavioural check -- set it and the
		// three built-ins report that instant, frozen until the host moves it again --
		// is the Node one in .kb/wasm-export-no-wasi.md and examples/cloudflare-workers/.
		assertThat(compileNoWasiAndInvoke(program, "__ronto_set_time", "1786000000000000000")).isEmpty();
	}

	@Test
	void noWasiModuleRefusesToSleepInsteadOfSpinningForever() throws Exception {
		// Preview 1 elapses a sleep by SPINNING on the clock (its nine imports carry no
		// timer). A --no-wasi module has neither a timer nor a clock that can advance
		// while a call runs -- only a host write moves it, and no host can write it from
		// inside the call -- so the same spin would hang the instance rather than wait.
		// It signals instead, which is catchable and immediate.
		String program = """
				(defun nap (n) (if (ignore-errors (progn (sleep n) t)) 1 0))
				(rontolisp:wasm-export 'nap :params '(:int) :returns :int)
				""";
		assertThat(compileNoWasiAndInvoke(program, "nap", "2")).isEqualTo("0");
	}

	@Test
	void noWasiModuleStillTrapsOnInput() throws Exception {
		// The other side of the rule: a stub may discard output, but answering a READ
		// would fabricate input the program cannot tell from real, so it still traps.
		String program = """
				(defun ask (n) (+ n (length (read-line))))
				(rontolisp:wasm-export 'ask :params '(:int) :returns :int)
				""";
		byte[] wasmBytes = new WasmLispCompiler(false, false, true).compile(LispReader.readAllFromString(program));
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "--invoke", "ask", "-W", "gc", "-W",
				"exceptions=y", path("test.wasm"), "7");
		assertThat(result.getExitCode()).isNotZero();
		assertThat(result.getStderr()).contains("unreachable");
	}

	// #'mapcar AS A VALUE over more than one list: the list count is a runtime property
	// there, so the wrapper walks the list-of-lists itself (BuiltinFunctionWrappers.
	// mapFamilyWrapper) instead of dropping every list but the first. alexandria:mappend
	// is
	// (apply #'mapcar function lists), so this is the shape a real library takes.
	@Test
	void mapcarAsValueOverMultipleListsCompilesAndRuns() throws Exception {
		assertThat(compileAndRun("(print (apply #'mapcar #'list '((1 2) (3 4))))")).isEqualTo("((1 3) (2 4))");
		assertThat(compileAndRun("(print (apply #'mapcar #'+ '((1 2) (10 20) (100 200))))")).isEqualTo("(111 222)");
		assertThat(compileAndRun("(print (apply #'mapcar #'list '((1 2 3) (3 4))))")).isEqualTo("((1 3) (2 4))");
		assertThat(compileAndRun("(print (funcall #'mapcar #'1+ '(1 2 3)))")).isEqualTo("(2 3 4)");
	}

	// The map* family reached through funcall with the lists SPREAD OUT, in a program
	// that uses apply nowhere else. The wrapper body is (apply f ...), and usesEval --
	// which gates the _apply runtime here -- scans the SOURCE program, not the injected
	// wrappers: without the wrapper clause in that gate _apply stayed a nil-answering
	// stub and this answered (NIL NIL) while the interpreter and the JVM answered
	// ((1 3) (2 4)). Every assertion below must be the ONLY form in its program.
	@Test
	void applyUsingWrapperReachedByFuncallCompilesAndRuns() throws Exception {
		assertThat(compileAndRun("(print (funcall #'mapcar #'list '(1 2) '(3 4)))")).isEqualTo("((1 3) (2 4))");
		assertThat(compileAndRun("(print (funcall #'mapcan #'list '(1 2) '(3 4)))")).isEqualTo("(1 3 2 4)");
		assertThat(compileAndRun("(print (funcall #'every #'< '(1 2) '(3 4)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (funcall #'some #'> '(1 5) '(3 4)))")).isEqualTo("T");
	}

	// The four primitives alexandria forced: (last list n), every/some over N sequences,
	// coerce to a COMPUTED result type, and read-sequence into a character buffer. Each
	// failed identically on all four backends before, so they are conformance gaps rather
	// than divergences -- but they are pinned per backend all the same.
	@Test
	void lastWithACountCompilesAndRuns() throws Exception {
		assertThat(compileAndRun("(print (last '(1 2 3) 2))")).isEqualTo("(2 3)");
		assertThat(compileAndRun("(print (last '(1 2 3) 0))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (last '(1 2 3) 5))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (last '(1 2 . 3) 1))")).isEqualTo("(2 . 3)");
		assertThat(compileAndRun("(print (last '(1 2 3)))")).isEqualTo("(3)");
		assertThat(compileAndRun("(print (funcall #'last '(1 2 3) 2))")).isEqualTo("(2 3)");
		assertThat(compileAndRun("(print (funcall #'last '(1 2 3)))")).isEqualTo("(3)");
	}

	@Test
	void everySomeOverMultipleSequencesCompilesAndRuns() throws Exception {
		assertThat(compileAndRun("(print (every #'< '(1 2) '(3 4)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (every #'< '(1 2) '(3 0)))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (every #'< '(1 2 3) '(9 9)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (every #'char= \"abc\" \"abd\"))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (some #'> '(1 5) '(3 4)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (notany #'> '(1 2) '(3 4)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (notevery #'< '(1 2) '(3 0)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (funcall #'every #'< '(1 2) '(3 4)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (funcall #'some #'> '(1 5) '(3 4)))")).isEqualTo("T");
		assertThat(compileAndRun("(print (funcall #'every #'evenp '(2 4)))")).isEqualTo("T");
	}

	@Test
	void coerceWithAComputedResultTypeCompilesAndRuns() throws Exception {
		String cs = "(defun cs (type seq) (coerce seq type))\n";
		assertThat(compileAndRun(cs + "(print (cs 'list (vector 1 2)))")).isEqualTo("(1 2)");
		assertThat(compileAndRun(cs + "(print (cs 'vector '(1 2)))")).isEqualTo("#(1 2)");
		assertThat(compileAndRun(cs + "(print (cs 'string '(#\\a #\\b)))")).isEqualTo("\"ab\"");
		assertThat(compileAndRun(cs + "(print (cs '(vector t) '(1 2)))")).isEqualTo("#(1 2)");
		assertThat(compileAndRun(cs + "(print (cs t '(1 2)))")).isEqualTo("(1 2)");
		assertThat(compileAndRun(cs + "(print (cs 'double-float 3))")).isEqualTo("3.0");
	}

	@Test
	void readSequenceIntoACharacterBufferCompilesAndRuns() throws Exception {
		assertThat(compileAndRun("""
				(with-input-from-string (s "abcdef")
				  (let ((buf (make-array 4 :element-type 'character)))
				    (print (list (read-sequence buf s) buf))))
				""")).isEqualTo("(4 \"abcd\")");
		// The element type may be COMPUTED, which is how alexandria allocates the buffer.
		assertThat(compileAndRun("""
				(with-input-from-string (s "xyz")
				  (let ((buf (make-array 3 :element-type (stream-element-type s))))
				    (print (list (read-sequence buf s) buf))))
				""")).isEqualTo("(3 \"xyz\")");
	}

	// The rest of the family over N lists, in call position and as values. Originally a
	// multi-list mapc trapped here (dispatch arity mismatch) and
	// mapcan/maplist/mapcon silently dropped every list but the first.
	@Test
	void mapFamilyMultipleListsCompilesAndRuns() throws Exception {
		assertThat(compileAndRun("(print (mapcan #'list '(1 2) '(3 4)))")).isEqualTo("(1 3 2 4)");
		assertThat(compileAndRun("(print (maplist #'list '(1 2) '(3 4)))")).isEqualTo("(((1 2) (3 4)) ((2) (4)))");
		assertThat(compileAndRun("(print (mapcon #'list '(1 2) '(3 4)))")).isEqualTo("((1 2) (3 4) (2) (4))");
		// mapc/mapl run for effect and answer the FIRST list.
		assertThat(compileAndRun("(print (mapc (lambda (a b) (print (list a b))) '(1 2) '(3 4)))"))
			.isEqualTo("(1 3)\n(2 4)\n(1 2)");
		assertThat(compileAndRun("(print (mapl (lambda (a b) (print (list a b))) '(1 2) '(3 4)))"))
			.isEqualTo("((1 2) (3 4))\n((2) (4))\n(1 2)");
		// The walk stops at the shortest list, and three lists work like two.
		assertThat(compileAndRun("(print (mapcan #'list '(1 2 3) '(3 4)))")).isEqualTo("(1 3 2 4)");
		assertThat(compileAndRun("(print (mapcan #'list '(1 2) '(3 4) '(5 6)))")).isEqualTo("(1 3 5 2 4 6)");
		assertThat(compileAndRun("(print (mapc #'list nil '(1 2)))")).isEqualTo("NIL");
	}

	@Test
	void mapFamilyAsValuesOverMultipleListsCompilesAndRuns() throws Exception {
		assertThat(compileAndRun("(print (apply #'mapcan #'list '((1 2) (3 4))))")).isEqualTo("(1 3 2 4)");
		assertThat(compileAndRun("(print (apply #'maplist #'list '((1 2) (3 4))))"))
			.isEqualTo("(((1 2) (3 4)) ((2) (4)))");
		assertThat(compileAndRun("(print (apply #'mapcon #'list '((1 2) (3 4))))")).isEqualTo("((1 2) (3 4) (2) (4))");
		assertThat(compileAndRun("(print (apply #'mapc #'list '((1 2) (3 4))))")).isEqualTo("(1 2)");
		assertThat(compileAndRun("(print (apply #'mapl #'list '((1 2) (3 4))))")).isEqualTo("(1 2)");
		// One list still answers what the call-position form answers.
		assertThat(compileAndRun("(print (funcall #'maplist #'identity '(1 2 3)))")).isEqualTo("((1 2 3) (2 3) (3))");
		assertThat(compileAndRun("(print (funcall #'mapcan #'list '(1 2 3)))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun("(print (funcall #'mapc #'1+ '(1 2)))")).isEqualTo("(1 2)");
		assertThat(compileAndRun("(print (funcall #'mapl #'car '(1 2)))")).isEqualTo("(1 2)");
	}

	// A designator the compiler can READ is called directly by funcall / the map family /
	// reduce / sort instead of through the arity dispatcher (WasmDesignatorCall). Every
	// answer here has to be the one the dispatching route gives, including the shapes the
	// direct call has to build itself: a variadic callee reached at exactly its required
	// count (the empty rest list) and wider than it (the surplus linked into one).
	@Test
	void literalFunctionDesignatorsCompileAndRun() throws Exception {
		String defs = "(defun dbl (x) (* x 2)) (defun addall (&rest xs) (reduce #'+ xs :initial-value 0)) ";
		assertThat(compileAndRun(defs + "(print (mapcar #'dbl '(1 2 3)))")).isEqualTo("(2 4 6)");
		assertThat(compileAndRun(defs + "(print (mapcar 'dbl '(1 2 3)))")).isEqualTo("(2 4 6)");
		assertThat(compileAndRun(defs + "(print (mapcar #'addall '(1 2) '(10 20)))")).isEqualTo("(11 22)");
		assertThat(compileAndRun(defs + "(print (mapcar #'addall '(1 2)))")).isEqualTo("(1 2)");
		assertThat(compileAndRun(defs + "(print (mapc #'dbl '(1 2)))")).isEqualTo("(1 2)");
		assertThat(compileAndRun(defs + "(print (mapcan #'list '(1 2 3)))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun(defs + "(print (reduce #'+ '(1 2 3 4)))")).isEqualTo("10");
		assertThat(compileAndRun(defs + "(print (reduce #'addall '(1 2 3) :initial-value 10))")).isEqualTo("16");
		assertThat(compileAndRun(defs + "(print (sort (list 3 1 2) #'<))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun(defs + "(print (funcall #'dbl 21))")).isEqualTo("42");
		assertThat(compileAndRun(defs + "(print (funcall #'addall 1 2 3))")).isEqualTo("6");
		assertThat(compileAndRun(defs + "(print (funcall #'addall))")).isEqualTo("0");
		// The same designator through a variable keeps the dispatching route, and both
		// routes answer alike.
		assertThat(compileAndRun(defs + "(let ((f #'dbl)) (print (mapcar f '(1 2 3))))")).isEqualTo("(2 4 6)");
		assertThat(compileAndRun(defs + "(let ((f #'addall)) (print (funcall f 1 2 3)))")).isEqualTo("6");
	}

	// Every operator that CALLS a function argument accepts a SYMBOL designator at run
	// time, as CL says and the interpreter does. The compiler cannot read one out of
	// (car (list 'pred)), so the call dispatches, and only the _lookup name registry
	// resolves the symbol that arrives -- the gate that emits it used to read
	// funcall/apply spellings alone, so every operator here trapped on `unreachable`
	// while the interpreter and the JVM answered. One row per family: the map loops and
	// sort reach the dispatcher through WasmDesignatorCall, the sequence predicates
	// through the funcall their Pass 2 expansion builds, maphash through its own site.
	@Test
	void aComputedSymbolDesignatorResolvesForEveryOperatorThatCallsIt() throws Exception {
		String defs = "(defun pred (x) (evenp x)) (defun lt (a b) (< a b)) ";
		assertThat(compileAndRun(defs + "(print (mapcar (car (list 'pred)) '(2 3 4)))")).isEqualTo("(T NIL T)");
		assertThat(compileAndRun(defs + "(print (mapc (car (list 'pred)) '(2 3)))")).isEqualTo("(2 3)");
		assertThat(compileAndRun(defs + "(print (mapcan (car (list 'list)) '(1 2)))")).isEqualTo("(1 2)");
		assertThat(compileAndRun(defs + "(print (every (car (list 'pred)) '(2 3 4)))")).isEqualTo("NIL");
		assertThat(compileAndRun(defs + "(print (some (car (list 'pred)) '(1 3 4)))")).isEqualTo("T");
		assertThat(compileAndRun(defs + "(print (remove-if (car (list 'pred)) '(2 3 4)))")).isEqualTo("(3)");
		assertThat(compileAndRun(defs + "(print (count-if (car (list 'pred)) '(2 3 4)))")).isEqualTo("2");
		assertThat(compileAndRun(defs + "(print (find-if (car (list 'pred)) '(2 3 4)))")).isEqualTo("2");
		assertThat(compileAndRun(defs + "(print (position-if (car (list 'pred)) '(2 3 4)))")).isEqualTo("0");
		assertThat(compileAndRun(defs + "(print (member-if (car (list 'pred)) '(1 2 3)))")).isEqualTo("(2 3)");
		assertThat(compileAndRun(defs + "(print (reduce (car (list 'lt)) '(1 2)))")).isEqualTo("T");
		assertThat(compileAndRun(defs + "(print (sort (list 3 1 2) (car (list 'lt))))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun(defs + "(print (stable-sort (list 3 1 2) (car (list 'lt))))")).isEqualTo("(1 2 3)");
		assertThat(compileAndRun(defs + "(print (sort (list 3 1 2) #'< :key (car (list 'identity))))"))
			.isEqualTo("(1 2 3)");
		// A designator out of a function PARAMETER is the same fact one call deeper.
		assertThat(compileAndRun(defs + "(defun call-it (f l) (mapcar f l)) (print (call-it 'pred '(2 3)))"))
			.isEqualTo("(T NIL)");
		assertThat(compileAndRun(defs + """
				(let ((h (make-hash-table)))
				  (setf (gethash 1 h) 2)
				  (maphash (car (list 'lt)) h)
				  (print (hash-table-count h)))
				""")).isEqualTo("1");
	}

	// The component backend answers the same, the way every cross-backend fact here is
	// pinned on both WASM tiers.
	@Test
	void aComputedSymbolDesignatorResolvesOnTheComponentBackendToo() throws Exception {
		String defs = "(defun pred (x) (evenp x)) (defun lt (a b) (< a b)) ";
		assertThat(compileComponentAndRun(defs + "(print (mapcar (car (list 'pred)) '(2 3 4)))"))
			.isEqualTo("(T NIL T)");
		assertThat(compileComponentAndRun(defs + "(print (every (car (list 'pred)) '(2 3 4)))")).isEqualTo("NIL");
		assertThat(compileComponentAndRun(defs + "(print (sort (list 3 1 2) (car (list 'lt))))")).isEqualTo("(1 2 3)");
	}

	@Test
	void mapFamilyTrapsOnNonList() throws Exception {
		// The map* family operates on lists; a non-list (e.g. a string) traps rather than
		// silently returning nil, matching the interpreter. WASM error is an
		// unreachable trap (it carries no message).
		for (String form : List.of("(mapcar #'identity \"abc\")", "(mapc #'identity \"abc\")",
				"(mapcan #'list \"abc\")", "(maplist #'identity \"abc\")", "(mapcon #'list \"abc\")",
				"(mapl #'identity \"abc\")",
				// Every list position is guarded, not just the first.
				"(mapcar #'list '(1) \"ab\")", "(mapc #'list '(1) \"ab\")", "(maplist #'list '(1) \"ab\")")) {
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
	void uiopBindingMacrosAndWithDeprecationCompileAndRun() throws Exception {
		// with-deprecation wraps top-level defuns in the wild, so its expansion has to
		// SPLICE at top level -- burying them in an expression would stop Pass 1 from
		// collecting them at all.
		assertThat(compileAndRun("""
				(uiop:with-deprecation (:style-warning)
				  (defun dep-a (x) (* x 2))
				  (defun dep-b (x) (+ x 1)))
				(eval-when (:compile-toplevel :load-toplevel :execute)
				  (uiop:with-deprecation (:style-warning)
				    (defun dep-c (x) (- x 1))))
				(print (list (uiop:if-let ((a 1) (b 2)) (list a b) 0)
				             (uiop:if-let ((a 1) (b nil)) (list a b) 0)
				             (uiop:if-let (x (+ 1 2)) (* x 10) 0)
				             (uiop:if-let ((a nil)) 7)
				             (uiop:when-let ((a 3) (b 4)) (+ a b) (* a b))
				             (uiop:when-let ((a 3) (b nil)) (+ a 1))
				             (uiop:when-let* ((a 5) (b (* a 2))) (+ a b))
				             (uiop:when-let* ((a nil) (b (error "no"))) b)
				             (dep-a 3) (dep-b 3) (dep-c 3)))
				""")).isEqualTo("((1 2) 0 30 NIL 12 NIL 15 NIL 6 4 2)");
	}

	@Test
	void uiopUtilityHelpersCompileAndRun() throws Exception {
		// The four uiop/utility members with real codegen shape -- strcat (a &rest call
		// into the spliced reduce/strcat), string-prefix-p (string= with :end2), nest (a
		// pure syntactic rearrangement) and while-collecting (a let + flet whose
		// collector functions mutate the accumulators they close over) -- plus the
		// selection those bodies drag in -- which is why this goes through
		// LispPreludeLibrary.process (it calls UiopLibrary.process): the plain
		// compileAndRun helper compiles the program as written, and uiop functions only
		// exist once their definitions are spliced. The JVM twin is
		// JvmLispCompilerTest.compileAndRunUiopUtilityHelpers.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(print (uiop:strcat "a" nil #\\b "c"))
				(print (list (uiop:string-prefix-p "ab" "abc") (uiop:string-prefix-p "b" "abc")))
				(print (uiop:nest (list 1) (list 2) (list 3)))
				(print (multiple-value-list
				        (uiop:while-collecting (foo bar)
				          (dolist (x (list (list 'a 1) (list 'b 2)))
				            (foo (first x))
				            (bar (second x))))))
				(print (uiop:access-at (list :a (list 10 20)) (list :a 1)))
				(print (list (uiop:timestamp< 1 2) (uiop:latest-timestamp 3 1 2)))
				(print (let ((l (list 1))) (uiop:appendf l (list 2 3)) l))
				""")))).isEqualTo("""
				"abc"
				(T NIL)
				(1 (2 (3)))
				((A B) (1 2))
				20
				(T 3)
				(1 2 3)""");
	}

	@Test
	void uiopOsHostIdentityAndGetenvOverrideCompileAndRun() throws Exception {
		// The uiop/os family on Preview 1. The program AND the uiop resources are read
		// with the TARGET feature set, so featurep -- and architecture, derived from it
		// -- answer for THIS backend: :rontolisp-wasm is present, :thread-support is
		// not, and the architecture is wasm32 where the JVM twin says :jvm
		// (JvmLispCompilerTest.compileAndRunUiopOsHostIdentityAndGetenvOverride).
		// getcwd signals here: a WASI program has preopened directories and no current
		// one, which is the family's only per-backend divergence.
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(print (list (uiop:featurep :rontolisp) (uiop:featurep :rontolisp-wasm)
				             (uiop:featurep :rontolisp-jvm) (uiop:featurep '(:and :rontolisp :unicode))
				             (uiop:featurep '(:not :nope))))
				(print (list (uiop:os-unix-p) (uiop:os-macosx-p) (uiop:os-windows-p) (uiop:os-genera-p)))
				(print (list (uiop:detect-os) (uiop:operating-system) (uiop:implementation-type)
				             uiop:*implementation-type* (uiop:architecture) (uiop:hostname)))
				(print (uiop:os-cond ((uiop:os-windows-p) :win) ((uiop:os-unix-p) :unix) (t :other)))
				(setf (uiop:getenv "RLENV") "overridden")
				(print (list (uiop:getenv "RLENV") (uiop:getenvp "RLENV")))
				(setf (uiop:getenv "RLENV") nil)
				(print (list (uiop:getenv "RLENV") (uiop:getenvp "RLENV")))
				(print (handler-case (uiop:getcwd) (uiop:not-implemented-error () :no-working-directory)))
				(print (handler-case (uiop:chdir "/tmp") (uiop:not-implemented-error () :chdir-signals)))
				""", Features.WASM), Features.WASM);
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		// --env RLENV: the override map must WIN over the host value, and the unset
		// must hide it again.
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc", "-W", "exceptions=y", "--env",
				"RLENV=from-host", path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("""
				(T T NIL T T)
				(T NIL NIL NIL)
				(:OS-UNIX :UNIX :RONTOLISP :RONTOLISP :WASM32 NIL)
				:UNIX
				("overridden" "overridden")
				(NIL NIL)
				:NO-WORKING-DIRECTORY
				:CHDIR-SIGNALS""");
	}

	@Test
	void uiopImageHooksBacktracesAndTheImageItselfCompileAndRun() throws Exception {
		// The uiop/image family that does not exit, on BOTH wasm backends. Same answers
		// as the interpreter and the JVM: the hooks are real lists (only the act of
		// DUMPING is impossible), fatal-condition is a deftype over serious-condition a
		// runtime typep matches, the backtrace family prints the condition and no frames,
		// and the three image operations name what is missing.
		String source = """
				(uiop:register-image-dump-hook 'a)
				(uiop:register-image-dump-hook 'b)
				(uiop:register-image-dump-hook 'a)
				(defvar *ran* nil)
				(uiop:register-image-restore-hook (lambda () (push :restored *ran*)) nil)
				(uiop:call-image-restore-hook)
				(print (list uiop:*image-dump-hook* *ran* uiop:*image-dumped-p* uiop:*lisp-interaction*))
				(print (list (uiop:fatal-condition-p (make-condition 'error))
				             (uiop:fatal-condition-p (make-condition 'warning))
				             (uiop:fatal-condition-p 42)))
				(print (with-output-to-string (s)
				         (uiop:print-condition-backtrace (make-condition 'simple-error :format-control "boom")
				                                         :stream s)
				         (uiop:print-backtrace :stream s :condition "second")
				         (uiop:raw-print-backtrace :stream s)))
				(print (list (handler-case (uiop:dump-image "x.img")
				               (uiop:not-implemented-error () :dump-image))
				             (handler-case (uiop:restore-image) (uiop:not-implemented-error () :restore-image))
				             (handler-case (uiop:create-image "x" nil)
				               (uiop:not-implemented-error () :create-image))))
				""";
		String expected = """
				((B A) (:RESTORED) NIL NIL)
				(T NIL NIL)
				"boom
				second
				"
				(:DUMP-IMAGE :RESTORE-IMAGE :CREATE-IMAGE)""";
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary
			.process(LispReader.readAllFromString(source, Features.WASM), Features.WASM))).isEqualTo(expected);
		assertThat(compileAndRunComponent(source)).isEqualTo(expected);
	}

	@Test
	void uiopImageQuitEndsTheProcessWithItsCode() throws Exception {
		// quit is the HOST's exit on both wasm backends -- wasi_snapshot_preview1's
		// proc_exit here, wit-imported wasi:cli/exit@0.3.0's exit-with-code under
		// --component (exit.lisp, eval/ExitLibrary) -- so neither the handler-case around
		// it (it is not a condition) nor the unwind-protect cleanup runs: the process
		// ends where the call stands. Byte-identical to the interpreter and the JVM.
		String quitSource = """
				(print :before)
				(unwind-protect
				     (handler-case (uiop:quit 3) (error (e) (print :caught)))
				  (print :cleanup))
				(print :after)
				""";
		assertThat(compileAndRunQuittingProgram(quitSource, false)).isEqualTo(new String[] { ":BEFORE", "3" });
		assertThat(compileAndRunQuittingProgram(quitSource, true)).isEqualTo(new String[] { ":BEFORE", "3" });
		// die reports on standard error and quits with the code it was given;
		// shell-boolean-exit is 0 for true and 1 for false; and the code is masked to
		// eight bits, which is what a POSIX host does with it anyway and what
		// wasi:cli/exit's u8 accepts.
		for (boolean component : new boolean[] { false, true }) {
			assertThat(compileAndRunQuittingProgram("(uiop:die 7 \"no such thing: ~A\" :widget)", component)[1])
				.isEqualTo("7");
			assertThat(compileAndRunQuittingProgram("(uiop:shell-boolean-exit nil)", component)[1]).isEqualTo("1");
			assertThat(compileAndRunQuittingProgram("(uiop:quit 300)", component)[1]).isEqualTo("44");
			// handle-fatal-condition reports and dies with upstream's own status 99;
			// *lisp-interaction* is nil because no backend has a debugger to enter.
			assertThat(compileAndRunQuittingProgram("""
					(print :start)
					(uiop:with-fatal-condition-handler () (error "the sky is falling"))
					(print :unreachable)
					""", component)).isEqualTo(new String[] { ":START", "99" });
		}
	}

	// A program that QUITS: the standard helpers assert exit code 0, and the whole point
	// here is the code. Answers {stdout, exit code}. The pipeline mirrors the CLI's --
	// the prelude splice brings uiop:quit in, and ExitLibrary binds %host-exit to
	// proc_exit / wasi:cli/exit for the backend being compiled.
	private static String[] compileAndRunQuittingProgram(String lispCode, boolean component) throws Exception {
		Features features = component ? Features.WASM.with(List.of(Features.COMPONENT)) : Features.WASM;
		List<LispVal> program = am.ik.rontolisp.eval.ExitLibrary.process(
				am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode, features),
						features),
				component ? am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT
						: am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_GC,
				features);
		byte[] bytes = new WasmLispCompiler(false, component).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(bytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
				path("test.wasm"));
		return new String[] { result.getStdout().trim(), String.valueOf(result.getExitCode()) };
	}

	@Test
	void uiopOsOctetReadersCompileAndRun() throws Exception {
		// read-little-endian / read-null-terminated-string are portable stream work over
		// read-byte; flexi-streams' in-memory octet stream is the one binary input every
		// backend can build without a filesystem. The Gray splice is the CLI's, and is
		// what routes read-byte on a CLOS instance stream to the protocol.
		List<LispVal> program = am.ik.rontolisp.cli.LoadInliner.inline(LispReader.readAllFromString("""
				(asdf:load-system "flexi-streams")
				(let* ((v (make-array 7 :element-type '(unsigned-byte 8)
				                        :initial-contents '(1 2 0 0 104 105 0)))
				       (s (flex:make-in-memory-input-stream v)))
				  (print (uiop:read-little-endian s))
				  (print (uiop:read-null-terminated-string s)))
				"""), path -> {
			throw new java.io.FileNotFoundException(path);
		});
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(program)))).isEqualTo("513\n\"hi\"");
	}

	@Test
	void uiopPathnameAlgebraCompileAndRuns() throws Exception {
		// The uiop/pathname family: pure computation over the flat namestring
		// (uiop-pathname.lisp), spliced by the selection pass, plus the two macros
		// (with-pathname-defaults binds the *default-pathname-defaults* special,
		// with-enough-pathname lowers onto call-with-enough-pathname). The JVM twin is
		// JvmLispCompilerTest.compileAndRunUiopPathnameAlgebra.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(print (namestring (uiop:subpathname #P"/tmp/foo/" "bar/baz.txt")))
				(print (namestring (uiop:subpathp (pathname "/tmp/foo/bar.txt") (pathname "/tmp/"))))
				(print (namestring (uiop:enough-pathname #P"/tmp/a/b.txt" #P"/tmp/")))
				(print (namestring (uiop:parse-unix-namestring "a//b/./c.txt")))
				(print (list (if (uiop:absolute-pathname-p "/a/b") t nil)
				             (if (uiop:relative-pathname-p "a/b") t nil)
				             (if (uiop:directory-pathname-p "/a/b/") t nil)
				             (if (uiop:file-pathname-p "/a/b") t nil)
				             (uiop:hidden-pathname-p ".gitignore")
				             (uiop:pathname-equal "/a/b" #P"/a/b")))
				(print (namestring (uiop:pathname-parent-directory-pathname #P"/a/b/c.txt")))
				(print (multiple-value-list (uiop:split-name-type "foo.lisp")))
				(print (namestring (uiop:wilden #P"/tmp/foo")))
				(print (namestring (uiop:translate-pathname* #P"/src/a/b.lisp" #P"/src/**/*.*" #P"/out/**/*.*")))
				(print (namestring (uiop:ensure-pathname "a/b" :ensure-directory t)))
				(print (namestring (uiop:get-pathname-defaults)))
				(uiop:with-pathname-defaults (#P"/wpd/") (print (namestring *default-pathname-defaults*)))
				(let ((p #P"/tmp/a/b.txt"))
				  (uiop:with-enough-pathname (p :defaults #P"/tmp/") (print (namestring p))))
				""")))).isEqualTo("""
				"/tmp/foo/bar/baz.txt"
				"foo/bar.txt"
				"a/b.txt"
				"a/b/c.txt"
				(T T T T T T)
				"/a/"
				("foo" "lisp")
				"/tmp/**/*.*"
				"/out/a/b.lisp"
				"a/b/"
				""
				"/wpd/"
				"a/b.txt\"""");
	}

	@Test
	void uiopUnimplementedMacroDropsItsArgumentFormsCompilesAndRuns() throws Exception {
		// A macro nothing implements yet must not evaluate what it was handed. Its
		// synthesized stub is a real variadic defun (the name has to be fboundp), so
		// the ordinary call path FINDS it and compiles the argument forms first --
		// which is why the lowering has to happen in the expression compiler's uiop
		// branch, ahead of the call path. The spec list here is non-empty on purpose:
		// with (), the arguments are the nil literal and the bug is invisible. The JVM
		// twin is
		// JvmLispCompilerTest.compileAndRunUiopUnimplementedMacroDropsItsArgumentForms.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(print (handler-case (uiop:with-current-directory ("/tmp") (defun um-probe () 1))
				         (uiop:not-implemented-error () :signalled)))
				(print (fboundp 'um-probe))
				""")))).isEqualTo("""
				:SIGNALLED
				NIL""");
	}

	@Test
	void uiopWithUpgradabilitySplicesItsDefinitionsCompilesAndRuns() throws Exception {
		// Upstream wraps every one of its definitions in with-upgradability; rontolisp
		// lowers it to progn, and the top-level flattening has to splice that progn or
		// Pass 1 never collects the defuns.
		assertThat(compileAndRun("""
				(uiop:with-upgradability ()
				  (defun up-a (x) (* x 2))
				  (defvar *up-v* 5))
				(eval-when (:compile-toplevel :load-toplevel :execute)
				  (uiop/utility:with-upgradability ()
				    (defun up-b (x) (+ x 1))))
				(print (list (up-a 3) (up-b 3) *up-v*))
				""")).isEqualTo("(6 4 5)");
	}

	@Test
	void uiopMuffledConditionsAndStyleWarnCompileAndRun() throws Exception {
		// with-muffled-conditions expands into call-with-muffled-conditions, a spliced
		// defun the program never names -- the surface-form rule in UiopLibrary is what
		// selects it, and without that this compiled to a call-time "undefined function".
		// Its body is handler-bind + muffle-warning, so the whole module is in EH mode.
		// style-warn signals uiop's own simple-style-warning, which really is a
		// style-warning, so a handler for the CL
		// supertype catches it. The JVM twin is
		// JvmLispCompilerTest.compileAndRunUiopMuffledConditionsAndStyleWarn.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(print (uiop:with-muffled-conditions ('(warning)) (warn "quiet") :muffled))
				(print (handler-bind ((style-warning (lambda (c) (muffle-warning c))))
				         (uiop:style-warn "styled ~A" 2)
				         :sw-done))
				(print (handler-case (uiop:register-hook-function '*h* (lambda () 1))
				         (uiop:not-implemented-error (c) :nie)))
				""")))).isEqualTo("""
				:MUFFLED
				:SW-DONE
				:NIE""");
	}

	@Test
	void loopAnaphoricItOutsideClUserCompilesAndRuns() throws Exception {
		// Read outside cl-user the anaphor arrives package-qualified; missing it here
		// fails at COMPILE time, not at run time.
		assertThat(compileAndRun("""
				(defpackage :zzit (:use :cl))
				(in-package :zzit)
				(print (list (loop for x in '(nil nil 3 4) when x return it)
				             (loop for x in '(1 nil 3 nil 5) when x collect it)
				             (let ((acc nil)) (loop for x in '(1 nil 2) when x do (push it acc)) (nreverse acc))
				             (loop for x in '(1 nil 3) when x collect it else collect 0)
				             (loop for x in '(4 nil 6) when x when (* x 10) collect it)
				             (loop for x in '(1 nil) when x collect (loop for y in '(5 nil 6) when y collect it))
				             (loop for i from 1 collect i into xs do (when (>= i 3) (loop-finish))
				                   finally (return (length xs)))))
				""")).isEqualTo("(3 (1 3 5) (1 2) (1 0 3) (40 60) ((5 6)) 3)");
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

	@Test
	void octetsDecodeThroughTheStrictFastPathAndFallBackOnMalformedBytes() throws Exception {
		// The two WASM arms of the gate (AsyncEvalTest and
		// JvmAsyncCompilerTest are the other two): _iv_utf8_str validates the packed
		// octet vector as UTF-8 and, when it is, builds the string with ONE array.copy
		// -- and the compiled per-byte loop takes only what it refuses, answering
		// exactly what the loop alone answered. The component leg runs the same core
		// module through a different I/O adapter, so both are checked.
		String source = """
				(defun octs (bs)
				  (let ((a (make-array (length bs) :element-type '(unsigned-byte 8))) (i 0))
				    (dolist (b bs) (setf (aref a i) b) (setq i (+ i 1)))
				    a))
				(print (list (rontolisp::%octets-to-string (octs '(72 105)))
				             (map 'list #'char-code
				                  (rontolisp::%octets-to-string (octs '(#xE3 #x81 #x82 #xF0 #x9F #x98 #x80))))
				             (map 'list #'char-code (rontolisp::%octets-to-string (octs '(#xFF #x41))))
				             (map 'list #'char-code (rontolisp::%octets-to-string (octs '(#xF4 #x90 #x80 #x80))))
				             (rontolisp::%octets-to-string-strict (octs '(72 105)))
				             (rontolisp::%octets-to-string-strict (octs '(#xFF)))))
				""";
		String expected = "(\"Hi\" (12354 128512) (255 65) (244 144 128 128) \"Hi\" NIL)";
		assertThat(compileAndRunProgram(
				am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(source))))
			.isEqualTo(expected);
		assertThat(compileAndRunComponent(source)).isEqualTo(expected);
	}

	private static String compileAndRunComponent(String lispCode) throws Exception {
		// The wait-for splice mirrors the CLI order (a no-op for a program that
		// references neither rontolisp:wait-for nor sleep -- under --component `sleep`
		// IS that splice's defun, over the real wasi:clocks timer).
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary
			.process(am.ik.rontolisp.eval.WaitForLibrary.process(LispReader.readAllFromString(lispCode),
					am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT));
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

	// uiop:getenv under --component is the spliced environment.lisp binding over
	// wit-imported wasi:cli/environment (eval/EnvironmentLibrary), so this helper runs
	// that splice the way the CLI pipeline does.
	private static String compileAndRunComponentWithEnv(String lispCode, String env) throws Exception {
		// The CLI's order: the prelude splice (which drives the uiop one, and so defines
		// uiop:getenv over the %host-getenv primitive) and then EnvironmentLibrary,
		// whose trigger is that primitive.
		List<LispVal> program = am.ik.rontolisp.eval.EnvironmentLibrary.process(
				am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode), Features.WASM),
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT);
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("test.component.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--env",
				env, path("test.component.wasm"));
		assertThat(result.getExitCode()).as("exit code for component: %s\nstderr: %s", lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	// The command-line vector with real arguments, on either wasm backend. Preview 1
	// scans the buffer its two APPENDED args_sizes_get / args_get imports fill (_argv);
	// --component takes the spliced environment.lisp defun over wit-imported
	// wasi:cli/environment's get-arguments, so that splice runs here the way the CLI
	// pipeline runs it.
	private static String compileAndRunWithArguments(String lispCode, boolean component, String... arguments)
			throws Exception {
		Features features = component ? Features.WASM.with(List.of(Features.COMPONENT)) : Features.WASM;
		List<LispVal> program = am.ik.rontolisp.eval.EnvironmentLibrary.process(
				am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode, features),
						features),
				component ? am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT
						: am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_GC);
		byte[] bytes = new WasmLispCompiler(false, component).compile(program);
		String module = path(component ? "args.component.wasm" : "args.wasm");
		wasmtime.copyFileToContainer(Transferable.of(bytes), module);
		List<String> command = new java.util.ArrayList<>(
				List.of("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", module));
		command.addAll(List.of(arguments));
		ExecResult result = wasmtime.execInContainer(command.toArray(new String[0]));
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	@Test
	void uiopImageTheCommandLineCompilesAndRuns() throws Exception {
		// The vector is WASI's own args, which already begin with the program name --
		// wasmtime puts the module path there -- so it is the same (program-name
		// user-arg ...) shape the interpreter and the JVM answer, and
		// command-line-arguments is its rest on all four.
		String source = """
				(print (uiop:argv0))
				(print (uiop:command-line-arguments))
				(print uiop:*command-line-arguments*)
				(print (length (uiop:raw-command-line-arguments)))
				""";
		for (boolean component : new boolean[] { false, true }) {
			String output = compileAndRunWithArguments(source, component, "alpha", "beta");
			assertThat(output).as("component: %s", component).endsWith("""
					("alpha" "beta")
					("alpha" "beta")
					3""");
			assertThat(output.lines().findFirst().orElseThrow()).as("argv0 for component: %s", component)
				.contains("args")
				.endsWith(".wasm\"");
			assertThat(compileAndRunWithArguments("(print (uiop:command-line-arguments))", component))
				.as("no arguments, component: %s", component)
				.isEqualTo("NIL");
		}
	}

	@Test
	void componentGetenvFromWasiEnvironment() throws Exception {
		// Component mode reads environment variables through wasi:cli/environment.
		assertThat(compileAndRunComponentWithEnv("(print (uiop:getenv \"RLENV\"))", "RLENV=hello"))
			.isEqualTo("\"hello\"");
		assertThat(compileAndRunComponentWithEnv("(print (stringp (uiop:getenv \"RLENV\")))", "RLENV=hello"))
			.isEqualTo("T");
		assertThat(compileAndRunComponentWithEnv("(print (uiop:getenv \"RL_UNSET\"))", "RLENV=hello")).isEqualTo("NIL");
	}

	@Test
	void preview1GetenvDoesNotCorruptNewline() throws Exception {
		// uiop:getenv calls environ_sizes_get with scratch addresses (ENV_COUNT_ADDR=136
		// ..)
		// that must NOT overlap the interned-string data segment. When they did
		// (DATA_BASE_OFFSET=128), the host's count write at 136..139 clobbered the
		// shared newline byte at offset 137, so every newline after a getenv printed as
		// a NUL (0x00) instead of 0x0a. Assert the raw bytes keep real newlines.
		assertThat(compileAndRunRawWithEnv("(uiop:getenv \"RLENV\") (format t \"X~%Y~%\")", "RLENV=hello"))
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
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode));
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("test.component.wasm"));
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"cd " + workDir() + " && wasmtime run -W gc=y -W exceptions=y --dir . test.component.wasm");
		assertThat(result.getExitCode()).as("exit code for component: %s\nstderr: %s", lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	@Test
	void anOptimizedComponentThatWarnsStillReachesStderr() throws Exception {
		// --optimize narrows fd_write to its STDOUT-ONLY half whenever the source cannot
		// materialize the reserved *error-output* handle 2, which drops the whole
		// wasi:cli/stderr surface. The narrowing is a claim about the source, so the two
		// spellings that CAN reach fd 2 have to keep working -- and land on the right
		// descriptor, not merged into stdout.
		for (String program : List.of("(warn \"careful\") (print :done)",
				"(format *error-output* \"careful~%\") (print :done)")) {
			byte[] componentBytes = new WasmLispCompiler(false, true, false, OptimizeLevel.DEFAULT)
				.compile(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(program)));
			wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("warn.optcomp.wasm"));
			ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", path("warn.optcomp.wasm"));
			assertThat(result.getExitCode()).as("%s\nstderr: %s", program, result.getStderr()).isZero();
			assertThat(result.getStdout().trim()).as(program).isEqualTo(":DONE");
			assertThat(result.getStderr()).as(program).contains("careful");
		}
	}

	@Test
	void anOptimizedComponentStillReportsAnUncaughtCondition() throws Exception {
		// The stderr producer the source scan cannot see: the entry function's EH-mode
		// landing pad writes the uncaught condition's report to fd 2 through a %warn
		// call the compiler SYNTHESIZES, while this program's text names none of the
		// three stderr spellings. Its emission fact now feeds the narrowing directly
		// (WasmUncaughtReportCompiler.emittedFor); before that --optimize dropped
		// wasi:cli/stderr here and the whole diagnosis vanished into the trap. The
		// import-level pin is WasmLispCompilerTest, this is the run.
		String program = """
				(print (handler-case (error "caught: ~a" 2) (error (e) (princ-to-string e))))
				(error "boom: ~a" 42)
				""";
		List<LispVal> parsed = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(program));
		for (OptimizeLevel level : List.of(OptimizeLevel.DEFAULT, OptimizeLevel.SIZE)) {
			byte[] componentBytes = new WasmLispCompiler(false, true, false, level).compile(parsed);
			wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("uncaught.optcomp.wasm"));
			ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
					path("uncaught.optcomp.wasm"));
			// An uncaught condition still exits as a trap -- the report precedes it.
			assertThat(result.getExitCode()).as("%s", level).isNotZero();
			assertThat(result.getStdout().trim()).as("%s", level).isEqualTo("\"caught: 2\"");
			assertThat(result.getStderr()).as("%s", level).contains("Unhandled condition: boom: 42");
		}
	}

	@Test
	void componentCoreIsTreeShakenUnderOptimize() throws Exception {
		// --optimize shakes the core module on the --component path too: every core
		// <-> component linkage is by NAME (alias core func "name"; core:instantiate
		// args keyed by import module name), so renumbering the surviving functions is
		// invisible to the wrapper (.kb/optimize-dead-code-elimination.md). The lifted
		// exports and the canonical-ABI helpers are core exports, hence shaker roots --
		// a string-returning export proves cabi_realloc / cabi_post_* survived.
		String program = """
				(defun add (a b) (+ a b))
				(rontolisp:wasm-export 'add :params '(:int :int) :returns :int)
				(defun greet (s) (concatenate 'string "Hello, " s))
				(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
				(defun never-called (x) (* x x x))
				""";
		List<LispVal> parsed = LispReader.readAllFromString(program);
		byte[] plain = new WasmLispCompiler(false, true, false, OptimizeLevel.NONE).compile(parsed);
		byte[] optimized = new WasmLispCompiler(false, true, false, OptimizeLevel.DEFAULT).compile(parsed);
		assertThat(optimized.length).as("--optimize should shrink the component").isLessThan(plain.length);
		wasmtime.copyFileToContainer(Transferable.of(optimized), path("test.optcomp.wasm"));
		ExecResult add = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "--invoke", "add(3, 4)",
				path("test.optcomp.wasm"));
		assertThat(add.getExitCode()).as("stderr: %s", add.getStderr()).isZero();
		assertThat(add.getStdout().trim()).isEqualTo("7");
		ExecResult greet = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "--invoke", "greet(\"bob\")",
				path("test.optcomp.wasm"));
		assertThat(greet.getExitCode()).as("stderr: %s", greet.getStderr()).isZero();
		assertThat(greet.getStdout().trim()).isEqualTo("\"Hello, bob\"");
	}

	@Test
	void keywordInternStaysInternedInAGateShakenModule() throws Exception {
		// (intern NAME :keyword) does not turn the funcall-dispatch gate off
		// (RuntimeNameProducers exemption 2), so this module shakes hard -- and the
		// shaken module must still route the runtime keyword through _intern so its
		// offset matches the literal (canonical-offset discipline: eq and getf are
		// offset comparisons).
		String program = """
				(print (eq (intern (string-upcase "post") :keyword) :post))
				(print (keywordp (intern (string-upcase "get") :keyword)))
				(print (getf (list :post 1) (intern (string-upcase "post") :keyword)))
				""";
		List<LispVal> parsed = LispReader.readAllFromString(program);
		byte[] plain = new WasmLispCompiler(false, false, false, OptimizeLevel.NONE).compile(parsed);
		byte[] optimized = new WasmLispCompiler(false, false, false, OptimizeLevel.DEFAULT).compile(parsed);
		assertThat(optimized.length).as("the gate should apply, not bail").isLessThan(plain.length / 2);
		assertThat(runModule(optimized, "kwgate.wasm")).isEqualTo("T\nT\n1");
	}

	@Test
	void optimizedServeComponentStillServesUnderWasmtimeServe() throws Exception {
		// The serve shape is the one the shaker could most easily break: `handle` and
		// `async_cb` are reached ONLY from the component's canon lift (and its callback
		// option), never from a `call` inside the core. Both are core exports, so both
		// are roots -- pinned by actually serving a request from the shaken component.
		String program = """
				(defun handle (env)
				  (list 200 nil
				        (list (symbol-name (getf env :request-method)) " " (getf env :path-info))))
				(rontolisp:http-handler 'handle)
				""";
		byte[] plain = compileServeComponent(program, null);
		byte[] optimized = compileServeComponent(program, null, OptimizeLevel.DEFAULT);
		assertThat(optimized.length).as("--optimize should shrink the serve component").isLessThan(plain.length);
		wasmtime.copyFileToContainer(Transferable.of(optimized), "/tmp/serve-opt.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8093 /tmp/serve-opt.wasm"
						+ " >/tmp/serve-opt.log 2>&1 & pid=$!; trap 'kill $pid 2>/dev/null' EXIT;"
						+ " sleep 0.3; kill -0 $pid 2>/dev/null || { echo 'wasmtime serve exited immediately; log:' 1>&2;"
						+ " cat /tmp/serve-opt.log 1>&2; exit 1; };"
						+ " for i in $(seq 1 60); do out=$(curl -s http://127.0.0.1:8093/hello) && [ -n \"$out\" ]"
						+ " && { echo \"$out\"; exit 0; }; sleep 0.25; done; cat /tmp/serve-opt.log; exit 1");
		assertThat(result.getExitCode()).as("optimized wasmtime serve round trip; log: %s", result.getStderr())
			.isZero();
		assertThat(result.getStdout().trim()).isEqualTo("GET /hello");
	}

	@Test
	void httpHandlerServesUnderWasmtimeServe() throws Exception {
		// rontolisp:http-handler compiles (via the CLI's HttpHandlerInliner + serve mode)
		// to a wasi:http/incoming-handler component; run it under `wasmtime serve` and
		// hit
		// it with curl (installed in the image), asserting the handler echoes the
		// request.
		//
		// An explicit --addr matters here: `wasmtime serve`'s default is 0.0.0.0:8080,
		// and this suite runs wasmtime as a HOST process (HostWasmtime), so 8080 would
		// be the developer's own -- on a machine already serving that port, curl would
		// reach whatever else is listening there instead of this test's server.
		byte[] componentBytes = compileServeComponent("""
				(defun handle (env)
				  (list 200 nil
				        (list (symbol-name (getf env :request-method)) " " (getf env :path-info))))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8088 /tmp/serve.wasm"
						+ " >/tmp/serve.log 2>&1 & pid=$!; trap 'kill $pid 2>/dev/null' EXIT;"
						+ " sleep 0.3; kill -0 $pid 2>/dev/null || { echo 'wasmtime serve exited immediately; log:' 1>&2;"
						+ " cat /tmp/serve.log 1>&2; exit 1; };"
						+ " for i in $(seq 1 60); do out=$(curl -s http://127.0.0.1:8088/hello) && [ -n \"$out\" ]"
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
				(defun handle (env)
				  (let ((s "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))
				    (dotimes (i 7)
				      (setq s (concatenate 'string s s)))
				    (list 200 nil (list s))))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve-big.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8081 /tmp/serve-big.wasm >/tmp/serve-big.log 2>&1 &"
						+ " pid=$!; trap 'kill $pid 2>/dev/null' EXIT;"
						+ " sleep 0.3; kill -0 $pid 2>/dev/null || { echo 'wasmtime serve exited immediately; log:' 1>&2;"
						+ " cat /tmp/serve-big.log 1>&2; exit 1; };"
						+ " for i in $(seq 1 60); do code=$(curl -s -m 20 -o /tmp/big.out -w '%{http_code}'"
						+ " http://127.0.0.1:8081/big) && [ \"$code\" != 000 ]"
						+ " && { echo \"$code $(wc -c < /tmp/big.out)\"; exit 0; }; sleep 0.25; done;"
						+ " cat /tmp/serve-big.log; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve large response; log: %s", result.getStderr()).isZero();
		// wc -c right-pads its count on some coreutils builds, so the two fields are
		// compared with the run of spaces between them collapsed.
		assertThat(result.getStdout().trim().replaceAll("\\s+", " ")).isEqualTo("200 8192");
	}

	@Test
	void httpHandlerServesAnOctetBodyByteExactlyUnderWasmtimeServe() throws Exception {
		// An (unsigned-byte 8) response body crosses the wasi:http body stream as the
		// RAW octets it holds: %http-serve-request hands them to the transport
		// unflattened, and stream<u8>.write stages a packed byte vector without the
		// UTF-8 encode a string parameter gets. Asserting the wire bytes is the point --
		// as text, the two octets >= #x80 would each come back doubled.
		byte[] componentBytes = compileServeComponent("""
				(defun handle (env)
				  (let ((v (make-array 3 :element-type '(unsigned-byte 8))))
				    (setf (aref v 0) 255)
				    (setf (aref v 1) 254)
				    (setf (aref v 2) 65)
				    (list 200 (list :content-type "application/octet-stream") v)))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve-octets.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8094 /tmp/serve-octets.wasm"
						+ " >/tmp/serve-octets.log 2>&1 & pid=$!; trap 'kill $pid 2>/dev/null' EXIT;"
						+ " sleep 0.3; kill -0 $pid 2>/dev/null || { echo 'wasmtime serve exited immediately; log:' 1>&2;"
						+ " cat /tmp/serve-octets.log 1>&2; exit 1; };"
						+ " for i in $(seq 1 60); do code=$(curl -s -m 20 -o /tmp/octets.out -w '%{http_code}'"
						+ " http://127.0.0.1:8094/) && [ \"$code\" != 000 ]"
						+ " && { od -An -tx1 /tmp/octets.out | tr -d ' \\n'; echo; exit 0; }; sleep 0.25; done;"
						+ " cat /tmp/serve-octets.log; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve octet body; log: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("fffe41");
	}

	@Test
	void httpHandlerRandomClockAndPrintUnderWasmtimeServe() throws Exception {
		// Inside a served handler random / time / print must work: the serve component
		// bridges the preview1 random_get / clock_time_get / fd_write imports to the
		// wasi:random / wasi:clocks / wasi:cli interfaces of the wasi:http proxy world
		// (adapter-http-server-p1.wat) instead of trapping.
		byte[] componentBytes = compileServeComponent("""
				(defun handle (env)
				  (print "handling")
				  (let ((r (random 10)))
				    (list 200 nil
				          (list (if (and (integerp r) (>= r 0) (< r 10)) "r-in" "r-out")
				                " "
				                (if (numberp (get-universal-time)) "t-num" "t-bad")))))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve-rand.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8082 /tmp/serve-rand.wasm >/tmp/serve-rand.log 2>&1 &"
						+ " pid=$!; trap 'kill $pid 2>/dev/null' EXIT;"
						+ " sleep 0.3; kill -0 $pid 2>/dev/null || { echo 'wasmtime serve exited immediately; log:' 1>&2;"
						+ " cat /tmp/serve-rand.log 1>&2; exit 1; };"
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
				(defun handle (env)
				  (list 200 nil (list "backend " (getf env :path-info))))
				(rontolisp:http-handler 'handle)
				""", null);
		byte[] proxyBytes = compileServeComponent("""
				(rontolisp:async-defun handle (env)
				  (let* ((resp (rontolisp:await (rontolisp:fetch "http://127.0.0.1:8083/up")))
				         (body (rontolisp:await (rontolisp:read-all (getf resp :body)))))
				    (list 200 nil
				          (list "proxied " body " " (princ-to-string (getf resp :status))))))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(backendBytes), "/tmp/serve-backend.wasm");
		wasmtime.copyFileToContainer(Transferable.of(proxyBytes), "/tmp/serve-proxy.wasm");
		// Wait for the BACKEND before querying the proxy: if the proxy answers first,
		// its fetch fails and it serves the non-empty body "proxied nil nil", which
		// would end the poll loop with the wrong output (a startup race, not a bug).
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8083 /tmp/serve-backend.wasm >/tmp/serve-backend.log 2>&1 &"
						+ " pid1=$!;"
						+ " wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8084 /tmp/serve-proxy.wasm >/tmp/serve-proxy.log 2>&1 &"
						+ " pid2=$!; trap 'kill $pid1 $pid2 2>/dev/null' EXIT;" + " sleep 0.3;"
						+ " kill -0 $pid1 2>/dev/null || { echo 'backend wasmtime serve exited immediately; log:' 1>&2;"
						+ " cat /tmp/serve-backend.log 1>&2; exit 1; };"
						+ " kill -0 $pid2 2>/dev/null || { echo 'proxy wasmtime serve exited immediately; log:' 1>&2;"
						+ " cat /tmp/serve-proxy.log 1>&2; exit 1; };"
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

	@Test
	void httpHandlerRelaysAFetchedBodyByteExactlyUnderWasmtimeServe() throws Exception {
		// The component leg of the gate (HttpHandlerTest#directiveRelays... is
		// the interpreter's, HttpHandlerJvmTest the JVM's, WasmHostGlueE2eTest the
		// reactor's): a fetched reply's :body STREAM answered as the response body --
		// the proxy shape, nothing reads it -- reaches the wire as the upstream's exact
		// octets. The stream<u8> read lifts each chunk as a packed octet vector,
		// %http-drain joins them without decoding, and stream<u8>.write stages the
		// vector raw. read-all on the same kind of reply still answers the decoded text.
		byte[] backendBytes = compileServeComponent("""
				(defun handle (env)
				  (if (string= (getf env :path-info) "/text")
				      (list 200 (list :content-type "text/plain") (list "こんにちは"))
				      (let ((v (make-array 9 :element-type '(unsigned-byte 8))))
				        (setf (aref v 0) 255) (setf (aref v 1) 216) (setf (aref v 2) 255)
				        (setf (aref v 3) 0) (setf (aref v 4) 65) (setf (aref v 5) 254)
				        (setf (aref v 6) 128) (setf (aref v 7) 195) (setf (aref v 8) 191)
				        (list 200 (list :content-type "image/jpeg") v))))
				(rontolisp:http-handler 'handle)
				""", null);
		byte[] proxyBytes = compileServeComponent("""
				(rontolisp:async-defun handle (env)
				  (if (string= (getf env :path-info) "/text")
				      (let ((res (rontolisp:await (rontolisp:fetch "http://127.0.0.1:8095/text"))))
				        (list 200 (list :content-type "text/plain")
				              (list (rontolisp:await (rontolisp:read-all (getf res :body))))))
				      (let ((res (rontolisp:await (rontolisp:fetch "http://127.0.0.1:8095/jpeg"))))
				        (list (getf res :status)
				              (list :content-type
				                    (cdr (assoc "content-type" (getf res :headers) :test #'string-equal)))
				              (getf res :body)))))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(backendBytes), "/tmp/relay-backend.wasm");
		wasmtime.copyFileToContainer(Transferable.of(proxyBytes), "/tmp/relay-proxy.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8095 /tmp/relay-backend.wasm >/tmp/relay-backend.log 2>&1 &"
						+ " pid1=$!;"
						+ " wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8096 /tmp/relay-proxy.wasm >/tmp/relay-proxy.log 2>&1 &"
						+ " pid2=$!; trap 'kill $pid1 $pid2 2>/dev/null' EXIT;" + " sleep 0.3;"
						+ " kill -0 $pid1 2>/dev/null || { echo 'backend wasmtime serve exited immediately; log:' 1>&2;"
						+ " cat /tmp/relay-backend.log 1>&2; exit 1; };"
						+ " kill -0 $pid2 2>/dev/null || { echo 'proxy wasmtime serve exited immediately; log:' 1>&2;"
						+ " cat /tmp/relay-proxy.log 1>&2; exit 1; };"
						+ " for i in $(seq 1 60); do curl -sf http://127.0.0.1:8095/text >/dev/null && break; sleep 0.25; done;"
						+ " curl -sf http://127.0.0.1:8095/text >/dev/null"
						+ " || { echo 'backend never came up' 1>&2; cat /tmp/relay-backend.log 1>&2; exit 1; };"
						+ " for i in $(seq 1 60); do code=$(curl -s -m 20 -o /tmp/relay.out -D /tmp/relay.hdr -w '%{http_code}'"
						+ " http://127.0.0.1:8096/relay) && [ \"$code\" != 000 ]"
						+ " && { echo \"$code $(grep -i '^content-type:' /tmp/relay.hdr | tr -d '\\r' | cut -d' ' -f2)"
						+ " $(od -An -tx1 /tmp/relay.out | tr -d ' \\n')\"; curl -s http://127.0.0.1:8096/text; echo; exit 0; };"
						+ " sleep 0.25; done; cat /tmp/relay-backend.log /tmp/relay-proxy.log 1>&2; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve relayed body; log: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim().lines().toList()).containsExactly("200 image/jpeg ffd8ff0041fe80c3bf",
				"こんにちは");
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
				(defun handle (env)
				  (let* ((page (getf env :path-info))
				         (bucket (kv:open ""))
				         (seen (kv:bucket-get bucket page)))
				    (kv:bucket-set bucket page
				                   (princ-to-string (+ 1 (if seen (parse-integer seen) 0))))
				    (list 200 nil
				          (list page " " (kv:bucket-get bucket page)))))
				(rontolisp:http-handler 'handle)
				""", dir.toString());
		wasmtime.copyFileToContainer(Transferable.of(component), "/tmp/serve-kv.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y -S keyvalue=y -S keyvalue-in-memory-data=/hits=41"
						+ " --addr 127.0.0.1:8085 /tmp/serve-kv.wasm >/tmp/serve-kv.log 2>&1 &"
						+ " pid=$!; trap 'kill $pid 2>/dev/null' EXIT;"
						+ " sleep 0.3; kill -0 $pid 2>/dev/null || { echo 'wasmtime serve exited immediately; log:' 1>&2;"
						+ " cat /tmp/serve-kv.log 1>&2; exit 1; };"
						+ " for i in $(seq 1 60); do out=$(curl -s http://127.0.0.1:8085/hits) && [ -n \"$out\" ]"
						+ " && { echo \"$out\"; exit 0; }; sleep 0.25; done; cat /tmp/serve-kv.log; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve keyvalue round trip; log: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("/hits 42");
	}

	@Test
	void httpHandlerReadsTheEnvironmentUnderWasmtimeServe() throws Exception {
		// A served handler reads environment variables like every other backend: the
		// serve leg of componentGetenvFromWasiEnvironment. uiop:getenv under --component
		// is environment.lisp over wit-imported wasi:cli/environment@0.3.0, which the
		// serve variant carries as an appended user import (its import block has no
		// environment interface -- the wasi:http service world does not carry one, and
		// the preview1 bridge's environ_* stubs answer with a zero environment). Before
		// that binding existed every variable read back nil here while the interpreter,
		// the JVM, Preview 1 and the run-mode component all answered.
		byte[] componentBytes = compileServeComponent("""
				(defun handle (env)
				  (list 200 nil
				        (list (or (uiop:getenv "RLENV") "unset")
				              " "
				              (if (uiop:getenv "RL_UNSET") "leaked" "nil"))))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve-env.wasm");
		ExecResult result = wasmtime
			.execInContainer("bash", "-c", "wasmtime serve -W gc=y -W exceptions=y --env RLENV=hello"
					+ " --addr 127.0.0.1:8092 /tmp/serve-env.wasm >/tmp/serve-env.log 2>&1 &"
					+ " pid=$!; trap 'kill $pid 2>/dev/null' EXIT;"
					+ " sleep 0.3; kill -0 $pid 2>/dev/null || { echo 'wasmtime serve exited immediately; log:' 1>&2;"
					+ " cat /tmp/serve-env.log 1>&2; exit 1; };"
					+ " for i in $(seq 1 60); do out=$(curl -sf http://127.0.0.1:8092/) && [ -n \"$out\" ]"
					+ " && { echo \"$out\"; exit 0; }; sleep 0.25; done; cat /tmp/serve-env.log 1>&2; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve getenv; log: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("hello nil");
	}

	@Test
	void httpHandlerReadsATopLevelGlobalUnderWasmtimeServe() throws Exception {
		// A serve component never lifts `run`, so the handle wrapper itself must run
		// the program's top level once before the first request: without that init a
		// defvar global reads back null inside the handler and the first arithmetic
		// on it traps with "cast failure".
		byte[] componentBytes = compileServeComponent("""
				(defvar *base* 41)
				(defun handle (env)
				  (list 200 nil (list (princ-to-string (+ *base* 1)))))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve-global.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8090 /tmp/serve-global.wasm >/tmp/serve-global.log 2>&1 &"
						+ " pid=$!; trap 'kill $pid 2>/dev/null' EXIT;"
						+ " sleep 0.3; kill -0 $pid 2>/dev/null || { echo 'wasmtime serve exited immediately; log:' 1>&2;"
						+ " cat /tmp/serve-global.log 1>&2; exit 1; };"
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
				(defun handle (env)
				  (let ((sock (rontolisp:tcp-connect "127.0.0.1" 8091)))
				    (if sock
				        (progn
				          (close sock)
				          (list 200 nil (list "connected")))
				        (list 200 nil (list "no-listener")))))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve-tcp.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y"
						+ " --addr 127.0.0.1:8091 /tmp/serve-tcp.wasm >/tmp/serve-tcp.log 2>&1 &"
						+ " pid=$!; trap 'kill $pid 2>/dev/null' EXIT;"
						+ " sleep 0.3; kill -0 $pid 2>/dev/null || { echo 'wasmtime serve exited immediately; log:' 1>&2;"
						+ " cat /tmp/serve-tcp.log 1>&2; exit 1; };"
						+ " for i in $(seq 1 60); do out=$(curl -sf http://127.0.0.1:8091/) && [ -n \"$out\" ]"
						+ " && { echo \"$out\"; exit 0; }; sleep 0.25; done; cat /tmp/serve-tcp.log 1>&2; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve tcp-connect; log: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("connected");
	}

	@Test
	void wasmtimeServeProcessDoesNotOutliveTheTest() throws Exception {
		// Every case above backgrounds `wasmtime serve` with `&` inside a `bash -c`
		// script and lets the script exit as soon as curl gets an answer. A
		// non-interactive bash does not SIGHUP a background job when the script's own
		// process exits, so without an explicit kill the server is orphaned and keeps
		// holding its port -- this host has, at times, accumulated a dozen of them
		// across runs. Prove the launch pattern kills what it starts: run a bare
		// serve+curl round trip, then, once the script has exited, confirm nothing is
		// still listening on the port it used.
		byte[] componentBytes = compileServeComponent("""
				(defun handle (env)
				  (list 200 nil (list "GET" " " (getf env :path-info))))
				(rontolisp:http-handler 'handle)
				""", null);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/serve-leak.wasm");
		ExecResult result = wasmtime.execInContainer("bash", "-c",
				"wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8089 /tmp/serve-leak.wasm"
						+ " >/tmp/serve-leak.log 2>&1 & pid=$!; trap 'kill $pid 2>/dev/null' EXIT;"
						+ " sleep 0.3; kill -0 $pid 2>/dev/null || { echo 'wasmtime serve exited immediately; log:' 1>&2;"
						+ " cat /tmp/serve-leak.log 1>&2; exit 1; };"
						+ " for i in $(seq 1 60); do out=$(curl -s http://127.0.0.1:8089/hello) && [ -n \"$out\" ]"
						+ " && { echo \"$out\"; exit 0; }; sleep 0.25; done; cat /tmp/serve-leak.log; exit 1");
		assertThat(result.getExitCode()).as("wasmtime serve round trip; log: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("GET /hello");
		// Give a killed process a moment to release the socket, then confirm the port
		// was actually freed rather than still answering.
		ExecResult probe = wasmtime.execInContainer("bash", "-c",
				"sleep 0.5; curl -s -o /dev/null --max-time 1 http://127.0.0.1:8089/hello; echo $?");
		assertThat(probe.getStdout().trim()).as("wasmtime serve must not outlive its test; port 8089 still answers")
			.isEqualTo("7");
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
	void substituteIf() throws Exception {
		assertThat(compileAndRun("(print (substitute-if 0 #'oddp '(1 2 3 4 5)))")).isEqualTo("(0 2 0 4 0)");
		assertThat(compileAndRun("(print (substitute-if-not 0 #'oddp '(1 2 3 4 5)))")).isEqualTo("(1 0 3 0 5)");
		assertThat(compileAndRun("(print (substitute-if 0 #'oddp '((1) (2) (3)) :key #'car))")).isEqualTo("(0 (2) 0)");
		assertThat(compileAndRun(
				"(print (substitute-if #\\- (lambda (c) (member c '(#\\. #\\/) :test 'char=)) \"lack/mw.backtrace\"))"))
			.isEqualTo("\"lack-mw-backtrace\"");
		assertThat(compileAndRun("(print (nsubstitute-if 0 #'oddp (list 1 2 3)))")).isEqualTo("(0 2 0)");
		assertThat(compileAndRun("(print (nsubstitute-if-not 0 #'oddp (list 1 2 3)))")).isEqualTo("(1 0 3)");
		assertThat(compileAndRun("(print (funcall #'substitute-if 0 #'oddp '(1 2 3)))")).isEqualTo("(0 2 0)");
	}

	@Test
	void sleepSpinsOnTheClockOnPreview1() throws Exception {
		// Preview 1 imports a clock but no host timer, so sleep loops on
		// get-internal-real-time -- the interval really elapses, which is what this
		// checks. The component takes the wait.lisp timer instead (see
		// componentSleepUsesTheHostTimerInsteadOfSpinning).
		assertThat(compileAndRun("""
				(setq s (get-internal-real-time))
				(sleep 0.05)
				(print (if (>= (- (get-internal-real-time) s) 40) "slept" "too-fast"))
				(print (sleep 0))
				""")).isEqualTo("\"slept\"\nNIL");
	}

	@Test
	void componentSleepUsesTheHostTimerInsteadOfSpinning() throws Exception {
		// Under --component `sleep` is the spliced wait.lisp defun: it FORCES a
		// wasi:clocks timer future through the module scheduler, so the wait costs no CPU
		// and other pending tasks still progress. It stays an ordinary synchronous defun
		// (an await would only be legal at top level or inside an async-defun), which is
		// what lets it be called from inside a plain defun -- clack's handler `stop`
		// shape -- and what makes #'sleep work with no built-in wrapper.
		assertThat(compileAndRunComponent("""
				(defun nap () (sleep 0.05))
				(setq s (get-internal-real-time))
				(nap)
				(print (if (>= (- (get-internal-real-time) s) 40) "slept" "too-fast"))
				(print (funcall #'sleep 0))
				(print (sleep 0))
				""")).isEqualTo("\"slept\"\nNIL\nNIL");
	}

	@Test
	void loadContextSpecialsAreLetBindable() throws Exception {
		assertThat(compileAndRun("""
				(print (let ((*package* *package*)
				             (*readtable* *readtable*)
				             (*load-pathname* "p")
				             (*load-truename* "t"))
				         (list *load-pathname* *load-truename* *readtable*)))
				""")).isEqualTo("(\"p\" \"t\" NIL)");
	}

	@Test
	void aSplicedFileGetsItsOwnLoadContext() throws Exception {
		// The same lowering as on the JVM (JvmLispCompilerTest): the (%begin-file
		// PATHNAME TRUENAME) brackets LoadInliner puts around every spliced file become
		// assignments of the two variables, with the enclosing file's values assigned
		// back at the end.
		assertThat(compileAndRun("""
				(defun ctx () (list *load-pathname* *load-truename*))
				(%begin-file "a.lisp" "/tmp/a.lisp")
				(print (ctx))
				(%begin-file "b.lisp" "/tmp/b.lisp")
				(print (ctx))
				(%end-file)
				(print (ctx))
				(%end-file)
				(print (ctx))
				""")).isEqualTo("""
				("a.lisp" "/tmp/a.lisp")
				("b.lisp" "/tmp/b.lisp")
				("a.lisp" "/tmp/a.lisp")
				(NIL NIL)""");
	}

	@Test
	void exportAndUnexport() throws Exception {
		// Both are consumed by the PackageResolver, so they work here even though the
		// compiled module carries no package registry.
		assertThat(compileAndRun("""
				(defpackage :expkg (:use :cl))
				(in-package :expkg)
				(export '(run))
				(defun run () 42)
				(in-package :cl-user)
				(print (expkg:run))
				(defpackage :unexpkg (:use :cl) (:export #:a #:b))
				(in-package :unexpkg)
				(unexport 'b)
				(defun a () 1)
				(defun b () 2)
				(in-package :cl-user)
				(print (+ (unexpkg:a) (unexpkg::b)))
				""")).isEqualTo("42\n3");
	}

	@Test
	void exportAfterTheDefinitions() throws Exception {
		// export grants ACCESSIBILITY and never re-keys the symbol, so the function, the
		// value and the setf-function cells defined BEFORE the export are all reachable
		// through the external spelling afterwards.
		assertThat(compileAndRun("""
				(defpackage :latepkg (:use :cl))
				(defun latepkg::my-fn (x) (* x 2))
				(defvar latepkg::*v* 7)
				(defun (setf latepkg::slot) (v c) (rplaca c v) v)
				(export '(latepkg::my-fn latepkg::*v* latepkg::slot) :latepkg)
				(print (latepkg:my-fn 21))
				(print latepkg:*v*)
				(let ((c (list 1 2)))
				  (setf (latepkg:slot c) 9)
				  (print (car c)))
				""")).isEqualTo("42\n7\n9");
	}

	@Test
	void importMakesASymbolAccessibleUnqualified() throws Exception {
		// Consumed by the PackageResolver like export, so it works in a module that
		// carries no package registry.
		assertThat(compileAndRun("""
				(defpackage :impkg (:use :cl) (:export #:pub))
				(in-package :impkg)
				(defun pub () 1)
				(defun priv () 2)
				(in-package :cl-user)
				(import 'impkg:pub)
				(import '(impkg::priv))
				(print (+ (pub) (priv)))
				""")).isEqualTo("3");
	}

	@Test
	void packageRegistryQueries() throws Exception {
		// Answered from the use table baked in at compile time -- same values the
		// interpreter reads off its live registry (package-name/-shadowing-symbols are
		// prelude defuns, so the program takes the prelude splice).
		assertThat(compileAndRunPrelude("""
				(defpackage :pql-a (:use :cl) (:export #:hi))
				(defpackage :pql-b (:use :cl :pql-a))
				(print (package-use-list :pql-b))
				(print (package-used-by-list :pql-a))
				(print (package-use-list :cl))
				(print (package-shadowing-symbols :cl-user))
				(print (car (member :pql-a (list-all-packages))))
				(defun q (p) (package-use-list p))
				(print (q (find-package "CL-USER")))
				(print (mapcar #'package-name (funcall #'package-use-list :cl-user)))
				""")).isEqualTo("(:CL :PQL-A)\n(:PQL-B)\nNIL\nNIL\n:PQL-A\n(:CL)\n(\"CL\")");
	}

	@Test
	void rempropDropsOnePropertyFromThePlist() throws Exception {
		// get / (setf get) / symbol-plist / remprop are all prelude defuns.
		assertThat(compileAndRunPrelude("""
				(setf (get 'rp 'a) 1)
				(setf (get 'rp 'b) 2)
				(setf (get 'rp 'c) 3)
				(print (list (remprop 'rp 'b) (symbol-plist 'rp) (remprop 'rp 'zz)
				             (remprop 'rp 'c) (symbol-plist 'rp) (get 'rp 'a)))
				(print (remprop 'nothing 'x))
				""")).isEqualTo("(T (C 3 A 1) NIL T (A 1) 1)\nNIL");
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
		// The full shortest round-trip decimal, identical to the interpreter and the
		// JVM.
		assertThat(compileAndRun("(print pi)")).isEqualTo("3.141592653589793");
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
	void featuresIsAnOrdinarySpecialVariable() throws Exception {
		// The WASM half of JvmLispCompilerTest's pin of the same name: *features* is a
		// list-valued special here too, seeded with the set the frontend read with,
		// pushed onto and bound like any other. The reader used to substitute the symbol
		// with the quoted list, which made every push a no-op and crashed the compile
		// outright in a BINDING position (clack:clackup's
		// (let* ((*features* (cons :clackup *features*))) ...)).
		assertThat(compileAndRun("(print (car *features*))")).isEqualTo(":RONTOLISP");
		assertThat(compileAndRun("(pushnew :my-feature *features*)(print (and (member :my-feature *features*) t))"))
			.isEqualTo("T");
		assertThat(compileAndRun("(defun f (a) (let ((*features* (cons :inner *features*))) (list a (car *features*))))"
				+ "(print (f 1))(print (car *features*))"))
			.isEqualTo("(1 :INNER)\n:RONTOLISP");
		// ... and the binding is DYNAMIC, so it reaches a callee reading the variable
		// -- the shape upstream uiop:featurep's own parameter list invites.
		assertThat(compileAndRun("(defun g (&optional (fs *features*)) (car fs))"
				+ "(print (let ((*features* '(:rebound))) (g)))(print (g))"))
			.isEqualTo(":REBOUND\n:RONTOLISP");
	}

	@Test
	void ownFeaturePushIsVisibleToTheSameSourcesConditionals() throws Exception {
		// The announcement idiom, decided in the READER so every backend agrees
		// (reader.FeaturePushes).
		assertThat(compileAndRun("""
				(eval-when (:compile-toplevel :load-toplevel :execute)
				  (pushnew :announced *features*))
				(print #+announced :saw-it #-announced :missed-it)
				(print (and (member :announced *features*) t))
				""")).isEqualTo(":SAW-IT\nT");
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
	void multiPairSetqBuildsAClosureInALaterPair() throws Exception {
		// setq takes place/value PAIRS, and a lambda in a pair after the first is a
		// closure like any other. The free-variable walk used to look at the first pair
		// only, so this failed to compile at all here ("Cannot find variable for
		// closure: G") while the JVM silently printed 0. cl-json's set-custom-vars
		// expands to a multi-pair setq whose values ARE lambdas.
		assertThat(compileAndRun("""
				(defvar *a* nil)
				(defvar *b* nil)
				(defun f ()
				  (let ((g 0))
				    (setq *a* 1 *b* (lambda (v) (setq g v) nil))
				    (funcall *b* 42)
				    g))
				(print (f))
				""")).isEqualTo("42");
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

	/** The literal spellings {@code WasmLiteralPrint} renders at compile time. */
	private static final List<String> FOLDED_PRINT_LITERALS = List.of("\"s\"", "\"a\\\"b\\\\c\"", "\"\"", "42", "-7",
			"0", "12345678901234567890", "-12345678901234567890", "1/2", "-3/4", "#\\a", "#\\Space", "#\\Newline",
			"#\\Tab", "#\\Rubout", "t", "nil");

	@Test
	void aFoldedLiteralPrintsWhatTheRuntimePrinterWouldHave() throws Exception {
		// The fold renders a literal at COMPILE time and writes static bytes, so the two
		// renderers -- LispVal.print()/display() in the compiler and _print_val /
		// _princ_val in the emitted module -- have to agree character for character.
		// Passing the same literal through a function parameter is what keeps the
		// runtime printer in the picture, so the two programs below differ in nothing
		// but which renderer produced the text.
		StringBuilder folded = new StringBuilder();
		StringBuilder viaParameter = new StringBuilder("(defun show (x) (prin1 x) (princ \"|\") (princ x) (terpri))\n");
		for (String literal : FOLDED_PRINT_LITERALS) {
			folded.append("(prin1 ")
				.append(literal)
				.append(") (princ \"|\") (princ ")
				.append(literal)
				.append(") (terpri)\n");
			viaParameter.append("(show ").append(literal).append(")\n");
		}
		String expected = compileAndRun(viaParameter.toString());
		assertThat(compileAndRun(folded.toString())).isEqualTo(expected);
		// print is the same rendering plus a newline, and write-string / write-line write
		// a string literal as it is.
		assertThat(compileAndRun("(print \"a\\\"b\") (write-string \"x\") (write-line \"y\") (princ #\\z)"))
			.isEqualTo("\"a\\\"b\"\nxy\nz");
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
	void formatWriteDirectiveRendersLikePrin1OnBothPaths() throws Exception {
		// ~W is `write` of the argument and consumes exactly one, so the ~:[...~] that
		// follows it in rove's assertion description reads the right one
		// (.kb/format.md).
		assertThat(compileAndRun("""
				(princ (format nil "Expect ~W to be ~:[true~;false~]." '(= (add 1 2) 3) nil))
				(terpri)
				(let ((c "Expect ~W to be ~:[true~;false~]."))
				  (princ (format nil c '(= (add 1 2) 3) t)))
				(terpri)
				(princ (format nil "~w|~:w|~@w|~a" "s" 'a nil "s"))
				""")).isEqualTo("""
				Expect (= (ADD 1 2) 3) to be true.
				Expect (= (ADD 1 2) 3) to be false.
				"s"|A|NIL|s""");
	}

	@Test
	void formatRuntimeControlStringHonorsEveryDirective() throws Exception {
		// The runtime renderer (format-render.lisp, injected once) understands the whole
		// directive set, so a computed control renders like the literal one would.
		assertThat(compileAndRun("""
				(let ((c "A=~A S=~S D=~D ~5,'0D% ~{~A~^,~} ~@[cond=~A~] ~~ end"))
				  (princ (format nil c 1 "s" 42 7 (list 1 2) "c")))
				""")).isEqualTo("A=1 S=\"s\" D=42 00007% 1,2 cond=c ~ end");
	}

	@Test
	void formatAsAFunctionValueHonorsEveryDirective() throws Exception {
		assertThat(compileAndRun("(princ (apply #'format nil (list \"~{~a~^ | ~} (~5,'0d)\" (list 'a 'b) 7)))"))
			.isEqualTo("A | B (00007)");
	}

	// The renderer's ~/name/ arm is injected only when a control string the compile can
	// SEE spells the directive (.kb/format.md) -- the literal below is bound to a local
	// and never appears in the format call. A control assembled at run time gets the
	// signalling stub instead; the JVM twin pins that half, and both backends have to
	// answer the same way.
	@Test
	void formatUserFunctionDirectiveRendersThroughARuntimeControl() throws Exception {
		assertThat(compileAndRun("""
				(defun fmt-slash-brackets (s x c a) (princ (if c "[" "<") s) (princ x s) (princ (if a "]" ">") s))
				(let ((c "~/fmt-slash-brackets/ ~:@/fmt-slash-brackets/"))
				  (princ (format nil c 1 2)))
				""")).isEqualTo("<1> [2]");
	}

	@Test
	void formatUserFunctionDirectiveSignalsWhenTheCompileNeverSawTheDirective() throws Exception {
		// search is prelude-backed, so this one splices the prelude like the CLI does.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(defun fmt-slash-brackets (s x c a) (princ (if c "[" "<") s) (princ x s) (princ (if a "]" ">") s))
				; The control must be built from a value the compile path cannot know, or the
				; pure-builtin literal fold reduces it back to a literal directive.
				(defun fmt-control (tilde) (concatenate 'string tilde "/fmt-slash-brackets/"))
				(princ (handler-case (format nil (fmt-control "~") 1)
				         (error (e) (if (search "--dynamic" (format nil "~a" e)) "signalled" "no-hint"))))
				""")))).isEqualTo("signalled");
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
	void formatNestedConditionalClauses() throws Exception {
		// A ~:[ nested inside another ~:[ clause, whose clauses consume a different
		// number of arguments -- expandFormat distributes the control string remainder
		// over both branches. postmodern's deftable constraint strings end with this.
		String deferrable = "~:[NOT DEFERRABLE~;DEFERRABLE INITIALLY ~:[IMMEDIATE~;DEFERRED~]~]";
		assertThat(compileAndRun("(format t \"" + deferrable + "\" nil nil)")).isEqualTo("NOT DEFERRABLE");
		assertThat(compileAndRun("(format t \"" + deferrable + "\" t nil)"))
			.isEqualTo("DEFERRABLE INITIALLY IMMEDIATE");
		assertThat(compileAndRun("(format t \"" + deferrable + "\" t t)")).isEqualTo("DEFERRABLE INITIALLY DEFERRED");
		assertThat(compileAndRun("(format t \"[~:[x~;y~a~]|~a]\" nil \"P\" \"Q\")")).isEqualTo("[x|P]");
		assertThat(compileAndRun("(format t \"[~:[x~;y~a~]|~a]\" t \"P\" \"Q\")")).isEqualTo("[yP|Q]");
	}

	@Test
	void getfDefaultRassocIfAndStringTrimListBag() throws Exception {
		assertThat(compileAndRun("(princ (getf '(:a 1) :on-delete :restrict))")).isEqualTo("RESTRICT");
		assertThat(compileAndRun("(princ (getf '(:on-delete :cascade) :on-delete :restrict))")).isEqualTo("CASCADE");
		assertThat(compileAndRun("(princ (funcall #'getf '(:x 10) :y :none))")).isEqualTo("NONE");
		assertThat(compileAndRun("(let ((n 0)) (getf '(:a 1) :a (setq n 1)) (princ n))")).isEqualTo("1");
		assertThat(compileAndRun("(princ (rassoc-if #'consp '((1 . 2) (3 4 . 5))))")).isEqualTo("(3 4 . 5)");
		assertThat(compileAndRun("(princ (string-trim '(#\\Space #\\Tab) \"\tx y \t\"))")).isEqualTo("x y");
		assertThat(compileAndRun("(let ((bag (list #\\Space))) (princ (string-trim bag \" q \")))")).isEqualTo("q");
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

	/** The program both wasm backends run for {@code *print-case*}. */
	private static final String PRINT_CASE_PROGRAM = """
			(dolist (m (list :upcase :downcase :capitalize))
			  (let ((*print-case* m))
			    (princ (princ-to-string 'add-test)) (princ "|")
			    (princ (prin1-to-string '(foo "Str" nil t 1))) (princ "|")
			    (princ (write-to-string (vector 'a 'b))) (terpri)))
			(let ((*print-case* :downcase))
			  (princ (format nil "~a ~s ~a" 'foo :foo (princ-to-string nil))) (terpri)
			  (princ 'foo) (princ "|") (prin1 :foo) (terpri))
			(let ((*print-case* :capitalize))
			  (princ (princ-to-string (intern "*FOO*"))) (princ "|")
			  (princ (princ-to-string (intern "foo-BAR"))) (terpri))
			""";

	/**
	 * What SBCL 2.2.9 prints for {@link #PRINT_CASE_PROGRAM}, minus the trailing newline.
	 */
	private static final String PRINT_CASE_EXPECTED = """
			ADD-TEST|(FOO "Str" NIL T 1)|#(A B)
			add-test|(foo "Str" nil t 1)|#(a b)
			Add-Test|(Foo "Str" Nil T 1)|#(A B)
			foo :foo nil
			foo|:foo
			*Foo*|foo-Bar""";

	// The default instance renderer's cycle guard: an instance already on the current
	// rendering path -- or the frame past the 256-frame depth cap -- prints as "#",
	// CL's *print-level* cutoff marker, byte-identical to the interpreter and the JVM
	// backend (LispEvaluatorTest.evalPrintOfACyclicInstanceGraphIsFinite,
	// JvmLispCompilerTest.compileAndRunPrintOfACyclicInstanceGraphIsFinite). Without it
	// two instances pointing at each other exhausted the wasm stack mid-write.
	private static final String CYCLIC_PRINT_PROGRAM = """
			(defclass cyc () ((next :initform nil :accessor cyc-next)
			                  (tag :initarg :tag :reader cyc-tag)))
			(let ((p (make-instance 'cyc :tag 1)) (q (make-instance 'cyc :tag 2)))
			  (setf (cyc-next p) q)
			  (setf (cyc-next q) p)
			  (print p)
			  (princ p))
			(terpri)
			(defstruct knot next)
			(let ((k (make-knot)))
			  (setf (knot-next k) k)
			  (prin1 k))
			""";

	private static final String CYCLIC_PRINT_EXPECTED = """
			#<CYC :NEXT #<CYC :NEXT # :TAG 2> :TAG 1>
			#<CYC :NEXT #<CYC :NEXT # :TAG 2> :TAG 1>
			#S(KNOT :NEXT #)""";

	@Test
	void printOfACyclicInstanceGraphIsFinite() throws Exception {
		assertThat(compileAndRun(CYCLIC_PRINT_PROGRAM)).isEqualTo(CYCLIC_PRINT_EXPECTED);
	}

	@Test
	void printOfACyclicInstanceGraphIsFiniteOnTheComponentPath() throws Exception {
		assertThat(compileComponentAndRun(CYCLIC_PRINT_PROGRAM)).isEqualTo(CYCLIC_PRINT_EXPECTED);
	}

	// The cons and vector arms' cycle guard (todo-585): a cdr chain that re-enters
	// itself prints every element once and then the improper tail " . #" (Floyd's
	// cycle detection over the chain); a cons or vector already on the current
	// rendering path prints as "#" -- byte-identical to the interpreter and the JVM
	// backend (LispEvaluatorTest.evalPrintOfACyclicConsIsFinite,
	// JvmLispCompilerTest.compileAndRunPrintOfACyclicConsIsFinite). Without it a
	// cyclic chain streamed elements until the write trapped mid-buffer.
	private static final String CYCLIC_CONS_PRINT_PROGRAM = """
			(let ((x (list 1)))
			  (setf (cdr x) x)
			  (prin1 x)
			  (terpri)
			  (princ x))
			(terpri)
			(let ((x (list 1 2 3)))
			  (setf (cdr (cdr (cdr x))) (cdr x))
			  (prin1 x))
			(terpri)
			(let ((x (list 1 2)))
			  (setf (car x) x)
			  (prin1 x))
			(terpri)
			(let ((v (vector 1 2)))
			  (setf (aref v 0) v)
			  (prin1 v))
			(terpri)
			(let ((s (list 9)))
			  (prin1 (list s s)))
			""";

	private static final String CYCLIC_CONS_PRINT_EXPECTED = """
			(1 . #)
			(1 . #)
			(1 2 3 . #)
			(# 2)
			#(# 2)
			((9) (9))""";

	@Test
	void printOfACyclicConsIsFinite() throws Exception {
		assertThat(compileAndRun(CYCLIC_CONS_PRINT_PROGRAM)).isEqualTo(CYCLIC_CONS_PRINT_EXPECTED);
	}

	@Test
	void printOfACyclicConsIsFiniteOnTheComponentPath() throws Exception {
		assertThat(compileComponentAndRun(CYCLIC_CONS_PRINT_PROGRAM)).isEqualTo(CYCLIC_CONS_PRINT_EXPECTED);
	}

	@Test
	void printCase() throws Exception {
		// *print-case* converts the case of every SYMBOL the printer spells, through the
		// shared %print-cased renderer the prelude splice brings in.
		assertThat(compileAndRunPrelude(PRINT_CASE_PROGRAM)).isEqualTo(PRINT_CASE_EXPECTED);
	}

	@Test
	void printCaseOnTheComponentPath() throws Exception {
		assertThat(compileComponentAndRunPrelude(PRINT_CASE_PROGRAM)).isEqualTo(PRINT_CASE_EXPECTED);
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
	void prin1EscapesQuotesAndBackslashesInStrings() throws Exception {
		// *print-escape* = t escapes the embedded " and \ (todo 216); princ / ~a do not.
		// A bare SYMBOL still prints verbatim -- the leading quote is the discriminator.
		assertThat(compileAndRun("(prin1 \"{\\\"hello\\\":\\\"aaa\\\"}\")"))
			.isEqualTo("\"{\\\"hello\\\":\\\"aaa\\\"}\"");
		assertThat(compileAndRun("(prin1 (list \"x\\\"y\" 'foo))")).isEqualTo("(\"x\\\"y\" FOO)");
		assertThat(compileAndRun("(princ (prin1-to-string \"a\\\"b\\\\c\"))")).isEqualTo("\"a\\\"b\\\\c\"");
		assertThat(compileAndRun("(princ (format nil \"~s|~a\" \"a\\\"b\" \"a\\\"b\"))")).isEqualTo("\"a\\\"b\"|a\"b");
		assertThat(compileAndRun("(princ \"{\\\"hello\\\":\\\"aaa\\\"}\")")).isEqualTo("{\"hello\":\"aaa\"}");
	}

	@Test
	void prin1OutputReadsBackAsTheSameString() throws Exception {
		assertThat(compileAndRun("""
				(let ((s (concatenate 'string "a" (string (code-char 34)) "b"
				                      (string (code-char 92)) "c"
				                      (string #\\Newline) "d")))
				  (prin1 (list (equal (read-from-string (prin1-to-string s)) s) (length s))))
				""")).isEqualTo("(T 7)");
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
	void replaceIntoAList() throws Exception {
		// A list destination is rewritten through its cons cells, like the interpreter's
		// native replace. It used to fall through to the immutable-string rebuild here
		// and print the three subsequences concatenated as text -- and (setf (subseq l
		// ...)) on a list was a silent no-op, the rebuilt value dropped in statement
		// position (.kb/sequence-op-runtimes.md).
		assertThat(compileAndRun("(print (replace (list 1 2 3 4 5) '(9 9) :start1 1))")).isEqualTo("(1 9 9 4 5)");
		assertThat(compileAndRun("(print (replace (list 0 0 0 0) #(7 8 9) :start1 1 :end1 3 :start2 1))"))
			.isEqualTo("(0 8 9 0)");
		assertThat(compileAndRun("(print (replace (list 1 2) '(5 6 7 8)))")).isEqualTo("(5 6)");
		assertThat(compileAndRun("(let ((l (list 1 2 3 4 5))) (setf (subseq l 1) '(9 9)) (print l))"))
			.isEqualTo("(1 9 9 4 5)");
		// The list-DESTINATION arm was the one arm still spelling (elt source (+ start2
		// k)), so a list-into-list replace stayed quadratic here long after the array arm
		// took a cursor (16.7 ms at n=4000 against 0.05 for an array destination). It
		// reads through a cursor now, falling back to the same elt call for everything
		// the cursor cannot reach, so no answer moved.
		assertThat(compileAndRun("(print (replace (list 0 0 0 0 0) (list 1 2 3 4 5) :start1 1 :start2 2))"))
			.isEqualTo("(0 3 4 5 0)");
		assertThat(compileAndRun("(print (replace (list 0 0 0) \"xy\"))")).isEqualTo("(#\\x #\\y 0)");
		assertThat(compileAndRun("(print (replace (list 0 0 0) (coerce (list 4 5 6) 'vector)))")).isEqualTo("(4 5 6)");
		assertThat(compileAndRun("(print (replace (list 0 0 0 0) (list 1 2) :start2 9))")).isEqualTo("(0 0 0 0)");
		assertThat(compileAndRun("(let ((l (list 1 2 3 4))) (replace l l :start1 1) (print l))"))
			.isEqualTo("(1 1 1 1)");
		assertThat(compileAndRun("""
				(let* ((long (let ((out nil))
				               (dotimes (i 2000) (setq out (cons (mod i 7) out)))
				               (nreverse out)))
				       (dst (let ((out nil)) (dotimes (i 2000) (setq out (cons 0 out))) out)))
				  (replace dst long)
				  (print (list (first dst) (nth 1999 dst) (length dst))))
				""")).isEqualTo("(0 4 2000)");
	}

	@Test
	void makeArrayInitialContentsWalksAListWithACursor() throws Exception {
		// The fill indexed the contents with elt so it could serve ANY sequence, and elt
		// on a list is an nth walk from the head: the whole fill was quadratic here (8.29
		// ms at n=4000 against the interpreter's native 0.045). A cons cursor beside the
		// index serves a list in O(1) and pins itself to a non-cons for every other
		// representation, which keeps indexing exactly as before.
		assertThat(compileAndRun("(let ((c (list 1 2 3 4))) (print (make-array 4 :initial-contents c)))"))
			.isEqualTo("#(1 2 3 4)");
		assertThat(compileAndRun(
				"(let ((c (list 1 2 3 4))) (print (make-array 4 :element-type '(unsigned-byte 8) :initial-contents c)))"))
			.isEqualTo("#(1 2 3 4)");
		assertThat(
				compileAndRun("(let ((c (coerce (list 1 2 3) 'vector))) (print (make-array 3 :initial-contents c)))"))
			.isEqualTo("#(1 2 3)");
		assertThat(compileAndRun("(print (make-array 3 :initial-contents \"xyz\"))")).isEqualTo("#(#\\x #\\y #\\z)");
		assertThat(compileAndRun(
				"(print (make-array 3 :element-type 'character :initial-contents (list #\\a #\\b #\\c)))"))
			.isEqualTo("\"abc\"");
		// Rank >= 2 has one cursor per LEVEL, re-seeded per iteration of the level above.
		assertThat(compileAndRun(
				"(let ((c (list (list 1 2 3) (list 4 5 6)))) (print (make-array '(2 3) :initial-contents c)))"))
			.isEqualTo("#2A((1 2 3) (4 5 6))");
		assertThat(compileAndRun(
				"(print (make-array '(2 3) :initial-contents (list (coerce (list 1 2 3) 'vector) (list 4 5 6))))"))
			.isEqualTo("#2A((1 2 3) (4 5 6))");
		assertThat(compileAndRun(
				"(print (make-array '(2 3) :initial-contents (coerce (list (list 1 2 3) (list 4 5 6)) 'vector)))"))
			.isEqualTo("#2A((1 2 3) (4 5 6))");
		assertThat(compileAndRun("""
				(print (make-array '(2 2 2) :initial-contents
					(list (list (list 1 2) (list 3 4)) (list (list 5 6) (list 7 8)))))
				""")).isEqualTo("#3A(((1 2) (3 4)) ((5 6) (7 8)))");
		// A row shorter than the dimension, and a row the contents do not have: the
		// cursor runs out and the read falls back to the elt call this fill always made,
		// which answers NIL past a proper list's end.
		assertThat(compileAndRun("(print (make-array '(2 3) :initial-contents (list (list 1 2) (list 4 5 6))))"))
			.isEqualTo("#2A((1 2 NIL) (4 5 6))");
		assertThat(compileAndRun("(print (make-array '(2 3) :initial-contents (list (list 1 2 3))))"))
			.isEqualTo("#2A((1 2 3) (NIL NIL NIL))");
	}

	@Test
	void mapWalksAListWithACursor() throws Exception {
		// map read each operand with (nth i s) for a list, an nth walk from the head, so
		// (map 'list ...) over a list -- and (map 'vector ...), which routes through it
		// -- was O(n^2): 12.9 ms at n=4000 against 0.015 for mapcar. Each operand now
		// carries a cons cursor; a non-list operand pins a non-cons cursor and keeps
		// reading through the stringp/listp/aref three-way.
		assertThat(compileAndRun("(let ((l (list 1 2 3 4))) (print (map 'list #'1+ l)))")).isEqualTo("(2 3 4 5)");
		assertThat(compileAndRun("(let ((l (list 1 2 3 4))) (print (map 'vector #'1+ l)))")).isEqualTo("#(2 3 4 5)");
		assertThat(compileAndRun("(let ((l (list 1 2 3))) (print (map 'list #'+ l (coerce (list 10 20) 'vector))))"))
			.isEqualTo("(11 22)");
		assertThat(compileAndRun("(let ((l (list 1 2 3))) (print (map 'list #'list l \"ab\")))"))
			.isEqualTo("((1 #\\a) (2 #\\b))");
		assertThat(compileAndRun("(print (map 'string #'char-upcase (coerce (list #\\a #\\b) 'string)))"))
			.isEqualTo("\"AB\"");
		assertThat(compileAndRun("(let ((l (list 1 2 3))) (print (map nil #'1+ l)))")).isEqualTo("NIL");
		assertThat(compileAndRun("""
				(let ((long (let ((out nil))
				              (dotimes (i 2000) (setq out (cons (mod i 7) out)))
				              (nreverse out))))
				  (print (list (first (map 'list #'1+ long)) (car (last (map 'list #'1+ long)))
				               (length (map 'vector #'1+ long)))))
				""")).isEqualTo("(1 5 2000)");
	}

	@Test
	void theFormatRendererReadsItsArgumentListThroughAMaterializedVector() throws Exception {
		// The renderer indexed its argument list with (nth i all) per directive and
		// measured (length items) TWICE per ~{ pass: 3.00 ms a call at n = 2000, 50x
		// building the same text by hand. `all` is now the (list vector) pair %fmt-args
		// builds, read through %fmt-arg / %fmt-count -- the argument pointer moves in
		// FOUR directions, so a monotone cursor could not have served it.
		assertThat(compileAndRun("""
				(let ((c "~a~a~:*~a~2:*~a~@*~a~5@*~a~11@*~a~12@*~a|"))
				  (princ (apply #'format nil c '(a b c d e f g h i j k l)))
				  (princ (apply #'format nil c '(a b c))))
				""")).isEqualTo("ABBAAFLNIL|ABBAANILNILNIL|");
		assertThat(compileAndRun("""
				(let ((c "~a~*~a~*~a"))
				  (princ (apply #'format nil c '(1 2 3 4 5)))
				  (princ (apply #'format nil c '(1 2 3 4 5 6 7 8 9 10))))
				""")).isEqualTo("135135");
		assertThat(compileAndRun("""
				(let ((c "~#a~a"))
				  (princ (apply #'format nil c '(x y z)))
				  (princ (apply #'format nil c '(x y z 1 2 3 4 5 6 7))))
				""")).isEqualTo("X  YX         Y");
		assertThat(compileAndRun("""
				(let ((c "~{~a~^,~}"))
				  (print (list (format nil c '(1 2 3))
				               (format nil c '(1 2 3 4 5 6 7 8 9 10 11 12))
				               (format nil c nil)
				               (format nil "~:{[~a ~a]~}" '((1 2) (3 4) (5 6) (7 8) (9 10)))
				               (apply #'format nil "|~@{~a~^-~}" '(1 2 3 4 5 6 7 8 9)))))
				""")).isEqualTo("(\"1,2,3\" \"1,2,3,4,5,6,7,8,9,10,11,12\" \"\" \"[1 2][3 4][5 6][7 8][9 10]\" "
				+ "\"|1-2-3-4-5-6-7-8-9\")");
		assertThat(compileAndRun("""
				(let ((c "~{~a~}[~a]"))
				  (princ (format nil c 5 'tail))
				  (princ (format nil c nil 'tail)))
				""")).isEqualTo("[TAIL][TAIL]");
		assertThat(compileAndRun("""
				(let* ((c "~{~a~}")
				       (long (let ((out nil))
				               (dotimes (i 3000) (setq out (cons (mod i 7) out)))
				               (nreverse out)))
				       (s (format nil c long)))
				  (print (list (length s) (subseq s 0 9) (subseq s 2991))))
				""")).isEqualTo("(3000 \"012345601\" \"234560123\")");
	}

	@Test
	void theMapIntoWrapperWalksAListDestinationWithACursor() throws Exception {
		// #'map-into as a VALUE stored with (setf (elt r i) v), an O(i) head-walk for a
		// list destination: 2.14 ms a call at n = 2000, 54x the same operation in call
		// position. The wrapper now carries the result cursor its lowering has always
		// had; every other representation keeps the indexed store.
		assertThat(compileAndRun("""
				(print (list (funcall #'map-into (list 0 0 0 0) #'+ '(1 2 3) '(10 20 30 40))
				             (funcall #'map-into (make-array 3) #'* #(2 3 4) #(5 6 7))
				             (funcall #'map-into (list 0 0 0) (lambda () 42))
				             (funcall #'map-into (list 0 0 0) #'+ #(1 2 3) '(10 20 30))
				             (funcall #'map-into nil #'1+ '(1 2 3))
				             (funcall #'map-into (list 0) #'1+ '(1 2 3))
				             (funcall #'map-into (copy-seq "xxxxx") #'char-upcase "abc")
				             (funcall #'map-into (make-array 6 :fill-pointer 3 :initial-element 0)
				                      #'1+ '(1 2 3 4 5 6))))
				""")).isEqualTo("((11 22 33 0) #(10 18 28) (42 42 42) (11 22 33) NIL (2) \"ABCxx\" #(2 3 4))");
		assertThat(
				compileAndRun("(let ((d (list 0 0 0))) (print (list (eq (funcall #'map-into d #'1+ '(1 2 3)) d) d)))"))
			.isEqualTo("(T (2 3 4))");
		assertThat(compileAndRun("(let ((l (list 1 2 3 4))) (print (list (funcall #'map-into l #'1+ l) l)))"))
			.isEqualTo("((2 3 4 5) (2 3 4 5))");
		assertThat(compileAndRun("""
				(let* ((n 2000)
				       (src (let ((out nil))
				              (dotimes (i n) (setq out (cons (mod i 7) out)))
				              (nreverse out)))
				       (dst (make-list n)))
				  (funcall #'map-into dst #'1+ src)
				  (print (list (car dst) (car (last dst)) (length dst))))
				""")).isEqualTo("(1 5 2000)");
	}

	@Test
	void theStringResultOfMapJoinsItsPiecesOnce() throws Exception {
		// (map 'string ...) rebuilt the whole result once per element, quadratic in the
		// OUTPUT: 56.2 ms a call at n = 4000 on wasm-GC, and the cursor beside it could
		// not move it because the defect is the STORE. The pieces are collected and
		// joined pairwise now.
		assertThat(compileAndRun("""
				(print (list (map 'string #'identity nil)
				             (map 'string #'identity (list #\\a))
				             (map 'string #'identity (list #\\a #\\b))
				             (map 'string #'identity (list #\\a #\\b #\\c))
				             (map 'string #'identity (list #\\a #\\b #\\c #\\d))
				             (map 'string #'identity (list #\\a #\\b #\\c #\\d #\\e))
				             (map 'string #'identity (list 1 2 33))
				             (map 'string #'char-upcase "abc")
				             (map 'string #'identity "")
				             (map 'string #'identity #(#\\x #\\y))))
				""")).isEqualTo("(\"\" \"a\" \"ab\" \"abc\" \"abcd\" \"abcde\" \"1233\" \"ABC\" \"\" \"xy\")");
		assertThat(compileAndRun("(print (list (coerce (list #\\x #\\y #\\z) 'string) (coerce '(1 2) 'string)))"))
			.isEqualTo("(\"xyz\" \"12\")");
		assertThat(compileAndRun("""
				(let ((s (map 'string #'char-upcase (make-string 5000 :initial-element #\\q))))
				  (print (list (length s) (subseq s 0 4) (char s 4999))))
				""")).isEqualTo("(5000 \"QQQQ\" #\\Q)");
	}

	@Test
	void sequenceOpRuntimeArmRouting() throws Exception {
		// The narrowing gate of .kb/sequence-op-runtimes.md, from both sides IN ONE
		// PROGRAM: sites whose destination this compile proves to be an array call the
		// array-arm-only shared runtime, and the list / immutable-string destinations
		// that must NOT take that arm still answer what they always did. A gate that
		// over-predicted would trap here rather than answer wrong data, which is why
		// this runs the module rather than reading it.
		String source = """
				(defun sor-copy (n)
				  (let ((a (make-array (* 2 n) :element-type '(unsigned-byte 8)))
				        (b (make-array (* 2 n) :element-type '(unsigned-byte 8))))
				    (fill b 7 :start 1)
				    (replace a b :start1 1 :end1 3)
				    (replace a '(1 2) :start1 5)
				    a))
				(print (sor-copy 3))
				(print (replace (list 0 0 0 0) '(1 2) :start1 1))
				(print (replace "abcdef" "XY" :start1 2))
				(print (fill (list 1 2 3) 9 :start 1))
				(print (fill "abcdef" #\\z :start 2 :end 4))
				""";
		assertThat(compileAndRun(source)).isEqualTo("""
				#(0 0 7 0 0 1)
				(0 1 2 0)
				"abXYef"
				(1 9 9)
				"abzzef\"""");
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
	void stringDesignators() throws Exception {
		// CL coerces a string DESIGNATOR -- a string, a symbol (nil and t included) or a
		// character -- wherever a string is expected. A LITERAL designator folds to a
		// constant; a computed one goes through the shared, type-checking (string ...).
		assertThat(compileAndRun("(princ (string-trim \"*\" '*foo*))")).isEqualTo("FOO");
		assertThat(compileAndRun("(princ (string-left-trim \"F\" '|FOO|))")).isEqualTo("OO");
		assertThat(compileAndRun("(princ (string-right-trim \"O\" '|FOO|))")).isEqualTo("F");
		assertThat(compileAndRun("(princ (string-trim \"N\" nil))")).isEqualTo("IL");
		assertThat(compileAndRun("(princ (string-trim \"K\" :key))")).isEqualTo("EY");
		assertThat(compileAndRun("(princ (string-trim \"A\" #\\a))")).isEqualTo("a");
		assertThat(compileAndRun("(defun id (x) x) (princ (string-trim \"*\" (id '*foo*)))")).isEqualTo("FOO");
		assertThat(compileAndRun("(defun id (x) x) (princ (string-upcase (id #\\a)))")).isEqualTo("A");
		assertThat(compileAndRun("(princ (string-capitalize nil))")).isEqualTo("Nil");
		// A non-designator still signals: the widening is per POSITION, not "stringify
		// anything". Before this, the compile paths took the symbol's runtime spelling
		// for a quoted string and answered a silently wrong substring.
		assertThat(compileAndRun(
				"(defun id (x) x) (princ (handler-case (string-trim \"*\" (id 42)) (error () :signalled)))"))
			.isEqualTo("SIGNALLED");
		assertThat(
				compileAndRun("(defun id (x) x) (princ (handler-case (string-upcase (id 42)) (error () :signalled)))"))
			.isEqualTo("SIGNALLED");
		assertThat(compileAndRun(
				"(defun id (x) x) (princ (handler-case (string-trim (id #\\*) \"*x*\") (error () :signalled)))"))
			.isEqualTo("SIGNALLED");
		// ... but ANY sequence of characters is a bag, a general vector included.
		assertThat(compileAndRun("(defun id (x) x) (princ (string-trim (id (vector #\\x)) \"xhellox\"))"))
			.isEqualTo("hello");
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
	void nestedDefunsShareTheEnclosingLetsBindingRatherThanEachTakingACopy() throws Exception {
		// The CL closure-over-let idiom (cl-ppcre spells its scanner caches this way).
		// A nested defun is not a definition on the compile paths: it lowers to
		// (setq name (lambda ...)), a closure over the let variables, so the capture
		// analysis must see it. Skipping defun left the binding unboxed and gave every
		// nested definition a private copy -- the compiled answer was (0 START) where
		// the interpreter says (2 LATER).
		assertThat(compileAndRun("""
				(let ((counter 0) (tag 'start))
				  (defun bump () (setq counter (+ counter 1)))
				  (defun retag (v) (setq tag v))
				  (defun peek () (list counter tag)))
				(bump)
				(bump)
				(retag 'later)
				(print (peek))
				""")).isEqualTo("(2 LATER)");
	}

	@Test
	void aDefunNestedInADefunBodyIsReachableByName() throws Exception {
		// The same lowering as the closure-over-let idiom above, one level deeper: the
		// nested defun becomes (setq read-seed (lambda () seed)) inside install's body,
		// so the NAME needs the same global backing store for a call site to dispatch
		// through it. Collecting it only from top-level non-defun forms left the call
		// compiled as "The function READ-SEED is undefined".
		assertThat(compileAndRun("""
				(defun install (seed)
				  (defun read-seed () seed)
				  (lambda () (setq seed (+ seed 1)) seed))
				(setq *step* (install 10))
				(funcall *step*)
				(funcall *step*)
				(print (list (read-seed) (funcall *step*)))
				""")).isEqualTo("(12 13)");
	}

	@Test
	void aDefunNestedInADefunBodyRedefinesAnExistingTopLevelDefun() throws Exception {
		// The JvmLispCompilerTest twin: the redefined name has BOTH a top-level defun
		// and a nested one, and the call site resolved the compiled function first -- so
		// the nested definition was written to a store nothing read and the call after
		// (redefiner) still answered TOP. The top-level definition is renamed and its
		// function value assigned to the global in its place, so the one variable
		// carries both answers in order (.kb/core-representation.md, "The NAME half").
		assertThat(compileAndRun("""
				(defun over () 'top)
				(defun redefiner ()
				  (defun over () 'nested)
				  'done)
				(print (over))
				(print (redefiner))
				(print (over))
				""")).isEqualTo("TOP\nDONE\nNESTED");
	}

	@Test
	void aRedefinedDefunIsAlsoRedefinedForFunctionReferencesAndCallers() throws Exception {
		// #'over and a caller compiled BEFORE the redefinition must see it too: both
		// read the same global variable, and the call is a funcall rather than a fixed
		// direct call, so the two definitions need not share an arity.
		assertThat(compileAndRun("""
				(defun over (x) (list 'top x))
				(defun call-it (x) (over x))
				(defun redefiner ()
				  (defun over (x) (list 'nested x))
				  'done)
				(print (call-it 1))
				(print (funcall #'over 2))
				(redefiner)
				(print (call-it 3))
				(print (funcall #'over 4))
				""")).isEqualTo("(TOP 1)\n(TOP 2)\n(NESTED 3)\n(NESTED 4)");
	}

	@Test
	void anInlineLambdaCallBoxesAParameterItsBodyClosesOver() throws Exception {
		// ((lambda (n) ...) 0) binds n in the CALLER's frame, and that binder never
		// asked whether the body closes over it -- so the nested lambda was handed a
		// snapshot cell and its assignments never reached n. Answered 0, not 2.
		assertThat(compileAndRun("""
				(print ((lambda (n)
				          (let ((g (lambda () (setq n (+ n 1)))))
				            (funcall g)
				            (funcall g)
				            n))
				        0))
				""")).isEqualTo("2");
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

	// A character index costs the same wherever it lands: scanning ONE long string costs
	// what scanning the SAME characters in short chunks costs. Before, _str_char_at
	// decoded the UTF-8 data forward from byte 0 on every call, so a left-to-right scan
	// was quadratic and the whole-string half below was 64x the chunked one (4,230 ms
	// against 66 ms over 131,072 characters). The comparison is against the chunked half
	// rather than a wall-clock constant so the bound does not depend on the machine.
	@Test
	void aCharacterIndexDoesNotDecodeFromTheStartOfTheString() throws Exception {
		assertScanIsFlat(compileAndRun(SCAN_PROGRAM.formatted("\"0123456789abcdef\"")), "ASCII");
	}

	// Same over multi-byte content, where the byte offset of a character genuinely has to
	// be walked: the cursor is what keeps a scan linear, not an ASCII shortcut.
	@Test
	void aCharacterIndexDoesNotDecodeFromTheStartOfAMultiByteString() throws Exception {
		assertScanIsFlat(compileAndRun(SCAN_PROGRAM.formatted("\"あいうえおかきくけこさしすせそた\"")), "Hiragana");
	}

	// Builds a 1,024-character string and the 131,072-character string that IS that
	// string 128 times over, scans each, and prints the two elapsed times in ms. Both
	// grow from the same literal rather than the shorter (defvar *long* *short*), which
	// the compile paths get wrong.
	private static final String SCAN_PROGRAM = """
			(defun scan-sum (s)
			  (let ((total 0))
			    (dotimes (i (length s))
			      (setq total (+ total (char-code (char s i)))))
			    total))
			(defvar *short* %1$s)
			(dotimes (i 6) (setq *short* (concatenate 'string *short* *short*)))
			(defvar *long* %1$s)
			(dotimes (i 13) (setq *long* (concatenate 'string *long* *long*)))
			(scan-sum "warm")
			(defvar *t0* (get-internal-real-time))
			(defvar *whole* (scan-sum *long*))
			(defvar *t1* (get-internal-real-time))
			(defvar *chunked* 0)
			(dotimes (k 128) (setq *chunked* (+ *chunked* (scan-sum *short*))))
			(defvar *t2* (get-internal-real-time))
			(print (= *whole* *chunked*))
			(princ (- *t1* *t0*)) (terpri)
			(princ (- *t2* *t1*)) (terpri)
			""";

	private static void assertScanIsFlat(String output, String label) {
		String[] lines = output.split("\n");
		assertThat(lines[0]).as("the two halves must scan the same characters").isEqualTo("T");
		long whole = Long.parseLong(lines[1].trim());
		long chunked = Long.parseLong(lines[2].trim());
		assertThat(whole)
			.as("%s: scanning 131,072 characters as one string (%d ms) against the same "
					+ "characters in 1,024-character chunks (%d ms)", label, whole, chunked)
			.isLessThanOrEqualTo(500 + 6 * chunked);
	}

	// The same cost invariant over the MUTABLE CHARACTER VECTOR representation (a
	// make-string buffer). (char v i) used to render the WHOLE vector into a fresh
	// string per index (_charvec_to_str at the site), which made a scan O(n^2); it
	// reads the element through _str_char_ref -> _arr_get now, so a scan of one long
	// buffer costs what scanning the same characters in short chunks costs
	// (.kb/string-index-cost.md).
	@Test
	void aCharacterIndexIntoACharacterVectorDoesNotRenderTheVector() throws Exception {
		String output = compileAndRun("""
				(defun cv-scan-sum (s)
				  (let ((total 0))
				    (dotimes (i (length s))
				      (setq total (+ total (char-code (char s i)))))
				    total))
				(defvar *cv-short* (make-string 1024 :initial-element #\\a))
				(defvar *cv-long* (make-string 65536 :initial-element #\\a))
				(cv-scan-sum (make-string 4 :initial-element #\\w))
				(defvar *cv-t0* (get-internal-real-time))
				(defvar *cv-whole* (cv-scan-sum *cv-long*))
				(defvar *cv-t1* (get-internal-real-time))
				(defvar *cv-chunked* 0)
				(dotimes (k 64) (setq *cv-chunked* (+ *cv-chunked* (cv-scan-sum *cv-short*))))
				(defvar *cv-t2* (get-internal-real-time))
				(print (= *cv-whole* *cv-chunked*))
				(princ (- *cv-t1* *cv-t0*)) (terpri)
				(princ (- *cv-t2* *cv-t1*)) (terpri)
				""");
		String[] lines = output.split("\n");
		assertThat(lines[0]).as("the two halves must scan the same characters").isEqualTo("T");
		long whole = Long.parseLong(lines[1].trim());
		long chunked = Long.parseLong(lines[2].trim());
		assertThat(whole)
			.as("scanning a 65,536-character make-string buffer as one vector (%d ms) against "
					+ "the same characters in 1,024-character chunks (%d ms)", whole, chunked)
			.isLessThanOrEqualTo(500 + 6 * chunked);
	}

	// The correctness half of the read above: char/schar/elt/aref agree on a character
	// vector, non-ASCII elements included, and a displaced string view reads through
	// the same element path.
	@Test
	void aCharacterVectorIndexAgreesAcrossTheFourSpellings() throws Exception {
		assertThat(compileAndRun("""
				(defvar *cv* (make-string 4 :initial-element #\\a))
				(setf (char *cv* 1) #\\é)
				(setf (char *cv* 2) (code-char 128512))
				(print (list (char-code (char *cv* 1)) (char-code (schar *cv* 1))
				             (char-code (elt *cv* 2)) (char-code (aref *cv* 2))))
				(defvar *cvv* (make-array 2 :element-type 'character :displaced-to *cv*
				                            :displaced-index-offset 1))
				(print (char-code (char *cvv* 1)))
				""")).isEqualTo("(233 233 128512 128512)\n128512");
	}

	// An index outside the string answers whatever it answered before (bounds are
	// unchecked on this backend) -- what matters here is that it cannot leave
	// the string's index cursor pointing outside its byte array, which would trap on
	// every LATER index. A negative index is the dangerous one: it is the only input that
	// can walk backwards past the opening quote.
	@Test
	void anOutOfRangeIndexLeavesTheStringUsable() throws Exception {
		assertThat(compileAndRun("""
				(defvar *s* "hello")
				(char *s* -1)
				(char *s* -9)
				(char *s* 99)
				(princ (char-code (char *s* 1)))
				(princ " ")
				(princ (length *s*))
				(princ " ")
				(princ (char-code (char *s* 4)))
				""")).isEqualTo("101 5 111");
	}

	// (length s) reads the same walk, so it may not be linear in the length either:
	// 20,000 (length *long*) calls against 20,000 (length *short*) ones. Before, every
	// call counted the UTF-8 lead bytes of the whole string (5,510 ms against 40 ms).
	@Test
	void aStringLengthDoesNotRecountTheWholeStringOnEveryCall() throws Exception {
		String output = compileAndRun("""
				(defvar *short* "0123456789abcdef")
				(dotimes (i 6) (setq *short* (concatenate 'string *short* *short*)))
				(defvar *long* "0123456789abcdef")
				(dotimes (i 13) (setq *long* (concatenate 'string *long* *long*)))
				(defvar *acc* 0)
				(defvar *t0* (get-internal-real-time))
				(dotimes (k 20000) (setq *acc* (+ *acc* (length *long*))))
				(defvar *t1* (get-internal-real-time))
				(dotimes (k 20000) (setq *acc* (+ *acc* (length *short*))))
				(defvar *t2* (get-internal-real-time))
				(print (= *acc* (* 20000 (+ 131072 1024))))
				(princ (- *t1* *t0*)) (terpri)
				(princ (- *t2* *t1*)) (terpri)
				""");
		String[] lines = output.split("\n");
		assertThat(lines[0]).as("the two halves must be the lengths they claim").isEqualTo("T");
		long whole = Long.parseLong(lines[1].trim());
		long chunked = Long.parseLong(lines[2].trim());
		assertThat(whole)
			.as("20,000 lengths of a 131,072-character string (%d ms) against 20,000 of a "
					+ "1,024-character one (%d ms)", whole, chunked)
			.isLessThanOrEqualTo(500 + 6 * chunked);
	}

	@Test
	void stringCaseOpsAreFullUnicode() throws Exception {
		// The string case operators decode the UTF-8 content one code point at a time
		// and call the same _char_upcase / _char_downcase range-table helpers
		// char-upcase uses, so they fold every Unicode letter exactly like the
		// interpreter and the JVM compile path -- no ASCII-only +/- 32 loop.
		assertThat(compileAndRun("(print (string-downcase \"ÉΛΩ\"))")).isEqualTo("\"éλω\"");
		assertThat(compileAndRun("(print (string-upcase \"éλω\"))")).isEqualTo("\"ÉΛΩ\"");
		assertThat(compileAndRun("(print (string-capitalize \"élan vital\"))")).isEqualTo("\"Élan Vital\"");
		assertThat(compileAndRun("(print (string-upcase \"привет\"))")).isEqualTo("\"ПРИВЕТ\"");
		// Astral cased letters exercise the 4-byte UTF-8 decode / re-encode path.
		assertThat(compileAndRun("(print (string-upcase \"𐐨𐐩\"))")).isEqualTo("\"𐐀𐐁\"");
		assertThat(compileAndRun("(print (string-downcase \"𐐀𐐁\"))")).isEqualTo("\"𐐨𐐩\"");
		// Length is preserved in CHARACTERS, so sharp s stays one character and a
		// final sigma is not context-folded.
		assertThat(compileAndRun("(print (string-upcase \"straße\"))")).isEqualTo("\"STRAßE\"");
		assertThat(compileAndRun("(print (length (string-upcase \"straße\")))")).isEqualTo("6");
		assertThat(compileAndRun("(print (string-downcase \"ΑΣ\"))")).isEqualTo("\"ασ\"");
		// Folds that WIDEN the UTF-8 encoding from 2 to 3 bytes: the output buffer is
		// not sized from the input byte count alone.
		assertThat(compileAndRun("(print (string-upcase \"ɐɐɐ\"))")).isEqualTo("\"ⱯⱯⱯ\"");
		assertThat(compileAndRun("(print (string-downcase \"ȺȺȺ\"))")).isEqualTo("\"ⱥⱥⱥ\"");
	}

	@Test
	void stringCapitalizeWordConstituentsAreFullUnicode() throws Exception {
		// string-capitalize's word boundary is alphanumericp in the full-Unicode
		// sense (_char_alnum_p's baked range table), so a caseless letter (CJK) or a
		// non-ASCII letter continues the word rather than starting a new one.
		assertThat(compileAndRun("(print (string-capitalize \"aあb 42x\"))")).isEqualTo("\"Aあb 42x\"");
		assertThat(compileAndRun("(print (string-capitalize \"ЗДРАВСТВУЙ мир\"))")).isEqualTo("\"Здравствуй Мир\"");
		assertThat(compileAndRun("(print (string-capitalize \"ǆenan\"))")).isEqualTo("\"Ǆenan\"");
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
	void reduceOverAnEmptySequence() throws Exception {
		// CL calls the function with ZERO arguments. On WASM a wrong-arity indirect call
		// TRAPS, so #'append's wrapper being binary made this an `unreachable` rather
		// than the nil the JVM already answered -- the shape esrap's parse-error report
		// hits through (reduce #'append all-children).
		assertThat(compileAndRun("(print (reduce #'append '()))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (reduce #'+ '()))")).isEqualTo("0");
		assertThat(compileAndRun("(print (funcall #'append))")).isEqualTo("NIL");
		assertThat(compileAndRun("(print (reduce #'append '((1 2) (3))))")).isEqualTo("(1 2 3)");
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
	void deleteDuplicatesAndFromEnd() throws Exception {
		// delete-duplicates shares remove-duplicates' lowering; :from-end t keeps
		// the FIRST occurrence (sxql's group-by / select-statement ordering).
		assertThat(compileAndRun(
				"(print (delete-duplicates '(1 2 1 3 2))) (print (delete-duplicates '(1 2 1 3 2) :from-end t)) (print (remove-duplicates '(1 2 1 3 2) :from-end t)) (print (funcall #'delete-duplicates '(a b a a c)))"))
			.isEqualTo("(1 3 2)\n(1 2 3)\n(1 2 3)\n(B A C)");
	}

	@Test
	void callNextMethodOverAStructIncludeChain() throws Exception {
		// A method on an :include PARENT struct joins the child's chain, and an
		// :around on t wraps struct-specialized primaries (sxql's yield tree).
		assertThat(compileAndRun("""
				(defstruct cnm-base (name "b"))
				(defstruct (cnm-child (:include cnm-base)))
				(defgeneric cnm-render (x))
				(defmethod cnm-render ((x cnm-base)) (list :base (cnm-base-name x) (next-method-p)))
				(defmethod cnm-render ((x cnm-child)) (cons :child (call-next-method)))
				(defmethod cnm-render :around ((x t)) (cons :around (call-next-method)))
				(defgeneric cnm-kind (x))
				(defmethod cnm-kind ((x structure-object)) :struct)
				(defmethod cnm-kind ((x t)) :other)
				(defstruct cnm-late)
				(print (cnm-render (make-cnm-child)))
				(print (list (cnm-kind (make-cnm-late)) (cnm-kind 42)))
				""")).isEqualTo("(:AROUND :CHILD :BASE \"b\" NIL)\n(:STRUCT :OTHER)");
	}

	@Test
	void slotValueWithAPackageQualifiedRuntimeSlotName() throws Exception {
		// The runtime name arrives in the caller's package spelling while the shared
		// %slot-value-runtime dispatch matches package-stripped slot base names
		// (sxql's compute-select-statement-children).
		assertThat(compileAndRun("""
				(defpackage :rsn-pkg (:use :cl) (:export #:make-holder))
				(in-package :rsn-pkg)
				(defstruct holder (fields-clause 7))
				(in-package :cl-user)
				(let ((h (rsn-pkg:make-holder))
				      (n (car (list 'rsn-pkg::fields-clause))))
				  (setf (slot-value h n) 8)
				  (print (slot-value h n)))
				""")).isEqualTo("8");
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
	void nconcOfAListOntoItself() throws Exception {
		// (nconc s s) builds a circular list: the last argument is left untouched, so the
		// splice must not walk into the cycle it has just created.
		assertThat(compileAndRun("(let ((s (list 1 2 3))) (nconc s s) (print (nth 4 s)) (print (nth 6 s)))"))
			.isEqualTo("2\n1");
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
	void subsetpFunction() throws Exception {
		assertThat(compileAndRun(
				"(print (subsetp '(1 2) '(1 2 3))) (print (subsetp '(1 2) '(2 3 4))) (print (subsetp nil '(1 2))) "
						+ "(print (subsetp '(\"a\" \"z\") '(\"a\" \"b\") :test #'string=)) "
						+ "(print (subsetp '((1 x)) '((1 a) (2 b)) :key #'car)) "
						+ "(print (funcall #'subsetp '(1 2) '(1 2 3)))"))
			.isEqualTo("T\nNIL\nT\nNIL\nT\nT");
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
	void logtest() throws Exception {
		assertThat(compileAndRun(
				"(print (logtest 1 2)) (print (logtest 1 3)) (print (logtest -1 5)) (print (logtest (expt 2 100) (expt 2 100))) (print (funcall #'logtest 1 3))"))
			.isEqualTo("NIL\nT\nT\nT\nT");
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
	void returnInAHandlerBindHandlerExitsTheLexicalNilBlock() throws Exception {
		// rove's SIGNALS shape: the handler's plain (return c) names the (block nil ...)
		// that LEXICALLY encloses it, whatever iteration form -- each of which
		// establishes an implicit nil block of its own -- the SIGNALLING function is
		// running. The lexical answer was already this backend's; the interpreter's
		// dynamic lookup is what moved to match it. (Prelude spliced: type-of is a
		// prelude defun.)
		assertThat(compileAndRunPrelude("""
				(define-condition my-error (error) ())
				(defun raise () (error 'my-error))
				(defun sig-nil (thunk)
				  (block nil
				    (handler-bind ((condition (lambda (c) (return c))))
				      (funcall thunk)
				      nil)))
				(print (type-of (sig-nil (lambda () (raise)))))
				(print (type-of (sig-nil (lambda () (loop :for i :from 1 :to 3 :collect (raise))))))
				(print (type-of (sig-nil (lambda () (dolist (x (list 1 2)) (raise))))))
				(print (type-of (sig-nil (lambda () (dotimes (i 2) (raise))))))
				""")).isEqualTo("MY-ERROR\nMY-ERROR\nMY-ERROR\nMY-ERROR");
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
	void nestedNonLocalExitInACleanupDoesNotLoseTheOuterOne() throws Exception {
		// An unwind-protect cleanup that itself completes a non-local exit runs WHILE the
		// outer exit is still travelling. Here the exit rides the tag's own payload, so
		// the inner one cannot consume the outer's -- the JVM twin of this test pinned a
		// real bug in its single-slot channel, and this one keeps the two backends
		// answering alike.
		assertThat(compileAndRun("""
				(defun run-protected (thunk cleanup)
				  (unwind-protect (funcall thunk) (funcall cleanup)))
				(defun inner-block-exit ()
				  (block in (mapcar (lambda (x) (return-from in x)) '(:inner))))
				(defun catch-throw-cleanup () (catch 'tag (throw 'tag :cleaned)))
				(defun probe (cleanup)
				  (block done
				    (run-protected (lambda () (return-from done :from-inner)) cleanup)))
				(print (probe #'catch-throw-cleanup))
				(print (probe #'inner-block-exit))
				(print (handler-case (probe #'catch-throw-cleanup) (error (e) :swallowed)))
				(print (block done
				         (handler-bind ((error (lambda (e) e)))
				           (run-protected (lambda () (return-from done :past-guard))
				                          #'catch-throw-cleanup))))
				""")).isEqualTo(":FROM-INNER\n:FROM-INNER\n:FROM-INNER\n:PAST-GUARD");
	}

	@Test
	void goInsideLambdaReentersOuterTagbody() throws Exception {
		// A go whose tag belongs to a tagbody in the ENCLOSING function -- the shape a
		// handler-bind handler resuming its loop produces (quri's :lenient
		// percent-decoding). The lowering throws the target label's re-entry index on
		// the block-exit tag; the tagbody's catch re-dispatches into it at that label
		// and carries on. Also covers the backward case (the loop keeps running) and a
		// prog, which establishes the tags AND the nil block a (return) exits.
		// (Was an `unreachable` trap.)
		assertThat(compileAndRun("""
				(define-condition my-err (error) ())
				(defun f (x)
				  (tagbody
				   top
				     (handler-bind ((my-err (lambda (e) (declare (ignore e)) (go done))))
				       (when (> x 0) (error 'my-err))
				       (princ "no-error"))
				   done)
				  :ok)
				(print (f 1))
				(print (f 0))
				(defun countdown (n)
				  (let ((out nil))
				    (tagbody
				     again
				       (setq out (cons n out))
				       (setq n (- n 1))
				       (funcall (lambda () (if (> n 0) (go again)))))
				    (reverse out)))
				(print (countdown 4))
				(defun via-prog (x)
				  (prog ((acc nil))
				     (funcall (lambda () (if (eq x :jump) (go later))))
				     (return (cons :early acc))
				   later
				     (return (cons :later acc))))
				(print (via-prog :plain))
				(print (via-prog :jump))
				""")).isEqualTo(":OK\nno-error:OK\n(4 3 2 1)\n(:EARLY)\n(:LATER)");
	}

	@Test
	void crossLambdaGoRecursiveTargetsCorrectFrame() throws Exception {
		// The thrown id is the per-activation %nlx-tag lexical (an i31 VALUE on wasm),
		// so a recursive function's inner activation cannot catch an outer one's go; an
		// escaped unwind-protect cleanup still runs on the way out.
		assertThat(compileAndRun("""
				(defun rec (n)
				  (let ((hit nil))
				    (tagbody
				     top
				       (if (= n 0) (go fin))
				       (setq hit (rec (- n 1)))
				       (funcall (lambda () (go fin)))
				       (setq hit :never)
				     fin)
				    (list n hit)))
				(print (rec 2))
				(defun cleaned ()
				  (let ((log nil))
				    (tagbody
				     top
				       (unwind-protect (funcall (lambda () (go out)))
				         (setq log (cons :cleanup log)))
				       (setq log (cons :never log))
				     out
				       (setq log (cons :out log)))
				    (reverse log)))
				(print (cleaned))
				""")).isEqualTo("(2 (1 (0 NIL)))\n(:CLEANUP :OUT)");
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
	void sequenceAndSetExtensions() throws Exception {
		// tree-equal / count-if-not / set-exclusive-or / merge are prelude defuns, so the
		// splice mirrors the CLI pipeline like substAndSimpleStringP above.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(print (tree-equal '(1 (2 3)) '(1 (2 3))))
				(print (tree-equal '(1 (2 3)) '(1 (2 4))))
				(print (tree-equal '(1 (2)) '(1 2)))
				(print (tree-equal '("a" ("b")) '("A" ("B")) :test #'string-equal))
				(print (tree-equal '(1 2) '(1 2) :test-not #'eql))
				(print (count-if-not #'evenp '(1 2 3 4 5)))
				(print (count-if-not #'evenp #(1 2 3 4 5)))
				(print (count-if-not #'alpha-char-p "ab1c2"))
				(print (count-if-not #'evenp '(1 2 3 4 5) :start 1 :end 4))
				(print (count-if-not #'oddp '((1) (2) (3)) :key #'car))
				(print (set-exclusive-or '(1 2 3) '(2 3 4)))
				(print (set-exclusive-or '("a" "b") '("B" "c") :test #'string-equal))
				(print (set-exclusive-or '((1 a) (2 b)) '((2 x)) :key #'car))
				(print (merge 'list (list 1 3 5) (list 2 4 6) #'<))
				(print (merge 'vector (vector 1 3) (vector 2 4) #'<))
				(print (merge 'string "ac" "bd" #'char<))
				(print (merge 'list (list '(1 a)) (list '(1 b) '(2 c)) #'< :key #'car))
				(print (funcall #'tree-equal '(1 2) '(1 2)))
				""")))).isEqualTo("""
				T
				NIL
				NIL
				T
				NIL
				3
				3
				2
				1
				1
				(1 4)
				("a" "c")
				((1 A))
				(1 2 3 4 5 6)
				#(1 2 3 4)
				"abcd"
				((1 A) (1 B) (2 C))
				T""");
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
	void ctypecaseForm() throws Exception {
		assertThat(compileAndRun(
				"(print (ctypecase 42 (string \"s\") (integer \"i\"))) (print (ctypecase \"x\" (string \"s\") (integer \"i\")))"))
			.isEqualTo("\"i\"\n\"s\"");
		compileAndExpectTrap("(print (ctypecase 'sym (string \"s\") (integer \"i\")))");
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
	void symbolMacroletForms() throws Exception {
		// Substitution, let-shadowing, nesting, setq write-through, a lambda-body
		// reference at top level and the same inside a defun (the capture of n is
		// visible only THROUGH the substitution -- an unboxed local has no cell for the
		// closure to load), and the dbi shape (setf through a slot-value expansion).
		assertThat(compileAndRun("""
				(symbol-macrolet ((x 42)) (print (list (let ((x 1)) x) x)))
				(symbol-macrolet ((x 1)) (symbol-macrolet ((x 2)) (print x)))
				(let ((cell (list 1 2))) (symbol-macrolet ((head (car cell))) (setq head 99) (print cell)))
				(let ((n 10)) (symbol-macrolet ((big (* n n))) (print (funcall (lambda () big)))))
				(defun sm-square () (let ((n 10)) (symbol-macrolet ((big (* n n))) (funcall (lambda () big)))))
				(print (sm-square))
				(defclass conn () ((auto-commit :initform nil)))
				(defvar *c* (make-instance 'conn))
				(symbol-macrolet ((auto-commit (slot-value *c* 'auto-commit)))
				  (setf auto-commit 'on)
				  (print auto-commit))
				""")).isEqualTo("(1 42)\n2\n(99 2)\n100\n100\nON");
	}

	@Test
	void defineSymbolMacroForms() throws Exception {
		// The global sibling of symbol-macrolet, dropped and substituted by
		// UserMacroExpander before the compiler runs: a reference reads the expansion,
		// setq/setf/incf write through it as a place, and a let of the name shadows it.
		List<LispVal> program = am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defvar *buf* (make-array 3 :initial-element 0))
				(define-symbol-macro *slot0* (aref *buf* 0))
				(setf *slot0* 42)
				(setq *slot0* (+ *slot0* 1))
				(defun bump () (incf *slot0*))
				(bump)
				(print (list *slot0* *buf*))
				(print (let ((*slot0* 7)) *slot0*))
				"""));
		assertThat(compileAndRunProgram(program)).isEqualTo("(44 #(44 0 0))\n7");
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
	void sharpLAndCommaDotAndWithHashTableIterator() throws Exception {
		// #L lowers in the reader, ",." is ",@", and with-hash-table-iterator expands to
		// a snapshot alist plus an flet -- all front-end work, so the WASM backend sees
		// only ordinary forms. ldiff/sublis ride the prelude splice.
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(print (mapcar #L(* !1 !1) '(1 2 3)))
				(print (funcall #L(list !2 !3) 'a 'b 'c))
				(let ((xs '(1 2))) (print `(f ,.xs g)))
				(print (ldiff '(1 2 3 4) nil))
				(print (sublis '((a . 1)) '(a b)))
				(let ((h (make-hash-table)))
				  (setf (gethash 'a h) 1)
				  (with-hash-table-iterator (next h)
				    (multiple-value-bind (more k v) (next)
				      (print (list more k v)))))
				"""));
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y",
				path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("(1 4 9)\n(B C)\n(F 1 2 G)\n(1 2 3 4)\n(1 B)\n(T A 1)");
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
	void unwindProtectKeepsTheProtectedFormsValues() throws Exception {
		// A cleanup runs for effect: its values -- and its value COUNT -- are discarded,
		// so the whole form answers the protected form's values, all of them
		// (.kb/multiple-values.md). Cleanup shapes x exit shapes; the same matrix the
		// interpreter and the JVM pin, and the unwind-protect-values ci-spec case adds
		// the --component leg.
		assertThat(compileAndRun("""
				(setq uwp-log nil)
				(defun uwp-zero () (values))
				(defun uwp-one () (values 7))
				(defun uwp-two () (values 7 8))
				(defun uwp-release () (setq uwp-log (cons 'released uwp-log)) (values))
				(defun uwp-compute () (values 1 2 3))
				(defun uwp-nil () (unwind-protect (values 1 2 3) nil))
				(defun uwp-v0 () (unwind-protect (values 1 2 3) (uwp-zero)))
				(defun uwp-v1 () (unwind-protect (values 1 2 3) (uwp-one)))
				(defun uwp-v2 () (unwind-protect (values 1 2 3) (values 7 8)))
				(defun uwp-call () (unwind-protect (uwp-compute) (uwp-release)))
				(defun uwp-nested ()
				  (unwind-protect (values 1 2 3) (unwind-protect (uwp-two) (uwp-zero))))
				(defun uwp-return ()
				  (block b (unwind-protect (return-from b (values 1 2 3)) (uwp-two))))
				(defun uwp-return-call ()
				  (block b (unwind-protect (return-from b (uwp-compute)) (uwp-release))))
				(defun uwp-go ()
				  (let ((r 0))
				    (block b (tagbody (unwind-protect (go done) (uwp-two)) done))
				    (values r 2 3)))
				(defun uwp-signal ()
				  (handler-case (unwind-protect (error "boom") (uwp-release))
				    (error (e) (values 1 2 3))))
				(defun uwp-signal-plain ()
				  (handler-case (unwind-protect (error "boom") (uwp-two)) (error (e) 'caught)))
				(print (multiple-value-list (uwp-nil)))
				(print (multiple-value-list (uwp-v0)))
				(print (multiple-value-list (uwp-v1)))
				(print (multiple-value-list (uwp-v2)))
				(print (multiple-value-list (uwp-call)))
				(print (multiple-value-list (uwp-nested)))
				(print (multiple-value-list (uwp-return)))
				(print (multiple-value-list (uwp-return-call)))
				(print (multiple-value-list (uwp-go)))
				(print (multiple-value-list (uwp-signal)))
				(print (multiple-value-list (uwp-signal-plain)))
				(print (multiple-value-list (unwind-protect (values 1 2 3) (uwp-zero))))
				(print uwp-log)
				""")).isEqualTo("""
				(1 2 3)
				(1 2 3)
				(1 2 3)
				(1 2 3)
				(1 2 3)
				(1 2 3)
				(1 2 3)
				(1 2 3)
				(0 2 3)
				(1 2 3)
				(CAUGHT)
				(1 2 3)
				(RELEASED RELEASED RELEASED)""");
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
	void syntacticMvProducerTailPublishesThroughAFunctionReturn() throws Exception {
		// A syntactic producer (gethash / floor family / find-symbol / intern /
		// array-displacement) in a defun's tail publishes through %mv-spill, so the
		// secondary value survives the function return -- including a defmethod, the
		// shape that found this (cl-mustache's context-get IS a gethash). Shared
		// verbatim with LispEvaluatorTest / JvmLispCompilerTest and the
		// mv-producer-function-return ci-spec case.
		assertThat(compileAndRun("""
				(defun f-gethash (h) (gethash "K" h))
				(defun f-floor (a b) (floor a b))
				(defun f-disp (a) (array-displacement a))
				(defmethod ctx-get ((key string) (context hash-table))
				  (gethash (string-upcase key) context))
				(defun f-cond (h k) (cond (k (gethash "K" h)) (t nil)))
				(defun my-user-fn (x) x)
				(defun f-find (n) (find-symbol n))
				(defun f-intern (n) (intern n))
				(defun f-nontail (h) (let ((v (gethash "K" h))) v))
				(setq mv427-tbl (make-hash-table :test 'equal))
				(setf (gethash "K" mv427-tbl) "V")
				(print (multiple-value-list (f-gethash mv427-tbl)))
				(print (multiple-value-list (f-gethash (make-hash-table))))
				(print (multiple-value-list (f-floor 7 2)))
				(print (multiple-value-list (f-find "MY-USER-FN")))
				(print (multiple-value-list (f-intern "MY-USER-FN")))
				(print (multiple-value-list (f-disp (make-array 3))))
				(print (multiple-value-list (ctx-get "k" mv427-tbl)))
				(print (multiple-value-list (f-cond mv427-tbl t)))
				(print (multiple-value-list (f-cond mv427-tbl nil)))
				(multiple-value-bind (v f) (ctx-get "k" mv427-tbl) (print (list v f)))
				(print (multiple-value-list (f-nontail mv427-tbl)))
				""")).isEqualTo("(\"V\" T)\n(NIL NIL)\n(3 1)\n(MY-USER-FN :INTERNAL)\n(MY-USER-FN :INTERNAL)\n(NIL 0)\n"
				+ "(\"V\" T)\n(\"V\" T)\n(NIL)\n(\"V\" T)\n(\"V\")");
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

	// CLHS 3.2.4.4, the WASM half of JvmLispCompilerTest's pair: a literal object a
	// macro spliced into its expansion is dumped through its own make-load-form method
	// (eval.LoadFormSubstituter substitutes the creation form before either backend
	// sees the object), and make-load-form-saving-slots answers the same creation form
	// the quote compilers dump an instance as. See .kb/make-load-form.md.
	@Test
	void literalObjectDumpedByMakeLoadForm() throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary
			.process(am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
					(defclass box () ((name :initarg :name :accessor box-name) (cache :initarg :cache)))
					(defmethod make-load-form ((b box) &optional env)
					  (declare (ignore env))
					  (list 'make-instance ''box :name (box-name b)))
					(defparameter *box* (make-instance 'box :name "dumped" :cache (make-hash-table)))
					(defmacro splice-box () *box*)
					(princ (box-name (splice-box)))
					(terpri)
					(defstruct pt x y)
					(defmethod make-load-form ((p pt) &optional env)
					  (make-load-form-saving-slots p :environment env))
					(defparameter *p* (make-pt :x 3 :y "four"))
					(defmacro splice-pt () *p*)
					(princ (make-load-form *p*))
					(terpri)
					(princ (list (pt-x (splice-pt)) (pt-y (splice-pt))))
					""")));
		assertThat(compileAndRunProgram(program))
			.isEqualTo("dumped\n(%OBJ-NEW (QUOTE %struct-PT) (QUOTE 3) (QUOTE four))\n(3 four)");
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
				+ " (print (pathnamep #P\"/tmp/x\"))" + " (print (stream-element-type t))"
				+ " (print (input-stream-p t))" + " (print (output-stream-p (make-broadcast-stream)))"
				+ " (print (input-stream-p \"s\"))"))
			.isEqualTo("240\n44\n12.0\n0.0\n42\nNIL\nNIL\nNIL\nT\nCHARACTER\nT\nT\nNIL");
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
		// class-of answers the metaobject view: class-name (a prelude defun, so the
		// program goes through the prelude pre-pass like the CLI pipeline) reads the
		// name of the memoized standard-class instance, eq to what find-class yields.
		// The internal %class-designator keeps the pre-migration tag/type-name view.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary.process(
				LispReader.readAllFromString("(print (class-name (class-of 42))) (print (class-name (class-of \"s\")))"
						+ " (print (class-name (class-of 'foo))) (print (class-name (class-of :k)))"
						+ " (print (class-name (class-of 1.5))) (print (class-name (class-of (cons 1 2))))"
						+ " (print (class-name (class-of nil))) (print (class-name (class-of t)))"
						+ " (print (class-name (class-of (make-hash-table)))) (print (class-name (class-of #'car)))"
						+ " (defclass co-pt () ((x :initarg :x))) (defstruct co-node value)"
						+ " (print (list (eq (class-of (make-instance 'co-pt :x 1)) (find-class 'co-pt))"
						+ "              (eq (class-of (make-co-node :value 1)) (find-class 'co-node))"
						+ "              (eq (class-of 42) (find-class 'integer))"
						+ "              (class-name (class-of (make-instance 'co-pt :x 1)))))"
						+ " (print (list (%class-designator (make-instance 'co-pt :x 1)) (%class-designator 42)))"
						+ " (print (%class-slot-defs (class-of (make-instance 'co-pt :x 1))))"))))
			.isEqualTo("INTEGER\nSTRING\nSYMBOL\nKEYWORD\nFLOAT\nCONS\nNULL\nBOOLEAN\nHASH-TABLE\nFUNCTION\n"
					+ "(T T T CO-PT)\n(%class-CO-PT INTEGER)\n((X T))");
		// Real unboundness: the supplied slot is bound, an unknown one is
		// not, and slot-makunbound puts it back to unbound.
		assertThat(compileAndRun(
				"(defclass sb-pt () ((x :initarg :x) (y :initarg :y)))" + " (let ((p (make-instance 'sb-pt :x 1 :y 2)))"
						+ " (print (slot-boundp p 'x)) (print (slot-boundp p 'sb-absent))"
						+ " (slot-makunbound p 'x) (print (slot-boundp p 'x)))"))
			.isEqualTo("T\nNIL\nNIL");
		// slot-exists-p: an unbound slot EXISTS, an undeclared one does not, a
		// non-instance answers nil, and a RUNTIME name rides the shared
		// %slot-exists-p-runtime dispatch (mito's col-type probe).
		assertThat(compileAndRun(
				"(defclass se-box () ((a :initarg :a) (b :initform 7)))" + " (let ((o (make-instance 'se-box)))"
						+ " (print (list (slot-exists-p o 'a) (slot-exists-p o 'b) (slot-exists-p o 'zz)"
						+ " (slot-exists-p 42 'a) (slot-exists-p o (car (list 'a))))))"))
			.isEqualTo("(T T NIL NIL T)");
		// A slot NO class declares is a run-time error, never a compile-time one: the
		// eagerly expanding compile path must not fail the build over a read that may
		// never execute (fast-io's open-stream-p reads a typo'd slot name).
		assertThat(compileAndRun("(defclass ms-box () ((a :initform 1)))"
				+ " (print (handler-case (slot-value (make-instance 'ms-box) 'nope)"
				+ " (error (e) (princ-to-string e))))" + " (print (slot-value (make-instance 'ms-box) 'a))"))
			.isEqualTo("\"The slot NOPE is missing\"\n1");
	}

	@Test
	void typepAndSubtypepAcceptClassMetaobjectsAsTypeSpecifiers() throws Exception {
		// The WASM twin of
		// JvmLispCompilerTest#compileTypepAndSubtypepAcceptClassMetaobjectsAsTypeSpecifiers:
		// a class metaobject standing where a type specifier is expected designates its
		// own class, and `class' is the metaobject predicate.
		assertThat(compileAndRun("""
				(defclass mo-super () ())
				(defclass mo-sub (mo-super) ())
				(print (list (subtypep (find-class 'mo-sub) (find-class 'mo-super))
				             (subtypep (find-class 'mo-super) (find-class 'mo-sub))
				             (subtypep (find-class 'mo-sub) 'mo-super)
				             (subtypep 'mo-sub (find-class 'mo-super))
				             (typep (find-class 'mo-sub) 'class)
				             (typep (find-class 'mo-sub) 'standard-class)
				             (typep 42 'class)
				             (typep (make-instance 'mo-sub) (find-class 'mo-super))
				             (typep (make-instance 'mo-super) (find-class 'mo-sub))
				             (typep 42 (find-class 'integer))
				             (typep 42 (find-class 't))
				             (typep (make-instance 'mo-sub) (find-class 't))))
				""")).isEqualTo("(T NIL T T T T NIL T NIL T T T)");
	}

	@Test
	void setfSlotValueWithARuntimeSlotName() throws Exception {
		// Mirrors JvmLispCompilerTest#compileSetfSlotValueWithARuntimeSlotName.
		assertThat(compileAndRun("""
				(defclass rs-pt () ((x :initarg :x :initform 0) (y :initform 1)))
				(let ((p (make-instance 'rs-pt))
				      (n 'y))
				  (print (list (setf (slot-value p n) 42) (slot-value p 'y) (slot-value p n))))
				""")).isEqualTo("(42 42 42)");
	}

	@Test
	void makeInstanceAsAFirstClassFunction() throws Exception {
		// (apply #'make-instance class initargs) with a runtime class, mirroring
		// JvmLispCompilerTest#compileMakeInstanceAsAFirstClassFunction.
		assertThat(compileAndRun("""
				(defclass fv-pt () ((x :initarg :x :initform 0)))
				(defclass fv-line () ((n :initarg :n)))
				(let ((a (apply #'make-instance (list (find-class 'fv-pt) :x 7)))
				      (b (funcall #'make-instance 'fv-line :n 3)))
				  (print (list (slot-value a 'x) (slot-value b 'n) (typep a 'fv-pt) (typep b 'fv-line))))
				""")).isEqualTo("(7 3 T T)");
	}

	@Test
	void runtimeClassDesignatorResolvesAnInternedNonExportedName() throws Exception {
		// The WASM twin of
		// JvmLispCompilerTest#compileRuntimeClassDesignatorResolvesAnInternedNonExportedName:
		// a runtime-interned class name carries the single-colon external spelling on
		// both compile paths, and every designator dispatch answers to it.
		assertThat(compileAndRun("""
				(defpackage :rcd-pkg (:use :cl))
				(in-package :rcd-pkg)
				(defclass spec-rep () ((s :initarg :s)))
				(defclass sub-rep (spec-rep) ())
				(define-condition rcd-err (error) ((k :initarg :k)))
				(defun make-rep (style package)
				  (make-instance (intern (format nil "~A-~A" style '#:rep) package) :s 7))
				(in-package :cl-user)
				(let ((n (intern "SPEC-REP" :rcd-pkg)))
				  (print (slot-value (rcd-pkg::make-rep '#:spec (find-package :rcd-pkg)) 's))
				  (print (eq (find-class n) (find-class 'rcd-pkg::spec-rep)))
				  (print (typep (make-instance n :s 1) n))
				  (print (subtypep (intern "SUB-REP" :rcd-pkg) n))
				  (print (handler-case (error (intern "RCD-ERR" :rcd-pkg) :k 5)
				           (rcd-pkg::rcd-err (c) (slot-value c 'k)))))
				""")).isEqualTo("7\nT\nT\nT\n5");
	}

	@Test
	void typeOfAnswersAForeignPackageClassNameUnqualifiedByTheCurrentPackage() throws Exception {
		// The WASM twin of
		// JvmLispCompilerTest#compileTypeOfAnswersAForeignPackageClassNameUnqualifiedByTheCurrentPackage:
		// type-of interns the already-qualified remainder of the class tag, and a
		// package-blind intern keeps that spelling -- what the interpreter was taught to
		// match. type-of and class-name are prelude defuns, so the program needs the
		// CLI pipeline's prelude splice.
		assertThat(compileAndRunPrelude("""
				(defpackage :tof-lib (:use :cl) (:export :widget :make-w))
				(in-package :tof-lib)
				(defclass widget () ())
				(defclass gadget () ())
				(defstruct point x y)
				(defun make-w () (make-instance 'widget))
				(defun make-g () (make-instance 'gadget))
				(defpackage :tof-app (:use :cl))
				(in-package :tof-app)
				(print (list (type-of (tof-lib:make-w))
				             (type-of (tof-lib::make-g))
				             (type-of (tof-lib::make-point :x 1 :y 2))))
				(print (list (eq (type-of (tof-lib:make-w)) 'tof-lib:widget)
				             (eq (type-of (tof-lib::make-g)) 'tof-lib::gadget)
				             (eq (type-of (tof-lib::make-point :x 1 :y 2)) 'tof-lib::point)
				             (eq (type-of (tof-lib:make-w)) (class-name (class-of (tof-lib:make-w))))
				             (type-of 42)))
				""")).isEqualTo("(TOF-LIB:WIDGET TOF-LIB::GADGET TOF-LIB::POINT)\n(T T T T INTEGER)");
	}

	@Test
	void typeOfAndTypepAnswerTheCompoundArraySpecifier() throws Exception {
		// The WASM twin of
		// JvmLispCompilerTest#compileTypeOfAndTypepAnswerTheCompoundArraySpecifier:
		// type-of is a prelude defun, so the program needs the CLI pipeline's splice.
		assertThat(compileAndRunPrelude("""
				(print (list (type-of (make-array 4))
				             (type-of (make-array '(2 3)))
				             (type-of (make-array nil))
				             (type-of (make-array 4 :element-type 'single-float))
				             (type-of (make-array '(2 2) :element-type 'double-float))
				             (type-of (make-array 4 :element-type '(unsigned-byte 8)))
				             (type-of (make-array 4 :fill-pointer 0))
				             (type-of (make-array 4 :adjustable t))
				             (type-of "abc")))
				(print (list (typep (make-array '(2 2) :element-type 'single-float) '(simple-array single-float (2 2)))
				             (typep (make-array '(2 2) :element-type 'single-float) '(simple-array single-float (2 3)))
				             (typep (make-array 4) '(simple-vector 4))
				             (typep (make-array 4) '(simple-vector 3))
				             (typep (make-array nil) '(simple-array t nil))
				             (typep (make-array '(2 2)) '(simple-vector 4))
				             (typep (make-array 4) '(simple-array t 1))
				             (typep "abc" '(array * (3)))
				             (typep "abc" '(simple-array t (3)))
				             (typep (make-array 4 :element-type '(unsigned-byte 8)) '(vector (unsigned-byte 8) 4))
				             (typep (make-array 4) '(vector (unsigned-byte 8)))))
				(print (eq (array-element-type (make-array 4)) t))
				""")).isEqualTo("((SIMPLE-VECTOR 4) (SIMPLE-ARRAY T (2 3)) (SIMPLE-ARRAY T NIL)"
				+ " (SIMPLE-ARRAY SINGLE-FLOAT (4)) (SIMPLE-ARRAY DOUBLE-FLOAT (2 2))"
				+ " (SIMPLE-ARRAY (UNSIGNED-BYTE 8) (4)) (VECTOR T 4) (VECTOR T 4) STRING)"
				+ "\n(T NIL T NIL T NIL T T NIL T NIL)\nT");
	}

	@Test
	void computedCompoundTypeSpecifiers() throws Exception {
		// The WASM twin of JvmLispCompilerTest#compileComputedCompoundTypeSpecifiers:
		// type-of is a prelude defun, so the program needs the CLI pipeline's splice.
		assertThat(compileAndRunPrelude("""
				(defstruct tpc-pt x y)
				(defun tpc (v s) (typep v s))
				(print
				  (list (list (let ((a (make-array 4))) (tpc a (type-of a)))
				              (let ((a (make-array '(2 3)))) (tpc a (type-of a)))
				              (let ((a (make-array nil))) (tpc a (type-of a)))
				              (let ((a (make-array 4 :element-type 'double-float))) (tpc a (type-of a)))
				              (let ((a (make-array 4 :element-type '(unsigned-byte 8)))) (tpc a (type-of a)))
				              (let ((a (make-array 4 :fill-pointer 0))) (tpc a (type-of a)))
				              (tpc "abc" (type-of "abc"))
				              (tpc 42 (type-of 42))
				              (tpc 5 '(integer 0 10))
				              (tpc 50 '(integer 0 10))
				              (tpc 6 '(integer (5) 10))
				              (tpc 5 '(integer (5) 10))
				              (tpc 3.0d0 '(double-float 0.0d0 10.0d0))
				              (tpc 200 '(unsigned-byte 8))
				              (tpc 300 '(unsigned-byte 8))
				              (tpc -5 '(signed-byte 8))
				              (tpc -500 '(signed-byte 8)))
				        (list (tpc "x" '(or (integer 0 10) string))
				              (tpc 'sym '(or (integer 0 10) string))
				              (tpc 3 '(and integer (satisfies oddp)))
				              (tpc 4 '(and integer (satisfies oddp)))
				              (tpc 4 '(not (integer 0 3)))
				              (tpc 2 '(member 1 2 3))
				              (tpc 9 '(member 1 2 3))
				              (tpc 3 '(eql 3))
				              (tpc '(1 2) '(cons integer))
				              (tpc "ab" '(string 2))
				              (tpc "ab" '(string 3))
				              (tpc (make-tpc-pt) '(or string tpc-pt))
				              (tpc (make-tpc-pt) '(or string integer)))
				        (list (tpc (make-array 4) '(simple-vector 4))
				              (tpc (make-array 4) '(simple-vector 5))
				              (tpc (make-array '(2 2)) '(simple-vector 4))
				              (tpc (make-array '(2 2)) '(simple-array t (2 2)))
				              (tpc (make-array '(2 2)) '(simple-array t 2))
				              (tpc (make-array '(2 2)) '(array))
				              (tpc "abc" '(vector character))
				              (tpc (make-array 4) '(vector (unsigned-byte 8))))))
				""")).isEqualTo("((T T T T T T T T T NIL T NIL T T NIL T NIL)"
				+ " (T NIL T NIL T T NIL T T T NIL T NIL) (T NIL NIL T T T T NIL))");
	}

	@Test
	void computedCompoundSubtypepSpecifiers() throws Exception {
		// The WASM twin of JvmLispCompilerTest#compileComputedCompoundSubtypepSpecifiers:
		// type-of is a prelude defun, so the program needs the CLI pipeline's splice.
		assertThat(compileAndRunPrelude("""
				(defstruct stc-pt x y)
				(deftype stc-num () '(or integer float))
				(defun stp (a b) (subtypep a b))
				(print
				  (list (list (stp '(or fixnum ratio) 'number)
				              (stp '(integer 0 10) 'integer)
				              (stp '(integer 0 10) 'number)
				              (stp 'integer '(integer 0 10))
				              (stp '(unsigned-byte 8) 'integer)
				              (stp '(and integer ratio) 'number)
				              (stp 'fixnum '(and integer real))
				              (stp 'fixnum '(and integer string))
				              (stp '(vector t 3) '(or array hash-table))
				              (stp 'nil '(integer 0 10))
				              (stp '(integer 0 10) t))
				        (list (stp '(simple-array t (2 2)) 'array)
				              (stp '(simple-vector 4) 'vector)
				              (stp '(simple-vector 4) 'sequence)
				              (stp '(string 2) 'string)
				              (stp (type-of (make-array 4)) 'vector)
				              (stp (type-of (make-array '(2 3))) 'array)
				              (stp (type-of "abc") 'sequence)
				              (stp (type-of (make-hash-table)) 'hash-table)
				              (stp (type-of (make-stc-pt)) 'structure-object)
				              (stp (type-of (make-array 4)) (type-of (make-array 4))))
				        (list (stp 'stc-num 'number)
				              (stp 'fixnum '(and))
				              (stp '(not integer) 'number)
				              (stp '(satisfies oddp) 'number)
				              (stp '(and) 'number)
				              (stp '(member 1 2) 'number)
				              (stp '(eql 1) 'number))))
				""")).isEqualTo("((T T T NIL T T T NIL T T T) (T T T T T T T T T T) (T T NIL NIL NIL NIL NIL))");
	}

	@Test
	void simpleTypeNameSubtypepLattice() throws Exception {
		// The WASM twin of JvmLispCompilerTest#compileSimpleTypeNameSubtypepLattice.
		assertThat(compileAndRun("""
				(defun sts (a b) (subtypep a b))
				(print
				  (list (list (subtypep 'simple-vector 'vector)
				              (subtypep 'vector 'simple-vector)
				              (subtypep 'simple-array 'array)
				              (subtypep 'array 'simple-array)
				              (subtypep 'simple-string 'string)
				              (subtypep 'string 'simple-string))
				        (list (subtypep 'simple-vector 'simple-array)
				              (subtypep 'simple-string 'simple-array)
				              (subtypep 'simple-vector 'sequence)
				              (subtypep 'simple-array 'sequence)
				              (subtypep 'string 'simple-vector)
				              (subtypep 'simple-string 'simple-vector))
				        (list (subtypep 'simple-base-string 'simple-string)
				              (subtypep 'simple-string 'simple-base-string)
				              (subtypep 'base-string 'string)
				              (subtypep 'string 'base-string))
				        (list (sts 'simple-vector 'vector)
				              (sts 'vector 'simple-vector)
				              (sts 'simple-string 'string)
				              (sts 'string 'simple-string)
				              (sts '(simple-vector 4) 'vector)
				              (sts 'vector '(simple-vector 4)))))
				""")).isEqualTo("((T NIL T NIL T NIL) (T T T NIL NIL NIL) (T T T T) (T NIL T NIL T NIL))");
	}

	@Test
	void simpleTypeNameTypepChecksSimplicity() throws Exception {
		// The WASM twin of
		// JvmLispCompilerTest#compileSimpleTypeNameTypepChecksSimplicity. type-of is a
		// prelude defun, so the program takes the CLI pipeline's prelude splice.
		assertThat(compileAndRunPrelude("""
				(defvar *tps-sv* (make-array 4))
				(defvar *tps-fp* (make-array 4 :fill-pointer 0))
				(defvar *tps-ad* (make-array 4 :adjustable t))
				(defvar *tps-dp* (make-array 2 :displaced-to *tps-sv*))
				(defvar *tps-pk* (make-array 4 :element-type 'single-float))
				(defvar *tps-m2* (make-array '(2 3)))
				(defvar *tps-st* "abc")
				(defvar *tps-cs* (make-array 3 :element-type 'character))
				(defvar *tps-cv* (make-array 4 :element-type 'character :fill-pointer 0))
				(defun tps (v s) (typep v s))
				(print
				 (list
				  (list (typep *tps-sv* 'simple-vector) (typep *tps-fp* 'simple-vector)
				        (typep *tps-ad* 'simple-vector) (typep *tps-dp* 'simple-vector)
				        (typep *tps-pk* 'simple-vector) (typep *tps-st* 'simple-vector))
				  (list (typep *tps-sv* 'simple-array) (typep *tps-fp* 'simple-array)
				        (typep *tps-dp* 'simple-array) (typep *tps-pk* 'simple-array)
				        (typep *tps-m2* 'simple-array) (typep *tps-st* 'simple-array))
				  (list (typep *tps-st* 'simple-string) (typep *tps-cs* 'simple-string)
				        (typep *tps-cv* 'simple-string) (typep *tps-cv* 'string)
				        (typep *tps-sv* 'simple-string))
				  (list (typep *tps-fp* '(simple-vector 4)) (typep *tps-fp* '(simple-array t (4)))
				        (typep *tps-fp* '(vector t 4)) (typep *tps-fp* '(array t (4))))
				  (list (type-of *tps-fp*) (type-of *tps-ad*) (type-of *tps-dp*) (type-of *tps-sv*))
				  (list (typep *tps-sv* (type-of *tps-sv*)) (typep *tps-fp* (type-of *tps-fp*))
				        (typep *tps-dp* (type-of *tps-dp*)) (typep *tps-pk* (type-of *tps-pk*))
				        (typep *tps-m2* (type-of *tps-m2*)))
				  (list (tps *tps-sv* 'simple-vector) (tps *tps-fp* 'simple-vector)
				        (tps *tps-st* 'simple-string) (tps *tps-cv* 'simple-string)
				        (tps *tps-fp* '(simple-vector 4)) (tps *tps-sv* '(simple-vector 4)))
				  (list (simple-string-p *tps-st*) (simple-string-p *tps-cs*)
				        (simple-string-p *tps-cv*)
				        (simple-string-p (coerce *tps-cv* 'simple-string))
				        (simple-string-p (coerce *tps-cv* 'string))
				        (let ((ty 'simple-string))
				          (simple-string-p (coerce *tps-cv* ty))))
				  (list (typep *tps-cv* '(string 4)) (typep *tps-cv* '(string 0))
				        (typep *tps-cv* '(simple-string 4)) (typep *tps-cv* '(vector character 4))
				        (typep *tps-cv* '(simple-array character (4)))
				        (typep *tps-cv* '(array character (4)))
				        (typep *tps-st* '(string 3)) (typep *tps-cs* '(simple-string 3)))
				  (list (tps *tps-cv* '(string 4)) (tps *tps-cv* '(string 0))
				        (tps *tps-cv* '(vector character 4)) (tps *tps-cv* '(array character (4)))
				        (tps *tps-st* '(string 3)) (tps *tps-sv* '(string 4)))))
				""")).isEqualTo(
				"((T NIL NIL NIL NIL NIL) (T NIL NIL T T T) (T T NIL T NIL) (NIL NIL T T) ((VECTOR T 4) (VECTOR T 4) (VECTOR T 2) (SIMPLE-VECTOR 4)) (T T T T T) (T NIL T NIL NIL T) (T T NIL T NIL T) (T NIL NIL T NIL T T T) (T NIL T T T NIL))");
	}

	@Test
	void defclassMetaclassRunsTheClassDefinitionProtocol() throws Exception {
		// The postmodern dao-class shape on the WASM path, mirroring
		// JvmLispCompilerTest#compileDefclassMetaclassRunsTheClassDefinitionProtocol.
		assertThat(compileAndRun("""
				(defvar *direct-column-slot* nil)
				(defclass mc-dao-class (standard-class)
				  ((direct-keys :initarg :keys :initform nil :accessor mc-direct-keys)
				   (table-name)))
				(defclass mc-direct-column-slot (closer-mop:standard-direct-slot-definition)
				  ((col-type :initarg :col-type :accessor mc-column-type)))
				(defclass mc-effective-column-slot (closer-mop:standard-effective-slot-definition)
				  ((direct-slot :initform *direct-column-slot* :reader mc-slot-column)))
				(defmethod closer-mop:validate-superclass ((class mc-dao-class) (super standard-class)) t)
				(defmethod shared-initialize :before ((class mc-dao-class) slot-names
				                                      &key table-name &allow-other-keys)
				  (if table-name
				      (setf (slot-value class 'table-name) (car table-name))
				      (slot-makunbound class 'table-name)))
				(defmethod closer-mop:direct-slot-definition-class ((class mc-dao-class) &rest initargs
				                                                    &key col-type &allow-other-keys)
				  (if col-type (find-class 'mc-direct-column-slot) (call-next-method)))
				(defmethod closer-mop:compute-effective-slot-definition ((class mc-dao-class) name dsds)
				  (let ((*direct-column-slot* (find-if (lambda (s) (typep s 'mc-direct-column-slot)) dsds)))
				    (call-next-method)))
				(defmethod closer-mop:effective-slot-definition-class ((class mc-dao-class) &rest initargs)
				  (if *direct-column-slot* (find-class 'mc-effective-column-slot) (call-next-method)))
				(defvar *mc-finalized* nil)
				(defmethod closer-mop:finalize-inheritance :after ((class mc-dao-class))
				  (setq *mc-finalized* (cons (%obj-ref class 0) *mc-finalized*)))
				(defclass mc-user ()
				  ((id :col-type integer :initarg :id :accessor mc-user-id)
				   (note :initarg :note :initform "n/a"))
				  (:metaclass mc-dao-class)
				  (:table-name "users")
				  (:keys id))
				(print (list (let ((c (find-class 'mc-user)))
				               (list (%obj-ref c 0)
				                     (typep c 'mc-dao-class)
				                     (eq (class-of c) (find-class 'mc-dao-class))
				                     (slot-value c 'table-name)
				                     (mc-direct-keys c)
				                     *mc-finalized*
				                     (%obj-ref c 4)))
				             (let ((u (make-instance 'mc-user :id 7)))
				               (list (mc-user-id u) (slot-value u 'note) (eq (class-of u) (find-class 'mc-user))))
				             (mapcar (lambda (s)
				                       (list (%obj-ref s 0)
				                             (if (typep s 'mc-effective-column-slot)
				                                 (mc-column-type (mc-slot-column s))
				                                 :plain)))
				                     (%obj-ref (find-class 'mc-user) 3))))
				"""))
			.isEqualTo("((MC-USER T T \"users\" (ID) (MC-USER) T) (7 \"n/a\" T) ((ID INTEGER) (NOTE :PLAIN)))");
	}

	@Test
	void defclassMetaclassSharedInitializeBeforeRunsBeforeInitargFilling() throws Exception {
		// Mirrors
		// JvmLispCompilerTest#compileDefclassMetaclassSharedInitializeBeforeRunsBeforeInitargFilling
		// on the WASM path.
		assertThat(compileAndRun("""
				(defclass mcb-meta (standard-class)
				  ((ks :initarg :keys :initform nil :reader mcb-ks)
				   (table-name)))
				(defmethod shared-initialize :before ((c mcb-meta) slot-names
				                                      &key table-name &allow-other-keys)
				  (setf (slot-value c 'ks) nil)
				  (if table-name
				      (setf (slot-value c 'table-name) (car table-name))
				      (slot-makunbound c 'table-name)))
				(defclass mcb-user () ((id)) (:metaclass mcb-meta) (:keys id) (:table-name "users"))
				(print (let ((c (find-class 'mcb-user))
				             (m (apply #'make-instance (list 'mcb-meta :name 'raw :keys '(k)))))
				         (list (mcb-ks c) (slot-value c 'table-name) (mcb-ks m))))
				""")).isEqualTo("((ID) \"users\" (K))");
	}

	@Test
	void defclassMetaclassEnsureClassUsingClassAndInitargMunging() throws Exception {
		// Mirrors
		// JvmLispCompilerTest#compileDefclassMetaclassEnsureClassUsingClassAndInitargMunging
		// on the WASM path (the mito shape).
		assertThat(compileAndRun(am.ik.rontolisp.MopWideningFixture.MITO_SHAPE_SOURCE + """
				(print (list *mt-first*
				             (let ((c (find-class 'mt-user)))
				               (list *mt-ecuc*
				                     (slot-value c 'table-name)
				                     (mapcar (lambda (s) (%obj-ref s 0)) (%obj-ref c 2))
				                     (slot-value c 'col-count)
				                     (mt-user-id (make-instance 'mt-user :id 1))))))
				""")).isEqualTo(am.ik.rontolisp.MopWideningFixture.MITO_SHAPE_EXPECTED);
	}

	@Test
	void compileInterceptsDefinitionTimeMethodConstruction() throws Exception {
		// The build-dao-methods idiom on the WASM path, mirroring
		// JvmLispCompilerTest#compileInterceptsDefinitionTimeMethodConstruction: the
		// UserMacroExpander pass intercepts the (compile nil `(lambda () ,code)) inside
		// the finalize-inheritance :after hook and splices the folded method
		// definitions; the generated compile runtime answers the run-time re-execution
		// with a no-op.
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary
			.process(am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
					(defclass ce-tbl-class (standard-class)
					  ((table-name :initform nil)))
					(defmethod shared-initialize :before ((class ce-tbl-class) slot-names
					                                      &key table-name &allow-other-keys)
					  (if table-name (setf (slot-value class 'table-name) (car table-name)) nil))
					(defgeneric ce-row-tag (obj))
					(defgeneric ce-fetch-row (type key))
					(defun ce-eval (code)
					  (funcall (compile nil (list 'lambda nil code))))
					(defun ce-build-methods (class)
					  (ce-eval
					   `(let* ((tname (slot-value ,class 'table-name)))
					      (labels ((prefix (s) (concatenate 'string tname ":" s)))
					        (defmethod ce-row-tag ((object ,class))
					          (prefix "row"))
					        (defmethod ce-fetch-row ((type (eql (class-name ,class))) key)
					          (prefix key))))))
					(defmethod closer-mop:finalize-inheritance :after ((class ce-tbl-class))
					  (ce-build-methods class))
					(defclass ce-user ()
					  ((id :initarg :id))
					  (:metaclass ce-tbl-class)
					  (:table-name "users"))
					(print (list (ce-row-tag (make-instance 'ce-user :id 1))
					             (ce-fetch-row 'ce-user "k7")))
					""")));
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "--wasm", "gc", path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("(\"users:row\" \"users:k7\")");
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
				(defclass wsu-box () ((buffer)))
				(defmethod initialize-instance ((self wsu-box) &key)
				  (call-next-method)
				  (with-slots (buffer) self (setf buffer (list 1 2))))
				(print (slot-value (make-instance 'wsu-box) 'buffer))
				(defstruct (so-kv (:constructor make-so-pair) (:conc-name so-get-)
				                  (:predicate so-kv?) (:copier so-clone))
				  key val)
				(let ((k (make-so-pair :key 'a :val 1)))
				  (print (list (so-get-key k) (so-kv? k) (so-kv? 5)))
				  (let ((k2 (so-clone k)))
				    (setf (so-get-val k2) 99)
				    (print (list (so-get-val k) (so-get-val k2)))))
				""")).isEqualTo("(:CHASE :FLEE :IGNORE)\n(:DOG (1 2))\n(\"localhost\" 8080 9090)\n(11 2)\n11\n(1 2)\n"
				+ "(A T NIL)\n(1 99)");
	}

	@Test
	void grayStreamInstanceDispatch() throws Exception {
		// The GrayStreamsLibrary pre-pass splices gray.lisp and rewrites the
		// write-string/write-char call sites onto the dispatch helpers, mirroring the
		// CLI pipeline.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
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
					"""))))).isEqualTo("\"HELLO!\"\nstill-works");
	}

	@Test
	void grayOutputProtocolWidening() throws Exception {
		// the line-oriented and print-family operators reach a Gray
		// instance on the WASM path too -- a class defining ONLY stream-write-char
		// (rove's indent-stream shape) answers all of them, and only the dispatch
		// helpers the rewrites produced are spliced.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
					(defclass gw-col (rontolisp:fundamental-character-output-stream)
					  ((acc :initform "") (col :initform 0)))
					(defmethod rontolisp:stream-write-char ((s gw-col) c)
					  (setf (slot-value s 'acc) (concatenate 'string (slot-value s 'acc) (string c)))
					  (setf (slot-value s 'col) (if (char= c #\\Newline) 0 (+ (slot-value s 'col) 1)))
					  c)
					(defmethod rontolisp:stream-line-column ((s gw-col)) (slot-value s 'col))
					(let ((s (make-instance 'gw-col)))
					  (write-char #\\a s)
					  (write-string "bc" s)
					  (princ "-" s)
					  (prin1 :k s)
					  (fresh-line s)
					  (fresh-line s)
					  (terpri s)
					  (write-line "l" s)
					  (print 7 s)
					  (format s "f~a" 1)
					  (force-output s)
					  (finish-output s)
					  (clear-output s)
					  (print (list (slot-value s 'acc) (close s))))
					(write-line "past" t)
					"""))))).isEqualTo("(\"abc-:K\n\nl\n7\nf1\" T)\npast");
	}

	@Test
	void grayInputStreamPeekUnreadAndStreamQueries() throws Exception {
		// The rest of the input protocol on the WASM path: peek-char (all three
		// peek-type forms, looped inside the dispatch helper -- expandPeekChar runs
		// after the rewrite and could not see the instance), unread-char through the
		// protocol's pushback, read-char-no-hang, open-stream-p and
		// stream-element-type (character, octets for a binary base class, and
		// character again for a BIVALENT class subclassing both). Same answers as
		// the interpreter and the JVM.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
					(defclass gin-source (rontolisp:fundamental-character-input-stream)
					  ((text :initarg :text) (pos :initform 0)))
					(defmethod rontolisp:stream-read-char ((s gin-source))
					  (let ((text (slot-value s 'text)) (pos (slot-value s 'pos)))
					    (if (>= pos (length text))
					        :eof
					        (progn (setf (slot-value s 'pos) (+ pos 1)) (char text pos)))))
					(defclass gin-bytes (rontolisp:fundamental-binary-input-stream) ())
					(defclass gin-both (rontolisp:fundamental-binary-input-stream
					                    rontolisp:fundamental-character-input-stream)
					  ())
					(let ((in (make-instance 'gin-source :text (format nil "ab~%  cd"))))
					  (print (peek-char nil in))
					  (print (read-char in))
					  (print (unread-char #\\a in))
					  (print (read-char in))
					  (print (read-char-no-hang in))
					  (print (read-line in))
					  (print (peek-char t in))
					  (print (peek-char #\\d in))
					  (print (read-line in))
					  (print (peek-char nil in nil :done))
					  (print (open-stream-p in))
					  (print (stream-element-type in))
					  (print (stream-element-type (make-instance 'gin-bytes)))
					  (print (stream-element-type (make-instance 'gin-both))))
					"""))))).isEqualTo(
					"#\\a\n#\\a\nNIL\n#\\a\n#\\b\n\"\"\n#\\c\n#\\d\n\"d\"\n:DONE\nT\nCHARACTER\n(UNSIGNED-BYTE 8)\nCHARACTER");
	}

	@Test
	void grayStreamDirectionPredicates() throws Exception {
		// input-stream-p / output-stream-p on a Gray instance answer the DIRECTION base
		// class (a typep, not a predicate generic per class); a stream HANDLE keeps the
		// bidirectional-lite answer. Same answers as the interpreter and the JVM.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
					(defclass gdp-in (rontolisp:fundamental-character-input-stream) ())
					(defclass gdp-out (rontolisp:fundamental-character-output-stream) ())
					(defmethod rontolisp:stream-read-char ((s gdp-in)) :eof)
					(defmethod rontolisp:stream-write-string ((s gdp-out) str) str)
					(let ((in (make-instance 'gdp-in)) (out (make-instance 'gdp-out))
					      (handle (make-string-input-stream "z")))
					  (print (list (input-stream-p in) (output-stream-p in)))
					  (print (list (input-stream-p out) (output-stream-p out)))
					  (print (list (input-stream-p handle) (output-stream-p handle))))
					"""))))).isEqualTo("(T NIL)\n(NIL T)\n(T T)");
	}

	@Test
	void grayStreamIsAStream() throws Exception {
		// streamp / (typep x 'stream) on a Gray instance: t here as well, from the same
		// instance arm the JVM lowering emits. Same answers as the interpreter.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
					(defclass gsp-out (rontolisp:fundamental-character-output-stream) ())
					(defclass gsp-in (rontolisp:fundamental-character-input-stream) ())
					(defclass gsp-other () ())
					(defmethod rontolisp:stream-write-string ((s gsp-out) str) str)
					(defmethod rontolisp:stream-read-char ((s gsp-in)) :eof)
					(defun gsp-typep (x ty) (typep x ty))
					(let ((out (make-instance 'gsp-out)) (in (make-instance 'gsp-in))
					      (other (make-instance 'gsp-other)))
					  (print (list (streamp out) (streamp in) (streamp other)))
					  (print (list (typep out 'stream) (typep in 'stream) (typep other 'stream)))
					  (print (mapcar #'streamp (list out other 3 t nil)))
					  (print (etypecase out (integer :fd) (stream :lisp-stream)))
					  (print (list (gsp-typep out 'stream) (gsp-typep other 'stream) (gsp-typep 3 'stream))))
					"""))))).isEqualTo("(T T NIL)\n(T T NIL)\n(T NIL NIL T NIL)\n:LISP-STREAM\n(T NIL NIL)");
	}

	@Test
	void unreadCharOnAStreamHandleRoundTrips() throws Exception {
		// The handle-side pushback of unread-char is ordinary Lisp (unread-char.lisp),
		// spliced and wired to the call sites by eval/UnreadCharLibrary -- so read-char,
		// peek-char and read-line drain the same one-slot cell here too, and a second
		// unread with the cell still full signals.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.UnreadCharLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
					(let ((s (make-string-input-stream (format nil "ab~%cd"))))
					  (print (read-char s))
					  (print (unread-char #\\a s))
					  (print (peek-char nil s))
					  (print (read-char s))
					  (print (read-line s))
					  (print (read-line s)))
					(print (handler-case
					           (let ((s (make-string-input-stream "xy")))
					             (read-char s)
					             (unread-char #\\x s)
					             (unread-char #\\x s))
					         (error (e) (princ-to-string e))))
					"""))))).isEqualTo("""
					#\\a
					NIL
					#\\a
					#\\a
					"b"
					"cd"
					"UNREAD-CHAR without an intervening READ-CHAR\"""");
	}

	@Test
	void grayBinaryStreamDispatchAndFilePosition() throws Exception {
		// The read side of the Gray pre-pass: read-byte/write-byte and
		// file-position call sites with a non-literal stream rewrite onto the
		// %gray-*-dispatch helpers; only the helpers a rewrite produced are
		// spliced (an unconditional %gray-listen-dispatch would not compile here).
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.GrayStreamsLibrary
			.process(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
					(defclass gbs-sink (rontolisp:fundamental-binary-output-stream)
					  ((bytes :initform nil)))
					(defmethod rontolisp:stream-write-byte ((s gbs-sink) byte)
					  (setf (slot-value s 'bytes) (cons byte (slot-value s 'bytes)))
					  byte)
					(defclass gbs-source (rontolisp:fundamental-binary-input-stream)
					  ((items :initarg :items) (pos :initform 0)))
					(defmethod rontolisp:stream-read-byte ((s gbs-source))
					  (let ((items (slot-value s 'items)) (pos (slot-value s 'pos)))
					    (if (>= pos (length items))
					        :eof
					        (progn (setf (slot-value s 'pos) (+ pos 1)) (nth pos items)))))
					(defmethod rontolisp:stream-file-position ((s gbs-source)) (slot-value s 'pos))
					(defmethod (setf rontolisp:stream-file-position) (position (s gbs-source))
					  (setf (slot-value s 'pos) position))
					(let ((out (make-instance 'gbs-sink)))
					  (write-byte 7 out)
					  (write-byte 250 out)
					  (print (reverse (slot-value out 'bytes))))
					(let ((in (make-instance 'gbs-source :items (list 10 20 30))))
					  (print (read-byte in))
					  (print (file-position in))
					  (file-position in 0)
					  (print (read-byte in))
					  (print (read-byte in nil :done))
					  (print (read-byte in nil :done))
					  (print (read-byte in nil :done)))
					"""))))).isEqualTo("(7 250)\n10\n1\n10\n20\n30\n:DONE");
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
	void readTimeEvalGeneratedDefconstants() throws Exception {
		// fast-http's multipart-parser idiom: #. generates the state defconstants via
		// a backquoted eval-when whose datum value is CODE, evaluated in place.
		assertThat(compileAndRunProgram(
				am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllWithReadEvalMarkers("""
						#.`(eval-when (:compile-toplevel :load-toplevel :execute)
						     ,@(loop for i from 0
						             for state in '(re-state-alpha re-state-beta re-state-gamma)
						             collect `(defconstant ,(intern (format nil "+~A+" state)) ,i)))
						(print +re-state-beta+)
						(print +re-state-gamma+)
						""", am.ik.rontolisp.reader.Features.WASM))))
			.isEqualTo("1\n2");
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
	void sortOfALargeListIsLinearithmicAndOrdersEqualElementsLikeEveryOtherBackend() throws Exception {
		// The site calls the shared %sort-runtime merge sort (.kb/sort.md): 50,000
		// elements are instant where the selection sort this replaced took minutes, and
		// elements the predicate calls equal come out in input order -- the same
		// permutation the interpreter and the JVM backend answer.
		assertThat(compileAndRun("""
				(let ((data nil) (s 42))
				  (dotimes (i 50000)
				    (setq s (mod (+ (* s 1103515245) 12345) 2147483648))
				    (setq data (cons s data)))
				  (let ((sorted (sort data #'<)) (ordered t) (n 0))
				    (do ((c sorted (cdr c))) ((null (cdr c)) nil)
				      (if (> (car c) (car (cdr c))) (setq ordered nil)))
				    (do ((c sorted (cdr c))) ((null c) nil) (setq n (+ n 1)))
				    (print (list ordered n))))
				(print (sort (list (cons 1 'a) (cons 1 'b) (cons 0 'c) (cons 1 'd) (cons 0 'e))
				             (lambda (x y) (< (car x) (car y)))))
				""")).isEqualTo("(T 50000)\n((0 . C) (0 . E) (1 . A) (1 . B) (1 . D))");
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
	void applyAlignedVariadicTarget() throws Exception {
		// The aligned fast path: a literal #'f target whose required parameters are all
		// covered by the leading arguments takes them directly -- the rest parameter is
		// the tail verbatim (same list object) or the excess consed onto it. Fewer
		// leading arguments than required parameters keeps the build-then-unpack path.
		assertThat(compileAndRun("(defun g (a b &rest r) (list a b r)) (print (apply #'g 1 2 '(3 4)))"))
			.isEqualTo("(1 2 (3 4))");
		assertThat(compileAndRun("(defun g (a b &rest r) (list a b r)) (print (apply #'g 1 2 3 '(4)))"))
			.isEqualTo("(1 2 (3 4))");
		assertThat(compileAndRun("(defun g (a b &rest r) (list a b r)) (print (apply #'g 1 '(2 3)))"))
			.isEqualTo("(1 2 (3))");
		assertThat(compileAndRun(
				"(defun tailof (a &rest r) r) (defvar *l* (list 1 2)) (print (eq *l* (apply #'tailof 0 *l*)))"))
			.isEqualTo("T");
		assertThat(compileAndRun(
				"(defvar *o* nil) (defun n (x) (setq *o* (cons x *o*)) x)" + " (defun g (a &rest r) (cons a r))"
						+ " (print (apply #'g (n 1) (n 2) (list (n 3)))) (print (reverse *o*))"))
			.isEqualTo("(1 2 3)\n(1 2 3)");
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

	/**
	 * {@link #compileAndRunWithDir} plus extra preopened directories, named by their
	 * ABSOLUTE host paths (wasmtime maps {@code --dir
	 *
	<p>
	 * } to the guest path {@code
	 *
	<p>
	 * }). That is what a test of absolute-path resolution needs: {@code --dir .} preopens
	 * a directory whose NAME is {@code "."}, which can never cover an absolute path.
	 * @param lispCode the program
	 * @param dirs the additional directories to preopen
	 * @return its trimmed standard output
	 * @throws Exception if the run fails
	 */
	private static String compileAndRunWithDirs(String lispCode, String... dirs) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode));
		byte[] wasmBytes = new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(wasmBytes), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("bash", "-c", "cd " + workDir()
				+ " && wasmtime --wasm gc --wasm exceptions=y --dir ." + preopenFlags(dirs) + " test.wasm");
		assertThat(result.getExitCode()).as("exit code for: %s\nstderr: %s", lispCode, result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	/** The component twin of {@link #compileAndRunWithDirs}. */
	private static String compileAndRunComponentWithDirs(String lispCode, String... dirs) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode));
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("test.component.wasm"));
		ExecResult result = wasmtime.execInContainer("bash", "-c", "cd " + workDir()
				+ " && wasmtime run -W gc=y -W exceptions=y --dir ." + preopenFlags(dirs) + " test.component.wasm");
		assertThat(result.getExitCode()).as("exit code for component: %s\nstderr: %s", lispCode, result.getStderr())
			.isZero();
		return result.getStdout().trim();
	}

	private static String preopenFlags(String... dirs) {
		StringBuilder flags = new StringBuilder();
		for (String dir : dirs) {
			flags.append(" --dir ").append(dir);
		}
		return flags.toString();
	}

	private static String compileAndRunWithDir(String lispCode) throws Exception {
		return compileAndRunWithDir(lispCode, false, false);
	}

	private static String compileAndRunWithDir(String lispCode, boolean simd, boolean component) throws Exception {
		// The prelude splice mirrors the CLI pipeline; it emits nothing for a program
		// that references no prelude name, so every pre-existing case is unaffected.
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode));
		byte[] wasmBytes = new WasmLispCompiler(false, component, false, OptimizeLevel.NONE, false, simd)
			.compile(program);
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
	void defmethodOnABuiltinNameKeepsTheBuiltinAsTheDefaultMethod() throws Exception {
		// (length X) is compiler-lowered, so the generated dispatcher defun used to be
		// dead under its own name and the user method was silently ignored.
		// ShadowedBuiltins renames the dispatcher, rewrites the call sites and keeps
		// the built-in as the default method.
		assertThat(compileAndRun("""
				(defclass sbm-cls () ())
				(defmethod length ((x sbm-cls)) 42)
				(print (list (length (make-instance 'sbm-cls)) (length "abc") (length '(1 2 3))))
				(defgeneric endp (x))
				(print (endp nil))
				""")).isEqualTo("(42 3 3)\nT");
	}

	@Test
	void defmethodOnAVariadicBuiltinNameForwardsThroughTheDispatcher() throws Exception {
		// fast-io's gray.lisp shape: a (close (s cls) &key abort) method must not
		// poison close for real stream handles -- with-open-file (pre-expanded so its
		// implicit close routes through the dispatcher) still closes, and an explicit
		// (close s :abort t) reaches the built-in through the %gf-rest tail.
		assertThat(compileAndRunWithDir("""
				(defclass sbm-stream () ())
				(defmethod close ((s sbm-stream) &key abort) (declare (ignore abort)) :closed)
				(print (close (make-instance 'sbm-stream)))
				(with-open-file (out "sbm-closed.txt" :direction :output) (write-line "hi" out))
				(print (with-open-file (in "sbm-closed.txt") (read-line in)))
				(let ((s (open "sbm-closed.txt"))) (print (close s :abort t)))
				""")).isEqualTo(":CLOSED\n\"hi\"\nT");
	}

	@Test
	void defmethodOnABuiltinNameQualifiersAndFirstClassReference() throws Exception {
		// A :before-only branch composes with the built-in default method,
		// call-next-method out of the least specific user primary reaches it, and
		// #'length is rewritten onto the dispatcher so a funcall dispatches too.
		assertThat(compileAndRun("""
				(defclass sbq-cls () ())
				(defparameter *sbq-log* nil)
				(defmethod length :before ((x string)) (push :before *sbq-log*))
				(defmethod length ((x sbq-cls)) (call-next-method))
				(print (list (length "abc") (reverse *sbq-log*)))
				(print (funcall #'length "abcd"))
				""")).isEqualTo("(3 (:BEFORE))\n4");
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
		assertThat(compileAndRunWithDir(code)).isEqualTo("#P\"probe.txt\"\nNIL\nNO");
	}

	// One program for both WASM modes: pure computation over the namestring, so the
	// answers have to be the interpreter's and the JVM's exactly.
	private static final String PATHNAME_ALGEBRA_PROGRAM = """
			(print (list (pathname-host "d/a.txt") (pathname-device #P"d/a.txt")
			             (pathname-version #P"d/a.txt")))
			(print (list (wild-pathname-p "d/*.txt") (wild-pathname-p "d/a.txt")
			             (wild-pathname-p "d/*.txt" :name) (wild-pathname-p "d/*.txt" :type)
			             (wild-pathname-p "*/a.txt" :directory) (wild-pathname-p "d/*.txt" :host)))
			(print (enough-namestring "/a/b/c.lisp" "/a/"))
			(print (enough-namestring "/a/b/c.lisp" "/x/"))
			(print (namestring *default-pathname-defaults*))
			(print (let ((*default-pathname-defaults* #P"/a/b/")) (enough-namestring "/a/b/c.lisp")))
			(print (translate-pathname "src/foo.lisp" "src/*.lisp" "build/*.fasl"))
			(print (namestring (translate-pathname "a/b.c" "*/*.*" "x/*-y.*")))
			(print (translate-logical-pathname "d/a.txt"))
			(print (handler-case (logical-pathname "SYS:SRC;") (error () :signalled)))
			(print (handler-case (rename-file "a.txt" "b.txt") (error () :no-rename)))
			""";

	private static final String PATHNAME_ALGEBRA_EXPECTED = """
			(NIL NIL NIL)
			(T NIL T NIL T NIL)
			"b/c.lisp"
			"/a/b/c.lisp"
			""
			"c.lisp"
			#P"build/foo.fasl"
			"x/a-y.b"
			#P"d/a.txt"
			:SIGNALLED
			:NO-RENAME""";

	@Test
	void pathnameAlgebraOverTheFlatNamestring() throws Exception {
		// Everything here is namestring computation shared with the interpreter and the
		// JVM through one prelude definition, so a divergence would mean the shared
		// definition stopped being shared. rename-file is the one WASM divergence: the
		// import set carries no rename call, so %rename-file is a call-time signal (the
		// %delete-file rule).
		assertThat(compileAndRunPrelude(PATHNAME_ALGEBRA_PROGRAM)).isEqualTo(PATHNAME_ALGEBRA_EXPECTED);
	}

	@Test
	void componentPathnameAlgebraOverTheFlatNamestring() throws Exception {
		assertThat(compileAndRunComponent(PATHNAME_ALGEBRA_PROGRAM)).isEqualTo(PATHNAME_ALGEBRA_EXPECTED);
	}

	// One program for both WASM modes: the three prelude-Lisp families. Only machine-type
	// differs from the JVM expectations -- it names the ABI the
	// artifact targets, which here is the wasm32 module.
	private static final String ENQUIRY_AND_NAMESTRING_PROGRAM = """
			(print (list (file-namestring #P"/a/b/c.txt") (directory-namestring #P"/a/b/c.txt")
			             (host-namestring #P"/a/b/c.txt")))
			(print (list (file-namestring "a.txt") (directory-namestring "a.txt")))
			(print (list (file-namestring "/a/b/") (directory-namestring "/a/b/")))
			(print (list (file-namestring "/a/.bashrc") (directory-namestring "/a/.bashrc")))
			(print (nstring-upcase (copy-seq "hello world")))
			(print (nstring-downcase (copy-seq "ABC")))
			(print (nstring-capitalize (copy-seq "hello world")))
			(print (funcall #'nstring-upcase (copy-seq "ab")))
			(print (let ((s (make-string 3 :initial-element #\\a)))
			         (list (eq s (nstring-upcase s)) s)))
			;; A copy-seq result is a mutable character vector too (.todo/559 step 2),
			;; so the destructive case family writes it in place like the interpreter.
			(print (let ((s (copy-seq "ab"))) (nstring-upcase s) s))
			(print (list (lisp-implementation-type) (software-type) (software-version)))
			(print (list (machine-type) (machine-version) (machine-instance)))
			(print (list (short-site-name) (long-site-name)))
			(print (equal (lisp-implementation-version) (getf (rontolisp:version) :version)))
			""";

	private static final String ENQUIRY_AND_NAMESTRING_EXPECTED = """
			("c.txt" "/a/b/" "")
			("a.txt" "")
			("" "/a/b/")
			(".bashrc" "/a/")
			"HELLO WORLD"
			"abc"
			"Hello World"
			"AB"
			(T "AAA")
			"AB"
			("rontolisp" "Unix" NIL)
			("WASM32" NIL NIL)
			(NIL NIL)
			T""";

	@Test
	void namestringHalvesNstringCaseAndEnvironmentEnquiry() throws Exception {
		assertThat(compileAndRunPrelude(ENQUIRY_AND_NAMESTRING_PROGRAM)).isEqualTo(ENQUIRY_AND_NAMESTRING_EXPECTED);
	}

	@Test
	void componentNamestringHalvesNstringCaseAndEnvironmentEnquiry() throws Exception {
		assertThat(compileAndRunComponent(ENQUIRY_AND_NAMESTRING_PROGRAM)).isEqualTo(ENQUIRY_AND_NAMESTRING_EXPECTED);
	}

	@Test
	void fileMetadataAnswersNilAndDirectoryCreationSignals() throws Exception {
		// The remaining WASM divergence (.kb/read-load-streams.md): no timestamp call is
		// imported, and "cannot be determined" IS Common Lisp's answer for
		// file-write-date -- so it answers nil here while the interpreter and the JVM
		// answer for real. file-length is REAL on all four since the fd_filestat_get
		// import landed (fileLengthAnswersTheSizeOfARealFile below).
		// ensure-directories-exist has no such escape in its contract, so it SIGNALS
		// rather than pretending the directory is there.
		String code = """
				(with-open-file (out "meta.txt" :direction :output) (write-line "hello" out))
				(print (file-write-date "meta.txt"))
				(print (ignore-errors (ensure-directories-exist "sub/dir/x.txt")))
				(print (if (probe-file "sub/dir/x.txt") 'made 'absent))
				""";
		assertThat(compileAndRunWithDir(code)).isEqualTo("NIL\nNIL\nABSENT");
	}

	/**
	 * The program both {@code file-length} tests run: a file of a KNOWN size, its length
	 * read back through the output stream that wrote it and through a fresh input stream,
	 * then every designator that genuinely has no length. The last line is the shape that
	 * used to TRAP one call later -- {@code (min 4096 nil)} is a cast failure on this
	 * backend, not an error -- which is what made the nil answer worse than a wrong
	 * number would have been.
	 */
	private static final String FILE_LENGTH_PROGRAM = """
			(with-open-file (out "len.bin" :direction :output :if-exists :supersede
			                     :element-type '(unsigned-byte 8))
			  (dotimes (i 300) (write-byte (mod i 256) out))
			  (print (file-length out)))
			(with-open-file (in "len.bin" :element-type '(unsigned-byte 8))
			  (print (file-length in)))
			(with-input-from-string (s "abcdef") (print (file-length s)))
			(print (file-length *standard-output*))
			(print (file-length t))
			(let ((h (open "len.bin"))) (close h) (print (file-length h)))
			(with-open-file (in "len.bin" :element-type '(unsigned-byte 8))
			  (print (min 4096 (file-length in))))
			""";

	private static final String FILE_LENGTH_EXPECTED = "300\n300\nNIL\nNIL\nNIL\nNIL\n300";

	@Test
	void fileLengthAnswersTheSizeOfARealFile() throws Exception {
		assertThat(compileAndRunWithDir(FILE_LENGTH_PROGRAM)).isEqualTo(FILE_LENGTH_EXPECTED);
	}

	@Test
	void componentFileLength() throws Exception {
		// The component path stats over wasi:filesystem's descriptor.stat through a
		// different adapter (the preview1 filestat is re-encoded from a lowered
		// descriptor-stat), so the whole answer is verified there too.
		assertThat(compileAndRunComponentWithDir(FILE_LENGTH_PROGRAM)).isEqualTo(FILE_LENGTH_EXPECTED);
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
		assertThat(compileAndRunWithDir(code)).isEqualTo("(#P\"probe-fc.txt\" NIL)\n#P\"probe-fc.txt\"\nNIL");
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
		assertThat(compileAndRunComponentWithDir(code)).isEqualTo("#P\"cprobe.txt\"\nNIL");
	}

	/**
	 * Stages the tree the two absolute-path tests share, in its OWN per-thread directory
	 * rather than under {@link #workDir()} -- the work dir's stale-file sweep deletes
	 * plain files, so a directory left there would break the NEXT run's setup.
	 *
	 * <p>
	 * The layout is chosen to make the prefix match the thing under test. The preopen
	 * handed to wasmtime is {@code <root>}, the file's GRANDPARENT, so the module has to
	 * strip the preopen name and hand {@code path_open} the remainder. And both
	 * {@code <root>-sibling/abs.txt} and {@code <root>/-sibling/abs.txt} exist, so a
	 * resolution that matched the preopen name WITHOUT requiring a component boundary
	 * would find a real file where the answer must be nil.
	 * @return the absolute path of the staged root
	 * @throws Exception if the tree cannot be staged
	 */
	private static String stageAbsolutePathTree() throws Exception {
		Path root = Path.of(System.getProperty("java.io.tmpdir"), "rontolisp-wasmtime",
				"abs" + Thread.currentThread().threadId());
		try (Stream<Path> stale = Files.isDirectory(root) ? Files.walk(root) : Stream.<Path>empty()) {
			for (Path entry : stale.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(entry);
			}
		}
		wasmtime.copyFileToContainer(Transferable.of("absolute\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				root + "/sub/abs.txt");
		wasmtime.copyFileToContainer(Transferable.of("boundary\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				root + "-sibling/abs.txt");
		wasmtime.copyFileToContainer(Transferable.of("boundary\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				root + "/-sibling/abs.txt");
		wasmtime.copyFileToContainer(Transferable.of("relative\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				path("rel-abs432.txt"));
		return root.toString();
	}

	// One program for both WASM modes, and the shape the whole feature is about: the path
	// is BUILT AT RUN TIME. A LITERAL absolute path proves nothing here -- both compile
	// paths bundle the file's compile-time contents into the artifact
	// (CompileTimePathnameFolder, .kb/asdf.md), so nothing is opened and the program
	// "works" with no --dir at all.
	private static final String ABSOLUTE_PATH_PROGRAM = """
			(defvar *root* "%s")
			(print (with-open-file (s (concatenate 'string *root* "/sub/abs.txt")) (read-line s)))
			(print (probe-file (concatenate 'string *root* "/sub/abs.txt")))
			(print (probe-file (concatenate 'string *root* "/sub/absent.txt")))
			(print (probe-file (concatenate 'string *root* "-sibling/abs.txt")))
			(print (probe-file *root*))
			(print (with-open-file (s "rel-abs432.txt") (read-line s)))
			""";

	private static String absolutePathExpected(String root) {
		return """
				"absolute"
				#P"%s/sub/abs.txt"
				NIL
				NIL
				#P"%s"
				"relative"
				""".formatted(root, root).trim();
	}

	@Test
	void absoluteRuntimePathResolvesAgainstThePreopenThatCoversIt() throws Exception {
		// The bug this pins: _open / _probe_file handed every path to fd 3, so an
		// absolute one was rejected by WASI even when a preopen mapped exactly the
		// directory meant -- and probe-file, which cannot signal, answered "not there"
		// for a file that exists.
		String root = stageAbsolutePathTree();
		assertThat(compileAndRunWithDirs(ABSOLUTE_PATH_PROGRAM.formatted(root), root))
			.isEqualTo(absolutePathExpected(root));
	}

	@Test
	void componentAbsoluteRuntimePathResolvesAgainstThePreopenThatCoversIt() throws Exception {
		// The component reaches path_open through adapter.wat over wasi:filesystem@0.3.0,
		// which used to cache ONE preopen descriptor and ignore dirfd entirely -- so the
		// same widening had to happen there, and only this leg exercises it.
		String root = stageAbsolutePathTree();
		assertThat(compileAndRunComponentWithDirs(ABSOLUTE_PATH_PROGRAM.formatted(root), root))
			.isEqualTo(absolutePathExpected(root));
	}

	// One program for both WASM modes: the entries are created by the program itself so
	// the listing is exactly what it wrote, whatever the run directory holds.
	private static final String DIRECTORY_LISTING_PROGRAM = """
			(with-open-file (out "dl-b.txt" :direction :output) (write-line "b" out))
			(with-open-file (out "dl-a.txt" :direction :output) (write-line "a" out))
			(print (directory "./dl-*.txt"))
			(print (remove-if-not (lambda (x)
			                        (let ((n (namestring x)))
			                          (and (> (length n) 5) (string= (subseq n 0 5) "./dl-"))))
			                      (directory "./*.*")))
			(print (uiop:directory-exists-p "."))
			(print (uiop:directory-exists-p "dl-a.txt"))
			(print (uiop:directory-exists-p "no-such-dir"))
			(print (directory "no-such-dir/*.*"))
			""";

	private static final String DIRECTORY_LISTING_EXPECTED = """
			(#P"./dl-a.txt" #P"./dl-b.txt")
			(#P"./dl-a.txt" #P"./dl-b.txt")
			#P"./"
			NIL
			NIL
			NIL""";

	@Test
	void directoryListsEntriesOverFdReaddir() throws Exception {
		// The ninth preview1 import. "." and ".." come back from a preview1 fd_readdir
		// and must be dropped -- Files.list and wasi:filesystem's read-directory both
		// omit them, so keeping them would be a one-backend divergence (and would make
		// collect-sub*directories walk its own parent forever).
		assertThat(compileAndRunWithDir(DIRECTORY_LISTING_PROGRAM)).isEqualTo(DIRECTORY_LISTING_EXPECTED);
	}

	@Test
	void directoryListingResumesPastOneReaddirRound() throws Exception {
		// The listing buffer is 8 KiB, so a directory this size spans several fd_readdir
		// rounds and only the cookie resume gets all of it back.
		String code = """
				(dotimes (i 400)
				  (with-open-file (out (concatenate 'string "many-" (princ-to-string (+ 1000 i)) ".txt")
				                       :direction :output)
				    (write-line "x" out)))
				(let ((all (directory "./many-*.txt")))
				  (print (length all))
				  (print (first all))
				  (print (car (last all))))
				""";
		assertThat(compileAndRunWithDir(code)).isEqualTo("400\n#P\"./many-1000.txt\"\n#P\"./many-1399.txt\"");
	}

	@Test
	void componentDirectoryListing() throws Exception {
		// The component path lists over wasi:filesystem's read-directory through a
		// different adapter (a stream<directory-entry>, not a byte stream), so the whole
		// family is verified there too.
		assertThat(compileAndRunComponentWithDir(DIRECTORY_LISTING_PROGRAM)).isEqualTo(DIRECTORY_LISTING_EXPECTED);
	}

	// The wild-DIRECTORY walk. The tree is built with mkdir in the container because
	// neither WASM backend has a directory-creating primitive, and it is
	// removed again so the scratch dir stays flat for the next case.
	private static final String WILD_TREE_PROGRAM = """
			(print (namestring (make-pathname :directory '(:absolute "a" :wild-inferiors)
			                                  :name :wild :type "lisp")))
			(print (list (pathname-directory "/a/**/x.lisp") (pathname-name "/a/**/*.lisp")))
			(print (list (wild-pathname-p "/a/**/x.lisp" :directory)
			             (wild-pathname-p "/a/**/x.lisp" :name)))
			(print (namestring (translate-pathname "/a/b/d/c.lisp" "/a/**/*.lisp" "/x/**/*.fasl")))
			(print (namestring (translate-pathname "/a/c.lisp" "/a/**/*.lisp" "/x/**/*.fasl")))
			(print (directory "./wt/**/*.lisp"))
			(print (directory "./wt/*/*.lisp"))
			(print (directory "./wt/**/"))
			(print (directory "./nope/**/*.lisp"))
			""";

	private static final String WILD_TREE_EXPECTED = """
			"/a/**/*.lisp"
			((:ABSOLUTE "a" :WILD-INFERIORS) :WILD)
			(T NIL)
			"/x/b/d/c.fasl"
			"/x/c.fasl"
			(#P"./wt/b/c/deep.lisp" #P"./wt/b/mid.lisp" #P"./wt/top.lisp")
			(#P"./wt/b/mid.lisp")
			(#P"./wt/" #P"./wt/b/" #P"./wt/b/c/" #P"./wt/d/")
			NIL""";

	private static final String WILD_TREE_MKDIR = "mkdir -p wt/b/c wt/d && echo 1 > wt/top.lisp"
			+ " && echo 2 > wt/b/mid.lisp && echo 3 > wt/b/c/deep.lisp && echo 4 > wt/d/x.txt";

	@Test
	void wildDirectoryComponentsDriveTheRecursiveWalk() throws Exception {
		assertThat(compileAndRunWildTree(false)).isEqualTo(WILD_TREE_EXPECTED);
	}

	@Test
	void componentWildDirectoryComponentsDriveTheRecursiveWalk() throws Exception {
		assertThat(compileAndRunWildTree(true)).isEqualTo(WILD_TREE_EXPECTED);
	}

	private static String compileAndRunWildTree(boolean component) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary
			.process(LispReader.readAllFromString(WILD_TREE_PROGRAM));
		String module = component ? "wildtree.component.wasm" : "wildtree.wasm";
		byte[] bytes = component ? new WasmLispCompiler(false, true).compile(program)
				: new WasmLispCompiler().compile(program);
		wasmtime.copyFileToContainer(Transferable.of(bytes), path(module));
		String run = component ? "wasmtime run -W gc=y -W exceptions=y --dir . " + module
				: "wasmtime --wasm gc --wasm exceptions=y --dir . " + module;
		try {
			ExecResult result = wasmtime.execInContainer("bash", "-c",
					"cd " + workDir() + " && " + WILD_TREE_MKDIR + " && " + run);
			assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
			return result.getStdout().trim();
		}
		finally {
			wasmtime.execInContainer("bash", "-c", "rm -rf " + workDir() + "/wt " + path(module));
		}
	}

	@Test
	void componentDirectoryListingWithoutAPreopenAnswersNil() throws Exception {
		// No --dir at all: path_open cannot even name a directory, and the answer must
		// be nil rather than the trap an unguarded preopen lookup used to produce.
		assertThat(compileAndRunComponent("(print (directory \"./*.*\")) (print (uiop:directory-exists-p \".\"))"))
			.isEqualTo("NIL\nNIL");
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
				  (write-string "tail" s)))""")).isEqualTo("\"a=42\n\\\"q\\\" end\ntail\"");
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
	void errorOutputIsTheProcessErrorStream() throws Exception {
		// *error-output* is the standard ERROR designator (the handle 2, here literally
		// the WASI fd), so a diagnostic written through it stays off standard output.
		assertThat(compileAndRunStderr("""
				(format *error-output* "diag ~a~%" 1)
				(write-line "line" *error-output*)
				(princ "on-stdout")""")).isEqualTo("diag 1\nline");
		assertThat(compileAndRun("""
				(format *error-output* "diag~%")
				(princ "on-stdout")""")).isEqualTo("on-stdout");
	}

	@Test
	void bindingErrorOutputCapturesWarnAndRestores() throws Exception {
		// CL's warning-capture idiom: warn's report defaults to *error-output*, so a
		// binding captures it -- and the unbound default still reaches stderr.
		assertThat(compileAndRun("""
				(princ (with-output-to-string (*error-output*)
				  (warn "captured")
				  (format *error-output* "diag~%")))
				(princ "|")
				(princ (open-stream-p *error-output*))""")).isEqualTo("WARNING: captured\ndiag\n|T");
		assertThat(compileAndRunStderr("""
				(let ((s (%make-string-output-stream)))
				  (let ((*error-output* s)) (warn "hidden")))
				(warn "visible")""")).isEqualTo("WARNING: visible");
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
	void stringOutputStreamNamesClearOnRead() throws Exception {
		assertThat(compileAndRun("""
				(let ((s (make-string-output-stream)))
				  (write-string "ab" s)
				  (princ (get-output-stream-string s))
				  (write-string "cd" s)
				  (princ (get-output-stream-string s))
				  (princ (list (length (get-output-stream-string s)))))""")).isEqualTo("abcd(0)");
	}

	@Test
	void stringInputStreamReadsWithoutWithInputFromString() throws Exception {
		assertThat(compileAndRun("""
				(let ((s (make-string-input-stream "ab
				cd")))
				  (princ (read-line s))
				  (princ (read-line s))
				  (princ (read-line s nil 'eof)))""")).isEqualTo("abcdEOF");
	}

	@Test
	void stringInputStreamHonoursStartAndEnd() throws Exception {
		assertThat(compileAndRun("""
				(let ((s (make-string-input-stream "xxhixx" 2 4)))
				  (princ (read-char s))
				  (princ (read-char s))
				  (princ (read-char s nil 'eof)))""")).isEqualTo("hiEOF");
	}

	@Test
	void peekCharLeavesTheCharacterInTheStream() throws Exception {
		assertThat(compileAndRun("""
				(with-input-from-string (s "ab")
				  (princ (peek-char nil s))
				  (princ (peek-char nil s))
				  (princ (read-char s))
				  (princ (read-char s))
				  (princ (peek-char nil s nil 'eof)))""")).isEqualTo("aaabEOF");
	}

	@Test
	void peekCharSkipsWhitespaceAndUpToACharacter() throws Exception {
		assertThat(compileAndRun("""
				(with-input-from-string (s "   xy")
				  (princ (peek-char t s))
				  (princ (read-char s))
				  (princ (peek-char #\\y s))
				  (princ (read-char s)))""")).isEqualTo("xxyy");
	}

	@Test
	void readCharEndOfFileIsCatchableAsEndOfFile() throws Exception {
		assertThat(compileAndRunEh("""
				(with-input-from-string (s "")
				  (princ (handler-case (read-char s) (end-of-file () 'caught))))
				(with-input-from-string (s "")
				  (princ (handler-case (read-char s) (error () 'as-error))))""")).isEqualTo("CAUGHTAS-ERROR");
	}

	@Test
	void makeSynonymStreamResolvesTheNamedVariable() throws Exception {
		assertThat(compileAndRunPrelude(
				"(defvar *sink* (make-synonym-stream '*standard-output*)) (write-string \"via\" *sink*)"))
			.isEqualTo("via");
	}

	@Test
	void makeSynonymStreamIsAStreamValue() throws Exception {
		// A synonym stream is a VALUE, not the nil designator: it answers true, it is a
		// stream in both directions, close is a no-op t, and it prints the symbol it
		// forwards to.
		assertThat(compileAndRunPrelude("""
				(defvar *sink* (make-synonym-stream '*standard-output*))
				(princ (if *sink* "yes" "no"))
				(princ (streamp *sink*))
				(princ (input-stream-p *sink*))
				(princ (output-stream-p *sink*))
				(princ (close *sink*))
				(princ (synonym-stream-symbol *sink*))
				(princ *sink*)""")).isEqualTo("yesTTTT*STANDARD-OUTPUT*#<SYNONYM-STREAM :SYMBOL *STANDARD-OUTPUT*>");
	}

	@Test
	void synonymStreamOverAUserSpecialFollowsALaterBinding() throws Exception {
		// The re-evaluation trigger the lite lowering left behind: a synonym over a
		// NON-standard symbol forwards per operation too, so a binding established after
		// the synonym was built redirects it. A Gray stream on either side of the
		// synonym is carried as well (rove's reporter shape).
		assertThat(compileAndRunGray("""
				(defvar *port* t)
				(defvar *syn* (make-synonym-stream '*port*))
				(defclass upcaser (rontolisp:fundamental-character-output-stream)
				  ((target :initarg :target :reader upcaser-target)))
				(defmethod rontolisp:stream-write-string ((s upcaser) str)
				  (write-string (string-upcase str) (upcaser-target s))
				  str)
				(princ (with-output-to-string (s) (let ((*port* s)) (write-string "user" *syn*))))
				(princ "|")
				(princ (with-output-to-string (s)
				  (let ((*port* s)) (write-string "wrap" (make-instance 'upcaser :target *syn*)))))
				(princ "|")
				(princ (with-output-to-string (s)
				  (let ((*port* (make-instance 'upcaser :target s))) (write-string "under" *syn*))))"""))
			.isEqualTo("user|WRAP|UNDER");
	}

	@Test
	void synonymStreamOverStandardOutputFollowsALaterBinding() throws Exception {
		// The synonym stream resolves its symbol at WRITE time, so a binding
		// established after it was built still captures.
		assertThat(compileAndRunPrelude("""
				(defvar *sink* (make-synonym-stream '*standard-output*))
				(princ (with-output-to-string (*standard-output*)
				  (write-line "captured" *sink*)))
				(princ "|")
				(write-line "plain" *sink*)""")).isEqualTo("captured\n|plain");
	}

	@Test
	void bindingStandardInputRedirectsTheStreamlessReadFamily() throws Exception {
		// The input mirror of the *standard-output* redirect: binding *standard-input*
		// redirects read-line / read-char / read, including inside called functions, and
		// an explicit nil argument is the same designator.
		assertThat(compileAndRun("""
				(defun slurp (&optional stream) (princ (read-line stream)) (princ "|"))
				(with-input-from-string (*standard-input* "one")
				  (slurp))
				(princ (with-input-from-string (*standard-input* "abc") (read-char)))
				(princ (with-input-from-string (*standard-input* "(1 2 3)") (read)))
				(princ (with-input-from-string (*standard-input* "x") (read-line nil)))""")).isEqualTo("one|a(1 2 3)x");
	}

	@Test
	void makeSynonymStreamOverStandardInputFollowsALaterBinding() throws Exception {
		assertThat(compileAndRunPrelude("""
				(defvar *src* (make-synonym-stream '*standard-input*))
				(princ (with-input-from-string (*standard-input* "later")
				  (read-line *src*)))""")).isEqualTo("later");
	}

	@Test
	void explicitNilStreamArgumentIsTheStandardOutputDesignator() throws Exception {
		// CL's stream designator rule: a forwarded optional that arrives as nil reaches
		// the CURRENT *standard-output*, not raw stdout.
		assertThat(compileAndRun("""
				(defun emit (x &optional stream)
				  (princ x stream)
				  (write-string "|" stream)
				  (write-line "" stream)
				  (fresh-line stream)
				  (terpri stream))
				(princ (with-output-to-string (*standard-output*)
				  (emit "a")
				  (print 1 nil)))
				(princ "-")
				(emit "b")""")).isEqualTo("a|\n\n1\n-b|");
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
	void withOpenFileComputedOptionsDispatchAtRunTime() throws Exception {
		// The options arrive as ARGUMENTS (uiop's call-with-input-file shape), so the
		// mode is not foldable: the compiled module dispatches onto the literal open
		// shapes, one WASI path_open per leaf.
		String code = """
				(defun wr (path text dir ie)
				  (with-open-file (out path :direction dir :if-exists ie) (write-string text out)))
				(defun rd (path et)
				  (with-open-file (in path :element-type et) (read-line in)))
				(defun rd1 (path et)
				  (with-open-file (in path :element-type et) (read-byte in)))
				(wr "computed.txt" "one" :output :supersede)
				(wr "computed.txt" "two" :output :append)
				(print (rd "computed.txt" 'character))
				(print (rd1 "computed.txt" (list 'unsigned-byte 8)))
				""";
		assertThat(compileAndRunWithDir(code)).isEqualTo("\"onetwo\"\n111");
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

	/** The byte-transparency program every backend's standard-stream test runs. */
	private static final String BINARY_CAT = """
			(let ((b (read-byte *standard-input* nil nil)))
			  (while b
			    (write-byte b *standard-output*)
			    (setq b (read-byte *standard-input* nil nil))))
			""";

	/** stdin for {@link #BINARY_CAT}: a NUL, a UTF-8 sequence, a high byte, a newline. */
	private static final byte[] BINARY_CAT_STDIN = { 'h', 'i', 0, (byte) 0xE6, (byte) 0x97, (byte) 0xA5, (byte) 0xFF,
			'\n', 'z' };

	private static final String BINARY_CAT_HEX = "686900e697a5ff0a7a";

	@Test
	void binaryStandardStreamsAreByteTransparent() throws Exception {
		assertThat(compileAndRunBinary(new WasmLispCompiler().compile(LispReader.readAllFromString(BINARY_CAT)),
				BINARY_CAT_STDIN, "wasmtime run -W gc"))
			.isEqualTo(BINARY_CAT_HEX);
	}

	@Test
	void componentBinaryStandardStreamsAreByteTransparent() throws Exception {
		// The component reads fd 0 and writes fd 1 through the preview1 adapter (this
		// program is not async, so stdin.lisp is not spliced -- see
		// .kb/read-load-streams.md).
		assertThat(
				compileAndRunBinary(new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(BINARY_CAT)),
						BINARY_CAT_STDIN, "wasmtime run -W gc=y"))
			.isEqualTo(BINARY_CAT_HEX);
	}

	@Test
	void binaryStandardStreamDesignators() throws Exception {
		String code = """
				(setq buf (make-array 4 :initial-element 0))
				(setq filled (read-sequence buf t))
				(write-byte 62 nil)
				(write-sequence buf t :end filled)
				(write-byte 60 *standard-output*)
				(print (read-byte *standard-input* nil :eof))
				""";
		assertThat(compileAndRunBinary(new WasmLispCompiler().compile(LispReader.readAllFromString(code)),
				new byte[] { 65, 66, 67 }, "wasmtime run -W gc"))
			// ">ABC<:EOF\n"
			.isEqualTo("3e4142433c3a454f460a");
	}

	/**
	 * Runs a module over RAW stdin bytes and answers its stdout as lowercase hex.
	 * {@code ExecResult} decodes stdout as text, which a byte-transparency assertion
	 * cannot use, so {@code od} renders the bytes inside the container instead.
	 */
	private static String compileAndRunBinary(byte[] moduleBytes, byte[] stdin, String runCommand) throws Exception {
		wasmtime.copyFileToContainer(Transferable.of(moduleBytes), path("test.wasm"));
		wasmtime.copyFileToContainer(Transferable.of(stdin), path("stdin.bin"));
		ExecResult result = wasmtime.execInContainer("bash", "-c", "set -o pipefail; " + runCommand + " "
				+ path("test.wasm") + " < " + path("stdin.bin") + " | od -An -tx1 -v | tr -d ' \\n'");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		return result.getStdout().trim();
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
	void readWriteSequencePackedBuffersMoveRawLittleEndianElements() throws Exception {
		// The _read_packed / _write_packed runtime helpers (.kb/binary-sequence-io.md):
		// a packed float array of any rank and a packed (unsigned-byte 8|16|32) vector
		// move as raw little-endian elements through the 64 KiB HEAP_PTR chunk; a general
		// vector still receives one byte per element. Both the scalar farray and the
		// --simd vblock representation, and the --component adapter's fd_read/fd_write.
		String code = """
				(with-open-file (out "pk.dat" :direction :output :element-type '(unsigned-byte 8))
				  (write-sequence #f(1.5 -2.25 3.0e10) out)
				  (write-sequence #d((0.5 -0.0) (0.1 42.0)) out)
				  (write-sequence (make-array 3 :element-type '(unsigned-byte 16) :initial-contents '(1 65535 258)) out)
				  (write-sequence (make-array 2 :element-type '(unsigned-byte 32) :initial-contents '(65536 4294967295)) out))
				(with-open-file (in "pk.dat" :element-type '(unsigned-byte 8))
				  (let ((f (make-array 3 :element-type 'single-float :initial-element 0.0))
				        (d (make-array '(2 2) :element-type 'double-float :initial-element 0.0))
				        (u16 (make-array 3 :element-type '(unsigned-byte 16)))
				        (u32 (make-array 2 :element-type '(unsigned-byte 32))))
				    (print (list (read-sequence f in) (read-sequence d in) (read-sequence u16 in) (read-sequence u32 in)))
				    (print f) (print d) (print u16) (print u32)))
				(with-open-file (in "pk.dat" :element-type '(unsigned-byte 8))
				  (let ((b (make-array 4 :element-type '(unsigned-byte 8))))
				    (read-sequence b in)
				    (print b)))
				(with-open-file (in "pk.dat" :element-type '(unsigned-byte 8))
				  (let ((f (make-array 6 :element-type 'single-float :initial-element 9.0)))
				    (print (read-sequence f in :start 1 :end 3))
				    (print f)))
				(with-open-file (in "pk.dat" :element-type '(unsigned-byte 8))
				  (let ((g (make-array 4)))
				    (print (read-sequence g in))
				    (print g)))
				""";
		String expected = "(3 4 3 2)\n#f(1.5 -2.25 3.0e10)\n#d((0.5 -0.0) (0.1 42.0))\n#(1 65535 258)\n#(65536 4294967295)\n#(0 0 192 63)\n3\n#f(9.0 1.5 -2.25 9.0 9.0 9.0)\n4\n#(0 0 192 63)";
		assertThat(compileAndRunWithDir(code)).isEqualTo(expected);
		assertThat(compileAndRunWithDir(code, true, false)).isEqualTo(expected);
		assertThat(compileAndRunWithDir(code, false, true)).isEqualTo(expected);
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
	void compileFindClassReturnsAnEqStableClassMetaobject() throws Exception {
		// The generated metaobject runtime memoizes per canonical name, so the answer is
		// eq across calls; slot 0 = name, slot 1 = direct-superclass metaobjects, slot 4
		// = finalized-p (the %obj-ref index contract of the seeded MOP layouts).
		assertThat(compileAndRun("""
				(defclass fc-animal () ((legs :initarg :legs :accessor fc-legs :type integer)))
				(defclass fc-dog (fc-animal) ((name :initarg :name)))
				(let ((c (find-class 'fc-dog)))
				  (print (list (eq c (find-class 'fc-dog))
				               (%obj-ref c 0)
				               (eq (car (%obj-ref c 1)) (find-class 'fc-animal))
				               (%obj-ref c 4))))
				""")).isEqualTo("(T FC-DOG T T)");
	}

	@Test
	void compileFindClassMetaobjectCarriesEffectiveSlotDefinitions() throws Exception {
		assertThat(compileAndRun("""
				(defclass fc-box () ((w :initarg :w :accessor fc-w :type integer) (h :initform 2)))
				(let* ((c (find-class 'fc-box))
				       (slots (%obj-ref c 3)))
				  (print (list (length slots)
				               (mapcar (lambda (s) (%obj-ref s 0)) slots)
				               (%obj-ref (car slots) 1)
				               (%obj-ref (car slots) 3)
				               (%obj-ref (car slots) 4)
				               (%obj-ref (car (cdr slots)) 2))))
				""")).isEqualTo("(2 (W H) (:W) INTEGER (FC-W) 2)");
	}

	@Test
	void compileFindClassUnknownSignalsUnlessErrorpNil() throws Exception {
		assertThat(compileAndRunEh("""
				(print (list (find-class 'fc-no-such nil)
				             (handler-case (find-class 'fc-no-such) (error (e) :signaled))))
				""")).isEqualTo("(NIL :SIGNALED)");
	}

	@Test
	void compileSetfFindClassRegistersAnAliasNameForTheSameClass() throws Exception {
		// The alias is an extra SPELLING of the target's %class-meta-table% entry, so the
		// runtime %find-class memoizes one metaobject for both names and the static
		// make-instance / typep / handler-case resolutions see the target's class.
		assertThat(compileAndRunEh("""
				(defclass fca-shape () ((n :initarg :n :reader fca-n)))
				(setf (find-class '<fca-shape>) (find-class 'fca-shape))
				(define-condition fca-error (error) ((code :initarg :code :reader fca-code)))
				(setf (find-class '<fca-error>) (find-class 'fca-error))
				(print (list (eq (find-class '<fca-shape>) (find-class 'fca-shape))
				             (fca-n (make-instance '<fca-shape> :n 7))
				             (typep (make-instance 'fca-shape :n 1) '<fca-shape>)
				             (%obj-ref (find-class '<fca-shape>) 0)
				             (handler-case (error 'fca-error :code 42) (<fca-error> (e) (fca-code e)))))
				""")).isEqualTo("(T 7 T FCA-SHAPE 42)");
	}

	@Test
	void compileMacroFunctionAndSpecialOperatorP() throws Exception {
		// The four-backend partition of the operators with no function value: the pass
		// appends the program's own macro-function over its macro names and the prelude
		// carries the built-in half, so wasm answers exactly what the interpreter and the
		// JVM answer (LispEvaluatorTest / JvmLispCompilerTest, SBCL-checked).
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary
			.process(am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
					(defmacro mfp-mac (x) `(list ,x))
					(defun mfp-probe (form)
					  (cond ((special-operator-p (first form)) :special)
					        ((macro-function (first form)) :macro)
					        (t :function)))
					(print (list (mfp-probe '(if a b)) (mfp-probe '(quote a)) (mfp-probe '(mfp-mac 1))
					             (mfp-probe '(when a b)) (mfp-probe '(handler-case a)) (mfp-probe '(car x))
					             (mfp-probe '(+ 1 2))))
					(print (list (special-operator-p 'defun) (and (macro-function 'defun) t)
					             (and (macro-function (intern "WHEN")) t)
					             (macro-function 'car) (macro-function 'if)))
					(print (list (multiple-value-list (macroexpand-1 '(mfp-mac 1)))
					             (multiple-value-list (macroexpand-1 '(+ 1 2)))))
					""")))))
			.isEqualTo("(:SPECIAL :SPECIAL :MACRO :MACRO :MACRO :FUNCTION :FUNCTION)\n"
					+ "(NIL T T NIL NIL)\n(((LIST 1) T) ((+ 1 2) NIL))");
	}

	@Test
	void compileMacroexpandOfAComputedArgument() throws Exception {
		// A COMPUTED macroexpand-1 argument is the one shape the fold cannot decide, and
		// the answer must agree with macro-function's on every backend: a macro call
		// signals (no macro table survives compilation), anything else comes back
		// unchanged with expanded-p nil. Answering a macro call with ITSELF would spin
		// the standard "expand until it stops expanding" loop forever.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary
			.process(am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
					(defmacro mxc-mac (x) `(list ,x))
					(defun mxc-steps (form)
					  (do ((step form (macroexpand-1 step)))
					      ((or (special-operator-p (first step)) (not (macro-function (first step)))) step)))
					(print (mxc-steps (list 'car 'x)))
					(print (handler-case (mxc-steps (list 'mxc-mac 9)) (error (e) :no-runtime-macro-table)))
					(print (handler-case (mxc-steps (list 'when 'a 'b)) (error (e) :no-runtime-macro-table)))
					(print (multiple-value-list (macroexpand-1 (list '+ 1 2))))
					"""))))).isEqualTo("(CAR X)\n:NO-RUNTIME-MACRO-TABLE\n:NO-RUNTIME-MACRO-TABLE\n((+ 1 2) NIL)");
	}

	@Test
	void compileSetfMacroFunctionAliasAfterExpansionPass() throws Exception {
		// The macro alias is carried out by the compile-path macro pass (the only macro
		// table the backends have) and the form is dropped, like the defmacro it aliases.
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.UserMacroExpander.expand(LispReader.readAllFromString("""
				(defmacro sfmf-greet (x) `(list :hello ,x))
				(setf (macro-function 'sfmf-hi) (macro-function 'sfmf-greet))
				(print (sfmf-hi 1))
				(print (sfmf-greet 2))
				""")))).isEqualTo("(:HELLO 1)\n(:HELLO 2)");
	}

	@Test
	void compileFindClassAnswersForSeededConditionClasses() throws Exception {
		assertThat(compileAndRun("""
				(let ((c (find-class 'type-error)))
				  (print (list (%obj-ref c 0) (%obj-ref (car (%obj-ref c 1)) 0))))
				""")).isEqualTo("(TYPE-ERROR ERROR)");
	}

	@Test
	void compileCloserMopShimAnswersOverClassMetaobjectsAndLegacyTagDesignators() throws Exception {
		// The closer-mop system is spliced by the compile-time LoadInliner pass (cli),
		// mirroring the CLI pipeline; the shim serves BOTH generations, exactly like the
		// interpreter
		// (LispEvaluatorTest#closerMopShimAnswersOverClassMetaobjectsAndLegacyTagDesignators).
		List<LispVal> program = am.ik.rontolisp.cli.LoadInliner.inline(LispReader.readAllFromString("""
				(asdf:load-system "closer-mop")
				(defclass cmm-p () ((name :initarg :name) (age :initarg :age :type integer)))
				(let ((c (find-class 'cmm-p)))
				  (print (list (closer-mop:classp c)
				               (closer-mop:classp 42)
				               (closer-mop:class-name c)
				               (closer-mop:class-finalized-p c)
				               (mapcar #'closer-mop:slot-definition-name (closer-mop:class-slots c))
				               (mapcar #'closer-mop:slot-definition-type (closer-mop:class-slots c))
				               (mapcar #'closer-mop:slot-definition-initargs (closer-mop:class-slots c))
				               (mapcar #'closer-mop:slot-definition-name
				                       (closer-mop:class-slots (class-of (make-instance 'cmm-p :name "x")))))))
				"""), path -> {
			throw new java.io.FileNotFoundException(path);
		});
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary.process(program)))
			.isEqualTo("(T NIL CMM-P T (NAME AGE) (T INTEGER) ((:NAME) (:AGE)) (NAME AGE))");
	}

	@Test
	void compileAllocateInstanceAnswersAnAllSlotsUnboundInstance() throws Exception {
		// The generated allocate-instance runtime (injected when referenced) resolves a
		// metaobject OR a name designator to the per-class construction arm; every
		// slot starts unbound, mirroring the interpreter built-in.
		assertThat(compileAndRunEh("""
				(defclass ai-pt () ((x :initarg :x :initform 7) (y :initarg :y)))
				(defstruct ai-node value)
				(let ((p (allocate-instance (find-class 'ai-pt))))
				  (print (list (typep p 'ai-pt)
				               (slot-boundp p 'x)
				               (progn (setf (slot-value p 'x) 10) (slot-value p 'x))
				               (let ((q (allocate-instance 'ai-pt))) (slot-boundp q 'x))
				               (handler-case (allocate-instance 'ai-node) (error (e) :signaled)))))
				""")).isEqualTo("(T NIL 10 NIL :SIGNALED)");
	}

	@Test
	void compileCloserCommonLispPackageServesTheDaoPackageShape() throws Exception {
		// (:use :closer-common-lisp) implies cl, and the closer-mop members inherit
		// through the re-export -- the postmodern DAO package shape on the compile
		// path (shim spliced by LoadInliner, like the closer-mop test above).
		List<LispVal> program = am.ik.rontolisp.cli.LoadInliner.inline(LispReader.readAllFromString("""
				(asdf:load-system "closer-mop")
				(defpackage :ccl-probe (:use :closer-common-lisp))
				(in-package :ccl-probe)
				(defclass ccl-pt () ((x :initarg :x) (y :initarg :y)))
				(let ((c (find-class 'ccl-pt)))
				  (print (list (classp c)
				               (mapcar #'slot-definition-name (class-slots c))
				               (c2cl:class-name c)
				               (car (c2cl:list 1 2)))))
				"""), path -> {
			throw new java.io.FileNotFoundException(path);
		});
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary.process(program)))
			.isEqualTo("(T (X Y) CCL-PROBE::CCL-PT 1)");
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
	void exptWithFloatOrRatioExponent() throws Exception {
		// A float or ratio exponent used to trap (the exact loop cast it to an i31):
		// WasmExptCompiler now dispatches on the run-time exponent. An
		// integer-valued float exponent takes the exact multiplication path (8.0, not
		// 7.999...); a fractional one is exp(y * log(x)) over the software exp/log --
		// so its low-order digits differ from Math.pow's and are rounded here -- with
		// the Math.pow edges: x^0.0 = 1.0, 0^y = 0.0 / +inf by the sign of y, a
		// negative base to a fractional power is NaN, +inf^y follows the sign of y.
		assertThat(compileAndRun("""
				(defun give (x) x)
				(print (expt 2 (give 3.0)))
				(print (expt (give 2.0) (give 0.0)))
				(print (expt (give 0.0) (give 0.5)))
				(print (expt (give 0.0) (give -0.5)))
				(print (expt (give -2.0) (give 0.5)))
				(print (expt (give -2.0) (give 3.0)))
				(print (round (* 1000 (expt 4 (give 1/2)))))
				(print (round (* 1000 (expt (give 10000.0) (give 0.75)))))
				(print (round (* 1000000 (expt 2 (give 0.5)))))
				(print (* 1.5 (expt 10 (give 0.0))))
				(print (expt (/ 1.0 0.0) (give 0.5)))
				(print (expt (/ 1.0 0.0) (give -0.5)))
				""")).isEqualTo("8.0\n1.0\n0.0\nInfinity\nNaN\n-8.0\n2000\n1000000\n1414214\n1.5\nInfinity\n0.0");
		// A literal float exponent, the shape the RoPE table of examples/llama2 needs.
		assertThat(compileAndRun("(print (round (* 1000 (expt 10000.0 0.75))))")).isEqualTo("1000000");
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
		// The value is the package KEYWORD find-package answers, so the two are eq.
		assertThat(compileAndRun("(print *package*)")).isEqualTo(":CL-USER");
		assertThat(compileAndRun("(print (eq *package* (find-package \"CL-USER\")))")).isEqualTo("T");
	}

	@Test
	void packageVarIsReadWhenTheFormRuns() throws Exception {
		// The JvmLispCompilerTest.compileAndRunPackageVarIsReadWhenTheFormRuns twin: a
		// dynamic *package* -- read at call time, assigned by in-package, let-bindable,
		// bound to CL-USER by with-standard-io-syntax -- in the rove registry shape.
		assertThat(compileAndRun(
				"""
						(defvar *suites* (make-hash-table :test 'eq))
						(defun package-suite (package) (or (gethash package *suites*) (setf (gethash package *suites*) (list :suite (string package)))))
						(defun set-test (name) (push name (cdr (package-suite *package*))) name)
						(defun cur () *package*)
						(defpackage :my-app/tests (:use :cl))
						(in-package :my-app/tests)
						(cl-user::set-test 'test-a)
						(cl-user::set-test 'test-b)
						(print (cl-user::cur))
						(print (let ((*package* (find-package :cl-user))) (cl-user::cur)))
						(print (cl-user::cur))
						(print (with-standard-io-syntax (cl-user::cur)))
						(print (gethash (find-package "MY-APP/TESTS") cl-user::*suites*))
						(print (eq *package* (find-package :my-app/tests)))
						(in-package :cl-user)
						(print (cur))
						(print (string *package*))
						"""))
			.isEqualTo("""
					:MY-APP/TESTS
					:CL-USER
					:MY-APP/TESTS
					:CL-USER
					(:SUITE MY-APP/TESTS::TEST-B MY-APP/TESTS::TEST-A "MY-APP/TESTS")
					T
					:CL-USER
					"CL-USER\"""");
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
				""";
		assertThat(compileAndRun(code)).isEqualTo("\"hello, world\"\n(2 4 6)\n42");
	}

	@Test
	void usePackageInheritsTheExternalSymbols() throws Exception {
		String code = """
				(defpackage :up-greeter (:use :cl) (:export :up-hello))
				(in-package :up-greeter)
				(defun up-hello () "hi")
				(in-package :cl-user)
				(use-package :up-greeter)
				(print (up-hello))
				""";
		assertThat(compileAndRun(code)).isEqualTo("\"hi\"");
	}

	@Test
	void argumentFormsEvaluateLeftToRight() throws Exception {
		// Common Lisp evaluates argument forms left to right. list (and everything that
		// lowers onto it -- backquote, make-array :initial-contents) LINKS its cons
		// chain from the last element backwards, and emitting the arguments in that
		// consumption order used to run the side effects right to left:
		// a `(:a ,(read-a) :b ,(read-b)) plist off a byte stream read its fields in
		// reverse. compiler.ArgumentOrder decides which arguments need the temp.
		assertThat(compileAndRun("""
				(defun noter (buf pos)
				  (lambda (x)
				    (setf (aref buf (aref pos 0)) x)
				    (setf (aref pos 0) (+ 1 (aref pos 0)))
				    x))
				(defparameter *buf* (make-array 3 :initial-element 0))
				(defparameter *pos* (make-array 1 :initial-element 0))
				(defparameter *note* (noter *buf* *pos*))
				(defun reset () (setf (aref *pos* 0) 0))
				(defun seen () (list (aref *buf* 0) (aref *buf* 1) (aref *buf* 2)))
				(reset)
				(print (list (funcall *note* 1) (funcall *note* 2) (funcall *note* 3)))
				(print (seen))
				(reset)
				(print `(,(funcall *note* 1) ,(funcall *note* 2) ,(funcall *note* 3)))
				(print (seen))
				(reset)
				(print (make-array 3 :initial-contents
				                   (list (funcall *note* 1) (funcall *note* 2) (funcall *note* 3))))
				(print (seen))
				""")).isEqualTo("""
				(1 2 3)
				(1 2 3)
				(1 2 3)
				(1 2 3)
				#(1 2 3)
				(1 2 3)""");
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
	void internIntoALiteralPackage() throws Exception {
		// 2-arg intern with a literal package designator lowers to the canonical-
		// spelling build the 2-arg find-symbol lowering already uses: an
		// exported name gets the single-colon external spelling (eq to the
		// source-quoted symbol) and resolves through the function registry; an
		// internal name resolves through the registry's single-colon alias row.
		// cl/cl-user need no qualifier.
		assertThat(compileAndRun("""
				(defpackage :rt-pkg (:use :cl) (:export :ex-fn))
				(in-package :rt-pkg)
				(defun ex-fn (x) (+ x 1))
				(defun in-fn (x) (+ x 2))
				(in-package :cl-user)
				(print (eq (intern "EX-FN" :rt-pkg) 'rt-pkg:ex-fn))
				(print (funcall (intern "EX-FN" :rt-pkg) 1))
				(print (funcall (intern "IN-FN" :rt-pkg) 1))
				(print (intern "foo" :cl-user))
				""")).isEqualTo("T\n2\n3\nfoo");
	}

	@Test
	void internIntoAComputedPackage() throws Exception {
		// clack's handler protocol is late-bound by name against a package VALUE:
		// (apply (intern (string :run) handler-package) ...) in handler.lisp, and
		// find-middleware's (symbol-value (intern (format ...) package)). The
		// computed designator is tested at run time like computedPackageFindSymbol.
		assertThat(compileAndRun("""
				(defpackage :rt-h (:use :cl) (:export :run :*mw*))
				(in-package :rt-h)
				(defun run (x) (concatenate 'string "run:" x))
				(defparameter *mw* "the-mw")
				(in-package :cl-user)
				(let ((pkg (find-package :rt-h)))
				  (print (apply (intern (string :run) pkg) (list "a")))
				  (print (symbol-value (intern (concatenate 'string "*" "MW*") pkg)))
				  (print (boundp (intern "*MW*" pkg))))
				(let ((kw (find-package :keyword)))
				  (print (intern "K2" kw)))
				""")).isEqualTo("\"run:a\"\n\"the-mw\"\nT\n:K2");
	}

	@Test
	void internIntoAnUnknownPackageSignalsAtCallTime() throws Exception {
		// find-symbol folds an unknown literal package to nil, but intern must SIGNAL
		// there (interpreter parity): the program COMPILES (a library merely
		// containing the form builds) and traps when the call is reached.
		String stderr = compileAndRunEhExpectTrap("(print (intern \"X\" :rt-no-such-pkg))");
		assertThat(stderr).contains("unreachable");
	}

	@Test
	void computedSymbolFunctionResolvesLate() throws Exception {
		// the jzon :key-fn shape: (funcall (symbol-function sym) x) with a runtime
		// symbol. The computed designator lowers to the symbol itself, which the
		// dispatchers resolve through the _lookup registry.
		assertThat(compileAndRun("(defun sf-f (x) (* x 2)) (setq s (intern \"SF-F\"))"
				+ "(print (funcall (symbol-function s) 21)) (print (funcall (fdefinition s) 5))"))
			.isEqualTo("42\n10");
	}

	@Test
	void uiopSymbolCall() throws Exception {
		// REAL on the compile paths (used to be a call-time error, see
		// .kb/asdf.md): lowers to (funcall (intern (string name) (find-package pkg))
		// args...), late-bound through the registry like the interpreter's
		// resolveFunction.
		assertThat(compileAndRun("(defpackage :usc-pkg (:use :cl) (:export :usc-fn))"
				+ "(in-package :usc-pkg) (defun usc-fn (a b) (+ a b)) (in-package :cl-user)"
				+ "(print (uiop:symbol-call :usc-pkg :usc-fn 40 2))" + "(print (uiop:symbol-call :cl :list 1 2))"))
			.isEqualTo("42\n(1 2)");
	}

	@Test
	void uiopSymbolCallAsAFirstClassValueOverUninternedDesignators() throws Exception {
		// The JVM twin is JvmLispCompilerTest
		// .compileAndRunUiopSymbolCallAsAFirstClassValueOverUninternedDesignators:
		// dexador's backend dispatch, where the operator is a VALUE and both
		// designators are uninterned symbols. #' needs uiop's own definition of
		// symbol-call (the fold only covers call position); '#:name needs the dispatch
		// gate to probe the "#:member" spelling. Run through the prelude
		// helper because that definition arrives by the SPLICE the CLI pipeline does
		// (LispPreludeLibrary.process drives UiopLibrary.process); the fold-only
		// uiopSymbolCall case above needs no splice and keeps the bare helper.
		assertThat(compileAndRunPrelude("(defpackage :dex-usocket (:use :cl) (:export :request))"
				+ "(in-package :dex-usocket) (defun request (uri &rest args) (list uri args))"
				+ "(in-package :cl-user) (defvar *backend* :usocket)"
				+ "(defun dex-request (uri &rest args) (ecase *backend*"
				+ "  (:usocket (apply #'uiop:symbol-call '#:dex-usocket '#:request uri args))"
				+ "  (:winhttp (apply #'uiop:symbol-call '#:dex-winhttp '#:request uri args))))"
				+ "(print (dex-request \"http://x\" :method :get))"
				+ "(print (uiop:symbol-call '#:dex-usocket '#:request \"u\"))"))
			.isEqualTo("(\"http://x\" (:METHOD :GET))\n(\"u\" NIL)");
	}

	@Test
	void applyOfAnUndefinedRuntimeSymbolTraps() throws Exception {
		// _apply's symbol-designator miss used to return nil SILENTLY; it must fail
		// loudly like the funcall dispatcher's miss arm (a trap -- the tree-shaker
		// carve-out contract: a shaken-out or unknown name errors, never silent
		// wrong output).
		String stderr = compileAndRunEhExpectTrap("(defun rt-a (x) x) (print (apply (intern \"RT-NOPE\") (list 1)))");
		assertThat(stderr).contains("unreachable");
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
	void findSymbolStatusSecondValue() throws Exception {
		// The same compile-time fold the JVM backend uses (LispMacroExpander), so the
		// status cannot depend on which backend compiled the call.
		assertThat(compileAndRun("(print (multiple-value-list (find-symbol \"CAR\" 'common-lisp)))"
				+ "(print (multiple-value-list (find-symbol \"NO-SUCH-NAME\" 'common-lisp)))"
				+ "(print (multiple-value-list (find-symbol \"CAR\")))"
				+ "(print (multiple-value-list (find-symbol \"FOO\" :keyword)))"
				+ "(print (multiple-value-list (intern \"CAR\" 'common-lisp)))"))
			.isEqualTo("(CAR :EXTERNAL)\n(NIL NIL)\n(CAR :INHERITED)\n(:FOO :EXTERNAL)\n(CAR :EXTERNAL)");
	}

	@Test
	void symbolPlistReadsTheWholePropertyList() throws Exception {
		// The prelude splice mirrors the CLI pipeline (symbol-plist and get are prelude
		// defuns, and (setf get) is the setf-function the place expansion needs).
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader
			.readAllFromString("(print (symbol-plist 'sp-none))(setf (get 'sp-x 'a) 1)(print (symbol-plist 'sp-x))"))))
			.isEqualTo("NIL\n(A 1)");
	}

	@Test
	void boundpChecksTheGlobalVariableNamespace() throws Exception {
		assertThat(compileAndRun("(defvar *bp-var* 1) (print (boundp '*bp-var*)) (print (boundp '*bp-nope*))"
				+ "(print (boundp :kw)) (print (boundp t)) (print (boundp nil))"
				+ "(let ((lex 1)) (print (boundp 'lex)))"))
			.isEqualTo("T\nNIL\nT\nT\nT\nNIL");
	}

	@Test
	void aLiteralBoundpCostsNothingWhileAComputedOneStillCarriesTheEvalRuntime() {
		// A probe the top-level order decides compiles to exactly the module its ANSWER
		// compiles to (compiler/CompileTimeBoundp), so the eval runtime the boundp arm of
		// the usesEval OR-chain would have pulled in is not there at all.
		byte[] probed = new WasmLispCompiler().compile(
				LispReader.readAllFromString("(defvar *bpv* 1) (print (boundp '*bpv*)) (print (boundp '*bp-nope*))"));
		byte[] answered = new WasmLispCompiler()
			.compile(LispReader.readAllFromString("(defvar *bpv* 1) (print t) (print nil)"));
		assertThat(probed).isEqualTo(answered);
		// Same for the portable define-constant guard, whose collapse also has to leave
		// the definition behind as a plain top-level definer.
		byte[] guarded = new WasmLispCompiler()
			.compile(LispReader.readAllFromString("(unless (boundp '+bpk+) (defconstant +bpk+ 5)) (print +bpk+)"));
		byte[] bare = new WasmLispCompiler()
			.compile(LispReader.readAllFromString("(defconstant +bpk+ 5) (print +bpk+)"));
		assertThat(guarded).isEqualTo(bare);
		// A genuinely computed designator has to be resolved at run time, so the same
		// program keeps the runtime. What that is worth is read off the SHAKEN modules,
		// where nothing else is left to hide it: 21,800 bytes against 529.
		byte[] computed = new WasmLispCompiler(false, false, false, OptimizeLevel.SIZE)
			.compile(LispReader.readAllFromString("(defconstant +bpk+ 5) (print (boundp (intern \"+BPK+\")))"));
		byte[] literal = new WasmLispCompiler(false, false, false, OptimizeLevel.SIZE)
			.compile(LispReader.readAllFromString("(defconstant +bpk+ 5) (print t)"));
		assertThat(computed.length).isGreaterThan(literal.length * 10);
	}

	@Test
	void symbolValueReadsTheGlobalVariableNamespace() throws Exception {
		assertThat(compileAndRun("(defvar *sv-var* 42) (print (symbol-value '*sv-var*))"
				+ "(setq *sv-var2* 7) (print (symbol-value (intern \"*SV-VAR2*\")))"
				+ "(print (symbol-value :kw)) (print (symbol-value t)) (print (symbol-value nil))"))
			.isEqualTo("42\n7\n:KW\nT\nNIL");
	}

	@Test
	void standardStreamVariablesAreBoundToTheirDefaultsThroughTheSymbolApi() throws Exception {
		// The two homes of a special -- the module global a direct read uses and the eval
		// runtime's GLOBAL_ENV mirror symbol-value/boundp probe -- seed from one table,
		// so the three stream variables answer here exactly what the interpreter does
		// (before this, symbol-value missed the mirror and TRAPPED).
		assertThat(compileAndRun("(print (boundp '*error-output*)) (print (symbol-value '*error-output*))"
				+ "(print (boundp '*standard-output*)) (print (symbol-value '*standard-output*))"
				+ "(print (boundp '*standard-input*)) (print (symbol-value '*standard-input*))"))
			.isEqualTo("T\n#<STREAM :HANDLE 2 :KIND :STANDARD>\nT\nT\nT\nT");
	}

	@Test
	void standardStreamVariablesResolveThroughAComputedSymbolAndAfterAssignment() throws Exception {
		// lack's backtrace middleware carries the SYMBOL '*error-output* in a variable,
		// reporting through (symbol-value output). A top-level assignment must still
		// win over the seeded default: the seed IS the binding cell _store mutates.
		assertThat(compileAndRun("(defvar *sv-stream-name* '*error-output*)"
				+ "(print (boundp *sv-stream-name*)) (print (symbol-value *sv-stream-name*))"
				+ "(setq *error-output* 7) (print (symbol-value '*error-output*))"))
			.isEqualTo("T\n#<STREAM :HANDLE 2 :KIND :STANDARD>\n7");
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
	void preview1ExportResolvesTheFutureItsTargetAnswers() throws Exception {
		// A wasm-export whose target answers a future -- the settled one a degenerate
		// async body produces here -- used to unbox it as the declared scalar and trap
		// with `illegal cast` on the very first call, so the only working spelling was
		// an explicit rontolisp::%future-force inside the target. The wrapper resolves
		// it now, the courtesy the --component wrapper's poll already extended. The
		// resolve is dynamic, so the PASS-THROUGH shape -- a plain defun handing back
		// an async function's future -- is covered by the same instruction.
		String direct = """
				(rontolisp:async-defun probe (n) (+ n 100))
				(rontolisp:wasm-export 'probe :params '(:int) :returns :int)
				""";
		String awaited = """
				(rontolisp:async-defun inner (n) (+ n 100))
				(rontolisp:async-defun probe (n) (rontolisp:await (inner n)))
				(rontolisp:wasm-export 'probe :params '(:int) :returns :int)
				""";
		String passThrough = """
				(rontolisp:async-defun inner (n) (+ n 100))
				(defun probe (n) (inner n))
				(rontolisp:wasm-export 'probe :params '(:int) :returns :int)
				""";
		for (String program : List.of(direct, awaited, passThrough)) {
			assertThat(compileAndInvoke(program, "probe", "7")).isEqualTo("107");
			assertThat(compileNoWasiAndInvoke(program, "probe", "7")).isEqualTo("107");
			assertThat(compileOptimizedAndInvoke(program, false, "probe", "7")).isEqualTo("107");
			assertThat(compileOptimizedAndInvoke(program, true, "probe", "7")).isEqualTo("107");
		}
	}

	// Compiles an asyncMode --component program (async surface forces EH mode) and
	// invokes a component-model export by its WAVE signature.
	private static String compileAsyncComponentAndInvoke(String lispCode, String invocation) throws Exception {
		byte[] component = new WasmLispCompiler(false, true).compile(LispReader.readAllFromString(lispCode));
		wasmtime.copyFileToContainer(Transferable.of(component), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--invoke",
				invocation, path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		return result.getStdout().trim();
	}

	@Test
	void componentExportResolvesTheFutureItsTargetAnswers() throws Exception {
		// asyncMode's counterpart of preview1ExportResolvesTheFutureItsTargetAnswers:
		// the wrapper's poll / _sched_loop branch used to be keyed on the TARGET being
		// an async-defun, so a plain defun handing back someone else's future was
		// unboxed as the declared scalar and trapped with `cast failure` on the very
		// first call. The poll block is fully dynamic (a non-future passes through),
		// so it now runs on every asyncMode export. The extra leading body form keeps
		// `inner` off the fusion-inline fast path independently of the async gate.
		String direct = """
				(rontolisp:async-defun probe (n) (+ n 100))
				(rontolisp:wasm-export 'probe :params '(:int) :returns :int)
				""";
		String awaited = """
				(rontolisp:async-defun inner (n) (+ n 100))
				(rontolisp:async-defun probe (n) (rontolisp:await (inner n)))
				(rontolisp:wasm-export 'probe :params '(:int) :returns :int)
				""";
		String passThrough = """
				(rontolisp:async-defun inner (n) n (+ n 100))
				(defun probe (n) (inner n))
				(rontolisp:wasm-export 'probe :params '(:int) :returns :int)
				""";
		String passThroughOneForm = """
				(rontolisp:async-defun inner (n) (+ n 100))
				(defun probe (n) (inner n))
				(rontolisp:wasm-export 'probe :params '(:int) :returns :int)
				""";
		for (String program : List.of(direct, awaited, passThrough, passThroughOneForm)) {
			assertThat(compileAsyncComponentAndInvoke(program, "probe(7)")).isEqualTo("107");
		}
	}

	@Test
	void componentAsyncDefunKeepsItsFutureThroughASyncCaller() throws Exception {
		// A one-form async-defun's rewritten plain defun used to be collected as
		// fusion-inlinable (its body is a closed integer tree), so a synchronous
		// caller spliced the raw body -- bypassing the entry+resume state machine --
		// and futurep answered NIL where every other backend answers T.
		String program = """
				(rontolisp:async-defun inner (n) (+ n 100))
				(defun probe (n) (if (rontolisp:futurep (inner n)) 1 0))
				(rontolisp:wasm-export 'probe :params '(:int) :returns :int)
				""";
		assertThat(compileAsyncComponentAndInvoke(program, "probe(7)")).isEqualTo("1");
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
		// %future-new/%future-settle test primitives stand in for the import layer as
		// the pending-future source.
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
	void guestCreatedStreamsStayACompileErrorInPreview1Mode() throws Exception {
		assertThatThrownBy(() -> compileAndRun("(print (rontolisp:make-stream))"))
			.hasMessageContaining("guest-created streams are not available on the WASM backends yet");
		// A module that never names %stream-new can hold no stream value at all, so
		// stream-read/stream-close compile to CALL-time error stubs (the
		// clack-handler-rontolisp bridge carries a body drain that is dead code there):
		// the message is handler-case-catchable, and an uncaught one is the usual silent
		// trap. streamp is NOT one of them -- nothing being a stream is an answer, not a
		// failure, so it is the constant nil.
		assertThat(compileAndRun("(print (handler-case (rontolisp:stream-read 1) (error (e) (princ-to-string e))))"))
			.contains("requires a stream value, and this module can hold none");
		assertThat(compileAndRun("(print (rontolisp:streamp 1)) (print (rontolisp:streamp \"x\"))"))
			.isEqualTo("NIL\nNIL");
		assertThatThrownBy(() -> compileAndRun("(defun bad () (rontolisp:await 1))"))
			.hasMessageContaining("only allowed inside");
	}

	@Test
	void preview1HasAFirstClassStreamValueOverAPairOfThunks() throws Exception {
		// The degenerate tier's TYPE_P1_STREAM: rontolisp::%stream-new over a read thunk
		// and a close thunk, drained the portable way. Nothing here can suspend, so every
		// stream-read answers a SETTLED future -- but the surface is the one every other
		// backend has: streamp -> T, the prelude read-all concatenates the chunks, the
		// close protocol runs exactly once at EOF, and a read past EOF is nil.
		assertThat(compileAndRunCombinatorsP1("""
				(defvar *chunks* '("ab" "cd"))
				(defvar *closes* 0)
				(defun next-chunk ()
				  (if *chunks*
				      (let ((c (car *chunks*))) (setq *chunks* (cdr *chunks*)) c)
				      nil))
				(defvar *s* (rontolisp::%stream-new #'next-chunk
				                                    (lambda () (setq *closes* (+ *closes* 1)) nil)))
				(print (rontolisp:streamp *s*))
				(print (rontolisp:streamp 42))
				(print *s*)
				(print (rontolisp:await (rontolisp:read-all *s*)))
				(print *closes*)
				(print (rontolisp:await (rontolisp:stream-read *s*)))
				(rontolisp:stream-close *s*)
				(print *closes*)
				""")).isEqualTo("""
				T
				NIL
				#<STREAM>
				"abcd"
				1
				NIL
				1""");
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
	// section on both variants).
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
	void anOptimizedComponentFailsAsLoudlyAsAPlainOneOnAnFdItCannotServe() throws Exception {
		// Under --optimize the adapter's STDIO-ONLY fd_write is retained under the name
		// fd_write, on the strength of "no path_open means no file fd". A SOCKET fd (>=
		// 200)
		// is the other thing that can arrive there, when a write form escapes
		// WasmSocketsRewrite's dispatch table (`format` is one such form today). The wide
		// implementation walked off the fd table and trapped inside the host; the narrow
		// one
		// must NOT quietly divert those bytes to stderr and report success, or --optimize
		// would turn a loud failure into a silent protocol desync.
		String program = """
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "127.0.0.1" port)))
				  (format client "not through the dispatch table~%")
				  (print :unreachable))
				""";
		for (OptimizeLevel level : List.of(OptimizeLevel.NONE, OptimizeLevel.DEFAULT)) {
			byte[] componentBytes = compileFetchComponent(program, level);
			wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("fd-contract.component.wasm"));
			ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S",
					"tcp=y", "-S", "inherit-network=y", path("fd-contract.component.wasm"));
			assertThat(result.getExitCode())
				.as("%s must fail rather than succeed; stdout: %s", level, result.getStdout())
				.isNotZero();
			assertThat(result.getStdout()).as("%s must not report success", level).doesNotContain("UNREACHABLE");
		}
	}

	@Test
	void componentTcpBinaryBytesAreWireTransparent() throws Exception {
		// The socket chunk cursor walks BYTES, not UTF-8 characters (the root
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
	void componentTcpBareReadCharSignalsAtPeerClose() throws Exception {
		// A bare (read-char sock) / (read-byte sock) after peer close carries CL's
		// default eof-error-p t, so it SIGNALS end-of-file -- the same contract the
		// interpreter and the JVM have
		// (LispEvaluatorTest#tcpReadCharAtPeerCloseHonoursTheEofArguments,
		// JvmLispCompilerTest#compileAndRunTcpReadCharAtPeerCloseHonoursTheEofArguments)
		// and the same the component's own non-socket designators have. The socket
		// reads underneath still answer nil at EOF -- the eof test lives in the two
		// dispatch entries the bare shape reaches, the sync %io-read-char and the
		// async-promoted %read-char-future -- so BOTH contexts are pinned here: a
		// defun body (sync dispatch) and the async top level (await promotion).
		// read-line is the read that keeps answering nil at peer close, because
		// rontolisp's read-line defaults eof-error-p to nil.
		String program = """
				(defun run-sync ()
				  (let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				         (port (rontolisp:tcp-local-port listener))
				         (client (rontolisp:tcp-connect "127.0.0.1" port))
				         (server (rontolisp:tcp-accept listener)))
				    (close client)
				    (print (read-char server nil :eof))
				    (print (handler-case (read-char server)
				             (end-of-file () :signalled)
				             (error (e) :other)))
				    (print (handler-case (read-byte server)
				             (end-of-file () :signalled)
				             (error (e) :other)))
				    (print (read-line server))
				    (close server)
				    (close listener)))
				(run-sync)
				(let* ((l2 (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (c2 (rontolisp:tcp-connect "127.0.0.1" (rontolisp:tcp-local-port l2)))
				       (s2 (rontolisp:tcp-accept l2)))
				  (close c2)
				  (print (read-char s2 nil :eof))
				  (print (handler-case (read-char s2)
				           (end-of-file () :signalled)
				           (error (e) :other)))
				  (print (handler-case (read-byte s2)
				           (end-of-file () :signalled)
				           (error (e) :other)))
				  (print (read-line s2))
				  (close s2)
				  (close l2))
				""";
		byte[] componentBytes = compileFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/tcp-readchar-eof.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S",
				"tcp=y", "-S", "inherit-network=y", "/tmp/tcp-readchar-eof.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim())
			.isEqualTo(":EOF\n:SIGNALLED\n:SIGNALLED\nNIL\n:EOF\n:SIGNALLED\n:SIGNALLED\nNIL");
	}

	@Test
	void componentTcpGrayStreamsFallThroughShapesReachTheSocketDispatch() throws Exception {
		// GrayStreamsLibrary.process runs BEFORE the component socket rewrite
		// and normalizes every possibly-CLOS-instance stream call site onto its
		// %gray-*-dispatch helper, whose non-instance fall-through arm re-spells the
		// built-in with EVERY optional argument filled in:
		// (write-sequence seq s :start a :end b), (read-line s eof-error-p eof-value),
		// ... None of those shapes was in the socket rewrite's table, so on a socket
		// handle they compiled to the NATIVE built-ins, whose fd_read/fd_write on a
		// socket fd (>= 200) walks off the preview1 adapter's 64-slot fd table --
		// cl-postgres' (write-sequence bytes socket) died mid-message with "unknown
		// handle index 0". The `defclass ... fundamental-character-output-stream` here is
		// what makes the program a Gray-protocol one, so the socket calls below all
		// arrive at the dispatch helpers exactly as they do in a quickloaded driver.
		String program = """
				(defclass sink (rontolisp:fundamental-character-output-stream) ())
				(defmethod rontolisp:stream-write-string ((s sink) str) str)
				(defun run-sync (client server)
				  (write-sequence (vector 1 2 250 4) client)
				  (let ((buf (make-array 4 :initial-element 0)))
				    (read-sequence buf server)
				    (print (list (aref buf 0) (aref buf 1) (aref buf 2) (aref buf 3))))
				  (write-sequence (vector 9 8 7 6) client :start 1 :end 3)
				  (let ((buf (make-array 4 :initial-element 0)))
				    (read-sequence buf server :start 1 :end 3)
				    (print (list (aref buf 0) (aref buf 1) (aref buf 2) (aref buf 3))))
				  (write-char #\\Z client)
				  (print (read-char server nil :eof))
				  (write-string "0123456789" client :start 2 :end 5)
				  (write-line "" client)
				  (print (read-line server nil :eof))
				  (print (write-string "instance" (make-instance 'sink))))
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (client (rontolisp:tcp-connect "127.0.0.1" (rontolisp:tcp-local-port listener)))
				       (server (rontolisp:tcp-accept listener)))
				  (run-sync client server)
				  (close client)
				  (print (read-line server nil :eof))
				  (print (read-char server nil :eof))
				  (close server)
				  (close listener))
				""";
		byte[] componentBytes = compileGrayFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/tcp-gray.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S",
				"tcp=y", "-S", "inherit-network=y", "/tmp/tcp-gray.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim())
			.isEqualTo("(1 2 250 4)\n(0 8 7 0)\n#\\Z\n\"234\"\n\"instance\"\n:EOF\n:EOF");
	}

	@Test
	void usocketHostToHostnameRendersEveryDesignatorShape() throws Exception {
		// The clack.handler:run address-normalization pair, spliced like the CLI
		// pre-pass does. Pure Lisp in the shim and it opens no socket, so it answers on
		// Preview 1 too -- and must answer exactly what the interpreter and the JVM do.
		String output = compileAndRunProgram(
				am.ik.rontolisp.eval.UsocketLibrary.process(LispReader.readAllFromString("""
						(print (usocket:host-to-hostname nil))
						(print (usocket:host-to-hostname #(192 168 0 1)))
						(print (usocket:host-to-hostname 2130706433))
						(print (usocket:host-to-hostname (usocket:get-host-by-name "example.com")))
						""")));
		assertThat(output).isEqualTo("\"0.0.0.0\"\n\"192.168.0.1\"\n\"127.0.0.1\"\n\"example.com\"");
	}

	@Test
	void printConditionBacktracePrintsTheCondition() throws Exception {
		// The prelude entry defines the uiop/image symbol and the uiop package imports
		// the name, so both spellings a library may use select the one definition.
		String output = compileAndRunProgram(
				am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
						(handler-case (error "boom")
						  (error (c) (uiop/image:print-condition-backtrace c :stream *standard-output*)))
						(handler-case (error "boom2")
						  (error (c) (uiop:print-condition-backtrace c :stream *standard-output*)))
						""")));
		assertThat(output).isEqualTo("boom\nboom2");
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
		List<LispVal> spliced = am.ik.rontolisp.eval.WitLibrary.process(
				am.ik.rontolisp.eval.StdinLibrary.process(
						am.ik.rontolisp.eval.SocketsLibrary.process(
								am.ik.rontolisp.eval.WaitForLibrary.process(
										am.ik.rontolisp.eval.UsocketLibrary
											.process(LispReader.readAllFromString(program)),
										am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT),
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
	void componentUsocketSocketOptionRefusesAndWaitForInputClaimsReadiness() throws Exception {
		// The two socket decisions on this backend (.kb/tcp-sockets.md): a
		// :receive-timeout write SIGNALS loudly and catchably (wasi:sockets has no
		// read deadline; a timeout that never fires is worse than an error, so the
		// refusal leaves no bookkeeping behind), and wait-for-input returns
		// immediately claiming readiness (reads block anyway, so the wait-then-read
		// loop behaves like the interpreter/JVM; a listen poll would spin forever on
		// data waiting host-side).
		String program = """
				(let* ((listener (usocket:socket-listen "127.0.0.1" 0))
				       (port (usocket:get-local-port listener))
				       (client (usocket:socket-connect "127.0.0.1" port)))
				  (print (handler-case (setf (usocket:socket-option client :receive-timeout) 0.2)
				           (error (e) :refused)))
				  (print (usocket:socket-option client :receive-timeout))
				  (let ((server (usocket:socket-accept listener)))
				    (multiple-value-bind (ready remaining)
				        (usocket:wait-for-input (list client) :timeout 5 :ready-only t)
				      (print (if ready :claimed :not-ready)))
				    (print (eql (usocket:wait-for-input client) client))
				    (write-line "ping" server)
				    (print (read-line client))
				    (usocket:socket-close server))
				  (usocket:socket-close client)
				  (usocket:socket-close listener))
				""";
		List<LispVal> spliced = am.ik.rontolisp.eval.WitLibrary.process(
				am.ik.rontolisp.eval.StdinLibrary.process(
						am.ik.rontolisp.eval.SocketsLibrary.process(
								am.ik.rontolisp.eval.WaitForLibrary.process(
										am.ik.rontolisp.eval.UsocketLibrary
											.process(LispReader.readAllFromString(program)),
										am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT),
								am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT),
						am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT, false));
		byte[] componentBytes = new WasmLispCompiler(false, true).compile(spliced);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/usocket-option.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S",
				"tcp=y", "-S", "inherit-network=y", "/tmp/usocket-option.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo(":REFUSED\nNIL\n:CLAIMED\nT\n\"ping\"");
	}

	@Test
	void componentAsyncStdinReadDoesNotStallTheInstance() throws Exception {
		// The stdin migration's promotion goal: a pending stdin read in an async body
		// suspends the task, so a concurrent timer fires while the pipe is still empty.
		// The preview1 adapter's blocking stdin branch could never do this -- it parks
		// the whole instance.
		//
		// The feeder is driven by the guest's own output, not by a wall clock: a FIFO
		// held open with nothing in it is stdin, and "hello" is written only once
		// "timer fired" has appeared on stdout. That makes the expected order the only
		// possible one when the read suspends, and unreachable when it does not -- a
		// sleep-vs-cold-start race could invert it whenever the runner was loaded
		// enough for the line to be buffered before the body reached read-line. A
		// stalled instance never prints the marker, so the poll times out and reports
		// it rather than passing on a lucky interleaving.
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
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), path("stdin-async.component.wasm"));
		// Opening the FIFO read-write keeps a writer attached for the whole run, so
		// wasmtime's own open of it for reading returns at once and never sees EOF.
		ExecResult result = wasmtime.execInContainer("sh", "-c", """
				cd %s || exit 1
				rm -f stdin-async.fifo stdin-async.out
				mkfifo stdin-async.fifo && : > stdin-async.out || exit 1
				exec 3<> stdin-async.fifo
				wasmtime run -W gc=y -W exceptions=y stdin-async.component.wasm < stdin-async.fifo > stdin-async.out &
				runner=$!
				polls=0
				until grep -q 'timer fired' stdin-async.out; do
				  polls=$((polls + 1))
				  if [ $polls -gt 300 ]; then
				    echo 'TIMED OUT waiting for the timer while the stdin read was pending'
				    kill $runner 2>/dev/null
				    break
				  fi
				  sleep 0.1
				done
				echo hello >&3
				wait $runner
				status=$?
				cat stdin-async.out
				exit $status
				""".formatted(workDir()));
		assertThat(result.getExitCode()).as("stdout: %s stderr: %s", result.getStdout(), result.getStderr()).isZero();
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
	void componentAsyncStdinBareReadCharSignalsTheEndOfFileClass() throws Exception {
		// The stdin arm of the same bare-read-char contract the socket arm has
		// (componentTcpBareReadCharSignalsAtPeerClose): at EOF it signals, and with the
		// end-of-file CLASS -- a look-alike message would leave handler-case unable to
		// catch what the interpreter and the JVM let it catch.
		String program = """
				(rontolisp:async-defun main ()
				  (print (read-char))
				  (print (handler-case (read-char)
				           (end-of-file () :signalled)
				           (error (e) :other))))
				(rontolisp:await (main))
				""";
		byte[] componentBytes = compileFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/stdin-readchar-eof.component.wasm");
		ExecResult result = wasmtime.execInContainer("sh", "-c",
				"printf 'A' | wasmtime run -W gc=y -W exceptions=y /tmp/stdin-readchar-eof.component.wasm");
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo("#\\A\n:SIGNALLED");
	}

	@Test
	void componentNonAsyncStdinKeepsTheAdapterPathAndItsFlags() throws Exception {
		// The byte-stability contract at run level: a synchronous stdin program is
		// NOT migrated -- it keeps the preview1 adapter's stdin branch, so it does not
		// compile in EH mode.
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
	void componentTlsUpgradeAttemptsARealHandshakeAndRejectsAnUntrustedServer() throws Exception {
		// The deterministic in-container leg of the wasi:tls@0.3.0-draft support: an
		// in-container `openssl s_server` presents a fresh self-signed certificate,
		// and wasmtime's TLS provider (rustls over the compiled-in webpki/Mozilla
		// roots -- there is no CA-file knob) rejects it, so tls-upgrade answers nil
		// (the WASM error convention). This drives the WHOLE pipeline for real --
		// the wasi:tls instance imports link (-S tls=y), the connector transforms
		// wrap the socket's streams, the async handshake runs a genuine
		// ClientHello/ServerHello exchange and the error arm releases the error
		// resource. A trusted-path success E2E cannot be run against a local
		// fixture (the host trust store is compiled in); that leg is the opt-in
		// componentTlsFetchesARealHostOverHttps below.
		String program = """
				(let ((s (rontolisp:tcp-connect "127.0.0.1" 14443)))
				  (print (rontolisp:tls-upgrade s "localhost"))
				  (close s))
				(print (rontolisp:tls-connect "127.0.0.1" 14443))
				(print (handler-case (rontolisp:tls-upgrade 999 "h" :insecure t)
				         (error () :insecure-signals)))
				(print (rontolisp:tls-upgrade 999 "h"))
				""";
		byte[] componentBytes = compileFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/tls-reject.component.wasm");
		ExecResult result = wasmtime.execInContainer("sh", "-c", """
				cd /tmp || exit 1
				openssl req -x509 -newkey rsa:2048 -nodes -keyout tls-reject-key.pem \
				  -out tls-reject-cert.pem -days 1 -subj "/CN=localhost" 2>/dev/null || exit 1
				openssl s_server -accept 14443 -cert tls-reject-cert.pem -key tls-reject-key.pem \
				  -quiet > /dev/null 2>&1 &
				server=$!
				sleep 0.5
				wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y -S tls=y \
				  tls-reject.component.wasm
				status=$?
				kill $server 2>/dev/null
				exit $status
				""");
		assertThat(result.getExitCode()).as("stdout: %s stderr: %s", result.getStdout(), result.getStderr()).isZero();
		// Line 1: the handshake against the untrusted cert fails -> nil. Line 2:
		// tls-connect (tcp-connect + tls-upgrade) fails the same way. Line 3: a
		// non-nil :insecure SIGNALS at run time (the draft has no verification
		// knob; silently verifying would betray the caller). Line 4: a non-socket
		// handle answers nil.
		assertThat(result.getStdout().trim()).isEqualTo("NIL\nNIL\n:INSECURE-SIGNALS\nNIL");
	}

	@Test
	@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RONTOLISP_HTTP_E2E", matches = "1")
	void componentTlsFetchesARealHostOverHttps() throws Exception {
		// The trusted success path needs a certificate wasmtime's compiled-in webpki
		// roots accept, i.e. a real public host -- opt-in like the other network
		// E2Es. 1.1.1.1's certificate carries its IP as a SAN, so the IP-literal
		// tcp-connect (hostname lookup is not implemented) verifies cleanly.
		String program = """
				(let ((s (rontolisp:tcp-connect "1.1.1.1" 443)))
				  (let ((tls (rontolisp:tls-upgrade s "1.1.1.1")))
				    (write-line "GET / HTTP/1.1" tls)
				    (write-line "Host: 1.1.1.1" tls)
				    (write-line "Connection: close" tls)
				    (write-line "" tls)
				    (print (read-line tls))
				    (close tls)))
				""";
		byte[] componentBytes = compileFetchComponent(program);
		wasmtime.copyFileToContainer(Transferable.of(componentBytes), "/tmp/tls-real.component.wasm");
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S",
				"tcp=y", "-S", "inherit-network=y", "-S", "tls=y", "/tmp/tls-real.component.wasm");
		assertThat(result.getExitCode()).as("stdout: %s stderr: %s", result.getStdout(), result.getStderr()).isZero();
		assertThat(result.getStdout()).contains("HTTP/1.1");
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
		// Echoes the received User-Agent: the component has no HTTP client of its own to
		// default the field, so a caller-silent request used to go out with none at all
		// (and origins that reject agent-less traffic answered it with a 4xx). It carries
		// FetchResponseShape.defaultUserAgent() now, the same string the interpreter and
		// the JVM send (LispEvaluatorTest#fetchSendsADefaultUserAgent).
		server.createContext("/agent", exchange -> {
			java.util.List<String> received = exchange.getRequestHeaders().get("User-Agent");
			byte[] body = String.join("|", (received == null) ? java.util.List.<String>of() : received)
				.getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
			String agent = base + "/agent";
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
					+ "   (print (getf (rontolisp:await p1) :status)))"
					// the request we send on the caller's behalf, and the caller's own
					// spelling of it winning (HTTP field names are case-insensitive)
					+ " (print (rontolisp:await (rontolisp:read-all (getf (rontolisp:await (rontolisp:fetch \"" + agent
					+ "\")) :body))))"
					+ " (print (rontolisp:await (rontolisp:read-all (getf (rontolisp:await (rontolisp:fetch \"" + agent
					+ "\" (list :headers (list (cons \"user-agent\" \"custom/1\"))))) :body))))";
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
				.contains("\"POST:one\"")
				.contains("\"" + am.ik.rontolisp.compiler.FetchResponseShape.defaultUserAgent() + "\"")
				.contains("\"custom/1\"");
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
				"Unknown character name after #\\\\"
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
	void compileHashTablePrintsAsUnreadableTagWithCount() throws Exception {
		// A table is the non-array shape of the TYPE_CELL box; before it had a printer
		// arm
		// it fell into the cons tail, which re-entered the printer on the same value and
		// trapped with "call stack exhausted", losing the buffered stdout with it
		// (todo 430). It prints the interpreter's unreadable tag instead, with the LIVE
		// ENTRY COUNT read from the header car -- the same i31 hash-table-count reads.
		assertThat(compileAndRun("""
				(defparameter *h* (make-hash-table :test 'equal))
				(setf (gethash "a" *h*) 1)
				(setf (gethash "b" *h*) 2)
				(princ *h*)
				(terpri)
				(prin1 *h*)
				(terpri)
				(format t "~a ~s~%" *h* *h*)
				(print (list *h*))
				(remhash "a" *h*)
				(print *h*)
				""")).isEqualTo("""
				#<HASH-TABLE :TEST EQUAL :COUNT 2>
				#<HASH-TABLE :TEST EQUAL :COUNT 2>
				#<HASH-TABLE :TEST EQUAL :COUNT 2> #<HASH-TABLE :TEST EQUAL :COUNT 2>
				(#<HASH-TABLE :TEST EQUAL :COUNT 2>)
				#<HASH-TABLE :TEST EQUAL :COUNT 1>""");
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
	// A key is placed by a DEPTH-CAPPED structural hash and decided by equal, not by
	// the key's printed text: a cyclic key stores and retrieves (printing one never
	// terminated), two equal keys deeper than the cap are still ONE key, and a general
	// vector key is compared by identity -- which is what equal does to a vector.
	void compileHashTableCyclicDeepAndVectorKeys() throws Exception {
		assertThat(compileAndRun("""
				(let ((h (make-hash-table)) (k (list 1 2)))
				  (setf (cdr (last k)) k)
				  (setf (gethash k h) :cyclic)
				  (print (list (gethash k h) (hash-table-count h))))
				(let ((h (make-hash-table)) (deep (loop for i from 1 to 200 collect i)))
				  (setf (gethash deep h) :deep)
				  (print (list (gethash (loop for i from 1 to 200 collect i) h)
				               (gethash (loop for i from 1 to 199 collect i) h))))
				(let ((h (make-hash-table)) (v (vector 1 2)))
				  (setf (gethash v h) :self)
				  (print (list (gethash v h) (gethash (vector 1 2) h))))
				""")).isEqualTo("(:CYCLIC 1)\n(:DEEP NIL)\n(:SELF NIL)");
	}

	@Test
	// The depth cap bounds the hash's HEIGHT and nothing about its SIZE, which a WORK
	// budget does. Both keys here have SHARED substructure: a DAG of 60 conses holds 60
	// cells and 2^60 root-to-leaf paths, and an instance that knows its parent is worse
	// still. An un-budgeted hash could not place either one inside this suite's lifetime,
	// so a regression shows up as a HANG rather than as a slow number -- the only way to
	// pin "terminates SOON" without a flaky wall-clock assertion.
	void compileHashTableSharedGraphKeysArePlacedInBoundedWork() throws Exception {
		assertThat(compileAndRun("""
				(defun shared-dag (n)
				  (let ((node :leaf))
				    (dotimes (i n node) (setq node (cons node node)))))
				(defclass linked-node ()
				  ((up :initarg :up :initform nil)
				   (down :initform nil :accessor node-down)))
				(defun linked-chain (n)
				  (let ((node (make-instance 'linked-node)))
				    (dotimes (i n node)
				      (let ((next (make-instance 'linked-node :up node)))
				        (setf (node-down node) (cons next (node-down node)))
				        (setq node next)))))
				(let ((h (make-hash-table :test 'equal))
				      (dag (shared-dag 60))
				      (node (linked-chain 8)))
				  (setf (gethash dag h) :dag)
				  (setf (gethash node h) :node)
				  (print (list (gethash dag h) (gethash node h) (hash-table-count h))))
				(let ((e (make-hash-table :test 'eq)))
				  (print (list (gethash (shared-dag 60) e) (gethash (linked-chain 8) e))))
				(let ((p (make-hash-table :test 'equalp)))
				  (setf (gethash "cs" p) 1)
				  (print (list (gethash "CS" p) (gethash (shared-dag 60) p))))
				""")).isEqualTo("(:DAG :NODE 2)\n(NIL NIL)\n(1 NIL)");
	}

	@Test
	// An equalp table places its keys by the equalp FOLD -- upper case for a string and a
	// character, the integer it equals for a float, element-wise for a cons -- so the
	// four backends agree on which keys are one key (.kb/hash-tables.md). The table's
	// test rides in the low bit of the header count, so the count read back past it, the
	// clrhash that keeps the test, and hash-table-p all still answer.
	void compileEqualpHashTableFoldsItsKeys() throws Exception {
		assertThat(compileAndRun("""
				(let ((h (make-hash-table :test 'equalp)))
				  (setf (gethash "CS" h) 1)
				  (print (list (gethash "Cs" h) (gethash "cs" h) (hash-table-count h))))
				(let ((h (make-hash-table :test 'equalp)))
				  (setf (gethash 1 h) :one)
				  (setf (gethash #\\a h) :a)
				  (setf (gethash (list "x" 2) h) :pair)
				  (print (list (gethash 1.0 h) (gethash 2/2 h) (gethash #\\A h)
				               (gethash (list "X" 2.0) h) (hash-table-count h))))
				(let ((h (make-hash-table :test 'equal)))
				  (setf (gethash "CS" h) 1)
				  (print (list (gethash "Cs" h) (hash-table-count h) (hash-table-p h))))
				(let ((h (make-hash-table :test 'equalp)))
				  (setf (gethash "a" h) 1)
				  (remhash "A" h)
				  (clrhash h)
				  (setf (gethash "b" h) 2)
				  (print (list (gethash "B" h) (hash-table-count h) (hash-table-test h))))
				(let ((h (make-hash-table :test 'equalp)) (acc nil))
				  (setf (gethash "cs" h) 1)
				  (maphash (lambda (k v) (setq acc (cons k acc))) h)
				  (print acc))
				""")).isEqualTo("(1 1 1)\n(:ONE :ONE :A :PAIR 3)\n(NIL 1 T)\n(2 1 EQUALP)\n(\"CS\")");
	}

	@Test
	// The printed :TEST field and hash-table-test report the test lookup implements, now
	// that a table knows whether it folds.
	void compileEqualpHashTablePrintsAndReportsItsTest() throws Exception {
		assertThat(compileAndRun("""
				(let ((p (make-hash-table :test 'equalp)) (q (make-hash-table :test 'equal)))
				  (setf (gethash "a" p) 1)
				  (print (list (hash-table-test p) (hash-table-test q)))
				  (princ p)
				  (terpri)
				  (princ q))
				"""))
			.isEqualTo("(EQUALP EQUAL)\n#<HASH-TABLE :TEST EQUALP :COUNT 1>\n#<HASH-TABLE :TEST EQUAL :COUNT 0>");
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
	void compileLinalgRankNShapesAndStackedMatmul() throws Exception {
		// The rank-N round, same program and expectation as the JVM
		// compileAndRunLinalgRankNShapesAndStackedMatmul case. The last softmax pins
		// the masked-attention idiom: -infinity through where -> amax -> exp -> div
		// weighs exactly 0.0 here too, which needs WasmExpCompiler's underflow clamp.
		assertThat(compileAndRunLinalg("""
				(defparameter *m* (linalg:reshape (linalg:arange 6) '(2 3)))
				(print (linalg:expand-dims #(1 2 3) 0))
				(print (linalg:squeeze #2A((1 2 3))))
				(print (linalg:concatenate (list *m* *m*) :axis 1))
				(print (linalg:stack (list #(1 2) #(3 4)) :axis 1))
				(print (linalg:slice *m* '(nil (0 2))))
				(print (linalg:slice #(0 1 2 3 4 5) '((nil nil -1))))
				(print (linalg:triu (linalg:ones '(3 3)) :k 1))
				(print (linalg:tril #2A((1 2 3) (4 5 6) (7 8 9)) :k -1))
				(print (linalg:matmul (linalg:reshape (linalg:arange 12) '(2 2 3))
				                      (linalg:reshape (linalg:arange 12) '(2 3 2))))
				(print (linalg:matmul (linalg:reshape (linalg:arange 12) '(2 2 3)) #(1 1 1)))
				(print (linalg:shape (linalg:matmul (linalg:zeros '(2 1 3 4)) (linalg:zeros '(5 4 2)))))
				(print (linalg:var #(1 2 3 4)))
				(print (linalg:std *m* :axis 0 :keepdims t))
				(print (linalg:power 2 #(1 2 3)))
				(print (linalg:where #(1 0 1) 10 20))
				(print (linalg:softmax #(1 1 1 1)))
				(print (linalg:softmax #2A((0 0) (1 1)) :axis 1))
				(print (linalg:log-softmax #(0 0)))
				(print (linalg:softmax (linalg:where (linalg:from-list '((1 0) (0 1)))
				                                     (linalg:from-list '((1.0 2.0) (3.0 4.0)))
				                                     (/ -1.0 0.0))
				                       :axis 1))
				(print (array-element-type (linalg:slice (linalg:ones 4 :element-type 'single-float) '((0 2)))))
				"""))
			.isEqualTo("#d((1.0 2.0 3.0))\n#d(1.0 2.0 3.0)\n#d((0.0 1.0 2.0 0.0 1.0 2.0) (3.0 4.0 5.0 3.0 4.0 5.0))\n"
					+ "#d((1.0 3.0) (2.0 4.0))\n#d((0.0 1.0) (3.0 4.0))\n#d(5.0 4.0 3.0 2.0 1.0 0.0)\n"
					+ "#d((0.0 1.0 1.0) (0.0 0.0 1.0) (0.0 0.0 0.0))\n#d((0.0 0.0 0.0) (4.0 0.0 0.0) (7.0 8.0 0.0))\n"
					+ "#d(((10.0 13.0) (28.0 40.0)) ((172.0 193.0) (244.0 274.0)))\n#d((3.0 12.0) (21.0 30.0))\n"
					+ "(2 5 3 2)\n1.25\n#d((1.5 1.5 1.5))\n#d(2.0 4.0 8.0)\n#d(10.0 20.0 10.0)\n"
					+ "#d(0.25 0.25 0.25 0.25)\n#d((0.5 0.5) (0.5 0.5))\n#d(-0.6931471805599453 -0.6931471805599453)\n"
					+ "#d((1.0 0.0) (0.0 1.0))\nSINGLE-FLOAT");
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
				(print (linalg:sum *m* :axis 0))
				(print (linalg:sum *m* :axis -1 :keepdims t))
				(print (linalg:mean *m* :axis 0))
				(print (linalg:amax *m* :axis 1))
				(print (linalg:amin *m* :axis 0 :keepdims t))
				(print (linalg:argmax *m* :axis 1))
				(print (linalg:argmin *m* :axis 0))
				(print (linalg:sum (linalg:reshape (linalg:arange 24) '(2 3 4)) :axis 1))
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
				(print (linalg:equal (linalg:argmax *m* :axis 1) #(2 2)))
				(print (linalg:zeros-like (linalg:ones 2 :element-type 'single-float)))
				""")).isEqualTo("#d(5.0 7.0 9.0)\n#d((6.0) (15.0))\n#d(2.5 3.5 4.5)\n#d(3.0 6.0)\n#d((1.0 2.0 3.0))\n"
				+ "#d(2.0 2.0)\n#d(0.0 0.0 0.0)\n#d((12.0 15.0 18.0 21.0) (48.0 51.0 54.0 57.0))\n(3 4)\n"
				+ "#d(26833.0 11120.0 29256.0 22347.0)\n#d(4.0 5.0 6.0 2.0 9.0 7.0 1.0 0.0 8.0 3.0)\n"
				+ "#d((317.0 637.0) (949.0 376.0))\n#d(284.0 -21.0 221.0 -1653.0)\n"
				+ "#d((4.0 5.0 6.0) (1.0 2.0 3.0))\n#d(4.0 5.0 6.0)\n#d(3.0 4.0)\n"
				+ "#d((0.0 1.0 0.0) (1.0 0.0 0.0) (0.0 0.0 1.0))\n#d((0.0 0.0 0.0) (1.0 1.0 1.0))\n"
				+ "#d(1.0 1.0)\n#f(0.0 0.0)");
	}

	@Test
	void compileTorchAutogradSmallGraph() throws Exception {
		// Same program and expectation as the JVM compileAndRunTorchAutogradSmallGraph
		// case: a multi-step graph (matmul -> broadcasting add -> mul -> sum) with
		// backward, plus torch:no-grad keeping a result off the tape.
		assertThat(compileAndRunTorch("""
				(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
				(defparameter *b* (torch:tensor 0.5 :requires-grad t))
				(defparameter *x* (torch:tensor #2A((1.0 2.0) (3.0 4.0))))
				(defparameter *y* (torch:add (torch:matmul *x* *w*) *b*))
				(print (torch:data *y*))
				(defparameter *loss* (torch:sum (torch:mul *y* *y*)))
				(print (torch:item *loss*))
				(torch:backward *loss*)
				(print (torch:grad *w*))
				(print (torch:grad *b*))
				(torch:no-grad
				  (print (torch:requires-grad-p (torch:mul *w* 2))))
				(print (torch:requires-grad-p (torch:mul *w* 2)))
				""")).isEqualTo("#f(5.5 11.5)\n162.5\n#f(80.0 114.0)\n34.0\nNIL\nT");
	}

	@Test
	void compileTorchTrainingLoopWithNoGrad() throws Exception {
		// Same program and expectation as the JVM
		// compileAndRunTorchTrainingLoopWithNoGrad
		// case: ten exact-dyadic gradient-descent steps, the update inside
		// torch:no-grad (the special-variable rebinding on the wasm backend).
		assertThat(compileAndRunTorch("""
				(defparameter *w* (torch:tensor '(0.0) :requires-grad t))
				(defparameter *x* (torch:tensor '(1.0 2.0)))
				(defparameter *y* (torch:tensor '(2.0 4.0)))
				(dotimes (i 10)
				  (let* ((diff (torch:sub (torch:mul *x* *w*) *y*))
				         (loss (torch:mean (torch:mul diff diff))))
				    (torch:backward loss)
				    (torch:no-grad
				      (setq *w* (torch:tensor (linalg:sub (torch:data *w*)
				                                          (linalg:mul 0.125 (torch:grad *w*)))
				                              :requires-grad t)))))
				(print (torch:data *w*))
				""")).isEqualTo("#f(1.9998901)");
	}

	@Test
	void compileTorchGradcheckTable() throws Exception {
		// The shared table-driven gradient check (TorchGradcheck), on the wasm-GC
		// backend: the tape's closures and the polynomial exp/log/tanh must stay
		// CONSISTENT between the analytic backward and the numeric forward
		// differences, which is exactly what the relative tolerance verifies.
		assertThat(compileAndRunTorch(am.ik.rontolisp.testsupport.TorchGradcheck.PROGRAM))
			.isEqualTo(am.ik.rontolisp.testsupport.TorchGradcheck.EXPECTED);
	}

	@Test
	void compileTorchModuleTrainingLoop() throws Exception {
		// The nn acceptance program (TorchGradcheck.NN_TRAINING_PROGRAM) on the
		// wasm-GC backend: the module records, the forward closures reached through
		// torch:forward, the parameter walk and the in-place torch:set-data update
		// inside torch:no-grad all have to agree with the other backends.
		assertThat(compileAndRunTorch(am.ik.rontolisp.testsupport.TorchGradcheck.NN_TRAINING_PROGRAM))
			.isEqualTo(am.ik.rontolisp.testsupport.TorchGradcheck.NN_TRAINING_EXPECTED);
	}

	@Test
	void compileTorchRecordPrinting() throws Exception {
		// TorchGradcheck.RECORD_PRINT_PROGRAM on the wasm-GC backend: the
		// (:print-object ...) renderings of the three records and the identity
		// semantics of eq/eql/member on a record instance have to match the other
		// backends byte for byte.
		assertThat(compileAndRunTorch(am.ik.rontolisp.testsupport.TorchGradcheck.RECORD_PRINT_PROGRAM))
			.isEqualTo(am.ik.rontolisp.testsupport.TorchGradcheck.RECORD_PRINT_EXPECTED);
	}

	@Test
	void compileTorchElementTypes() throws Exception {
		// TorchGradcheck.ELEMENT_TYPE_PROGRAM on the wasm-GC backend: the torch layers
		// originate single-float arrays (TYPE_F32ARR) and every derived value keeps
		// that width through forward and backward, while linalg stays double -- the
		// same lines the interpreter and the JVM print.
		assertThat(compileAndRunTorch(am.ik.rontolisp.testsupport.TorchGradcheck.ELEMENT_TYPE_PROGRAM))
			.isEqualTo(am.ik.rontolisp.testsupport.TorchGradcheck.ELEMENT_TYPE_EXPECTED);
	}

	@Test
	void compileTorchOptimizerRules() throws Exception {
		// The optimizer acceptance program (TorchGradcheck.OPTIMIZER_PROGRAM) on the
		// wasm-GC backend: the element-wise in-place parameter update, the optimizer
		// record's step-fn closure, the seeded batch shuffle and the
		// residual-vs-plain identity-learning experiment must all match the other
		// backends.
		assertThat(compileAndRunTorch(am.ik.rontolisp.testsupport.TorchGradcheck.OPTIMIZER_PROGRAM))
			.isEqualTo(am.ik.rontolisp.testsupport.TorchGradcheck.OPTIMIZER_EXPECTED);
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
	void compileRankZeroArray() throws Exception {
		// (make-array nil): no dimensions, one element reached with NO subscripts, total
		// size 1, printed as #0A<datum> without parens -- identical on all four backends
		// (JvmLispCompilerTest#compileAndRunRankZeroArray, ci-spec
		// rank-zero-arrays-cross-backend).
		assertThat(compileAndRun("""
				(defparameter *z* (make-array nil :initial-element 5))
				(print (list (array-rank *z*) (array-dimensions *z*) (array-total-size *z*)
				             (aref *z*) (row-major-aref *z* 0) (array-row-major-index *z*)))
				(setf (aref *z*) 7)
				(print *z*)
				(setf (row-major-aref *z* 0) 9)
				(print (list (aref *z*) *z*))
				(print (list #0A5 (aref #0A5) #0ANIL #0A(1 2) (array-rank #0A(1 2))))
				""")).isEqualTo("(0 NIL 1 5 5 0)\n#0A7\n(9 #0A9)\n(#0A5 5 #0ANIL #0A(1 2) 0)");
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
	void compileRowMajorArefReadsAndWritesAString() throws Exception {
		// A string is a rank-1 array of characters in CL, so row-major-aref reads it
		// like aref does -- both wasm backends were missing the string arm and trapped
		// instead (.kb/string-write-runtime.md, todo 587). The write spelling
		// (setf (row-major-aref v i) c) is the same %schar-set place aref/char/elt use:
		// in place for a mutable buffer, a rebind that leaves the source constant for a
		// literal.
		assertThat(compileAndRun("""
				(defun %rmar-lit () "abc")
				(print (row-major-aref (%rmar-lit) 1))
				(let ((a (%rmar-lit))) (setf (row-major-aref a 0) #\\Z) (print a))
				(print (%rmar-lit))
				(let ((b (make-string 3 :initial-element #\\a)))
				  (setf (row-major-aref b 1) #\\Z)
				  (print b))
				""")).isEqualTo("#\\b\n\"Zbc\"\n\"abc\"\n\"aZa\"");
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
	// reads widen f32->f64, writes narrow. A single-float ELEMENT prints at its f32
	// width on every backend (the shortest f32 round-trip decimal, so
	// #f(0.1) round-trips); lossy narrowing is also asserted arithmetically below and
	// byte-exactly in JvmFloatArrayTest.

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
		assertThat(compileAndRun("""
				(let ((a (make-array 1 :element-type 'single-float)))
				  (setf (aref a 0) 0.1)
				  (print (= (aref a 0) 0.1)))
				""")).isEqualTo("NIL");
		// The element itself prints at the f32 width (the widened double would show
		// 0.10000000149011612), while aref answers the widened double.
		assertThat(compileAndRun("""
				(let ((a (make-array 1 :element-type 'single-float)))
				  (setf (aref a 0) 0.1)
				  (print a)
				  (print (aref a 0)))
				""")).isEqualTo("#f(0.1)\n0.10000000149011612");
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
	void compileVectorPushExtendGrowthPolicyIsDoubling() throws Exception {
		// The ONE growth policy (am.ik.rontolisp.ArrayGrowth): a supplied extension
		// verbatim, otherwise doubling off a floor of 1 -- the same numbers the
		// interpreter and the other compile path answer with.
		assertThat(compileAndRun("""
				(defun growth-run (cap n)
				  (let ((v (make-array cap :fill-pointer 0 :adjustable t)))
				    (dotimes (i n) (vector-push-extend i v))
				    (array-dimension v 0)))
				(defun growth-run-string (cap n)
				  (let ((s (make-array cap :element-type 'character :fill-pointer 0 :adjustable t)))
				    (dotimes (i n) (vector-push-extend #\\a s))
				    (array-dimension s 0)))
				(defun growth-run-ext (cap n ext)
				  (let ((v (make-array cap :fill-pointer 0 :adjustable t)))
				    (dotimes (i n) (vector-push-extend i v ext))
				    (array-dimension v 0)))
				(print (list (growth-run 2 5) (growth-run-string 2 5) (growth-run 0 1)
				             (growth-run 0 5) (growth-run-ext 2 3 100) (growth-run-ext 2 5 1)))
				""")).isEqualTo("(8 8 1 8 102 5)");
	}

	@Test
	void compileASlotOpenedByGrowthTakesTheElementTypeZero() throws Exception {
		// A slot the GROWTH opens is below the new DIMENSION but above the fill
		// pointer, so aref may read it, and it answers the array's remembered element
		// type's own zero -- the same fill make-array gives an unsupplied element
		// (am.ik.rontolisp.ArrayElementTypes). #\Space is this project's ONE character
		// fill; the general vector keeps NIL. adjust-array opens the same kind of slot
		// and takes the same fill. Pinned here, in the other three backends' twins and
		// in the opened-slot-fill-cross-backend ci-spec case.
		assertThat(compileAndRun("""
				(defun opened-push (cap n)
				  (let ((v (make-array cap :fill-pointer 0 :adjustable t)))
				    (dotimes (i n) (vector-push-extend i v))
				    (aref v (1- (array-dimension v 0)))))
				(defun opened-push-string (cap n)
				  (let ((s (make-array cap :element-type 'character :fill-pointer 0 :adjustable t)))
				    (dotimes (i n) (vector-push-extend #\\a s))
				    (char-code (aref s (1- (array-dimension s 0))))))
				(defun opened-push-typed (cap n)
				  (let ((f (make-array cap :element-type 'double-float :fill-pointer 0 :adjustable t))
				        (b (make-array cap :element-type '(unsigned-byte 8) :fill-pointer 0 :adjustable t)))
				    (dotimes (i n) (vector-push-extend 1.5d0 f))
				    (dotimes (i n) (vector-push-extend 7 b))
				    (list (aref f (1- (array-dimension f 0))) (aref b (1- (array-dimension b 0))))))
				(defun opened-adjust ()
				  (let ((s (make-array 2 :element-type 'character :fill-pointer 1 :adjustable t))
				        (f (make-array 2 :element-type 'double-float :fill-pointer 1 :adjustable t))
				        (v (make-array 2 :fill-pointer 1 :adjustable t)))
				    (adjust-array s 5)
				    (adjust-array f 5)
				    (adjust-array v 5)
				    (list (char-code (aref s 4)) (aref f 4) (aref v 4))))
				(print (list (opened-push 2 3) (opened-push-string 2 3) (opened-push-typed 2 3)
				      (opened-adjust)))
				""")).isEqualTo("(NIL 32 (0.0 0) (32 0.0 NIL))");
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
	void compileDisplacedStringView() throws Exception {
		// Displacing onto a STRING answers a string VIEW, not a bare array view: it is
		// stringp, prints and measures as a string, and aliases the target's characters
		// in both directions -- including a view of a view.
		assertThat(compileAndRun("""
				(defparameter *s* (make-string 6 :initial-element #\\a))
				(dotimes (i 6) (setf (char *s* i) (char "abcdef" i)))
				(defparameter *v* (make-array 3 :element-type 'character :displaced-to *s*
				                                :displaced-index-offset 1))
				(print (list (stringp *v*) (length *v*) *v* (char *v* 0) (subseq *v* 1)))
				(setf (char *v* 0) #\\X)
				(print (list *v* *s*))
				(setf (char *s* 3) #\\Y)
				(print (list *v* *s* (string= *v* "XcY")))
				(multiple-value-bind (tgt off) (array-displacement *v*)
				  (print (list (eq tgt *s*) off)))
				(defparameter *w* (make-array 2 :element-type 'character :displaced-to *v*
				                                :displaced-index-offset 1))
				(setf (char *w* 1) #\\Q)
				(print (list *w* *v* *s*))
				""")).isEqualTo("""
				(T 3 "bcd" #\\b "cd")
				("Xcd" "aXcdef")
				("XcY" "aXcYef" T)
				(T 1)
				("cQ" "XcQ" "aXcQef")""");
	}

	@Test
	void compileDisplacedStringViewOverACopySeqResultWritesThrough() throws Exception {
		// A copy-seq/subseq result is a MUTABLE character vector (.todo/559 step 2), so
		// a displaced view over it aliases real storage and a write through the view
		// reaches the target -- the same answer the interpreter and SBCL give. The
		// promote-on-write fallback this test used to pin applied only while such a
		// string was an immutable TYPE_STRING here.
		assertThat(compileAndRun("""
				(defparameter *s* (copy-seq "abcdef"))
				(defparameter *v* (make-array 3 :element-type 'character :displaced-to *s*
				                                :displaced-index-offset 1))
				(print (list *v* (length *v*) (string= *v* "bcd")))
				(setf (char *v* 0) #\\X)
				(print (list *v* *s*))
				""")).isEqualTo("(\"bcd\" 3 T)\n(\"Xcd\" \"aXcdef\")");
	}

	@Test
	void compileAFlippedStringProducerResultHasWritableIdentity() throws Exception {
		// The remaining producers flipped after copy-seq/subseq: concatenate 'string,
		// the case family, format nil, the string-stream capture and read-line answer
		// a MUTABLE character vector, so an alias sees a write, a callee's write
		// reaches its caller, and replace/fill land in place -- matching the
		// interpreter and SBCL (.kb/string-write-runtime.md).
		assertThat(compileAndRun("""
				(let* ((s (string-upcase "abc")) (a s)) (setf (char s 0) #\\x) (print (list s a)))
				(let ((s (concatenate 'string "ab" "cd"))) (replace s "XY") (print s))
				(let ((s (format nil "~a" 42))) (fill s #\\9) (print s))
				(defun t596f (x) (setf (char x 0) #\\Z) x)
				(let ((s (with-output-to-string (o) (princ "hi" o)))) (print (list (t596f s) s)))
				(with-input-from-string (in "hello")
				  (let* ((s (read-line in nil)) (a s)) (setf (char s 0) #\\J) (print (list s a))))
				(let* ((s (string-capitalize "foo bar")) (a s)) (setf (char s 3) #\\!) (print (list s a)))
				(let* ((s (string-trim " " "  ab  ")) (a s)) (setf (char s 0) #\\x) (print (list s a)))
				(let* ((s (map 'string #'char-upcase "abc")) (a s)) (setf (char s 0) #\\x) (print (list s a)))
				(let* ((s (coerce (list #\\a #\\b) 'string)) (a s)) (setf (char s 0) #\\x) (print (list s a)))
				(let ((s (funcall #'concatenate 'string "ab" "cd"))) (print (list (t596f s) s)))
				(print (let ((s (copy-seq "abc"))) (eq s (coerce s 'string))))
				(print (with-input-from-string (in "")
				  (let ((e (copy-seq "eof"))) (eq (read-line in nil e) e))))
				""")).isEqualTo(
				"(\"xBC\" \"xBC\")\n\"XYcd\"\n\"99\"\n(\"Zi\" \"Zi\")\n(\"Jello\" \"Jello\")\n(\"Foo!Bar\" \"Foo!Bar\")"
						+ "\n(\"xb\" \"xb\")\n(\"xBC\" \"xBC\")\n(\"xb\" \"xb\")\n(\"Zbcd\" \"Zbcd\")\nT\nT");
	}

	@Test
	void compileAProducerBuiltStringFoldsAsAnEqualpHashKey() throws Exception {
		// An equalp table's key fold runs AFTER the character-vector render: without
		// it two same-content producer-built keys fold to two distinct vectors and
		// never collide, while the literal spelling of the same key hits
		// (.kb/hash-tables.md; found by the .todo/596 boundary sweep).
		assertThat(compileAndRun("""
				(let ((h (make-hash-table :test 'equalp)))
				  (setf (gethash (format nil "K~a" 1) h) 42)
				  (print (gethash "k1" h))
				  (print (gethash (concatenate 'string "k" "1") h))
				  (print (gethash (string-upcase "k1") h)))
				""")).isEqualTo("42\n42\n42");
	}

	@Test
	void compileALiteralArgumentProducerCallAnswersAFreshMutableStringPerEvaluation() throws Exception {
		// string-upcase/string-downcase/concatenate 'string/subseq of literal
		// arguments fold to a (%str-fresh ...) constant -- the value is computed at
		// compile time, but each evaluation materializes a FRESH MUTABLE string, so
		// the fold cannot forge aliasing (.kb/pure-builtin-fold.md).
		assertThat(compileAndRun("""
				(defun t596g () (string-upcase "ab"))
				(let ((s (t596g))) (setf (char s 0) #\\z) (print (list s (t596g))))
				(let ((s (subseq "abcdef" 1 3))) (replace s "Q") (print s))
				(let* ((s (concatenate 'string "ab" "cd")) (a s)) (fill s #\\y) (print (list s a)))
				""")).isEqualTo("(\"zB\" \"AB\")\n\"Qc\"\n(\"yyyy\" \"yyyy\")");
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
	void compileSetfEltDispatchesOverListStringAndVector() throws Exception {
		// (setf (elt seq i) v) has a three-way runtime dispatch: rplaca for a list,
		// the schar-set rebuild for a string, %aset for a vector. The string arm used
		// to be missing, so an IMMUTABLE string target reached %aset and trapped here
		// (cast failure) while the interpreter mutated it.
		assertThat(compileAndRun("""
				(let ((s "abc")) (setf (elt s 0) #\\z) (print s))
				(let ((v (vector 1 2 3))) (setf (elt v 0) 9) (print v))
				(let ((l (list 1 2 3))) (setf (elt l 0) 8) (print l))
				(let ((s (make-string 3 :initial-element #\\a))) (setf (elt s 1) #\\z) (print s))
				""")).isEqualTo("\"zbc\"\n#(9 2 3)\n(8 2 3)\n\"aza\"");
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
	void compileStringpClassifiesEveryCellShape() throws Exception {
		// stringp's TYPE_CELL arm asks _charvec_p for the marker shape instead of
		// rendering the vector through _charvec_to_str, so this pins that the split
		// kept every one of the normalization's early exits: only the marked rank-1
		// character array (meta offset i31 == 1) is a string, and the values that
		// share the TYPE_CELL box -- a general/adjustable/multi-dimensional array, a
		// packed vector, a displaced array, a hash table, a struct, an instance, a
		// closure, a stream -- are not.
		assertThat(compileAndRun("""
				(defstruct pt x)
				(defclass cls () ((a :initform 1)))
				(defparameter *cv* (make-array 3 :element-type 'character :fill-pointer 3 :adjustable t))
				(print (list (stringp "abc") (stringp (make-string 3)) (stringp *cv*)
				             (stringp (make-array 3 :element-type 'character :adjustable t))
				             (stringp (copy-seq (make-string 3))) (stringp (symbol-name 'foo))))
				(print (list (stringp (make-array 3)) (stringp (make-array 3 :adjustable t))
				             (stringp (make-array '(2 2))) (stringp (make-array 3 :displaced-to (make-array 3)))
				             (stringp (make-array 3 :element-type '(unsigned-byte 8)))
				             (stringp (make-array 3 :element-type 'double-float))
				             (stringp (make-hash-table))))
				(print (list (stringp (make-pt :x 1)) (stringp (make-instance 'cls)) (stringp #'car)
				             (stringp (lambda (x) x)) (stringp (make-string-output-stream))
				             (stringp '(1 2)) (stringp 12345678901234567890) (stringp 1/3) (stringp #\\a)))
				""")).isEqualTo("""
				(T T T T T T)
				(NIL NIL NIL NIL NIL NIL NIL)
				(NIL NIL NIL NIL NIL NIL NIL NIL NIL)""");
	}

	@Test
	void compileStringpOverACharVectorIsConstantTime() throws Exception {
		// Todo 342. (stringp v) over a mutable character vector used to answer by
		// calling _charvec_to_str -- rendering all of v into a fresh string, then
		// keeping one bit of the result -- so it was O(length v) and re-paid on every
		// call: 200,000 calls cost 10.2 s for an 8192-character vector against 0.1 s
		// for a 64-character one (wasmtime 47, and ~1 ns per character per call on
		// node 24 too). It now calls _charvec_p, which stops at the marker.
		//
		// A RATIO rather than a wall-clock bound, so the pin is the complexity class
		// and not the speed of the machine that runs it: linear costs made the long
		// vector ~850x the short one, constant ones make them equal, and the assertion
		// sits between at 20x plus 50 ms of scheduling slack.
		assertThat(compileAndRun("""
				(defun stringp-ms (s iters)
				  (let ((n 0) (start (get-internal-real-time)))
				    (dotimes (i iters)
				      (if (stringp s) (setq n (+ n 1))))
				    (if (= n iters) (- (get-internal-real-time) start) -1)))
				(let* ((short (stringp-ms (make-string 1) 200000))
				       (long (stringp-ms (make-string 8192) 200000)))
				  (print (and (>= short 0) (<= long (+ 50 (* 20 short))))))
				""")).isEqualTo("T");
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
	void sequenceCoerceAnswersTheSameForEveryRepresentation() throws Exception {
		// The interpreter converts these in Java rather than through the expansion's
		// (map 'list #'identity ...) loop (.kb/seq-coerce-runtime.md); the compile paths
		// keep the expansion. Both must answer the same thing for every representation
		// the walk reaches -- and for the ones the interpreter's fast arm DECLINES, which
		// is where a divergence would hide.
		assertThat(compileAndRun("""
				(print (length (coerce (string (code-char 128512)) 'list)))
				(let ((s (make-array 5 :element-type 'character :fill-pointer 2 :initial-element #\\z)))
				  (print (coerce s 'list)))
				(let ((a (make-array 5 :fill-pointer 2 :initial-element 7)))
				  (print (coerce a 'list)))
				(print (coerce (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(1 2 3)) 'list))
				(print (coerce (make-array 2 :element-type 'double-float :initial-contents '(1d0 2d0)) 'list))
				(print (coerce #(#\\p #\\q) 'string))
				(print (coerce nil 'string))
				(print (coerce nil 'vector))
				(print (coerce '(1 2) 'string))
				(print (coerce 5 'vector))
				(print (coerce 5 'list))
				(print (position #\\Space "a b c"))
				(print (position #\\Space "a b c" :from-end t))
				(print (count #\\a "banana"))
				(print (remove #\\a "banana"))
				""")).isEqualTo(
				"1\n(#\\z #\\z)\n(7 7)\n(1 2 3)\n(1.0 2.0)\n\"pq\"\n\"\"\n#()\n\"12\"\n5\nNIL\n1\n3\n3\n\"bnn\"");
	}

	@Test
	void searchAndMismatchAnswerTheSameAsTheInterpretersNativeArm() throws Exception {
		// search and mismatch are Lisp-source prelude defuns, and only the INTERPRETER
		// answers them natively (.kb/seq-coerce-runtime.md): this backend runs the defun.
		// Both must answer the same thing for every representation and keyword the arm
		// serves -- and for the ones it DECLINES, which is where a divergence would hide.
		assertThat(compileAndRunPrelude("""
				(print (search "bc" "abcd"))
				(print (search "x" "abcd"))
				(print (search "" "abcd"))
				(print (search "ab" "ab-ab" :from-end t))
				(print (search "" "abcd" :from-end t))
				(print (search "xxabyy" "ab-ab" :start1 2 :end1 4 :start2 1 :end2 5 :from-end t))
				(print (search '(3 4) '(1 2 3 4 5)))
				(print (search "bc" (coerce "abcd" 'vector)))
				(print (search '(#\\b #\\c) "abcd"))
				(print (search nil "abcd"))
				(print (search #(2 3) (make-array 4 :element-type '(unsigned-byte 8)
				                                 :initial-contents '(1 2 3 4))))
				(print (search '(2.0d0) (make-array 3 :element-type 'double-float
				                                   :initial-contents '(1d0 2d0 3d0))))
				(let ((s (make-array 6 :element-type 'character :fill-pointer 3
				                       :initial-contents '(#\\a #\\b #\\c #\\d #\\e #\\f))))
				  (print (list (search "bc" s) (search "cd" s))))
				(print (search (string (code-char 128512))
				               (concatenate 'string "ab" (string (code-char 128512)) "cd")))
				(print (search "bc" "abcd" :test #'char=))
				(print (search "BC" "abcd" :test #'char-equal))
				(print (search "BC" "abcd" :key #'char-upcase))
				(print (search "ab" "xab" :end2 99))
				(print (search "abcd" "xab" :start1 3 :end1 1))
				(print (mismatch "abc" "abd"))
				(print (mismatch "abc" "abc"))
				(print (mismatch "abc" "ab"))
				(print (mismatch '(1 2 3) '(1 2 4)))
				(print (mismatch "xxabyy" "zzab" :start1 2 :end1 4 :start2 2))
				(print (mismatch "abcd" "xbcd" :from-end t))
				"""))
			.isEqualTo("1\nNIL\n0\n3\n4\n3\n2\n1\n1\n0\n1\n1\n(1 NIL)\n2\n1\n1\n1\n1\n0\n2\nNIL\n2\n2\nNIL\n0");
	}

	@Test
	void searchAndMismatchWalkAListWithACursor() throws Exception {
		// This backend runs the prelude defun, which used to index a LIST operand with
		// (elt seq i) -- an nth walk from the head, so O(n^2*m) for search and O(n^2)
		// for mismatch. It reads a list through a cons cursor now; every answer here is
		// the one the elt-indexed body gave, out-of-range and negative bounds included
		// (the cursor cannot answer those, so the read falls back to the same elt call).
		assertThat(compileAndRunPrelude("""
				(print (search '(3 4) '(1 2 3 4 5)))
				(print (search '(3 4) '(1 2 3 4 5) :start2 3))
				(print (search '(3 4) '(1 2 3 4 3 4) :from-end t))
				(print (search '(9 3 4 9) '(1 2 3 4 5) :start1 1 :end1 3))
				(print (search '(3 4) '(1 2 3 4 5) :end2 3))
				(print (search '(#\\b #\\c) "abcd"))
				(print (search "bc" '(#\\a #\\b #\\c #\\d)))
				(print (search '(1 2) '(1 2 3) :end2 99))
				(print (search '(1 2) '(1 2 3) :start2 99))
				(print (search '(1 2 3) '(1 2 3) :start1 99))
				(print (search '(1 2 3) '(1 2 3) :start1 1 :end1 99))
				(print (search '(1 2 3) '(1 2 3) :start2 -1))
				(print (search '(1 2 3) '(1 2 3) :start1 -1))
				(print (search '(1) '(1 2 . 3)))
				(print (search '(3 4) '(1 2 3 4 5) :key #'identity))
				(print (mismatch '(1 2 3) '(1 2 4)))
				(print (mismatch '(1 2 3) '(1 2 3) :end1 99))
				(print (mismatch '(1 2 3) '(1 2 3) :end2 99))
				(print (mismatch '(1 2 3) "abc"))
				(print (mismatch '(9 1 2 3) '(1 2 3) :start1 1))
				(print (mismatch '(1 2 3) '(1 2 4) :from-end t))
				(let ((long (let ((out nil))
				              (dotimes (i 400) (setq out (cons (mod i 7) out)))
				              (nreverse out))))
				  (print (list (search '(3 5) long) (search '(5 6) long)
				               (search '(5 6) long :from-end t) (mismatch long long)
				               (mismatch long (append (butlast long) (list 99))))))
				""")).isEqualTo(
				"2\nNIL\n4\n2\nNIL\n1\n1\n0\nNIL\n0\nNIL\n0\nNIL\n0\n2\n2\n3\n3\n0\nNIL\n2\n" + "(NIL 5 397 NIL 399)");
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
				(print (linalg:diff #(1 2 4 7 0) :n 2))
				(print (linalg:diff #2A((1 3 6) (0 5 6))))
				(print (linalg:gradient #(0 1 4 9 16)))
				(print (linalg:gradient #(0 1 4 9 16) 2))
				(print (linalg:gradient #(0 1 9) #(0 1 3)))
				(print (array-element-type (linalg:gradient (linalg:arange 0 4 :element-type 'single-float))))
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
				(print (linalg:mul (linalg:from-list '((1 2) (3 4)) :element-type 'single-float) #(10 20)))
				(print (linalg:div #3A(((2.0 4.0) (6.0 8.0))) #(2 4)))
				""")).isEqualTo("#d((10.0 40.0) (30.0 80.0))\n#d((101.0 102.0) (203.0 204.0))\n#d(0.0 1.0)\n"
				+ "#d((3.0 5.0) (4.0 3.0))\n#f((10.0 40.0) (30.0 80.0))\n#d(((1.0 1.0) (3.0 2.0)))");
	}

	@Test
	void linalgTransposeAxesPadIm2colWorkInPreview1Mode() throws Exception {
		// Same program as the JVM compileAndRunLinalgTransposeAxesPadIm2col case:
		// the ch07 CNN additions -- transpose with an axes list, np.pad's
		// constant-0 mode, and the internal rank-4 %la-im2col/%la-col2im pair.
		assertThat(compileAndRunLinalg(
				"""
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
						(print (array-element-type (linalg:transpose (linalg:ones '(2 2 2) :element-type 'single-float) '(2 1 0))))
						(print (array-element-type (linalg:pad (linalg:ones 2 :element-type 'single-float) 1)))
						"""))
			.isEqualTo("(3 2 4)\n#d((1.0 3.0) (2.0 4.0))\nT\n"
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
			.isEqualTo(
					"\"[1,[2,3],false]\"\n\"{\\\"deep\\\":{\\\"list\\\":[{\\\"k\\\":\\\"v\\\"},2.5,true]}}\"\n\"[1,2]\"");
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
		assertThat(compileAndRunProgram(program)).isEqualTo("\"{\\\"msg\\\":\\\"hi\\\"}\"\n(:A 1)\n\"{\\\"x\\\":1}\"\n"
				+ "\"{\\\"status\\\":200,\\\"headers\\\":{\\\"content-type\\\":\\\"application/json\\\"},\\\"items\\\":[1,2,3]}\"");
	}

	@Test
	void alistPlistAndPlistAlistInPreview1Mode() throws Exception {
		// rontolisp:alist-plist / plist-alist (prelude, alexandria subsets) go
		// through no hash table, so multi-key output is backend-stable too.
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(print (rontolisp:alist-plist (list (cons :a 1) (cons :b 2))))
				(print (rontolisp:plist-alist (list :a 1 :b 2)))
				(print (rontolisp:alist-plist (rontolisp:plist-alist (list :x 1 :y 2))))
				"""));
		assertThat(compileAndRunProgram(program)).isEqualTo("(:A 1 :B 2)\n((:A . 1) (:B . 2))\n(:X 1 :Y 2)");
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
	void compileAndRunAPrunedBundledDefstructThroughTheRegistrationMarker() throws Exception {
		// The pruner expands a bundled-library defstruct into its defuns ahead of
		// pruning and leaves a %struct-definition marker in the stream; the shared
		// expansion pass re-runs the registration from the marker, so setf places, the
		// predicate and construction still compile with the unreferenced accessors
		// pruned.
		java.util.List<LispVal> pruned = am.ik.rontolisp.eval.LibraryDefunPruner.prune(LispReader.readAllFromString("""
				(defstruct torch::rec a b c)
				(setq r (torch::make-rec :a 1 :b 2 :c 3))
				(setf (torch::rec-a r) 10)
				(print (torch::rec-a r))
				(print (torch::rec-p r))
				"""));
		assertThat(compileAndRunProgram(pruned)).isEqualTo("10\nT");
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
	void compileAndRunDefstructPrintObjectAndPrintFunctionOptions() throws Exception {
		// Both printer options lower to a synthesized print-object defmethod; the CLtL1
		// :print-function spelling takes the extra depth (always 0 -- no print level is
		// tracked).
		assertThat(compileAndRun("""
				(defstruct (dpo-pt (:print-object dpo-pt-printer)) (x 1) (y 2))
				(defun dpo-pt-printer (obj stream)
				  (format stream "<~D,~D>" (dpo-pt-x obj) (dpo-pt-y obj)))
				(defstruct (dpf-set (:print-function (lambda (obj stream depth)
				                                       (print-unreadable-object (obj stream :type t)
				                                         (format stream "of ~D element~:P at ~D"
				                                                 (dpf-set-size obj) depth)))))
				  (size 1))
				(print (list (princ-to-string (make-dpo-pt)) (prin1-to-string (make-dpo-pt :y 9))
				             (princ-to-string (make-dpf-set)) (princ-to-string (make-dpf-set :size 3))))
				"""))
			.isEqualTo("(\"<1,2>\" \"<1,9>\" \"#<DPF-SET of 1 element at 0>\" \"#<DPF-SET of 3 elements at 0>\")");
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
	void compileAndRunDefmethodEqlSpecializerNamingAConstant() throws Exception {
		assertThat(compileAndRun("""
				(defconstant +eql-spec-utf8+ 12)
				(defconstant +eql-spec-tag+ :tag)
				(defgeneric decode-it (a b))
				(defmethod decode-it (a b) (list :default a b))
				(defmethod decode-it (a (b (eql +eql-spec-utf8+))) (list :utf8 a b))
				(defmethod decode-it (a (b (eql +eql-spec-tag+))) (list :tag a b))
				(print (list (decode-it 1 12) (decode-it 1 :tag) (decode-it 1 99)))
				""")).isEqualTo("((:UTF8 1 12) (:TAG 1 :TAG) (:DEFAULT 1 99))");
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
				  (print (list (%class-designator alias) (wc-conn alias) (wc-extra alias) (wc-sub-open-p alias)))
				  (with-accessors ((e wc-extra)) alias (setf e (list e :seen)))
				  (print (wc-extra alias)))
				(print (format nil "~a|~s" (make-wc-node :value 1) (make-wc-node :value 2)))
				"""))
			.isEqualTo("(T T)\n(%class-WC-SUB \"c\" :POOLED T)\n(:POOLED :SEEN)" + "\n\"#<WC-NODE 1>|#<WC-NODE 2>\"");
	}

	@Test
	void compileAndRunPrintObjectForANestedObject() throws Exception {
		// The method decides the text wherever the instance sits, and the walk that makes
		// that true reproduces the raw renderer exactly for everything it does not route.
		assertThat(compileAndRun("""
				(defclass wpn-c () ((x :initarg :x)))
				(defmethod print-object ((o wpn-c) s) (format s "#<C custom>"))
				(let ((i (make-instance 'wpn-c :x 1)))
				  (print i)
				  (print (list i))
				  (format t "~S~%" (list i))
				  (print (vector i))
				  (print (list 1 "s" (list i) 2))
				  (print (cons 1 i))
				  (print '(1 2 . 3))
				  (princ (list 1 "s" 'sym))
				  (terpri))
				""")).isEqualTo("#<C custom>\n(#<C custom>)\n(#<C custom>)\n#(#<C custom>)\n(1 \"s\" (#<C custom>) 2)\n"
				+ "(1 . #<C custom>)\n(1 2 . 3)\n(1 s SYM)");
	}

	@Test
	void compileAndRunUnboundSlotSignalsUnboundSlot() throws Exception {
		assertThat(compileAndRun("""
				(defclass wu-box () ((a :initarg :a) (b :initform 7)))
				(let ((o (make-instance 'wu-box)))
				  (print (list (slot-boundp o 'a) (slot-boundp o 'b)))
				  (print (handler-case (slot-value o 'a)
				           (unbound-slot (e) (list (slot-value e 'name) (%class-designator (slot-value e 'instance))))))
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
	void setfSymbolFunctionAliasAndRedefinition() throws Exception {
		assertThat(compileAndRun("""
				(defun wsf-orig (x) (* x 2))
				(setf (symbol-function 'wsf-alias) #'wsf-orig)
				(print (wsf-alias 21))
				(print (funcall #'wsf-alias 3))
				(setf (fdefinition 'wsf-alias) (lambda (x) (list :new x)))
				(print (wsf-alias 5))
				(print (fboundp 'wsf-alias))
				""")).isEqualTo("42\n6\n(:NEW 5)\nT");
	}

	@Test
	void reinitializeInstanceAndComputedChangeClass() throws Exception {
		// The two runtime halves of the CLOS surface family: reinitialize-instance is
		// callable with no user method (the system default fills the supplied
		// initargs), and change-class takes a COMPUTED class designator -- a symbol or
		// a class metaobject -- through the generated %change-class-runtime dispatch.
		assertThat(compileAndRun("""
				(defclass wri-p () ((n :initarg :n :reader wri-n) (m :initarg :m :initform 10)))
				(let ((o (make-instance 'wri-p :n 1)))
				  (reinitialize-instance o :n 2)
				  (print (list (wri-n o) (slot-value o 'm))))
				(defclass wcc-base () ((n :initarg :n)))
				(defclass wcc-sub (wcc-base) ((extra :initform 42)))
				(let ((o (make-instance 'wcc-base :n 1)) (cls 'wcc-sub))
				  (change-class o cls)
				  (let ((p (make-instance 'wcc-base :n 5)))
				    (change-class p (find-class 'wcc-sub) :n 7)
				    ;; class-name is a prelude defun and this harness splices no prelude,
				    ;; so the class change reads back through typep.
				    (print (list (typep o 'wcc-sub) (slot-value o 'n) (slot-value o 'extra)
				                 (slot-value p 'n)))))
				""")).isEqualTo("(2 10)\n(T 1 42 7)");
	}

	@Test
	void theMissingStandardNames() throws Exception {
		// The batch that this harness can carry: with-compilation-unit is a
		// progn, the load switches are bound nil, and the three new type names resolve.
		// print-object is callable DIRECTLY, with and without a user method on the
		// object. (cl:most-positive-fixnum is a READER substitution keyed on the
		// reader's feature set, which this harness does not carry the WASM one of, and
		// the prelude-backed names of the batch are pinned in ci-spec instead.)
		assertThat(compileAndRunPrelude("""
				(print (with-compilation-unit (:override t) 1 2 3))
				(print (list *load-verbose* *load-print*))
				(print (list (typep (make-synonym-stream '*standard-output*) 'synonym-stream)
				             (typep *readtable* 'readtable)
				             (typep 3 'file-stream)
				             (typep "s" 'file-stream)))
				(print (let ((s (make-string-input-stream "z")))
				         (list (typep s 'file-stream) (typep s 'string-stream) (typep s 'stream))))
				(print-object 42 *standard-output*)
				(terpri)
				(defclass wpo-p () ())
				(defmethod print-object ((x wpo-p) s) (write-string "#<WPO!>" s))
				(print-object (make-instance 'wpo-p) *standard-output*)
				(terpri)
				(print (make-instance 'wpo-p))
				""")).isEqualTo("3\n(NIL NIL)\n(T T NIL NIL)\n(NIL T T)\n42\n#<WPO!>\n#<WPO!>");
	}

	@Test
	void defclassWriterClassAllocationAndStandardClassTypecase() throws Exception {
		// :writer generics (both spellings), the shared cell of :allocation :class
		// (subclass re-declaration owns a new cell; slot-value and a runtime name read
		// the cell), and standard-class as a typecase specifier.
		assertThat(compileAndRun("""
				(defclass wwr-q () ((a :writer (setf wwr-q-a) :reader wwr-q-a :initform 1)
				                    (b :writer wwr-set-b :reader wwr-get-b :initform 0)))
				(let ((o (make-instance 'wwr-q)))
				  (setf (wwr-q-a o) 5)
				  (wwr-set-b 7 o)
				  (print (list (wwr-q-a o) (wwr-get-b o))))
				(defclass wca-op () ((selfward :initform 'base-op :allocation :class :reader wca-selfward)))
				(defclass wca-load-op (wca-op) ((selfward :initform 'load-dep :allocation :class)))
				(defclass wca-r () ((a :initform 1 :allocation :class :accessor wca-r-a)))
				(let ((x (make-instance 'wca-r)) (y (make-instance 'wca-r)) (nm 'a))
				  (setf (wca-r-a x) 9)
				  (print (list (wca-selfward (make-instance 'wca-op))
				               (wca-selfward (make-instance 'wca-load-op))
				               (wca-r-a y) (slot-value y 'a) (slot-value y nm))))
				(print (typecase (find-class 'wca-op) (standard-class :meta) (t :other)))
				""")).isEqualTo("(5 7)\n(BASE-OP LOAD-DEP 9 9 9)\n:META");
	}

	@Test
	void compileAndRunDefclassMultipleInheritance() throws Exception {
		// Slot merge across supers (second super's accessor overridden for the shifted
		// index), diamond slot dedup with the CPL-most-specific initform, and method
		// dispatch + call-next-method following the instance class's precedence list.
		assertThat(compileAndRun("""
				(defclass wmi-a () ((x :initarg :x :accessor wmi-x)))
				(defclass wmi-b () ((y :initarg :y :accessor wmi-y)))
				(defclass wmi-c (wmi-a wmi-b) ((z :initarg :z :accessor wmi-z)))
				(let ((b (make-instance 'wmi-b :y 7))
				      (c (make-instance 'wmi-c :x 1 :y 2 :z 3)))
				  (setf (wmi-y c) 9)
				  (print (list (wmi-x c) (wmi-y c) (wmi-z c) (wmi-y b))))
				(defclass wdi-base () ((v :initform :base :reader wdi-v)))
				(defclass wdi-l (wdi-base) ())
				(defclass wdi-r (wdi-base) ((v :initform :right)))
				(defclass wdi-d (wdi-l wdi-r) ())
				(print (list (wdi-v (make-instance 'wdi-d)) (typep (make-instance 'wdi-d) 'wdi-r)))
				(defgeneric wlp-who (x))
				(defmethod wlp-who ((x wmi-a)) (cons :a (if (next-method-p) (call-next-method) nil)))
				(defmethod wlp-who ((x wmi-b)) (cons :b (if (next-method-p) (call-next-method) nil)))
				(defmethod wlp-who (x) (list :default))
				(defclass wlp-ba (wmi-b wmi-a) ())
				(print (wlp-who (make-instance 'wmi-c :x 1 :y 2 :z 3)))
				(print (wlp-who (make-instance 'wlp-ba)))
				""")).isEqualTo("(1 9 3 7)\n(:RIGHT T)\n(:A :B :DEFAULT)\n(:B :A :DEFAULT)");
	}

	@Test
	void compileAndRunSetfMethodDispatchesPerClass() throws Exception {
		assertThat(compileAndRun("""
				(defclass wsm-a () ((x :initarg :x :accessor wsm-val)))
				(defclass wsm-b () ((log :initform nil :reader wsm-log)))
				(defmethod (setf wsm-val) (new (b wsm-b)) (setf (slot-value b 'log) (list :wrote new)) new)
				(let ((a (make-instance 'wsm-a :x 1))
				      (b (make-instance 'wsm-b)))
				  (setf (wsm-val a) 2)
				  (setf (wsm-val b) 3)
				  (print (list (wsm-val a) (wsm-log b))))
				(defclass wsm-box () ((v :initform 0 :reader wsm-content)))
				(defgeneric (setf wsm-content) (new box)
				  (:method (new (b wsm-box)) (setf (slot-value b 'v) new)))
				(let ((b (make-instance 'wsm-box)))
				  (setf (wsm-content b) 9)
				  (funcall #'(setf wsm-content) 11 b)
				  (print (wsm-content b)))
				""")).isEqualTo("(2 (:WROTE 3))\n11");
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
	void compileAndRunPackageIsADefmethodSpecializer() throws Exception {
		// rove's find-suite: a (package) method beside an unspecialized DESIGNATOR
		// method that calls find-package and recurses. The specializer shares the
		// package TYPE test, and ranks ahead of keyword/symbol -- misordered, the
		// designator method would recurse forever.
		assertThat(compileAndRun("""
				(defpackage :rov-suite (:use :cl))
				(defvar *package-suites* (make-hash-table :test 'equal))
				(defgeneric find-suite (package)
				  (:method ((package package))
				    (values (gethash package *package-suites*)))
				  (:method (package-name)
				    (check-type package-name string-designator)
				    (let ((package (find-package package-name)))
				      (unless package (error "No package '~A' found" package-name))
				      (find-suite package))))
				(setf (gethash :rov-suite *package-suites*) "suite")
				(print (list (find-suite :rov-suite) (find-suite "ROV-SUITE") (find-suite 'rov-suite)))
				""")).isEqualTo("(\"suite\" \"suite\" \"suite\")");
	}

	@Test
	void compileAndRunPackageSpecializerOutranksKeywordAndSymbol() throws Exception {
		// A package IS a keyword in this value model, so the package branch must be
		// tested first; a keyword naming no package falls through to the keyword method.
		assertThat(compileAndRun("""
				(defpackage :psk-pkg (:use :cl))
				(defgeneric psk-kind (x))
				(defmethod psk-kind ((x symbol)) :symbol)
				(defmethod psk-kind ((x keyword)) :keyword)
				(defmethod psk-kind ((x package)) :package)
				(defmethod psk-kind (x) :other)
				(print (list (psk-kind :psk-pkg) (psk-kind :no-such-pkg-xyz) (psk-kind 'psk-pkg) (psk-kind 42)))
				""")).isEqualTo("(:PACKAGE :KEYWORD :SYMBOL :OTHER)");
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
				""")).isEqualTo("\"x=5 y=7\"\nto-stdout ok\n\"\\\"q\\\"\"");
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
	void progvBindsRestoresAndNests() throws Exception {
		// Interpreter parity for the progv lowering (.kb/dynamic-special-variables.md):
		// a declared special gets a true dynamic binding visible through a called defun,
		// nested binds stack, an UNDECLARED name is readable via symbol-value for the
		// extent (and unbound again after), and extra symbols bind to nil. The lowering
		// rides unwind-protect, so the module compiles in EH mode.
		assertThat(compileAndRun("""
				(defvar *a* 1)
				(defvar *b* 2)
				(defun peek () (list *a* *b*))
				(progv '(*a* *b*) '(10 20) (print (peek)))
				(print (peek))
				(print (progv (list '*a*) (list 5)
				         (progv (list '*a* '*c*) (list (* *a* 2) 7)
				           (list *a* (symbol-value '*c*)))))
				(print (list *a* (boundp '*c*)))
				(print (progv '(*a*) '() *a*))
				""")).isEqualTo("""
				(10 20)
				(1 2)
				(10 7)
				(1 NIL)
				NIL""");
	}

	@Test
	void progvRestoresOnEveryExit() throws Exception {
		// The lowering rides the unwind-protect cleanup emitter, so a return-from, a go
		// and an error caught OUTSIDE the progv all restore the binding.
		assertThat(compileAndRun("""
				(defvar *x* :top)
				(defun f ()
				  (progv '(*x*) '(:in)
				    (return-from f *x*)))
				(print (list (f) *x*))
				(defun g ()
				  (let ((r nil))
				    (tagbody
				       (progv '(*x*) '(:go)
				         (setq r *x*)
				         (go out))
				     out)
				    (list r *x*)))
				(print (g))
				(print (handler-case (progv '(*x*) '(:err) (error "boom"))
				         (error () *x*)))
				""")).isEqualTo("""
				(:IN :TOP)
				(:GO :TOP)
				:TOP""");
	}

	@Test
	void progvSymbolValueSeesTheSetqInsideTheExtent() throws Exception {
		// The cl-json aggregate-scope shape: (progv vars (mapcar #'symbol-value vars))
		// re-binds each scope variable to its CURRENT value, where "current" includes a
		// setq made inside an enclosing progv extent -- symbol-value compiles
		// dynamic-first in a progv-using program. The progv here sits inside a defun,
		// pinning that the eval-mirror maintenance is not top-level-only.
		assertThat(compileAndRun("""
				(defvar *acc* nil)
				(defun scoped ()
				  (progv '(*acc*) (list (symbol-value '*acc*))
				    (setq *acc* (cons 1 *acc*))
				    (progv '(*acc*) (list (symbol-value '*acc*))
				      (setq *acc* (cons 2 *acc*))
				      (print *acc*))
				    (print *acc*)))
				(scoped)
				(print *acc*)
				""")).isEqualTo("""
				(2 1)
				(1)
				NIL""");
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
		byte[] wasmBytes = new WasmLispCompiler(false, false, false, OptimizeLevel.NONE, false, simd).compile(program);
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
			  (let ((a (vec:arange n :element-type 'single-float)) (b (vec:ones n :element-type 'single-float)))
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
			  (let* ((w (linalg:reshape (linalg:arange 0 (* 3 n) :element-type 'single-float) (list 3 n)))
			         (x (vec:ones n :element-type 'single-float)))
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
		String dot = "(let ((v (vec:ones 1024 :element-type 'single-float))) (setf (aref v 0) 4096.0)"
				+ " (print (round (vec:dot v v))))";
		assertThat(compileAndRunVec(dot, true)).isEqualTo("16777984");
		assertThat(compileAndRunVec(dot, false)).isEqualTo("16778239");

		String sum = "(let ((v (vec:ones 1024 :element-type 'single-float))) (setf (aref v 0) 16777216.0)"
				+ " (print (round (vec:sum v))))";
		assertThat(compileAndRunVec(sum, true)).isEqualTo("16777984");
		assertThat(compileAndRunVec(sum, false)).isEqualTo("16778239");

		String gemv = "(let ((m (make-array '(1 1024) :element-type 'single-float :initial-element 1.0))"
				+ " (v (vec:ones 1024 :element-type 'single-float)))"
				+ " (setf (aref m 0 0) 4096.0) (setf (aref v 0) 4096.0)"
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
				+ "(print (vec:sum (vec:add (vec:arange 7 :element-type 'single-float) (vec:ones 7 :element-type 'single-float))))"
				+ "(print (vec:matvec #d((1.0 2.0) (3.0 4.0)) #d(5.0 6.0)))";
		List<LispVal> program = am.ik.rontolisp.eval.VecLibrary.process(LispReader.readAllFromString(source));
		byte[] optimized = new WasmLispCompiler(false, false, false, OptimizeLevel.DEFAULT, false, true)
			.compile(program);
		wasmtime.copyFileToContainer(Transferable.of(optimized), path("test.wasm"));
		ExecResult result = wasmtime.execInContainer("wasmtime", "run", "--wasm", "gc", path("test.wasm"));
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout().trim()).isEqualTo(compileAndRunVec(source, false));
	}

	@Test
	void wasmGcSimdModuleNeedsTheSimdProposalAndTheDefaultOneDoesNot() throws Exception {
		// The runnable dead-flag guard, the wasm-GC counterpart of the JVM's embedded-
		// bridge check (JvmSimdAccelCompilerTest#embedsBridge; a JVM without
		// jdk.incubator.vector degrades instead of failing to run at all):
		// turning the SIMD proposal off makes wasmtime REFUSE to compile the --simd
		// module (its v128 opcodes no longer validate), while the default module still
		// runs. Proves the interception fired -- correctness alone cannot, since both
		// compute the same
		// values. (relaxed-simd must be disabled too: wasmtime rejects simd=n on its
		// own.)
		String source = "(print (vec:dot (vec:ones 5) (vec:ones 5)))";
		String[] noSimd = { "--wasm", "simd=n", "--wasm", "relaxed-simd=n" };
		assertThat(compileAndRunVec(source, false, noSimd)).isEqualTo("5.0");

		List<LispVal> program = am.ik.rontolisp.eval.VecLibrary.process(LispReader.readAllFromString(source));
		byte[] simdBytes = new WasmLispCompiler(false, false, false, OptimizeLevel.NONE, false, true).compile(program);
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
			      (a (vec:ones 5 :element-type 'single-float)) (b (vec:ones 5 :element-type 'single-float)))
			  (vec:sub-into o a b)
			  (print o))
			(let ((o (make-array 6 :element-type 'double-float :initial-element 9.0))
			      (v (vec:ones 3)))
			  (vec:scale-into o v 2.0)
			  (print o))
			(let ((o (make-array 7 :element-type 'single-float :initial-element 9.0))
			      (v (vec:ones 3 :element-type 'single-float)))
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
			        (f (vec:sub (vec:arange n :element-type 'single-float) (vec:scale (vec:ones n :element-type 'single-float) 2.0))))
			    (print (list n (vec:abs v) (vec:negative v) (vec:sign v) (vec:square v)))
			    (print (list (vec:abs f) (vec:negative f) (vec:sign f) (vec:square f)))
			    (print (list (vec:sqrt (vec:square v)) (vec:sqrt (vec:square f))))
			    (print (vec:reciprocal (vec:add (vec:arange n) (vec:ones n))))
			    (print (vec:reciprocal (vec:add (vec:arange n :element-type 'single-float) (vec:ones n :element-type 'single-float))))
			    (print (vec:exp (vec:reciprocal (vec:add (vec:arange n) (vec:ones n)))))
			    (print (vec:exp (vec:reciprocal (vec:add (vec:arange n :element-type 'single-float) (vec:ones n :element-type 'single-float)))))
			    (print (vec:log (vec:add (vec:arange n) (vec:ones n))))
			    (print (vec:log (vec:add (vec:arange n :element-type 'single-float) (vec:ones n :element-type 'single-float))))
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
			(let ((flong (vec:scale (vec:ones 6 :element-type 'single-float) 9.0)))
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
			        (f (vec:sub (vec:arange n :element-type 'single-float) (vec:scale (vec:ones n :element-type 'single-float) 2.0))))
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
			(let ((flong (vec:scale (vec:ones 6 :element-type 'single-float) 9.0)))
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
		byte[] wasmBytes = new WasmLispCompiler(false, false, false, OptimizeLevel.NONE, false, simd).compile(program);
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
			sources
				.add("(print (linalg:sum (linalg:%s 3.0 (linalg:ones 5 :element-type 'single-float))))".formatted(op));
		}
		sources.add("(print (linalg:transpose #d((1.0 2.0 3.0) (4.0 5.0 6.0))))");
		sources.add("(print (linalg:transpose #f((1.0 2.0) (3.0 4.0))))");
		sources.add("(let ((v #d(1.0 2.0))) (print (eq v (linalg:transpose v))))");
		sources.add("(print (linalg:reshape (linalg:arange 12) '(3 4)))");
		sources.add("(print (linalg:reshape (linalg:arange 12) '(2 3 2)))");
		sources.add("(print (linalg:reshape (linalg:arange 0 12 :element-type 'single-float) 12))");
		sources.add("(print (linalg:flatten #d((1.0 2.0) (3.0 4.0))))");
		return sources;
	}

	@ParameterizedTest
	@MethodSource("elementWiseAndShapeKernelSources")
	void wasmGcSimdLinalgElementWiseAndShapeKernelsAreByteIdenticalToTheScalarPath(String lispCode) throws Exception {
		assertLinalgMatchesTheScalarPath(lispCode);
	}

	// The axis forms are intercepted call shapes: a literal :axis / :keepdims tail
	// (any order, each at most once) routes to the SUM_AXIS/AMAX_AXIS/ARGMAX_AXIS
	// kernels (an option not supplied padded with a null ref), whose folds mirror
	// %la-fold-axis / %la-argfold-axis exactly; a 1-arg call still hits the base
	// kernel whose decline branch passes an extra null rest to the variadic defun,
	// and a declined AXIS call links the surplus locals -- keyword literals included
	// -- into the rest list.
	static List<String> axisFormSources() {
		return List.of("(print (linalg:sum (linalg:reshape (linalg:arange 6) '(2 3)) :axis 0))",
				"(print (linalg:sum (linalg:reshape (linalg:arange 6) '(2 3)) :axis 1 :keepdims t))",
				"(print (linalg:sum (linalg:reshape (linalg:arange 6) '(2 3)) :keepdims t :axis 1))",
				"(print (linalg:sum (linalg:reshape (linalg:arange 6) '(2 3)) :keepdims t))",
				"(print (linalg:amax (linalg:reshape (linalg:arange 6) '(2 3)) :keepdims t :axis -1))",
				"(print (linalg:sum (linalg:reshape (linalg:arange 24) '(2 3 4)) :axis -1))",
				"(print (linalg:sum (linalg:arange 5) :axis 0))",
				"(print (linalg:sum (linalg:from-list '((0.5 0.25) (0.125 2.0)) :element-type 'single-float) :axis 0))",
				"(print (linalg:mean (linalg:reshape (linalg:arange 6) '(2 3)) :axis 0))",
				"(print (linalg:amax (linalg:reshape (linalg:arange 6) '(2 3)) :axis 1))",
				"(print (linalg:amin (linalg:reshape (linalg:arange 6) '(2 3)) :axis 0 :keepdims t))",
				"(print (linalg:argmax (linalg:reshape (linalg:arange 6) '(2 3)) :axis 1))",
				"(print (linalg:argmin (linalg:from-list '(3.0 9.0 2.0)) :axis 0))",
				"(print (linalg:amax (linalg:from-list '((0.5 0.25) (0.125 2.0)) :element-type 'single-float) :axis 1))",
				// 1-arg over a general (boxed) array exercises the decline branch itself;
				// an axis call over one exercises the extended decline's rest packaging.
				"(print (linalg:sum #(1 2 3)))", "(print (linalg:argmax #(1 9 3)))",
				"(print (linalg:sum #2A((1 2) (3 4)) :axis 0))", "(print (linalg:sum #d((1.0 2.0))))",
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
		assertThat(compileAndRunVec("(print (linalg:amax #d((-0.0 0.0)) :axis 1))", true)).isEqualTo("#d(-0.0)");
		assertThat(compileAndRunVec("(print (linalg:amax #d((-3.0 -1.0) (-5.0 -2.0)) :axis 1))", true))
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
				"(print (linalg:sub (linalg:reshape (linalg:arange 0 4 :element-type 'single-float) '(2 2))"
						+ " (linalg:arange 0 2 :element-type 'single-float)))",
				"(print (linalg:maximum (linalg:reshape (linalg:arange 6) '(2 3)) (linalg:from-list '(2.0 4.0 1.0))))",
				// A mixed-width broadcast still declines to the defun (first operand's
				// width).
				"(print (array-element-type (linalg:div (linalg:ones '(2 2) :element-type 'single-float) #d(1.0 2.0))))",
				"(print (linalg:transpose (linalg:reshape (linalg:arange 24) '(2 3 2 2)) '(0 3 1 2)))",
				"(print (linalg:transpose (linalg:reshape (linalg:arange 6) '(2 3)) '(1 0)))",
				"(print (linalg:transpose (linalg:reshape (linalg:from-list '(1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0)"
						+ " :element-type 'single-float) '(2 2 2)) '(2 0 1)))",
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
		sources.add("(print (linalg:trace (linalg:eye 5 :element-type 'single-float)))");
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
			(defun mk (r c et) (linalg:reshape (linalg:add (linalg:arange 0 (* r c) :element-type et) 0.5) (list r c)))
			(defun mkv (n et) (linalg:add (linalg:arange 0 n :element-type et) 0.25))
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
		// The same, for the stacked matrix product: a boxed rank-3 operand declines.
		assertThat(compileAndRunVec("""
				(defparameter *n* 0)
				(defun bump () (setq *n* (+ *n* 1)) (make-array '(2 2 2) :initial-element 1))
				(linalg::%la-matmul-nd (bump) (linalg:zeros '(2 2)))
				(print *n*)
				""", true)).isEqualTo("1");
	}

	// The internal STACKED matrix product (%la-matmul-nd, torch.bmm). Plain rank 3, a
	// BROADCAST leading axis on either side (stride 0 in the batch odometer), a rank-2
	// operand against a rank-3 one, rank 4 with two leading axes, both widths, and a p
	// wide enough to fill whole lane groups inside each batch's slab. Integer-valued
	// operands, so the f32 fold is exact and this stays a byte-identity check. The last
	// three DECLINE (a general boxed operand, a rank-1 side, a mixed-width pair) and must
	// answer through the defun instead.
	static List<String> matmulNdSources() {
		return List.of(
				"(print (linalg:matmul (linalg:reshape (linalg:arange 24) '(2 3 4))"
						+ " (linalg:reshape (linalg:arange 32) '(2 4 4))))",
				"(print (linalg:matmul (linalg:reshape (linalg:arange 12) '(1 3 4))"
						+ " (linalg:reshape (linalg:arange 32) '(2 4 4))))",
				"(print (linalg:matmul (linalg:reshape (linalg:arange 24) '(2 3 4))"
						+ " (linalg:reshape (linalg:arange 8) '(1 4 2))))",
				"(print (linalg:matmul (linalg:reshape (linalg:arange 32) '(2 4 4))"
						+ " (linalg:reshape (linalg:arange 8) '(4 2))))",
				"(print (linalg:matmul (linalg:reshape (linalg:arange 8) '(2 4))"
						+ " (linalg:reshape (linalg:arange 32) '(2 4 4))))",
				"(print (linalg:matmul (linalg:reshape (linalg:arange 48) '(2 3 2 4))"
						+ " (linalg:reshape (linalg:arange 24) '(1 3 4 2))))",
				"(print (linalg:matmul"
						+ " (linalg:reshape (linalg:arange 0 24 1 :element-type 'single-float) '(2 3 4))"
						+ " (linalg:reshape (linalg:arange 0 32 1 :element-type 'single-float) '(2 4 4))))",
				"(print (linalg:matmul"
						+ " (linalg:reshape (linalg:arange 0 70 1 :element-type 'single-float) '(2 5 7))"
						+ " (linalg:reshape (linalg:arange 0 70 1 :element-type 'single-float) '(2 7 5))))",
				"(print (linalg:sum (linalg:matmul (linalg:reshape (linalg:arange 512) '(2 2 128))"
						+ " (linalg:reshape (linalg:arange 512) '(2 128 2)))))",
				"(print (linalg:matmul (make-array '(2 2 2) :initial-element 1) (linalg:zeros '(2 2))))",
				"(print (linalg:matmul (linalg:reshape (linalg:arange 24) '(2 3 4)) (linalg:arange 4)))",
				"(print (linalg:matmul (linalg:reshape (linalg:arange 24) '(2 3 4))"
						+ " (linalg:reshape (linalg:arange 0 32 1 :element-type 'single-float) '(2 4 4))))");
	}

	@ParameterizedTest
	@MethodSource("matmulNdSources")
	void wasmGcSimdLinalgMatmulNdIsByteIdenticalToTheScalarPath(String lispCode) throws Exception {
		assertLinalgMatchesTheScalarPath(lispCode);
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
						+ " (linalg:reshape (linalg:arange 0 96 1 :element-type 'single-float) '(2 3 4 4)) 3 3 2 1))",
				"(print (linalg::%la-col2im (linalg::%la-im2col"
						+ " (linalg:reshape (linalg:arange 96) '(2 3 4 4)) 3 3 1 1) '(2 3 4 4) 3 3 1 1))",
				"(print (linalg::%la-col2im (linalg::%la-im2col"
						+ " (linalg:reshape (linalg:arange 0 96 1 :element-type 'single-float) '(2 3 4 4)) 3 3 1 1) '(2 3 4 4) 3 3 1 1))",
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
		String dot = "(let ((v (linalg:ones 1024 :element-type 'single-float))) (setf (aref v 0) 4096.0)"
				+ " (print (round (linalg:dot v v))))";
		assertThat(compileAndRunVec(dot, true)).isEqualTo("16777984");
		assertThat(compileAndRunVec(dot, false)).isEqualTo("16778239");
		String sum = "(let ((v (linalg:ones 1024 :element-type 'single-float))) (setf (aref v 0) 16777216.0)"
				+ " (print (round (linalg:sum v))))";
		assertThat(compileAndRunVec(sum, true)).isEqualTo("16777984");
		assertThat(compileAndRunVec(sum, false)).isEqualTo("16778239");
		// mean rides on sum, matrix . vector on the vec: GEMV kernel (a dot per row).
		String mean = "(let ((v (linalg:ones 1024 :element-type 'single-float))) (setf (aref v 0) 16777216.0)"
				+ " (print (round (* 1024 (linalg:mean v)))))";
		assertThat(compileAndRunVec(mean, true)).isEqualTo("16777984");
		assertThat(compileAndRunVec(mean, false)).isEqualTo("16778239");
		String gemv = "(let ((v (linalg:ones 1024 :element-type 'single-float))) (setf (aref v 0) 4096.0)"
				+ " (print (round (aref (linalg:dot (linalg:reshape v '(1 1024)) v) 0))))";
		assertThat(compileAndRunVec(gemv, true)).isEqualTo("16777984");
		assertThat(compileAndRunVec(gemv, false)).isEqualTo("16778240");
		// The MATRIX PRODUCT follows the contract too: its scratch row is now the
		// operand width, so an #f cell folds k in the oracle's own ascending order but
		// at single precision. p = 1 here, so only lane 0 of the group is real; the
		// 200-column b below fills whole f32x4 groups. The lanes run across j, which
		// carries no summation, so the lane count cannot move the answer -- which is how
		// this agrees with the interpreter and the JVM.
		String vm = "(let ((v (linalg:ones 1024 :element-type 'single-float))) (setf (aref v 0) 4096.0)"
				+ " (print (round (aref (linalg:dot v (linalg:reshape v '(1024 1))) 0))))";
		assertThat(compileAndRunVec(vm, true)).isEqualTo("16777216");
		assertThat(compileAndRunVec(vm, false)).isEqualTo("16778240");
		String mm = "(let ((v (linalg:ones 1024 :element-type 'single-float))) (setf (aref v 0) 4096.0)"
				+ " (let ((r (linalg:dot v (linalg:outer v (linalg:ones 200 :element-type 'single-float)))))"
				+ " (print (list (round (aref r 0)) (round (aref r 199))))))";
		assertThat(compileAndRunVec(mm, true)).isEqualTo("(16777216 16777216)");
		assertThat(compileAndRunVec(mm, false)).isEqualTo("(16778240 16778240)");
		// The STACKED product folds each cell exactly as a per-batch linalg:dot does --
		// its precision contract -- so it lands on the same 16777216 at rank 3.
		String nd = "(let ((v (linalg:ones 1024 :element-type 'single-float))) (setf (aref v 0) 4096.0)"
				+ " (print (round (row-major-aref (linalg:matmul (linalg:reshape v '(1 1 1024))"
				+ " (linalg:reshape v '(1 1024 1))) 0))))";
		assertThat(compileAndRunVec(nd, true)).isEqualTo("16777216");
		assertThat(compileAndRunVec(nd, false)).isEqualTo("16778240");
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
				"(print (linalg:negative (linalg:reshape (linalg:arange 0 6 :element-type 'single-float) '(2 3))))",
				"(print (linalg:sign (linalg:sub (linalg:arange 0 9 :element-type 'single-float) 4)))",
				"(print (linalg:square (linalg:sub (linalg:arange 5) 2)))",
				"(print (linalg:square (linalg:arange 0 5 :element-type 'single-float)))",
				"(print (linalg:reciprocal (linalg:add (linalg:arange 6) 1)))",
				"(print (linalg:exp (linalg:reciprocal (linalg:add (linalg:arange 200) 1))))",
				"(print (linalg:exp (linalg:reciprocal (linalg:add (linalg:arange 0 8 :element-type 'single-float) 1))))",
				"(print (linalg:log (linalg:add (linalg:arange 200) 1)))",
				"(print (linalg:log (linalg:reshape (linalg:add (linalg:arange 12) 1) '(3 4))))",
				"(print (linalg:log (linalg:add (linalg:arange 0 8 :element-type 'single-float) 1)))",
				"(print (linalg:tanh (linalg:mul (linalg:sub (linalg:arange 200) 100) 0.03)))",
				"(print (linalg:tanh (linalg:arange 0 8 :element-type 'single-float)))",
				"(print (linalg:tanh #d(-25.0 -0.0 0.0 25.0)))",
				"(print (linalg:sin (linalg:sub (linalg:arange 200) 100)))",
				"(print (linalg:cos (linalg:reshape (linalg:arange 12) '(3 4))))",
				"(print (linalg:tan (linalg:sub (linalg:arange 0 8 :element-type 'single-float) 4)))",
				"(print (linalg:sin #d(0.0 -0.0 1.0 -2.5 100.0)))",
				"(print (linalg:asin (linalg:mul (linalg:sub (linalg:arange 200) 100) 0.005)))",
				"(print (linalg:acos (linalg:mul (linalg:arange 0 8 :element-type 'single-float) 0.005)))",
				"(print (linalg:atan (linalg:sub (linalg:arange 200) 100)))",
				"(print (linalg:atan (linalg:reshape (linalg:arange 0 12 :element-type 'single-float) '(3 4))))",
				"(print (linalg:sinh (linalg:mul (linalg:sub (linalg:arange 200) 100) 0.05)))",
				"(print (linalg:cosh (linalg:mul (linalg:arange 0 8 :element-type 'single-float) 0.05)))",
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

	// linalg:erf: the defun is (linalg:emap #'%la-erf-1 a) and emap is never
	// intercepted, so the member is (the exact torch:gelu rides on it). The kernel
	// keeps %la-erf-1's order of operations AND this backend's own spellings of abs
	// and unary minus, so it is bit-identical -- checked where that could break:
	// x = 0 and -0.0 (which the wasm defun does NOT fold, unlike Math.abs), negatives,
	// both sides of the |x| >= 6 short circuit, the |x| ~ 3 region where the
	// alternating Maclaurin series would have lost every digit, both widths, rank 2,
	// and the declined inputs running the defun. (torch:gelu rides on it, but this
	// harness splices only vec.lisp / linalg.lisp; the gelu leg is verified by hand
	// and by the interpreter / JVM suites.)
	static List<String> erfSources() {
		return List.of(
				"(print (linalg:erf #d(0.0 -0.0 1.0e-8 -1.0e-8 0.5 -0.5 1.0 -1.0 2.0 -2.0 2.9 -2.9 3.0 -3.0 3.1 -3.1)))",
				"(print (linalg:erf #d(4.0 -4.0 5.0 -5.0 5.999999 -5.999999 6.0 -6.0 6.0000001 -6.0000001 7.0 -7.0 12.5 -12.5)))",
				"(print (linalg:erf #f(0.0 -0.0 0.5 -0.5 2.9 -2.9 3.1 -3.1 5.999999 -5.999999 6.0 -6.0 12.5 -12.5)))",
				"(print (linalg:erf (linalg:mul (linalg:sub (linalg:arange 200) 100) 0.07)))",
				"(print (linalg:erf (linalg:mul (linalg:arange 0 200 :element-type 'single-float) 0.07)))",
				"(print (linalg:erf (linalg:reshape (linalg:mul (linalg:arange 12) 0.5) '(3 4))))",
				"(print (linalg:erf #(0 1 -1)))", "(print (linalg:erf #(0.0 1.0 -1.0)))");
	}

	@ParameterizedTest
	@MethodSource("erfSources")
	void wasmGcSimdLinalgErfIsByteIdenticalToTheScalarPath(String lispCode) throws Exception {
		assertLinalgMatchesTheScalarPath(lispCode);
	}

	// The seeded generator's one fill loop (linalg:rand / randn / uniform) and Adam's
	// fused element-wise update, todo-473's two members. Both are internal linalg:
	// members precisely so that this seam can reach them, and both are bit-identical at
	// both widths -- the generator because linalg:seed promises one seed reproduces one
	// sequence on every backend, the update because it keeps the defun's order of
	// operations and narrows only on the store. The declines run the defun.
	static List<String> optimizerAndGeneratorSources() {
		String rule = """
				(defun rule (mode it)
				  (let ((r (linalg::%la-make 11 0.0 nil)))
				    (setf (aref r 0) 0.01)
				    (setf (aref r 1) (* 0.01 0.1))
				    (setf (aref r 2) 0.1)
				    (setf (aref r 3) 0.9)
				    (setf (aref r 4) (- 1.0 0.9))
				    (setf (aref r 5) 0.999)
				    (setf (aref r 6) (- 1.0 0.999))
				    (setf (aref r 7) 1.0e-8)
				    (setf (aref r 8) (- 1.0 (expt 0.9 it)))
				    (setf (aref r 9) (- 1.0 (expt 0.999 it)))
				    (setf (aref r 10) mode)
				    r))
				(defun run (et mode steps)
				  (linalg:seed 11)
				  (let ((x (linalg:randn '(3 5) :element-type et))
				        (g (linalg:randn '(3 5) :element-type et))
				        (m (linalg:zeros '(3 5) :element-type et))
				        (v (linalg:zeros '(3 5) :element-type et)))
				    (do ((it 1 (+ it 1)))
				        ((> it steps))
				      (linalg::%la-adam-step x g m v (rule mode it)))
				    (print x)
				    (print m)
				    (print v)))
				""";
		List<String> sources = new ArrayList<>();
		for (String et : List.of("nil", "'single-float")) {
			for (String mode : List.of("0", "1", "2")) {
				sources.add(rule + "(run " + et + " " + mode + " 4)");
			}
		}
		// The declines: a scalar parameter, a scalar gradient, a boxed array, a
		// mixed-width quadruple, a mode outside 0..2.
		sources.add(rule + "(print (linalg::%la-adam-step 0.5 0.25 (linalg:zeros 1) (linalg:zeros 1) (rule 2 1)))");
		sources.add(rule + "(print (linalg::%la-adam-step (linalg:ones 3) 0.25 (linalg:zeros 3) (linalg:zeros 3)"
				+ " (rule 1 1)))");
		sources.add(rule + "(print (linalg::%la-adam-step (make-array 3 :initial-element 1.0) (linalg:ones 3)"
				+ " (linalg:zeros 3) (linalg:zeros 3) (rule 0 1)))");
		sources.add(rule + "(print (linalg::%la-adam-step (linalg:ones 3 :element-type 'single-float)"
				+ " (linalg:ones 3) (linalg:zeros 3) (linalg:zeros 3) (rule 0 1)))");
		sources.add(rule + "(print (linalg::%la-adam-step (linalg:ones 3) (linalg:ones 3) (linalg:zeros 3)"
				+ " (linalg:zeros 3) (rule 7 1)))");
		// The generator: every rule, both widths, and the interleaving with the scalar
		// draws that keep using the specials (choice / permutation / %la-rng-next).
		sources.add("""
				(linalg:seed 42)
				(print (linalg:rand 5))
				(print (linalg:randn '(2 3)))
				(print (linalg:uniform -1 3 4))
				(print (linalg:choice 10 5))
				(print (linalg:permutation 6))
				(print (linalg:rand '(2 2) :element-type 'single-float))
				(print (linalg:randn 3 :element-type 'single-float))
				(print (linalg:uniform 0.5 1.5 3 :element-type 'single-float))
				(print (linalg::%la-rng-next))
				""");
		sources.add("(linalg:seed 1) (print (linalg:rand 0)) (print (linalg::%la-rng-next))");
		sources.add("(linalg:seed 9) (print (linalg:randn 700))");
		// The generator's declines: a boxed destination, a state vector of the wrong
		// length, a state word outside the generator's range, an out-of-range mode.
		sources.add("(linalg:seed 4) (print (linalg::%la-rng-fill (make-array 3 :initial-element 0.0)"
				+ " (linalg::%la-rng-state) 0 0.0 1.0))");
		sources.add("(linalg:seed 4) (print (linalg::%la-rng-fill (linalg:zeros 3) (linalg:zeros 4) 0 0.0 1.0))");
		sources.add("(linalg:seed 4) (let ((s (linalg::%la-rng-state))) (setf (aref s 0) -3.0)"
				+ " (print (linalg::%la-rng-fill (linalg:zeros 3) s 0 0.0 1.0)))");
		sources.add("(linalg:seed 4) (let ((s (linalg::%la-rng-state))) (setf (aref s 1) 0.5)"
				+ " (print (linalg::%la-rng-fill (linalg:zeros 3) s 0 0.0 1.0)))");
		sources.add("(linalg:seed 4) (print (linalg::%la-rng-fill (linalg:zeros 3) (linalg::%la-rng-state)"
				+ " 5 0.0 1.0))");
		return sources;
	}

	@ParameterizedTest
	@MethodSource("optimizerAndGeneratorSources")
	void wasmGcSimdLinalgOptimizerAndGeneratorAreByteIdenticalToTheScalarPath(String lispCode) throws Exception {
		assertLinalgMatchesTheScalarPath(lispCode);
	}

	// The 2026-08-22 selects and copies: the comparison masks (equal dims, a broadcast
	// pair, a scalar on either side, both widths), where over every operand mix, the
	// strided gather behind slice (a negative step included), take-rows and its
	// scatter-add adjoint, clip-grad-norm's two halves -- and their declines (boxed
	// operands, a ratio accumulator, three numbers), which run the defun.
	static List<String> selectAndCopySources() {
		List<String> sources = new ArrayList<>();
		for (String form : new String[] { "(linalg:equal #d(1.0 2.0 0.0) 0)", "(linalg:greater #f(1.0 2.0 0.0) 0.5)",
				"(linalg:greater 0.5 #f(1.0 2.0 0.0))", "(linalg:greater-equal #d(1.0 2.0 0.0) #d(1.0 3.0 -1.0))",
				"(linalg:less #f((1.0 2.0) (3.0 4.0)) #f((2.0 2.0) (2.0 5.0)))",
				"(linalg:less-equal #d((1.0 2.0) (3.0 4.0)) #d(2.0 3.0))",
				"(linalg:greater #d((1.0 2.0) (3.0 4.0)) #d(2.0 3.0))", "(linalg:equal 1 2)",
				"(linalg:greater #d(0.0 -0.0 1.0) -0.0)", "(linalg:equal #f(0.0 -0.0) 0.0)",
				"(linalg:less-equal #d(1.0 2.0) #f(1.0 3.0))",
				"(linalg:where #d((0.0 1.0) (1.0 0.0)) 9 #d((1.0 2.0) (3.0 4.0)))",
				"(linalg:where #d(0.0 1.0) #f((1.0 2.0) (3.0 4.0)) -1.5)", "(linalg:where 1 #d(1.0 2.0) #d(3.0 4.0))",
				"(linalg:where 0 #d(1.0 2.0) 5)", "(linalg:where #d((1.0) (0.0)) #d(1.0 2.0 3.0) #f(7.0 8.0 9.0))",
				"(linalg:where 2 3 4)", "(linalg:where #d(-0.0 1.0) #d(1.0 2.0) #d(3.0 4.0))",
				"(linalg:where #f((1.0 0.0)) #f((1.0 2.0) (3.0 4.0)) #d((5.0 6.0) (7.0 8.0)))",
				"(linalg:take-rows #d((1.0 2.0) (3.0 4.0) (5.0 6.0)) #d(2.0 0.0 2.0))",
				"(linalg:take-rows #f((1.0 2.0) (3.0 4.0)) (linalg:zeros 0))",
				"(linalg:take-rows #f(((1.0 2.0) (3.0 4.0)) ((5.0 6.0) (7.0 8.0))) #d(1.0 1.0))",
				"(linalg:take-rows #d((1.0 2.0) (3.0 4.0)) #d(1.7 0.2))",
				"(linalg:slice #d((1.0 2.0 3.0) (4.0 5.0 6.0)) '(nil (2 0 -1)))",
				"(linalg:slice #f((1.0 2.0 3.0) (4.0 5.0 6.0)) '((1 2) (0 3 2)))",
				"(linalg:slice #d((1.0 2.0 3.0) (4.0 5.0 6.0)) '((0 2) (1 1)))",
				"(linalg:slice #d(((1.0 2.0) (3.0 4.0)) ((5.0 6.0) (7.0 8.0))) '((1 2) nil (-1 nil -1)))",
				"(linalg::%la-gather-strided #d((1.0 2.0 3.0) (4.0 5.0 6.0)) '(2 2) '(1 3) 1 t)",
				"(linalg::%la-gather-strided #f((1.0 2.0 3.0) (4.0 5.0 6.0)) '(3) '(2) 0 nil)",
				"(linalg::%la-scatter-rows (linalg:zeros '(3 2)) #d((1.0 2.0) (3.0 4.0) (5.0 6.0)) #d(2.0 0.0 2.0))",
				"(linalg::%la-scatter-rows (linalg:zeros '(3 2) :element-type 'single-float)"
						+ " #f((1.0 2.0) (3.0 4.0) (5.0 6.0)) #d(2.0 0.0 2.0))",
				"(linalg::%la-sum-squares #d(1.0 2.0 3.0) 0.5)", "(linalg::%la-sum-squares #f(0.1 0.2) 0.0)",
				"(linalg::%la-sum-squares #d(1.0 2.0) 1/3)", "(linalg::%la-scale #d(1.0 2.0 3.0) 0.5)",
				"(linalg::%la-scale #f(1.0 2.0 3.0) 0.1)", "(linalg::%la-scale #f(1.0 2.0 3.0) 2)",
				"(linalg:greater (make-array 3 :initial-element 1) 0)",
				"(linalg:where (make-array 2 :initial-element 1) #d(1.0 2.0) #d(3.0 4.0))",
				"(linalg:take-rows (make-array '(2 2) :initial-element 1) #d(1.0))",
				"(linalg::%la-sum-squares (make-array 2 :initial-element 2) 0.0)",
				"(linalg:maximum (linalg:expand-dims (linalg:equal (linalg:from-list '(1 2 0 0)) 0) 1)"
						+ " (linalg:expand-dims (linalg:triu (linalg:ones '(4 4)) :k 1) 0))",
				"(linalg:where (linalg:expand-dims (linalg:triu (linalg:ones '(3 3)) :k 1) 0) -1.0e9"
						+ " (linalg:reshape (linalg:arange 0 18 :element-type 'single-float) '(2 3 3)))" }) {
			sources.add("(print " + form + ")");
		}
		return sources;
	}

	@ParameterizedTest
	@MethodSource("selectAndCopySources")
	void wasmGcSimdLinalgSelectsAndCopiesAreByteIdenticalToTheScalarPath(String lispCode) throws Exception {
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
				"(let ((a (linalg:sub (linalg:arange 0 8 :element-type 'single-float) 4))) (print (linalg:minimum a (linalg:negative a))))",
				"(print (linalg:maximum (linalg:reshape (linalg:arange 12) '(3 4)) (linalg:negative (linalg:reshape (linalg:arange 12) '(3 4)))))",
				"(print (linalg:maximum (linalg:sub (linalg:arange 200) 100) 3.0))",
				"(print (linalg:minimum 3.0 (linalg:sub (linalg:arange 200) 100)))",
				"(print (linalg:maximum (linalg:arange 0 8 :element-type 'single-float) 4.3))",
				"(print (linalg:minimum 4.3 (linalg:arange 0 8 :element-type 'single-float)))",
				"(print (linalg:maximum #d(-0.0 0.0) #d(0.0 -0.0)))",
				"(print (linalg:minimum #d(-0.0 0.0) #d(0.0 -0.0)))", "(print (linalg:maximum #d(-0.0) 0.0))",
				"(print (linalg:maximum (linalg:mul (linalg:ones 2) (/ 0.0 0.0)) #d(1.0 2.0)))",
				"(print (linalg:maximum #d(1.0 2.0) (linalg:mul (linalg:ones 2) (/ 0.0 0.0))))",
				"(print (linalg:clip (linalg:sub (linalg:arange 200) 100) -50.0 50.0))",
				"(print (linalg:clip (linalg:arange 0 8 :element-type 'single-float) 1.3 5.3))",
				"(print (linalg:relu (linalg:sub (linalg:arange 200) 100)))",
				"(print (linalg:relu (linalg:reshape (linalg:sub (linalg:arange 0 12 :element-type 'single-float) 6) '(3 4))))",
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
		byte[] wasmBytes = new WasmLispCompiler(false, false, false, OptimizeLevel.DEFAULT, false, true)
			.compile(program);
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
		assertThat(compileAndRun("(print (* 1.5 (expt 10.0 12)))")).isEqualTo("1.5e12");
		assertThat(compileAndRun("(print (- (* 2.5 (expt 10.0 15))))")).isEqualTo("-2.5e15");
		assertThat(compileAndRun("(print (expt 10.0 19))")).isEqualTo("1.0e19");
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
	void ehConditionReportRendersUnderPrincButNotUnderPrin1() throws Exception {
		assertThat(compileAndRunEh("""
				(define-condition cr-lam (error) ((msg :initarg :msg :reader cr-msg))
				  (:report (lambda (c s) (format s "cr-lam: ~a" (cr-msg c)))))
				(define-condition cr-str (error) () (:report "fixed text"))
				(handler-case (error 'cr-lam :msg "boom")
				  (error (e) (print (list (format nil "~a" e) (format nil "~s" e)
				                          (princ-to-string (make-condition 'cr-str))))))
				""")).isEqualTo("(\"cr-lam: boom\" \"#<CR-LAM :MSG \\\"boom\\\">\" \"fixed text\")");
	}

	@Test
	void ehConditionReportIsInheritedBySubtypes() throws Exception {
		assertThat(compileAndRunEh("""
				(define-condition cri-base (error) ((n :initarg :n :reader cri-n))
				  (:report (lambda (c s) (format s "base ~a" (cri-n c)))))
				(define-condition cri-sub (cri-base) ())
				(print (handler-case (error 'cri-sub :n 3) (error (e) (princ-to-string e))))
				""")).isEqualTo("\"base 3\"");
	}

	@Test
	void ehNoApplicableMethodIsCatchableAndReportsTheSameText() throws Exception {
		// The last resort signals a typed condition whose :report renders lazily (the
		// signal-point message operand is never compiled on this backend); a handler
		// that PRINTS it must still see exactly the old eager message.
		assertThat(compileAndRunEh("""
				(defclass nam-box () ((v :initarg :v :reader nam-box-v)))
				(print (handler-case (nam-box-v 42) (error (e) (princ-to-string e))))
				""")).isEqualTo("\"No applicable method: NAM-BOX-V on INTEGER\"");
	}

	@Test
	void ehSimpleConditionFamilyReportsThroughFormatControl() throws Exception {
		assertThat(compileAndRunEh("""
				(define-condition cr-pg (simple-warning) ())
				(print (list (princ-to-string
				               (make-condition 'cr-pg :format-control "pg ~A/~A" :format-arguments (list 1 2)))
				             (handler-case (error "plain ~a" 7) (error (e) (princ-to-string e)))))
				""")).isEqualTo("(\"pg 1/2\" \"plain 7\")");
	}

	@Test
	void ehRuntimeControlStringDatumRendersItsFormatArguments() throws Exception {
		assertThat(compileAndRunEh("""
				(print (list (handler-case (error "lit ~a-~a" 1 2) (error (e) (princ-to-string e)))
				             (let ((c "~a-~a"))
				               (handler-case (error c 1 2) (error (e) (princ-to-string e))))
				             (let ((c "PostgreSQL warning: ~A~@[~%~A~]"))
				               (handler-case (error c "relation already exists, skipping" nil)
				                 (error (e) (princ-to-string e))))
				             (let ((c "sig ~a/~a"))
				               (handler-case (signal c 3 4) (condition (e) (princ-to-string e))))))
				"""))
			.isEqualTo("(\"lit 1-2\" \"1-2\" \"PostgreSQL warning: relation already exists, skipping\" \"sig 3/4\")");
	}

	@Test
	void runtimeControlStringDatumRendersItsFormatArgumentsUnderWarn() throws Exception {
		assertThat(compileAndRunStderr("(let ((c \"rt ~a/~a\")) (warn c 1 2))")).isEqualTo("WARNING: rt 1/2");
	}

	@Test
	void warnRendersTheFormatControlArgumentsOfItsCondition() throws Exception {
		assertThat(
				compileAndRunStderr("(warn 'simple-warning :format-control \"sw ~A/~A\" :format-arguments (list 1 2))"))
			.isEqualTo("WARNING: sw 1/2");
	}

	@Test
	void printObjectMethodWinsOverTheConditionReport() throws Exception {
		assertThat(compileAndRun("""
				(define-condition cr-po (error) () (:report "report text"))
				(defmethod print-object ((c cr-po) s) (format s "PO"))
				(print (list (princ-to-string (make-condition 'cr-po)) (prin1-to-string (make-condition 'cr-po))))
				""")).isEqualTo("(\"PO\" \"PO\")");
	}

	@Test
	void conditionWithNoReportKeepsTheInstanceRendering() throws Exception {
		assertThat(compileAndRun("""
				(define-condition cr-bare (error) ((v :initarg :v)))
				(print (princ-to-string (make-condition 'cr-bare :v 1)))
				""")).isEqualTo("\"#<CR-BARE :V 1>\"");
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
		// unchanged from the pre-EH `unreachable` -- and it says what it
		// was on the way out.
		String stderr = compileAndRunEhExpectTrap("(handler-case (error \"boom\") (warning (w) :w))");
		assertThat(stderr).contains("unreachable").contains("Unhandled condition: boom");
	}

	@Test
	void ehUncaughtPlainErrorReportsItsMessageBeforeTrapping() throws Exception {
		// The message rides the $lisp-cond payload CDR, which the entry landing pad now
		// reads: EH mode forces Ctx.condMessagesObservable on so the operand compiles.
		assertThat(compileAndRunEhExpectTrap("""
				(print (handler-case (error "caught ~a" 1) (error (e) (princ-to-string e))))
				(error "boom: ~a" 42)
				""")).contains("Unhandled condition: boom: 42");
	}

	@Test
	void ehUncaughtTypedConditionReportsThroughItsReportLambda() throws Exception {
		// A typed signal carries a nil cdr, so the landing pad renders the INSTANCE
		// through %condition-report-str -- one call site for the whole program, which is
		// what forces the routing gate broad in EH mode. The text is the one princ
		// writes, i.e. the one the interpreter and the JVM backend print.
		assertThat(compileAndRunEhExpectTrap("""
				(define-condition uc-db (error) ((text :initarg :text :reader uc-db-text))
				  (:report (lambda (c s) (format s "database error: ~a" (uc-db-text c)))))
				(print (handler-case (error "caught") (error (e) :ok)))
				(error 'uc-db :text "password authentication failed")
				""")).contains("Unhandled condition: database error: password authentication failed");
	}

	@Test
	void ehNonNumberArithmeticOperandsAreCaughtWithTheInterpreterText() throws Exception {
		// A type slip into an arithmetic operator used to die as an UNCATCHABLE
		// `wasm trap: cast failure` straight past handler-case. The arithmetic
		// runtime's non-number arms (_int_val, _as_f64) now land in
		// _type_err_int/_type_err_num, which throw a $lisp-cond whose message is the
		// interpreter's exact text (.kb/error-handling.md, "A non-number reaching
		// arithmetic"). The evaluator/JVM twins assert the same strings.
		assertThat(compileAndRunEh("""
				(defun te-print (thunk)
				  (handler-case (funcall thunk) (error (e) (princ-to-string e))))
				(print (te-print (lambda () (+ 1 nil))))
				(print (te-print (lambda () (< 1 nil))))
				(print (te-print (lambda () (* 2 "x"))))
				(print (te-print (lambda () (max 1 'sym))))
				(print (te-print (lambda () (+ 1.5 nil))))
				""")).isEqualTo("\"Expected integer, got: NIL\"\n\"Expected integer, got: NIL\"\n"
				+ "\"Expected integer, got: \\\"x\\\"\"\n\"Expected integer, got: SYM\"\n"
				+ "\"Expected number, got: NIL\"");
	}

	@Test
	void ehNonNumberArithmeticOperandsAreCaughtOnTheComponentPathToo() throws Exception {
		assertThat(compileComponentAndRun("""
				(print (handler-case (+ 1 nil) (error (e) (princ-to-string e))))
				(print (handler-case (* 2 "x") (error (e) (princ-to-string e))))
				""")).isEqualTo("\"Expected integer, got: NIL\"\n\"Expected integer, got: \\\"x\\\"\"");
	}

	@Test
	void ehANonNumberArithmeticOperandIsCaughtAsASimpleErrorHere() throws Exception {
		// Divergence by CLASS, not catchability (the undefined-function precedent): the
		// payload a fixed runtime helper can build is instance-less, so the landing
		// synthesizes a simple-error where the interpreter and the JVM answer
		// type-error. .kb/error-handling.md carries the re-evaluation trigger.
		assertThat(compileAndRunEh("""
				(print (handler-case (+ 1 nil) (type-error (e) :type-error) (error (e) :plain-error)))
				""")).isEqualTo(":PLAIN-ERROR");
	}

	@Test
	void ehAnUncaughtNonNumberOperandReportsTheInterpreterLineBeforeTrapping() throws Exception {
		assertThat(compileAndRunEhExpectTrap("""
				(print (handler-case (error "warm") (error (e) :ok)))
				(print (+ 1 nil))
				""")).contains("Unhandled condition: Expected integer, got: NIL");
	}

	@Test
	void aNonNumberArithmeticOperandOutsideEhModeStaysATrap() throws Exception {
		// No catching form => no tag section: _type_err_int is a bare `unreachable`,
		// so the failure class is the trap it always was, with no report line -- the
		// same deliberate non-EH silence as anUncaughtConditionOutsideEhModeStaysSilent.
		String stderr = compileAndRunExpectTrap("(print 1) (print (+ 1 nil))");
		assertThat(stderr).contains("unreachable").doesNotContain("Unhandled condition");
	}

	@Test
	void anUncaughtConditionOutsideEhModeStaysSilent() throws Exception {
		// No catching form anywhere: %error is a bare `unreachable` that evaluates
		// nothing, the module has no tag section and there is no landing pad to read a
		// payload that was never built. Reporting here would cost every program the EH
		// machinery, so it is deliberately not done (.kb/error-handling.md).
		String stderr = compileAndRunExpectTrap("(print 1) (error \"boom: ~a\" 42)");
		assertThat(stderr).contains("unreachable").doesNotContain("Unhandled condition");
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
	void ehSignalFallsThroughAHandlerCaseWhoseClausesDoNotMatch() throws Exception {
		// CLHS 9.1.4.1: signal runs the applicable handlers and, if none transfers
		// control, returns nil. A handler-case whose clauses do not match the
		// condition is not an applicable handler, so it must decline and the forms
		// after the signal still run (cl-mustache's read-partial shape).
		assertThat(compileAndRunEh("""
				(define-condition note () ())
				(defun boom () (signal 'note) :returned)
				(print (handler-case (boom) (error (e) :err)))
				(print (handler-case (progn (signal 'note) :after) (type-error (e) :te)))
				""")).isEqualTo(":RETURNED\n:AFTER");
	}

	@Test
	void ehAnUnmatchedSignalLeavesTheHandlerCaseArmedForALaterCondition() throws Exception {
		assertThat(compileAndRunEh("""
				(define-condition note () ())
				(print (handler-case (progn (signal 'note) (error "boom")) (error (e) :err)))
				(print (handler-case (progn (signal 'note) :after) (note (e) :caught)))
				""")).isEqualTo(":ERR\n:CAUGHT");
	}

	@Test
	void ehHandlerBindHandlersStillRunWhenAnUnmatchedHandlerCaseDeclines() throws Exception {
		// Restart mode: the intervening handler-bind handler runs at the signal
		// point and declines; the unmatched handler-case then declines too, so the
		// form after the signal still runs.
		assertThat(compileAndRunEh("""
				(define-condition note () ())
				(defvar *log* nil)
				(print (handler-bind ((note (lambda (c) (setq *log* (cons :hb *log*)))))
				         (handler-case (progn (signal 'note) (setq *log* (cons :after *log*)) :done)
				           (error (e) :err))))
				(print *log*)
				""")).isEqualTo(":DONE\n(:AFTER :HB)");
	}

	@Test
	void ehAnErrorStillUnwindsThroughAnUnmatchedHandlerCase() throws Exception {
		// The decline applies to signal only: error (and cerror, here real under
		// restart mode) must keep unwinding through a handler-case that does not
		// match, and a restart-case arm changes nothing about the decline.
		assertThat(compileAndRunEh("""
				(define-condition note () ())
				(print (handler-case
				           (handler-case (cerror "Continue." "boom") (type-error (e) :te))
				         (error (e) :outer)))
				(print (handler-case (restart-case (progn (signal 'note) :after) (continue () :c))
				         (error (e) :err)))
				""")).isEqualTo(":OUTER\n:AFTER");
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

	// --- The restart system: the wasm mirrors of the JVM restart pins.
	// A restart-mode program is in EH mode (the expansions ride catch/throw +
	// unwind-protect).

	@Test
	void ehRestartCaseNormalCompletionReturnsPrimaryValues() throws Exception {
		assertThat(compileAndRunEh("(print (restart-case (+ 1 2) (retry () :retried)))")).isEqualTo("3");
		assertThat(compileAndRunEh("(print (multiple-value-list (restart-case (values 1 2) (retry () nil))))"))
			.isEqualTo("(1 2)");
	}

	@Test
	void ehHandlerBindInvokesKeywordRestartAcrossFunctions() throws Exception {
		// The postmodern prepare.lisp shape: the restart is ESTABLISHED in one
		// function and INVOKED (by keyword name, with an argument) from a
		// handler-bind handler running in another, before unwinding.
		assertThat(compileAndRunEh("""
				(defun rs-f ()
				  (restart-case (progn (error "boom") :not-reached)
				    (:reconnect (x) (list :reconnected x))))
				(print (handler-bind ((error (lambda (c) (invoke-restart :reconnect 42))))
				         (rs-f)))
				""")).isEqualTo("(:RECONNECTED 42)");
	}

	@Test
	void ehHandlerBindDecliningHandlerFallsThroughToHandlerCase() throws Exception {
		assertThat(compileAndRunEh("""
				(print (let ((log nil))
				         (handler-case
				             (handler-bind ((error (lambda (c) (setq log (cons :seen log)))))
				               (error "boom"))
				           (error (e) (cons :caught log)))))
				""")).isEqualTo("(:CAUGHT :SEEN)");
	}

	@Test
	void ehHandlerBindReceivesTypedConditionWithSlots() throws Exception {
		assertThat(compileAndRunEh("""
				(define-condition hb-err (error) ((v :initarg :v :reader hb-err-v)))
				(print (handler-bind ((hb-err (lambda (c) (invoke-restart :use (hb-err-v c)))))
				         (restart-case (error 'hb-err :v 7)
				           (:use (x) (list :slot x)))))
				""")).isEqualTo("(:SLOT 7)");
	}

	@Test
	void ehHandlerBindSeesASignaledErrorAndAnUndefinedFunctionError() throws Exception {
		// The rove shape within this backend's reach: everything thrown
		// on the $lisp-cond tag -- a typed signal, an internal %error such as the
		// undefined-function stub -- lands in the handler-bind expansion's
		// %hb-guard pad. A raw TRAP ((car 1), a failed cast) stays uncatchable:
		// the documented three-point-spectrum divergence
		// (.kb/error-handling.md).
		assertThat(compileAndRunEh("""
				(print (block b
				  (handler-bind ((error (lambda (e) (return-from b (list :typed (typep e 'type-error))))))
				    (error 'type-error :datum 1 :expected-type 'cons))))
				""")).isEqualTo("(:TYPED T)");
		assertThat(compileAndRunEh("""
				(print (block b (handler-bind ((error (lambda (e) (return-from b :caught)))) (no-such-function-xyz 1))))
				""")).isEqualTo(":CAUGHT");
	}

	@Test
	void ehAnInnerHandlerCaseShadowsAnEnclosingHandlerBind() throws Exception {
		// CLHS 9.1.4.1: handlers run MOST RECENT FIRST and handler-case transfers
		// control, so the nearer handler-case handles the condition and the enclosing
		// handler-bind handler never runs. A raw TRAP is out of reach here (the
		// three-point spectrum), so the probes signal.
		assertThat(compileAndRunEh("""
				(print (block b
				         (handler-bind ((error (lambda (e) (return-from b :outer-ran))))
				           (handler-case (error "boom") (error () :caught)))))
				""")).isEqualTo(":CAUGHT");
		assertThat(compileAndRunEh("""
				(print (block b
				         (handler-bind ((error (lambda (e) (return-from b :outer-ran))))
				           (ignore-errors (error "boom")))))
				""")).isEqualTo("NIL");
	}

	@Test
	void ehAnInnerHandlerCaseWhoseClausesDoNotMatchStillLetsTheHandlerBindRun() throws Exception {
		assertThat(compileAndRunEh("""
				(print (block b
				         (handler-bind ((error (lambda (e) (return-from b :outer-ran))))
				           (handler-case (error "boom") (end-of-file () :caught)))))
				""")).isEqualTo(":OUTER-RAN");
	}

	@Test
	void ehAHandlerCaseClauseBodyDoesNotCatchWhatItSignals() throws Exception {
		assertThat(compileAndRunEh("""
				(print (block b
				         (handler-bind ((error (lambda (e) (return-from b :outer-ran))))
				           (handler-case (error "boom") (error () (error "again"))))))
				""")).isEqualTo(":OUTER-RAN");
	}

	@Test
	void ehHandlerBindRunsEachClusterOnceInnermostFirst() throws Exception {
		assertThat(compileAndRunEh("""
				(print (let ((log nil))
				         (handler-case
				             (handler-bind ((error (lambda (c) (setq log (cons :outer log)))))
				               (handler-bind ((error (lambda (c) (setq log (cons :inner log)))))
				                 (no-such-function-xyz 1)))
				           (error (e) (cons :caught log)))))
				""")).isEqualTo("(:CAUGHT :OUTER :INNER)");
	}

	@Test
	void ehHandlerBindHandlerAndHandlerCaseSeeTheSameInstance() throws Exception {
		// The signal path attaches the instance %run-handlers saw to the throw, so
		// the handlers run once and handler-case dispatches on the identical
		// condition.
		assertThat(compileAndRunEh("""
				(print (let ((seen nil) (n 0))
				         (handler-case
				             (handler-bind ((error (lambda (c) (setq n (+ n 1)) (setq seen c))))
				               (error "boom"))
				           (error (e) (list :caught (eq e seen) n)))))
				""")).isEqualTo("(:CAUGHT T 1)");
	}

	@Test
	void standardConditionTypeNamesAreClSymbols() throws Exception {
		// cl owns the condition type names, so a (:use #:cl) package
		// spells them bare -- which is also what makes the RUNTIME type test below
		// match the registry's plain class name.
		assertThat(compileAndRun("""
				(defpackage #:ct-pkg (:use #:cl))
				(in-package #:ct-pkg)
				(print (list 'type-error 'condition 'warning 'division-by-zero 'undefined-function 'end-of-file))
				""")).isEqualTo("(TYPE-ERROR CONDITION WARNING DIVISION-BY-ZERO UNDEFINED-FUNCTION END-OF-FILE)");
	}

	@Test
	void runtimeTypeSpecifierNamingASeededConditionClass() throws Exception {
		assertThat(compileAndRun("""
				(print (list (let ((ty 'type-error)) (typep (make-condition 'type-error) ty))
				             (let ((ty 'condition)) (typep (make-condition 'simple-warning) ty))
				             (let ((ty 'type-error)) (typep (make-condition 'simple-warning) ty))))
				""")).isEqualTo("(T T NIL)");
	}

	@Test
	void ehAnUndefinedFunctionCallIsCaughtAsASimpleErrorHere() throws Exception {
		// The DIVERGENCE pin: the interpreter and the JVM answer
		// :UNDEFINED for this, but the call-time stub cannot construct a typed
		// condition -- it is produced during body compilation, after the layout scan
		// chose what to bake -- so this backend catches the text as a simple-error.
		// The raw-trap families ((car 1), (/ 1 0)) are not catchable here at all.
		// Reason and re-evaluation trigger: .kb/error-handling.md,
		// LispMacroExpander.undefinedFunctionCallStub.
		assertThat(compileAndRunEh("""
				(print (handler-case (no-such-function-xyz 1)
				         (undefined-function (e) :undefined)
				         (simple-error (e) (list :simple (princ-to-string e)))))
				""")).isEqualTo("(:SIMPLE \"The function NO-SUCH-FUNCTION-XYZ is undefined\")");
	}

	@Test
	void ehFindRestartReturnsObjectAndGoLeavesClauseIntoTagbody() throws Exception {
		// The postmodern transaction.lisp shape: find-restart with a condition
		// argument returns a first-class restart object, invoke-restart on the
		// object transfers to the clause, and the clause body (go start) re-enters
		// the enclosing tagbody -- a lexical, same-function go.
		assertThat(compileAndRunEh("""
				(defun rs-retry (c)
				  (let ((r (find-restart 'retry-me c)))
				    (if (null r) :none (invoke-restart r))))
				(print (handler-bind ((error (lambda (c) (rs-retry c))))
				         (let ((n 0))
				           (tagbody start
				             (restart-case
				                 (progn (setq n (+ n 1)) (when (< n 3) (error "again")))
				               (retry-me () (go start))))
				           n)))
				""")).isEqualTo("3");
	}

	@Test
	void ehRestartsDisappearOutsideTheirExtent() throws Exception {
		assertThat(compileAndRunEh("(print (progn (restart-case 1 (gone () nil)) (find-restart 'gone)))"))
			.isEqualTo("NIL");
		assertThat(compileAndRunEh("(print (handler-case (invoke-restart :nope) (error (e) :no-restart)))"))
			.isEqualTo(":NO-RESTART");
	}

	@Test
	void ehComputeRestartsListsInnermostFirstAndRestartNameReads() throws Exception {
		assertThat(compileAndRunEh("""
				(print (restart-case
				           (restart-case (mapcar (function restart-name) (compute-restarts))
				             (aaa () nil)
				             (bbb () nil))
				         (ccc () nil)))
				""")).isEqualTo("(AAA BBB CCC)");
	}

	@Test
	void ehRestartCasePassesFiveArguments() throws Exception {
		// The postmodern roles.lisp shape: a restart taking 5 arguments.
		assertThat(compileAndRunEh("""
				(print (handler-bind ((error (lambda (c) (invoke-restart :five 1 2 3 4 5))))
				         (restart-case (error "x")
				           (:five (a b c d e) (list a b c d e)))))
				""")).isEqualTo("(1 2 3 4 5)");
	}

	@Test
	void ehNestedHandlerBindLayersInnerDeclinesOuterInvokes() throws Exception {
		// The prepare.lisp shape: nested handler-bind layers around one
		// restart-case; the inner cluster's handler declines (returns), the outer
		// cluster's handler invokes the restart.
		assertThat(compileAndRunEh("""
				(print (let ((log nil))
				         (handler-bind ((error (lambda (c) (invoke-restart :reconnect))))
				           (handler-bind ((error (lambda (c) (setq log (cons :inner-saw log)))))
				             (restart-case (error "conn lost")
				               (:reconnect () (cons :reconnected log)))))))
				""")).isEqualTo("(:RECONNECTED :INNER-SAW)");
	}

	@Test
	void ehHandlerSignalingInsideHandlerDoesNotSeeOwnCluster() throws Exception {
		assertThat(compileAndRunEh("""
				(print (handler-case
				           (handler-bind ((error (lambda (c) (error "inner"))))
				             (error "outer"))
				         (error (e) (simple-condition-format-control e))))
				""")).isEqualTo("\"inner\"");
	}

	@Test
	void ehRestartBindInvokesFunctionAtInvocationPoint() throws Exception {
		assertThat(compileAndRunEh("""
				(print (let ((hit nil))
				         (restart-bind ((poke (lambda (v) (setq hit v))))
				           (invoke-restart 'poke 9)
				           hit)))
				""")).isEqualTo("9");
	}

	@Test
	void ehWithSimpleRestartReturnsNilAndT() throws Exception {
		assertThat(compileAndRunEh("""
				(print (handler-bind ((error (lambda (c) (invoke-restart 'skip))))
				         (multiple-value-list (with-simple-restart (skip "Skip it") (error "x")))))
				""")).isEqualTo("(NIL T)");
	}

	@Test
	void ehCerrorEstablishesContinueRestart() throws Exception {
		assertThat(compileAndRunEh("""
				(print (handler-bind ((error (lambda (c) (continue))))
				         (list :after (cerror "Continue." "problem"))))
				""")).isEqualTo("(:AFTER NIL)");
	}

	@Test
	void ehSignalRunsHandlerBindHandlersAndReturnsNil() throws Exception {
		assertThat(compileAndRunEh("""
				(print (let ((log nil))
				         (handler-bind ((condition (lambda (c) (setq log :ran))))
				           (signal "s"))
				         log))
				""")).isEqualTo(":RAN");
	}

	@Test
	void ehMuffleWarningAbortsWarnOutput() throws Exception {
		assertThat(compileAndRunEh("""
				(print (handler-bind ((warning (lambda (w) (muffle-warning))))
				         (list :done (warn "noise"))))
				""")).isEqualTo("(:DONE NIL)");
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
				(defun handle (env)
				  (list 200 nil (list "backend " (getf env :path-info))))
				(rontolisp:http-handler 'handle)
				""", null);
		// blocking-read signals rontolisp:wit-error on the `closed` arm (a WIT result's
		// error arm), so reading to EOF needs handler-case, which puts the module in EH
		// mode.
		byte[] fetchBytes = compileWitImportComponent(vendoredWasiHttpWit(), """
				(rontolisp:wit-import "iface.wit" :interface "wasi:http/types@0.3.0" :package http)
				(rontolisp:wit-import "iface.wit" :interface "wasi:http/client@0.3.0" :package client)

				;; body-stream-read answers the chunk -- a packed (unsigned-byte 8)
				;; vector, the octets as read -- immediately, or -- when the host
				;; reports the read in flight -- a PENDING future the scheduler settles;
				;; await passes an immediate chunk through and suspends on the pending
				;; one, so stream reads belong in an async function.
				(rontolisp:async-defun read-all (stream acc)
				  (let ((chunk (rontolisp:await (http:body-stream-read stream))))
				    (if (or (null chunk) (= (length chunk) 0))
				        acc
				        (rontolisp:await (read-all stream (concatenate 'string acc (map 'string #'code-char chunk)))))))

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
						+ " pid=$!; trap 'kill $pid 2>/dev/null' EXIT;"
						+ " sleep 0.3; kill -0 $pid 2>/dev/null || { echo 'backend wasmtime serve exited immediately; log:' 1>&2;"
						+ " cat /tmp/wit-fetch-backend.log 1>&2; exit 1; };"
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
				(rontolisp:async-defun handle (env)
				  (let ((body (rontolisp:await (rontolisp:read-all (getf env :raw-body)))))
				    (list 200 nil (list "echo " body))))
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
				        (rontolisp:await (read-all stream (concatenate 'string acc (map 'string #'code-char chunk)))))))

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
						+ " pid=$!; trap 'kill $pid 2>/dev/null' EXIT;"
						+ " sleep 0.3; kill -0 $pid 2>/dev/null || { echo 'backend wasmtime serve exited immediately; log:' 1>&2;"
						+ " cat /tmp/wit-post-backend.log 1>&2; exit 1; };"
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

	// The twelve wasi_snapshot_preview1 imports of a component-mode core, as trap stubs:
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
			types
				.addFunc(
						new am.ik.wasm.Type[] { am.ik.wasm.Type.I32, am.ik.wasm.Type.I32, am.ik.wasm.Type.I32,
								am.ik.wasm.Type.I64, am.ik.wasm.Type.I32 },
						new am.ik.wasm.Type[] { am.ik.wasm.Type.I32 }); // 5 fd_readdir
			types.addFunc(new am.ik.wasm.Type[] { am.ik.wasm.Type.I32, am.ik.wasm.Type.I32, am.ik.wasm.Type.I32 },
					new am.ik.wasm.Type[] { am.ik.wasm.Type.I32 }); // 6
																	// fd_prestat_dir_name
		});
		w.writeFunction(f -> f.addFunction(0) // fd_write
			.addFunction(0) // fd_read
			.addFunction(1) // path_open
			.addFunction(2) // fd_close
			.addFunction(3) // random_get
			.addFunction(4) // clock_time_get
			.addFunction(3) // environ_sizes_get
			.addFunction(3) // environ_get
			.addFunction(5) // fd_readdir
			.addFunction(3) // fd_prestat_get
			.addFunction(6) // fd_prestat_dir_name
			.addFunction(3)); // fd_filestat_get
		w.writeExport(e -> e.addExport("fd_write", am.ik.wasm.ExternalKind.FUNCTION, 0)
			.addExport("fd_read", am.ik.wasm.ExternalKind.FUNCTION, 1)
			.addExport("path_open", am.ik.wasm.ExternalKind.FUNCTION, 2)
			.addExport("fd_close", am.ik.wasm.ExternalKind.FUNCTION, 3)
			.addExport("random_get", am.ik.wasm.ExternalKind.FUNCTION, 4)
			.addExport("clock_time_get", am.ik.wasm.ExternalKind.FUNCTION, 5)
			.addExport("environ_sizes_get", am.ik.wasm.ExternalKind.FUNCTION, 6)
			.addExport("environ_get", am.ik.wasm.ExternalKind.FUNCTION, 7)
			.addExport("fd_readdir", am.ik.wasm.ExternalKind.FUNCTION, 8)
			.addExport("fd_prestat_get", am.ik.wasm.ExternalKind.FUNCTION, 9)
			.addExport("fd_prestat_dir_name", am.ik.wasm.ExternalKind.FUNCTION, 10)
			.addExport("fd_filestat_get", am.ik.wasm.ExternalKind.FUNCTION, 11));
		w.writeCode(codes -> {
			for (int i = 0; i < 12; i++) {
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
		// evaluate right-to-left, so the store must be ordered explicitly.
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
	void compileRank2InitialContentsFillsRowMajor() throws Exception {
		// A literal rank >= 2 :initial-contents used to be refused outright on
		// the compiled backends; it now lowers to a nested row-major fill, matching the
		// interpreter -- for the general boxed representation and for the packed
		// float-array one (the silent-zero bug the interpreter fix addressed).
		assertThat(compileAndRun("(print (aref (make-array '(2 2) :initial-contents '((1 2) (3 4))) 1 1))"))
			.isEqualTo("4");
		assertThat(compileAndRun("(print (aref (make-array '(2 3) :initial-contents '((1 2 3) (4 5 6))) 1 0))"))
			.isEqualTo("4");
		assertThat(compileAndRun("""
				(print (make-array '(2 2) :element-type 'double-float
				                    :initial-contents '((1.0 2.0) (3.0 4.0))))
				""")).isEqualTo("#d((1.0 2.0) (3.0 4.0))");
		assertThat(compileAndRun("""
				(print (make-array '(2 2 2) :initial-contents '(((1 2) (3 4)) ((5 6) (7 8)))))
				""")).isEqualTo("#3A(((1 2) (3 4)) ((5 6) (7 8)))");
	}

	@Test
	void compilePackedIntVectorIntrospection() throws Exception {
		assertThat(compileAndRun("""
				(let ((a (make-array 3 :element-type '(unsigned-byte 8))))
				  (print (list (array-element-type a) (arrayp a) (vectorp a) (array-dimensions a)
				               (typep a '(simple-array (unsigned-byte 8) (*))))))
				""")).isEqualTo("((UNSIGNED-BYTE 8) T T (3) T)");
		// A rank-n shape (runtime-detected) and a fill-pointer combination keep the
		// general boxed representation, but REMEMBER the element type they were asked
		// for (todo-611).
		assertThat(compileAndRun("(print (array-element-type (make-array '(2 2) :element-type '(unsigned-byte 8))))"))
			.isEqualTo("(UNSIGNED-BYTE 8)");
		assertThat(compileAndRun("""
				(let ((a (make-array '(2 2) :element-type '(unsigned-byte 8) :initial-element 3)))
				  (setf (aref a 1 1) 9)
				  (print (list (aref a 0 0) (aref a 1 1))))
				""")).isEqualTo("(3 9)");
	}

	@Test
	void compileVectorpChecksTheRank() throws Exception {
		// Same contract as the interpreter's evalVectorpChecksTheRank: a vector is a
		// rank-1 array, so every other rank is an arrayp but not a vectorp, and the
		// atomic vector/simple-vector specifiers answer alike.
		assertThat(compileAndRun("""
				(print (list (vectorp #2A((1 2) (3 4))) (vectorp (make-array nil)) (arrayp #2A((1 2)))
				             (vectorp (vector 1 2)) (vectorp "ab")
				             (vectorp (make-array 3 :element-type '(unsigned-byte 8)))
				             (funcall #'vectorp #2A((1 2)))))
				""")).isEqualTo("(NIL NIL T T T T NIL)");
		assertThat(compileAndRun("""
				(print (list (typep #2A((1 2)) 'vector) (typep #2A((1 2)) 'simple-vector)
				             (typep #2A((1 2)) 'array) (typep (vector 1) 'vector)
				             (typecase #2A((1 2)) (vector 'vec) (array 'arr) (t 'other))
				             (let ((s 'vector)) (typep #2A((1 2)) s))))
				""")).isEqualTo("(NIL NIL T T ARR NIL)");
	}

	@Test
	void compileCharacterElementTypeAboveRankOneIsAGeneralArray() throws Exception {
		// Same contract as the interpreter's
		// evalCharacterElementTypeAboveRankOneIsAGeneralArray: the character marker means
		// "a rank-1 character array", so nothing above rank 1 carries it and the value is
		// the plain general array. This backend used to mark a rank-2 request and answer
		// T to stringp, handing every string operation a value it cannot index. type-of
		// is a prelude defun, so the program needs the CLI pipeline's splice.
		assertThat(compileAndRunPrelude("""
				(let ((b (make-array '(2 2) :element-type 'character :initial-element #\\a)))
				  (print (list (stringp b) (array-element-type b) (array-dimensions b) (type-of b) (vectorp b)))
				  (setf (aref b 1 1) #\\z)
				  (print b))
				(print (aref (make-array '(2 2) :element-type 'character) 0 1))
				(let ((c (make-array '(2 2) :element-type 'character
				                     :initial-contents '((#\\a #\\b) (#\\c #\\d)))))
				  (print (list (stringp c) (aref c 1 0) (type-of c))))
				(let ((s (make-array 3 :element-type 'character :initial-element #\\z)))
				  (print (list (stringp s) (array-element-type s) (type-of s) s)))
				(print (list (aref (make-array 3 :element-type 'double-float :adjustable t) 0)
				             (aref (make-array 3 :element-type 'single-float :fill-pointer 3) 2)))
				""")).isEqualTo("""
				(NIL CHARACTER (2 2) (SIMPLE-ARRAY CHARACTER (2 2)) NIL)
				#2A((#\\a #\\a) (#\\a #\\z))
				#\\Space
				(NIL #\\c (SIMPLE-ARRAY CHARACTER (2 2)))
				(T CHARACTER STRING "zzz")
				(0.0 0.0)""");
	}

	@Test
	void compileGeneralArrayRemembersItsDeclaredElementType() throws Exception {
		// Same contract as the interpreter's
		// evalGeneralArrayRemembersItsDeclaredElementType: a specialized element type
		// that selects no representation of its own is still REMEMBERED on the general
		// array it lands in -- array-element-type answers it, type-of builds the
		// compound specifier from it, and an unsupplied element takes its own zero.
		// type-of is a prelude defun, so the program needs the CLI pipeline's splice.
		assertThat(compileAndRunPrelude("""
				(defun ret-probe (a) (list (array-element-type a) (type-of a)))
				(print (ret-probe (make-array '(2 2) :element-type '(unsigned-byte 8))))
				(print (ret-probe (make-array '(2 3) :element-type 'character :initial-element #\\a)))
				(print (ret-probe (make-array 4 :element-type 'double-float :fill-pointer 0)))
				(print (ret-probe (make-array 4 :element-type '(unsigned-byte 16) :adjustable t)))
				(print (ret-probe (make-array 3)))
				(print (list (aref (make-array '(2 2) :element-type '(unsigned-byte 8)) 0 0)
				             (aref (make-array 3 :element-type '(unsigned-byte 16) :adjustable t) 2)
				             (aref (make-array 3 :element-type 'double-float :adjustable t) 0)))
				(let ((a (make-array '(2 2) :element-type '(unsigned-byte 8))))
				  (print (list (typep a '(simple-array (unsigned-byte 8) (2 2))) (typep a (type-of a)))))
				(let ((v (make-array 2 :element-type '(unsigned-byte 8) :fill-pointer 0 :adjustable t)))
				  (vector-push-extend 7 v)
				  (vector-push-extend 8 v)
				  (vector-push-extend 9 v)
				  (print (list v (array-element-type v) (fill-pointer v))))
				(print (list (array-has-fill-pointer-p (make-array 4 :element-type 'double-float))
				             (adjustable-array-p (make-array 4 :element-type '(unsigned-byte 8)))))
				""")).isEqualTo("""
				((UNSIGNED-BYTE 8) (SIMPLE-ARRAY (UNSIGNED-BYTE 8) (2 2)))
				(CHARACTER (SIMPLE-ARRAY CHARACTER (2 3)))
				(DOUBLE-FLOAT (VECTOR DOUBLE-FLOAT 4))
				((UNSIGNED-BYTE 16) (VECTOR (UNSIGNED-BYTE 16) 4))
				(T (SIMPLE-VECTOR 3))
				(0 0 0.0)
				(T T)
				(#(7 8 9) (UNSIGNED-BYTE 8) 3)
				(NIL NIL)""");
	}

	@Test
	void compileRuntimeElementTypePicksTheSameArrayAsALiteralOne() throws Exception {
		// Same contract as the interpreter's
		// evalRuntimeElementTypePicksTheSameArrayAsALiteralOne, and the same program: a
		// :element-type held in a VARIABLE reaches the representation, the remembered
		// type and the zero fill a literal spelling would. This backend gets there
		// through the %make-array-et prelude helper, so the program needs the CLI
		// pipeline's splice; both helper shapes are exercised -- the plain one and the
		// :fill-pointer one.
		assertThat(compileAndRunPrelude("""
				(defun mk (et) (make-array 4 :element-type et))
				(defun mk2 (et) (make-array '(2 2) :element-type et))
				(defun mkfp (et) (make-array 4 :element-type et :fill-pointer 2))
				(defun mki (et x) (make-array 3 :element-type et :initial-element x))
				(print (list (array-element-type (mk '(unsigned-byte 8)))
				             (type-of (mk '(unsigned-byte 8)))
				             (aref (mk '(unsigned-byte 8)) 0)
				             (zerop (aref (mk 'double-float) 3))
				             (array-element-type (mk2 'character))
				             (stringp (mk 'character))
				             (array-element-type (mkfp 'double-float))
				             (type-of (mkfp 'double-float))))
				(print (list (array-element-type (mk 'fixnum)) (type-of (mk 'bit)) (aref (mk 'fixnum) 0)))
				(print (list (aref (mki '(unsigned-byte 8) 7) 0) (aref (mki 'character #\\z) 0)
				             (aref (mki t 'a) 0)))
				""")).isEqualTo(
				"""
						((UNSIGNED-BYTE 8) (SIMPLE-ARRAY (UNSIGNED-BYTE 8) (4)) 0 T CHARACTER T DOUBLE-FLOAT (VECTOR DOUBLE-FLOAT 4))
						(T (SIMPLE-VECTOR 4) NIL)
						(7 #\\z A)""");
	}

	@Test
	void compileRuntimeElementTypeResolvesADeftypeAlias() throws Exception {
		// Same contract as the interpreter's
		// evalRuntimeElementTypeResolvesADeftypeAlias, and the same program: a deftype
		// alias held in a VARIABLE picks the representation its expansion designates.
		// The designator goes through the injected %make-array-et-alias resolver before
		// the %make-array-et dispatch, which needs the CLI pipeline's prelude splice.
		assertThat(compileAndRunPrelude("""
				(deftype octet () '(unsigned-byte 8))
				(deftype byte-buffer () 'octet)
				(deftype char-buf () 'character)
				(defun mk (et n) (make-array n :element-type et))
				(defun mkfp (et) (make-array 4 :element-type et :fill-pointer 2))
				(print (list (array-element-type (mk 'octet 4))
				             (aref (mk 'octet 4) 0)
				             (type-of (mk 'byte-buffer 4))
				             (stringp (mk 'char-buf 3))
				             (array-element-type (mkfp 'octet))
				             (array-element-type (mk 'double-float 2))))
				"""))
			.isEqualTo("((UNSIGNED-BYTE 8) 0 (SIMPLE-ARRAY (UNSIGNED-BYTE 8) (4)) T (UNSIGNED-BYTE 8) DOUBLE-FLOAT)");
		// A designator naming no alias is left alone, and a self-referential deftype
		// terminates on the hop bound instead of spinning.
		assertThat(compileAndRunPrelude("""
				(deftype loopy () 'loopy)
				(defun mk (et) (make-array 2 :element-type et))
				(print (list (array-element-type (mk 'not-a-type)) (array-element-type (mk 'loopy))))
				""")).isEqualTo("(T T)");
	}

	@Test
	void compileMakeArrayEvaluatesItsDimensionsExactlyOnce() throws Exception {
		// Same contract as the interpreter's
		// evalMakeArrayEvaluatesItsDimensionsExactlyOnce.
		assertThat(compileAndRun("""
				(let ((n 0))
				  (flet ((bump () (setq n (+ n 1)) 3))
				    (let ((s (make-array (bump) :element-type 'character :initial-element #\\k)))
				      (print (list s n)))))
				""")).isEqualTo("(\"kkk\" 1)");
	}

	@Test
	void compileFillWritesEveryElementInRange() throws Exception {
		// Same contract as the interpreter's fillWritesEveryElementInRange.
		assertThat(compileAndRun("""
				(let* ((a (make-array 5 :element-type '(unsigned-byte 8) :initial-element 9))
				       (same (eq a (fill a 300 :start 1 :end 4))))
				  (print (list a same (array-element-type a))))
				""")).isEqualTo("(#(9 44 44 44 9) T (UNSIGNED-BYTE 8))");
		assertThat(compileAndRun("(let ((l (list 1 2 3 4 5))) (fill l 0 :start 1 :end 3) (print l))"))
			.isEqualTo("(1 0 0 4 5)");
		assertThat(compileAndRun(
				"(print (fill (make-array 5 :element-type 'character :initial-element #\\a) #\\z :start 2))"))
			.isEqualTo("\"aazzz\"");
		assertThat(compileAndRun("(print (funcall #'fill (make-array 2 :initial-element 1) 8))")).isEqualTo("#(8 8)");
	}

	@Test
	void compileFuncallWiderThanTheCallableLimitGoesThroughApply() throws Exception {
		// The FIXED per-arity dispatcher block stops at MAX_CALLABLE_ARITY, so an
		// 11-argument funcall used to compile to a call-time "not supported" signal --
		// a trap on a LIVE path. It now gets a dispatcher of its own, appended after the
		// fixed block; past MAX_EXTRA_CALL_ARITY more of them,
		// WasmArityBundler.spreadOverArityFuncalls still rewrites the site to
		// (apply f (list ...)) for the SPREAD dispatcher to serve. A keyword lambda list
		// is how a program reaches the limit: the arguments go through verbatim for the
		// callee to parse, so four keywords is eleven arguments. chipz's %inflate is
		// exactly this shape.
		assertThat(compileAndRun("""
				(defun k4 (a b c &key (input-start 0) input-end (output-start 0) output-end)
				  (list a b c input-start input-end output-start output-end))
				(defun pick () #'k4)
				(print (funcall (pick) 1 2 3 :input-start 4 :input-end 5 :output-start 6 :output-end 7))
				(print (funcall #'k4 1 2 3 :input-start 4 :input-end 5 :output-start 6 :output-end 7))
				""")).isEqualTo("(1 2 3 4 5 6 7)\n(1 2 3 4 5 6 7)");
		// Twelve plain positional arguments through a function value, the same route.
		assertThat(compileAndRun("""
				(defun v (&rest xs) (length xs))
				(defun pickv () #'v)
				(print (funcall (pickv) 1 2 3 4 5 6 7 8 9 10 11 12))
				""")).isEqualTo("12");
	}

	@Test
	void compileFuncallEitherSideOfTheDerivedArityCeilingAnswersTheSame() throws Exception {
		// The ceiling is derived from the program's own widest call, so both the
		// per-arity route (11..14 arguments) and the apply/SPREAD route (15, past
		// MAX_EXTRA_CALL_ARITY) have to answer identically -- and both at the TOP LEVEL,
		// which compiles through WasmAsyncEmit.freshCtx: a context that did not carry
		// the ceiling compiled the site to a call-time signal there while the same form
		// inside a defun worked.
		for (int width : new int[] { 10, 11, 14, 15 }) {
			StringBuilder args = new StringBuilder();
			for (int i = 1; i <= width; i++) {
				args.append(' ').append(i);
			}
			assertThat(compileAndRun("""
					(defun v (&rest xs) (length xs))
					(defun pickv () #'v)
					(print (funcall (pickv)%s))
					""".formatted(args))).as("funcall of width %d", width).isEqualTo(String.valueOf(width));
		}
	}

	@Test
	void compileMapcarOverMoreListsThanTheFixedDispatcherBlockWorks() throws Exception {
		// The map family funcalls its function once per element of each list, so its
		// list count picks a per-arity dispatcher exactly as a funcall's argument count
		// does -- and it used to compute that index without a ceiling check, which past
		// the fixed block silently addressed the NEXT runtime helper and emitted a
		// module that does not validate. Eleven lists is inside the derived ceiling now.
		// The mapped function is variadic: a fixed ELEVEN-parameter defun is past the
		// defun-side limit, which the bundler rewrites and then refuses to hand out as a
		// function value -- a separate rule, and one this change deliberately leaves at
		// MAX_CALLABLE_ARITY.
		assertThat(compileAndRun("""
				(defun s11 (&rest xs) (reduce #'+ xs))
				(print (mapcar #'s11 '(1) '(2) '(3) '(4) '(5) '(6) '(7) '(8) '(9) '(10) '(11)))
				""")).isEqualTo("(66)");
	}

	@Test
	void compileMakeArrayElementTypeResolvesADeftypeAlias() throws Exception {
		// Same contract as the interpreter's makeArrayElementTypeResolvesADeftypeAlias:
		// an alias picks the representation its expansion designates. salza2 allocates
		// every buffer as :element-type 'octet.
		assertThat(compileAndRun("""
				(deftype octet () '(unsigned-byte 8))
				(deftype byte-buffer () 'octet)
				(deftype real-double () 'double-float)
				(deftype char-buf () 'character)
				(let ((a (make-array 3 :element-type 'octet))
				      (b (make-array 2 :element-type 'byte-buffer :initial-contents '(1 300)))
				      (c (make-array 2 :element-type 'real-double))
				      (s (make-array 3 :element-type 'char-buf :initial-element #\\x)))
				  (print (list (array-element-type a) (aref a 0) b (array-element-type c) (aref c 0) (stringp s) s)))
				""")).isEqualTo("((UNSIGNED-BYTE 8) 0 #(1 44) DOUBLE-FLOAT 0.0 T \"xxx\")");
		assertThat(compileAndRun("(print (array-element-type (make-array 2 :element-type 'not-a-type)))"))
			.isEqualTo("T");
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

	// Thread creation does not exist on WASM (single-threaded by construction): the
	// bordeaux-threads/bt2 shim's spawn entry points SIGNAL at call time (never run
	// inline -- that would turn a compile-time error into a silently sequential
	// program) while threadp answers nil, so clack's non-threaded path compiles and
	// runs. The shim must be spliced with the TARGET backend's features, so the
	// #+rontolisp-wasm defuns are the ones compiled (the *supports-threads-p* rule).
	@Test
	void bt2ThreadEntryPointsSignalWhileThreadpAnswersNil() throws Exception {
		List<LispVal> program = am.ik.rontolisp.cli.LoadInliner.inline(LispReader.readAllFromString("""
				(asdf:load-system "bordeaux-threads")
				(print (bt2:threadp 42))
				(print (handler-case (bt2:thread-alive-p 42) (error (e) :not-a-thread)))
				(print (handler-case (bt2:make-thread (lambda () 1)) (error (e) :spawn-error)))
				(print bordeaux-threads:*supports-threads-p*)
				"""), path -> {
			throw new java.io.FileNotFoundException(path);
		}, null, List.of(), am.ik.rontolisp.reader.Features.WASM);
		assertThat(compileAndRunProgram(am.ik.rontolisp.eval.LispPreludeLibrary.process(program)))
			.isEqualTo("NIL\n:NOT-A-THREAD\n:SPAWN-ERROR\nNIL");
	}

	@Test
	void shortFormMethodCombination() throws Exception {
		// yason's encode-slots hook: the operator over EVERY applicable qualified
		// method, in specificity order, with :most-specific-last reversing it.
		assertThat(compileAndRun("""
				(defclass mc-base () ())
				(defclass mc-leaf (mc-base) ())
				(defgeneric mc-trace (x) (:method-combination progn :most-specific-last))
				(defmethod mc-trace progn ((x mc-base)) (print :base))
				(defmethod mc-trace progn ((x mc-leaf)) (print :leaf))
				(defgeneric mc-sum (x) (:method-combination +))
				(defmethod mc-sum + ((x mc-base)) 1)
				(defmethod mc-sum + ((x mc-leaf)) 100)
				(mc-trace (make-instance 'mc-leaf))
				(print (mc-sum (make-instance 'mc-leaf)))
				(print (mc-sum (make-instance 'mc-base)))
				""")).isEqualTo(":BASE\n:LEAF\n101\n1");
	}

}
