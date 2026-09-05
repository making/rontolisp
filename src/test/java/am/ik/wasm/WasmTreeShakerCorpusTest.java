package am.ik.wasm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 */
class WasmTreeShakerCorpusTest {

	@TempDir
	Path workDir;

	private static String corpusSource() throws IOException {
		return am.ik.rontolisp.testsupport.YamlResources.corpusSource();
	}

	@Test
	void optimizesTheWholeCorpusWithoutDecoderGapsAndStaysValid() throws Exception {
		String source = corpusSource();
		// Both modes exercise renumbering: default WASI drops unused function imports,
		// no-wasi drops the trap-stub functions that fill the import slots.
		for (boolean noWasi : new boolean[] { false, true }) {
			// The CLI's own pass pipeline, not a copy of it: CorpusFrontend calls
			// CompileFrontend.expand, so the shaker decodes exactly the module the real
			// CLI emits. It runs INSIDE the loop because --no-wasi reaches the front end
			// too (the feature set, and which wasi:*-binding libraries splice), which
			// the hand-written copy this replaces could not express at all (.todo/688).
			List<LispVal> program = am.ik.rontolisp.cli.CorpusFrontend.program(source,
					am.ik.rontolisp.reader.Features.WASM, true, noWasi);
			byte[] plain = withoutUndefinedWarnings(
					() -> new WasmLispCompiler(false, false, noWasi, OptimizeLevel.NONE).compile(program));
			// A decoder gap (unrecognized opcode) throws here -> test failure, by design.
			byte[] optimized = withoutUndefinedWarnings(
					() -> new WasmLispCompiler(false, false, noWasi, OptimizeLevel.DEFAULT).compile(program));

			assertThat(optimized.length).as("optimized should shrink the module (noWasi=%s)", noWasi)
				.isLessThan(plain.length);

			validateWithWasmTools(optimized, noWasi);
			roundTripIsAFixpoint(plain, "plain-" + (noWasi ? "nowasi" : "wasi"));
			roundTripIsAFixpoint(optimized, "optimized-" + (noWasi ? "nowasi" : "wasi"));
		}
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
		assumeTrue(onPath("wasm-tools"), "wasm-tools not on PATH; skipping the round-trip oracle");
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

	// Runs `wasm-tools validate -f gc` on the bytes when wasm-tools is available;
	// otherwise
	// the structural correctness check is skipped (the no-throw + shrink assertions above
	// still run on every JVM).
	private void validateWithWasmTools(byte[] module, boolean noWasi) throws Exception {
		assumeTrue(onPath("wasm-tools"), "wasm-tools not on PATH; skipping validation");
		Path file = this.workDir.resolve("corpus-" + (noWasi ? "nowasi" : "wasi") + ".wasm");
		Files.write(file, module);
		Process process = new ProcessBuilder("wasm-tools", "validate", "-f", "gc", file.toString())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int exit = process.waitFor();
		assertThat(exit).as("wasm-tools validate (noWasi=%s) failed:%n%s", noWasi, output).isZero();
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
