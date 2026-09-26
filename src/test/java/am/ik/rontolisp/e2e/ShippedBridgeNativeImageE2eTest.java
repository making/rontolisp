package am.ik.rontolisp.e2e;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
 * {@code --simd} and {@code --gpu} programs need no configuration at all, and neither
 * does a {@code java:} program compiled with {@code --java-static}, whose calls are all
 * direct (.kb/java-interop.md, "Direct calls"). The {@code objc:} program runs on macOS
 * only, where the image's {@code main} is thread 0 and has to hand it to AppKit
 * (.kb/objc.md).
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
		// The bridge's entry points a program still needs -- a class named at run time,
		// a receiver of no known class, a proxy -- and both reflective back-calls bind()
		// makes: _apply (the proxy's lambda) and _strv (a string built by concatenate).
		// The calls that resolve are direct and need no configuration.
		Path jar = compileJar("""
				(defvar *sb* (java:new "java.lang.StringBuilder" "hi"))
				(let ((math "java.lang.Math") (int "java.lang.Integer"))
				  (print (java:static math "max" 3 7))
				  (java:call *sb* "append" (concatenate 'string "!" "?"))
				  (print (java:call *sb* "toString"))
				  (print (java:field int "MAX_VALUE")))
				(print (java:call (java:proxy "java.util.function.Supplier" (lambda (method) 42)) "get"))
				""");
		List<String> expected = List.of("7", "\"hi!?\"", "2147483647", "42");

		Path config = this.tempDir.resolve("config");
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		assertThat(lines(run(java, "-agentlib:native-image-agent=config-output-dir=" + config, "-jar", jar.toString())))
			.isEqualTo(expected);
		assertThat(lines(run(buildImage(jar, "-H:ConfigurationFileDirectories=" + config)))).isEqualTo(expected);
	}

	// What --java-static is for: every call resolved and direct, so the jar carries no
	// bridge and native-image builds it with NO configuration -- no reflect-config.json,
	// no agent run -- into an executable that prints what java -jar prints. Every shape a
	// direct call has: a constructor, instance and interface calls, a static call and a
	// varargs one, fields, a chain typed by declared return types, a let-typed receiver,
	// a returned array, a primitive char, and an exception the member throws -- and the
	// dispatch of a site whose argument kinds are known only when it runs, over numbers,
	// t, a list to a char[], a vector to an int[] and a list to an Object[].
	@Test
	void aJavaStaticJarRunsAsANativeImageWithNoConfiguration() throws Exception {
		Path jar = compileJar("""
				(defun joined (items)
				  (let ((sb (java:new "java.lang.StringBuilder" "items:")))
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
				(defun mx (x y) (java:static "java.lang.Math" "max" x y))
				(defun val (x) (java:static "java.lang.String" "valueOf" x))
				(defun ts (x) (java:static "java.util.Arrays" "toString" x))
				(print (list (mx 1 2) (mx 1 2.5) (val t) (val (list #\\a #\\b)) (ts (vector 1 2)) (ts (list "a" nil))))
				""", "--java-static");
		List<String> expected = List.of("\"items: 1 2 3\"", "7", "2.5", "\"cba\"", "#\\b", "2147483647", "4", "\"1-x\"",
				"(\"a\" \"b\" \"c\")", "3", "\"SATURDAY\"",
				"\"caught: error calling java.lang.Integer.parseInt: java.lang.NumberFormatException:"
						+ " For input string: \\\"zz\\\"\"",
				"(2 2.5 \"true\" \"ab\" \"[1, 2]\" \"[a, null]\")");
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		assertThat(lines(run(java, "-jar", jar.toString()))).isEqualTo(expected);
		try (ZipFile entries = new ZipFile(jar.toFile())) {
			assertThat(entries.stream().map(ZipEntry::getName)).noneMatch(name -> name.contains("Bridge"));
		}
		assertThat(lines(run(buildImage(jar)))).isEqualTo(expected);
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

	// A native image's main IS thread 0, the thread AppKit needs draining: the image
	// has to park it in the run loop and run the program on a worker, as java -jar's
	// launcher does. A timer on thread 0 clicks the button three times and closes the
	// window, so no hand is needed -- and nothing fires at all when thread 0 is never
	// handed over, which is the hang the deadline turns into a failure.
	@Test
	void anObjcJarRunsAsANativeImageThatHandsThreadZeroToAppKit() throws Exception {
		assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"), "objc: needs macOS");
		Path jar = compileJar("""
				(defvar *window* (appkit:window "native image hand-over" :width 240 :height 120))
				(defvar *clicks* 0)
				(defvar *button*
				  (appkit:button *window* "b" :on-click (lambda () (setq *clicks* (+ *clicks* 1)))))
				(defvar *ticks* 0)
				(appkit:timer 0.1 (lambda ()
				                    (setq *ticks* (+ *ticks* 1))
				                    (cond ((<= *ticks* 3) (objc:send *button* "performClick:" nil) t)
				                          (t (objc:send *window* "performClose:" nil) nil))))
				(appkit:wait *window*)
				(format t "clicks ~a~%" *clicks*)
				""");
		List<String> expected = List.of("clicks 3");
		Path config = this.tempDir.resolve("config");
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		assertThat(lines(run(java, "-agentlib:native-image-agent=config-output-dir=" + config, "-jar", jar.toString())))
			.isEqualTo(expected);
		assertThat(lines(
				run(Map.of(), Duration.ofSeconds(60), buildImage(jar, "-H:ConfigurationFileDirectories=" + config))))
			.isEqualTo(expected);
	}

	private Path compileJar(String program, String... options) throws Exception {
		Path source = this.tempDir.resolve("prog.lisp");
		Files.writeString(source, program);
		Path jar = this.tempDir.resolve("prog.jar");
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(new ByteArrayOutputStream()));
		List<String> arguments = new ArrayList<>(
				List.of(source.toString(), "-o", jar.toString(), "--class-name", "com.example.Prog"));
		arguments.addAll(List.of(options));
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
		return run(environment, Duration.ofMinutes(10), executable, arguments);
	}

	private String run(Map<String, String> environment, Duration deadline, Path executable, String... arguments)
			throws Exception {
		List<String> command = new ArrayList<>();
		command.add(executable.toString());
		command.addAll(List.of(arguments));
		// Into a file, not a pipe: a process that hangs must not hang the read.
		Path log = Files.createTempFile(this.tempDir, "run", ".log");
		ProcessBuilder builder = new ProcessBuilder(command).directory(this.tempDir.toFile())
			.redirectErrorStream(true)
			.redirectOutput(log.toFile());
		builder.environment().putAll(environment);
		Process process = builder.start();
		boolean exited = process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS);
		if (!exited) {
			process.destroyForcibly().waitFor();
		}
		String output = Files.readString(log, StandardCharsets.UTF_8);
		assertThat(exited).describedAs("%s still running after %s:%n%s", command, deadline, output).isTrue();
		int status = process.exitValue();
		assertThat(status).describedAs("%s exited %d:%n%s", command, status, output).isZero();
		return output;
	}

}
