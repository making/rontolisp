package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.SourceSession;
import am.ik.rontolisp.eval.SourceStandards;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.testsupport.HostWasmtime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code rontolisp.wit} of the Clojure front end: {@code import} binds a WIT interface's
 * functions as the vars of a namespace an alias reaches, {@code export} implements a WIT
 * world with the program's vars, {@code provide} binds an imported interface's
 * implementation where the program provides it. Each LOWERS to the Common Lisp directive
 * through its {@code :names} naming hook, so every backend binds the interface the way it
 * binds a Common Lisp program's: a provider on the interpreter and the JVM, a Preview 1
 * import block, a canonical-ABI component import -- the Preview 1 block byte for byte the
 * {@code rontolisp.wasm/defimport} one.
 */
class ClojureWitBoundaryTest {

	@TempDir
	Path dir;

	/** wasi:keyvalue's store, the shape wasmtime's in-memory provider answers. */
	private static final String KEYVALUE = """
			package wasi:keyvalue@0.2.0-draft;

			interface store {
			  variant error {
			    no-such-store,
			    access-denied,
			    other(string)
			  }

			  open: func(identifier: string) -> result<bucket, error>;

			  resource bucket {
			    get: func(key: string) -> result<option<list<u8>>, error>;
			    set: func(key: string, value: list<u8>) -> result<_, error>;
			  }
			}
			""";

	private static final String KV_PROGRAM = """
			(ns kv-demo
			  (:require [rontolisp.wit :as wit]))

			(wit/import "kv.wit" {:interface "wasi:keyvalue/store@0.2.0-draft" :as kv})

			(let [bucket (kv/open "")
			      seen (kv/bucket-get bucket "hits")]
			  (println "seen" (pr-str seen))
			  (kv/bucket-set bucket "hits" "42")
			  (println "now" (pr-str (kv/bucket-get bucket "hits"))))
			""";

	private Path write(String name, String text) throws Exception {
		Path file = this.dir.resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}

	private List<String> wasmForms(Path program) throws Exception {
		return SourceLanguage.CLOJURE
			.read(Files.readString(program), Features.WASM, program.toString(), SourceStandards.DEFAULT,
					SourceLoader.fileSystem())
			.stream()
			.map(LispVal::print)
			.toList();
	}

	@Test
	void anImportLowersToTheDirectiveNamingEachMembersVar() throws Exception {
		write("kv.wit", KEYVALUE);
		Path program = write("kv.clj", KV_PROGRAM);
		String ns = "c%wasi%ckeyvalue.store@0.2.0-draft/";
		// the path is the program's own spelling, which every later pass resolves against
		// the entry's directory; every member that crosses into Clojure is named, the
		// drop too (bound only where the program names it, like a Common Lisp drop)
		assertThat(wasmForms(program))
			.contains("(RONTOLISP:WIT-IMPORT \"kv.wit\" :INTERFACE "
					+ "\"wasi:keyvalue/store@0.2.0-draft\" :NAMES ((\"open\" \"" + ns + "open\") (\"bucket-get\" \""
					+ ns + "bucket-get\") (\"bucket-set\" \"" + ns + "bucket-set\") (\"bucket-drop\" \"" + ns
					+ "bucket-drop\")))")
			.anyMatch(form -> form.contains("(|" + ns + "bucket-set| |c%bucket| \"hits\" \"42\")"));
	}

	/**
	 * Under {@code --component} the import is wasmtime's real {@code wasi:keyvalue}: the
	 * value the host seeded comes back through the canonical ABI, and the one written is
	 * read again -- which no process-local table could fake.
	 */
	@Test
	void aComponentImportsWasmtimesKeyvalueStore() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no wasmtime " + HostWasmtime.MINIMUM_MAJOR + "+ on PATH");
		write("kv.wit", KEYVALUE);
		Path program = write("kv.clj", KV_PROGRAM);
		Path module = this.dir.resolve("kv.wasm");
		HostBoundaryRuns.cli(program.toString(), "-o", module.toString(), "--component");
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-S", "keyvalue=y", "-S",
				"keyvalue-in-memory-data=hits=41", module.toString());
		assertThat(run.exitCode()).as("wasmtime: %s", run.stderr()).isZero();
		assertThat(run.stdout()).isEqualTo("seen \"41\"\nnow \"42\"\n");
	}

	/**
	 * On the interpreter and the JVM the program provides the interface itself: a Clojure
	 * function of the member name and its arguments, bound under the interface as an
	 * import wrote it (the bindings dispatch on the canonical id, which the versionless
	 * spelling is resolved to), and the bound vars are functions -- a value, a
	 * {@code #'}, a refer.
	 */
	@Test
	void aClojureProviderAnswersOnTheInterpreterAndTheJvm() throws Exception {
		write("kv.wit", KEYVALUE);
		Path program = write("provided.clj", """
				(ns kv-provided
				  (:require [rontolisp.wit :as wit]))

				(wit/import "kv.wit" {:interface "wasi:keyvalue/store" :as kv :refer [open]})

				(def store (atom {"hits" "41"}))

				(println (wit/provide "wasi:keyvalue/store"
				                      (fn [member & args]
				                        (cond
				                          (= member "open") 7
				                          (= member "bucket-get") (get @store (second args))
				                          (= member "bucket-set") (do (swap! store assoc (second args) (nth args 2))
				                                                      nil)))))

				(let [bucket (open "")]
				  (println "bucket" bucket "seen" (pr-str (kv/bucket-get bucket "hits")))
				  (kv/bucket-set bucket "hits" "42")
				  (println "now" (pr-str (kv/bucket-get bucket "hits"))))
				(println (map kv/bucket-get [7 7] ["hits" "nope"]))
				(println #'kv/open)
				""");
		String expected = """
				wasi:keyvalue/store@0.2.0-draft
				bucket 7 seen "41"
				now "42"
				(42 nil)
				#'wasi:keyvalue.store@0.2.0-draft/open
				""";
		assertThat(HostBoundaryRuns.cli(program.toString())).isEqualTo(expected);
		assertThat(HostBoundaryRuns.jvm(program, Files.createDirectories(this.dir.resolve("classes")), "KvProvided"))
			.isEqualTo(expected);
	}

	private static final String MATH_WIT = """
			package example:host@0.1.0;

			interface math {
			  add-ints: func(a: s32, b: s32) -> s32;
			}
			""";

	/**
	 * THE ACCEPTANCE TEST: on Preview 1 an import is byte for byte the
	 * {@code rontolisp.wasm/defimport} block declaring the same host function -- which is
	 * itself the hand-written {@code rontolisp:wasm-import}. The vars' names differ;
	 * nothing of them reaches the module.
	 */
	@Test
	void aPreview1ImportIsTheDefimportBlock() throws Exception {
		write("math.wit", MATH_WIT);
		Path fromWit = write("from-wit.clj", """
				(ns app
				  (:require [rontolisp.wit :as wit]))

				(wit/import "math.wit" {:interface "example:host/math@0.1.0" :as math})

				(defn add10 {:wasm/export {:params [:int] :returns :int}} [n] (math/add-ints n 10))
				""");
		Path byHand = write("by-hand.clj", """
				(ns app
				  (:require [rontolisp.wasm :as wasm]))

				(wasm/defimport add-ints {:from "math" :as "addInts" :params [:int :int] :returns :int})

				(defn add10 {:wasm/export {:params [:int] :returns :int}} [n] (add-ints n 10))
				""");
		for (List<String> flags : List.<List<String>>of(List.of(), List.of("--optimize=off"))) {
			byte[] module = compile(fromWit, flags);
			assertThat(module).as("flags %s", flags).isEqualTo(compile(byHand, flags));
			assertThat(new String(module, StandardCharsets.ISO_8859_1)).contains("addInts");
		}
	}

	private byte[] compile(Path program, List<String> flags) throws Exception {
		Path out = this.dir.resolve(program.getFileName() + "-" + flags.size() + ".wasm");
		List<String> args = new ArrayList<>(List.of(program.toString(), "-o", out.toString()));
		args.addAll(flags);
		HostBoundaryRuns.cli(args.toArray(String[]::new));
		return Files.readAllBytes(out);
	}

	/**
	 * A WIT {@code bool} crosses in Clojure's spelling on Preview 1, under node: a
	 * {@code false} argument reaches the host as false and a false answer comes back
	 * {@code false} -- through a wrapper emitted only for the members the program calls.
	 */
	@Test
	void aBoolMemberCrossesInClojuresSpellingUnderNode() throws Exception {
		assumeTrue(HostBoundaryRuns.nodeAvailable(), "node is not on PATH");
		write("math.wit", """
				package example:host@0.1.0;

				interface math {
				  add-ints: func(a: s32, b: s32) -> s32;
				  is-even: func(n: s32) -> bool;
				  both: func(a: bool, b: bool) -> bool;
				  unused-flag: func() -> bool;
				}
				""");
		Path program = write("bools.clj", """
				(ns app
				  (:require [rontolisp.wit :as wit]))

				(wit/import "math.wit" {:interface "example:host/math" :as math :refer [both]})

				(defn report {:wasm/export {:params [:int] :returns :string}} [n]
				  (str (math/add-ints n 1) " " (math/is-even n) " " (both true false) " " (both (math/is-even n) true)))
				""");
		assertThat(wasmForms(program)).anyMatch(form -> form.startsWith("(DEFUN |c%example%chost.math@0.1.0/both| "))
			.noneMatch(form -> form.startsWith("(DEFUN |c%example%chost.math@0.1.0/unused-flag| "));
		HostBoundaryRuns.cli(program.toString(), "-o", this.dir.resolve("bools.wasm").toString(), "--no-wasi",
				"--emit-js-glue");
		Path host = write("run.mjs", """
				import fs from 'fs';
				import { instantiate } from './bools.js';

				const module = new WebAssembly.Module(fs.readFileSync(new URL('./bools.wasm', import.meta.url)));
				const lisp = instantiate(module, {
				  math: {
				    addInts: (a, b) => a + b,
				    isEven: (n) => n % 2 === 0,
				    both: (a, b) => a && b,
				    unusedFlag: () => true,
				  },
				});
				console.log(lisp.report(4));
				console.log(lisp.report(3));
				""");
		assertThat(HostBoundaryRuns.node(this.dir, host.toString()))
			.isEqualTo("5 true false true\n4 false false false\n");
	}

	private static final String GREETER_WIT = """
			package local:greeter;

			world greeter {
			  export greet: func(name: string) -> string;
			  export is-short: func(name: string) -> bool;
			  export negate: func(flag: bool) -> bool;
			  export total: func(a: u32, b: u32) -> u32;
			}
			""";

	private static final String GREETER = """
			(ns greeter
			  (:require [rontolisp.wit :as wit]))

			(wit/export "greeter.wit")

			(defn greet [name] (str "Hello, " name "!"))
			(defn is-short [name] (< (count name) 4))
			(defn negate [flag] (not flag))
			(defn total ([a] a) ([a b] (+ a b)))
			""";

	/**
	 * A world the program implements, lifted as a component: each label a var of the
	 * declaring namespace (defined below the declaration, which a file may), a
	 * {@code bool} converted both ways, a multi-arity {@code defn} through a wrapper --
	 * and {@code --emit-wit} answers the world it was handed, parameter names included.
	 */
	@Test
	void aWorldIsImplementedAsAComponent() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no wasmtime " + HostWasmtime.MINIMUM_MAJOR + "+ on PATH");
		write("greeter.wit", GREETER_WIT);
		Path program = write("greeter.clj", GREETER);
		Path module = this.dir.resolve("out/greeter.wasm");
		Files.createDirectories(module.getParent());
		HostBoundaryRuns.cli(program.toString(), "-o", module.toString(), "--component", "--emit-wit");
		assertThat(Files.readString(this.dir.resolve("out/greeter.wit")))
			.contains("  export greet: func(name: string) -> string;\n  export is-short: func(name: string) -> bool;\n"
					+ "  export negate: func(flag: bool) -> bool;\n  export total: func(a: u32, b: u32) -> u32;\n");
		StringBuilder answers = new StringBuilder();
		for (String call : List.of("greet(\"Ada\")", "is-short(\"Ada\")", "is-short(\"Grace\")", "negate(false)",
				"total(2, 40)")) {
			HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "--invoke", call,
					module.toString());
			assertThat(run.exitCode()).as("%s: %s", call, run.stderr()).isZero();
			answers.append(run.stdout().strip()).append('\n');
		}
		assertThat(answers.toString()).isEqualTo("\"Hello, Ada!\"\ntrue\nfalse\ntrue\n42\n");
	}

	/**
	 * A world of plain exports is byte for byte the component the hand-written
	 * {@code rontolisp:wasm-export} block lifts the same {@code defn}s with.
	 */
	@Test
	void aWorldIsTheHandWrittenExportBlock() throws Exception {
		write("plain.wit", """
				package local:plain;

				world plain {
				  export greet: func(name: string) -> string;
				  export total: func(a: u32, b: u32) -> u32;
				}
				""");
		String defns = "(defn greet [name] (str \"Hello, \" name \"!\"))\n(defn total [a b] (+ a b))\n";
		Path clojure = write("world.clj",
				"(ns plain\n  (:require [rontolisp.wit :as wit]))\n\n" + defns + "(wit/export \"plain.wit\")\n");
		write("core.clj", "(ns plain)\n\n" + defns);
		Path byHand = write("main.lisp",
				"""
						(load "core.clj")
						(rontolisp:wasm-export '|c%plain/greet| :as "greet" :params '(:string) :param-names '(|name|) :returns :string)
						(rontolisp:wasm-export '|c%plain/total| :as "total" :params '(:u32 :u32) :param-names '(|a| |b|) :returns :u32)
						""");
		for (List<String> flags : List.<List<String>>of(List.of("--component"), List.of())) {
			assertThat(compile(clojure, flags)).as("flags %s", flags).isEqualTo(compile(byHand, flags));
		}
	}

	/** {@code examples/clojure/greeter}: the world it implements, invoked. */
	@Test
	void theGreeterExampleAnswersItsWorld() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no wasmtime " + HostWasmtime.MINIMUM_MAJOR + "+ on PATH");
		Path module = this.dir.resolve("greeter.wasm");
		HostBoundaryRuns.cli(Path.of("examples/clojure/greeter/greeter.clj").toString(), "-o", module.toString(),
				"--component");
		StringBuilder answers = new StringBuilder();
		for (String call : List.of("greet(\"Ada\")", "shout(\"Ada\", true)", "shout(\"Ada\", false)",
				"is-short(\"Grace\")")) {
			HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "--invoke", call,
					module.toString());
			assertThat(run.exitCode()).as("%s: %s", call, run.stderr()).isZero();
			answers.append(run.stdout().strip()).append('\n');
		}
		assertThat(answers.toString()).isEqualTo("\"Hello, Ada!\"\n\"HELLO, ADA!!!\"\n\"HELLO, ADA!\"\nfalse\n");
	}

	/**
	 * {@code examples/wit/keyvalue/page-hits.clj}: the interpreter and the JVM against
	 * the store the program provides, the component against wasmtime's -- one output.
	 */
	@Test
	void thePageHitsExampleAnswersAlikeOnEveryBackend() throws Exception {
		Path program = Path.of("examples/wit/keyvalue/page-hits.clj");
		String expected = Files.readString(Path.of("examples/.expected/wit-keyvalue-page-hits-clj.txt"));
		assertThat(HostBoundaryRuns.cli(program.toString())).isEqualTo(expected);
		assertThat(HostBoundaryRuns.jvm(program, Files.createDirectories(this.dir.resolve("classes")), "PageHits"))
			.isEqualTo(expected);
		assumeTrue(HostWasmtime.isAvailable(), "no wasmtime " + HostWasmtime.MINIMUM_MAJOR + "+ on PATH");
		Path module = this.dir.resolve("page-hits.wasm");
		HostBoundaryRuns.cli(program.toString(), "-o", module.toString(), "--component");
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-S", "keyvalue=y",
				module.toString());
		assertThat(run.exitCode()).as(run.stderr()).isZero();
		assertThat(run.stdout()).isEqualTo(expected);
	}

	@Test
	void theInterpreterChecksTheWorldItImplements() throws Exception {
		write("greeter.wit", GREETER_WIT);
		Path program = write("greeter.clj", GREETER + "(println (greet \"x\") (total 1 2))\n");
		assertThat(HostBoundaryRuns.cli(program.toString())).isEqualTo("Hello, x! 3\n");
		Path drifted = write("drifted.clj", GREETER.replace("(defn negate [flag] (not flag))\n", ""));
		assertThatThrownBy(() -> HostBoundaryRuns.cli(drifted.toString()))
			.hasMessageContaining("rontolisp.wit/export (greeter.wit:6): negate names no var of greeter");
	}

	/**
	 * A WIT path is the importing file's, like {@code load}: an import in a required
	 * namespace finds the WIT beside that namespace's file, on every backend.
	 */
	@Test
	void anImportInARequiredNamespaceReadsTheWitBesideItsFile() throws Exception {
		write("deps.edn", "{:paths [\"src\"]}\n");
		write("src/app/kv.wit", KEYVALUE);
		write("src/app/store.clj", """
				(ns app.store
				  (:require [rontolisp.wit :as wit]))

				(wit/import "kv.wit" {:interface "wasi:keyvalue/store@0.2.0-draft" :as kv})

				(wit/provide "wasi:keyvalue/store@0.2.0-draft"
				             (fn [member & args] (if (= member "open") 1 (str member " " (second args)))))

				(defn hits [] (kv/bucket-get (kv/open "") "hits"))
				""");
		Path main = write("src/app/main.clj", """
				(ns app.main
				  (:require [app.store :as store]))

				(println (store/hits))
				""");
		assertThat(HostBoundaryRuns.cli(main.toString())).isEqualTo("bucket-get hits\n");
		assertThat(HostBoundaryRuns.jvm(main, Files.createDirectories(this.dir.resolve("classes")), "StoreMain"))
			.isEqualTo("bucket-get hits\n");
	}

	@Test
	void aSessionImportsInOneBufferAndCallsInTheNext() throws Exception {
		Path wit = write("kv.wit", KEYVALUE);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		SourceSession session = new SourceSession(SourceLanguage.CLOJURE, SourceStandards.DEFAULT,
				SourceLoader.fileSystem());
		for (String buffer : List.of("(require '[rontolisp.wit :as wit])",
				"(wit/import \"" + wit.toString().replace("\\", "\\\\")
						+ "\" {:interface \"wasi:keyvalue/store@0.2.0-draft\" :as kv})",
				"(wit/provide \"wasi:keyvalue/store@0.2.0-draft\" (fn [m & args] (if (= m \"open\") 1 (count args))))",
				"(println (kv/bucket-get (kv/open \"\") \"hits\"))")) {
			for (SourceSession.Step step : session.read(buffer, Features.INTERPRETER)) {
				for (LispVal form : step.forms()) {
					evaluator.eval(form);
				}
			}
		}
		assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("2\n");
	}

	private static final String GEO_WIT = """
			package example:geo@0.1.0;

			interface api {
			  record point { x: s32, y: s32 }
			  plot: func(p: point);
			  later: async func(n: u32) -> u32;
			  distance: func(a: s32, b: s32) -> s32;
			}
			""";

	@Test
	void aMemberOutsideTheClojureTierIsRefusedWhereTheProgramReachesIt() throws Exception {
		write("geo.wit", GEO_WIT);
		String head = "(ns app (:require [rontolisp.wit :as wit]))\n";
		String imported = head + "(wit/import \"geo.wit\" {:interface \"example:geo/api\" :as geo})\n";
		// the interface imports: what crosses is bound
		assertThat(wasmForms(write("ok.clj", imported + "(println (geo/distance 1 2))\n")))
			.anyMatch(form -> form.contains("(\"distance\" \"c%example%cgeo.api@0.1.0/distance\")"));
		assertRefused(imported + "(geo/plot 1)", "plot of example:geo/api@0.1.0 takes point (parameter 'p'), "
				+ "a record, which the Clojure tier does not carry yet (geo.wit:5)");
		assertRefused(imported + "(map geo/later [1])", "later of example:geo/api@0.1.0 is an async func: the future "
				+ "it answers does not map onto a Clojure future yet (geo.wit:6)");
		assertRefused(head + "(wit/import \"geo.wit\" {:interface \"example:geo/api\" :refer [plot]})",
				"plot of example:geo/api@0.1.0 takes point");
		assertRefused(imported + "(geo/nope 1)", "No such var: geo/nope");
		assertRefused(head + "(wit/import \"geo.wit\" {:interface \"example:geo/api\"})",
				"rontolisp.wit/import takes :as or :refer");
		assertRefused(head + "(wit/import \"nope.wit\" {:interface \"example:geo/api\" :as g})",
				"rontolisp.wit/import: cannot read WIT file nope.wit");
		assertRefused(head + "(wit/import \"geo.wit\" {:interface \"example:geo/nope\" :as g})",
				"geo.wit: no interface 'example:geo/nope'");
	}

	@Test
	void aWorldTheClojureTierCannotImplementIsRefused() throws Exception {
		write("async.wit", """
				package local:async;

				world async {
				  export fetch: async func(url: string) -> string;
				}
				""");
		write("greeter.wit", GREETER_WIT);
		String head = "(ns app (:require [rontolisp.wit :as wit] [rontolisp.wasm :as wasm]))\n";
		assertRefused(head + "(wit/export \"async.wit\")",
				"fetch is an async func export, which the Clojure tier does not implement yet (async.wit:4)");
		assertRefused(head + "(defn f [] 1)\n(wasm/export f {})\n(wit/export \"greeter.wit\")",
				"rontolisp.wasm/export cannot be combined with rontolisp.wit/export");
	}

	private void assertRefused(String source, String message) {
		Path program = this.dir.resolve("refused.clj");
		assertThatThrownBy(() -> {
			Files.writeString(program, source);
			wasmForms(program);
		}).isInstanceOf(LispReadException.class).hasMessageContaining(message);
	}

}
