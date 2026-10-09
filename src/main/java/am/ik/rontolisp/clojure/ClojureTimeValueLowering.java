package am.ik.rontolisp.clojure;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The host members that spell the values {@code #inst} and {@code #uuid} read as
 * ({@code .kb/clojure-frontend.md}, "Instants and UUIDs"). {@code java.util.Date},
 * {@code java.sql.Timestamp} and {@code java.util.UUID} are values of this front end's
 * own, so a construction of one and the statics {@code UUID/randomUUID} and
 * {@code UUID/fromString} build the value itself, and {@code System/currentTimeMillis}
 * answers the milliseconds those are made of, on every backend -- never a {@code java:}
 * call, which wasm refuses and whose host object no read value is {@code =} to. Such a
 * value crosses into a {@code java:} member as the host object it stands for
 * ({@code %clojure-host-value}; a {@code clojure.instant} Calendar has none), and an
 * instance call on one answers through the rows of {@link ClojureValueMethodLowering} or,
 * where the host is, through that host object ({@link #methodArm}).
 *
 * <p>
 * One slice of {@link ClojureLowering}: a method that lowers further takes the hub as its
 * first argument.
 */
final class ClojureTimeValueLowering {

	private ClojureTimeValueLowering() {
	}

	/**
	 * The classes a construction of which builds a value of this front end's own: a
	 * receiver known to be one may be such a value, so its instance calls keep the value
	 * arms.
	 */
	static final Set<String> CLASSES = Set.of("java.util.Date", "java.sql.Timestamp", "java.util.UUID");

	/** {@code (java.util.Date. x)} of an argument that is no literal integer. */
	static final String NEW_DATE = "RONTOLISP::%CLOJURE-NEW-DATE";

	/** {@code (java.sql.Timestamp. x)}. */
	static final String NEW_TIMESTAMP = "RONTOLISP::%CLOJURE-NEW-TIMESTAMP";

	/** {@code (java.util.UUID/fromString s)}. */
	static final String UUID_FROM_STRING = "RONTOLISP::%CLOJURE-UUID-FROM-STRING";

	/** The host object a Date, Timestamp or UUID made here stands for. */
	static final String HOST = "RONTOLISP::%CLOJURE-TIME-VALUE-HOST";

	/** The Date or Timestamp made here a host one stands for, anything else itself. */
	static final String FROM_HOST = "RONTOLISP::%CLOJURE-TIME-VALUE-FROM-HOST";

	private static final String RANDOM_UUID = "RONTOLISP::%CLOJURE-RANDOM-UUID";

	private static final String LONG_CAST = "RONTOLISP::%CLOJURE-LONG-CAST";

	/**
	 * A construction over already-lowered arguments, untagged or tagged with the
	 * parameter types of the constructor it builds the value of: a Date of no argument
	 * the instant of the current milliseconds, a Date or a Timestamp of one the instant
	 * of those milliseconds, a UUID of two the UUID of the halves, each through the
	 * oracle's {@code longCast}. Null for any other class, count or tag, and for a Date
	 * of a literal string: the deprecated parse stays the host construction.
	 * @param cls the resolved class name
	 * @param args the lowered arguments
	 * @param types the param tags, or null when untagged
	 * @return the construction, or null
	 */
	static @Nullable LispVal construction(String cls, List<LispVal> args, @Nullable List<String> types) {
		switch (cls) {
			case "java.util.Date":
				if (args.isEmpty() && (types == null || types.isEmpty())) {
					return ClojureLowerUtil.list(new LispSymbol(ClojureDefaultReaders.MAKE_INST),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("get-internal-real-time")));
				}
				if (args.size() == 1
						&& (types == null ? !(args.get(0) instanceof LispString) : types.equals(List.of("long")))) {
					LispVal ms = args.get(0);
					return ms instanceof LispInteger
							? ClojureLowerUtil.list(new LispSymbol(ClojureDefaultReaders.MAKE_INST), ms)
							: ClojureLowerUtil.list(new LispSymbol(NEW_DATE), ms);
				}
				return null;
			case "java.sql.Timestamp":
				if (args.size() == 1 && (types == null || types.equals(List.of("long")))) {
					return ClojureLowerUtil.list(new LispSymbol(NEW_TIMESTAMP), args.get(0));
				}
				return null;
			case "java.util.UUID":
				if (args.size() == 2 && (types == null || types.equals(List.of("long", "long")))) {
					return ClojureLowerUtil.list(new LispSymbol(ClojureDefaultReaders.MAKE_UUID), longCast(args.get(0)),
							longCast(args.get(1)));
				}
				return null;
			default:
				return null;
		}
	}

	/**
	 * A half of a UUID as the oracle's {@code longCast} takes it: a literal long as is.
	 */
	private static LispVal longCast(LispVal arg) {
		return arg instanceof LispInteger ? arg : ClojureLowerUtil.list(new LispSymbol(LONG_CAST), arg);
	}

	/**
	 * A static call over already-lowered arguments: {@code UUID/randomUUID} a version 4
	 * UUID, {@code UUID/fromString} the UUID a string spells, and
	 * {@code System/currentTimeMillis} the milliseconds since the epoch
	 * ({@code get-internal-real-time}, the wall clock on every backend). Null for any
	 * other member or count.
	 * @param cls the resolved class name
	 * @param member the static member
	 * @param args the lowered arguments
	 * @return the call, or null
	 */
	static @Nullable LispVal staticCall(String cls, String member, List<LispVal> args) {
		if (cls.equals("java.util.UUID") && member.equals("randomUUID") && args.isEmpty()) {
			return ClojureLowerUtil.list(new LispSymbol(RANDOM_UUID));
		}
		if (cls.equals("java.util.UUID") && member.equals("fromString") && args.size() == 1) {
			return ClojureLowerUtil.list(new LispSymbol(UUID_FROM_STRING), args.get(0));
		}
		if (cls.equals("java.lang.System") && member.equals("currentTimeMillis") && args.isEmpty()) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("get-internal-real-time"));
		}
		return null;
	}

	/**
	 * The host construction of what a lowered argument of a {@code java:} member makes,
	 * when it is a Date, Timestamp or UUID construction or static (a literal's included):
	 * the value made here would only cross into the member as the host object, so the
	 * member is handed the one the oracle builds, which the site resolves on -- and the
	 * program makes no value of the kind for it. {@code (Date. x)} of an argument that is
	 * no literal is the host's own constructor choice ({@code Date(String)} the
	 * deprecated parse, as in the oracle). Null for any other form.
	 * @param form the lowered argument
	 * @return the host construction, or null
	 */
	static @Nullable LispVal hostConstruction(LispVal form) {
		List<LispVal> items = ClojureLowerUtil.items(form);
		if (items == null || items.isEmpty() || !(items.get(0) instanceof LispSymbol head)) {
			return null;
		}
		List<LispVal> args = items.subList(1, items.size());
		switch (head.name()) {
			case ClojureDefaultReaders.MAKE_INST:
				if (args.size() != 1) {
					return null;
				}
				if (args.get(0) instanceof LispInteger) {
					return javaNew("java.util.Date(long)", args);
				}
				List<LispVal> now = ClojureLowerUtil.items(args.get(0));
				return now != null && now.size() == 1
						&& ClojureLowerUtil.isSymbolNamed(now.get(0), "GET-INTERNAL-REAL-TIME")
								? javaNew("java.util.Date", List.of()) : null;
			case NEW_DATE:
				return args.size() == 1 ? javaNew("java.util.Date", args) : null;
			case NEW_TIMESTAMP:
				return args.size() == 1 ? javaNew("java.sql.Timestamp(long)",
						List.of(ClojureLowerUtil.list(new LispSymbol(LONG_CAST), args.get(0)))) : null;
			case ClojureDefaultReaders.MAKE_UUID:
				return args.size() == 2 ? javaNew("java.util.UUID(long,long)", args) : null;
			case RANDOM_UUID:
				return args.isEmpty() ? ClojureLowerUtil.list(ClojureInteropLowering.JAVA_STATIC,
						LispString.literal("java.util.UUID"), LispString.literal("randomUUID")) : null;
			default:
				return null;
		}
	}

	private static LispVal javaNew(String designator, List<LispVal> args) {
		List<LispVal> parts = new ArrayList<>();
		parts.add(LispString.literal(designator));
		parts.addAll(args);
		return ClojureLowerUtil.cons(ClojureInteropLowering.JAVA_NEW, parts);
	}

	/**
	 * A kind of value made here, by the host class it is the oracle's value of, its arm
	 * test, and whether a host object stands for it ({@code %clojure-time-value-host}): a
	 * Calendar, which only {@code clojure.instant} reads, has none, so it never crosses
	 * into a {@code java:} member and its class's other methods are refused by name.
	 */
	private record Kind(String host, String test, boolean crosses) {

	}

	/** The kinds, each a test of the instant or the UUID family. */
	private static final List<Kind> KINDS = List.of(new Kind("java.util.Date", ClojurePredicateLowering.DATE_P, true),
			new Kind("java.sql.Timestamp", ClojurePredicateLowering.TIMESTAMP_P, true),
			new Kind("java.util.GregorianCalendar", ClojurePredicateLowering.CALENDAR_P, false),
			new Kind("java.util.UUID", ClojurePredicateLowering.UUID_P, true));

	/** Every kind's test: what an arm taking every value answers. */
	static final Set<String> ALL = Set.of(ClojurePredicateLowering.DATE_P, ClojurePredicateLowering.TIMESTAMP_P,
			ClojurePredicateLowering.CALENDAR_P, ClojurePredicateLowering.UUID_P);

	/**
	 * The public instance methods of a name and parameter count of a host class: none,
	 * every one void (a mutator, whose effect would land on a copy), or some answering a
	 * value, perhaps a Date (a {@code clone}), which comes back as one made here.
	 */
	private enum Members {

		NONE, MUTATOR, ANSWERS, ANSWERS_DATE

	}

	/**
	 * {@link #members} by class, method and count, which every instance call no row maps
	 * asks of the four JDK classes: the same answer for every program, so it is kept
	 * (cleared past 4096 entries, like the {@code java:} caches).
	 */
	private static final Map<String, Members> MEMBERS = new ConcurrentHashMap<>();

	private static Members members(String host, String method, int count) {
		if (MEMBERS.size() > 4096) {
			MEMBERS.clear();
		}
		return MEMBERS.computeIfAbsent(host + "#" + method + "/" + count, key -> scan(host, method, count));
	}

	private static Members scan(String host, String method, int count) {
		Method[] methods;
		try {
			methods = ClojureHostClasses.load(host).getMethods();
		}
		catch (ReflectiveOperationException | LinkageError | SecurityException _) {
			return Members.NONE;
		}
		Members found = Members.NONE;
		for (Method candidate : methods) {
			if (!candidate.getName().equals(method) || Modifier.isStatic(candidate.getModifiers())
					|| candidate.getParameterCount() != count) {
				continue;
			}
			Class<?> answer = candidate.getReturnType();
			if (answer == Void.TYPE) {
				found = found == Members.NONE ? Members.MUTATOR : found;
			}
			else if (answer.isAssignableFrom(java.util.Date.class)) {
				found = Members.ANSWERS_DATE;
			}
			else if (found != Members.ANSWERS_DATE) {
				found = Members.ANSWERS;
			}
		}
		return found;
	}

	/**
	 * The arms of an instance call for an instant or a UUID made here that the rows did
	 * not answer, ahead of {@code otherwise}: where the host is, a kind whose class has
	 * the method calls it on the host object the value stands for, a Date it answers
	 * coming back as one made here ({@code (.toInstant d)}, {@code (.clone d)}); a kind
	 * whose class lacks it is refused in the oracle's words ({@code No matching field
	 * found}); a mutator, a Calendar's method and any method where the host is not
	 * (wasm), by name, since the class has it. Each arm's test is a family test, so a
	 * program making no such value folds back to {@code otherwise}.
	 * @param ctx the hub
	 * @param method the method name
	 * @param designator the method as {@code java:call} names it
	 * @param recv the bound receiver
	 * @param args the lowered (or bound) arguments
	 * @param answered the tests of the kinds a row answered already
	 * @param otherwise the form for any other receiver
	 * @return the arms around {@code otherwise}, or {@code otherwise} itself
	 */
	static LispVal methodArm(ClojureLowering ctx, String method, String designator, LispSymbol recv, List<LispVal> args,
			Set<String> answered, LispVal otherwise) {
		List<String> hosted = new ArrayList<>();
		List<String> unsupported = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		boolean answersDate = false;
		for (Kind kind : KINDS) {
			if (answered.contains(kind.test())) {
				continue;
			}
			Members found = members(kind.host(), method, args.size());
			if (found == Members.NONE) {
				missing.add(kind.test());
			}
			else if (found == Members.MUTATOR || !kind.crosses() || !ctx.hostTarget) {
				unsupported.add(kind.test());
			}
			else {
				hosted.add(kind.test());
				answersDate |= found == Members.ANSWERS_DATE;
			}
		}
		LispVal out = otherwise;
		if (!missing.isEmpty()) {
			out = arm(missing, recv, ClojureValueMethodLowering.refusal(recv, method, true, args), out);
		}
		if (!unsupported.isEmpty()) {
			out = arm(unsupported, recv, ClojureValueMethodLowering.refusal(recv, method, false, args), out);
		}
		if (!hosted.isEmpty()) {
			List<LispVal> parts = new ArrayList<>();
			parts.add(ClojureLowerUtil.list(new LispSymbol(HOST), recv));
			parts.add(LispString.literal(designator));
			parts.addAll(args);
			LispVal call = ClojureInteropLowering.hostCall(ctx, ClojureInteropLowering.JAVA_CALL, parts, 2);
			out = arm(hosted, recv, answersDate ? ClojureLowerUtil.list(new LispSymbol(FROM_HOST), call) : call, out);
		}
		return out;
	}

	/** {@code (if (or (test recv)...) then otherwise)}. */
	private static LispVal arm(List<String> tests, LispSymbol recv, LispVal then, LispVal otherwise) {
		List<LispVal> checks = new ArrayList<>();
		for (String test : tests) {
			checks.add(ClojureLowerUtil.list(new LispSymbol(test), recv));
		}
		LispVal test = checks.size() == 1 ? checks.get(0) : ClojureLowerUtil.cons(ClojureLowerUtil.sym("or"), checks);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), test, then, otherwise);
	}

}
