package am.ik.rontolisp.eval;

import java.util.IdentityHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispBFloat16Array;
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
 * inner vectors, as the compile backends do), one per source array. Every other element
 * -- a number, a string, a symbol, a cons -- is passed through by identity: a quoted cons
 * is shared on the interpreter and rebuilt on the compile backends whether or not an
 * array is involved, and that divergence belongs to {@code quote}, not here.
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
			case LispArray a -> freshArray(a, null);
			// Exhaustive over the sealed widths: a catch-all arm here handed the reader's
			// own datum out for a width it did not name, which is exactly the sharing
			// this class exists to stop.
			case LispFloatArray packed -> switch (packed) {
				case LispSingleFloatArray fa -> new LispSingleFloatArray(fa.data().clone(), fa.dims().clone());
				case LispDoubleFloatArray fa -> new LispDoubleFloatArray(fa.data().clone(), fa.dims().clone());
				case LispBFloat16Array fa -> new LispBFloat16Array(fa.data().clone(), fa.dims().clone());
			};
			case LispIntVector iv -> iv.copy();
			default -> datum;
		};
	}

	// A literal array never has a fill pointer, is not adjustable and is not displaced --
	// the reader has no syntax for any of the three -- so the copy is dimensions + data
	// plus the remembered element type (a #*1011 literal is stamped bit, and the copy
	// must stay one or bit-vector-p would answer differently for the second evaluation).
	// A #n= label may share a nested array or close a circle through one, so a literal
	// that
	// nests arrays copies each of them once, and the copy exists before its elements do.
	private static LispArray freshArray(LispArray array, @Nullable Map<LispVal, LispVal> copiesSoFar) {
		LispVal[] source = array.data();
		LispVal[] copy = new LispVal[source.length];
		LispArray fresh = new LispArray(array.dimensions().clone(), copy, -1, false, array.elementTypeCode());
		Map<LispVal, LispVal> copies = copiesSoFar;
		if (copies != null) {
			copies.put(array, fresh);
		}
		for (int i = 0; i < source.length; i++) {
			if (source[i] instanceof LispArray nested) {
				if (copies == null) {
					copies = new IdentityHashMap<>();
					copies.put(array, fresh);
				}
				LispVal done = copies.get(nested);
				copy[i] = (done != null) ? done : freshArray(nested, copies);
			}
			else {
				copy[i] = materialize(source[i]);
			}
		}
		return fresh;
	}

}
