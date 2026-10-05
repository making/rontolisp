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
 * {@code subs}, {@code .substring} and {@code .charAt} take a double or ratio bound as
 * its truncation, in a program that reads no condition's class (the refusal family is
 * stripped there, so its alias of {@code subseq} is plain {@code subseq}), on the
 * interpreter, the JVM and both wasm backends. The spec corpus cannot pin this: it reads
 * classes, so its program keeps the family. Answers are clj 1.12.6's.
 */
class ClojureStringBoundE2eTest {

	record Program(String name, String source, String expected) {

		@Override
		public String toString() {
			return this.name;
		}

	}

	@TempDir
	static Path workDir;

	static List<Program> programs() {
		return List.of(
				new Program("literal-string-literal-bounds",
						"(println [(subs \"abc\" 1.0) (.substring \"abc\" 1.0) (.substring \"abc\" 1 2.9)"
								+ " (.charAt \"abc\" 1.5) (.charAt \"abc\" 1/2) (subs \"abc\" 1.5M)])",
						"[bc bc b b a bc]\n"),
				new Program("built-string-computed-bounds",
						"(def s (apply str [\"ab\" \"c\"])) (def i 1.0) (def j 2.9) (def r 1/2)"
								+ " (println [(subs s i) (subs s i j) (.substring s i) (.substring s i j) (subs s r)"
								+ " (subs s 0 (+ r r)) (.charAt s i) (.charAt s j) (.charAt s r)])",
						"[bc b bc b abc a b c a]\n"),
				new Program("bounds-a-function-hands-over",
						"(def s (apply str [\"ab\" \"c\"]))" + " (println (map #(subs s %) [0.0 1.5 2.9 3.0]))"
								+ " (println (map #(subs s 1 %) [1.0 2.5 3.0]))"
								+ " (println (apply subs s [1.5 2.5]))",
						"(abc bc c )\n( b bc)\nb\n"),
				new Program("nan-truncates-to-zero",
						"(def s (apply str [\"ab\" \"c\"])) (def nan (/ 0.0 0))"
								+ " (println [(subs s nan) (.charAt s nan) (.substring s 0 nan)])",
						"[abc a ]\n"),
				new Program("integers-and-in-loop-indexes-are-unchanged", "(def s (apply str [\"ab\" \"c\"]))"
						+ " (println (apply str (for [i (range 3)] (subs s i (inc i)))) (.substring s 1) (.charAt s 2))",
						"abc bc c\n"));
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
		CliStack.call("clojure-string-bound", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : SourceLanguage.CLOJURE.read(program, Features.INTERPRETER, "bound.clj")) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnJvm(String program) throws Exception {
		String name = "ClojureStringBound";
		byte[] classBytes = new JvmSourceCompiler(name).sourceLanguage("clojure").compile(program, null).classBytes();
		ClassLoader loader = new ClassLoader(ClojureStringBoundE2eTest.class.getClassLoader()) {
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
			CliStack.call("clojure-string-bound", () -> main.invoke(null, (Object) new String[0]));
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
		Path path = Files.createTempFile(workDir, "bound", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		Path outFile = Files.createTempFile(workDir, "bound", ".out");
		Path errFile = Files.createTempFile(workDir, "bound", ".err");
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
