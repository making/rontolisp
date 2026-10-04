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
	 * The oracle's special forms ({@code Compiler.specials}): a call site never reaches a
	 * macro of one of these names, so a {@code defmacro} of one is refused by name (the
	 * oracle accepts it and ignores it).
	 */
	static final Set<String> SPECIAL_FORMS = Set.of("def", "loop*", "recur", "if", "case*", "let*", "letfn*", "do",
			"fn*", "quote", "var", "import*", ".", "set!", "deftype*", "reify*", "try", "throw", "monitor-enter",
			"monitor-exit", "catch", "finally", "new", "&");

	/**
	 * The heads the reader itself spells ({@code `x}, {@code ~x}, {@code ~@x},
	 * {@code @x}) plus the two the pre-scan reads a namespace from: a macro of one would
	 * capture the reader's own forms (the oracle reads {@code @x} as
	 * {@code clojure.core/deref}, which no program macro shadows), so a {@code defmacro}
	 * of one is refused by name. {@code #(...)} reads as {@code fn*}, a special form, so
	 * {@code fn} is no reader head: a {@code fn} macro captures {@code fn} call sites
	 * only. {@code ^m x} reads as {@code %with-meta}, reserved by its {@code %}, so a
	 * {@code with-meta} macro shadows the call only.
	 */
	static final Set<String> READER_HEADS = Set.of("syntax-quote", "unquote", "unquote-splicing", "deref", "ns",
			"in-ns");

	/**
	 * The names a syntax-quote leaves bare: the oracle's special forms, measured on
	 * {@code clj} 1.12.6.1673. One less than {@link #SPECIAL_FORMS}: {@code import*} is
	 * no special form there -- the oracle spells {@code ns/import*}.
	 */
	static final Set<String> SYNTAX_QUOTE_BARE = Set.of("def", "loop*", "recur", "if", "case*", "let*", "letfn*", "do",
			"fn*", "quote", "var", ".", "set!", "deftype*", "reify*", "try", "throw", "monitor-enter", "monitor-exit",
			"catch", "finally", "new", "&");

	/**
	 * Whether a head never reaches the macro table: a special form or a reader head. Any
	 * other name -- a lowering row like {@code with-out-str} or {@code when} included --
	 * expands through a program macro of that name once the macro is defined.
	 */
	static boolean isReservedHead(String name) {
		return SPECIAL_FORMS.contains(name) || READER_HEADS.contains(name) || name.startsWith("%");
	}

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
	 * call above the definition names the missing expander instead of an unknown name,
	 * unless the name is a {@code clojure.core} one, whose core meaning holds there.
	 */
	static List<LispVal> defmacroForms(ClojureLowering ctx, LispVal form, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "defmacro needs a name, a parameter vector and a body");
		String name = ClojureLowerUtil.plainName(items.get(1), "defmacro");
		ClojureLowerUtil.isTrue(!SPECIAL_FORMS.contains(name), name + " cannot name a macro: it names a special form");
		ClojureLowerUtil.isTrue(!isReservedHead(name),
				name + " cannot name a macro: the reader spells its own forms with it");
		ClojureLowerUtil.isTrue(!name.startsWith(".") && !name.endsWith(".") && name.indexOf('/') < 0,
				name + " cannot name a macro: it names an interop or qualified form");
		int at = 2;
		LispString doc = null;
		if (items.size() > at && items.get(at) instanceof LispString string) {
			doc = string; // the docstring
			at++;
		}
		LispVal attrMap = null;
		if (items.size() > at) {
			List<LispVal> attr = ClojureLowerUtil.items(items.get(at));
			if (attr != null && !attr.isEmpty() && ClojureLowerUtil.isSymbolNamed(attr.get(0), "%hash-map")) {
				attrMap = items.get(at); // the attr map
				at++;
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
		String key = ctx.intern(name, ClojureLowerUtil.nameIsPrivate(items.get(1)));
		LispSymbol table = macroTable(key);
		ctx.macros.put(key, expander);
		ctx.globals.put(key, ClojureLowering.Kind.MACRO);
		ctx.usedMacros = true;
		List<LispVal> metaStore = ClojureVarLowering.record(ctx, key, form, items.get(1),
				ClojureBindingLowering.arglistsOf(items.subList(at, items.size())), doc, attrMap, false, true);
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
		List<LispVal> forms = new ArrayList<>();
		forms.add(setq);
		forms.addAll(metaStore);
		forms.add(ClojureLowering.NIL_CONST);
		return List.of(ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), forms));
	}

	/**
	 * The runtime table global holding a macro's expander: the var's symbol plus
	 * {@code %macro}.
	 */
	static LispSymbol macroTable(String key) {
		return new LispSymbol(ClojureLowering.varSym(key).name() + "%macro");
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
	 * A call whose head names a macro -- the current namespace's own, a referred one, or
	 * one reached through an alias or its namespace's full name: the expansion, lowered
	 * in place of the call. A local binding shadows the macro (a parameter, a {@code let}
	 * name); a macro name without its expander is a call above its definition. Null when
	 * the head names no macro.
	 */
	static @Nullable LispVal macroCall(ClojureLowering ctx, String name, List<LispVal> items) {
		String key = macroKey(ctx, name);
		if (key == null) {
			return null;
		}
		LispVal expander = ctx.macros.get(key);
		if (expander == null) {
			throw new LispReadException("macro `" + name + "` used before its definition");
		}
		return expandMacro(ctx, name, expander, items);
	}

	/**
	 * The var key of the program macro a head names at the current point, or null: a
	 * local binding shadows the macro, and a name no macro owns has none.
	 */
	static @Nullable String macroKey(ClojureLowering ctx, String name) {
		if (ctx.isLocal(name)) {
			return null; // a local binding shadows the macro
		}
		String key = ctx.resolveVar(name);
		return key != null && ctx.globals.get(key) == ClojureLowering.Kind.MACRO ? key : null;
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
		// the body reads the load's specials where the call expands, like the oracle's
		// macroexpansion inside the load
		LispVal invocation = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureCoreSpecials.NS, ClojureCoreSpecials.namespaceObject(ctx.currentNs)),
				ClojureLowerUtil.list(ClojureCoreSpecials.FILE, LispString.literal(ctx.loadingFile)),
				ClojureLowerUtil.list(ClojureCoreSpecials.SOURCE_PATH, LispString.literal(ctx.loadingSourcePath))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("funcall"), expander,
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), quoted)));
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
	 * keyword and set wrappers answer their datum, a pattern its regex literal, vectors
	 * and tables their literals, and a gensym ({@code #:}-spelled, uninterned) travels as
	 * itself so the {@code #:} bypass lowers it back to the same symbol.
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
				if (tag.name().equals(":C%SORTED")) {
					return decodeSorted(ctx, cons);
				}
				if (tag.name().equals(":C%PATTERN")) {
					return decodePattern(cons);
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

	/**
	 * A sorted collection answer back to its literal: a map or set literal of its entries
	 * or members in order, which builds an unsorted one, like the oracle's compiler (a
	 * returned collection is a literal of its kind, so {@code sorted?} of it is false).
	 */
	static LispVal decodeSorted(ClojureLowering ctx, LispCons wrapper) {
		List<LispVal> parts = ClojureLowerUtil.items(wrapper);
		if (parts != null && parts.size() == 4 && parts.get(3) instanceof LispArray items
				&& items.dimensions().length == 1) {
			boolean set = !(parts.get(1) instanceof LispNil);
			List<LispVal> elements = new ArrayList<>();
			elements.add(new LispSymbol(set ? "%hash-set" : "%hash-map"));
			for (int i = 0; i < items.totalSize(); i++) {
				LispVal item = items.readFlat(i);
				if (set) {
					elements.add(decodeDatum(ctx, item));
				}
				else if (item instanceof LispArray entry && entry.totalSize() == 2) {
					elements.add(decodeDatum(ctx, entry.readFlat(0)));
					elements.add(decodeDatum(ctx, entry.readFlat(1)));
				}
				else {
					throw new LispReadException("an unreadable value: " + wrapper.print());
				}
			}
			return ClojureLowerUtil.list(elements);
		}
		throw new LispReadException("an unreadable value: " + wrapper.print());
	}

	/**
	 * A pattern answer back to the regex literal of its source, which compiles to an
	 * equivalent pattern (the oracle's expansion holds the one Pattern object; this one
	 * compiles its own, so only identity tells them apart).
	 */
	static LispVal decodePattern(LispCons wrapper) {
		List<LispVal> parts = ClojureLowerUtil.items(wrapper);
		if (parts != null && parts.size() == 5 && parts.get(2) instanceof LispString source) {
			return ClojureLowerUtil.list(ClojureReader.REGEX, source);
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
	 * the mangled namespace. A symbol naming a var the defining namespace sees qualifies
	 * with its namespace (like the oracle's read-time resolution, so the expansion
	 * reaches it from any namespace); a special form stays bare, while every other symbol
	 * qualifies even when it resolves to nothing: a core name the namespace sees spells
	 * {@code clojure.core/name}, any other unresolved spelling the defining namespace, an
	 * alias head its namespace, a class head its fully qualified name. {@code ~} lowers
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
		// a var of a project namespace qualifies with its namespace, like the
		// oracle's read-time resolution, so the expansion reaches it from any
		// namespace it expands in; a special form stays bare, while every other
		// symbol qualifies even unresolved: a core name the namespace sees
		// as clojure.core/name (a pending program macro below still shadows:
		// shadowedCoreName above), any other unresolved spelling with the defining
		// namespace, an alias head with its namespace, a class head with its fully
		// qualified name -- only a qualified head naming neither stays as written
		if (SYNTAX_QUOTE_BARE.contains(name)) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.dataSym(name));
		}
		if (ctx.shadowedCoreName(name)) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"),
					ClojureLowerUtil.dataSym(ClojureCoreNames.PREFIX + name));
		}
		String key = ctx.lookupVar(name);
		if (key != null) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.dataSym(key));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"),
				ClojureLowerUtil.dataSym(unresolvedQualification(ctx, name)));
	}

	/**
	 * Where an unresolved syntax-quoted symbol qualifies, like the oracle (measured on
	 * {@code clj} 1.12.6.1673): unqualified, a core name the namespace sees
	 * ({@code (:refer-clojure ...)} may hide it) spells {@code clojure.core/name}, a
	 * class spelling its fully qualified name (an import, then {@code java.lang}, then a
	 * dotted spelling as written -- the oracle refuses to {@code def} over one, so the
	 * class wins) and anything else the defining namespace; qualified, an alias head
	 * spells its namespace (no var check, like the oracle) and a class head its fully
	 * qualified name (dotted as written, imported, or {@code java.lang}); a qualified
	 * head naming neither stays as written.
	 */
	static String unresolvedQualification(ClojureLowering ctx, String name) {
		int slash = ClojureLowering.qualifierSlash(name);
		if (slash < 0) {
			if (name.indexOf('/') >= 0) {
				return name; // several slashes: no one namespace to qualify with
			}
			if (ClojureCoreNames.contains(name) && ClojureNamespaceLowering.coreAllowed(ctx, name)) {
				return ClojureCoreNames.PREFIX + name;
			}
			// a class spelling is already fully qualified (measured on the
			// oracle: `java.io.StringWriter reads as written, `String as
			// java.lang.String, an imported name through its import -- and the
			// oracle refuses to def over one, so the class wins over any var)
			String imported = ctx.ns().classNames.get(name);
			if (imported != null) {
				return imported;
			}
			if (ClojureNamespaceLowering.JAVA_LANG.contains(name)) {
				return "java.lang." + name;
			}
			if (name.indexOf('.') >= 0) {
				return name;
			}
			return ClojureLowering.varKey(ctx.currentNs, name);
		}
		String head = name.substring(0, slash);
		String tail = name.substring(slash + 1);
		String aliased = ctx.ns().aliases.get(head);
		if (aliased != null) {
			return aliased + "/" + tail;
		}
		if (head.indexOf('.') >= 0) {
			return name; // already fully qualified
		}
		String imported = ctx.ns().classNames.get(head);
		if (imported != null) {
			return imported + "/" + tail;
		}
		if (ClojureNamespaceLowering.JAVA_LANG.contains(head)) {
			return "java.lang." + name;
		}
		return name;
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
				segments.add(ClojureSeqLowering.seqAllForm(ctx, ctx.lower(spliced.get(1))));
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
				segments.add(ClojureSeqLowering.seqAllForm(ctx, ctx.lower(splice.get(1))));
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
				segments.add(ClojureSeqLowering.seqAllForm(ctx, ctx.lower(splice.get(1))));
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
		return ClojureCollectionLowering.grownTable(ClojureLowering.NIL_CONST, appended);
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
				inits.add(setSplice(ctx, table, ClojureSeqLowering.seqAllForm(ctx, ctx.lower(splice.get(1)))));
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
				ClojureCollectionLowering.setPut(table, one));
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

	/**
	 * {@code macroexpand-1} / {@code macroexpand} as a value: a one-argument lambda over
	 * the call site's macro scope.
	 */
	static LispVal macroexpandValue(ClojureLowering ctx, String helper) {
		ctx.usedMacros = true;
		LispSymbol form = new LispSymbol(ClojureLowering.mangle("expand-form"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(form),
				ClojureLowerUtil.list(new LispSymbol(helper), form, macroScope(ctx)));
	}

	/**
	 * {@code (macroexpand-1 form)} / {@code (macroexpand form)}: the runtime expander
	 * over the form and the call site's macro scope.
	 */
	static LispVal macroexpandCall(ClojureLowering ctx, String helper, LispVal form) {
		ctx.usedMacros = true;
		return ClojureLowerUtil.list(new LispSymbol(helper), form, macroScope(ctx));
	}

	/**
	 * The macros a run-time expansion at this call site reaches by a spelling the table
	 * lookup alone cannot -- the oracle resolves the head in the current namespace: a
	 * bare name of the current namespace's own macro (outside {@code user}, whose table
	 * spells the bare name already), a bare name a refer brought in, and an
	 * alias-qualified name. A quoted alist of data symbol to table global, or nil when
	 * there is none (a fully qualified name, and every {@code user} macro, need no
	 * entry).
	 */
	static LispVal macroScope(ClojureLowering ctx) {
		// sorted, so the same program spells the same alist on every run
		Map<String, LispSymbol> spelled = new java.util.TreeMap<>();
		ClojureNsState here = ctx.ns();
		for (Map.Entry<String, ClojureLowering.Kind> global : ctx.globals.entrySet()) {
			if (global.getValue() != ClojureLowering.Kind.MACRO) {
				continue;
			}
			String key = global.getKey();
			int slash = key.indexOf('/');
			String ns = key.substring(0, slash);
			String macro = key.substring(slash + 1);
			if (ns.equals(ctx.currentNs) && !ns.equals("user")) {
				spelled.putIfAbsent(macro, macroTable(key));
			}
			for (Map.Entry<String, String> alias : here.aliases.entrySet()) {
				if (alias.getValue().equals(ns) && !alias.getKey().equals(ns)) {
					spelled.putIfAbsent(alias.getKey() + "/" + macro, macroTable(key));
				}
			}
		}
		for (Map.Entry<String, ClojureLowering.VarRef> referred : here.refers.entrySet()) {
			String key = ClojureLowering.varKey(referred.getValue().ns(), referred.getValue().var());
			if (ctx.globals.get(key) == ClojureLowering.Kind.MACRO
					&& !ctx.globals.containsKey(ClojureLowering.varKey(ctx.currentNs, referred.getKey()))) {
				spelled.putIfAbsent(referred.getKey(), macroTable(key));
			}
		}
		if (spelled.isEmpty()) {
			return ClojureLowering.NIL_CONST;
		}
		LispVal alist = LispNil.INSTANCE;
		List<Map.Entry<String, LispSymbol>> entries = new ArrayList<>(spelled.entrySet());
		for (int i = entries.size() - 1; i >= 0; i--) {
			alist = new LispCons(
					new LispCons(ClojureLowerUtil.idSym(entries.get(i).getKey()), entries.get(i).getValue()), alist);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), alist);
	}

	/**
	 * The macro runtime, spliced once behind the false binding when the program defines
	 * or expands macros: the table lookup over the {@code c%name%macro} globals and the
	 * once/fixpoint expanders. The expanders answer the mangled data itself, so {@code =}
	 * against a quoted form holds and the Clojure printer (which demangles {@code c%}
	 * symbols) spells the oracle's lowercase. Pure lowering over the shared primitives,
	 * so every backend runs it unchanged.
	 */
	static List<LispVal> macroRuntime(ClojureLowering ctx) {
		List<LispVal> runtime = new ArrayList<>();
		LispSymbol op = new LispSymbol("op");
		LispSymbol name = new LispSymbol("name");
		LispSymbol found = new LispSymbol("found");
		LispSymbol cell = new LispSymbol("cell");
		LispSymbol form = new LispSymbol("form");
		LispSymbol next = new LispSymbol("next");
		LispSymbol scope = new LispSymbol("scope");
		LispSymbol hit = new LispSymbol("hit");
		// (defun C%MACRO-FN (op scope) ...): the expander for a macro call's head, or
		// nil -- the call site's scope first (a bare or alias-qualified spelling the
		// table cannot spell), else the table global the symbol spells, user's
		// qualified spelling (a syntax-quoted user macro) read as the bare one
		LispVal isMangled = ClojureHierarchyLowering.hfn("AND",
				ClojureHierarchyLowering.hfn(">=", ClojureHierarchyLowering.hfn("LENGTH", name), new LispInteger(2)),
				ClojureHierarchyLowering.hfn("CHAR=", ClojureHierarchyLowering.hfn("CHAR", name, new LispInteger(0)),
						new LispChar('c')),
				ClojureHierarchyLowering.hfn("CHAR=", ClojureHierarchyLowering.hfn("CHAR", name, new LispInteger(1)),
						new LispChar('%')));
		String userPrefix = ClojureLowering.PREFIX + "user/";
		LispVal unqualified = ClojureHierarchyLowering.hfn("IF",
				ClojureHierarchyLowering.hfn("AND",
						ClojureHierarchyLowering.hfn(">=", ClojureHierarchyLowering.hfn("LENGTH", name),
								new LispInteger(userPrefix.length())),
						ClojureHierarchyLowering.hfn("STRING=",
								ClojureHierarchyLowering.hfn("SUBSEQ", name, new LispInteger(0),
										new LispInteger(userPrefix.length())),
								LispString.literal(userPrefix))),
				ClojureHierarchyLowering.hfn("CONCATENATE", ClojureLowerUtil.quoted("string"),
						LispString.literal(ClojureLowering.PREFIX),
						ClojureHierarchyLowering.hfn("SUBSEQ", name, new LispInteger(userPrefix.length()))),
				name);
		LispVal tableOf = ClojureHierarchyLowering.hfn("IF", hit, ClojureHierarchyLowering.hfn("CDR", hit),
				ClojureHierarchyLowering.hfn("IF", isMangled,
						ClojureHierarchyLowering.hfn("INTERN", ClojureHierarchyLowering.hfn("CONCATENATE",
								ClojureLowerUtil.quoted("string"), unqualified, LispString.literal("%macro"))),
						ClojureLowering.NIL_CONST));
		LispVal tabled = ClojureHierarchyLowering
			.hlet(List.of(ClojureLowerUtil.list(found, tableOf)), ClojureHierarchyLowering.hfn("IF",
					ClojureHierarchyLowering.hfn("AND", found, ClojureHierarchyLowering.hfn("BOUNDP", found)),
					ClojureHierarchyLowering.hlet(
							List.of(ClojureLowerUtil.list(cell, ClojureHierarchyLowering.hfn("SYMBOL-VALUE", found))),
							ClojureHierarchyLowering.hfn("IF", ClojureHierarchyLowering.hfn("FUNCTIONP", cell), cell,
									ClojureLowering.NIL_CONST)),
					ClojureLowering.NIL_CONST));
		runtime.add(ClojureHierarchyLowering.hdefun("C%MACRO-FN", List.of(op, scope),
				ClojureHierarchyLowering.hfn("IF", ClojureHierarchyLowering.hfn("SYMBOLP", op),
						ClojureHierarchyLowering.hlet(
								List.of(ClojureLowerUtil.list(hit, ClojureHierarchyLowering.hfn("ASSOC", op, scope)),
										ClojureLowerUtil.list(name, ClojureHierarchyLowering.hfn("SYMBOL-NAME", op))),
								tabled),
						ClojureLowering.NIL_CONST)));
		// (defun C%MACROEXPAND-1 (form scope) ...): one expansion, answered as the
		// mangled data itself; a non-macro head answers the form itself
		runtime.add(ClojureHierarchyLowering.hdefun(MACROEXPAND_1, List.of(form, scope), ClojureHierarchyLowering.hlet(
				List.of(ClojureLowerUtil.list(next,
						ClojureHierarchyLowering.hfn("IF", ClojureHierarchyLowering.hfn("CONSP", form),
								ClojureHierarchyLowering.hfn("C%MACRO-FN", ClojureHierarchyLowering.hfn("CAR", form),
										scope),
								ClojureLowering.NIL_CONST))),
				ClojureHierarchyLowering.hfn("IF", next,
						ClojureHierarchyLowering.hfn("FUNCALL", next, ClojureHierarchyLowering.hfn("CDR", form)),
						form))));
		// (defun C%MACROEXPAND (form scope) ...): to the fixpoint, the mangled data
		// itself
		LispVal walkExpand = ClojureHierarchyLowering.hfnDef("WALK", List.of(form),
				ClojureHierarchyLowering.hlet(List.of(ClojureLowerUtil.list(next,
						ClojureHierarchyLowering.hfn("IF", ClojureHierarchyLowering.hfn("CONSP", form),
								ClojureHierarchyLowering.hfn("C%MACRO-FN", ClojureHierarchyLowering.hfn("CAR", form),
										scope),
								ClojureLowering.NIL_CONST))),
						ClojureHierarchyLowering.hfn("IF", next,
								ClojureHierarchyLowering.hfn("WALK", ClojureHierarchyLowering.hfn("FUNCALL", next,
										ClojureHierarchyLowering.hfn("CDR", form))),
								form)));
		runtime.add(ClojureHierarchyLowering.hdefun(MACROEXPAND, List.of(form, scope),
				ClojureHierarchyLowering.hlabels(List.of(walkExpand), ClojureHierarchyLowering.hfn("WALK", form))));
		return runtime;
	}

}
