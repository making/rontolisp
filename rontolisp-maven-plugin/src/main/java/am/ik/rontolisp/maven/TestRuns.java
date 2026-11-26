package am.ik.rontolisp.maven;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import am.ik.rontolisp.cli.RontoLispCli;
import org.jspecify.annotations.Nullable;

/**
 * The runs of {@code rontolisp test} a project's tests are -- the whole of what the
 * {@code test} goal does, with no Maven type in sight.
 * <p>
 * Each run is the command line a user types, in a process of its own whose working
 * directory is the project's, as surefire forks one: the interpreter's is
 * {@code rontolisp test}, a compiled backend's is the same command with {@code -o} and
 * then the artifact on {@code java} or {@code wasmtime run}. The tests of a Java project
 * are one run a backend, every test file of the tree in one program; a Lisp project's
 * rove files are a run each, as {@code rontolisp test} takes one rove target.
 */
public final class TestRuns {

	/** What a backend is called in the {@code backends} parameter. */
	public enum Backend {

		/** The interpreter: {@code rontolisp test}. */
		INTERPRETER("the interpreter"),

		/** A class on the JVM: {@code -o Tests.class}, then {@code java}. */
		JVM("the JVM"),

		/** A WASI Preview 1 module: {@code -o tests.wasm}, then {@code wasmtime run}. */
		WASM("WASM Preview 1"),

		/** A WASI 0.3 component: {@code --component}, then {@code wasmtime run}. */
		COMPONENT("the WASI 0.3 component");

		private final String description;

		Backend(String description) {
			this.description = description;
		}

		/**
		 * @param name a backend's name, in any case
		 * @return the backend
		 * @throws IllegalArgumentException for a name of none
		 */
		public static Backend parse(String name) {
			for (Backend backend : values()) {
				if (backend.name().equalsIgnoreCase(name.trim())) {
					return backend;
				}
			}
			throw new IllegalArgumentException(
					"backends: no backend '" + name.trim() + "': interpreter, jvm, wasm or component");
		}

		@Override
		public String toString() {
			return this.description;
		}

	}

	/** surefire's default includes: the test classes a JDK run of the project runs. */
	public static final List<String> JAVA_INCLUDES = List.of("**/Test*.java", "**/*Test.java", "**/*Tests.java",
			"**/*TestCase.java");

	/** surefire's default excludes. */
	public static final List<String> JAVA_EXCLUDES = List.of("**/*$*");

	/** Every file of a Lisp tree; a file is a test file when it defines a test. */
	public static final List<String> LISP_INCLUDES = List.of("**/*.lisp");

	/** A rove test defined in a file, by either of its names. */
	private static final Pattern DEFTEST = Pattern.compile("\\(\\s*(rove:)?deftest\\s", Pattern.CASE_INSENSITIVE);

	/** A test a Java run reports as failed or in error. */
	private static final Pattern JAVA_PROBLEM = Pattern.compile("^(?!Tests run:)(.+) <<< (FAILURE|ERROR)!$");

	/** A test rove's summary lists as failed. */
	private static final Pattern ROVE_FAILED = Pattern.compile("^    - (.+)$");

	/** The name of the class a JVM run compiles to. */
	private static final String CLASS_NAME = "RontolispTests";

	private final Path baseDirectory;

	private final Path outputDirectory;

	private final Consumer<String> log;

	/**
	 * @param baseDirectory the project's directory, where every process runs
	 * @param outputDirectory where the compiled runs are written
	 * @param log what is told each line a run prints
	 */
	public TestRuns(Path baseDirectory, Path outputDirectory, Consumer<String> log) {
		this.baseDirectory = baseDirectory;
		this.outputDirectory = outputDirectory;
		this.log = log;
	}

	/**
	 * The test files of a tree: its files whose paths below it match an include and no
	 * exclude, in the order of their paths.
	 * @param tree the tree, which may not exist
	 * @param includes Ant-style patterns ({@code **}{@code /*Test.java})
	 * @param excludes Ant-style patterns
	 * @return the files
	 * @throws IOException if the tree cannot be read
	 */
	public static List<Path> select(Path tree, List<String> includes, List<String> excludes) throws IOException {
		if (!Files.isDirectory(tree)) {
			return List.of();
		}
		List<Pattern> included = includes.stream().map(TestRuns::glob).toList();
		List<Pattern> excluded = excludes.stream().map(TestRuns::glob).toList();
		try (Stream<Path> files = Files.walk(tree)) {
			return files.filter(Files::isRegularFile).filter(file -> {
				String path = tree.relativize(file).toString().replace(File.separatorChar, '/');
				return included.stream().anyMatch(pattern -> pattern.matcher(path).matches())
						&& excluded.stream().noneMatch(pattern -> pattern.matcher(path).matches());
			}).sorted(Comparator.comparing(Path::toString)).toList();
		}
	}

	/**
	 * The rove test files among some files: those defining a test.
	 * @param files the files
	 * @return the ones whose text holds a {@code deftest}
	 * @throws IOException if a file cannot be read
	 */
	public static List<Path> roveFiles(List<Path> files) throws IOException {
		List<Path> tests = new ArrayList<>();
		for (Path file : files) {
			if (file.toString().endsWith(".lisp")
					&& DEFTEST.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
				tests.add(file);
			}
		}
		return tests;
	}

	/**
	 * An Ant-style pattern as a regular expression over a path with {@code /}: a
	 * {@code **}{@code /} is any number of directories, none included, a {@code *} a
	 * name's part, a {@code ?} one of its characters.
	 */
	static Pattern glob(String pattern) {
		String normalized = pattern.trim().replace('\\', '/');
		StringBuilder regex = new StringBuilder();
		for (int i = 0; i < normalized.length(); i++) {
			char c = normalized.charAt(i);
			if (normalized.startsWith("**/", i)) {
				regex.append("(?:.*/)?");
				i += 2;
			}
			else if (normalized.startsWith("**", i)) {
				regex.append(".*");
				i++;
			}
			else if (c == '*') {
				regex.append("[^/]*");
			}
			else if (c == '?') {
				regex.append("[^/]");
			}
			else {
				regex.append(Pattern.quote(String.valueOf(c)));
			}
		}
		return Pattern.compile(regex.toString());
	}

	/**
	 * Runs the tests of Java files, all in one program, on a backend.
	 * @param files the test files, of one tree
	 * @param sourcePath the program's source path: the directories and jars the code
	 * under test and JUnit Jupiter are read from
	 * @param backend the backend
	 * @return the failure, or {@code null} where every test passed
	 * @throws IOException if a process cannot be started
	 * @throws InterruptedException if interrupted while a process runs
	 */
	public @Nullable String java(List<Path> files, List<Path> sourcePath, Backend backend)
			throws IOException, InterruptedException {
		List<String> command = new ArrayList<>(List.of("--java-source-path", joined(sourcePath)));
		for (Path file : files) {
			command.add(file.toString());
		}
		return run(command, this.outputDirectory.resolve("java"), backend, JAVA_PROBLEM, false);
	}

	/**
	 * Runs the tests of a rove file on a backend.
	 * @param file the file
	 * @param tree the tree it is of, which names the directory of its compiled run
	 * @param systemPath the directories searched for a system's {@code .asd}
	 * @param dists the dists {@code ql:quickload} may download from beside quicklisp
	 * @param backend the backend
	 * @return the failure, or {@code null} where every test passed
	 * @throws IOException if a process cannot be started
	 * @throws InterruptedException if interrupted while a process runs
	 */
	public @Nullable String lisp(Path file, Path tree, List<Path> systemPath, List<String> dists, Backend backend)
			throws IOException, InterruptedException {
		List<String> command = new ArrayList<>(List.of(file.toString()));
		if (!systemPath.isEmpty()) {
			command.addAll(List.of("--system-path", joined(systemPath)));
		}
		for (String dist : dists) {
			command.addAll(List.of("--dist", dist));
		}
		String relative = tree.relativize(file).toString().replace(File.separatorChar, '/');
		return run(command, this.outputDirectory.resolve("lisp").resolve(relative.replaceFirst("\\.lisp$", "")),
				backend, ROVE_FAILED, true);
	}

	/**
	 * {@code rontolisp test} with the arguments, on a backend: the run itself, or its
	 * compile into the directory and then the artifact.
	 * @return the failure, or {@code null} where the run ended with 0
	 */
	private @Nullable String run(List<String> arguments, Path directory, Backend backend, Pattern problem,
			boolean summary) throws IOException, InterruptedException {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		List<String> command = new ArrayList<>(
				List.of(java, "-cp", rontolispJar(), RontoLispCli.class.getName(), "test"));
		command.addAll(arguments);
		@Nullable List<String> artifact = null;
		if (backend != Backend.INTERPRETER) {
			Path output = directory.resolve(backend.name().toLowerCase(Locale.ROOT));
			Files.createDirectories(output);
			Path compiled = output.resolve(backend == Backend.JVM ? CLASS_NAME + ".class" : "tests.wasm");
			command.addAll(List.of("-o", compiled.toString()));
			if (backend == Backend.COMPONENT) {
				command.add("--component");
			}
			// a module reaches the directory a @TempDir is made in where it is preopened
			artifact = backend == Backend.JVM ? List.of(java, "-cp", output.toString(), CLASS_NAME)
					: Files.isDirectory(Path.of("/tmp"))
							? List.of("wasmtime", "run", "--dir", "/tmp::/tmp", compiled.toString())
							: List.of("wasmtime", "run", compiled.toString());
		}
		Ended ended = ended(command, problem, summary);
		if (ended.status() == 0 && artifact != null) {
			ended = ended(artifact, problem, summary);
		}
		if (ended.status() == 0) {
			return null;
		}
		String what = ended.problems().isEmpty()
				? (ended.errors().isEmpty() ? "it exited " + ended.status() : String.join("\n", ended.errors()))
				: "failed: " + String.join(", ", ended.problems());
		return "rontolisp test on " + backend + ": " + what;
	}

	/** What a process said and how it ended. */
	private record Ended(int status, List<String> problems, List<String> errors) {
	}

	/** Runs a process in the project's directory, telling each line it prints. */
	private Ended ended(List<String> command, Pattern problem, boolean summary)
			throws IOException, InterruptedException {
		Process process;
		try {
			process = new ProcessBuilder(command).directory(this.baseDirectory.toFile())
				.redirectErrorStream(true)
				.start();
		}
		catch (IOException ex) {
			throw new IOException("cannot run " + command.get(0) + ": " + ex.getMessage(), ex);
		}
		process.getOutputStream().close();
		List<String> problems = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		boolean summarized = !summary;
		try (BufferedReader lines = process.inputReader(StandardCharsets.UTF_8)) {
			for (String line; (line = lines.readLine()) != null;) {
				this.log.accept(line);
				summarized |= line.equals("Summary:");
				Matcher matched = problem.matcher(line);
				if (summarized && matched.matches()) {
					problems.add(matched.group(1));
				}
				if (line.startsWith("error: ") || line.startsWith("rontolisp: ")) {
					errors.add(line);
				}
			}
		}
		return new Ended(process.waitFor(), problems, errors);
	}

	private static String joined(List<Path> paths) {
		return String.join(File.pathSeparator, paths.stream().map(Path::toString).toList());
	}

	/** The rontolisp this plugin runs, whose command line each run is. */
	private static String rontolispJar() {
		try {
			return Path.of(RontoLispCli.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
		}
		catch (URISyntaxException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
