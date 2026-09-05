package am.ik.rontolisp.eval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every call to {@code linalg::%la-gather-strided} passes a WIDTH CODE, not the boolean
 * the protocol carried until 2026-09-03.
 *
 * <p>
 * This is a source-shape pin, which is unusual, and the reason is that nothing else can
 * see the defect. The readers of that argument DECLINE what they do not recognize -- the
 * right direction, since a decline falls through to the defun and the answer stays
 * correct -- so a call site left passing {@code t}/{@code nil} produces no exception, no
 * wrong number and no failing test. It produces a scalar walk where a lane kernel was
 * intended: a performance loss at a site nobody is watching, which is
 * {@code .kb/measurement-probes.md}'s instrument-that-cannot-fail, and the class of
 * defect that survives for months. {@code .todo/687} converts more of this family through
 * the same door, so the door is what is guarded here rather than today's two calls.
 *
 * <p>
 * The compiled backends had the OPPOSITE failure and it is worth contrasting: their
 * readers tested the argument for nil rather than declining an unrecognized one, so the
 * changeover made them read every width as single-float -- silently wrong results, not a
 * silent decline. A reader that declines is what makes a source pin the right instrument;
 * a reader that guesses needs a differential test instead.
 *
 * <p>
 * Which is why they all decline now. {@code LinalgGpu} already stated the contract for
 * this seam -- "the defun's shape rules; anything it would signal on declines to it" --
 * so a value that is not a width code is not a shape to interpret but a call the defun
 * will signal on, and every reader hands it back rather than guessing at it.
 * {@code JvmSimdVectorTemplate.laGatherStrided} was the last one still guessing (reading
 * an unrecognized value as "therefore double" while its sibling
 * {@code JvmGpuTemplate.gpuGatherStrided} correctly returned null); it declines too,
 * which is what leaves this source pin as the only observer needed.
 */
class LinalgWidthWireTest {

	private static final Path LINALG = Path.of("src/main/resources/am/ik/rontolisp/eval/linalg.lisp");

	@Test
	void everyGatherStridedCallPassesAWidthCode() throws Exception {
		List<LispCons> calls = new ArrayList<>();
		for (LispVal form : LispReader.readAllFromString(Files.readString(LINALG, StandardCharsets.UTF_8))) {
			collectCalls(form, "%LA-GATHER-STRIDED", calls);
		}
		assertThat(calls).as("the call sites this pin exists for must still be found").isNotEmpty();
		for (LispCons call : calls) {
			List<LispVal> parts = call.toList();
			assertThat(parts).as("%la-gather-strided takes (a od rs base width): %s", call.print()).hasSize(6);
			LispVal width = parts.get(5);
			assertThat(width).as("the width argument of %s must be a call, not a literal flag", call.print())
				.isInstanceOf(LispCons.class);
			assertThat(head((LispCons) width)).as("the width argument of %s must be a width CODE", call.print())
				.endsWith("%LA-WIDTH-CODE");
		}
	}

	// THREE TRAVELLING TEMPLATES read the width as a two-valued boolean --
	// `boolean single = a instanceof float[]`, with the negative half read as "therefore
	// double": JvmSimdVectorTemplate (11 sites), JvmGpuTemplate (12) and JvmBlasTemplate
	// (2). A bfloat16 array is a short[], so it is neither half, and none of the three
	// has a differential test that would catch an edit letting one through
	// (.todo/487's census, .todo/687 owns converting the mechanism).
	//
	// What keeps them safe today is not anything in the templates: it is that linalg:
	// REFUSES the width upstream, at %la-make and %la-etype, and the refusal had no test
	// at all. This is that test. It is behavioural rather than source-shape because the
	// property is what a caller SEES -- a stated refusal, the "does not yet" temporary
	// form (.kb/bfloat16.md, "Refusing a width") -- and because a leak would not present
	// as a wrong answer here but as a ClassCastException inside a template, three layers
	// down from anything a reader would suspect.
	@Test
	void linalgRefusesABfloat16OperandRatherThanLettingItReachATemplate() {
		LispEvaluator evaluator = new LispEvaluator(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
		for (LispVal form : LispReader.readAllFromString("""
				(defvar *b* (make-array '(2 2) :element-type 'bfloat16 :initial-element 1.0))
				""")) {
			evaluator.eval(form);
		}
		for (String call : List.of("(linalg:matmul *b* *b*)", "(linalg:add *b* *b*)")) {
			String answer = evaluator
				.eval(LispReader.readFromString("(handler-case " + call + " (error (e) (format nil \"~a\" e)))"))
				.display();
			assertThat(answer).as("%s must state the refusal, not reach a template", call)
				.isEqualTo("linalg: does not yet carry bfloat16 arrays");
		}
		// Not everything is refused, and that is deliberate: a reduction that never asks
		// the width wire answers correctly at any width.
		assertThat(evaluator.eval(LispReader.readFromString("(linalg:sum *b*)")).print()).isEqualTo("4.0");
	}

	private static void collectCalls(LispVal form, String suffix, List<LispCons> found) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (head(cons).endsWith(suffix)) {
			found.add(cons);
		}
		for (LispVal part : cons.toList()) {
			collectCalls(part, suffix, found);
		}
	}

	private static String head(LispCons cons) {
		return cons.car() instanceof LispSymbol symbol ? symbol.name() : "";
	}

}
