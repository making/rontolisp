package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The pure number statics data.json's read path calls ({@code .kb/clojure-frontend.md},
 * "Portable number statics"): {@code Long/valueOf} and {@code Double/valueOf} of one
 * argument, {@code Long/parseLong} of one argument (malli's {@code -string->long}, which
 * the string-transformer decode reads), {@code Integer/parseInt} of a string and a
 * literal radix 16, {@code Integer/toHexString} of one argument, {@code Math/min} of two
 * and {@code Math/floor}, {@code Math/ceil} and {@code Math/log} of one argument
 * (data.generators' {@code uniform} and {@code geometric}), {@code Math/abs} of one
 * argument (test.check's {@code shrink-long} and generators), {@code Long/bitCount} of
 * one argument (test.check's {@code mix-gamma}, every split) and
 * {@code Long/numberOfLeadingZeros} of one argument (test.check's {@code shrink-long}),
 * and what tools.reader's read path calls: {@code Integer/parseInt} of one argument (any
 * literal int radix beside it), {@code Double/parseDouble},
 * {@code Character/isWhitespace}, {@code isDigit}, {@code digit} and {@code valueOf}, and
 * a {@code BigInteger} construction of a string, a byte array or either with its radix or
 * signum ({@link #construction}). Where the host is not (wasm) each lowers to its
 * portable worker in {@code clojure.lisp} -- never a {@code java:static}, which wasm
 * refuses; where the host is each stays the host's call. Each worker is measured against
 * clj 1.12.6, {@code Long/valueOf}'s and {@code Long/parseLong}'s grammar being
 * {@code parse-long}'s and {@code Double/valueOf}'s {@code parse-double}'s.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojurePortableStaticsLowering {

	/** {@code (Long/valueOf s)}. */
	static final String LONG_VALUE_OF = "RONTOLISP::%CLOJURE-LONG-VALUE-OF";

	/** {@code (Long/parseLong s)} of one argument. */
	static final String PARSE_LONG = "RONTOLISP::%CLOJURE-PARSE-LONG-STATIC";

	/** {@code (Double/valueOf s)}. */
	static final String DOUBLE_VALUE_OF = "RONTOLISP::%CLOJURE-DOUBLE-VALUE-OF";

	/** {@code (Integer/parseInt s)} and {@code (Integer/parseInt s radix)}. */
	static final String PARSE_INT = "RONTOLISP::%CLOJURE-PARSE-INT";

	/** {@code (Double/parseDouble s)}. */
	static final String PARSE_DOUBLE = "RONTOLISP::%CLOJURE-PARSE-DOUBLE-STATIC";

	/** {@code (Character/isWhitespace x)}. */
	static final String IS_WHITESPACE = "RONTOLISP::%CLOJURE-CHARACTER-IS-WHITESPACE";

	/** {@code (Character/isDigit x)}. */
	static final String IS_DIGIT = "RONTOLISP::%CLOJURE-CHARACTER-IS-DIGIT";

	/** {@code (Character/digit x radix)}. */
	static final String DIGIT = "RONTOLISP::%CLOJURE-CHARACTER-DIGIT";

	/** {@code (Character/valueOf x)}. */
	static final String CHARACTER_VALUE_OF = "RONTOLISP::%CLOJURE-CHARACTER-VALUE-OF";

	/** {@code (BigInteger. s-or-bytes)}. */
	static final String BIG_INTEGER_NEW_1 = "RONTOLISP::%CLOJURE-BIG-INTEGER-NEW-1";

	/** {@code (BigInteger. s radix)} and {@code (BigInteger. signum bytes)}. */
	static final String BIG_INTEGER_NEW = "RONTOLISP::%CLOJURE-BIG-INTEGER-NEW";

	/** {@code (Integer/toHexString x)}. */
	static final String TO_HEX_STRING = "RONTOLISP::%CLOJURE-INTEGER-TO-HEX-STRING";

	/** {@code (Math/min a b)}. */
	static final String MATH_MIN = "RONTOLISP::%CLOJURE-MATH-MIN";

	/** {@code (Math/floor x)}. */
	static final String MATH_FLOOR = "RONTOLISP::%CLOJURE-MATH-FLOOR";

	/** {@code (Math/ceil x)}. */
	static final String MATH_CEIL = "RONTOLISP::%CLOJURE-MATH-CEIL";

	/** {@code (Math/log x)}. */
	static final String MATH_LOG = "RONTOLISP::%CLOJURE-MATH-LOG";

	/** {@code (Math/abs x)}. */
	static final String MATH_ABS = "RONTOLISP::%CLOJURE-MATH-ABS";

	/** {@code (Long/bitCount x)}. */
	static final String LONG_BIT_COUNT = "RONTOLISP::%CLOJURE-LONG-BIT-COUNT";

	/** {@code (Long/numberOfLeadingZeros x)}. */
	static final String LONG_NLZ = "RONTOLISP::%CLOJURE-LONG-NUMBER-OF-LEADING-ZEROS";

	/** {@code infinite?} over a double: what {@code .isInfinite} answers. */
	static final String IS_INFINITE = "RONTOLISP::%CLOJURE-IS-INFINITE";

	/** {@code NaN?} over a double: what {@code .isNaN} answers. */
	static final String IS_NAN = "RONTOLISP::%CLOJURE-IS-NAN";

	private ClojurePortableStaticsLowering() {
	}

	/**
	 * A static call as its portable worker, where the host is not: the caller checks the
	 * target. Null for any other class, member or count -- and for
	 * {@code Integer/parseInt} of a computed radix or a literal past the int range, which
	 * stays the host's call.
	 * @param cls the resolved class name
	 * @param member the static member
	 * @param args the lowered arguments
	 * @return the call, or null
	 */
	static @Nullable LispVal staticCall(String cls, String member, List<LispVal> args) {
		if (cls.equals("java.lang.Long") && member.equals("valueOf") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(LONG_VALUE_OF), args.get(0));
		}
		if (cls.equals("java.lang.Long") && member.equals("parseLong") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(PARSE_LONG), args.get(0));
		}
		if (cls.equals("java.lang.Double") && member.equals("valueOf") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(DOUBLE_VALUE_OF), args.get(0));
		}
		if (cls.equals("java.lang.Integer") && member.equals("parseInt") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(PARSE_INT), args.get(0), new LispInteger(10));
		}
		if (cls.equals("java.lang.Integer") && member.equals("parseInt") && args.size() == 2
				&& args.get(1) instanceof LispInteger radix && radix.value() == (int) radix.value()) {
			return ClojureLowerUtil.list(new LispSymbol(PARSE_INT), args.get(0), radix);
		}
		if (cls.equals("java.lang.Double") && member.equals("parseDouble") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(PARSE_DOUBLE), args.get(0));
		}
		if (cls.equals("java.lang.Character")) {
			String worker = switch (member + "/" + args.size()) {
				case "isWhitespace/1" -> IS_WHITESPACE;
				case "isDigit/1" -> IS_DIGIT;
				case "digit/2" -> DIGIT;
				case "valueOf/1" -> CHARACTER_VALUE_OF;
				default -> null;
			};
			if (worker != null) {
				List<LispVal> call = new ArrayList<>();
				call.add(new LispSymbol(worker));
				call.addAll(args);
				return ClojureLowerUtil.list(call);
			}
		}
		if (cls.equals("java.lang.Integer") && member.equals("toHexString") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(TO_HEX_STRING), args.get(0));
		}
		if (cls.equals("java.lang.Math") && member.equals("min") && args.size() == 2) {
			return ClojureLowerUtil.list(new LispSymbol(MATH_MIN), args.get(0), args.get(1));
		}
		if (cls.equals("java.lang.Math") && member.equals("floor") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(MATH_FLOOR), args.get(0));
		}
		if (cls.equals("java.lang.Math") && member.equals("ceil") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(MATH_CEIL), args.get(0));
		}
		if (cls.equals("java.lang.Math") && member.equals("log") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(MATH_LOG), args.get(0));
		}
		if (cls.equals("java.lang.Math") && member.equals("abs") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(MATH_ABS), args.get(0));
		}
		if (cls.equals("java.lang.Long") && member.equals("bitCount") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(LONG_BIT_COUNT), args.get(0));
		}
		if (cls.equals("java.lang.Long") && member.equals("numberOfLeadingZeros") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(LONG_NLZ), args.get(0));
		}
		return null;
	}

	/**
	 * An untagged {@code java.math.BigInteger} construction of one or two arguments (a
	 * string, a string and a radix, a byte array, a signum and a byte array) as its
	 * portable reader, where the host is not: the caller checks the target. Null for any
	 * other class or count.
	 * @param cls the resolved class name
	 * @param args the lowered arguments
	 * @return the call, or null
	 */
	static @Nullable LispVal construction(String cls, List<LispVal> args) {
		if (!cls.equals("java.math.BigInteger") || args.isEmpty() || args.size() > 2) {
			return null;
		}
		return args.size() == 1 ? ClojureLowerUtil.list(new LispSymbol(BIG_INTEGER_NEW_1), args.get(0))
				: ClojureLowerUtil.list(new LispSymbol(BIG_INTEGER_NEW), args.get(0), args.get(1));
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
