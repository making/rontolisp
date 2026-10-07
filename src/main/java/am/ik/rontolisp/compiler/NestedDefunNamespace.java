package am.ik.rontolisp.compiler;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * The functions a {@code defun} below the top level defines whose home is the run-time
 * function namespace ({@code _fenv} on the JVM, {@code GLOBAL_FENV} on WASM) rather than
 * the global variable such a definition otherwise assigns.
 *
 * <p>
 * The global serves every reference the compile can see -- a call, {@code #'name}, a
 * literal {@code fboundp} -- but a name resolved at run time ({@code (funcall (intern
 * "F"))}, a computed {@code fboundp} / {@code symbol-function} / {@code fdefinition},
 * {@code eval}) looks in the namespace and the compiled-function registry, which never
 * learn it, and {@code fmakunbound} leaves its tombstone in the namespace, where a call
 * through the global never looks. So where a run-time name can reach the function, the
 * definition installs it in the namespace instead ({@code %set-symbol-function}) and
 * every reference reads it from there ({@code %fenv-function}), as for a name only
 * {@code (setf (symbol-function 'n) ...)} binds: one home, which {@code fmakunbound}
 * retires and the next definition restores, as the interpreter's global definition is.
 *
 * <p>
 * A run-time name can reach the function when the program evaluates data
 * ({@link RuntimeNameProducers#anyNameResolvable}), walks a package
 * ({@link RuntimeNameProducers#packageWalkSpellings}), or holds one of the function's
 * {@link DesignatorSpellings} as a value: a quoted symbol (a {@code (setf name)}
 * function's place too) -- except the first argument of {@code fboundp},
 * {@code symbol-function}, {@code fdefinition}, {@code funcall} and {@code apply}, which
 * the backends resolve at compile time, unless it is a {@code setf} place that writes the
 * namespace -- and, beside a symbol builder, a string, keyword or uninterned spelling. A
 * name assembled out of computed pieces is the registry's own carve-out
 * ({@link RuntimeNameProducers}). Every other program keeps the global.
 */
public final class NestedDefunNamespace {

	/**
	 * The operators whose literal quoted first argument the backends resolve statically.
	 */
	private static final Set<String> STATIC_DESIGNATOR_OPERATORS = Set.of(LispNames.FBOUNDP, LispNames.SYMBOL_FUNCTION,
			LispNames.FDEFINITION, LispNames.FUNCALL, LispNames.APPLY);

	private NestedDefunNamespace() {
	}

	/**
	 * The names of the functions a {@code defun} below the top level defines that a
	 * run-time name can reach -- those whose definition writes the function namespace.
	 * @param program the program after {@code expandTopLevelDefinitions} (a nested
	 * {@code (defun (setf n) ...)} already renamed to its writer's name)
	 * @return the names, empty when the program has no such function
	 */
	public static Set<String> heldNames(List<LispVal> program) {
		Set<String> nested = GlobalVarCollector.collectAllNestedDefunNames(program);
		// A method body a defmethod below the top level defines is no function of the
		// program's: the generic's dispatcher reads its global (and tests it for the
		// method's having been added), and no name of the program's spells it.
		nested.removeIf(name -> LispMacroExpander.genericOfMethodFunction(name) != null);
		if (nested.isEmpty()) {
			return Set.of();
		}
		if (RuntimeNameProducers.anyNameResolvable(program)) {
			return nested;
		}
		Set<String> spelled = new HashSet<>(RuntimeNameProducers.packageWalkSpellings(program));
		for (LispVal form : program) {
			collectSpellings(form, spelled);
		}
		boolean symbolBuilders = RuntimeNameProducers.anySymbolBuilder(program);
		Set<String> held = new LinkedHashSet<>();
		for (String name : nested) {
			if (DesignatorSpellings.anySpelled(name, spelled, symbolBuilders, true)) {
				held.add(name);
			}
		}
		return held;
	}

	/**
	 * What a held nested {@code defun} compiles to in place of the assignment of its
	 * global: {@code (%set-symbol-function 'name (lambda ...))}, the same lambda (the
	 * uncaught report keys its name on it).
	 * @param nested the lowered definition
	 * @return the installation form
	 */
	public static LispVal install(UncaughtReport.NestedDefun nested) {
		LispVal quotedName = new LispCons(new LispSymbol(LispNames.QUOTE),
				new LispCons(new LispSymbol(nested.name()), LispNil.INSTANCE));
		return new LispCons(new LispSymbol(LispNames.SET_SYMBOL_FUNCTION_INTERNAL),
				new LispCons(quotedName, new LispCons(nested.lambda(), LispNil.INSTANCE)));
	}

	/**
	 * The spellings {@code form} holds as run-time values: the symbols of quoted data,
	 * and every string, keyword and uninterned symbol (a string is spelled framed in
	 * quotes, as {@link DesignatorSpellings} probes it).
	 */
	private static void collectSpellings(LispVal form, Set<String> spelled) {
		if (form instanceof LispString string) {
			spelled.add("\"" + string.value() + "\"");
			return;
		}
		if (form instanceof LispArray) {
			collectDatum(form, spelled);
			return;
		}
		if (form instanceof LispSymbol symbol) {
			if (symbol.name().startsWith(":") || symbol.name().startsWith("#:")) {
				spelled.add(symbol.name());
			}
			return;
		}
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head) {
			if (LispNames.QUOTE.equals(head.name())) {
				collectDatum(cons.cdr(), spelled);
				return;
			}
			if (LispNames.FUNCTION.equals(head.name())) {
				return;
			}
			if (STATIC_DESIGNATOR_OPERATORS.contains(head.name()) && cons.cdr() instanceof LispCons first
					&& isQuotedSymbol(first.car())) {
				collectRest(first.cdr(), spelled);
				return;
			}
			if (LispNames.SETF.equals(head.name()) || LispNames.PSETF.equals(head.name())) {
				// A symbol-function / fdefinition place WRITES the namespace by name.
				collectPlaceNames(cons.cdr(), spelled);
			}
		}
		collectRest(cons, spelled);
	}

	private static void collectRest(LispVal list, Set<String> spelled) {
		LispVal node = list;
		while (node instanceof LispCons cell) {
			collectSpellings(cell.car(), spelled);
			node = cell.cdr();
		}
	}

	private static void collectPlaceNames(LispVal pairs, Set<String> spelled) {
		LispVal node = pairs;
		while (node instanceof LispCons pair) {
			if (pair.car() instanceof LispCons place && place.car() instanceof LispSymbol accessor
					&& (LispNames.SYMBOL_FUNCTION.equals(accessor.name())
							|| LispNames.FDEFINITION.equals(accessor.name()))
					&& place.cdr() instanceof LispCons nameCell) {
				collectSpellings(nameCell.car(), spelled);
			}
			node = pair.cdr() instanceof LispCons value ? value.cdr() : null;
		}
	}

	private static void collectDatum(LispVal datum, Set<String> spelled) {
		LispVal node = datum;
		while (node instanceof LispCons cons) {
			collectDatum(cons.car(), spelled);
			node = cons.cdr();
		}
		if (node instanceof LispSymbol symbol) {
			spelled.add(symbol.name());
		}
		else if (node instanceof LispString string) {
			spelled.add("\"" + string.value() + "\"");
		}
		else if (node instanceof LispArray array) {
			for (LispVal element : array.data()) {
				collectDatum(element, spelled);
			}
		}
	}

	private static boolean isQuotedSymbol(LispVal arg) {
		return arg instanceof LispCons quote && quote.car() instanceof LispSymbol head
				&& LispNames.QUOTE.equals(head.name()) && quote.cdr() instanceof LispCons datum
				&& datum.car() instanceof LispSymbol;
	}

}
