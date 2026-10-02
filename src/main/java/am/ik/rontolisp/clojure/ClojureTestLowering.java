package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The {@code clojure.test} slice of the Clojure lowering: {@code deftest}, {@code is}
 * (with {@code thrown?} and {@code thrown-with-msg?}), {@code are}, {@code testing},
 * {@code run-tests}, {@code run-all-tests} and {@code successful?} over the run-time half
 * in {@code clojure.lisp} ({@code rontolisp::%clojure-test-*}).
 *
 * <p>
 * The shapes follow the oracle's macro expansions (clj 1.12.6.1673): a {@code deftest} is
 * a zero-argument function (its body a {@code c%name%body} function of its own, so a
 * {@code recur} in it targets the body like the oracle's inner {@code fn}) registered per
 * namespace in definition order; an {@code is} is a thunk behind the error report, around
 * a predicate call (arguments evaluated first, so a failure shows
 * {@code (not (f values...))}), any other form (a failure shows the value), or
 * {@code thrown?}/{@code thrown-with-msg?}; {@code are} substitutes its template per
 * argument group at lower time ({@code clojure.template/do-template}); {@code testing}
 * pushes its context around a thunk. Every report names its {@code is} form's
 * {@code (file:line)}, baked at lower time, like the oracle's.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureTestLowering {

	private ClojureTestLowering() {
	}

	/** The namespace this slice serves. */
	static final String NAMESPACE = "clojure.test";

	/**
	 * The {@code clojure.test} vars this front end implements (plus {@code use-fixtures},
	 * refused by name).
	 */
	static final Set<String> VARS = Set.of("deftest", "deftest-", "is", "are", "testing", "run-tests", "run-all-tests",
			"successful?", "use-fixtures");

	/** The vars that are macros in the oracle: no function value. */
	static final Set<String> MACROS = Set.of("deftest", "deftest-", "is", "are", "testing");

	/**
	 * The oracle's core macros and special forms: an {@code is} over one of these is the
	 * "any form" assertion (a failure shows the value), not a predicate call -- the
	 * oracle's {@code function?} test resolves the head to a var holding a function.
	 */
	static final Set<String> CORE_MACROS = Set.of("def", "if", "do", "let", "let*", "letfn", "letfn*", "loop", "loop*",
			"recur", "fn", "fn*", "quote", "var", "throw", "try", "new", "set!", ".", "..", "->", "->>", "as->",
			"cond->", "cond->>", "some->", "some->>", "and", "or", "when", "when-not", "when-let", "when-first",
			"when-some", "if-let", "if-not", "if-some", "cond", "condp", "case", "doto", "dotimes", "doseq", "for",
			"while", "with-open", "with-out-str", "with-in-str", "with-local-vars", "with-redefs", "binding", "dosync",
			"sync", "io!", "time", "lazy-seq", "lazy-cat", "delay", "future", "assert", "comment", "declare", "defn",
			"defn-", "defmacro", "defmulti", "defmethod", "defonce", "defprotocol", "defrecord", "deftype",
			"definterface", "defstruct", "extend-protocol", "extend-type", "reify", "proxy", "memfn", "import", "ns",
			"locking", "gen-class", "vswap!", "monitor-enter", "monitor-exit");

	/** The run-time helpers, as the lowered program spells them. */
	static final String INIT = "RONTOLISP::%CLOJURE-TEST-INIT";

	/**
	 * The runtime the program runs first when it uses {@code clojure.test}: the report
	 * stream captured (the oracle's {@code *test-out*}) and the ex-info reader the error
	 * report spells the oracle's way.
	 */
	static List<LispVal> testRuntime(ClojureLowering ctx) {
		LispSymbol e = new LispSymbol("e");
		LispVal exInfo = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(e),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(new LispSymbol("C%E-EX-INFO?"), e),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"),
								ClojureLowerUtil.list(new LispSymbol("C%E-EX-INFO-MESSAGE"), e),
								ClojureLowerUtil.list(new LispSymbol("C%E-EX-INFO-DATA"), e)),
						ClojureLowering.NIL_CONST));
		return List.of(ClojureLowerUtil.list(new LispSymbol(INIT), exInfo));
	}

	/** Marks the program as a {@code clojure.test} user: the runtime travels with it. */
	static void use(ClojureLowering ctx) {
		ctx.usedTest = true;
		ctx.usedExInfo = true; // the error report reads ex-info conditions
	}

	/**
	 * The {@code clojure.test} var a head names -- referred, aliased or fully qualified,
	 * and not shadowed by a user definition or a local -- or null.
	 */
	static @Nullable String testVarOf(ClojureLowering ctx, LispVal head) {
		if (!(head instanceof LispSymbol s)) {
			return null;
		}
		String name = s.name();
		if (name.indexOf('/') > 0) {
			ClojureLowering.VarRef qualified = ClojureNamespaceLowering.resolveQualified(ctx, name);
			return qualified != null && qualified.ns().equals(NAMESPACE) ? qualified.var() : null;
		}
		if (ctx.known(name)) {
			return null;
		}
		ClojureLowering.VarRef referred = ctx.ns().refers.get(name);
		return referred != null && referred.ns().equals(NAMESPACE) ? referred.var() : null;
	}

	/** Whether a top-level datum's head names a test definition. */
	static boolean isDeftestHead(ClojureLowering ctx, LispVal head) {
		String var = testVarOf(ctx, head);
		return "deftest".equals(var) || "deftest-".equals(var);
	}

	/**
	 * Whether the pre-scan should register a test definition's name: any head spelled
	 * {@code deftest}/{@code deftest-}, bare or qualified (the aliases are wired only in
	 * pass two, so the spelling decides).
	 */
	static boolean isDeftestSpelling(LispVal head) {
		if (!(head instanceof LispSymbol s)) {
			return false;
		}
		String name = s.name();
		int slash = name.lastIndexOf('/');
		String tail = slash > 0 ? name.substring(slash + 1) : name;
		return tail.equals("deftest") || tail.equals("deftest-");
	}

	/** One call of a {@code clojure.test} var. */
	static LispVal testCall(ClojureLowering ctx, String var, List<LispVal> items, @Nullable LispVal form) {
		use(ctx);
		return switch (var) {
			case "deftest", "deftest-" -> {
				List<LispVal> forms = deftestForms(ctx, items, form);
				yield ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), forms);
			}
			case "is" -> isOf(ctx, items, location(ctx, form));
			case "are" -> areOf(ctx, items, location(ctx, form));
			case "testing" -> testingOf(ctx, items);
			case "run-tests" -> runTestsOf(ctx, items);
			case "run-all-tests" -> runAllTestsOf(ctx, items);
			case "successful?" -> {
				ClojureLowerUtil.isTrue(items.size() == 2, "successful? takes a summary");
				yield ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TEST-SUCCESSFUL"),
						ctx.lower(items.get(1)));
			}
			case "use-fixtures" ->
				throw new LispReadException("use-fixtures is not supported yet: fixtures need a design");
			default -> throw new LispReadException("unknown name: clojure.test/" + var);
		};
	}

	/** A {@code clojure.test} var as a function value. */
	static LispVal testValue(ClojureLowering ctx, String var) {
		use(ctx);
		if (MACROS.contains(var)) {
			throw new LispReadException("Can't take value of a macro: #'clojure.test/" + var);
		}
		return switch (var) {
			case "run-tests" -> {
				LispSymbol nss = new LispSymbol(ClojureLowering.mangle("run-tests-nss"));
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
						ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, nss),
						runTests(ctx, ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), nss, nss, currentNsList(ctx))));
			}
			case "run-all-tests" -> {
				LispSymbol re = new LispSymbol(ClojureLowering.mangle("run-all-tests-re"));
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("&optional"), re),
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TEST-RUN-ALL"), re, knownList(ctx)));
			}
			case "successful?" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("function"),
					new LispSymbol("RONTOLISP::%CLOJURE-TEST-SUCCESSFUL"));
			case "use-fixtures" ->
				throw new LispReadException("use-fixtures is not supported yet: fixtures need a design");
			default -> throw new LispReadException("unknown name: clojure.test/" + var);
		};
	}

	/**
	 * {@code (deftest name body...)}: the body as a function of its own (the recur
	 * target, like the oracle's inner {@code fn}), the test function running it through
	 * the reporting wrapper, and the registration under the current namespace.
	 */
	static List<LispVal> deftestForms(ClojureLowering ctx, List<LispVal> items, @Nullable LispVal form) {
		use(ctx);
		ClojureLowerUtil.isTrue(items.size() >= 2, "deftest takes a name and a body");
		String name = ClojureLowerUtil.plainName(items.get(1), "deftest");
		String key = ctx.intern(name, false);
		ctx.globals.put(key, ClojureLowering.Kind.FUNCTION);
		ctx.macros.remove(key);
		String loc = location(ctx, form);
		LispSymbol fn = ClojureLowering.varSym(key);
		LispSymbol bodyFn = new LispSymbol(fn.name() + "%body");
		ClojureLowering.RecurTarget target = new ClojureLowering.RecurTarget(bodyFn.name(), true);
		String outer = ctx.testLocation;
		ctx.testLocation = loc;
		ClojureLowering.Clause clause;
		try {
			clause = ClojureBindingLowering.clause(ctx, ClojureLowerUtil.list(ClojureReader.VECTOR),
					items.subList(2, items.size()), target);
		}
		finally {
			ctx.testLocation = outer;
		}
		List<LispVal> forms = new ArrayList<>();
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), bodyFn, LispNil.INSTANCE, clause.wrapped()));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), fn, LispNil.INSTANCE,
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TEST-VAR"), LispString.literal(name),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), bodyFn), LispString.literal(loc))));
		forms.add(ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TEST-REGISTER"),
				LispString.literal(ctx.currentNs), LispString.literal(name),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), fn)));
		return forms;
	}

	/**
	 * {@code (is form msg?)}: the assertion as a thunk behind the error report. The
	 * expected form and the message are bound once, ahead of the assertion, so both the
	 * failure and the error report read the same values. The whole form lowers behind the
	 * {@code try} barrier (the oracle wraps every assertion in a {@code try}).
	 */
	static LispVal isOf(ClojureLowering ctx, List<LispVal> items, String loc) {
		ClojureLowerUtil.isTrue(items.size() == 2 || items.size() == 3, "is takes a form and an optional message");
		LispVal form = items.get(1);
		ctx.tryDepth++;
		try {
			LispSymbol expected = ctx.freshTemp();
			LispSymbol msg = ctx.freshTemp();
			LispVal msgForm = items.size() == 3 ? ctx.lower(items.get(2)) : ClojureLowering.NIL_CONST;
			LispVal assertion = assertion(ctx, form, expected, msg, LispString.literal(loc));
			LispVal thunk = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), LispNil.INSTANCE, assertion);
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(ClojureLowerUtil.list(expected, ctx.quote(form)),
							ClojureLowerUtil.list(msg, msgForm)),
					ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TEST-TRY"), thunk, expected, msg,
							LispString.literal(loc)));
		}
		finally {
			ctx.tryDepth--;
		}
	}

	/**
	 * The assertion kind the form takes, as the oracle's {@code assert-expr} picks it.
	 */
	static LispVal assertion(ClojureLowering ctx, LispVal form, LispSymbol expected, LispSymbol msg, LispVal loc) {
		List<LispVal> items = form instanceof LispCons ? ClojureLowerUtil.items(form) : null;
		List<LispVal> parts = items == null ? List.of() : items;
		LispVal head = parts.isEmpty() ? LispNil.INSTANCE : parts.get(0);
		if (ClojureLowerUtil.isSymbolNamed(head, "thrown?")) {
			ClojureLowerUtil.isTrue(parts.size() >= 2, "thrown? takes a class and a body");
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TEST-THROWN"), thunkOf(ctx, parts, 2),
					expected, msg, loc);
		}
		if (ClojureLowerUtil.isSymbolNamed(head, "thrown-with-msg?")) {
			ClojureLowerUtil.isTrue(parts.size() >= 3, "thrown-with-msg? takes a class, a pattern and a body");
			LispVal re = ctx.lower(parts.get(2));
			return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TEST-THROWN-MSG"), thunkOf(ctx, parts, 3),
					re, expected, msg, loc);
		}
		if (isPredicateHead(ctx, head)) {
			return predicate(ctx, parts, expected, msg, loc);
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TEST-ANY"), ctx.lower(form), expected, msg,
				loc);
	}

	/** The body forms from {@code from} as a zero-argument lambda. */
	static LispVal thunkOf(ClojureLowering ctx, List<LispVal> parts, int from) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), LispNil.INSTANCE, ctx.nonTailBody(parts, from));
	}

	/**
	 * Whether {@code (head ...)} is a predicate call to the oracle: the head resolves to
	 * a var holding a function. A local never resolves (it is no var), a keyword or an
	 * interop spelling neither, a macro or special form takes the any-form path, and a
	 * {@code def}'d variable counts only when it is known to hold a function.
	 */
	static boolean isPredicateHead(ClojureLowering ctx, LispVal head) {
		if (!(head instanceof LispSymbol s)) {
			return false;
		}
		String name = s.name();
		if (name.startsWith(":") || name.startsWith(".") || name.endsWith(".") || name.startsWith("%")) {
			return false;
		}
		for (Map<String, ClojureLowering.Kind> scope : ctx.scopes) {
			if (scope.containsKey(name)) {
				return false;
			}
		}
		String key = ctx.resolveVar(name);
		ClojureLowering.Kind global = key == null ? null : ctx.globals.get(key);
		if (global != null) {
			return switch (global) {
				case FUNCTION -> true;
				case MACRO, MUTABLE_FIELD -> false; // a field is never global
				case VARIABLE -> ctx.globalDirectFuns.contains(key);
			};
		}
		if (name.indexOf('/') > 0) {
			ClojureLowering.VarRef qualified = ClojureNamespaceLowering.resolveQualified(ctx, name);
			return qualified != null && !(qualified.ns().equals(NAMESPACE) && MACROS.contains(qualified.var()));
		}
		ClojureLowering.VarRef referred = ClojureNamespaceLowering.libraryRefer(ctx, name);
		if (referred != null) {
			return !(referred.ns().equals(NAMESPACE) && MACROS.contains(referred.var()));
		}
		return !CORE_MACROS.contains(name);
	}

	/**
	 * A predicate call: the evaluated arguments bound first (a literal or a function name
	 * stays in place), the call over them, and the {@code (not (f values...))} form a
	 * failure shows.
	 */
	static LispVal predicate(ClojureLowering ctx, List<LispVal> parts, LispSymbol expected, LispSymbol msg,
			LispVal loc) {
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> callDatum = new ArrayList<>();
		List<LispVal> shown = new ArrayList<>();
		callDatum.add(parts.get(0));
		shown.add(ctx.quote(parts.get(0)));
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		for (int i = 1; i < parts.size(); i++) {
			LispVal arg = parts.get(i);
			if (staysInPlace(ctx, arg)) {
				callDatum.add(arg);
				shown.add(
						arg instanceof LispSymbol s && isFunctionName(ctx, s.name()) ? ctx.quote(arg) : ctx.lower(arg));
				continue;
			}
			String temp = "%is-arg" + ctx.counter++;
			bindings.add(ClojureLowerUtil.list(ClojureLowerUtil.idSym(temp), ctx.lower(arg)));
			scope.put(temp, ClojureLowering.Kind.VARIABLE);
			callDatum.add(new LispSymbol(temp));
			shown.add(ClojureLowerUtil.idSym(temp));
		}
		LispVal call = ctx.inScope(scope, () -> ctx.lower(ClojureLowerUtil.list(callDatum)));
		LispVal notForm = ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ctx.quote(new LispSymbol("not")),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), shown));
		LispVal report = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TEST-PRED"), call, notForm, expected,
				msg, loc);
		return bindings.isEmpty() ? report
				: ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(bindings), report);
	}

	/**
	 * Whether an argument stays in the call as written: a self-evaluating literal (a
	 * keyword, nil, true and false included) or a name that is no variable -- a function,
	 * a class -- whose value the call lowering reads itself (an {@code instance?} class,
	 * a function value). A variable, a call or a collection literal is evaluated into a
	 * temporary first.
	 */
	static boolean staysInPlace(ClojureLowering ctx, LispVal arg) {
		if (arg instanceof LispCons) {
			return false;
		}
		if (!(arg instanceof LispSymbol s)) {
			return true;
		}
		String name = s.name();
		if (name.startsWith(":") || name.equals("nil") || name.equals("true") || name.equals("false")) {
			return true;
		}
		if (name.startsWith("%")) {
			return false; // an anonymous-function argument
		}
		return isFunctionName(ctx, name) || !ctx.known(name);
	}

	/** Whether the name is a function definition, not a variable. */
	static boolean isFunctionName(ClojureLowering ctx, String name) {
		return ctx.known(name) ? ctx.isFunction(name)
				: !name.startsWith(":") && !name.equals("nil") && !name.equals("true") && !name.equals("false");
	}

	/**
	 * {@code (are [argv...] expr args...)}: one {@code is} per argument group, the
	 * template's names replaced by the group's datums ({@code clojure.template}'s
	 * postwalk), each reporting the {@code are} form's position. An argument count that
	 * does not divide by the names is the oracle's refusal.
	 */
	static LispVal areOf(ClojureLowering ctx, List<LispVal> items, String loc) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "are takes a vector of names, a template and arguments");
		List<LispVal> argv = ClojureLowerUtil.bindingItems(items.get(1), "are");
		List<String> names = new ArrayList<>();
		for (LispVal name : argv) {
			names.add(ClojureLowerUtil.plainName(name, "are"));
		}
		LispVal template = items.get(2);
		List<LispVal> args = items.subList(3, items.size());
		boolean ok = names.isEmpty() && args.isEmpty()
				|| !names.isEmpty() && !args.isEmpty() && args.size() % names.size() == 0;
		ClojureLowerUtil.isTrue(ok, "The number of args doesn't match are's argv.");
		List<LispVal> out = new ArrayList<>();
		out.add(ClojureLowerUtil.sym("progn"));
		for (int at = 0; at < args.size(); at += names.size()) {
			Map<String, LispVal> replace = new HashMap<>();
			for (int i = 0; i < names.size(); i++) {
				replace.put(names.get(i), args.get(at + i));
			}
			LispVal assertion = substitute(template, replace);
			out.add(isOf(ctx, List.of(new LispSymbol("is"), assertion), loc));
		}
		return out.size() == 1 ? ClojureLowering.NIL_CONST : ClojureLowerUtil.list(out);
	}

	/**
	 * The datum with every symbol the map names replaced by its value, at any depth; a
	 * replacement is never walked again, and the reader's markers stay put.
	 */
	static LispVal substitute(LispVal datum, Map<String, LispVal> replace) {
		if (datum instanceof LispSymbol s) {
			if (s == ClojureReader.VECTOR || s == ClojureReader.FN_ANON || s == ClojureReader.REGEX) {
				return s;
			}
			LispVal value = replace.get(s.name());
			return value != null ? value : s;
		}
		if (datum instanceof LispCons cons) {
			return new LispCons(substitute(cons.car(), replace), substitute(cons.cdr(), replace));
		}
		return datum;
	}

	/**
	 * {@code (testing context body...)}: the body as a thunk under the context. The body
	 * lowers behind the {@code try} barrier (the oracle's {@code binding}).
	 */
	static LispVal testingOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "testing takes a context string and a body");
		LispVal context = ctx.lower(items.get(1));
		LispVal body;
		ctx.tryDepth++;
		try {
			body = ctx.nonTailBody(items, 2);
		}
		finally {
			ctx.tryDepth--;
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TEST-TESTING"), context,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), LispNil.INSTANCE, body));
	}

	/**
	 * {@code (run-tests ns...)}: the named namespaces (symbols or strings), or the
	 * current one ({@code *ns*} at this form) without any.
	 */
	static LispVal runTestsOf(ClojureLowering ctx, List<LispVal> items) {
		if (items.size() == 1) {
			return runTests(ctx, currentNsList(ctx));
		}
		return runTests(ctx, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 1)));
	}

	/**
	 * The runner over the namespaces form, with the namespaces the program named so far:
	 * one that neither defined a test nor was named is the oracle's refusal.
	 */
	static LispVal runTests(ClojureLowering ctx, LispVal namespaces) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TEST-RUN-TESTS"), namespaces, knownList(ctx));
	}

	/** The namespaces the program named so far, as a list constant. */
	static LispVal knownList(ClojureLowering ctx) {
		List<LispVal> names = new ArrayList<>();
		ctx.namespacesSeen.add(ctx.currentNs);
		for (String ns : ctx.namespacesSeen) {
			names.add(LispString.literal(ns));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(names));
	}

	static LispVal currentNsList(ClojureLowering ctx) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), LispString.literal(ctx.currentNs));
	}

	/**
	 * {@code (run-all-tests re?)}: every namespace the program named or that defined a
	 * test, narrowed to the names the pattern matches.
	 */
	static LispVal runAllTestsOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() <= 2, "run-all-tests takes an optional pattern");
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TEST-RUN-ALL"),
				items.size() == 2 ? ctx.lower(items.get(1)) : ClojureLowering.NIL_CONST, knownList(ctx));
	}

	/**
	 * The {@code (file:line)} a report names: the form's file (its last path segment,
	 * like the oracle's stack frame) and line, or {@code NO_SOURCE_FILE} for a read
	 * without a file. A form the reader never saw (a macro's expansion) names the
	 * enclosing test definition's position.
	 */
	static String location(ClojureLowering ctx, @Nullable LispVal form) {
		SourceLocation at = form == null || ctx.reader == null ? null : ctx.reader.locate(form);
		if (at == null) {
			return ctx.testLocation != null ? ctx.testLocation : "(NO_SOURCE_FILE:0)";
		}
		String file = at.file();
		if (file == null) {
			file = "NO_SOURCE_FILE";
		}
		else {
			int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
			file = file.substring(slash + 1);
		}
		return "(" + file + ":" + at.line() + ")";
	}

}
