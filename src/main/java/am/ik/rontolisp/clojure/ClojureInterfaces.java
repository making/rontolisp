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
 * consult -- the ones a single verb reads ({@code Seqable}, {@code Counted},
 * {@code ILookup} ...) and the collection interfaces ({@code IPersistentCollection},
 * {@code IPersistentMap}, {@code ISeq} ...) --, the {@code java.util} collection
 * interfaces and {@code Iterable}/{@code Iterator}, which the oracle's verbs read where a
 * value has no {@code clojure.lang} counterpart, and {@code java.lang.Object}'s
 * overridable methods, which any body defines without naming {@code Object}. Each row is
 * read off clj 1.12.6 on JDK 25 by reflection (2026-10-08): the methods an interface
 * declares, abstract and default, with their parameter counts counting the target, and
 * its direct super-interfaces, which a body naming it implements too.
 * {@code equals}/{@code hashCode}, which the {@code java.util} interfaces redeclare, are
 * {@code Object}'s.
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

	/**
	 * {@code clojure.core/iteration}'s worker and its value: a reify implementing
	 * {@code Seqable} and {@code IReduceInit}, whose rows it stores itself, so naming
	 * either makes a value of both families ({@code clojure.lisp}).
	 */
	static final String ITERATION = "RONTOLISP::%CLOJURE-ITERATION";

	/** {@link #ITERATION} as a value. */
	static final String ITERATION_V = "RONTOLISP::%CLOJURE-ITERATION-V";

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

	/** The store of an {@code IPersistentCollection} row. */
	static final String COLLECTION_ROW = "RONTOLISP::%CLOJURE-COLLECTION-ROW";

	/** The store of an {@code Associative} row. */
	static final String ASSOCIATIVE_ROW = "RONTOLISP::%CLOJURE-ASSOCIATIVE-ROW";

	/** The store of an {@code IPersistentMap} or {@code MapEquivalence} row. */
	static final String PERSISTENT_MAP_ROW = "RONTOLISP::%CLOJURE-PERSISTENT-MAP-ROW";

	/** The store of an {@code IPersistentSet} row. */
	static final String PERSISTENT_SET_ROW = "RONTOLISP::%CLOJURE-PERSISTENT-SET-ROW";

	/** The store of an {@code IPersistentStack} row. */
	static final String STACK_ROW = "RONTOLISP::%CLOJURE-STACK-ROW";

	/** The store of an {@code IPersistentVector} row. */
	static final String PERSISTENT_VECTOR_ROW = "RONTOLISP::%CLOJURE-PERSISTENT-VECTOR-ROW";

	/** The store of an {@code ISeq} row. */
	static final String ISEQ_ROW = "RONTOLISP::%CLOJURE-ISEQ-ROW";

	/** The store of a {@code Sequential} or {@code IPersistentList} row. */
	static final String SEQUENTIAL_ROW = "RONTOLISP::%CLOJURE-SEQUENTIAL-ROW";

	/** The store of a {@code Reversible} row. */
	static final String REVERSIBLE_ROW = "RONTOLISP::%CLOJURE-REVERSIBLE-ROW";

	/** The store of an {@code IPending} row. */
	static final String PENDING_ROW = "RONTOLISP::%CLOJURE-PENDING-ROW";

	/** The store of a {@code Sorted} row. */
	static final String SORTED_ROW = "RONTOLISP::%CLOJURE-SORTED-ROW";

	/** The store of a {@code Comparable} row. */
	static final String COMPARABLE_ROW = "RONTOLISP::%CLOJURE-COMPARABLE-ROW";

	/** The store of a {@code java.lang.Iterable} row. */
	static final String ITERABLE_ROW = "RONTOLISP::%CLOJURE-ITERABLE-ROW";

	/** The store of a {@code java.util.Iterator} row. */
	static final String ITERATOR_ROW = "RONTOLISP::%CLOJURE-ITERATOR-ROW";

	/**
	 * The store of a {@code java.util.Collection}, {@code SequencedCollection},
	 * {@code List}, {@code Set} or {@code RandomAccess} row.
	 */
	static final String JAVA_COLLECTION_ROW = "RONTOLISP::%CLOJURE-JAVA-COLLECTION-ROW";

	/** The store of a {@code java.util.Map} row. */
	static final String JAVA_MAP_ROW = "RONTOLISP::%CLOJURE-JAVA-MAP-ROW";

	/** The store of an {@code IHashEq} row, whose {@code hasheq} {@code hash} reads. */
	static final String HASHEQ_ROW = "RONTOLISP::%CLOJURE-HASHEQ-ROW";

	/**
	 * The store of a row of an interface no core verb here reads: {@code Serializable},
	 * {@code IEditableCollection} and the transients (refused by name), whose methods
	 * only an instance call reaches.
	 */
	static final String MARKER_ROW = "RONTOLISP::%CLOJURE-MARKER-ROW";

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

	/**
	 * The library's reader of a method only defaults declare: the row's entry, or a
	 * lambda refusing the call by name when the body left it out, since the interface's
	 * default body is Java.
	 */
	static final String DEFAULT_METHOD = "RONTOLISP::%CLOJURE-DEFAULT-METHOD";

	/**
	 * The library's iterator over the seq of a collection: what {@code .iterator} of a
	 * Clojure collection, {@code clojure.lang.SeqIterator} and
	 * {@code clojure.lang.RT/iter} answer, which an {@code Iterable} body's
	 * {@code iterator} usually hands on.
	 */
	static final String SEQ_ITERATOR = "RONTOLISP::%CLOJURE-SEQ-ITERATOR";

	/** Each family's store. */
	private static final Map<ClojureArms.Family, String> STORES = Map.ofEntries(
			Map.entry(ClojureArms.Family.REDUCE_INTERFACE, REDUCE_ROW),
			Map.entry(ClojureArms.Family.SEQABLE, SEQABLE_ROW), Map.entry(ClojureArms.Family.COUNTED, COUNTED_ROW),
			Map.entry(ClojureArms.Family.INDEXED, INDEXED_ROW), Map.entry(ClojureArms.Family.LOOKUP, LOOKUP_ROW),
			Map.entry(ClojureArms.Family.INVOKABLE, INVOKABLE_ROW),
			Map.entry(ClojureArms.Family.DEREFABLE, DEREFABLE_ROW),
			Map.entry(ClojureArms.Family.META_INTERFACE, META_ROW),
			Map.entry(ClojureArms.Family.OBJECT_METHODS, OBJECT_ROW),
			Map.entry(ClojureArms.Family.COLLECTION, COLLECTION_ROW),
			Map.entry(ClojureArms.Family.ASSOCIATIVE, ASSOCIATIVE_ROW),
			Map.entry(ClojureArms.Family.PERSISTENT_MAP, PERSISTENT_MAP_ROW),
			Map.entry(ClojureArms.Family.PERSISTENT_SET, PERSISTENT_SET_ROW),
			Map.entry(ClojureArms.Family.STACK, STACK_ROW),
			Map.entry(ClojureArms.Family.PERSISTENT_VECTOR, PERSISTENT_VECTOR_ROW),
			Map.entry(ClojureArms.Family.ISEQ, ISEQ_ROW), Map.entry(ClojureArms.Family.SEQUENTIAL, SEQUENTIAL_ROW),
			Map.entry(ClojureArms.Family.REVERSIBLE, REVERSIBLE_ROW),
			Map.entry(ClojureArms.Family.PENDING, PENDING_ROW),
			Map.entry(ClojureArms.Family.SORTED_INTERFACE, SORTED_ROW),
			Map.entry(ClojureArms.Family.COMPARABLE, COMPARABLE_ROW),
			Map.entry(ClojureArms.Family.ITERABLE, ITERABLE_ROW), Map.entry(ClojureArms.Family.ITERATOR, ITERATOR_ROW),
			Map.entry(ClojureArms.Family.JAVA_COLLECTION, JAVA_COLLECTION_ROW),
			Map.entry(ClojureArms.Family.JAVA_MAP, JAVA_MAP_ROW), Map.entry(ClojureArms.Family.HASHEQ, HASHEQ_ROW),
			Map.entry(ClojureArms.Family.MARKER, MARKER_ROW));

	/**
	 * One interface: its binary name, the abstract methods it declares (name to the
	 * parameter counts, the target included), the default ones, its direct
	 * super-interfaces, the family of the run-time library its methods are stored in, and
	 * the library's test of a value whose type implements it ({@code instance?} reads
	 * it).
	 */
	record HostInterface(String name, Map<String, Set<Integer>> methods, Map<String, Set<Integer>> defaults,
			List<String> supers, ClojureArms.Family family, String test) {

		/**
		 * Whether the interface declares the method at the count, abstract or default.
		 * @param method the method name
		 * @param count the parameter count, the target included
		 * @return {@code true} when it declares it
		 */
		boolean declares(String method, int count) {
			Set<Integer> counts = this.methods.get(method);
			if (counts != null && counts.contains(count)) {
				return true;
			}
			Set<Integer> fallbacks = this.defaults.get(method);
			return fallbacks != null && fallbacks.contains(count);
		}

		/**
		 * Every method name the interface declares, the abstract ones first.
		 * @return the names
		 */
		Set<String> methodNames() {
			Set<String> names = new LinkedHashSet<>(this.methods.keySet());
			names.addAll(this.defaults.keySet());
			return names;
		}

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
			methods("toString", 1, "equals", 2, "hashCode", 1), Map.of(), List.of(), ClojureArms.Family.OBJECT_METHODS,
			"");

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
	 * The supported interfaces a record implements itself (its {@code supers}): a body
	 * naming one stores no row for it.
	 */
	private static final Set<String> RECORD_PROVIDED = Set.of("clojure.lang.Counted", "clojure.lang.Seqable",
			"clojure.lang.ILookup", "clojure.lang.IMeta", "clojure.lang.IObj", "clojure.lang.IPersistentCollection",
			"clojure.lang.Associative", "clojure.lang.IPersistentMap", "clojure.lang.IHashEq", "java.lang.Iterable",
			"java.util.Map", "java.io.Serializable");

	/**
	 * The supported interfaces among a record class's own direct ones, which a
	 * {@code defrecord} body may not name: the oracle's {@code Duplicate interface name}.
	 */
	private static final Set<String> RECORD_DIRECT = Set.of("clojure.lang.ILookup", "clojure.lang.IObj",
			"clojure.lang.IPersistentMap", "clojure.lang.IHashEq", "java.util.Map", "java.io.Serializable");

	/**
	 * The methods a record class defines itself, as {@code name/count} with the target
	 * counted, which a {@code defrecord} body may not define again: the oracle's
	 * {@code Duplicate method name}.
	 */
	private static final Set<String> RECORD_GENERATED = Set.of("assoc/3", "clear/1", "cons/2", "containsKey/2",
			"containsValue/2", "count/1", "empty/1", "entryAt/2", "entrySet/1", "equals/2", "equiv/2", "get/2",
			"getLookupThunk/2", "hashCode/1", "hasheq/1", "isEmpty/1", "iterator/1", "keySet/1", "meta/1", "put/3",
			"putAll/2", "remove/2", "seq/1", "size/1", "valAt/2", "valAt/3", "values/1", "withMeta/2", "without/2");

	/**
	 * The supported interfaces every reify class implements itself (metadata): a body
	 * naming one stores no row for it.
	 */
	private static final Set<String> REIFY_PROVIDED = Set.of("clojure.lang.IMeta", "clojure.lang.IObj");

	/**
	 * The methods every reify class defines itself, which a {@code reify} body may not
	 * define again.
	 */
	private static final Set<String> REIFY_GENERATED = Set.of("meta/1", "withMeta/2");

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
		collectionTable(table);
		javaTable(table);
		return table;
	}

	/**
	 * The {@code clojure.lang} collection interfaces, {@code IHashEq} and the ones no
	 * verb reads.
	 */
	private static void collectionTable(Map<String, HostInterface> table) {
		add(table, "clojure.lang.IPersistentCollection", methods("count", 1, "cons", 2, "empty", 1, "equiv", 2),
				List.of("clojure.lang.Seqable"), ClojureArms.Family.COLLECTION, "RONTOLISP::%CLOJURE-ICOLLECTION-P");
		add(table, "clojure.lang.Associative", methods("containsKey", 2, "entryAt", 2, "assoc", 3),
				List.of("clojure.lang.IPersistentCollection", "clojure.lang.ILookup"), ClojureArms.Family.ASSOCIATIVE,
				"RONTOLISP::%CLOJURE-IASSOCIATIVE-P");
		add(table, "clojure.lang.IPersistentMap", methods("assoc", 3, "assocEx", 3, "without", 2),
				List.of("java.lang.Iterable", "clojure.lang.Associative", "clojure.lang.Counted"),
				ClojureArms.Family.PERSISTENT_MAP, "RONTOLISP::%CLOJURE-IMAP-P");
		add(table, "clojure.lang.MapEquivalence", Map.of(), List.of(), ClojureArms.Family.PERSISTENT_MAP,
				"RONTOLISP::%CLOJURE-MAP-EQUIVALENCE-P");
		add(table, "clojure.lang.IPersistentSet", methods("disjoin", 2, "contains", 2, "get", 2),
				List.of("clojure.lang.IPersistentCollection", "clojure.lang.Counted"),
				ClojureArms.Family.PERSISTENT_SET, "RONTOLISP::%CLOJURE-ISET-P");
		add(table, "clojure.lang.IPersistentStack", methods("peek", 1, "pop", 1),
				List.of("clojure.lang.IPersistentCollection"), ClojureArms.Family.STACK,
				"RONTOLISP::%CLOJURE-ISTACK-P");
		add(table, "clojure.lang.IPersistentVector", methods("length", 1, "assocN", 3, "cons", 2),
				List.of("clojure.lang.Associative", "clojure.lang.Sequential", "clojure.lang.IPersistentStack",
						"clojure.lang.Reversible", "clojure.lang.Indexed"),
				ClojureArms.Family.PERSISTENT_VECTOR, "RONTOLISP::%CLOJURE-IVECTOR-P");
		add(table, "clojure.lang.IPersistentList", Map.of(),
				List.of("clojure.lang.Sequential", "clojure.lang.IPersistentStack"), ClojureArms.Family.SEQUENTIAL,
				"RONTOLISP::%CLOJURE-ILIST-P");
		add(table, "clojure.lang.ISeq", methods("first", 1, "next", 1, "more", 1, "cons", 2),
				List.of("clojure.lang.IPersistentCollection"), ClojureArms.Family.ISEQ, "RONTOLISP::%CLOJURE-ISEQ-P");
		add(table, "clojure.lang.Sequential", Map.of(), List.of(), ClojureArms.Family.SEQUENTIAL,
				"RONTOLISP::%CLOJURE-ISEQUENTIAL-P");
		add(table, "clojure.lang.Reversible", methods("rseq", 1), List.of(), ClojureArms.Family.REVERSIBLE,
				"RONTOLISP::%CLOJURE-IREVERSIBLE-P");
		add(table, "clojure.lang.IPending", methods("isRealized", 1), List.of(), ClojureArms.Family.PENDING,
				"RONTOLISP::%CLOJURE-IPENDING-P");
		add(table, "clojure.lang.Sorted", methods("comparator", 1, "entryKey", 2, "seq", 2, "seqFrom", 3), List.of(),
				ClojureArms.Family.SORTED_INTERFACE, "RONTOLISP::%CLOJURE-ISORTED-P");
		add(table, "clojure.lang.IHashEq", methods("hasheq", 1), List.of(), ClojureArms.Family.HASHEQ,
				"RONTOLISP::%CLOJURE-HASHEQ-P");
		add(table, "clojure.lang.IEditableCollection", methods("asTransient", 1), List.of(), ClojureArms.Family.MARKER,
				"RONTOLISP::%CLOJURE-EDITABLE-P");
		add(table, "clojure.lang.ITransientCollection", methods("conj", 2, "persistent", 1), List.of(),
				ClojureArms.Family.MARKER, "RONTOLISP::%CLOJURE-ITRANSIENT-COLLECTION-P");
		add(table, "clojure.lang.ITransientAssociative", methods("assoc", 3),
				List.of("clojure.lang.ITransientCollection", "clojure.lang.ILookup"), ClojureArms.Family.MARKER,
				"RONTOLISP::%CLOJURE-ITRANSIENT-ASSOCIATIVE-P");
		add(table, "clojure.lang.ITransientAssociative2", methods("containsKey", 2, "entryAt", 2),
				List.of("clojure.lang.ITransientAssociative"), ClojureArms.Family.MARKER,
				"RONTOLISP::%CLOJURE-ITRANSIENT-ASSOCIATIVE2-P");
		add(table, "clojure.lang.ITransientMap", methods("assoc", 3, "without", 2, "persistent", 1),
				List.of("clojure.lang.ITransientAssociative", "clojure.lang.Counted"), ClojureArms.Family.MARKER,
				"RONTOLISP::%CLOJURE-ITRANSIENT-MAP-P");
		add(table, "clojure.lang.ITransientVector", methods("assocN", 3, "pop", 1),
				List.of("clojure.lang.ITransientAssociative", "clojure.lang.Indexed"), ClojureArms.Family.MARKER,
				"RONTOLISP::%CLOJURE-ITRANSIENT-VECTOR-P");
		add(table, "clojure.lang.ITransientSet", methods("disjoin", 2, "contains", 2, "get", 2),
				List.of("clojure.lang.ITransientCollection", "clojure.lang.Counted"), ClojureArms.Family.MARKER,
				"RONTOLISP::%CLOJURE-ITRANSIENT-SET-P");
	}

	/**
	 * The {@code java.lang}/{@code java.util} interfaces the oracle's verbs read where a
	 * value has no {@code clojure.lang} counterpart. Their {@code equals} and
	 * {@code hashCode} are {@code Object}'s.
	 */
	private static void javaTable(Map<String, HostInterface> table) {
		add(table, "java.lang.Iterable", methods("iterator", 1), methods("forEach", 2, "spliterator", 1), List.of(),
				ClojureArms.Family.ITERABLE, "RONTOLISP::%CLOJURE-ITERABLE-P");
		add(table, "java.util.Iterator", methods("hasNext", 1, "next", 1), methods("remove", 1, "forEachRemaining", 2),
				List.of(), ClojureArms.Family.ITERATOR, "RONTOLISP::%CLOJURE-ITERATOR-P");
		Object[] collection = { "add", 2, "addAll", 2, "clear", 1, "contains", 2, "containsAll", 2, "isEmpty", 1,
				"iterator", 1, "remove", 2, "removeAll", 2, "retainAll", 2, "size", 1, "toArray", 1, "toArray", 2 };
		Object[] streams = { "parallelStream", 1, "removeIf", 2, "spliterator", 1, "stream", 1 };
		add(table, "java.util.Collection", methods(collection), methods(streams), List.of("java.lang.Iterable"),
				ClojureArms.Family.JAVA_COLLECTION, "RONTOLISP::%CLOJURE-JCOLLECTION-P");
		Object[] sequenced = { "addFirst", 2, "addLast", 2, "getFirst", 1, "getLast", 1, "removeFirst", 1, "removeLast",
				1 };
		add(table, "java.util.SequencedCollection", join(collection, "reversed", 1), methods(pairs(streams, sequenced)),
				List.of("java.util.Collection"), ClojureArms.Family.JAVA_COLLECTION, "RONTOLISP::%CLOJURE-SEQUENCED-P");
		add(table, "java.util.List",
				join(collection, "add", 3, "addAll", 3, "get", 2, "indexOf", 2, "lastIndexOf", 2, "listIterator", 1,
						"listIterator", 2, "set", 3, "subList", 3),
				join(pairs(streams, sequenced), "replaceAll", 2, "reversed", 1, "sort", 2),
				List.of("java.util.SequencedCollection"), ClojureArms.Family.JAVA_COLLECTION,
				"RONTOLISP::%CLOJURE-JLIST-P");
		add(table, "java.util.Set", methods(collection), methods(streams), List.of("java.util.Collection"),
				ClojureArms.Family.JAVA_COLLECTION, "RONTOLISP::%CLOJURE-JSET-P");
		add(table, "java.util.RandomAccess", Map.of(), List.of(), ClojureArms.Family.JAVA_COLLECTION,
				"RONTOLISP::%CLOJURE-RANDOM-ACCESS-P");
		add(table, "java.util.Map",
				methods("clear", 1, "containsKey", 2, "containsValue", 2, "entrySet", 1, "get", 2, "isEmpty", 1,
						"keySet", 1, "put", 3, "putAll", 2, "remove", 2, "size", 1, "values", 1),
				methods("compute", 3, "computeIfAbsent", 3, "computeIfPresent", 3, "forEach", 2, "getOrDefault", 3,
						"merge", 4, "putIfAbsent", 3, "remove", 3, "replace", 3, "replace", 4, "replaceAll", 2),
				List.of(), ClojureArms.Family.JAVA_MAP, "RONTOLISP::%CLOJURE-JMAP-P");
		add(table, "java.lang.Comparable", methods("compareTo", 2), List.of(), ClojureArms.Family.COMPARABLE,
				"RONTOLISP::%CLOJURE-ICOMPARABLE-P");
		add(table, "java.io.Serializable", Map.of(), List.of(), ClojureArms.Family.MARKER,
				"RONTOLISP::%CLOJURE-SERIALIZABLE-P");
	}

	private static void add(Map<String, HostInterface> table, String name, Map<String, Set<Integer>> methods,
			List<String> supers, ClojureArms.Family family, String test) {
		add(table, name, methods, Map.of(), supers, family, test);
	}

	private static void add(Map<String, HostInterface> table, String name, Map<String, Set<Integer>> methods,
			Map<String, Set<Integer>> defaults, List<String> supers, ClojureArms.Family family, String test) {
		table.put(name, new HostInterface(name, methods, defaults, supers, family, test));
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

	/** The methods of a pair list and of further pairs. */
	private static Map<String, Set<Integer>> join(Object[] pairs, Object... more) {
		Object[] all = new Object[pairs.length + more.length];
		System.arraycopy(pairs, 0, all, 0, pairs.length);
		System.arraycopy(more, 0, all, pairs.length, more.length);
		return methods(all);
	}

	/** The pairs of two lists as one pair list. */
	private static Object[] pairs(Object[] pairs, Object[] more) {
		Object[] all = new Object[pairs.length + more.length];
		System.arraycopy(pairs, 0, all, 0, pairs.length);
		System.arraycopy(more, 0, all, pairs.length, more.length);
		return all;
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
	 * parameter count, abstract or default, the first in order; null when none does.
	 * @param closure the body's interfaces
	 * @param method the method name
	 * @param count the parameter count, the target included
	 * @return the declaring interface, or null
	 */
	static @Nullable HostInterface declaring(List<HostInterface> closure, String method, int count) {
		for (HostInterface one : closure) {
			if (one.declares(method, count)) {
				return one;
			}
		}
		return OBJECT_METHODS.declares(method, count) ? OBJECT_METHODS : null;
	}

	/**
	 * Every parameter count the closure (and {@code Object}) declares for a method name,
	 * abstract or default.
	 * @param closure the body's interfaces
	 * @param method the method name
	 * @return the counts, ascending
	 */
	static List<Integer> declaredCounts(List<HostInterface> closure, String method) {
		Set<Integer> counts = new TreeSet<>();
		for (HostInterface one : closure) {
			counts.addAll(one.methods().getOrDefault(method, Set.of()));
			counts.addAll(one.defaults().getOrDefault(method, Set.of()));
		}
		counts.addAll(OBJECT_METHODS.methods().getOrDefault(method, Set.of()));
		return new ArrayList<>(counts);
	}

	/**
	 * Whether every count the closure declares a method at is a default one: a body
	 * leaving such a method out keeps the interface's default, which no verb here reads,
	 * so its row holds no entry for it rather than an abstract method's refusal.
	 */
	private static boolean onlyDefault(List<HostInterface> closure, String method) {
		for (HostInterface one : closure) {
			if (one.methods().containsKey(method)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The tests an instance call {@code (.method x args...)} asks of a record, deftype or
	 * reify whose type implements the method at that count: the test of every supported
	 * interface declaring it, or for an {@code Object} override the test of that method;
	 * empty when no supported interface declares it. {@code toString} answers through
	 * {@code str} and {@code hashCode} through the hash runtime's {@code hashCode} of any
	 * value. A type implementing any of them answers its row's method, so the caller asks
	 * them as one disjunction, each a test of its own family.
	 * @param method the method name
	 * @param count the parameter count, the target included
	 * @return the tests, in table order
	 */
	static List<String> instanceTests(String method, int count) {
		Set<String> tests = new LinkedHashSet<>();
		for (HostInterface one : INTERFACES.values()) {
			if (one.declares(method, count)) {
				tests.add(one.test());
			}
		}
		if (!tests.isEmpty()) {
			return List.copyOf(tests);
		}
		return method.equals("equals") && count == 2 ? List.of("RONTOLISP::%CLOJURE-EQUALS-P") : List.of();
	}

	/**
	 * Whether an instance call of the method at that count reaches a default method of
	 * every interface declaring it: a body may leave it out, so the call asks the row for
	 * the entry instead of calling it unchecked.
	 * @param method the method name
	 * @param count the parameter count, the target included
	 * @return {@code true} when no supported interface declares it abstract
	 */
	static boolean defaultOnly(String method, int count) {
		boolean declared = false;
		for (HostInterface one : INTERFACES.values()) {
			Set<Integer> counts = one.methods().get(method);
			if (counts != null && counts.contains(count)) {
				return false;
			}
			declared |= one.declares(method, count);
		}
		return declared;
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
	 * Refuses a body's method the class a form defines implements itself, in the oracle's
	 * words, and one of an interface the class implements itself that it leaves to the
	 * interface (a record's {@code assocEx}, a {@code java.util.Map} default), which the
	 * oracle's class overrides and no row here holds.
	 * @param what the defining form
	 * @param declaring the interface declaring the method
	 * @param method the method name
	 * @param count the parameter count, the target included
	 */
	static void checkMethod(String what, HostInterface declaring, String method, int count) {
		checkGenerated(what, method, count);
		if (provides(what, declaring)) {
			throw new LispReadException(
					declaring.name() + "/" + method + " is not supported yet as a method of " + what);
		}
	}

	/**
	 * The refusal of a body's method no interface it names and no protocol declares: the
	 * oracle's {@code Duplicate method name} for one the class a form defines implements
	 * itself, a refusal by name for one of the class's own interfaces that the class
	 * leaves to the interface ({@link #checkMethod}), and otherwise the oracle's
	 * {@code Can't define method not in interfaces}.
	 * @param what the defining form
	 * @param method the method name
	 * @param count the parameter count, the target included
	 * @return the refusal to throw
	 */
	static LispReadException unmatched(String what, String method, int count) {
		checkGenerated(what, method, count);
		for (HostInterface one : INTERFACES.values()) {
			if (one.declares(method, count)) {
				checkMethod(what, one, method, count);
			}
		}
		return new LispReadException("Can't define method not in interfaces: " + method);
	}

	private static void checkGenerated(String what, String method, int count) {
		Set<String> generated = switch (what) {
			case "defrecord" -> RECORD_GENERATED;
			case "reify" -> REIFY_GENERATED;
			default -> Set.of();
		};
		if (generated.contains(method + "/" + count)) {
			throw new LispReadException("Duplicate method name \"" + method + "\" in " + what);
		}
	}

	/**
	 * The stores of a body's interface rows under the tag, one per family of its closure
	 * and one for its {@code Object} overrides: each names the family's interfaces it
	 * implements and holds a lambda per method name -- the body's, dispatching on the
	 * call's count when the interfaces declare several, or for one it leaves out the
	 * oracle's {@code AbstractMethodError} (a method only a default declares is left out
	 * of the row) -- and a reify's class name for the printer. An interface the form's
	 * class implements itself ({@link #provides}) stores nothing: its verbs answer
	 * already.
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
			for (String method : one.methodNames()) {
				if (!OBJECT_METHODS.methods().containsKey(method) && stored.add(method)) {
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
	 * One method's entries: its name over its lambda, the body's or else the refusal; a
	 * method the body leaves out that only defaults declare has none.
	 */
	private static void addEntries(ClojureLowering ctx, InterfaceBody body, String method, HostInterface owner,
			List<LispVal> out, LambdaBuilder lambdas) {
		List<ClojureLowering.MethodArity> arities = body.methods().get(method);
		if (arities == null) {
			if (owner != OBJECT_METHODS && onlyDefault(body.closure(), method)) {
				return;
			}
			out.add(LispString.literal(method));
			out.add(abstractLambda(ctx, method, owner));
			return;
		}
		out.add(LispString.literal(method));
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
