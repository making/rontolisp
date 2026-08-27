package am.ik.rontolisp.eval;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
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
 * same form {@code make-load-form-saving-slots} spells. See
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
			LispVal car = code(cons.car());
			LispVal cdr = code(cons.cdr());
			return car == cons.car() && cdr == cons.cdr() ? form : new LispCons(car, cdr);
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
			return built == null ? null : listOf(new LispSymbol(LispNames.LOAD_TIME_VALUE), built);
		}

		/** The expression rebuilding one datum, or null when it needs no rebuilding. */
		@Nullable private LispVal rebuild(LispVal datum) {
			if (datum instanceof LispInstance instance) {
				return loadForm(instance);
			}
			if (!(datum instanceof LispCons cons)) {
				return null;
			}
			LispVal car = rebuild(cons.car());
			LispVal cdr = rebuild(cons.cdr());
			if (car == null && cdr == null) {
				return null;
			}
			return listOf(new LispSymbol(LispNames.CONS), car == null ? quoted(cons.car()) : car,
					cdr == null ? quoted(cons.cdr()) : cdr);
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
			this.inProgress.put(instance, Boolean.TRUE);
			try {
				List<LispVal> values = this.macroEval.makeLoadFormValues(instance);
				if (values == null || values.isEmpty()) {
					return null;
				}
				LispVal creation = code(values.get(0));
				LispVal init = values.size() > 1 ? values.get(1) : LispNil.INSTANCE;
				LispVal value = init instanceof LispNil ? creation : withInit(instance, creation, init);
				LispVal replacement = listOf(new LispSymbol(LispNames.LOAD_TIME_VALUE), value);
				this.done.put(instance, replacement);
				return replacement;
			}
			finally {
				this.inProgress.remove(instance);
			}
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
			return listOf(new LispSymbol(LispNames.LET), listOf(listOf(name, creation)), body, name);
		}

		private LispVal replaceObject(LispVal form, LispInstance instance, LispSymbol name) {
			if (form == instance) {
				return name;
			}
			if (!(form instanceof LispCons cons)) {
				return form;
			}
			LispVal car = replaceObject(cons.car(), instance, name);
			LispVal cdr = replaceObject(cons.cdr(), instance, name);
			return car == cons.car() && cdr == cons.cdr() ? form : new LispCons(car, cdr);
		}

	}

	private static LispVal quoted(LispVal datum) {
		return listOf(new LispSymbol(LispNames.QUOTE), datum);
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
