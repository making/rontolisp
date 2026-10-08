package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The host interfaces a {@code reify}, {@code deftype} or {@code defrecord} body
 * implements beside its protocols: the {@code clojure.lang} interfaces the core verbs
 * consult, and {@code java.lang.Object}'s overridable methods, which any body defines
 * without naming {@code Object}. Each row is read off clj 1.12.6 by reflection
 * (2026-10-08): the methods an interface declares, with their parameter counts counting
 * the target, and its direct super-interfaces, which a body naming it implements too.
 *
 * <p>
 * A body's methods are matched by name and parameter count against every interface and
 * protocol it names and against {@code Object}'s, whichever group they stand under, like
 * the oracle's class: a {@code toString} under a protocol group overrides
 * {@code Object}'s. The type stores each interface's methods in that interface's family
 * of the run-time library ({@link ClojureArms.Family}), whose arms are what the core
 * verbs consult, so a program naming none of an interface's family compiles as before.
 */
final class ClojureInterfaces {

	/**
	 * The class every body's {@code toString}, {@code equals} and {@code hashCode}
	 * override.
	 */
	static final String OBJECT = "java.lang.Object";

	/**
	 * The store of an {@code IReduceInit}, {@code IReduce} or {@code IKVReduce} row:
	 * {@code (store tag interface-names methods)}, each family's store taking the same
	 * arguments ({@code clojure.lisp}, "Host interfaces").
	 */
	static final String REDUCE_ROW = "RONTOLISP::%CLOJURE-REDUCE-INTERFACE-ROW";

	/** The store of a {@code Seqable} row. */
	static final String SEQABLE_ROW = "RONTOLISP::%CLOJURE-SEQABLE-ROW";

	/** The store of a {@code Counted} row. */
	static final String COUNTED_ROW = "RONTOLISP::%CLOJURE-COUNTED-ROW";

	/** The store of an {@code Indexed} row. */
	static final String INDEXED_ROW = "RONTOLISP::%CLOJURE-INDEXED-ROW";

	/** The store of an {@code ILookup} row. */
	static final String LOOKUP_ROW = "RONTOLISP::%CLOJURE-LOOKUP-ROW";

	/** The store of an {@code IFn}, {@code Callable} or {@code Runnable} row. */
	static final String INVOKABLE_ROW = "RONTOLISP::%CLOJURE-INVOKABLE-ROW";

	/** The store of an {@code IDeref} row. */
	static final String DEREFABLE_ROW = "RONTOLISP::%CLOJURE-DEREFABLE-ROW";

	/** The store of an {@code IMeta} or {@code IObj} row. */
	static final String META_ROW = "RONTOLISP::%CLOJURE-META-ROW";

	/** The store of an {@code Object} override's row. */
	static final String OBJECT_ROW = "RONTOLISP::%CLOJURE-OBJECT-ROW";

	/**
	 * The view of a collection {@code vec} and {@code set} walk: a value implementing
	 * {@code IReduceInit} answers the members its reduction steps, anything else itself
	 * ({@link ClojureArms.Family#REDUCE_INTERFACE}).
	 */
	static final String REDUCE_INIT_ITEMS = "RONTOLISP::%CLOJURE-REDUCE-INIT-ITEMS";

	/**
	 * The view of {@code apply}'s function: a value implementing {@code IFn} answers a
	 * function calling its {@code applyTo} over the argument seq, like the oracle's
	 * {@code apply}, anything else itself ({@link ClojureArms.Family#INVOKABLE}).
	 */
	static final String APPLIED_FN = "RONTOLISP::%CLOJURE-APPLIED-FN";

	/**
	 * The library's reader of a value's interface row entry: an interface flag or a
	 * method.
	 */
	static final String ENTRY = "RONTOLISP::%CLOJURE-INTERFACE-ENTRY";

	/** Each family's store. */
	private static final Map<ClojureArms.Family, String> STORES = Map.of(ClojureArms.Family.REDUCE_INTERFACE,
			REDUCE_ROW, ClojureArms.Family.SEQABLE, SEQABLE_ROW, ClojureArms.Family.COUNTED, COUNTED_ROW,
			ClojureArms.Family.INDEXED, INDEXED_ROW, ClojureArms.Family.LOOKUP, LOOKUP_ROW,
			ClojureArms.Family.INVOKABLE, INVOKABLE_ROW, ClojureArms.Family.DEREFABLE, DEREFABLE_ROW,
			ClojureArms.Family.META_INTERFACE, META_ROW, ClojureArms.Family.OBJECT_METHODS, OBJECT_ROW);

	/**
	 * One interface: its binary name, the methods it declares (name to the parameter
	 * counts, the target included), its direct super-interfaces, the family of the
	 * run-time library its methods are stored in, and the library's test of a value whose
	 * type implements it ({@code instance?} reads it).
	 */
	record HostInterface(String name, Map<String, Set<Integer>> methods, List<String> supers, ClojureArms.Family family,
			String test) {
	}

	/**
	 * A body's implementation of its interfaces: the interfaces it names with their
	 * supers ({@link #closure}), and the arities it defines per method name, an
	 * {@code Object} override's too, in body order.
	 */
	record InterfaceBody(List<HostInterface> closure, Map<String, List<ClojureLowering.MethodArity>> methods) {

		/** No interface and no override: a body of protocols alone. */
		static final InterfaceBody EMPTY = new InterfaceBody(List.of(), Map.of());

		boolean isEmpty() {
			return this.closure.isEmpty() && this.methods.isEmpty();
		}

	}

	/**
	 * What builds a defined method's lambda for the defining form: over its arities, with
	 * the target first like a protocol method's, the fields of a record or deftype in
	 * scope. {@code dispatch} says the lambda dispatches on the call's count, answering
	 * {@code fallback} for a count the body leaves out; else it is the one arity's
	 * lambda.
	 */
	interface LambdaBuilder {

		LispVal build(ClojureLowering.TypeMethod impl, boolean dispatch, LispVal fallback);

	}

	/** The interfaces a body may name, by binary name, in declaration order. */
	private static final Map<String, HostInterface> INTERFACES = table();

	/**
	 * {@code Object}'s methods a body may override: no test, since every value but nil is
	 * an {@code Object}.
	 */
	static final HostInterface OBJECT_METHODS = new HostInterface(OBJECT,
			methods("toString", 1, "equals", 2, "hashCode", 1), List.of(), ClojureArms.Family.OBJECT_METHODS, "");

	/**
	 * Every public interface of {@code clojure.lang} in clj 1.12.6 (read off the jar,
	 * 2026-10-08): a body naming one this class does not support is refused by name,
	 * where any other dotted name is no class at all.
	 */
	private static final Set<String> CLOJURE_LANG = Set.of("clojure.lang.Associative", "clojure.lang.Counted",
			"clojure.lang.Fn", "clojure.lang.IAtom", "clojure.lang.IAtom2", "clojure.lang.IBlockingDeref",
			"clojure.lang.IChunk", "clojure.lang.IChunkedSeq", "clojure.lang.IDeref", "clojure.lang.IDrop",
			"clojure.lang.IEditableCollection", "clojure.lang.IExceptionInfo", "clojure.lang.IFn",
			"clojure.lang.IHashEq", "clojure.lang.IKVReduce", "clojure.lang.IKeywordLookup", "clojure.lang.ILookup",
			"clojure.lang.ILookupSite", "clojure.lang.ILookupThunk", "clojure.lang.IMapEntry",
			"clojure.lang.IMapIterable", "clojure.lang.IMeta", "clojure.lang.IObj", "clojure.lang.IPending",
			"clojure.lang.IPersistentCollection", "clojure.lang.IPersistentList", "clojure.lang.IPersistentMap",
			"clojure.lang.IPersistentSet", "clojure.lang.IPersistentStack", "clojure.lang.IPersistentVector",
			"clojure.lang.IProxy", "clojure.lang.IRecord", "clojure.lang.IReduce", "clojure.lang.IReduceInit",
			"clojure.lang.IRef", "clojure.lang.IReference", "clojure.lang.ISeq", "clojure.lang.ITransientAssociative",
			"clojure.lang.ITransientAssociative2", "clojure.lang.ITransientCollection", "clojure.lang.ITransientMap",
			"clojure.lang.ITransientSet", "clojure.lang.ITransientVector", "clojure.lang.IType", "clojure.lang.Indexed",
			"clojure.lang.IndexedSeq", "clojure.lang.MapEquivalence", "clojure.lang.Named", "clojure.lang.Reversible",
			"clojure.lang.Seqable", "clojure.lang.Sequential", "clojure.lang.Settable", "clojure.lang.Sorted",
			"clojure.lang.WarnBoxedMath");

	/**
	 * The supported interfaces a record implements itself (its {@code supers}), whose
	 * methods a {@code defrecord} body may not define again: the oracle's
	 * {@code Duplicate method name}.
	 */
	private static final Set<String> RECORD_PROVIDED = Set.of("clojure.lang.Counted", "clojure.lang.Seqable",
			"clojure.lang.ILookup", "clojure.lang.IMeta", "clojure.lang.IObj");

	/**
	 * The supported interfaces among a record class's own direct ones, which a
	 * {@code defrecord} body may not name: the oracle's {@code Duplicate interface name}.
	 */
	private static final Set<String> RECORD_DIRECT = Set.of("clojure.lang.ILookup", "clojure.lang.IObj");

	/**
	 * The supported interfaces every reify class implements itself (metadata), whose
	 * methods a {@code reify} body may not define again.
	 */
	private static final Set<String> REIFY_PROVIDED = Set.of("clojure.lang.IMeta", "clojure.lang.IObj");

	/**
	 * The supported interface among a reify class's own direct ones, which a
	 * {@code reify} body may not name.
	 */
	private static final Set<String> REIFY_DIRECT = Set.of("clojure.lang.IObj");

	private ClojureInterfaces() {
	}

	private static Map<String, HostInterface> table() {
		Map<String, HostInterface> table = new LinkedHashMap<>();
		ClojureArms.Family reduce = ClojureArms.Family.REDUCE_INTERFACE;
		add(table, "clojure.lang.IReduceInit", methods("reduce", 3), List.of(), reduce,
				"RONTOLISP::%CLOJURE-REDUCE-INIT-P");
		add(table, "clojure.lang.IReduce", methods("reduce", 2), List.of("clojure.lang.IReduceInit"), reduce,
				"RONTOLISP::%CLOJURE-IREDUCE-P");
		add(table, "clojure.lang.IKVReduce", methods("kvreduce", 3), List.of(), reduce,
				"RONTOLISP::%CLOJURE-KVREDUCE-P");
		add(table, "clojure.lang.Seqable", methods("seq", 1), List.of(), ClojureArms.Family.SEQABLE,
				"RONTOLISP::%CLOJURE-SEQABLE-P");
		add(table, "clojure.lang.Counted", methods("count", 1), List.of(), ClojureArms.Family.COUNTED,
				"RONTOLISP::%CLOJURE-COUNTED-P");
		add(table, "clojure.lang.Indexed", methods("nth", 2, "nth", 3), List.of("clojure.lang.Counted"),
				ClojureArms.Family.INDEXED, "RONTOLISP::%CLOJURE-INDEXED-P");
		add(table, "clojure.lang.ILookup", methods("valAt", 2, "valAt", 3), List.of(), ClojureArms.Family.LOOKUP,
				"RONTOLISP::%CLOJURE-LOOKUP-P");
		Map<String, Set<Integer>> invoke = new LinkedHashMap<>();
		Set<Integer> counts = new LinkedHashSet<>();
		for (int n = 1; n <= 22; n++) {
			counts.add(n);
		}
		invoke.put("invoke", Set.copyOf(counts));
		invoke.put("applyTo", Set.of(2));
		add(table, "clojure.lang.IFn", invoke, List.of("java.util.concurrent.Callable", "java.lang.Runnable"),
				ClojureArms.Family.INVOKABLE, "RONTOLISP::%CLOJURE-INVOKABLE-P");
		add(table, "java.util.concurrent.Callable", methods("call", 1), List.of(), ClojureArms.Family.INVOKABLE,
				"RONTOLISP::%CLOJURE-CALLABLE-P");
		add(table, "java.lang.Runnable", methods("run", 1), List.of(), ClojureArms.Family.INVOKABLE,
				"RONTOLISP::%CLOJURE-RUNNABLE-P");
		add(table, "clojure.lang.IDeref", methods("deref", 1), List.of(), ClojureArms.Family.DEREFABLE,
				"RONTOLISP::%CLOJURE-DEREFABLE-P");
		add(table, "clojure.lang.IMeta", methods("meta", 1), List.of(), ClojureArms.Family.META_INTERFACE,
				"RONTOLISP::%CLOJURE-IMETA-P");
		add(table, "clojure.lang.IObj", methods("withMeta", 2), List.of("clojure.lang.IMeta"),
				ClojureArms.Family.META_INTERFACE, "RONTOLISP::%CLOJURE-IOBJ-P");
		return table;
	}

	private static void add(Map<String, HostInterface> table, String name, Map<String, Set<Integer>> methods,
			List<String> supers, ClojureArms.Family family, String test) {
		table.put(name, new HostInterface(name, methods, supers, family, test));
	}

	/** Alternating method names and parameter counts, a name repeated per arity. */
	private static Map<String, Set<Integer>> methods(Object... pairs) {
		Map<String, Set<Integer>> methods = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			methods.computeIfAbsent((String) pairs[i], ignored -> new LinkedHashSet<>()).add((Integer) pairs[i + 1]);
		}
		Map<String, Set<Integer>> out = new LinkedHashMap<>();
		methods.forEach((name, counts) -> out.put(name, Set.copyOf(counts)));
		return out;
	}

	/**
	 * The supported interface a binary name names, or null.
	 * @param name the binary name
	 * @return the interface, or null
	 */
	static @Nullable HostInterface named(String name) {
		return INTERFACES.get(name);
	}

	/**
	 * The interface a body's group symbol names: imported, a {@code java.lang} default or
	 * spelled with its package, like the oracle's ({@code clojure.lang} is no default
	 * import); {@code Object} answers {@link #OBJECT_METHODS}. A name of a known
	 * interface this class does not support, of a class that is no interface or of
	 * nothing at all is refused in the oracle's words where it has some.
	 * @param ctx the hub
	 * @param symbol the group symbol, which names no protocol
	 * @param what the defining form, for the refusals
	 * @return the interface
	 */
	static HostInterface resolve(ClojureLowering ctx, String symbol, String what) {
		String name = ClojureNamespaceLowering.resolveClass(ctx, symbol);
		HostInterface known = INTERFACES.get(name);
		if (known != null) {
			return known;
		}
		if (name.equals(OBJECT)) {
			return OBJECT_METHODS;
		}
		if (CLOJURE_LANG.contains(name)) {
			throw new LispReadException(name + " is not supported yet as an interface of " + what);
		}
		if (name.indexOf('.') < 0) {
			throw new LispReadException("Unable to resolve symbol: " + symbol + " in this context");
		}
		Class<?> host;
		try {
			host = ClojureHostClasses.load(name);
		}
		catch (ClassNotFoundException | LinkageError _) {
			throw new LispReadException("Unable to resolve classname: " + name);
		}
		if (!host.isInterface()) {
			throw new LispReadException("only interfaces are supported, had: " + name);
		}
		throw new LispReadException(name + " is not supported yet as an interface of " + what);
	}

	/**
	 * The interfaces a body names and their supers, each once, a named one ahead of its
	 * supers.
	 * @param named the interfaces the body names, in body order
	 * @return the closure
	 */
	static List<HostInterface> closure(List<HostInterface> named) {
		Map<String, HostInterface> out = new LinkedHashMap<>();
		for (HostInterface one : named) {
			addClosure(one, out);
		}
		return List.copyOf(out.values());
	}

	private static void addClosure(HostInterface one, Map<String, HostInterface> out) {
		if (out.putIfAbsent(one.name(), one) != null) {
			return;
		}
		for (String parent : one.supers()) {
			HostInterface known = INTERFACES.get(parent);
			if (known != null) {
				addClosure(known, out);
			}
		}
	}

	/**
	 * The interface among the closure (or {@code Object}) that declares a method at a
	 * parameter count, the first in order; null when none does.
	 * @param closure the body's interfaces
	 * @param method the method name
	 * @param count the parameter count, the target included
	 * @return the declaring interface, or null
	 */
	static @Nullable HostInterface declaring(List<HostInterface> closure, String method, int count) {
		for (HostInterface one : closure) {
			Set<Integer> counts = one.methods().get(method);
			if (counts != null && counts.contains(count)) {
				return one;
			}
		}
		Set<Integer> object = OBJECT_METHODS.methods().get(method);
		return object != null && object.contains(count) ? OBJECT_METHODS : null;
	}

	/**
	 * Every parameter count the closure (and {@code Object}) declares for a method name.
	 * @param closure the body's interfaces
	 * @param method the method name
	 * @return the counts, ascending
	 */
	static List<Integer> declaredCounts(List<HostInterface> closure, String method) {
		Set<Integer> counts = new TreeSet<>();
		for (HostInterface one : closure) {
			Set<Integer> declared = one.methods().get(method);
			if (declared != null) {
				counts.addAll(declared);
			}
		}
		Set<Integer> object = OBJECT_METHODS.methods().get(method);
		if (object != null) {
			counts.addAll(object);
		}
		return new ArrayList<>(counts);
	}

	/**
	 * The test an instance call {@code (.method x args...)} asks of a record, deftype or
	 * reify whose type implements the method at that count: the declaring interface's
	 * test, or for an {@code Object} override the test of that method; null when no
	 * supported interface declares it. {@code toString} answers through {@code str}.
	 * @param method the method name
	 * @param count the parameter count, the target included
	 * @return the test, or null
	 */
	static @Nullable String instanceTest(String method, int count) {
		for (HostInterface one : INTERFACES.values()) {
			Set<Integer> counts = one.methods().get(method);
			if (counts != null && counts.contains(count)) {
				return one.test();
			}
		}
		return switch (method + "/" + count) {
			case "equals/2" -> "RONTOLISP::%CLOJURE-EQUALS-P";
			case "hashCode/1" -> "RONTOLISP::%CLOJURE-HASH-CODE-P";
			default -> null;
		};
	}

	/**
	 * Refuses an interface a {@code defrecord} or {@code reify} class implements itself
	 * among its direct ones, in the oracle's words.
	 * @param what the defining form
	 * @param named an interface the body names
	 */
	static void checkNamed(String what, HostInterface named) {
		Set<String> direct = switch (what) {
			case "defrecord" -> RECORD_DIRECT;
			case "reify" -> REIFY_DIRECT;
			default -> Set.of();
		};
		if (direct.contains(named.name())) {
			throw new LispReadException(
					"Duplicate interface name \"" + named.name().replace('.', '/') + "\" in " + what);
		}
	}

	/**
	 * Whether the class a form defines implements the interface itself (a record its map
	 * interfaces, a reify its metadata), so a body naming it stores no row: the value's
	 * own verbs already answer.
	 * @param what the defining form
	 * @param one an interface of the body's closure
	 * @return {@code true} for one of the class's own
	 */
	static boolean provides(String what, HostInterface one) {
		return switch (what) {
			case "defrecord" -> RECORD_PROVIDED.contains(one.name());
			case "reify" -> REIFY_PROVIDED.contains(one.name());
			default -> false;
		};
	}

	/**
	 * Refuses a method the class a form defines implements itself, in the oracle's words:
	 * one of an interface it provides ({@link #provides}), or a record's {@code equals}
	 * and {@code hashCode}.
	 * @param what the defining form
	 * @param declaring the interface declaring the method
	 * @param method the method name
	 */
	static void checkMethod(String what, HostInterface declaring, String method) {
		if (provides(what, declaring)
				|| what.equals("defrecord") && declaring == OBJECT_METHODS && !method.equals("toString")) {
			throw new LispReadException("Duplicate method name \"" + method + "\" in " + what);
		}
	}

	/**
	 * The stores of a body's interface rows under the tag, one per family of its closure
	 * and one for its {@code Object} overrides: each names the family's interfaces it
	 * implements and holds a lambda per method name -- the body's, dispatching on the
	 * call's count when the interfaces declare several, or for one it leaves out the
	 * oracle's {@code AbstractMethodError} -- and a reify's class name for the printer.
	 * An interface the form's class implements itself ({@link #provides}) stores nothing:
	 * its verbs answer already.
	 * @param ctx the hub
	 * @param what the defining form
	 * @param body the body's interfaces and methods
	 * @param tag the type's tag form
	 * @param reifyClass the class name a reify prints, or null for a record or deftype
	 * @param lambdas what builds a defined method's lambda
	 * @return the stores, in closure order
	 */
	static List<LispVal> rowForms(ClojureLowering ctx, String what, InterfaceBody body, LispVal tag,
			@Nullable String reifyClass, LambdaBuilder lambdas) {
		Map<ClojureArms.Family, List<LispVal>> names = new LinkedHashMap<>();
		Map<ClojureArms.Family, List<LispVal>> entries = new LinkedHashMap<>();
		Set<String> stored = new HashSet<>();
		for (HostInterface one : body.closure()) {
			if (provides(what, one)) {
				continue;
			}
			names.computeIfAbsent(one.family(), ignored -> new ArrayList<>()).add(LispString.literal(one.name()));
			List<LispVal> family = entries.computeIfAbsent(one.family(), ignored -> new ArrayList<>());
			for (String method : one.methods().keySet()) {
				if (stored.add(method)) {
					addEntries(ctx, body, method, one, family, lambdas);
				}
			}
		}
		for (String method : OBJECT_METHODS.methods().keySet()) {
			if (body.methods().containsKey(method) && stored.add(method)) {
				addEntries(ctx, body, method, OBJECT_METHODS,
						entries.computeIfAbsent(OBJECT_METHODS.family(), ignored -> new ArrayList<>()), lambdas);
			}
		}
		List<LispVal> object = entries.get(OBJECT_METHODS.family());
		if (object != null && reifyClass != null) {
			object.add(LispString.literal("class"));
			object.add(LispString.literal(reifyClass));
		}
		List<LispVal> forms = new ArrayList<>();
		for (Map.Entry<ClojureArms.Family, List<LispVal>> family : entries.entrySet()) {
			List<LispVal> familyNames = names.getOrDefault(family.getKey(), List.of());
			String store = STORES.get(family.getKey());
			if (store == null) {
				throw new IllegalStateException("no store for the family " + family.getKey());
			}
			forms.add(ClojureLowerUtil.list(new LispSymbol(store), tag,
					familyNames.isEmpty() ? ClojureLowering.NIL_CONST
							: ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(familyNames)),
					ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), family.getValue())));
		}
		return forms;
	}

	/**
	 * One method's entries: its name over its lambda, the body's or else the refusal.
	 */
	private static void addEntries(ClojureLowering ctx, InterfaceBody body, String method, HostInterface owner,
			List<LispVal> out, LambdaBuilder lambdas) {
		List<ClojureLowering.MethodArity> arities = body.methods().get(method);
		out.add(LispString.literal(method));
		if (arities == null) {
			out.add(abstractLambda(ctx, method, owner));
			return;
		}
		boolean dispatch = arities.size() > 1 || declaredCounts(body.closure(), method).size() > 1;
		out.add(lambdas.build(new ClojureLowering.TypeMethod(method, arities), dispatch,
				abstractRefusal(method, owner)));
	}

	/**
	 * The lambda a method the body leaves out stores: any call is the oracle's
	 * {@code AbstractMethodError}.
	 */
	private static LispVal abstractLambda(ClojureLowering ctx, String method, HostInterface owner) {
		LispSymbol args = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(ClojureLowering.AMPERSAND_REST, args), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("declare"), ClojureLowerUtil.list(ClojureLowerUtil.sym("ignore"), args)),
				abstractRefusal(method, owner));
	}

	/** The oracle's {@code AbstractMethodError} of a method the class leaves out. */
	private static LispVal abstractRefusal(String method, HostInterface owner) {
		return ClojureRefusals.refusal(ClojureRefusals.ABSTRACT_METHOD,
				LispString.literal("does not define or inherit an implementation of the resolved method " + method
						+ " of interface " + owner.name()));
	}

}
