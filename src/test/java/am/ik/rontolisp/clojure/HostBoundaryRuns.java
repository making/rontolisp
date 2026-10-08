package am.ik.rontolisp.clojure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.ThreadStdio;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs a Clojure program the ways the host-boundary tests need: through the CLI in
 * process (the interpreter, or a compile), as a compiled JVM class, and a compiled module
 * under {@code node} -- every path a temporary directory the test owns.
 */
final class HostBoundaryRuns {

	private HostBoundaryRuns() {
	}

	/**
	 * Runs the CLI in process.
	 * @param args the command line
	 * @return what it printed
	 */
	static String cli(String... args) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-host-boundary", () -> {
			new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, StandardCharsets.UTF_8))
				.run(args);
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	/**
	 * Compiles the program to a class in the directory and runs its {@code main}.
	 * @param program the program file
	 * @param dir an empty directory for the class and the runtime classes beside it
	 * @param name the class name
	 * @return what it printed
	 */
	static String jvm(Path program, Path dir, String name) throws Exception {
		cli(program.toString(), "-o", dir.resolve(name + ".class").toString());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (var loader = new java.net.URLClassLoader(new java.net.URL[] { dir.toUri().toURL() },
				ClassLoader.getSystemClassLoader()); var _ = ThreadStdio.out(out)) {
			CliStack.call("clojure-host-boundary", () -> {
				Method main = loader.loadClass(name).getMethod("main", String[].class);
				main.invoke(null, (Object) new String[0]);
				return null;
			});
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	/** Whether {@code node} runs here. */
	static boolean nodeAvailable() {
		try {
			return new ProcessBuilder("node", "--version").start().waitFor() == 0;
		}
		catch (Exception ex) {
			return false;
		}
	}

	/**
	 * Runs {@code node} over a script to completion, asserting it exits cleanly.
	 * @param dir the directory to run in (and to keep the output files in)
	 * @param args the script and its arguments
	 * @return what it printed
	 */
	static String node(Path dir, String... args) throws Exception {
		List<String> command = new ArrayList<>();
		command.add("node");
		command.addAll(List.of(args));
		Path out = Files.createTempFile(dir, "node", ".out");
		Path err = Files.createTempFile(dir, "node", ".err");
		Process process = new ProcessBuilder(command).directory(dir.toFile())
			.redirectOutput(out.toFile())
			.redirectError(err.toFile())
			.start();
		assertThat(process.waitFor(300, TimeUnit.SECONDS)).as("node finished").isTrue();
		String stdout = Files.readString(out, StandardCharsets.UTF_8);
		assertThat(process.exitValue())
			.as("node exit; stdout: %s; stderr: %s", stdout, Files.readString(err, StandardCharsets.UTF_8))
			.isZero();
		return stdout;
	}

}
