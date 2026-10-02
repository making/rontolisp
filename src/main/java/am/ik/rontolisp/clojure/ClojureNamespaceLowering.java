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
 * Namespace forms of the Clojure lowering: ns/require/use/import and name resolution.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureNamespaceLowering {

	private ClojureNamespaceLowering() {
	}

	/**
	 * A known-namespace call: the vars {@code ns} resolution already vetted, over the
	 * call's own items (whose head is ignored). {@code clojure.string} lowers to the core
	 * string operations, {@code clojure.java.io} to the file-stream runtime.
	 */
	static LispVal namespaceCall(ClojureLowering ctx, ClojureLowering.VarRef ref, List<LispVal> items,
			@Nullable LispVal form) {
		if (ref.ns().equals(ClojureTestLowering.NAMESPACE)) {
			return ClojureTestLowering.testCall(ctx, ref.var(), items, form);
		}
		if (ref.ns().equals("clojure.java.io")) {
			return jioCall(ctx, ref.var(), items);
		}
		return ClojureStringLowering.stringCall(ctx, ref.var(), items);
	}

	/**
	 * A known-namespace var as a function value: {@code clojure.string} lowers through
	 * the call lowering per arity, {@code clojure.java.io/reader} is a one-argument
	 * lambda over the same open.
	 */
	static LispVal namespaceValue(ClojureLowering ctx, ClojureLowering.VarRef ref) {
		if (ref.ns().equals(ClojureTestLowering.NAMESPACE)) {
			return ClojureTestLowering.testValue(ctx, ref.var());
		}
		if (ref.ns().equals("clojure.java.io")) {
			return jioValue(ctx, ref.var());
		}
		return ClojureStringLowering.stringValue(ctx, ref.var());
	}

	/**
	 * A {@code clojure.java.io} call: exactly {@code reader}, a buffered reader over the
	 * path through the same file-stream runtime {@code slurp} reads through -- an
	 * {@code open} input stream, so {@code line-seq} reads it and {@code with-open}
	 * closes it.
	 */
	static LispVal jioCall(ClojureLowering ctx, String var, List<LispVal> items) {
		int n = items.size() - 1;
		if (var.equals("reader")) {
			ClojureLowerUtil.isTrue(n == 1, "reader takes one path");
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("open"), ctx.lower(items.get(1)));
		}
		throw new LispReadException("unknown name: clojure.java.io/" + var);
	}

	/**
	 * {@code clojure.java.io/reader} as a function value: a one-argument lambda over the
	 * same open.
	 */
	static LispVal jioValue(ClojureLowering ctx, String var) {
		if (var.equals("reader")) {
			LispSymbol path = new LispSymbol(ClojureLowering.mangle("reader-path"));
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(path),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("open"), path));
		}
		throw new LispReadException("unknown name: clojure.java.io/" + var);
	}

	/**
	 * The namespaces whose vars lower to core forms: {@code clojure.string},
	 * {@code clojure.java.io} and {@code clojure.test}.
	 */
	static boolean isKnownNamespace(String ns) {
		return ns.equals("clojure.string") || ns.equals("clojure.java.io") || ns.equals(ClojureTestLowering.NAMESPACE);
	}

	/** Whether the namespace exports the var as a lowering. */
	static boolean isKnownVar(String ns, String var) {
		return ns.equals("clojure.string") && STRING_VARS.contains(var)
				|| ns.equals("clojure.java.io") && JIO_VARS.contains(var)
				|| ns.equals(ClojureTestLowering.NAMESPACE) && ClojureTestLowering.VARS.contains(var);
	}

	/**
	 * The vars a namespace refers in full: per namespace, so {@code :refer :all} stays
	 * exact.
	 */
	static Set<String> varsOf(String ns) {
		if (ns.equals("clojure.java.io")) {
			return JIO_VARS;
		}
		if (ns.equals(ClojureTestLowering.NAMESPACE)) {
			return ClojureTestLowering.VARS;
		}
		return STRING_VARS;
	}

	/**
	 * The {@code clojure.java.io} vars this front end implements: exactly {@code reader}.
	 */
	static final Set<String> JIO_VARS = Set.of("reader");

	/** The {@code clojure.string} vars this front end implements. */
	static final Set<String> STRING_VARS = Set.of("join", "split", "split-lines", "upper-case", "lower-case",
			"capitalize", "trim", "triml", "trimr", "trim-newline", "blank?", "starts-with?", "ends-with?", "includes?",
			"index-of", "last-index-of", "replace", "replace-first", "escape", "re-quote-replacement", "reverse");

	/** The {@code java.lang} classes a simple name resolves to without an import. */
	static final Set<String> JAVA_LANG = Set.of("Object", "String", "Integer", "Long", "Double", "Float", "Boolean",
			"Character", "Byte", "Short", "Math", "System", "Class", "Thread", "Exception", "RuntimeException", "Error",
			"StringBuilder", "Number", "Comparable", "CharSequence");

	/**
	 * Whether a core name is visible: everything outside a
	 * {@code (:refer-clojure :only [...])} set, minus a {@code :exclude} set. An
	 * invisible name is not a builtin, so a user definition of it wins.
	 */
	static boolean coreAllowed(ClojureLowering ctx, String name) {
		if (ctx.referClojureOnly != null && !ctx.referClojureOnly.contains(name)) {
			return false;
		}
		return !ctx.referClojureExclude.contains(name);
	}

	/**
	 * One {@code (ns name doc? attr-map? clause...)} form: wires the {@code :require} /
	 * {@code :use} aliases and refers, the {@code :import} class names and the
	 * {@code :refer-clojure} filter, and defines nothing. Metadata, the docstring and the
	 * attr map are ignored, like the declaration itself.
	 */
	static void processNs(ClojureLowering ctx, LispVal form) {
		List<LispVal> items = ClojureLowerUtil.items(form);
		if (items == null || items.size() < 2) {
			throw new LispReadException("ns takes a name: " + form.print());
		}
		if (!(ClojureLowerUtil.stripMeta(items.get(1)) instanceof LispSymbol name)) {
			throw new LispReadException("ns takes a name, not " + items.get(1).print());
		}
		ctx.currentNs = name.name();
		for (int i = 2; i < items.size(); i++) {
			LispVal clause = items.get(i);
			if (clause instanceof LispString) {
				continue; // the docstring
			}
			List<LispVal> parts = ClojureLowerUtil.items(clause);
			if (parts == null || parts.isEmpty()) {
				continue;
			}
			if (!(parts.get(0) instanceof LispSymbol head) || !head.name().startsWith(":")) {
				continue; // metadata and anything else that declares nothing
			}
			switch (head.name()) {
				case ":require" -> requireSpecs(ctx, parts.subList(1, parts.size()), false);
				case ":use" -> requireSpecs(ctx, parts.subList(1, parts.size()), true);
				case ":import" -> importSpecs(ctx, parts.subList(1, parts.size()));
				case ":refer-clojure" -> referClojure(ctx, parts.subList(1, parts.size()));
				default -> throw new LispReadException("ns clause " + head.name() + " is not supported yet");
			}
		}
	}

	/**
	 * {@code (in-ns 'name)}: switches the session's {@code *ns*} for the
	 * {@code ::}-keywords below it, answering nil. The namespace stays flat -- every
	 * definition is still global -- so only the auto-resolve moves. A quoted symbol, a
	 * bare symbol or a string names it directly (never evaluated); anything else leaves
	 * it alone.
	 */
	static LispVal inNsOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "in-ns takes a namespace");
		LispVal arg = items.get(1);
		if (arg instanceof LispSymbol sym) {
			ctx.currentNs = sym.name();
		}
		else if (arg instanceof LispString text) {
			ctx.currentNs = text.value();
		}
		else {
			List<LispVal> quoted = ClojureLowerUtil.items(arg);
			if (quoted != null && quoted.size() == 2 && ClojureLowerUtil.isSymbolNamed(quoted.get(0), "quote")
					&& quoted.get(1) instanceof LispSymbol sym) {
				ctx.currentNs = sym.name();
			}
		}
		return ClojureLowering.NIL_CONST; // the namespace is flat: every definition is
											// global
	}

	/**
	 * The libspecs of a {@code :require} (or {@code :use}, which refers everything by
	 * default): {@code [ns :as alias :refer [vars]/:all]} vectors, bare library symbols
	 * and prefix lists -- each either bare or quoted ({@code 'spec}, the oracle's
	 * bare-{@code require} spelling; the {@code ns} clauses quote implicitly, so both
	 * paths share this parser). An unquoted vector spec stays accepted too (a lenient
	 * superset: the oracle rejects it with a {@code ClassNotFoundException}). Requiring
	 * an unknown namespace is an error, like the oracle's missing-library failure.
	 */
	static void requireSpecs(ClojureLowering ctx, List<LispVal> specs, boolean referAll) {
		for (LispVal spec : specs) {
			LispVal unwrapped = unwrapQuote(spec);
			if (unwrapped instanceof LispSymbol flag && flag.name().startsWith(":")) {
				continue; // :reload, :reload-all, :verbose: one load per program here
			}
			if (unwrapped instanceof LispSymbol bare) {
				// a bare symbol names one library, like the oracle (a prefix
				// takes the list form below): (:use clojure.test) refers it all
				requireOne(ctx, null, List.of(bare), referAll, spec);
				continue;
			}
			if (!ClojureBindingLowering.isVectorDatum(unwrapped)) {
				// a prefix list: (prefix member...) names prefix.member...
				// libraries -- quoted ('(prefix ...), the oracle's bare-require
				// spelling) or bare (the unquoted-vector leniency extended, shared
				// with the ns clauses which quote implicitly). Each member (bare
				// or quoted) is a symbol or a [...] vector resolved under the
				// prefix through requireOne.
				List<LispVal> prefixParts = ClojureLowerUtil.items(unwrapped);
				if (prefixParts == null || prefixParts.isEmpty()
						|| !(prefixParts.get(0) instanceof LispSymbol prefixName)
						|| prefixName.name().startsWith(":")) {
					throw new LispReadException("require takes library specs, not " + spec.print());
				}
				if (prefixParts.size() < 2) {
					// (prefix) names the prefix library itself, like a bare symbol.
					requireOne(ctx, null, prefixParts, referAll, spec);
					continue;
				}
				for (int i = 1; i < prefixParts.size(); i++) {
					LispVal sub = unwrapQuote(prefixParts.get(i));
					if (sub instanceof LispSymbol sym) {
						requireOne(ctx, prefixName.name(), List.of(sym), referAll, spec);
						continue;
					}
					List<LispVal> subParts = ClojureLowerUtil.items(sub);
					if (subParts == null || subParts.isEmpty() || !ClojureBindingLowering.isVectorDatum(sub)
							|| subParts.size() < 2 || !(subParts.get(1) instanceof LispSymbol)) {
						throw new LispReadException("require takes library specs, not " + spec.print());
					}
					requireOne(ctx, prefixName.name(), subParts.subList(1, subParts.size()), referAll, spec);
				}
				continue;
			}
			List<LispVal> parts = ClojureLowerUtil.items(unwrapped);
			if (parts == null || parts.size() < 2 || !(parts.get(1) instanceof LispSymbol)) {
				throw new LispReadException("require takes library specs, not " + spec.print());
			}
			requireOne(ctx, null, parts.subList(1, parts.size()), referAll, spec);
		}
	}

	static void requireOne(ClojureLowering ctx, @Nullable String prefix, List<LispVal> parts, boolean referAll,
			LispVal spec) {
		String ns = ((LispSymbol) parts.get(0)).name();
		if (prefix != null) {
			ns = prefix + "." + ns;
		}
		if (!isKnownNamespace(ns)) {
			throw new LispReadException("unknown namespace: " + ns);
		}
		ctx.aliases.putIfAbsent(ns, ns); // the fully-qualified spelling always resolves
		boolean all = referAll;
		List<String> only = null;
		Set<String> exclude = new HashSet<>();
		for (int i = 1; i < parts.size(); i += 2) {
			if (i + 1 >= parts.size() || !(parts.get(i) instanceof LispSymbol opt)) {
				throw new LispReadException("require takes option/value pairs, not " + spec.print());
			}
			LispVal arg = parts.get(i + 1);
			switch (opt.name()) {
				case ":as" -> {
					if (!(arg instanceof LispSymbol alias)) {
						throw new LispReadException(":as takes an alias, not " + arg.print());
					}
					ctx.aliases.put(alias.name(), ns);
				}
				case ":refer" -> {
					if (arg instanceof LispSymbol every && every.name().equals(":all")) {
						all = true;
					}
					else {
						only = referNames(arg, spec);
					}
				}
				case ":only" -> only = referNames(arg, spec);
				case ":exclude" -> exclude.addAll(referNames(arg, spec));
				case ":rename" -> throw new LispReadException(":rename is not supported yet: " + spec.print());
				default -> throw new LispReadException("require option " + opt.name() + " is not supported yet");
			}
		}
		if (only != null) {
			// :only narrows in both modes: under use (:refer :all) it wins over
			// the refer-all default, and :exclude subtracts from it either way.
			for (String var : only) {
				if (!exclude.contains(var)) {
					if (!isKnownVar(ns, var)) {
						throw new LispReadException("unknown name: " + ns + "/" + var);
					}
					ctx.refers.put(var, new ClojureLowering.VarRef(ns, var));
				}
			}
		}
		else if (all) {
			for (String var : varsOf(ns)) {
				if (!exclude.contains(var)) {
					ctx.refers.put(var, new ClojureLowering.VarRef(ns, var));
				}
			}
		}
	}

	static List<String> referNames(LispVal arg, LispVal spec) {
		List<LispVal> elements = ClojureLowerUtil.items(arg);
		if (elements == null || elements.isEmpty()) {
			throw new LispReadException("a :refer/:only/:exclude takes a vector of names, not " + spec.print());
		}
		// a vector, or a list like the oracle's (reader) spelling
		int from = elements.get(0) == ClojureReader.VECTOR ? 1 : 0;
		List<String> names = new ArrayList<>();
		for (LispVal element : elements.subList(from, elements.size())) {
			if (!(element instanceof LispSymbol named) || named.name().startsWith(":")) {
				throw new LispReadException("a :refer/:only/:exclude takes a vector of names, not " + spec.print());
			}
			names.add(named.name());
		}
		return names;
	}

	/**
	 * The class specs of an {@code :import}: bare classes and {@code (package ...)}
	 * lists.
	 */
	static void importSpecs(ClojureLowering ctx, List<LispVal> specs) {
		for (LispVal spec : specs) {
			LispVal unwrapped = unwrapQuote(spec);
			if (unwrapped instanceof LispSymbol single) {
				importClass(ctx, single.name());
				continue;
			}
			List<LispVal> parts = ClojureLowerUtil.items(unwrapped);
			if (parts == null || parts.isEmpty() || !(parts.get(0) instanceof LispSymbol pack)) {
				throw new LispReadException("import takes class names, not " + spec.print());
			}
			for (int i = 1; i < parts.size(); i++) {
				if (!(parts.get(i) instanceof LispSymbol named)) {
					throw new LispReadException("import takes class names, not " + spec.print());
				}
				importClass(ctx, pack.name() + "." + named.name());
			}
		}
	}

	static void importClass(ClojureLowering ctx, String fqn) {
		int dot = fqn.lastIndexOf('.');
		ctx.classNames.put(dot < 0 ? fqn : fqn.substring(dot + 1), fqn);
	}

	/**
	 * The options of a {@code (:refer-clojure ...)} clause: {@code :only},
	 * {@code :exclude}.
	 */
	static void referClojure(ClojureLowering ctx, List<LispVal> opts) {
		for (int i = 0; i < opts.size(); i += 2) {
			if (i + 1 >= opts.size() || !(opts.get(i) instanceof LispSymbol opt)) {
				throw new LispReadException(":refer-clojure takes option/value pairs");
			}
			switch (opt.name()) {
				case ":only" -> ctx.referClojureOnly = new HashSet<>(referNames(opts.get(i + 1), opts.get(i + 1)));
				case ":exclude" -> ctx.referClojureExclude.addAll(referNames(opts.get(i + 1), opts.get(i + 1)));
				case ":rename" -> throw new LispReadException(":rename is not supported yet in :refer-clojure");
				default -> throw new LispReadException(":refer-clojure option " + opt.name() + " is not supported yet");
			}
		}
	}

	/**
	 * The libspecs of a top-level {@code require} / {@code use} / {@code import} call.
	 */
	static List<LispVal> specsOf(List<LispVal> items, String what) {
		ClojureLowerUtil.isTrue(items.size() >= 2, what + " takes library specs");
		return items.subList(1, items.size());
	}

	/**
	 * A libspec unquoted: {@code 'spec} and {@code spec} spell the same library, whether
	 * the spec is a bare symbol, a {@code [...]} vector or a prefix list -- the oracle
	 * quotes every bare-{@code require} spec, while the {@code ns} clauses quote
	 * implicitly. Unquoted vector specs stay accepted too (a lenient superset: the oracle
	 * rejects them with a {@code ClassNotFoundException}).
	 */
	static LispVal unwrapQuote(LispVal spec) {
		List<LispVal> parts = ClojureLowerUtil.items(spec);
		if (parts != null && parts.size() == 2 && ClojureLowerUtil.isSymbolNamed(parts.get(0), "quote")) {
			return parts.get(1);
		}
		return spec;
	}

	/**
	 * A slash name resolved through the aliases: {@code s/join} with {@code s} for
	 * {@code clojure.string}, or the fully-qualified {@code clojure.string/join}. Null
	 * when the head is no alias and no known namespace, so the call keeps falling through
	 * to interop and the unknown-name refusal.
	 */
	static ClojureLowering.@Nullable VarRef resolveQualified(ClojureLowering ctx, String name) {
		int slash = name.indexOf('/');
		if (slash <= 0) {
			return null;
		}
		String head = name.substring(0, slash);
		String tail = name.substring(slash + 1);
		if (tail.isEmpty() || tail.indexOf('/') >= 0) {
			return null;
		}
		String ns = ctx.aliases.get(head);
		if (ns == null && isKnownNamespace(head)) {
			ns = head;
		}
		if (ns == null) {
			return null;
		}
		if (!isKnownVar(ns, tail)) {
			if (isKnownNamespace(ns)) {
				throw new LispReadException("unknown name: " + ns + "/" + tail);
			}
			return null;
		}
		return new ClojureLowering.VarRef(ns, tail);
	}

	/** A class name resolved: dotted as written, imported, or {@code java.lang}. */
	static String resolveClass(ClojureLowering ctx, String name) {
		if (name.indexOf('.') >= 0) {
			return name;
		}
		String imported = ctx.classNames.get(name);
		if (imported != null) {
			return imported;
		}
		if (JAVA_LANG.contains(name)) {
			return "java.lang." + name;
		}
		return name;
	}

	/**
	 * Whether the slash form's head names a class: imported, java.lang, dotted or
	 * capitalized.
	 */
	static boolean isClasslike(ClojureLowering ctx, String head) {
		return ctx.classNames.containsKey(head) || JAVA_LANG.contains(head) || head.indexOf('.') >= 0
				|| (!head.isEmpty() && Character.isUpperCase(head.charAt(0)));
	}

	// atoms and quotes

}
