package am.ik.femtolisp.codegen.jvm;

/**
 * Runtime helper for JVM-compiled Lisp programs. Provides conversion from runtime value
 * representations to their display strings.
 *
 * <p>
 * Value representations:
 * <ul>
 * <li>{@code null} = nil</li>
 * <li>{@link Long} = integer</li>
 * <li>{@link String} = string literal (display form) or quoted symbol (bare name)</li>
 * <li>{@code Object[2]} = cons cell where [0]=car, [1]=cdr</li>
 * </ul>
 */
public final class LispRuntime {

	private LispRuntime() {
	}

	/**
	 * Converts a Lisp runtime value to its display string.
	 */
	public static String lispToString(Object val) {
		if (val == null) {
			return "nil";
		}
		if (val instanceof Long l) {
			return l.toString();
		}
		if (val instanceof String s) {
			return s;
		}
		if (val instanceof Object[] arr) {
			return consToString(arr);
		}
		return val.toString();
	}

	private static String consToString(Object[] cons) {
		StringBuilder sb = new StringBuilder("(");
		Object current = cons;
		boolean first = true;
		while (current instanceof Object[] c) {
			if (!first) {
				sb.append(' ');
			}
			sb.append(lispToString(c[0]));
			current = c[1];
			first = false;
		}
		if (current != null) {
			sb.append(" . ");
			sb.append(lispToString(current));
		}
		sb.append(')');
		return sb.toString();
	}

}
