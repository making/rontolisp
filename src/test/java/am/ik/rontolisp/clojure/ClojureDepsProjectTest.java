package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.SourceStandards;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A {@code deps.edn} project on disk whose {@code :deps} name {@code :local/root}
 * directories (one with a {@code :local/root} of its own), a {@code :local/root} jar read
 * in place, a {@code clojure.*} namespace from a dependency, and the built-in
 * {@code org.clojure/clojure} at another version -- the same program on the interpreter,
 * the JVM and both WASM backends. The expected output is the oracle's ({@code clj -Srepro
 * -M src/app/main.clj}, {@code clj} 1.12.6, 2026-10-08), as are the refusals' words where
 * the oracle has them.
 */
class ClojureDepsProjectTest {

	@TempDir
	static Path dir;

	private static final String MAIN = """
			(ns app.main
			  (:require [util.core :as u]
			            [util.extra :as x]
			            [jarlib.core :as j]
			            [jarlib.portable :as p]
			            [clojure.data.simple :as s]))
			(println (u/greet "deps"))
			(println x/where (j/twice 21) j/file p/platform)
			(println (s/wrap 1))
			""";

	private static final String MAIN_OUT = """
			loading util.core
			hello, deps
			resources 42 jarlib/core.clj :jvm
			[:simple 1]
			""";

	@BeforeAll
	static void writeProject() throws IOException {
		write("app/deps.edn", """
				{:paths ["src"]
				 :deps {org.clojure/clojure {:mvn/version "1.11.1"}
				        my/util {:local/root "../util"}
				        my/jarlib {:local/root "../jarlib.jar"}
				        my/contrib {:local/root "../contrib"}}}
				""");
		write("app/src/app/main.clj", MAIN);
		write("util/deps.edn", """
				{:paths ["src" "resources"]
				 :deps {my/deep {:local/root "../deep"}}}
				""");
		write("util/src/util/core.clj", """
				(ns util.core (:require [deep.core :as d]))
				(println "loading util.core")
				(defn greet [n] (str (d/hello) ", " n))
				""");
		write("util/resources/util/extra.clj", "(ns util.extra)\n(def where \"resources\")\n");
		write("deep/deps.edn", "{}\n");
		write("deep/src/deep/core.clj", "(ns deep.core)\n(defn hello [] \"hello\")\n");
		Map<String, String> jar = new LinkedHashMap<>();
		jar.put("jarlib/core.clj", "(ns jarlib.core)\n(def file *file*)\n(defn twice [x] (* 2 x))\n");
		jar.put("jarlib/portable.cljc", "(ns jarlib.portable)\n(def platform #?(:clj :jvm :default :other))\n");
		jar.put("jarlib/aot__init.class", "not really a class");
		jar("jarlib.jar", jar);
		write("contrib/deps.edn", "{:paths [\"src\"]}\n");
		write("contrib/src/clojure/data/simple.clj", "(ns clojure.data.simple)\n(defn wrap [x] [:simple x])\n");
	}

	@Test
	void aProjectsDependenciesLoadOnTheInterpreterAndTheJvm() throws Exception {
		Path entry = dir.resolve("app/src/app/main.clj");
		assertThat(interpret(entry, SourceStandards.DEFAULT)).isEqualTo(MAIN_OUT);
		assertThat(runOnJvm(entry, "DepsMain")).isEqualTo(MAIN_OUT);
	}

	@Test
	void aProjectsDependenciesLoadOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path entry = dir.resolve("app/src/app/main.clj");
		assertThat(runOnWasm(entry, false)).isEqualTo(MAIN_OUT);
		assertThat(runOnWasm(entry, true)).isEqualTo(MAIN_OUT);
	}

	@Test
	void aNamespaceNoRootHoldsIsRefusedNamingWhatIsNotSearched() throws Exception {
		write("unfetched/deps.edn", """
				{:deps {org.clojure/data.json {:mvn/version "2.5.1"}
				        my/jarlib {:local/root "../withpom.jar"}
				        io.github.someone/lib {:git/sha "0123456789012345678901234567890123456789"}}}
				""");
		Map<String, String> jar = new LinkedHashMap<>();
		jar.put("META-INF/maven/my/withpom/pom.xml", "<project/>");
		jar.put("withpom/core.clj", "(ns withpom.core)\n");
		jar("withpom.jar", jar);
		// in source path order: io.github.someone/ < my/ < org.clojure/
		String notSearched = "; not searched: io.github.someone/lib https://github.com/someone/lib.git at 0123456 "
				+ "(a git coordinate, not fetched), the dependencies the pom.xml in " + real("withpom.jar")
				+ " declares (a jar's pom.xml is not read), org.clojure/data.json 2.5.1 (a Maven coordinate, not "
				+ "fetched)";
		String roots = dir.resolve("unfetched") + ", " + dir.resolve("unfetched/src") + ", " + real("withpom.jar");
		// a contrib clojure.* library is a library like any other
		Path json = write("unfetched/main.clj", "(require '[clojure.data.json :as json])\n");
		assertThatThrownBy(() -> read(json, SourceStandards.DEFAULT)).isInstanceOf(LispReadException.class)
			.hasMessage(json + ":1:1: Could not locate clojure/data/json.clj or clojure/data/json.cljc on the source "
					+ "path: " + roots + notSearched);
		Path missing = write("unfetched/missing.clj", "(require 'nowhere.at-all)\n");
		assertThatThrownBy(() -> read(missing, SourceStandards.DEFAULT)).isInstanceOf(LispReadException.class)
			.hasMessage(
					missing + ":1:1: Could not locate nowhere/at_all.clj or nowhere/at_all.cljc on the source path: "
							+ roots + notSearched);
		// what the jar holds still loads
		Path found = write("unfetched/found.clj", "(require 'withpom.core) (println :ok)\n");
		assertThat(interpret(found, SourceStandards.DEFAULT)).isEqualTo(":ok\n");
	}

	@Test
	void aClojureJarNamespaceThisFrontEndLacksStaysUnknown() throws Exception {
		Path inspector = write("app/src/inspector.clj", "(require 'clojure.inspector)\n");
		assertThatThrownBy(() -> read(inspector, SourceStandards.DEFAULT)).isInstanceOf(LispReadException.class)
			.hasMessage(inspector + ":1:1: unknown namespace: clojure.inspector");
	}

	@Test
	void aNamespaceCompiledAheadOfTimeWithoutItsSourceIsRefused() throws Exception {
		Path aot = write("app/src/aot.clj", "(require 'jarlib.aot)\n");
		assertThatThrownBy(() -> read(aot, SourceStandards.DEFAULT)).isInstanceOf(LispReadException.class)
			.hasMessage(aot + ":1:1: jarlib.aot is compiled ahead of time in " + real("jarlib.jar")
					+ " without its source, and only a namespace's source is read");
	}

	@Test
	void theBuiltInRingNamespacesStandInForAnOlderRingCoreOnly() throws Exception {
		String program = "(require '[ring.util.response :as r]) (println (:status (r/response \"hi\")))\n";
		for (String version : List.of("1.15.5", "1.9.0")) {
			write("ring-" + version + "/deps.edn", "{:deps {ring/ring-core {:mvn/version \"" + version + "\"}}}\n");
			Path main = write("ring-" + version + "/main.clj", program);
			assertThat(interpret(main, SourceStandards.DEFAULT)).isEqualTo("200\n");
		}
		write("ring-newer/deps.edn", "{:deps {ring/ring-core {:mvn/version \"99.0\"}}}\n");
		Path newer = write("ring-newer/main.clj", program);
		assertThatThrownBy(() -> read(newer, SourceStandards.DEFAULT)).isInstanceOf(LispReadException.class)
			.hasMessage(newer + ":1:1: ring.util.response: the deps.edn selects ring/ring-core 99.0, newer than the "
					+ "built-in ring-core 1.15.5");
	}

	@Test
	void theUserLevelDepsEdnTheStandardsLocateIsMerged() throws Exception {
		write("user-config/deps.edn", "{:deps {my/deep {:local/root \"" + dir.resolve("deep") + "\"}}}\n");
		Path main = write("user-project/main.clj", "(require '[deep.core :as d]) (println (d/hello))\n");
		SourceStandards standards = SourceStandards.DEFAULT.withClojureConfigDir(dir.resolve("user-config").toString());
		assertThat(interpret(main, standards)).isEqualTo("hello\n");
		assertThatThrownBy(() -> read(main, SourceStandards.DEFAULT)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Could not locate deep/core.clj or deep/core.cljc on the source path");
	}

	@Test
	void theUserLevelDirectoryIsTheOraclesChoice() {
		assertThat(SourceStandards.clojureConfigDir("/c", "/x", "/h")).isEqualTo("/c");
		assertThat(SourceStandards.clojureConfigDir("", "/x", "/h"))
			.isEqualTo("/x" + java.io.File.separator + "clojure");
		assertThat(SourceStandards.clojureConfigDir(null, null, "/h"))
			.isEqualTo("/h" + java.io.File.separator + ".clojure");
		assertThat(SourceStandards.clojureConfigDir(null, null, null)).isNull();
	}

	@Test
	void aSessionLoadsFromTheWorkingDirectorysDependencies() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("deps.edn", "{:deps {my/lib {:local/root \"lib\"}}}");
		files.put("lib/deps.edn", "{}");
		files.put("lib/src/lib/core.clj", "(ns lib.core) (defn f [x] (inc x))");
		ClojureSession session = Clojure.session(new MemoryClojureFiles(files));
		session.setMacroEvaluator(am.ik.rontolisp.eval.ClojureMacroTime.create());
		assertThat(session.read("(require '[lib.core :as l])")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList()).anyMatch(form -> form.contains("(DEFUN |c%lib.core/f| (|c%x|) (+ |c%x| 1))"));
	}

	private static String real(String name) throws IOException {
		return dir.resolve(name).toRealPath().toString();
	}

	private static Path write(String name, String text) throws IOException {
		Path path = dir.resolve(name);
		Files.createDirectories(path.getParent());
		Files.writeString(path, text);
		return path;
	}

	private static void jar(String name, Map<String, String> entries) throws IOException {
		try (OutputStream out = Files.newOutputStream(dir.resolve(name));
				ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Map.Entry<String, String> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
	}

	private static List<LispVal> read(Path entry, SourceStandards standards) throws IOException {
		return SourceLanguage.CLOJURE.read(Files.readString(entry), Features.INTERPRETER, entry.toString(), standards,
				SourceLoader.fileSystem());
	}

	private static String interpret(Path entry, SourceStandards standards) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-deps", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : read(entry, standards)) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnJvm(Path entry, String name) throws Exception {
		JvmSourceCompiler.Result result = new JvmSourceCompiler(name).sourceLanguage("clojure")
			.compile(Files.readString(entry), entry.toString());
		Path classes = Files.createTempDirectory(dir, name);
		Files.write(classes.resolve(name + ".class"), result.classBytes());
		for (Map.Entry<String, byte[]> file : result.runtimeClasses().entrySet()) {
			Path target = classes.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
				new java.net.URL[] { classes.toUri().toURL() }, ClassLoader.getSystemClassLoader());
				ThreadStdio.Scope redirected = ThreadStdio.out(out)) {
			CliStack.call("clojure-deps", () -> {
				Method main = loader.loadClass(name).getMethod("main", String[].class);
				main.invoke(null, (Object) new String[0]);
				return null;
			});
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnWasm(Path entry, boolean component) throws Exception {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(Files.readString(entry),
				entry.toString(), true, component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		Path path = Files.createTempFile(dir, "deps", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-W", "gc=y", "-W",
				"exceptions=y", path.toString());
		assertThat(run.exitCode()).as("wasmtime exit code; stderr: %s", run.stderr()).isZero();
		return run.stdout();
	}

}
