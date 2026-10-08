package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import am.ik.artifact.GitFetcher;
import am.ik.rontolisp.eval.ClojureDepsRepositories;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.JavaLibraryJar;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A {@code deps.edn} project whose Maven and git coordinates are fetched for real -- from
 * a {@code file:} Maven repository and git repositories the test makes on disk, nothing
 * reaching the network -- through the command line: the same program on the interpreter,
 * a jar, a Preview 1 module and a component; a Java library a Maven dependency brings on
 * the interpreter's class path and beside a jar, and at macro time on every backend; a
 * second run from the caches alone. The expected output is the oracle's
 * ({@code clj -Srepro -M src/app/main.clj} over the same repositories, {@code clj}
 * 1.12.6, 2026-10-08): the newest {@code fixture/words} across the tree wins, the
 * optional and test dependencies of a POM are never fetched.
 */
class ClojureDepsFetchCliTest {

	@TempDir
	static Path dir;

	private static String c1 = "";

	private static String c2 = "";

	private static final String APP_OUTPUT = "hi, deps\ngit2:hi\n";

	@BeforeAll
	static void writeRepositories() throws Exception {
		Path repo = Files.createDirectories(dir.resolve("repo"));
		publish(repo, "words", "1.0", "", sources("fixture/words.clj", "(ns fixture.words) (defn word [] \"hello\")"));
		publish(repo, "words", "2.0", "", sources("fixture/words.clj", "(ns fixture.words) (defn word [] \"hi\")"));
		publish(repo, "greeting", "1.0",
				dependency("words", "1.0", "") + dependency("absent-optional", "1.0", "<optional>true</optional>")
						+ dependency("absent-test", "1.0", "<scope>test</scope>"),
				sources("fixture/greeting.clj", """
						(ns fixture.greeting (:require [fixture.words :as w]))
						(defn hello [n] (str (w/word) ", " n))
						"""));
		Path javalib = JavaLibraryJar.build(Files.createDirectories(dir.resolve("build-javalib")), "javalib.jar",
				Map.of("fixture/javalib/Shout.java", """
						package fixture.javalib;

						public class Shout {
							public static String shout(String s) { return s.toUpperCase() + "!"; }
						}
						"""));
		publish(repo, "javalib", "1.0", "", Files.readAllBytes(javalib));
		publish(repo, "shouting", "1.0", dependency("javalib", "1.0", ""), sources("fixture/shouting.clj", """
				(ns fixture.shouting (:import [fixture.javalib Shout]))
				(defn shout [s] (Shout/shout s))
				"""));
		Path git = Files.createDirectories(dir.resolve("git/gitlib"));
		git(git, "init", "--quiet", "--initial-branch=main");
		write(git.resolve("deps.edn"), "{:deps {fixture/words {:mvn/version \"2.0\"}}}\n");
		write(git.resolve("src/gitlib/core.clj"),
				"(ns gitlib.core (:require [fixture.words :as w]))\n(defn tag [] (str \"git1:\" (w/word)))\n");
		c1 = commit(git);
		git(git, "tag", "v1");
		write(git.resolve("src/gitlib/core.clj"),
				"(ns gitlib.core (:require [fixture.words :as w]))\n(defn tag [] (str \"git2:\" (w/word)))\n");
		c2 = commit(git);
		git(git, "tag", "--annotate", "--message=v2", "v2");
		project("app", "fixture/greeting {:mvn/version \"1.0\"} my/gitlib {:git/url \"" + git.toUri()
				+ "\" :git/tag \"v2\" :git/sha \"" + c2.substring(0, 7) + "\"}", "");
		write(dir.resolve("app/src/app/main.clj"), """
				(ns app.main (:require [fixture.greeting :as g] [gitlib.core :as git]))
				(println (g/hello "deps"))
				(println (git/tag))
				""");
		project("java", "fixture/shouting {:mvn/version \"1.0\"}", " :mvn/local-repo \"m2-java\"");
		write(dir.resolve("java/src/java_app/main.clj"),
				"(ns java-app.main (:require [fixture.shouting :as s]))\n(println (s/shout \"deps\"))\n");
		write(dir.resolve("java/src/java_app/macro.clj"), """
				(ns java-app.macro (:require [fixture.shouting :as s]))
				(defmacro loud [x] (s/shout x))
				(println (loud "macro"))
				""");
	}

	@Test
	void mavenAndGitDependenciesAreFetchedAndLoadOnEveryBackend() throws Exception {
		Path main = dir.resolve("app/src/app/main.clj");
		assertThat(runCli(main.toString())).isEqualTo(APP_OUTPUT);
		// what the oracle's selection keeps is fetched; the replaced words 1.0 has its
		// POM read and no jar, the optional and test dependencies nothing at all
		Path m2 = dir.resolve("m2/fixture");
		assertThat(m2.resolve("words/2.0/words-2.0.jar")).exists();
		assertThat(m2.resolve("words/1.0/words-1.0.pom")).exists();
		assertThat(m2.resolve("words/1.0/words-1.0.jar")).doesNotExist();
		assertThat(m2.resolve("absent-optional")).doesNotExist();
		assertThat(m2.resolve("absent-test")).doesNotExist();
		Path jar = dir.resolve("out/app.jar");
		runCli(main.toString(), "-o", jar.toString());
		assertThat(java("-jar", jar.toString())).isEqualTo(APP_OUTPUT);
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path preview1 = dir.resolve("out/app.wasm");
		runCli(main.toString(), "-o", preview1.toString());
		assertThat(wasmtime(preview1)).isEqualTo(APP_OUTPUT);
		Path component = dir.resolve("out/app-c.wasm");
		runCli(main.toString(), "-o", component.toString(), "--component");
		assertThat(wasmtime(component)).isEqualTo(APP_OUTPUT);
	}

	@Test
	void aJavaLibraryAMavenDependencyBringsJoinsTheProgramsClassPath() throws Exception {
		Path main = dir.resolve("java/src/java_app/main.clj");
		assertThat(runCli(main.toString())).isEqualTo("DEPS!\n");
		// :mvn/local-repo is the project's own
		Path javalib = dir.resolve("java/m2-java/fixture/javalib/1.0/javalib-1.0.jar");
		assertThat(javalib).exists();
		Path jar = dir.resolve("out/java.jar");
		runCli(main.toString(), "-o", jar.toString(), "--maven-coordinates", "com.acme:java-app:1.0", "--emit-pom");
		// the jar holding classes travels beside the program; the Clojure source jar is
		// lowered into it
		assertThat(dir.resolve("out/java-lib/javalib-1.0.jar")).hasSameBinaryContentAs(javalib);
		assertThat(dir.resolve("out/java-lib/shouting-1.0.jar")).doesNotExist();
		assertThat(manifest(jar)).contains("Class-Path: java-lib/javalib-1.0.jar");
		assertThat(Files.readString(dir.resolve("out/java.pom"))).contains("<artifactId>javalib</artifactId>")
			.doesNotContain("<artifactId>shouting</artifactId>");
		assertThat(java("-jar", jar.toString())).isEqualTo("DEPS!\n");
	}

	@Test
	void aMacroBodyReachesTheClassesADependencyBringsOnEveryBackend() throws Exception {
		// the expansion runs while the program lowers, on the JVM whatever the target,
		// so the wasm legs print what the Java class answered then
		Path main = dir.resolve("java/src/java_app/macro.clj");
		assertThat(runCli(main.toString())).isEqualTo("MACRO!\n");
		Path jar = dir.resolve("out/macro.jar");
		runCli(main.toString(), "-o", jar.toString());
		assertThat(java("-jar", jar.toString())).isEqualTo("MACRO!\n");
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path preview1 = dir.resolve("out/macro.wasm");
		runCli(main.toString(), "-o", preview1.toString());
		assertThat(wasmtime(preview1)).isEqualTo("MACRO!\n");
		Path component = dir.resolve("out/macro-c.wasm");
		runCli(main.toString(), "-o", component.toString(), "--component");
		assertThat(wasmtime(component)).isEqualTo("MACRO!\n");
	}

	@Test
	void aSecondRunReadsTheCachesAlone() throws Exception {
		Path main = dir.resolve("app/src/app/main.clj");
		runCli(main.toString());
		// no repository and no git to reach: the local repository and the checkout answer
		project("offline", "fixture/greeting {:mvn/version \"1.0\"} my/gitlib {:git/url \""
				+ dir.resolve("git/gitlib").toUri() + "\" :git/sha \"" + c2 + "\"}", "");
		write(dir.resolve("offline/main.clj"), "(require '[fixture.greeting :as g])\n(println (g/hello \"cache\"))\n");
		runCli(dir.resolve("offline/main.clj").toString());
		Path moved = dir.resolve("repo-moved");
		Files.move(dir.resolve("repo"), moved);
		try {
			ClojureDepsRepositories offline = ClojureDepsRepositories.builder()
				.defaultLocalRepository(dir.resolve("m2"))
				.git(new GitFetcher(dir.resolve("gitlibs"), "rontolisp-test-no-such-git"))
				.build();
			assertThat(runCli(offline, dir.resolve("offline/main.clj").toString())).isEqualTo("hi, cache\n");
		}
		finally {
			Files.move(moved, dir.resolve("repo"));
		}
	}

	@Test
	void aCoordinateTheOracleRefusesStopsTheProgramInItsWords() throws Exception {
		project("badtag", "my/gitlib {:git/url \"" + dir.resolve("git/gitlib").toUri()
				+ "\" :git/tag \"v9\" :git/sha \"" + c1 + "\"}", "");
		Path badTag = write(dir.resolve("badtag/main.clj"), "(println :never)\n");
		assertThatThrownBy(() -> runCli(badTag.toString()))
			.hasMessage(badTag + ":1:1: Library my/gitlib has invalid tag: v9");
		Files.writeString(dir.resolve("badtag/deps.edn"),
				"{:mvn/repos {\"plain\" {:url \"http://127.0.0.1:9/repo\"}} :deps {fixture/words {:mvn/version \"1.0\"}}}");
		assertThatThrownBy(() -> runCli(badTag.toString()))
			.hasMessage(badTag + ":1:1: Invalid repo url (http not supported): http://127.0.0.1:9/repo");
	}

	private static String runCli(String... args) {
		return runCli(ClojureDepsRepositories.builder()
			.defaultLocalRepository(dir.resolve("m2"))
			.git(new GitFetcher(dir.resolve("gitlibs"), GitFetcher.DEFAULT_EXECUTABLE))
			.build(), args);
	}

	private static String runCli(ClojureDepsRepositories repositories, String... args) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(out, true, StandardCharsets.UTF_8));
		cli.clojureRepositories(repositories);
		cli.run(args);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String java(String... args) throws Exception {
		List<String> command = new ArrayList<>();
		command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		command.addAll(List.of(args));
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		process.getOutputStream().close();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(300, TimeUnit.SECONDS)).isTrue();
		assertThat(process.exitValue()).as(output).isZero();
		return output;
	}

	private static String wasmtime(Path module) throws Exception {
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-W", "gc=y", "-W",
				"exceptions=y", module.toString());
		assertThat(run.exitCode()).as("wasmtime exit code; stderr: %s", run.stderr()).isZero();
		return run.stdout();
	}

	private static String manifest(Path jar) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				if (entry.getName().equals("META-INF/MANIFEST.MF")) {
					return new String(zip.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n ", "");
				}
			}
		}
		throw new IllegalStateException(jar + " has no manifest");
	}

	/** A project fetching from the fixture repository alone. */
	private static void project(String name, String deps, String more) throws IOException {
		write(dir.resolve(name + "/deps.edn"),
				"{:paths [\"src\"]" + more + " :mvn/repos {\"central\" nil \"clojars\" nil \"fixture\" {:url \""
						+ dir.resolve("repo").toUri() + "\"}} :deps {" + deps + "}}\n");
	}

	private static String dependency(String artifact, String version, String more) {
		return "<dependency><groupId>fixture</groupId><artifactId>" + artifact + "</artifactId><version>" + version
				+ "</version>" + more + "</dependency>";
	}

	/** A jar holding one source file. */
	private static byte[] sources(String entry, String text) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (OutputStream out = bytes; ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry(entry));
			zip.write(text.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return bytes.toByteArray();
	}

	/** A POM and its jar, each with its {@code .sha1}, as a repository publishes them. */
	private static void publish(Path repo, String artifact, String version, String dependencies, byte[] jar)
			throws Exception {
		Path at = Files.createDirectories(repo.resolve("fixture/" + artifact + "/" + version));
		String base = artifact + "-" + version;
		published(at.resolve(base + ".pom"), ("""
				<project xmlns="http://maven.apache.org/POM/4.0.0">
				  <modelVersion>4.0.0</modelVersion>
				  <groupId>fixture</groupId><artifactId>%s</artifactId><version>%s</version>
				  <dependencies>%s</dependencies>
				</project>
				""".formatted(artifact, version, dependencies)).getBytes(StandardCharsets.UTF_8));
		published(at.resolve(base + ".jar"), jar);
	}

	private static void published(Path file, byte[] bytes) throws Exception {
		Files.write(file, bytes);
		Files.writeString(file.resolveSibling(file.getFileName() + ".sha1"),
				HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes)));
	}

	private static Path write(Path path, String text) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, text);
		return path;
	}

	private static String commit(Path repo) throws IOException {
		git(repo, "add", "--all");
		git(repo, "commit", "--quiet", "--message=commit");
		return git(repo, "rev-parse", "HEAD").strip();
	}

	private static String git(Path repo, String... args) throws IOException {
		List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=test", "-c",
				"user.email=test@example.com", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false"));
		command.addAll(List.of(args));
		ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true);
		Map<String, String> environment = builder.environment();
		environment.put("GIT_CONFIG_NOSYSTEM", "1");
		environment.put("GIT_CONFIG_GLOBAL", repo.resolve(".no-global-config").toString());
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		try {
			if (process.waitFor() != 0) {
				throw new IOException("git " + String.join(" ", args) + " failed: " + output);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException(ex);
		}
		return output;
	}

}
