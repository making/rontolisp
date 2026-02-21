package am.ik.rontolisp.eval;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
		env.define(LispNames.ADD, new LispFunction(LispNames.ADD, args -> {
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
			if (args.size() == 1) {
				return new LispInteger(-asLong(args.get(0)));
			}
			long result = asLong(args.get(0));
			for (int i = 1; i < args.size(); i++) {
				result -= asLong(args.get(i));
			}
			return new LispInteger(result);
		}));
		env.define(LispNames.MUL, new LispFunction(LispNames.MUL, args -> {
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
		env.define(LispNames.DIV, new LispFunction(LispNames.DIV, args -> {
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
		env.define(LispNames.MOD, new LispFunction(LispNames.MOD, args -> {
			requireArgCount(LispNames.MOD, args, 2);
			if (hasDouble(args)) {
				return new LispDouble(asDouble(args.get(0)) % asDouble(args.get(1)));
			}
			return new LispInteger(asLong(args.get(0)) % asLong(args.get(1)));
		}));
	}

	private static void registerComparison(Environment env) {
		env.define(LispNames.EQ, new LispFunction(LispNames.EQ, args -> {
			requireArgCount(LispNames.EQ, args, 2);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) == asDouble(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) == asLong(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.LT, new LispFunction(LispNames.LT, args -> {
			requireArgCount(LispNames.LT, args, 2);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) < asDouble(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) < asLong(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.GT, new LispFunction(LispNames.GT, args -> {
			requireArgCount(LispNames.GT, args, 2);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) > asDouble(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) > asLong(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.LE, new LispFunction(LispNames.LE, args -> {
			requireArgCount(LispNames.LE, args, 2);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) <= asDouble(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) <= asLong(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.GE, new LispFunction(LispNames.GE, args -> {
			requireArgCount(LispNames.GE, args, 2);
			if (hasDouble(args)) {
				return asDouble(args.get(0)) >= asDouble(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			return asLong(args.get(0)) >= asLong(args.get(1)) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
	}

	private static void registerIO(Environment env, PrintStream out) {
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
	}

	private static void registerPredicates(Environment env) {
		env.define(LispNames.NULL, new LispFunction(LispNames.NULL, args -> {
			requireArgCount(LispNames.NULL, args, 1);
			return args.get(0) instanceof LispNil ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.ATOM, new LispFunction(LispNames.ATOM, args -> {
			requireArgCount(LispNames.ATOM, args, 1);
			return !(args.get(0) instanceof LispCons) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.NUMBERP, new LispFunction(LispNames.NUMBERP, args -> {
			requireArgCount(LispNames.NUMBERP, args, 1);
			LispVal arg = args.get(0);
			return (arg instanceof LispInteger || arg instanceof LispDouble) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}));
		env.define(LispNames.INTEGERP, new LispFunction(LispNames.INTEGERP, args -> {
			requireArgCount(LispNames.INTEGERP, args, 1);
			return args.get(0) instanceof LispInteger ? LispTrue.INSTANCE : LispNil.INSTANCE;
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
