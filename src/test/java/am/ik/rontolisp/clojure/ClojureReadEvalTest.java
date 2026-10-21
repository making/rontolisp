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

class ClojureReadEvalTest {

	@TempDir
	static Path dir;

	private static final String MAIN = """
			(ns app.main (:require [app.helpers] [re.readers] [clojure.string :as str]))
			(println #=(+ 1 2))
			(println #=(app.helpers/double 21))
			(println '#=(app.helpers/double 3) [#=(str "a" 1) #=(app.helpers/double #=(* 2 3))])
			(println #=(str/join "-" ["a" "b"]))
			(println #=(vector 1 2) #=(hash-map :a 1) #=(keyword "k"))
			(println #=(var app.helpers/double) (var? #=(var app.helpers/double)))
			(println #re/tag #=(+ 1 2))
			(set! *read-eval* false)
			(set! *read-eval* true)
			(println #=(+ 3 4))
			""";

	private static final String MAIN_OUT = """
			3
			42
			6 [a1 12]
			a-b
			[1 2] {:a 1} :k
			#'app.helpers/double true
			[:tagged 3]
			7
			""";

	@BeforeAll
	static void writeProject() throws IOException {
		write("app/deps.edn", "{:paths [\"src\"]}\n");
		write("app/src/data_readers.clj", "{re/tag re.readers/tag}\n");
		write("app/src/re/readers.clj", "(ns re.readers)\n(defn tag [x] [:tagged x])\n");
		write("app/src/app/helpers.clj", "(ns app.helpers)\n(defn double [x] (* x 2))\n");
		write("app/src/app/main.clj", MAIN);
	}

	@Test
	void readEvalEmbedsComputedLiteralsOnTheInterpreterAndTheJvm() throws Exception {
		Path main = dir.resolve("app/src/app/main.clj");
		assertThat(interpret(main)).isEqualTo(MAIN_OUT);
		assertThat(runOnJvm(main, "ReadEvalMain")).isEqualTo(MAIN_OUT);
	}

	@Test
	void readEvalEmbedsComputedLiteralsOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path main = dir.resolve("app/src/app/main.clj");
		assertThat(runOnWasm(main, false)).isEqualTo(MAIN_OUT);
		assertThat(runOnWasm(main, true)).isEqualTo(MAIN_OUT);
	}

	@Test
	void readEvalHonorsReadEvalNil() {
		Path denied = write("app/scripts/denied.clj", "(set! *read-eval* false)\n(println #=(+ 1 2))\n");
		assertThatThrownBy(() -> read(denied)).isInstanceOf(LispReadException.class)
			.hasMessage(denied + ":2:10: EvalReader not allowed when *read-eval* is false.");
	}

	@Test
	void readEvalRefusesAnUnknownHeadASpecialAndAMacro() {
		Path unknown = write("app/scripts/unknown.clj", "(println #=(re-nosuch 1))\n");
		assertThatThrownBy(() -> read(unknown)).isInstanceOf(LispReadException.class)
			.hasMessage(unknown + ":1:10: Can't resolve re-nosuch");
		Path special = write("app/scripts/special.clj", "(println #=(if true 1 2))\n");
		assertThatThrownBy(() -> read(special)).isInstanceOf(LispReadException.class)
			.hasMessage(special + ":1:10: Can't resolve if");
		Path macro = write("app/scripts/macro.clj", "(println #=(when true 1))\n");
		assertThatThrownBy(() -> read(macro)).isInstanceOf(LispReadException.class)
			.hasMessage(macro + ":1:10: Wrong number of args (2) passed to: clojure.core/when");
	}

	@Test
	void readEvalRefusesAFormThatIsNoCall() {
		Path scalar = write("app/scripts/scalar.clj", "(println #=42)\n");
		assertThatThrownBy(() -> read(scalar)).isInstanceOf(LispReadException.class)
			.hasMessage(scalar + ":1:10: Unsupported #= form");
		Path vector = write("app/scripts/vector.clj", "(println #=[1 2])\n");
		assertThatThrownBy(() -> read(vector)).isInstanceOf(LispReadException.class)
			.hasMessage(vector + ":1:10: Unsupported #= form");
	}

	@Test
	void readEvalRefusesAnAnswerWithNoLiteralSpelling() {
		// the oracle embeds the object; here an atom has no spelling to compile
		Path atom = write("app/scripts/atom.clj", "(println #=(atom 1))\n");
		assertThatThrownBy(() -> read(atom)).isInstanceOf(LispReadException.class)
			.hasMessage(atom + ":1:10: Can't embed object in code, maybe print-dup not defined: #<Atom 1>");
		Path missing = write("app/scripts/missing.clj", "(println #=nosuch.Class)\n");
		assertThatThrownBy(() -> read(missing)).isInstanceOf(LispReadException.class)
			.hasMessage(missing + ":1:10: nosuch.Class");
	}

	@Test
	void readEvalSeesOnlyDefinitionsAboveItsSite() {
		// the oracle reads each form after the ones above it ran, so a var defined
		// below is not resolved yet
		Path below = write("app/scripts/below.clj", "(println #=(re-later 1))\n(defn re-later [x] x)\n");
		assertThatThrownBy(() -> read(below)).isInstanceOf(LispReadException.class)
			.hasMessage(below + ":1:10: Can't resolve re-later");
	}

	@Test
	void aSessionReadsADefinitionOnceAnEarlierBufferDefinedIt() throws Exception {
		ClojureSession session = Clojure.session(new MemoryClojureFiles(Map.of()));
		session.setMacroEvaluator(ClojureMacroTime.create());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		for (String buffer : List.of("(defn se-double [x] (* x 2))", "(prn #=(se-double 21))")) {
			for (ClojureTopLevel top : session.read(buffer)) {
				for (LispVal form : top.forms()) {
					evaluator.eval(form);
				}
			}
		}
		assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("42\n");
		assertThatThrownBy(() -> session.read("(set! *read-eval* false) #=(+ 1 2)"))
			.isInstanceOf(LispReadException.class)
			.hasMessage("EvalReader not allowed when *read-eval* is false.");
	}

	private static Path write(String name, String text) {
		try {
			Path path = dir.resolve(name);
			Files.createDirectories(path.getParent());
			return Files.writeString(path, text);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static List<LispVal> read(Path entry) throws IOException {
		return SourceLanguage.CLOJURE.read(Files.readString(entry), Features.INTERPRETER, entry.toString(),
				SourceStandards.DEFAULT, SourceLoader.fileSystem());
	}

	private static String interpret(Path entry) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-read-eval", () -> {
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
			CliStack.call("clojure-read-eval", () -> {
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
		Path path = Files.createTempFile(dir, "readeval", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-W", "gc=y", "-W",
				"exceptions=y", path.toString());
		assertThat(run.exitCode()).as("wasmtime exit code; stderr: %s", run.stderr()).isZero();
		return run.stdout();
	}

}
