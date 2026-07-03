package am.ik.rontolisp;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Macro expander for cond, and, and or forms. Expands into primitive special forms (if,
 * let, progn) that are handled by the evaluator and compilers.
 */
public final class LispMacroExpander {

	private static final String COND_VAR = "__cond";

	private LispMacroExpander() {
	}

	/**
	 * Single-step expansion of a built-in macro call, used by
	 * {@code macroexpand-1}/{@code macroexpand}. The case list must stay in sync with
	 * {@code PackageRegistry.CL_MACROS} (the names {@code rontolisp:list-macros}
	 * reports); function-like operators expanded through this class (e.g. {@code 1+},
	 * {@code member}) are CL functions, not macros, and are deliberately absent.
	 * @param cons the form to expand
	 * @return the expansion, or {@code null} when the operator is not a built-in macro
	 */
	public static @Nullable LispVal expandBuiltinMacro(LispCons cons) {
		if (!(cons.car() instanceof LispSymbol sym)) {
			return null;
		}
		return switch (sym.name()) {
			case LispNames.COND -> expandCond(cons);
			case LispNames.CASE -> expandCase(cons);
			case LispNames.ECASE -> expandEcase(cons);
			case LispNames.CCASE -> expandCcase(cons);
			case LispNames.TYPECASE -> expandTypecase(cons);
			case LispNames.ETYPECASE -> expandEtypecase(cons);
			case LispNames.AND -> expandAnd(cons);
			case LispNames.OR -> expandOr(cons);
			case LispNames.WHEN -> expandWhen(cons);
			case LispNames.UNLESS -> expandUnless(cons);
			case LispNames.DOTIMES -> expandDotimes(cons);
			case LispNames.DOLIST -> expandDolist(cons);
			case LispNames.DO -> expandDo(cons);
			case LispNames.DO_STAR -> expandDoStar(cons);
			case LispNames.LET_STAR -> expandLetStar(cons);
			case LispNames.SETF -> expandSetf(cons);
			case LispNames.PUSH -> expandPush(cons);
			case LispNames.POP -> expandPop(cons);
			case LispNames.REMF -> expandRemf(cons);
			case LispNames.INCF -> expandIncf(cons);
			case LispNames.DECF -> expandDecf(cons);
			case LispNames.FORMAT -> expandFormat(cons);
			case LispNames.WITH_OPEN_FILE -> expandWithOpenFile(cons);
			case LispNames.PROG1 -> expandProg1(cons);
			case LispNames.PROG2 -> expandProg2(cons);
			case LispNames.PSETQ -> expandPsetq(cons);
			case LispNames.ERROR -> expandError(cons);
			case LispNames.TIME -> expandTime(cons);
			case LispNames.LOOP -> expandLoop(cons);
			default -> null;
		};
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
	 * Expands (case keyform clause...) into a let/cond expression. The keyform is
	 * evaluated once and bound to a temporary, then each clause's keys are compared with
	 * {@code eq}.
	 *
	 * <pre>
	 * (case x
	 *   (a 1)              ; single key
	 *   ((b c) 2)          ; list of keys (matches b or c)
	 *   (otherwise 3))     ; default clause (also t)
	 * ->
	 * (let ((__case x))
	 *   (cond ((eq __case 'a) 1)
	 *         ((or (eq __case 'b) (eq __case 'c)) 2)
	 *         (t 3)))
	 * </pre>
	 *
	 * The clause keys are object designators and are not evaluated. A clause key of
	 * {@code t} or {@code otherwise} marks the default clause. A list key matches when
	 * the keyform is {@code eql} to any element; any other atom is a single key. (Unlike
	 * Common Lisp, a {@code nil} key is treated as a single key matching {@code nil}, not
	 * as an empty key list.) A clause with no body returns nil.
	 * @param cons the case expression
	 * @return the expanded expression
	 */
	public static LispVal expandCase(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException("case expects a keyform");
		}
		LispVal keyform = parts.get(1);
		List<LispVal> clauses = parts.subList(2, parts.size());
		LispSymbol keyVar = new LispSymbol(CASE_VAR);
		List<LispVal> condParts = new java.util.ArrayList<>();
		condParts.add(new LispSymbol(LispNames.COND));
		for (LispVal clauseVal : clauses) {
			if (!(clauseVal instanceof LispCons clause)) {
				throw new IllegalArgumentException("case clause must be a list, got: " + clauseVal.print());
			}
			List<LispVal> clauseParts = clause.toList();
			LispVal keys = clauseParts.get(0);
			List<LispVal> body = clauseParts.subList(1, clauseParts.size());
			LispVal test;
			if (isCaseDefaultKey(keys)) {
				test = LispTrue.INSTANCE;
			}
			else if (keys instanceof LispCons keyList) {
				List<LispVal> orParts = new java.util.ArrayList<>();
				orParts.add(new LispSymbol(LispNames.OR));
				for (LispVal k : keyList.toList()) {
					orParts.add(makeCaseEq(keyVar, k));
				}
				test = listToCons(orParts);
			}
			else {
				test = makeCaseEq(keyVar, keys);
			}
			List<LispVal> condClause = new java.util.ArrayList<>();
			condClause.add(test);
			if (body.isEmpty()) {
				condClause.add(LispNil.INSTANCE);
			}
			else {
				condClause.addAll(body);
			}
			condParts.add(listToCons(condClause));
		}
		return makeLet(CASE_VAR, keyform, listToCons(condParts));
	}

	private static final String CASE_VAR = "__case";

	private static final String ECASE_VAR = "__ecase";

	/**
	 * Expands {@code (ecase keyform (keys body...) ...)} into the same {@code let}/cond
	 * as {@code case}, but without any default clause: {@code t} and {@code otherwise}
	 * are treated as ordinary keys, and a final {@code (error ...)} clause is appended so
	 * an unmatched key signals an error (Common Lisp's exhaustive {@code ecase}).
	 * @param cons the ecase expression
	 * @return the expanded expression
	 */
	public static LispVal expandEcase(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException("ecase expects a keyform");
		}
		LispVal keyform = parts.get(1);
		List<LispVal> clauses = parts.subList(2, parts.size());
		LispSymbol keyVar = new LispSymbol(ECASE_VAR);
		List<LispVal> condParts = new java.util.ArrayList<>();
		condParts.add(new LispSymbol(LispNames.COND));
		for (LispVal clauseVal : clauses) {
			if (!(clauseVal instanceof LispCons clause)) {
				throw new IllegalArgumentException("ecase clause must be a list, got: " + clauseVal.print());
			}
			List<LispVal> clauseParts = clause.toList();
			LispVal keys = clauseParts.get(0);
			List<LispVal> body = clauseParts.subList(1, clauseParts.size());
			LispVal test;
			// ecase does not treat t/otherwise specially: they are ordinary keys.
			if (keys instanceof LispCons keyList) {
				List<LispVal> orParts = new java.util.ArrayList<>();
				orParts.add(new LispSymbol(LispNames.OR));
				for (LispVal k : keyList.toList()) {
					orParts.add(makeCaseEq(keyVar, k));
				}
				test = listToCons(orParts);
			}
			else {
				test = makeCaseEq(keyVar, keys);
			}
			List<LispVal> condClause = new java.util.ArrayList<>();
			condClause.add(test);
			if (body.isEmpty()) {
				condClause.add(LispNil.INSTANCE);
			}
			else {
				condClause.addAll(body);
			}
			condParts.add(listToCons(condClause));
		}
		condParts.add(makeExhaustiveErrorClause(keyVar, "ECASE"));
		return makeLet(ECASE_VAR, keyform, listToCons(condParts));
	}

	/**
	 * Expands {@code (ccase keyform ...)}. Without a restart (store-value) mechanism,
	 * {@code ccase} behaves exactly like {@code ecase}: an unmatched key signals an
	 * error.
	 * @param cons the ccase expression
	 * @return the expanded expression
	 */
	public static LispVal expandCcase(LispCons cons) {
		return expandEcase(cons);
	}

	/**
	 * Builds the {@code (t (error "..."))} fall-through clause shared by {@code ecase}
	 * and {@code etypecase}: it reports the unmatched value bound to {@code var}.
	 */
	private static LispVal makeExhaustiveErrorClause(LispSymbol var, String label) {
		LispVal errorCall = listToCons(
				List.of(new LispSymbol(LispNames.ERROR), new LispString(label + ": no clause matches ~s"), var));
		return listToCons(List.of(LispTrue.INSTANCE, errorCall));
	}

	private static boolean isCaseDefaultKey(LispVal keys) {
		return keys instanceof LispTrue || (keys instanceof LispSymbol sym && LispNames.OTHERWISE.equals(sym.name()));
	}

	private static LispVal makeCaseEq(LispVal var, LispVal key) {
		LispVal quoted = listToCons(List.of(new LispSymbol(LispNames.QUOTE), key));
		// case keys are compared with eql (the Common Lisp default), so numeric keys
		// match
		// by value.
		return listToCons(List.of(new LispSymbol(LispNames.EQL), var, quoted));
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
		return makeBlock(listToCons(letParts));
	}

	private static final String DOTIMES_LIMIT_VAR = "__dotimes_limit";

	/**
	 * Expands (prog1 first body...) into a let that evaluates first, then the body forms,
	 * and finally returns the saved value of first.
	 *
	 * <pre>
	 * (prog1 first body...) ->
	 * (let ((__prog1_result first))
	 *   body...
	 *   __prog1_result)
	 * </pre>
	 *
	 * The first form is evaluated once and saved before the body runs, so its value is
	 * returned even if the body mutates whatever first referred to. With no body forms
	 * the expansion simply returns the value of first.
	 * @param cons the prog1 expression
	 * @return the expanded expression
	 */
	public static LispVal expandProg1(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal first = parts.get(1);
		List<LispVal> body = parts.subList(2, parts.size());
		LispSymbol resultSym = new LispSymbol(PROG1_RESULT_VAR);
		// ((__prog1_result first))
		LispVal binding = listToCons(List.of(resultSym, first));
		LispVal bindings = new LispCons(binding, LispNil.INSTANCE);
		// (let (bindings) body... __prog1_result)
		List<LispVal> letParts = new java.util.ArrayList<>();
		letParts.add(new LispSymbol(LispNames.LET));
		letParts.add(bindings);
		letParts.addAll(body);
		letParts.add(resultSym);
		return listToCons(letParts);
	}

	private static final String PROG1_RESULT_VAR = "__prog1_result";

	private static final String TIME_START_VAR = "__time_start";

	private static final String TIME_RESULT_VAR = "__time_result";

	/**
	 * Expands (time form) into a let/progn that records the real time before and after
	 * evaluating {@code form}, prints the elapsed milliseconds to standard output, and
	 * returns the form's value.
	 *
	 * <pre>
	 * (time form) ->
	 * (let ((__time_start (get-internal-real-time)))
	 *   (let ((__time_result form))
	 *     (progn
	 *       (princ "; Elapsed real time: ")
	 *       (princ (- (get-internal-real-time) __time_start))
	 *       (princ " ms")
	 *       (terpri)
	 *       __time_result)))
	 * </pre>
	 *
	 * The elapsed value is an integer of milliseconds on the interpreter and JVM backends
	 * and a float of milliseconds on WASM (where {@code get-internal-real-time} returns a
	 * float).
	 * @param cons the time expression
	 * @return the expanded expression
	 */
	public static LispVal expandTime(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal form = parts.get(1);
		LispSymbol startSym = new LispSymbol(TIME_START_VAR);
		LispSymbol resultSym = new LispSymbol(TIME_RESULT_VAR);
		// (- (get-internal-real-time) __time_start)
		LispVal nowCall = listToCons(List.of(new LispSymbol(LispNames.GET_INTERNAL_REAL_TIME)));
		LispVal elapsed = listToCons(List.of(new LispSymbol(LispNames.SUB), nowCall, startSym));
		// (progn (princ ...) (princ elapsed) (princ " ms") (terpri) __time_result)
		LispVal report = makeProgn(
				List.of(listToCons(List.of(new LispSymbol(LispNames.PRINC), new LispString("; Elapsed real time: "))),
						listToCons(List.of(new LispSymbol(LispNames.PRINC), elapsed)),
						listToCons(List.of(new LispSymbol(LispNames.PRINC), new LispString(" ms"))),
						listToCons(List.of(new LispSymbol(LispNames.TERPRI))), resultSym));
		// (let ((__time_result form)) report)
		LispVal inner = makeLet(TIME_RESULT_VAR, form, report);
		// (let ((__time_start (get-internal-real-time))) inner)
		LispVal startCall = listToCons(List.of(new LispSymbol(LispNames.GET_INTERNAL_REAL_TIME)));
		return makeLet(TIME_START_VAR, startCall, inner);
	}

	/**
	 * Expands (do ((var init [step])...) (end-test result...) body...) into a let/while
	 * loop wrapped in a {@code %block} so that {@code return} exits it.
	 *
	 * <pre>
	 * (do ((v1 i1 s1) (v2 i2)) (end r...) body...) ->
	 * (%block
	 *   (let ((v1 i1) (v2 i2))
	 *     (while (not end)
	 *       body...
	 *       (setq v1 s1))       ; only stepped vars are reassigned (in parallel)
	 *     (progn r...)))
	 * </pre>
	 *
	 * Steps are evaluated in parallel: when more than one variable has a step form, all
	 * step forms are first evaluated into temporaries and only then assigned, matching
	 * Common Lisp. A variable without a step keeps its value (mutated only by the body).
	 * @param cons the do expression
	 * @return the expanded expression
	 */
	public static LispVal expandDo(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal bindingsForm = parts.get(1);
		LispVal endClause = parts.get(2);
		List<LispVal> body = parts.subList(3, parts.size());
		List<LispVal> letBindings = new java.util.ArrayList<>();
		// Stepped variables, in source order: each entry is {var, step-form}.
		List<LispVal[]> steps = new java.util.ArrayList<>();
		if (bindingsForm instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				if (binding instanceof LispSymbol bare) {
					// (do (x) ...) -- a bare symbol binds x to nil with no step.
					letBindings.add(listToCons(List.of(bare, LispNil.INSTANCE)));
				}
				else {
					List<LispVal> spec = ((LispCons) binding).toList();
					LispVal var = spec.get(0);
					LispVal init = spec.size() > 1 ? spec.get(1) : LispNil.INSTANCE;
					letBindings.add(listToCons(List.of(var, init)));
					if (spec.size() > 2) {
						steps.add(new LispVal[] { var, spec.get(2) });
					}
				}
			}
		}
		// (end-test result...): loop while the test is false, then evaluate the results.
		LispVal endTest = LispNil.INSTANCE;
		List<LispVal> resultForms = List.of();
		if (endClause instanceof LispCons endCons) {
			List<LispVal> endParts = endCons.toList();
			endTest = endParts.get(0);
			resultForms = endParts.subList(1, endParts.size());
		}
		// (while (not end-test) body... step)
		List<LispVal> whileParts = new java.util.ArrayList<>();
		whileParts.add(new LispSymbol(LispNames.WHILE));
		whileParts.add(makeNot(endTest));
		whileParts.addAll(body);
		whileParts.addAll(makeStepForms(steps));
		LispVal whileExpr = listToCons(whileParts);
		// Result: nil with no result forms, the form itself for one, else a progn.
		LispVal resultExpr;
		if (resultForms.isEmpty()) {
			resultExpr = LispNil.INSTANCE;
		}
		else if (resultForms.size() == 1) {
			resultExpr = resultForms.get(0);
		}
		else {
			resultExpr = makeProgn(resultForms);
		}
		LispVal bindings = listToCons(letBindings);
		LispVal letExpr = listToCons(List.of(new LispSymbol(LispNames.LET), bindings, whileExpr, resultExpr));
		return makeBlock(letExpr);

	}

	/**
	 * Expands (do* ((var init [step])...) (end-test result...) body...) into a
	 * {@code let*} plus {@code while} loop wrapped in a {@code %block}. Unlike
	 * {@code do}, bindings are established sequentially (each init form sees the
	 * variables bound before it) and step forms are assigned sequentially each iteration
	 * (each step sees the updates already made this iteration), matching Common Lisp
	 * {@code do*} semantics.
	 * @param cons the do* expression
	 * @return the expanded expression
	 */
	public static LispVal expandDoStar(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal bindingsForm = parts.get(1);
		LispVal endClause = parts.get(2);
		List<LispVal> body = parts.subList(3, parts.size());
		List<LispVal> letBindings = new java.util.ArrayList<>();
		// Stepped variables, in source order: each entry is {var, step-form}.
		List<LispVal[]> steps = new java.util.ArrayList<>();
		if (bindingsForm instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				if (binding instanceof LispSymbol bare) {
					letBindings.add(listToCons(List.of(bare, LispNil.INSTANCE)));
				}
				else {
					List<LispVal> spec = ((LispCons) binding).toList();
					LispVal var = spec.get(0);
					LispVal init = spec.size() > 1 ? spec.get(1) : LispNil.INSTANCE;
					letBindings.add(listToCons(List.of(var, init)));
					if (spec.size() > 2) {
						steps.add(new LispVal[] { var, spec.get(2) });
					}
				}
			}
		}
		LispVal endTest = LispNil.INSTANCE;
		List<LispVal> resultForms = List.of();
		if (endClause instanceof LispCons endCons) {
			List<LispVal> endParts = endCons.toList();
			endTest = endParts.get(0);
			resultForms = endParts.subList(1, endParts.size());
		}
		// (while (not end-test) body... (setq v1 s1) (setq v2 s2) ...) -- sequential
		// steps.
		List<LispVal> whileParts = new java.util.ArrayList<>();
		whileParts.add(new LispSymbol(LispNames.WHILE));
		whileParts.add(makeNot(endTest));
		whileParts.addAll(body);
		for (LispVal[] s : steps) {
			whileParts.add(listToCons(List.of(new LispSymbol(LispNames.SETQ), s[0], s[1])));
		}
		LispVal whileExpr = listToCons(whileParts);
		LispVal resultExpr;
		if (resultForms.isEmpty()) {
			resultExpr = LispNil.INSTANCE;
		}
		else if (resultForms.size() == 1) {
			resultExpr = resultForms.get(0);
		}
		else {
			resultExpr = makeProgn(resultForms);
		}
		LispVal bindings = listToCons(letBindings);
		LispVal letExpr = listToCons(List.of(new LispSymbol(LispNames.LET_STAR), bindings, whileExpr, resultExpr));
		return makeBlock(letExpr);
	}

	/**
	 * The surface keywords recognized by the {@code loop} macro, matched by symbol name
	 * (case-insensitively, ignoring package) only in clause position. The same name used
	 * as an ordinary value in a clause expression (e.g.
	 * {@code (loop repeat (length to) ...)}) is not treated as a keyword because it never
	 * appears where a keyword is expected.
	 */
	private static final java.util.Set<String> LOOP_KEYWORDS = java.util.Set.of("for", "as", "from", "upfrom",
			"downfrom", "to", "upto", "below", "downto", "above", "by", "in", "on", "=", "then", "across", "repeat",
			"while", "until", "do", "doing", "collect", "collecting", "append", "appending", "nconc", "nconcing", "sum",
			"summing", "count", "counting", "maximize", "maximizing", "minimize", "minimizing", "when", "if", "unless",
			"else", "end", "finally", "initially", "return", "with", "and", "into", "thereis", "always", "never",
			// Recognized so their use fails with a clear "unsupported" error instead of
			// being misparsed as a simple-loop body or a missing for sub-clause.
			"named", "being");

	/**
	 * Expands the {@code loop} macro into the existing core ({@code %block}/{@code let*}/
	 * {@code while}/{@code setq}/{@code return} plus list/number accumulators) so that
	 * all three backends compile it without a dedicated form.
	 *
	 * <p>
	 * Two shapes are recognized. A <em>simple loop</em> — every top-level subform is a
	 * compound form, e.g. {@code (loop (print x) (when done (return)))} — repeats its
	 * body forever until a {@code return}. An <em>extended loop</em> begins with a clause
	 * keyword and supports this bounded subset of the ANSI grammar:
	 *
	 * <ul>
	 * <li>numeric stepping:
	 * {@code for v from LO [to|upto|below|downto|above HI] [by STEP]} (and
	 * {@code upfrom}/{@code downfrom}; a limit keyword with no {@code from} defaults the
	 * start to 0),</li>
	 * <li>list stepping: {@code for v in LIST [by FN]} and {@code for v on LIST [by FN]}
	 * ({@code v} may be a destructuring pattern),</li>
	 * <li>general stepping: {@code for v = INIT [then STEP]} ({@code v} may be a
	 * destructuring pattern),</li>
	 * <li>sequence stepping: {@code for v across SEQ} (a string's characters or a
	 * vector's elements),</li>
	 * <li>parallel {@code for} groups: {@code for a ... and b ...} binds and steps the
	 * group in parallel (each init/step sees the previous iteration's values),</li>
	 * <li>local variables: {@code with v [= INIT]} ({@code v} may be a destructuring
	 * pattern; {@code and}-joined {@code with} bindings are parallel),</li>
	 * <li>accumulation: {@code collect}/{@code append}/{@code nconc}/{@code sum}/
	 * {@code count}/{@code maximize}/{@code minimize}, each with an optional
	 * {@code into v},</li>
	 * <li>termination tests: {@code thereis}/{@code always}/{@code never} (short-circuit
	 * like {@code return}, skipping {@code finally}; {@code always}/{@code never} yield
	 * {@code t} on normal completion),</li>
	 * <li>control: {@code while}/{@code until} (honoring their textual position),
	 * {@code repeat N}, {@code do FORM...}, {@code return EXPR}, {@code (loop-finish)}
	 * inside body forms (jumps to the {@code finally}+result epilogue),
	 * {@code initially FORM...}, {@code finally FORM...}, and the conditionals
	 * {@code when}/{@code if}/{@code unless} with an optional {@code else} and
	 * {@code end} (selectable clauses chainable with {@code and}, supporting the
	 * anaphoric {@code it}).</li>
	 * </ul>
	 *
	 * <p>
	 * Limitations: {@code being} (hash/package iteration), {@code named}/
	 * {@code return-from}, and lambda-list keywords inside destructuring patterns.
	 * {@code (loop-finish)} must appear in statement position and not inside a nested
	 * iteration form. Accumulation clauses without {@code into} must all be of the same
	 * kind (collecting clauses build the list in order; the result list is produced with
	 * {@code nreverse}) and cannot be combined with {@code thereis}/{@code always}/
	 * {@code never}.
	 * @param cons the loop expression
	 * @return the expanded expression
	 */
	public static LispVal expandLoop(LispCons cons) {
		List<LispVal> all = cons.toList();
		List<LispVal> toks = all.subList(1, all.size());
		// (loop) -> an empty endless loop, exited only by a non-local return.
		if (toks.isEmpty()) {
			return makeBlock(listToCons(List.of(new LispSymbol(LispNames.WHILE), LispTrue.INSTANCE, LispNil.INSTANCE)));
		}
		// Simple loop: the body is a sequence of forms repeated forever.
		if (loopKeyword(toks.get(0)) == null) {
			List<LispVal> whileParts = new java.util.ArrayList<>();
			whileParts.add(new LispSymbol(LispNames.WHILE));
			whileParts.add(LispTrue.INSTANCE);
			whileParts.addAll(toks);
			return makeBlock(listToCons(whileParts));
		}
		return new LoopExpander(toks).build();
	}

	/** Returns the lowercased loop keyword a token denotes, or null if it is not one. */
	private static @Nullable String loopKeyword(LispVal v) {
		if (v instanceof LispSymbol s) {
			String n = s.name().toLowerCase(java.util.Locale.ROOT);
			if (LOOP_KEYWORDS.contains(n)) {
				return n;
			}
		}
		return null;
	}

	/**
	 * Parses an extended {@code loop} clause sequence into the pieces of a {@code let*}/
	 * {@code while} expansion: binding/init forms, per-iteration variable assignments,
	 * the loop body, step forms, termination tests, {@code initially}/{@code finally}
	 * forms and the accumulation result. See {@link LispMacroExpander#expandLoop} for the
	 * grammar.
	 */
	private static final class LoopExpander {

		private final List<LispVal> toks;

		private int pos;

		private int gen;

		private final List<LispVal> bindings = new java.util.ArrayList<>();

		private final List<LispVal> initially = new java.util.ArrayList<>();

		private final List<LispVal> mainBody = new java.util.ArrayList<>();

		private final List<LispVal> steps = new java.util.ArrayList<>();

		private final List<LispVal> endTests = new java.util.ArrayList<>();

		// The termination tests contributed by driver clauses (for/repeat) only, in
		// clause order. A later driver's steps are guarded by the earlier drivers'
		// tests so that, as in CL, stepping stops at the first exhausted driver
		// (e.g. `for x in xs for a = ... then (f a x)` must not evaluate the step
		// form once xs runs out).
		private final List<LispVal> driverEndTests = new java.util.ArrayList<>();

		private final List<LispVal> finallyForms = new java.util.ArrayList<>();

		private final List<LispVal> postLoop = new java.util.ArrayList<>();

		// The implicit (un-named) accumulator: its variable, the init form establishing
		// it,
		// and whether the collected list must be reversed to restore source order.
		private @Nullable LispSymbol implicitAcc;

		private @Nullable LispVal implicitInit;

		private boolean implicitReverse;

		// Set by always/never (TRUE: normal completion returns t) or thereis (FALSE:
		// normal completion returns nil). Incompatible with implicit accumulation.
		private @Nullable Boolean terminationT;

		/**
		 * Placeholder statement emitted where a positional {@code while}/{@code until}
		 * (or a {@code (loop-finish)} call) exits to the loop epilogue; replaced with the
		 * actual finally+result exit once the whole loop has been parsed.
		 */
		private static final LispSymbol EPILOGUE_MARKER = new LispSymbol("__loop_epilogue_exit");

		/**
		 * Form heads the epilogue-exit substitution must not descend into: quoted data,
		 * function values, and forms that establish their own {@code %block} boundary
		 * (the epilogue exit is a {@code return} to the nearest block, so it is only
		 * valid directly inside this loop's block).
		 */
		private static final java.util.Set<String> EXIT_SKIP_HEADS = java.util.Set.of(LispNames.QUOTE,
				LispNames.FUNCTION, LispNames.LAMBDA, LispNames.DEFUN, LispNames.LOOP, LispNames.DO, LispNames.DO_STAR,
				LispNames.DOLIST, LispNames.DOTIMES, LispNames.BLOCK_INTERNAL);

		/**
		 * Form heads the anaphoric-{@code it} substitution must not descend into: quoted
		 * data and nested loops (whose own conditionals define their own {@code it}).
		 */
		private static final java.util.Set<String> IT_SKIP_HEADS = java.util.Set.of(LispNames.QUOTE, LispNames.FUNCTION,
				LispNames.LOOP);

		LoopExpander(List<LispVal> toks) {
			this.toks = toks;
		}

		LispVal build() {
			parse();
			LispVal epilogue = epilogueExit();
			substituteEpilogueExits(initially, epilogue);
			substituteEpilogueExits(mainBody, epilogue);
			substituteEpilogueExits(steps, epilogue);
			LispVal whileCond = endTests.isEmpty() ? LispTrue.INSTANCE : makeNot(orOf(endTests));
			List<LispVal> whileParts = new java.util.ArrayList<>();
			whileParts.add(new LispSymbol(LispNames.WHILE));
			whileParts.add(whileCond);
			whileParts.addAll(mainBody);
			whileParts.addAll(steps);
			if (whileParts.size() == 2) {
				// while needs a (possibly nil) body form.
				whileParts.add(LispNil.INSTANCE);
			}
			List<LispVal> letBody = new java.util.ArrayList<>();
			letBody.addAll(initially);
			letBody.add(listToCons(whileParts));
			letBody.addAll(postLoop);
			letBody.addAll(finallyForms);
			letBody.add(resultExpr());
			List<LispVal> letParts = new java.util.ArrayList<>();
			letParts.add(new LispSymbol(LispNames.LET_STAR));
			letParts.add(bindings.isEmpty() ? LispNil.INSTANCE : listToCons(bindings));
			letParts.addAll(letBody);
			return makeBlock(listToCons(letParts));
		}

		private void parse() {
			while (pos < toks.size()) {
				String kw = loopKeyword(toks.get(pos));
				if (kw == null) {
					throw new IllegalArgumentException(
							"loop: expected a clause keyword, got: " + toks.get(pos).print());
				}
				pos++;
				parseClause(kw);
			}
		}

		private void parseClause(String kw) {
			switch (kw) {
				case "for", "as" -> parseFor();
				case "with" -> parseWith();
				case "repeat" -> parseRepeat();
				case "while" -> addTerminationTest(nextForm(), false);
				case "until" -> addTerminationTest(nextForm(), true);
				case "thereis" -> parseThereis();
				case "always" -> parseAlwaysNever(false);
				case "never" -> parseAlwaysNever(true);
				case "initially" -> initially.addAll(readForms());
				case "finally" -> finallyForms.addAll(readForms());
				case "do", "doing" -> mainBody.addAll(readForms());
				case "return" -> mainBody.add(makeReturn(nextForm()));
				case "when", "if" -> mainBody.add(parseConditional(false));
				case "unless" -> mainBody.add(parseConditional(true));
				case "collect", "collecting", "append", "appending", "nconc", "nconcing", "sum", "summing", "count",
						"counting", "maximize", "maximizing", "minimize", "minimizing" ->
					mainBody.add(parseAccumulation(kw));
				default -> throw new UnsupportedOperationException("loop: unsupported clause: " + kw);
			}
		}

		/**
		 * One {@code for} clause's binding/init entry. {@code user} marks a user-visible
		 * variable (as opposed to an internal cursor/limit gensym), whose binding is
		 * deferred behind temporaries when the clause is part of a parallel {@code and}
		 * group.
		 */
		private record ForBinding(LispSymbol var, LispVal init, boolean user) {
		}

		/**
		 * One {@code for} clause's contributions, kept separate so {@code and}-joined
		 * clauses can bind and step in parallel.
		 */
		private static final class ForPiece {

			final List<ForBinding> binds = new java.util.ArrayList<>();

			// {stepped variable, step expression} pairs (see makeStepForms).
			final List<LispVal[]> steps = new java.util.ArrayList<>();

			// Assignments syncing an iteration variable with its stepped cursor (e.g.
			// the `in` variable from its list cursor). Run after the clause's steps —
			// and, in an `and` group, after the whole group's parallel steps, so the
			// other clauses' step forms still see the previous element.
			final List<LispVal> postSteps = new java.util.ArrayList<>();

			final List<LispVal> endTests = new java.util.ArrayList<>();

		}

		private void parseFor() {
			List<ForPiece> group = new java.util.ArrayList<>();
			while (true) {
				group.add(parseForPiece());
				if ("and".equals(peekKeyword())) {
					pos++;
					// Tolerate a redundant `for`/`as` after `and`.
					String k = peekKeyword();
					if ("for".equals(k) || "as".equals(k)) {
						pos++;
					}
					continue;
				}
				break;
			}
			flushForGroup(group);
		}

		private ForPiece parseForPiece() {
			LispVal var = nextForm();
			if (!(var instanceof LispSymbol) && !(var instanceof LispCons)) {
				throw new IllegalArgumentException(
						"loop: for expects a variable or destructuring pattern, got: " + var.print());
			}
			String sub = peekKeyword();
			if (sub == null) {
				throw new IllegalArgumentException("loop: incomplete for clause for " + var.print());
			}
			ForPiece piece = new ForPiece();
			switch (sub) {
				case "in", "on" -> parseForList(piece, var, sub);
				case "=" -> parseForEquals(piece, var);
				case "from", "upfrom", "downfrom", "to", "upto", "below", "downto", "above", "by" ->
					parseForNumeric(piece, requireSymbol(var, "numeric stepping"));
				case "across" -> parseForAcross(piece, requireSymbol(var, "across"));
				default -> throw new UnsupportedOperationException("loop: unsupported for clause: " + sub);
			}
			return piece;
		}

		private static LispSymbol requireSymbol(LispVal var, String what) {
			if (var instanceof LispSymbol s) {
				return s;
			}
			throw new IllegalArgumentException("loop: " + what + " does not support destructuring: " + var.print());
		}

		/**
		 * Merges a group of {@code and}-joined {@code for} clauses. A single clause binds
		 * and steps sequentially as before. A parallel group evaluates every init before
		 * binding any user variable (so a later init sees the outer bindings) and steps
		 * all variables against the previous iteration's values via temporaries.
		 */
		private void flushForGroup(List<ForPiece> group) {
			boolean parallel = group.size() > 1;
			List<ForBinding> deferred = new java.util.ArrayList<>();
			List<LispVal[]> groupSteps = new java.util.ArrayList<>();
			for (ForPiece piece : group) {
				for (ForBinding b : piece.binds) {
					if (parallel && b.user()) {
						if (b.init() instanceof LispNil) {
							deferred.add(b);
						}
						else {
							LispSymbol temp = gensym("tmp");
							bindings.add(pair(temp, b.init()));
							deferred.add(new ForBinding(b.var(), temp, true));
						}
					}
					else {
						bindings.add(pair(b.var(), b.init()));
					}
				}
				endTests.addAll(piece.endTests);
				groupSteps.addAll(piece.steps);
			}
			for (ForBinding b : deferred) {
				bindings.add(pair(b.var(), b.init()));
			}
			// As in CL, stepping stops at the first exhausted driver: guard this
			// group's steps with the earlier drivers' termination tests (exiting
			// through the epilogue, exactly like the loop head test would).
			if (!steps.isEmpty() && !driverEndTests.isEmpty()) {
				steps.add(makeIf(orOf(driverEndTests), EPILOGUE_MARKER, LispNil.INSTANCE));
			}
			if (parallel) {
				// All steps compute against the previous iteration's values; the
				// cursor-var syncs run once every cursor has stepped.
				steps.addAll(makeStepForms(groupSteps));
				for (ForPiece piece : group) {
					steps.addAll(piece.postSteps);
				}
			}
			else {
				// Sequential (do*-style): each clause steps fully -- cursor then its
				// variable -- before the next clause's step forms run.
				for (ForPiece piece : group) {
					for (LispVal[] s : piece.steps) {
						steps.add(setq((LispSymbol) s[0], s[1]));
					}
					steps.addAll(piece.postSteps);
				}
			}
			for (ForPiece piece : group) {
				driverEndTests.addAll(piece.endTests);
			}
		}

		private void parseForNumeric(ForPiece piece, LispSymbol var) {
			String k = peekKeyword();
			boolean down = false;
			LispVal from;
			if ("from".equals(k) || "upfrom".equals(k) || "downfrom".equals(k)) {
				down = "downfrom".equals(k);
				pos++;
				from = nextForm();
			}
			else {
				from = new LispInteger(0);
			}
			piece.binds.add(new ForBinding(var, from, true));
			String limitKw = null;
			LispSymbol limitVar = null;
			String lk = peekKeyword();
			if ("to".equals(lk) || "upto".equals(lk) || "below".equals(lk) || "downto".equals(lk)
					|| "above".equals(lk)) {
				limitKw = lk;
				pos++;
				limitVar = gensym("limit");
				piece.binds.add(new ForBinding(limitVar, nextForm(), false));
				if ("downto".equals(lk) || "above".equals(lk)) {
					down = true;
				}
			}
			LispVal by = new LispInteger(1);
			if ("by".equals(peekKeyword())) {
				pos++;
				by = nextForm();
			}
			LispSymbol byVar = gensym("by");
			piece.binds.add(new ForBinding(byVar, by, false));
			piece.steps.add(new LispVal[] { var, call(down ? LispNames.SUB : LispNames.ADD, var, byVar) });
			if (limitVar != null) {
				boolean exclusive = "below".equals(limitKw) || "above".equals(limitKw);
				String cmp;
				if (down) {
					cmp = exclusive ? LispNames.LE : LispNames.LT;
				}
				else {
					cmp = exclusive ? LispNames.GE : LispNames.GT;
				}
				piece.endTests.add(call(cmp, var, limitVar));
			}
		}

		private void parseForList(ForPiece piece, LispVal pattern, String sub) {
			pos++; // consume in/on
			LispVal listForm = nextForm();
			LispVal byFn = null;
			if ("by".equals(peekKeyword())) {
				pos++;
				byFn = normalizeFunctionDesignator(nextForm());
			}
			if ("on".equals(sub)) {
				// The variable is bound to successive tails of the list.
				if (pattern instanceof LispSymbol var) {
					piece.binds.add(new ForBinding(var, listForm, true));
					piece.endTests.add(makeNot(call(LispNames.CONSP, var)));
					piece.steps.add(new LispVal[] { var, stepCdr(var, byFn) });
				}
				else {
					LispSymbol tail = gensym("on");
					piece.binds.add(new ForBinding(tail, listForm, false));
					piece.endTests.add(makeNot(call(LispNames.CONSP, tail)));
					destructureInto(piece, pattern, tail);
					piece.steps.add(new LispVal[] { tail, stepCdr(tail, byFn) });
				}
			}
			else {
				// The variable holds the first element already at binding time (so a
				// later sequential clause's init can reference it, as in CL) and is
				// re-synced from the cursor after each step.
				LispSymbol cursor = gensym("list");
				piece.binds.add(new ForBinding(cursor, listForm, false));
				piece.endTests.add(makeNot(call(LispNames.CONSP, cursor)));
				destructureInto(piece, pattern, call(LispNames.CAR, cursor));
				piece.steps.add(new LispVal[] { cursor, stepCdr(cursor, byFn) });
			}
		}

		private void parseForAcross(ForPiece piece, LispSymbol var) {
			pos++; // consume across
			LispVal seqForm = nextForm();
			// `across` walks a random-access sequence (a string's characters or a
			// vector's elements) via an index cursor; the element accessor is chosen at
			// runtime because the sequence type is unknown at expansion time. The
			// element read is bounds-guarded because the variable syncs at binding time
			// and after each step, where the index may sit at the (empty or exhausted)
			// sequence's length.
			LispSymbol seq = gensym("seq");
			LispSymbol idx = gensym("idx");
			piece.binds.add(new ForBinding(seq, seqForm, false));
			piece.binds.add(new ForBinding(idx, new LispInteger(0), false));
			piece.binds.add(new ForBinding(var, guardedElt(seq, idx), true));
			piece.endTests.add(call(LispNames.GE, idx, call(LispNames.LENGTH, seq)));
			piece.steps.add(new LispVal[] { idx, call(LispNames.ADD, idx, new LispInteger(1)) });
			piece.postSteps.add(setq(var, guardedElt(seq, idx)));
		}

		/**
		 * A bounds-checked element read: the sequence's element at the index, or nil when
		 * the index is past the end.
		 */
		private static LispVal guardedElt(LispSymbol seq, LispSymbol idx) {
			LispVal elt = makeIf(call(LispNames.STRINGP, seq), call(LispNames.CHAR, seq, idx),
					call(LispNames.AREF, seq, idx));
			return makeIf(call(LispNames.LT, idx, call(LispNames.LENGTH, seq)), elt, LispNil.INSTANCE);
		}

		private LispVal stepCdr(LispSymbol cursor, @Nullable LispVal byFn) {
			if (byFn == null) {
				return call(LispNames.CDR, cursor);
			}
			return listToCons(List.of(new LispSymbol(LispNames.FUNCALL), byFn, cursor));
		}

		private void parseForEquals(ForPiece piece, LispVal pattern) {
			pos++; // consume =
			LispVal init = nextForm();
			LispVal then = null;
			if ("then".equals(peekKeyword())) {
				pos++;
				then = nextForm();
			}
			// With `then`, step to the then-form; without it, re-evaluate the init each
			// iteration (the step runs at the end of the iteration, so the next pass sees
			// it).
			if (pattern instanceof LispSymbol var) {
				piece.binds.add(new ForBinding(var, init, true));
				piece.steps.add(new LispVal[] { var, then != null ? then : init });
			}
			else {
				LispSymbol whole = gensym("d");
				piece.binds.add(new ForBinding(whole, init, false));
				destructureInto(piece, pattern, whole);
				piece.steps.add(new LispVal[] { whole, then != null ? then : init });
			}
		}

		/**
		 * Binds every symbol of a destructuring pattern (or a single variable) to its
		 * accessor over the source expression, and re-destructures after each step.
		 */
		private void destructureInto(ForPiece piece, LispVal pattern, LispVal source) {
			List<LispVal[]> parts = new java.util.ArrayList<>();
			destructure(pattern, source, parts);
			for (LispVal[] p : parts) {
				piece.binds.add(new ForBinding((LispSymbol) p[0], p[1], true));
				piece.postSteps.add(setq((LispSymbol) p[0], p[1]));
			}
		}

		/**
		 * Collects {@code {var, accessor}} pairs binding each symbol of a destructuring
		 * pattern to the matching car/cdr chain over the source expression. A nil in the
		 * pattern ignores that position.
		 */
		private static void destructure(LispVal pattern, LispVal source, List<LispVal[]> out) {
			if (pattern instanceof LispNil) {
				return;
			}
			if (pattern instanceof LispSymbol s) {
				out.add(new LispVal[] { s, source });
				return;
			}
			if (pattern instanceof LispCons c) {
				destructure(c.car(), call(LispNames.CAR, source), out);
				destructure(c.cdr(), call(LispNames.CDR, source), out);
				return;
			}
			throw new IllegalArgumentException("loop: invalid destructuring pattern: " + pattern.print());
		}

		private void parseWith() {
			List<LispVal[]> group = new java.util.ArrayList<>(); // {pattern, init}
			while (true) {
				LispVal var = nextForm();
				if (!(var instanceof LispSymbol) && !(var instanceof LispCons)) {
					throw new IllegalArgumentException("loop: with expects a variable, got: " + var.print());
				}
				LispVal val = LispNil.INSTANCE;
				if ("=".equals(peekKeyword())) {
					pos++;
					val = nextForm();
				}
				group.add(new LispVal[] { var, val });
				if ("and".equals(peekKeyword())) {
					pos++;
					continue;
				}
				break;
			}
			if (group.size() == 1) {
				bindWithPattern(group.get(0)[0], group.get(0)[1]);
				return;
			}
			// `and`-joined with bindings are parallel: evaluate every init before binding
			// any variable, so a later init sees the outer bindings.
			List<LispVal[]> deferred = new java.util.ArrayList<>();
			for (LispVal[] g : group) {
				if (g[1] instanceof LispNil) {
					deferred.add(g);
					continue;
				}
				LispSymbol temp = gensym("tmp");
				bindings.add(pair(temp, g[1]));
				deferred.add(new LispVal[] { g[0], temp });
			}
			for (LispVal[] d : deferred) {
				bindWithPattern(d[0], d[1]);
			}
		}

		private void bindWithPattern(LispVal pattern, LispVal init) {
			if (pattern instanceof LispSymbol var) {
				bindings.add(pair(var, init));
				return;
			}
			LispVal source = init;
			if (!(init instanceof LispSymbol)) {
				LispSymbol temp = gensym("tmp");
				bindings.add(pair(temp, init));
				source = temp;
			}
			List<LispVal[]> parts = new java.util.ArrayList<>();
			destructure(pattern, source, parts);
			for (LispVal[] p : parts) {
				bindings.add(pair((LispSymbol) p[0], p[1]));
			}
		}

		/**
		 * A {@code while}/{@code until} before any body clause is hoisted into the loop
		 * head (iteration variables are already current there — they sync at binding time
		 * and at the end of the step forms); once body statements exist, the test runs at
		 * its textual position and exits through the epilogue (finally + result) when it
		 * fires.
		 */
		private void addTerminationTest(LispVal test, boolean until) {
			if (mainBody.isEmpty()) {
				endTests.add(until ? test : makeNot(test));
			}
			else if (until) {
				mainBody.add(makeIf(test, EPILOGUE_MARKER, LispNil.INSTANCE));
			}
			else {
				mainBody.add(makeIf(test, LispNil.INSTANCE, EPILOGUE_MARKER));
			}
		}

		/**
		 * {@code always EXPR} returns nil (skipping {@code finally}, like {@code return})
		 * the first time the expression is nil and t on normal completion;
		 * {@code never EXPR} is the negation.
		 */
		private void parseAlwaysNever(boolean never) {
			setTerminationResult(true);
			LispVal form = nextForm();
			mainBody.add(never ? makeIf(form, makeReturn(LispNil.INSTANCE), LispNil.INSTANCE)
					: makeIf(form, LispNil.INSTANCE, makeReturn(LispNil.INSTANCE)));
		}

		/**
		 * {@code thereis EXPR} returns the first non-nil value of the expression
		 * (skipping {@code finally}, like {@code return}) and nil on normal completion.
		 */
		private void parseThereis() {
			setTerminationResult(false);
			LispSymbol val = gensym("thereis");
			bindings.add(pair(val, LispNil.INSTANCE));
			mainBody.add(makeProgn(List.of(setq(val, nextForm()), makeIf(val, makeReturn(val), LispNil.INSTANCE))));
		}

		private void setTerminationResult(boolean t) {
			if (implicitAcc != null) {
				throw new IllegalArgumentException(
						"loop: cannot combine always/never/thereis with accumulation (use into)");
			}
			if (terminationT != null && terminationT != t) {
				throw new IllegalArgumentException("loop: cannot mix always/never with thereis");
			}
			terminationT = t;
		}

		private void parseRepeat() {
			LispVal n = nextForm();
			LispSymbol counter = gensym("repeat");
			bindings.add(pair(counter, n));
			LispVal test = call(LispNames.LE, counter, new LispInteger(0));
			endTests.add(test);
			if (!steps.isEmpty() && !driverEndTests.isEmpty()) {
				steps.add(makeIf(orOf(driverEndTests), EPILOGUE_MARKER, LispNil.INSTANCE));
			}
			steps.add(setq(counter, call(LispNames.SUB, counter, new LispInteger(1))));
			driverEndTests.add(test);
		}

		/**
		 * Parses a {@code when}/{@code if}/{@code unless} conditional into an if
		 * statement. When a branch references the anaphoric {@code it}, the raw test
		 * value is stored in a per-conditional variable that {@code it} is substituted
		 * with (nested conditionals substitute their own {@code it} first, so each
		 * {@code it} refers to the nearest enclosing test).
		 */
		private LispVal parseConditional(boolean negate) {
			LispVal cond = nextForm();
			LispVal thenStmt = parseConditionalBranch();
			LispVal elseStmt = LispNil.INSTANCE;
			if ("else".equals(peekKeyword())) {
				pos++;
				elseStmt = parseConditionalBranch();
			}
			if ("end".equals(peekKeyword())) {
				pos++;
			}
			LispSymbol itVar = gensym("it");
			LispVal thenSub = substituteIt(thenStmt, itVar);
			LispVal elseSub = substituteIt(elseStmt, itVar);
			if (thenSub == thenStmt && elseSub == elseStmt) {
				return makeIf(negate ? makeNot(cond) : cond, thenStmt, elseStmt);
			}
			bindings.add(pair(itVar, LispNil.INSTANCE));
			return makeProgn(List.of(setq(itVar, cond), makeIf(negate ? makeNot(itVar) : itVar, thenSub, elseSub)));
		}

		/**
		 * Parses one or more selectable clauses joined by {@code and} into a single
		 * statement.
		 */
		private LispVal parseConditionalBranch() {
			List<LispVal> stmts = new java.util.ArrayList<>();
			stmts.add(parseSelectable());
			while ("and".equals(peekKeyword())) {
				pos++;
				stmts.add(parseSelectable());
			}
			return stmts.size() == 1 ? stmts.get(0) : makeProgn(stmts);
		}

		/**
		 * Parses a single clause selectable by {@code when}/{@code unless} into a
		 * statement.
		 */
		private LispVal parseSelectable() {
			String kw = peekKeyword();
			if (kw == null) {
				throw new IllegalArgumentException(
						"loop: expected a clause after when/unless, got: " + toks.get(pos).print());
			}
			pos++;
			return switch (kw) {
				case "do", "doing" -> makeProgn(readForms());
				case "return" -> makeReturn(nextForm());
				case "when", "if" -> parseConditional(false);
				case "unless" -> parseConditional(true);
				case "collect", "collecting", "append", "appending", "nconc", "nconcing", "sum", "summing", "count",
						"counting", "maximize", "maximizing", "minimize", "minimizing" ->
					parseAccumulation(kw);
				default -> throw new UnsupportedOperationException("loop: clause not selectable by when/unless: " + kw);
			};
		}

		private LispVal parseAccumulation(String kw) {
			LispVal value = nextForm();
			LispSymbol target;
			if ("into".equals(peekKeyword())) {
				pos++;
				LispVal v = nextForm();
				if (!(v instanceof LispSymbol intoVar)) {
					throw new IllegalArgumentException("loop: into expects a variable, got: " + v.print());
				}
				target = intoVar;
				registerInto(intoVar, accInit(kw), accReverse(kw));
			}
			else {
				target = implicitAccumulator(accInit(kw), accReverse(kw));
			}
			return accStep(kw, target, value);
		}

		/** The init form for an accumulator of the given kind. */
		private LispVal accInit(String kw) {
			return switch (canonicalAcc(kw)) {
				case "sum", "count" -> new LispInteger(0);
				default -> LispNil.INSTANCE;
			};
		}

		/** Whether a kind builds a list that must be reversed to restore source order. */
		private boolean accReverse(String kw) {
			return switch (canonicalAcc(kw)) {
				case "collect", "append", "nconc" -> true;
				default -> false;
			};
		}

		/**
		 * Builds the per-iteration accumulation step for the given kind onto {@code acc}.
		 */
		private LispVal accStep(String kw, LispSymbol acc, LispVal value) {
			return switch (canonicalAcc(kw)) {
				case "collect" -> setq(acc, call(LispNames.CONS, value, acc));
				case "append" -> setq(acc, call(LispNames.REVAPPEND, value, acc));
				case "nconc" -> setq(acc, call(LispNames.NRECONC, value, acc));
				case "sum" -> setq(acc, call(LispNames.ADD, acc, value));
				case "count" ->
					makeIf(value, setq(acc, call(LispNames.ADD, acc, new LispInteger(1))), LispNil.INSTANCE);
				case "maximize" -> setq(acc, makeIf(acc, call(LispNames.MAX, acc, value), value));
				case "minimize" -> setq(acc, makeIf(acc, call(LispNames.MIN, acc, value), value));
				default -> throw new UnsupportedOperationException("loop: unsupported accumulation: " + kw);
			};
		}

		private static String canonicalAcc(String kw) {
			return switch (kw) {
				case "collecting" -> "collect";
				case "appending" -> "append";
				case "nconcing" -> "nconc";
				case "summing" -> "sum";
				case "counting" -> "count";
				case "maximizing" -> "maximize";
				case "minimizing" -> "minimize";
				default -> kw;
			};
		}

		private final java.util.Map<String, Boolean> intoVars = new java.util.HashMap<>();

		private void registerInto(LispSymbol var, LispVal init, boolean reverse) {
			Boolean seen = intoVars.get(var.name());
			if (seen == null) {
				intoVars.put(var.name(), reverse);
				bindings.add(pair(var, init));
				if (reverse) {
					postLoop.add(setq(var, call(LispNames.NREVERSE, var)));
				}
			}
			else if (seen != reverse) {
				throw new IllegalArgumentException("loop: incompatible accumulation kinds into " + var.name());
			}
		}

		private LispSymbol implicitAccumulator(LispVal init, boolean reverse) {
			if (terminationT != null) {
				throw new IllegalArgumentException(
						"loop: cannot combine always/never/thereis with accumulation (use into)");
			}
			if (implicitAcc == null) {
				implicitAcc = gensym("acc");
				implicitInit = init;
				implicitReverse = reverse;
				bindings.add(pair(implicitAcc, init));
			}
			else if (implicitReverse != reverse || implicitInit == null || !implicitInit.equals(init)) {
				throw new IllegalArgumentException(
						"loop: cannot mix accumulation kinds without into (use distinct into variables)");
			}
			return implicitAcc;
		}

		private LispVal resultExpr() {
			if (implicitAcc == null) {
				return Boolean.TRUE.equals(terminationT) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return implicitReverse ? call(LispNames.NREVERSE, implicitAcc) : implicitAcc;
		}

		/**
		 * The loop's normal-completion epilogue as an inline exit statement: the
		 * accumulator finish forms, then {@code finally}, then a {@code return} of the
		 * loop result. Substituted for positional {@code while}/{@code until} exits and
		 * {@code (loop-finish)} calls.
		 */
		private LispVal epilogueExit() {
			List<LispVal> parts = new java.util.ArrayList<>();
			parts.add(new LispSymbol(LispNames.PROGN));
			parts.addAll(postLoop);
			parts.addAll(finallyForms);
			parts.add(makeReturn(resultExpr()));
			return listToCons(parts);
		}

		/**
		 * Replaces every epilogue marker and {@code (loop-finish)} call in the given
		 * statements with the epilogue exit.
		 */
		private static void substituteEpilogueExits(List<LispVal> stmts, LispVal epilogue) {
			stmts.replaceAll(stmt -> substituteTree(stmt, EXIT_SKIP_HEADS, v -> isEpilogueExit(v) ? epilogue : null));
		}

		private static boolean isEpilogueExit(LispVal v) {
			if (v instanceof LispSymbol s && EPILOGUE_MARKER.name().equals(s.name())) {
				return true;
			}
			return v instanceof LispCons c && c.car() instanceof LispSymbol head
					&& head.name().equalsIgnoreCase("loop-finish");
		}

		/** Substitutes the anaphoric {@code it} symbol with the given variable. */
		private static LispVal substituteIt(LispVal tree, LispSymbol itVar) {
			return substituteTree(tree, IT_SKIP_HEADS,
					v -> (v instanceof LispSymbol s && s.name().equalsIgnoreCase("it")) ? itVar : null);
		}

		/**
		 * Rebuilds a form tree with every node the replacer maps swapped out, without
		 * descending into forms whose head is in {@code skipHeads}. Returns the original
		 * instance when nothing was replaced (callers use identity to detect a hit).
		 */
		private static LispVal substituteTree(LispVal tree, java.util.Set<String> skipHeads,
				java.util.function.Function<LispVal, @Nullable LispVal> replacer) {
			LispVal replaced = replacer.apply(tree);
			if (replaced != null) {
				return replaced;
			}
			if (tree instanceof LispCons cons) {
				if (cons.car() instanceof LispSymbol head
						&& skipHeads.contains(head.name().toLowerCase(java.util.Locale.ROOT))) {
					return tree;
				}
				LispVal car = substituteTree(cons.car(), skipHeads, replacer);
				LispVal cdr = substituteTree(cons.cdr(), skipHeads, replacer);
				if (car == cons.car() && cdr == cons.cdr()) {
					return tree;
				}
				return new LispCons(car, cdr);
			}
			return tree;
		}

		// --- small token / form helpers ---

		private LispVal nextForm() {
			if (pos >= toks.size()) {
				throw new IllegalArgumentException("loop: unexpected end of clauses");
			}
			return toks.get(pos++);
		}

		private @Nullable String peekKeyword() {
			return pos < toks.size() ? loopKeyword(toks.get(pos)) : null;
		}

		/**
		 * Reads forms up to (but not consuming) the next clause keyword or end of input.
		 */
		private List<LispVal> readForms() {
			List<LispVal> forms = new java.util.ArrayList<>();
			while (pos < toks.size() && loopKeyword(toks.get(pos)) == null) {
				forms.add(toks.get(pos++));
			}
			if (forms.isEmpty()) {
				forms.add(LispNil.INSTANCE);
			}
			return forms;
		}

		private LispSymbol gensym(String tag) {
			return new LispSymbol("__loop_" + tag + (gen++));
		}

		private static LispVal pair(LispSymbol var, LispVal val) {
			return listToCons(List.of(var, val));
		}

		private static LispVal setq(LispSymbol var, LispVal val) {
			return listToCons(List.of(new LispSymbol(LispNames.SETQ), var, val));
		}

		private static LispVal call(String op, LispVal a, LispVal b) {
			return listToCons(List.of(new LispSymbol(op), a, b));
		}

		private static LispVal call(String op, LispVal a) {
			return listToCons(List.of(new LispSymbol(op), a));
		}

		private static LispVal orOf(List<LispVal> tests) {
			if (tests.size() == 1) {
				return tests.get(0);
			}
			List<LispVal> parts = new java.util.ArrayList<>();
			parts.add(new LispSymbol(LispNames.OR));
			parts.addAll(tests);
			return listToCons(parts);
		}

	}

	/**
	 * Builds the per-iteration step assignments for {@code do}. A single stepped variable
	 * becomes a direct {@code setq}; multiple stepped variables are assigned in parallel
	 * via temporaries so a step form sees the previous iteration's values.
	 */
	private static List<LispVal> makeStepForms(List<LispVal[]> steps) {
		if (steps.isEmpty()) {
			return List.of();
		}
		if (steps.size() == 1) {
			LispVal[] s = steps.get(0);
			return List.of(listToCons(List.of(new LispSymbol(LispNames.SETQ), s[0], s[1])));
		}
		List<LispVal> tempBindings = new java.util.ArrayList<>();
		List<LispVal> assignments = new java.util.ArrayList<>();
		for (int i = 0; i < steps.size(); i++) {
			LispVal[] s = steps.get(i);
			LispSymbol temp = new LispSymbol(DO_STEP_VAR + i);
			tempBindings.add(listToCons(List.of(temp, s[1])));
			assignments.add(listToCons(List.of(new LispSymbol(LispNames.SETQ), s[0], temp)));
		}
		List<LispVal> letParts = new java.util.ArrayList<>();
		letParts.add(new LispSymbol(LispNames.LET));
		letParts.add(listToCons(tempBindings));
		letParts.addAll(assignments);
		return List.of(listToCons(letParts));
	}

	private static final String DO_STEP_VAR = "__do_step_";

	/** Wraps a form in the internal {@code %block} return boundary. */
	private static LispVal makeBlock(LispVal body) {
		return listToCons(List.of(new LispSymbol(LispNames.BLOCK_INTERNAL), body));
	}

	/** Builds a {@code (return value)} form for non-local exit from the nearest loop. */
	private static LispVal makeReturn(LispVal value) {
		return listToCons(List.of(new LispSymbol(LispNames.RETURN), value));
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
	 *
	 * Multiple place/value pairs assign sequentially, like several setf forms in a row:
	 * {@code (setf p1 v1 p2 v2)} -> {@code (progn (setf p1 v1) (setf p2 v2))}.
	 * @param cons the setf expression
	 * @return the expanded expression
	 */
	public static LispVal expandSetf(LispCons cons) {
		return expandSetf(cons, java.util.Map.of());
	}

	/**
	 * Like {@link #expandSetf(LispCons)}, but additionally treats registered
	 * {@code defstruct} accessors as places: {@code (setf (point-x p) val)} expands like
	 * {@code (setf (nth <position> p) val)}. The evaluator and the compilers pass their
	 * accessor registry (built by {@link #expandDefstruct}) here; {@code push}/
	 * {@code pop}/{@code incf}/... expand into {@code setf} forms that are re-dispatched
	 * through the caller, so struct places work with them too.
	 * @param cons the setf expression
	 * @param structAccessors accessor name to 1-based slot position
	 * @return the expanded expression
	 */
	public static LispVal expandSetf(LispCons cons, java.util.Map<String, Integer> structAccessors) {
		List<LispVal> parts = cons.toList();
		if (parts.size() > 3) {
			if (parts.size() % 2 == 0) {
				throw new IllegalArgumentException("setf: odd number of arguments: " + cons.print());
			}
			List<LispVal> forms = new java.util.ArrayList<>();
			for (int i = 1; i < parts.size(); i += 2) {
				forms.add(expandSetf(
						(LispCons) listToCons(List.of(new LispSymbol(LispNames.SETF), parts.get(i), parts.get(i + 1))),
						structAccessors));
			}
			return makeProgn(forms);
		}
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
				case LispNames.GETHASH ->
					// (setf (gethash key table [default]) val) -> (%puthash key table
					// val).
					// The optional default in the place is only used by gethash in read
					// position, so it is dropped here.
					listToCons(List.of(new LispSymbol(LispNames.PUTHASH), placeParts.get(1), placeParts.get(2), value));
				case LispNames.AREF -> {
					// (setf (aref array sub...) val) -> (%aset array sub... val)
					List<LispVal> asetParts = new java.util.ArrayList<>();
					asetParts.add(new LispSymbol(LispNames.ASET));
					for (int i = 1; i < placeParts.size(); i++) {
						asetParts.add(placeParts.get(i));
					}
					asetParts.add(value);
					yield listToCons(asetParts);
				}
				case LispNames.NTH -> {
					// (setf (nth n x) val) -> (let ((__setf val)) (rplaca (nthcdr n x)
					// __setf) __setf)
					LispVal n = placeParts.get(1);
					LispVal list = placeParts.get(2);
					LispVal nthcdrExpr = listToCons(List.of(new LispSymbol(LispNames.NTHCDR), n, list));

					yield expandSetfWithRplaca(nthcdrExpr, value);
				}
				case LispNames.SECOND ->

				{
					LispVal nthcdrExpr = listToCons(
							List.of(new LispSymbol(LispNames.NTHCDR), new LispInteger(1), placeParts.get(1)));

					yield expandSetfWithRplaca(nthcdrExpr, value);
				}
				case LispNames.THIRD ->

				{
					LispVal nthcdrExpr = listToCons(
							List.of(new LispSymbol(LispNames.NTHCDR), new LispInteger(2), placeParts.get(1)));

					yield expandSetfWithRplaca(nthcdrExpr, value);
				}
				case LispNames.FOURTH ->

				{
					LispVal nthcdrExpr = listToCons(
							List.of(new LispSymbol(LispNames.NTHCDR), new LispInteger(3), placeParts.get(1)));

					yield expandSetfWithRplaca(nthcdrExpr, value);
				}
				default ->

				{
					Integer structSlot = structAccessors.get(accessor);
					if (structSlot != null) {
						// (setf (point-x p) val) -> (setf (nth <position> p) val)
						LispVal nthcdrExpr = listToCons(List.of(new LispSymbol(LispNames.NTHCDR),
								new LispInteger(structSlot), placeParts.get(1)));
						yield expandSetfWithRplaca(nthcdrExpr, value);
					}
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
	 * Expands (pop place) into a prog1 expression: save the head, advance the place, and
	 * return the saved head.
	 *
	 * <pre>
	 * (pop place) -> (prog1 (car place) (setf place (cdr place)))
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
		// (prog1 (car place) (setf place (cdr place)))
		LispCons prog1Expr = (LispCons) listToCons(List.of(new LispSymbol(LispNames.PROG1), carExpr, setfExpr));
		return expandProg1(prog1Expr);
	}

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
		// (let ((__dolist list)) while-expr result-expr), wrapped in a return boundary.
		LispVal bindings = new LispCons(listToCons(List.of(cursor, listForm)), LispNil.INSTANCE);
		return makeBlock(listToCons(List.of(new LispSymbol(LispNames.LET), bindings, whileExpr, resultExpr)));
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
	 * (length lst) -> (reduce (lambda (__acc __x) (+ __acc 1)) lst :initial-value 0)
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
		return listToCons(List.of(new LispSymbol(LispNames.REDUCE), lambda, parts.get(1),
				new LispSymbol(LispNames.INITIAL_VALUE_KEYWORD), new LispInteger(0)));
	}

	/**
	 * Expands (reverse lst) into a reduce-based reversal.
	 *
	 * <pre>
	 * (reverse lst) -> (reduce (lambda (__acc __x) (cons __x __acc)) lst :initial-value nil)
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
		return listToCons(List.of(new LispSymbol(LispNames.REDUCE), lambda, parts.get(1),
				new LispSymbol(LispNames.INITIAL_VALUE_KEYWORD), LispNil.INSTANCE));
	}

	/**
	 * Expands (member item lst) into a let/while scan returning the tail whose car is
	 * {@code eql} to the item, or nil. With a {@code :test} keyword
	 * ({@code (member item lst :test fn)}) the equality predicate is {@code fn} applied
	 * as {@code (funcall fn item element)} instead of {@code eql}; the test designator is
	 * inlined so a literal {@code 'name}/{@code #'name} resolves through the compilers'
	 * function-designator normalization.
	 * @param cons the member expression
	 * @return the expanded expression
	 */
	public static LispVal expandMember(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal testForm = keywordValue(parts, 3, LispNames.TEST_KEYWORD);
		LispSymbol item = new LispSymbol("__member_item");
		LispSymbol cur = new LispSymbol("__member_cur");
		// (do ((__member_item item) (__member_cur lst (cdr __member_cur)))
		// ((atom __member_cur) nil)
		// (if (eql __member_item (car __member_cur)) (return __member_cur)))
		// With :test fn, the match becomes (funcall fn __member_item (car __member_cur)).
		LispVal bindings = listToCons(List.of(listToCons(List.of(item, parts.get(1))),
				listToCons(List.of(cur, parts.get(2), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), LispNil.INSTANCE));
		LispVal element = callOf(LispNames.CAR, cur);
		LispVal match = (testForm == null) ? listToCons(List.of(new LispSymbol(LispNames.EQL), item, element))
				: listToCons(List.of(new LispSymbol(LispNames.FUNCALL), testForm, item, element));
		LispVal body = makeIf(match, makeReturn(cur), LispNil.INSTANCE);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	// Scans a keyword/value argument tail starting at the given index for the named
	// keyword, returning the form following the first match, or null when absent.
	private static @Nullable LispVal keywordValue(List<LispVal> parts, int start, String keyword) {
		for (int i = start; i + 1 < parts.size(); i += 2) {
			if (parts.get(i) instanceof LispSymbol kw && keyword.equals(kw.name())) {
				return parts.get(i + 1);
			}
		}
		return null;
	}

	/**
	 * Expands (find item lst) into a do/return scan returning the first element
	 * {@code eql} to the item, or nil. Like {@code member} but yields the element itself
	 * rather than the tail.
	 * @param cons the find expression
	 * @return the expanded expression
	 */
	public static LispVal expandFind(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol item = new LispSymbol("__find_item");
		LispSymbol cur = new LispSymbol("__find_cur");
		// (do ((__find_item item) (__find_cur lst (cdr __find_cur)))
		// ((atom __find_cur) nil)
		// (if (eql __find_item (car __find_cur)) (return (car __find_cur)) nil))
		LispVal bindings = listToCons(List.of(listToCons(List.of(item, parts.get(1))),
				listToCons(List.of(cur, parts.get(2), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), LispNil.INSTANCE));
		LispVal elem = callOf(LispNames.CAR, cur);
		LispVal match = listToCons(List.of(new LispSymbol(LispNames.EQL), item, elem));
		LispVal body = makeIf(match, makeReturn(elem), LispNil.INSTANCE);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (find-if pred lst) into a do/return scan returning the first element for
	 * which the predicate is true, or nil. Like {@code find} but tests each element with
	 * {@code (funcall pred element)} rather than {@code eql}.
	 * @param cons the find-if expression
	 * @return the expanded expression
	 */
	public static LispVal expandFindIf(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol pred = new LispSymbol("__findif_pred");
		LispSymbol cur = new LispSymbol("__findif_cur");
		// (do ((__findif_pred pred) (__findif_cur lst (cdr __findif_cur)))
		// ((atom __findif_cur) nil)
		// (if (funcall __findif_pred (car __findif_cur)) (return (car __findif_cur))
		// nil))
		LispVal bindings = listToCons(List.of(listToCons(List.of(pred, parts.get(1))),
				listToCons(List.of(cur, parts.get(2), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), LispNil.INSTANCE));
		LispVal elem = callOf(LispNames.CAR, cur);
		LispVal test = listToCons(List.of(new LispSymbol(LispNames.FUNCALL), pred, elem));
		LispVal body = makeIf(test, makeReturn(elem), LispNil.INSTANCE);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (find-if-not pred lst) into a do/return scan returning the first element
	 * for which the predicate is false, or nil. The complement of {@code find-if}: the
	 * test is {@code (not (funcall pred element))}.
	 * @param cons the find-if-not expression
	 * @return the expanded expression
	 */
	public static LispVal expandFindIfNot(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol pred = new LispSymbol("__findifnot_pred");
		LispSymbol cur = new LispSymbol("__findifnot_cur");
		// (do ((__findifnot_pred pred) (__findifnot_cur lst (cdr __findifnot_cur)))
		// ((atom __findifnot_cur) nil)
		// (if (not (funcall __findifnot_pred (car __findifnot_cur)))
		// (return (car __findifnot_cur)) nil))
		LispVal bindings = listToCons(List.of(listToCons(List.of(pred, parts.get(1))),
				listToCons(List.of(cur, parts.get(2), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), LispNil.INSTANCE));
		LispVal elem = callOf(LispNames.CAR, cur);
		LispVal call = listToCons(List.of(new LispSymbol(LispNames.FUNCALL), pred, elem));
		LispVal test = listToCons(List.of(new LispSymbol(LispNames.NOT), call));
		LispVal body = makeIf(test, makeReturn(elem), LispNil.INSTANCE);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (position item lst) into a do/return scan returning the 0-based index of
	 * the first element {@code eql} to the item, or nil. Like {@code find} but yields the
	 * position rather than the element.
	 * @param cons the position expression
	 * @return the expanded expression
	 */
	public static LispVal expandPosition(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol item = new LispSymbol("__pos_item");
		LispSymbol idx = new LispSymbol("__pos_idx");
		LispSymbol cur = new LispSymbol("__pos_cur");
		// (do ((__pos_item item) (__pos_idx 0 (+ __pos_idx 1))
		// (__pos_cur lst (cdr __pos_cur)))
		// ((atom __pos_cur) nil)
		// (if (eql __pos_item (car __pos_cur)) (return __pos_idx) nil))
		LispVal idxStep = listToCons(List.of(new LispSymbol(LispNames.ADD), idx, new LispInteger(1)));
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(item, parts.get(1))), listToCons(List.of(idx, new LispInteger(0), idxStep)),
						listToCons(List.of(cur, parts.get(2), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), LispNil.INSTANCE));
		LispVal match = listToCons(List.of(new LispSymbol(LispNames.EQL), item, callOf(LispNames.CAR, cur)));
		LispVal body = makeIf(match, makeReturn(idx), LispNil.INSTANCE);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (position-if pred lst) into a do/return scan returning the 0-based index of
	 * the first element for which the predicate is true, or nil. Like {@code position}
	 * but tests each element with {@code (funcall pred element)} rather than {@code eql}.
	 * @param cons the position-if expression
	 * @return the expanded expression
	 */
	public static LispVal expandPositionIf(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol pred = new LispSymbol("__posif_pred");
		LispSymbol idx = new LispSymbol("__posif_idx");
		LispSymbol cur = new LispSymbol("__posif_cur");
		// (do ((__posif_pred pred) (__posif_idx 0 (+ __posif_idx 1))
		// (__posif_cur lst (cdr __posif_cur)))
		// ((atom __posif_cur) nil)
		// (if (funcall __posif_pred (car __posif_cur)) (return __posif_idx) nil))
		LispVal idxStep = listToCons(List.of(new LispSymbol(LispNames.ADD), idx, new LispInteger(1)));
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(pred, parts.get(1))), listToCons(List.of(idx, new LispInteger(0), idxStep)),
						listToCons(List.of(cur, parts.get(2), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), LispNil.INSTANCE));
		LispVal test = listToCons(List.of(new LispSymbol(LispNames.FUNCALL), pred, callOf(LispNames.CAR, cur)));
		LispVal body = makeIf(test, makeReturn(idx), LispNil.INSTANCE);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (count item lst) into a do scan returning the number of elements
	 * {@code eql} to the item. Like {@code position} but accumulates a count of all
	 * matches rather than returning the first index.
	 * @param cons the count expression
	 * @return the expanded expression
	 */
	public static LispVal expandCount(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol item = new LispSymbol("__count_item");
		LispSymbol n = new LispSymbol("__count_n");
		LispSymbol cur = new LispSymbol("__count_cur");
		// (do ((__count_item item) (__count_n 0)
		// (__count_cur lst (cdr __count_cur)))
		// ((atom __count_cur) __count_n)
		// (if (eql __count_item (car __count_cur))
		// (setq __count_n (+ __count_n 1)) nil))
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(item, parts.get(1))), listToCons(List.of(n, new LispInteger(0))),
						listToCons(List.of(cur, parts.get(2), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), n));
		LispVal match = listToCons(List.of(new LispSymbol(LispNames.EQL), item, callOf(LispNames.CAR, cur)));
		LispVal increment = listToCons(List.of(new LispSymbol(LispNames.ADD), n, new LispInteger(1)));
		LispVal incrementStep = listToCons(List.of(new LispSymbol(LispNames.SETQ), n, increment));
		LispVal body = makeIf(match, incrementStep, LispNil.INSTANCE);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (count-if pred lst) into a do scan returning the number of elements for
	 * which the predicate is true. Like {@code count} but tests each element with
	 * {@code (funcall pred element)} rather than {@code eql}.
	 * @param cons the count-if expression
	 * @return the expanded expression
	 */
	public static LispVal expandCountIf(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol pred = new LispSymbol("__countif_pred");
		LispSymbol n = new LispSymbol("__countif_n");
		LispSymbol cur = new LispSymbol("__countif_cur");
		// (do ((__countif_pred pred) (__countif_n 0)
		// (__countif_cur lst (cdr __countif_cur)))
		// ((atom __countif_cur) __countif_n)
		// (if (funcall __countif_pred (car __countif_cur))
		// (setq __countif_n (+ __countif_n 1)) nil))
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(pred, parts.get(1))), listToCons(List.of(n, new LispInteger(0))),
						listToCons(List.of(cur, parts.get(2), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), n));
		LispVal test = listToCons(List.of(new LispSymbol(LispNames.FUNCALL), pred, callOf(LispNames.CAR, cur)));
		LispVal increment = listToCons(List.of(new LispSymbol(LispNames.ADD), n, new LispInteger(1)));
		LispVal incrementStep = listToCons(List.of(new LispSymbol(LispNames.SETQ), n, increment));
		LispVal body = makeIf(test, incrementStep, LispNil.INSTANCE);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (assoc key alist) into a let/while scan returning the first pair whose car
	 * is {@code eql} to the key, or nil.
	 * @param cons the assoc expression
	 * @return the expanded expression
	 */
	public static LispVal expandAssoc(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol key = new LispSymbol("__assoc_key");
		LispSymbol cur = new LispSymbol("__assoc_cur");
		LispVal pair = callOf(LispNames.CAR, cur);
		// (do ((__assoc_key key) (__assoc_cur alist (cdr __assoc_cur)))
		// ((atom __assoc_cur) nil)
		// (if (and (consp (car __assoc_cur)) (eq __assoc_key (car (car __assoc_cur))))
		// (return (car __assoc_cur))))
		LispVal bindings = listToCons(List.of(listToCons(List.of(key, parts.get(1))),
				listToCons(List.of(cur, parts.get(2), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), LispNil.INSTANCE));
		LispVal match = listToCons(
				List.of(new LispSymbol(LispNames.AND), listToCons(List.of(new LispSymbol(LispNames.CONSP), pair)),
						listToCons(List.of(new LispSymbol(LispNames.EQL), key, callOf(LispNames.CAR, pair)))));
		LispVal body = makeIf(match, makeReturn(pair), LispNil.INSTANCE);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (member-if pred lst) into a do/return scan returning the tail of the list
	 * starting at the first element for which the predicate is true, or nil. Like
	 * {@code member} but tests each element with {@code (funcall pred element)} rather
	 * than {@code eql}, and like {@code find-if} but yields the tail rather than the
	 * element.
	 * @param cons the member-if expression
	 * @return the expanded expression
	 */
	public static LispVal expandMemberIf(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol pred = new LispSymbol("__memberif_pred");
		LispSymbol cur = new LispSymbol("__memberif_cur");
		// (do ((__memberif_pred pred) (__memberif_cur lst (cdr __memberif_cur)))
		// ((atom __memberif_cur) nil)
		// (if (funcall __memberif_pred (car __memberif_cur)) (return __memberif_cur)
		// nil))
		LispVal bindings = listToCons(List.of(listToCons(List.of(pred, parts.get(1))),
				listToCons(List.of(cur, parts.get(2), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), LispNil.INSTANCE));
		LispVal test = listToCons(List.of(new LispSymbol(LispNames.FUNCALL), pred, callOf(LispNames.CAR, cur)));
		LispVal body = makeIf(test, makeReturn(cur), LispNil.INSTANCE);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (assoc-if pred alist) into a do/return scan returning the first pair whose
	 * car satisfies the predicate, or nil. Like {@code assoc} but tests each pair's car
	 * with {@code (funcall pred (car pair))} rather than {@code eql}.
	 * @param cons the assoc-if expression
	 * @return the expanded expression
	 */
	public static LispVal expandAssocIf(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol pred = new LispSymbol("__associf_pred");
		LispSymbol cur = new LispSymbol("__associf_cur");
		LispVal pair = callOf(LispNames.CAR, cur);
		// (do ((__associf_pred pred) (__associf_cur alist (cdr __associf_cur)))
		// ((atom __associf_cur) nil)
		// (if (and (consp (car __associf_cur))
		// (funcall __associf_pred (car (car __associf_cur))))
		// (return (car __associf_cur))))
		LispVal bindings = listToCons(List.of(listToCons(List.of(pred, parts.get(1))),
				listToCons(List.of(cur, parts.get(2), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), LispNil.INSTANCE));
		LispVal match = listToCons(
				List.of(new LispSymbol(LispNames.AND), listToCons(List.of(new LispSymbol(LispNames.CONSP), pair)),
						listToCons(List.of(new LispSymbol(LispNames.FUNCALL), pred, callOf(LispNames.CAR, pair)))));
		LispVal body = makeIf(match, makeReturn(pair), LispNil.INSTANCE);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (getf plist key) into a do/return scan over a property list, returning the
	 * value following the first key {@code eql} to the indicator, or nil. The cursor
	 * advances two cells at a time ({@code cddr}). The partner of {@code remf}.
	 * @param cons the getf expression
	 * @return the expanded expression
	 */
	public static LispVal expandGetf(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol key = new LispSymbol("__getf_key");
		LispSymbol cur = new LispSymbol("__getf_cur");
		// (getf plist key): plist is parts.get(1), the indicator is parts.get(2).
		// (do ((__getf_key key) (__getf_cur plist (cddr __getf_cur)))
		// ((atom __getf_cur) nil)
		// (if (eql __getf_key (car __getf_cur)) (return (cadr __getf_cur)) nil))
		LispVal cddrStep = listToCons(List.of(new LispSymbol("cddr"), cur));
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(key, parts.get(2))), listToCons(List.of(cur, parts.get(1), cddrStep))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), LispNil.INSTANCE));
		LispVal match = listToCons(List.of(new LispSymbol(LispNames.EQL), key, callOf(LispNames.CAR, cur)));
		LispVal value = listToCons(List.of(new LispSymbol("cadr"), cur));
		LispVal body = makeIf(match, makeReturn(value), LispNil.INSTANCE);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (remove-duplicates lst) into a do scan that accumulates (in reverse) every
	 * element that does not occur again later in the list, then reverses the accumulator
	 * back to source order. Elements are compared with {@code eql} via {@code member}, so
	 * the last occurrence of each element is kept (Common Lisp's default).
	 * @param cons the remove-duplicates expression
	 * @return the expanded expression
	 */
	public static LispVal expandRemoveDuplicates(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol acc = new LispSymbol("__rd_acc");
		LispSymbol cur = new LispSymbol("__rd_cur");
		// (do ((__rd_acc nil) (__rd_cur lst (cdr __rd_cur)))
		// ((atom __rd_cur) (reverse __rd_acc))
		// (if (member (car __rd_cur) (cdr __rd_cur)) nil
		// (setq __rd_acc (cons (car __rd_cur) __rd_acc))))
		LispVal bindings = listToCons(List.of(listToCons(List.of(acc, LispNil.INSTANCE)),
				listToCons(List.of(cur, parts.get(1), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), callOf(LispNames.REVERSE, acc)));
		LispVal dup = listToCons(
				List.of(new LispSymbol(LispNames.MEMBER), callOf(LispNames.CAR, cur), callOf(LispNames.CDR, cur)));
		LispVal keep = listToCons(List.of(new LispSymbol(LispNames.SETQ), acc,
				listToCons(List.of(new LispSymbol(LispNames.CONS), callOf(LispNames.CAR, cur), acc))));
		LispVal body = makeIf(dup, LispNil.INSTANCE, keep);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (butlast lst) into a do scan that accumulates (in reverse) every element
	 * except the last, then reverses the accumulator back to source order. An empty or
	 * single-element list yields nil.
	 * @param cons the butlast expression
	 * @return the expanded expression
	 */
	public static LispVal expandButlast(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol acc = new LispSymbol("__butlast_acc");
		LispSymbol cur = new LispSymbol("__butlast_cur");
		// (do ((__butlast_acc nil) (__butlast_cur lst (cdr __butlast_cur)))
		// ((or (atom __butlast_cur) (atom (cdr __butlast_cur))) (reverse __butlast_acc))
		// (setq __butlast_acc (cons (car __butlast_cur) __butlast_acc)))
		// The (atom __butlast_cur) guard short-circuits so (cdr __butlast_cur) is never
		// evaluated on nil (the compiled cdr requires a cons).
		LispVal bindings = listToCons(List.of(listToCons(List.of(acc, LispNil.INSTANCE)),
				listToCons(List.of(cur, parts.get(1), callOf(LispNames.CDR, cur)))));
		LispVal endTest = listToCons(List.of(new LispSymbol(LispNames.OR), callOf(LispNames.ATOM, cur),
				callOf(LispNames.ATOM, callOf(LispNames.CDR, cur))));
		LispVal endClause = listToCons(List.of(endTest, callOf(LispNames.REVERSE, acc)));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.SETQ), acc,
				listToCons(List.of(new LispSymbol(LispNames.CONS), callOf(LispNames.CAR, cur), acc))));
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (nconc a b) into a let/while walk that destructively links the last cons of
	 * {@code a} to {@code b} and returns {@code a} (or {@code b} when {@code a} is
	 * empty). Both operands are bound once to avoid re-evaluation.
	 * @param cons the nconc expression
	 * @return the expanded expression
	 */
	public static LispVal expandNconc(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol a = new LispSymbol("__nconc_a");
		LispSymbol b = new LispSymbol("__nconc_b");
		LispSymbol tail = new LispSymbol("__nconc_tail");
		// (let ((__nconc_a a) (__nconc_b b))
		// (if (atom __nconc_a) __nconc_b
		// (let ((__nconc_tail __nconc_a))
		// (while (consp (cdr __nconc_tail)) (setq __nconc_tail (cdr __nconc_tail)))
		// (rplacd __nconc_tail __nconc_b)
		// __nconc_a)))
		LispVal whileTest = listToCons(List.of(new LispSymbol(LispNames.CONSP), callOf(LispNames.CDR, tail)));
		LispVal whileStep = listToCons(List.of(new LispSymbol(LispNames.SETQ), tail, callOf(LispNames.CDR, tail)));
		LispVal whileExpr = listToCons(List.of(new LispSymbol(LispNames.WHILE), whileTest, whileStep));
		LispVal rplacd = listToCons(List.of(new LispSymbol(LispNames.RPLACD), tail, b));
		LispVal innerBindings = new LispCons(listToCons(List.of(tail, a)), LispNil.INSTANCE);
		LispVal innerLet = listToCons(List.of(new LispSymbol(LispNames.LET), innerBindings, whileExpr, rplacd, a));
		LispVal ifExpr = makeIf(callOf(LispNames.ATOM, a), b, innerLet);
		LispVal outerBindings = listToCons(
				List.of(listToCons(List.of(a, parts.get(1))), listToCons(List.of(b, parts.get(2)))));
		return listToCons(List.of(new LispSymbol(LispNames.LET), outerBindings, ifExpr));
	}

	/**
	 * Expands (identity x) into the argument form itself.
	 * @param cons the identity expression
	 * @return the expanded expression
	 */
	public static LispVal expandIdentity(LispCons cons) {
		return cons.toList().get(1);
	}

	/**
	 * Expands (nreverse lst) into an in-place reversal loop that rewires the {@code cdr}
	 * of every cons to its predecessor, returning the former last cell as the new head.
	 * This is destructive: the argument's cons cells are reused and the original list
	 * head is left pointing at a single-element list (Common Lisp semantics; use the
	 * return value).
	 * @param cons the nreverse expression
	 * @return the expanded expression
	 */
	public static LispVal expandNreverse(LispCons cons) {
		LispVal list = cons.toList().get(1);
		LispSymbol prev = new LispSymbol("__nrev_prev");
		LispSymbol cur = new LispSymbol("__nrev_cur");
		LispSymbol next = new LispSymbol("__nrev_next");
		// (let ((__nrev_prev nil) (__nrev_cur lst) (__nrev_next nil))
		// (while (consp __nrev_cur)
		// (setq __nrev_next (cdr __nrev_cur))
		// (rplacd __nrev_cur __nrev_prev)
		// (setq __nrev_prev __nrev_cur)
		// (setq __nrev_cur __nrev_next))
		// __nrev_prev)
		LispVal whileTest = listToCons(List.of(new LispSymbol(LispNames.CONSP), cur));
		LispVal saveNext = listToCons(List.of(new LispSymbol(LispNames.SETQ), next, callOf(LispNames.CDR, cur)));
		LispVal relink = listToCons(List.of(new LispSymbol(LispNames.RPLACD), cur, prev));
		LispVal advancePrev = listToCons(List.of(new LispSymbol(LispNames.SETQ), prev, cur));
		LispVal advanceCur = listToCons(List.of(new LispSymbol(LispNames.SETQ), cur, next));
		LispVal whileExpr = listToCons(
				List.of(new LispSymbol(LispNames.WHILE), whileTest, saveNext, relink, advancePrev, advanceCur));
		LispVal bindings = listToCons(List.of(listToCons(List.of(prev, LispNil.INSTANCE)),
				listToCons(List.of(cur, list)), listToCons(List.of(next, LispNil.INSTANCE))));
		return listToCons(List.of(new LispSymbol(LispNames.LET), bindings, whileExpr, prev));
	}

	/**
	 * Expands (copy-list lst) into (append lst nil). {@code append} copies every argument
	 * except the last, so this yields a shallow copy of the list.
	 * @param cons the copy-list expression
	 * @return the expanded expression
	 */
	public static LispVal expandCopyList(LispCons cons) {
		return listToCons(List.of(new LispSymbol(LispNames.APPEND), cons.toList().get(1), LispNil.INSTANCE));
	}

	/**
	 * Expands (make-list n) into a do loop that conses n nil elements. The CL
	 * {@code :initial-element} keyword is not supported.
	 * @param cons the make-list expression
	 * @return the expanded expression
	 */
	public static LispVal expandMakeList(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol i = new LispSymbol("__ml_i");
		LispSymbol acc = new LispSymbol("__ml_acc");
		// (do ((__ml_i n (- __ml_i 1)) (__ml_acc nil (cons nil __ml_acc)))
		// ((<= __ml_i 0) __ml_acc))
		LispVal iStep = listToCons(List.of(new LispSymbol(LispNames.SUB), i, new LispInteger(1)));
		LispVal accStep = listToCons(List.of(new LispSymbol(LispNames.CONS), LispNil.INSTANCE, acc));
		LispVal bindings = listToCons(List.of(listToCons(List.of(i, parts.get(1), iStep)),
				listToCons(List.of(acc, LispNil.INSTANCE, accStep))));
		LispVal endTest = listToCons(List.of(new LispSymbol(LispNames.LE), i, new LispInteger(0)));
		LispVal endClause = listToCons(List.of(endTest, acc));
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause)));
	}

	/**
	 * Expands (adjoin item lst) into a let/if that prepends the item to the list unless
	 * it is already a member (compared with {@code eql}). Both operands are bound once to
	 * avoid re-evaluation.
	 * @param cons the adjoin expression
	 * @return the expanded expression
	 */
	public static LispVal expandAdjoin(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol item = new LispSymbol("__adjoin_item");
		LispSymbol lst = new LispSymbol("__adjoin_lst");
		// (let ((__adjoin_item item) (__adjoin_lst lst))
		// (if (member __adjoin_item __adjoin_lst) __adjoin_lst
		// (cons __adjoin_item __adjoin_lst)))
		LispVal memberTest = listToCons(List.of(new LispSymbol(LispNames.MEMBER), item, lst));
		LispVal consCall = listToCons(List.of(new LispSymbol(LispNames.CONS), item, lst));
		LispVal ifExpr = makeIf(memberTest, lst, consCall);
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(item, parts.get(1))), listToCons(List.of(lst, parts.get(2)))));
		return listToCons(List.of(new LispSymbol(LispNames.LET), bindings, ifExpr));
	}

	/**
	 * Expands (union a b) into a do scan that starts from {@code a} and prepends each
	 * element of {@code b} not already present (compared with {@code eql}). The result
	 * order is implementation-defined (CL leaves it unspecified).
	 * @param cons the union expression
	 * @return the expanded expression
	 */
	public static LispVal expandUnion(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol cur = new LispSymbol("__un_cur");
		LispSymbol acc = new LispSymbol("__un_acc");
		// (do ((__un_cur b (cdr __un_cur)) (__un_acc a))
		// ((atom __un_cur) __un_acc)
		// (if (member (car __un_cur) __un_acc) nil
		// (setq __un_acc (cons (car __un_cur) __un_acc))))
		LispVal bindings = listToCons(List.of(listToCons(List.of(cur, parts.get(2), callOf(LispNames.CDR, cur))),
				listToCons(List.of(acc, parts.get(1)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), acc));
		LispVal elem = callOf(LispNames.CAR, cur);
		LispVal match = listToCons(List.of(new LispSymbol(LispNames.MEMBER), elem, acc));
		LispVal prepend = listToCons(List.of(new LispSymbol(LispNames.SETQ), acc,
				listToCons(List.of(new LispSymbol(LispNames.CONS), elem, acc))));
		LispVal body = makeIf(match, LispNil.INSTANCE, prepend);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (intersection a b) into a do scan that collects each element of {@code a}
	 * that is a member of {@code b} (compared with {@code eql}). The result order is
	 * implementation-defined (CL leaves it unspecified).
	 * @param cons the intersection expression
	 * @return the expanded expression
	 */
	public static LispVal expandIntersection(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol cur = new LispSymbol("__in_cur");
		LispSymbol b = new LispSymbol("__in_b");
		LispSymbol acc = new LispSymbol("__in_acc");
		// (do ((__in_cur a (cdr __in_cur)) (__in_b b) (__in_acc nil))
		// ((atom __in_cur) __in_acc)
		// (if (member (car __in_cur) __in_b)
		// (setq __in_acc (cons (car __in_cur) __in_acc)) nil))
		LispVal bindings = listToCons(List.of(listToCons(List.of(cur, parts.get(1), callOf(LispNames.CDR, cur))),
				listToCons(List.of(b, parts.get(2))), listToCons(List.of(acc, LispNil.INSTANCE))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), acc));
		LispVal elem = callOf(LispNames.CAR, cur);
		LispVal match = listToCons(List.of(new LispSymbol(LispNames.MEMBER), elem, b));
		LispVal collect = listToCons(List.of(new LispSymbol(LispNames.SETQ), acc,
				listToCons(List.of(new LispSymbol(LispNames.CONS), elem, acc))));
		LispVal body = makeIf(match, collect, LispNil.INSTANCE);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (set-difference a b) into a do scan that collects each element of {@code a}
	 * that is not a member of {@code b} (compared with {@code eql}). The result order is
	 * implementation-defined (CL leaves it unspecified).
	 * @param cons the set-difference expression
	 * @return the expanded expression
	 */
	public static LispVal expandSetDifference(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol cur = new LispSymbol("__sd_cur");
		LispSymbol b = new LispSymbol("__sd_b");
		LispSymbol acc = new LispSymbol("__sd_acc");
		// (do ((__sd_cur a (cdr __sd_cur)) (__sd_b b) (__sd_acc nil))
		// ((atom __sd_cur) __sd_acc)
		// (if (member (car __sd_cur) __sd_b) nil
		// (setq __sd_acc (cons (car __sd_cur) __sd_acc))))
		LispVal bindings = listToCons(List.of(listToCons(List.of(cur, parts.get(1), callOf(LispNames.CDR, cur))),
				listToCons(List.of(b, parts.get(2))), listToCons(List.of(acc, LispNil.INSTANCE))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), acc));
		LispVal elem = callOf(LispNames.CAR, cur);
		LispVal match = listToCons(List.of(new LispSymbol(LispNames.MEMBER), elem, b));
		LispVal collect = listToCons(List.of(new LispSymbol(LispNames.SETQ), acc,
				listToCons(List.of(new LispSymbol(LispNames.CONS), elem, acc))));
		LispVal body = makeIf(match, LispNil.INSTANCE, collect);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (every pred lst) into a do/return scan that yields t when the predicate
	 * holds for every element and nil at the first element for which it fails.
	 * @param cons the every expression
	 * @return the expanded expression
	 */
	public static LispVal expandEvery(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol pred = new LispSymbol("__every_pred");
		LispSymbol cur = new LispSymbol("__every_cur");
		// (do ((__every_pred pred) (__every_cur lst (cdr __every_cur)))
		// ((atom __every_cur) t)
		// (if (funcall __every_pred (car __every_cur)) nil (return nil)))
		LispVal bindings = listToCons(List.of(listToCons(List.of(pred, parts.get(1))),
				listToCons(List.of(cur, parts.get(2), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), LispTrue.INSTANCE));
		LispVal test = listToCons(List.of(new LispSymbol(LispNames.FUNCALL), pred, callOf(LispNames.CAR, cur)));
		LispVal body = makeIf(test, LispNil.INSTANCE, makeReturn(LispNil.INSTANCE));
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (some pred lst) into a do/return scan that yields the first non-nil
	 * predicate result, or nil when the predicate fails for every element.
	 * @param cons the some expression
	 * @return the expanded expression
	 */
	public static LispVal expandSome(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol pred = new LispSymbol("__some_pred");
		LispSymbol res = new LispSymbol("__some_res");
		LispSymbol cur = new LispSymbol("__some_cur");
		// (do ((__some_pred pred) (__some_res nil) (__some_cur lst (cdr __some_cur)))
		// ((atom __some_cur) nil)
		// (setq __some_res (funcall __some_pred (car __some_cur)))
		// (if __some_res (return __some_res) nil))
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(pred, parts.get(1))), listToCons(List.of(res, LispNil.INSTANCE)),
						listToCons(List.of(cur, parts.get(2), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), LispNil.INSTANCE));
		LispVal call = listToCons(List.of(new LispSymbol(LispNames.FUNCALL), pred, callOf(LispNames.CAR, cur)));
		LispVal assign = listToCons(List.of(new LispSymbol(LispNames.SETQ), res, call));
		LispVal check = makeIf(res, makeReturn(res), LispNil.INSTANCE);
		return expandDo(
				(LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, assign, check)));
	}

	/**
	 * Expands (remove item lst) into a do scan that accumulates, then reverses, the
	 * elements that are not {@code eql} to the item.
	 * @param cons the remove expression
	 * @return the expanded expression
	 */
	public static LispVal expandRemove(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol item = new LispSymbol("__remove_item");
		return expandFilter(item, parts.get(1), parts.get(2), "__remove", LispNames.EQL, item, false);
	}

	/**
	 * Expands (remove-if pred lst) into a do scan that accumulates, then reverses, the
	 * elements for which the predicate fails.
	 * @param cons the remove-if expression
	 * @return the expanded expression
	 */
	public static LispVal expandRemoveIf(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol pred = new LispSymbol("__removeif_pred");
		return expandFilter(pred, parts.get(1), parts.get(2), "__removeif", LispNames.FUNCALL, pred, false);
	}

	/**
	 * Expands (remove-if-not pred lst) into a do scan that accumulates, then reverses,
	 * the elements for which the predicate holds (the complement of {@code remove-if}).
	 * @param cons the remove-if-not expression
	 * @return the expanded expression
	 */
	public static LispVal expandRemoveIfNot(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol pred = new LispSymbol("__removeifnot_pred");
		return expandFilter(pred, parts.get(1), parts.get(2), "__removeifnot", LispNames.FUNCALL, pred, true);
	}

	/**
	 * Expands (substitute new old lst) into a do scan that accumulates, then reverses, a
	 * copy of the list with every element {@code eql} to {@code old} replaced by
	 * {@code new}.
	 * @param cons the substitute expression
	 * @return the expanded expression
	 */
	public static LispVal expandSubstitute(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol newItem = new LispSymbol("__subst_new");
		LispSymbol oldItem = new LispSymbol("__subst_old");
		LispSymbol acc = new LispSymbol("__subst_acc");
		LispSymbol cur = new LispSymbol("__subst_cur");
		// (do ((__subst_new new) (__subst_old old) (__subst_acc nil)
		// (__subst_cur lst (cdr __subst_cur)))
		// ((atom __subst_cur) (reverse __subst_acc))
		// (setq __subst_acc
		// (cons (if (eql __subst_old (car __subst_cur)) __subst_new (car __subst_cur))
		// __subst_acc)))
		LispVal bindings = listToCons(List.of(listToCons(List.of(newItem, parts.get(1))),
				listToCons(List.of(oldItem, parts.get(2))), listToCons(List.of(acc, LispNil.INSTANCE)),
				listToCons(List.of(cur, parts.get(3), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), callOf(LispNames.REVERSE, acc)));
		LispVal match = listToCons(List.of(new LispSymbol(LispNames.EQL), oldItem, callOf(LispNames.CAR, cur)));
		LispVal chosen = makeIf(match, newItem, callOf(LispNames.CAR, cur));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.SETQ), acc,
				listToCons(List.of(new LispSymbol(LispNames.CONS), chosen, acc))));
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (nsubstitute new old lst) into an in-place scan that rewrites the
	 * {@code car} of every cons whose value is {@code eql} to {@code old} with
	 * {@code new}, returning the (possibly mutated) original list. This is destructive:
	 * the argument's cons cells are reused (Common Lisp semantics).
	 * @param cons the nsubstitute expression
	 * @return the expanded expression
	 */
	public static LispVal expandNsubstitute(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol newItem = new LispSymbol("__nsub_new");
		LispSymbol oldItem = new LispSymbol("__nsub_old");
		LispSymbol lst = new LispSymbol("__nsub_lst");
		LispSymbol cur = new LispSymbol("__nsub_cur");
		// (let ((__nsub_new new) (__nsub_old old) (__nsub_lst lst) (__nsub_cur nil))
		// (setq __nsub_cur __nsub_lst)
		// (while (consp __nsub_cur)
		// (if (eql __nsub_old (car __nsub_cur)) (rplaca __nsub_cur __nsub_new) nil)
		// (setq __nsub_cur (cdr __nsub_cur)))
		// __nsub_lst)
		LispVal initCur = listToCons(List.of(new LispSymbol(LispNames.SETQ), cur, lst));
		LispVal whileTest = listToCons(List.of(new LispSymbol(LispNames.CONSP), cur));
		LispVal match = listToCons(List.of(new LispSymbol(LispNames.EQL), oldItem, callOf(LispNames.CAR, cur)));
		LispVal replace = listToCons(List.of(new LispSymbol(LispNames.RPLACA), cur, newItem));
		LispVal ifExpr = makeIf(match, replace, LispNil.INSTANCE);
		LispVal advance = listToCons(List.of(new LispSymbol(LispNames.SETQ), cur, callOf(LispNames.CDR, cur)));
		LispVal whileExpr = listToCons(List.of(new LispSymbol(LispNames.WHILE), whileTest, ifExpr, advance));
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(newItem, parts.get(1))), listToCons(List.of(oldItem, parts.get(2))),
						listToCons(List.of(lst, parts.get(3))), listToCons(List.of(cur, LispNil.INSTANCE))));
		return listToCons(List.of(new LispSymbol(LispNames.LET), bindings, initCur, whileExpr, lst));
	}

	/**
	 * Expands (delete item lst) into a destructive splice that removes every cons whose
	 * {@code car} is {@code eql} to {@code item}, reusing the surviving cons cells.
	 * @param cons the delete expression
	 * @return the expanded expression
	 */
	public static LispVal expandDelete(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol item = new LispSymbol("__delete_item");
		return expandDeleteFilter(item, parts.get(1), parts.get(2), "__delete", LispNames.EQL, item, true);
	}

	/**
	 * Expands (delete-if pred lst) into a destructive splice that removes every cons
	 * whose {@code car} satisfies the predicate, reusing the surviving cons cells.
	 * @param cons the delete-if expression
	 * @return the expanded expression
	 */
	public static LispVal expandDeleteIf(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol pred = new LispSymbol("__deleteif_pred");
		return expandDeleteFilter(pred, parts.get(1), parts.get(2), "__deleteif", LispNames.FUNCALL, pred, true);
	}

	/**
	 * Expands (delete-if-not pred lst) into a destructive splice that removes every cons
	 * whose {@code car} fails the predicate, reusing the surviving cons cells.
	 * @param cons the delete-if-not expression
	 * @return the expanded expression
	 */
	public static LispVal expandDeleteIfNot(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol pred = new LispSymbol("__deleteifnot_pred");
		return expandDeleteFilter(pred, parts.get(1), parts.get(2), "__deleteifnot", LispNames.FUNCALL, pred, false);
	}

	/**
	 * Shared destructive expansion for
	 * {@code delete}/{@code delete-if}/{@code delete-if-not}: bind the test operand once,
	 * drop matching cons cells from the front by advancing the head, then splice out
	 * matching cons cells in the interior with {@code rplacd}. The deletion test is
	 * {@code (matchOp matchArg (car cursor))}; when {@code deleteWhenMatch} is false the
	 * cons is deleted when the test is false ({@code delete-if-not}). The surviving cons
	 * cells are reused (Common Lisp semantics; use the return value).
	 */
	private static LispVal expandDeleteFilter(LispSymbol operand, LispVal operandInit, LispVal list, String prefix,
			String matchOp, LispVal matchArg, boolean deleteWhenMatch) {
		LispSymbol head = new LispSymbol(prefix + "_head");
		LispSymbol prev = new LispSymbol(prefix + "_prev");
		LispSymbol cur = new LispSymbol(prefix + "_cur");
		LispVal match = listToCons(List.of(new LispSymbol(matchOp), matchArg, callOf(LispNames.CAR, cur)));
		LispVal deleteForm = deleteWhenMatch ? match : listToCons(List.of(new LispSymbol(LispNames.NOT), match));
		// (let ((operand operandInit) (head list) (prev nil) (cur nil))
		// (while (and (consp head) deleteFormOnHead) (setq head (cdr head))) ; drop
		// leading
		// (setq prev head)
		// (if (consp head) (setq cur (cdr head)) nil)
		// (while (consp cur)
		// (if deleteFormOnCur (rplacd prev (cdr cur)) (setq prev cur))
		// (setq cur (cdr cur)))
		// head)
		LispVal deleteOnHead = substituteCursor(deleteForm, cur, head);
		LispVal leadTest = listToCons(List.of(new LispSymbol(LispNames.AND),
				listToCons(List.of(new LispSymbol(LispNames.CONSP), head)), deleteOnHead));
		LispVal leadStep = listToCons(List.of(new LispSymbol(LispNames.SETQ), head, callOf(LispNames.CDR, head)));
		LispVal leadWhile = listToCons(List.of(new LispSymbol(LispNames.WHILE), leadTest, leadStep));
		LispVal setPrev = listToCons(List.of(new LispSymbol(LispNames.SETQ), prev, head));
		LispVal initCur = makeIf(listToCons(List.of(new LispSymbol(LispNames.CONSP), head)),
				listToCons(List.of(new LispSymbol(LispNames.SETQ), cur, callOf(LispNames.CDR, head))),
				LispNil.INSTANCE);
		LispVal mainTest = listToCons(List.of(new LispSymbol(LispNames.CONSP), cur));
		LispVal splice = listToCons(List.of(new LispSymbol(LispNames.RPLACD), prev, callOf(LispNames.CDR, cur)));
		LispVal keepPrev = listToCons(List.of(new LispSymbol(LispNames.SETQ), prev, cur));
		LispVal mainIf = makeIf(deleteForm, splice, keepPrev);
		LispVal mainAdvance = listToCons(List.of(new LispSymbol(LispNames.SETQ), cur, callOf(LispNames.CDR, cur)));
		LispVal mainWhile = listToCons(List.of(new LispSymbol(LispNames.WHILE), mainTest, mainIf, mainAdvance));
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(operand, operandInit)), listToCons(List.of(head, list)),
						listToCons(List.of(prev, LispNil.INSTANCE)), listToCons(List.of(cur, LispNil.INSTANCE))));
		return listToCons(
				List.of(new LispSymbol(LispNames.LET), bindings, leadWhile, setPrev, initCur, mainWhile, head));
	}

	/**
	 * Returns a copy of {@code form} (a deletion test referencing {@code (car from)})
	 * rebuilt to reference {@code (car to)} instead, so the same test can run against the
	 * head cursor and the interior cursor. Only the {@code (car from)} subforms are
	 * rewritten; everything else is shared.
	 */
	private static LispVal substituteCursor(LispVal form, LispSymbol from, LispSymbol to) {
		if (form instanceof LispCons cell) {
			List<LispVal> parts = cell.toList();
			// Replace the exact subform (car from) with (car to).
			if (parts.size() == 2 && parts.get(0) instanceof LispSymbol op && LispNames.CAR.equals(op.name())
					&& parts.get(1) instanceof LispSymbol s && from.name().equals(s.name())) {
				return callOf(LispNames.CAR, to);
			}
			List<LispVal> rebuilt = new java.util.ArrayList<>();
			for (LispVal part : parts) {
				rebuilt.add(substituteCursor(part, from, to));
			}
			return listToCons(rebuilt);
		}
		return form;
	}

	/**
	 * Shared expansion for {@code remove}/{@code remove-if}/{@code remove-if-not}: bind
	 * the test operand, walk the list, and accumulate (in reverse) every element to keep.
	 * The result form reverses the accumulator back to source order. The match form is
	 * {@code (matchOp matchArg (car cursor))}; for {@code remove} it is
	 * {@code (eql item (car cursor))} and for the {@code -if} variants it is
	 * {@code (funcall pred (car cursor))}. When {@code keepWhenMatch} is false an element
	 * is kept when the match is false ({@code remove}/{@code remove-if}); when true it is
	 * kept when the match is true ({@code remove-if-not}).
	 */
	private static LispVal expandFilter(LispSymbol operand, LispVal operandInit, LispVal list, String prefix,
			String matchOp, LispVal matchArg, boolean keepWhenMatch) {
		LispSymbol acc = new LispSymbol(prefix + "_acc");
		LispSymbol cur = new LispSymbol(prefix + "_cur");
		LispVal match = listToCons(List.of(new LispSymbol(matchOp), matchArg, callOf(LispNames.CAR, cur)));
		// (do ((operand operandInit) (acc nil) (cur list (cdr cur)))
		// ((atom cur) (reverse acc))
		// (if match nil (setq acc (cons (car cur) acc)))) ; keepWhenMatch swaps the
		// branches
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(operand, operandInit)), listToCons(List.of(acc, LispNil.INSTANCE)),
						listToCons(List.of(cur, list, callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), callOf(LispNames.REVERSE, acc)));
		LispVal keep = listToCons(List.of(new LispSymbol(LispNames.SETQ), acc,
				listToCons(List.of(new LispSymbol(LispNames.CONS), callOf(LispNames.CAR, cur), acc))));
		LispVal body = keepWhenMatch ? makeIf(match, keep, LispNil.INSTANCE) : makeIf(match, LispNil.INSTANCE, keep);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
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

	/**
	 * Expands (with-open-file (var filename options...) body...) into open/close calls.
	 * The only supported option is {@code :direction} with a literal {@code :input}
	 * (default) or {@code :output} value, so the direction is known at expansion time and
	 * the compilers can pick the file mode statically.
	 *
	 * <pre>
	 * (with-open-file (s "f.txt" :direction :output) body...) ->
	 *   (let ((s (open "f.txt" :output)))
	 *     (let ((__wof_result (progn body...)))
	 *       (close s)
	 *       __wof_result))
	 * </pre>
	 * @param cons the with-open-file expression
	 * @return the expanded expression
	 */
	public static LispVal expandWithOpenFile(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || !(parts.get(1) instanceof LispCons spec)) {
			throw new IllegalArgumentException(
					LispNames.WITH_OPEN_FILE + " expects a (var filename options...) spec as the first argument");
		}
		List<LispVal> specParts = spec.toList();
		if (specParts.size() < 2) {
			throw new IllegalArgumentException(
					LispNames.WITH_OPEN_FILE + " expects a (var filename options...) spec as the first argument");
		}
		LispVal var = specParts.get(0);
		LispVal filename = specParts.get(1);
		String direction = LispNames.INPUT_KEYWORD;
		for (int i = 2; i < specParts.size(); i += 2) {
			if (!(specParts.get(i) instanceof LispSymbol key) || !LispNames.DIRECTION_KEYWORD.equals(key.name())) {
				throw new UnsupportedOperationException(
						LispNames.WITH_OPEN_FILE + " supports only the :direction option");
			}
			if (i + 1 >= specParts.size() || !(specParts.get(i + 1) instanceof LispSymbol dir)
					|| !(LispNames.INPUT_KEYWORD.equals(dir.name()) || LispNames.OUTPUT_KEYWORD.equals(dir.name()))) {
				throw new UnsupportedOperationException(
						LispNames.WITH_OPEN_FILE + " :direction must be the literal :input or :output");
			}
			direction = dir.name();
		}
		List<LispVal> body = parts.subList(2, parts.size());
		LispVal openCall = listToCons(List.of(new LispSymbol(LispNames.OPEN), filename, new LispSymbol(direction)));
		// (progn body...) -- nil for an empty body (a body-less progn does not compile)
		LispVal bodyExpr;
		if (body.isEmpty()) {
			bodyExpr = LispNil.INSTANCE;
		}
		else {
			List<LispVal> prognParts = new java.util.ArrayList<>();
			prognParts.add(new LispSymbol(LispNames.PROGN));
			prognParts.addAll(body);
			bodyExpr = listToCons(prognParts);
		}
		LispSymbol result = new LispSymbol(WOF_RESULT_VAR);
		// (let ((__wof_result body-expr)) (close var) __wof_result)
		LispVal innerBindings = new LispCons(listToCons(List.of(result, bodyExpr)), LispNil.INSTANCE);
		LispVal innerLet = listToCons(
				List.of(new LispSymbol(LispNames.LET), innerBindings, callOf(LispNames.CLOSE, var), result));
		// (let ((var (open filename direction))) inner-let)
		LispVal outerBindings = new LispCons(listToCons(List.of(var, openCall)), LispNil.INSTANCE);
		return listToCons(List.of(new LispSymbol(LispNames.LET), outerBindings, innerLet));
	}

	private static final String WOF_RESULT_VAR = "__wof_result";

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
	 * Expands {@code (defstruct name slot...)} into the defuns it generates -- a keyword
	 * constructor, a predicate, a copier and one accessor per slot -- and records each
	 * accessor's slot position in {@code structAccessors} so
	 * {@link #expandSetf(LispCons, java.util.Map)} treats accessor calls as places. An
	 * instance is a tagged proper list {@code (%struct-<name> value...)}.
	 *
	 * <pre>
	 * (defstruct point x (y 10)) ->
	 * (defun make-point (&amp;key ((:x x) nil) ((:y y) 10)) (list '%struct-point x y))
	 * (defun point-p (__struct) (if (consp __struct) (equal (car __struct) '%struct-point) nil))
	 * (defun copy-point (__struct) (copy-list __struct))
	 * (defun point-x (__struct) (nth 1 __struct))
	 * (defun point-y (__struct) (nth 2 __struct))
	 * </pre>
	 *
	 * The form is expected in its canonical (package-resolved) spelling; a qualified
	 * struct name derives internal (double-colon) generated names, e.g.
	 * {@code (defstruct foo::point x)} defines {@code foo::make-point},
	 * {@code foo::point-p}, {@code foo::copy-point} and {@code foo::point-x}, and the
	 * constructor keywords come from the unqualified slot names ({@code :x}). defstruct
	 * options ({@code (defstruct (name (:conc-name ...) ...) ...)}) are not supported.
	 * @param cons the defstruct expression
	 * @param structAccessors mutated: accessor name to 1-based slot position in the
	 * tagged list
	 * @return the generated top-level forms, in definition order
	 */
	public static List<LispVal> expandDefstruct(LispCons cons, java.util.Map<String, Integer> structAccessors) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException(LispNames.DEFSTRUCT + " expects a struct name: " + cons.print());
		}
		if (!(parts.get(1) instanceof LispSymbol nameSym)) {
			throw new UnsupportedOperationException(
					LispNames.DEFSTRUCT + " options are not supported: " + parts.get(1).print());
		}
		String structName = nameSym.name();
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(structName);
		// Generated names of a qualified struct are interned as internal symbols
		// (double colon), matching how the resolver canonicalizes their call sites.
		String prefix = qn == null ? "" : qn.pkg() + "::";
		String base = qn == null ? structName : qn.member();
		LispVal quotedTag = listToCons(
				List.of(new LispSymbol(LispNames.QUOTE), new LispSymbol("%struct-" + structName)));
		List<LispSymbol> slotSyms = new java.util.ArrayList<>();
		List<String> slotBases = new java.util.ArrayList<>();
		List<LispVal> slotDefaults = new java.util.ArrayList<>();
		for (LispVal spec : parts.subList(2, parts.size())) {
			LispSymbol slot;
			LispVal dflt = LispNil.INSTANCE;
			if (spec instanceof LispSymbol s) {
				slot = s;
			}
			else if (spec instanceof LispCons specCons && specCons.car() instanceof LispSymbol s
					&& specCons.toList().size() <= 2) {
				slot = s;
				List<LispVal> specParts = specCons.toList();
				if (specParts.size() == 2) {
					dflt = specParts.get(1);
				}
			}
			else {
				throw new IllegalArgumentException(
						LispNames.DEFSTRUCT + " expects a slot name or (name default), got " + spec.print());
			}
			PackageRegistry.QualifiedName slotQn = PackageRegistry.splitQualified(slot.name());
			slotSyms.add(slot);
			slotBases.add(slotQn == null ? slot.name() : slotQn.member());
			slotDefaults.add(dflt);
		}
		List<LispVal> forms = new java.util.ArrayList<>();
		// (defun make-<base> (&key ((:slot slot) default)...) (list '%struct-<name>
		// slot...))
		List<LispVal> lambdaList = new java.util.ArrayList<>();
		if (!slotSyms.isEmpty()) {
			lambdaList.add(new LispSymbol(LispNames.LAMBDA_KEY));
			for (int i = 0; i < slotSyms.size(); i++) {
				LispVal keywordAndVar = listToCons(List.of(new LispSymbol(":" + slotBases.get(i)), slotSyms.get(i)));
				lambdaList.add(listToCons(List.of(keywordAndVar, slotDefaults.get(i))));
			}
		}
		List<LispVal> listCall = new java.util.ArrayList<>();
		listCall.add(new LispSymbol(LispNames.LIST));
		listCall.add(quotedTag);
		listCall.addAll(slotSyms);
		forms.add(listToCons(List.of(new LispSymbol(LispNames.DEFUN), new LispSymbol(prefix + "make-" + base),
				listToCons(lambdaList), listToCons(listCall))));
		// (defun <base>-p (__struct) (if (consp __struct) (equal (car __struct)
		// '%struct-<name>) nil))
		LispSymbol obj = new LispSymbol(STRUCT_VAR);
		LispVal params = listToCons(List.<LispVal>of(obj));
		LispVal tagCheck = makeIf(listToCons(List.of(new LispSymbol(LispNames.CONSP), obj)), listToCons(List
			.of(new LispSymbol(LispNames.EQUAL), listToCons(List.of(new LispSymbol(LispNames.CAR), obj)), quotedTag)),
				LispNil.INSTANCE);
		forms.add(listToCons(
				List.of(new LispSymbol(LispNames.DEFUN), new LispSymbol(prefix + base + "-p"), params, tagCheck)));
		// (defun copy-<base> (__struct) (copy-list __struct))
		forms.add(listToCons(List.of(new LispSymbol(LispNames.DEFUN), new LispSymbol(prefix + "copy-" + base), params,
				listToCons(List.of(new LispSymbol(LispNames.COPY_LIST), obj)))));
		// (defun <base>-<slot> (__struct) (nth <position> __struct))
		for (int i = 0; i < slotSyms.size(); i++) {
			String accessor = prefix + base + "-" + slotBases.get(i);
			forms.add(listToCons(List.of(new LispSymbol(LispNames.DEFUN), new LispSymbol(accessor), params,
					listToCons(List.of(new LispSymbol(LispNames.NTH), new LispInteger(i + 1), obj)))));
			structAccessors.put(accessor, i + 1);
		}
		return forms;
	}

	private static final String STRUCT_VAR = "__struct";

	/**
	 * Splices every top-level {@code (defstruct ...)} of a program into its generated
	 * defuns (see {@link #expandDefstruct}). The compilers run this after package
	 * resolution and before lambda-list desugaring, so Pass 1 collects the generated
	 * defuns as ordinary top-level functions; a defstruct anywhere else is rejected by
	 * the expression compilers. Returns the program unchanged when it has no defstruct.
	 * @param program the top-level forms
	 * @param structAccessors mutated: accessor name to 1-based slot position
	 * @return the program with each defstruct replaced by its generated defuns
	 */
	public static List<LispVal> expandTopLevelDefstructs(List<LispVal> program,
			java.util.Map<String, Integer> structAccessors) {
		if (program.stream().noneMatch(LispMacroExpander::isDefstructForm)) {
			return program;
		}
		List<LispVal> out = new java.util.ArrayList<>(program.size());
		for (LispVal form : program) {
			if (isDefstructForm(form)) {
				out.addAll(expandDefstruct((LispCons) form, structAccessors));
			}
			else {
				out.add(form);
			}
		}
		return out;
	}

	private static boolean isDefstructForm(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol sym
				&& LispNames.DEFSTRUCT.equals(sym.name());
	}

	/**
	 * Expands (format destination control-string args...). The control string must be a
	 * literal string and the destination must be the literal {@code t} (print to standard
	 * output, return nil) or {@code nil} (return the formatted string). Supported
	 * directives: {@code ~a}/{@code ~A} (princ), {@code ~s}/{@code ~S} (prin1),
	 * {@code ~d}/{@code ~D} (decimal, with {@code :} comma grouping and {@code @} sign),
	 * {@code ~f}/{@code ~F} (fixed-decimal float), {@code ~e}/{@code ~E} (exponential
	 * float), {@code ~$} (monetary), {@code ~%} (newline), {@code ~&} (fresh-line) and
	 * {@code ~~} (a literal tilde). Directives accept prefix parameters (numbers,
	 * {@code 'c}, {@code v}, {@code #}) and the {@code :}/{@code @} modifiers; value
	 * directives that need padding/grouping/rounding expand into the {@code %fmt-*}
	 * runtime helpers.
	 *
	 * <pre>
	 * (format t "Hello ~a!~%" name) ->
	 * (let ((__format_arg0 name))
	 *   (princ "Hello ")
	 *   (princ __format_arg0)
	 *   (princ "!")
	 *   (terpri)
	 *   nil)
	 *
	 * (format nil "Hello ~a!" name) ->
	 * (let ((__format_arg0 name))
	 *   (%string-concat (%string-concat "Hello " (princ-to-string __format_arg0)) "!"))
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
		boolean toString;
		if (parts.get(1) instanceof LispTrue) {
			toString = false;
		}
		else if (parts.get(1) instanceof LispNil) {
			toString = true;
		}
		else {
			throw new UnsupportedOperationException(
					"format supports only t (standard output) or nil (string) as destination, got: "
							+ parts.get(1).print());
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
		List<FmtOp> parsed = new FmtParser(control.value()).parseTop(FmtArgs.forTemps(argSyms));
		List<LispVal> forms = toString ? opsToPieces(parsed) : formatOutputForms(parsed);
		if (toString) {
			// Fold the pieces into nested %string-concat calls (left-associative).
			LispVal result = forms.isEmpty() ? new LispString("") : forms.get(0);
			for (int i = 1; i < forms.size(); i++) {
				result = listToCons(List.of(new LispSymbol(LispNames.STRING_CONCAT), result, forms.get(i)));
			}
			forms = new java.util.ArrayList<>(List.of(result));
		}
		else {
			// format t returns nil
			forms = new java.util.ArrayList<>(forms);
			forms.add(LispNil.INSTANCE);
		}
		if (bindings.isEmpty()) {
			return forms.size() == 1 ? forms.get(0) : makeProgn(forms);
		}
		List<LispVal> letParts = new java.util.ArrayList<>();
		letParts.add(new LispSymbol(LispNames.LET));
		letParts.add(listToCons(bindings));
		letParts.addAll(forms);
		return listToCons(letParts);
	}

	private static final String FORMAT_ARG_VAR = "__format_arg";

	/**
	 * A parsed unit of a format control string: either a literal text run, a
	 * string-valued expression to emit, a run of newlines, or a fresh-line.
	 */
	private sealed interface FmtOp permits FmtLiteral, FmtString, FmtNewline, FmtFreshLine {

	}

	private record FmtLiteral(String text) implements FmtOp {
	}

	private record FmtString(LispVal expr) implements FmtOp {
	}

	private record FmtNewline(int count) implements FmtOp {
	}

	private record FmtFreshLine(int count) implements FmtOp {
	}

	/**
	 * A cursor over the arguments a format control string consumes. At the top level the
	 * arguments are the pre-bound {@code __format_arg} temporaries ("static" source, with
	 * a known count); inside a {@code ~{ ... ~}} iteration body they are per-iteration
	 * item temporaries allocated on demand and later bound to successive list elements
	 * ("runtime" source, with an unknown count). Conditional clauses parse against a
	 * {@link #branch()} of the cursor so each clause sees the same starting position.
	 */
	private static final class FmtArgs {

		private final List<LispSymbol> syms;

		@Nullable private final String runtimePrefix;

		private int index;

		private FmtArgs(List<LispSymbol> syms, @Nullable String runtimePrefix, int index) {
			this.syms = syms;
			this.runtimePrefix = runtimePrefix;
			this.index = index;
		}

		static FmtArgs forTemps(List<LispSymbol> temps) {
			return new FmtArgs(temps, null, 0);
		}

		static FmtArgs forRuntimeItems(String prefix) {
			return new FmtArgs(new java.util.ArrayList<>(), prefix, 0);
		}

		boolean isStatic() {
			return this.runtimePrefix == null;
		}

		LispSymbol next(char directive) {
			if (this.runtimePrefix == null && this.index >= this.syms.size()) {
				throw new IllegalArgumentException("format: not enough arguments for directive ~" + directive);
			}
			while (this.syms.size() <= this.index) {
				this.syms.add(new LispSymbol(this.runtimePrefix + this.syms.size()));
			}
			return this.syms.get(this.index++);
		}

		int position() {
			return this.index;
		}

		void move(int delta, char directive) {
			int to = this.index + delta;
			if (to < 0 || (this.runtimePrefix == null && to > this.syms.size())) {
				throw new IllegalArgumentException("format: ~" + directive + " moves outside the argument list");
			}
			this.index = to;
		}

		int remaining(char directive) {
			if (this.runtimePrefix != null) {
				throw new UnsupportedOperationException(
						"format: " + directive + " is not supported inside a ~{ iteration body");
			}
			return this.syms.size() - this.index;
		}

		FmtArgs branch() {
			return new FmtArgs(this.syms, this.runtimePrefix, this.index);
		}

		FmtArgs branchAt(int index) {
			return new FmtArgs(this.syms, this.runtimePrefix, index);
		}

		void adoptIndex(int index) {
			this.index = index;
		}

		/** Every item temporary this (runtime) source handed out, in index order. */
		List<LispSymbol> items() {
			return this.syms;
		}

	}

	/** A parsed conditional clause: its ops and the argument position it ended at. */
	private record FmtClause(List<FmtOp> ops, int end, boolean isDefault) {
	}

	/**
	 * Recursive-descent parser for a format control string. Parses directives into
	 * {@link FmtOp}s, consuming format arguments (left to right) through a
	 * {@link FmtArgs} cursor as it encounters value directives and {@code v}/{@code #}
	 * prefix parameters. Composite directives (case conversion {@code ~(...~)},
	 * conditionals {@code ~[...~]}, iteration {@code ~{...~}}) parse their bodies
	 * recursively and collapse to a single string-valued {@link FmtString}.
	 */
	private static final class FmtParser {

		private final String src;

		private int pos;

		private int gensym;

		/** The terminator directive that ended the last {@link #parseOps} call. */
		private char stopChar;

		/** Whether that terminator carried a {@code :} modifier (for {@code ~:;}). */
		private boolean stopColon;

		FmtParser(String src) {
			this.src = src;
		}

		List<FmtOp> parseTop(FmtArgs args) {
			return parseOps(args, "");
		}

		/**
		 * Parses ops until the end of the control string (when {@code stoppers} is empty)
		 * or until one of the {@code stoppers} directives ({@code ~;}, {@code ~]},
		 * {@code ~&#125;}, {@code ~)}) is read, recording it in {@link #stopChar}/
		 * {@link #stopColon}.
		 */
		private List<FmtOp> parseOps(FmtArgs args, String stoppers) {
			List<FmtOp> ops = new java.util.ArrayList<>();
			StringBuilder lit = new StringBuilder();
			this.stopChar = 0;
			this.stopColon = false;
			int n = this.src.length();
			while (this.pos < n) {
				char c = this.src.charAt(this.pos);
				if (c != '~') {
					lit.append(c);
					this.pos++;
					continue;
				}
				this.pos++; // skip '~'
				List<LispVal> params = parseParams(args);
				boolean colon = false;
				boolean at = false;
				while (this.pos < n && (this.src.charAt(this.pos) == ':' || this.src.charAt(this.pos) == '@')) {
					if (this.src.charAt(this.pos) == ':') {
						colon = true;
					}
					else {
						at = true;
					}
					this.pos++;
				}
				if (this.pos >= n) {
					throw new IllegalArgumentException("format: control string ends with ~");
				}
				char directive = this.src.charAt(this.pos++);
				if (stoppers.indexOf(directive) >= 0) {
					flushFmtLiteral(lit, ops);
					this.stopChar = directive;
					this.stopColon = colon;
					return ops;
				}
				dispatch(directive, params, colon, at, args, ops, lit);
			}
			if (!stoppers.isEmpty()) {
				throw new IllegalArgumentException(
						"format: missing ~" + stoppers.charAt(stoppers.length() - 1) + " in control string");
			}
			flushFmtLiteral(lit, ops);
			return ops;
		}

		/** Parses the comma-separated prefix parameters (numbers, 'char, v, #). */
		private List<LispVal> parseParams(FmtArgs args) {
			List<LispVal> params = new java.util.ArrayList<>();
			int n = this.src.length();
			if (this.pos >= n || (!isParamStart(this.src.charAt(this.pos)) && this.src.charAt(this.pos) != ',')) {
				return params;
			}
			while (true) {
				char p = (this.pos < n) ? this.src.charAt(this.pos) : '\0';
				if (p == '\'') {
					if (this.pos + 1 >= n) {
						throw new IllegalArgumentException("format: ~' prefix with no character");
					}
					params.add(new LispInteger(this.src.charAt(this.pos + 1)));
					this.pos += 2;
				}
				else if (p == 'v' || p == 'V') {
					params.add(args.next('v'));
					this.pos++;
				}
				else if (p == '#') {
					params.add(new LispInteger(args.remaining('#')));
					this.pos++;
				}
				else if (p == '-' || Character.isDigit(p)) {
					int start = this.pos;
					if (p == '-') {
						this.pos++;
					}
					while (this.pos < n && Character.isDigit(this.src.charAt(this.pos))) {
						this.pos++;
					}
					params.add(new LispInteger(Long.parseLong(this.src.substring(start, this.pos))));
				}
				else {
					params.add(LispNil.INSTANCE); // omitted parameter slot
				}
				if (this.pos < n && this.src.charAt(this.pos) == ',') {
					this.pos++;
					continue;
				}
				break;
			}
			return params;
		}

		private void dispatch(char directive, List<LispVal> params, boolean colon, boolean at, FmtArgs args,
				List<FmtOp> ops, StringBuilder lit) {
			switch (Character.toLowerCase(directive)) {
				case '~' -> {
					int count = fmtCount(params, 0, 1, directive);
					lit.append("~".repeat(count));
				}
				case '%' -> {
					flushFmtLiteral(lit, ops);
					ops.add(new FmtNewline(fmtCount(params, 0, 1, directive)));
				}
				case '&' -> {
					flushFmtLiteral(lit, ops);
					ops.add(new FmtFreshLine(fmtCount(params, 0, 1, directive)));
				}
				case 'a', 's' -> {
					flushFmtLiteral(lit, ops);
					LispVal arg = args.next(directive);
					String op = (Character.toLowerCase(directive) == 's') ? LispNames.PRIN1_TO_STRING
							: LispNames.PRINC_TO_STRING;
					LispVal base = fmtCall(op, arg);
					if (colon) {
						base = makeIf(arg, base, new LispString("()"));
					}
					if (fmtHasParam(params, 0)) {
						base = padExpr(base, fmtParam(params, 0), fmtPadChar(params, 3, ' '), at);
					}
					ops.add(new FmtString(base));
				}
				case 'd' -> {
					flushFmtLiteral(lit, ops);
					LispVal arg = args.next(directive);
					LispVal base = (colon || at)
							? decimalExpr(arg, colon, fmtCommaChar(params, 2), fmtInterval(params, 3), at)
							: fmtCall(LispNames.PRINC_TO_STRING, arg);
					if (fmtHasParam(params, 0)) {
						base = padExpr(base, fmtParam(params, 0), fmtPadChar(params, 1, ' '), true);
					}
					ops.add(new FmtString(base));
				}
				case 'x', 'o', 'b' -> {
					flushFmtLiteral(lit, ops);
					LispVal arg = args.next(directive);
					int base = switch (Character.toLowerCase(directive)) {
						case 'x' -> 16;
						case 'o' -> 8;
						default -> 2;
					};
					LispVal digits = radixIntegerExpr(arg, new LispInteger(base), colon, fmtCommaChar(params, 2),
							fmtInterval(params, 3), at);
					if (fmtHasParam(params, 0)) {
						digits = padExpr(digits, fmtParam(params, 0), fmtPadChar(params, 1, ' '), true);
					}
					ops.add(new FmtString(digits));
				}
				case 'r' -> {
					flushFmtLiteral(lit, ops);
					if (!fmtHasParam(params, 0)) {
						throw new UnsupportedOperationException(
								"format: ~r requires a radix parameter (English cardinal/ordinal output is not supported)");
					}
					LispVal arg = args.next(directive);
					LispVal digits = radixIntegerExpr(arg, fmtParam(params, 0), colon, fmtCommaChar(params, 3),
							fmtInterval(params, 4), at);
					if (fmtHasParam(params, 1)) {
						digits = padExpr(digits, fmtParam(params, 1), fmtPadChar(params, 2, ' '), true);
					}
					ops.add(new FmtString(digits));
				}
				case 'c' -> {
					flushFmtLiteral(lit, ops);
					LispVal arg = args.next(directive);
					LispVal base;
					if (at) {
						base = fmtCall(LispNames.PRIN1_TO_STRING, arg);
					}
					else if (colon) {
						// prin1 of a character is "#\name"; dropping the #\ prefix
						// yields the glyph for graphic characters and the standard
						// name (Newline, Space, ...) for non-graphic ones.
						base = fmtCall(LispNames.SUBSEQ, fmtCall(LispNames.PRIN1_TO_STRING, arg), new LispInteger(2));
					}
					else {
						base = fmtCall(LispNames.PRINC_TO_STRING, arg);
					}
					ops.add(new FmtString(base));
				}
				case 'f' -> {
					flushFmtLiteral(lit, ops);
					LispVal arg = args.next(directive);
					int scale = fmtHasParam(params, 2) ? fmtIntParam(params, 2, directive) : 0;
					LispVal value = (scale == 0) ? arg
							: fmtCall(LispNames.MUL, arg, new LispDouble(Math.pow(10, scale)));
					LispVal base = fmtHasParam(params, 1) ? decimalFloatExpr(value, fmtParam(params, 1), null, at)
							: fmtCall(LispNames.PRINC_TO_STRING, value);
					if (fmtHasParam(params, 0)) {
						base = padExpr(base, fmtParam(params, 0), fmtPadChar(params, 4, ' '), true);
					}
					base = maybeOverflow(base, params, 0, 3, directive);
					ops.add(new FmtString(base));
				}
				case 'e' -> {
					flushFmtLiteral(lit, ops);
					LispVal arg = args.next(directive);
					boolean dGiven = fmtHasParam(params, 1);
					int places = dGiven ? fmtIntParam(params, 1, directive) : DEFAULT_EXP_PLACES;
					if (places < 0) {
						throw new IllegalArgumentException("format: ~e precision must be non-negative");
					}
					int expDigits = fmtHasParam(params, 2) ? fmtIntParam(params, 2, directive) : 0;
					if (fmtHasParam(params, 3) && fmtIntParam(params, 3, directive) != 1) {
						throw new UnsupportedOperationException(
								"format: ~e scale factors other than 1 are not supported");
					}
					char marker = fmtCharParam(params, 6, 'e');
					LispVal base = decimalExpExpr(arg, places, !dGiven, at, expDigits, marker);
					if (fmtHasParam(params, 0)) {
						base = padExpr(base, fmtParam(params, 0), fmtPadChar(params, 5, ' '), true);
					}
					base = maybeOverflow(base, params, 0, 4, directive);
					ops.add(new FmtString(base));
				}
				case 'g' -> {
					flushFmtLiteral(lit, ops);
					for (LispVal p : params) {
						if (!(p instanceof LispNil)) {
							throw new UnsupportedOperationException(
									"format: ~g prefix parameters are not supported; use ~f or ~e");
						}
					}
					LispVal arg = args.next(directive);
					ops.add(new FmtString(generalFloatExpr(arg, at)));
				}
				case '$' -> {
					flushFmtLiteral(lit, ops);
					LispVal arg = args.next(directive);
					LispVal places = fmtHasParam(params, 0) ? fmtParam(params, 0) : new LispInteger(2);
					LispVal nbefore = fmtHasParam(params, 1) ? fmtParam(params, 1) : new LispInteger(1);
					LispVal base = decimalFloatExpr(arg, places, nbefore, at);
					if (fmtHasParam(params, 2)) {
						base = padExpr(base, fmtParam(params, 2), fmtPadChar(params, 3, ' '), true);
					}
					ops.add(new FmtString(base));
				}
				case '*' -> {
					if (at) {
						// ~n@* is an absolute jump to argument n (default 0).
						args.move(fmtCount(params, 0, 0, directive) - args.position(), directive);
					}
					else if (colon) {
						args.move(-fmtCount(params, 0, 1, directive), directive);
					}
					else {
						args.move(fmtCount(params, 0, 1, directive), directive);
					}
				}
				case '(' -> {
					flushFmtLiteral(lit, ops);
					LispVal body = opsToExpr(parseOps(args, ")"));
					LispVal converted;
					if (colon && at) {
						converted = fmtCall(LispNames.STRING_UPCASE, body);
					}
					else if (colon) {
						converted = fmtCall(LispNames.STRING_CAPITALIZE, body);
					}
					else if (at) {
						converted = capitalizeFirstExpr(body);
					}
					else {
						converted = fmtCall(LispNames.STRING_DOWNCASE, body);
					}
					ops.add(new FmtString(converted));
				}
				case '[' -> {
					flushFmtLiteral(lit, ops);
					parseConditional(params, colon, at, args, ops);
				}
				case '{' -> {
					flushFmtLiteral(lit, ops);
					parseIteration(params, colon, at, args, ops);
				}
				case ';', ']', '}', ')' -> throw new IllegalArgumentException("format: misplaced ~" + directive);
				default -> throw new UnsupportedOperationException("format: unsupported directive ~" + directive);
			}
		}

		/**
		 * Parses the clauses of a {@code ~[...~]}, each against a branch of the cursor.
		 */
		private List<FmtClause> parseClauses(FmtArgs args) {
			List<FmtClause> clauses = new java.util.ArrayList<>();
			boolean nextIsDefault = false;
			while (true) {
				FmtArgs branch = args.branch();
				List<FmtOp> body = parseOps(branch, ";]");
				clauses.add(new FmtClause(body, branch.position(), nextIsDefault));
				if (this.stopChar == ']') {
					return clauses;
				}
				nextIsDefault = this.stopColon;
			}
		}

		/**
		 * A runtime-selected conditional can only be expanded statically when every
		 * clause consumes the same number of arguments (the selected clause is not known
		 * until run time). Returns that common end position.
		 */
		private static int requireEqualConsumption(List<FmtClause> clauses) {
			int end = clauses.get(0).end();
			for (FmtClause clause : clauses) {
				if (clause.end() != end) {
					throw new UnsupportedOperationException(
							"format: every ~[ clause must consume the same number of arguments"
									+ " (use a literal or # selector for uneven clauses)");
				}
			}
			return end;
		}

		private void parseConditional(List<LispVal> params, boolean colon, boolean at, FmtArgs args, List<FmtOp> ops) {
			if (at) {
				// ~@[str~]: the tested argument is re-used by the clause when non-nil
				// and consumed outright when nil, so the clause must consume exactly it.
				LispSymbol sel = args.next('[');
				FmtArgs branch = args.branchAt(args.position() - 1);
				List<FmtOp> body = parseOps(branch, "]");
				if (branch.position() != args.position()) {
					throw new UnsupportedOperationException(
							"format: the ~@[ clause must consume exactly the tested argument");
				}
				ops.add(new FmtString(makeIf(sel, opsToExpr(body), new LispString(""))));
				return;
			}
			if (colon) {
				LispSymbol sel = args.next('[');
				List<FmtClause> clauses = parseClauses(args);
				if (clauses.size() != 2) {
					throw new IllegalArgumentException("format: ~:[ requires exactly two clauses");
				}
				args.adoptIndex(requireEqualConsumption(clauses));
				ops.add(new FmtString(makeIf(sel, opsToExpr(clauses.get(1).ops()), opsToExpr(clauses.get(0).ops()))));
				return;
			}
			LispVal selParam = params.isEmpty() ? LispNil.INSTANCE : params.get(0);
			if (selParam instanceof LispInteger li) {
				// A literal (or #) selector picks the clause at expansion time, so
				// clauses may consume different numbers of arguments. Unselected
				// clauses parse against a throwaway source (they never touch the
				// real arguments); the default clause (after ~:;) is last, so by the
				// time it is reached the numbered selection is decided.
				long nval = li.value();
				List<FmtOp> chosenOps = null;
				int chosenEnd = -1;
				boolean nextIsDefault = false;
				int numberedIdx = 0;
				while (true) {
					boolean isDefault = nextIsDefault;
					boolean choose = (chosenOps == null) && (isDefault || numberedIdx == nval);
					if (!isDefault) {
						numberedIdx++;
					}
					if (choose) {
						FmtArgs branch = args.branch();
						chosenOps = parseOps(branch, ";]");
						chosenEnd = branch.position();
					}
					else {
						parseOps(FmtArgs.forRuntimeItems("__fmtdis" + this.gensym++ + "_"), ";]");
					}
					if (this.stopChar == ']') {
						break;
					}
					nextIsDefault = this.stopColon;
				}
				if (chosenOps != null) {
					ops.addAll(chosenOps);
					args.adoptIndex(chosenEnd);
				}
				return;
			}
			LispVal sel = (selParam instanceof LispNil) ? args.next('[') : selParam;
			List<FmtClause> clauses = parseClauses(args);
			args.adoptIndex(requireEqualConsumption(clauses));
			List<FmtClause> numbered = clauses.stream().filter(c -> !c.isDefault()).toList();
			FmtClause dflt = clauses.stream().filter(FmtClause::isDefault).findFirst().orElse(null);
			LispVal chain = (dflt != null) ? opsToExpr(dflt.ops()) : new LispString("");
			for (int i = numbered.size() - 1; i >= 0; i--) {
				chain = makeIf(fmtCall(LispNames.EQL, sel, new LispInteger(i)), opsToExpr(numbered.get(i).ops()),
						chain);
			}
			ops.add(new FmtString(chain));
		}

		private void parseIteration(List<LispVal> params, boolean colon, boolean at, FmtArgs args, List<FmtOp> ops) {
			int id = this.gensym++;
			LispVal maxParam = (params.isEmpty()) ? LispNil.INSTANCE : params.get(0);
			if (at) {
				// ~@{ iterates over the remaining top-level arguments, so the passes
				// can be unrolled at expansion time (each pass re-parses the body).
				if (!args.isStatic()) {
					throw new UnsupportedOperationException("format: ~@{ is not supported inside a ~{ iteration body");
				}
				int limit = Integer.MAX_VALUE;
				if (maxParam instanceof LispInteger li) {
					limit = (int) li.value();
				}
				else if (!(maxParam instanceof LispNil)) {
					throw new UnsupportedOperationException("format: a runtime (v) count is not supported for ~@{");
				}
				int bodyStart = this.pos;
				int bodyEnd = -1;
				int passes = 0;
				while (args.remaining('{') > 0 && passes < limit) {
					if (colon) {
						// ~:@{: each remaining argument is one iteration's sublist.
						LispSymbol sub = args.next('{');
						FmtArgs items = FmtArgs.forRuntimeItems("__fmtsub" + id + "_" + passes + "_");
						this.pos = bodyStart;
						List<FmtOp> body = parseOps(items, "}");
						bodyEnd = this.pos;
						ops.add(new FmtString(letItems(items.items(), sub, opsToExpr(body))));
						passes++;
					}
					else {
						int before = args.position();
						this.pos = bodyStart;
						ops.addAll(parseOps(args, "}"));
						bodyEnd = this.pos;
						passes++;
						if (args.position() == before) {
							break; // a pass that consumes nothing would never terminate
						}
					}
				}
				if (bodyEnd < 0) {
					// Zero passes ran; skip the body with a throwaway parse.
					this.pos = bodyStart;
					parseOps(FmtArgs.forRuntimeItems("__fmtskip" + id + "_"), "}");
				}
				else {
					this.pos = bodyEnd;
				}
				return;
			}
			LispSymbol listSym = args.next('{');
			FmtArgs items = FmtArgs.forRuntimeItems("__fmtit" + id + "_");
			List<FmtOp> body = parseOps(items, "}");
			LispVal maxExpr = (maxParam instanceof LispNil) ? null : maxParam;
			if (!colon && items.position() == 0) {
				throw new UnsupportedOperationException("format: the ~{ body must consume at least one argument");
			}
			LispVal loop = colon ? sublistLoopExpr(listSym, items, opsToExpr(body), maxExpr, id)
					: flatLoopExpr(listSym, items, opsToExpr(body), maxExpr, id);
			ops.add(new FmtString(loop));
		}

	}

	private static boolean isParamStart(char c) {
		return c == '\'' || c == 'v' || c == 'V' || c == '#' || c == '-' || Character.isDigit(c);
	}

	private static LispVal fmtCall(String op, LispVal... args) {
		List<LispVal> parts = new java.util.ArrayList<>();
		parts.add(new LispSymbol(op));
		parts.addAll(List.of(args));
		return listToCons(parts);
	}

	/**
	 * Builds {@code (let ((r strExpr)) (while (< (length r) mincol) (setq r ...)) r)},
	 * which pads {@code strExpr} with the single-character {@code padChar} until it
	 * reaches {@code mincol} columns. When {@code left} is true the padding is prepended
	 * (right-justify), otherwise appended (left-justify). Pure-Lisp so all three backends
	 * share the implementation; {@code mincol} may be a compile-time literal or a runtime
	 * (v) parameter, and {@code padChar} a literal string or a runtime string expression.
	 */
	private static LispVal padExpr(LispVal strExpr, LispVal mincolExpr, LispVal padChar, boolean left) {
		LispSymbol r = new LispSymbol("__fmtr");
		LispVal lenCheck = fmtCall(LispNames.LT, fmtCall(LispNames.LENGTH, r), mincolExpr);
		LispVal concat = left ? fmtCall(LispNames.STRING_CONCAT, padChar, r)
				: fmtCall(LispNames.STRING_CONCAT, r, padChar);
		LispVal loop = fmtCall(LispNames.WHILE, lenCheck, fmtCall(LispNames.SETQ, r, concat));
		LispVal binding = listToCons(List.of(listToCons(List.of(r, strExpr))));
		return listToCons(List.of(new LispSymbol(LispNames.LET), binding, loop, r));
	}

	/**
	 * Builds the string-valued expansion of {@code ~d} with the {@code :} (comma
	 * grouping) and/or {@code @} (explicit plus sign) modifiers.
	 */
	private static LispVal decimalExpr(LispVal arg, boolean comma, String commaChar, int interval, boolean plus) {
		LispSymbol nstr = new LispSymbol("__fmtn");
		LispSymbol neg = new LispSymbol("__fmtneg");
		LispSymbol dig = new LispSymbol("__fmtd");
		LispVal nstrInit = fmtCall(LispNames.PRINC_TO_STRING, arg);
		LispVal negInit = fmtCall(LispNames.LT, arg, new LispInteger(0));
		LispVal digInit = makeIf(neg, fmtCall(LispNames.SUBSEQ, nstr, new LispInteger(1)), nstr);
		LispVal grouped = comma ? groupExpr(dig, commaChar, interval) : dig;
		LispVal sign = makeIf(neg, new LispString("-"), new LispString(plus ? "+" : ""));
		List<LispVal> bindings = List.of(listToCons(List.of(nstr, nstrInit)), listToCons(List.of(neg, negInit)),
				listToCons(List.of(dig, digInit)));
		LispVal body = fmtCall(LispNames.STRING_CONCAT, sign, grouped);
		return listToCons(List.of(new LispSymbol(LispNames.LET_STAR), listToCons(bindings), body));
	}

	/** Builds the comma-grouping loop over a string of digits. */
	private static LispVal groupExpr(LispVal digitsExpr, String commaChar, int interval) {
		LispSymbol rem = new LispSymbol("__fmtrem");
		LispSymbol out = new LispSymbol("__fmtout");
		LispInteger iv = new LispInteger(interval);
		LispVal tailLen = fmtCall(LispNames.SUB, fmtCall(LispNames.LENGTH, rem), iv);
		LispVal test = fmtCall(LispNames.GT, fmtCall(LispNames.LENGTH, rem), iv);
		LispVal newOut = fmtCall(LispNames.STRING_CONCAT,
				fmtCall(LispNames.STRING_CONCAT, new LispString(commaChar), fmtCall(LispNames.SUBSEQ, rem, tailLen)),
				out);
		LispVal newRem = fmtCall(LispNames.SUBSEQ, rem, new LispInteger(0), tailLen);
		LispVal loop = fmtCall(LispNames.WHILE, test, fmtCall(LispNames.SETQ, out, newOut),
				fmtCall(LispNames.SETQ, rem, newRem));
		List<LispVal> bindings = List.of(listToCons(List.of(rem, digitsExpr)),
				listToCons(List.of(out, new LispString(""))));
		LispVal body = fmtCall(LispNames.STRING_CONCAT, rem, out);
		return listToCons(List.of(new LispSymbol(LispNames.LET), listToCons(bindings), loop, body));
	}

	/**
	 * Builds the string-valued expansion of a fixed-decimal float (~f) or monetary value
	 * (~$). The value is scaled by 10^places, rounded to an integer (round-half-to-even,
	 * matching {@code round} on every backend) and split into integer and fractional
	 * parts by string slicing, so no floating-point string formatting is needed at
	 * runtime. When {@code nbefore} is non-null the integer part is zero-padded to that
	 * many digits (~$).
	 */
	private static LispVal decimalFloatExpr(LispVal arg, LispVal placesExpr, @Nullable LispVal nbefore, boolean plus) {
		boolean zeroPlaces = placesExpr instanceof LispInteger li && li.value() == 0;
		// A floating-point 10^places keeps the scaling multiply in f64 on every backend
		// (an integer expt can widen to bignum and break mixed arithmetic).
		LispVal pow10 = (placesExpr instanceof LispInteger li) ? new LispDouble(pow10(li.value()))
				: fmtCall(LispNames.EXPT, new LispDouble(10.0), placesExpr);
		LispVal nPlus1 = (placesExpr instanceof LispInteger li) ? new LispInteger(li.value() + 1)
				: fmtCall(LispNames.ADD, placesExpr, new LispInteger(1));
		LispSymbol v = new LispSymbol("__fmtv");
		LispSymbol neg = new LispSymbol("__fmtneg");
		LispSymbol sc = new LispSymbol("__fmtsc");
		LispSymbol s2 = new LispSymbol("__fmts");
		LispSymbol len = new LispSymbol("__fmtlen");
		LispSymbol ip = new LispSymbol("__fmtip");
		LispVal vInit = fmtCall(LispNames.MUL, arg, new LispDouble(1.0));
		LispVal negInit = fmtCall(LispNames.LT, v, new LispDouble(0.0));
		// Round to a signed integer, then take the magnitude by integer negation (the
		// integer `abs` runtime path rejects a float operand on the compiled backends).
		LispVal signedScaled = fmtCall(LispNames.ROUND, fmtCall(LispNames.MUL, v, pow10));
		LispVal scInit = makeIf(neg, fmtCall(LispNames.SUB, new LispInteger(0), signedScaled), signedScaled);
		LispVal s2Init = padExpr(fmtCall(LispNames.PRINC_TO_STRING, sc), nPlus1, new LispString("0"), true);
		LispVal lenInit = fmtCall(LispNames.LENGTH, s2);
		LispVal ipSlice = fmtCall(LispNames.SUBSEQ, s2, new LispInteger(0), fmtCall(LispNames.SUB, len, placesExpr));
		LispVal ipInit = (nbefore == null) ? ipSlice : padExpr(ipSlice, nbefore, new LispString("0"), true);
		LispVal sign = makeIf(neg, new LispString("-"), new LispString(plus ? "+" : ""));
		List<LispVal> bindings = new java.util.ArrayList<>(List.of(listToCons(List.of(v, vInit)),
				listToCons(List.of(neg, negInit)), listToCons(List.of(sc, scInit)), listToCons(List.of(s2, s2Init)),
				listToCons(List.of(len, lenInit)), listToCons(List.of(ip, ipInit))));
		LispVal body;
		if (zeroPlaces) {
			body = fmtCall(LispNames.STRING_CONCAT, sign, ip);
		}
		else {
			LispVal fp = fmtCall(LispNames.SUBSEQ, s2, fmtCall(LispNames.SUB, len, placesExpr));
			LispVal withDot = fmtCall(LispNames.STRING_CONCAT,
					fmtCall(LispNames.STRING_CONCAT, ip, new LispString(".")), fp);
			body = fmtCall(LispNames.STRING_CONCAT, sign, withDot);
		}
		return listToCons(List.of(new LispSymbol(LispNames.LET_STAR), listToCons(bindings), body));
	}

	private static long pow10(long n) {
		long r = 1;
		for (long k = 0; k < n; k++) {
			r *= 10;
		}
		return r;
	}

	/**
	 * The number of fractional digits used by {@code ~e} when the {@code d} parameter is
	 * omitted (matching C's {@code printf("%e")} default). Kept small enough that the
	 * scaled mantissa fits in a WASM {@code i31}, so the directive renders identically on
	 * all three backends.
	 */
	private static final int DEFAULT_EXP_PLACES = 6;

	/**
	 * Builds the string-valued expansion of an exponential-notation float ({@code ~e}).
	 * The magnitude is normalized to a mantissa in {@code [1, 10)} by a runtime
	 * divide/multiply loop that tracks the decimal exponent, rounded to {@code places}
	 * fractional digits via integer scaling (so the digit string is built purely from
	 * integer arithmetic and renders identically on every backend, unlike a direct
	 * {@code princ-to-string} of a float), and assembled as
	 * {@code [sign]d.ddd...e[+/-]xx}. A mantissa that rounds up to {@code 10.0} bumps the
	 * exponent and renormalizes to {@code 1.0}. When {@code strip} is true (the {@code d}
	 * parameter was omitted) trailing fractional zeros are dropped down to a single
	 * digit. On the WASM backend the scaled mantissa must fit in an {@code i31}, so
	 * {@code places} is effectively limited to about 8. When {@code expDigits} is
	 * positive the exponent magnitude is zero-padded to that many digits, and
	 * {@code marker} is the exponent-marker character (default {@code e}).
	 */
	private static LispVal decimalExpExpr(LispVal arg, int places, boolean strip, boolean plus, int expDigits,
			char marker) {
		long pd = pow10(places);
		long pd1 = pow10(places + 1L);
		LispSymbol v = new LispSymbol("__ev");
		LispSymbol neg = new LispSymbol("__eneg");
		LispSymbol a = new LispSymbol("__ea");
		LispSymbol ee = new LispSymbol("__ee");
		LispSymbol sc = new LispSymbol("__esc");
		LispSymbol ovf = new LispSymbol("__eovf");
		LispSymbol sc2 = new LispSymbol("__esc2");
		LispSymbol eef = new LispSymbol("__eef");
		LispSymbol s = new LispSymbol("__es");
		LispSymbol ip = new LispSymbol("__eip");
		LispSymbol fr = new LispSymbol("__efr");

		// Outer let* bindings: coerce to float, capture sign, take magnitude, exponent =
		// 0.
		LispVal vInit = fmtCall(LispNames.MUL, arg, new LispDouble(1.0));
		LispVal negInit = fmtCall(LispNames.LT, v, new LispDouble(0.0));
		LispVal aInit = makeIf(neg, fmtCall(LispNames.SUB, new LispDouble(0.0), v), v);
		// Normalize the mantissa into [1, 10), tracking the decimal exponent in ee.
		LispVal up = fmtCall(LispNames.WHILE, fmtCall(LispNames.GE, a, new LispDouble(10.0)),
				fmtCall(LispNames.SETQ, a, fmtCall(LispNames.DIV, a, new LispDouble(10.0))),
				fmtCall(LispNames.SETQ, ee, fmtCall(LispNames.ADD, ee, new LispInteger(1))));
		LispVal down = fmtCall(LispNames.WHILE, fmtCall(LispNames.LT, a, new LispDouble(1.0)),
				fmtCall(LispNames.SETQ, a, fmtCall(LispNames.MUL, a, new LispDouble(10.0))),
				fmtCall(LispNames.SETQ, ee, fmtCall(LispNames.SUB, ee, new LispInteger(1))));
		// Inner let*: round to (places+1) significant digits, renormalize on overflow.
		LispVal scInit = fmtCall(LispNames.ROUND, fmtCall(LispNames.MUL, a, new LispDouble((double) pd)));
		LispVal ovfInit = fmtCall(LispNames.GE, sc, new LispInteger(pd1));
		LispVal sc2Init = makeIf(ovf, new LispInteger(pd), sc);
		LispVal eefInit = makeIf(ovf, fmtCall(LispNames.ADD, ee, new LispInteger(1)), ee);
		LispVal sInit = fmtCall(LispNames.PRINC_TO_STRING, sc2);
		LispVal ipInit = fmtCall(LispNames.SUBSEQ, s, new LispInteger(0), new LispInteger(1));
		LispVal frInit = fmtCall(LispNames.SUBSEQ, s, new LispInteger(1));
		// Exponent suffix: always-signed, magnitude printed as an integer.
		LispVal eneg = fmtCall(LispNames.LT, eef, new LispInteger(0));
		LispVal esign = makeIf(eneg, new LispString("-"), new LispString("+"));
		LispVal eabs = fmtCall(LispNames.PRINC_TO_STRING,
				makeIf(eneg, fmtCall(LispNames.SUB, new LispInteger(0), eef), eef));
		if (expDigits > 0) {
			eabs = padExpr(eabs, new LispInteger(expDigits), new LispString("0"), true);
		}
		LispVal sign = makeIf(neg, new LispString("-"), new LispString(plus ? "+" : ""));
		LispVal frFinal = strip ? stripTrailingZeros(fr) : fr;
		LispVal mant = (places == 0) ? ip : fmtConcat(ip, new LispString("."), frFinal);
		LispVal body = fmtConcat(sign, mant, new LispString(String.valueOf(marker)), esign, eabs);
		List<LispVal> innerBindings = List.of(listToCons(List.of(sc, scInit)), listToCons(List.of(ovf, ovfInit)),
				listToCons(List.of(sc2, sc2Init)), listToCons(List.of(eef, eefInit)), listToCons(List.of(s, sInit)),
				listToCons(List.of(ip, ipInit)), listToCons(List.of(fr, frInit)));
		LispVal innerLet = listToCons(List.of(new LispSymbol(LispNames.LET_STAR), listToCons(innerBindings), body));
		LispVal normal = listToCons(List.of(new LispSymbol(LispNames.PROGN), up, down, innerLet));
		// Zero needs no normalization (it would loop forever), so short-circuit it.
		String zeroMant = (places == 0) ? "0" : (strip ? "0.0" : "0." + "0".repeat(places));
		LispVal zero = new LispString((plus ? "+" : "") + zeroMant + marker + "+" + "0".repeat(Math.max(1, expDigits)));
		LispVal outerBody = makeIf(fmtCall(LispNames.EQ, a, new LispDouble(0.0)), zero, normal);
		List<LispVal> outerBindings = List.of(listToCons(List.of(v, vInit)), listToCons(List.of(neg, negInit)),
				listToCons(List.of(a, aInit)), listToCons(List.of(ee, new LispInteger(0))));
		return listToCons(List.of(new LispSymbol(LispNames.LET_STAR), listToCons(outerBindings), outerBody));
	}

	/**
	 * Builds
	 * {@code (let ((g frExpr)) (while (and (> (length g) 1) (string= last "0")) ...)
	 * g)}, trimming trailing {@code 0} characters from a fractional-digit string down to
	 * a single remaining digit. Pure-Lisp so all three backends share it.
	 */
	private static LispVal stripTrailingZeros(LispVal frExpr) {
		LispSymbol g = new LispSymbol("__eg");
		LispVal last = fmtCall(LispNames.SUBSEQ, g,
				fmtCall(LispNames.SUB, fmtCall(LispNames.LENGTH, g), new LispInteger(1)));
		LispVal cond = fmtCall(LispNames.AND, fmtCall(LispNames.GT, fmtCall(LispNames.LENGTH, g), new LispInteger(1)),
				fmtCall(LispNames.STRING_EQ, last, new LispString("0")));
		LispVal trimmed = fmtCall(LispNames.SUBSEQ, g, new LispInteger(0),
				fmtCall(LispNames.SUB, fmtCall(LispNames.LENGTH, g), new LispInteger(1)));
		LispVal loop = fmtCall(LispNames.WHILE, cond, fmtCall(LispNames.SETQ, g, trimmed));
		LispVal binding = listToCons(List.of(listToCons(List.of(g, frExpr))));
		return listToCons(List.of(new LispSymbol(LispNames.LET), binding, loop, g));
	}

	/**
	 * Folds {@code parts} into a right-nested chain of binary {@code %string-concat}
	 * calls.
	 */
	private static LispVal fmtConcat(LispVal... parts) {
		LispVal r = parts[parts.length - 1];
		for (int k = parts.length - 2; k >= 0; k--) {
			r = fmtCall(LispNames.STRING_CONCAT, parts[k], r);
		}
		return r;
	}

	/**
	 * Resolves a compile-time integer prefix parameter, rejecting a runtime {@code v}.
	 */
	private static int fmtIntParam(List<LispVal> params, int idx, char directive) {
		if (params.get(idx) instanceof LispInteger li) {
			return (int) li.value();
		}
		throw new UnsupportedOperationException(
				"format: runtime (v) parameter not supported for directive ~" + directive);
	}

	/**
	 * The pad string for a pad-character parameter: a literal string for a {@code 'c}
	 * parameter, or a {@code (princ-to-string v)} expression for a runtime (v) parameter.
	 */
	private static LispVal fmtPadChar(List<LispVal> params, int idx, char def) {
		if (idx < params.size() && params.get(idx) instanceof LispInteger code) {
			return new LispString(String.valueOf((char) code.value()));
		}
		if (idx < params.size() && params.get(idx) instanceof LispSymbol v) {
			return fmtCall(LispNames.PRINC_TO_STRING, v);
		}
		return new LispString(String.valueOf(def));
	}

	/** A literal character parameter (e.g. the ~e exponent marker), or the default. */
	private static char fmtCharParam(List<LispVal> params, int idx, char def) {
		if (idx < params.size() && params.get(idx) instanceof LispInteger code) {
			return (char) code.value();
		}
		if (idx < params.size() && !(params.get(idx) instanceof LispNil)) {
			throw new UnsupportedOperationException("format: a runtime (v) character parameter is not supported here");
		}
		return def;
	}

	/** The literal comma character for ~:d (default {@code ,}). */
	private static String fmtCommaChar(List<LispVal> params, int idx) {
		if (idx < params.size() && params.get(idx) instanceof LispInteger code) {
			return String.valueOf((char) code.value());
		}
		return ",";
	}

	/** The comma interval for ~:d (default 3). */
	private static int fmtInterval(List<LispVal> params, int idx) {
		if (idx < params.size() && params.get(idx) instanceof LispInteger iv) {
			return (int) iv.value();
		}
		return 3;
	}

	/**
	 * Returns the param at {@code idx}, or nil (the helper's default sentinel) if absent.
	 */
	private static LispVal fmtParam(List<LispVal> params, int idx) {
		return idx < params.size() ? params.get(idx) : LispNil.INSTANCE;
	}

	private static boolean fmtHasParam(List<LispVal> params, int idx) {
		return idx < params.size() && !(params.get(idx) instanceof LispNil);
	}

	/** Resolves a compile-time integer parameter (count) for ~%/~&/~~. */
	private static int fmtCount(List<LispVal> params, int idx, int def, char directive) {
		if (idx >= params.size()) {
			return def;
		}
		LispVal pv = params.get(idx);
		if (pv instanceof LispNil) {
			return def;
		}
		if (pv instanceof LispInteger li) {
			return (int) li.value();
		}
		throw new UnsupportedOperationException("format: runtime (v) count not supported for directive ~" + directive);
	}

	private static void flushFmtLiteral(StringBuilder lit, List<FmtOp> ops) {
		if (!lit.isEmpty()) {
			ops.add(new FmtLiteral(lit.toString()));
			lit.setLength(0);
		}
	}

	/**
	 * Renders the parsed ops into output forms for the {@code t} destination: literals
	 * and string-valued directives become {@code (princ ...)}, {@code ~%} becomes
	 * {@code (terpri)}, and {@code ~&} becomes {@code (fresh-line)}.
	 */
	private static List<LispVal> formatOutputForms(List<FmtOp> ops) {
		List<LispVal> forms = new java.util.ArrayList<>();
		for (FmtOp op : ops) {
			switch (op) {
				case FmtLiteral l -> forms.add(fmtCall(LispNames.PRINC, new LispString(l.text())));
				case FmtString f -> forms.add(fmtCall(LispNames.PRINC, f.expr()));
				case FmtNewline nl -> {
					for (int k = 0; k < nl.count(); k++) {
						forms.add(listToCons(List.of(new LispSymbol(LispNames.TERPRI))));
					}
				}
				case FmtFreshLine fl -> {
					forms.add(listToCons(List.of(new LispSymbol(LispNames.FRESH_LINE))));
					for (int k = 1; k < fl.count(); k++) {
						forms.add(listToCons(List.of(new LispSymbol(LispNames.TERPRI))));
					}
				}
			}
		}
		return forms;
	}

	/**
	 * Renders the parsed ops into string-valued pieces for the {@code nil} destination
	 * (and for the bodies of composite directives). Newlines become literal {@code \n}
	 * runs; {@code ~&} is statically approximated from the surrounding literal text (a
	 * value piece is assumed not to end a line).
	 */
	private static List<LispVal> opsToPieces(List<FmtOp> ops) {
		List<LispVal> pieces = new java.util.ArrayList<>();
		boolean atLineStart = true;
		for (FmtOp op : ops) {
			switch (op) {
				case FmtLiteral l -> {
					pieces.add(new LispString(l.text()));
					atLineStart = l.text().endsWith("\n");
				}
				case FmtString f -> {
					pieces.add(f.expr());
					atLineStart = false;
				}
				case FmtNewline nl -> {
					if (nl.count() > 0) {
						pieces.add(new LispString("\n".repeat(nl.count())));
						atLineStart = true;
					}
				}
				case FmtFreshLine fl -> {
					int emit = atLineStart ? fl.count() - 1 : fl.count();
					if (emit > 0) {
						pieces.add(new LispString("\n".repeat(emit)));
					}
					atLineStart = true;
				}
			}
		}
		return pieces;
	}

	/**
	 * Folds the string pieces of a composite-directive body into one string-valued
	 * expression (the empty string when the body is empty).
	 */
	private static LispVal opsToExpr(List<FmtOp> ops) {
		List<LispVal> pieces = opsToPieces(ops);
		if (pieces.isEmpty()) {
			return new LispString("");
		}
		LispVal result = pieces.get(0);
		for (int i = 1; i < pieces.size(); i++) {
			result = fmtCall(LispNames.STRING_CONCAT, result, pieces.get(i));
		}
		return result;
	}

	/**
	 * Builds the string-valued expansion of an integer in an arbitrary radix
	 * ({@code ~x}/{@code ~o}/{@code ~b}/{@code ~nR}): a runtime division loop over the
	 * magnitude producing uppercase digits (via {@code code-char}), with the same
	 * sign/grouping handling as {@code ~d}. {@code base} is a literal or a runtime (v)
	 * parameter.
	 */
	private static LispVal radixIntegerExpr(LispVal arg, LispVal base, boolean comma, String commaChar, int interval,
			boolean plus) {
		LispSymbol neg = new LispSymbol("__rxneg");
		LispSymbol m = new LispSymbol("__rxm");
		LispSymbol s = new LispSymbol("__rxs");
		LispSymbol d = new LispSymbol("__rxd");
		LispSymbol g = new LispSymbol("__rxg");
		LispVal negInit = fmtCall(LispNames.LT, arg, new LispInteger(0));
		LispVal mInit = makeIf(neg, fmtCall(LispNames.SUB, new LispInteger(0), arg), arg);
		// Digit character: 0-9 then uppercase A-Z (48 = '0', 55 = 'A' - 10).
		LispVal digitChar = fmtCall(LispNames.PRINC_TO_STRING,
				fmtCall(LispNames.CODE_CHAR,
						makeIf(fmtCall(LispNames.LT, d, new LispInteger(10)),
								fmtCall(LispNames.ADD, new LispInteger(48), d),
								fmtCall(LispNames.ADD, new LispInteger(55), d))));
		LispVal digitStep = listToCons(List.of(new LispSymbol(LispNames.LET),
				listToCons(List.of(listToCons(List.of(d, fmtCall(LispNames.MOD, m, base))))),
				fmtCall(LispNames.SETQ, s, fmtCall(LispNames.STRING_CONCAT, digitChar, s))));
		// Integer division by the base: / yields a rational, truncate the quotient.
		LispVal divStep = fmtCall(LispNames.SETQ, m, fmtCall(LispNames.TRUNCATE, fmtCall(LispNames.DIV, m, base)));
		LispVal loop = fmtCall(LispNames.WHILE, fmtCall(LispNames.GT, m, new LispInteger(0)), digitStep, divStep);
		LispVal gInit = makeIf(fmtCall(LispNames.STRING_EQ, s, new LispString("")), new LispString("0"), s);
		LispVal grouped = comma ? groupExpr(g, commaChar, interval) : g;
		LispVal sign = makeIf(neg, new LispString("-"), new LispString(plus ? "+" : ""));
		LispVal inner = listToCons(List.of(new LispSymbol(LispNames.LET),
				listToCons(List.of(listToCons(List.of(g, gInit)))), fmtCall(LispNames.STRING_CONCAT, sign, grouped)));
		List<LispVal> bindings = List.of(listToCons(List.of(neg, negInit)), listToCons(List.of(m, mInit)),
				listToCons(List.of(s, new LispString(""))));
		return listToCons(List.of(new LispSymbol(LispNames.LET_STAR), listToCons(bindings), loop, inner));
	}

	/**
	 * Builds the string-valued expansion of {@code ~g}: the plain (backend-native) float
	 * representation when the magnitude is zero or within {@code [0.1, 1e16)}, and the
	 * {@code ~e} default form outside that range. An approximation of the CLHS rule
	 * (which chooses between {@code ~f} and {@code ~e} by the number of significant
	 * digits); prefix parameters are not supported.
	 */
	private static LispVal generalFloatExpr(LispVal arg, boolean plus) {
		LispSymbol v = new LispSymbol("__fmtgv");
		LispSymbol a = new LispSymbol("__fmtga");
		LispVal vInit = fmtCall(LispNames.MUL, arg, new LispDouble(1.0));
		LispVal aInit = makeIf(fmtCall(LispNames.LT, v, new LispDouble(0.0)),
				fmtCall(LispNames.SUB, new LispDouble(0.0), v), v);
		LispVal fixedRange = fmtCall(LispNames.OR, fmtCall(LispNames.EQ, a, new LispDouble(0.0)), fmtCall(LispNames.AND,
				fmtCall(LispNames.GE, a, new LispDouble(0.1)), fmtCall(LispNames.LT, a, new LispDouble(1.0e16))));
		LispVal fixed = fmtCall(LispNames.PRINC_TO_STRING, v);
		if (plus) {
			fixed = fmtCall(LispNames.STRING_CONCAT,
					makeIf(fmtCall(LispNames.LT, v, new LispDouble(0.0)), new LispString(""), new LispString("+")),
					fixed);
		}
		LispVal expo = decimalExpExpr(v, DEFAULT_EXP_PLACES, true, plus, 0, 'e');
		List<LispVal> bindings = List.of(listToCons(List.of(v, vInit)), listToCons(List.of(a, aInit)));
		return listToCons(
				List.of(new LispSymbol(LispNames.LET_STAR), listToCons(bindings), makeIf(fixedRange, fixed, expo)));
	}

	/**
	 * Builds the {@code ~@(} conversion: downcase the whole string, then upcase the first
	 * alphabetic character (capitalize just the first word).
	 */
	private static LispVal capitalizeFirstExpr(LispVal strExpr) {
		LispSymbol s = new LispSymbol("__fmtcs");
		LispSymbol n = new LispSymbol("__fmtcn");
		LispSymbol i = new LispSymbol("__fmtci");
		LispVal notAlpha = fmtCall(LispNames.NOT, fmtCall(LispNames.ALPHA_CHAR_P, fmtCall(LispNames.CHAR, s, i)));
		LispVal scan = fmtCall(LispNames.WHILE, fmtCall(LispNames.AND, fmtCall(LispNames.LT, i, n), notAlpha),
				fmtCall(LispNames.SETQ, i, fmtCall(LispNames.ADD, i, new LispInteger(1))));
		LispVal iPlus1 = fmtCall(LispNames.ADD, i, new LispInteger(1));
		LispVal recombined = fmtConcat(fmtCall(LispNames.SUBSEQ, s, new LispInteger(0), i),
				fmtCall(LispNames.STRING_UPCASE, fmtCall(LispNames.SUBSEQ, s, i, iPlus1)),
				fmtCall(LispNames.SUBSEQ, s, iPlus1));
		LispVal body = makeIf(fmtCall(LispNames.LT, i, n), recombined, s);
		List<LispVal> bindings = List.of(listToCons(List.of(s, fmtCall(LispNames.STRING_DOWNCASE, strExpr))),
				listToCons(List.of(n, fmtCall(LispNames.LENGTH, s))), listToCons(List.of(i, new LispInteger(0))));
		return listToCons(List.of(new LispSymbol(LispNames.LET_STAR), listToCons(bindings), scan, body));
	}

	/**
	 * Wraps a padded field with the overflow check: when the field width (literal
	 * {@code w} at {@code widthIdx}) and an overflow character (literal at
	 * {@code ovfIdx}) are both given and the formatted text exceeds the width, the field
	 * is replaced by {@code w} copies of the overflow character.
	 */
	private static LispVal maybeOverflow(LispVal strExpr, List<LispVal> params, int widthIdx, int ovfIdx,
			char directive) {
		if (!fmtHasParam(params, ovfIdx)) {
			return strExpr;
		}
		if (!(fmtParam(params, ovfIdx) instanceof LispInteger ovf)
				|| !(fmtParam(params, widthIdx) instanceof LispInteger w)) {
			throw new UnsupportedOperationException(
					"format: the overflow character of ~" + directive + " requires literal width and character");
		}
		LispSymbol r = new LispSymbol("__fmtovf");
		LispVal over = fmtCall(LispNames.GT, fmtCall(LispNames.LENGTH, r), new LispInteger(w.value()));
		LispVal filled = new LispString(String.valueOf((char) ovf.value()).repeat((int) w.value()));
		LispVal binding = listToCons(List.of(listToCons(List.of(r, strExpr))));
		return listToCons(List.of(new LispSymbol(LispNames.LET), binding, makeIf(over, filled, r)));
	}

	/**
	 * The k-th element of a runtime list: {@code (car base)} /
	 * {@code (car (nthcdr k base))}.
	 */
	private static LispVal carChain(LispVal base, int k) {
		return (k == 0) ? fmtCall(LispNames.CAR, base)
				: fmtCall(LispNames.CAR, fmtCall(LispNames.NTHCDR, new LispInteger(k), base));
	}

	/**
	 * Binds the iteration item temporaries to successive elements of {@code base} around
	 * {@code bodyExpr} (no binding when the body consumed no items).
	 */
	private static LispVal letItems(List<LispSymbol> items, LispVal base, LispVal bodyExpr) {
		if (items.isEmpty()) {
			return bodyExpr;
		}
		List<LispVal> bindings = new java.util.ArrayList<>();
		for (int k = 0; k < items.size(); k++) {
			bindings.add(listToCons(List.of(items.get(k), carChain(base, k))));
		}
		return listToCons(List.of(new LispSymbol(LispNames.LET_STAR), listToCons(bindings), bodyExpr));
	}

	/**
	 * Builds the {@code ~{str~}} loop over a flat runtime list: each pass binds the
	 * body's item temporaries to the next {@code m} elements, appends the body string to
	 * an accumulator, and advances the cursor by {@code m}; an optional {@code maxExpr}
	 * caps the number of passes.
	 */
	private static LispVal flatLoopExpr(LispSymbol listSym, FmtArgs items, LispVal bodyExpr, @Nullable LispVal maxExpr,
			int id) {
		int m = items.position();
		LispSymbol l = new LispSymbol("__fmtl" + id);
		LispSymbol o = new LispSymbol("__fmto" + id);
		LispSymbol c = new LispSymbol("__fmtc" + id);
		List<LispVal> bindings = new java.util.ArrayList<>();
		bindings.add(listToCons(List.of(l, listSym)));
		bindings.add(listToCons(List.of(o, new LispString(""))));
		LispVal test = fmtCall(LispNames.CONSP, l);
		if (maxExpr != null) {
			bindings.add(listToCons(List.of(c, new LispInteger(0))));
			test = fmtCall(LispNames.AND, test, fmtCall(LispNames.LT, c, maxExpr));
		}
		LispVal append = letItems(items.items(), l,
				fmtCall(LispNames.SETQ, o, fmtCall(LispNames.STRING_CONCAT, o, bodyExpr)));
		LispVal advance = fmtCall(LispNames.SETQ, l,
				(m == 1) ? fmtCall(LispNames.CDR, l) : fmtCall(LispNames.NTHCDR, new LispInteger(m), l));
		List<LispVal> loopParts = new java.util.ArrayList<>(
				List.of(new LispSymbol(LispNames.WHILE), test, append, advance));
		if (maxExpr != null) {
			loopParts.add(fmtCall(LispNames.SETQ, c, fmtCall(LispNames.ADD, c, new LispInteger(1))));
		}
		return listToCons(List.of(new LispSymbol(LispNames.LET), listToCons(bindings), listToCons(loopParts), o));
	}

	/**
	 * Builds the {@code ~:{str~}} loop over a runtime list of sublists: each pass binds
	 * the body's item temporaries to the elements of the next sublist and advances by
	 * one.
	 */
	private static LispVal sublistLoopExpr(LispSymbol listSym, FmtArgs items, LispVal bodyExpr,
			@Nullable LispVal maxExpr, int id) {
		LispSymbol l = new LispSymbol("__fmtl" + id);
		LispSymbol o = new LispSymbol("__fmto" + id);
		LispSymbol c = new LispSymbol("__fmtc" + id);
		LispSymbol sub = new LispSymbol("__fmtsl" + id);
		List<LispVal> bindings = new java.util.ArrayList<>();
		bindings.add(listToCons(List.of(l, listSym)));
		bindings.add(listToCons(List.of(o, new LispString(""))));
		LispVal test = fmtCall(LispNames.CONSP, l);
		if (maxExpr != null) {
			bindings.add(listToCons(List.of(c, new LispInteger(0))));
			test = fmtCall(LispNames.AND, test, fmtCall(LispNames.LT, c, maxExpr));
		}
		List<LispVal> innerBindings = new java.util.ArrayList<>();
		innerBindings.add(listToCons(List.of(sub, fmtCall(LispNames.CAR, l))));
		List<LispSymbol> syms = items.items();
		for (int k = 0; k < syms.size(); k++) {
			innerBindings.add(listToCons(List.of(syms.get(k), carChain(sub, k))));
		}
		LispVal append = listToCons(List.of(new LispSymbol(LispNames.LET_STAR), listToCons(innerBindings),
				fmtCall(LispNames.SETQ, o, fmtCall(LispNames.STRING_CONCAT, o, bodyExpr))));
		LispVal advance = fmtCall(LispNames.SETQ, l, fmtCall(LispNames.CDR, l));
		List<LispVal> loopParts = new java.util.ArrayList<>(
				List.of(new LispSymbol(LispNames.WHILE), test, append, advance));
		if (maxExpr != null) {
			loopParts.add(fmtCall(LispNames.SETQ, c, fmtCall(LispNames.ADD, c, new LispInteger(1))));
		}
		return listToCons(List.of(new LispSymbol(LispNames.LET), listToCons(bindings), listToCons(loopParts), o));
	}

	private static final String ERROR_ARG_VAR = "__error_arg";

	/**
	 * Expands {@code (error control args...)} into {@code (%error message)}, where
	 * {@code message} is built with the same control-string machinery as
	 * {@code (format nil control args...)} (so {@code ~a}/{@code ~s}/{@code ~%}
	 * directives are supported). The control string must be a literal, mirroring
	 * {@code format}; passing a condition object is not supported.
	 * @param cons the error expression
	 * @return the expanded expression
	 */
	public static LispVal expandError(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException("error expects a control string");
		}
		if (!(parts.get(1) instanceof LispString control)) {
			throw new UnsupportedOperationException(
					"error requires a literal control string, got: " + parts.get(1).print());
		}
		List<LispVal> args = parts.subList(2, parts.size());
		List<LispVal> bindings = new java.util.ArrayList<>();
		List<LispSymbol> argSyms = new java.util.ArrayList<>();
		for (int i = 0; i < args.size(); i++) {
			LispSymbol g = new LispSymbol(ERROR_ARG_VAR + i);
			argSyms.add(g);
			bindings.add(listToCons(List.of(g, args.get(i))));
		}
		List<LispVal> pieces = opsToPieces(new FmtParser(control.value()).parseTop(FmtArgs.forTemps(argSyms)));
		// Fold the pieces into nested %string-concat calls (left-associative), as the
		// format nil destination does.
		LispVal message = pieces.isEmpty() ? new LispString("") : pieces.get(0);
		for (int i = 1; i < pieces.size(); i++) {
			message = listToCons(List.of(new LispSymbol(LispNames.STRING_CONCAT), message, pieces.get(i)));
		}
		LispVal errorCall = listToCons(List.of(new LispSymbol(LispNames.ERROR_INTERNAL), message));
		if (bindings.isEmpty()) {
			return errorCall;
		}
		List<LispVal> letParts = new java.util.ArrayList<>();
		letParts.add(new LispSymbol(LispNames.LET));
		letParts.add(listToCons(bindings));
		letParts.add(errorCall);
		return listToCons(letParts);
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
		// mod differs from rem only when the remainder and divisor have opposite signs
		// (then add the divisor). The sign comparison multiplies the signums rather than
		// (* r b) directly, which would overflow the WASM i31 range for large operands
		// and
		// spuriously trigger the correction.
		LispVal sgnR = listToCons(List.of(new LispSymbol(LispNames.SIGNUM), rv));
		LispVal sgnB = listToCons(List.of(new LispSymbol(LispNames.SIGNUM), bv));
		LispVal product = listToCons(List.of(new LispSymbol(LispNames.MUL), sgnR, sgnB));
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
			if (LispNames.GCD.equals(name) || LispNames.LOGIOR.equals(name) || LispNames.LOGXOR.equals(name)) {
				return new LispInteger(0);
			}
			if (LispNames.LCM.equals(name)) {
				return new LispInteger(1);
			}
			if (LispNames.LOGAND.equals(name)) {
				return new LispInteger(-1);
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

	/**
	 * Expands (list* a b ... z) into nested cons cells whose final tail is the last
	 * argument: {@code (list* a b c) -> (cons a (cons b c))}. A single argument expands
	 * to itself.
	 * @param cons the list* expression
	 * @return the expanded expression
	 */
	public static LispVal expandListStar(LispCons cons) {
		List<LispVal> parts = cons.toList();
		int n = parts.size() - 1;
		if (n == 0) {
			throw new IllegalArgumentException("list* requires at least one argument");
		}
		// The last argument becomes the tail; cons the preceding ones onto it.
		LispVal result = parts.get(n);
		for (int i = n - 1; i >= 1; i--) {
			result = listToCons(List.of(new LispSymbol(LispNames.CONS), parts.get(i), result));
		}
		return result;
	}

	/**
	 * Expands (acons key value alist) into (cons (cons key value) alist).
	 * @param cons the acons expression
	 * @return the expanded expression
	 */
	public static LispVal expandAcons(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal pair = listToCons(List.of(new LispSymbol(LispNames.CONS), parts.get(1), parts.get(2)));
		return listToCons(List.of(new LispSymbol(LispNames.CONS), pair, parts.get(3)));
	}

	/**
	 * Expands (endp x) into (null x). The Common Lisp improper-list error is relaxed.
	 * @param cons the endp expression
	 * @return the expanded expression
	 */
	public static LispVal expandEndp(LispCons cons) {
		return callOf(LispNames.NULL, cons.toList().get(1));
	}

	/**
	 * Expands (elt seq n) into a runtime dispatch on the sequence type: {@code char} for
	 * a string, {@code nth} for a list.
	 * @param cons the elt expression
	 * @return the expanded expression
	 */
	public static LispVal expandElt(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol seq = new LispSymbol("__elt_seq");
		LispSymbol idx = new LispSymbol("__elt_idx");
		LispVal bindings = listToCons(
				List.of(listToCons(List.of(seq, parts.get(1))), listToCons(List.of(idx, parts.get(2)))));
		LispVal stringCase = listToCons(List.of(new LispSymbol(LispNames.CHAR), seq, idx));
		LispVal listCase = listToCons(List.of(new LispSymbol(LispNames.NTH), idx, seq));
		LispVal body = makeIf(listToCons(List.of(new LispSymbol(LispNames.STRINGP), seq)), stringCase, listCase);
		return listToCons(List.of(new LispSymbol(LispNames.LET), bindings, body));
	}

	/**
	 * Expands (rassoc value alist) into a do/return scan returning the first pair whose
	 * cdr is {@code eql} to the value, or nil. The mirror of {@code assoc} (which matches
	 * on the car).
	 * @param cons the rassoc expression
	 * @return the expanded expression
	 */
	public static LispVal expandRassoc(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol key = new LispSymbol("__rassoc_key");
		LispSymbol cur = new LispSymbol("__rassoc_cur");
		LispVal pair = callOf(LispNames.CAR, cur);
		LispVal bindings = listToCons(List.of(listToCons(List.of(key, parts.get(1))),
				listToCons(List.of(cur, parts.get(2), callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), LispNil.INSTANCE));
		LispVal match = listToCons(
				List.of(new LispSymbol(LispNames.AND), listToCons(List.of(new LispSymbol(LispNames.CONSP), pair)),
						listToCons(List.of(new LispSymbol(LispNames.EQL), key, callOf(LispNames.CDR, pair)))));
		LispVal body = makeIf(match, makeReturn(pair), LispNil.INSTANCE);
		return expandDo((LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
	}

	/**
	 * Expands (revappend x y) into (append (reverse x) y).
	 * @param cons the revappend expression
	 * @return the expanded expression
	 */
	public static LispVal expandRevappend(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal rev = callOf(LispNames.REVERSE, parts.get(1));
		return listToCons(List.of(new LispSymbol(LispNames.APPEND), rev, parts.get(2)));
	}

	/**
	 * Expands (nreconc x y) into (nconc (nreverse x) y). Like {@code nreverse}, this
	 * implementation is non-destructive.
	 * @param cons the nreconc expression
	 * @return the expanded expression
	 */
	public static LispVal expandNreconc(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal rev = callOf(LispNames.NREVERSE, parts.get(1));
		return listToCons(List.of(new LispSymbol(LispNames.NCONC), rev, parts.get(2)));
	}

	/**
	 * Expands (maplist fn lst) into a do scan that applies the function to successive
	 * cdrs (tails) of the list, accumulating the results and reversing them at the end.
	 * Single-list only.
	 * @param cons the maplist expression
	 * @return the expanded expression
	 */
	public static LispVal expandMaplist(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol fn = new LispSymbol("__maplist_fn");
		LispSymbol lst = new LispSymbol("__maplist_lst");
		LispSymbol acc = new LispSymbol("__maplist_acc");
		LispSymbol cur = new LispSymbol("__maplist_cur");
		LispVal bindings = listToCons(List.of(listToCons(List.of(acc, LispNil.INSTANCE)),
				listToCons(List.of(cur, lst, callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), callOf(LispNames.REVERSE, acc)));
		LispVal call = listToCons(List.of(new LispSymbol(LispNames.FUNCALL), fn, cur));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.SETQ), acc,
				listToCons(List.of(new LispSymbol(LispNames.CONS), call, acc))));
		LispVal loop = expandDo(
				(LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
		return wrapMapListGuard(LispNames.MAPLIST, fn, parts.get(1), lst, parts.get(2), loop);
	}

	/**
	 * Expands (mapcon fn lst) like {@code maplist} but concatenates the result lists with
	 * {@code append} rather than collecting them. Single-list only.
	 * @param cons the mapcon expression
	 * @return the expanded expression
	 */
	public static LispVal expandMapcon(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispSymbol fn = new LispSymbol("__mapcon_fn");
		LispSymbol lst = new LispSymbol("__mapcon_lst");
		LispSymbol acc = new LispSymbol("__mapcon_acc");
		LispSymbol cur = new LispSymbol("__mapcon_cur");
		LispVal bindings = listToCons(List.of(listToCons(List.of(acc, LispNil.INSTANCE)),
				listToCons(List.of(cur, lst, callOf(LispNames.CDR, cur)))));
		LispVal endClause = listToCons(List.of(callOf(LispNames.ATOM, cur), acc));
		LispVal call = listToCons(List.of(new LispSymbol(LispNames.FUNCALL), fn, cur));
		LispVal body = listToCons(List.of(new LispSymbol(LispNames.SETQ), acc,
				listToCons(List.of(new LispSymbol(LispNames.APPEND), acc, call))));
		LispVal loop = expandDo(
				(LispCons) listToCons(List.of(new LispSymbol(LispNames.DO), bindings, endClause, body)));
		return wrapMapListGuard(LispNames.MAPCON, fn, parts.get(1), lst, parts.get(2), loop);
	}

	/**
	 * Wraps a {@code maplist}/{@code mapcon} loop in a guard that binds the function and
	 * list arguments once (preserving left-to-right evaluation: function then list) and
	 * signals an error when the list argument is not a list. The list value is bound to
	 * {@code lstSym}, which the loop walks. nil is a valid empty list.
	 * @param name the operator name (for the error message)
	 * @param fnSym the symbol the function argument is bound to
	 * @param fnArg the (unevaluated) function argument form
	 * @param lstSym the symbol the list argument is bound to
	 * @param listArg the (unevaluated) list argument form
	 * @param loop the expanded loop that walks {@code lstSym}
	 * @return the guarded expression
	 */
	private static LispVal wrapMapListGuard(String name, LispSymbol fnSym, LispVal fnArg, LispSymbol lstSym,
			LispVal listArg, LispVal loop) {
		LispVal letBindings = listToCons(
				List.of(listToCons(List.of(fnSym, fnArg)), listToCons(List.of(lstSym, listArg))));
		LispVal guard = makeIf(listToCons(List.of(new LispSymbol(LispNames.LISTP), lstSym)), loop,
				mapNotAListError(name, lstSym));
		return listToCons(List.of(new LispSymbol(LispNames.LET), letBindings, guard));
	}

	/**
	 * Builds {@code (error "<name>: argument is not a list: ~s ..." valueSym)} for the
	 * map* family list-type guard.
	 * @param name the operator name
	 * @param valueSym the symbol holding the offending value
	 * @return the error expression
	 */
	private static LispVal mapNotAListError(String name, LispSymbol valueSym) {
		return listToCons(List.of(new LispSymbol(LispNames.ERROR),
				new LispString(name + ": argument is not a list: ~s (use map for strings/vectors)"), valueSym));
	}

	/**
	 * Expands (map result-type function seq...) into a {@code do*} index loop that walks
	 * every sequence in parallel up to the shortest length, applying the function to one
	 * element from each. Elements are read with a runtime {@code (if (stringp s) (char s
	 * i) (nth i s))} so the same expansion handles both list and string sequences.
	 *
	 * <p>
	 * The result type is a literal designator (resolved statically, like
	 * {@code concatenate}): {@code 'list} collects the results into a list,
	 * {@code 'string} builds a string from the (character) results, and {@code nil} calls
	 * the function for effect and returns nil. The function designator is normalized so a
	 * {@code 'name} quote resolves in the function namespace before being bound.
	 * @param cons the map expression
	 * @return the expanded expression
	 */
	public static LispVal expandMap(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 4) {
			throw new UnsupportedOperationException("map expects a result-type, a function, and at least one sequence");
		}
		LispVal resultTypeForm = parts.get(1);
		String resultType = quotedSymbolName(resultTypeForm);
		boolean nilResult = isNilForm(resultTypeForm);
		if (!nilResult && !"list".equals(resultType) && !"string".equals(resultType)) {
			throw new UnsupportedOperationException(
					"map supports only the 'list, 'string, or nil result types, got: " + resultTypeForm.print());
		}
		LispVal fnForm = normalizeFunctionDesignator(parts.get(2));
		List<LispVal> seqs = parts.subList(3, parts.size());

		LispSymbol fnVar = new LispSymbol("__map_fn");
		LispSymbol nVar = new LispSymbol("__map_n");
		LispSymbol iVar = new LispSymbol("__map_i");
		LispSymbol accVar = new LispSymbol("__map_acc");

		List<LispVal> bindings = new java.util.ArrayList<>();
		bindings.add(listToCons(List.of(fnVar, fnForm)));
		List<LispSymbol> seqVars = new java.util.ArrayList<>();
		for (int k = 0; k < seqs.size(); k++) {
			LispSymbol sv = new LispSymbol("__map_s" + k);
			seqVars.add(sv);
			bindings.add(listToCons(List.of(sv, seqs.get(k))));
		}
		// n = (length s0) for a single sequence, else (min (length s0) (length s1) ...).
		LispVal lenExpr;
		if (seqVars.size() == 1) {
			lenExpr = callOf(LispNames.LENGTH, seqVars.get(0));
		}
		else {
			List<LispVal> minParts = new java.util.ArrayList<>();
			minParts.add(new LispSymbol(LispNames.MIN));
			for (LispSymbol sv : seqVars) {
				minParts.add(callOf(LispNames.LENGTH, sv));
			}
			lenExpr = listToCons(minParts);
		}
		bindings.add(listToCons(List.of(nVar, lenExpr)));

		// call = (funcall fn (elt s0 i) (elt s1 i) ...) where elt dispatches on stringp.
		List<LispVal> callParts = new java.util.ArrayList<>();
		callParts.add(new LispSymbol(LispNames.FUNCALL));
		callParts.add(fnVar);
		for (LispSymbol sv : seqVars) {
			LispVal stringElt = listToCons(List.of(new LispSymbol(LispNames.CHAR), sv, iVar));
			LispVal listElt = listToCons(List.of(new LispSymbol(LispNames.NTH), iVar, sv));
			callParts.add(makeIf(listToCons(List.of(new LispSymbol(LispNames.STRINGP), sv)), stringElt, listElt));
		}
		LispVal call = listToCons(callParts);

		LispVal accInit;
		LispVal accStep;
		LispVal resultForm;
		if ("string".equals(resultType)) {
			accInit = new LispString("");
			accStep = listToCons(List.of(new LispSymbol(LispNames.STRING_CONCAT), accVar,
					listToCons(List.of(new LispSymbol(LispNames.PRINC_TO_STRING), call))));
			resultForm = accVar;
		}
		else if ("list".equals(resultType)) {
			accInit = LispNil.INSTANCE;
			accStep = listToCons(List.of(new LispSymbol(LispNames.CONS), call, accVar));
			resultForm = callOf(LispNames.REVERSE, accVar);
		}
		else {
			// nil: call the function for effect, return nil.
			accInit = LispNil.INSTANCE;
			accStep = call;
			resultForm = LispNil.INSTANCE;
		}
		// The accumulator must be stepped before the index so the element access reads
		// the
		// current i (do* applies steps in source order at the end of each iteration).
		bindings.add(listToCons(List.of(accVar, accInit, accStep)));
		bindings.add(listToCons(
				List.of(iVar, new LispInteger(0), listToCons(List.of(new LispSymbol(LispNames.ONE_PLUS), iVar)))));

		LispVal endTest = listToCons(List.of(new LispSymbol(LispNames.GE), iVar, nVar));
		LispVal endClause = listToCons(List.of(endTest, resultForm));
		LispVal doStar = listToCons(List.of(new LispSymbol(LispNames.DO_STAR), listToCons(bindings), endClause));
		return expandDoStar((LispCons) doStar);
	}

	/** Returns the symbol name inside a {@code (quote name)} form, or null otherwise. */
	private static @Nullable String quotedSymbolName(LispVal form) {
		if (form instanceof LispCons quoted) {
			List<LispVal> p = quoted.toList();
			if (p.size() == 2 && p.get(0) instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
					&& p.get(1) instanceof LispSymbol s) {
				return s.name();
			}
		}
		return null;
	}

	/**
	 * Returns true when the form denotes nil (the nil literal or the {@code nil} symbol).
	 */
	private static boolean isNilForm(LispVal form) {
		return form instanceof LispNil || (form instanceof LispSymbol s && "nil".equals(s.name()));
	}

	/**
	 * Normalizes a function designator so a {@code (quote name)} resolves in the function
	 * namespace ({@code (function name)}) once bound to a variable; other forms (a
	 * {@code #'name}/{@code (function ...)} value or a lambda) are returned unchanged.
	 */
	private static LispVal normalizeFunctionDesignator(LispVal form) {
		String name = quotedSymbolName(form);
		if (name != null) {
			return listToCons(List.of(new LispSymbol(LispNames.FUNCTION), new LispSymbol(name)));
		}
		return form;
	}

	/**
	 * Expands (notany pred lst) into (not (some pred lst)).
	 * @param cons the notany expression
	 * @return the expanded expression
	 */
	public static LispVal expandNotany(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal some = listToCons(List.of(new LispSymbol(LispNames.SOME), parts.get(1), parts.get(2)));
		return makeNot(some);
	}

	/**
	 * Expands (notevery pred lst) into (not (every pred lst)).
	 * @param cons the notevery expression
	 * @return the expanded expression
	 */
	public static LispVal expandNotevery(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal every = listToCons(List.of(new LispSymbol(LispNames.EVERY), parts.get(1), parts.get(2)));
		return makeNot(every);
	}

	/**
	 * Expands (prog2 first second body...) into (progn first (prog1 second body...)): all
	 * forms are evaluated in order and the value of the second is returned.
	 * @param cons the prog2 expression
	 * @return the expanded expression
	 */
	public static LispVal expandProg2(LispCons cons) {
		List<LispVal> parts = cons.toList();
		LispVal first = parts.get(1);
		List<LispVal> prog1Parts = new java.util.ArrayList<>();
		prog1Parts.add(new LispSymbol(LispNames.PROG1));
		prog1Parts.addAll(parts.subList(2, parts.size()));
		LispVal prog1Expr = expandProg1((LispCons) listToCons(prog1Parts));
		return makeProgn(List.of(first, prog1Expr));
	}

	/**
	 * Expands (psetq v1 e1 v2 e2 ...) into a let that binds every right-hand side to a
	 * temporary before assigning any variable, giving the parallel-assignment semantics
	 * of Common Lisp. The result is nil.
	 * @param cons the psetq expression
	 * @return the expanded expression
	 */
	public static LispVal expandPsetq(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if ((parts.size() - 1) % 2 != 0) {
			throw new IllegalArgumentException("psetq requires an even number of arguments");
		}
		List<LispVal> bindings = new java.util.ArrayList<>();
		List<LispVal> assignments = new java.util.ArrayList<>();
		for (int i = 1; i < parts.size(); i += 2) {
			LispVal var = parts.get(i);
			LispVal val = parts.get(i + 1);
			LispSymbol temp = new LispSymbol("__psetq_" + (i / 2));
			bindings.add(listToCons(List.of(temp, val)));
			assignments.add(listToCons(List.of(new LispSymbol(LispNames.SETQ), var, temp)));
		}
		List<LispVal> letParts = new java.util.ArrayList<>();
		letParts.add(new LispSymbol(LispNames.LET));
		letParts.add(bindings.isEmpty() ? LispNil.INSTANCE : listToCons(bindings));
		letParts.addAll(assignments);
		letParts.add(LispNil.INSTANCE);
		return listToCons(letParts);
	}

	private static final String TYPECASE_VAR = "__typecase";

	/**
	 * Expands (typecase keyform (type body...) ... (t default)) into a let/cond that
	 * dispatches on the type of the keyform using the built-in type predicates. An
	 * unknown type symbol is rejected at expansion time.
	 * @param cons the typecase expression
	 * @return the expanded expression
	 */
	public static LispVal expandTypecase(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException("typecase expects a keyform");
		}
		LispVal keyform = parts.get(1);
		List<LispVal> clauses = parts.subList(2, parts.size());
		LispSymbol var = new LispSymbol(TYPECASE_VAR);
		List<LispVal> condParts = new java.util.ArrayList<>();
		condParts.add(new LispSymbol(LispNames.COND));
		for (LispVal clauseVal : clauses) {
			if (!(clauseVal instanceof LispCons clause)) {
				throw new IllegalArgumentException("typecase clause must be a list, got: " + clauseVal.print());
			}
			List<LispVal> clauseParts = clause.toList();
			List<LispVal> body = clauseParts.subList(1, clauseParts.size());
			LispVal test = makeTypecaseTest(var, clauseParts.get(0));
			List<LispVal> condClause = new java.util.ArrayList<>();
			condClause.add(test);
			if (body.isEmpty()) {
				condClause.add(LispNil.INSTANCE);
			}
			else {
				condClause.addAll(body);
			}
			condParts.add(listToCons(condClause));
		}
		return makeLet(TYPECASE_VAR, keyform, listToCons(condParts));
	}

	private static final String ETYPECASE_VAR = "__etypecase";

	/**
	 * Expands {@code (etypecase keyform (type body...) ...)} like {@code typecase} but
	 * appends a final {@code (error ...)} clause so an object whose type matches no
	 * clause signals an error (Common Lisp's exhaustive {@code etypecase}).
	 * @param cons the etypecase expression
	 * @return the expanded expression
	 */
	public static LispVal expandEtypecase(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException("etypecase expects a keyform");
		}
		LispVal keyform = parts.get(1);
		List<LispVal> clauses = parts.subList(2, parts.size());
		LispSymbol var = new LispSymbol(ETYPECASE_VAR);
		List<LispVal> condParts = new java.util.ArrayList<>();
		condParts.add(new LispSymbol(LispNames.COND));
		for (LispVal clauseVal : clauses) {
			if (!(clauseVal instanceof LispCons clause)) {
				throw new IllegalArgumentException("etypecase clause must be a list, got: " + clauseVal.print());
			}
			List<LispVal> clauseParts = clause.toList();
			List<LispVal> body = clauseParts.subList(1, clauseParts.size());
			LispVal test = makeTypecaseTest(var, clauseParts.get(0));
			List<LispVal> condClause = new java.util.ArrayList<>();
			condClause.add(test);
			if (body.isEmpty()) {
				condClause.add(LispNil.INSTANCE);
			}
			else {
				condClause.addAll(body);
			}
			condParts.add(listToCons(condClause));
		}
		condParts.add(makeExhaustiveErrorClause(var, "ETYPECASE"));
		return makeLet(ETYPECASE_VAR, keyform, listToCons(condParts));
	}

	private static LispVal makeTypecaseTest(LispSymbol var, LispVal typeSpec) {
		if (typeSpec instanceof LispTrue
				|| (typeSpec instanceof LispSymbol s && LispNames.OTHERWISE.equals(s.name()))) {
			return LispTrue.INSTANCE;
		}
		if (!(typeSpec instanceof LispSymbol sym)) {
			throw new IllegalArgumentException("typecase type must be a type symbol, got: " + typeSpec.print());
		}
		String pred = switch (sym.name()) {
			case "integer", "fixnum", "bignum" -> LispNames.INTEGERP;
			case "float", "single-float", "double-float", "short-float", "long-float" -> LispNames.FLOATP;
			case "number", "real" -> LispNames.NUMBERP;
			case "rational", "ratio" -> LispNames.RATIONALP;
			case "string" -> LispNames.STRINGP;
			case "symbol" -> LispNames.SYMBOLP;
			case "keyword" -> LispNames.KEYWORDP;
			case "cons" -> LispNames.CONSP;
			case "list" -> LispNames.LISTP;
			case "null" -> LispNames.NULL;
			case "atom" -> LispNames.ATOM;
			default -> throw new IllegalArgumentException("typecase does not support the type: " + sym.name());
		};
		return callOf(pred, var);
	}

	private static LispCons listToCons(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return (LispCons) result;
	}

}
