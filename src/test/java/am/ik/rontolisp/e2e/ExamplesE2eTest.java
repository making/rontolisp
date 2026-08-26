package am.ik.rontolisp.e2e;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.OS;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Smoke-tests every non-GUI example listed in {@code examples/examples.yaml}, turning
 * each (example x declared-backend) pair into one JUnit dynamic test.
 * <p>
 * Two kinds of check, chosen per backend token in the manifest:
 * <ul>
 * <li><b>RUN</b> ({@code interpreter}/{@code jvm}/{@code wasm}) -- the program runs to
 * completion; we assert it exits 0 and (optionally) that its stdout matches the declared
 * {@code expect}.</li>
 * <li><b>COMPILE</b> ({@code jvm-compile}/{@code war-compile}/{@code wasm-component}/
 * {@code wasm-reactor}/{@code no-gc}/{@code no-gc-simd}) -- for the blocking servers and
 * the host-invoked modules, which never return on their own (or are never run as a
 * program at all: a war is deployed and a reactor is called), we only build them and
 * assert the compile succeeds. This still catches broken {@code (load ...)} paths,
 * missing symbols and package errors.</li>
 * </ul>
 * Per-example manifest fields (all optional except {@code path}/{@code backends}):
 * <ul>
 * <li>{@code args} -- the program's OWN command-line arguments, what
 * {@code (uiop:command-line-arguments)} answers. They are appended when the program is
 * run: after a {@code --} separator on the interpreter (where rontolisp's own options
 * end), after the class name on the JVM, after the module path under wasmtime.</li>
 * <li>{@code os} -- the operating systems the example can RUN on ({@code mac},
 * {@code linux}, {@code windows}; one token or a list). Omitted means everywhere, which
 * is what almost every example is. It gates the RUN legs ONLY: a COMPILE leg never
 * executes the program, so it stays green on every machine -- which is the whole point
 * for {@code macos/objc-runtime.lisp}, whose output can be checked on a Mac while its
 * lowering keeps being checked in CI. A leg gated out is a skipped assumption, not a
 * failure.</li>
 * <li>{@code library} -- shared libraries the example calls into through {@code cffi},
 * spelled as a {@code define-foreign-library} spells them (one per platform). The RUN
 * legs are skipped unless the platform's loader resolves ONE of them; a COMPILE leg is
 * never gated, because it loads nothing. The {@code os} gate's shape, for the other kind
 * of thing a machine may not have.</li>
 * <li>{@code env} -- environment variables (a map) the program sees: exported to the
 * interpreter / JVM process, passed to wasmtime as {@code --env NAME=VALUE}. This is how
 * an example takes a knob a WASM leg must also see; {@code args} above is the other way,
 * and a program reads it with {@code uiop:command-line-arguments}.</li>
 * <li>{@code simd} -- {@code true} runs every leg under {@code --simd}: the interpreter
 * and every compile get the flag, the JVM leg runs with
 * {@code --add-modules jdk.incubator.vector} (as does the interpreter when the driver is
 * the exec jar). For an example whose scalar interpretation is minutes per token (llama2
 * over a real checkpoint), this is the difference between a leg and no leg.</li>
 * <li>{@code parallel} -- {@code true} adds {@code --parallel} to the interpreter and JVM
 * legs (the two that have threads; the wasm legs refuse the flag and run serially), so a
 * {@code simd: true} example also pins that the row-parallel kernels print the same
 * story.</li>
 * <li>{@code stdin} / {@code stdinFile} -- text fed to the program's standard input
 * (inline, or from a file resolved under {@code examples/}).</li>
 * <li>{@code expect} -- how to check RUN output; exactly one of:
 * <ul>
 * <li>{@code equals} -- stdout must match this text exactly (hard-coded);</li>
 * <li>{@code file} -- stdout must match the contents of this file under {@code examples/}
 * (externalised expected value, e.g. {@code .expected/nqueens.txt});</li>
 * <li>{@code contains} -- every listed substring must appear (partial match, for output
 * that only partly stabilises, e.g. hash-table iteration order);</li>
 * <li>{@code skip: true} -- do not check the output at all (for random / non-repeatable
 * results); the exit status is still asserted.</li>
 * </ul>
 * When {@code expect} is omitted the baseline check is "exit 0 and non-empty
 * output".</li>
 * <li>{@code systemPath} -- directory (or LIST of directories) added as
 * {@code --system-path}, the ASDF source registry. Each element is resolved against the
 * project directory and they are joined with the platform path separator, so a system
 * whose dependencies are vendored side by side (rove needs rove + dissect + cl-ppcre)
 * names them all. Passed to the interpreter leg as well as the compiling ones.</li>
 * <li>{@code workDir} -- sub-directory under {@code examples/} the process runs from
 * (default: none, i.e. the throwaway workdir itself). Set it when the script's
 * CWD-relative reads are written against a book root (e.g.
 * {@code deep-learning-from-scratch/}); the leg's CWD becomes
 * {@code work/<workDir>/}.</li>
 * <li>{@code workFiles} -- files to stage beside the program before it runs (list of
 * paths, relative to {@code workDir} when set, otherwise relative to {@code examples/}).
 * Each is copied 1:1 into the workspace, so the mirrored slice looks like the fragment of
 * {@code examples/} the script was written against. A missing file aborts the leg as a
 * skipped assumption (not a failure), so datasets that need
 * {@code deep-learning-from-scratch/download-mnist.sh} silently gate on file
 * presence.</li>
 * </ul>
 * Exact matches ({@code equals}/{@code file}) are compared line-by-line, tolerant of a
 * trailing newline, and are asserted against every RUN backend -- so a per-backend
 * divergence is a real failure, not silently accepted.
 * <p>
 * Each example runs in a throwaway working directory, because a few of them write scratch
 * files (poem.txt, numbers.txt, ...) next to themselves.
 * <p>
 * The driver is the native binary when {@code -Drontolisp.binary=<path>} is set;
 * otherwise it is the built {@code target/*-exec.jar} ({@code java -jar}), but ONLY when
 * {@code -Drontolisp.examples=true} opts in -- so a plain {@code mvn test} skips this
 * heavy suite even when a jar happens to sit in {@code target/}. The {@code wasm} run
 * backend is additionally skipped when {@code wasmtime} is not on the {@code PATH}; the
 * compile backends need no runtime.
 * <p>
 * Run it with:
 *
 * <pre>{@code
 * ./mvnw clean package -DskipTests            # build the exec jar once
 * ./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false -Drontolisp.examples=true test
 * # ...or against the native binary (no -Drontolisp.examples needed):
 * ./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false \
 *        -Drontolisp.binary="$PWD/target/rontolisp" test
 * }</pre>
 *
 * The whole suite takes minutes. While iterating on one example, narrow it with
 * {@code -Drontolisp.examples.only=<substrings>} -- a comma-separated list matched
 * against the manifest path, so {@code =cloudflare} runs one directory and
 * {@code =console/,ml/} runs two. A pattern that matches nothing produces no tests at all
 * (a skipped assumption naming the pattern), never the whole suite -- so a typo is
 * visible as "Tests run: 0" rather than as an unexpectedly long run.
 */
class ExamplesE2eTest {

	private static final Path PROJECT_DIR = Path.of("").toAbsolutePath();

	private static final Path EXAMPLES_DIR = PROJECT_DIR.resolve("examples");

	private static final Path MANIFEST = EXAMPLES_DIR.resolve("examples.yaml");

	/** Per-process wall-clock cap; the slowest interpreter examples take ~20s. */
	private static final long TIMEOUT_SECONDS = 240;

	private static final YAMLMapper MAPPER = YAMLMapper.builder()
		.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
		.build();

	enum Backend {

		// RUN backends run the program; COMPILE backends only build it (see verify()).
		INTERPRETER, JVM, WASM, JVM_COMPILE, WAR_COMPILE, WASM_COMPONENT, WASM_REACTOR, NO_GC, NO_GC_SIMD;

		/**
		 * The manifest spelling: lower-case with hyphens (e.g. {@code wasm-component}).
		 */
		static Backend fromToken(String token) {
			String normalized = token.trim().toUpperCase(Locale.ROOT).replace('-', '_');
			return Backend.valueOf(normalized);
		}

		/**
		 * Whether this backend runs the program (rather than only compiling it) -- gates
		 * whether {@code workFiles} must actually exist ahead of the leg.
		 */
		boolean runsProgram() {
			return this == INTERPRETER || this == JVM || this == WASM;
		}

	}

	record Expect(@Nullable String equals, @Nullable String file, @Nullable List<String> contains,
			@Nullable Boolean skip) {
	}

	record Example(String path, List<String> backends, @Nullable List<String> os, @Nullable List<String> args,
			@Nullable String stdin, @Nullable String stdinFile, @Nullable Expect expect,
			@Nullable List<String> systemPath, @Nullable String workDir, @Nullable List<String> workFiles,
			@Nullable Map<String, String> env, @Nullable Boolean simd, @Nullable Boolean parallel,
			@Nullable List<String> library, @Nullable String note) {

		List<String> argsOrEmpty() {
			return this.args == null ? List.of() : this.args;
		}

		/**
		 * Whether this machine is one the example can RUN on. An example that names no
		 * {@code os} runs everywhere; one that names some runs only there.
		 */
		boolean runnableOnThisOs() {
			return this.os == null || this.os.stream().anyMatch(token -> parseOs(token) == OS.current());
		}

		/**
		 * Whether this machine HAS one of the shared libraries the example calls into
		 * through {@code cffi}. The names are spelled as a {@code define-foreign-library}
		 * spells them (one per platform), and the leg needs only one to resolve -- the
		 * example's own library definition picks the right one. An example that names
		 * none needs none.
		 */
		boolean foreignLibrariesPresent() {
			return this.library == null || this.library.stream().anyMatch(ExamplesE2eTest::libraryLoadable);
		}

		Map<String, String> envOrEmpty() {
			return this.env == null ? Map.of() : this.env;
		}

		boolean simdOn() {
			return Boolean.TRUE.equals(this.simd);
		}

		/** The {@code --simd} flag when the example asks for it, else nothing. */
		List<String> simdFlag() {
			return simdOn() ? List.of("--simd") : List.of();
		}

		/**
		 * The {@code --parallel} flag for a threaded leg when the example asks for it,
		 * else nothing.
		 */
		List<String> parallelFlag() {
			return Boolean.TRUE.equals(this.parallel) ? List.of("--parallel") : List.of();
		}
	}

	record Manifest(List<Example> examples) {
	}

	/**
	 * The manifest's {@code systemPath} takes one directory or a LIST of them, and each
	 * element is absolutized SEPARATELY before they are joined.
	 * <p>
	 * {@code --system-path} is a {@link File#pathSeparator}-joined list
	 * ({@code RontoLispCli.systemPath}), so writing {@code "a:b"} as a single path used
	 * to absolutize only {@code a} and leave {@code b} resolving against the throwaway
	 * working directory the leg runs in -- silently, as a system that is simply not
	 * found. rove needs three directories (rove, dissect, cl-ppcre), which is what forced
	 * the list; the single-directory spelling is still what most entries use.
	 * <p>
	 * Needs neither the examples opt-in nor a driver, so it runs in a plain
	 * {@code mvn test}.
	 * @throws Exception if the inline manifest cannot be parsed
	 */
	@Test
	void systemPathTakesOneDirectoryOrAList() throws Exception {
		Manifest manifest = MAPPER.readValue("""
				examples:
				  - path: one.lisp
				    backends: [interpreter]
				    systemPath: src/test/resources/cl-who
				  - path: many.lisp
				    backends: [interpreter]
				    systemPath:
				      - src/test/resources/rove
				      - src/test/resources/dissect
				  - path: none.lisp
				    backends: [interpreter]
				""", Manifest.class);
		Example one = manifest.examples().get(0);
		Example many = manifest.examples().get(1);
		Example none = manifest.examples().get(2);

		assertThat(systemPathFlags(one)).containsExactly("--system-path",
				PROJECT_DIR.resolve("src/test/resources/cl-who").toString());
		assertThat(systemPathFlags(many)).containsExactly("--system-path",
				PROJECT_DIR.resolve("src/test/resources/rove") + File.pathSeparator
						+ PROJECT_DIR.resolve("src/test/resources/dissect"));
		assertThat(systemPathFlags(none)).isEmpty();
	}

	/**
	 * Every directory the real manifest names actually exists: a mistyped one would
	 * otherwise surface only as "system not found" inside an opt-in leg nobody runs
	 * locally.
	 * @throws Exception if the manifest cannot be read
	 */
	@Test
	void everySystemPathDirectoryInTheManifestExists() throws Exception {
		assumeTrue(Files.isRegularFile(MANIFEST), () -> "manifest not found: " + MANIFEST);
		for (Example example : loadManifest().examples()) {
			if (example.systemPath() == null) {
				continue;
			}
			for (String dir : example.systemPath()) {
				assertThat(PROJECT_DIR.resolve(dir))
					.as("%s declares a systemPath directory that does not exist: %s", example.path(), dir)
					.isDirectory();
			}
		}
	}

	/**
	 * {@code os} gates the RUN legs and leaves the COMPILE legs alone, so an example that
	 * needs a platform to execute still has its lowering checked on every machine. Needs
	 * neither the examples opt-in nor a driver, and asserts the same thing on any
	 * platform: the gate follows {@link OS#current()}.
	 * @throws Exception if the inline manifest cannot be parsed
	 */
	@Test
	void osGatesTheRunLegsOnly() throws Exception {
		Manifest manifest = MAPPER.readValue("""
				examples:
				  - path: mac-only.lisp
				    backends: [interpreter, jvm, jvm-compile]
				    os: [mac]
				  - path: one-token.lisp
				    backends: [interpreter]
				    os: mac
				  - path: everywhere.lisp
				    backends: [interpreter, jvm-compile]
				""", Manifest.class);
		Example macOnly = manifest.examples().get(0);
		Example oneToken = manifest.examples().get(1);
		Example everywhere = manifest.examples().get(2);
		boolean elsewhere = OS.current() != OS.MAC;

		assertThat(skippedForOs(Backend.INTERPRETER, macOnly)).isEqualTo(elsewhere);
		assertThat(skippedForOs(Backend.JVM, macOnly)).isEqualTo(elsewhere);
		// ACCEPT_SINGLE_VALUE_AS_ARRAY: `os: mac` is the same declaration as `os: [mac]`.
		assertThat(skippedForOs(Backend.INTERPRETER, oneToken)).isEqualTo(elsewhere);
		// A COMPILE leg never executes the program, so no platform gates it.
		assertThat(skippedForOs(Backend.JVM_COMPILE, macOnly)).isFalse();
		assertThat(skippedForOs(Backend.INTERPRETER, everywhere)).isFalse();
		assertThat(skippedForOs(Backend.JVM_COMPILE, everywhere)).isFalse();
	}

	/**
	 * Every {@code os} token the real manifest names is a platform. A typo would
	 * otherwise gate an example out of every machine there is -- silently, since a gated
	 * leg is a skip.
	 * @throws Exception if the manifest cannot be read
	 */
	@Test
	void everyOsTokenInTheManifestIsKnown() throws Exception {
		assumeTrue(Files.isRegularFile(MANIFEST), () -> "manifest not found: " + MANIFEST);
		for (Example example : loadManifest().examples()) {
			if (example.os() == null) {
				continue;
			}
			for (String token : example.os()) {
				assertThatCode(() -> parseOs(token))
					.as("%s declares an os token that names no platform: %s", example.path(), token)
					.doesNotThrowAnyException();
			}
		}
	}

	@TestFactory
	Stream<DynamicNode> examples() throws Exception {
		List<String> driver = resolveDriver();
		assumeTrue(driver != null,
				() -> "examples E2E is opt-in: pass -Drontolisp.examples=true (uses target/*-exec.jar, so run "
						+ "./mvnw clean package -DskipTests first) or -Drontolisp.binary=<native binary>. Looked in "
						+ PROJECT_DIR.resolve("target"));
		assumeTrue(Files.isRegularFile(MANIFEST), () -> "manifest not found: " + MANIFEST);

		Manifest manifest = loadManifest();
		String only = System.getProperty("rontolisp.examples.only");
		List<Example> selected = manifest.examples().stream().filter(example -> matchesOnly(example, only)).toList();
		assumeTrue(!selected.isEmpty(),
				() -> "-Drontolisp.examples.only=" + only + " matched no example in " + MANIFEST);

		List<DynamicNode> nodes = new ArrayList<>();
		for (Example example : selected) {
			nodes.add(exampleNode(example, driver));
		}
		return nodes.stream();
	}

	/**
	 * One {@code os} token from the manifest, as the platform it names. A typo fails the
	 * leg (and {@link #everyOsTokenInTheManifestIsKnown()}) with the spelling that was
	 * wrong, rather than quietly gating the example out of every machine there is.
	 * @param token the manifest spelling, e.g. {@code mac}
	 * @return the platform
	 */
	static OS parseOs(String token) {
		try {
			return OS.valueOf(token.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException(
					"unknown os token in examples.yaml: '" + token + "' -- expected one of " + List.of(OS.values()),
					ex);
		}
	}

	/**
	 * Whether this leg is gated out by the example's {@code os}. Only a RUN leg is: a
	 * COMPILE leg builds the program without executing it, so it is as portable as the
	 * compiler and keeps running everywhere.
	 * @param backend the leg
	 * @param example the manifest entry
	 * @return {@code true} when the leg must be skipped here
	 */
	static boolean skippedForOs(Backend backend, Example example) {
		return backend.runsProgram() && !example.runnableOnThisOs();
	}

	/**
	 * Whether this leg is gated out by the example's {@code library}: a RUN leg of a
	 * {@code cffi} example on a machine that has none of the shared libraries it calls. A
	 * COMPILE leg is not gated -- it never loads anything.
	 * @param backend the leg
	 * @param example the manifest entry
	 * @return {@code true} when the leg must be skipped here
	 */
	static boolean skippedForLibrary(Backend backend, Example example) {
		return backend.runsProgram() && !example.foreignLibrariesPresent();
	}

	/** Whether the platform's loader resolves a shared library by that name. */
	private static boolean libraryLoadable(String name) {
		try (Arena arena = Arena.ofConfined()) {
			SymbolLookup.libraryLookup(name, arena);
			return true;
		}
		catch (RuntimeException | Error ex) {
			return false;
		}
	}

	/**
	 * Whether {@code example} survives the {@code -Drontolisp.examples.only=...} filter:
	 * a comma-separated list of substrings matched against the manifest path, so
	 * {@code -Drontolisp.examples.only=cloudflare} runs one directory and
	 * {@code =console/,ml/} runs two. Unset (or blank) selects everything.
	 */
	private static boolean matchesOnly(Example example, @Nullable String only) {
		if (only == null || only.isBlank()) {
			return true;
		}
		for (String pattern : only.split(",")) {
			String trimmed = pattern.trim();
			if (!trimmed.isEmpty() && example.path().contains(trimmed)) {
				return true;
			}
		}
		return false;
	}

	private static DynamicContainer exampleNode(Example example, List<String> driver) {
		Path source = EXAMPLES_DIR.resolve(example.path());
		List<DynamicNode> tests = new ArrayList<>();
		for (String token : example.backends()) {
			Backend backend = Backend.fromToken(token);
			tests.add(dynamicTest(token, () -> {
				if (skippedForOs(backend, example)) {
					abort(example.path() + " runs on " + example.os() + " only; this is " + OS.current());
				}
				if (skippedForLibrary(backend, example)) {
					abort(example.path() + " needs one of " + example.library() + "; this machine has none");
				}
				if (backend == Backend.WASM && !onPath("wasmtime")) {
					abort("wasmtime not on PATH");
				}
				assertThat(Files.isRegularFile(source)).as("example source is missing: %s", source).isTrue();
				Path work = Files.createTempDirectory("rontolisp-example-");
				try {
					verify(backend, example, source, driver, work);
				}
				finally {
					deleteRecursively(work);
				}
			}));
		}
		return dynamicContainer(example.path(), tests.stream());
	}

	private static void verify(Backend backend, Example example, Path source, List<String> driver, Path work)
			throws Exception {
		Path runDir = stageWorkspace(example, work, backend);
		String src = source.toString();
		List<String> args = example.argsOrEmpty();
		Map<String, String> env = example.envOrEmpty();
		byte @Nullable [] stdin = resolveStdin(example);
		// --simd is a compile-time flag on every path, the interpreter's included; the
		// --system-path flags come with it.
		List<String> flags = concat(example.simdFlag(), systemPathFlags(example));
		switch (backend) {
			case INTERPRETER -> {
				// The flags come BEFORE the program's own arguments, and a `--` separates
				// the two: rontolisp's options end there and the program's begin, which
				// is what puts them in (uiop:command-line-arguments). Without the
				// separator an argument would be read as a second input file. An
				// interpreted example needs --system-path just as much as a compiled one
				// -- asdf:load-system resolves the .asd at run time here.
				List<String> ownArguments = args.isEmpty() ? List.of() : concat(List.of("--"), args);
				Result run = exec(runDir,
						concat(vectorApiDriver(driver, example),
								concat(concat(List.of(src), concat(flags, example.parallelFlag())), ownArguments)),
						stdin, env);
				assertRan(run, example, "interpreter");
			}
			case JVM -> {
				Result compile = exec(runDir,
						concat(driver, concat(List.of(src, "-o", "Prog.class"), concat(flags, example.parallelFlag()))),
						null, Map.of());
				assertCompiled(compile, example, "jvm (compile)");
				assertNoOsrHostileBackedges(runDir, example, "jvm (compile)");
				List<String> java = example.simdOn() ? List.of("java", "--add-modules", "jdk.incubator.vector")
						: List.of("java");
				Result run = exec(runDir, concat(java, concat(List.of("-cp", jvmClasspath(runDir), "Prog"), args)),
						stdin, env);
				assertRan(run, example, "jvm (run)");
			}
			case WASM -> {
				Result compile = exec(runDir,
						concat(driver, concat(List.of(src, "-o", "prog.wasm", "--optimize"), flags)), null, Map.of());
				assertCompiled(compile, example, "wasm (compile)");
				Result run = exec(runDir,
						concat(concat(List.of("wasmtime", "run", "-W", "gc", "-W", "exceptions=y", "--dir", "."),
								concat(wasmtimeEnvFlags(env), List.of("prog.wasm"))), args),
						stdin, Map.of());
				assertRan(run, example, "wasm (run)");
			}
			case JVM_COMPILE -> {
				Result compile = exec(runDir, concat(driver, concat(List.of(src, "-o", "Prog.class"), flags)), null,
						Map.of());
				assertCompiled(compile, example, "jvm-compile");
				assertNoOsrHostileBackedges(runDir, example, "jvm-compile");
			}
			case WAR_COMPILE -> {
				// The Servlet transport: the container owns the port, so there is
				// nothing to run here -- the war is deployed, and the deployment
				// itself is pinned by WarE2eTest / ClackE2eTest.
				Result compile = exec(runDir, concat(driver, concat(List.of(src, "-o", "app.war"), flags)), null,
						Map.of());
				assertCompiled(compile, example, "war-compile");
			}
			case WASM_COMPONENT -> {
				Result compile = exec(runDir,
						concat(driver, concat(List.of(src, "-o", "prog.wasm", "--component", "--optimize"), flags)),
						null, Map.of());
				assertCompiled(compile, example, "wasm-component");
			}
			case WASM_REACTOR -> {
				// The host CALLS the module (a Cloudflare Worker, a browser page,
				// node) rather than running it, so this leg builds the reactor shape
				// the directory's own build.sh deploys.
				Result compile = exec(runDir,
						concat(driver, concat(List.of(src, "-o", "prog.wasm", "--no-wasi", "--optimize"), flags)), null,
						Map.of());
				assertCompiled(compile, example, "wasm-reactor");
			}
			case NO_GC -> {
				Result compile = exec(runDir,
						concat(driver, concat(List.of(src, "-o", "prog.wasm", "--no-gc", "--optimize"), flags)), null,
						Map.of());
				assertCompiled(compile, example, "no-gc");
			}
			case NO_GC_SIMD -> {
				Result compile = exec(runDir,
						concat(driver,
								concat(List.of(src, "-o", "prog.wasm", "--no-gc", "--simd", "--optimize"), flags)),
						null, Map.of());
				assertCompiled(compile, example, "no-gc-simd");
			}
		}
	}

	/**
	 * The interpreter driver for a {@code simd: true} example: the exec-jar driver gains
	 * {@code --add-modules jdk.incubator.vector} (without it {@code --simd} warns and
	 * runs the scalar kernels -- correct, but minutes per llama2 token); the native
	 * binary needs nothing.
	 */
	private static List<String> vectorApiDriver(List<String> driver, Example example) {
		if (!example.simdOn() || driver.isEmpty() || !"java".equals(driver.get(0))) {
			return driver;
		}
		return concat(List.of("java", "--add-modules", "jdk.incubator.vector"), driver.subList(1, driver.size()));
	}

	/** {@code --env NAME=VALUE} for every manifest {@code env} entry. */
	private static List<String> wasmtimeEnvFlags(Map<String, String> env) {
		List<String> flags = new ArrayList<>();
		for (Map.Entry<String, String> e : env.entrySet()) {
			flags.add("--env");
			flags.add(e.getKey() + "=" + e.getValue());
		}
		return flags;
	}

	/**
	 * Prepare the working directory the leg will run in and stage any files the example
	 * needs beside it.
	 * <p>
	 * With {@code workDir} set, the process CWD becomes {@code work/<workDir>} so a
	 * script written to run from {@code examples/<workDir>/} keeps its CWD-relative reads
	 * (like {@code "ch07/params.bin"}) resolving. {@code workFiles} entries are relative
	 * to {@code workDir} (or to {@code examples/} when it is unset) and are copied 1:1
	 * into the workspace, so the mirrored slice looks like the fragment of
	 * {@code examples/} the script was written against. If a required file is missing
	 * (the MNIST idx dumps are gitignored, fetched by
	 * {@code deep-learning-from-scratch/download-mnist.sh}) the leg is skipped rather
	 * than failed -- same shape as the wasmtime-on-PATH check.
	 */
	private static Path stageWorkspace(Example example, Path work, Backend backend) throws IOException {
		Path runDir = work;
		if (example.workDir() != null && !example.workDir().isBlank()) {
			runDir = work.resolve(example.workDir());
			Files.createDirectories(runDir);
		}
		if (example.workFiles() == null || example.workFiles().isEmpty()) {
			return runDir;
		}
		// workFiles are runtime inputs -- a compile-only leg does not read them, so it
		// stays green whether or not the file is present. RUN legs skip themselves when a
		// required file is absent (same shape as the wasmtime-on-PATH check), so CI
		// without the gitignored idx dumps still exercises every leg it can.
		if (!backend.runsProgram()) {
			return runDir;
		}
		Path anchor = (example.workDir() == null || example.workDir().isBlank()) ? EXAMPLES_DIR
				: EXAMPLES_DIR.resolve(example.workDir());
		for (String rel : example.workFiles()) {
			Path src = anchor.resolve(rel);
			if (!Files.isRegularFile(src)) {
				abort("required work file is missing: " + rel + " (looked in " + src
						+ ") -- run the example directory's download script (deep-learning-from-scratch/download-mnist.sh"
						+ " for a dataset/*-ubyte file, llama2/download-stories15M.sh for stories15M.bin)");
			}
			Path dst = runDir.resolve(rel);
			Path parent = dst.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.copy(src, dst);
		}
		return runDir;
	}

	private static void assertCompiled(Result result, Example example, String label) {
		assertThat(result.exit())
			.as("%s: %s failed to compile (exit %d)%n%s", example.path(), label, result.exit(), result.diagnostics())
			.isZero();
	}

	private static void assertRan(Result result, Example example, String label) throws IOException {
		assertThat(result.exit()).as("%s: %s exited %d%n%s", example.path(), label, result.exit(), result.diagnostics())
			.isZero();
		checkOutput(result.stdout(), example, label);
	}

	private static void checkOutput(String stdout, Example example, String label) throws IOException {
		Expect expect = example.expect();
		if (expect == null) {
			assertThat(stdout.isBlank()).as("%s: %s produced no output", example.path(), label).isFalse();
			return;
		}
		if (Boolean.TRUE.equals(expect.skip())) {
			return;
		}
		if (expect.contains() != null && !expect.contains().isEmpty()) {
			for (String needle : expect.contains()) {
				assertThat(stdout).as("%s: %s output is missing %s", example.path(), label, needle).contains(needle);
			}
			return;
		}
		String expected;
		if (expect.file() != null) {
			expected = Files.readString(EXAMPLES_DIR.resolve(expect.file()));
		}
		else if (expect.equals() != null) {
			expected = expect.equals();
		}
		else {
			// Misconfigured expect (no mode set): fall back to the non-empty baseline.
			assertThat(stdout.isBlank()).as("%s: %s produced no output", example.path(), label).isFalse();
			return;
		}
		assertThat(lines(stdout)).as("%s: %s output did not match the expected value", example.path(), label)
			.isEqualTo(lines(expected));
	}

	private static byte @Nullable [] resolveStdin(Example example) throws IOException {
		if (example.stdin() != null) {
			return example.stdin().getBytes(StandardCharsets.UTF_8);
		}
		if (example.stdinFile() != null) {
			return Files.readAllBytes(EXAMPLES_DIR.resolve(example.stdinFile()));
		}
		return null;
	}

	private record Result(int exit, String stdout, String stderr, boolean timedOut) {
		String diagnostics() {
			String out = this.timedOut ? "TIMED OUT after " + TIMEOUT_SECONDS + "s\n" : "";
			return out + "--- stdout ---\n" + this.stdout + "\n--- stderr ---\n" + this.stderr;
		}
	}

	private static Result exec(Path work, List<String> command, byte @Nullable [] stdin, Map<String, String> env)
			throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile());
		builder.environment().putAll(env);
		Process process = builder.start();
		try (OutputStream in = process.getOutputStream()) {
			if (stdin != null) {
				in.write(stdin);
			}
		}
		byte[] out = process.getInputStream().readAllBytes();
		byte[] err = process.getErrorStream().readAllBytes();
		boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
			process.waitFor();
			return new Result(-1, str(out), str(err), true);
		}
		return new Result(process.exitValue(), str(out), str(err), false);
	}

	private static @Nullable List<String> resolveDriver() {
		String binary = System.getProperty("rontolisp.binary");
		if (binary != null) {
			Path bin = Path.of(binary).toAbsolutePath();
			return Files.isExecutable(bin) ? List.of(bin.toString()) : null;
		}
		// Opt-in against the exec jar: only when -Drontolisp.examples=true is passed, so
		// a
		// plain `mvn test` (which may have a stale exec jar in target/) does not trigger
		// this heavy, subprocess-spawning suite.
		if (!Boolean.getBoolean("rontolisp.examples")) {
			return null;
		}
		String jarProp = System.getProperty("rontolisp.jar");
		Path jar = jarProp != null ? Path.of(jarProp) : newestExecJar();
		return (jar != null && Files.isRegularFile(jar)) ? List.of("java", "-jar", jar.toAbsolutePath().toString())
				: null;
	}

	/**
	 * Asserts the emitted class holds no backward branch into a non-empty operand stack.
	 * HotSpot can only enter an on-stack-replacement compilation at a backedge whose
	 * operand stack is empty, and a method entered once -- every top-level form, every
	 * {@code defun} called once with a long loop inside -- has no other route into a
	 * compiled version, so such a loop runs in the bytecode interpreter forever
	 * ({@code .kb/jvm-osr-backedges.md}). {@code JvmOsrBackedgeCorpusTest} pins the same
	 * invariant over {@code ci-spec.yaml}; this leg covers the examples.
	 */
	private static void assertNoOsrHostileBackedges(Path runDir, Example example, String leg) throws IOException {
		Path classFile = runDir.resolve("Prog.class");
		if (!Files.isRegularFile(classFile)) {
			return;
		}
		assertThat(am.ik.jvm.StackMapAugmenter.osrHostileBackedges(Files.readAllBytes(classFile)))
			.as("[example '" + example.path() + "' on " + leg + "] backward branches into a non-empty "
					+ "operand stack -- HotSpot refuses to OSR-compile such a method (.kb/jvm-osr-backedges.md)")
			.isEmpty();
	}

	/**
	 * The classpath a compiled {@code Prog.class} runs on: the workspace, plus the
	 * rontolisp runtime as a SUPERSET. Every example is self-contained today -- the last
	 * ones that were not were the serving programs, whose embedded server now travels
	 * beside the class ({@code .kb/jvm-export.md}, "What travels"), which is why their
	 * READMEs say {@code java -cp . App}. The runtime stays on the classpath here because
	 * a superset cannot break a leg, and dropping it would make this suite the only place
	 * a future non-travelling support class is discovered -- as a
	 * {@code NoClassDefFoundError} rather than as the compile-time list failure
	 * {@code JvmRuntimeClassFilesTest} is for.
	 * <p>
	 * The runtime is the exec jar when one was built, and {@code target/classes}
	 * otherwise: driven by {@code -Drontolisp.binary} (how CI runs this suite) nothing
	 * ever packages a jar, and a missing runtime does not degrade -- it fails those legs
	 * with {@code NoClassDefFoundError}. The two are interchangeable here because the
	 * exec jar is a shade of {@code target/classes} with no external dependencies.
	 * @param runDir the workspace the leg runs in
	 * @return the {@code -cp} value
	 */
	private static String jvmClasspath(Path runDir) {
		Path runtime = newestExecJar();
		if (runtime == null) {
			Path classes = PROJECT_DIR.resolve("target").resolve("classes");
			runtime = Files.isDirectory(classes) ? classes : null;
		}
		return runtime == null ? runDir.toString() : runDir + File.pathSeparator + runtime.toAbsolutePath();
	}

	private static @Nullable Path newestExecJar() {
		Path target = PROJECT_DIR.resolve("target");
		if (!Files.isDirectory(target)) {
			return null;
		}
		try (Stream<Path> jars = Files.list(target)) {
			return jars.filter(p -> p.getFileName().toString().endsWith("-exec.jar"))
				.max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()))
				.orElse(null);
		}
		catch (IOException ex) {
			return null;
		}
	}

	/**
	 * The {@code --system-path} flag pair for an example's {@code systemPath}, or nothing
	 * when it declares none.
	 * <p>
	 * {@code --system-path} takes a {@link File#pathSeparator}-joined LIST of directories
	 * ({@code RontoLispCli.systemPath}), and each element is resolved against the project
	 * directory separately -- the leg runs from a throwaway working directory, so a
	 * relative element would otherwise resolve against that. Joining first and
	 * absolutizing the result would only absolutize the first element; rove needs three
	 * directories, which is why the manifest field is a list.
	 * @param example the manifest entry
	 * @return {@code ["--system-path", "<abs>:<abs>:..."]}, or an empty list
	 */
	static List<String> systemPathFlags(Example example) {
		List<String> declared = example.systemPath();
		if (declared == null || declared.isEmpty()) {
			return List.of();
		}
		String joined = declared.stream()
			.filter(dir -> !dir.isBlank())
			.map(dir -> PROJECT_DIR.resolve(dir).toAbsolutePath().toString())
			.collect(Collectors.joining(File.pathSeparator));
		return joined.isEmpty() ? List.of() : List.of("--system-path", joined);
	}

	/**
	 * Reads {@code examples.yaml}. {@code ACCEPT_SINGLE_VALUE_AS_ARRAY} is what lets a
	 * one-directory {@code systemPath} keep its scalar spelling now that the field is a
	 * list.
	 * @return the parsed manifest
	 * @throws IOException if the manifest cannot be read
	 */
	private static Manifest loadManifest() throws IOException {
		return MAPPER.readValue(Files.readString(MANIFEST), Manifest.class);
	}

	private static List<String> concat(List<String> a, List<String> b) {
		if (b.isEmpty()) {
			return a;
		}
		List<String> out = new ArrayList<>(a.size() + b.size());
		out.addAll(a);
		out.addAll(b);
		return out;
	}

	/** Split into lines, dropping a single trailing empty line so "a\n" == "a". */
	private static List<String> lines(String text) {
		if (text.isEmpty()) {
			return List.of();
		}
		List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
		if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}
		return lines;
	}

	private static String str(byte[] bytes) {
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static boolean onPath(String tool) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(File.pathSeparator)) {
			if (Files.isExecutable(Path.of(dir).resolve(tool))) {
				return true;
			}
		}
		return false;
	}

	private static void deleteRecursively(Path root) {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				}
				catch (IOException ignored) {
					// best effort cleanup
				}
			});
		}
		catch (IOException ignored) {
			// best effort cleanup
		}
	}

}
