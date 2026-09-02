package am.ik.rontolisp.compiler;

import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Which forms are KNOWN, at compile time, to answer an immutable string -- never a
 * mutable character vector, never any other type.
 *
 * <p>
 * The compile backends accept a character vector wherever a string is wanted, so every
 * string-consuming site normalizes its argument first ({@code _charvec_to_str} on WASM,
 * {@code _strv} on the JVM). That normalizer is not small -- it walks an array header and
 * re-encodes code points, 653 bytes of WASM -- and it is pure overhead in front of a form
 * that cannot produce a character vector in the first place. A {@code (format t "~,2F")}
 * pulled the whole thing in for a value its own primitive had just built.
 *
 * <p>
 * The set is deliberately CONSERVATIVE and closed: each entry is a built-in whose every
 * return path constructs a fresh string. Answering true for something that can also
 * answer a character vector silently drops a normalization that the semantics need, so a
 * new entry is only earned by checking every backend's implementation of that operator --
 * "it usually returns a string" is not enough.
 *
 * <p>
 * The public {@code princ-to-string} / {@code prin1-to-string} / {@code write-to-string}
 * are deliberately NOT here: they are flipped producers ({@link MutableStringProducers})
 * whose compiled result is a mutable character vector. The entries are the INTERNAL piece
 * conversions the expander builds with ({@code %princ-piece} / {@code %prin1-piece}) and
 * the raw, print-object-free pair, which stay immutable.
 */
public final class StringValuedForms {

	private static final Set<String> ALWAYS_STRING = Set.of(LispNames.FIXED_DECIMAL, LispNames.STRING_CONCAT,
			LispNames.PRINC_PIECE_INTERNAL, LispNames.PRIN1_PIECE_INTERNAL, LispNames.PRINC_TO_STRING_RAW,
			LispNames.PRIN1_TO_STRING_RAW);

	private StringValuedForms() {
	}

	/**
	 * Whether {@code form} evaluates to an immutable string on every backend.
	 * @param form the argument form
	 * @return true when no character-vector normalization is needed in front of it
	 */
	public static boolean certainlyString(LispVal form) {
		if (form instanceof LispString) {
			return true;
		}
		return form instanceof LispCons call && call.car() instanceof LispSymbol head
				&& ALWAYS_STRING.contains(head.name());
	}

}
