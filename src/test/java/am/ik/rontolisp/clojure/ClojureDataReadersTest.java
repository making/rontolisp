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
import am.ik.rontolisp.eval.ClojureMacroTime;
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
 * A project's {@code data_readers.clj} and {@code data_readers.cljc} files -- its own, a
 * {@code :local/root} directory's (a {@code .cljc} one) and a {@code :local/root} jar's
 * -- whose tags read in source through their reader functions once the namespace defining
 * them loaded above, and at run time through {@code *data-readers*}, on the interpreter,
 * the JVM and both WASM backends. Every expected line, refusal and position is the
 * oracle's ({@code clj -M} on the same files, {@code clj} 1.12.6.1673, 2026-10-08), but
 * for the words of a refusal the oracle has none for or reports as a stack trace, which
 * are marked.
 */
class ClojureDataReadersTest {

	@TempDir
	static Path dir;

	private static final String MAIN = """
			(ns app.main
			  (:require [app.readers]
			            [app.other :as o]
			            [lib.read]
			            [jar.read]))
			(println #app/tag 1 '#app/tag 1 #app/code 41 '#app/code 41)
			(defmacro quoted [x] (list 'quote x))
			(println (quoted #app/tag 2) `[#app/tag 3] [#_#app/tag :gone 4])
			(println #lib/x 7 #jar/y "j" o/v (o/f))
			(println (read-string "#app/tag 5")
			         (binding [*data-readers* {'app/tag (fn [x] [:bound x])}] (read-string "#app/tag 6"))
			         (binding [*default-data-reader-fn* (fn [t v] [:default t v])] (read-string "#foo/bar 7")))
			(println (= #'app.readers/tag (get *data-readers* 'app/tag)) (get *data-readers* 'jar/y) (count *data-readers*))
			(println (try (read-string "#app/unbound 8") (catch IllegalStateException e (ex-message e))))
			(println (get default-data-readers 'inst) ((get default-data-readers 'uuid) "1-1-1-1-1"))
			(println (some? (find-ns 'app.nowhere)) (some? (find-ns 'app.elsewhere)))
			""";

	private static final String MAIN_OUT = """
			[:tagged 1] [:tagged 1] 42 (clojure.core/inc 41)
			[:tagged 2] [[:tagged 3]] [4]
			{:x 7} y:j [:tagged :other] 2
			[:tagged 5] [:bound 6] [:default foo/bar 7]
			true #'jar.read/y 14
			Attempting to call unbound fn: #'app.nowhere/reader
			#'clojure.instant/read-instant-date #uuid "00000001-0001-0001-0001-000000000001"
			true false
			""";

	/**
	 * What a reader function answers is compiled like a literal: a record, a symbol
	 * (code, quoted data), false, a pattern, and -- from a function calling the host,
	 * which runs at lower time even for wasm -- a host UUID and Date, embedded as the
	 * oracle's {@code #uuid} and {@code #inst}.
	 */
	private static final String KINDS = """
			(ns app.kinds-main (:require [app.kinds]))
			(def s 5)
			(println #app/rec 1 (record? #app/rec 1) #app/sym "" '#app/sym "")
			(println #app/false 1 #app/re "a+" (re-find #app/re "b+" "abbb"))
			(println #app/uuid "1-1-1-1-2" (uuid? #app/uuid "1-1-1-1-2") #app/date 3 (inst? #app/date 3))
			""";

	private static final String KINDS_OUT = """
			#app.kinds.R{:v 1} true 5 s
			false #"a+" bbb
			#uuid "00000001-0001-0001-0001-000000000002" true #inst "1970-01-01T00:00:00.003-00:00" true
			""";

	@BeforeAll
	static void writeProject() throws IOException {
		write("app/deps.edn", """
				{:paths ["src"]
				 :deps {my/readers {:local/root "../readers"}
				        my/jarred {:local/root "../jarred.jar"}}}
				""");
		write("app/src/data_readers.clj", """
				{app/tag app.readers/tag
				 app/code app.readers/code
				 app/unbound app.nowhere/reader
				 app/rec app.kinds/rec
				 app/sym app.kinds/sym
				 app/false app.kinds/no
				 app/re app.kinds/re
				 app/uuid app.kinds/uuid
				 app/date app.kinds/date
				 app/nil app.kinds/nothing
				 app/atom app.kinds/cell
				 app/throw app.kinds/fail}
				""");
		write("app/src/app/readers.clj", """
				(ns app.readers)
				(defn tag [x] [:tagged x])
				(defn code [x] (list 'clojure.core/inc x))
				""");
		write("app/src/app/other.clj", """
				(ns app.other (:require [app.readers]))
				(def v #app/tag :other)
				(defn f [] #app/code 1)
				""");
		write("app/src/app/kinds.clj", """
				(ns app.kinds)
				(defrecord R [v])
				(defn rec [x] (->R x))
				(defn sym [x] (symbol (str "s" x)))
				(defn no [x] false)
				(defn re [x] (re-pattern x))
				(defn uuid [x] (java.util.UUID/fromString x))
				(defn date [x] (java.util.Date. (long x)))
				(defn nothing [x] nil)
				(defn cell [x] (atom x))
				(defn fail [x] (throw (ex-info "bad tag value" {:x x})))
				""");
		write("app/src/app/main.clj", MAIN);
		write("app/src/app/kinds_main.clj", KINDS);
		write("readers/deps.edn", "{:paths [\"src\"]}\n");
		write("readers/src/data_readers.cljc", """
				{lib/x #?(:cljs lib.read/x-cljs :clj lib.read/x)
				 app/tag app.readers/tag}
				""");
		write("readers/src/lib/read.clj", "(ns lib.read)\n(defn x [v] {:x v})\n");
		Map<String, String> jar = new LinkedHashMap<>();
		jar.put("data_readers.clj", "{jar/y jar.read/y}\n");
		jar.put("jar/read.clj", "(ns jar.read)\n(defn y [v] (str \"y:\" v))\n");
		jar("jarred.jar", jar);
	}

	@Test
	void aProjectsDataReadersReadItsTagsOnTheInterpreterAndTheJvm() throws Exception {
		Path main = dir.resolve("app/src/app/main.clj");
		assertThat(interpret(main)).isEqualTo(MAIN_OUT);
		assertThat(runOnJvm(main, "ReadersMain")).isEqualTo(MAIN_OUT);
		Path kinds = dir.resolve("app/src/app/kinds_main.clj");
		assertThat(interpret(kinds)).isEqualTo(KINDS_OUT);
		assertThat(runOnJvm(kinds, "ReadersKinds")).isEqualTo(KINDS_OUT);
	}

	@Test
	void aProjectsDataReadersReadItsTagsOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path main = dir.resolve("app/src/app/main.clj");
		assertThat(runOnWasm(main, false)).isEqualTo(MAIN_OUT);
		assertThat(runOnWasm(main, true)).isEqualTo(MAIN_OUT);
		Path kinds = dir.resolve("app/src/app/kinds_main.clj");
		assertThat(runOnWasm(kinds, false)).isEqualTo(KINDS_OUT);
		assertThat(runOnWasm(kinds, true)).isEqualTo(KINDS_OUT);
	}

	@Test
	void aTagReadBeforeItsReaderIsDefinedCallsAnUnboundVar() throws Exception {
		// the oracle's startup interns the var without loading its namespace, and reads a
		// form only after the ones above it ran, so the require must come first; a #_
		// discard reads its form, the tag's function called too
		Path early = write("app/scripts/early.clj",
				"(println :before)\n(println #app/tag 1)\n(require 'app.readers)\n");
		assertThatThrownBy(() -> read(early)).isInstanceOf(LispReadException.class)
			.hasMessage(early + ":2:20: Attempting to call unbound fn: #'app.readers/tag");
		Path discard = write("app/scripts/discard.clj", "(println [#_#app/unbound 1 2])\n");
		assertThatThrownBy(() -> read(discard)).isInstanceOf(LispReadException.class)
			.hasMessage(discard + ":1:27: Attempting to call unbound fn: #'app.nowhere/reader");
		Path missing = write("app/scripts/missing.clj", "(require 'app.readers)\n(println #app/unbound 1)\n");
		assertThatThrownBy(() -> read(missing)).isInstanceOf(LispReadException.class)
			.hasMessage(missing + ":2:24: Attempting to call unbound fn: #'app.nowhere/reader");
		Path nope = write("app/scripts/nope.clj", "(require 'app.readers)\n(println #app/nope 1)\n");
		assertThatThrownBy(() -> read(nope)).isInstanceOf(LispReadException.class)
			.hasMessage(nope + ":2:21: No reader function for tag app/nope");
	}

	@Test
	void aTagNoFileMapsNamesWhatTheSourcePathLeavesUnread() throws Exception {
		// a library nothing fetched (no command line here) may hold the data_readers file
		write("unfetched/deps.edn", "{:deps {org.clojure/data.json {:mvn/version \"2.5.1\"}}}\n");
		Path main = write("unfetched/main.clj", "(println #time/date \"2020-01-01\")\n");
		assertThatThrownBy(() -> read(main)).isInstanceOf(LispReadException.class)
			.hasMessage(main + ":1:33: No reader function for tag time/date; not searched: org.clojure/data.json 2.5.1 "
					+ "(a Maven coordinate, not fetched)");
	}

	@Test
	void aReaderAnswerThatCannotCompileIsRefused() throws Exception {
		Path nil = write("app/scripts/nil.clj", "(require 'app.kinds)\n(println #app/nil 1)\n");
		assertThatThrownBy(() -> read(nil)).isInstanceOf(LispReadException.class)
			.hasMessage(nil + ":2:20: No dispatch macro for: a");
		// the oracle: "Syntax error compiling fn*" at the form's start, its str of the
		// atom
		Path atom = write("app/scripts/atom.clj", "(require 'app.kinds)\n(println #app/atom 1)\n");
		assertThatThrownBy(() -> read(atom)).isInstanceOf(LispReadException.class)
			.hasMessage(atom + ":2:21: Can't embed object in code, maybe print-dup not defined: #<Atom 1>");
		// the oracle reports the exception's message alone
		Path fail = write("app/scripts/fail.clj", "(require 'app.kinds)\n(println #app/throw 1)\n");
		assertThatThrownBy(() -> read(fail)).isInstanceOf(LispReadException.class)
			.hasMessage(
					fail + ":2:22: in data reader #'app.kinds/fail: clojure.lang.ExceptionInfo: bad tag value {:x 1}");
	}

	@Test
	void aDataReaderOfADefaultTagReadsItFirst() throws Exception {
		write("inst/deps.edn", "{:paths [\"src\"]}\n");
		write("inst/src/data_readers.clj", "{inst my.inst/read}\n");
		write("inst/src/my/inst.clj", "(ns my.inst)\n(defn read [s] [:inst s])\n");
		// what the default reader refuses too
		Path main = write("inst/src/my/main.clj",
				"(ns my.main (:require [my.inst]))\n(println #inst \"2020\" #inst \"not a date\")\n");
		assertThat(interpret(main)).isEqualTo("[:inst 2020] [:inst not a date]\n");
		Path early = write("inst/src/my/early.clj", "(ns my.early)\n(println #inst \"2020\")\n");
		assertThatThrownBy(() -> read(early)).isInstanceOf(LispReadException.class)
			.hasMessage(early + ":2:22: Attempting to call unbound fn: #'my.inst/read");
	}

	@Test
	void theDataReadersFilesAreRefusedLikeTheOraclesStartup() throws Exception {
		// the oracle throws these as it starts: an ex-info of a URL where a file is named
		// here, the cast refusals with no file at all
		write("shapes/deps.edn", "{:paths [\"src\"]}\n");
		Path main = write("shapes/main.clj", "(println 1)\n");
		Path readers = write("shapes/src/data_readers.clj", "");
		Map<String, String> refused = new LinkedHashMap<>();
		refused.put("[1 2]", "1:1: Not a valid data-reader map");
		refused.put("", "1:1: Not a valid data-reader map");
		refused.put("{:k a/b}", "1:2: Invalid form in data-reader file: :k");
		refused.put("{\"x/y\" a/b}", "1:2: Invalid form in data-reader file: \"x/y\"");
		refused.put("{x/y bar}", "1:6: no conversion to symbol: bar");
		refused.put("{x/y :kw}", "1:6: no conversion to symbol: :kw");
		refused.put("{x/y 1}", "1:6: 1 cannot be cast to clojure.lang.Named");
		refused.put("{x/y a/b x/y a/b}", "1:18: Duplicate key: x/y");
		refused.put("{x/y #?(:clj a/b)}", "1:8: Conditional read not allowed");
		for (Map.Entry<String, String> file : refused.entrySet()) {
			Files.writeString(readers, file.getKey() + "\n");
			assertThatThrownBy(() -> read(main)).as(file.getKey())
				.isInstanceOf(LispReadException.class)
				.hasMessage(readers + ":" + file.getValue());
		}
		// a tag without a namespace is the program's to read, only the first form counts
		Files.writeString(readers, "{x a.b/c} {y/z d/e}\n");
		assertThat(interpret(write("shapes/count.clj", "(println (count *data-readers*))\n"))).isEqualTo("1\n");
		Files.delete(readers);
		// a tag two files map to two vars; the same var twice is no conflict
		write("conflict/deps.edn", "{:paths [\"src\"] :deps {my/lib {:local/root \"lib\"}}}\n");
		write("conflict/src/data_readers.clj", "{t/x a/f t/y a/g}\n");
		write("conflict/lib/deps.edn", "{}\n");
		Path lib = write("conflict/lib/src/data_readers.cljc", "{t/y a/g t/x b/f}\n");
		Path conflict = write("conflict/main.clj", "(println 1)\n");
		assertThatThrownBy(() -> read(conflict)).isInstanceOf(LispReadException.class)
			.hasMessage(lib.toRealPath() + ":1:10: Conflicting data-reader mapping: t/x is #'b/f here and #'a/f in "
					+ dir.resolve("conflict/src/data_readers.clj"));
	}

	@Test
	void aSessionReadsATagOnceAnEarlierInputLoadedItsReader() {
		// the oracle's REPL reads an input after the ones before it ran
		Map<String, String> files = new LinkedHashMap<>();
		files.put("src/data_readers.clj", "{s/tag s.readers/tag}");
		files.put("src/s/readers.clj", "(ns s.readers) (defn tag [x] [:tagged x])");
		ClojureSession session = Clojure.session(new MemoryClojureFiles(files));
		session.setMacroEvaluator(ClojureMacroTime.create());
		assertThatThrownBy(() -> session.read("#s/tag 0")).isInstanceOf(LispReadException.class)
			.hasMessage("Attempting to call unbound fn: #'s.readers/tag");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		for (String buffer : List.of("(require 's.readers)", "(prn #s/tag 1 (read-string \"#s/tag 2\"))")) {
			for (ClojureTopLevel top : session.read(buffer)) {
				for (LispVal form : top.forms()) {
					evaluator.eval(form);
				}
			}
		}
		assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("[:tagged 1] [:tagged 2]\n");
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

	private static List<LispVal> read(Path entry) throws IOException {
		return SourceLanguage.CLOJURE.read(Files.readString(entry), Features.INTERPRETER, entry.toString(),
				SourceStandards.DEFAULT, SourceLoader.fileSystem());
	}

	private static String interpret(Path entry) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-data-readers", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : evaluator.clojureProgram(read(entry))) {
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
			CliStack.call("clojure-data-readers", () -> {
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
		Path path = Files.createTempFile(dir, "readers", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-W", "gc=y", "-W",
				"exceptions=y", path.toString());
		assertThat(run.exitCode()).as("wasmtime exit code; stderr: %s", run.stderr()).isZero();
		return run.stdout();
	}

}
