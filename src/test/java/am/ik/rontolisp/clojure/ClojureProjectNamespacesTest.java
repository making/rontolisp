package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
 * Programs split across files: an entry file whose {@code require}s load project
 * namespaces from its source path (the root its own namespace names, then the
 * {@code deps.edn} {@code :paths}), on the interpreter, the JVM and both WASM backends --
 * the namespace files lower ahead of the entry, so every backend runs one program. Every
 * expected line is the oracle's ({@code clj} 1.12.6, run on the same files with
 * {@code -Sdeps '{:paths ["src" "test"]}'}).
 */
class ClojureProjectNamespacesTest {

	@TempDir
	static Path project;

	private static final Map<String, String> FILES = Map.of("deps.edn", "{:paths [\"src\"]}\n", "src/app/greet.clj", """
			(ns app.greet)
			(println "loading app.greet")
			(def greeting "hello")
			(defn- shout [s] (.toUpperCase s))
			(defn greet [n] (str greeting ", " n))
			(defn loud [n] (shout (greet n)))
			(defmacro twice [x] `(* 2 ~x))
			(defonce counter (atom 0))
			""", "src/app/util.clj", """
			(ns app.util (:require [app.greet :as g]))
			(println "loading app.util")
			(defn twice-greet [n] [(g/greet n) (g/twice 3)])
			""", "src/app/nons.clj", """
			(defn from-nons [] :into-the-requiring-ns)
			""", "src/app/inbox.clj", """
			(ns app.inbox)
			(defrecord Note [from text])
			(def notes (ref ()))
			(defn valid? [n] (boolean (and (:from n) (:text n))))
			(defn post [n] (dosync (alter notes conj n)))
			""", "src/app/cyc_a.clj", """
			(ns app.cyc-a (:require [app.cyc-b]))
			""", "src/app/cyc_b.clj", """
			(ns app.cyc-b (:require [app.cyc-a]))
			""");

	/**
	 * The entry under {@code test/}, the oracle's test layout: its namespace names the
	 * {@code test} root, the {@code deps.edn} the {@code src} one. A second
	 * {@code require} loads nothing, {@code use} refers what {@code :only} names, a file
	 * without an {@code ns} defines into the requiring namespace.
	 */
	private static final String MAIN = """
			(ns app.main-test
			  (:require [app.greet :as g :refer [greet]]
			            [app.util :refer :all]))
			(require 'app.greet)
			(use '[app.greet :only [loud]])
			(require 'app.nons)
			(println (greet "a") (g/loud "b") (loud "c"))
			(println (twice-greet "d"))
			(println (map g/greet ["e"]) (app.greet/greet "f"))
			(println (from-nons))
			(swap! g/counter inc)
			(println @app.greet/counter)
			""";

	private static final String MAIN_OUT = """
			loading app.greet
			loading app.util
			hello, a HELLO, B HELLO, C
			[hello, d 6]
			(hello, e) hello, f
			:into-the-requiring-ns
			1
			""";

	/**
	 * A test namespace whose tests are named like the functions under test, the corpus's
	 * chat shape: each namespace has its own vars, so {@code post} the test and
	 * {@code i/post} the function stay apart.
	 */
	private static final String INBOX_TEST = """
			(ns app.inbox-test
			  (:use clojure.test)
			  (:require [app.inbox :as i]))
			(deftest post
			  (dosync (ref-set i/notes ()))
			  (i/post (i/->Note "ann" "hi"))
			  (is (= [#app.inbox.Note{:from "ann" :text "hi"}] @i/notes)))
			(deftest valid?
			  (is (i/valid? {:from "a" :text "b"}))
			  (is (not (i/valid? {}))))
			(run-tests)
			""";

	private static final String INBOX_OUT = """

			Testing app.inbox-test

			Ran 2 tests containing 3 assertions.
			0 failures, 0 errors.
			""";

	@BeforeAll
	static void writeProject() throws IOException {
		for (Map.Entry<String, String> file : FILES.entrySet()) {
			Path target = project.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.writeString(target, file.getValue());
		}
	}

	@Test
	void anEntryLoadsItsNamespacesOnTheInterpreterAndTheJvm() throws Exception {
		Path entry = entry("main_test.clj", MAIN);
		assertThat(interpret(entry)).isEqualTo(MAIN_OUT);
		assertThat(runOnJvm(entry, "B56Main")).isEqualTo(MAIN_OUT);
	}

	@Test
	void anEntryLoadsItsNamespacesOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path entry = entry("main_test.clj", MAIN);
		assertThat(runOnWasm(entry, false)).isEqualTo(MAIN_OUT);
		assertThat(runOnWasm(entry, true)).isEqualTo(MAIN_OUT);
	}

	@Test
	void testsNamedLikeTheFunctionsUnderTestStayApart() throws Exception {
		Path entry = entry("inbox_test.clj", INBOX_TEST);
		assertThat(interpret(entry)).isEqualTo(INBOX_OUT);
		assertThat(runOnJvm(entry, "B56Inbox")).isEqualTo(INBOX_OUT);
		if (HostWasmtime.isAvailable()) {
			assertThat(runOnWasm(entry, false)).isEqualTo(INBOX_OUT);
		}
	}

	@Test
	void aMissingNamespaceFileIsNamedWithTheRootsSearched() throws Exception {
		Path entry = entry("missing_test.clj", "(ns app.missing-test (:require [app.nowhere]))");
		assertThatThrownBy(() -> read(entry)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Could not locate app/nowhere.clj on the source path: ")
			.hasMessageContaining(project.resolve("test").toString())
			.hasMessageContaining(project.resolve("src").toString());
	}

	@Test
	void theOraclesRefusalsKeepItsWords() throws Exception {
		assertThatThrownBy(() -> read(entry("p1.clj", "(require '[app.greet :as g]) (g/shout \"x\")")))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("var: #'app.greet/shout is not public");
		assertThatThrownBy(() -> read(entry("p2.clj", "(require '[app.greet :as g]) (g/nope 1)")))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such var: g/nope");
		assertThatThrownBy(() -> read(entry("p3.clj", "(require '[app.greet :refer [nope]])")))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("nope does not exist");
		assertThatThrownBy(() -> read(entry("p4.clj", "(require '[app.greet :refer [shout]])")))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("shout is not public");
		assertThatThrownBy(() -> read(entry("p5.clj", "(require '[app.nons :as n])")))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("namespace 'app.nons' not found after loading '/app/nons'");
		assertThatThrownBy(() -> read(entry("p6.clj", "(require 'app.cyc-a)"))).isInstanceOf(LispReadException.class)
			.hasMessageContaining("cyc_b.clj:1:1: Cyclic load dependency: [ /app/cyc_a ]->/app/cyc_b->[ /app/cyc_a ]");
	}

	@Test
	void useReferredEveryPublicVarAndNoPrivateOne() throws Exception {
		assertThat(interpret(entry("p7.clj", "(use 'app.greet) (println (loud \"x\") greeting)")))
			.isEqualTo("loading app.greet\nHELLO, X hello\n");
		assertThatThrownBy(() -> read(entry("p8.clj", "(use 'app.greet) (shout \"x\")")))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: shout");
	}

	private static Path entry(String name, String source) throws IOException {
		Path path = project.resolve("test").resolve("app").resolve(name);
		Files.createDirectories(path.getParent());
		Files.writeString(path, source);
		return path;
	}

	private static List<LispVal> read(Path entry) throws IOException {
		return SourceLanguage.CLOJURE.read(Files.readString(entry), Features.INTERPRETER, entry.toString(),
				SourceStandards.DEFAULT, SourceLoader.fileSystem());
	}

	private static String interpret(Path entry) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-namespaces", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : read(entry)) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnJvm(Path entry, String name) throws Exception {
		JvmSourceCompiler.Result result = new JvmSourceCompiler(name).sourceLanguage("clojure")
			.compile(Files.readString(entry), entry.toString());
		Path classes = Files.createTempDirectory(project, name);
		Files.write(classes.resolve(name + ".class"), result.classBytes());
		for (var file : result.runtimeClasses().entrySet()) {
			Path target = classes.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (var loader = new java.net.URLClassLoader(new java.net.URL[] { classes.toUri().toURL() },
				ClassLoader.getSystemClassLoader()); var _ = ThreadStdio.out(out)) {
			CliStack.call("clojure-namespaces", () -> {
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
		Path path = Files.createTempFile(project, "b56", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-W", "gc=y", "-W",
				"exceptions=y", path.toString());
		assertThat(run.exitCode()).as("wasmtime exit code; stderr: %s", run.stderr()).isZero();
		return run.stdout();
	}

}
