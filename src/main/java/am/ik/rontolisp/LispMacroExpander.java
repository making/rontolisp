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

	/**
	 * Expands (dotimes (var count-form [result-form]) body...) into a let/while loop.
	 *
	 * <pre>
	 * (dotimes (i n result) body...) ->
	 * (let ((i 0) (__dotimes_limit n))
	 *   (while (&lt; i __dotimes_limit)
	 *     body...
	 *     (setq i (+ i 1)))
	 *   result)
	 * </pre>
	 *
	 * The count form is evaluated once and bound to a temporary so that side effects in
	 * the count expression are not repeated on every iteration. The loop variable is
	 * bound to the integers 0, 1, ..., count-1 in turn; after the loop it holds the count
	 * value when the optional result form is evaluated. Without a result form the
	 * expansion yields nil.
	 * @param cons the dotimes expression
	 * @return the expanded expression
	 */
	public static LispVal expandDotimes(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispCons spec = (LispCons) parts.get(1);
		List<LispVal> specParts = spec.toList();
		LispVal var = specParts.get(0);
		LispVal countForm = specParts.get(1);
		LispVal resultForm = (specParts.size() > 2) ? specParts.get(2) : LispNil.INSTANCE;
		List<LispVal> body = parts.subList(2, parts.size());
		LispSymbol limitSym = new LispSymbol(DOTIMES_LIMIT_VAR);
		// Bindings: ((var 0) (__dotimes_limit count-form))
		LispVal binding1 = listToCons(List.of(var, new LispInteger(0)));
		LispVal binding2 = listToCons(List.of(limitSym, countForm));
		LispVal bindings = listToCons(List.of(binding1, binding2));
		// (< var __dotimes_limit)
		LispVal test = listToCons(List.of(new LispSymbol(LispNames.LT), var, limitSym));
		// (setq var (+ var 1))
		LispVal increment = listToCons(List.of(new LispSymbol(LispNames.ADD), var, new LispInteger(1)));
		LispVal step = listToCons(List.of(new LispSymbol(LispNames.SETQ), var, increment));
		// (while test body... step)
		List<LispVal> whileParts = new java.util.ArrayList<>();
		whileParts.add(new LispSymbol(LispNames.WHILE));
		whileParts.add(test);
		whileParts.addAll(body);
		whileParts.add(step);
		LispVal whileExpr = listToCons(whileParts);
		// (let (bindings) while-expr result-form)
		List<LispVal> letParts = new java.util.ArrayList<>();
		letParts.add(new LispSymbol(LispNames.LET));
		letParts.add(bindings);
		letParts.add(whileExpr);
		letParts.add(resultForm);
		return listToCons(letParts);
	}

	private static final String DOTIMES_LIMIT_VAR = "__dotimes_limit";

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
	 * Expands (rest x) into (cdr x).
	 * @param cons the rest expression
	 * @return the expanded expression
	 */
	public static LispVal expandRest(LispCons cons) {
		LispVal arg = cons.toList().get(1);
		return listToCons(List.of(new LispSymbol(LispNames.CDR), arg));
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
	 * Expands (setf place value) into the appropriate mutation form.
	 *
	 * <pre>
	 * (setf x val)            -> (setq x val)
	 * (setf (car x) val)      -> (let ((__setf val)) (rplaca x __setf) __setf)
	 * (setf (cdr x) val)      -> (let ((__setf val)) (rplacd x __setf) __setf)
	 * (setf (nth n x) val)    -> (let ((__setf val)) (rplaca (nthcdr n x) __setf) __setf)
	 * (setf (first x) val)    -> same as (setf (car x) val)
	 * (setf (second x) val)   -> (let ((__setf val)) (rplaca (nthcdr 1 x) __setf) __setf)
	 * (setf (third x) val)    -> (let ((__setf val)) (rplaca (nthcdr 2 x) __setf) __setf)
	 * (setf (fourth x) val)   -> (let ((__setf val)) (rplaca (nthcdr 3 x) __setf) __setf)
	 * (setf (caXXXr x) val)   -> rplaca/rplacd on nested cdr chain
	 * </pre>
	 * @param cons the setf expression
	 * @return the expanded expression
	 */
	public static LispVal expandSetf(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal place = parts.get(1);
		LispVal value = parts.get(2);
		// (setf x val) -> (setq x val)
		if (place instanceof LispSymbol) {
			return listToCons(List.of(new LispSymbol(LispNames.SETQ), place, value));
		}
		// (setf (accessor ...) val)
		if (place instanceof LispCons placeCons) {
			List<LispVal> placeParts = placeCons.toList();
			String accessor = ((LispSymbol) placeParts.get(0)).name();
			return switch (accessor) {
				case LispNames.CAR, LispNames.FIRST -> expandSetfWithRplaca(placeParts.get(1), value);
				case LispNames.CDR, LispNames.REST -> expandSetfWithRplacd(placeParts.get(1), value);
				case LispNames.NTH -> {
					// (setf (nth n x) val) -> (let ((__setf val)) (rplaca (nthcdr n x)
					// __setf) __setf)
					LispVal n = placeParts.get(1);
					LispVal list = placeParts.get(2);
					LispVal nthcdrExpr = listToCons(List.of(new LispSymbol(LispNames.NTHCDR), n, list));
					yield expandSetfWithRplaca(nthcdrExpr, value);
				}
				case LispNames.SECOND -> {
					LispVal nthcdrExpr = listToCons(
							List.of(new LispSymbol(LispNames.NTHCDR), new LispInteger(1), placeParts.get(1)));
					yield expandSetfWithRplaca(nthcdrExpr, value);
				}
				case LispNames.THIRD -> {
					LispVal nthcdrExpr = listToCons(
							List.of(new LispSymbol(LispNames.NTHCDR), new LispInteger(2), placeParts.get(1)));
					yield expandSetfWithRplaca(nthcdrExpr, value);
				}
				case LispNames.FOURTH -> {
					LispVal nthcdrExpr = listToCons(
							List.of(new LispSymbol(LispNames.NTHCDR), new LispInteger(3), placeParts.get(1)));
					yield expandSetfWithRplaca(nthcdrExpr, value);
				}
				default -> {
					if (isCarCdrComposition(accessor)) {
						yield expandSetfCarCdr(accessor, placeParts.get(1), value);
					}
					throw new UnsupportedOperationException("setf does not support place: " + accessor);
				}
			};
		}
		throw new UnsupportedOperationException("setf expects a symbol or accessor form as place");
	}

	private static final String SETF_VAR = "__setf";

	private static LispVal expandSetfWithRplaca(LispVal target, LispVal value) {
		// (let ((__setf value)) (rplaca target __setf) __setf)
		LispVal rplacaExpr = listToCons(List.of(new LispSymbol(LispNames.RPLACA), target, new LispSymbol(SETF_VAR)));
		LispVal body = makeProgn(List.of(rplacaExpr, new LispSymbol(SETF_VAR)));
		return makeLet(SETF_VAR, value, body);
	}

	private static LispVal expandSetfWithRplacd(LispVal target, LispVal value) {
		// (let ((__setf value)) (rplacd target __setf) __setf)
		LispVal rplacdExpr = listToCons(List.of(new LispSymbol(LispNames.RPLACD), target, new LispSymbol(SETF_VAR)));
		LispVal body = makeProgn(List.of(rplacdExpr, new LispSymbol(SETF_VAR)));
		return makeLet(SETF_VAR, value, body);
	}

	private static LispVal expandSetfCarCdr(String accessor, LispVal arg, LispVal value) {
		// e.g. (setf (cadr x) val) -> (setf (car (cdr x)) val)
		// The outermost operation (first char after 'c') determines rplaca vs rplacd
		// The remaining inner operations build the target expression
		char outerOp = accessor.charAt(1);
		// Build the inner chain: characters from index 2 to len-2 (right to left)
		LispVal target = arg;
		for (int i = accessor.length() - 2; i >= 2; i--) {
			String op = (accessor.charAt(i) == 'a') ? LispNames.CAR : LispNames.CDR;
			target = listToCons(List.of(new LispSymbol(op), target));
		}
		if (outerOp == 'a') {
			return expandSetfWithRplaca(target, value);
		}
		else {
			return expandSetfWithRplacd(target, value);
		}
	}

	/**
	 * Expands (push item place) into a let/setf/cons expression.
	 *
	 * <pre>
	 * (push item place) -> (let ((__push_item item)) (setf place (cons __push_item place)))
	 * </pre>
	 * @param cons the push expression
	 * @return the expanded expression
	 */
	public static LispVal expandPush(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal item = parts.get(1);
		LispVal place = parts.get(2);
		// (cons __push_item place)
		LispVal consExpr = listToCons(List.of(new LispSymbol(LispNames.CONS), new LispSymbol(PUSH_VAR), place));
		// (setf place (cons __push_item place))
		LispVal setfExpr = listToCons(List.of(new LispSymbol(LispNames.SETF), place, consExpr));
		return makeLet(PUSH_VAR, item, setfExpr);
	}

	private static final String PUSH_VAR = "__push_item";

	/**
	 * Expands (pop place) into a let/progn/setf expression.
	 *
	 * <pre>
	 * (pop place) -> (let ((__pop (car place))) (progn (setf place (cdr place)) __pop))
	 * </pre>
	 * @param cons the pop expression
	 * @return the expanded expression
	 */
	public static LispVal expandPop(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal place = parts.get(1);
		// (car place)
		LispVal carExpr = listToCons(List.of(new LispSymbol(LispNames.CAR), place));
		// (cdr place)
		LispVal cdrExpr = listToCons(List.of(new LispSymbol(LispNames.CDR), place));
		// (setf place (cdr place))
		LispVal setfExpr = listToCons(List.of(new LispSymbol(LispNames.SETF), place, cdrExpr));
		// (progn (setf place (cdr place)) __pop)
		LispVal body = makeProgn(List.of(setfExpr, new LispSymbol(POP_VAR)));
		return makeLet(POP_VAR, carExpr, body);
	}

	private static final String POP_VAR = "__pop";

	/**
	 * Expands (remf place indicator) into conditional setf/%remf-tail expression.
	 *
	 * <pre>
	 * (remf place indicator) ->
	 * (let ((__plist place))
	 *   (if (null __plist) nil
	 *     (let ((__indicator indicator))
	 *       (if (eq (car __plist) __indicator)
	 *         (progn (setf place (cdr (cdr __plist))) t)
	 *         (%remf-tail __plist __indicator)))))
	 * </pre>
	 * @param cons the remf expression
	 * @return the expanded expression
	 */
	public static LispVal expandRemf(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal place = parts.get(1);
		LispVal indicator = parts.get(2);
		LispSymbol plistSym = new LispSymbol(REMF_PLIST_VAR);
		LispSymbol indSym = new LispSymbol(REMF_IND_VAR);
		// (car __plist)
		LispVal carPlist = listToCons(List.of(new LispSymbol(LispNames.CAR), plistSym));
		// (eq (car __plist) __indicator)
		LispVal eqExpr = listToCons(List.of(new LispSymbol(LispNames.EQ_GENERAL), carPlist, indSym));
		// (cdr (cdr __plist))
		LispVal cddrPlist = listToCons(
				List.of(new LispSymbol(LispNames.CDR), listToCons(List.of(new LispSymbol(LispNames.CDR), plistSym))));
		// (setf place (cdr (cdr __plist)))
		LispVal setfExpr = listToCons(List.of(new LispSymbol(LispNames.SETF), place, cddrPlist));
		// (progn (setf place (cdr (cdr __plist))) t)
		LispVal headMatch = makeProgn(List.of(setfExpr, LispTrue.INSTANCE));
		// (%remf-tail __plist __indicator)
		LispVal tailCall = listToCons(List.of(new LispSymbol(LispNames.REMF_TAIL), plistSym, indSym));
		// (if (eq (car __plist) __indicator) headMatch tailCall)
		LispVal innerIf = makeIf(eqExpr, headMatch, tailCall);
		// (let ((__indicator indicator)) innerIf)
		LispVal innerLet = makeLet(REMF_IND_VAR, indicator, innerIf);
		// (null __plist)
		LispVal nullCheck = listToCons(List.of(new LispSymbol(LispNames.NULL), plistSym));
		// (if (null __plist) nil innerLet)
		LispVal outerIf = makeIf(nullCheck, LispNil.INSTANCE, innerLet);
		return makeLet(REMF_PLIST_VAR, place, outerIf);
	}

	private static final String REMF_PLIST_VAR = "__plist";

	private static final String REMF_IND_VAR = "__indicator";

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
	/**
	 * Expands (let* ((x 1) (y x)) body...) into nested let forms so each binding sees the
	 * previous ones.
	 *
	 * <pre>
	 * (let* () body...)                -> (let () body...)
	 * (let* ((x 1) (y x)) body...)    -> (let ((x 1)) (let ((y x)) body...))
	 * </pre>
	 * @param cons the let* expression
	 * @return the expanded expression
	 */
	public static LispVal expandLetStar(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal bindings = parts.get(1);
		List<LispVal> body = parts.subList(2, parts.size());
		if (!(bindings instanceof LispCons bindingsCons)) {
			// (let* () body...) -> (let () body...)
			List<LispVal> letParts = new java.util.ArrayList<>();
			letParts.add(new LispSymbol(LispNames.LET));
			letParts.add(LispNil.INSTANCE);
			letParts.addAll(body);
			return listToCons(letParts);
		}
		List<LispVal> bindingList = bindingsCons.toList();
		// Build from the innermost let outward
		List<LispVal> innerParts = new java.util.ArrayList<>();
		innerParts.add(new LispSymbol(LispNames.LET));
		innerParts.add(new LispCons(bindingList.get(bindingList.size() - 1), LispNil.INSTANCE));
		innerParts.addAll(body);
		LispVal result = listToCons(innerParts);
		for (int i = bindingList.size() - 2; i >= 0; i--) {
			result = listToCons(
					List.of(new LispSymbol(LispNames.LET), new LispCons(bindingList.get(i), LispNil.INSTANCE), result));
		}
		return result;
	}

	/**
	 * Expands (dolist (var list result?) body...) into a let/while loop.
	 *
	 * <pre>
	 * (dolist (x lst r) body...) ->
	 *   (let ((__dolist lst))
	 *     (while (consp __dolist)
	 *       (let ((x (car __dolist))) body...)
	 *       (setq __dolist (cdr __dolist)))
	 *     (let ((x nil)) r))
	 * </pre>
	 * @param cons the dolist expression
	 * @return the expanded expression
	 */
	public static LispVal expandDolist(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispCons spec = (LispCons) parts.get(1);
		List<LispVal> specParts = spec.toList();
		LispVal var = specParts.get(0);
		LispVal listForm = specParts.get(1);
		LispVal resultForm = (specParts.size() > 2) ? specParts.get(2) : LispNil.INSTANCE;
		List<LispVal> body = parts.subList(2, parts.size());
		LispSymbol cursor = new LispSymbol(DOLIST_CURSOR_VAR);
		// (setq __dolist (cdr __dolist))
		LispVal step = listToCons(List.of(new LispSymbol(LispNames.SETQ), cursor, callOf(LispNames.CDR, cursor)));
		// (while (consp __dolist) iteration? step); the iteration let is omitted for an
		// empty body (a body-less let does not compile)
		List<LispVal> whileParts = new java.util.ArrayList<>();
		whileParts.add(new LispSymbol(LispNames.WHILE));
		whileParts.add(callOf(LispNames.CONSP, cursor));
		if (!body.isEmpty()) {
			// (let ((var (car __dolist))) body...)
			List<LispVal> iterParts = new java.util.ArrayList<>();
			iterParts.add(new LispSymbol(LispNames.LET));
			iterParts.add(new LispCons(listToCons(List.of(var, callOf(LispNames.CAR, cursor))), LispNil.INSTANCE));
			iterParts.addAll(body);
			whileParts.add(listToCons(iterParts));
		}
		whileParts.add(step);
		LispVal whileExpr = listToCons(whileParts);
		// (let ((var nil)) result) -- CL evaluates the result form with var bound to nil
		LispVal resultExpr = listToCons(List.of(new LispSymbol(LispNames.LET),
				new LispCons(listToCons(List.of(var, LispNil.INSTANCE)), LispNil.INSTANCE), resultForm));
		// (let ((__dolist list)) while-expr result-expr)
		LispVal bindings = new LispCons(listToCons(List.of(cursor, listForm)), LispNil.INSTANCE);
		return listToCons(List.of(new LispSymbol(LispNames.LET), bindings, whileExpr, resultExpr));
	}

	/**
	 * Expands (incf place delta?) into (setf place (+ place delta)).
	 * @param cons the incf expression
	 * @return the expanded expression
	 */
	public static LispVal expandIncf(LispCons cons) {
		return expandIncfDecf(cons, LispNames.ADD);
	}

	/**
	 * Expands (decf place delta?) into (setf place (- place delta)).
	 * @param cons the decf expression
	 * @return the expanded expression
	 */
	public static LispVal expandDecf(LispCons cons) {
		return expandIncfDecf(cons, LispNames.SUB);
	}

	private static LispVal expandIncfDecf(LispCons cons, String op) {
		List<LispVal> parts = cons.toList();
		LispVal place = parts.get(1);
		LispVal delta = (parts.size() > 2) ? parts.get(2) : new LispInteger(1);
		LispVal newValue = listToCons(List.of(new LispSymbol(op), place, delta));
		return listToCons(List.of(new LispSymbol(LispNames.SETF), place, newValue));
	}

	/**
	 * Expands (length lst) into a reduce-based count so all backends compile it through
	 * existing primitives.
	 *
	 * <pre>
	 * (length lst) -> (reduce (lambda (__acc __x) (+ __acc 1)) 0 lst)
	 * </pre>
	 * @param cons the length expression
	 * @return the expanded expression
	 */
	public static LispVal expandLength(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol acc = new LispSymbol("__length_acc");
		LispSymbol x = new LispSymbol("__length_x");
		LispVal lambda = listToCons(List.of(new LispSymbol(LispNames.LAMBDA), listToCons(List.of(acc, x)),
				listToCons(List.of(new LispSymbol(LispNames.ADD), acc, new LispInteger(1)))));
		return listToCons(List.of(new LispSymbol(LispNames.REDUCE), lambda, new LispInteger(0), parts.get(1)));
	}

	/**
	 * Expands (reverse lst) into a reduce-based reversal.
	 *
	 * <pre>
	 * (reverse lst) -> (reduce (lambda (__acc __x) (cons __x __acc)) nil lst)
	 * </pre>
	 * @param cons the reverse expression
	 * @return the expanded expression
	 */
	public static LispVal expandReverse(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol acc = new LispSymbol("__reverse_acc");
		LispSymbol x = new LispSymbol("__reverse_x");
		LispVal lambda = listToCons(List.of(new LispSymbol(LispNames.LAMBDA), listToCons(List.of(acc, x)),
				listToCons(List.of(new LispSymbol(LispNames.CONS), x, acc))));
		return listToCons(List.of(new LispSymbol(LispNames.REDUCE), lambda, LispNil.INSTANCE, parts.get(1)));
	}

	/**
	 * Expands (member item lst) into a let/while scan returning the tail whose car is
	 * {@code eq} to the item, or nil.
	 * @param cons the member expression
	 * @return the expanded expression
	 */
	public static LispVal expandMember(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol item = new LispSymbol("__member_item");
		LispSymbol cur = new LispSymbol("__member_cur");
		// (and (consp __cur) (not (eq __item (car __cur))))
		LispVal test = listToCons(
				List.of(new LispSymbol(LispNames.AND), callOf(LispNames.CONSP, cur), callOf(LispNames.NOT,
						listToCons(List.of(new LispSymbol(LispNames.EQ_GENERAL), item, callOf(LispNames.CAR, cur))))));
		// (while test (setq __cur (cdr __cur)))
		LispVal step = listToCons(List.of(new LispSymbol(LispNames.SETQ), cur, callOf(LispNames.CDR, cur)));
		LispVal whileExpr = listToCons(List.of(new LispSymbol(LispNames.WHILE), test, step));
		// (let ((__item item) (__cur lst)) while-expr __cur)
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(item, parts.get(1))), listToCons(List.of(cur, parts.get(2)))));
		return listToCons(List.of(new LispSymbol(LispNames.LET), bindings, whileExpr, cur));
	}

	/**
	 * Expands (assoc key alist) into a let/while scan returning the first pair whose car
	 * is {@code eq} to the key, or nil.
	 * @param cons the assoc expression
	 * @return the expanded expression
	 */
	public static LispVal expandAssoc(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol key = new LispSymbol("__assoc_key");
		LispSymbol cur = new LispSymbol("__assoc_cur");
		// match: (and (consp (car __cur)) (eq __key (car (car __cur))))
		LispVal pair = callOf(LispNames.CAR, cur);
		LispVal match = listToCons(
				List.of(new LispSymbol(LispNames.AND), listToCons(List.of(new LispSymbol(LispNames.CONSP), pair)),
						listToCons(List.of(new LispSymbol(LispNames.EQ_GENERAL), key,
								listToCons(List.of(new LispSymbol(LispNames.CAR), pair))))));
		// (while (and (consp __cur) (not match)) (setq __cur (cdr __cur)))
		LispVal test = listToCons(List.of(new LispSymbol(LispNames.AND), callOf(LispNames.CONSP, cur),
				listToCons(List.of(new LispSymbol(LispNames.NOT), match))));
		LispVal step = listToCons(List.of(new LispSymbol(LispNames.SETQ), cur, callOf(LispNames.CDR, cur)));
		LispVal whileExpr = listToCons(List.of(new LispSymbol(LispNames.WHILE), test, step));
		// (if (consp __cur) (car __cur) nil)
		LispVal result = makeIf(callOf(LispNames.CONSP, cur), callOf(LispNames.CAR, cur), LispNil.INSTANCE);
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(key, parts.get(1))), listToCons(List.of(cur, parts.get(2)))));
		return listToCons(List.of(new LispSymbol(LispNames.LET), bindings, whileExpr, result));
	}

	/**
	 * Expands (last lst) into a let/while walk returning the last cons cell (or nil for
	 * an empty list).
	 * @param cons the last expression
	 * @return the expanded expression
	 */
	public static LispVal expandLast(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol cur = new LispSymbol("__last_cur");
		// (while (and (consp __cur) (consp (cdr __cur))) (setq __cur (cdr __cur)))
		LispVal test = listToCons(List.of(new LispSymbol(LispNames.AND), callOf(LispNames.CONSP, cur),
				listToCons(List.of(new LispSymbol(LispNames.CONSP), callOf(LispNames.CDR, cur)))));
		LispVal step = listToCons(List.of(new LispSymbol(LispNames.SETQ), cur, callOf(LispNames.CDR, cur)));
		LispVal whileExpr = listToCons(List.of(new LispSymbol(LispNames.WHILE), test, step));
		LispVal bindings = new LispCons(listToCons(List.of(cur, parts.get(1))), LispNil.INSTANCE);
		return listToCons(List.of(new LispSymbol(LispNames.LET), bindings, whileExpr, cur));
	}

	private static final String DOLIST_CURSOR_VAR = "__dolist";

	private static LispVal callOf(String op, LispVal arg) {
		return listToCons(List.of(new LispSymbol(op), arg));
	}

	/**
	 * Expands (defun name (params...) body...) into (setq name (lambda (params...)
	 * body...)), the canonical shape the compilers collect in Pass 1. The interpreter
	 * handles defun natively (Lisp-2 function namespace) and does not use this.
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

	/**
	 * Expands (format t control-string args...) into a sequence of princ/prin1/terpri
	 * calls, returning nil. The control string must be a literal string and the
	 * destination must be the literal {@code t} (standard output). Supported directives:
	 * {@code ~a}/{@code ~A} (princ), {@code ~s}/{@code ~S} (prin1), {@code ~d}/{@code ~D}
	 * (princ), {@code ~%} (terpri) and {@code ~~} (a literal tilde).
	 *
	 * <pre>
	 * (format t "Hello ~a!~%" name) ->
	 * (let ((__format_arg0 name))
	 *   (princ "Hello ")
	 *   (princ __format_arg0)
	 *   (princ "!")
	 *   (terpri)
	 *   nil)
	 * </pre>
	 *
	 * All arguments are bound to temporaries up front so they are evaluated left to right
	 * before any output, matching Common Lisp's function-call semantics.
	 * @param cons the format expression
	 * @return the expanded expression
	 */
	public static LispVal expandFormat(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 3) {
			throw new IllegalArgumentException("format expects a destination and a control string");
		}
		if (!(parts.get(1) instanceof LispTrue)) {
			throw new UnsupportedOperationException(
					"format supports only t (standard output) as destination, got: " + parts.get(1).print());
		}
		if (!(parts.get(2) instanceof LispString control)) {
			throw new UnsupportedOperationException(
					"format requires a literal control string, got: " + parts.get(2).print());
		}
		List<LispVal> args = parts.subList(3, parts.size());
		List<LispVal> bindings = new java.util.ArrayList<>();
		List<LispSymbol> argSyms = new java.util.ArrayList<>();
		for (int i = 0; i < args.size(); i++) {
			LispSymbol g = new LispSymbol(FORMAT_ARG_VAR + i);
			argSyms.add(g);
			bindings.add(listToCons(List.of(g, args.get(i))));
		}
		List<LispVal> forms = new java.util.ArrayList<>();
		String s = control.value();
		StringBuilder literal = new StringBuilder();
		int argIndex = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c != '~') {
				literal.append(c);
				continue;
			}
			if (i + 1 >= s.length()) {
				throw new IllegalArgumentException("format: control string ends with ~");
			}
			char directive = s.charAt(++i);
			switch (Character.toLowerCase(directive)) {
				case '~' -> literal.append('~');
				case '%' -> {
					flushFormatLiteral(literal, forms);
					forms.add(listToCons(List.of(new LispSymbol(LispNames.TERPRI))));
				}
				case 'a', 'd', 's' -> {
					flushFormatLiteral(literal, forms);
					if (argIndex >= argSyms.size()) {
						throw new IllegalArgumentException("format: not enough arguments for directive ~" + directive);
					}
					String op = (Character.toLowerCase(directive) == 's') ? LispNames.PRIN1 : LispNames.PRINC;
					forms.add(listToCons(List.of(new LispSymbol(op), argSyms.get(argIndex++))));
				}
				default -> throw new UnsupportedOperationException("format: unsupported directive ~" + directive);
			}
		}
		flushFormatLiteral(literal, forms);
		// format returns nil
		forms.add(LispNil.INSTANCE);
		if (bindings.isEmpty()) {
			return makeProgn(forms);
		}
		List<LispVal> letParts = new java.util.ArrayList<>();
		letParts.add(new LispSymbol(LispNames.LET));
		letParts.add(listToCons(bindings));
		letParts.addAll(forms);
		return listToCons(letParts);
	}

	private static final String FORMAT_ARG_VAR = "__format_arg";

	private static void flushFormatLiteral(StringBuilder literal, List<LispVal> forms) {
		if (!literal.isEmpty()) {
			forms.add(listToCons(List.of(new LispSymbol(LispNames.PRINC), new LispString(literal.toString()))));
			literal.setLength(0);
		}
	}

	/**
	 * Expands {@code (mod a b)} into a {@code rem}-based form whose result takes the sign
	 * of the divisor (Common Lisp modulo), reusing the {@code rem}, {@code *}, {@code <}
	 * and {@code +} primitives:
	 * {@code (let* ((a' a) (b' b) (r (rem a' b'))) (if (< (* r b') 0) (+ r b') r))}. Each
	 * operand is evaluated exactly once.
	 * @param cons the mod expression
	 * @return the expanded expression
	 */
	public static LispVal expandMod(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol av = new LispSymbol("__mod_a");
		LispSymbol bv = new LispSymbol("__mod_b");
		LispSymbol rv = new LispSymbol("__mod_r");
		LispVal remExpr = listToCons(List.of(new LispSymbol(LispNames.REM), av, bv));
		LispVal bindings = listToCons(List.of(listToCons(List.of(av, parts.get(1))),
				listToCons(List.of(bv, parts.get(2))), listToCons(List.of(rv, remExpr))));
		LispVal product = listToCons(List.of(new LispSymbol(LispNames.MUL), rv, bv));
		LispVal test = listToCons(List.of(new LispSymbol(LispNames.LT), product, new LispInteger(0)));
		LispVal corrected = listToCons(List.of(new LispSymbol(LispNames.ADD), rv, bv));
		LispVal body = makeIf(test, corrected, rv);
		return listToCons(List.of(new LispSymbol(LispNames.LET_STAR), bindings, body));
	}

	/**
	 * Expands a variadic numeric comparison (= &lt; &gt; &lt;= &gt;=) into nested binary
	 * comparisons so the compilers can reuse the binary comparison code. A two-argument
	 * comparison is left for the caller to compile directly. For {@code n >= 3} arguments
	 * the result is
	 * {@code (let* ((g1 a1) ... (gn an)) (and (op g1 g2) ... (op g(n-1) gn)))},
	 * evaluating each argument exactly once; a single argument expands to
	 * {@code (progn a1 t)}.
	 * @param cons the comparison expression
	 * @return the expanded expression
	 */
	public static LispVal expandComparison(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal op = parts.get(0);
		int n = parts.size() - 1;
		if (n <= 0) {
			throw new IllegalArgumentException(((LispSymbol) op).name() + " requires at least one argument");
		}
		if (n == 1) {
			return makeProgn(List.of(parts.get(1), LispTrue.INSTANCE));
		}
		List<LispVal> bindings = new java.util.ArrayList<>();
		List<LispVal> gsyms = new java.util.ArrayList<>();
		for (int i = 1; i <= n; i++) {
			LispSymbol g = new LispSymbol("__cmp" + i);
			gsyms.add(g);
			bindings.add(listToCons(List.of(g, parts.get(i))));
		}
		List<LispVal> andParts = new java.util.ArrayList<>();
		andParts.add(new LispSymbol(LispNames.AND));
		for (int i = 0; i + 1 < gsyms.size(); i++) {
			andParts.add(listToCons(List.of(op, gsyms.get(i), gsyms.get(i + 1))));
		}
		return listToCons(List.of(new LispSymbol(LispNames.LET_STAR), listToCons(bindings), listToCons(andParts)));
	}

	/**
	 * Expands a variadic associative reduction (min max gcd lcm) into a left fold of
	 * binary applications, e.g. {@code (min a b c)} becomes {@code (min (min a b) c)}. A
	 * two-argument call is left for the caller to compile directly. The identity cases
	 * follow Common Lisp: {@code (gcd)} is 0, {@code (lcm)} is 1, a single argument to
	 * {@code gcd}/{@code lcm} is its absolute value, and a single argument to
	 * {@code min}/{@code max} is itself.
	 * @param cons the reduction expression
	 * @return the expanded expression
	 */
	public static LispVal expandReduction(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal op = parts.get(0);
		String name = ((LispSymbol) op).name();
		int n = parts.size() - 1;
		boolean gcdLcm = LispNames.GCD.equals(name) || LispNames.LCM.equals(name);
		if (n == 0) {
			if (LispNames.GCD.equals(name)) {
				return new LispInteger(0);
			}
			if (LispNames.LCM.equals(name)) {
				return new LispInteger(1);
			}
			throw new IllegalArgumentException(name + " requires at least one argument");
		}
		if (n == 1) {
			return gcdLcm ? listToCons(List.of(new LispSymbol(LispNames.ABS), parts.get(1))) : parts.get(1);
		}
		LispVal acc = listToCons(List.of(op, parts.get(1), parts.get(2)));
		for (int i = 3; i <= n; i++) {
			acc = listToCons(List.of(op, acc, parts.get(i)));
		}
		return acc;
	}

	private static LispCons listToCons(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return (LispCons) result;
	}

}
