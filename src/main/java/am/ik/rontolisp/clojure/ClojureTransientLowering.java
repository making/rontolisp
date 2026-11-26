package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Transients in the lowering: {@code transient}, {@code persistent!} and the bang verbs
 * ({@code conj!}, {@code assoc!}, {@code dissoc!}, {@code disj!}, {@code pop!}), each one
 * call to its {@code clojure.lisp} worker ("Transients") after an arity check worded like
 * the oracle's, and as a value that worker's {@code -v} entry. The value and its runtime
 * are {@code clojure.lisp}'s; every shared verb reads one through the transient family's
 * arm tests ({@link ClojureArms.Family#TRANSIENT}), so a program making none compiles as
 * before transients.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureTransientLowering {

	private static final String PREFIX = "RONTOLISP::%CLOJURE-";

	/** The family's arm test: whether a value is a transient. */
	static final String TRANSIENT_P = PREFIX + "TRANSIENT-P";

	/** {@code instance?}'s test of a transient vector. */
	static final String VECTOR_P = PREFIX + "TRANSIENT-VECTOR-P";

	/** {@code instance?}'s test of a transient map. */
	static final String MAP_P = PREFIX + "TRANSIENT-MAP-P";

	/** {@code instance?}'s test of a transient set. */
	static final String SET_P = PREFIX + "TRANSIENT-SET-P";

	/** {@code (transient coll)}. */
	static final String TRANSIENT = PREFIX + "TRANSIENT";

	/**
	 * {@code indexed?}'s helper: {@code %clojure-is-indexed}'s answer or a vector
	 * transient.
	 */
	static final String IS_INDEXED = PREFIX + "IS-INDEXED-TRANSIENT";

	/**
	 * {@code indexed?}'s helper to the one it stands for where the program makes no
	 * transient.
	 */
	static final Map<String, String> ALIASES = Map.of(IS_INDEXED, PREFIX + "IS-INDEXED");

	/**
	 * What makes a transient: {@code transient} as a call and a value, and
	 * {@code conj!}'s value, whose call of no argument answers a fresh transient vector
	 * (the call lowers to {@code transient} itself).
	 */
	static final Set<String> PRODUCERS = Set.of(TRANSIENT, TRANSIENT + "-V", PREFIX + "TRANSIENT-CONJ-V");

	/** Each verb's worker, its {@code -v} entry its value. */
	private static final Map<String, String> WORKERS = Map.of("transient", TRANSIENT, "persistent!",
			PREFIX + "PERSISTENT", "conj!", PREFIX + "TRANSIENT-CONJ", "assoc!", PREFIX + "TRANSIENT-ASSOC", "dissoc!",
			PREFIX + "TRANSIENT-DISSOC", "disj!", PREFIX + "TRANSIENT-DISJ", "pop!", PREFIX + "TRANSIENT-POP");

	private ClojureTransientLowering() {
	}

	/**
	 * A transient verb in call position, or null when the name is none of them.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param items the call, head included
	 * @return the lowered call, or null
	 */
	static @Nullable LispVal callOf(ClojureLowering ctx, String name, List<LispVal> items) {
		String worker = WORKERS.get(name);
		if (worker == null) {
			return null;
		}
		int n = items.size() - 1;
		LispSymbol head = new LispSymbol(worker);
		switch (name) {
			case "transient", "persistent!", "pop!":
				ClojureCoreLowering.arity(name, n, 1, 1);
				return ClojureLowerUtil.list(head, ctx.lower(items.get(1)));
			case "conj!":
				ClojureCoreLowering.arity(name, n, 0, 2);
				if (n == 0) {
					// the oracle's (transient [])
					return ClojureLowerUtil.list(new LispSymbol(TRANSIENT),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("vector")));
				}
				return n == 1 ? ctx.lower(items.get(1))
						: ClojureLowerUtil.list(head, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "disj!":
				ClojureCoreLowering.arity(name, n, 1, -1);
				if (n == 1) {
					// the oracle's ([set] set)
					return ctx.lower(items.get(1));
				}
				return rest(ctx, head, items);
			default:
				// assoc! takes a key and a value at least, dissoc! a key
				ClojureCoreLowering.arity(name, n, name.equals("assoc!") ? 3 : 2, -1);
				return rest(ctx, head, items);
		}
	}

	/** {@code (worker target (list args...))}: the target, then the rest in order. */
	private static LispVal rest(ClojureLowering ctx, LispSymbol head, List<LispVal> items) {
		return ClojureLowerUtil.list(head, ctx.lower(items.get(1)),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 2)));
	}

	/**
	 * A transient verb as a function value, or null when the name is none of them.
	 * @param name the Clojure name
	 * @return the value form, or null
	 */
	static @Nullable LispVal valueOf(String name) {
		String worker = WORKERS.get(name);
		return worker == null ? null
				: ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), new LispSymbol(worker + "-V"));
	}

}
