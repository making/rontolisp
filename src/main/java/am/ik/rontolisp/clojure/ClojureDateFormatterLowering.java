package am.ik.rontolisp.clojure;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The {@code java.time.format.DateTimeFormatter/ISO_INSTANT} field data.json's defaults
 * read ({@code .kb/clojure-frontend.md}, "The ISO_INSTANT formatter"): where the host is
 * not (wasm) it lowers to a portable value -- a tag no other value reads, which
 * {@code .format} answers over an instant of this front end's own in ISO-8601 -- never a
 * {@code java:field}, which wasm refuses at load; where the host is the field stays the
 * host's object and {@code .format} its call. Measured against clj 1.12.6.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojureDateFormatterLowering {

	/** The class the field is read off. */
	static final String FORMATTER = "java.time.format.DateTimeFormatter";

	/** The field this lowers. */
	static final String ISO_INSTANT = "ISO_INSTANT";

	/** The portable value. */
	static final String MAKE = "RONTOLISP::%CLOJURE-ISO-INSTANT-FORMATTER";

	/** Whether a value is the portable one. */
	static final String FORMATTER_P = "RONTOLISP::%CLOJURE-ISO-INSTANT-FORMATTER-P";

	/** {@code .format} of the portable value. */
	static final String FORMAT = "RONTOLISP::%CLOJURE-ISO-INSTANT-FORMAT";

	private ClojureDateFormatterLowering() {
	}

	/**
	 * The {@code ISO_INSTANT} field as the portable value, where the host is not: the
	 * caller checks the target.
	 * @param cls the class
	 * @param member the field
	 * @return the value form, or null when this is no such field
	 */
	static @Nullable LispVal field(String cls, String member) {
		if (!cls.equals(FORMATTER) || !member.equals(ISO_INSTANT)) {
			return null;
		}
		return ClojureLowerUtil.list(new LispSymbol(MAKE));
	}

	/**
	 * {@code .format} of one argument over an already-bound receiver of no known class:
	 * the portable value formats an instant of this front end's own (data.json's
	 * {@code write-instant}), nil and anything else are refused in the oracle's words,
	 * ahead of {@code otherwise}.
	 * @param recv the bound receiver
	 * @param arg the lowered argument
	 * @param otherwise the form for any other receiver
	 * @return the form
	 */
	static LispVal formatArm(LispSymbol recv, LispVal arg, LispVal otherwise) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(FORMATTER_P), recv),
				ClojureLowerUtil.list(new LispSymbol(FORMAT), recv, arg), otherwise);
	}

}
