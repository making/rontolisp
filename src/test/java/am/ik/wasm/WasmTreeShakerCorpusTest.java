package am.ik.wasm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Decoder-completeness guard for the WASM tree-shaker. The shaker has to skip over every
 * instruction the code generators emit; an opcode it does not recognize makes
 * {@link WasmTreeShaker#shake} throw (the safe failure), and a renumbering bug would
 * produce an invalid module. To keep the decoder in sync with the backend as new
 * built-ins/opcodes land, this test compiles the whole {@code ci-spec.yaml} corpus (the
 * cross-backend feature catalogue) with {@code --optimize} and asserts that
 * <ol>
 * <li>shaking never throws and strictly shrinks the module (pure-JVM, always runs), and
 * <li>the optimized module passes {@code wasm-tools validate -f gc} (gated on
 * {@code wasm-tools} being on the {@code PATH}).
 * </ol>
 * Because the corpus exercises arithmetic, rationals, floats, strings, chars, conses,
 * hash tables, {@code eval}, control flow and more, it touches the great majority of the
 * emitted opcode set, so a newly-introduced opcode the shaker cannot decode fails here
 * rather than silently disabling {@code --optimize} for that program.
 *
 * <p>
 * The two modes are concurrent invocations, and inside one the three compile levels and
 * every {@code wasm-tools} check overlap: nothing here shares a file name across modes
 * (every label carries the mode), and the compile warnings are captured per thread
 * ({@code UndefinedWarnings}).
 */
@Execution(ExecutionMode.CONCURRENT)
class WasmTreeShakerCorpusTest {

	/**
	 * Corpus compiles one mode runs at once. The three levels of a mode are independent
	 * compiles of one program and the two modes are concurrent invocations, so the class
	 * holds up to twice this many corpus compiles in flight. Each keeps hundreds of
	 * megabytes live, so the bound follows the core count rather than filling it: three
	 * on a many-core box, one on the 4 vCPU CI runner (whose other fork is busy anyway).
	 */
	private static final int COMPILE_THREADS = Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 1, 3);

	@TempDir
	Path workDir;

	private static String corpusSource() throws IOException {
		return am.ik.rontolisp.testsupport.YamlResources.corpusSource();
	}

	// Both modes exercise renumbering: default WASI drops unused function imports,
	// no-wasi drops the trap-stub functions that fill the import slots.
	@ParameterizedTest(name = "noWasi={0}")
	@ValueSource(booleans = { false, true })
	void optimizesTheWholeCorpusWithoutDecoderGapsAndStaysValid(boolean noWasi) throws Exception {
		// The CLI's own pass pipeline, not a copy of it: CompileFrontendAccess calls
		// CompileFrontend.expand, so the shaker decodes exactly the module the real CLI
		// emits. It runs per mode because --no-wasi reaches the front end too (the
		// feature set, and which wasi:*-binding libraries splice), which the
		// hand-written copy this replaces could not express at all (.todo/688).
		List<LispVal> program = am.ik.rontolisp.cli.CompileFrontendAccess.corpus(corpusSource(),
				am.ik.rontolisp.reader.Features.WASM, true, noWasi);
		String mode = noWasi ? "nowasi" : "wasi";
		boolean tools = onPath("wasm-tools");
		ExecutorService compiles = Executors.newFixedThreadPool(COMPILE_THREADS);
		// The wasm-tools checks are child processes, not heap: they overlap freely with
		// the compiles and with each other.
		ExecutorService checks = Executors.newCachedThreadPool();
		List<Future<?>> toolChecks = new ArrayList<>();
		try {
			// The three levels read the same finished program and none of them writes to
			// it, so they compile side by side.
			Future<byte[]> plainCompile = compiles.submit(() -> compile(program, noWasi, OptimizeLevel.NONE));
			// A decoder gap (unrecognized opcode) throws here -> test failure, by design.
			Future<byte[]> optimizedCompile = compiles.submit(() -> compile(program, noWasi, OptimizeLevel.DEFAULT));
			// The size level swaps emissions rather than only dropping them (the shared
			// cons readers, .kb/cons-access-runtime.md), so it is validated on the corpus
			// too: a rewrite that validates on a toy and not on the corpus is exactly
			// what
			// this test exists to catch.
			Future<byte[]> smallestCompile = compiles.submit(() -> compile(program, noWasi, OptimizeLevel.SIZE));

			byte[] plain = await(plainCompile);
			if (tools) {
				toolChecks.add(checks.submit(check(() -> roundTripIsAFixpoint(plain, "plain-" + mode))));
			}

			// The single-call-site move, isolated. It runs INSIDE the two optimized
			// compiles, so the only way to see what it is worth -- and that it is not
			// worth less than nothing -- is to run the same three passes by hand over the
			// same bytes. It may never make the shipped module bigger: everything it
			// relocates has to come back with interest once the shake collects the
			// callee, and the body it moves is budgeted for exactly that reason
			// (.kb/optimize-dead-code-elimination.md, "The single-call-site move").
			byte[] prepared = WasmCallForwarding.redirect(WasmRefTypeFolder.fold(plain));
			// The adjacent-instruction peepholes, in the position the compile path runs
			// them: over what the fold and the redirection leave, in front of the move.
			// They too run INSIDE the two optimized compiles, so this is the only place
			// their own shrink is visible -- and the only place every body they rewrite
			// is
			// put through the validator and the round-trip oracle.
			byte[] peepholed = WasmPeephole.rewrite(prepared,
					WasmLispCompiler.peepholePureNonNullCalls(WasmLispCompiler.hostImportShift(plain, noWasi)));
			assertThat(peepholed.length).as("the peepholes must shrink the module (noWasi=%s)", noWasi)
				.isLessThan(prepared.length);
			byte[] withoutMove = WasmTreeShaker.shake(peepholed);
			byte[] withMove = WasmTreeShaker.shake(WasmInliner.inline(peepholed));
			assertThat(withMove.length)
				.as("the single-call-site move must not grow the shaken module (noWasi=%s)", noWasi)
				.isLessThanOrEqualTo(withoutMove.length);
			// The local renumbering, last, over the shaken module as the compile path
			// runs
			// it: a permutation of each function's own locals, so it may never grow the
			// module, and every body it touches goes through the validator and the
			// round-trip oracle.
			byte[] ordered = WasmLocalOrder.reorder(withMove);
			assertThat(ordered.length).as("the local renumbering must not grow the module (noWasi=%s)", noWasi)
				.isLessThanOrEqualTo(withMove.length);
			if (tools) {
				toolChecks.add(checks.submit(check(() -> validateAndRoundTrip(withoutMove, "peepholed-" + mode))));
				toolChecks.add(checks.submit(check(() -> validateAndRoundTrip(withMove, "inlined-" + mode))));
				toolChecks.add(checks.submit(check(() -> validateAndRoundTrip(ordered, "ordered-" + mode))));
			}

			byte[] optimized = await(optimizedCompile);
			assertThat(optimized.length).as("optimized should shrink the module (noWasi=%s)", noWasi)
				.isLessThan(plain.length);
			if (tools) {
				toolChecks.add(checks.submit(check(() -> validateAndRoundTrip(optimized, "optimized-" + mode))));
			}
			byte[] smallest = await(smallestCompile);
			assertThat(smallest.length).as("--optimize=size should not exceed the default level (noWasi=%s)", noWasi)
				.isLessThanOrEqualTo(optimized.length);
			if (tools) {
				toolChecks.add(checks.submit(check(() -> validateAndRoundTrip(smallest, "size-" + mode))));
			}
			for (Future<?> check : toolChecks) {
				await(check);
			}
		}
		finally {
			compiles.shutdownNow();
			checks.shutdownNow();
		}
		// Every assertion that needs no external tool has run by now; only the
		// structural checks are skipped on a machine without wasm-tools.
		assumeTrue(tools, "wasm-tools not on PATH; skipping validation and the round-trip oracle");
	}

	private static byte[] compile(List<LispVal> program, boolean noWasi, OptimizeLevel level) {
		return am.ik.rontolisp.testsupport.UndefinedWarnings
			.forbid(() -> WasmLispCompiler.builder().noWasi(noWasi).optimize(level).build().compile(program));
	}

	/** A check that returns nothing, as a task an executor accepts. */
	private interface Check {

		void run() throws Exception;

	}

	private static Callable<Boolean> check(Check check) {
		return () -> {
			check.run();
			return true;
		};
	}

	/**
	 * The value of a finished task, with a failure inside it rethrown as itself -- an
	 * assertion failure keeps its own message rather than arriving wrapped in an
	 * {@link ExecutionException}.
	 */
	private static <T> T await(Future<T> task) throws Exception {
		try {
			return task.get();
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof Error error) {
				throw error;
			}
			if (ex.getCause() instanceof Exception cause) {
				throw cause;
			}
			throw ex;
		}
	}

	private void validateAndRoundTrip(byte[] module, String label) throws Exception {
		validateWithWasmTools(module, label);
		roundTripIsAFixpoint(module, label);
	}

	/**
	 * The emitter writes the SHORTEST LEGAL encoding, and this is the oracle for it:
	 * {@code wasm-tools parse (wasm-tools print M)} must converge on {@code M} itself.
	 * The tool re-encodes from the decoded module, so any field this project spells
	 * non-minimally (a {@code (ref null eq)} written long, an index written as a signed
	 * LEB, an explicit {@code sub final} wrapper, a zero-entry section) comes back
	 * shorter and the comparison fails -- which is the whole point, because such a module
	 * still VALIDATES and RUNS, so nothing else notices.
	 * <p>
	 * A failure is one of two things and the message says so: a newly-emitted field in a
	 * non-minimal encoding (fix the emitter), or a place where {@code wasm-tools} started
	 * normalizing something this project deliberately does not (record the reason in
	 * {@code .kb/optimize-dead-code-elimination.md} and relax this to a size comparison).
	 */
	private void roundTripIsAFixpoint(byte[] module, String label) throws Exception {
		Path binary = this.workDir.resolve(label + ".wasm");
		Path text = this.workDir.resolve(label + ".wat");
		Path reencoded = this.workDir.resolve(label + ".rt.wasm");
		Files.write(binary, module);
		run("wasm-tools", "print", binary.toString(), "-o", text.toString());
		run("wasm-tools", "parse", text.toString(), "-o", reencoded.toString());
		byte[] roundTripped = Files.readAllBytes(reencoded);
		assertThat(module.length)
			.as("%s: wasm-tools re-encodes this module in %d bytes, %d fewer than we wrote it in -- "
					+ "something is not in its shortest legal encoding", label, roundTripped.length,
					module.length - roundTripped.length)
			.isEqualTo(roundTripped.length);
		assertThat(module).as("%s: same size but different bytes than the round-trip", label).isEqualTo(roundTripped);
	}

	private static void run(String... command) throws Exception {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).as("%s failed:%n%s", String.join(" ", command), output).isZero();
	}

	// Runs `wasm-tools validate -f gc` on the bytes; the caller runs it only when
	// wasm-tools is on the PATH (the no-throw + shrink assertions run on every JVM).
	private void validateWithWasmTools(byte[] module, String label) throws Exception {
		Path file = this.workDir.resolve("corpus-" + label + ".wasm");
		Files.write(file, module);
		Process process = new ProcessBuilder("wasm-tools", "validate", "-f", "gc", file.toString())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int exit = process.waitFor();
		assertThat(exit).as("wasm-tools validate (%s) failed:%n%s", label, output).isZero();
	}

	private static boolean onPath(String tool) {
		try {
			Process p = new ProcessBuilder("which", tool).start();
			return p.waitFor() == 0;
		}
		catch (IOException | InterruptedException ex) {
			return false;
		}
	}

}
