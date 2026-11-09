package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The {@code java.util.IdentityHashMap} editscript keeps its per-diff caches in
 * ({@code .kb/clojure-frontend.md}, "Portable IdentityHashMap"): an untagged
 * zero-argument construction is the {@code (:C%IDENTITY-MAP table)} list over an
 * {@code eq} table on EVERY backend, and its {@code put}, {@code get},
 * {@code containsKey}, {@code remove}, {@code size}, {@code isEmpty} and {@code clear}
 * answer through {@code clojure.lisp}'s workers. Where the host is, the host map missed
 * every lookup of a Clojure key, which the {@code java:} boundary converts into a new
 * Java object on each crossing; here no key crosses. A receiver hinted
 * {@code ^IdentityHashMap} is treated as unknown, so the arm sees it.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojurePortableIdentityMapLowering {

	/** The host class the construction names. */
	static final String IDENTITY_MAP_CLASS = "java.util.IdentityHashMap";

	/** Whether a value is a portable identity map. */
	static final String IDENTITY_MAP_P = "RONTOLISP::%CLOJURE-IDENTITY-MAP-P";

	/** An empty portable identity map: the family's producer. */
	static final String IDENTITY_MAP_NEW = "RONTOLISP::%CLOJURE-IDENTITY-MAP-NEW";

	/**
	 * An empty portable map keying by {@code =}, a {@code java.util.WeakHashMap} where no
	 * host is: the family's other producer.
	 */
	static final String IDENTITY_MAP_NEW_EQUAL = "RONTOLISP::%CLOJURE-IDENTITY-MAP-NEW-EQUAL";

	/**
	 * The host class whose construction is the map keying by {@code =} where no host is.
	 */
	static final String WEAK_HASH_MAP_CLASS = "java.util.WeakHashMap";

	/**
	 * The {@code java.util.Collections} wrappers that answer their argument where no host
	 * is: no other thread exists there to synchronize with.
	 */
	static final Set<String> SYNCHRONIZED_WRAPPERS = Set.of("synchronizedMap", "synchronizedList", "synchronizedSet",
			"synchronizedCollection", "synchronizedSortedMap", "synchronizedSortedSet", "synchronizedNavigableMap",
			"synchronizedNavigableSet");

	/** The prefix of the library definitions only the family reaches. */
	static final String OWNED = "RONTOLISP::%CLOJURE-IDENTITY-MAP";

	/** The members by the argument counts each takes. */
	static final Map<String, Set<Integer>> METHODS = Map.of("put", Set.of(2), "get", Set.of(1), "containsKey",
			Set.of(1), "remove", Set.of(1), "size", Set.of(0), "isEmpty", Set.of(0), "clear", Set.of(0));

	private ClojurePortableIdentityMapLowering() {
	}

	/**
	 * An untagged {@code java.util.IdentityHashMap} construction as the portable map: the
	 * caller checks it is untagged. Null for any other class or count (a capacity or a
	 * copy keeps {@code java:new}).
	 * @param cls the resolved class name
	 * @param argCount the argument count
	 * @return the construction, or null
	 */
	static @Nullable LispVal construction(String cls, int argCount) {
		if (argCount != 0 || !IDENTITY_MAP_CLASS.equals(cls)) {
			return null;
		}
		return ClojureLowerUtil.list(new LispSymbol(IDENTITY_MAP_NEW));
	}

	/**
	 * An untagged zero-argument {@code java.util.WeakHashMap} construction where no host
	 * is (schema's class registry): the portable map keying by {@code =}, which no
	 * collector empties. The caller checks the target and that it is untagged. Null for
	 * any other class or count (a capacity or a copy keeps {@code java:new}).
	 * @param cls the resolved class name
	 * @param argCount the argument count
	 * @return the construction, or null
	 */
	static @Nullable LispVal equalConstruction(String cls, int argCount) {
		if (argCount != 0 || !WEAK_HASH_MAP_CLASS.equals(cls)) {
			return null;
		}
		return ClojureLowerUtil.list(new LispSymbol(IDENTITY_MAP_NEW_EQUAL));
	}

	/**
	 * A {@code java.util.Collections} synchronized wrapper of one collection where no
	 * host is: the collection itself, since no other thread exists there. The caller
	 * checks the target.
	 * @param cls the resolved class name
	 * @param member the static method
	 * @param args the lowered arguments
	 * @return the argument, or null for any other call
	 */
	static @Nullable LispVal synchronizedWrapper(String cls, String member, List<LispVal> args) {
		if (!cls.equals("java.util.Collections") || args.size() != 1 || !SYNCHRONIZED_WRAPPERS.contains(member)) {
			return null;
		}
		return args.get(0);
	}

	/**
	 * Whether an instance call of the method and count may reach a portable map.
	 * @param method the method name
	 * @param argCount the argument count
	 * @return {@code true} for a member the map answers
	 */
	static boolean answers(String method, int argCount) {
		Set<Integer> counts = METHODS.get(method);
		return counts != null && counts.contains(argCount);
	}

	/**
	 * An instance call of a member a portable map answers: the map answers its worker,
	 * anything else what the call lowered to. The caller checks {@link #answers}.
	 * @param ctx the hub
	 * @param method the method name
	 * @param recv the bound receiver
	 * @param args the lowered arguments
	 * @param otherwise the form for any other receiver
	 * @return the gated call
	 */
	static LispVal arm(ClojureLowering ctx, String method, LispSymbol recv, List<LispVal> args, LispVal otherwise) {
		String worker = switch (method) {
			case "put" -> "RONTOLISP::%CLOJURE-IDENTITY-MAP-PUT";
			case "get" -> "RONTOLISP::%CLOJURE-IDENTITY-MAP-GET";
			case "containsKey" -> "RONTOLISP::%CLOJURE-IDENTITY-MAP-CONTAINS";
			case "remove" -> "RONTOLISP::%CLOJURE-IDENTITY-MAP-REMOVE";
			case "size" -> "RONTOLISP::%CLOJURE-IDENTITY-MAP-SIZE";
			case "isEmpty" -> "RONTOLISP::%CLOJURE-IDENTITY-MAP-EMPTY";
			case "clear" -> "RONTOLISP::%CLOJURE-IDENTITY-MAP-CLEAR";
			default -> throw new IllegalStateException("no portable identity map member: " + method);
		};
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(worker));
		call.add(recv);
		call.addAll(args);
		LispVal answer = ClojureLowerUtil.list(call);
		if (method.equals("containsKey") || method.equals("isEmpty")) {
			answer = ctx.booleanAnswer(answer);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(new LispSymbol(IDENTITY_MAP_P), recv), answer, otherwise);
	}

}
