package am.ik.rontolisp.clojure;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The {@code java.nio.charset.Charset} a program names by a literal
 * ({@code .kb/clojure-frontend.md}, "Charset values"): the six {@code StandardCharsets}
 * fields and {@code Charset/forName} of a string literal. Each lowers to a value of this
 * front end's own on every backend -- a {@code clojure.java.io} value of the io family
 * ({@link ClojureIoLowering}), printed, named and compared like the host {@code Charset},
 * read by {@code .getBytes}, {@code String.} and the other charset readers -- never a
 * {@code java:field}, which wasm refuses. It crosses into a {@code java:} member as the
 * host {@code Charset} ({@code %clojure-io-host}), and an argument of one built for that
 * member alone is the host object at once ({@link #hostConstruction}). The name and the
 * implementing class are resolved while the program lowers, so a charset the runtime
 * encodes in no codec still prints and compares; {@code Charset/forName} of anything but
 * a literal stays the host's own static.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojureCharsetLowering {

	/**
	 * {@code (%clojure-io-charset-value name class)}: the value, a producer of the io
	 * family.
	 */
	static final String VALUE = "RONTOLISP::%CLOJURE-IO-CHARSET-VALUE";

	private static final String STANDARD = "java.nio.charset.StandardCharsets";

	private static final String CHARSET = "java.nio.charset.Charset";

	private ClojureCharsetLowering() {
	}

	/**
	 * A {@code StandardCharsets} field as the value.
	 * @param cls the class
	 * @param member the field
	 * @return the value form, or null when this is no such field
	 */
	static @Nullable LispVal field(String cls, String member) {
		if (!cls.equals(STANDARD)) {
			return null;
		}
		Charset charset = switch (member) {
			case "UTF_8" -> StandardCharsets.UTF_8;
			case "ISO_8859_1" -> StandardCharsets.ISO_8859_1;
			case "US_ASCII" -> StandardCharsets.US_ASCII;
			case "UTF_16" -> StandardCharsets.UTF_16;
			case "UTF_16BE" -> StandardCharsets.UTF_16BE;
			case "UTF_16LE" -> StandardCharsets.UTF_16LE;
			default -> null;
		};
		return charset == null ? null : value(charset);
	}

	/**
	 * {@code (Charset/forName "literal")}: the value, or the oracle's refusal for a name
	 * it rejects.
	 * @param cls the class
	 * @param member the method
	 * @param args the lowered arguments
	 * @return the form, or null when this is no such call
	 */
	static @Nullable LispVal forName(String cls, String member, List<LispVal> args) {
		if (!cls.equals(CHARSET) || !member.equals("forName") || args.size() != 1
				|| !(args.get(0) instanceof LispString name)) {
			return null;
		}
		try {
			return value(Charset.forName(name.value()));
		}
		catch (IllegalCharsetNameException e) {
			return ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_CHARSET_NAME, LispString.literal(name.value()));
		}
		catch (UnsupportedCharsetException e) {
			return ClojureRefusals.refusal(ClojureRefusals.UNSUPPORTED_CHARSET, LispString.literal(name.value()));
		}
	}

	/**
	 * The host {@code Charset} a value made here stands for, when the form is one.
	 * @param form the lowered argument of a {@code java:} member
	 * @return {@code (java:static "...Charset" "forName" name)}, or null
	 */
	static @Nullable LispVal hostConstruction(LispVal form) {
		List<LispVal> items = ClojureLowerUtil.items(form);
		if (items == null || !isValue(form)) {
			return null;
		}
		return ClojureLowerUtil.list(ClojureInteropLowering.JAVA_STATIC, LispString.literal(CHARSET),
				LispString.literal("forName"), items.get(1));
	}

	/**
	 * Whether a lowered form makes a charset value.
	 * @param form the form
	 * @return whether it is a call of {@link #VALUE}
	 */
	static boolean isValue(LispVal form) {
		List<LispVal> items = ClojureLowerUtil.items(form);
		return items != null && items.size() == 3 && ClojureLowerUtil.isSymbolNamed(items.get(0), VALUE);
	}

	private static LispVal value(Charset charset) {
		return ClojureLowerUtil.list(new LispSymbol(VALUE), LispString.literal(charset.name()),
				LispString.literal(charset.getClass().getName()));
	}

}
