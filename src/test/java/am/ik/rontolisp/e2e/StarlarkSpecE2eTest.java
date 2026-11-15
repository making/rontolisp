package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceStandards;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.ThreadStdio;
import am.ik.rontolisp.testsupport.YamlResources;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Runs the corpus of the EXPERIMENTAL Starlark front end ({@code starlark-spec.yaml}) on
 * the interpreter, the JVM, wasm and the component, and requires the same output of all
 * four. The {@code ci-spec.yaml} idea: the cases are concatenated into ONE {@code .star}
 * program per backend (they share its globals and run in order) and the output is sliced
 * back per case, so a failure names its case and its backend. A {@code standalone:} case
 * ENDS its program with an uncaught error, so each one is compiled and run on its own,
 * per backend: it prints {@code stdout}, then fails with {@code error}, which the
 * interpreter throws and a compiled program reports first on standard error; it may name
 * the {@code standard} it is read against. {@link #conformance} runs
 * bazelbuild/starlark's conformance corpus the same four ways, one program per corpus
 * file.
 *
 * <p>
 * Unlike {@code CiSpecE2eTest} this needs no native binary: every leg goes through the
 * same front end the CLI runs ({@code SourceLanguage.STARLARK},
 * {@code JvmSourceCompiler}, {@code CompileFrontendAccess}), in process, so it is part of
 * {@code ./mvnw test}. The two wasm legs need a {@code wasmtime} on {@code PATH} and are
 * skipped without one.
 */
@Execution(ExecutionMode.CONCURRENT)
class StarlarkSpecE2eTest {

	private static final String SPEC_RESOURCE = "/starlark-spec.yaml";

	private static final String UNHANDLED = "Unhandled condition: ";

	record Case(String name, String source, String expected) {

		List<String> expectedLines() {
			return splitLines(this.expected);
		}

	}

	/**
	 * A case that ends with an uncaught error.
	 *
	 * @param standard the {@code --starlark-standard} it is read against, or {@code null}
	 * for the default
	 */
	record Standalone(String name, String source, String stdout, String error, @Nullable String standard) {

		String stem() {
			return "Standalone" + this.name.replaceAll("[^A-Za-z0-9]", "");
		}

	}

	record Spec(List<Case> cases, @Nullable List<Standalone> standalone) {

		String program() {
			StringBuilder program = new StringBuilder();
			for (Case c : this.cases) {
				program.append(c.source());
			}
			return program.toString();
		}

	}

	@TempDir
	static Path workDir;

	/**
	 * bazelbuild/starlark's {@code test_suite/testdata}: a file of chunks separated by
	 * {@code ---} lines, each chunk a program of its own.
	 */
	private static final String CORPUS = "/starlark-conformance";

	/**
	 * What the corpus's harness ({@code test_suite/starlark_test.py}) puts ahead of every
	 * chunk: its assertions are Starlark, not the language's.
	 */
	private static final String PRELUDE = """
			def assert_eq(x, y):
			  if x != y:
			    print("%r != %r" % (x, y))

			def assert_ne(x, y):
			  if x == y:
			    print("%r == %r" % (x, y))

			def assert_(cond, msg="assertion failed"):
			  if not cond:
			    print(msg)
			""";

	/**
	 * The chunks that do not pass yet, each with why. A chunk named here must still fail,
	 * so the one a change fixes is taken off.
	 */
	private static final Map<String, String> KNOWN_FAILURES = Map.of("go/builtins.star:64",
			"the corpus matches 'Error in sorted', the line Go's and Java's tracebacks print, not the message");

	/**
	 * What the corpus is read against: the specification's rules, the Go implementation's
	 * default, which the corpus is written for.
	 */
	private static final SourceStandards CORPUS_STANDARDS = SourceStandards.parse(null, "spec");

	/** The line a program prints after each chunk: its name, then its error if any. */
	private static final String CHUNK_MARK = "%%chunk ";

	private static final String CHUNK_ERROR = " error: ";

	private static final Pattern EXPECTED_ERROR = Pattern.compile("(.*?) *### *((go|java|rust):)? *(.*)");

	@TestFactory
	Stream<DynamicNode> e2e() throws Exception {
		Spec spec = loadSpec();
		String program = spec.program();
		boolean wasm = HostWasmtime.isAvailable();
		Callable<String> interpreter = started(() -> interpret(program));
		Callable<String> jvm = started(() -> runOnJvm(program));
		Callable<String> wasmPreview1 = wasm ? started(() -> runOnWasm(program, false)) : null;
		Callable<String> wasmComponent = wasm ? started(() -> runOnWasm(program, true)) : null;
		return Stream.of(backend("INTERPRETER", spec.cases(), interpreter), backend("JVM", spec.cases(), jvm),
				backend("WASM", spec.cases(), wasmPreview1), backend("WASM_COMPONENT", spec.cases(), wasmComponent));
	}

	@TestFactory
	Stream<DynamicNode> standalone() throws Exception {
		List<DynamicNode> legs = new ArrayList<>();
		List<Standalone> cases = loadSpec().standalone();
		for (Standalone s : cases == null ? List.<Standalone>of() : cases) {
			legs.add(dynamicTest(s.name() + " INTERPRETER", () -> standaloneOnInterpreter(s)));
			legs.add(dynamicTest(s.name() + " JVM", () -> standaloneOnJvm(s)));
			for (boolean component : List.of(false, true)) {
				legs.add(dynamicTest(s.name() + (component ? " WASM_COMPONENT" : " WASM"), () -> {
					if (!HostWasmtime.isAvailable()) {
						abort("no usable wasmtime on PATH");
					}
					Path module = compileWasm(CompileFrontendAccess.starlark(s.source(), true, component, s.standard()),
							component, s.stem());
					Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
							module.toString())
						.start();
					assertEnded(s, component ? "WASM_COMPONENT" : "WASM", process);
				}));
			}
		}
		return legs.stream();
	}

	/**
	 * The conformance corpus: every file one program per backend, each chunk in it
	 * lowered on its own (the {@link #PRELUDE} first), its names given a spelling of
	 * their own and every top-level statement guarded, so the chunk stops at its first
	 * error and the next one runs. A chunk passes when it prints nothing (no assertion
	 * failed) and fails exactly when the corpus expects an error, with a message one of
	 * the corpus's patterns matches; the compiled legs must print what the interpreter
	 * prints.
	 */
	@TestFactory
	Stream<DynamicNode> conformance() throws Exception {
		Path root = Path.of(Objects.requireNonNull(StarlarkSpecE2eTest.class.getResource(CORPUS)).toURI());
		List<Path> files;
		try (Stream<Path> walk = Files.walk(root)) {
			files = walk.filter(path -> path.toString().endsWith(".star")).sorted().toList();
		}
		boolean wasm = HostWasmtime.isAvailable();
		ExecutorService pool = Executors.newFixedThreadPool(4);
		List<DynamicNode> nodes = new ArrayList<>();
		for (Path file : files) {
			String name = root.relativize(file).toString().replace('\\', '/');
			List<Chunk> chunks = chunks(name, Files.readString(file, StandardCharsets.UTF_8));
			List<LispVal> program = conformanceProgram(chunks);
			String stem = "Conformance" + name.replaceAll("[^A-Za-z0-9]", "");
			Future<String> interpreter = pool.submit(() -> interpretForms(program));
			Future<String> jvm = pool.submit(() -> runClass(stem,
					CliStack.call(stem,
							() -> CompileFrontendAccess
								.jvmClass(stem, CompileFrontendAccess.lowered(program, false, false))
								.classBytes())));
			Future<String> wasmPreview1 = wasm ? pool
				.submit(() -> runModule(compileWasm(CompileFrontendAccess.lowered(program, true, false), false, stem)))
					: null;
			Future<String> wasmComponent = wasm ? pool
				.submit(() -> runModule(compileWasm(CompileFrontendAccess.lowered(program, true, true), true, stem)))
					: null;
			nodes.add(dynamicContainer(name,
					Stream.of(conformanceLeg("INTERPRETER", chunks, interpreter, null),
							conformanceLeg("JVM", chunks, jvm, interpreter),
							conformanceLeg("WASM", chunks, wasmPreview1, interpreter),
							conformanceLeg("WASM_COMPONENT", chunks, wasmComponent, interpreter))));
		}
		pool.shutdown();
		return nodes.stream();
	}

	/**
	 * A chunk of the corpus.
	 *
	 * @param name its file and the line it starts on
	 * @param source its text, the expected-error comments taken off
	 * @param patterns what its error message must match, any one of them: the corpus's
	 * {@code ###} patterns, an implementation's own ({@code go:}, {@code java:},
	 * {@code rust:}) included
	 * @param expectsError whether it must fail: when it has a pattern for every
	 * implementation or one for Go, whose messages the front end's follow
	 */
	record Chunk(String name, String source, List<String> patterns, boolean expectsError) {

		boolean matches(String message) {
			String text = message.toLowerCase(Locale.ROOT);
			for (String pattern : this.patterns) {
				String wanted = pattern.toLowerCase(Locale.ROOT);
				if (text.contains(wanted)) {
					return true;
				}
				if (Pattern.compile(pythonRegex(wanted)).matcher(text).find()) {
					return true;
				}
			}
			return false;
		}

	}

	/**
	 * A pattern as Python's {@code re} reads it: a brace that starts no repetition is a
	 * brace, where Java's regular expressions refuse it.
	 */
	private static String pythonRegex(String pattern) {
		try {
			Pattern.compile(pattern);
			return pattern;
		}
		catch (PatternSyntaxException ex) {
			return pattern.replace("{", "\\{").replace("}", "\\}");
		}
	}

	/** What a chunk printed (an assertion that failed) and the error it stopped at. */
	record Outcome(List<String> printed, @Nullable String error) {
	}

	/** Splits a corpus file into its chunks, as {@code starlark_test.py} does. */
	static List<Chunk> chunks(String file, String text) {
		List<Chunk> chunks = new ArrayList<>();
		StringBuilder source = new StringBuilder();
		List<String> patterns = new ArrayList<>();
		boolean expectsError = false;
		int start = 1;
		List<String> lines = text.lines().toList();
		for (int i = 0; i <= lines.size(); i++) {
			String line = i < lines.size() ? lines.get(i).stripTrailing() : "---";
			if (line.equals("---")) {
				chunks.add(new Chunk(file + ":" + start, source.toString(), List.copyOf(patterns), expectsError));
				source.setLength(0);
				patterns.clear();
				expectsError = false;
				start = i + 2;
				continue;
			}
			Matcher m = EXPECTED_ERROR.matcher(line);
			if (m.matches()) {
				patterns.add(m.group(4));
				expectsError |= m.group(3) == null || m.group(3).equals("go");
				line = m.group(1);
			}
			source.append(line).append('\n');
		}
		return chunks;
	}

	/**
	 * The program a corpus file runs as: a reporting function, then every chunk's forms
	 * behind a guard, then its report.
	 */
	private static List<LispVal> conformanceProgram(List<Chunk> chunks) {
		List<LispVal> forms = new ArrayList<>(SourceLanguage.COMMON_LISP.read("""
				(defun corpus-report (name failure)
				  (princ "%s")
				  (princ name)
				  (if failure
				      (progn (princ "%s") (princ (substitute #\\Space #\\Newline failure))))
				  (terpri))
				""".formatted(CHUNK_MARK, CHUNK_ERROR), Features.INTERPRETER, null));
		LispSymbol failure = new LispSymbol("CORPUS-FAILURE");
		LispSymbol report = new LispSymbol("CORPUS-REPORT");
		for (int i = 0; i < chunks.size(); i++) {
			Chunk chunk = chunks.get(i);
			List<LispVal> lowered;
			try {
				lowered = SourceLanguage.STARLARK.read(PRELUDE + chunk.source(), Features.INTERPRETER, chunk.name(),
						CORPUS_STANDARDS);
			}
			catch (LispReadException ex) {
				forms.add(list(report, LispString.literal(chunk.name()),
						LispString.literal(String.valueOf(ex.getMessage()))));
				continue;
			}
			forms.add(list(new LispSymbol("SETQ"), failure, LispNil.INSTANCE));
			String prefix = "c" + i + "%";
			for (LispVal form : lowered) {
				LispVal own = renamed(form, prefix);
				boolean definition = own instanceof LispCons cons && cons.car() instanceof LispSymbol head
						&& head.name().equals("DEFUN");
				LispSymbol e = new LispSymbol("E");
				forms.add(definition ? own : list(new LispSymbol("IF"), list(new LispSymbol("NULL"), failure),
						list(new LispSymbol("HANDLER-CASE"), own, list(new LispSymbol("ERROR"), list(e),
								list(new LispSymbol("SETQ"), failure, list(new LispSymbol("PRINC-TO-STRING"), e))))));
			}
			forms.add(list(report, LispString.literal(chunk.name()), failure));
		}
		return forms;
	}

	/**
	 * A form with every name the program spells -- each symbol the lowering spells with a
	 * lowercase letter ({@code StarlarkNames}) -- behind a prefix: the chunk's own
	 * globals.
	 */
	private static LispVal renamed(LispVal form, String prefix) {
		if (form instanceof LispSymbol symbol && !symbol.name().equals(symbol.name().toUpperCase(Locale.ROOT))) {
			return new LispSymbol(prefix + symbol.name());
		}
		if (form instanceof LispCons cons) {
			return new LispCons(renamed(cons.car(), prefix), renamed(cons.cdr(), prefix));
		}
		return form;
	}

	private static LispVal list(LispVal... elements) {
		LispVal list = LispNil.INSTANCE;
		for (int i = elements.length - 1; i >= 0; i--) {
			list = new LispCons(elements[i], list);
		}
		return list;
	}

	/** The outcome of each chunk a program's output reports, by chunk name. */
	static Map<String, Outcome> outcomes(String output) {
		Map<String, Outcome> outcomes = new LinkedHashMap<>();
		List<String> printed = new ArrayList<>();
		for (String line : splitLines(output)) {
			if (!line.startsWith(CHUNK_MARK)) {
				printed.add(line);
				continue;
			}
			String rest = line.substring(CHUNK_MARK.length());
			int error = rest.indexOf(CHUNK_ERROR);
			outcomes.put(error < 0 ? rest : rest.substring(0, error),
					new Outcome(List.copyOf(printed), error < 0 ? null : rest.substring(error + CHUNK_ERROR.length())));
			printed.clear();
		}
		return outcomes;
	}

	/** Why a chunk fails, or {@code null} when it passes. */
	static @Nullable String verdict(Chunk chunk, @Nullable Outcome outcome) {
		if (outcome == null) {
			return "no report: the program stopped before it";
		}
		if (!outcome.printed().isEmpty()) {
			return "printed " + outcome.printed();
		}
		if (chunk.expectsError()) {
			if (outcome.error() == null) {
				return "no error, expected one matching " + chunk.patterns();
			}
			if (!chunk.matches(outcome.error())) {
				return "error '" + outcome.error() + "' matches none of " + chunk.patterns();
			}
		}
		else if (outcome.error() != null) {
			return "unexpected error '" + outcome.error() + "'";
		}
		return null;
	}

	// run is null when there is no wasmtime to run it on; reference, the interpreter's
	// output, is what a compiled leg must print.
	private static DynamicContainer conformanceLeg(String leg, List<Chunk> chunks, @Nullable Future<String> run,
			@Nullable Future<String> reference) {
		if (run == null) {
			return dynamicContainer(leg,
					Stream.of(dynamicTest("(skipped)", () -> abort("no usable wasmtime on PATH"))));
		}
		List<DynamicTest> tests = new ArrayList<>();
		if (reference != null) {
			tests.add(dynamicTest("prints what the interpreter prints",
					() -> assertThat(splitLines(run.get())).containsExactlyElementsOf(splitLines(reference.get()))));
		}
		for (Chunk chunk : chunks) {
			tests.add(dynamicTest(chunk.name(), () -> {
				String why = verdict(chunk, outcomes(run.get()).get(chunk.name()));
				String known = KNOWN_FAILURES.get(chunk.name());
				if (known != null) {
					assertThat(why)
						.as("%s on %s is known to fail (%s) and passes now: take it off KNOWN_FAILURES", chunk.name(),
								leg, known)
						.isNotNull();
				}
				else {
					assertThat(why)
						.as("chunk %s on %s%n--- source ---%n%s--- end source ---", chunk.name(), leg, chunk.source())
						.isNull();
				}
			}));
		}
		return dynamicContainer(leg, tests.stream());
	}

	private static String interpretForms(List<LispVal> program) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("starlark-conformance", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : program) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private static void standaloneOnInterpreter(Standalone s) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Throwable[] thrown = new Throwable[1];
		CliStack.call("starlark-spec", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			try {
				for (LispVal form : SourceLanguage.STARLARK.read(s.source(), Features.INTERPRETER, null,
						SourceStandards.parse(null, s.standard()))) {
					evaluator.eval(form);
				}
			}
			catch (Throwable ex) {
				thrown[0] = ex;
			}
			return null;
		});
		String where = where(s, "INTERPRETER", "");
		assertThat(out.toString(StandardCharsets.UTF_8)).as("%s", where).isEqualTo(s.stdout());
		assertThat(thrown[0]).as("%s: expected a failure", where).isNotNull().hasMessage(s.error());
	}

	// The program dies, so it runs in a process of its own: with the class and the
	// runtime classes travelling beside it in its class path directory, as the CLI
	// writes them.
	private static void standaloneOnJvm(Standalone s) throws Exception {
		Path dir = workDir.resolve(s.stem());
		Files.createDirectories(dir);
		JvmSourceCompiler.Result compiled = new JvmSourceCompiler(s.stem()).sourceLanguage("starlark")
			.starlarkStandard(s.standard())
			.compile(s.source(), null);
		Files.write(dir.resolve(s.stem() + ".class"), compiled.classBytes());
		for (Map.Entry<String, byte[]> runtime : compiled.runtimeClasses().entrySet()) {
			Path file = dir.resolve(runtime.getKey());
			Files.createDirectories(file.getParent());
			Files.write(file, runtime.getValue());
		}
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
				dir.toString(), s.stem())
			.start();
		assertEnded(s, "JVM", process);
	}

	private static void assertEnded(Standalone s, String leg, Process process) throws Exception {
		process.getOutputStream().close();
		Path errors = Files.createTempFile(workDir, s.stem(), ".err");
		Thread drain = Thread.ofPlatform().start(() -> {
			try (InputStream err = process.getErrorStream()) {
				Files.write(errors, err.readAllBytes());
			}
			catch (IOException ex) {
				throw new java.io.UncheckedIOException(ex);
			}
		});
		String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(300, TimeUnit.SECONDS)).as("%s on %s timed out", s.name(), leg).isTrue();
		drain.join();
		String stderr = Files.readString(errors, StandardCharsets.UTF_8);
		String where = where(s, leg, stderr);
		assertThat(stdout).as("%s", where).isEqualTo(s.stdout());
		// the report first; what the host adds about the dead process follows
		assertThat(splitLines(stderr)).as("%s", where).first().isEqualTo(UNHANDLED + s.error());
		assertThat(process.exitValue()).as("%s: expected a non-zero exit", where).isNotZero();
	}

	private static String where(Standalone s, String leg, String stderr) {
		return "standalone case '%s' on %s%n--- source ---%n%s--- end source ---%n--- stderr ---%n%s"
			.formatted(s.name(), leg, s.source(), stderr);
	}

	// Starts run on a thread of its own and answers its result, rethrowing what it threw.
	private static Callable<String> started(Callable<String> run) {
		FutureTask<String> task = new FutureTask<>(run);
		Thread.ofPlatform().name("starlark-spec-corpus").start(task);
		return () -> {
			try {
				return task.get();
			}
			catch (ExecutionException ex) {
				if (ex.getCause() instanceof Exception cause) {
					throw cause;
				}
				if (ex.getCause() instanceof Error cause) {
					throw cause;
				}
				throw ex;
			}
		};
	}

	// run is null when there is no wasmtime to run it on.
	private static DynamicContainer backend(String leg, List<Case> cases, @Nullable Callable<String> run) {
		if (run == null) {
			return dynamicContainer(leg,
					Stream.of(dynamicTest("(skipped)", () -> abort("no usable wasmtime on PATH"))));
		}
		List<String> actual;
		try {
			actual = splitLines(run.call());
		}
		catch (Exception | StackOverflowError ex) {
			return dynamicContainer(leg, Stream.of(dynamicTest("(execution failed)", () -> fail(leg + ": " + ex, ex))));
		}
		List<DynamicTest> tests = new ArrayList<>();
		int expectedTotal = cases.stream().mapToInt(c -> c.expectedLines().size()).sum();
		tests.add(dynamicTest("total-line-count",
				() -> assertThat(actual).as("%s produced a different number of output lines than the spec", leg)
					.hasSize(expectedTotal)));
		int offset = 0;
		for (Case c : cases) {
			List<String> expected = c.expectedLines();
			int start = Math.min(offset, actual.size());
			List<String> slice = actual.subList(start, Math.min(offset + expected.size(), actual.size()));
			offset += expected.size();
			tests.add(dynamicTest(c.name(),
					() -> assertThat(slice)
						.as("case '%s' on %s%n--- source ---%n%s--- end source ---", c.name(), leg, c.source())
						.containsExactlyElementsOf(expected)));
		}
		return dynamicContainer(leg, tests.stream());
	}

	private static String interpret(String program) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("starlark-spec", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : SourceLanguage.STARLARK.read(program, Features.INTERPRETER, "spec.star")) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnJvm(String program) throws Exception {
		String name = "StarlarkSpec";
		return runClass(name,
				new JvmSourceCompiler(name).sourceLanguage("starlark").compile(program, null).classBytes());
	}

	private static String runClass(String name, byte[] classBytes) throws Exception {
		ClassLoader loader = new ClassLoader(StarlarkSpecE2eTest.class.getClassLoader()) {
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
		try (ThreadStdio.Scope _ = ThreadStdio.out(out)) {
			CliStack.call("starlark-spec", () -> main.invoke(null, (Object) new String[0]));
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnWasm(String program, boolean component) throws Exception {
		return runModule(compileWasm(program, component, "starlark-spec"));
	}

	private static String runModule(Path module) throws Exception {
		Path outFile = Files.createTempFile(workDir, "starlark-spec", ".out");
		Path errFile = Files.createTempFile(workDir, "starlark-spec", ".err");
		Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", module.toString())
			.redirectOutput(outFile.toFile())
			.redirectError(errFile.toFile())
			.start();
		if (!process.waitFor(300, TimeUnit.SECONDS)) {
			process.destroyForcibly().waitFor();
			throw new IllegalStateException("wasmtime command timed out: " + module);
		}
		assertThat(process.exitValue())
			.as("wasmtime exit code (%s): %s", module, Files.readString(errFile, StandardCharsets.UTF_8))
			.isZero();
		return Files.readString(outFile, StandardCharsets.UTF_8);
	}

	private static Path compileWasm(String program, boolean component, String name) throws IOException {
		return compileWasm(CompileFrontendAccess.starlark(program, true, component, null), component, name);
	}

	private static Path compileWasm(CompileFrontendAccess.Program frontend, boolean component, String name)
			throws IOException {
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		Path path = workDir.resolve(name + (component ? ".component.wasm" : ".wasm"));
		Files.write(path, module);
		return path;
	}

	private static Spec loadSpec() throws IOException {
		try (InputStream in = StarlarkSpecE2eTest.class.getResourceAsStream(SPEC_RESOURCE)) {
			if (in == null) {
				throw new IOException("missing test resource: " + SPEC_RESOURCE);
			}
			return new YAMLMapper()
				.readValue(YamlResources.safeReader(new String(in.readAllBytes(), StandardCharsets.UTF_8)), Spec.class);
		}
	}

	private static List<String> splitLines(String text) {
		if (text.isEmpty()) {
			return List.of();
		}
		List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
		if (lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}
		return lines;
	}

}
