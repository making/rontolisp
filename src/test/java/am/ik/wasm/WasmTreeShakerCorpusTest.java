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
		// Mirror the CLI compile path: user macros (defmacro) are expanded, the
		// JSON, linalg, URL, prelude (equalp/string<) and usocket libraries are spliced
		// by the pre-passes, and the LibraryDefunPruner drops the spliced defuns the
		// corpus never reaches -- so the shaker decodes exactly the module the real CLI
		// emits (the per-backend unit tests keep full-library codegen coverage).
		// #. in the corpus rides the marker read (resolved in UserMacroExpander), like
		// the CLI.
		String source = corpusSource();
		List<LispVal> read = source.contains("#.")
				? LispReader.readAllWithReadEvalMarkers(source, am.ik.rontolisp.reader.Features.WASM)
				: LispReader.readAllFromString(source, am.ik.rontolisp.reader.Features.WASM);
		// LoadInliner splices the built-in ASDF shim systems the corpus load-systems
		// (bordeaux-threads' bt2 case), exactly like the CLI; the corpus references no
		// filesystem source, so the loader throws.
		List<LispVal> inlined = am.ik.rontolisp.cli.LoadInliner.inline(read, path -> {
			throw new java.io.FileNotFoundException(path);
		}, null, List.of(), am.ik.rontolisp.reader.Features.WASM);
		// The prelude splice takes the TARGET features, like the CLI: the uiop splice it
		// drives reads its resources with them, and uiop:featurep -- which the corpus
		// reaches -- is written over *features*, a symbol the compile backends have no
		// runtime binding for (.kb/uiop.md).
		// JsonLibrary runs OUTSIDE GeomLibrary, like the CLI: geom:read-gltf parses
		// through rontolisp:json-parse, so the geom splice introduces the reference.
		List<LispVal> spliced = am.ik.rontolisp.eval.LispPreludeLibrary.process(
				am.ik.rontolisp.eval.UrlLibrary.process(am.ik.rontolisp.eval.JsonLibrary
					.process(am.ik.rontolisp.eval.LinalgLibrary.process(am.ik.rontolisp.eval.GeomLibrary
						.process(am.ik.rontolisp.eval.TorchLibrary.process(am.ik.rontolisp.eval.UserMacroExpander
							.expand(am.ik.rontolisp.eval.HttpServerLibrary.process(inlined,
									am.ik.rontolisp.compiler.ClackEnv.usesBufferedBody(inlined)))))))),
				am.ik.rontolisp.reader.Features.WASM);
		List<LispVal> program = am.ik.rontolisp.eval.LibraryDefunPruner
			.prune(am.ik.rontolisp.eval.UsocketLibrary.process(
					am.ik.rontolisp.eval.GrayStreamsLibrary.process(am.ik.rontolisp.eval.VecLibrary.process(spliced))));

		// Both modes exercise renumbering: default WASI drops unused function imports,
		// no-wasi drops the trap-stub functions that fill the import slots.
		for (boolean noWasi : new boolean[] { false, true }) {
			byte[] plain = new WasmLispCompiler(false, false, noWasi, OptimizeLevel.NONE).compile(program);
			// A decoder gap (unrecognized opcode) throws here -> test failure, by design.
			byte[] optimized = new WasmLispCompiler(false, false, noWasi, OptimizeLevel.DEFAULT).compile(program);

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

}
