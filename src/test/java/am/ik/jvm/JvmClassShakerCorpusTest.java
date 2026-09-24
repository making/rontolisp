package am.ik.jvm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guards that need the whole {@code ci-spec.yaml} corpus (the cross-backend feature
 * catalogue) compiled for the JVM, sharing ONE front-end run and ONE compile per
 * optimization level: the class shaker's decoder completeness and behavior preservation,
 * the constant-pool headroom, and the OSR-entry invariant. A corpus compile is the
 * expensive part of each (~13 s), and the OSR guard used to be a class of its own that
 * compiled the identical program at the identical two levels a second time.
 *
 * <p>
 * <b>The class shaker.</b> The JVM counterpart of {@code WasmTreeShakerCorpusTest}. The
 * shaker has to walk every instruction and constant-pool tag the code generator emits;
 * anything it does not recognize makes {@link JvmClassShaker#shake} throw (the safe
 * failure), and a compaction bug would produce a class the JVM verifier rejects or that
 * misbehaves. So: shaking never throws, strictly shrinks the class, and the optimized
 * class runs with output identical to the unoptimized one.
 *
 * <p>
 * <b>The OSR entry.</b> Pins the emitter invariant behind
 * {@code .kb/jvm-osr-backedges.md}: no backward branch in an emitted class may target a
 * position whose operand stack is non-empty. HotSpot can only enter an on-stack-
 * replacement compilation at a backedge whose operand stack is empty; a loop head
 * carrying pending operands is refused at every tier ({@code COMPILE SKIPPED: stack not
 * empty at OSR entry point}), and a method entered once -- every top-level form, every
 * {@code defun} called once with a long loop inside -- then runs in the bytecode
 * interpreter forever. The measured cost when {@code nth} last had that shape was 8.5x
 * the WASM backend on identical source. The check is
 * {@link StackMapAugmenter#osrHostileBackedges}, which reuses the verifier-style dataflow
 * the augmenter already runs; {@code ExamplesE2eTest} runs the same assertion over every
 * example it compiles for the JVM.
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
 * the shaken class. Because each run owns its directory, the two runs also overlap.
 *
 * <p>
 * <b>Concurrency.</b> The two levels compile side by side from {@link #compileCorpus}
 * (they read one finished program and neither writes to it), each test awaits only the
 * compile it needs -- so a decoder gap in the optimized compile leaves the
 * {@code NONE}-level guards green and fails exactly the tests that needed the shaken
 * class -- and the methods themselves run concurrently.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
class JvmClassShakerCorpusTest {

	private final AtomicInteger runs = new AtomicInteger();

	private final ExecutorService compiles = Executors.newFixedThreadPool(2);

	private CompletableFuture<byte[]> plain = CompletableFuture.failedFuture(new IllegalStateException("not started"));

	private CompletableFuture<byte[]> optimized = CompletableFuture
		.failedFuture(new IllegalStateException("not started"));

	private static String corpusSource() throws IOException {
		return am.ik.rontolisp.testsupport.YamlResources.corpusSource();
	}

	@BeforeAll
	void compileCorpus() throws IOException {
		// The CLI's own pass pipeline, not a copy of it: CompileFrontendAccess calls
		// CompileFrontend.expand, so the shaker decodes exactly the class the real CLI
		// emits and no pass or ordering can drift out of this test again. It used to be
		// spelled out here and had fallen ten passes behind (.todo/688); the OSR guard
		// kept its own copy eleven splices behind until 2026-09-19.
		List<LispVal> program = am.ik.rontolisp.cli.CompileFrontendAccess.corpus(corpusSource(),
				am.ik.rontolisp.reader.Features.JVM, false, false);
		this.plain = CompletableFuture.supplyAsync(() -> compile(program, OptimizeLevel.NONE), this.compiles);
		// A decoder gap (unrecognized opcode / constant tag) throws here, by design.
		this.optimized = CompletableFuture.supplyAsync(() -> compile(program, OptimizeLevel.DEFAULT), this.compiles);
	}

	@AfterAll
	void stopCompiles() {
		this.compiles.shutdownNow();
	}

	private static byte[] compile(List<LispVal> program, OptimizeLevel level) {
		return am.ik.rontolisp.testsupport.UndefinedWarnings
			.forbid(() -> JvmLispCompiler.builder().className("Test").optimize(level).build().compile(program));
	}

	private byte[] classAt(OptimizeLevel level) {
		CompletableFuture<byte[]> compile = switch (level) {
			case NONE -> this.plain;
			case DEFAULT -> this.optimized;
			default -> throw new IllegalArgumentException(level.toString());
		};
		return join(compile);
	}

	/**
	 * The value of a finished task, with a failure inside it rethrown as itself: an
	 * assertion keeps its message and a decoder gap its stack.
	 */
	private static <T> T join(CompletableFuture<T> task) {
		try {
			return task.join();
		}
		catch (CompletionException ex) {
			if (ex.getCause() instanceof RuntimeException cause) {
				throw cause;
			}
			if (ex.getCause() instanceof Error error) {
				throw error;
			}
			throw ex;
		}
	}

	@Test
	void theCorpusClassKeepsConstantPoolHeadroom() {
		byte[] plain = classAt(OptimizeLevel.NONE);
		// The corpus class is the one that once crossed the JVM 65535 constant-pool
		// ceiling. The LibraryDefunPruner keeps the pool small by dropping unreachable
		// spliced library defuns; guard the headroom so a growing corpus or library fails
		// loudly here, not with a corrupt class in CI.
		int constantPoolEntries = (((plain[8] & 0xff) << 8) | (plain[9] & 0xff)) - 1;
		System.out.println("corpus class constant-pool entries: " + constantPoolEntries + " / 65534");
		assertThat(constantPoolEntries)
			.as("constant-pool headroom (was 65520/65534 before the "
					+ "LibraryDefunPruner and ConstantPool deduplication)")
			.isLessThanOrEqualTo(52000);
	}

	@Test
	void optimizesTheWholeCorpusWithoutDecoderGapsAndBehavesIdentically(@TempDir Path workDir) throws Exception {
		// The corpus runs in a private directory, so the project root must come out of
		// this test exactly as it went in. Entries that VANISH are not asserted on: a
		// concurrent build in another fork owns its own files, and this test is no
		// longer the one deleting them.
		java.util.Set<String> rootBefore = am.ik.rontolisp.testsupport.CorpusFixtures.snapshotTopLevel(Path.of("."));
		byte[] plain = classAt(OptimizeLevel.NONE);
		byte[] optimized = classAt(OptimizeLevel.DEFAULT);
		assertThat(optimized.length).as("optimized should shrink the class").isLessThan(plain.length);
		// Two runs in two directories of their own: nothing one writes is visible to the
		// other, so they overlap.
		CompletableFuture<String> plainRun = CompletableFuture.supplyAsync(() -> run(plain, workDir));
		String optimizedOutput = run(optimized, workDir);
		assertThat(optimizedOutput).isEqualTo(join(plainRun));
		assertThat(am.ik.rontolisp.testsupport.CorpusFixtures.snapshotTopLevel(Path.of(".")))
			.as("the corpus runs in a private working directory, so the project root -- shared with "
					+ "the other surefire fork and with every other build on the box -- gains nothing")
			.containsExactlyInAnyOrderElementsOf(rootBefore);
	}

	@ParameterizedTest
	@EnumSource(value = OptimizeLevel.class, names = { "NONE", "DEFAULT" })
	void noEmittedLoopHeadCarriesPendingOperands(OptimizeLevel level) {
		assertThat(StackMapAugmenter.osrHostileBackedges(classAt(level)))
			.as("backward branches into a non-empty operand stack at " + level
					+ " -- HotSpot refuses to OSR-compile such a method (.kb/jvm-osr-backedges.md)")
			.isEmpty();
	}

	/**
	 * Runs the class in a JVM of its own, in a fresh working directory, and returns what
	 * it wrote to standard output. The fresh directory is what makes the two runs
	 * comparable: both see the same staged {@code wpc-sub/} tree and neither sees the
	 * scratch files the other left.
	 * @param classBytes the compiled program
	 * @param workDir the test's own temporary directory (a method parameter, not a field:
	 * under the per-class lifecycle a {@code @TempDir} field is re-injected and cleaned
	 * per method, which concurrent methods would share)
	 * @return its standard output
	 */
	private String run(byte[] classBytes, Path workDir) {
		try {
			int n = this.runs.incrementAndGet();
			Path runDir = Files.createDirectory(workDir.resolve("run" + n));
			Files.write(runDir.resolve("Test.class"), classBytes);
			// The `wild-pathnames` case walks a bounded wpc-sub/ tree the driver must
			// stage (see CorpusFixtures); here it is staged inside the run directory,
			// which goes away with the @TempDir.
			am.ik.rontolisp.testsupport.CorpusFixtures.stageWildPathnameTree(runDir);
			// The test's own classpath supplies what the in-process loader's parent used
			// to: the emitted class embeds its runtime, but not the classes the compiler
			// shares with it.
			String classpath = runDir + java.io.File.pathSeparator + System.getProperty("java.class.path");
			// Beside the run directory, not in it, so the program's working directory
			// holds
			// exactly what the driver staged.
			Path stderr = workDir.resolve("run" + n + "-stderr.txt");
			Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
					"--add-modules", "jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
					"Test")
				.directory(runDir.toFile())
				// To a file rather than a pipe: a pipe nobody drains while stdout is
				// being
				// read can fill and stall the program.
				.redirectError(stderr.toFile())
				.start();
			byte[] out = process.getInputStream().readAllBytes();
			int status = process.waitFor();
			System.err.print(Files.readString(stderr, StandardCharsets.UTF_8));
			assertThat(status).as("the corpus program exited non-zero").isZero();
			return new String(out, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
