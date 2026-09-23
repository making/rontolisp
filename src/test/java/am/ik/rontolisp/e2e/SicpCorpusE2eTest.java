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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.LispExitSignal;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.scheme.Scheme;
import am.ik.rontolisp.testsupport.HostWasmtime;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Runs the SICP sample corpus ({@code https://sicp.sourceacademy.org/sicp.zip}, unpacked;
 * never checked in) on all four backends and requires the same output the interpreter
 * gives. The opt-in E2E of {@code .todo/828}.
 *
 * <p>
 * Run it with:
 *
 * <pre>{@code
 * ./mvnw clean package -DskipTests            # the wasm legs drive `wasmtime`, nothing else
 * ./mvnw -Dtest=SicpCorpusE2eTest -DfailIfNoTests=false -Drontolisp.sicp=<unpacked dir> test
 * # ...while iterating: -Drontolisp.sicp.only=<substring> runs the matching files only.
 * }</pre>
 *
 * <p>
 * Without {@code -Drontolisp.sicp} every factory aborts: a plain {@code mvn test} never
 * touches the corpus. {@code <unpacked dir>} is the directory holding
 * {@code programs_scm/} (the zip's root after unpacking).
 *
 * <p>
 * What runs per file is decided by {@code src/test/resources/sicp-manifest.tsv} (one row
 * per file: category, expected outcome, note):
 * <ul>
 * <li>{@code scheme}/{@code concurrent} with {@code ok} -- interpreter, JVM, wasm and
 * component; every backend must exit 0 with byte-identical stdout to the interpreter's.
 * {@code timing} compares modulo the wall-clock seconds the two {@code timed-prime}
 * samples print; {@code timeout}/{@code error} pin the two samples that behave as the
 * book says and run on the interpreter only.</li>
 * <li>{@code fragment} and {@code embedded-*} -- the interpreter only, lenient: the
 * recorded outcome is informational, drift from it never fails. A fragment that starts to
 * fail differently is not a failure; the 12 fragments the compile backends refuse (a
 * global VARIABLE nothing defines is a compile error where the interpreter only fails if
 * the path runs) stay fragments by decision, recorded per row.</li>
 * <li>{@code js-import}/{@code unreadable} -- skipped; not Scheme.</li>
 * </ul>
 *
 * <p>
 * The manifest's category is the static rule of
 * {@code .todo/artefacts/828-sicp-sample-corpus-harness/baseline.py}, ported below
 * ({@link Categories}): text markers first, then free names minus the provided set
 * ({@link Scheme#providedNames()}). The suite asserts the port still agrees with the
 * manifest on every file, so a corpus snapshot or a new builtin that moves a file across
 * the scheme/fragment line fails loudly with a "regenerate the manifest" message instead
 * of silently changing what is gated.
 *
 * <p>
 * Regenerating the manifest for a new corpus snapshot: unpack the zip, run every file on
 * the interpreter in file mode (stdin empty) and REPL mode (stdin the file), classify
 * with the rule, run the interpreter-exit-0 files on the JVM and wasm and compare stdout,
 * then write one row per file in the manifest's shape. The header of
 * {@code sicp-manifest.tsv} records the snapshot such a run describes.
 *
 * <p>
 * File mode only: the probes behind this suite measured file mode and REPL mode agreeing
 * on every one of the 1,586 files, so the harness pins file mode and the agreement stays
 * a recorded number ({@code .kb/scheme-frontend.md}), not a second leg per file. The
 * {@code ; expected:} annotations are a positive check only (158 of the 450 agree with
 * the REPL echo; no disagreement is a wrong value -- the annotation names another
 * expression or the file ends in a definition), likewise a recorded number, never a gate.
 *
 * <p>
 * The {@code embedded-*} samples stay excluded for a measured reason, one per family: the
 * corpus does not ship a complete evaluator -- the amb evaluator lacks its
 * {@code (amb? exp)} dispatch clause, {@code define-variable!} and any stream support
 * under {@code ambeval}; the lazy evaluator lacks its {@code eval} dispatch,
 * {@code eval-sequence}, {@code eval-definition}, the whole expression-syntax layer AND
 * {@code define-variable!} (2026-09-18, {@code .todo/856}: the "define-variable! only"
 * premise did not survive measurement -- no corpus file defines {@code self-evaluating?},
 * {@code variable?}, {@code quoted?}, ... either); the query system lacks its whole
 * syntax layer ({@code assertion-to-be-added?}, {@code query-syntax-process}, ...).
 * Feeding them to a driver loop is follow-up work once those pieces exist; the manifest
 * records the reason per row.
 *
 * <p>
 * The amb family's stream-free samples run as driver legs ({@link #ambDrivers()}): the
 * evaluator is one corpus core file
 * ({@code chapter4/section3/subsection3/16_driver_loop_amb.scm}, minus its trailing
 * {@code (driver-loop)} call) over the corpus support files, closed by the
 * harness-written {@code /sicp-amb-glue.scm} (the syntax layer, {@code define-variable!},
 * {@code analyze-quoted}, {@code analyze-sequence}, the {@code amb?}/{@code let?} advice
 * on {@code analyze}, {@code apply-primitive-procedure} over the host {@code apply}, the
 * driver prompts, a driver loop with the EOF clause the book loop lacks, the global
 * environment with the extra primitives the samples need, and the replacing
 * {@code (driver-loop)} call -- no corpus text in it). Stdin per leg is the two corpus
 * prelude files ({@code 03_require_non_det.scm}, {@code 05_an_element_of.scm}) then the
 * sample; the EOF clause ends the run, so the legs stay in-process on every backend (an
 * {@code (exit)} terminator would be {@code System.exit} on the JVM leg); the legs are
 * listed in {@code /sicp-amb-drivers.tsv}. The stream-using interactions (subsection1
 * {@code 08}-{@code 12}, prime-sum-pair) stay excluded -- a {@code (cons-stream a b)}
 * under {@code ambeval} looks up an unbound operator -- and so does
 * {@code 03_office_move.scm}, which finds the book answer but needs ~256 MiB of host
 * stack on the interpreter where the CLI hands every program 16 MiB.
 */
class SicpCorpusE2eTest {

	private static final String MANIFEST_RESOURCE = "/sicp-manifest.tsv";

	private static final String AMB_DRIVERS_RESOURCE = "/sicp-amb-drivers.tsv";

	private static final String AMB_GLUE_RESOURCE = "/sicp-amb-glue.scm";

	private static final long LEG_TIMEOUT_SECONDS = 90;

	// The CLI hands every program 16 MiB (RontoLispCli.WORKER_STACK_BYTES); the
	// in-process legs measure the same ceiling rather than JUnit's.
	private static final long PROGRAM_STACK_BYTES = 16L << 20;

	@TempDir
	static Path workDir;

	record Entry(String file, String category, String expected, String note) {
	}

	@TestFactory
	Stream<DynamicNode> corpus() throws Exception {
		Path root = corpusRoot();
		assumeTrue(root != null, () -> "SICP corpus E2E is opt-in: pass -Drontolisp.sicp=<unpacked sicp.zip dir> "
				+ "(holding programs_scm/)");
		List<Entry> manifest = loadManifest();
		String only = System.getProperty("rontolisp.sicp.only");
		List<Entry> selected = manifest.stream()
			.filter(entry -> only == null || only.isBlank() || entry.file().contains(only))
			.toList();
		assumeTrue(!selected.isEmpty(), () -> "-Drontolisp.sicp.only=" + only + " matched no manifest row");

		checkManifestCovers(root, manifest, only);
		List<DynamicNode> nodes = new ArrayList<>();
		for (Entry entry : selected) {
			nodes.add(fileNode(root, entry));
		}
		return nodes.stream();
	}

	/**
	 * The manifest names every corpus file and no other, and the ported rule still
	 * classifies each file as the manifest says. Needs no legs, so corpus drift fails
	 * here rather than as thousands of legs.
	 * @throws Exception if the manifest cannot be read
	 */
	@Test
	void manifestCoversTheCorpusAndAgreesWithThePortedRule() throws Exception {
		Path root = corpusRoot();
		assumeTrue(root != null, () -> "SICP corpus E2E is opt-in: pass -Drontolisp.sicp=<unpacked sicp.zip dir>");
		List<Entry> manifest = loadManifest();
		checkManifestCovers(root, manifest, null);
	}

	/**
	 * The ported category rule on textbook shapes: needs neither the corpus nor a driver,
	 * so it runs in a plain {@code mvn test}.
	 * @throws Exception if classification fails
	 */
	@Test
	void categoriesPortSpotsTheTextbookShapes() throws Exception {
		SequencedSet<String> provided = Scheme.providedNames();
		// The set itself: entries, constants and syntax keywords.
		assertThat(provided).contains("car", "true", "delay", "cons-stream", "parallel-execute");
		assertThat(Categories.classify("chapter1/section1/subsection1/01.scm",
				"(define (square x) (* x x))\n(square 5)\n", provided))
			.isEqualTo("scheme");
		assertThat(Categories.classify("chapter2/section1/subsection1/01.scm", "(define (f x) (g x))\n", provided))
			.isEqualTo("fragment");
		assertThat(Categories.classify("chapter9/section1/subsection1/01.scm", "import { beside } from 'rune';\n",
				provided))
			.isEqualTo("js-import");
		assertThat(Categories.classify("chapter4/section4/subsection1/19_simple_queries_1.scm",
				"(address (Bitdiddle Ben) 10)\n", provided))
			.isEqualTo("embedded-query");
		assertThat(Categories.classify("chapter4/section3/subsection1/05_an_element_of.scm",
				"; chapter=3 variant=non-det\n(define (an-element-of items) (amb (car items)))\n", provided))
			.isEqualTo("embedded-amb");
		assertThat(Categories.classify("chapter4/section2/subsection1/02_try_me.scm",
				"; chapter=2 variant=lazy\n(define (try a b) (if (= a 0) 1 b))\n", provided))
			.isEqualTo("embedded-lazy");
		assertThat(Categories.classify("chapter3/section4/subsection2/01.scm",
				"; chapter=3 variant=concurrent\n(define (f x) (parallel-execute x))\n(f 1)\n", provided))
			.isEqualTo("concurrent");
		assertThatThrownBy(() -> Categories.classify("chapter2/section3/subsection2/25_number_equal_example.scm",
				"(define (f x) x))", provided))
			.isInstanceOf(Categories.Unreadable.class);
		// Quoted names bind nothing and use nothing; a quasiquoted template's commas do.
		assertThat(Categories.classify("q.scm", "(define ops '(+ - *))\n", provided)).isEqualTo("scheme");
		assertThat(Categories.classify("q.scm", "(define (f x) `(a ,x ,@y))\n", provided)).isEqualTo("fragment");
	}

	/**
	 * The timing mask covers the seconds but keeps the trial count: needs neither the
	 * corpus nor a driver.
	 */
	@Test
	void maskTimingKeepsTheTrialCountButNotTheSeconds() {
		assertThat(maskTiming("\n43 *** 0.003000020980834961")).isEqualTo(maskTiming("\n43 *** 0.0"));
		assertThat(maskTiming("\n43 *** 0.003")).isNotEqualTo(maskTiming("\n44 *** 0.003"));
	}

	private static void checkManifestCovers(Path root, List<Entry> manifest,
			@org.jspecify.annotations.Nullable String only) throws IOException {
		SequencedSet<String> onDisk = new LinkedHashSet<>();
		try (Stream<Path> walk = Files.walk(root.resolve("programs_scm"))) {
			walk.filter(Files::isRegularFile)
				.map(path -> root.resolve("programs_scm").relativize(path).toString())
				.filter(name -> name.endsWith(".scm"))
				.forEach(onDisk::add);
		}
		SequencedSet<String> inManifest = new LinkedHashSet<>();
		for (Entry entry : manifest) {
			inManifest.add(entry.file());
		}
		List<String> missing = onDisk.stream().filter(file -> !inManifest.contains(file)).toList();
		List<String> ghost = inManifest.stream().filter(file -> !onDisk.contains(file)).toList();
		assertThat(missing).as("corpus files with no manifest row -- regenerate sicp-manifest.tsv: %s", missing)
			.isEmpty();
		assertThat(ghost).as("manifest rows with no corpus file -- regenerate sicp-manifest.tsv: %s", ghost).isEmpty();
		SequencedSet<String> provided = Scheme.providedNames();
		List<String> drifted = new ArrayList<>();
		for (Entry entry : manifest) {
			if (only != null && !only.isBlank() && !entry.file().contains(only)) {
				continue;
			}
			String text = Files.readString(root.resolve("programs_scm").resolve(entry.file()));
			String actual;
			try {
				actual = Categories.classify(entry.file(), text, provided);
			}
			catch (Categories.Unreadable ex) {
				actual = "unreadable";
			}
			if (!actual.equals(entry.category())) {
				drifted.add(entry.file() + ": manifest=" + entry.category() + " rule=" + actual);
			}
		}
		assertThat(drifted).as("files whose category drifted from the manifest -- regenerate sicp-manifest.tsv")
			.isEmpty();
	}

	private static DynamicContainer fileNode(Path root, Entry entry) {
		Path file = root.resolve("programs_scm").resolve(entry.file());
		List<DynamicNode> legs = new ArrayList<>();
		switch (entry.expected()) {
			case "ok" -> {
				legs.add(dynamicTest("INTERPRETER", () -> {
					Outcome reference = interpret(file);
					assertThat(reference.exit()).as(exitMessage(entry, reference)).isZero();
				}));
				legs.add(dynamicTest("JVM", () -> {
					Outcome reference = interpret(file);
					assertThat(reference.exit()).as(exitMessage(entry, reference)).isZero();
					Outcome actual = runOnJvm(file);
					assertThat(actual.exit()).as(exitMessage(entry, actual)).isZero();
					assertThat(actual.stdout()).as("JVM stdout differs for %s", entry.file())
						.isEqualTo(reference.stdout());
				}));
				legs.add(dynamicTest("WASM", () -> {
					requireWasmtime();
					Outcome reference = interpret(file);
					assertThat(reference.exit()).as(exitMessage(entry, reference)).isZero();
					Outcome actual = runOnWasm(file, false);
					assertThat(actual.exit()).as(exitMessage(entry, actual)).isZero();
					assertThat(actual.stdout()).as("wasm stdout differs for %s", entry.file())
						.isEqualTo(reference.stdout());
				}));
				legs.add(dynamicTest("WASM_COMPONENT", () -> {
					requireWasmtime();
					Outcome reference = interpret(file);
					assertThat(reference.exit()).as(exitMessage(entry, reference)).isZero();
					Outcome actual = runOnWasm(file, true);
					assertThat(actual.exit()).as(exitMessage(entry, actual)).isZero();
					assertThat(actual.stdout()).as("component stdout differs for %s", entry.file())
						.isEqualTo(reference.stdout());
				}));
			}
			case "timing" -> {
				legs.add(dynamicTest("INTERPRETER", () -> assertThat(interpret(file).exit()).isZero()));
				legs.add(dynamicTest("JVM", () -> assertTimingMatches(runOnJvm(file), interpret(file), entry)));
				legs.add(dynamicTest("WASM", () -> {
					requireWasmtime();
					assertTimingMatches(runOnWasm(file, false), interpret(file), entry);
				}));
				legs.add(dynamicTest("WASM_COMPONENT", () -> {
					requireWasmtime();
					assertTimingMatches(runOnWasm(file, true), interpret(file), entry);
				}));
			}
			case "timeout" -> legs.add(dynamicTest("INTERPRETER", () -> {
				Outcome reference = interpret(file);
				assertThat(reference.timedOut())
					.as("%s should never terminate (the book's applicative-order program)", entry.file())
					.isTrue();
			}));
			case "error" -> {
				legs.add(dynamicTest("INTERPRETER", () -> {
					Outcome reference = interpret(file);
					assertThat(reference.timedOut()).isFalse();
					assertThat(reference.exit()).as("%s should end in (error ...) as the book says", entry.file())
						.isNotZero();
				}));
				legs.add(dynamicTest("JVM", () -> {
					Outcome actual = runOnJvm(file);
					assertThat(actual.timedOut()).isFalse();
					assertThat(actual.exit()).as("%s should end in (error ...) on the JVM", entry.file()).isNotZero();
				}));
				legs.add(dynamicTest("WASM", () -> {
					requireWasmtime();
					Outcome actual = runOnWasm(file, false);
					assertThat(actual.exit()).as("%s should end in (error ...) on wasm", entry.file()).isNotZero();
				}));
				legs.add(dynamicTest("WASM_COMPONENT", () -> {
					requireWasmtime();
					Outcome actual = runOnWasm(file, true);
					assertThat(actual.exit()).as("%s should end in (error ...) on the component", entry.file())
						.isNotZero();
				}));
			}
			case "lenient-ok", "lenient-error" -> legs.add(dynamicTest("INTERPRETER (lenient)", () -> {
				Outcome reference = interpret(file);
				boolean wasOk = reference.exit() == 0 && !reference.timedOut();
				boolean wantOk = entry.expected().equals("lenient-ok");
				if (wasOk != wantOk) {
					System.out.printf("[sicp] drift: %s manifest=%s now=%s%n", entry.file(), entry.expected(),
							reference.timedOut() ? "timeout" : "exit-" + reference.exit());
				}
			}));
			case "skip" -> legs.add(dynamicTest("(skipped)", () -> abort(entry.file() + ": " + entry.note())));
			default -> legs.add(dynamicTest("(misconfigured)", () -> {
				throw new IllegalStateException(
						"sicp-manifest.tsv: unknown expected value '" + entry.expected() + "' for " + entry.file());
			}));
		}
		return dynamicContainer(entry.file(), legs.stream());
	}

	/**
	 * The amb driver legs of {@code .todo/856}: every stream-free {@code embedded-amb}
	 * sample in {@code /sicp-amb-drivers.tsv}, fed to the composed amb evaluator over
	 * stdin with the interpreter as the reference -- the same four legs and the same
	 * exit-0 plus byte-identical-stdout contract as a {@code scheme/ok} file.
	 * @throws Exception if the composition cannot be read
	 */
	@TestFactory
	Stream<DynamicNode> ambDrivers() throws Exception {
		Path root = corpusRoot();
		assumeTrue(root != null, () -> "SICP corpus E2E is opt-in: pass -Drontolisp.sicp=<unpacked sicp.zip dir> "
				+ "(holding programs_scm/)");
		String program = composeAmbProgram(root);
		List<String> samples = loadAmbDrivers();
		String only = System.getProperty("rontolisp.sicp.only");
		List<String> selected = samples.stream()
			.filter(sample -> only == null || only.isBlank() || sample.contains(only))
			.toList();
		assumeTrue(!selected.isEmpty(), () -> "-Drontolisp.sicp.only=" + only + " matched no amb driver leg");
		List<DynamicNode> nodes = new ArrayList<>();
		for (String sample : selected) {
			nodes.add(ambDriverNode(program, root, sample));
		}
		return nodes.stream();
	}

	/**
	 * The composed amb evaluator: the corpus support files, then the corpus core file
	 * minus its trailing {@code (driver-loop)} call (the harness drives the input itself;
	 * stdin ends after the sample and the glue loop's EOF clause ends the run), then the
	 * harness glue ending in the replacing {@code (driver-loop)} call. Every addition to
	 * the corpus files is the checked-in {@code /sicp-amb-glue.scm} -- no corpus text is
	 * checked in.
	 */
	static String composeAmbProgram(Path root) throws IOException {
		Path programs = root.resolve("programs_scm");
		StringBuilder program = new StringBuilder();
		for (String support : List.of("chapter4/section1/subsection3/02_true.scm",
				"chapter4/section1/subsection3/04_make_procedure.scm",
				"chapter4/section1/subsection3/12_extend_environment.scm",
				"chapter4/section1/subsection3/14_lookup_variable_value.scm",
				"chapter4/section1/subsection3/16_assign_name_value.scm",
				"chapter4/section1/subsection4/01_setup_environment.scm")) {
			program.append(Files.readString(programs.resolve(support))).append('\n');
		}
		String core = Files.readString(programs.resolve("chapter4/section3/subsection3/16_driver_loop_amb.scm"));
		String stripped = core.stripTrailing();
		assertThat(stripped).as("the amb core file should end in its (driver-loop) call").endsWith("(driver-loop)");
		program.append(stripped, 0, stripped.length() - "(driver-loop)".length()).append('\n');
		program.append(readResource(AMB_GLUE_RESOURCE)).append('\n');
		return program.toString();
	}

	/**
	 * Stdin per amb driver leg: the two corpus prelude files, then the sample. The glue
	 * loop's EOF clause ends the run, so no terminator form is appended.
	 */
	static byte[] ambStdin(Path root, String sample) throws IOException {
		Path programs = root.resolve("programs_scm");
		String stdin = Files.readString(programs.resolve("chapter4/section3/subsection1/03_require_non_det.scm")) + "\n"
				+ Files.readString(programs.resolve("chapter4/section3/subsection1/05_an_element_of.scm")) + "\n"
				+ Files.readString(programs.resolve(sample)) + "\n";
		return stdin.getBytes(StandardCharsets.UTF_8);
	}

	private static DynamicContainer ambDriverNode(String program, Path root, String sample) {
		List<DynamicNode> legs = new ArrayList<>();
		legs.add(dynamicTest("INTERPRETER", () -> {
			Outcome reference = interpretSource(program, sample, ambStdin(root, sample));
			assertThat(reference.exit()).as(exitMessage(sample, reference)).isZero();
		}));
		legs.add(dynamicTest("JVM", () -> {
			byte[] stdin = ambStdin(root, sample);
			Outcome reference = interpretSource(program, sample, stdin);
			assertThat(reference.exit()).as(exitMessage(sample, reference)).isZero();
			Outcome actual = runOnJvmSource(program, stdin);
			assertThat(actual.exit()).as(exitMessage(sample, actual)).isZero();
			assertThat(actual.stdout()).as("JVM stdout differs for %s", sample).isEqualTo(reference.stdout());
		}));
		legs.add(dynamicTest("WASM", () -> {
			requireWasmtime();
			byte[] stdin = ambStdin(root, sample);
			Outcome reference = interpretSource(program, sample, stdin);
			assertThat(reference.exit()).as(exitMessage(sample, reference)).isZero();
			Outcome actual = runOnWasmSource(program, "amb_" + sample, false, stdin);
			assertThat(actual.exit()).as(exitMessage(sample, actual)).isZero();
			assertThat(actual.stdout()).as("wasm stdout differs for %s", sample).isEqualTo(reference.stdout());
		}));
		legs.add(dynamicTest("WASM_COMPONENT", () -> {
			requireWasmtime();
			byte[] stdin = ambStdin(root, sample);
			Outcome reference = interpretSource(program, sample, stdin);
			assertThat(reference.exit()).as(exitMessage(sample, reference)).isZero();
			Outcome actual = runOnWasmSource(program, "amb_" + sample, true, stdin);
			assertThat(actual.exit()).as(exitMessage(sample, actual)).isZero();
			assertThat(actual.stdout()).as("component stdout differs for %s", sample).isEqualTo(reference.stdout());
		}));
		return dynamicContainer(sample, legs.stream());
	}

	private static String exitMessage(String file, Outcome outcome) {
		return file + " exited " + outcome.exit() + (outcome.timedOut() ? " (timed out)" : "") + "\n"
				+ outcome.stdout();
	}

	private static String exitMessage(Entry entry, Outcome outcome) {
		return exitMessage(entry.file(), outcome);
	}

	private static void assertTimingMatches(Outcome actual, Outcome reference, Entry entry) {
		assertThat(reference.exit()).as(exitMessage(entry, reference)).isZero();
		assertThat(actual.exit()).as(exitMessage(entry, actual)).isZero();
		assertThat(maskTiming(actual.stdout())).as("timed output differs for %s", entry.file())
			.isEqualTo(maskTiming(reference.stdout()));
	}

	/**
	 * Masks the wall-clock seconds the two {@code timed-prime} samples print
	 * ({@code 43 *** 0.003}): the trials' count is deterministic, the seconds are not.
	 */
	static String maskTiming(String stdout) {
		return stdout.replaceAll("\\d+\\.\\d+", "#.#");
	}

	private static void requireWasmtime() {
		if (!HostWasmtime.isAvailable()) {
			abort("no usable wasmtime on PATH");
		}
	}

	private record Outcome(int exit, String stdout, boolean timedOut) {
	}

	private static Outcome interpret(Path file) throws Exception {
		String source = Files.readString(file);
		String name = file.getFileName().toString();
		return interpretSource(source, name, new byte[0]);
	}

	private static Outcome interpretSource(String source, String name, byte[] stdin) throws Exception {
		return onAProgramStack(() -> {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8),
					new java.io.ByteArrayInputStream(stdin));
			try {
				for (LispVal form : SourceLanguage.SCHEME.read(source, Features.INTERPRETER, name)) {
					evaluator.eval(form);
				}
			}
			catch (LispExitSignal exit) {
				return new Outcome(exit.code(), out.toString(StandardCharsets.UTF_8), false);
			}
			catch (StackOverflowError overflow) {
				return new Outcome(1, out.toString(StandardCharsets.UTF_8) + "\n[stack overflow]", false);
			}
			catch (Throwable thrown) {
				return new Outcome(1, out.toString(StandardCharsets.UTF_8) + "\n[" + thrown + "]", false);
			}
			return new Outcome(0, out.toString(StandardCharsets.UTF_8), false);
		}, LEG_TIMEOUT_SECONDS);
	}

	private static Outcome runOnJvm(Path file) throws Exception {
		return runOnJvmSource(Files.readString(file), new byte[0]);
	}

	private static Outcome runOnJvmSource(String source, byte[] stdin) throws Exception {
		return onAProgramStack(() -> {
			byte[] classBytes;
			try {
				classBytes = new JvmSourceCompiler("SicpSample").sourceLanguage("scheme")
					.compile(source, null)
					.classBytes();
			}
			catch (Throwable thrown) {
				return new Outcome(1, "[compile failed: " + thrown + "]", false);
			}
			ClassLoader loader = new ClassLoader(SicpCorpusE2eTest.class.getClassLoader()) {
				@Override
				protected Class<?> findClass(String name) throws ClassNotFoundException {
					if (name.equals("SicpSample")) {
						return defineClass(name, classBytes, 0, classBytes.length);
					}
					return super.findClass(name);
				}
			};
			Method main;
			try {
				main = loader.loadClass("SicpSample").getMethod("main", String[].class);
			}
			catch (Throwable thrown) {
				return new Outcome(1, "[load failed: " + thrown + "]", false);
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			PrintStream previousOut = System.out;
			java.io.InputStream previousIn = System.in;
			System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
			System.setIn(new java.io.ByteArrayInputStream(stdin));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			catch (java.lang.reflect.InvocationTargetException thrown) {
				return new Outcome(1, out.toString(StandardCharsets.UTF_8) + "\n[" + thrown.getCause() + "]", false);
			}
			catch (Throwable thrown) {
				return new Outcome(1, out.toString(StandardCharsets.UTF_8) + "\n[" + thrown + "]", false);
			}
			finally {
				System.setOut(previousOut);
				System.setIn(previousIn);
			}
			return new Outcome(0, out.toString(StandardCharsets.UTF_8), false);
		}, LEG_TIMEOUT_SECONDS);
	}

	private static Outcome runOnWasm(Path file, boolean component) throws Exception {
		String source = Files.readString(file);
		String base = file.getFileName().toString().replaceAll("[^A-Za-z0-9]", "_");
		return runOnWasmSource(source, base, component, new byte[0]);
	}

	private static Outcome runOnWasmSource(String source, String name, boolean component, byte[] stdin)
			throws Exception {
		String base = name.replaceAll("[^A-Za-z0-9]", "_");
		CompileFrontendAccess.Program frontend;
		try {
			frontend = CompileFrontendAccess.scheme(source, true, component);
		}
		catch (Throwable thrown) {
			return new Outcome(1, "[compile failed: " + thrown + "]", false);
		}
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		Path moduleFile = workDir.resolve(base + (component ? ".component.wasm" : ".wasm"));
		Files.write(moduleFile, module);
		Path stdinFile = workDir.resolve(base + ".stdin");
		Files.write(stdinFile, stdin);
		Path outFile = Files.createTempFile(workDir, base + "-wasm", ".out");
		Path errFile = Files.createTempFile(workDir, base + "-wasm", ".err");
		try {
			Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
					moduleFile.toString())
				.redirectInput(stdinFile.toFile())
				.redirectOutput(outFile.toFile())
				.redirectError(errFile.toFile())
				.start();
			boolean finished = process.waitFor(LEG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly().waitFor();
				return new Outcome(1, "[wasmtime timed out]", true);
			}
			String stdout = Files.readString(outFile, StandardCharsets.UTF_8);
			if (process.exitValue() != 0) {
				stdout += "\n[" + Files.readString(errFile, StandardCharsets.UTF_8) + "]";
			}
			return new Outcome(process.exitValue(), stdout, false);
		}
		finally {
			Files.deleteIfExists(outFile);
			Files.deleteIfExists(errFile);
		}
	}

	/**
	 * Runs the body on a thread with the CLI's program stack, capped by a wall-clock
	 * timeout (the corpus holds a program that never terminates). A leg that is still
	 * running then is abandoned and reported as timed out.
	 */
	private static Outcome onAProgramStack(java.util.concurrent.Callable<Outcome> body, long timeoutSeconds)
			throws Exception {
		Outcome[] result = new Outcome[1];
		Throwable[] thrown = new Throwable[1];
		Thread worker = new Thread(null, () -> {
			try {
				result[0] = body.call();
			}
			catch (Throwable ex) {
				thrown[0] = ex;
			}
		}, "sicp-corpus", PROGRAM_STACK_BYTES);
		worker.setDaemon(true);
		worker.start();
		worker.join(TimeUnit.SECONDS.toMillis(timeoutSeconds));
		if (worker.isAlive()) {
			worker.interrupt();
			return new Outcome(1, "", true);
		}
		if (thrown[0] instanceof Error error) {
			throw error;
		}
		if (thrown[0] instanceof Exception exception) {
			throw exception;
		}
		if (thrown[0] != null) {
			return new Outcome(1, "[" + thrown[0] + "]", false);
		}
		return result[0];
	}

	private static @org.jspecify.annotations.Nullable Path corpusRoot() {
		String property = System.getProperty("rontolisp.sicp");
		if (property == null || property.isBlank()) {
			return null;
		}
		Path root = Path.of(property);
		return Files.isDirectory(root.resolve("programs_scm")) ? root : null;
	}

	private static List<Entry> loadManifest() throws IOException {
		try (InputStream in = SicpCorpusE2eTest.class.getResourceAsStream(MANIFEST_RESOURCE)) {
			if (in == null) {
				throw new IOException("missing test resource: " + MANIFEST_RESOURCE);
			}
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			List<Entry> entries = new ArrayList<>();
			for (String line : text.split("\n")) {
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				String[] columns = line.split("\t", -1);
				if (columns.length != 4) {
					throw new IOException("sicp-manifest.tsv: malformed row: " + line);
				}
				entries.add(new Entry(columns[0], columns[1], columns[2], columns[3]));
			}
			return entries;
		}
	}

	private static List<String> loadAmbDrivers() throws IOException {
		String text = readResource(AMB_DRIVERS_RESOURCE);
		List<String> samples = new ArrayList<>();
		for (String line : text.split("\n")) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			samples.add(line.strip());
		}
		return samples;
	}

	private static String readResource(String resource) throws IOException {
		try (InputStream in = SicpCorpusE2eTest.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("missing test resource: " + resource);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * The manifest's category rule, ported from
	 * {@code .todo/artefacts/828-sicp-sample-corpus-harness/baseline.py}: text markers
	 * first ({@code js-import}, {@code unreadable}, the {@code variant=} evaluators, the
	 * query section), then free names minus the provided set ({@code scheme} when none
	 * remain, {@code fragment} otherwise; {@code concurrent} when the only missing name
	 * beyond the set is {@code parallel-execute}).
	 */
	static final class Categories {

		static final class Unreadable extends Exception {

			Unreadable(String message) {
				super(message);
			}

		}

		private static final Pattern IMPORT = Pattern.compile("^import ", Pattern.MULTILINE);

		private static final Pattern NUMBER = Pattern
			.compile("^[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?$|^[-+]?\\d+/\\d+$");

		private Categories() {
		}

		static String classify(String file, String text, SequencedSet<String> provided) throws Unreadable {
			if (IMPORT.matcher(text).find()) {
				return "js-import";
			}
			List<Object> forms = read(text);
			if (text.contains("variant=non-det")) {
				return "embedded-amb";
			}
			if (text.contains("variant=lazy")) {
				return "embedded-lazy";
			}
			if (file.startsWith("chapter4/section4/subsection1")) {
				return "embedded-query";
			}
			LinkedHashSet<String> used = new LinkedHashSet<>();
			LinkedHashSet<String> bound = new LinkedHashSet<>();
			for (Object form : forms) {
				walk(form, 0, used, bound);
			}
			LinkedHashSet<String> missing = new LinkedHashSet<>(used);
			missing.removeAll(bound);
			missing.removeAll(provided);
			missing.remove("#f");
			missing.remove("#t");
			if (text.contains("variant=concurrent")) {
				LinkedHashSet<String> rest = new LinkedHashSet<>(missing);
				rest.remove("parallel-execute");
				return rest.isEmpty() ? "concurrent" : "fragment";
			}
			return missing.isEmpty() ? "scheme" : "fragment";
		}

		// A datum: a String atom, a {@code String[]} string literal, a List, or a
		// Quote wrapper (only ' matters: ` walks in, , walks out, like the probe).
		private record Quote(String kind, Object datum) {
		}

		private record Pending(String kind, int depth) {
		}

		private static List<Object> read(String text) throws Unreadable {
			List<Object> stack = new ArrayList<>();
			stack.add(new ArrayList<Object>());
			List<Pending> pending = new ArrayList<>();
			int i = 0;
			int length = text.length();
			while (i < length) {
				char c = text.charAt(i);
				if (Character.isWhitespace(c) || c == ',') {
					if (c == ',' && i + 1 < length && text.charAt(i + 1) == '@') {
						pending.add(new Pending(",@", stack.size()));
						i += 2;
						continue;
					}
					if (c == ',') {
						pending.add(new Pending(",", stack.size()));
						i++;
						continue;
					}
					i++;
				}
				else if (c == ';') {
					while (i < length && text.charAt(i) != '\n') {
						i++;
					}
				}
				else if (c == '(' || c == '[' || c == '{') {
					stack.add(new ArrayList<Object>());
					i++;
				}
				else if (c == ')' || c == ']' || c == '}') {
					if (stack.size() == 1) {
						throw new Unreadable("unexpected " + c);
					}
					Object form = stack.remove(stack.size() - 1);
					push(stack, pending, form);
					i++;
				}
				else if (c == '\'') {
					pending.add(new Pending("'", stack.size()));
					i++;
				}
				else if (c == '`') {
					pending.add(new Pending("`", stack.size()));
					i++;
				}
				else if (c == '"') {
					StringBuilder literal = new StringBuilder("\"");
					i++;
					while (i < length) {
						char d = text.charAt(i);
						literal.append(d);
						i++;
						if (d == '\\' && i < length) {
							literal.append(text.charAt(i));
							i++;
						}
						else if (d == '"') {
							break;
						}
					}
					push(stack, pending, new String[] { literal.toString() });
				}
				else if (c == '#' && i + 1 < length && text.charAt(i + 1) == '|') {
					int end = text.indexOf("|#", i + 2);
					if (end < 0) {
						throw new Unreadable("unterminated #| |#");
					}
					i = end + 2;
				}
				else {
					int start = i;
					while (i < length && !Character.isWhitespace(text.charAt(i))
							&& "()[]{}\";'`,".indexOf(text.charAt(i)) < 0) {
						i++;
					}
					push(stack, pending, text.substring(start, i));
				}
			}
			if (stack.size() != 1) {
				throw new Unreadable("unbalanced");
			}
			@SuppressWarnings("unchecked")
			List<Object> forms = (List<Object>) stack.get(0);
			return forms;
		}

		@SuppressWarnings("unchecked")
		private static void push(List<Object> stack, List<Pending> pending, Object form) {
			while (!pending.isEmpty() && pending.get(pending.size() - 1).depth() == stack.size()) {
				form = new Quote(pending.remove(pending.size() - 1).kind(), form);
			}
			((List<Object>) stack.get(stack.size() - 1)).add(form);
		}

		@SuppressWarnings("unchecked")
		private static void walk(Object form, int quoted, LinkedHashSet<String> used, LinkedHashSet<String> bound) {
			if (form instanceof Quote quote) {
				switch (quote.kind()) {
					case "'" -> {
						return;
					}
					case "`" -> walk(quote.datum(), quoted + 1, used, bound);
					default -> walk(quote.datum(), quoted - 1, used, bound);
				}
				return;
			}
			if (form instanceof String[] || form instanceof String atom && quoted == 0
					&& (NUMBER.matcher(atom).matches() || atom.equals("."))) {
				return;
			}
			if (form instanceof String atom) {
				if (quoted == 0) {
					used.add(atom);
				}
				return;
			}
			List<Object> list = (List<Object>) form;
			if (list.isEmpty()) {
				return;
			}
			if (quoted > 0) {
				for (Object child : list) {
					walk(child, quoted, used, bound);
				}
				return;
			}
			Object head = list.get(0);
			if ("quote".equals(head)) {
				return;
			}
			if ("define".equals(head) && list.size() > 1) {
				Object target = list.get(1);
				while (target instanceof List<?> parts && !parts.isEmpty()) {
					for (int index = 1; index < parts.size(); index++) {
						bind(parts.get(index), bound);
					}
					target = parts.get(0);
				}
				bind(target, bound);
				for (int index = 2; index < list.size(); index++) {
					walk(list.get(index), 0, used, bound);
				}
				return;
			}
			if (("lambda".equals(head) || "named-lambda".equals(head)) && list.size() > 1) {
				bind(list.get(1), bound);
				for (int index = 2; index < list.size(); index++) {
					walk(list.get(index), 0, used, bound);
				}
				return;
			}
			if (("let".equals(head) || "let*".equals(head) || "letrec".equals(head) || "letrec*".equals(head)
					|| "do".equals(head)) && list.size() > 1) {
				int index = 1;
				if (list.get(index) instanceof String name) {
					bound.add(name);
					index++;
				}
				if (index < list.size() && list.get(index) instanceof List<?> bindings) {
					for (Object binding : bindings) {
						if (binding instanceof List<?> pair && !pair.isEmpty()) {
							bind(pair.get(0), bound);
							for (int rest = 1; rest < pair.size(); rest++) {
								walk(pair.get(rest), 0, used, bound);
							}
						}
						else {
							bind(java.util.Objects.requireNonNull(binding), bound);
						}
					}
					index++;
				}
				for (; index < list.size(); index++) {
					walk(list.get(index), 0, used, bound);
				}
				used.add(String.valueOf(head));
				return;
			}
			for (Object child : list) {
				walk(child, 0, used, bound);
			}
		}

		@SuppressWarnings("unchecked")
		private static void bind(Object form, LinkedHashSet<String> bound) {
			if (form instanceof String name) {
				bound.add(name);
			}
			else if (form instanceof List<?> parts) {
				for (Object part : parts) {
					bind(java.util.Objects.requireNonNull(part), bound);
				}
			}
		}

	}

}
