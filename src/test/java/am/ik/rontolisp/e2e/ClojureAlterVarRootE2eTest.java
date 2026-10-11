package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * {@code alter-var-root} of a var the program does not spell as {@code #'x} at the call,
 * on the interpreter, the JVM and both wasm backends: every var site of such a program
 * hands its root setter over, and a function whose var is taken is made redefinable. The
 * spec corpus cannot pin this: one computed call turns the setters on for its whole
 * program. Answers are clj 1.12.6's, but for the refusals of a var with no value cell.
 */
class ClojureAlterVarRootE2eTest {

	record Program(String name, String source, String expected) {

		@Override
		public String toString() {
			return this.name;
		}

	}

	@TempDir
	static Path workDir;

	static List<Program> programs() {
		return List.of(new Program("a-computed-var-reaches-a-def-and-a-defn", """
				(def a 1)
				(defn k [x] (* x 10))
				(defn kk [] (k 1))
				(let [v #'a] (println (alter-var-root v inc) a))
				(let [v #'k] (alter-var-root v (fn [old] (fn [x] (inc (old x))))))
				(println (k 1) (kk))
				(println (mapv (fn [v] (alter-var-root v + 100)) [#'a #'a]) a)
				(println (apply alter-var-root #'a [- 1]) a)
				(let [a 7] (println (alter-var-root (identity #'a) * 2) a))
				(println a)
				(def ^:dynamic *d* :root)
				(println (alter-var-root (identity #'*d*) (constantly :new)) *d*)
				(defn ^:dynamic dyn-f [] 1)
				(alter-var-root (identity #'dyn-f) (constantly (fn [] 2)))
				(println (dyn-f))
				(defn base [] 1)
				(def alias-fn base)
				(defn call-alias [] (alias-fn))
				(alter-var-root (identity (var alias-fn)) (constantly (fn [] 9)))
				(println (alias-fn) (call-alias))
				""", """
				2 2
				11 11
				[102 202] 202
				201 201
				402 7
				402
				:new :new
				2
				9 9
				"""),
				// the computed call is built by the macro body, so only the lowering
				// finds
				// it and the program lowers again with the setters on
				new Program("a-macro-expansion-alters-a-computed-var", """
						(defmacro set-via [v x]
						  (list 'let ['vv v] (list (symbol "alter-var-root") 'vv (list 'constantly x))))
						(defn z [] :z)
						(defn zz [] (z))
						(def w 1)
						(defn read-w [] w)
						(set-via #'z (fn [] :zed))
						(set-via #'w 2)
						(println (z) (zz) (read-w))
						""", ":zed :zed 2\n"),
				// the oracle alters these roots; here a clojure.core verb lowers inline
				// and
				// a multimethod has no value cell, so they are refused by name
				new Program("a-var-with-no-value-cell-is-refused",
						"""
								(defmulti avm :k)
								(println (try (alter-var-root (identity #'inc) identity) (catch UnsupportedOperationException e :refused))
								         (try (alter-var-root #'inc identity) (catch UnsupportedOperationException e :refused))
								         (try (alter-var-root #'avm identity) (catch UnsupportedOperationException e :refused))
								         (try (alter-var-root 1 inc) (catch ClassCastException e :cce))
								         (try (alter-var-root nil inc) (catch NullPointerException e :npe)))
								""",
						":refused :refused :refused :cce :npe\n"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("programs")
	void interpreter(Program program) throws Exception {
		assertThat(interpret(program.source())).isEqualTo(program.expected());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("programs")
	void jvm(Program program) throws Exception {
		assertThat(runOnJvm(program.source())).isEqualTo(program.expected());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("programs")
	void wasmPreview1(Program program) throws Exception {
		assertThat(runOnWasm(program, false)).isEqualTo(program.expected());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("programs")
	void wasmComponent(Program program) throws Exception {
		assertThat(runOnWasm(program, true)).isEqualTo(program.expected());
	}

	private static String interpret(String program) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-alter-var-root", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : evaluator
				.clojureProgram(SourceLanguage.CLOJURE.read(program, Features.INTERPRETER, "alter.clj"))) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnJvm(String program) throws Exception {
		String name = "ClojureAlterVarRoot";
		byte[] classBytes = new JvmSourceCompiler(name).sourceLanguage("clojure").compile(program, null).classBytes();
		ClassLoader loader = new ClassLoader(ClojureAlterVarRootE2eTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String n) throws ClassNotFoundException {
				if (n.equals(name)) {
					return defineClass(n, classBytes, 0, classBytes.length);
				}
				return super.findClass(n);
			}
		};
		Method main = loader.loadClass(name).getMethod("main", String[].class);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (var _ = ThreadStdio.out(out)) {
			CliStack.call("clojure-alter-var-root", () -> main.invoke(null, (Object) new String[0]));
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnWasm(Program program, boolean component) throws Exception {
		if (!HostWasmtime.isAvailable()) {
			abort("no usable wasmtime on PATH");
		}
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(program.source(), true, component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		Path path = Files.createTempFile(workDir, "alter", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		Path outFile = Files.createTempFile(workDir, "alter", ".out");
		Path errFile = Files.createTempFile(workDir, "alter", ".err");
		Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", path.toString())
			.redirectOutput(outFile.toFile())
			.redirectError(errFile.toFile())
			.start();
		if (!process.waitFor(300, TimeUnit.SECONDS)) {
			process.destroyForcibly().waitFor();
			throw new IllegalStateException("wasmtime command timed out: " + path);
		}
		assertThat(process.exitValue()).as("wasmtime exit code: %s", Files.readString(errFile)).isZero();
		return Files.readString(outFile, StandardCharsets.UTF_8);
	}

}
