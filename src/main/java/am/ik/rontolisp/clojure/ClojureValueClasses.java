package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The host classes the oracle's value of each kind is an instance of, so
 * {@code instance?} of an interface or a class no kind is named after answers the
 * oracle's {@code isInstance} for a Clojure value: {@code java.util.List} of a vector, a
 * list or a lazy seq, {@code clojure.lang.IFn} of a keyword. Each row is the value's
 * class and its {@code supers}, read off clj 1.12.6 on JDK 25 (2026-10-04) as tables, not
 * reflection: {@code clojure.lang} is not on this class path. {@code java.lang.Object} is
 * in every row and left out; {@code instance?} of it is every value but nil.
 *
 * <p>
 * A kind stands for the one host class its values have here. Where the oracle's values of
 * a kind have several, the row is the one the representation matches: a list is a
 * {@code PersistentList} (a strict seq shares it, where the oracle's is a
 * {@code LazySeq}), an integer a {@code Long}, a map both array and hash maps' classes,
 * and a two-member vector both a vector and a map entry (the {@code map-entry?}
 * deviation).
 */
final class ClojureValueClasses {

	private static final String SERIALIZABLE = "java.io.Serializable";

	private static final String COMPARABLE = "java.lang.Comparable";

	private static final String CONSTABLE = "java.lang.constant.Constable";

	private static final String CONSTANT_DESC = "java.lang.constant.ConstantDesc";

	private static final String NUMBER = "java.lang.Number";

	private static final String RUNNABLE = "java.lang.Runnable";

	private static final String CALLABLE = "java.util.concurrent.Callable";

	private static final String ITERABLE = "java.lang.Iterable";

	private static final List<String> SUPPLIERS = List.of("java.util.function.BooleanSupplier",
			"java.util.function.DoubleSupplier", "java.util.function.IntSupplier", "java.util.function.LongSupplier",
			"java.util.function.Supplier");

	/** A {@code PersistentVector}'s supers, a {@code MapEntry}'s but its own. */
	private static final List<String> VECTOR_SUPERS = List.of("clojure.lang.AFn", "clojure.lang.APersistentVector",
			"clojure.lang.Associative", "clojure.lang.Counted", "clojure.lang.IFn", "clojure.lang.IHashEq",
			"clojure.lang.ILookup", "clojure.lang.IPersistentCollection", "clojure.lang.IPersistentStack",
			"clojure.lang.IPersistentVector", "clojure.lang.Indexed", "clojure.lang.Reversible", "clojure.lang.Seqable",
			"clojure.lang.Sequential", SERIALIZABLE, COMPARABLE, ITERABLE, RUNNABLE, "java.util.Collection",
			"java.util.List", "java.util.RandomAccess", "java.util.SequencedCollection", CALLABLE);

	/** The supers a {@code LazySeq} shares with a {@code PersistentList}. */
	private static final List<String> SEQ_SUPERS = List.of("clojure.lang.IHashEq", "clojure.lang.IMeta",
			"clojure.lang.IObj", "clojure.lang.IPersistentCollection", "clojure.lang.ISeq", "clojure.lang.Obj",
			"clojure.lang.Seqable", "clojure.lang.Sequential", SERIALIZABLE, ITERABLE, "java.util.Collection",
			"java.util.List", "java.util.SequencedCollection");

	/** An {@code APersistentMap}'s supers. */
	private static final List<String> MAP_SUPERS = List.of("clojure.lang.AFn", "clojure.lang.APersistentMap",
			"clojure.lang.Associative", "clojure.lang.Counted", "clojure.lang.IFn", "clojure.lang.IHashEq",
			"clojure.lang.IKVReduce", "clojure.lang.ILookup", "clojure.lang.IMeta", "clojure.lang.IObj",
			"clojure.lang.IPersistentCollection", "clojure.lang.IPersistentMap", "clojure.lang.MapEquivalence",
			"clojure.lang.Seqable", SERIALIZABLE, ITERABLE, RUNNABLE, "java.util.Map", CALLABLE);

	/** An {@code APersistentSet}'s supers. */
	private static final List<String> SET_SUPERS = List.of("clojure.lang.AFn", "clojure.lang.APersistentSet",
			"clojure.lang.Counted", "clojure.lang.IFn", "clojure.lang.IHashEq", "clojure.lang.IMeta",
			"clojure.lang.IObj", "clojure.lang.IPersistentCollection", "clojure.lang.IPersistentSet",
			"clojure.lang.Seqable", SERIALIZABLE, ITERABLE, RUNNABLE, "java.util.Collection", "java.util.Set",
			CALLABLE);

	/** An {@code ARef}'s supers, an atom's and a var's. */
	private static final List<String> REF_SUPERS = List.of("clojure.lang.ARef", "clojure.lang.AReference",
			"clojure.lang.IDeref", "clojure.lang.IMeta", "clojure.lang.IRef", "clojure.lang.IReference");

	/**
	 * A kind of value with its classes. The order is the order {@code instance?} tests
	 * them in.
	 */
	enum Kind {

		/** {@code java.lang.String}. */
		STRING(List.of("java.lang.String", SERIALIZABLE, "java.lang.CharSequence", COMPARABLE, CONSTABLE,
				CONSTANT_DESC)),

		/** {@code java.lang.Character}. */
		CHAR(List.of("java.lang.Character", SERIALIZABLE, COMPARABLE, CONSTABLE)),

		/** {@code java.lang.Boolean}. */
		BOOLEAN(List.of("java.lang.Boolean", SERIALIZABLE, COMPARABLE, CONSTABLE)),

		/** An integer: {@code java.lang.Long} (a bignum too, the oracle's BigInt). */
		LONG(List.of("java.lang.Long", SERIALIZABLE, COMPARABLE, NUMBER, CONSTABLE, CONSTANT_DESC)),

		/** {@code java.lang.Double}. */
		DOUBLE(List.of("java.lang.Double", SERIALIZABLE, COMPARABLE, NUMBER, CONSTABLE, CONSTANT_DESC)),

		/** {@code clojure.lang.Ratio}. */
		RATIO(List.of("clojure.lang.Ratio", SERIALIZABLE, COMPARABLE, NUMBER)),

		/** {@code clojure.lang.Keyword}. */
		KEYWORD(List.of("clojure.lang.Keyword", "clojure.lang.IFn", "clojure.lang.IHashEq", "clojure.lang.Named",
				SERIALIZABLE, COMPARABLE, RUNNABLE, CALLABLE)),

		/** {@code clojure.lang.Symbol}. */
		SYMBOL(List.of("clojure.lang.Symbol", "clojure.lang.AFn", "clojure.lang.IFn", "clojure.lang.IHashEq",
				"clojure.lang.IMeta", "clojure.lang.IObj", "clojure.lang.Named", SERIALIZABLE, COMPARABLE, RUNNABLE,
				CALLABLE)),

		/** {@code clojure.lang.PersistentVector}. */
		VECTOR(concat(List.of("clojure.lang.PersistentVector", "clojure.lang.IDrop", "clojure.lang.IEditableCollection",
				"clojure.lang.IKVReduce", "clojure.lang.IMeta", "clojure.lang.IObj", "clojure.lang.IReduce",
				"clojure.lang.IReduceInit"), VECTOR_SUPERS)),

		/** {@code clojure.lang.MapEntry}: any two-member vector. */
		MAP_ENTRY(concat(List.of("clojure.lang.MapEntry", "clojure.lang.AMapEntry", "clojure.lang.IMapEntry",
				"java.util.Map$Entry"), VECTOR_SUPERS)),

		/** {@code clojure.lang.PersistentList}: a list, a strict seq. */
		LIST(concat(List.of("clojure.lang.PersistentList", "clojure.lang.ASeq", "clojure.lang.Counted",
				"clojure.lang.IPersistentList", "clojure.lang.IPersistentStack", "clojure.lang.IReduce",
				"clojure.lang.IReduceInit"), SEQ_SUPERS)),

		/** {@code clojure.lang.LazySeq}. */
		LAZY_SEQ(concat(List.of("clojure.lang.LazySeq", "clojure.lang.IPending"), SEQ_SUPERS)),

		/** {@code clojure.lang.PersistentArrayMap} or {@code PersistentHashMap}. */
		MAP(concat(List.of("clojure.lang.PersistentArrayMap", "clojure.lang.PersistentHashMap", "clojure.lang.IDrop",
				"clojure.lang.IEditableCollection", "clojure.lang.IMapIterable"), MAP_SUPERS)),

		/** {@code clojure.lang.PersistentTreeMap}. */
		SORTED_MAP(concat(List.of("clojure.lang.PersistentTreeMap", "clojure.lang.Reversible", "clojure.lang.Sorted"),
				MAP_SUPERS)),

		/** {@code clojure.lang.PersistentHashSet}. */
		SET(concat(List.of("clojure.lang.PersistentHashSet", "clojure.lang.IEditableCollection"), SET_SUPERS)),

		/** {@code clojure.lang.PersistentTreeSet}. */
		SORTED_SET(concat(List.of("clojure.lang.PersistentTreeSet", "clojure.lang.Reversible", "clojure.lang.Sorted"),
				SET_SUPERS)),

		/** A function: a {@code clojure.lang.AFunction}. */
		FUNCTION(List.of("clojure.lang.AFn", "clojure.lang.AFunction", "clojure.lang.Fn", "clojure.lang.IFn",
				"clojure.lang.IMeta", "clojure.lang.IObj", SERIALIZABLE, RUNNABLE, "java.util.Comparator", CALLABLE)),

		/** {@code clojure.lang.Atom}. */
		ATOM(concat(List.of("clojure.lang.Atom", "clojure.lang.IAtom", "clojure.lang.IAtom2"),
				concat(REF_SUPERS, SUPPLIERS))),

		/** {@code clojure.lang.Volatile}. */
		VOLATILE(concat(List.of("clojure.lang.Volatile", "clojure.lang.IDeref"), SUPPLIERS)),

		/** {@code clojure.lang.Var}. */
		VAR(concat(List.of("clojure.lang.Var", "clojure.lang.IFn", "clojure.lang.Settable", SERIALIZABLE, RUNNABLE,
				CALLABLE), concat(REF_SUPERS, SUPPLIERS))),

		/** {@code java.util.regex.Pattern}. */
		PATTERN(List.of("java.util.regex.Pattern", SERIALIZABLE)),

		/** {@code java.util.regex.Matcher}. */
		MATCHER(List.of("java.util.regex.Matcher", "java.util.regex.MatchResult")),

		/** {@code clojure.lang.Namespace}. */
		NAMESPACE(List.of("clojure.lang.Namespace", "clojure.lang.AReference", "clojure.lang.IMeta",
				"clojure.lang.IReference", SERIALIZABLE)),

		/** {@code clojure.lang.ReaderConditional}. */
		READER_CONDITIONAL(List.of("clojure.lang.ReaderConditional", "clojure.lang.ILookup")),

		/** {@code clojure.lang.TaggedLiteral}. */
		TAGGED_LITERAL(List.of("clojure.lang.TaggedLiteral", "clojure.lang.ILookup")),

		/** A record: what every record class implements. */
		RECORD(List.of("clojure.lang.Associative", "clojure.lang.Counted", "clojure.lang.IHashEq",
				"clojure.lang.IKeywordLookup", "clojure.lang.ILookup", "clojure.lang.IMeta", "clojure.lang.IObj",
				"clojure.lang.IPersistentCollection", "clojure.lang.IPersistentMap", "clojure.lang.IRecord",
				"clojure.lang.Seqable", SERIALIZABLE, ITERABLE, "java.util.Map")),

		/** A deftype: what every deftype class implements. */
		DEFTYPE(List.of("clojure.lang.IType")),

		/** A reify: what every reify class implements. */
		REIFY(List.of("clojure.lang.IMeta", "clojure.lang.IObj"));

		/** The classes, the value's own first where it has one. */
		final Set<String> classes;

		Kind(List<String> classes) {
			this.classes = Set.copyOf(classes);
		}

	}

	/**
	 * The host classes a value of a Clojure kind may be or a host object of one is
	 * converted into at the {@code java:} boundary: no host object is an instance of one,
	 * so {@code instance?} needs no host arm for them.
	 */
	private static final Set<String> CONVERTED = Set.of("java.lang.String", "java.lang.Long", "java.lang.Integer",
			"java.lang.Short", "java.lang.Byte", "java.lang.Double", "java.lang.Float", "java.lang.Boolean",
			"java.lang.Character", "java.math.BigInteger");

	/**
	 * The classes {@code clojure.lisp}'s {@code %clojure-stream-class} answers for a
	 * stream.
	 */
	static final List<String> STREAM_CLASSES = List.of("java.io.OutputStreamWriter", "java.io.PrintWriter",
			"java.io.StringWriter", "clojure.lang.LineNumberingPushbackReader", "java.io.BufferedReader");

	private static final Set<String> NAMED = named();

	private ClojureValueClasses() {
	}

	private static List<String> concat(List<String> a, List<String> b) {
		List<String> out = new ArrayList<>(a);
		out.addAll(b);
		return out;
	}

	private static Set<String> named() {
		Set<String> names = new HashSet<>();
		for (Kind kind : Kind.values()) {
			names.addAll(kind.classes);
		}
		return names;
	}

	/**
	 * The kinds whose values are instances of the class.
	 * @param className the class's binary name
	 * @return the kinds, in {@link Kind} order
	 */
	static List<Kind> kindsOf(String className) {
		List<Kind> kinds = new ArrayList<>();
		for (Kind kind : Kind.values()) {
			if (kind.classes.contains(className)) {
				kinds.add(kind);
			}
		}
		return kinds;
	}

	/**
	 * The {@code clojure.lang} class a bare simple name spells when no import or
	 * {@code java.lang} default claims it ({@code Keyword}, {@code IPersistentMap}), the
	 * leniency the dispatch keywords have too; null when no kind names one.
	 * @param simpleName the spelling, without a package
	 * @return the binary name, or null
	 */
	static @Nullable String clojureLang(String simpleName) {
		String name = "clojure.lang." + simpleName;
		return NAMED.contains(name) ? name : null;
	}

	/**
	 * The stream classes whose values are instances of the class: a stream class or one
	 * of its supers ({@link ClojureClassBases#supersOf}).
	 * @param className the class's binary name
	 * @return the classes {@code %clojure-stream-class} answers that are instances
	 */
	static List<String> streamClassesOf(String className) {
		List<String> classes = new ArrayList<>();
		for (String stream : STREAM_CLASSES) {
			if (stream.equals(className) || ClojureClassBases.supersOf(stream).contains(className)) {
				classes.add(stream);
			}
		}
		return classes;
	}

	/**
	 * The throwable classes a condition's chain may name whose instances the interface
	 * covers, the highest of each line: {@code Throwable} for {@code Serializable},
	 * {@code ExceptionInfo} and its {@code clojure.lang} siblings for
	 * {@code IExceptionInfo}. Empty for a class, whose chain {@code instance?} tests
	 * itself.
	 * @param className the class's binary name
	 * @return the classes, sorted
	 */
	static List<String> throwablesImplementing(String className) {
		Set<String> candidates = new LinkedHashSet<>();
		candidates.add(ClojureThrowables.THROWABLE);
		candidates.addAll(ClojureThrowables.PARENTS.keySet());
		List<String> hits = new ArrayList<>();
		for (String candidate : candidates) {
			if (ClojureClassBases.supersOf(candidate).contains(className)) {
				hits.add(candidate);
			}
		}
		List<String> highest = new ArrayList<>();
		for (String hit : hits) {
			List<String> chain = ClojureThrowables.chainOf(hit);
			boolean covered = false;
			for (int i = 1; chain != null && i < chain.size(); i++) {
				covered |= hits.contains(chain.get(i));
			}
			if (!covered) {
				highest.add(hit);
			}
		}
		highest.sort(null);
		return highest;
	}

	/**
	 * Whether the host loads the class.
	 * @param className the class's binary name
	 * @return {@code true} when it resolves
	 */
	static boolean loads(String className) {
		try {
			ClojureHostClasses.load(className);
			return true;
		}
		catch (ClassNotFoundException | LinkageError _) {
			return false;
		}
	}

	/**
	 * Whether a host object may be an instance of the class: the host loads it and no
	 * value of it is converted into a Clojure value at the {@code java:} boundary.
	 * @param className the class's binary name
	 * @param loads whether the host loads it ({@link #loads})
	 * @return {@code true} when {@code instance?} needs the host arm
	 */
	static boolean hostMayHold(String className, boolean loads) {
		return loads && !CONVERTED.contains(className) && !className.equals(ClojureClassBases.OBJECT);
	}

}
