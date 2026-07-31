package am.ik.rontolisp.compiler;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Common Lisp's stream DESIGNATOR rule as an AST rewrite the compile backends share: for
 * an output operation, an omitted stream argument AND an explicit {@code nil} both denote
 * {@code *standard-output*} (for an INPUT operation, {@code *standard-input*}), resolved
 * at the time of the operation; only {@code t} names the process standard stream.
 *
 * <p>
 * The omitted case is what {@code (with-output-to-string (*standard-output*) ...)} needs;
 * the explicit-nil case is what a forwarded optional needs --
 * {@code (defun emit (x &optional stream) (princ x stream))} called as {@code (emit x)}
 * passes nil down, and CL sends that to the CURRENT {@code *standard-output*}, not to raw
 * stdout. Because the argument arrives in a variable, the test cannot be hoisted: a
 * non-literal expression becomes {@code (or <expr> *standard-output*)}, which evaluates
 * it exactly once.
 *
 * <p>
 * Both backends apply this ONLY when the redirect is active (the variable has a global
 * cell because the program binds it somewhere -- see
 * {@code .kb/standard-output-redirect.md}); a program that never binds
 * {@code *standard-output*} keeps its hard-coded standard output and compiles
 * byte-identically to before.
 */
public final class StreamDesignators {

	private StreamDesignators() {
	}

	/** The {@code *standard-output*} read an omitted stream argument denotes. */
	public static LispVal standardOutput() {
		return new LispSymbol(LispNames.STANDARD_OUTPUT_VAR);
	}

	/**
	 * The destination expression of an output operation whose stream argument is
	 * {@code explicit} ({@code null} when the argument was omitted), for a program in
	 * which the {@code *standard-output*} redirect is active.
	 * @param explicit the stream argument expression, or {@code null} if omitted
	 * @return the expression to compile as the destination
	 */
	public static LispVal resolveOutput(@Nullable LispVal explicit) {
		if (explicit == null || explicit instanceof LispNil || isStandardOutputRead(explicit)) {
			return standardOutput();
		}
		if (explicit instanceof LispTrue || explicit instanceof LispInteger) {
			// A literal that can never be nil needs no test.
			return explicit;
		}
		return new LispCons(new LispSymbol(LispNames.OR),
				new LispCons(explicit, new LispCons(standardOutput(), LispNil.INSTANCE)));
	}

	/** The {@code *standard-input*} read an omitted stream argument denotes. */
	public static LispVal standardInput() {
		return new LispSymbol(LispNames.STANDARD_INPUT_VAR);
	}

	/**
	 * The source expression of an INPUT operation whose stream argument is
	 * {@code explicit} ({@code null} when the argument was omitted), for a program in
	 * which the {@code *standard-input*} redirect is active. The mirror of
	 * {@link #resolveOutput}: an omitted argument and an explicit nil both denote
	 * {@code *standard-input*}, and only {@code t} names the process standard input.
	 * @param explicit the stream argument expression, or {@code null} if omitted
	 * @return the expression to compile as the source
	 */
	public static LispVal resolveInput(@Nullable LispVal explicit) {
		if (explicit == null || explicit instanceof LispNil || isRead(explicit, LispNames.STANDARD_INPUT_VAR)) {
			return standardInput();
		}
		if (explicit instanceof LispTrue || explicit instanceof LispInteger) {
			// A literal that can never be nil needs no test.
			return explicit;
		}
		return new LispCons(new LispSymbol(LispNames.OR),
				new LispCons(explicit, new LispCons(standardInput(), LispNil.INSTANCE)));
	}

	private static boolean isStandardOutputRead(LispVal expr) {
		return isRead(expr, LispNames.STANDARD_OUTPUT_VAR);
	}

	private static boolean isRead(LispVal expr, String name) {
		return expr instanceof LispSymbol sym && name.equals(sym.name());
	}

}
