package am.ik.rontolisp.macro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrees;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import org.jspecify.annotations.Nullable;

/**
 * A {@code defclass} or {@code define-condition} below the top level on the compile paths
 * ({@code .kb/clos.md}, "A class defined below the top level"): snooze's
 * {@code (unless (find-class 'text nil) ... (defclass text ...))}, contextl's definitions
 * in a {@code loop}. The class's name, superclasses and slots are literal, so it is
 * registered like a top-level class: the form is HOISTED to top level, right ahead of the
 * form it stood in, where every scan for definers and the definition walk see it. What
 * the definition does as it runs stays where it stood, behind a marker the walk replaces
 * with those forms: the metaclass protocol's driver, and the end of the name's pending
 * state, before which {@code find-class} answers no class.
 *
 * <p>
 * Its initforms are inlined into top-level constructors, so a class whose initform refers
 * to a variable a form around it binds is refused.
 */
final class NestedClassDefinitions {

	/**
	 * {@code (%nested-class-defined ID)}: where hoisted definition {@code ID} ran. The
	 * definition walk replaces it with what runs there; no backend sees it.
	 */
	static final String MARKER = "%NESTED-CLASS-DEFINED";

	/** Operators whose body forms are evaluated in order, the last one's value theirs. */
	private static final Set<String> BODIES = Set.of(LispNames.PROGN, LispNames.LOCALLY, LispNames.EVAL_WHEN);

	/** Operators whose element 1 binds variables for the elements after it. */
	private static final Set<String> BINDERS = Set.of(LispNames.LET, LispNames.LET_STAR, LispNames.PROG,
			LispNames.PROG_STAR, LispNames.SYMBOL_MACROLET, LispNames.LAMBDA, LispNames.DOLIST, LispNames.DOTIMES,
			LispNames.DO, LispNames.DO_STAR, LispNames.MULTIPLE_VALUE_BIND, LispNames.DESTRUCTURING_BIND);

	/** The case family: element 1 is evaluated, every clause's first element is keys. */
	private static final Set<String> CASES = Set.of(LispNames.CASE, LispNames.ECASE, LispNames.CCASE,
			LispNames.TYPECASE, LispNames.ETYPECASE, LispNames.CTYPECASE);

	/** Words a {@code loop} binds the symbols after. */
	private static final Set<String> LOOP_BINDING_WORDS = Set.of("FOR", "AS", "WITH", "INTO", "USING");

	private final List<LispVal> hoisted = new ArrayList<>();

	private final Map<LispVal, Integer> ids = new IdentityHashMap<>();

	private final Set<String> classNames = new LinkedHashSet<>();

	private NestedClassDefinitions() {
	}

	/**
	 * What {@link #split} made of a program.
	 *
	 * @param program the program, each nested definition hoisted ahead of its form
	 * @param ids each hoisted definition (by identity) to the id its marker carries
	 * @param marked the forms holding a marker, by identity
	 * @param classNames the normalized names of the classes defined below the top level
	 */
	record Split(List<LispVal> program, Map<LispVal, Integer> ids, Set<LispVal> marked, Set<String> classNames) {
	}

	/**
	 * Hoists every class definition below the top level of the program ahead of the
	 * top-level form it stood in, leaving a {@link #MARKER} in its place, followed by the
	 * class object ({@code define-condition}: the name) where the form's value is used.
	 * @param program the top-level forms
	 * @return the program and what the walk needs to put the markers back
	 * @throws UnsupportedOperationException when an initform refers to a variable a form
	 * around the class binds
	 */
	static Split split(List<LispVal> program) {
		NestedClassDefinitions state = new NestedClassDefinitions();
		List<LispVal> out = null;
		Set<LispVal> marked = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int i = 0; i < program.size(); i++) {
			LispVal form = program.get(i);
			int before = state.hoisted.size();
			LispVal rewritten = walksBelow(form) && holdsClassDefinition(form) ? state.walk(form, Set.of(), false)
					: form;
			if (rewritten == form) {
				if (out != null) {
					out.add(form);
				}
				continue;
			}
			if (out == null) {
				out = new ArrayList<>(program.subList(0, i));
			}
			out.addAll(state.hoisted.subList(before, state.hoisted.size()));
			out.add(rewritten);
			marked.add(rewritten);
		}
		return new Split(out == null ? program : out, state.ids, marked, state.classNames);
	}

	/**
	 * Replaces each {@link #MARKER} in a form the split marked with what its definition
	 * runs there.
	 * @param form the form
	 * @param runs the forms each definition runs where it stood, by id
	 * @return the form with every marker replaced
	 */
	static LispVal substitute(LispVal form, Map<Integer, List<LispVal>> runs) {
		return LispTrees.rebuildSpine(form, node -> {
			if (!(node instanceof LispCons cons)) {
				return node;
			}
			if (cons.car() instanceof LispSymbol head && LispNames.isQuote(head.name())) {
				return node;
			}
			Integer id = markerId(cons);
			if (id == null) {
				return null;
			}
			List<LispVal> run = runs.getOrDefault(id, List.of());
			return run.isEmpty() ? LispNil.INSTANCE : listOf(LispNames.PROGN, run);
		}, element -> substitute(element, runs));
	}

	/** The id of a {@code (%nested-class-defined ID)} marker, or null. */
	private static @Nullable Integer markerId(LispCons cons) {
		return cons.car() instanceof LispSymbol head && MARKER.equals(head.name()) && cons.cdr() instanceof LispCons arg
				&& arg.car() instanceof LispInteger id ? (int) id.value() : null;
	}

	/**
	 * Whether the walk looks below the top-level form: the definers whose parts are data
	 * or initforms have no class definition below them to run.
	 */
	private static boolean walksBelow(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
			return form instanceof LispCons;
		}
		return switch (head.name()) {
			case LispNames.DEFCLASS, LispNames.DEFINE_CONDITION, LispNames.DEFSTRUCT, LispNames.DEFTYPE,
					LispNames.DEFMACRO ->
				false;
			default -> !LispNames.isQuote(head.name());
		};
	}

	/**
	 * Whether a class definition stands anywhere in the form, quoted data included: what
	 * the walk looks below at all, which skips the quoted data itself.
	 */
	private static boolean holdsClassDefinition(LispVal form) {
		return LispTrees.anyCons(form, NestedClassDefinitions::isClassDefinition);
	}

	/**
	 * Whether the cons is a {@code defclass}/{@code define-condition} of the definition's
	 * shape -- a name, a superclass list and a slot list -- rather than a list a
	 * {@code case} clause or data spells with the operator's name.
	 */
	private static boolean isClassDefinition(LispCons cons) {
		if (!(cons.car() instanceof LispSymbol head)
				|| !(LispNames.DEFCLASS.equals(head.name()) || LispNames.DEFINE_CONDITION.equals(head.name()))
				|| !cons.isProperList()) {
			return false;
		}
		List<LispVal> parts = cons.toList();
		return parts.size() >= 4 && parts.get(1) instanceof LispSymbol name && !name.isKeyword()
				&& isProperListOf(parts.get(2), true) && isProperListOf(parts.get(3), false);
	}

	private static boolean isProperListOf(LispVal list, boolean symbols) {
		if (list instanceof LispNil) {
			return true;
		}
		if (!(list instanceof LispCons cons) || !cons.isProperList()) {
			return false;
		}
		return !symbols || cons.toList().stream().allMatch(LispSymbol.class::isInstance);
	}

	/**
	 * Rewrites one form: the class definitions below it hoisted, each replaced by its
	 * marker.
	 * @param form the form
	 * @param bound the variables the forms around it bind
	 * @param used whether its value is used
	 * @return the rewritten form, the form itself when nothing below it changed
	 */
	private LispVal walk(LispVal form, Set<String> bound, boolean used) {
		if (!(form instanceof LispCons cons) || !cons.isProperList() || !holdsClassDefinition(form)) {
			return form;
		}
		if (isClassDefinition(cons)) {
			return hoist(cons, bound, used);
		}
		List<LispVal> parts = cons.toList();
		if (!(cons.car() instanceof LispSymbol head)) {
			return rebuilt(cons, parts, walkAll(parts, 0, bound));
		}
		String op = head.name();
		if (LispNames.isQuote(op) || LispNames.DECLARE.equals(op)) {
			return form;
		}
		List<LispVal> walked;
		if (BODIES.contains(op) || LispNames.BLOCK_INTERNAL.equals(op)) {
			walked = walkBody(parts, LispNames.EVAL_WHEN.equals(op) ? 2 : 1, bound, used);
		}
		else if (LispNames.WHEN.equals(op) || LispNames.UNLESS.equals(op) || LispNames.BLOCK.equals(op)
				|| LispNames.UNWIND_PROTECT.equals(op) || LispNames.WHILE.equals(op)) {
			walked = walkBody(parts, 2, bound, used && !LispNames.WHILE.equals(op));
			if (!LispNames.BLOCK.equals(op) && parts.size() > 1) {
				walked.set(1, walk(parts.get(1), bound, LispNames.UNWIND_PROTECT.equals(op) ? used : true));
			}
		}
		else if (LispNames.IF.equals(op)) {
			walked = new ArrayList<>(parts);
			for (int i = 1; i < parts.size(); i++) {
				walked.set(i, walk(parts.get(i), bound, i == 1 || used));
			}
		}
		else if (LispNames.COND.equals(op)) {
			walked = new ArrayList<>(parts);
			for (int i = 1; i < parts.size(); i++) {
				walked.set(i, walkClause(parts.get(i), bound, used, true));
			}
		}
		else if (CASES.contains(op) || LispNames.HANDLER_CASE.equals(op)) {
			walked = new ArrayList<>(parts);
			if (parts.size() > 1) {
				walked.set(1, walk(parts.get(1), bound, true));
			}
			for (int i = 2; i < parts.size(); i++) {
				walked.set(i, LispNames.HANDLER_CASE.equals(op) ? walkHandlerClause(parts.get(i), bound, used)
						: walkClause(parts.get(i), bound, used, false));
			}
		}
		else if (LispNames.TAGBODY.equals(op)) {
			walked = walkBody(parts, 1, bound, false);
		}
		else if (BINDERS.contains(op) || op.startsWith("WITH-")) {
			walked = walkBinder(op, parts, bound, used);
		}
		else if (LispNames.FUNCTION.equals(op)) {
			walked = walkAll(parts, 0, bound);
		}
		else if (LispNames.DEFUN.equals(op) || LispNames.DEFMETHOD.equals(op) || ":METHOD".equals(op)) {
			// A defgeneric's (:method ...) option has no name ahead of its qualifiers.
			walked = walkFunctionBody(parts, ":METHOD".equals(op) ? 1 : 2, bound);
		}
		else if (LispNames.FLET.equals(op) || LispNames.LABELS.equals(op) || LispNames.MACROLET.equals(op)) {
			walked = walkBody(parts, 2, bound, used);
			if (parts.size() > 1 && parts.get(1) instanceof LispCons definitions && definitions.isProperList()) {
				List<LispVal> local = new ArrayList<>();
				for (LispVal definition : definitions.toList()) {
					local.add(definition instanceof LispCons function && function.isProperList()
							? rebuilt(function, function.toList(), walkLambda(function.toList(), 1, bound))
							: definition);
				}
				walked.set(1, rebuilt(definitions, definitions.toList(), local));
			}
		}
		else if (LispNames.LOOP.equals(op)) {
			Set<String> inner = new HashSet<>(bound);
			for (int i = 1; i + 1 < parts.size(); i++) {
				if (parts.get(i) instanceof LispSymbol word && LOOP_BINDING_WORDS.contains(memberOf(word))) {
					addSymbols(parts.get(i + 1), inner);
				}
			}
			walked = walkAll(parts, 1, inner);
		}
		else {
			walked = walkAll(parts, 1, bound);
		}
		return rebuilt(cons, parts, walked);
	}

	/** Every element from {@code from}, each a form whose value is used. */
	private List<LispVal> walkAll(List<LispVal> parts, int from, Set<String> bound) {
		List<LispVal> walked = new ArrayList<>(parts);
		for (int i = from; i < parts.size(); i++) {
			walked.set(i, walk(parts.get(i), bound, true));
		}
		return walked;
	}

	/** A body from {@code from}: the last form's value is the body's. */
	private List<LispVal> walkBody(List<LispVal> parts, int from, Set<String> bound, boolean used) {
		List<LispVal> walked = new ArrayList<>(parts);
		for (int i = from; i < parts.size(); i++) {
			walked.set(i, walk(parts.get(i), bound, used && i == parts.size() - 1));
		}
		return walked;
	}

	/**
	 * A {@code cond} clause (its test evaluated) or a case-family clause (its keys not).
	 */
	private LispVal walkClause(LispVal clause, Set<String> bound, boolean used, boolean test) {
		if (!(clause instanceof LispCons cons) || !cons.isProperList()) {
			return clause;
		}
		List<LispVal> parts = cons.toList();
		List<LispVal> walked = walkBody(parts, 1, bound, used);
		if (test) {
			walked.set(0, walk(parts.get(0), bound, true));
		}
		return rebuilt(cons, parts, walked);
	}

	/** A {@code handler-case} clause: {@code (type ([var]) body...)}. */
	private LispVal walkHandlerClause(LispVal clause, Set<String> bound, boolean used) {
		if (!(clause instanceof LispCons cons) || !cons.isProperList()) {
			return clause;
		}
		List<LispVal> parts = cons.toList();
		Set<String> inner = new HashSet<>(bound);
		if (parts.size() > 1) {
			addSymbols(parts.get(1), inner);
		}
		return rebuilt(cons, parts, walkBody(parts, 2, inner, used));
	}

	/**
	 * A form whose element 1 binds: what it binds is every symbol there (a binding's
	 * initial value form among them, which only makes the capture check stricter), and
	 * the elements after it are its body.
	 */
	private List<LispVal> walkBinder(String op, List<LispVal> parts, Set<String> bound, boolean used) {
		if (parts.size() < 2) {
			return new ArrayList<>(parts);
		}
		Set<String> inner = new HashSet<>(bound);
		addSymbols(parts.get(1), inner);
		boolean iterates = LispNames.DOLIST.equals(op) || LispNames.DOTIMES.equals(op) || LispNames.DO.equals(op)
				|| LispNames.DO_STAR.equals(op) || LispNames.PROG.equals(op) || LispNames.PROG_STAR.equals(op);
		boolean valueForm = LispNames.MULTIPLE_VALUE_BIND.equals(op) || LispNames.DESTRUCTURING_BIND.equals(op);
		List<LispVal> walked = walkBody(parts, valueForm ? 3 : 2, inner, used && !iterates);
		walked.set(1, walk(parts.get(1), inner, true));
		if (valueForm && parts.size() > 2) {
			walked.set(2, walk(parts.get(2), bound, true));
		}
		return walked;
	}

	/**
	 * {@code (defun name lambda-list body...)}, a method's qualifiers ahead of its lambda
	 * list: the body returns the value.
	 */
	private List<LispVal> walkFunctionBody(List<LispVal> parts, int from, Set<String> bound) {
		int listAt = from;
		while (listAt < parts.size()
				&& !(parts.get(listAt) instanceof LispCons || parts.get(listAt) instanceof LispNil)) {
			listAt++;
		}
		return walkLambda(parts, listAt, bound);
	}

	/** A lambda list at {@code listAt} and the body after it, the parameters bound. */
	private List<LispVal> walkLambda(List<LispVal> parts, int listAt, Set<String> bound) {
		if (listAt >= parts.size()) {
			return new ArrayList<>(parts);
		}
		Set<String> inner = new HashSet<>(bound);
		addSymbols(parts.get(listAt), inner);
		List<LispVal> walked = walkBody(parts, listAt + 1, inner, true);
		walked.set(listAt, walk(parts.get(listAt), inner, true));
		return walked;
	}

	/**
	 * Hoists one class definition and answers its marker, followed by the definition's
	 * value where it is used.
	 */
	private LispVal hoist(LispCons definition, Set<String> bound, boolean used) {
		String captured = capturedVariable(definition, bound);
		List<LispVal> parts = definition.toList();
		LispSymbol name = (LispSymbol) parts.get(1);
		String operator = ((LispSymbol) definition.car()).name();
		if (captured != null) {
			throw SourceProvenance.noteFailure(definition,
					new UnsupportedOperationException(operator + " " + name.name()
							+ ": a class defined below the top level refers in an initform to " + captured
							+ ", which a form around it binds; the compile paths inline initforms into top-level"
							+ " constructors, so only the interpreter supports that"));
		}
		int id = this.ids.size() + 1;
		this.hoisted.add(definition);
		this.ids.put(definition, id);
		this.classNames.add(ClosRegistry.normalize(name.name()));
		LispVal marker = new LispCons(new LispSymbol(MARKER), new LispCons(new LispInteger(id), LispNil.INSTANCE));
		if (!used) {
			return marker;
		}
		LispVal quoted = new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(name, LispNil.INSTANCE));
		LispVal value = LispNames.DEFCLASS.equals(operator)
				? new LispCons(new LispSymbol(LispNames.FIND_CLASS), new LispCons(quoted, LispNil.INSTANCE)) : quoted;
		return listOf(LispNames.PROGN, List.of(marker, value));
	}

	/**
	 * The first variable a form around the definition binds that one of its initforms or
	 * default initargs mentions, or null.
	 */
	private static @Nullable String capturedVariable(LispCons definition, Set<String> bound) {
		if (bound.isEmpty()) {
			return null;
		}
		List<LispVal> parts = definition.toList();
		List<LispVal> forms = new ArrayList<>();
		if (parts.get(3) instanceof LispCons slots) {
			for (LispVal slot : slots.toList()) {
				if (slot instanceof LispCons spec && spec.isProperList()) {
					List<LispVal> options = spec.toList();
					for (int i = 1; i + 1 < options.size(); i += 2) {
						if (options.get(i) instanceof LispSymbol key && ":INITFORM".equals(key.name())) {
							forms.add(options.get(i + 1));
						}
					}
				}
			}
		}
		for (LispVal option : parts.subList(4, parts.size())) {
			if (option instanceof LispCons cons && cons.car() instanceof LispSymbol key
					&& ":DEFAULT-INITARGS".equals(key.name()) && cons.isProperList()) {
				List<LispVal> pairs = cons.toList();
				for (int i = 2; i < pairs.size(); i += 2) {
					forms.add(pairs.get(i));
				}
			}
		}
		for (LispVal form : forms) {
			String mentioned = mentionedVariable(form, bound);
			if (mentioned != null) {
				return mentioned;
			}
		}
		return null;
	}

	/** A symbol of {@code bound} the form mentions outside quoted data and operators. */
	private static @Nullable String mentionedVariable(LispVal form, Set<String> bound) {
		if (form instanceof LispSymbol symbol) {
			return bound.contains(symbol.name()) ? symbol.name() : null;
		}
		if (!(form instanceof LispCons cons)) {
			return null;
		}
		if (cons.car() instanceof LispSymbol head && LispNames.isQuote(head.name())) {
			return null;
		}
		LispVal cell = cons.car() instanceof LispSymbol ? cons.cdr() : cons;
		while (cell instanceof LispCons c) {
			String mentioned = mentionedVariable(c.car(), bound);
			if (mentioned != null) {
				return mentioned;
			}
			cell = c.cdr();
		}
		return mentionedVariable(cell, bound);
	}

	/** Adds every symbol of the datum (the names a binding form binds, and more). */
	private static void addSymbols(LispVal datum, Set<String> into) {
		LispTrees.anyDatumNode(datum, node -> {
			if (node instanceof LispSymbol symbol && !symbol.isKeyword()) {
				into.add(symbol.name());
			}
			return false;
		});
	}

	private static String memberOf(LispSymbol symbol) {
		String name = symbol.name();
		int colon = name.lastIndexOf(':');
		return colon < 0 ? name : name.substring(colon + 1);
	}

	/** The cons rebuilt from the walked elements, itself when none changed. */
	private static LispVal rebuilt(LispCons cons, List<LispVal> parts, List<LispVal> walked) {
		for (int i = 0; i < parts.size(); i++) {
			if (parts.get(i) != walked.get(i)) {
				return SourceProvenance.inherit(cons, listOf(walked));
			}
		}
		return cons;
	}

	private static LispVal listOf(String head, List<LispVal> elements) {
		List<LispVal> all = new ArrayList<>();
		all.add(new LispSymbol(head));
		all.addAll(elements);
		return listOf(all);
	}

	private static LispVal listOf(List<LispVal> elements) {
		LispVal list = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			list = new LispCons(elements.get(i), list);
		}
		return list;
	}

}
