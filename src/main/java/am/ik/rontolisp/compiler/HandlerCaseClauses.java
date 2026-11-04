package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * A {@code handler-case} clause whose variables need what only a {@code let} gives a
 * binding: each backend's clause binder puts the value in a plain slot, which a special
 * does not read and a closure built in the body cannot share.
 */
public final class HandlerCaseClauses {

	private HandlerCaseClauses() {
	}

	/**
	 * Rewrites {@code (type (var ...) body...)} to
	 * {@code (type (temp ...) (let ((var temp) ...) body...))} when a variable is special
	 * or a closure in the body captures one; any other clause comes back as it is.
	 * @param clauseParts the clause as a list
	 * @param specials the program's special variables
	 * @param capturesAny whether a closure in the clause body captures one of the names
	 * @return the clause to bind and compile
	 */
	public static List<LispVal> letBound(List<LispVal> clauseParts, Set<String> specials,
			Predicate<Set<String>> capturesAny) {
		if (!(clauseParts.get(1) instanceof LispCons varList)) {
			return clauseParts;
		}
		List<LispVal> vars = varList.toList();
		Set<String> names = new HashSet<>();
		boolean anySpecial = false;
		for (LispVal var : vars) {
			if (!(var instanceof LispSymbol symbol)) {
				return clauseParts;
			}
			names.add(symbol.name());
			anySpecial |= specials.contains(symbol.name());
		}
		if (!anySpecial && !capturesAny.test(names)) {
			return clauseParts;
		}
		LispVal temps = LispNil.INSTANCE;
		LispVal bindings = LispNil.INSTANCE;
		for (int i = vars.size() - 1; i >= 0; i--) {
			LispVal temp = new LispSymbol("__hc_value" + i);
			temps = new LispCons(temp, temps);
			bindings = new LispCons(new LispCons(vars.get(i), new LispCons(temp, LispNil.INSTANCE)), bindings);
		}
		LispVal body = LispNil.INSTANCE;
		for (int i = clauseParts.size() - 1; i >= 2; i--) {
			body = new LispCons(clauseParts.get(i), body);
		}
		List<LispVal> rewritten = new ArrayList<>();
		rewritten.add(clauseParts.get(0));
		rewritten.add(temps);
		rewritten.add(new LispCons(new LispSymbol(LispNames.LET), new LispCons(bindings, body)));
		return rewritten;
	}

}
