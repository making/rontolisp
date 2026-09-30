package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The EXPERIMENTAL Clojure front end's lowering: datums to the Common Lisp core forms the
 * pipeline already consumes. A deliberately small subset, enough to state what a Clojure
 * front end over this pipeline looks like; anything else is refused by name.
 *
 * <p>
 * Binding model: every identifier mangles behind {@link #PREFIX} ({@code c%}), so no user
 * name collides with a core form, a built-in or the reader's markers. {@code defn} is a
 * {@code defun} (the direct call and the tree shaker keep working); {@code def} a
 * top-level {@code setq}; {@code fn} and {@code #(...)} a {@code lambda} (the
 * {@code #(...)} arguments travel as one rest list); {@code let} a {@code let*}
 * (Clojure's let is sequential); {@code loop}/{@code recur} a {@code labels} self call
 * (the interpreter's tail calls make it constant-stack). Collections: a vector literal is
 * a {@code vector} call, the seq functions run over LISTS ({@code car}/{@code cdr}); a
 * map or set literal is refused. {@code false} folds into {@code nil} (both falsey), so
 * nothing can tell them apart.
 */
final class ClojureLowering {

	/**
	 * What every mangled identifier starts with. Contains a lowercase letter on purpose.
	 */
	static final String PREFIX = "c%";

	private static final LispVal NIL_CONST = LispNil.INSTANCE;

	private static final LispVal TRUE_CONST = LispTrue.INSTANCE;

	private static final LispSymbol AMPERSAND_REST = new LispSymbol("&REST");

	private static final LispSymbol ELSE = new LispSymbol(":else");

	private final List<LispVal> forms = new ArrayList<>();

	private final Map<String, Kind> globals = new HashMap<>();

	/** The scopes, innermost last; globals live in {@link #globals}. */
	private final List<Map<String, Kind>> scopes = new ArrayList<>();

	private int counter;

	/** The loop whose body is being lowered, for {@code recur}; null outside any. */
	private @Nullable String loop;

	/** The rest parameter of the {@code #(...)} being lowered, or null outside one. */
	private @Nullable String anonArgs;

	static List<LispVal> lower(List<LispVal> datums) {
		ClojureLowering lowering = new ClojureLowering();
		lowering.declare(datums);
		// pass two: lower
		for (LispVal datum : datums) {
			if (isSymbolNamed(datum, "ns")) {
				continue; // a namespace declaration defines nothing
			}
			lowering.forms.add(lowering.topLevel(datum));
		}
		return lowering.forms;
	}

	/**
	 * Lowers one buffer of a session, one entry per top-level datum. No pre-scan can see
	 * the forms still to be typed, so each buffer first declares its own top-level names
	 * into the session's globals -- a later buffer may call what an earlier one defined
	 * -- and then lowers. A buffer that defines and uses a name in the same buffer sees
	 * it too, like a file's own pre-scan.
	 * @param buffer the reader over the typed text
	 * @return the lowered datums, in order
	 */
	List<ClojureTopLevel> interact(ClojureReader buffer) {
		List<LispVal> datums = buffer.readAll();
		declare(datums);
		List<ClojureTopLevel> out = new ArrayList<>();
		for (LispVal datum : datums) {
			if (isSymbolNamed(datum, "ns")) {
				continue; // a namespace declaration defines nothing
			}
			out.add(new ClojureTopLevel(List.of(topLevel(datum)), true));
		}
		return out;
	}

	private void declare(List<LispVal> datums) {
		// pass one: every top-level name, so a definition may use one below it
		for (LispVal datum : datums) {
			List<LispVal> items = items(datum);
			if (items == null || items.size() < 2) {
				continue;
			}
			if (isSymbolNamed(items.get(0), "def")) {
				this.globals.put(plainName(items.get(1), "def"), Kind.VARIABLE);
			}
			else if (isSymbolNamed(items.get(0), "defn")) {
				this.globals.put(plainName(items.get(1), "defn"), Kind.FUNCTION);
			}
		}
	}

	ClojureLowering() {
		this.scopes.add(new HashMap<>()); // locals; globals live in globals
	}

	private LispVal topLevel(LispVal form) {
		List<LispVal> items = items(form);
		if (items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "defn")) {
			return defun(items);
		}
		return lower(form);
	}

	private LispVal lower(LispVal form) {
		if (!(form instanceof LispCons)) {
			return atom(form);
		}
		List<LispVal> items = items(form);
		if (items == null) {
			throw new LispReadException("a dotted list is not a Clojure form");
		}
		if (items.isEmpty()) {
			return NIL_CONST;
		}
		LispVal head = items.get(0);
		if (isSymbolNamed(head, "quote")) {
			isTrue(items.size() == 2, "quote takes one form");
			return quote(items.get(1));
		}
		if (isSymbolNamed(head, "def")) {
			return def(items);
		}
		if (isSymbolNamed(head, "defn")) {
			return defun(items);
		}
		if (head == ClojureReader.FN_ANON || isSymbolNamed(head, "fn")) {
			return fn(items);
		}
		if (isSymbolNamed(head, "let")) {
			return let(items);
		}
		if (isSymbolNamed(head, "loop")) {
			return loop(items);
		}
		if (head instanceof LispCons) {
			return cons(sym("funcall"), lowers(items, 0)); // ((fn ...) args)
		}
		if (head == ClojureReader.VECTOR) {
			return cons(sym("vector"), lowers(items, 1));
		}
		if (isSymbolNamed(head, "%hash-map")) {
			throw new LispReadException("a map literal is not supported yet");
		}
		if (isSymbolNamed(head, "if")) {
			isTrue(items.size() == 3 || items.size() == 4, "if takes a condition, a then and an optional else");
			LispVal elseForm = items.size() == 4 ? lower(items.get(3)) : NIL_CONST;
			return list(sym("if"), lower(items.get(1)), lower(items.get(2)), elseForm);
		}
		if (isSymbolNamed(head, "when")) {
			isTrue(items.size() >= 3, "when needs a condition and a body");
			return list(sym("when"), lower(items.get(1)), body(items, 2));
		}
		if (isSymbolNamed(head, "cond")) {
			return condOf(items);
		}
		if (isSymbolNamed(head, "do")) {
			return body(items, 1);
		}
		if (isSymbolNamed(head, "recur")) {
			if (this.loop == null) {
				throw new LispReadException("recur outside loop");
			}
			String target = this.loop;
			List<LispVal> out = new ArrayList<>();
			out.add(sym(target));
			for (int i = 1; i < items.size(); i++) {
				out.add(lower(items.get(i)));
			}
			return list(out);
		}
		return call(items);
	}

	private LispVal def(List<LispVal> items) {
		isTrue(items.size() == 2 || items.size() == 3, "def takes a name and an optional value");
		String name = plainName(items.get(1), "def");
		this.globals.put(name, Kind.VARIABLE);
		LispVal value = items.size() == 3 ? lower(items.get(2)) : NIL_CONST;
		return list(sym("setq"), sym(mangle(name)), value);
	}

	private LispVal defun(List<LispVal> items) {
		int at = 2;
		if (items.size() > at && items.get(at) instanceof LispString) {
			at++; // the docstring
		}
		isTrue(items.size() > at + 1, "defn needs a parameter vector and a body");
		String name = plainName(items.get(1), "defn");
		this.globals.put(name, Kind.FUNCTION);
		LispVal lambda = lambda(items.get(at), items.subList(at + 1, items.size()));
		return new LispCons(sym("defun"), new LispCons(sym(mangle(name)), ((LispCons) lambda).cdr()));
	}

	private LispVal fn(List<LispVal> items) {
		if (items.get(1) == ClojureReader.FN_ANON) {
			isTrue(items.size() >= 2, "the anon form #(...) needs a body");
			LispVal form = items.size() == 3 ? items.get(2)
					: new LispCons(items.get(2), list(items.subList(3, items.size())));
			String outer = this.anonArgs;
			this.anonArgs = mangle("anon-args");
			try {
				return list(sym("lambda"), list(AMPERSAND_REST, sym(this.anonArgs)), lower(form));
			}
			finally {
				this.anonArgs = outer;
			}
		}
		int at = 1;
		if (items.get(at) instanceof LispString) {
			at++; // fn name
		}
		isTrue(items.size() > at, "fn needs a parameter vector and a body");
		return lambda(items.get(at), items.subList(at + 1, items.size()));
	}

	private LispVal let(List<LispVal> items) {
		isTrue(items.size() >= 3, "let needs a binding vector and a body");
		List<LispVal> bindings = bindingItems(items.get(1), "let");
		isTrue(bindings.size() % 2 == 0, "a let binding vector pairs a name with a value");
		Map<String, Kind> scope = new HashMap<>();
		this.scopes.add(scope); // let* : each value sees the bindings before it
		LispVal form;
		try {
			List<LispVal> pairs = new ArrayList<>();
			for (int i = 0; i < bindings.size(); i += 2) {
				String name = plainName(bindings.get(i), "let");
				pairs.add(list(sym(mangle(name)), lower(bindings.get(i + 1))));
				scope.put(name, Kind.VARIABLE);
			}
			form = list(sym("let*"), list(pairs), body(items, 2));
		}
		finally {
			this.scopes.remove(this.scopes.size() - 1);
		}
		return form;
	}

	private LispVal loop(List<LispVal> items) {
		isTrue(items.size() >= 3, "loop needs a binding vector and a body");
		List<LispVal> bindings = bindingItems(items.get(1), "loop");
		isTrue(bindings.size() % 2 == 0, "a loop binding vector pairs a name with a value");
		String name = mangle("loop-") + this.counter++;
		Map<String, Kind> scope = new HashMap<>();
		List<LispVal> paramSyms = new ArrayList<>();
		List<LispVal> inits = new ArrayList<>();
		for (int i = 0; i < bindings.size(); i += 2) {
			String binding = plainName(bindings.get(i), "loop");
			paramSyms.add(sym(mangle(binding)));
			inits.add(lower(bindings.get(i + 1)));
			scope.put(binding, Kind.VARIABLE);
		}
		String outer = this.loop;
		this.loop = name;
		this.scopes.add(scope);
		try {
			LispVal lambdaBody = body(items, 2);
			// a labels binding is a named function: (name (params...) body...)
			LispVal binding = new LispCons(sym(name), new LispCons(list(paramSyms), cons(lambdaBody, List.of())));
			return list(sym("labels"), list(binding), new LispCons(sym(name), list(inits)));
		}
		finally {
			this.scopes.remove(this.scopes.size() - 1);
			this.loop = outer;
		}
	}

	private LispVal condOf(List<LispVal> items) {
		LispVal out = NIL_CONST;
		// an odd trailing arm is the default: it fires whatever came before
		int pairsEnd = items.size() % 2 == 0 ? items.size() - 1 : items.size();
		if (pairsEnd != items.size()) {
			out = lower(items.get(items.size() - 1));
		}
		for (int i = pairsEnd - 2; i >= 1; i -= 2) {
			out = list(sym("if"), condTest(items.get(i)), lower(items.get(i + 1)), out);
		}
		return out;
	}

	private LispVal condTest(LispVal test) {
		if (isSymbolNamed(test, ":else")) {
			return TRUE_CONST;
		}
		return lower(test);
	}

	// calls

	private LispVal call(List<LispVal> items) {
		if (!(items.get(0) instanceof LispSymbol op)) {
			throw new LispReadException("a call's head must be a name: " + items.get(0).print());
		}
		String name = op.name();
		if (name.startsWith(":")) {
			throw new LispReadException("a keyword cannot be called: " + name);
		}
		LispVal special = builtin(name, items);
		if (special != null) {
			return special;
		}
		isTrue(known(name), "unknown name: " + name);
		List<LispVal> out = new ArrayList<>();
		out.add(sym(mangle(name)));
		for (int i = 1; i < items.size(); i++) {
			out.add(lower(items.get(i)));
		}
		return list(out);
	}

	/** The core names, spelled as the Common Lisp operation they lower to. */
	private @Nullable LispVal builtin(String name, List<LispVal> items) {
		int n = items.size() - 1;
		switch (name) {
			case "+", "-", "*", "/", "max", "min", "rem", "mod", "quot", "abs", "cons", "list", "nth", "expt",
					"reverse", "apply":
				return plain(name, items);
			case "and":
				return plain("and", items);
			case "or":
				return plain("or", items);
			case "=":
				return plain("equal", items);
			case "not=":
				return list(sym("not"), plain("equal", items));
			case "<", ">", "<=", ">=":
				return plain(name, items);
			case "inc":
				isTrue(n == 1, "inc takes one argument");
				return list(sym("+"), lower(items.get(1)), new LispInteger(1));
			case "dec":
				isTrue(n == 1, "dec takes one argument");
				return list(sym("-"), lower(items.get(1)), new LispInteger(1));
			case "not":
				return plain("null", items);
			case "str":
				return strCall(items);
			case "println":
				return printCall(items, true);
			case "print":
				return printCall(items, false);
			case "newline":
				isTrue(n == 0, "newline takes no argument");
				return list(sym("princ"), LispString.literal("\n"));
			case "count":
				return plain("length", items);
			case "first":
				return plain("car", items);
			case "rest":
				return plain("cdr", items);
			case "empty?", "nil?":
				return plain("null", items);
			case "some?":
				isTrue(n == 1, "some? takes one argument");
				return list(sym("not"), list(sym("null"), lower(items.get(1))));
			case "even?":
				return plain("evenp", items);
			case "odd?":
				return plain("oddp", items);
			case "zero?":
				return plain("zerop", items);
			case "pos?":
				return plain("plusp", items);
			case "neg?":
				return plain("minusp", items);
			case "vector":
				return plain("vector", items);
			case "vector?":
				return plain("vectorp", items);
			case "map":
				isTrue(n == 2, "map takes one function and one collection");
				return list(sym("mapcar"), fnValue(items.get(1)), lower(items.get(2)));
			case "filter":
				isTrue(n == 2, "filter takes a predicate and a collection");
				return list(sym("remove-if-not"), fnValue(items.get(1)), lower(items.get(2)));
			case "reduce":
				isTrue(n == 2 || n == 3, "reduce takes a function, an optional value and a collection");
				if (n == 2) {
					return list(sym("reduce"), fnValue(items.get(1)), lower(items.get(2)));
				}
				return list(sym("reduce"), fnValue(items.get(1)), lower(items.get(3)), sym(":initial-value"),
						lower(items.get(2)));
			case "concat":
				return cons(sym("append"), lowers(items, 1));
			default:
				return null;
		}
	}

	private LispVal plain(String clName, List<LispVal> items) {
		return cons(sym(clName), lowers(items, 1));
	}

	private List<LispVal> lowers(List<LispVal> items, int from) {
		List<LispVal> out = new ArrayList<>();
		for (int i = from; i < items.size(); i++) {
			out.add(lower(items.get(i)));
		}
		return out;
	}

	/** A function in argument position: a known defn, a local binding, a lambda. */
	private LispVal fnValue(LispVal form) {
		if (form instanceof LispSymbol s && known(s.name())) {
			if (isFunction(s.name())) {
				return list(sym("function"), sym(mangle(s.name())));
			}
			return sym(mangle(s.name()));
		}
		return lower(form);
	}

	private LispVal strCall(List<LispVal> items) {
		if (items.size() == 1) {
			return LispString.literal("");
		}
		List<LispVal> parts = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			parts.add(list(sym("princ-to-string"), lower(items.get(i))));
		}
		return new LispCons(sym("concatenate"), new LispCons(list(sym("quote"), sym("string")), list(parts)));
	}

	private LispVal printCall(List<LispVal> items, boolean newline) {
		if (!newline) {
			return list(sym("princ"), items.size() == 1 ? LispString.literal("") : strCall(items));
		}
		// one concatenate whose last part is the newline: no separate newline call
		List<LispVal> parts = new ArrayList<>();
		pairs(items, parts);
		parts.add(LispString.literal("\n"));
		LispVal text = new LispCons(sym("concatenate"), new LispCons(list(sym("quote"), sym("string")), list(parts)));
		return list(sym("princ"), text);
	}

	private void pairs(List<LispVal> items, List<LispVal> parts) {
		for (int i = 1; i < items.size(); i++) {
			parts.add(list(sym("princ-to-string"), lower(items.get(i))));
		}
	}

	private LispVal bodyOf(List<LispVal> forms) {
		if (forms.isEmpty()) {
			return NIL_CONST;
		}
		if (forms.size() == 1) {
			return lower(forms.get(0));
		}
		List<LispVal> out = new ArrayList<>();
		out.add(sym("progn"));
		for (LispVal form : forms) {
			out.add(lower(form));
		}
		return list(out);
	}

	private LispVal body(List<LispVal> items, int from) {
		int n = items.size() - from;
		if (n <= 0) {
			return NIL_CONST;
		}
		if (n == 1) {
			return lower(items.get(from));
		}
		List<LispVal> out = new ArrayList<>();
		out.add(sym("progn"));
		for (int i = from; i < items.size(); i++) {
			out.add(lower(items.get(i)));
		}
		return list(out);
	}

	/**
	 * One lambda over a parameter vector and a not-yet-lowered body, the params scoped.
	 */
	private LispVal lambda(LispVal paramVector, List<LispVal> bodyForms) {
		List<LispVal> names = bindingItems(paramVector, "the parameter vector of");
		Map<String, Kind> scope = new HashMap<>();
		List<LispVal> paramSyms = new ArrayList<>();
		for (int i = 0; i < names.size(); i++) {
			if (isSymbolNamed(names.get(i), "&")) {
				isTrue(i + 1 < names.size(), "a & needs a rest name after it");
				paramSyms.add(AMPERSAND_REST);
				String rest = plainName(names.get(++i), "the parameter vector of");
				scope.put(rest, Kind.VARIABLE);
				paramSyms.add(sym(mangle(rest)));
				continue;
			}
			String param = plainName(names.get(i), "the parameter vector of");
			scope.put(param, Kind.VARIABLE);
			paramSyms.add(sym(mangle(param)));
		}
		return inScope(scope, () -> list(sym("lambda"), list(paramSyms), bodyOf(bodyForms)));
	}

	// atoms and quotes

	private LispVal atom(LispVal form) {
		if (!(form instanceof LispSymbol s)) {
			return form; // numbers, strings, characters are self-evaluating
		}
		String name = s.name();
		switch (name) {
			case "nil":
				return NIL_CONST;
			case "true":
				return TRUE_CONST;
			case "false":
				return NIL_CONST; // the spike folds false into nil: both are falsey
			default:
				break;
		}
		if (name.startsWith(":")) {
			return sym(name); // a keyword is data
		}
		if (name.equals("%") || name.length() > 1 && name.charAt(0) == '%'
				&& name.substring(1).chars().allMatch(Character::isDigit)) {
			if (this.anonArgs == null) {
				throw new LispReadException(name + " outside the anon form #(...)");
			}
			String args = this.anonArgs;
			int oneBased = name.equals("%") ? 1 : Integer.parseInt(name.substring(1));
			isTrue(oneBased <= 9, "the anon form #(...) takes at most 9 arguments");
			return list(sym("nth"), new LispInteger(oneBased - 1), sym(args));
		}
		if (!known(name)) {
			String cl = builtinValue(name);
			if (cl == null) {
				throw new LispReadException("unknown name: " + name);
			}
			return list(sym("function"), sym(cl));
		}
		if (isFunction(name)) {
			return list(sym("function"), sym(mangle(name)));
		}
		return sym(mangle(name));
	}

	/** The Common Lisp function a core name names as a value, or null. */
	private static @Nullable String builtinValue(String name) {
		return switch (name) {
			case "+", "-", "*", "/", "max", "min", "rem", "mod", "quot", "abs", "cons", "list", "nth", "expt",
					"reverse", "apply", "=", "<", ">", "<=", ">=", "not", "length", "car", "cdr", "equal", "evenp",
					"oddp", "zerop", "plusp", "minusp", "vector", "vectorp" ->
				name;
			case "empty?", "nil?" -> "null";
			case "even?" -> "evenp";
			case "odd?" -> "oddp";
			case "zero?" -> "zerop";
			case "pos?" -> "plusp";
			case "neg?" -> "minusp";
			case "some?" -> "not";
			case "count" -> "length";
			case "first" -> "car";
			case "rest" -> "cdr";
			default -> null;
		};
	}

	private LispVal quote(LispVal datum) {
		if (datum instanceof LispSymbol s) {
			String name = s.name();
			if (name.equals("nil")) {
				return list(sym("quote"), NIL_CONST);
			}
			if (name.equals("true") || name.equals("false")) {
				return list(sym("quote"), TRUE_CONST);
			}
			if (name.startsWith(":")) {
				return list(sym("quote"), sym(name));
			}
			return list(sym("quote"), sym(mangle(name)));
		}
		if (datum instanceof LispCons) {
			List<LispVal> items = items(datum, List.of());
			if (!items.isEmpty() && items.get(0) == ClojureReader.VECTOR) {
				List<LispVal> out = new ArrayList<>();
				out.add(sym("vector"));
				for (int i = 1; i < items.size(); i++) {
					out.add(quote(items.get(i)));
				}
				return list(out);
			}
			LispVal tail = NIL_CONST;
			for (int i = items.size() - 1; i >= 0; i--) {
				tail = new LispCons(quotedConstant(items.get(i)), tail);
			}
			return list(sym("quote"), tail);
		}
		return list(sym("quote"), datum);
	}

	/** One element of a quoted list: the constant, the per-element quote flattened. */
	private LispVal quotedConstant(LispVal datum) {
		LispVal quoted = quote(datum);
		if (quoted instanceof LispCons cell && cell.car() instanceof LispSymbol s && s.name().equals("QUOTE")
				&& cell.cdr() instanceof LispCons inner) {
			return inner.car();
		}
		return quoted;
	}

	// names

	/** The Common Lisp symbol name of a Clojure identifier: always behind the prefix. */
	static String mangle(String identifier) {
		StringBuilder out = new StringBuilder(PREFIX);
		for (int i = 0; i < identifier.length(); i++) {
			char c = identifier.charAt(i);
			if (c == '%') {
				out.append("%%");
			}
			else if (c == ':') {
				out.append("%c");
			}
			else {
				out.append(c);
			}
		}
		return out.toString();
	}

	private boolean known(String name) {
		for (Map<String, Kind> scope : this.scopes) {
			if (scope.containsKey(name)) {
				return true;
			}
		}
		return this.globals.containsKey(name);
	}

	private boolean isFunction(String name) {
		for (Map<String, Kind> scope : this.scopes) {
			Kind kind = scope.get(name);
			if (kind != null) {
				return kind == Kind.FUNCTION;
			}
		}
		Kind global = this.globals.get(name);
		return global == Kind.FUNCTION;
	}

	private <T> T inScope(Map<String, Kind> scope, java.util.function.Supplier<T> body) {
		this.scopes.add(scope);
		try {
			return body.get();
		}
		finally {
			this.scopes.remove(this.scopes.size() - 1);
		}
	}

	private enum Kind {

		VARIABLE, FUNCTION

	}

	// shape helpers

	private static String plainName(LispVal val, String what) {
		if (val instanceof LispSymbol s && !s.name().startsWith(":") && !s.name().equals("&")) {
			return s.name();
		}
		throw new LispReadException(what + " needs a plain name, not " + val.print());
	}

	/** The items of a {@code [...]} vector datum, without its marker. */
	private static List<LispVal> bindingItems(LispVal vector, String what) {
		List<LispVal> items = items(vector);
		if (items == null || items.isEmpty() || items.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException(what + " takes its bindings in a vector: " + vector.print());
		}
		return items.subList(1, items.size());
	}

	private static boolean isSymbolNamed(LispVal val, String name) {
		return val instanceof LispSymbol s && s.name().equals(name);
	}

	private static List<LispVal> items(LispVal val, List<LispVal> ifNone) {
		List<LispVal> items = items(val);
		return items == null ? ifNone : items;
	}

	private static @Nullable List<LispVal> items(LispVal val) {
		if (val instanceof LispNil) {
			return List.of();
		}
		if (!(val instanceof LispCons)) {
			return null;
		}
		List<LispVal> out = new ArrayList<>();
		LispVal run = val;
		while (run instanceof LispCons cons) {
			out.add(cons.car());
			run = cons.cdr();
		}
		if (!(run instanceof LispNil)) {
			return null;
		}
		return out;
	}

	private static LispVal cons(LispVal car, List<LispVal> rest) {
		return new LispCons(car, list(rest));
	}

	private static LispSymbol sym(String name) {
		return new LispSymbol(name.toUpperCase(java.util.Locale.ROOT));
	}

	private static LispVal list(List<LispVal> items) {
		LispVal tail = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			tail = new LispCons(items.get(i), tail);
		}
		return tail;
	}

	private static LispVal list(LispVal... items) {
		return list(List.of(items));
	}

	private static void isTrue(boolean ok, String message) {
		if (!ok) {
			throw new LispReadException(message);
		}
	}

}
