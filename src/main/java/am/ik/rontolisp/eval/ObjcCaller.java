package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispVal;

/**
 * How the {@code objc} package applies a Lisp function -- a callback's method body, or
 * the body of {@code objc:on-main}. The evaluator supplies its {@code apply}; the
 * interface is its own type rather than a nested one of {@link ObjcInterop} so that
 * {@link ObjcBridge}, which the Web Image substitution must be able to cut, references
 * the entry-point class in no direction.
 */
@FunctionalInterface
public interface ObjcCaller {

	/**
	 * @param function a function value
	 * @param args its arguments
	 * @return its value
	 */
	LispVal apply(LispVal function, List<LispVal> args);

}
