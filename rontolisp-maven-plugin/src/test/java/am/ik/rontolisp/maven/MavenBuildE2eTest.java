package am.ik.rontolisp.maven;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The half {@link LispSourceSetTest} cannot reach: that a REAL Maven build, with the
 * plugin declared in a pom and nothing else configured, runs the goal early enough for
 * {@code src/main/java} to compile against the classes it writes, and that
 * {@code maven-jar-plugin} then packages the Lisp and Java classes into one jar.
 * <p>
 * The phase binding is the whole point of the test. Maven injects the default lifecycle
 * bindings ahead of a POM-declared plugin, so a goal bound to {@code compile} would run
 * after javac and the Java source would not compile; {@code process-sources} is the only
 * ordering declaration order cannot break, and only a real build can say so.
 * <p>
 * Opt-in ({@code -Drontolisp.plugin.e2e=true}), because it shells out to Maven and needs
 * this plugin -- and the rontolisp it embeds -- already installed:
 *
 * <pre>
 * ./mvnw install -DskipTests
 * ./mvnw -f rontolisp-maven-plugin/pom.xml install -DskipTests
 * ./mvnw -f rontolisp-maven-plugin/pom.xml -Drontolisp.plugin.e2e=true test
 * </pre>
 */
class MavenBuildE2eTest {

	@TempDir
	Path project;

	@Test
	void aRealBuildCompilesTheLispBeforeTheJavaAndJarsBoth() throws Exception {
		assumeTrue("true".equals(System.getProperty("rontolisp.plugin.e2e")),
				"the Maven build E2E is opt-in (it shells out to Maven): pass -Drontolisp.plugin.e2e=true");
		Optional<Path> maven = maven();
		assumeTrue(maven.isPresent(),
				"no Maven executable found (mvn on PATH, MAVEN_HOME, or the ./mvnw distribution)");
		String version = System.getProperty("rontolisp.plugin.version");
		assumeTrue(version != null, "rontolisp.plugin.version is unset");
		Path installed = Path.of(System.getProperty("user.home"), ".m2", "repository", "am", "ik", "rontolisp",
				"rontolisp-maven-plugin", version, "rontolisp-maven-plugin-" + version + ".jar");
		assumeTrue(Files.isRegularFile(installed),
				"the plugin is not in the local repository: run `./mvnw -f rontolisp-maven-plugin/pom.xml install`");

		writeProject(version);
		run(maven.get(), this.project, "-o", "-q", "package");

		Path jar = this.project.resolve("target/consumer-1.0.0.jar");
		assertThat(jar).exists();
		// One jar, both languages -- and the handle class the export hands out. The
		// unexported helper is spliced into the kernel, so it is no class of its own.
		assertThat(entries(jar)).contains("com/example/Kernels.class", "app/App.class",
				"am/ik/rontolisp/runtime/RontoFloatArray.class");
		assertThat(entries(jar)).noneMatch(entry -> entry.contains("scale-helpers"));
		assertThat(run(javaExecutable(), this.project, "-cp", jar.toString(), "app.App").lines().toList())
			.containsExactly("12.0", "5.0");

		// And the second build compiles nothing, because nothing is stale.
		assertThat(run(maven.get(), this.project, "-o", "package")).contains("Nothing to compile");
	}

	private void writeProject(String version) throws Exception {
		Path kernels = this.project.resolve("src/main/lisp/com/example/Kernels.lisp");
		Files.createDirectories(kernels.getParent());
		Files.writeString(kernels, """
				(load "scale-helpers.lisp")

				(defvar *scale* 2.0)

				(defun scaled-sum (a b)
				  (scale-by *scale* (+ a b)))

				(defun norm2 (x)
				  (sqrt (vec:dot x x)))

				(rontolisp:jvm-export 'scaled-sum :params '(:float :float) :returns :float)
				(rontolisp:jvm-export 'norm2 :params '(:float-vector) :returns :float)
				""");
		// Ordinary Lisp beside the kernels: no export, a name no class could carry, and
		// the build has to be fine with both -- a source set is Lisp, not a pile of
		// exports.
		Files.writeString(this.project.resolve("src/main/lisp/com/example/scale-helpers.lisp"), """
				(defun scale-by (scale x)
				  (* scale x))
				""");
		Path app = this.project.resolve("src/main/java/app/App.java");
		Files.createDirectories(app.getParent());
		Files.writeString(app, """
				package app;

				import am.ik.rontolisp.runtime.RontoFloatArray;
				import com.example.Kernels;

				public class App {
					public static void main(String[] args) {
						System.out.println(Kernels.scaledSum(2.5, 3.5));
						System.out.println(Kernels.norm2(RontoFloatArray.of(new double[] { 3.0, 4.0 })));
					}
				}
				""");
		// Nothing but the one <plugin> block: no dependency, no source-directory
		// declaration, no jar configuration.
		Files.writeString(this.project.resolve("pom.xml"), """
				<project xmlns="http://maven.apache.org/POM/4.0.0">
				  <modelVersion>4.0.0</modelVersion>
				  <groupId>app</groupId>
				  <artifactId>consumer</artifactId>
				  <version>1.0.0</version>
				  <properties>
				    <maven.compiler.release>%d</maven.compiler.release>
				    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
				  </properties>
				  <build>
				    <plugins>
				      <!-- Pinned to what this module already resolved: the build runs offline. -->
				      <plugin>
				        <groupId>org.apache.maven.plugins</groupId>
				        <artifactId>maven-surefire-plugin</artifactId>
				        <version>3.5.2</version>
				      </plugin>
				      <plugin>
				        <groupId>org.apache.maven.plugins</groupId>
				        <artifactId>maven-compiler-plugin</artifactId>
				        <version>3.13.0</version>
				      </plugin>
				      <plugin>
				        <groupId>am.ik.rontolisp</groupId>
				        <artifactId>rontolisp-maven-plugin</artifactId>
				        <version>%s</version>
				        <executions>
				          <execution>
				            <goals><goal>compile</goal></goals>
				          </execution>
				        </executions>
				      </plugin>
				    </plugins>
				  </build>
				</project>
				""".formatted(Runtime.version().feature(), version));
	}

	private static List<String> entries(Path jar) throws Exception {
		try (JarFile file = new JarFile(jar.toFile())) {
			return file.stream().map(JarEntry::getName).toList();
		}
	}

	private static Optional<Path> maven() {
		List<Path> candidates = new ArrayList<>();
		String path = System.getenv("PATH");
		if (path != null) {
			for (String directory : path.split(File.pathSeparator)) {
				candidates.add(Path.of(directory, "mvn"));
			}
		}
		String home = System.getenv("MAVEN_HOME");
		if (home != null) {
			candidates.add(Path.of(home, "bin", "mvn"));
		}
		// The ./mvnw wrapper's own downloaded distribution, which is what a machine that
		// only ever builds through the wrapper has.
		Path wrapper = Path.of(System.getProperty("user.home"), ".m2", "wrapper", "dists");
		if (Files.isDirectory(wrapper)) {
			try (Stream<Path> tree = Files.walk(wrapper, 4)) {
				tree.filter(candidate -> candidate.endsWith(Path.of("bin", "mvn"))).forEach(candidates::add);
			}
			catch (Exception ignored) {
				// Fall through to whatever the other candidates found.
			}
		}
		return candidates.stream().filter(Files::isExecutable).findFirst();
	}

	private static Path javaExecutable() {
		return Path.of(System.getProperty("java.home"), "bin", "java");
	}

	private static String run(Path executable, Path directory, String... arguments) throws Exception {
		List<String> command = new ArrayList<>();
		command.add(executable.toString());
		command.addAll(List.of(arguments));
		Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int status = process.waitFor();
		assertThat(status).describedAs("%s exited %d:%n%s", command, status, output).isZero();
		return output;
	}

}
