package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import am.ik.maven.MavenResolver;
import am.ik.maven.RemoteRepository;
import am.ik.rontolisp.testsupport.JavaLibraryJar;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A program's Java class path from the command line: {@code --java-classpath} entries and
 * {@code --java-dep} coordinates reach the interpreter's class loading, a JVM compile's
 * resolution and what a compiled jar or war runs with.
 */
@Execution(ExecutionMode.CONCURRENT)
class JavaClassPathCliTest {

	@TempDir
	Path tempDir;

	private String runCli(String... args) {
		return runCli(null, args);
	}

	private String runCli(@Nullable MavenResolver resolver, String... args) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(out));
		if (resolver != null) {
			cli.javaDependencyResolver(resolver);
		}
		cli.run(args);
		return out.toString(StandardCharsets.UTF_8);
	}

	// print's leading newline and trailing space, gone.
	private static String printed(String output) {
		return String.join("\n", output.strip().lines().map(String::strip).toList());
	}

	private Path program(String name, String source) throws Exception {
		Path program = this.tempDir.resolve(name);
		Files.writeString(program, source);
		return program;
	}

	private static String run(String... command) throws Exception {
		List<String> line = new java.util.ArrayList<>();
		line.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		line.addAll(List.of(command));
		Process process = new ProcessBuilder(line).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).describedAs("%s said:%n%s", line, output).isZero();
		return output;
	}

	private static Map<String, byte[]> entries(Path archive) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				entries.put(entry.getName(), zip.readAllBytes());
			}
		}
		return entries;
	}

	@Test
	void theInterpreterLoadsClassesFromTheJavaClassPath() throws Exception {
		Path jar = JavaLibraryJar.build(this.tempDir);
		Path program = program("prog.lisp", JavaLibraryJar.LIBRARY_PROGRAM);
		assertThat(printed(runCli(program.toString(), "--java-classpath", jar.toString())))
			.isEqualTo(JavaLibraryJar.LIBRARY_OUTPUT);
	}

	@Test
	void aClassFileOutputResolvesAgainstTheClassPathAndRunsWithIt() throws Exception {
		Path jar = JavaLibraryJar.build(this.tempDir);
		Path program = program("prog.lisp", JavaLibraryJar.LIBRARY_PROGRAM);
		Path out = Files.createDirectories(this.tempDir.resolve("out"));
		runCli(program.toString(), "-o", out.resolve("Prog.class").toString(), "--java-classpath", jar.toString());
		assertThat(printed(run("-cp", out + java.io.File.pathSeparator + jar, "Prog")))
			.isEqualTo(JavaLibraryJar.LIBRARY_OUTPUT);
	}

	@Test
	void aProgramJarCarriesItsClassPathBesideItAndNamesItInItsManifest() throws Exception {
		Path jar = JavaLibraryJar.build(this.tempDir);
		Path program = program("prog.lisp", JavaLibraryJar.LIBRARY_PROGRAM);
		Path output = this.tempDir.resolve("dist/my-app.jar");
		runCli(program.toString(), "-o", output.toString(), "--java-classpath", jar.toString());
		Path copied = this.tempDir.resolve("dist/my-app-lib/fixture-lib.jar");
		assertThat(copied).hasSameBinaryContentAs(jar);
		String manifest = new String(entries(output).get("META-INF/MANIFEST.MF"), StandardCharsets.UTF_8);
		assertThat(manifest).contains("Class-Path: my-app-lib/fixture-lib.jar");
		// The library's own location is gone from the picture: only the copy is read.
		Files.delete(jar);
		assertThat(printed(run("-jar", output.toString()))).isEqualTo(JavaLibraryJar.LIBRARY_OUTPUT);
	}

	@Test
	void aWarCarriesItsClassPathInWebInfLib() throws Exception {
		Path jar = JavaLibraryJar.build(this.tempDir);
		Path program = program("app.lisp", """
				(defun handle (env)
				  (list 200 '(:content-type "text/plain") (list (java:field "fixture.lib.Greeter" "NAME"))))
				(rontolisp:http-handler 'handle)
				""");
		Path war = this.tempDir.resolve("app.war");
		runCli(program.toString(), "-o", war.toString(), "--java-classpath", jar.toString());
		assertThat(entries(war).get("WEB-INF/lib/fixture-lib.jar")).isEqualTo(Files.readAllBytes(jar));
	}

	@Test
	void coordinatesResolveNearestWinsAndReachTheInterpreterTheJarAndThePom() throws Exception {
		MavenResolver resolver = fixtureResolver();
		Path program = program("prog.lisp",
				"(print (java:static \"fixture.app.App\" \"hello\"))\n" + JavaLibraryJar.LIBRARY_PROGRAM);
		String expected = "\"app\"\n" + JavaLibraryJar.LIBRARY_OUTPUT;
		// fixture-app depends on fixture-lib 0.9, which has no jar: the requested 1.0 is
		// nearer, so 0.9 is never fetched.
		String[] coordinates = { "--java-dep", "test.fixture:fixture-app:1.0", "--java-dep",
				"test.fixture:fixture-lib:1.0" };
		assertThat(printed(runCli(resolver, concat(new String[] { program.toString() }, coordinates))))
			.isEqualTo(expected);
		Path output = this.tempDir.resolve("prog.jar");
		runCli(resolver, concat(new String[] { program.toString(), "-o", output.toString(), "--maven-coordinates",
				"com.acme:prog:1.0", "--emit-pom" }, coordinates));
		String manifest = new String(entries(output).get("META-INF/MANIFEST.MF"), StandardCharsets.UTF_8);
		assertThat(manifest.replace("\r\n ", ""))
			.contains("Class-Path: prog-lib/fixture-app-1.0.jar prog-lib/fixture-lib-1.0.jar");
		assertThat(printed(run("-jar", output.toString()))).isEqualTo(expected);
		String pom = Files.readString(this.tempDir.resolve("prog.pom"));
		assertThat(pom).contains("<artifactId>fixture-app</artifactId>")
			.contains("<artifactId>fixture-lib</artifactId>")
			.doesNotContain("<dependencies/>");
		assertThat(new String(entries(output).get("META-INF/maven/com.acme/prog/pom.xml"), StandardCharsets.UTF_8))
			.isEqualTo(pom);
	}

	@Test
	void aLibraryJarNamesItsCoordinatesAndCarriesNoClassPath() throws Exception {
		MavenResolver resolver = fixtureResolver();
		Path program = program("lib.lisp", """
				(defun twice (x) (java:static "fixture.lib.Greeter" "twice" x))
				(rontolisp:jvm-export 'twice :params '(:s32) :returns :s32)
				""");
		Path output = this.tempDir.resolve("lib.jar");
		runCli(resolver, program.toString(), "-o", output.toString(), "--no-main", "--class-name", "com.acme.Lib",
				"--maven-coordinates", "com.acme:lib:1.0", "--java-dep", "test.fixture:fixture-lib:1.0");
		Map<String, byte[]> entries = entries(output);
		assertThat(new String(entries.get("META-INF/MANIFEST.MF"), StandardCharsets.UTF_8))
			.doesNotContain("Class-Path");
		assertThat(new String(entries.get("META-INF/maven/com.acme/lib/pom.xml"), StandardCharsets.UTF_8))
			.contains("<groupId>test.fixture</groupId>")
			.contains("<artifactId>fixture-lib</artifactId>");
		assertThat(this.tempDir.resolve("lib-lib")).doesNotExist();
	}

	@Test
	void aClojureProgramLowersAgainstItsClassPath() throws Exception {
		Path jar = JavaLibraryJar.build(this.tempDir);
		// (Greeter/version) is a static call only where the lowering sees the class: a
		// class it cannot see reads a field of that name.
		Path program = program("prog.clj", """
				(println (fixture.lib.Greeter/version))
				(println (fixture.lib.Greeter/twice 21))
				(println (.greet (fixture.lib.Greeter. "clj")))
				(println fixture.lib.Greeter/NAME)
				""");
		String expected = "1.0\n42\nhello, clj\nfixture";
		assertThat(printed(runCli(program.toString(), "--java-classpath", jar.toString()))).isEqualTo(expected);
		Path output = this.tempDir.resolve("clj.jar");
		runCli(program.toString(), "-o", output.toString(), "--java-classpath", jar.toString());
		assertThat(printed(run("-jar", output.toString()))).isEqualTo(expected);
	}

	@Test
	void aMissingClassPathEntryIsRefusedByName() throws Exception {
		Path program = program("prog.lisp", "(print 1)\n");
		Path missing = this.tempDir.resolve("missing.jar");
		assertThatThrownBy(() -> runCli(program.toString(), "--java-classpath", missing.toString()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("--java-classpath")
			.hasMessageContaining(missing.toString());
	}

	@Test
	void theClassPathNeedsAJvmOutputWhenCompiling() throws Exception {
		Path jar = JavaLibraryJar.build(this.tempDir);
		Path program = program("prog.lisp", "(print 1)\n");
		assertThatThrownBy(() -> runCli(program.toString(), "-o", this.tempDir.resolve("p.wasm").toString(),
				"--java-classpath", jar.toString()))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--java-classpath");
		assertThatThrownBy(() -> runCli(program.toString(), "--java-release", "21"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--java-release");
	}

	@Test
	void aMalformedCoordinateFailsBeforeAnythingRuns() throws Exception {
		Path program = program("prog.lisp", "(print 1)\n");
		assertThatThrownBy(() -> runCli(program.toString(), "--java-dep", "not-a-coordinate"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("--java-dep");
	}

	private static String[] concat(String[] first, String[] second) {
		String[] all = java.util.Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, all, first.length, second.length);
		return all;
	}

	/**
	 * A {@code file:} repository: {@code fixture-app:1.0} (a class of its own) depending
	 * on {@code fixture-lib:0.9}, whose jar is absent, and {@code fixture-lib:1.0}, the
	 * library jar.
	 */
	private MavenResolver fixtureResolver() throws Exception {
		Path repo = this.tempDir.resolve("repo");
		Path libJar = JavaLibraryJar.build(Files.createDirectories(this.tempDir.resolve("build-lib")));
		Path appJar = JavaLibraryJar.build(Files.createDirectories(this.tempDir.resolve("build-app")), "app.jar",
				Map.of("fixture/app/App.java", """
						package fixture.app;

						public class App {
							public static String hello() { return "app"; }
						}
						"""));
		publish(repo, "fixture-lib", "1.0", "", libJar);
		publish(repo, "fixture-lib", "0.9", "", null);
		publish(repo, "fixture-app", "1.0",
				"""
						<dependencies>
						  <dependency><groupId>test.fixture</groupId><artifactId>fixture-lib</artifactId><version>0.9</version></dependency>
						</dependencies>
						""",
				appJar);
		return MavenResolver.builder()
			.localRepository(this.tempDir.resolve("local"))
			.repositories(List.of(new RemoteRepository("fixture", repo.toUri().toString())))
			.systemProperties(Map.of())
			.build();
	}

	private static void publish(Path repo, String artifactId, String version, String dependencies, @Nullable Path jar)
			throws Exception {
		Path dir = Files.createDirectories(repo.resolve("test/fixture/" + artifactId + "/" + version));
		String base = artifactId + "-" + version;
		write(dir.resolve(base + ".pom"), ("""
				<project xmlns="http://maven.apache.org/POM/4.0.0">
				  <modelVersion>4.0.0</modelVersion>
				  <groupId>test.fixture</groupId>
				  <artifactId>%s</artifactId>
				  <version>%s</version>
				  %s
				</project>
				""".formatted(artifactId, version, dependencies)).getBytes(StandardCharsets.UTF_8));
		if (jar != null) {
			write(dir.resolve(base + ".jar"), Files.readAllBytes(jar));
		}
	}

	// A file and its .sha1, as a repository publishes them.
	private static void write(Path file, byte[] bytes) throws Exception {
		Files.write(file, bytes);
		Files.writeString(file.resolveSibling(file.getFileName() + ".sha1"),
				java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes)));
	}

}
