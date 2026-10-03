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
		ClojureLowering.TypeDef known = ctx.typeDefOf(name);
		if (known != null) {
			// a record or deftype name tests the dispatch tag, like a class
			ctx.usedProtocols = true;
			return ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("equal"),
					ClojureLowerUtil.list(new LispSymbol(ClojureProtocolLowering.PROTOCOL_TAG), lowered),
					ClojureProtocolLowering.typeTagForm(known.tagSpelling())));
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
			case "Keyword" -> ClojureFilterLowering.keywordTest(lowered);
			case "Symbol" -> ClojureFnLowering.symbolRaw(ctx, lowered);
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
		branches.add(ClojureLowerUtil.list(ClojureProtocolLowering.isRecordForm(one),
				ClojureProtocolLowering.typedTagOf(one)));
		branches.add(ClojureLowerUtil.list(ClojureProtocolLowering.isDeftypeForm(one),
				ClojureProtocolLowering.typedTagOf(one)));
		branches.add(ClojureLowerUtil.list(ClojureProtocolLowering.isReifyForm(one),
				ClojureCollectionLowering.keywordForm("reify")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), one),
				ctx.inDispatchFn ? ClojureLowering.NIL_CONST : ClojureCollectionLowering.keywordForm("nil")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), one, ctx.falseVariable),
				ClojureCollectionLowering.keywordForm("boolean")));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), one, ClojureLowering.TRUE_CONST),
				ClojureCollectionLowering.keywordForm("boolean")));
		branches.add(ClojureLowerUtil.list(ClojureFilterLowering.keywordTest(one),
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
		return ctx.typeKeyOf(name) == null
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
		ClojureLowering.TypeDef type = ctx.typeDefOf(name);
		if (type != null) {
			return ClojureProtocolLowering.typeTagForm(type.tagSpelling());
		}
		if (isObjectClassName(ctx, name)) {
			return ClojureCollectionLowering.keywordForm("object");
		}
		boolean classlike = ctx.ns().classNames.containsKey(name) || ClojureNamespaceLowering.JAVA_LANG.contains(name)
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
	 * @param name the defined var's key
	 * @param dynamic whether the name is {@code ^:dynamic}
	 * @param valueDatum the {@code fn} datum, or null when there is none
	 */
	static void recordClassDispatchFn(ClojureLowering ctx, String name, boolean dynamic, @Nullable LispVal valueDatum) {
		if (dynamic || valueDatum == null) {
			ctx.classDispatchFns.remove(name);
			return;
		}
		if (valueDatum instanceof LispSymbol s && !s.name().startsWith(":")) {
			LispVal target = recordedDispatchFn(ctx, s.name());
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
			LispVal recorded = recordedDispatchFn(ctx, s.name());
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
	 * The recorded dispatch datum of the definition a name resolves to -- only one of the
	 * current namespace, since the datum re-lowers here and its names resolve here -- or
	 * null.
	 */
	static @Nullable LispVal recordedDispatchFn(ClojureLowering ctx, String name) {
		String key = ctx.lookupVar(name);
		if (key == null || !key.startsWith(ctx.currentNs + "/")) {
			return null;
		}
		return ctx.classDispatchFns.get(key);
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
		LispVal recorded = recordedDispatchFn(ctx, name);
		String key = ctx.lookupVar(name);
		if (recorded == null || key == null || !ctx.inliningDispatch.add(key)) {
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
			ctx.inliningDispatch.remove(key);
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
		String key = ctx.intern(name, ClojureLowerUtil.nameIsPrivate(items.get(1)));
		ctx.globals.put(key, ClojureLowering.Kind.FUNCTION);
		ctx.macros.remove(key); // a definition wins over the macro it shadows
		LispSymbol fn = ClojureLowering.varSym(key);
		LispSymbol methods = tableGlobal(key, "%methods");
		LispSymbol fallback = tableGlobal(key, "%default");
		LispSymbol prefers = tableGlobal(key, "%prefers");
		LispSymbol object = tableGlobal(key, "%object");
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
		LispVal hierarchyForm = hierarchyDatum == null ? ClojureHierarchyLowering.hierarchyGlobal()
				: ctx.lower(hierarchyDatum);
		LispSymbol args = ctx.freshTemp();
		LispSymbol disp = ctx.freshTemp();
		LispSymbol raw = ctx.freshTemp();
		LispSymbol found = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispVal missCall = ClojureLowerUtil.list(new LispSymbol(ClojureHierarchyLowering.HIERARCHY_DISPATCH),
				LispString.literal(name), methods, prefers, hierarchyForm, fallback, disp, args);
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
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
								ClojureCollectionLowering.lookupKey(disp, methods), methods, miss)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), found, miss), missForm,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), found, args)));
		List<LispVal> forms = new ArrayList<>();
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), methods, ClojureCollectionLowering.makeTable()));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), fallback, defaultForm));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), prefers, ClojureCollectionLowering.makeTable()));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), object, ClojureLowering.NIL_CONST));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), fn,
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

	/**
	 * The var key of the multimethod a {@code defmethod} (or {@code remove-method},
	 * {@code get-method}, {@code prefer-method}) names: the current namespace's own, a
	 * referred one, or one reached through an alias or its namespace's full name.
	 */
	static String multimethodKey(ClojureLowering ctx, String name) {
		String key = ctx.isLocal(name) ? null : ctx.resolveVar(name);
		if (key == null) {
			throw new LispReadException("No such multimethod: " + name);
		}
		return key;
	}

	/**
	 * One of a multimethod's globals: its var's symbol plus a suffix no identifier can
	 * spell ({@code %methods}, {@code %default}, {@code %prefers}, {@code %object}).
	 */
	static LispSymbol tableGlobal(String key, String suffix) {
		return new LispSymbol(ClojureLowering.varSym(key).name() + suffix);
	}

	static LispVal defmethodForm(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 4,
				"defmethod takes a name, a dispatch value, a parameter vector and a body");
		String name = ClojureLowerUtil.plainName(items.get(1), "defmethod");
		String key = multimethodKey(ctx, name);
		LispSymbol methods = tableGlobal(key, "%methods");
		LispVal lambda = methodLambda(ctx, items.get(3), items.subList(4, items.size()));
		LispVal keyDatum = items.get(2);
		if (keyDatum instanceof LispSymbol s && isObjectClassName(ctx, s.name())) {
			// the table row (for get-method) and the catch-all slot the dispatcher
			// tries past the hierarchy search but ahead of the default
			LispSymbol object = tableGlobal(key, "%object");
			return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), List.of(
					ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
									ClojureCollectionLowering.keywordForm("object"), methods),
							lambda),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), object, lambda)));
		}
		return ClojureCollectionLowering.tablePut(methods, dispatchKeyForm(ctx, keyDatum), lambda);
	}

	static LispVal removeMethodOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "remove-method takes a multimethod and a dispatch value");
		String name = ClojureLowerUtil.plainName(items.get(1), "remove-method");
		String key = multimethodKey(ctx, name);
		LispSymbol methods = tableGlobal(key, "%methods");
		LispVal keyDatum = items.get(2);
		if (keyDatum instanceof LispSymbol s && isObjectClassName(ctx, s.name())) {
			LispSymbol object = tableGlobal(key, "%object");
			return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"),
					List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("remhash"),
							ClojureCollectionLowering.keywordForm("object"), methods),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), object, ClojureLowering.NIL_CONST),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowering.varSym(key))));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("remhash"),
						ClojureCollectionLowering.lookupKey(dispatchKeyForm(ctx, keyDatum), methods), methods),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowering.varSym(key)));
	}

	static LispVal getMethodOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "get-method takes a multimethod and a dispatch value");
		String name = ClojureLowerUtil.plainName(items.get(1), "get-method");
		LispSymbol methods = tableGlobal(multimethodKey(ctx, name), "%methods");
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
				ClojureCollectionLowering.lookupKey(dispatchKeyForm(ctx, items.get(2)), methods), methods,
				ClojureLowering.NIL_CONST);
	}

	// protocols/records: defprotocol/defrecord/deftype/reify/extend/satisfies? over the
	// table runtime

}
