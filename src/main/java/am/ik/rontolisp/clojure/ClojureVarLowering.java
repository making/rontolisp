package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Vars as values: {@code #'x} / {@code (var x)}, the metadata a definition gives its var,
 * and {@code clojure.core/test} over it.
 *
 * <p>
 * A definition records its var's metadata at lower time ({@link #record}): the oracle's
 * {@code :arglists}, the docstring as {@code :doc}, the name's reader metadata and attr
 * map, then {@code :line}/{@code :column}/{@code :file}/{@code :name}/{@code :ns}. A map
 * whose values are all constants stays a datum the {@code #'} site lowers; one with an
 * evaluated value (a {@code :test} fn) is stored in a global beside the var at the
 * definition, in the definition's scope, and the site reads that global. A site lowered
 * after a redefinition therefore sees the newest metadata.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureVarLowering {

	private ClojureVarLowering() {
	}

	/** The suffix of the global holding a var's evaluated metadata. */
	private static final String META_SUFFIX = "%meta";

	/**
	 * A var's recorded metadata: the map datum, whether the definition evaluated it into
	 * the var's meta global (some value is no constant), and which definition of the name
	 * recorded it (1 for the first), so a site of an older one never replaces the
	 * metadata a newer one or an {@code alter-meta!} gave the var.
	 */
	record VarMeta(LispVal datum, boolean stored, int generation) {
	}

	/** The global a definition stores its var's evaluated metadata in. */
	static LispSymbol metaSym(String key) {
		return new LispSymbol(ClojureLowering.varSym(key).name() + META_SUFFIX);
	}

	/** Whether the lowered form is the store of a var's evaluated metadata. */
	static boolean isMetaStore(LispVal form) {
		List<LispVal> parts = ClojureLowerUtil.items(form);
		return parts != null && parts.size() == 3 && ClojureLowerUtil.isSymbolNamed(parts.get(0), "SETQ")
				&& parts.get(1) instanceof LispSymbol s && s.name().endsWith(META_SUFFIX);
	}

	/**
	 * Records a definition's var metadata, in the oracle's entry order.
	 * @param ctx the hub
	 * @param key the var key
	 * @param form the definition datum (its position), or null
	 * @param nameDatum the name as written, reader metadata included
	 * @param arglists the parameter vector datums of a {@code defn}, or null for a
	 * {@code def}
	 * @param doc the docstring, or null
	 * @param attrMap the attr map datum, or null
	 * @param isPrivate whether the definition adds {@code :private true} ({@code defn-})
	 * @param isMacro whether the var is a macro's ({@code :macro true})
	 * @return the definition's extra forms: the store of the evaluated metadata, or none
	 */
	static List<LispVal> record(ClojureLowering ctx, String key, @Nullable LispVal form, LispVal nameDatum,
			@Nullable List<LispVal> arglists, @Nullable LispString doc, @Nullable LispVal attrMap, boolean isPrivate,
			boolean isMacro) {
		Map<LispVal, LispVal> entries = new LinkedHashMap<>();
		if (arglists != null) {
			// the parameters' type hints stay out (the oracle keeps them on each
			// symbol): no program printing or comparing :arglists makes a symbol
			// carrying metadata for them
			List<LispVal> vectors = new ArrayList<>();
			for (LispVal params : arglists) {
				vectors.add(ClojureLowerUtil.stripMetaDeep(params));
			}
			entries.put(keyword("arglists"),
					ClojureLowerUtil.list(new LispSymbol("quote"), ClojureLowerUtil.list(vectors)));
		}
		putAll(entries, nameMetaEntries(nameDatum));
		if (isPrivate) {
			entries.put(keyword("private"), new LispSymbol("true"));
		}
		if (doc != null) {
			entries.put(keyword("doc"), doc);
		}
		if (attrMap != null) {
			List<LispVal> parts = ClojureLowerUtil.items(attrMap, List.of());
			putAll(entries, parts.subList(Math.min(1, parts.size()), parts.size()));
		}
		LispVal tag = entries.get(keyword("tag"));
		if (tag instanceof LispSymbol tagName && ctx.typeKeyOf(tagName.name()) != null) {
			// {:tag Type} naming a record or deftype: the oracle stores the class,
			// which has no spelling here (a bare name in value position stays an
			// unknown-name refusal, ClojureInteropLowering.classValue), so keep the
			// quoted symbol, like ^Type reader metadata does
			// (ClojureCoreLowering.metaEntries)
			entries.put(keyword("tag"), ClojureLowerUtil.list(new LispSymbol("quote"), tagName));
		}
		boolean stored = false;
		for (LispVal value : entries.values()) {
			stored |= !isConstant(value);
		}
		SourceLocation at = form == null || ctx.reader == null ? null : ctx.reader.locate(form);
		if (at != null) {
			entries.put(keyword("line"), new LispInteger(at.line()));
			entries.put(keyword("column"), new LispInteger(at.column()));
		}
		entries.put(keyword("file"), LispString.literal(fileOf(ctx, at)));
		int slash = key.indexOf('/');
		entries.put(keyword("name"),
				ClojureLowerUtil.list(new LispSymbol("quote"), new LispSymbol(key.substring(slash + 1))));
		entries.put(keyword("ns"),
				ClojureLowerUtil.list(new LispSymbol("quote"), new LispSymbol(key.substring(0, slash))));
		if (isMacro) {
			entries.put(keyword("macro"), new LispSymbol("true"));
		}
		List<LispVal> map = new ArrayList<>();
		map.add(new LispSymbol("%hash-map"));
		for (Map.Entry<LispVal, LispVal> entry : entries.entrySet()) {
			map.add(entry.getKey());
			map.add(entry.getValue());
		}
		LispVal datum = ClojureLowerUtil.list(map);
		VarMeta older = ctx.varMetas.get(key);
		ctx.varMetas.put(key, new VarMeta(datum, stored, older == null ? 1 : older.generation() + 1));
		if (!stored) {
			return List.of();
		}
		return List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), metaSym(key), ctx.lower(datum)));
	}

	/**
	 * The {@code :file} the oracle records: a required namespace's root-relative
	 * resource, else the file the reader read (the oracle absolutizes it), else
	 * {@code NO_SOURCE_PATH}.
	 */
	private static String fileOf(ClojureLowering ctx, @Nullable SourceLocation at) {
		String loading = ctx.loadingNamespaces.peek();
		if (loading != null) {
			return ctx.fileOf(loading);
		}
		return at != null && at.file() != null ? at.file() : "NO_SOURCE_PATH";
	}

	/** The name's reader metadata as entries, inner layer first (outer ones win). */
	private static List<LispVal> nameMetaEntries(LispVal nameDatum) {
		List<LispVal> layers = new ArrayList<>();
		List<LispVal> parts = ClojureLowerUtil.items(nameDatum);
		while (parts != null && parts.size() == 3
				&& ClojureLowerUtil.isSymbolNamed(parts.get(0), ClojureLowerUtil.READER_META)) {
			layers.add(parts.get(2));
			parts = ClojureLowerUtil.items(parts.get(1));
		}
		List<LispVal> entries = new ArrayList<>();
		for (int i = layers.size() - 1; i >= 0; i--) {
			entries.addAll(ClojureCoreLowering.metaEntries(layers.get(i)));
		}
		return entries;
	}

	private static void putAll(Map<LispVal, LispVal> entries, List<LispVal> pairs) {
		ClojureLowerUtil.isTrue(pairs.size() % 2 == 0, "a metadata map takes key/value pairs");
		for (int i = 0; i < pairs.size(); i += 2) {
			// a re-put keeps the first position and takes the later value, like the
			// oracle's conj onto an array map
			entries.put(pairs.get(i), pairs.get(i + 1));
		}
	}

	/**
	 * Whether a metadata value lowers the same anywhere: a literal, a plain keyword, or
	 * quoted data. An {@code ::}-keyword resolves against the namespace, and anything
	 * else evaluates, so either is stored at the definition.
	 */
	private static boolean isConstant(LispVal value) {
		if (value instanceof LispString || value instanceof LispInteger || value instanceof LispDouble
				|| value instanceof LispChar) {
			return true;
		}
		if (value instanceof LispSymbol s) {
			String name = s.name();
			return name.startsWith(":") && !name.startsWith("::") || name.equals("true") || name.equals("false")
					|| name.equals("nil");
		}
		return value instanceof LispCons cons && ClojureLowerUtil.isSymbolNamed(cons.car(), "quote");
	}

	private static LispSymbol keyword(String name) {
		return new LispSymbol(":" + name);
	}

	/**
	 * {@code #'x} / {@code (var x)}: the interned var of a program definition, its root
	 * read through a closure over the name's value here and its metadata the newest
	 * definition's. Locals are no vars (the oracle resolves past them), and another
	 * namespace's private var is reachable, like the oracle's. A name no program var
	 * claims, or a {@code clojure.core/} spelling, is the core var ({@link #coreVarOf}).
	 * @param ctx the hub
	 * @param items the form, head included
	 * @return the lowered var
	 */
	static LispVal varOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "var takes one symbol");
		if (!(ClojureLowerUtil.stripMeta(items.get(1)) instanceof LispSymbol target)) {
			throw new LispReadException("var takes a symbol: " + items.get(1).print());
		}
		String name = target.name();
		String core = ctx.coreSpelling(name);
		String key = core != null ? null : ctx.lookupVar(name);
		if (key != null) {
			return varOfKey(ctx, key);
		}
		if (core == null && ClojureCoreNames.contains(name)) {
			core = name;
		}
		if (core == null) {
			LispVal library = libraryVarOf(ctx, name);
			if (library != null) {
				return library;
			}
			if (ctx.isLocal(name) && ctx.isLocalVar(name)) {
				// a with-local-vars cell in a lexically enclosing scope: the
				// local holds the cell object itself
				return ctx.symOf(name);
			}
			throw new LispReadException("Unable to resolve var: " + name + " in this context");
		}
		return coreVarOf(ctx, core);
	}

	/**
	 * The var of a library the lowering implements ({@code clojure.string},
	 * {@code clojure.set}, {@code clojure.edn} ...) a name names -- qualified through an
	 * alias or the full name, or referred -- whose root is the function value the name
	 * lowers to in value position; or null for any other name, and for a var with no
	 * function value (a macro). camel-snake-kebab's macro body calls
	 * {@code (resolve 'clojure.string/capitalize)}.
	 */
	private static @Nullable LispVal libraryVarOf(ClojureLowering ctx, String name) {
		ClojureLowering.VarRef ref;
		try {
			ref = ClojureNamespaceLowering.resolveQualified(ctx, name);
		}
		catch (LispReadException ex) {
			return null; // a var the library does not have
		}
		if (ref == null && ClojureLowering.qualifierSlash(name) < 0 && !ctx.isLocal(name)) {
			ref = ClojureNamespaceLowering.libraryRefer(ctx, name);
		}
		if (ref == null) {
			return null;
		}
		LispVal root;
		try {
			root = ClojureNamespaceLowering.namespaceValue(ctx, ref);
		}
		catch (LispReadException ex) {
			return null;
		}
		LispVal metaForm = ctx.lower(ClojureLowerUtil.list(new LispSymbol("%hash-map"), keyword("name"),
				ClojureLowerUtil.list(new LispSymbol("quote"), new LispSymbol(ref.var())), keyword("ns"),
				ClojureLowerUtil.list(new LispSymbol("quote"), new LispSymbol(ref.ns()))));
		LispVal getter = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(), root);
		return ClojureLowerUtil.list(runtime("VAR"), LispString.literal(ref.ns() + "/" + ref.var()), getter, metaForm);
	}

	/**
	 * {@code (resolve sym)}: a quoted symbol resolves while the program lowers
	 * ({@link #resolved}), in the namespace the call lowers in; any other argument calls
	 * the run-time library's {@code resolve}, which refuses in the program and resolves
	 * in the macro-time environment ({@link ClojureMacroLowering#evalLowering}).
	 * @param ctx the hub
	 * @param items the form, head included
	 * @return the lowered call
	 */
	static LispVal resolveOf(ClojureLowering ctx, List<LispVal> items) {
		if (items.size() == 3) {
			// (resolve env sym) is the oracle's (ns-resolve *ns* env sym)
			return nsResolveOf(ctx, List.of(new LispSymbol("ns-resolve"), new LispSymbol("clojure.core/*ns*"),
					items.get(1), items.get(2)));
		}
		ClojureLowerUtil.isTrue(items.size() == 2,
				"Wrong number of args (" + (items.size() - 1) + ") passed to: clojure.core/resolve");
		List<LispVal> quoted = ClojureLowerUtil.items(items.get(1));
		if (quoted != null && quoted.size() == 2 && ClojureLowerUtil.isSymbolNamed(quoted.get(0), "quote")
				&& ClojureLowerUtil.stripMeta(quoted.get(1)) instanceof LispSymbol target
				&& !target.name().startsWith(":")) {
			return resolved(ctx, target.name());
		}
		return ClojureLowerUtil.list(ClojureMacroLowering.RESOLVE, ctx.lower(items.get(1)));
	}

	/** {@code resolve} as a value: every argument is computed. */
	static LispVal resolveValue() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureMacroLowering.RESOLVE);
	}

	/** The run-time library's {@code find-var} of a computed symbol. */
	private static final LispSymbol FIND_VAR = new LispSymbol("RONTOLISP::%CLOJURE-FIND-VAR");

	/**
	 * {@code (find-var sym)}, the oracle's {@code Var.find}: a quoted qualified symbol
	 * resolves while the program lowers -- the var of an existing namespace, nil for one
	 * it lacks, the oracle's {@code No such namespace} for a namespace that does not
	 * exist (an alias is no namespace), {@code Symbol must be namespace-qualified} for an
	 * unqualified symbol, both run-time refusals; any other argument calls the run-time
	 * library's {@code find-var}, which refuses in the program and resolves while a macro
	 * expands (hiccup's {@code compile-multi}).
	 * @param ctx the hub
	 * @param items the form, head included
	 * @return the lowered call
	 */
	static LispVal findVarOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2,
				"Wrong number of args (" + (items.size() - 1) + ") passed to: clojure.core/find-var");
		List<LispVal> quoted = ClojureLowerUtil.items(items.get(1));
		if (quoted != null && quoted.size() == 2 && ClojureLowerUtil.isSymbolNamed(quoted.get(0), "quote")
				&& ClojureLowerUtil.stripMeta(quoted.get(1)) instanceof LispSymbol target
				&& !target.name().startsWith(":")) {
			String name = target.name();
			int slash = ClojureLowering.qualifierSlash(name);
			if (slash < 0) {
				return ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_ARGUMENT,
						LispString.literal("Symbol must be namespace-qualified"));
			}
			String ns = name.substring(0, slash);
			if (!ctx.knowsNamespace(ns) && !ClojureNamespaceLowering.isKnownNamespace(ns)) {
				return ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_ARGUMENT,
						LispString.literal("No such namespace: " + ns));
			}
			return resolved(ctx, name);
		}
		return ClojureLowerUtil.list(FIND_VAR, ctx.lower(items.get(1)));
	}

	/** {@code find-var} as a value: every argument is computed. */
	static LispVal findVarValue() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"),
				new LispSymbol("RONTOLISP::%CLOJURE-FIND-VAR-V"));
	}

	/**
	 * The run-time library's {@code ns-resolve} of a computed namespace or symbol: a nil
	 * namespace is the oracle's {@code NullPointerException}, any other is refused there
	 * and resolved by the lowering at macro time.
	 */
	static final String NS_RESOLVE = "RONTOLISP::%CLOJURE-NS-RESOLVE";

	/** {@code ns-resolve} as a value: every argument is computed. */
	static final String NS_RESOLVE_V = "RONTOLISP::%CLOJURE-NS-RESOLVE-V";

	/**
	 * {@code (ns-resolve ns sym)} / {@code (ns-resolve ns env sym)}: a quoted symbol in a
	 * namespace the lowering knows -- a quoted name, {@code (find-ns 'n)},
	 * {@code (the-ns 'n)} or {@code *ns*} -- resolves while the program lowers, like
	 * {@code resolve} in that namespace ({@link #resolvedIn}); one no {@code ns} or
	 * {@code in-ns} created above the call and the program required none of is the
	 * oracle's failure there ({@code find-ns}'s nil, a {@code NullPointerException};
	 * {@code the-ns}'s {@code No namespace: n found}). Anything else calls the run-time
	 * library's {@code ns-resolve} ({@link #NS_RESOLVE}). An environment holding the
	 * symbol answers nil first, the oracle's {@code contains?} test.
	 * @param ctx the hub
	 * @param items the form, head included
	 * @return the lowered call
	 */
	static LispVal nsResolveOf(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		ClojureCoreLowering.arity("ns-resolve", n, 2, 3);
		LispVal symDatum = items.get(n);
		String target = quotedSymbol(symDatum);
		NamespaceDesignator designator = designatorOf(ctx, items.get(1));
		if (target != null && designator != null) {
			LispVal resolved = resolvedIn(ctx, designator, target);
			if (n == 2) {
				return resolved;
			}
			LispSymbol env = ctx.freshTemp();
			LispVal held = ClojureCollectionLowering.containsForm(ctx, env, ctx.lower(symDatum));
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(ClojureLowerUtil.list(env, ctx.lower(items.get(2)))),
					ctx.ifFalsey(held, ClojureLowering.NIL_CONST, resolved));
		}
		LispSymbol ns = ctx.freshTemp();
		LispSymbol sym = ctx.freshTemp();
		LispVal call = ClojureLowerUtil.list(new LispSymbol(NS_RESOLVE), ns, sym);
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(ClojureLowerUtil.list(ns, ctx.lower(items.get(1))));
		LispVal body = call;
		if (n == 3) {
			LispSymbol env = ctx.freshTemp();
			bindings.add(ClojureLowerUtil.list(env, ctx.lower(items.get(2))));
			body = ctx.ifFalsey(ClojureCollectionLowering.containsForm(ctx, env, sym), ClojureLowering.NIL_CONST, call);
		}
		bindings.add(ClojureLowerUtil.list(sym, ctx.lower(symDatum)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings), body);
	}

	/** {@code ns-resolve} as a value: every argument is computed. */
	static LispVal nsResolveValue() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), new LispSymbol(NS_RESOLVE_V));
	}

	/** The name a quoted symbol datum spells, or null for any other datum. */
	private static @Nullable String quotedSymbol(LispVal datum) {
		List<LispVal> quoted = ClojureLowerUtil.items(datum);
		if (quoted != null && quoted.size() == 2 && ClojureLowerUtil.isSymbolNamed(quoted.get(0), "quote")
				&& ClojureLowerUtil.stripMeta(quoted.get(1)) instanceof LispSymbol target
				&& !target.name().startsWith(":")) {
			return target.name();
		}
		return null;
	}

	/**
	 * A namespace the lowering knows at the call: its name, and whether the spelling
	 * finds it ({@code find-ns}, nil when none exists) or names it ({@code the-ns} or a
	 * quoted name, refused when none exists).
	 */
	private record NamespaceDesignator(String name, boolean finds) {
	}

	/**
	 * The namespace a datum designates while the program lowers: a quoted name,
	 * {@code (find-ns 'n)}, {@code (the-ns 'n)}, or {@code *ns*}, the namespace the call
	 * lowers in (the oracle reads it where the call runs, like {@code resolve}'s); null
	 * for any other datum.
	 */
	private static @Nullable NamespaceDesignator designatorOf(ClojureLowering ctx, LispVal datum) {
		String quoted = quotedSymbol(datum);
		if (quoted != null) {
			return new NamespaceDesignator(quoted, false);
		}
		if (datum instanceof LispSymbol s && (s.name().equals("*ns*") || s.name().equals("clojure.core/*ns*"))
				&& !ctx.isLocal(s.name())
				&& (s.name().startsWith("clojure.core/") || ctx.lookupVar(s.name()) == null)) {
			return new NamespaceDesignator(ctx.currentNs, false);
		}
		List<LispVal> call = ClojureLowerUtil.items(datum);
		if (call != null && call.size() == 2 && call.get(0) instanceof LispSymbol head
				&& (head.name().equals("find-ns") || head.name().equals("the-ns")) && !ctx.isLocal(head.name())
				&& ctx.lookupVar(head.name()) == null) {
			String name = quotedSymbol(call.get(1));
			if (name != null) {
				return new NamespaceDesignator(name, head.name().equals("find-ns"));
			}
		}
		return null;
	}

	/**
	 * What {@code ns-resolve} of a symbol answers in a namespace a macro body computed
	 * (the macro-time {@code ns-resolve}): {@link #resolvedIn} of the namespace named,
	 * which does not exist as the oracle's {@code the-ns} says.
	 * @param ctx the hub
	 * @param namespace the namespace's name
	 * @param target the symbol's name
	 * @return the lowered answer
	 */
	static LispVal resolvedInNamespace(ClojureLowering ctx, String namespace, String target) {
		return resolvedIn(ctx, new NamespaceDesignator(namespace, false), target);
	}

	/**
	 * What {@code ns-resolve} of a symbol answers in a namespace the lowering knows:
	 * {@code resolve} of it where that namespace is current -- a namespace a program
	 * {@code ns} or {@code in-ns} created reads its own interns and refers, any other
	 * known one (a library, {@code clojure.core}) its qualified name. A namespace that
	 * does not exist is the oracle's failure.
	 */
	private static LispVal resolvedIn(ClojureLowering ctx, NamespaceDesignator designator, String target) {
		String name = designator.name();
		if (ctx.createdNamespaces.contains(name)) {
			String outer = ctx.currentNs;
			ctx.currentNs = name;
			try {
				return resolved(ctx, target);
			}
			finally {
				ctx.currentNs = outer;
			}
		}
		if (ctx.knowsNamespace(name)) {
			return resolved(ctx, ClojureLowering.qualifierSlash(target) > 0 ? target : name + "/" + target);
		}
		if (designator.finds()) {
			// find-ns answers nil, and the-ns of nil fails on the namespace table's key
			return ClojureRefusals.refusal(ClojureRefusals.NULL_POINTER,
					LispString.literal("Cannot invoke \"Object.hashCode()\" because \"key\" is null"));
		}
		return ClojureRefusals.refusal(ClojureRefusals.EXCEPTION,
				LispString.literal("No namespace: " + name + " found"));
	}

	/**
	 * What {@code resolve} of a symbol answers here: the var {@code #'name} lowers to
	 * where it lowers (a program var, a {@code clojure.core} var the subset implements or
	 * a core macro), the class a class name spells, and nil for every other name -- a
	 * core var the subset lacks included, so a library choosing code by the
	 * {@code clojure.core} it runs on chooses the code this front end lowers.
	 * @param ctx the hub
	 * @param name the symbol's name
	 * @return the lowered answer
	 */
	static LispVal resolved(ClojureLowering ctx, String name) {
		String core;
		try {
			core = ctx.coreSpelling(name);
		}
		catch (LispReadException ex) {
			return ClojureLowering.NIL_CONST; // clojure.core/name of no core var
		}
		String key = core != null ? null : ctx.lookupVar(name);
		if (key != null) {
			return varOfKey(ctx, key);
		}
		if (core == null && ClojureCoreNames.contains(name) && ClojureNamespaceLowering.coreAllowed(ctx, name)) {
			core = name;
		}
		if (core != null) {
			return ClojureCoreNames.isMacro(core) || ctx.coreValueOrNull(core) != null ? coreVarOf(ctx, core)
					: ClojureLowering.NIL_CONST;
		}
		LispVal library = libraryVarOf(ctx, name);
		if (library != null) {
			return library;
		}
		LispVal classValue = ClojureInteropLowering.classValue(ctx, name);
		return classValue != null ? classValue : ClojureLowering.NIL_CONST;
	}

	/**
	 * {@code (with-redefs [var value ...] body...)}: every value evaluated in order, then
	 * each var's root replaced for the body and restored in an {@code unwind-protect},
	 * like the oracle's {@code with-redefs-fn} (a later binding of one var wins; a
	 * trailing name without a value is resolved and left alone). Each replacement
	 * validates and notifies like the oracle's {@code bindRoot} where the var is guarded,
	 * and is the plain {@code setq} otherwise (an {@code if} arm the strip folds back). A
	 * var is a value cell here: a {@code def}'s, a {@code declare}'s, and a
	 * {@code defn}'s when the var is redefinable
	 * ({@link ClojureLowering#redefinable(String, LispVal)}), which every {@code defn} a
	 * {@code with-redefs} names is: the pre-scan finds the name, or the program lowers
	 * again ({@link ClojureLowering#redefMisses}). A session cannot lower an earlier
	 * input again, so there a {@code defn} it lowered as a direct call is refused by
	 * name. A local, an unknown name, a macro, a {@code clojure.core} var (its verbs
	 * lower inline) and a function no {@code defn} defined (a multimethod, a protocol
	 * method, a test) are refused too. The body sits behind the {@code try} barrier.
	 */
	static LispVal withRedefsOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2,
				"Wrong number of args (" + (items.size() - 1) + ") passed to: clojure.core/with-redefs");
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(items.get(1), "with-redefs");
		List<LispVal> pairs = new ArrayList<>();
		Map<LispSymbol, String> cells = new LinkedHashMap<>();
		List<LispVal> sets = new ArrayList<>();
		boolean missed = false;
		for (int i = 0; i < bindings.size(); i += 2) {
			if (!(ClojureLowerUtil.stripMeta(bindings.get(i)) instanceof LispSymbol target)) {
				throw new LispReadException("with-redefs names a var, not " + bindings.get(i).print());
			}
			String key = redefTarget(ctx, target.name());
			missed |= key == null;
			if (i + 1 >= bindings.size()) {
				break;
			}
			LispSymbol value = ctx.freshTemp();
			pairs.add(ClojureLowerUtil.list(value, ctx.lower(bindings.get(i + 1))));
			if (key != null) {
				LispSymbol cell = ClojureLowering.varSym(key);
				cells.putIfAbsent(cell, key);
				sets.add(ClojureStateLowering.guardedVarStore(ctx, cell, value, key));
			}
		}
		LispVal body = ClojureStateLowering.barrierBody(ctx, items, 2);
		if (missed) {
			// this pass is thrown away: the program lowers again with the var
			// redefinable
			return ClojureLowering.NIL_CONST;
		}
		if (cells.isEmpty()) {
			return pairs.isEmpty() ? body
					: ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs), body);
		}
		List<LispVal> saved = new ArrayList<>();
		List<LispVal> restores = new ArrayList<>();
		for (Map.Entry<LispSymbol, String> cell : cells.entrySet()) {
			LispSymbol old = ctx.freshTemp();
			saved.add(ClojureLowerUtil.list(old, cell.getKey()));
			restores.add(ClojureStateLowering.guardedVarStore(ctx, cell.getKey(), old, cell.getValue()));
		}
		List<LispVal> run = new ArrayList<>();
		run.add(ClojureLowerUtil.sym("progn"));
		run.addAll(sets);
		run.add(body);
		List<LispVal> guarded = new ArrayList<>();
		guarded.add(ClojureLowerUtil.sym("unwind-protect"));
		guarded.add(ClojureLowerUtil.list(run));
		guarded.addAll(restores);
		pairs.addAll(saved);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs),
				ClojureLowerUtil.list(guarded));
	}

	/**
	 * The var key a {@code with-redefs} replaces, or null when its definition lowered as
	 * a direct call and the program lowers again ({@link ClojureLowering#redefMisses}).
	 */
	private static @Nullable String redefTarget(ClojureLowering ctx, String name) {
		if (ctx.isLocal(name)) {
			throw new LispReadException("Unable to resolve var: " + name + " in this context");
		}
		String core = ctx.coreSpelling(name);
		String key = core != null ? null : ctx.lookupVar(name);
		if (key == null) {
			if (core != null || ClojureCoreNames.contains(name)) {
				throw new LispReadException("with-redefs of a clojure.core var is not supported: "
						+ (core != null ? core : name) + " lowers inline at every call");
			}
			throw new LispReadException("Unable to resolve var: " + name + " in this context");
		}
		ClojureLowering.Kind kind = ctx.globals.get(key);
		if (kind == ClojureLowering.Kind.MACRO) {
			throw new LispReadException("with-redefs of a macro is not supported: " + key);
		}
		if (kind != ClojureLowering.Kind.FUNCTION && !ctx.globalDirectFuns.contains(key)) {
			return key;
		}
		if (ctx.redefinable.contains(key)) {
			// the program already lowers again with the var redefinable, and its
			// definition still calls directly: no defn or def defined it
			throw new LispReadException("with-redefs of " + key
					+ " is not supported: only a def or defn var can be redefined, not a multimethod, protocol method, record constructor or test");
		}
		if (ctx.incremental) {
			throw new LispReadException("with-redefs of " + key
					+ ": an earlier input defined it as a function called directly; define it ^:redef to redefine it");
		}
		ctx.redefMisses.add(key);
		return null;
	}

	/**
	 * The var of a {@code clojure.core} name: interned as {@code clojure.core/name}, its
	 * root the name's core value (what {@code clojure.core/name} reads), a macro's root a
	 * signal like a program macro's, and its metadata {@code :name}/{@code :ns} plus a
	 * macro's {@code :macro} (the oracle's {@code :arglists}, {@code :doc} and position
	 * are not carried). A special ({@link ClojureCoreSpecials}) carries the binding-depth
	 * reader {@code thread-bound?} asks: its counter, or one for a flag
	 * {@code clojure.main} binds. A core var with no value here is refused.
	 */
	private static LispVal coreVarOf(ClojureLowering ctx, String name) {
		String key = ClojureCoreNames.PREFIX + name;
		boolean macro = ClojureCoreNames.isMacro(name);
		// a macro's root is its expander as a value (g16): deref/@ of the var hands
		// back the fn, like a program macro's table entry handed out; every other
		// core name's root is its core value (what clojure.core/name reads)
		LispVal root = ctx.coreValueOrNull(name);
		if (root == null) {
			throw new LispReadException("var of a clojure.core var is not supported yet: #'" + key);
		}
		List<LispVal> meta = new ArrayList<>(List.of(new LispSymbol("%hash-map"), keyword("name"),
				ClojureLowerUtil.list(new LispSymbol("quote"), new LispSymbol(name)), keyword("ns"),
				ClojureLowerUtil.list(new LispSymbol("quote"), new LispSymbol("clojure.core"))));
		if (macro) {
			meta.add(keyword("macro"));
			meta.add(new LispSymbol("true"));
		}
		LispVal metaForm = ctx.lower(ClojureLowerUtil.list(meta));
		LispVal getter = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(), root);
		ClojureCoreSpecials.Special special = ClojureCoreSpecials.of(name);
		if (special != null) {
			ctx.usedSpecials.add(name);
			LispSymbol counter = special.counter();
			LispVal depth = counter == null ? new LispInteger(1) : counter;
			List<LispVal> call = new ArrayList<>(List.of(runtime("VAR-DYNAMIC"), LispString.literal(key), getter,
					metaForm, ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(), depth)));
			if (ctx.threadBinders) {
				List<LispVal> pairs = new ArrayList<>();
				pairs.add(ClojureLowerUtil.list(ctx.specialValue(special), BINDER_VALUE));
				LispVal depthPair = specialDepthPair(ctx, name);
				if (depthPair != null) {
					pairs.add(depthPair);
				}
				call.add(new LispInteger(1));
				call.add(binder(pairs));
			}
			else {
				ctx.unboundDynamicSites = true;
			}
			return ClojureLowerUtil.list(call);
		}
		return ClojureLowerUtil.list(runtime("VAR"), LispString.literal(key), getter, metaForm);
	}

	/** The value a dynamic var's binder binds. */
	private static final LispSymbol BINDER_VALUE = new LispSymbol(ClojureLowering.mangle("binder-value"));

	/** The function a dynamic var's binder calls under the binding. */
	private static final LispSymbol BINDER_THUNK = new LispSymbol(ClojureLowering.mangle("binder-thunk"));

	/**
	 * A dynamic var's binder:
	 * {@code (lambda (value thunk) (let* PAIRS (funcall thunk)))}, the {@code binding} of
	 * the var -- its special and its binding-depth counter, as {@code binding} lowers
	 * them -- around a call, which {@code with-bindings} and a
	 * {@code push-thread-bindings} pair reach through the var object
	 * ({@code %clojure-with-bindings}). Thread-scoped like {@code binding} itself.
	 */
	private static LispVal binder(List<LispVal> pairs) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(BINDER_VALUE, BINDER_THUNK),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("funcall"), BINDER_THUNK)));
	}

	/**
	 * The {@code let} pair rebinding a special's binding-depth counter one deeper, beside
	 * a binding of the special, or null for a flag {@code clojure.main} binds (always
	 * thread-bound, so it has no counter).
	 * @param ctx the hub, which then defines the special
	 * @param special a {@link ClojureCoreSpecials} name
	 * @return the pair, or null
	 */
	static @Nullable LispVal specialDepthPair(ClojureLowering ctx, String special) {
		ctx.usedSpecials.add(special);
		LispSymbol counter = ClojureCoreSpecials.required(special).counter();
		if (counter == null) {
			return null;
		}
		return ClojureLowerUtil.list(counter,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), counter, new LispInteger(1)));
	}

	/**
	 * The var a definition defined in the current namespace, as a lowered form: what a
	 * REPL echoes for a top-level {@code def}. It needs no name resolution, so a name the
	 * program defines over a {@code clojure.core} one is still its own var.
	 * @param ctx the hub, after it lowered the definition
	 * @param name the defined plain name
	 * @return the lowered var
	 */
	static LispVal definedVar(ClojureLowering ctx, String name) {
		return varOfKey(ctx, ClojureLowering.varKey(ctx.currentNs, name));
	}

	private static LispVal varOfKey(ClojureLowering ctx, String key) {
		LispVal var = internedVarOf(ctx, key);
		if (!ctx.rootSetters) {
			// a computed alter-var-root met later in this pass would have needed the
			// site's setter, or the function made redefinable
			ctx.unsetVarSites = true;
			return var;
		}
		if (!hasValueCell(ctx, key)) {
			if (!ctx.incremental && ctx.globals.get(key) != ClojureLowering.Kind.MACRO
					&& !ctx.redefinable.contains(key)) {
				// a computed alter-var-root may reach this var: the program lowers
				// again with the function redefinable, so the var has a value cell
				ctx.redefMisses.add(key);
			}
			return var;
		}
		// a computed alter-var-root finds the root setter of the var here
		return ClojureLowerUtil.list(runtime("VAR-SETTER"), var, rootSetterOf(ctx, key));
	}

	static LispVal internedVarOf(ClojureLowering ctx, String key) {
		LispVal root = rootOfKey(ctx, key);
		VarMeta meta = ctx.varMetas.get(key);
		// the definition whose metadata the site carries: 0 before any
		int generation = meta == null ? 0 : meta.generation();
		LispVal metaForm;
		if (meta == null) {
			int slash = key.indexOf('/');
			metaForm = ctx.lower(ClojureLowerUtil.list(new LispSymbol("%hash-map"), keyword("name"),
					ClojureLowerUtil.list(new LispSymbol("quote"), new LispSymbol(key.substring(slash + 1))),
					keyword("ns"),
					ClojureLowerUtil.list(new LispSymbol("quote"), new LispSymbol(key.substring(0, slash)))));
		}
		else {
			metaForm = meta.stored() ? metaSym(key) : ctx.lower(meta.datum());
		}
		LispVal getter = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(), root);
		List<LispVal> call = new ArrayList<>();
		LispVal binder = null;
		if (ctx.dynamicVars.contains(key)) {
			// only a dynamic var's site carries the binding-depth reader thread-bound?
			// asks, so a program with no dynamic var pays nothing for it
			call.addAll(List.of(runtime("VAR-DYNAMIC"), LispString.literal(key), getter, metaForm, ClojureLowerUtil
				.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(), ClojureLowering.boundDepthSym(key))));
			// and its binder only in a program binding vars through var objects
			if (ctx.threadBinders) {
				LispSymbol depth = ClojureLowering.boundDepthSym(key);
				binder = binder(
						List.of(ClojureLowerUtil.list(ClojureLowering.varSym(key), BINDER_VALUE), ClojureLowerUtil
							.list(depth, ClojureLowerUtil.list(ClojureLowerUtil.sym("+"), depth, new LispInteger(1)))));
			}
			else {
				ctx.unboundDynamicSites = true;
			}
		}
		else {
			call.addAll(List.of(runtime("VAR"), LispString.literal(key), getter, metaForm));
		}
		if (generation != 1 || binder != null) {
			call.add(new LispInteger(generation));
		}
		if (binder != null) {
			call.add(binder);
		}
		return ClojureLowerUtil.list(call);
	}

	/**
	 * Whether a program var's root is a value cell a store reaches: a {@code def}'s, a
	 * {@code declare}'s, a redefinable or dynamic {@code defn}'s -- not a function called
	 * directly, whose calls never read the cell, nor a macro's.
	 */
	private static boolean hasValueCell(ClojureLowering ctx, String key) {
		ClojureLowering.Kind kind = ctx.globals.get(key);
		return kind != ClojureLowering.Kind.FUNCTION && kind != ClojureLowering.Kind.MACRO
				&& !ctx.globalDirectFuns.contains(key);
	}

	/**
	 * The one-argument function storing a var's value cell: a closure where the site
	 * stands, or, where a local of a {@code user} var's name shadows the cell, a
	 * top-level writer the local cannot shadow.
	 */
	static LispVal rootSetterOf(ClojureLowering ctx, String key) {
		LispSymbol cell = ClojureLowering.varSym(key);
		if (key.startsWith("user/") && ctx.isLocal(key.substring("user/".length()))) {
			LispSymbol writer = new LispSymbol(cell.name() + "%set-root");
			if (ctx.varRootWriters.add(key)) {
				LispSymbol value = ctx.freshTemp();
				ctx.hoisted
					.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), writer, ClojureLowerUtil.list(value),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), cell, value)));
			}
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), writer);
		}
		LispSymbol value = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(value),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), cell, value));
	}

	/**
	 * The symbol a literal {@code #'x} / {@code (var x)} datum names, or null for any
	 * other datum.
	 */
	static @Nullable String literalVarName(LispVal datum) {
		List<LispVal> parts = ClojureLowerUtil.items(ClojureLowerUtil.stripMeta(datum));
		if (parts != null && parts.size() == 2 && ClojureLowerUtil.isSymbolNamed(parts.get(0), "var")
				&& ClojureLowerUtil.stripMeta(parts.get(1)) instanceof LispSymbol target) {
			return target.name();
		}
		return null;
	}

	/**
	 * {@code (alter-var-root v f args...)}: the root becomes {@code (apply f root args)},
	 * which the call answers. A literal {@code #'x} of a var with a value cell stores the
	 * cell through the setter the site hands the run-time library; a {@code defn} called
	 * directly is made redefinable like a {@code with-redefs} target (the pre-scan finds
	 * the name, or the program lowers again, {@link ClojureLowering#redefMisses}). Any
	 * other var -- a computed one, which turns {@link ClojureLowering#rootSetters} on, a
	 * {@code clojure.core} var, a macro, a multimethod, a session's direct {@code defn}
	 * -- goes through the setter a site of it registered, refused at run time where there
	 * is none.
	 * @param ctx the hub
	 * @param items the form, head included
	 * @return the lowered call
	 */
	static LispVal alterRootOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3,
				"Wrong number of args (" + (items.size() - 1) + ") passed to: clojure.core/alter-var-root");
		String name = literalVarName(items.get(1));
		if (name == null) {
			ctx.needRootSetters();
		}
		LispVal var = ctx.lower(items.get(1));
		LispVal f = ctx.lower(items.get(2));
		LispVal args = ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 3));
		if (name != null) {
			String core = ctx.coreSpelling(name);
			String key = core != null ? null : ctx.lookupVar(name);
			if (key != null && hasValueCell(ctx, key)) {
				return ClojureLowerUtil.list(runtime("ALTER-ROOT"), var, rootSetterOf(ctx, key), f, args);
			}
			if (key != null && ctx.globals.get(key) != ClojureLowering.Kind.MACRO && !ctx.redefinable.contains(key)
					&& !ctx.incremental) {
				// this pass is thrown away: the program lowers again with the var
				// redefinable
				ctx.redefMisses.add(key);
				return ClojureLowering.NIL_CONST;
			}
		}
		return ClojureLowerUtil.list(runtime("ALTER-VAR-ROOT"), var, f, args);
	}

	/** {@code alter-var-root} as a value: its var is computed. */
	static LispVal alterRootValue(ClojureLowering ctx) {
		ctx.needRootSetters();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtime("ALTER-VAR-ROOT-V"));
	}

	/**
	 * {@code (alter-meta! r f args...)}: the reference's metadata becomes
	 * {@code (apply f meta args)}, which the call answers. A var keeps it until a site of
	 * a later definition of the name runs, like the oracle's {@code def} replacing it.
	 * @param ctx the hub
	 * @param items the form, head included
	 * @return the lowered call
	 */
	static LispVal alterMetaOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3,
				"Wrong number of args (" + (items.size() - 1) + ") passed to: clojure.core/alter-meta!");
		return ClojureLowerUtil.list(runtime("ALTER-META"), ctx.lower(items.get(1)), ctx.lower(items.get(2)),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 3)));
	}

	/** {@code (reset-meta! r m)}: the reference's metadata becomes {@code m}. */
	static LispVal resetMetaOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3,
				"Wrong number of args (" + (items.size() - 1) + ") passed to: clojure.core/reset-meta!");
		return ClojureLowerUtil.list(runtime("RESET-META"), ctx.lower(items.get(1)), ctx.lower(items.get(2)));
	}

	/** {@code alter-meta!} or {@code reset-meta!} as a value. */
	static LispVal metaWriterValue(String name) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"),
				runtime(name.equals("alter-meta!") ? "ALTER-META-V" : "RESET-META-V"));
	}

	/**
	 * What a program var's root reads as, where the site stands: a function's current
	 * definition, a value cell (through a top-level reader where a local of a
	 * {@code user} var's name shadows the cell), a session's declared name through both,
	 * a macro's the oracle's refusal to take its value.
	 * @param ctx the hub
	 * @param key the var key, one of {@link ClojureLowering#globals}
	 * @return the lowered root
	 */
	static LispVal rootOfKey(ClojureLowering ctx, String key) {
		ClojureLowering.Kind kind = ctx.globals.get(key);
		if (kind == ClojureLowering.Kind.MACRO) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
					LispString.literal("Can't take value of a macro: #'" + key));
		}
		if (kind == ClojureLowering.Kind.FUNCTION) {
			LispSymbol fn = ctx.defnCounts.getOrDefault(key, 0) > 1 ? ctx.currentDefnSym(key)
					: ClojureLowering.varSym(key);
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), fn);
		}
		if (key.startsWith("user/") && ctx.isLocal(key.substring("user/".length()))) {
			// a user var's symbol is also the local's: read the root through a
			// top-level reader the local cannot shadow
			LispSymbol reader = new LispSymbol(ClojureLowering.varSym(key).name() + "%root");
			if (ctx.varRootReaders.add(key)) {
				ctx.hoisted.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), reader, ClojureLowerUtil.list(),
						ClojureLowering.varSym(key)));
			}
			return ClojureLowerUtil.list(reader);
		}
		if (kind == ClojureLowering.Kind.DECLARED && ctx.open) {
			return sessionDeclaredRoot(ClojureLowering.varSym(key));
		}
		return ClojureLowering.varSym(key);
	}

	/**
	 * {@code (declare name...)}: each name interned with no definition (one met later
	 * replaces it), its var's metadata the oracle's {@code :declared true} over the
	 * name's, and its root left alone when bound, else the unbound root. A
	 * {@code ^:dynamic} name rebinds like a dynamic {@code def}'s: its declaim and
	 * binding-depth counter go top-level ahead of the datum.
	 * @param ctx the hub
	 * @param form the declare datum (its position for the metadata)
	 * @param items the form, head included
	 * @return the lowered form, answering nil
	 */
	static LispVal declareForm(ClojureLowering ctx, LispVal form, List<LispVal> items) {
		List<LispVal> forms = new ArrayList<>();
		LispVal declared = ClojureLowerUtil.list(new LispSymbol("%hash-map"), keyword("declared"),
				new LispSymbol("true"));
		for (int i = 1; i < items.size(); i++) {
			LispVal nameDatum = items.get(i);
			String key = ctx.internDeclared(ClojureLowerUtil.plainName(nameDatum, "declare"),
					ClojureLowerUtil.nameIsPrivate(nameDatum));
			ctx.globals.putIfAbsent(key, ClojureLowering.Kind.DECLARED);
			ctx.declaredAbove.add(key);
			ctx.unboundCapable.add(key);
			forms.addAll(record(ctx, key, form, nameDatum, null, null, declared, false, false));
			if (ClojureLowerUtil.nameIsDynamic(nameDatum) && ctx.dynamicVars.add(key)) {
				ctx.hoisted.add(ClojureLowering.declaimSpecial(ClojureLowering.varSym(key),
						ClojureLowering.boundDepthSym(key)));
				ctx.hoisted.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defparameter"),
						ClojureLowering.boundDepthSym(key), new LispInteger(0)));
			}
			ClojureLowering.Kind kind = ctx.globals.get(key);
			if (kind == ClojureLowering.Kind.DECLARED || kind == ClojureLowering.Kind.VARIABLE) {
				// a function's or a macro's root is no value cell
				LispVal root = unboundRoot(ctx, key);
				if (root != null) {
					forms.add(root);
				}
			}
		}
		if (forms.isEmpty()) {
			return ClojureLowering.NIL_CONST;
		}
		forms.add(ClojureLowering.NIL_CONST);
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), forms);
	}

	/**
	 * The unbound root of a var a {@code declare} or a value-less {@code def} names. A
	 * file is one closed program, so its value cell holds the root from the program's
	 * start ({@link #unboundRootInits}) and the site lowers to nothing: a definition
	 * above the site has replaced it by then, and one below it still finds it, like the
	 * oracle's interned var. A session cannot place anything ahead of an earlier buffer,
	 * so its site stores the root when the cell is still unbound.
	 * @param ctx the hub
	 * @param key the var key
	 * @return the session's store, or null for a file
	 */
	static @Nullable LispVal unboundRoot(ClojureLowering ctx, String key) {
		ctx.unboundCapable.add(key);
		if (!ctx.incremental) {
			ctx.unboundRoots.add(key);
			return null;
		}
		LispSymbol var = ClojureLowering.varSym(key);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("unless"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("boundp"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), var)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), var, unboundOf(key)));
	}

	/** The stores a file's program starts with: each unbound var's root into its cell. */
	static List<LispVal> unboundRootInits(Iterable<String> keys) {
		List<LispVal> inits = new ArrayList<>();
		for (String key : keys) {
			inits.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), ClojureLowering.varSym(key), unboundOf(key)));
		}
		return inits;
	}

	private static LispVal unboundOf(String key) {
		return ClojureLowerUtil.list(runtime("UNBOUND"), LispString.literal(key));
	}

	/**
	 * A session's read of a name only declared so far: the function a later buffer's
	 * {@code defn} defined, else the value cell (a later {@code def}'s value, or the
	 * unbound root). The session runs on the interpreter, where both probes are cheap.
	 */
	static LispVal sessionDeclaredRoot(LispSymbol var) {
		LispVal quoted = ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), var);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("fboundp"), quoted),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), var), var);
	}

	/** Whether the call head is a {@code #'x} / {@code (var x)} form. */
	static boolean isVarForm(LispVal head) {
		List<LispVal> parts = ClojureLowerUtil.items(head);
		return parts != null && parts.size() == 2 && ClojureLowerUtil.isSymbolNamed(parts.get(0), "var");
	}

	/** {@code (#'f args...)}: the var invoked like the oracle's IFn, through its root. */
	static LispVal callOf(ClojureLowering ctx, List<LispVal> items) {
		return ClojureLowerUtil.list(runtime("CALL"), varOf(ctx, ClojureLowerUtil.items(items.get(0), List.of())),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 1)));
	}

	/** {@code clojure.core/test} on a lowered var. */
	static LispVal testOf(LispVal var) {
		return ClojureLowerUtil.list(runtime("VAR-TEST"), var);
	}

	/** {@code clojure.core/test} as a function value. */
	static LispVal testValue() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtime("VAR-TEST-V"));
	}

	/** {@code clojure.core/var-get} on a lowered var: its root, a non-var signals. */
	static LispVal getOf(LispVal var) {
		return ClojureLowerUtil.list(runtime("VAR-ROOT"), var);
	}

	/** {@code clojure.core/var-get} as a function value. */
	static LispVal getValue() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtime("VAR-ROOT-V"));
	}

	private static LispSymbol runtime(String name) {
		return new LispSymbol("RONTOLISP::%CLOJURE-" + name);
	}

}
