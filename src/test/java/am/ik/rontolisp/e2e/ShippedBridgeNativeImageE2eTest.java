package am.ik.rontolisp.e2e;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.cli.RontoLispCli;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A {@code -o prog.jar} whose program needs a template bridge builds into a working
 * GraalVM native image: the bridge travels as an ordinary class file inside the jar, so
 * nothing is defined at run time (a native image refuses {@code Lookup.defineClass} with
 * an {@code UnsupportedFeatureError}). The {@code java:} program's reflective calls are
 * covered by the configuration the tracing agent records from one {@code java -jar} run;
 * the {@code geom:} kernels need no configuration at all.
 * <p>
 * Opt-in ({@code -Drontolisp.native-image.e2e=true}), because it runs
 * {@code native-image} (about 20 s a program) from the running JDK, which must be a
 * GraalVM:
 *
 * <pre>
 * ./mvnw -Dtest=ShippedBridgeNativeImageE2eTest -DfailIfNoTests=false -Drontolisp.native-image.e2e=true test
 * </pre>
 */
class ShippedBridgeNativeImageE2eTest {

	@TempDir
	Path tempDir;

	private Path nativeImage = Path.of("native-image");

	@BeforeEach
	void requireNativeImage() {
		assumeTrue("true".equals(System.getProperty("rontolisp.native-image.e2e")),
				"the shipped-bridge native-image E2E is opt-in: pass -Drontolisp.native-image.e2e=true");
		this.nativeImage = Path.of(System.getProperty("java.home"), "bin", "native-image");
		assumeTrue(Files.isExecutable(this.nativeImage),
				() -> "the running JDK has no native-image: " + this.nativeImage);
	}

	@Test
	void aJavaInteropJarRunsAsANativeImageWithAgentConfiguration() throws Exception {
		// Every entry point of the bridge, and both reflective back-calls bind() makes:
		// _apply (the proxy's lambda) and _strv (a string built by concatenate).
		Path jar = compileJar("""
				(print (java:static "java.lang.Math" "max" 3 7))
				(let ((sb (java:new "java.lang.StringBuilder" "hi")))
				  (java:call sb "append" (concatenate 'string "!" "?"))
				  (print (java:call sb "toString")))
				(print (java:field "java.lang.Integer" "MAX_VALUE"))
				(print (java:call (java:proxy "java.util.function.Supplier" (lambda (method) 42)) "get"))
				""");
		List<String> expected = List.of("7", "\"hi!?\"", "2147483647", "42");

		Path config = this.tempDir.resolve("config");
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		assertThat(lines(run(java, "-agentlib:native-image-agent=config-output-dir=" + config, "-jar", jar.toString())))
			.isEqualTo(expected);
		assertThat(lines(run(buildImage(jar, "-H:ConfigurationFileDirectories=" + config)))).isEqualTo(expected);
	}

	@Test
	void aGeomJarRunsAsANativeImageWithNoConfiguration() throws Exception {
		// The flagless geom: bridge used to be defined at run time too, and the native
		// image's refusal is an Error, not the LinkageError its degrade catches.
		Path jar = compileJar("(print (geom:volume (geom:box 10)))");
		assertThat(lines(run(buildImage(jar)))).isEqualTo(List.of("1000.0"));
	}

	private Path compileJar(String program) throws Exception {
		Path source = this.tempDir.resolve("prog.lisp");
		Files.writeString(source, program);
		Path jar = this.tempDir.resolve("prog.jar");
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(new ByteArrayOutputStream()));
		cli.run(new String[] { source.toString(), "-o", jar.toString(), "--class-name", "com.example.Prog" });
		assertThat(jar).exists();
		return jar;
	}

	private Path buildImage(Path jar, String... options) throws Exception {
		Path binary = this.tempDir.resolve("prog");
		List<String> arguments = new ArrayList<>(List.of("-jar", jar.toString()));
		arguments.addAll(List.of(options));
		arguments.addAll(List.of("-o", binary.toString()));
		run(this.nativeImage, arguments.toArray(String[]::new));
		return binary;
	}

	private static List<String> lines(String output) {
		return output.lines().filter(line -> !line.isBlank()).toList();
	}

	private String run(Path executable, String... arguments) throws Exception {
		List<String> command = new ArrayList<>();
		command.add(executable.toString());
		command.addAll(List.of(arguments));
		Process process = new ProcessBuilder(command).directory(this.tempDir.toFile())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int status = process.waitFor();
		assertThat(status).describedAs("%s exited %d:%n%s", command, status, output).isZero();
		return output;
	}

}
