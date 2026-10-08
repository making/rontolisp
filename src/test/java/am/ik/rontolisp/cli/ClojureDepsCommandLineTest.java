package am.ik.rontolisp.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.testsupport.HostWasmtime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A {@code deps.edn} project run the oracle's way -- {@code -M:alias}, {@code -X:alias},
 * {@code rontolisp test} -- from its own directory, as a separate process (the project is
 * the working directory's), on the interpreter and compiled to a jar, a Preview 1 module
 * and a component. The expected output and exit codes are the oracle's ({@code clj -M:dev
 * a b}, {@code clj -X:run ...}, {@code clj} 1.12.6, 2026-10-08); a compiled run keeps the
 * command line's arguments and takes its own after them. {@code System/exit} ends the
 * process with its status on every backend.
 */
class ClojureDepsCommandLineTest {

	@TempDir
	static Path dir;

	@BeforeAll
	static void writeProject() throws IOException {
		write("deps.edn", """
				{:paths ["src"]
				 :aliases {:dev {:extra-paths ["dev"] :main-opts ["-m" "my.app" "fromdev"]}
				           :run {:exec-fn my.app/run :exec-args {:a 1 :b {:c 2}}}}}
				""");
		write("src/my/app.clj", """
				(ns my.app (:require [my.helper :as h]))
				(defn -main [& args]
				  (prn :main args *command-line-args*)
				  (System/exit (count args)))
				(defn run [m] (prn (h/tag :run) m *command-line-args*))
				""");
		write("src/my/plain.clj", "(ns my.plain)\n(defn -main [& args] (println :plain))\n");
		write("dev/my/helper.clj", "(ns my.helper)\n(defn tag [k] k)\n");
		write("test/my/app_test.clj", """
				(ns my.app-test (:require [clojure.test :refer [deftest is]] [my.helper :as h]))
				(deftest passes (is (= :x (h/tag :x))))
				""");
		write("test/my/more_test.clj", """
				(ns my.more-test (:require [clojure.test :refer [deftest is]]))
				(deftest fails (is (= 1 2)))
				""");
	}

	@Test
	void mainRunsTheAliasesMainOptsWithTheArgumentsOnEveryBackend() throws Exception {
		assertRun(run("-M:dev", "a", "b"), 3, ":main (\"fromdev\" \"a\" \"b\") (\"fromdev\" \"a\" \"b\")\n");
		for (Leg leg : Leg.values()) {
			assertRun(compiled(leg, "-M:dev", "a", "b"), 4,
					":main (\"fromdev\" \"a\" \"b\" \"rt\") (\"fromdev\" \"a\" \"b\" \"rt\")\n");
		}
	}

	@Test
	void execCallsTheFunctionWithTheMergedMapOnEveryBackend() throws Exception {
		// the :dev alias puts dev/ on the source path for my.helper
		String args = "(\":a\" \"5\" \"[:b :d]\" \"3\"";
		Result interpreted = run("-A:dev", "-X:run", ":a", "5", "[:b :d]", "3");
		assertRun(interpreted, 0, ":run {:a 5, :b {:c 2, :d 3}} " + args + ")\n");
		// -A selected an alias naming :main-opts: the oracle warns in -X mode too
		assertThat(interpreted.stderr())
			.isEqualTo("WARNING: Use of :main-opts with -A is deprecated. Use -M instead." + System.lineSeparator());
		for (Leg leg : Leg.values()) {
			assertRun(compiled(leg, "-A:dev", "-X:run", ":a", "5", "[:b :d]", "3"), 0,
					":run {:a 5, :b {:c 2, :d 3}} " + args + " \"rt\")\n");
		}
	}

	@Test
	void aProjectsTestsRunUnderItsTestAliasAndExitWithTheVerdictOnEveryBackend() throws Exception {
		String report = """

				Testing my.app-test

				Testing my.more-test

				FAIL in (fails) (more_test.clj:2)
				expected: (= 1 2)
				  actual: (not (= 1 2))

				Ran 2 tests containing 2 assertions.
				1 failures, 0 errors.
				""";
		assertRun(run("test", "-A:test:dev"), 1, report);
		for (Leg leg : Leg.values()) {
			assertRun(compiled(leg, "test", "-A:test:dev"), 1, report);
		}
		assertRun(run("test", "-A:test:dev", "test/my/app_test.clj"), 0, """

				Testing my.app-test

				Ran 1 tests containing 1 assertions.
				0 failures, 0 errors.
				""");
	}

	@Test
	void anUndeclaredAliasIsWarnedOfAndAFileBeforeMIsRefused() throws Exception {
		Result warned = run("-M:nope", "-m", "my.plain");
		assertRun(warned, 0, ":plain\n");
		assertThat(warned.stderr())
			.contains("WARNING: Specified aliases are undeclared and are not being used: [:nope]");
		Result refused = run("main.clj", "-M", "-m", "my.app");
		assertThat(refused.exit()).isEqualTo(1);
		assertThat(refused.stderr()).contains("-M ends rontolisp's options");
	}

	private enum Leg {

		JAR("app.jar"), PREVIEW1("app.wasm"), COMPONENT("app-c.wasm");

		private final String output;

		Leg(String output) {
			this.output = output;
		}

	}

	private static void assertRun(Result result, int exit, String stdout) {
		assertThat(result.exit()).as(result.toString()).isEqualTo(exit);
		assertThat(result.stdout()).as(result.toString()).isEqualTo(stdout);
	}

	/**
	 * Compiles the run with {@code -o} ahead of the rest, then runs it with {@code rt}.
	 */
	private static Result compiled(Leg leg, String... args) throws Exception {
		if (leg != Leg.JAR) {
			assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		}
		Path output = dir.resolve(leg.output);
		Files.deleteIfExists(output);
		List<String> command = new ArrayList<>();
		boolean test = args[0].equals("test");
		if (test) {
			command.add("test");
		}
		command.add("-o");
		command.add(output.toString());
		if (leg == Leg.COMPONENT) {
			command.add("--component");
		}
		command.addAll(List.of(args).subList(test ? 1 : 0, args.length));
		Result compile = run(command.toArray(String[]::new));
		assertThat(compile.exit()).as(compile.toString()).isZero();
		if (leg == Leg.JAR) {
			String java = ProcessHandle.current().info().command().orElse("java");
			return exec(List.of(java, "-jar", output.toString(), "rt"));
		}
		HostWasmtime.ExecResult wasm = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-W", "gc=y", "-W",
				"exceptions=y", output.toString(), "rt");
		return new Result(wasm.exitCode(), wasm.stdout(), wasm.stderr());
	}

	/** The CLI as its own process, in the project's directory. */
	private static Result run(String... args) throws Exception {
		String java = ProcessHandle.current().info().command().orElse("java");
		List<String> command = new ArrayList<>(
				List.of(java, "-cp", System.getProperty("java.class.path"), "am.ik.rontolisp.cli.RontoLispCli"));
		command.addAll(List.of(args));
		return exec(command);
	}

	private static Result exec(List<String> command) throws Exception {
		Path errors = Files.createTempFile(dir, "stderr", ".txt");
		Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectError(errors.toFile()).start();
		process.getOutputStream().close();
		String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(300, TimeUnit.SECONDS)).as("%s timed out", command).isTrue();
		return new Result(process.exitValue(), stdout, Files.readString(errors));
	}

	private record Result(int exit, String stdout, String stderr) {
	}

	private static void write(String name, String text) throws IOException {
		Path path = dir.resolve(name);
		Files.createDirectories(path.getParent());
		Files.writeString(path, text);
	}

}
