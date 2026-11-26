package am.ik.rontolisp.compiler;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The functions of the internal {@code (%strict-math :name x [y])}: the double methods of
 * {@code java.lang.StrictMath}, one keyword each, answering the same bits on every
 * backend ({@code .kb/transcendentals.md}, "%strict-math"). The interpreter calls the
 * method, the JVM backend {@code invokestatic}s it, the wasm backends call the fdlibm
 * runtime ({@code WasmFdlibmRuntimeBuilder}) or emit the one instruction an exact
 * operation is.
 *
 * <p>
 * Unlike {@code sin} or {@code log}, none of them leaves the reals or reads a complex:
 * {@code (%strict-math :log -1.0)} is NaN, as in Java, where {@code (log -1.0)} answers a
 * complex, and {@code :ceil} of {@code -0.5} is {@code -0.0}. The Clojure front end's
 * {@code clojure.math} is built on it.
 */
public enum StrictMathFunction {

	/** {@code sin}. */
	SIN("sin", Shape.UNARY, false),

	/** {@code cos}. */
	COS("cos", Shape.UNARY, false),

	/** {@code tan}. */
	TAN("tan", Shape.UNARY, false),

	/** {@code asin}: NaN outside [-1, 1]. */
	ASIN("asin", Shape.UNARY, false),

	/** {@code acos}: NaN outside [-1, 1]. */
	ACOS("acos", Shape.UNARY, false),

	/** {@code atan}. */
	ATAN("atan", Shape.UNARY, false),

	/** {@code exp}. */
	EXP("exp", Shape.UNARY, false),

	/** {@code log}: NaN below zero, -infinity at either zero. */
	LOG("log", Shape.UNARY, false),

	/** {@code log10}: fdlibm's own, not {@code log(x)/log(10)}. */
	LOG10("log10", Shape.UNARY, false),

	/** {@code sqrt}: correctly rounded, NaN below zero, {@code -0.0} of {@code -0.0}. */
	SQRT("sqrt", Shape.UNARY, true),

	/** {@code cbrt}. */
	CBRT("cbrt", Shape.UNARY, false),

	/** {@code ceil}: a zero result keeps the argument's sign. */
	CEIL("ceil", Shape.UNARY, true),

	/** {@code floor}: a zero result keeps the argument's sign. */
	FLOOR("floor", Shape.UNARY, true),

	/** {@code rint}: the nearest integer, ties to even. */
	RINT("rint", Shape.UNARY, true),

	/** {@code sinh}. */
	SINH("sinh", Shape.UNARY, false),

	/** {@code cosh}. */
	COSH("cosh", Shape.UNARY, false),

	/** {@code tanh}. */
	TANH("tanh", Shape.UNARY, false),

	/** {@code expm1}. */
	EXPM1("expm1", Shape.UNARY, false),

	/** {@code log1p}. */
	LOG1P("log1p", Shape.UNARY, false),

	/** {@code ulp}: the distance to the next double away from zero. */
	ULP("ulp", Shape.UNARY, false),

	/** {@code signum}: a zero or NaN itself, else 1.0 of the argument's sign. */
	SIGNUM("signum", Shape.UNARY, false),

	/** {@code nextUp}. */
	NEXT_UP("nextUp", Shape.UNARY, false),

	/** {@code nextDown}. */
	NEXT_DOWN("nextDown", Shape.UNARY, false),

	/** {@code atan2}: {@code (y x)}. */
	ATAN2("atan2", Shape.BINARY, false),

	/** {@code pow}: NaN for a negative base to a non-integer power. */
	POW("pow", Shape.BINARY, false),

	/** {@code hypot}. */
	HYPOT("hypot", Shape.BINARY, false),

	/** {@code IEEEremainder}: {@code x - n*y}, n the integer nearest {@code x/y}. */
	IEEE_REMAINDER("IEEEremainder", Shape.BINARY, false),

	/**
	 * {@code copySign}: StrictMath's, which reads a NaN sign as positive, so the answer
	 * does not depend on the sign bit a backend gave a computed NaN.
	 */
	COPY_SIGN("copySign", Shape.BINARY, false),

	/** {@code nextAfter}: {@code (start direction)}. */
	NEXT_AFTER("nextAfter", Shape.BINARY, false),

	/**
	 * {@code getExponent}: an integer, 1024 for an infinity or NaN, -1023 below normal.
	 */
	GET_EXPONENT("getExponent", Shape.TO_INT, false),

	/**
	 * {@code scalb}: {@code (d n)}, {@code d * 2^n} rounded once; {@code n} an integer,
	 * clamped to {@link #SCALB_CLAMP}.
	 */
	SCALB("scalb", Shape.SCALE, false);

	/**
	 * How far a {@code :scalb} exponent is clamped: past it every double has saturated to
	 * zero or an infinity ({@code 1023 + 1074 + 1} is enough), so the clamp changes no
	 * answer, and every backend passes the exponent on as an int.
	 */
	public static final int SCALB_CLAMP = 2200;

	/** The arguments a function takes and the value it answers. */
	public enum Shape {

		/** {@code (double) -> double}. */
		UNARY(1, "(D)D"),

		/** {@code (double, double) -> double}. */
		BINARY(2, "(DD)D"),

		/** {@code (double) -> int}, an integer to Lisp. */
		TO_INT(1, "(D)I"),

		/** {@code (double, int) -> double}, the int an integer clamped from Lisp. */
		SCALE(2, "(DI)D");

		private final int arity;

		private final String descriptor;

		Shape(int arity, String descriptor) {
			this.arity = arity;
			this.descriptor = descriptor;
		}

		/**
		 * The argument count after the keyword.
		 * @return the count
		 */
		public int arity() {
			return this.arity;
		}

		/**
		 * The JVM method descriptor.
		 * @return the descriptor
		 */
		public String descriptor() {
			return this.descriptor;
		}

	}

	private static final Map<String, StrictMathFunction> BY_KEYWORD = new HashMap<>();

	static {
		for (StrictMathFunction fn : values()) {
			BY_KEYWORD.put(fn.keyword(), fn);
		}
	}

	private final String method;

	private final Shape shape;

	private final boolean exact;

	StrictMathFunction(String method, Shape shape, boolean exact) {
		this.method = method;
		this.shape = shape;
		this.exact = exact;
	}

	/**
	 * The function a keyword names.
	 * @param keyword the keyword's symbol name, colon included ({@code ":LOG10"})
	 * @return the function, or {@code null} for any other name
	 */
	public static @Nullable StrictMathFunction ofKeyword(String keyword) {
		return BY_KEYWORD.get(keyword.toUpperCase(Locale.ROOT));
	}

	/**
	 * The keyword naming the function: its constant's name with a colon and dashes
	 * ({@code :IEEE-REMAINDER}).
	 * @return the keyword's symbol name
	 */
	public String keyword() {
		return ":" + name().replace('_', '-');
	}

	/**
	 * The Java method's name, in {@code java.lang.StrictMath} and {@code java.lang.Math}.
	 * @return the name
	 */
	public String method() {
		return this.method;
	}

	/**
	 * The arguments and the value.
	 * @return the shape
	 */
	public Shape shape() {
		return this.shape;
	}

	/**
	 * Whether the operation is exact -- one answer on every machine -- so the JVM may
	 * call {@code java.lang.Math}'s intrinsic and wasm has an instruction for it.
	 * @return true for {@code sqrt}, {@code ceil}, {@code floor} and {@code rint}
	 */
	public boolean exact() {
		return this.exact;
	}

	/**
	 * The class the JVM backend calls: {@code java/lang/Math} for an exact operation,
	 * {@code java/lang/StrictMath} otherwise.
	 * @return the internal class name
	 */
	public String owner() {
		return this.exact ? "java/lang/Math" : "java/lang/StrictMath";
	}

	/**
	 * A one-argument function's value.
	 * @param x the argument
	 * @return {@code StrictMath.<method>(x)}
	 */
	public double apply(double x) {
		return switch (this) {
			case SIN -> StrictMath.sin(x);
			case COS -> StrictMath.cos(x);
			case TAN -> StrictMath.tan(x);
			case ASIN -> StrictMath.asin(x);
			case ACOS -> StrictMath.acos(x);
			case ATAN -> StrictMath.atan(x);
			case EXP -> StrictMath.exp(x);
			case LOG -> StrictMath.log(x);
			case LOG10 -> StrictMath.log10(x);
			case SQRT -> Math.sqrt(x);
			case CBRT -> StrictMath.cbrt(x);
			case CEIL -> Math.ceil(x);
			case FLOOR -> Math.floor(x);
			case RINT -> Math.rint(x);
			case SINH -> StrictMath.sinh(x);
			case COSH -> StrictMath.cosh(x);
			case TANH -> StrictMath.tanh(x);
			case EXPM1 -> StrictMath.expm1(x);
			case LOG1P -> StrictMath.log1p(x);
			case ULP -> StrictMath.ulp(x);
			case SIGNUM -> StrictMath.signum(x);
			case NEXT_UP -> StrictMath.nextUp(x);
			case NEXT_DOWN -> StrictMath.nextDown(x);
			default -> throw new IllegalStateException(this + " takes " + this.shape);
		};
	}

	/**
	 * A two-argument function's value.
	 * @param x the first argument
	 * @param y the second argument
	 * @return {@code StrictMath.<method>(x, y)}
	 */
	public double apply(double x, double y) {
		return switch (this) {
			case ATAN2 -> StrictMath.atan2(x, y);
			case POW -> StrictMath.pow(x, y);
			case HYPOT -> StrictMath.hypot(x, y);
			case IEEE_REMAINDER -> StrictMath.IEEEremainder(x, y);
			case COPY_SIGN -> StrictMath.copySign(x, y);
			case NEXT_AFTER -> StrictMath.nextAfter(x, y);
			default -> throw new IllegalStateException(this + " takes " + this.shape);
		};
	}

	/**
	 * {@link #GET_EXPONENT}'s value.
	 * @param x the argument
	 * @return its unbiased exponent
	 */
	public int applyToInt(double x) {
		if (this != GET_EXPONENT) {
			throw new IllegalStateException(this + " takes " + this.shape);
		}
		return StrictMath.getExponent(x);
	}

	/**
	 * {@link #SCALB}'s value.
	 * @param x the double
	 * @param n the exponent, clamped to {@link #SCALB_CLAMP} already
	 * @return {@code x * 2^n}
	 */
	public double applyScaled(double x, int n) {
		if (this != SCALB) {
			throw new IllegalStateException(this + " takes " + this.shape);
		}
		return StrictMath.scalb(x, n);
	}

}
