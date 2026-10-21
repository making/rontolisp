package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Differential parity of the builtin surface against the host {@code clj} oracle
 * (1.12.x): a corpus of {@code (ns, var, form)} rows, one small pure expression per
 * builtin where a canonical expression exists (docstring examples are the natural source;
 * the per-ns families {@code clojure.string}/{@code set}/{@code walk}/
 * {@code pprint}/{@code data} included). Pure expressions only -- no IO, no concurrency,
 * no time-of-day. Each form runs through both {@code clj -M} and every rontolisp backend,
 * compared as printed values.
 *
 * <p>
 * Adding a row is one {@link #row} (or {@link #deviating}) call in {@link #ROWS}. The
 * failure output names the ns, var, form and both outputs.
 *
 * <p>
 * The runner skips like {@code CiSpecE2eTest} when {@code clj} is absent, so the suite
 * stays green without the oracle. The interpreter and JVM legs always run; the two wasm
 * legs run only with a usable {@code wasmtime} on {@code PATH}.
 *
 * <p>
 * Known printer deviations are whitelisted explicitly per row via {@link #deviating},
 * never globally: the row pins the deliberate deviation documented in
 * {@code .kb/clojure-frontend.md} and fails if either side moves.
 */
class ClojureDifferentialParityTest {

	record Row(String ns, String var, String form, @Nullable String knownDeviation) {

		String label() {
			return this.ns + "/" + this.var;
		}

	}

	private static Row row(String ns, String var, String form) {
		return new Row(ns, var, form, null);
	}

	private static Row deviating(String ns, String var, String form, String rontolispPrints) {
		return new Row(ns, var, form, rontolispPrints);
	}

	static final List<Row> ROWS = List.of(
			// clojure.core arithmetic
			row("clojure.core", "+", "(+ 1 2 3)"), row("clojure.core", "-", "(- 10 3 2)"),
			row("clojure.core", "*", "(* 2 3 4)"), row("clojure.core", "/", "(/ 7 2)"),
			row("clojure.core", "quot", "(quot 7 2)"), row("clojure.core", "rem", "(rem 7 3)"),
			row("clojure.core", "mod", "(mod -7 2)"), row("clojure.core", "inc", "(inc 5)"),
			row("clojure.core", "dec", "(dec 5)"), row("clojure.core", "max", "(max 1 5 3)"),
			row("clojure.core", "min", "(min 1 5 3)"), row("clojure.core", "+", "(+ 1/2 1/3)"),
			// clojure.core equality
			row("clojure.core", "=", "(= 1 1.0)"), row("clojure.core", "==", "(== 1 1.0 1N)"),
			row("clojure.core", "=", "(= [1 2] '(1 2))"),
			// clojure.core predicates
			row("clojure.core", "odd?", "(odd? 3)"), row("clojure.core", "even?", "(even? 4)"),
			row("clojure.core", "zero?", "(zero? 0)"), row("clojure.core", "pos?", "(pos? 1)"),
			row("clojure.core", "neg?", "(neg? -1)"), row("clojure.core", "nil?", "(nil? nil)"),
			row("clojure.core", "some?", "(some? false)"), row("clojure.core", "true?", "(true? true)"),
			row("clojure.core", "false?", "(false? false)"), row("clojure.core", "string?", "(string? \"a\")"),
			row("clojure.core", "keyword?", "(keyword? :a)"), row("clojure.core", "symbol?", "(symbol? 'a)"),
			row("clojure.core", "number?", "(number? 1)"), row("clojure.core", "integer?", "(integer? 1.0)"),
			// clojure.core seq verbs
			row("clojure.core", "map", "(map inc [1 2 3])"), row("clojure.core", "filter", "(filter odd? [1 2 3 4])"),
			row("clojure.core", "take", "(take 2 [1 2 3])"), row("clojure.core", "drop", "(drop 2 [1 2 3])"),
			row("clojure.core", "first", "(first [1 2])"), row("clojure.core", "rest", "(rest [1 2])"),
			row("clojure.core", "next", "(next [1])"), row("clojure.core", "last", "(last [1 2 3])"),
			row("clojure.core", "count", "(count [1 2 3])"), row("clojure.core", "conj", "(conj [1] 2 3)"),
			row("clojure.core", "cons", "(cons 1 [2])"), row("clojure.core", "concat", "(concat [1] [2 3])"),
			row("clojure.core", "distinct", "(distinct [1 1 2 2 3])"),
			row("clojure.core", "interleave", "(interleave [1 2] [:a :b])"),
			row("clojure.core", "interpose", "(interpose 0 [1 2 3])"),
			row("clojure.core", "partition", "(partition 2 [1 2 3 4])"),
			row("clojure.core", "reverse", "(reverse [1 2 3])"), row("clojure.core", "sort", "(sort [3 1 2])"),
			row("clojure.core", "sort-by", "(sort-by count [\"aaa\" \"b\" \"cc\"])"),
			row("clojure.core", "group-by", "(group-by odd? [1 2 3])"),
			row("clojure.core", "frequencies", "(frequencies [:a :a :b])"),
			row("clojure.core", "zipmap", "(zipmap [:a :b] [1 2])"), row("clojure.core", "mapv", "(mapv inc [1 2])"),
			row("clojure.core", "filterv", "(filterv odd? [1 2 3])"),
			row("clojure.core", "mapcat", "(mapcat reverse [[3 2 1] [4]])"),
			row("clojure.core", "keep", "(keep #(when (odd? %) %) [1 2 3])"),
			row("clojure.core", "remove", "(remove odd? [1 2 3])"),
			row("clojure.core", "take-while", "(take-while pos? [1 5 -1 3])"),
			row("clojure.core", "drop-while", "(drop-while pos? [1 5 -1 3])"),
			row("clojure.core", "some", "(some odd? [2 4 5])"), row("clojure.core", "every?", "(every? odd? [1 3])"),
			// clojure.core map verbs
			row("clojure.core", "get", "(get {:a 1} :a)"), row("clojure.core", "assoc", "(assoc {:a 1} :b 2)"),
			row("clojure.core", "dissoc", "(dissoc {:a 1 :b 2} :a)"),
			row("clojure.core", "merge", "(merge {:a 1} {:b 2})"),
			row("clojure.core", "update", "(update {:a 1} :a inc)"),
			row("clojure.core", "select-keys", "(select-keys {:a 1 :b 2} [:a])"),
			row("clojure.core", "keys", "(keys {:a 1 :b 2})"), row("clojure.core", "vals", "(vals {:a 1 :b 2})"),
			row("clojure.core", "contains?", "(contains? {:a 1} :b)"), row("clojure.core", "find", "(find {:a 1} :a)"),
			// clojure.core conversions and misc
			row("clojure.core", "str", "(str \"a\" 1 :b)"), row("clojure.core", "pr-str", "(pr-str [1 :a \"x\"])"),
			row("clojure.core", "vec", "(vec \"abc\")"), row("clojure.core", "set", "(set [1 1 2])"),
			row("clojure.core", "list", "(list 1 2)"), row("clojure.core", "range", "(range 5)"),
			row("clojure.core", "range", "(take 5 (range))"),
			row("clojure.core", "iterate", "(take 3 (iterate inc 0))"), row("clojure.core", "nth", "(nth [1 2 3] 1)"),
			row("clojure.core", "get-in", "(get-in {:a {:b 2}} [:a :b])"),
			row("clojure.core", "assoc-in", "(assoc-in {} [:a :b] 1)"),
			row("clojure.core", "apply", "(apply + [1 2 3])"),
			row("clojure.core", "format", "(format \"%s=%d\" \"a\" 1)"),
			row("clojure.core", "subs", "(subs \"hello\" 1 3)"),
			row("clojure.core", "ex-data", "(ex-data (ex-info \"m\" {:a 1}))"),
			row("clojure.core", "name", "(name :foo/bar)"), row("clojure.core", "namespace", "(namespace :foo/bar)"),
			row("clojure.core", "keyword", "(keyword \"a\" \"b\")"), row("clojure.core", "compare", "(compare 1 2)"),
			// clojure.string
			row("clojure.string", "join", "(clojure.string/join \", \" [1 2])"),
			row("clojure.string", "split", "(clojure.string/split \"a,b,c\" #\",\")"),
			row("clojure.string", "split-lines", "(clojure.string/split-lines \"a\\nb\")"),
			row("clojure.string", "upper-case", "(clojure.string/upper-case \"abc\")"),
			row("clojure.string", "lower-case", "(clojure.string/lower-case \"ABC\")"),
			row("clojure.string", "capitalize", "(clojure.string/capitalize \"hello\")"),
			row("clojure.string", "trim", "(clojure.string/trim \"  x  \")"),
			row("clojure.string", "triml", "(clojure.string/triml \"  x\")"),
			row("clojure.string", "trimr", "(clojure.string/trimr \"x  \")"),
			row("clojure.string", "blank?", "(clojure.string/blank? \"  \")"),
			row("clojure.string", "starts-with?", "(clojure.string/starts-with? \"abc\" \"ab\")"),
			row("clojure.string", "ends-with?", "(clojure.string/ends-with? \"abc\" \"bc\")"),
			row("clojure.string", "includes?", "(clojure.string/includes? \"abc\" \"b\")"),
			row("clojure.string", "index-of", "(clojure.string/index-of \"abc\" \"b\")"),
			row("clojure.string", "replace", "(clojure.string/replace \"aaa\" \"a\" \"b\")"),
			row("clojure.string", "reverse", "(clojure.string/reverse \"abc\")"),
			// clojure.set (needs its require: no oracle-boot namespace)
			row("clojure.set", "union", "(st/union #{1} #{2})"),
			row("clojure.set", "intersection", "(st/intersection #{1 2} #{2 3})"),
			row("clojure.set", "difference", "(st/difference #{1 2} #{2})"),
			row("clojure.set", "subset?", "(st/subset? #{1} #{1 2})"),
			row("clojure.set", "superset?", "(st/superset? #{1 2} #{1})"),
			row("clojure.set", "map-invert", "(st/map-invert {:a 1 :b 2})"),
			row("clojure.set", "rename-keys", "(st/rename-keys {:a 1} {:a :b})"),
			// clojure.walk
			row("clojure.walk", "postwalk", "(clojure.walk/postwalk #(if (number? %) (inc %) %) [1 [2 3]])"),
			row("clojure.walk", "prewalk", "(clojure.walk/prewalk #(if (number? %) (inc %) %) [1 [2 3]])"),
			// clojure.pprint and clojure.data (loaded at their require)
			row("clojure.pprint", "cl-format", "(pp/cl-format nil \"~a+~a\" 1 2)"),
			row("clojure.pprint", "pprint", "(with-out-str (pp/pprint [1 2]))"),
			row("clojure.data", "diff", "(d/diff {:a 1} {:a 2})"));

	private static final String REQUIRES = "(require '[clojure.set :as st] '[clojure.pprint :as pp] '[clojure.data :as d])\n";

	@TempDir
	static Path workDir;

	@TestFactory
	Stream<DynamicNode> parity() throws Exception {
		Map<Integer, String> oracle = runOracle();
		assumeTrue(oracle != null, "no usable clj on PATH; skipping differential parity");
		Map<Integer, String> interpreter = runOnInterpreter(program());
		Map<Integer, String> jvm = runOnJvm(program());
		boolean wasm = HostWasmtime.isAvailable();
		List<DynamicNode> legs = new ArrayList<>();
		legs.add(leg("INTERPRETER", oracle, interpreter));
		legs.add(leg("JVM", oracle, jvm));
		if (wasm) {
			legs.add(leg("WASM", oracle, runOnWasm(program(), false)));
			legs.add(leg("WASM_COMPONENT", oracle, runOnWasm(program(), true)));
		}
		else {
			legs.add(dynamicContainer("WASM",
					Stream.of(dynamicTest("(skipped)", () -> abort("no usable wasmtime on PATH")))));
			legs.add(dynamicContainer("WASM_COMPONENT",
					Stream.of(dynamicTest("(skipped)", () -> abort("no usable wasmtime on PATH")))));
		}
		return legs.stream();
	}

	private static DynamicContainer leg(String name, Map<Integer, String> oracle, Map<Integer, String> actual) {
		List<DynamicTest> tests = new ArrayList<>();
		for (int i = 0; i < ROWS.size(); i++) {
			Row row = ROWS.get(i);
			String expected = oracle.get(i);
			String got = actual.get(i);
			int index = i;
			tests.add(dynamicTest(row.label(), () -> {
				assertThat(got).as("row %d of %s has no output on %s%nform: %s", index, row.label(), name, row.form())
					.isNotNull();
				assertThat(expected).as("row %d of %s has no oracle output%nform: %s", index, row.label(), row.form())
					.isNotNull();
				if (row.knownDeviation() != null) {
					assertThat(got)
						.as("[%s] %s/%s deliberately prints %s, see .kb/clojure-frontend.md%nform: %s%noracle now prints: %s",
								name, row.ns(), row.var(), row.knownDeviation(), row.form(), expected)
						.isEqualTo(row.knownDeviation());
				}
				else {
					assertThat(got)
						.as("[%s] %s/%s diverged from the clj oracle%nform: %s%noracle: %s%rontolisp: %s", name,
								row.ns(), row.var(), row.form(), expected, got)
						.isEqualTo(expected);
				}
			}));
		}
		return dynamicContainer(name, tests.stream());
	}

	private static String program() {
		StringBuilder program = new StringBuilder(REQUIRES);
		for (int i = 0; i < ROWS.size(); i++) {
			program.append("(try (println (str \"@@ROW ").append(i).append(" \" (pr-str (pr-str (do ");
			program.append(ROWS.get(i).form());
			program.append("))))) (catch Exception e (println (str \"@@ROW ").append(i);
			program.append(" \" \"@@THROW \" (pr-str (ex-message e))))))\n");
		}
		return program.toString();
	}

	private static Map<Integer, String> parse(String output) {
		Map<Integer, String> rows = new HashMap<>();
		for (String line : output.split("\n")) {
			if (line.startsWith("@@ROW ")) {
				int space = line.indexOf(' ', "@@ROW ".length());
				rows.put(Integer.parseInt(line.substring("@ROW ".length() + 1, space)), line.substring(space + 1));
			}
		}
		return rows;
	}

	// Null when clj is absent or refuses to run: the factory skips like
	// CiSpecE2eTest does without its binary.
	private static @Nullable Map<Integer, String> runOracle() throws Exception {
		Path script = workDir.resolve("parity-oracle.clj");
		Files.writeString(script, program(), StandardCharsets.UTF_8);
		Process process;
		try {
			process = new ProcessBuilder("clj", "-M", script.toString()).directory(workDir.toFile())
				.redirectErrorStream(false)
				.start();
		}
		catch (IOException ex) {
			return null;
		}
		String stdout;
		String stderr;
		try {
			stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		}
		finally {
			if (!process.waitFor(300, TimeUnit.SECONDS)) {
				process.destroyForcibly().waitFor();
				throw new IllegalStateException("clj oracle timed out");
			}
		}
		if (process.exitValue() != 0) {
			return null;
		}
		if (!stderr.isEmpty()) {
			System.err.println("[differential-parity] clj stderr: " + stderr);
		}
		return parse(stdout);
	}

	private static Map<Integer, String> runOnInterpreter(String program) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("differential-parity", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : evaluator
				.clojureProgram(SourceLanguage.CLOJURE.read(program, Features.INTERPRETER, "parity.clj"))) {
				evaluator.eval(form);
			}
			return null;
		});
		return parse(out.toString(StandardCharsets.UTF_8));
	}

	private static Map<Integer, String> runOnJvm(String program) throws Exception {
		String name = "ClojureDifferentialParity";
		JvmSourceCompiler.Result compiled = new JvmSourceCompiler(name).sourceLanguage("clojure")
			.compile(program, null);
		byte[] classBytes = compiled.classBytes();
		Map<String, byte[]> parts = new HashMap<>();
		compiled.runtimeClasses()
			.forEach((path, bytes) -> parts.put(path.substring(0, path.length() - ".class".length()).replace('/', '.'),
					bytes));
		ClassLoader loader = new ClassLoader(ClojureDifferentialParityTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String n) throws ClassNotFoundException {
				if (n.equals(name)) {
					return defineClass(n, classBytes, 0, classBytes.length);
				}
				byte[] part = parts.get(n);
				if (part != null) {
					return defineClass(n, part, 0, part.length);
				}
				return super.findClass(n);
			}
		};
		Method main = loader.loadClass(name).getMethod("main", String[].class);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (var _ = ThreadStdio.out(out)) {
			CliStack.call("differential-parity", () -> main.invoke(null, (Object) new String[0]));
		}
		return parse(out.toString(StandardCharsets.UTF_8));
	}

	private static Map<Integer, String> runOnWasm(String program, boolean component) throws Exception {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(program, true, component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		String name = "clojure-parity" + (component ? "-component" : "");
		Path path = workDir.resolve(name + ".wasm");
		Files.write(path, module);
		Path outFile = Files.createTempFile(workDir, name, ".out");
		Path errFile = Files.createTempFile(workDir, name, ".err");
		try {
			Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--dir", "/tmp",
					path.toString())
				.redirectOutput(outFile.toFile())
				.redirectError(errFile.toFile())
				.start();
			if (!process.waitFor(300, TimeUnit.SECONDS)) {
				process.destroyForcibly().waitFor();
				throw new IllegalStateException("wasmtime command timed out: " + path);
			}
			String stderr = Files.readString(errFile, StandardCharsets.UTF_8);
			assertThat(process.exitValue()).as("wasmtime exit code: %s", stderr).isZero();
			return parse(Files.readString(outFile, StandardCharsets.UTF_8));
		}
		finally {
			Files.deleteIfExists(outFile);
			Files.deleteIfExists(errFile);
		}
	}

}
