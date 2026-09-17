package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * Keeps a {@code let} PARALLEL on the compile path: every init form is evaluated before
 * any variable is bound, so in {@code (let ((a b) (b a)) ...)} the second init reads the
 * OUTER {@code a}.
 *
 * <p>
 * Both backends' {@code let} compilers bind one variable at a time -- compile the init,
 * store it, register the name -- which is what lets each binding pick its own
 * representation (a raw long or double slot, a boxed cell, a dynamic binding) the moment
 * its init is known. That order is only observable when a LATER init refers to the name
 * of an EARLIER variable, and then it is wrong: the swap above answered {@code (2 2)},
 * and a closure in the later init was a compile error on the JVM ("closure over X whose
 * binding left it unboxed"). Such a {@code let} is rewritten here, at the entry of each
 * {@code let} compiler (the choke point: a {@code do}, a {@code multiple-value-bind} and
 * every other macro that expands to a {@code let} arrive there too), into
 *
 * <pre>
 * (let ((%let-init-0 init0) (%let-init-1 init1)) (let ((a %let-init-0) (b %let-init-1)) body...))
 * </pre>
 *
 * whose outer inits still run left to right and see only outer names. A {@code let}
 * without that hazard -- nearly every one -- is returned as the SAME object, so its
 * emitted bytes do not change and its bindings keep their typed slots.
 */
public final class ParallelLetStaging {

	private ParallelLetStaging() {
	}

	/**
	 * Stages the inits of a {@code let} in which a later init refers to an earlier
	 * variable's name.
	 * @param letForm the {@code (let bindings body...)} form
	 * @return the staged form, or {@code letForm} itself when no init can observe the
	 * binding order
	 */
	public static LispCons stage(LispCons letForm) {
		if (!(letForm.cdr() instanceof LispCons rest)
				|| !(LispMacroExpander.normalizeBindingList(rest.car()) instanceof LispCons bindingList)
				|| !bindingList.isProperList()) {
			return letForm;
		}
		List<LispVal> bindings = bindingList.toList();
		List<String> names = new ArrayList<>();
		List<LispVal> inits = new ArrayList<>();
		for (LispVal binding : bindings) {
			if (!(binding instanceof LispCons pair && pair.car() instanceof LispSymbol name
					&& pair.cdr() instanceof LispCons initCell)) {
				return letForm;
			}
			names.add(name.name());
			inits.add(initCell.car());
		}
		if (!laterInitReadsEarlierVariable(names, inits)) {
			return letForm;
		}
		List<LispVal> staged = new ArrayList<>();
		List<LispVal> rebound = new ArrayList<>();
		for (int i = 0; i < names.size(); i++) {
			LispVal init = inits.get(i);
			// A literal reads no variable: it binds directly and keeps its typed slot.
			if (init instanceof LispCons || init instanceof LispSymbol) {
				LispSymbol temporary = new LispSymbol("%LET-INIT-" + i);
				staged.add(list(List.of(temporary, init)));
				init = temporary;
			}
			rebound.add(list(List.of(new LispSymbol(names.get(i)), init)));
		}
		LispCons inner = new LispCons(new LispSymbol(LispNames.LET), new LispCons(list(rebound), rest.cdr()));
		LispCons outer = (LispCons) list(List.of(new LispSymbol(LispNames.LET), list(staged), inner));
		SourceProvenance.inherit(letForm, inner);
		return SourceProvenance.inherit(letForm, outer);
	}

	private static boolean laterInitReadsEarlierVariable(List<String> names, List<LispVal> inits) {
		for (int j = 1; j < inits.size(); j++) {
			LispVal init = inits.get(j);
			if (!(init instanceof LispCons) && !(init instanceof LispSymbol)) {
				continue;
			}
			for (int i = 0; i < j; i++) {
				// enclosingLexicals = the name: a bare symbol is a variable whatever
				// function, global or special shares its spelling (Lisp-2).
				Set<String> name = Set.of(names.get(i));
				if (mentions(init, names.get(i))
						&& FreeVarAnalyzer.findFreeVars(List.of(init), Set.of(), Set.of(), Set.of(), name)
							.contains(names.get(i))) {
					return true;
				}
			}
		}
		return false;
	}

	// The cheap filter in front of the free-variable walk, which expands macros: nearly
	// every let fails it, so the choke point costs a symbol scan and nothing else.
	private static boolean mentions(LispVal form, String name) {
		LispVal rest = form;
		while (rest instanceof LispCons cell) {
			if (mentions(cell.car(), name)) {
				return true;
			}
			rest = cell.cdr();
		}
		return rest instanceof LispSymbol symbol && symbol.name().equals(name);
	}

	private static LispVal list(List<LispVal> elements) {
		LispVal list = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			list = new LispCons(elements.get(i), list);
		}
		return list;
	}

}
