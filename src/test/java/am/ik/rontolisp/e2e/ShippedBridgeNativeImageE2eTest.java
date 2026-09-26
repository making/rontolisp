package am.ik.rontolisp.e2e;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
 * the {@code geom:} kernels need no configuration at all, and neither does a
 * {@code java:} program compiled with {@code --java-static}, whose calls are all direct
 * (.kb/java-interop.md, "Direct calls").
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
		// an argument of no known kind, a proxy -- and both reflective back-calls bind()
		// makes: _apply (the proxy's lambda) and _strv (a string built by concatenate).
		// The calls that resolve are direct and need no configuration.
		Path jar = compileJar("""
				(let ((math "java.lang.Math") (int "java.lang.Integer"))
				  (print (java:static math "max" 3 7))
				  (let ((sb (java:new "java.lang.StringBuilder" "hi")))
				    (java:call sb "append" (concatenate 'string "!" "?"))
				    (print (java:call sb "toString")))
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
