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
 * Namespace forms of the Clojure lowering: ns/require/use/import, the loading of project
 * namespaces from the source path ({@link ClojureSourcePath}) and the libraries that are
 * lowerings ({@code clojure.string}, {@code clojure.set}, {@code clojure.test}). Which
 * var a name names is the hub's ({@link ClojureLowering#resolveVar}); this slice wires
 * the aliases and refers it reads.
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
	 * string operations, {@code clojure.set} to the set runtime.
	 */
	static LispVal namespaceCall(ClojureLowering ctx, ClojureLowering.VarRef ref, List<LispVal> items,
			@Nullable LispVal form) {
		if (ref.ns().equals(ClojureTestLowering.NAMESPACE)) {
			return ClojureTestLowering.testCall(ctx, ref.var(), items, form);
		}
		if (ref.ns().equals(ClojureSetLowering.NAMESPACE)) {
			return ClojureSetLowering.setCall(ctx, ref.var(), items);
		}
		if (ref.ns().equals(ClojureEdnLowering.NAMESPACE)) {
			return ClojureEdnLowering.ednCall(ctx, ref.var(), items);
		}
		if (ref.ns().equals(ClojureRingLowering.NAMESPACE)) {
			return ClojureRingLowering.ringCall(ctx, ref.var(), items);
		}
		if (ClojureKernelLowering.isKernelNamespace(ref.ns())) {
			return ClojureKernelLowering.kernelCall(ctx, ref.ns(), ref.var(), items);
		}
		if (ref.ns().equals(ClojureWasmLowering.NAMESPACE)) {
			return ClojureWasmLowering.call(ctx, ref.var(), items, form);
		}
		if (ref.ns().equals(ClojureWitLowering.NAMESPACE)) {
			return ClojureWitLowering.call(ctx, ref.var(), items, form);
		}
		return ClojureStringLowering.stringCall(ctx, ref.var(), items);
	}

	/**
	 * A known-namespace var as a function value: {@code clojure.string} lowers through
	 * the call lowering per arity, {@code clojure.set} names its runtime entry.
	 */
	static LispVal namespaceValue(ClojureLowering ctx, ClojureLowering.VarRef ref) {
		if (ref.ns().equals(ClojureTestLowering.NAMESPACE)) {
			return ClojureTestLowering.testValue(ctx, ref.var());
		}
		if (ref.ns().equals(ClojureSetLowering.NAMESPACE)) {
			return ClojureSetLowering.setValue(ref.var());
		}
		if (ref.ns().equals(ClojureEdnLowering.NAMESPACE)) {
			return ClojureEdnLowering.ednValue(ref.var());
		}
		if (ref.ns().equals(ClojureRingLowering.NAMESPACE)) {
			return ClojureRingLowering.ringValue(ref.var());
		}
		if (ClojureKernelLowering.isKernelNamespace(ref.ns())) {
			return ClojureKernelLowering.kernelValue(ctx, ref.ns(), ref.var());
		}
		if (ref.ns().equals(ClojureWasmLowering.NAMESPACE)) {
			return ClojureWasmLowering.value(ref.var());
		}
		if (ref.ns().equals(ClojureWitLowering.NAMESPACE)) {
			return ClojureWitLowering.value(ref.var());
		}
		return ClojureStringLowering.stringValue(ctx, ref.var());
	}

	/**
	 * The {@code clojure.java.io/reader} of a path before the namespace shipped as
	 * Clojure source: an {@code open} of the path, a stream producer the reader wrappers
	 * still recognize.
	 */
	static final String READER = "RONTOLISP::%CLOJURE-READER";

	/**
	 * The namespaces whose vars lower to core forms: {@code clojure.string},
	 * {@code clojure.set}, {@code clojure.edn}, {@code clojure.test}, the Ring adapter
	 * {@code ring.adapter.rontolisp}, the kernels of the built-in namespaces
	 * ({@link ClojureKernelLowering}), and the host boundary's {@code rontolisp.wasm} and
	 * {@code rontolisp.wit}.
	 */
	static boolean isKnownNamespace(String ns) {
		return ns.equals("clojure.string") || ns.equals(ClojureSetLowering.NAMESPACE)
				|| ns.equals(ClojureEdnLowering.NAMESPACE) || ns.equals(ClojureTestLowering.NAMESPACE)
				|| ns.equals(ClojureRingLowering.NAMESPACE) || ClojureKernelLowering.isKernelNamespace(ns)
				|| ns.equals(ClojureWasmLowering.NAMESPACE) || ns.equals(ClojureWitLowering.NAMESPACE);
	}

	/** Whether the namespace exports the var as a lowering. */
	static boolean isKnownVar(String ns, String var) {
		return ns.equals("clojure.string") && STRING_VARS.contains(var)
				|| ns.equals(ClojureSetLowering.NAMESPACE) && ClojureSetLowering.VARS.contains(var)
				|| ns.equals(ClojureEdnLowering.NAMESPACE) && ClojureEdnLowering.VARS.contains(var)
				|| ns.equals(ClojureTestLowering.NAMESPACE) && ClojureTestLowering.VARS.contains(var)
				|| ns.equals(ClojureRingLowering.NAMESPACE) && ClojureRingLowering.VARS.contains(var)
				|| ClojureKernelLowering.isKernelVar(ns, var)
				|| ns.equals(ClojureWasmLowering.NAMESPACE) && ClojureWasmLowering.VARS.contains(var)
				|| ns.equals(ClojureWitLowering.NAMESPACE) && ClojureWitLowering.VARS.contains(var);
	}

	/**
	 * The vars a namespace refers in full: per namespace, so {@code :refer :all} stays
	 * exact.
	 */
	static Set<String> varsOf(String ns) {
		if (ns.equals(ClojureTestLowering.NAMESPACE)) {
			return ClojureTestLowering.VARS;
		}
		if (ns.equals(ClojureSetLowering.NAMESPACE)) {
			return ClojureSetLowering.VARS;
		}
		if (ns.equals(ClojureEdnLowering.NAMESPACE)) {
			return ClojureEdnLowering.VARS;
		}
		if (ns.equals(ClojureRingLowering.NAMESPACE)) {
			return ClojureRingLowering.VARS;
		}
		Set<String> kernels = ClojureKernelLowering.varsOf(ns);
		if (kernels != null) {
			return kernels;
		}
		if (ns.equals(ClojureWasmLowering.NAMESPACE)) {
			return ClojureWasmLowering.VARS;
		}
		if (ns.equals(ClojureWitLowering.NAMESPACE)) {
			return ClojureWitLowering.VARS;
		}
		return STRING_VARS;
	}

	/** The {@code clojure.string} vars this front end implements. */
	static final Set<String> STRING_VARS = Set.of("join", "split", "split-lines", "upper-case", "lower-case",
			"capitalize", "trim", "triml", "trimr", "trim-newline", "blank?", "starts-with?", "ends-with?", "includes?",
			"index-of", "last-index-of", "replace", "replace-first", "escape", "re-quote-replacement", "reverse");

	/**
	 * The {@code java.lang} classes a simple name resolves to without an import: the
	 * oracle's fixed default-import list (not every {@code java.lang} class -- {@code
	 * AutoCloseable}, {@code Record} and {@code Module} are unresolved there too).
	 */
	static final Set<String> JAVA_LANG = Set.of("AbstractMethodError", "Appendable", "ArithmeticException",
			"ArrayIndexOutOfBoundsException", "ArrayStoreException", "AssertionError", "Boolean", "Byte", "Character",
			"CharSequence", "Class", "ClassCastException", "ClassCircularityError", "ClassFormatError", "ClassLoader",
			"ClassNotFoundException", "CloneNotSupportedException", "Cloneable", "Comparable", "Deprecated", "Double",
			"Enum", "EnumConstantNotPresentException", "Error", "Exception", "ExceptionInInitializerError", "Float",
			"IllegalAccessError", "IllegalAccessException", "IllegalArgumentException", "IllegalMonitorStateException",
			"IllegalStateException", "IllegalThreadStateException", "IncompatibleClassChangeError",
			"IndexOutOfBoundsException", "InheritableThreadLocal", "InstantiationError", "InstantiationException",
			"Integer", "InternalError", "InterruptedException", "Iterable", "LinkageError", "Long", "Math",
			"NegativeArraySizeException", "NoClassDefFoundError", "NoSuchFieldError", "NoSuchFieldException",
			"NoSuchMethodError", "NoSuchMethodException", "NullPointerException", "Number", "NumberFormatException",
			"Object", "OutOfMemoryError", "Override", "Package", "Process", "ProcessBuilder", "Readable", "Runnable",
			"Runtime", "RuntimeException", "RuntimePermission", "SecurityException", "SecurityManager", "Short",
			"StackOverflowError", "StackTraceElement", "StrictMath", "String", "StringBuffer", "StringBuilder",
			"StringIndexOutOfBoundsException", "SuppressWarnings", "System", "Thread", "ThreadDeath", "ThreadGroup",
			"ThreadLocal", "Throwable", "TypeNotPresentException", "UnknownError", "UnsatisfiedLinkError",
			"UnsupportedClassVersionError", "UnsupportedOperationException", "VerifyError", "VirtualMachineError",
			"Void");

	/**
	 * Whether a core name is visible: everything outside a
	 * {@code (:refer-clojure :only [...])} set, minus a {@code :exclude} set. An
	 * invisible name is not a builtin, so a user definition of it wins.
	 */
	static boolean coreAllowed(ClojureLowering ctx, String name) {
		ClojureNsState here = ctx.ns();
		if (here.referClojureOnly != null && !here.referClojureOnly.contains(name)) {
			return false;
		}
		return !here.referClojureExclude.contains(name);
	}

	/**
	 * One {@code (ns name doc? attr-map? clause...)} form: switches to the namespace
	 * (creating it, and marking it loaded like the oracle's {@code ns}, so a later
	 * {@code require} of it reads no file), wires the {@code :require} / {@code :use}
	 * aliases and refers -- loading each project namespace they name -- the
	 * {@code :import} class names and the {@code :refer-clojure} filter, and defines
	 * nothing itself. Returns the required namespaces' init calls, which run here, in
	 * program order. Metadata, the docstring and the attr map are ignored, like the
	 * declaration itself.
	 */
	static List<LispVal> processNs(ClojureLowering ctx, LispVal form) {
		List<LispVal> items = ClojureLowerUtil.items(form);
		if (items == null || items.size() < 2) {
			throw new LispReadException("ns takes a name: " + form.print());
		}
		if (!(ClojureLowerUtil.stripMeta(items.get(1)) instanceof LispSymbol name)) {
			throw new LispReadException("ns takes a name, not " + items.get(1).print());
		}
		ctx.currentNs = name.name();
		ctx.createdNamespaces.add(name.name());
		// *ns* switches before the clauses load anything, like the oracle's ns
		List<LispVal> calls = new ArrayList<>();
		calls.add(ctx.nsSwitch(name.name()));
		calls.addAll(nsClauses(ctx, items));
		// loaded once its clauses ran, like the oracle's ns: a require of it from a
		// namespace its own clauses load is a cycle, not a no-op
		ctx.loadedNamespaces.add(name.name());
		return calls;
	}

	private static List<LispVal> nsClauses(ClojureLowering ctx, List<LispVal> items) {
		List<LispVal> calls = new ArrayList<>();
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
				case ":require" -> calls.addAll(requireSpecs(ctx, parts.subList(1, parts.size()), false));
				case ":use" -> calls.addAll(requireSpecs(ctx, parts.subList(1, parts.size()), true));
				case ":import" -> importSpecs(ctx, parts.subList(1, parts.size()));
				case ":refer-clojure" -> referClojure(ctx, parts.subList(1, parts.size()));
				case ":load" -> calls.addAll(loadForms(ctx, parts.subList(1, parts.size())));
				case ":gen-class" -> {
					// a class is generated only by an AOT compile, which this front end
					// has no use for: the clause (and its options) declares nothing
				}
				default -> throw new LispReadException("ns clause " + head.name() + " is not supported yet");
			}
		}
		return calls;
	}

	/**
	 * {@code (in-ns 'name)}: switches to the namespace (creating it, but not marking it
	 * loaded -- the oracle's {@code in-ns} loads nothing), so the definitions and the
	 * {@code ::}-keywords below it belong there, and {@code *ns*} to it where it runs,
	 * answering nil (the oracle's answers the namespace). A quoted symbol, a bare symbol
	 * or a string names it directly (never evaluated); anything else leaves it alone.
	 */
	static LispVal inNsOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "in-ns takes a namespace");
		String name = inNsName(items.get(1));
		if (name == null) {
			return ClojureLowering.NIL_CONST;
		}
		ctx.currentNs = name;
		ctx.createdNamespaces.add(name);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), ctx.nsSwitch(name), ClojureLowering.NIL_CONST);
	}

	/**
	 * The namespace an {@code in-ns} argument names: a quoted symbol, a bare symbol or a
	 * string, never evaluated; null for anything else.
	 */
	static @Nullable String inNsName(LispVal arg) {
		if (arg instanceof LispSymbol sym) {
			return sym.name();
		}
		if (arg instanceof LispString text) {
			return text.value();
		}
		List<LispVal> quoted = ClojureLowerUtil.items(arg);
		if (quoted != null && quoted.size() == 2 && ClojureLowerUtil.isSymbolNamed(quoted.get(0), "quote")
				&& quoted.get(1) instanceof LispSymbol sym) {
			return sym.name();
		}
		return null;
	}

	/**
	 * The libspecs of a {@code :require} (or {@code :use}, which refers everything by
	 * default): {@code [ns :as alias :refer [vars]/:all]} vectors, bare library symbols
	 * and prefix lists -- each either bare or quoted ({@code 'spec}, the oracle's
	 * bare-{@code require} spelling; the {@code ns} clauses quote implicitly, so both
	 * paths share this parser). An unquoted vector spec stays accepted too (a lenient
	 * superset: the oracle rejects it with a {@code ClassNotFoundException}). A
	 * {@code :reload} flag re-runs every project namespace the call names,
	 * {@code :reload-all} with every transitive dependency first, like the oracle;
	 * {@code :verbose} is skipped, like every other bare flag. Returns the namespaces'
	 * init calls, in libspec order.
	 */
	static List<LispVal> requireSpecs(ClojureLowering ctx, List<LispVal> specs, boolean referAll) {
		ClojureLowering.LoadMode mode = ClojureLowering.LoadMode.GUARDED;
		for (LispVal spec : specs) {
			LispVal unwrapped = unwrapQuote(spec);
			if (unwrapped instanceof LispSymbol flag && flag.name().startsWith(":")) {
				if (flag.name().equals(":reload-all")) {
					mode = ClojureLowering.LoadMode.RELOAD_ALL;
				}
				else if (flag.name().equals(":reload") && mode == ClojureLowering.LoadMode.GUARDED) {
					mode = ClojureLowering.LoadMode.RELOAD;
				}
			}
		}
		List<LispVal> calls = new ArrayList<>();
		for (LispVal spec : specs) {
			LispVal unwrapped = unwrapQuote(spec);
			if (unwrapped instanceof LispSymbol flag && flag.name().startsWith(":")) {
				continue; // :reload, :reload-all, :verbose and friends name no library
			}
			if (unwrapped instanceof LispSymbol bare) {
				// a bare symbol names one library, like the oracle (a prefix
				// takes the list form below): (:use clojure.test) refers it all
				LispVal call = requireOne(ctx, null, List.of(bare), referAll, spec, mode);
				if (call != null) {
					calls.add(call);
				}
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
					LispVal prefixCall = requireOne(ctx, null, prefixParts, referAll, spec, mode);
					if (prefixCall != null) {
						calls.add(prefixCall);
					}
					continue;
				}
				for (int i = 1; i < prefixParts.size(); i++) {
					LispVal sub = unwrapQuote(prefixParts.get(i));
					if (sub instanceof LispSymbol sym) {
						LispVal subCall = requireOne(ctx, prefixName.name(), List.of(sym), referAll, spec, mode);
						if (subCall != null) {
							calls.add(subCall);
						}
						continue;
					}
					List<LispVal> subParts = ClojureLowerUtil.items(sub);
					if (subParts == null || subParts.isEmpty() || !ClojureBindingLowering.isVectorDatum(sub)
							|| subParts.size() < 2 || !(subParts.get(1) instanceof LispSymbol)) {
						throw new LispReadException("require takes library specs, not " + spec.print());
					}
					LispVal subCall = requireOne(ctx, prefixName.name(), subParts.subList(1, subParts.size()), referAll,
							spec, mode);
					if (subCall != null) {
						calls.add(subCall);
					}
				}
				continue;
			}
			List<LispVal> parts = ClojureLowerUtil.items(unwrapped);
			if (parts == null || parts.size() < 2 || !(parts.get(1) instanceof LispSymbol)) {
				throw new LispReadException("require takes library specs, not " + spec.print());
			}
			LispVal call = requireOne(ctx, null, parts.subList(1, parts.size()), referAll, spec, mode);
			if (call != null) {
				calls.add(call);
			}
		}
		return calls;
	}

	/**
	 * One libspec: the namespace loaded (a known library is a lowering, a project
	 * namespace its file, once per program read), then its {@code :as} alias and its
	 * refers wired into the current namespace. Like the oracle's {@code load-lib}, names
	 * are referred only by {@code use} or a {@code :refer} option -- a {@code require}
	 * with a bare {@code :only} or {@code :exclude} refers nothing -- and {@code :only}
	 * narrows the refer-all default of {@code use}, {@code :exclude} subtracting either
	 * way. Returns the namespace's init call in the call's load mode, or null when it has
	 * nothing to run.
	 */
	static @Nullable LispVal requireOne(ClojureLowering ctx, @Nullable String prefix, List<LispVal> parts, boolean use,
			LispVal spec, ClojureLowering.LoadMode mode) {
		String ns = ((LispSymbol) parts.get(0)).name();
		if (prefix != null) {
			ns = prefix + "." + ns;
		}
		String alias = null;
		String asAlias = null;
		boolean all = use;
		boolean refer = use;
		List<String> only = null;
		Set<String> exclude = new HashSet<>();
		Map<String, String> rename = Map.of();
		for (int i = 1; i < parts.size(); i += 2) {
			if (i + 1 >= parts.size() || !(parts.get(i) instanceof LispSymbol opt)) {
				throw new LispReadException("require takes option/value pairs, not " + spec.print());
			}
			LispVal arg = parts.get(i + 1);
			switch (opt.name()) {
				case ":as" -> {
					if (!(arg instanceof LispSymbol named)) {
						throw new LispReadException(":as takes an alias, not " + arg.print());
					}
					alias = named.name();
				}
				case ":as-alias" -> {
					if (!(arg instanceof LispSymbol named)) {
						throw new LispReadException(":as-alias takes an alias, not " + arg.print());
					}
					asAlias = named.name();
				}
				case ":refer" -> {
					refer = true;
					if (arg instanceof LispSymbol every && every.name().equals(":all")) {
						all = true;
					}
					else {
						only = referNames(arg, spec);
					}
				}
				case ":only" -> only = referNames(arg, spec);
				case ":exclude" -> exclude.addAll(referNames(arg, spec));
				case ":rename" -> rename = renameMap(arg, spec);
				default -> throw new LispReadException("require option " + opt.name() + " is not supported yet");
			}
		}
		if (asAlias != null && alias == null && !refer) {
			// an alias for the namespace's name alone: nothing is loaded or created
			ctx.ns().aliases.put(asAlias, ns);
			return null;
		}
		boolean library = isKnownNamespace(ns);
		if (ClojureKernelLowering.isKernelNamespace(ns)) {
			// the kernels are the built-in namespaces' own, no user surface
			String owner = ctx.loadingNamespaces.peek();
			if (owner == null || !ctx.builtinNamespaces.contains(owner)) {
				throw new LispReadException(ClojureKernelLowering.refusal(ns));
			}
			ctx.ns().aliases.putIfAbsent(ns, ns);
		}
		else if (library) {
			ctx.ns().aliases.putIfAbsent(ns, ns); // the fully-qualified spelling always
													// resolves
			ctx.requiredLibraries.add(ns);
		}
		else {
			if (ClojureBuiltinNamespaces.isLanguage(ns) && !ClojureBuiltinNamespaces.isShipped(ns)) {
				// the language's own libraries are lowerings or built-in files, never
				// project files; a contrib clojure.* library is found like any other
				throw new LispReadException("unknown namespace: " + ns);
			}
			// a dependency edge for :reload-all: the file being lowered owns it
			// (the innermost file on the loading stack), or the entry program's
			// namespace at its own top level
			String owner = !ctx.loadingNamespaces.isEmpty() ? ctx.loadingNamespaces.peek() : ctx.currentNs;
			if (!owner.equals(ns)) {
				ctx.namespaceDeps.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(ns);
			}
			loadNamespace(ctx, ns);
			if ((alias != null || refer) && !ctx.createdNamespaces.contains(ns)) {
				// the file defined no such namespace (its forms went into the
				// requiring one, like the oracle's load)
				throw new LispReadException(
						"namespace '" + ns + "' not found after loading '" + rootResource(ns) + "'");
			}
		}
		if (alias != null) {
			ctx.ns().aliases.put(alias, ns);
		}
		if (asAlias != null) {
			ctx.ns().aliases.put(asAlias, ns);
		}
		if (!refer) {
			return ctx.requireCall(ns, mode);
		}
		if (only != null) {
			for (String var : only) {
				if (!exclude.contains(var)) {
					checkReferable(ctx, ns, var, library);
					ctx.ns().refers.put(rename.getOrDefault(var, var), new ClojureLowering.VarRef(ns, var));
				}
			}
		}
		else if (all) {
			for (String var : library ? varsOf(ns) : publicVarsOf(ctx, ns)) {
				if (!exclude.contains(var)) {
					ctx.ns().refers.put(rename.getOrDefault(var, var), new ClojureLowering.VarRef(ns, var));
				}
			}
		}
		return ctx.requireCall(ns, mode);
	}

	/**
	 * Refuses a refer of a name the namespace does not export: a known library's unknown
	 * var by name, a project namespace's missing or private var with the oracle's words.
	 */
	static void checkReferable(ClojureLowering ctx, String ns, String var, boolean library) {
		refuseLeftOut(ctx, ns, var);
		if (library) {
			if (!isKnownVar(ns, var)) {
				throw new LispReadException("unknown name: " + ns + "/" + var);
			}
			return;
		}
		ClojureNsState state = ctx.namespaces.get(ns);
		Boolean isPrivate = state == null ? null : state.interns.get(var);
		if (isPrivate == null) {
			throw new LispReadException(var + " does not exist");
		}
		if (isPrivate) {
			throw new LispReadException(var + " is not public");
		}
	}

	/**
	 * What {@code use} and {@code :refer :all} bring in from a project namespace: every
	 * public var it interns ({@code def}, {@code defn}, {@code defmacro},
	 * {@code defmulti}, a protocol and its methods, record constructors, {@code deftest},
	 * ...), in definition order; private vars stay out, like the oracle's
	 * {@code ns-publics}.
	 */
	static List<String> publicVarsOf(ClojureLowering ctx, String ns) {
		ClojureNsState state = ctx.namespaces.get(ns);
		List<String> out = new ArrayList<>();
		if (state != null) {
			for (Map.Entry<String, Boolean> intern : state.interns.entrySet()) {
				if (!intern.getValue()) {
					out.add(intern.getKey());
				}
			}
		}
		return out;
	}

	/** The oracle's root resource of a namespace: {@code /demo/cyc_a}. */
	static String rootResource(String ns) {
		return "/" + ClojureSourcePath.scriptBaseOf(ns);
	}

	/**
	 * Loads a project namespace once per program read: nothing when it is already loaded
	 * (an {@code ns} form of the program, or an earlier {@code require}), else its file
	 * from the source path -- definitions ahead of the requiring form, statements into
	 * the namespace's init, whose flag and driver are emitted here -- lowered in place. A
	 * namespace already loading is the oracle's cyclic-load refusal; one no root holds is
	 * named with the roots searched.
	 */
	static void loadNamespace(ClojureLowering ctx, String ns) {
		if (ctx.loadedNamespaces.contains(ns)) {
			return;
		}
		if (ctx.loadingNamespaces.contains(ns)) {
			// the oracle's chain: the new request, then the pending loads innermost
			// first, the repeated one bracketed at both ends
			List<String> chain = new ArrayList<>();
			chain.add(ns);
			chain.addAll(ctx.loadingNamespaces);
			StringBuilder text = new StringBuilder();
			for (String pending : chain) {
				if (!text.isEmpty()) {
					text.append("->");
				}
				String resource = rootResource(pending);
				text.append(pending.equals(ns) ? "[ " + resource + " ]" : resource);
			}
			throw new LispReadException("Cyclic load dependency: " + text);
		}
		ClojureSourcePath.Found found = ctx.sourcePath.find(ns);
		if (found == null) {
			String notShipped = ClojureBuiltinNamespaces.notShipped(ns);
			if (notShipped != null) {
				throw new LispReadException(notShipped);
			}
			String base = ClojureSourcePath.scriptBaseOf(ns);
			throw new LispReadException("Could not locate " + base + ".clj or " + base + ".cljc on the source path"
					+ ctx.sourcePath.describeRoots());
		}
		if (found.builtin()) {
			String hostOnly = ctx.hostTarget ? null : ClojureBuiltinNamespaces.hostOnly(ns);
			if (hostOnly != null) {
				throw new LispReadException(hostOnly);
			}
			ctx.builtinNamespaces.add(ns);
		}
		ctx.loadFile(ns, found);
		ctx.emitNamespaceInit(ns);
	}

	/**
	 * Loads a shipped namespace the oracle loads before the program
	 * ({@link ClojureBuiltinNamespaces#isStartup}) where a qualified name first reaches
	 * it: its definitions ahead of the top-level datum at hand, its init (if any) run
	 * there behind its loaded flag.
	 * @param ctx the hub
	 * @param ns the namespace
	 */
	static void preload(ClojureLowering ctx, String ns) {
		loadNamespace(ctx, ns);
		LispVal init = ctx.requireCall(ns, ClojureLowering.LoadMode.GUARDED);
		if (init != null) {
			ctx.hoisted.add(init);
		}
	}

	/**
	 * A refer of the current namespace into a known library ({@code clojure.string},
	 * ...): the var it lowers through, or null (no refer, or a project var's, which
	 * resolves as a var instead).
	 */
	static ClojureLowering.@Nullable VarRef libraryRefer(ClojureLowering ctx, String name) {
		ClojureLowering.VarRef referred = ctx.ns().refers.get(name);
		return referred != null && isKnownNamespace(referred.ns()) ? referred : null;
	}

	/**
	 * Refuses a qualified name whose head names a project namespace but whose var it does
	 * not define, with the oracle's words ({@code No such var: l/nope}) -- such a name is
	 * never a class.
	 */
	static void refuseMissingVar(ClojureLowering ctx, String name) {
		int slash = ClojureLowering.qualifierSlash(name);
		String ns = slash > 0 ? ctx.projectNamespaceOf(name.substring(0, slash)) : null;
		if (ns != null && ctx.lookupVar(name) == null) {
			refuseLeftOut(ctx, ns, name.substring(slash + 1));
			throw new LispReadException("No such var: " + name);
		}
	}

	/**
	 * Refuses by name a var the oracle's namespace has and the built-in one leaves out
	 * ({@code ring.util.response/file-response}), when the namespace was loaded from the
	 * built-in file.
	 */
	static void refuseLeftOut(ClojureLowering ctx, String ns, String var) {
		String why = ctx.builtinNamespaces.contains(ns) ? ClojureBuiltinNamespaces.leftOut(ns, var)
				: ClojureWitLowering.refusalOf(ctx, ns, var);
		if (why != null) {
			throw new LispReadException(why);
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
			// a [pkg Class ...] vector leads with the reader's marker, a (pkg Class ...)
			// list does not
			int head = parts != null && !parts.isEmpty() && parts.get(0) == ClojureReader.VECTOR ? 1 : 0;
			if (parts == null || parts.size() <= head || !(parts.get(head) instanceof LispSymbol pack)) {
				throw new LispReadException("import takes class names, not " + spec.print());
			}
			for (int i = head + 1; i < parts.size(); i++) {
				if (!(parts.get(i) instanceof LispSymbol named)) {
					throw new LispReadException("import takes class names, not " + spec.print());
				}
				importClass(ctx, pack.name() + "." + named.name());
			}
		}
	}

	static void importClass(ClojureLowering ctx, String fqn) {
		int dot = fqn.lastIndexOf('.');
		ctx.ns().classNames.put(dot < 0 ? fqn : fqn.substring(dot + 1), fqn);
	}

	/**
	 * The options of a {@code (:refer-clojure ...)} clause: {@code :only},
	 * {@code :exclude}.
	 */
	static void referClojure(ClojureLowering ctx, List<LispVal> opts) {
		Map<String, String> rename = Map.of();
		for (int i = 0; i < opts.size(); i += 2) {
			if (i + 1 >= opts.size() || !(opts.get(i) instanceof LispSymbol opt)) {
				throw new LispReadException(":refer-clojure takes option/value pairs");
			}
			switch (opt.name()) {
				case ":only" -> ctx.ns().referClojureOnly = new HashSet<>(referNames(opts.get(i + 1), opts.get(i + 1)));
				case ":exclude" -> ctx.ns().referClojureExclude.addAll(referNames(opts.get(i + 1), opts.get(i + 1)));
				case ":rename" -> rename = renameMap(opts.get(i + 1), opts.get(i + 1));
				default -> throw new LispReadException(":refer-clojure option " + opt.name() + " is not supported yet");
			}
		}
		// a renamed core var is referred under its new name only; a name the clause
		// leaves out of the core renames nothing
		for (Map.Entry<String, String> entry : rename.entrySet()) {
			if (coreAllowed(ctx, entry.getKey())) {
				ctx.ns().referClojureExclude.add(entry.getKey());
				ctx.ns().coreRenames.put(entry.getValue(), entry.getKey());
			}
		}
	}

	/**
	 * The {@code :rename {old new}} map of a libspec or a {@code :refer-clojure} clause,
	 * as old name to new name.
	 */
	static Map<String, String> renameMap(LispVal arg, LispVal spec) {
		List<LispVal> entries = ClojureLowerUtil.items(arg);
		if (entries == null || entries.isEmpty() || !ClojureLowerUtil.isSymbolNamed(entries.get(0), "%hash-map")
				|| entries.size() % 2 == 0) {
			throw new LispReadException(":rename takes a map of names, not " + spec.print());
		}
		Map<String, String> renames = new LinkedHashMap<>();
		for (int i = 1; i < entries.size(); i += 2) {
			if (!(entries.get(i) instanceof LispSymbol from) || !(entries.get(i + 1) instanceof LispSymbol to)
					|| from.name().startsWith(":") || to.name().startsWith(":")) {
				throw new LispReadException(":rename takes a map of names, not " + spec.print());
			}
			renames.put(from.name(), to.name());
		}
		return renames;
	}

	/**
	 * The files of a {@code (load "path" ...)} call or a {@code (:load "path" ...)} ns
	 * clause: each lowered in place (once per program read, like a namespace file) and
	 * run unconditionally where the call stands, in the current namespace -- the file
	 * evaluates there and its own {@code in-ns} does not outlive it. A path is a string
	 * literal: the file is read while the program lowers.
	 * @return the calls that run the files' statements, in order
	 */
	static List<LispVal> loadForms(ClojureLowering ctx, List<LispVal> paths) {
		List<LispVal> calls = new ArrayList<>();
		for (LispVal arg : paths) {
			if (!(arg instanceof LispString path)) {
				throw new LispReadException("load takes string literal paths: " + arg.print());
			}
			LispVal call = loadOne(ctx, path.value());
			if (call != null) {
				calls.add(call);
			}
		}
		return calls;
	}

	/**
	 * One {@code load}: a path with a leading slash is root-relative, any other is
	 * relative to the directory of the current namespace's file, like the oracle's.
	 */
	private static @Nullable LispVal loadOne(ClojureLowering ctx, String path) {
		String relative;
		if (path.startsWith("/")) {
			relative = path.substring(1);
		}
		else {
			String own = ClojureSourcePath.resourceOf(ctx.currentNs);
			relative = own.substring(0, own.lastIndexOf('/') + 1) + path;
		}
		String unit = "load:" + ctx.currentNs + ":" + relative;
		if (!ctx.loadedNamespaces.contains(unit)) {
			if (ctx.loadingNamespaces.contains(unit)) {
				throw new LispReadException("Cyclic load dependency: /" + relative);
			}
			// the oracle's RT.load: the .clj under any root, then the .cljc
			ClojureSourcePath.Found found = ctx.sourcePath.findFile(relative + ".clj");
			if (found == null) {
				found = ctx.sourcePath.findFile(relative + ".cljc");
			}
			if (found == null) {
				throw new LispReadException("Could not locate " + relative + ".clj or " + relative
						+ ".cljc on the source path" + ctx.sourcePath.describeRoots());
			}
			ctx.unitFiles.put(unit, found.resource());
			ctx.loadFile(unit, found);
			ctx.emitNamespaceInit(unit);
		}
		return ctx.requireCall(unit, ClojureLowering.LoadMode.RELOAD);
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
		String ns = ctx.ns().aliases.get(head);
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
		String imported = ctx.ns().classNames.get(name);
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
		return ctx.ns().classNames.containsKey(head) || JAVA_LANG.contains(head) || head.indexOf('.') >= 0
				|| (!head.isEmpty() && Character.isUpperCase(head.charAt(0)));
	}

	// atoms and quotes

}
