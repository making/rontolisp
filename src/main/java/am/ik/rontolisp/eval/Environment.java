package am.ik.rontolisp.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.Scope;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * Lexical environment for variable bindings.
 */
public final class Environment implements Scope {

	private final Map<String, LispVal> bindings;

	@Nullable private final Environment parent;

	/**
	 * Create a new environment with the given parent scope.
	 * @param parent the parent environment, or {@code null} for a top-level scope
	 */
	public Environment(@Nullable Environment parent) {
		this.bindings = new HashMap<>();
		this.parent = parent;
	}

	@Override
	public LispVal lookup(String name) {
		LispVal val = this.bindings.get(name);
		if (val != null) {
			return val;
		}
		if (this.parent != null) {
			return this.parent.lookup(name);
		}
		throw new LispEvalException("Undefined symbol: " + name);
	}

	/**
	 * Define a new binding in this environment.
	 * @param name the variable name
	 * @param value the value to bind
	 */
	public void define(String name, LispVal value) {
		this.bindings.put(name, value);
	}

	/**
	 * Set an existing binding, searching up the scope chain.
	 * @param name the variable name
	 * @param value the new value
	 */
	public void set(String name, LispVal value) {
		if (this.bindings.containsKey(name)) {
			this.bindings.put(name, value);
			return;
		}
		if (this.parent != null) {
			this.parent.set(name, value);
			return;
		}
		// If not found anywhere, define in current scope
		this.bindings.put(name, value);
	}

	/**
	 * Create the global environment with all built-in functions.
	 * @param out the output stream for print operations
	 * @return the global environment
	 */
	public static Environment createGlobal(PrintStream out) {
		return createGlobal(out, InputStream.nullInputStream());
	}

	/**
	 * Create the global environment with all built-in functions.
	 * @param out the output stream for print operations
	 * @param in the input stream for read operations
	 * @return the global environment
	 */
	public static Environment createGlobal(PrintStream out, InputStream in) {
		Environment env = new Environment(null);
		registerArithmetic(env);
		registerMath(env);
		registerComparison(env);
		registerIO(env, out, in);
		registerPredicates(env);
		registerListOps(env);
		registerTypeConversion(env);
		return env;
	}

	private static void registerArithmetic(Environment env) {
		env.define(LispNames.ADD, new LispFunction(LispNames.ADD, args -> {
			if (hasDouble(args)) {
				double result = 0;
				for (LispVal arg : args) {
					result += asDouble(arg);
				}
				return new LispDouble(result);
			}
			if (hasBigInteger(args)) {
				return addBig(args);
			}
			try {
				long result = 0;
				for (LispVal arg : args) {
					result = Math.addExact(result, asLong(arg));
				}
				return new LispInteger(result);
			}
			catch (ArithmeticException overflow) {
				return addBig(args);
			}
		}));
		env.define(LispNames.SUB, new LispFunction(LispNames.SUB, args -> {
			if (hasDouble(args)) {
				if (args.size() == 1) {
					return new LispDouble(-asDouble(args.get(0)));
				}
				double result = asDouble(args.get(0));
				for (int i = 1; i < args.size(); i++) {
					result -= asDouble(args.get(i));
				}
				return new LispDouble(result);
			}
			if (hasBigInteger(args)) {
				return subBig(args);
			}
			try {
				if (args.size() == 1) {
					return new LispInteger(Math.negateExact(asLong(args.get(0))));
				}
				long result = asLong(args.get(0));
				for (int i = 1; i < args.size(); i++) {
					result = Math.subtractExact(result, asLong(args.get(i)));
				}
				return new LispInteger(result);
			}
			catch (ArithmeticException overflow) {
				return subBig(args);
			}
		}));
		env.define(LispNames.MUL, new LispFunction(LispNames.MUL, args -> {
			if (hasDouble(args)) {
				double result = 1;
				for (LispVal arg : args) {
					result *= asDouble(arg);
				}
				return new LispDouble(result);
			}
			if (hasBigInteger(args)) {
				return mulBig(args);
			}
			try {
				long result = 1;
				for (LispVal arg : args) {
					result = Math.multiplyExact(result, asLong(arg));
				}
				return new LispInteger(result);
			}
			catch (ArithmeticException overflow) {
				return mulBig(args);
			}
		}));
		env.define(LispNames.DIV, new LispFunction(LispNames.DIV, args -> {
			if (hasDouble(args)) {
				double result = asDouble(args.get(0));
				for (int i = 1; i < args.size(); i++) {
					result /= asDouble(args.get(i));
				}
				return new LispDouble(result);
			}
			if (hasBigInteger(args)) {
				return divBig(args);
			}
			long result = asLong(args.get(0));
			for (int i = 1; i < args.size(); i++) {
				long divisor = asLong(args.get(i));
				// Only Long.MIN_VALUE / -1 overflows long division; promote that case.
				if (result == Long.MIN_VALUE && divisor == -1) {
					return divBig(args);
				}
				result /= divisor;
			}
			return new LispInteger(result);
		}));
		env.define(LispNames.MOD, new LispFunction(LispNames.MOD, args -> {
			requireArgCount(LispNames.MOD, args, 2);
			if (hasDouble(args)) {
				return new LispDouble(asDouble(args.get(0)) % asDouble(args.get(1)));
			}
			if (hasBigInteger(args)) {
				return normalizeBig(asBigInteger(args.get(0)).remainder(asBigInteger(args.get(1))));
			}
			return new LispInteger(asLong(args.get(0)) % asLong(args.get(1)));
		}));
		env.define(LispNames.ABS, new LispFunction(LispNames.ABS, args -> {
			requireArgCount(LispNames.ABS, args, 1);
			if (hasDouble(args)) {
				return new LispDouble(Math.abs(asDouble(args.get(0))));
			}
			if (args.get(0) instanceof LispBigInteger b) {
				return normalizeBig(b.value().abs());
			}
			long value = asLong(args.get(0));
			if (value == Long.MIN_VALUE) {
				return new LispBigInteger(BigInteger.valueOf(value).negate());
			}
			return new LispInteger(Math.abs(value));
		}));
		env.define(LispNames.MIN, new LispFunction(LispNames.MIN, args -> {
			requireArgCount(LispNames.MIN, args, 2);
			if (hasDouble(args)) {
				return new LispDouble(Math.min(asDouble(args.get(0)), asDouble(args.get(1))));
			}
			if (hasBigInteger(args)) {
				return asBigInteger(args.get(0)).compareTo(asBigInteger(args.get(1))) <= 0 ? args.get(0) : args.get(1);
			}
			return new LispInteger(Math.min(asLong(args.get(0)), asLong(args.get(1))));
		}));
		env.define(LispNames.MAX, new LispFunction(LispNames.MAX, args -> {
			requireArgCount(LispNames.MAX, args, 2);
			if (hasDouble(args)) {
				return new LispDouble(Math.max(asDouble(args.get(0)), asDouble(args.get(1))));
			}
			if (hasBigInteger(args)) {
				return asBigInteger(args.get(0)).compareTo(asBigInteger(args.get(1))) >= 0 ? args.get(0) : args.get(1);
			}
			return new LispInteger(Math.max(asLong(args.get(0)), asLong(args.get(1))));
		}));
		env.define(LispNames.ONE_PLUS, new LispFunction(LispNames.ONE_PLUS, args -> {
			requireArgCount(LispNames.ONE_PLUS, args, 1);
			if (hasDouble(args)) {
				return new LispDouble(asDouble(args.get(0)) + 1);
			}
			if (args.get(0) instanceof LispBigInteger b) {
				return normalizeBig(b.value().add(BigInteger.ONE));
			}
			try {
				return new LispInteger(Math.addExact(asLong(args.get(0)), 1));
			}
			catch (ArithmeticException overflow) {
				return normalizeBig(asBigInteger(args.get(0)).add(BigInteger.ONE));
			}
		}));
		env.define(LispNames.ONE_MINUS, new LispFunction(LispNames.ONE_MINUS, args -> {
			requireArgCount(LispNames.ONE_MINUS, args, 1);
			if (hasDouble(args)) {
				return new LispDouble(asDouble(args.get(0)) - 1);
			}
			if (args.get(0) instanceof LispBigInteger b) {
				return normalizeBig(b.value().subtract(BigInteger.ONE));
			}
			try {
				return new LispInteger(Math.subtractExact(asLong(args.get(0)), 1));
			}
			catch (ArithmeticException overflow) {
				return normalizeBig(asBigInteger(args.get(0)).subtract(BigInteger.ONE));
			}
		}));
	}

	private static void registerMath(Environment env) {
		// Unary floating-point functions: always return a double (Math.<name>).
		defineUnaryDouble(env, LispNames.SQRT, Math::sqrt);
		defineUnaryDouble(env, LispNames.EXP, Math::exp);
		defineUnaryDouble(env, LispNames.LOG, Math::log);
		defineUnaryDouble(env, LispNames.SIN, Math::sin);
		defineUnaryDouble(env, LispNames.COS, Math::cos);
		defineUnaryDouble(env, LispNames.TAN, Math::tan);
		defineUnaryDouble(env, LispNames.ASIN, Math::asin);
		defineUnaryDouble(env, LispNames.ACOS, Math::acos);
		defineUnaryDouble(env, LispNames.ATAN, Math::atan);
		defineUnaryDouble(env, LispNames.SINH, Math::sinh);
		defineUnaryDouble(env, LispNames.COSH, Math::cosh);
		defineUnaryDouble(env, LispNames.TANH, Math::tanh);
		// isqrt: exact integer square root (floor of the real square root).
		env.define(LispNames.ISQRT, new LispFunction(LispNames.ISQRT, args -> {
			requireArgCount(LispNames.ISQRT, args, 1);
			BigInteger n = asBigInteger(args.get(0));
			if (n.signum() < 0) {
				throw new LispEvalException("isqrt expects a non-negative integer, got: " + args.get(0).print());
			}
			return normalizeBig(n.sqrt());
		}));
		// expt: integer^non-negative-integer stays exact; otherwise Math.pow (double).
		env.define(LispNames.EXPT, new LispFunction(LispNames.EXPT, args -> {
			requireArgCount(LispNames.EXPT, args, 2);
			if (!hasDouble(args)) {
				long power = asLong(args.get(1));
				if (power >= 0 && power <= Integer.MAX_VALUE) {
					return normalizeBig(asBigInteger(args.get(0)).pow((int) power));
				}
			}
			return new LispDouble(Math.pow(asDouble(args.get(0)), asDouble(args.get(1))));
		}));
		// gcd: greatest common divisor of two integers (always non-negative).
		env.define(LispNames.GCD, new LispFunction(LispNames.GCD, args -> {
			requireArgCount(LispNames.GCD, args, 2);
			return normalizeBig(asBigInteger(args.get(0)).gcd(asBigInteger(args.get(1))));
		}));
		// lcm: least common multiple of two integers (0 if either is 0).
		env.define(LispNames.LCM, new LispFunction(LispNames.LCM, args -> {
			requireArgCount(LispNames.LCM, args, 2);
			BigInteger a = asBigInteger(args.get(0));
			BigInteger b = asBigInteger(args.get(1));
			if (a.signum() == 0 || b.signum() == 0) {
				return new LispInteger(0);
			}
			return normalizeBig(a.divide(a.gcd(b)).multiply(b).abs());
		}));
		// signum: sign as -1/0/1, preserving the float/integer type of the argument.
		env.define(LispNames.SIGNUM, new LispFunction(LispNames.SIGNUM, args -> {
			requireArgCount(LispNames.SIGNUM, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispDouble d) {
				return new LispDouble(Math.signum(d.value()));
			}
			if (arg instanceof LispBigInteger b) {
				return new LispInteger(b.value().signum());
			}
			return new LispInteger(Long.signum(asLong(arg)));
		}));
	}

	private static void defineUnaryDouble(Environment env, String name, DoubleUnaryOperator fn) {
		env.define(name, new LispFunction(name, args -> {
			requireArgCount(name, args, 1);
			return new LispDouble(fn.applyAsDouble(asDouble(args.get(0))));
		}));
	}

	private static void registerComparison(Environment env) {
		env.define(LispNames.EQ, new LispFunction(LispNames.EQ, args -> {
			requireArgCount(LispNames.EQ, args, 2);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) == asDouble(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (hasBigInteger(args)) {
				return compareBig(args) == 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) == asLong(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.LT, new LispFunction(LispNames.LT, args -> {
			requireArgCount(LispNames.LT, args, 2);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) < asDouble(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (hasBigInteger(args)) {
				return compareBig(args) < 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) < asLong(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.GT, new LispFunction(LispNames.GT, args -> {
			requireArgCount(LispNames.GT, args, 2);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) > asDouble(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (hasBigInteger(args)) {
				return compareBig(args) > 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) > asLong(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.LE, new LispFunction(LispNames.LE, args -> {
			requireArgCount(LispNames.LE, args, 2);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) <= asDouble(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (hasBigInteger(args)) {
				return compareBig(args) <= 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) <= asLong(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.GE, new LispFunction(LispNames.GE, args -> {
			requireArgCount(LispNames.GE, args, 2);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) >= asDouble(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (hasBigInteger(args)) {
				return compareBig(args) >= 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) >= asLong(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.EQ_GENERAL, new LispFunction(LispNames.EQ_GENERAL, args -> {
			requireArgCount(LispNames.EQ_GENERAL, args, 2);
			LispVal a = args.get(0);
			LispVal b = args.get(1);
			if (a instanceof LispCons || b instanceof LispCons) {
				return a == b ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (a instanceof LispNil && b instanceof LispNil) {
				return LispTrue.INSTANCE;
			}
			if (a instanceof LispNil || b instanceof LispNil) {
				return LispNil.INSTANCE;
			}
			return a.equals(b) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
	}

	private static void registerIO(Environment env, PrintStream out, InputStream in) {
		BufferedReader stdinReader = new BufferedReader(new InputStreamReader(in));
		env.define(LispNames.PRINT, new LispFunction(LispNames.PRINT, args -> {
			requireArgCount(LispNames.PRINT, args, 1);
			LispVal val = args.get(0);
			if (val instanceof LispInteger i) {
				out.println(i.value());
			}
			else if (val instanceof LispDouble d) {
				out.println(Double.toString(d.value()));
			}
			else {
				out.println(val.print());
			}
			return val;
		}));
		env.define(LispNames.PRIN1, new LispFunction(LispNames.PRIN1, args -> {
			requireArgCount(LispNames.PRIN1, args, 1);
			LispVal val = args.get(0);
			if (val instanceof LispInteger i) {
				out.print(i.value());
			}
			else if (val instanceof LispDouble d) {
				out.print(Double.toString(d.value()));
			}
			else {
				out.print(val.print());
			}
			return val;
		}));
		env.define(LispNames.PRINC, new LispFunction(LispNames.PRINC, args -> {
			requireArgCount(LispNames.PRINC, args, 1);
			LispVal val = args.get(0);
			if (val instanceof LispInteger i) {
				out.print(i.value());
			}
			else if (val instanceof LispDouble d) {
				out.print(Double.toString(d.value()));
			}
			else {
				out.print(val.display());
			}
			return val;
		}));
		env.define(LispNames.TERPRI, new LispFunction(LispNames.TERPRI, args -> {
			requireArgCount(LispNames.TERPRI, args, 0);
			out.println();
			return LispNil.INSTANCE;
		}));
		env.define(LispNames.READ_LINE, new LispFunction(LispNames.READ_LINE, args -> {
			requireArgCount(LispNames.READ_LINE, args, 0);
			try {
				String line = stdinReader.readLine();
				return line == null ? LispNil.INSTANCE : new LispString(line);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}));
		env.define(LispNames.READ, new LispFunction(LispNames.READ, args -> {
			requireArgCount(LispNames.READ, args, 0);
			try {
				String line = stdinReader.readLine();
				if (line == null) {
					return LispNil.INSTANCE;
				}
				line = line.trim();
				if (line.isEmpty()) {
					return LispNil.INSTANCE;
				}
				return LispReader.readFromString(line);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}));
	}

	private static void registerPredicates(Environment env) {
		env.define(LispNames.NULL, new LispFunction(LispNames.NULL, args -> {
			requireArgCount(LispNames.NULL, args, 1);
			return args.get(0) instanceof LispNil ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.NOT, new LispFunction(LispNames.NOT, args -> {
			requireArgCount(LispNames.NOT, args, 1);
			return args.get(0) instanceof LispNil ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.ATOM, new LispFunction(LispNames.ATOM, args -> {
			requireArgCount(LispNames.ATOM, args, 1);
			return !(args.get(0) instanceof LispCons) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.NUMBERP, new LispFunction(LispNames.NUMBERP, args -> {
			requireArgCount(LispNames.NUMBERP, args, 1);
			LispVal arg = args.get(0);
			return (arg instanceof LispInteger || arg instanceof LispBigInteger || arg instanceof LispDouble)
					? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.INTEGERP, new LispFunction(LispNames.INTEGERP, args -> {
			requireArgCount(LispNames.INTEGERP, args, 1);
			LispVal arg = args.get(0);
			return (arg instanceof LispInteger || arg instanceof LispBigInteger) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.FLOATP, new LispFunction(LispNames.FLOATP, args -> {
			requireArgCount(LispNames.FLOATP, args, 1);
			return args.get(0) instanceof LispDouble ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.SYMBOLP, new LispFunction(LispNames.SYMBOLP, args -> {
			requireArgCount(LispNames.SYMBOLP, args, 1);
			return args.get(0) instanceof LispSymbol ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.STRINGP, new LispFunction(LispNames.STRINGP, args -> {
			requireArgCount(LispNames.STRINGP, args, 1);
			return args.get(0) instanceof LispString ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.LISTP, new LispFunction(LispNames.LISTP, args -> {
			requireArgCount(LispNames.LISTP, args, 1);
			LispVal arg = args.get(0);
			return (arg instanceof LispCons || arg instanceof LispNil) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.CONSP, new LispFunction(LispNames.CONSP, args -> {
			requireArgCount(LispNames.CONSP, args, 1);
			return args.get(0) instanceof LispCons ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.KEYWORDP, new LispFunction(LispNames.KEYWORDP, args -> {
			requireArgCount(LispNames.KEYWORDP, args, 1);
			return args.get(0) instanceof LispSymbol sym && sym.isKeyword() ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.ZEROP, new LispFunction(LispNames.ZEROP, args -> {
			requireArgCount(LispNames.ZEROP, args, 1);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) == 0.0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			// A normalized LispBigInteger is always outside the long range, hence
			// non-zero.
			if (args.get(0) instanceof LispBigInteger) {
				return LispNil.INSTANCE;
			}
			return asLong(args.get(0)) == 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.PLUSP, new LispFunction(LispNames.PLUSP, args -> {
			requireArgCount(LispNames.PLUSP, args, 1);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) > 0.0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (args.get(0) instanceof LispBigInteger b) {
				return b.value().signum() > 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) > 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.MINUSP, new LispFunction(LispNames.MINUSP, args -> {
			requireArgCount(LispNames.MINUSP, args, 1);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) < 0.0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			if (args.get(0) instanceof LispBigInteger b) {
				return b.value().signum() < 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) < 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.EVENP, new LispFunction(LispNames.EVENP, args -> {
			requireArgCount(LispNames.EVENP, args, 1);
			if (args.get(0) instanceof LispBigInteger b) {
				return b.value().testBit(0) ? LispNil.INSTANCE : LispTrue.INSTANCE;
			}
			return asLong(args.get(0)) % 2 == 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.ODDP, new LispFunction(LispNames.ODDP, args -> {
			requireArgCount(LispNames.ODDP, args, 1);
			if (args.get(0) instanceof LispBigInteger b) {
				return b.value().testBit(0) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) % 2 != 0 ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
	}

	private static void registerListOps(Environment env) {
		env.define(LispNames.CONS, new LispFunction(LispNames.CONS, args -> {
			requireArgCount(LispNames.CONS, args, 2);
			return new LispCons(args.get(0), args.get(1));
		}));
		env.define(LispNames.CAR, new LispFunction(LispNames.CAR, args -> {
			requireArgCount(LispNames.CAR, args, 1);
			if (args.get(0) instanceof LispCons cons) {
				return cons.car();
			}
			throw new LispEvalException("car expects a cons cell, got: " + args.get(0).print());
		}));
		env.define(LispNames.CDR, new LispFunction(LispNames.CDR, args -> {
			requireArgCount(LispNames.CDR, args, 1);
			if (args.get(0) instanceof LispCons cons) {
				return cons.cdr();
			}
			throw new LispEvalException("cdr expects a cons cell, got: " + args.get(0).print());
		}));
		env.define(LispNames.LIST, new LispFunction(LispNames.LIST, args -> {
			LispVal result = LispNil.INSTANCE;
			for (int i = args.size() - 1; i >= 0; i--) {
				result = new LispCons(args.get(i), result);
			}
			return result;
		}));
		env.define(LispNames.NTHCDR, new LispFunction(LispNames.NTHCDR, args -> {
			requireArgCount(LispNames.NTHCDR, args, 2);
			long n = asLong(args.get(0));
			LispVal list = args.get(1);
			for (long i = 0; i < n; i++) {
				if (list instanceof LispCons cons) {
					list = cons.cdr();
				}
				else {
					return LispNil.INSTANCE;
				}
			}
			return list;
		}));
		env.define(LispNames.RPLACA, new LispFunction(LispNames.RPLACA, args -> {
			requireArgCount(LispNames.RPLACA, args, 2);
			if (args.get(0) instanceof LispCons cons) {
				cons.setCar(args.get(1));
				return cons;
			}
			throw new LispEvalException("rplaca expects a cons cell, got: " + args.get(0).print());
		}));
		env.define(LispNames.RPLACD, new LispFunction(LispNames.RPLACD, args -> {
			requireArgCount(LispNames.RPLACD, args, 2);
			if (args.get(0) instanceof LispCons cons) {
				cons.setCdr(args.get(1));
				return cons;
			}
			throw new LispEvalException("rplacd expects a cons cell, got: " + args.get(0).print());
		}));
		env.define(LispNames.REMF_TAIL, new LispFunction(LispNames.REMF_TAIL, args -> {
			requireArgCount(LispNames.REMF_TAIL, args, 2);
			LispVal current = args.get(0);
			LispVal indicator = args.get(1);
			while (current instanceof LispCons currentCons) {
				LispVal valueCellVal = currentCons.cdr();
				if (!(valueCellVal instanceof LispCons valueCell)) {
					return LispNil.INSTANCE;
				}
				LispVal nextKeyCellVal = valueCell.cdr();
				if (!(nextKeyCellVal instanceof LispCons nextKeyCell)) {
					return LispNil.INSTANCE;
				}
				LispVal key = nextKeyCell.car();
				boolean match;
				if (key instanceof LispCons || indicator instanceof LispCons) {
					match = key == indicator;
				}
				else if (key instanceof LispNil && indicator instanceof LispNil) {
					match = true;
				}
				else if (key instanceof LispNil || indicator instanceof LispNil) {
					match = false;
				}
				else {
					match = key.equals(indicator);
				}
				if (match) {
					LispVal rest = nextKeyCell.cdr();
					if (rest instanceof LispCons restCons) {
						valueCell.setCdr(restCons.cdr());
					}
					else {
						valueCell.setCdr(LispNil.INSTANCE);
					}
					return LispTrue.INSTANCE;
				}
				current = nextKeyCell;
			}
			return LispNil.INSTANCE;
		}));
		env.define(LispNames.APPEND, new LispFunction(LispNames.APPEND, args -> {
			if (args.isEmpty()) {
				return LispNil.INSTANCE;
			}
			LispVal result = args.getLast();
			for (int i = args.size() - 2; i >= 0; i--) {
				result = appendTwo(args.get(i), result);
			}
			return result;
		}));
	}

	private static LispVal appendTwo(LispVal list, LispVal tail) {
		if (list instanceof LispNil) {
			return tail;
		}
		if (list instanceof LispCons cons) {
			return new LispCons(cons.car(), appendTwo(cons.cdr(), tail));
		}
		throw new LispEvalException("append expects a list, got: " + list.print());
	}

	private static void registerTypeConversion(Environment env) {
		env.define(LispNames.FLOAT, new LispFunction(LispNames.FLOAT, args -> {
			requireArgCount(LispNames.FLOAT, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispDouble) {
				return arg;
			}
			if (arg instanceof LispInteger i) {
				return new LispDouble((double) i.value());
			}
			if (arg instanceof LispBigInteger b) {
				return new LispDouble(b.value().doubleValue());
			}
			throw new LispEvalException("float expects a number, got: " + arg.print());
		}));
		env.define(LispNames.TRUNCATE, new LispFunction(LispNames.TRUNCATE, args -> {
			requireArgCount(LispNames.TRUNCATE, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispInteger || arg instanceof LispBigInteger) {
				return arg;
			}
			if (arg instanceof LispDouble d) {
				return new LispInteger((long) d.value());
			}
			throw new LispEvalException("truncate expects a number, got: " + arg.print());
		}));
		env.define(LispNames.FLOOR, new LispFunction(LispNames.FLOOR, args -> {
			requireArgCount(LispNames.FLOOR, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispInteger || arg instanceof LispBigInteger) {
				return arg;
			}
			if (arg instanceof LispDouble d) {
				return new LispInteger((long) Math.floor(d.value()));
			}
			throw new LispEvalException("floor expects a number, got: " + arg.print());
		}));
		env.define(LispNames.CEILING, new LispFunction(LispNames.CEILING, args -> {
			requireArgCount(LispNames.CEILING, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispInteger || arg instanceof LispBigInteger) {
				return arg;
			}
			if (arg instanceof LispDouble d) {
				return new LispInteger((long) Math.ceil(d.value()));
			}
			throw new LispEvalException("ceiling expects a number, got: " + arg.print());
		}));
		env.define(LispNames.ROUND, new LispFunction(LispNames.ROUND, args -> {
			requireArgCount(LispNames.ROUND, args, 1);
			LispVal arg = args.get(0);
			if (arg instanceof LispInteger || arg instanceof LispBigInteger) {
				return arg;
			}
			if (arg instanceof LispDouble d) {
				return new LispInteger((long) Math.rint(d.value()));
			}
			throw new LispEvalException("round expects a number, got: " + arg.print());
		}));
	}

	private static long asLong(LispVal val) {
		if (val instanceof LispInteger i) {
			return i.value();
		}
		throw new LispEvalException("Expected integer, got: " + val.print());
	}

	private static double asDouble(LispVal val) {
		if (val instanceof LispDouble d) {
			return d.value();
		}
		if (val instanceof LispInteger i) {
			return (double) i.value();
		}
		if (val instanceof LispBigInteger b) {
			return b.value().doubleValue();
		}
		throw new LispEvalException("Expected number, got: " + val.print());
	}

	private static BigInteger asBigInteger(LispVal val) {
		if (val instanceof LispInteger i) {
			return BigInteger.valueOf(i.value());
		}
		if (val instanceof LispBigInteger b) {
			return b.value();
		}
		throw new LispEvalException("Expected integer, got: " + val.print());
	}

	/**
	 * Normalizes a {@link BigInteger} result, demoting it back to a {@link LispInteger}
	 * when it fits in a {@code long} so that fixnum-range values keep a single
	 * representation.
	 */
	private static LispVal normalizeBig(BigInteger value) {
		// bitLength() < 64 holds exactly for the signed long range [-2^63, 2^63-1].
		return value.bitLength() < 64 ? new LispInteger(value.longValue()) : new LispBigInteger(value);
	}

	private static LispVal addBig(List<LispVal> args) {
		BigInteger result = BigInteger.ZERO;
		for (LispVal arg : args) {
			result = result.add(asBigInteger(arg));
		}
		return normalizeBig(result);
	}

	private static LispVal subBig(List<LispVal> args) {
		if (args.size() == 1) {
			return normalizeBig(asBigInteger(args.get(0)).negate());
		}
		BigInteger result = asBigInteger(args.get(0));
		for (int i = 1; i < args.size(); i++) {
			result = result.subtract(asBigInteger(args.get(i)));
		}
		return normalizeBig(result);
	}

	private static LispVal mulBig(List<LispVal> args) {
		BigInteger result = BigInteger.ONE;
		for (LispVal arg : args) {
			result = result.multiply(asBigInteger(arg));
		}
		return normalizeBig(result);
	}

	private static LispVal divBig(List<LispVal> args) {
		BigInteger result = asBigInteger(args.get(0));
		for (int i = 1; i < args.size(); i++) {
			result = result.divide(asBigInteger(args.get(i)));
		}
		return normalizeBig(result);
	}

	private static int compareBig(List<LispVal> args) {
		return asBigInteger(args.get(0)).compareTo(asBigInteger(args.get(1)));
	}

	private static boolean hasBigInteger(List<LispVal> args) {
		for (LispVal arg : args) {
			if (arg instanceof LispBigInteger) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasDouble(List<LispVal> args) {
		for (LispVal arg : args) {
			if (arg instanceof LispDouble) {
				return true;
			}
		}
		return false;
	}

	private static void requireArgCount(String name, List<LispVal> args, int expected) {
		if (args.size() != expected) {
			throw new LispEvalException(name + " expects " + expected + " arguments, got " + args.size());
		}
	}

}
