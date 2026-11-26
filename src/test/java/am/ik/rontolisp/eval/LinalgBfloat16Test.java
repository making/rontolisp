package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.FloatWidth;
import am.ik.rontolisp.LinalgBfloat16Corpus;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code linalg:} CARRIES the third packed float width, and every acceleration seam
 * declines it to the scalar defun without moving a bit.
 *
 * <p>
 * The width used to be refused at {@code %la-make} / {@code %la-etype}, because the
 * library's own width protocol was a BOOLEAN and a boolean admits exactly two widths
 * (2026-09-06). With the protocol widened to a code ({@link FloatWidth#code()}) the
 * refusal is gone and the property to hold is the one {@code vec:} already had: a
 * constructor takes {@code :element-type 'bfloat16} and every transform PRESERVES it.
 *
 * <p>
 * The second half is the one no source pin can see. Every kernel on every seam declines a
 * {@code short[]}, so a correct decline and a reader that GUESSES ("not a
 * {@code float[]}, therefore {@code double[]}") differ only in the numbers they answer --
 * which is why this is a differential test against the same corpus with the flag off,
 * rather than an inspection of the seams. {@link LinalgWidthWireTest} owns the other
 * half, the source shape of the wire itself.
 *
 * <p>
 * {@code --gpu} and {@code --blas} are asked the same question here as {@code --simd},
 * and on a machine with neither device nor library they are a decline against a decline
 * -- which is still the right assertion, because it is the shape a reader would break and
 * not the device that matters (the sibling reasoning in {@link LinalgGpuDeclineTest}).
 */
class LinalgBfloat16Test {

	private LispVal eval(String input, java.util.function.Consumer<LispEvaluator> flags) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		flags.accept(evaluator);
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	private void assertFlagChangesNothing(java.util.function.Consumer<LispEvaluator> flags) {
		for (String form : LinalgBfloat16Corpus.FORMS) {
			String program = LinalgBfloat16Corpus.PREAMBLE + form;
			assertThat(eval(program, flags).print()).as(form).isEqualTo(eval(program, evaluator -> {
			}).print());
		}
	}

	@Test
	void everyConstructorTakesTheWidthAndEveryTransformPreservesIt() {
		// The property vec: had and linalg: did not. A #d answer anywhere here is the
		// silent widening the width exists to avoid: it would force a mixed-width error
		// on the next vec: call rather than fail where it happened.
		String program = LinalgBfloat16Corpus.PREAMBLE + """
				(list (linalg:zeros '(2 2) :element-type 'bfloat16)
				      (linalg:ones 3 :element-type 'bfloat16)
				      (linalg:full '(2 2) 1.5 :element-type 'bfloat16)
				      (linalg:eye 2 :element-type 'bfloat16)
				      (linalg:arange 4 :element-type 'bfloat16)
				      (linalg:linspace 0 1 3 :element-type 'bfloat16)
				      (linalg:from-list '(1.0 2.0) :element-type 'bfloat16)
				      (linalg:one-hot #d(0.0) 2 :element-type 'bfloat16)
				      (linalg:add *m* *n*) (linalg:mul *m* 2.0) (linalg:exp *m*)
				      (linalg:transpose *m*) (linalg:matmul *m* (linalg:transpose *n*))
				      (linalg:slice *m* '((0 1) nil)) (linalg:zeros-like *m*))
				""";
		for (LispVal element : ((am.ik.rontolisp.LispCons) eval(program, evaluator -> {
		})).toList()) {
			assertThat(element.print()).as("every one of these answers at the operand's own width").startsWith("#bf16");
		}
	}

	@Test
	void theWidthCodeOnTheWireIsFloatWidthsOwn() {
		// linalg::%la-width-code and its inverse are the Lisp half of FloatWidth.ofCode:
		// the same three numbers, or the two halves of the protocol disagree in silence.
		String codes = LinalgBfloat16Corpus.PREAMBLE + """
				(list (linalg::%la-width-code *f*) (linalg::%la-width-code *d*) (linalg::%la-width-code *m*)
				      (linalg::%la-etype-of-width-code 0) (linalg::%la-etype-of-width-code 1)
				      (linalg::%la-etype-of-width-code 2))
				""";
		assertThat(eval(codes, evaluator -> {
		}).print()).isEqualTo("(" + FloatWidth.SINGLE.code() + " " + FloatWidth.DOUBLE.code() + " "
				+ FloatWidth.BFLOAT16.code() + " SINGLE-FLOAT DOUBLE-FLOAT BFLOAT16)");
	}

	@Test
	void aWidthCodeThatNamesNoWidthSignalsRatherThanDefaultingToDouble() {
		// The trap the changeover had to avoid: a small integer code with a default arm
		// admits a
		// third value while re-importing the silence the changeover removes. Both
		// directions signal instead.
		for (String form : new String[] { "(linalg::%la-etype-of-width-code 3)",
				"(linalg::%la-width-code-of-etype 'fixnum)" }) {
			assertThat(eval("(handler-case " + form + " (error (e) (format nil \"~a\" e)))", evaluator -> {
			}).display()).as(form).startsWith("linalg: unknown packed float ");
		}
	}

	@Test
	void simdDeclinesTheWidthWithoutMovingABit() {
		assertFlagChangesNothing(evaluator -> evaluator.setSimd(true));
	}

	@Test
	void blasDeclinesTheWidthWithoutMovingABit() {
		assertFlagChangesNothing(evaluator -> evaluator.setBlas(true));
	}

	@Test
	void gpuDeclinesTheWidthWithoutMovingABit() {
		assertFlagChangesNothing(evaluator -> evaluator.setGpu(true));
	}

	@Test
	void theThreeSeamsTogetherDeclineTheWidthWithoutMovingABit() {
		// The chain, not the rungs: a --gpu --blas --simd run walks all three readers of
		// the width per call site, and the one that guesses need not be the first.
		assertFlagChangesNothing(evaluator -> {
			evaluator.setGpu(true);
			evaluator.setBlas(true);
			evaluator.setSimd(true);
		});
	}

}
