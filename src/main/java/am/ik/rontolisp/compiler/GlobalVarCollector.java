package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.Set;
import java.util.function.Consumer;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.macro.SpecialVarCollector;
import org.jspecify.annotations.Nullable;

/**
 * Collects the names of top-level global variables in a program. Shared by the JVM and
 * WASM compilers, which give each global its own persistent backing store (a JVM static
 * field / a WASM module-level global) so a {@code defun}/{@code lambda} body can read and
 * assign it. A symbol is a top-level global when it is the name of a top-level
 * {@code defvar}/{@code defparameter}/{@code defconstant}, or the place of a top-level
 * {@code setq}/{@code setf} whose place is a bare symbol (Lisp-2: a top-level assignment
 * targets the global variable namespace), or a name a function body or a top-level form
 * assigns with no lexical binding in scope ({@link #collectFreeAssigned}).
 */
public final class GlobalVarCollector {

	private GlobalVarCollector() {
	}

	/**
	 * The globals the compilers write for their own bookkeeping on nearly every call: the
	 * multiple-value spill and the handler / restart cluster stacks. No eval'd form reads
	 * them -- the eval runtime has no {@code values}, {@code handler-bind} or
	 * {@code restart-case} of its own, and calls into compiled code, which reads the
	 * globals directly.
	 */
	private static final Set<String> COMPILER_CHANNELS = Set.of(LispNames.MV_SPILL, LispNames.HANDLER_CLUSTERS_VAR,
			LispNames.RESTART_CLUSTERS_VAR);

	/**
	 * Whether an assignment of the global {@code name} is mirrored into the eval
	 * runtime's global environment ({@code .kb/eval-runtime.md}, "Global mirroring")
	 * where the program carries one: every global but the compiler's own channels. A
	 * mirror write walks that environment, which holds every global the program assigns,
	 * so mirroring a channel written on every call made every call cost the size of the
	 * program.
	 * @param name the global's name
	 * @return {@code true} when its assignments reach the eval runtime
	 */
	public static boolean mirrorsIntoEval(String name) {
		return !COMPILER_CHANNELS.contains(name);
	}

	/**
	 * Returns the ordered set of top-level global variable names declared by the given
	 * top-level forms. Order is deterministic (declaration order) so backing-store
	 * indices are stable.
	 * @param topLevelExprs the top-level forms (excluding {@code defun}, which defines a
	 * function, not a variable)
	 * @return the global variable names in declaration order
	 */
	public static LinkedHashSet<String> collect(List<LispVal> topLevelExprs) {
		LinkedHashSet<String> globals = new LinkedHashSet<>();
		for (LispVal expr : topLevelExprs) {
			if (!(expr instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
				continue;
			}
			List<LispVal> parts = cons.toList();
			switch (head.name()) {
				case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> {
					if (parts.size() >= 2 && parts.get(1) instanceof LispSymbol name) {
						globals.add(name.name());
					}
				}
				case LispNames.SETQ, LispNames.SETF -> {
					// Record each place that is a bare symbol (a symbol place under setf
					// expands to setq).
					assignedPlaces(head.name(), cons, globals::add);
					// The values may hold further assignments -- a namespace init is a
					// top-level setq of a lambda over the namespace's setqs -- and those
					// assign the same globals a head-position setq would (nested in a
					// top-level non-defun form), so they get the same walk as below.
					collectNestedDefunNames(cons, globals);
					collectNestedAssignedNames(cons, globals);
				}
				default -> {
					// A defun nested inside a top-level non-defun form (the CL
					// closure-over-let idiom) compiles to (setq name (lambda ...)), so
					// the name needs the same global backing store; call sites then
					// dispatch through the variable (expandCallThroughFunctionValue).
					collectNestedDefunNames(cons, globals);
					// An assignment NESTED in a top-level form -- (print (progn (setq a
					// 10)
					// a)) -- assigns the same top-level variable a head-position setq
					// would,
					// and every backend already lets a later top-level form read it back.
					// Without a global it is backed by a local of the enclosing top-level
					// function instead, which pins the whole top level into that one
					// function: an outlined chunk cannot see another chunk's locals
					// (WasmToplevelEmit, .kb/wasm-function-body-size.md).
					collectNestedAssignedNames(cons, globals);
				}
			}
		}
		return globals;
	}

	/**
	 * The globals a literal {@code (boundp 'G)} in a program WITHOUT the eval mirror
	 * reads off their own variable: no definer gives them a value
	 * ({@link SpecialVarCollector#collectLiterallyProbedValueless}), so the compile paths
	 * seed the variable with an UNBOUND marker the first store overwrites. With the
	 * mirror the probe asks it instead, and in a program calling {@code progv}, which can
	 * bind a name no special declaration covers and reaches the mirror for it, none is
	 * answered this way.
	 * @param forms the program's forms, the injected runtime's and the condition reports'
	 * included
	 * @param program the program's forms
	 * @param globals the program's globals
	 * @return those globals, in {@code globals} order
	 */
	public static LinkedHashSet<String> collectProbedUnbound(Collection<LispVal> forms, List<LispVal> program,
			SequencedSet<String> globals) {
		if (LispMacroExpander.programCallsProgv(program)) {
			return new LinkedHashSet<>();
		}
		return SpecialVarCollector.collectLiterallyProbedValueless(forms, globals);
	}

	/**
	 * The globals whose variable the eval gate may count on to answer a literal
	 * {@code boundp}, read off the program BEFORE the compile path injects its runtime
	 * and collects its globals: the dynamically bound specials
	 * {@link SpecialVarCollector#collectProbedValuelessBound} tracks, and
	 * {@link #collectProbedUnbound} over the globals the program certainly has -- its
	 * specials, the names {@link #collect} reads off its top-level forms, a nested
	 * {@code defun}, a free assignment ({@link #collectFreeAssigned}). A subset of what
	 * the compilers carry once their globals are final, so the gate never drops a mirror
	 * a probe needs (they check, {@code LispMacroExpander.requireBoundpOffMirror}).
	 * @param program the program's forms
	 * @param reports the condition {@code :report} lambdas, probed like the program
	 * @param specials the program's special-variable names
	 * @param everyBound whether every special is runtime-bindable by name (the JVM's
	 * {@code make-thread} hand-over)
	 * @return those globals
	 */
	public static LinkedHashSet<String> collectProbedUnboundBeforeInjection(List<LispVal> program,
			Collection<LispVal> reports, SequencedSet<String> specials, boolean everyBound) {
		LinkedHashSet<String> tracked = SpecialVarCollector.collectProbedValuelessBound(program, reports, specials,
				everyBound);
		List<LispVal> topLevel = new ArrayList<>();
		for (LispVal expr : program) {
			if (!(expr instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& LispNames.DEFUN.equals(head.name()))) {
				topLevel.add(expr);
			}
		}
		LinkedHashSet<String> globals = new LinkedHashSet<>(specials);
		globals.addAll(collect(topLevel));
		globals.addAll(collectNestedInDefunBodies(program));
		globals.addAll(collectFreeAssigned(program));
		List<LispVal> probed = new ArrayList<>(program);
		probed.addAll(reports);
		tracked.addAll(collectProbedUnbound(probed, program, globals));
		return tracked;
	}

	/**
	 * Returns the names of {@code defun}s nested inside a top-level {@code defun}'s BODY,
	 * in program order. The companion of the nested-defun branch of {@link #collect}: a
	 * top-level {@code defun} is removed from the top-level forms before that runs (it
	 * declares a function, not a variable), so its body is the one place a nested defun
	 * could hide from the collector.
	 * <p>
	 * A nested {@code defun} lowers to {@code (setq name (lambda ...))} on both compile
	 * backends whatever encloses it, so the name needs the same global backing store in a
	 * function body as under a top-level {@code let} -- call sites dispatch through the
	 * variable ({@code LispMacroExpander.expandCallThroughFunctionValue}). Without it the
	 * call compiled to the generic undefined-function error while the interpreter (and
	 * SBCL) answered. The definition still does not exist until the enclosing function is
	 * CALLED, and calling it twice rebinds the name; that is the shape's semantics, not a
	 * limitation of the store.
	 * @param program the whole program, top-level {@code defun}s included
	 * @return the nested defun names in program order
	 */
	public static LinkedHashSet<String> collectNestedInDefunBodies(List<LispVal> program) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		for (LispVal expr : program) {
			if (!(expr instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)
					|| !LispNames.DEFUN.equals(head.name())) {
				continue;
			}
			// Skip the head, the defun's own name and its lambda list; walk the body.
			if (cons.cdr() instanceof LispCons nameCell && nameCell.cdr() instanceof LispCons paramsCell) {
				collectNestedDefunNames(paramsCell.cdr(), names);
			}
		}
		return names;
	}

	/**
	 * Returns the names the program assigns -- {@code setq}, {@code setf}, {@code psetq},
	 * {@code psetf} or {@code multiple-value-setq} of a bare symbol, in a top-level
	 * {@code defun}'s body or in a top-level form -- where no lexical binding of the name
	 * is in scope, in program order. The body's (or form's) lambdas, local functions and
	 * nested {@code defun}s count; the function's own parameters, and every {@code let}
	 * or parameter between the top level and the assignment, shadow it.
	 * <p>
	 * Assigning an undeclared variable is undefined in CL; SBCL warns and assigns the
	 * global, and so does the interpreter, so such a name needs the backing store a
	 * top-level {@code setq}'s gets. Without it the assignment compiled into a local of
	 * the enclosing function, and a read anywhere else was refused. Unlike
	 * {@link #collect} this walk is scope-aware ({@link FreeVarAnalyzer#findFreeVars}):
	 * almost every {@code setq} in a function body assigns a {@code let} variable or a
	 * parameter, and almost every top-level {@code psetq} or {@code multiple-value-setq}
	 * a {@code do} loop's or a {@code let}'s, and a store for each would grow every
	 * program. A top-level {@code setq} or {@code setf} is mostly {@code collect}'s
	 * already; this walk adds the one in a definer's initform, which {@code collect} does
	 * not enter. A name the scope both reads free and assigns under a binding still
	 * counts -- the read had no other store to reach.
	 * @param program the whole program, top-level {@code defun}s included
	 * @return the names in program order; empty for a program that assigns only lexical
	 * variables
	 */
	public static LinkedHashSet<String> collectFreeAssigned(List<LispVal> program) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		for (LispVal expr : program) {
			LinkedHashSet<String> assigned = new LinkedHashSet<>();
			LispVal lambdaListAndBody;
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& LispNames.DEFUN.equals(head.name())) {
				if (!(cons.cdr() instanceof LispCons nameCell)
						|| !(nameCell.cdr() instanceof LispCons lambdaListCell)) {
					continue;
				}
				collectAssignedPlaces(lambdaListCell.cdr(), assigned);
				// The defun as the lambda it is: the walk binds a lambda's parameters.
				lambdaListAndBody = lambdaListCell;
			}
			else {
				collectAssignedPlaces(expr, assigned);
				// The form as the body of a lambda of no parameters.
				lambdaListAndBody = new LispCons(LispNil.INSTANCE, new LispCons(expr, LispNil.INSTANCE));
			}
			// The standard stream variables and the multiple-value channel are never a
			// collected global: the backends give them their own representation
			// (FreeVarAnalyzer's SPECIAL_NAMES).
			assigned.remove(LispNames.STANDARD_OUTPUT_VAR);
			assigned.remove(LispNames.STANDARD_INPUT_VAR);
			assigned.remove(LispNames.ERROR_OUTPUT_VAR);
			assigned.remove(LispNames.MV_SPILL);
			if (assigned.isEmpty()) {
				continue;
			}
			// Its nested defuns read as lambdas too: the walk skips a defun.
			LispVal lambda = new LispCons(new LispSymbol(LispNames.LAMBDA), nestedDefunsAsLambdas(lambdaListAndBody));
			// The assigned names go in as enclosing lexicals only to lift the walk's
			// exclusion of built-in names: (setq list 1) assigns a variable too.
			for (String free : FreeVarAnalyzer.findFreeVars(List.of(lambda), Set.of(), Set.of(), Set.of(), assigned)) {
				if (assigned.contains(free)) {
					names.add(free);
				}
			}
		}
		return names;
	}

	/**
	 * Whether {@code op} heads a form that assigns bare-symbol places
	 * ({@link #assignedPlaces}).
	 */
	public static boolean isAssignmentHead(@Nullable String op) {
		return LispNames.SETQ.equals(op) || LispNames.SETF.equals(op) || LispNames.PSETQ.equals(op)
				|| LispNames.PSETF.equals(op) || LispNames.MULTIPLE_VALUE_SETQ.equals(op);
	}

	/**
	 * Hands {@code sink} each bare symbol the assignment form {@code form}, headed by
	 * {@code op}, stores to: the places of {@code setq}, {@code setf}, {@code psetq} and
	 * {@code psetf}, the variables of {@code multiple-value-setq}. The one recognition of
	 * what assigns a variable, shared by every walk that asks which names a program
	 * stores. Keywords and non-symbol places ({@code (setf (car x) 1)}) are skipped, and
	 * a form of any other head, or a malformed one, yields what it has.
	 * @param op the head's name, as the caller reads it
	 * @param form the whole form
	 * @param sink receives each assigned name, in order
	 */
	public static void assignedPlaces(String op, LispCons form, Consumer<String> sink) {
		switch (op) {
			case LispNames.SETQ, LispNames.SETF, LispNames.PSETQ, LispNames.PSETF -> {
				// Place/value pairs: every other element, starting at the first.
				LispVal node = form.cdr();
				while (node instanceof LispCons placeCell && placeCell.cdr() instanceof LispCons valueCell) {
					if (placeCell.car() instanceof LispSymbol place && !place.isKeyword()) {
						sink.accept(place.name());
					}
					node = valueCell.cdr();
				}
			}
			case LispNames.MULTIPLE_VALUE_SETQ -> {
				if (form.cdr() instanceof LispCons varsCell) {
					LispVal vars = varsCell.car();
					while (vars instanceof LispCons varCons) {
						if (varCons.car() instanceof LispSymbol place && !place.isKeyword()) {
							sink.accept(place.name());
						}
						vars = varCons.cdr();
					}
				}
			}
			default -> {
			}
		}
	}

	/**
	 * Every bare-symbol place an assignment form names at any depth of {@code form},
	 * excluding quoted data and keywords. Scope-blind: the caller decides which are free.
	 */
	private static void collectAssignedPlaces(LispVal form, Set<String> names) {
		LispVal node = form;
		while (node instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head) {
				switch (head.name()) {
					case LispNames.QUOTE -> {
						return;
					}
					case LispNames.SETQ, LispNames.SETF, LispNames.PSETQ, LispNames.PSETF,
							LispNames.MULTIPLE_VALUE_SETQ ->
						assignedPlaces(head.name(), cons, names::add);
					default -> {
					}
				}
			}
			collectAssignedPlaces(cons.car(), names);
			node = cons.cdr();
		}
	}

	/**
	 * {@code form} with every {@code (defun name lambda-list . body)} inside it read as
	 * {@code (lambda lambda-list . body)}, for the free-variable walk only: a nested
	 * defun is a closure over the bindings around it. Answers {@code form} itself when it
	 * holds no defun, so the common body allocates nothing; a rewritten path is a copy
	 * the program never sees.
	 */
	static LispVal nestedDefunsAsLambdas(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol head) {
			if (LispNames.QUOTE.equals(head.name())) {
				return form;
			}
			if (LispNames.DEFUN.equals(head.name()) && cons.cdr() instanceof LispCons nameCell
					&& nameCell.cdr() instanceof LispCons lambdaListCell) {
				return new LispCons(new LispSymbol(LispNames.LAMBDA), nestedDefunsAsLambdas(lambdaListCell));
			}
		}
		// The element list exists only once an element changed.
		@Nullable List<LispVal> elements = null;
		LispVal node = cons;
		while (node instanceof LispCons cell) {
			LispVal element = nestedDefunsAsLambdas(cell.car());
			if (elements == null && element != cell.car()) {
				elements = new ArrayList<>();
				for (LispVal seen = cons; seen != cell; seen = ((LispCons) seen).cdr()) {
					elements.add(((LispCons) seen).car());
				}
			}
			if (elements != null) {
				elements.add(element);
			}
			node = cell.cdr();
		}
		if (elements == null) {
			return form;
		}
		LispVal rebuilt = node;
		for (int i = elements.size() - 1; i >= 0; i--) {
			rebuilt = new LispCons(elements.get(i), rebuilt);
		}
		return rebuilt;
	}

	/**
	 * Returns the names of every NON-top-level {@code defun} in the program, in program
	 * order -- the union of the two spellings the collectors above cover separately (a
	 * defun nested in a top-level non-defun form, and one nested in a top-level defun's
	 * body). Every one of these names is a global variable holding a closure rather than
	 * a compiled function, which is what a call site has to know to dispatch through the
	 * variable ({@code NestedDefunRedefinition}, and the late-binding order in the
	 * backends' call compilers).
	 * @param program the whole program, top-level {@code defun}s included
	 * @return the non-top-level defun names in program order
	 */
	public static LinkedHashSet<String> collectAllNestedDefunNames(List<LispVal> program) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		for (LispVal expr : program) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& LispNames.DEFUN.equals(head.name())) {
				// A top-level defun is not itself nested; its BODY is where one hides.
				if (cons.cdr() instanceof LispCons nameCell && nameCell.cdr() instanceof LispCons paramsCell) {
					collectNestedDefunNames(paramsCell.cdr(), names);
				}
			}
			else {
				collectNestedDefunNames(expr, names);
			}
		}
		return names;
	}

	private static void collectNestedDefunNames(LispVal form, Set<String> globals) {
		LispVal node = form;
		while (node instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head) {
				if (LispNames.QUOTE.equals(head.name())) {
					return;
				}
				if (LispNames.DEFUN.equals(head.name()) && cons.cdr() instanceof LispCons nameCell
						&& nameCell.car() instanceof LispSymbol name) {
					globals.add(name.name());
				}
			}
			collectNestedDefunNames(cons.car(), globals);
			node = cons.cdr();
		}
	}

	/**
	 * Records every {@code setq}/{@code setf} bare-symbol place and
	 * {@code defvar}/{@code defparameter}/{@code defconstant} name found at any depth
	 * inside a top-level form, excluding quoted data.
	 * <p>
	 * Deliberately blind to lexical scope: a name that is only ever a {@code let}
	 * variable also gets a backing store it never uses, because every assignment site
	 * resolves a lexical slot before it looks at the global. Over-collecting costs an
	 * unused store; missing a name costs a variable that a later top-level form cannot
	 * read.
	 */
	private static void collectNestedAssignedNames(LispVal form, Set<String> globals) {
		LispVal node = form;
		while (node instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head) {
				if (LispNames.QUOTE.equals(head.name())) {
					return;
				}
				switch (head.name()) {
					case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> {
						List<LispVal> parts = cons.toList();
						if (parts.size() >= 2 && parts.get(1) instanceof LispSymbol name) {
							globals.add(name.name());
						}
					}
					case LispNames.SETQ, LispNames.SETF -> assignedPlaces(head.name(), cons, globals::add);
					default -> {
					}
				}
			}
			collectNestedAssignedNames(cons.car(), globals);
			node = cons.cdr();
		}
	}

}
