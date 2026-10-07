package am.ik.rontolisp.maven;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.catalina.startup.Tomcat;
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
 * this plugin -- and the rontolisp it embeds -- already installed. Every shelled-out
 * build runs on the Maven running this test and the local repository it uses (the
 * module's surefire configuration passes both), never an {@code mvn} found on PATH: a
 * machine's Maven moves on its own schedule, and its default bindings with it.
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
		Ready ready = assumeReady();
		writeProject(ready.version(), ready.lifecyclePlugins());
		run(this.project, ready.maven("-o", "-q", "package"));

		Path jar = this.project.resolve("target/consumer-1.0.0.jar");
		assertThat(jar).exists();
		// One jar, both languages -- and the handle class the export hands out. The
		// unexported helper is spliced into the kernel, so it is no class of its own.
		assertThat(entries(jar)).contains("com/example/Kernels.class", "app/App.class",
				"am/ik/rontolisp/runtime/RontoFloatArray.class");
		assertThat(entries(jar)).noneMatch(entry -> entry.contains("scale-helpers"));
		assertThat(run(this.project, List.of(javaExecutable().toString(), "-cp", jar.toString(), "app.App")).lines()
			.toList()).containsExactly("12.0", "5.0");

		// And the second build compiles nothing, because nothing is stale.
		assertThat(run(this.project, ready.maven("-o", "package"))).contains("Nothing to compile");
	}

	private void writeProject(String version, Map<String, String> plugins) throws Exception {
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
		// The source-set plugin needs no dependency, source-directory declaration, or
		// jar configuration. Every lifecycle plugin this `package` binds is declared with
		// the version this module's own build pins, so the offline build resolves only
		// what the seeding `install` executed in full. Neither a Maven's default bindings
		// (3.9.16 moved maven-jar-plugin from 3.4.1 to 3.5.0, 3.10.0 to 3.5.1) nor the
		// newest version the local repository holds will do: an online build that only
		// computed a lifecycle -- the failing-build legs below -- leaves a plugin's jar
		// without its dependencies.
		Files.writeString(this.project.resolve("pom.xml"),
				"""
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
						      <plugin>
						        <groupId>org.apache.maven.plugins</groupId>
						        <artifactId>maven-resources-plugin</artifactId>
						        <version>%s</version>
						      </plugin>
						      <plugin>
						        <groupId>org.apache.maven.plugins</groupId>
						        <artifactId>maven-jar-plugin</artifactId>
						        <version>%s</version>
						      </plugin>
						      <plugin>
						        <groupId>org.apache.maven.plugins</groupId>
						        <artifactId>maven-surefire-plugin</artifactId>
						        <version>%s</version>
						      </plugin>
						      <plugin>
						        <groupId>org.apache.maven.plugins</groupId>
						        <artifactId>maven-compiler-plugin</artifactId>
						        <version>%s</version>
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
						""".formatted(Runtime.version().feature(), plugins.get("maven-resources-plugin"),
						plugins.get("maven-jar-plugin"), plugins.get("maven-surefire-plugin"),
						plugins.get("maven-compiler-plugin"), version));
	}

	/**
	 * The war leg: {@code <packaging>war</packaging>}, a {@code provided}
	 * jakarta.servlet-api and one execution's {@code <servlet>true</servlet>} is a
	 * deployable war with no further configuration -- verified by deploying it,
	 * UNMODIFIED, into an embedded Tomcat and driving it.
	 */
	@Test
	void aWarProjectPackagesAWarThatServesOnTomcat() throws Exception {
		Ready ready = assumeReady();
		writeWarProject(ready.version(), "war", true);
		run(this.project, ready.maven("-q", "package"));

		Path war = this.project.resolve("target/warconsumer-1.0.0.war");
		assertThat(war).exists();
		// No web.xml, no file naming the program class -- only the class files
		// maven-war-plugin copied from target/classes on its own, plus the one-line
		// service declaration this goal wrote there.
		assertThat(entries(war)).contains("WEB-INF/classes/App.class",
				"WEB-INF/classes/am/ik/rontolisp/runtime/RontoHttpServer.class",
				"WEB-INF/classes/am/ik/rontolisp/runtime/RontoHttpServlet.class",
				"WEB-INF/classes/am/ik/rontolisp/runtime/RontoHttpServletInitializer.class",
				"WEB-INF/classes/META-INF/services/jakarta.servlet.ServletContainerInitializer");
		assertThat(entries(war)).noneMatch(entry -> entry.equals("WEB-INF/web.xml"));

		Tomcat tomcat = new Tomcat();
		Path base = Files.createDirectories(this.project.resolve("tomcat-base"));
		Files.createDirectories(base.resolve("webapps"));
		tomcat.setBaseDir(base.toAbsolutePath().toString());
		tomcat.setPort(0);
		tomcat.getConnector();
		tomcat.addWebapp("", war.toAbsolutePath().toString());
		tomcat.start();
		try {
			int port = tomcat.getConnector().getLocalPort();
			HttpResponse<String> response = HttpClient.newHttpClient()
				.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).build(),
						HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).isEqualTo("hello from the war");
		}
		finally {
			tomcat.stop();
			tomcat.destroy();
		}
	}

	@Test
	void aWarPackagingWithoutTheServletFlagFailsNamingWhichIsMissing() throws Exception {
		Ready ready = assumeReady();
		writeWarProject(ready.version(), "war", false);

		ProcessResult result = runAllowingFailure(this.project, ready.maven("package"));
		assertThat(result.status()).isNotZero();
		assertThat(result.output()).contains("rontolisp.servlet").contains("packaging");
	}

	@Test
	void theServletFlagWithoutWarPackagingFailsNamingWhichIsMissing() throws Exception {
		Ready ready = assumeReady();
		writeWarProject(ready.version(), "jar", true);

		ProcessResult result = runAllowingFailure(this.project, ready.maven("package"));
		assertThat(result.status()).isNotZero();
		assertThat(result.output()).contains("rontolisp.servlet").contains("<packaging>war</packaging>");
	}

	private void writeWarProject(String version, String packaging, boolean servlet) throws Exception {
		Path app = this.project.resolve("src/main/lisp/App.lisp");
		Files.createDirectories(app.getParent());
		Files.writeString(app, """
				(defun handle (env)
				  (declare (ignore env))
				  (list 200 '(:content-type "text/plain") (list "hello from the war")))

				(rontolisp:http-handler 'handle)
				""");
		Files.writeString(this.project.resolve("pom.xml"), """
				<project xmlns="http://maven.apache.org/POM/4.0.0">
				  <modelVersion>4.0.0</modelVersion>
				  <groupId>app</groupId>
				  <artifactId>warconsumer</artifactId>
				  <version>1.0.0</version>
				  <packaging>%s</packaging>
				  <properties>
				    <maven.compiler.release>%d</maven.compiler.release>
				    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
				  </properties>
				  <dependencies>
				    <dependency>
				      <groupId>jakarta.servlet</groupId>
				      <artifactId>jakarta.servlet-api</artifactId>
				      <version>6.0.0</version>
				      <scope>provided</scope>
				    </dependency>
				  </dependencies>
				  <build>
				    <plugins>
				      <plugin>
				        <groupId>org.apache.maven.plugins</groupId>
				        <artifactId>maven-war-plugin</artifactId>
				        <version>3.5.1</version>
				        <configuration>
				          <failOnMissingWebXml>false</failOnMissingWebXml>
				        </configuration>
				      </plugin>
				      <plugin>
				        <groupId>am.ik.rontolisp</groupId>
				        <artifactId>rontolisp-maven-plugin</artifactId>
				        <version>%s</version>
				        <executions>
				          <execution>
				            <goals><goal>compile</goal></goals>
				            <configuration>
				              <servlet>%s</servlet>
				            </configuration>
				          </execution>
				        </executions>
				      </plugin>
				    </plugins>
				  </build>
				</project>
				""".formatted(packaging, Runtime.version().feature(), version, servlet));
	}

	private Ready assumeReady() {
		assumeTrue("true".equals(System.getProperty("rontolisp.plugin.e2e")),
				"the Maven build E2E is opt-in (it shells out to Maven): pass -Drontolisp.plugin.e2e=true");
		String version = System.getProperty("rontolisp.plugin.version");
		String mavenHome = System.getProperty("rontolisp.plugin.e2e.maven.home");
		String localRepository = System.getProperty("rontolisp.plugin.e2e.local.repository");
		assumeTrue(version != null && mavenHome != null && localRepository != null,
				"run through this module's pom, whose surefire configuration names the plugin version,"
						+ " the Maven and the local repository");
		Path maven = Path.of(mavenHome, "bin", "mvn");
		assumeTrue(Files.isExecutable(maven), "no Maven executable at " + maven);
		Path installed = Path.of(localRepository, "am", "ik", "rontolisp", "rontolisp-maven-plugin", version,
				"rontolisp-maven-plugin-" + version + ".jar");
		assumeTrue(Files.isRegularFile(installed),
				"the plugin is not in the local repository: run `./mvnw -f rontolisp-maven-plugin/pom.xml install`");
		Map<String, String> lifecyclePlugins = new LinkedHashMap<>();
		for (String artifactId : List.of("maven-resources-plugin", "maven-jar-plugin", "maven-surefire-plugin",
				"maven-compiler-plugin")) {
			String pinned = System.getProperty("rontolisp.plugin.e2e." + artifactId);
			assumeTrue(pinned != null, "rontolisp.plugin.e2e." + artifactId + " is unset");
			lifecyclePlugins.put(artifactId, pinned);
		}
		return new Ready(maven, Path.of(localRepository), version, lifecyclePlugins);
	}

	/**
	 * The Maven running this test, the local repository it uses, this plugin's version,
	 * and the version this module's build pins for each lifecycle plugin the offline
	 * fixture declares, keyed by artifact id.
	 */
	private record Ready(Path mavenExecutable, Path localRepository, String version,
			Map<String, String> lifecyclePlugins) {

		List<String> maven(String... arguments) {
			List<String> command = new ArrayList<>();
			command.add(this.mavenExecutable.toString());
			command.add("-Dmaven.repo.local=" + this.localRepository);
			command.addAll(List.of(arguments));
			return command;
		}

	}

	private record ProcessResult(int status, String output) {
	}

	private static List<String> entries(Path jar) throws Exception {
		try (JarFile file = new JarFile(jar.toFile())) {
			return file.stream().map(JarEntry::getName).toList();
		}
	}

	private static Path javaExecutable() {
		return Path.of(System.getProperty("java.home"), "bin", "java");
	}

	private static String run(Path directory, List<String> command) throws Exception {
		ProcessResult result = runAllowingFailure(directory, command);
		assertThat(result.status()).describedAs("%s exited %d:%n%s", command, result.status(), result.output())
			.isZero();
		return result.output();
	}

	private static ProcessResult runAllowingFailure(Path directory, List<String> command) throws Exception {
		Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int status = process.waitFor();
		return new ProcessResult(status, output);
	}

}
