package am.ik.rontolisp.macro;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import org.jspecify.annotations.Nullable;

/**
 * The last argument of a standard lambda list that rontolisp accepts and ignores: the
 * {@code recursive-p} of {@code read-char}, {@code read-char-no-hang}, {@code read-line}
 * and {@code peek-char} (only a reader macro's recursive read consults it), and the
 * {@code environment} of {@code subtypep} (there is one global environment). The call
 * shape ({@code compiler/BuiltinCallArity}) admits the argument, so every compile-path
 * consumer that judges one of these calls by its argument count -- the lowerings, the
 * call-site rewrites in front of them, the scans that decide what a program carries --
 * reads the call through this class and is never narrower than the shape. The
 * interpreter's bodies take the argument and ignore it.
 */
public final class IgnoredArgument {

	/** The argument's position in the call form, operator at 0. */
	private static final Map<String, Integer> POSITIONS = Map.of(LispNames.READ_CHAR, 4, LispNames.READ_CHAR_NO_HANG, 4,
			LispNames.READ_LINE, 4, LispNames.PEEK_CHAR, 5, LispNames.SUBTYPEP, 3);

	private IgnoredArgument() {
	}

	/**
	 * {@link #drop(LispCons, String)} under the call's own operator name.
	 * @param call the call
	 * @return the replacement form, or {@code call} itself
	 */
	public static LispVal drop(LispCons call) {
		return call.car() instanceof LispSymbol head ? drop(call, head.name()) : call;
	}

	/**
	 * The form a call passing the ignored argument evaluates instead: the call without
	 * it, the argument still evaluated after every argument before it and before the
	 * operator runs, as CL evaluates it. An argument with no effect is dropped outright;
	 * otherwise it rides in front of the call when every other argument is a constant,
	 * and in a {@code prog1} behind the argument before it when one is not, so neither
	 * order nor any literal a lowering folds changes for the common spelling.
	 *
	 * <pre>
	 * (read-char s nil :eof nil)    -&gt; (read-char s nil :eof)
	 * (read-char nil nil :eof (f))  -&gt; (progn (f) (read-char nil nil :eof))
	 * (read-char s nil :eof (f))    -&gt; (read-char s nil (prog1 :eof (f)))
	 * </pre>
	 * @param call the call
	 * @param operator the operator's canonical name (a pass that matches the member name
	 * of a qualified head passes that member)
	 * @return the replacement form, or {@code call} itself when it is not such a call or
	 * does not pass the argument
	 */
	public static LispVal drop(LispCons call, String operator) {
		List<LispVal> parts = passing(call, operator);
		if (parts == null) {
			return call;
		}
		int position = parts.size() - 1;
		LispVal ignored = parts.get(position);
		List<LispVal> kept = new ArrayList<>(parts.subList(0, position));
		if (isInert(ignored)) {
			return LispCons.rebuiltList(call, kept);
		}
		if (kept.subList(1, kept.size()).stream().allMatch(IgnoredArgument::isInert)) {
			return SourceProvenance.inherit(call,
					list(List.of(new LispSymbol(LispNames.PROGN), ignored, LispCons.rebuiltList(call, kept))));
		}
		kept.set(position - 1, list(List.of(new LispSymbol(LispNames.PROG1), kept.get(position - 1), ignored)));
		return LispCons.rebuiltList(call, kept);
	}

	/**
	 * The call without the ignored argument, for a judgment of its shape that evaluates
	 * nothing (a scan deciding what the program carries): the argument is gone, not
	 * moved.
	 * @param call the call
	 * @param operator the operator's canonical name
	 * @return the call without the argument, or {@code call} itself
	 */
	public static LispCons withoutArgument(LispCons call, String operator) {
		List<LispVal> parts = passing(call, operator);
		return parts == null ? call : (LispCons) LispCons.rebuiltList(call, parts.subList(0, parts.size() - 1));
	}

	// The call's elements when it is one of the operators passing the argument, else
	// null.
	private static @Nullable List<LispVal> passing(LispCons call, String operator) {
		Integer position = POSITIONS.get(operator);
		if (position == null || !call.isProperList()) {
			return null;
		}
		List<LispVal> parts = call.toList();
		return parts.size() == position + 1 ? parts : null;
	}

	/**
	 * Whether a form's evaluation has no effect and cannot fail: a self-evaluating
	 * literal or a quoted datum. A variable is not one -- its read can fail, and it may
	 * be what an argument after it changes.
	 * @param form the form
	 * @return whether evaluating it can be skipped or moved
	 */
	static boolean isInert(LispVal form) {
		return switch (form) {
			case LispNil ignored -> true;
			case LispTrue ignored -> true;
			case LispString ignored -> true;
			case LispInteger ignored -> true;
			case LispBigInteger ignored -> true;
			case LispDouble ignored -> true;
			case LispRatio ignored -> true;
			case LispChar ignored -> true;
			case LispSymbol sym -> sym.isKeyword() || "NIL".equals(sym.name()) || "T".equals(sym.name());
			case LispCons cons -> cons.car() instanceof LispSymbol head && LispNames.isQuote(head.name());
			default -> false;
		};
	}

	private static LispVal list(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

}
