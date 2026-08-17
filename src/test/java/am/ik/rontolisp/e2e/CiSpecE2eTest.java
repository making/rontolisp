package am.ik.rontolisp.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import am.ik.rontolisp.codegen.wasm.WasmModuleInspector;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * End-to-end test that runs the whole {@code ci-spec.yaml} program through the rontolisp
 * native binary in all four backend modes (interpreter, JVM, WASM Preview 1, and WASM as
 * a WASI 0.3 component) and compares the output of each case.
 * <p>
 * The cases share global state, so they are concatenated into a single program and each
 * backend is compiled/run once. The output is sliced back per case using each case's
 * declared expected-line count, so a failure names the exact case (and its source) rather
 * than only a line number.
 * <p>
 * The spec's {@code standalone:} list is the exception: a case whose program ENDS (an
 * uncaught condition) can neither share that run nor be read off standard output, so each
 * one is compiled and run by itself and checked against its stdout, the lines it must put
 * on standard error, and its exit code. See {@link Standalone}.
 * <p>
 * The {@code WASM_COMPONENT} backend compiles with {@code --component} and runs the
 * resulting WASI 0.3 (Preview 3) component with {@code wasmtime run -W gc=y
 *} (the async canonical ABI and stackful lifts are on by default in wasmtime 46+; only
 * the synchronous stream/future built-ins are still feature-gated). The
 * {@code ci-spec.yaml} cases are deterministic and do no file I/O / random / time /
 * getenv, so the component's output is identical to the Preview 1 WASM backend and is
 * checked against the same {@code expected} lines.
 * <p>
 * Runs only when {@code -Drontolisp.binary=<path>} points at a built native binary;
 * otherwise the whole factory is skipped (the regular {@code mvn test} job runs on the
 * JVM before the native binary exists). The two WASM backends are additionally skipped
 * when {@code wasmtime} is not on the {@code PATH}.
 */
class CiSpecE2eTest {

	private static final String SPEC_RESOURCE = "/ci-spec.yaml";

	enum Backend {

		INTERPRETER, JVM, WASM, WASM_COMPONENT

	}

	record Case(String name, String source, @Nullable String expected,
			@Nullable Map<String, String> expectedByBackend) {

		List<String> expectedLines(Backend backend) {
			String text = null;
			if (this.expectedByBackend != null) {
				text = this.expectedByBackend.get(backend.name().toLowerCase());
				// A WASI 0.3 component mirrors the Preview 1 WASM backend's output, so
				// reuse a "wasm" override when no component-specific one is declared.
				if (text == null && backend == Backend.WASM_COMPONENT) {
					text = this.expectedByBackend.get(Backend.WASM.name().toLowerCase());
				}
			}
			if (text == null) {
				text = this.expected;
			}
			return splitLines(text == null ? "" : text);
		}
	}

	/**
	 * A case that cannot join the shared corpus because running it ENDS the program: an
	 * uncaught condition takes the process down, and its report goes to standard error,
	 * which the concatenated run neither slices nor keeps. Each one is compiled and run
	 * on its own, per backend.
	 *
	 * @param name the case name, also the basename of its generated program
	 * @param source the whole program
	 * @param stdout the expected standard output, compared line for line
	 * @param stderr lines that must APPEAR on standard error, in order but not
	 * exclusively -- wasmtime prints its own trap report around ours
	 * @param fails whether the program is expected to exit non-zero
	 */
	record Standalone(String name, String source, @Nullable String stdout, @Nullable String stderr, boolean fails) {
	}

	record Spec(List<Case> cases, @Nullable List<Standalone> standalone) {

		List<Standalone> standaloneCases() {
			return this.standalone == null ? List.of() : this.standalone;
		}
	}

	@TempDir
	static Path workDir;

	@TestFactory
	Stream<DynamicNode> e2e() throws Exception {
		String binary = System.getProperty("rontolisp.binary");
		assumeTrue(binary != null, "rontolisp.binary not set; skipping native-binary E2E");
		Path bin = Path.of(binary).toAbsolutePath();
		assumeTrue(Files.isExecutable(bin), () -> "not an executable binary: " + bin);

		Spec spec = loadSpec();
		Path program = writeProgram(spec);
		WasmGuard guard = wasmCompileMemoryGuard(bin, program);

		List<DynamicNode> backends = new ArrayList<>();
		for (Backend backend : Backend.values()) {
			backends.add(backendNode(backend, bin, program, spec, guard));
		}
		return backends.stream();
	}

	/**
	 * Largest emitted WASM function body this test is willing to hand to wasmtime.
	 * <p>
	 * A wasmtime cold compile needs memory superlinear in the size of ONE function body
	 * -- 850 KB of body peaks at 25.8 GB, 630 KB at 15.1 GB -- so a monolithic module
	 * does not fail here, it gets the whole CI runner OOM-killed ("The runner has
	 * received a shutdown signal", no stderr, no timeout, every other backend in the run
	 * cancelled as a fail-fast peer). The bound and its measurements are pinned in
	 * {@code WasmToplevelChunkingTest}, and it is checked for BOTH WASM builds (see
	 * {@link WasmGuard}).
	 */
	private static final int MAX_WASM_FUNCTION_BODY_BYTES = 256 * 1024;

	/**
	 * Why each WASM backend must not be run, or {@code null} per backend when it is safe.
	 * The two are measured separately because the {@code --component} build is NOT the
	 * Preview 1 module plus a wrapper: an async top level (which the corpus has, and
	 * every fetch/serve program has) compiles as an entry+resume pair, so the component's
	 * bodies are cut differently and either one can be the larger. Guarding only the core
	 * build let a 650 KB component body through while the core build's largest was 214
	 * KB, and the runner was OOM-killed on the component leg.
	 */
	private record WasmGuard(@Nullable String wasm, @Nullable String component) {

		@Nullable String forBackend(Backend backend) {
			return backend == Backend.WASM_COMPONENT ? this.component : this.wasm;
		}
	}

	/**
	 * Compiles the corpus both ways and reports why each WASM backend must not be run, or
	 * {@code null} for one that is safe to run. Returning a message rather than launching
	 * wasmtime is the whole point: it turns a machine-killing OOM into an ordinary test
	 * failure that names its own cause.
	 */
	private static WasmGuard wasmCompileMemoryGuard(Path bin, Path program) {
		return new WasmGuard(guardOne(bin, program, "compile-wasm-guard", "guard.wasm", List.of()),
				guardOne(bin, program, "compile-wasm-component-guard", "guard.component.wasm", List.of("--component")));
	}

	private static @Nullable String guardOne(Path bin, Path program, String label, String output,
			List<String> extraFlags) {
		byte[] module;
		try {
			List<String> command = new ArrayList<>(List.of(bin.toString(), program.toString(), "-o", output));
			command.addAll(extraFlags);
			execLabeled(label, command);
			module = Files.readAllBytes(workDir.resolve(output));
		}
		catch (Exception ex) {
			// Not this guard's job to report a compile failure; the backend legs run
			// their own compile and will surface it with their own label.
			return null;
		}
		int largest = WasmModuleInspector.largestFunctionBodySize(module);
		if (largest <= MAX_WASM_FUNCTION_BODY_BYTES) {
			return null;
		}
		return ("refusing to run wasmtime: largest emitted function body of %s is %d bytes, over the %d byte bound. "
				+ "A wasmtime cold compile needs memory superlinear in that number (850 KB of body -> 25.8 GB), "
				+ "so running this module would OOM-kill the CI runner instead of failing. "
				+ "See WasmToplevelChunkingTest.")
			.formatted(output, largest, MAX_WASM_FUNCTION_BODY_BYTES);
	}

	private static DynamicContainer backendNode(Backend backend, Path bin, Path program, Spec spec, WasmGuard guard) {
		if (backend == Backend.WASM || backend == Backend.WASM_COMPONENT) {
			if (!onPath("wasmtime")) {
				return dynamicContainer(backend.name(),
						Stream.of(dynamicTest("(skipped)", () -> abort("wasmtime not on PATH"))));
			}
			String guardFailure = guard.forBackend(backend);
			if (guardFailure != null) {
				return dynamicContainer(backend.name(),
						Stream.of(dynamicTest("(module too large to run)", () -> fail(guardFailure))));
			}
		}
		// The standalone cases are their own programs, so each compiles and runs inside
		// its own lazily-executed test rather than in the one shared run below.
		List<DynamicNode> standalone = spec.standaloneCases()
			.stream()
			.<DynamicNode>map(s -> dynamicTest("standalone: " + s.name(), () -> runStandalone(backend, bin, s)))
			.toList();

		List<String> actual;
		try {
			System.err.println("[CiSpecE2eTest] starting backend " + backend);
			long t0 = System.nanoTime();
			actual = runBackend(backend, bin, program);
			System.err.println("[CiSpecE2eTest] finished backend " + backend + " in "
					+ ((System.nanoTime() - t0) / 1_000_000) + " ms");
		}
		catch (Exception ex) {
			System.err.println("[CiSpecE2eTest] backend " + backend + " failed: " + ex.getMessage());
			return dynamicContainer(backend.name(),
					Stream.concat(Stream.of(dynamicTest("(execution failed)", () -> fail(ex.getMessage(), ex))),
							standalone.stream()));
		}

		List<DynamicNode> tests = new ArrayList<>();
		List<String> expectedAll = spec.cases().stream().flatMap(c -> c.expectedLines(backend).stream()).toList();
		List<String> actualSnapshot = actual;
		tests.add(dynamicTest("total-line-count",
				() -> assertThat(actualSnapshot)
					.as("%s produced a different number of output lines than the spec", backend)
					.hasSize(expectedAll.size())));

		int offset = 0;
		for (Case c : spec.cases()) {
			List<String> expected = c.expectedLines(backend);
			int start = offset;
			offset += expected.size();
			List<String> slice = sublist(actual, start, expected.size());
			tests.add(dynamicTest(c.name(),
					() -> assertThat(slice)
						.as("case '%s' on %s%n--- source ---%n%s--- end source ---", c.name(), backend, c.source())
						.containsExactlyElementsOf(expected)));
		}
		tests.addAll(standalone);
		return dynamicContainer(backend.name(), tests.stream());
	}

	/**
	 * Compiles and runs one {@link Standalone} case on one backend and checks its
	 * standard output, the lines it must put on standard error, and whether it exits
	 * non-zero. Standard error is checked by CONTAINMENT, not equality: a wasm program
	 * that reports and then traps prints wasmtime's own backtrace around our line, and
	 * that text belongs to the host, not to the contract under test.
	 */
	private static void runStandalone(Backend backend, Path bin, Standalone standalone) throws Exception {
		Path source = workDir.resolve(standalone.name() + ".lisp");
		Files.writeString(source, standalone.source());
		String stem = "S" + standalone.name().replaceAll("[^A-Za-z0-9]", "");
		Result result = switch (backend) {
			case INTERPRETER -> execCapture(List.of(bin.toString(), source.toString()));
			case JVM -> {
				execLabeled("compile-jvm-" + standalone.name(),
						List.of(bin.toString(), source.toString(), "-o", stem + ".class"));
				yield execCapture(List.of("java", stem));
			}
			case WASM -> {
				execLabeled("compile-wasm-" + standalone.name(),
						List.of(bin.toString(), source.toString(), "-o", stem + ".wasm"));
				yield execCapture(List.of("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y", "--dir", ".", "--dir",
						"/tmp", stem + ".wasm"));
			}
			case WASM_COMPONENT -> {
				execLabeled("compile-wasm-component-" + standalone.name(),
						List.of(bin.toString(), source.toString(), "-o", stem + ".component.wasm", "--component"));
				yield execCapture(List.of("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--dir", ".", "--dir",
						"/tmp", stem + ".component.wasm"));
			}
		};
		String where = "standalone case '%s' on %s%n--- source ---%n%s--- end source ---%n--- stderr ---%n%s"
			.formatted(standalone.name(), backend, standalone.source(), result.stderr());
		assertThat(splitLines(result.stdout())).as("%s", where)
			.containsExactlyElementsOf(splitLines(standalone.stdout() == null ? "" : standalone.stdout()));
		for (String line : splitLines(standalone.stderr() == null ? "" : standalone.stderr())) {
			assertThat(splitLines(result.stderr())).as("%s", where).contains(line);
		}
		if (standalone.fails()) {
			assertThat(result.exit()).as("%s: expected a non-zero exit", where).isNotZero();
		}
		else {
			assertThat(result.exit()).as("%s", where).isZero();
		}
	}

	private static List<String> runBackend(Backend backend, Path bin, Path program) throws Exception {
		return switch (backend) {
			case INTERPRETER -> execLabeled("interpret", List.of(bin.toString(), program.toString()));
			case JVM -> {
				execLabeled("compile-jvm", List.of(bin.toString(), program.toString(), "-o", "Test.class"));
				yield execLabeled("run-jvm", List.of("java", "Test"));
			}
			case WASM -> {
				execLabeled("compile-wasm", List.of(bin.toString(), program.toString(), "-o", "test.wasm"));
				// --dir . preopens the work dir so the file-stream cases can open files,
				// and --dir /tmp preopens a directory whose NAME is absolute -- the
				// runtime-absolute-path case needs a preopen that can COVER an absolute
				// path, and "." never does (.kb/read-load-streams.md);
				// exceptions=y because the concatenated program contains catching cases
				// (handler-case &c), which put the whole module in EH mode (harmless
				// otherwise).
				yield execLabeled("run-wasm", List.of("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y", "--dir",
						".", "--dir", "/tmp", "test.wasm"));
			}
			case WASM_COMPONENT -> {
				execLabeled("compile-wasm-component",
						List.of(bin.toString(), program.toString(), "-o", "test.component.wasm", "--component"));
				yield execLabeled("run-wasm-component", List.of("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
						"--dir", ".", "--dir", "/tmp", "test.component.wasm"));
			}
		};
	}

	private static List<String> execLabeled(String label, List<String> command)
			throws IOException, InterruptedException {
		System.err.println("[CiSpecE2eTest]   > " + label + " " + command);
		long t0 = System.nanoTime();
		try {
			List<String> out = exec(command);
			System.err.println("[CiSpecE2eTest]   < " + label + " ok in " + ((System.nanoTime() - t0) / 1_000_000)
					+ " ms (" + out.size() + " lines)");
			return out;
		}
		catch (IOException | InterruptedException ex) {
			System.err.println("[CiSpecE2eTest]   ! " + label + " failed after "
					+ ((System.nanoTime() - t0) / 1_000_000) + " ms: " + ex.getMessage());
			throw ex;
		}
	}

	/**
	 * Timeout for a single child-process invocation. All four backends finish the full
	 * ci-spec corpus in well under a minute locally; the ceiling here just needs to be
	 * high enough that a healthy CI runner never trips it and low enough that a hang
	 * surfaces as a clear failure long before the CI job's own time limit kicks in.
	 */
	private static final long EXEC_TIMEOUT_SECONDS = 300;

	/** One child process's whole result; see {@link #execCapture}. */
	private record Result(String stdout, String stderr, int exit) {
	}

	private static List<String> exec(List<String> command) throws IOException, InterruptedException {
		Result result = execCapture(command);
		if (result.exit() != 0) {
			throw new IOException(
					"command %s exited with %d%nstderr:%n%s".formatted(command, result.exit(), result.stderr()));
		}
		return splitLines(result.stdout());
	}

	/**
	 * Runs a command and answers everything it produced. {@link #exec} is this plus "a
	 * non-zero exit is a failure", which is right for the corpus and wrong for a
	 * standalone case whose whole point is to die.
	 */
	private static Result execCapture(List<String> command) throws IOException, InterruptedException {
		ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile());
		Process process = pb.start();
		// Drain stdout and stderr concurrently. A single-threaded readAllBytes() on
		// stdout deadlocks when the child fills the OS pipe buffer on stderr (Linux
		// pipe buffer is 64 KB, macOS's grows further), so the buffered-first-stream
		// approach can hang forever even when the child is running normally.
		ExecutorService drain = Executors.newFixedThreadPool(2);
		Future<String> stdoutFuture = drain.submit(() -> readAll(process.getInputStream()));
		Future<String> stderrFuture = drain.submit(() -> readAll(process.getErrorStream()));
		try {
			if (!process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("command %s timed out after %d seconds".formatted(command, EXEC_TIMEOUT_SECONDS));
			}
			return new Result(getSafely(stdoutFuture), getSafely(stderrFuture), process.exitValue());
		}
		finally {
			drain.shutdownNow();
		}
	}

	private static String readAll(InputStream in) {
		try {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			return "";
		}
	}

	private static String getSafely(Future<String> future) throws IOException {
		try {
			return future.get(30, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted while collecting process output", ex);
		}
		catch (ExecutionException | TimeoutException ex) {
			return "";
		}
	}

	private static Spec loadSpec() throws IOException {
		try (InputStream in = CiSpecE2eTest.class.getResourceAsStream(SPEC_RESOURCE)) {
			if (in == null) {
				throw new IOException("missing test resource: " + SPEC_RESOURCE);
			}
			return new YAMLMapper().readValue(in, Spec.class);
		}
	}

	private static Path writeProgram(Spec spec) throws IOException {
		StringBuilder sb = new StringBuilder();
		for (Case c : spec.cases()) {
			sb.append(c.source());
			if (!c.source().endsWith("\n")) {
				sb.append('\n');
			}
		}
		Path program = workDir.resolve("ci-program.lisp");
		Files.writeString(program, sb.toString());
		return program;
	}

	private static List<String> splitLines(String text) {
		if (text.isEmpty()) {
			return List.of();
		}
		List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
		// A trailing newline yields a final empty element; drop exactly one so
		// "3\n" is one line, not two.
		if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}
		return lines;
	}

	private static List<String> sublist(List<String> lines, int start, int count) {
		int from = Math.min(start, lines.size());
		int to = Math.min(start + count, lines.size());
		return lines.subList(from, to);
	}

	private static boolean onPath(String tool) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(java.io.File.pathSeparator)) {
			if (Files.isExecutable(Path.of(dir).resolve(tool))) {
				return true;
			}
		}
		return false;
	}

}
