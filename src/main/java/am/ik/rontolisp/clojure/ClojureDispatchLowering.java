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
import java.util.function.Function;
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
 * Dispatch forms of the Clojure lowering: multimethods, hierarchies, protocols and
 * records.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureDispatchLowering {

	private ClojureDispatchLowering() {
	}

	/**
	 * {@code instance?}: whether the value is an instance of the named class, the
	 * oracle's {@code isInstance}. A record or deftype name tests the dispatch tag,
	 * {@code Object} every value but nil, a throwable class the class chain; any other
	 * class tests each kind of value whose oracle class is or implements it
	 * ({@link ClojureValueClasses}), a stream by its class, a condition by the throwable
	 * classes implementing it, and a host object by its host class
	 * ({@code %clojure-host-object-p}, an arm a program naming no {@code java:} operator
	 * sheds, {@link ClojureArms.Family#HOST}). A protocol's interface tests the records,
	 * deftypes and reifies whose body names the protocol. A class no value here has
	 * answers false; a name no class has is the oracle's unresolved symbol.
	 */
	static LispVal instanceOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "instance? takes a class and a value");
		if (!(items.get(1) instanceof LispSymbol cls)) {
			throw new LispReadException("instance? takes a class name, not " + items.get(1).print());
		}
		String name = cls.name();
		LispVal lowered = ctx.lower(items.get(2));
		ClojureLowering.TypeDef known = ctx.typeDefOf(name);
		if (known != null) {
			// a record or deftype name tests the dispatch tag, like a class
			ctx.usedProtocols = true;
			return ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("equal"),
					ClojureLowerUtil.list(new LispSymbol(ClojureProtocolLowering.PROTOCOL_TAG), lowered),
					ClojureProtocolLowering.typeTagForm(known.tagSpelling())));
		}
		if (name.startsWith(":") || !ClojureNamespaceLowering.isClasslike(ctx, name)) {
			throw new LispReadException("instance? takes a class name, not " + name);
		}
		String resolved = ClojureNamespaceLowering.resolveClass(ctx, name);
		Map.Entry<String, ClojureLowering.ProtocolDef> protocol = ClojureProtocolLowering.protocolOfInterface(ctx,
				resolved);
		if (protocol != null) {
			return ctx.booleanAnswer(ClojureProtocolLowering.implementsForm(ctx, protocol, lowered));
		}
		String lang = resolved.indexOf('.') < 0 ? ClojureValueClasses.clojureLang(resolved) : null;
		String fqn = lang != null ? lang : resolved;
		if (fqn.equals(ClojureClassBases.OBJECT)) {
			return ctx.booleanAnswer(ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), lowered)));
		}
		List<String> chain = ClojureThrowables.chainOf(fqn);
		if (chain != null) {
			return ctx.booleanAnswer(throwableInstance(ctx, chain, lowered));
		}
		String alias = HOST_ALIASES.get(fqn);
		if (alias != null) {
			// a core kind's test a program naming no java: operator calls plain
			// (ClojureArms.Family.HOST), so it lowers as before host objects counted
			return ctx.booleanAnswer(ClojureLowerUtil.list(new LispSymbol(alias), lowered));
		}
		List<ClojureValueClasses.Kind> kinds = ClojureValueClasses.kindsOf(fqn);
		if (kinds.equals(List.of(ClojureValueClasses.Kind.SYMBOL))) {
			return ctx.booleanAnswer(ClojureFnLowering.symbolRaw(ctx, lowered));
		}
		// every number kind is one numberp
		boolean numbers = kinds.containsAll(List.of(ClojureValueClasses.Kind.LONG, ClojureValueClasses.Kind.DOUBLE,
				ClojureValueClasses.Kind.RATIO));
		List<Arm> arms = new ArrayList<>();
		for (ClojureValueClasses.Kind kind : kinds) {
			if (numbers && kind == ClojureValueClasses.Kind.LONG) {
				arms.add(cl(Use.ONCE, "numberp"));
			}
			else if (!numbers || (kind != ClojureValueClasses.Kind.DOUBLE && kind != ClojureValueClasses.Kind.RATIO)) {
				arms.add(kindArm(ctx, kind));
			}
		}
		List<String> streams = ClojureValueClasses.streamClassesOf(fqn);
		if (!streams.isEmpty()) {
			arms.add(streamArm(streams));
		}
		for (String throwable : ClojureValueClasses.throwablesImplementing(fqn)) {
			List<String> throwableChain = ClojureThrowables.chainOf(throwable);
			if (throwableChain != null) {
				arms.add(new Arm(Use.ONCE, v -> throwableInstance(ctx, throwableChain, v)));
			}
		}
		boolean loads = ClojureValueClasses.loads(fqn);
		if (ClojureValueClasses.hostMayHold(fqn, loads)) {
			arms.add(new Arm(Use.VARIABLE,
					v -> ClojureLowerUtil.list(new LispSymbol(HOST_OBJECT_P), v, LispString.literal(fqn))));
		}
		if (arms.isEmpty()) {
			if (!loads) {
				throw new LispReadException("unknown name: " + name);
			}
			// a class no value here has (Integer: an int is a Long)
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), lowered, ctx.falseVariable);
		}
		return armsAnswer(ctx, arms, lowered);
	}

	/**
	 * The classes a core kind's value is or implements that a host object may be too, to
	 * the test answering both, which a program naming no {@code java:} operator calls as
	 * the kind's own test ({@link ClojureArms.Family#HOST}'s aliases).
	 */
	private static final Map<String, String> HOST_ALIASES = Map.of("java.lang.Number",
			"RONTOLISP::%CLOJURE-HOST-NUMBER-P", "java.lang.CharSequence", "RONTOLISP::%CLOJURE-HOST-CHAR-SEQUENCE-P");

	/** {@code instance?}'s host arm ({@code clojure.lisp}). */
	static final String HOST_OBJECT_P = "RONTOLISP::%CLOJURE-HOST-OBJECT-P";

	/**
	 * How an arm reads the value: once, several times (a variable or a constant then), or
	 * as a family arm test, whose argument the strip requires to be a variable or a
	 * literal.
	 */
	private enum Use {

		ONCE, MANY, VARIABLE

	}

	/** One test of {@code instance?} over the value. */
	private record Arm(Use use, Function<LispVal, LispVal> test) {

	}

	/**
	 * The arms' disjunction as the {@code T}-or-false answer, the value bound to a
	 * variable unless every arm may read it as it is: a variable always, a constant where
	 * no family arm reads it, any form where one arm reads it once.
	 */
	private static LispVal armsAnswer(ClojureLowering ctx, List<Arm> arms, LispVal lowered) {
		boolean constant = !(lowered instanceof LispCons) || lowered instanceof LispCons quote
				&& quote.car() instanceof LispSymbol head && head.name().equals("QUOTE");
		boolean variableArm = arms.stream().anyMatch(arm -> arm.use() == Use.VARIABLE);
		boolean once = arms.size() == 1 && arms.get(0).use() == Use.ONCE;
		boolean bind = !(lowered instanceof LispSymbol) && (variableArm || !(once || constant));
		LispVal value = bind ? ctx.freshTemp() : lowered;
		List<LispVal> tests = new ArrayList<>();
		for (Arm arm : arms) {
			tests.add(arm.test().apply(value));
		}
		LispVal answer = ctx
			.booleanAnswer(tests.size() == 1 ? tests.get(0) : ClojureLowerUtil.cons(ClojureLowerUtil.sym("or"), tests));
		if (!bind) {
			return answer;
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(value, lowered))), answer);
	}

	/** The test of one kind of value over the value. */
	private static Arm kindArm(ClojureLowering ctx, ClojureValueClasses.Kind kind) {
		return switch (kind) {
			case STRING -> cl(Use.ONCE, "stringp");
			case CHAR -> cl(Use.ONCE, "characterp");
			case BOOLEAN -> new Arm(Use.MANY,
					v -> ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), v, ClojureLowering.TRUE_CONST),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), v, ctx.falseVariable)));
			case LONG -> cl(Use.ONCE, "integerp");
			case DOUBLE -> cl(Use.ONCE, "floatp");
			case RATIO -> runtime(Use.ONCE, "RONTOLISP::%CLOJURE-IS-RATIO");
			case KEYWORD -> new Arm(Use.MANY, ClojureFilterLowering::keywordTest);
			case SYMBOL -> new Arm(Use.MANY, v -> ClojureFnLowering.symbolTest(ctx, v));
			case VECTOR -> runtime(Use.ONCE, "RONTOLISP::%CLOJURE-IS-VECTOR");
			case MAP_ENTRY -> runtime(Use.ONCE, "RONTOLISP::%CLOJURE-ENTRY-P");
			case LIST -> runtime(Use.ONCE, "RONTOLISP::%CLOJURE-IS-LIST");
			case LAZY_SEQ -> runtime(Use.ONCE, "RONTOLISP::%CLOJURE-LAZY-P");
			case MAP -> cl(Use.ONCE, "hash-table-p");
			case SORTED_MAP -> new Arm(Use.VARIABLE, ClojureSortedLowering::sortedMapTest);
			case SET -> new Arm(Use.MANY, ClojureCollectionLowering::isSetForm);
			case SORTED_SET -> new Arm(Use.VARIABLE, ClojureSortedLowering::sortedSetTest);
			case FUNCTION -> cl(Use.ONCE, "functionp");
			case ATOM -> new Arm(Use.MANY,
					v -> ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), ClojureStateLowering.isAtomForm(v),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
									ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-IS-VOLATILE"), v))));
			case VOLATILE -> runtime(Use.ONCE, "RONTOLISP::%CLOJURE-IS-VOLATILE");
			case VAR -> runtime(Use.ONCE, "RONTOLISP::%CLOJURE-VAR-P");
			case PATTERN -> new Arm(Use.ONCE, ClojureStringLowering::isPatternForm);
			case MATCHER -> new Arm(Use.ONCE, ClojureStringLowering::isMatcherForm);
			case NAMESPACE -> runtime(Use.VARIABLE, "RONTOLISP::%CLOJURE-NS-OBJECT-P");
			case READER_CONDITIONAL -> runtime(Use.VARIABLE, ClojurePredicateLowering.READER_COND_P);
			case TAGGED_LITERAL -> runtime(Use.VARIABLE, ClojurePredicateLowering.TAGGED_LITERAL_P);
			case RECORD -> new Arm(Use.MANY, ClojureProtocolLowering::isRecordForm);
			case DEFTYPE -> new Arm(Use.MANY, ClojureProtocolLowering::isDeftypeForm);
			case REIFY -> new Arm(Use.MANY, ClojureProtocolLowering::isReifyForm);
		};
	}

	private static Arm cl(Use use, String predicate) {
		return new Arm(use, v -> ClojureLowerUtil.list(ClojureLowerUtil.sym(predicate), v));
	}

	private static Arm runtime(Use use, String function) {
		return new Arm(use, v -> ClojureLowerUtil.list(new LispSymbol(function), v));
	}

	/**
	 * A stream whose class is one of the classes: any stream when they are every class
	 * {@code %clojure-stream-class} answers, else the stream's class among them. A family
	 * arm a program making no stream sheds ({@link ClojureArms.Family#STREAM}).
	 */
	private static Arm streamArm(List<String> classes) {
		if (classes.size() == ClojureValueClasses.STREAM_CLASSES.size()) {
			return runtime(Use.VARIABLE, ClojureInteropLowering.STREAM_P);
		}
		return new Arm(Use.VARIABLE,
				v -> ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(new LispSymbol(ClojureInteropLowering.STREAM_P), v),
						ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CHAIN-HAS"),
								ClojureThrowables.quoted(classes),
								ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-STREAM-CLASS"), v))));
	}

	/**
	 * {@code instance?} of a throwable class: whether the value is an exception or a
	 * runtime error whose class is the class or a subclass of it (the class {@code class}
	 * answers), or a host {@code Throwable} of it.
	 */
	private static LispVal throwableInstance(ClojureLowering ctx, List<String> chain, LispVal lowered) {
		ctx.readsExceptionParts = true;
		ctx.recordChain(chain);
		return ClojureLowerUtil.list(new LispSymbol(INSTANCE_OF), lowered, ClojureThrowables.quoted(chain));
	}

	/** The arm test of an exception or a runtime error ({@code clojure.lisp}). */
	static final String EXCEPTION_P = "RONTOLISP::%CLOJURE-EXCEPTION-P";

	/** The class of an exception or a runtime error, as a keyword. */
	static final String EXCEPTION_CLASS = "RONTOLISP::%CLOJURE-EXCEPTION-CLASS";

	/** {@code instance?} of a throwable class: the value and the class's chain. */
	static final String INSTANCE_OF = "RONTOLISP::%CLOJURE-INSTANCE-OF";

	/**
	 * {@code class}: the value's kind as a keyword. The oracle answers host classes,
	 * which no wasm backend has -- the keyword names the kind instead, on every backend
	 * alike. A value of no Clojure kind answers its host class when it is a host object
	 * (interpreter and JVM), else the refusal. Inside a {@code defmulti} dispatch
	 * function (see {@link #defmultiForms(List)}) a nil answers nil itself instead, so
	 * the dispatcher's null test maps it onto the nil method's marker while an explicit
	 * {@code :nil} keyword keeps its row, like the oracle.
	 */
	static LispVal classForm(ClojureLowering ctx, LispVal lowered) {
		LispSymbol one = ctx.freshTemp();
		List<LispVal> branches = new ArrayList<>();
		// a record or deftype answers its tag keyword (the oracle answers a host
		// class, which no wasm backend has); a reify answers a constant keyword
		branches.add(ClojureLowerUtil.list(ClojureProtocolLowering.isRecordForm(one),
				ClojureProtocolLowering.typedTagOf(one)));
		branches.add(ClojureLowerUtil.list(ClojureProtocolLowering.isDeftypeForm(one),
				ClojureProtocolLowering.typedTagOf(one)));
		branches.add(ClojureLowerUtil.list(ClojureProtocolLowering.isReifyForm(one),
				ClojureCollectionLowering.keywordForm("reify")));
		// a stream answers the host class its printer names, as a keyword like every
		// kind: an arm a program making no stream sheds (ClojureArms.Family.STREAM)
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(ClojureInteropLowering.STREAM_P), one),
				streamClassKeyword(one)));
		// a namespace answers its class's keyword: an arm a program making no namespace
		// sheds (ClojureArms.Family.NAMESPACE)
		branches
			.add(ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-NS-OBJECT-P"), one),
					ClojureCollectionLowering.keywordForm("clojure.lang.Namespace")));
		// a reader conditional or tagged literal answers its class's keyword: an arm a
		// program making neither sheds (ClojureArms.Family.READER_VALUE)
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(new LispSymbol(ClojurePredicateLowering.READER_VALUE_P), one),
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-READER-VALUE-CLASS"), one)));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), one),
				ctx.inDispatchFn ? ClojureLowering.NIL_CONST : ClojureCollectionLowering.keywordForm("nil")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), one, ctx.falseVariable),
				ClojureCollectionLowering.keywordForm("boolean")));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), one, ClojureLowering.TRUE_CONST),
				ClojureCollectionLowering.keywordForm("boolean")));
		branches.add(ClojureLowerUtil.list(ClojureFilterLowering.keywordTest(one),
				ClojureCollectionLowering.keywordForm("keyword")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("symbolp"), one),
				ClojureCollectionLowering.keywordForm("symbol")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), one),
				ClojureCollectionLowering.keywordForm("char")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), one),
				ClojureCollectionLowering.keywordForm("string")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), one),
				ClojureCollectionLowering.keywordForm("number")));
		branches.add(ClojureLowerUtil.list(ClojureCollectionLowering.isSetForm(one),
				ClojureCollectionLowering.keywordForm("set")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), one),
				ClojureCollectionLowering.keywordForm("map")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), one),
				ClojureCollectionLowering.keywordForm("vector")));
		branches.add(ClojureLowerUtil.list(ClojureStringLowering.isPatternForm(one),
				ClojureCollectionLowering.keywordForm("pattern")));
		branches.add(ClojureLowerUtil.list(ClojureStringLowering.isMatcherForm(one),
				ClojureCollectionLowering.keywordForm("matcher")));
		// a sorted map or set is a map or set to class (its wrapper is a cons): an arm a
		// program building no sorted collection sheds
		branches.add(ClojureLowerUtil.list(ClojureSortedLowering.sortedMapTest(one),
				ClojureCollectionLowering.keywordForm("map")));
		branches.add(ClojureLowerUtil.list(ClojureSortedLowering.sortedSetTest(one),
				ClojureCollectionLowering.keywordForm("set")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), one),
				ClojureCollectionLowering.keywordForm("list")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("functionp"), one),
				ClojureCollectionLowering.keywordForm("function")));
		branches.add(ClojureLowerUtil.list(ClojureStateLowering.isAtomForm(one),
				ClojureCollectionLowering.keywordForm("atom")));
		// an exception or a runtime error answers its class: an arm a program that can
		// hold none sheds (ClojureArms.Family.EXCEPTION)
		ctx.readsConditionClass = true;
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(new LispSymbol(EXCEPTION_P), one),
				ClojureLowerUtil.list(new LispSymbol(EXCEPTION_CLASS), one)));
		// anything else: a host object's class on the interpreter and the JVM, else the
		// refusal (clojure.lisp; a program without java: gets the refusal alone)
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST,
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-HOST-CLASS"), one)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(one, lowered))),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches));
	}

	/** The keyword of the host class a stream's printer names ({@code clojure.lisp}). */
	static LispVal streamClassKeyword(LispVal stream) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureCollectionLowering.KEYWORD_TAG,
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-STREAM-CLASS"), stream));
	}

	/**
	 * {@code .getClass} over the call any other receiver takes: an exception or a runtime
	 * error, and a stream, answer the keyword {@code class} answers -- arms a program
	 * that can hold none of the kind sheds ({@link ClojureArms.Family#EXCEPTION},
	 * {@link ClojureArms.Family#STREAM}), so the exception reader travels only where a
	 * condition can reach the call. A receiver of a known class takes only its own kind's
	 * arm.
	 * @param ctx the hub
	 * @param recv the bound receiver
	 * @param knownClass the receiver's known host class, or null
	 * @param call the call for any other receiver
	 * @return the form
	 */
	static LispVal getClassForm(ClojureLowering ctx, LispSymbol recv, @Nullable String knownClass, LispVal call) {
		LispVal out = call;
		if (knownClass == null || ClojureClassBases.isStreamClass(knownClass)) {
			out = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(new LispSymbol(ClojureInteropLowering.STREAM_P), recv),
					streamClassKeyword(recv), out);
		}
		if (knownClass == null || ClojureThrowables.chainOf(knownClass) != null) {
			ctx.readsConditionClass = true;
			out = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(new LispSymbol(EXCEPTION_P), recv),
					ClojureLowerUtil.list(new LispSymbol(EXCEPTION_CLASS), recv), out);
		}
		return out;
	}

	/** {@code class} as a value: a one-argument lambda over the same read. */
	static LispVal classValue(ClojureLowering ctx) {
		LispSymbol one = new LispSymbol(ClojureLowering.mangle("class-one"));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(one), classForm(ctx, one));
	}

	// IO entry points: spit/slurp/line-seq over the eval IO layer (plus the
	// clojure.java.io/reader constructor and the line-seq reader arity)

	/**
	 * The host spellings a {@code defmethod} dispatch value may name, to the
	 * {@code class}-keyword the dispatcher actually produces for them. The table mirrors
	 * {@code class} (and {@code extend-protocol} targets): every numeric spelling merges
	 * into {@code :number} (the oracle tells {@code Long} from {@code Double}, which no
	 * wasm backend has -- the documented merge), every collection spelling into its kind.
	 */
	static final Map<String, String> DISPATCH_CLASS_KEYWORDS = Map.ofEntries(Map.entry("String", "string"),
			Map.entry("CharSequence", "string"), Map.entry("Number", "number"), Map.entry("Long", "number"),
			Map.entry("Double", "number"), Map.entry("Integer", "number"), Map.entry("Float", "number"),
			Map.entry("Short", "number"), Map.entry("Byte", "number"), Map.entry("Boolean", "boolean"),
			Map.entry("Keyword", "keyword"), Map.entry("Symbol", "symbol"), Map.entry("Character", "char"),
			Map.entry("Char", "char"), Map.entry("Map", "map"), Map.entry("IPersistentMap", "map"),
			Map.entry("Vector", "vector"), Map.entry("IPersistentVector", "vector"), Map.entry("Set", "set"),
			Map.entry("IPersistentSet", "set"), Map.entry("List", "list"), Map.entry("Seq", "list"),
			Map.entry("Sequential", "list"), Map.entry("Collection", "list"), Map.entry("IPersistentList", "list"),
			Map.entry("IPersistentCollection", "list"), Map.entry("Fn", "function"), Map.entry("IFn", "function"),
			Map.entry("Function", "function"), Map.entry("Atom", "atom"));

	/**
	 * Whether the dispatch datum is the nil spelling: the symbol the reader answers for
	 * {@code nil}, or the empty list (which is nil too). A nil method stores under the
	 * {@code (:C%NIL)} marker (see {@link #nilMarkerForm}), so the corpus's both shapes
	 * ({@code my-print} over {@code class}, {@code my-class} over {@code identity}) share
	 * the one definition while a literal {@code :nil} keyword keeps its own row.
	 */
	static boolean isNilDatum(LispVal datum) {
		return datum instanceof LispNil || (datum instanceof LispSymbol s && s.name().equals("nil"));
	}

	/**
	 * Whether the name spells {@code Object}: dotted, imported, or {@code java.lang}, a
	 * record or deftype of that name aside (which keeps its tag). An {@code Object}
	 * method matches every dispatch value, like the oracle's, so it lives in the
	 * multimethod's {@code %object} global beside its table row: the dispatcher tries it
	 * past the hierarchy search but ahead of the default, which it always beats.
	 */
	static boolean isObjectClassName(ClojureLowering ctx, String name) {
		return ctx.typeKeyOf(name) == null
				&& ClojureNamespaceLowering.resolveClass(ctx, name).equals("java.lang.Object");
	}

	/**
	 * A class spelling to the keyword the {@code class} dispatcher produces for it, or
	 * null when the name is no class spelling at all (so the caller lowers it as usual --
	 * a var holding the dispatch value, like the oracle's evaluated position). Record and
	 * deftype names answer their tags; dotted, imported and {@code java.lang} spellings
	 * resolve through {@link #resolveClass} first, so {@code java.util.Map} and
	 * {@code clojure.lang.IPersistentVector} map like their simple names, and a throwable
	 * or stream class answers its own name ({@link #classKey}). Any other class is null
	 * too: it lowers to its class object, which {@code class} answers for a host object
	 * of it and the hierarchy walks through Java inheritance, like {@link #hierarchyArg}.
	 */
	static @Nullable LispVal dispatchClassKey(ClojureLowering ctx, String name) {
		if (ctx.typeDefOf(name) == null && isObjectClassName(ctx, name)) {
			return ClojureCollectionLowering.keywordForm("object");
		}
		if (!ClojureNamespaceLowering.isClasslike(ctx, name) && ctx.typeDefOf(name) == null) {
			return null;
		}
		return classKey(ctx, name);
	}

	/**
	 * A class spelling to the keyword {@code class} answers for its values, or null when
	 * the spelling maps to none: a record or deftype name its tag, a core class its kind,
	 * a throwable or stream class its own name -- the keyword {@code class} answers for
	 * an exception or a stream -- with its chain recorded ({@link #chainedClassKey}).
	 */
	static @Nullable LispVal classKey(ClojureLowering ctx, String name) {
		ClojureLowering.TypeDef type = ctx.typeDefOf(name);
		if (type != null) {
			return ClojureProtocolLowering.typeTagForm(type.tagSpelling());
		}
		String fqn = ClojureNamespaceLowering.resolveClass(ctx, name);
		String kind = DISPATCH_CLASS_KEYWORDS.get(fqn.substring(fqn.lastIndexOf('.') + 1));
		if (kind != null) {
			return ClojureCollectionLowering.keywordForm(kind);
		}
		return chainedClassKey(ctx, fqn);
	}

	/**
	 * A class name to its keyword when a value's class keyword may walk to it -- a
	 * throwable, a stream class, an interface among their supers, {@code Object}
	 * ({@link ClojureClassBases#isChained}) -- recording its row and those of the classes
	 * a value may have without the program naming them
	 * ({@link ClojureLowering#recordSpelledClass}); null for any other class.
	 */
	static @Nullable LispVal chainedClassKey(ClojureLowering ctx, String fqn) {
		if (!ClojureClassBases.isChained(fqn)) {
			return null;
		}
		ctx.recordSpelledClass(fqn);
		return ClojureCollectionLowering.keywordForm(fqn);
	}

	/**
	 * An argument of {@code isa?}, {@code derive}, {@code underive}, {@code parents},
	 * {@code ancestors} or {@code descendants}: a class spelling (no local or var of that
	 * name) is the keyword {@code class} answers for its values ({@link #classKey}), like
	 * a dispatch value, and {@code Object} the keyword every class keyword walks to; the
	 * hierarchy then reads the class rows. Anything else lowers as usual, a class that
	 * maps to no keyword included.
	 */
	static LispVal hierarchyArg(ClojureLowering ctx, LispVal datum) {
		if (datum instanceof LispSymbol s && !s.name().startsWith(":") && !ctx.isLocal(s.name())
				&& ctx.lookupVar(s.name()) == null
				&& (ClojureNamespaceLowering.isClasslike(ctx, s.name()) || ctx.typeDefOf(s.name()) != null)) {
			LispVal key = isObjectClassName(ctx, s.name()) ? chainedClassKey(ctx, ClojureClassBases.OBJECT)
					: classKey(ctx, s.name());
			if (key != null) {
				ctx.usedClassChains = true;
				return key;
			}
		}
		return ctx.lower(datum);
	}

	/**
	 * A {@code defmethod} (or {@code remove-method}, {@code get-method},
	 * {@code prefer-method}) dispatch value lowered to its table key: {@code nil} onto
	 * the {@code (:C%NIL)} marker (the dispatcher maps a true nil onto it, so no table
	 * ever keys on nil, and a literal {@code :nil} keyword keeps its keyword row), class
	 * spellings onto the keyword the {@code class} dispatcher produces,
	 * {@code ::}-keywords resolved like anywhere else, and literal vectors element by
	 * element (the corpus's {@code [Number]} and {@code [Map Number]} pairs, whose
	 * element-wise derivation the hierarchy search already runs). Anything else lowers as
	 * usual, so plain keywords and values keep their exact shapes.
	 */
	static LispVal dispatchKeyForm(ClojureLowering ctx, LispVal datum) {
		if (isNilDatum(datum)) {
			return ClojureCollectionLowering.nilMarkerForm();
		}
		return dispatchElementForm(ctx, datum);
	}

	/**
	 * A literal vector's element lowered: the table-key shape except that a nil element
	 * stays the {@code :nil} keyword, exactly as before -- a class-mapped dispatch vector
	 * spells its nil element the same way, so a {@code [nil]} row keeps answering it,
	 * while a runtime vector holding a true nil still misses it, like before. Nested
	 * vectors recurse here, never onto the marker.
	 */
	static LispVal dispatchElementForm(ClojureLowering ctx, LispVal datum) {
		if (isNilDatum(datum)) {
			return ClojureCollectionLowering.keywordForm("nil");
		}
		if (datum instanceof LispSymbol s) {
			String name = s.name();
			if (name.startsWith(":")) {
				return ClojureCollectionLowering
					.keywordForm(ClojureCollectionLowering.resolveKeywordSpelling(ctx, name));
			}
			LispVal cls = dispatchClassKey(ctx, name);
			if (cls != null) {
				return cls;
			}
			return ctx.lower(datum);
		}
		List<LispVal> parts = ClojureLowerUtil.items(datum);
		if (parts != null && !parts.isEmpty() && parts.get(0) == ClojureReader.VECTOR) {
			List<LispVal> out = new ArrayList<>();
			out.add(ClojureLowerUtil.sym("vector"));
			for (int i = 1; i < parts.size(); i++) {
				out.add(dispatchElementForm(ctx, parts.get(i)));
			}
			return ClojureLowerUtil.list(out);
		}
		return ctx.lower(datum);
	}

	/**
	 * Whether the datum holds a {@code class} call: any list headed by the symbol
	 * {@code class}. Quoted data is skipped (a call there never evaluates); anything else
	 * over-approximates, which is harmless -- a definition whose only {@code class}
	 * spellings never evaluate re-lowers identically, so recording it changes nothing.
	 */
	static boolean containsClassCall(LispVal datum) {
		List<LispVal> parts = ClojureLowerUtil.items(datum);
		if (parts == null) {
			return false;
		}
		if (!parts.isEmpty() && ClojureLowerUtil.isSymbolNamed(parts.get(0), "quote")) {
			return false;
		}
		for (LispVal part : parts) {
			if (part instanceof LispSymbol s && s.name().equals("class")) {
				return true;
			}
			if (part instanceof LispCons && containsClassCall(part)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Records a definition's dispatch-function datum for a later {@code defmulti} over
	 * its name: a {@code defn}'s rebuilt {@code (fn* ...)} datum, or a {@code def}'s
	 * {@code (fn ...)} value datum (a name aliasing an already-recorded one shares its
	 * datum). Only a class-calling definition is kept -- anything else (including a
	 * value-less {@code def} and a {@code ^:dynamic} name, whose calls route through the
	 * value cell) drops the name, so a redefinition without one unregisters it.
	 * @param name the defined var's key
	 * @param dynamic whether the name is {@code ^:dynamic}
	 * @param valueDatum the {@code fn} datum, or null when there is none
	 */
	static void recordClassDispatchFn(ClojureLowering ctx, String name, boolean dynamic, @Nullable LispVal valueDatum) {
		if (dynamic || valueDatum == null) {
			ctx.classDispatchFns.remove(name);
			return;
		}
		if (valueDatum instanceof LispSymbol s && !s.name().startsWith(":")) {
			LispVal target = recordedDispatchFn(ctx, s.name());
			if (target != null) {
				ctx.classDispatchFns.put(name, target);
			}
			else {
				ctx.classDispatchFns.remove(name);
			}
			return;
		}
		List<LispVal> parts = ClojureLowerUtil.items(valueDatum);
		if (parts != null && !parts.isEmpty() && isFnHead(ctx, parts.get(0)) && containsClassCall(valueDatum)) {
			ctx.classDispatchFns.put(name, valueDatum);
		}
		else {
			ctx.classDispatchFns.remove(name);
		}
	}

	/**
	 * Whether a head spells a function form: {@code fn*}, or {@code fn} unless a program
	 * macro of that name owns it (the call then expands to whatever the macro answers).
	 */
	private static boolean isFnHead(ClojureLowering ctx, LispVal head) {
		return ClojureLowerUtil.isSymbolNamed(head, "fn*")
				|| ClojureLowerUtil.isSymbolNamed(head, "fn") && ClojureMacroLowering.macroKey(ctx, "fn") == null;
	}

	/**
	 * The dispatch datum a {@code defmulti} lowers: the recorded definition when the
	 * datum names a class-calling {@code defn} or {@code def}'d function, so its
	 * {@code class} calls answer nil itself for nil under the dispatch lowering, like the
	 * inline datum does. A name a local shadows keeps its reference (the local is the
	 * dispatch function, not the definition).
	 */
	static LispVal dispatchDatumFor(ClojureLowering ctx, LispVal dispatchDatum) {
		if (dispatchDatum instanceof LispSymbol s) {
			LispVal recorded = recordedDispatchFn(ctx, s.name());
			if (recorded != null) {
				for (Map<String, ClojureLowering.Kind> scope : ctx.scopes) {
					if (scope.containsKey(s.name())) {
						return dispatchDatum;
					}
				}
				return recorded;
			}
		}
		return dispatchDatum;
	}

	/**
	 * The recorded dispatch datum of the definition a name resolves to -- only one of the
	 * current namespace, since the datum re-lowers here and its names resolve here -- or
	 * null.
	 */
	static @Nullable LispVal recordedDispatchFn(ClojureLowering ctx, String name) {
		String key = ctx.lookupVar(name);
		if (key == null || !key.startsWith(ctx.currentNs + "/")) {
			return null;
		}
		return ctx.classDispatchFns.get(key);
	}

	/**
	 * A call inside a {@code defmulti} dispatch function to a recorded class-calling
	 * definition, inlined from its recorded {@code (fn ...)} datum: the definition's own
	 * lowering answers the {@code :nil} keyword for nil outside the dispatch lowering, so
	 * a direct call there would miss the nil method -- re-lowering the recorded datum
	 * applied to the call's argument datums answers nil itself instead, like the inline
	 * datum and the oracle. A name a local shadows keeps its call (the local is the
	 * function, not the definition), and a name already being inlined keeps its direct
	 * call, so a (mutually) recursive definition still terminates the lowering.
	 * @param name the called name
	 * @param items the call datum, head included
	 * @return the inlined form, or null when the name is no unshadowed recorded
	 * definition
	 */
	static @Nullable LispVal inlineDispatchCall(ClojureLowering ctx, String name, List<LispVal> items) {
		LispVal recorded = recordedDispatchFn(ctx, name);
		String key = ctx.lookupVar(name);
		if (recorded == null || key == null || !ctx.inliningDispatch.add(key)) {
			return null;
		}
		try {
			for (Map<String, ClojureLowering.Kind> scope : ctx.scopes) {
				if (scope.containsKey(name)) {
					return null;
				}
			}
			List<LispVal> synthetic = new ArrayList<>();
			synthetic.add(recorded);
			synthetic.addAll(items.subList(1, items.size()));
			return ctx.lowerInner(ClojureLowerUtil.list(synthetic));
		}
		finally {
			ctx.inliningDispatch.remove(key);
		}
	}

	/**
	 * {@code (defmulti name doc? dispatch-fn & opts)}: a method table, a default dispatch
	 * value and an {@code Object}-method slot in four globals no identifier can spell
	 * (the suffix follows the mangled name, like the multi-arity helpers), plus a
	 * dispatcher {@code defun} applying each call's dispatch value to the table. The
	 * dispatcher maps a true nil onto the {@code (:C%NIL)} marker first (no table ever
	 * keys on nil, on any backend), so a {@code nil} method answers both the
	 * {@code class} nil and the {@code identity} nil while a dispatch value that
	 * literally is the {@code :nil} keyword keeps its keyword row, like the oracle. A
	 * {@code class} call inside the dispatch function answers nil itself for a nil
	 * argument (see {@link #classForm(LispVal)}), so the null test maps it onto the
	 * marker too -- bare, wrapped in another function, or through a named {@code defn} or
	 * {@code def}'d function (re-lowered from the recorded definition, like the oracle).
	 * A call to one of these names nested inside an inline dispatch datum inlines the
	 * recorded datum at the call site instead (see
	 * {@link #inlineDispatchCall(String, List)}), so it answers nil itself too. The
	 * default dispatch value is {@code :default} without a {@code :default} option (an
	 * arbitrary keyword with one -- the corpus's {@code :everything-else} -- stored
	 * per-multimethod like {@code :default} today); a miss with no method for the default
	 * signals, like the oracle. With a {@code :hierarchy} option the dispatcher consults
	 * that hierarchy value on a miss (the global one without the option): every method
	 * whose key the dispatch value descends from ({@code isa?}) is a candidate, the
	 * strictly most specific wins, {@code prefer-method} breaks the remaining ties, and
	 * an unbroken tie signals -- like the oracle. Past the search but ahead of the
	 * default, a defined {@code Object} method catches the rest, like the oracle's (which
	 * it always beats); without one the slot is nil and the search decides alone.
	 */
	static List<LispVal> defmultiForms(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "defmulti takes a name, a dispatch function and options");
		String name = ClojureLowerUtil.plainName(items.get(1), "defmulti");
		int at = 2;
		if (items.get(at) instanceof LispString) {
			at++; // the docstring
		}
		List<LispVal> attr = ClojureLowerUtil.items(items.get(at));
		if (attr != null && !attr.isEmpty() && ClojureLowerUtil.isSymbolNamed(attr.get(0), "%hash-map")) {
			at++; // the attr map
		}
		ClojureLowerUtil.isTrue(at < items.size(), "defmulti takes a name, a dispatch function and options");
		LispVal dispatchDatum = items.get(at++);
		LispVal defaultDatum = null;
		LispVal hierarchyDatum = null;
		for (; at < items.size(); at += 2) {
			if (at + 1 >= items.size() || !(items.get(at) instanceof LispSymbol opt)) {
				throw new LispReadException("defmulti takes option/value pairs");
			}
			switch (opt.name()) {
				case ":default" -> defaultDatum = items.get(at + 1);
				case ":hierarchy" -> hierarchyDatum = items.get(at + 1);
				default -> throw new LispReadException("defmulti option " + opt.name() + " is not supported yet");
			}
		}
		if (ctx.multimethods.contains(ClojureLowering.varKey(ctx.currentNs, name))) {
			// the var already holds a multimethod: the oracle keeps it, methods,
			// dispatch function and default alike
			return List.of(ClojureLowering.NIL_CONST);
		}
		String key = ctx.intern(name, ClojureLowerUtil.nameIsPrivate(items.get(1)));
		ctx.multimethods.add(key);
		ctx.globals.put(key, ClojureLowering.Kind.FUNCTION);
		ctx.macros.remove(key); // a definition wins over the macro it shadows
		LispSymbol fn = ClojureLowering.varSym(key);
		LispSymbol methods = tableGlobal(key, "%methods");
		LispSymbol fallback = tableGlobal(key, "%default");
		LispSymbol prefers = tableGlobal(key, "%prefers");
		LispSymbol object = tableGlobal(key, "%object");
		LispVal dispatchFn;
		boolean wasDispatch = ctx.inDispatchFn;
		ctx.inDispatchFn = true;
		try {
			dispatchFn = ClojureBindingLowering.fnValue(ctx, dispatchDatumFor(ctx, dispatchDatum));
		}
		finally {
			ctx.inDispatchFn = wasDispatch;
		}
		LispVal defaultForm = defaultDatum == null ? ClojureCollectionLowering.keywordForm("default")
				: ctx.lower(defaultDatum);
		LispVal hierarchyForm = hierarchyDatum == null ? ClojureHierarchyLowering.hierarchyGlobal()
				: ctx.lower(hierarchyDatum);
		LispSymbol args = ctx.freshTemp();
		LispSymbol disp = ctx.freshTemp();
		LispSymbol raw = ctx.freshTemp();
		LispSymbol found = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispVal missCall = ClojureLowerUtil.list(new LispSymbol(ClojureHierarchyLowering.HIERARCHY_DISPATCH),
				LispString.literal(name), methods, prefers, hierarchyForm, fallback, disp, args);
		LispVal missForm = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), object,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"),
								ClojureLowerUtil.list(new LispSymbol("C%H-CANDIDATES"), methods, hierarchyForm, disp))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), object, args), missCall);
		// A `class` call inside the dispatch function answers nil itself for a nil
		// argument (see classForm), so the one null test maps every class-produced
		// nil onto the marker while an explicit `:nil` keyword keeps its keyword row,
		// like the oracle; a shadowed `class` is the caller's own function.
		LispVal nilTest = ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), raw);
		LispVal dispatch = ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(
						ClojureLowerUtil.list(raw,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"),
										ClojureLowering.realFun(dispatchFn), args)),
						ClojureLowerUtil.list(disp,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), nilTest,
										ClojureCollectionLowering.nilMarkerForm(), raw)),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)),
						ClojureLowerUtil.list(found,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
										ClojureCollectionLowering.lookupKey(disp, methods), methods, miss)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), found, miss), missForm,
						ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), found, args)));
		List<LispVal> forms = new ArrayList<>();
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), methods, ClojureCollectionLowering.makeTable()));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), fallback, defaultForm));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), prefers, ClojureCollectionLowering.makeTable()));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), object, ClojureLowering.NIL_CONST));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), fn,
				ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, args)), dispatch));
		ctx.usedHierarchy = true;
		return forms;
	}

	/**
	 * {@code (defmethod name dispatch-value [params...] body...)}: the lambda over the
	 * (possibly destructured) parameters stored in the method table. The multimethod must
	 * be defined -- forward order aside, the pre-scan declares every {@code defmulti}
	 * first.
	 */
	/**
	 * One single-arity method lambda, {@code labels}-wrapped under a fresh name when a
	 * {@code recur} reaches its body (like an anonymous {@code fn}): a stored method has
	 * no callable name of its own, so without a {@code recur} it stays a bare lambda,
	 * exactly as before. A used variadic target splits into a worker plus its
	 * {@code &rest} head, like every other {@code fn} shape (decided 2026-10-01).
	 */
	static LispVal methodLambda(ClojureLowering ctx, LispVal params, List<LispVal> bodyForms) {
		String fresh = ctx.freshRecurName();
		String worker = ClojureBindingLowering.workerName(fresh);
		ClojureLowering.RecurTarget target = new ClojureLowering.RecurTarget(
				ClojureBindingLowering.isVariadicParams(params) ? worker : fresh, true);
		ClojureLowering.Clause clause = ClojureBindingLowering.clause(ctx, params, bodyForms, target);
		if (target.used() && clause.variadic()) {
			return ClojureBindingLowering.splitMethodLambda(ctx, fresh, worker, clause, clause.wrapped());
		}
		LispVal lambda = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(clause.params()),
				clause.wrapped());
		if (!target.used()) {
			return lambda;
		}
		return ClojureLowering.labelsSelfCall(fresh, lambda);
	}

	/**
	 * The var key of the multimethod a {@code defmethod} (or {@code remove-method},
	 * {@code get-method}, {@code prefer-method}) names: the current namespace's own, a
	 * referred one, or one reached through an alias or its namespace's full name.
	 */
	static String multimethodKey(ClojureLowering ctx, String name) {
		String key = ctx.isLocal(name) ? null : ctx.resolveVar(name);
		if (key == null) {
			throw new LispReadException("No such multimethod: " + name);
		}
		return key;
	}

	/**
	 * One of a multimethod's globals: its var's symbol plus a suffix no identifier can
	 * spell ({@code %methods}, {@code %default}, {@code %prefers}, {@code %object}).
	 */
	static LispSymbol tableGlobal(String key, String suffix) {
		return new LispSymbol(ClojureLowering.varSym(key).name() + suffix);
	}

	static LispVal defmethodForm(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 4,
				"defmethod takes a name, a dispatch value, a parameter vector and a body");
		String name = ClojureLowerUtil.plainName(items.get(1), "defmethod");
		String key = multimethodKey(ctx, name);
		LispSymbol methods = tableGlobal(key, "%methods");
		LispVal lambda = methodLambda(ctx, items.get(3), items.subList(4, items.size()));
		LispVal keyDatum = items.get(2);
		if (keyDatum instanceof LispSymbol s && isObjectClassName(ctx, s.name())) {
			// the table row (for get-method) and the catch-all slot the dispatcher
			// tries past the hierarchy search but ahead of the default
			LispSymbol object = tableGlobal(key, "%object");
			return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), List.of(
					ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
									ClojureCollectionLowering.keywordForm("object"), methods),
							lambda),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), object, lambda)));
		}
		return ClojureCollectionLowering.tablePut(methods, dispatchKeyForm(ctx, keyDatum), lambda);
	}

	static LispVal removeMethodOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "remove-method takes a multimethod and a dispatch value");
		String name = ClojureLowerUtil.plainName(items.get(1), "remove-method");
		String key = multimethodKey(ctx, name);
		LispSymbol methods = tableGlobal(key, "%methods");
		LispVal keyDatum = items.get(2);
		if (keyDatum instanceof LispSymbol s && isObjectClassName(ctx, s.name())) {
			LispSymbol object = tableGlobal(key, "%object");
			return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"),
					List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("remhash"),
							ClojureCollectionLowering.keywordForm("object"), methods),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), object, ClojureLowering.NIL_CONST),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowering.varSym(key))));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("remhash"),
						ClojureCollectionLowering.lookupKey(dispatchKeyForm(ctx, keyDatum), methods), methods),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), ClojureLowering.varSym(key)));
	}

	static LispVal getMethodOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "get-method takes a multimethod and a dispatch value");
		String name = ClojureLowerUtil.plainName(items.get(1), "get-method");
		LispSymbol methods = tableGlobal(multimethodKey(ctx, name), "%methods");
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
				ClojureCollectionLowering.lookupKey(dispatchKeyForm(ctx, items.get(2)), methods), methods,
				ClojureLowering.NIL_CONST);
	}

	/**
	 * {@code (methods name)}: the multimethod's table as a map, a copy the later
	 * {@code defmethod} and {@code remove-method} do not reach, with the nil marker row
	 * keyed by nil ({@code rontolisp::%clojure-methods}). The {@code Object} and the
	 * default rows are in it like the oracle's, and a host class's row is under the
	 * keyword {@code class} answers for it.
	 */
	static LispVal methodsOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 2, "methods takes a multimethod");
		String name = ClojureLowerUtil.plainName(items.get(1), "methods");
		String key = multimethodKey(ctx, name);
		if (!ctx.multimethods.contains(key)) {
			throw new LispReadException("No such multimethod: " + name);
		}
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-METHODS"), tableGlobal(key, "%methods"));
	}

	// protocols/records: defprotocol/defrecord/deftype/reify/extend/satisfies? over the
	// table runtime

}
