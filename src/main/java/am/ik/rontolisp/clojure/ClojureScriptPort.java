package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * A namespace read from its ClojureScript port ({@code .kb/clojure-frontend.md}, "Classes
 * compiled against `clojure.lang`"): where a library's {@code .clj} imports a class that
 * cannot load here, the {@code .cljs} its root ships beside it is the namespace's file
 * ({@link ClojureSourcePath.Port}), and its forms lower as this front end's Clojure with
 * ClojureScript's names read as follows.
 * <ul>
 * <li>{@code cljs.core}: the shipped namespace {@value #NAMESPACE} holds what
 * {@code clojure.core} leaves out -- the protocols no {@code clojure.lang} interface
 * stands for, the functions of the ones that one does, the printing and hashing helpers
 * -- and the port's {@code ns} refers it all, as a ClojureScript namespace refers
 * {@code cljs.core}; {@code cljs.core/name} is that namespace's var, else
 * {@code clojure.core}'s.</li>
 * <li>A {@code cljs.core} protocol a {@code clojure.lang} interface stands for, in a
 * {@code deftype} or {@code reify} body, is that interface, its methods renamed
 * ({@code ISeqable}'s {@code -seq} is {@code Seqable}'s {@code seq}), so the core verbs
 * read the port's types as they read the interface's; a set's {@code contains} and
 * {@code get}, which ClojureScript asks the lookup for, are made over its lookup.</li>
 * <li>{@code ^:mutable} marks a mutable field, as {@code ^:unsynchronized-mutable}
 * does.</li>
 * <li>{@code :require-macros} requires the namespace, whose macros a Clojure namespace
 * holds with its functions.</li>
 * <li>{@code js/Math} and the bare {@code Math} are {@code java.lang.Math}, a whole
 * result an integer as JavaScript's numbers print; {@code js/Error} is
 * {@code java.lang.RuntimeException}; any other {@code js/} name refuses where it is
 * reached. {@code .slice} of an array or a string is JavaScript's.</li>
 * </ul>
 * A var the namespace's Clojure file has and its port lacks (datascript's storage, which
 * its {@code .cljc} files name in their {@code :clj} branches) refuses where it is
 * called, not where it is named.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojureScriptPort {

	/**
	 * The shipped namespace of the {@code cljs.core} names {@code clojure.core} lacks.
	 */
	static final String NAMESPACE = "rontolisp.internal.cljs-core";

	/** The prefix of a qualified {@code cljs.core} name. */
	private static final String CLJS_CORE = "cljs.core/";

	/**
	 * A {@code cljs.core} protocol a {@code clojure.lang} (or {@code java.lang})
	 * interface stands for: the interface's binary name and each protocol method's name
	 * there.
	 */
	private record Standin(String iface, Map<String, String> methods) {
	}

	/**
	 * The {@code cljs.core} protocols an interface here stands for. {@code IEquiv}'s
	 * {@code -equiv} is a collection's {@code equiv}, else {@code Object}'s
	 * {@code equals} ({@link #typeBody}).
	 */
	private static final Map<String, Standin> STANDINS = standins();

	/**
	 * The interfaces whose type is a collection to {@code =}: they extend
	 * {@code IPersistentCollection}.
	 */
	private static final Set<String> COLLECTIONS = Set.of("clojure.lang.IPersistentCollection",
			"clojure.lang.IPersistentSet", "clojure.lang.ISeq", "clojure.lang.IPersistentMap",
			"clojure.lang.Associative", "clojure.lang.IPersistentStack", "clojure.lang.IPersistentVector",
			"clojure.lang.IPersistentList");

	/**
	 * The set interfaces whose {@code contains} and {@code get} ClojureScript asks the
	 * lookup for.
	 */
	private static final Set<String> SETS = Set.of("clojure.lang.IPersistentSet", "clojure.lang.ITransientSet");

	private ClojureScriptPort() {
	}

	private static Map<String, Standin> standins() {
		Map<String, Standin> out = new LinkedHashMap<>();
		standin(out, "ISeqable", "clojure.lang.Seqable", "-seq", "seq");
		standin(out, "ICounted", "clojure.lang.Counted", "-count", "count");
		standin(out, "IIndexed", "clojure.lang.Indexed", "-nth", "nth");
		standin(out, "ILookup", "clojure.lang.ILookup", "-lookup", "valAt");
		standin(out, "IReduce", "clojure.lang.IReduce", "-reduce", "reduce");
		standin(out, "IKVReduce", "clojure.lang.IKVReduce", "-kv-reduce", "kvreduce");
		standin(out, "IFn", "clojure.lang.IFn", "-invoke", "invoke");
		standin(out, "IDeref", "clojure.lang.IDeref", "-deref", "deref");
		standin(out, "IMeta", "clojure.lang.IMeta", "-meta", "meta");
		standin(out, "IWithMeta", "clojure.lang.IObj", "-with-meta", "withMeta");
		standin(out, "ICollection", "clojure.lang.IPersistentCollection", "-conj", "cons");
		standin(out, "IEmptyableCollection", "clojure.lang.IPersistentCollection", "-empty", "empty");
		standin(out, "IEquiv", "clojure.lang.IPersistentCollection", "-equiv", "equiv");
		standin(out, "IHash", "clojure.lang.IHashEq", "-hash", "hasheq");
		standin(out, "ISet", "clojure.lang.IPersistentSet", "-disjoin", "disjoin");
		standin(out, "IAssociative", "clojure.lang.Associative", "-assoc", "assoc", "-contains-key?", "containsKey");
		standin(out, "IFind", "clojure.lang.Associative", "-find", "entryAt");
		standin(out, "IMap", "clojure.lang.IPersistentMap", "-dissoc", "without");
		standin(out, "IStack", "clojure.lang.IPersistentStack", "-peek", "peek", "-pop", "pop");
		standin(out, "IVector", "clojure.lang.IPersistentVector", "-assoc-n", "assocN");
		standin(out, "ISeq", "clojure.lang.ISeq", "-first", "first", "-rest", "more");
		standin(out, "INext", "clojure.lang.ISeq", "-next", "next");
		standin(out, "ISequential", "clojure.lang.Sequential");
		standin(out, "IList", "clojure.lang.IPersistentList");
		standin(out, "IReversible", "clojure.lang.Reversible", "-rseq", "rseq");
		standin(out, "ISorted", "clojure.lang.Sorted", "-sorted-seq", "seq", "-sorted-seq-from", "seqFrom",
				"-entry-key", "entryKey", "-comparator", "comparator");
		standin(out, "IPending", "clojure.lang.IPending", "-realized?", "isRealized");
		standin(out, "IEditableCollection", "clojure.lang.IEditableCollection", "-as-transient", "asTransient");
		standin(out, "ITransientCollection", "clojure.lang.ITransientCollection", "-conj!", "conj", "-persistent!",
				"persistent");
		standin(out, "ITransientAssociative", "clojure.lang.ITransientAssociative", "-assoc!", "assoc");
		standin(out, "ITransientMap", "clojure.lang.ITransientMap", "-dissoc!", "without");
		standin(out, "ITransientVector", "clojure.lang.ITransientVector", "-assoc-n!", "assocN", "-pop!", "pop");
		standin(out, "ITransientSet", "clojure.lang.ITransientSet", "-disjoin!", "disjoin");
		standin(out, "IComparable", "java.lang.Comparable", "-compare", "compareTo");
		standin(out, "IIterable", "java.lang.Iterable", "-iterator", "iterator");
		standin(out, "IReset", "clojure.lang.IAtom", "-reset!", "reset");
		standin(out, "ISwap", "clojure.lang.IAtom", "-swap!", "swap");
		standin(out, "IRecord", "clojure.lang.IRecord");
		return Map.copyOf(out);
	}

	private static void standin(Map<String, Standin> out, String protocol, String iface, String... renames) {
		Map<String, String> methods = new LinkedHashMap<>();
		for (int i = 0; i < renames.length; i += 2) {
			methods.put(renames[i], renames[i + 1]);
		}
		out.put(protocol, new Standin(iface, Map.copyOf(methods)));
	}

	/**
	 * The {@code cljs.core} names at a port's {@code ns}: {@value #NAMESPACE} loaded and
	 * every public var of it referred into the current namespace -- but a name its
	 * {@code :refer-clojure} leaves out, which the port defines itself -- and its types'
	 * class names imported.
	 * @param ctx the hub, in the port's namespace
	 */
	static void referCore(ClojureLowering ctx) {
		if (!ctx.loadedNamespaces.contains(NAMESPACE) && !ctx.loadingNamespaces.contains(NAMESPACE)) {
			ClojureNamespaceLowering.preload(ctx, NAMESPACE);
		}
		ClojureNsState here = ctx.ns();
		for (String var : ClojureNamespaceLowering.publicVarsOf(ctx, NAMESPACE)) {
			if (ClojureNamespaceLowering.coreAllowed(ctx, var) && !here.refers.containsKey(var)) {
				here.refers.put(var, new ClojureLowering.VarRef(NAMESPACE, var));
			}
		}
		String prefix = NAMESPACE + "/";
		for (Map.Entry<String, ClojureLowering.TypeDef> type : ctx.types.entrySet()) {
			if (type.getKey().startsWith(prefix)) {
				here.classNames.putIfAbsent(type.getValue().simpleName(), type.getValue().className());
			}
		}
	}

	/**
	 * A call a port's names give their own meaning: {@code cljs.core/name},
	 * {@code js/Math.member} and the bare {@code Math/member}, {@code js/Error.}, any
	 * other {@code js/} name, and {@code .slice}.
	 * @param ctx the hub, lowering a port
	 * @param name the head's name
	 * @param items the call, head included
	 * @return the lowered call, or {@code null} when the head means what it means in
	 * Clojure
	 */
	static @Nullable LispVal call(ClojureLowering ctx, String name, List<LispVal> items) {
		if (name.startsWith(CLJS_CORE) && name.length() > CLJS_CORE.length()) {
			return ctx.lower(withHead(items, new LispSymbol(coreVar(ctx, name.substring(CLJS_CORE.length())))));
		}
		if (name.equals(".slice")) {
			return ctx.lower(withHead(items, new LispSymbol(NAMESPACE + "/js-slice")));
		}
		String math = mathMember(ctx, name);
		if (math != null) {
			return ctx.lower(jsNumber(withHead(items, new LispSymbol("java.lang.Math/" + math))));
		}
		if (!name.startsWith("js/")) {
			return null;
		}
		if (name.equals("js/Error.")) {
			return ctx.lower(withHead(items, new LispSymbol("java.lang.RuntimeException.")));
		}
		return ClojureCoreLowering.refusedCall(ctx, items, jsRefusal(name));
	}

	/**
	 * A value a port's names give their own meaning: {@code cljs.core/name}, a
	 * {@code js/Math} or {@code Math} member, any other {@code js/} name.
	 * @param ctx the hub, lowering a port
	 * @param name the name
	 * @return the lowered value, or {@code null} when the name means what it means in
	 * Clojure
	 */
	static @Nullable LispVal value(ClojureLowering ctx, String name) {
		if (name.startsWith(CLJS_CORE) && name.length() > CLJS_CORE.length()) {
			return ctx.lower(new LispSymbol(coreVar(ctx, name.substring(CLJS_CORE.length()))));
		}
		String math = mathMember(ctx, name);
		if (math != null) {
			return ctx.lower(jsNumber(new LispSymbol("java.lang.Math/" + math)));
		}
		if (!name.startsWith("js/")) {
			return null;
		}
		return ClojureCoreLowering.refusedValue(jsRefusal(name));
	}

	/**
	 * The var a {@code cljs.core/name} spells: {@value #NAMESPACE}'s, else the core one.
	 */
	private static String coreVar(ClojureLowering ctx, String var) {
		if (!ctx.loadedNamespaces.contains(NAMESPACE) && !ctx.loadingNamespaces.contains(NAMESPACE)) {
			ClojureNamespaceLowering.preload(ctx, NAMESPACE);
		}
		ClojureNsState shim = ctx.namespaces.get(NAMESPACE);
		return shim != null && Boolean.FALSE.equals(shim.interns.get(var)) ? NAMESPACE + "/" + var
				: ClojureCoreNames.NAMESPACE + "/" + var;
	}

	/**
	 * The {@code java.lang.Math} member a name spells as JavaScript's {@code Math}:
	 * {@code js/Math.member}, or {@code Math/member} where no alias or class import names
	 * {@code Math} otherwise.
	 */
	private static @Nullable String mathMember(ClojureLowering ctx, String name) {
		if (name.startsWith("js/Math.") && name.length() > "js/Math.".length()) {
			return name.substring("js/Math.".length());
		}
		if (name.startsWith("Math/") && name.length() > "Math/".length() && !ctx.ns().aliases.containsKey("Math")
				&& !ctx.ns().classNames.containsKey("Math")) {
			return name.substring("Math/".length());
		}
		return null;
	}

	/** A JavaScript number read back: {@code (js-number form)}. */
	private static LispVal jsNumber(LispVal form) {
		return ClojureLowerUtil.list(new LispSymbol(NAMESPACE + "/js-number"), form);
	}

	private static LispVal withHead(List<LispVal> items, LispVal head) {
		List<LispVal> out = new ArrayList<>(items);
		out.set(0, head);
		return ClojureLowerUtil.list(out);
	}

	private static String jsRefusal(String name) {
		return name + " is not supported yet: a ClojureScript port reads js/Math and js/Error here,"
				+ " no other JavaScript global";
	}

	/**
	 * Whether a {@code deftype} field datum is mutable in a port: ClojureScript's
	 * {@code ^:mutable} marker.
	 * @param ctx the hub
	 * @param field the field datum
	 * @return whether it marks the field mutable
	 */
	static boolean isMutable(ClojureLowering ctx, LispVal field) {
		return ctx.clojureScript && ClojureLowerUtil.nameHasFlag(field, ":mutable");
	}

	/**
	 * The {@code :require-macros} clause of a port's {@code ns}: the namespaces required,
	 * their macros held with their functions; any other file refuses the clause like the
	 * oracle's {@code ns}.
	 * @param ctx the hub
	 * @param specs the clause's libspecs
	 * @return the namespaces' init calls
	 */
	static List<LispVal> requireMacros(ClojureLowering ctx, List<LispVal> specs) {
		if (!ctx.clojureScript) {
			throw new LispReadException("ns clause :require-macros is not supported yet");
		}
		return ClojureNamespaceLowering.requireSpecs(ctx, specs, false);
	}

	/**
	 * A port's {@code deftype} or {@code reify} body read as this front end's: each group
	 * naming a {@code cljs.core} protocol an interface stands for names the interface,
	 * its methods renamed to the interface's; {@code IEquiv}'s {@code -equiv} is
	 * {@code Object}'s {@code equals} where no collection interface makes it the
	 * collection's {@code equiv}. A set's {@code contains} and {@code get} are made over
	 * its lookup, as ClojureScript's {@code contains?} and {@code get} ask the lookup,
	 * and a persistent set is a {@code java.util.Set} too, as every set class of the
	 * oracle's is, so a core set compares equal to it ({@link #setMethods}). A group that
	 * names a program protocol (the port's own, or one of {@value #NAMESPACE}) keeps it;
	 * any other {@code cljs.core} protocol refuses by name.
	 * @param ctx the hub
	 * @param rest the body: group symbols and method implementations
	 * @param what the defining form
	 * @return the body to parse
	 */
	static List<LispVal> typeBody(ClojureLowering ctx, List<LispVal> rest, String what) {
		if (!ctx.clojureScript) {
			return rest;
		}
		boolean collection = false;
		boolean lookup = false;
		boolean set = false;
		boolean persistentSet = false;
		for (LispVal datum : rest) {
			Standin standin = standinOf(ctx, datum);
			if (standin != null) {
				collection |= COLLECTIONS.contains(standin.iface()) && !standin.methods().containsKey("-equiv");
				lookup |= standin.iface().equals("clojure.lang.ILookup");
				set |= SETS.contains(standin.iface());
				persistentSet |= standin.iface().equals("clojure.lang.IPersistentSet");
			}
		}
		List<LispVal> out = new ArrayList<>();
		Standin current = null;
		List<String> defined = new ArrayList<>();
		Set<String> arities = new HashSet<>();
		for (LispVal datum : rest) {
			if (datum instanceof LispSymbol group && !group.name().startsWith(":")) {
				current = standinOf(ctx, datum);
				if (current == null) {
					refuseUnknown(ctx, group.name(), what);
					out.add(datum);
				}
				else if (current.methods().containsKey("-equiv") && !collection) {
					out.add(new LispSymbol("Object"));
				}
				else {
					out.add(new LispSymbol(current.iface()));
				}
				continue;
			}
			List<LispVal> impl = ClojureLowerUtil.items(datum);
			if (current != null && impl != null && !impl.isEmpty()
					&& ClojureLowerUtil.stripMeta(impl.get(0)) instanceof LispSymbol method) {
				String renamed = current.methods().get(method.name());
				if (renamed != null && renamed.equals("equiv") && !collection) {
					renamed = "equals";
				}
				if (renamed != null) {
					// two protocols' methods one interface method stands for (ISet's
					// -disjoin and ITransientSet's -disjoin!, a set being its own
					// transient) are one method here: the first defined
					if (arities.add(renamed + "/" + paramCount(impl))) {
						defined.add(renamed);
						out.add(new LispCons(new LispSymbol(renamed),
								((LispCons) ClojureLowerUtil.stripMeta(datum)).cdr()));
					}
					continue;
				}
				defined.add(method.name());
			}
			out.add(datum);
		}
		if (set) {
			out.addAll(setMethods(lookup, persistentSet, defined));
		}
		return out;
	}

	/** The parameter count of an inline method, or -1 for one of another shape. */
	private static int paramCount(List<LispVal> impl) {
		if (impl.size() < 2 || !ClojureBindingLowering.isVectorDatum(impl.get(1))) {
			return -1;
		}
		List<LispVal> params = ClojureLowerUtil.items(ClojureLowerUtil.stripMeta(impl.get(1)));
		return params == null ? -1 : params.size() - 1;
	}

	/**
	 * The stand-in a group symbol names, or null: a program protocol of the name (the
	 * port's own, or one {@value #NAMESPACE} refers) and a defined interface keep their
	 * meaning, as does any dotted class name.
	 */
	private static @Nullable Standin standinOf(ClojureLowering ctx, LispVal datum) {
		if (!(datum instanceof LispSymbol group) || group.name().startsWith(":")) {
			return null;
		}
		String name = group.name().startsWith(CLJS_CORE) ? group.name().substring(CLJS_CORE.length()) : group.name();
		if (name.equals(group.name()) && (ClojureProtocolLowering.protocolOf(ctx, name) != null
				|| ClojureProtocolLowering.definedInterfaceOf(ctx, name) != null)) {
			return null;
		}
		return STANDINS.get(name);
	}

	/**
	 * Refuses a group naming a {@code cljs.core} protocol nothing here stands for: one
	 * spelled {@code cljs.core/IName}, or an undotted {@code IName} no protocol, defined
	 * interface, import or {@code java.lang} class claims. Anything else is the group the
	 * Clojure body parse reads ({@code Object}, a dotted interface).
	 */
	private static void refuseUnknown(ClojureLowering ctx, String name, String what) {
		boolean qualified = name.startsWith(CLJS_CORE);
		String bare = qualified ? name.substring(CLJS_CORE.length()) : name;
		boolean protocolShaped = bare.length() > 1 && bare.charAt(0) == 'I' && Character.isUpperCase(bare.charAt(1))
				&& bare.indexOf('.') < 0;
		boolean claimed = ClojureProtocolLowering.protocolOf(ctx, bare) != null
				|| ClojureProtocolLowering.definedInterfaceOf(ctx, bare) != null
				|| ctx.ns().classNames.containsKey(bare) || ClojureHostClasses.exists("java.lang." + bare);
		if (qualified || protocolShaped && !claimed) {
			throw new LispReadException("cljs.core/" + bare + " is not supported yet as a protocol of a " + what
					+ " in a ClojureScript port");
		}
	}

	/**
	 * What a set's body leaves to ClojureScript's verbs, appended to it: its
	 * {@code contains} and {@code get} over its lookup ({@code valAt}), which
	 * ClojureScript's {@code contains?} and {@code get} ask -- with no lookup a set holds
	 * nothing --, and for a persistent set the {@code java.util.Set} every set class of
	 * the oracle's is, so a core set compares equal to it as ClojureScript's compares a
	 * set by count and members: its {@code size}, {@code isEmpty} and {@code iterator}
	 * over its count and seq. A method serves every interface declaring it, so each is
	 * made once.
	 */
	private static List<LispVal> setMethods(boolean lookup, boolean persistent, List<String> defined) {
		List<LispVal> out = new ArrayList<>();
		LispSymbol self = new LispSymbol("this%");
		LispSymbol key = new LispSymbol("k%");
		LispVal pair = ClojureLowerUtil.list(ClojureReader.VECTOR, self, key);
		LispVal one = ClojureLowerUtil.list(ClojureReader.VECTOR, self);
		if (persistent) {
			out.add(new LispSymbol("java.util.Set"));
		}
		if (!defined.contains("contains")) {
			LispVal body = lookup ? ClojureLowerUtil.list(new LispSymbol(NAMESPACE + "/lookup-contains?"), self, key)
					: new LispSymbol("false");
			out.add(ClojureLowerUtil.list(new LispSymbol("contains"), pair, body));
		}
		if (!defined.contains("get")) {
			LispVal body = lookup ? ClojureLowerUtil.list(new LispSymbol(".valAt"), self, key) : new LispSymbol("nil");
			out.add(ClojureLowerUtil.list(new LispSymbol("get"), pair, body));
		}
		if (persistent && !defined.contains("size")) {
			out.add(ClojureLowerUtil.list(new LispSymbol("size"), one,
					ClojureLowerUtil.list(new LispSymbol("clojure.core/count"), self)));
		}
		if (persistent && !defined.contains("isEmpty")) {
			out.add(ClojureLowerUtil.list(new LispSymbol("isEmpty"), one,
					ClojureLowerUtil.list(new LispSymbol("clojure.core/nil?"),
							ClojureLowerUtil.list(new LispSymbol("clojure.core/seq"), self))));
		}
		if (persistent && !defined.contains("iterator")) {
			// over the seq, as the oracle's APersistentSet's: RT/iter of the set would
			// ask this iterator again
			out.add(ClojureLowerUtil.list(new LispSymbol("iterator"), one,
					ClojureLowerUtil.list(new LispSymbol("clojure.lang.SeqIterator."),
							ClojureLowerUtil.list(new LispSymbol("clojure.core/seq"), self))));
		}
		return out;
	}

	/**
	 * A call of a var of a namespace read from its port that the port does not define:
	 * the refusal, where the call runs.
	 * @param ctx the hub
	 * @param name the qualified name
	 * @param items the call, head included
	 * @return the lowered call, or {@code null} when the name is no such var
	 */
	static @Nullable LispVal missingVarCall(ClojureLowering ctx, String name, List<LispVal> items) {
		String why = missingVar(ctx, name);
		return why == null ? null : ClojureCoreLowering.refusedCall(ctx, items, why);
	}

	/**
	 * {@link #missingVarCall} as a value: a function refusing when called.
	 * @param ctx the hub
	 * @param name the qualified name
	 * @return the value, or {@code null} when the name is no such var
	 */
	static @Nullable LispVal missingVarValue(ClojureLowering ctx, String name) {
		String why = missingVar(ctx, name);
		return why == null ? null : ClojureCoreLowering.refusedValue(why);
	}

	private static @Nullable String missingVar(ClojureLowering ctx, String name) {
		int slash = ClojureLowering.qualifierSlash(name);
		if (slash < 0 || ctx.portNamespaces.isEmpty()) {
			return null;
		}
		String ns = ctx.projectNamespaceOf(name.substring(0, slash));
		ClojureSourcePath.Port port = ns == null ? null : ctx.portNamespaces.get(ns);
		if (ns == null || port == null || ctx.lookupVar(name) != null) {
			return null;
		}
		return ns + "/" + name.substring(slash + 1) + " is not defined by " + ctx.fileOf(ns)
				+ ", the ClojureScript port read for " + ns + ": " + port.clojureFile() + " imports " + port.imported()
				+ ", which cannot load here without " + port.missing();
	}

}
