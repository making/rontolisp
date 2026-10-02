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
 * Dispatch forms of the Clojure lowering: multimethods, hierarchies, protocols and
 * records.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureDispatchLowering {

	private ClojureDispatchLowering() {
	}

	/**
	 * {@code instance?}: the class name mapped onto the predicate the backends share.
	 * Only the core classes lower -- a host width we do not have (or any other class) is
	 * a named refusal instead of a wrong answer.
	 */
	static LispVal instanceOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "instance? takes a class and a value");
		if (!(items.get(1) instanceof LispSymbol cls)) {
			throw new LispReadException("instance? takes a class name, not " + items.get(1).print());
		}
		String name = cls.name();
		String simple = name.lastIndexOf('.') >= 0 ? name.substring(name.lastIndexOf('.') + 1) : name;
		LispVal lowered = ctx.lower(items.get(2));
		ClojureLowering.TypeDef known = ctx.types.get(name);
		if (known == null && name.indexOf('.') < 0) {
			known = ctx.types.get(simple);
		}
		if (known != null) {
			// a record or deftype name tests the dispatch tag, like a class
			ctx.usedProtocols = true;
			return ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("equal"),
					ClojureLowerUtil.list(new LispSymbol(PROTOCOL_TAG), lowered), typeTagForm(known.tagSpelling())));
		}
		LispVal raw = switch (simple) {
			case "String", "CharSequence" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), lowered);
			case "Character" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), lowered);
			case "Boolean" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), lowered, ClojureLowering.TRUE_CONST),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), lowered, ctx.falseVariable));
			case "Number" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), lowered);
			case "Long" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("integerp"), lowered);
			case "Double" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("floatp"), lowered);
			case "Object" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), lowered));
			case "Keyword" -> ClojureSeqLowering.keywordTest(lowered);
			case "Symbol" -> ClojureCollectionLowering.symbolRaw(ctx, lowered);
			default -> throw new LispReadException("instance? needs a core class, not " + name);
		};
		return ctx.booleanAnswer(raw);
	}

	/**
	 * {@code class}: the value's kind as a keyword. The oracle answers host classes,
	 * which no wasm backend has -- the keyword names the kind instead, on every backend
	 * alike. Inside a {@code defmulti} dispatch function (see
	 * {@link #defmultiForms(List)}) a nil answers nil itself instead, so the dispatcher's
	 * null test maps it onto the nil method's marker while an explicit {@code :nil}
	 * keyword keeps its row, like the oracle.
	 */
	static LispVal classForm(ClojureLowering ctx, LispVal lowered) {
		LispSymbol one = ctx.freshTemp();
		List<LispVal> branches = new ArrayList<>();
		// a record or deftype answers its tag keyword (the oracle answers a host
		// class, which no wasm backend has); a reify answers a constant keyword
		branches.add(ClojureLowerUtil.list(isRecordForm(one), typedTagOf(one)));
		branches.add(ClojureLowerUtil.list(isDeftypeForm(one), typedTagOf(one)));
		branches.add(ClojureLowerUtil.list(isReifyForm(one), ClojureCollectionLowering.keywordForm("reify")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), one),
				ctx.inDispatchFn ? ClojureLowering.NIL_CONST : ClojureCollectionLowering.keywordForm("nil")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), one, ctx.falseVariable),
				ClojureCollectionLowering.keywordForm("boolean")));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), one, ClojureLowering.TRUE_CONST),
				ClojureCollectionLowering.keywordForm("boolean")));
		branches.add(ClojureLowerUtil.list(ClojureSeqLowering.keywordTest(one),
				ClojureCollectionLowering.keywordForm("keyword")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("symbolp"), one),
				ClojureCollectionLowering.keywordForm("symbol")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), one),
				ClojureCollectionLowering.keywordForm("char")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), one),
				ClojureCollectionLowering.keywordForm("string")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), one),
				ClojureCollectionLowering.keywordForm("number")));
		branches.add(ClojureLowerUtil.list(ClojureCollectionLowering.isSetForm(one),
				ClojureCollectionLowering.keywordForm("set")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), one),
				ClojureCollectionLowering.keywordForm("map")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), one),
				ClojureCollectionLowering.keywordForm("vector")));
		branches.add(ClojureLowerUtil.list(ClojureStringLowering.isPatternForm(one),
				ClojureCollectionLowering.keywordForm("pattern")));
		branches.add(ClojureLowerUtil.list(ClojureStringLowering.isMatcherForm(one),
				ClojureCollectionLowering.keywordForm("matcher")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), one),
				ClojureCollectionLowering.keywordForm("list")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("functionp"), one),
				ClojureCollectionLowering.keywordForm("function")));
		branches.add(ClojureLowerUtil.list(ClojureStateLowering.isAtomForm(one),
				ClojureCollectionLowering.keywordForm("atom")));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, ClojureLowerUtil
			.list(ClojureLowerUtil.sym("error"), LispString.literal("class needs a value of a known kind"))));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(one, lowered))),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches));
	}

	/** {@code class} as a value: a one-argument lambda over the same read. */
	static LispVal classValue(ClojureLowering ctx) {
		LispSymbol one = new LispSymbol(ClojureLowering.mangle("class-one"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one), classForm(ctx, one));
	}

	/**
	 * {@code int}/{@code long} over an already-lowered value: a character reads back
	 * through {@code char-code} (round-tripping {@code char}), anything else truncates,
	 * like the oracle.
	 */
	static LispVal intForm(ClojureLowering ctx, LispVal lowered) {
		LispSymbol one = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(one, lowered))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), one),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("char-code"), one),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("truncate"), one)));
	}

	/** {@code int}/{@code long} as a value: truncation, like the call. */
	static LispVal intValue(ClojureLowering ctx) {
		LispSymbol one = new LispSymbol(ClojureLowering.mangle("int-one"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one), intForm(ctx, one));
	}

	/** {@code unchecked-add} as a value: addition without the overflow check. */
	static LispVal uncheckedAddValue(ClojureLowering ctx) {
		LispSymbol first = new LispSymbol(ClojureLowering.mangle("unchecked-a"));
		LispSymbol second = new LispSymbol(ClojureLowering.mangle("unchecked-b"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(List.of(first, second)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), first, second));
	}

	// b15 IO entry points: spit/slurp/line-seq over the eval IO layer (b22 adds the
	// clojure.java.io/reader constructor and the line-seq reader arity)

	/**
	 * The host spellings a {@code defmethod} dispatch value may name, to the
	 * {@code class}-keyword the dispatcher actually produces for them. The table mirrors
	 * {@code class} (and {@code extend-protocol} targets): every numeric spelling merges
	 * into {@code :number} (the oracle tells {@code Long} from {@code Double}, which no
	 * wasm backend has -- the documented merge), every collection spelling into its kind.
	 */
	static final Map<String, String> DISPATCH_CLASS_KEYWORDS = Map.ofEntries(Map.entry("String", "string"),
			Map.entry("CharSequence", "string"), Map.entry("Number", "number"), Map.entry("Long", "number"),
			Map.entry("Double", "number"), Map.entry("Integer", "number"), Map.entry("Float", "number"),
			Map.entry("Short", "number"), Map.entry("Byte", "number"), Map.entry("Boolean", "boolean"),
			Map.entry("Keyword", "keyword"), Map.entry("Symbol", "symbol"), Map.entry("Character", "char"),
			Map.entry("Char", "char"), Map.entry("Map", "map"), Map.entry("IPersistentMap", "map"),
			Map.entry("Vector", "vector"), Map.entry("IPersistentVector", "vector"), Map.entry("Set", "set"),
			Map.entry("IPersistentSet", "set"), Map.entry("List", "list"), Map.entry("Seq", "list"),
			Map.entry("Sequential", "list"), Map.entry("Collection", "list"), Map.entry("IPersistentList", "list"),
			Map.entry("IPersistentCollection", "list"), Map.entry("Fn", "function"), Map.entry("IFn", "function"),
			Map.entry("Function", "function"), Map.entry("Atom", "atom"));

	/**
	 * Whether the dispatch datum is the nil spelling: the symbol the reader answers for
	 * {@code nil}, or the empty list (which is nil too). A nil method stores under the
	 * {@code (:C%NIL)} marker (see {@link #nilMarkerForm}), so the corpus's both shapes
	 * ({@code my-print} over {@code class}, {@code my-class} over {@code identity}) share
	 * the one definition while a literal {@code :nil} keyword keeps its own row.
	 */
	static boolean isNilDatum(LispVal datum) {
		return datum instanceof LispNil || (datum instanceof LispSymbol s && s.name().equals("nil"));
	}

	/**
	 * Whether the name spells {@code Object}: dotted, imported, or {@code java.lang}, a
	 * record or deftype of that name aside (which keeps its tag). An {@code Object}
	 * method matches every dispatch value, like the oracle's, so it lives in the
	 * multimethod's {@code %object} global beside its table row: the dispatcher tries it
	 * past the hierarchy search but ahead of the default, which it always beats.
	 */
	static boolean isObjectClassName(ClojureLowering ctx, String name) {
		return !ctx.types.containsKey(name)
				&& ClojureNamespaceLowering.resolveClass(ctx, name).equals("java.lang.Object");
	}

	/**
	 * A class spelling to the keyword the {@code class} dispatcher produces for it, or
	 * null when the name is no class spelling at all (so the caller lowers it as usual --
	 * a var holding the dispatch value, like the oracle's evaluated position). Record and
	 * deftype names answer their tags; dotted, imported and {@code java.lang} spellings
	 * resolve through {@link #resolveClass} first, so {@code java.util.Map} and
	 * {@code clojure.lang.IPersistentVector} map like their simple names. A capitalized
	 * name that maps to nothing (an {@code Instant}, a {@code Date}, ...) is the
	 * {@code extend-protocol} row's named refusal.
	 */
	static @Nullable LispVal dispatchClassKey(ClojureLowering ctx, String name) {
		if (ctx.types.containsKey(name)) {
			return typeTagForm(name);
		}
		if (isObjectClassName(ctx, name)) {
			return ClojureCollectionLowering.keywordForm("object");
		}
		boolean classlike = ctx.classNames.containsKey(name) || ClojureNamespaceLowering.JAVA_LANG.contains(name)
				|| name.indexOf('.') >= 0 || (!name.isEmpty() && Character.isUpperCase(name.charAt(0)));
		if (!classlike) {
			return null;
		}
		String fqn = ClojureNamespaceLowering.resolveClass(ctx, name);
		String kind = DISPATCH_CLASS_KEYWORDS.get(fqn.substring(fqn.lastIndexOf('.') + 1));
		if (kind == null) {
			throw new LispReadException("defmethod needs a core class, not " + name);
		}
		return ClojureCollectionLowering.keywordForm(kind);
	}

	/**
	 * A {@code defmethod} (or {@code remove-method}, {@code get-method},
	 * {@code prefer-method}) dispatch value lowered to its table key: {@code nil} onto
	 * the {@code (:C%NIL)} marker (the dispatcher maps a true nil onto it, so no table
	 * ever keys on nil, and a literal {@code :nil} keyword keeps its keyword row), class
	 * spellings onto the keyword the {@code class} dispatcher produces,
	 * {@code ::}-keywords resolved like anywhere else, and literal vectors element by
	 * element (the corpus's {@code [Number]} and {@code [Map Number]} pairs, whose
	 * element-wise derivation the hierarchy search already runs). Anything else lowers as
	 * usual, so plain keywords and values keep their exact shapes.
	 */
	static LispVal dispatchKeyForm(ClojureLowering ctx, LispVal datum) {
		if (isNilDatum(datum)) {
			return ClojureCollectionLowering.nilMarkerForm();
		}
		return dispatchElementForm(ctx, datum);
	}

	/**
	 * A literal vector's element lowered: the table-key shape except that a nil element
	 * stays the {@code :nil} keyword, exactly as before -- a class-mapped dispatch vector
	 * spells its nil element the same way, so a {@code [nil]} row keeps answering it,
	 * while a runtime vector holding a true nil still misses it, like before. Nested
	 * vectors recurse here, never onto the marker.
	 */
	static LispVal dispatchElementForm(ClojureLowering ctx, LispVal datum) {
		if (isNilDatum(datum)) {
			return ClojureCollectionLowering.keywordForm("nil");
		}
		if (datum instanceof LispSymbol s) {
			String name = s.name();
			if (name.startsWith(":")) {
				return ClojureCollectionLowering
					.keywordForm(ClojureCollectionLowering.resolveKeywordSpelling(ctx, name));
			}
			LispVal cls = dispatchClassKey(ctx, name);
			if (cls != null) {
				return cls;
			}
			return ctx.lower(datum);
		}
		List<LispVal> parts = ClojureLowerUtil.items(datum);
		if (parts != null && !parts.isEmpty() && parts.get(0) == ClojureReader.VECTOR) {
			List<LispVal> out = new ArrayList<>();
			out.add(ClojureLowerUtil.sym("vector"));
			for (int i = 1; i < parts.size(); i++) {
				out.add(dispatchElementForm(ctx, parts.get(i)));
			}
			return ClojureLowerUtil.list(out);
		}
		return ctx.lower(datum);
	}

	/**
	 * Whether the datum holds a {@code class} call: any list headed by the symbol
	 * {@code class}. Quoted data is skipped (a call there never evaluates); anything else
	 * over-approximates, which is harmless -- a definition whose only {@code class}
	 * spellings never evaluate re-lowers identically, so recording it changes nothing.
	 */
	static boolean containsClassCall(LispVal datum) {
		List<LispVal> parts = ClojureLowerUtil.items(datum);
		if (parts == null) {
			return false;
		}
		if (!parts.isEmpty() && ClojureLowerUtil.isSymbolNamed(parts.get(0), "quote")) {
			return false;
		}
		for (LispVal part : parts) {
			if (part instanceof LispSymbol s && s.name().equals("class")) {
				return true;
			}
			if (part instanceof LispCons && containsClassCall(part)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Records a definition's dispatch-function datum for a later {@code defmulti} over
	 * its name: a {@code defn}'s rebuilt {@code (fn ...)} datum, or a {@code def}'s
	 * {@code (fn ...)} value datum (a name aliasing an already-recorded one shares its
	 * datum). Only a class-calling definition is kept -- anything else (including a
	 * value-less {@code def} and a {@code ^:dynamic} name, whose calls route through the
	 * value cell) drops the name, so a redefinition without one unregisters it.
	 * @param name the defined name
	 * @param dynamic whether the name is {@code ^:dynamic}
	 * @param valueDatum the {@code fn} datum, or null when there is none
	 */
	static void recordClassDispatchFn(ClojureLowering ctx, String name, boolean dynamic, @Nullable LispVal valueDatum) {
		if (dynamic || valueDatum == null) {
			ctx.classDispatchFns.remove(name);
			return;
		}
		if (valueDatum instanceof LispSymbol s && !s.name().startsWith(":")) {
			LispVal target = ctx.classDispatchFns.get(s.name());
			if (target != null) {
				ctx.classDispatchFns.put(name, target);
			}
			else {
				ctx.classDispatchFns.remove(name);
			}
			return;
		}
		List<LispVal> parts = ClojureLowerUtil.items(valueDatum);
		if (parts != null && !parts.isEmpty()
				&& (ClojureLowerUtil.isSymbolNamed(parts.get(0), "fn") || parts.get(0) == ClojureReader.FN_ANON)
				&& containsClassCall(valueDatum)) {
			ctx.classDispatchFns.put(name, valueDatum);
		}
		else {
			ctx.classDispatchFns.remove(name);
		}
	}

	/**
	 * The dispatch datum a {@code defmulti} lowers: the recorded definition when the
	 * datum names a class-calling {@code defn} or {@code def}'d function, so its
	 * {@code class} calls answer nil itself for nil under the dispatch lowering, like the
	 * inline datum does. A name a local shadows keeps its reference (the local is the
	 * dispatch function, not the definition).
	 */
	static LispVal dispatchDatumFor(ClojureLowering ctx, LispVal dispatchDatum) {
		if (dispatchDatum instanceof LispSymbol s) {
			LispVal recorded = ctx.classDispatchFns.get(s.name());
			if (recorded != null) {
				for (Map<String, ClojureLowering.Kind> scope : ctx.scopes) {
					if (scope.containsKey(s.name())) {
						return dispatchDatum;
					}
				}
				return recorded;
			}
		}
		return dispatchDatum;
	}

	/**
	 * A call inside a {@code defmulti} dispatch function to a recorded class-calling
	 * definition, inlined from its recorded {@code (fn ...)} datum: the definition's own
	 * lowering answers the {@code :nil} keyword for nil outside the dispatch lowering, so
	 * a direct call there would miss the nil method -- re-lowering the recorded datum
	 * applied to the call's argument datums answers nil itself instead, like the inline
	 * datum and the oracle. A name a local shadows keeps its call (the local is the
	 * function, not the definition), and a name already being inlined keeps its direct
	 * call, so a (mutually) recursive definition still terminates the lowering.
	 * @param name the called name
	 * @param items the call datum, head included
	 * @return the inlined form, or null when the name is no unshadowed recorded
	 * definition
	 */
	static @Nullable LispVal inlineDispatchCall(ClojureLowering ctx, String name, List<LispVal> items) {
		LispVal recorded = ctx.classDispatchFns.get(name);
		if (recorded == null || !ctx.inliningDispatch.add(name)) {
			return null;
		}
		try {
			for (Map<String, ClojureLowering.Kind> scope : ctx.scopes) {
				if (scope.containsKey(name)) {
					return null;
				}
			}
			List<LispVal> synthetic = new ArrayList<>();
			synthetic.add(recorded);
			synthetic.addAll(items.subList(1, items.size()));
			return ctx.lowerInner(ClojureLowerUtil.list(synthetic));
		}
		finally {
			ctx.inliningDispatch.remove(name);
		}
	}

	/**
	 * {@code (defmulti name doc? dispatch-fn & opts)}: a method table, a default dispatch
	 * value and an {@code Object}-method slot in four globals no identifier can spell
	 * (the suffix follows the mangled name, like the multi-arity helpers), plus a
	 * dispatcher {@code defun} applying each call's dispatch value to the table. The
	 * dispatcher maps a true nil onto the {@code (:C%NIL)} marker first (no table ever
	 * keys on nil, on any backend), so a {@code nil} method answers both the
	 * {@code class} nil and the {@code identity} nil while a dispatch value that
	 * literally is the {@code :nil} keyword keeps its keyword row, like the oracle. A
	 * {@code class} call inside the dispatch function answers nil itself for a nil
	 * argument (see {@link #classForm(LispVal)}), so the null test maps it onto the
	 * marker too -- bare, wrapped in another function, or through a named {@code defn} or
	 * {@code def}'d function (re-lowered from the recorded definition, like the oracle).
	 * A call to one of these names nested inside an inline dispatch datum inlines the
	 * recorded datum at the call site instead (see
	 * {@link #inlineDispatchCall(String, List)}), so it answers nil itself too. The
	 * default dispatch value is {@code :default} without a {@code :default} option (an
	 * arbitrary keyword with one -- the corpus's {@code :everything-else} -- stored
	 * per-multimethod like {@code :default} today); a miss with no method for the default
	 * signals, like the oracle. With a {@code :hierarchy} option the dispatcher consults
	 * that hierarchy value on a miss (the global one without the option): every method
	 * whose key the dispatch value descends from ({@code isa?}) is a candidate, the
	 * strictly most specific wins, {@code prefer-method} breaks the remaining ties, and
	 * an unbroken tie signals -- like the oracle. Past the search but ahead of the
	 * default, a defined {@code Object} method catches the rest, like the oracle's (which
	 * it always beats); without one the slot is nil and the search decides alone.
	 */
	static List<LispVal> defmultiForms(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "defmulti takes a name, a dispatch function and options");
		String name = ClojureLowerUtil.plainName(items.get(1), "defmulti");
		int at = 2;
		if (items.get(at) instanceof LispString) {
			at++; // the docstring
		}
		List<LispVal> attr = ClojureLowerUtil.items(items.get(at));
		if (attr != null && !attr.isEmpty() && ClojureLowerUtil.isSymbolNamed(attr.get(0), "%hash-map")) {
			at++; // the attr map
		}
		ClojureLowerUtil.isTrue(at < items.size(), "defmulti takes a name, a dispatch function and options");
		LispVal dispatchDatum = items.get(at++);
		LispVal defaultDatum = null;
		LispVal hierarchyDatum = null;
		for (; at < items.size(); at += 2) {
			if (at + 1 >= items.size() || !(items.get(at) instanceof LispSymbol opt)) {
				throw new LispReadException("defmulti takes option/value pairs");
			}
			switch (opt.name()) {
				case ":default" -> defaultDatum = items.get(at + 1);
				case ":hierarchy" -> hierarchyDatum = items.get(at + 1);
				default -> throw new LispReadException("defmulti option " + opt.name() + " is not supported yet");
			}
		}
		ctx.globals.put(name, ClojureLowering.Kind.FUNCTION);
		ctx.macros.remove(name); // a definition wins over the macro it shadows
		LispSymbol methods = new LispSymbol(ClojureLowering.mangle(name) + "%methods");
		LispSymbol fallback = new LispSymbol(ClojureLowering.mangle(name) + "%default");
		LispSymbol prefers = new LispSymbol(ClojureLowering.mangle(name) + "%prefers");
		LispSymbol object = new LispSymbol(ClojureLowering.mangle(name) + "%object");
		LispVal dispatchFn;
		boolean wasDispatch = ctx.inDispatchFn;
		ctx.inDispatchFn = true;
		try {
			dispatchFn = ClojureBindingLowering.fnValue(ctx, dispatchDatumFor(ctx, dispatchDatum));
		}
		finally {
			ctx.inDispatchFn = wasDispatch;
		}
		LispVal defaultForm = defaultDatum == null ? ClojureCollectionLowering.keywordForm("default")
				: ctx.lower(defaultDatum);
		LispVal hierarchyForm = hierarchyDatum == null ? hierarchyGlobal() : ctx.lower(hierarchyDatum);
		LispSymbol args = ctx.freshTemp();
		LispSymbol disp = ctx.freshTemp();
		LispSymbol raw = ctx.freshTemp();
		LispSymbol found = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispVal missCall = ClojureLowerUtil.list(new LispSymbol(HIERARCHY_DISPATCH), LispString.literal(name), methods,
				prefers, hierarchyForm, fallback, disp, args);
		LispVal missForm = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), object,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
								ClojureLowerUtil.list(new LispSymbol("C%H-CANDIDATES"), methods, hierarchyForm, disp))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), object, args), missCall);
		// A `class` call inside the dispatch function answers nil itself for a nil
		// argument (see classForm), so the one null test maps every class-produced
		// nil onto the marker while an explicit `:nil` keyword keeps its keyword row,
		// like the oracle; a shadowed `class` is the caller's own function.
		LispVal nilTest = ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), raw);
		LispVal dispatch = ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(List.of(
				ClojureLowerUtil.list(raw, ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), dispatchFn, args)),
				ClojureLowerUtil.list(disp,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), nilTest,
								ClojureCollectionLowering.nilMarkerForm(), raw)),
				ClojureLowerUtil.list(miss,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)),
				ClojureLowerUtil.list(found,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), disp, methods, miss)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), found, miss), missForm,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), found, args)));
		List<LispVal> forms = new ArrayList<>();
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), methods, ClojureCollectionLowering.makeTable()));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), fallback, defaultForm));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), prefers, ClojureCollectionLowering.makeTable()));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), object, ClojureLowering.NIL_CONST));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), ClojureLowerUtil.idSym(name),
				ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, args)), dispatch));
		ctx.usedHierarchy = true;
		return forms;
	}

	/**
	 * {@code (defmethod name dispatch-value [params...] body...)}: the lambda over the
	 * (possibly destructured) parameters stored in the method table. The multimethod must
	 * be defined -- forward order aside, the pre-scan declares every {@code defmulti}
	 * first.
	 */
	/**
	 * One single-arity method lambda, {@code labels}-wrapped under a fresh name when a
	 * {@code recur} reaches its body (like an anonymous {@code fn}): a stored method has
	 * no callable name of its own, so without a {@code recur} it stays a bare lambda,
	 * exactly as before. A used variadic target splits into a worker plus its
	 * {@code &rest} head, like every other {@code fn} shape (decided 2026-10-01, b36).
	 */
	static LispVal methodLambda(ClojureLowering ctx, LispVal params, List<LispVal> bodyForms) {
		String fresh = ctx.freshRecurName();
		String worker = ClojureBindingLowering.workerName(fresh);
		ClojureLowering.RecurTarget target = new ClojureLowering.RecurTarget(
				ClojureBindingLowering.isVariadicParams(params) ? worker : fresh, true);
		ClojureLowering.Clause clause = ClojureBindingLowering.clause(ctx, params, bodyForms, target);
		if (target.used() && clause.variadic()) {
			return ClojureBindingLowering.splitMethodLambda(ctx, fresh, worker, clause, clause.wrapped());
		}
		LispVal lambda = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(clause.params()),
				clause.wrapped());
		if (!target.used()) {
			return lambda;
		}
		return ClojureLowering.labelsSelfCall(fresh, lambda);
	}

	static LispVal defmethodForm(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 4,
				"defmethod takes a name, a dispatch value, a parameter vector and a body");
		String name = ClojureLowerUtil.plainName(items.get(1), "defmethod");
		ClojureLowerUtil.isTrue(ctx.known(name), "No such multimethod: " + name);
		LispSymbol methods = new LispSymbol(ClojureLowering.mangle(name) + "%methods");
		LispVal lambda = methodLambda(ctx, items.get(3), items.subList(4, items.size()));
		LispVal keyDatum = items.get(2);
		if (keyDatum instanceof LispSymbol s && isObjectClassName(ctx, s.name())) {
			// the table row (for get-method) and the catch-all slot the dispatcher
			// tries past the hierarchy search but ahead of the default
			LispSymbol object = new LispSymbol(ClojureLowering.mangle(name) + "%object");
			return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), List.of(
					ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
									ClojureCollectionLowering.keywordForm("object"), methods),
							lambda),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), object, lambda)));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), dispatchKeyForm(ctx, keyDatum), methods),
				lambda);
	}

	static LispVal removeMethodOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "remove-method takes a multimethod and a dispatch value");
		String name = ClojureLowerUtil.plainName(items.get(1), "remove-method");
		ClojureLowerUtil.isTrue(ctx.known(name), "No such multimethod: " + name);
		LispSymbol methods = new LispSymbol(ClojureLowering.mangle(name) + "%methods");
		LispVal keyDatum = items.get(2);
		if (keyDatum instanceof LispSymbol s && isObjectClassName(ctx, s.name())) {
			LispSymbol object = new LispSymbol(ClojureLowering.mangle(name) + "%object");
			return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"),
					List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("remhash"),
							ClojureCollectionLowering.keywordForm("object"), methods),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), object, ClojureLowering.NIL_CONST),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.idSym(name))));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("remhash"), dispatchKeyForm(ctx, keyDatum), methods),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.idSym(name)));
	}

	static LispVal getMethodOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "get-method takes a multimethod and a dispatch value");
		String name = ClojureLowerUtil.plainName(items.get(1), "get-method");
		ClojureLowerUtil.isTrue(ctx.known(name), "No such multimethod: " + name);
		LispSymbol methods = new LispSymbol(ClojureLowering.mangle(name) + "%methods");
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), dispatchKeyForm(ctx, items.get(2)), methods,
				ClojureLowering.NIL_CONST);
	}

	// protocols/records: defprotocol/defrecord/deftype/reify/extend/satisfies? over the
	// table runtime

	/**
	 * The tag heading a record value: a record is
	 * {@code (LIST :C%RECORD (:C%KEYWORD "Name") (fields...) table)}, beside the
	 * {@code (:C%SET table)} and {@code (:C%KEYWORD spelling)} wrappers. The table is an
	 * {@code equal} table like every map, so every backend prints, hashes and compares it
	 * through the runtime they already share; no backend learns a new value shape.
	 */
	static final LispSymbol RECORD_TAG = new LispSymbol(":C%RECORD");

	/**
	 * The tag heading a deftype value: the same 4-list as a record, but opaque to the map
	 * verbs (reads miss, writers signal, {@code =} is identity), like the oracle.
	 */
	static final LispSymbol TYPE_TAG = new LispSymbol(":C%TYPE");

	/**
	 * The tag heading a reify value: {@code (LIST :C%REIFY (gensym))}, one fresh tag per
	 * evaluation, so two instances never share a dispatch row. Opaque like a deftype.
	 */
	static final LispSymbol REIFY_TAG = new LispSymbol(":C%REIFY");

	/**
	 * The runtime reader of a value's protocol-dispatch tag, as spelled in programs.
	 */
	static final String PROTOCOL_TAG = "C%PROTOCOL-TAG";

	/**
	 * Whether the form holds a record: a cons headed by the tag with a field list and a
	 * table behind it. The full shape check keeps user data from misfiring the test, like
	 * {@link #isSetForm}.
	 */
	static LispVal isRecordForm(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), form),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), form), RECORD_TAG),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cddr"), form)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadddr"), form)));
	}

	/** Whether the form holds a deftype value: the same shape check over its tag. */
	static LispVal isDeftypeForm(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), form),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), form), TYPE_TAG),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cddr"), form)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadddr"), form)));
	}

	/** Whether the form holds a reify value: a cons headed by its tag. */
	static LispVal isReifyForm(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), form),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), form), REIFY_TAG));
	}

	/** Whether the form holds any typed value: a record, a deftype or a reify. */
	static LispVal isTypedForm(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("or"), isRecordForm(form), isDeftypeForm(form),
				isReifyForm(form));
	}

	/** The dispatch tag inside a record or deftype value: {@code (CADR form)}. */
	static LispVal typedTagOf(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), form);
	}

	/** The entry table inside a record or deftype value: {@code (CADDDR form)}. */
	static LispVal typedTableOf(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("cadddr"), form);
	}

	/**
	 * The declared field keywords inside a record or deftype value: {@code (CADDR form)}.
	 */
	static LispVal typedFieldsOf(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("caddr"), form);
	}

	/** A record's construction: {@code (LIST :C%RECORD tag fields table)}. */
	static LispVal wrapRecord(LispVal tag, LispVal fields, LispVal table) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), RECORD_TAG, tag, fields, table);
	}

	/** A deftype's construction: {@code (LIST :C%TYPE tag fields table)}. */
	static LispVal wrapDeftype(LispVal tag, LispVal fields, LispVal table) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), TYPE_TAG, tag, fields, table);
	}

	/** A type's dispatch tag as data: the keyword of its spelling. */
	static LispVal typeTagForm(String name) {
		return ClojureCollectionLowering.keywordForm(name);
	}

	/**
	 * The protocol runtime, spliced once per program behind the false binding: the tag
	 * reader every dispatcher and {@code satisfies?} shares. A record, deftype or reify
	 * answers its own tag; anything else answers its {@code class} kind keyword, so
	 * extending to a host kind ({@code String}, {@code Number}, ...) dispatches on the
	 * same spelling {@code class} answers. What no branch names (a host object from
	 * interop on the backends that have one) answers a fresh one-list no row can hold, so
	 * the {@code Object} default still catches it instead of signalling.
	 */
	static List<LispVal> protocolRuntime(ClojureLowering ctx) {
		LispSymbol one = new LispSymbol("x");
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(isRecordForm(one), typedTagOf(one)));
		branches.add(ClojureLowerUtil.list(isDeftypeForm(one), typedTagOf(one)));
		branches.add(ClojureLowerUtil.list(isReifyForm(one), typedTagOf(one)));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), one),
				ClojureCollectionLowering.keywordForm("nil")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), one, ctx.falseVariable),
				ClojureCollectionLowering.keywordForm("boolean")));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), one, ClojureLowering.TRUE_CONST),
				ClojureCollectionLowering.keywordForm("boolean")));
		branches.add(ClojureLowerUtil.list(ClojureSeqLowering.keywordTest(one),
				ClojureCollectionLowering.keywordForm("keyword")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("symbolp"), one),
				ClojureCollectionLowering.keywordForm("symbol")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), one),
				ClojureCollectionLowering.keywordForm("char")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), one),
				ClojureCollectionLowering.keywordForm("string")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), one),
				ClojureCollectionLowering.keywordForm("number")));
		branches.add(ClojureLowerUtil.list(ClojureCollectionLowering.isSetForm(one),
				ClojureCollectionLowering.keywordForm("set")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), one),
				ClojureCollectionLowering.keywordForm("map")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), one),
				ClojureCollectionLowering.keywordForm("vector")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), one),
				ClojureCollectionLowering.keywordForm("list")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("functionp"), one),
				ClojureCollectionLowering.keywordForm("function")));
		branches.add(ClojureLowerUtil.list(ClojureStateLowering.isAtomForm(one),
				ClojureCollectionLowering.keywordForm("atom")));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)));
		return List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), new LispSymbol(PROTOCOL_TAG),
				ClojureLowerUtil.list(List.of(one)), ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches)));
	}

	/**
	 * The pre-scan half of {@code defprotocol}: registers the protocol (parsed pure, so
	 * parsing twice is harmless) plus the protocol name and every method name, so a
	 * dispatch call may stand above the definition, like {@code defn}.
	 */
	static ClojureLowering.ProtocolDef declareProtocol(ClojureLowering ctx, List<LispVal> items) {
		String name = ClojureLowerUtil.plainName(items.get(1), "defprotocol");
		ClojureLowering.ProtocolDef def = parseProtocol(ctx, name, items);
		ctx.protocols.put(name, def);
		ctx.globals.put(name, ClojureLowering.Kind.VARIABLE);
		ctx.macros.remove(name); // a definition wins over the macro it shadows
		for (String method : def.methods()) {
			ctx.globals.put(method, ClojureLowering.Kind.FUNCTION);
			ctx.macros.remove(method); // a definition wins over the macro it shadows
		}
		return def;
	}

	/**
	 * The pre-scan half of {@code defrecord}/{@code deftype}: registers the type plus its
	 * constructors, so a constructor call may stand above the definition, like
	 * {@code defn}. The type name itself is no value (the oracle answers a host class,
	 * which no wasm backend has).
	 */
	static ClojureLowering.TypeDef declareRecordType(ClojureLowering ctx, List<LispVal> items) {
		boolean record = ClojureLowerUtil.isSymbolNamed(items.get(0), "defrecord");
		String name = ClojureLowerUtil.plainName(items.get(1), record ? "defrecord" : "deftype");
		List<String> fields = recordFields(ctx, items);
		ClojureLowering.TypeDef def = new ClojureLowering.TypeDef(record, fields, name);
		ctx.types.put(name, def);
		ctx.globals.put("->" + name, ClojureLowering.Kind.FUNCTION);
		if (record) {
			ctx.globals.put("map->" + name, ClojureLowering.Kind.FUNCTION);
		}
		return def;
	}

	/**
	 * Parses a {@code defprotocol} datum without emitting: the name's method names in
	 * definition order, with the table and default globals. Docstrings and attr maps are
	 * skipped, like {@code defn} and {@code defmulti}; a signature is one parameter
	 * vector per method (several arities stay refused).
	 */
	static ClojureLowering.ProtocolDef parseProtocol(ClojureLowering ctx, String name, List<LispVal> items) {
		int at = 2;
		if (at < items.size() && items.get(at) instanceof LispString) {
			at++; // the docstring
		}
		if (at < items.size() && ClojureBindingLowering.isAttrMap(items.get(at))) {
			at++; // the attr map
		}
		for (; at + 1 < items.size() && items.get(at) instanceof LispSymbol opt
				&& opt.name().startsWith(":"); at += 2) {
			if (opt.name().equals(":extend-via-metadata")) {
				LispVal flag = items.get(at + 1);
				if (!(flag instanceof LispSymbol f && (f.name().equals("false") || f.name().equals("nil")))) {
					throw new LispReadException(
							"extend-via-metadata is not supported yet: metadata never affects dispatch");
				}
			}
			else {
				throw new LispReadException("defprotocol option " + opt.name() + " is not supported yet");
			}
		}
		Set<String> methods = new LinkedHashSet<>();
		for (; at < items.size(); at++) {
			List<LispVal> sig = ClojureLowerUtil.items(items.get(at));
			if (sig == null || sig.isEmpty() || !(sig.get(0) instanceof LispSymbol)) {
				throw new LispReadException("defprotocol takes method signatures, not " + items.get(at).print());
			}
			String method = ((LispSymbol) sig.get(0)).name();
			ClojureLowerUtil.isTrue(!method.startsWith(":"),
					"defprotocol takes method signatures, not " + items.get(at).print());
			ClojureLowerUtil.isTrue(methods.add(method), "duplicate method in defprotocol " + name + ": " + method);
			int marg = 1;
			if (marg < sig.size() && sig.get(marg) instanceof LispString) {
				marg++; // the method docstring
			}
			ClojureLowerUtil.isTrue(
					marg < sig.size()
							&& ClojureBindingLowering.isVectorDatum(ClojureLowerUtil.stripMeta(sig.get(marg))),
					"multi-arity protocol methods are not supported yet: " + method);
			List<LispVal> params = ClojureLowerUtil.bindingItems(ClojureLowerUtil.stripMeta(sig.get(marg)),
					"the method " + method + " of");
			ClojureLowerUtil.isTrue(!params.isEmpty(), "a protocol method takes a target and arguments: " + method);
		}
		ClojureLowerUtil.isTrue(!methods.isEmpty(), "defprotocol takes at least one method: " + name);
		return new ClojureLowering.ProtocolDef(methods, new LispSymbol(ClojureLowering.mangle(name) + "%methods"),
				new LispSymbol(ClojureLowering.mangle(name) + "%default"));
	}

	/**
	 * {@code (defprotocol name doc? (method [target & args] doc?)...)}: a method-table
	 * global plus an {@code Object}-default global per protocol, one dispatcher
	 * {@code defun} per method, and the protocol name bound to its table. A call
	 * dispatches on the target's tag (exact match, then the {@code Object} row); a miss
	 * with no {@code Object} row signals, like the oracle.
	 */
	static List<LispVal> defprotocolForms(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "defprotocol takes a name and methods");
		String name = ClojureLowerUtil.plainName(items.get(1), "defprotocol");
		ClojureLowering.ProtocolDef def = declareProtocol(ctx, items);
		List<LispVal> forms = new ArrayList<>();
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), def.methodsVar(),
				ClojureCollectionLowering.makeTable()));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), def.defaultVar(), ClojureLowering.NIL_CONST));
		for (String method : def.methods()) {
			forms.add(dispatcherDefun(ctx, name, method, def));
		}
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), ClojureLowerUtil.idSym(name), def.methodsVar()));
		ctx.usedProtocols = true;
		return forms;
	}

	/**
	 * One protocol-method dispatcher: the {@code &rest} shape {@code defmulti} takes, so
	 * arities (fixed or variadic) fall out of the stored lambda. No arguments signals the
	 * wrong-count error instead of dispatching on nil, like the oracle's arity error.
	 */
	static LispVal dispatcherDefun(ClojureLowering ctx, String protocol, String method,
			ClojureLowering.ProtocolDef def) {
		LispSymbol args = ctx.freshTemp();
		LispSymbol tag = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol inner = ctx.freshTemp();
		LispSymbol found = ctx.freshTemp();
		LispVal lookup = ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(
						ClojureLowerUtil.list(tag,
								ClojureLowerUtil.list(new LispSymbol(PROTOCOL_TAG),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args))),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)),
						ClojureLowerUtil.list(inner,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), tag, def.methodsVar(), miss)),
						ClojureLowerUtil.list(found,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), inner, miss), miss,
										ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
												ClojureCollectionLowering.keywordForm(method), inner, miss))))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), found, miss),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), def.defaultVar()),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
										LispString.literal("No implementation of method :" + method + " of protocol :"
												+ protocol + " found")),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), def.defaultVar(), args)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), found, args)));
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
						LispString.literal("wrong number of arguments passed to: " + method)),
				lookup);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), ClojureLowerUtil.idSym(method),
				ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, args)), body);
	}

	/**
	 * Stores one method row in a protocol's table: the tag's inner table (made on first
	 * use) mapping the method keyword to the lambda. Later rows win, like the oracle.
	 */
	static LispVal methodStoreForm(ClojureLowering ctx, LispSymbol methodsVar, LispVal key, String method,
			LispVal lambda) {
		LispSymbol inner = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispVal ensure = ClojureLowerUtil
			.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), inner, miss),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, methodsVar), ClojureLowerUtil
								.list(ClojureLowerUtil.sym("setf"), inner, ClojureCollectionLowering.makeTable())),
					inner);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)),
						ClojureLowerUtil.list(inner,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, methodsVar, miss)))),
				ensure,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("gethash"), ClojureCollectionLowering.keywordForm(method), inner),
						lambda));
	}

	/**
	 * The declared fields of a {@code defrecord}/{@code deftype} datum: plain names (type
	 * hints strip, like everywhere else), each once.
	 */
	static List<String> recordFields(ClojureLowering ctx, List<LispVal> items) {
		boolean record = ClojureLowerUtil.isSymbolNamed(items.get(0), "defrecord");
		String what = record ? "defrecord" : "deftype";
		ClojureLowerUtil.isTrue(items.size() >= 3, what + " takes a name and fields");
		List<LispVal> names = ClojureLowerUtil.bindingItems(ClojureLowerUtil.stripMeta(items.get(2)), what);
		List<String> fields = new ArrayList<>();
		for (LispVal datum : names) {
			String field = ClojureLowerUtil.plainName(ClojureLowerUtil.stripMeta(datum), what);
			ClojureLowerUtil.isTrue(!fields.contains(field),
					"duplicate field in " + what + " " + items.get(1).print() + ": " + field);
			fields.add(field);
		}
		return fields;
	}

	/**
	 * The implementation groups behind {@code defrecord}/{@code deftype} (each headed by
	 * a known protocol name) or {@code extend-type}: every method must belong to its
	 * protocol, like the oracle's "Can't define method not in interfaces".
	 */
	static List<ClojureLowering.ImplGroup> implGroups(ClojureLowering ctx, List<LispVal> rest, String what) {
		List<ClojureLowering.ImplGroup> groups = new ArrayList<>();
		String protocol = null;
		ClojureLowering.ProtocolDef def = null;
		List<ClojureLowering.TypeMethod> methods = null;
		for (LispVal datum : rest) {
			if (datum instanceof LispSymbol s && !s.name().startsWith(":")) {
				if (methods != null) {
					if (protocol == null) {
						throw new LispReadException(what + " takes method implementations");
					}
					groups.add(new ClojureLowering.ImplGroup(protocol, methods));
				}
				protocol = s.name();
				def = ctx.protocols.get(protocol);
				if (def == null) {
					throw new LispReadException("No such protocol: " + protocol);
				}
				methods = new ArrayList<>();
				continue;
			}
			if (def == null || methods == null) {
				throw new LispReadException(what + " methods group under a protocol name, not " + datum.print());
			}
			List<LispVal> impl = ClojureLowerUtil.items(datum);
			if (impl == null || impl.size() < 2 || !(impl.get(0) instanceof LispSymbol)) {
				throw new LispReadException(
						"a method implementation takes a name, parameters and a body, not " + datum.print());
			}
			String method = ((LispSymbol) impl.get(0)).name();
			ClojureLowerUtil.isTrue(def.methods().contains(method), "Can't define method not in interfaces: " + method);
			String seen = method;
			ClojureLowerUtil.isTrue(methods.stream().noneMatch(m -> m.method().equals(seen)),
					"duplicate method implementation: " + method);
			ClojureLowerUtil.isTrue(ClojureBindingLowering.isVectorDatum(ClojureLowerUtil.stripMeta(impl.get(1))),
					"multi-arity protocol methods are not supported yet: " + method);
			List<LispVal> params = ClojureLowerUtil.bindingItems(ClojureLowerUtil.stripMeta(impl.get(1)),
					"the method " + method + " of");
			ClojureLowerUtil.isTrue(!params.isEmpty(), "a protocol method takes a target and arguments: " + method);
			methods.add(new ClojureLowering.TypeMethod(method, impl.get(1), impl.subList(2, impl.size())));
		}
		if (methods != null) {
			if (protocol == null) {
				throw new LispReadException(what + " takes method implementations");
			}
			groups.add(new ClojureLowering.ImplGroup(protocol, methods));
		}
		return groups;
	}

	/**
	 * One method-implementation row: the lambda (with the usual destructuring prologue)
	 * stored in the protocol's table under the target's tag. Inline
	 * {@code defrecord}/{@code deftype} bodies see the fields as locals bound from the
	 * instance table -- an outer scope, so an explicit parameter shadows its field, like
	 * the oracle.
	 */
	static LispVal methodRow(ClojureLowering ctx, String protocol, LispVal key, ClojureLowering.TypeMethod impl,
			@Nullable List<String> fields) {
		ClojureLowering.ProtocolDef def = ctx.protocols.get(protocol);
		if (def == null) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		LispVal lambda;
		if (fields == null) {
			lambda = methodLambda(ctx, impl.params(), impl.body());
		}
		else {
			Map<String, ClojureLowering.Kind> scope = new HashMap<>();
			for (String field : fields) {
				scope.put(field, ClojureLowering.Kind.VARIABLE);
			}
			lambda = ctx.inScope(scope, () -> {
				String fresh = ctx.freshRecurName();
				String worker = ClojureBindingLowering.workerName(fresh);
				ClojureLowering.RecurTarget target = new ClojureLowering.RecurTarget(
						ClojureBindingLowering.isVariadicParams(impl.params()) ? worker : fresh, true);
				ClojureLowering.Clause clause = ClojureBindingLowering.clause(ctx, impl.params(), impl.body(), target);
				LispVal inner = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
						ClojureLowerUtil.list(clause.params()), clause.wrapped());
				if (fields.isEmpty()) {
					if (target.used() && clause.variadic()) {
						return ClojureBindingLowering.splitMethodLambda(ctx, fresh, worker, clause, clause.wrapped());
					}
					return target.used() ? ClojureLowering.labelsSelfCall(fresh, inner) : inner;
				}
				LispVal self = clause.params().isEmpty() ? ClojureLowering.NIL_CONST : clause.params().get(0);
				List<LispVal> binds = new ArrayList<>();
				for (String field : fields) {
					binds.add(ClojureLowerUtil.list(ClojureLowerUtil.idSym(field),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
									ClojureCollectionLowering.keywordForm(field), typedTableOf(self),
									ClojureLowering.NIL_CONST)));
				}
				LispVal fieldBody = ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(binds),
						clause.wrapped());
				LispVal withFields = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
						ClojureLowerUtil.list(clause.params()), fieldBody);
				if (target.used() && clause.variadic()) {
					return ClojureBindingLowering.splitMethodLambda(ctx, fresh, worker, clause, fieldBody);
				}
				return target.used() ? ClojureLowering.labelsSelfCall(fresh, withFields) : withFields;
			});
		}
		return methodStoreForm(ctx, def.methodsVar(), key, impl.method(), lambda);
	}

	/**
	 * {@code (defrecord Name [fields] Protocol (method [target & args] body...)...
	 * opts?)}: the positional and map constructors plus one table row per inline method.
	 * A trailing keyword names an unsupported option, like {@code defprotocol}'s.
	 */
	static List<LispVal> recordTypeForms(ClojureLowering ctx, List<LispVal> items) {
		boolean record = ClojureLowerUtil.isSymbolNamed(items.get(0), "defrecord");
		String what = record ? "defrecord" : "deftype";
		ClojureLowerUtil.isTrue(items.size() >= 3, what + " takes a name and fields");
		String name = ClojureLowerUtil.plainName(items.get(1), what);
		ClojureLowering.TypeDef def = declareRecordType(ctx, items);
		List<LispVal> rest = new ArrayList<>(items.subList(3, items.size()));
		for (LispVal datum : rest) {
			ClojureLowerUtil.isTrue(!(datum instanceof LispSymbol s && s.name().startsWith(":")),
					what + " option " + datum.print() + " is not supported yet");
		}
		List<ClojureLowering.ImplGroup> groups = implGroups(ctx, rest, what);
		List<LispVal> forms = new ArrayList<>();
		forms.add(positionalCtor(ctx, name, def));
		if (record) {
			forms.add(mapCtor(ctx, name, def));
		}
		for (ClojureLowering.ImplGroup group : groups) {
			for (ClojureLowering.TypeMethod impl : group.methods()) {
				forms.add(methodRow(ctx, group.protocol(), typeTagForm(name), impl, def.fields()));
			}
		}
		ctx.usedProtocols = true;
		return forms;
	}

	/**
	 * The positional constructor: a mangled {@code defun} over the fields, so a wrong
	 * count signals like any other call. The field keywords travel twice (the declared
	 * list for {@code dissoc}'s keep-type rule, the table for the map verbs).
	 */
	static LispVal positionalCtor(ClojureLowering ctx, String name, ClojureLowering.TypeDef def) {
		List<LispVal> params = new ArrayList<>();
		List<LispVal> pairs = new ArrayList<>();
		List<LispVal> keys = new ArrayList<>();
		for (String field : def.fields()) {
			params.add(ClojureLowerUtil.idSym(field));
			LispVal key = ClojureCollectionLowering.keywordForm(field);
			keys.add(key);
			pairs.add(key);
			pairs.add(ClojureLowerUtil.idSym(field));
		}
		LispVal table = ClojureCollectionLowering
			.tableFromPlist(ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), pairs));
		LispVal value = def.record()
				? wrapRecord(typeTagForm(name), ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), keys), table)
				: wrapDeftype(typeTagForm(name), ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), keys), table);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), ClojureLowerUtil.idSym("->" + name),
				ClojureLowerUtil.list(params), value);
	}

	/**
	 * The map constructor (records only -- the oracle defines none for deftypes): the
	 * entries copied out of the argument (nil builds empty, a record contributes its
	 * entries), missing fields defaulting to nil, extra entries kept, like the oracle.
	 */
	static LispVal mapCtor(ClojureLowering ctx, String name, ClojureLowering.TypeDef def) {
		LispSymbol src = ctx.freshTemp();
		LispSymbol table = ctx.freshTemp();
		LispSymbol pairs = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		List<LispVal> keys = new ArrayList<>();
		for (String field : def.fields()) {
			keys.add(ClojureCollectionLowering.keywordForm(field));
		}
		List<LispVal> fill = new ArrayList<>();
		for (LispVal key : keys) {
			fill.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table, miss), miss),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table),
							ClojureLowering.NIL_CONST),
					ClojureLowering.NIL_CONST));
		}
		fill.add(wrapRecord(typeTagForm(name), ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), keys), table));
		LispVal whole = ClojureLowerUtil
			.list(ClojureLowerUtil.sym("let*"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(src, src),
							ClojureLowerUtil.list(pairs, ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), src,
									ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), isRecordForm(src),
											ClojureCollectionLowering.tablePlist(typedTableOf(src)),
											ClojureCollectionLowering.tablePlist(src)),
									ClojureLowering.NIL_CONST)),
							ClojureLowerUtil.list(miss,
									ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
							ClojureLowerUtil.list(List
								.of(ClojureLowerUtil.list(table, ClojureCollectionLowering.tableFromPlist(pairs)))),
							ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), fill)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), ClojureLowerUtil.idSym("map->" + name),
				ClojureLowerUtil.list(List.of(src)), whole);
	}

	/**
	 * Maps an {@code extend} target name to its dispatch-key form, or null for
	 * {@code Object} (the default row). Record and deftype names answer their tags; host
	 * kinds answer the {@code class} keyword spelling, so dispatch agrees with
	 * {@code class}; anything else (a {@code java.time.Instant}, a {@code Date}, ...) is
	 * a named refusal.
	 */
	static @Nullable LispVal extendKeyForm(ClojureLowering ctx, String typeName, String what) {
		if (typeName.equals("Object")) {
			return null;
		}
		if (typeName.equals("nil")) {
			return ClojureCollectionLowering.keywordForm("nil");
		}
		if (ctx.types.containsKey(typeName)) {
			return typeTagForm(typeName);
		}
		String kind = switch (typeName) {
			case "String", "CharSequence" -> "string";
			case "Number", "Long", "Double", "Integer", "Float", "Short", "Byte" -> "number";
			case "Boolean" -> "boolean";
			case "Keyword" -> "keyword";
			case "Symbol" -> "symbol";
			case "Character", "Char" -> "char";
			case "Map", "IPersistentMap" -> "map";
			case "Vector", "IPersistentVector" -> "vector";
			case "Set", "IPersistentSet" -> "set";
			case "List", "Seq", "Sequential", "Collection", "IPersistentList", "IPersistentCollection" -> "list";
			case "Fn", "IFn", "Function" -> "function";
			case "Atom" -> "atom";
			default -> null;
		};
		if (kind == null) {
			throw new LispReadException(what + " needs a core type, not " + typeName);
		}
		return ClojureCollectionLowering.keywordForm(kind);
	}

	/**
	 * Stores one extension row: under the target's tag, or (for {@code Object}) the
	 * protocol's default global consulted on a miss. The method must belong to the
	 * protocol, like the inline implementations.
	 */
	static LispVal extendRow(ClojureLowering ctx, String protocol, @Nullable LispVal key,
			ClojureLowering.TypeMethod impl, String what) {
		ClojureLowering.ProtocolDef def = ctx.protocols.get(protocol);
		if (def == null) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		ClojureLowerUtil.isTrue(def.methods().contains(impl.method()),
				"Can't define method not in interfaces: " + impl.method());
		LispVal lambda = methodLambda(ctx, impl.params(), impl.body());
		if (key == null) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), def.defaultVar(), lambda);
		}
		return methodStoreForm(ctx, def.methodsVar(), key, impl.method(), lambda);
	}

	/**
	 * {@code (extend-protocol P Type (method [target & args] body...)+ ...)}: one row per
	 * method per type, like {@code defmethod} rows. The type may repeat (later rows win,
	 * like the oracle).
	 */
	static LispVal extendProtocolForm(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "extend-protocol takes a protocol, a type and methods");
		ClojureLowerUtil.isTrue(items.get(1) instanceof LispSymbol, "extend-protocol takes a protocol name");
		String protocol = ((LispSymbol) items.get(1)).name();
		if (!ctx.protocols.containsKey(protocol)) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		List<LispVal> rows = new ArrayList<>();
		int at = 2;
		while (at < items.size()) {
			ClojureLowerUtil.isTrue(items.get(at) instanceof LispSymbol, "extend-protocol takes a type name");
			String target = ((LispSymbol) items.get(at)).name();
			LispVal key = extendKeyForm(ctx, target, "extend-protocol");
			at++;
			while (at < items.size() && !(items.get(at) instanceof LispSymbol)) {
				List<LispVal> impl = ClojureLowerUtil.items(items.get(at));
				if (impl == null || impl.size() < 2 || !(impl.get(0) instanceof LispSymbol)) {
					throw new LispReadException("a method implementation takes a name, parameters and a body, not "
							+ items.get(at).print());
				}
				String method = ((LispSymbol) impl.get(0)).name();
				ClojureLowerUtil.isTrue(ClojureBindingLowering.isVectorDatum(ClojureLowerUtil.stripMeta(impl.get(1))),
						"multi-arity protocol methods are not supported yet: " + method);
				List<LispVal> params = ClojureLowerUtil.bindingItems(ClojureLowerUtil.stripMeta(impl.get(1)),
						"the method " + method + " of");
				ClojureLowerUtil.isTrue(!params.isEmpty(), "a protocol method takes a target and arguments: " + method);
				rows.add(extendRow(ctx, protocol, key,
						new ClojureLowering.TypeMethod(method, impl.get(1), impl.subList(2, impl.size())),
						"extend-protocol"));
				at++;
			}
		}
		ctx.usedProtocols = true;
		if (rows.isEmpty()) {
			return ClojureLowering.NIL_CONST;
		}
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), rows);
	}

	/**
	 * {@code (extend-type T Protocol (method [target & args] body...)+ ...)}: the same
	 * rows, grouped under protocol names. A bare method group (no protocol) is refused:
	 * there is no interface to check it against.
	 */
	static LispVal extendTypeForm(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "extend-type takes a type, a protocol and methods");
		ClojureLowerUtil.isTrue(items.get(1) instanceof LispSymbol, "extend-type takes a type name");
		String target = ((LispSymbol) items.get(1)).name();
		LispVal key = extendKeyForm(ctx, target, "extend-type");
		List<ClojureLowering.ImplGroup> groups = implGroups(ctx, items.subList(2, items.size()), "extend-type");
		List<LispVal> rows = new ArrayList<>();
		for (ClojureLowering.ImplGroup group : groups) {
			for (ClojureLowering.TypeMethod impl : group.methods()) {
				rows.add(extendRow(ctx, group.protocol(), key, impl, "extend-type"));
			}
		}
		ctx.usedProtocols = true;
		if (rows.isEmpty()) {
			return ClojureLowering.NIL_CONST;
		}
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), rows);
	}

	/**
	 * {@code (extend T Protocol {method fn ...})}: the same rows from a map literal of
	 * method functions. Anything but a literal map is refused (there is nothing to walk
	 * at lower time).
	 */
	static LispVal extendForm(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 4, "extend takes a type, a protocol and a map of methods");
		ClojureLowerUtil.isTrue(items.get(1) instanceof LispSymbol, "extend takes a type name");
		ClojureLowerUtil.isTrue(items.get(2) instanceof LispSymbol, "extend takes a protocol name");
		String target = ((LispSymbol) items.get(1)).name();
		String protocol = ((LispSymbol) items.get(2)).name();
		ClojureLowering.ProtocolDef def = ctx.protocols.get(protocol);
		if (def == null) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		List<LispVal> entries = ClojureLowerUtil.items(items.get(3));
		if (entries == null || entries.isEmpty() || !ClojureLowerUtil.isSymbolNamed(entries.get(0), "%hash-map")
				|| entries.size() % 2 == 0) {
			throw new LispReadException("extend takes a map literal of methods, not " + items.get(3).print());
		}
		LispVal key = extendKeyForm(ctx, target, "extend");
		List<LispVal> rows = new ArrayList<>();
		for (int i = 1; i < entries.size(); i += 2) {
			ClojureLowerUtil.isTrue(entries.get(i) instanceof LispSymbol k && k.name().startsWith(":"),
					"extend takes keyword method names, not " + entries.get(i).print());
			String method = ((LispSymbol) entries.get(i)).name().substring(1);
			ClojureLowerUtil.isTrue(def.methods().contains(method), "Can't define method not in interfaces: " + method);
			LispVal fun = ClojureBindingLowering.fnValue(ctx, entries.get(i + 1));
			LispVal row = key == null ? ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), def.defaultVar(), fun)
					: methodStoreForm(ctx, def.methodsVar(), key, method, fun);
			rows.add(row);
		}
		ctx.usedProtocols = true;
		if (rows.isEmpty()) {
			return ClojureLowering.NIL_CONST;
		}
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), rows);
	}

	/**
	 * {@code (satisfies? Protocol x)}: table membership -- the tag's row, or the
	 * {@code Object} row an extension installed, like the oracle. The protocol is a
	 * literal name, like {@code defmethod}'s multimethod.
	 */
	static LispVal satisfiesOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "satisfies? takes a protocol and a value");
		ClojureLowerUtil.isTrue(items.get(1) instanceof LispSymbol, "satisfies? takes a protocol name");
		String protocol = ((LispSymbol) items.get(1)).name();
		ClojureLowering.ProtocolDef def = ctx.protocols.get(protocol);
		if (def == null) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		LispSymbol tag = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol inner = ctx.freshTemp();
		LispVal hit = ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), inner, miss));
		LispVal answer = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), hit, ClojureLowering.TRUE_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), def.defaultVar()), ctx.falseVariable,
						ClojureLowering.TRUE_CONST));
		ctx.usedProtocols = true;
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(
						ClojureLowerUtil.list(tag,
								ClojureLowerUtil.list(new LispSymbol(PROTOCOL_TAG), ctx.lower(items.get(2)))),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)),
						ClojureLowerUtil.list(inner,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), tag, def.methodsVar(), miss)))),
				answer);
	}

	/**
	 * {@code (reify Protocol (method [target & args] body...)+ ...)}: one fresh tag per
	 * evaluation with a row per method in each protocol's table, answering the opaque
	 * value -- a single-shot map plus methods (never {@code proxy}, which stays the
	 * {@code java:} surface).
	 */
	static LispVal reifyForm(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "reify takes a protocol and methods");
		List<ClojureLowering.ImplGroup> groups = implGroups(ctx, items.subList(1, items.size()), "reify");
		LispSymbol self = ctx.freshTemp();
		List<LispVal> prologue = new ArrayList<>();
		prologue.add(ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), REIFY_TAG,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gensym"), LispString.literal("reify")))));
		List<LispVal> body = new ArrayList<>();
		for (ClojureLowering.ImplGroup group : groups) {
			ClojureLowering.ProtocolDef def = ctx.protocols.get(group.protocol());
			if (def == null) {
				throw new LispReadException("No such protocol: " + group.protocol());
			}
			for (ClojureLowering.TypeMethod impl : group.methods()) {
				LispVal lambda = methodLambda(ctx, impl.params(), impl.body());
				body.add(methodStoreForm(ctx, def.methodsVar(),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), self), impl.method(), lambda));
			}
		}
		body.add(self);
		ctx.usedProtocols = true;
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(prologue),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), body));
	}

	// macros: defmacro, lower-time expansion, syntax-quote, macroexpand, gensym

	/**
	 * The global hierarchy value: a map of {@code :parents}, {@code :ancestors} and
	 * {@code :descendants} tables, rebound by every global {@code derive}/
	 * {@code underive}. A lone {@code %} no mangled identifier spells, so no user
	 * definition can collide with it (like the multimethod helpers).
	 */
	static final String HIERARCHY_GLOBAL = "C%H-GLOBAL";

	static final String HIERARCHY_DISPATCH = "C%H-DISPATCH";

	/** The global hierarchy value as a form. */
	static LispVal hierarchyGlobal() {
		return new LispSymbol(HIERARCHY_GLOBAL);
	}

	/**
	 * A raw call over already-built forms: the hierarchy runtime is Common Lisp, so its
	 * heads name core operations directly (never mangled, never re-lowered).
	 */
	static LispVal hfn(String head, LispVal... args) {
		List<LispVal> out = new ArrayList<>();
		out.add(new LispSymbol(head));
		out.addAll(List.of(args));
		return ClojureLowerUtil.list(out);
	}

	/** A hierarchy map key: the keyword wrapper over its spelling. */
	static LispVal hkey(String spelling) {
		return ClojureCollectionLowering.keywordForm(spelling);
	}

	static LispVal hdefun(String name, List<LispVal> params, LispVal... body) {
		List<LispVal> form = new ArrayList<>();
		form.add(ClojureLowerUtil.sym("defun"));
		form.add(new LispSymbol(name));
		form.add(ClojureLowerUtil.list(params));
		form.addAll(List.of(body));
		return ClojureLowerUtil.list(form);
	}

	/**
	 * A labels binding: {@code (name (params) body)} as one binding form.
	 */
	static LispVal hfnDef(String name, List<LispVal> params, LispVal body) {
		return ClojureLowerUtil.list(new LispSymbol(name), ClojureLowerUtil.list(params), body);
	}

	/**
	 * A labels form over prebuilt bindings: no inline nesting, so the parentheses stay
	 * countable.
	 */
	static LispVal hlabels(List<LispVal> fns, LispVal... body) {
		List<LispVal> form = new ArrayList<>();
		form.add(ClojureLowerUtil.sym("labels"));
		form.add(ClojureLowerUtil.list(fns));
		form.addAll(List.of(body));
		return ClojureLowerUtil.list(form);
	}

	/**
	 * A let over prebuilt bindings (sequential, like the rest of the lowering).
	 */
	static LispVal hlet(List<LispVal> bindings, LispVal... body) {
		List<LispVal> form = new ArrayList<>();
		form.add(ClojureLowerUtil.sym("let*"));
		form.add(ClojureLowerUtil.list(bindings));
		form.addAll(List.of(body));
		return ClojureLowerUtil.list(form);
	}

	static LispVal hmiss(LispSymbol miss) {
		return ClojureLowerUtil.list(miss, hfn("LIST", ClojureLowering.NIL_CONST));
	}

	/**
	 * The hierarchy runtime, spliced once behind the false binding when the program uses
	 * hierarchies: set helpers over the wrapped-set shape, the global hierarchy value,
	 * the transitive {@code isa?}, and the multimethod miss search (candidates through
	 * {@code isa?}, the strictly most specific, {@code prefer-method} ties). Pure
	 * lowering over the shared table runtime, so every backend runs it unchanged.
	 */
	static List<LispVal> hierarchyRuntime(ClojureLowering ctx) {
		List<LispVal> runtime = new ArrayList<>();
		LispSymbol table = new LispSymbol("table");
		LispSymbol key = new LispSymbol("key");
		LispSymbol setv = new LispSymbol("setv");
		LispSymbol member = new LispSymbol("member");
		LispSymbol members = new LispSymbol("members");
		LispSymbol nt = new LispSymbol("nt");
		LispSymbol miss = new LispSymbol("miss");
		LispSymbol found = new LispSymbol("found");
		LispSymbol pl = new LispSymbol("pl");
		LispSymbol p = new LispSymbol("p");
		LispSymbol acc = new LispSymbol("acc");
		LispSymbol x = new LispSymbol("x");
		LispSymbol lst = new LispSymbol("lst");
		LispSymbol h = new LispSymbol("h");
		LispSymbol child = new LispSymbol("child");
		LispSymbol parent = new LispSymbol("parent");
		LispSymbol parents = new LispSymbol("parents");
		LispSymbol anc = new LispSymbol("anc");
		LispSymbol desc = new LispSymbol("desc");
		LispSymbol done = new LispSymbol("done");
		LispSymbol c = new LispSymbol("c");
		LispSymbol c0 = new LispSymbol("c0");
		LispSymbol as = new LispSymbol("as");
		LispSymbol stack = new LispSymbol("stack");
		LispSymbol ps = new LispSymbol("ps");
		LispSymbol parts = new LispSymbol("parts");
		LispSymbol pc = new LispSymbol("pc");
		LispSymbol d = new LispSymbol("d");
		LispSymbol i = new LispSymbol("i");
		LispSymbol n = new LispSymbol("n");
		LispSymbol m = new LispSymbol("m");
		LispSymbol y = new LispSymbol("y");
		LispSymbol others = new LispSymbol("others");
		LispSymbol cs = new LispSymbol("cs");
		LispSymbol cands = new LispSymbol("cands");
		LispSymbol hier = new LispSymbol("hier");
		LispSymbol prefers = new LispSymbol("prefers");
		LispSymbol methods = new LispSymbol("methods");
		LispSymbol dv = new LispSymbol("dv");
		LispSymbol args = new LispSymbol("args");
		LispSymbol name = new LispSymbol("name");
		LispSymbol def = new LispSymbol("default");
		LispSymbol meth = new LispSymbol("meth");
		LispSymbol surv = new LispSymbol("surv");
		LispSymbol pick = new LispSymbol("pick");
		LispSymbol ss = new LispSymbol("ss");
		LispSymbol s = new LispSymbol("s");
		// (defun c%h-copy-table (table) ...)
		runtime.add(hdefun("C%H-COPY-TABLE", List.of(table),
				ClojureCollectionLowering.tableFromPlist(ClojureCollectionLowering.tablePlist(table))));
		// (defun c%h-empty-set () (list :c%set (make-hash-table ...)))
		runtime.add(hdefun("C%H-EMPTY-SET", List.of(),
				hfn("LIST", ClojureCollectionLowering.SET_TAG, ClojureCollectionLowering.makeTable())));
		// (defun c%h-get-set (table key) ...) with a nil-table guard
		runtime
			.add(hdefun("C%H-GET-SET", List.of(table, key),
					hfn("IF", hfn("NULL", table), hfn("C%H-EMPTY-SET"),
							ClojureLowerUtil.letForm(List.of(hmiss(miss)), List.of(ClojureLowerUtil.letForm(
									List.of(ClojureLowerUtil.list(found, hfn("GETHASH", key, table, miss))),
									List.of(hfn("IF", hfn("EQ", found, miss), hfn("C%H-EMPTY-SET"), found))))))));
		// (defun c%h-set-add (setv member) ...)
		runtime.add(hdefun("C%H-SET-ADD", List.of(setv, member),
				ClojureLowerUtil.letForm(List.of(ClojureLowerUtil.list(nt, hfn("C%H-COPY-TABLE", hfn("CADR", setv)))),
						List.of(hfn("SETF", hfn("GETHASH", member, nt), member),
								hfn("LIST", ClojureCollectionLowering.SET_TAG, nt)))));
		// (defun c%h-add-all (setv members) ...)
		LispVal addAllWalk = hfnDef("WALK", List.of(p),
				hfn("IF", hfn("NULL", p), ClojureLowering.NIL_CONST, hfn("PROGN",
						hfn("SETF", hfn("GETHASH", hfn("CAR", p), nt), hfn("CAR", p)), hfn("WALK", hfn("CDR", p)))));
		runtime.add(hdefun("C%H-ADD-ALL", List.of(setv, members),
				hlet(List.of(ClojureLowerUtil.list(nt, hfn("C%H-COPY-TABLE", hfn("CADR", setv)))),
						hlabels(List.of(addAllWalk), hfn("WALK", members)),
						hfn("LIST", ClojureCollectionLowering.SET_TAG, nt))));
		// (defun c%h-set-list (setv) ...): the members of a set wrapper
		LispVal setListWalk = hfnDef("WALK", List.of(p, acc), hfn("IF", hfn("NULL", p), acc,
				hfn("WALK", hfn("CDR", hfn("CDR", p)), hfn("CONS", hfn("CAR", p), acc))));
		runtime.add(hdefun("C%H-SET-LIST", List.of(setv),
				hlet(List.of(ClojureLowerUtil.list(pl, ClojureCollectionLowering.tablePlist(hfn("CADR", setv)))),
						hlabels(List.of(setListWalk), hfn("WALK", pl, ClojureLowering.NIL_CONST)))));
		// (defun c%h-mem? (x lst) ...): equal membership, t-or-nil
		LispVal memWalk = hfnDef("WALK", List.of(p), hfn("IF", hfn("NULL", p), ClojureLowering.NIL_CONST,
				hfn("IF", hfn("EQUAL", hfn("CAR", p), x), ClojureLowering.TRUE_CONST, hfn("WALK", hfn("CDR", p)))));
		runtime.add(hdefun("C%H-MEM?", List.of(x, lst), hlabels(List.of(memWalk), hfn("WALK", lst))));
		// (defun c%h-rebuild (parents) ...): transitive ancestors and descendants
		LispVal rebuildEach = hfnDef("EACH", List.of(ps),
				hfn("IF", hfn("NULL", ps), ClojureLowering.NIL_CONST,
						hfn("PROGN", hfn("SETF", acc, hfn("C%H-ADD-ALL", acc,
								hfn("CONS", hfn("CAR", ps),
										hfn("C%H-SET-LIST", hfn("WALK", hfn("CAR", ps), hfn("CONS", c, stack)))))),
								hfn("EACH", hfn("CDR", ps)))));
		LispVal walkFn = hfnDef("WALK", List.of(c, stack),
				hfn("IF", hfn("EQ", hfn("GETHASH", c, done, miss), miss),
						hfn("IF", hfn("C%H-MEM?", c, stack), hfn("C%H-EMPTY-SET"),
								hlet(List.of(ClojureLowerUtil.list(acc, hfn("C%H-EMPTY-SET"))),
										hlabels(List.of(rebuildEach),
												hfn("EACH", hfn("C%H-SET-LIST", hfn("C%H-GET-SET", parents, c)))),
										hfn("SETF", hfn("GETHASH", c, done), ClojureLowering.TRUE_CONST),
										hfn("SETF", hfn("GETHASH", c, anc), acc), acc)),
						hfn("C%H-GET-SET", anc, c)));
		LispVal driveFn = hfnDef("DRIVE", List.of(p), hfn("IF", hfn("NULL", p), ClojureLowering.NIL_CONST, hfn("PROGN",
				hfn("WALK", hfn("CAR", p), ClojureLowering.NIL_CONST), hfn("DRIVE", hfn("CDR", hfn("CDR", p))))));
		LispVal invEach = hfnDef("EACH2", List.of(as, c0),
				hfn("IF", hfn("NULL", as), ClojureLowering.NIL_CONST,
						hfn("PROGN",
								hfn("SETF", hfn("GETHASH", hfn("CAR", as), desc),
										hfn("C%H-SET-ADD", hfn("C%H-GET-SET", desc, hfn("CAR", as)), c0)),
								hfn("EACH2", hfn("CDR", as), c0))));
		LispVal invFn = hfnDef("INV", List.of(p), hfn("IF", hfn("NULL", p), ClojureLowering.NIL_CONST,
				hfn("PROGN",
						hlabels(List.of(invEach), hfn("EACH2", hfn("C%H-SET-LIST", hfn("CADR", p)), hfn("CAR", p))),
						hfn("INV", hfn("CDR", hfn("CDR", p))))));
		runtime.add(hdefun("C%H-REBUILD", List.of(parents),
				hlet(List.of(ClojureLowerUtil.list(anc, ClojureCollectionLowering.makeTable()),
						ClojureLowerUtil.list(desc, ClojureCollectionLowering.makeTable()),
						ClojureLowerUtil.list(done, ClojureCollectionLowering.makeTable()), hmiss(miss)),
						hlabels(List.of(walkFn, driveFn, invFn),
								hfn("DRIVE", ClojureCollectionLowering.tablePlist(parents)),
								hfn("INV", ClojureCollectionLowering.tablePlist(anc))),
						hfn("LIST", anc, desc))));
		// (defun c%h-new-map (parents anc desc) ...): the three tables as a hierarchy
		// value
		LispSymbol newParents = new LispSymbol("new-parents");
		LispSymbol newAnc = new LispSymbol("new-anc");
		LispSymbol newDesc = new LispSymbol("new-desc");
		LispVal newMap = ClojureCollectionLowering.tableFromPlist(
				hfn("LIST", hkey("parents"), newParents, hkey("ancestors"), newAnc, hkey("descendants"), newDesc));
		runtime.add(hdefun("C%H-NEW-MAP", List.of(newParents, newAnc, newDesc), newMap));
		LispVal remake = hfn("C%H-NEW-MAP", parents, hfn("CAR", parts), hfn("CAR", hfn("CDR", parts)));
		// (defun c%h-derive (h child parent) ...)
		runtime.add(hdefun("C%H-DERIVE", List.of(h, child, parent),
				hlet(List.of(ClojureLowerUtil.list(parents, hfn("C%H-COPY-TABLE", hfn("GETHASH", hkey("parents"), h)))),
						hfn("SETF", hfn("GETHASH", child, parents),
								hfn("C%H-SET-ADD", hfn("C%H-GET-SET", parents, child), parent)),
						hlet(List.of(ClojureLowerUtil.list(parts, hfn("C%H-REBUILD", parents))), remake))));
		// (defun c%h-underive (h child parent) ...)
		runtime.add(hdefun("C%H-UNDERIVE", List.of(h, child, parent),
				hlet(List.of(ClojureLowerUtil.list(parents, hfn("C%H-COPY-TABLE", hfn("GETHASH", hkey("parents"), h))),
						hmiss(miss)),
						hlet(List.of(ClojureLowerUtil.list(pc, hfn("GETHASH", child, parents, miss))),
								hfn("IF", hfn("EQ", pc, miss), ClojureLowering.NIL_CONST,
										hlet(List.of(ClojureLowerUtil.list(nt, hfn("C%H-COPY-TABLE", hfn("CADR", pc)))),
												hfn("REMHASH", parent, nt),
												hfn("SETF", hfn("GETHASH", child, parents),
														hfn("LIST", ClojureCollectionLowering.SET_TAG, nt))))),
						hlet(List.of(ClojureLowerUtil.list(parts, hfn("C%H-REBUILD", parents))), remake))));
		// (defun c%h-vec-isa? (h c p i n m) ...): element-wise vector derivation
		runtime.add(hdefun("C%H-VEC-ISA?", List.of(h, c, p, i, n, m),
				hfn("IF", hfn("NOT", hfn("EQL", n, m)), ClojureLowering.NIL_CONST,
						hfn("IF", hfn(">=", i, n), ClojureLowering.TRUE_CONST,
								hfn("IF", hfn("C%H-ISA?", h, hfn("AREF", c, i), hfn("AREF", p, i)),
										hfn("C%H-VEC-ISA?", h, c, p, hfn("+", i, new LispInteger(1)), n, m),
										ClojureLowering.NIL_CONST)))));
		// (defun c%h-isa? (h child parent) ...): equal, vector-wise, or an ancestor walk
		runtime.add(hdefun("C%H-ISA?", List.of(h, child, parent), hfn("IF", hfn("EQUAL", child, parent),
				ClojureLowering.TRUE_CONST,
				hfn("IF", hfn("AND", hfn("VECTORP", child), hfn("VECTORP", parent)),
						hfn("C%H-VEC-ISA?", h, child, parent, new LispInteger(0), hfn("LENGTH", child),
								hfn("LENGTH", parent)),
						hfn("IF",
								hfn("C%H-MEM?", parent,
										hfn("C%H-SET-LIST",
												hfn("C%H-GET-SET", hfn("GETHASH", hkey("ancestors"), h), child))),
								ClojureLowering.TRUE_CONST, ClojureLowering.NIL_CONST)))));
		// parents/ancestors/descendants reads, and the empty hierarchy value
		runtime.add(hdefun("C%H-PARENTS", List.of(h, child),
				hfn("C%H-GET-SET", hfn("GETHASH", hkey("parents"), h), child)));
		runtime.add(hdefun("C%H-ANCESTORS", List.of(h, child),
				hfn("C%H-GET-SET", hfn("GETHASH", hkey("ancestors"), h), child)));
		runtime.add(hdefun("C%H-DESCENDANTS", List.of(h, child),
				hfn("C%H-GET-SET", hfn("GETHASH", hkey("descendants"), h), child)));
		runtime.add(hdefun("C%H-EMPTY", List.of(),
				ClojureCollectionLowering.tableFromPlist(hfn("LIST", hkey("parents"),
						ClojureCollectionLowering.makeTable(), hkey("ancestors"), ClojureCollectionLowering.makeTable(),
						hkey("descendants"), ClojureCollectionLowering.makeTable()))));
		// (defun c%h-preferred? (x y prefers) ...)
		runtime.add(hdefun("C%H-PREFERRED?", List.of(x, y, prefers),
				hlet(List.of(hmiss(miss)), hfn("IF", hfn("EQ", hfn("GETHASH", hfn("CONS", x, y), prefers, miss), miss),
						ClojureLowering.NIL_CONST, ClojureLowering.TRUE_CONST))));
		// (defun c%h-more-specific? (c d hier) ...): c descends from d, not vice versa
		runtime.add(hdefun("C%H-MORE-SPECIFIC?", List.of(c, d, hier),
				hfn("IF", hfn("C%H-ISA?", hier, c, d),
						hfn("IF", hfn("C%H-ISA?", hier, d, c), ClojureLowering.NIL_CONST, ClojureLowering.TRUE_CONST),
						ClojureLowering.NIL_CONST)));
		// (defun c%h-survivors (cands hier prefers) ...): undominated candidates
		LispVal badFn = hfnDef("BAD?", List.of(d, others),
				hfn("IF", hfn("NULL", others), ClojureLowering.NIL_CONST,
						hfn("IF",
								hfn("AND", hfn("C%H-MORE-SPECIFIC?", hfn("CAR", others), d, hier),
										hfn("NOT", hfn("EQUAL", hfn("CAR", others), d)),
										hfn("NOT", hfn("C%H-PREFERRED?", d, hfn("CAR", others), prefers))),
								ClojureLowering.TRUE_CONST, hfn("BAD?", d, hfn("CDR", others)))));
		LispVal keepFn = hfnDef("KEEP", List.of(cs, acc), hfn("IF", hfn("NULL", cs), acc, hfn("KEEP", hfn("CDR", cs),
				hfn("IF", hfn("BAD?", hfn("CAR", cs), cands), acc, hfn("CONS", hfn("CAR", cs), acc)))));
		runtime.add(hdefun("C%H-SURVIVORS", List.of(cands, hier, prefers),
				hlabels(List.of(badFn, keepFn), hfn("KEEP", cands, ClojureLowering.NIL_CONST))));
		// (defun c%h-candidates (methods hier dv) ...): methods the value descends from
		LispVal candWalk = hfnDef("WALK", List.of(p, acc),
				hfn("IF", hfn("NULL", p), acc, hfn("WALK", hfn("CDR", hfn("CDR", p)),
						hfn("IF", hfn("C%H-ISA?", hier, dv, hfn("CAR", p)), hfn("CONS", hfn("CAR", p), acc), acc))));
		runtime.add(hdefun("C%H-CANDIDATES", List.of(methods, hier, dv),
				hlet(List.of(ClojureLowerUtil.list(pl, ClojureCollectionLowering.tablePlist(methods))),
						hlabels(List.of(candWalk), hfn("WALK", pl, ClojureLowering.NIL_CONST)))));
		// (defun c%h-pick (surv prefers) ...): the survivor preferred over every other
		LispVal beatsFn = hfnDef("BEATS-ALL?", List.of(s, others),
				hfn("IF", hfn("NULL", others), ClojureLowering.TRUE_CONST,
						hfn("IF", hfn("EQUAL", hfn("CAR", others), s), hfn("BEATS-ALL?", s, hfn("CDR", others)),
								hfn("IF", hfn("C%H-PREFERRED?", s, hfn("CAR", others), prefers),
										hfn("BEATS-ALL?", s, hfn("CDR", others)), ClojureLowering.NIL_CONST))));
		LispVal findFn = hfnDef("FIND", List.of(ss), hfn("IF", hfn("NULL", ss), ClojureLowering.NIL_CONST,
				hfn("IF", hfn("BEATS-ALL?", hfn("CAR", ss), surv), hfn("CAR", ss), hfn("FIND", hfn("CDR", ss)))));
		runtime.add(hdefun("C%H-PICK", List.of(surv, prefers), hlabels(List.of(beatsFn, findFn), hfn("FIND", surv))));
		// (defun c%h-dispatch (name methods prefers hier default dv args) ...)
		LispVal noMethod = hfn("ERROR",
				hfn("CONCATENATE", ClojureLowerUtil.quoted("string"), LispString.literal("No method in "), name,
						LispString.literal(" for dispatch value: "),
						hfn("RONTOLISP::%CLOJURE-STR-OF", dv, LispString.literal("nil"), ClojureLowering.NIL_CONST)));
		LispVal ambiguous = hfn("ERROR",
				hfn("CONCATENATE", ClojureLowerUtil.quoted("string"),
						LispString.literal("Multiple methods in multimethod '"), name,
						LispString.literal("' match dispatch value: "),
						hfn("RONTOLISP::%CLOJURE-STR-OF", dv, LispString.literal("nil"), ClojureLowering.NIL_CONST),
						LispString.literal(", and neither is preferred")));
		runtime.add(hdefun(HIERARCHY_DISPATCH, List.of(name, methods, prefers, hier, def, dv, args), hlet(
				List.of(ClojureLowerUtil.list(cands, hfn("C%H-CANDIDATES", methods, hier, dv))),
				hfn("IF", hfn("NULL", cands),
						hlet(List.of(hmiss(miss), ClojureLowerUtil.list(meth, hfn("GETHASH", def, methods, miss))),
								hfn("IF", hfn("EQ", meth, miss), noMethod, hfn("APPLY", meth, args))),
						hfn("IF", hfn("NULL", hfn("CDR", cands)),
								hfn("APPLY", hfn("GETHASH", hfn("CAR", cands), methods), args),
								hlet(List.of(ClojureLowerUtil.list(surv, hfn("C%H-SURVIVORS", cands, hier, prefers))),
										hfn("IF", hfn("NULL", surv), ambiguous, hlet(
												List.of(ClojureLowerUtil.list(pick, hfn("C%H-PICK", surv, prefers))),
												hfn("IF", hfn("NULL", pick), ambiguous,
														hfn("APPLY", hfn("GETHASH", pick, methods), args))))))))));
		// the global hierarchy value, bound after its builders
		runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), hierarchyGlobal(), hfn("C%H-EMPTY")));
		return runtime;
	}

	/**
	 * A hierarchy call: {@code derive}/{@code underive} (two forms on the global
	 * hierarchy, three returning an updated hierarchy value), {@code isa?} (two or three,
	 * answering {@code T}-or-false), {@code parents}/{@code ancestors}/
	 * {@code descendants} (one or two, answering sets) and {@code make-hierarchy} (none,
	 * a fresh hierarchy value).
	 */
	static LispVal hierarchyOp(ClojureLowering ctx, List<LispVal> items) {
		String name = ((LispSymbol) items.get(0)).name();
		int n = items.size() - 1;
		ctx.usedHierarchy = true;
		return switch (name) {
			case "derive" -> {
				if (n == 2) {
					yield ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), hierarchyGlobal(),
									ClojureLowerUtil.list(new LispSymbol("C%H-DERIVE"), hierarchyGlobal(),
											ctx.lower(items.get(1)), ctx.lower(items.get(2)))),
							ClojureLowering.NIL_CONST);
				}
				ClojureLowerUtil.isTrue(n == 3, "derive takes a child and a parent, or a hierarchy and both");
				yield ClojureLowerUtil.list(new LispSymbol("C%H-DERIVE"), ctx.lower(items.get(1)),
						ctx.lower(items.get(2)), ctx.lower(items.get(3)));
			}
			case "underive" -> {
				if (n == 2) {
					yield ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), hierarchyGlobal(),
									ClojureLowerUtil.list(new LispSymbol("C%H-UNDERIVE"), hierarchyGlobal(),
											ctx.lower(items.get(1)), ctx.lower(items.get(2)))),
							ClojureLowering.NIL_CONST);
				}
				ClojureLowerUtil.isTrue(n == 3, "underive takes a child and a parent, or a hierarchy and both");
				yield ClojureLowerUtil.list(new LispSymbol("C%H-UNDERIVE"), ctx.lower(items.get(1)),
						ctx.lower(items.get(2)), ctx.lower(items.get(3)));
			}
			case "isa?" -> {
				ClojureLowerUtil.isTrue(n == 2 || n == 3, "isa? takes a child and a parent, or a hierarchy and both");
				LispVal hier = n == 3 ? ctx.lower(items.get(1)) : hierarchyGlobal();
				LispVal child = ctx.lower(items.get(n == 3 ? 2 : 1));
				LispVal parent = ctx.lower(items.get(n == 3 ? 3 : 2));
				yield ctx.booleanAnswer(ClojureLowerUtil.list(new LispSymbol("C%H-ISA?"), hier, child, parent));
			}
			case "parents", "ancestors", "descendants" -> {
				ClojureLowerUtil.isTrue(n == 1 || n == 2, name + " takes a child, or a hierarchy and a child");
				LispVal hier = n == 2 ? ctx.lower(items.get(1)) : hierarchyGlobal();
				LispVal child = ctx.lower(items.get(n == 2 ? 2 : 1));
				yield ClojureLowerUtil.list(new LispSymbol("C%H-" + name.toUpperCase(java.util.Locale.ROOT)), hier,
						child);
			}
			case "make-hierarchy" -> {
				ClojureLowerUtil.isTrue(n == 0, "make-hierarchy takes no arguments");
				yield ClojureLowerUtil.list(new LispSymbol("C%H-EMPTY"));
			}
			default -> throw new LispReadException("unknown name: " + name);
		};
	}

	/**
	 * {@code (prefer-method name x y)}: {@code x} wins over {@code y} when both match a
	 * dispatch of {@code name}. The multimethod must be defined -- forward order aside,
	 * the pre-scan declares every {@code defmulti} first -- and answers itself, like
	 * {@code remove-method}.
	 */
	static LispVal preferMethodOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 4, "prefer-method takes a multimethod and two dispatch values");
		String name = ClojureLowerUtil.plainName(items.get(1), "prefer-method");
		ClojureLowerUtil.isTrue(ctx.known(name), "No such multimethod: " + name);
		LispSymbol prefers = new LispSymbol(ClojureLowering.mangle(name) + "%prefers");
		ctx.usedHierarchy = true;
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), dispatchKeyForm(ctx, items.get(2)),
										dispatchKeyForm(ctx, items.get(3))),
								prefers),
						ClojureLowering.TRUE_CONST),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowerUtil.idSym(name)));
	}

	// platform: Java interop over the java: surface

}
