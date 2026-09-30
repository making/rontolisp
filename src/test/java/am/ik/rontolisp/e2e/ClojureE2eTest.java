package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The EXPERIMENTAL Clojure front end pinned on one corpus: the interpreter, the JVM and
 * both WASM backends answer the same output. The corpus is
 * {@code examples/clojure/demo.clj} (recursion, {@code loop}/{@code recur},
 * {@code #(...)}, higher-order {@code map}/{@code filter}/{@code reduce}, vectors,
 * keywords, {@code cond}, {@code let}, a directly called {@code fn}) -- the a97 spike's
 * verification program. Divergences from real Clojure (upcased keywords, CL-notation
 * collections, no {@code println} separators) are the documented deviations in
 * {@code .kb/clojure-frontend.md}, not failures: what is pinned here is that every
 * backend agrees.
 */
class ClojureE2eTest {

	private static final String PROGRAM = """
			(def greeting "hello")

			(defn fact [n]
			  (if (< n 2) 1 (* n (fact (- n 1)))))

			(defn fib [n]
			  (loop [a 0 b 1 i 0]
			    (if (= i n) a (recur b (+ a b) (inc i)))))

			(defn compose-demo [coll]
			  (reduce + 0 (map #(* % %) (filter odd? coll))))

			(println (str greeting ", clojure on rontolisp!"))
			(println (fact 10))
			(println (fib 20))
			(println (compose-demo '(1 2 3 4 5 6 7)))
			(println (apply max '(3 9 4)))
			(println [:a :b "vec"])
			(println (count [10 20 30]))
			(println (= 1 1) (= 1 2) (nil? nil) (some? 0))
			(println (cond
			           (= 1 2) :one
			           :else :fallback))
			(let [x 10
			      y (+ x 5)]
			  (println (str "x+y=" y)))
			(println ((fn [a b] (+ (* a 10) b)) 4 2))
			""";

	private static final List<String> EXPECTED = List.of("hello, clojure on rontolisp!", "3628800", "6765", "84", "9",
			"#(A B vec)", "3", "TNILTT", "FALLBACK", "x+y=15", "42");

	@TempDir
	Path workDir;

	@Test
	void theInterpreterAnswersTheCorpus() throws Exception {
		assertThat(splitLines(interpret(PROGRAM))).containsExactlyElementsOf(EXPECTED);
	}

	@Test
	void theJvmAnswersTheCorpus() throws Exception {
		assertThat(splitLines(runOnJvm(PROGRAM))).containsExactlyElementsOf(EXPECTED);
	}

	@Test
	void wasmAnswersTheCorpus() throws Exception {
		if (!HostWasmtime.isAvailable()) {
			abort("no usable wasmtime on PATH");
		}
		assertThat(splitLines(runOnWasm(PROGRAM, false))).containsExactlyElementsOf(EXPECTED);
	}

	@Test
	void theComponentAnswersTheCorpus() throws Exception {
		if (!HostWasmtime.isAvailable()) {
			abort("no usable wasmtime on PATH");
		}
		assertThat(splitLines(runOnWasm(PROGRAM, true))).containsExactlyElementsOf(EXPECTED);
	}

	private static String interpret(String program) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-e2e", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : SourceLanguage.CLOJURE.read(program, Features.INTERPRETER, "demo.clj")) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnJvm(String program) throws Exception {
		String name = "ClojureDemo";
		byte[] classBytes = new JvmSourceCompiler(name).sourceLanguage("clojure").compile(program, null).classBytes();
		ClassLoader loader = new ClassLoader(ClojureE2eTest.class.getClassLoader()) {
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
			CliStack.call("clojure-e2e", () -> main.invoke(null, (Object) new String[0]));
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private String runOnWasm(String program, boolean component) throws Exception {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(program, false, component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		String name = "clojure-demo" + (component ? "-component" : "");
		Path path = this.workDir.resolve(name + ".wasm");
		Files.write(path, module);
		Path outFile = Files.createTempFile(this.workDir, name, ".out");
		Path errFile = Files.createTempFile(this.workDir, name, ".err");
		try {
			Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--dir", "/tmp",
					path.toString())
				.redirectOutput(outFile.toFile())
				.redirectError(errFile.toFile())
				.start();
			if (!process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly().waitFor();
				throw new IllegalStateException("wasmtime command timed out: " + path);
			}
			String stderr = Files.readString(errFile, StandardCharsets.UTF_8);
			assertThat(process.exitValue()).as("wasmtime exit code: %s", stderr).isZero();
			return Files.readString(outFile, StandardCharsets.UTF_8);
		}
		finally {
			Files.deleteIfExists(outFile);
			Files.deleteIfExists(errFile);
		}
	}

	private static List<String> splitLines(String text) {
		if (text.isEmpty()) {
			return List.of();
		}
		List<String> lines = new java.util.ArrayList<>(List.of(text.split("\n", -1)));
		if (lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}
		return lines;
	}

}
