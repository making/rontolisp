package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;

/**
 * The function value of a special operator or a macro on the compile paths: CLHS
 * {@code symbol-function} / {@code fdefinition} make such a name fbound, with an object
 * of implementation-dependent nature. Here it is the prelude's
 * {@code %operator-function}, one function per name that signals
 * {@code undefined-function} when called, and nil for a name that is neither -- decided
 * by {@code special-operator-p} and {@code macro-function}, so the three answers cannot
 * disagree.
 *
 * <p>
 * The backends ask it where a name built at run time MISSES the compiled-function
 * registry ({@code fboundp}, {@code symbol-function}; {@code fdefinition} lowers to the
 * latter), so a name that is a function pays nothing. The miss arm evaluates the operand
 * again, so a site whose operand is not a variable is first bound to one
 * ({@link #throughVariable}). A literal name {@code UserMacroExpander} lowers to the call
 * itself, since only it knows the program's macros.
 */
public final class OperatorFunctions {

	/** The operators whose computed operand the miss arm asks about. */
	private static final Set<String> ASKING_OPERATORS = Set.of(LispNames.FBOUNDP, LispNames.SYMBOL_FUNCTION,
			LispNames.FDEFINITION);

	/** The variable {@link #throughVariable} binds the operand to. */
	private static final String OPERAND = "%OPFN-NAME";

	private OperatorFunctions() {
	}

	/**
	 * Whether the program can reach {@code %operator-function}: a computed operand of
	 * {@code fboundp}, {@code symbol-function} or {@code fdefinition}, one of them (or
	 * {@code compile}, whose run time calls {@code fdefinition}) as a function value, or
	 * a call of the helper itself -- what selects the prelude entry.
	 * @param program the top-level forms
	 * @return whether the helper must be spliced
	 */
	public static boolean reached(List<LispVal> program) {
		for (LispVal form : program) {
			if (reaches(form)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code (%operator-function operand)}.
	 * @param operand the form naming the operator
	 * @return the call
	 */
	public static LispCons call(LispVal operand) {
		return new LispCons(new LispSymbol(LispNames.OPERATOR_FUNCTION_INTERNAL),
				new LispCons(operand, LispNil.INSTANCE));
	}

	/**
	 * {@code (let ((%opfn-name operand)) (op %opfn-name))} for a one-operand call whose
	 * operand is a form computing the name, in a program that defines the helper; null
	 * otherwise, and the call compiles as it stands, its miss arm asking about a variable
	 * operand.
	 * @param call the {@code fboundp} or {@code symbol-function} call
	 * @param helperDefined whether the program defines {@code %operator-function}
	 * @return the rewritten call, or null
	 */
	public static @Nullable LispVal throughVariable(LispCons call, boolean helperDefined) {
		if (!helperDefined || !(call.cdr() instanceof LispCons args) || !(args.cdr() instanceof LispNil)
				|| !(args.car() instanceof LispCons operand) || operand.car() instanceof LispSymbol head
						&& (LispNames.isQuote(head.name()) || LispNames.FUNCTION.equals(head.name()))) {
			return null;
		}
		LispSymbol variable = new LispSymbol(OPERAND);
		LispVal binding = new LispCons(new LispCons(variable, new LispCons(args.car(), LispNil.INSTANCE)),
				LispNil.INSTANCE);
		LispVal body = new LispCons(call.car(), new LispCons(variable, LispNil.INSTANCE));
		return SourceProvenance.inherit(call, new LispCons(new LispSymbol(LispNames.LET),
				new LispCons(binding, new LispCons(body, LispNil.INSTANCE))));
	}

	/**
	 * The operand the miss arm of a computed call asks about: the variable it names, in a
	 * program that defines the helper; null otherwise.
	 * @param operand the call's operand
	 * @param helperDefined whether the program defines {@code %operator-function}
	 * @return the variable, or null
	 */
	public static @Nullable LispSymbol askedOperand(LispVal operand, boolean helperDefined) {
		return helperDefined && operand instanceof LispSymbol variable && !variable.name().startsWith(":") ? variable
				: null;
	}

	private static boolean reaches(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol head) {
			String op = head.name();
			if (LispNames.isQuote(op) || LispNames.FUNCTION.equals(op)) {
				// 'fboundp / #'symbol-function: the operator as a function value, whose
				// wrapper's operand is computed.
				return cons.cdr() instanceof LispCons arg && arg.car() instanceof LispSymbol named
						&& (ASKING_OPERATORS.contains(named.name()) || LispNames.COMPILE.equals(named.name()));
			}
			if (LispNames.OPERATOR_FUNCTION_INTERNAL.equals(op) || LispNames.COMPILE.equals(op)) {
				return true;
			}
			if (ASKING_OPERATORS.contains(op) && cons.cdr() instanceof LispCons arg && !isQuotedSymbol(arg.car())) {
				return true;
			}
		}
		LispVal rest = cons;
		while (rest instanceof LispCons cell) {
			if (reaches(cell.car())) {
				return true;
			}
			rest = cell.cdr();
		}
		return false;
	}

	private static boolean isQuotedSymbol(LispVal form) {
		return form instanceof LispCons quote && quote.car() instanceof LispSymbol head
				&& LispNames.QUOTE.equals(head.name()) && quote.cdr() instanceof LispCons datum
				&& datum.car() instanceof LispSymbol;
	}

}
