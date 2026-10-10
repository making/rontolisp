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
			  (println "seen" (bytes? seen) (String. seen))
			  (kv/bucket-set bucket "hits" (.getBytes "42\u00e9"))
			  (println "now" (vec (kv/bucket-get bucket "hits")) (kv/bucket-get bucket "nope")))
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
		// drop too (bound only where the program names it, like a Common Lisp drop). A
		// member answering a result binds behind its var's wrapper, which on WASM reads
		// the envelope through the raw binding and throws the error arm itself; a byte
		// array crosses a Preview 1 module as the text its octets decode to
		List<String> forms = wasmForms(program);
		assertThat(forms)
			.contains("(RONTOLISP:WIT-IMPORT \"kv.wit\" :INTERFACE "
					+ "\"wasi:keyvalue/store@0.2.0-draft\" :NAMES ((\"open\" \"" + ns + "open%wit\") (\"bucket-get\" \""
					+ ns + "bucket-get%wit\") (\"bucket-set\" \"" + ns + "bucket-set%wit\") (\"bucket-drop\" \"" + ns
					+ "bucket-drop\")))")
			.anyMatch(form -> form.startsWith("(DEFUN |" + ns + "bucket-set| (")
					&& form.contains("(RONTOLISP::%CLOJURE-BYTES-TO-TEXT "))
			.anyMatch(form -> form.startsWith("(DEFUN |" + ns + "open| (")
					&& form.contains("(RONTOLISP::%CLOJURE-WIT-ANSWER (|" + ns + "open%wit%raw| ")
					&& form.endsWith(" \"wasi:keyvalue/store@0.2.0-draft\" \"open\"))"));
	}

	/**
	 * Under {@code --component} the import is wasmtime's real {@code wasi:keyvalue}: the
	 * value the host seeded comes back through the canonical ABI as a byte array, and the
	 * one written is read again, a two-byte character's octets included -- which no
	 * process-local table could fake.
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
		assertThat(run.stdout()).isEqualTo("seen true 41\nnow [52 50 -61 -87] nil\n");
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

				(def store (atom {"hits" (.getBytes "41")}))

				(println (wit/provide "wasi:keyvalue/store"
				                      (fn [member & args]
				                        (cond
				                          (= member "open") 7
				                          (= member "bucket-get") (get @store (second args))
				                          (= member "bucket-set") (do (swap! store assoc (second args) (nth args 2))
				                                                      nil)))))

				(let [bucket (open "")]
				  (println "bucket" bucket "seen" (pr-str (String. (kv/bucket-get bucket "hits"))))
				  (kv/bucket-set bucket "hits" (.getBytes "42"))
				  (println "now" (pr-str (String. (kv/bucket-get bucket "hits")))))
				(println (map #(some-> % String.) (map kv/bucket-get [7 7] ["hits" "nope"])))
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

	/** {@code list<u8>} alone, nested in an option and in a list. */
	private static final String BLOB_WIT = """
			package example:blob@0.1.0;

			interface blob {
			  checksum: func(data: list<u8>) -> u32;
			  fetch: func(n: u8) -> list<u8>;
			  maybe: func(present: bool) -> option<list<u8>>;
			  joined: func(parts: list<list<u8>>) -> list<u8>;
			}
			""";

	private static final String BLOB_IMPORT = """
			(ns blob-calls
			  (:require [rontolisp.wit :as wit]))

			(wit/import "blob.wit" {:interface "example:blob/blob" :as blob})
			""";

	private static final String BLOB_CALLS = """
			(println (blob/checksum (byte-array [1 2 -1])))
			(let [b (blob/fetch 7)] (println (bytes? b) (vec b)))
			(println (vec (blob/maybe true)) (blob/maybe false))
			(println (vec (blob/joined [(byte-array [1 2]) (.getBytes "AB")])))
			(println (try (blob/checksum "abc") (catch ClassCastException e (ex-message e))))
			""";

	/**
	 * A {@code list<u8>} is a byte array both ways on the interpreter and the JVM, alone
	 * and inside an option or a list: the Clojure provider sees byte arrays and answers
	 * them, and anything else going out is the oracle's refusal of a cast to
	 * {@code byte[]}. Across the language boundary the Common Lisp tier holds the octets
	 * (a packed vector), and a Common Lisp provider's string arrives as its UTF-8
	 * encoding -- the text a WASM build lifts a {@code list<u8>} to.
	 */
	@Test
	void aListOfOctetsIsAByteArrayBothWaysOnTheInterpreterAndTheJvm() throws Exception {
		write("blob.wit", BLOB_WIT);
		Path program = write("blob.clj", BLOB_IMPORT + """

				(wit/provide "example:blob/blob"
				  (fn [member & args]
				    (case member
				      "checksum" (if (bytes? (first args)) (reduce + (map #(if (neg? %) (+ % 256) %) (first args))) -1)
				      "fetch" (byte-array [(first args) -1 65])
				      "maybe" (when (first args) (.getBytes "h\u00e9"))
				      "joined" (byte-array (mapcat seq (first args))))))

				""" + BLOB_CALLS);
		String expected = """
				258
				true [7 -1 65]
				[104 -61 -87] nil
				[1 2 65 66]
				class java.lang.String cannot be cast to class [B
				""";
		assertThat(HostBoundaryRuns.cli(program.toString())).isEqualTo(expected);
		assertThat(HostBoundaryRuns.jvm(program, Files.createDirectories(this.dir.resolve("a")), "Blob"))
			.isEqualTo(expected);
		write("blob-calls.clj", BLOB_IMPORT + BLOB_CALLS);
		Path lispProvider = write("lisp-provider.lisp", """
				(rontolisp:wit-provide "example:blob/blob@0.1.0"
				  (lambda (member &rest args)
				    (cond ((equal member "checksum") (reduce #'+ (first args)))
				          ((equal member "fetch") (coerce (list (code-char (first args)) (code-char 233)) 'string))
				          ((equal member "maybe") (if (first args) "hi" nil))
				          ((equal member "joined")
				           (map 'string #'code-char (apply #'concatenate 'list (first args)))))))
				(load "blob-calls.clj")
				""");
		String clojureCalls = """
				258
				true [7 -61 -87]
				[104 105] nil
				[1 2 65 66]
				class java.lang.String cannot be cast to class [B
				""";
		assertThat(HostBoundaryRuns.cli(lispProvider.toString())).isEqualTo(clojureCalls);
		assertThat(HostBoundaryRuns.jvm(lispProvider, Files.createDirectories(this.dir.resolve("b")), "LispBlob"))
			.isEqualTo(clojureCalls);
		write("blob-provider.clj", BLOB_IMPORT + """
				(wit/provide "example:blob/blob"
				  (fn [member & args]
				    (case member
				      "checksum" (alength (first args))
				      "fetch" (byte-array [(first args) -1]))))
				""");
		Path lispCaller = write("lisp-caller.lisp", """
				(load "blob-provider.clj")
				(rontolisp:wit-import "blob.wit" :interface "example:blob/blob" :package blob)
				(print (coerce (blob:fetch 7) 'list))
				(print (blob:checksum (coerce '(1 2 3) '(vector (unsigned-byte 8)))))
				""");
		String lispCalls = "(7 255)\n3\n";
		assertThat(HostBoundaryRuns.cli(lispCaller.toString())).isEqualTo(lispCalls);
		assertThat(HostBoundaryRuns.jvm(lispCaller, Files.createDirectories(this.dir.resolve("c")), "LispCaller"))
			.isEqualTo(lispCalls);
	}

	/**
	 * A Preview 1 core module carries a {@code list<u8>} as {@code :string} text, so a
	 * byte array goes to the host as the text its octets decode to as UTF-8 and the
	 * host's text comes back as its UTF-8 encoding: the octets of a two-byte character
	 * arrive whole both ways, under node.
	 */
	@Test
	void aListOfOctetsCrossesAPreview1ModuleAsItsUtf8TextUnderNode() throws Exception {
		assumeTrue(HostBoundaryRuns.nodeAvailable(), "node is not on PATH");
		write("blob.wit", BLOB_WIT);
		Path program = write("text.clj", """
				(ns text
				  (:require [rontolisp.wit :as wit]))

				(wit/import "blob.wit" {:interface "example:blob/blob" :as blob})

				(defn report {:wasm/export {:params [:int] :returns :string}} [n]
				  (let [b (blob/fetch n)]
				    (str (blob/checksum (.getBytes "h\u00e9")) " " (bytes? b) " " (vec b))))
				""");
		HostBoundaryRuns.cli(program.toString(), "-o", this.dir.resolve("text.wasm").toString(), "--no-wasi",
				"--emit-js-glue");
		Path host = write("run.mjs", """
				import fs from 'fs';
				import { instantiate } from './text.js';

				const module = new WebAssembly.Module(fs.readFileSync(new URL('./text.wasm', import.meta.url)));
				const lisp = instantiate(module, {
				  blob: {
				    checksum: (text) => { console.log('host got', JSON.stringify(text)); return text.length; },
				    fetch: (n) => '\u00e9' + n,
				  },
				});
				console.log(lisp.report(7));
				""");
		assertThat(HostBoundaryRuns.node(this.dir, host.toString()))
			.isEqualTo("host got \"h\u00e9\"\n2 true [-61 -87 55]\n");
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
				             (fn [member & args] (if (= member "open") 1 (.getBytes (str member " " (second args))))))

				(defn hits [] (String. (kv/bucket-get (kv/open "") "hits")))
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
				"(wit/provide \"wasi:keyvalue/store@0.2.0-draft\" (fn [m & args] (if (= m \"open\") 1 (byte-array [(count args)]))))",
				"(println (vec (kv/bucket-get (kv/open \"\") \"hits\")))")) {
			for (SourceSession.Step step : session.read(buffer, Features.INTERPRETER)) {
				for (LispVal form : step.forms()) {
					evaluator.eval(form);
				}
			}
		}
		assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("[2]\n");
	}

	private static final String GEO_WIT = """
			package example:geo@0.1.0;

			interface api {
			  record point { x: s32, y: s32 }
			  plot: func(p: point);
			  later: async func(n: u32) -> u32;
			  distance: func(a: s32, b: s32) -> s32;
			  feed: func(body: option<stream<u8>>);
			}
			""";

	@Test
	void aMemberOutsideTheClojureTierIsRefusedWhereTheProgramReachesIt() throws Exception {
		write("geo.wit", GEO_WIT);
		String head = "(ns app (:require [rontolisp.wit :as wit]))\n";
		String imported = head + "(wit/import \"geo.wit\" {:interface \"example:geo/api\" :as geo})\n";
		// the interface imports: what has a Clojure value is bound, a record behind its
		// var's converting wrapper
		assertThat(wasmForms(write("ok.clj", imported + "(println (geo/distance 1 2))\n")))
			.anyMatch(form -> form.contains("(\"distance\" \"c%example%cgeo.api@0.1.0/distance\")"))
			.anyMatch(form -> form.contains("(\"plot\" \"c%example%cgeo.api@0.1.0/plot%wit\")"));
		assertRefused(imported + "(geo/feed nil)",
				"feed of example:geo/api@0.1.0 takes option<stream<u8>> (parameter 'body'), an option carrying a "
						+ "stream, which the Clojure tier does not carry yet (geo.wit:8)");
		assertRefused(imported + "(map geo/later [1])", "later of example:geo/api@0.1.0 is an async func: the future "
				+ "it answers does not map onto a Clojure future yet (geo.wit:6)");
		assertRefused(head + "(wit/import \"geo.wit\" {:interface \"example:geo/api\" :refer [feed]})",
				"feed of example:geo/api@0.1.0 takes option<stream<u8>>");
		assertRefused(imported + "(geo/nope 1)", "No such var: geo/nope");
		assertRefused(head + "(wit/import \"geo.wit\" {:interface \"example:geo/api\"})",
				"rontolisp.wit/import takes :as or :refer");
		assertRefused(head + "(wit/import \"nope.wit\" {:interface \"example:geo/api\" :as g})",
				"rontolisp.wit/import: cannot read WIT file nope.wit");
		assertRefused(head + "(wit/import \"geo.wit\" {:interface \"example:geo/nope\" :as g})",
				"geo.wit: no interface 'example:geo/nope'");
	}

	/** Every rich WIT type: records, enums, variants, flags, tuples, lists, results. */
	private static final String SHAPES_WIT = """
			package example:shapes@0.1.0;

			interface api {
			  enum color { red, green, DNS-blue }
			  flags perms { read, write, exec }
			  record point { x: s32, y: s32 }
			  record shape { name: string, at: point, tags: list<string>, color: color, visible: bool, label: option<string> }
			  variant figure { none, dot(point), poly(list<point>), named(tuple<string, u32>) }
			  variant problem { too-big(u32), unknown }

			  move: func(p: point, by: tuple<s32, s32>) -> point;
			  describe: func(s: shape) -> shape;
			  echo-figure: func(f: figure) -> figure;
			  grant: func(p: perms) -> perms;
			  colors: func() -> list<color>;
			  checked: func(n: u32) -> result<u32, problem>;
			  fallible: func(ok: bool) -> result<_, string>;
			  take-result: func(r: result<point, problem>) -> string;
			}
			""";

	private static final String SHAPES_IMPORT = """
			(ns shapes
			  (:require [rontolisp.wit :as wit]))

			(wit/import "shapes.wit" {:interface "example:shapes/api" :as api})
			""";

	/**
	 * Every row of the Clojure spelling on the interpreter and the JVM, where the program
	 * provides the interface: a record is a map, an enum a keyword (the label as written,
	 * upper case and all), a variant its case keyword or {@code [:case payload]}, flags a
	 * set, a tuple and a list a vector, a {@code result} argument {@code [:ok v]} /
	 * {@code [:error e]}. The provider sees those values too (its {@code take-result}
	 * renders the argument it got, its {@code describe} negates a {@code bool} field),
	 * and a {@code result}'s error arm it throws under {@code ::wit/error} reaches the
	 * caller as an {@code ExceptionInfo} holding it, the provider's own exception its
	 * cause. A value of no shape of its type is refused naming the type.
	 */
	@Test
	void everyRichValueCrossesInClojuresSpellingOnTheInterpreterAndTheJvm() throws Exception {
		write("shapes.wit", SHAPES_WIT);
		Path program = write("shapes.clj", SHAPES_IMPORT
				+ """

						(def handlers
						  {"move" (fn [p [dx dy]] {:x (+ (:x p) dx) :y (+ (:y p) dy)})
						   "describe" (fn [s] (assoc s :name (str (:name s) "!") :visible (not (:visible s))))
						   "echo-figure" (fn [f] f)
						   "grant" (fn [p] (conj p :exec))
						   "colors" (fn [] [:red :DNS-blue])
						   "checked" (fn [n] (if (> n 10) (throw (ex-info "too big" {::wit/error [:too-big n]})) (* n 2)))
						   "fallible" (fn [ok] (if ok nil (throw (ex-info "nope" {::wit/error "failed"}))))
						   "take-result" (fn [r] (pr-str r))})

						(wit/provide "example:shapes/api" (fn [member & args] (apply (get handlers member) args)))

						(println (api/move {:x 1 :y 2} [10 20]))
						(let [s (api/describe {:name "sq" :at {:x 0 :y 0} :tags ["a" "b"] :color :green :visible true})]
						  (println (:name s) (:at s) (:tags s) (:color s) (:visible s) (:label s)))
						(println (api/echo-figure :none) (api/echo-figure [:dot {:x 3 :y 4}]))
						(println (api/echo-figure [:poly (list {:x 1 :y 1} {:x 2 :y 2})]) (api/echo-figure [:named ["n" 7]]))
						(println (sort (map name (api/grant #{:read}))) (api/colors))
						(println (api/checked 3))
						(println (try (api/checked 30)
						              (catch clojure.lang.ExceptionInfo e [(ex-message e) (ex-data e) (ex-message (ex-cause e))])))
						(println (api/fallible true) (try (api/fallible false) (catch Exception e (::wit/error (ex-data e)))))
						(println (api/take-result [:ok {:x 1 :y 2}]) (api/take-result [:error [:too-big 5]])
						         (api/take-result [:error :unknown]))
						(println (try (api/echo-figure :purple) (catch IllegalArgumentException e (ex-message e))))
						(println (try (api/move [1 2] [1 1]) (catch IllegalArgumentException e (ex-message e))))
						(println (try (api/move {:x 1 :y 2} [1]) (catch IllegalArgumentException e (ex-message e))))
						(println (try (api/grant #{:read :fly}) (catch IllegalArgumentException e (ex-message e))))
						""");
		String expected = """
				{:x 11, :y 22}
				sq! {:x 0, :y 0} [a b] :green false nil
				:none [:dot {:x 3, :y 4}]
				[:poly [{:x 1, :y 1} {:x 2, :y 2}]] [:named [n 7]]
				(exec read) [:red :DNS-blue]
				6
				[checked of example:shapes/api@0.1.0 answered its error arm #:rontolisp.wit{:error [:too-big 30]} too big]
				nil failed
				[:ok {:x 1, :y 2}] [:error [:too-big 5]] [:error :unknown]
				:purple is no case of figure
				[1 2] is no point (a map of its fields)
				[1] is no tuple<s32, s32> (a collection of its members)
				:fly is no flag of perms
				""";
		assertThat(HostBoundaryRuns.cli(program.toString())).isEqualTo(expected);
		assertThat(HostBoundaryRuns.jvm(program, Files.createDirectories(this.dir.resolve("classes")), "Shapes"))
			.isEqualTo(expected);
	}

	/**
	 * The boundary between the two languages is the Common Lisp tier, so each sees its
	 * own spelling of one interface: a Common Lisp provider answers a Clojure caller with
	 * keyword plists and signals {@code rontolisp:wit-error}, which the caller reads as a
	 * map and an {@code ExceptionInfo}; a Clojure provider answers a Common Lisp caller
	 * with a plist and a {@code rontolisp:wit-error} carrying the Common Lisp payload.
	 */
	@Test
	void eachLanguageSeesItsOwnSpellingOfOneInterface() throws Exception {
		write("shapes.wit", SHAPES_WIT);
		write("caller.clj", SHAPES_IMPORT
				+ """

						(println (api/move {:x 1 :y 2} [10 20]) (api/echo-figure [:dot {:x 3 :y 4}]))
						(println (try (api/checked 30)
						              (catch clojure.lang.ExceptionInfo e [(::wit/error (ex-data e)) (ex-message (ex-cause e))])))
						""");
		Path lispProvider = write("lisp-provider.lisp", """
				(rontolisp:wit-provide "example:shapes/api@0.1.0"
				  (lambda (member &rest args)
				    (cond ((equal member "move")
				           (list :x (+ (getf (first args) :x) (first (second args)))
				                 :y (+ (getf (first args) :y) (second (second args)))))
				          ((equal member "echo-figure") (first args))
				          ((equal member "checked")
				           (error 'rontolisp:wit-error :payload (cons :too-big (first args)) :message "too big")))))
				(load "caller.clj")
				""");
		String clojureCalls = "{:x 11, :y 22} [:dot {:x 3, :y 4}]\n[[:too-big 30] too big]\n";
		assertThat(HostBoundaryRuns.cli(lispProvider.toString())).isEqualTo(clojureCalls);
		assertThat(HostBoundaryRuns.jvm(lispProvider, Files.createDirectories(this.dir.resolve("a")), "LispProvider"))
			.isEqualTo(clojureCalls);
		write("provider.clj", SHAPES_IMPORT + """

				(wit/provide "example:shapes/api"
				  (fn [member & args]
				    (cond (= member "move") (let [[p [dx dy]] args] {:x (+ (:x p) dx) :y (+ (:y p) dy)})
				          (= member "echo-figure") (first args)
				          (= member "checked") (throw (ex-info "too big" {::wit/error [:too-big (first args)]})))))
				""");
		Path lispCaller = write("lisp-caller.lisp", """
				(load "provider.clj")
				(rontolisp:wit-import "shapes.wit" :interface "example:shapes/api" :package api)
				(print (api:move '(:x 1 :y 2) '(10 20)))
				(print (api:echo-figure '(:dot :x 3 :y 4)))
				(print (handler-case (api:checked 30)
				         (rontolisp:wit-error (e) (list (rontolisp:wit-error-payload e) (princ-to-string e)))))
				""");
		String lispCalls = "(:X 11 :Y 22)\n(:DOT :X 3 :Y 4)\n((:TOO-BIG . 30) \"too big\")\n";
		assertThat(HostBoundaryRuns.cli(lispCaller.toString())).isEqualTo(lispCalls);
		assertThat(HostBoundaryRuns.jvm(lispCaller, Files.createDirectories(this.dir.resolve("b")), "LispCaller"))
			.isEqualTo(lispCalls);
	}

	/**
	 * The rich types against wasmtime's real hosts under {@code --component}: an enum
	 * argument, a variant carrying a record carrying a tuple both ways, and a
	 * {@code result}'s error arm thrown with its value ({@code wasi:sockets}); a variant
	 * whose cases mostly carry nothing and a {@code result} with no payload at all
	 * ({@code wasi:http}). The interfaces are the hosts' own, trimmed (the subtype check
	 * is structural).
	 */
	@Test
	void aComponentCrossesWasmtimesSocketsAndHttpTypesInClojuresSpelling() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no wasmtime " + HostWasmtime.MINIMUM_MAJOR + "+ on PATH");
		write("sockets.wit", """
				package wasi:sockets@0.3.0;

				interface types {
				  variant error-code {
				    access-denied,
				    not-supported,
				    invalid-argument,
				    out-of-memory,
				    timeout,
				    invalid-state,
				    address-not-bindable,
				    address-in-use,
				    remote-unreachable,
				    connection-refused,
				    connection-broken,
				    connection-reset,
				    connection-aborted,
				    datagram-too-large,
				    other(option<string>)
				  }

				  enum ip-address-family {
				    ipv4,
				    ipv6
				  }

				  type ipv4-address = tuple<u8, u8, u8, u8>;
				  type ipv6-address = tuple<u16, u16, u16, u16, u16, u16, u16, u16>;

				  record ipv4-socket-address {
				    port: u16,
				    address: ipv4-address
				  }

				  record ipv6-socket-address {
				    port: u16,
				    flow-info: u32,
				    address: ipv6-address,
				    scope-id: u32
				  }

				  variant ip-socket-address {
				    ipv4(ipv4-socket-address),
				    ipv6(ipv6-socket-address)
				  }

				  resource tcp-socket {
				    create: static func(address-family: ip-address-family) -> result<tcp-socket, error-code>;

				    bind: func(local-address: ip-socket-address) -> result<_, error-code>;

				    get-local-address: func() -> result<ip-socket-address, error-code>;
				  }
				}
				""");
		Path sockets = write("sockets.clj", """
				(ns sockets
				  (:require [rontolisp.wit :as wit]))

				(wit/import "sockets.wit" {:interface "wasi:sockets/types@0.3.0" :as sock})

				(let [s (sock/tcp-socket-create :ipv4)]
				  (sock/tcp-socket-bind s [:ipv4 {:port 0 :address [127 0 0 1]}])
				  (let [[family address] (sock/tcp-socket-get-local-address s)]
				    (println family (:address address) (pos? (:port address))))
				  (println (try (sock/tcp-socket-bind s [:ipv4 {:port 0 :address [127 0 0 1]}])
				                (catch clojure.lang.ExceptionInfo e [(ex-message e) (::wit/error (ex-data e))]))))
				""");
		Path module = this.dir.resolve("sockets.wasm");
		HostBoundaryRuns.cli(sockets.toString(), "-o", module.toString(), "--component");
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-S",
				"inherit-network=y", module.toString());
		assertThat(run.exitCode()).as("wasmtime: %s", run.stderr()).isZero();
		assertThat(run.stdout()).isEqualTo(":ipv4 [127 0 0 1] true\n"
				+ "[tcp-socket-bind of wasi:sockets/types@0.3.0 answered its error arm :invalid-state]\n");
		write("http.wit", """
				package wasi:http@0.2.0;

				interface types {
				  variant method {
				    get,
				    head,
				    post,
				    put,
				    delete,
				    connect,
				    options,
				    trace,
				    patch,
				    other(string)
				  }

				  resource fields {
				    constructor();
				  }

				  type headers = fields;

				  resource outgoing-request {
				    constructor(headers: headers);

				    method: func() -> method;

				    set-method: func(method: method) -> result;
				  }
				}
				""");
		Path http = write("http.clj", """
				(ns http
				  (:require [rontolisp.wit :as wit]))

				(wit/import "http.wit" {:interface "wasi:http/types@0.2.0" :as http})

				(let [request (http/outgoing-request-new (http/fields-new))]
				  (println (http/outgoing-request-method request))
				  (http/outgoing-request-set-method request :post)
				  (println (http/outgoing-request-method request))
				  (http/outgoing-request-set-method request [:other "PATCH"])
				  (println (http/outgoing-request-method request))
				  (println (try (http/outgoing-request-set-method request [:other "bad method"])
				                (catch clojure.lang.ExceptionInfo e (ex-data e)))))
				""");
		Path httpModule = this.dir.resolve("http.wasm");
		HostBoundaryRuns.cli(http.toString(), "-o", httpModule.toString(), "--component");
		HostWasmtime.ExecResult httpRun = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-S", "http=y",
				httpModule.toString());
		assertThat(httpRun.exitCode()).as("wasmtime: %s", httpRun.stderr()).isZero();
		assertThat(httpRun.stdout()).isEqualTo(":get\n:post\n[:other PATCH]\n#:rontolisp.wit{:error nil}\n");
	}

	/**
	 * A WASM core module carries the flat values only, so it refuses a rich member as it
	 * does for Common Lisp, naming the WIT line -- but only a member the program calls:
	 * under a Clojure import a Preview 1 module binds the members the program names, like
	 * a component, and one the program leaves alone costs nothing.
	 */
	@Test
	void aPreview1ModuleRefusesARichMemberOnlyWhereTheProgramCallsIt() throws Exception {
		write("flat.wit", """
				package example:flat@0.1.0;

				interface flat {
				  record point { x: s32, y: s32 }
				  move: func(p: point) -> point;
				  twice: func(n: s32) -> s32;
				}
				""");
		Path flat = write("flat.clj", """
				(ns flat
				  (:require [rontolisp.wit :as wit]))

				(wit/import "flat.wit" {:interface "example:flat/flat" :as flat})

				(defn report {:wasm/export {:params [:int] :returns :int}} [n] (flat/twice n))
				""");
		Path module = this.dir.resolve("flat.wasm");
		HostBoundaryRuns.cli(flat.toString(), "-o", module.toString(), "--no-wasi");
		assertThat(new String(Files.readAllBytes(module), StandardCharsets.ISO_8859_1)).contains("twice")
			.doesNotContain("move");
		Path moving = write("moving.clj", Files.readString(flat).replace("(flat/twice n)", "(:x (flat/move {:x n}))"));
		assertThatThrownBy(() -> HostBoundaryRuns.cli(moving.toString(), "-o", module.toString(), "--no-wasi"))
			.hasMessageContaining("flat.wit:5: 'move': the WIT type of parameter 'p' does not cross the Preview 1 "
					+ "WASM import boundary");
	}

	@Test
	void aSessionCrossesRichValuesInTheBufferThatCallsThem() throws Exception {
		Path wit = write("shapes.wit", SHAPES_WIT);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		SourceSession session = new SourceSession(SourceLanguage.CLOJURE, SourceStandards.DEFAULT,
				SourceLoader.fileSystem());
		for (String buffer : List.of("(require '[rontolisp.wit :as wit])",
				"(wit/import \"" + wit.toString().replace("\\", "\\\\")
						+ "\" {:interface \"example:shapes/api\" :as api})",
				"(wit/provide \"example:shapes/api\" (fn [m p [dx dy]] {:x (+ (:x p) dx) :y (+ (:y p) dy)}))",
				"(println (api/move {:x 1 :y 2} [10 20]))", "(println (:y (api/move {:x 0 :y 0} [1 1])))")) {
			for (SourceSession.Step step : session.read(buffer, Features.INTERPRETER)) {
				for (LispVal form : step.forms()) {
					evaluator.eval(form);
				}
			}
		}
		assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("{:x 11, :y 22}\n1\n");
	}

	@Test
	void provideNamesAnInterfaceAnImportAboveBinds() throws Exception {
		write("shapes.wit", SHAPES_WIT);
		assertRefused(SHAPES_IMPORT + "(def iface \"example:shapes/api\")\n(wit/provide iface (fn [m] m))",
				"rontolisp.wit/provide takes the interface as a string, the way an import above wrote it, not iface");
		assertRefused(SHAPES_IMPORT + "(wit/provide \"example:other/api\" (fn [m] m))",
				"rontolisp.wit/provide: example:other/api is no interface an import above binds -- import it first");
		assertRefused(SHAPES_IMPORT + "(map wit/provide [\"example:shapes/api\"] [identity])",
				"Can't take value of a macro: #'rontolisp.wit/provide");
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
