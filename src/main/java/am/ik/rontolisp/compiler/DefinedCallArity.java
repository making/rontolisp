package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The report a DIRECT call of one of the program's own compiled functions makes when it
 * passes a count the function's lambda list rules out: {@code (ud 1)} against
 * {@code (defun ud (a b) ...)} is {@code Function expects 2 arguments, got 1}, a
 * {@code program-error} at run time with its arguments evaluated first, exactly as the
 * interpreter reports it -- never a compile error, since the call may sit in a branch the
 * program never takes or under a {@code program-error} handler.
 *
 * <p>
 * The counterpart of {@link BuiltinCallArity} for a callee the program defines. The shape
 * is the compiled function's (required parameters, and whether a rest list takes the
 * surplus); an {@code &optional} or {@code &key} tail is a rest list by then and judges
 * its own surplus inside the callee. A {@code lambda} form in call position is judged the
 * same way and reported as {@code Function}. The operator the report names is the
 * interpreter's ({@link BuiltinFunctionWrappers#arityOperator}): {@code Function} for a
 * program's own name, the built-in's own name for a defun of a catalog name and for the
 * dispatcher a {@code defmethod} on a built-in is compiled to ({@link ShadowedBuiltins})
 * -- the one rule the function-value path reports by.
 */
public final class DefinedCallArity {

	private DefinedCallArity() {
	}

	/**
	 * What a compiled backend compiles a direct call of a program function to when the
	 * call's count does not fit: the argument forms, then {@code (%program-error
	 * "message")}, whose literal message the compile paths warn about.
	 * @param call the call, a proper list headed by the function's name or lambda form
	 * @param name the function's name as the backend registered it, or {@code null} for a
	 * {@code lambda} form in call position
	 * @param required the required parameter count
	 * @param variadic whether a rest list takes any surplus
	 * @return the replacement form, or {@code null} when the count fits
	 */
	public static @Nullable LispVal wrongCountSignal(LispCons call, @Nullable String name, int required,
			boolean variadic) {
		int supplied = 0;
		for (LispVal rest = call.cdr(); rest instanceof LispCons cell; rest = cell.cdr()) {
			supplied++;
		}
		if (supplied >= required && (variadic || supplied == required)) {
			return null;
		}
		List<LispVal> args = new ArrayList<>(supplied);
		for (LispVal rest = call.cdr(); rest instanceof LispCons cell; rest = cell.cdr()) {
			args.add(cell.car());
		}
		String message = ClosRegistry.arityMessage(BuiltinFunctionWrappers.arityOperator(name), required, variadic,
				supplied);
		return BuiltinCallArity.signalAfterArguments(call, args, message);
	}

}
