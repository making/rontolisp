package am.ik.rontolisp.compiler;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.macro.LispMacroExpander;
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
			LispNames.SYMBOLP, LispNames.STRINGP, LispNames.LISTP, LispNames.CONSP, LispNames.KEYWORDP,
			// The three standard stream variables are GLOBAL, never a lexical the
			// enclosing scope could hand down: compileSymbolRef renders each as the
			// designator t (or, once the program binds one of them somewhere, as the
			// special
			// SpecialVarCollector then registers). Without them here, a lambda that
			// merely writes to one -- postmodern's generate-prepared reports its
			// reconnect with (format *error-output* ...) inside a handler-bind handler --
			// counted it as a free variable and failed to compile with "Cannot capture
			// variable: *ERROR-OUTPUT*". A program that DOES bind one lexically still
			// captures it: findFreeVars subtracts enclosingLexicals from this set.
			LispNames.STANDARD_OUTPUT_VAR, LispNames.ERROR_OUTPUT_VAR, LispNames.STANDARD_INPUT_VAR);

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
		return findFreeVars(body, boundVars, knownFunctions, globals, Set.of());
	}

	/**
	 * Like {@link #findFreeVars(List, Set, Set, Set)}, but names in
	 * {@code enclosingLexicals} -- variables lexically visible at the analyzed lambda's
	 * creation site -- override the built-in/function/global exclusions: Lisp-2 means a
	 * bare symbol is always a variable, so a local named {@code list} or {@code count}
	 * shadows the function of the same name and must be captured like any other free
	 * variable.
	 * @param body the expressions to analyze
	 * @param boundVars variables bound in the current scope (params, let bindings)
	 * @param knownFunctions names of defined functions
	 * @param globals names of top-level global variables (excluded from the result)
	 * @param enclosingLexicals variable names lexically visible at the creation site
	 * @return ordered set of free variable names
	 */
	public static LinkedHashSet<String> findFreeVars(List<LispVal> body, Set<String> boundVars,
			Set<String> knownFunctions, Set<String> globals, Set<String> enclosingLexicals) {
		Set<String> functionsMinusLexicals = knownFunctions;
		Set<String> globalsMinusLexicals = globals;
		Set<String> specialsMinusLexicals = SPECIAL_NAMES;
		if (!enclosingLexicals.isEmpty()) {
			functionsMinusLexicals = new HashSet<>(knownFunctions);
			functionsMinusLexicals.removeAll(enclosingLexicals);
			globalsMinusLexicals = new HashSet<>(globals);
			globalsMinusLexicals.removeAll(enclosingLexicals);
			specialsMinusLexicals = new HashSet<>(SPECIAL_NAMES);
			specialsMinusLexicals.removeAll(enclosingLexicals);
		}
		LinkedHashSet<String> freeVars = new LinkedHashSet<>();
		for (LispVal expr : body) {
			collectFreeVars(expr, boundVars, functionsMinusLexicals, globalsMinusLexicals, specialsMinusLexicals,
					freeVars);
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
			Set<String> globals, Set<String> specialNames, LinkedHashSet<String> freeVars) {
		switch (expr) {
			case LispSymbol sym -> {
				String name = sym.name();
				if (!sym.isKeyword() && !specialNames.contains(name) && !boundVars.contains(name)
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
								collectFreeVars(parts.get(i), innerBound, knownFunctions, globals, specialNames,
										freeVars);
							}
						}
						case LispNames.LET -> {
							List<LispVal> parts = cons.toList();
							Set<String> innerBound = new HashSet<>(boundVars);
							LispVal bindings = LispMacroExpander.normalizeBindingList(parts.get(1));
							if (bindings instanceof LispCons bindingsCons) {
								for (LispVal binding : bindingsCons.toList()) {
									LispCons pair = (LispCons) binding;
									List<LispVal> pairList = pair.toList();
									// The init expression is evaluated in outer scope
									collectFreeVars(pairList.get(1), boundVars, knownFunctions, globals, specialNames,
											freeVars);
									innerBound.add(((LispSymbol) pairList.get(0)).name());
								}
							}
							for (int i = 2; i < parts.size(); i++) {
								collectFreeVars(parts.get(i), innerBound, knownFunctions, globals, specialNames,
										freeVars);
							}
						}
						case LispNames.DEFUN -> {
							// defun body is handled separately; skip
						}
						case LispNames.LET_STAR -> collectFreeVars(LispMacroExpander.expandLetStar(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						// Expand before walking: a cond clause whose test is a bare
						// symbol would otherwise be misread as a call form, dropping
						// the variable reference (cl-ppcre's (cond (start-anchored-p
						// ...)) inside the scan closures).
						case LispNames.COND -> collectFreeVars(LispMacroExpander.expandCond(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						// Expand before walking: with-slots binds its slot variables,
						// which
						// the default walk would misread as free references.
						case LispNames.WITH_SLOTS -> collectFreeVars(LispMacroExpander.expandWithSlots(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						// Expand before walking: the substitution replaces the macro
						// names
						// (which the default walk would misread as free references) and
						// the
						// shadow-aware scoping is the expansion's own.
						case LispNames.SYMBOL_MACROLET -> collectFreeVars(LispMacroExpander.expandSymbolMacrolet(cons),
								boundVars, knownFunctions, globals, specialNames, freeVars);
						case LispNames.WITH_ACCESSORS -> collectFreeVars(LispMacroExpander.expandWithAccessors(cons),
								boundVars, knownFunctions, globals, specialNames, freeVars);
						case LispNames.DOLIST -> collectFreeVars(LispMacroExpander.expandDolist(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						case LispNames.DOTIMES -> collectFreeVars(LispMacroExpander.expandDotimes(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						case LispNames.DO -> collectFreeVars(LispMacroExpander.expandDo(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						case LispNames.DO_STAR -> collectFreeVars(LispMacroExpander.expandDoStar(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						case LispNames.LOOP -> collectFreeVars(LispMacroExpander.expandLoop(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						// Expand before walking: the default walk would misread the raw
						// shapes (e.g. the type symbol in (the integer x), declaration
						// specifiers in declare) as variable references.
						case LispNames.CHECK_TYPE -> collectFreeVars(LispMacroExpander.expandCheckType(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						case LispNames.ASSERT -> collectFreeVars(LispMacroExpander.expandAssert(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						// typecase clause HEADS are type specifiers and case/ecase/ccase
						// clause HEADS are unevaluated key lists -- data, not variable
						// references -- so walk the keyform and the clause bodies only.
						// Walked structurally rather than expanded: the typecase
						// expansion needs the class registry for a class-name head
						// (lack-middleware-backtrace's (or pathname string) head is what
						// surfaced this), and a case key list like mito's
						// (lambda flet labels) read as an expression makes its keys
						// free variables.
						case LispNames.TYPECASE, LispNames.ETYPECASE, LispNames.CASE, LispNames.ECASE,
								LispNames.CCASE -> {
							List<LispVal> parts = cons.toList();
							if (parts.size() > 1) {
								collectFreeVars(parts.get(1), boundVars, knownFunctions, globals, specialNames,
										freeVars);
							}
							for (int i = 2; i < parts.size(); i++) {
								if (!(parts.get(i) instanceof LispCons clause)) {
									continue;
								}
								List<LispVal> clauseParts = clause.toList();
								for (int j = 1; j < clauseParts.size(); j++) {
									collectFreeVars(clauseParts.get(j), boundVars, knownFunctions, globals,
											specialNames, freeVars);
								}
							}
						}
						case LispNames.DECLARE, LispNames.DECLAIM, LispNames.PROCLAIM -> {
							// Parsed no-ops: no variable references.
						}
						case LispNames.THE -> collectFreeVars(LispMacroExpander.expandThe(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						case LispNames.EVAL_WHEN -> collectFreeVars(LispMacroExpander.expandEvalWhen(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						// Expand before walking: the default walk would misread the
						// definition lists as call forms. The expansion generates fresh
						// variable names, but they are all bound inside it, so the free
						// set is the same as the compile-time expansion's.
						case LispNames.FLET -> collectFreeVars(LispMacroExpander.expandFlet(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						case LispNames.LABELS -> collectFreeVars(LispMacroExpander.expandLabels(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						// Expand before walking: the default walk would misread the
						// multiple-value-bind variable list as a call form. The temp
						// names
						// are counter-fresh but all bound inside the expansion.
						case LispNames.MULTIPLE_VALUE_BIND ->
							collectFreeVars(LispMacroExpander.expandMultipleValueBind(cons), boundVars, knownFunctions,
									globals, specialNames, freeVars);
						case LispNames.MULTIPLE_VALUE_LIST ->
							collectFreeVars(LispMacroExpander.expandMultipleValueList(cons), boundVars, knownFunctions,
									globals, specialNames, freeVars);
						case LispNames.MULTIPLE_VALUE_CALL ->
							collectFreeVars(LispMacroExpander.expandMultipleValueCall(cons), boundVars, knownFunctions,
									globals, specialNames, freeVars);
						case LispNames.NTH_VALUE -> collectFreeVars(LispMacroExpander.expandNthValue(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						// Expand before walking: the default walk would misread the
						// multiple-value-setq variable list as a call form.
						case LispNames.MULTIPLE_VALUE_SETQ ->
							collectFreeVars(LispMacroExpander.expandMultipleValueSetq(cons), boundVars, knownFunctions,
									globals, specialNames, freeVars);
						case LispNames.ROTATEF -> collectFreeVars(LispMacroExpander.expandRotatef(cons), boundVars,
								knownFunctions, globals, specialNames, freeVars);
						// Expand before walking: the default walk would misread the
						// destructuring pattern as a call form.
						case LispNames.DESTRUCTURING_BIND ->
							collectFreeVars(LispMacroExpander.expandDestructuringBind(cons), boundVars, knownFunctions,
									globals, specialNames, freeVars);
						// Expand before walking: the default walk would misread the
						// (stream object ...) spec as a call form, so the STREAM the body
						// writes to would never count as a free variable.
						case LispNames.PPRINT_LOGICAL_BLOCK ->
							collectFreeVars(LispMacroExpander.expandPprintLogicalBlock(cons), boundVars, knownFunctions,
									globals, specialNames, freeVars);
						// handler-case binds each clause's condition variable; the
						// default
						// walk would misread it as a free reference (and the clause type
						// as
						// a call form).
						case LispNames.HANDLER_CASE -> {
							List<LispVal> parts = cons.toList();
							if (parts.size() > 1) {
								collectFreeVars(parts.get(1), boundVars, knownFunctions, globals, specialNames,
										freeVars);
							}
							for (int i = 2; i < parts.size(); i++) {
								if (!(parts.get(i) instanceof LispCons clause)) {
									continue;
								}
								List<LispVal> clauseParts = clause.toList();
								Set<String> innerBound = new HashSet<>(boundVars);
								if (clauseParts.size() > 1 && clauseParts.get(1) instanceof LispCons varList
										&& varList.car() instanceof LispSymbol var) {
									innerBound.add(var.name());
								}
								for (int j = 2; j < clauseParts.size(); j++) {
									collectFreeVars(clauseParts.get(j), innerBound, knownFunctions, globals,
											specialNames, freeVars);
								}
							}
						}
						case LispNames.IGNORE_ERRORS -> collectFreeVars(LispMacroExpander.expandIgnoreErrors(cons),
								boundVars, knownFunctions, globals, specialNames, freeVars);
						// Expand before walking: the default walk would misread the
						// handler-bind clause types / restart-case clause parameter lists
						// as call forms or free references. The expansions bind their
						// temps and clause parameters inside, so the free set matches the
						// compile-time expansion's (the registry-less handler-bind
						// expansion differs only in the type-test shape, which references
						// no variables beyond the bound condition temp).
						case LispNames.HANDLER_BIND ->
							collectFreeVars(LispMacroExpander.expandHandlerBindForAnalysis(cons), boundVars,
									knownFunctions, globals, specialNames, freeVars);
						case LispNames.RESTART_CASE -> collectFreeVars(LispMacroExpander.expandRestartCase(cons),
								boundVars, knownFunctions, globals, specialNames, freeVars);
						case LispNames.RESTART_BIND -> collectFreeVars(LispMacroExpander.expandRestartBind(cons),
								boundVars, knownFunctions, globals, specialNames, freeVars);
						case LispNames.WITH_SIMPLE_RESTART ->
							collectFreeVars(LispMacroExpander.expandWithSimpleRestart(cons), boundVars, knownFunctions,
									globals, specialNames, freeVars);
						// The with-* stream macros BIND their stream variable; the
						// default
						// walk would read it as a free reference and try to capture a
						// variable that only the expansion introduces (a labels-local
						// function whose body opens a string stream, cl-postgres'
						// read-array-value).
						case LispNames.WITH_OUTPUT_TO_STRING ->
							collectFreeVars(LispMacroExpander.expandWithOutputToString(cons), boundVars, knownFunctions,
									globals, specialNames, freeVars);
						case LispNames.WITH_INPUT_FROM_STRING ->
							collectFreeVars(LispMacroExpander.expandWithInputFromString(cons), boundVars,
									knownFunctions, globals, specialNames, freeVars);
						case LispNames.WITH_OPEN_FILE -> collectFreeVars(LispMacroExpander.expandWithOpenFile(cons),
								boundVars, knownFunctions, globals, specialNames, freeVars);
						case LispNames.WITH_OPEN_STREAM ->
							collectFreeVars(LispMacroExpander.expandWithOpenStream(cons, true), boundVars,
									knownFunctions, globals, specialNames, freeVars);
						// uiop:with-temporary-file BINDS its :stream / :pathname
						// variables, and they sit in a keyword plist the default walk
						// would read as ordinary arguments -- smart-buffer's check-limit
						// closes over exactly those.
						case LispNames.UIOP_WITH_TEMPORARY_FILE_QUALIFIED ->
							collectFreeVars(LispMacroExpander.expandUiopWithTemporaryFile(cons, true), boundVars,
									knownFunctions, globals, specialNames, freeVars);
						// with-mutex / with-lock-held is the OPPOSITE shape of the with-*
						// stream macros: its one-element spec holds a VALUE, not a
						// binding, so the default walk would read (lock) as a call and
						// never see the variable -- postmodern's statement-id counter
						// closes over exactly such a lock, and the missing capture made
						// the enclosing defun fail to compile.
						case LispNames.WITH_MUTEX_QUALIFIED, LispNames.WITH_LOCK_HELD_QUALIFIED ->
							collectFreeVars(LispMacroExpander.expandWithMutex(cons), boundVars, knownFunctions, globals,
									specialNames, freeVars);
						case LispNames.FUNCTION -> {
							// (function name) names the function namespace, not a
							// variable; (function (lambda ...)) is analyzed like lambda
							List<LispVal> parts = cons.toList();
							if (parts.size() == 2 && parts.get(1) instanceof LispCons) {
								collectFreeVars(parts.get(1), boundVars, knownFunctions, globals, specialNames,
										freeVars);
							}
						}
						case LispNames.SETQ -> {
							List<LispVal> parts = cons.toList();
							String name = ((LispSymbol) parts.get(1)).name();
							if (!specialNames.contains(name) && !boundVars.contains(name)
									&& !knownFunctions.contains(name) && !globals.contains(name)) {
								freeVars.add(name);
							}
							collectFreeVars(parts.get(2), boundVars, knownFunctions, globals, specialNames, freeVars);
						}
						case LispNames.DEFVAR -> {
							// defvar names a global variable, not a lexical reference;
							// only
							// the optional init form can reference variables.
							List<LispVal> parts = cons.toList();
							if (parts.size() > 2) {
								collectFreeVars(parts.get(2), boundVars, knownFunctions, globals, specialNames,
										freeVars);
							}
						}
						case LispNames.BLOCK, LispNames.FN_BLOCK_INTERNAL, LispNames.RETURN_FROM -> {
							// The first argument is a block NAME, not a variable
							// reference; only the body/value forms can reference
							// variables.
							List<LispVal> parts = cons.toList();
							for (int i = 2; i < parts.size(); i++) {
								collectFreeVars(parts.get(i), boundVars, knownFunctions, globals, specialNames,
										freeVars);
							}
						}
						case LispNames.TAGBODY -> {
							// Body atoms are labels, not variable references; only the
							// cons statements can reference variables.
							List<LispVal> parts = cons.toList();
							for (int i = 1; i < parts.size(); i++) {
								if (parts.get(i) instanceof LispCons) {
									collectFreeVars(parts.get(i), boundVars, knownFunctions, globals, specialNames,
											freeVars);
								}
							}
						}
						case LispNames.GO -> {
							// (go tag): the tag is a label, not a variable reference.
						}
						default -> {
							// Function call or special form: the operator resolves in
							// the function namespace (Lisp-2), so only the argument
							// subexpressions can reference variables
							List<LispVal> parts = cons.toList();
							for (int i = 1; i < parts.size(); i++) {
								collectFreeVars(parts.get(i), boundVars, knownFunctions, globals, specialNames,
										freeVars);
							}
						}
					}
				}
				else {
					// Non-symbol head (e.g., ((lambda ...) args))
					List<LispVal> parts = cons.toList();
					for (LispVal part : parts) {
						collectFreeVars(part, boundVars, knownFunctions, globals, specialNames, freeVars);
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
		try {
			collectCapturedVarsLocated(expr, localVars, knownFunctions, captured, insideLambda);
		}
		catch (RuntimeException ex) {
			// This walk casts binding lists and parameter lists to their expected shapes,
			// so a malformed form surfaces here as a ClassCastException long before any
			// backend gets to reject it by name -- worth a position more than most
			// (.todo/151 phase 2).
			throw SourceProvenance.noteFailure(expr, ex);
		}
	}

	private static void collectCapturedVarsLocated(LispVal expr, Set<String> localVars, Set<String> knownFunctions,
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
							LispVal bindings = LispMacroExpander.normalizeBindingList(parts.get(1));
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
						// Expand before walking (same reason as collectFreeVars).
						case LispNames.COND -> collectCapturedVars(LispMacroExpander.expandCond(cons), localVars,
								knownFunctions, captured, insideLambda);
						case LispNames.DOTIMES -> collectCapturedVars(LispMacroExpander.expandDotimes(cons), localVars,
								knownFunctions, captured, insideLambda);
						case LispNames.DO_STAR -> collectCapturedVars(LispMacroExpander.expandDoStar(cons), localVars,
								knownFunctions, captured, insideLambda);
						case LispNames.DOLIST -> collectCapturedVars(LispMacroExpander.expandDolist(cons), localVars,
								knownFunctions, captured, insideLambda);
						case LispNames.DO -> collectCapturedVars(LispMacroExpander.expandDo(cons), localVars,
								knownFunctions, captured, insideLambda);
						case LispNames.LOOP -> collectCapturedVars(LispMacroExpander.expandLoop(cons), localVars,
								knownFunctions, captured, insideLambda);
						// The with-* stream macros bind their stream variable (same
						// reason
						// as in collectFreeVars).
						case LispNames.WITH_OUTPUT_TO_STRING ->
							collectCapturedVars(LispMacroExpander.expandWithOutputToString(cons), localVars,
									knownFunctions, captured, insideLambda);
						case LispNames.WITH_INPUT_FROM_STRING ->
							collectCapturedVars(LispMacroExpander.expandWithInputFromString(cons), localVars,
									knownFunctions, captured, insideLambda);
						case LispNames.WITH_OPEN_FILE -> collectCapturedVars(LispMacroExpander.expandWithOpenFile(cons),
								localVars, knownFunctions, captured, insideLambda);
						case LispNames.WITH_OPEN_STREAM ->
							collectCapturedVars(LispMacroExpander.expandWithOpenStream(cons, true), localVars,
									knownFunctions, captured, insideLambda);
						// The :stream / :pathname plist entries are BINDINGS (same
						// reason as in collectFreeVars).
						case LispNames.UIOP_WITH_TEMPORARY_FILE_QUALIFIED ->
							collectCapturedVars(LispMacroExpander.expandUiopWithTemporaryFile(cons, true), localVars,
									knownFunctions, captured, insideLambda);
						// The lock spec holds a VALUE, not a binding (same reason as in
						// collectFreeVars).
						case LispNames.WITH_MUTEX_QUALIFIED, LispNames.WITH_LOCK_HELD_QUALIFIED ->
							collectCapturedVars(LispMacroExpander.expandWithMutex(cons), localVars, knownFunctions,
									captured, insideLambda);
						// Expand before walking (same reason as collectFreeVars).
						case LispNames.CHECK_TYPE -> collectCapturedVars(LispMacroExpander.expandCheckType(cons),
								localVars, knownFunctions, captured, insideLambda);
						case LispNames.ASSERT -> collectCapturedVars(LispMacroExpander.expandAssert(cons), localVars,
								knownFunctions, captured, insideLambda);
						// typecase/case clause HEADS are data: walk the keyform and the
						// clause bodies only (the collectFreeVars twin).
						case LispNames.TYPECASE, LispNames.ETYPECASE, LispNames.CASE, LispNames.ECASE,
								LispNames.CCASE -> {
							List<LispVal> parts = cons.toList();
							if (parts.size() > 1) {
								collectCapturedVars(parts.get(1), localVars, knownFunctions, captured, insideLambda);
							}
							for (int i = 2; i < parts.size(); i++) {
								if (!(parts.get(i) instanceof LispCons clause)) {
									continue;
								}
								List<LispVal> clauseParts = clause.toList();
								for (int j = 1; j < clauseParts.size(); j++) {
									collectCapturedVars(clauseParts.get(j), localVars, knownFunctions, captured,
											insideLambda);
								}
							}
						}
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
						case LispNames.MULTIPLE_VALUE_SETQ ->
							collectCapturedVars(LispMacroExpander.expandMultipleValueSetq(cons), localVars,
									knownFunctions, captured, insideLambda);
						case LispNames.ROTATEF -> collectCapturedVars(LispMacroExpander.expandRotatef(cons), localVars,
								knownFunctions, captured, insideLambda);
						// Expand before walking (same reason as collectFreeVars).
						case LispNames.DESTRUCTURING_BIND ->
							collectCapturedVars(LispMacroExpander.expandDestructuringBind(cons), localVars,
									knownFunctions, captured, insideLambda);
						// Expand before walking (same reason as collectFreeVars).
						case LispNames.PPRINT_LOGICAL_BLOCK ->
							collectCapturedVars(LispMacroExpander.expandPprintLogicalBlock(cons), localVars,
									knownFunctions, captured, insideLambda);
						// Expand before walking: the restart expansions introduce lambdas
						// (restart invokers, handler type tests) whose captures of USER
						// locals the surface form cannot show.
						case LispNames.HANDLER_BIND ->
							collectCapturedVars(LispMacroExpander.expandHandlerBindForAnalysis(cons), localVars,
									knownFunctions, captured, insideLambda);
						case LispNames.RESTART_CASE -> collectCapturedVars(LispMacroExpander.expandRestartCase(cons),
								localVars, knownFunctions, captured, insideLambda);
						case LispNames.RESTART_BIND -> collectCapturedVars(LispMacroExpander.expandRestartBind(cons),
								localVars, knownFunctions, captured, insideLambda);
						case LispNames.WITH_SIMPLE_RESTART ->
							collectCapturedVars(LispMacroExpander.expandWithSimpleRestart(cons), localVars,
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
						case LispNames.BLOCK, LispNames.FN_BLOCK_INTERNAL, LispNames.RETURN_FROM -> {
							// The first argument is a block NAME, not a variable
							// reference.
							List<LispVal> parts = cons.toList();
							for (int i = 2; i < parts.size(); i++) {
								collectCapturedVars(parts.get(i), localVars, knownFunctions, captured, insideLambda);
							}
						}
						case LispNames.TAGBODY -> {
							// Body atoms are labels, not variable references.
							List<LispVal> parts = cons.toList();
							for (int i = 1; i < parts.size(); i++) {
								if (parts.get(i) instanceof LispCons) {
									collectCapturedVars(parts.get(i), localVars, knownFunctions, captured,
											insideLambda);
								}
							}
						}
						case LispNames.GO -> {
							// (go tag): the tag is a label, not a variable reference.
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
