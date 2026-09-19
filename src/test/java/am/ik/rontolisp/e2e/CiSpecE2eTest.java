package am.ik.rontolisp.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
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
 * resulting WASI 0.3 (Preview 3) component with {@code wasmtime run} (the async canonical
 * ABI and stackful lifts are on by default in wasmtime 46+; only the synchronous
 * stream/future built-ins are still feature-gated). The {@code ci-spec.yaml} cases are
 * deterministic and do no file I/O / random / time / getenv, so the component's output is
 * identical to the Preview 1 WASM backend and is checked against the same
 * {@code expected} lines.
 * <p>
 * Every backend runs the corpus TWICE, once per {@link Accel}: the default kernels and
 * again under {@code --simd}. That is the second axis, and it is a whole second pass
 * rather than a per-case flag or a chosen subset -- see {@link Accel}.
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

	/**
	 * The acceleration axis, crossed with {@link Backend}: every case runs on all four
	 * backends with the default kernels AND with {@code --simd}.
	 * <p>
	 * {@code --simd} is not a faster route to the same code, it is a different DATA
	 * REPRESENTATION -- wasm-GC packs a {@code #f}/{@code #d} array into a
	 * {@code TYPE_VBLOCK} of {@code v128} groups instead of an {@code $f32arr}/
	 * {@code $f64arr}, and every reader and writer of a packed array has to know. Which
	 * primitives touch that representation is not a property anyone can enumerate by eye,
	 * so the axis is the WHOLE corpus rather than the cases someone classified as
	 * relevant: pinning only the obvious ones is what shipped the
	 * {@code widen-float-bits} /{@code narrow-float-bits} trap green on both WASM
	 * backends (`.kb/vec.md`).
	 * <p>
	 * A per-case flag was rejected for cost, not taste: the corpus is concatenated into
	 * ONE program per backend, so a per-case flag means one program per case, and the
	 * measured fixed cost of a program (~750 ms across the four backends) against 479
	 * cases is ~6 minutes of process starts. A second whole pass pays that fixed cost
	 * once. See {@code .kb/vec.md} for the measurement.
	 * <p>
	 * {@code --no-gc} and {@code --parallel} are deliberately NOT axes here;
	 * {@code .kb/vec.md} records why.
	 */
	enum Accel {

		/** The default kernels: no acceleration flag. */
		SCALAR("", List.of(), List.of("java")),

		/**
		 * {@code --simd}. The {@code java} launcher needs
		 * {@code --add-modules jdk.incubator.vector} for a {@code -o Prog.class} output
		 * -- without it the emitted {@code _simdInit} catches the {@code LinkageError},
		 * warns and runs the SCALAR kernels, which produces byte-identical output. That
		 * degrade is why {@link #assertSimdTookEffect} exists: passing the flag is not
		 * evidence that it did anything.
		 */
		SIMD("-simd", List.of("--simd"), List.of("java", "--add-modules", "jdk.incubator.vector"));

		private final String tag;

		private final List<String> flags;

		private final List<String> javaLauncher;

		Accel(String tag, List<String> flags, List<String> javaLauncher) {
			this.tag = tag;
			this.flags = flags;
			this.javaLauncher = javaLauncher;
		}

		/** The compiler flags this leg adds, appended to every rontolisp invocation. */
		List<String> flags() {
			return this.flags;
		}

		/** The {@code java} command (plus module flags) that runs a compiled class. */
		List<String> javaLauncher() {
			return this.javaLauncher;
		}

		/** Suffix keeping this leg's generated files apart from the other leg's. */
		String fileTag() {
			return this.tag;
		}

		/** Suffix for a generated JVM class name, which cannot carry a hyphen. */
		String classTag() {
			return this == SIMD ? "Simd" : "";
		}

		/** How this leg names itself in a test-tree node and in a failure message. */
		String label(Backend backend) {
			return this == SIMD ? backend.name() + " --simd" : backend.name();
		}

	}

	/**
	 * The one line that says {@code --simd} FAILED OPEN: the CLI (interpreter) and the
	 * emitted {@code _simdInit} (a {@code -o Prog.class} output) both print it and then
	 * run the scalar kernels, and the program's own output is identical either way. Its
	 * ABSENCE is the only positive evidence the flag took effect on the JVM family.
	 */
	private static final String SIMD_DEGRADE_MARKER = "jdk.incubator.vector is unavailable";

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
	record Standalone(String name, String source, @Nullable String stdout, @Nullable String stderr,
			@Nullable Boolean fails, @Nullable List<String> refusedOn, @Nullable String refusal) {

		/** Whether the program is expected to end with a non-zero exit. */
		boolean failsExpected() {
			return Boolean.TRUE.equals(this.fails);
		}

		/**
		 * Whether this backend must REFUSE to compile the program rather than run it --
		 * the shape a case takes when its subject exists on some backends only (the
		 * foreign function API on the JVM family, absent from both WASM backends). The
		 * compile is then asserted to fail with {@link #refusal} in its report, and
		 * nothing is run.
		 * @param backend the leg
		 * @return {@code true} when the compile must fail here
		 */
		boolean refusedOn(Backend backend) {
			return this.refusedOn != null && this.refusedOn.contains(backend.name().toLowerCase(Locale.ROOT));
		}
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
		// The corpus's `wild-pathnames` case walks a harness-staged ./wpc-sub/ tree
		// (see CorpusFixtures): the walk pins LISTING over a known tree, so the tree
		// stays staged even though both WASM backends can create directories since
		// .todo/257. Every leg of this driver runs with @TempDir as its working
		// directory.
		am.ik.rontolisp.testsupport.CorpusFixtures.stageWildPathnameTree(workDir);
		// The `uiop-os-host-identity` case parses a .lnk shortcut, which no backend
		// can build at run time -- the same reason the wild-pathname tree is staged.
		am.ik.rontolisp.testsupport.CorpusFixtures.stageLnkFixture(workDir);

		// The SCALAR leg of a backend records its compiled artifact here and the SIMD
		// leg of the SAME backend reads it back, to assert the flag changed what was
		// emitted. The loop below is backend-major and builds every node eagerly, so
		// the write always precedes the read.
		Map<Backend, byte[]> scalarArtifacts = new EnumMap<>(Backend.class);
		List<DynamicNode> backends = new ArrayList<>();
		for (Backend backend : Backend.values()) {
			for (Accel accel : Accel.values()) {
				backends.add(backendNode(backend, accel, bin, program, spec, scalarArtifacts));
			}
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
	 * {@code WasmToplevelChunkingTest}, and it is checked for BOTH WASM builds, on both
	 * {@link Accel} legs (see {@link ModuleTooLargeException}).
	 */
	private static final int MAX_WASM_FUNCTION_BODY_BYTES = 256 * 1024;

	/**
	 * Thrown instead of running wasmtime on a module whose largest function body is over
	 * {@link #MAX_WASM_FUNCTION_BODY_BYTES} -- it turns a machine-killing OOM into an
	 * ordinary test failure that names its own cause. Each WASM leg checks the module it
	 * just compiled, so the {@code --component} build is measured separately from the
	 * Preview 1 one: a component is NOT that module plus a wrapper (an async top level,
	 * which the corpus has, compiles as an entry+resume pair), its bodies are cut
	 * differently, and either can be the larger. Guarding only the core build once let a
	 * 650 KB component body through while the core build's largest was 214 KB, and the
	 * runner was OOM-killed on the component leg.
	 */
	private static final class ModuleTooLargeException extends Exception {

		ModuleTooLargeException(String message) {
			super(message);
		}

	}

	/** Refuses to hand wasmtime a module with an over-large function body. */
	private static void requireRunnableModule(String output, byte[] module) throws ModuleTooLargeException {
		int largest = WasmModuleInspector.largestFunctionBodySize(module);
		if (largest <= MAX_WASM_FUNCTION_BODY_BYTES) {
			return;
		}
		throw new ModuleTooLargeException(
				("refusing to run wasmtime: largest emitted function body of %s is %d bytes, over the %d byte bound. "
						+ "A wasmtime cold compile needs memory superlinear in that number (850 KB of body -> 25.8 GB), "
						+ "so running this module would OOM-kill the CI runner instead of failing. "
						+ "See WasmToplevelChunkingTest.")
					.formatted(output, largest, MAX_WASM_FUNCTION_BODY_BYTES));
	}

	private static DynamicContainer backendNode(Backend backend, Accel accel, Path bin, Path program, Spec spec,
			Map<Backend, byte[]> scalarArtifacts) {
		String leg = accel.label(backend);
		if ((backend == Backend.WASM || backend == Backend.WASM_COMPONENT) && !onPath("wasmtime")) {
			return dynamicContainer(leg, Stream.of(dynamicTest("(skipped)", () -> abort("wasmtime not on PATH"))));
		}
		// The standalone cases are their own programs, so each compiles and runs inside
		// its own lazily-executed test rather than in the one shared run below.
		List<DynamicNode> standalone = spec.standaloneCases()
			.stream()
			.<DynamicNode>map(s -> dynamicTest("standalone: " + s.name(), () -> runStandalone(backend, accel, bin, s)))
			.toList();

		BackendRun run;
		try {
			System.err.println("[CiSpecE2eTest] starting backend " + leg);
			long t0 = System.nanoTime();
			run = runBackend(backend, accel, bin, program);
			System.err.println("[CiSpecE2eTest] finished backend " + leg + " in "
					+ ((System.nanoTime() - t0) / 1_000_000) + " ms");
		}
		catch (ModuleTooLargeException ex) {
			return dynamicContainer(leg,
					Stream.concat(Stream.of(dynamicTest("(module too large to run)", () -> fail(ex.getMessage()))),
							standalone.stream()));
		}
		catch (Exception ex) {
			System.err.println("[CiSpecE2eTest] backend " + leg + " failed: " + ex.getMessage());
			return dynamicContainer(leg,
					Stream.concat(Stream.of(dynamicTest("(execution failed)", () -> fail(ex.getMessage(), ex))),
							standalone.stream()));
		}
		byte[] artifact = run.artifact();
		if (accel == Accel.SCALAR && artifact != null) {
			scalarArtifacts.put(backend, artifact);
		}

		List<DynamicNode> tests = new ArrayList<>();
		if (accel == Accel.SIMD) {
			byte[] scalarArtifact = scalarArtifacts.get(backend);
			tests.add(dynamicTest("(--simd took effect)", () -> assertSimdTookEffect(leg, run, scalarArtifact)));
		}
		List<String> actual = run.stdout();
		List<String> expectedAll = spec.cases().stream().flatMap(c -> c.expectedLines(backend).stream()).toList();
		tests.add(dynamicTest("total-line-count",
				() -> assertThat(actual).as("%s produced a different number of output lines than the spec", leg)
					.hasSize(expectedAll.size())));

		int offset = 0;
		for (Case c : spec.cases()) {
			List<String> expected = c.expectedLines(backend);
			int start = offset;
			offset += expected.size();
			List<String> slice = sublist(actual, start, expected.size());
			tests.add(dynamicTest(c.name(),
					() -> assertThat(slice)
						.as("case '%s' on %s%n--- source ---%n%s--- end source ---", c.name(), leg, c.source())
						.containsExactlyElementsOf(expected)));
		}
		tests.addAll(standalone);
		return dynamicContainer(leg, tests.stream());
	}

	/**
	 * Asserts that {@code --simd} DID something, which no output of the program can show:
	 * the flag is semantically transparent by design, and both JVM-family paths degrade
	 * to the scalar kernels with a warning when {@code jdk.incubator.vector} is off the
	 * module graph. Without this, a {@code --simd} axis asserts only that the flag was
	 * spelled on the command line -- exactly the fail-open it exists to kill.
	 * <p>
	 * Two independent pieces of evidence, per what the backend can offer:
	 * <ul>
	 * <li>the degrade warning is ABSENT from stderr (the interpreter and the JVM, the
	 * only two paths that have a fallback at all);
	 * <li>the emitted artifact DIFFERS from the scalar leg's (the JVM class and both WASM
	 * modules -- a {@code --simd} wasm module carries the {@code v128} types and kernels
	 * a default one must not have).
	 * </ul>
	 */
	private static void assertSimdTookEffect(String leg, BackendRun run, byte @Nullable [] scalarArtifact) {
		assertThat(run.stderr())
			.as("%s degraded to the scalar kernels; the flag was passed but did nothing%n--- stderr ---%n%s", leg,
					run.stderr())
			.doesNotContain(SIMD_DEGRADE_MARKER);
		byte[] artifact = run.artifact();
		if (artifact == null) {
			return;
		}
		assertThat(scalarArtifact).as("%s: no scalar artifact was recorded to compare against", leg).isNotNull();
		assertThat(artifact)
			.as("%s emitted an artifact byte-identical to the scalar one, so --simd changed nothing", leg)
			.isNotEqualTo(scalarArtifact);
	}

	/**
	 * Compiles and runs one {@link Standalone} case on one backend and checks its
	 * standard output, the lines it must put on standard error, and whether it exits
	 * non-zero. Standard error is checked by CONTAINMENT, not equality: a wasm program
	 * that reports and then traps prints wasmtime's own backtrace around our line, and
	 * that text belongs to the host, not to the contract under test.
	 */
	private static void runStandalone(Backend backend, Accel accel, Path bin, Standalone standalone) throws Exception {
		Path source = workDir.resolve(standalone.name() + ".lisp");
		Files.writeString(source, standalone.source());
		String stem = "S" + accel.classTag() + standalone.name().replaceAll("[^A-Za-z0-9]", "");
		String leg = accel.label(backend);
		if (standalone.refusedOn(backend)) {
			assertRefusedCompile(backend, accel, standalone, bin, source, stem);
			return;
		}
		Result result = switch (backend) {
			case INTERPRETER -> execCapture(command(List.of(bin.toString(), source.toString()), accel.flags()));
			case JVM -> {
				execLabeled("compile-jvm-" + standalone.name() + accel.fileTag(),
						command(List.of(bin.toString(), source.toString(), "-o", stem + ".class"), accel.flags()));
				yield execCapture(command(accel.javaLauncher(), List.of(stem)));
			}
			case WASM -> {
				execLabeled("compile-wasm-" + standalone.name() + accel.fileTag(),
						command(List.of(bin.toString(), source.toString(), "-o", stem + ".wasm"), accel.flags()));
				yield execCapture(List.of("wasmtime", "--wasm", "gc", "--wasm", "exceptions=y", "--dir", ".", "--dir",
						"/tmp", stem + ".wasm"));
			}
			case WASM_COMPONENT -> {
				execLabeled("compile-wasm-component-" + standalone.name() + accel.fileTag(), command(
						List.of(bin.toString(), source.toString(), "-o", stem + ".component.wasm", "--component"),
						accel.flags()));
				yield execCapture(List.of("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--dir", ".", "--dir",
						"/tmp", stem + ".component.wasm"));
			}
		};
		String where = "standalone case '%s' on %s%n--- source ---%n%s--- end source ---%n--- stderr ---%n%s"
			.formatted(standalone.name(), leg, standalone.source(), result.stderr());
		if (accel == Accel.SIMD) {
			assertThat(result.stderr()).as("%s: --simd degraded to the scalar kernels", where)
				.doesNotContain(SIMD_DEGRADE_MARKER);
		}
		assertThat(splitLines(result.stdout())).as("%s", where)
			.containsExactlyElementsOf(splitLines(standalone.stdout() == null ? "" : standalone.stdout()));
		for (String line : splitLines(standalone.stderr() == null ? "" : standalone.stderr())) {
			assertThat(splitLines(result.stderr())).as("%s", where).contains(line);
		}
		if (standalone.failsExpected()) {
			assertThat(result.exit()).as("%s: expected a non-zero exit", where).isNotZero();
		}
		else {
			assertThat(result.exit()).as("%s", where).isZero();
		}
	}

	/**
	 * Asserts that compiling a standalone case for this backend FAILS, with the case's
	 * {@code refusal} text in the report. A backend that has no foreign function API
	 * refuses such a program by name at compile time; the refusal is the contract, so it
	 * is asserted rather than skipped.
	 */
	private static void assertRefusedCompile(Backend backend, Accel accel, Standalone standalone, Path bin, Path source,
			String stem) throws Exception {
		List<String> base = switch (backend) {
			case INTERPRETER -> List.of(bin.toString(), source.toString());
			case JVM -> List.of(bin.toString(), source.toString(), "-o", stem + ".class");
			case WASM -> List.of(bin.toString(), source.toString(), "-o", stem + ".wasm");
			case WASM_COMPONENT ->
				List.of(bin.toString(), source.toString(), "-o", stem + ".component.wasm", "--component");
		};
		Result result = execCapture(command(base, accel.flags()));
		String where = "standalone case '%s' on %s: expected a refusal%n--- stderr ---%n%s".formatted(standalone.name(),
				accel.label(backend), result.stderr());
		assertThat(result.exit()).as("%s", where).isNotZero();
		assertThat(result.stderr()).as("%s", where).contains(standalone.refusal() == null ? "" : standalone.refusal());
	}

	/**
	 * The arguments every backend's launcher hands the concatenated program, so the
	 * {@code uiop-image-command-line} case can pin that the four agree about what a
	 * program's own arguments ARE -- not just about the family that reads them. Each
	 * launcher below passes them the way its host spells them: after a {@code --}
	 * separator on the interpreter (where rontolisp's own options end), straight after
	 * the class name on the JVM, and after the module path under wasmtime.
	 */
	private static final List<String> PROGRAM_ARGUMENTS = List.of("alpha", "beta");

	private static List<String> withArguments(List<String> command) {
		List<String> full = new ArrayList<>(command);
		full.addAll(PROGRAM_ARGUMENTS);
		return full;
	}

	/** One command line: a base plus the acceleration flags this leg adds. */
	private static List<String> command(List<String> base, List<String> extra) {
		List<String> full = new ArrayList<>(base);
		full.addAll(extra);
		return full;
	}

	/**
	 * What one backend leg produced: the program's standard output sliced per case, its
	 * standard error (which is where {@code --simd} confesses a degrade), and the
	 * compiled artifact, or {@code null} for the interpreter, which emits none.
	 */
	private record BackendRun(List<String> stdout, String stderr, byte @Nullable [] artifact) {
	}

	private static BackendRun runBackend(Backend backend, Accel accel, Path bin, Path program) throws Exception {
		String tag = accel.fileTag();
		List<String> flags = accel.flags();
		return switch (backend) {
			case INTERPRETER -> {
				Result result = execLabeledCapture("interpret" + tag, withArguments(
						command(List.of(bin.toString(), program.toString()), command(flags, List.of("--")))));
				yield new BackendRun(splitLines(result.stdout()), result.stderr(), null);
			}
			case JVM -> {
				String className = "Test" + accel.classTag();
				execLabeledCapture("compile-jvm" + tag,
						command(List.of(bin.toString(), program.toString(), "-o", className + ".class"), flags));
				Result result = execLabeledCapture("run-jvm" + tag,
						withArguments(command(accel.javaLauncher(), List.of(className))));
				yield new BackendRun(splitLines(result.stdout()), result.stderr(),
						Files.readAllBytes(workDir.resolve(className + ".class")));
			}
			case WASM -> {
				String module = "test" + tag + ".wasm";
				execLabeledCapture("compile-wasm" + tag,
						command(List.of(bin.toString(), program.toString(), "-o", module), flags));
				byte[] bytes = Files.readAllBytes(workDir.resolve(module));
				requireRunnableModule(module, bytes);
				// --dir . preopens the work dir so the file-stream cases can open files,
				// and --dir /tmp preopens a directory whose NAME is absolute -- the
				// runtime-absolute-path case needs a preopen that can COVER an absolute
				// path, and "." never does (.kb/read-load-streams.md);
				// exceptions=y because the concatenated program contains catching cases
				// (handler-case &c), which put the whole module in EH mode (harmless
				// otherwise).
				Result result = execLabeledCapture("run-wasm" + tag, withArguments(List.of("wasmtime", "--wasm", "gc",
						"--wasm", "exceptions=y", "--dir", ".", "--dir", "/tmp", module)));
				yield new BackendRun(splitLines(result.stdout()), result.stderr(), bytes);
			}
			case WASM_COMPONENT -> {
				String module = "test" + tag + ".component.wasm";
				execLabeledCapture("compile-wasm-component" + tag,
						command(List.of(bin.toString(), program.toString(), "-o", module, "--component"), flags));
				byte[] bytes = Files.readAllBytes(workDir.resolve(module));
				requireRunnableModule(module, bytes);
				Result result = execLabeledCapture("run-wasm-component" + tag, withArguments(List.of("wasmtime", "run",
						"-W", "gc=y", "-W", "exceptions=y", "--dir", ".", "--dir", "/tmp", module)));
				yield new BackendRun(splitLines(result.stdout()), result.stderr(), bytes);
			}
		};
	}

	private static List<String> execLabeled(String label, List<String> command)
			throws IOException, InterruptedException {
		return splitLines(execLabeledCapture(label, command).stdout());
	}

	/**
	 * {@link #execLabeled} keeping standard error as well, since that is where
	 * {@code --simd} reports having degraded to the scalar kernels. A non-zero exit is
	 * still a failure here -- the corpus program is expected to run to completion.
	 */
	private static Result execLabeledCapture(String label, List<String> command)
			throws IOException, InterruptedException {
		System.err.println("[CiSpecE2eTest]   > " + label + " " + command);
		long t0 = System.nanoTime();
		try {
			Result result = execCapture(command);
			if (result.exit() != 0) {
				throw new IOException(
						"command %s exited with %d%nstderr:%n%s".formatted(command, result.exit(), result.stderr()));
			}
			System.err.println("[CiSpecE2eTest]   < " + label + " ok in " + ((System.nanoTime() - t0) / 1_000_000)
					+ " ms (" + splitLines(result.stdout()).size() + " lines)");
			return result;
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
			// Decoded to a String FIRST and read through YamlResources.safeReader: the
			// corpus carries non-ASCII source (the code-point cases), and
			// snakeyaml-engine
			// 3.0.1 crashes when a high surrogate lands exactly on its internal buffer
			// boundary -- an unrelated ci-spec edit can arm it. See YamlResources.
			return new YAMLMapper().readValue(am.ik.rontolisp.testsupport.YamlResources
				.safeReader(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)), Spec.class);
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
