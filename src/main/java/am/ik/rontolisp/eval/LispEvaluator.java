package am.ik.rontolisp.eval;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
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

	/**
	 * Create a new evaluator with the given output stream.
	 * @param out the output stream for print operations
	 */
	public LispEvaluator(PrintStream out) {
		this.globalEnv = Environment.createGlobal(out);
	}

	/**
	 * Evaluate an expression in the global environment.
	 * @param expr the expression to evaluate
	 * @return the result
	 */
	public LispVal eval(LispVal expr) {
		return eval(expr, this.globalEnv);
	}

	/**
	 * Evaluate an expression in the given environment.
	 * @param expr the expression to evaluate
	 * @param env the lexical environment
	 * @return the result
	 */
	public LispVal eval(LispVal expr, Environment env) {
		return switch (expr) {
			case LispInteger i -> i;
			case LispDouble d -> d;
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
				case LispNames.QUOTE:
					return evalQuote(cons);
				case LispNames.IF:
					return evalIf(cons, env);
				case LispNames.LET:
					return evalLet(cons, env);
				case LispNames.DEFUN:
					return eval(LispMacroExpander.expandDefun(cons), env);
				case LispNames.PROGN:
					return evalProgn(cons, env);
				case LispNames.SETQ:
					return evalSetq(cons, env);
				case LispNames.LAMBDA:
					return evalLambdaForm(cons, env);
				case LispNames.FUNCALL:
					return evalFuncall(cons, env);
				case LispNames.COND:
					return eval(LispMacroExpander.expandCond(cons), env);
				case LispNames.AND:
					return eval(LispMacroExpander.expandAnd(cons), env);
				case LispNames.OR:
					return eval(LispMacroExpander.expandOr(cons), env);
				case LispNames.WHEN:
					return eval(LispMacroExpander.expandWhen(cons), env);
				case LispNames.UNLESS:
					return eval(LispMacroExpander.expandUnless(cons), env);
				case LispNames.ONE_PLUS:
					return eval(LispMacroExpander.expandOnePlus(cons), env);
				case LispNames.ONE_MINUS:
					return eval(LispMacroExpander.expandOneMinus(cons), env);
				case LispNames.ZEROP:
					return eval(LispMacroExpander.expandZerop(cons), env);
				case LispNames.PLUSP:
					return eval(LispMacroExpander.expandPlusp(cons), env);
				case LispNames.MINUSP:
					return eval(LispMacroExpander.expandMinusp(cons), env);
				case LispNames.EVENP:
					return eval(LispMacroExpander.expandEvenp(cons), env);
				case LispNames.ODDP:
					return eval(LispMacroExpander.expandOddp(cons), env);
				case LispNames.FIRST:
					return eval(LispMacroExpander.expandFirst(cons), env);
				case LispNames.NTH:
					return eval(LispMacroExpander.expandNth(cons), env);
				case LispNames.SECOND:
					return eval(LispMacroExpander.expandSecond(cons), env);
				case LispNames.THIRD:
					return eval(LispMacroExpander.expandThird(cons), env);
				case LispNames.FOURTH:
					return eval(LispMacroExpander.expandFourth(cons), env);
				case LispNames.SETF:
					return eval(LispMacroExpander.expandSetf(cons), env);
			}
			if (LispMacroExpander.isCarCdrComposition(sym.name())) {
				return eval(LispMacroExpander.expandCarCdrComposition(cons), env);
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

	private LispVal evalFuncall(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		LispVal function = eval(parts.get(1), env);
		List<LispVal> args = new ArrayList<>();
		for (int i = 2; i < parts.size(); i++) {
			args.add(eval(parts.get(i), env));
		}
		return apply(function, args, env);
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
