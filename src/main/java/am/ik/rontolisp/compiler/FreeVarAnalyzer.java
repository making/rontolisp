package am.ik.rontolisp.compiler;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Static utility for analyzing free variables in Lisp expressions. Shared between JVM and
 * WASM compilers for closure support.
 */
public final class FreeVarAnalyzer {

	private static final Set<String> SPECIAL_NAMES = Set.of(LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.DIV,
			LispNames.MOD, LispNames.EQ, LispNames.LT, LispNames.GT, LispNames.LE, LispNames.GE, LispNames.PRINT,
			LispNames.READ_LINE, LispNames.QUOTE, LispNames.IF, LispNames.LET, LispNames.PROGN, LispNames.SETQ,
			LispNames.DEFUN, LispNames.LAMBDA, LispNames.NULL, LispNames.LIST, LispNames.CAR, LispNames.CDR,
			LispNames.CONS, LispNames.FUNCALL, LispNames.ATOM, LispNames.NUMBERP, LispNames.INTEGERP, LispNames.FLOATP,
			LispNames.SYMBOLP, LispNames.STRINGP, LispNames.LISTP, LispNames.CONSP, LispNames.KEYWORDP);

	private FreeVarAnalyzer() {
	}

	/**
	 * Finds free variables in the given body expressions. A free variable is a symbol
	 * reference that is not in boundVars and not a known function or special form.
	 * @param body the expressions to analyze
	 * @param boundVars variables bound in the current scope (params, let bindings)
	 * @param knownFunctions names of defined functions
	 * @return ordered set of free variable names
	 */
	public static LinkedHashSet<String> findFreeVars(List<LispVal> body, Set<String> boundVars,
			Set<String> knownFunctions) {
		return findFreeVars(body, boundVars, knownFunctions, Set.of());
	}

	/**
	 * Finds free variables, excluding top-level global variable names. A global is
	 * resolved directly from its backing store (a JVM static field / a WASM module-level
	 * global), so it must not be treated as a free variable that a nested lambda would
	 * try to capture from the enclosing scope.
	 * @param body the expressions to analyze
	 * @param boundVars variables bound in the current scope (params, let bindings)
	 * @param knownFunctions names of defined functions
	 * @param globals names of top-level global variables (excluded from the result)
	 * @return ordered set of free variable names
	 */
	public static LinkedHashSet<String> findFreeVars(List<LispVal> body, Set<String> boundVars,
			Set<String> knownFunctions, Set<String> globals) {
		LinkedHashSet<String> freeVars = new LinkedHashSet<>();
		for (LispVal expr : body) {
			collectFreeVars(expr, boundVars, knownFunctions, globals, freeVars);
		}
		return freeVars;
	}

	/**
	 * Finds which variables in localVars are captured by nested lambda-as-value
	 * expressions. These variables need to be boxed (allocated in mutable cells) so that
	 * the closure and outer scope share the same reference.
	 * @param body the expressions to scan
	 * @param localVars the local variable names in the current scope
	 * @param knownFunctions names of defined functions
	 * @return set of variable names that need boxing
	 */
	public static Set<String> findCapturedVars(List<LispVal> body, Set<String> localVars, Set<String> knownFunctions) {
		Set<String> captured = new HashSet<>();
		for (LispVal expr : body) {
			collectCapturedVars(expr, localVars, knownFunctions, captured, false);
		}
		return captured;
	}

	private static void collectFreeVars(LispVal expr, Set<String> boundVars, Set<String> knownFunctions,
			Set<String> globals, LinkedHashSet<String> freeVars) {
		switch (expr) {
			case LispSymbol sym -> {
				String name = sym.name();
				if (!sym.isKeyword() && !SPECIAL_NAMES.contains(name) && !boundVars.contains(name)
						&& !knownFunctions.contains(name) && !globals.contains(name)) {
					freeVars.add(name);
				}
			}
			case LispCons cons -> {
				LispVal head = cons.car();
				if (head instanceof LispSymbol sym) {
					switch (sym.name()) {
						case LispNames.QUOTE -> {
							// skip quoted expressions
						}
						case LispNames.LAMBDA -> {
							List<LispVal> parts = cons.toList();
							Set<String> innerBound = new HashSet<>(boundVars);
							innerBound.addAll(extractParamNames(parts.get(1)));
							for (int i = 2; i < parts.size(); i++) {
								collectFreeVars(parts.get(i), innerBound, knownFunctions, globals, freeVars);
							}
						}
						case LispNames.LET -> {
							List<LispVal> parts = cons.toList();
							Set<String> innerBound = new HashSet<>(boundVars);
							LispVal bindings = parts.get(1);
							if (bindings instanceof LispCons bindingsCons) {
								for (LispVal binding : bindingsCons.toList()) {
									LispCons pair = (LispCons) binding;
									List<LispVal> pairList = pair.toList();
									// The init expression is evaluated in outer scope
									collectFreeVars(pairList.get(1), boundVars, knownFunctions, globals, freeVars);
									innerBound.add(((LispSymbol) pairList.get(0)).name());
								}
							}
							for (int i = 2; i < parts.size(); i++) {
								collectFreeVars(parts.get(i), innerBound, knownFunctions, globals, freeVars);
							}
						}
						case LispNames.DEFUN -> {
							// defun body is handled separately; skip
						}
						case LispNames.LET_STAR -> collectFreeVars(LispMacroExpander.expandLetStar(cons), boundVars,
								knownFunctions, globals, freeVars);
						case LispNames.DOLIST -> collectFreeVars(LispMacroExpander.expandDolist(cons), boundVars,
								knownFunctions, globals, freeVars);
						case LispNames.DO -> collectFreeVars(LispMacroExpander.expandDo(cons), boundVars,
								knownFunctions, globals, freeVars);
						case LispNames.LOOP -> collectFreeVars(LispMacroExpander.expandLoop(cons), boundVars,
								knownFunctions, globals, freeVars);
						// Expand before walking: the default walk would misread the raw
						// shapes (e.g. the type symbol in (the integer x), declaration
						// specifiers in declare) as variable references.
						case LispNames.CHECK_TYPE -> collectFreeVars(LispMacroExpander.expandCheckType(cons), boundVars,
								knownFunctions, globals, freeVars);
						case LispNames.ASSERT -> collectFreeVars(LispMacroExpander.expandAssert(cons), boundVars,
								knownFunctions, globals, freeVars);
						case LispNames.DECLARE, LispNames.DECLAIM, LispNames.PROCLAIM -> {
							// Parsed no-ops: no variable references.
						}
						case LispNames.THE -> collectFreeVars(LispMacroExpander.expandThe(cons), boundVars,
								knownFunctions, globals, freeVars);
						case LispNames.EVAL_WHEN -> collectFreeVars(LispMacroExpander.expandEvalWhen(cons), boundVars,
								knownFunctions, globals, freeVars);
						// Expand before walking: the default walk would misread the
						// definition lists as call forms. The expansion generates fresh
						// variable names, but they are all bound inside it, so the free
						// set is the same as the compile-time expansion's.
						case LispNames.FLET -> collectFreeVars(LispMacroExpander.expandFlet(cons), boundVars,
								knownFunctions, globals, freeVars);
						case LispNames.LABELS -> collectFreeVars(LispMacroExpander.expandLabels(cons), boundVars,
								knownFunctions, globals, freeVars);
						// Expand before walking: the default walk would misread the
						// multiple-value-bind variable list as a call form. The temp
						// names
						// are counter-fresh but all bound inside the expansion.
						case LispNames.MULTIPLE_VALUE_BIND ->
							collectFreeVars(LispMacroExpander.expandMultipleValueBind(cons), boundVars, knownFunctions,
									globals, freeVars);
						case LispNames.MULTIPLE_VALUE_LIST ->
							collectFreeVars(LispMacroExpander.expandMultipleValueList(cons), boundVars, knownFunctions,
									globals, freeVars);
						case LispNames.MULTIPLE_VALUE_CALL ->
							collectFreeVars(LispMacroExpander.expandMultipleValueCall(cons), boundVars, knownFunctions,
									globals, freeVars);
						case LispNames.NTH_VALUE -> collectFreeVars(LispMacroExpander.expandNthValue(cons), boundVars,
								knownFunctions, globals, freeVars);
						// Expand before walking: the default walk would misread the
						// destructuring pattern as a call form.
						case LispNames.DESTRUCTURING_BIND ->
							collectFreeVars(LispMacroExpander.expandDestructuringBind(cons), boundVars, knownFunctions,
									globals, freeVars);
						case LispNames.FUNCTION -> {
							// (function name) names the function namespace, not a
							// variable; (function (lambda ...)) is analyzed like lambda
							List<LispVal> parts = cons.toList();
							if (parts.size() == 2 && parts.get(1) instanceof LispCons) {
								collectFreeVars(parts.get(1), boundVars, knownFunctions, globals, freeVars);
							}
						}
						case LispNames.SETQ -> {
							List<LispVal> parts = cons.toList();
							String name = ((LispSymbol) parts.get(1)).name();
							if (!SPECIAL_NAMES.contains(name) && !boundVars.contains(name)
									&& !knownFunctions.contains(name) && !globals.contains(name)) {
								freeVars.add(name);
							}
							collectFreeVars(parts.get(2), boundVars, knownFunctions, globals, freeVars);
						}
						case LispNames.DEFVAR -> {
							// defvar names a global variable, not a lexical reference;
							// only
							// the optional init form can reference variables.
							List<LispVal> parts = cons.toList();
							if (parts.size() > 2) {
								collectFreeVars(parts.get(2), boundVars, knownFunctions, globals, freeVars);
							}
						}
						default -> {
							// Function call or special form: the operator resolves in
							// the function namespace (Lisp-2), so only the argument
							// subexpressions can reference variables
							List<LispVal> parts = cons.toList();
							for (int i = 1; i < parts.size(); i++) {
								collectFreeVars(parts.get(i), boundVars, knownFunctions, globals, freeVars);
							}
						}
					}
				}
				else {
					// Non-symbol head (e.g., ((lambda ...) args))
					List<LispVal> parts = cons.toList();
					for (LispVal part : parts) {
						collectFreeVars(part, boundVars, knownFunctions, globals, freeVars);
					}
				}
			}
			default -> {
				// Literals (integers, strings, nil, true) have no free variables
			}
		}
	}

	private static void collectCapturedVars(LispVal expr, Set<String> localVars, Set<String> knownFunctions,
			Set<String> captured, boolean insideLambda) {
		switch (expr) {
			case LispSymbol sym -> {
				if (insideLambda && localVars.contains(sym.name())) {
					captured.add(sym.name());
				}
			}
			case LispCons cons -> {
				LispVal head = cons.car();
				if (head instanceof LispSymbol sym) {
					switch (sym.name()) {
						case LispNames.QUOTE -> {
							// skip
						}
						case LispNames.LAMBDA -> {
							// Any reference to localVars inside a lambda body means
							// capture
							List<LispVal> parts = cons.toList();
							Set<String> lambdaParams = extractParamNames(parts.get(1));
							// Only look for captures of outer localVars, excluding
							// lambda's own params
							Set<String> outerVars = new HashSet<>(localVars);
							outerVars.removeAll(lambdaParams);
							for (int i = 2; i < parts.size(); i++) {
								collectCapturedVars(parts.get(i), outerVars, knownFunctions, captured, true);
							}
						}
						case LispNames.LET -> {
							List<LispVal> parts = cons.toList();
							LispVal bindings = parts.get(1);
							if (bindings instanceof LispCons bindingsCons) {
								for (LispVal binding : bindingsCons.toList()) {
									LispCons pair = (LispCons) binding;
									List<LispVal> pairList = pair.toList();
									collectCapturedVars(pairList.get(1), localVars, knownFunctions, captured,
											insideLambda);
								}
							}
							for (int i = 2; i < parts.size(); i++) {
								collectCapturedVars(parts.get(i), localVars, knownFunctions, captured, insideLambda);
							}
						}
						case LispNames.DEFUN -> {
							// skip
						}
						case LispNames.LET_STAR -> collectCapturedVars(LispMacroExpander.expandLetStar(cons), localVars,
								knownFunctions, captured, insideLambda);
						case LispNames.DOLIST -> collectCapturedVars(LispMacroExpander.expandDolist(cons), localVars,
								knownFunctions, captured, insideLambda);
						case LispNames.DO -> collectCapturedVars(LispMacroExpander.expandDo(cons), localVars,
								knownFunctions, captured, insideLambda);
						case LispNames.LOOP -> collectCapturedVars(LispMacroExpander.expandLoop(cons), localVars,
								knownFunctions, captured, insideLambda);
						// Expand before walking (same reason as collectFreeVars).
						case LispNames.CHECK_TYPE -> collectCapturedVars(LispMacroExpander.expandCheckType(cons),
								localVars, knownFunctions, captured, insideLambda);
						case LispNames.ASSERT -> collectCapturedVars(LispMacroExpander.expandAssert(cons), localVars,
								knownFunctions, captured, insideLambda);
						case LispNames.DECLARE, LispNames.DECLAIM, LispNames.PROCLAIM -> {
							// Parsed no-ops: no variable references.
						}
						case LispNames.THE -> collectCapturedVars(LispMacroExpander.expandThe(cons), localVars,
								knownFunctions, captured, insideLambda);
						case LispNames.EVAL_WHEN -> collectCapturedVars(LispMacroExpander.expandEvalWhen(cons),
								localVars, knownFunctions, captured, insideLambda);
						// Expand before walking (same reason as collectFreeVars).
						case LispNames.FLET -> collectCapturedVars(LispMacroExpander.expandFlet(cons), localVars,
								knownFunctions, captured, insideLambda);
						case LispNames.LABELS -> collectCapturedVars(LispMacroExpander.expandLabels(cons), localVars,
								knownFunctions, captured, insideLambda);
						// Expand before walking (same reason as collectFreeVars).
						case LispNames.MULTIPLE_VALUE_BIND ->
							collectCapturedVars(LispMacroExpander.expandMultipleValueBind(cons), localVars,
									knownFunctions, captured, insideLambda);
						case LispNames.MULTIPLE_VALUE_LIST ->
							collectCapturedVars(LispMacroExpander.expandMultipleValueList(cons), localVars,
									knownFunctions, captured, insideLambda);
						case LispNames.MULTIPLE_VALUE_CALL ->
							collectCapturedVars(LispMacroExpander.expandMultipleValueCall(cons), localVars,
									knownFunctions, captured, insideLambda);
						case LispNames.NTH_VALUE -> collectCapturedVars(LispMacroExpander.expandNthValue(cons),
								localVars, knownFunctions, captured, insideLambda);
						// Expand before walking (same reason as collectFreeVars).
						case LispNames.DESTRUCTURING_BIND ->
							collectCapturedVars(LispMacroExpander.expandDestructuringBind(cons), localVars,
									knownFunctions, captured, insideLambda);
						case LispNames.FUNCTION -> {
							List<LispVal> parts = cons.toList();
							if (parts.size() == 2 && parts.get(1) instanceof LispCons) {
								collectCapturedVars(parts.get(1), localVars, knownFunctions, captured, insideLambda);
							}
						}
						case LispNames.SETQ -> {
							List<LispVal> parts = cons.toList();
							String name = ((LispSymbol) parts.get(1)).name();
							if (insideLambda && localVars.contains(name)) {
								captured.add(name);
							}
							collectCapturedVars(parts.get(2), localVars, knownFunctions, captured, insideLambda);
						}
						case LispNames.DEFVAR -> {
							// defvar names a global variable; only the optional init form
							// can reference captured locals.
							List<LispVal> parts = cons.toList();
							if (parts.size() > 2) {
								collectCapturedVars(parts.get(2), localVars, knownFunctions, captured, insideLambda);
							}
						}
						default -> {
							// Lisp-2: the operator symbol is not a variable reference
							List<LispVal> parts = cons.toList();
							for (int i = 1; i < parts.size(); i++) {
								collectCapturedVars(parts.get(i), localVars, knownFunctions, captured, insideLambda);
							}
						}
					}
				}
				else {
					List<LispVal> parts = cons.toList();
					for (LispVal part : parts) {
						collectCapturedVars(part, localVars, knownFunctions, captured, insideLambda);
					}
				}
			}
			default -> {
				// Literals
			}
		}
	}

	private static Set<String> extractParamNames(LispVal paramList) {
		Set<String> names = new HashSet<>();
		if (paramList instanceof LispCons paramCons) {
			for (LispVal p : paramCons.toList()) {
				names.add(((LispSymbol) p).name());
			}
		}
		return names;
	}

}
