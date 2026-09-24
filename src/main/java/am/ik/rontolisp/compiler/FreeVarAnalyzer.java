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
import org.jspecify.annotations.Nullable;

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

	/** The operators that can build a closure -- see {@link #createsAClosure}. */
	private static final Set<String> CLOSURE_OPERATORS = Set.of(LispNames.LAMBDA, LispNames.DEFUN,
			LispNames.ASYNC_LAMBDA, LispNames.ASYNC_LAMBDA_QUALIFIED, LispNames.ASYNC_DEFUN,
			LispNames.ASYNC_DEFUN_QUALIFIED);

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
		return findCapturedVars(body, localVars, knownFunctions, new CaptureMemo());
	}

	/**
	 * {@link #findCapturedVars(List, Set, Set)}, answering from and recording into
	 * {@code memo}. A compiler asks once per scope, and the walk an enclosing scope made
	 * already covered a nested one's body: the let compilers asked at every level of a
	 * nest, which made the walk quadratic in its depth (8% of a WASM compile's CPU).
	 * @param body the expressions to scan
	 * @param localVars the local variable names in the current scope
	 * @param knownFunctions names of defined functions
	 * @param memo the answers so far; one per compilation, since it holds forms by
	 * identity and they must not change under it
	 * @return set of variable names that need boxing
	 */
	public static Set<String> findCapturedVars(List<LispVal> body, Set<String> localVars, Set<String> knownFunctions,
			CaptureMemo memo) {
		Set<String> captured = new HashSet<>();
		for (LispVal expr : body) {
			for (String name : reach(expr, false, memo)) {
				if (localVars.contains(name)) {
					captured.add(name);
				}
			}
		}
		return captured;
	}

	/**
	 * What {@link #findCapturedVars(List, Set, Set, CaptureMemo)} has walked, per form
	 * (by identity) and per whether it was reached inside a lambda. Not thread-safe: one
	 * per compilation.
	 */
	public static final class CaptureMemo {

		final java.util.IdentityHashMap<LispCons, List<String>> outside = new java.util.IdentityHashMap<>();

		final java.util.IdentityHashMap<LispCons, List<String>> inside = new java.util.IdentityHashMap<>();

	}

	private static void collectFreeVars(LispVal expr, Set<String> boundVars, Set<String> knownFunctions,
			Set<String> globals, Set<String> specialNames, LinkedHashSet<String> freeVars) {
		try {
			collectFreeVarsLocated(expr, boundVars, knownFunctions, globals, specialNames, freeVars);
		}
		catch (RuntimeException ex) {
			// The twin of collectCapturedVars' hook: this walk EXPANDS the macros whose
			// raw shape it would misread (check-type/assert/do/loop/...), so a malformed
			// one signals here -- for a TOP-LEVEL such form, before any hooked pass has
			// seen it, which used to leave the message with no position at all.
			throw SourceProvenance.noteFailure(expr, ex);
		}
	}

	private static void collectFreeVarsLocated(LispVal expr, Set<String> boundVars, Set<String> knownFunctions,
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
						// do-symbols / do-external-symbols bind their iteration
						// variable (the expansion is a while loop over the
						// %do-symbols-list universe); without this the variable
						// reads as a free reference, which breaks any closure
						// around the walk.
						case LispNames.DO_SYMBOLS -> collectFreeVars(LispMacroExpander.expandDoSymbols(cons, false),
								boundVars, knownFunctions, globals, specialNames, freeVars);
						case LispNames.DO_EXTERNAL_SYMBOLS ->
							collectFreeVars(LispMacroExpander.expandDoSymbols(cons, true), boundVars, knownFunctions,
									globals, specialNames, freeVars);
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
						case LispNames.TYPECASE, LispNames.ETYPECASE, LispNames.CTYPECASE, LispNames.CASE,
								LispNames.ECASE, LispNames.CCASE -> {
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
						// with-mutex / with-lock-held is the OPPOSITE shape of the with-*
						// stream macros: its one-element spec holds a VALUE, not a
						// binding, so the default walk would read (lock) as a call and
						// never see the variable -- postmodern's statement-id counter
						// closes over exactly such a lock, and the missing capture made
						// the enclosing defun fail to compile.
						case LispNames.WITH_MUTEX_QUALIFIED, LispNames.WITH_LOCK_HELD_QUALIFIED,
								LispNames.WITH_RECURSIVE_LOCK_HELD_QUALIFIED ->
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
							// setq takes place/value PAIRS. Walking only the first one
							// hides every closure a later value form builds, so its
							// captures are never emitted -- a silently wrong answer on
							// the JVM and a "Cannot find variable for closure" on WASM
							// (cl-json's set-custom-vars expands to exactly this shape).
							List<LispVal> parts = cons.toList();
							for (int i = 1; i + 1 < parts.size(); i += 2) {
								if (parts.get(i) instanceof LispSymbol place) {
									String name = place.name();
									if (!specialNames.contains(name) && !boundVars.contains(name)
											&& !knownFunctions.contains(name) && !globals.contains(name)) {
										freeVars.add(name);
									}
								}
								collectFreeVars(parts.get(i + 1), boundVars, knownFunctions, globals, specialNames,
										freeVars);
							}
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
							// A uiop macro with a real expansion is expanded before
							// walking, through the ONE dispatcher the interpreter and
							// both compilers also use: several of them BIND (if-let /
							// when-let's binding list, while-collecting's collectors,
							// with-temporary-file's :stream / :pathname plist entries --
							// smart-buffer's check-limit closes over exactly those) and
							// nest REARRANGES its forms, all of which the default walk
							// below would read as ordinary call forms.
							LispVal uiopMacro = LispMacroExpander.expandUiopMacro(cons, true);
							if (uiopMacro != null) {
								collectFreeVars(uiopMacro, boundVars, knownFunctions, globals, specialNames, freeVars);
								break;
							}
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

	/**
	 * What the capture walk finds under one form, independent of which locals are being
	 * asked about: the names a symbol reference or a {@code setq} place spells at a point
	 * inside a lambda (a nested defun body counts) that no lambda between the form and
	 * that point binds as a parameter -- with {@code insideLambda}, the form's own top
	 * level counts as inside one. In order of first occurrence. The captured subset of a
	 * set of locals is that set's intersection with this, which is what lets
	 * {@link CaptureMemo} answer a nested scope from the walk an enclosing one made.
	 */
	private static List<String> reach(LispVal expr, boolean insideLambda, CaptureMemo memo) {
		if (expr instanceof LispSymbol sym) {
			return insideLambda ? List.of(sym.name()) : List.of();
		}
		if (!(expr instanceof LispCons cons)) {
			return List.of();
		}
		java.util.Map<LispCons, List<String>> answers = insideLambda ? memo.inside : memo.outside;
		List<String> known = answers.get(cons);
		if (known != null) {
			return known;
		}
		List<String> found;
		try {
			found = reachCons(cons, insideLambda, memo);
		}
		catch (RuntimeException ex) {
			// This walk casts binding lists and parameter lists to their expected shapes,
			// so a malformed form surfaces here as a ClassCastException long before any
			// backend gets to reject it by name -- worth a position more than most.
			throw SourceProvenance.noteFailure(cons, ex);
		}
		answers.put(cons, found);
		return found;
	}

	private static List<String> without(List<String> names, Set<String> removed) {
		if (names.isEmpty() || removed.isEmpty()) {
			return names;
		}
		List<String> kept = new java.util.ArrayList<>(names.size());
		for (String name : names) {
			if (!removed.contains(name)) {
				kept.add(name);
			}
		}
		return kept;
	}

	/** An ordered, duplicate-free union of name lists, copying only when two meet. */
	private static final class Names {

		private List<String> single = List.of();

		private @Nullable LinkedHashSet<String> merged;

		void add(String name) {
			add(List.of(name));
		}

		void add(List<String> names) {
			if (names.isEmpty()) {
				return;
			}
			if (this.merged != null) {
				this.merged.addAll(names);
			}
			else if (this.single.isEmpty()) {
				this.single = names;
			}
			else {
				this.merged = new LinkedHashSet<>(this.single);
				this.merged.addAll(names);
			}
		}

		List<String> result() {
			return this.merged != null ? List.copyOf(this.merged) : this.single;
		}

	}

	private static List<String> reachCons(LispCons cons, boolean insideLambda, CaptureMemo memo) {
		Names acc = new Names();
		LispVal head = cons.car();
		if (head instanceof LispSymbol sym) {
			switch (sym.name()) {
				case LispNames.QUOTE -> {
					// skip
				}
				case LispNames.LAMBDA -> {
					// Any reference to an outer local inside a lambda body means
					// capture
					List<LispVal> parts = cons.toList();
					// Everything the body names is inside a lambda -- except the
					// lambda's own params, which are not the outer locals.
					Set<String> lambdaParams = extractParamNames(parts.get(1));
					Names inner = new Names();
					for (int i = 2; i < parts.size(); i++) {
						inner.add(reach(parts.get(i), true, memo));
					}
					acc.add(without(inner.result(), lambdaParams));
				}
				case LispNames.LET -> {
					List<LispVal> parts = cons.toList();
					LispVal bindings = LispMacroExpander.normalizeBindingList(parts.get(1));
					if (bindings instanceof LispCons bindingsCons) {
						for (LispVal binding : bindingsCons.toList()) {
							LispCons pair = (LispCons) binding;
							List<LispVal> pairList = pair.toList();
							acc.add(reach(pairList.get(1), insideLambda, memo));
						}
					}
					for (int i = 2; i < parts.size(); i++) {
						acc.add(reach(parts.get(i), insideLambda, memo));
					}
				}
				case LispNames.DEFUN -> {
					// A defun that is NOT at top level is not a definition: both
					// backends lower it to (setq name (lambda ...)) and call it
					// through the variable
					// (LispMacroExpander.expandCallThroughVariable), so it closes
					// over the enclosing bindings exactly as a lambda does and
					// they need the same cell. Skipping it left the binding
					// unboxed and handed every nested definition a private
					// snapshot copy -- the CL closure-over-let idiom (cl-ppcre's
					// scanner caches) then answered the INITIAL value for good.
					// A top-level defun's body never reaches here: it is walked
					// with its own parameters as the local set, so a name in
					// this set can only be an enclosing binding.
					List<LispVal> parts = cons.toList();
					if (parts.size() >= 3) {
						Set<String> defunParams = extractParamNames(parts.get(2));
						Names inner = new Names();
						for (int i = 3; i < parts.size(); i++) {
							inner.add(reach(parts.get(i), true, memo));
						}
						acc.add(without(inner.result(), defunParams));
					}
				}
				case LispNames.LET_STAR -> acc.add(reach(LispMacroExpander.expandLetStar(cons), insideLambda, memo));
				// The SUBSTITUTION family, expanded before walking for the same
				// reason as in collectFreeVars -- and here it is a lost CAPTURE,
				// not a spurious free variable: a lambda body that spells only
				// the macro name captures whatever the expansion references
				// ((symbol-macrolet ((big (* n n))) (lambda () big)) captures n).
				// Missing it leaves the outer binding unboxed -- or, on wasm, an
				// unboxed i64 local -- with no cell for the closure to load.
				case LispNames.SYMBOL_MACROLET ->
					acc.add(reach(LispMacroExpander.expandSymbolMacrolet(cons), insideLambda, memo));
				case LispNames.WITH_SLOTS ->
					acc.add(reach(LispMacroExpander.expandWithSlots(cons), insideLambda, memo));
				case LispNames.WITH_ACCESSORS ->
					acc.add(reach(LispMacroExpander.expandWithAccessors(cons), insideLambda, memo));
				// Expand before walking (same reason as collectFreeVars).
				case LispNames.COND -> acc.add(reach(LispMacroExpander.expandCond(cons), insideLambda, memo));
				case LispNames.DOTIMES -> acc.add(reach(LispMacroExpander.expandDotimes(cons), insideLambda, memo));
				case LispNames.DO_STAR -> acc.add(reach(LispMacroExpander.expandDoStar(cons), insideLambda, memo));
				case LispNames.DOLIST -> acc.add(reach(LispMacroExpander.expandDolist(cons), insideLambda, memo));
				case LispNames.DO -> acc.add(reach(LispMacroExpander.expandDo(cons), insideLambda, memo));
				// do-symbols / do-external-symbols bind their iteration
				// variable (same reason as in collectFreeVars).
				case LispNames.DO_SYMBOLS ->
					acc.add(reach(LispMacroExpander.expandDoSymbols(cons, false), insideLambda, memo));
				case LispNames.DO_EXTERNAL_SYMBOLS ->
					acc.add(reach(LispMacroExpander.expandDoSymbols(cons, true), insideLambda, memo));
				case LispNames.LOOP -> acc.add(reach(LispMacroExpander.expandLoop(cons), insideLambda, memo));
				// The with-* stream macros bind their stream variable (same
				// reason
				// as in collectFreeVars).
				case LispNames.WITH_OUTPUT_TO_STRING ->
					acc.add(reach(LispMacroExpander.expandWithOutputToString(cons), insideLambda, memo));
				case LispNames.WITH_INPUT_FROM_STRING ->
					acc.add(reach(LispMacroExpander.expandWithInputFromString(cons), insideLambda, memo));
				case LispNames.WITH_OPEN_FILE ->
					acc.add(reach(LispMacroExpander.expandWithOpenFile(cons), insideLambda, memo));
				case LispNames.WITH_OPEN_STREAM ->
					acc.add(reach(LispMacroExpander.expandWithOpenStream(cons, true), insideLambda, memo));
				// The lock spec holds a VALUE, not a binding (same reason as in
				// collectFreeVars).
				case LispNames.WITH_MUTEX_QUALIFIED, LispNames.WITH_LOCK_HELD_QUALIFIED,
						LispNames.WITH_RECURSIVE_LOCK_HELD_QUALIFIED ->
					acc.add(reach(LispMacroExpander.expandWithMutex(cons), insideLambda, memo));
				// Expand before walking (same reason as collectFreeVars).
				case LispNames.CHECK_TYPE ->
					acc.add(reach(LispMacroExpander.expandCheckType(cons), insideLambda, memo));
				case LispNames.ASSERT -> acc.add(reach(LispMacroExpander.expandAssert(cons), insideLambda, memo));
				// typecase/case clause HEADS are data: walk the keyform and the
				// clause bodies only (the collectFreeVars twin).
				case LispNames.TYPECASE, LispNames.ETYPECASE, LispNames.CTYPECASE, LispNames.CASE, LispNames.ECASE,
						LispNames.CCASE -> {
					List<LispVal> parts = cons.toList();
					if (parts.size() > 1) {
						acc.add(reach(parts.get(1), insideLambda, memo));
					}
					for (int i = 2; i < parts.size(); i++) {
						if (!(parts.get(i) instanceof LispCons clause)) {
							continue;
						}
						List<LispVal> clauseParts = clause.toList();
						for (int j = 1; j < clauseParts.size(); j++) {
							acc.add(reach(clauseParts.get(j), insideLambda, memo));
						}
					}
				}
				case LispNames.DECLARE, LispNames.DECLAIM, LispNames.PROCLAIM -> {
					// Parsed no-ops: no variable references.
				}
				case LispNames.THE -> acc.add(reach(LispMacroExpander.expandThe(cons), insideLambda, memo));
				case LispNames.EVAL_WHEN -> acc.add(reach(LispMacroExpander.expandEvalWhen(cons), insideLambda, memo));
				// Expand before walking (same reason as collectFreeVars).
				case LispNames.FLET -> acc.add(reach(LispMacroExpander.expandFlet(cons), insideLambda, memo));
				case LispNames.LABELS -> acc.add(reach(LispMacroExpander.expandLabels(cons), insideLambda, memo));
				// Expand before walking (same reason as collectFreeVars).
				case LispNames.MULTIPLE_VALUE_BIND ->
					acc.add(reach(LispMacroExpander.expandMultipleValueBind(cons), insideLambda, memo));
				case LispNames.MULTIPLE_VALUE_LIST ->
					acc.add(reach(LispMacroExpander.expandMultipleValueList(cons), insideLambda, memo));
				case LispNames.MULTIPLE_VALUE_CALL ->
					acc.add(reach(LispMacroExpander.expandMultipleValueCall(cons), insideLambda, memo));
				case LispNames.NTH_VALUE -> acc.add(reach(LispMacroExpander.expandNthValue(cons), insideLambda, memo));
				// Expand before walking (same reason as collectFreeVars).
				case LispNames.MULTIPLE_VALUE_SETQ ->
					acc.add(reach(LispMacroExpander.expandMultipleValueSetq(cons), insideLambda, memo));
				case LispNames.ROTATEF -> acc.add(reach(LispMacroExpander.expandRotatef(cons), insideLambda, memo));
				// Expand before walking (same reason as collectFreeVars).
				case LispNames.DESTRUCTURING_BIND ->
					acc.add(reach(LispMacroExpander.expandDestructuringBind(cons), insideLambda, memo));
				// Expand before walking (same reason as collectFreeVars).
				case LispNames.PPRINT_LOGICAL_BLOCK ->
					acc.add(reach(LispMacroExpander.expandPprintLogicalBlock(cons), insideLambda, memo));
				// Expand before walking: the restart expansions introduce lambdas
				// (restart invokers, handler type tests) whose captures of USER
				// locals the surface form cannot show.
				case LispNames.HANDLER_BIND ->
					acc.add(reach(LispMacroExpander.expandHandlerBindForAnalysis(cons), insideLambda, memo));
				case LispNames.RESTART_CASE ->
					acc.add(reach(LispMacroExpander.expandRestartCase(cons), insideLambda, memo));
				case LispNames.RESTART_BIND ->
					acc.add(reach(LispMacroExpander.expandRestartBind(cons), insideLambda, memo));
				case LispNames.WITH_SIMPLE_RESTART ->
					acc.add(reach(LispMacroExpander.expandWithSimpleRestart(cons), insideLambda, memo));
				case LispNames.FUNCTION -> {
					List<LispVal> parts = cons.toList();
					if (parts.size() == 2 && parts.get(1) instanceof LispCons) {
						acc.add(reach(parts.get(1), insideLambda, memo));
					}
				}
				case LispNames.SETQ -> {
					// Every place/value pair, for the reason spelled out in the
					// free-variable walk above.
					List<LispVal> parts = cons.toList();
					for (int i = 1; i + 1 < parts.size(); i += 2) {
						if (parts.get(i) instanceof LispSymbol place && insideLambda) {
							acc.add(place.name());
						}
						acc.add(reach(parts.get(i + 1), insideLambda, memo));
					}
				}
				case LispNames.DEFVAR -> {
					// defvar names a global variable; only the optional init form
					// can reference captured locals.
					List<LispVal> parts = cons.toList();
					if (parts.size() > 2) {
						acc.add(reach(parts.get(2), insideLambda, memo));
					}
				}
				case LispNames.BLOCK, LispNames.FN_BLOCK_INTERNAL, LispNames.RETURN_FROM -> {
					// The first argument is a block NAME, not a variable
					// reference.
					List<LispVal> parts = cons.toList();
					for (int i = 2; i < parts.size(); i++) {
						acc.add(reach(parts.get(i), insideLambda, memo));
					}
				}
				case LispNames.TAGBODY -> {
					// Body atoms are labels, not variable references.
					List<LispVal> parts = cons.toList();
					for (int i = 1; i < parts.size(); i++) {
						if (parts.get(i) instanceof LispCons) {
							acc.add(reach(parts.get(i), insideLambda, memo));
						}
					}
				}
				case LispNames.GO -> {
					// (go tag): the tag is a label, not a variable reference.
				}
				default -> {
					// A uiop macro with a real expansion binds / rearranges
					// forms the default walk would misread (same reason, and the
					// same dispatcher, as in collectFreeVars).
					LispVal uiopMacro = LispMacroExpander.expandUiopMacro(cons, true);
					if (uiopMacro != null) {
						acc.add(reach(uiopMacro, insideLambda, memo));
						break;
					}
					// Lisp-2: the operator symbol is not a variable reference
					List<LispVal> parts = cons.toList();
					for (int i = 1; i < parts.size(); i++) {
						acc.add(reach(parts.get(i), insideLambda, memo));
					}
				}
			}
		}
		else {
			List<LispVal> parts = cons.toList();
			for (LispVal part : parts) {
				acc.add(reach(part, insideLambda, memo));
			}
		}
		return acc.result();
	}

	/**
	 * The variables a lambda list binds. An entry is a bare symbol or -- for an
	 * {@code &optional} / {@code &key} parameter that carries a default -- the
	 * {@code (var init [supplied-p])} shape, whose supplied-p variable is bound too and
	 * whose {@code &key} {@code var} may itself be a {@code (:keyword var)} pair. Reading
	 * only the bare shape made a nested lambda with a defaulted optional
	 * ({@code (defun f (n) (lambda (&optional (x 1 p)) ...))}) a ClassCastException at
	 * compile time.
	 *
	 * <p>
	 * The init FORMS are values, not bindings, so they are not collected here; a name
	 * only an init mentions still reaches both walks, because the lambda-list lowering
	 * moves the default into the body before either runs.
	 */
	private static Set<String> extractParamNames(LispVal paramList) {
		Set<String> names = new HashSet<>();
		if (paramList instanceof LispCons paramCons) {
			for (LispVal p : paramCons.toList()) {
				collectParamNames(p, names);
			}
		}
		return names;
	}

	private static void collectParamNames(LispVal param, Set<String> names) {
		if (param instanceof LispSymbol symbol) {
			names.add(symbol.name());
			return;
		}
		if (!(param instanceof LispCons spec)) {
			return;
		}
		List<LispVal> parts = spec.toList();
		// (var init supplied-p) -- the init form is a value, not a binding -- where var
		// is itself ((:keyword var)) for a &key parameter that renames its keyword.
		if (parts.get(0) instanceof LispCons named && named.toList().size() == 2
				&& named.toList().get(1) instanceof LispSymbol keyVar) {
			names.add(keyVar.name());
		}
		else if (parts.get(0) instanceof LispSymbol var) {
			names.add(var.name());
		}
		if (parts.size() > 2 && parts.get(2) instanceof LispSymbol suppliedP) {
			names.add(suppliedP.name());
		}
	}

	/**
	 * Whether any form in a body can build a closure. The WASM backend asks before giving
	 * a TOP-LEVEL {@code let}'s binding an unboxed (i64) local: the capture analysis
	 * below has two blind spots that only top level reaches, and a boxed local tolerates
	 * both while a raw slot does not (the closure emitter needs a cell, and throws
	 * "Cannot find variable for closure" without one).
	 * <ul>
	 * <li>a {@code defun} nested in a top-level {@code let} -- the CL closure-over-let
	 * idiom -- which {@link #findCapturedVars} DOES see (it lowers to a lambda), but
	 * which also reaches the binding through the global backing store
	 * {@code GlobalVarCollector} mints for it;</li>
	 * <li>an {@code async-lambda}/{@code async-defun}, whose head that walk does not read
	 * as a lambda (the async emitter runs its own free-variable pass instead).</li>
	 * </ul>
	 * Deliberately blind to scope and to quoting: over-approximating only declines an
	 * unboxing that was available, while a miss is a compile-time throw.
	 * @param body the forms to scan
	 * @return {@code true} when any of them mentions a closure-building operator
	 */
	public static boolean createsAClosure(List<LispVal> body) {
		for (LispVal expr : body) {
			if (mentionsClosureOperator(expr)) {
				return true;
			}
		}
		return false;
	}

	private static boolean mentionsClosureOperator(LispVal expr) {
		return switch (expr) {
			case LispSymbol sym -> CLOSURE_OPERATORS.contains(sym.name());
			case LispCons cons -> mentionsClosureOperator(cons.car()) || mentionsClosureOperator(cons.cdr());
			default -> false;
		};
	}

}
