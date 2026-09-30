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
import am.ik.rontolisp.SourceLocation;
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
 * a {@code vector} call; a map literal is an {@code equal} hash table built by
 * {@code rontolisp:plist-hash-table} (lists and nested maps key structurally; vectors and
 * tables key by identity, like the runtime), never mutated in place -- every verb that
 * "changes" a map builds a fresh table, which is what keeps the persistent semantics
 * observable; a set literal is the same table with each member stored under itself,
 * wrapped as {@code (:C%SET table)} so a verb can tell a set from a map (the wrapper
 * prints as written, like vectors in CL notation); a seq is a STRICT list view -- lists
 * pass through untouched, vectors and strings coerce, maps contribute one two-vector per
 * entry and sets one member per element (both in the table's walk order, unspecified),
 * nil and the false object are empty, anything else signals -- so
 * {@code first}/{@code rest}/{@code next}/{@code seq}/{@code cons}/
 * {@code concat}/{@code map}/{@code filter}/{@code reduce}/{@code apply}/
 * {@code nth}/{@code take}/{@code drop} all run over every collection while the list path
 * stays a no-copy identity. There is no laziness, chunking or memoisation:
 * {@code lazy-seq}/{@code cycle}/{@code repeat}/{@code repeatedly}/{@code iterate} and an
 * end-less {@code range} are refused by name, and {@code range} with an end builds the
 * strict list. {@code nth} and {@code quot} as VALUES are correctly-ordered lambdas
 * wrapping the primitive (a bare {@code #'NTH} would have the operands backwards). A
 * keyword {@code :foo} is the list {@code (:C%KEYWORD "foo")} holding its spelling
 * verbatim (case-preserved, so {@code :a} and {@code :A} stay apart and compare unequal);
 * {@code println}/{@code print}/{@code str} spell it with its colon, and a keyword in
 * call position {@code (:k m)} (or with a default {@code (:k m dflt)}) is the same
 * table-aware read {@code get} lowers to. {@code false} is a DISTINCT non-{@code NIL}
 * object -- the value of {@code rontolisp::%clojure-false}, bound before anything else
 * runs, a symbol spelled {@code false} -- so {@code (= false nil)} is false and
 * {@code (nil?
 * false)} is false. It is falsey in every conditional: {@code if}/{@code when}/
 * {@code cond}/{@code and}/{@code or}/{@code not} lower their tests to an explicit
 * null-or-false check, and every boolean-answering builtin ({@code =}, the comparisons,
 * {@code not}, the {@code ?} predicates) answers {@code T} or the false object. Printing
 * spells the three values out: {@code println}/{@code print} show
 * {@code true}/{@code false}/{@code nil}, {@code str} shows {@code true}/{@code false}
 * and {@code ""} for {@code nil}; {@code println}/{@code print} join their parts with a
 * single space (like the oracle) while {@code str} concatenates bare, and
 * {@code pr}/{@code prn} are the readable arms (strings print quoted). A lowering error
 * names the innermost form's position ({@code file:line:column} when the file is known)
 * through the reader's offsets. Transients ({@code transient}, {@code persistent!},
 * {@code assoc!}/{@code dissoc!}/{@code conj!}/{@code disj!}) are refused by name: there
 * is no transient runtime behind the tables.
 */
final class ClojureLowering {

	/**
	 * What every mangled identifier starts with. Contains a lowercase letter on purpose.
	 */
	static final String PREFIX = "c%";

	/**
	 * The variable holding the Clojure false value, as spelled in the emitted program --
	 * the distinct-object treatment {@code scheme.lisp}'s {@code #f} uses: a dedicated
	 * object, distinct from {@code NIL}, bound before anything else runs, so no backend
	 * learns a Clojure name.
	 */
	static final String FALSE_VARIABLE = "RONTOLISP::%CLOJURE-FALSE";

	/** The false value's own spelling: a symbol, so it prints as {@code false}. */
	static final String FALSE_VALUE_NAME = "false";

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

	/** The false value, referenced (never rebuilt) wherever {@code false} lowers. */
	private final LispSymbol falseVariable = new LispSymbol(FALSE_VARIABLE);

	/** Whether the session already emitted the false binding (files always emit it). */
	private boolean falseBound;

	/** The reader the datums came out of, for error positions; null when unknown. */
	private @Nullable ClojureReader reader;

	static List<LispVal> lower(List<LispVal> datums) {
		return lower(datums, null);
	}

	static List<LispVal> lower(List<LispVal> datums, @Nullable ClojureReader reader) {
		ClojureLowering lowering = new ClojureLowering();
		lowering.reader = reader;
		lowering.declare(datums);
		lowering.forms.add(lowering.falseBinding());
		// pass two: lower
		for (LispVal datum : datums) {
			if (isNsForm(datum)) {
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
		this.reader = buffer;
		List<LispVal> datums = buffer.readAll();
		declare(datums);
		List<ClojureTopLevel> out = new ArrayList<>();
		for (LispVal datum : datums) {
			if (isNsForm(datum)) {
				continue; // a namespace declaration defines nothing
			}
			out.add(new ClojureTopLevel(List.of(topLevel(datum)), true));
		}
		if (!this.falseBound && !out.isEmpty()) {
			// The session's first datum carries the false binding ahead of itself,
			// like a file's first form; a buffer that failed to lower binds nothing.
			ClojureTopLevel first = out.get(0);
			List<LispVal> forms = new ArrayList<>();
			forms.add(falseBinding());
			forms.addAll(first.forms());
			out.set(0, new ClojureTopLevel(List.copyOf(forms), first.echoes()));
			this.falseBound = true;
		}
		return out;
	}

	private void declare(List<LispVal> datums) {
		// pass one: every top-level name, so a definition may use one below it
		for (LispVal datum : datums) {
			try {
				declareOne(datum);
			}
			catch (LispReadException ex) {
				throw positioned(ex, datum);
			}
		}
	}

	private void declareOne(LispVal datum) {
		List<LispVal> items = items(datum);
		if (items == null || items.size() < 2) {
			return;
		}
		if (isSymbolNamed(items.get(0), "def")) {
			this.globals.put(plainName(items.get(1), "def"), Kind.VARIABLE);
		}
		else if (isSymbolNamed(items.get(0), "defn")) {
			this.globals.put(plainName(items.get(1), "defn"), Kind.FUNCTION);
		}
	}

	ClojureLowering() {
		this.scopes.add(new HashMap<>()); // locals; globals live in globals
	}

	private LispVal topLevel(LispVal form) {
		try {
			List<LispVal> items = items(form);
			if (items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "defn")) {
				return defun(items);
			}
			return lower(form);
		}
		catch (LispReadException ex) {
			throw positioned(ex, form);
		}
	}

	/**
	 * One datum lowered, positioned: a lowering error names the innermost form's
	 * {@code file:line:column} (through the reader's offsets), so {@code unknown name}
	 * and arity errors point at the call. An error that already carries a position -- a
	 * reader error, or one an inner form attached -- passes through untouched.
	 */
	private LispVal lower(LispVal form) {
		try {
			return lowerInner(form);
		}
		catch (LispReadException ex) {
			throw positioned(ex, form);
		}
	}

	/**
	 * The datum's position, or the error untouched when neither the datum nor the reader
	 * knows one.
	 */
	private LispReadException positioned(LispReadException ex, LispVal datum) {
		if (ex.location() != null || this.reader == null) {
			return ex;
		}
		SourceLocation at = this.reader.locate(datum);
		if (at == null) {
			return ex;
		}
		String message = ex.getMessage();
		return new LispReadException(message != null ? message : ex.toString(), at);
	}

	private LispVal lowerInner(LispVal form) {
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
			return mapBuild(lowers(items, 1));
		}
		if (isSymbolNamed(head, "%hash-set")) {
			return setBuild(lowers(items, 1));
		}
		if (isSymbolNamed(head, "if")) {
			isTrue(items.size() == 3 || items.size() == 4, "if takes a condition, a then and an optional else");
			LispVal test = lower(items.get(1));
			LispVal then = lower(items.get(2));
			LispVal elseForm = items.size() == 4 ? lower(items.get(3)) : NIL_CONST;
			return ifFalsey(test, then, elseForm);
		}
		if (isSymbolNamed(head, "when")) {
			isTrue(items.size() >= 3, "when needs a condition and a body");
			LispVal test = lower(items.get(1));
			return ifFalsey(test, body(items, 2), NIL_CONST);
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
			out.add(new LispSymbol(target));
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
		return list(sym("setq"), idSym(name), value);
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
		return new LispCons(sym("defun"), new LispCons(idSym(name), ((LispCons) lambda).cdr()));
	}

	private LispVal fn(List<LispVal> items) {
		if (items.get(1) == ClojureReader.FN_ANON) {
			isTrue(items.size() >= 2, "the anon form #(...) needs a body");
			LispVal form = items.size() == 3 ? items.get(2)
					: new LispCons(items.get(2), list(items.subList(3, items.size())));
			String outer = this.anonArgs;
			this.anonArgs = mangle("anon-args");
			try {
				return list(sym("lambda"), list(AMPERSAND_REST, new LispSymbol(this.anonArgs)), lower(form));
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
				pairs.add(list(idSym(name), lower(bindings.get(i + 1))));
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
			paramSyms.add(idSym(binding));
			inits.add(lower(bindings.get(i + 1)));
			scope.put(binding, Kind.VARIABLE);
		}
		String outer = this.loop;
		this.loop = name;
		this.scopes.add(scope);
		try {
			LispVal lambdaBody = body(items, 2);
			// a labels binding is a named function: (name (params...) body...)
			LispVal binding = new LispCons(new LispSymbol(name),
					new LispCons(list(paramSyms), cons(lambdaBody, List.of())));
			return list(sym("labels"), list(binding), new LispCons(new LispSymbol(name), list(inits)));
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
			out = ifFalsey(condTest(items.get(i)), lower(items.get(i + 1)), out);
		}
		return out;
	}

	private LispVal condTest(LispVal test) {
		if (isSymbolNamed(test, ":else")) {
			return TRUE_CONST;
		}
		return lower(test);
	}

	/**
	 * Binds the false value before anything else runs: a quoted symbol, so every
	 * occurrence spells the same name and {@code eq} holds on every backend.
	 */
	private LispVal falseBinding() {
		return list(sym("SETQ"), this.falseVariable, list(sym("QUOTE"), new LispSymbol(FALSE_VALUE_NAME)));
	}

	/**
	 * A Clojure conditional over an already-lowered test: falsey when {@code NIL} or the
	 * false object, truthy otherwise. The test runs once, behind a temporary no user
	 * identifier can spell (user names always start with the prefix).
	 * @param test the lowered test
	 * @param whenTrue the lowered then form
	 * @param whenFalse the lowered else form
	 * @return the form
	 */
	private LispVal ifFalsey(LispVal test, LispVal whenTrue, LispVal whenFalse) {
		LispSymbol temp = freshTemp();
		return list(sym("LET"), list(list(temp, test)), list(sym("IF"), isFalsey(temp), whenFalse, whenTrue));
	}

	/** Whether the bound test value is falsey: {@code NIL} or the false object. */
	private LispVal isFalsey(LispSymbol temp) {
		return list(sym("OR"), list(sym("NULL"), temp), list(sym("EQ"), temp, this.falseVariable));
	}

	/**
	 * A temporary no user identifier can spell: user names always start with the prefix.
	 */
	private LispSymbol freshTemp() {
		return new LispSymbol("__clojure_" + (this.counter++));
	}

	/**
	 * A boolean-answering builtin's Clojure value: {@code T} or the false object, so
	 * printing spells it out. The raw form answers a Common Lisp boolean and appears
	 * once, so its values run once.
	 * @param raw the raw form
	 * @return the form
	 */
	private LispVal booleanAnswer(LispVal raw) {
		return list(sym("IF"), raw, TRUE_CONST, this.falseVariable);
	}

	/** {@code (and a b ...)} answers the first falsey value or the last value. */
	private LispVal andOf(List<LispVal> args) {
		if (args.isEmpty()) {
			return TRUE_CONST; // (and) is true
		}
		if (args.size() == 1) {
			return lower(args.get(0));
		}
		LispSymbol temp = freshTemp();
		return list(sym("LET"), list(list(temp, lower(args.get(0)))),
				list(sym("IF"), isFalsey(temp), temp, andOf(args.subList(1, args.size()))));
	}

	/** {@code (or a b ...)} answers the first truthy value or the last value. */
	private LispVal orOf(List<LispVal> args) {
		if (args.isEmpty()) {
			return NIL_CONST; // (or) is nil
		}
		if (args.size() == 1) {
			return lower(args.get(0));
		}
		LispSymbol temp = freshTemp();
		return list(sym("LET"), list(list(temp, lower(args.get(0)))),
				list(sym("IF"), isFalsey(temp), orOf(args.subList(1, args.size())), temp));
	}

	// calls

	private LispVal call(List<LispVal> items) {
		if (!(items.get(0) instanceof LispSymbol op)) {
			throw new LispReadException("a call's head must be a name: " + items.get(0).print());
		}
		String name = op.name();
		if (name.startsWith(":")) {
			return keywordCall(name, items);
		}
		LispVal special = builtin(name, items);
		if (special != null) {
			return special;
		}
		isTrue(known(name), "unknown name: " + name);
		List<LispVal> out = new ArrayList<>();
		out.add(idSym(name));
		for (int i = 1; i < items.size(); i++) {
			out.add(lower(items.get(i)));
		}
		return list(out);
	}

	/** The core names, spelled as the Common Lisp operation they lower to. */
	private @Nullable LispVal builtin(String name, List<LispVal> items) {
		int n = items.size() - 1;
		switch (name) {
			case "+", "-", "*", "/", "max", "min", "rem", "mod", "abs", "list", "expt", "reverse":
				return plain(name, items);
			case "quot":
				isTrue(n == 2, "quot takes two arguments");
				return list(sym("truncate"), lower(items.get(1)), lower(items.get(2)));
			case "nth":
				return nthOf(items);
			case "and":
				return andOf(items.subList(1, items.size()));
			case "or":
				return orOf(items.subList(1, items.size()));
			case "=":
				return booleanAnswer(equalityRaw(items));
			case "not=":
				return booleanAnswer(list(sym("not"), equalityRaw(items)));
			case "<", ">", "<=", ">=":
				return booleanAnswer(plain(name, items));
			case "inc":
				isTrue(n == 1, "inc takes one argument");
				return list(sym("+"), lower(items.get(1)), new LispInteger(1));
			case "dec":
				isTrue(n == 1, "dec takes one argument");
				return list(sym("-"), lower(items.get(1)), new LispInteger(1));
			case "not":
				isTrue(n == 1, "not takes one argument");
				LispSymbol notTemp = freshTemp();
				return list(sym("LET"), list(list(notTemp, lower(items.get(1)))), booleanAnswer(isFalsey(notTemp)));
			case "str":
				return strCall(items);
			case "println":
				return printCall(items, true);
			case "print":
				return printCall(items, false);
			case "prn":
				return prCall(items, true);
			case "pr":
				return prCall(items, false);
			case "newline":
				isTrue(n == 0, "newline takes no argument");
				return list(sym("princ"), LispString.literal("\n"));
			case "count":
				return countOf(items);
			case "seq":
				isTrue(items.size() == 2, "seq takes one collection");
				return seqForm(lower(items.get(1)));
			case "first":
				isTrue(items.size() == 2, "first takes one collection");
				return list(sym("car"), seqForm(lower(items.get(1))));
			case "rest":
			case "next":
				isTrue(items.size() == 2, name + " takes one collection");
				return list(sym("cdr"), seqForm(lower(items.get(1))));
			case "cons":
				isTrue(items.size() == 3, "cons takes an item and a collection");
				return list(sym("cons"), lower(items.get(1)), seqForm(lower(items.get(2))));
			case "empty?":
				return booleanAnswer(emptyOf(items));
			case "nil?":
				return booleanAnswer(plain("null", items));
			case "some?":
				isTrue(n == 1, "some? takes one argument");
				return booleanAnswer(list(sym("not"), list(sym("null"), lower(items.get(1)))));
			case "even?":
				return booleanAnswer(plain("evenp", items));
			case "odd?":
				return booleanAnswer(plain("oddp", items));
			case "zero?":
				return booleanAnswer(plain("zerop", items));
			case "pos?":
				return booleanAnswer(plain("plusp", items));
			case "neg?":
				return booleanAnswer(plain("minusp", items));
			case "false?":
				isTrue(n == 1, "false? takes one argument");
				return booleanAnswer(list(sym("EQ"), lower(items.get(1)), this.falseVariable));
			case "true?":
				isTrue(n == 1, "true? takes one argument");
				return booleanAnswer(list(sym("EQ"), lower(items.get(1)), TRUE_CONST));
			case "boolean?":
				isTrue(n == 1, "boolean? takes one argument");
				LispSymbol booleanTemp = freshTemp();
				return list(sym("LET"), list(list(booleanTemp, lower(items.get(1)))), booleanAnswer(list(sym("OR"),
						list(sym("EQ"), booleanTemp, TRUE_CONST), list(sym("EQ"), booleanTemp, this.falseVariable))));
			case "vector":
				return plain("vector", items);
			case "vector?":
				return booleanAnswer(plain("vectorp", items));
			case "map":
				isTrue(n == 2, "map takes one function and one collection");
				return list(sym("mapcar"), fnValue(items.get(1)), seqForm(lower(items.get(2))));
			case "filter":
				isTrue(n == 2, "filter takes a predicate and a collection");
				return list(sym("remove-if-not"), fnValue(items.get(1)), seqForm(lower(items.get(2))));
			case "reduce":
				isTrue(n == 2 || n == 3, "reduce takes a function, an optional value and a collection");
				if (n == 2) {
					return list(sym("reduce"), fnValue(items.get(1)), seqForm(lower(items.get(2))));
				}
				return list(sym("reduce"), fnValue(items.get(1)), seqForm(lower(items.get(3))), sym(":initial-value"),
						lower(items.get(2)));
			case "apply":
				return applyOf(items);
			case "concat":
				if (items.size() == 1) {
					return NIL_CONST;
				}
				List<LispVal> seqs = new ArrayList<>();
				for (int i = 1; i < items.size(); i++) {
					seqs.add(seqForm(lower(items.get(i))));
				}
				return cons(sym("append"), seqs);
			case "take":
				return takeOf(items);
			case "drop":
				return dropOf(items);
			case "range":
				return rangeOf(items);
			case "assoc":
				return assocOf(items);
			case "dissoc":
				return dissocOf(items);
			case "get":
				return getOf(items);
			case "contains?":
				return containsOf(items);
			case "keys":
				return keysOf(items);
			case "vals":
				return valsOf(items);
			case "merge":
				return mergeOf(items);
			case "conj":
				return conjOf(items);
			case "disj":
				return disjOf(items);
			case "set":
				return setOf(items);
			case "hash-map":
				return mapConstructorOf(items, "hash-map");
			case "array-map":
				return mapConstructorOf(items, "array-map");
			case "transient", "persistent!", "assoc!", "dissoc!", "conj!", "disj!":
				throw new LispReadException("transients are not supported yet: " + name);
			case "lazy-seq", "cycle", "repeat", "repeatedly", "iterate":
				throw new LispReadException("lazy sequences are not supported: " + name);
			default:
				return null;
		}
	}

	/** The tag heading a wrapped set: a set is {@code (LIST :C%SET table)}. */
	private static final LispSymbol SET_TAG = new LispSymbol(":C%SET");

	/**
	 * The tag heading a keyword value: a keyword is {@code (LIST :C%KEYWORD name)}
	 * holding its spelling verbatim (without the colon, case-preserved), so {@code :a}
	 * and {@code :A} stay apart. A cons keys an {@code equal} table structurally, so
	 * keywords key structurally and never collide with strings; {@code equal} compares
	 * two spellings case-sensitively through the same shape.
	 */
	private static final LispSymbol KEYWORD_TAG = new LispSymbol(":C%KEYWORD");

	/** A keyword's spelling as data: {@code (:C%KEYWORD "name")}, for quoted forms. */
	private static LispVal keywordDatum(String spelling) {
		return new LispCons(KEYWORD_TAG, new LispCons(new LispString(spelling), NIL_CONST));
	}

	/** A keyword's construction: {@code (LIST :C%KEYWORD "name")}. */
	private static LispVal keywordForm(String spelling) {
		return list(sym("list"), KEYWORD_TAG, LispString.literal(spelling));
	}

	/**
	 * Refuses what a keyword spelling cannot be: {@code ::}-auto-resolve has no namespace
	 * to resolve against (an {@code ns} form defines nothing), and a bare {@code :} names
	 * nothing. A namespaced {@code :a/b} is opaque data otherwise -- its spelling prints
	 * and compares whole, like the oracle's.
	 */
	private static void validateKeyword(String name) {
		if (name.startsWith("::")) {
			throw new LispReadException("auto-resolved keywords are not supported yet: " + name);
		}
		isTrue(name.length() > 1, "a keyword needs a name: " + name);
	}

	/**
	 * A keyword in call position: the map lookup {@code (:k m)} (or with a default
	 * {@code (:k m dflt)}), over the same table-aware read {@code get} lowers to, so sets
	 * answer their member and vectors and strings their indexed element too.
	 */
	private LispVal keywordCall(String name, List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 1 || n == 2, name + " takes a collection and an optional default");
		validateKeyword(name);
		List<LispVal> getForm = new ArrayList<>();
		getForm.add(new LispSymbol("get"));
		getForm.add(items.get(1));
		getForm.add(items.get(0));
		if (n == 2) {
			getForm.add(items.get(2));
		}
		return getOf(getForm);
	}

	/** {@code (RONTOLISP:PLIST-HASH-TABLE plist :TEST 'EQUAL)}: a fresh equal table. */
	private static LispVal tableFromPlist(LispVal plist) {
		return list(sym("rontolisp:plist-hash-table"), plist, sym(":test"), list(sym("quote"), sym("equal")));
	}

	/** {@code (RONTOLISP:HASH-TABLE-PLIST table)}. */
	private static LispVal tablePlist(LispVal table) {
		return list(sym("rontolisp:hash-table-plist"), table);
	}

	/** {@code (MAKE-HASH-TABLE :TEST 'EQUAL)}. */
	private static LispVal makeTable() {
		return list(sym("make-hash-table"), sym(":test"), list(sym("quote"), sym("equal")));
	}

	/**
	 * Whether the form holds a wrapped set: a cons headed by the tag over a table. The
	 * full shape check keeps user data from misfiring the test.
	 */
	private static LispVal isSetForm(LispVal form) {
		return list(sym("and"), list(sym("consp"), form), list(sym("eq"), list(sym("car"), form), SET_TAG),
				list(sym("hash-table-p"), list(sym("cadr"), form)));
	}

	/** The table inside a wrapped set: {@code (CADR form)}. */
	private static LispVal setInner(LispVal form) {
		return list(sym("cadr"), form);
	}

	/** {@code (LIST :C%SET table)}: the set wrapper. */
	private static LispVal wrapSet(LispVal table) {
		return list(sym("list"), SET_TAG, table);
	}

	/**
	 * A map construction over lowered key/value pairs: an equal table, so vector keys and
	 * nested maps compare structurally. The pairs evaluate once each, left to right.
	 */
	private static LispVal mapBuild(List<LispVal> pairs) {
		return tableFromPlist(cons(sym("list"), pairs));
	}

	/**
	 * A set construction over lowered elements: an equal table holding each member under
	 * itself, wrapped so verbs tell it from a map. Each element is bound once, so a
	 * side-effecting element runs once.
	 */
	private LispVal setBuild(List<LispVal> elements) {
		LispSymbol table = freshTemp();
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(list(table, makeTable()));
		List<LispVal> body = new ArrayList<>();
		for (LispVal element : elements) {
			LispSymbol one = freshTemp();
			bindings.add(list(one, element));
			body.add(list(sym("setf"), list(sym("gethash"), one, table), one));
		}
		body.add(wrapSet(table));
		return letForm(bindings, body);
	}

	/** {@code (LET bindings body...)}: a let over a computed body, spliced flat. */
	private static LispVal letForm(List<LispVal> bindings, List<LispVal> body) {
		List<LispVal> forms = new ArrayList<>();
		forms.add(list(bindings));
		forms.addAll(body);
		return cons(sym("let"), forms);
	}

	private LispVal assocOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n >= 3 && n % 2 == 1, "assoc takes a map and key/value pairs");
		LispSymbol map = freshTemp();
		LispVal grown = cons(sym("append"),
				List.of(list(sym("if"), map, tablePlist(map), NIL_CONST), cons(sym("list"), lowers(items, 2))));
		return list(sym("let"), list(List.of(list(map, lower(items.get(1))))), tableFromPlist(grown));
	}

	private LispVal dissocOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n >= 1, "dissoc takes a map and keys");
		LispSymbol map = freshTemp();
		LispSymbol copy = freshTemp();
		List<LispVal> body = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			body.add(list(sym("remhash"), lower(items.get(i)), copy));
		}
		body.add(copy);
		LispVal rebuilt = letForm(List.of(list(copy, tableFromPlist(tablePlist(map)))), body);
		return list(sym("let"), list(List.of(list(map, lower(items.get(1))))),
				list(sym("if"), map, rebuilt, NIL_CONST));
	}

	/**
	 * The bounds check of an indexed read: a vector for {@code get}, either for the rest.
	 */
	private static LispVal indexForm(LispVal coll, LispVal key, boolean vector) {
		return list(sym("and"), list(sym(vector ? "vectorp" : "stringp"), coll), list(sym("integerp"), key),
				list(sym(">="), key, new LispInteger(0)), list(sym("<"), key, list(sym("length"), coll)));
	}

	private LispVal getOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 2 || n == 3, "get takes a map, a key and an optional default");
		LispSymbol coll = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol dflt = freshTemp();
		List<LispVal> bindings = List.of(list(coll, lower(items.get(1))), list(key, lower(items.get(2))),
				list(dflt, n == 3 ? lower(items.get(3)) : NIL_CONST));
		return list(sym("let"), list(bindings), cons(sym("cond"), getBranches(coll, key, dflt)));
	}

	/**
	 * The branches of a table-aware read over an already-bound collection, key and
	 * default: a set answers its member, a map its value, a vector or a string its
	 * indexed element, anything else the default. The three arrive as side-effect-free
	 * forms (bound temporaries, a lambda parameter), so the branches may name them more
	 * than once.
	 */
	private List<LispVal> getBranches(LispVal coll, LispVal key, LispVal dflt) {
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(isSetForm(coll), list(sym("gethash"), key, setInner(coll), dflt)));
		branches.add(list(list(sym("hash-table-p"), coll), list(sym("gethash"), key, coll, dflt)));
		branches.add(list(indexForm(coll, key, true), list(sym("elt"), coll, key)));
		branches.add(list(indexForm(coll, key, false), list(sym("char"), coll, key)));
		branches.add(list(TRUE_CONST, dflt));
		return branches;
	}

	private LispVal containsOf(List<LispVal> items) {
		isTrue(items.size() == 3, "contains? takes a collection and a key");
		LispSymbol coll = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol miss = freshTemp();
		List<LispVal> bindings = List.of(list(coll, lower(items.get(1))), list(key, lower(items.get(2))),
				list(miss, list(sym("list"), NIL_CONST)));
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(isSetForm(coll), booleanAnswer(
				list(sym("not"), list(sym("eq"), list(sym("gethash"), key, setInner(coll), miss), miss)))));
		branches.add(list(list(sym("hash-table-p"), coll),
				booleanAnswer(list(sym("not"), list(sym("eq"), list(sym("gethash"), key, coll, miss), miss)))));
		branches.add(list(indexForm(coll, key, true), TRUE_CONST));
		branches.add(list(indexForm(coll, key, false), TRUE_CONST));
		branches.add(list(TRUE_CONST, this.falseVariable));
		return list(sym("let"), list(bindings), cons(sym("cond"), branches));
	}

	private LispVal keysOf(List<LispVal> items) {
		isTrue(items.size() == 2, "keys takes one map");
		return tableKeysOf(items, true);
	}

	private LispVal valsOf(List<LispVal> items) {
		isTrue(items.size() == 2, "vals takes one map");
		return tableKeysOf(items, false);
	}

	/**
	 * {@code keys} (or {@code vals}): the table's keys (or values) accumulated into a
	 * list. The order is the table's walk order, unspecified like the oracle's.
	 */
	private LispVal tableKeysOf(List<LispVal> items, boolean keys) {
		LispSymbol map = freshTemp();
		LispSymbol acc = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispVal take = keys ? key : val;
		LispVal drop = keys ? val : key;
		LispVal collect = list(sym("maphash"), list(sym("lambda"), list(List.of(key, val)),
				list(sym("declare"), list(sym("ignore"), drop)), list(sym("setq"), acc, list(sym("cons"), take, acc))),
				map);
		return list(sym("let"), list(List.of(list(map, lower(items.get(1))))),
				list(sym("if"), map, list(sym("let"), list(List.of(list(acc, NIL_CONST))), collect, acc), NIL_CONST));
	}

	private LispVal mergeOf(List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			return NIL_CONST;
		}
		if (n == 1) {
			return lower(items.get(1));
		}
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> present = new ArrayList<>();
		List<LispVal> plists = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			LispSymbol one = freshTemp();
			bindings.add(list(one, lower(items.get(i))));
			present.add(one);
			plists.add(list(sym("if"), one, tablePlist(one), NIL_CONST));
		}
		return list(sym("let"), list(bindings),
				list(sym("if"), cons(sym("or"), present), tableFromPlist(cons(sym("append"), plists)), NIL_CONST));
	}

	private LispVal conjOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n >= 1, "conj takes a collection and items");
		LispVal acc = lower(items.get(1));
		for (int i = 2; i < items.size(); i++) {
			acc = conjTwo(acc, items.get(i));
		}
		return acc;
	}

	/**
	 * One conjoined item: a set gains a member, a map gains the item's entries, a vector
	 * gains at the end, a list or nil at the front. Anything else signals, like the
	 * oracle's.
	 */
	private LispVal conjTwo(LispVal coll, LispVal itemDatum) {
		LispSymbol collSym = freshTemp();
		LispSymbol item = freshTemp();
		List<LispVal> bindings = List.of(list(collSym, coll), list(item, lower(itemDatum)));
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(isSetForm(collSym), setAdd(collSym, item)));
		branches.add(list(list(sym("hash-table-p"), collSym),
				tableFromPlist(cons(sym("append"), List.of(tablePlist(collSym), entryPlist(item))))));
		branches.add(list(list(sym("vectorp"), collSym),
				list(sym("coerce"),
						list(sym("append"), list(sym("coerce"), collSym, quoted("list")), list(sym("list"), item)),
						quoted("vector"))));
		branches.add(list(list(sym("or"), list(sym("null"), collSym), list(sym("consp"), collSym)),
				list(sym("cons"), item, collSym)));
		branches.add(list(TRUE_CONST, list(sym("error"), LispString.literal("conj needs a collection and an item"))));
		return list(sym("let"), list(bindings), cons(sym("cond"), branches));
	}

	/** {@code (QUOTE name)} over a lower-case name, for a coerce designator. */
	private static LispVal quoted(String name) {
		return list(sym("quote"), sym(name));
	}

	/**
	 * The entries one conjoined item adds to a map, as a plist: a map's own pairs, a
	 * two-vector's or two-list's pair, or a set's members each as an entry.
	 */
	private LispVal entryPlist(LispVal item) {
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(list(sym("hash-table-p"), item), tablePlist(item)));
		branches.add(list(
				list(sym("and"), list(sym("vectorp"), item),
						list(sym("eql"), list(sym("length"), item), new LispInteger(2))),
				list(sym("list"), list(sym("elt"), item, new LispInteger(0)),
						list(sym("elt"), item, new LispInteger(1)))));
		branches.add(list(
				list(sym("and"), list(sym("consp"), item), list(sym("not"), isSetForm(item)),
						list(sym("consp"), list(sym("cdr"), item)), list(sym("null"), list(sym("cddr"), item))),
				list(sym("list"), list(sym("car"), item), list(sym("cadr"), item))));
		branches.add(list(isSetForm(item), membersPlist(item)));
		branches.add(list(TRUE_CONST, list(sym("error"),
				LispString.literal("conj needs a map entry: a map, a [k v] vector or a (k v) list"))));
		return cons(sym("cond"), branches);
	}

	/**
	 * The entries of a set conjoined onto a map, as a plist: each member is itself an
	 * entry, one level deep. A set nested in the set is refused: entries nest one level.
	 */
	private LispVal membersPlist(LispVal item) {
		LispSymbol grown = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispVal collect = list(sym("maphash"),
				list(sym("lambda"), list(List.of(key, val)), list(sym("declare"), list(sym("ignore"), val)),
						list(sym("setq"), grown, list(sym("append"), grown, memberEntryPlist(key)))),
				setInner(item));
		return list(sym("let"), list(List.of(list(grown, NIL_CONST))), collect, grown);
	}

	/**
	 * One set member's entries as a plist: a map's pairs, a two-vector's or two-list's
	 * pair. Unlike {@link #entryPlist}, this never recurses, so the Java construction
	 * terminates; a set nested in the conjoined set is refused at run time instead.
	 */
	private static LispVal memberEntryPlist(LispVal key) {
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(list(sym("hash-table-p"), key), tablePlist(key)));
		branches.add(list(
				list(sym("and"), list(sym("vectorp"), key),
						list(sym("eql"), list(sym("length"), key), new LispInteger(2))),
				list(sym("list"), list(sym("elt"), key, new LispInteger(0)),
						list(sym("elt"), key, new LispInteger(1)))));
		branches.add(list(
				list(sym("and"), list(sym("consp"), key), list(sym("not"), isSetForm(key)),
						list(sym("consp"), list(sym("cdr"), key)), list(sym("null"), list(sym("cddr"), key))),
				list(sym("list"), list(sym("car"), key), list(sym("cadr"), key))));
		branches.add(list(TRUE_CONST, list(sym("error"),
				LispString.literal("conj needs a map entry: a map, a [k v] vector or a (k v) list"))));
		return cons(sym("cond"), branches);
	}

	/** One member added to a set: a fresh table over the old members plus the member. */
	private LispVal setAdd(LispVal coll, LispVal item) {
		LispSymbol table = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispVal copy = list(sym("maphash"),
				list(sym("lambda"), list(List.of(key, val)), list(sym("declare"), list(sym("ignore"), val)),
						list(sym("setf"), list(sym("gethash"), key, table), key)),
				setInner(coll));
		return list(sym("let"), list(List.of(list(table, makeTable()))), copy,
				list(sym("setf"), list(sym("gethash"), item, table), item), wrapSet(table));
	}

	private LispVal disjOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n >= 1, "disj takes a set and members");
		LispSymbol set = freshTemp();
		LispSymbol table = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispVal copy = list(sym("maphash"),
				list(sym("lambda"), list(List.of(key, val)), list(sym("declare"), list(sym("ignore"), val)),
						list(sym("setf"), list(sym("gethash"), key, table), key)),
				setInner(set));
		List<LispVal> body = new ArrayList<>();
		body.add(list(table, makeTable()));
		LispVal kept = list(sym("let"), list(body), copy, remhashes(items, table), wrapSet(table));
		LispVal needSet = list(sym("error"), LispString.literal("disj needs a set"));
		return list(sym("let"), list(List.of(list(set, lower(items.get(1))))),
				list(sym("if"), set, list(sym("if"), isSetForm(set), kept, needSet), NIL_CONST));
	}

	/**
	 * The {@code remhash} of each of {@code items}' keys from {@code table}, in order.
	 */
	private LispVal remhashes(List<LispVal> items, LispSymbol table) {
		if (items.size() == 2) {
			return table;
		}
		List<LispVal> drops = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			drops.add(list(sym("remhash"), lower(items.get(i)), table));
		}
		drops.add(table);
		return cons(sym("progn"), drops);
	}

	private LispVal setOf(List<LispVal> items) {
		isTrue(items.size() == 2, "set takes one collection");
		LispSymbol coll = freshTemp();
		LispSymbol table = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispSymbol one = freshTemp();
		LispSymbol entry = freshTemp();
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(list(sym("vectorp"), coll),
				list(sym("dolist"), list(List.of(one, list(sym("coerce"), coll, quoted("list")))),
						list(sym("setf"), list(sym("gethash"), one, table), one))));
		branches.add(list(list(sym("hash-table-p"), coll),
				list(sym("maphash"),
						list(sym("lambda"), list(List.of(key, val)),
								list(sym("let"), list(List.of(list(entry, list(sym("vector"), key, val)))),
										list(sym("setf"), list(sym("gethash"), entry, table), entry))),
						coll)));
		branches.add(list(isSetForm(coll),
				list(sym("maphash"),
						list(sym("lambda"), list(List.of(key, val)), list(sym("declare"), list(sym("ignore"), val)),
								list(sym("setf"), list(sym("gethash"), key, table), key)),
						setInner(coll))));
		branches.add(list(TRUE_CONST, list(sym("dolist"), list(List.of(one, coll)),
				list(sym("setf"), list(sym("gethash"), one, table), one))));
		return list(sym("let"), list(List.of(list(coll, lower(items.get(1))), list(table, makeTable()))),
				cons(sym("cond"), branches), wrapSet(table));
	}

	private LispVal mapConstructorOf(List<LispVal> items, String what) {
		isTrue((items.size() - 1) % 2 == 0, what + " takes key/value pairs");
		return mapBuild(lowers(items, 1));
	}

	private LispVal countOf(List<LispVal> items) {
		isTrue(items.size() == 2, "count takes one collection");
		return countForm(lower(items.get(1)));
	}

	/** The count of an already-lowered collection: tables by entries, else length. */
	private LispVal countForm(LispVal lowered) {
		LispSymbol coll = freshTemp();
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(isSetForm(coll), list(sym("hash-table-count"), setInner(coll))));
		branches.add(list(list(sym("hash-table-p"), coll), list(sym("hash-table-count"), coll)));
		// the false object counts as empty, like the oracle; anything else takes length
		branches.add(list(list(sym("eq"), coll, this.falseVariable), new LispInteger(0)));
		branches.add(list(TRUE_CONST, list(sym("length"), coll)));
		return list(sym("let"), list(List.of(list(coll, lowered))), cons(sym("cond"), branches));
	}

	private LispVal emptyOf(List<LispVal> items) {
		isTrue(items.size() == 2, "empty? takes one collection");
		return emptyForm(lower(items.get(1)));
	}

	/** Whether an already-lowered collection is empty, answering raw. */
	private LispVal emptyForm(LispVal lowered) {
		LispSymbol coll = freshTemp();
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(isSetForm(coll), list(sym("zerop"), list(sym("hash-table-count"), setInner(coll)))));
		branches.add(list(list(sym("hash-table-p"), coll), list(sym("zerop"), list(sym("hash-table-count"), coll))));
		branches.add(list(list(sym("vectorp"), coll), list(sym("zerop"), list(sym("length"), coll))));
		branches.add(list(list(sym("stringp"), coll), list(sym("zerop"), list(sym("length"), coll))));
		branches.add(list(TRUE_CONST, list(sym("null"), coll)));
		return list(sym("let"), list(List.of(list(coll, lowered))), cons(sym("cond"), branches));
	}

	/**
	 * The seq view of an already-lowered collection: a strict LIST, the one sequence
	 * every backend already shares, so no backend learns a representation. Lists pass
	 * through untouched (the list fast path -- no copy); vectors and strings coerce; maps
	 * contribute one two-vector per entry and sets one member per element, both in the
	 * table's walk order, unspecified like the oracle's; nil and the false object are
	 * empty; anything else signals, like the oracle's. The collection runs once, behind a
	 * temporary no user identifier can spell.
	 * @param lowered the lowered collection
	 * @return the form answering the list view
	 */
	private LispVal seqForm(LispVal lowered) {
		LispSymbol coll = freshTemp();
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(list(sym("null"), coll), NIL_CONST));
		branches.add(list(isSetForm(coll), memberList(coll)));
		branches.add(list(list(sym("consp"), coll), coll));
		branches.add(list(list(sym("vectorp"), coll), list(sym("coerce"), coll, quoted("list"))));
		branches.add(list(list(sym("stringp"), coll), list(sym("coerce"), coll, quoted("list"))));
		branches.add(list(list(sym("hash-table-p"), coll), entryList(coll)));
		branches.add(list(list(sym("eq"), coll, this.falseVariable), NIL_CONST));
		branches.add(list(TRUE_CONST, list(sym("error"), LispString.literal("seq needs a collection"))));
		return list(sym("let"), list(List.of(list(coll, lowered))), cons(sym("cond"), branches));
	}

	/** A set's members accumulated into a list, in the table's walk order. */
	private LispVal memberList(LispVal coll) {
		LispSymbol acc = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		return list(sym("let"), list(List.of(list(acc, NIL_CONST))),
				list(sym("maphash"),
						list(sym("lambda"), list(List.of(key, val)), list(sym("declare"), list(sym("ignore"), val)),
								list(sym("setq"), acc, list(sym("cons"), key, acc))),
						setInner(coll)),
				acc);
	}

	/** A map's entries accumulated into a list of two-vectors, in walk order. */
	private LispVal entryList(LispVal coll) {
		LispSymbol acc = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		return list(sym("let"), list(List.of(list(acc, NIL_CONST))),
				list(sym("maphash"),
						list(sym("lambda"), list(List.of(key, val)),
								list(sym("setq"), acc, list(sym("cons"), list(sym("vector"), key, val), acc))),
						coll),
				acc);
	}

	/**
	 * {@code nth} over any collection: the seq view indexed, past the end the default
	 * (nil without one) instead of the oracle's throw. The collection and the index run
	 * once each.
	 */
	private LispVal nthForm(LispVal coll, LispVal index, LispVal dflt) {
		LispSymbol seq = freshTemp();
		LispSymbol at = freshTemp();
		return list(sym("let"), list(List.of(list(seq, seqForm(coll)), list(at, index))),
				list(sym("if"), list(sym("<"), at, list(sym("length"), seq)), list(sym("nth"), at, seq), dflt));
	}

	private LispVal nthOf(List<LispVal> items) {
		int n = items.size() - 1;
		isTrue(n == 2 || n == 3, "nth takes a collection, an index and an optional default");
		return nthForm(lower(items.get(1)), lower(items.get(2)), n == 3 ? lower(items.get(3)) : NIL_CONST);
	}

	/**
	 * {@code nth} as a value: a lambda with the Clojure argument order, since a bare
	 * {@code #'NTH} would take the index first.
	 */
	private LispVal nthValue() {
		LispSymbol coll = new LispSymbol(mangle("nth-coll"));
		LispSymbol index = new LispSymbol(mangle("nth-index"));
		return list(sym("lambda"), list(List.of(coll, index)), nthForm(coll, index, NIL_CONST));
	}

	/**
	 * {@code quot} as a value: a lambda over the primitive, like {@link #nthValue}.
	 */
	private LispVal quotValue() {
		LispSymbol first = new LispSymbol(mangle("quot-a"));
		LispSymbol second = new LispSymbol(mangle("quot-b"));
		return list(sym("lambda"), list(List.of(first, second)), list(sym("truncate"), first, second));
	}

	/**
	 * A builtin as a function value: a lambda with the Clojure argument order and the
	 * Clojure coercions, so {@code (map inc ...)} runs what a call would run. Only the
	 * builtins whose call lowering is more than a direct Common Lisp call need an entry
	 * here -- a direct call's {@code #'name} in {@link #builtinValue} already answers the
	 * same function. Null when the name has no value form.
	 * @param name the Clojure name
	 * @return the lambda, or null
	 */
	private @Nullable LispVal valueOf(String name) {
		return switch (name) {
			case "inc", "dec" -> incValue(name);
			case "str" -> strValue();
			case "seq" -> seqValue();
			case "first" -> firstValue();
			case "rest", "next" -> restValue();
			case "cons" -> consValue();
			case "count" -> countValue();
			case "empty?" -> emptyValue();
			case "map" -> mapValue();
			case "filter" -> filterValue();
			case "reduce" -> reduceValue();
			case "concat" -> concatValue();
			case "take" -> takeValue();
			case "drop" -> dropValue();
			case "range" -> rangeValue();
			default -> null;
		};
	}

	/** {@code inc}/{@code dec} as a value: a one-argument lambda over the primitive. */
	private LispVal incValue(String name) {
		LispSymbol x = new LispSymbol(mangle("inc-x"));
		return list(sym("lambda"), list(x), list(sym(name.equals("inc") ? "+" : "-"), x, new LispInteger(1)));
	}

	/**
	 * {@code str} as a value: over any number of arguments, each converted like a
	 * {@code str} part ({@code ""} for {@code nil}) and concatenated. The parts map over
	 * the rest list and spread back through {@code apply}.
	 */
	private LispVal strValue() {
		LispSymbol args = new LispSymbol(mangle("str-args"));
		LispSymbol one = new LispSymbol(mangle("str-one"));
		LispVal oneFn = list(sym("lambda"), list(one), printPartBody(one, LispString.literal(""), false));
		return list(sym("lambda"), list(AMPERSAND_REST, args),
				list(sym("apply"), list(sym("function"), sym("concatenate")), list(sym("quote"), sym("string")),
						list(sym("mapcar"), oneFn, args)));
	}

	/** {@code seq} as a value: the seq view as a one-argument lambda. */
	private LispVal seqValue() {
		LispSymbol coll = new LispSymbol(mangle("seq-coll"));
		return list(sym("lambda"), list(coll), seqForm(coll));
	}

	/** {@code first} as a value: the head of the seq view. */
	private LispVal firstValue() {
		LispSymbol coll = new LispSymbol(mangle("first-coll"));
		return list(sym("lambda"), list(coll), list(sym("car"), seqForm(coll)));
	}

	/** {@code rest}/{@code next} as a value: the tail of the seq view. */
	private LispVal restValue() {
		LispSymbol coll = new LispSymbol(mangle("rest-coll"));
		return list(sym("lambda"), list(coll), list(sym("cdr"), seqForm(coll)));
	}

	/** {@code cons} as a value: the item over the seq view. */
	private LispVal consValue() {
		LispSymbol item = new LispSymbol(mangle("cons-item"));
		LispSymbol coll = new LispSymbol(mangle("cons-coll"));
		return list(sym("lambda"), list(List.of(item, coll)), list(sym("cons"), item, seqForm(coll)));
	}

	/** {@code count} as a value: the table-aware count. */
	private LispVal countValue() {
		LispSymbol coll = new LispSymbol(mangle("count-coll"));
		return list(sym("lambda"), list(coll), countForm(coll));
	}

	/** {@code empty?} as a value: the table-aware emptiness test, answering raw. */
	private LispVal emptyValue() {
		LispSymbol coll = new LispSymbol(mangle("empty-coll"));
		return list(sym("lambda"), list(coll), emptyForm(coll));
	}

	/** {@code map} as a value: {@code mapcar} over the seq view. */
	private LispVal mapValue() {
		LispSymbol fn = new LispSymbol(mangle("map-fn"));
		LispSymbol coll = new LispSymbol(mangle("map-coll"));
		return list(sym("lambda"), list(List.of(fn, coll)), list(sym("mapcar"), fn, seqForm(coll)));
	}

	/** {@code filter} as a value: {@code remove-if-not} over the seq view. */
	private LispVal filterValue() {
		LispSymbol pred = new LispSymbol(mangle("filter-pred"));
		LispSymbol coll = new LispSymbol(mangle("filter-coll"));
		return list(sym("lambda"), list(List.of(pred, coll)), list(sym("remove-if-not"), pred, seqForm(coll)));
	}

	/**
	 * {@code reduce} as a value: over a function and a collection, or a function, a value
	 * and a collection -- the two call shapes, dispatched on the rest count. Any other
	 * count signals, like a call's arity refusal.
	 */
	private LispVal reduceValue() {
		LispSymbol fn = new LispSymbol(mangle("reduce-fn"));
		LispSymbol args = new LispSymbol(mangle("reduce-args"));
		LispVal two = list(sym("reduce"), fn, seqForm(list(sym("car"), args)));
		LispVal three = list(sym("reduce"), fn, seqForm(list(sym("car"), list(sym("cdr"), args))),
				sym(":initial-value"), list(sym("car"), args));
		LispVal arity = list(sym("error"),
				LispString.literal("reduce takes a function, an optional value and a collection"));
		LispVal body = list(sym("cond"), list(list(sym("null"), args), arity),
				list(list(sym("null"), list(sym("cdr"), args)), two),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), args))), three), list(TRUE_CONST, arity));
		return list(sym("lambda"), list(List.of(fn, AMPERSAND_REST, args)), body);
	}

	/** {@code concat} as a value: every argument's seq view appended. */
	private LispVal concatValue() {
		LispSymbol colls = new LispSymbol(mangle("concat-colls"));
		return list(sym("lambda"), list(AMPERSAND_REST, colls),
				list(sym("apply"), list(sym("function"), sym("append")), list(sym("mapcar"), seqValue(), colls)));
	}

	/** {@code take} as a value: the strict prefix over the seq view. */
	private LispVal takeValue() {
		LispSymbol count = new LispSymbol(mangle("take-count"));
		LispSymbol coll = new LispSymbol(mangle("take-coll"));
		return list(sym("lambda"), list(List.of(count, coll)), takeForm(count, seqForm(coll)));
	}

	/** {@code drop} as a value: the seq view past the strict prefix. */
	private LispVal dropValue() {
		LispSymbol count = new LispSymbol(mangle("drop-count"));
		LispSymbol coll = new LispSymbol(mangle("drop-coll"));
		return list(sym("lambda"), list(List.of(count, coll)), dropForm(count, seqForm(coll)));
	}

	/**
	 * {@code range} as a value: the one-, two- and three-argument call shapes over the
	 * rest list. Any other count signals, like a call's arity refusal.
	 */
	private LispVal rangeValue() {
		LispSymbol args = new LispSymbol(mangle("range-args"));
		LispVal second = list(sym("car"), list(sym("cdr"), args));
		LispVal third = list(sym("car"), list(sym("cdr"), list(sym("cdr"), args)));
		LispVal one = rangeForm(new LispInteger(0), list(sym("car"), args), new LispInteger(1));
		LispVal two = rangeForm(list(sym("car"), args), second, new LispInteger(1));
		LispVal three = rangeForm(list(sym("car"), args), second, third);
		LispVal arity = list(sym("error"),
				LispString.literal("range takes an end, or a start, an end and an optional step"));
		LispVal body = list(sym("cond"), list(list(sym("null"), args), arity),
				list(list(sym("null"), list(sym("cdr"), args)), one),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), args))), two),
				list(list(sym("null"), list(sym("cdr"), list(sym("cdr"), list(sym("cdr"), args)))), three),
				list(TRUE_CONST, arity));
		return list(sym("lambda"), list(AMPERSAND_REST, args), body);
	}

	/**
	 * {@code apply} over any leading arguments: each but the last passes through, the
	 * last answers its seq view -- CL {@code apply}'s own shape, so
	 * {@code (apply f x args)} spreads like the oracle's.
	 */
	private LispVal applyOf(List<LispVal> items) {
		isTrue(items.size() >= 3, "apply takes a function and an argument list");
		List<LispVal> out = new ArrayList<>();
		out.add(sym("apply"));
		out.add(fnValue(items.get(1)));
		for (int i = 2; i < items.size() - 1; i++) {
			out.add(lower(items.get(i)));
		}
		out.add(seqForm(lower(items.get(items.size() - 1))));
		return list(out);
	}

	/** {@code take}: the first {@code n} of the seq view, strictly. */
	private LispVal takeOf(List<LispVal> items) {
		isTrue(items.size() == 3, "take takes a count and a collection");
		return takeForm(lower(items.get(1)), seqForm(lower(items.get(2))));
	}

	/**
	 * The first {@code count} of an already-lowered seq view, strictly: a labels self
	 * call accumulating in reverse. Both arrive bound (a temporary, a lambda parameter),
	 * so the walk names them more than once.
	 */
	private LispVal takeForm(LispVal count, LispVal seqView) {
		String name = mangle("take-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol left = freshTemp();
		LispSymbol seq = freshTemp();
		LispSymbol walked = freshTemp();
		LispSymbol cell = freshTemp();
		LispSymbol grown = freshTemp();
		LispVal done = list(sym("reverse"), grown);
		LispVal more = list(self, list(sym("-"), walked, new LispInteger(1)), list(sym("cdr"), cell),
				list(sym("cons"), list(sym("car"), cell), grown));
		LispVal step = list(sym("if"),
				list(sym("or"), list(sym("<="), walked, new LispInteger(0)), list(sym("null"), cell)), done, more);
		LispVal binding = new LispCons(self, new LispCons(list(List.of(walked, cell, grown)), cons(step, List.of())));
		return list(sym("let"), list(List.of(list(left, count), list(seq, seqView))),
				list(sym("labels"), list(List.of(binding)), list(self, left, seq, NIL_CONST)));
	}

	/** {@code drop}: the seq view past the first {@code n}, strictly. */
	private LispVal dropOf(List<LispVal> items) {
		isTrue(items.size() == 3, "drop takes a count and a collection");
		return dropForm(lower(items.get(1)), seqForm(lower(items.get(2))));
	}

	/** The already-lowered seq view past the first {@code count}. */
	private LispVal dropForm(LispVal count, LispVal seqView) {
		LispSymbol left = freshTemp();
		LispSymbol seq = freshTemp();
		return list(sym("let"), list(List.of(list(left, count), list(seq, seqView))),
				list(sym("if"), list(sym("<="), left, new LispInteger(0)), seq, list(sym("nthcdr"), left, seq)));
	}

	/**
	 * {@code range} with an end: the strict list, built by a labels self call. Without an
	 * end there is no strict lowering -- an infinite seq cannot be spelled -- so it is
	 * refused by name, like {@code lazy-seq}. A zero step signals at run time.
	 */
	private LispVal rangeOf(List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			throw new LispReadException("infinite range is not supported: range needs an end");
		}
		isTrue(n >= 1 && n <= 3, "range takes an end, or a start, an end and an optional step");
		LispVal start = n >= 2 ? lower(items.get(1)) : new LispInteger(0);
		LispVal end = n >= 2 ? lower(items.get(2)) : lower(items.get(1));
		LispVal step = n == 3 ? lower(items.get(3)) : new LispInteger(1);
		return rangeForm(start, end, step);
	}

	/** The strict list from {@code start} below {@code end} stepping by {@code step}. */
	private LispVal rangeForm(LispVal start, LispVal end, LispVal step) {
		String name = mangle("range-") + (this.counter++);
		LispSymbol self = new LispSymbol(name);
		LispSymbol from = freshTemp();
		LispSymbol to = freshTemp();
		LispSymbol by = freshTemp();
		LispSymbol at = freshTemp();
		LispSymbol grown = freshTemp();
		LispVal pastEnd = list(sym("if"), list(sym("plusp"), by), list(sym(">="), at, to), list(sym("<="), at, to));
		LispVal advance = list(self, list(sym("+"), at, by), list(sym("cons"), at, grown));
		LispVal body = list(sym("if"), pastEnd, list(sym("reverse"), grown), advance);
		LispVal binding = new LispCons(self, new LispCons(list(List.of(at, grown)), cons(body, List.of())));
		return list(sym("let"), list(List.of(list(from, start), list(to, end), list(by, step))),
				list(sym("if"), list(sym("zerop"), by),
						list(sym("error"), LispString.literal("range step cannot be zero")),
						list(sym("labels"), list(List.of(binding)), list(self, from, NIL_CONST))));
	}

	/**
	 * {@code =} over any arity: pairs of neighbours compared with the map- and set-aware
	 * two-form below, {@code AND}ed. Zero arguments is true; one evaluates its argument
	 * and is true. The answer is raw ({@code T} or {@code NIL}); the call sites wrap it
	 * in {@link #booleanAnswer} for the Clojure {@code T}-or-false.
	 */
	private LispVal equalityRaw(List<LispVal> items) {
		int n = items.size() - 1;
		if (n == 0) {
			return TRUE_CONST;
		}
		if (n == 1) {
			return list(sym("progn"), lower(items.get(1)), TRUE_CONST);
		}
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> names = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			LispSymbol one = freshTemp();
			bindings.add(list(one, lower(items.get(i))));
			names.add(one);
		}
		if (n == 2) {
			return list(sym("let"), list(bindings), equalityTwo(names.get(0), names.get(1)));
		}
		List<LispVal> pairs = new ArrayList<>();
		for (int i = 0; i + 1 < names.size(); i++) {
			pairs.add(equalityTwo(names.get(i), names.get(i + 1)));
		}
		return list(sym("let"), list(bindings), cons(sym("and"), pairs));
	}

	/**
	 * Two values compared the Clojure way: two wrapped sets by membership both ways
	 * (order-free, deep in the members), two tables entry by entry (deep in the values),
	 * anything else with {@code equal}. The comparison is a labels self call, so nested
	 * maps and sets compare all the way down.
	 */
	private LispVal equalityTwo(LispVal first, LispVal second) {
		LispSymbol eq = freshTemp();
		LispSymbol left = freshTemp();
		LispSymbol right = freshTemp();
		List<LispVal> branches = new ArrayList<>();
		branches.add(list(list(sym("and"), isSetForm(left), isSetForm(right)), setEquality(left, right, eq)));
		branches.add(list(list(sym("and"), list(sym("hash-table-p"), left), list(sym("hash-table-p"), right)),
				mapEquality(left, right, eq)));
		branches.add(list(TRUE_CONST, list(sym("equal"), left, right)));
		LispVal test = cons(sym("cond"), branches);
		LispVal binding = new LispCons(eq, new LispCons(list(List.of(left, right)), cons(test, List.of())));
		return list(sym("labels"), list(List.of(binding)), new LispCons(eq, list(List.of(first, second))));
	}

	/** Two tables are equal when they hold the same count and every entry agrees. */
	private LispVal mapEquality(LispVal left, LispVal right, LispVal eq) {
		LispSymbol ok = freshTemp();
		LispSymbol miss = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispVal walk = list(sym("maphash"),
				list(sym("lambda"), list(List.of(key, val)),
						list(sym("when"),
								list(sym("or"), list(sym("eq"), list(sym("gethash"), key, right, miss), miss),
										list(sym("not"), list(eq, val, list(sym("gethash"), key, right)))),
								list(sym("setq"), ok, NIL_CONST))),
				left);
		return list(sym("and"),
				list(sym("eql"), list(sym("hash-table-count"), left), list(sym("hash-table-count"), right)),
				list(sym("let"), list(List.of(list(ok, TRUE_CONST), list(miss, list(sym("list"), NIL_CONST)))), walk,
						ok));
	}

	/**
	 * Two wrapped sets are equal when they hold the same count and every member agrees.
	 */
	private LispVal setEquality(LispVal left, LispVal right, LispVal eq) {
		LispSymbol ok = freshTemp();
		LispSymbol miss = freshTemp();
		LispSymbol found = freshTemp();
		LispSymbol key = freshTemp();
		LispSymbol val = freshTemp();
		LispVal walk = list(sym("maphash"),
				list(sym("lambda"), list(List.of(key, val)), list(sym("declare"), list(sym("ignore"), val)),
						list(sym("when"),
								list(sym("or"), list(sym("eq"), list(sym("gethash"), key, found, miss), miss),
										list(sym("not"), list(eq, key, list(sym("gethash"), key, found)))),
								list(sym("setq"), ok, NIL_CONST))),
				setInner(left));
		return list(sym("and"),
				list(sym("eql"), list(sym("hash-table-count"), setInner(left)),
						list(sym("hash-table-count"), setInner(right))),
				list(sym("let"), list(List.of(list(ok, TRUE_CONST), list(miss, list(sym("list"), NIL_CONST)),
						list(found, setInner(right)))), walk, ok));
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

	/**
	 * A function in argument position: a known defn, a local binding, a lambda, a
	 * keyword.
	 */
	private LispVal fnValue(LispVal form) {
		if (form instanceof LispSymbol s && s.name().startsWith(":")) {
			// no scope can bind a keyword (plainName refuses one), so this is data
			validateKeyword(s.name());
			return keywordFn(form);
		}
		if (form instanceof LispSymbol s && known(s.name())) {
			if (isFunction(s.name())) {
				return list(sym("function"), idSym(s.name()));
			}
			return idSym(s.name());
		}
		if (form instanceof LispSymbol s) {
			LispVal predicate = predicateValue(s.name());
			if (predicate != null) {
				return predicate;
			}
		}
		return lower(form);
	}

	/**
	 * A keyword as a function value: the lookup over one argument, so
	 * {@code (map :k coll)} reads the key out of each member. The key lowers once, behind
	 * a temporary; the collection is the lambda's parameter, named once per branch by
	 * {@link #getBranches}.
	 * @param keyDatum the keyword datum
	 * @return the form
	 */
	private LispVal keywordFn(LispVal keyDatum) {
		LispSymbol coll = freshTemp();
		LispSymbol key = freshTemp();
		return list(sym("lambda"), list(coll), list(sym("let"), list(List.of(list(key, lower(keyDatum)))),
				cons(sym("cond"), getBranches(coll, key, NIL_CONST))));
	}

	/**
	 * A predicate as a first-class value: a lambda answering a Common Lisp boolean, so a
	 * sequence function called with it keeps testing raw truthiness. A call answers the
	 * Clojure {@code T}-or-false instead, for printing; null when not a predicate.
	 * @param name the Clojure name
	 * @return the lambda, or null
	 */
	private @Nullable LispVal predicateValue(String name) {
		LispSymbol arg = new LispSymbol(mangle("pred"));
		return switch (name) {
			case "false?" -> list(sym("LAMBDA"), list(arg), list(sym("EQ"), arg, this.falseVariable));
			case "true?" -> list(sym("LAMBDA"), list(arg), list(sym("EQ"), arg, TRUE_CONST));
			case "boolean?" -> list(sym("LAMBDA"), list(arg),
					list(sym("OR"), list(sym("EQ"), arg, TRUE_CONST), list(sym("EQ"), arg, this.falseVariable)));
			default -> null;
		};
	}

	private LispVal strCall(List<LispVal> items) {
		if (items.size() == 1) {
			return LispString.literal("");
		}
		return concat(stringParts(items, LispString.literal(""), false, false));
	}

	private LispVal printCall(List<LispVal> items, boolean newline) {
		if (!newline && items.size() == 1) {
			return list(sym("princ"), LispString.literal(""));
		}
		// one concatenate whose last part is the newline: no separate newline call
		List<LispVal> parts = stringParts(items, LispString.literal("nil"), false, true);
		if (newline) {
			parts.add(LispString.literal("\n"));
		}
		return list(sym("princ"), concat(parts));
	}

	/**
	 * {@code pr}/{@code prn}: the readable arms of {@code print}/{@code println} -- same
	 * space separator, same newline folding, but every part converts through
	 * {@code prin1-to-string}, so strings print quoted like the oracle's.
	 */
	private LispVal prCall(List<LispVal> items, boolean newline) {
		if (!newline && items.size() == 1) {
			return list(sym("princ"), LispString.literal(""));
		}
		List<LispVal> parts = stringParts(items, LispString.literal("nil"), true, true);
		if (newline) {
			parts.add(LispString.literal("\n"));
		}
		return list(sym("princ"), concat(parts));
	}

	private LispVal concat(List<LispVal> parts) {
		return new LispCons(sym("concatenate"), new LispCons(list(sym("quote"), sym("string")), list(parts)));
	}

	/**
	 * Every argument's string, joined with a single space when asked (what
	 * {@code print}/{@code println}/{@code pr}/{@code prn} separate with, like the
	 * oracle) or concatenated bare (what {@code str} has always done).
	 */
	private List<LispVal> stringParts(List<LispVal> items, LispVal nilReplacement, boolean readable, boolean spaced) {
		List<LispVal> parts = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			if (spaced && i > 1) {
				parts.add(LispString.literal(" "));
			}
			parts.add(printPart(lower(items.get(i)), nilReplacement, readable));
		}
		return parts;
	}

	/**
	 * One printed part's string: {@code "false"} for the false object, {@code "true"} for
	 * {@code T}, the replacement for {@code NIL} ({@code ""} in {@code str},
	 * {@code "nil"} in {@code print}/{@code println}/{@code pr}/{@code prn}), the colon
	 * spelling for a keyword, else {@code princ-to-string} (or {@code prin1-to-string}
	 * for the readable arms). The value runs once, behind a temporary no user identifier
	 * can spell.
	 * @param value the lowered value
	 * @param nilReplacement the string {@code NIL} prints as
	 * @param readable whether the fallback conversion reads back
	 * @return the form
	 */
	private LispVal printPart(LispVal value, LispVal nilReplacement, boolean readable) {
		LispSymbol temp = freshTemp();
		return list(sym("LET"), list(list(temp, value)), printPartBody(temp, nilReplacement, readable));
	}

	/**
	 * The string of an already-bound value: the {@code IF} chain inside
	 * {@link #printPart}, shared with the {@code str} function value (whose argument is
	 * its lambda's parameter, so there is no temporary to bind).
	 */
	private LispVal printPartBody(LispVal value, LispVal nilReplacement, boolean readable) {
		return list(sym("IF"), list(sym("EQ"), value, this.falseVariable), LispString.literal("false"),
				list(sym("IF"), list(sym("EQ"), value, TRUE_CONST), LispString.literal("true"),
						list(sym("IF"), list(sym("NULL"), value), nilReplacement, keywordOrString(value, readable))));
	}

	/**
	 * A keyword prints with its leading colon ({@code :a}); anything else prints through
	 * {@code princ-to-string} (or {@code prin1-to-string} for the readable arms). The tag
	 * test names the wrapper, and the string test keeps a user list that happens to share
	 * the tag's head from reaching the colon path with a non-string tail.
	 * @param value the bound part value
	 * @param readable whether the fallback conversion reads back
	 * @return the form
	 */
	private LispVal keywordOrString(LispVal value, boolean readable) {
		LispVal isKeyword = list(sym("AND"), list(sym("CONSP"), value),
				list(sym("EQ"), list(sym("CAR"), value), KEYWORD_TAG), list(sym("STRINGP"), list(sym("CADR"), value)));
		LispVal spelled = concat(List.of(LispString.literal(":"), list(sym("CADR"), value)));
		String converter = readable ? "prin1-to-string" : "princ-to-string";
		return list(sym("IF"), isKeyword, spelled, list(sym(converter), value));
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
				paramSyms.add(idSym(rest));
				continue;
			}
			String param = plainName(names.get(i), "the parameter vector of");
			scope.put(param, Kind.VARIABLE);
			paramSyms.add(idSym(param));
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
				return this.falseVariable;
			default:
				break;
		}
		if (name.startsWith(":")) {
			validateKeyword(name);
			return keywordForm(name.substring(1)); // a keyword is its spelling,
													// case-preserved
		}
		if (name.equals("%") || name.length() > 1 && name.charAt(0) == '%'
				&& name.substring(1).chars().allMatch(Character::isDigit)) {
			if (this.anonArgs == null) {
				throw new LispReadException(name + " outside the anon form #(...)");
			}
			String args = this.anonArgs;
			int oneBased = name.equals("%") ? 1 : Integer.parseInt(name.substring(1));
			isTrue(oneBased <= 9, "the anon form #(...) takes at most 9 arguments");
			return list(sym("nth"), new LispInteger(oneBased - 1), new LispSymbol(args));
		}
		if (!known(name)) {
			if (name.equals("nth")) {
				return nthValue();
			}
			if (name.equals("quot")) {
				return quotValue();
			}
			LispVal synth = valueOf(name);
			if (synth != null) {
				return synth;
			}
			String cl = builtinValue(name);
			if (cl == null) {
				throw new LispReadException("unknown name: " + name);
			}
			return list(sym("function"), sym(cl));
		}
		if (isFunction(name)) {
			return list(sym("function"), idSym(name));
		}
		return idSym(name);
	}

	/** The Common Lisp function a core name names as a value, or null. */
	private static @Nullable String builtinValue(String name) {
		return switch (name) {
			case "+", "-", "*", "/", "max", "min", "rem", "mod", "abs", "cons", "list", "expt", "reverse", "apply", "=",
					"<", ">", "<=", ">=", "not", "length", "car", "cdr", "equal", "evenp", "oddp", "zerop", "plusp",
					"minusp", "vector", "vectorp" ->
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
			if (name.equals("true")) {
				return list(sym("quote"), TRUE_CONST);
			}
			if (name.equals("false")) {
				return list(sym("quote"), new LispSymbol(FALSE_VALUE_NAME));
			}
			if (name.startsWith(":")) {
				validateKeyword(name);
				return list(sym("quote"), keywordDatum(name.substring(1)));
			}
			return list(sym("quote"), idSym(name));
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
			if (!items.isEmpty()
					&& (isSymbolNamed(items.get(0), "%hash-map") || isSymbolNamed(items.get(0), "%hash-set"))) {
				return quotedCollection(items);
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

	/**
	 * A quoted map or set literal: the construction over the quoted elements, so
	 * {@code '{:a x}} builds a table holding the symbol. Markers never reach the
	 * per-element path -- they are skipped here, not quoted.
	 */
	private LispVal quotedCollection(List<LispVal> items) {
		List<LispVal> quoted = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			quoted.add(quotedElement(items.get(i)));
		}
		if (isSymbolNamed(items.get(0), "%hash-map")) {
			return mapBuild(quoted);
		}
		return setBuild(quoted);
	}

	/**
	 * One element of a quoted map or set construction: the construction RUNS, so its
	 * elements are forms, not data -- a keyword travels as its construction (its datum in
	 * a {@code (LIST ...)} element would call the tag as a function); anything else
	 * quotes as usual.
	 */
	private LispVal quotedElement(LispVal datum) {
		if (datum instanceof LispSymbol s && s.name().startsWith(":")) {
			validateKeyword(s.name());
			return keywordForm(s.name().substring(1));
		}
		return quotedConstant(datum);
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

	private static boolean isNsForm(LispVal val) {
		if (isSymbolNamed(val, "ns")) {
			return true;
		}
		List<LispVal> items = items(val);
		return items != null && !items.isEmpty() && isSymbolNamed(items.get(0), "ns");
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

	/**
	 * A user identifier as a symbol: mangled behind the prefix but otherwise
	 * case-preserved, so {@code Foo} and {@code foo} stay apart. The prefix holds a
	 * lowercase letter, so no result can collide with a core form or built-in.
	 */
	private static LispSymbol idSym(String identifier) {
		return new LispSymbol(mangle(identifier));
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
