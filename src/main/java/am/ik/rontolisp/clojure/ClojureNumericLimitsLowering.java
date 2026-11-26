package am.ik.rontolisp.clojure;

import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The boxed limits a program names as {@code Long/MAX_VALUE}, {@code Integer/MIN_VALUE}
 * and their eight siblings, and the double limits a program names as
 * {@code Double/MAX_VALUE}, {@code Double/POSITIVE_INFINITY} and their three siblings,
 * where the host is not (wasm): the limit itself, so malli's {@code +max-size+}
 * {@code def} and test.check's double-generator bounds loads -- never a
 * {@code java:field}, which wasm refuses. The values are exact on every backend (plain
 * integers and doubles), so where the host is the field read stays untouched. Only these
 * thirteen constants lower this way; any other member stays the host's own.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojureNumericLimitsLowering {

	private ClojureNumericLimitsLowering() {
	}

	/**
	 * A boxed-integer limit as itself.
	 * @param cls the class
	 * @param member the field
	 * @return the value form, or null when this is no such field
	 */
	static @Nullable LispVal field(String cls, String member) {
		return switch (cls) {
			case "java.lang.Byte" -> switch (member) {
				case "MIN_VALUE" -> new LispInteger(Byte.MIN_VALUE);
				case "MAX_VALUE" -> new LispInteger(Byte.MAX_VALUE);
				default -> null;
			};
			case "java.lang.Short" -> switch (member) {
				case "MIN_VALUE" -> new LispInteger(Short.MIN_VALUE);
				case "MAX_VALUE" -> new LispInteger(Short.MAX_VALUE);
				default -> null;
			};
			case "java.lang.Integer" -> switch (member) {
				case "MIN_VALUE" -> new LispInteger(Integer.MIN_VALUE);
				case "MAX_VALUE" -> new LispInteger(Integer.MAX_VALUE);
				default -> null;
			};
			case "java.lang.Long" -> switch (member) {
				case "MIN_VALUE" -> new LispInteger(Long.MIN_VALUE);
				case "MAX_VALUE" -> new LispInteger(Long.MAX_VALUE);
				default -> null;
			};
			case "java.lang.Double" -> switch (member) {
				case "MIN_VALUE" -> new LispDouble(Double.MIN_VALUE);
				case "MAX_VALUE" -> new LispDouble(Double.MAX_VALUE);
				case "POSITIVE_INFINITY" -> new LispDouble(Double.POSITIVE_INFINITY);
				case "NEGATIVE_INFINITY" -> new LispDouble(Double.NEGATIVE_INFINITY);
				case "NaN" -> new LispDouble(Double.NaN);
				default -> null;
			};
			default -> null;
		};
	}

}
