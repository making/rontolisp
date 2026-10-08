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
	 * A var's recorded metadata: the map datum, and whether the definition evaluated it
	 * into the var's meta global (some value is no constant).
	 */
	record VarMeta(LispVal datum, boolean stored) {
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
			List<LispVal> vectors = new ArrayList<>();
			for (LispVal params : arglists) {
				vectors.add(ClojureLowerUtil.stripMeta(params));
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
		ctx.varMetas.put(key, new VarMeta(datum, stored));
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
			return ctx.namespaceResources.getOrDefault(loading, ClojureSourcePath.resourceOf(loading));
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
		String core = ClojureCoreNames.coreSpelling(name);
		String key = core != null ? null : ctx.lookupVar(name);
		if (key != null) {
			return varOfKey(ctx, key);
		}
		if (core == null && ClojureCoreNames.contains(name)) {
			core = name;
		}
		if (core == null) {
			throw new LispReadException("Unable to resolve var: " + name + " in this context");
		}
		return coreVarOf(ctx, core);
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
		LispVal root;
		if (macro) {
			root = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
					LispString.literal("Can't take value of a macro: #'" + key));
		}
		else {
			root = ctx.coreValueOrNull(name);
			if (root == null) {
				throw new LispReadException("var of a clojure.core var is not supported yet: #'" + key);
			}
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
			return ClojureLowerUtil.list(runtime("VAR-DYNAMIC"), LispString.literal(key), getter, metaForm,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(), depth));
		}
		return ClojureLowerUtil.list(runtime("VAR"), LispString.literal(key), getter, metaForm);
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
		LispVal root;
		ClojureLowering.Kind kind = ctx.globals.get(key);
		if (kind == ClojureLowering.Kind.MACRO) {
			root = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
					LispString.literal("Can't take value of a macro: #'" + key));
		}
		else if (kind == ClojureLowering.Kind.FUNCTION) {
			LispSymbol fn = ctx.defnCounts.getOrDefault(key, 0) > 1 ? ctx.currentDefnSym(key)
					: ClojureLowering.varSym(key);
			root = ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), fn);
		}
		else if (key.startsWith("user/") && ctx.isLocal(key.substring("user/".length()))) {
			// a user var's symbol is also the local's: read the root through a
			// top-level reader the local cannot shadow
			LispSymbol reader = new LispSymbol(ClojureLowering.varSym(key).name() + "%root");
			if (ctx.varRootReaders.add(key)) {
				ctx.hoisted.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), reader, ClojureLowerUtil.list(),
						ClojureLowering.varSym(key)));
			}
			root = ClojureLowerUtil.list(reader);
		}
		else if (kind == ClojureLowering.Kind.DECLARED && ctx.session) {
			root = sessionDeclaredRoot(ClojureLowering.varSym(key));
		}
		else {
			root = ClojureLowering.varSym(key);
		}
		VarMeta meta = ctx.varMetas.get(key);
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
		if (ctx.dynamicVars.contains(key)) {
			// only a dynamic var's site carries the binding-depth reader thread-bound?
			// asks, so a program with no dynamic var pays nothing for it
			return ClojureLowerUtil.list(runtime("VAR-DYNAMIC"), LispString.literal(key), getter, metaForm,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(),
							ClojureLowering.boundDepthSym(key)));
		}
		return ClojureLowerUtil.list(runtime("VAR"), LispString.literal(key), getter, metaForm);
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
		if (!ctx.session) {
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
