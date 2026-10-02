package am.ik.rontolisp.clojure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispDouble;
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
 * Macro forms of the Clojure lowering: defmacro, expansion, syntax-quote and the macro
 * runtime.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureMacroLowering {

	private ClojureMacroLowering() {
	}

	/** The runtime once-expander behind {@code macroexpand-1}, as spelled in programs. */
	static final String MACROEXPAND_1 = "C%MACROEXPAND-1";

	/**
	 * The runtime fixpoint expander behind {@code macroexpand}, as spelled in programs.
	 */
	static final String MACROEXPAND = "C%MACROEXPAND";

	/**
	 * The names a {@code defmacro} cannot take: every special form a call site could
	 * never reach through the macro table (the form intercepts first), the three new
	 * builtins below (whose value paths would disagree with the macro), the two dot-heads
	 * (instance-call position) and, by rule in {@link #defmacroForms}, anything dotted,
	 * suffixed or qualified.
	 */
	static final Set<String> MACRO_RESERVED = Set.of("quote", "def", "defn", "defn-", "defonce", "defstruct", "struct",
			"struct-map", "defmacro", "fn", "let", "letfn", "loop", "declare", "->", "->>", "as->", "doto", "cond->",
			"cond->>", "some->", "some->>", "list*", "doseq", "dotimes", "for", "defmulti", "defmethod",
			"remove-method", "get-method", "prefer-method", "derive", "underive", "isa?", "parents", "ancestors",
			"descendants", "make-hierarchy", "defprotocol", "defrecord", "deftype", "definterface", "reify",
			"extend-protocol", "extend-type", "extend", "satisfies?", "gen-class", "gen-interface", "try", "throw",
			"ex-info", "ex-data", "ex-message", "atom", "deref", "swap!", "reset!", "compare-and-set!", "volatile!",
			"vreset!", "vswap!", "add-watch", "remove-watch", "ref", "dosync", "alter", "commute", "ref-set", "ensure",
			"agent", "send", "send-off", "await", "shutdown-agents", "binding", "with-open", "with-out-str", "time",
			"comment", "require", "use", "import", "in-ns", "set!", "memfn", "proxy", "new", "syntax-quote", "unquote",
			"unquote-splicing", "var", "with-meta", "if", "when", "cond", "do", "recur", ".", "..", "gensym",
			"macroexpand-1", "macroexpand");

	/**
	 * {@code (defmacro name doc? attr? ([params] body...)+)}: a compile-time expander
	 * plus its runtime table entry, so {@code macroexpand-1} sees the same function the
	 * lower-time expansion runs. One entry per arity is overkill here (unlike
	 * {@code defn}, whose arities are callable separately): the expander is a single
	 * lambda over the call's argument list dispatching on its length, applying each
	 * arity's parameters the way {@code multiFn} binds its. A docstring and an attr map
	 * are skipped, like {@code defn} and {@code defmulti}; {@code &} rest, destructured
	 * parameters and several arities work the same way. {@code &form} and {@code &env}
	 * are refused: there is no compilation environment to bind. The definition emits
	 * {@code (progn (setq c%name%macro expander) nil)} -- a lone {@code %} no mangled
	 * identifier spells, so the table stays apart from user definitions, like the
	 * multi-arity helpers -- and registers the expander for the call sites below it; a
	 * call above the definition names the missing expander instead of an unknown name.
	 */
	static List<LispVal> defmacroForms(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "defmacro needs a name, a parameter vector and a body");
		String name = ClojureLowerUtil.plainName(items.get(1), "defmacro");
		ClojureLowerUtil.isTrue(
				!MACRO_RESERVED.contains(name) && !name.startsWith(".") && !name.endsWith(".") && name.indexOf('/') < 0,
				name + " cannot name a macro: it names a core form");
		int at = 2;
		if (items.size() > at && items.get(at) instanceof LispString) {
			at++; // the docstring
		}
		if (items.size() > at) {
			List<LispVal> attr = ClojureLowerUtil.items(items.get(at));
			if (attr != null && !attr.isEmpty() && ClojureLowerUtil.isSymbolNamed(attr.get(0), "%hash-map")) {
				at++; // the attr map
			}
		}
		ClojureLowerUtil.isTrue(items.size() > at, "defmacro needs a parameter vector and a body");
		List<ClojureLowering.Clause> clauses;
		if (items.get(at) instanceof LispCons && !ClojureBindingLowering.isVectorDatum(items.get(at))) {
			List<LispVal> raw = items.subList(at, items.size());
			for (LispVal clauseDatum : raw) {
				List<LispVal> parts = ClojureLowerUtil.items(clauseDatum);
				if (parts != null && !parts.isEmpty()) {
					refuseEnvForm(parts.get(0));
				}
			}
			clauses = ClojureBindingLowering.arityClauses(ctx, raw, "defmacro");
		}
		else {
			refuseEnvForm(items.get(at));
			clauses = List.of(ClojureBindingLowering.clause(ctx, items.get(at), items.subList(at + 1, items.size())));
		}
		LispVal expander = macroExpander(ctx, name, clauses);
		LispSymbol table = macroTable(name);
		ctx.macros.put(name, expander);
		ctx.globals.put(name, ClojureLowering.Kind.MACRO);
		ctx.usedMacros = true;
		LispVal setq = ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), table, expander);
		if (ctx.macroEvaluator != null) {
			// the macro-time table entry, so a macro body calling macroexpand-1
			// at expansion time sees the macros defined so far
			try {
				ctx.macroEvaluator.evaluate(setq);
			}
			catch (RuntimeException ex) {
				throw ctx.positioned(new LispReadException(exMessage(ex)), items.get(0));
			}
		}
		return List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), setq, ClojureLowering.NIL_CONST));
	}

	/** The runtime table global holding a macro's expander. */
	static LispSymbol macroTable(String name) {
		return new LispSymbol(ClojureLowering.mangle(name) + "%macro");
	}

	/**
	 * Refuses {@code &form} and {@code &env} anywhere in a macro parameter datum: a macro
	 * body runs with its arguments only, never with a compilation environment.
	 */
	static void refuseEnvForm(LispVal datum) {
		if (datum instanceof LispSymbol s) {
			if (s.name().equals("&form") || s.name().equals("&env")) {
				throw new LispReadException(s.name() + " is not supported yet: macros receive no environment");
			}
			return;
		}
		List<LispVal> parts = ClojureLowerUtil.items(datum);
		if (parts == null) {
			return;
		}
		for (LispVal part : parts) {
			refuseEnvForm(part);
		}
	}

	/**
	 * A macro's expander: one lambda over the call's argument list, dispatching on its
	 * length like the multi-arity dispatch and applying each arity's parameters (with
	 * their destructuring prologue) to the lowered body. The same lambda runs at lower
	 * time (through the macro evaluator) and at run time (through the table global), so
	 * the two expansions agree by construction.
	 */
	static LispVal macroExpander(ClojureLowering ctx, String name, List<ClojureLowering.Clause> clauses) {
		LispSymbol args = ctx.freshTemp();
		if (clauses.size() == 1 && !clauses.get(0).variadic()) {
			ClojureLowering.Clause only = clauses.get(0);
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(args),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil.sym("="),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), args), new LispInteger(only.fixed())),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
											ClojureLowerUtil.list(only.params()), only.wrapped()),
									args),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
									LispString.literal("wrong number of arguments passed to macro: " + name))));
		}
		LispSymbol count = ctx.freshTemp();
		List<LispVal> arms = new ArrayList<>();
		for (ClojureLowering.Clause clause : clauses) {
			LispVal test = clause.variadic()
					? ClojureLowerUtil.list(ClojureLowerUtil.sym(">="), count, new LispInteger(clause.fixed()))
					: ClojureLowerUtil.list(ClojureLowerUtil.sym("="), count, new LispInteger(clause.fixed()));
			arms.add(ClojureLowerUtil.list(test,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), ClojureLowerUtil
						.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(clause.params()), clause.wrapped()),
							args)));
		}
		arms.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
				LispString.literal("wrong number of arguments passed to macro: " + name))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(args), ClojureLowerUtil.list(
				ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List
					.of(ClojureLowerUtil.list(count, ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), args)))),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), arms)));
	}

	/**
	 * A call whose head names a macro: the expansion, lowered in place of the call. A
	 * local binding shadows the macro (a parameter, a {@code let} name); a macro name
	 * without its expander is a call above its definition. Null when the head names no
	 * macro.
	 */
	static @Nullable LispVal macroCall(ClojureLowering ctx, String name, List<LispVal> items) {
		for (Map<String, ClojureLowering.Kind> scope : ctx.scopes) {
			if (scope.containsKey(name)) {
				return null; // a local binding shadows the macro
			}
		}
		LispVal expander = ctx.macros.get(name);
		String macroName = name;
		if (expander == null) {
			ClojureLowering.VarRef qualified = ClojureNamespaceLowering.resolveQualified(ctx, name);
			ClojureLowering.VarRef ref = qualified != null ? qualified : ctx.refers.get(name);
			if (ref != null) {
				expander = ctx.macros.get(ref.var());
				macroName = ref.var();
			}
		}
		if (expander == null) {
			if (ctx.isMacro(name)) {
				throw new LispReadException("macro `" + name + "` used before its definition");
			}
			return null;
		}
		return expandMacro(ctx, macroName, expander, items);
	}

	/**
	 * One macro call expanded: the argument datums travel quoted (the same values a
	 * quoted form answers at run time, so the two expanders agree) into one application
	 * of the expander, the answer decodes back to a datum and lowers like any other form
	 * -- which expands the macros the expansion calls, one depth deeper.
	 */
	static LispVal expandMacro(ClojureLowering ctx, String name, LispVal expander, List<LispVal> items) {
		if (ctx.macroDepth >= ClojureLowering.MAX_MACRO_DEPTH) {
			throw new LispReadException("macro expansion of `" + name + "` did not terminate");
		}
		if (ctx.macroEvaluator == null) {
			throw new LispReadException(
					"macro `" + name + "` cannot expand without a macro evaluator (lower with one)");
		}
		List<LispVal> quoted = new ArrayList<>();
		for (int i = 1; i < items.size(); i++) {
			quoted.add(ctx.quote(items.get(i)));
		}
		LispVal invocation = ClojureLowerUtil.list(ClojureLowerUtil.sym("funcall"), expander,
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), quoted));
		LispVal value;
		try {
			value = ctx.macroEvaluator.evaluate(invocation);
		}
		catch (RuntimeException ex) {
			throw new LispReadException("in macro `" + name + "`: " + exMessage(ex));
		}
		LispVal expansion;
		try {
			expansion = decodeDatum(ctx, value);
		}
		catch (LispReadException ex) {
			throw new LispReadException("macro `" + name + "` answered an unreadable value: " + ex.getMessage());
		}
		ctx.macroDepth++;
		try {
			return ctx.lower(expansion);
		}
		finally {
			ctx.macroDepth--;
		}
	}

	static String exMessage(RuntimeException ex) {
		String message = ex.getMessage();
		return message == null ? ex.toString() : message;
	}

	/**
	 * A macro answer back to a datum: the inverse of {@link #quote}, so the expansion
	 * lowers the way the quoted call-site data would. Mangled symbols shed the prefix,
	 * keyword and set wrappers answer their datum, vectors and tables their literals, and
	 * a gensym ({@code #:}-spelled, uninterned) travels as itself so the {@code #:}
	 * bypass lowers it back to the same symbol.
	 */
	static LispVal decodeDatum(ClojureLowering ctx, LispVal value) {
		if (value instanceof LispNil) {
			return new LispSymbol("nil");
		}
		if (value instanceof LispTrue) {
			return new LispSymbol("true");
		}
		if (value instanceof LispSymbol s) {
			String symbol = s.name();
			if (symbol.equals("false") || symbol.equals("T")) {
				return new LispSymbol(symbol.equals("T") ? "true" : "false");
			}
			if (symbol.startsWith("#:")) {
				return new LispSymbol(symbol);
			}
			if (symbol.startsWith(ClojureLowering.PREFIX)) {
				return new LispSymbol(unmangleName(symbol.substring(ClojureLowering.PREFIX.length())));
			}
			throw new LispReadException("an unreadable symbol: " + value.print());
		}
		if (value instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol tag) {
				if (tag.name().equals(":C%KEYWORD")) {
					return decodeKeyword(ctx, cons);
				}
				if (tag.name().equals(":C%SET")) {
					return decodeSet(ctx, cons);
				}
				if (tag.name().equals(":C%RECORD")) {
					return decodeRecord(ctx, cons);
				}
				if (tag.name().equals(":C%ATOM")) {
					throw new LispReadException("an atom cannot travel through a macro expansion");
				}
			}
			List<LispVal> out = new ArrayList<>();
			LispVal run = value;
			while (run instanceof LispCons cell) {
				out.add(decodeDatum(ctx, cell.car()));
				run = cell.cdr();
			}
			if (run instanceof LispNil) {
				return ClojureLowerUtil.list(out);
			}
			LispVal tail = decodeDatum(ctx, run);
			for (int i = out.size() - 1; i >= 0; i--) {
				tail = new LispCons(out.get(i), tail);
			}
			return tail;
		}
		if (value instanceof LispArray array && array.dimensions().length == 1) {
			List<LispVal> elements = new ArrayList<>();
			elements.add(ClojureReader.VECTOR);
			for (int i = 0; i < array.totalSize(); i++) {
				elements.add(decodeDatum(ctx, array.readFlat(i)));
			}
			return ClojureLowerUtil.list(elements);
		}
		if (value instanceof LispHashTable table) {
			List<LispVal> elements = new ArrayList<>();
			elements.add(new LispSymbol("%hash-map"));
			for (LispHashTable.Entry entry : table.entries()) {
				elements.add(decodeDatum(ctx, entry.key()));
				elements.add(decodeDatum(ctx, entry.value()));
			}
			return ClojureLowerUtil.list(elements);
		}
		if (value instanceof LispString || value instanceof LispChar || value instanceof LispInteger
				|| value instanceof am.ik.rontolisp.LispBigInteger || value instanceof am.ik.rontolisp.LispRatio
				|| value instanceof am.ik.rontolisp.LispDouble) {
			return value;
		}
		throw new LispReadException("an unreadable value: " + value.print());
	}

	static LispVal decodeKeyword(ClojureLowering ctx, LispCons wrapper) {
		if (wrapper.cdr() instanceof LispCons rest && rest.car() instanceof LispString spelling
				&& rest.cdr() instanceof LispNil) {
			return new LispSymbol(":" + spelling.value());
		}
		throw new LispReadException("an unreadable value: " + wrapper.print());
	}

	/**
	 * A record answer back to its literal: {@code (%record ns.Name {entries})}, which
	 * lowers through the map constructor like the source spelling.
	 */
	static LispVal decodeRecord(ClojureLowering ctx, LispCons wrapper) {
		List<LispVal> parts = ClojureLowerUtil.items(wrapper);
		if (parts != null && parts.size() == 5 && parts.get(3) instanceof LispHashTable table
				&& parts.get(4) instanceof LispString className) {
			return ClojureLowerUtil.list(new LispSymbol("%record"), new LispSymbol(className.value()),
					decodeDatum(ctx, table));
		}
		throw new LispReadException("an unreadable value: " + wrapper.print());
	}

	static LispVal decodeSet(ClojureLowering ctx, LispCons wrapper) {
		if (wrapper.cdr() instanceof LispCons rest && rest.car() instanceof LispHashTable table
				&& rest.cdr() instanceof LispNil) {
			List<LispVal> elements = new ArrayList<>();
			elements.add(new LispSymbol("%hash-set"));
			for (LispHashTable.Entry entry : table.entries()) {
				elements.add(decodeDatum(ctx, entry.key()));
			}
			return ClojureLowerUtil.list(elements);
		}
		throw new LispReadException("an unreadable value: " + wrapper.print());
	}

	/**
	 * The inverse of {@link #mangle}: {@code %%} is {@code %}, {@code %c} is {@code :},
	 * anything else travels as spelled.
	 */
	static String unmangleName(String mangled) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < mangled.length(); i++) {
			char c = mangled.charAt(i);
			if (c == '%' && i + 1 < mangled.length()) {
				char next = mangled.charAt(i + 1);
				if (next == '%') {
					out.append('%');
					i++;
					continue;
				}
				if (next == 'c') {
					out.append(':');
					i++;
					continue;
				}
			}
			out.append(c);
		}
		return out.toString();
	}

	/**
	 * {@code `form}: syntax-quote, lowered to {@code quote} with unquote splicing over
	 * the mangled namespace. Every symbol qualifies (the {@code c%} prefix, the
	 * documented deviation: there are no namespaces to qualify against); {@code ~} lowers
	 * its form as code, {@code ~@} splices a sequence into the enclosing list, vector,
	 * map or set, and each {@code x#} binds one {@code (gensym "x")} per syntax-quote
	 * node, so the name is one symbol per expansion and the same symbol at every
	 * occurrence within it.
	 */
	static LispVal syntaxQuote(ClojureLowering ctx, LispVal datum) {
		return syntaxQuoteNode(ctx, datum);
	}

	static LispVal syntaxQuoteNode(ClojureLowering ctx, LispVal datum) {
		Map<String, LispSymbol> gens = new LinkedHashMap<>();
		ctx.syntaxGens.add(gens);
		try {
			LispVal body = syntaxQuoted(ctx, datum, 1, gens);
			if (gens.isEmpty()) {
				return body;
			}
			List<LispVal> bindings = new ArrayList<>();
			for (Map.Entry<String, LispSymbol> entry : gens.entrySet()) {
				bindings.add(ClojureLowerUtil.list(entry.getValue(),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gensym"), LispString.literal(entry.getKey()))));
			}
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(bindings), body);
		}
		finally {
			ctx.syntaxGens.remove(ctx.syntaxGens.size() - 1);
		}
	}

	static LispVal syntaxQuoted(ClojureLowering ctx, LispVal datum, int level, Map<String, LispSymbol> gens) {
		List<LispVal> marked = ClojureLowerUtil.items(datum, List.of());
		if (!marked.isEmpty() && marked.get(0) == ClojureReader.VECTOR) {
			return syntaxQuotedVector(ctx, marked.subList(1, marked.size()), level, gens);
		}
		if (!marked.isEmpty() && ClojureLowerUtil.isSymbolNamed(marked.get(0), "%hash-map")) {
			return syntaxQuotedMap(ctx, marked.subList(1, marked.size()), level, gens);
		}
		if (!marked.isEmpty() && ClojureLowerUtil.isSymbolNamed(marked.get(0), "%hash-set")) {
			return syntaxQuotedSet(ctx, marked.subList(1, marked.size()), level, gens);
		}
		if (!marked.isEmpty()
				&& (marked.get(0) == ClojureReader.REGEX || ClojureLowerUtil.isSymbolNamed(marked.get(0), "%regex"))) {
			return ClojureCollectionLowering.regexForm(marked);
		}
		if (!marked.isEmpty() && ClojureLowerUtil.isSymbolNamed(marked.get(0), "%record")) {
			// a record literal is already a value: syntax-quote leaves it alone
			return ClojureProtocolLowering.recordLiteral(ctx, marked);
		}
		if (datum instanceof LispCons) {
			List<LispVal> parts = ClojureLowerUtil.items(datum);
			if (parts == null) {
				throw new LispReadException("a dotted list is not a Clojure form");
			}
			if (!parts.isEmpty() && parts.get(0) instanceof LispSymbol head) {
				if (head.name().equals("syntax-quote")) {
					ClojureLowerUtil.isTrue(parts.size() == 2, "syntax-quote takes one form");
					return syntaxQuoteNode(ctx, parts.get(1));
				}
				if (head.name().equals("unquote")) {
					ClojureLowerUtil.isTrue(parts.size() == 2, "unquote takes one form");
					if (level == 1) {
						return unquoted(ctx, parts.get(1));
					}
					return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), syntaxQuotedSymbol(ctx, "unquote"),
							syntaxQuoted(ctx, parts.get(1), level - 1, gens));
				}
				if (head.name().equals("unquote-splicing")) {
					ClojureLowerUtil.isTrue(parts.size() == 2, "unquote-splicing takes one form");
					if (level == 1) {
						throw new LispReadException(
								"unquote-splicing outside a sequence: `~@` only splices inside a list, vector, map or set");
					}
					return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"),
							syntaxQuotedSymbol(ctx, "unquote-splicing"),
							syntaxQuoted(ctx, parts.get(1), level - 1, gens));
				}
			}
			return syntaxQuotedSeq(ctx, parts, level, gens);
		}
		if (datum instanceof LispSymbol s) {
			return syntaxQuotedSymbol(ctx, s.name(), gens);
		}
		return datum; // numbers, strings and characters are self-evaluating
	}

	static LispVal syntaxQuotedSymbol(ClojureLowering ctx, String name) {
		return syntaxQuotedSymbol(ctx, name, null);
	}

	static LispVal syntaxQuotedSymbol(ClojureLowering ctx, String name, @Nullable Map<String, LispSymbol> gens) {
		if (name.equals("nil")) {
			return ClojureLowering.NIL_CONST;
		}
		if (name.equals("true")) {
			return ClojureLowering.TRUE_CONST;
		}
		if (name.equals("false")) {
			return ctx.falseVariable;
		}
		if (name.startsWith(":")) {
			return ClojureCollectionLowering.keywordForm(ClojureCollectionLowering.resolveKeywordSpelling(ctx, name));
		}
		if (name.endsWith("#") && name.length() > 1 && gens != null) {
			String stem = name.substring(0, name.length() - 1);
			LispSymbol bound = gens.get(stem);
			if (bound == null) {
				bound = ctx.freshTemp();
				gens.put(stem, bound);
			}
			return bound;
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.idSym(name));
	}

	/**
	 * An unquoted form: code, lowered as written -- except {@code ~x#}, which answers the
	 * gensym the enclosing template bound for {@code x#}.
	 */
	static LispVal unquoted(ClojureLowering ctx, LispVal datum) {
		if (datum instanceof LispSymbol s && s.name().endsWith("#") && s.name().length() > 1) {
			String stem = s.name().substring(0, s.name().length() - 1);
			for (int i = ctx.syntaxGens.size() - 1; i >= 0; i--) {
				LispSymbol bound = ctx.syntaxGens.get(i).get(stem);
				if (bound != null) {
					return bound;
				}
			}
		}
		return ctx.lower(datum);
	}

	static LispVal syntaxQuotedSeq(ClojureLowering ctx, List<LispVal> parts, int level, Map<String, LispSymbol> gens) {
		List<LispVal> segments = new ArrayList<>();
		List<LispVal> run = new ArrayList<>();
		for (LispVal element : parts) {
			List<LispVal> spliced = splicingOf(element, level);
			if (spliced != null) {
				if (!run.isEmpty()) {
					segments.add(ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), run));
					run = new ArrayList<>();
				}
				segments.add(ClojureSeqLowering.seqForm(ctx, ctx.lower(spliced.get(1))));
				continue;
			}
			List<LispVal> single = unquoteOf(element, level);
			run.add(single != null ? unquoted(ctx, single.get(1)) : syntaxQuoted(ctx, element, level, gens));
		}
		if (!run.isEmpty()) {
			segments.add(ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), run));
		}
		if (segments.isEmpty()) {
			return ClojureLowering.NIL_CONST;
		}
		if (segments.size() == 1) {
			return segments.get(0);
		}
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("append"), segments);
	}

	/**
	 * The {@code (unquote-splicing X)} parts, or null when the element splices nothing.
	 */
	static @Nullable List<LispVal> splicingOf(LispVal element, int level) {
		if (level != 1) {
			return null;
		}
		List<LispVal> parts = ClojureLowerUtil.items(element);
		if (parts != null && parts.size() == 2 && ClojureLowerUtil.isSymbolNamed(parts.get(0), "unquote-splicing")) {
			return parts;
		}
		return null;
	}

	/** The {@code (unquote X)} parts, or null when the element unquotes nothing. */
	static @Nullable List<LispVal> unquoteOf(LispVal element, int level) {
		if (level != 1) {
			return null;
		}
		List<LispVal> parts = ClojureLowerUtil.items(element);
		if (parts != null && parts.size() == 2 && ClojureLowerUtil.isSymbolNamed(parts.get(0), "unquote")) {
			return parts;
		}
		return null;
	}

	static LispVal syntaxQuotedVector(ClojureLowering ctx, List<LispVal> elements, int level,
			Map<String, LispSymbol> gens) {
		List<LispVal> segments = new ArrayList<>();
		List<LispVal> run = new ArrayList<>();
		boolean spliced = false;
		for (LispVal element : elements) {
			List<LispVal> splice = splicingOf(element, level);
			if (splice != null) {
				if (!run.isEmpty()) {
					segments.add(ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), run));
					run = new ArrayList<>();
				}
				segments.add(ClojureSeqLowering.seqForm(ctx, ctx.lower(splice.get(1))));
				spliced = true;
				continue;
			}
			List<LispVal> single = unquoteOf(element, level);
			run.add(single != null ? unquoted(ctx, single.get(1)) : syntaxQuoted(ctx, element, level, gens));
		}
		if (!spliced) {
			return ClojureLowerUtil.cons(ClojureLowerUtil.sym("vector"), run);
		}
		if (!run.isEmpty()) {
			segments.add(ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), run));
		}
		LispVal appended = segments.size() == 1 ? segments.get(0)
				: ClojureLowerUtil.cons(ClojureLowerUtil.sym("append"), segments);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("vector")), appended);
	}

	static LispVal syntaxQuotedMap(ClojureLowering ctx, List<LispVal> pairs, int level, Map<String, LispSymbol> gens) {
		List<LispVal> segments = new ArrayList<>();
		List<LispVal> run = new ArrayList<>();
		boolean spliced = false;
		for (LispVal element : pairs) {
			List<LispVal> splice = splicingOf(element, level);
			if (splice != null) {
				if (!run.isEmpty()) {
					segments.add(ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), run));
					run = new ArrayList<>();
				}
				// a flat sequence of alternating keys and values, like the
				// literal pairs around it
				segments.add(ClojureSeqLowering.seqForm(ctx, ctx.lower(splice.get(1))));
				spliced = true;
				continue;
			}
			List<LispVal> single = unquoteOf(element, level);
			run.add(single != null ? unquoted(ctx, single.get(1)) : syntaxQuoted(ctx, element, level, gens));
		}
		if (!spliced) {
			return ClojureCollectionLowering.mapBuild(run);
		}
		if (!run.isEmpty()) {
			segments.add(ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), run));
		}
		LispVal appended = segments.size() == 1 ? segments.get(0)
				: ClojureLowerUtil.cons(ClojureLowerUtil.sym("append"), segments);
		return ClojureCollectionLowering.tableFromPlist(appended);
	}

	static LispVal syntaxQuotedSet(ClojureLowering ctx, List<LispVal> elements, int level,
			Map<String, LispSymbol> gens) {
		boolean spliced = false;
		for (LispVal element : elements) {
			if (splicingOf(element, level) != null) {
				spliced = true;
				break;
			}
		}
		if (!spliced) {
			List<LispVal> lowered = new ArrayList<>();
			for (LispVal element : elements) {
				List<LispVal> single = unquoteOf(element, level);
				lowered.add(single != null ? unquoted(ctx, single.get(1)) : syntaxQuoted(ctx, element, level, gens));
			}
			return ClojureCollectionLowering.setBuild(ctx, lowered);
		}
		LispSymbol table = ctx.freshTemp();
		List<LispVal> inits = new ArrayList<>();
		List<LispVal> run = new ArrayList<>();
		for (LispVal element : elements) {
			List<LispVal> splice = splicingOf(element, level);
			if (splice != null) {
				if (!run.isEmpty()) {
					inits.add(setSplice(ctx, table, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), run)));
					run = new ArrayList<>();
				}
				inits.add(setSplice(ctx, table, ClojureSeqLowering.seqForm(ctx, ctx.lower(splice.get(1)))));
				continue;
			}
			List<LispVal> single = unquoteOf(element, level);
			run.add(single != null ? unquoted(ctx, single.get(1)) : syntaxQuoted(ctx, element, level, gens));
		}
		if (!run.isEmpty()) {
			inits.add(setSplice(ctx, table, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), run)));
		}
		List<LispVal> body = new ArrayList<>(inits);
		body.add(ClojureCollectionLowering.wrapSet(table));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(table, ClojureCollectionLowering.makeTable()))),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), body));
	}

	static LispVal setSplice(ClojureLowering ctx, LispSymbol table, LispVal members) {
		LispSymbol one = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"), ClojureLowerUtil.list(List.of(one, members)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), one, table), one));
	}

	/**
	 * {@code (gensym)} / {@code (gensym prefix)}: the ordinary uninterned symbol, a fresh
	 * one per evaluation -- per expansion inside a macro, per call at run time. An
	 * integer suffix spells itself ({@code (gensym 5)} is {@code #:G5}); anything else
	 * validates at run time, like the oracle.
	 */
	static LispVal gensymOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureLowerUtil.isTrue(n <= 1, "gensym takes an optional prefix");
		if (n == 0) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("gensym"));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("gensym"), ctx.lower(items.get(1)));
	}

	/** {@code macroexpand-1} / {@code macroexpand} as a value: a one-argument lambda. */
	static LispVal macroexpandValue(ClojureLowering ctx, String helper) {
		ctx.usedMacros = true;
		LispSymbol form = new LispSymbol(ClojureLowering.mangle("expand-form"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(form),
				ClojureLowerUtil.list(new LispSymbol(helper), form));
	}

	/**
	 * The macro runtime, spliced once behind the false binding when the program defines
	 * or expands macros: the table lookup over the {@code c%name%macro} globals, the
	 * demangler (mangled symbols back to readable ones for printing; everything else
	 * travels untouched) and the once/fixpoint expanders. Pure lowering over the shared
	 * primitives, so every backend runs it unchanged.
	 */
	static List<LispVal> macroRuntime(ClojureLowering ctx) {
		List<LispVal> runtime = new ArrayList<>();
		LispSymbol op = new LispSymbol("op");
		LispSymbol name = new LispSymbol("name");
		LispSymbol found = new LispSymbol("found");
		LispSymbol cell = new LispSymbol("cell");
		LispSymbol form = new LispSymbol("form");
		LispSymbol next = new LispSymbol("next");
		LispSymbol value = new LispSymbol("x");
		LispSymbol text = new LispSymbol("text");
		LispSymbol at = new LispSymbol("at");
		LispSymbol end = new LispSymbol("end");
		LispSymbol chars = new LispSymbol("chars");
		LispSymbol one = new LispSymbol("one");
		LispSymbol two = new LispSymbol("two");
		LispSymbol vec = new LispSymbol("vec");
		LispSymbol index = new LispSymbol("index");
		LispSymbol acc = new LispSymbol("acc");
		// (defun C%MACRO-FN (op) ...): the expander for a macro call's head, or nil
		LispVal isMangled = ClojureHierarchyLowering.hfn("AND",
				ClojureHierarchyLowering.hfn(">=", ClojureHierarchyLowering.hfn("LENGTH", name), new LispInteger(2)),
				ClojureHierarchyLowering.hfn("CHAR=", ClojureHierarchyLowering.hfn("CHAR", name, new LispInteger(0)),
						new LispChar('c')),
				ClojureHierarchyLowering.hfn("CHAR=", ClojureHierarchyLowering.hfn("CHAR", name, new LispInteger(1)),
						new LispChar('%')));
		LispVal tabled = ClojureHierarchyLowering.hlet(
				List.of(ClojureLowerUtil.list(found,
						ClojureHierarchyLowering.hfn("INTERN",
								ClojureHierarchyLowering.hfn("CONCATENATE", ClojureLowerUtil.quoted("string"), name,
										LispString.literal("%macro"))))),
				ClojureHierarchyLowering
					.hfn("IF", ClojureHierarchyLowering.hfn("BOUNDP", found), ClojureHierarchyLowering.hlet(
							List.of(ClojureLowerUtil.list(cell, ClojureHierarchyLowering.hfn("SYMBOL-VALUE", found))),
							ClojureHierarchyLowering.hfn("IF", ClojureHierarchyLowering.hfn("FUNCTIONP", cell), cell,
									ClojureLowering.NIL_CONST)),
							ClojureLowering.NIL_CONST));
		runtime.add(ClojureHierarchyLowering.hdefun("C%MACRO-FN", List.of(op),
				ClojureHierarchyLowering.hfn("IF", ClojureHierarchyLowering.hfn("SYMBOLP", op),
						ClojureHierarchyLowering.hlet(
								List.of(ClojureLowerUtil.list(name, ClojureHierarchyLowering.hfn("SYMBOL-NAME", op))),
								ClojureHierarchyLowering.hfn("IF", isMangled, tabled, ClojureLowering.NIL_CONST)),
						ClojureLowering.NIL_CONST)));
		// (defun C%UNMANGLE (text at end) ...): the demangled spelling as a string
		LispVal step = ClojureHierarchyLowering.hfnDef("STEP", List.of(at, chars), ClojureHierarchyLowering.hfn("IF",
				ClojureHierarchyLowering.hfn(">=", at, end),
				ClojureHierarchyLowering.hfn("APPLY",
						ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.sym("concatenate")),
						ClojureLowerUtil.quoted("string"), ClojureHierarchyLowering.hfn("REVERSE", chars)),
				ClojureHierarchyLowering.hlet(List.of(ClojureLowerUtil
					.list(one, ClojureHierarchyLowering.hfn("CHAR", text, at))), ClojureHierarchyLowering.hfn(
							"IF",
							ClojureHierarchyLowering
								.hfn("AND", ClojureHierarchyLowering.hfn("CHAR=", one, new LispChar('%')),
										ClojureHierarchyLowering.hfn("<",
												ClojureHierarchyLowering.hfn("+", at, new LispInteger(1)), end)),
							ClojureHierarchyLowering.hlet(
									List.of(ClojureLowerUtil.list(two,
											ClojureHierarchyLowering.hfn("CHAR", text,
													ClojureHierarchyLowering.hfn("+", at, new LispInteger(1))))),
									ClojureHierarchyLowering.hfn("COND", ClojureLowerUtil.list(
											ClojureHierarchyLowering.hfn("CHAR=", two, new LispChar('%')),
											ClojureHierarchyLowering.hfn("STEP",
													ClojureHierarchyLowering.hfn("+", at, new LispInteger(2)),
													ClojureHierarchyLowering.hfn("CONS", LispString.literal("%"),
															chars))),
											ClojureLowerUtil.list(
													ClojureHierarchyLowering.hfn("CHAR=", two, new LispChar('c')),
													ClojureHierarchyLowering.hfn("STEP",
															ClojureHierarchyLowering.hfn("+", at, new LispInteger(2)),
															ClojureHierarchyLowering.hfn("CONS",
																	LispString.literal(":"), chars))),
											ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureHierarchyLowering
												.hfn("STEP", ClojureHierarchyLowering.hfn("+", at, new LispInteger(1)),
														ClojureHierarchyLowering.hfn("CONS",
																ClojureHierarchyLowering.hfn("STRING", one), chars))))),
							ClojureHierarchyLowering.hfn("STEP",
									ClojureHierarchyLowering.hfn("+", at, new LispInteger(1)), ClojureHierarchyLowering
										.hfn("CONS", ClojureHierarchyLowering.hfn("STRING", one), chars))))));
		runtime.add(ClojureHierarchyLowering.hdefun("C%UNMANGLE", List.of(text, at, end), ClojureHierarchyLowering
			.hlabels(List.of(step), ClojureHierarchyLowering.hfn("STEP", at, ClojureLowering.NIL_CONST))));
		// (defun C%DEMANGLE-SYMBOL (s) ...): a mangled symbol back to its readable name,
		// uppercased like every other symbol the printer spells (case folds, print-only)
		runtime.add(ClojureHierarchyLowering.hdefun("C%DEMANGLE-SYMBOL", List.of(value), ClojureHierarchyLowering.hlet(
				List.of(ClojureLowerUtil.list(text, ClojureHierarchyLowering.hfn("SYMBOL-NAME", value))),
				ClojureHierarchyLowering.hfn("IF", ClojureHierarchyLowering.hfn("AND",
						ClojureHierarchyLowering.hfn(">=", ClojureHierarchyLowering.hfn("LENGTH", text),
								new LispInteger(2)),
						ClojureHierarchyLowering.hfn("CHAR=",
								ClojureHierarchyLowering.hfn("CHAR", text, new LispInteger(0)), new LispChar('c')),
						ClojureHierarchyLowering.hfn("CHAR=",
								ClojureHierarchyLowering.hfn("CHAR", text, new LispInteger(1)), new LispChar('%'))),
						ClojureHierarchyLowering.hfn("INTERN",
								ClojureHierarchyLowering.hfn("STRING-UPCASE", ClojureHierarchyLowering.hfn("C%UNMANGLE",
										text, new LispInteger(2), ClojureHierarchyLowering.hfn("LENGTH", text)))),
						value))));
		// (defun C%DEMANGLE-VECTOR (v) ...): the elements demangled, in a fresh vector
		LispVal walkVec = ClojureHierarchyLowering.hfnDef("WALK", List.of(index, acc),
				ClojureHierarchyLowering
					.hfn("IF", ClojureHierarchyLowering.hfn(">=", index, ClojureHierarchyLowering.hfn("LENGTH", vec)),
							ClojureHierarchyLowering.hfn("COERCE", ClojureHierarchyLowering.hfn("REVERSE",
									acc), ClojureLowerUtil.quoted("vector")),
							ClojureHierarchyLowering.hfn("WALK",
									ClojureHierarchyLowering.hfn("+", index, new LispInteger(1)),
									ClojureHierarchyLowering.hfn("CONS", ClojureHierarchyLowering.hfn("C%DEMANGLE",
											ClojureHierarchyLowering.hfn("AREF", vec, index)), acc))));
		runtime.add(ClojureHierarchyLowering.hdefun("C%DEMANGLE-VECTOR", List.of(vec),
				ClojureHierarchyLowering.hlabels(List.of(walkVec),
						ClojureHierarchyLowering.hfn("WALK", new LispInteger(0), ClojureLowering.NIL_CONST))));
		// (defun C%DEMANGLE (x) ...): lists and vectors demangled, anything else itself
		runtime.add(ClojureHierarchyLowering.hdefun("C%DEMANGLE", List.of(value), ClojureHierarchyLowering.hfn("COND",
				ClojureLowerUtil.list(ClojureHierarchyLowering.hfn("NULL", value), ClojureLowering.NIL_CONST),
				ClojureLowerUtil.list(ClojureHierarchyLowering.hfn("SYMBOLP", value),
						ClojureHierarchyLowering.hfn("C%DEMANGLE-SYMBOL", value)),
				ClojureLowerUtil.list(ClojureHierarchyLowering.hfn("CONSP", value),
						ClojureHierarchyLowering.hfn("CONS",
								ClojureHierarchyLowering.hfn("C%DEMANGLE", ClojureHierarchyLowering.hfn("CAR", value)),
								ClojureHierarchyLowering.hfn("C%DEMANGLE",
										ClojureHierarchyLowering.hfn("CDR", value)))),
				ClojureLowerUtil.list(ClojureHierarchyLowering.hfn("VECTORP", value),
						ClojureHierarchyLowering.hfn("C%DEMANGLE-VECTOR", value)),
				ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, value))));
		// (defun C%MACROEXPAND-1 (form) ...): one expansion, demangled for printing
		runtime
			.add(ClojureHierarchyLowering.hdefun(MACROEXPAND_1, List.of(form), ClojureHierarchyLowering.hlet(
					List.of(ClojureLowerUtil.list(next,
							ClojureHierarchyLowering.hfn("IF", ClojureHierarchyLowering.hfn("CONSP", form),
									ClojureHierarchyLowering.hfn("C%MACRO-FN",
											ClojureHierarchyLowering.hfn("CAR", form)),
									ClojureLowering.NIL_CONST))),
					ClojureHierarchyLowering.hfn("C%DEMANGLE", ClojureHierarchyLowering.hfn("IF", next,
							ClojureHierarchyLowering.hfn("FUNCALL", next, ClojureHierarchyLowering.hfn("CDR", form)),
							form)))));
		// (defun C%MACROEXPAND (form) ...): to the fixpoint, demangled once at the end
		LispVal walkExpand = ClojureHierarchyLowering
			.hfnDef("WALK", List.of(form), ClojureHierarchyLowering.hlet(
					List.of(ClojureLowerUtil.list(next,
							ClojureHierarchyLowering.hfn("IF", ClojureHierarchyLowering.hfn("CONSP", form),
									ClojureHierarchyLowering.hfn("C%MACRO-FN",
											ClojureHierarchyLowering.hfn("CAR", form)),
									ClojureLowering.NIL_CONST))),
					ClojureHierarchyLowering.hfn("IF", next, ClojureHierarchyLowering.hfn("WALK",
							ClojureHierarchyLowering.hfn("FUNCALL", next, ClojureHierarchyLowering.hfn("CDR", form))),
							form)));
		runtime.add(ClojureHierarchyLowering.hdefun(MACROEXPAND, List.of(form),
				ClojureHierarchyLowering.hlabels(List.of(walkExpand),
						ClojureHierarchyLowering.hfn("C%DEMANGLE", ClojureHierarchyLowering.hfn("WALK", form)))));
		return runtime;
	}

}
