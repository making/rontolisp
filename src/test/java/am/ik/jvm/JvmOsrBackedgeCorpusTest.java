package am.ik.jvm;

import java.io.IOException;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
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

	private static String corpusSource() throws IOException {
		return am.ik.rontolisp.testsupport.YamlResources.corpusSource();
	}

	@Test
	void noEmittedLoopHeadCarriesPendingOperands() throws Exception {
		List<LispVal> program = corpusProgram();
		for (OptimizeLevel level : List.of(OptimizeLevel.NONE, OptimizeLevel.DEFAULT)) {
			byte[] classBytes = JvmLispCompiler.builder().className("Test").optimize(level).build().compile(program);
			assertThat(StackMapAugmenter.osrHostileBackedges(classBytes))
				.as("backward branches into a non-empty operand stack at " + level
						+ " -- HotSpot refuses to OSR-compile such a method (.kb/jvm-osr-backedges.md)")
				.isEmpty();
		}
	}

	/**
	 * The ci-spec corpus put through the CLI's OWN front end, not a copy of it:
	 * {@code CompileFrontendAccess} calls {@code CompileFrontend.expand}, so the class
	 * analyzed here is the one the real CLI emits. This method used to spell the pass
	 * pipeline out, and it was the last copy left after .todo/688 -- eleven splices
	 * behind, including {@code TokenizersLibrary}, so every run printed ten
	 * {@code TOKENIZER:... is undefined} warnings to the console and passed green, which
	 * is the exact failure .todo/688 existed to end.
	 */
	private static List<LispVal> corpusProgram() throws Exception {
		return am.ik.rontolisp.cli.CompileFrontendAccess.corpus(corpusSource(), am.ik.rontolisp.reader.Features.JVM,
				false, false);
	}

}
