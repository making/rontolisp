package am.ik.rontolisp.e2e;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.eval.FfiInterop;
import am.ik.rontolisp.eval.LinalgBlas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A {@code -o prog.jar} whose program needs a template bridge builds into a working
 * GraalVM native image: the bridge travels as an ordinary class file inside the jar, so
 * nothing is defined at run time (a native image refuses {@code Lookup.defineClass} with
 * an {@code UnsupportedFeatureError}). The {@code java:} program's reflective calls and
 * the {@code --blas} / {@code ffi:} programs' downcalls are covered by the configuration
 * the tracing agent records from one {@code java -jar} run; the {@code geom:},
 * {@code --simd} and {@code --gpu} programs need no configuration at all. {@code objc:}
 * needs macOS and is not covered here.
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

	@Test
	void aSimdJarRunsAsANativeImageAcceleratedOrDegraded() throws Exception {
		// The bridge is loaded and forced to link inside _simdInit's LinkageError
		// catch: an image built without jdk.incubator.vector warns and runs the scalar
		// defuns, one built with it runs the lanes.
		Path jar = compileJar("(print (vec:sum (vec:add #d(1.0 2.0 3.0) #d(4.0 5.0 6.0))))", "--simd");
		String warning = "rontolisp: warning: --simd: jdk.incubator.vector is unavailable";
		List<String> degraded = lines(run(buildImage(jar)));
		assertThat(degraded).hasSize(2).last().isEqualTo("21.0");
		assertThat(degraded.getFirst()).startsWith(warning);
		assertThat(lines(run(buildImage(jar, "--add-modules", "jdk.incubator.vector")))).containsExactly("21.0");
	}

	@Test
	void aBlasJarRunsAsANativeImageWithAgentConfiguration() throws Exception {
		assumeTrue(LinalgBlas.available(), LinalgBlas::description);
		// An 8x8 product is above the size the bridge declines, so the image makes the
		// downcall the agent recorded; the verbose line proves the library bound.
		Path jar = compileJar("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 65) '(8 8)))
				(print (linalg:to-list (linalg:dot *a* (linalg:eye 8))))
				""", "--blas");
		Path config = this.tempDir.resolve("config");
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		List<String> onTheJvm = lines(run(Map.of("RONTOLISP_BLAS_VERBOSE", "1"), java,
				"-agentlib:native-image-agent=config-output-dir=" + config, "-jar", jar.toString()));
		assertThat(onTheJvm.getFirst()).startsWith("rontolisp: --blas bound ");
		assertThat(lines(run(Map.of("RONTOLISP_BLAS_VERBOSE", "1"),
				buildImage(jar, "-H:ConfigurationFileDirectories=" + config))))
			.isEqualTo(onTheJvm);
	}

	@Test
	void aGpuJarRunsAsANativeImageWithNoConfiguration() throws Exception {
		// The whole renamed am.ik.gpu travels in the jar. A machine without a device
		// declines every product, so this pins that the image loads the library and
		// runs; the device path itself is .kb/gpu.md's.
		Path jar = compileJar("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 65) '(8 8)))
				(print (linalg:sum (linalg:matmul *a* *a*)))
				""", "--gpu");
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		List<String> onTheJvm = lines(run(java, "-jar", jar.toString()));
		assertThat(onTheJvm).hasSize(1);
		assertThat(lines(run(buildImage(jar)))).isEqualTo(onTheJvm);
	}

	@Test
	void anFfiJarRunsAsANativeImageWithAgentConfiguration() throws Exception {
		assumeTrue(FfiInterop.available(), FfiInterop::description);
		assumeTrue(Files.exists(Path.of("/lib/x86_64-linux-gnu/libm.so.6"))
				|| Files.exists(Path.of("/lib/aarch64-linux-gnu/libm.so.6")), "a Linux libm.so.6");
		Path jar = compileJar("""
				(let ((strlen (ffi:symbol (ffi:open) "strlen")))
				  (print (ffi:call strlen :long '(:string) "hello, world")))
				(print (ffi:call (ffi:symbol (ffi:open "libm.so.6") "cos") :double '(:double) 0.0))
				(let ((p (ffi:alloc 16)))
				  (ffi:poke p :int 42)
				  (print (ffi:peek p :int))
				  (ffi:free p))
				""");
		List<String> expected = List.of("12", "1.0", "42");
		Path config = this.tempDir.resolve("config");
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		assertThat(lines(run(java, "-agentlib:native-image-agent=config-output-dir=" + config, "-jar", jar.toString())))
			.isEqualTo(expected);
		assertThat(lines(run(buildImage(jar, "-H:ConfigurationFileDirectories=" + config)))).isEqualTo(expected);
	}

	private Path compileJar(String program, String... flags) throws Exception {
		Path source = this.tempDir.resolve("prog.lisp");
		Files.writeString(source, program);
		Path jar = this.tempDir.resolve("prog.jar");
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(new ByteArrayOutputStream()));
		List<String> arguments = new ArrayList<>(
				List.of(source.toString(), "-o", jar.toString(), "--class-name", "com.example.Prog"));
		arguments.addAll(List.of(flags));
		cli.run(arguments.toArray(String[]::new));
		assertThat(jar).exists();
		return jar;
	}

	private Path buildImage(Path jar, String... options) throws Exception {
		Path binary = this.tempDir.resolve("prog" + System.nanoTime());
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
		return run(Map.of(), executable, arguments);
	}

	private String run(Map<String, String> environment, Path executable, String... arguments) throws Exception {
		List<String> command = new ArrayList<>();
		command.add(executable.toString());
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command).directory(this.tempDir.toFile()).redirectErrorStream(true);
		builder.environment().putAll(environment);
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int status = process.waitFor();
		assertThat(status).describedAs("%s exited %d:%n%s", command, status, output).isZero();
		return output;
	}

}
