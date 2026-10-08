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
import am.ik.rontolisp.eval.SourceSession;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.testsupport.HostWasmtime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code rontolisp.wasm} of the Clojure front end: {@code defimport}, {@code export} and
 * a {@code defn}'s {@code {:wasm/export ...}} metadata LOWER to the Common Lisp
 * {@code rontolisp:wasm-import} / {@code rontolisp:wasm-export} directives -- the same
 * forms, and so the same module, a hand-written block produces -- and convert at the
 * boundary only the values Clojure spells differently ({@code false}, an s-expression),
 * through wrappers a program without such a crossing never gets.
 */
class ClojureWasmBoundaryTest {

	@TempDir
	Path dir;

	/** The guide's {@code add}/{@code add10} pair, in Clojure. */
	private static final String ADD10 = """
			(ns app.core
			  (:require [rontolisp.wasm :as wasm]))

			(wasm/defimport add {:from "host" :params [:int :int] :returns :int})

			(defn add10 {:wasm/export {:params [:int] :returns :int}} [n] (add n 10))
			""";

	private static List<String> wasmForms(String source) {
		return SourceLanguage.CLOJURE.read(source, Features.WASM, "app.clj").stream().map(LispVal::print).toList();
	}

	private static List<String> handWritten(String source) {
		return LispReader.readAllFromString(source).stream().map(LispVal::print).toList();
	}

	@Test
	void aDeclarationLowersToTheHandWrittenDirective() {
		// the host name is the var's as written, passed explicitly: the directive's own
		// default would hand the host the mangled c%app.core/add
		assertThat(wasmForms(ADD10)).containsAll(handWritten("""
				(rontolisp:wasm-import '|c%app.core/add| :from "host" :as "add" :params '(:int :int) :returns :int)
				(rontolisp:wasm-export '|c%app.core/add10| :as "add10" :params '(:int) :returns :int)
				"""));
	}

	/**
	 * THE ACCEPTANCE TEST: a module exporting a Clojure {@code defn} by its metadata is
	 * byte for byte the one a Common Lisp file loading the same {@code defn} and writing
	 * the directive by hand compiles to -- on Preview 1 and as a component. No new export
	 * path, and the spec the metadata holds costs the program nothing.
	 */
	@Test
	void anExportedDefnIsTheModuleTheHandWrittenDirectiveCompilesTo() throws Exception {
		Files.writeString(this.dir.resolve("core.clj"), "(ns app)\n\n(defn add10 [n] (+ n 10))\n");
		Path byHand = this.dir.resolve("main.lisp");
		Files.writeString(byHand, """
				(load "core.clj")
				(rontolisp:wasm-export '|c%app/add10| :as "add10" :params '(:int) :returns :int)
				""");
		Path clojure = this.dir.resolve("full.clj");
		Files.writeString(clojure, """
				(ns app
				  (:require [rontolisp.wasm :as wasm]))

				(defn add10 {:wasm/export {:params [:int] :returns :int}} [n] (+ n 10))
				""");
		for (List<String> flags : List.<List<String>>of(List.of("--optimize=off"), List.of(), List.of("--component"))) {
			assertThat(compile(clojure, "full", flags)).as("flags %s", flags).isEqualTo(compile(byHand, "main", flags));
		}
	}

	private byte[] compile(Path program, String name, List<String> flags) throws Exception {
		Path out = this.dir.resolve(name + flags.size() + ".wasm");
		List<String> args = new ArrayList<>(List.of(program.toString(), "-o", out.toString()));
		args.addAll(flags);
		HostBoundaryRuns.cli(args.toArray(String[]::new));
		return Files.readAllBytes(out);
	}

	@Test
	void theInterpreterAndTheJvmCallAnExportAndRefuseAHostFunctionInClojuresWords() throws Exception {
		Path program = this.dir.resolve("app.clj");
		Files.writeString(program, ADD10 + """
				(defn twice {:wasm/export {:params [:int] :returns :int}} [n] (* 2 n))
				(println (twice 21))
				(println (try (add10 1) (catch UnsupportedOperationException e (ex-message e))))
				""");
		String expected = "42\nadd is a host function (rontolisp.wasm/defimport): only a compiled WASM module "
				+ "can call it\n";
		assertThat(HostBoundaryRuns.cli(program.toString())).isEqualTo(expected);
		assertThat(HostBoundaryRuns.jvm(program, Files.createDirectories(this.dir.resolve("classes")), "WasmApp"))
			.isEqualTo(expected);
	}

	/** Every crossing that converts, and every export shape a wrapper serves. */
	private static final String CROSSINGS = """
			(ns conv
			  (:require [rontolisp.wasm :as wasm]))

			(wasm/defimport even-host? {:from "host" :as "isEven" :params [:int] :returns :bool})
			(wasm/defimport flip {:from "host" :params [:bool] :returns :bool})
			(wasm/defimport echo-data {:from "host" :as "echoData" :params [:s-expr] :returns :s-expr})

			(defn positive? {:wasm/export {:as "isPositive" :params [:int] :returns :bool}} [n] (> n 0))
			(defn negate {:wasm/export {:params [:bool] :returns :bool}} [b] (not b))
			(defn describe {:wasm/export {:params [:s-expr] :returns :s-expr}} [x] [x (count x)])
			(defn ask {:wasm/export {:params [:int] :returns :string}} [n]
			  (str (even-host? n) " " (flip false) " " (pr-str (echo-data [1 :a "s" false nil {:k 2}]))))
			(defn many ([a] a) ([a b] (+ a b)))
			(wasm/export many {:as "many2" :params [:int :int] :returns :int})
			(def twice (fn [x] (* 2 x)))
			(wasm/export twice {:params [:int] :returns :int})
			""";

	/**
	 * Under node, through the glue {@code --emit-js-glue} writes: a Clojure {@code false}
	 * crosses as the host's false both ways (unconverted it would be a non-{@code nil}
	 * object, so true), an s-expression in Clojure notation both ways (vectors, maps,
	 * keywords and {@code false} round-trip, which the Common Lisp printer and reader do
	 * not), and a multi-arity {@code defn} and a {@code def}'d function export through a
	 * fixed-arity wrapper calling the var.
	 */
	@Test
	void aBoolAndAnSExprCrossInTheirClojureSpellingUnderNode() throws Exception {
		assumeTrue(HostBoundaryRuns.nodeAvailable(), "node is not on PATH");
		Path program = this.dir.resolve("conv.clj");
		Files.writeString(program, CROSSINGS);
		HostBoundaryRuns.cli(program.toString(), "-o", this.dir.resolve("conv.wasm").toString(), "--no-wasi",
				"--emit-js-glue");
		Path host = this.dir.resolve("run.mjs");
		Files.writeString(host, """
				import fs from 'fs';
				import { instantiate } from './conv.js';

				const module = new WebAssembly.Module(fs.readFileSync(new URL('./conv.wasm', import.meta.url)));
				const lisp = instantiate(module, {
				  host: {
				    isEven: (n) => n % 2 === 0,
				    flip: (b) => !b,
				    echoData: (text) => { console.log('host got ' + text); return text; },
				  },
				});
				console.log(lisp.isPositive(3), lisp.isPositive(-1), lisp.negate(true), lisp.negate(false));
				console.log(lisp.describe('[1 2 :a]'), lisp.describe('{:x false}'));
				console.log(lisp.ask(4));
				console.log(lisp.ask(3));
				console.log(lisp.many2(2, 3), lisp.twice(21));
				""");
		assertThat(HostBoundaryRuns.node(this.dir, host.toString())).isEqualTo("""
				true false false true
				[[1 2 :a] 3] [{:x false} 1]
				host got [1 :a "s" false nil {:k 2}]
				true true [1 :a "s" false nil {:k 2}]
				host got [1 :a "s" false nil {:k 2}]
				false true [1 :a "s" false nil {:k 2}]
				5 42
				""");
	}

	/**
	 * A plain crossing gets no wrapper: the export names the {@code defun}, the import
	 * binds the var's own symbol. A converting one goes through a wrapper of the var, so
	 * the definition itself stays the one a Clojure call reaches.
	 */
	@Test
	void onlyAConvertingCrossingOrAnUnplainShapeGetsAWrapper() {
		List<String> forms = wasmForms(CROSSINGS);
		assertThat(forms).contains(
				"(RONTOLISP:WASM-IMPORT '|c%conv/even-host?%import| :FROM \"host\" :AS \"isEven\" :PARAMS '(:INT) "
						+ ":RETURNS :BOOL)",
				"(RONTOLISP:WASM-IMPORT '|c%conv/echo-data%import| :FROM \"host\" :AS \"echoData\" "
						+ ":PARAMS '(:STRING) :RETURNS :STRING)",
				"(RONTOLISP:WASM-EXPORT '|c%conv/ask| :AS \"ask\" :PARAMS '(:INT) :RETURNS :STRING)",
				"(RONTOLISP:WASM-EXPORT '|c%conv/positive?%export| :AS \"isPositive\" :PARAMS '(:INT) "
						+ ":RETURNS :BOOL)",
				"(RONTOLISP:WASM-EXPORT '|c%conv/many%export| :AS \"many2\" :PARAMS '(:INT :INT) :RETURNS :INT)",
				"(RONTOLISP:WASM-EXPORT '|c%conv/twice%export| :AS \"twice\" :PARAMS '(:INT) :RETURNS :INT)");
		assertThat(forms).anyMatch(form -> form.startsWith("(DEFUN |c%conv/positive?| (|c%n|)"))
			.noneMatch(form -> form.contains("%meta|"));
	}

	/**
	 * A directive's quoted name is a compile-time name: exporting a namespaced var
	 * ({@code '|c%app/show|}) makes no qualified symbol the printer could meet, so a
	 * printing program keeps no namespace-map arm for it -- the same printer it compiles
	 * to without the export.
	 */
	@Test
	void aDirectivesNameBuildsNoQualifiedSymbolThePrinterSpells() {
		String program = """
				(ns app
				  (:require [rontolisp.wasm :as wasm]))

				(defn show {:wasm/export {:params [:int] :returns :string}} [n] (str [n]))
				(println (show 1))
				""";
		assertThat(am.ik.rontolisp.cli.CompileFrontendAccess.clojure(program, true, false).forms()).map(LispVal::print)
			.anyMatch(form -> form.startsWith("(RONTOLISP:WASM-EXPORT '|c%app/show| "))
			.noneMatch(form -> form.contains("PRINT-NAMESPACE-MAPS"));
	}

	/**
	 * {@code examples/clojure/host-boundary}: the guide's pair in Clojure, the host a
	 * Clojure module too, preloaded by wasmtime to answer the import.
	 */
	@Test
	void theHostBoundaryExampleRunsWithItsHostPreloaded() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no wasmtime " + HostWasmtime.MINIMUM_MAJOR + "+ on PATH");
		Path example = Path.of("examples/clojure/host-boundary");
		Path main = this.dir.resolve("main.wasm");
		Path host = this.dir.resolve("host.wasm");
		HostBoundaryRuns.cli(example.resolve("main.clj").toString(), "-o", main.toString(), "--no-wasi");
		HostBoundaryRuns.cli(example.resolve("host.clj").toString(), "-o", host.toString(), "--no-wasi");
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "--preload",
				"host=" + host, "--invoke", "add10", main.toString(), "32");
		assertThat(run.exitCode()).as(run.stderr()).isZero();
		assertThat(run.stdout().strip()).isEqualTo("42");
	}

	@Test
	void aComponentLiftsTheConvertedExports() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no wasmtime " + HostWasmtime.MINIMUM_MAJOR + "+ on PATH");
		Path program = this.dir.resolve("calc.clj");
		Files.writeString(program, """
				(ns calc
				  (:require [rontolisp.wasm :as wasm]))

				(defn positive? {:wasm/export {:as "is-positive" :params [:s32] :returns :bool}} [n] (> n 0))
				(defn total ([a] a) ([a b] (+ a b)))
				(wasm/export total {:params [:s32 :s32] :returns :s32})
				""");
		Path module = this.dir.resolve("calc.wasm");
		HostBoundaryRuns.cli(program.toString(), "-o", module.toString(), "--component");
		StringBuilder answers = new StringBuilder();
		for (String call : List.of("is-positive(3)", "is-positive(-3)", "total(2, 40)")) {
			HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "--invoke", call,
					module.toString());
			assertThat(run.exitCode()).as("%s: %s", call, run.stderr()).isZero();
			answers.append(run.stdout().strip()).append('\n');
		}
		assertThat(answers.toString()).isEqualTo("true\nfalse\n42\n");
	}

	@Test
	void aSessionEchoesTheVarAndExportsWhatItsBufferDefined() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		SourceSession session = new SourceSession(SourceLanguage.CLOJURE);
		List<String> echoes = new ArrayList<>();
		for (String buffer : List.of("(require '[rontolisp.wasm :as wasm])",
				"(wasm/defimport add {:from \"host\" :params [:int :int] :returns :int})",
				"(defn add10 {:wasm/export {:params [:int] :returns :int}} [n] (+ n 10))", "(add10 5)")) {
			for (SourceSession.Step step : session.read(buffer, Features.INTERPRETER)) {
				LispVal value = null;
				for (LispVal form : step.forms()) {
					value = evaluator.eval(form);
				}
				if (step.echoes() && value != null) {
					echoes.add(session.echo(value, evaluator));
				}
			}
		}
		assertThat(echoes).containsExactly("nil", "#'user/add", "#'user/add10", "15");
	}

	@Test
	void aDeclarationRefusesWhatItCannotCarryByName() {
		String head = "(ns app (:require [rontolisp.wasm :as wasm]))\n";
		assertRefused(head + "(wasm/defimport fetch {:params [:bytes]})", ":bytes does not cross from Clojure");
		assertRefused(head + "(wasm/defimport fetch {:params [:string] :async true})", ":async is not supported yet");
		assertRefused(head + "(wasm/defimport fetch {:params [:strng]})", "unknown type :strng (the boundary carries");
		assertRefused(head + "(wasm/defimport fetch {:params [:int] :retruns :int})", "unknown option :retruns");
		assertRefused(head + "(wasm/defimport fetch)", "Wrong number of args (1) passed to: rontolisp.wasm/defimport");
		assertRefused(head + "(wasm/defimport other/fetch {})", "defines a name of the current namespace");
		assertRefused(head + "(def spec {:params [:int]})\n(wasm/defimport fetch spec)",
				"rontolisp.wasm/defimport takes an options map, not spec");
		assertRefused(head + "(wasm/export nope {:params [:int]})", "rontolisp.wasm/export: nope names no var of app");
		assertRefused(head + "(defn f [a b] a)\n(wasm/export f {:params [:int]})",
				"f is exported with 1 parameter, but no arity of (defn f ...) takes 1");
		assertRefused(head + "(defmacro m [x] x)\n(wasm/export m {:params [:int]})",
				"Can't take value of a macro: #'app/m");
		assertRefused(head + "(map wasm/defimport [1])", "Can't take value of a macro: #'rontolisp.wasm/defimport");
	}

	private static void assertRefused(String source, String message) {
		assertThatThrownBy(() -> SourceLanguage.CLOJURE.read(source, Features.WASM, "app.clj"))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining(message);
	}

}
