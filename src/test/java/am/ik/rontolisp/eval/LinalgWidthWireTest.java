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
 * Every call that puts a packed float width on {@code linalg.lisp}'s wire passes a WIDTH
 * CODE, not the boolean the protocol carried until 2026-09-03 --
 * {@code linalg::%la-gather-strided}, and {@code linalg::%la-dropout-mask}, which was the
 * last boolean in the library until 2026-09-06.
 *
 * <p>
 * This is a source-shape pin, which is unusual, and the reason is that nothing else can
 * see the defect. The readers of that argument DECLINE what they do not recognize -- the
 * right direction, since a decline falls through to the defun and the answer stays
 * correct -- so a call site left passing {@code t}/{@code nil} produces no exception, no
 * wrong number and no failing test. It produces a scalar walk where a lane kernel was
 * intended: a performance loss at a site nobody is watching, which is
 * {@code .kb/measurement-probes.md}'s instrument-that-cannot-fail, and the class of
 * defect that survives for months. So the door is what is guarded here rather than
 * today's calls: a fourth member joining the wire is the case this has to survive.
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
 *
 * <p>
 * What the wire now CARRIES is the third width itself
 * ({@link am.ik.rontolisp.FloatWidth#BFLOAT16}): {@code linalg:} refused it at
 * {@code %la-make} / {@code %la-etype} until 2026-09-06, and the refusal was what kept a
 * {@code short[]} away from every template. {@link LinalgBfloat16Test} is the
 * differential run that replaces it over the whole {@code linalg:} corpus; the one test
 * below covers this file's own two members.
 */
class LinalgWidthWireTest {

	private static final Path LINALG = Path.of("src/main/resources/am/ik/rontolisp/eval/linalg.lisp");

	/**
	 * The spliced libraries that CALL a member of this wire: the one the defuns live in,
	 * and {@code torch.lisp}, whose dropout layer is the only caller of the mask.
	 */
	private static final List<Path> SOURCES = List.of(LINALG,
			Path.of("src/main/resources/am/ik/rontolisp/eval/torch.lisp"));

	@Test
	void everyGatherStridedCallPassesAWidthCode() throws Exception {
		assertEveryCallPassesAWidthCode("%LA-GATHER-STRIDED", 6, "(a od rs base width)");
	}

	@Test
	void everyDropoutMaskCallPassesAWidthCode() throws Exception {
		// The second member on this wire, and the one the conversion reached last: its
		// two readers (LinalgGpu and JvmGpuTemplate) tested the argument for NULLNESS,
		// which a boxed 0 is not, so a call site left passing t/nil here is the same
		// silent scalar walk. Its only caller today is in torch.lisp, which is why both
		// spliced libraries are read rather than the one the defun lives in.
		assertEveryCallPassesAWidthCode("%LA-DROPOUT-MASK", 5, "(shape p st width)");
	}

	private void assertEveryCallPassesAWidthCode(String member, int arity, String lambdaList) throws Exception {
		List<LispCons> calls = new ArrayList<>();
		for (Path source : SOURCES) {
			for (LispVal form : LispReader.readAllFromString(Files.readString(source, StandardCharsets.UTF_8))) {
				collectCalls(form, member, calls);
			}
		}
		assertThat(calls).as("the %s call sites this pin exists for must still be found", member).isNotEmpty();
		for (LispCons call : calls) {
			List<LispVal> parts = call.toList();
			assertThat(parts).as("%s takes %s: %s", member, lambdaList, call.print()).hasSize(arity);
			LispVal width = parts.get(arity - 1);
			assertThat(width).as("the width argument of %s must be a call, not a literal flag", call.print())
				.isInstanceOf(LispCons.class);
			// %LA-WIDTH-CODE reads it off an array; %LA-WIDTH-CODE-OF-ETYPE off an
			// element-type symbol a caller already holds. Both put FloatWidth's own code
			// on the wire, and nothing else may.
			assertThat(head((LispCons) width)).as("the width argument of %s must be a width CODE", call.print())
				.contains("%LA-WIDTH-CODE");
		}
	}

	// THREE TRAVELLING TEMPLATES transcribe FloatWidth's codes as bare literals --
	// JvmSimdVectorTemplate.laGatherStrided, JvmGpuTemplate.gpuGatherStrided /
	// gpuDropoutMask, and the body WasmLinalgSimdRuntimeBuilder.buildGatherStrided emits
	// -- because the root package does not travel with a compiled program. FloatWidthTest
	// pins the literals; what THEY must do with a value that is not one of theirs is
	// DECLINE, and that is asserted below rather than read out of the templates.
	//
	// Until 2026-09-06 what kept them safe was not anything in the templates: linalg:
	// REFUSED bfloat16 upstream at %la-make and %la-etype. The refusal is gone -- the
	// width is carried now -- so a short[] really does reach every one of
	// these seams at run time, and the property that replaces the refusal is that each
	// declines to the defun. It cannot be a source pin: a decline changes nothing
	// observable, so only a differential run can tell it from a reader that GUESSES.
	// LinalgBfloat16Test owns that run over the whole corpus; what is asserted here is
	// the two members of THIS wire, which is the file's own subject.
	@Test
	void theWidthWireCarriesBfloat16AndEverySeamDeclinesItToTheDefun() {
		String gather = """
				(defvar *b* #bf16((1.0 2.0 3.0) (4.0 5.0 6.0)))
				(list (linalg::%la-gather-strided *b* '(2 2) '(1 3) 1 (linalg::%la-width-code *b*))
				      (linalg::%la-broadcast-to #bf16(1.0 2.0) '(3 2))
				      (progn (linalg:seed 5)
				             (linalg::%la-dropout-mask '(4 4) 0.25 (linalg::%la-rng-state)
				                                       (linalg::%la-width-code-of-etype 'bfloat16))))
				""";
		String scalar = run(gather, false);
		assertThat(scalar).as("the wire carries the width rather than refusing it").startsWith("(#bf16");
		assertThat(run(gather, true)).as("every seam declines it without moving a bit").isEqualTo(scalar);
	}

	private String run(String program, boolean accelerate) {
		LispEvaluator evaluator = new LispEvaluator(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
		if (accelerate) {
			evaluator.setGpu(true);
			evaluator.setBlas(true);
			evaluator.setSimd(true);
		}
		LispVal result = am.ik.rontolisp.LispNil.INSTANCE;
		for (LispVal form : LispReader.readAllFromString(program)) {
			result = evaluator.eval(form);
		}
		return result.print();
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
