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
 * optimization level: the write-time shake's coverage of every instruction and
 * constant-pool entry the code generator emits, its behavior preservation, and the
 * OSR-entry invariant. The corpus no longer fits one class's constant pool, so each
 * compile is the {@code Test} class plus its {@code Test$PartN} classes
 * ({@code .kb/quoted-data.md}, {@code .kb/jvm-method-size-limits.md}), and every guard
 * reads, writes and runs all of them. A corpus compile is the expensive part of each (~13
 * s), and the OSR guard used to be a class of its own that compiled the identical program
 * at the identical two levels a second time.
 *
 * <p>
 * <b>The dead-method elimination.</b> The JVM counterpart of
 * {@code WasmTreeShakerCorpusTest}. The write-time shake ({@code OwnCallGraph} over every
 * body's records, applied by {@code JvmClassSplitter} as it writes) has to see every
 * instruction and constant-pool entry the code generator emits; a missed root would drop
 * a live method, and a write bug would produce a class the JVM verifier rejects or that
 * misbehaves. So: the shake never drops a reachable member, strictly shrinks the class,
 * and the optimized class runs with output identical to the unoptimized one.
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
 * {@link StackMapFrames#osrHostileBackedges}, which reads the operand stack of every
 * branch target off the frames the class ships with; {@code ExamplesE2eTest} runs the
 * same assertion over every example it compiles for the JVM.
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
class JvmDeadMethodEliminationCorpusTest {

	private final AtomicInteger runs = new AtomicInteger();

	private final ExecutorService compiles = Executors.newFixedThreadPool(2);

	private CompletableFuture<Compiled> plain = CompletableFuture
		.failedFuture(new IllegalStateException("not started"));

	private CompletableFuture<Compiled> optimized = CompletableFuture
		.failedFuture(new IllegalStateException("not started"));

	/**
	 * What one compile produced: the program class and every file the compiler writes
	 * beside it ({@link JvmLispCompiler#runtimeClassFiles()}) -- among them the
	 * {@code Test$PartN} classes once the corpus no longer fits one class's pool.
	 *
	 * @param main the {@code Test} class
	 * @param beside the files beside it, keyed by their path in an output tree
	 */
	private record Compiled(byte[] main, java.util.Map<String, byte[]> beside) {

		/** The {@code Test$PartN} classes, empty while the corpus fits one class. */
		java.util.Map<String, byte[]> parts() {
			java.util.Map<String, byte[]> parts = new java.util.TreeMap<>();
			this.beside.forEach((path, bytes) -> {
				if (path.startsWith("Test$Part")) {
					parts.put(path, bytes);
				}
			});
			return parts;
		}

		/** The program class and its parts: the classes the compile emitted. */
		List<byte[]> programClasses() {
			List<byte[]> classes = new java.util.ArrayList<>();
			classes.add(this.main);
			classes.addAll(parts().values());
			return classes;
		}

		int programBytes() {
			return programClasses().stream().mapToInt(bytes -> bytes.length).sum();
		}

	}

	private static String corpusSource() throws IOException {
		return am.ik.rontolisp.testsupport.YamlResources.corpusSource();
	}

	@BeforeAll
	void compileCorpus() throws IOException {
		// The CLI's own pass pipeline, not a copy of it: CompileFrontendAccess calls
		// CompileFrontend.expand, so the shaker decodes exactly the class the real CLI
		// emits and no pass or ordering can drift out of this test again. It used to be
		// spelled out here and had fallen ten passes behind; the OSR guard
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

	private static Compiled compile(List<LispVal> program, OptimizeLevel level) {
		return am.ik.rontolisp.testsupport.UndefinedWarnings.forbid(() -> {
			JvmLispCompiler compiler = JvmLispCompiler.builder().className("Test").optimize(level).build();
			byte[] main = compiler.compile(program);
			return new Compiled(main, compiler.runtimeClassFiles());
		});
	}

	private Compiled classAt(OptimizeLevel level) {
		CompletableFuture<Compiled> compile = switch (level) {
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
	void optimizesTheWholeCorpusWithoutDecoderGapsAndBehavesIdentically(@TempDir Path workDir) throws Exception {
		// The corpus runs in a private directory, so the project root must come out of
		// this test exactly as it went in. Entries that VANISH are not asserted on: a
		// concurrent build in another fork owns its own files, and this test is no
		// longer the one deleting them.
		java.util.Set<String> rootBefore = am.ik.rontolisp.testsupport.CorpusFixtures.snapshotTopLevel(Path.of("."));
		Compiled plain = classAt(OptimizeLevel.NONE);
		Compiled optimized = classAt(OptimizeLevel.DEFAULT);
		assertThat(optimized.programBytes()).as("optimized should shrink the program's classes")
			.isLessThan(plain.programBytes());
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
		assertThat(classAt(level).programClasses()
			.stream()
			.flatMap(bytes -> StackMapFrames.osrHostileBackedges(bytes).stream())
			.toList())
			.as("backward branches into a non-empty operand stack at " + level
					+ " -- HotSpot refuses to OSR-compile such a method (.kb/jvm-osr-backedges.md)")
			.isEmpty();
	}

	/**
	 * Runs the class in a JVM of its own, in a fresh working directory, and returns what
	 * it wrote to standard output. The fresh directory is what makes the two runs
	 * comparable: both see the same staged fixtures and neither sees the scratch files
	 * the other left.
	 * @param compiled the compiled program, written as a class tree: the class, its
	 * {@code $PartN} classes and whatever else the compiler writes beside it
	 * @param workDir the test's own temporary directory (a method parameter, not a field:
	 * under the per-class lifecycle a {@code @TempDir} field is re-injected and cleaned
	 * per method, which concurrent methods would share)
	 * @return its standard output
	 */
	private String run(Compiled compiled, Path workDir) {
		try {
			int n = this.runs.incrementAndGet();
			Path runDir = Files.createDirectory(workDir.resolve("run" + n));
			// The classes go in a directory of their own on the classpath, so the
			// program's working directory holds only what the driver stages.
			Path classes = Files.createDirectory(workDir.resolve("run" + n + "-classes"));
			Files.write(classes.resolve("Test.class"), compiled.main());
			for (java.util.Map.Entry<String, byte[]> file : compiled.beside().entrySet()) {
				Path target = classes.resolve(file.getKey());
				Files.createDirectories(target.getParent());
				Files.write(target, file.getValue());
			}
			// The corpus reads fixtures the driver must stage (see CorpusFixtures); here
			// they are staged inside the run directory, which goes away with the
			// @TempDir.
			am.ik.rontolisp.testsupport.CorpusFixtures.stageAll(runDir);
			// The test's own classpath supplies what the in-process loader's parent used
			// to: the emitted class embeds its runtime, but not the classes the compiler
			// shares with it.
			String classpath = classes + java.io.File.pathSeparator + System.getProperty("java.class.path");
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
