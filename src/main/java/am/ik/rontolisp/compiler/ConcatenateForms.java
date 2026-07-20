package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Canonicalizes {@code (concatenate 'string args...)} into nested binary
 * {@code %string-concat} calls for the JVM/WASM compilers. The result type must be the
 * literal {@code 'string}: the interpreter evaluates the type designator at runtime, but
 * a compiler has to resolve it statically.
 */
public final class ConcatenateForms {

	private ConcatenateForms() {
	}

	/**
	 * Expands (concatenate 'string args...) into nested %string-concat calls.
	 *
	 * <pre>
	 * (concatenate 'string)         -> ""
	 * (concatenate 'string a)       -> (%string-concat a "")
	 * (concatenate 'string a b c)   -> (%string-concat (%string-concat a b) c)
	 * </pre>
	 * @param cons the concatenate expression
	 * @return the expanded expression
	 */
	public static LispVal expand(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || !isStringResultType(parts.get(1))) {
			throw new UnsupportedOperationException(
					"Cannot compile concatenate: only a literal 'string result type is supported");
		}
		List<LispVal> args = parts.subList(2, parts.size());
		if (args.isEmpty()) {
			return new LispString("");
		}
		// A single argument is concatenated with "" so the result is always a string.
		LispVal acc = (args.size() == 1) ? concatCall(args.get(0), new LispString("")) : args.get(0);
		for (int i = 1; i < args.size(); i++) {
			acc = concatCall(acc, args.get(i));
		}
		return acc;
	}

	private static boolean isStringResultType(LispVal type) {
		if (!(type instanceof LispCons quoted)) {
			return false;
		}
		List<LispVal> quoteParts = quoted.toList();
		return quoteParts.size() == 2 && quoteParts.get(0) instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& quoteParts.get(1) instanceof LispSymbol s && "STRING".equals(s.name());
	}

	private static LispVal concatCall(LispVal a, LispVal b) {
		LispVal result = LispNil.INSTANCE;
		result = new LispCons(b, result);
		result = new LispCons(a, result);
		return new LispCons(new LispSymbol(LispNames.STRING_CONCAT), result);
	}

}
