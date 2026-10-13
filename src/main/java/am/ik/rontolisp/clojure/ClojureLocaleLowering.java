package am.ik.rontolisp.clojure;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The {@code java.util.Locale} a program names as {@code Locale/US}, {@code Locale/ROOT}
 * or {@code Locale/ENGLISH} ({@code .kb/clojure-frontend.md}, "Case mapping"): the
 * locale's own {@code toString} text ({@code "en_US"}, {@code ""}, {@code "en"}), so
 * {@code str} and {@code .toString} answer what the oracle answers, on every backend --
 * never a {@code java:field}, which wasm refuses. The case-mapping verbs ignore it (the
 * mapping is locale-independent but for tr/az/lt, which nothing here models), evaluating
 * it for its effects; anything else reads it as the string it is. Only these three
 * constants lower this way; any other member stays the host's own.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojureLocaleLowering {

	private static final String LOCALE = "java.util.Locale";

	private ClojureLocaleLowering() {
	}

	/**
	 * A {@code java.util.Locale} constant as its {@code toString} text.
	 * @param cls the class
	 * @param member the field
	 * @return the value form, or null when this is no such field
	 */
	static @Nullable LispVal field(String cls, String member) {
		if (!cls.equals(LOCALE)) {
			return null;
		}
		return switch (member) {
			case "US" -> LispString.literal("en_US");
			case "ROOT" -> LispString.literal("");
			case "ENGLISH" -> LispString.literal("en");
			default -> null;
		};
	}

}
