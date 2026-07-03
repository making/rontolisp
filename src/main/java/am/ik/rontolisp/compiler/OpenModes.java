package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Resolves the literal {@code open} arguments to a compile-time file mode shared by the
 * JVM and WASM compilers. The form is {@code (open path [direction [element-type]])}
 * where the direction must be the literal {@code :input} (default) or {@code :output}
 * keyword and the element type must be the literal {@code '(unsigned-byte 8)} (binary) or
 * {@code 'character} (text, default). The mode encoding is {@code 0} = text input,
 * {@code 1} = text output, {@code 2} = binary input, {@code 3} = binary output.
 */
public final class OpenModes {

	/** Bit set in the mode when the stream is opened for output. */
	public static final int OUTPUT_BIT = 1;

	/** Bit set in the mode when the stream is binary ({@code '(unsigned-byte 8)}). */
	public static final int BINARY_BIT = 2;

	private OpenModes() {
	}

	/**
	 * Resolves the literal direction and element-type arguments to the file mode.
	 * @param parts the open form parts
	 * @return the file mode (0 = text input, 1 = text output, 2 = binary input, 3 =
	 * binary output)
	 */
	public static int staticMode(List<LispVal> parts) {
		if (parts.size() < 3) {
			return 0;
		}
		int mode;
		if (parts.get(2) instanceof LispSymbol dir && LispNames.INPUT_KEYWORD.equals(dir.name())) {
			mode = 0;
		}
		else if (parts.get(2) instanceof LispSymbol dir && LispNames.OUTPUT_KEYWORD.equals(dir.name())) {
			mode = OUTPUT_BIT;
		}
		else {
			throw new UnsupportedOperationException("open requires a literal :input or :output direction");
		}
		if (parts.size() > 3) {
			if (isBinaryElementType(unquote(parts.get(3)))) {
				mode |= BINARY_BIT;
			}
		}
		return mode;
	}

	/**
	 * Strips a literal {@code (quote x)} wrapper, leaving the type specifier form.
	 * @param val the element-type argument as it appears in the source
	 * @return the quoted form, or the value itself when not a quote form
	 */
	private static LispVal unquote(LispVal val) {
		if (val instanceof LispCons cons) {
			List<LispVal> list = cons.toList();
			if (list.size() == 2 && list.get(0) instanceof LispSymbol sym && LispNames.QUOTE.equals(sym.name())) {
				return list.get(1);
			}
		}
		return val;
	}

	/**
	 * Classifies an element type specifier: {@code (unsigned-byte 8)} is binary,
	 * {@code character} is text, anything else is rejected.
	 * @param spec the unquoted type specifier
	 * @return true for the binary element type
	 */
	public static boolean isBinaryElementType(LispVal spec) {
		if (spec instanceof LispSymbol sym && LispNames.CHARACTER_TYPE.equals(sym.name())) {
			return false;
		}
		if (spec instanceof LispCons cons) {
			List<LispVal> list = cons.toList();
			if (list.size() == 2 && list.get(0) instanceof LispSymbol sym && LispNames.UNSIGNED_BYTE.equals(sym.name())
					&& list.get(1) instanceof LispInteger bits && bits.value() == 8) {
				return true;
			}
		}
		throw new UnsupportedOperationException(
				"open requires a literal 'character or '(unsigned-byte 8) element type");
	}

}
