package am.ik.jvm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decoder-completeness guard for the JVM class shaker, the JVM counterpart of
 * {@code WasmTreeShakerCorpusTest}. The shaker has to walk every instruction and
 * constant-pool tag the code generator emits; anything it does not recognize makes
 * {@link JvmClassShaker#shake} throw (the safe failure), and a compaction bug would
 * produce a class the JVM verifier rejects or that misbehaves. This test compiles the
 * whole {@code ci-spec.yaml} corpus (the cross-backend feature catalogue) with
 * {@code --optimize} and asserts that shaking never throws, strictly shrinks the class,
 * and that the optimized class runs with output identical to the unoptimized one.
 *
 * <p>
 * <b>Each run gets its own working directory, and the corpus program runs in a SUBPROCESS
 * to get one.</b> Dozens of ci-spec cases read and write scratch files at RELATIVE paths,
 * and one of them ({@code wild-pathnames}) walks a harness-staged {@code wpc-sub/} tree,
 * so what the program prints is a function of its working directory. A Java process
 * cannot change its own, so an in-process run resolves those paths against the PROJECT
 * ROOT -- a directory shared with the second surefire fork, with any other build on the
 * box and with any orphaned one. This test used to run there and to clean up afterwards
 * by deleting every top-level entry that was not in a before-snapshot, which made it
 * flaky in both directions and was measured on 2026-09-19 ({@code .kb/test-execution.md},
 * "A test that runs a program in the project root"):
 *
 * <ul>
 * <li>Something removed {@code ./wpc-sub/} between the two in-process runs, so the walk
 * answered {@code NIL} in the second and the two outputs differed on exactly one of 4519
 * lines -- a red build that passed when the class was re-run alone.</li>
 * <li>The cleanup deleted, recursively, whatever ANOTHER process had created in the
 * project root inside the test's ~70 s window.</li>
 * </ul>
 *
 * A subprocess per run removes both: the corpus writes into a fresh {@code @TempDir}
 * child that nothing else can see, the project root is never touched, and the JVM
 * verifier check the in-process loader gave is if anything stronger -- a real launch of
 * the shaken class.
 */
class JvmClassShakerCorpusTest {

	@TempDir
	Path workDir;

	private int runs;

	private static String corpusSource() throws IOException {
		return am.ik.rontolisp.testsupport.YamlResources.corpusSource();
	}

	@Test
	void optimizesTheWholeCorpusWithoutDecoderGapsAndBehavesIdentically() throws Exception {
		// The corpus runs in a private directory, so the project root must come out of
		// this test exactly as it went in. Entries that VANISH are not asserted on: a
		// concurrent build in another fork owns its own files, and this test is no
		// longer the one deleting them.
		java.util.Set<String> rootBefore = am.ik.rontolisp.testsupport.CorpusFixtures.snapshotTopLevel(Path.of("."));
		// The CLI's own pass pipeline, not a copy of it: CompileFrontendAccess calls
		// CompileFrontend.expand, so the shaker decodes exactly the class the real CLI
		// emits and no pass or ordering can drift out of this test again. It used to be
		// spelled out here and had fallen ten passes behind (.todo/688).
		List<LispVal> program = am.ik.rontolisp.cli.CompileFrontendAccess.corpus(corpusSource(),
				am.ik.rontolisp.reader.Features.JVM, false, false);

		byte[] plain = withoutUndefinedWarnings(() -> JvmLispCompiler.builder()
			.className("Test")
			.optimize(OptimizeLevel.NONE)
			.build()
			.compile(program));
		// The corpus class is the one that once crossed the JVM 65535 constant-pool
		// ceiling. The LibraryDefunPruner keeps the pool small by dropping
		// unreachable spliced library defuns; guard the headroom so a growing corpus
		// or library fails loudly here, not with a corrupt class in CI.
		int constantPoolEntries = (((plain[8] & 0xff) << 8) | (plain[9] & 0xff)) - 1;
		System.out.println("corpus class constant-pool entries: " + constantPoolEntries + " / 65534");
		assertThat(constantPoolEntries)
			.as("constant-pool headroom (was 65520/65534 before the "
					+ "LibraryDefunPruner and ConstantPool deduplication)")
			.isLessThanOrEqualTo(52000);
		// A decoder gap (unrecognized opcode / constant tag) throws here, by design.
		byte[] optimized = withoutUndefinedWarnings(() -> JvmLispCompiler.builder()
			.className("Test")
			.optimize(OptimizeLevel.DEFAULT)
			.build()
			.compile(program));

		assertThat(optimized.length).as("optimized should shrink the class").isLessThan(plain.length);
		assertThat(run(optimized)).isEqualTo(run(plain));
		assertThat(am.ik.rontolisp.testsupport.CorpusFixtures.snapshotTopLevel(Path.of(".")))
			.as("the corpus runs in a private working directory, so the project root -- shared with "
					+ "the other surefire fork and with every other build on the box -- gains nothing")
			.containsExactlyInAnyOrderElementsOf(rootBefore);
	}

	/**
	 * Runs the class in a JVM of its own, in a fresh working directory, and returns what
	 * it wrote to standard output. The fresh directory is what makes the two runs
	 * comparable: both see the same staged {@code wpc-sub/} tree and neither sees the
	 * scratch files the other left.
	 * @param classBytes the compiled program
	 * @return its standard output
	 * @throws Exception if the process cannot be started or does not finish
	 */
	private String run(byte[] classBytes) throws Exception {
		Path runDir = Files.createDirectory(this.workDir.resolve("run" + (++this.runs)));
		Files.write(runDir.resolve("Test.class"), classBytes);
		// The `wild-pathnames` case walks a bounded wpc-sub/ tree the driver must stage
		// (see CorpusFixtures); here it is staged inside the run directory, which goes
		// away with the @TempDir.
		am.ik.rontolisp.testsupport.CorpusFixtures.stageWildPathnameTree(runDir);
		// The test's own classpath supplies what the in-process loader's parent used to:
		// the emitted class embeds its runtime, but not the classes the compiler shares
		// with it.
		String classpath = runDir + java.io.File.pathSeparator + System.getProperty("java.class.path");
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"--add-modules", "jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED", "-cp", classpath, "Test")
			.directory(runDir.toFile())
			.start();
		byte[] out = process.getInputStream().readAllBytes();
		byte[] err = process.getErrorStream().readAllBytes();
		int status = process.waitFor();
		System.err.print(new String(err, StandardCharsets.UTF_8));
		assertThat(status).as("the corpus program exited non-zero").isZero();
		return new String(out, StandardCharsets.UTF_8);
	}

	/**
	 * Compiles with {@code System.err} captured and fails on any
	 * {@code warning: ... is undefined} line.
	 *
	 * <p>
	 * <b>A test that prints a compile warning must assert on it.</b> When the corpus lost
	 * its {@code TokenizersLibrary} splice, the WASM guard compiled fifteen
	 * undefined-call warnings to standard output and PASSED -- the program was broken,
	 * the breakage was on the console, and nobody reads the output of a green test
	 * (.todo/688). Warnings reach {@code System.err} whether a backend buffers them per
	 * attempt or prints them straight through ({@code compiler/CompileWarnings}), so
	 * capturing that stream catches both.
	 * @param compile the compile to run
	 * @return whatever it produced
	 */
	private static <T> T withoutUndefinedWarnings(java.util.function.Supplier<T> compile) {
		java.io.PrintStream saved = System.err;
		java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
		T result;
		try {
			System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
			result = compile.get();
		}
		finally {
			System.setErr(saved);
		}
		String warnings = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
		saved.print(warnings);
		assertThat(
				warnings.lines().filter(line -> line.contains("warning: ") && line.contains("is undefined")).toList())
			.as("undefined-function warnings from the corpus compile: a name the corpus "
					+ "reaches is not being spliced, so the pass pipeline is wrong")
			.isEmpty();
		return result;
	}

}
