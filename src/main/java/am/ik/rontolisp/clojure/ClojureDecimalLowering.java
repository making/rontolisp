package am.ik.rontolisp.clojure;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The decimals of the Clojure lowering, the oracle's {@code java.math.BigDecimal}: an
 * {@code M} literal, the numeric operators a decimal reaches, and the math context
 * {@code with-precision} binds. A decimal is {@code (:C%DECIMAL unscaled scale)}
 * ({@code clojure.lisp}, "Decimals"), a literal one quoted data, so a program making none
 * names neither the tag nor a decimal helper once the decimal family's arms are folded
 * ({@link ClojureArms.Family#DECIMAL}): every operator below lowers to its decimal-aware
 * helper, which the fold renames back to the plain operator, so such a program compiles
 * to the bytes it did before decimals existed.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureDecimalLowering {

	/**
	 * Marks a decimal literal, {@code (%decimal unscaled scale)}: what the reader
	 * answers.
	 */
	static final LispSymbol MARKER = new LispSymbol("%decimal");

	/** The tag of a decimal's wrapper; quoted data holding it makes a decimal. */
	static final String TAG = ":C%DECIMAL";

	/** The prefix of every definition the decimal family owns ({@code clojure.lisp}). */
	static final String PREFIX = "RONTOLISP::%CLOJURE-BD-";

	/** The family's test of a decimal. */
	static final String DECIMAL_P = PREFIX + "P";

	/** The family's test of a list holding a decimal. */
	static final String DECIMAL_IN_P = PREFIX + "IN-P";

	/** The family's test of a decimal or a math context. */
	static final String VALUE_P = PREFIX + "VALUE-P";

	/** {@code bigdec}, which makes a decimal. */
	static final String BIGDEC = "RONTOLISP::%CLOJURE-BIGDEC";

	/** {@code bigdec} as a value. */
	static final String BIGDEC_V = "RONTOLISP::%CLOJURE-BIGDEC-V";

	/** The math context {@code with-precision} binds, which makes a context. */
	static final String MATH_CONTEXT = PREFIX + "MATH-CONTEXT";

	/**
	 * {@code decimal?}'s test {@code (p value false)}, an alias of {@code progn} in a
	 * program making no decimal, where it answers false like the other predicates of a
	 * kind no value has.
	 */
	static final String IS_DECIMAL = PREFIX + "IS-DECIMAL";

	/**
	 * {@code instance?} of {@code java.lang.Number}'s test, a decimal or what the host
	 * test answers: an alias of that test in a program making no decimal, which the host
	 * family folds to {@code numberp} in turn.
	 */
	static final String NUMBER_INSTANCE_P = PREFIX + "NUMBER-INSTANCE-P";

	/** The rounding modes a math context takes, the oracle's {@code RoundingMode}. */
	private static final Set<String> ROUNDING_MODES = Set.of("UP", "DOWN", "CEILING", "FLOOR", "HALF_UP", "HALF_DOWN",
			"HALF_EVEN", "UNNECESSARY");

	/**
	 * Each plain numeric operator's decimal-aware helper, which runs the oracle's
	 * operation beside a decimal and the operator otherwise.
	 */
	private static final Map<String, String> HELPERS = helpers();

	/**
	 * The plain form each decimal-aware helper stands for: the family's aliases -- the
	 * numeric operators' helpers, {@link #IS_DECIMAL} and {@link #NUMBER_INSTANCE_P}.
	 */
	static final Map<String, String> ALIASES = aliases();

	private ClojureDecimalLowering() {
	}

	private static Map<String, String> helpers() {
		Map<String, String> out = new HashMap<>();
		for (String operator : List.of("+", "-", "*", "/", "<", ">", "<=", ">=", "=", "ZEROP", "PLUSP", "MINUSP", "ABS",
				"MAX", "MIN", "REM", "MOD", "NUMBERP", "RATIONALP")) {
			out.put(operator, PREFIX + operator);
		}
		out.put("TRUNCATE", PREFIX + "QUOT");
		return Map.copyOf(out);
	}

	private static Map<String, String> aliases() {
		Map<String, String> out = new HashMap<>();
		for (Map.Entry<String, String> helper : HELPERS.entrySet()) {
			out.put(helper.getValue(), helper.getKey());
		}
		out.put(IS_DECIMAL, "PROGN");
		out.put(NUMBER_INSTANCE_P, "RONTOLISP::%CLOJURE-HOST-NUMBER-P");
		return Map.copyOf(out);
	}

	/**
	 * The decimal-aware helper standing for a plain numeric operator.
	 * @param operator the Common Lisp operator ({@code +}, {@code zerop},
	 * {@code truncate} ...), in any case
	 * @return the helper's symbol
	 */
	static LispSymbol helper(String operator) {
		String name = HELPERS.get(operator.toUpperCase(java.util.Locale.ROOT));
		if (name == null) {
			throw new IllegalArgumentException("no decimal helper for " + operator);
		}
		return new LispSymbol(name);
	}

	/**
	 * A call of the plain numeric operator's decimal-aware helper over lowered operands:
	 * the plain call once the family's arms fold.
	 * @param operator the Common Lisp operator
	 * @param operands the lowered operands
	 * @return the call
	 */
	static LispVal call(String operator, List<LispVal> operands) {
		List<LispVal> out = new ArrayList<>();
		out.add(helper(operator));
		out.addAll(operands);
		return ClojureLowerUtil.list(out);
	}

	/**
	 * {@code (function operator)} of the plain numeric operator's decimal-aware helper, a
	 * core name's value: {@code (function operator)} once the family's arms fold.
	 * @param operator the Common Lisp operator
	 * @return the function form
	 */
	static LispVal function(String operator) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), helper(operator));
	}

	/**
	 * The family's test of a decimal or a math context over a variable: a {@code cond}
	 * branch's test where {@code class} and a protocol's dispatch tag read one.
	 * @param value the variable holding the value
	 * @return the test
	 */
	static LispVal classTest(LispSymbol value) {
		return ClojureLowerUtil.list(new LispSymbol(VALUE_P), value);
	}

	/**
	 * The keyword of a decimal's or a math context's class, {@code :java.math.BigDecimal}
	 * or {@code :java.math.MathContext}: what {@code class} answers and a protocol
	 * dispatches on.
	 * @param value the variable holding the value
	 * @return the keyword form
	 */
	static LispVal classKey(LispSymbol value) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureCollectionLowering.KEYWORD_TAG,
				ClojureLowerUtil.list(new LispSymbol(CLASS_NAME), value));
	}

	/** The class name of a decimal or a math context ({@code clojure.lisp}). */
	private static final String CLASS_NAME = PREFIX + "CLASS-NAME";

	/**
	 * Whether a datum's head marks a decimal literal: the reader's marker, or a symbol of
	 * its spelling (a macro answer decodes to one).
	 * @param head the head of a datum
	 * @return whether the datum is a decimal literal
	 */
	static boolean isMarker(@Nullable LispVal head) {
		return head != null && ClojureLowerUtil.isSymbolNamed(head, MARKER.name());
	}

	/**
	 * The marker of the decimal an {@code M} token spells, as {@code new BigDecimal}
	 * reads its digits: the unscaled value and the scale.
	 * @param digits the token without its {@code M}
	 * @return the marker
	 */
	static LispVal marker(String digits) {
		BigDecimal decimal = new BigDecimal(digits);
		return marker(decimal.unscaledValue(), decimal.scale());
	}

	/**
	 * The marker of the decimal {@code unscaled x 10^-scale}.
	 * @param unscaled the unscaled value
	 * @param scale the scale
	 * @return the marker
	 */
	static LispVal marker(BigInteger unscaled, int scale) {
		return ClojureLowerUtil.list(MARKER, integer(unscaled), new LispInteger(scale));
	}

	private static LispVal integer(BigInteger value) {
		return value.bitLength() < 64 ? new LispInteger(value.longValue()) : new LispBigInteger(value);
	}

	/**
	 * The decimal a well-formed marker stands for, or null for any other datum.
	 * @param items the datum's items, head included
	 * @return the decimal, or null
	 */
	static @Nullable BigDecimal valueOf(List<LispVal> items) {
		if (items.size() != 3 || !isMarker(items.get(0)) || !(items.get(2) instanceof LispInteger scale)) {
			return null;
		}
		BigInteger unscaled = switch (items.get(1)) {
			case LispInteger small -> BigInteger.valueOf(small.value());
			case LispBigInteger big -> big.value();
			default -> null;
		};
		return unscaled == null ? null : new BigDecimal(unscaled, Math.toIntExact(scale.value()));
	}

	/**
	 * A decimal literal as the value it is: the quoted wrapper, one constant per literal
	 * site, which names the family's tag and so makes the program a decimal one.
	 * @param ctx the hub
	 * @param items the marker's items
	 * @return the quoted decimal
	 */
	static LispVal literal(ClojureLowering ctx, List<LispVal> items) {
		BigDecimal value = valueOf(items);
		if (value == null) {
			throw new LispReadException("an unreadable decimal: " + ClojureLowerUtil.list(items).print());
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(new LispSymbol(TAG),
				integer(value.unscaledValue()), new LispInteger(value.scale())));
	}

	/**
	 * {@code (with-precision precision [:rounding mode] body...)}: the body with
	 * {@code *math-context*} bound to the math context of the evaluated precision and the
	 * rounding mode ({@code HALF_UP} unless given), answering its last value (nil for an
	 * empty body), like the oracle's binding of a {@code MathContext}. The mode is a
	 * symbol naming a {@code RoundingMode} constant, bare or qualified ({@code FLOOR},
	 * {@code java.math.RoundingMode/FLOOR}); any other is the oracle's missing field. The
	 * body lowers behind the {@code try} barrier like a {@code binding} body, which the
	 * oracle wraps in a {@code try} too.
	 * @param ctx the hub
	 * @param items the call, head included
	 * @return the lowered form
	 */
	static LispVal withPrecisionOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "with-precision takes a precision and a body");
		LispVal precision = ctx.lower(items.get(1));
		String mode = "HALF_UP";
		int bodyFrom = 2;
		if (items.size() >= 3
				&& ClojureLowerUtil.isSymbolNamed(ClojureLowerUtil.stripMeta(items.get(2)), ":rounding")) {
			ClojureLowerUtil.isTrue(items.size() >= 4, "with-precision's :rounding takes a rounding mode");
			if (!(ClojureLowerUtil.stripMeta(items.get(3)) instanceof LispSymbol named)) {
				throw new LispReadException("with-precision's :rounding takes a RoundingMode name");
			}
			String spelled = named.name();
			mode = spelled.substring(Math.max(spelled.lastIndexOf('/'), spelled.lastIndexOf('.')) + 1);
			if (!ROUNDING_MODES.contains(mode)) {
				throw new LispReadException("No matching field found: " + mode + " for class java.math.RoundingMode");
			}
			bodyFrom = 4;
		}
		LispVal context = ClojureLowerUtil.list(new LispSymbol(MATH_CONTEXT), precision, LispString.literal(mode));
		List<LispVal> pairs = new ArrayList<>();
		pairs.add(ClojureLowerUtil.list(ctx.specialValue(ClojureCoreSpecials.required("*math-context*")), context));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs),
				ClojureStateLowering.barrierBody(ctx, items, bodyFrom));
	}

}
