package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;

/**
 * Tree-walking interpreter for Lisp expressions.
 */
public final class LispEvaluator {

	/**
	 * Names that are special operators or macros and therefore have no function value:
	 * {@code (function name)} / {@code #'name} on these is an error, mirroring Common
	 * Lisp. Function-like macros (1+, zerop, ...) are excluded because they are also
	 * registered as functions in the global environment.
	 */
	private static final java.util.Set<String> SPECIAL_OPERATORS = PackageRegistry.specialOperatorNames();

	private final Environment globalEnv;

	private final PackageResolver packageResolver = new PackageResolver();

	private SourceLoader sourceLoader = SourceLoader.fileSystem();

	/**
	 * Create a new evaluator with the given output stream.
	 * @param out the output stream for print operations
	 */
	public LispEvaluator(PrintStream out) {
		this.globalEnv = Environment.createGlobal(out);
		registerEval();
	}

	/**
	 * Create a new evaluator with the given output and input streams.
	 * @param out the output stream for print operations
	 * @param in the input stream for read operations
	 */
	public LispEvaluator(PrintStream out, InputStream in) {
		this.globalEnv = Environment.createGlobal(out, in);
		registerEval();
	}

	/**
	 * Sets the loader used to resolve {@code (load "path")} source text. Defaults to the
	 * local filesystem; environments without a filesystem (e.g. the browser playground)
	 * can install an in-memory loader.
	 * @param loader the source loader
	 */
	public void setSourceLoader(SourceLoader loader) {
		this.sourceLoader = java.util.Objects.requireNonNull(loader);
	}

	private void registerEval() {
		this.globalEnv.defineFunction(LispNames.EVAL, new LispFunction(LispNames.EVAL, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.EVAL + " expects 1 argument, got " + args.size());
			}
			return eval(args.get(0));
		}));
		this.globalEnv.defineFunction(LispNames.SYMBOL_FUNCTION, new LispFunction(LispNames.SYMBOL_FUNCTION, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.SYMBOL_FUNCTION + " expects 1 argument, got " + args.size());
			}
			if (!(args.get(0) instanceof LispSymbol sym)) {
				throw new LispEvalException(
						LispNames.SYMBOL_FUNCTION + " expects a symbol, got " + args.get(0).print());
			}
			return resolveFunction(sym.name());
		}));
		this.globalEnv.defineFunction(LispNames.FUNCALL, new LispFunction(LispNames.FUNCALL, args -> {
			if (args.isEmpty()) {
				throw new LispEvalException(LispNames.FUNCALL + " expects at least 1 argument");
			}
			return apply(args.get(0), args.subList(1, args.size()), this.globalEnv);
		}));
		this.globalEnv.defineFunction(LispNames.MAP, new LispFunction(LispNames.MAP, args -> {
			if (args.size() != 2) {
				throw new LispEvalException(LispNames.MAP + " expects 2 arguments, got " + args.size());
			}
			return mapValues(args.get(0), args.get(1));
		}));
		this.globalEnv.defineFunction(LispNames.REDUCE, new LispFunction(LispNames.REDUCE, args -> {
			if (args.size() == 2) {
				LispVal list = args.get(1);
				if (!(list instanceof LispCons first)) {
					throw new LispEvalException("reduce requires a non-empty list when no initial value is provided");
				}
				return reduceValues(args.get(0), first.car(), first.cdr());
			}
			if (args.size() == 3) {
				return reduceValues(args.get(0), args.get(1), args.get(2));
			}
			throw new LispEvalException(LispNames.REDUCE + " expects 2 or 3 arguments, got " + args.size());
		}));
		this.globalEnv.defineFunction(LispNames.LOAD, new LispFunction(LispNames.LOAD, args -> {
			if (args.size() != 1) {
				throw new LispEvalException(LispNames.LOAD + " expects 1 argument, got " + args.size());
			}
			if (!(args.get(0) instanceof LispString path)) {
				throw new LispEvalException(LispNames.LOAD + " expects a string argument");
			}
			String source;
			try {
				source = this.sourceLoader.load(path.value());
			}
			catch (IOException ex) {
				throw new LispEvalException(
						LispNames.LOAD + ": cannot read file " + path.value() + ": " + ex.getMessage());
			}
			// Evaluate every top-level form in the global environment so that
			// definitions become reusable after load returns. Route through the
			// top-level entry so package directives in the loaded file are processed.
			for (LispVal form : LispReader.readAllFromString(source)) {
				eval(form);
			}
			return LispTrue.INSTANCE;
		}));
	}

	/**
	 * Evaluate an expression in the global environment.
	 * @param expr the expression to evaluate
	 * @return the result
	 */
	public LispVal eval(LispVal expr) {
		// Resolve packages at the top-level entry only; nested evaluation and macro
		// expansion operate on the already-resolved canonical form.
		return eval(this.packageResolver.resolve(expr), this.globalEnv);
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
			case LispBigInteger b -> b;
			case LispRatio r -> r;
			case LispDouble d -> d;
			case LispString s -> s;
			case LispNil n -> n;
			case LispTrue t -> t;
			case LispFunction f -> f;
			case LispLambda l -> l;
			case LispSymbol sym -> sym.isKeyword() ? sym : env.lookup(sym.name());
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
					return evalDefun(cons, env);
				case LispNames.FUNCTION:
					return evalFunction(cons, env);
				case LispNames.PROGN:
					return evalProgn(cons, env);
				case LispNames.SETQ:
					return evalSetq(cons, env);
				case LispNames.LAMBDA:
					return evalLambdaForm(cons, env);
				case LispNames.WHILE:
					return evalWhile(cons, env);
				case LispNames.COND:
					return eval(LispMacroExpander.expandCond(cons), env);
				case LispNames.AND:
					return eval(LispMacroExpander.expandAnd(cons), env);
				case LispNames.OR:
					return eval(LispMacroExpander.expandOr(cons), env);
				case LispNames.WHEN:
					return eval(LispMacroExpander.expandWhen(cons), env);
				case LispNames.DOTIMES:
					return eval(LispMacroExpander.expandDotimes(cons), env);
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
				case LispNames.REST:
					return eval(LispMacroExpander.expandRest(cons), env);
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
				case LispNames.PUSH:
					return eval(LispMacroExpander.expandPush(cons), env);
				case LispNames.POP:
					return eval(LispMacroExpander.expandPop(cons), env);
				case LispNames.REMF:
					return eval(LispMacroExpander.expandRemf(cons), env);
				case LispNames.LET_STAR:
					return eval(LispMacroExpander.expandLetStar(cons), env);
				case LispNames.DOLIST:
					return eval(LispMacroExpander.expandDolist(cons), env);
				case LispNames.INCF:
					return eval(LispMacroExpander.expandIncf(cons), env);
				case LispNames.DECF:
					return eval(LispMacroExpander.expandDecf(cons), env);
			}
			if (LispMacroExpander.isCarCdrComposition(sym.name())) {
				return eval(LispMacroExpander.expandCarCdrComposition(cons), env);
			}
			// Lisp-2: a symbol in call position is resolved in the function namespace
			// only; variable bindings of the same name do not shadow it.
			LispVal function = resolveFunction(sym.name());
			List<LispVal> args = evalArgs(cons, env);
			return apply(function, args, env);
		}
		// Non-symbol head: a lambda form such as ((lambda (x) x) 5)
		LispVal function = eval(head, env);
		List<LispVal> args = evalArgs(cons, env);
		return apply(function, args, env);
	}

	private LispVal evalDefun(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		LispSymbol name = (LispSymbol) parts.get(1);
		List<LispSymbol> params = extractParams(parts.get(2));
		List<LispVal> body = parts.subList(3, parts.size());
		// defun installs into the global function namespace, capturing the current
		// lexical environment, and returns the function name like Common Lisp.
		this.globalEnv.defineFunction(name.name(), new LispLambda(params, body, env));
		return name;
	}

	private LispVal evalFunction(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new LispEvalException(LispNames.FUNCTION + " expects exactly one argument");
		}
		LispVal designator = parts.get(1);
		if (designator instanceof LispCons lambdaForm && lambdaForm.car() instanceof LispSymbol op
				&& LispNames.LAMBDA.equals(op.name())) {
			return evalLambdaForm(lambdaForm, env);
		}
		if (designator instanceof LispSymbol sym) {
			return resolveFunction(sym.name());
		}
		throw new LispEvalException(
				LispNames.FUNCTION + " expects a function name or lambda expression, got " + designator.print());
	}

	/**
	 * Resolves a function designator name against the global function namespace.
	 * @param name the function name
	 * @return the function value
	 * @throws LispEvalException if the name is a special operator or macro, or undefined
	 */
	private LispVal resolveFunction(String name) {
		if (SPECIAL_OPERATORS.contains(name)) {
			throw new LispEvalException(name + " is a macro or special operator, not a function");
		}
		LispVal fn = this.globalEnv.lookupFunctionOrNull(name);
		if (fn != null) {
			return fn;
		}
		if (LispMacroExpander.isCarCdrComposition(name)) {
			// Synthesize (lambda (x) (cadr x)) so car/cdr compositions are first-class.
			LispSymbol param = new LispSymbol("x");
			LispVal call = new LispCons(new LispSymbol(name), new LispCons(param, LispNil.INSTANCE));
			return new LispLambda(List.of(param), List.of(call), this.globalEnv);
		}
		throw new LispEvalException("The function " + name + " is undefined");
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

	private LispVal evalWhile(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		LispVal test = parts.get(1);
		while (isTruthy(eval(test, env))) {
			for (int i = 2; i < parts.size(); i++) {
				eval(parts.get(i), env);
			}
		}
		return LispNil.INSTANCE;
	}

	private LispVal evalLambdaForm(LispCons cons, Environment env) {
		List<LispVal> parts = cons.toList();
		List<LispSymbol> params = extractParams(parts.get(1));
		List<LispVal> body = parts.subList(2, parts.size());
		return new LispLambda(params, body, env);
	}

	private LispVal mapValues(LispVal function, LispVal list) {
		List<LispVal> results = new ArrayList<>();
		while (list instanceof LispCons cell) {
			LispVal mapped = apply(function, List.of(cell.car()), this.globalEnv);
			results.add(mapped);
			list = cell.cdr();
		}
		if (results.isEmpty()) {
			return LispNil.INSTANCE;
		}
		LispVal result = LispNil.INSTANCE;
		for (int i = results.size() - 1; i >= 0; i--) {
			result = new LispCons(results.get(i), result);
		}
		return result;
	}

	private LispVal reduceValues(LispVal function, LispVal accumulator, LispVal list) {
		while (list instanceof LispCons cell) {
			accumulator = apply(function, List.of(accumulator, cell.car()), this.globalEnv);
			list = cell.cdr();
		}
		return accumulator;
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
		if (function instanceof LispSymbol sym) {
			// A symbol is a function designator naming its global function (CL-style).
			function = resolveFunction(sym.name());
		}
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
