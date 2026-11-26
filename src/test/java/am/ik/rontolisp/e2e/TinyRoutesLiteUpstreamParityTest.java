package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.LoadInliner;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upstream half of the {@code tiny-routes/lite} pinning: the REAL tiny-routes over
 * the REAL cl-ppcre engine (both vendored) runs the shared {@link TinyRoutesLiteCorpus}
 * on the interpreter and must produce the same expected output the lite matcher produces
 * on all four backends ({@link TinyRoutesLiteE2eTest}). One corpus, two engines, one
 * expectation list -- that structure keeps the two aligned without co-loading them (they
 * define the same packages), and a divergence in EITHER direction fails exactly one of
 * the two classes, naming the side that moved.
 *
 * <p>
 * Also pins the co-load refusal itself: the two systems conflict by construction
 * (whichever loaded last would silently redefine the matcher), so both loaders refuse the
 * second load with a clear error, in both orders.
 */
class TinyRoutesLiteUpstreamParityTest {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "tiny-routes")
		.toAbsolutePath()
		.toString();

	private static final String CL_PPCRE_DIR = Path.of("src", "test", "resources", "cl-ppcre")
		.toAbsolutePath()
		.toString();

	private static String run(String program, List<String> systemPath) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		evaluator.setSystemPath(systemPath);
		for (LispVal expr : LispReader.readAllFromString(program)) {
			evaluator.eval(expr);
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	@Test
	void theRealEngineProducesTheCorpusExpectationsTheLiteMatcherIsPinnedTo() {
		String output = run("(asdf:load-system :tiny-routes)\n" + TinyRoutesLiteCorpus.CORPUS,
				List.of(SYSTEM_DIR, CL_PPCRE_DIR));
		assertThat(output.trim().lines().map(String::trim))
			.containsExactlyElementsOf(TinyRoutesLiteCorpus.CORPUS_EXPECTED);
	}

	@Test
	void loadingTheLiteSystemAfterTheFullOneIsRefused() {
		assertThatThrownBy(() -> run("(asdf:load-system :tiny-routes)\n(asdf:load-system \"tiny-routes/lite\")",
				List.of(SYSTEM_DIR, CL_PPCRE_DIR)))
			.hasMessageContaining("Cannot load system 'tiny-routes/lite'")
			.hasMessageContaining("load one of the two, not both");
	}

	@Test
	void loadingTheFullSystemAfterTheLiteOneIsRefused() {
		assertThatThrownBy(() -> run("(asdf:load-system \"tiny-routes/lite\")\n(asdf:load-system :tiny-routes)",
				List.of(SYSTEM_DIR, CL_PPCRE_DIR)))
			.hasMessageContaining("Cannot load system 'tiny-routes'")
			.hasMessageContaining("load one of the two, not both");
	}

	@Test
	void theCompileTimeInlinerRefusesTheConflictToo() {
		List<LispVal> program = LispReader.readAllFromString(
				"(asdf:load-system \"tiny-routes/lite\")\n(asdf:load-system :tiny-routes)", Features.JVM);
		assertThatThrownBy(() -> LoadInliner.inline(program, SourceLoader.fileSystem(), null,
				List.of(SYSTEM_DIR, CL_PPCRE_DIR), Features.JVM))
			.hasMessageContaining("Cannot load system 'tiny-routes'")
			.hasMessageContaining("load one of the two, not both");
	}

}
