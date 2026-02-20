package am.ik.rontolisp.eval;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;

/**
 * Tree-walking interpreter for Lisp expressions.
 */
public final class LispEvaluator {

	private final Environment globalEnv;

	public LispEvaluator(PrintStream out) {
		this.globalEnv = Environment.createGlobal(out);
	}

	public LispVal eval(LispVal expr) {
		return eval(expr, this.globalEnv);
	}

	public LispVal eval(LispVal expr, Environment env) {
		return switch (expr) {
			case LispInteger i -> i;
			case LispString s -> s;
			case LispNil n -> n;
			case LispTrue t -> t;
			case LispFunction f -> f;
			case LispLambda l -> l;
			case LispSymbol sym -> env.lookup(sym.name());
			case LispCons cons -> evalCons(cons, env);
		};
	}

	private LispVal evalCons(LispCons cons, Environment env) {
		LispVal head = cons.car();
		if (head instanceof LispSymbol sym) {
			switch (sym.name()) {
				case "quote":
					return evalQuote(cons);
				case "if":
					return evalIf(cons, env);
				case "let":
					return evalLet(cons, env);
				case "defun":
					return evalDefun(cons, env);
				case "progn":
					return evalProgn(cons, env);
				case "setq":
					return evalSetq(cons, env);
				case "lambda":
					return evalLambdaForm(cons, env);
			}
		}
		// Function application
		LispVal function = eval(head, env);
		List<LispVal> args = evalArgs(cons, env);
		return apply(function, args, env);
	}

	private LispVal evalQuote(LispCons cons) {
		LispCons rest = (LispCons) cons.cdr();
		return rest.car();
	}

	private LispVal evalIf(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		LispVal condition = eval(parts.get(1), env);
		if (isTruthy(condition)) {
			return eval(parts.get(2), env);
		}
		if (parts.size() > 3) {
			return eval(parts.get(3), env);
		}
		return LispNil.INSTANCE;
	}

	private LispVal evalLet(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		Environment letEnv = new Environment(env);
		// parts.get(1) is the bindings list: ((x 1) (y 2))
		LispVal bindings = parts.get(1);
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons bindPair = (LispCons) binding;
				List<LispVal> pair = bindPair.toList();
				String name = ((LispSymbol) pair.get(0)).name();
				LispVal value = eval(pair.get(1), env);
				letEnv.define(name, value);
			}
		}
		// Evaluate body expressions
		LispVal result = LispNil.INSTANCE;
		for (int i = 2; i < parts.size(); i++) {
			result = eval(parts.get(i), letEnv);
		}
		return result;
	}

	private LispVal evalDefun(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		LispSymbol name = (LispSymbol) parts.get(1);
		List<LispSymbol> params = extractParams(parts.get(2));
		List<LispVal> body = parts.subList(3, parts.size());
		LispLambda lambda = new LispLambda(params, body, env);
		this.globalEnv.define(name.name(), lambda);
		return name;
	}

	private LispVal evalProgn(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		LispVal result = LispNil.INSTANCE;
		for (int i = 1; i < parts.size(); i++) {
			result = eval(parts.get(i), env);
		}
		return result;
	}

	private LispVal evalSetq(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		LispSymbol name = (LispSymbol) parts.get(1);
		LispVal value = eval(parts.get(2), env);
		env.set(name.name(), value);
		return value;
	}

	private LispVal evalLambdaForm(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		List<LispSymbol> params = extractParams(parts.get(1));
		List<LispVal> body = parts.subList(2, parts.size());
		return new LispLambda(params, body, env);
	}

	private List<LispVal> evalArgs(LispCons cons, Environment env) {
		List<LispVal> args = new ArrayList<>();
		LispVal rest = cons.cdr();
		while (rest instanceof LispCons argCons) {
			args.add(eval(argCons.car(), env));
			rest = argCons.cdr();
		}
		return args;
	}

	private LispVal apply(LispVal function, List<LispVal> args, Environment env) {
		if (function instanceof LispFunction builtIn) {
			return builtIn.body().apply(args);
		}
		if (function instanceof LispLambda lambda) {
			Environment lambdaEnv = new Environment((Environment) lambda.closure());
			for (int i = 0; i < lambda.params().size(); i++) {
				lambdaEnv.define(lambda.params().get(i).name(), args.get(i));
			}
			LispVal result = LispNil.INSTANCE;
			for (LispVal bodyExpr : lambda.body()) {
				result = eval(bodyExpr, lambdaEnv);
			}
			return result;
		}
		throw new LispEvalException("Not a function: " + function.print());
	}

	private List<LispSymbol> extractParams(LispVal paramList) {
		List<LispSymbol> params = new ArrayList<>();
		if (paramList instanceof LispCons paramCons) {
			for (LispVal p : paramCons.toList()) {
				params.add((LispSymbol) p);
			}
		}
		return params;
	}

	private boolean isTruthy(LispVal val) {
		return !(val instanceof LispNil);
	}

}
