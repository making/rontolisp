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
import java.time.Duration;
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
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.JavaTestOracle;
import am.ik.rontolisp.testsupport.ThreadStdio;
import am.ik.rontolisp.testsupport.YamlResources;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
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
 * wasm legs need a {@code wasmtime} on {@code PATH} and are skipped without one; a run of
 * tests needs JUnit Jupiter's sources from Maven's local repository or Central
 * ({@link #junitSources()}) and is skipped without them, as a case naming
 * {@code dependencies} is without theirs; a case's host-log leg needs {@code wasm-tools}
 * too, and is skipped without it.
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
	 * @param warnings what a compiler warns of before the program runs: the start of the
	 * standard error under the JDK and the interpreter, what a compile prints for the JVM
	 * and wasm; {@code null} when not checked
	 * @param library the files of a library the program uses, by path, or {@code null}: a
	 * sources jar on the program's source path, classes the JDK leg compiles first, with
	 * any file that is no {@code .java} a resource beside them
	 * @param provided what the library was compiled against and its sources jar does not
	 * hold, by path, or {@code null}: only the JDK leg's compile of the library reads it
	 * @param directory whether it runs in the directory its files are written to, on
	 * every leg: the interpreter then in a process of its own (a process cannot change
	 * where it runs), a wasm leg with that directory preopened under its own name
	 * @param modes the permissions its files and directories are given after they are
	 * written, by path ({@code r-xr-xr-x}; a path ending in a slash is a directory, made
	 * empty), or {@code null}: its legs are skipped where the user is root, whom no
	 * permission refuses
	 * @param links the symbolic links made after its files are written and before their
	 * modes are given, by path, each to the target it holds, or {@code null}: no leg
	 * makes a link
	 * @param tests the binary names of the file's test classes, in the order declared, or
	 * {@code null} for a program: the file is then run as {@code rontolisp test} runs it,
	 * and under the JDK by JUnit Jupiter and AssertJ themselves ({@link JavaTestOracle}),
	 * reported in the same shape
	 * @param status the status it ends with where no uncaught exception ends it, or
	 * {@code null} for 0: 1 for a run of tests one of which fails
	 * @param arguments the program's command-line arguments, the same on every leg, or
	 * {@code null} for none
	 * @param dependencies the Maven coordinates of the libraries the program uses, or
	 * {@code null}: their sources jars and those of what they depend on at run time on
	 * the program's source path, as {@code --java-source-dep} puts them there, and the
	 * jars themselves on the JDK leg's class path
	 * @param hostLog what the host's log is handed where the build gives it the records
	 * ({@code --wasi-logging}), or {@code null}: the component is then also compiled so,
	 * composed with the test's host of {@code wasi:logging}, which writes a record a line
	 * on the standard error ({@code testsupport/wasi-logging-host}), and run; it prints
	 * {@code stdout} and its standard error is this
	 * @param exists whether each path, below the directory the program runs in, is there
	 * once it ended -- what it deletes at its end, which nothing of it can see -- or
	 * {@code null}
	 * @param wasi what the lines of {@code stdout} that begin with each label and a colon
	 * print after it on the two WASI legs, where a WASI host cannot answer as a JDK does
	 * ({@code doc/en/java/deviations.md}), or {@code null}
	 */
	record Standalone(String name, String file, String source, String stdout, @Nullable String error,
			@Nullable String stderr, @Nullable String at, @Nullable Map<String, String> files, @Nullable String stdin,
			@Nullable String warnings, @Nullable Map<String, String> library, @Nullable Map<String, String> provided,
			@Nullable Boolean directory, @Nullable Map<String, String> modes, @Nullable Map<String, String> links,
			@Nullable List<String> tests, @Nullable Integer status, @Nullable List<String> arguments,
			@Nullable List<String> dependencies, @Nullable String hostLog, @Nullable Map<String, Boolean> exists,
			@Nullable Map<String, String> wasi) {

		/**
		 * What the case prints on a leg: {@code stdout}, each line {@code wasi} labels
		 * given its text on a WASI leg. A label no line has fails the case.
		 */
		String stdout(String leg) {
			if (this.wasi == null || !leg.startsWith("WASM")) {
				return this.stdout;
			}
			List<String> lines = new ArrayList<>();
			java.util.Set<String> given = new java.util.HashSet<>();
			for (String line : this.stdout.split("\n", -1)) {
				int colon = line.indexOf(": ");
				String label = colon < 0 ? "" : line.substring(0, colon);
				String text = this.wasi.get(label);
				if (text != null) {
					given.add(label);
					line = label + ": " + text;
				}
				lines.add(line);
			}
			assertThat(given).as("the labels of %s's wasi", this.name).isEqualTo(this.wasi.keySet());
			return String.join("\n", lines);
		}

		/**
		 * The name of the library's sources jar, which a location line ends a path of.
		 */
		static final String SOURCES_JAR = "library-sources.jar";

		/**
		 * The program's source path on a leg: the library's sources jar, written beside
		 * the leg's directory, the sources of its dependencies, and for a run of tests
		 * JUnit Jupiter's sources.
		 */
		List<String> sourcePath(String leg) throws IOException {
			List<String> roots = new ArrayList<>(this.tests == null ? List.of() : junitSources());
			if (this.dependencies != null) {
				roots.addAll(resolved(this.dependencies, true));
			}
			if (this.library == null) {
				return roots;
			}
			Path jar = workDir.resolve(leg + "-library").resolve(SOURCES_JAR);
			Files.createDirectories(java.util.Objects.requireNonNull(jar.getParent()));
			try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
				for (Map.Entry<String, String> source : this.library.entrySet()) {
					zip.putNextEntry(new java.util.zip.ZipEntry(source.getKey()));
					zip.write(source.getValue().getBytes(StandardCharsets.UTF_8));
					zip.closeEntry();
				}
			}
			roots.add(0, jar.toString());
			return roots;
		}

		/**
		 * The library as the JDK leg has it: its classes, compiled against what it was
		 * compiled against, which is not where the program runs. {@code null} when the
		 * program uses none.
		 */
		@Nullable Path classes(String leg) throws Exception {
			if (this.library == null) {
				return null;
			}
			Path classes = workDir.resolve(leg + "-library").resolve("classes");
			Files.createDirectories(classes);
			String javac = Path.of(System.getProperty("java.home"), "bin", "javac").toString();
			List<String> command = new ArrayList<>(List.of(javac, "-d", classes.toString()));
			if (this.provided != null) {
				Path provided = workDir.resolve(leg + "-library").resolve("provided");
				List<String> compile = new ArrayList<>(List.of(javac, "-d", provided.toString()));
				for (Map.Entry<String, String> source : this.provided.entrySet()) {
					compile.add(JavaSpecE2eTest.write(leg + "-provided/src", source.getKey(), source.getValue())
						.toString());
				}
				Ended compiled = ended(new ProcessBuilder(compile).start());
				assertThat(compiled.exit()).as("javac of what '%s' provides: %s", this.name, compiled.stderr())
					.isZero();
				command.addAll(List.of("-cp", provided.toString()));
			}
			for (Map.Entry<String, String> source : this.library.entrySet()) {
				if (source.getKey().endsWith(".java")) {
					command.add(
							JavaSpecE2eTest.write(leg + "-library/src", source.getKey(), source.getValue()).toString());
				}
				else {
					// a resource, which the jar of the library's classes holds beside
					// them
					Path resource = classes.resolve(source.getKey());
					Files.createDirectories(java.util.Objects.requireNonNull(resource.getParent()));
					Files.writeString(resource, source.getValue(), StandardCharsets.UTF_8);
				}
			}
			Ended compiled = ended(new ProcessBuilder(command).start());
			assertThat(compiled.exit()).as("javac of the library of '%s': %s", this.name, compiled.stderr()).isZero();
			return classes;
		}

		/**
		 * The class path of the JDK leg: the library's classes and the jars of the
		 * dependencies, or {@code null} for neither.
		 */
		@Nullable String classPath(String leg) throws Exception {
			List<String> entries = new ArrayList<>();
			Path classes = classes(leg);
			if (classes != null) {
				entries.add(classes.toString());
			}
			if (this.dependencies != null) {
				entries.addAll(resolved(this.dependencies, false));
			}
			return entries.isEmpty() ? null : String.join(java.io.File.pathSeparator, entries);
		}

		SourceStandards standards(String leg) throws IOException {
			SourceStandards standards = SourceStandards.DEFAULT.withJavaSourcePath(sourcePath(leg));
			return this.tests == null ? standards : standards.withJavaTests(this.file, testFiles(leg));
		}

		/**
		 * The further files of a run of tests on a leg: every {@code .java} of the
		 * program's other files, as written for the leg.
		 */
		List<String> testFiles(String leg) {
			List<String> further = new ArrayList<>();
			for (String other : this.files == null ? List.<String>of() : this.files.keySet()) {
				if (other.endsWith(".java")) {
					further.add(workDir.resolve(leg).resolve(other).toString());
				}
			}
			return further;
		}

		/**
		 * What names the case's directories, modules and JVM class: its name, which no
		 * other case has, as an identifier -- not its file's, which two cases share
		 * ({@code app/Main.java}).
		 */
		String stem() {
			return "Standalone_" + this.name.replace('-', '_');
		}

		/** Whether the program is a Common Lisp one, which no JDK runs. */
		boolean isLisp() {
			return this.file.endsWith(".lisp");
		}

		/**
		 * Writes the program's files under a directory of its own, makes its links, gives
		 * them their modes, and answers the first.
		 */
		Path write(String leg) throws IOException {
			if (this.files != null) {
				for (Map.Entry<String, String> other : this.files.entrySet()) {
					JavaSpecE2eTest.write(leg, other.getKey(), other.getValue());
				}
			}
			Path written = JavaSpecE2eTest.write(leg, this.file, this.source);
			if (this.links != null) {
				for (Map.Entry<String, String> link : this.links.entrySet()) {
					Path path = workDir.resolve(leg).resolve(link.getKey());
					Files.createDirectories(java.util.Objects.requireNonNull(path.getParent()));
					Files.createSymbolicLink(path, Path.of(link.getValue()));
				}
			}
			if (this.modes != null) {
				if (System.getProperty("user.name").equals("root")) {
					abort("no permission refuses root");
				}
				for (Map.Entry<String, String> mode : this.modes.entrySet()) {
					Path path = workDir.resolve(leg).resolve(mode.getKey());
					if (mode.getKey().endsWith("/")) {
						Files.createDirectories(path);
					}
					Files.setPosixFilePermissions(path,
							java.nio.file.attribute.PosixFilePermissions.fromString(mode.getValue()));
				}
			}
			return written;
		}

		/**
		 * The directory a leg runs in, the program's root, where the case asks for one.
		 */
		@Nullable Path runsIn(Path written) {
			if (!Boolean.TRUE.equals(this.directory)) {
				return null;
			}
			Path dir = written;
			for (int i = this.file.split("/").length; i > 0; i--) {
				dir = java.util.Objects.requireNonNull(dir.getParent());
			}
			return dir;
		}

		List<String> args() {
			return this.arguments == null ? List.of() : this.arguments;
		}

		/**
		 * What wasmtime runs the module with: its directory preopened under its own name,
		 * and for a run of tests the directory a {@code @TempDir} is made in.
		 */
		List<String> wasmtime(Path module, @Nullable Path dir) {
			List<String> command = new ArrayList<>(List.of("wasmtime", "run"));
			if (dir != null) {
				command.addAll(List.of("--dir", dir + "::" + dir));
			}
			if (this.tests != null) {
				command.addAll(List.of("--dir", "/tmp::/tmp"));
			}
			command.add(module.toString());
			command.addAll(args());
			return command;
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

	/** How long a program of a leg may run, in seconds. */
	private static final int PROCESS_SECONDS = 300;

	@TempDir
	static Path workDir;

	/**
	 * The jars of the coordinates asked for, once resolved, by the coordinates and the
	 * kind ({@link #resolved}); the reason they cannot be had, once that is known.
	 */
	private static final Map<String, List<String>> resolvedJars = new java.util.HashMap<>();

	private static final Map<String, String> missingJars = new java.util.HashMap<>();

	/**
	 * How a run of tests names JUnit Jupiter, the version the JDK leg runs on: its
	 * parameterized tests, which bring its API.
	 */
	static String junitCoordinate() {
		return "org.junit.jupiter:junit-jupiter-params:"
				+ org.junit.jupiter.params.ParameterizedTest.class.getPackage().getImplementationVersion();
	}

	/**
	 * JUnit Jupiter's sources, which a run of tests compiles as a library of the program
	 * ({@code .kb/java-frontend.md}, "Tests of a program"): the sources jars of
	 * {@link #junitCoordinate()} and of what it depends on at run time, resolved as
	 * {@code --java-source-dep} resolves it, from Maven's local repository, then Central.
	 * A leg needing them is skipped where they cannot be had: offline, with no copy in
	 * the local repository.
	 */
	static List<String> junitSources() {
		return resolved(List.of(junitCoordinate()), true);
	}

	/**
	 * The jars of Maven coordinates and of what they depend on at run time -- their
	 * sources jars, or the jars themselves -- resolved as {@code --java-source-dep}
	 * resolves them, from Maven's local repository, then Central. A leg needing them is
	 * skipped where they cannot be had: offline, with no copy in the local repository.
	 * @param coordinates the coordinates
	 * @param sources whether the sources jars are asked for
	 * @return the jars, in class path order
	 */
	static synchronized List<String> resolved(List<String> coordinates, boolean sources) {
		String key = coordinates + (sources ? " sources" : " jars");
		if (!resolvedJars.containsKey(key) && !missingJars.containsKey(key)) {
			try {
				am.ik.maven.MavenResolver maven = ExamplesE2eTest.mavenResolver();
				List<am.ik.maven.Dependency> asked = new ArrayList<>();
				for (String coordinate : coordinates) {
					asked.add(am.ik.maven.Dependency.of(am.ik.maven.Artifact.parse(coordinate)));
				}
				List<String> jars = new ArrayList<>();
				for (am.ik.maven.Artifact artifact : maven.resolve(asked, List.of()).runtimeClassPath()) {
					jars.add(maven
						.artifact(sources ? new am.ik.maven.Artifact(artifact.groupId(), artifact.artifactId(),
								artifact.version(), "sources", "jar") : artifact)
						.toString());
				}
				resolvedJars.put(key, List.copyOf(jars));
			}
			catch (am.ik.maven.MavenResolutionException ex) {
				missingJars.put(key, String.valueOf(ex.getMessage()));
			}
		}
		if (!resolvedJars.containsKey(key)) {
			abort(coordinates + (sources ? "' sources" : "") + " cannot be had: " + missingJars.get(key));
		}
		return java.util.Objects.requireNonNull(resolvedJars.get(key));
	}

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
		cases = cases == null ? List.of() : cases;
		// the legs run at once, each in the directories its stem names
		assertThat(cases.stream().map(Standalone::stem)).as("the stems of the standalone cases")
			.doesNotHaveDuplicates();
		for (Standalone s : cases) {
			if (!s.isLisp()) {
				legs.add(dynamicTest(s.name() + " JDK", () -> {
					Path written = s.write(s.stem() + "-JDK");
					String classPath = s.classPath(s.stem() + "-JDK");
					assertEnded(s, "JDK",
							s.tests() == null ? runOnJdk(s.file(), written, s.stdin(), classPath, s.args())
									: runTestsOnJdk(s, written, classPath),
							null);
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
					ByteArrayOutputStream warned = new ByteArrayOutputStream();
					Path module;
					try (ThreadStdio.Scope _ = ThreadStdio.err(warned)) {
						module = compileWasm(file, s.source(), component, s.stem(), s.standards(s.stem() + "-" + leg));
					}
					Path dir = s.runsIn(file);
					ProcessBuilder run = new ProcessBuilder(s.wasmtime(module, dir));
					if (dir != null) {
						run.directory(dir.toFile());
					}
					assertEnded(s, leg, ended(run.start(), s.stdin()), withoutRoot(warned, s.stem() + "-" + leg));
				}));
			}
			if (s.hostLog() != null) {
				legs.add(dynamicTest(s.name() + " WASM_COMPONENT_WASI_LOGGING", () -> hostLogged(s)));
			}
		}
		return legs.stream();
	}

	/**
	 * A case's component compiled with {@code --wasi-logging}, composed with the test's
	 * host of {@code wasi:logging} and run: the program's output is the other legs', its
	 * records are the host's lines on the standard error.
	 */
	private static void hostLogged(Standalone s) throws Exception {
		if (!HostWasmtime.isAvailable()) {
			abort("no usable wasmtime on PATH");
		}
		String leg = "WASM_COMPONENT_WASI_LOGGING";
		Path file = s.write(s.stem() + "-" + leg);
		Path module;
		try (ThreadStdio.Scope _ = ThreadStdio.err(new ByteArrayOutputStream())) {
			module = compileWasm(file, s.source(), true, s.stem() + "-wasi-logging", s.standards(s.stem() + "-" + leg),
					true);
		}
		Path composed = workDir.resolve(s.stem() + "-wasi-logging.composed.wasm");
		tool("wasm-tools", "compose", module.toString(), "-d", loggingHost().toString(), "-o", composed.toString());
		Path dir = s.runsIn(file);
		ProcessBuilder run = new ProcessBuilder(s.wasmtime(composed, dir));
		if (dir != null) {
			run.directory(dir.toFile());
		}
		Ended ended = ended(run.start(), s.stdin());
		String where = where(s, leg, ended.stderr());
		assertThat(ended.stdout()).as("%s", where).isEqualTo(s.stdout(leg));
		assertThat(ended.stderr()).as("%s", where).isEqualTo(s.hostLog());
		assertThat(ended.exit()).as("%s", where).isZero();
	}

	/** The test's host of {@code wasi:logging}, once built. */
	private static @Nullable Path loggingHost;

	/**
	 * The test's host of {@code wasi:logging}, a component built from
	 * {@code testsupport/wasi-logging-host}'s core module and world by
	 * {@code wasm-tools}: a leg needing it is skipped without the tool.
	 */
	private static synchronized Path loggingHost() throws Exception {
		if (loggingHost == null) {
			try {
				tool("wasm-tools", "--version");
			}
			catch (IOException ex) {
				abort("no wasm-tools on PATH");
			}
			Path dir = workDir.resolve("wasi-logging-host");
			Files.createDirectories(dir);
			for (String name : List.of("host.wat", "host.wit")) {
				try (InputStream in = HostWasmtime.class.getResourceAsStream("wasi-logging-host/" + name)) {
					Files.write(dir.resolve(name), java.util.Objects.requireNonNull(in, name).readAllBytes());
				}
			}
			tool("wasm-tools", "parse", dir.resolve("host.wat").toString(), "-o", dir.resolve("core.wasm").toString());
			tool("wasm-tools", "component", "embed", dir.resolve("host.wit").toString(), "--world", "host",
					dir.resolve("core.wasm").toString(), "-o", dir.resolve("embedded.wasm").toString());
			tool("wasm-tools", "component", "new", dir.resolve("embedded.wasm").toString(), "-o",
					dir.resolve("host.wasm").toString());
			loggingHost = dir.resolve("host.wasm");
		}
		return loggingHost;
	}

	/** Runs a tool, which has to succeed. */
	private static void tool(String... command) throws Exception {
		Ended ended = ended(new ProcessBuilder(command).start());
		assertThat(ended.exit()).as("%s: %s", String.join(" ", command), ended.stderr()).isZero();
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
	 * What a run of tests does not run is refused by name where the file is compiled,
	 * never skipped, with JUnit Jupiter itself on the source path: an annotation of
	 * JUnit's the runner has not, wherever it is written; an element of one it has that
	 * it does not read; one written on an annotation type, whose methods JUnit would run;
	 * an orderer of tests other than JUnit's by order, name or display name; a
	 * RepetitionInfo given around a test that is not repeated; a parameter of a
	 * parameterized test that JUnit would make of its own, or whose TestInfo would name
	 * its arguments; a text a parameterized test's source gives to a class parameter.
	 */
	@TestFactory
	Stream<DynamicNode> whatARunOfTestsDoesNotRunIsRefusedByName() {
		String[][] refusals = {
				{ "import java.nio.file.Path; import org.junit.jupiter.api.Test; import org.junit.jupiter.api.io.*; "
						+ "class T { @TempDir(cleanup = CleanupMode.NEVER) Path dir; @Test void t() { } }", "@TempDir",
						"the element cleanup of @TempDir" },
				{ "import org.junit.jupiter.api.*; @TestMethodOrder(MethodOrderer.Random.class) class T { "
						+ "@Test void t() { } }", "@TestMethodOrder",
						"the method orderer org.junit.jupiter.api.MethodOrderer.Random" },
				{ "import org.junit.jupiter.api.*; class T { @BeforeEach void b(RepetitionInfo r) { } "
						+ "@Test void t() { } }", "b(",
						"a RepetitionInfo given to what runs around a test that is not a @RepeatedTest" },
				{ "import org.junit.jupiter.api.Timeout; import org.junit.jupiter.api.Test; class T { "
						+ "@Test @Timeout(1) void t() { } }", "@Timeout",
						"the annotation org.junit.jupiter.api.Timeout" },
				{ "import org.junit.jupiter.api.TestInfo; import org.junit.jupiter.params.ParameterizedTest; "
						+ "import org.junit.jupiter.params.provider.ValueSource; class T { @ParameterizedTest "
						+ "@ValueSource(ints = 1) void t(int i, TestInfo info) { } }", "info)",
						"a TestInfo parameter of a @ParameterizedTest method" },
				{ "import org.junit.jupiter.params.ParameterizedTest; import org.junit.jupiter.params.provider.ValueSource; "
						+ "class T { @ParameterizedTest @ValueSource(classes = String.class) void t(Class<?> c) { } }",
						"@ValueSource", "the element classes of @ValueSource" },
				{ "import org.junit.jupiter.params.ParameterizedTest; import org.junit.jupiter.params.provider.ValueSource; "
						+ "class T { @ParameterizedTest @ValueSource(strings = \"java.lang.String\") void t(Class<?> c) { } }",
						"c)", "the conversion of a text to a java.lang.Class" },
				{ "import org.junit.jupiter.api.Test; @Test @interface Fast { } class T { @Fast void t() { } }",
						"@Test", "the annotation org.junit.jupiter.api.Test on an annotation type" },
				{ "import org.junit.jupiter.params.ParameterizedTest; import org.junit.jupiter.params.aggregator."
						+ "ArgumentsAccessor; import org.junit.jupiter.params.provider.CsvSource; class T { "
						+ "@ParameterizedTest @CsvSource(\"a, 1\") void t(ArgumentsAccessor row) { } }", "row)",
						"a parameter of the type org.junit.jupiter.params.aggregator.ArgumentsAccessor" } };
		List<DynamicNode> legs = new ArrayList<>();
		for (String[] refusal : refusals) {
			legs.add(dynamicTest(refusal[2], () -> {
				SourceStandards standards = SourceStandards.DEFAULT.withJavaSourcePath(junitSources())
					.withJavaTests("T.java");
				LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
				assertThat(CliStack.callWithin("java-spec-refused-test", () -> {
					try {
						SourceLanguage.JAVA.read(refusal[0], Features.INTERPRETER, "T.java", standards,
								evaluator.sourceLoader());
						return "lowered";
					}
					catch (LispReadException ex) {
						return ex.getMessage();
					}
				}, Duration.ofSeconds(PROCESS_SECONDS))).as("%s", refusal[0])
					.contains("T.java:1:" + (refusal[0].indexOf(refusal[1]) + 1) + ": " + refusal[2]
							+ " is not supported by the experimental Java subset yet");
			}));
		}
		return legs.stream();
	}

	/**
	 * The suites of the tree's own modules that test no server and need no container run
	 * under {@code rontolisp test} as they are: the files surefire runs under a JDK,
	 * their module's sources the source path and JUnit Jupiter a dependency, every test
	 * green on the four backends. Through the command line, in a process of its own: what
	 * is run is {@code rontolisp test FILE.java [-o OUT]}.
	 */
	@TestFactory
	Stream<DynamicNode> theSuitesOfTheTreesModulesRunUnchanged() {
		record Suite(String module, String file, int tests) {
		}
		String jdbc = "src/test/java/am/ik/jdbc/postgresql/";
		List<DynamicNode> legs = new ArrayList<>();
		for (Suite suite : List.of(new Suite("jdbc-postgresql", jdbc + "SqlTest.java", 2),
				new Suite("jdbc-postgresql", jdbc + "ScramTest.java", 3),
				new Suite("jdbc-postgresql", jdbc + "TargetTest.java", 3),
				new Suite("servlet-httpserver", "src/test/java/am/ik/servlet/httpserver/UrlCodecTest.java", 65))) {
			String name = suite.file().substring(suite.file().lastIndexOf('/') + 1).replace(".java", "");
			for (String leg : List.of("INTERPRETER", "JVM", "WASM", "WASM_COMPONENT")) {
				legs.add(dynamicTest(name + " " + leg, () -> {
					if (leg.startsWith("WASM") && !HostWasmtime.isAvailable()) {
						abort("no usable wasmtime on PATH");
					}
					// had in the local repository, where the command line finds them
					junitSources();
					String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
					Path module = Path.of(suite.module()).toAbsolutePath();
					List<String> command = new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"),
							"am.ik.rontolisp.cli.RontoLispCli", "test", "--java-source-path",
							module.resolve("src/main/java").toString(), "--java-source-dep", junitCoordinate(),
							module.resolve(suite.file()).toString()));
					Path dir = workDir.resolve("Module-" + name + "-" + leg);
					Files.createDirectories(dir);
					Ended ended;
					if (leg.equals("INTERPRETER")) {
						ended = ended(new ProcessBuilder(command).directory(dir.toFile()).start());
					}
					else {
						Path output = dir.resolve(leg.equals("JVM") ? name + "Suite.class" : name + ".wasm");
						command.addAll(List.of("-o", output.toString()));
						if (leg.equals("WASM_COMPONENT")) {
							command.add("--component");
						}
						Ended compiled = ended(new ProcessBuilder(command).directory(dir.toFile()).start());
						assertThat(compiled.exit()).as("the compile of %s on %s: %s", name, leg, compiled.stderr())
							.isZero();
						ended = ended(new ProcessBuilder(
								leg.equals("JVM") ? List.of(java, "-cp", dir.toString(), name + "Suite")
										: List.of("wasmtime", "run", output.toString()))
							.directory(dir.toFile())
							.start());
					}
					String counts = "Tests run: " + suite.tests() + ", Failures: 0, Errors: 0, Skipped: 0";
					assertThat(ended.stdout()).as("%s on %s: %s", name, leg, ended.stderr())
						.endsWith("\nResults:\n\n" + counts + "\n");
					assertThat(ended.exit()).as("%s on %s: %s", name, leg, ended.stderr()).isZero();
				}));
			}
		}
		return legs.stream();
	}

	/**
	 * Where the target has one stack the threads of a program are bodies taking turns, so
	 * a program whose threads all wait for one another -- two threads each holding the
	 * monitor the other enters -- is known to be one that cannot go on: refused by name
	 * with the status 1, where a JDK, the interpreter and the JVM wait for ever. The
	 * programs that do go on are the corpus's
	 * {@code threads-wait-for-one-another-again-and-again}.
	 */
	@TestFactory
	Stream<DynamicNode> aProgramWhoseThreadsAllWaitIsRefusedWhereThreadsTakeTurns() {
		String source = """
				public class Stuck {
				    static final Object first = new Object();
				    static final Object second = new Object();

				    static Thread holding(Object mine, Object other, String word) {
				        return new Thread(() -> {
				            synchronized (mine) {
				                try {
				                    Thread.sleep(5);
				                }
				                catch (InterruptedException e) {
				                    return;
				                }
				                synchronized (other) {
				                    System.out.println(word);
				                }
				            }
				        });
				    }

				    public static void main(String[] args) throws InterruptedException {
				        Thread one = holding(first, second, "one has both");
				        Thread two = holding(second, first, "two has both");
				        one.start();
				        two.start();
				        System.out.println("started");
				        one.join();
				        two.join();
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
				Path module = compileWasm(write("Stuck-" + leg, "Stuck.java", source), source, component, "stuck");
				Ended ended = ended(new ProcessBuilder("wasmtime", "run", module.toString()).start());
				assertThat(ended.stdout()).as("%s", ended.stderr()).isEqualTo("started\n");
				assertThat(ended.stderr()).contains("every thread of the program waits for another and none can go on");
				assertThat(ended.exit()).isEqualTo(1);
			});
		});
	}

	/**
	 * A task of a scheduled pool runs although its time comes between two readings of the
	 * clock. The clock is the test's here, a millisecond later at every reading, which
	 * only the interpreter lets a test hold: a task some milliseconds and a half away is
	 * not yet due at one reading and overdue at the next. A pool's thread that asked the
	 * clock a second time for how long to wait then waited for no time at all, which is a
	 * wait with no limit, and the program never ended -- what a loaded machine did to
	 * {@code a-scheduled-pool-and-a-timer-run-tasks-at-their-times} with the real clock.
	 */
	@Test
	void aTaskWhoseTimeComesBetweenTwoReadingsOfTheClockRuns() throws Exception {
		String source = """
				import java.util.concurrent.ScheduledThreadPoolExecutor;
				import java.util.concurrent.TimeUnit;

				public class Late {
				    public static void main(String[] args) throws Exception {
				        for (int halves = 1; halves <= 7; halves += 2) {
				            ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1);
				            System.out.println(pool.schedule(() -> "ran", halves * 500, TimeUnit.MICROSECONDS).get());
				            pool.shutdown();
				        }
				    }
				}
				""";
		Path file = write("Late-INTERPRETER", "Late.java", source);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertThat(CliStack.callWithin("java-spec-late", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			evaluator.eval(LispReader.readFromString("(defvar *readings* (cons 0 (rontolisp:make-mutex)))"));
			evaluator.eval(LispReader.readFromString("""
					(defun %nano-time ()
					  (rontolisp:with-mutex ((cdr *readings*))
					    (rplaca *readings* (+ (car *readings*) 1000000))
					    (car *readings*)))
					"""));
			for (LispVal form : SourceLanguage.JAVA.read(source, Features.INTERPRETER, file.toString())) {
				evaluator.eval(form);
			}
			return out.toString(StandardCharsets.UTF_8);
		}, Duration.ofSeconds(60))).as("the program ended; it printed%n%s", out).contains("ran\nran\nran\nran\n");
	}

	/**
	 * A static initialization past what one method of a JVM class holds runs on the JVM:
	 * the initializers that run before {@code main} are a function the backend cuts as it
	 * cuts any. In the one form that runs the program, with the exit of a loop in it,
	 * these 3,000 statements were one method of 422,144 bytes, which no class holds.
	 */
	@Test
	void aStaticInitializationPastOneMethodOfAJvmClassRuns() throws Exception {
		int statements = 3000;
		StringBuilder source = new StringBuilder("public class Table {\n    static int[] t = new int[" + statements
				+ "];\n    static int sum;\n    static {\n");
		for (int i = 0; i < statements; i++) {
			source.append("        t[").append(i).append("] = ").append(i).append(" * 3;\n");
		}
		source.append("""
				        for (int v : t) sum += v;
				    }
				    public static void main(String[] args) {
				        System.out.println(sum + " " + t[2999]);
				    }
				}
				""");
		assertThat(runOnJvm("Table-JVM", "Table.java", source.toString())).isEqualTo("13495500 8997\n");
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
		return runOnJdk(file, written, stdin, null, List.of());
	}

	/**
	 * @param classPath the classes and the jars of the libraries the program uses, or
	 * {@code null}
	 * @param arguments the program's command-line arguments
	 */
	private static Ended runOnJdk(String file, Path written, @Nullable String stdin, @Nullable String classPath,
			List<String> arguments) throws Exception {
		Path dir = written;
		for (int i = file.split("/").length; i > 0; i--) {
			dir = java.util.Objects.requireNonNull(dir.getParent());
		}
		// the default locale the subset has, whatever the host's
		List<String> command = new ArrayList<>(
				List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Duser.language=en",
						"-Duser.country=US"));
		if (classPath != null) {
			command.addAll(List.of("-cp", classPath));
		}
		command.add(file);
		command.addAll(arguments);
		return ended(new ProcessBuilder(command).directory(dir.toFile()).start(), stdin);
	}

	/**
	 * The oracle of a run of tests: the file compiled by the JDK's compiler against JUnit
	 * Jupiter and AssertJ themselves -- the ones this test runs on --, and its classes
	 * run on the JUnit Platform by {@link JavaTestOracle}, which reports them in the
	 * shape the subset's own runner does.
	 * @param classes the classes and the jars of the libraries the tests are of, or
	 * {@code null}
	 */
	private static Ended runTestsOnJdk(Standalone s, Path written, @Nullable String classes) throws Exception {
		String javaHome = System.getProperty("java.home");
		Path compiled = workDir.resolve(s.stem() + "-JDK-classes");
		String classPath = (classes == null ? "" : classes + java.io.File.pathSeparator)
				+ System.getProperty("java.class.path");
		List<String> javacCommand = new ArrayList<>(List.of(Path.of(javaHome, "bin", "javac").toString(), "-proc:none",
				"-d", compiled.toString(), "-cp", classPath, written.toString()));
		javacCommand.addAll(s.testFiles(s.stem() + "-JDK"));
		Ended javac = ended(new ProcessBuilder(javacCommand).start());
		assertThat(javac.exit()).as("javac of the tests of '%s': %s", s.name(), javac.stderr()).isZero();
		List<String> command = new ArrayList<>(
				List.of(Path.of(javaHome, "bin", "java").toString(), "-Duser.language=en", "-Duser.country=US", "-cp",
						compiled + java.io.File.pathSeparator + classPath, JavaTestOracle.class.getName()));
		command.addAll(java.util.Objects.requireNonNull(s.tests()));
		return ended(new ProcessBuilder(command).start(), s.stdin());
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
		if (!process.waitFor(PROCESS_SECONDS, TimeUnit.SECONDS)) {
			process.destroyForcibly().waitFor();
			throw new IllegalStateException("the process timed out");
		}
		drain.join();
		return new Ended(stdout, Files.readString(errors, StandardCharsets.UTF_8), process.exitValue());
	}

	/**
	 * Checks how a standalone case ended on a leg.
	 * @param compiled what its compile printed, its directory taken out of the paths, or
	 * {@code null} where the standard error holds that (the JDK and the interpreter)
	 */
	private static void assertEnded(Standalone s, String leg, Ended ended, @Nullable String compiled) {
		String where = where(s, leg, ended.stderr());
		assertThat(ended.stdout()).as("%s", where).isEqualTo(s.stdout(leg));
		String stderr = ended.stderr();
		if (s.warnings() != null) {
			if (compiled != null) {
				assertThat(compiled).as("what the compile printed, %s", where).isEqualTo(s.warnings());
			}
			else {
				assertThat(stderr).as("%s", where).startsWith(s.warnings());
				stderr = stderr.substring(s.warnings().length());
			}
		}
		if (s.stderr() != null) {
			assertThat(splitLines(stderr).stream()
				.filter(line -> !line.matches("\\s+(at |\\.\\.\\. \\d+ more).*"))
				.toList()).as("%s", where).containsExactlyElementsOf(splitLines(s.stderr()));
		}
		if (s.exists() != null) {
			Path root = workDir.resolve(s.stem() + "-" + leg);
			s.exists()
				.forEach((path, there) -> assertThat(Files.exists(root.resolve(path))).as("%s exists, %s", path, where)
					.isEqualTo(there));
		}
		if (s.error() == null) {
			assertThat(ended.exit()).as("%s", where).isEqualTo(s.status() == null ? 0 : s.status());
			return;
		}
		// the report first; the JDK's stack trace follows
		assertThat(splitLines(stderr)).as("%s", where).first().isEqualTo(UNCAUGHT + s.error());
		assertThat(ended.exit()).as("%s: expected the status 1", where).isEqualTo(1);
		if (s.at() != null && (leg.equals("INTERPRETER") || leg.equals("JVM"))) {
			// where it was raised, under the report and what the exception encloses:
			// the pipeline's own location line
			assertThat(splitLines(stderr).stream().filter(line -> line.startsWith("  at ")).findFirst()).as("%s", where)
				.hasValueSatisfying(line -> assertThat(line).endsWith(s.at()));
		}
	}

	/**
	 * What a leg printed, with the directory its files were written to taken out of the
	 * paths: a JDK names a file as its command line does, from that directory.
	 */
	private static String withoutRoot(ByteArrayOutputStream printed, String leg) {
		return printed.toString(java.nio.charset.Charset.defaultCharset())
			.replace(workDir.resolve(leg) + java.io.File.separator, "");
	}

	private static final String UNCAUGHT = "Exception in thread \"main\" ";

	private static String where(Standalone s, String leg, String stderr) {
		return "standalone case '%s' on %s%n--- source ---%n%s--- end source ---%n--- stderr ---%n%s"
			.formatted(s.name(), leg, s.source(), stderr);
	}

	private static void standaloneOnInterpreter(Standalone s) throws Exception {
		Path file = s.write(s.stem() + "-INTERPRETER");
		Path dir = s.runsIn(file);
		if (dir != null) {
			// where the program runs is the directory of a process of its own
			List<String> command = new ArrayList<>(
					List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
							System.getProperty("java.class.path"), "am.ik.rontolisp.cli.RontoLispCli",
							dir.relativize(file).toString(), "--"));
			command.addAll(s.args());
			assertEnded(s, "INTERPRETER", ended(new ProcessBuilder(command).directory(dir.toFile()).start(), s.stdin()),
					null);
			return;
		}
		SourceStandards standards = s.standards(s.stem() + "-INTERPRETER");
		int[] status = new int[1];
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		try (ThreadStdio.Scope _ = ThreadStdio.err(err)) {
			// within the time a process of the other legs has: a program whose threads
			// wait for ever fails its leg, and the run goes on
			assertThat(CliStack.callWithin("java-spec", () -> {
				LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8),
						new ByteArrayInputStream(
								(s.stdin() == null ? "" : s.stdin()).getBytes(StandardCharsets.UTF_8)));
				List<String> argv = new ArrayList<>(List.of(file.toString()));
				argv.addAll(s.args());
				evaluator.setCommandLineArguments(argv);
				if (s.isLisp()) {
					// what the program loads is beside it, as the compiled legs' splice
					// reads it
					evaluator.setLoadBaseDir(java.util.Objects.requireNonNull(file.getParent()).toString());
				}
				try {
					for (LispVal form : SourceLanguage.forFile(file.toString(), null)
						.read(s.source(), Features.INTERPRETER, file.toString(), standards, evaluator.sourceLoader())) {
						evaluator.eval(form);
					}
				}
				catch (LispExitSignal exit) {
					status[0] = exit.code();
				}
				finally {
					// the other legs' process ends, and its threads with it: a daemon
					// left spinning is gone when the leg returns
					for (Thread left : evaluator.stopThreads()) {
						left.join();
					}
				}
				return true;
			}, Duration.ofSeconds(PROCESS_SECONDS)))
				.as("standalone case '%s' on INTERPRETER ended within %d s; it printed%n%s", s.name(), PROCESS_SECONDS,
						out)
				.isPresent();
		}
		assertEnded(s, "INTERPRETER",
				new Ended(out.toString(StandardCharsets.UTF_8), withoutRoot(err, s.stem() + "-INTERPRETER"), status[0]),
				null);
	}

	// The program may die, so it runs in a process of its own: with the class and the
	// runtime classes travelling beside it in its class path directory, as the CLI
	// writes them.
	private static void standaloneOnJvm(Standalone s) throws Exception {
		Path file = s.write(s.stem() + "-JVM");
		Path dir = workDir.resolve(s.stem());
		Files.createDirectories(dir);
		ByteArrayOutputStream warned = new ByteArrayOutputStream();
		JvmSourceCompiler.Result compiled;
		try (ThreadStdio.Scope _ = ThreadStdio.err(warned)) {
			JvmSourceCompiler compiler = new JvmSourceCompiler(s.stem())
				.baseDir(java.util.Objects.requireNonNull(file.getParent()).toString())
				.javaSourcePath(s.sourcePath(s.stem() + "-JVM"));
			compiled = (s.tests() == null ? compiler : compiler.javaTests(s.file(), s.testFiles(s.stem() + "-JVM")))
				.compile(s.source(), file.toString());
		}
		Files.write(dir.resolve(s.stem() + ".class"), compiled.classBytes());
		for (Map.Entry<String, byte[]> runtime : compiled.runtimeClasses().entrySet()) {
			Path travelling = dir.resolve(runtime.getKey());
			Files.createDirectories(java.util.Objects.requireNonNull(travelling.getParent()));
			Files.write(travelling, runtime.getValue());
		}
		List<String> command = new ArrayList<>(List
			.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", dir.toString(), s.stem()));
		command.addAll(s.args());
		ProcessBuilder run = new ProcessBuilder(command);
		Path runsIn = s.runsIn(file);
		if (runsIn != null) {
			run.directory(runsIn.toFile());
		}
		assertEnded(s, "JVM", ended(run.start(), s.stdin()), withoutRoot(warned, s.stem() + "-JVM"));
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
		return runOnJvm("JVM", PROGRAM + ".java", program);
	}

	private static String runOnJvm(String leg, String fileName, String program) throws Exception {
		String name = "JavaSpecProgram";
		Path file = write(leg, fileName, program);
		JvmSourceCompiler.Result compiled = new JvmSourceCompiler(name).compile(program, file.toString());
		// the corpus is past one class's constant pool: its parts travel beside the class
		ClassLoader loader = new ClassLoader(JavaSpecE2eTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String n) throws ClassNotFoundException {
				byte[] bytes = n.equals(name) ? compiled.classBytes()
						: compiled.runtimeClasses().get(n.replace('.', '/') + ".class");
				if (bytes != null) {
					return defineClass(n, bytes, 0, bytes.length);
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
		Ended ended = ended(new ProcessBuilder("wasmtime", "run", module.toString()).start());
		assertThat(ended.exit()).as("wasmtime exit code (%s): %s", module, ended.stderr()).isZero();
		return ended.stdout();
	}

	private static Path compileWasm(Path file, String program, boolean component, String name) throws IOException {
		return compileWasm(file, program, component, name, SourceStandards.DEFAULT);
	}

	private static Path compileWasm(Path file, String program, boolean component, String name,
			SourceStandards standards) throws IOException {
		return compileWasm(file, program, component, name, standards, false);
	}

	/**
	 * @param wasiLogging {@code --wasi-logging}: the log records to the host's
	 * {@code wasi:logging}
	 */
	private static Path compileWasm(Path file, String program, boolean component, String name,
			SourceStandards standards, boolean wasiLogging) throws IOException {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.file(program, file.toString(), true, component,
				standards, wasiLogging);
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
