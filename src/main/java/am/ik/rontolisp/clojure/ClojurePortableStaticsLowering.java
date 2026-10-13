package am.ik.rontolisp.clojure;

import java.util.List;

import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The pure number statics data.json's read path calls ({@code .kb/clojure-frontend.md},
 * "Portable number statics"): {@code Long/valueOf} and {@code Double/valueOf} of one
 * argument, {@code Integer/parseInt} of a string and a literal radix 16,
 * {@code Integer/toHexString} of one argument and {@code Math/min} of two. Where the host
 * is not (wasm) each lowers to its portable worker in {@code clojure.lisp} -- never a
 * {@code java:static}, which wasm refuses; where the host is each stays the host's call.
 * Each worker is measured against clj 1.12.6, {@code Long/valueOf}'s grammar being
 * {@code parse-long}'s and {@code Double/valueOf}'s {@code parse-double}'s.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojurePortableStaticsLowering {

	/** {@code (Long/valueOf s)}. */
	static final String LONG_VALUE_OF = "RONTOLISP::%CLOJURE-LONG-VALUE-OF";

	/** {@code (Double/valueOf s)}. */
	static final String DOUBLE_VALUE_OF = "RONTOLISP::%CLOJURE-DOUBLE-VALUE-OF";

	/** {@code (Integer/parseInt s 16)}. */
	static final String PARSE_INT_16 = "RONTOLISP::%CLOJURE-PARSE-INT-16";

	/** {@code (Integer/toHexString x)}. */
	static final String TO_HEX_STRING = "RONTOLISP::%CLOJURE-INTEGER-TO-HEX-STRING";

	/** {@code (Math/min a b)}. */
	static final String MATH_MIN = "RONTOLISP::%CLOJURE-MATH-MIN";

	/** {@code infinite?} over a double: what {@code .isInfinite} answers. */
	static final String IS_INFINITE = "RONTOLISP::%CLOJURE-IS-INFINITE";

	/** {@code NaN?} over a double: what {@code .isNaN} answers. */
	static final String IS_NAN = "RONTOLISP::%CLOJURE-IS-NAN";

	private ClojurePortableStaticsLowering() {
	}

	/**
	 * A static call as its portable worker, where the host is not: the caller checks the
	 * target. Null for any other class, member or count -- and for
	 * {@code Integer/parseInt} of any radix but a literal 16, which stays the host's
	 * call.
	 * @param cls the resolved class name
	 * @param member the static member
	 * @param args the lowered arguments
	 * @return the call, or null
	 */
	static @Nullable LispVal staticCall(String cls, String member, List<LispVal> args) {
		if (cls.equals("java.lang.Long") && member.equals("valueOf") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(LONG_VALUE_OF), args.get(0));
		}
		if (cls.equals("java.lang.Double") && member.equals("valueOf") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(DOUBLE_VALUE_OF), args.get(0));
		}
		if (cls.equals("java.lang.Integer") && member.equals("parseInt") && args.size() == 2
				&& args.get(1) instanceof LispInteger radix && radix.value() == 16) {
			return ClojureLowerUtil.list(new LispSymbol(PARSE_INT_16), args.get(0));
		}
		if (cls.equals("java.lang.Integer") && member.equals("toHexString") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(TO_HEX_STRING), args.get(0));
		}
		if (cls.equals("java.lang.Math") && member.equals("min") && args.size() == 2) {
			return ClojureLowerUtil.list(new LispSymbol(MATH_MIN), args.get(0), args.get(1));
		}
		return null;
	}

	/**
	 * {@code .isInfinite} or {@code .isNaN} of no argument over an already-bound receiver
	 * of no known class: a double answers what {@code infinite?} or {@code NaN?} answers
	 * (data.json's float writers), as a Clojure boolean, ahead of {@code otherwise}.
	 * @param ctx the hub
	 * @param method the method name
	 * @param recv the bound receiver
	 * @param otherwise the form for any other receiver
	 * @return the form
	 */
	static LispVal doublePredicateArm(ClojureLowering ctx, String method, LispSymbol recv, LispVal otherwise) {
		LispVal raw = ClojureLowerUtil.list(new LispSymbol(method.equals("isInfinite") ? IS_INFINITE : IS_NAN), recv);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("floatp"), recv), ctx.booleanAnswer(raw), otherwise);
	}

}
