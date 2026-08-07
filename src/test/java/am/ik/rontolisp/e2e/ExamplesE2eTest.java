package am.ik.rontolisp.e2e;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Smoke-tests every non-GUI example listed in {@code examples/examples.yaml}, turning
 * each (example x declared-backend) pair into one JUnit dynamic test.
 * <p>
 * Two kinds of check, chosen per backend token in the manifest:
 * <ul>
 * <li><b>RUN</b> ({@code interpreter}/{@code jvm}/{@code wasm}) -- the program runs to
 * completion; we assert it exits 0 and (optionally) that its stdout matches the declared
 * {@code expect}.</li>
 * <li><b>COMPILE</b> ({@code jvm-compile}/{@code wasm-component}/{@code no-gc}/
 * {@code no-gc-simd}) -- for the blocking servers and the host-invoked {@code --no-gc}
 * module, which never return on their own, we only build them and assert the compile
 * succeeds. This still catches broken {@code (load ...)} paths, missing symbols and
 * package errors.</li>
 * </ul>
 * Per-example manifest fields (all optional except {@code path}/{@code backends}):
 * <ul>
 * <li>{@code args} -- command-line arguments appended when the program is run.</li>
 * <li>{@code stdin} / {@code stdinFile} -- text fed to the program's standard input
 * (inline, or from a file resolved under {@code examples/}).</li>
 * <li>{@code expect} -- how to check RUN output; exactly one of:
 * <ul>
 * <li>{@code equals} -- stdout must match this text exactly (hard-coded);</li>
 * <li>{@code file} -- stdout must match the contents of this file under {@code examples/}
 * (externalised expected value, e.g. {@code .expected/nqueens.txt});</li>
 * <li>{@code contains} -- every listed substring must appear (partial match, for output
 * that only partly stabilises, e.g. hash-table iteration order);</li>
 * <li>{@code skip: true} -- do not check the output at all (for random / non-repeatable
 * results); the exit status is still asserted.</li>
 * </ul>
 * When {@code expect} is omitted the baseline check is "exit 0 and non-empty
 * output".</li>
 * <li>{@code systemPath} -- directory added as {@code --system-path} (ASDF
 * registry).</li>
 * <li>{@code workDir} -- sub-directory under {@code examples/} the process runs from
 * (default: none, i.e. the throwaway workdir itself). Set it when the script's
 * CWD-relative reads are written against a book root (e.g.
 * {@code deep-learning-from-scratch/}); the leg's CWD becomes
 * {@code work/<workDir>/}.</li>
 * <li>{@code workFiles} -- files to stage beside the program before it runs (list of
 * paths, relative to {@code workDir} when set, otherwise relative to {@code examples/}).
 * Each is copied 1:1 into the workspace, so the mirrored slice looks like the fragment of
 * {@code examples/} the script was written against. A missing file aborts the leg as a
 * skipped assumption (not a failure), so datasets that need
 * {@code deep-learning-from-scratch/download-mnist.sh} silently gate on file
 * presence.</li>
 * </ul>
 * Exact matches ({@code equals}/{@code file}) are compared line-by-line, tolerant of a
 * trailing newline, and are asserted against every RUN backend -- so a per-backend
 * divergence is a real failure, not silently accepted.
 * <p>
 * Each example runs in a throwaway working directory, because a few of them write scratch
 * files (poem.txt, numbers.txt, ...) next to themselves.
 * <p>
 * The driver is the native binary when {@code -Drontolisp.binary=<path>} is set;
 * otherwise it is the built {@code target/*-exec.jar} ({@code java -jar}), but ONLY when
 * {@code -Drontolisp.examples=true} opts in -- so a plain {@code mvn test} skips this
 * heavy suite even when a jar happens to sit in {@code target/}. The {@code wasm} run
 * backend is additionally skipped when {@code wasmtime} is not on the {@code PATH}; the
 * compile backends need no runtime.
 * <p>
 * Run it with:
 *
 * <pre>{@code
 * ./mvnw clean package -DskipTests            # build the exec jar once
 * ./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false -Drontolisp.examples=true test
 * # ...or against the native binary (no -Drontolisp.examples needed):
 * ./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false \
 *        -Drontolisp.binary="$PWD/target/rontolisp" test
 * }</pre>
 *
 * The whole suite takes minutes. While iterating on one example, narrow it with
 * {@code -Drontolisp.examples.only=<substrings>} -- a comma-separated list matched
 * against the manifest path, so {@code =cloudflare} runs one directory and
 * {@code =console/,ml/} runs two. A pattern that matches nothing produces no tests at all
 * (a skipped assumption naming the pattern), never the whole suite -- so a typo is
 * visible as "Tests run: 0" rather than as an unexpectedly long run.
 */
class ExamplesE2eTest {

	private static final Path PROJECT_DIR = Path.of("").toAbsolutePath();

	private static final Path EXAMPLES_DIR = PROJECT_DIR.resolve("examples");

	private static final Path MANIFEST = EXAMPLES_DIR.resolve("examples.yaml");

	/** Per-process wall-clock cap; the slowest interpreter examples take ~20s. */
	private static final long TIMEOUT_SECONDS = 240;

	enum Backend {

		// RUN backends run the program; COMPILE backends only build it (see verify()).
		INTERPRETER, JVM, WASM, JVM_COMPILE, WASM_COMPONENT, NO_GC, NO_GC_SIMD;

		/**
		 * The manifest spelling: lower-case with hyphens (e.g. {@code wasm-component}).
		 */
		static Backend fromToken(String token) {
			String normalized = token.trim().toUpperCase(Locale.ROOT).replace('-', '_');
			return Backend.valueOf(normalized);
		}

		/**
		 * Whether this backend runs the program (rather than only compiling it) -- gates
		 * whether {@code workFiles} must actually exist ahead of the leg.
		 */
		boolean runsProgram() {
			return this == INTERPRETER || this == JVM || this == WASM;
		}

	}

	record Expect(@Nullable String equals, @Nullable String file, @Nullable List<String> contains,
			@Nullable Boolean skip) {
	}

	record Example(String path, List<String> backends, @Nullable List<String> args, @Nullable String stdin,
			@Nullable String stdinFile, @Nullable Expect expect, @Nullable String systemPath, @Nullable String workDir,
			@Nullable List<String> workFiles, @Nullable String note) {

		List<String> argsOrEmpty() {
			return this.args == null ? List.of() : this.args;
		}
	}

	record Manifest(List<Example> examples) {
	}

	@TestFactory
	Stream<DynamicNode> examples() throws Exception {
		List<String> driver = resolveDriver();
		assumeTrue(driver != null,
				() -> "examples E2E is opt-in: pass -Drontolisp.examples=true (uses target/*-exec.jar, so run "
						+ "./mvnw clean package -DskipTests first) or -Drontolisp.binary=<native binary>. Looked in "
						+ PROJECT_DIR.resolve("target"));
		assumeTrue(Files.isRegularFile(MANIFEST), () -> "manifest not found: " + MANIFEST);

		Manifest manifest = loadManifest();
		String only = System.getProperty("rontolisp.examples.only");
		List<Example> selected = manifest.examples().stream().filter(example -> matchesOnly(example, only)).toList();
		assumeTrue(!selected.isEmpty(),
				() -> "-Drontolisp.examples.only=" + only + " matched no example in " + MANIFEST);

		List<DynamicNode> nodes = new ArrayList<>();
		for (Example example : selected) {
			nodes.add(exampleNode(example, driver));
		}
		return nodes.stream();
	}

	/**
	 * Whether {@code example} survives the {@code -Drontolisp.examples.only=...} filter:
	 * a comma-separated list of substrings matched against the manifest path, so
	 * {@code -Drontolisp.examples.only=cloudflare} runs one directory and
	 * {@code =console/,ml/} runs two. Unset (or blank) selects everything.
	 */
	private static boolean matchesOnly(Example example, @Nullable String only) {
		if (only == null || only.isBlank()) {
			return true;
		}
		for (String pattern : only.split(",")) {
			String trimmed = pattern.trim();
			if (!trimmed.isEmpty() && example.path().contains(trimmed)) {
				return true;
			}
		}
		return false;
	}

	private static DynamicContainer exampleNode(Example example, List<String> driver) {
		Path source = EXAMPLES_DIR.resolve(example.path());
		List<DynamicNode> tests = new ArrayList<>();
		for (String token : example.backends()) {
			Backend backend = Backend.fromToken(token);
			tests.add(dynamicTest(token, () -> {
				if (backend == Backend.WASM && !onPath("wasmtime")) {
					abort("wasmtime not on PATH");
				}
				assertThat(Files.isRegularFile(source)).as("example source is missing: %s", source).isTrue();
				Path work = Files.createTempDirectory("rontolisp-example-");
				try {
					verify(backend, example, source, driver, work);
				}
				finally {
					deleteRecursively(work);
				}
			}));
		}
		return dynamicContainer(example.path(), tests.stream());
	}

	private static void verify(Backend backend, Example example, Path source, List<String> driver, Path work)
			throws Exception {
		Path runDir = stageWorkspace(example, work, backend);
		String src = source.toString();
		List<String> args = example.argsOrEmpty();
		byte @Nullable [] stdin = resolveStdin(example);
		List<String> flags = systemPathFlags(example);
		switch (backend) {
			case INTERPRETER -> {
				Result run = exec(runDir, concat(driver, concat(List.of(src), args)), stdin);
				assertRan(run, example, "interpreter");
			}
			case JVM -> {
				Result compile = exec(runDir, concat(driver, concat(List.of(src, "-o", "Prog.class"), flags)), null);
				assertCompiled(compile, example, "jvm (compile)");
				Result run = exec(runDir, concat(List.of("java", "-cp", jvmClasspath(runDir), "Prog"), args), stdin);
				assertRan(run, example, "jvm (run)");
			}
			case WASM -> {
				Result compile = exec(runDir,
						concat(driver, concat(List.of(src, "-o", "prog.wasm", "--optimize"), flags)), null);
				assertCompiled(compile, example, "wasm (compile)");
				Result run = exec(runDir,
						concat(List.of("wasmtime", "run", "-W", "gc", "-W", "exceptions=y", "--dir", ".", "prog.wasm"),
								args),
						stdin);
				assertRan(run, example, "wasm (run)");
			}
			case JVM_COMPILE -> {
				Result compile = exec(runDir, concat(driver, concat(List.of(src, "-o", "Prog.class"), flags)), null);
				assertCompiled(compile, example, "jvm-compile");
			}
			case WASM_COMPONENT -> {
				Result compile = exec(runDir,
						concat(driver, concat(List.of(src, "-o", "prog.wasm", "--component", "--optimize"), flags)),
						null);
				assertCompiled(compile, example, "wasm-component");
			}
			case NO_GC -> {
				Result compile = exec(runDir,
						concat(driver, concat(List.of(src, "-o", "prog.wasm", "--no-gc", "--optimize"), flags)), null);
				assertCompiled(compile, example, "no-gc");
			}
			case NO_GC_SIMD -> {
				Result compile = exec(runDir,
						concat(driver,
								concat(List.of(src, "-o", "prog.wasm", "--no-gc", "--simd", "--optimize"), flags)),
						null);
				assertCompiled(compile, example, "no-gc-simd");
			}
		}
	}

	/**
	 * Prepare the working directory the leg will run in and stage any files the example
	 * needs beside it.
	 * <p>
	 * With {@code workDir} set, the process CWD becomes {@code work/<workDir>} so a
	 * script written to run from {@code examples/<workDir>/} keeps its CWD-relative reads
	 * (like {@code "ch07/params.bin"}) resolving. {@code workFiles} entries are relative
	 * to {@code workDir} (or to {@code examples/} when it is unset) and are copied 1:1
	 * into the workspace, so the mirrored slice looks like the fragment of
	 * {@code examples/} the script was written against. If a required file is missing
	 * (the MNIST idx dumps are gitignored, fetched by
	 * {@code deep-learning-from-scratch/download-mnist.sh}) the leg is skipped rather
	 * than failed -- same shape as the wasmtime-on-PATH check.
	 */
	private static Path stageWorkspace(Example example, Path work, Backend backend) throws IOException {
		Path runDir = work;
		if (example.workDir() != null && !example.workDir().isBlank()) {
			runDir = work.resolve(example.workDir());
			Files.createDirectories(runDir);
		}
		if (example.workFiles() == null || example.workFiles().isEmpty()) {
			return runDir;
		}
		// workFiles are runtime inputs -- a compile-only leg does not read them, so it
		// stays green whether or not the file is present. RUN legs skip themselves when a
		// required file is absent (same shape as the wasmtime-on-PATH check), so CI
		// without the gitignored idx dumps still exercises every leg it can.
		if (!backend.runsProgram()) {
			return runDir;
		}
		Path anchor = (example.workDir() == null || example.workDir().isBlank()) ? EXAMPLES_DIR
				: EXAMPLES_DIR.resolve(example.workDir());
		for (String rel : example.workFiles()) {
			Path src = anchor.resolve(rel);
			if (!Files.isRegularFile(src)) {
				abort("required work file is missing: " + rel + " (looked in " + src
						+ ") -- run examples/deep-learning-from-scratch/download-mnist.sh if it is a dataset/*-ubyte file");
			}
			Path dst = runDir.resolve(rel);
			Path parent = dst.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.copy(src, dst);
		}
		return runDir;
	}

	private static void assertCompiled(Result result, Example example, String label) {
		assertThat(result.exit())
			.as("%s: %s failed to compile (exit %d)%n%s", example.path(), label, result.exit(), result.diagnostics())
			.isZero();
	}

	private static void assertRan(Result result, Example example, String label) throws IOException {
		assertThat(result.exit()).as("%s: %s exited %d%n%s", example.path(), label, result.exit(), result.diagnostics())
			.isZero();
		checkOutput(result.stdout(), example, label);
	}

	private static void checkOutput(String stdout, Example example, String label) throws IOException {
		Expect expect = example.expect();
		if (expect == null) {
			assertThat(stdout.isBlank()).as("%s: %s produced no output", example.path(), label).isFalse();
			return;
		}
		if (Boolean.TRUE.equals(expect.skip())) {
			return;
		}
		if (expect.contains() != null && !expect.contains().isEmpty()) {
			for (String needle : expect.contains()) {
				assertThat(stdout).as("%s: %s output is missing %s", example.path(), label, needle).contains(needle);
			}
			return;
		}
		String expected;
		if (expect.file() != null) {
			expected = Files.readString(EXAMPLES_DIR.resolve(expect.file()));
		}
		else if (expect.equals() != null) {
			expected = expect.equals();
		}
		else {
			// Misconfigured expect (no mode set): fall back to the non-empty baseline.
			assertThat(stdout.isBlank()).as("%s: %s produced no output", example.path(), label).isFalse();
			return;
		}
		assertThat(lines(stdout)).as("%s: %s output did not match the expected value", example.path(), label)
			.isEqualTo(lines(expected));
	}

	private static byte @Nullable [] resolveStdin(Example example) throws IOException {
		if (example.stdin() != null) {
			return example.stdin().getBytes(StandardCharsets.UTF_8);
		}
		if (example.stdinFile() != null) {
			return Files.readAllBytes(EXAMPLES_DIR.resolve(example.stdinFile()));
		}
		return null;
	}

	private record Result(int exit, String stdout, String stderr, boolean timedOut) {
		String diagnostics() {
			String out = this.timedOut ? "TIMED OUT after " + TIMEOUT_SECONDS + "s\n" : "";
			return out + "--- stdout ---\n" + this.stdout + "\n--- stderr ---\n" + this.stderr;
		}
	}

	private static Result exec(Path work, List<String> command, byte @Nullable [] stdin)
			throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).directory(work.toFile()).start();
		try (OutputStream in = process.getOutputStream()) {
			if (stdin != null) {
				in.write(stdin);
			}
		}
		byte[] out = process.getInputStream().readAllBytes();
		byte[] err = process.getErrorStream().readAllBytes();
		boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
			process.waitFor();
			return new Result(-1, str(out), str(err), true);
		}
		return new Result(process.exitValue(), str(out), str(err), false);
	}

	private static @Nullable List<String> resolveDriver() {
		String binary = System.getProperty("rontolisp.binary");
		if (binary != null) {
			Path bin = Path.of(binary).toAbsolutePath();
			return Files.isExecutable(bin) ? List.of(bin.toString()) : null;
		}
		// Opt-in against the exec jar: only when -Drontolisp.examples=true is passed, so
		// a
		// plain `mvn test` (which may have a stale exec jar in target/) does not trigger
		// this heavy, subprocess-spawning suite.
		if (!Boolean.getBoolean("rontolisp.examples")) {
			return null;
		}
		String jarProp = System.getProperty("rontolisp.jar");
		Path jar = jarProp != null ? Path.of(jarProp) : newestExecJar();
		return (jar != null && Files.isRegularFile(jar)) ? List.of("java", "-jar", jar.toAbsolutePath().toString())
				: null;
	}

	/**
	 * The classpath a compiled {@code Prog.class} runs on: the workspace, plus the exec
	 * jar when there is one. Most compiled programs are self-contained and only need the
	 * workspace, but one that reaches a runtime support class -- an HTTP server (which
	 * quickloading clack pulls in whether or not the program starts a server), a socket,
	 * a Gray stream -- needs the jar, which is exactly what those examples' own READMEs
	 * tell a reader to put on the classpath.
	 * @param runDir the workspace the leg runs in
	 * @return the {@code -cp} value
	 */
	private static String jvmClasspath(Path runDir) {
		Path jar = newestExecJar();
		return jar == null ? runDir.toString() : runDir + File.pathSeparator + jar.toAbsolutePath();
	}

	private static @Nullable Path newestExecJar() {
		Path target = PROJECT_DIR.resolve("target");
		if (!Files.isDirectory(target)) {
			return null;
		}
		try (Stream<Path> jars = Files.list(target)) {
			return jars.filter(p -> p.getFileName().toString().endsWith("-exec.jar"))
				.max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()))
				.orElse(null);
		}
		catch (IOException ex) {
			return null;
		}
	}

	private static List<String> systemPathFlags(Example example) {
		if (example.systemPath() == null || example.systemPath().isBlank()) {
			return List.of();
		}
		// Resolve against the project dir so it works from the throwaway work dir.
		Path abs = PROJECT_DIR.resolve(example.systemPath()).toAbsolutePath();
		return List.of("--system-path", abs.toString());
	}

	private static Manifest loadManifest() throws IOException {
		return new YAMLMapper().readValue(Files.readString(MANIFEST), Manifest.class);
	}

	private static List<String> concat(List<String> a, List<String> b) {
		if (b.isEmpty()) {
			return a;
		}
		List<String> out = new ArrayList<>(a.size() + b.size());
		out.addAll(a);
		out.addAll(b);
		return out;
	}

	/** Split into lines, dropping a single trailing empty line so "a\n" == "a". */
	private static List<String> lines(String text) {
		if (text.isEmpty()) {
			return List.of();
		}
		List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
		if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}
		return lines;
	}

	private static String str(byte[] bytes) {
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static boolean onPath(String tool) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(File.pathSeparator)) {
			if (Files.isExecutable(Path.of(dir).resolve(tool))) {
				return true;
			}
		}
		return false;
	}

	private static void deleteRecursively(Path root) {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				}
				catch (IOException ignored) {
					// best effort cleanup
				}
			});
		}
		catch (IOException ignored) {
			// best effort cleanup
		}
	}

}
