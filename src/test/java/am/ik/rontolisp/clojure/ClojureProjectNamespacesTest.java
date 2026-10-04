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
import java.util.Objects;

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

	private static final Map<String, String> FILES = Map.ofEntries(Map.entry("deps.edn", "{:paths [\"src\"]}\n"),
			Map.entry("src/app/greet.clj", """
					(ns app.greet)
					(println "loading app.greet")
					(def greeting "hello")
					(defn- shout [s] (.toUpperCase s))
					(defn greet [n] (str greeting ", " n))
					(defn loud [n] (shout (greet n)))
					(defmacro twice [x] `(* 2 ~x))
					(defonce counter (atom 0))
					"""), Map.entry("src/app/util.clj", """
					(ns app.util (:require [app.greet :as g]))
					(println "loading app.util")
					(defn twice-greet [n] [(g/greet n) (g/twice 3)])
					"""), Map.entry("src/app/nons.clj", """
					(defn from-nons [] :into-the-requiring-ns)
					"""), Map.entry("src/app/inbox.clj", """
					(ns app.inbox)
					(defrecord Note [from text])
					(def notes (ref ()))
					(defn valid? [n] (boolean (and (:from n) (:text n))))
					(defn post [n] (dosync (alter notes conj n)))
					"""), Map.entry("src/app/cyc_a.clj", """
					(ns app.cyc-a (:require [app.cyc-b]))
					"""), Map.entry("src/app/cyc_b.clj", """
					(ns app.cyc-b (:require [app.cyc-a]))
					"""), Map.entry("src/examples/preface.clj", """
					(ns examples.preface)
					(println "hello")
					"""), Map.entry("src/examples/sequences.clj", """
					(ns examples.sequences
					    (:require [clojure.set :refer :all]))
					(def composers
					  #{{:composer "J. S. Bach" :country "Germany"}
					    {:composer "W. A. Mozart" :country "Austria"}
					    {:composer "Giuseppe Verdi" :country "Italy"}})
					(def nations
					  #{{:nation "Germany" :language "German"}
					    {:nation "Austria" :language "German"}
					    {:nation "Italy" :language "Italian"}})
					(def languages #{"java" "c" "d" "clojure"})
					(def beverages #{"java" "chai" "pop"})
					"""), Map.entry("test/examples/test/sequences.clj", """
					(ns examples.test.sequences
					  (:import java.io.File)
					  (:use clojure.test clojure.set examples.sequences))
					(deftest test-sets
					  (are [x y] (= x y)
					   (union languages beverages) #{"java" "c" "d" "clojure" "chai" "pop"}
					   (difference languages beverages) #{"c" "d" "clojure"}
					   (intersection languages beverages) #{"java"}
					   (select #(= 1 (count %)) languages) #{"c" "d"}))
					(deftest test-joins
					  (are [x y] (= x y)
					   (join composers nations {:country :nation})
					   #{{:language "German", :nation "Austria", :composer "W. A. Mozart", :country "Austria"}
					     {:language "German", :nation "Germany", :composer "J. S. Bach", :country "Germany"}
					     {:language "Italian", :nation "Italy", :composer "Giuseppe Verdi", :country "Italy"}}))
					"""), Map.entry("src/examples/functional.clj", """
					(ns examples.functional)
					(defn stack-consuming-fibo [n]
					  (cond
					   (= n 0) 0
					   (= n 1) 1
					   :else (+ (stack-consuming-fibo (- n 1))
					            (stack-consuming-fibo (- n 2)))))
					(defn tail-fibo [n]
					  (letfn [(fib [current next n]
					            (if (zero? n)
					              current
					              (fib next (+ current next) (dec n))))]
					    (fib 0N 1N n)))
					(defn recur-fibo [n]
					  (letfn [(fib [current next n]
					            (if (zero? n)
					              current
					              (recur next (+ current next) (dec n))))]
					    (fib 0N 1N n)))
					(defn fibo []
					  (map first (iterate (fn [[a b]] [b (+ a b)]) [0N 1N])))
					(def head-fibo (lazy-cat [0N 1N] (map + head-fibo (rest head-fibo))))
					(defn faux-curry [& args] (apply partial partial args))
					"""), Map.entry("test/examples/test/functional.clj", """
					(ns examples.test.functional
					  (:use clojure.test
					        examples.functional))
					(def ten-fibs [0 1 1 2 3 5 8 13 21 34])
					(deftest test-stack-crushing-fibo
					  (is (= ten-fibs (map stack-consuming-fibo (range 0 10))))
					  (is (thrown? StackOverflowError (stack-consuming-fibo 1000000N))))
					(deftest test-tail-fibo
					  (is (= ten-fibs (map tail-fibo (range 0 10)))))
					(deftest test-recur-fibo
					  (is (= ten-fibs (map recur-fibo (range 0 10)))))
					(deftest test-fibo
					  (is (= ten-fibs (take 10 (fibo)))))
					(deftest test-head-fibo
					  (is (= ten-fibs (take 10 head-fibo))))
					(deftest test-faux-curry
					  (is (fn? (faux-curry + 1)))
					  (is (fn? ((faux-curry + 1) 1)))
					  (is (= 2 (((faux-curry + 1) 1)))))
					"""), Map.entry("test/examples/test/preface.clj", """
					(ns examples.test.preface
					  (:use clojure.test))
					(deftest test-load-preface
					  (is (= "hello\\n" (with-out-str (use :reload 'examples.preface)))))
					"""), Map.entry("src/app/counter_ns.clj", """
					(ns app.counter-ns)
					(defonce calls (atom 0))
					(def snapshot @calls)
					(defn touch [] (swap! calls inc))
					"""), Map.entry("src/app/dep_b.clj", """
					(ns app.dep-b)
					(println "loading b")
					(def bv 1)
					"""), Map.entry("src/app/dep_a.clj", """
					(ns app.dep-a (:require [app.dep-b :as b]))
					(println "loading a")
					(def av (+ 10 b/bv))
					"""), Map.entry("src/app/late.clj", """
					(ns app.late)
					(println "loading late")
					(defn g [] :late!)
					"""), Map.entry("src/app/dyn.clj", """
					(ns app.dyn)
					(def ^:dynamic *level* 0)
					(defn level [] *level*)
					"""), Map.entry("src/app/shared.clj", """
					(ns app.shared)
					(println "loading shared")
					(defn s [] :shared!)
					"""), Map.entry("src/app/where.clj", """
					(ns app.where)
					(prn :loading (str *ns*) *file* *source-path*)
					(defn here [] [(str *ns*) *file* *source-path*])
					(in-ns 'app.elsewhere)
					(clojure.core/prn :switched (clojure.core/str clojure.core/*ns*))
					"""), Map.entry("test/app/a.clj", """
					(ns app.a (:require [app.shared :as sh]))
					(println (sh/s) :from-a)
					"""), Map.entry("test/app/b.clj", """
					(ns app.b (:require [app.shared :as sh]))
					(println (sh/s) :from-b)
					"""));

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
		assertThat(runOnJvm(entry, "ProjMain")).isEqualTo(MAIN_OUT);
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
		assertThat(runOnJvm(entry, "ProjInbox")).isEqualTo(INBOX_OUT);
		if (HostWasmtime.isAvailable()) {
			assertThat(runOnWasm(entry, false)).isEqualTo(INBOX_OUT);
		}
	}

	/**
	 * {@code *ns*}, {@code *file*} and {@code *source-path*} are bound while a file
	 * loads: a required namespace's file reads its own namespace and its root-relative
	 * path, its {@code in-ns} switches only there, and the entry reads its own again
	 * afterwards; a function reads the values of whoever calls it, like the oracle.
	 */
	private static final String WHERE = """
			(ns app.where-test (:require [app.where :as w]))
			(prn (str *ns*) *file* *source-path*)
			(prn (w/here))
			(in-ns 'user)
			(prn (app.where/here))
			""";

	private static String whereOut(Path entry) {
		String file = "\"" + entry + "\" \"where_test.clj\"";
		return """
				:loading "app.where" "app/where.clj" "where.clj"
				:switched "app.elsewhere"
				"app.where-test" %1$s
				["app.where-test" %1$s]
				["user" %1$s]
				""".formatted(file);
	}

	@Test
	void aLoadingFileReadsItsOwnNamespaceAndPathOnTheInterpreterAndTheJvm() throws Exception {
		Path entry = entry("where_test.clj", WHERE);
		assertThat(interpret(entry)).isEqualTo(whereOut(entry));
		assertThat(runOnJvm(entry, "ProjWhere")).isEqualTo(whereOut(entry));
	}

	@Test
	void aLoadingFileReadsItsOwnNamespaceAndPathOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path entry = entry("where_test.clj", WHERE);
		assertThat(runOnWasm(entry, false)).isEqualTo(whereOut(entry));
		assertThat(runOnWasm(entry, true)).isEqualTo(whereOut(entry));
	}

	/**
	 * The corpus preface shape: the test namespace never requires the printing one; the
	 * test body's own {@code (use :reload ...)} runs its file, so the print lands inside
	 * the capture, like the oracle.
	 */
	private static final String PREFACE_DRIVER = """
			(ns preface-driver (:use clojure.test))
			(require 'examples.test.preface)
			(run-tests 'examples.test.preface)
			""";

	private static final String PREFACE_OUT = """

			Testing examples.test.preface

			Ran 1 tests containing 1 assertions.
			0 failures, 0 errors.
			""";

	@Test
	void thePrefaceShapeLoadsWhenTheRequireRuns() throws Exception {
		Path entry = project.resolve("test").resolve("preface_driver.clj");
		Files.writeString(entry, PREFACE_DRIVER);
		assertThat(interpret(entry)).isEqualTo(PREFACE_OUT);
		assertThat(runOnJvm(entry, "Preface")).isEqualTo(PREFACE_OUT);
	}

	@Test
	void thePrefaceShapeLoadsWhenTheRequireRunsOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path entry = project.resolve("test").resolve("preface_driver.clj");
		Files.writeString(entry, PREFACE_DRIVER);
		assertThat(runOnWasm(entry, false)).isEqualTo(PREFACE_OUT);
		assertThat(runOnWasm(entry, true)).isEqualTo(PREFACE_OUT);
	}

	/**
	 * The corpus {@code examples.test.sequences} {@code test-sets} / {@code test-joins}
	 * shape: {@code clojure.set} referred in full by {@code :refer :all} and by
	 * {@code :use}, over the source namespace's relations (the corpus file's
	 * {@code clojure.xml}, {@code file-seq} and {@code examples.utils} legs left out; the
	 * {@code .length} interop spelled {@code count}, so no leg compiles a host call).
	 */
	private static final String SETS_DRIVER = """
			(ns corpus.sets-driver (:use clojure.test))
			(require 'examples.test.sequences)
			(run-tests 'examples.test.sequences)
			""";

	private static final String SETS_OUT = """

			Testing examples.test.sequences

			Ran 2 tests containing 5 assertions.
			0 failures, 0 errors.
			""";

	@Test
	void theCorpusSetTestsRunOnTheInterpreterAndTheJvm() throws Exception {
		Path entry = project.resolve("test").resolve("sets_driver.clj");
		Files.writeString(entry, SETS_DRIVER);
		assertThat(interpret(entry)).isEqualTo(SETS_OUT);
		assertThat(runOnJvm(entry, "CorpusSets")).isEqualTo(SETS_OUT);
	}

	@Test
	void theCorpusSetTestsRunOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path entry = project.resolve("test").resolve("sets_driver_wasm.clj");
		Files.writeString(entry, SETS_DRIVER);
		assertThat(runOnWasm(entry, false)).isEqualTo(SETS_OUT);
		assertThat(runOnWasm(entry, true)).isEqualTo(SETS_OUT);
	}

	/**
	 * The corpus {@code examples.test.functional}: a {@code thrown?} assertion over a
	 * million-deep non-tail call. The oracle catches the {@code StackOverflowError} and
	 * runs every assertion. The JVM backend's landing catches any {@code Throwable}, so
	 * it answers the oracle's summary; the interpreter's overflow is no condition and
	 * ends the program (the CLI reports it, {@code RontoLispCliStreamsTest}); a WASM
	 * stack exhaustion is a trap. The shapes around the assertion run on every backend
	 * ({@code clojure-spec.yaml}, {@code functional-shapes-match-the-oracle}). The book's
	 * second one, {@code (thrown? StackOverflowError (tail-fibo 1000000N))}, is left out
	 * of the copy: its overflow is the oracle keeping a frame per named self call, where
	 * every backend here runs a self tail call in constant stack (the JVM's jump,
	 * {@code .kb/jvm-self-tail-calls.md}) and would compute the millionth Fibonacci
	 * number instead -- a documented deviation ({@code doc/en/clojure/deviations.md}).
	 */
	private static final String FUNCTIONAL_DRIVER = """
			(ns corpus.functional-driver (:use clojure.test))
			(require 'examples.test.functional)
			(run-tests 'examples.test.functional)
			""";

	private static final String FUNCTIONAL_OUT = """

			Testing examples.test.functional

			Ran 6 tests containing 9 assertions.
			0 failures, 0 errors.
			""";

	@Test
	void aDeepNonTailCallInAThrownAssertionRunsTheCatchOnTheJvm() throws Exception {
		Path entry = project.resolve("test").resolve("functional_driver_jvm.clj");
		Files.writeString(entry, FUNCTIONAL_DRIVER);
		assertThat(runOnJvm(entry, "CorpusFunctional")).isEqualTo(FUNCTIONAL_OUT);
	}

	@Test
	void aDeepNonTailCallInAThrownAssertionEndsTheInterpretedProgram() throws Exception {
		Path entry = project.resolve("test").resolve("functional_driver_interp.clj");
		Files.writeString(entry, FUNCTIONAL_DRIVER);
		assertThatThrownBy(() -> interpret(entry)).isInstanceOf(StackOverflowError.class);
	}

	@Test
	void aDeepNonTailCallInAThrownAssertionTrapsOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path entry = project.resolve("test").resolve("functional_driver_wasm.clj");
		Files.writeString(entry, FUNCTIONAL_DRIVER);
		for (boolean component : new boolean[] { false, true }) {
			HostWasmtime.ExecResult run = wasmRun(
					CompileFrontendAccess.clojure(Files.readString(entry), entry.toString(), true, component),
					component);
			assertThat(run.exitCode()).as("component=%s", component).isNotZero();
			assertThat(run.stdout()).as("component=%s", component).isEqualTo("\nTesting examples.test.functional\n");
			assertThat(run.stderr()).as("component=%s", component).contains("call stack exhausted");
		}
	}

	/**
	 * {@code :reload} re-runs the namespace: the {@code def} resets to the current root,
	 * the {@code defonce} keeps it, like the oracle.
	 */
	private static final String RELOAD_MAIN = """
			(ns app.reload-test (:require [app.counter-ns :as c]))
			(c/touch) (c/touch)
			(println @c/calls c/snapshot)
			(require '[app.counter-ns] :reload)
			(println @c/calls c/snapshot)
			(c/touch)
			(println @c/calls c/snapshot)
			""";

	private static final String RELOAD_OUT = """
			2 0
			2 2
			3 2
			""";

	@Test
	void reloadRerunsTheNamespaceKeepingDefonce() throws Exception {
		Path entry = entry("reload_test.clj", RELOAD_MAIN);
		assertThat(interpret(entry)).isEqualTo(RELOAD_OUT);
		assertThat(runOnJvm(entry, "Reload")).isEqualTo(RELOAD_OUT);
	}

	@Test
	void reloadRerunsTheNamespaceKeepingDefonceOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path entry = entry("reload_test.clj", RELOAD_MAIN);
		assertThat(runOnWasm(entry, false)).isEqualTo(RELOAD_OUT);
		assertThat(runOnWasm(entry, true)).isEqualTo(RELOAD_OUT);
	}

	/**
	 * {@code :reload-all} re-runs the namespace with every transitive dependency first,
	 * like the oracle.
	 */
	private static final String RELOAD_ALL_MAIN = """
			(ns app.dep-test (:require [app.dep-a :as a]))
			(println "first" a/av)
			(require '[app.dep-a] :reload-all)
			(println "second" a/av)
			""";

	private static final String RELOAD_ALL_OUT = """
			loading b
			loading a
			first 11
			loading b
			loading a
			second 11
			""";

	@Test
	void reloadAllRerunsDependenciesFirst() throws Exception {
		Path entry = entry("dep_test.clj", RELOAD_ALL_MAIN);
		assertThat(interpret(entry)).isEqualTo(RELOAD_ALL_OUT);
		assertThat(runOnJvm(entry, "ReloadAll")).isEqualTo(RELOAD_ALL_OUT);
	}

	@Test
	void reloadAllRerunsDependenciesFirstOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path entry = entry("dep_test.clj", RELOAD_ALL_MAIN);
		assertThat(runOnWasm(entry, false)).isEqualTo(RELOAD_ALL_OUT);
		assertThat(runOnWasm(entry, true)).isEqualTo(RELOAD_ALL_OUT);
	}

	/**
	 * A {@code require} inside a function body loads when the body runs: the load print
	 * comes after the earlier top-level print, not ahead of the program.
	 */
	private static final String LATE_MAIN = """
			(ns app.late-test)
			(defn f [] (require '[app.late :as l]) (l/g))
			(println "before")
			(println (f))
			""";

	private static final String LATE_OUT = """
			before
			loading late
			:late!
			""";

	@Test
	void aRequireInsideABodyLoadsWhenTheBodyRuns() throws Exception {
		Path entry = entry("late_test.clj", LATE_MAIN);
		assertThat(interpret(entry)).isEqualTo(LATE_OUT);
		assertThat(runOnJvm(entry, "Late")).isEqualTo(LATE_OUT);
	}

	@Test
	void aRequireInsideABodyLoadsWhenTheBodyRunsOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path entry = entry("late_test.clj", LATE_MAIN);
		assertThat(runOnWasm(entry, false)).isEqualTo(LATE_OUT);
		assertThat(runOnWasm(entry, true)).isEqualTo(LATE_OUT);
	}

	/**
	 * A `^:dynamic` var of a required namespace rebinds through `binding`: its declaim
	 * and counter ride top-level, so the rebinding has dynamic extent on every backend.
	 */
	private static final String DYN_MAIN = """
			(ns app.dyn-test (:require [app.dyn :as d]))
			(println (d/level))
			(binding [d/*level* 5] (println (d/level)))
			(println (d/level))
			""";

	private static final String DYN_OUT = """
			0
			5
			0
			""";

	@Test
	void aDynamicVarOfARequiredNamespaceRebinds() throws Exception {
		Path entry = entry("dyn_test.clj", DYN_MAIN);
		assertThat(interpret(entry)).isEqualTo(DYN_OUT);
		assertThat(runOnJvm(entry, "Dyn")).isEqualTo(DYN_OUT);
	}

	@Test
	void aDynamicVarOfARequiredNamespaceRebindsOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path entry = entry("dyn_test.clj", DYN_MAIN);
		assertThat(runOnWasm(entry, false)).isEqualTo(DYN_OUT);
		assertThat(runOnWasm(entry, true)).isEqualTo(DYN_OUT);
	}

	/**
	 * A namespace two separately lowered files require runs once: each {@code (load ...)}
	 * lowers on its own (its own loaded flag, already bound by the first), so the shared
	 * file's print appears a single time on every backend.
	 */
	private static final String TWICE_OUT = """
			loading shared
			:shared! :from-a
			:shared! :from-b
			""";

	@Test
	void aNamespaceTwoSeparatelyLoweredFilesRequireRunsOnce() throws Exception {
		Path entry = twiceEntry();
		assertThat(interpretLoads(entry)).isEqualTo(TWICE_OUT);
		assertThat(runLoadsOnJvm(entry, "Twice")).isEqualTo(TWICE_OUT);
	}

	@Test
	void aNamespaceTwoSeparatelyLoweredFilesRequireRunsOnceOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path entry = twiceEntry();
		assertThat(runLoadsOnWasm(entry, false)).isEqualTo(TWICE_OUT);
		assertThat(runLoadsOnWasm(entry, true)).isEqualTo(TWICE_OUT);
	}

	private static Path twiceEntry() throws IOException {
		Path path = project.resolve("test").resolve("app").resolve("twice_main.lisp");
		Files.writeString(path, "(load \"a.clj\")\n(load \"b.clj\")\n");
		return path;
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
		return execJvm(name, classes);
	}

	private static String runLoadsOnJvm(Path entry, String name) throws Exception {
		JvmSourceCompiler.Result result = new JvmSourceCompiler(name)
			.baseDir(Objects.requireNonNull(entry.getParent()).toString())
			.compile(Files.readString(entry), entry.toString());
		Path classes = Files.createTempDirectory(project, name);
		Files.write(classes.resolve(name + ".class"), result.classBytes());
		for (var file : result.runtimeClasses().entrySet()) {
			Path target = classes.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		return execJvm(name, classes);
	}

	private static String execJvm(String name, Path classes) throws Exception {
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

	private static String interpretLoads(Path entry) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-namespaces", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			evaluator.setLoadBaseDir(Objects.requireNonNull(entry.getParent()).toString());
			for (LispVal form : SourceLanguage.COMMON_LISP.read(Files.readString(entry), Features.INTERPRETER,
					entry.toString(), SourceStandards.DEFAULT, SourceLoader.fileSystem())) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnWasm(Path entry, boolean component) throws Exception {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(Files.readString(entry),
				entry.toString(), true, component);
		return execWasm(frontend, component);
	}

	private static String runLoadsOnWasm(Path entry, boolean component) throws Exception {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.commonLisp(Files.readString(entry),
				entry.toString(), Objects.requireNonNull(entry.getParent()).toString(), true, component);
		return execWasm(frontend, component);
	}

	private static String execWasm(CompileFrontendAccess.Program frontend, boolean component) throws Exception {
		HostWasmtime.ExecResult run = wasmRun(frontend, component);
		assertThat(run.exitCode()).as("wasmtime exit code; stderr: %s", run.stderr()).isZero();
		return run.stdout();
	}

	private static HostWasmtime.ExecResult wasmRun(CompileFrontendAccess.Program frontend, boolean component)
			throws Exception {
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		Path path = Files.createTempFile(project, "proj", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		return HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
				path.toString());
	}

}
