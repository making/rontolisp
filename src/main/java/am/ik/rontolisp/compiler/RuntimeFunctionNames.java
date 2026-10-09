package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

/**
 * A function name the program builds when it runs, given to {@code fdefinition},
 * {@code fboundp}, {@code fmakunbound} or {@code (setf fdefinition)} on the compile
 * paths. A {@code (setf place)} list names the function stored under the internal symbol
 * {@code %setf-PLACE}; a quoted list is mapped at compile time
 * ({@link FunctionDesignators#normalizeBuiltinDesignators}), a list built at run time by
 * the prelude helpers {@code %function-name}, {@code %fdefinition} and
 * {@code %fmakunbound}, which the expression compilers of both backends call in place of
 * the operator ({@link #functionNameArgument}, {@link #fdefinitionCall},
 * {@link #fmakunboundCall}).
 *
 * <p>
 * The helpers ride only in a program where such a list can reach such an operator
 * ({@link #sites}): an operator given a COMPUTED name, and a way for the run time to hold
 * the symbol {@code SETF} as data -- quoted in the program, a {@code "SETF"} string a
 * symbol builder interns, or a data evaluator ({@link RuntimeNameProducers}). A program
 * without one compiles as it did; a name assembled out of computed pieces is the
 * carve-out {@link RuntimeNameProducers} documents.
 */
public final class RuntimeFunctionNames {

	/** The operators that take a function name. */
	private static final Set<String> FUNCTION_NAME_OPERATORS = Set.of(LispNames.FDEFINITION, LispNames.FBOUNDP,
			LispNames.FMAKUNBOUND);

	/** Calls whose value is a symbol (or nil), never a {@code (setf place)} list. */
	private static final Set<String> SYMBOL_VALUED = Set.of(LispNames.INTERN, LispNames.FIND_SYMBOL,
			LispNames.MAKE_SYMBOL, LispNames.GENSYM, LispNames.GENTEMP);

	private RuntimeFunctionNames() {
	}

	/**
	 * Which operators of a program can be handed a {@code (setf place)} list built at run
	 * time -- what selects the prelude helpers.
	 * 
	 * @param any whether any can ({@code %function-name})
	 * @param fdefinition whether {@code fdefinition} can ({@code %fdefinition})
	 * @param fmakunbound whether {@code fmakunbound} can ({@code %fmakunbound})
	 */
	public record Sites(boolean any, boolean fdefinition, boolean fmakunbound) {

		/** No operator can. */
		public static final Sites NONE = new Sites(false, false, false);

	}

	/**
	 * The operators of {@code program} that can be handed a {@code (setf place)} list
	 * built at run time: a call given a computed name ({@link #mayBeComputedName}) or the
	 * operator as a function value, in a program that can hold the symbol {@code SETF} as
	 * data at run time.
	 * @param program the top-level forms
	 * @return the sites, {@link Sites#NONE} when no such list can reach one
	 */
	public static Sites sites(List<LispVal> program) {
		boolean[] found = new boolean[3];
		for (LispVal form : program) {
			scanSites(form, found);
		}
		if (!(found[0] || found[1] || found[2]) || !setfConstructible(program)) {
			return Sites.NONE;
		}
		return new Sites(true, found[1], found[2]);
	}

	/**
	 * Whether an argument in a function-name position can be a {@code (setf place)} list
	 * the compiler cannot see: anything but a quoted datum, a {@code #'} form, a constant
	 * and a call whose value is a symbol.
	 * @param arg the argument form
	 * @return true when the name is computed
	 */
	public static boolean mayBeComputedName(LispVal arg) {
		if (arg instanceof LispSymbol sym) {
			return !sym.name().startsWith(":") && !"T".equals(sym.name()) && !"NIL".equals(sym.name());
		}
		if (arg instanceof LispCons cons) {
			return !(cons.car() instanceof LispSymbol head && (LispNames.isQuote(head.name())
					|| LispNames.FUNCTION.equals(head.name()) || LispNames.FUNCTION_NAME_INTERNAL.equals(head.name())
					|| SYMBOL_VALUED.contains(head.name())));
		}
		return false;
	}

	/**
	 * The name argument of {@code %set-symbol-function} -- the lowering of
	 * {@code (setf (fdefinition name) fn)} -- or of a computed {@code fboundp}:
	 * {@code (%function-name arg)} where the program defines the helper and the argument
	 * is computed, else the argument.
	 * @param arg the argument form
	 * @param helperDefined whether the program defines {@code %function-name}
	 * @return the form naming the function
	 */
	public static LispVal functionNameArgument(LispVal arg, boolean helperDefined) {
		return helperDefined && mayBeComputedName(arg) ? call(LispNames.FUNCTION_NAME_INTERNAL, arg) : arg;
	}

	/**
	 * {@code (%fdefinition arg)} for an {@code (fdefinition arg)} call whose argument is
	 * computed, in a program that defines the helper; otherwise null, and the call
	 * compiles as before.
	 * @param call the {@code fdefinition} call
	 * @param helperDefined whether the program defines {@code %fdefinition}
	 * @return the replacement call, or null
	 */
	public static @Nullable LispCons fdefinitionCall(LispCons call, boolean helperDefined) {
		return replacement(call, LispNames.FDEFINITION_INTERNAL, helperDefined);
	}

	/**
	 * {@code (%fmakunbound arg)} for an {@code (fmakunbound arg)} call whose argument is
	 * computed, in a program that defines the helper; otherwise null.
	 * @param call the {@code fmakunbound} call
	 * @param helperDefined whether the program defines {@code %fmakunbound}
	 * @return the replacement call, or null
	 */
	public static @Nullable LispCons fmakunboundCall(LispCons call, boolean helperDefined) {
		return replacement(call, LispNames.FMAKUNBOUND_INTERNAL, helperDefined);
	}

	/**
	 * The prelude source of the three helpers, in {@code LispPreludeLibrary} entry form.
	 * {@code %fdefinition} and {@code %fmakunbound} hand the mapped name to the operator
	 * as a {@code (%function-name ...)} call, which no compiler maps again.
	 * @param helper the helper's name
	 * @return its definition
	 */
	public static String definition(String helper) {
		return switch (helper) {
			case LispNames.FUNCTION_NAME_INTERNAL -> """
					(defun %function-name (%fn-name)
					  (if (and (consp %fn-name) (eq (car %fn-name) 'setf) (consp (cdr %fn-name))
					           (null (cddr %fn-name)) (cadr %fn-name) (symbolp (cadr %fn-name)))
					      (%setf-function-symbol (cadr %fn-name))
					      %fn-name))
					""";
			case LispNames.FDEFINITION_INTERNAL -> """
					(defun %fdefinition (%fn-name)
					  (if (and (consp %fn-name) (eq (car %fn-name) 'setf)
					           (not (fboundp (%function-name %fn-name))))
					      (%undefined-setf-function (cadr %fn-name))
					      (symbol-function (%function-name %fn-name))))
					""";
			case LispNames.FMAKUNBOUND_INTERNAL -> """
					(defun %fmakunbound (%fn-name)
					  (fmakunbound (%function-name %fn-name))
					  %fn-name)
					""";
			default -> throw new IllegalArgumentException(helper);
		};
	}

	private static @Nullable LispCons replacement(LispCons call, String helper, boolean helperDefined) {
		if (helperDefined && call.cdr() instanceof LispCons args && args.cdr() instanceof LispNil
				&& mayBeComputedName(args.car())) {
			return am.ik.rontolisp.SourceProvenance.inherit(call, call(helper, args.car()));
		}
		return null;
	}

	private static LispCons call(String op, LispVal arg) {
		return new LispCons(new LispSymbol(op), new LispCons(arg, LispNil.INSTANCE));
	}

	// found: {fboundp / (setf fdefinition), fdefinition, fmakunbound}
	private static void scanSites(LispVal form, boolean[] found) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head) {
			String op = head.name();
			if (LispNames.QUOTE.equals(op) || LispNames.FUNCTION.equals(op)) {
				// 'fboundp / #'fboundp: the operator as a function value.
				if (cons.cdr() instanceof LispCons arg && arg.car() instanceof LispSymbol named
						&& FUNCTION_NAME_OPERATORS.contains(named.name())) {
					mark(named.name(), found);
				}
				return;
			}
			if (FUNCTION_NAME_OPERATORS.contains(op) && cons.cdr() instanceof LispCons arg
					&& mayBeComputedName(arg.car())) {
				mark(op, found);
			}
		}
		LispVal rest = cons;
		while (rest instanceof LispCons cell) {
			scanSites(cell.car(), found);
			rest = cell.cdr();
		}
	}

	private static void mark(String op, boolean[] found) {
		found[LispNames.FDEFINITION.equals(op) ? 1 : LispNames.FMAKUNBOUND.equals(op) ? 2 : 0] = true;
	}

	/**
	 * Whether the run time can hold the symbol {@code SETF} as data: quoted in the
	 * program, a {@code "SETF"} string in a program with a symbol builder, or any name at
	 * all through a data evaluator.
	 */
	private static boolean setfConstructible(List<LispVal> program) {
		boolean setfString = false;
		for (LispVal form : program) {
			int found = scanSetfData(form, false);
			if (found == 2) {
				return true;
			}
			setfString |= found == 1;
		}
		return setfString && RuntimeNameProducers.anySymbolBuilder(program)
				|| RuntimeNameProducers.anyNameResolvable(program);
	}

	// 2: SETF quoted; 1: a "SETF" string; 0: neither.
	private static int scanSetfData(LispVal form, boolean quoted) {
		if (form instanceof LispSymbol sym) {
			return quoted && isSetf(sym.name()) ? 2 : 0;
		}
		if (form instanceof LispString str) {
			return LispNames.SETF.equals(str.value()) ? 1 : 0;
		}
		if (!(form instanceof LispCons cons)) {
			return 0;
		}
		if (!quoted && cons.car() instanceof LispSymbol op && FUNCTION_NAME_OPERATORS.contains(op.name())
				&& cons.cdr() instanceof LispCons arg && arg.cdr() instanceof LispNil
				&& arg.car() instanceof LispCons quote && quote.car() instanceof LispSymbol q
				&& LispNames.QUOTE.equals(q.name())) {
			// (fboundp '(setf place)): mapped when the program compiles, its list never
			// reaching the run time as data.
			return 0;
		}
		boolean inner = quoted || cons.car() instanceof LispSymbol head && LispNames.isQuote(head.name());
		int found = 0;
		LispVal rest = cons;
		while (rest instanceof LispCons cell) {
			found = Math.max(found, scanSetfData(cell.car(), inner));
			if (found == 2) {
				return 2;
			}
			rest = cell.cdr();
		}
		return Math.max(found, scanSetfData(rest, inner));
	}

	private static boolean isSetf(String name) {
		if (LispNames.SETF.equals(name)) {
			return true;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn != null && LispNames.SETF.equals(qn.member());
	}

}
