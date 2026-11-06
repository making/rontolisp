package am.ik.rontolisp.eval;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrees;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.ReaderSymbols;
import org.jspecify.annotations.Nullable;

/**
 * CLHS 3.2.4.4 on the compile path: an object that appears as a LITERAL in code being
 * compiled is dumped through its own {@code make-load-form} method, not by structure.
 *
 * <p>
 * The substitution happens HERE, in the macro-time pass, because that is where such an
 * object appears: a macro splices the live object straight into its expansion (cffi's
 * {@code expand-to-foreign} puts the parsed foreign type into every {@code defcfun}
 * body), and by the time {@code JvmQuoteCompiler}/{@code WasmQuoteCompiler} sees it there
 * is no evaluator left to ask. Each such object is replaced by its creation form BEFORE
 * any backend sees it, so both backends inherit one behavior with no codegen of their
 * own.
 *
 * <p>
 * A type nobody wrote a method for is left alone: rontolisp's built-in default for an
 * instance literal is the structural dump the quote compilers implement, which is the
 * same form {@code make-load-form-saving-slots} spells -- so an object whose method
 * answers that form, holding only such objects, is left alone too. See
 * {@code .kb/make-load-form.md}.
 */
final class LoadFormSubstituter {

	private LoadFormSubstituter() {
	}

	/**
	 * Replaces every literal instance in the form whose type has a {@code make-load-form}
	 * method with the form that reconstructs it.
	 * @param form one already-expanded top-level form
	 * @param macroEval the macro-time evaluator holding the program's methods
	 * @return the substituted form, or the argument itself when nothing was replaced
	 */
	static LispVal substitute(LispVal form, LispEvaluator macroEval) {
		if (!macroEval.hasMakeLoadFormMethods() || !containsInstance(form)) {
			return form;
		}
		return new Walk(macroEval).code(form);
	}

	// Cheap gate: a form with no instance anywhere needs no walk at all. Iterative on
	// purpose -- this runs over every top-level form of any program that defines a
	// make-load-form method (cl-ppcre defines two), quoted data included, and a
	// Java-recursive walk would spend one frame per cons of a long literal table.
	private static boolean containsInstance(LispVal form) {
		java.util.ArrayDeque<LispVal> pending = new java.util.ArrayDeque<>();
		pending.push(form);
		while (!pending.isEmpty()) {
			LispVal current = pending.pop();
			if (current instanceof LispInstance) {
				return true;
			}
			if (current instanceof LispCons cons) {
				pending.push(cons.car());
				pending.push(cons.cdr());
			}
		}
		return false;
	}

	/**
	 * One top-level form's substitution: carries the cycle guard and the name counter.
	 */
	private static final class Walk {

		private final LispEvaluator macroEval;

		/** The objects whose creation form is being built, for the cycle report. */
		private final Map<LispInstance, Boolean> inProgress = new IdentityHashMap<>();

		/** Memo so one object dumped twice in a form yields one creation form. */
		private final Map<LispInstance, LispVal> done = new IdentityHashMap<>();

		/** Whether each object judged so far stays a literal ({@link #staysLiteral}). */
		private final Map<LispInstance, Boolean> literal = new IdentityHashMap<>();

		/** The objects being judged, whose verdict a cycle back to them cannot use. */
		private final Map<LispInstance, Boolean> judging = new IdentityHashMap<>();

		/** Each object's make-load-form values, asked once. */
		private final Map<LispInstance, Optional<List<LispVal>>> asked = new IdentityHashMap<>();

		private int counter;

		private Walk(LispEvaluator macroEval) {
			this.macroEval = macroEval;
		}

		/**
		 * Walks a form in CODE position: an instance here is a self-evaluating literal
		 * (CLHS 3.1.2.1.3). {@code quote} is not descended into -- its argument is data
		 * -- but a quoted constant that CONTAINS such an object is rebuilt, since quoting
		 * cannot spell one.
		 */
		private LispVal code(LispVal form) {
			return LispTrees.rebuildSpine(form, this::codeNode, this::code);
		}

		// What a node code() does not walk into becomes, or null for an ordinary cell.
		private @Nullable LispVal codeNode(LispVal form) {
			if (form instanceof LispInstance instance) {
				LispVal replacement = loadForm(instance);
				return replacement == null ? form : replacement;
			}
			if (!(form instanceof LispCons cons)) {
				return form;
			}
			if (cons.car() instanceof LispSymbol op && LispNames.QUOTE.equals(memberName(op.name()))
					&& cons.cdr() instanceof LispCons rest && rest.cdr() instanceof LispNil) {
				LispVal rebuilt = quotedConstant(rest.car());
				return rebuilt == null ? form : rebuilt;
			}
			return null;
		}

		/**
		 * Rebuilds a quoted constant that contains a dumpable object, as an expression
		 * evaluated once: {@code '(:enum . #<type>)} becomes {@code (load-time-value
		 * (cons ':enum (parse-type '(:enum status))))}. Returns null when the constant
		 * holds no such object, which is the ordinary case -- the quote then stays put
		 * and the quote compilers dump it as they always have.
		 */
		@Nullable private LispVal quotedConstant(LispVal datum) {
			LispVal built = rebuild(datum);
			return built == null ? null : listOf(ReaderSymbols.LOAD_TIME_VALUE, built);
		}

		/** The expression rebuilding one datum, or null when it needs no rebuilding. */
		@Nullable private LispVal rebuild(LispVal datum) {
			if (datum instanceof LispInstance instance) {
				return loadForm(instance);
			}
			if (!(datum instanceof LispCons cons)) {
				return null;
			}
			// The cars in order, then the tail, then the cells from the tail back: the
			// recursive car / cdr walk's order, without a Java frame per element.
			List<LispCons> cells = new ArrayList<>();
			List<@Nullable LispVal> cars = new ArrayList<>();
			LispVal rest = cons;
			while (rest instanceof LispCons cell) {
				cells.add(cell);
				cars.add(rebuild(cell.car()));
				rest = cell.cdr();
			}
			LispVal cdr = rest instanceof LispInstance instance ? loadForm(instance) : null;
			for (int i = cells.size() - 1; i >= 0; i--) {
				LispCons cell = cells.get(i);
				LispVal car = cars.get(i);
				if (car != null || cdr != null) {
					cdr = listOf(ReaderSymbols.CONS, car == null ? quoted(cell.car()) : car,
							cdr == null ? quoted(cell.cdr()) : cdr);
				}
			}
			return cdr;
		}

		/**
		 * The expression that rebuilds one object, or null when its type has no
		 * {@code make-load-form} method. The value form runs once per program run
		 * ({@code load-time-value}), which is the load-time-once contract CL gives a
		 * dumped literal; an init form, when the method returns one, runs against the
		 * freshly created object -- every reference to the object inside it is the object
		 * ITSELF, which is what the substitution below rewrites to the binding.
		 */
		@Nullable private LispVal loadForm(LispInstance instance) {
			LispVal memo = this.done.get(instance);
			if (memo != null) {
				return memo;
			}
			if (this.inProgress.containsKey(instance)) {
				throw new IllegalStateException(LispNames.MAKE_LOAD_FORM + " of " + instance.layout().printName()
						+ " reaches the object itself: a circular creation form cannot be dumped");
			}
			if (staysLiteral(instance)) {
				return null;
			}
			this.inProgress.put(instance, Boolean.TRUE);
			try {
				List<LispVal> values = values(instance);
				if (values == null || values.isEmpty()) {
					return null;
				}
				LispVal creation = code(values.get(0));
				LispVal init = values.size() > 1 ? values.get(1) : LispNil.INSTANCE;
				LispVal value = init instanceof LispNil ? creation : withInit(instance, creation, init);
				LispVal replacement = listOf(ReaderSymbols.LOAD_TIME_VALUE, value);
				this.done.put(instance, replacement);
				return replacement;
			}
			finally {
				this.inProgress.remove(instance);
			}
		}

		/** The object's make-load-form values, or null when no method covers its type. */
		@Nullable private List<LispVal> values(LispInstance instance) {
			Optional<List<LispVal>> known = this.asked.get(instance);
			if (known == null) {
				known = Optional.ofNullable(this.macroEval.makeLoadFormValues(instance));
				this.asked.put(instance, known);
			}
			return known.orElse(null);
		}

		/**
		 * Whether an object stays the literal it is: no method covers its type, or its
		 * method answers exactly the structural dump the quote compilers build
		 * ({@code make-load-form-saving-slots}) and so does every object its slots reach.
		 * The quote compilers build such an object where it stands, the objects it holds
		 * inline and a node two literals reach once ({@code QuotedSharing}), with no
		 * load-time value of its own: coalton's environment updates carry a million of
		 * them. A cycle back to an object being judged answers no: its creation form
		 * reports the cycle, as before.
		 */
		private boolean staysLiteral(LispInstance instance) {
			Boolean known = this.literal.get(instance);
			if (known != null) {
				return known;
			}
			if (this.judging.containsKey(instance)) {
				return false;
			}
			this.judging.put(instance, Boolean.TRUE);
			boolean stays;
			try {
				List<LispVal> values = values(instance);
				stays = values == null || savesSlots(instance, values) && holdsOnlyLiterals(instance);
			}
			finally {
				this.judging.remove(instance);
			}
			this.literal.put(instance, stays);
			return stays;
		}

		/** Whether every object an instance's slots reach stays a literal. */
		private boolean holdsOnlyLiterals(LispInstance instance) {
			Set<LispVal> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			ArrayDeque<LispVal> pending = new ArrayDeque<>();
			for (int i = 0; i < instance.slotCount(); i++) {
				LispVal slot = instance.slot(i);
				if (slot != null) {
					pending.push(slot);
				}
			}
			while (!pending.isEmpty()) {
				LispVal value = pending.pop();
				switch (value) {
					case LispInstance held -> {
						if (!staysLiteral(held)) {
							return false;
						}
					}
					case LispCons cons -> {
						if (seen.add(cons)) {
							pending.push(cons.cdr());
							pending.push(cons.car());
						}
					}
					case LispArray array -> {
						if (seen.add(array)) {
							for (LispVal element : array.data()) {
								if (element != null) {
									pending.push(element);
								}
							}
						}
					}
					default -> {
					}
				}
			}
			return true;
		}

		/**
		 * {@code (let ((#:obj creation)) init #:obj)} -- the two-value protocol's shape.
		 * The init form names the object by carrying it literally (that is how CLHS words
		 * it: references to the object are references to the one being created), so every
		 * occurrence of the SAME object is replaced by the binding.
		 */
		private LispVal withInit(LispInstance instance, LispVal creation, LispVal init) {
			LispSymbol name = new LispSymbol(LispNames.LOAD_FORM_OBJECT_PREFIX + (++this.counter));
			LispVal body = code(replaceObject(init, instance, name));
			return listOf(ReaderSymbols.LET, listOf(listOf(name, creation)), body, name);
		}

		private LispVal replaceObject(LispVal form, LispInstance instance, LispSymbol name) {
			return LispTrees.rebuildSpine(form, node -> {
				if (node == instance) {
					return name;
				}
				return node instanceof LispCons ? null : node;
			}, car -> replaceObject(car, instance, name));
		}

	}

	/**
	 * Whether make-load-form values are the structural dump of the instance itself, the
	 * one form {@code make-load-form-saving-slots} answers:
	 * {@code (%obj-new 'TAG 'S0 ...)} over the instance's own tag and slots, by identity,
	 * and no init form.
	 */
	private static boolean savesSlots(LispInstance instance, List<LispVal> values) {
		if (values.size() > 1 && !(values.get(1) instanceof LispNil)) {
			return false;
		}
		if (!(values.get(0) instanceof LispCons form) || !form.isProperList()) {
			return false;
		}
		List<LispVal> parts = form.toList();
		if (parts.size() != instance.slotCount() + 2 || !(parts.get(0) instanceof LispSymbol op)
				|| !LispNames.OBJ_NEW.equals(memberName(op.name()))
				|| !(quotedDatum(parts.get(1)) instanceof LispSymbol tag)
				|| !tag.name().equals(instance.layout().tag())) {
			return false;
		}
		for (int i = 0; i < instance.slotCount(); i++) {
			LispVal datum = quotedDatum(parts.get(i + 2));
			if (datum == null || datum != instance.slot(i)) {
				return false;
			}
		}
		return true;
	}

	/** What a {@code (quote datum)} form quotes, or null for any other form. */
	private static @Nullable LispVal quotedDatum(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& LispNames.QUOTE.equals(memberName(op.name())) && cons.cdr() instanceof LispCons rest
				&& rest.cdr() instanceof LispNil ? rest.car() : null;
	}

	private static LispVal quoted(LispVal datum) {
		return listOf(ReaderSymbols.QUOTE, datum);
	}

	private static LispVal listOf(LispVal... items) {
		LispVal list = LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			list = new LispCons(items[i], list);
		}
		return list;
	}

	/** The package-stripped spelling an operator is recognized by. */
	private static String memberName(String name) {
		int colon = name.lastIndexOf(':');
		return colon < 0 ? name : name.substring(colon + 1);
	}

}
