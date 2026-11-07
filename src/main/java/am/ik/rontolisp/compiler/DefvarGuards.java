package am.ik.rontolisp.compiler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

/**
 * The {@code defvar}s whose "bind only if unbound" the compile paths test at run time.
 *
 * <p>
 * Both backends decide a {@code defvar}'s store at compile time: the first top-level
 * {@code defvar} of a name stores, a later one -- or one after a top-level
 * {@code defparameter} -- does not. That holds while nothing but those straight-line
 * top-level definers can have bound the name when the {@code defvar} runs. A name this
 * class answers instead starts as the UNBOUND marker (the compilers add it to their
 * unbound globals), and every {@code defvar} of it stores only while its variable -- the
 * active binding of the special -- holds the marker ({@link #guardedStore}). A program in
 * which no name qualifies compiles as before.
 *
 * <p>
 * A name with a {@code defvar} that has a value qualifies, unless it is a {@code cl}
 * symbol (the backends seed those), when:
 * <ul>
 * <li>one of its valued {@code defvar}s is not a top-level form -- in a function body, a
 * conditional, a loop, a binding form: it runs any number of times, in any order with the
 * others;</li>
 * <li>a {@code makunbound} quotes it, or one computes its argument: it can take the value
 * a {@code defvar} gave;</li>
 * <li>a top-level form before its first top-level {@code defvar} can store it: one that
 * holds an assignment, a modify macro or a definer of it at any depth (a top-level
 * {@code setq}, an assignment inside a lambda) -- a top-level {@code defparameter} /
 * {@code defconstant} of it aside, which the backends count as a binding -- or a store by
 * a computed name ({@code set}, a {@code (setf (symbol-value ...))}); a {@code defun}
 * counts so only when a form that can call it runs between it and that
 * {@code defvar}.</li>
 * </ul>
 * Over-collecting costs the marker test on the name's reads; missing a name stores a
 * {@code defvar}'s value over one the program already gave it.
 */
public final class DefvarGuards {

	private DefvarGuards() {
	}

	/**
	 * The guarded names of a program.
	 * @param program the program's forms, top-level {@code defun}s included, in source
	 * order, with top-level {@code progn}s spliced
	 * @return the names, in first-{@code defvar} order; empty for a program whose every
	 * {@code defvar} the compile-time decision answers
	 */
	public static LinkedHashSet<String> collect(List<LispVal> program) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		Set<String> nested = new HashSet<>();
		Map<String, Integer> firstTopLevel = new HashMap<>();
		for (int i = 0; i < program.size(); i++) {
			LispVal form = program.get(i);
			String own = valuedDefvarName(form);
			if (own != null) {
				names.add(own);
				firstTopLevel.putIfAbsent(own, i);
				collectNestedDefvars(((LispCons) ((LispCons) form).cdr()).cdr(), names, nested);
			}
			else {
				collectNestedDefvars(form, names, nested);
			}
		}
		names.removeIf(name -> PackageRegistry.isClSymbol(name) || PackageRegistry.isClSymbol(member(name)));
		if (names.isEmpty()) {
			return names;
		}
		Set<String> guarded = new HashSet<>(nested);
		// makunbound can take a value a defvar gave, between two of its defvars as
		// before the first: the name it quotes, or any name it computes.
		Set<String> unbound = new HashSet<>();
		for (LispVal form : program) {
			if (makunbounds(form, unbound)) {
				return names;
			}
		}
		guarded.addAll(unbound);
		guarded.retainAll(names);
		int[] nextRunning = new int[program.size()];
		int next = Integer.MAX_VALUE;
		for (int i = program.size() - 1; i >= 0; i--) {
			nextRunning[i] = next;
			if (runsCode(program.get(i))) {
				next = i;
			}
		}
		int last = 0;
		for (int index : firstTopLevel.values()) {
			last = Math.max(last, index);
		}
		for (int i = 0; i < last; i++) {
			LispVal form = program.get(i);
			boolean defun = isHead(form, LispNames.DEFUN);
			// The point from which this form's stores can have run: at once, or once a
			// form that can call the function runs.
			int from = defun ? nextRunning[i] : i;
			Set<String> stored = new HashSet<>();
			if (stores(form, names, stored)) {
				stored.addAll(names);
			}
			for (String name : stored) {
				Integer first = firstTopLevel.get(name);
				if (first != null && from < first) {
					guarded.add(name);
				}
			}
		}
		names.retainAll(guarded);
		return names;
	}

	/**
	 * The store a guarded {@code defvar} makes: {@code (if (%global-boundp 'name) nil
	 * (setq name init))}, positioned at the {@code defvar}.
	 * @param defvar the {@code (defvar name init ...)} form
	 * @return the expression
	 */
	public static LispVal guardedStore(LispCons defvar) {
		List<LispVal> parts = defvar.toList();
		LispVal name = parts.get(1);
		LispVal probe = list(new LispSymbol(LispNames.GLOBAL_BOUNDP), list(new LispSymbol(LispNames.QUOTE), name));
		LispVal store = list(new LispSymbol(LispNames.SETQ), name, parts.get(2));
		LispVal guarded = list(new LispSymbol(LispNames.IF), probe, LispNil.INSTANCE, store);
		SourceProvenance.inheritWhenCompiling(defvar, store);
		SourceProvenance.inheritWhenCompiling(defvar, guarded);
		return guarded;
	}

	private static LispVal list(LispVal... elements) {
		LispVal out = LispNil.INSTANCE;
		for (int i = elements.length - 1; i >= 0; i--) {
			out = new LispCons(elements[i], out);
		}
		return out;
	}

	/**
	 * Records the name every {@code (makunbound 'name)} in {@code form} quotes, quoted
	 * data skipped, and answers whether one computes its argument or {@code #'makunbound}
	 * is taken.
	 */
	private static boolean makunbounds(LispVal form, Set<String> out) {
		LispVal node = form;
		while (node instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head) {
				if (LispNames.isQuote(head.name())) {
					return false;
				}
				if (LispNames.MAKUNBOUND.equals(head.name()) && node == form) {
					if (cons.cdr() instanceof LispCons arg && isHead(arg.car(), LispNames.QUOTE)
							&& ((LispCons) arg.car()).cdr() instanceof LispCons quoted
							&& quoted.car() instanceof LispSymbol name) {
						out.add(name.name());
					}
					else {
						return true;
					}
				}
				if (LispNames.FUNCTION.equals(head.name()) && node == form && cons.cdr() instanceof LispCons fn
						&& fn.car() instanceof LispSymbol name && LispNames.MAKUNBOUND.equals(name.name())) {
					return true;
				}
			}
			if (makunbounds(cons.car(), out)) {
				return true;
			}
			node = cons.cdr();
		}
		return false;
	}

	/** The name of {@code (defvar name init ...)}, or null for any other form. */
	private static @Nullable String valuedDefvarName(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.DEFVAR.equals(head.name()) && cons.cdr() instanceof LispCons nameCell
				&& nameCell.car() instanceof LispSymbol name && nameCell.cdr() instanceof LispCons ? name.name() : null;
	}

	/**
	 * Records the name of every valued {@code defvar} at any depth of {@code form} into
	 * both sets, quoted data skipped.
	 */
	private static void collectNestedDefvars(LispVal form, Set<String> names, Set<String> nested) {
		LispVal node = form;
		while (node instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head && LispNames.isQuote(head.name())) {
				return;
			}
			String name = valuedDefvarName(cons.car());
			if (name != null) {
				names.add(name);
				nested.add(name);
			}
			collectNestedDefvars(cons.car(), names, nested);
			node = cons.cdr();
		}
	}

	/**
	 * Records into {@code out} the candidate names top-level {@code form} can store, its
	 * own definer's name aside, and answers whether it stores a variable by a computed
	 * name ({@code set}, a {@code symbol-value} place --
	 * {@link LispMacroExpander#programUsesSet}).
	 */
	private static boolean stores(LispVal form, Set<String> candidates, Set<String> out) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head) {
			switch (head.name()) {
				case LispNames.DECLAIM, LispNames.PROCLAIM -> {
					return false;
				}
				// The name of a top-level definer is no store to count: a valueless
				// defvar binds nothing, a defparameter or defconstant is a binding the
				// backends count. The values are code.
				case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> {
					if (cons.cdr() instanceof LispCons nameCell) {
						walkStores(nameCell.cdr(), candidates, out);
					}
					return LispMacroExpander.programUsesSet(List.of(form));
				}
				default -> {
				}
			}
		}
		walkStores(form, candidates, out);
		return LispMacroExpander.programUsesSet(List.of(form));
	}

	/**
	 * Records every candidate a form at any depth of {@code form} stores, quoted data
	 * skipped: the places of an assignment ({@link GlobalVarCollector#assignedPlaces}) or
	 * of a modify macro, and the name of a definer. Blind to scope, as an over-collection
	 * only costs a test.
	 */
	private static void walkStores(LispVal form, Set<String> candidates, Set<String> out) {
		LispVal node = form;
		while (node instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head) {
				String op = head.name();
				if (LispNames.isQuote(op)) {
					return;
				}
				storedPlaces(op, cons, name -> {
					if (candidates.contains(name)) {
						out.add(name);
					}
				});
			}
			walkStores(cons.car(), candidates, out);
			node = cons.cdr();
		}
	}

	private static void storedPlaces(String op, LispCons form, java.util.function.Consumer<String> sink) {
		if (GlobalVarCollector.isAssignmentHead(op)) {
			GlobalVarCollector.assignedPlaces(op, form, sink);
			// (setf (values a b) ...) stores each variable of the values place.
			if (LispNames.SETF.equals(op) || LispNames.PSETF.equals(op)) {
				for (LispVal node = form.cdr(); node instanceof LispCons cell; node = cell.cdr()) {
					if (isHead(cell.car(), LispNames.VALUES)) {
						symbolsOf(((LispCons) cell.car()).cdr(), sink);
					}
				}
			}
			return;
		}
		if (!(form.cdr() instanceof LispCons first)) {
			return;
		}
		switch (op) {
			case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT, LispNames.POP, LispNames.INCF,
					LispNames.DECF, LispNames.REMF ->
				symbolsOf(new LispCons(first.car(), LispNil.INSTANCE), sink);
			case LispNames.PUSH, LispNames.PUSHNEW -> {
				if (first.cdr() instanceof LispCons second) {
					symbolsOf(new LispCons(second.car(), LispNil.INSTANCE), sink);
				}
			}
			case LispNames.ROTATEF, LispNames.SHIFTF -> symbolsOf(first, sink);
			default -> {
			}
		}
	}

	/** Hands {@code sink} each non-keyword symbol element of a list. */
	private static void symbolsOf(LispVal list, java.util.function.Consumer<String> sink) {
		for (LispVal node = list; node instanceof LispCons cell; node = cell.cdr()) {
			if (cell.car() instanceof LispSymbol symbol && !symbol.isKeyword()) {
				sink.accept(symbol.name());
			}
		}
	}

	/**
	 * Whether a top-level form can call a function when it runs. A definition, a
	 * declaration and a definer whose value is a constant or a function cannot.
	 */
	private static boolean runsCode(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
			return false;
		}
		return switch (head.name()) {
			case LispNames.DEFUN, LispNames.DEFMACRO, LispNames.DECLAIM, LispNames.PROCLAIM, LispNames.IN_PACKAGE,
					LispNames.QUOTE, LispNames.FUNCTION, LispNames.LAMBDA ->
				false;
			case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> {
				if (!(cons.cdr() instanceof LispCons nameCell) || !(nameCell.cdr() instanceof LispCons valueCell)) {
					yield false;
				}
				LispVal value = valueCell.car();
				yield value instanceof LispCons valueForm && !(isHead(valueForm, LispNames.QUOTE)
						|| isHead(valueForm, LispNames.FUNCTION) || isHead(valueForm, LispNames.LAMBDA));
			}
			default -> true;
		};
	}

	private static boolean isHead(LispVal form, String op) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head && op.equals(head.name());
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

}
