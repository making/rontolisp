package am.ik.rontolisp.e2e;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.testsupport.WasmtimeSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code rontolisp test} exit contract, on all four backends: a red rove suite exits
 * 1, a green one exits 0, and a target that runs no test at all exits 1 rather than
 * reporting a vacuous pass. Everything else about rove is pinned by {@link RoveE2eTest},
 * which compares the REPORT -- the status is what no line of stdout can carry, so it
 * needs a driver of its own.
 *
 * <p>
 * The target is the shape rove's README FAQ teaches and the one this command exists for:
 * a single file that ends in {@code (run-suite *package*)}, with no {@code uiop:quit} of
 * its own. rove records {@code *last-suite-report*} in {@code call-with-suite}, which
 * that entry point never reaches, so the runner's {@code invoke-reporter :after} hook is
 * what the verdict is read through -- and the "exactly once" assertion below is what
 * proves the runner did not simply re-run the suite to obtain one.
 *
 * <p>
 * Every leg is a SUBPROCESS, unlike the in-process {@link AsdfLibraryE2eSupport} legs:
 * the exit status is the whole subject, and a compiled program's {@code uiop:quit} is a
 * real {@code System.exit} / {@code proc_exit} that would take the test JVM with it. The
 * CLI runs from the test classpath
 * ({@code java -cp ... am.ik.rontolisp.cli.RontoLispCli}), so no packaged jar is needed
 * and {@code ./mvnw test} covers this by default; the two WASM legs additionally need
 * Docker (the shared {@code wasmtime} container) and skip without it.
 */
@Execution(ExecutionMode.CONCURRENT)
class RoveTestCommandE2eTest {

	private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

	private static final String CLASSPATH = System.getProperty("java.class.path");

	/** rove + its two real-source dependencies, vendored beside cl-ppcre. */
	private static final String SYSTEM_PATH = String.join(File.pathSeparator,
			Path.of("src", "test", "resources", "rove").toAbsolutePath().toString(),
			Path.of("src", "test", "resources", "dissect").toAbsolutePath().toString(),
			Path.of("src", "test", "resources", "cl-ppcre").toAbsolutePath().toString());

	/** The two-shape demo project {@link RoveE2eTest} drives, for the system targets. */
	private static final String DEMO_SYSTEM_PATH = Path.of("src", "test", "resources", "rove-demo").toAbsolutePath()
			+ File.pathSeparator + SYSTEM_PATH;

	private static final boolean DOCKER_AVAILABLE = WasmtimeSupport.DOCKER_AVAILABLE;

	private static final long TIMEOUT_MINUTES = 10;

	@TempDir
	Path workDir;

	@Test
	void theInterpreterExitsWithTheVerdict() throws Exception {
		Result red = test(fixture("red.lisp", 2));
		assertThat(red.exit()).as("%s", red).isEqualTo(1);
		// Exactly one report: the file ran its own suite and the runner read the verdict
		// off it rather than running the tests a second time.
		assertThat(red.out()).containsOnlyOnce("Summary:").contains("1 test failed.");

		Result green = test(fixture("green.lisp", 1));
		assertThat(green.exit()).as("%s", green).isZero();
		assertThat(green.out()).containsOnlyOnce("Summary:").contains("All 1 test passed.");
	}

	@Test
	void theReporterOptionReachesTheSuiteTheFileRunsItself() throws Exception {
		// rove:run reads *default-reporter*, but run-suite's own method hard-codes :spec,
		// so without the runner's replacement -r would silently do nothing for exactly
		// the shape this command is for.
		Result dot = test(fixture("dot.lisp", 2), "-r", "dot");
		assertThat(dot.exit()).as("%s", dot).isEqualTo(1);
		assertThat(dot.out()).doesNotContain(";; testing").contains("1 of 1 test failed");

		Result none = test(fixture("none.lisp", 2), "--reporter", "none");
		assertThat(none.exit()).as("%s", none).isEqualTo(1);
		assertThat(none.out()).isEmpty();
	}

	@Test
	void aFileThatOnlyDefinesTestsHasItsOwnSuiteRun() throws Exception {
		// No run-suite call of its own: the runner runs the suite of the package the
		// file declares, which is what makes a bare file of deftests a usable target.
		Path file = this.workDir.resolve("defines-only.lisp");
		Files.writeString(file, """
				(asdf:load-system :rove)
				(use-package :rove)

				(deftest defines-only
				  (ok (= 1 2)))
				""");
		Result result = test(file);
		assertThat(result.exit()).as("%s", result).isEqualTo(1);
		assertThat(result.out()).containsOnlyOnce("Summary:").contains("1 test failed.");
	}

	@Test
	void aSystemTargetIsTestedAndAnAsdNamesItsOwnSystem() throws Exception {
		// asdf:test-system, then rove:run for a system declaring no :perform (test-op).
		Result system = target("my-plain/tests", DEMO_SYSTEM_PATH);
		assertThat(system.exit()).as("%s", system).isZero();
		assertThat(system.out()).contains("All 1 test passed.");

		// A .asd is the system it is NAMED after (rove.ros's rule), which here holds the
		// code under test and no suite at all -- so it is the no-test failure, not a
		// vacuous pass.
		Result asd = target(
				Path.of("src", "test", "resources", "rove-demo", "my-plain.asd").toAbsolutePath().toString(),
				SYSTEM_PATH);
		assertThat(asd.exit()).as("%s", asd).isEqualTo(1);
		assertThat(asd.err()).contains("no tests were run");
	}

	@Test
	void aTargetThatRunsNoTestExitsOne() throws Exception {
		// Not a vacuous pass: a suite that stopped registering its tests is the silence
		// this command exists to end, so it is a failure with a message of its own.
		Path empty = this.workDir.resolve("empty.lisp");
		Files.writeString(empty, "(asdf:load-system :rove)\n(print \"no tests here\")\n");
		Result result = test(empty);
		assertThat(result.exit()).as("%s", result).isEqualTo(1);
		assertThat(result.err()).contains("no tests were run");
	}

	@Test
	void theCompiledClassCarriesTheSameContract() throws Exception {
		assertThat(runClass(compile("red.lisp", 2, "Red.class"), "Red")).isEqualTo(1);
		assertThat(runClass(compile("green.lisp", 1, "Green.class"), "Green")).isZero();
	}

	@Test
	void thePreview1ModuleCarriesTheSameContract() throws Exception {
		assumeTrue(DOCKER_AVAILABLE, "Docker is not available");
		assertThat(runWasm(compile("red.lisp", 2, "red.wasm"), false)).isEqualTo(1);
		assertThat(runWasm(compile("green.lisp", 1, "green.wasm"), false)).isZero();
	}

	@Test
	void theComponentCarriesTheSameContract() throws Exception {
		assumeTrue(DOCKER_AVAILABLE, "Docker is not available");
		assertThat(runWasm(compile("red-c.lisp", 2, "red-c.wasm", "--component"), true)).isEqualTo(1);
		assertThat(runWasm(compile("green-c.lisp", 1, "green-c.wasm", "--component"), true)).isZero();
	}

	/**
	 * A single-file rove suite in the README FAQ shape: it loads rove, declares its own
	 * package, defines one test and runs the suite -- and says nothing about the exit
	 * code, which is the runner's business.
	 */
	private Path fixture(String name, int expected) throws IOException {
		Path file = this.workDir.resolve(name);
		Files.writeString(file, """
				(asdf:load-system :rove)

				(defpackage #:rove-cli-demo
				  (:use #:cl
				        #:rove))
				(in-package #:rove-cli-demo)

				(deftest cli-demo
				  (ok (= 1 %d)))

				(run-suite *package*)
				""".formatted(expected));
		return file;
	}

	/** Compiles a fixture through {@code rontolisp test -o}, answering the artifact. */
	private Path compile(String name, int expected, String output, String... flags) throws Exception {
		List<String> args = new ArrayList<>(List.of("-o", output));
		args.addAll(List.of(flags));
		Result result = test(fixture(name, expected), args.toArray(String[]::new));
		assertThat(result.exit()).as("%s", result).isZero();
		return this.workDir.resolve(output);
	}

	/** {@code rontolisp test <target> --system-path ... [flags]}, from the work dir. */
	private Result test(Path target, String... flags) throws Exception {
		return target(target.getFileName().toString(), SYSTEM_PATH, flags);
	}

	/**
	 * The same, for a target that is not a file in the work dir (a system designator).
	 */
	private Result target(String target, String systemPath, String... flags) throws Exception {
		List<String> command = new ArrayList<>(List.of(JAVA, "-cp", CLASSPATH, "am.ik.rontolisp.cli.RontoLispCli",
				"test", target, "--system-path", systemPath));
		command.addAll(List.of(flags));
		return run(command);
	}

	private int runClass(Path artifact, String name) throws Exception {
		Result result = run(List.of(JAVA, "-cp", this.workDir.toString(), name));
		assertThat(artifact).exists();
		assertThat(result.out()).as("%s", result).containsOnlyOnce("Summary:");
		return result.exit();
	}

	private int runWasm(Path artifact, boolean component) throws Exception {
		GenericContainer<?> wasmtime = WasmtimeSupport.container();
		String path = "/tmp/" + this.workDir.getFileName() + "-" + artifact.getFileName();
		wasmtime.copyFileToContainer(Transferable.of(Files.readAllBytes(artifact)), path);
		// gc for the value representation, exceptions because rove records a failing
		// test through handler-bind, which puts the module in EH mode.
		String flags = component ? "run -W gc=y -W exceptions=y" : "--wasm gc --wasm exceptions=y";
		ExecResult result = wasmtime.execInContainer("bash", "-c", "wasmtime " + flags + " " + path);
		assertThat(result.getStdout()).as("stderr: %s", result.getStderr()).containsOnlyOnce("Summary:");
		return result.getExitCode();
	}

	/** One finished subprocess: its exit code and its two streams, kept apart. */
	private record Result(List<String> command, int exit, String out, String err) {
		@Override
		public String toString() {
			return "command: %s%nstdout: %s%nstderr: %s".formatted(String.join(" ", this.command), this.out, this.err);
		}
	}

	private Result run(List<String> command) throws Exception {
		Path errFile = Files.createTempFile(this.workDir, "stderr", ".log");
		Process process = new ProcessBuilder(command).directory(this.workDir.toFile())
			.redirectError(errFile.toFile())
			.start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
			process.destroyForcibly();
			throw new AssertionError("timed out: " + String.join(" ", command));
		}
		return new Result(command, process.exitValue(), out, Files.readString(errFile));
	}

}
