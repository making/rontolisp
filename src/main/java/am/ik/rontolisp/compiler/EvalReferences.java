package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * Makes the operators and variables a compiled {@code eval} can reach references of the
 * program, so the one gate each of them already has opens for it.
 *
 * <p>
 * {@code _eval} resolves an operator through the registry of compiled functions and a
 * variable through the global mirror, and the program holds a reference-gated wrapper
 * ({@link BuiltinFunctionWrappers#REFERENCE_GATED_FUNCTIONS}) or a standard variable only
 * when it names one: {@code (eval '(typep 1 'integer))} names {@code typep} as data,
 * which no gate counts, so the call found no function. Every gate does count a
 * {@code #'name}, and every standard variable's gate counts a mention, so this pass adds
 * those at the head of the program, ahead of any {@code in-package}, as forms that run
 * nothing.
 *
 * <p>
 * Two cases, both syntactic like {@link RuntimeNameProducers}:
 * <ul>
 * <li>A program whose forms are its own data references the gated operators that data
 * spells: the head of a quoted list at any depth, or a quoted symbol, which is how
 * {@code (list 'typep x ''integer)} spells one.</li>
 * <li>A program that can evaluate a form it reads or builds at run time can reach any
 * name, so it references all of them, but for {@link #NOT_REFERENCED_FOR_READ_FORMS}. A
 * spliced ASDF system does not count here: a library's own {@code eval} of what it reads
 * (a feature probe, a config file) is no reason to put every wrapper in every program
 * using the library, and most such definitions are pruned later.</li>
 * </ul>
 */
public final class EvalReferences {

	/**
	 * The directives {@code _eval} answers nil for instead of reporting an undefined
	 * function: a file a run-time {@code load} reads may start with them, and packages
	 * are resolved when a program compiles, so they have nothing to do.
	 */
	public static final List<String> UNPROCESSED_DIRECTIVES = List.of(LispNames.IN_PACKAGE, LispNames.DEFPACKAGE,
			LispNames.DECLAIM);

	/**
	 * What a form read at run time cannot reach unless the program names it.
	 * {@code sleep}: under {@code --component} its wrapper puts the whole module in async
	 * mode. {@code load}: a reference keeps every library definition from the pruner. The
	 * package-qualified operators are out of {@link #GATED_OPERATORS} altogether: the
	 * {@code rontolisp:} natives refuse the compile on the backends without them.
	 */
	private static final Set<String> NOT_REFERENCED_FOR_READ_FORMS = Set.of(LispNames.SLEEP, LispNames.LOAD);

	private static final Set<String> GATED_OPERATORS = gatedOperators();

	private static Set<String> gatedOperators() {
		Set<String> gated = new TreeSet<>(BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS);
		gated.addAll(BuiltinFunctionWrappers.COMPLEX_GATED_FUNCTIONS);
		gated.removeIf((name) -> PackageRegistry.splitQualified(name) != null);
		return Set.copyOf(gated);
	}

	private EvalReferences() {
	}

	/**
	 * The program with the references its evaluated forms need at its head.
	 * @param program the program after user-macro expansion, before any library splice,
	 * which has to see the references: a wrapper's body reaches the prelude
	 * @return the program, itself when it evaluates no form or needs no reference
	 */
	public static List<LispVal> process(List<LispVal> program) {
		Scan library = new Scan();
		Scan user = new Scan();
		List<LispVal> userForms = new ArrayList<>();
		int systems = 0;
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head) {
				systems += LispNames.BEGIN_SYSTEM.equals(head.name()) ? 1
						: LispNames.END_SYSTEM.equals(head.name()) ? -1 : 0;
			}
			if (systems > 0) {
				library.code(form);
			}
			else {
				user.code(form);
				userForms.add(form);
			}
		}
		boolean readForms = user.loads
				|| user.evaluates && (user.reads || RuntimeNameProducers.anySymbolBuilder(userForms));
		if (!readForms && !user.evaluates && !library.evaluates) {
			return program;
		}
		Set<String> spelled = new TreeSet<>(user.spelled);
		spelled.addAll(library.spelled);
		Set<String> operators = new TreeSet<>(readForms ? GATED_OPERATORS : spelled);
		if (readForms) {
			operators.removeAll(NOT_REFERENCED_FOR_READ_FORMS);
		}
		if (operators.isEmpty() && !readForms) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(program.size() + operators.size() + 1);
		for (String operator : operators) {
			out.add(form(LispNames.FUNCTION, new LispSymbol(operator)));
		}
		if (readForms) {
			List<LispVal> names = new ArrayList<>();
			for (String variable : new TreeSet<>(PackageRegistry.standardVariableNames())) {
				names.add(new LispSymbol(variable));
			}
			out.add(form(LispNames.QUOTE, list(names)));
		}
		out.addAll(program);
		return out;
	}

	private static LispVal form(String operator, LispVal argument) {
		return list(List.of(new LispSymbol(operator), argument));
	}

	private static LispVal list(List<LispVal> elements) {
		LispVal list = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			list = new LispCons(elements.get(i), list);
		}
		return list;
	}

	/** The name of the standard operator a symbol spells, or null when it spells none. */
	private static @Nullable String standardName(LispSymbol symbol) {
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(symbol.name());
		if (qualified == null) {
			return symbol.name();
		}
		return LispNames.CL_PKG.equals(qualified.pkg()) || "COMMON-LISP".equals(qualified.pkg()) ? qualified.member()
				: null;
	}

	private static final class Scan {

		final Set<String> spelled = new TreeSet<>();

		boolean evaluates;

		boolean reads;

		boolean loads;

		/** Walks code: the head of every list, and the data of a {@code quote}. */
		void code(LispVal form) {
			LispVal cursor = form;
			while (cursor instanceof LispCons cons) {
				if (cons.car() instanceof LispSymbol head) {
					String name = standardName(head);
					if (LispNames.QUOTE.equals(name) && cons.cdr() instanceof LispCons quoted) {
						datum(quoted.car());
						return;
					}
					this.evaluates = this.evaluates || LispNames.EVAL.equals(name);
					this.reads = this.reads || LispNames.READ.equals(name) || LispNames.READ_FROM_STRING.equals(name);
					this.loads = this.loads || LispNames.LOAD.equals(name);
				}
				else {
					code(cons.car());
				}
				cursor = cons.cdr();
			}
		}

		/**
		 * Walks quoted data: a symbol that stands alone or heads a list spells a call.
		 */
		void datum(LispVal datum) {
			if (datum instanceof LispSymbol symbol) {
				spell(symbol);
			}
			else if (datum instanceof LispCons list) {
				if (list.car() instanceof LispSymbol head) {
					spell(head);
				}
				LispVal cursor = list;
				while (cursor instanceof LispCons cons) {
					if (cons.car() instanceof LispCons || cons.car() instanceof LispArray) {
						datum(cons.car());
					}
					cursor = cons.cdr();
				}
			}
			else if (datum instanceof LispArray array) {
				for (LispVal element : array.data()) {
					datum(element);
				}
			}
		}

		private void spell(LispSymbol symbol) {
			String name = standardName(symbol);
			if (name != null && GATED_OPERATORS.contains(name)) {
				this.spelled.add(name);
			}
		}

	}

}
