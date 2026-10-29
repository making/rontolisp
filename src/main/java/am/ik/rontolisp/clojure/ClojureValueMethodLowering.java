package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Instance calls on a Clojure value that has no host object: a collection (vector, list,
 * lazy seq, map, record, set, sorted collection), a keyword, a symbol, a ratio, an atom
 * cell, a fn or nil (the empty list here). The oracle calls the {@code clojure.lang} /
 * {@code java.util} interface method of the value's class ({@code Counted}, {@code List},
 * {@code Map}, {@code Set}, {@code Named}, {@code Ratio}, {@code IFn}, ...); here the
 * common ones answer through the core verb that does the same, so they run on every
 * backend; where the host is, another method of a JDK interface the oracle's class
 * implements ({@code toArray}, {@code entrySet}, {@code sort}) is called on the value's
 * Java object ({@code %clojure-host-member}), never on the value itself, which
 * {@code java:call} takes only as a string, number, character or {@code t}; every other
 * method is refused by name.
 *
 * <p>
 * A record, deftype or reify is such a value too, and its class also has the protocol
 * methods its body implements and, for a record or deftype, its declared fields: a site
 * naming one of those ({@link TypedMembers}) calls the method's dispatcher or reads the
 * field, and refuses any other name on such a value in the oracle's words, since its
 * class is fully known. A site naming none pays nothing for them.
 *
 * <p>
 * A row maps a method at one arity to arms: the kinds that answer it (a one-argument
 * predicate's bare test) and the core verb, a datum lowered with the receiver and the
 * arguments bound to locals of this class's own names. A kind the row names no arm for is
 * refused in the oracle's words ({@code No matching method contains found taking 1 args
 * for class clojure.lang.PersistentArrayMap}), since its class lacks the method. A method
 * no row names goes to the Java object of a kind whose class has it through a JDK
 * interface, and is refused otherwise: as unsupported when some value class of the oracle
 * has it ({@link ClojureValueMethods}), in the oracle's words when none does.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureValueMethodLowering {

	private ClojureValueMethodLowering() {
	}

	/**
	 * The receiver's local name inside an arm: the {@code %} suffix keeps it generated.
	 */
	private static final String RECV = "recv%";

	private static final LispSymbol R = new LispSymbol(RECV);

	private static final LispSymbol A = new LispSymbol(argName(0));

	private static final LispSymbol B = new LispSymbol(argName(1));

	/**
	 * One arm: the predicates whose kinds answer (any one of them; none for every value
	 * kind) and the body over the bound locals.
	 */
	private record Arm(List<String> kinds, Function<ClojureLowering, LispVal> body) {

	}

	/**
	 * The atom cell's kind (atoms, volatiles, refs and agents share it), which no
	 * one-argument predicate names.
	 */
	private static final String ATOM = "atom";

	/** A fn's kind, which no one-argument predicate here names. */
	private static final String FUNCTION = "fn";

	/**
	 * The kind of a {@code (:C%CLASS name)} class designator ({@code .getClass} of a fn,
	 * a class literal where no host is), which no one-argument predicate names.
	 */
	private static final String CLASS_DESIGNATOR = "class-designator";

	/**
	 * A namespace's kind ({@code *ns*}, {@code the-ns}), tested through the namespace
	 * family's own test, an arm a program making no namespace sheds.
	 */
	private static final String NAMESPACE = "namespace";

	/** The most arguments {@code IFn.invoke} takes before its variadic overload. */
	private static final int MAX_INVOKE_ARITY = 20;

	/** The predicates whose kinds include the list, which nil stands for when empty. */
	private static final List<String> LIST_KINDS = List.of("coll?", "seq?", "list?", "sequential?");

	/** Any instant's kind: a Date, a Timestamp or a Calendar. */
	private static final String INSTANT = "instant";

	/** The kind of an instant that is a {@code java.util.Date}: a Date or a Timestamp. */
	private static final String DATE = "date";

	/** A Timestamp's kind. */
	private static final String TIMESTAMP = "timestamp";

	/** The UUID's kind. */
	private static final String UUID = "uuid";

	/**
	 * The kind of the iterator over a seq the library makes ({@code .iterator} of a
	 * collection, {@code clojure.lang.SeqIterator}), which no predicate names.
	 */
	private static final String SEQ_ITERATOR = "seq-iterator";

	/** The class whose construction a seq iterator's {@code remove} throws. */
	private static final String UNSUPPORTED_OPERATION = "java.lang.UnsupportedOperationException";

	/**
	 * {@code .putAll} of one map into a {@code java.util.HashMap} stand-in where the host
	 * is not (wasm), storing the entries into the target's table ({@code clojure.lisp}).
	 */
	private static final String HASH_MAP_PUT_ALL = "RONTOLISP::%CLOJURE-HASH-MAP-PUT-ALL";

	/** What makes the Java object a value is ({@code clojure.lisp}). */
	private static final String HOST_MEMBER = "RONTOLISP::%CLOJURE-HOST-MEMBER";

	/**
	 * What makes the Java object a value is with a seq iterator its {@code Iterator} face
	 * ({@code clojure.lisp}).
	 */
	private static final String HOST_ITERATOR = "RONTOLISP::%CLOJURE-HOST-ITERATOR";

	private static final String OBJECT = "java.lang.Object";

	private static final String LIST = "java.util.List";

	private static final String COMPARABLE = "java.lang.Comparable";

	/**
	 * A kind of value whose Java object ({@code %clojure-host-member}) implements JDK
	 * interfaces the oracle's class implements too: the test of the kind over the bound
	 * receiver, the interfaces' names, and what makes the object.
	 */
	private record HostKind(Function<LispVal, LispVal> test, List<String> types, String maker) {

	}

	/**
	 * The JDK types every {@link #HOST_KINDS} kind's Java object implements: what
	 * {@link #declares} reads, on every target. A native image without a JDK's class
	 * files answers these from its own reflection, so its build registers every one
	 * ({@code .kb/clojure-frontend.md}, "Host classes").
	 * @return the types, in kind order
	 */
	static List<String> hostKindTypes() {
		List<String> types = new ArrayList<>();
		for (HostKind kind : HOST_KINDS) {
			types.addAll(kind.types());
		}
		return List.copyOf(types);
	}

	/**
	 * The kinds whose Java object answers the methods of the JDK interfaces of the
	 * oracle's class: a vector a {@code List}, {@code RandomAccess} and
	 * {@code Comparable}, a list or seq a {@code List}, a set a {@code Set}, a map or
	 * record a {@code Map}, a keyword or symbol a {@code Comparable}, a ratio a
	 * {@code Number}, a seq iterator an {@code Iterator}, and a fn the oracle's
	 * {@code AFunction}'s three JDK interfaces ({@code Comparator}'s {@code reversed} or
	 * {@code thenComparing}; its {@code IFn} methods are rows). {@code Object}'s methods
	 * every value but nil answers.
	 */
	private static final List<HostKind> HOST_KINDS = List.of(
			new HostKind(r -> ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-IS-VECTOR"), r),
					List.of(LIST, "java.util.RandomAccess", COMPARABLE), HOST_MEMBER),
			new HostKind(r -> ClojurePredicateLowering.rawTest("seq?", r), List.of(LIST), HOST_MEMBER),
			new HostKind(r -> ClojurePredicateLowering.rawTest("set?", r), List.of("java.util.Set"), HOST_MEMBER),
			new HostKind(r -> ClojurePredicateLowering.rawTest("map?", r), List.of("java.util.Map"), HOST_MEMBER),
			new HostKind(r -> ClojurePredicateLowering.rawTest("ident?", r), List.of(COMPARABLE), HOST_MEMBER),
			new HostKind(r -> ClojurePredicateLowering.rawTest("ratio?", r), List.of("java.lang.Number", COMPARABLE),
					HOST_MEMBER),
			new HostKind(r -> ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SEQ-ITERATOR-P"), r),
					List.of("java.util.Iterator"), HOST_ITERATOR),
			new HostKind(r -> ClojureLowerUtil.list(ClojureLowerUtil.sym("functionp"), r),
					List.of("java.util.Comparator", "java.lang.Runnable", "java.util.concurrent.Callable"),
					HOST_MEMBER));

	/**
	 * The kinds no one-argument predicate names exactly, each to its family's test, which
	 * a program making no such value folds ({@link ClojureArms.Family#INSTANT},
	 * {@link ClojureArms.Family#UUID}).
	 */
	private static final Map<String, String> FAMILY_KINDS = Map.of(INSTANT, ClojurePredicateLowering.INSTANT_P, DATE,
			ClojurePredicateLowering.INST_P, TIMESTAMP, ClojurePredicateLowering.TIMESTAMP_P, UUID,
			ClojurePredicateLowering.UUID_P, NAMESPACE, "RONTOLISP::%CLOJURE-NS-OBJECT-P");

	/**
	 * The values a time kind's arm answers, by the tests {@link ClojureTimeValueLowering}
	 * tells them apart with: an instant arm answers a Date, a Timestamp and a Calendar.
	 */
	private static final Map<String, Set<String>> TIME_KINDS = Map.of(INSTANT,
			Set.of(ClojurePredicateLowering.DATE_P, ClojurePredicateLowering.TIMESTAMP_P,
					ClojurePredicateLowering.CALENDAR_P),
			DATE, Set.of(ClojurePredicateLowering.DATE_P, ClojurePredicateLowering.TIMESTAMP_P), TIMESTAMP,
			Set.of(ClojurePredicateLowering.TIMESTAMP_P), UUID, Set.of(ClojurePredicateLowering.UUID_P));

	/** The rows, by {@code method/arity}. */
	private static final Map<String, List<Arm>> ROWS = new HashMap<>();

	static {
		collectionRows();
		lookupRows();
		entryRows();
		metaRows();
		functionRows();
		updateRows();
		nameAndNumberRows();
		timeValueRows();
	}

	private static void collectionRows() {
		for (String counted : List.of("count", "size")) {
			row(counted, 0, arm(List.of("coll?"), core("count", R)));
		}
		row("isEmpty", 0, arm(List.of("coll?"), core("empty?", R)));
		row("length", 0, arm(List.of("indexed?"), core("count", R)));
		row("seq", 0, arm(List.of("coll?"), core("seq", R)));
		row("first", 0, arm(List.of("seq?"), core("first", R)));
		row("more", 0, arm(List.of("seq?"), core("rest", R)));
		row("peek", 0, arm(List.of("indexed?", "list?"), core("peek", R)));
		row("pop", 0, arm(List.of("indexed?", "list?"), core("pop", R)));
		row("empty", 0, arm(List.of("coll?"), core("empty", R)));
		row("rseq", 0, arm(List.of("reversible?"), core("rseq", R)));
		row("keySet", 0, arm(List.of("map?"), core("set", core("keys", R))));
		row("subList", 2, arm(List.of("indexed?"), core("subvec", R, A, B)),
				arm(List.of("seq?"), core("subvec", core("vec", R), A, B)));
		// a collection's iterator steps its seq, like the oracle's SeqIterator; the
		// iterator answers hasNext and next
		row("iterator", 0, new Arm(List.of("coll?"),
				ctx -> ClojureLowerUtil.list(new LispSymbol(ClojureInterfaces.SEQ_ITERATOR), ctx.localSym(RECV))));
		row("hasNext", 0, new Arm(List.of(SEQ_ITERATOR), ctx -> ctx.booleanAnswer(
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ITER-HAS-NEXT"), ctx.localSym(RECV)))));
		row("next", 0, arm(List.of("seq?"), core("next", R)), new Arm(List.of(SEQ_ITERATOR),
				ctx -> ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ITER-NEXT"), ctx.localSym(RECV))));
		// SeqIterator.remove throws, a message-less UnsupportedOperationException
		row("remove", 0, arm(List.of(SEQ_ITERATOR), ClojureLowerUtil.list(new LispSymbol("throw"),
				ClojureLowerUtil.list(new LispSymbol("new"), new LispSymbol(UNSUPPORTED_OPERATION)))));
		row("assocN", 2, arm(List.of("indexed?"), core("assoc", R, A, B)));
		// a sorted map or set is the oracle's Sorted: its comparator, an entry's key, the
		// seq from a key and the seq either way, which a Sorted body's subseq reads
		row("comparator", 0, new Arm(List.of("sorted?"), ctx -> ClojureLowerUtil
			.list(new LispSymbol("RONTOLISP::%CLOJURE-SORTED-COMPARATOR"), ctx.localSym(RECV))));
		row("entryKey", 1,
				new Arm(List.of("sorted?"),
						ctx -> ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SORTED-ENTRY-KEY"),
								ctx.localSym(RECV), ctx.localSym(argName(0)))));
		row("seqFrom", 2,
				new Arm(List.of("sorted?"),
						ctx -> ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SORTED-SEQ-FROM"),
								ctx.localSym(RECV), ctx.localSym(argName(0)), ctx.localSym(argName(1)))));
		row("seq", 1, new Arm(List.of("sorted?"), ctx -> ClojureLowerUtil
			.list(new LispSymbol("RONTOLISP::%CLOJURE-SORTED-SEQ-DIR"), ctx.localSym(RECV), ctx.localSym(argName(0)))));
	}

	private static void lookupRows() {
		row("get", 1, arm(List.of("map?", "set?"), core("get", R, A)),
				new Arm(List.of("sequential?"), ClojureValueMethodLowering::listGet));
		row("nth", 1, new Arm(List.of("indexed?"), ClojureValueMethodLowering::listGet));
		row("nth", 2, arm(List.of("indexed?"), core("nth", R, A, B)));
		row("valAt", 1, arm(List.of("map?", "indexed?"), core("get", R, A)));
		row("valAt", 2, arm(List.of("map?", "indexed?"), core("get", R, A, B)));
		row("contains", 1, arm(List.of("set?"), core("contains?", R, A)),
				arm(List.of("sequential?"), core("contains?", core("set", R), A)));
		row("containsKey", 1, arm(List.of("map?", "indexed?"), core("contains?", R, A)));
		row("containsValue", 1, arm(List.of("map?"), core("contains?", core("set", core("vals", R)), A)));
		row("indexOf", 1, new Arm(List.of("sequential?"), ctx -> indexOf(ctx, false)));
		row("lastIndexOf", 1, new Arm(List.of("sequential?"), ctx -> indexOf(ctx, true)));
		row("entryAt", 1, arm(List.of("map?", "indexed?"), core("find", R, A)));
	}

	/**
	 * A map entry's key and value: a map entry is a plain 2-vector here, so
	 * {@code key}/{@code getKey} answer its first member and {@code val}/{@code getValue}
	 * its second, while any other vector keeps the refusal, in the oracle's
	 * {@code No matching field found} words (flatland.ordered reads its entries this way;
	 * the oracle's reflection finds the methods only on a map entry).
	 */
	private static void entryRows() {
		row("key", 0, new Arm(List.of("indexed?"), ctx -> entryPart(ctx, 0, "key")));
		row("val", 0, new Arm(List.of("indexed?"), ctx -> entryPart(ctx, 1, "val")));
		row("getKey", 0, new Arm(List.of("indexed?"), ctx -> entryPart(ctx, 0, "getKey")));
		row("getValue", 0, new Arm(List.of("indexed?"), ctx -> entryPart(ctx, 1, "getValue")));
	}

	/**
	 * The entry's member at the index: the member when the receiver is a 2-vector, else
	 * the refusal. The arms' vector test answers every vector, so the count decides.
	 */
	/**
	 * The entry's member at the index: the member when the receiver is a 2-vector, else
	 * the refusal. The arms' vector test answers every vector, so the count decides; the
	 * test is a Clojure boolean (true or the false object, both truthy but nil), so the
	 * shell reads it through {@code ifFalsey} like any {@code if} does.
	 */
	private static LispVal entryPart(ClojureLowering ctx, int index, String method) {
		LispSymbol recv = ctx.localSym(RECV);
		LispVal test = ctx.lower(core("=", core("count", R), new LispInteger(2)));
		LispVal member = ctx.lower(core("nth", R, new LispInteger(index)));
		return ctx.ifFalsey(test, member, refusal(recv, method, true, List.of()));
	}

	/**
	 * The metadata verbs: {@code meta} answers the metadata, {@code withMeta} a copy
	 * carrying it, for a collection, a function, a var or a symbol, like the oracle's
	 * {@code IObj} implementors; any other value keeps the refusal, in the oracle's
	 * {@code No matching field found} words (flatland.ordered's {@code meta} method reads
	 * its backing map this way).
	 */
	private static void metaRows() {
		List<String> kinds = List.of("coll?", FUNCTION, "var?", "simple-symbol?", "qualified-symbol?");
		row("meta", 0, arm(kinds, core("meta", R)));
		row("withMeta", 1, arm(kinds, core("with-meta", R, A)));
	}

	/**
	 * A fn is the oracle's {@code AFunction}: an {@code IFn} ({@code invoke} of up to
	 * twenty arguments, {@code applyTo}), a {@code Callable} ({@code call}), a
	 * {@code Runnable} ({@code run}) and a {@code Comparator} ({@code compare}, whose
	 * boolean answer is {@code AFunction.compare}'s); the {@code IFn} ones answer for any
	 * {@code ifn?} value, an {@code AFn} too.
	 */
	private static void functionRows() {
		for (int n = 0; n <= MAX_INVOKE_ARITY; n++) {
			List<LispVal> call = new ArrayList<>();
			call.add(R);
			for (int i = 0; i < n; i++) {
				call.add(new LispSymbol(argName(i)));
			}
			row("invoke", n, arm(List.of("ifn?"), ClojureLowerUtil.list(call)));
		}
		row("applyTo", 1, arm(List.of("ifn?"), core("apply", R, A)));
		row("call", 0, arm(List.of("ifn?"), ClojureLowerUtil.list(R)));
		row("run", 0, arm(List.of("ifn?"),
				ClojureLowerUtil.list(new LispSymbol("do"), ClojureLowerUtil.list(R), new LispSymbol("nil"))));
		row("compare", 2, new Arm(List.of(FUNCTION), ClojureValueMethodLowering::fnCompare));
		// a multimethod is its dispatcher function here: a registered one answers
		// clojure.lang.MultiFn's dispatchFn and getMethod (spec.alpha's multi-spec)
		row("dispatchFn", 0, new Arm(List.of(FUNCTION), ctx -> multiFnMember(ctx, "dispatchFn")));
		row("getMethod", 1, new Arm(List.of(FUNCTION), ctx -> multiFnMember(ctx, "getMethod")));
	}

	/**
	 * A {@code clojure.lang.MultiFn} member of a fn: through the multimethod registry
	 * ({@code clojure.lisp}) when the fn is a multimethod's dispatcher -- the dispatch
	 * function's getter called, or {@code MultiFn.getMethod} of the argument -- else the
	 * refusal in the oracle's words, as for any other {@code AFunction}.
	 */
	private static LispVal multiFnMember(ClojureLowering ctx, String method) {
		LispSymbol recv = ctx.localSym(RECV);
		boolean getMethod = method.equals("getMethod");
		List<LispVal> args = getMethod ? List.of(ctx.localSym(argName(0))) : List.of();
		LispVal registered = ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), recv,
				new LispSymbol(ClojureDispatchLowering.MULTI_REGISTRY));
		LispVal answer = getMethod
				? ClojureLowerUtil.list(new LispSymbol(ClojureDispatchLowering.MULTI_GET_METHOD), recv, args.get(0))
				: ClojureLowerUtil.list(new LispSymbol(ClojureDispatchLowering.MULTI_DISPATCH_FN), recv);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), registered, answer, refusal(recv, method, true, args));
	}

	private static void updateRows() {
		row("cons", 1, arm(List.of("coll?"), core("conj", R, A)));
		row("assoc", 2, arm(List.of("map?", "indexed?"), core("assoc", R, A, B)));
		row("without", 1, arm(List.of("map?"), core("dissoc", R, A)));
		row("disjoin", 1, arm(List.of("set?"), core("disj", R, A)));
		row("equiv", 1, arm(List.of(), core("=", R, A)));
		// the class's Java equals, which parts from = (a record equals a map of its
		// entries, a double only a double of its bits)
		row("equals", 1, new Arm(List.of(), ctx -> ctx.booleanAnswer(ClojureLowerUtil
			.list(new LispSymbol("RONTOLISP::%CLOJURE-JAVA-EQUAL"), ctx.localSym(RECV), ctx.localSym(argName(0))))));
		row("compareTo", 1, arm(List.of("indexed?", "ident?", "ratio?", INSTANT, UUID), core("compare", R, A)));
	}

	/**
	 * The methods of the instants and the UUID a program reads: a Date's (or a
	 * Timestamp's) {@code getTime}, {@code before} and {@code after} (by milliseconds,
	 * {@code Date}'s) and {@code setTime} (in place, the oracle's Date being mutable), a
	 * Timestamp's {@code getNanos}, a UUID's halves, version and variant.
	 */
	private static void timeValueRows() {
		row("getTime", 0, arm(List.of(DATE), core("inst-ms", R)));
		row("before", 1, arm(List.of(DATE), core("<", core("inst-ms", R), core("inst-ms", A))));
		row("after", 1, arm(List.of(DATE), core(">", core("inst-ms", R), core("inst-ms", A))));
		row("setTime", 1, new Arm(List.of(DATE), ctx -> ClojureLowerUtil.list(
				new LispSymbol("RONTOLISP::%CLOJURE-INST-SET-TIME"), ctx.localSym(RECV),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-LONG-CAST"), ctx.localSym(argName(0))))));
		row("getNanos", 0, new Arm(List.of(TIMESTAMP), ctx -> part(ctx, "CADDR")));
		row("getMostSignificantBits", 0, new Arm(List.of(UUID), ctx -> part(ctx, "CADR")));
		row("getLeastSignificantBits", 0, new Arm(List.of(UUID), ctx -> part(ctx, "CADDR")));
		row("version", 0, new Arm(List.of(UUID), ctx -> part(ctx, "RONTOLISP::%CLOJURE-UUID-VERSION")));
		row("variant", 0, new Arm(List.of(UUID), ctx -> part(ctx, "RONTOLISP::%CLOJURE-UUID-VARIANT")));
	}

	/** The function applied to the bound receiver. */
	private static LispVal part(ClojureLowering ctx, String function) {
		return ClojureLowerUtil.list(new LispSymbol(function), ctx.localSym(RECV));
	}

	private static void nameAndNumberRows() {
		// a class designator's name is the class's, like Class#getName; a namespace's
		// its name symbol, like Namespace#getName and its public name field
		row("getName", 0, arm(List.of("ident?"), core("name", R)),
				new Arm(List.of(CLASS_DESIGNATOR), ctx -> part(ctx, "CADR")),
				arm(List.of(NAMESPACE), core("ns-name", R)));
		row("name", 0, arm(List.of(NAMESPACE), core("ns-name", R)));
		row("getNamespace", 0, arm(List.of("ident?"), core("namespace", R)));
		row("sym", 0, arm(List.of("keyword?"), core("symbol", R)));
		row("numerator", 0, arm(List.of("ratio?"), core("numerator", R)));
		row("denominator", 0, arm(List.of("ratio?"), core("denominator", R)));
		for (String widened : List.of("doubleValue", "floatValue")) {
			row(widened, 0, arm(List.of("ratio?"), core("double", R)));
		}
		for (String truncated : List.of("intValue", "longValue")) {
			row(truncated, 0, arm(List.of("ratio?"), core("long", R)));
		}
		// a fn's own class, the designator of the class the oracle compiles it to (a
		// core function's clojure.core$name, else an anonymous fn's), where class
		// answers the one fn kind; any other value's class
		row("getClass", 0,
				new Arm(List.of(FUNCTION), ctx -> ClojureLowerUtil.list(ClojureLowerUtil.sym("list"),
						new LispSymbol(":C%CLASS"), part(ctx, "RONTOLISP::%CLOJURE-FN-CLASS-NAME"))),
				arm(List.of(), core("class", R)));
		// .isArray of a class keyword: only a byte array's class ([B) is an array
		// class here (a char[] is a vector of characters, a host array unmarshals
		// to a list), so its name decides, like the oracle's Class#isArray
		// .isArray of a class keyword: only a byte array's class ([B) is an array
		// class here (a char[] is a vector of characters, a host array unmarshals
		// to a list), so its name decides, like the oracle's Class#isArray. The =
		// already answers true-or-false, so no booleanAnswer wraps it (whose test
		// would read the false object as true).
		row("isArray", 0, arm(List.of("keyword?"), core("=", core("name", R), LispString.literal("[B"))));
		// IHashEq: a collection's, a keyword's or a symbol's hasheq is its hash
		row("hasheq", 0, arm(List.of("coll?", "ident?"), core("hash", R)));
		row("deref", 0, arm(List.of(ATOM), core("deref", R)));
		row("reset", 1, arm(List.of(ATOM), core("reset!", R, A)));
		row("swap", 1, arm(List.of(ATOM), core("swap!", R, A)));
	}

	private static void row(String method, int arity, Arm... arms) {
		ROWS.put(method + "/" + arity, List.of(arms));
	}

	private static Arm arm(List<String> kinds, LispVal datum) {
		return new Arm(kinds, ctx -> ctx.lower(datum));
	}

	private static LispVal core(String verb, LispVal... operands) {
		List<LispVal> items = new ArrayList<>();
		items.add(new LispSymbol(ClojureCoreNames.PREFIX + verb));
		items.addAll(List.of(operands));
		return ClojureLowerUtil.list(items);
	}

	private static String argName(int i) {
		return "arg" + i + "%";
	}

	/**
	 * {@code List.get} / {@code Indexed.nth} of one index: the member, signalling past
	 * either end like the oracle's {@code IndexOutOfBoundsException} ({@code nth} answers
	 * nil there).
	 */
	private static LispVal listGet(ClojureLowering ctx) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-LIST-GET"), ctx.localSym(RECV),
				ctx.localSym(argName(0)));
	}

	/**
	 * {@code Comparator.compare} of a fn: {@code AFunction.compare} over the receiver and
	 * the two arguments.
	 */
	private static LispVal fnCompare(ClojureLowering ctx) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-FN-COMPARE"), ctx.localSym(RECV),
				ctx.localSym(argName(0)), ctx.localSym(argName(1)));
	}

	/**
	 * {@code indexOf} / {@code lastIndexOf}: the first (last) index of a member {@code =}
	 * to the argument, or {@code -1}, like {@code java.util.List}.
	 */
	private static LispVal indexOf(ClojureLowering ctx, boolean last) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-INDEX-OF"), ctx.localSym(RECV),
				ctx.localSym(argName(0)), last ? ClojureLowering.TRUE_CONST : ClojureLowering.NIL_CONST);
	}

	/**
	 * The value arm of an instance call over an already-bound receiver: when the receiver
	 * is a Clojure value with no host object, or nil, the mapped core verb of the first
	 * kind that answers the method, else the refusal; anything else keeps {@code call}.
	 * Nil is the empty list here, so it answers where a list does ({@code .isEmpty} of an
	 * empty {@code filter} is true, like the oracle's empty seq) and is the oracle's
	 * {@code NullPointerException} elsewhere. The arguments run once, before the
	 * dispatch, like the oracle's.
	 * @param ctx the hub
	 * @param method the method name
	 * @param designator the method as {@code java:call} names it (its parameter types
	 * when tagged)
	 * @param recv the bound receiver
	 * @param args the lowered arguments
	 * @param call the call for any other receiver
	 * @return the form
	 */
	static LispVal valueArm(ClojureLowering ctx, String method, String designator, LispSymbol recv, List<LispVal> args,
			LispVal call) {
		List<Arm> arms = ROWS.get(method + "/" + args.size());
		// a row at another arity: the method exists, so the refusal is the oracle's own
		boolean known = arms != null || ROWS.keySet().stream().anyMatch(key -> key.startsWith(method + "/"))
				|| ClojureInterfaces.definedDeclares(ctx, method);
		TypedMembers typed = typedMembers(ctx, method, args.size());
		List<String> implemented = ClojureInterfaces.instanceTests(method, args.size() + 1);
		List<String> defined = ClojureInterfaces.definedTests(ctx, method, args.size() + 1);
		LispVal arm;
		if (arms == null && typed == null) {
			arm = unmappedArm(ctx, method, designator, recv, args);
			// an instant or a UUID made here answers its class's method through the
			// host object it stands for (arms a program making none sheds)
			arm = ClojureTimeValueLowering.methodArm(ctx, method, designator, recv, args, Set.of(), arm);
			if (!implemented.isEmpty()) {
				// a type implementing the interface answers its own method (an arm a
				// program storing no such row sheds, leaving the refusal)
				arm = interfaceArm(method, recv, args, implemented, arm);
			}
			if (!defined.isEmpty()) {
				// a type implementing a program-defined interface answers its own
				// method, read off its row under the interface's binary name
				arm = definedArm(method, recv, args, defined, arm);
			}
			arm = hostInterfaceArm(method, recv, args, arm);
		}
		else {
			arm = boundArm(ctx, method, designator, recv, args, arms == null ? List.of() : arms, known, typed,
					implemented, defined);
		}
		LispVal matcherCall = matcherMethod(method, recv, args);
		if (!ctx.hostTarget && method.equals("format") && args.size() == 1) {
			// where no host is, .format of the ISO_INSTANT portable value formats
			// an instant of this front end's own (data.json's write-instant); any
			// other value answers the rows as before. Inside the value branch: the
			// portable value is one, and the host arm it would otherwise reach
			// refuses it.
			arm = ClojureDateFormatterLowering.formatArm(recv, args.get(0), arm);
		}
		if (!ctx.hostTarget) {
			// where no host is, a date builder or formatter call threads the
			// marker, so malli's formatter defs load; any other receiver keeps
			// the arm it had
			arm = ClojureDateBuilderLowering.methodArm(recv, method, args, arm);
		}
		if (matcherCall != null) {
			// a matcher answers java.util.regex.Matcher's own method: an arm a program
			// making no matcher sheds (ClojureArms.Family.MATCHER), leaving the arm it
			// had
			arm = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(new LispSymbol(ClojureStringLowering.MATCHER_VALUE_P), recv), matcherCall,
					arm);
		}
		LispVal patternCall = patternMethod(method, recv, args);
		if (patternCall != null) {
			// a pattern answers java.util.regex.Pattern's matcher and pattern, as the
			// one Pattern/compile and a literal make on every backend
			arm = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RE-PATTERN-P"), recv), patternCall, arm);
		}
		LispVal dispatch = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-VALUE-RECEIVER-P"), recv), arm, call);
		LispVal bigIntegerCall = bigIntegerMethod(method, recv, args);
		if (bigIntegerCall != null) {
			// an integer answers java.math.BigInteger's own method: a BigInteger is an
			// integer here (converted at the java: boundary), and no Long method of
			// the name exists for the host call to take
			dispatch = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("integerp"), recv), bigIntegerCall, dispatch);
		}
		return dispatch;
	}

	/**
	 * The call of a {@code java.math.BigInteger} method {@code java.lang.Long} lacks, on
	 * an integer (tools.reader's number reader): {@code negate} and {@code bitLength},
	 * the length past the sign bit, and {@code clojure.lang.BigInt}'s
	 * {@code toBigInteger}, the integer itself (data.generators' {@code bigdec}); null
	 * for any other method.
	 */
	private static @Nullable LispVal bigIntegerMethod(String method, LispSymbol recv, List<LispVal> args) {
		return switch (method + "/" + args.size()) {
			case "negate/0" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("-"), recv);
			case "bitLength/0" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("integer-length"), recv);
			case "toBigInteger/0" -> recv;
			default -> null;
		};
	}

	/**
	 * The call of a {@code java.util.regex.Pattern} method on a pattern: {@code matcher}
	 * of an input is {@code re-matcher}'s matcher, {@code pattern} the source; null for
	 * any other method.
	 */
	private static @Nullable LispVal patternMethod(String method, LispSymbol recv, List<LispVal> args) {
		return switch (method + "/" + args.size()) {
			case "matcher/1" ->
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RE-MATCHER"), recv, args.get(0));
			case "pattern/0" -> ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-STR-OF"), recv,
					LispString.literal(""), ClojureLowering.NIL_CONST);
			default -> null;
		};
	}

	/**
	 * The call of a {@code java.util.regex.Matcher} method on a matcher
	 * ({@code clojure.lisp} "Matcher methods"): {@code find} of none or a start,
	 * {@code lookingAt}, {@code matches}, {@code group}, {@code start} and {@code end} of
	 * none or a group, {@code groupCount}, {@code reset} and {@code pattern}; null for
	 * any other method.
	 */
	private static @Nullable LispVal matcherMethod(String method, LispSymbol recv, List<LispVal> args) {
		String helper = switch (method + "/" + args.size()) {
			case "find/0" -> "FIND";
			case "find/1" -> "FIND-FROM";
			case "lookingAt/0" -> "LOOKING-AT";
			case "matches/0" -> "MATCHES";
			case "group/0", "group/1" -> "GROUP";
			case "start/0", "start/1" -> "START";
			case "end/0", "end/1" -> "END";
			case "groupCount/0" -> "GROUP-COUNT";
			case "reset/0" -> "RESET";
			case "pattern/0" -> "PATTERN";
			default -> null;
		};
		if (helper == null) {
			return null;
		}
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol("RONTOLISP::%CLOJURE-MATCHER-" + helper));
		call.add(recv);
		if (args.isEmpty() && (helper.equals("GROUP") || helper.equals("START") || helper.equals("END"))) {
			// group 0, the whole match
			call.add(new LispInteger(0));
		}
		call.addAll(args);
		return ClojureLowerUtil.list(call);
	}

	/**
	 * The test of a receiver whose type implements a method through one of the interfaces
	 * declaring it: each interface's test, a disjunct of its own family.
	 */
	private static LispVal implementedTest(List<String> implemented, LispVal recv) {
		if (implemented.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(implemented.get(0)), recv);
		}
		List<LispVal> tests = new ArrayList<>();
		for (String test : implemented) {
			tests.add(ClojureLowerUtil.list(new LispSymbol(test), recv));
		}
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("or"), tests);
	}

	/**
	 * {@code (if implemented call otherwise)}: the call of a record's, deftype's or
	 * reify's own method ahead of {@code otherwise}.
	 */
	private static LispVal interfaceArm(String method, LispVal recv, List<LispVal> args, List<String> implemented,
			LispVal otherwise) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), implementedTest(implemented, recv),
				interfaceCall(method, recv, args), otherwise);
	}

	/**
	 * The call of the method a record's, deftype's or reify's type implements for an
	 * interface (its row's entry), over the receiver and the arguments. A method only
	 * defaults declare may be absent from the row: its call goes through
	 * {@code %clojure-default-method}, which refuses an absent one by name (the
	 * interface's default body is Java, which no backend here runs).
	 */
	private static LispVal interfaceCall(String method, LispVal recv, List<LispVal> args) {
		List<LispVal> call = new ArrayList<>();
		call.add(ClojureLowerUtil.sym("funcall"));
		String reader = ClojureInterfaces.defaultOnly(method, args.size() + 1) ? ClojureInterfaces.DEFAULT_METHOD
				: ClojureInterfaces.ENTRY;
		call.add(ClojureLowerUtil.list(new LispSymbol(reader), recv, LispString.literal(method)));
		call.add(recv);
		call.addAll(args);
		return ClojureLowerUtil.list(call);
	}

	/**
	 * {@code (if defined call otherwise)}: the call of a record's, deftype's or reify's
	 * own method for a program-defined interface ahead of {@code otherwise}. The test
	 * reads the receiver's row for each declaring interface's binary name, which only a
	 * body implementing one stores.
	 */
	private static LispVal definedArm(String method, LispVal recv, List<LispVal> args, List<String> names,
			LispVal otherwise) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), definedTest(names, recv),
				interfaceCall(method, recv, args), otherwise);
	}

	/**
	 * {@code (if host call otherwise)}: the call of a record's, deftype's or reify's own
	 * method of the name ahead of {@code otherwise}, for a host interface no core verb
	 * reads ({@code Lock}, {@code ThreadFactory}, {@code Closeable} ...). The test reads
	 * the receiver's row by the method's name and the call's count, so a site needs no
	 * knowledge of the type, which another file may define; a program storing no such row
	 * sheds the arm ({@link ClojureArms.Family#HOST_INTERFACE}).
	 */
	private static LispVal hostInterfaceArm(String method, LispVal recv, List<LispVal> args, LispVal otherwise) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), hostInterfaceTest(method, recv, args.size()),
				hostInterfaceCall(method, recv, args), otherwise);
	}

	/**
	 * Whether the receiver's row holds a host interface's method of the name at the
	 * call's count, the target counted ({@code "lock/1"}): a call of another count keeps
	 * the refusal it had.
	 */
	private static LispVal hostInterfaceTest(String method, LispVal recv, int args) {
		return ClojureLowerUtil.list(new LispSymbol(ClojureInterfaces.HOST_METHOD_P), recv,
				LispString.literal(method + "/" + (args + 1)));
	}

	private static LispVal hostInterfaceCall(String method, LispVal recv, List<LispVal> args) {
		List<LispVal> call = new ArrayList<>();
		call.add(ClojureLowerUtil.sym("funcall"));
		call.add(ClojureLowerUtil.list(new LispSymbol(ClojureInterfaces.ENTRY), recv, LispString.literal(method)));
		call.add(recv);
		call.addAll(args);
		return ClojureLowerUtil.list(call);
	}

	/**
	 * The test of a receiver whose type implements a program-defined interface declaring
	 * the called method: its row holds the interface's binary name.
	 */
	private static LispVal definedTest(List<String> names, LispVal recv) {
		List<LispVal> tests = new ArrayList<>();
		for (String name : names) {
			tests.add(ClojureLowerUtil.list(new LispSymbol(ClojureInterfaces.ENTRY), recv, LispString.literal(name)));
		}
		return tests.size() == 1 ? tests.get(0) : ClojureLowerUtil.cons(ClojureLowerUtil.sym("or"), tests);
	}

	/**
	 * What a site's method name is on a record, deftype or reify: the protocols declaring
	 * it at the site's arity, each with the record and deftype classes whose body
	 * implements it ({@code classes}; a reify is read at run time), and whether it is an
	 * immutable declared field a zero-argument call reads.
	 */
	private record TypedMembers(List<InlineCall> calls, boolean field) {

	}

	/** One protocol a site's method may call: its var key and definition. */
	private record InlineCall(String protocolKey, ClojureLowering.ProtocolDef def, List<String> classes) {

	}

	/**
	 * The typed members a site's method name may reach, or null when it names no protocol
	 * method (at any arity) and no declared field of a known record or deftype: such a
	 * site is lowered as before. The protocols and types are the lowering's, so a type
	 * defined later in another buffer of a session is not seen.
	 */
	private static @Nullable TypedMembers typedMembers(ClojureLowering ctx, String method, int n) {
		boolean named = false;
		List<InlineCall> calls = new ArrayList<>();
		for (Map.Entry<String, ClojureLowering.ProtocolDef> protocol : new TreeMap<>(ctx.protocols).entrySet()) {
			Set<Integer> arities = protocol.getValue().arities().get(method);
			if (arities == null) {
				continue;
			}
			named = true;
			if (arities.contains(n + 1)) {
				String inline = ClojureLowering.inlineMethodKey(protocol.getKey(), method);
				List<String> classes = ctx.types.values()
					.stream()
					.filter(type -> type.inlineMethods().contains(inline))
					.map(ClojureLowering.TypeDef::className)
					.sorted()
					.toList();
				calls.add(new InlineCall(protocol.getKey(), protocol.getValue(), classes));
			}
		}
		boolean field = false;
		for (ClojureLowering.TypeDef type : ctx.types.values()) {
			if (type.fields().contains(method)) {
				named = true;
				field |= n == 0 && !type.mutableFields().contains(method);
			}
		}
		return named ? new TypedMembers(calls, field) : null;
	}

	/**
	 * The arm over the receiver and arguments bound to this class's locals: the typed
	 * members first (a body's own method, then the mapped rows, then a declared field),
	 * then the refusal -- in the oracle's words on a record, deftype or reify when the
	 * site names a typed member, since its class is fully known.
	 */
	private static LispVal boundArm(ClojureLowering ctx, String method, String designator, LispSymbol recv,
			List<LispVal> args, List<Arm> arms, boolean known, @Nullable TypedMembers typed, List<String> implemented,
			List<String> defined) {
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		scope.put(RECV, ClojureLowering.Kind.VARIABLE);
		for (int i = 0; i < args.size(); i++) {
			scope.put(argName(i), ClojureLowering.Kind.VARIABLE);
		}
		return ctx.inScope(scope, () -> {
			LispSymbol self = ctx.localSym(RECV);
			List<LispVal> bindings = new ArrayList<>();
			bindings.add(ClojureLowerUtil.list(self, recv));
			List<LispVal> locals = new ArrayList<>();
			for (int i = 0; i < args.size(); i++) {
				LispSymbol local = ctx.localSym(argName(i));
				bindings.add(ClojureLowerUtil.list(local, args.get(i)));
				locals.add(local);
			}
			List<LispVal> clauses = new ArrayList<>();
			clauses.add(ClojureLowerUtil.sym("cond"));
			if (typed != null) {
				for (InlineCall call : typed.calls()) {
					clauses.add(inlineClause(call, method, self, locals));
				}
			}
			if (!implemented.isEmpty()) {
				// a type implementing the interface answers its own method (an arm a
				// program storing no such row sheds, ClojureArms)
				clauses.add(
						ClojureLowerUtil.list(implementedTest(implemented, self), interfaceCall(method, self, locals)));
			}
			if (!defined.isEmpty()) {
				// a type implementing a program-defined interface answers its own
				// method, read off its row under the interface's binary name
				clauses.add(ClojureLowerUtil.list(definedTest(defined, self), interfaceCall(method, self, locals)));
			}
			for (Arm arm : arms) {
				clauses.add(ClojureLowerUtil.list(armTest(arm, self), arm.body().apply(ctx)));
			}
			// a type implementing a host interface answers its own method of the name
			clauses.add(ClojureLowerUtil.list(hostInterfaceTest(method, self, locals.size()),
					hostInterfaceCall(method, self, locals)));
			if (typed != null && typed.field()) {
				LispVal key = ClojureCollectionLowering.keywordForm(method);
				clauses.add(ClojureLowerUtil.list(
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DECLARED-FIELD-P"), self, key),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key,
								ClojureProtocolLowering.typedTableOf(self))));
			}
			// a row at this arity names every kind whose class has the method
			LispVal fallback = arms.isEmpty() ? unmappedArm(ctx, method, designator, self, locals)
					: refusal(self, method, true, locals);
			// an instant or a UUID made here that no row answered: its class's method
			// through the host object it stands for (arms a program making none sheds)
			fallback = ClojureTimeValueLowering.methodArm(ctx, method, designator, self, locals,
					answeredTimeKinds(arms), fallback);
			if (typed != null && !known) {
				fallback = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
								ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RECORD-P"), self),
								ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-TYPED-OPAQUE-P"), self)),
						refusal(self, method, true, locals), fallback);
			}
			clauses.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, fallback));
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(bindings),
					ClojureLowerUtil.list(clauses));
		});
	}

	/**
	 * The time values the arms answer, by the tests {@link ClojureTimeValueLowering}
	 * tells them apart with: every one for an arm naming no kind, the kinds' own
	 * otherwise.
	 */
	private static Set<String> answeredTimeKinds(List<Arm> arms) {
		Set<String> answered = new HashSet<>();
		for (Arm arm : arms) {
			if (arm.kinds().isEmpty()) {
				return ClojureTimeValueLowering.ALL;
			}
			for (String kind : arm.kinds()) {
				answered.addAll(TIME_KINDS.getOrDefault(kind, Set.of()));
			}
		}
		return answered;
	}

	/**
	 * A clause calling a protocol method's dispatcher on a typed receiver whose own body
	 * implements it -- an {@code extend-type} row is no method of the class, so it is not
	 * reached here, like the oracle.
	 */
	private static LispVal inlineClause(InlineCall call, String method, LispSymbol self, List<LispVal> locals) {
		List<LispVal> classes = new ArrayList<>();
		for (String cls : call.classes()) {
			classes.add(LispString.literal(cls));
		}
		LispVal test = ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-INLINE-METHOD-P"), self,
				call.def().inlineTable(), ClojureCollectionLowering.keywordForm(method),
				classes.isEmpty() ? ClojureLowering.NIL_CONST
						: ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(classes)));
		String ns = call.protocolKey().substring(0, call.protocolKey().lastIndexOf('/'));
		List<LispVal> invocation = new ArrayList<>();
		invocation.add(ClojureLowering.varSym(ClojureLowering.varKey(ns, method)));
		invocation.add(self);
		invocation.addAll(locals);
		return ClojureLowerUtil.list(test, ClojureLowerUtil.list(invocation));
	}

	/**
	 * Whether an arm takes the bound receiver: one of its kinds, where nil -- the empty
	 * list here -- counts as a list; an arm naming no kind takes every value but nil.
	 */
	private static LispVal armTest(Arm arm, LispSymbol self) {
		if (arm.kinds().isEmpty()) {
			return self;
		}
		List<LispVal> tests = new ArrayList<>();
		for (String kind : arm.kinds()) {
			String family = FAMILY_KINDS.get(kind);
			if (family != null) {
				tests.add(ClojureLowerUtil.list(new LispSymbol(family), self));
				continue;
			}
			tests.add(switch (kind) {
				case ATOM -> ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ATOM-P"), self);
				case FUNCTION -> ClojureLowerUtil.list(ClojureLowerUtil.sym("functionp"), self);
				case CLASS_DESIGNATOR -> ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), self),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), self), new LispSymbol(":C%CLASS")));
				case SEQ_ITERATOR -> ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-SEQ-ITERATOR-P"), self);
				// a vector's methods: a type implementing Indexed answers its own through
				// the typed clauses ahead of the rows
				case "indexed?" -> ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-IS-VECTOR"), self);
				default -> ClojurePredicateLowering.rawTest(kind, self);
			});
		}
		if (arm.kinds().stream().anyMatch(LIST_KINDS::contains)) {
			tests.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), self));
		}
		return tests.size() == 1 ? tests.get(0) : ClojureLowerUtil.cons(ClojureLowerUtil.sym("or"), tests);
	}

	/**
	 * The arm of a method no row names at the site's arity: where the host is, a kind
	 * whose oracle class has it through a JDK interface ({@link #HOST_KINDS}, or
	 * {@code Object}) calls it on the value's Java object, as the oracle calls its own
	 * object -- a vector's {@code toArray} or {@code sort}, a map's {@code entrySet} --
	 * and where the host is not (wasm), such a kind is refused as unsupported. Any other
	 * receiver is refused as unsupported when some value class has the method
	 * ({@link ClojureValueMethods}), else in the oracle's words; nil is the oracle's
	 * {@code NullPointerException}.
	 * @param ctx the hub
	 * @param method the method name
	 * @param designator the method as {@code java:call} names it
	 * @param recv the bound receiver
	 * @param args the lowered (or bound) arguments
	 * @return the arm
	 */
	private static LispVal unmappedArm(ClojureLowering ctx, String method, String designator, LispSymbol recv,
			List<LispVal> args) {
		int n = args.size();
		LispVal refusal = refusal(recv, method, !ClojureValueMethods.unsupported(method, n), args);
		List<LispVal> tests = new ArrayList<>();
		String maker = HOST_MEMBER;
		if (declares(List.of(OBJECT), method, n)) {
			tests.add(recv);
		}
		else {
			for (HostKind kind : HOST_KINDS) {
				if (declares(kind.types(), method, n)) {
					tests.add(kind.test().apply(recv));
					maker = kind.maker().equals(HOST_MEMBER) ? maker : kind.maker();
				}
			}
		}
		if (tests.isEmpty()) {
			return refusal;
		}
		LispVal answer;
		if (ctx.hostTarget) {
			List<LispVal> parts = new ArrayList<>();
			parts.add(ClojureLowerUtil.list(new LispSymbol(maker), recv));
			parts.add(LispString.literal(designator));
			parts.addAll(args);
			answer = ClojureInteropLowering.hostCall(ctx, ClojureInteropLowering.JAVA_CALL, parts, 2, true);
		}
		else if (method.equals("put") && n == 2) {
			// .put of a key and a value on a map: the map is an unmodifiable
			// java.util.Map here -- the read-only view Java is handed it as where
			// the host is -- so writing it is the oracle's
			// UnsupportedOperationException, a message-less construction like the
			// seq iterator's .remove (.kb/clojure-frontend.md, "HashMap
			// construction")
			answer = ctx.lower(ClojureLowerUtil.list(new LispSymbol("throw"),
					ClojureLowerUtil.list(new LispSymbol("new"), new LispSymbol(UNSUPPORTED_OPERATION))));
		}
		else if (method.equals("putAll") && n == 1) {
			// .putAll of one map into another where the host is not (wasm): the
			// entries stored into the target's table, answering nil, like the
			// oracle's mutation of its HashMap (malli's fast-registry fills its
			// (HashMap. 1024 0.25) this way at load). A record or a sorted map is
			// immutable there as here, so writing one is the oracle's
			// message-less UnsupportedOperationException, like .put's above; any
			// other receiver keeps the refusal, in the oracle's words
			// (.kb/clojure-frontend.md, "HashMap construction")
			LispVal stored = ClojureLowerUtil.list(new LispSymbol(HASH_MAP_PUT_ALL), recv, args.get(0));
			LispVal unmodifiable = ctx.lower(ClojureLowerUtil.list(new LispSymbol("throw"),
					ClojureLowerUtil.list(new LispSymbol("new"), new LispSymbol(UNSUPPORTED_OPERATION))));
			answer = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), recv), stored,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), ClojurePredicateLowering.rawTest("map?", recv),
							unmodifiable, refusal(recv, method, !ClojureValueMethods.unsupported(method, n), args)));
		}
		else {
			answer = refusal(recv, method, false, args);
		}
		LispVal test = tests.size() == 1 ? tests.get(0) : ClojureLowerUtil.cons(ClojureLowerUtil.sym("or"), tests);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), test, answer, refusal);
	}

	/**
	 * Whether one of the types has a public instance method of the name taking the count
	 * of arguments, which an instance call of the oracle's reflection would find. A type
	 * the lowering's classes do not hold has none.
	 * @param types the types' binary names
	 * @param method the method's name
	 * @param n the argument count
	 * @return whether one of them declares it
	 */
	static boolean declares(List<String> types, String method, int n) {
		for (String name : types) {
			ClojureJavaClasses.JavaClass type = ClojureHostClasses.find(name);
			if (type == null) {
				continue;
			}
			for (ClojureJavaClasses.Member candidate : type.methods()) {
				if (candidate.name().equals(method) && candidate.parameterCount() == n && !candidate.isStatic()) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The refusal after the arguments run: in the oracle's words when {@code known} (a
	 * row maps the method for another kind or arity, so this class lacks it), by name
	 * otherwise. The words are fixed here but for the receiver's class, which
	 * {@code %clojure-no-method} appends (or, for nil, signals the oracle's
	 * {@code NullPointerException}).
	 */
	static LispVal refusal(LispVal recv, String method, boolean known, List<LispVal> args) {
		int n = args.size();
		String words;
		if (!known) {
			words = "Method " + method + " taking " + n + " args is not supported for class ";
		}
		else if (n == 0) {
			words = "No matching field found: " + method + " for class ";
		}
		else {
			words = "No matching method " + method + " found taking " + n + " args for class ";
		}
		List<LispVal> body = new ArrayList<>();
		body.add(ClojureLowerUtil.sym("progn"));
		for (LispVal arg : args) {
			if (!(arg instanceof LispSymbol)) {
				body.add(arg);
			}
		}
		body.add(ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-NO-METHOD"), recv, LispString.literal(words),
				LispString.literal(method)));
		return body.size() == 2 ? body.get(1) : ClojureLowerUtil.list(body);
	}

}
