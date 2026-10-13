package am.ik.rontolisp.clojure;

import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The boxed-integer limits a program names as {@code Long/MAX_VALUE},
 * {@code Integer/MIN_VALUE} and their six siblings, where the host is not (wasm): the
 * limit itself, so malli's {@code +max-size+} {@code def} loads -- never a
 * {@code java:field}, which wasm refuses. The values are exact on every backend (plain
 * integers), so where the host is the field read stays untouched. Only these eight
 * constants lower this way; any other member stays the host's own.
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
			default -> null;
		};
	}

}
