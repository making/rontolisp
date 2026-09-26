package am.ik.rontolisp.e2e;

import java.io.ByteArrayInputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.cli.RontoLispCli;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What {@code --java-static} is for, end to end: a program whose {@code java:} calls all
 * resolved compiles to a jar with no reflection in it, and GraalVM native-image builds
 * that jar into an executable with NO reachability metadata -- no
 * {@code reflect-config.json}, no agent run -- which prints exactly what
 * {@code java -jar} prints (.kb/java-interop.md, "Direct calls").
 *
 * <p>
 * Opt-in ({@code -Drontolisp.java.native=true}): a native-image build takes every core
 * for half a minute. It needs a GraalVM {@code native-image} beside the running JVM or on
 * {@code PATH}; missing, the test is skipped unless the opt-in is given, in which case it
 * fails.
 *
 * <pre>
 * ./mvnw -Dtest=JavaStaticNativeImageE2eTest -DfailIfNoTests=false -Drontolisp.java.native=true test
 * </pre>
 */
class JavaStaticNativeImageE2eTest {

	private static final boolean OPTED_IN = Boolean.getBoolean("rontolisp.java.native");

	/**
	 * Every site shape a direct call has: a constructor, instance and interface calls, a
	 * static call and a varargs one, a field, a chain typed by declared return types,
	 * declared receivers, a returned array, a primitive char, and an exception the member
	 * throws, caught in Lisp.
	 */
	private static final String PROGRAM = """
			(defun joined (items)
			  (let ((sb (java:new "java.lang.StringBuilder" "items:")))
			    (declare (type (java:object "java.lang.StringBuilder") sb))
			    (dolist (x items)
			      (java:call sb "append" " ")
			      (java:call sb "append" (the (java:object "int") x)))
			    (java:call sb "toString")))
			(print (joined (list 1 2 3)))
			(print (java:static "java.lang.Math" "max" 3 7))
			(print (java:static "java.lang.Math" "max" 2.5 1))
			(print (java:call (java:call (java:new "java.lang.StringBuilder" "abc") "reverse") "toString"))
			(print (java:call (java:new "java.lang.StringBuilder" "abc") "charAt" 1))
			(print (java:field "java.lang.Integer" "MAX_VALUE"))
			(print (java:field (java:new "java.awt.Point" 3 4) "y"))
			(print (java:static "java.lang.String" "format" "%s-%s" 1 "x"))
			(print (java:call (java:static "java.util.regex.Pattern" "compile" ",") "split" "a,b,c"))
			(print (java:call (java:static "java.util.List" "of" 1 2 3) "size"))
			(print (java:call (java:call (java:static "java.time.LocalDate" "of" 2026 9 26) "getDayOfWeek")
			                  "toString"))
			(print (handler-case (java:static "java.lang.Integer" "parseInt" "zz")
			         (error (e) (format nil "caught: ~a" e))))
			""";

	@TempDir
	Path tempDir;

	@Test
	void aJavaStaticJarBuildsWithNativeImageWithoutMetadata() throws Exception {
		assumeTrue(OPTED_IN,
				"the native-image leg is opt-in (a build takes every core): pass -Drontolisp.java.native=true");
		Path nativeImage = nativeImage();
		if (nativeImage == null) {
			fail("-Drontolisp.java.native=true but no native-image beside " + System.getProperty("java.home")
					+ " or on PATH");
			return;
		}
		Path source = this.tempDir.resolve("app.lisp");
		Files.writeString(source, PROGRAM);
		Path jar = this.tempDir.resolve("app.jar");
		new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(java.io.OutputStream.nullOutputStream()))
			.run(new String[] { source.toString(), "--java-static", "-o", jar.toString() });
		assertThat(jar).exists();

		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		Run onTheJvm = run(this.tempDir, List.of(java.toString(), "-jar", jar.toString()));
		assertThat(onTheJvm.exit()).as(onTheJvm.output()).isZero();
		assertThat(onTheJvm.output()).startsWith("\"items: 1 2 3\"\n7\n2.5\n\"cba\"\n#\\b\n")
			.contains("\"SATURDAY\"")
			.contains("\"caught: error calling java.lang.Integer.parseInt: java.lang.NumberFormatException:"
					+ " For input string: \\\"zz\\\"\"");

		// No -H:ConfigurationFileDirectories, no META-INF/native-image in the jar: the
		// class names every member it calls in its own bytecode.
		Path executable = this.tempDir.resolve("app");
		Run build = run(this.tempDir,
				List.of(nativeImage.toString(), "--no-fallback", "-jar", jar.toString(), "-o", executable.toString()));
		assertThat(build.exit()).as(build.output()).isZero();
		Run natively = run(this.tempDir, List.of(executable.toString()));
		assertThat(natively.exit()).as(natively.output()).isZero();
		assertThat(natively.output()).isEqualTo(onTheJvm.output());
	}

	private static @Nullable Path nativeImage() {
		Path beside = Path.of(System.getProperty("java.home"), "bin", "native-image");
		if (Files.isExecutable(beside)) {
			return beside;
		}
		String path = System.getenv("PATH");
		if (path != null) {
			for (String dir : path.split(java.io.File.pathSeparator)) {
				Path candidate = Path.of(dir, "native-image");
				if (!dir.isEmpty() && Files.isExecutable(candidate)) {
					return candidate;
				}
			}
		}
		return null;
	}

	private record Run(int exit, String output) {
	}

	private static Run run(Path dir, List<String> command) throws Exception {
		Process process = new ProcessBuilder(new ArrayList<>(command)).directory(dir.toFile())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!process.waitFor(10, TimeUnit.MINUTES)) {
			process.destroyForcibly();
			fail("timed out: " + command);
		}
		return new Run(process.exitValue(), output.strip());
	}

}
