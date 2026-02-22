package am.ik.rontolisp;

import java.util.List;

/**
 * Macro expander for cond, and, and or forms. Expands into primitive special forms (if,
 * let, progn) that are handled by the evaluator and compilers.
 */
public final class LispMacroExpander {

	private static final String COND_VAR = "__cond";

	private LispMacroExpander() {
	}

	/**
	 * Expands (cond (test body...) ...) into nested if/let/progn expressions.
	 *
	 * <pre>
	 * (cond)                         -> nil
	 * (cond (test) rest...)          -> (let ((__cond test)) (if __cond __cond (cond rest...)))
	 * (cond (test body) rest...)     -> (if test body (cond rest...))
	 * (cond (test b1 b2...) rest...) -> (if test (progn b1 b2...) (cond rest...))
	 * </pre>
	 * @param cons the cond expression
	 * @return the expanded expression
	 */
	public static LispVal expandCond(LispCons cons) {
		List<LispVal> parts = cons.toList();
		// (cond) -> nil
		if (parts.size() == 1) {
			return LispNil.INSTANCE;
		}
		return expandCondClauses(parts.subList(1, parts.size()));
	}

	private static LispVal expandCondClauses(List<LispVal> clauses) {
		if (clauses.isEmpty()) {
			return LispNil.INSTANCE;
		}
		LispCons clause = (LispCons) clauses.get(0);
		List<LispVal> clauseParts = clause.toList();
		LispVal test = clauseParts.get(0);
		List<LispVal> body = clauseParts.subList(1, clauseParts.size());
		LispVal elseExpr = expandCondClauses(clauses.subList(1, clauses.size()));
		if (body.isEmpty()) {
			// Bodyless clause: (cond (test) ...) -> (let ((__cond test)) (if __cond
			// __cond (cond rest...)))
			return makeLet(COND_VAR, test, makeIf(new LispSymbol(COND_VAR), new LispSymbol(COND_VAR), elseExpr));
		}
		else if (body.size() == 1) {
			// Single body: (cond (test body) ...) -> (if test body (cond rest...))
			return makeIf(test, body.get(0), elseExpr);
		}
		else {
			// Multiple body: (cond (test b1 b2...) ...) -> (if test (progn b1 b2...)
			// (cond rest...))
			return makeIf(test, makeProgn(body), elseExpr);
		}
	}

	/**
	 * Expands (and ...) into cond expressions.
	 *
	 * <pre>
	 * (and)            -> t
	 * (and x)          -> x
	 * (and x y ... z)  -> (cond ((not x) nil) ((not y) nil) ... (t z))
	 * </pre>
	 * @param cons the and expression
	 * @return the expanded expression
	 */
	public static LispVal expandAnd(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() == 1) {
			// (and) -> t
			return LispTrue.INSTANCE;
		}
		if (parts.size() == 2) {
			// (and x) -> x
			return parts.get(1);
		}
		// (and x y ... z) -> (cond ((not x) nil) ((not y) nil) ... (t z))
		List<LispVal> args = parts.subList(1, parts.size());
		// Build clauses list from right to left
		LispVal lastClause = listToCons(List.of(LispTrue.INSTANCE, args.get(args.size() - 1)));
		LispVal clauses = new LispCons(lastClause, LispNil.INSTANCE);
		for (int i = args.size() - 2; i >= 0; i--) {
			LispVal notClause = listToCons(List.of(makeNot(args.get(i)), LispNil.INSTANCE));
			clauses = new LispCons(notClause, clauses);
		}
		LispCons condExpr = new LispCons(new LispSymbol(LispNames.COND), clauses);
		return expandCond(condExpr);
	}

	/**
	 * Expands (or ...) into cond expressions.
	 *
	 * <pre>
	 * (or)             -> nil
	 * (or x)           -> x
	 * (or x y ... z)   -> (cond (x) (y) ... (z))
	 * </pre>
	 * @param cons the or expression
	 * @return the expanded expression
	 */
	public static LispVal expandOr(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() == 1) {
			// (or) -> nil
			return LispNil.INSTANCE;
		}
		if (parts.size() == 2) {
			// (or x) -> x
			return parts.get(1);
		}
		// (or x y ... z) -> (cond (x) (y) ... (z))
		// Each clause is a bodyless clause wrapping a single expr
		LispVal clauses = LispNil.INSTANCE;
		for (int i = parts.size() - 1; i >= 1; i--) {
			LispVal clause = new LispCons(parts.get(i), LispNil.INSTANCE);
			clauses = new LispCons(clause, clauses);
		}
		LispCons condExpr = new LispCons(new LispSymbol(LispNames.COND), clauses);
		return expandCond(condExpr);
	}

	/**
	 * Expands (when condition body...) into an if expression.
	 *
	 * <pre>
	 * (when cond body)       -> (if cond body nil)
	 * (when cond b1 b2...)   -> (if cond (progn b1 b2...) nil)
	 * </pre>
	 * @param cons the when expression
	 * @return the expanded expression
	 */
	public static LispVal expandWhen(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal condition = parts.get(1);
		List<LispVal> body = parts.subList(2, parts.size());
		if (body.size() == 1) {
			return makeIf(condition, body.get(0), LispNil.INSTANCE);
		}
		else {
			return makeIf(condition, makeProgn(body), LispNil.INSTANCE);
		}
	}

	private static LispVal makeIf(LispVal cond, LispVal then, LispVal else_) {
		return listToCons(List.of(new LispSymbol(LispNames.IF), cond, then, else_));
	}

	private static LispVal makeLet(String varName, LispVal value, LispVal body) {
		// (let ((varName value)) body)
		LispVal binding = listToCons(List.of(new LispSymbol(varName), value));
		LispVal bindings = new LispCons(binding, LispNil.INSTANCE);
		return listToCons(List.of(new LispSymbol(LispNames.LET), bindings, body));
	}

	private static LispVal makeProgn(List<LispVal> exprs) {
		List<LispVal> all = new java.util.ArrayList<>();
		all.add(new LispSymbol(LispNames.PROGN));
		all.addAll(exprs);
		return listToCons(all);
	}

	private static LispVal makeNot(LispVal expr) {
		return listToCons(List.of(new LispSymbol(LispNames.NOT), expr));
	}

	/**
	 * Checks if the given name matches the c[ad]{2,4}r pattern (e.g., caar, cadr, cddr,
	 * caddr, cdddr, etc.).
	 * @param name the function name to check
	 * @return {@code true} if the name matches the pattern
	 */
	public static boolean isCarCdrComposition(String name) {
		int len = name.length();
		if (len < 4 || len > 6 || name.charAt(0) != 'c' || name.charAt(len - 1) != 'r') {
			return false;
		}
		for (int i = 1; i < len - 1; i++) {
			char ch = name.charAt(i);
			if (ch != 'a' && ch != 'd') {
				return false;
			}
		}
		return true;
	}

	/**
	 * Expands car/cdr composition functions into nested car/cdr calls.
	 *
	 * <pre>
	 * (cadr x)  -> (car (cdr x))
	 * (caddr x) -> (car (cdr (cdr x)))
	 * </pre>
	 * @param cons the car/cdr composition expression
	 * @return the expanded expression
	 */
	public static LispVal expandCarCdrComposition(LispCons cons) {
		List<LispVal> parts = cons.toList();
		String name = ((LispSymbol) parts.get(0)).name();
		LispVal arg = parts.get(1);
		// Process middle characters right to left
		for (int i = name.length() - 2; i >= 1; i--) {
			String op = (name.charAt(i) == 'a') ? LispNames.CAR : LispNames.CDR;
			arg = listToCons(List.of(new LispSymbol(op), arg));
		}
		return arg;
	}

	/**
	 * Expands (1+ x) into (+ x 1).
	 * @param cons the 1+ expression
	 * @return the expanded expression
	 */
	public static LispVal expandOnePlus(LispCons cons) {
		LispVal arg = cons.toList().get(1);
		return listToCons(List.of(new LispSymbol(LispNames.ADD), arg, new LispInteger(1)));
	}

	/**
	 * Expands (1- x) into (- x 1).
	 * @param cons the 1- expression
	 * @return the expanded expression
	 */
	public static LispVal expandOneMinus(LispCons cons) {
		LispVal arg = cons.toList().get(1);
		return listToCons(List.of(new LispSymbol(LispNames.SUB), arg, new LispInteger(1)));
	}

	/**
	 * Expands (zerop x) into (= x 0).
	 * @param cons the zerop expression
	 * @return the expanded expression
	 */
	public static LispVal expandZerop(LispCons cons) {
		LispVal arg = cons.toList().get(1);
		return listToCons(List.of(new LispSymbol(LispNames.EQ), arg, new LispInteger(0)));
	}

	/**
	 * Expands (plusp x) into {@code (> x 0)}.
	 * @param cons the plusp expression
	 * @return the expanded expression
	 */
	public static LispVal expandPlusp(LispCons cons) {
		LispVal arg = cons.toList().get(1);
		return listToCons(List.of(new LispSymbol(LispNames.GT), arg, new LispInteger(0)));
	}

	/**
	 * Expands (minusp x) into {@code (< x 0)}.
	 * @param cons the minusp expression
	 * @return the expanded expression
	 */
	public static LispVal expandMinusp(LispCons cons) {
		LispVal arg = cons.toList().get(1);
		return listToCons(List.of(new LispSymbol(LispNames.LT), arg, new LispInteger(0)));
	}

	/**
	 * Expands (evenp x) into (= (mod x 2) 0).
	 * @param cons the evenp expression
	 * @return the expanded expression
	 */
	public static LispVal expandEvenp(LispCons cons) {
		LispVal arg = cons.toList().get(1);
		LispVal modExpr = listToCons(List.of(new LispSymbol(LispNames.MOD), arg, new LispInteger(2)));
		return listToCons(List.of(new LispSymbol(LispNames.EQ), modExpr, new LispInteger(0)));
	}

	/**
	 * Expands (oddp x) into (not (= (mod x 2) 0)).
	 * @param cons the oddp expression
	 * @return the expanded expression
	 */
	public static LispVal expandOddp(LispCons cons) {
		LispVal arg = cons.toList().get(1);
		LispVal modExpr = listToCons(List.of(new LispSymbol(LispNames.MOD), arg, new LispInteger(2)));
		LispVal eqExpr = listToCons(List.of(new LispSymbol(LispNames.EQ), modExpr, new LispInteger(0)));
		return makeNot(eqExpr);
	}

	/**
	 * Expands (unless condition body...) into an if expression.
	 *
	 * <pre>
	 * (unless cond body)       -> (if cond nil body)
	 * (unless cond b1 b2...)   -> (if cond nil (progn b1 b2...))
	 * </pre>
	 * @param cons the unless expression
	 * @return the expanded expression
	 */
	public static LispVal expandUnless(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal condition = parts.get(1);
		List<LispVal> body = parts.subList(2, parts.size());
		if (body.size() == 1) {
			return makeIf(condition, LispNil.INSTANCE, body.get(0));
		}
		else {
			return makeIf(condition, LispNil.INSTANCE, makeProgn(body));
		}
	}

	/**
	 * Expands (first x) into (car x).
	 * @param cons the first expression
	 * @return the expanded expression
	 */
	public static LispVal expandFirst(LispCons cons) {
		LispVal arg = cons.toList().get(1);
		return listToCons(List.of(new LispSymbol(LispNames.CAR), arg));
	}

	/**
	 * Expands (nth n list) into (car (nthcdr n list)).
	 * @param cons the nth expression
	 * @return the expanded expression
	 */
	public static LispVal expandNth(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal n = parts.get(1);
		LispVal list = parts.get(2);
		LispVal nthcdrExpr = listToCons(List.of(new LispSymbol(LispNames.NTHCDR), n, list));
		return listToCons(List.of(new LispSymbol(LispNames.CAR), nthcdrExpr));
	}

	/**
	 * Expands (second x) into (nth 1 x) -> (car (nthcdr 1 x)).
	 * @param cons the second expression
	 * @return the expanded expression
	 */
	public static LispVal expandSecond(LispCons cons) {
		LispVal arg = cons.toList().get(1);
		LispCons nthCons = listToCons(List.of(new LispSymbol(LispNames.NTH), new LispInteger(1), arg));
		return expandNth(nthCons);
	}

	/**
	 * Expands (third x) into (nth 2 x) -> (car (nthcdr 2 x)).
	 * @param cons the third expression
	 * @return the expanded expression
	 */
	public static LispVal expandThird(LispCons cons) {
		LispVal arg = cons.toList().get(1);
		LispCons nthCons = listToCons(List.of(new LispSymbol(LispNames.NTH), new LispInteger(2), arg));
		return expandNth(nthCons);
	}

	/**
	 * Expands (fourth x) into (nth 3 x) -> (car (nthcdr 3 x)).
	 * @param cons the fourth expression
	 * @return the expanded expression
	 */
	public static LispVal expandFourth(LispCons cons) {
		LispVal arg = cons.toList().get(1);
		LispCons nthCons = listToCons(List.of(new LispSymbol(LispNames.NTH), new LispInteger(3), arg));
		return expandNth(nthCons);
	}

	/**
	 * Expands (defun name (params...) body...) into (setq name (lambda (params...)
	 * body...)).
	 *
	 * <pre>
	 * (defun f (x y) body1 body2) -> (setq f (lambda (x y) body1 body2))
	 * </pre>
	 * @param cons the defun expression
	 * @return the expanded expression
	 */
	public static LispVal expandDefun(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal name = parts.get(1);
		LispVal params = parts.get(2);
		List<LispVal> lambdaParts = new java.util.ArrayList<>();
		lambdaParts.add(new LispSymbol(LispNames.LAMBDA));
		lambdaParts.add(params);
		lambdaParts.addAll(parts.subList(3, parts.size()));
		LispVal lambda = listToCons(lambdaParts);
		return listToCons(List.of(new LispSymbol(LispNames.SETQ), name, lambda));
	}

	private static LispCons listToCons(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return (LispCons) result;
	}

}
