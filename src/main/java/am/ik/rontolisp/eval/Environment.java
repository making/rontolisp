package am.ik.rontolisp.eval;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.Scope;
import org.jspecify.annotations.Nullable;

/**
 * Lexical environment for variable bindings.
 */
public final class Environment implements Scope {

	private final Map<String, LispVal> bindings;

	@Nullable private final Environment parent;

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

	public void define(String name, LispVal value) {
		this.bindings.put(name, value);
	}

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

	public static Environment createGlobal(PrintStream out) {
		Environment env = new Environment(null);
		registerArithmetic(env);
		registerComparison(env);
		registerIO(env, out);
		registerPredicates(env);
		registerListOps(env);
		return env;
	}

	private static void registerArithmetic(Environment env) {
		env.define("+", new LispFunction("+", args -> {
			if (hasDouble(args)) {
				double result = 0;
				for (LispVal arg : args) {
					result += asDouble(arg);
				}
				return new LispDouble(result);
			}
			long result = 0;
			for (LispVal arg : args) {
				result += asLong(arg);
			}
			return new LispInteger(result);
		}));
		env.define("-", new LispFunction("-", args -> {
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
			if (args.size() == 1) {
				return new LispInteger(-asLong(args.get(0)));
			}
			long result = asLong(args.get(0));
			for (int i = 1; i < args.size(); i++) {
				result -= asLong(args.get(i));
			}
			return new LispInteger(result);
		}));
		env.define("*", new LispFunction("*", args -> {
			if (hasDouble(args)) {
				double result = 1;
				for (LispVal arg : args) {
					result *= asDouble(arg);
				}
				return new LispDouble(result);
			}
			long result = 1;
			for (LispVal arg : args) {
				result *= asLong(arg);
			}
			return new LispInteger(result);
		}));
		env.define("/", new LispFunction("/", args -> {
			if (hasDouble(args)) {
				double result = asDouble(args.get(0));
				for (int i = 1; i < args.size(); i++) {
					result /= asDouble(args.get(i));
				}
				return new LispDouble(result);
			}
			long result = asLong(args.get(0));
			for (int i = 1; i < args.size(); i++) {
				result /= asLong(args.get(i));
			}
			return new LispInteger(result);
		}));
		env.define("mod", new LispFunction("mod", args -> {
			requireArgCount("mod", args, 2);
			if (hasDouble(args)) {
				return new LispDouble(asDouble(args.get(0)) % asDouble(args.get(1)));
			}
			return new LispInteger(asLong(args.get(0)) % asLong(args.get(1)));
		}));
	}

	private static void registerComparison(Environment env) {
		env.define("=", new LispFunction("=", args -> {
			requireArgCount("=", args, 2);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) == asDouble(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) == asLong(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define("<", new LispFunction("<", args -> {
			requireArgCount("<", args, 2);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) < asDouble(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) < asLong(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(">", new LispFunction(">", args -> {
			requireArgCount(">", args, 2);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) > asDouble(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) > asLong(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define("<=", new LispFunction("<=", args -> {
			requireArgCount("<=", args, 2);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) <= asDouble(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) <= asLong(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(">=", new LispFunction(">=", args -> {
			requireArgCount(">=", args, 2);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) >= asDouble(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) >= asLong(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
	}

	private static void registerIO(Environment env, PrintStream out) {
		env.define("print", new LispFunction("print", args -> {
			requireArgCount("print", args, 1);
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
	}

	private static void registerPredicates(Environment env) {
		env.define("null", new LispFunction("null", args -> {
			requireArgCount("null", args, 1);
			return args.get(0) instanceof LispNil ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
	}

	private static void registerListOps(Environment env) {
		env.define("cons", new LispFunction("cons", args -> {
			requireArgCount("cons", args, 2);
			return new LispCons(args.get(0), args.get(1));
		}));
		env.define("car", new LispFunction("car", args -> {
			requireArgCount("car", args, 1);
			if (args.get(0) instanceof LispCons cons) {
				return cons.car();
			}
			throw new LispEvalException("car expects a cons cell, got: " + args.get(0).print());
		}));
		env.define("cdr", new LispFunction("cdr", args -> {
			requireArgCount("cdr", args, 1);
			if (args.get(0) instanceof LispCons cons) {
				return cons.cdr();
			}
			throw new LispEvalException("cdr expects a cons cell, got: " + args.get(0).print());
		}));
		env.define("list", new LispFunction("list", args -> {
			LispVal result = LispNil.INSTANCE;
			for (int i = args.size() - 1; i >= 0; i--) {
				result = new LispCons(args.get(i), result);
			}
			return result;
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
		throw new LispEvalException("Expected number, got: " + val.print());
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
