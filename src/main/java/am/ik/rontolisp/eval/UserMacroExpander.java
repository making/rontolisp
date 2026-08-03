package am.ik.rontolisp.eval;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

/**
 * Compile-path expansion of user macros defined with {@code defmacro}. Runs in the CLI
 * before the JVM/WASM compilers (after {@link am.ik.rontolisp.cli.LoadInliner} splices
 * {@code load}ed files), mirroring how the compilers never see {@code load}: each
 * top-level {@code (defmacro ...)} is evaluated into an internal {@link LispEvaluator}
 * (which runs the macro bodies at compile time) and removed from the program, and every
 * macro call in the remaining forms is fully expanded. The backends therefore compile
 * only ordinary forms and need no macro support of their own. Top-level {@code defun}s
 * are also registered into the macro-time evaluator (registration only, no body runs) so
 * a macro body can call helper functions defined in the same program. The interpreter
 * does not use this pass; it expands user macros natively at evaluation time.
 */
public final class UserMacroExpander {

	private UserMacroExpander() {
	}

	/**
	 * Expands user macros in the program and removes the {@code defmacro} forms. Returns
	 * the program unchanged when it defines no macros.
	 * @param program the top-level forms
	 * @return the program with macro definitions consumed and macro calls expanded
	 */
	public static List<LispVal> expand(List<LispVal> program) {
		// Splice top-level (progn ...)/(eval-when ...) first so a defmacro nested in
		// the (eval-when (:compile-toplevel ...) (defmacro ...)) idiom is seen (and
		// nested defuns become top-level for the compilers' Pass 1).
		program = LispMacroExpander.flattenTopLevel(program);
		// Also activate for macroexpand/macroexpand-1 calls: their literal quoted
		// arguments are folded to the expansion here, even when no macro is defined.
		// Read-eval markers ((%read-eval datum), the shape the marker-mode reader wraps
		// #. in) also activate the pass: they resolve against the macro-time evaluator
		// per top-level form, mirroring the interpreter's loadFile timing.
		if (program.stream().noneMatch(form -> isOperator(form, LispNames.DEFMACRO))
				&& program.stream().noneMatch(form -> isOperator(form, LispNames.DEFINE_COMPILER_MACRO))
				&& program.stream().noneMatch(form -> isOperator(form, LispNames.DEFINE_MODIFY_MACRO))
				&& program.stream().noneMatch(form -> isOperator(form, LispNames.DEFINE_SETF_EXPANDER))
				&& program.stream().noneMatch(form -> isOperator(form, LispNames.DEFSETF))
				&& program.stream().noneMatch(LispMacroExpander::isSetfMacroFunctionForm)
				&& program.stream().noneMatch(UserMacroExpander::usesMacroexpand)
				&& program.stream().noneMatch(UserMacroExpander::usesMacrolet)
				&& program.stream().noneMatch(UserMacroExpander::usesReadEvalMarker)
				&& program.stream().noneMatch(UserMacroExpander::isParameterizedDeftype)
				&& program.stream().noneMatch(UserMacroExpander::isMetaclassDefclass)) {
			return program;
		}
		LispEvaluator macroEval = new LispEvaluator(new PrintStream(OutputStream.nullOutputStream()));
		// A :metaclass defclass evaluated below runs the class-definition protocol in the
		// macro-time evaluator; a build-dao-methods-style (compile nil `(lambda ()
		// ,code))
		// inside a finalize-inheritance hook is intercepted there and its folded method
		// definitions land here (see MopEvalCapture), to be spliced after the defclass as
		// top-level forms the compilers expand statically.
		List<LispVal> mopSplice = new ArrayList<>();
		macroEval.setMopEvalSpliceSink(mopSplice);
		List<LispVal> result = new ArrayList<>();
		// Nesting depth of the (%begin-system "NAME") / (%end-system) provenance
		// brackets LoadInliner puts around every spliced ASDF system. Inside them the
		// pass REPLAYS plain top-level forms into the macro-time evaluator (see
		// replayLibraryTopLevel): in CL, compiling a file requires its DEPENDENCY
		// systems loaded into the compiling image -- fully executed, registries built
		// -- and the macro-time evaluator is that image here. trivia registers its
		// vector matchers with plain top-level set-vector-matcher CALLS whose effect a
		// later match expansion reads; without the replay the pattern lookup misses
		// and the expander degrades to its structure-pattern fallback. User-file forms
		// (depth 0) keep compile-file semantics: only definitions register, nothing
		// else runs.
		int systemDepth = 0;
		for (LispVal rawForm : program) {
			if (isProvenanceMarker(rawForm, LispNames.BEGIN_SYSTEM)) {
				systemDepth++;
			}
			else if (isProvenanceMarker(rawForm, LispNames.END_SYSTEM)) {
				systemDepth = Math.max(0, systemDepth - 1);
			}
			// Resolve #. markers first (before package resolution, like the interpreter
			// resolves them just before its top-level form evaluates): each datum runs in
			// the macro-time evaluator, so it sees the defuns/defvars registered by the
			// preceding forms.
			LispVal form = usesReadEvalMarker(rawForm) ? macroEval.resolveReadTimeEval(rawForm) : rawForm;
			// A package directive updates the macro evaluator's resolver state (so a
			// defmacro under (in-package P) registers its canonical qualified name and
			// its template symbols resolve against P) and is kept verbatim for the
			// compilers' own resolution pass (which tracks the same state itself).
			if (isPackageDirective(form)) {
				macroEval.resolvePackages(form);
				result.add(form);
				continue;
			}
			// Resolve through the same resolver so macro CALL SITES match the
			// canonical registered names (a defmacro under (in-package P) registers
			// P-qualified).
			LispVal resolved = macroEval.resolvePackages(form);
			if (isOperator(resolved, LispNames.DEFMACRO)) {
				macroEval.evalResolved(resolved);
				continue;
			}
			if (LispMacroExpander.isSetfMacroFunctionForm(resolved)) {
				// (setf (macro-function 'new) (macro-function 'existing)): a macro ALIAS
				// is a fact about the macro table, and this evaluator holds the only
				// macro
				// table the compile paths have. Register it and drop the form, exactly
				// like the defmacro it aliases -- the backends never see either.
				macroEval.evalResolved(resolved);
				continue;
			}
			if (isOperator(resolved, LispNames.DEFINE_COMPILER_MACRO)) {
				// Register the hint and drop the form: a compiler macro rewrites CALL
				// SITES here (see expandAll), and the backends have no table to consult
				// at runtime. Registration cannot fail the compile -- an unsupported
				// definition is simply not registered.
				macroEval.evalResolved(resolved);
				continue;
			}
			if (isOperator(resolved, LispNames.DEFINE_MODIFY_MACRO)) {
				// Lower to a defmacro and register it in the macro evaluator, then drop
				// the
				// form: the generated macro is expanded at its call sites like any other
				// user macro (the compilers never see define-modify-macro).
				macroEval.evalResolved(LispMacroExpander.expandDefineModifyMacro((LispCons) resolved));
				continue;
			}
			if (isOperator(resolved, LispNames.DEFTYPE) && resolved instanceof LispCons deftype) {
				// A PARAMETERIZED deftype is folded here, the one pass with an evaluator:
				// its body runs with each parameter defaulted (to * unless the lambda
				// list says otherwise) and the result replaces the form as the equivalent
				// zero-parameter deftype -- the shape the compilers' own registry pass
				// understands, so a bare use of the type name (ironclad's
				// simple-octet-vector in an etypecase) resolves on every backend.
				LispVal folded = macroEval.foldDeftype(deftype);
				if (folded != null && deftype.toList().get(1) instanceof LispSymbol typeName) {
					result.add(properList(List.of(new LispSymbol(LispNames.DEFTYPE), typeName, LispNil.INSTANCE,
							properList(List.of(new LispSymbol(LispNames.QUOTE), folded)))));
					continue;
				}
				result.add(form);
				continue;
			}
			if (isOperator(resolved, LispNames.DEFINE_SETF_EXPANDER) || isOperator(resolved, LispNames.DEFSETF)) {
				// Register the user setf expansion in the macro evaluator, then drop the
				// form: a (setf (place ...) v) call site is rewritten through it below
				// (the compilers cannot run the expander, and treat the definition as a
				// no-op anyway).
				macroEval.evalResolved(resolved);
				continue;
			}
			LispVal expanded = expandAll(resolved, macroEval);
			// A macro may expand into the (rontolisp:async (defun ...)) wrapper; rewrite
			// it here so the definition scanners downstream (WitExportInliner, the
			// library pruner) see the canonical async-defun/async-lambda forms, exactly
			// as they would for code the CLI rewrote before macro expansion.
			expanded = LispMacroExpander.rewriteAsyncSugarForm(expanded);
			if (isOperator(expanded, LispNames.DEFMACRO)) {
				// A macro expanded into a macro definition: consume it as well.
				macroEval.evalResolved(expanded);
				continue;
			}
			if (isOperator(expanded, LispNames.EVAL_WHEN)) {
				// A macro EXPANDED into an eval-when (source-level top-level eval-whens
				// were flattened before the loop): honor the compile-file situations.
				// :compile-toplevel replays every member into the macro-time evaluator
				// -- lisp-namespace's define-namespace and trivia's defoptimizer build
				// their symbol->function registries this way, and a later match
				// expansion READS them at macro time -- and :load-toplevel keeps the
				// member in the program. A defmacro member is consumed here like a
				// top-level one.
				processExpandedEvalWhen((LispCons) expanded, macroEval, result);
				continue;
			}
			if (systemDepth > 0) {
				replayLibraryTopLevel(expanded, macroEval);
			}
			else {
				registerMacroTimeDefinitions(expanded, macroEval);
			}
			if (systemDepth == 0 && isPureConfigSetf(expanded, macroEval)) {
				// A top-level (setf (PLACE ...) V) whose (defun (setf PLACE) ...) writer
				// is
				// a PURE CONFIGURATION SETTER -- its body only assigns special/global
				// variables via side-effect-free computation, and V is itself pure -- is
				// replayed into the macro-time evaluator, so a macro that reads the
				// underlying global at macro-EXPANSION time (e.g. cl-who's
				// with-html-output
				// reading *html-mode*) observes the mutation as a compile-time constant.
				// The
				// form stays in the program so the runtime global tracks it too. The
				// purity walk is conservative (deny by default), so an impure writer or
				// value is never double-run at compile time.
				macroEval.evalResolved(expanded);
			}
			// A form the walk did not touch keeps its ORIGINAL spelling: the resolved
			// canonical form is not always re-resolvable by the compilers' own pass
			// (a cl: symbol canonicalizes to a bare name, which is an error to spell
			// under a package that does not use cl). An emitted expansion conversely
			// must survive that pass: a bare canonical CL name the current package
			// SHADOWS would re-resolve to the shadowing package's own symbol, so it is
			// re-spelled explicitly cl:-qualified (cl-ppcre's defconstant macro expands
			// to cl:defconstant under a package shadowing defconstant).
			result
				.add(expanded.print().equals(resolved.print()) ? form : requalifyShadowedClNames(expanded, macroEval));
			// Drain the definition-time method constructions the form's macro-time
			// evaluation captured (a :metaclass defclass whose finalize-inheritance hook
			// ran a build-dao-methods-style compile): the folded forms join the program
			// right after the defclass, so at run time the driver call has completed --
			// and registered the metaobject -- before they evaluate. Their macro-time
			// evaluation was skipped (the sink swallows it), so they walk through
			// expandAll like any generated expansion.
			if (!mopSplice.isEmpty()) {
				for (LispVal spliced : new ArrayList<>(mopSplice)) {
					result.add(requalifyShadowedClNames(expandAll(spliced, macroEval), macroEval));
				}
				mopSplice.clear();
			}
		}
		return result;
	}

	/**
	 * Processes an {@code (eval-when (situations) member...)} produced by a macro
	 * expansion with compile-file semantics: {@code :compile-toplevel} (or old-style
	 * {@code compile}) replays every member into the macro-time evaluator -- the whole
	 * member, not just definitions, because registry-building side effects like
	 * lisp-namespace's {@code (setf (gethash ...))} are exactly what the situation asks
	 * for -- and {@code :load-toplevel} (or {@code load}) keeps the member in the
	 * program. A member that is itself an eval-when recurses; a defmacro member is
	 * consumed. A member the macro-time evaluator cannot run is skipped with a warning
	 * instead of failing the compile (the lazy-defvar precedent): expansions reading the
	 * state it would have built fail later, plain runtime uses are unaffected.
	 */
	private static void processExpandedEvalWhen(LispCons evalWhen, LispEvaluator macroEval, List<LispVal> result) {
		List<LispVal> parts = evalWhen.toList();
		boolean compileTime = false;
		boolean loadTime = false;
		if (parts.size() >= 2 && parts.get(1) instanceof LispCons situations && situations.isProperList()) {
			for (LispVal situation : situations.toList()) {
				String name = situation instanceof LispSymbol sym ? member(sym.name()) : "";
				compileTime = compileTime || ":COMPILE-TOPLEVEL".equals(name) || "COMPILE".equals(name);
				loadTime = loadTime || ":LOAD-TOPLEVEL".equals(name) || "LOAD".equals(name);
			}
		}
		for (int i = 2; i < parts.size(); i++) {
			LispVal member = parts.get(i);
			if (isOperator(member, LispNames.EVAL_WHEN) && member instanceof LispCons nested) {
				processExpandedEvalWhen(nested, macroEval, result);
				continue;
			}
			if (isOperator(member, LispNames.DEFMACRO)) {
				macroEval.evalResolved(member);
				continue;
			}
			if (compileTime) {
				try {
					macroEval.evalResolved(member);
				}
				catch (RuntimeException ex) {
					System.err.println(
							"WARNING: eval-when (:compile-toplevel) form failed at compile time: " + ex.getMessage());
				}
			}
			else {
				registerMacroTimeDefinitions(member, macroEval);
			}
			if (loadTime) {
				result.add(requalifyShadowedClNames(member, macroEval));
			}
		}
	}

	/**
	 * Re-spells every bare CL-symbol name that the resolver's current package shadows as
	 * the explicit {@code cl:}-qualified form, recursively through the expansion. Quoted
	 * data is exempt (the compilers' resolution pass leaves it untouched, and rewriting
	 * it would change the datum). Only code positions resolved from an explicit
	 * {@code cl:} template spelling can contain such a bare name -- an unqualified use
	 * under the shadowing package resolved to the package's own qualified symbol.
	 */
	private static LispVal requalifyShadowedClNames(LispVal form, LispEvaluator macroEval) {
		return switch (form) {
			case LispSymbol sym -> PackageRegistry.splitQualified(sym.name()) == null
					&& PackageRegistry.isClSymbol(sym.name()) && macroEval.currentPackageShadows(sym.name())
							? new LispSymbol(PackageRegistry.qualify(LispNames.CL_PKG, sym.name())) : sym;
			case LispCons cons -> {
				if (cons.car() instanceof LispSymbol op && LispNames.QUOTE.equals(op.name())) {
					yield cons;
				}
				yield new LispCons(requalifyShadowedClNames(cons.car(), macroEval),
						requalifyShadowedClNames(cons.cdr(), macroEval));
			}
			default -> form;
		};
	}

	private static boolean isOperator(LispVal form, String name) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol sym && name.equals(sym.name());
	}

	/**
	 * Whether any place of a {@code setf} form is a registered user setf-expander place.
	 */
	private static boolean hasUserSetfPlace(LispCons setf, LispEvaluator macroEval) {
		List<LispVal> parts = setf.toList();
		for (int i = 1; i + 1 < parts.size(); i += 2) {
			if (parts.get(i) instanceof LispCons place && place.car() instanceof LispSymbol acc
					&& macroEval.hasSetfExpander(acc.name())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Registers a top-level definition into the macro-time evaluator so later macro
	 * bodies (and later defparameter value expressions) can use it: a {@code defun} (no
	 * body execution), the CLOS definitions (constructor/accessor/method defuns plus the
	 * generic's dispatcher -- cl-who's process-tag chain calls a generic at expansion
	 * time), and {@code defvar}/{@code defparameter}/{@code defconstant} (so an
	 * expansion-time function can READ the global -- cl-who's tree-to-commands chain
	 * reads {@code *html-mode*} and friends). A top-level {@code progn} is walked member
	 * by member (a macrolet whose body defines defuns expands into one, and the compilers
	 * flatten it the same way), evaluating ONLY the definition members. Every form stays
	 * in the program for the compilers' own splice.
	 * <p>
	 * The defvar family is registered LAZILY (see
	 * {@link LispEvaluator#registerLazyGlobal}): the name is proclaimed special at once
	 * but its value expression runs only if an expansion actually reads the global. A
	 * library that builds its tables in a top-level {@code defvar} therefore builds them
	 * once, in the compiled program, instead of once here and once again at run time. A
	 * value expression that cannot evaluate at macro time (e.g. it needs runtime-only
	 * state) is skipped with a warning instead of failing the compile -- macros reading
	 * that global would fail later, plain runtime uses are unaffected.
	 */
	private static void registerMacroTimeDefinitions(LispVal form, LispEvaluator macroEval) {
		if (isOperator(form, LispNames.PROGN) && form instanceof LispCons progn && progn.isProperList()) {
			for (LispVal member : progn.toList().subList(1, progn.toList().size())) {
				registerMacroTimeDefinitions(member, macroEval);
			}
			return;
		}
		// A top-level (let/let* (...) definition...) wraps definitions in a shared
		// lexical environment (s-sql's register-sql-operators closes each
		// expand-sql-op method over its make-expander expander). Replaying the WHOLE
		// let gives the macro-time definitions the same closure. Gated on every body
		// form being a definition, so an unrelated top-level side effect is never
		// double-run; the binding inits do run here as well as at run time, the same
		// scaffolding-only assumption the definition replay itself makes.
		if ((isOperator(form, LispNames.LET) || isOperator(form, LispNames.LET_STAR)) && form instanceof LispCons let
				&& let.isProperList()) {
			List<LispVal> parts = let.toList();
			boolean allDefinitions = parts.size() >= 3;
			for (int i = 2; i < parts.size(); i++) {
				if (!isDefinitionForm(parts.get(i))) {
					allDefinitions = false;
					break;
				}
			}
			if (allDefinitions) {
				macroEval.evalResolved(form);
			}
			return;
		}
		if (isDefinitionForm(form)) {
			macroEval.evalResolved(form);
			return;
		}
		if (isOperator(form, LispNames.DEFVAR) || isOperator(form, LispNames.DEFPARAMETER)
				|| isOperator(form, LispNames.DEFCONSTANT)) {
			macroEval.registerLazyGlobal(form);
		}
	}

	/** A provenance-marker form, matched by member name in any package spelling. */
	private static boolean isProvenanceMarker(LispVal form, String marker) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol sym
				&& marker.equals(member(sym.name()));
	}

	/**
	 * Replays a top-level form of a SPLICED SYSTEM into the macro-time evaluator: in CL,
	 * the systems a file depends on are LOADED into the compiling image before the file
	 * compiles, so their registry-building top-level calls have already run when macro
	 * expansion reads the registries (trivia's set-vector-matcher pattern table). The
	 * defvar family keeps the lazy registration (the value expression runs only if an
	 * expansion reads the global, see {@link #registerMacroTimeDefinitions}); atoms and
	 * quoted data have no effect to replay; everything else evaluates, and a form the
	 * macro-time evaluator cannot run is skipped with a warning instead of failing the
	 * compile -- expansions reading the state it would have built fail later, plain
	 * runtime uses are unaffected. Output the replay writes is discarded (the macro
	 * evaluator prints to a null stream), and the form stays in the program either way.
	 */
	private static void replayLibraryTopLevel(LispVal form, LispEvaluator macroEval) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
				|| LispNames.QUOTE.equals(op.name())) {
			return;
		}
		if (isOperator(form, LispNames.PROGN) && cons.isProperList()) {
			for (LispVal member : cons.toList().subList(1, cons.toList().size())) {
				replayLibraryTopLevel(member, macroEval);
			}
			return;
		}
		if (isOperator(form, LispNames.DEFVAR) || isOperator(form, LispNames.DEFPARAMETER)
				|| isOperator(form, LispNames.DEFCONSTANT)) {
			macroEval.registerLazyGlobal(form);
			return;
		}
		try {
			macroEval.evalResolved(form);
		}
		catch (RuntimeException ex) {
			System.err.println("WARNING: library top-level form failed at compile time (skipped): " + ex.getMessage());
		}
	}

	/** A definition form the macro-time evaluator replays; see the caller above. */
	private static boolean isDefinitionForm(LispVal form) {
		return isOperator(form, LispNames.DEFUN) || isOperator(form, LispNames.DEFCLASS)
				|| isOperator(form, LispNames.DEFGENERIC) || isOperator(form, LispNames.DEFMETHOD)
				|| isOperator(form, LispNames.DEFINE_CONDITION) || isOperator(form, LispNames.DEFSTRUCT);
	}

	/**
	 * Whether the top-level form is a {@code defclass} carrying {@code (:metaclass M)} --
	 * which activates the pass even without a defmacro: the class-definition protocol
	 * must run in the macro-time evaluator so a {@code build-dao-methods}-style
	 * {@code compile} inside a {@code finalize-inheritance} hook is intercepted and its
	 * method definitions spliced (see {@code MopEvalCapture}).
	 */
	private static boolean isMetaclassDefclass(LispVal form) {
		return isOperator(form, LispNames.DEFCLASS) && form instanceof LispCons cons && cons.isProperList()
				&& LispMacroExpander.defclassUsesMetaclass(cons);
	}

	/** Whether the form contains a {@code %read-eval} marker symbol anywhere. */
	private static boolean usesReadEvalMarker(LispVal form) {
		return switch (form) {
			case LispSymbol sym ->
				LispNames.READ_EVAL.equals(sym.name()) || LispNames.READ_EVAL_TEMPLATE.equals(sym.name());
			case LispCons cons -> usesReadEvalMarker(cons.car()) || usesReadEvalMarker(cons.cdr());
			default -> false;
		};
	}

	// --- Pure-config-setter detection ------------------------------------------------
	//
	// rontolisp expands ALL macros at compile time, so a macro that reads a global at
	// macro-EXPANSION time (cl-who's with-html-output reads *html-mode*) cannot see a
	// runtime (setf (place) ...). The fix is to REPLAY such a top-level setf into the
	// macro-time evaluator -- but only when doing so cannot double-run an external side
	// effect. The walk below is a static purity judgment that replaces the old
	// library-specific data file (macro-time-setf-places.txt): a setf is replayed iff its
	// (defun (setf PLACE) ...) writer is a pure configuration setter and its value is
	// pure.

	// A top-level (setf (PLACE args...) VALUE) is a replayable pure config setter when:
	// the
	// value and any place args are pure expressions, and the user-defined (setf PLACE)
	// writer's body only assigns special/global variables via side-effect-free
	// computation.
	// The bar is asymmetric -- deny by default -- so an impure writer/value is never
	// replayed (a false positive would double-run side effects at compile time); a false
	// negative merely fails to propagate a config change to expansion time.
	private static boolean isPureConfigSetf(LispVal form, LispEvaluator macroEval) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
				|| !LispNames.SETF.equals(member(op.name())) || !(cons.cdr() instanceof LispCons rest)
				|| !(rest.car() instanceof LispCons place) || !(place.car() instanceof LispSymbol placeOp)
				|| !(rest.cdr() instanceof LispCons valTail) || !(valTail.cdr() instanceof LispNil)) {
			// Only the single place/value shape (setf (PLACE args...) VALUE) is handled;
			// a
			// multi-pair setf or a non-accessor place is left for runtime only.
			return false;
		}
		// The value written and any subforms of the place are evaluated on replay, so
		// they
		// must be pure (no side effect, no mutation).
		if (!isPure(valTail.car(), false, macroEval)) {
			return false;
		}
		for (LispVal arg : cdrList(place)) {
			if (!isPure(arg, false, macroEval)) {
				return false;
			}
		}
		// The (setf PLACE) writer must be a user-defined pure config setter: no writer (a
		// built-in place or none) is not replayable.
		List<LispVal> body = macroEval.setfWriterBody(placeOp.name());
		if (body == null) {
			return false;
		}
		for (LispVal bodyForm : body) {
			if (!isPure(bodyForm, true, macroEval)) {
				return false;
			}
		}
		return true;
	}

	// Whether FORM is a side-effect-free expression. With allowSpecialMutation, a
	// setq/setf of a special/global variable is permitted (the config-setter body);
	// otherwise any assignment is impure. Deny by default: an operator outside the pure
	// allow-list (I/O, destructive ops, incf/push, funcall/apply, error, an unknown or
	// user function, ...) makes the form impure.
	private static boolean isPure(LispVal form, boolean allowSpecialMutation, LispEvaluator macroEval) {
		if (!(form instanceof LispCons cons)) {
			// A number/string/character/keyword literal, nil/t, or a variable read.
			return true;
		}
		if (!cons.isProperList() || !(cons.car() instanceof LispSymbol op)) {
			// A dotted datum, or a computed operator ((lambda ...) / ((f) ...)): reject.
			return false;
		}
		List<LispVal> parts = cons.toList();
		return switch (member(op.name())) {
			case LispNames.QUOTE -> true; // quoted literal data
			case LispNames.BLOCK -> {
				// (block name body...): the name is data, the body is expressions --
				// every defun body is now block-wrapped (named return-from), so the
				// purity walk must see through the wrapper.
				if (parts.size() < 2) {
					yield false;
				}
				for (int i = 2; i < parts.size(); i++) {
					if (!isPure(parts.get(i), allowSpecialMutation, macroEval)) {
						yield false;
					}
				}
				yield true;
			}
			case LispNames.SETQ -> allowSpecialMutation && isPureAssignment(parts, macroEval);
			case LispNames.SETF -> allowSpecialMutation && isPureAssignment(parts, macroEval);
			case LispNames.LET, LispNames.LET_STAR -> isPureLet(parts, allowSpecialMutation, macroEval);
			case LispNames.COND -> isPureCond(parts, allowSpecialMutation, macroEval);
			case LispNames.CASE, LispNames.ECASE, LispNames.CCASE, LispNames.TYPECASE, LispNames.ETYPECASE ->
				isPureCase(parts, allowSpecialMutation, macroEval);
			case LispNames.THE ->
				// (the type value): the type is data; only the value is evaluated.
				parts.size() >= 3 && isPure(parts.get(2), allowSpecialMutation, macroEval);
			default -> {
				if (!PURE_OPERATORS.contains(member(op.name()))) {
					yield false;
				}
				// A pure control form / pure builtin: every argument is an expression.
				for (int i = 1; i < parts.size(); i++) {
					if (!isPure(parts.get(i), allowSpecialMutation, macroEval)) {
						yield false;
					}
				}
				yield true;
			}
		};
	}

	// (setq v1 e1 ...) / (setf p1 e1 ...): every target must be a special/global variable
	// symbol (a non-symbol place is a data-structure mutation; a lexical/unknown target
	// is
	// not config state), and every value must be pure.
	private static boolean isPureAssignment(List<LispVal> parts, LispEvaluator macroEval) {
		if (parts.size() < 3 || (parts.size() - 1) % 2 != 0) {
			return false;
		}
		for (int i = 1; i < parts.size(); i += 2) {
			if (!(parts.get(i) instanceof LispSymbol target) || !macroEval.isGlobalOrSpecialVariable(target.name())
					|| !isPure(parts.get(i + 1), true, macroEval)) {
				return false;
			}
		}
		return true;
	}

	// (let/let* (bindings) body...): bound NAMES are new lexicals (skipped); each init
	// and
	// body form is an expression.
	private static boolean isPureLet(List<LispVal> parts, boolean allowSpecialMutation, LispEvaluator macroEval) {
		if (parts.size() < 2) {
			return false;
		}
		if (parts.get(1) instanceof LispCons bindings) {
			if (!bindings.isProperList()) {
				return false;
			}
			for (LispVal binding : bindings.toList()) {
				if (binding instanceof LispSymbol) {
					continue; // (let (x) ...): bound to nil
				}
				if (!(binding instanceof LispCons pair) || !pair.isProperList()) {
					return false;
				}
				List<LispVal> pairParts = pair.toList();
				for (int i = 1; i < pairParts.size(); i++) {
					if (!isPure(pairParts.get(i), allowSpecialMutation, macroEval)) {
						return false;
					}
				}
			}
		}
		else if (!(parts.get(1) instanceof LispNil)) {
			return false;
		}
		for (int i = 2; i < parts.size(); i++) {
			if (!isPure(parts.get(i), allowSpecialMutation, macroEval)) {
				return false;
			}
		}
		return true;
	}

	// (cond (test body...)...): every subform of every clause is an expression.
	private static boolean isPureCond(List<LispVal> parts, boolean allowSpecialMutation, LispEvaluator macroEval) {
		for (int i = 1; i < parts.size(); i++) {
			if (parts.get(i) instanceof LispNil) {
				continue;
			}
			if (!(parts.get(i) instanceof LispCons clause) || !clause.isProperList()) {
				return false;
			}
			for (LispVal f : clause.toList()) {
				if (!isPure(f, allowSpecialMutation, macroEval)) {
					return false;
				}
			}
		}
		return true;
	}

	// (case/ecase/... keyform (keys body...)...): the keyform and clause bodies are
	// expressions; the keys are unevaluated data (skipped).
	private static boolean isPureCase(List<LispVal> parts, boolean allowSpecialMutation, LispEvaluator macroEval) {
		if (parts.size() < 2 || !isPure(parts.get(1), allowSpecialMutation, macroEval)) {
			return false;
		}
		for (int i = 2; i < parts.size(); i++) {
			if (parts.get(i) instanceof LispNil) {
				continue;
			}
			if (!(parts.get(i) instanceof LispCons clause) || !clause.isProperList()) {
				return false;
			}
			List<LispVal> clauseParts = clause.toList();
			for (int j = 1; j < clauseParts.size(); j++) {
				if (!isPure(clauseParts.get(j), allowSpecialMutation, macroEval)) {
					return false;
				}
			}
		}
		return true;
	}

	private static List<LispVal> cdrList(LispCons cons) {
		return cons.cdr() instanceof LispCons rest && rest.isProperList() ? rest.toList() : List.of();
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	// Operators whose every argument is an expression AND which have no side effect: pure
	// control forms and side-effect-free builtins (arithmetic, comparison, logic, type
	// predicates, and non-destructive constructors/readers). An operator absent from this
	// set (and from the structural cases in isPure) is treated as impure. Deliberately
	// EXCLUDES anything that mutates or does I/O: print/format, rplaca/rplacd, aset,
	// push/pop/incf/decf, nconc/nreverse/sort/delete, funcall/apply, error, file/stream
	// ops.
	private static final Set<String> PURE_OPERATORS = Set.of("if", "when", "unless", "and", "or", "not", "progn",
			"prog1", "prog2", "values", "identity", "+", "-", "*", "/", "1+", "1-", "mod", "rem", "abs", "min", "max",
			"expt", "sqrt", "isqrt", "gcd", "lcm", "floor", "ceiling", "round", "truncate", "float", "signum", "=",
			"/=", "<", ">", "<=", ">=", "eq", "eql", "equal", "equalp", "char=", "char<", "char>", "char<=", "char>=",
			"string=", "string<", "string>", "string<=", "string>=", "string-equal", "null", "atom", "consp", "listp",
			"symbolp", "keywordp", "stringp", "numberp", "integerp", "floatp", "characterp", "functionp", "zerop",
			"plusp", "minusp", "evenp", "oddp", "endp", "car", "cdr", "caar", "cadr", "cdar", "cddr", "caddr", "first",
			"second", "third", "fourth", "rest", "last", "nth", "nthcdr", "elt", "length", "list", "list*", "cons",
			"reverse", "append", "member", "assoc", "getf", "position", "find", "string", "symbol-name", "char-code",
			"code-char");

	// in-package/defpackage in any package spelling ((cl:in-package ...) included) --
	// the resolver consumes these, so they must be recognized BEFORE resolution to be
	// kept verbatim for the compilers.
	private static boolean isPackageDirective(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol sym)) {
			return false;
		}
		String name = sym.name();
		int colon = name.lastIndexOf(':');
		String member = colon >= 0 ? name.substring(colon + 1) : name;
		// The %push-package/%pop-package markers LoadInliner brackets a loaded file with
		// are package directives too: the macro evaluator's resolver must track their
		// save/restore so a defmacro after a load resolves in the caller's package, and
		// they are kept verbatim for the compilers' own resolution pass.
		return LispNames.IN_PACKAGE.equals(member) || LispNames.DEFPACKAGE.equals(member)
				|| LispNames.USE_PACKAGE.equals(member) || LispNames.EXPORT.equals(member)
				|| LispNames.UNEXPORT.equals(member) || LispNames.PUSH_PACKAGE.equals(member)
				|| LispNames.POP_PACKAGE.equals(member);
	}

	private static boolean usesMacroexpand(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym
				&& (LispNames.MACROEXPAND.equals(sym.name()) || LispNames.MACROEXPAND_1.equals(sym.name()))) {
			return true;
		}
		return usesMacroexpand(cons.car()) || usesMacroexpand(cons.cdr());
	}

	// A macrolet anywhere (top level or nested in a body) forces the pass to run so its
	// local macros are expanded away; the compilers have no macrolet support of their
	// own.
	/**
	 * Whether the top-level form is a {@code deftype} with a non-empty lambda list -- the
	 * shape folded into a zero-parameter deftype by the loop above, which also activates
	 * the pass.
	 */
	private static boolean isParameterizedDeftype(LispVal form) {
		return isOperator(form, LispNames.DEFTYPE) && form instanceof LispCons cons && cons.isProperList()
				&& cons.toList().size() >= 4 && cons.toList().get(2) instanceof LispCons;
	}

	private static boolean usesMacrolet(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym && LispNames.MACROLET.equals(sym.name())) {
			return true;
		}
		return usesMacrolet(cons.car()) || usesMacrolet(cons.cdr());
	}

	/**
	 * Recursively expands every user macro call in the form. The walk is aware of the
	 * special-form shapes whose subforms are not expressions (binding lists, parameter
	 * lists, case keys), so a macro name reused there is left alone.
	 */
	static LispVal expandAll(LispVal form, LispEvaluator macroEval) {
		// Expand head-position user macros repeatedly; the expansion may itself be a
		// macro call (or an atom, which needs no further walking).
		while (form instanceof LispCons cons && cons.car() instanceof LispSymbol sym
				&& macroEval.isUserMacro(sym.name())) {
			form = macroEval.expandUserMacro(cons);
		}
		// Then the operator's compiler macro, at most ONCE: the standard shape rewrites a
		// call into a call of the SAME function with a better argument (cl-ppcre's
		// literal regex becomes a load-time-value scanner), so re-applying would either
		// spin or depend on the macro declining the second time. Declining is signalled
		// by returning the form itself; a user macro of the same name has already won
		// above, as CL requires.
		if (form instanceof LispCons call && call.car() instanceof LispSymbol op && call.isProperList()
				&& macroEval.hasCompilerMacro(op.name())) {
			LispVal expansion = macroEval.expandCompilerMacro(call);
			if (expansion != form) {
				form = expansion;
				while (form instanceof LispCons cons && cons.car() instanceof LispSymbol sym
						&& macroEval.isUserMacro(sym.name())) {
					form = macroEval.expandUserMacro(cons);
				}
			}
		}
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol sym) {
			if (LispNames.QUOTE.equals(sym.name()) || LispNames.DEFMACRO.equals(sym.name())) {
				return form;
			}
			if (!cons.isProperList()) {
				// An improper list is never a call form; the legitimate appearances
				// are data patterns (a loop destructuring pattern like
				// (value . remaining)) whose elements are plain variables.
				return form;
			}
			List<LispVal> parts = cons.toList();
			switch (sym.name()) {
				case LispNames.LET, LispNames.LET_STAR, LispNames.DO, LispNames.DO_STAR:
					// (let ((name init)...) body...) / (do ((var init step)...) (end
					// result...) body...): binding names stay, init/step/end/result and
					// the body are expressions.
					return rebuild(parts, 2, macroEval, expandBindings(parts.get(1), macroEval));
				case LispNames.LAMBDA:
					return rebuild(parts, 2, macroEval, parts.get(1));
				case LispNames.DEFUN:
					return rebuild(parts, 3, macroEval, parts.get(1), parts.get(2));
				case LispNames.DEFMETHOD: {
					// (defmethod name [qualifier] (params...) body...): the name,
					// optional
					// qualifier, and lambda list (specializers included) stay, the body
					// is
					// expressions. A malformed form is left for the expansion to report.
					if (parts.size() < 3) {
						return form;
					}
					// A leading symbol after the name is a qualifier, so the lambda list
					// (and body) shift by one.
					int llIndex = parts.get(2) instanceof LispSymbol ? 3 : 2;
					if (parts.size() <= llIndex) {
						return form;
					}
					LispVal[] kept = new LispVal[llIndex];
					for (int k = 1; k <= llIndex; k++) {
						kept[k - 1] = parts.get(k);
					}
					return rebuild(parts, llIndex + 1, macroEval, kept);
				}
				case LispNames.DEFGENERIC: {
					// (defgeneric name (params...) options...): names, lambda lists, and
					// options are data, EXCEPT an inline (:method ...) clause's body,
					// which is expressions -- a macrolet-local macro called there (jzon's
					// %coerced-fields-slots) must expand away like a defmethod body.
					List<LispVal> newParts = new ArrayList<>(parts);
					for (int i = 3; i < parts.size(); i++) {
						if (parts.get(i) instanceof LispCons opt && opt.isProperList()
								&& opt.car() instanceof LispSymbol key && ":METHOD".equals(key.name())) {
							List<LispVal> mp = opt.toList();
							// (:method [qualifier] (lambda-list) body...)
							int llIndex = mp.size() > 1 && mp.get(1) instanceof LispSymbol ? 2 : 1;
							if (mp.size() > llIndex + 1) {
								List<LispVal> newMethod = new ArrayList<>(mp.subList(0, llIndex + 1));
								for (int j = llIndex + 1; j < mp.size(); j++) {
									newMethod.add(expandAll(mp.get(j), macroEval));
								}
								newParts.set(i, properList(newMethod));
							}
						}
					}
					return properList(newParts);
				}
				case LispNames.DEFCLASS: {
					// (defclass name (super) ((slot options...)...) options...): names
					// and options stay, only :initform values are expressions.
					if (parts.size() < 4 || !(parts.get(3) instanceof LispCons slotsCons)) {
						return form;
					}
					List<LispVal> newSlots = new ArrayList<>();
					for (LispVal slot : slotsCons.toList()) {
						if (slot instanceof LispCons slotCons && slotCons.isProperList()) {
							List<LispVal> slotParts = slotCons.toList();
							List<LispVal> newSlot = new ArrayList<>();
							newSlot.add(slotParts.get(0));
							for (int i = 1; i < slotParts.size(); i += 2) {
								newSlot.add(slotParts.get(i));
								if (i + 1 < slotParts.size()) {
									boolean initform = slotParts.get(i) instanceof LispSymbol key
											&& ":INITFORM".equals(key.name());
									newSlot.add(initform ? expandAll(slotParts.get(i + 1), macroEval)
											: slotParts.get(i + 1));
								}
							}
							newSlots.add(properList(newSlot));
						}
						else {
							newSlots.add(slot);
						}
					}
					List<LispVal> newParts = new ArrayList<>(parts);
					newParts.set(3, properList(newSlots));
					return properList(newParts);
				}
				case LispNames.DEFSTRUCT: {
					// (defstruct name (slot default)...): the struct and slot names stay,
					// only slot defaults are expressions.
					List<LispVal> newParts = new ArrayList<>();
					newParts.add(parts.get(0));
					newParts.add(parts.get(1));
					for (int i = 2; i < parts.size(); i++) {
						if (parts.get(i) instanceof LispCons slotCons) {
							List<LispVal> slotParts = slotCons.toList();
							List<LispVal> newSlot = new ArrayList<>();
							newSlot.add(slotParts.get(0));
							for (int j = 1; j < slotParts.size(); j++) {
								newSlot.add(expandAll(slotParts.get(j), macroEval));
							}
							newParts.add(properList(newSlot));
						}
						else {
							newParts.add(parts.get(i));
						}
					}
					return properList(newParts);
				}
				case LispNames.FLET, LispNames.LABELS: {
					// (flet ((name lambda-list body...)...) body...): the definition
					// names and lambda lists stay, definition bodies and the body are
					// expressions.
					if (parts.size() < 2) {
						return form; // malformed; the expansion reports it
					}
					if (!(parts.get(1) instanceof LispCons defsCons)) {
						return rebuild(parts, 2, macroEval, parts.get(1));
					}
					List<LispVal> newDefs = new ArrayList<>();
					for (LispVal def : defsCons.toList()) {
						if (def instanceof LispCons defCons && defCons.isProperList() && defCons.toList().size() >= 2) {
							List<LispVal> dp = defCons.toList();
							List<LispVal> newDef = new ArrayList<>();
							newDef.add(dp.get(0));
							newDef.add(dp.get(1));
							for (int i = 2; i < dp.size(); i++) {
								newDef.add(expandAll(dp.get(i), macroEval));
							}
							newDefs.add(properList(newDef));
						}
						else {
							newDefs.add(def);
						}
					}
					return rebuild(parts, 2, macroEval, properList(newDefs));
				}
				case LispNames.MACROLET: {
					// (macrolet ((name lambda-list body...)...) body...): register the
					// local macros for the extent of the body walk, expand the body with
					// them active, and drop the macrolet (its definitions are consumed
					// here,
					// like defmacro). Lexically correct: the locals are removed again
					// after
					// the body, so they do not leak to sibling forms.
					if (parts.size() < 2 || !(parts.get(1) instanceof LispCons || parts.get(1) instanceof LispNil)) {
						return form; // malformed; leave for the interpreter/compiler to
										// report
					}
					return expandMacrolet(parts, macroEval);
				}
				case LispNames.SYMBOL_MACROLET: {
					// (symbol-macrolet ((name expansion)...) body...): the binding names
					// stay; the expansion forms and the body are expressions -- user
					// macros
					// inside BOTH must be gone before the backends run the substitution
					// (the compilers have no macro table to expand a call the
					// substitution
					// splices in).
					if (parts.size() < 2) {
						return form; // malformed; the expansion reports it
					}
					return rebuild(parts, 2, macroEval, expandBindings(parts.get(1), macroEval));
				}
				case LispNames.MULTIPLE_VALUE_BIND: {
					// (multiple-value-bind (vars...) values-form body...): the variable
					// list stays, the values form and the body are expressions.
					if (parts.size() < 2) {
						return form; // malformed; the expansion reports it
					}
					return rebuild(parts, 2, macroEval, parts.get(1));
				}
				case LispNames.MULTIPLE_VALUE_SETQ: {
					// (multiple-value-setq (vars...) values-form): the variable list
					// stays,
					// the values form is an expression.
					if (parts.size() < 2) {
						return form; // malformed; the expansion reports it
					}
					return rebuild(parts, 2, macroEval, parts.get(1));
				}
				case LispNames.DESTRUCTURING_BIND: {
					// (destructuring-bind pattern form body...): the pattern stays (like
					// a lambda list), the form and the body are expressions.
					if (parts.size() < 2) {
						return form; // malformed; the expansion reports it
					}
					return rebuild(parts, 2, macroEval, parts.get(1));
				}
				case LispNames.LOOP: {
					// (loop clause...): clause keywords and expressions are walked, but a
					// destructuring pattern is data -- an improper or cons-headed element
					// (`for ((x . y) . rest) on args`) must survive verbatim, where the
					// generic walk would reject it as a call form.
					List<LispVal> newParts = new ArrayList<>();
					newParts.add(parts.get(0));
					for (int i = 1; i < parts.size(); i++) {
						LispVal part = parts.get(i);
						boolean pattern = part instanceof LispCons pc
								&& (!pc.isProperList() || !(pc.car() instanceof LispSymbol));
						newParts.add(pattern ? part : expandAll(part, macroEval));
					}
					return properList(newParts);
				}
				case LispNames.DOLIST, LispNames.DOTIMES: {
					// (dolist (var listform result) body...): var stays.
					LispVal spec = parts.get(1);
					if (spec instanceof LispCons specCons) {
						List<LispVal> specParts = specCons.toList();
						List<LispVal> newSpec = new ArrayList<>();
						newSpec.add(specParts.get(0));
						for (int i = 1; i < specParts.size(); i++) {
							newSpec.add(expandAll(specParts.get(i), macroEval));
						}
						spec = properList(newSpec);
					}
					return rebuild(parts, 2, macroEval, spec);
				}
				case LispNames.MACROEXPAND, LispNames.MACROEXPAND_1: {
					// Fold a literal quoted argument to its expansion at compile time
					// (the
					// only form the compilers can support: the macro table does not exist
					// at runtime). A computed argument is left as-is and fails naturally.
					// The optional second argument is CL's macro-expansion environment,
					// accepted and ignored (there is none to consult), so the fold
					// applies
					// to both call shapes.
					if ((parts.size() == 2 || parts.size() == 3) && parts.get(1) instanceof LispCons quoted
							&& quoted.car() instanceof LispSymbol qs && LispNames.QUOTE.equals(qs.name())
							&& quoted.cdr() instanceof LispCons quotedCdr) {
						LispVal target = quotedCdr.car();
						LispVal expanded = LispNames.MACROEXPAND.equals(sym.name()) ? macroEval.macroexpand(target)
								: macroEval.macroexpand1(target);
						return properList(List.of(new LispSymbol(LispNames.QUOTE), expanded));
					}
					return rebuild(parts, 1, macroEval);
				}
				case LispNames.CASE, LispNames.ECASE, LispNames.CCASE, LispNames.TYPECASE, LispNames.ETYPECASE: {
					// (case keyform (keys body...)...): keys are unevaluated data.
					List<LispVal> newParts = new ArrayList<>();
					newParts.add(parts.get(0));
					newParts.add(expandAll(parts.get(1), macroEval));
					for (int i = 2; i < parts.size(); i++) {
						if (parts.get(i) instanceof LispCons clause) {
							List<LispVal> clauseParts = clause.toList();
							List<LispVal> newClause = new ArrayList<>();
							newClause.add(clauseParts.get(0));
							for (int j = 1; j < clauseParts.size(); j++) {
								newClause.add(expandAll(clauseParts.get(j), macroEval));
							}
							newParts.add(properList(newClause));
						}
						else {
							newParts.add(parts.get(i));
						}
					}
					return properList(newParts);
				}
				case LispNames.SETF: {
					// Walk the subforms first, then, if any place is a registered user
					// setf-expander place, rewrite the whole setf through the macro-time
					// evaluator (the compilers cannot run the expander themselves).
					LispVal walked = rebuild(parts, 1, macroEval);
					if (walked instanceof LispCons wc && hasUserSetfPlace(wc, macroEval)) {
						return macroEval.expandSetfMaybeUserExpander(wc);
					}
					return walked;
				}
				case LispNames.INCF, LispNames.DECF: {
					// incf/decf on a user setf-expander place: expand to setf here so the
					// place rewrite above applies; an ordinary place is left for the
					// compiler's own expansion.
					if (parts.size() >= 2 && parts.get(1) instanceof LispCons place
							&& place.car() instanceof LispSymbol acc && macroEval.hasSetfExpander(acc.name())) {
						LispVal setfForm = LispNames.INCF.equals(sym.name()) ? LispMacroExpander.expandIncf(cons)
								: LispMacroExpander.expandDecf(cons);
						return expandAll(setfForm, macroEval);
					}
					return rebuild(parts, 1, macroEval);
				}
				default:
					// Every other operator: the head stays, all arguments are walked.
					return rebuild(parts, 1, macroEval);
			}
		}
		// Non-symbol head, e.g. ((lambda (x) ...) arg...): walk every element.
		requireProperCallForm(cons);
		List<LispVal> parts = cons.toList();
		List<LispVal> newParts = new ArrayList<>();
		for (LispVal part : parts) {
			newParts.add(expandAll(part, macroEval));
		}
		return properList(newParts);
	}

	// Expands a macrolet: installs each local macro into the macro-time evaluator, walks
	// the body with them active (so calls to the locals expand), restores the previous
	// bindings, and returns the expanded body as a (progn ...) so the macrolet wrapper is
	// dropped. The compilers never see macrolet; the interpreter handles it natively.
	private static LispVal expandMacrolet(List<LispVal> parts, LispEvaluator macroEval) {
		List<LispVal> defs = parts.get(1) instanceof LispCons defsCons ? defsCons.toList() : List.of();
		java.util.LinkedHashMap<String, Object> saved = new java.util.LinkedHashMap<>();
		for (LispVal def : defs) {
			if (def instanceof LispCons defCons && defCons.isProperList()) {
				List<LispVal> dp = defCons.toList();
				if (dp.size() >= 2 && dp.get(0) instanceof LispSymbol name && !name.isKeyword()) {
					Object previous = macroEval.pushLocalMacro(name, dp.get(1), dp.subList(2, dp.size()));
					// Only remember the binding present before this macrolet (a duplicate
					// name pushed twice must still restore to the outermost prior value).
					if (!saved.containsKey(name.name())) {
						saved.put(name.name(), previous);
					}
				}
			}
		}
		try {
			List<LispVal> body = new ArrayList<>();
			body.add(new LispSymbol(LispNames.PROGN));
			for (int i = 2; i < parts.size(); i++) {
				body.add(expandAll(parts.get(i), macroEval));
			}
			if (body.size() == 1) {
				return LispNil.INSTANCE;
			}
			if (body.size() == 2) {
				return body.get(1);
			}
			return properList(body);
		}
		finally {
			for (java.util.Map.Entry<String, Object> entry : saved.entrySet()) {
				macroEval.popLocalMacro(entry.getKey(), entry.getValue());
			}
		}
	}

	// A dotted tail is only meaningful as data (inside quote); rebuilding a call form
	// through toList() would silently drop it, so reject it here instead.
	private static void requireProperCallForm(LispCons cons) {
		if (!cons.isProperList()) {
			throw new LispEvalException("Improper list in call position: " + cons.print());
		}
	}

	// Rebuilds a form keeping parts[0] and the given fixed subforms verbatim, walking
	// parts[from..] as expressions.
	private static LispVal rebuild(List<LispVal> parts, int from, LispEvaluator macroEval, LispVal... fixed) {
		List<LispVal> newParts = new ArrayList<>();
		newParts.add(parts.get(0));
		newParts.addAll(List.of(fixed));
		for (int i = from; i < parts.size(); i++) {
			newParts.add(expandAll(parts.get(i), macroEval));
		}
		return properList(newParts);
	}

	// Expands the init/step forms of a let/do binding list, keeping the bound names.
	private static LispVal expandBindings(LispVal bindings, LispEvaluator macroEval) {
		if (!(bindings instanceof LispCons bindingsCons)) {
			return bindings;
		}
		List<LispVal> newBindings = new ArrayList<>();
		for (LispVal binding : bindingsCons.toList()) {
			if (binding instanceof LispCons bindingCons) {
				List<LispVal> pair = bindingCons.toList();
				List<LispVal> newPair = new ArrayList<>();
				newPair.add(pair.get(0));
				for (int i = 1; i < pair.size(); i++) {
					newPair.add(expandAll(pair.get(i), macroEval));
				}
				newBindings.add(properList(newPair));
			}
			else {
				newBindings.add(binding);
			}
		}
		return properList(newBindings);
	}

	private static LispVal properList(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

}
