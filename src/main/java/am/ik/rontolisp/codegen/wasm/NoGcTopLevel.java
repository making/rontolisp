package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.GlobalVarCollector;
import am.ik.rontolisp.macro.SpecialVarCollector;

/**
 * The top level of a program the scalar backend compiles, past its function definitions
 * and its export and import directives ({@code .kb/no-gc-scalar-wasm.md}, "The top
 * level"):
 * <ul>
 * <li>its global variables, what every backend takes for one
 * ({@link GlobalVarCollector}): the name of a {@code defvar}, {@code defparameter} or
 * {@code defconstant} and a name assigned where no binding holds it -- each a wasm
 * global;</li>
 * <li>its structure definitions ({@link NoGcStructures});</li>
 * <li>and the forms the module runs when it is instantiated, in program order: its start
 * function. An assignment of a literal that no form before it runs code ahead of is the
 * variable's initial value instead, so a top level that only gives its variables literal
 * values has no start function.</li>
 * </ul>
 * A {@code define-condition}, a declaration and a constant form do nothing here: a
 * condition is only ever signalled, which ends the call.
 */
final class NoGcTopLevel {

	/** The start function's name among the program's functions: no symbol reads as it. */
	static final String START = "(top level)";

	private final LinkedHashSet<String> globals;

	private final Set<String> specials;

	private final Map<String, LispVal> initialValues = new HashMap<>();

	private final List<LispVal> startForms = new ArrayList<>();

	private final NoGcStructures structures = new NoGcStructures();

	/** The globals a definer or an assignment has given a value, in program order. */
	private final Set<String> bound = new HashSet<>();

	/** Whether a start form runs code: every later assignment then runs after it. */
	private boolean running;

	private NoGcTopLevel(LinkedHashSet<String> globals, Set<String> specials) {
		this.globals = globals;
		this.specials = specials;
	}

	/**
	 * Sorts a program's top level.
	 * @param forms the top-level forms that define no function and are no directive, in
	 * program order
	 * @param program the whole program, whose function bodies may assign a global too
	 * @return the sorted top level
	 */
	static NoGcTopLevel of(List<LispVal> forms, List<LispVal> program) {
		LinkedHashSet<String> globals = GlobalVarCollector.collect(forms);
		globals.addAll(GlobalVarCollector.collectFreeAssigned(program));
		NoGcTopLevel top = new NoGcTopLevel(globals, SpecialVarCollector.collect(program));
		for (LispVal form : forms) {
			top.take(form);
		}
		return top;
	}

	/** Every global variable, in declaration order. */
	LinkedHashSet<String> globals() {
		return this.globals;
	}

	/**
	 * Whether a name is proclaimed special, which a binding of it would bind dynamically.
	 */
	boolean special(String name) {
		return this.specials.contains(name);
	}

	/** The literal each global holds when the module is instantiated, where one does. */
	Map<String, LispVal> initialValues() {
		return this.initialValues;
	}

	/** The forms the start function runs, in program order; empty for none. */
	List<LispVal> startForms() {
		return this.startForms;
	}

	/** The structure definitions. */
	NoGcStructures structures() {
		return this.structures;
	}

	/**
	 * Whether a value is a literal a wasm global can start at: a number, a character, a
	 * string, {@code t} or {@code nil}.
	 */
	static boolean literal(LispVal value) {
		return value instanceof LispInteger || value instanceof LispDouble || value instanceof LispChar
				|| value instanceof LispString || value instanceof LispTrue || value instanceof LispNil;
	}

	private void take(LispVal form) {
		if (literal(form) || form instanceof LispSymbol symbol && symbol.isKeyword()) {
			return;
		}
		if (!(form instanceof LispCons cons && cons.car() instanceof LispSymbol head && cons.isProperList())) {
			run(form);
			return;
		}
		List<LispVal> parts = cons.toList();
		switch (head.name()) {
			case LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT -> {
				if (parts.size() < 2 || !(parts.get(1) instanceof LispSymbol name)) {
					run(form);
					return;
				}
				// a defvar gives a value only to a variable that has none
				if (parts.size() > 2 && !(LispNames.DEFVAR.equals(head.name()) && this.bound.contains(name.name()))) {
					assign(name, parts.get(2));
				}
			}
			case LispNames.SETQ -> {
				if (parts.size() % 2 == 0) {
					run(form);
					return;
				}
				for (int i = 1; i < parts.size(); i += 2) {
					if (!(parts.get(i) instanceof LispSymbol name)) {
						run(form);
						return;
					}
				}
				for (int i = 1; i < parts.size(); i += 2) {
					assign((LispSymbol) parts.get(i), parts.get(i + 1));
				}
			}
			case LispNames.DEFSTRUCT -> {
				if (!this.structures.define(form)) {
					run(form);
				}
			}
			case LispNames.DEFINE_CONDITION, LispNames.DECLAIM, LispNames.PROCLAIM -> {
				// a condition is only ever signalled here, which ends the call; a
				// declaration changes nothing the scalar backend reads
			}
			default -> run(form);
		}
	}

	private void assign(LispSymbol name, LispVal value) {
		this.bound.add(name.name());
		if (!this.running && literal(value)) {
			this.initialValues.put(name.name(), value);
			return;
		}
		run(new LispCons(new LispSymbol(LispNames.SETQ), new LispCons(name, new LispCons(value, LispNil.INSTANCE))));
	}

	private void run(LispVal form) {
		this.startForms.add(form);
		this.running = true;
	}

}
