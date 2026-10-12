package am.ik.rontolisp.e2e;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import am.ik.rontolisp.cli.RontoLispCli;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * The native binary lowers a Clojure program to the IR this JVM's front end lowers it to.
 * A Clojure host form means what the Java class it names says -- a static method or a
 * field, a throwable constructed as an exception or left to {@code java:new}, the methods
 * a Java face implements -- so the lowering asks the class, and a native image holds only
 * the classes and the reflection its build registered: it reads the JDK's class files and
 * the program's class path instead ({@code .kb/clojure-frontend.md}, "Host classes"). A
 * divergence is a program the binary compiles differently from the jar, which the
 * examples' output checks see only where the output differs -- the 2026-10-12 one left
 * {@code (Exception. "outer" cause)} to {@code java:new}, undefined on wasm.
 * <p>
 * Each program of {@link ClojureLoweringCorpus} is dumped with {@code --dump-ir} by the
 * binary and by this JVM's {@link RontoLispCli}; the binary finds the JDK this JVM runs
 * on ({@code JAVA_HOME} or the {@code java} on {@code PATH}).
 * <p>
 * Runs only when {@code -Drontolisp.binary=<path>} points at a built native binary.
 */
class ClojureNativeLoweringE2eTest {

	private static final Path PROJECT_DIR = Path.of("").toAbsolutePath();

	private static final long TIMEOUT_SECONDS = 300;

	@TempDir
	static Path workDir;

	@TestFactory
	Stream<DynamicTest> theBinaryLowersEveryProgramAsTheJvmDoes() throws Exception {
		String binary = System.getProperty("rontolisp.binary");
		assumeTrue(binary != null, "rontolisp.binary not set; skipping native-binary E2E");
		Path bin = Path.of(binary).toAbsolutePath();
		assumeTrue(Files.isExecutable(bin), () -> "not an executable binary: " + bin);
		List<DynamicTest> tests = new ArrayList<>();
		for (ClojureLoweringCorpus.Program program : ClojureLoweringCorpus.programs(workDir)) {
			tests.add(dynamicTest(program.name(), () -> {
				List<String> jvm = dumpOnThisJvm(program).lines().toList();
				List<String> image = dumpWithBinary(bin, program).lines().toList();
				assertThat(jvm).as("%s: the JVM's dump", program.name()).isNotEmpty();
				int differs = firstDifference(jvm, image);
				assertThat(differs)
					.as("%s: the binary's IR differs from the JVM's at form %d%n  jvm:    %s%n  binary: %s",
							program.name(), differs, excerpt(jvm, differs), excerpt(image, differs))
					.isEqualTo(-1);
			}));
		}
		return tests.stream();
	}

	private static String dumpOnThisJvm(ClojureLoweringCorpus.Program program) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(out, true));
		cli.run(arguments(program).toArray(String[]::new));
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String dumpWithBinary(Path bin, ClojureLoweringCorpus.Program program)
			throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(bin.toString());
		command.addAll(arguments(program));
		Path errors = Files.createTempFile(workDir, "dump-", ".err");
		Process process = new ProcessBuilder(command).directory(PROJECT_DIR.toFile())
			.redirectError(errors.toFile())
			.start();
		process.getOutputStream().close();
		byte[] out = process.getInputStream().readAllBytes();
		assertThat(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
			.as("%s: the binary's dump finished", program.name())
			.isTrue();
		assertThat(process.exitValue()).as("%s: the binary's dump exited%n%s", program.name(), Files.readString(errors))
			.isZero();
		return new String(out, StandardCharsets.UTF_8);
	}

	private static List<String> arguments(ClojureLoweringCorpus.Program program) {
		List<String> arguments = new ArrayList<>();
		arguments.add(program.file().toString());
		for (Path entry : program.javaClassPath()) {
			arguments.add("--java-classpath");
			arguments.add(entry.toString());
		}
		arguments.add("--dump-ir");
		return arguments;
	}

	/** The index of the first line the two dumps differ at, or -1 when they are equal. */
	private static int firstDifference(List<String> a, List<String> b) {
		int common = Math.min(a.size(), b.size());
		for (int i = 0; i < common; i++) {
			if (!a.get(i).equals(b.get(i))) {
				return i;
			}
		}
		return a.size() == b.size() ? -1 : common;
	}

	private static String excerpt(List<String> lines, int index) {
		if (index < 0 || index >= lines.size()) {
			return "(none)";
		}
		String line = lines.get(index);
		return line.length() <= 400 ? line : line.substring(0, 400) + " ...";
	}

}
