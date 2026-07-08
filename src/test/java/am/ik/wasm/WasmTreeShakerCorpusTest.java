package am.ik.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.dataformat.yaml.YAMLMapper;

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

	private record Case(String name, String source, @Nullable String expected,
			@Nullable Map<String, String> expectedByBackend) {
	}

	private record Spec(List<Case> cases) {
	}

	private static String corpusSource() throws IOException {
		try (InputStream in = WasmTreeShakerCorpusTest.class.getResourceAsStream("/ci-spec.yaml")) {
			assertThat(in).as("ci-spec.yaml test resource").isNotNull();
			Spec spec = new YAMLMapper().readValue(in, Spec.class);
			StringBuilder sb = new StringBuilder();
			for (Case c : spec.cases()) {
				sb.append(c.source());
				if (!c.source().endsWith("\n")) {
					sb.append('\n');
				}
			}
			return sb.toString();
		}
	}

	@Test
	void optimizesTheWholeCorpusWithoutDecoderGapsAndStaysValid() throws Exception {
		// Mirror the CLI compile path: user macros (defmacro) are expanded and the
		// JSON, linalg, URL and prelude (equalp/string<) libraries are spliced by the
		// pre-passes before the compiler ever sees the program.
		List<LispVal> program = am.ik.rontolisp.eval.VecLibrary.process(am.ik.rontolisp.eval.LispPreludeLibrary
			.process(am.ik.rontolisp.eval.UrlLibrary.process(am.ik.rontolisp.eval.LinalgLibrary
				.process(am.ik.rontolisp.eval.JsonLibrary.process(am.ik.rontolisp.eval.UserMacroExpander
					.expand(LispReader.readAllFromString(corpusSource())))))));

		// Both modes exercise renumbering: default WASI drops unused function imports,
		// no-wasi drops the trap-stub functions that fill the import slots.
		for (boolean noWasi : new boolean[] { false, true }) {
			byte[] plain = new WasmLispCompiler(false, false, noWasi, false).compile(program);
			// A decoder gap (unrecognized opcode) throws here -> test failure, by design.
			byte[] optimized = new WasmLispCompiler(false, false, noWasi, true).compile(program);

			assertThat(optimized.length).as("optimized should shrink the module (noWasi=%s)", noWasi)
				.isLessThan(plain.length);

			validateWithWasmTools(optimized, noWasi);
		}
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
