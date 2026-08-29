package am.ik.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the emitter invariant behind {@code .kb/jvm-osr-backedges.md}: no backward branch
 * in an emitted class may target a position whose operand stack is non-empty.
 *
 * <p>
 * HotSpot can only enter an on-stack-replacement compilation at a backedge whose operand
 * stack is empty. A loop head carrying pending operands is refused at every tier
 * ({@code COMPILE SKIPPED: stack not empty at OSR entry point}), and a method entered
 * once -- every top-level form, every {@code defun} called once with a long loop inside
 * -- has no other route into a compiled version, so it runs in the bytecode interpreter
 * forever. The measured cost when {@code nth} last had that shape was 8.5x the WASM
 * backend on identical source, and slower than the tree-walking interpreter.
 *
 * <p>
 * The check itself is {@link StackMapAugmenter#osrHostileBackedges}, which reuses the
 * verifier-style dataflow the augmenter already runs -- the one place that sees what all
 * the emitters together produced. The corpus is {@code ci-spec.yaml}, the cross-backend
 * feature catalogue, built exactly the way {@code JvmClassShakerCorpusTest} builds it so
 * the analyzed class is the one the real CLI emits. {@code ExamplesE2eTest} runs the same
 * assertion over every example it compiles for the JVM.
 */
class JvmOsrBackedgeCorpusTest {

	private record Case(String name, String source, @Nullable String expected,
			@Nullable Map<String, String> expectedByBackend) {
	}

	private record Spec(List<Case> cases) {
	}

	private static String corpusSource() throws IOException {
		try (InputStream in = JvmOsrBackedgeCorpusTest.class.getResourceAsStream("/ci-spec.yaml")) {
			assertThat(in).as("ci-spec.yaml test resource").isNotNull();
			Spec spec = new tools.jackson.dataformat.yaml.YAMLMapper().readValue(in, Spec.class);
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
	void noEmittedLoopHeadCarriesPendingOperands() throws Exception {
		List<LispVal> program = corpusProgram();
		for (OptimizeLevel level : List.of(OptimizeLevel.NONE, OptimizeLevel.DEFAULT)) {
			byte[] classBytes = new JvmLispCompiler("Test", false, level).compile(program);
			assertThat(StackMapAugmenter.osrHostileBackedges(classBytes))
				.as("backward branches into a non-empty operand stack at " + level
						+ " -- HotSpot refuses to OSR-compile such a method (.kb/jvm-osr-backedges.md)")
				.isEmpty();
		}
	}

	/** The ci-spec corpus put through the same front end the CLI's compile path runs. */
	private static List<LispVal> corpusProgram() throws Exception {
		String source = corpusSource();
		List<LispVal> read = source.contains("#.")
				? LispReader.readAllWithReadEvalMarkers(source, am.ik.rontolisp.reader.Features.JVM)
				: LispReader.readAllFromString(source, am.ik.rontolisp.reader.Features.JVM);
		List<LispVal> inlined = am.ik.rontolisp.cli.LoadInliner.inline(read, path -> {
			throw new java.io.FileNotFoundException(path);
		}, null, List.of(), am.ik.rontolisp.reader.Features.JVM);
		List<LispVal> spliced = am.ik.rontolisp.eval.LispPreludeLibrary.process(
				am.ik.rontolisp.eval.UrlLibrary
					.process(am.ik.rontolisp.eval.LinalgLibrary.process(am.ik.rontolisp.eval.GeomLibrary
						.process(am.ik.rontolisp.eval.TorchLibrary.process(am.ik.rontolisp.eval.JsonLibrary.process(
								am.ik.rontolisp.eval.UserMacroExpander.expand(am.ik.rontolisp.eval.HttpServerLibrary
									.process(am.ik.rontolisp.eval.HttpReactorLibrary.process(inlined),
											am.ik.rontolisp.compiler.ClackEnv.usesBufferedBody(inlined)))))))),
				am.ik.rontolisp.reader.Features.JVM);
		return am.ik.rontolisp.eval.LibraryDefunPruner.prune(am.ik.rontolisp.eval.UnreadCharLibrary
			.process(am.ik.rontolisp.eval.UsocketLibrary.process(am.ik.rontolisp.eval.GrayStreamsLibrary
				.process(am.ik.rontolisp.eval.VecLibrary.process(spliced)))));
	}

}
