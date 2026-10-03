package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;

/**
 * The arms of a lowered program and of the run-time library ({@code clojure.lisp}) for a
 * kind of value only some programs can make, and their strip. Every verb that must
 * understand such a value carries an arm for it: a {@code cond} clause, an {@code if} or
 * a disjunct of an {@code or} whose test is one of the family's {@link Family#tests}, or
 * a view from its {@link Family#views} wrapping a form the verb already had, answering
 * that form's value for anything that is no such value. A program that cannot make one --
 * it names none of the family's {@link Family#producers}, the only way one comes to exist
 * -- gets every arm of the family stripped before the library splice: a test folds to
 * false (its clause, its {@code if} branch or its disjunct goes), a view to its first
 * argument, and an {@link Family#aliases} helper to the plain one it stands for. What is
 * left is exactly the form the verb lowered to before the kind existed, so such a program
 * compiles to the same bytes, and the kind's runtime is pruned with nothing left to reach
 * it.
 *
 * <p>
 * The shape every arm keeps, which {@link #strip} checks: a test's arguments, and a
 * view's arguments past the first, are variables or {@code car}/{@code cdr} reads of one
 * (they are dropped, so they must have no effect), and a test stands only where it folds
 * -- a {@code cond} clause's test, an {@code if}'s test, an {@code or}'s disjunct. The
 * interpreter and a session keep the arms: what a later input builds is unknown there.
 */
public final class ClojureArms {

	private ClojureArms() {
	}

	/**
	 * A kind of value with arms: its tests, views and aliases, and what makes one.
	 */
	public enum Family {

		/**
		 * Sorted maps and sets: no literal makes one, only the constructor and the
		 * constructors' values.
		 */
		SORTED("sorted-collection",
				Set.of("RONTOLISP::%CLOJURE-SORTED-P", "RONTOLISP::%CLOJURE-SORTED-MAP-P",
						"RONTOLISP::%CLOJURE-SORTED-SET-P"),
				Set.of("RONTOLISP::%CLOJURE-SORTED-KEY", "RONTOLISP::%CLOJURE-SORTED-ITEMS",
						"RONTOLISP::%CLOJURE-SORTED-HASHED", "RONTOLISP::%CLOJURE-SORTED-SHRUNK",
						"RONTOLISP::%CLOJURE-SORTED-REWRAP"),
				Map.of("RONTOLISP::%CLOJURE-IS-SET", "RONTOLISP::%CLOJURE-SET-P", "RONTOLISP::%CLOJURE-IS-REVERSIBLE",
						"RONTOLISP::%CLOJURE-IS-VECTOR"),
				Set.of("RONTOLISP::%CLOJURE-SORTED-MAKE", "RONTOLISP::%CLOJURE-SORTED-MAP-V",
						"RONTOLISP::%CLOJURE-SORTED-MAP-BY-V", "RONTOLISP::%CLOJURE-SORTED-SET-V",
						"RONTOLISP::%CLOJURE-SORTED-SET-BY-V")),

		/**
		 * The unbound root of a declared-never-defined name or a value-less {@code def}:
		 * only the store a file starts with (or a session's declare) makes one.
		 */
		UNBOUND("unbound-root", Set.of("RONTOLISP::%CLOJURE-UNBOUND-P"), Set.of(), Map.of(),
				Set.of("RONTOLISP::%CLOJURE-UNBOUND"));

		private final String label;

		/** The arm tests. */
		final Set<String> tests;

		/** The views, each answering its first argument for a value of no such kind. */
		final Set<String> views;

		/** The kind-aware helpers, to the plain one each stands for without the kind. */
		final Map<String, String> aliases;

		/** What makes a value of the kind. */
		final Set<String> producers;

		Family(String label, Set<String> tests, Set<String> views, Map<String, String> aliases, Set<String> producers) {
			this.label = label;
			this.tests = tests;
			this.views = views;
			this.aliases = aliases;
			this.producers = producers;
		}

	}

	/** A folded test while the walk runs: never a form of its own. */
	private static final LispVal FALSE = new LispSymbol("%ARM-FOLDED%");

	/**
	 * What a program says about one family.
	 *
	 * @param builds whether it names a producer, so a value of the kind may exist
	 * @param arms whether it carries an arm the strip would fold
	 */
	public record Scan(boolean builds, boolean arms) {

		/**
		 * Whether the program's arms go: it carries one and builds no sorted collection.
		 * @return {@code true} when {@link #strip} applies
		 */
		public boolean strips() {
			return this.arms && !this.builds;
		}

	}

	/**
	 * Scans the forms for a producer and for an arm of the family, in one walk.
	 * @param forms the top-level forms
	 * @param family the kind
	 * @return what they say
	 */
	public static Scan scan(List<LispVal> forms, Family family) {
		boolean[] found = new boolean[2];
		for (LispVal form : forms) {
			scanInto(form, family, found);
			if (found[0] && found[1]) {
				break;
			}
		}
		return new Scan(found[0], found[1]);
	}

	private static void scanInto(LispVal form, Family family, boolean[] found) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			scanInto(cons.car(), family, found);
			rest = cons.cdr();
		}
		if (rest instanceof LispSymbol symbol) {
			String name = symbol.name();
			if (family.producers.contains(name)) {
				found[0] = true;
			}
			else if (family.tests.contains(name) || family.views.contains(name) || family.aliases.containsKey(name)) {
				found[1] = true;
			}
		}
	}

	/**
	 * The forms with every arm of the family folded away: what they lower to when no
	 * value of the kind can exist. A form with no arm is answered itself (cons identity
	 * kept, the source-position rule); an arm in a shape the fold cannot take signals,
	 * since leaving it would splice the kind's runtime after all.
	 * @param forms the top-level forms
	 * @param family the kind
	 * @return the forms without their arms
	 */
	public static List<LispVal> strip(List<LispVal> forms, Family family) {
		Stripper stripper = new Stripper(family);
		List<LispVal> out = new ArrayList<>(forms.size());
		boolean changed = false;
		for (LispVal form : forms) {
			LispVal walked = stripper.walkCode(form);
			changed |= walked != form;
			out.add(walked);
		}
		return changed ? out : forms;
	}

	/** One family's fold over code. */
	private record Stripper(Family family) {

		private LispVal walkCode(LispVal form) {
			LispVal walked = walk(form);
			if (walked == FALSE) {
				throw new IllegalStateException(
						"a " + this.family.label + " test where it cannot fold: " + form.print());
			}
			return walked;
		}

		private LispVal walk(LispVal form) {
			if (!(form instanceof LispCons cons)) {
				return form;
			}
			if (cons.car() instanceof LispSymbol head) {
				String name = head.name();
				if (name.equals("QUOTE")) {
					return form;
				}
				if (this.family.tests.contains(name)) {
					requirePure(cons.cdr(), form);
					return FALSE;
				}
				if (this.family.views.contains(name)) {
					if (!(cons.cdr() instanceof LispCons args)) {
						throw new IllegalStateException(
								"a " + this.family.label + " view without an argument: " + form.print());
					}
					requirePure(args.cdr(), form);
					return walkCode(args.car());
				}
				String alias = this.family.aliases.get(name);
				if (alias != null) {
					return LispCons.rebuilt(cons, new LispSymbol(alias), walkElements(cons.cdr()));
				}
				if (name.equals("FUNCTION") && cons.cdr() instanceof LispCons fn
						&& fn.car() instanceof LispSymbol target && this.family.aliases.containsKey(target.name())) {
					return LispCons.rebuilt(cons, head,
							LispCons.rebuilt(fn, new LispSymbol(this.family.aliases.get(target.name())), fn.cdr()));
				}
				switch (name) {
					case "OR":
						return walkOr(cons);
					case "COND":
						return walkCond(cons);
					case "IF":
						return walkIf(cons);
					default:
						break;
				}
			}
			return LispCons.rebuilt(cons, walkCode(cons.car()), walkElements(cons.cdr()));
		}

		/**
		 * The rest of a list, every element walked as code, down the cdr in a loop so a
		 * long list costs no stack; a dotted tail is kept as it is.
		 */
		private LispVal walkElements(LispVal rest) {
			List<LispCons> cells = new ArrayList<>();
			LispVal run = rest;
			while (run instanceof LispCons cell) {
				cells.add(cell);
				run = cell.cdr();
			}
			LispVal tail = run;
			for (int i = cells.size() - 1; i >= 0; i--) {
				LispCons cell = cells.get(i);
				tail = LispCons.rebuilt(cell, walkCode(cell.car()), tail);
			}
			return tail;
		}

		/**
		 * {@code (or ...)}: a folded disjunct goes; one left stands alone, none is false.
		 */
		private LispVal walkOr(LispCons form) {
			List<LispVal> elements = new ArrayList<>();
			elements.add(form.car());
			boolean folded = false;
			LispVal run = form.cdr();
			while (run instanceof LispCons cell) {
				LispVal walked = walk(cell.car());
				if (walked == FALSE) {
					folded = true;
				}
				else {
					elements.add(walked);
				}
				run = cell.cdr();
			}
			if (folded && elements.size() == 1) {
				return FALSE;
			}
			if (folded && elements.size() == 2) {
				return elements.get(1);
			}
			return LispCons.rebuiltList(form, elements);
		}

		/** {@code (cond ...)}: a clause whose test folds goes. */
		private LispVal walkCond(LispCons form) {
			List<LispVal> clauses = new ArrayList<>();
			clauses.add(form.car());
			LispVal run = form.cdr();
			while (run instanceof LispCons cell) {
				LispVal clause = cell.car();
				if (clause instanceof LispCons parts) {
					LispVal test = walk(parts.car());
					if (test != FALSE) {
						clauses.add(LispCons.rebuilt(parts, test, walkElements(parts.cdr())));
					}
				}
				else {
					clauses.add(clause);
				}
				run = cell.cdr();
			}
			return LispCons.rebuiltList(form, clauses);
		}

		/** {@code (if test then else)}: a folded test leaves the else form, or nil. */
		private LispVal walkIf(LispCons form) {
			if (!(form.cdr() instanceof LispCons testCell)) {
				return form;
			}
			LispVal test = walk(testCell.car());
			if (test != FALSE) {
				return LispCons.rebuilt(form, form.car(),
						LispCons.rebuilt(testCell, test, walkElements(testCell.cdr())));
			}
			if (testCell.cdr() instanceof LispCons thenCell && thenCell.cdr() instanceof LispCons elseCell) {
				return walkCode(elseCell.car());
			}
			return LispNil.INSTANCE;
		}

		/**
		 * Refuses an argument a fold would drop with an effect: anything but a variable,
		 * a constant or a {@code car}/{@code cdr} read of one.
		 */
		private void requirePure(LispVal args, LispVal form) {
			LispVal run = args;
			while (run instanceof LispCons cell) {
				if (!isPure(cell.car())) {
					throw new IllegalStateException(
							"a " + this.family.label + " arm over an argument with an effect: " + form.print());
				}
				run = cell.cdr();
			}
		}

	}

	private static boolean isPure(LispVal arg) {
		if (arg instanceof LispSymbol || arg instanceof LispString || arg instanceof LispInteger
				|| arg instanceof LispChar || arg instanceof LispNil || arg instanceof LispTrue) {
			return true;
		}
		if (arg instanceof LispCons cons && cons.car() instanceof LispSymbol head && cons.cdr() instanceof LispCons only
				&& only.cdr() instanceof LispNil) {
			String name = head.name();
			boolean read = name.equals("CAR") || name.equals("CDR") || name.equals("CADR") || name.equals("CDDR")
					|| name.equals("CADDR") || name.equals("CADDDR");
			return read && isPure(only.car());
		}
		return false;
	}

}
