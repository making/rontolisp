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
			return ClojureSourcePath.resourceOf(loading);
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
	 * namespace's private var is reachable, like the oracle's.
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
		String key = ClojureCoreNames.coreSpelling(name) != null ? null : ctx.lookupVar(name);
		if (key == null) {
			String core = ClojureCoreNames.coreSpelling(name);
			if (core != null || ClojureCoreNames.contains(name)) {
				throw new LispReadException("var of a clojure.core var is not supported yet: #'"
						+ ClojureCoreNames.PREFIX + (core != null ? core : name));
			}
			throw new LispReadException("Unable to resolve var: " + name + " in this context");
		}
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
		return ClojureLowerUtil.list(runtime("VAR"), LispString.literal(key),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(), root), metaForm);
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

	private static LispSymbol runtime(String name) {
		return new LispSymbol("RONTOLISP::%CLOJURE-" + name);
	}

}
