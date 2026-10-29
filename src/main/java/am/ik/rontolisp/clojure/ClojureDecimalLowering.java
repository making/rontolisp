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
 * to the bytes it did before decimals existed. {@code BigDecimal}'s methods, its and
 * {@code MathContext}'s constructions, statics and constants, and the
 * {@code RoundingMode} constants are values of this front end's own on every backend too
 * ({@link #methodArm}, {@link #construction}, {@link #staticCall}, {@link #field}); a
 * {@code BigDecimal} a {@code java:} member answers comes back as a decimal
 * ({@link #hostAnswer}).
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

	/** The host class a decimal is the oracle's value of. */
	static final String HOST = "java.math.BigDecimal";

	/**
	 * The classes whose values are made here ({@code clojure.lisp}, "Decimal methods"): a
	 * receiver known to be one may be such a value, so its instance calls keep the value
	 * arms.
	 */
	static final Set<String> CLASSES = Set.of(HOST, "java.math.MathContext", "java.math.RoundingMode");

	/** The tag of a math context's wrapper; quoted data holding it makes one. */
	static final String CONTEXT_TAG = ":C%MATH-CONTEXT";

	/** The tag of a rounding mode's wrapper; quoted data holding it makes one. */
	static final String ROUNDING_TAG = ":C%ROUNDING-MODE";

	/** {@code new BigDecimal} of one argument. */
	static final String NEW = PREFIX + "NEW";

	/** {@code new BigDecimal} of two arguments. */
	static final String NEW_2 = PREFIX + "NEW-2";

	/** {@code new BigDecimal} of three arguments. */
	static final String NEW_3 = PREFIX + "NEW-3";

	/** {@code BigDecimal/valueOf} of one argument. */
	static final String VALUE_OF = PREFIX + "VALUE-OF";

	/** {@code BigDecimal/valueOf} of an unscaled value and a scale. */
	static final String VALUE_OF_SCALED = PREFIX + "VALUE-OF-SCALED";

	/** {@code new MathContext} of a precision and a rounding mode. */
	static final String NEW_MATH_CONTEXT = PREFIX + "NEW-MATH-CONTEXT";

	/** {@code RoundingMode/valueOf}. */
	static final String ROUNDING_MODE_OF = PREFIX + "ROUNDING-MODE-OF";

	/**
	 * The answer of a {@code java:} member declaring a {@code BigDecimal}, as a decimal:
	 * a producer, since Java hands the program one.
	 */
	static final String OF_HOST = PREFIX + "OF-HOST";

	/**
	 * The answer of a {@code java:} member whose declared type a {@code BigDecimal} may
	 * be ({@code Object}, {@code Number} ...), as a decimal when it is one: the family's
	 * view, the answer itself in a program making no decimal.
	 */
	static final String HOST_VIEW = PREFIX + "HOST-VIEW";

	/** The decimal a host {@code BigDecimal} stands for ({@code clojure.lisp}). */
	private static final String FROM_HOST = PREFIX + "FROM-HOST";

	/**
	 * The supertypes of {@code BigDecimal}, which a member may declare and answer one.
	 */
	private static final Set<String> SUPERTYPES = Set.of("java.lang.Object", "java.lang.Number", "java.lang.Comparable",
			"java.io.Serializable");

	/** Every producer the family names: the reads, the makers and the tags. */
	static final Set<String> PRODUCERS = Set.of(TAG, CONTEXT_TAG, ROUNDING_TAG, BIGDEC, BIGDEC_V, MATH_CONTEXT, NEW,
			NEW_2, NEW_3, VALUE_OF, VALUE_OF_SCALED, NEW_MATH_CONTEXT, ROUNDING_MODE_OF, OF_HOST);

	/**
	 * A method a value of the family answers on every backend: its kernel
	 * ({@code clojure.lisp}, called with the receiver, the arguments and the extra
	 * literal arguments), whether any value of the family takes it (a math context's or a
	 * rounding mode's method, which refuses a decimal) or only a decimal, and whether it
	 * answers a boolean.
	 */
	private record Method(String kernel, boolean anyValue, boolean bool, List<LispVal> extra) {

	}

	/** The methods, by {@code name/arity}. */
	private static final Map<String, Method> METHODS = methods();

	private static Map<String, Method> methods() {
		Map<String, Method> out = new HashMap<>();
		decimal(out, "SCALE", "scale/0");
		decimal(out, "M-PRECISION", "precision/0");
		decimal(out, "UNSCALED", "unscaledValue/0");
		decimal(out, "SIGNUM", "signum/0");
		decimal(out, "M-NEGATE", "negate/0", "negate/1");
		decimal(out, "M-ABS", "abs/0", "abs/1");
		decimal(out, "M-PLUS", "plus/0", "plus/1");
		decimal(out, "M-ROUND", "round/1");
		decimal(out, "SET-SCALE", "setScale/1", "setScale/2");
		decimal(out, "M-ADD", "add/1", "add/2");
		decimal(out, "M-SUBTRACT", "subtract/1", "subtract/2");
		decimal(out, "M-MULTIPLY", "multiply/1", "multiply/2");
		decimal(out, "M-DIVIDE", "divide/1", "divide/2", "divide/3");
		decimal(out, "M-REMAINDER", "remainder/1");
		decimal(out, "M-INTEGRAL", "divideToIntegralValue/1");
		decimal(out, "M-POW", "pow/1");
		decimal(out, "M-MAX", "max/1");
		decimal(out, "M-MIN", "min/1");
		decimal(out, "MOVE-LEFT", "movePointLeft/1");
		decimal(out, "MOVE-RIGHT", "movePointRight/1");
		decimal(out, "SCALE-BY-TEN", "scaleByPowerOfTen/1");
		decimal(out, "M-COMPARE", "compareTo/1");
		decimal(out, "STRIP", "stripTrailingZeros/0");
		decimal(out, "PLAIN-STRING", "toPlainString/0");
		decimal(out, "ENGINEERING-STRING", "toEngineeringString/0");
		decimal(out, "ULP", "ulp/0");
		decimal(out, "DOUBLE", "doubleValue/0", "floatValue/0");
		decimal(out, "TO-INTEGER", "toBigInteger/0");
		out.put("equals/1", new Method(PREFIX + "JAVA-EQUAL", false, true, List.of()));
		Map<String, Integer> widths = Map.of("long", 64, "int", 32, "short", 16, "byte", 8);
		for (Map.Entry<String, Integer> width : widths.entrySet()) {
			LispInteger bits = new LispInteger(width.getValue());
			out.put(width.getKey() + "Value/0", new Method(PREFIX + "INTEGER-VALUE", false, false, List.of(bits)));
			out.put(width.getKey() + "ValueExact/0", new Method(PREFIX + "EXACT-INTEGER", false, false, List.of(bits)));
		}
		out.put("toBigIntegerExact/0",
				new Method(PREFIX + "EXACT-INTEGER", false, false, List.of(ClojureLowering.NIL_CONST)));
		for (String[] row : new String[][] { { "GET-PRECISION", "getPrecision/0" },
				{ "GET-ROUNDING-MODE", "getRoundingMode/0" }, { "MODE-NAME", "name/0" },
				{ "MODE-ORDINAL", "ordinal/0" } }) {
			out.put(row[1], new Method(PREFIX + row[0], true, false, List.of()));
		}
		return Map.copyOf(out);
	}

	private static void decimal(Map<String, Method> out, String kernel, String... keys) {
		for (String key : keys) {
			out.put(key, new Method(PREFIX + kernel, false, false, List.of()));
		}
	}

	/**
	 * The arm of an instance call for a value of the family ahead of {@code otherwise},
	 * whose test is a family test, so a program making none folds back to
	 * {@code otherwise}: a mapped method calls its kernel on every backend; any other
	 * method {@code BigDecimal} has calls it, where the host is, on the
	 * {@code BigDecimal} the decimal crosses as (a {@code BigDecimal} it answers coming
	 * back as a decimal), and is refused by name where it is not (wasm), since the class
	 * has it. {@code Object}'s methods keep {@code otherwise}, which answers them for
	 * every value.
	 * @param ctx the hub
	 * @param method the method name
	 * @param designator the method as {@code java:call} names it
	 * @param recv the bound receiver
	 * @param args the lowered arguments
	 * @param otherwise the form for any other receiver
	 * @return the arm around {@code otherwise}, or {@code otherwise} itself
	 */
	static LispVal methodArm(ClojureLowering ctx, String method, String designator, LispSymbol recv, List<LispVal> args,
			LispVal otherwise) {
		int n = args.size();
		Method mapped = METHODS.get(method + "/" + n);
		LispVal answer;
		String test = DECIMAL_P;
		if (mapped != null) {
			List<LispVal> call = new ArrayList<>();
			call.add(new LispSymbol(mapped.kernel()));
			call.add(recv);
			call.addAll(args);
			call.addAll(mapped.extra());
			answer = ClojureLowerUtil.list(call);
			if (mapped.bool()) {
				answer = ctx.booleanAnswer(answer);
			}
			if (mapped.anyValue()) {
				test = VALUE_P;
			}
		}
		else if (ClojureValueMethodLowering.declares(List.of("java.lang.Object"), method, n)
				|| !ClojureValueMethodLowering.declares(List.of(HOST), method, n)) {
			return otherwise;
		}
		else if (ctx.hostTarget) {
			List<LispVal> parts = new ArrayList<>();
			parts.add(ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-HOST-MEMBER"), recv));
			parts.add(LispString.literal(designator));
			parts.addAll(args);
			LispVal call = ClojureInteropLowering.hostCall(ctx, ClojureInteropLowering.JAVA_CALL, parts, 2,
					ClojureInteropLowering.answersBytes(HOST, designator, n, false));
			answer = ClojureLowerUtil.list(new LispSymbol(FROM_HOST), call);
		}
		else {
			answer = ClojureValueMethodLowering.refusal(recv, method, false, args);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(new LispSymbol(test), recv),
				answer, otherwise);
	}

	/**
	 * A construction of a decimal or a math context over already-lowered arguments, on
	 * every backend: {@code BigDecimal} of a literal string or integer the quoted decimal
	 * (a string {@code BigDecimal} refuses keeps the run-time read, which refuses it), of
	 * anything else the kernel of its count; {@code MathContext} of a precision (and a
	 * rounding mode). Null for any other class or count. Param tags are not read: the
	 * kernels tell the overloads apart by the arguments, like the oracle's reflection.
	 * @param cls the resolved class name
	 * @param args the lowered arguments
	 * @return the construction, or null
	 */
	static @Nullable LispVal construction(String cls, List<LispVal> args) {
		if (cls.equals(HOST)) {
			if (args.size() == 1) {
				LispVal literal = literalOf(args.get(0));
				return literal != null ? literal : ClojureLowerUtil.list(new LispSymbol(NEW), args.get(0));
			}
			String kernel = args.size() == 2 ? NEW_2 : args.size() == 3 ? NEW_3 : null;
			return kernel == null ? null : ClojureLowerUtil.cons(new LispSymbol(kernel), args);
		}
		if (cls.equals("java.math.MathContext")) {
			return switch (args.size()) {
				case 1 ->
					ClojureLowerUtil.list(new LispSymbol(MATH_CONTEXT), args.get(0), LispString.literal("HALF_UP"));
				case 2 -> ClojureLowerUtil.list(new LispSymbol(NEW_MATH_CONTEXT), args.get(0), args.get(1));
				default -> null;
			};
		}
		return null;
	}

	/** The quoted decimal a literal string or integer constructs, or null. */
	private static @Nullable LispVal literalOf(LispVal arg) {
		BigDecimal value;
		try {
			value = switch (arg) {
				case LispString string -> new BigDecimal(string.value());
				case LispInteger small -> BigDecimal.valueOf(small.value());
				case LispBigInteger big -> new BigDecimal(big.value());
				default -> null;
			};
		}
		catch (NumberFormatException _) {
			return null;
		}
		return value == null ? null : quoted(value);
	}

	private static LispVal quoted(BigDecimal value) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(new LispSymbol(TAG),
				integer(value.unscaledValue()), new LispInteger(value.scale())));
	}

	/**
	 * A static call of the family's classes over already-lowered arguments, on every
	 * backend: {@code BigDecimal/valueOf} of one or two arguments,
	 * {@code RoundingMode/valueOf}. Null for any other member or count.
	 * @param cls the resolved class name
	 * @param member the static member
	 * @param args the lowered arguments
	 * @return the call, or null
	 */
	static @Nullable LispVal staticCall(String cls, String member, List<LispVal> args) {
		if (!member.equals("valueOf")) {
			return null;
		}
		if (cls.equals(HOST) && (args.size() == 1 || args.size() == 2)) {
			return ClojureLowerUtil.cons(new LispSymbol(args.size() == 1 ? VALUE_OF : VALUE_OF_SCALED), args);
		}
		if (cls.equals("java.math.RoundingMode") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(ROUNDING_MODE_OF), args.get(0));
		}
		return null;
	}

	/** The legacy {@code BigDecimal.ROUND_} constants, each its mode's ordinal. */
	private static final List<String> ROUND_CONSTANTS = List.of("UP", "DOWN", "CEILING", "FLOOR", "HALF_UP",
			"HALF_DOWN", "HALF_EVEN", "UNNECESSARY");

	/**
	 * A static field of the family's classes as the value it is, on every backend:
	 * {@code BigDecimal}'s {@code ZERO}, {@code ONE}, {@code TWO} and {@code TEN} and its
	 * legacy {@code ROUND_} ints, a {@code RoundingMode} constant, {@code MathContext}'s
	 * {@code DECIMAL32}, {@code DECIMAL64}, {@code DECIMAL128} and {@code UNLIMITED}.
	 * Null for any other.
	 * @param cls the resolved class name
	 * @param member the field
	 * @return the value, or null
	 */
	static @Nullable LispVal field(String cls, String member) {
		switch (cls) {
			case HOST:
				switch (member) {
					case "ZERO":
						return quoted(BigDecimal.ZERO);
					case "ONE":
						return quoted(BigDecimal.ONE);
					case "TWO":
						return quoted(BigDecimal.TWO);
					case "TEN":
						return quoted(BigDecimal.TEN);
					default:
						int ordinal = member.startsWith("ROUND_") ? ROUND_CONSTANTS.indexOf(member.substring(6)) : -1;
						return ordinal < 0 ? null : new LispInteger(ordinal);
				}
			case "java.math.RoundingMode":
				return ROUNDING_MODES.contains(member) ? ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"),
						ClojureLowerUtil.list(new LispSymbol(ROUNDING_TAG), LispString.literal(member))) : null;
			case "java.math.MathContext":
				return switch (member) {
					case "DECIMAL32" -> context(7, "HALF_EVEN");
					case "DECIMAL64" -> context(16, "HALF_EVEN");
					case "DECIMAL128" -> context(34, "HALF_EVEN");
					case "UNLIMITED" -> context(0, "HALF_UP");
					default -> null;
				};
			default:
				return null;
		}
	}

	private static LispVal context(int precision, String mode) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(new LispSymbol(CONTEXT_TAG),
				new LispInteger(precision), LispString.literal(mode)));
	}

	/**
	 * A {@code java:} member's answer as the program reads it where the host is: a member
	 * declaring a {@code BigDecimal} answers a decimal ({@link #OF_HOST}, which makes the
	 * program a decimal one), a member whose declared type a {@code BigDecimal} may be
	 * ({@code Object}, {@code Number}, {@code Comparable}, {@code Serializable}), or one
	 * the lowering cannot see, answers through the family's view ({@link #HOST_VIEW}),
	 * which a program making no decimal folds back to the call; any other is the call
	 * itself.
	 * @param ctx the hub
	 * @param call the lowered call
	 * @param className the class, or null when the receiver's class is unknown
	 * @param designator the member, perhaps tagged ({@code m(T1,T2)})
	 * @param arity the argument count
	 * @param statics whether the call is a static one
	 * @return the answer
	 */
	static LispVal hostAnswer(ClojureLowering ctx, LispVal call, @Nullable String className, String designator,
			int arity, boolean statics) {
		if (!ctx.hostTarget) {
			return call;
		}
		String wrapper = HOST_VIEW;
		ClojureJavaClasses.JavaClass found = className == null ? null : ClojureHostClasses.find(className);
		if (found != null) {
			int tagged = designator.indexOf('(');
			String member = tagged < 0 ? designator : designator.substring(0, tagged);
			try {
				boolean seen = false;
				boolean maybe = false;
				boolean certain = false;
				for (ClojureJavaClasses.Member candidate : found.methods()) {
					if (!candidate.name().equals(member) || statics && !candidate.isStatic()) {
						continue;
					}
					int count = candidate.parameterCount();
					if (count != arity && !(candidate.isVarArgs() && count - 1 <= arity)) {
						continue;
					}
					seen = true;
					certain |= candidate.returnType().equals(HOST);
					maybe |= SUPERTYPES.contains(candidate.returnType());
				}
				if (certain) {
					wrapper = OF_HOST;
				}
				else if (seen && !maybe) {
					return call;
				}
			}
			catch (LinkageError | SecurityException _) {
				// an unloadable class may answer anything
			}
		}
		return ClojureLowerUtil.list(new LispSymbol(wrapper), call);
	}

}
