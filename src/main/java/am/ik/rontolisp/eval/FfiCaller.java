package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispVal;

/**
 * How the {@code ffi} package applies a Lisp function -- the body of a callback C calls.
 * The evaluator supplies its {@code apply}; the interface is its own type rather than a
 * nested one of {@link FfiInterop} so that {@link FfiBridge}, which the Web Image
 * substitution must be able to cut, references the entry-point class in no direction --
 * the {@link ObjcCaller} shape, for the same reason.
 */
@FunctionalInterface
public interface FfiCaller {

	/**
	 * @param function a function value
	 * @param args its arguments
	 * @return its value
	 */
	LispVal apply(LispVal function, List<LispVal> args);

}
