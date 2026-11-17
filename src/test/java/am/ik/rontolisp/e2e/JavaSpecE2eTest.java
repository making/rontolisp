package am.ik.rontolisp.e2e;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.LispExitSignal;
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
 * Runs the corpus of the EXPERIMENTAL front end for a subset of the Java language
 * ({@code java-spec.yaml}) under the JDK running this test and on the interpreter, the
 * JVM, wasm and the component, and requires the same output of all five. The JDK is the
 * oracle: a case's expectation is what {@code java File.java} prints, and the first leg
 * checks that it still is. The {@code ci-spec.yaml} idea: the cases are concatenated into
 * ONE file per leg, behind a class whose {@code main} calls each case's in order, and the
 * output is sliced back per case, so a failure names its case and its leg. A
 * {@code standalone:} case is a file of its own, per leg: one that starts another way, or
 * one that ENDS with an uncaught exception -- it prints {@code stdout}, then reports
 * {@code error} after {@code Exception in thread "main"} on standard error and exits with
 * the status 1, on every leg; one whose file is a {@code .lisp} is a Common Lisp program
 * loading the Java files beside it, which has no JDK leg. A {@code refused:} case is a
 * program a compiler refuses: the JDK has to report {@code error} at {@code line}, and
 * the front end the same line and a message starting with it.
 *
 * <p>
 * Like {@code StarlarkSpecE2eTest} this needs no native binary: every leg goes through
 * the front end the CLI runs, in process, so it is part of {@code ./mvnw test}. The two
 * wasm legs need a {@code wasmtime} on {@code PATH} and are skipped without one.
 */
@Execution(ExecutionMode.CONCURRENT)
class JavaSpecE2eTest {

	private static final String SPEC_RESOURCE = "/java-spec.yaml";

	private static final String PROGRAM = "JavaSpec";

	record Case(String name, String main, String source, String expected) {
	}

	/**
	 * A case run as a file of its own.
	 *
	 * @param file the file's path, which the launch protocol reads: below the directories
	 * its package names
	 * @param error the text of the uncaught exception it ends with, or {@code null} when
	 * it ends normally
	 * @param stderr the whole of its standard error but a JDK's frame lines, or
	 * {@code null} when only the report of {@code error} is checked
	 * @param at where {@code error} was raised, as the line under the report ends on the
	 * interpreter and the JVM ({@code File.java:line in Class.method(types)}), or
	 * {@code null} when it is not checked
	 * @param files the other files of its source tree, by path, or {@code null}
	 * @param stdin what it reads from the standard input, or {@code null} for nothing
	 */
	record Standalone(String name, String file, String source, String stdout, @Nullable String error,
			@Nullable String stderr, @Nullable String at, @Nullable Map<String, String> files, @Nullable String stdin) {

		String stem() {
			return "Standalone" + this.file.replaceFirst("\\.[^./]*$", "").replace('/', '_');
		}

		/** Whether the program is a Common Lisp one, which no JDK runs. */
		boolean isLisp() {
			return this.file.endsWith(".lisp");
		}

		/**
		 * Writes the program's files under a directory of its own and answers the first.
		 */
		Path write(String leg) throws IOException {
			if (this.files != null) {
				for (Map.Entry<String, String> other : this.files.entrySet()) {
					JavaSpecE2eTest.write(leg, other.getKey(), other.getValue());
				}
			}
			return JavaSpecE2eTest.write(leg, this.file, this.source);
		}

	}

	/**
	 * A program that must not compile.
	 *
	 * @param body the statements of the scaffold's {@code main}, on one line
	 * @param error the first line of the compiler's message
	 */
	record Refused(String name, String body, String error) {
	}

	/**
	 * The programs that must not compile.
	 *
	 * @param scaffold the file every case is, with {@code @@} where its body goes
	 */
	record Refusals(String scaffold, List<Refused> cases) {

		String source(Refused refused) {
			return this.scaffold.replace(PLACEHOLDER, refused.body());
		}

		/** The line every case's error is on, from 1. */
		int line() {
			return splitLines(this.scaffold.substring(0, this.scaffold.indexOf(PLACEHOLDER))).size();
		}

	}

	private static final String PLACEHOLDER = "@@";

	record Spec(String imports, List<Case> cases, @Nullable List<Standalone> standalone, @Nullable Refusals refused) {

		String program() {
			StringBuilder program = new StringBuilder(this.imports);
			program.append("\npublic class ")
				.append(PROGRAM)
				.append(" {\n    public static void main(String[] args) {\n");
			for (Case c : this.cases) {
				program.append("        ").append(c.main()).append(".main(args);\n");
			}
			program.append("    }\n}\n");
			for (Case c : this.cases) {
				program.append('\n').append(c.source());
			}
			return program.toString();
		}

	}

	@TempDir
	static Path workDir;

	@TestFactory
	Stream<DynamicNode> e2e() throws Exception {
		Spec spec = loadSpec();
		String program = spec.program();
		boolean wasm = HostWasmtime.isAvailable();
		Callable<String> jdk = started(
				() -> runOnJdk(PROGRAM + ".java", write("JDK-" + PROGRAM, PROGRAM + ".java", program), null).stdout());
		Callable<String> interpreter = started(
				() -> interpret(write("INTERPRETER", PROGRAM + ".java", program), program));
		Callable<String> jvm = started(() -> runOnJvm(program));
		Callable<String> wasmPreview1 = wasm ? started(() -> runOnWasm(program, false)) : null;
		Callable<String> wasmComponent = wasm ? started(() -> runOnWasm(program, true)) : null;
		return Stream.of(backend("JDK", spec.cases(), jdk), backend("INTERPRETER", spec.cases(), interpreter),
				backend("JVM", spec.cases(), jvm), backend("WASM", spec.cases(), wasmPreview1),
				backend("WASM_COMPONENT", spec.cases(), wasmComponent));
	}

	@TestFactory
	Stream<DynamicNode> standalone() throws Exception {
		List<DynamicNode> legs = new ArrayList<>();
		List<Standalone> cases = loadSpec().standalone();
		for (Standalone s : cases == null ? List.<Standalone>of() : cases) {
			if (!s.isLisp()) {
				legs.add(dynamicTest(s.name() + " JDK", () -> {
					assertEnded(s, "JDK", runOnJdk(s.file(), s.write(s.stem() + "-JDK"), s.stdin()));
				}));
			}
			legs.add(dynamicTest(s.name() + " INTERPRETER", () -> standaloneOnInterpreter(s)));
			legs.add(dynamicTest(s.name() + " JVM", () -> standaloneOnJvm(s)));
			for (boolean component : List.of(false, true)) {
				String leg = component ? "WASM_COMPONENT" : "WASM";
				legs.add(dynamicTest(s.name() + " " + leg, () -> {
					if (!HostWasmtime.isAvailable()) {
						abort("no usable wasmtime on PATH");
					}
					Path file = s.write(s.stem() + "-" + leg);
					Path module = compileWasm(file, s.source(), component, s.stem());
					assertEnded(s, leg, ended(
							new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", module.toString())
								.start(),
							s.stdin()));
				}));
			}
		}
		return legs.stream();
	}

	@TestFactory
	Stream<DynamicNode> refused() throws Exception {
		List<DynamicNode> legs = new ArrayList<>();
		Refusals refusals = loadSpec().refused();
		if (refusals == null) {
			return Stream.empty();
		}
		int line = refusals.line();
		int index = 0;
		for (Refused r : refusals.cases()) {
			String file = "Refused" + (index++) + ".java";
			String source = refusals.source(r);
			legs.add(dynamicTest(r.name() + " JDK", () -> {
				Ended ended = runOnJdk(file, write("JDK-" + file.replace(".java", ""), file, source), null);
				assertThat(ended.stderr()).as("refused case '%s' under the JDK: %s", r.name(), r.body())
					.contains(file + ":" + line + ": error: " + r.error() + "\n");
			}));
			legs.add(dynamicTest(r.name() + " FRONT_END", () -> {
				try {
					SourceLanguage.JAVA.read(source, Features.INTERPRETER, file);
				}
				catch (LispReadException ex) {
					assertThat(ex.reason()).as("refused case '%s': %s", r.name(), r.body()).startsWith(r.error());
					assertThat(java.util.Objects.requireNonNull(ex.location()).line())
						.as("the line of '%s' in refused case '%s': %s", ex.reason(), r.name(), r.body())
						.isEqualTo(line);
					return;
				}
				fail("refused case '%s' was lowered: %s".formatted(r.name(), r.body()));
			}));
		}
		return legs.stream();
	}

	/**
	 * Where the target has one stack a started thread runs on the stack of the thread
	 * that waits for it, so a wait that only a thread suspended under the waiting one
	 * could end -- two threads taking turns -- cannot end: refused by name with the
	 * status 1, never gone on from as if it had. The JDK, the interpreter and the JVM run
	 * such a program (the corpus's {@code threads-wait-are-notified-and-interrupted}).
	 */
	@TestFactory
	Stream<DynamicNode> aWaitOnlyAThreadSuspendedUnderItCouldEndIsRefusedOnOneStack() {
		String source = """
				public class Turns {
				    static int turn;
				    static final Object table = new Object();

				    static Thread player(String word, int mine) {
				        return new Thread(() -> {
				            try {
				                for (int i = 0; i < 2; i++) {
				                    synchronized (table) {
				                        while (turn != mine) {
				                            table.wait();
				                        }
				                        System.out.println(word);
				                        turn = 1 - mine;
				                        table.notifyAll();
				                    }
				                }
				            }
				            catch (InterruptedException e) {
				                System.out.println("interrupted");
				            }
				        });
				    }

				    public static void main(String[] args) throws InterruptedException {
				        Thread ping = player("ping", 0);
				        Thread pong = player("pong", 1);
				        ping.start();
				        pong.start();
				        ping.join();
				        pong.join();
				        System.out.println("not reached");
				    }
				}
				""";
		return Stream.of(false, true).map(component -> {
			String leg = component ? "WASM_COMPONENT" : "WASM";
			return dynamicTest(leg, () -> {
				if (!HostWasmtime.isAvailable()) {
					abort("no usable wasmtime on PATH");
				}
				Path module = compileWasm(write("Turns-" + leg, "Turns.java", source), source, component, "turns");
				Ended ended = ended(
						new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", module.toString())
							.start());
				assertThat(ended.stdout()).as("%s", ended.stderr()).isEqualTo("ping\npong\n");
				assertThat(ended.stderr()).contains("a thread waiting for a thread suspended under it",
						"is not supported by the experimental Java subset yet");
				assertThat(ended.exit()).isEqualTo(1);
			});
		});
	}

	/** What a process printed and how it ended. */
	record Ended(String stdout, String stderr, int exit) {
	}

	private static Path write(String leg, String file, String source) throws IOException {
		Path dir = workDir.resolve(leg);
		Files.createDirectories(dir);
		Path path = dir.resolve(file);
		Files.createDirectories(java.util.Objects.requireNonNull(path.getParent()));
		Files.writeString(path, source, StandardCharsets.UTF_8);
		return path;
	}

	// The oracle: the source launcher of the JDK running this test, started where the
	// path of the file it is given begins.
	private static Ended runOnJdk(String file, Path written, @Nullable String stdin) throws Exception {
		Path dir = written;
		for (int i = file.split("/").length; i > 0; i--) {
			dir = java.util.Objects.requireNonNull(dir.getParent());
		}
		return ended(new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), file)
			.directory(dir.toFile())
			.start(), stdin);
	}

	private static Ended ended(Process process) throws Exception {
		return ended(process, null);
	}

	// What a process printed and how it ended, once it has read the standard input given.
	private static Ended ended(Process process, @Nullable String stdin) throws Exception {
		try (OutputStream in = process.getOutputStream()) {
			if (stdin != null) {
				in.write(stdin.getBytes(StandardCharsets.UTF_8));
			}
		}
		Path errors = Files.createTempFile(workDir, "java-spec", ".err");
		Thread drain = Thread.ofPlatform().start(() -> {
			try (InputStream err = process.getErrorStream()) {
				Files.write(errors, err.readAllBytes());
			}
			catch (IOException ex) {
				throw new java.io.UncheckedIOException(ex);
			}
		});
		String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!process.waitFor(300, TimeUnit.SECONDS)) {
			process.destroyForcibly().waitFor();
			throw new IllegalStateException("the process timed out");
		}
		drain.join();
		return new Ended(stdout, Files.readString(errors, StandardCharsets.UTF_8), process.exitValue());
	}

	private static void assertEnded(Standalone s, String leg, Ended ended) {
		String where = where(s, leg, ended.stderr());
		assertThat(ended.stdout()).as("%s", where).isEqualTo(s.stdout());
		if (s.stderr() != null) {
			assertThat(splitLines(ended.stderr()).stream()
				.filter(line -> !line.matches("\\s+(at |\\.\\.\\. \\d+ more).*"))
				.toList()).as("%s", where).containsExactlyElementsOf(splitLines(s.stderr()));
		}
		if (s.error() == null) {
			assertThat(ended.exit()).as("%s", where).isZero();
			return;
		}
		// the report first; the JDK's stack trace follows
		assertThat(splitLines(ended.stderr())).as("%s", where).first().isEqualTo(UNCAUGHT + s.error());
		assertThat(ended.exit()).as("%s: expected the status 1", where).isEqualTo(1);
		if (s.at() != null && (leg.equals("INTERPRETER") || leg.equals("JVM"))) {
			// where it was raised, under the report: the pipeline's own location line
			assertThat(splitLines(ended.stderr()).get(1)).as("%s", where).startsWith("  at ").endsWith(s.at());
		}
	}

	private static final String UNCAUGHT = "Exception in thread \"main\" ";

	private static String where(Standalone s, String leg, String stderr) {
		return "standalone case '%s' on %s%n--- source ---%n%s--- end source ---%n--- stderr ---%n%s"
			.formatted(s.name(), leg, s.source(), stderr);
	}

	private static void standaloneOnInterpreter(Standalone s) throws Exception {
		Path file = s.write(s.stem() + "-INTERPRETER");
		int[] status = new int[1];
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		try (ThreadStdio.Scope _ = ThreadStdio.err(err)) {
			CliStack.call("java-spec", () -> {
				LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8),
						new ByteArrayInputStream(
								(s.stdin() == null ? "" : s.stdin()).getBytes(StandardCharsets.UTF_8)));
				if (s.isLisp()) {
					// what the program loads is beside it, as the compiled legs' splice
					// reads it
					evaluator.setLoadBaseDir(java.util.Objects.requireNonNull(file.getParent()).toString());
				}
				try {
					for (LispVal form : SourceLanguage.forFile(file.toString(), null)
						.read(s.source(), Features.INTERPRETER, file.toString(), SourceStandards.DEFAULT,
								evaluator.sourceLoader())) {
						evaluator.eval(form);
					}
				}
				catch (LispExitSignal exit) {
					status[0] = exit.code();
				}
				return null;
			});
		}
		assertEnded(s, "INTERPRETER", new Ended(out.toString(StandardCharsets.UTF_8),
				err.toString(java.nio.charset.Charset.defaultCharset()), status[0]));
	}

	// The program may die, so it runs in a process of its own: with the class and the
	// runtime classes travelling beside it in its class path directory, as the CLI
	// writes them.
	private static void standaloneOnJvm(Standalone s) throws Exception {
		Path file = s.write(s.stem() + "-JVM");
		Path dir = workDir.resolve(s.stem());
		Files.createDirectories(dir);
		JvmSourceCompiler.Result compiled = new JvmSourceCompiler(s.stem())
			.baseDir(java.util.Objects.requireNonNull(file.getParent()).toString())
			.compile(s.source(), file.toString());
		Files.write(dir.resolve(s.stem() + ".class"), compiled.classBytes());
		for (Map.Entry<String, byte[]> runtime : compiled.runtimeClasses().entrySet()) {
			Path travelling = dir.resolve(runtime.getKey());
			Files.createDirectories(java.util.Objects.requireNonNull(travelling.getParent()));
			Files.write(travelling, runtime.getValue());
		}
		assertEnded(s, "JVM",
				ended(new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
						dir.toString(), s.stem())
					.start(), s.stdin()));
	}

	// Starts run on a thread of its own and answers its result, rethrowing what it threw.
	private static Callable<String> started(Callable<String> run) {
		FutureTask<String> task = new FutureTask<>(run);
		Thread.ofPlatform().name("java-spec-corpus").start(task);
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
		int expectedTotal = cases.stream().mapToInt(c -> splitLines(c.expected()).size()).sum();
		tests.add(dynamicTest("total-line-count",
				() -> assertThat(actual).as("%s produced a different number of output lines than the spec", leg)
					.hasSize(expectedTotal)));
		int offset = 0;
		for (Case c : cases) {
			List<String> expected = splitLines(c.expected());
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

	private static String interpret(Path file, String program) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("java-spec", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : SourceLanguage.JAVA.read(program, Features.INTERPRETER, file.toString())) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnJvm(String program) throws Exception {
		String name = "JavaSpecProgram";
		Path file = write("JVM", PROGRAM + ".java", program);
		byte[] classBytes = new JvmSourceCompiler(name).compile(program, file.toString()).classBytes();
		ClassLoader loader = new ClassLoader(JavaSpecE2eTest.class.getClassLoader()) {
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
			CliStack.call("java-spec", () -> main.invoke(null, (Object) new String[0]));
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String runOnWasm(String program, boolean component) throws Exception {
		String leg = component ? "WASM_COMPONENT" : "WASM";
		Path module = compileWasm(write(leg, PROGRAM + ".java", program), program, component, "java-spec");
		Ended ended = ended(
				new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", module.toString()).start());
		assertThat(ended.exit()).as("wasmtime exit code (%s): %s", module, ended.stderr()).isZero();
		return ended.stdout();
	}

	private static Path compileWasm(Path file, String program, boolean component, String name) throws IOException {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.file(program, file.toString(), true, component,
				null);
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
		try (InputStream in = JavaSpecE2eTest.class.getResourceAsStream(SPEC_RESOURCE)) {
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
