package am.ik.rontolisp.eval;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispVal;

/**
 * Materialization of an ARRAY LITERAL: {@code #(...)}, {@code #nA(...)}, {@code #*1011},
 * {@code #f(...)}, {@code #d(...)} and {@code #N@(...)} each yield a FRESH, independently
 * mutable array at every evaluation.
 *
 * <p>
 * The AST node the reader built is the SOURCE constant and never leaves the program:
 * handing it out is what let a write through one evaluation's result be visible to the
 * next. Both compile backends already rebuild the array at the site (see
 * {@code JvmQuoteCompiler} / {@code WasmQuoteCompiler}); this is the interpreter's half
 * of that rule, so all four backends agree.
 *
 * <p>
 * The copy is deep through nested ARRAYS only ({@code #(#(1 2) #(3 4))} yields two fresh
 * inner vectors, as the compile backends do). Every other element -- a number, a string,
 * a symbol, a cons -- is passed through by identity: a quoted cons is shared on the
 * interpreter and rebuilt on the compile backends whether or not an array is involved,
 * and that divergence belongs to {@code quote}, not here.
 */
final class LiteralArrays {

	private LiteralArrays() {
	}

	/**
	 * Answers the value an array literal evaluates to: a fresh array for an array,
	 * {@code datum} itself for anything else.
	 * @param datum the literal datum
	 * @return the value of evaluating it
	 */
	static LispVal materialize(LispVal datum) {
		return switch (datum) {
			case LispArray a -> freshArray(a);
			case LispSingleFloatArray fa -> new LispSingleFloatArray(fa.data().clone(), fa.dims().clone());
			case LispDoubleFloatArray fa -> new LispDoubleFloatArray(fa.data().clone(), fa.dims().clone());
			case LispFloatArray fa -> fa;
			case LispIntVector iv -> new LispIntVector(iv.width(), iv.data().clone());
			default -> datum;
		};
	}

	// A literal array never has a fill pointer, is not adjustable and is not displaced --
	// the reader has no syntax for any of the three -- so the copy is dimensions + data.
	private static LispArray freshArray(LispArray array) {
		LispVal[] source = array.data();
		LispVal[] copy = new LispVal[source.length];
		for (int i = 0; i < source.length; i++) {
			copy[i] = materialize(source[i]);
		}
		return new LispArray(array.dimensions().clone(), copy);
	}

}
